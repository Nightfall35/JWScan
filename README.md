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
| OS | Windows / Linux | Windows tested; Linux supported via libpcap |

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

### 4. Compile
```powershell
 javac -encoding UTF-8 -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;json-20210307.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar;slf4j-api-1.7.36.jar;slf4j-simple-1.7.36.jar;openpdf-1.3.30.jar" *.java
```

### 5. Run (as Administrator — required for raw packet capture)
```powershell
java -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;json-20210307.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar;slf4j-api-1.7.36.jar;slf4j-simple-1.7.36.jar;." Rat 8080
```

Or use the included `run.bat` (right-click → Run as Administrator).

### 6. Open dashboard
```
http://localhost:8080
```

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

- [ ] PCAP file export for offline analysis
- [ ] WPA handshake capture detection
- [ ] Channel hopping support
- [ ] CSV/JSON export of discovered APs
- [ ] Historical session replay in dashboard
- [ ] Mobile-responsive dashboard layout
- [ ] Docker container for Linux deployment
- [ ] Raspberry Pi wardriving mode (headless + GPS auto-detect)

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
