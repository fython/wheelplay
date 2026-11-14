/** Public download entry; it always points at the GitHub Releases page. */
const RELEASES_URL = "https://github.com/fython/wheelplay/releases";

/** Release URLs are stable, so a short public cache is safe and keeps visits cheap. */
const CACHE_TTL_SECONDS = 300;

const RESPONSE_HEADERS: Record<string, string> = {
  "Cache-Control": `public, max-age=${CACHE_TTL_SECONDS}`,
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
};

/**
 * The website used to resolve the latest `debug-build.yml` artifact through the GitHub
 * API. The entry is now a constant redirect: no API calls, no token, no artifact expiry,
 * and no translated error pages.
 */
export function createDownloadHandler(): (request: Request) => Response {
  return function download(request: Request): Response {
    const { pathname } = new URL(request.url);
    if (pathname !== "/download" && pathname !== "/download/") {
      return new Response("Not found", { status: 404 });
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", {
        status: 405,
        headers: { Allow: "GET, HEAD" },
      });
    }
    return new Response(null, {
      status: 302,
      headers: { ...RESPONSE_HEADERS, Location: RELEASES_URL },
    });
  };
}
