import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Rat.java - Wi-Fi monitor with:
 *  - OUI auto-download & parse (IEEE oui.txt)
 *  - vendor lookup (OUI -> vendor)
 *  - signal trend analysis
 *  - minimal REST API (GET /aps, GET /stats)
 *
 * Usage:
 *   java Rat [intervalSeconds] [httpPort]
 *
 * Optional local OUI file: oui.txt (same format as supported earlier, one OUI per line)
 * Cached OUI file: oui_cache.txt (created by this program after successful download)
 */
public class Rat {
    private final Map<String, AP> seenById = new ConcurrentHashMap<>(); // key: BSSID or SSID fallback
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final int runningSeconds;
    private final String osNameLower = System.getProperty("os.name").toLowerCase();
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    // Trend / OUI config
    private final int SIGNAL_HISTORY_SIZE = 6; // keep last N signals (even number preferred)
    private final int TREND_THRESHOLD = 7; // percent points difference to signal a trend

    // OUI vendor map (prefix -> vendor)
    private final Map<String, String> ouiMap = new ConcurrentHashMap<>();

    // REST server
    private HttpServer httpServer;
    private final int httpPort;

    public Rat(int runningTime, int httpPort) {
        this.runningSeconds = Math.max(5, runningTime); // run a minimum of 5 seconds
        this.httpPort = httpPort;
        loadBuiltInOuIs();                          // small builtin fallback
        loadLocalOuiFile("oui.txt");               // merge local file if present
        // Start background OUI download/parse (runs quickly) but we initialize it now
        background.submit(this::downloadAndCacheIeeeOui);
        startHttpServer();                          // start REST endpoint immediately
    }

    public void start() {
        println("Rat is waking up.........\nRat has awakened, beginning crawling\nEstimated time (interval: " + runningSeconds + "s). Press ctrl+c to exit.");
        scheduler.scheduleWithFixedDelay(this::scanAndAlert, 0, runningSeconds, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            println("Rat retreating to sleep...");
            scheduler.shutdownNow();
            background.shutdownNow();
            if (httpServer != null) httpServer.stop(1);
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            println("stopped.");
        }));
    }

    private void scanAndAlert() {
        try {
            List<AP> aps = scanForAps();
            if (aps.isEmpty()) {
                println(timestamp() + " NO APS FOUND or scanning tool unavailable");
                return;
            }

            Set<String> currentIds = new HashSet<>();
            for (AP ap : aps) {
                String id = (ap.bssid != null && !ap.bssid.isEmpty())
                        ? ap.bssid
                        : (ap.ssid == null ? "<unknown>" : ap.ssid);
                currentIds.add(id);

                if (!seenById.containsKey(id)) {
                    // new AP
                    ap.initAux(SIGNAL_HISTORY_SIZE);
                    ap.addSignalSafely(ap.signal);
                    ap.lastSeen = Instant.now();
                    seenById.put(id, ap);
                    printlnAlert("NEW AP discovered: " + summarize(ap));
                    if (isOpen(ap)) {
                        printlnStrongAlert("!! NEW OPEN NETWORK detected: " + summarize(ap));
                        soundBell();
                    }
                } else {
                    AP old = seenById.get(id);
                    if (old != null) {
                        synchronized (old) {
                            // update fields
                            old.ssid = ap.ssid;
                            old.security = ap.security;
                            old.bssid = ap.bssid;
                            old.addSignalSafely(ap.signal);
                            old.lastSeen = Instant.now();

                            // analyze signal trend
                            Trend t = old.computeTrend(TREND_THRESHOLD);
                            if (t != Trend.UNKNOWN) {
                                println(timestamp() + " [TREND] " + id + " (" + safe(old.ssid) + ") : " + t + " | " + trendSummary(old));
                            }
                        }
                    }
                }
            }

            println(timestamp() + " Crawling complete. Total unique seen: " + seenById.size() +
                    " | currently visible: " + currentIds.size());
        } catch (Exception ex) {
            println(timestamp() + " Scan failed: " + ex.getMessage());
            ex.printStackTrace(System.out);
        }
    }

    private String trendSummary(AP ap) {
        String vendor = getVendorFromBssid(ap.bssid);
        String trend = ap.computeTrend(TREND_THRESHOLD).toString();
        int last = ap.getLastSignal();
        return String.format("%s | SIG=%s%% | vendor=%s", trend, (last >= 0 ? last : "N/A"), vendor == null ? "unknown" : vendor);
    }

    private boolean isOpen(AP ap) {
        if (ap == null) return false;
        String s = ap.security == null ? "" : ap.security.toUpperCase();
        return s.isEmpty() || s.equals("OPEN");
    }

    private String summarize(AP ap) {
        String vendor = getVendorFromBssid(ap.bssid);
        String trend = ap.computeTrend(TREND_THRESHOLD).toString();
        return String.format("SSID='%s' BSSID=%s (%s) SEC='%s' SIG=%s%% TREND=%s",
                safe(ap.ssid), safe(ap.bssid), (vendor == null ? "vendor=?": vendor), safe(ap.security),
                ap.signal >= 0 ? ap.signal : "N/A", trend);
    }

    private List<AP> scanForAps() throws IOException, InterruptedException {
        if (osNameLower.contains("win")) {
            return scanWindows();
        } else {
            if (isCommandAvailable("nmcli")) {
                return scanNmcli();
            } else if (isCommandAvailable("iwlist")) {
                return scanIwlist();
            } else {
                return Collections.emptyList();
            }
        }
    }

    // =================== Windows ===================
    private List<AP> scanWindows() throws IOException, InterruptedException {
        List<String> out = runCommandAndCollect("netsh", "wlan", "show", "networks", "mode=bssid");
        List<AP> aps = new ArrayList<>();
        AP current = null;

        for (String raw : out) {
            String line = raw.trim();
            if (line.startsWith("SSID ")) {
                int idx = line.indexOf(":");
                if (idx >= 0) {
                    String ssid = line.substring(idx + 1).trim();
                    current = new AP();
                    current.ssid = ssid;
                    aps.add(current);
                }
            } else if (line.startsWith("BSSID")) {
                int idx = line.indexOf(":");
                if (idx >= 0 && current != null) {
                    current.bssid = line.substring(idx + 1).trim();
                }
            } else if (line.startsWith("Signal")) {
                int idx = line.indexOf(":");
                if (idx >= 0 && current != null) {
                    String sig = line.substring(idx + 1).trim().replace("%", "");
                    try { current.signal = Integer.parseInt(sig); } catch (Exception ignored) {}
                }
            } else if (line.startsWith("Authentication") || line.startsWith("Encryption")) {
                int idx = line.indexOf(":");
                if (idx >= 0 && current != null) {
                    String v = line.substring(idx + 1).trim();
                    if (current.security == null || current.security.isEmpty()) current.security = v;
                    else current.security += " / " + v;
                }
            }
        }
        return aps;
    }

    // =================== Linux: nmcli ===================
    private List<AP> scanNmcli() throws IOException, InterruptedException {
        List<String> out = runCommandAndCollect("nmcli", "-f", "SSID,BSSID,SECURITY,SIGNAL", "device", "wifi", "list");
        List<AP> aps = new ArrayList<>();
        boolean headerSkipped = false;

        for (String line : out) {
            if (!headerSkipped) { headerSkipped = true; continue; }
            if (line.trim().isEmpty()) continue;

            String[] parts = line.trim().split("\\s{2,}");
            if (parts.length >= 4) {
                AP ap = new AP();
                ap.ssid = parts[0].trim();
                ap.bssid = parts[1].trim();
                ap.security = parts[2].trim();
                try { ap.signal = Integer.parseInt(parts[3].trim()); } catch (Exception e) { ap.signal = -1; }
                aps.add(ap);
            }
        }
        return aps;
    }

    // =================== Linux: iwlist ===================
    private List<AP> scanIwlist() throws IOException, InterruptedException {
        List<String> out = runCommandAndCollect("iwlist", "scanning");
        List<AP> aps = new ArrayList<>();
        AP current = null;

        for (String raw : out) {
            String line = raw.trim();
            if (line.startsWith("Cell")) {
                current = new AP();
                int idx = line.indexOf("Address:");
                if (idx >= 0) current.bssid = line.substring(idx + "Address:".length()).trim();
                aps.add(current);
            } else if (line.startsWith("ESSID:")) {
                if (current != null) current.ssid = line.substring(6).replace("\"", "").trim();
            } else if (line.contains("Encryption key:")) {
                if (current != null) {
                    String v = line.substring(line.indexOf("Encryption key:") + "Encryption key:".length()).trim();
                    if ("off".equalsIgnoreCase(v)) current.security = "OPEN";
                    else if (current.security == null) current.security = "ENCRYPTED";
                }
            } else if (line.contains("WPA") || line.contains("WEP")) {
                if (current != null) {
                    String exist = current.security == null ? "" : current.security + " ";
                    current.security = exist + line.replace("IE:", "").trim();
                }
            } else if (line.contains("Signal level=") && current != null) {
                // try to parse signal if present
                int idx = line.indexOf("Signal level=");
                if (idx >= 0) {
                    String rest = line.substring(idx + "Signal level=".length());
                    // formats like -45 dBm or 64/70
                    String token = rest.split("\\s+")[0].replace("dBm", "").trim();
                    try {
                        int val = Integer.parseInt(token);
                        // convert dBm to percent approximation (simple heuristic)
                        int percent = Math.max(0, Math.min(100, 2 * (val + 100)));
                        current.signal = percent;
                    } catch (Exception ignored) {}
                }
            }
        }
        return aps;
    }

    // =================== Helpers ===================
    private List<String> runCommandAndCollect(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return lines;
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            Process p = pb.start();
            boolean ok = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            return ok;
        } catch (Exception e) {
            try {
                Process p2 = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                p2.destroy();
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private void println(String s) { System.out.println(s); }

    private void printlnStrongAlert(String s) {
        System.out.println(timestamp() + " [*****CRITICAL ALERT******] " + s);
    }

    private void printlnAlert(String s) {
        System.out.println(timestamp() + " [ALERT] " + s);
    }

    private String timestamp() {
        return "[" + LocalDateTime.now().format(dtf) + "]";
    }

    private void soundBell() {
        System.out.print("\u0007");
        System.out.flush();
    }

    private String safe(String s) { return s == null ? "" : s; }

    // ---------------- OUI vendor lookup ----------------
    private void loadBuiltInOuIs() {
        // small sample; add/remove as desired
        ouiMap.put("001122", "Cisco Systems");
        ouiMap.put("44650D", "Cisco Meraki");
        ouiMap.put("F85D51", "Huawei Technologies");
        ouiMap.put("F8E079", "Xiaomi Communications");
        ouiMap.put("A4C3F0", "Apple, Inc.");
        ouiMap.put("7C9EBD", "TP-Link Technologies");
    }

    private void loadLocalOuiFile(String filename) {
        File f = new File(filename);
        if (!f.exists() || !f.isFile()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln;
            int added = 0;
            while ((ln = br.readLine()) != null) {
                ln = ln.trim();
                if (ln.isEmpty() || ln.startsWith("#")) continue;
                // split tokens; first token is OUI-ish, rest vendor name
                String[] toks = ln.split("\\s+", 2);
                if (toks.length >= 1) {
                    String raw = toks[0].replace(":", "").replace("-", "").toUpperCase();
                    if (raw.length() >= 6) {
                        String prefix = raw.substring(0, 6);
                        String vendor = toks.length >= 2 ? toks[1].trim() : "unknown";
                        ouiMap.put(prefix, vendor);
                        added++;
                    }
                }
            }
            if (added > 0) println(timestamp() + " Loaded " + added + " OUIs from " + filename);
        } catch (Exception e) {
            println(timestamp() + " Failed to load " + filename + " : " + e.getMessage());
        }
    }

    private void downloadAndCacheIeeeOui() {
        String ieeeUrl = "https://standards-oui.ieee.org/oui/oui.txt";
        String cacheFile = "oui_cache.txt";
        println(timestamp() + " Attempting to download IEEE OUI list...");
        try {
            URL url = new URL(ieeeUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", "Rat-OUI-Updater/1.0");
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    int added = parseAndMergeIeeeOui(br);
                    // write cache
                    try (BufferedWriter bw = new BufferedWriter(new FileWriter(cacheFile))) {
                        for (Map.Entry<String, String> e : ouiMap.entrySet()) {
                            bw.write(e.getKey() + " " + e.getValue());
                            bw.newLine();
                        }
                    } catch (Exception ex) {
                        println(timestamp() + " Warning: failed to write cache: " + ex.getMessage());
                    }
                    println(timestamp() + " Downloaded and merged IEEE OUI list (" + added + " added). Cached to " + cacheFile);
                    return;
                }
            } else {
                println(timestamp() + " IEEE download HTTP code: " + code + " — falling back to local cache if present.");
            }
        } catch (Exception e) {
            println(timestamp() + " IEEE download failed: " + e.getMessage());
        }

        // fallback: try to load cache file if present
        File cf = new File(cacheFile);
        if (cf.exists() && cf.isFile()) {
            try (BufferedReader br = new BufferedReader(new FileReader(cf))) {
                String ln;
                int added = 0;
                while ((ln = br.readLine()) != null) {
                    ln = ln.trim();
                    if (ln.isEmpty()) continue;
                    String[] toks = ln.split("\\s+", 2);
                    if (toks.length >= 1) {
                        String k = toks[0].toUpperCase();
                        String v = toks.length >= 2 ? toks[1] : "unknown";
                        if (k.length() >= 6) {
                            ouiMap.put(k.substring(0, 6), v);
                            added++;
                        }
                    }
                }
                println(timestamp() + " Loaded " + added + " OUIs from cache " + cacheFile);
            } catch (Exception e) {
                println(timestamp() + " Failed to load cache: " + e.getMessage());
            }
        } else {
            println(timestamp() + " No cache available.");
        }
    }

    /**
     * Parse IEEE oui.txt format. Look for lines like:
     *   00-00-00   (hex)        XEROX CORPORATION
     * or
     *   00:00:00   (hex)        XEROX CORPORATION
     *
     * We read lines and extract the hex token (first token) when "(hex)" is present on the line.
     */
    private int parseAndMergeIeeeOui(BufferedReader br) throws IOException {
        String ln;
        int added = 0;
        while ((ln = br.readLine()) != null) {
            if (!ln.contains("(hex)")) continue;
            String[] toks = ln.trim().split("\\s+");
            if (toks.length < 2) continue;
            String raw = toks[0].replace(":", "").replace("-", "").toUpperCase();
            if (raw.length() < 6) continue;
            String vendor = ln.substring(ln.indexOf("(hex)") + "(hex)".length()).trim();
            if (vendor.isEmpty()) vendor = toks.length >= 2 ? toks[1] : "unknown";
            String prefix = raw.substring(0, 6);
            // Put only if new or better non-empty vendor
            if (!ouiMap.containsKey(prefix) || ouiMap.get(prefix) == null || ouiMap.get(prefix).isEmpty()) {
                ouiMap.put(prefix, vendor);
                added++;
            }
        }
        return added;
    }

    private String getVendorFromBssid(String bssid) {
        if (bssid == null) return null;
        String cleaned = bssid.replace(":", "").replace("-", "").toUpperCase();
        if (cleaned.length() < 6) return null;
        String prefix = cleaned.substring(0, 6);
        return ouiMap.get(prefix);
    }

    // ---------------- AP class and trend analysis ----------------
    private enum Trend { RISING, FALLING, STABLE, UNKNOWN }

    private static class AP {
        String ssid = "";
        String bssid = "";
        String security = "";
        int signal = -1;
        Instant lastSeen = Instant.now();

        // for trend analysis
        private Deque<Integer> signalHistory;
        private int historyCapacity = 6;

        void initAux(int capacity) {
            if (signalHistory == null) {
                signalHistory = new ArrayDeque<>(capacity);
                historyCapacity = Math.max(2, capacity);
            }
        }

        synchronized void addSignalSafely(int s) {
            if (signalHistory == null) initAux(historyCapacity);
            if (s >= 0) {
                if (signalHistory.size() == historyCapacity) {
                    signalHistory.removeFirst();
                }
                signalHistory.addLast(s);
                this.signal = s;
            }
        }

        synchronized int getLastSignal() {
            return this.signal;
        }

        synchronized Trend computeTrend(int threshold) {
            if (signalHistory == null) return Trend.UNKNOWN;
            int n = signalHistory.size();
            if (n < 4) return Trend.UNKNOWN; // not enough samples

            // convert to list for easier slicing
            List<Integer> list = new ArrayList<>(signalHistory);
            int half = n / 2;
            double firstAvg = 0, secondAvg = 0;
            for (int i = 0; i < half; i++) firstAvg += list.get(i);
            firstAvg /= half;
            for (int i = half; i < n; i++) secondAvg += list.get(i);
            secondAvg /= (n - half);

            double diff = secondAvg - firstAvg;
            if (diff >= threshold) return Trend.RISING;
            if (diff <= -threshold) return Trend.FALLING;
            return Trend.STABLE;
        }

        synchronized List<Integer> getSignalHistorySnapshot() {
            if (signalHistory == null) return Collections.emptyList();
            return new ArrayList<>(signalHistory);
        }
    }

    // ---------------- REST API ----------------
    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
            httpServer.createContext("/aps", new ApsHandler());
            httpServer.createContext("/stats", new StatsHandler());
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            println(timestamp() + " REST API started on port " + httpPort + " (GET /aps , GET /stats)");
        } catch (IOException e) {
            println(timestamp() + " Failed to start HTTP server on port " + httpPort + " : " + e.getMessage());
        }
    }

    // Basic JSON escaping helper
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7f) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else sb.append(c);
            }
        }
        return sb.toString();
    }

    private class ApsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

                StringBuilder sb = new StringBuilder();
                sb.append("[");
                boolean first = true;
                for (AP ap : seenById.values()) {
                    if (!first) sb.append(",");
                    first = false;
                    Trend t = ap.computeTrend(TREND_THRESHOLD);
                    String vendor = getVendorFromBssid(ap.bssid);
                    List<Integer> history = ap.getSignalHistorySnapshot();
                    sb.append("{");
                    sb.append("\"ssid\":\"").append(jsonEscape(ap.ssid)).append("\",");
                    sb.append("\"bssid\":\"").append(jsonEscape(ap.bssid)).append("\",");
                    sb.append("\"security\":\"").append(jsonEscape(ap.security)).append("\",");
                    sb.append("\"signal\":").append(ap.signal).append(",");
                    sb.append("\"vendor\":\"").append(jsonEscape(vendor == null ? "" : vendor)).append("\",");
                    sb.append("\"trend\":\"").append(t.name()).append("\",");
                    sb.append("\"lastSeen\":\"").append(ap.lastSeen.toString()).append("\",");
                    sb.append("\"signalHistory\":").append(historyToJsonArray(history));
                    sb.append("}");
                }
                sb.append("]");
                byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    private String historyToJsonArray(List<Integer> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (int v : history) {
            if (!first) sb.append(",");
            first = false;
            sb.append(v);
        }
        sb.append("]");
        return sb.toString();
    }

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

                int unique = seenById.size();
                // currently visible: naive count of APs seen within last 2 * interval seconds
                int visible = 0;
                Instant cutoff = Instant.now().minusSeconds(Math.max(30, runningSeconds * 2L));
                for (AP ap : seenById.values()) {
                    if (ap.lastSeen.isAfter(cutoff)) visible++;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("{");
                sb.append("\"uniqueSeen\":").append(unique).append(",");
                sb.append("\"currentlyVisible\":").append(visible).append(",");
                sb.append("\"httpPort\":").append(httpPort).append(",");
                sb.append("\"intervalSeconds\":").append(runningSeconds);
                sb.append("}");
                byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    // ------------------ main -------------------------
    public static void main(String[] args) {
        int interval = 30;
        int port = 8080;
        if (args.length >= 1) {
            try { interval = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        if (args.length >= 2) {
            try { port = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        }
        Rat scanner = new Rat(interval, port);
        scanner.start();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {}
    }
}
