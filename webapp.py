"""
3·4·5D — YouTube Downloader (PC server; the UI lives in www/index.html) (single file, stdlib + yt-dlp).

Run:  python webapp.py   → open the printed URL (localhost or Codespaces).
UI: 3D warp-speed starfield, rotating 3D status cube, video+audio MERGE fusion
animation, ZIP crush animation, confetti + shockwave on completion, tilt cards,
sound blips, auto-fetch on paste, Ctrl+Enter to fetch/download.
"""
from __future__ import annotations

import http.server
import json
import os
import re
import shutil
import socketserver
import sys
import threading
import time
import traceback
import uuid
import zipfile
from pathlib import Path
from urllib.parse import quote, urlparse


def _auto_install(pkgs: list[str]) -> None:
    """pip install; retries with --break-system-packages for PEP 668 distros (Ubuntu/Mint/Debian)."""
    import subprocess
    print(f"[setup] Installing: {', '.join(pkgs)} …")
    base = [sys.executable, "-m", "pip", "install", "-U", "--quiet", *pkgs]
    for extra in ([], ["--break-system-packages"], ["--user", "--break-system-packages"]):
        try:
            subprocess.check_call(base + extra, stderr=subprocess.DEVNULL if extra else None)
            return
        except subprocess.CalledProcessError:
            continue
    print("[setup] pip install failed. Run manually (inside a venv is best):\n  "
          + " ".join(base) + "   (add --break-system-packages if pip says 'externally-managed')")
    sys.exit(1)


try:
    from yt_dlp import YoutubeDL
except ImportError:
    _auto_install(["yt-dlp[default]"])
    from yt_dlp import YoutubeDL  # type: ignore

try:
    import yt_dlp_ejs  # type: ignore  # noqa: F401
    _HAS_EJS = True
except ImportError:
    try:
        _auto_install(["yt-dlp-ejs"])
        import yt_dlp_ejs  # type: ignore  # noqa: F401
        _HAS_EJS = True
    except Exception:
        _HAS_EJS = False
        print("[setup] Warning: yt-dlp-ejs missing; YouTube may block downloads.")

def _find_ffmpeg():
    """System ffmpeg, else a bundled static one via imageio-ffmpeg (needed to MERGE video+audio)."""
    p = shutil.which("ffmpeg")
    if p:
        return p
    try:
        import imageio_ffmpeg  # type: ignore
    except ImportError:
        try:
            _auto_install(["imageio-ffmpeg"])
            import imageio_ffmpeg  # type: ignore
        except (Exception, SystemExit):
            return None
    try:
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


_FFMPEG = _find_ffmpeg()
if _FFMPEG:
    print(f"[setup] ffmpeg: {_FFMPEG}")
else:
    print("[setup] Warning: ffmpeg not found — merging / mp3 conversion will fail.")
    print("        apt: sudo apt-get install -y ffmpeg | brew install ffmpeg | winget install Gyan.FFmpeg")
_HAS_DENO = bool(shutil.which("deno") or shutil.which("node"))
if not _HAS_DENO:
    print("[setup] Warning: no 'deno'/'node' on PATH (needed for YouTube n-challenge).")

try:
    from yt_dlp.utils import DownloadCancelled
except ImportError:  # very old yt-dlp
    class DownloadCancelled(Exception):  # type: ignore
        pass


class _Cancel(DownloadCancelled):
    pass


ROOT = Path.cwd()
DOWNLOADS_DIR = ROOT / "downloads"
COOKIES_DIR = ROOT / ".webapp_cookies"
DOWNLOADS_DIR.mkdir(exist_ok=True)
COOKIES_DIR.mkdir(exist_ok=True)

JOBS: dict[str, dict] = {}
JOBS_LOCK = threading.Lock()
MAX_LOG_LINES = 250
AUDIO_EXT = {".mp3", ".m4a", ".opus", ".wav"}


# ---------------- helpers ----------------
def _list_downloaded_files() -> list[dict]:
    out: list[dict] = []
    files = [p for p in DOWNLOADS_DIR.rglob("*") if p.is_file()]
    files.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    for fp in files:
        try:
            st = fp.stat()
            rel = fp.relative_to(DOWNLOADS_DIR).as_posix()
            ext = fp.suffix.lower()
            out.append({
                "name": fp.name, "path": rel, "url": "/downloads/" + quote(rel),
                "size_mb": round(st.st_size / 1048576, 2), "mtime": int(st.st_mtime),
                "is_zip": ext == ".zip",
                "kind": "audio" if ext in AUDIO_EXT else ("zip" if ext == ".zip" else "video"),
            })
        except OSError:
            continue
    return out


def _split_urls(text: str) -> list[str]:
    return [p for p in re.split(r"[\s,]+", text.strip()) if p.startswith("http")]


def _is_playlist_url(url: str) -> bool:
    try:
        return "list=" in urlparse(url).query
    except Exception:
        return False


def _safe_cookie(name: str) -> str:
    """Only allow files that live inside COOKIES_DIR (blocks arbitrary path access)."""
    if not name:
        return ""
    p = COOKIES_DIR / Path(name).name
    return str(p) if p.exists() else ""


def _job_log(job_id: str, message: str, level: str = "info") -> None:
    line = {"time": time.strftime("%H:%M:%S"), "level": level, "message": str(message).strip()}
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job:
            return
        logs = job.setdefault("log", [])
        logs.append(line)
        del logs[:-MAX_LOG_LINES]
        job["last_message"] = line["message"]


def _job_update(job_id: str, **values) -> None:
    with JOBS_LOCK:
        if job_id in JOBS:
            JOBS[job_id].update(values)


class YtdlpJobLogger:
    def __init__(self, job_id: str):
        self.job_id = job_id

    def debug(self, msg):
        t = str(msg)
        if t and not t.startswith("[debug]"):
            _job_log(self.job_id, t, "debug")

    def warning(self, msg):
        _job_log(self.job_id, msg, "warn")

    def error(self, msg):
        _job_log(self.job_id, msg, "error")


def _friendly_error(error: Exception) -> str:
    msg = str(error)
    missing = []
    if not _HAS_EJS:
        missing.append("pip install -U yt-dlp-ejs")
    if not _HAS_DENO:
        missing.append("install deno or node")
    hint = ("\nMissing: " + "; ".join(missing)) if missing else ""
    if any(k in msg for k in ("n challenge", "JavaScript runtime", "challenge solver",
                              "Requested format is not available", "Only images are available")):
        return "YouTube's bot-check solver failed. Install yt-dlp-ejs + deno/node, upgrade yt-dlp, retry." + hint
    if "Sign in to confirm" in msg or "not a bot" in msg:
        return "YouTube wants verification. Upload a fresh cookies.txt and make sure the JS solver is installed." + hint
    if "429" in msg or "Too Many Requests" in msg:
        return "Rate-limited by YouTube. Wait a few minutes or use fresh cookies."
    if "HTTP Error 403" in msg:
        return "YouTube blocked this request. Refresh cookies.txt and retry."
    if "HTTP Error 404" in msg:
        return "Video not found or private."
    return msg or "Something went wrong."


# ---------------- probing ----------------
_EST_BPS = {144: 19e3, 240: 37e3, 360: 62e3, 480: 112e3, 720: 225e3, 1080: 437e3}  # rough bytes/sec incl. audio
_DEF_RES = sorted(_EST_BPS)


def _fsize(f: dict, dur) -> int:
    s = f.get("filesize") or f.get("filesize_approx")
    if not s and f.get("tbr") and dur:
        s = f["tbr"] * 125 * dur  # kbit/s -> bytes
    return int(s) if s else 0


def _sizes_from_formats(fm: list, dur) -> dict:
    """{height: bytes of the video(+best audio) that would be downloaded}."""
    aud = [f for f in fm if f.get("vcodec") in (None, "none") and f.get("acodec") not in (None, "none")]
    best_a = max(aud, key=lambda f: (f.get("ext") == "m4a", f.get("abr") or 0), default=None)
    asz = _fsize(best_a, dur) if best_a else 0
    by: dict = {}
    for f in fm:
        h = f.get("height")
        if not h or f.get("vcodec") in (None, "none"):
            continue
        sz = _fsize(f, dur)
        if not sz:
            continue
        key = (f.get("ext") == "mp4", sz)  # mp4 is what the downloader prefers
        has_audio = f.get("acodec") not in (None, "none")
        if h not in by or key > by[h][0]:
            by[h] = (key, sz + (0 if has_audio else asz))
    return {str(h): v[1] for h, v in by.items()}


def _entry(info: dict, url: str = "") -> dict:
    dur = info.get("duration")
    thumbs = info.get("thumbnails") or []
    th = info.get("thumbnail") or (thumbs[-1].get("url") if thumbs else None)
    sizes = _sizes_from_formats(info.get("formats") or [], dur)
    est = False
    if not sizes and dur:  # flat playlist entries: no formats, estimate from duration
        sizes, est = {str(h): int(dur * bps) for h, bps in _EST_BPS.items()}, True
    return {"title": info.get("title") or url or "(untitled)", "url": info.get("webpage_url") or url,
            "duration": dur, "thumbnail": th, "sizes": sizes, "est": est,
            "audio": int(dur * 24000) if dur else None}  # mp3 192 kbps ~ 24 KB/s


def probe_url(url: str, cookies_file: str | None, limit: int = 200, job_id: str | None = None) -> dict:
    opts = {"quiet": True, "skip_download": True, "extract_flat": "in_playlist",
            "socket_timeout": 20, "retries": 3, "extractor_retries": 3, "ignoreerrors": False}
    if cookies_file:
        opts["cookiefile"] = cookies_file
    if job_id:
        opts["logger"] = YtdlpJobLogger(job_id)
        _job_log(job_id, "Connecting to YouTube and reading metadata…")
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    title = info.get("title") or "video"
    is_playlist = bool(info.get("_type") == "playlist" or info.get("entries"))
    videos = []
    if is_playlist:
        for e in (info.get("entries") or [])[:limit]:
            if not e:
                continue
            vu = e.get("url") or e.get("webpage_url") or ""
            if vu and not vu.startswith("http"):
                vu = f"https://www.youtube.com/watch?v={vu}"
            videos.append(_entry(e, vu))
    else:
        videos.append(_entry(info, url))
    res = sorted({f["height"] for f in (info.get("formats") or [])
                  if f.get("height") and f.get("vcodec") != "none"}) or _DEF_RES
    if job_id:
        _job_log(job_id, f"Found {len(videos)} item(s). Sizes ready.", "ok")
    return {"title": title, "is_playlist": is_playlist, "videos": videos, "resolutions": res}


def _probe_many(urls: list, cookies_file: str | None, job_id: str) -> dict:
    """Read several links in parallel so we can show real sizes for each."""
    from concurrent.futures import ThreadPoolExecutor
    count = [0]

    def one(u: str) -> dict:
        try:
            opts = {"quiet": True, "skip_download": True, "noplaylist": True, "socket_timeout": 20,
                    "retries": 2, "extractor_retries": 2, "logger": YtdlpJobLogger(job_id)}
            if cookies_file:
                opts["cookiefile"] = cookies_file
            with YoutubeDL(opts) as ydl:
                e = _entry(ydl.extract_info(u, download=False), u)
        except Exception as ex:
            _job_log(job_id, f"Could not read {u[:60]}: {str(ex)[:80]}", "warn")
            e = {"title": u, "url": u, "duration": None, "thumbnail": None, "sizes": {}, "est": False, "audio": None}
        count[0] += 1
        _job_update(job_id, progress=25 + int(70 * count[0] / len(urls)), stage=f"Reading {count[0]}/{len(urls)}")
        _job_log(job_id, f"Read {count[0]}/{len(urls)}: {e['title'][:60]}", "ok")
        return e

    with ThreadPoolExecutor(max_workers=4) as ex:
        videos = list(ex.map(one, urls))
    res = sorted({int(h) for v in videos for h in v["sizes"]}) or _DEF_RES
    return {"title": f"{len(videos)} videos", "is_playlist": False, "mode": "multiple", "urls": urls,
            "videos": videos, "resolutions": res}


def run_probe(job_id: str) -> None:
    with JOBS_LOCK:
        job = JOBS[job_id]
        url_text, cookies_file = job.get("url_text", ""), job.get("cookies_file") or None
    try:
        _job_update(job_id, status="fetching", stage="Parsing links", progress=8)
        urls = _split_urls(url_text)[:20]
        if not urls:
            raise ValueError("Please paste at least one YouTube URL.")
        _job_log(job_id, f"Cookies: {'loaded' if cookies_file else 'not uploaded'}.")
        _job_update(job_id, progress=25, stage="Detecting mode")
        if len(urls) > 1:
            _job_log(job_id, f"Multiple mode: reading {len(urls)} links in parallel…")
            info = _probe_many(urls, cookies_file, job_id)
        else:
            playlist = _is_playlist_url(urls[0])
            _job_update(job_id, progress=40, stage="Reading playlist" if playlist else "Reading video")
            info = probe_url(urls[0], cookies_file, job_id=job_id)
            info["mode"] = "playlist" if playlist else "single"
            info["urls"] = urls
        _job_update(job_id, status="probe_done", stage="Ready", progress=100, probe=info)
        _job_log(job_id, "Fetch complete.", "ok")
    except Exception as e:
        traceback.print_exc()
        m = _friendly_error(e)
        _job_update(job_id, status="error", stage="Fetch failed", error=m, progress=100)
        _job_log(job_id, m, "error")


# ---------------- download worker ----------------
def _make_progress_hook(job_id: str):
    def hook(d: dict) -> None:
        fin = d.get("status") == "finished"
        with JOBS_LOCK:
            j = JOBS.get(job_id)
            if not j:
                return
            if j.get("cancel"):
                raise _Cancel("Cancelled by user")
            if d.get("status") == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                j["stage"] = "Downloading"
                j["current_file"] = os.path.basename(d.get("filename", ""))
                j["speed"] = d.get("speed") or 0
                j["eta"] = d.get("eta") or 0
                j["current_pct"] = ((d.get("downloaded_bytes") or 0) / total * 100) if total else 0
            elif fin:
                j["current_pct"] = 0
        if fin:
            _job_log(job_id, "File finished. Merging/converting if needed…", "ok")
    return hook


def _make_pp_hook(job_id: str):
    def hook(d: dict) -> None:
        pp, st = d.get("postprocessor") or "postprocess", d.get("status")
        if st == "started":
            _job_update(job_id, stage=f"Running {pp}")
            _job_log(job_id, f"Started {pp}…")
        elif st == "finished":
            _job_log(job_id, f"Finished {pp}.", "ok")
            if pp == "MoveFiles":
                with JOBS_LOCK:
                    j = JOBS.get(job_id)
                    if j:
                        j["completed_items"] = int(j.get("completed_items") or 0) + 1
    return hook


def _fix_unmerged(out_dir: Path, job_id: str) -> None:
    """Safety net: if yt-dlp left separate video/audio streams (name.f137.mp4 + name.f140.m4a), merge them."""
    if not _FFMPEG:
        return
    import subprocess
    groups: dict = {}
    for fp in out_dir.rglob("*"):
        m = re.match(r"(.+)\.f[\w-]+\.(\w+)$", fp.name)
        if fp.is_file() and m:
            groups.setdefault((fp.parent, m.group(1)), []).append(fp)
    for (parent, base), parts in groups.items():
        if len(parts) != 2:
            continue
        out = parent / f"{base}.mp4"
        _job_update(job_id, stage="Running Merger")
        _job_log(job_id, f"Merge fallback: joining video+audio for '{base}'", "warn")
        for a, b in (parts, parts[::-1]):
            cmd = [_FFMPEG, "-y", "-loglevel", "error", "-i", str(a), "-i", str(b),
                   "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", str(out)]
            if subprocess.run(cmd).returncode == 0 and out.exists() and out.stat().st_size > 0:
                for x in parts:
                    x.unlink(missing_ok=True)
                _job_log(job_id, "Merged successfully.", "ok")
                break
            out.unlink(missing_ok=True)
        else:
            _job_log(job_id, f"Could not merge '{base}'; leaving the separate streams.", "error")


def run_download(job_id: str) -> None:
    with JOBS_LOCK:
        job = JOBS[job_id]
        job.update(status="running", stage="Preparing download", progress=0)
    try:
        out_dir = DOWNLOADS_DIR / job["folder_name"]
        out_dir.mkdir(parents=True, exist_ok=True)
        mode, media = job["mode"], job["media_type"]
        chosen = int(job.get("resolution") or 0)
        cookies = _safe_cookie(job.get("cookies_file") or "")
        if media == "audio":
            fmt = "bestaudio/best"
        else:
            h = f"[height<={chosen}]" if chosen else ""
            fmt = f"bv*[ext=mp4]{h}+ba[ext=m4a]/bv*{h}+ba/b{h}/b"
        _job_log(job_id, f"Mode: {mode} · {media} · format: {fmt}")

        outtmpl = str(out_dir / ("%(playlist_index)s - %(title)s.%(ext)s" if mode == "playlist"
                                 else "%(title)s.%(ext)s"))
        opts = {
            "format": fmt, "outtmpl": outtmpl, "ignoreerrors": True, "noprogress": True,
            "quiet": True, "no_warnings": True, "concurrent_fragment_downloads": 8, "trim_file_name": 150,
            "retries": 20, "fragment_retries": 20, "extractor_retries": 5, "socket_timeout": 30,
            "progress_hooks": [_make_progress_hook(job_id)],
            "postprocessor_hooks": [_make_pp_hook(job_id)],
            "logger": YtdlpJobLogger(job_id),
        }
        if cookies:
            opts["cookiefile"] = cookies
        if _FFMPEG:
            opts["ffmpeg_location"] = _FFMPEG
        else:
            _job_log(job_id, "ffmpeg missing: video and audio CANNOT be merged!", "error")
        if media == "audio":
            opts["postprocessors"] = [{"key": "FFmpegExtractAudio", "preferredcodec": "mp3",
                                       "preferredquality": "192"}]
        else:
            opts["merge_output_format"] = "mp4"

        targets = job["urls"]
        planned = len(targets)
        if mode == "playlist":
            items = (job.get("playlist_items") or "").strip()
            if items:
                opts["playlist_items"] = items
                n = 0
                for part in items.split(","):
                    a, _, b = part.strip().partition("-")
                    n += max(1, int(b) - int(a) + 1) if a.isdigit() and b.isdigit() else 1
                planned = max(1, n)
            else:
                planned = job.get("total_items") or 1
        else:
            opts["noplaylist"] = True

        if shutil.which("aria2c") and planned > 1:
            opts["external_downloader"] = "aria2c"
            opts["external_downloader_args"] = ["-x", "4", "-s", "4", "-k", "1M", "--max-tries=10",
                                                "--retry-wait=3", "--console-log-level=warn",
                                                "--summary-interval=0", "--allow-overwrite=true"]
            _job_log(job_id, "aria2c detected: turbo mode for multi-file job.")

        _job_update(job_id, stage="Downloading", total_items=planned)
        with YoutubeDL(opts) as ydl:
            ydl.download(targets)
        with JOBS_LOCK:
            if JOBS[job_id].get("cancel"):
                raise _Cancel("Cancelled by user")

        _fix_unmerged(out_dir, job_id)
        _job_update(job_id, stage="Collecting files")
        files = sorted(os.path.join(r, f) for r, _, fs in os.walk(out_dir) for f in fs
                       if not f.endswith((".part", ".ytdl")))
        links: list[dict] = []
        if mode == "single" or len(files) <= 1:
            for fp in files:
                rel = os.path.relpath(fp, ROOT).replace(os.sep, "/")
                links.append({"name": os.path.basename(fp),
                              "size_mb": round(os.path.getsize(fp) / 1048576, 1), "url": "/" + quote(rel)})
        else:
            _job_update(job_id, stage="Creating ZIP")
            _job_log(job_id, f"Creating ZIP with {len(files)} file(s)…")
            zip_name = f"{job['folder_name']}.zip"
            zip_path = DOWNLOADS_DIR / zip_name
            with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as zf:
                for fp in files:
                    zf.write(fp, arcname=os.path.relpath(fp, DOWNLOADS_DIR))
            shutil.rmtree(out_dir, ignore_errors=True)
            rel = os.path.relpath(zip_path, ROOT).replace(os.sep, "/")
            links.append({"name": zip_name, "size_mb": round(zip_path.stat().st_size / 1048576, 1),
                          "url": "/" + quote(rel), "is_zip": True, "file_count": len(files)})
        with JOBS_LOCK:
            JOBS[job_id].update(status="done", stage="Complete", current_pct=100, links=links)
        _job_log(job_id, "All done. Download link is ready.", "ok")
    except Exception as e:
        with JOBS_LOCK:
            cancelled = isinstance(e, DownloadCancelled) or bool(JOBS[job_id].get("cancel"))
        if cancelled:
            shutil.rmtree(DOWNLOADS_DIR / job["folder_name"], ignore_errors=True)
            _job_update(job_id, status="cancelled", stage="Cancelled")
            _job_log(job_id, "Cancelled. Partial files removed.", "warn")
            return
        traceback.print_exc()
        m = _friendly_error(e)
        _job_update(job_id, status="error", stage="Failed", error=m)
        _job_log(job_id, m, "error")


# ---------------- HTTP handler ----------------
HERE = Path(__file__).resolve().parent


def _load_index() -> str:
    p = HERE / "www" / "index.html"
    return p.read_text(encoding="utf-8") if p.exists() else "<h1>www/index.html is missing</h1>"


class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a, **k):
        pass

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def _send_json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads((self.rfile.read(n) if n else b"{}").decode("utf-8") or "{}")
        except Exception:
            return {}

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            body = _load_index().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path.startswith("/api/job/"):
            with JOBS_LOCK:
                j = JOBS.get(path.rsplit("/", 1)[-1])
                out = dict(j) if j else None
                if out:
                    out["log"] = list(j.get("log") or [])
                    out["links"] = list(j.get("links") or [])
            if not out:
                return self._send_json(404, {"error": "unknown job"})
            out.pop("cookies_file", None)
            self._send_json(200, out)
        elif path == "/api/files":
            self._send_json(200, {"files": _list_downloaded_files()})
        elif path.startswith("/downloads/") and not path.endswith("/"):
            super().do_GET()
        else:
            self.send_error(404)

    def do_DELETE(self):
        if urlparse(self.path).path != "/api/files":
            return self.send_error(404)
        try:
            rel = (self._read_json().get("path") or "").strip().lstrip("/")
            if not rel:
                return self._send_json(400, {"error": "Missing path"})
            target = (DOWNLOADS_DIR / rel).resolve()
            try:
                target.relative_to(DOWNLOADS_DIR.resolve())
            except ValueError:
                return self._send_json(400, {"error": "Invalid path"})
            if not target.exists():
                return self._send_json(404, {"error": "Not found"})
            shutil.rmtree(target) if target.is_dir() else target.unlink()
            p = target.parent
            if p != DOWNLOADS_DIR.resolve() and p.exists() and not any(p.iterdir()):
                p.rmdir()
            self._send_json(200, {"ok": True, "files": _list_downloaded_files()})
        except Exception as e:
            traceback.print_exc()
            self._send_json(500, {"error": str(e)})

    def do_POST(self):
        path = urlparse(self.path).path
        try:
            if path == "/api/cookies":
                return self._upload_cookies()
            data = self._read_json()
            jid = uuid.uuid4().hex[:12]
            if path == "/api/cancel":
                with JOBS_LOCK:
                    j = JOBS.get(data.get("job_id"))
                    if j is not None:
                        j["cancel"] = True
                return self._send_json(200, {"ok": j is not None})
            if path == "/api/probe":
                if not _split_urls(data.get("url", "")):
                    return self._send_json(400, {"error": "Please paste at least one YouTube URL."})
                with JOBS_LOCK:
                    JOBS[jid] = {"id": jid, "kind": "probe", "status": "queued", "stage": "Queued",
                                 "progress": 0, "url_text": data.get("url", ""),
                                 "cookies_file": _safe_cookie(data.get("cookies_file") or ""), "log": []}
                threading.Thread(target=run_probe, args=(jid,), daemon=True).start()
                return self._send_json(202, {"job_id": jid})
            if path == "/api/download":
                folder = re.sub(r"[^\w\-\. ]+", "_", data.get("title") or "download")[:80].strip() or "job"
                with JOBS_LOCK:
                    JOBS[jid] = {
                        "id": jid, "kind": "download", "status": "queued", "stage": "Queued",
                        "mode": data.get("mode") or "single", "media_type": data.get("media_type") or "video",
                        "resolution": data.get("resolution") or 0, "urls": data.get("urls") or [],
                        "playlist_items": data.get("playlist_items") or "",
                        "folder_name": f"{folder}_{jid[:4]}",
                        "cookies_file": _safe_cookie(data.get("cookies_file") or ""),
                        "current_file": "", "current_pct": 0, "completed_items": 0,
                        "total_items": data.get("total_items") or len(data.get("urls") or []) or 1, "log": []}
                threading.Thread(target=run_download, args=(jid,), daemon=True).start()
                return self._send_json(202, {"job_id": jid})
            self.send_error(404)
        except Exception as e:
            traceback.print_exc()
            self._send_json(500, {"error": str(e)})

    def _upload_cookies(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > 5 * 1024 * 1024:
            return self._send_json(400, {"error": "Cookie file must be under 5 MB."})
        body = self.rfile.read(n)
        ctype = self.headers.get("Content-Type", "")
        file_bytes = body
        if "multipart/form-data" in ctype and "boundary=" in ctype:
            boundary = ctype.split("boundary=", 1)[1].encode()
            file_bytes = None
            for p in body.split(b"--" + boundary):
                if b"filename=" in p:
                    he = p.find(b"\r\n\r\n")
                    if he != -1:
                        file_bytes = p[he + 4:].rstrip(b"\r\n-")
                        break
            if not file_bytes:
                return self._send_json(400, {"error": "No file found in upload."})
        text = file_bytes.decode("utf-8", errors="ignore")
        if "Netscape HTTP Cookie File" not in "\n".join(text.splitlines()[:3]) and "\t" not in text:
            return self._send_json(400, {"error": "Not a Netscape cookies.txt file."})
        cp = COOKIES_DIR / f"c_{uuid.uuid4().hex[:10]}.txt"
        cp.write_bytes(file_bytes)
        self._send_json(200, {"cookies_file": cp.name, "name": cp.name})


class ReuseServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 64

    def handle_error(self, request, client_address):
        # Browser cancelled / refreshed mid-transfer: harmless, don't spam tracebacks.
        if isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError)):
            return
        super().handle_error(request, client_address)





def main():
    port = int(os.environ.get("PORT") or 8000)
    os.chdir(ROOT)
    server = ReuseServer(("0.0.0.0", port), Handler)
    host = f"localhost:{port}"
    cs_domain, cs_name = os.environ.get("GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN"), os.environ.get("CODESPACE_NAME")
    if cs_domain and cs_name:
        print(f"\n  🌐 Open in browser:  https://{cs_name}-{port}.{cs_domain}\n")
    else:
        print(f"\n  🌐 Open in browser:  http://{host}\n")
    print("  (Ctrl+C to stop)\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down…")
        server.shutdown()


if __name__ == "__main__":
    main()
