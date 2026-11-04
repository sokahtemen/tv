#!/usr/bin/env python3
"""Serve MTVA's free live channels (M1, M2, Duna, M4 Sport, M5, Duna World)
to IPTV players on the home network.

MTVA's streams use short-lived addresses, so they can't sit in a playlist file.
For every channel request this starts Streamlink, which fetches a fresh stream
from MTVA's own player (mediaklikk.hu / m4sport.hu) and passes it on as plain
MPEG-TS over HTTP. Nothing is re-encoded, so a Raspberry Pi is enough.

It must run on a machine in Hungary: MTVA's live streams are geo-restricted.

    pip install streamlink        (or your distro's streamlink package)
    python3 mtva_proxy.py         (listens on port 8088)

Player playlist:  http://<this-machine-ip>:8088/playlist.m3u
Single channel:   http://<this-machine-ip>:8088/m1
"""
import shutil
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8088

# path: (name, tvg-id in the Hungarian guide, MTVA live pages tried in order)
CHANNELS = {
    "m1": ("M1", "m1.HD.hu", ("https://www.mediaklikk.hu/m1-elo/", "https://hirado.hu/elo/m1")),
    "m2": ("M2 / Petőfi TV", "m2.HD.hu", ("https://www.mediaklikk.hu/m2-elo/", "https://hirado.hu/elo/m2")),
    "duna": ("Duna", "Duna.TV.hu", ("https://www.mediaklikk.hu/duna-elo/",)),
    "m4sport": ("M4 Sport", "m4.hu", ("https://m4sport.hu/elo/",)),
    "m5": ("M5", "m5.hu", ("https://www.mediaklikk.hu/m5-elo/",)),
    # Duna World shares its channel with M4 Sport+ (weekend afternoons); one stream carries both
    "dunaworld": ("Duna World / M4 Sport+", "Duna.World.hu", ("https://m4sport.hu/elo/?showchannel=mtv4plus",)),
}

STREAMLINK = ["streamlink"] if shutil.which("streamlink") else [sys.executable, "-m", "streamlink"]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?", 1)[0].strip("/")
        if path in ("", "playlist.m3u"):
            return self.send_playlist()
        channel = CHANNELS.get(path.removesuffix(".ts"))
        if not channel:
            return self.send_error(404, "Unknown channel")
        self.stream(*channel)

    def send_playlist(self):
        host = self.headers.get("Host") or f"localhost:{PORT}"
        lines = ["#EXTM3U"]
        for key, (name, tvg_id, _) in CHANNELS.items():
            lines.append(f'#EXTINF:-1 tvg-name="{name}" tvg-id="{tvg_id}" group-title="Magyar közszolgálati",{name}')
            lines.append(f"http://{host}/{key}")
        body = ("\n".join(lines) + "\n").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "audio/x-mpegurl; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def stream(self, name, _tvg_id, pages):
        proc, first = None, b""
        for page in pages:  # the first page that gives video wins
            proc = subprocess.Popen(
                STREAMLINK + ["--stdout", "--loglevel", "warning", "--stream-timeout", "30", page, "best"],
                stdout=subprocess.PIPE,
            )
            first = proc.stdout.read(188 * 100)  # wait for real video before answering
            if first:
                break
            proc.kill()
            proc.wait()
        try:
            if not first:
                self.log_message("%s: no stream from MTVA (offline or geo-blocked?)", name)
                return self.send_error(502, "Stream not available")
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            self.wfile.write(first)
            while data := proc.stdout.read1(65536):
                self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # the player switched channel or stopped
        finally:
            proc.kill()
            proc.wait()
            self.log_message("%s: stopped", name)

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.address_string()} {fmt % args}\n")


if __name__ == "__main__":
    server = ThreadingHTTPServer(("", PORT), Handler)
    server.daemon_threads = True
    print(f"MTVA proxy on port {PORT}: http://<this-machine-ip>:{PORT}/playlist.m3u", flush=True)
    server.serve_forever()
