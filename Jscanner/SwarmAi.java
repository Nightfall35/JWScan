import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;


public class SwarmAi {
    // SSID → Set of legitimate BSSIDs (first seen wins)
    private final Map<String, Set<String>> legitimateAPs = new ConcurrentHashMap<>();
    
    // Last time we saw a specific BSSID (for cleanup)
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    
    private final Set<String> alreadyNuked = ConcurrentHashMap.newKeySet();

    
    private final Rat rat;
    private final ScheduledExecutorService cleaner;

    public SwarmAi(Rat rat) {
        this.rat = rat;
        this.cleaner = Executors.newSingleThreadScheduledExecutor();
        
        // Clean old entries every 5 minutes ( This should fix over clogging )
        cleaner.scheduleAtFixedRate(this::cleanupOldEntries, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Will Called every time a new AP is discovered
     */
    public void seeAP(String bssid, String ssid, int channel, String security) {
        if (bssid == null || bssid.isEmpty() || ssid == null) return;

        lastSeen.put(bssid, System.currentTimeMillis());

        // Normalize SSID (trim + case-sensitive for now): NOTE!! Find more efficient method if possible 
        String cleanSsid = ssid.trim();

        // First time we see this SSID → mark current BSSID as legitimate (Although this leaves a lot of room for error)
        Set<String> knownGood = legitimateAPs.computeIfAbsent(cleanSsid, k -> new CopyOnWriteArraySet<>());
        
        if (knownGood.isEmpty()) {
            knownGood.add(bssid);
            rat.printlnAlert("LEGIT AP REGISTERED → " + cleanSsid + " = " + bssid);
        } 
        // We already know this SSID from another BSSID → EVIL TWIN!
        else if (!knownGood.contains(bssid)) {
            if (alreadyNuked.add(bssid)) {
                rat.evilTwinDetected(cleanSsid, knownGood.iterator().next(), bssid, channel);
            }
        }
    }

    /**
     * Called from Rat.java when evil twin is found 
     */
    public void evilTwinDetected(String ssid, String realBssid, String fakeBssid, int channel) {
        rat.printlnStrongAlert("EVIL TWIN / ROGUE AP DETECTED");
        rat.printlnStrongAlert("    SSID: " + ssid);
        rat.printlnStrongAlert("    LEGITIMATE → " + realBssid);
        rat.printlnStrongAlert("    FAKE / ROGUE → " + fakeBssid + " (channel " + channel + ")");
        rat.printlnStrongAlert("    AUTO COUNTERMEASURE: DEAUTH FLOOD INITIATED");
        rat.soundBell();

        // AUTO-NUKE THE ROGUE AP — kill everyone connected to it 
	Deauther d =rat.getDeauther();
        if (d != null) {
            new Thread(() -> {
		
		if(d!=null) {
		    d.deauth("FF:FF:FF:FF:FF:FF", fakeBssid, 0); // broadcast client = everyone	
		}
            }).start();
        }
    }

    private void cleanupOldEntries() {
        long cutoff = System.currentTimeMillis() - 30 * 60 * 1000; // 30 minutes??
        lastSeen.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }
}