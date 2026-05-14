package com.nightfall35.blackice;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ChannelHopper — rotates the Wi-Fi adapter through 802.11 channels
 * so PassiveScanner captures beacons from ALL APs, not just the current channel.
 *
 * HOW IT WORKS:
 *   On Windows  → uses netsh to set the channel (requires Npcap + monitor mode)
 *   On Linux    → uses iwconfig / iw (standard tools, no extra deps)
 *   Fallback    → logs a warning; scanning still works on the fixed channel
 *
 * DWELL TIME:
 *   Each channel is visited for a configurable dwell period (default 150ms).
 *   150ms is enough to catch at least one beacon (APs beacon every 100ms).
 *   Shorter = faster full sweep but may miss low-power APs.
 *   Longer  = more reliable capture but slower full-cycle time.
 *
 * SMART HOPPING:
 *   After the first full sweep, channels with detected APs get 2× dwell time.
 *   Channels with no APs get 0.5× dwell time (still visited to catch new APs).
 *   This biases time toward where the actual traffic is.
 *
 * CHANNEL SETS:
 *   2.4 GHz: 1–13 (14 Japan only, skipped by default)
 *   5 GHz:   36,40,44,48,52,56,60,64,100,104,108,112,116,120,124,128,132,136,140,149,153,157,161,165
 *   6 GHz:   1,5,9,13,17,21,25,29,33,37,41,45,49,53,57,61,65,69,73,77,81,85,89,93 (Wi-Fi 6E, optional)
 *
 * INTEGRATION:
 *   1. In PassiveScanner.start(), after opening the handle, call:
 *        hopper = new ChannelHopper(rat, interfaceName);
 *        hopper.start();
 *   2. When PassiveScanner detects an AP on a channel, call:
 *        hopper.markChannelActive(ap.channel);
 *   3. In PassiveScanner.stop():
 *        if (hopper != null) hopper.stop();
 *
 * LEGAL NOTE:
 *   Channel hopping is passive — it only listens, never transmits.
 *   Legal in all jurisdictions for authorized monitoring,helps us avoid problems.
 */
public class ChannelHopper {

    // ── Configuration ────────────────────────────────────────────────────────
    /** Milliseconds to stay on each channel during the first sweep. */
    public static final int DEFAULT_DWELL_MS = 150;

    /** Dwell multiplier for channels with known APs. */
    private static final double ACTIVE_MULTIPLIER = 2.0;

    /** Dwell multiplier for channels with no known APs (after first sweep). */
    private static final double QUIET_MULTIPLIER = 0.5;

    /** After this many full sweeps, apply smart dwell weighting. */
    private static final int SMART_AFTER_SWEEPS = 1;

    // ── 2.4 GHz channels ─────────────────────────────────────────────────────
    public static final int[] CHANNELS_24 = {1,2,3,4,5,6,7,8,9,10,11,12,13};

    // ── 5 GHz channels (UNII-1 through UNII-4) ───────────────────────────────
    public static final int[] CHANNELS_5 = {
        36,40,44,48,           // UNII-1
        52,56,60,64,           // UNII-2A (DFS)
        100,104,108,112,       // UNII-2C (DFS)
        116,120,124,128,       // UNII-2C (DFS)
        132,136,140,           // UNII-2C (DFS)
        149,153,157,161,165    // UNII-3
    };

    // ── 6 GHz channels (Wi-Fi 6E, optional) ──────────────────────────────────
    public static final int[] CHANNELS_6 = {
        1,5,9,13,17,21,25,29,33,37,41,45,49,53,57,61,65,69,73,77,81,85,89,93
    };

    // ── State ────────────────────────────────────────────────────────────────
    private final Rat    rat;
    private final String ifaceName;  // e.g. "wlan0" or "Wi-Fi"
    private final OS     os;

    private final int[]  channels;         // active channel list
    private final int    dwellMs;
    private final AtomicBoolean running    = new AtomicBoolean(false);
    private final AtomicInteger currentCh  = new AtomicInteger(0);
    private final Set<Integer>  activeChs  = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> apLastSeen = new ConcurrentHashMap<>();

    private Thread       hopThread;
    private int          sweepCount  = 0;
    private int          hopCount    = 0;
    private long         startTime   = 0;

    // Strategy modes
    public enum Strategy {
        SEQUENTIAL,   // 1,2,3,...,13,36,40,... — full sweep in order
        INTERLEAVED,  // 1,6,11,36,40,... — non-overlapping 2.4GHz first
        SMART,        // weighted dwell after first sweep
        CUSTOM        // user-supplied channel list
    }
    private Strategy strategy = Strategy.SMART;

    // ── OS detection ─────────────────────────────────────────────────────────
    private enum OS { WINDOWS, LINUX, MAC, UNKNOWN }

    // ── Constructor ──────────────────────────────────────────────────────────
    public ChannelHopper(Rat rat, String interfaceName) {
        this(rat, interfaceName, DEFAULT_DWELL_MS, false, false);
    }

    public ChannelHopper(Rat rat, String interfaceName, int dwellMs,
                          boolean include5GHz, boolean include6GHz) {
        this.rat       = rat;
        this.ifaceName = interfaceName;
        this.dwellMs   = dwellMs;
        this.os        = detectOs();
        this.channels  = buildChannelList(include5GHz, include6GHz);

        rat.println("[HOPPER] Interface: " + interfaceName);
        rat.println("[HOPPER] OS: " + os);
        rat.println("[HOPPER] Channels: " + channels.length + " total"
                + " | Dwell: " + dwellMs + "ms"
                + " | Full cycle: ~" + (channels.length * dwellMs / 1000) + "s");
        rat.println("[HOPPER] Strategy: " + strategy);
    }

    // ── Channel list builder ─────────────────────────────────────────────────
    private int[] buildChannelList(boolean inc5, boolean inc6) {
        List<Integer> list = new ArrayList<>();
        for (int ch : CHANNELS_24) list.add(ch);
        if (inc5) for (int ch : CHANNELS_5) list.add(ch);
        if (inc6) for (int ch : CHANNELS_6) list.add(ch);
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Use a custom channel list (e.g. only non-overlapping 2.4GHz). */
    public ChannelHopper withStrategy(Strategy s) {
        this.strategy = s;
        return this;
    }

    // ── Start / stop ─────────────────────────────────────────────────────────
    public void start() {
        if (running.getAndSet(true)) return;
        startTime = System.currentTimeMillis();

        // Verify we can actually set channels before starting the loop
        boolean canHop = testChannelSet(channels[0]);
        if (!canHop) {
            rat.println("[HOPPER] ⚠ Channel switching unavailable on this adapter.");
            rat.println("[HOPPER]   On Windows: ensure Npcap is in monitor mode and adapter supports it.");
            rat.println("[HOPPER]   On Linux:   run as root; ensure 'iw' or 'iwconfig' is installed.");
            rat.println("[HOPPER]   Passive scanning continues on the adapter's current fixed channel.");
            running.set(false);
            return;
        }

        hopThread = new Thread(this::hopLoop, "ChannelHopper");
        hopThread.setDaemon(true);
        hopThread.start();
        rat.println("[HOPPER] ✓ Channel hopping STARTED — " + channels.length + " channels");
    }

    public void stop() {
        running.set(false);
        if (hopThread != null) hopThread.interrupt();
        rat.println("[HOPPER] Stopped. Total hops: " + hopCount
                + " | Sweeps: " + sweepCount
                + " | Active channels: " + activeChs.size()
                + " | Uptime: " + ((System.currentTimeMillis() - startTime) / 1000) + "s");
    }

    // ── Main hop loop ─────────────────────────────────────────────────────────
    private void hopLoop() {
        int[] seq = buildSequence();
        int   idx = 0;

        while (running.get()) {
            int ch = seq[idx];
            currentCh.set(ch);

            boolean ok = setChannel(ch);
            if (!ok) {
                rat.printlnDebug("[HOPPER] Failed to set channel " + ch);
            }

            int dwell = computeDwell(ch);
            try {
                Thread.sleep(dwell);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            hopCount++;
            idx++;
            if (idx >= seq.length) {
                idx = 0;
                sweepCount++;
                // Rebuild sequence after each sweep so smart weights update
                if (strategy == Strategy.SMART && sweepCount >= SMART_AFTER_SWEEPS) {
                    seq = buildSmartSequence();
                }
                rat.printlnDebug("[HOPPER] Sweep #" + sweepCount
                        + " complete | Active channels: " + activeChs
                        + " | Hops: " + hopCount);
            }
        }
    }

    // ── Sequence builders ────────────────────────────────────────────────────
    private int[] buildSequence() {
        return switch (strategy) {
            case INTERLEAVED -> buildInterleavedSequence();
            case SMART       -> channels.clone(); // start sequential, switch after first sweep
            default          -> channels.clone();
        };
    }

    /**
     * Interleaved: visits non-overlapping 2.4GHz channels first (1, 6, 11),
     * then fills in the rest, then 5GHz. Reduces co-channel interference
     * during the scan and catches the most common APs fastest.
     */
    private int[] buildInterleavedSequence() {
        List<Integer> priority = new ArrayList<>(Arrays.asList(1, 6, 11));
        for (int ch : channels) {
            if (ch != 1 && ch != 6 && ch != 11) priority.add(ch);
        }
        return priority.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Smart sequence: active channels get visited multiple times per cycle.
     * Channels with APs seen in the last 5 minutes are repeated proportionally
     * to their AP count. Quiet channels appear once.
     */
    private int[] buildSmartSequence() {
        long now = System.currentTimeMillis();
        long cutoff = now - 5 * 60_000;
        Map<Integer, Integer> apCount = new HashMap<>();

        for (Map.Entry<Integer, Long> e : apLastSeen.entrySet()) {
            if (e.getValue() > cutoff) {
                apCount.merge(e.getKey(), 1, Integer::sum);
            }
        }

        List<Integer> seq = new ArrayList<>();
        for (int ch : channels) {
            int count = apCount.getOrDefault(ch, 0);
            int repeats = count > 0 ? Math.min(count, 3) : 1; // max 3× for busy channels
            for (int r = 0; r < repeats; r++) seq.add(ch);
        }

        // Shuffle within each band to avoid predictable patterns
        Collections.shuffle(seq.subList(0, Math.min(13, seq.size())));

        rat.printlnDebug("[HOPPER] Smart sequence rebuilt: " + seq.size() + " slots for "
                + channels.length + " channels | Active: " + apCount.keySet());

        return seq.stream().mapToInt(Integer::intValue).toArray();
    }

    // ── Dwell time computation ────────────────────────────────────────────────
    private int computeDwell(int ch) {
        if (sweepCount < SMART_AFTER_SWEEPS) return dwellMs;
        boolean isActive = activeChs.contains(ch);
        double  mult     = isActive ? ACTIVE_MULTIPLIER : QUIET_MULTIPLIER;
        return (int) Math.max(50, dwellMs * mult); // minimum 50ms always
    }

    // ── Public: called by PassiveScanner when an AP is seen ──────────────────
    /**
     * Call this from PassiveScanner.parseBeacon() when a beacon is decoded:
     *   hopper.markChannelActive(ap.channel);
     *
     * This feeds the smart hopper's weighting so it spends more time
     * on channels where APs are actually transmitting.
     */
    public void markChannelActive(int channel) {
        if (channel <= 0) return;
        activeChs.add(channel);
        apLastSeen.put(channel, System.currentTimeMillis());
    }

    /** Returns the channel the adapter is currently tuned to. */
    public int getCurrentChannel() { return currentCh.get(); }

    /** Returns a snapshot of channels where APs have been detected. */
    public Set<Integer> getActiveChannels() { return Collections.unmodifiableSet(activeChs); }

    /** Returns total number of channel hops performed. */
    public int getHopCount() { return hopCount; }

    /** Returns total number of full channel sweeps completed. */
    public int getSweepCount() { return sweepCount; }

    // ── Channel set command ───────────────────────────────────────────────────
    /**
     * Sets the adapter to the given channel.
     * Returns true if the command succeeded (exit code 0), false otherwise.
     *
     * Windows: netsh wlan set channel <ifaceName> <ch>
     *          (Npcap monitor mode required; not all drivers support this)
     *
     * Linux:   iw dev <ifaceName> set channel <ch>   (preferred)
     *          iwconfig <ifaceName> channel <ch>       (fallback)
     */
    private boolean setChannel(int ch) {
        try {
            String[] cmd = buildSetChannelCommand(ch);
            if (cmd == null) return false;

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // Drain output so the process doesn't block
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    rat.printlnDebug("[HOPPER] setChannel(" + ch + "): " + line);
                }
            }

            boolean done = proc.waitFor(500, TimeUnit.MILLISECONDS);
            if (!done) { proc.destroyForcibly(); return false; }
            return proc.exitValue() == 0;

        } catch (Exception e) {
            rat.printlnDebug("[HOPPER] setChannel(" + ch + ") exception: " + e.getMessage());
            return false;
        }
    }

    private boolean testChannelSet(int ch) {
        boolean ok = setChannel(ch);
        if (ok) rat.println("[HOPPER] Channel set test: OK (ch " + ch + ")");
        else    rat.println("[HOPPER] Channel set test: FAILED (ch " + ch + ")");
        return ok;
    }

    private String[] buildSetChannelCommand(int ch) {
        return switch (os) {
            case WINDOWS -> new String[]{
                "netsh", "wlan", "set", "channel",
                "interface=" + ifaceName,
                "channel=" + ch
            };
            case LINUX -> {
                // Try 'iw' first (modern), fall back to 'iwconfig'
                if (commandExists("iw")) {
                    yield new String[]{"iw", "dev", ifaceName, "set", "channel", String.valueOf(ch)};
                } else {
                    yield new String[]{"iwconfig", ifaceName, "channel", String.valueOf(ch)};
                }
            }
            case MAC -> new String[]{
                "airport", ifaceName, "channel", String.valueOf(ch)
            };
            default -> null;
        };
    }

    private boolean commandExists(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(200, TimeUnit.MILLISECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── OS detection ─────────────────────────────────────────────────────────
    private OS detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win"))   return OS.WINDOWS;
        if (name.contains("linux")) return OS.LINUX;
        if (name.contains("mac"))   return OS.MAC;
        return OS.UNKNOWN;
    }

    // ── Status JSON (for /api/v1/stats or a future /api/v1/hopper endpoint) ──
    /**
     * Returns a JSON object describing current hopper state.
     * Add this to Rat.getStatsJson() or expose it via EnterpriseApiServer
     * at GET /api/v1/hopper.
     */
    public String toJson() {
        return "{\"running\":" + running.get()
            + ",\"currentChannel\":" + currentCh.get()
            + ",\"sweepCount\":" + sweepCount
            + ",\"hopCount\":" + hopCount
            + ",\"channelCount\":" + channels.length
            + ",\"activeChannels\":" + activeChs.size()
            + ",\"dwellMs\":" + dwellMs
            + ",\"strategy\":\"" + strategy + "\""
            + ",\"uptimeSeconds\":" + ((System.currentTimeMillis() - startTime) / 1000)
            + "}";
    }

    // ── toString ──────────────────────────────────────────────────────────────
    @Override
    public String toString() {
        return "ChannelHopper[iface=" + ifaceName
            + ", ch=" + currentCh.get()
            + ", sweeps=" + sweepCount
            + ", active=" + activeChs + "]";
    }
}
