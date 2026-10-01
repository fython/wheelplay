// Raster exports share the Android vector's path, positioning, and color.
// Requires sharp (export-time only; not an Android build dependency).
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const sharp = require('sharp');
const root = new URL('../', import.meta.url);
const resource = new URL('shared/src/main/res/', root);
const vector = await readFile(new URL('drawable/ic_launcher_foreground.xml', resource), 'utf8');
const colors = await readFile(new URL('values/ic_launcher_colors.xml', resource), 'utf8');
const attribute = (name) => {
  const value = vector.match(new RegExp(`android:${name}="([^"]+)"`))?.[1];
  if (!value) throw new Error(`Missing vector attribute: ${name}`);
  return value;
};
const background = colors.match(/<color name="ic_launcher_background">(#[0-9A-Fa-f]{6})<\/color>/)?.[1];
if (!background) throw new Error('Missing icon background color');
const foreground = `<g transform="translate(${attribute('translateX')} ${attribute('translateY')}) scale(${attribute('scaleX')} ${attribute('scaleY')})"><path fill="#FFFFFF" d="${attribute('pathData')}"/></g>`;

// Adaptive icons expose the central 72dp of their 108dp layers at rest.
const svg = (size, round = false) => `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="18 18 72 72">${round ? '<defs><clipPath id="mask"><circle cx="54" cy="54" r="36"/></clipPath></defs><g clip-path="url(#mask)">' : ''}<path fill="${background}" d="M0 0h108v108H0z"/>${foreground}${round ? '</g>' : ''}</svg>\n`;

for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
  const directory = new URL(`mipmap-${density}/`, resource);
  await mkdir(directory, { recursive: true });
  for (const round of [false, true]) {
    await sharp(Buffer.from(svg(size, round))).webp({ lossless: true })
      .toFile(fileURLToPath(new URL(`ic_launcher${round ? '_round' : ''}.webp`, directory)));
  }
}
await sharp(Buffer.from(svg(512))).png()
  .toFile(fileURLToPath(new URL('shared/src/main/ic_launcher-playstore.png', root)));
await mkdir(new URL('docs/assets/', root), { recursive: true });
await writeFile(new URL('docs/assets/app-icon.svg', root), svg(512));
console.log('Generated launcher bitmaps, Play Store icon, and SVG export.');
