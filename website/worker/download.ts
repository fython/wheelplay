const REPOSITORY = "fython/wheelplay";
const WORKFLOW = "debug-build.yml";
const ARTIFACT = "wheelplay-debug-apk";
const API = `https://api.github.com/repos/${REPOSITORY}/actions`;
const WORKFLOW_URL = `https://github.com/${REPOSITORY}/actions/workflows/${WORKFLOW}`;
const SUCCESS_TTL = 300;
const FAILURE_TTL = 60;
const TIMEOUT_MS = 5_000;

export interface DownloadEnv {
  /** Optional Worker/Pages secret; never returned to the browser. */
  GITHUB_TOKEN?: string;
}

export interface DownloadContext {
  waitUntil(promise: Promise<unknown>): void;
}

export interface DownloadCache {
  match(request: Request): Promise<Response | undefined>;
  put(request: Request, response: Response): Promise<void>;
}

interface Result {
  status: 302 | 404 | 503;
  location?: string;
  message?: string;
  validUntil: number;
}

interface Run {
  id: number;
  head_branch: string;
  status: string;
  conclusion: string;
}

interface Artifact {
  id: number;
  name: string;
  expired: boolean;
  expires_at: string;
}

class GitHubUnavailable extends Error {
  retryAfter: number;
  constructor(retryAfter = FAILURE_TTL) {
    super("GitHub metadata unavailable");
    this.retryAfter = retryAfter;
  }
}

const validId = (id: number) => Number.isSafeInteger(id) && id > 0;

/** One bounded memory record and one in-flight lookup per isolate; no user-keyed map. */
export function createDownloadHandler({
  fetch: fetchMetadata = globalThis.fetch,
  now = Date.now,
}: {
  fetch?: typeof globalThis.fetch;
  now?: () => number;
} = {}) {
  let memory: Result | undefined;
  let pending: Promise<Result> | undefined;

  async function github<T>(path: string, env: DownloadEnv): Promise<T> {
    const headers: Record<string, string> = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2026-03-10",
      "User-Agent": "WheelPlay-Download-Resolver",
    };
    if (env.GITHUB_TOKEN) headers.Authorization = `Bearer ${env.GITHUB_TOKEN}`;
    const response = await fetchMetadata(`${API}/${path}`, {
      headers,
      signal: AbortSignal.timeout(TIMEOUT_MS),
      // workerd supports manual/follow, not the browser-only "error" mode.
      redirect: "manual",
      cache: "no-store",
    });
    if (!response.ok) {
      // Respect primary/secondary rate limits without repeatedly parsing error bodies.
      const retry = response.headers.get("Retry-After");
      const retrySeconds = retry
        ? /^\d+$/.test(retry)
          ? Number(retry)
          : (Date.parse(retry) - now()) / 1_000
        : 0;
      const resetSeconds =
        response.headers.get("X-RateLimit-Remaining") === "0"
          ? Number(response.headers.get("X-RateLimit-Reset")) - now() / 1_000
          : 0;
      const backoff = Math.max(
        FAILURE_TTL,
        retrySeconds || 0,
        resetSeconds || 0,
      );
      await response.body?.cancel();
      throw new GitHubUnavailable(Math.min(3_600, Math.ceil(backoff)));
    }
    return response.json() as Promise<T>;
  }

  function failure(
    status: 404 | 503,
    message: string,
    ttl = FAILURE_TTL,
  ): Result {
    return { status, message, validUntil: now() + ttl * 1_000 };
  }

  async function resolve(env: DownloadEnv): Promise<Result> {
    try {
      const runs = await github<{ workflow_runs: Run[] }>(
        `workflows/${WORKFLOW}/runs?branch=main&status=success&per_page=1`,
        env,
      );
      if (!Array.isArray(runs.workflow_runs)) throw new GitHubUnavailable();
      const run = runs.workflow_runs[0];
      if (!run) return failure(404, "暂时没有 main 分支的成功构建。");
      if (
        !validId(run.id) ||
        run.head_branch !== "main" ||
        run.status !== "completed" ||
        run.conclusion !== "success"
      ) {
        throw new GitHubUnavailable();
      }
      // GitHub filters by exact name; retain the local check and bound the result size.
      const result = await github<{ artifacts: Artifact[] }>(
        `runs/${run.id}/artifacts?name=${ARTIFACT}&per_page=100&direction=desc`,
        env,
      );
      if (!Array.isArray(result.artifacts)) throw new GitHubUnavailable();
      const artifact = result.artifacts.find(
        (item) =>
          item.name === ARTIFACT &&
          item.expired === false &&
          validId(item.id) &&
          Date.parse(item.expires_at) > now(),
      );
      if (!artifact)
        return failure(
          404,
          "最新成功构建中的 wheelplay-debug-apk 不存在或已过期，请稍后再试。",
        );
      return {
        status: 302,
        // Construct the UI URL, never request archive_download_url or relay ZIP/APK bytes.
        location: `https://github.com/${REPOSITORY}/actions/runs/${run.id}/artifacts/${artifact.id}`,
        validUntil: Math.min(
          now() + SUCCESS_TTL * 1_000,
          Date.parse(artifact.expires_at),
        ),
      };
    } catch (error) {
      return failure(
        503,
        "暂时无法查询 GitHub 构建，请稍后重试，或手动打开构建列表。",
        error instanceof GitHubUnavailable ? error.retryAfter : FAILURE_TTL,
      );
    }
  }

  async function lookup(
    env: DownloadEnv,
    ctx: DownloadContext,
    cache: DownloadCache,
    key: Request,
  ): Promise<Result> {
    try {
      const cached = await cache.match(key);
      if (cached) {
        const result = (await cached.json()) as Result;
        if (result.validUntil > now()) return result;
      }
    } catch {
      // Cache eviction or cache service failure must not break the download entry.
    }
    const result = await resolve(env);
    const ttl = Math.floor((result.validUntil - now()) / 1_000);
    if (ttl > 0) {
      // Store a tiny metadata response, including failures, rather than a large API body.
      const cached = Response.json(result, {
        headers: { "Cache-Control": `public, max-age=${ttl}` },
      });
      ctx.waitUntil(cache.put(key, cached).catch(() => {}));
    }
    return result;
  }

  return async function download(
    request: Request,
    env: DownloadEnv,
    ctx: DownloadContext,
    cache: DownloadCache,
  ): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname !== "/download" && url.pathname !== "/download/") {
      return new Response("Not found", { status: 404 });
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", {
        status: 405,
        headers: { Allow: "GET, HEAD" },
      });
    }
    let result = memory;
    if (!result || result.validUntil <= now()) {
      if (!pending) {
        // Ignore query strings, cookies and client cache headers so they cannot bust the cache.
        const key = new Request(
          new URL("/__wheelplay/download-cache/v1", url.origin),
          { method: "GET" },
        );
        pending = lookup(env, ctx, cache, key).then(
          (value) => (memory = value),
        );
      }
      const current = pending;
      try {
        result = await current;
      } finally {
        if (pending === current) pending = undefined;
      }
    }
    const headers: Record<string, string> = {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
    };
    if (result.status === 302 && result.location) {
      headers.Location = result.location;
      return new Response(null, { status: 302, headers });
    }
    headers["Content-Type"] = "text/plain; charset=utf-8";
    headers["Retry-After"] = String(
      Math.max(1, Math.ceil((result.validUntil - now()) / 1_000)),
    );
    const body = `${result.message}\n\nGitHub 构建列表：${WORKFLOW_URL}\n`;
    return new Response(request.method === "HEAD" ? null : body, {
      status: result.status,
      headers,
    });
  };
}
