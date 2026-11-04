#!/usr/bin/env python3
"""Build one small XMLTV guide for the channels in the playlist.

Reads every tvg-id from the playlist, downloads the guide sources below (in parallel),
keeps only matching <channel> and <programme> entries, and writes guide.xml.gz.
Sources are streamed and parsed on the fly, so memory use stays small however big they are.
If two sources have the same channel, the one listed first wins.
"""
import gzip
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

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
# Optional search: EPG_FIND="africa|bloomberg" lists matching channel IDs per source
FIND = re.compile(os.environ["EPG_FIND"], re.I) if os.environ.get("EPG_FIND") else None


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
                names = ids.setdefault(m.group(1), [])
                if name not in names:
                    names.append(name)
    return ids


def open_source(src):
    """Returns (raw, stream): the guide as an XML byte stream, decompressed on the fly if gzipped."""
    if src.startswith(("http://", "https://")):
        req = urllib.request.Request(src, headers={"User-Agent": "epg-merge/1.0"})
        raw = urllib.request.urlopen(req, timeout=180)
    else:
        raw = open(src, "rb")
    gzipped = raw.peek(2)[:2] == b"\x1f\x8b"
    return raw, (gzip.GzipFile(fileobj=raw) if gzipped else raw)


def parse_source(src, wanted):
    """Keeps only the wanted channels and their programmes from one guide."""
    res = {"channels": {}, "programmes": {}, "found": {}, "error": None}
    try:
        raw, stream = open_source(src)
        with raw:
            root = None
            for event, el in ET.iterparse(stream, events=("start", "end")):
                if root is None:
                    root = el  # <tv>
                    continue
                if event != "end" or el.tag not in ("channel", "programme"):
                    continue
                if el.tag == "channel":
                    cid = el.get("id") or ""
                    dname = el.findtext("display-name") or ""
                    if FIND and FIND.search(cid + " " + dname):
                        res["found"].setdefault(cid, [0, dname])
                    if cid in wanted and cid not in res["channels"]:
                        # The playlist's own names first, so players that match by name also find it
                        for i, name in enumerate(wanted[cid]):
                            d = ET.Element("display-name", {"lang": "en"})
                            d.text = name
                            el.insert(i, d)
                        el.tail = "\n"
                        res["channels"][cid] = ET.tostring(el, encoding="utf-8")
                else:
                    ch = el.get("channel") or ""
                    if ch in wanted:
                        el.tail = "\n"
                        res["programmes"].setdefault(ch, []).append(ET.tostring(el, encoding="utf-8"))
                    if FIND and (ch in res["found"] or FIND.search(ch)):
                        res["found"].setdefault(ch, [0, ""])[0] += 1
                root.clear()  # drop everything parsed so far: memory stays flat
    except Exception as e:  # one broken source must not stop the others
        res["error"] = e
    return res


def main():
    wanted = wanted_ids(PLAYLIST)
    with ThreadPoolExecutor(max_workers=len(SOURCES)) as pool:
        results = list(pool.map(lambda s: parse_source(s, wanted), SOURCES))

    channels, programmes, owner = {}, [], {}
    for src, res in zip(SOURCES, results):
        if res["error"]:
            print(f"FAIL {label(src)}: {res['error']}")
            continue
        kept = 0
        ids = list(res["channels"]) + [c for c in res["programmes"] if c not in res["channels"]]
        for cid in ids:
            if cid in owner:
                continue  # an earlier source already has this channel
            owner[cid] = src
            if cid in res["channels"]:
                channels[cid] = res["channels"][cid]
            progs = res["programmes"].get(cid, [])
            programmes.extend(progs)
            kept += len(progs)
        print(f"OK   {label(src)}: {kept} programmes kept")
        for cid, (cnt, dname) in sorted(res["found"].items()):
            print(f"     found: {cid}  [{dname}]  ({cnt} programmes)")

    if not owner:
        sys.exit("No matching channels found in any source; keeping the old guide.")

    with open(OUTPUT, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
        gz.write(b'<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="epg-merge">\n')
        for c in channels.values():
            gz.write(c)
        for p in programmes:
            gz.write(p)
        gz.write(b"</tv>\n")

    print(f"\nWrote {OUTPUT}: {len(channels)} channels, {len(programmes)} programmes")
    missing = sorted(set(wanted) - set(owner))
    if missing:
        print("No guide data for:", ", ".join(missing))


if __name__ == "__main__":
    main()
