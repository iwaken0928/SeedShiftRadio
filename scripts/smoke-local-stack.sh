#!/usr/bin/env bash

set -euo pipefail

api_base_url="${SEEDSHIFT_SMOKE_API_BASE_URL:-http://127.0.0.1:8080}"
web_base_url="${SEEDSHIFT_SMOKE_WEB_BASE_URL:-http://127.0.0.1:3000}"
worker_base_url="${SEEDSHIFT_SMOKE_WORKER_BASE_URL:-http://127.0.0.1:8000}"
admin_password="${SEEDSHIFT_WEB_ADMIN_PASSWORD:-}"
retries="${SEEDSHIFT_SMOKE_RETRIES:-30}"
sleep_seconds="${SEEDSHIFT_SMOKE_SLEEP_SECONDS:-2}"

poll_url() {
  local label="$1"
  local url="$2"
  local header="${3:-}"
  local attempt

  for attempt in $(seq 1 "${retries}"); do
    if [[ -n "${header}" ]]; then
      if curl --fail --silent --show-error --location -H "${header}" "${url}" >/dev/null; then
        printf '[ok] %s -> %s\n' "${label}" "${url}"
        return 0
      fi
    else
      if curl --fail --silent --show-error --location "${url}" >/dev/null; then
        printf '[ok] %s -> %s\n' "${label}" "${url}"
        return 0
      fi
    fi
    sleep "${sleep_seconds}"
  done

  printf '[ng] %s -> %s\n' "${label}" "${url}" >&2
  return 1
}

poll_url "MusicGen worker health" "${worker_base_url}/health"
poll_url "Server health" "${api_base_url}/api/health"
poll_url "Radio status" "${api_base_url}/api/radio/status"
poll_url "Radio queue" "${api_base_url}/api/radio/queue"
poll_url "Web /" "${web_base_url}/"
poll_url "Web /letters" "${web_base_url}/letters"
poll_url "Web /settings" "${web_base_url}/settings"
poll_url "Web /monitor" "${web_base_url}/monitor"

if [[ -n "${admin_password}" ]]; then
  SEEDSHIFT_SMOKE_WEB_BASE_URL="${web_base_url}" \
    SEEDSHIFT_WEB_ADMIN_PASSWORD="${admin_password}" \
    sh ./scripts/smoke-admin-session.sh
fi

printf 'Local stack smoke check completed.\n'
