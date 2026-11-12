# PIN Scanner — Android app (offline)

The counter tool: a native camera app that reads prepaid top-up codes **on the
phone**, with no network at all. Built for a shop doing ~100 cards a day on
Android phones with unreliable Wi‑Fi.

- Reads the printed code with **ML Kit** (bundled model, works in airplane mode)
- Validates the `4-4-4-3` / `4-4-4-4` layout so the 15-digit serial under the
  barcode can never be mistaken for a code
- Requires **two matching reads** before showing a code
- Saves every scan to a local history and exports it as a **`.txt` file in one
  tap** (the file goes wherever you choose: Downloads, Drive, Telegram…)
- **No `INTERNET` permission** — a photo cannot leave the device even if
  something tried

The bot in the parent folder stays as the fallback for iPhones and for scanning
photos taken earlier.

---

## Requirements

| | |
| --- | --- |
| Phone | Android 7.0 (API 24) or newer, any camera |
| Build machine | Android Studio **Koala 2024.1.1** or newer (AGP 8.5 needs it), JDK 17 |
| Internet | only while **building** (Gradle + ML Kit download); the app itself is offline |

## Build in the cloud — no Android Studio

The project already contains the CI recipe in
`.github/workflows/build-apk.yml`. Two free ways to use it.

### A. GitHub Actions (recommended, ~5 minutes, no git required)

1. Create a free GitHub account and a new **private** repository.
2. In the repository choose **Add file -> Create new file**, name it
   `.github/workflows/build-apk.yml`, paste the file of the same name from this
   project and commit it.
3. Choose **Add file -> Upload files**, drag the whole `android` folder into the
   page and commit.
4. Open the **Actions** tab, pick **Build APK** and press **Run workflow**.
5. In 3–6 minutes the run turns green. Open it, scroll to **Artifacts** and
   download `pin-scanner-apk.zip` — inside is `pin-scanner-<run>.apk`.
6. Send that APK to the phone and install it (see below).

Private repositories include 2 000 free build minutes per month and one build
takes about 5, so it stays free. Public repositories are unlimited, but the
source is public.

> The workflow also runs the layout unit tests, so a red run means either a
> compile error or a broken validator test. Send me the log and I fix it.

> **Updates and the signing key.** Android only installs an update over an app
> signed with the *same* key. The workflow caches the debug keystore, so APKs
> from later runs install over the previous one and keep the scan history.
> GitHub can evict that cache after long inactivity: if Android answers
> "App not installed", export the history first, uninstall the old app once,
> then install the new APK.

### B. Codemagic / AppCircle / Bitrise (if you prefer a dashboard)

All three have Android build free tiers. Connect the repository and use:

- Working directory: `android`
- Build command: `gradle assembleDebug`

### C. Local build without the IDE (offline option)

Android Studio is not required — only the SDK tools:

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"   # after installing a JDK 17
cd android
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

Needs roughly 4 GB of disk and 2 GB of RAM. On Windows-on-ARM (Surface Pro X)
the Android SDK tools are not officially supported, which is why the cloud
build is the safer route for a Surface.

## Build and install with Android Studio

1. Open the `android/` folder (not the repository root) in Android Studio.
2. Let it sync — it downloads Gradle 8.7 and the dependencies.
3. Connect a phone with USB debugging, press **Run**.
   Or build the APK: **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
   The file lands in `app/build/outputs/apk/debug/app-debug.apk`.

Command line:

```bash
cd android
gradle wrapper --gradle-version 8.7      # once, creates ./gradlew
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Copy that APK to each phone (Telegram "Saved Messages" is the easiest), open it,
allow *Install unknown apps* for the app you opened it from, and install.

The **debug** APK is fine for your own phones: it is signed with the debug key,
so updates install over it without losing history. For a release build you need
your own key:

```bash
keytool -genkey -v -keystore pinbot-release.jks -alias pinbot \
        -keyalg RSA -keysize 2048 -validity 10000
```

```kotlin
// android/app/build.gradle.kts
android {
    signingConfigs {
        create("release") {
            storeFile = file("../pinbot-release.jks")
            storePassword = System.getenv("PINBOT_STORE_PASSWORD")
            keyAlias = "pinbot"
            keyPassword = System.getenv("PINBOT_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
}
```

**Back that `.jks` file up.** Without it you can never update an installed app —
only uninstall and reinstall, which wipes the history.

---

## How it works

```
CameraX (1280×720, keep-latest)
   ↓ every frame
ML Kit Text Recognition (offline, per-character boxes)
   ↓
PinLayout.validateLine: chain boxes into lines → find 4-4-4-3 / 4-4-4-4 groups
   ↓
two consecutive identical reads within 4 s → show the code
   ↓
history (JSON lines in private storage) → Export .txt
```

- `ocr/PinLayout.kt` — the same geometry rules as the Python pipeline in
  `pinbot/ocr/detect.py`, ported to Kotlin and unit-tested. It is what rejects
  the serial number and random text.
- `ocr/FrameText.kt` — turns ML Kit symbols into one box per digit.
- `MainActivity.kt` — camera, confirmation logic, result card.
- `HistoryActivity.kt` — list, clear, and the one-click export.
- `data/HistoryRepository.kt` — append-only file, no database.
- `util/Exporter.kt` — builds the text file and writes it via the system file
  picker (Storage Access Framework), so no storage permission is needed.

### What the export looks like

```
PIN scanner export
Generated: 2026-11-12 22:14
Cards: 3

Date	Code	Digits
2026-11-12 18:02	8503 0422 6422 626	850304226422626
2026-11-12 18:05	8524 9797 6735 201	852497976735201
2026-11-12 18:11	8506 0852 5695 028	850608525695028
```

Tab separated, so it also opens cleanly in Excel or Google Sheets.

---

## Tests

```bash
cd android
./gradlew test          # JVM unit tests for the layout validator
```

`PinLayoutTest` covers: accepted 4-4-4-3 and 4-4-4-4, rejection of the evenly
spaced serial, wrong group sizes, short rows, rows too small for the frame,
confidence range, and picking the PIN out of a frame that also contains a
serial line.

---

## Tuning and troubleshooting

| Symptom | Fix |
| --- | --- |
| Camera black | Grant permission; if permanently denied use **Open settings** in the app, or Settings → Apps → PIN Scanner → Permissions |
| Slow on a very cheap phone | In `MainActivity.kt`, lower `Size(1280, 720)` to `Size(960, 540)` |
| Code not found but it is clearly visible | Move closer (digits should be ~30 px tall in the frame), clean the scratch area, tilt the card away from the light to kill glare |
| Wrong code accepted | It cannot be shown without two identical reads; if it happens, the two reads agreed on the same error — report the photo and the layout thresholds in `PinLayout.kt` can be tightened |
| History empty after reinstall | History lives in app-private storage; uninstall wipes it. Export regularly |

## Not included (yet)

- Sync of the history to the bot or to KridiAmane — the export is the bridge for
  now.
- Barcode reading of the card serial (the barcode holds the serial, not the PIN).
- Arabic UI strings (French and English are included).
