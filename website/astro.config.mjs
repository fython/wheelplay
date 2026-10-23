import { defineConfig } from "astro/config";

export default defineConfig({
  output: "static",
  server: { host: "127.0.0.1", port: 4173 },
  // Keep built CSS and JS external to satisfy public/_headers without unsafe-inline.
  build: { inlineStylesheets: "never" },
  vite: { build: { assetsInlineLimit: 0 } },
});
