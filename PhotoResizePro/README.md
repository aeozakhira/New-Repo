# Photo Resize Pro

A native Android app (Kotlin + Material Components) that resizes photos to an exact
resolution, flattens/whitens the background, and iteratively compresses the output
to a target file size (default: **25 KB or less**) — fully offline.

## Features implemented

- **Select from Gallery** (`ActivityResultContracts.GetContent`) or **Capture via Camera**
  (`ActivityResultContracts.TakePicture`, backed by a `FileProvider` cache file).
- **Exact-resolution resize** with a toggle for aspect-ratio handling:
  - *Maintain aspect ratio ON* → image is scaled to fit inside the target box and
    centered (letterboxed) on a white or transparent canvas.
  - *Maintain aspect ratio OFF* → image is stretched to exactly fill the target
    width × height.
- **White background flattening** — toggle to composite transparency onto pure white.
- **Iterative compression engine** (`ImageProcessor.compressToTargetSize`):
  1. Binary-searches JPEG quality (1–100) for the highest quality that fits the byte budget.
  2. If quality 1 still exceeds the budget, progressively downscales the bitmap
     (×0.9 per iteration, up to 12–15 passes) and re-encodes until it fits.
  3. PNG output (lossless) uses the same progressive-downscale fallback since
     "quality" has no effect on PNG fidelity.
- **Memory-safe decoding** — uses `BitmapFactory.Options.inSampleSize` to downsample
  huge source photos during decode (avoids OOM), plus EXIF-orientation correction.
- **Save** to `Pictures/PhotoResizePro` via `MediaStore` (scoped-storage compliant,
  no legacy WRITE_EXTERNAL_STORAGE needed on Android 10+).
- **Share** via `FileProvider` + `Intent.ACTION_SEND` chooser.
- **Before/after preview** with live resolution + file-size labels.
- **Material Design 3 controls**: TextInputLayout width/height fields, Chip preset group
  (600×800 default / 300×400 / 1200×1600 / Custom), MaterialSwitch toggles, Slider for
  JPEG quality, checkbox for auto-compress, radio group for JPEG/PNG.
- **Dark Mode** — full `values-night` theme, all colors theme-aware.
- **Progress indicator** during processing (indeterminate ProgressBar) and
  **Snackbar** success/error messages.
- All processing runs on a background dispatcher (`Dispatchers.Default`/`IO`) via
  coroutines, so the UI never freezes on large images.

## Project structure

```
PhotoResizePro/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/photoresizepro/
│       │   ├── MainActivity.kt        # UI wiring, permissions, save/share
│       │   └── ImageProcessor.kt      # Decoding, resizing, compression engine
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/ (colors, strings, themes)
│           ├── values-night/themes.xml
│           └── xml/file_paths.xml     # FileProvider paths
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## How to build

1. Open the `PhotoResizePro` folder in **Android Studio (Hedgehog or newer)**.
2. Let Gradle sync (Android Studio will auto-generate the Gradle wrapper JAR if
   it's missing — this project ships `gradle/wrapper/gradle-wrapper.properties`
   pointing at Gradle 8.4, but the wrapper `.jar`/`.bat`/shell script are omitted
   since they're binary; Android Studio recreates them on first sync, or run
   `gradle wrapper` once if you have a local Gradle install).
3. Build → Run on a device/emulator running **API 23+** (Android 6.0+).

## Notes on the 25 KB target

Getting a **600×800 photo under 25 KB** is aggressive — that's roughly 0.05 bits per
pixel. The app handles this by:
- Starting from your chosen quality slider value as the search ceiling.
- Binary-searching down through JPEG quality first (cheap, preserves resolution).
- Only downscaling the actual pixel dimensions as a last resort, and only enough
  to hit the budget — so you still get the sharpest image possible under 25 KB.

If you'd rather not enforce the hard cap, uncheck **"Auto compress to maximum 25 KB"**
and the quality slider value is used directly with no size ceiling.

## Permissions

- `CAMERA` — for the Capture Photo button.
- `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (API ≤32) — for gallery picking.
- No `INTERNET` permission — the app is 100% offline by design.
