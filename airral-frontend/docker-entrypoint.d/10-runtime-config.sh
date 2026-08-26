#!/bin/sh
# Writes runtime-config.js at container start from the environment, so one image
# can be deployed to any environment without rebuilding. Runs before nginx via
# the base image's /docker-entrypoint.d/ hook.
set -eu

cat > /usr/share/nginx/html/runtime-config.js <<INNER
window.AIRRAL_RUNTIME_CONFIG = {
  apiBaseUrl: '${AIRRAL_API_BASE_URL:-}',
  googleClientId: '${GOOGLE_OAUTH_CLIENT_ID:-}'
};
INNER

echo "runtime-config.js: apiBaseUrl='${AIRRAL_API_BASE_URL:-}'"
