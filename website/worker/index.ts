import { createDownloadHandler } from "./download.ts";
import type {
  DownloadEnv,
  DownloadContext,
  DownloadCache,
} from "./download.ts";

interface Env extends DownloadEnv {
  ASSETS: { fetch(request: Request): Promise<Response> };
}

const download = createDownloadHandler();

export default {
  async fetch(
    request: Request,
    env: Env,
    ctx: DownloadContext,
  ): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/download" || pathname.startsWith("/download/")) {
      const cache = (caches as CacheStorage & { default: DownloadCache })
        .default;
      return download(request, env, ctx, cache);
    }
    return env.ASSETS.fetch(request);
  },
};
