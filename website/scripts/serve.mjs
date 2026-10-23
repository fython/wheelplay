import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { extname, resolve, sep } from "node:path";
import "./build.mjs";

const root = fileURLToPath(new URL("../dist/", import.meta.url));
const port = Number(process.env.PORT || 4173);
const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".txt": "text/plain; charset=utf-8",
};
const headers = Object.fromEntries(
  (await readFile(resolve(root, "_headers"), "utf8"))
    .split("\n")
    .filter((line) => line.startsWith("  "))
    .map((line) => {
      const colon = line.indexOf(":");
      return [line.slice(0, colon).trim(), line.slice(colon + 1).trim()];
    }),
);

createServer(async (request, response) => {
  if (!["GET", "HEAD"].includes(request.method)) {
    response.writeHead(405, { ...headers, Allow: "GET, HEAD" });
    response.end();
    return;
  }
  try {
    const pathname = decodeURIComponent(
      new URL(request.url, "http://localhost").pathname,
    );
    const path = resolve(
      root,
      `.${pathname === "/" ? "/index.html" : pathname}`,
    );
    if (
      !path.startsWith(root.endsWith(sep) ? root : root + sep) ||
      ["_headers", "_redirects"].includes(pathname.slice(1))
    ) {
      response.writeHead(404, headers);
      response.end();
      return;
    }
    const body = await readFile(path);
    response.writeHead(200, {
      ...headers,
      "Content-Type": types[extname(path)] || "application/octet-stream",
    });
    response.end(request.method === "HEAD" ? undefined : body);
  } catch {
    response.writeHead(404, {
      ...headers,
      "Content-Type": "text/html; charset=utf-8",
    });
    response.end(
      request.method === "HEAD"
        ? undefined
        : await readFile(resolve(root, "404.html")),
    );
  }
}).listen(port, "127.0.0.1", () =>
  console.log(
    `WheelPlay website: http://127.0.0.1:${port} (rerun to rebuild after edits)`,
  ),
);
