import assert from "node:assert/strict";
import test from "node:test";
import { createDownloadHandler } from "../worker/download.ts";

const START = Date.parse("2026-10-23T12:00:00Z");
const RUN_ID = 39951112470;
const ARTIFACT_ID = 12501440441;
const LOCATION = `https://github.com/fython/wheelplay/actions/runs/${RUN_ID}/artifacts/${ARTIFACT_ID}`;
const run = (id = RUN_ID) => ({
  id,
  head_branch: "main",
  status: "completed",
  conclusion: "success",
});
const artifact = (overrides = {}) => ({
  id: ARTIFACT_ID,
  name: "wheelplay-debug-apk",
  expired: false,
  expires_at: new Date(START + 86_400_000).toISOString(),
  ...overrides,
});

function harness(
  responses = [{ workflow_runs: [run()] }, { artifacts: [artifact()] }],
) {
  let time = START;
  const calls = [];
  const writes = [];
  const stored = new Map();
  const cache = {
    async match(key) {
      return stored.get(key.url)?.clone();
    },
    async put(key, response) {
      stored.set(key.url, response.clone());
    },
  };
  const ctx = {
    waitUntil(promise) {
      writes.push(promise);
    },
  };
  const fetch = async (url, init) => {
    calls.push({ url, init });
    const response = responses.shift();
    if (typeof response === "function") return response();
    if (response instanceof Response) return response;
    assert.ok(response, "Unexpected extra GitHub API request");
    return Response.json(response);
  };
  const makeHandler = () => createDownloadHandler({ fetch, now: () => time });
  const handler = makeHandler();
  return {
    cache,
    ctx,
    calls,
    stored,
    responses,
    makeHandler,
    advance(ms) {
      time += ms;
    },
    async flush() {
      await Promise.all(writes);
    },
    get(path = "/download", options = {}, env = {}) {
      return handler(
        new Request(`https://wheelplay.example${path}`, options),
        env,
        ctx,
        cache,
      );
    },
  };
}

test("cold request makes two bounded metadata calls and returns only the GitHub UI redirect", async () => {
  const h = harness();
  const response = await h.get(
    "/download",
    {},
    { GITHUB_TOKEN: "test-secret" },
  );
  assert.equal(response.status, 302);
  assert.equal(response.headers.get("Location"), LOCATION);
  assert.equal(await response.text(), "");
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(h.calls.length, 2);
  const runs = new URL(h.calls[0].url);
  assert.equal(
    runs.pathname,
    "/repos/fython/wheelplay/actions/workflows/debug-build.yml/runs",
  );
  assert.deepEqual(Object.fromEntries(runs.searchParams), {
    branch: "main",
    status: "success",
    per_page: "1",
  });
  const artifacts = new URL(h.calls[1].url);
  assert.equal(
    artifacts.pathname,
    `/repos/fython/wheelplay/actions/runs/${RUN_ID}/artifacts`,
  );
  assert.equal(artifacts.searchParams.get("name"), "wheelplay-debug-apk");
  assert.equal(artifacts.searchParams.get("per_page"), "100");
  for (const { init } of h.calls) {
    assert.equal(init.headers.Authorization, "Bearer test-secret");
    assert.equal(init.redirect, "manual");
    assert.ok(init.signal instanceof AbortSignal);
  }
  await h.flush();
  const cached = [...h.stored.values()][0];
  assert.equal(cached.headers.get("Cache-Control"), "public, max-age=300");
  assert.equal((await cached.clone().text()).includes("test-secret"), false);
});

test("memory and edge hits ignore query strings, client cache headers, and HEAD method", async () => {
  const h = harness();
  await h.get();
  await h.flush();
  const head = await h.get("/download?force=1", {
    method: "HEAD",
    headers: { "Cache-Control": "no-cache" },
  });
  assert.equal(head.headers.get("Location"), LOCATION);
  assert.equal(await head.text(), "");
  assert.equal(h.calls.length, 2);
  const otherIsolate = h.makeHandler();
  const cached = await otherIsolate(
    new Request("https://wheelplay.example/download/?random=2"),
    {},
    h.ctx,
    h.cache,
  );
  assert.equal(cached.headers.get("Location"), LOCATION);
  assert.equal(h.calls.length, 2);
  assert.equal(h.stored.size, 1);
  assert.match([...h.stored.keys()][0], /\/__wheelplay\/download-cache\/v1$/);
});

test("concurrent clicks share one in-flight query", async () => {
  let release;
  const first = new Promise((resolve) => {
    release = resolve;
  });
  const h = harness([() => first, { artifacts: [artifact()] }]);
  const requests = Array.from({ length: 20 }, (_, i) =>
    h.get(`/download?request=${i}`),
  );
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(h.calls.length, 1);
  release(Response.json({ workflow_runs: [run()] }));
  const results = await Promise.all(requests);
  assert.ok(
    results.every((response) => response.headers.get("Location") === LOCATION),
  );
  assert.equal(h.calls.length, 2);
});

test("five-minute expiry re-resolves the latest successful run", async () => {
  const h = harness();
  await h.get();
  await h.flush();
  h.advance(300_001);
  h.responses.push(
    { workflow_runs: [run(RUN_ID + 1)] },
    { artifacts: [artifact({ id: ARTIFACT_ID + 1 })] },
  );
  const fresh = await h.get();
  assert.equal(h.calls.length, 4);
  assert.equal(
    fresh.headers.get("Location"),
    `https://github.com/fython/wheelplay/actions/runs/${RUN_ID + 1}/artifacts/${ARTIFACT_ID + 1}`,
  );
});

test("cache freshness never exceeds the artifact expiry", async () => {
  const h = harness([
    { workflow_runs: [run()] },
    {
      artifacts: [
        artifact({ expires_at: new Date(START + 2_000).toISOString() }),
      ],
    },
  ]);
  await h.get();
  await h.flush();
  assert.equal(
    [...h.stored.values()][0].headers.get("Cache-Control"),
    "public, max-age=2",
  );
  h.advance(2_001);
  h.responses.push(
    { workflow_runs: [run()] },
    { artifacts: [artifact({ expired: true })] },
  );
  const response = await h.get();
  assert.equal(response.status, 404);
  assert.equal(response.headers.get("Location"), null);
});

test("no successful run is negatively cached for one minute", async () => {
  const h = harness([{ workflow_runs: [] }]);
  assert.equal((await h.get()).status, 404);
  await h.flush();
  const otherIsolate = h.makeHandler();
  assert.equal(
    (
      await otherIsolate(
        new Request("https://wheelplay.example/download"),
        {},
        h.ctx,
        h.cache,
      )
    ).status,
    404,
  );
  assert.equal(h.calls.length, 1);
  h.advance(60_001);
  h.responses.push({ workflow_runs: [run()] }, { artifacts: [artifact()] });
  assert.equal((await h.get()).status, 302);
});

for (const [name, artifacts] of [
  ["missing", []],
  ["wrong name", [artifact({ name: "wheelplay-browser-runtime-tests" })]],
  ["expired flag", [artifact({ expired: true })]],
  [
    "expired timestamp",
    [artifact({ expires_at: new Date(START - 1).toISOString() })],
  ],
  ["invalid timestamp", [artifact({ expires_at: "invalid" })]],
]) {
  test(`${name} artifact does not fall back to an older build`, async () => {
    const h = harness([{ workflow_runs: [run()] }, { artifacts }]);
    assert.equal((await h.get()).status, 404);
    assert.equal((await h.get()).status, 404);
    assert.equal(h.calls.length, 2);
  });
}

test("a similarly named artifact cannot redirect the user elsewhere", async () => {
  const h = harness([
    { workflow_runs: [run()] },
    {
      artifacts: [
        artifact({ name: "wheelplay-debug-apk-malicious", id: 1 }),
        artifact({
          archive_download_url: "https://elsewhere.example/archive.zip",
        }),
      ],
    },
  ]);
  assert.equal((await h.get()).headers.get("Location"), LOCATION);
});

for (const [name, response, expected] of [
  [
    "primary rate limit",
    new Response("rate limit", {
      status: 403,
      headers: {
        "X-RateLimit-Remaining": "0",
        "X-RateLimit-Reset": String(START / 1_000 + 600),
      },
    }),
    600,
  ],
  [
    "secondary rate limit",
    new Response("rate limit", {
      status: 429,
      headers: { "Retry-After": "120" },
    }),
    120,
  ],
  ["unavailable", new Response("upstream details", { status: 500 }), 60],
  [
    "unexpected API redirect",
    new Response(null, {
      status: 302,
      headers: { Location: "https://elsewhere.example/archive.zip" },
    }),
    60,
  ],
  ["invalid JSON", new Response("invalid", { status: 200 }), 60],
  ["invalid shape", Response.json({ message: "not runs" }), 60],
  [
    "timeout",
    () => Promise.reject(new DOMException("Timed out", "TimeoutError")),
    60,
  ],
]) {
  test(`${name} returns a safe cached error and backs off`, async () => {
    const h = harness([response]);
    const result = await h.get();
    assert.equal(result.status, 503);
    assert.equal(result.headers.get("Retry-After"), String(expected));
    assert.equal(result.headers.get("Location"), null);
    assert.doesNotMatch(await result.text(), /upstream details/);
    await h.get();
    assert.equal(h.calls.length, 1);
    await h.flush();
    const otherIsolate = h.makeHandler();
    assert.equal(
      (
        await otherIsolate(
          new Request("https://wheelplay.example/download"),
          {},
          h.ctx,
          h.cache,
        )
      ).status,
      503,
    );
    assert.equal(h.calls.length, 1);
  });
}

test("unusable latest-run metadata never redirects to the wrong branch", async () => {
  const h = harness([
    { workflow_runs: [{ ...run(), head_branch: "feature" }] },
  ]);
  assert.equal((await h.get()).status, 503);
  assert.equal(h.calls.length, 1);
});

test("unsupported methods and paths do not query GitHub", async () => {
  const h = harness();
  assert.equal((await h.get("/download", { method: "POST" })).status, 405);
  assert.equal((await h.get("/download/anything")).status, 404);
  assert.equal(h.calls.length, 0);
});

test("cache service failure leaves a usable in-memory redirect", async () => {
  const h = harness();
  h.cache.match = async () => {
    throw new Error("cache unavailable");
  };
  h.cache.put = async () => {
    throw new Error("cache unavailable");
  };
  assert.equal((await h.get()).headers.get("Location"), LOCATION);
  await h.flush();
  assert.equal((await h.get()).headers.get("Location"), LOCATION);
  assert.equal(h.calls.length, 2);
});

for (const [name, responses, expected] of [
  ["missing run", [{ workflow_runs: [] }], "No successful build"],
  ["missing artifact", [{ workflow_runs: [run()] }, { artifacts: [] }], "missing or expired"],
  ["API failure", [new Response(null, { status: 503 })], "temporarily unavailable"],
]) {
  test(`English ${name} translates a shared cached error without extra API calls`, async () => {
    const h = harness(responses);
    const chinese = await h.get();
    assert.equal(chinese.headers.get("Content-Language"), "zh-CN");
    assert.match(await chinese.text(), /GitHub 构建列表/);
    await h.flush();
    const calls = h.calls.length;
    const otherIsolate = h.makeHandler();
    const english = await otherIsolate(
      new Request("https://wheelplay.example/download?lang=en"), {}, h.ctx, h.cache,
    );
    assert.equal(english.status, chinese.status);
    assert.equal(english.headers.get("Content-Language"), "en");
    const body = await english.text();
    assert.ok(body.includes(expected));
    assert.doesNotMatch(body, /[\u4e00-\u9fff]/);
    assert.equal(h.calls.length, calls);
    assert.equal(h.stored.size, 1);
    const head = await h.get("/download?lang=en", { method: "HEAD" });
    assert.equal(head.headers.get("Content-Language"), "en");
    assert.equal(await head.text(), "");
  });
}
