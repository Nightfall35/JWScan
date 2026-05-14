import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * EnterpriseApiServer — replacement for the inline HTTP server in Rat.java.
 *
 * ENTERPRISE ADDITIONS for bank / corporate buyers:
 *
 * 1. TOKEN AUTHENTICATION — every endpoint (except /health) requires: (still work in progress )
 *      Authorization: Bearer <token>
 *    Tokens are generated on startup and printed to console. Configurable
 *    via api_token.txt in the working directory.
 *
 * 2. RATE LIMITING — per-IP sliding window (100 req/min by default).
 *    Returns HTTP 429 with Retry-After header.
 *
 * 3. STRUCTURED JSON API — machine-readable endpoints banks can consume:
 *      GET  /api/v1/health          — system status (no auth required)
 *      GET  /api/v1/aps             — all visible APs as JSON array
 *      GET  /api/v1/aps/{bssid}     — single AP detail
 *      GET  /api/v1/threats         — only evil-twin / deauth alerts
 *      GET  /api/v1/stats           — aggregate counts and threat level
 *      POST /api/v1/alerts          — create alert rule
 *      GET  /api/v1/alerts          — list alert rules
 *      DELETE /api/v1/alerts/{id}   — delete alert rule
 *      GET  /api/v1/export/csv      — survey CSV
 *      GET  /api/v1/export/kml      — survey KML
 *      GET  /api/v1/export/gpx      — survey GPX
 *      GET  /sse                    — live event stream (auth required)
 *      GET  /                       — dashboard HTML (no auth — browser UI)
 *
 * 4. CORS headers — configurable allowed origins for embedding in bank portals.
 *
 * 5. AUDIT LOG — every authenticated API call is written to api_audit.log.
 *

 */
public class EnterpriseApiServer {

    private final Rat rat;
    private final int port;
    private HttpServer server;

    // Auth
    private String apiToken;
    private static final String TOKEN_FILE = "api_token.txt";

    // Rate limiting: IP → [request timestamps]
    private final Map<String, LinkedList<Long>> rateLimitMap = new ConcurrentHashMap<>();
    private static final int RATE_LIMIT_REQUESTS = 100;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000;

    // Audit log
    private PrintWriter auditLog;
    private final Object auditLock = new Object();
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // CORS — add your bank portal origin here
    private String allowedOrigin = "*"; // Change to specific origin in production

    public EnterpriseApiServer(Rat rat, int port) {
        this.rat = rat;
        this.port = port;
        loadOrGenerateToken();
        openAuditLog();
    }

    // ── Token management ────────────────────────────────────────────────────
    private void loadOrGenerateToken() {
        java.nio.file.Path tokenFile = java.nio.file.Paths.get(TOKEN_FILE);
        if (java.nio.file.Files.exists(tokenFile)) {
            try {
                apiToken = java.nio.file.Files.readString(tokenFile).trim();
                rat.println("[API] Token loaded from " + TOKEN_FILE);
            } catch (IOException e) {
                generateNewToken(tokenFile);
            }
        } else {
            generateNewToken(tokenFile);
        }
        rat.println("[API] ╔══════════════════════════════════════════════╗");
        rat.println("[API] ║  API TOKEN: " + apiToken);
        rat.println("[API] ║  Include as: Authorization: Bearer <token>");
        rat.println("[API] ╚══════════════════════════════════════════════╝");
    }

    private void generateNewToken(java.nio.file.Path file) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        apiToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            java.nio.file.Files.writeString(file, apiToken);
        } catch (IOException ignored) {}
        rat.println("[API] New token generated and saved to " + TOKEN_FILE);
    }

    private void openAuditLog() {
        try {
            auditLog = new PrintWriter(new FileWriter("api_audit.log", true));
        } catch (IOException e) {
            rat.println("[API] Could not open audit log: " + e.getMessage());
        }
    }

    private void audit(String ip, String method, String path, int status) {
        if (auditLog == null) return;
        synchronized (auditLock) {
            auditLog.printf("[%s] %s %s %s → %d%n",
                    LocalDateTime.now().format(dtf), ip, method, path, status);
            auditLog.flush();
        }
    }

    // ── Rate limiting ────────────────────────────────────────────────────────
    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        LinkedList<Long> timestamps = rateLimitMap.computeIfAbsent(ip, k -> new LinkedList<>());
        synchronized (timestamps) {
            timestamps.removeIf(t -> now - t > RATE_LIMIT_WINDOW_MS);
            if (timestamps.size() >= RATE_LIMIT_REQUESTS) return true;
            timestamps.add(now);
            return false;
        }
    }

    // ── Auth check ───────────────────────────────────────────────────────────
    private boolean isAuthorized(HttpExchange ex) {
        String authHeader = ex.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        String token = authHeader.substring(7).trim();
        // Constant-time comparison
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] provided = md.digest(token.getBytes(StandardCharsets.UTF_8));
            byte[] expected = md.digest(apiToken.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(provided, expected);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Common response helpers ───────────────────────────────────────────────
    private void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", allowedOrigin);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private void sendError(HttpExchange ex, int code, String message) throws IOException {
        sendJson(ex, code, "{\"error\":" + jsonString(message) + ",\"code\":" + code + "}");
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // ── Middleware: auth + rate limit guard ───────────────────────────────────
    /**
     * Call at the start of every protected handler.
     * Returns false and sends the error response if request should be rejected.
     */
    private boolean guard(HttpExchange ex, boolean requireAuth) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (isRateLimited(ip)) {
            ex.getResponseHeaders().set("Retry-After", "60");
            sendError(ex, 429, "Too many requests. Limit: " + RATE_LIMIT_REQUESTS + "/min.");
            audit(ip, method, path, 429);
            return false;
        }

        if (requireAuth && !isAuthorized(ex)) {
            sendError(ex, 401, "Unauthorized. Provide: Authorization: Bearer <token>");
            audit(ip, method, path, 401);
            return false;
        }

        audit(ip, method, path, 200); // optimistic; handlers may override
        return true;
    }

    // ── Server startup ───────────────────────────────────────────────────────
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            // Public endpoints (no auth)
            server.createContext("/api/v1/health", this::handleHealth);
            server.createContext("/",              this::handleDashboard);

            // Authenticated endpoints
            server.createContext("/api/v1/aps",     this::handleAps);
            server.createContext("/api/v1/threats", this::handleThreats);
            server.createContext("/api/v1/stats",   this::handleStats);
            server.createContext("/api/v1/alerts",  this::handleAlerts);
            server.createContext("/api/v1/export",  this::handleExport);
            server.createContext("/api/v1/survey",  this::handleSurvey);
            
            server.createContext("/sse",            this::handleSse);
            server.createContext("/api/v1/report", this::handleReport);

            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            rat.println("[API] Enterprise API server running on port " + port);
        } catch (IOException e) {
            rat.println("[API] Failed to start server: " + e.getMessage());
        }
    }

    // ── GET /api/v1/health ───────────────────────────────────────────────────
    private void handleHealth(HttpExchange ex) throws IOException {
        String body = "{\"status\":\"ok\",\"version\":\"2.1\",\"timestamp\":\""
                + LocalDateTime.now().format(dtf) + "\"}";
        sendJson(ex, 200, body);
    }

    // ── GET / (dashboard) ────────────────────────────────────────────────────
    private void handleDashboard(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            sendError(ex, 404, "Not found");
            return;
        }
        // Delegate to Rat's embedded dashboard
        byte[] html = rat.getDashboardHtml().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        ex.getResponseBody().write(html);
        ex.getResponseBody().close();
    }

    // ── GET /api/v1/aps[/{bssid}] ────────────────────────────────────────────
    private void handleAps(HttpExchange ex) throws IOException {
        if (!guard(ex, true)) return;

        String path = ex.getRequestURI().getPath();
        // /api/v1/aps/{bssid}
        if (path.length() > "/api/v1/aps/".length()) {
            String bssid = path.substring("/api/v1/aps/".length()).toUpperCase();
            String apJson = rat.getApJson(bssid);
            if (apJson == null) { sendError(ex, 404, "AP not found: " + bssid); return; }
            sendJson(ex, 200, apJson);
            return;
        }

        sendJson(ex, 200, rat.getAllApsJson());
    }

    // ── GET /api/v1/threats ──────────────────────────────────────────────────
    private void handleThreats(HttpExchange ex) throws IOException {
        if (!guard(ex, true)) return;
        sendJson(ex, 200, rat.getThreatsJson());
    }

    // ── GET /api/v1/stats ────────────────────────────────────────────────────
    private void handleStats(HttpExchange ex) throws IOException {
        if (!guard(ex, true)) return;
        sendJson(ex, 200, rat.getStatsJson());
    }

    // ── /api/v1/alerts ───────────────────────────────────────────────────────
    private void handleAlerts(HttpExchange ex) throws IOException {
        if (!guard(ex, true)) return;
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        switch (method) {
            case "GET" -> sendJson(ex, 200, rat.getAlertRulesJson());
            case "POST" -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String result = rat.addAlertRule(body);
                sendJson(ex, 200, result);
            }
            case "DELETE" -> {
                // /api/v1/alerts/{id}
                String id = path.substring(path.lastIndexOf('/') + 1);
                rat.deleteAlertRule(id);
                sendJson(ex, 200, "{\"deleted\":true}");
            }
            default -> sendError(ex, 405, "Method not allowed");
        }
    }

    // ── /api/v1/export ───────────────────────────────────────────────────────
    private void handleExport(HttpExchange ex) throws IOException {
        if (!guard(ex, true)) return;
        String path = ex.getRequestURI().getPath();
        try {
            if (path.endsWith("/csv")) {
                byte[] data = rat.exportCsv();
                ex.getResponseHeaders().set("Content-Type", "text/csv");
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"survey.csv\"");
                ex.sendResponseHeaders(200, data.length);
                ex.getResponseBody().write(data);
            } else if (path.endsWith("/kml")) {
                byte[] data = rat.exportKml();
                ex.getResponseHeaders().set("Content-Type", "application/vnd.google-earth.kml+xml");
                ex.sendResponseHeaders(200, data.length);
                ex.getResponseBody().write(data);
            } else if (path.endsWith("/gpx")) {
                byte[] data = rat.exportGpx();
                ex.getResponseHeaders().set("Content-Type", "application/gpx+xml");
                ex.sendResponseHeaders(200, data.length);
                ex.getResponseBody().write(data);
            } else {
                sendError(ex, 404, "Unknown export format. Use /csv, /kml, or /gpx");
            }
        } catch (Exception e) {
            sendError(ex, 500, "Export failed: " + e.getMessage());
        }
        ex.getResponseBody().close();
    }

    // ── /api/v1/survey ───────────────────────────────────────────────────────
    private void handleSurvey(HttpExchange ex) throws IOException {
        if (!guard(ex, true)) return;
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        switch (body) {
            case "start" -> { rat.startSurvey(); sendJson(ex, 200, "{\"status\":\"started\"}"); }
            case "stop"  -> { rat.stopSurvey();  sendJson(ex, 200, "{\"status\":\"stopped\"}"); }
            default      -> sendError(ex, 400, "Body must be 'start' or 'stop'");
        }
    }

    // ── GET /sse ─────────────────────────────────────────────────────────────
    private void handleSse(HttpExchange ex) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        if (isRateLimited(ip)) {
            ex.getResponseHeaders().set("Retry-After", "60");
            sendError(ex, 429, "Rate limited");
            return;
        }
        //if (!isAuthorized(ex)) {
          //  sendError(ex, 401, "Unauthorized");
            //return;
        //}
        // Delegate SSE connection to Rat's SSE infrastructure
        rat.registerSseClient(ex);
    }

    private void handleReport(HttpExchange ex) throws IOException {
        if (!guard(ex, true)) return;

        // Parse query string: ?site=Bank+HQ&title=Floor+3&operator=Ishmael
        String query = ex.getRequestURI().getQuery();
        String site     = queryParam(query, "site",     "Site Survey");
        String title    = queryParam(query, "title",    "Wireless Security Assessment");
        String operator = queryParam(query, "operator", "BLACK ICE v2");

        try {
            PdfReportGenerator gen = new PdfReportGenerator(rat);
            byte[] pdf = gen.generate(site, title, operator);

            String filename = "blackice_report_"
                + java.time.LocalDate.now() + ".pdf";
            ex.getResponseHeaders().set("Content-Type", "application/pdf");
            ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + filename + "\"");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", allowedOrigin);
            ex.sendResponseHeaders(200, pdf.length);
            ex.getResponseBody().write(pdf);

        } catch (Exception e) {
            rat.println("[API] Report generation failed: " + e.getMessage());
            sendError(ex, 500, "Report generation failed: " + e.getMessage());
        } finally {
            ex.getResponseBody().close();
        }
    }
    // helper method to parse query parameters with defaults
    private String queryParam(String query, String key, String def) {
        if (query == null) return def;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv[1],
                        java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) { return kv[1]; }
            }
        }
        return def;
    }

    public void stop() {
        if (server != null) server.stop(1);
        if (auditLog != null) { auditLog.flush(); auditLog.close(); }
    }
}
