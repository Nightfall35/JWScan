# BLACK ICE v2 — Complete Testing Guide

## PART 1 — Automated Tests (No hardware needed)

### Compile and run
```powershell
cd "C:\Users\ishma\Desktop\Architect\Jscanner"

javac -encoding UTF-8 -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;json-20210307.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar;slf4j-api-1.7.36.jar;slf4j-simple-1.7.36.jar" *.java

java -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;json-20210307.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar;slf4j-api-1.7.36.jar;slf4j-simple-1.7.36.jar;." TestRunner
```

### What gets tested automatically
- OuiDatabase: vendor lookup, cache, fallback
- WigleGeolocator: IP geolocation, cache, null safety
- SwarmAi: evil twin detection, hidden SSID skip, dedup
- GPSReader: NMEA parsing, coordinate conversion, malformed input
- Rat JSON: structure, escaping, operator field
- HTTP: dashboard 200, SSE 200, 404

---

## PART 2 — Dashboard Visual Tests (Browser only)

Start the app, open http://localhost:8080, check each:

| # | Test | How to verify | Pass condition |
|---|------|---------------|----------------|
| 1 | Boot typewriter | Watch on load | Characters type out line by line, overlay fades |
| 2 | Matrix rain | Background | Green Japanese/Latin chars falling |
| 3 | Hex grid | Background | Faint hexagonal grid drifting slowly |
| 4 | Radar ring | Map center | Green ring expands from center every 4 seconds |
| 5 | Clock | Header right | HH:MM:SS ticking every second |
| 6 | Logo glitch | Header left | BLACK ICE v2 briefly glitches every 6 seconds |
| 7 | Status pill | Header right | Shows CONNECTING → LIVE after SSE connects |
| 8 | SSE reconnect | Kill & restart app | Pill goes OFFLINE → CONNECTING → LIVE |
| 9 | Browser GPS | Allow location | Cyan operator marker appears on your position |

---

## PART 3 — Live Scanning Tests (Needs Npcap + Wi-Fi adapter)

Run as Administrator:
```powershell
java -cp "...all jars...;." Rat 8080
```

### 3.1 Basic beacon capture
**Expected in console:**
```
[ALERT] NEW AP → SSID='YourHomeWifi' BSSID=XX:XX:XX:XX:XX:XX ...
Total APs in memory: 1
```
**Expected on dashboard:**
- AP COUNT increments
- Green dot appears on map
- Side panel shows the AP

### 3.2 Open network detection
**Setup:** Create a mobile hotspot with NO password (open)
**Expected:**
```
[CRITICAL] OPEN NETWORK → YourHotspot
```
**Dashboard:** Red dot, threat badge changes to ELEVATED or CRITICAL

### 3.3 Signal strength
**Expected:** dBm values in console (-40 to -90 range)
Walk toward/away from router — signal value should change

### 3.4 Vendor lookup
**Expected:** Console shows vendor name for known routers
```
SSID='HomeNet' BSSID=7C:9E:BD:XX:XX:XX (TP-Link) SEC='WPA2'
```

### 3.5 Probe request capture
**Setup:** Turn Wi-Fi off and on on your phone
**Expected:**
```
CLIENT PROBE → XX:XX:XX:XX:XX:XX → "<any>"
```

### 3.6 Wigle geolocation
**Expected:** After ~30 seconds, AP source changes from "Jitter" to "wigle.net"
Check console for:
```
[GEO] WIGLE FIX → YourSSID @ -15.XXXXX,28.XXXXX
```
**Dashboard:** AP marker moves from random position to real-world location

### 3.7 Map interaction
- Click "TARGETS" button → side panel opens
- Click any AP row → map flies to that AP
- Click AP marker → popup shows SSID, BSSID, signal, vendor, coords

---

## PART 4 — SwarmAi Evil Twin Test

**Setup (use two devices):**
1. Create hotspot named `TestEvil` on Device A
2. Run BLACK ICE — it registers Device A's BSSID as legitimate
3. Create hotspot named `TestEvil` on Device B (same SSID, different BSSID)

**Expected after Device B is seen 2+ times:**
```
[CRITICAL] EVIL TWIN / ROGUE AP DETECTED
[CRITICAL]     SSID:        TestEvil
[CRITICAL]     LEGITIMATE → AA:BB:CC:XX:XX:XX
[CRITICAL]     FAKE/ROGUE → BB:CC:DD:XX:XX:XX (channel X)
```

---

## PART 5 — GPS Test (Only if you have a GPS dongle)

1. Create `gps_port.txt` in your project folder with your COM port:
   ```
   COM4
   ```
2. Plug in GPS dongle
3. Start BLACK ICE

**Expected:**
```
[GPS] Reader started on COM4 (hasFix=false until NMEA lock)
```
After getting a satellite fix (go outside or near a window):
```
[GPS] FIX -> -15.387500, 28.322800
```
**Dashboard:** GPS pill changes from grey "GPS: SEARCHING" to cyan "GPS: LOCKED"
Operator marker turns cyan and tracks your real position

---

## PART 6 — Stress Test

1. Drive or walk around your neighborhood for 15+ minutes
2. Check:
   - Console doesn't crash or freeze
   - Dashboard AP count keeps incrementing
   - No "Removed dead SSE client" spam (means dashboard is stable)
   - Memory usage stays stable (Task Manager → java.exe process)

**Red flags to watch:**
- `OutOfMemoryError` → too many APs in memory (add eviction logic later)
- `Wigle API rate limit exceeded (429)` → normal if scanning fast, backs off 60s
- `SSE write error` → browser tab was closed, normal

---

## Quick reference — what each console line means

| Console output | Meaning |
|----------------|---------|
| `[ALERT] NEW AP →` | New access point discovered |
| `[ALERT] LEGIT AP CONFIRMED →` | SwarmAi locked in a legitimate BSSID |
| `[CRITICAL] OPEN NETWORK →` | Unsecured AP found |
| `[CRITICAL] EVIL TWIN →` | Rogue AP detected |
| `[CRITICAL] ATTACK DETECTED →` | Deauth attack aimed at your MAC |
| `[GEO] WIGLE FIX →` | Real coordinates received from Wigle |
| `Broadcasting to N clients` | SSE working, N browser tabs connected |
| `Removed dead SSE client` | Browser tab closed — normal |
| `Wigle API rate limit (429)` | Too many requests — backing off 60s |
| `[GPS] FIX →` | Hardware GPS locked |
