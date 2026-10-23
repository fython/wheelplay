import type { APIRoute } from "astro";
import license from "../../../../docs/licenses/Material-Symbols-Apache-2.0.txt?raw";

export const GET: APIRoute = () =>
  new Response(license, {
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  });
