#!/usr/bin/env python3
"""Build one small XMLTV guide for the channels in the playlist.

Reads every tvg-id from the playlist, downloads the guide sources below (in parallel),
keeps only matching <channel> and <programme> entries, and writes guide.xml.gz.
Sources are streamed and parsed on the fly, so memory use stays small however big they are.
If two sources have the same channel, the one listed first wins.
"""
import gzip
import html
import io
import json
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

BUDAPEST = ZoneInfo("Europe/Budapest")

PLAYLIST = "intl-english-tv.m3u"
OUTPUT = "guide.xml.gz"

SOURCES = [
    "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_SG1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_HU1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_IL1.xml.gz",   # i24NEWS English
    "https://epgshare01.online/epgshare01/epg_ripper_RS1.xml.gz",   # Pannon RTV
    # Free streaming (FAST) channels
    "https://epgshare01.online/epgshare01/epg_ripper_RAKUTEN1.xml.gz",
    "https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz",
    "https://i.mjh.nz/SamsungTVPlus/gb.xml.gz",
    "https://i.mjh.nz/au/Sydney/epg.xml.gz",                         # ABC News Australia
    # Channels no XMLTV guide has, built from the broadcasters' or TV listing sites' schedules
    "maxstream:0_iu9ah7cu=TVRIWorld.id@SD",   # maxstream.tv (Indonesia)
    "porthu:297=Balaton.TV.hu",                # port.hu
    "kapostv:KaposTV.hu@SD",                   # kapos.hu/kapostv/tv-musor.html
]
# For local testing: EPG_SOURCES="a.xml.gz b.xml" python3 merge_epg.py
if os.environ.get("EPG_SOURCES"):
    SOURCES = os.environ["EPG_SOURCES"].split()
# Try extra guides for one run: EPG_EXTRA="url1 url2" (workflow input "extra")
SOURCES += os.environ.get("EPG_EXTRA", "").split()
# Optional search: EPG_FIND="africa|bloomberg" lists matching channel IDs per source
FIND = re.compile(os.environ["EPG_FIND"], re.I) if os.environ.get("EPG_FIND") else None


def label(src):
    if src.startswith(tuple(BUILT)):
        return src
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


def to_xmltv(cid, progs):
    """XMLTV bytes for one channel from (start, stop, title, desc) with aware datetimes."""
    tv = ET.Element("tv")
    ch = ET.SubElement(tv, "channel", id=cid)
    ET.SubElement(ch, "display-name").text = cid
    fmt = lambda t: t.astimezone(timezone.utc).strftime("%Y%m%d%H%M%S +0000")
    seen = set()
    for start, stop, title, desc in sorted(progs, key=lambda p: p[0]):
        if start in seen or not title or stop <= start:
            continue
        seen.add(start)
        p = ET.SubElement(tv, "programme", start=fmt(start), stop=fmt(stop), channel=cid)
        ET.SubElement(p, "title").text = title
        if desc:
            ET.SubElement(p, "desc").text = desc
    return ET.tostring(tv, encoding="utf-8")


def fetch(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) "
                                                             "Gecko/20100101 Firefox/128.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def maxstream_guide(spec):
    """<video id>=<tvg-id>: the maxstream.tv schedule API."""
    vid, _, cid = spec.partition("=")
    data = json.loads(fetch(f"https://vmp.maxstream.tv/api/v3/videos/{vid}/schedules"))
    # The "Z" is wrong: the times are Jakarta local time (each day's list runs from midnight to
    # midnight on that clock; iptv-org's grabber reads them the same way)
    jakarta = timezone(timedelta(hours=7))
    parse = lambda t: datetime.strptime(t[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=jakarta)
    progs = []
    for day in data.get("data") or []:
        for m in day.get("metadata") or []:
            if m.get("parentId") == vid and m.get("startTime") and m.get("endTime"):
                progs.append((parse(m["startTime"]), parse(m["endTime"]), m.get("tvProgram"), m.get("description")))
    return to_xmltv(cid, progs)


def porthu_guide(spec):
    """<port.hu channel number>=<tvg-id>: port.hu's TV listing API, today and the next two days."""
    num, _, cid = spec.partition("=")
    progs, today = [], datetime.now(BUDAPEST).date()
    for d in range(3):
        day = (today + timedelta(days=d)).isoformat()
        data = json.loads(fetch(f"https://port.hu/tvapi?channel_id%5B%5D=tvchannel-{num}"
                                f"&i_datetime_from={day}&i_datetime_to={day}"))
        for block in (data.values() if isinstance(data, dict) else []):
            for ch in block.get("channels") or []:
                if ch.get("id") != f"tvchannel-{num}":
                    continue
                for p in ch.get("programs") or []:
                    try:
                        start = datetime.fromisoformat(p["start_datetime"])
                        stop = datetime.fromisoformat(p["end_datetime"])
                    except (KeyError, TypeError, ValueError):
                        continue
                    progs.append((start, stop, p.get("title"), p.get("description") or p.get("short_description")))
    return to_xmltv(cid, progs)


HU_MONTHS = {m: i for i, m in enumerate(["január", "február", "március", "április", "május", "június", "július",
                                          "augusztus", "szeptember", "október", "november", "december"], 1)}


def kapostv_guide(cid):
    """Kapos TV's own weekly schedule page (one table per day, times in Hungarian time)."""
    page = fetch("https://kapos.hu/kapostv/tv-musor.html").decode("utf-8", "replace")
    progs = []
    for body in re.split(r'<div id="tab-\d+-body"', page)[1:]:
        m = re.search(r"(\d{4})\.\s*(\w+)\s+(\d{1,2})\.", body)
        if not m or m.group(2).lower() not in HU_MONTHS:
            continue
        day = datetime(int(m.group(1)), HU_MONTHS[m.group(2).lower()], int(m.group(3)))
        rows = re.findall(r'<td class="left">\s*(\d{1,2})[.:](\d{2})\s*</td>\s*<td>(.*?)</td>', body, re.S)
        times = [(day.replace(hour=int(h), minute=int(mi)).replace(tzinfo=BUDAPEST),
                  re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", t))).strip()) for h, mi, t in rows]
        for i, (start, title) in enumerate(times):
            # each programme runs until the next one; the day's last one is given an hour
            stop = times[i + 1][0] if i + 1 < len(times) else start + timedelta(hours=1)
            progs.append((start, stop, title, None))
    return to_xmltv(cid, progs)


BUILT = {"maxstream:": maxstream_guide, "porthu:": porthu_guide, "kapostv:": kapostv_guide}


def open_source(src):
    for prefix, build in BUILT.items():
        if src.startswith(prefix):
            raw = io.BufferedReader(io.BytesIO(build(src[len(prefix):])))
            return raw, raw
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
                        # Some guides list several logo URLs joined by "|"; players need one
                        for icon in el.findall("icon"):
                            if "|" in (icon.get("src") or ""):
                                icon.set("src", icon.get("src").split("|")[0])
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
            if cid not in channels and cid in res["channels"]:
                channels[cid] = res["channels"][cid]
            progs = res["programmes"].get(cid, [])
            if cid in owner or not progs:
                continue  # an earlier source already has its programmes (or this one has none)
            owner[cid] = src
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
