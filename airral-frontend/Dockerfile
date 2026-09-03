# Static Angular app served by nginx, for Cloud Run.
#
# Parameterised by APP so the same file builds any of the four frontends. All
# four are deployed this way: the entrypoint writes runtime-config.js from
# environment variables at container start, so one image can be repointed at a
# different API or a different sibling portal without a rebuild -- which a
# static host cannot do.
#
# Build with:  docker build --build-arg APP=website -t airral-website airral-frontend
# APP is an nx project name: website, applicant-portal, hr-portal, admin-portal.
FROM node:20.19.0-bookworm-slim AS builder

WORKDIR /app

# Manifests first so the dependency layer caches independently of source edits.
COPY package.json yarn.lock .yarnrc.yml ./
COPY .yarn ./.yarn
RUN corepack enable && yarn install --immutable

COPY tsconfig.base.json nx.json eslint.config.mjs ./
COPY apps ./apps
COPY libs ./libs

ARG APP=website
RUN NX_DAEMON=false NX_TUI=false yarn nx build "$APP" --configuration=production

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM nginx:alpine

ARG APP=website

# Templates are rendered by the base image's entrypoint, which substitutes
# ${PORT}; scripts in /docker-entrypoint.d run before nginx starts.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker-entrypoint.d/10-runtime-config.sh /docker-entrypoint.d/10-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/10-runtime-config.sh

COPY --from=builder /app/dist/apps/${APP}/browser /usr/share/nginx/html

# Cloud Run overrides PORT; this is the default for a plain `docker run`.
ENV PORT=8080
EXPOSE 8080
