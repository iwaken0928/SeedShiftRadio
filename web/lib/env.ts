export function getApiBaseUrl() {
  return process.env.NEXT_PUBLIC_API_BASE_URL?.trim() || "http://127.0.0.1:8080";
}

export function getAdminToken() {
  const primary = process.env.NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN?.trim();
  if (primary) {
    return primary;
  }
  const legacy = process.env.NEXT_PUBLIC_ADMIN_TOKEN?.trim();
  return legacy || null;
}
