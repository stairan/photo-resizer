# Fotó Kicsinyítő (Photo Resizer)

An Android app that shrinks a folder full of photos to a smaller resolution, built for SD cards that
fill up with full-size camera photos. Pick a folder, pick a size, and the app resizes every JPEG and
PNG in it, including all subfolders. It runs entirely on the phone, with no PC step and no internet
connection.

The user interface is in **Hungarian** only.

## Features

- **Folder picker** (Android's Storage Access Framework): works on internal storage and on SD cards,
  without any "all files access" permission. The last folder is remembered.
- **Selectable size**: 640, 800, 1024, 1280 (default), 1600, 1920 or 2560 px on the long side. A 4:3
  photo at 1280 becomes 1280×960, or 960×1280 if it is a portrait photo.
- **JPEG quality slider** (60–95, default 80).
- **Two save modes**
  - **Overwrite the originals** (default): replaces each photo with the smaller version. The screen
    shows a red warning, and a second confirmation dialog appears before every run.
  - **Save to a new folder**: mirrors the folder structure in a destination folder and leaves the
    originals untouched. Optionally it also copies all non-image files (documents, videos, ...).
- **Safe by design**
  - Overwriting writes a temporary file next to the original, checks that it is readable and has the
    expected size, and only then deletes the original and renames the temporary file. A crash leaves
    either the original or the new file, never neither.
  - Photos that are already at or below the chosen size are skipped, and so is any photo that would
    not get smaller. Running the app again on the same folder only touches new photos.
  - Damaged or empty images are never modified or deleted. In new-folder mode they are copied
    unchanged, and they are all listed on the result screen.
  - Existing files in the destination are skipped, so an interrupted new-folder run can be repeated.
- **Metadata kept**: EXIF data such as the date taken, camera model and GPS position is copied to the
  resized photo, and EXIF rotation is applied to the pixels.
- **Test run**: processes only the first 10 photos so the result can be checked first.
- **Long runs**: a foreground service with a progress notification and a wake lock keeps the job
  running with the screen off. Changed files are announced to the gallery when the run finishes.
- **Remembers its settings**: size, quality, mode, "copy other files" and the destination folder.

## Install

Download the APK from the [Releases](../../releases) page and open it on the phone. Android will ask
to allow installs from the browser or file manager you used. The release APK is signed with the
debug key, so it is meant for direct installs only, not for Google Play.

Requirements: Android 8.0 (API 26) or newer. The app was written with Android 16 in mind.

## Usage

1. Open the app and tap **Mappa kiválasztása** (choose folder), then pick the folder with the photos
   (for example `DCIM/Camera` on the SD card). The app counts the images and other files.
2. Choose the **mode**: overwrite the originals, or save to a new folder (pick the destination, and
   tick "copy non-image files" if wanted).
3. Choose the **resolution** and the **quality**.
4. Optionally run **Teszt (10 kép)** first, then **Indítás** (start). In overwrite mode, confirm the
   warning dialog.
5. Follow the progress in the app or in the notification. When it finishes, the summary shows how
   many photos were resized, how much space was saved, and which files failed and why.

Tip: try the new-folder mode on a small folder first, and compare the result with the originals.

## How it works

The Flutter layer (`lib/`) is only the user interface. The work is done by native Kotlin code, which
is far faster than decoding 12 MP photos in Dart.

- Each image is decoded already scaled down (`BitmapFactory` with `inSampleSize`), then scaled to the
  exact size with the EXIF orientation applied, and re-encoded as JPEG (PNG stays PNG).
- Three worker threads process files in parallel while one thread walks the folder tree.
- All file access goes through `DocumentsContract`, so any folder the user picks works, including
  removable storage.

```
lib/
  main.dart        UI: folder, mode, resolution, progress and result screens
  native.dart      Dart side of the method/event channels
  strings.dart     all Hungarian UI text
android/app/src/main/kotlin/hu/photosizer/photo_sizer/
  MainActivity.kt  folder picker, settings storage, channel handlers
  ResizeEngine.kt  folder walk, resize, safe overwrite, EXIF copy, statistics
  ResizeService.kt foreground service and progress notification
```

## Build from source

Requires Flutter and the Android SDK.

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --release                    # one APK for every CPU type (~44 MB)
flutter build apk --release --split-per-abi    # smaller per-CPU APKs (arm64-v8a is roughly 15-20 MB)
```

The APK ends up in `build/app/outputs/flutter-apk/`.

The project is deliberately pinned to the SDK that was installed on the development machine:
`compileSdk` 33, `targetSdk` 33, build-tools 33.0.2, `minSdk` 26 (see `android/app/build.gradle.kts`).
Flutter's AndroidX dependencies declare that they need compileSdk 34 or newer, so Gradle's
`AarMetadata` check is switched off in that file. If you have a newer SDK installed, you can raise
the SDK versions and remove that workaround. A newer `targetSdk` also requires declaring the
foreground-service type permission, which is already present in the manifest.

## Limitations

- Only `.jpg`, `.jpeg` and `.png` files are resized. Other formats, such as HEIC and WebP, count as
  non-image files.
- Android does not let apps set the modified date through the folder picker, so overwritten files get
  the current date as their modified time. The EXIF "date taken" is kept, which is what most gallery
  apps sort by.
- There is no free-space check before a new-folder run.
- Photos damaged on the card (for example blocks of zeros) cannot be decoded and are reported, not
  repaired.
- It has been tested by hand on a Samsung phone only. A folder created by the app may take a while
  to appear in some file manager apps because of their own caching.
