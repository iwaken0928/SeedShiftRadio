const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export function isUnsafeMethod(method: string) {
  return !SAFE_METHODS.has(method.toUpperCase());
}

export function requiresAdminSession(method: string, path: string[]) {
  const normalizedMethod = method.toUpperCase();
  if (path[0] !== "api") return false;
  const resource = path[1];
  if (resource === "settings" || resource === "monitor" || resource === "management" || resource === "program-templates" || resource === "play-history") return true;
  if (resource === "letters") {
    const isSubmission = normalizedMethod === "POST" && path.length === 2;
    const isPublicHistoryLookup = normalizedMethod === "POST" && path.length === 4 && path[2] === "public" && path[3] === "history";
    return !(isSubmission || isPublicHistoryLookup);
  }
  if (resource === "stations") {
    if (normalizedMethod !== "GET") return true;
    return path.includes("programming");
  }
  return false;
}
