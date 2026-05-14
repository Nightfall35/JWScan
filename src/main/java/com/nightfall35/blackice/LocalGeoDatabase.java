/**
 * LocalGeoDatabase.java
 * ─────────────────────────────────────────────────────────────────────────────
 * BLACK ICE v2 — Local wardriving geolocation database.
 *
 * No external dependencies. Pure Java CSV + weighted centroid math.
 *
 * HOW IT WORKS:
 *   Each row in survey_log.csv is a (bssid, rssi, lat, lon) tuple recorded
 *   while walking. This class:
 *     1. Loads the full CSV on startup so any previous surveys are immediately
 *        available.
 *     2. Accepts live ingestReading() calls from Rat.writeSurveyReadings()
 *        on every SSE tick — no file re-read needed during a live survey.
 *     3. After each ingest, recalculates the weighted centroid for that BSSID:
 *
 *           weight_i  = 10 ^ ( (rssi_i - REF_RSSI) / (10 * PATH_LOSS_N) )
 *                     ≈ estimated 1/distance  (stronger signal → higher weight)
 *
 *           lat_est   = Σ(weight_i * lat_i) / Σ(weight_i)
 *           lon_est   = Σ(weight_i * lon_i) / Σ(weight_i)
 *
 *     4. Accuracy estimate = weighted standard deviation of positions × 111,320
 *        (converts degrees to metres at equatorial scale — good enough for Lusaka).
 *
 * PRIORITY in Rat.buildFullJson():
 *   LocalDB → Wigle → Approx+IP → Jitter
 *   Local always wins when data exists.
 *
 * THREAD SAFETY:
 *   estimates map is ConcurrentHashMap; rawReadings per BSSID uses synchronized
 *   blocks. Safe to call from SSE scheduler thread and HTTP handler threads.
 *
 * Author: Ishmael D. Tembo (NIGHTFALL35)
 */
package com.nightfall35.blackice;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalGeoDatabase {

    // ── Tuning constants ────────────────────────────────────────────────────────
    /** Reference RSSI at 1 metre (typical AP TX power seen at 1m). */
    private static final double REF_RSSI      = -30.0;
    /** Path loss exponent: 2.0 = free space, 3.0 = indoor average. */
    private static final double PATH_LOSS_N   = 2.8;
    /** Minimum readings before we trust the estimate enough to use it. */
    private static final int    MIN_READINGS  = 2;

    // ── State ───────────────────────────────────────────────────────────────────
    private final Rat rat;
    private final Path csvPath = Paths.get("survey_log.csv");

    /** Raw readings per BSSID — kept in memory so centroid can be recomputed. */
    private final Map<String, List<Reading>> rawReadings = new ConcurrentHashMap<>();

    /** Computed estimates — what buildFullJson() actually reads. */
    private final Map<String, GeoEstimate> estimates = new ConcurrentHashMap<>();

    // ── Data classes ─────────────────────────────────────────────────────────────
    public static class Reading {
        final double lat, lon;
        final int    rssi;
        Reading(double lat, double lon, int rssi) {
            this.lat  = lat;
            this.lon  = lon;
            this.rssi = rssi;
        }
    }

    public static class GeoEstimate {
        public final double lat, lon;
        public final double accuracyMeters;
        public final int    readings;
        public final long   updatedMs;

        GeoEstimate(double lat, double lon, double accuracyMeters, int readings) {
            this.lat            = lat;
            this.lon            = lon;
            this.accuracyMeters = accuracyMeters;
            this.readings       = readings;
            this.updatedMs      = System.currentTimeMillis();
        }

        @Override public String toString() {
            return String.format("GeoEstimate[%.6f, %.6f ±%.1fm, %d pts]",
                lat, lon, accuracyMeters, readings);
        }
    }

    // ── Constructor ──────────────────────────────────────────────────────────────
    public LocalGeoDatabase(Rat rat) {
        this.rat = rat;
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /**
     * Load (or reload) the full survey_log.csv from disk.
     * Called once on Rat.start(). Safe to call again to reload after external edits.
     */
    public void load() {
        if (!Files.exists(csvPath)) {
            rat.println("[LocalGeo] No survey_log.csv found — starting with empty DB");
            return;
        }
        int loaded = 0, skipped = 0;
        try (BufferedReader br = Files.newBufferedReader(csvPath)) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; } // skip CSV header row
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = splitCsvLine(line);
                // Expected columns: timestamp,bssid,ssid,rssi,lat,lon,channel,security
                if (parts.length < 6) { skipped++; continue; }
                try {
                    String bssid = parts[1].trim();
                    int    rssi  = Integer.parseInt(parts[3].trim());
                    double lat   = Double.parseDouble(parts[4].trim());
                    double lon   = Double.parseDouble(parts[5].trim());
                    if (bssid.isEmpty() || lat == 0.0 && lon == 0.0) { skipped++; continue; }
                    addRawReading(bssid, lat, lon, rssi);
                    loaded++;
                } catch (NumberFormatException e) {
                    skipped++;
                }
            }
        } catch (IOException e) {
            rat.println("[LocalGeo] Error reading CSV: " + e.getMessage());
        }

        // Recompute all estimates from loaded readings
        int computed = 0;
        for (String bssid : rawReadings.keySet()) {
            GeoEstimate est = computeCentroid(bssid);
            if (est != null) { estimates.put(bssid, est); computed++; }
        }

        rat.println(String.format("[LocalGeo] Loaded %d readings → %d BSSID estimates (%d rows skipped)",
            loaded, computed, skipped));
    }

    /**
     * Called live from Rat.writeSurveyReadings() on every SSE tick.
     * Adds one reading and immediately recomputes the centroid for this BSSID.
     */
    public void ingestReading(String bssid, String ssid, double lat, double lon, int rssi) {
        if (bssid == null || bssid.isEmpty()) return;
        if (lat == 0.0 && lon == 0.0) return;

        addRawReading(bssid, lat, lon, rssi);
        GeoEstimate est = computeCentroid(bssid);
        if (est != null) {
            estimates.put(bssid, est);
            // Mark AP as needing a position update on next JSON build
            // (positionRandom will be set in buildFullJson via localGeo.lookup())
        }
    }

    /**
     * Look up the best position estimate for a BSSID.
     * Returns null if no data or fewer than MIN_READINGS readings exist.
     */
    public GeoEstimate lookup(String bssid) {
        if (bssid == null) return null;
        return estimates.get(normaliseBssid(bssid));
    }

    /** Number of BSSIDs with valid estimates. */
    public int size() {
        return estimates.size();
    }

    /** Called on Rat shutdown — nothing to close (no file handles kept open). */
    public void shutdown() {
        rat.println("[LocalGeo] Shutdown — " + estimates.size() + " BSSID estimates in memory");
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private void addRawReading(String bssid, double lat, double lon, int rssi) {
        String key = normaliseBssid(bssid);
        rawReadings.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                   .add(new Reading(lat, lon, rssi));
    }

    /**
     * Weighted centroid calculation.
     *
     * Weight per reading is derived from RSSI using the log-distance path loss
     * model inverted: stronger signal = closer = more reliable position reading
     * = higher weight.
     *
     *   weight = 10 ^ ((rssi - REF_RSSI) / (10 * PATH_LOSS_N))
     *
     * For rssi = -55, REF=-30, N=2.8:
     *   weight = 10^((-55 - -30) / 28) = 10^(-0.893) ≈ 0.128
     * For rssi = -40:
     *   weight = 10^((-40 - -30) / 28) = 10^(-0.357) ≈ 0.440
     *
     * So a -40 dBm reading has ~3.4× the influence of a -55 dBm reading.
     */
    private GeoEstimate computeCentroid(String bssid) {
        List<Reading> readings = rawReadings.get(bssid);
        if (readings == null) return null;

        List<Reading> snapshot;
        synchronized (readings) {
            if (readings.size() < MIN_READINGS) return null;
            snapshot = new ArrayList<>(readings);
        }

        double sumW = 0, sumWLat = 0, sumWLon = 0;
        for (Reading r : snapshot) {
            double w = Math.pow(10.0, (r.rssi - REF_RSSI) / (10.0 * PATH_LOSS_N));
            sumW    += w;
            sumWLat += w * r.lat;
            sumWLon += w * r.lon;
        }
        if (sumW == 0) return null;

        double estLat = sumWLat / sumW;
        double estLon = sumWLon / sumW;

        // Weighted standard deviation → accuracy estimate in metres
        double sumWD2 = 0;
        for (Reading r : snapshot) {
            double w    = Math.pow(10.0, (r.rssi - REF_RSSI) / (10.0 * PATH_LOSS_N));
            double dLat = r.lat - estLat;
            double dLon = r.lon - estLon;
            // Convert degree difference to metres (equatorial approximation)
            double dm   = Math.sqrt(Math.pow(dLat * 111_320, 2) + Math.pow(dLon * 111_320, 2));
            sumWD2 += w * dm * dm;
        }
        double accuracyMeters = Math.sqrt(sumWD2 / sumW);

        return new GeoEstimate(estLat, estLon, accuracyMeters, snapshot.size());
    }

    /**
     * Normalise BSSID to upper-case colon-separated format.
     * Handles: "AA:BB:CC:DD:EE:FF", "AA-BB-CC-DD-EE-FF", "AABBCCDDEEFF"
     */
    private String normaliseBssid(String bssid) {
        if (bssid == null) return "";
        String clean = bssid.trim().toUpperCase()
                            .replace("-", ":")
                            .replace(".", ":");
        // If no separators and 12 hex chars, insert colons
        if (!clean.contains(":") && clean.length() == 12) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i += 2) {
                if (i > 0) sb.append(':');
                sb.append(clean, i, i + 2);
            }
            return sb.toString();
        }
        return clean;
    }

    /**
     * Minimal CSV line splitter that handles quoted fields.
     * Not a full RFC 4180 parser but handles the common cases in survey_log.csv.
     */
    private String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++; // escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}