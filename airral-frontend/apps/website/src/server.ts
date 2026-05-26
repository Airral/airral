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

const browserDistFolder = resolve(
  fileURLToPath(new URL('.', import.meta.url)),
  '../browser'
);
const angularApp = new AngularNodeAppEngine({
  allowedHosts: ['airral.com', '*.airral.com', 'localhost', '127.0.0.1'],
});

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

  res.statusCode = 200;
  res.setHeader('Content-Type', contentType);
  res.setHeader(
    'Cache-Control',
    immutableAsset ? 'public, max-age=31536000, immutable' : 'public, max-age=3600'
  );

  if (req.method === 'HEAD') {
    res.end();
    return true;
  }

  createReadStream(filePath)
    .on('error', () => {
      res.statusCode = 500;
      res.end('Internal server error.');
    })
    .pipe(res);

  return true;
}

async function handleRequest(req: IncomingMessage, res: ServerResponse, next?: (err?: unknown) => void): Promise<void> {
  try {
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
