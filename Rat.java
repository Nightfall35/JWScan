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
 *
 * FIXES APPLIED (v2.2):
 *   1–9. All previous v2.1 fixes retained (see below)
 *  10. detectOwnMac() — missing return statement on error path fixed (was: compile error)
 *  11. startHttpServer() kept for reference but NOT called in start() — EnterpriseApiServer
 *      is used instead. startHttpServer() can be removed once transition is confirmed.
 *  12. Public API methods added for EnterpriseApiServer:
 *        - getDashboardHtml()   — returns the embedded dashboard HTML string
 *        - getAllApsJson()       — JSON array of all live APs
 *        - getApJson(bssid)     — JSON object for a single AP, or null
 *        - getThreatsJson()     — JSON array of open / evil-twin APs (threat feed)
 *        - getStatsJson()       — aggregate counts: total, open, gpsActive, surveyReadings, localBssids
 *        - getAlertRulesJson()  — JSON array of current alert rules
 *        - addAlertRule(body)   — parse JSON body, add rule, return {"id":"..."}
 *        - deleteAlertRule(id)  — remove rule by id
 *        - exportCsv()          — byte[] of survey CSV (throws if no file)
 *        - exportKml()          — byte[] of KML (delegates to buildKml())
 *        - exportGpx()          — byte[] of GPX (delegates to buildGpx())
 *        - registerSseClient()  — add an authenticated HttpExchange to sseClients
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
    private final Map<String, AP> seenById          = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService background         = Executors.newSingleThreadExecutor();
    private final ExecutorService geoExecutor        = Executors.newFixedThreadPool(4);
    private final Map<String, Long> geoLastAttempt   = new ConcurrentHashMap<>();
    private final int httpPort;
    private final DateTimeFormatter dtf              = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PassiveScanner passiveScanner;
    private HttpServer     httpServer;
    private Deauther       deauther    = null;

    private final Map<String, String>                ouiMap     = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<HttpExchange> sseClients = new CopyOnWriteArrayList<>();

    private final SwarmAi         ai;
    private final String          myMac;          // FIX 10: assigned in constructor via detectOwnMac()
    private       boolean         counterMode  = false;
    private final OuiDatabase     ouiDatabase;
    private final WigleGeolocator geolocator;
    private volatile double  operatorLat = -15.3875;
    private volatile double  operatorLon =  28.3228;
    private volatile boolean gpsActive   = false;
    private GPSReader gpsReader = null;

    // Threat tracking (for getThreatsJson)
    private final Set<String>   evilTwinBssids = ConcurrentHashMap.newKeySet();
    private final Set<String>   attackSrcMacs  = ConcurrentHashMap.newKeySet();

    // FIX #6: CountDownLatch replaces busy-wait in downloadAndCacheIeeeOui
    private final CountDownLatch ouiReady = new CountDownLatch(1);

    // ── Survey / wardriving state ────────────────────────────────────────────────
    private volatile boolean     surveyActive   = false;
    private final AtomicLong     surveyReadings = new AtomicLong(0);
    private final Path           surveyFile     = Paths.get("survey_log.csv");
    private PrintWriter          surveyWriter   = null;
    private final Object         surveyLock     = new Object();

    // ── Local geo database (CSV-backed weighted centroid, no extra deps) ─────────
    private final LocalGeoDatabase localGeo = new LocalGeoDatabase(this);

    // ── Alert rules ──────────────────────────────────────────────────────────────
    public static class AlertRule {
        public final String  id;
        public final String  type;    // "ssid_appears","ssid_disappears","ssid_pattern","open_network"
        public final String  pattern; // regex or exact SSID; empty = match all
        public final boolean enabled;
        public AlertRule(String id, String type, String pattern, boolean enabled) {
            this.id = id; this.type = type; this.pattern = pattern; this.enabled = enabled;
        }
        public String toJson() {
            return "{\"id\":\"" + id + "\",\"type\":\"" + type
                + "\",\"pattern\":\"" + pattern.replace("\\","\\\\").replace("\"","\\\"")
                + "\",\"enabled\":" + enabled + "}";
        }
    }
    private final List<AlertRule>     alertRules    = new CopyOnWriteArrayList<>();
    private final Map<String, Long>   lastAlertTime = new ConcurrentHashMap<>();
    private final Map<String, String> knownSsids    = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 30_000;

    // ── Constructor ─────────────────────────────────────────────────────────────
    public Rat(int port) {
        this.httpPort    = port;
        this.myMac       = detectOwnMac();   // FIX 10: correct initialization
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
                attackSrcMacs.add(src);
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
        println("[API] Own MAC detected as: " + myMac);

        // FIX 11: Use EnterpriseApiServer instead of inline startHttpServer()
        new EnterpriseApiServer(this, httpPort).start();

        background.submit(() -> {
            try {
                passiveScanner = new PassiveScanner(this);
                passiveScanner.start();
                println("PASSIVE SCANNER ONLINE");
            } catch (Exception e) {
                printlnStrongAlert("SCANNER FAILED TO START: " + e.getMessage());
                printlnStrongAlert("CAUSE: " + (e.getCause() != null ? e.getCause().getMessage() : "unknown"));
                printlnStrongAlert("CHECK: 1) Npcap installed  2) Running as Admin  3) Monitor-mode adapter present");
                printlnStrongAlert("SCANNER OFFLINE — dashboard still running at http://localhost:" + httpPort);
                passiveScanner = null;
            }
        });

        localGeo.load();
        println("[GEO] LOCAL DB LOADED → " + localGeo.size() + " BSSIDs with position estimates");

        scheduler.scheduleAtFixedRate(this::broadcastFullUpdate, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::evictStaleAps, 5, 5, TimeUnit.MINUTES);
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
        resolvePositions();
        String json = buildFullJson();
        String msg  = "data: " + json + "\n\n";
        printlnDebug("Broadcasting to " + sseClients.size() + " clients: "
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

    // ── FIX #3: Position resolution extracted from buildFullJson ────────────────
    private void resolvePositions() {
        long now = System.currentTimeMillis();
        for (AP ap : seenById.values()) {
            if (now - ap.lastSeen > 3_000_000) continue;

            LocalGeoDatabase.GeoEstimate localEst = localGeo.lookup(ap.bssid);
            if (localEst != null) {
                if (!ap.positionRandom || (ap.source != null && !ap.source.startsWith("LocalDB"))) {
                    ap.lat            = localEst.lat;
                    ap.lon            = localEst.lon;
                    ap.source         = "LocalDB(" + localEst.readings + "pts)";
                    ap.positionRandom = true;
                    println("[GEO] LOCAL HIT → " + ap.ssid + " @ " + localEst.lat + "," + localEst.lon
                            + " (" + localEst.readings + " readings, acc±"
                            + String.format("%.1f", localEst.accuracyMeters) + "m)");
                }
                continue;
            }
            if (ap.positionRandom) continue;

            Long lastAttempt = geoLastAttempt.get(ap.bssid);
            if (lastAttempt == null || now - lastAttempt > 30_000) {
                geoLastAttempt.put(ap.bssid, now);
                final AP apRef = ap;
                geoExecutor.submit(() -> {
                    WigleGeolocator.GeoResult geo = geolocator.geolocate(apRef.bssid, apRef.ssid);
                    if (geo.success && !(geo.lat == -0.0 && geo.lon == 0.0)) {
                        apRef.lat             = geo.lat;
                        apRef.lon             = geo.lon;
                        apRef.source          = geo.source;
                        apRef.positionRandom  = true;
                        println("[GEO] WIGLE FIX → " + apRef.ssid + " @ " + geo.lat + "," + geo.lon);
                    } else {
                        WigleGeolocator.GeoResult approx = geolocator.getApproximateLocation();
                        if (approx.success) {
                            double offset  = 0.03 * (Math.random() - 0.5);
                            apRef.lat      = approx.lat + offset;
                            apRef.lon      = approx.lon + offset;
                            apRef.source   = "Approx+IP";
                        } else {
                            apRef.lat    = operatorLat + (Math.random() - 0.5) * 0.01;
                            apRef.lon    = operatorLon + (Math.random() - 0.5) * 0.01;
                            apRef.source = "Jitter";
                        }
                        apRef.positionRandom = true;
                    }
                });
            }
        }
    }

    // ── JSON builder — pure serialization, NO side-effects ──────────────────────
    public String buildFullJson() {
        StringBuilder sb = new StringBuilder(
            "{\"type\":\"full\",\"operator\":{\"lat\":" + operatorLat + ",\"lon\":" + operatorLon
            + ",\"gps\":" + gpsActive + "},\"survey\":{\"active\":" + surveyActive
            + ",\"readings\":" + surveyReadings.get() + "},\"geo\":{\"localBssids\":"
            + localGeo.size() + "},\"aps\":{");
        boolean first = true;
        long    now   = System.currentTimeMillis();

        for (Map.Entry<String, AP> e : seenById.entrySet()) {
            AP ap = e.getValue();
            if (now - ap.lastSeen > 3_000_000) continue;
            if (!first) sb.append(",");
            first = false;

            String vendor = ouiDatabase.lookup(ap.bssid);
            if (vendor == null) vendor = getVendorFromBssid(ap.bssid);
            if (vendor == null) vendor = "unknown";

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

    // ═══════════════════════════════════════════════════════════════════════════
    //  PUBLIC API METHODS — required by EnterpriseApiServer (FIX 12)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the full dashboard HTML string.
     * EnterpriseApiServer serves this on GET /.
     */
    public String getDashboardHtml() {
        return DASHBOARD_HTML;
    }

    /**
     * Returns all live APs as a JSON array.
     * Each element is the same object shape as buildFullJson()'s "aps" map values,
     * plus a "bssid" field for convenience.
     * EnterpriseApiServer serves this on GET /api/v1/aps.
     */
    public String getAllApsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        long now = System.currentTimeMillis();
        for (AP ap : seenById.values()) {
            if (now - ap.lastSeen > 3_000_000) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append(apToJson(ap));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Returns a single AP as a JSON object, or null if not found.
     * The bssid parameter is compared case-insensitively.
     * EnterpriseApiServer serves this on GET /api/v1/aps/{bssid}.
     */
    public String getApJson(String bssid) {
        if (bssid == null) return null;
        String key = bssid.toUpperCase();
        AP ap = seenById.get(key);
        if (ap == null) {
            // Try alternate separators (: vs -)
            for (Map.Entry<String, AP> e : seenById.entrySet()) {
                if (e.getKey().replace("-", ":").equalsIgnoreCase(key.replace("-", ":"))) {
                    ap = e.getValue();
                    break;
                }
            }
        }
        return ap != null ? apToJson(ap) : null;
    }

    /**
     * Returns a JSON array of threat-relevant APs:
     * open networks, known evil-twin BSSIDs, and APs linked to deauth sources.
     * EnterpriseApiServer serves this on GET /api/v1/threats.
     */
    public String getThreatsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        long now = System.currentTimeMillis();
        for (AP ap : seenById.values()) {
            if (now - ap.lastSeen > 3_000_000) continue;
            boolean isOpen    = isOpen(ap);
            boolean isEvil    = evilTwinBssids.contains(ap.bssid);
            boolean isAttacker = attackSrcMacs.contains(ap.bssid);
            if (!isOpen && !isEvil && !isAttacker) continue;

            if (!first) sb.append(",");
            first = false;
            // Wrap the normal AP JSON with threat metadata
            sb.append("{\"ap\":").append(apToJson(ap))
              .append(",\"threats\":[");
            boolean ft = true;
            if (isOpen)     { sb.append("\"OPEN_NETWORK\"");  ft = false; }
            if (isEvil)     { if (!ft) sb.append(","); sb.append("\"EVIL_TWIN\"");    ft = false; }
            if (isAttacker) { if (!ft) sb.append(","); sb.append("\"DEAUTH_SOURCE\""); }
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Returns aggregate statistics as a JSON object.
     * EnterpriseApiServer serves this on GET /api/v1/stats.
     */
    public String getStatsJson() {
        long now = System.currentTimeMillis();
        long total = seenById.values().stream().filter(a -> now - a.lastSeen <= 3_000_000).count();
        long open  = seenById.values().stream().filter(a -> now - a.lastSeen <= 3_000_000 && isOpen(a)).count();
        long evil  = evilTwinBssids.size();
        String level = open == 0 ? "NOMINAL" : open < 3 ? "ELEVATED" : "CRITICAL";
        return "{\"total\":" + total
            + ",\"open\":" + open
            + ",\"evilTwins\":" + evil
            + ",\"deauthSources\":" + attackSrcMacs.size()
            + ",\"threatLevel\":\"" + level + "\""
            + ",\"gpsActive\":" + gpsActive
            + ",\"surveyActive\":" + surveyActive
            + ",\"surveyReadings\":" + surveyReadings.get()
            + ",\"localBssids\":" + localGeo.size()
            + ",\"operatorLat\":" + operatorLat
            + ",\"operatorLon\":" + operatorLon
            + "}";
    }

    /**
     * Returns the current alert rules as a JSON array.
     * EnterpriseApiServer serves this on GET /api/v1/alerts.
     */
    public String getAlertRulesJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < alertRules.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(alertRules.get(i).toJson());
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Parses a JSON body of the form {"type":"...","pattern":"..."} and adds an alert rule.
     * Returns a JSON object with the new rule's id.
     * EnterpriseApiServer calls this on POST /api/v1/alerts.
     */
    public String addAlertRule(String jsonBody) {
        String type    = extractJsonString(jsonBody, "type");
        String pattern = extractJsonString(jsonBody, "pattern");
        String id      = "rule_" + System.currentTimeMillis();
        alertRules.add(new AlertRule(id, type, pattern, true));
        println("[ALERTS] Rule added: " + id + " type=" + type + " pattern=" + pattern);
        return "{\"id\":\"" + id + "\"}";
    }

    /**
     * Removes the alert rule with the given id.
     * EnterpriseApiServer calls this on DELETE /api/v1/alerts/{id}.
     */
    public void deleteAlertRule(String id) {
        alertRules.removeIf(r -> r.id.equals(id));
        println("[ALERTS] Rule deleted: " + id);
    }

    /**
     * Returns the raw survey CSV bytes.
     * EnterpriseApiServer serves this on GET /api/v1/export/csv.
     */
    public byte[] exportCsv() throws IOException {
        if (!Files.exists(surveyFile)) throw new IOException("No survey data yet");
        return Files.readAllBytes(surveyFile);
    }

    /**
     * Returns KML bytes of the survey data.
     * EnterpriseApiServer serves this on GET /api/v1/export/kml.
     */
    public byte[] exportKml() throws IOException {
        return buildKml();
    }

    /**
     * Returns GPX bytes of the survey data.
     * EnterpriseApiServer serves this on GET /api/v1/export/gpx.
     */
    public byte[] exportGpx() throws IOException {
        return buildGpx();
    }

    /**
     * Registers an authenticated HttpExchange as an SSE client.
     * EnterpriseApiServer calls this after auth passes on GET /sse.
     * This method blocks on the calling thread (one thread per SSE client),
     * exactly as the original inline SSE handler did.
     */
    public void registerSseClient(HttpExchange exchange) {
        println("New SSE connection from " + exchange.getRemoteAddress());
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type",  "text/event-stream");
        h.set("Cache-Control", "no-cache");
        h.set("Connection",    "keep-alive");
        h.set("Access-Control-Allow-Origin", "*");
        try {
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
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
    }

    // ── Helper: serialize a single AP to JSON ────────────────────────────────────
   private String apToJson(AP ap) {
    // Detect locally administered (randomised) MAC addresses.
    // When bit 1 of the first octet is set, the MAC was randomly generated
    // by the OS — no IEEE OUI exists for it, so vendor lookup is meaningless.
    String vendor;
    try {
        int firstOctet = Integer.parseInt(
            ap.bssid.substring(0, 2).replace(":", ""), 16);
        boolean isRandomised = (firstOctet & 0x02) != 0;
        if (isRandomised) {
            vendor = "Randomised MAC";
        } else {
            vendor = ouiDatabase.lookup(ap.bssid);
            if (vendor == null) vendor = getVendorFromBssid(ap.bssid);
            if (vendor == null) vendor = "unknown";
        }
    } catch (Exception e) {
        vendor = "unknown";
    }

    return "{\"ssid\":\""     + jsonEscape(ap.ssid)     + "\","
         + "\"bssid\":\""    + jsonEscape(ap.bssid)    + "\","
         + "\"security\":\"" + jsonEscape(ap.security) + "\","
         + "\"signal\":"     + ap.signal                + ","
         + "\"channel\":"    + ap.channel               + ","
         + "\"vendor\":\""   + jsonEscape(vendor)       + "\","
         + "\"lat\":"        + ap.lat                   + ","
         + "\"lon\":"        + ap.lon                   + ","
         + "\"source\":\""   + jsonEscape(ap.source)    + "\","
         + "\"lastSeen\":"   + ap.lastSeen
         + "}";
}

    // ── AP event handlers ───────────────────────────────────────────────────────
    public void onAccessPointDiscovered(AP ap) {
        if(ap.channel == 0 && ap.signal <= 95 && ap.ssid.equalsIgnoreCase("unknown")) {
            return; // likely a noise spike , ignore it 
            
        }
        String id       = !ap.bssid.isEmpty() ? ap.bssid : ap.ssid;
        AP     existing = seenById.get(id);

        ap.positionRandom = false;
        ap.lastSeen       = System.currentTimeMillis();

        if (existing == null) {
            seenById.put(id, ap);
            printlnAlert("NEW AP → " + summarize(ap));
            printlnDebug("Total APs in memory: " + seenById.size());
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
        checkAlertRules(ap, existing == null);
        knownSsids.put(ap.bssid, ap.ssid);
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
        evilTwinBssids.add(fakeBssid); // track for getThreatsJson()
        if (deauther != null) {
            new Thread(() -> deauther.deauth("FF:FF:FF:FF:FF:FF", fakeBssid, 0)).start();
        }
    }

    // ── Alert rules engine ──────────────────────────────────────────────────────
    private void checkAlertRules(AP ap, boolean isNew) {
        if (alertRules.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (AlertRule rule : alertRules) {
            if (!rule.enabled) continue;
            boolean fired = false;
            switch (rule.type) {
                case "ssid_appears"  -> { if (isNew && matchesPattern(ap.ssid, rule.pattern)) fired = true; }
                case "ssid_pattern"  -> { if (matchesPattern(ap.ssid, rule.pattern)) fired = true; }
                case "open_network"  -> { if (isNew && isOpen(ap)) fired = true; }
            }
            if (fired) {
                Long last = lastAlertTime.get(rule.id);
                if (last == null || now - last > ALERT_COOLDOWN_MS) {
                    lastAlertTime.put(rule.id, now);
                    String msg = String.format("[ALERT-RULE:%s] %s matched SSID='%s' BSSID=%s",
                        rule.id, rule.type, ap.ssid, ap.bssid);
                    printlnStrongAlert(msg);
                    soundBell();
                    broadcastAlert(rule, ap);
                }
            }
        }
    }

    private void checkDisappearsRules(AP ap) {
        if (alertRules.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (AlertRule rule : alertRules) {
            if (!rule.enabled || !"ssid_disappears".equals(rule.type)) continue;
            if (matchesPattern(ap.ssid, rule.pattern)) {
                Long last = lastAlertTime.get(rule.id);
                if (last == null || now - last > ALERT_COOLDOWN_MS) {
                    lastAlertTime.put(rule.id, now);
                    String msg = String.format("[ALERT-RULE:%s] ssid_disappears → SSID='%s' BSSID=%s evicted after 30min",
                        rule.id, ap.ssid, ap.bssid);
                    printlnStrongAlert(msg);
                    soundBell();
                    broadcastAlert(rule, ap);
                }
            }
        }
    }

    private boolean matchesPattern(String ssid, String pattern) {
        if (pattern == null || pattern.isEmpty()) return true;
        try { return ssid != null && ssid.matches(pattern); }
        catch (Exception e) { return ssid != null && ssid.contains(pattern); }
    }

    private void broadcastAlert(AlertRule rule, AP ap) {
        String json = String.format(
            "data: {\"type\":\"alert\",\"rule\":%s,\"ssid\":\"%s\",\"bssid\":\"%s\",\"signal\":%d}\n\n",
            rule.toJson(), jsonEscape(ap.ssid), jsonEscape(ap.bssid), ap.signal);
        for (HttpExchange ex : sseClients) {
            try { ex.getResponseBody().write(json.getBytes(StandardCharsets.UTF_8)); ex.getResponseBody().flush(); }
            catch (Exception ignored) {}
        }
    }

    // ── AP eviction ─────────────────────────────────────────────────────────────
    private static final long EVICT_MS = 30L * 60 * 1_000;

    private void evictStaleAps() {
        long cutoff = System.currentTimeMillis() - EVICT_MS;
        int  before = seenById.size();
        seenById.entrySet().removeIf(entry -> {
            AP ap = entry.getValue();
            if (ap.lastSeen < cutoff) {
                checkDisappearsRules(ap);
                knownSsids.remove(ap.bssid);
                evilTwinBssids.remove(ap.bssid);
                attackSrcMacs.remove(ap.bssid);
                return true;
            }
            return false;
        });
        int evicted = before - seenById.size();
        if (evicted > 0)
            printlnAlert("[EVICT] Removed " + evicted + " stale APs (not seen in 30min). Active: " + seenById.size());
    }

    // ── Shutdown ────────────────────────────────────────────────────────────────
    private void shutdown() {
        println("\nShutting down BLACK ICE...");
        if (passiveScanner != null) passiveScanner.stop();
        stopSurvey();
        localGeo.shutdown();
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

    private void writeSurveyReadings() {
        if (!surveyActive || surveyWriter == null) return;
        if (!gpsActive && (gpsReader == null || !gpsReader.hasFix())) return;
        double lat = operatorLat, lon = operatorLon;
        String ts  = LocalDateTime.now().format(dtf);
        synchronized (surveyLock) {
            for (AP ap : seenById.values()) {
                if (System.currentTimeMillis() - ap.lastSeen > 5_000) continue;
                surveyWriter.printf("%s,%s,%s,%d,%.8f,%.8f,%d,%s%n",
                    ts, csvEscape(ap.bssid), csvEscape(ap.ssid), ap.signal,
                    lat, lon, ap.channel, csvEscape(ap.security));
                surveyReadings.incrementAndGet();
                localGeo.ingestReading(ap.bssid, ap.ssid, lat, lon, ap.signal);
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

    // ── KML builder ─────────────────────────────────────────────────────────────
    private byte[] buildKml() throws IOException {
        Map<String, List<String[]>> byBssid = new java.util.LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(surveyFile)) {
            String line; boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] p = line.split(",", -1);
                if (p.length < 6) continue;
                byBssid.computeIfAbsent(p[1], k -> new ArrayList<>()).add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n");
        sb.append("<name>BLACK ICE v2 Survey</name>\n");
        for (Map.Entry<String, List<String[]>> e : byBssid.entrySet()) {
            List<String[]> rows = e.getValue();
            String ssid = rows.get(0).length > 2 ? rows.get(0)[2] : e.getKey();
            double lat = 0, lon = 0;
            for (String[] r : rows) {
                try { lat += Double.parseDouble(r[4]); lon += Double.parseDouble(r[5]); }
                catch (Exception ignored) {}
            }
            lat /= rows.size(); lon /= rows.size();
            sb.append("<Placemark>\n");
            sb.append("<name>").append(xmlEscape(ssid)).append("</name>\n");
            sb.append("<description>BSSID: ").append(xmlEscape(e.getKey()))
              .append(" | Readings: ").append(rows.size()).append("</description>\n");
            sb.append("<Point><coordinates>").append(lon).append(",").append(lat)
              .append(",0</coordinates></Point>\n");
            sb.append("</Placemark>\n");
        }
        sb.append("</Document>\n</kml>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── GPX builder ─────────────────────────────────────────────────────────────
    private byte[] buildGpx() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"BLACK ICE v2\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("<trk><name>BLACK ICE Survey</name><trkseg>\n");
        try (BufferedReader br = Files.newBufferedReader(surveyFile)) {
            String line; boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] p = line.split(",", -1);
                if (p.length < 6) continue;
                try {
                    double lat = Double.parseDouble(p[4]);
                    double lon = Double.parseDouble(p[5]);
                    sb.append("<trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\">\n");
                    sb.append("<name>").append(xmlEscape(p[2])).append("</name>\n");
                    sb.append("<desc>BSSID:").append(xmlEscape(p[1])).append(" RSSI:").append(p[3]).append("</desc>\n");
                    sb.append("<time>").append(p[0].trim().replace(" ", "T")).append("Z</time>\n");
                    sb.append("</trkpt>\n");
                } catch (Exception ignored) {}
            }
        }
        sb.append("</trkseg></trk>\n</gpx>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx + search.length());
        if (colon < 0) return "";
        int q1 = json.indexOf("\"", colon + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf("\"", q1 + 1);
        if (q2 < 0) return "";
        return json.substring(q1 + 1, q2);
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
                default   -> { if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04x",(int)c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }

    private String safe(String s) { return s == null ? "" : s; }

    // ── Log level filter ───────────────────────────────────────────────────────
    public enum LogLevel { DEBUG, INFO, ALERT, CRITICAL }
    private volatile LogLevel logLevel = LogLevel.INFO;
    public void setLogLevel(LogLevel lvl) { this.logLevel = lvl; }
    private void log(LogLevel lvl, String prefix, String s) {
        if (lvl.ordinal() < logLevel.ordinal()) return;
        System.out.println(timestamp() + prefix + s);
    }
    public void printlnDebug(String s)       { log(LogLevel.DEBUG,    " [DEBUG]    ", s); }
    public void println(String s)            { log(LogLevel.INFO,     " ", s); }
    public void printlnAlert(String s)       { log(LogLevel.ALERT,    " [ALERT]    ", s); }
    public void printlnStrongAlert(String s) { log(LogLevel.CRITICAL, " [CRITICAL] ", s); }
    public String timestamp()                { return "[" + LocalDateTime.now().format(dtf) + "]"; }
    public void soundBell()                  { System.out.print("\007"); System.out.flush(); }

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
            {"68D79A","Ubiquiti"},    {"802AA8","Ubiquiti"},
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
            boolean ready = ouiReady.await(90, TimeUnit.SECONDS);
            if (ready) {
                println("[OUI] Database ready: " + ouiDatabase.getEntryCount() + " entries");
            } else {
                println("[OUI] Timeout waiting for IEEE download — using built-in entries only");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void signalOuiReady() {
        ouiReady.countDown();
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

    // ── FIX 10: detectOwnMac — missing return statement on error path fixed ─────
    /**
     * Detects the MAC address of the first physical network interface.
     * Returns "00:00:00:00:00:00" if no interface is found (never null,
     * never causes a compile error from a missing return statement).
     */
    private String detectOwnMac() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length == 6) {
                        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                                mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
                    }
                }
            }
        } catch (SocketException e) {
            println("[MAC] Could not detect own MAC: " + e.getMessage());
        }
        return "00:00:00:00:00:00"; // FIX 10: this return was missing in previous version
    }

    // ── Main ────────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new Rat(port).start();
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
    }

    // ── Data classes ────────────────────────────────────────────────────────────
    public static class AP {
        public String  ssid          = "<hidden>";
        public String  bssid         = "";
        public String  security      = "OPEN";
        public int     signal        = -100;
        public int     channel       = 0;
        public double  lat           = -15.3875;
        public double  lon           =  28.3228;
        public boolean positionRandom = false;
        public boolean isPositionResolved()           { return positionRandom; }
        public void    setPositionResolved(boolean v) { positionRandom = v; }
        public long    lastSeen = System.currentTimeMillis();
        public String  source   = "Unknown";
    }

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

    // ── Dashboard HTML ───────────────────────────────────────────────────────────
    private static String buildDashboardHtml() {
        return dashHtml1() + dashHtml2();
    }

    private static String dashHtml1() {
        return """
<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<title>BLACK ICE v2 // SIGINT TERMINAL</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<link href="https://fonts.googleapis.com/css2?family=Big+Shoulders+Display:wght@400;700;900&family=Courier+Prime:ital,wght@0,400;0,700;1,400&family=Share+Tech+Mono&display=swap" rel="stylesheet">
<style>
:root {
  --amber:   #ff8c00;
  --amber2:  #cc6d00;
  --amber3:  #7a3e00;
  --amber4:  #2a1500;
  --bone:    #e8dcc8;
  --bone2:   #a89880;
  --red:     #c41e0a;
  --red2:    #7a1206;
  --cyan:    #00b4cc;
  --bg:      #080604;
  --bg2:     #0e0c09;
  --bg3:     #161208;
  --rule:    #3a2800;
  --glow:    0 0 8px #ff8c0088, 0 0 24px #ff8c0033;
  --glow-r:  0 0 8px #c41e0a88, 0 0 24px #c41e0a33;
  --glow-c:  0 0 8px #00b4cc88;
}
*{ margin:0; padding:0; box-sizing:border-box; }
body { background:var(--bg); color:var(--amber); font-family:'Share Tech Mono',monospace; overflow:hidden; cursor:crosshair; }
#crt-scanlines { position:fixed; inset:0; z-index:9998; pointer-events:none; background:repeating-linear-gradient(0deg,transparent,transparent 2px,rgba(0,0,0,0.18) 2px,rgba(0,0,0,0.18) 4px); }
#crt-vignette  { position:fixed; inset:0; z-index:9997; pointer-events:none; background:radial-gradient(ellipse at 50% 50%,transparent 55%,rgba(0,0,0,0.6) 80%,rgba(0,0,0,0.95) 100%); }
#crt-sweep { position:fixed; left:0; right:0; height:3px; z-index:9996; pointer-events:none; background:linear-gradient(180deg,transparent,rgba(255,140,0,0.12) 50%,transparent); animation:sweep 8s linear infinite; }
@keyframes sweep { 0%{top:-4px;opacity:0}2%{opacity:1}98%{opacity:1}100%{top:100vh;opacity:0} }
#crt-flicker { position:fixed; inset:0; z-index:9995; pointer-events:none; animation:flicker 0.15s infinite; background:rgba(255,140,0,0.015); }
@keyframes flicker { 0%,100%{opacity:1}50%{opacity:0.94}75%{opacity:0.97} }
#noise-canvas { position:fixed; inset:0; z-index:9994; pointer-events:none; opacity:0.04; }
#map { position:fixed; inset:0; z-index:0; transition:opacity 0.4s; }
#map.hidden { opacity:0; pointer-events:none; }
#room-radar { position:fixed; inset:0; z-index:1; display:none; align-items:center; justify-content:center; background:var(--bg); }
#room-radar.active { display:flex; }
#radar-canvas { position:fixed; top:48px; left:0; }
.hdr { position:fixed; top:0; left:0; right:0; height:48px; z-index:1000; background:var(--bg2); border-bottom:2px solid var(--amber3); display:flex; align-items:stretch; box-shadow:0 2px 0 var(--amber),0 3px 20px rgba(255,140,0,0.15); }
.hdr-brand { display:flex; align-items:center; padding:0 20px; border-right:2px solid var(--amber3); gap:10px; flex-shrink:0; }
.hdr-brand-name { font-family:'Big Shoulders Display',sans-serif; font-size:20px; font-weight:900; letter-spacing:6px; color:var(--amber); text-shadow:var(--glow); line-height:1; }
.hdr-brand-sub { font-size:7px; letter-spacing:4px; color:var(--amber3); text-transform:uppercase; line-height:1; margin-top:2px; }
.hdr-stat { display:flex; flex-direction:column; justify-content:center; padding:0 16px; border-right:1px solid var(--amber3); flex-shrink:0; }
.hdr-stat-lbl { font-size:6px; letter-spacing:4px; color:var(--amber3); text-transform:uppercase; line-height:1; }
.hdr-stat-val { font-family:'Big Shoulders Display',sans-serif; font-size:22px; font-weight:700; line-height:1; color:var(--amber); text-shadow:var(--glow); }
.hdr-stat-val.danger { color:var(--red); text-shadow:var(--glow-r); }
.hdr-stat-val.cyan { color:var(--cyan); text-shadow:var(--glow-c); }
.threat-bar { display:flex; align-items:center; padding:0 16px; border-right:1px solid var(--amber3); gap:6px; flex-shrink:0; }
.threat-label { font-family:'Big Shoulders Display',sans-serif; font-size:11px; font-weight:700; letter-spacing:4px; padding:3px 10px 2px; border:1px solid currentColor; }
.threat-label.nom { color:var(--amber); box-shadow:var(--glow); }
.threat-label.elv { color:#ffcc00; box-shadow:0 0 8px #ffcc0066; }
.threat-label.crit { color:var(--red); box-shadow:var(--glow-r); animation:crit-blink 0.4s step-end infinite; }
@keyframes crit-blink { 50%{opacity:0.2} }
.hdr-gps { display:flex; align-items:center; gap:6px; padding:0 14px; border-right:1px solid var(--amber3); font-size:9px; letter-spacing:2px; color:var(--amber3); flex-shrink:0; }
.hdr-gps.live { color:var(--cyan); }
.gps-pip { width:6px; height:6px; border-radius:50%; background:var(--amber3); }
.hdr-gps.live .gps-pip { background:var(--cyan); box-shadow:var(--glow-c); animation:pip-pulse 1.2s ease-in-out infinite; }
@keyframes pip-pulse { 0%,100%{transform:scale(1);opacity:1}50%{transform:scale(1.8);opacity:0.4} }
#clock { display:flex; align-items:center; padding:0 16px; border-right:1px solid var(--amber3); font-family:'Big Shoulders Display',sans-serif; font-size:20px; font-weight:400; letter-spacing:4px; color:var(--bone2); flex-shrink:0; }
.hdr-right { margin-left:auto; display:flex; align-items:stretch; }
.conn-badge { display:flex; align-items:center; gap:5px; padding:0 14px; border-left:1px solid var(--amber3); font-size:8px; letter-spacing:3px; text-transform:uppercase; flex-shrink:0; }
.conn-badge.live { color:var(--amber); }
.conn-badge.disc { color:var(--red); }
.conn-badge.wait { color:#ffcc00; animation:crit-blink 1s step-end infinite; }
.conn-dot { width:6px; height:6px; border-radius:50%; background:currentColor; box-shadow:0 0 4px currentColor; }
.ctrl-strip { position:fixed; top:48px; right:0; width:44px; z-index:800; background:var(--bg2); border-left:2px solid var(--amber3); border-bottom:2px solid var(--amber3); display:flex; flex-direction:column; }
.ctrl-btn { width:44px; height:44px; background:transparent; border:none; border-bottom:1px solid var(--amber3); color:var(--amber3); cursor:crosshair; display:flex; align-items:center; justify-content:center; font-size:15px; transition:all 0.1s; position:relative; }
.ctrl-btn:hover,.ctrl-btn.active { background:var(--amber3); color:var(--amber); text-shadow:var(--glow); }
.ctrl-btn.alert-active { color:var(--red); background:rgba(196,30,10,0.15); animation:crit-blink 0.5s step-end infinite; }
.ctrl-btn[data-tip]:hover::after { content:attr(data-tip); position:absolute; right:48px; top:50%; transform:translateY(-50%); background:var(--bg2); border:1px solid var(--amber); color:var(--amber); font-size:8px; letter-spacing:2px; padding:3px 8px; white-space:nowrap; z-index:9999; box-shadow:var(--glow); }
#panel { position:fixed; top:48px; right:44px; bottom:52px; width:280px; background:var(--bg2); border-left:2px solid var(--amber3); display:none; flex-direction:column; z-index:700; }
#panel.open { display:flex; }
.panel-head { padding:10px 14px 8px; border-bottom:1px solid var(--amber3); font-family:'Big Shoulders Display',sans-serif; font-size:13px; font-weight:700; letter-spacing:5px; color:var(--amber); display:flex; align-items:center; justify-content:space-between; background:var(--bg3); flex-shrink:0; }
.panel-count { font-family:'Big Shoulders Display',monospace; font-size:18px; font-weight:900; color:var(--bg); background:var(--amber); padding:0 6px; line-height:1.4; }
#ap-list { flex:1; overflow-y:auto; }
#ap-list::-webkit-scrollbar { width:3px; }
#ap-list::-webkit-scrollbar-track { background:var(--bg); }
#ap-list::-webkit-scrollbar-thumb { background:var(--amber3); }
.ap-item { padding:7px 14px; border-bottom:1px solid var(--rule); cursor:crosshair; transition:background 0.08s; }
.ap-item:hover { background:rgba(255,140,0,0.06); }
.ap-item.open-net { border-left:3px solid var(--red); }
.ap-ssid { font-size:11px; color:var(--bone); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.ap-ssid.open-ssid { color:var(--red); }
.ap-meta { font-size:8px; color:var(--amber3); display:flex; gap:8px; margin-top:2px; flex-wrap:wrap; }
.ap-meta span { color:var(--amber2); }
.ap-meta .mac { color:var(--amber3); font-size:7px; }
.open-tag { color:var(--red); font-size:7px; letter-spacing:2px; animation:crit-blink 0.8s step-end infinite; }
.sig-bar { display:inline-flex; gap:1px; align-items:flex-end; height:8px; vertical-align:middle; }
.sig-bar span { display:block; width:2px; background:currentColor; opacity:0.2; }
.sig-bar span.on { opacity:1; }
.sig-bar span:nth-child(1){height:25%}.sig-bar span:nth-child(2){height:50%}.sig-bar span:nth-child(3){height:75%}.sig-bar span:nth-child(4){height:100%}
#chan-panel { position:fixed; top:48px; left:0; bottom:52px; width:240px; background:var(--bg2); border-right:2px solid var(--amber3); display:none; flex-direction:column; z-index:700; }
#chan-panel.open { display:flex; }
.chan-head { padding:10px 14px 8px; border-bottom:1px solid var(--amber3); font-family:'Big Shoulders Display',sans-serif; font-size:13px; font-weight:700; letter-spacing:5px; color:var(--cyan); background:var(--bg3); flex-shrink:0; text-shadow:var(--glow-c); }
#chan-canvas { flex:1; }
#alert-panel { position:fixed; top:48px; right:44px; bottom:52px; width:280px; background:var(--bg2); border-left:2px solid var(--red2); display:none; flex-direction:column; z-index:699; box-shadow:-4px 0 20px rgba(196,30,10,0.2); }
#alert-panel.open { display:flex; }
.alert-head { padding:10px 14px 8px; border-bottom:1px solid var(--red2); font-family:'Big Shoulders Display',sans-serif; font-size:13px; font-weight:700; letter-spacing:5px; color:var(--red); background:var(--bg3); flex-shrink:0; text-shadow:var(--glow-r); }
#alert-fired-log { flex:1; overflow-y:auto; }
.fired-row { padding:5px 14px; border-bottom:1px solid rgba(196,30,10,0.15); font-size:8px; letter-spacing:1px; color:var(--red); }
#alert-rule-list { flex-shrink:0; }
.rule-row { padding:6px 14px; border-bottom:1px solid rgba(196,30,10,0.12); font-size:8px; letter-spacing:1px; display:flex; align-items:center; gap:6px; }
.rule-type { color:#ffcc00; font-size:7px; letter-spacing:2px; }
.rule-pattern { color:var(--amber2); flex:1; overflow:hidden; text-overflow:ellipsis; }
.rule-del { font-size:9px; color:var(--red); border:1px solid var(--red2); background:transparent; padding:1px 5px; cursor:crosshair; font-family:'Share Tech Mono',monospace; flex-shrink:0; }
.rule-del:hover { background:var(--red2); }
.alert-form { padding:10px 14px; border-top:1px solid var(--red2); flex-shrink:0; background:var(--bg3); }
.alert-form-title { font-family:'Big Shoulders Display',sans-serif; font-size:9px; letter-spacing:4px; color:var(--red); margin-bottom:6px; font-weight:700; }
.a-sel,.a-inp { width:100%; background:var(--bg); color:var(--amber); border:1px solid var(--amber3); font-family:'Share Tech Mono',monospace; font-size:8px; padding:4px 6px; letter-spacing:1px; margin-bottom:4px; }
.a-sel option { background:var(--bg); }
.a-add { width:100%; font-family:'Share Tech Mono',monospace; font-size:8px; letter-spacing:3px; background:transparent; color:var(--red); border:1px solid var(--red2); padding:5px; cursor:crosshair; text-transform:uppercase; transition:all 0.1s; }
.a-add:hover { background:var(--red2); color:var(--bone); }
#tri-panel { position:fixed; top:56px; left:50%; transform:translateX(-50%); width:340px; background:var(--bg2); border:2px solid var(--cyan); z-index:2000; display:none; flex-direction:column; padding:16px; gap:10px; box-shadow:0 0 40px rgba(0,180,204,0.2); }
#tri-panel.active { display:flex; }
.tri-head { font-family:'Big Shoulders Display',sans-serif; font-size:16px; font-weight:700; letter-spacing:5px; color:var(--cyan); text-shadow:var(--glow-c); }
.tri-lbl { font-size:8px; letter-spacing:3px; color:var(--amber3); }
.tri-status { font-size:10px; color:var(--bone2); min-height:32px; line-height:1.6; }
.tri-dots { display:flex; gap:6px; }
.tri-dot { flex:1; height:4px; background:var(--rule); border:1px solid var(--amber3); transition:background 0.2s; }
.tri-dot.lit { background:var(--cyan); border-color:var(--cyan); box-shadow:var(--glow-c); }
#tri-ap-sel { background:var(--bg); color:var(--amber); border:1px solid var(--amber3); font-family:'Share Tech Mono',monospace; font-size:9px; padding:4px 8px; width:100%; }
#tri-ap-sel option { background:var(--bg); }
.tri-btns { display:flex; gap:8px; }
.tri-btn { font-family:'Share Tech Mono',monospace; font-size:8px; letter-spacing:3px; background:transparent; color:var(--cyan); border:1px solid var(--cyan); padding:6px 12px; cursor:crosshair; text-transform:uppercase; transition:all 0.1s; }
.tri-btn:hover { background:var(--cyan); color:var(--bg); }
.tri-btn.cancel { color:var(--red); border-color:var(--red2); }
.tri-btn.cancel:hover { background:var(--red2); color:var(--bone); }
#survey-legend { position:fixed; bottom:60px; right:52px; z-index:600; background:var(--bg2); border:1px solid var(--amber3); padding:10px 12px; display:none; flex-direction:column; gap:4px; font-size:8px; letter-spacing:2px; box-shadow:var(--glow); }
#survey-legend.active { display:flex; }
.survey-title { font-family:'Big Shoulders Display',sans-serif; font-size:11px; font-weight:700; letter-spacing:4px; color:#ffcc00; margin-bottom:2px; }
.survey-row { display:flex; align-items:center; gap:6px; cursor:crosshair; }
.survey-dot { width:7px; height:7px; flex-shrink:0; }
.survey-cnt-bar { display:flex; justify-content:space-between; align-items:center; gap:10px; margin-top:6px; padding-top:6px; border-top:1px solid var(--amber3); color:var(--amber3); font-size:7px; }
.sv-btn { font-size:7px; letter-spacing:2px; border:1px solid var(--amber3); background:transparent; color:var(--amber3); padding:2px 6px; cursor:crosshair; font-family:'Share Tech Mono',monospace; }
.sv-btn:hover { border-color:var(--amber); color:var(--amber); }
.sv-btn.danger { border-color:var(--red2); color:var(--red); }
.sv-btn.danger:hover { background:var(--red2); }
#radar-legend { position:fixed; bottom:60px; left:8px; z-index:600; background:var(--bg2); border:1px solid var(--amber3); padding:8px 12px; display:none; flex-direction:column; gap:4px; font-size:8px; letter-spacing:2px; color:var(--amber3); }
#radar-legend.active { display:flex; }
.rleg-title { font-family:'Big Shoulders Display',sans-serif; font-size:10px; font-weight:700; letter-spacing:4px; color:var(--amber); margin-bottom:2px; }
.rleg-row { display:flex; align-items:center; gap:7px; }
.rleg-dot { width:7px; height:7px; flex-shrink:0; }
#radar-tip { position:fixed; z-index:9990; display:none; background:var(--bg2); border:1px solid var(--amber); padding:8px 12px; font-size:9px; pointer-events:none; min-width:180px; box-shadow:var(--glow); }
.rtip-ssid { font-family:'Big Shoulders Display',sans-serif; font-size:15px; font-weight:700; letter-spacing:3px; color:var(--amber); line-height:1; margin-bottom:5px; }
#log-strip { position:fixed; bottom:0; left:0; right:0; height:52px; z-index:800; background:var(--bg2); border-top:2px solid var(--amber3); padding:5px 14px; display:flex; flex-direction:column; justify-content:flex-end; gap:1px; font-size:9px; box-shadow:0 -2px 0 var(--amber),0 -3px 20px rgba(255,140,0,0.1); }
.log-row { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; opacity:0.55; line-height:1.3; }
.log-row:last-child { opacity:1; }
.log-ts { color:var(--amber3); margin-right:6px; }
.log-tag { color:var(--amber2); margin-right:4px; }
.log-msg { color:var(--bone2); }
.log-msg.warn { color:#ffcc00; }
.log-msg.crit { color:var(--red); text-shadow:var(--glow-r); }
.log-msg.info { color:var(--cyan); }
#export-strip { position:fixed; bottom:56px; left:8px; z-index:600; display:flex; gap:4px; }
.exp-btn { font-family:'Share Tech Mono',monospace; font-size:7px; letter-spacing:3px; background:var(--bg2); color:var(--cyan); border:1px solid var(--cyan); padding:3px 8px; cursor:crosshair; text-transform:uppercase; transition:all 0.1s; }
.exp-btn:hover { background:var(--cyan); color:var(--bg); }
.bracket { position:fixed; width:20px; height:20px; z-index:9993; pointer-events:none; }
.bracket.tl { top:50px; left:1px; border-top:1px solid var(--amber2); border-left:1px solid var(--amber2); }
.bracket.tr { top:50px; right:47px; border-top:1px solid var(--amber2); border-right:1px solid var(--amber2); }
.bracket.bl { bottom:54px; left:1px; border-bottom:1px solid var(--amber2); border-left:1px solid var(--amber2); }
.bracket.br { bottom:54px; right:47px; border-bottom:1px solid var(--amber2); border-right:1px solid var(--amber2); }
.leaflet-popup-content-wrapper { background:transparent!important; border:none!important; border-radius:0!important; box-shadow:none!important; padding:0!important; }
.leaflet-popup-content { margin:0!important; padding:0!important; font-family:'Share Tech Mono',monospace!important; width:auto!important; min-width:260px!important; }
.leaflet-popup-tip-container { display:none!important; }
.leaflet-popup-close-button { color:var(--amber)!important; font-size:16px!important; top:5px!important; right:7px!important; background:transparent!important; }
.leaflet-control-attribution { display:none!important; }
.leaflet-control-zoom a { background:var(--bg2)!important; color:var(--amber)!important; border-color:var(--amber3)!important; font-family:'Share Tech Mono',monospace!important; }
#boot { position:fixed; inset:0; z-index:10000; background:var(--bg); display:flex; flex-direction:column; align-items:center; justify-content:center; transition:opacity 0.8s; }
#boot.gone { opacity:0; pointer-events:none; }
.boot-logo { font-family:'Big Shoulders Display',sans-serif; font-size:clamp(60px,10vw,120px); font-weight:900; letter-spacing:16px; color:var(--amber); text-shadow:var(--glow); animation:boot-glitch 3s infinite; }
@keyframes boot-glitch { 0%,92%,100%{clip-path:none;transform:none}93%{clip-path:inset(20% 0 50% 0);transform:translateX(-4px);filter:hue-rotate(20deg)}94%{clip-path:inset(60% 0 10% 0);transform:translateX(4px)}95%{clip-path:none;transform:none} }
.boot-sub { font-size:9px; letter-spacing:6px; color:var(--amber3); margin-top:8px; }
.boot-bar-wrap { width:360px; height:2px; background:var(--rule); border:1px solid var(--amber3); margin:24px 0 6px; overflow:hidden; }
.boot-bar-fill { height:100%; width:0%; background:var(--amber); box-shadow:0 0 6px var(--amber); transition:width 0.08s linear; }
.boot-pct { font-size:10px; letter-spacing:4px; color:var(--amber); }
.boot-log { margin-top:20px; width:480px; max-width:90vw; font-size:10px; color:var(--amber2); line-height:1.7; letter-spacing:1px; min-height:180px; }
.boot-log-line { display:flex; gap:10px; animation:fadein 0.15s ease-out; }
@keyframes fadein { from{opacity:0;transform:translateX(-6px)}to{opacity:1;transform:none} }
.bll-tag { font-size:8px; letter-spacing:2px; min-width:56px; padding:1px 0; }
.bll-tag.ok{color:var(--amber)}.bll-tag.warn{color:#ffcc00}.bll-tag.info{color:var(--cyan)}.bll-tag.sys{color:var(--amber3)}
.bll-msg { color:var(--bone2); flex:1; }
.bll-status { font-size:7px; letter-spacing:2px; padding:1px 6px; border:1px solid currentColor; flex-shrink:0; }
.bll-status.ok{color:var(--amber)}.bll-status.warn{color:#ffcc00}
.boot-granted { display:none; flex-direction:column; align-items:center; justify-content:center; gap:10px; }
.boot-granted.show { display:flex; }
.granted-text { font-family:'Big Shoulders Display',sans-serif; font-size:clamp(40px,8vw,80px); font-weight:900; letter-spacing:10px; color:var(--amber); text-shadow:var(--glow); animation:granted-in 0.5s ease-out; }
@keyframes granted-in { from{opacity:0;transform:scale(1.1);letter-spacing:30px}to{opacity:1;transform:none;letter-spacing:10px} }
.granted-node { font-size:9px; letter-spacing:4px; color:var(--amber3); }
</style>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
</head><body>
<div id="crt-scanlines"></div><div id="crt-vignette"></div><div id="crt-sweep"></div><div id="crt-flicker"></div>
<canvas id="noise-canvas"></canvas>
<div class="bracket tl"></div><div class="bracket tr"></div><div class="bracket bl"></div><div class="bracket br"></div>
<div id="map"></div>
<div id="room-radar"><canvas id="radar-canvas"></canvas></div>
<div id="radar-tip"><div class="rtip-ssid" id="rtip-ssid"></div><div id="rtip-body" style="color:var(--bone2);line-height:1.6;font-size:8px;letter-spacing:1px;"></div></div>
<div id="radar-legend">
  <div class="rleg-title">PROXIMITY</div>
  <div class="rleg-row"><div class="rleg-dot" style="background:var(--amber);box-shadow:var(--glow)"></div><span>&gt; -55 dBm — SAME ROOM</span></div>
  <div class="rleg-row"><div class="rleg-dot" style="background:#ffcc00"></div><span>-55 to -70 dBm — NEAR</span></div>
  <div class="rleg-row"><div class="rleg-dot" style="background:var(--red)"></div><span>&lt; -70 dBm — DISTANT</span></div>
  <div class="rleg-row" style="margin-top:3px;"><div class="rleg-dot" style="border:1px solid var(--red);background:transparent;"></div><span style="color:var(--red)">OPEN NETWORK</span></div>
</div>
<div id="survey-legend">
  <div class="survey-title">// WALK SURVEY</div>
  <div id="survey-ap-list"></div>
  <div class="survey-cnt-bar">
    <span id="survey-reading-lbl">0 READINGS</span>
    <div style="display:flex;gap:4px;">
      <button class="sv-btn" onclick="surveyDownload()">&#8595; CSV</button>
      <button class="sv-btn danger" onclick="surveyClear()">CLR</button>
    </div>
  </div>
</div>
<div id="export-strip">
  <button class="exp-btn" onclick="window.open('/api/v1/export/kml','_blank')">&#8659; KML</button>
  <button class="exp-btn" onclick="window.open('/api/v1/export/gpx','_blank')">&#8659; GPX</button>
</div>
<div id="export-strip">
  <button class="exp-btn" onclick="window.open('/api/v1/export/kml','_blank')">&#8659; KML</button>
  <button class="exp-btn" onclick="window.open('/api/v1/export/gpx','_blank')">&#8659; GPX</button>
  <button class="exp-btn" onclick="generateReport()">&#8659; PDF</button>
</div>
<div class="hdr">
  <div class="hdr-brand"><div><div class="hdr-brand-name">BLACK ICE</div><div class="hdr-brand-sub">SIGINT // v2 // NIGHTFALL35</div></div></div>
  <div class="hdr-stat"><div class="hdr-stat-lbl">TARGETS</div><div class="hdr-stat-val" id="cnt">000</div></div>
  <div class="hdr-stat"><div class="hdr-stat-lbl">OPEN</div><div class="hdr-stat-val danger" id="ocnt">0</div></div>
  <div class="hdr-stat"><div class="hdr-stat-lbl">LOCAL DB</div><div class="hdr-stat-val cyan" id="local-db-cnt">0</div></div>
  <div class="hdr-stat"><div class="hdr-stat-lbl">READINGS</div><div class="hdr-stat-val" style="color:#ffcc00;text-shadow:0 0 8px #ffcc0066;" id="survey-cnt">0</div></div>
  <div class="threat-bar"><div class="threat-label nom" id="threat-badge">NOMINAL</div></div>
  <div class="hdr-gps" id="gps-pill"><div class="gps-pip"></div><span id="gps-txt">GPS: SEARCHING</span></div>
  <div id="clock">00:00:00</div>
  <div class="hdr-right"><div class="conn-badge wait" id="conn-badge"><div class="conn-dot"></div><span id="conn-txt">CONNECTING</span></div></div>
</div>
<div class="ctrl-strip">
  <button class="ctrl-btn" id="btn-targets"  onclick="togglePanel()"      data-tip="TARGETS">&#9776;</button>
  <button class="ctrl-btn" id="btn-channels" onclick="toggleChanPanel()"  data-tip="CHANNELS">&#9636;</button>
  <button class="ctrl-btn" id="btn-alerts"   onclick="toggleAlertPanel()" data-tip="ALERTS">&#9888;</button>
  <button class="ctrl-btn" id="btn-tri"      onclick="toggleTriPanel()"   data-tip="TRI-FIX">&#9651;</button>
  <button class="ctrl-btn" id="btn-survey"   onclick="toggleSurvey()"     data-tip="SURVEY">&#9678;</button>
  <button class="ctrl-btn" id="btn-room"     onclick="toggleRoomMode()"   data-tip="ROOM MODE">&#9673;</button>
</div>
<div id="panel">
  <div class="panel-head"><span>// ACCESS POINTS</span><div class="panel-count" id="pcnt">0</div></div>
  <div id="ap-list"></div>
</div>
<div id="chan-panel"><div class="chan-head">// CHANNEL MAP</div><canvas id="chan-canvas"></canvas></div>
<div id="alert-panel">
  <div class="alert-head">// ALERT ENGINE</div>
  <div id="alert-fired-log"></div>
  <div id="alert-rule-list"></div>
  <div class="alert-form">
    <div class="alert-form-title">ADD RULE</div>
    <select class="a-sel" id="alert-type-sel">
      <option value="ssid_appears">SSID APPEARS</option>
      <option value="ssid_pattern">SSID MATCHES PATTERN</option>
      <option value="open_network">ANY OPEN NETWORK</option>
      <option value="ssid_disappears">SSID DISAPPEARS</option>
    </select>
    <input class="a-inp" id="alert-pattern-inp" placeholder="pattern (regex, blank=any)" />
    <button class="a-add" onclick="addAlertRule()">+ ADD RULE</button>
  </div>
</div>
<div id="tri-panel">
  <div class="tri-head">&#9651; TRILATERATION FIX</div>
  <div class="tri-lbl">SELECT TARGET AP</div>
  <select id="tri-ap-sel"><option value="">-- select AP --</option></select>
  <div class="tri-lbl">MARK PROGRESS</div>
  <div class="tri-dots"><div class="tri-dot" id="tri-d0"></div><div class="tri-dot" id="tri-d1"></div><div class="tri-dot" id="tri-d2"></div></div>
  <div class="tri-status" id="tri-status">Move to position A, select AP, then MARK.</div>
  <div class="tri-btns"><button class="tri-btn" onclick="triMark()">&#9654; MARK</button><button class="tri-btn cancel" onclick="triCancel()">&#x2715; ABORT</button></div>
</div>
<div id="log-strip">
  <div class="log-row"><span class="log-ts">[BOOT]</span><span class="log-tag">[SYS]</span><span class="log-msg">BLACK ICE v2 — INITIALIZING SIGINT TERMINAL</span></div>
  <div class="log-row"><span class="log-ts">[SYS]</span><span class="log-tag">[NET]</span><span class="log-msg warn">AWAITING 802.11 FRAME CAPTURE</span></div>
</div>
<div id="boot">
  <div class="boot-logo">BLACK ICE</div>
  <div class="boot-sub">v2 // SIGINT PLATFORM // NIGHTFALL35 // LUSAKA</div>
  <div class="boot-bar-wrap"><div class="boot-bar-fill" id="boot-bar-fill"></div></div>
  <div class="boot-pct" id="boot-pct">0%</div>
  <div class="boot-log" id="boot-log" style="display:none;"></div>
  <div class="boot-granted"><div class="granted-text">ACCESS GRANTED</div><div class="granted-node">NODE: -15.387500, 28.322800 // LUSAKA, ZM</div></div>
</div>
<script>
'use strict';
(function(){const c=document.getElementById('noise-canvas');const ctx=c.getContext('2d');function resize(){c.width=innerWidth;c.height=innerHeight;}resize();window.addEventListener('resize',resize);function drawNoise(){const img=ctx.createImageData(c.width,c.height);const d=img.data;for(let i=0;i<d.length;i+=4){const v=Math.random()*60|0;d[i]=v*1.2;d[i+1]=v*0.7;d[i+2]=0;d[i+3]=255;}ctx.putImageData(img,0,0);}drawNoise();setInterval(drawNoise,120);})();
function updateClock(){const n=new Date();document.getElementById('clock').textContent=String(n.getHours()).padStart(2,'0')+':'+String(n.getMinutes()).padStart(2,'0')+':'+String(n.getSeconds()).padStart(2,'0');}setInterval(updateClock,1000);updateClock();
const map=L.map('map',{zoomControl:true,attributionControl:false}).setView([-15.3875,28.3228],15);
const tileSources=['https://cartodb-basemaps-{s}.global.ssl.fastly.net/dark_all/{z}/{x}/{y}.png','https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png','https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'];
let tileIdx=0,activeLayer=null;
function tryNextTile(){if(activeLayer)map.removeLayer(activeLayer);if(tileIdx>=tileSources.length){addLog('MAP','ALL TILE SOURCES FAILED','crit');return;}const url=tileSources[tileIdx];activeLayer=L.tileLayer(url,{maxZoom:19,subdomains:['a','b','c','d'],attribution:''});activeLayer.on('tileerror',function(){if(!this._failed){this._failed=true;tileIdx++;tryNextTile();}});activeLayer.addTo(map);}tryNextTile();
let allAps={},panelOpen=false,chanOpen=false,alertOpen=false,roomMode=false,radarAnim=null,sweepAngle=0,surveyMode=false,surveyReadings=0,reconnects=0,gpsLocked=false,opMarker=null;
window._opLat=null;window._opLon=null;
const radarWobble={},surveyData={},heatLayers={},sigHistory={};
const SIG_HIST_MS=60_000;
const PALETTE=['#ff8c00','#00b4cc','#ffcc00','#c41e0a','#ff4da6','#8855ff','#00cc88','#ff6600'];
let paletteIdx=0;
const firedAlerts=[];
const MM={_m:{},_rings:{},_mode:'map',setMode(m){this._mode=m;},has(k){return k in this._m;},isReal(k){return this._m[k]&&!this._m[k]._ph;},flush(){Object.keys(this._m).forEach(k=>{if(!this._m[k]||this._m[k]._ph)delete this._m[k];});},add(key,ap,mapRef){const isOpen=isOpenNet(ap),strong=ap.signal>-65;const col=isOpen?'#c41e0a':strong?'#ff8c00':'#ffcc00';const r=isOpen?11:strong?8:6;if(this._mode==='room'){this._m[key]={_ph:true};return;}const m=L.circleMarker([ap.lat,ap.lon],{radius:r,color:col,fillColor:col,fillOpacity:0.75,weight:1.5}).addTo(mapRef);m.bindPopup(makePopup(ap),{maxWidth:300,minWidth:260});this._m[key]=m;if(isOpen){const ring=L.circleMarker([ap.lat,ap.lon],{radius:r+8,color:'#c41e0a',fillColor:'transparent',weight:1,opacity:0.4}).addTo(mapRef);this._rings[key]=ring;}},update(key,ap){if(!this.isReal(key))return;const isOpen=isOpenNet(ap),strong=ap.signal>-65;const col=isOpen?'#c41e0a':strong?'#ff8c00':'#ffcc00';const r=isOpen?11:strong?8:6;this._m[key].setLatLng([ap.lat,ap.lon]).setStyle({color:col,fillColor:col,radius:r});this._m[key].setPopupContent(makePopup(ap));if(this._rings[key])this._rings[key].setLatLng([ap.lat,ap.lon]);},remove(key,mapRef){if(this.isReal(key))mapRef.removeLayer(this._m[key]);delete this._m[key];if(this._rings[key]){mapRef.removeLayer(this._rings[key]);delete this._rings[key];}},flyTo(key,mapRef,ap){if(this.isReal(key))mapRef.flyTo([ap.lat,ap.lon],17,{duration:0.6});},openPopup(key){if(this.isReal(key))this._m[key].openPopup();},keys(){return Object.keys(this._m);}};
function isOpenNet(ap){const s=ap.security||'';return s.toUpperCase().includes('OPEN')||s==='';}
const logEl=document.getElementById('log-strip');
function addLog(tag,msg,type){const ts=new Date();const t=String(ts.getHours()).padStart(2,'0')+':'+String(ts.getMinutes()).padStart(2,'0')+':'+String(ts.getSeconds()).padStart(2,'0');const d=document.createElement('div');d.className='log-row';const cls=type==='crit'?'crit':type==='warn'?'warn':type==='info'?'info':'';d.innerHTML=`<span class="log-ts">[${t}]</span><span class="log-tag">[${tag}]</span><span class="log-msg ${cls}">${msg}</span>`;logEl.appendChild(d);while(logEl.children.length>3)logEl.removeChild(logEl.firstChild);}
function makePopup(ap){const isOpen=isOpenNet(ap);const isWPA3=(ap.security||'').includes('WPA3');const isWPA2=(ap.security||'').includes('WPA2')&&!isWPA3;const hidden=!ap.ssid||ap.ssid==='<hidden>';let threat=0;if(isOpen)threat+=60;if(hidden)threat+=15;if(ap.signal>-55)threat+=15;if(ap.channel===0)threat+=10;threat=Math.min(threat,100);const tc=threat>=60?'#c41e0a':threat>=30?'#ffcc00':'#ff8c00';const mc=isOpen?'#c41e0a':isWPA3?'#00b4cc':'#ff8c00';const sc=ap.signal>-55?'#ff8c00':ap.signal>-70?'#ffcc00':'#c41e0a';const sig=Math.max(0,Math.min(100,(ap.signal+100)*2));const band=ap.channel>=36?'5GHz':ap.channel>0?'2.4GHz':'---';const ssid=hidden?'[ HIDDEN ]':(ap.ssid||'---');const enc=isOpen?`<span style="color:#c41e0a;border:1px solid #c41e0a;padding:1px 6px;font-size:7px;letter-spacing:2px;">UNENCRYPTED</span>`:isWPA3?`<span style="color:#00b4cc;border:1px solid #00b4cc;padding:1px 6px;font-size:7px;letter-spacing:2px;">WPA3</span>`:isWPA2?`<span style="color:#ff8c00;border:1px solid #ff8c00;padding:1px 6px;font-size:7px;letter-spacing:2px;">WPA2</span>`:`<span style="color:#ffcc00;border:1px solid #ffcc00;padding:1px 6px;font-size:7px;letter-spacing:2px;">LEGACY</span>`;const spark=drawSparkline(ap.bssid,240,24);return `<div style="background:#0e0c09;border:1px solid ${mc}88;font-family:'Share Tech Mono',monospace;overflow:hidden;min-width:260px;"><div style="background:${mc}14;border-bottom:1px solid ${mc}44;padding:9px 12px 7px;"><div style="font-family:'Big Shoulders Display',sans-serif;font-size:18px;font-weight:700;letter-spacing:4px;color:${mc};text-shadow:0 0 8px ${mc}88;">${ssid}</div><div style="font-size:8px;color:#7a5500;letter-spacing:1px;margin-top:2px;">${ap.bssid||'---'}</div><div style="margin-top:5px;">${enc}</div></div><div style="padding:7px 12px;border-bottom:1px solid #2a1500;"><div style="display:flex;justify-content:space-between;margin-bottom:3px;"><span style="font-size:7px;color:#7a5500;letter-spacing:2px;">SIGNAL STRENGTH</span><span style="font-size:9px;color:${sc};font-family:'Big Shoulders Display',sans-serif;letter-spacing:1px;">${ap.signal} dBm</span></div><div style="height:3px;background:#0e0c09;border:1px solid #2a1500;overflow:hidden;"><div style="height:100%;width:${sig}%;background:${sc};box-shadow:0 0 4px ${sc};"></div></div><div style="margin-top:5px;">${spark}</div></div><div style="padding:6px 12px;border-bottom:1px solid ${tc}33;background:${tc}0d;"><div style="display:flex;justify-content:space-between;"><span style="font-size:7px;color:#7a5500;letter-spacing:2px;">THREAT SCORE</span><span style="font-family:'Big Shoulders Display',sans-serif;font-size:12px;color:${tc};letter-spacing:3px;">${threat>=60?'HIGH':threat>=30?'MED':'LOW'} — ${threat}</span></div><div style="height:2px;background:#0e0c09;margin-top:4px;overflow:hidden;"><div style="height:100%;width:${threat}%;background:${tc};"></div></div></div><div style="padding:7px 12px 9px;font-size:8px;line-height:1.8;"><table style="width:100%;border-collapse:collapse;"><tr><td style="color:#7a5500;padding-right:12px;letter-spacing:2px;white-space:nowrap;font-size:7px;">VENDOR</td><td style="color:#00b4cc;">${ap.vendor||'UNKNOWN'}</td></tr><tr><td style="color:#7a5500;letter-spacing:2px;font-size:7px;">CHANNEL</td><td style="color:#ff8c00;">${ap.channel>0?ap.channel:'?'} <span style="color:#7a5500;">${band}</span></td></tr><tr><td style="color:#7a5500;letter-spacing:2px;font-size:7px;">ENCRYPT</td><td style="color:${mc};">${ap.security||'NONE'}</td></tr><tr><td style="color:#7a5500;letter-spacing:2px;font-size:7px;">SOURCE</td><td style="color:#a89880;">${ap.source||'---'}</td></tr><tr><td style="color:#7a5500;letter-spacing:2px;font-size:7px;">COORDS</td><td style="color:#7a5500;font-size:7px;">${ap.lat?ap.lat.toFixed(5):'---'}, ${ap.lon?ap.lon.toFixed(5):'---'}</td></tr></table></div><div style="border-top:1px solid #2a1500;padding:4px 12px;display:flex;justify-content:space-between;background:#080604;"><span style="font-size:7px;color:#3a2800;letter-spacing:3px;">BLACK ICE v2</span><span style="font-size:7px;color:${mc}88;letter-spacing:2px;">NIGHTFALL35</span></div></div>`;}
function updateSigHistory(aps){const now=Date.now();for(const[k,ap]of Object.entries(aps)){if(!sigHistory[k])sigHistory[k]=[];sigHistory[k].push({t:now,rssi:ap.signal});sigHistory[k]=sigHistory[k].filter(e=>now-e.t<SIG_HIST_MS);}}
function drawSparkline(bssid,w,h){const hist=sigHistory[bssid];if(!hist||hist.length<2)return'';const now=Date.now();const minR=-95,maxR=-20;const pts=hist.map(e=>{const x=(Math.max(0,e.t-(now-SIG_HIST_MS))/SIG_HIST_MS)*w;const y=h-((e.rssi-minR)/(maxR-minR))*h;return x.toFixed(1)+','+y.toFixed(1);}).join(' ');const last=hist[hist.length-1].rssi;const col=last>-55?'#ff8c00':last>-70?'#ffcc00':'#c41e0a';return `<svg width="${w}" height="${h}" style="display:block;"><polyline points="${pts}" fill="none" stroke="${col}" stroke-width="1.2" opacity="0.8"/><line x1="0" y1="${h/2}" x2="${w}" y2="${h/2}" stroke="#3a280066" stroke-width="1" stroke-dasharray="2,4"/></svg>`;}
function sigBars(dbm,col){const lvl=dbm>-55?4:dbm>-65?3:dbm>-75?2:1;let h=`<span class="sig-bar" style="color:${col}">`;for(let i=1;i<=4;i++)h+=`<span${i<=lvl?' class="on"':''}></span>`;return h+'</span>';}
function updateDisplay(aps){allAps=aps;const total=Object.keys(aps).length;document.getElementById('cnt').textContent=String(total).padStart(3,'0');const openCount=Object.values(aps).filter(a=>isOpenNet(a)).length;document.getElementById('ocnt').textContent=openCount;const b=document.getElementById('threat-badge');if(openCount===0){b.textContent='NOMINAL';b.className='threat-label nom';}else if(openCount<3){b.textContent='ELEVATED';b.className='threat-label elv';}else{b.textContent='!! CRITICAL';b.className='threat-label crit';}updateSigHistory(aps);if(panelOpen)renderPanel();if(chanOpen)drawChannelChart(aps);if(!roomMode){MM.flush();for(const[k,ap]of Object.entries(aps)){if(MM.isReal(k))MM.update(k,ap);else{addLog('SIG',(ap.ssid||'<HIDDEN>')+' '+ap.signal+'dBm',isOpenNet(ap)?'crit':'info');MM.add(k,ap,map);}}for(const k of MM.keys()){if(!aps[k])MM.remove(k,map);}}else{for(const[k,ap]of Object.entries(aps)){if(!MM.has(k)){addLog('SIG',(ap.ssid||'<HIDDEN>')+' '+ap.signal+'dBm',isOpenNet(ap)?'crit':'info');MM.add(k,ap,map);}}}}
function togglePanel(){panelOpen=!panelOpen;document.getElementById('panel').classList.toggle('open',panelOpen);document.getElementById('btn-targets').classList.toggle('active',panelOpen);if(panelOpen)renderPanel();}
function renderPanel(){const list=document.getElementById('ap-list');const entries=Object.entries(allAps).sort((a,b)=>b[1].signal-a[1].signal);document.getElementById('pcnt').textContent=entries.length;list.innerHTML='';for(const[k,ap]of entries){const isOpen=isOpenNet(ap);const sc=ap.signal>-65?'var(--amber)':ap.signal>-80?'#ffcc00':'var(--red)';const d=document.createElement('div');d.className='ap-item'+(isOpen?' open-net':'');d.innerHTML=`<div class="ap-ssid${isOpen?' open-ssid':''}">${isOpen?'&#9888; ':''}${ap.ssid||'&lt;HIDDEN&gt;'}${isOpen?' <span class="open-tag">[OPEN]</span>':''}</div><div class="ap-meta"><span class="mac">${ap.bssid}</span><span>${sigBars(ap.signal,sc)} ${ap.signal}dBm</span><span>CH${ap.channel}</span><span>${ap.vendor||'?'}</span></div>`;d.onclick=()=>{if(!roomMode){MM.flyTo(k,map,ap);MM.openPopup(k);}};list.appendChild(d);}}
function toggleChanPanel(){chanOpen=!chanOpen;document.getElementById('chan-panel').classList.toggle('open',chanOpen);document.getElementById('btn-channels').classList.toggle('active',chanOpen);if(chanOpen)drawChannelChart(allAps);}
function drawChannelChart(aps){const canvas=document.getElementById('chan-canvas');const ctx=canvas.getContext('2d');const W=canvas.offsetWidth||224,H=canvas.offsetHeight||300;canvas.width=W;canvas.height=H;ctx.fillStyle='#080604';ctx.fillRect(0,0,W,H);const counts={};for(const ap of Object.values(aps)){const ch=ap.channel||0;counts[ch]=(counts[ch]||0)+1;}const ch24=[1,2,3,4,5,6,7,8,9,10,11,12,13,14].map(c=>({ch:c,n:counts[c]||0}));const ch5=[36,40,44,48,52,56,60,64,100,104,108,112,116,120,124,128,132,136,140,149,153,157,161,165].map(c=>({ch:c,n:counts[c]||0}));const maxN=Math.max(1,...Object.values(counts));function section(chs,sy,label,col){ctx.font='700 8px Big Shoulders Display,monospace';ctx.fillStyle=col;ctx.fillText(label,6,sy-4);const bw=Math.floor((W-20)/chs.length)-1;chs.forEach((c,i)=>{const x=10+i*(bw+1),bh=c.n>0?Math.max(3,Math.floor((c.n/maxN)*55)):0,y=sy+58-bh;if(c.n>0){const g=ctx.createLinearGradient(0,y,0,sy+58);g.addColorStop(0,col);g.addColorStop(1,col+'44');ctx.fillStyle=g;ctx.fillRect(x,y,bw,bh);ctx.shadowColor=col;ctx.shadowBlur=3;ctx.fillRect(x,y,bw,1);ctx.shadowBlur=0;}else{ctx.fillStyle='#2a1500';ctx.fillRect(x,sy+52,bw,5);}if(bw>=7){ctx.font='6px Share Tech Mono,monospace';ctx.fillStyle=c.n>0?col:'#3a2800';ctx.fillText(c.ch,x+bw/2-(c.ch>9?4:2),sy+70);}if(c.n>0){ctx.font='700 7px Share Tech Mono,monospace';ctx.fillStyle=col;ctx.fillText(c.n,x+bw/2-3,y-2);}});}section(ch24,18,'2.4 GHz','#ff8c00');section(ch5,118,'5 GHz','#00b4cc');const ov=ch24.filter(c=>c.n>0&&![1,6,11].includes(c.ch));if(ov.length>0){ctx.font='7px Share Tech Mono,monospace';ctx.fillStyle='#c41e0a';ctx.fillText('\\u26a0 CH OVERLAP: '+ov.map(c=>c.ch).join(','),6,104);}ctx.font='8px Share Tech Mono,monospace';ctx.fillStyle='#7a5500';ctx.fillText('TOTAL: '+Object.values(aps).length+' APs',6,H-6);}
function makeOpIcon(gps){const col=gps?'#00b4cc':'#ff8c00';return L.divIcon({html:`<div style="width:14px;height:14px;border:2px solid ${col};background:${col}22;box-shadow:0 0 10px ${col}88;animation:pip-pulse 1.2s infinite;position:relative;"><div style="position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:4px;height:4px;background:${col};"></div></div>`,iconSize:[14,14],iconAnchor:[7,7],className:''});}
function updateOpMarker(lat,lon,gps){if(!opMarker){opMarker=L.marker([lat,lon],{icon:makeOpIcon(gps),zIndexOffset:9000}).addTo(map);opMarker.bindPopup(`<div style="font-family:'Share Tech Mono',monospace;background:#0e0c09;border:1px solid #ff8c00;padding:8px;font-size:9px;color:#ff8c00;letter-spacing:2px;">OPERATOR<br><span style="color:#7a5500;font-size:8px;">${gps?'HARDWARE GPS':'BROWSER'}</span><br><span style="font-size:7px;color:#3a2800;">${lat.toFixed(6)}, ${lon.toFixed(6)}</span></div>`);}else{opMarker.setLatLng([lat,lon]);opMarker.setIcon(makeOpIcon(gps));}window._opLat=lat;window._opLon=lon;}
if(navigator.geolocation){navigator.geolocation.watchPosition(pos=>{if(gpsLocked)return;const{latitude:lat,longitude:lon}=pos.coords;window._opLat=lat;window._opLon=lon;map.setView([lat,lon],16);updateOpMarker(lat,lon,false);addLog('GPS','BROWSER LOC: '+lat.toFixed(4)+','+lon.toFixed(4),'info');},()=>addLog('GPS','BROWSER GPS UNAVAILABLE','warn'),{enableHighAccuracy:true,maximumAge:5000});}
function setConn(s){const el=document.getElementById('conn-badge');const txt=document.getElementById('conn-txt');el.className='conn-badge '+s;txt.textContent=s==='live'?'LIVE':s==='disc'?'OFFLINE':'CONNECTING';}
let evt=null;
function connect(){if(evt)evt.close();setConn('wait');evt=new EventSource('/sse');evt.onopen=()=>{setConn('live');reconnects=0;addLog('SSE','STREAM ESTABLISHED','info');};evt.onmessage=e=>{try{const d=JSON.parse(e.data);if(d.type==='alert'){handleAlertEvent(d);return;}if(d.type==='full'){if(d.operator)updateOp(d.operator);updateDisplay(d.aps);if(d.geo)document.getElementById('local-db-cnt').textContent=d.geo.localBssids;if(d.survey){const op=d.operator||{};ingestSurvey(d.aps,op.lat,op.lon,d.survey.readings);if(d.survey.active&&!surveyMode){document.getElementById('btn-survey').classList.add('active');document.getElementById('survey-legend').classList.add('active');surveyMode=true;}}}}catch(err){addLog('ERR','PARSE: '+err.message,'crit');}};evt.onerror=()=>{setConn('disc');evt.close();if(reconnects<10){reconnects++;addLog('SSE','RECONNECT '+reconnects+'/10...','warn');setTimeout(connect,2000+reconnects*500);}else addLog('SSE','STREAM DEAD — MAX RETRIES','crit');};}
connect();setInterval(()=>{if(evt&&evt.readyState===EventSource.CLOSED)connect();},30000);
function updateOp(op){if(!op)return;if(op.gps&&!gpsLocked){gpsLocked=true;addLog('GPS','HARDWARE LOCK: '+op.lat.toFixed(5)+','+op.lon.toFixed(5),'info');const pill=document.getElementById('gps-pill');pill.className='hdr-gps live';document.getElementById('gps-txt').textContent='GPS: LOCKED';}if(op.gps||!opMarker){updateOpMarker(op.lat,op.lon,op.gps);if(op.gps)map.panTo([op.lat,op.lon],{animate:true,duration:0.5});}}
""";
    }

    private static String dashHtml2() {
        return """
function toggleRoomMode(){roomMode=!roomMode;document.getElementById('room-radar').classList.toggle('active',roomMode);document.getElementById('map').classList.toggle('hidden',roomMode);document.getElementById('radar-legend').classList.toggle('active',roomMode);document.getElementById('btn-room').classList.toggle('active',roomMode);MM.setMode(roomMode?'room':'map');if(roomMode){addLog('MODE','ROOM RADAR ENGAGED','info');startRadar();}else{if(radarAnim){cancelAnimationFrame(radarAnim);radarAnim=null;}addLog('MODE','MAP MODE RESTORED','info');}}
function startRadar(){const canvas=document.getElementById('radar-canvas');const ctx=canvas.getContext('2d');function resize(){canvas.width=window.innerWidth;canvas.height=window.innerHeight-48-52;canvas.style.top='48px';}resize();window.addEventListener('resize',resize);const blobPos={};function sigToR(dbm){const t=(Math.max(-95,Math.min(-25,dbm))-(-25))/((-95)-(-25));const R=Math.min(canvas.width,canvas.height)*0.42;return R*(0.12+t*0.82);}function frame(){if(!roomMode)return;radarAnim=requestAnimationFrame(frame);const W=canvas.width,H=canvas.height,X=W/2,Y=H/2,R=Math.min(W,H)*0.42;ctx.clearRect(0,0,W,H);const bg=ctx.createRadialGradient(X,Y,0,X,Y,R*1.15);bg.addColorStop(0,'#0e0a04');bg.addColorStop(1,'#060402');ctx.fillStyle=bg;ctx.fillRect(0,0,W,H);[[0.15,'-35',0.5],[0.35,'-50',0.3],[0.55,'-65',0.2],[0.75,'-75',0.14],[0.95,'-90',0.1]].forEach(([t,lbl,a])=>{const r=R*t;ctx.beginPath();ctx.arc(X,Y,r,0,Math.PI*2);ctx.strokeStyle=`rgba(255,140,0,${a})`;ctx.lineWidth=1;ctx.setLineDash([3,6]);ctx.stroke();ctx.setLineDash([]);ctx.fillStyle=`rgba(255,140,0,${a*0.8})`;ctx.font='8px Share Tech Mono,monospace';ctx.fillText(lbl+' dBm',X+r+4,Y-4);});ctx.strokeStyle='rgba(255,140,0,0.1)';ctx.lineWidth=1;ctx.setLineDash([2,8]);ctx.beginPath();ctx.moveTo(X,Y-R*1.05);ctx.lineTo(X,Y+R*1.05);ctx.stroke();ctx.beginPath();ctx.moveTo(X-R*1.05,Y);ctx.lineTo(X+R*1.05,Y);ctx.stroke();ctx.setLineDash([]);ctx.beginPath();ctx.arc(X,Y,R,0,Math.PI*2);ctx.strokeStyle='rgba(255,140,0,0.25)';ctx.lineWidth=1.5;ctx.stroke();sweepAngle=(sweepAngle+0.016)%(Math.PI*2);const sw=Math.PI/7;ctx.beginPath();ctx.moveTo(X,Y);ctx.arc(X,Y,R,sweepAngle-sw,sweepAngle);ctx.closePath();const sg=ctx.createRadialGradient(X,Y,0,X,Y,R);sg.addColorStop(0,'rgba(255,140,0,0)');sg.addColorStop(0.6,'rgba(255,140,0,0.04)');sg.addColorStop(1,'rgba(255,140,0,0.1)');ctx.fillStyle=sg;ctx.fill();ctx.beginPath();ctx.moveTo(X,Y);ctx.lineTo(X+R*Math.cos(sweepAngle),Y+R*Math.sin(sweepAngle));ctx.strokeStyle='rgba(255,140,0,0.6)';ctx.lineWidth=1.2;ctx.stroke();for(const[key,ap]of Object.entries(allAps)){if(!radarWobble[key])radarWobble[key]={a:Math.random()*Math.PI*2,d:(Math.random()-0.5)*0.003};radarWobble[key].a+=radarWobble[key].d;const dist=sigToR(ap.signal);const bx=X+dist*Math.cos(radarWobble[key].a);const by=Y+dist*Math.sin(radarWobble[key].a);blobPos[key]={x:bx,y:by,ap};const isOpen=isOpenNet(ap);const col=ap.signal>-55?'#ff8c00':ap.signal>-70?'#ffcc00':'#c41e0a';const rad=ap.signal>-55?8:ap.signal>-70?6:5;if(isOpen){ctx.beginPath();ctx.arc(bx,by,rad+8,0,Math.PI*2);ctx.strokeStyle='rgba(196,30,10,0.5)';ctx.lineWidth=1;ctx.setLineDash([2,3]);ctx.stroke();ctx.setLineDash([]);}const halo=ctx.createRadialGradient(bx,by,0,bx,by,rad*3);halo.addColorStop(0,col+'aa');halo.addColorStop(1,col+'00');ctx.beginPath();ctx.arc(bx,by,rad*3,0,Math.PI*2);ctx.fillStyle=halo;ctx.fill();ctx.beginPath();ctx.arc(bx,by,rad,0,Math.PI*2);ctx.fillStyle=col;ctx.fill();const label=ap.ssid&&ap.ssid!=='<hidden>'?ap.ssid:'< HIDDEN >';ctx.font='9px Share Tech Mono,monospace';ctx.fillStyle=col;ctx.globalAlpha=0.8;ctx.shadowColor='#080604';ctx.shadowBlur=5;ctx.fillText(label,bx+rad+4,by-3);ctx.fillStyle='rgba(255,140,0,0.4)';ctx.font='7px Share Tech Mono,monospace';ctx.fillText(ap.signal+'dBm',bx+rad+4,by+9);ctx.shadowBlur=0;ctx.globalAlpha=1;}ctx.strokeStyle='#00b4cc';ctx.lineWidth=1.5;ctx.beginPath();ctx.arc(X,Y,5,0,Math.PI*2);ctx.stroke();ctx.beginPath();ctx.moveTo(X-10,Y);ctx.lineTo(X+10,Y);ctx.stroke();ctx.beginPath();ctx.moveTo(X,Y-10);ctx.lineTo(X,Y+10);ctx.stroke();ctx.fillStyle='#00b4cc';ctx.beginPath();ctx.arc(X,Y,2,0,Math.PI*2);ctx.fill();ctx.font='700 10px Big Shoulders Display,monospace';ctx.fillStyle='rgba(255,140,0,0.3)';ctx.globalAlpha=1;ctx.fillText('PROXIMITY RADAR — ROOM MODE',X-155,22);canvas._blobPos=blobPos;}frame();const tip=document.getElementById('radar-tip');canvas.addEventListener('mousemove',e=>{if(!roomMode)return;const r=canvas.getBoundingClientRect();const mx=e.clientX-r.left,my=e.clientY-r.top;let hit=null;for(const pos of Object.values(canvas._blobPos||{})){if(Math.hypot(mx-pos.x,my-pos.y)<16){hit=pos.ap;break;}}if(hit){const isOpen=isOpenNet(hit);const col=hit.signal>-55?'#ff8c00':hit.signal>-70?'#ffcc00':'#c41e0a';const prox=hit.signal>-55?'SAME ROOM':hit.signal>-65?'VERY CLOSE':hit.signal>-75?'NEARBY':'DISTANT';document.getElementById('rtip-ssid').textContent=hit.ssid||'<HIDDEN>';document.getElementById('rtip-ssid').style.color=isOpen?'#c41e0a':col;document.getElementById('rtip-body').innerHTML=`<span style="color:#7a5500">BSSID</span>  ${hit.bssid}<br><span style="color:#7a5500">SIG</span>    <span style="color:${col}">${hit.signal} dBm — ${prox}</span><br><span style="color:#7a5500">CH</span>     ${hit.channel}<br><span style="color:#7a5500">ENC</span>    <span style="color:${isOpen?'#c41e0a':'#ff8c00'}">${hit.security||'OPEN'}</span>`;tip.style.display='block';let tx=e.clientX+16,ty=e.clientY-8;if(tx+200>window.innerWidth)tx=e.clientX-210;tip.style.left=tx+'px';tip.style.top=ty+'px';}else{tip.style.display='none';}});canvas.addEventListener('mouseleave',()=>{tip.style.display='none';});}
function toggleSurvey(){surveyMode=!surveyMode;const btn=document.getElementById('btn-survey');const leg=document.getElementById('survey-legend');if(surveyMode){btn.classList.add('active');leg.classList.add('active');fetch('/api/v1/survey',{method:'POST',body:'start'});addLog('SURVEY','WALK SURVEY STARTED','warn');}else{btn.classList.remove('active');fetch('/api/v1/survey',{method:'POST',body:'stop'});addLog('SURVEY','SURVEY PAUSED — '+surveyReadings+' READINGS','info');}}
function surveyDownload(){window.open('/api/v1/export/csv','_blank');}
function surveyClear(){fetch('/api/v1/survey',{method:'POST',body:'clear'}).then(()=>{Object.values(heatLayers).forEach(arr=>arr.forEach(l=>map.removeLayer(l)));Object.keys(heatLayers).forEach(k=>delete heatLayers[k]);Object.keys(surveyData).forEach(k=>{surveyData[k].readings=[];});surveyReadings=0;document.getElementById('survey-cnt').textContent='0';document.getElementById('survey-reading-lbl').textContent='0 READINGS';renderSurveyLegend();addLog('SURVEY','DATA CLEARED','warn');});}
function ingestSurvey(aps,opLat,opLon,serverCount){document.getElementById('survey-cnt').textContent=serverCount;document.getElementById('survey-reading-lbl').textContent=serverCount+' READINGS';surveyReadings=serverCount;if(!surveyMode||!opLat||!opLon)return;for(const[k,ap]of Object.entries(aps)){if(!surveyData[k])surveyData[k]={ssid:ap.ssid||k,readings:[],color:PALETTE[paletteIdx++%PALETTE.length],visible:true};surveyData[k].ssid=ap.ssid||k;const last=surveyData[k].readings[surveyData[k].readings.length-1];if(last&&Math.hypot(opLat-last.lat,opLon-last.lon)<0.00003)continue;surveyData[k].readings.push({lat:opLat,lon:opLon,rssi:ap.signal});drawHeatPt(k,opLat,opLon,ap.signal);}renderSurveyLegend();}
function drawHeatPt(bssid,lat,lon,rssi){if(!surveyData[bssid]||!surveyData[bssid].visible)return;const col=surveyData[bssid].color;const alpha=rssi>-55?0.7:rssi>-70?0.5:0.3;const r=rssi>-55?20:rssi>-70?14:9;const c=L.circleMarker([lat,lon],{radius:r,color:col,fillColor:col,fillOpacity:alpha,weight:0}).addTo(map);c.bindTooltip(`<span style="font-family:'Share Tech Mono';font-size:9px;color:${col}">${surveyData[bssid].ssid}<br>${rssi} dBm</span>`,{sticky:true});if(!heatLayers[bssid])heatLayers[bssid]=[];heatLayers[bssid].push(c);}
function renderSurveyLegend(){const cont=document.getElementById('survey-ap-list');cont.innerHTML='';for(const[bssid,d]of Object.entries(surveyData)){if(d.readings.length===0)continue;const row=document.createElement('div');row.className='survey-row';row.style.color=d.visible?d.color:'#3a2800';row.innerHTML=`<div class="survey-dot" style="background:${d.visible?d.color:'#3a2800'};border:1px solid ${d.color};"></div><span style="flex:1;">${d.ssid!=='<hidden>'?d.ssid:bssid.slice(-8)}</span><span style="color:#3a2800;">${d.readings.length}</span>`;row.onclick=()=>{d.visible=!d.visible;(heatLayers[bssid]||[]).forEach(l=>{if(d.visible)map.addLayer(l);else map.removeLayer(l);});renderSurveyLegend();};cont.appendChild(row);}}
function toggleAlertPanel(){alertOpen=!alertOpen;document.getElementById('alert-panel').classList.toggle('open',alertOpen);document.getElementById('btn-alerts').classList.toggle('active',alertOpen);if(alertOpen)loadAlertRules();}
function loadAlertRules(){fetch('/api/v1/alerts').then(r=>r.json()).then(rules=>renderAlertRules(rules)).catch(()=>{});}
function renderAlertRules(rules){const el=document.getElementById('alert-rule-list');el.innerHTML='';if(!rules.length){el.innerHTML='<div style="padding:8px 14px;font-size:8px;color:#3a2800;letter-spacing:2px;">NO RULES</div>';return;}rules.forEach(r=>{const row=document.createElement('div');row.className='rule-row';row.innerHTML=`<div style="flex:1;"><div class="rule-type">${r.type.toUpperCase()}</div><div class="rule-pattern">${r.pattern||'(any)'}</div></div><button class="rule-del" onclick="deleteAlertRule('${r.id}')">&#x2715;</button>`;el.appendChild(row);});}
function addAlertRule(){const type=document.getElementById('alert-type-sel').value;const pattern=document.getElementById('alert-pattern-inp').value.trim();fetch('/api/v1/alerts',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({type,pattern})}).then(()=>{loadAlertRules();document.getElementById('alert-pattern-inp').value='';addLog('ALERT','RULE ADDED: '+type,'warn');});}
function deleteAlertRule(id){fetch('/api/v1/alerts/'+id,{method:'DELETE'}).then(()=>loadAlertRules());}
function handleAlertEvent(d){const msg='['+d.rule.type.toUpperCase()+'] '+(d.ssid||'?')+' '+d.bssid;firedAlerts.unshift({msg,t:new Date().toLocaleTimeString()});if(firedAlerts.length>20)firedAlerts.pop();renderFiredAlerts();addLog('ALERT',msg,'crit');const btn=document.getElementById('btn-alerts');btn.classList.add('alert-active');setTimeout(()=>btn.classList.remove('alert-active'),2000);}
function renderFiredAlerts(){const el=document.getElementById('alert-fired-log');el.innerHTML='';firedAlerts.forEach(a=>{const row=document.createElement('div');row.className='fired-row';row.textContent='['+a.t+'] '+a.msg;el.appendChild(row);});}
let triActive=false;const triPts=[];let triBssid=null,triMarker=null;
function toggleTriPanel(){triActive=!triActive;document.getElementById('tri-panel').classList.toggle('active',triActive);document.getElementById('btn-tri').classList.toggle('active',triActive);if(triActive){triPts.length=0;triBssid=null;updateTriSel();updateTriStatus();addLog('TRI','WIZARD OPEN — MARK 3 POSITIONS','info');}}
function triCancel(){triActive=false;triPts.length=0;document.getElementById('tri-panel').classList.remove('active');document.getElementById('btn-tri').classList.remove('active');addLog('TRI','ABORTED','warn');}
function updateTriSel(){const sel=document.getElementById('tri-ap-sel');sel.innerHTML='<option value="">-- select AP --</option>';Object.entries(allAps).sort((a,b)=>b[1].signal-a[1].signal).forEach(([k,ap])=>{const o=document.createElement('option');o.value=k;o.textContent=(ap.ssid||'<HIDDEN>')+' ['+ap.signal+'dBm]';sel.appendChild(o);});}
function updateTriStatus(){for(let i=0;i<3;i++)document.getElementById('tri-d'+i).className='tri-dot'+(i<triPts.length?' lit':'');const steps=['Move to position A. Select AP. Click MARK.','Move to position B (\\u22653m away). Click MARK.','Move to position C. Click MARK to compute fix.','Computing trilateration...'];document.getElementById('tri-status').textContent=steps[Math.min(triPts.length,3)];}
function triMark(){const sel=document.getElementById('tri-ap-sel');if(!sel.value){addLog('TRI','SELECT AP FIRST','crit');return;}triBssid=sel.value;const ap=allAps[triBssid];if(!ap){addLog('TRI','AP NOT IN SCAN','crit');return;}if(!window._opLat){addLog('TRI','NO GPS FIX','crit');return;}triPts.push({lat:window._opLat,lon:window._opLon,rssi:ap.signal});addLog('TRI','PT '+'ABC'[triPts.length-1]+' @ '+window._opLat.toFixed(4)+','+window._opLon.toFixed(4),'info');if(triPts.length===3)computeTri(ap);else updateTriStatus();}
function computeTri(ap){updateTriStatus();const TX=-30,N=2.8;const pts=triPts.map(p=>({lat:p.lat,lon:p.lon,d:Math.pow(10,(TX-p.rssi)/(10*N))}));const cLat=(pts[0].lat+pts[1].lat+pts[2].lat)/3,cLon=(pts[0].lon+pts[1].lon+pts[2].lon)/3;const toXY=p=>({x:(p.lon-cLon)*111320*Math.cos(cLat*Math.PI/180),y:(p.lat-cLat)*111320,d:p.d});const[A,B,C]=pts.map(toXY);const bx=2*(B.x-A.x),by=2*(B.y-A.y),cx=2*(C.x-A.x),cy=2*(C.y-A.y);const br=A.d*A.d-B.d*B.d-A.x*A.x+B.x*B.x-A.y*A.y+B.y*B.y;const cr=A.d*A.d-C.d*C.d-A.x*A.x+C.x*C.x-A.y*A.y+C.y*C.y;const det=bx*cy-by*cx;if(Math.abs(det)<1e-10){document.getElementById('tri-status').textContent='COLLINEAR — REPOSITION';triPts.length=0;updateTriStatus();return;}const estX=(br*cy-cr*by)/det,estY=(bx*cr-cx*br)/det;const estLat=cLat+estY/111320,estLon=cLon+estX/(111320*Math.cos(cLat*Math.PI/180));if(triMarker)map.removeLayer(triMarker);const col='#c41e0a';triMarker=L.circleMarker([estLat,estLon],{radius:12,color:col,fillColor:col,fillOpacity:0.4,weight:2}).addTo(map);triMarker.bindPopup(`<div style="background:#0e0c09;border:1px solid #c41e0a;padding:10px;font-family:'Share Tech Mono';font-size:9px;color:#ff8c00;min-width:200px;"><div style="font-family:'Big Shoulders Display';font-size:14px;font-weight:700;letter-spacing:4px;color:#c41e0a;margin-bottom:6px;">TRILATERATION FIX</div>${ap.ssid||'HIDDEN'}<br>EST: ${estLat.toFixed(6)}, ${estLon.toFixed(6)}<br><span style="color:#3a2800;font-size:8px;">3-point RSSI method — accuracy varies</span></div>`).openPopup();map.setView([estLat,estLon],17);document.getElementById('tri-status').textContent='FIX: '+estLat.toFixed(5)+', '+estLon.toFixed(5);addLog('TRI','FIX \\u2192 '+estLat.toFixed(4)+','+estLon.toFixed(4),'info');triPts.length=0;triActive=false;document.getElementById('tri-panel').classList.remove('active');document.getElementById('btn-tri').classList.remove('active');}
(async()=>{const sleep=ms=>new Promise(r=>setTimeout(r,ms));const boot=document.getElementById('boot');const fill=document.getElementById('boot-bar-fill');const pct=document.getElementById('boot-pct');const log=document.getElementById('boot-log');for(let p=0;p<=100;p++){fill.style.width=p+'%';pct.textContent=p+'%';await sleep(12);}await sleep(300);log.style.display='block';const lines=[{tag:'SYS',cls:'sys',msg:'BLACK ICE v2 — SIGINT SURVEILLANCE PLATFORM',st:'',d:60},{tag:'SYS',cls:'sys',msg:'NIGHTFALL35 RESEARCH DIV // LUSAKA NODE',st:'',d:40},{tag:'INIT',cls:'ok',msg:'IEEE OUI DATABASE LOADED (38,847 entries)',st:'ok',d:100},{tag:'PCAP',cls:'ok',msg:'NPCAP 1.79 — MONITOR MODE DRIVER ARMED',st:'ok',d:120},{tag:'GPS',cls:'warn',msg:'COM3 9600 BAUD — SEEKING NMEA LOCK...',st:'warn',d:200},{tag:'HTTP',cls:'ok',msg:'ENTERPRISE API SERVER ON PORT 8080',st:'ok',d:80},{tag:'SSE',cls:'ok',msg:'EVENT STREAM ARMED (1Hz BROADCAST)',st:'ok',d:70},{tag:'GEO',cls:'ok',msg:'WIGLE GEOLOCATION ENGINE READY',st:'ok',d:80},{tag:'AI',cls:'ok',msg:'SWARM INTELLIGENCE MODULE ARMED',st:'ok',d:70},{tag:'AUTH',cls:'ok',msg:'BEARER TOKEN AUTH ACTIVE — SEE CONSOLE',st:'ok',d:80},{tag:'RADAR',cls:'ok',msg:'ROOM MODE PROXIMITY RADAR READY',st:'ok',d:60},{tag:'SURV',cls:'ok',msg:'WALK SURVEY / WARDRIVING ENGINE ARMED',st:'ok',d:60},{tag:'TRI',cls:'ok',msg:'TRILATERATION ENGINE READY — 3-POINT FIX',st:'ok',d:60},{tag:'ALERT',cls:'ok',msg:'RULES ENGINE ARMED — ssid_disappears FIRES',st:'ok',d:60},{tag:'SYS',cls:'ok',msg:'ALL SYSTEMS NOMINAL — ENTERING SURVEILLANCE',st:'ok',d:80},];for(const l of lines){const row=document.createElement('div');row.className='boot-log-line';const sc=l.cls==='ok'?'ok':l.cls==='warn'?'warn':l.cls==='info'?'info':'sys';const st=l.st?`<span class="bll-status ${l.st}">${l.st.toUpperCase()}</span>`:'';row.innerHTML=`<span class="bll-tag ${sc}">[${l.tag}]</span><span class="bll-msg">${l.msg}</span>${st}`;log.appendChild(row);log.scrollTop=log.scrollHeight;await sleep(l.d);}await sleep(500);log.style.display='none';document.querySelector('.boot-logo').style.display='none';document.querySelector('.boot-sub').style.display='none';document.querySelector('.boot-bar-wrap').style.display='none';document.querySelector('.boot-pct').style.display='none';const granted=document.querySelector('.boot-granted');granted.classList.add('show');await sleep(1000);boot.classList.add('gone');await sleep(800);boot.style.display='none';})();
function generateReport() {
  const site     = prompt('Site name:', 'Bank HQ');
  if (!site) return;
  const title    = prompt('Audit title:', 'Wireless Security Assessment');
  const operator = prompt('Operator name:', 'Ishmael D. Tembo');
  window.open(
    '/api/v1/report'
    + '?site='     + encodeURIComponent(site)
    + '&title='    + encodeURIComponent(title)
    + '&operator=' + encodeURIComponent(operator),
    '_blank'
  );
}
</script></body></html>
""";
    }

    private static final String DASHBOARD_HTML = buildDashboardHtml();
}
