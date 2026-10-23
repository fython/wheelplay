import { defineConfig } from "astro/config";

export default defineConfig({
  site: "https://wheelplay.gwo.app",
  i18n: { defaultLocale: "zh-CN", locales: ["zh-CN", "en"], routing: { prefixDefaultLocale: false } },
  output: "static",
  // Plain code blocks keep Markdown compatible with the no-inline-style CSP.
  markdown: { syntaxHighlight: false },
  server: { host: "127.0.0.1", port: 4173 },
  // Keep built CSS and JS external to satisfy public/_headers without unsafe-inline.
  build: { inlineStylesheets: "never" },
  vite: { build: { assetsInlineLimit: 0 } },
});
