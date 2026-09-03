#!/bin/sh
# Writes runtime-config.js at container start from the environment, so one image
# can be deployed to any environment without rebuilding. Runs before nginx via
# the base image's /docker-entrypoint.d/ hook.
#
# Portal URLs live here rather than in the bundle because a subdomain that has
# not been pointed yet must be reachable some other way — otherwise every call
# to action on the site links to a hostname that does not resolve.
set -eu

cat > /usr/share/nginx/html/runtime-config.js <<INNER
window.AIRRAL_RUNTIME_CONFIG = {
  apiBaseUrl: '${AIRRAL_API_BASE_URL:-}',
  websiteUrl: '${AIRRAL_WEBSITE_URL:-}',
  applicantUrl: '${AIRRAL_APPLICANT_URL:-}',
  hrUrl: '${AIRRAL_HR_URL:-}',
  adminUrl: '${AIRRAL_ADMIN_URL:-}',
  googleClientId: '${GOOGLE_OAUTH_CLIENT_ID:-}'
};
INNER

echo "runtime-config.js: api='${AIRRAL_API_BASE_URL:-}' applicant='${AIRRAL_APPLICANT_URL:-}' hr='${AIRRAL_HR_URL:-}'"
