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
    # Free streaming (FAST) channels
    "https://epgshare01.online/epgshare01/epg_ripper_RAKUTEN1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz",
    "https://i.mjh.nz/SamsungTVPlus/gb.xml.gz",
]
# For local testing: EPG_SOURCES="a.xml.gz b.xml" python3 merge_epg.py
if os.environ.get("EPG_SOURCES"):
    SOURCES = os.environ["EPG_SOURCES"].split()
# Try extra guides for one run: EPG_EXTRA="url1 url2" (workflow input "extra")
SOURCES += os.environ.get("EPG_EXTRA", "").split()


def label(src):
    return "/".join(src.rstrip("/").split("/")[-2:])


def wanted_ids(path):
    """Map each tvg-id in the playlist to the channel names that use it."""
    ids = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.search(r'tvg-id="([^"]+)"', line)
            if line.startswith("#EXTINF") and m and "," in line:
                name = re.sub(r"\s*\(alt\)$", "", line.rsplit(",", 1)[1].strip())
                ids.setdefault(m.group(1), [])
                if name not in ids[m.group(1)]:
                    ids[m.group(1)].append(name)
    return ids


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
    # Optional search: EPG_FIND="africa|bloomberg" lists matching channel IDs per source
    find = re.compile(os.environ["EPG_FIND"], re.I) if os.environ.get("EPG_FIND") else None
    channels, programmes = {}, []
    for src in SOURCES:
        try:
            stream = fetch(src)
            n, found = 0, {}
            for _, el in ET.iterparse(stream, events=("end",)):
                if el.tag == "channel":
                    cid = el.get("id")
                    if cid in wanted and cid not in channels:
                        # Put the playlist's own channel names first, so players
                        # that match the guide by name (not by tvg-id) also find it
                        for i, name in enumerate(wanted[cid]):
                            d = ET.Element("display-name", {"lang": "en"})
                            d.text = name
                            el.insert(i, d)
                        el.tail = "\n"
                        channels[cid] = ET.tostring(el, encoding="utf-8")
                    dname = el.findtext("display-name") or ""
                    if find and find.search(cid + " " + dname):
                        found.setdefault(cid, [0, dname])
                    el.clear()
                elif el.tag == "programme":
                    ch = el.get("channel") or ""
                    if ch in wanted:
                        el.tail = "\n"
                        programmes.append(ET.tostring(el, encoding="utf-8"))
                        n += 1
                    if find and (ch in found or find.search(ch)):
                        found.setdefault(ch, [0, ""])[0] += 1
                    el.clear()
            print(f"OK   {label(src)}: {n} programmes kept")
            for cid, (cnt, dname) in sorted(found.items()):
                print(f"     found: {cid}  [{dname}]  ({cnt} programmes)")
        except Exception as e:  # one broken source must not kill the others
            print(f"FAIL {label(src)}: {e}")

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
    missing = sorted(set(wanted) - set(channels))
    if missing:
        print("No guide data for:", ", ".join(missing))


if __name__ == "__main__":
    main()
