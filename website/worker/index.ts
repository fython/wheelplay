import { createDownloadHandler } from "./download.ts";

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> };
}

const download = createDownloadHandler();

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/download" || pathname.startsWith("/download/")) {
      return download(request);
    }
    return env.ASSETS.fetch(request);
  },
};
