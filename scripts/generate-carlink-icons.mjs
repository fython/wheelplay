// npm install --prefix .local/icon-tools --no-audit --no-fund sharp
// node scripts/generate-carlink-icons.mjs
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const sharp = require('../.local/icon-tools/node_modules/sharp');
const base = new URL('../projection-plugin-carlink/', import.meta.url);
const source = fileURLToPath(new URL('artwork/carlink-icon.png', base));
const res = new URL('src/main/res/', base);
const save = async (path, data) => {
  const url = new URL(path, res);
  await mkdir(new URL('./', url), { recursive: true });
  await writeFile(url, data);
};
for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
  const square = await sharp(source).resize(size, size).toColourspace('srgb').ensureAlpha().png().toBuffer();
  await save(`mipmap-${density}/ic_carlink.png`, square);
  const mask = Buffer.from(`<svg width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="white"/></svg>`);
  await save(`mipmap-${density}/ic_carlink_round.png`, await sharp(square).composite([{ input: mask, blend: 'dest-in' }]).png().toBuffer());
}
const play = await sharp(source).resize(512, 512).toColourspace('srgb').ensureAlpha().withIccProfile('srgb').png().toBuffer();
if (play.length > 1024 * 1024) throw new Error('Play icon exceeds 1024 KB');
await writeFile(new URL('src/main/ic_launcher-playstore.png', base), play);
// The supplied square represents the visible 72dp viewport of a 108dp layer.
// Extend the outermost green pixels into overscan. The generated source has
// slight background variation, so sampling a single corner creates a visible box.
await save('drawable-nodpi/ic_carlink_layer.png', await sharp(source).resize(432, 432).extend({ top: 108, bottom: 108, left: 108, right: 108, extendWith: 'copy' }).png().toBuffer());
const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/transparent" />
    <foreground android:drawable="@drawable/ic_carlink_layer" />
</adaptive-icon>
`;
await save('mipmap-anydpi-v26/ic_carlink.xml', adaptive);
await save('mipmap-anydpi-v26/ic_carlink_round.xml', adaptive);
console.log('Generated CarLink launcher resources and 512px RGBA sRGB Play icon.');
