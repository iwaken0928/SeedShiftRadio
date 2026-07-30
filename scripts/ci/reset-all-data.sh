#!/bin/sh
set -eu

if [ "${RESET_ALL_DATA_CONFIRMATION:-KEEP}" != "RESET_ALL_DATA" ]; then
  echo "RESET_ALL_DATA_CONFIRMATION=RESET_ALL_DATA が選択されていないため、初期化を中止します。" >&2
  exit 1
fi

: "${SEEDSHIFT_RADIO_DB_HOST:?SEEDSHIFT_RADIO_DB_HOST is required}"
: "${SEEDSHIFT_RADIO_DB_PORT:?SEEDSHIFT_RADIO_DB_PORT is required}"
: "${SEEDSHIFT_RADIO_DB_NAME:?SEEDSHIFT_RADIO_DB_NAME is required}"
: "${SEEDSHIFT_RADIO_DB_USERNAME:?SEEDSHIFT_RADIO_DB_USERNAME is required}"
: "${SEEDSHIFT_RADIO_DB_PASSWORD:?SEEDSHIFT_RADIO_DB_PASSWORD is required}"
: "${SEEDSHIFT_RADIO_DATA_VOLUME:?SEEDSHIFT_RADIO_DATA_VOLUME is required}"
: "${PODMAN_RESET_POSTGRES_IMAGE:?PODMAN_RESET_POSTGRES_IMAGE is required}"
: "${PODMAN_RESET_VOLUME_IMAGE:?PODMAN_RESET_VOLUME_IMAGE is required}"

validate_volume_source() {
  case "$1" in
    ""|"/"|"."|"..")
      echo "危険な永続データ領域が指定されています。" >&2
      exit 1
      ;;
  esac
}

stop_container_if_present() {
  if podman --remote container exists "$1"; then
    podman --remote stop "$1"
  fi
}

reset_volume() {
  volume_source="$1"
  validate_volume_source "${volume_source}"
  podman --remote run --rm \
    --security-opt=label=disable \
    -v "${volume_source}:/reset-target" \
    "${PODMAN_RESET_VOLUME_IMAGE}" \
    sh -c 'find /reset-target -mindepth 1 -depth -delete'
}

server_container="${SEEDSHIFT_SERVER_CONTAINER_NAME:-seedshift-radio-server}"
web_container="${SEEDSHIFT_WEB_CONTAINER_NAME:-seedshift-radio-web}"
musicgen_container="${MUSICGEN_CONTAINER_NAME:-seedshift-radio-musicgen}"

stop_container_if_present "${web_container}"
stop_container_if_present "${server_container}"
if [ -n "${MUSICGEN_DATA_VOLUME:-}" ]; then
  stop_container_if_present "${musicgen_container}"
fi

podman --remote pull "${PODMAN_RESET_POSTGRES_IMAGE}"
podman --remote pull "${PODMAN_RESET_VOLUME_IMAGE}"

export PGPASSWORD="${SEEDSHIFT_RADIO_DB_PASSWORD}"
podman --remote run --rm \
  --network=host \
  --security-opt=label=disable \
  -e PGPASSWORD \
  "${PODMAN_RESET_POSTGRES_IMAGE}" \
  psql \
  --host="${SEEDSHIFT_RADIO_DB_HOST}" \
  --port="${SEEDSHIFT_RADIO_DB_PORT}" \
  --username="${SEEDSHIFT_RADIO_DB_USERNAME}" \
  --dbname="${SEEDSHIFT_RADIO_DB_NAME}" \
  --set=ON_ERROR_STOP=1 \
  --command='DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION CURRENT_USER;'

reset_volume "${SEEDSHIFT_RADIO_DATA_VOLUME}"
if [ -n "${MUSICGEN_DATA_VOLUME:-}" ] && [ "${MUSICGEN_DATA_VOLUME}" != "${SEEDSHIFT_RADIO_DATA_VOLUME}" ]; then
  reset_volume "${MUSICGEN_DATA_VOLUME}"
fi

echo "DB schema と永続データを初期化しました。続けて migration と deploy を実行してください。"
