#!/usr/bin/env python3
"""Build one small XMLTV guide for the channels in the playlist.

Reads every tvg-id from the playlist, downloads the guide sources below,
keeps only matching <channel> and <programme> entries, and writes guide.xml.gz.
"""
import gzip
import io
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

PLAYLIST = "intl-english-tv.m3u"
OUTPUT = "guide.xml.gz"

SOURCES = [
    "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_SG1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_HU1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_ID1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_DISTROTV1.xml.gz",
]
# For local testing: EPG_SOURCES="a.xml.gz b.xml" python3 merge_epg.py
if os.environ.get("EPG_SOURCES"):
    SOURCES = os.environ["EPG_SOURCES"].split()


def wanted_ids(path):
    with open(path, encoding="utf-8") as f:
        return {m.group(1) for m in re.finditer(r'tvg-id="([^"]+)"', f.read())}


def fetch(src):
    if src.startswith(("http://", "https://")):
        req = urllib.request.Request(src, headers={"User-Agent": "epg-merge/1.0"})
        with urllib.request.urlopen(req, timeout=180) as r:
            data = r.read()
    else:
        with open(src, "rb") as f:
            data = f.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return io.BytesIO(data)


def main():
    wanted = wanted_ids(PLAYLIST)
    channels, programmes = {}, []
    for src in SOURCES:
        try:
            stream = fetch(src)
            n = 0
            for _, el in ET.iterparse(stream, events=("end",)):
                if el.tag == "channel":
                    cid = el.get("id")
                    if cid in wanted and cid not in channels:
                        el.tail = "\n"
                        channels[cid] = ET.tostring(el, encoding="utf-8")
                    el.clear()
                elif el.tag == "programme":
                    if el.get("channel") in wanted:
                        el.tail = "\n"
                        programmes.append(ET.tostring(el, encoding="utf-8"))
                        n += 1
                    el.clear()
            print(f"OK   {src.rsplit('/', 1)[-1]}: {n} programmes kept")
        except Exception as e:  # one broken source must not kill the others
            print(f"FAIL {src}: {e}")

    if not channels:
        sys.exit("No matching channels found in any source; keeping the old guide.")

    with open(OUTPUT, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
        gz.write(b'<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="epg-merge">\n')
        for c in channels.values():
            gz.write(c)
        for p in programmes:
            gz.write(p)
        gz.write(b"</tv>\n")

    print(f"\nWrote {OUTPUT}: {len(channels)} channels, {len(programmes)} programmes")
    missing = sorted(wanted - set(channels))
    if missing:
        print("No guide data for:", ", ".join(missing))


if __name__ == "__main__":
    main()
