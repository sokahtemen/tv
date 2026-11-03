# tv

IPTV playlist with a merged daily TV guide.

- Playlist: https://raw.githubusercontent.com/sokahtemen/tv/main/intl-english-tv.m3u
- Guide (EPG): https://raw.githubusercontent.com/sokahtemen/tv/epg/guide.xml.gz

`merge_epg.py` reads the channel IDs (`tvg-id`) from the playlist, downloads the epgshare01 guides listed in it,
keeps only those channels and writes `guide.xml.gz`. The **Build EPG** workflow runs it every 6 hours
and publishes the result to the `epg` branch. To add a channel to the guide, add it to the playlist with a matching `tvg-id`.
