# App icon

The launcher uses Google's **Material Symbols Outlined `laptop_car`** with
`FILL=0`, `wght=400`, `GRAD=0`, and `opsz=24`. The original path is preserved,
painted white, and centered over **Material Green 500 (`#4CAF50`)**.

Source: https://github.com/google/material-design-icons/blob/master/symbols/web/laptop_car/materialsymbolsoutlined/laptop_car_24px.svg

Google distributes Material Symbols under Apache License 2.0; the license is
included in `docs/licenses/Material-Symbols-Apache-2.0.txt`.

The adaptive layers use a 108dp canvas. The foreground artwork is 45.36 × 38.88dp,
centered at (54, 54) and contained in the 66dp safe circle. The background has
no baked-in mask, border, or shadow. The same vector supplies the monochrome
layer for Android's themed icons.

`mobile/src/main/AndroidManifest.xml` points both launcher icon attributes at
the adaptive resources in `shared`. Density-specific legacy square and round
icons and the 512px Play Store image use the same artwork and color. Store
artwork is square; rounding is applied by the store or launcher.

Regenerate the bitmap exports from the Android vector and color resources:

```sh
npm install --no-save --package-lock=false sharp
node scripts/generate-app-icons.mjs
```

The generator also creates `docs/assets/app-icon.svg` as an editable export.
Adaptive icon sizing guidance: https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
