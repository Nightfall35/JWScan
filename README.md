
**Pure-Java Real-Time Wi-Fi Monitoring & Defense Platform**

```
██████╗  █████╗ ████████╗      ███████╗██╗    ██╗ █████╗ ██████╗ ███╗   ███╗
██╔══██╗██╔══██╗╚══██╔══╝      ██╔════╝██║    ██║██╔══██╗██╔══██╗████╗ ████║
██████╔╝███████║   ██║         ███████║██║ █╗ ██║███████║██████╔╝██╔████╔██║
██╔══██╗██╔══██║   ██║         ╚════██║██║███╗██║██╔══██║██╔══██╗██║╚██╔╝██║
██║  ██║██║  ██║   ██║         ███████║╚███╔███╔╝██║  ██║██║  ██║██║ ╚═╝ ██║
╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝         ╚══════╝ ╚══╝╚══╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝     ╚═╝
```

RAT Swarm is a lightweight, high-performance Wi-Fi analysis and defense tool written entirely in Java. It provides real-time visibility into 802.11 networks and includes automated countermeasures against common attacks.

### Features

| Feature                        | Status | Description
|--------------------------------|--------|--------------------------------------------------
| Real-time monitor-mode capture | Active | Full radiotap headers via pcap4j + Npcap/libpcap
| Live web dashboard             | Active | Leaflet + WebSocket, works on desktop & mobile
| Automatic rogue/evil-twin detection | Active | First-seen BSSID policy with instant alerting
| Automated counter-deauthentication | Active | Revenge mode when under attack
| Targeted & broadcast deauthentication | Active | Radiotap injection, works on all modern clients
| Open-network detection & audio alert | Active | Console bell + visual highlight
| Vendor identification (OUI lookup) | Active | Built-in + optional online update
| GPS-ready coordinates (plug-and-play) | Active | Real USB GPS support included
| Pure Java (JDK 11+)            | Active | No Python, no aircrack-suite, no external binaries

### Hardware Requirements
- Wi-Fi adapter that supports monitor mode and packet injection  
  Recommended: Alfa AWUS036ACH, AWUS036ACM, AWUS036NEH, AWUS036NH, any AR9271/RT3070 chipset
- OS: Windows 10/11 (Npcap) or Linux (libpcap)
- Optional: USB GPS receiver (NMEA 0183) – GlobalSat BU-353-S4, VK-162, u-blox 7/8

### Quick Start

```bash
# 1. Compile
javac -encoding UTF-8 -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar" *.java

# 2. Run (change COM port if using GPS)
java -cp ".;pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar" Rat 8080
```

Open http://localhost:8080 → live war-driving map appears instantly.

### Current Limitations & Roadmap

| Limitation                         | Description                                               | Planned Fix
|------------------------------------|-----------------------------------------------------------|------------------------
| Single-channel operation           | No automatic channel hopping                              | In progress
| Fake GPS jitter by default         | Real GPS works but requires manual COM port configuration | Documentation + auto-detect
| No full packet forging             | Only deauth/disassoc injection (no fake beacons yet)      | Future release
| No encrypted communication         | WebSocket and console output are plaintext                | Future release
| No multi-instance / C2             | Single system only                                        | Future enterprise version
| Windows-centric development        | Linux works but requires minor tweaks                     | Ongoing

### Legal & Ethical Notice

This tool is intended for:
- Authorized security assessments
- Educational purposes
- Network administrators monitoring their own infrastructure
- Educational and research activities

Deauthentication attacks and unauthorized monitoring are illegal in most jurisdictions when performed without explicit permission.

The authors and contributors assume no liability for misuse.

### License

MIT License – feel free to use, modify, and distribute for legal and ethical purposes.

### Credits & Acknowledgments

- pcap4j project – pure-Java packet capture
- OpenStreetMap & Leaflet contributors
- All testers who pushed this tool to its limits in 2025

RAT Swarm – Because sometimes the best defense is a swarm that bites back.

Happy hunting (responsibly).
