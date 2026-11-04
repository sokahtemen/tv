#!/usr/bin/env python3
"""Long playback test: plays one channel through the local MTVA proxy like a player does.

Fetches the master playlist once, then refreshes the media playlist every target duration
and downloads each new segment, for the given number of minutes. Prints every failure, so it
shows whether a stream stops after a while (for example when an address expires).

    python3 soak_test.py m1 45        (the proxy must be running on 127.0.0.1:8088)
"""
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8088"


def get(path):
    with urllib.request.urlopen(urllib.parse.urljoin(BASE, path), timeout=30) as r:
        return r.read()


def uris(text):
    return [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]


def main():
    channel = sys.argv[1] if len(sys.argv) > 1 else "m1"
    minutes = float(sys.argv[2]) if len(sys.argv) > 2 else 45
    start = time.time()
    stamp = lambda: f"{(time.time() - start) / 60:6.1f} min"

    master = get(f"/{channel}.m3u8").decode()
    print("master playlist:\n" + "\n".join(l[:160] for l in master.splitlines()[:12]) + "\n", flush=True)
    media_path = uris(master)[0] if "#EXT-X-STREAM-INF" in master else f"/{channel}.m3u8"

    seen, segments, errors, last_report = set(), 0, 0, 0
    while time.time() - start < minutes * 60:
        wait = 6
        try:
            text = get(media_path).decode()
            for line in text.splitlines():
                if line.startswith("#EXT-X-TARGETDURATION:"):
                    wait = max(2, int(line.split(":")[1]))
            for seg in uris(text):
                if seg not in seen:
                    get(seg)
                    seen.add(seg)
                    segments += 1
            if len(seen) > 500:
                seen = set(uris(text))
        except Exception as e:
            errors += 1
            print(f"{stamp()}  FAIL: {e}", flush=True)
        if time.time() - last_report > 300:
            print(f"{stamp()}  ok so far: {segments} segments, {errors} errors", flush=True)
            last_report = time.time()
        time.sleep(wait)
    print(f"\nDone after {stamp()}: {segments} segments downloaded, {errors} errors")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
