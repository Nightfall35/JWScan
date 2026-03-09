import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

public class OuiDatabase {
    private final Map<String, String> ouiMap   = new ConcurrentHashMap<>();
    private final String              cacheFile = "oui_cache.dat";
    private final Rat                 rat;

    private static final String IEEE_OUI_URL      = "https://standards-oui.ieee.org/oui/oui.txt";
    private static final String WIRESHARK_OUI_URL =
        "https://code.wireshark.org/review/gitweb?p=wireshark.git;a=blob_plain;f=manuf";

    public OuiDatabase(Rat rat) {
        this.rat = rat;
        loadCache();
        if (ouiMap.size() < 10_000 || isCacheOlderThan(7)) {
            new Thread(this::downloadAndUpdate).start();
        }
    }

    // ── Cache load ───────────────────────────────────────────────────────────────
    public void loadCache() {
        Path path = Paths.get(cacheFile);
        if (Files.exists(path)) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile))) {
                @SuppressWarnings("unchecked")
                Map<String, String> cached = (Map<String, String>) ois.readObject();
                ouiMap.putAll(cached);
                rat.println("Loaded " + ouiMap.size() + " OUI entries from cache");
            } catch (Exception e) {
                rat.println("Failed to load OUI cache: " + e.getMessage());
                loadBuiltInFallback();
            }
        } else {
            loadBuiltInFallback();
        }
    }

    // ── Built-in fallback ────────────────────────────────────────────────────────
    private void loadBuiltInFallback() {
        Map<String, String> critical = Map.ofEntries(
            Map.entry("001122", "Cisco"),         Map.entry("44650D", "Cisco Meraki"),
            Map.entry("A4C3F0", "Apple"),          Map.entry("7C9EBD", "TP-Link"),
            Map.entry("001C10", "Ubiquiti"),       Map.entry("B827EB", "Raspberry Pi"),
            Map.entry("F4F5D8", "Google"),         Map.entry("9CADEF", "Huawei"),
            Map.entry("B0487A", "TP-Link"),        Map.entry("D85DFB", "Amazon"),
            Map.entry("EC086B", "TP-Link"),        Map.entry("F81A67", "ARRIS"),
            Map.entry("8C8590", "Apple"),          Map.entry("001D0F", "Netgear"),
            Map.entry("001E2A", "Netgear"),        Map.entry("0021B9", "Intel"),
            Map.entry("0022FA", "D-Link"),         Map.entry("0050F2", "Microsoft"),
            Map.entry("0090CC", "Intel"),          Map.entry("00A0C9", "Intel"),
            Map.entry("00E075", "Asus"),           Map.entry("08D40C", "Apple"),
            Map.entry("0C8268", "TP-Link"),        Map.entry("10DEE4", "Asus"),
            Map.entry("14CC20", "TP-Link"),        Map.entry("18A6F7", "TP-Link"),
            Map.entry("1C60DE", "Asus"),           Map.entry("203706", "Cisco"),
            Map.entry("289EDF", "Huawei"),         Map.entry("2CBE08", "Apple"),
            Map.entry("34159E", "Raspberry Pi"),   Map.entry("34E894", "Intel"),
            Map.entry("3C5AB4", "Google"),         Map.entry("40D32D", "Apple"),
            Map.entry("44D9E7", "Ubiquiti"),       Map.entry("4C32D9", "Asus"),
            Map.entry("5057A8", "Cisco"),          Map.entry("5C8576", "Asus"),
            Map.entry("60A44C", "Asus"),           Map.entry("647002", "TP-Link"),
            Map.entry("68FF7B", "TP-Link"),        Map.entry("7038EE", "Apple"),
            Map.entry("746A89", "TP-Link"),        Map.entry("7831C1", "Apple"),
            Map.entry("7C0191", "Apple"),          Map.entry("80D21D", "AzureWave"),
            Map.entry("841B5E", "Netgear"),        Map.entry("84A6C8", "Intel"),
            Map.entry("88DC96", "Apple"),          Map.entry("8C85C1", "Apple"),
            Map.entry("9094E4", "D-Link"),         Map.entry("94DBDA", "Huawei"),
            Map.entry("9CA513", "Samsung"),        Map.entry("A46706", "Apple"),
            Map.entry("AC293A", "Apple"),          Map.entry("B0754D", "Apple"),
            Map.entry("B8E856", "Apple"),          Map.entry("C05627", "Belkin"),
            Map.entry("C83A35", "TP-Link"),        Map.entry("CCB255", "D-Link"),
            Map.entry("D0C5D8", "AzureWave"),      Map.entry("D481CA", "Intel"),
            Map.entry("E0CB1D", "Belkin"),         Map.entry("E4CE70", "Huawei"),
            Map.entry("EC9327", "TP-Link"),        Map.entry("F48C50", "Intel"),
            Map.entry("FC626E", "Xiaomi")
        );
        ouiMap.putAll(critical);
        rat.println("Loaded " + ouiMap.size() + " built-in OUI entries");
    }

    // ── Cache age check — FIX: was 10000, should be 1000L ───────────────────────
    private boolean isCacheOlderThan(int days) {
        Path path = Paths.get(cacheFile);
        if (!Files.exists(path)) return true;
        try {
            FileTime lastModified = Files.getLastModifiedTime(path);
            long ageInDays = (System.currentTimeMillis() - lastModified.toMillis())
                             / (1000L * 60 * 60 * 24);   // FIX: was 10000, caused re-download every run
            return ageInDays > days;
        } catch (Exception e) {
            return true;
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────────
    public void downloadAndUpdate() {
        rat.println("Downloading OUI database...");
        try {
            if (downloadFromIeee())       { rat.println("IEEE OUI database loaded");       saveCache(); return; }
            if (downloadFromWireshark())  { rat.println("Wireshark OUI database loaded");  saveCache(); return; }
            rat.println("OUI download failed — using cached/built-in entries");
        } catch (Exception e) {
            rat.println("Error downloading OUI: " + e.getMessage());
        }
    }

    private boolean downloadFromIeee() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(IEEE_OUI_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            if (conn.getResponseCode() != 200) return false;

            int count = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("(hex)")) {
                        String[] parts = line.split("\t+");
                        if (parts.length >= 2) {
                            String prefix = parts[0].trim().replace("-", "").toUpperCase();
                            String vendor  = parts[parts.length - 1].trim();
                            if (prefix.length() >= 6) {
                                ouiMap.put(prefix.substring(0, 6), vendor);
                                count++;
                            }
                        }
                    }
                    if (count % 5000 == 0 && count > 0)
                        rat.println("OUI: processed " + count + " entries...");
                }
            }
            rat.println("OUI total: " + count + " entries");
            return count > 10_000;
        } catch (Exception e) {
            rat.println("IEEE OUI download failed: " + e.getMessage());
            return false;
        }
    }

    private boolean downloadFromWireshark() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(WIRESHARK_OUI_URL).openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return false;

            int count = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.trim().isEmpty()) continue;
                    String[] parts = line.split("\t");
                    if (parts.length >= 2) {
                        String mac    = parts[0].replace(":", "").toUpperCase();
                        String vendor = parts[1].trim();
                        if (mac.contains("/")) mac = mac.split("/")[0];
                        if (mac.length() == 6) { ouiMap.put(mac, vendor); count++; }
                    }
                }
            }
            rat.println("Wireshark OUI entries: " + count);
            return count > 10_000;
        } catch (Exception e) {
            rat.println("Wireshark OUI download failed: " + e.getMessage());
            return false;
        }
    }

    // ── Cache save ───────────────────────────────────────────────────────────────
    private void saveCache() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cacheFile))) {
            oos.writeObject(new HashMap<>(ouiMap));
            rat.println("Saved " + ouiMap.size() + " OUI entries to cache");
        } catch (Exception e) {
            rat.println("Failed to save OUI cache: " + e.getMessage());
        }
    }

    // ── Lookup ───────────────────────────────────────────────────────────────────
    public String lookup(String bssid) {
        if (bssid == null || bssid.isEmpty()) return "Unknown";
        try {
            String clean = bssid.replace(":", "").replace("-", "").toUpperCase();
            if (clean.length() >= 6) {
                String v = ouiMap.get(clean.substring(0, 6));
                if (v != null) return v;
            }
        } catch (Exception e) {
            rat.println("OUI lookup error for " + bssid + ": " + e.getMessage());
        }
        return "Unknown";
    }

    public String getDetailedInfo(String bssid) {
        String vendor     = lookup(bssid);
        String deviceType = inferDeviceType(vendor);
        String ownerType  = inferOwnerType(vendor);
        return vendor + " | " + deviceType + " | " + ownerType;
    }

    private String inferDeviceType(String vendor) {
        if (vendor.contains("Cisco") || vendor.contains("Meraki")) return "Enterprise AP";
        if (vendor.contains("Ubiquiti"))   return "Prosumer AP";
        if (vendor.contains("Raspberry"))  return "SBC/Hobbyist";
        if (vendor.contains("TP-Link") || vendor.contains("D-Link") || vendor.contains("Netgear"))
            return "Consumer Router";
        if (vendor.contains("Apple"))  return "Apple Device";
        if (vendor.contains("Intel"))  return "Wi-Fi Card";
        if (vendor.contains("AzureWave") || vendor.contains("Ralink") || vendor.contains("Atheros"))
            return "Wi-Fi Module";
        return "Generic Wi-Fi Device";
    }

    private String inferOwnerType(String vendor) {
        if (vendor.contains("Cisco") || vendor.contains("Meraki") || vendor.contains("Aruba"))
            return "Enterprise";
        if (vendor.contains("Ubiquiti"))   return "Business/IT Pro";
        if (vendor.contains("TP-Link") || vendor.contains("D-Link") || vendor.contains("Netgear"))
            return "Home User";
        if (vendor.contains("Apple"))  return "Apple Ecosystem";
        if (vendor.contains("Google")) return "Google Ecosystem";
        if (vendor.contains("Xiaomi") || vendor.contains("Huawei")) return "Chinese OEM";
        return "Unknown";
    }

    public int getEntryCount() { return ouiMap.size(); }
}