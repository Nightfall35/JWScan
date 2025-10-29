# JWScan
A java wifi scanner 


# Rat — Wi-Fi Crawler & Open Network Monitor

> *"The Rat wakes, crawls, and listens to the airwaves."*

`Rat` is a lightweight Java console utility that continuously scans nearby Wi-Fi access points (APs) and alerts you when **new networks** or **open (unsecured)** ones appear.  
It’s built for simplicity, portability, and automation — runs on **Windows** (`netsh`) and **Linux** (`nmcli` / `iwlist`) and adapts automatically.

---

## 🚀 Features

- 📡 **Auto-scanning** every N seconds  
- 🚨 **Alerts for newly discovered APs**  
- 🔓 **Strong alert for open / unsecured networks**  
- 💾 **Remembers previously seen APs in memory**  
- 🧠 **Cross-platform detection** (Windows + Linux)  
- 🔔 **Terminal bell** on open network discovery  
- ⏱️ **Graceful shutdown** with `Ctrl + C`

---

## ⚙️ How It Works

1. On startup, the Rat performs an initial scan.  
2. Every interval (`N` seconds), it rescans and compares results.  
3. If a **new AP** is found → prints `[ALERT]`.  
4. If a **new open AP** is found → prints `[*****CRITICAL ALERT******]` + bell sound.  
5. Maintains an in-memory map of all seen APs to avoid duplicate alerts.

---

## 💻 Example Output

Rat is waking up.........
Rat has awakened, beginning crawling
Estimated time (interval: 30s). Press ctrl+c to exit.

[2025-10-29 05:00:00] [ALERT] NEW AP discovered: SSID='Cafe_FreeWiFi' BSSID=AA:BB:CC:DD:EE:FF SEC='OPEN' SIG=89%
[2025-10-29 05:00:00] [CRITICAL ALERT*] !! NEW OPEN NETWORK detected: SSID='Cafe_FreeWiFi' ...
[2025-10-29 05:00:30] Crawling complete. Total unique seen: 6 | currently visible: 4




---

## 🧩 Requirements

| Platform | Dependency | Description |
|-----------|-------------|-------------|
| Windows | `netsh` | Built-in Wi-Fi management tool |
| Linux | `nmcli` or `iwlist` | NetworkManager or Wireless Tools |
| Any | Java 11+ | Required to compile & run |

---

## 🏗️ Build & Run

**Compile:**
```bash
javac Rat.java


Run (default 30-second interval):

java Rat 30


Custom interval (e.g., 10s):

java Rat 10


Stop: Press Ctrl + C — Rat shuts down gracefully



🧠 Output Codes
Symbol	Meaning
[ALERT]	New AP discovered
[*****CRITICAL ALERT******]	New open (unsecured) AP found
[timestamp]	Local date/time of event
🗂️ Internal Structure
Rat.java
├── Rat (main class)
│   ├── scanAndAlert()         → main periodic scanner
│   ├── scanWindows()          → uses `netsh wlan show networks`
│   ├── scanNmcli()            → uses `nmcli device wifi list`
│   ├── scanIwlist()           → uses `iwlist scanning`
│   ├── soundBell()            → plays terminal bell
│   ├── AP (inner class)       → model for network info
│   └── start()                → schedules periodic scanning

⚠️ Disclaimer

This tool is for educational and diagnostic purposes only — to help identify open or misconfigured networks around you.
Do not connect to or interfere with any network you do not own or have explicit permission to test.

👤 Author

Nightfall35
Programmer | Systems Engineer | Network Analyst

"Observe the noise. The Rat listens." 🐀

🧭 Roadmap / Future Plans

 Add color-coded console output (red/yellow/green)

 JSON export of discovered APs

 Optional web dashboard (lightweight)

 Windows service / Linux daemon mode

 Logging to file with rotation

🤝 Contributing

Fork the repo

Create a feature branch (feature/xyz)

Open a pull request with a clear description and use cases

Keep changes small and focused; include tests or manual validation steps


