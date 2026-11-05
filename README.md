# 5D Downloader

YouTube (and many other sites) downloader with a 3D/4D/5D animated UI. Two ways to use it:

* **Phone app (Android 10+)**: yt-dlp, ffmpeg and aria2c run *on the phone*, no server needed.
  Share a link to the app, pick quality (real sizes shown), saved in `Downloads/5D-Downloader`.
* **PC version**: `pip install -r requirements.txt && python webapp.py`, open `http://localhost:8000`
  (same UI, `www/index.html`). Works in GitHub Codespaces too.

## Features

| | |
|---|---|
| **Exact quality + size** | Every quality chip shows the real size of the video + audio streams that will be downloaded, e.g. `720p · H.264 · 30fps · 19.1 MB + audio AAC 130k · 3.1 MB`. Sizes shown with `≈` are estimates. What you see is what you get: the app sends the exact stream ids it showed to the engine. |
| **Video** | Resolution chips (only those the video really has), container `mp4 / mkv / webm / original`. mp4 prefers H.264 + AAC (plays everywhere); mkv/webm/original can give Opus audio (~160k). |
| **Audio** | `mp3 / m4a / opus / ogg / flac / wav / original`, plus bitrate chips (never above the source bitrate), each with its size. |
| **Live progress** | One overall percentage across video + audio + merge, with `downloaded / total`, speed and ETA. Never goes backwards. Preparing / merging / saving are shown as their own stages. |
| **Fast start** | The video is read only once. Download and Watch reuse that data (cached 20 min), so they start in about a second instead of reading the video again. |
| **Watch without downloading** | Android only. Streams the chosen quality in the built-in player. |
| **Many links / playlists** | Paste several links (up to 20) or a playlist. Each video is read in parallel; for playlists the sizes start as estimates and are replaced by exact sizes in the background (first 30 selected videos). |
| **Multi-connection downloads** | Android uses aria2c (16 connections). Pause / resume / cancel, keeps running in the background (notification). |
| **Spotify** | Track / album / playlist links: the track list is read from Spotify and each song is found and downloaded as audio from YouTube (Spotify audio itself is DRM-protected). |
| **Layout** | Buttons never overlap: screens scroll when there is a lot to show, and the hover "magnet" effect is tiny and mouse-only. |

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
| anything in `android/` (Kotlin, manifest…) | bump `"apk_version"` in `version.json` (it is also used as the Android `versionCode`), push | Actions builds a new APK → **⟳ → NEW APP BUILD** |

If a pushed UI is broken, the app falls back to its bundled UI automatically after ~12 s
(or use **⟳ → reset to bundled UI**).

Note: UI-only updates are compatible with older APKs, but the live-progress fix lives in the Kotlin engine
(`Engine.kt`), so install the new APK to get it.

## PC version
```
pip install -r requirements.txt
python webapp.py          # http://localhost:8000   (PORT=9000 python webapp.py to change the port)
```
* Needs `ffmpeg` (installed automatically through `imageio-ffmpeg`).
* `USE_ARIA2=1` enables aria2c turbo mode for multi-file jobs if `aria2c` is installed. It is **off by default**
  because aria2c gives no live progress.
* The PC server has no pause / Watch (those buttons are hidden there).

## How it works (short)
1. **Probe**: yt-dlp reads the video once. The page analyses the format list and decides, per quality, which video + audio stream to use and their size.
2. **Download**: the engine gets the chosen stream ids (`video_id`, `audio_id`) and `expected_bytes`, falls back to a generic selector if those ids are not available, and reports progress from yt-dlp / aria2c output.
3. **Save**: merged / converted with ffmpeg, then moved to `Downloads/5D-Downloader` (phone) or `downloads/` (PC).

## Files
```
www/index.html   UI + stream/size analysis (shared by phone + PC)
version.json     update manifest (UI version + APK version)
webapp.py        PC server (yt-dlp Python API)
requirements.txt PC dependencies
android/         Kotlin app: WebView + Engine.kt (yt-dlp, aria2c, ffmpeg, progress, Watch)
.github/         GitHub Action that builds the APK
```
