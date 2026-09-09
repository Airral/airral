# Angular app for Cloud Run, parameterised by APP so the same file builds any of
# the four frontends.
#
# Three of them are static bundles behind nginx. The website is not, any more.
# None of its ~16,000 job URLs was indexable: a crawler asking for one was
# handed the homepage shell -- the homepage <title>, a canonical pointing at
# "/", no JobPosting -- so every job page declared itself a duplicate of one
# page and Google kept exactly one. Fixing that means rendering the page before
# it is sent, which needs a Node runtime rather than a file server.
#
# Which runtime an app gets is decided by APP alone, through the runtime-<APP>
# aliases at the bottom. `docker build --build-arg APP=<nx project>` therefore
# stays the whole interface, and gcp-build-image.yml did not have to learn which
# of the four render on the server.
#
# Build with:  docker build --build-arg APP=website -t airral-website airral-frontend
# APP is an nx project name: website, applicant-portal, hr-portal, admin-portal.
ARG APP=website

FROM node:20.19.0-bookworm-slim AS builder

WORKDIR /app

# Manifests first so the dependency layer caches independently of source edits.
COPY package.json yarn.lock .yarnrc.yml ./
COPY .yarn ./.yarn
RUN corepack enable && yarn install --immutable

COPY tsconfig.base.json nx.json eslint.config.mjs ./
COPY apps ./apps
COPY libs ./libs

ARG APP
# The website prerenders 14 marketing routes at build time, and PORTAL_ROUTES in
# libs/shared-utils resolves at module load: with no window and no AIRRAL_ENV it
# took the development branch and baked http://localhost:4201/login into the
# "Sign in" and "Get started" links of every prerendered page, the homepage
# included. AIRRAL_ENV rather than NODE_ENV because only this codebase reads it,
# so flipping it cannot change how yarn, nx or esbuild behave.
ENV AIRRAL_ENV=production
RUN NX_DAEMON=false NX_TUI=false yarn nx build "$APP" --configuration=production

# ---------------------------------------------------------------------------
# Runtime: static, for the three portals
#
# The entrypoint writes runtime-config.js from environment variables at
# container start, so one image can be repointed at a different API or a
# different sibling portal without a rebuild -- which a static host cannot do.
# ---------------------------------------------------------------------------
FROM nginx:alpine AS runtime-static

ARG APP

# Templates are rendered by the base image's entrypoint, which substitutes
# ${PORT}; scripts in /docker-entrypoint.d run before nginx starts.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
# Not a template: it contains no variables, and putting it in templates/ would
# have envsubst rewrite the CSP's $ characters.
COPY nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY docker-entrypoint.d/10-runtime-config.sh /docker-entrypoint.d/10-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/10-runtime-config.sh

COPY --from=builder /app/dist/apps/${APP}/browser /usr/share/nginx/html

# Cloud Run overrides PORT; this is the default for a plain `docker run`.
ENV PORT=8080
EXPOSE 8080

# ---------------------------------------------------------------------------
# Runtime: server-side rendered, for the website
#
# No entrypoint hook and no runtime-config.sh here: /docker-entrypoint.d only
# exists in the nginx image, and src/server.ts answers for /runtime-config.js
# out of the same environment variables instead. Same contract for the deploy,
# one fewer file that can go stale.
# ---------------------------------------------------------------------------
FROM node:20.19.0-bookworm-slim AS runtime-ssr

ARG APP

WORKDIR /app

# No install step and no node_modules: esbuild bundles every dependency into the
# server output, so what the builder produced is the whole application.
# server/server.mjs resolves the browser bundle as ../browser, so the two
# directories have to arrive side by side exactly as they were built.
COPY --from=builder /app/dist/apps/${APP} ./

# NODE_ENV so shared-utils treats an unset AIRRAL_API_BASE_URL as production
# rather than falling back to a localhost API during a server render.
ENV NODE_ENV=production
# Cloud Run overrides PORT; this is the default for a plain `docker run`.
ENV PORT=8080
EXPOSE 8080

CMD ["node", "server/server.mjs"]

# ---------------------------------------------------------------------------
# Which runtime each app gets.
#
# Aliases rather than a second build arg, so a caller still passes only APP and
# cannot pair an app with the wrong runtime. Only the stage the final FROM
# resolves to is built.
# ---------------------------------------------------------------------------
FROM runtime-ssr    AS runtime-website
FROM runtime-static AS runtime-applicant-portal
FROM runtime-static AS runtime-hr-portal
FROM runtime-static AS runtime-admin-portal

FROM runtime-${APP}
