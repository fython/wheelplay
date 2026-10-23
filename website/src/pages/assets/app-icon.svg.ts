import type { APIRoute } from "astro";
import icon from "../../../../docs/assets/app-icon.svg?raw";

// Pre-render the shared repository asset at its existing public URL.
export const GET: APIRoute = () =>
  new Response(icon, { headers: { "Content-Type": "image/svg+xml" } });
