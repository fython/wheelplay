import { createDownloadHandler } from "../worker/download.ts";

const download = createDownloadHandler();

// Pages Functions run on Workers; reuse the same entry without an Astro SSR adapter.
export function onRequest(context: { request: Request }): Response {
  return download(context.request);
}
