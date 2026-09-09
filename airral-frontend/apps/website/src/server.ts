import {
  AngularNodeAppEngine,
  createNodeRequestHandler,
  isMainModule,
  writeResponseToNodeResponse,
} from '@angular/ssr/node';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { extname, isAbsolute, join, relative, resolve } from 'node:path';
import { env } from 'node:process';
import { fileURLToPath } from 'node:url';
import { createGzip } from 'node:zlib';

const browserDistFolder = resolve(
  fileURLToPath(new URL('.', import.meta.url)),
  '../browser'
);
// The allowed-host list is security.allowedHosts in project.json, which the
// build compiles into the app-engine manifest the bundler imports ahead of this
// module. AngularNodeAppEngine declares no constructor parameters -- passing the
// list here is a compile error, not a second place to set it. *.run.app has to
// be in that list: an unlisted Host gets a 400 rather than a page, and the
// deploy's smoke test and every rollback check reach the service on its Cloud
// Run URL, not on the custom domain.
const angularApp = new AngularNodeAppEngine();

const contentTypes: Record<string, string> = {
  '.css': 'text/css; charset=utf-8',
  '.gif': 'image/gif',
  '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.txt': 'text/plain; charset=utf-8',
  '.webp': 'image/webp',
  '.xml': 'application/xml; charset=utf-8',
};

/**
 * What nginx's gzip_types listed. Cloud Run does not compress responses on the
 * way out, so moving this app off nginx would otherwise have shipped the whole
 * bundle uncompressed -- several times the bytes, on a marketing site whose
 * only job is to load before the visitor leaves.
 */
const COMPRESSIBLE = /^(?:text\/|application\/(?:javascript|json)|image\/svg)/;

/**
 * Kept in step with nginx/security-headers.conf, which the three portals still
 * get from nginx. The website is the one origin of the four that is public and
 * crawled, so it is the last one that should quietly lose its framing and
 * transport protection just because it changed runtime.
 */
function setSecurityHeaders(res: ServerResponse): void {
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('Strict-Transport-Security', 'max-age=63072000; includeSubDomains');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=()');
  res.setHeader(
    'Content-Security-Policy-Report-Only',
    "default-src 'self'; " +
      "script-src 'self' https://accounts.google.com https://apis.google.com; " +
      "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
      "font-src 'self' https://fonts.gstatic.com data:; " +
      "img-src 'self' data: https:; " +
      "connect-src 'self' https://api.airral.com https://accounts.google.com; " +
      "frame-src https://accounts.google.com; frame-ancestors 'none'; " +
      "base-uri 'self'; form-action 'self'"
  );
}

/**
 * runtime-config.js: the one file a deploy repoints without a rebuild.
 *
 * Under nginx this was written to disk at container start by
 * docker-entrypoint.d/10-runtime-config.sh, which is a hook only the nginx base
 * image has. Rather than reinvent that hook for a Node image, the server
 * answers for the path itself and reads the same variables -- so the file can
 * never be stale relative to the environment, which writing it once at start-up
 * could not promise.
 *
 * The output is deliberately shaped like the shell version's, down to the
 * single quotes: the deploy smoke test greps it for `key: ''` to catch a portal
 * URL nobody set, and that check has to keep working for all four apps.
 *
 * Note what this does NOT give you. A <script> in the document never runs under
 * platform-server, so the server-side render cannot read this file; everything
 * the render itself needs comes from process.env instead.
 */
function runtimeConfigScript(): string {
  // A stray quote or newline in an environment variable would end the string
  // literal and hand the browser a syntax error in place of a config.
  const quote = (name: string) =>
    `'${(env[name] ?? '').replace(/[\\']/g, '\\$&').replace(/[\r\n]/g, '')}'`;

  return [
    'window.AIRRAL_RUNTIME_CONFIG = {',
    `  apiBaseUrl: ${quote('AIRRAL_API_BASE_URL')},`,
    `  websiteUrl: ${quote('AIRRAL_WEBSITE_URL')},`,
    `  applicantUrl: ${quote('AIRRAL_APPLICANT_URL')},`,
    `  hrUrl: ${quote('AIRRAL_HR_URL')},`,
    `  adminUrl: ${quote('AIRRAL_ADMIN_URL')},`,
    `  googleClientId: ${quote('GOOGLE_OAUTH_CLIENT_ID')}`,
    '};',
    '',
  ].join('\n');
}

function serveRuntimeConfig(req: IncomingMessage, res: ServerResponse): boolean {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return false;
  }

  const url = new URL(req.url || '/', 'http://localhost');
  if (url.pathname !== '/runtime-config.js') {
    return false;
  }

  res.statusCode = 200;
  res.setHeader('Content-Type', 'text/javascript; charset=utf-8');
  // For the reason nginx marked it no-store: a cached copy keeps a browser
  // calling the previous environment's API after the service was repointed.
  res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');

  if (req.method === 'HEAD') {
    res.end();
    return true;
  }

  res.end(runtimeConfigScript());
  return true;
}

async function serveStaticFile(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return false;
  }

  const url = new URL(req.url || '/', 'http://localhost');
  if (url.pathname.endsWith('/')) {
    return false;
  }

  const filePath = resolve(browserDistFolder, join('.', decodeURIComponent(url.pathname)));
  const pathFromRoot = relative(browserDistFolder, filePath);
  if (pathFromRoot.startsWith('..') || isAbsolute(pathFromRoot)) {
    return false;
  }

  const fileStats = await stat(filePath).catch(() => undefined);
  if (!fileStats?.isFile()) {
    return false;
  }

  const contentType = contentTypes[extname(filePath).toLowerCase()] || 'application/octet-stream';
  const immutableAsset = /\.[a-z0-9]{8,}\./i.test(filePath);
  const compressible = COMPRESSIBLE.test(contentType);
  const gzip = compressible && /\bgzip\b/.test(String(req.headers['accept-encoding'] ?? ''));

  res.statusCode = 200;
  res.setHeader('Content-Type', contentType);
  res.setHeader(
    'Cache-Control',
    immutableAsset ? 'public, max-age=31536000, immutable' : 'public, max-age=3600'
  );
  if (compressible) {
    // On every response the encoding could have applied to, not only the ones
    // it did, which is what nginx's gzip_vary did. Announcing it only on the
    // compressed copy lets a shared cache key the asset without it and then
    // hand that copy to a client that did not ask for gzip.
    res.setHeader('Vary', 'Accept-Encoding');
  }
  if (gzip) {
    res.setHeader('Content-Encoding', 'gzip');
  }

  if (req.method === 'HEAD') {
    res.end();
    return true;
  }

  const file = createReadStream(filePath).on('error', () => {
    res.statusCode = 500;
    res.end('Internal server error.');
  });

  if (gzip) {
    // pipe() does not forward errors, so a compressor that fails would
    // otherwise leave the response open until Cloud Run times the request out.
    file.pipe(createGzip().on('error', () => res.destroy())).pipe(res);
  } else {
    file.pipe(res);
  }

  return true;
}

async function handleRequest(req: IncomingMessage, res: ServerResponse, next?: (err?: unknown) => void): Promise<void> {
  try {
    setSecurityHeaders(res);

    if (serveRuntimeConfig(req, res)) {
      return;
    }

    if (await serveStaticFile(req, res)) {
      return;
    }

    const response = await angularApp.handle(req);
    if (response) {
      await writeResponseToNodeResponse(response, res);
      return;
    }

    if (next) {
      next();
      return;
    }

    res.statusCode = 404;
    res.end('Not found.');
  } catch (error) {
    if (next) {
      next(error);
      return;
    }

    res.statusCode = 500;
    res.end('Internal server error.');
  }
}

if (isMainModule(import.meta.url) || env['pm_id']) {
  const port = Number(env['PORT'] || 4000);
  createServer((req, res) => void handleRequest(req, res)).listen(port, () => {
    console.log(`AIRRAL website SSR listening on http://localhost:${port}`);
  });
}

export const reqHandler = createNodeRequestHandler(handleRequest);
