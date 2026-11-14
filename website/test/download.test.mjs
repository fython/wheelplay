import assert from "node:assert/strict";
import test from "node:test";
import { createDownloadHandler } from "../worker/download.ts";

const RELEASES = "https://github.com/fython/wheelplay/releases";
const download = createDownloadHandler();

const get = (path = "/download", init) =>
  download(new Request(`https://wheelplay.example${path}`, init));

test("the download entry redirects to the GitHub Releases page", async () => {
  const response = get();
  assert.equal(response.status, 302);
  assert.equal(response.headers.get("Location"), RELEASES);
  assert.equal(await response.text(), "");
  assert.equal(response.headers.get("Cache-Control"), "public, max-age=300");
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
  assert.equal(response.headers.get("Referrer-Policy"), "no-referrer");
});

test("HEAD and trailing-slash requests get the same redirect", async () => {
  for (const [path, init] of [
    ["/download", { method: "HEAD" }],
    ["/download/", {}],
  ]) {
    const response = get(path, init);
    assert.equal(response.status, 302);
    assert.equal(response.headers.get("Location"), RELEASES);
    assert.equal(await response.text(), "");
  }
});

test("query strings and client cache headers cannot change the target", async () => {
  for (const path of [
    "/download?lang=en",
    "/download?force=1",
    "/download/?random=2",
  ]) {
    const response = get(path, { headers: { "Cache-Control": "no-cache" } });
    assert.equal(response.status, 302);
    assert.equal(response.headers.get("Location"), RELEASES);
  }
});

test("unsupported methods and paths are rejected", async () => {
  const post = get("/download", { method: "POST" });
  assert.equal(post.status, 405);
  assert.equal(post.headers.get("Allow"), "GET, HEAD");
  assert.equal(post.headers.get("Location"), null);
  assert.equal(get("/download/anything").status, 404);
});

test("resolving the download never contacts GitHub", () => {
  const original = globalThis.fetch;
  globalThis.fetch = () => {
    throw new Error("the download entry must not query GitHub");
  };
  try {
    const response = createDownloadHandler()(
      new Request("https://wheelplay.example/download"),
    );
    assert.equal(response.headers.get("Location"), RELEASES);
  } finally {
    globalThis.fetch = original;
  }
});
