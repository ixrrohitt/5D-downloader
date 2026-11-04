# 5D Downloader

YouTube downloader with a 3D/4D/5D animated UI.

* **Phone app (Android 10+)** — yt-dlp + ffmpeg run *on the phone*, no server needed.
  Share a YouTube link to the app → pick quality (sizes shown) → saved in `Downloads/5D-Downloader`.
* **PC version** — `pip install -r requirements.txt && python webapp.py` (same UI, `www/index.html`).

## Install the phone app
1. Push this repo to GitHub (`main`). The **Actions** tab builds the APK (~5 min).
2. Open **Releases → 5D Downloader (latest)** on your phone, download `5D-Downloader.apk`, install
   (allow "install unknown apps" for your browser once).
3. In YouTube: **Share → 5D Downloader**.

## Updating (no reinstall needed for UI changes)
| What you changed | What to do | In the app |
|---|---|---|
| `www/index.html` (UI / script) | bump `"version"` in `version.json`, push | **⟳ → UPDATE NOW** |
| yt-dlp stopped working | nothing to push | **⟳ → UPDATE YT-DLP ENGINE** |
| anything in `android/` (Kotlin, manifest…) | bump `"apk_version"` in `version.json` **and** `versionCode` in `android/app/build.gradle.kts`, push | Actions builds a new APK → **⟳ → NEW APP BUILD** |

If a pushed UI is broken, the app falls back to its bundled UI automatically after ~12 s
(or use **⟳ → reset to bundled UI**).

## Files
```
www/index.html   UI (shared by phone + PC)       version.json   update manifest
webapp.py        PC server                        android/       Kotlin app (WebView + yt-dlp engine)
```
