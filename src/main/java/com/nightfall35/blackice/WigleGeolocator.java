package com.nightfall35.blackice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

public class WigleGeolocator {
    private final Rat rat;
    private String apiName;
    private String apiToken;
    private boolean enabled = false;
    private final Map<String, GeoCacheEntry> geoCache = new ConcurrentHashMap<>();
    private final String CACHE_FILE = "wigle_cache.dat";

    private static class GeoCacheEntry implements Serializable {
        double lat;
        double lon;
        long timestamp;
        int accuracy;
        GeoCacheEntry(double lat, double lon, int accuracy) {
            this.lat = lat;
            this.lon = lon;
            this.accuracy = accuracy;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public WigleGeolocator(Rat rat) {
        this.rat = rat;
        loadCredentials();
        loadGeoCache();
    }

    private void loadCredentials() {
        File config = new File("Wigle_config.txt");
        if (config.exists()) {
            try {
                List<String> lines = Files.readAllLines(config.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("API_NAME=")) {
                        apiName = line.substring(9).trim();
                    } else if (line.startsWith("API_TOKEN=")) {
                        apiToken = line.substring(10).trim();
                    }
                }
                rat.println("loaded Wigle credentials from config file");
                if (apiName != null && apiToken != null) {
                    enabled = true;
                    rat.println("Wigle geolocation enabled");
                }
            } catch (Exception e) {
                rat.println("Failed to load Wigle credentials: " + e.getMessage());
            }
        }

        // Fall back to env vars if config didn't provide them
        if (apiName == null || apiName.isEmpty()) {
            apiName = System.getenv("WIGLE_API_NAME");
        }
        if (apiToken == null || apiToken.isEmpty()) {
            apiToken = System.getenv("WIGLE_API_TOKEN");
        }

        enabled = apiName != null && apiToken != null
                && !apiName.isEmpty() && !apiToken.isEmpty();

        if (enabled) {
            rat.println("Wigle.net geolocation ENABLED for user: " + apiName);
        } else {
            rat.println("Wigle.net geolocation DISABLED - missing credentials");
            rat.println("Set WIGLE_API_NAME and WIGLE_API_TOKEN env vars or Wigle_config.txt");
        }
    }

    private void loadGeoCache() {
        File file = new File(CACHE_FILE);
        if (file.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                @SuppressWarnings("unchecked")
                Map<String, GeoCacheEntry> loaded = (Map<String, GeoCacheEntry>) ois.readObject();
                geoCache.putAll(loaded);
                rat.println("Loaded " + geoCache.size() + " cached geolocations");
            } catch (Exception e) {
                rat.println("Failed to load geolocation cache: " + e.getMessage());
            }
        }
    }

    private void saveGeoCache() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(CACHE_FILE))) {
            oos.writeObject(new HashMap<>(geoCache));
        } catch (Exception e) {
            rat.println("Failed to save geolocation cache: " + e.getMessage());
        }
    }

    public GeoResult geolocate(String bssid, String ssid) {
        if (bssid == null || bssid.isEmpty()) return new GeoResult(false, 0, 0, 0, "No BSSID");

        String cleanBssid = bssid.replace(":", "").replace("-", "").toUpperCase();

        // FIX 3: cache age was 30 minutes, should be 30 days
        GeoCacheEntry cached = geoCache.get(cleanBssid);
        if (cached != null) {
            long age = System.currentTimeMillis() - cached.timestamp;
            if (age < 30L * 24 * 60 * 60 * 1000) {
                return new GeoResult(true, cached.lat, cached.lon, cached.accuracy, "Cache");
            }
        }

        if (enabled) {
            return queryWigle(cleanBssid, ssid);
        }
        return new GeoResult(false, 0, 0, 0, "No geolocation available");
    }

    private GeoResult queryWigle(String bssid, String ssid) {
        try {
            // FIX 2: param was "newid" → correct Wigle API param is "netid"
            String queryParams = String.format("netid=%s&ssid=%s",
                    URLEncoder.encode(bssid, "UTF-8"),
                    URLEncoder.encode(ssid != null ? ssid : "", "UTF-8"));

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://api.wigle.net/api/v2/network/search?" + queryParams).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            // FIX 1: header was "Authentication" with no space after "Basic"
            //        correct header is "Authorization: Basic <token>"
            String credentials = apiName + ":" + apiToken;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                return parseWigleResponse(bssid, response.toString());

            } else if (status == 401) {
                rat.println("Wigle API auth failed (401) — check API_NAME / API_TOKEN in Wigle_config.txt");
            } else if (status == 429) {
                rat.println("Wigle API rate limit exceeded (429) — backing off 60s");
                Thread.sleep(60_000);
            } else {
                rat.println("Wigle API returned HTTP " + status + " for " + bssid);
            }

        } catch (Exception e) {
            rat.println("Wigle query failed: " + e.getMessage());
        }

        return new GeoResult(false, 0, 0, 0, "Query failed");
    }

    private GeoResult parseWigleResponse(String bssid, String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            if (json.optBoolean("success", false)) {
                JSONArray results = json.getJSONArray("results");
                if (results.length() > 0) {
                    JSONObject result = results.getJSONObject(0);
                    double lat      = result.getDouble("trilat");
                    double lon      = result.getDouble("trilong");
                    int    accuracy = result.optInt("accuracy", 100);

                    geoCache.put(bssid, new GeoCacheEntry(lat, lon, accuracy));
                    saveGeoCache();

                    return new GeoResult(true, lat, lon, accuracy, "wigle.net");
                }
                rat.println("Wigle returned 0 results for " + bssid);
            } else {
                rat.println("Wigle response success=false: " + jsonResponse.substring(0, Math.min(120, jsonResponse.length())));
            }
        } catch (Exception e) {
            rat.println("Failed to parse Wigle response: " + e.getMessage());
        }
        return new GeoResult(false, 0, 0, 0, "No result");
    }

    public GeoResult getApproximateLocation() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://ip-api.com/json/").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);

            JSONObject json = new JSONObject(response.toString());
            if ("success".equals(json.getString("status"))) {
                return new GeoResult(true,
                        json.getDouble("lat"),
                        json.getDouble("lon"),
                        50000,
                        "IP Geolocation");
            }
        } catch (Exception ignored) {
            // silent fallback — caller handles failure
        }
        return new GeoResult(false, 0, 0, 0, "No location available");
    }

    // ── GeoResult ─────────────────────────────────────────────────────────────
    public static class GeoResult {
        public final boolean success;
        public final double  lat;
        public final double  lon;
        public final int     accuracy;
        public final String  source;

        GeoResult(boolean success, double lat, double lon, int accuracy, String source) {
            this.success  = success;
            this.lat      = lat;
            this.lon      = lon;
            this.accuracy = accuracy;
            this.source   = source;
        }
    }
}