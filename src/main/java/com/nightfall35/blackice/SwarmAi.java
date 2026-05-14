package com.nightfall35.blackice;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
 * SwarmAi — Evil twin / rogue AP detection engine.
 *
 * FIX: Original logic marked the FIRST-SEEN BSSID as "legitimate" which
 * caused false positives constantly (rogue seen first → real AP flagged).
 *
 * New approach:
 *   - Build a frequency map: SSID → (BSSID → seen count)
 *   - The BSSID with the HIGHEST observation count is "legitimate"
 *   - Only flag evil twins after MIN_OBSERVATIONS to avoid cold-start noise
 *   - Deduplicate alerts with alreadyNuked set (unchanged)
 *   - evilTwinDetected() lives ONLY in Rat.java — SwarmAi delegates to it
 *     instead of duplicating the logic and calling Deauther directly
 */
public class SwarmAi {

    // Minimum times we must see an SSID before trusting our "legitimate" pick
    private static final int MIN_OBSERVATIONS = 3;

    // SSID → (BSSID → observation count)
    private final Map<String, Map<String, Integer>> apObservations = new ConcurrentHashMap<>();

    // SSID → BSSID we currently consider legitimate (highest count)
    private final Map<String, String> legitimateAPs = new ConcurrentHashMap<>();

    // BSSIDs we already fired an alert for — avoids repeat storms
    private final Set<String> alreadyNuked = ConcurrentHashMap.newKeySet();

    // Last time we saw a BSSID — used for stale-entry cleanup
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    private final Rat rat;
    private final ScheduledExecutorService cleaner;

    public SwarmAi(Rat rat) {
        this.rat     = rat;
        this.cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(this::cleanupOldEntries, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Called every time a beacon / probe-response is seen.
     */
    public void seeAP(String bssid, String ssid, int channel, String security) {
        if (bssid == null || bssid.isEmpty() || ssid == null || ssid.isEmpty()) return;
        if (ssid.equals("<hidden>")) return; // hidden SSIDs can't be evil-twin matched

        lastSeen.put(bssid, System.currentTimeMillis());

        String cleanSsid = ssid.trim();

        // Increment observation count for this BSSID under this SSID
        Map<String, Integer> counts = apObservations.computeIfAbsent(cleanSsid, k -> new ConcurrentHashMap<>());
        int newCount = counts.merge(bssid, 1, Integer::sum);

        // Total observations across all BSSIDs for this SSID
        int totalObs = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalObs < MIN_OBSERVATIONS) return; // not enough data yet

        // Elect the BSSID with the highest count as legitimate
        String topBssid = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(bssid);

        String previousLegit = legitimateAPs.put(cleanSsid, topBssid);

        // Log when we first lock in a legitimate AP
        if (previousLegit == null) {
            rat.printlnAlert("LEGIT AP CONFIRMED → \"" + cleanSsid + "\" = " + topBssid
                    + " (" + counts.get(topBssid) + " obs)");
        }

        // Any OTHER BSSID broadcasting the same SSID with meaningful count → evil twin
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String candidateBssid = entry.getKey();
            int    candidateCount = entry.getValue();

            if (candidateBssid.equals(topBssid)) continue;         // this IS the legit one
            if (candidateCount < 2) continue;                       // seen only once → noise
            if (!alreadyNuked.add(cleanSsid + "::" + candidateBssid)) continue; // already alerted

            // Delegate entirely to Rat — no direct Deauther call here
            rat.evilTwinDetected(cleanSsid, topBssid, candidateBssid, channel);
        }
    }

    /**
     * Remove entries for BSSIDs not seen in the last 30 minutes.
     * Also clears them from alreadyNuked so a returning rogue gets re-flagged.
     */
    private void cleanupOldEntries() {
        long cutoff = System.currentTimeMillis() - 30 * 60 * 1000L;

        Set<String> staleBssids = new HashSet<>();
        lastSeen.entrySet().removeIf(e -> {
            if (e.getValue() < cutoff) {
                staleBssids.add(e.getKey());
                return true;
            }
            return false;
        });

        // Remove stale BSSIDs from observation maps and alreadyNuked
        for (Map<String, Integer> counts : apObservations.values()) {
            staleBssids.forEach(counts::remove);
        }
        // alreadyNuked keys are "ssid::bssid" — remove any entry whose bssid suffix is stale
        alreadyNuked.removeIf(key -> {
            int sep = key.lastIndexOf("::");
            return sep >= 0 && staleBssids.contains(key.substring(sep + 2));
        });

        // Remove SSIDs that now have no remaining observations
        apObservations.entrySet().removeIf(e -> e.getValue().isEmpty());
        legitimateAPs.entrySet().removeIf(e -> !apObservations.containsKey(e.getKey()));
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }
}