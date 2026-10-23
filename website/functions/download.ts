import { createDownloadHandler } from "../worker/download.ts";
import type {
  DownloadEnv,
  DownloadContext,
  DownloadCache,
} from "../worker/download.ts";

const download = createDownloadHandler();

// Pages Functions run on Workers; reuse the resolver without an Astro SSR adapter.
export function onRequest(
  context: DownloadContext & { request: Request; env: DownloadEnv },
) {
  const cache = (caches as CacheStorage & { default: DownloadCache }).default;
  return download(context.request, context.env, context, cache);
}
