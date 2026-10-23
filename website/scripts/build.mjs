import { cp, mkdir, rm } from "node:fs/promises";

const root = new URL("../", import.meta.url);
const output = new URL("dist/", root);
await rm(output, { recursive: true, force: true });
await mkdir(new URL("assets/", output), { recursive: true });
await cp(new URL("public/", root), output, { recursive: true });
await cp(
  new URL("../docs/assets/app-icon.svg", root),
  new URL("assets/app-icon.svg", output),
);
await cp(
  new URL("../docs/licenses/Material-Symbols-Apache-2.0.txt", root),
  new URL("assets/Material-Symbols-Apache-2.0.txt", output),
);
console.log("WheelPlay website built → website/dist");
