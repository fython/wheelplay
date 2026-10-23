import assert from "node:assert/strict";
import { readFile, stat } from "node:fs/promises";
import test from "node:test";

const dist = new URL("../dist/", import.meta.url);

for (const page of ["index.html", "404.html", "docs/index.html", "docs/faq/index.html", "en/index.html", "en/docs/index.html", "en/docs/faq/index.html"]) {
  test(`${page} ships external assets compatible with the Cloudflare CSP`, async () => {
    const html = await readFile(new URL(page, dist), "utf8");
    assert.doesNotMatch(
      html,
      /<style\b|\sstyle\s*=|\son\w+\s*=/i,
      "Inline styles and event handlers would be blocked by the deployed CSP",
    );
    const scripts = [
      ...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi),
    ];
    if (page === "index.html")
      assert.ok(scripts.length > 0, "Homepage must load its interactions");
    if (page === "index.html")
      assert.match(
        html,
        /href="\/download"/,
        "Download button must use the resolver",
      );
    const sources = [];
    for (const [, attributes, content] of scripts) {
      assert.equal(content.trim(), "", "Astro must not inline small scripts");
      const source = attributes.match(/\bsrc="([^"]+)"/);
      assert.ok(source, "Scripts must have an external source");
      sources.push(source[1]);
    }
    const styles = [...html.matchAll(/<link\b[^>]*rel="stylesheet"[^>]*>/gi)];
    assert.ok(
      styles.length > 0,
      "Global styles must be emitted for every route",
    );
    sources.push(...styles.map(([tag]) => tag.match(/\bhref="([^"]+)"/)[1]));
    for (const source of sources) {
      assert.match(
        source,
        /^\/_astro\//,
        "Only locally bundled assets may load",
      );
      assert.ok((await stat(new URL(`.${source}`, dist))).isFile(), source);
    }
    if (page === "404.html")
      assert.match(html, /name="robots" content="noindex"/);
    if (page.includes("docs/")) {
      const prefix = page.startsWith("en/") ? "/en" : "";
      assert.equal([...html.matchAll(/<h1\b/g)].length, 1);
      assert.match(html, /aria-current="page"/);
      const headings = [...html.matchAll(/<h2\b[^>]*id="([^"]+)"/g)];
      assert.ok(headings.length > 0, "Markdown headings must be rendered");
      for (const [, id] of headings) {
        assert.ok(html.includes(`href="#${id}"`), `Missing TOC link for ${id}`);
      }
      assert.ok(html.includes(`href="${prefix}/docs/"`));
      assert.ok(html.includes(`href="${prefix}/docs/faq/"`));
    }
    if (page !== "404.html") {
      const english = page.startsWith("en/");
      const path = `/${page.replace(/index\.html$/, "")}`;
      const alternate = english ? path.replace(/^\/en/, "") : `/en${path}`;
      assert.ok(html.includes(`<html lang="${english ? "en" : "zh-CN"}"`));
      assert.ok(html.includes(`rel="canonical" href="https://wheelplay.gwo.app${path}"`));
      assert.ok(html.includes(`hreflang="${english ? "zh-CN" : "en"}" href="https://wheelplay.gwo.app${alternate}"`));
      assert.ok(html.includes(`href="${alternate}" lang="${english ? "zh-CN" : "en"}"`));
      if (english) {
        assert.match(html, /Switch to dark theme/);
        assert.match(html, /href="\/download\?lang=en"|Frequently asked questions/);
      }
    }
  });
}

test("static routes preserve the repository icon, license, and Cloudflare headers", async () => {
  for (const [output, input] of [
    ["assets/app-icon.svg", "../docs/assets/app-icon.svg"],
    [
      "assets/Material-Symbols-Apache-2.0.txt",
      "../docs/licenses/Material-Symbols-Apache-2.0.txt",
    ],
    ["_headers", "public/_headers"],
    ["_routes.json", "public/_routes.json"],
  ]) {
    const expected = new URL(input, new URL("../", import.meta.url));
    assert.deepEqual(
      await readFile(new URL(output, dist)),
      await readFile(expected),
    );
  }
});
