#!/bin/sh

set -eu

web_base_url="${SEEDSHIFT_SMOKE_WEB_BASE_URL:-http://127.0.0.1:3000}"
admin_password="${SEEDSHIFT_WEB_ADMIN_PASSWORD:-}"
require_secure_cookie="${SEEDSHIFT_SMOKE_REQUIRE_SECURE_COOKIE:-false}"

if [ -z "$admin_password" ]; then
  echo "SEEDSHIFT_WEB_ADMIN_PASSWORD is required for admin session smoke." >&2
  exit 1
fi

web_base_url=${web_base_url%/}
cookie_jar=$(mktemp)
response_body=$(mktemp)
trap 'rm -f "$cookie_jar" "$response_body"' EXIT HUP INT TERM

expect_status() {
  label=$1
  expected=$2
  actual=$3
  if [ "$actual" != "$expected" ]; then
    printf '[ng] %s: expected HTTP %s, got %s\n' "$label" "$expected" "$actual" >&2
    return 1
  fi
  printf '[ok] %s -> HTTP %s\n' "$label" "$actual"
}

status=$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  "$web_base_url/api-proxy/api/settings")
expect_status "Unauthenticated admin proxy rejection" 401 "$status"

escaped_password=$(printf '%s' "$admin_password" | sed 's/\\/\\\\/g; s/"/\\"/g')
status=$(printf '{"password":"%s"}' "$escaped_password" | \
  curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
    --cookie-jar "$cookie_jar" \
    --header "Content-Type: application/json" \
    --header "Origin: $web_base_url" \
    --data-binary @- \
    "$web_base_url/api/auth/login")
expect_status "Admin login" 200 "$status"

session_cookie=$(awk '$6 == "seedshift_admin_session" { print $7 }' "$cookie_jar")
cookie_secure=$(awk '$6 == "seedshift_admin_session" { print $4 }' "$cookie_jar")
if [ -z "$session_cookie" ]; then
  echo "[ng] Admin login did not issue the session cookie." >&2
  exit 1
fi
if [ "$require_secure_cookie" = "true" ] && [ "$cookie_secure" != "TRUE" ]; then
  echo "[ng] Production session cookie did not include Secure." >&2
  exit 1
fi

# Production cookie は Secure のため、Runner 内部の HTTP loopback smoke では
# cookie jar の自動送信に頼らず、取得済み cookie 値を明示して BFF 契約を検証する。
session_body=$(curl --fail --silent --show-error --cookie "seedshift_admin_session=$session_cookie" \
  "$web_base_url/api/auth/session")
csrf_token=$(printf '%s' "$session_body" | sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p')
if [ -z "$csrf_token" ]; then
  echo "[ng] Authenticated session did not return a CSRF token." >&2
  exit 1
fi
echo "[ok] Authenticated session and CSRF token"

status=$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  --cookie "seedshift_admin_session=$session_cookie" \
  "$web_base_url/api-proxy/api/settings")
expect_status "Authenticated admin proxy" 200 "$status"

status=$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  --request POST \
  --cookie "seedshift_admin_session=$session_cookie" \
  --header "X-CSRF-Token: invalid" \
  "$web_base_url/api/auth/logout")
expect_status "Invalid CSRF rejection" 403 "$status"

status=$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  --request POST \
  --cookie "seedshift_admin_session=$session_cookie" \
  --cookie-jar "$cookie_jar" \
  --header "X-CSRF-Token: $csrf_token" \
  "$web_base_url/api/auth/logout")
expect_status "Admin logout" 204 "$status"

session_body=$(curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$web_base_url/api/auth/session")
if ! printf '%s' "$session_body" | grep -q '"authenticated":false'; then
  echo "[ng] Session remained authenticated after logout." >&2
  exit 1
fi
echo "[ok] Session invalidated after logout"
