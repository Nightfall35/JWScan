import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

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
        loadCache();
 
    }
    private void loadCredentials() {
        apiName = System.getenv("WIGLE_API_NAME");
        apiToken = System.getenv("Wigle_API_TOKEN");
        if(apiName == null || apiToken == null) {
            File config = new File("wigle_config.properties");
            if(config.exists()) {
                try {
                    Properties props = new Properties();
                    props.load(new FileInputStream(config));
                    apiName = props.getProperty("apiName");
                    apiToken = props.getProperty("apiToken");

                }catch (Exception e) {
                    //Ignore this 
                }
            }
        }
        enabled = apiName != null && apiToken != null && !apiName.isEmpty() && !apiToken.isEmpty();

        if(enabled) {
            rat.println("Wigle.net geolocation ENABLED");

        }else {
            rat.println("Wigle.net geolocation DISABLED (set WIGLE_API_NAME and WIGLE_API_TOKEN env vars)");
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
        return new GeoResult(false, 0,0,0 "No geoloaction availbale")P;
    }
}
