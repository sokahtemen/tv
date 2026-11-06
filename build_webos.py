#!/usr/bin/env python3
"""Builds webos.m3u: the main playlist for TVs without the TV Hub app (LG webOS, Samsung, ...).

Leaves out the channels that only play through the app (M1, M2, Duna, M4 Sport, M5, Duna World) and
plays the other proxied streams (e.g. TVRI World) directly. No groups. The guide is linked uncompressed,
because some TV apps can't read .gz guides.
"""
import re
import sys
import urllib.parse

SRC, OUT = "intl-english-tv.m3u", sys.argv[1] if len(sys.argv) > 1 else "webos.m3u"
GUIDE = "https://raw.githubusercontent.com/sokahtemen/tv/epg/guide.xml"

lines = open(SRC, encoding="utf-8").read().splitlines()
out = [f'#EXTM3U url-tvg="{GUIDE}" x-tvg-url="{GUIDE}"']
dropped = []
i = 1
while i < len(lines):
    line = lines[i]
    if not line.startswith("#EXTINF"):
        i += 1
        continue
    url = lines[i + 1].strip() if i + 1 < len(lines) else ""
    name = line.rsplit(",", 1)[1]
    i += 2
    if url.startswith("http://127.0.0.1:"):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        if "/p/_" in url and "u" in q and "ref" not in q:
            url = q["u"][0]  # e.g. TVRI World: the station's own address (all its qualities)
        else:
            dropped.append(name)
            continue
    out += [re.sub(r'\s*group-title="[^"]*"', "", line), url]
open(OUT, "w", encoding="utf-8").write("\n".join(out) + "\n")
print(f"{OUT}: {(len(out) - 1) // 2} channels; left out (need the app): {', '.join(dropped)}")
