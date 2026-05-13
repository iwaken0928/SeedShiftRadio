#!/bin/sh
set -eu

missing=""
for name in "$@"; do
  eval "value=\${$name:-}"
  if [ -z "$value" ]; then
    missing="${missing} ${name}"
  fi
done

if [ -n "$missing" ]; then
  printf 'Missing required environment variables:%s\n' "$missing" >&2
  exit 1
fi
