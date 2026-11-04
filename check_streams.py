#!/usr/bin/env python3
"""Check which streams in the playlist actually play.

For HLS it follows the master playlist to a variant and downloads the newest
segment, which is what a player does. Local 127.0.0.1 entries (MTVA Proxy) are
skipped. Extra URLs to test can be passed in CHECK_URLS (space-separated).

Note: when run on GitHub Actions the check happens from a US server, so channels
that are geo-restricted to Europe may show as failed although they play in Hungary.
"""
import os
import re
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

PLAYLIST = "intl-english-tv.m3u"
UA = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"


def get(url, limit=None, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.geturl(), r.read(limit) if limit else r.read()


def uris(text):
    return [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]


def check(name, url):
    # Player-style extra headers: url|Referer=https://...&User-Agent=...
    url, _, extra = url.partition("|")
    headers = dict(urllib.parse.parse_qsl(extra)) if extra else {}
    try:
        final, body = get(url, headers=headers)
        text = body[:200000].decode("utf-8", "replace")
        if text.lstrip().startswith("<?xml") or "<MPD" in text[:2000]:
            return f"OK   {name}: DASH manifest ({len(body)} bytes)"
        if not text.startswith("#EXTM3U"):
            return f"FAIL {name}: not a playlist ({len(body)} bytes)"
        if "#EXT-X-STREAM-INF" in text:  # master -> first variant
            final, body = get(urllib.parse.urljoin(final, uris(text)[0]), headers=headers)
            text = body.decode("utf-8", "replace")
        segs = uris(text)
        if not segs:
            return f"FAIL {name}: empty media playlist"
        _, data = get(urllib.parse.urljoin(final, segs[-1]), limit=2_000_000, headers=headers)
        return f"OK   {name}: newest segment {len(data)} bytes"
    except Exception as e:
        return f"FAIL {name}: {e}"


def main():
    entries = []
    lines = open(os.environ.get("CHECK_PLAYLIST") or PLAYLIST, encoding="utf-8").read().splitlines()
    for i, line in enumerate(lines):
        if not line.startswith("#EXTINF"):
            continue
        j, opts = i + 1, {}
        while j < len(lines) and lines[j].startswith("#"):  # #EXTVLCOPT:http-referrer=... etc.
            m = re.match(r"#EXTVLCOPT:http-(referrer|user-agent)=(.*)", lines[j].strip())
            if m:
                opts["Referer" if m.group(1) == "referrer" else "User-Agent"] = m.group(2)
            j += 1
        if j < len(lines):
            url = lines[j].strip()
            if url.startswith("http") and "127.0.0.1" not in url:
                if opts and "|" not in url:
                    url += "|" + urllib.parse.urlencode(opts)
                entries.append((line.rsplit(",", 1)[1], url))
    for n, url in enumerate(os.environ.get("CHECK_URLS", "").split(), 1):
        entries.insert(n - 1, (f"extra {n}", url))
    with ThreadPoolExecutor(8) as pool:
        results = list(pool.map(lambda e: check(*e), entries))
    for r in results:
        print(r)
    failed = sum(r.startswith("FAIL") for r in results)
    print(f"\n{len(results) - failed} OK, {failed} failed")


if __name__ == "__main__":
    sys.exit(main())
