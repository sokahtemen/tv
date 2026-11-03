# tv

IPTV playlist with a merged daily TV guide.

- Playlist: https://raw.githubusercontent.com/sokahtemen/tv/main/intl-english-tv.m3u
- Guide (EPG): https://raw.githubusercontent.com/sokahtemen/tv/epg/guide.xml.gz

`merge_epg.py` reads the channel IDs (`tvg-id`) from the playlist, downloads the epgshare01 guides listed in it,
keeps only those channels and writes `guide.xml.gz`. The **Build EPG** workflow runs it every 6 hours
and publishes the result to the `epg` branch. To add a channel to the guide, add it to the playlist with a matching `tvg-id`.

## Hungarian public channels (M1, M2, Duna, M4 Sport, M4 Sport+, M5, Duna World)

MTVA's live streams use short-lived, geo-restricted addresses, so they can't be listed in the
playlist directly. `mtva_proxy.py` runs on an always-on machine at home (in Hungary). For each channel
request it lets [Streamlink](https://streamlink.github.io/) fetch a fresh stream from MTVA's own player
and passes it on as plain MPEG-TS. Nothing is re-encoded, so a Raspberry Pi is enough.

```sh
sudo pacman -S streamlink        # or: sudo apt install streamlink / pip install streamlink
python3 mtva_proxy.py            # port 8088
```

Test in VLC: `http://<machine-ip>:8088/m1`. Channel list: `http://<machine-ip>:8088/playlist.m3u`.

Run it permanently with systemd (`/etc/systemd/system/mtva-proxy.service`):

```ini
[Unit]
Description=MTVA live channels for IPTV players
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 /opt/tv/mtva_proxy.py
Restart=on-failure
DynamicUser=yes

[Install]
WantedBy=multi-user.target
```

Then `sudo systemctl enable --now mtva-proxy`. Give the machine a fixed IP in the router so the playlist
addresses stay valid.
