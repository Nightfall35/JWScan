/** ===============================R.A.T (NOT A REMOTE ACCESS TROJAN : JUST THOUGHT OF AN ACTUAL RAT THAT WAS CRAWLING
 * THROUGH MY CEILING HENCE THE NAME... NOTHING SPECIAL TO IT)
 *
 *                                         AUTHOR      -> ISHMAEL D.TEMBO
 *                                         CREATED     -> JANUARY 3RD -> NOVEMBER 24
 *                                         ALIAS       -> NIGHTFALL35
 *                                         GITHUB      -> Nightfall35
 *                                         EMAIL       -> ishamelgoku@gmail.com
 *
 *                                         DISCLAIMER: I AM NOT A NETWORK ENGINEER. JUST A JAVA-OBSESSED FOOL.
 *
 * BLACK ICE v2 — Pure-Java 802.11 surveillance lattice.
 * Born in Lusaka, Zambia — 2000.
 *
 * Legal note: For authorized security research, education, and testing on networks you own
 * or have explicit written permission to analyze. Active transmission features are disabled
 * by default and must only be used where legally permitted.
 */

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.pcap4j.core.PcapNetworkInterface;

public class Rat {

    // ── Core state ──────────────────────────────────────────────────────────────
    private final Map<String, AP> seenById        = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService background       = Executors.newSingleThreadExecutor();
    private final ExecutorService geoExecutor      = Executors.newFixedThreadPool(4);
    private final Map<String, Long> geoLastAttempt = new ConcurrentHashMap<>();
    private final int httpPort;
    private final DateTimeFormatter dtf            = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PassiveScanner passiveScanner;
    private HttpServer     httpServer;
    private Deauther       deauther    = null;

    private final Map<String, String>          ouiMap         = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<HttpExchange> sseClients = new CopyOnWriteArrayList<>();

    private final SwarmAi         ai;
    private final String          myMac        = "00-15-5D-BF-D7-5A";
    private       boolean         counterMode  = true;
    private final OuiDatabase     ouiDatabase;
    private final WigleGeolocator geolocator;
    private volatile double operatorLat = -15.3875;
    private volatile double operatorLon =  28.3228;
    private volatile boolean gpsActive   = false;
    private GPSReader gpsReader = null;

    // ── Constructor ─────────────────────────────────────────────────────────────
    public Rat(int port) {
        this.httpPort    = port;
        this.ouiDatabase = new OuiDatabase(this);
        this.geolocator  = new WigleGeolocator(this);

        loadBuiltInOuIs();
        background.submit(this::downloadAndCacheIeeeOui);
        ai = new SwarmAi(this);

        // GPS reader — reads port from gps_port.txt or defaults to COM3/ttyUSB0
        // GPSReader opens the port asynchronously; hasFix() returns false until a valid NMEA fix arrives.
        String gpsPortToTry = "COM3"; // Windows default
        try {
            java.nio.file.Path gpsConf = java.nio.file.Paths.get("gps_port.txt");
            if (java.nio.file.Files.exists(gpsConf)) {
                String line = java.nio.file.Files.readAllLines(gpsConf).stream()
                    .filter(l -> !l.trim().isEmpty() && !l.startsWith("#"))
                    .findFirst().orElse("COM3").trim();
                gpsPortToTry = line;
            }
        } catch (Exception ignored) {}
        try {
            gpsReader = new GPSReader(gpsPortToTry);
            println("[GPS] Reader started on " + gpsPortToTry + " (hasFix=false until NMEA lock)");
        } catch (Exception e) {
            println("[GPS] Could not start GPS reader on " + gpsPortToTry + ": " + e.getMessage());
        }

        // Auto counter-deauth when someone attacks us
        onDeauthAttack = (src, dst, count) -> {
            if (dst.equalsIgnoreCase(myMac) || dst.equalsIgnoreCase("FF:FF:FF:FF:FF:FF")) {
                printlnStrongAlert("ATTACK DETECTED → " + src + " deauthing YOU → COUNTER-ATTACK ENGAGED");
                if (deauther != null && counterMode) {
                    deauther.deauth(src, src, 0); // infinite revenge
                }
            }
        };
    }

    // ── Startup ─────────────────────────────────────────────────────────────────
    public void start() {
        println("==================================================");
        println(" BLACK ICE v2 — FULL PASSIVE + ACTIVE MODE");
        println("==================================================");

        try {
            passiveScanner = new PassiveScanner(this);
            passiveScanner.start();
            println("PASSIVE SCANNER ONLINE");
        } catch (Exception e) {
            printlnStrongAlert("SCANNER FAILED TO START: " + e.getMessage());
            printlnStrongAlert("CAUSE: " + (e.getCause() != null ? e.getCause().getMessage() : "unknown"));
            printlnStrongAlert("CHECK: 1) Npcap installed  2) Running as Admin  3) Monitor-mode adapter present");
            printlnStrongAlert("DASHBOARD WILL STILL START — scanner offline");
            passiveScanner = null;
        }

        startHttpServer();

        scheduler.scheduleAtFixedRate(this::broadcastFullUpdate, 1, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        println("DASHBOARD → http://localhost:" + httpPort);
        println("==================================================");
    }

    // ── SSE broadcast ───────────────────────────────────────────────────────────
    private void broadcastFullUpdate() {
        // Update operator position from GPS if available
        if (gpsReader != null && gpsReader.hasFix()) {
            operatorLat = gpsReader.getLat();
            operatorLon = gpsReader.getLon();
            if (!gpsActive) {
                gpsActive = true;
                printlnAlert("[GPS] REAL FIX ACQUIRED → " + operatorLat + ", " + operatorLon);
            }
        }
        if (sseClients.isEmpty()) return;
        String json = buildFullJson();
        String msg  = "data: " + json + "\n\n";

        println("Broadcasting to " + sseClients.size() + " clients: "
                + json.substring(0, Math.min(100, json.length())) + "...");

        Iterator<HttpExchange> it = sseClients.iterator();
        while (it.hasNext()) {
            HttpExchange ex = it.next();
            try {
                OutputStream os = ex.getResponseBody();
                os.write(msg.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                sseClients.remove(ex);
                try { ex.close(); } catch (Exception ignored) {}
                println("Removed dead SSE client");
            } catch (Exception e) {
                println("Unexpected SSE write error: " + e);
                sseClients.remove(ex);
                try { ex.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ── JSON builder ────────────────────────────────────────────────────────────
    public String buildFullJson() {
        StringBuilder sb  = new StringBuilder("{\"type\":\"full\",\"operator\":{\"lat\":" + operatorLat + ",\"lon\":" + operatorLon + ",\"gps\":" + gpsActive + "},\"aps\":{");
        boolean       first = true;
        long          now   = System.currentTimeMillis();

        for (Map.Entry<String, AP> e : seenById.entrySet()) {
            AP ap = e.getValue();
            if (now - ap.lastSeen > 3_000_000) continue;
            if (!first) sb.append(",");
            first = false;

            String vendor = ouiDatabase.lookup(ap.bssid);
            if (vendor == null) vendor = getVendorFromBssid(ap.bssid);
            if (vendor == null) vendor = "unknown";

            // positionRandom = false → needs geolocation; true → real fix already acquired
            if (!ap.positionRandom) {
                long now2 = System.currentTimeMillis();
                Long lastAttempt = geoLastAttempt.get(ap.bssid);
                // Retry at most once per 30 seconds per AP to avoid Wigle rate-limit hammering
                if (lastAttempt == null || now2 - lastAttempt > 30_000) {
                    geoLastAttempt.put(ap.bssid, now2);
                    final AP apRef = ap;
                    geoExecutor.submit(() -> {
                        WigleGeolocator.GeoResult geo = geolocator.geolocate(apRef.bssid, apRef.ssid);
                        if (geo.success && !(geo.lat == -0.0 && geo.lon == 0.0)) {
                            apRef.lat            = geo.lat;
                            apRef.lon            = geo.lon;
                            apRef.source         = geo.source;
                            apRef.positionRandom = true;
                            println("[GEO] WIGLE FIX → " + apRef.ssid + " @ " + geo.lat + "," + geo.lon);
                        } else {
                            // Temporary placeholder — stays false so next cycle retries
                            WigleGeolocator.GeoResult approx = geolocator.getApproximateLocation();
                            if (approx.success) {
                                double offset = 0.03 * (Math.random() - 0.5);
                                apRef.lat    = approx.lat + offset;
                                apRef.lon    = approx.lon + offset;
                                apRef.source = "Approx+IP";
                            } else {
                                apRef.lat    = operatorLat + (Math.random() - 0.5) * 0.01;
                                apRef.lon    = operatorLon + (Math.random() - 0.5) * 0.01;
                                apRef.source = "Jitter";
                            }
                        }
                    });
                }
            }

            sb.append("\"").append(e.getKey()).append("\":{")
              .append("\"ssid\":\"").append(jsonEscape(ap.ssid)).append("\",")
              .append("\"bssid\":\"").append(jsonEscape(ap.bssid)).append("\",")
              .append("\"security\":\"").append(jsonEscape(ap.security)).append("\",")
              .append("\"signal\":").append(ap.signal).append(",")
              .append("\"channel\":").append(ap.channel).append(",")
              .append("\"vendor\":\"").append(jsonEscape(vendor)).append("\",")
              .append("\"lat\":").append(ap.lat).append(",")
              .append("\"lon\":").append(ap.lon).append(",")
              .append("\"source\":\"").append(jsonEscape(ap.source)).append("\"")
              .append("}");
        }
        sb.append("}}");
        return sb.toString();
    }

    // ── AP event handlers ───────────────────────────────────────────────────────
    public void onAccessPointDiscovered(AP ap) {
        String id       = !ap.bssid.isEmpty() ? ap.bssid : ap.ssid;
        AP     existing = seenById.get(id);

        ap.positionRandom = false; // let buildFullJson attempt Wigle geolocation first
        ap.lastSeen       = System.currentTimeMillis();

        if (existing == null) {
            seenById.put(id, ap);
            printlnAlert("NEW AP → " + summarize(ap));
            println("Total APs in memory: " + seenById.size());
            if (isOpen(ap)) {
                printlnStrongAlert("OPEN NETWORK → " + ap.ssid);
                soundBell();
            }
        } else {
            existing.ssid     = ap.ssid.isEmpty() ? existing.ssid : ap.ssid;
            existing.security = ap.security;
            existing.channel  = ap.channel;
            existing.signal   = Math.max(existing.signal, ap.signal);
            // lat/lon managed exclusively by buildFullJson via geoExecutor — never overwrite here
            existing.lastSeen = ap.lastSeen;
        }
        ai.seeAP(ap.bssid, ap.ssid, ap.channel, ap.security);
    }

    public void onClientProbe(String mac, String ssid) {
        println("CLIENT PROBE → " + mac + " → \"" + (ssid.isEmpty() ? "<any>" : ssid) + "\"");
    }

    public void onDeauthAttack(String src, String dst, int count) {
        printlnStrongAlert("DEAUTH ATTACK → " + src + " → " + dst);
        soundBell();
        if (onDeauthAttack != null) onDeauthAttack.accept(src, dst, count);
    }

    @FunctionalInterface
    interface DeauthCallback { void accept(String src, String dst, int count); }
    private DeauthCallback onDeauthAttack;

    // ── Evil twin callback (called by SwarmAi) ──────────────────────────────────
    public void evilTwinDetected(String ssid, String realBssid, String fakeBssid, int channel) {
        printlnStrongAlert("EVIL TWIN / ROGUE AP DETECTED");
        printlnStrongAlert("    SSID:        " + ssid);
        printlnStrongAlert("    LEGITIMATE → " + realBssid);
        printlnStrongAlert("    FAKE/ROGUE → " + fakeBssid + " (channel " + channel + ")");
        printlnStrongAlert("    AUTO-NUKE ENGAGED");
        soundBell();
        if (deauther != null) {
            new Thread(() -> deauther.deauth("FF:FF:FF:FF:FF:FF", fakeBssid, 0)).start();
        }
    }

    // ── HTTP server ─────────────────────────────────────────────────────────────
    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);

            // ── Dashboard ──
            httpServer.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                println("HTTP " + exchange.getRequestMethod() + " " + path);

                if (path.equals("/") || path.equals("/index.html")) {
                    if ("GET".equals(exchange.getRequestMethod())) {
                        println("Serving dashboard to " + exchange.getRemoteAddress());
                        byte[] html = DASHBOARD_HTML.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        exchange.sendResponseHeaders(200, html.length);
                        exchange.getResponseBody().write(html);
                        exchange.getResponseBody().close();
                    } else {
                        exchange.sendResponseHeaders(405, -1);
                    }
                } else {
                    println("404 → " + path);
                    exchange.sendResponseHeaders(404, -1);
                }
                exchange.close();
            });

            // ── SSE endpoint ──
            httpServer.createContext("/sse", exchange -> {
                println("New SSE connection from " + exchange.getRemoteAddress());
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }

                Headers h = exchange.getResponseHeaders();
                h.set("Content-Type",  "text/event-stream");
                h.set("Cache-Control", "no-cache");
                h.set("Connection",    "keep-alive");
                h.set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, 0);

                OutputStream os = exchange.getResponseBody();
                try {
                    os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    sseClients.add(exchange);
                    println("SSE client registered (" + sseClients.size() + " total)");

                    // Keep thread alive with heartbeats; actual data pushed by scheduler
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(15_000);
                            os.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException e) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    println("SSE error: " + e.getMessage());
                } finally {
                    sseClients.remove(exchange);
                    try { exchange.close(); } catch (Exception ignored) {}
                }
            });

            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            println("Web dashboard → http://localhost:" + httpPort);

        } catch (IOException e) {
            println("Failed to start HTTP server: " + e.getMessage());
        }
    }

    // ── Shutdown ────────────────────────────────────────────────────────────────
    private void shutdown() {
        println("\nShutting down BLACK ICE...");
        if (passiveScanner != null) passiveScanner.stop();
        scheduler.shutdownNow();
        background.shutdownNow();
        geoExecutor.shutdownNow();
        if (httpServer != null) httpServer.stop(1);
        println("Swarm offline.");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────
    private String summarize(AP ap) {
        String v = getVendorFromBssid(ap.bssid);
        return String.format("SSID='%s' BSSID=%s (%s) SEC='%s' SIG=%ddBm CH=%d",
                ap.ssid.isEmpty() ? "<hidden>" : ap.ssid, safe(ap.bssid),
                v != null ? v : "?", safe(ap.security), ap.signal, ap.channel);
    }

    private boolean isOpen(AP ap) {
        String s = ap.security == null ? "" : ap.security.toUpperCase();
        return s.contains("OPEN") || s.isEmpty();
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String safe(String s) { return s == null ? "" : s; }

    public void println(String s)           { System.out.println(timestamp() + " " + s); }
    public void printlnAlert(String s)      { System.out.println(timestamp() + " [ALERT]    " + s); }
    public void printlnStrongAlert(String s){ System.out.println(timestamp() + " [CRITICAL] " + s); }
    public String timestamp()               { return "[" + LocalDateTime.now().format(dtf) + "]"; }
    public void soundBell()                 { System.out.print("\007"); System.out.flush(); }

    // ── OUI / vendor lookup ─────────────────────────────────────────────────────
    private void loadBuiltInOuIs() {
        ouiMap.put("001122", "Cisco");
        ouiMap.put("44650D", "Cisco Meraki");
        ouiMap.put("A4C3F0", "Apple");
        ouiMap.put("7C9EBD", "TP-Link");
    }

    /** Placeholder kept to avoid compilation errors — expand for full IEEE OUI download. */
    private void downloadAndCacheIeeeOui() {}

    private String getVendorFromBssid(String bssid) {
        if (bssid == null) return null;
        String c = bssid.replace(":", "").toUpperCase();
        return c.length() >= 6 ? ouiMap.get(c.substring(0, 6)) : null;
    }

    // ── Deauther ────────────────────────────────────────────────────────────────
    public void enableDeauthOn(PcapNetworkInterface nif) {
        try {
            deauther = new Deauther(nif);
            println("[WEAPON] DEAUTH CANNON ARMED AND READY");
        } catch (Exception e) {
            println("[WEAPON] Monitor mode required for frame injection");
        }
    }

    public Deauther getDeauther() { return deauther; }

    // ── Main ────────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new Rat(port).start();
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
    }

    // ── Data classes ────────────────────────────────────────────────────────────
    public static class AP {
        public String  ssid           = "<hidden>";
        public String  bssid          = "";
        public String  security       = "OPEN";
        public int     signal         = -100;
        public int     channel        = 0;
        public double  lat            = -15.3875;
        public double  lon            =  28.3228;
        public boolean positionRandom = false;
        public long    lastSeen       = System.currentTimeMillis();
        public String  source         = "Unknown";
    }

    /** Retained for any code that still references WebSocketHandshake. Though there should be none left. just to be safe*/
    private static class WebSocketHandshake {
        static String accept(String key) {
            String magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(magic.getBytes(StandardCharsets.UTF_8));
                return java.util.Base64.getEncoder().encodeToString(digest);
            } catch (Exception e) { return ""; }
        }
    }

    // ── Dashboard HTML ──────────────────────────────────────────────────────────
    // BLACK ICE v2 — upgraded cyberpunk dashboard with matrix rain, GPS wiring,
    // typewriter boot, radar sweep, and async operator position tracking.
    private static final String DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<title>BLACK ICE v2 // RAT SWARM</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<link href="https://fonts.googleapis.com/css2?family=Space+Mono:ital,wght@0,400;0,700;1,400&family=Bebas+Neue&family=JetBrains+Mono:wght@300;400;700&display=swap" rel="stylesheet">
<style>
:root {
  --g:    #0aff6e;
  --g2:   #00cc55;
  --g3:   #004422;
  --r:    #ff3c00;
  --r2:   #cc2200;
  --c:    #00e5ff;
  --c2:   #0099bb;
  --y:    #ffe600;
  --bg:   #020d05;
  --bg2:  #000a03;
  --panel:#030f06ee;
  --b1:   #0aff6e22;
  --b2:   #0aff6e55;
  --b3:   #0aff6e99;
  --glow: 0 0 10px #0aff6e, 0 0 30px #0aff6e44;
  --glow-r: 0 0 10px #ff3c00, 0 0 30px #ff3c0044;
  --glow-c: 0 0 10px #00e5ff, 0 0 30px #00e5ff44;
}
*{margin:0;padding:0;box-sizing:border-box;}

body{
  background:var(--bg);
  color:var(--g);
  font-family:'JetBrains Mono',monospace;
  overflow:hidden;
  cursor:crosshair;
}

/* ── MATRIX RAIN CANVAS ── */
#matrix-canvas{
  position:fixed;inset:0;
  z-index:1;
  pointer-events:none;
  opacity:0.07;
}

/* ── HEX GRID OVERLAY ── */
#hex-overlay{
  position:fixed;inset:0;
  z-index:2;
  pointer-events:none;
  background-image:
    linear-gradient(60deg,var(--b1) 1px,transparent 1px),
    linear-gradient(-60deg,var(--b1) 1px,transparent 1px),
    linear-gradient(0deg,var(--b1) 1px,transparent 1px);
  background-size:40px 69px,40px 69px,40px 69px;
  animation:hexdrift 40s linear infinite;
}
@keyframes hexdrift{0%{background-position:0 0,0 0,0 0}100%{background-position:80px 0,80px 0,0 138px}}

/* ── SCANLINE ── */
#scanline{
  position:fixed;inset:0;z-index:3;pointer-events:none;
  background:repeating-linear-gradient(0deg,transparent,transparent 3px,rgba(0,0,0,0.1) 3px,rgba(0,0,0,0.1) 4px);
}

/* ── VIGNETTE ── */
#vignette{
  position:fixed;inset:0;z-index:4;pointer-events:none;
  background:radial-gradient(ellipse at center,transparent 50%,rgba(0,0,0,0.85) 100%);
}

/* ── MAP ── */
#map{
  position:fixed;inset:0;
  z-index:0;
  filter:hue-rotate(105deg) saturate(0.25) brightness(0.4) contrast(1.4);
}

/* ── RADAR SWEEP ── */
#radar-ring{
  position:fixed;
  z-index:5;
  pointer-events:none;
  border-radius:50%;
  border:1px solid var(--b2);
  box-shadow:inset 0 0 40px var(--b1);
}

/* ── HEADER ── */
.hdr{
  position:fixed;top:0;left:0;right:0;
  height:52px;
  z-index:1000;
  background:linear-gradient(180deg,rgba(2,13,5,0.99) 0%,rgba(2,13,5,0.88) 100%);
  border-bottom:1px solid var(--b2);
  box-shadow:var(--glow);
  display:flex;align-items:center;
  padding:0 16px;gap:16px;
}

.logo{
  font-family:'Bebas Neue',monospace;
  font-size:26px;
  letter-spacing:5px;
  color:var(--g);
  text-shadow:var(--glow);
  animation:logoglitch 6s infinite;
  flex-shrink:0;
}
@keyframes logoglitch{
  0%,88%,100%{clip-path:none;transform:none;filter:none}
  89%{clip-path:inset(30% 0 40% 0);transform:translateX(-3px);filter:hue-rotate(90deg)}
  91%{clip-path:inset(55% 0 15% 0);transform:translateX(3px)}
  93%{clip-path:inset(15% 0 60% 0);transform:translateX(-1px)}
  95%{clip-path:none;transform:none}
}

.hdr-sep{width:1px;height:28px;background:var(--b2);flex-shrink:0;}

.stat{display:flex;flex-direction:column;gap:0;}
.stat-lbl{font-size:8px;color:var(--g2);letter-spacing:3px;text-transform:uppercase;}
.stat-val{
  font-family:'Bebas Neue',monospace;font-size:26px;line-height:1;
  color:var(--g);text-shadow:var(--glow);
}
.stat-val.red{color:var(--r);text-shadow:var(--glow-r);}
.stat-val.cyan{color:var(--c);text-shadow:var(--glow-c);}
.stat-val.yellow{color:var(--y);text-shadow:0 0 10px var(--y);}

.threat-badge{
  font-family:'Bebas Neue',monospace;font-size:13px;letter-spacing:3px;
  padding:3px 10px;border:1px solid currentColor;
  transition:all 0.3s;flex-shrink:0;
}
.threat-badge.nom{color:var(--g);box-shadow:var(--glow);}
.threat-badge.elv{color:var(--y);box-shadow:0 0 8px var(--y);}
.threat-badge.crit{color:var(--r);box-shadow:var(--glow-r);animation:blinkbadge 0.5s infinite;}
@keyframes blinkbadge{0%,100%{opacity:1}50%{opacity:0.3}}

.gps-pill{
  display:flex;align-items:center;gap:5px;
  font-size:9px;letter-spacing:2px;padding:3px 8px;
  border:1px solid var(--b2);color:var(--g2);flex-shrink:0;
}
.gps-dot{
  width:6px;height:6px;border-radius:50%;background:var(--g2);
  box-shadow:0 0 4px var(--g2);
}
.gps-pill.live .gps-dot{background:var(--c);box-shadow:var(--glow-c);animation:gpspulse 1s infinite;}
.gps-pill.live{color:var(--c);border-color:var(--c2);}
@keyframes gpspulse{0%,100%{transform:scale(1);opacity:1}50%{transform:scale(1.6);opacity:0.4}}

#clock{
  font-family:'Bebas Neue',monospace;font-size:22px;
  color:var(--g);text-shadow:var(--glow);letter-spacing:3px;flex-shrink:0;
}

.hdr-right{margin-left:auto;display:flex;align-items:center;gap:10px;}

.pill{
  display:flex;align-items:center;gap:6px;padding:4px 10px;
  border:1px solid currentColor;font-size:9px;letter-spacing:2px;text-transform:uppercase;
}
.pill.conn{color:var(--g);box-shadow:var(--glow);}
.pill.disc{color:var(--r);box-shadow:var(--glow-r);}
.pill.wait{color:var(--y);box-shadow:0 0 6px var(--y);animation:blinkbadge 1s infinite;}
.pill-dot{width:7px;height:7px;border-radius:50%;background:currentColor;box-shadow:0 0 5px currentColor;animation:gpspulse 1.2s infinite;}

.btn{
  font-family:'JetBrains Mono',monospace;font-size:9px;letter-spacing:2px;text-transform:uppercase;
  background:transparent;color:var(--g);border:1px solid var(--b3);
  padding:5px 12px;cursor:crosshair;transition:all 0.15s;position:relative;overflow:hidden;
  flex-shrink:0;
}
.btn::before{
  content:'';position:absolute;inset:0;background:var(--g);
  transform:translateX(-101%);transition:transform 0.12s;z-index:-1;
}
.btn:hover{color:#000;box-shadow:var(--glow);}
.btn:hover::before{transform:translateX(0);}
.btn.on{background:var(--g);color:#000;}

/* ── SIDE PANEL ── */
#panel{
  position:fixed;top:52px;right:0;bottom:60px;
  width:300px;
  background:var(--panel);
  border-left:1px solid var(--b2);
  box-shadow:var(--glow);
  display:none;flex-direction:column;
  z-index:800;
  backdrop-filter:blur(4px);
}
#panel.open{display:flex;}

.panel-hdr{
  padding:10px 14px;border-bottom:1px solid var(--b2);
  font-family:'Bebas Neue',monospace;font-size:14px;letter-spacing:4px;
  color:var(--g);text-shadow:var(--glow);
  display:flex;align-items:center;justify-content:space-between;
  background:rgba(10,255,110,0.04);
}
.pcnt{
  background:var(--g);color:#000;
  font-family:'Bebas Neue',monospace;padding:1px 7px;font-size:16px;
}

#ap-list{flex:1;overflow-y:auto;padding:2px 0;}
#ap-list::-webkit-scrollbar{width:3px;}
#ap-list::-webkit-scrollbar-track{background:#000;}
#ap-list::-webkit-scrollbar-thumb{background:var(--g2);}

.ap-row{
  padding:8px 14px;border-bottom:1px solid rgba(10,255,110,0.06);
  cursor:crosshair;transition:background 0.1s;
  animation:rowfade 0.35s ease-out;
}
@keyframes rowfade{from{background:rgba(10,255,110,0.1);opacity:0}to{background:transparent;opacity:1}}
.ap-row:hover{background:rgba(10,255,110,0.05);}

.ap-name{
  font-size:12px;color:var(--g);text-shadow:0 0 6px #0aff6e55;
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:230px;
}
.ap-name.open{color:var(--r);text-shadow:var(--glow-r);}
.ap-info{display:flex;flex-wrap:wrap;gap:8px;margin-top:2px;font-size:9px;color:var(--g3);}
.ap-info span{color:var(--g2);}
.ap-info .mac{color:#005522;font-size:8px;}
.ap-info .tag-open{color:var(--r);animation:blinkbadge 0.9s infinite;font-weight:700;}

/* ── BOTTOM LOG ── */
#log{
  position:fixed;bottom:0;left:0;right:0;height:60px;
  background:rgba(2,10,3,0.96);border-top:1px solid var(--b2);
  z-index:800;padding:4px 14px;
  display:flex;flex-direction:column;justify-content:flex-end;gap:1px;
  transition:right 0.2s;
  font-size:10px;
}
.log-line{
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
  animation:logslide 0.25s ease-out;opacity:0.65;
}
.log-line:last-child{opacity:1;}
@keyframes logslide{from{transform:translateY(8px);opacity:0}to{transform:translateY(0);opacity:1}}
.lt{color:#003d11;margin-right:6px;}
.li{color:var(--g2);}
.lw{color:var(--y);}
.lc{color:var(--r);text-shadow:var(--glow-r);}
.ln{color:var(--c);text-shadow:var(--glow-c);}

/* ── CORNER TARGETS ── */
.tgt{position:fixed;width:24px;height:24px;z-index:900;pointer-events:none;}
.tgt.tl{top:56px;left:4px;border-top:2px solid var(--g);border-left:2px solid var(--g);}
.tgt.tr{top:56px;right:4px;border-top:2px solid var(--g);border-right:2px solid var(--g);}
.tgt.bl{bottom:64px;left:4px;border-bottom:2px solid var(--g);border-left:2px solid var(--g);}
.tgt.br{bottom:64px;right:4px;border-bottom:2px solid var(--g);border-right:2px solid var(--g);}

/* ── SIGNAL BARS ── */
.sb{display:inline-flex;gap:2px;align-items:flex-end;height:10px;vertical-align:middle;}
.sb span{display:block;width:3px;background:currentColor;opacity:0.2;border-radius:1px;}
.sb span.on{opacity:1;}
.sb span:nth-child(1){height:25%}
.sb span:nth-child(2){height:50%}
.sb span:nth-child(3){height:75%}
.sb span:nth-child(4){height:100%}

/* ── LEAFLET ── */
.leaflet-popup-content-wrapper{
  background:rgba(2,13,5,0.97)!important;border:1px solid var(--g)!important;
  border-radius:2px!important;box-shadow:var(--glow)!important;
  color:var(--g)!important;font-family:'JetBrains Mono',monospace!important;font-size:11px!important;
}
.leaflet-popup-tip{background:var(--g)!important;}
.leaflet-popup-close-button{color:var(--g)!important;font-size:16px!important;}
.leaflet-control-attribution{display:none!important;}
.leaflet-control-zoom a{
  background:rgba(2,13,5,0.95)!important;color:var(--g)!important;
  border-color:var(--b2)!important;font-family:'Bebas Neue',monospace!important;
}

/* ── TYPEWRITER BOOT ── */
#boot-overlay{
  position:fixed;inset:0;z-index:9000;
  background:var(--bg2);
  display:flex;flex-direction:column;justify-content:center;align-items:flex-start;
  padding:60px;
  transition:opacity 0.8s;
}
#boot-overlay.fade{opacity:0;pointer-events:none;}
.boot-line{
  font-family:'JetBrains Mono',monospace;font-size:13px;color:var(--g);
  margin:1px 0;white-space:pre;
}
.boot-cursor{
  display:inline-block;width:9px;height:14px;background:var(--g);
  animation:cursorblink 0.6s infinite;vertical-align:middle;
}
@keyframes cursorblink{0%,49%{opacity:1}50%,100%{opacity:0}}
</style>
</head>
<body>

<!-- Boot overlay -->
<div id="boot-overlay">
  <div id="boot-out"></div>
  <span class="boot-cursor"></span>
</div>

<canvas id="matrix-canvas"></canvas>
<div id="hex-overlay"></div>
<div id="scanline"></div>
<div id="vignette"></div>
<div id="map"></div>
<div id="radar-ring"></div>

<div class="tgt tl"></div><div class="tgt tr"></div>
<div class="tgt bl"></div><div class="tgt br"></div>

<div class="hdr">
  <div class="logo">BLACK ICE v2</div>
  <div class="hdr-sep"></div>
  <div class="stat"><div class="stat-lbl">TARGETS</div><div class="stat-val" id="cnt">000</div></div>
  <div class="hdr-sep"></div>
  <div class="stat"><div class="stat-lbl">OPEN</div><div class="stat-val red" id="ocnt">0</div></div>
  <div class="hdr-sep"></div>
  <div class="stat"><div class="stat-lbl">THREATS</div><div class="stat-val yellow" id="tcnt">0</div></div>
  <div class="hdr-sep"></div>
  <div id="threat-badge" class="threat-badge nom">NOMINAL</div>
  <div class="hdr-sep"></div>
  <div id="gps-pill" class="gps-pill"><div class="gps-dot"></div><span id="gps-txt">GPS: SEARCHING</span></div>
  <div class="hdr-sep"></div>
  <div id="clock">00:00:00</div>
  <div class="hdr-right">
    <div id="sse-status" class="pill wait"><div class="pill-dot"></div>CONNECTING</div>
    <div class="hdr-sep"></div>
    <button class="btn" id="panel-btn" onclick="togglePanel()">&#9776; TARGETS</button>
  </div>
</div>

<div id="panel">
  <div class="panel-hdr">
    <div style="display:flex;align-items:center;gap:8px;">
      <span>// ACCESS POINTS</span>
      <div class="pcnt" id="pcnt">0</div>
    </div>
    <button class="btn" style="padding:2px 7px;font-size:9px;" onclick="togglePanel()">&#x2715;</button>
  </div>
  <div id="ap-list"></div>
</div>

<div id="log">
  <div class="log-line"><span class="lt">[BOOT]</span><span class="li">BLACK ICE v2 INITIALIZING...</span></div>
  <div class="log-line"><span class="lt">[SYS] </span><span class="lw">AWAITING 802.11 FRAMES</span></div>
</div>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
// ═══════════════════════════════════════════════════════════════
// MATRIX RAIN
// ═══════════════════════════════════════════════════════════════
(function(){
  const c = document.getElementById('matrix-canvas');
  const ctx = c.getContext('2d');
  function resize(){ c.width=innerWidth; c.height=innerHeight; }
  resize(); window.addEventListener('resize', resize);
  const chars = 'アイウエオカキクケコサシスセソタチツテトナニヌネノABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  const cols = Math.floor(innerWidth/14);
  const drops = Array(cols).fill(1);
  function draw(){
    ctx.fillStyle='rgba(2,13,5,0.05)';
    ctx.fillRect(0,0,c.width,c.height);
    ctx.fillStyle='#0aff6e';
    ctx.font='13px JetBrains Mono,monospace';
    drops.forEach((y,i)=>{
      ctx.fillText(chars[Math.floor(Math.random()*chars.length)], i*14, y*14);
      if(y*14>c.height && Math.random()>0.975) drops[i]=0;
      drops[i]++;
    });
  }
  setInterval(draw,50);
})();

// ═══════════════════════════════════════════════════════════════
// RADAR RING (grows and fades from operator position)
// ═══════════════════════════════════════════════════════════════
let radarCenter = {x: innerWidth/2, y: innerHeight/2};
(function(){
  const ring = document.getElementById('radar-ring');
  let r = 0;
  function pulse(){
    r = 0;
    const anim = setInterval(()=>{
      r += 3;
      const size = r*2;
      ring.style.cssText = `
        position:fixed;z-index:5;pointer-events:none;border-radius:50%;
        border:1px solid rgba(10,255,110,${Math.max(0,0.5-r/300)});
        box-shadow:0 0 ${r/10}px rgba(10,255,110,${Math.max(0,0.2-r/500)});
        width:${size}px;height:${size}px;
        left:${radarCenter.x - r}px;top:${radarCenter.y - r}px;
      `;
      if(r > 300){ clearInterval(anim); ring.style.cssText='position:fixed;z-index:5;pointer-events:none;'; }
    }, 16);
  }
  pulse();
  setInterval(pulse, 4000);
})();

// ═══════════════════════════════════════════════════════════════
// CLOCK
// ═══════════════════════════════════════════════════════════════
function updateClock(){
  const n = new Date();
  document.getElementById('clock').textContent =
    String(n.getHours()).padStart(2,'0') + ':' +
    String(n.getMinutes()).padStart(2,'0') + ':' +
    String(n.getSeconds()).padStart(2,'0');
}
setInterval(updateClock,1000); updateClock();

// ═══════════════════════════════════════════════════════════════
// MAP
// ═══════════════════════════════════════════════════════════════
const map = L.map('map',{zoomControl:true,attributionControl:false}).setView([-15.3875,28.3228],15);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(map);

const markers={}, pulseRings={};
let allAps={}, panelOpen=false, reconnects=0;
let operatorMarker=null, operatorLatLng=null, gpsLocked=false;

// ═══════════════════════════════════════════════════════════════
// OPERATOR MARKER
// ═══════════════════════════════════════════════════════════════
function makeOperatorIcon(gps){
  const col = gps ? '#00e5ff' : '#0aff6e';
  const shadow = gps ? '0 0 14px #00e5ff,0 0 28px #00e5ff55' : '0 0 14px #0aff6e,0 0 28px #0aff6e55';
  return L.divIcon({
    html:`<div style="width:16px;height:16px;border:2px solid ${col};border-radius:50%;
          background:${col}33;box-shadow:${shadow};
          animation:gpspulse 1.2s infinite;position:relative;">
          <div style="position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);
          width:4px;height:4px;border-radius:50%;background:${col};box-shadow:${shadow};"></div>
         </div>`,
    iconSize:[16,16],iconAnchor:[8,8],className:''
  });
}

function updateOperatorMarker(lat,lon,gps){
  if(!operatorMarker){
    operatorMarker = L.marker([lat,lon],{icon:makeOperatorIcon(gps),zIndexOffset:9000}).addTo(map);
    operatorMarker.bindPopup(
      '<div style="font-family:JetBrains Mono,monospace;font-size:11px;">' +
      '<div style="color:#00e5ff;font-family:Bebas Neue,monospace;font-size:14px;letter-spacing:3px;margin-bottom:4px;">OPERATOR</div>' +
      '<div style="color:#005522;">SOURCE: ' + (gps?'HARDWARE GPS':'BROWSER') + '</div>' +
      '<div id="op-coords" style="color:#00aa33;font-size:10px;">' + lat.toFixed(6) + ', ' + lon.toFixed(6) + '</div>' +
      '</div>'
    );
  } else {
    operatorMarker.setLatLng([lat,lon]);
    operatorMarker.setIcon(makeOperatorIcon(gps));
  }
  // Update radar center
  const pt = map.latLngToContainerPoint([lat,lon]);
  radarCenter = {x:pt.x, y:pt.y};
}

// ═══════════════════════════════════════════════════════════════
// BROWSER GEOLOCATION fallback (used until GPS or SSE provides coords)
// ═══════════════════════════════════════════════════════════════
if(navigator.geolocation){
  navigator.geolocation.watchPosition(pos=>{
    if(gpsLocked) return; // hardware GPS takes priority
    const {latitude:lat,longitude:lon} = pos.coords;
    map.setView([lat,lon],16);
    updateOperatorMarker(lat,lon,false);
    addLog('GPS','BROWSER GEOLOCATION ACTIVE // ' + lat.toFixed(5) + ',' + lon.toFixed(5),'n');
  },()=>addLog('SYS','BROWSER GPS UNAVAILABLE','w'),{enableHighAccuracy:true,maximumAge:5000});
}

// ═══════════════════════════════════════════════════════════════
// LOG
// ═══════════════════════════════════════════════════════════════
const logEl = document.getElementById('log');
function addLog(tag,msg,type){
  type=type||'i';
  const ts=new Date();
  const t=String(ts.getHours()).padStart(2,'0')+':'+String(ts.getMinutes()).padStart(2,'0')+':'+String(ts.getSeconds()).padStart(2,'0');
  const d=document.createElement('div');
  d.className='log-line';
  const cls={'i':'li','w':'lw','c':'lc','n':'ln'}[type]||'li';
  d.innerHTML='<span class="lt">['+t+']['+tag+']</span><span class="'+cls+'">'+msg+'</span>';
  logEl.appendChild(d);
  while(logEl.children.length>5) logEl.removeChild(logEl.firstChild);
}

// ═══════════════════════════════════════════════════════════════
// SIGNAL BARS
// ═══════════════════════════════════════════════════════════════
function sigBars(dbm,col){
  const lvl=dbm>-55?4:dbm>-65?3:dbm>-75?2:1;
  let h='<span class="sb" style="color:'+col+'">';
  for(let i=1;i<=4;i++) h+='<span'+(i<=lvl?' class="on"':'')+' ></span>';
  return h+'</span>';
}

// ═══════════════════════════════════════════════════════════════
// PANEL
// ═══════════════════════════════════════════════════════════════
function togglePanel(){
  panelOpen=!panelOpen;
  document.getElementById('panel').classList.toggle('open',panelOpen);
  document.getElementById('panel-btn').classList.toggle('on',panelOpen);
  document.getElementById('log').style.right=panelOpen?'300px':'0';
  if(panelOpen) renderPanel();
}

function renderPanel(){
  if(!panelOpen) return;
  const list=document.getElementById('ap-list');
  const entries=Object.entries(allAps).sort((a,b)=>b[1].signal-a[1].signal);
  document.getElementById('pcnt').textContent=entries.length;
  list.innerHTML='';
  entries.forEach(([key,ap])=>{
    const isOpen=ap.security&&(ap.security.includes('OPEN')||ap.security==='');
    const sc=ap.signal>-65?'var(--g)':ap.signal>-80?'var(--y)':'var(--r)';
    const d=document.createElement('div');
    d.className='ap-row';
    d.innerHTML=
      '<div class="ap-name'+(isOpen?' open':'')+'">'+
      (isOpen?'&#9888; ':'')+(ap.ssid||'&lt;HIDDEN&gt;')+
      (isOpen?' <span class="tag-open">[OPEN]</span>':'')+
      '</div>'+
      '<div class="ap-info">'+
      '<span class="mac">'+ap.bssid+'</span>'+
      '<span>'+sigBars(ap.signal,sc)+' '+ap.signal+'dBm</span>'+
      '<span>CH:'+ap.channel+'</span>'+
      '<span>'+( ap.vendor||'?')+'</span>'+
      '<span style="color:#003d11;font-size:8px">'+(ap.source||'')+'</span>'+
      '</div>';
    d.onclick=()=>{
      if(markers[key]){ map.flyTo([ap.lat,ap.lon],17,{duration:0.7}); markers[key].openPopup(); }
    };
    list.appendChild(d);
  });
}

// ═══════════════════════════════════════════════════════════════
// THREAT LEVEL
// ═══════════════════════════════════════════════════════════════
function updateThreat(aps){
  const openCount=Object.values(aps).filter(a=>a.security&&(a.security.includes('OPEN')||a.security==='')).length;
  document.getElementById('ocnt').textContent=openCount;
  document.getElementById('tcnt').textContent=openCount;
  const b=document.getElementById('threat-badge');
  if(openCount===0){b.textContent='NOMINAL';b.className='threat-badge nom';}
  else if(openCount<3){b.textContent='ELEVATED';b.className='threat-badge elv';}
  else{b.textContent='! CRITICAL';b.className='threat-badge crit';}
}

// ═══════════════════════════════════════════════════════════════
// POPUP
// ═══════════════════════════════════════════════════════════════
function makePopup(ap){
  const isOpen=ap.security&&(ap.security.includes('OPEN')||ap.security==='');
  const col=isOpen?'#ff3c00':'#0aff6e';
  const sc=ap.signal>-65?'var(--g)':ap.signal>-80?'var(--y)':'var(--r)';
  return '<div style="min-width:210px;line-height:1.9;font-family:JetBrains Mono,monospace;">'+
    '<div style="font-family:Bebas Neue,monospace;font-size:16px;letter-spacing:3px;color:'+col+
    ';text-shadow:0 0 8px '+col+';margin-bottom:5px;border-bottom:1px solid '+col+'33;padding-bottom:3px;">'+
    (ap.ssid||'&lt;HIDDEN&gt;')+'</div>'+
    '<div style="color:#004d22;font-size:9px;margin-bottom:5px;">'+ap.bssid+'</div>'+
    '<table style="font-size:10px;width:100%;border-collapse:collapse;">'+
    '<tr><td style="color:#003d11;padding-right:12px;padding-bottom:2px;">SECURITY</td><td style="color:'+col+'">'+( ap.security||'OPEN')+'</td></tr>'+
    '<tr><td style="color:#003d11;padding-bottom:2px;">SIGNAL</td><td>'+ap.signal+'dBm '+sigBars(ap.signal,sc)+'</td></tr>'+
    '<tr><td style="color:#003d11;padding-bottom:2px;">CHANNEL</td><td>'+ap.channel+'</td></tr>'+
    '<tr><td style="color:#003d11;padding-bottom:2px;">VENDOR</td><td style="color:#00aaff">'+( ap.vendor||'UNKNOWN')+'</td></tr>'+
    '<tr><td style="color:#003d11;padding-bottom:2px;">SOURCE</td><td style="color:#004d11;font-size:9px">'+( ap.source||'?')+'</td></tr>'+
    '<tr><td style="color:#003d11;">COORDS</td><td style="font-size:9px">'+ap.lat.toFixed(5)+', '+ap.lon.toFixed(5)+'</td></tr>'+
    '</table></div>';
}

// ═══════════════════════════════════════════════════════════════
// DISPLAY UPDATE
// ═══════════════════════════════════════════════════════════════
function updateDisplay(aps){
  allAps=aps;
  document.getElementById('cnt').textContent=String(Object.keys(aps).length).padStart(3,'0');
  updateThreat(aps);
  if(panelOpen) renderPanel();

  Object.entries(aps).forEach(([key,ap])=>{
    const isOpen=ap.security&&(ap.security.includes('OPEN')||ap.security==='');
    const strong=ap.signal>-65;
    const col=isOpen?'#ff3c00':strong?'#0aff6e':'#ffe600';
    const r=isOpen?11:strong?8:6;

    if(markers[key]){
      markers[key].setLatLng([ap.lat,ap.lon]);
      markers[key].setStyle({color:col,fillColor:col,radius:r});
      markers[key].setPopupContent(makePopup(ap));
    } else {
      addLog('SIG',(ap.ssid||'<HIDDEN>')+' ['+ap.bssid+'] '+ap.signal+'dBm',isOpen?'c':'n');
      const m=L.circleMarker([ap.lat,ap.lon],{
        radius:r,color:col,fillColor:col,fillOpacity:0.8,weight:1.5
      }).addTo(map);
      m.bindPopup(makePopup(ap));
      markers[key]=m;

      if(isOpen){
        const ring=L.circleMarker([ap.lat,ap.lon],{
          radius:r+8,color:'#ff3c00',fillColor:'transparent',weight:1,opacity:0.5
        }).addTo(map);
        pulseRings[key]=ring;
        // CSS pulse on the SVG element
        setTimeout(()=>{
          const el=ring.getElement();
          if(el){el.style.animation='blinkbadge 1.4s ease-out infinite';}
        },50);
      }
    }
  });

  // Remove stale markers
  Object.keys(markers).forEach(key=>{
    if(!aps[key]){
      map.removeLayer(markers[key]); delete markers[key];
      if(pulseRings[key]){map.removeLayer(pulseRings[key]);delete pulseRings[key];}
    }
  });
}

// ═══════════════════════════════════════════════════════════════
// OPERATOR UPDATE FROM SSE
// ═══════════════════════════════════════════════════════════════
function updateOperator(op){
  if(!op) return;
  if(op.gps && !gpsLocked){
    gpsLocked=true;
    addLog('GPS','HARDWARE GPS LOCKED // '+op.lat.toFixed(6)+','+op.lon.toFixed(6),'n');
    const pill=document.getElementById('gps-pill');
    pill.className='gps-pill live';
    document.getElementById('gps-txt').textContent='GPS: LOCKED';
  }
  if(op.gps || !operatorMarker){
    updateOperatorMarker(op.lat,op.lon,op.gps);
    if(op.gps) map.panTo([op.lat,op.lon],{animate:true,duration:0.5});
  }
}

// ═══════════════════════════════════════════════════════════════
// SSE
// ═══════════════════════════════════════════════════════════════
let evt=null;
const sseEl=document.getElementById('sse-status');
function setConn(s){
  const labels={conn:'LIVE',disc:'OFFLINE',wait:'CONNECTING'};
  sseEl.className='pill '+s;
  sseEl.innerHTML='<div class="pill-dot"></div>'+(labels[s]||s);
}

function connect(){
  if(evt) evt.close();
  setConn('wait');
  evt=new EventSource('/sse');
  evt.onopen=()=>{ setConn('conn'); reconnects=0; addLog('SSE','STREAM ESTABLISHED','i'); };
  evt.onmessage=e=>{
    try{
      const d=JSON.parse(e.data);
      if(d.type==='full'){
        if(d.operator) updateOperator(d.operator);
        updateDisplay(d.aps);
      }
    }catch(err){ addLog('ERR','PARSE: '+err.message,'c'); }
  };
  evt.onerror=()=>{
    setConn('disc'); evt.close();
    if(reconnects<10){
      reconnects++;
      addLog('SSE','RECONNECT '+reconnects+'/10...','w');
      setTimeout(connect, 2000+reconnects*500);
    } else addLog('SSE','MAX RETRIES — STREAM DEAD','c');
  };
}
connect();
setInterval(()=>{ if(evt&&evt.readyState===EventSource.CLOSED) connect(); },30000);

// ═══════════════════════════════════════════════════════════════
// BOOT TYPEWRITER
// ═══════════════════════════════════════════════════════════════
const bootLines=[
  '> INITIALIZING BLACK ICE v2...',
  '> LOADING OUI DATABASE...',
  '> BINDING NPCAP HANDLES...',
  '> STARTING PASSIVE 802.11 CAPTURE...',
  '> WIGLE GEOLOCATION: ACTIVE',
  '> GPS READER: SEARCHING...',
  '> HTTP SERVER: ONLINE',
  '> SSE STREAM: CONNECTED',
  '> SWARM INTELLIGENCE: ARMED',
  '> EVIL TWIN DETECTION: ENABLED',
  '',
  '  ALL SYSTEMS NOMINAL. ENTERING SURVEILLANCE MODE.',
  '',
];

(async()=>{
  const out=document.getElementById('boot-out');
  for(const line of bootLines){
    const div=document.createElement('div');
    div.className='boot-line';
    out.appendChild(div);
    for(const ch of line){
      div.textContent+=ch;
      await new Promise(r=>setTimeout(r,18));
    }
    await new Promise(r=>setTimeout(r,60));
  }
  await new Promise(r=>setTimeout(r,500));
  document.getElementById('boot-overlay').classList.add('fade');
  setTimeout(()=>{document.getElementById('boot-overlay').style.display='none';},800);
})();
</script>
</body></html>

""";
}