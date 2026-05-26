#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$BACKEND_DIR"

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

echo "Starting AIRRAL backend with Spring profile: ${SPRING_PROFILES_ACTIVE}"
echo "Local job sources come from src/main/resources/application-local.yml"
echo "API: http://localhost:8080"
echo "Swagger: http://localhost:8080/swagger-ui.html"

exec ./gradlew bootRun --args="--spring.profiles.active=${SPRING_PROFILES_ACTIVE}"
