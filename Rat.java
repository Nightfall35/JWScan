/** ===============================R.A.T (NOT A REMOTE ACCESS TROJAN : JUST THOUGHT OF AN ACTUAL RAT THAT WAS CRAWLING
 * THROUGH MY CEILING HENCE THE NAME... NOTHING SPECIAL TO IT)
 *
 *                                         AUTHOR      -> ISHMAEL D.TEMBO
 *                                         CREATED     -> JANUARY 3RD -> NOVEMBER 27
 *                                         ALIAS       -> NIGHTFALL35
 *                                         GITHUB      -> Nightfall35
 *                                         EMAIL       -> ishamelgoku@gmail.com
 *
 *                                         DISCLAIMER: I AM NOT A NETWORK ENGINEER. JUST A JAVA-OBSESSED FOOL.
 *
 * BLACK ICE v2 — Pure-Java 802.11 surveillance lattice.
 * Born in Lusaka, Zambia — 2025.
 *
 * Legal note: For authorized security research, education, and testing on networks you own
 * or have explicit written permission to analyze. Active transmission features are disabled
 * by default and must only be used where legally permitted.
 */

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
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

    // ── Survey / wardriving state ────────────────────────────────────────────────
    private volatile boolean         surveyActive     = false;
    private final    AtomicLong      surveyReadings   = new AtomicLong(0);
    private final    Path            surveyFile       = Paths.get("survey_log.csv");
    private          PrintWriter     surveyWriter     = null;
    private final    Object          surveyLock       = new Object();

    // ── Constructor ─────────────────────────────────────────────────────────────
    public Rat(int port) {
        this.httpPort    = port;
        this.ouiDatabase = new OuiDatabase(this);
        this.geolocator  = new WigleGeolocator(this);

        loadBuiltInOuIs();
        background.submit(this::downloadAndCacheIeeeOui);
        ai = new SwarmAi(this);

        String gpsPortToTry = "COM3";
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

        onDeauthAttack = (src, dst, count) -> {
            if (dst.equalsIgnoreCase(myMac) || dst.equalsIgnoreCase("FF:FF:FF:FF:FF:FF")) {
                printlnStrongAlert("ATTACK DETECTED → " + src + " deauthing YOU → COUNTER-ATTACK ENGAGED");
                if (deauther != null && counterMode) {
                    deauther.deauth(src, src, 0);
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
        if (gpsReader != null && gpsReader.hasFix()) {
            operatorLat = gpsReader.getLat();
            operatorLon = gpsReader.getLon();
            if (!gpsActive) {
                gpsActive = true;
                printlnAlert("[GPS] REAL FIX ACQUIRED → " + operatorLat + ", " + operatorLon);
            }
        }
        if (sseClients.isEmpty()) return;
        writeSurveyReadings();
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
        StringBuilder sb  = new StringBuilder("{\"type\":\"full\",\"operator\":{\"lat\":" + operatorLat + ",\"lon\":" + operatorLon + ",\"gps\":" + gpsActive + "},\"survey\":{\"active\":" + surveyActive + ",\"readings\":" + surveyReadings.get() + "},\"aps\":{");
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

            if (!ap.positionRandom) {
                long now2 = System.currentTimeMillis();
                Long lastAttempt = geoLastAttempt.get(ap.bssid);
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

        ap.positionRandom = false;
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

    // ── Evil twin callback ───────────────────────────────────────────────────────
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

            // ── Survey control ──
            httpServer.createContext("/survey", exchange -> {
                String method = exchange.getRequestMethod();
                String path   = exchange.getRequestURI().getPath();
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

                if (path.equals("/survey/export") && "GET".equals(method)) {
                    // Download the CSV file
                    if (!Files.exists(surveyFile)) {
                        byte[] msg = "No survey data yet.".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(404, msg.length);
                        exchange.getResponseBody().write(msg);
                    } else {
                        byte[] csv = Files.readAllBytes(surveyFile);
                        exchange.getResponseHeaders().set("Content-Type", "text/csv");
                        exchange.getResponseHeaders().set("Content-Disposition",
                            "attachment; filename=\"survey_" + LocalDate.now() + ".csv\"");
                        exchange.sendResponseHeaders(200, csv.length);
                        exchange.getResponseBody().write(csv);
                    }
                    exchange.getResponseBody().close();
                    exchange.close();
                    return;
                }

                if ("POST".equals(method)) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                    String resp;
                    if ("start".equals(body)) {
                        startSurvey();
                        resp = "{\"status\":\"started\",\"readings\":" + surveyReadings.get() + "}";
                    } else if ("stop".equals(body)) {
                        stopSurvey();
                        resp = "{\"status\":\"stopped\",\"readings\":" + surveyReadings.get() + "}";
                    } else if ("clear".equals(body)) {
                        stopSurvey();
                        surveyReadings.set(0);
                        try { Files.deleteIfExists(surveyFile); } catch (IOException ignored) {}
                        resp = "{\"status\":\"cleared\"}";
                    } else {
                        resp = "{\"error\":\"unknown command\"}";
                    }
                    byte[] rb = resp.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, rb.length);
                    exchange.getResponseBody().write(rb);
                    exchange.getResponseBody().close();
                    exchange.close();
                    return;
                }

                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            });

            httpServer.createContext("/survey/export", exchange -> {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1); exchange.close(); return;
                }
                if (!Files.exists(surveyFile)) {
                    byte[] msg = "No survey data yet.".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, msg.length);
                    exchange.getResponseBody().write(msg);
                } else {
                    byte[] csv = Files.readAllBytes(surveyFile);
                    exchange.getResponseHeaders().set("Content-Type", "text/csv");
                    exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"survey_" + LocalDate.now() + ".csv\"");
                    exchange.sendResponseHeaders(200, csv.length);
                    exchange.getResponseBody().write(csv);
                }
                exchange.getResponseBody().close();
                exchange.close();
            });

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
        stopSurvey();
        scheduler.shutdownNow();
        background.shutdownNow();
        geoExecutor.shutdownNow();
        if (httpServer != null) httpServer.stop(1);
        println("Swarm offline.");
    }

    // ── Survey / wardriving ──────────────────────────────────────────────────────
    public void startSurvey() {
        synchronized (surveyLock) {
            if (surveyActive) return;
            try {
                boolean newFile = !Files.exists(surveyFile);
                surveyWriter = new PrintWriter(new FileWriter(surveyFile.toFile(), true));
                if (newFile) surveyWriter.println("timestamp,bssid,ssid,rssi,lat,lon,channel,security");
                surveyActive = true;
                println("[SURVEY] Walk-survey STARTED → " + surveyFile.toAbsolutePath());
            } catch (IOException e) {
                printlnStrongAlert("[SURVEY] Failed to open log file: " + e.getMessage());
            }
        }
    }

    public void stopSurvey() {
        synchronized (surveyLock) {
            if (!surveyActive) return;
            surveyActive = false;
            if (surveyWriter != null) { surveyWriter.flush(); surveyWriter.close(); surveyWriter = null; }
            println("[SURVEY] Walk-survey STOPPED — " + surveyReadings.get() + " readings written");
        }
    }

    /** Called every SSE tick when survey is active and GPS has a fix. */
    private void writeSurveyReadings() {
        if (!surveyActive || surveyWriter == null) return;
        if (!gpsActive && (gpsReader == null || !gpsReader.hasFix())) return;
        double lat = operatorLat, lon = operatorLon;
        String ts  = LocalDateTime.now().format(dtf);
        synchronized (surveyLock) {
            for (AP ap : seenById.values()) {
                if (System.currentTimeMillis() - ap.lastSeen > 5_000) continue;
                surveyWriter.printf("%s,%s,%s,%d,%.8f,%.8f,%d,%s%n",
                    ts,
                    csvEscape(ap.bssid),
                    csvEscape(ap.ssid),
                    ap.signal,
                    lat, lon,
                    ap.channel,
                    csvEscape(ap.security));
                surveyReadings.incrementAndGet();
            }
            surveyWriter.flush();
        }
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
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
        String[][] entries = {
            {"001122","Cisco"},       {"44650D","Cisco Meraki"}, {"A4C3F0","Apple"},
            {"7C9EBD","TP-Link"},     {"001C10","Ubiquiti"},     {"B827EB","Raspberry Pi"},
            {"F4F5D8","Google"},      {"9CADEF","Huawei"},       {"B0487A","TP-Link"},
            {"D85DFB","Amazon"},      {"EC086B","TP-Link"},      {"F81A67","ARRIS"},
            {"8C8590","Apple"},       {"001D0F","Netgear"},      {"001E2A","Netgear"},
            {"0021B9","Intel"},       {"0022FA","D-Link"},       {"0050F2","Microsoft"},
            {"0090CC","Intel"},       {"00E075","Asus"},         {"08D40C","Apple"},
            {"0C8268","TP-Link"},     {"14CC20","TP-Link"},      {"18A6F7","TP-Link"},
            {"1C60DE","Asus"},        {"203706","Cisco"},        {"289EDF","Huawei"},
            {"2CBE08","Apple"},       {"34159E","Raspberry Pi"}, {"34E894","Intel"},
            {"3C5AB4","Google"},      {"40D32D","Apple"},        {"44D9E7","Ubiquiti"},
            {"4C32D9","Asus"},        {"5057A8","Cisco"},        {"5C8576","Asus"},
            {"60A44C","Asus"},        {"647002","TP-Link"},      {"68FF7B","TP-Link"},
            {"7038EE","Apple"},       {"746A89","TP-Link"},      {"7831C1","Apple"},
            {"7C0191","Apple"},       {"80D21D","AzureWave"},    {"841B5E","Netgear"},
            {"84A6C8","Intel"},       {"88DC96","Apple"},        {"8C85C1","Apple"},
            {"9094E4","D-Link"},      {"94DBDA","Huawei"},       {"9CA513","Samsung"},
            {"A46706","Apple"},       {"AC293A","Apple"},        {"B0754D","Apple"},
            {"B8E856","Apple"},       {"C05627","Belkin"},       {"C83A35","TP-Link"},
            {"CCB255","D-Link"},      {"D0C5D8","AzureWave"},    {"D481CA","Intel"},
            {"E0CB1D","Belkin"},      {"E4CE70","Huawei"},       {"EC9327","TP-Link"},
            {"F48C50","Intel"},       {"FC626E","Xiaomi"},       {"50C7BF","TP-Link"},
            {"000CF1","Samsung"},     {"0017F2","Apple"},        {"00176C","Samsung"},
            {"001788","Apple"},       {"ACBC32","Apple"},        {"D83462","TP-Link"},
            {"C4E984","TP-Link"},     {"A42BB0","TP-Link"},      {"307C20","TP-Link"},
            {"5001BB","Netgear"},     {"C04A00","Netgear"},      {"20E52A","Netgear"},
            {"000F86","Netgear"},     {"30B5C2","Netgear"},      {"9C3426","Netgear"},
            {"001B11","D-Link"},      {"00155F","D-Link"},       {"1C7EE5","D-Link"},
            {"84C9B2","D-Link"},      {"B8A386","D-Link"},       {"00265A","D-Link"},
            {"C8D3FF","Huawei"},      {"2C9D1E","Huawei"},       {"001E10","Huawei"},
            {"286ED4","Huawei"},      {"3C47C9","Huawei"},       {"70723C","Huawei"},
            {"000C29","VMware"},      {"005056","VMware"},       {"000569","VMware"},
            {"001C14","VMware"},      {"00505A","3Com"},         {"000ABF","3Com"},
            {"5CE0C5","Asus"},        {"10BF48","Asus"},         {"107B44","Asus"},
            {"2C56DC","Asus"},        {"50465D","Asus"},         {"BC9780","Asus"},
            {"001AA0","Ubiquiti"},    {"0418D6","Ubiquiti"},     {"246895","Ubiquiti"},
            {"44D9E7","Ubiquiti"},    {"68D79A","Ubiquiti"},     {"802AA8","Ubiquiti"},
            {"009963","Cisco"},       {"001BD4","Cisco"},        {"0026CB","Cisco"},
            {"04C5A4","Cisco"},       {"1C6A7A","Cisco"},        {"2CF8D8","Cisco"},
            {"38EDD1","Cisco"},       {"A073C4","Cisco"},        {"C84031","Cisco"},
            {"F44E73","Cisco"},       {"000414","Cisco"},        {"000562","Cisco"},
        };
        for (String[] e : entries) ouiMap.put(e[0], e[1]);
        println("[OUI] " + ouiMap.size() + " built-in entries loaded");
    }

    private void downloadAndCacheIeeeOui() {
        try {
            int waited = 0;
            while (ouiDatabase.getEntryCount() < 10_000 && waited < 60_000) {
                Thread.sleep(2_000);
                waited += 2_000;
                if (waited % 10_000 == 0)
                    println("[OUI] Waiting for IEEE download... (" + ouiDatabase.getEntryCount() + " entries so far)");
            }
            println("[OUI] Database ready: " + ouiDatabase.getEntryCount() + " entries");
        } catch (InterruptedException ignored) {}
    }

    private String getVendorFromBssid(String bssid) {
        if (bssid == null) return null;
        String v = ouiDatabase.lookup(bssid);
        if (v != null) return v;
        String c = bssid.replace(":", "").replace("-", "").toUpperCase();
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

    /** Retained for any code that still references WebSocketHandshake. */
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
    // BLACK ICE v2 — CartoDB Dark Matter tiles + ROOM MODE proximity radar
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
  filter:saturate(0.15) brightness(0.55) contrast(1.5);
  transition:opacity 0.5s;
}
#map.hidden{ opacity:0; pointer-events:none; }

/* ── ROOM MODE RADAR ── */
#room-radar{
  position:fixed;inset:0;
  z-index:0;
  background:radial-gradient(ellipse at center, #010d04 0%, #020d05 100%);
  display:none;
  align-items:center;
  justify-content:center;
}
#room-radar.active{ display:flex; }

#radar-canvas{
  position:absolute;
  top:50%; left:50%;
  transform:translate(-50%,-50%);
}

/* ── RADAR RING (map pulse) ── */
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

/* Room mode button active state */
.btn.room-active{
  background:var(--c);color:#000;border-color:var(--c);
  box-shadow:var(--glow-c);
  animation:none;
}
.btn.room-active::before{ background:var(--c); }

/* Survey mode button active state */
.btn.survey-active{
  background:var(--y);color:#000;border-color:var(--y);
  box-shadow:0 0 10px var(--y),0 0 30px #ffe60044;
  animation:surveypulse 1s infinite;
}
.btn.survey-active::before{ background:var(--y); }
@keyframes surveypulse{0%,100%{box-shadow:0 0 10px var(--y),0 0 30px #ffe60044}50%{box-shadow:0 0 18px var(--y),0 0 50px #ffe60088}}

/* Survey stat */
.stat-val.survey{ color:var(--y); text-shadow:0 0 10px var(--y),0 0 30px #ffe60044; }

/* ── SURVEY HEATMAP LEGEND ── */
#survey-legend{
  position:fixed;
  bottom:68px;right:12px;
  z-index:600;
  font-size:9px;
  letter-spacing:2px;
  display:none;
  flex-direction:column;
  gap:4px;
  background:rgba(2,13,5,0.92);
  border:1px solid rgba(255,230,0,0.4);
  padding:10px 14px;
  box-shadow:0 0 12px #ffe60033;
}
#survey-legend.active{ display:flex; }
.survey-legend-title{
  font-family:'Bebas Neue',monospace;font-size:12px;letter-spacing:3px;
  color:var(--y);text-shadow:0 0 8px var(--y);margin-bottom:4px;
}
.survey-ap-row{
  display:flex;align-items:center;gap:6px;cursor:crosshair;padding:2px 0;
  font-size:9px;letter-spacing:1px;
}
.survey-ap-row:hover{ color:var(--g); }
.survey-ap-dot{ width:8px;height:8px;border-radius:50%;flex-shrink:0; }
#survey-reading-cnt{
  font-family:'Bebas Neue',monospace;font-size:11px;color:#ffe600aa;
  margin-top:6px;border-top:1px solid #ffe60033;padding-top:6px;
  display:flex;justify-content:space-between;align-items:center;gap:12px;
}
.survey-dl-btn{
  font-size:8px;letter-spacing:2px;color:#ffe600;border:1px solid #ffe60055;
  padding:2px 7px;cursor:crosshair;background:transparent;
  font-family:'JetBrains Mono',monospace;
}
.survey-dl-btn:hover{background:#ffe60022;}
.survey-clear-btn{
  font-size:8px;letter-spacing:2px;color:#ff3c00;border:1px solid #ff3c0055;
  padding:2px 7px;cursor:crosshair;background:transparent;
  font-family:'JetBrains Mono',monospace;
}
.survey-clear-btn:hover{background:#ff3c0022;}

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

/* ── ROOM MODE TOOLTIP ── */
#radar-tooltip{
  position:fixed;
  background:rgba(2,13,5,0.95);
  border:1px solid var(--b2);
  padding:8px 12px;
  font-size:10px;
  pointer-events:none;
  z-index:600;
  display:none;
  min-width:200px;
  box-shadow:var(--glow);
}
#radar-tooltip .tt-ssid{
  font-family:'Bebas Neue',monospace;
  font-size:16px;
  letter-spacing:3px;
  color:var(--g);
  text-shadow:var(--glow);
}

/* ── ROOM MODE LEGEND ── */
#radar-legend{
  position:fixed;
  bottom:68px;left:12px;
  z-index:600;
  font-size:9px;
  letter-spacing:2px;
  color:var(--g3);
  display:none;
  flex-direction:column;
  gap:4px;
  background:rgba(2,13,5,0.85);
  border:1px solid var(--b2);
  padding:8px 12px;
}
#radar-legend.active{ display:flex; }
.leg-row{ display:flex;align-items:center;gap:8px; }
.leg-dot{ width:8px;height:8px;border-radius:50%;flex-shrink:0; }

/* ── LEAFLET ── */
.leaflet-popup-content-wrapper{
  background:transparent!important;border:none!important;
  border-radius:0!important;box-shadow:none!important;
  padding:0!important;
}
.leaflet-popup-content{
  margin:0!important;padding:0!important;
  font-family:'JetBrains Mono',monospace!important;
  font-size:11px!important;
  width:auto!important;
  min-width:280px!important;
}
.leaflet-popup-tip-container{display:none!important;}
.leaflet-popup-close-button{
  color:#0aff6e!important;font-size:18px!important;
  top:6px!important;right:8px!important;
  z-index:10;background:transparent!important;
  text-shadow:0 0 6px #0aff6e!important;
}
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

<!-- Room Mode Radar -->
<div id="room-radar">
  <canvas id="radar-canvas"></canvas>
</div>

<!-- Room Mode Tooltip -->
<div id="radar-tooltip">
  <div class="tt-ssid" id="tt-ssid"></div>
  <div id="tt-body" style="color:#00cc55;margin-top:4px;line-height:1.6;"></div>
</div>

<!-- Room Mode Legend -->
<div id="radar-legend">
  <div style="font-family:'Bebas Neue',monospace;font-size:11px;letter-spacing:3px;color:var(--g);margin-bottom:4px;">SIGNAL PROXIMITY</div>
  <div class="leg-row"><div class="leg-dot" style="background:#0aff6e;box-shadow:0 0 5px #0aff6e"></div><span style="color:#00cc55">STRONG  &gt; -55 dBm  (SAME ROOM)</span></div>
  <div class="leg-row"><div class="leg-dot" style="background:#ffe600;box-shadow:0 0 5px #ffe600"></div><span style="color:#00cc55">MEDIUM  -55 to -70 dBm  (NEARBY)</span></div>
  <div class="leg-row"><div class="leg-dot" style="background:#ff3c00;box-shadow:0 0 5px #ff3c00"></div><span style="color:#00cc55">WEAK   &lt; -70 dBm  (DISTANT)</span></div>
  <div class="leg-row" style="margin-top:4px;"><div class="leg-dot" style="background:transparent;border:1px solid #ff3c00;"></div><span style="color:#ff3c0099">OPEN NETWORK</span></div>
</div>

<!-- Survey heatmap legend / controls -->
<div id="survey-legend">
  <div class="survey-legend-title">&#9678; WALK SURVEY</div>
  <div id="survey-ap-filter" style="display:flex;flex-direction:column;gap:3px;max-height:160px;overflow-y:auto;"></div>
  <div id="survey-reading-cnt">
    <span id="survey-reading-label">0 READINGS</span>
    <div style="display:flex;gap:6px;">
      <button class="survey-dl-btn" onclick="surveyDownload()">&#8595; CSV</button>
      <button class="survey-clear-btn" onclick="surveyClear()">&#10005; CLEAR</button>
    </div>
  </div>
</div>

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
    <div class="stat"><div class="stat-lbl">READINGS</div><div class="stat-val survey" id="survey-cnt">0</div></div>
    <div class="hdr-sep"></div>
    <div id="sse-status" class="pill wait"><div class="pill-dot"></div>CONNECTING</div>
    <div class="hdr-sep"></div>
    <button class="btn" id="survey-btn" onclick="toggleSurvey()">&#9678; SURVEY</button>
    <button class="btn" id="room-btn" onclick="toggleRoomMode()">&#9673; ROOM MODE</button>
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
// RADAR RING (pulse from operator position on the map)
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
// MAP  —  CartoDB Dark Matter tiles
// ═══════════════════════════════════════════════════════════════
const map = L.map('map',{zoomControl:true,attributionControl:false}).setView([-15.3875,28.3228],15);
L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',{
  maxZoom:19,
  subdomains:'abcd'
}).addTo(map);

const markers={}, pulseRings={};
let allAps={}, panelOpen=false, reconnects=0;
let operatorMarker=null, operatorLatLng=null, gpsLocked=false;

// ═══════════════════════════════════════════════════════════════
// ROOM MODE STATE
// ═══════════════════════════════════════════════════════════════
let roomMode = false;
let radarAnimId = null;
let radarSweepAngle = 0;
// Wobble offsets per AP key so they don't stack perfectly
const radarWobble = {};

function toggleRoomMode(){
  roomMode = !roomMode;
  const btn = document.getElementById('room-btn');
  const mapEl = document.getElementById('map');
  const radarEl = document.getElementById('room-radar');
  const legend = document.getElementById('radar-legend');

  if(roomMode){
    btn.classList.add('room-active');
    mapEl.classList.add('hidden');
    radarEl.classList.add('active');
    legend.classList.add('active');
    addLog('MODE','ROOM MODE ENGAGED — SIGNAL PROXIMITY RADAR','n');
    startRoomRadar();
  } else {
    btn.classList.remove('room-active');
    mapEl.classList.remove('hidden');
    radarEl.classList.remove('active');
    legend.classList.remove('active');
    document.getElementById('radar-tooltip').style.display='none';
    if(radarAnimId){ cancelAnimationFrame(radarAnimId); radarAnimId=null; }
    addLog('MODE','MAP MODE RESTORED','i');
  }
}

// ═══════════════════════════════════════════════════════════════
// ROOM MODE RADAR — canvas-based proximity radar
// ═══════════════════════════════════════════════════════════════
function startRoomRadar(){
  const canvas = document.getElementById('radar-canvas');
  const ctx = canvas.getContext('2d');

  function resize(){
    const w = window.innerWidth;
    const h = window.innerHeight - 52 - 60; // subtract header + log
    canvas.width  = w;
    canvas.height = h;
    canvas.style.top  = '52px';
    canvas.style.left = '0';
    canvas.style.transform = 'none';
    canvas.style.position = 'fixed';
  }
  resize();
  window.addEventListener('resize', resize);

  const cx = () => canvas.width  / 2;
  const cy = () => canvas.height / 2;

  // Max radius — use 85% of the smaller half-dimension
  const maxR = () => Math.min(canvas.width, canvas.height) * 0.42;

  // Map signal dBm → radius on radar
  // -30 dBm (strongest practical) → inner ring (0.10 of maxR)
  // -90 dBm (noise floor) → outer ring (0.95 of maxR)
  function sigToRadius(dbm){
    const clamped = Math.max(-95, Math.min(-25, dbm));
    const t = (clamped - (-25)) / ((-95) - (-25)); // 0=strong/center … 1=weak/edge
    const r = maxR();
    return r * (0.12 + t * 0.82);
  }

  // Hit-test AP blobs for tooltip
  const blobPositions = {};

  function drawFrame(){
    if(!roomMode){ return; }
    radarAnimId = requestAnimationFrame(drawFrame);

    const W = canvas.width, H = canvas.height;
    const X = cx(), Y = cy(), R = maxR();

    ctx.clearRect(0,0,W,H);

    // ── Background gradient ──
    const bg = ctx.createRadialGradient(X,Y,0, X,Y,R*1.1);
    bg.addColorStop(0,   '#010f06');
    bg.addColorStop(0.7, '#020d05');
    bg.addColorStop(1,   '#000a03');
    ctx.fillStyle = bg;
    ctx.fillRect(0,0,W,H);

    // ── Concentric range rings ──
    const rings = [
      { t: 0.15, label:'-35 dBm', col:'rgba(10,255,110,0.5)' },
      { t: 0.35, label:'-50 dBm', col:'rgba(10,255,110,0.3)' },
      { t: 0.55, label:'-65 dBm', col:'rgba(255,230,0,0.25)' },
      { t: 0.75, label:'-75 dBm', col:'rgba(255,60,0,0.2)'   },
      { t: 0.95, label:'-90 dBm', col:'rgba(255,60,0,0.12)'  },
    ];
    rings.forEach(ring=>{
      const r = R * ring.t;
      ctx.beginPath();
      ctx.arc(X,Y,r,0,Math.PI*2);
      ctx.strokeStyle = ring.col;
      ctx.lineWidth   = 1;
      ctx.setLineDash([4,6]);
      ctx.stroke();
      ctx.setLineDash([]);
      // Label
      ctx.fillStyle = ring.col.replace(/[\\d.]+\\)$/, '0.55)');
      ctx.font = '9px JetBrains Mono, monospace';
      ctx.fillText(ring.label, X + r + 4, Y - 4);
    });

    // ── Cross-hairs ──
    ctx.strokeStyle = 'rgba(10,255,110,0.12)';
    ctx.lineWidth = 1;
    ctx.setLineDash([3,8]);
    ctx.beginPath(); ctx.moveTo(X,Y-R*1.05); ctx.lineTo(X,Y+R*1.05); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(X-R*1.05,Y); ctx.lineTo(X+R*1.05,Y); ctx.stroke();
    ctx.setLineDash([]);

    // ── Outer border circle ──
    ctx.beginPath();
    ctx.arc(X,Y,R,0,Math.PI*2);
    ctx.strokeStyle = 'rgba(10,255,110,0.3)';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // ── Sweep line ──
    radarSweepAngle = (radarSweepAngle + 0.018) % (Math.PI*2);
    const sweepGrad = ctx.createConicalGradient
      ? ctx.createConicalGradient(radarSweepAngle, X, Y)
      : null;

    // Draw sweep as a filled wedge (no conical gradient in standard Canvas API)
    const sweepWidth = Math.PI / 8;
    ctx.beginPath();
    ctx.moveTo(X, Y);
    ctx.arc(X, Y, R, radarSweepAngle - sweepWidth, radarSweepAngle);
    ctx.closePath();
    const sg = ctx.createRadialGradient(X,Y,0, X,Y,R);
    sg.addColorStop(0,   'rgba(10,255,110,0.0)');
    sg.addColorStop(0.6, 'rgba(10,255,110,0.05)');
    sg.addColorStop(1,   'rgba(10,255,110,0.13)');
    ctx.fillStyle = sg;
    ctx.fill();

    // Bright leading edge
    ctx.beginPath();
    ctx.moveTo(X, Y);
    ctx.lineTo(X + R * Math.cos(radarSweepAngle), Y + R * Math.sin(radarSweepAngle));
    ctx.strokeStyle = 'rgba(10,255,110,0.7)';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // ── AP blobs ──
    const aps = Object.entries(allAps).sort((a,b)=>a[1].signal - b[1].signal); // weak first = drawn below
    aps.forEach(([key, ap])=>{
      // Assign stable wobble so APs at same signal don't perfectly overlap
      if(!radarWobble[key]){
        radarWobble[key] = { a: Math.random() * Math.PI*2, drift: (Math.random()-0.5)*0.004 };
      }
      radarWobble[key].a += radarWobble[key].drift;

      const dist = sigToRadius(ap.signal);
      const angle = radarWobble[key].a;
      const bx = X + dist * Math.cos(angle);
      const by = Y + dist * Math.sin(angle);

      blobPositions[key] = { x:bx, y:by, ap };

      const isOpen = ap.security && (ap.security.includes('OPEN') || ap.security === '');
      const col    = ap.signal > -55 ? '#0aff6e' : ap.signal > -70 ? '#ffe600' : '#ff3c00';
      const glow   = ap.signal > -55 ? '0 0 12px #0aff6e' : ap.signal > -70 ? '0 0 10px #ffe600' : '0 0 8px #ff3c00';
      const radius = ap.signal > -55 ? 8 : ap.signal > -70 ? 6 : 5;

      // Open network outer warning ring
      if(isOpen){
        ctx.beginPath();
        ctx.arc(bx, by, radius+7, 0, Math.PI*2);
        ctx.strokeStyle = 'rgba(255,60,0,0.5)';
        ctx.lineWidth = 1;
        ctx.setLineDash([3,3]);
        ctx.stroke();
        ctx.setLineDash([]);
      }

      // Glow halo
      const halo = ctx.createRadialGradient(bx,by,0, bx,by,radius*3);
      halo.addColorStop(0,   col + 'cc');
      halo.addColorStop(0.4, col + '44');
      halo.addColorStop(1,   col + '00');
      ctx.beginPath();
      ctx.arc(bx, by, radius*3, 0, Math.PI*2);
      ctx.fillStyle = halo;
      ctx.fill();

      // Core dot
      ctx.beginPath();
      ctx.arc(bx, by, radius, 0, Math.PI*2);
      ctx.fillStyle = col;
      ctx.fill();

      // SSID label
      const label = ap.ssid && ap.ssid !== '<hidden>' ? ap.ssid : '< HIDDEN >';
      ctx.font = 'bold 10px JetBrains Mono, monospace';
      ctx.fillStyle = col;
      ctx.globalAlpha = 0.85;
      // Offset label to avoid overlap with dot
      const lx = bx + radius + 5;
      const ly = by - 4;
      // Shadow for legibility
      ctx.shadowColor = '#020d05';
      ctx.shadowBlur  = 6;
      ctx.fillText(label, lx, ly);
      ctx.fillStyle = 'rgba(10,255,110,0.4)';
      ctx.font = '8px JetBrains Mono, monospace';
      ctx.fillText(ap.signal + ' dBm', lx, ly + 12);
      ctx.shadowBlur  = 0;
      ctx.globalAlpha = 1;
    });

    // ── Centre "YOU ARE HERE" crosshair ──
    ctx.beginPath();
    ctx.arc(X,Y,6,0,Math.PI*2);
    ctx.strokeStyle = '#00e5ff';
    ctx.lineWidth = 1.5;
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(X,Y,2,0,Math.PI*2);
    ctx.fillStyle = '#00e5ff';
    ctx.fill();
    // pulsing outer ring
    const pulse = 0.5 + 0.5*Math.sin(Date.now()/400);
    ctx.beginPath();
    ctx.arc(X,Y,10 + pulse*5,0,Math.PI*2);
    ctx.strokeStyle = `rgba(0,229,255,${0.3*pulse})`;
    ctx.lineWidth = 1;
    ctx.stroke();

    // ── Title ──
    ctx.font = '700 11px JetBrains Mono, monospace';
    ctx.fillStyle = 'rgba(10,255,110,0.35)';
    ctx.fillText('ROOM MODE // SIGNAL PROXIMITY RADAR', X - 170, 28);
  }

  drawFrame();

  // ── Tooltip on hover ──
  const tooltip = document.getElementById('radar-tooltip');
  canvas.addEventListener('mousemove', e=>{
    if(!roomMode) return;
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    let hit = null;
    for(const [key, pos] of Object.entries(blobPositions)){
      const dx = mx - pos.x, dy = my - pos.y;
      if(Math.sqrt(dx*dx+dy*dy) < 18){ hit = pos.ap; break; }
    }
    if(hit){
      const isOpen = hit.security && (hit.security.includes('OPEN') || hit.security === '');
      const col = hit.signal > -55 ? '#0aff6e' : hit.signal > -70 ? '#ffe600' : '#ff3c00';
      const prox = hit.signal > -55 ? 'SAME ROOM' : hit.signal > -65 ? 'VERY CLOSE' : hit.signal > -75 ? 'NEARBY' : 'DISTANT';
      document.getElementById('tt-ssid').textContent = hit.ssid || '<HIDDEN>';
      document.getElementById('tt-ssid').style.color = isOpen ? '#ff3c00' : col;
      document.getElementById('tt-body').innerHTML =
        `<span style="color:#3db869">BSSID</span>  ${hit.bssid}<br>`+
        `<span style="color:#3db869">SIGNAL</span> <span style="color:${col}">${hit.signal} dBm</span><br>`+
        `<span style="color:#3db869">PROX</span>   <span style="color:${col}">${prox}</span><br>`+
        `<span style="color:#3db869">CH</span>     ${hit.channel}<br>`+
        `<span style="color:#3db869">ENC</span>    <span style="color:${isOpen?'#ff3c00':'#0aff6e'}">${hit.security||'OPEN'}</span><br>`+
        `<span style="color:#3db869">VENDOR</span> ${hit.vendor||'?'}`;
      tooltip.style.display = 'block';
      let tx = e.clientX + 18, ty = e.clientY - 10;
      if(tx + 220 > window.innerWidth) tx = e.clientX - 230;
      tooltip.style.left = tx + 'px';
      tooltip.style.top  = ty + 'px';
    } else {
      tooltip.style.display = 'none';
    }
  });
  canvas.addEventListener('mouseleave', ()=>{ tooltip.style.display='none'; });
}

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
  const pt = map.latLngToContainerPoint([lat,lon]);
  radarCenter = {x:pt.x, y:pt.y};
}

// ═══════════════════════════════════════════════════════════════
// BROWSER GEOLOCATION fallback
// ═══════════════════════════════════════════════════════════════
if(navigator.geolocation){
  navigator.geolocation.watchPosition(pos=>{
    if(gpsLocked) return;
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
      '<span>'+(ap.vendor||'?')+'</span>'+
      '<span style="color:#003d11;font-size:8px">'+(ap.source||'')+'</span>'+
      '</div>';
    d.onclick=()=>{
      if(!roomMode && markers[key]){ map.flyTo([ap.lat,ap.lon],17,{duration:0.7}); markers[key].openPopup(); }
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
  const isOpen  = ap.security && (ap.security.includes('OPEN') || ap.security === '');
  const isWPA3  = ap.security && ap.security.includes('WPA3');
  const isWPA2  = ap.security && ap.security.includes('WPA2') && !isWPA3;
  const isHidden= !ap.ssid || ap.ssid === '<hidden>';

  let threat = 0;
  if (isOpen)          threat += 60;
  if (isHidden)        threat += 15;
  if (ap.signal > -55) threat += 15;
  if (ap.channel === 0)threat += 10;
  threat = Math.min(threat, 100);

  const tCol  = threat >= 60 ? '#ff3c00' : threat >= 30 ? '#ffe600' : '#0aff6e';
  const tLbl  = threat >= 60 ? 'HIGH'    : threat >= 30 ? 'MEDIUM'  : 'LOW';
  const mCol  = isOpen ? '#ff3c00' : isWPA3 ? '#00e5ff' : '#0aff6e';
  const sCol  = ap.signal > -55 ? '#0aff6e' : ap.signal > -70 ? '#ffe600' : '#ff3c00';
  const sigPct= Math.max(0, Math.min(100, (ap.signal + 100) * 2));

  const encBadge = isOpen
    ? `<span style="background:#ff3c0022;color:#ff3c00;border:1px solid #ff3c00;padding:2px 8px;font-size:9px;letter-spacing:2px;font-family:JetBrains Mono,monospace;">&#9888; UNENCRYPTED</span>`
    : isWPA3
    ? `<span style="background:#00e5ff18;color:#00e5ff;border:1px solid #00e5ff55;padding:2px 8px;font-size:9px;letter-spacing:2px;font-family:JetBrains Mono,monospace;">&#9679; WPA3</span>`
    : isWPA2
    ? `<span style="background:#0aff6e18;color:#0aff6e;border:1px solid #0aff6e44;padding:2px 8px;font-size:9px;letter-spacing:2px;font-family:JetBrains Mono,monospace;">&#9679; WPA2</span>`
    : `<span style="background:#ffe60018;color:#ffe600;border:1px solid #ffe60044;padding:2px 8px;font-size:9px;letter-spacing:2px;font-family:JetBrains Mono,monospace;">&#9888; LEGACY</span>`;

  const band = ap.channel >= 36 ? '5 GHz' : ap.channel > 0 ? '2.4 GHz' : '---';
  const freq = ap.channel >= 36
    ? (5180 + (ap.channel - 36) * 5) + ' MHz'
    : ap.channel > 0 ? (2412 + (ap.channel - 1) * 5) + ' MHz' : '---';
  const sLbl = ap.signal > -55 ? 'EXCELLENT' : ap.signal > -65 ? 'GOOD' : ap.signal > -75 ? 'FAIR' : 'WEAK';

  const ssidDisplay = isHidden ? '[ HIDDEN ]' : (ap.ssid || '---');
  const lat6 = ap.lat ? ap.lat.toFixed(6) : '---';
  const lon6 = ap.lon ? ap.lon.toFixed(6) : '---';

  return `
<div style="font-family:JetBrains Mono,monospace;background:#020d05;border:1px solid ${mCol}66;min-width:280px;overflow:hidden;">
  <div style="background:linear-gradient(135deg,${mCol}22,${mCol}08);border-bottom:1px solid ${mCol}44;padding:10px 12px 8px;">
    <div style="font-family:'Bebas Neue',cursive,monospace;font-size:20px;letter-spacing:4px;color:${mCol};text-shadow:0 0 10px ${mCol};line-height:1.1;">
      ${ssidDisplay}
    </div>
    <div style="color:#5aff9a;font-size:9px;letter-spacing:1px;margin-top:3px;font-family:JetBrains Mono,monospace;opacity:0.7;">
      ${ap.bssid || '---'}
    </div>
    <div style="margin-top:6px;display:flex;gap:6px;align-items:center;flex-wrap:wrap;">
      ${encBadge}
    </div>
  </div>
  <div style="padding:8px 12px 7px;border-bottom:1px solid #0aff6e22;">
    <div style="display:flex;justify-content:space-between;margin-bottom:4px;">
      <span style="font-size:9px;color:#4dcc77;letter-spacing:2px;">SIGNAL STRENGTH</span>
      <span style="font-size:10px;color:${sCol};font-family:'Bebas Neue',monospace;letter-spacing:1px;">${ap.signal} dBm &nbsp; ${sLbl}</span>
    </div>
    <div style="height:5px;background:#071a0e;border:1px solid #0aff6e33;border-radius:2px;overflow:hidden;">
      <div style="height:100%;width:${sigPct}%;background:linear-gradient(90deg,${sCol}88,${sCol});box-shadow:0 0 5px ${sCol};"></div>
    </div>
  </div>
  <div style="padding:7px 12px;border-bottom:1px solid ${tCol}33;background:${tCol}12;">
    <div style="display:flex;justify-content:space-between;margin-bottom:4px;">
      <span style="font-size:9px;color:#4dcc77;letter-spacing:2px;">THREAT SCORE</span>
      <span style="font-family:'Bebas Neue',monospace;font-size:13px;color:${tCol};text-shadow:0 0 8px ${tCol};letter-spacing:3px;">${tLbl} &nbsp; ${threat}/100</span>
    </div>
    <div style="height:4px;background:#071a0e;border:1px solid ${tCol}33;border-radius:2px;overflow:hidden;">
      <div style="height:100%;width:${threat}%;background:linear-gradient(90deg,${tCol}88,${tCol});box-shadow:0 0 4px ${tCol};"></div>
    </div>
  </div>
  <div style="padding:8px 12px 10px;">
    <table style="width:100%;border-collapse:collapse;font-size:10px;">
      <tr><td style="color:#3db869;padding:3px 14px 3px 0;font-size:9px;letter-spacing:2px;white-space:nowrap;vertical-align:top;text-transform:uppercase;">Vendor</td><td style="color:#00ccff;padding:3px 0;font-weight:bold;">${ap.vendor || 'UNKNOWN'}</td></tr>
      <tr><td style="color:#3db869;padding:3px 14px 3px 0;font-size:9px;letter-spacing:2px;white-space:nowrap;vertical-align:top;text-transform:uppercase;">Channel</td><td style="color:#0aff6e;padding:3px 0;">${ap.channel > 0 ? ap.channel : '?'} <span style="color:#5aaa77;font-size:9px;">${band} // ${freq}</span></td></tr>
      <tr><td style="color:#3db869;padding:3px 14px 3px 0;font-size:9px;letter-spacing:2px;white-space:nowrap;vertical-align:top;text-transform:uppercase;">Encryption</td><td style="color:${mCol};padding:3px 0;font-weight:bold;">${ap.security || 'NONE'}</td></tr>
      <tr><td style="color:#3db869;padding:3px 14px 3px 0;font-size:9px;letter-spacing:2px;white-space:nowrap;vertical-align:top;text-transform:uppercase;">Geo Source</td><td style="color:#7acc99;padding:3px 0;font-size:9px;">${ap.source || '---'}</td></tr>
      <tr><td style="color:#3db869;padding:3px 14px 3px 0;font-size:9px;letter-spacing:2px;white-space:nowrap;vertical-align:top;text-transform:uppercase;">Coords</td><td style="color:#5aaa77;padding:3px 0;font-size:9px;">${lat6}, ${lon6}</td></tr>
    </table>
  </div>
  <div style="border-top:1px solid #0aff6e22;padding:5px 12px;display:flex;justify-content:space-between;background:#0aff6e0a;">
    <span style="font-size:8px;color:#3d7a50;letter-spacing:3px;">BLACK ICE v2</span>
    <span style="font-size:8px;color:${mCol}aa;letter-spacing:1px;">NIGHTFALL35</span>
  </div>
</div>`;
}

// ═══════════════════════════════════════════════════════════════
// DISPLAY UPDATE
// ═══════════════════════════════════════════════════════════════
function updateDisplay(aps){
  allAps=aps;
  document.getElementById('cnt').textContent=String(Object.keys(aps).length).padStart(3,'0');
  updateThreat(aps);
  if(panelOpen) renderPanel();

  // Only update map markers when not in room mode
  if(!roomMode){
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
        m.bindPopup(makePopup(ap), {maxWidth: 320, minWidth: 280});
        markers[key]=m;

        if(isOpen){
          const ring=L.circleMarker([ap.lat,ap.lon],{
            radius:r+8,color:'#ff3c00',fillColor:'transparent',weight:1,opacity:0.5
          }).addTo(map);
          pulseRings[key]=ring;
          setTimeout(()=>{
            const el=ring.getElement();
            if(el){el.style.animation='blinkbadge 1.4s ease-out infinite';}
          },50);
        }
      }
    });

    Object.keys(markers).forEach(key=>{
      if(!aps[key]){
        map.removeLayer(markers[key]); delete markers[key];
        if(pulseRings[key]){map.removeLayer(pulseRings[key]);delete pulseRings[key];}
      }
    });
  } else {
    // In room mode just log new APs
    Object.entries(aps).forEach(([key,ap])=>{
      if(!markers[key]){
        // Create a placeholder marker off-screen so panel clicks don't crash
        const isOpen=ap.security&&(ap.security.includes('OPEN')||ap.security==='');
        addLog('SIG',(ap.ssid||'<HIDDEN>')+' '+ap.signal+'dBm',isOpen?'c':'n');
        markers[key] = {_roomPlaceholder:true};
      }
    });
  }
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
        // Survey heatmap ingestion — pass operator lat/lon from SSE
        if(d.survey){
          const op = d.operator || {};
          ingestSurveyReadings(d.aps, op.lat, op.lon, d.survey.readings);
          // Keep survey button in sync if server restarted
          if(d.survey.active && !surveyMode){
            document.getElementById('survey-btn').classList.add('survey-active');
            document.getElementById('survey-legend').classList.add('active');
            surveyMode = true;
          }
        }
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
// WALK SURVEY — heatmap overlay
// ═══════════════════════════════════════════════════════════════
let surveyMode     = false;
let surveyReadings = 0;
// surveyData: { bssid -> { ssid, readings:[{lat,lon,rssi}], color, visible } }
const surveyData   = {};
const heatLayers   = {}; // bssid -> array of Leaflet circleMarkers
// Palette for per-AP colouring — cycles through distinct hues
const surveyPalette = [
  '#0aff6e','#00e5ff','#ffe600','#ff3c00','#ff00cc','#9900ff',
  '#00ffcc','#ff9900','#66ff00','#ff0066','#0066ff','#ffcc00'
];
let surveyPaletteIdx = 0;

function surveyColorFor(bssid){
  if(!surveyData[bssid]) surveyData[bssid] = {
    ssid:'?', readings:[], color: surveyPalette[surveyPaletteIdx++ % surveyPalette.length], visible:true
  };
  return surveyData[bssid].color;
}

function toggleSurvey(){
  surveyMode = !surveyMode;
  const btn = document.getElementById('survey-btn');
  const legend = document.getElementById('survey-legend');
  if(surveyMode){
    btn.classList.add('survey-active');
    legend.classList.add('active');
    fetch('/survey', {method:'POST', body:'start'});
    addLog('SURVEY','WALK SURVEY STARTED — move around to build heatmap','w');
  } else {
    btn.classList.remove('survey-active');
    fetch('/survey', {method:'POST', body:'stop'});
    addLog('SURVEY','WALK SURVEY PAUSED — ' + surveyReadings + ' readings','i');
  }
}

function surveyDownload(){
  window.open('/survey/export','_blank');
}

function surveyClear(){
  fetch('/survey', {method:'POST', body:'clear'}).then(()=>{
    // Remove all heatmap layers from map
    Object.values(heatLayers).forEach(arr => arr.forEach(l => map.removeLayer(l)));
    Object.keys(heatLayers).forEach(k => delete heatLayers[k]);
    Object.keys(surveyData).forEach(k => { surveyData[k].readings = []; });
    surveyReadings = 0;
    document.getElementById('survey-cnt').textContent = '0';
    document.getElementById('survey-reading-label').textContent = '0 READINGS';
    renderSurveyLegend();
    addLog('SURVEY','SURVEY DATA CLEARED','w');
  });
}

// Called on every SSE update when survey is active
function ingestSurveyReadings(aps, opLat, opLon, serverReadings){
  document.getElementById('survey-cnt').textContent = serverReadings;
  document.getElementById('survey-reading-label').textContent = serverReadings + ' READINGS';
  surveyReadings = serverReadings;

  if(!surveyMode) return;
  // Need a GPS fix to record meaningful positions
  if(!opLat || !opLon) return;

  Object.entries(aps).forEach(([key, ap])=>{
    if(!surveyData[key]){
      surveyData[key] = {
        ssid: ap.ssid || key,
        readings: [],
        color: surveyPalette[surveyPaletteIdx++ % surveyPalette.length],
        visible: true
      };
    }
    surveyData[key].ssid = ap.ssid || key;

    // Deduplicate — only add if moved > ~3m from last reading for this AP
    const last = surveyData[key].readings[surveyData[key].readings.length - 1];
    if(last){
      const dlat = opLat - last.lat, dlon = opLon - last.lon;
      if(Math.sqrt(dlat*dlat + dlon*dlon) < 0.00003) return; // ~3m threshold
    }

    surveyData[key].readings.push({lat:opLat, lon:opLon, rssi:ap.signal});
    drawHeatPoint(key, opLat, opLon, ap.signal);
  });

  renderSurveyLegend();
}

function drawHeatPoint(bssid, lat, lon, rssi){
  if(!surveyData[bssid] || !surveyData[bssid].visible) return;
  const col   = surveyData[bssid].color;
  const alpha = rssi > -55 ? 0.75 : rssi > -70 ? 0.55 : 0.35;
  const r     = rssi > -55 ? 22   : rssi > -70 ? 16   : 10;

  const circle = L.circleMarker([lat, lon], {
    radius:      r,
    color:       col,
    fillColor:   col,
    fillOpacity: alpha,
    weight:      0,
    className:   'survey-point'
  }).addTo(map);

  circle.bindTooltip(
    '<span style="font-family:JetBrains Mono,monospace;font-size:10px;color:' + col + '">' +
    (surveyData[bssid].ssid||bssid) + '<br>' + rssi + ' dBm</span>',
    {sticky:true, opacity:0.95, className:'survey-tip'}
  );

  if(!heatLayers[bssid]) heatLayers[bssid] = [];
  heatLayers[bssid].push(circle);
}

function renderSurveyLegend(){
  if(!surveyMode && Object.keys(surveyData).length === 0) return;
  const container = document.getElementById('survey-ap-filter');
  container.innerHTML = '';
  Object.entries(surveyData).forEach(([bssid, d])=>{
    if(d.readings.length === 0) return;
    const row = document.createElement('div');
    row.className = 'survey-ap-row';
    row.style.color = d.visible ? d.color : '#333';
    row.innerHTML =
      '<div class="survey-ap-dot" style="background:' + (d.visible?d.color:'#333') + ';box-shadow:0 0 4px ' + d.color + '"></div>' +
      '<span>' + (d.ssid&&d.ssid!=='<hidden>'?d.ssid:bssid.slice(-8)) + '</span>' +
      '<span style="color:#555;margin-left:auto;">' + d.readings.length + '</span>';
    row.title = bssid;
    row.onclick = ()=>{
      d.visible = !d.visible;
      // Show/hide map layers for this AP
      if(heatLayers[bssid]) heatLayers[bssid].forEach(l=>{
        if(d.visible) map.addLayer(l); else map.removeLayer(l);
      });
      renderSurveyLegend();
    };
    container.appendChild(row);
  });
}

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
  '> MAP TILES: CARTO DARK MATTER',
  '> ROOM MODE RADAR: READY',
  '> WALK SURVEY ENGINE: ARMED',
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