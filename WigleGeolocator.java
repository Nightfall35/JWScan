import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;
import java.util.concurrent.ConcurrentHashMap;


public class WigleGeolocator {
    private final Rat rat;
    private String apiName;
    private String apiToken;
    private boolean enabled = false;
    private final Map<String, GeoCacheEntry> geoCache = new ConcurrentHashMap<>();
    private final String CACHE_FILE = "wigle_cache.dat";
    

    private static class GeoCacheEntry {
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
        this.rat= rat;
        loadCredentials();
        loadGeoCache();
 
    }
   
    private void loadCredentials() {
        File config = new File("Wigle_config.txt");
        if(config.exists()) {
            try{
                List<String> lines = Files.readAllLines(config.toPath(),StandardCharsets.UTF_8);
                for(String line : lines) {
                    line = line.trim();
                    if(line.startsWith("API_NAME=")){
                        apiName = line.substring(9).trim();

                    }else if(line.startsWith("API_TOKEN=")){
                        apiToken=line.substring(10).trim();
                    }   
                }
                rat.println("loaded Wigle credentials from config file");
                if(apiName != null && apiToken != null) {
                    enabled = true;
                    rat.println("Wigle geolocation enabled");
                }
            }catch(Exception e){
                rat.println("Failed to load Wigle credentials: "+ e.getMessage());
            }

        }
        if(apiName == null || apiToken.isEmpty()) {
            apiName = System.getenv("WIGLE_API_NAME");

        }
        if(apiToken == null || apiToken.isEmpty()) {
            apiToken = System.getenv("WIGLE_API_TOKEN");
        }

        enabled = apiName != null && apiToken != null && !apiName.isEmpty() && !apiToken.isEmpty();
        if(enabled){
            rat.println("Wigle.net geolocation ENABLED for user: " + apiName);

        }else {
            ray.println("Wigle.net geolocation DISABLED - missing credentials");
        }
    }

    private void loadGeoCache() {
        File file = new File(CACHE_FILE);
        if(file.exists()) 
        {
            try(ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                Map<String , GeoCacheEntry> loaded = (Map<String , GeoCacheEntry>) ois.readObject();
                geoCache.putAll(loaded);
                rat.println("Loaded " + geoCache.size() + " cached geolocations");
            }catch(Exception e) {
                rat.println("Failed to load geolocation cache: "+e.getMessage());
            }
        }
    }
    private void saveGeoCache() {
        try(ObjectOutputStream oos = new ObjectOutputStream (new FileOutputStream(CACHE_FILE)))
        {
            oos.writeObject(geoCache);

        }catch(Exception e) {
            rat.println("Failed to save geolocation cache: "+ e.getMessage());
        }
    }
    
    public GeoResult geolocate(String bssid, String ssid) {
        String cleanBssid = bssid.replace(": ", "").replace("-","").toUpperCase();
        GeoCacheEntry cached = geoCache.get(cleanBssid);
        if(cached != null) {
            long age = System.currentTimeMillis() - cached.timestamp;
            if(age < 30L * 24*60*1000) {
                return new GeoResult(true, cached.lat,cached.lon, cached.accuracy,"Cache");
            }
        }
        if(enabled) {
            return queryWigle(cleanBssid, ssid);
        }
        return new GeoResult(false, 0,0,0 ,"No geoloaction availble");
    }
    private GeoResult queryWigle(String bssid, String ssid) {
        try{
            String url ="https://api.wigle.net/api/v2/network/search";
            String queryParams = String.format("newid=%s&ssid=%s",
                URLEncoder.encode(bssid, "UTF-8"),
                URLEncoder.encode(ssid != null? ssid: "", "UTF-8")
            );

            HttpURLConnection conn = (HttpURLConnection) new URL(url + "?" + queryParams).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authentication", "Basic" + Base64.getEncoder().encodeToString((apiName + ":" + apiToken).getBytes()));
            if(conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return parseWigleResponse(bssid, response.toString());
            }else if(conn.getResponseCode() == 429) {
                rat.println("Wigle API rat limit exceeded");
                Thread.sleep(60000);
            }
        }catch(Exception e) {
            rat.println("Wigle query failed: "+e.getMessage());
        }

        return new GeoResult(false, 0,0,0,"Query failed");
        
    }
    private GeoResult parseWigleResponse(String bssid, String jsonResponse) {
        try{
            JSONObject json = new JSONObject(jsonResponse);
            if(json.getBoolean("success")) {
                JSONArray results = json.getJSONArray("results");
                if(results.length() > 0) {
                    JSONObject result = results.getJSONObject(0);

                    double lat = result.getDouble("trilat");
                    double lon = result.getDouble("trilong");           
                    int accuracy = result.optInt("accuracy", 100);
                    
                    geoCache.put(bssid, new GeoCacheEntry(lat, lon, accuracy));
                    saveGeoCache();

                    return new GeoResult(true, lat, lon ,accuracy, "wigle.net");

                }
            }

        }catch (Exception e) {
            rat.println("Failed to parse wigle response: " + e.getMessage());
        }
        return new GeoResult(false,0,0,0,"No result");
    }

    public static class GeoResult {
        public final boolean success;
        public final double lat;
        public final double lon;
        public final int accuracy;
        public final String source;

        GeoResult(boolean success, double lat, double lon, int accuracy, String source) {
            this.success = success;
            this.lat =lat;
            this.lon = lon;
            this.accuracy = accuracy;
            this.source = source;
        }
    }

    public GeoResult getApproximateLocation() {
        try{
            HttpURLConnection conn =(HttpURLConnection) new URL("http://ip-api.com/json/").openConnection();
            conn.setConnectTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response  = new StringBuilder();
            String line;
            while((line = reader.readLine()) != null) {
                response.append(line);
            }
            JSONObject json = new JSONObject(response.toString());
            if(json.getString("status").equals("success")) {
                return new GeoResult(true,
                    json.getDouble("lat"),
                    json.getDouble("lon"),
                    50000,
                    "IP Geolocation"
                );
            }
        }catch(Exception e) {
            //ignore this - let it fallthroygh
        }

        return new GeoResult(false,0,0,0,"No location available");
    }
}
