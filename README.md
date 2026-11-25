<div align="center">

```
██████╗  █████╗ ████████╗   ███████╗██╗    ██╗ █████╗ ██████╗ ███╗   ███╗
██╔══██╗██╔══██╗╚══██╔══╝   ██╔════╝██║    ██║██╔══██╗██╔══██╗████╗ ████║
██████╔╝███████║   ██║      ███████╗██║ █╗ ██║███████║██████╔╝██╔████╔██║
██╔══██╗██╔══██║   ██║      ╚════██║██║███╗██║██╔══██║██╔══██╗██║╚██╔╝██║
██║  ██║██║  ██║   ██║      ███████║╚███╔███╔╝██║  ██║██║  ██║██║ ╚═╝ ██║
╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝      ╚══════╝ ╚══╝╚══╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝     ╚═╝
```

# BLACK ICE v2 — RAT SWARM

**Pure-Java 802.11 passive surveillance lattice with live cyberpunk dashboard**

[![Java](https://img.shields.io/badge/Java-17%2B-orange?style=flat-square&logo=java)](https://www.java.com)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux-blue?style=flat-square)]()
[![Status](https://img.shields.io/badge/Status-Active-brightgreen?style=flat-square)]()

*Born in Lusaka, Zambia — 2024*

</div>

---

> **R.A.T** = Not a Remote Access Trojan. Named after an actual rat crawling through my ceiling.
> — *Ishmael D. Tembo (NIGHTFALL35)*

---

## ⚠️ Legal Notice

This tool is for **authorized security research, education, and penetration testing only** — on networks you own or have **explicit written permission** to analyze. Active features (deauth, counter-deauth) are disabled by default and must only be used where legally permitted. The author accepts no liability for misuse.

---

## What Is This?

BLACK ICE v2 is a passive 802.11 wireless surveillance tool written entirely in Java. It captures beacon frames, probe requests, and deauthentication frames from nearby Wi-Fi networks using Npcap, geolocates discovered access points via the Wigle.net API, detects evil twin / rogue access points using an observation-frequency AI engine, and streams everything live to a cyberpunk-themed web dashboard with matrix rain, radar sweeps, and real-time map visualization.

No Python. No Node.js. Pure Java.

---

## Features

### 📡 Passive 802.11 Capture
- Captures **beacon frames** from all nearby access points without transmitting
- Captures **probe requests** from client devices scanning for networks
- Detects **deauthentication frames** (Wi-Fi jamming / attack indicator)
- Extracts **SSID, BSSID, channel, signal strength (dBm), and security type** from raw radiotap frames
- Supports **WPA2, WPA3, WPA, and OPEN** network classification
- Works on any Npcap-compatible Wi-Fi adapter in promiscuous mode
- Optional **PCAP export** — saves captured frames to a timestamped `.pcap` file for Wireshark (`--pcap` or `pcap_config.txt`; off by default)
- Optional **CSV/JSON export** of the discovered AP list (`--export-csv` / `--export-json`, or `export_config.txt`; off by default). Also available from the dashboard and `GET /api/v1/export/aps.csv` / `aps.json`.
- Optional **channel hopping** — periodically switches the adapter's listening channel so the scanner can observe APs across the band, not just one parked channel (`--hop` / `--channel-hop`, or `hopper_config.txt`; off by default)
- Optional **historical session recording + dashboard replay** — writes timestamped AP snapshots to `sessions\\session_<timestamp>.jsonl` (`--session-log` / `--record`, or `session_config.txt`; off by default). The dashboard REPLAY panel can play a past session back on the map (play/pause, timeline scrubber, speed).
- **Headless Linux / Raspberry Pi mode** — no desktop or GUI. Run the jar directly on a Pi (or any Linux box) or in Docker; the dashboard is served over the network on port 8080. GPS dongles at `/dev/ttyUSB0` (and similar) are auto-detected when `gps_port.txt` is absent.
- Always-on **WPA/WPA2 4-way handshake detection** — passively recognizes EAPOL-Key messages 1/4–4/4 (same idea as Wireshark's EAPOL display). When all four messages are seen between an AP and a client, the console logs `HANDSHAKE COMPLETE` and the AP's `handshakeSeen` / `handshakeCount` fields update in the dashboard, SSE JSON, and CSV/JSON exports. Observation only: no key material is stored, and nothing is transmitted.

### 🗺️ Live Geolocation
- Integrates with **Wigle.net API** to geolocate discovered access points by BSSID
- Falls back to **IP geolocation** (ip-api.com) when Wigle has no record
- Falls back to **jitter around operator position** when both fail
- **30-day geolocation cache** — no redundant API calls for already-seen BSSIDs
- Async geolocation via 4-thread executor — never blocks the capture loop
- Per-AP 30-second cooldown to respect Wigle rate limits

### 🧠 Swarm AI — Evil Twin Detection
- Builds an **observation frequency map** per SSID
- The BSSID seen most frequently is elected as **legitimate**
- Any other BSSID broadcasting the same SSID after 2+ observations triggers an **EVIL TWIN alert**
- **Hidden SSIDs are skipped** (can't be matched)
- Minimum 3 total observations before making any judgments (eliminates cold-start false positives)
- Duplicate alert suppression via `alreadyNuked` set
- Stale BSSID cleanup every 5 minutes (30-minute TTL)

### 🛡️ Attack Detection & Counter-Deauth
- Detects incoming **deauthentication attacks** targeting your MAC
- Optional **counter-deauth** (disabled by default) — retaliates against the attacking BSSID
- **Auto-nuke** for confirmed evil twin / rogue APs via broadcast deauth flood
- Configurable via `counterMode` flag in source

### 🛰️ Hardware GPS Integration
- Reads **NMEA sentences** from a serial GPS dongle (`$GPGGA`, `$GNGGA`)
- Supports **multi-constellation GPS** (GPS + GLONASS via `$GNGGA`)
- Configure port via `gps_port.txt` (e.g. `COM4` on Windows, `/dev/ttyUSB0` on Linux)
- Operator position tracked in real-time and broadcast to dashboard via SSE
- Falls back to browser geolocation when hardware GPS is unavailable

### 🌐 Live Web Dashboard
- Served at `http://localhost:8080` — no browser extension or install needed
- **Mobile-responsive** — wrapping header pills, bottom toolbar, and full-width drawers on phones/tablets; desktop layout unchanged. CRT flicker/sweep/noise disable on small screens
- **Server-Sent Events (SSE)** stream — updates every second, auto-reconnects on disconnect
- **Matrix rain** background (Japanese + Latin characters)
- **Hex grid overlay** that slowly drifts
- **Typewriter boot sequence** on load
- **Radar sweep ring** pulsing outward from operator position every 4 seconds
- **Live AP count, open network count, and threat level** in header
- **Threat badge** — NOMINAL → ELEVATED → ⚠ CRITICAL based on open network count
- **GPS status pill** — grey (searching) → cyan (hardware GPS locked)
- **Leaflet map** with dark cyberpunk tile filter
- **AP markers** — green (secure), red (open), amber (weak signal) with popup details
- **Pulsing rings** around open/unsecured networks
- **Operator position marker** with real-time GPS tracking
- **Side panel** — all APs sorted by signal strength, click-to-fly-to
- **Alert stream** — scrolling log of events (new APs, threats, GPS, SSE status)
- **Logo glitch animation** every 6 seconds

### 🔍 OUI Vendor Database
- Loads **38,000+ IEEE OUI entries** from local cache
- Auto-downloads from **IEEE standards server** or **Wireshark manuf file** on first run or when cache is 7+ days old
- Built-in fallback with 60+ common vendors (Cisco, TP-Link, Apple, Ubiquiti, Huawei, etc.)
- **Vendor inference** — identifies device type (Enterprise AP, Consumer Router, Wi-Fi Card) and likely owner type

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                        Rat.java                         │
│  ┌─────────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │PassiveScanner│  │SwarmAi   │  │  HTTP Server       │  │
│  │(pcap4j/Npcap)│  │Evil Twin │  │  /  → Dashboard    │  │
│  │802.11 frames │  │Detection │  │  /sse → SSE stream  │  │
│  └──────┬───────┘  └────┬─────┘  └────────────────────┘  │
│         │               │                                  │
│  ┌──────▼───────────────▼──────────────────────────────┐  │
│  │              AP State Map (ConcurrentHashMap)        │  │
│  └──────┬───────────────────────────────────────────────┘  │
│         │                                                   │
│  ┌──────▼──────┐  ┌─────────────┐  ┌──────────────────┐   │
│  │WigleGeolocator│  │OuiDatabase  │  │GPSReader         │  │
│  │Wigle.net API │  │IEEE/Wireshark│  │NMEA serial       │  │
│  │+ IP geo      │  │OUI cache     │  │$GPGGA / $GNGGA   │  │
│  └─────────────┘  └─────────────┘  └──────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## Requirements

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java JDK | 17+ | JDK 24 tested |
| Npcap | Latest | Install with **WinPcap API-compatible Mode** checked |
| Wi-Fi Adapter | Any | Monitor mode required for full frame capture |
| OS | Windows / Linux | Windows tested; Linux / Raspberry Pi via libpcap (no GUI needed) |
| libpcap | libpcap0.8+ | Linux / Pi only (`sudo apt install libpcap0.8 iw`). Windows uses Npcap. |
| Docker | 20+ (optional) | Headless container; see “Headless mode” below. Build on the Pi for ARM. |

### JAR Dependencies

Download these and place in your project folder:

| JAR | Download |
|-----|----------|
| `pcap4j-core-1.8.2.jar` | [Maven Central](https://repo1.maven.org/maven2/org/pcap4j/pcap4j-core/1.8.2/) |
| `pcap4j-packetfactory-static-1.8.2.jar` | [Maven Central](https://repo1.maven.org/maven2/org/pcap4j/pcap4j-packetfactory-static/1.8.2/) |
| `json-20210307.jar` | [Maven Central](https://repo1.maven.org/maven2/org/json/json/20210307/) |
| `jna-5.13.0.jar` | [Maven Central](https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/) |
| `jna-platform-5.13.0.jar` | [Maven Central](https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.13.0/) |
| `slf4j-api-1.7.36.jar` | [Maven Central](https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/) |
| `slf4j-simple-1.7.36.jar` | [Maven Central](https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/1.7.36/) |

---

## Setup

### 1. Install Npcap
Download from [npcap.com](https://npcap.com/#download) and install with:
- ☑ **Install Npcap in WinPcap API-compatible Mode**

### 2. Configure Wigle.net (optional but recommended)
Create `Wigle_config.txt` in your project folder:
```
API_NAME=YourWigleUsername
API_TOKEN=YourWigleAPIToken
```
Get your API token from [wigle.net/account](https://wigle.net/account) → Show API Token.

### 3. Configure GPS (optional)
If you have a serial GPS dongle, create `gps_port.txt`:
```
COM4
```
Linux: `/dev/ttyUSB0`

### 4. PCAP export (optional)
Off by default. To save captured 802.11 frames for Wireshark, pass `--pcap` when launching, or set `enabled=1` in `pcap_config.txt`:
```
enabled=1
dir=captures
```
Files are written as `captures\blackice_<yyyyMMdd_HHmmss>_<adapter>.pcap`. `--pcap` enables export even if the config file leaves it off.

### 5. AP list export (optional)
Off by default. To write the in-memory AP list (SSID, BSSID, channel, signal, security, vendor, coordinates if resolved, first/last seen) on shutdown, pass `--export-csv` and/or `--export-json` (or `--export` for both), or set the flags in `export_config.txt`:
```
csv=1
json=1
dir=exports
interval_sec=0
```
Files are written as `exports\blackice_<yyyyMMdd_HHmmss>.csv` and `.json`. `--export-csv` / `--export-json` enable automatic export even if the config file leaves them off. Set `interval_sec` to a positive number to also flush periodically.

A snapshot can be taken at any time, even when automatic export is off:
- Dashboard export strip: **AP CSV** / **AP JSON**
- `GET http://localhost:8080/api/v1/export/aps.csv?token=YOUR_TOKEN`
- `GET http://localhost:8080/api/v1/export/aps.json?token=YOUR_TOKEN`

The API token is printed at startup and stored in `api_token.txt`.

### 6. Channel hopping (optional)
Off by default. To sweep listening channels during a capture (same idea as Wireshark/airodump channel hop), pass `--hop` or `--channel-hop` when launching, or set `enabled=1` in `hopper_config.txt`:
```
enabled=1
dwell_ms=150
band_5ghz=1
band_6ghz=0
strategy=SMART
```
`--hop` enables hopping even if the config file leaves it off. Dwell time, 5/6 GHz inclusion, and strategy (`SEQUENTIAL`, `INTERLEAVED`, or `SMART`) are always read from the config file. Each channel change is logged to the console. Windows uses `netsh`; Linux uses `iw` (falling back to `iwconfig`). The adapter must support monitor-mode channel changes; if a switch fails, scanning continues on the current channel.

### 7. Historical session replay (optional)
Off by default. To record periodic snapshots of discovered APs during a scan (SSID, BSSID, signal, coordinates when resolved, timestamp), pass `--session-log` or `--record` when launching, or set `enabled=1` in `session_config.txt`:
```
enabled=1
dir=sessions
interval_sec=5
```
Files are written as `sessions\\session_<yyyyMMdd_HHmmss>.jsonl` (one snapshot per line). `--session-log` enables recording even if the config file leaves it off.

To replay a saved session, open the dashboard (`http://localhost:8080`), click the **REPLAY** button in the right control strip, pick a session, then use **PLAY** / the timeline slider. **LIVE** exits replay and returns to the live SSE feed. Listing and fetching sessions:
- `GET http://localhost:8080/api/v1/sessions?token=YOUR_TOKEN`
- `GET http://localhost:8080/api/v1/sessions/session_<timestamp>.jsonl?token=YOUR_TOKEN`

Replay works on previously recorded files even when recording is turned off for the current run. The API token is printed at startup and stored in `api_token.txt`.

### 8. WPA handshake detection
Always on and receive-only. While capturing, unencrypted 802.11 data frames are inspected for EAPOL-Key (EtherType `0x888E`). Key Information flags identify messages 1–4 of the pairwise 4-way handshake; group-key frames are ignored. A handshake is counted only when all four messages are observed for the same AP/client pair within 15 seconds.

On completion the console prints:
```
[ALERT]    HANDSHAKE COMPLETE → "SSID" AA:BB:CC:DD:EE:FF ↔ 11:22:33:44:55:66 (count 1)
```

The same AP shows `handshakeSeen` / `handshakeCount` in the SSE feed and API JSON, a `HANDSHAKE` row on the map popup, an `[HS ×N]` tag in the AP list, and matching columns in the AP CSV/JSON export. Detection does not crack, store nonces/MICs, or send deauth frames.

### 9. Compile
```powershell
 javac -encoding UTF-8 -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;json-20210307.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar;slf4j-api-1.7.36.jar;slf4j-simple-1.7.36.jar;openpdf-1.3.30.jar" *.java
```

### 10. Run (as Administrator — required for raw packet capture)
```powershell
java -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;json-20210307.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar;slf4j-api-1.7.36.jar;slf4j-simple-1.7.36.jar;." Rat 8080
```

With PCAP export enabled (files land in `captures\`):
```powershell
java -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;json-20210307.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar;slf4j-api-1.7.36.jar;slf4j-simple-1.7.36.jar;." Rat 8080 --pcap
```

Or, after `mvn package`:
```powershell
java -jar target\jwscan-2.0.0-jar-with-dependencies.jar 8080 --pcap
```

With AP-list export on shutdown (files land in `exports\`):
```powershell
java -jar target\jwscan-2.0.0-jar-with-dependencies.jar 8080 --export-csv --export-json
```

Both PCAP and AP export together:
```powershell
java -jar target\jwscan-2.0.0-jar-with-dependencies.jar 8080 --pcap --export
```

With channel hopping enabled (console logs each channel change):
```powershell
java -jar target\jwscan-2.0.0-jar-with-dependencies.jar 8080 --hop
```

With session recording enabled (files land in `sessions\`):
```powershell
java -jar target\jwscan-2.0.0-jar-with-dependencies.jar 8080 --session-log
```

PCAP export, AP-list export, channel hopping, and session recording together (handshake detection is always on):
```powershell
java -jar target\jwscan-2.0.0-jar-with-dependencies.jar 8080 --pcap --export --hop --session-log
```

Or use the included `run.bat` / `Start_Rat.bat` (right-click → Run as Administrator).

### 11. Open dashboard
On the machine running the scanner:
```
http://localhost:8080
```

On a phone or tablet on the same Wi-Fi, open `http://<PC-LAN-IP>:8080` (the dashboard already listens on all interfaces). In PowerShell, get the address with:

```powershell
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike '*Loopback*' } | Select-Object IPAddress, InterfaceAlias
```

Or run `ipconfig` and use the IPv4 address of your active adapter (often `Wireless LAN adapter Wi-Fi`). If the phone cannot connect, allow inbound TCP 8080 in Windows Firewall:

```powershell
New-NetFirewallRule -DisplayName "BLACK ICE dashboard" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
```

The desktop layout is unchanged. On narrow screens the header pills wrap, the right control strip becomes a bottom toolbar, and TARGETS / CHANNELS / ALERTS / REPLAY open as full-width drawers (tap the dimmed map or the × to close). CRT flicker, sweep, and film-grain noise are turned off on small screens, and whenever the OS requests reduced motion, so the map stays usable.

### 12. Headless mode (Linux / Raspberry Pi / Docker)

The process is a console app plus an HTTP dashboard — there is no desktop or AWT window. `Rat.main` sets `-Djava.awt.headless=true` so PDF reports (which only use `java.awt.Color`) work over SSH or in a container with no display.

**Linux capture (libpcap).** Install a JRE, libpcap, and `iw` (for optional channel hopping), then put the wireless adapter into monitor mode and run as root:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jre libpcap0.8 iw
sudo ip link set wlan0 down
sudo iw dev wlan0 set type monitor
sudo ip link set wlan0 up
```

Use your actual interface name (`ip link` or `iw dev`). USB adapters often show up as `wlan1` or `wlx…`.

**Bare metal on a Raspberry Pi** (after `mvn package`, or copy the fat jar from another machine — the jar is architecture-independent):

```bash
sudo java -Djava.awt.headless=true -jar target/jwscan-2.0.0-jar-with-dependencies.jar 8080
```

`sudo` is required so libpcap can open the adapter for raw 802.11 frames. Add the same optional flags as on Windows (`--pcap`, `--export`, `--hop`, `--session-log`). The console prints `DASHBOARD (LAN/<iface>) → http://<pi-ip>:8080` — open that URL from a phone or laptop on the same network. There is still no GUI on the Pi itself.

**GPS.** Create `gps_port.txt` with a single line, typically:

```
/dev/ttyUSB0
```

(`/dev/ttyACM0` for some u-blox dongles; `/dev/serial0` or `/dev/ttyAMA0` for the Pi UART.) If the file is missing, the reader auto-detects the first of those device nodes that exists. The process must be able to read the device (root, or membership in the `dialout` group). When no GPS is present, the dashboard falls back to browser geolocation.

**Docker.** Host networking and `NET_ADMIN` / `NET_RAW` are required so the container can see the host's Wi-Fi interfaces and open them for capture. Channel hopping also needs `iw` (already in the image) and `NET_ADMIN`.

```bash
docker build -t blackice .
docker run --rm --net=host --cap-add=NET_ADMIN --cap-add=NET_RAW blackice
```

With a USB GPS dongle and optional feature flags:

```bash
docker run --rm --net=host --cap-add=NET_ADMIN --cap-add=NET_RAW \
  --device=/dev/ttyUSB0 \
  -v "$PWD/captures:/app/captures" \
  -v "$PWD/exports:/app/exports" \
  -v "$PWD/sessions:/app/sessions" \
  blackice 8080 --hop --session-log
```

Or: `docker compose up --build` (same `--net=host` / capabilities; GPS device and config mounts are commented in `docker-compose.yml`). Build the image **on the Pi** (or `docker buildx build --platform linux/arm64`) for a 64-bit Pi. A 32-bit Pi OS is easier to run bare-metal with the `java -jar` command above.

The dashboard still listens on all interfaces (port 8080). Put the host adapter in monitor mode *before* starting the container if your driver requires it (same `iw` commands as above, run on the host).

---

## Project Structure

```
Jscanner/
├── Rat.java               # Main class + HTTP server + SSE + embedded dashboard HTML
├── PassiveScanner.java    # 802.11 frame capture via pcap4j/Npcap
├── SwarmAi.java           # Evil twin detection engine
├── WigleGeolocator.java   # Wigle.net + IP geolocation
├── OuiDatabase.java       # IEEE OUI vendor database
├── GPSReader.java         # Serial NMEA GPS reader
├── Deauther.java          # Frame injection (monitor mode only)
├── TestRunner.java        # Automated test suite
├── run.bat                # Windows launcher (run as Admin)
├── Wigle_config.txt       # Wigle API credentials (create this yourself)
├── gps_port.txt           # GPS serial port config (create if using GPS)
├── pcap_config.txt        # PCAP export toggle (enabled=0 by default)
├── captures/              # Timestamped .pcap dumps (created when export is on)
├── export_config.txt      # AP-list CSV/JSON export toggle (csv=0 / json=0 by default)
├── exports/               # Timestamped AP-list .csv / .json dumps
├── hopper_config.txt      # Channel hopper toggle (enabled=0 by default)
├── session_config.txt     # Session snapshot recording toggle (enabled=0 by default)
├── sessions/              # Timestamped session_<ts>.jsonl snapshot logs
├── Dockerfile             # Multi-stage Maven → slim JRE + libpcap image
├── docker-compose.yml     # Host-network / NET_ADMIN / NET_RAW survey node
├── .dockerignore
├── prebuilt/              # WigleGeolocator.class (source file is empty in this tree)
├── oui_cache.dat          # OUI database cache (auto-generated)
└── wigle_cache.dat        # Geolocation cache (auto-generated)
```

---

## Console Output Reference

| Output | Meaning |
|--------|---------|
| `[ALERT] NEW AP →` | New access point discovered |
| `[ALERT] LEGIT AP CONFIRMED →` | SwarmAi registered a legitimate BSSID |
| `[CRITICAL] OPEN NETWORK →` | Unsecured AP found — no encryption |
| `[CRITICAL] EVIL TWIN →` | Rogue AP broadcasting a known SSID |
| `[CRITICAL] ATTACK DETECTED →` | Deauth attack targeting your MAC |
| `[GEO] WIGLE FIX →` | Real GPS coordinates from Wigle.net |
| `[GPS] FIX →` | Hardware GPS dongle acquired satellite lock |
| `[SESSION] Recording snapshots` | Session log opened; writing under `sessions\\` |
| `[SESSION] Closed ... snapshots` | Session log flushed and closed on shutdown |
| `Broadcasting to N clients` | SSE working, N browser tabs open |
| `Wigle API rate limit (429)` | Too many requests — auto backs off 60s |

---

## Estimated Software Valuation

This is a student project, but for context — here's where comparable tools sit commercially:

| Comparison | Price | Notes |
|-----------|-------|-------|
| **Ekahau Site Survey** | $3,500–$8,000/yr | Professional Wi-Fi survey tool |
| **AirMagnet WiFi Analyzer** | $3,000–$6,000 | Enterprise 802.11 analysis |
| **Kismet** | Free (open source) | No web dashboard, no Wigle integration out of box |
| **NetStumbler** | Free (abandoned) | Windows only, no live dashboard |
| **Acrylic Wi-Fi** | $200–$500/yr | Consumer wardriving tool |
| **WiGLE Android App** | Free | Mobile only, no passive capture, no AI |
| **BLACK ICE v2** | **Open source** | Dashboard + geolocation + AI + GPS — pure Java |

As a **consulting deliverable** for a wireless security audit:
- Small business audit (1 site): **$500–$2,000 USD**
- Bank / enterprise site survey (multi-floor): **$3,000–$8,000 USD**
- Red team engagement with rogue AP detection: **$5,000–$15,000 USD**

---

## Roadmap

- [x] PCAP file export for offline analysis
- [x] WPA handshake capture detection (observation only)
- [x] Channel hopping support
- [x] CSV/JSON export of discovered APs
- [x] Historical session replay in dashboard
- [x] Mobile-responsive dashboard layout
- [x] Docker container for Linux deployment
- [x] Raspberry Pi wardriving mode (headless + GPS auto-detect)

---

## Author

**Ishmael D. Tembo**
Alias: NIGHTFALL35
Location: Lusaka, Zambia
GitHub: [Nightfall35](https://github.com/Nightfall35)
Email: ishamelgoku@gmail.com

> *"I am not a network engineer. Just a Java-obsessed fool."*

---

## License

MIT License — see [LICENSE](LICENSE) for details.

Free to use, modify, and distribute with attribution.
Active attack features (deauth, counter-deauth) must only be used with explicit written authorization.

---

<div align="center">
<sub>Built from scratch in Lusaka, Zambia. No frameworks. No shortcuts. Pure Java.</sub>
</div>
