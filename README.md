# Screen Censor (Android)

Covers nudity / cleavage / faces / feet on screen with mosaic, blur or solid boxes.

## Build the APK (no PC needed)
1. Put this whole folder in a new GitHub repo (keep the folder structure, including `.github/workflows/build.yml`).
2. Open the repo's **Actions** tab, run **Build APK** (it also runs on every push).
3. When it finishes, download the `screen-censor-apk` artifact, unzip it and install `app-debug.apk`
   (allow "install unknown apps" for your browser/files app).

The workflow pulls the NudeNet 320n model out of the `nudenet` pip package and bundles it into the APK.
Or open the folder in Android Studio (add `320n.onnx` from the nudenet package to `app/src/main/assets/`).

## First run
Tap Start -> allow "Display over other apps" -> tap Start again -> allow screen capture.

Note: the NudeNet weights are AGPL-3.0. Fine for personal use; check the license before distributing.
