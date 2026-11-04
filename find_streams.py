#!/usr/bin/env python3
"""Look for live stream addresses on TV station web pages.

For each page in FIND_URLS (space-separated) prints the HTTP status and title, then any
.m3u8/.mpd addresses, YouTube/Facebook/Twitch links, iframe and video sources found in the HTML,
following iframes one level deep (players are usually embedded from another site).
"""
import html
import os
import re
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
PATTERNS = {
    "stream": r"""https?:[^"'\s<>]+?\.(?:m3u8|mpd)[^"'\s<>]*""",
    "youtube": r"""https?://(?:www\.)?(?:youtube\.com/(?:embed/|watch\?v=|live/|channel/|@)[^"'\s<>]+|youtu\.be/[^"'\s<>]+)""",
    "facebook": r"""https?://(?:www\.)?facebook\.com/[^"'\s<>]+/(?:videos|live)[^"'\s<>]*""",
    "iframe": r"""<iframe[^>]+src=["']([^"']+)""",
    "video": r"""<(?:video|source)[^>]+src=["']([^"']+)""",
}


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.status, r.geturl(), r.read(3_000_000).decode("utf-8", "replace")


def scan(url, depth=0):
    pad = "    " * depth
    try:
        status, final, text = get(url)
    except Exception as e:
        print(f"{pad}FAIL {url}: {e}")
        return
    title = re.search(r"<title[^>]*>(.*?)</title>", text, re.S | re.I)
    print(f"{pad}{status} {final}  [{html.unescape(title.group(1)).strip()[:80] if title else ''}]")
    seen, iframes = set(), []
    for kind, pat in PATTERNS.items():
        for m in re.finditer(pat, text, re.I):
            found = html.unescape(m.group(1) if m.groups() else m.group(0)).replace("\\/", "/")
            found = urllib.parse.urljoin(final, found)
            if found in seen:
                continue
            seen.add(found)
            print(f"{pad}  {kind}: {found}")
            if kind == "iframe":
                iframes.append(found)
    if depth == 0:
        for f in iframes[:5]:
            scan(f, 1)
        # Sites built with JavaScript often keep the player address in their own script files
        host = urllib.parse.urlparse(final).netloc
        scripts = [urllib.parse.urljoin(final, s) for s in re.findall(r"""<script[^>]+src=["']([^"']+)""", text, re.I)]
        for s in [s for s in scripts if urllib.parse.urlparse(s).netloc == host][:15]:
            try:
                _, _, js = get(s)
            except Exception:
                continue
            for m in re.finditer(PATTERNS["stream"] + "|" + PATTERNS["youtube"], js, re.I):
                found = m.group(0).replace("\\/", "/")
                if found not in seen:
                    seen.add(found)
                    print(f"  in script {s.rsplit('/', 1)[-1][:40]}: {found}")


for u in os.environ.get("FIND_URLS", "").split():
    scan(u)
    print()
