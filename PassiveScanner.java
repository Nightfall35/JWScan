import org.pcap4j.core.*;
import org.pcap4j.packet.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * PassiveScanner — 802.11 frame capture via Npcap/pcap4j.
 *
 * FIXES APPLIED:
 * 1. Radiotap signal extraction now walks field offsets properly.
 * 2. Security classification extended: WPA/WPA2/WPA3/TKIP/WPS.
 * 3. SSID byte validation added.
 * 4. parseDeauth() correctly maps src/dst from 802.11 address fields.
 * 5. captureLoop() exception now logged with cause.
 * 6. Adapter filter accepts Microsoft WiFi Direct adapters.
 * 7. ChannelHopper wired in — started after handles open, stopped in stop().
 *    Channels are marked active every time a beacon is parsed so the smart
 *    hopper weights dwell time toward channels with real APs.
 */
public class PassiveScanner implements AutoCloseable {

    private final Rat rat;
    private final List<PcapHandle> handles = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    // ── CHANGE 1: hopper field ───────────────────────────────────────────────
    private ChannelHopper hopper = null;

    public PassiveScanner(Rat rat) {
        this.rat = rat;
    }

    public void start() {
        List<PcapNetworkInterface> ifaces = getWifiInterfaces();
        if (ifaces.isEmpty()) {
            rat.println("[SCANNER] No Wi-Fi interfaces found. Install Npcap and enable WinPcap-compatible mode.");
            return;
        }

        running = true;
        rat.println("[SCANNER] Starting on " + ifaces.size() + " interface(s):");

        for (PcapNetworkInterface nif : ifaces) {
            rat.println("  → " + nif.getName() + " | " + nif.getDescription());
            try {
                PcapHandle handle = nif.openLive(
                        65536,
                        PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                        10
                );
                handles.add(handle);
                final PcapNetworkInterface finalNif = nif;
                pool.submit(() -> captureLoop(handle, finalNif.getName()));

                // Arm deauther on first valid interface
                if (rat.getDeauther() == null) {
                    rat.enableDeauthOn(nif);
                }
            } catch (Exception e) {
                rat.println("[SCANNER] Failed to open " + nif.getName() + " → " + e.getMessage());
            }
        }

        // ── CHANGE 2: Start channel hopper on first interface ────────────────
        if (!ifaces.isEmpty()) {
            try {
                int     dwellMs  = readHopperConfig("dwell_ms",   150);
                boolean inc5GHz  = readHopperConfig("band_5ghz",    1) == 1;
                boolean inc6GHz  = readHopperConfig("band_6ghz",    0) == 1;
                String  stratStr = readHopperConfigStr("strategy", "SMART");

                hopper = new ChannelHopper(rat, ifaces.get(0).getName(),
                        dwellMs, inc5GHz, inc6GHz)
                        .withStrategy(ChannelHopper.Strategy.valueOf(stratStr));
                hopper.start();
            } catch (Exception e) {
                rat.println("[HOPPER] Could not start channel hopper: " + e.getMessage());
                hopper = null;
            }
        }
    }

    // ── Hopper config readers ────────────────────────────────────────────────
    private int readHopperConfig(String key, int defaultValue) {
        try {
            java.nio.file.Path f = java.nio.file.Paths.get("hopper_config.txt");
            if (!java.nio.file.Files.exists(f)) return defaultValue;
            for (String line : java.nio.file.Files.readAllLines(f)) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equals(key)) {
                    return Integer.parseInt(parts[1].trim());
                }
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private String readHopperConfigStr(String key, String defaultValue) {
        try {
            java.nio.file.Path f = java.nio.file.Paths.get("hopper_config.txt");
            if (!java.nio.file.Files.exists(f)) return defaultValue;
            for (String line : java.nio.file.Files.readAllLines(f)) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equals(key)) {
                    return parts[1].trim().toUpperCase();
                }
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    // ── Interface filter ─────────────────────────────────────────────────────
    private List<PcapNetworkInterface> getWifiInterfaces() {
        List<PcapNetworkInterface> list = new ArrayList<>();
        try {
            for (PcapNetworkInterface nif : Pcaps.findAllDevs()) {
                if (nif == null) continue;
                String name = nif.getName() != null ? nif.getName().toLowerCase() : "";
                String desc = nif.getDescription() != null ? nif.getDescription().toLowerCase() : "";

                if (name.contains("wlan")
                        || name.contains("wi-fi")
                        || desc.contains("wi-fi")
                        || desc.contains("wireless")
                        || desc.contains("802.11")
                        || desc.contains("wifi")
                        || name.contains("npcap")
                        || desc.contains("microsoft wi-fi direct")) {
                    list.add(nif);
                }
            }
        } catch (Exception e) {
            rat.println("[SCANNER] Interface enumeration failed: " + e.getMessage());
        }
        return list;
    }

    // ── Capture loop ─────────────────────────────────────────────────────────
    private void captureLoop(PcapHandle handle, String ifName) {
        try {
            handle.loop(-1, (PacketListener) packet -> {
                if (!running) return;

                RadiotapPacket radiotap = packet.get(RadiotapPacket.class);

                Packet payload = packet;
                while (payload.getPayload() != null) payload = payload.getPayload();
                if (!(payload instanceof UnknownPacket)) return;

                byte[] raw80211 = payload.getRawData();
                if (raw80211.length < 24) return;

                int signalDbm = extractSignal(radiotap);

                int fc      = raw80211[0] & 0xFF;
                int type    = (fc >> 2) & 0x3;
                int subtype = (fc >> 4) & 0xF;

                if (type != 0) return;

                switch (subtype) {
                    case 8, 5 -> parseBeacon(raw80211, signalDbm);
                    case 4    -> parseProbeReq(raw80211);
                    case 12   -> parseDeauth(raw80211);
                }
            });
        } catch (Exception e) {
            rat.println("[SCANNER] captureLoop error on " + ifName + ": "
                    + e.getMessage()
                    + (e.getCause() != null ? " (cause: " + e.getCause().getMessage() + ")" : ""));
        }
    }

    // ── Radiotap signal extraction ────────────────────────────────────────────
    private int extractSignal(RadiotapPacket radiotap) {
        if (radiotap == null) return -95;

        byte[] rt = radiotap.getRawData();
        if (rt.length < 8) return -95;

        int present = (rt[4] & 0xFF)
                | ((rt[5] & 0xFF) << 8)
                | ((rt[6] & 0xFF) << 16)
                | ((rt[7] & 0xFF) << 24);

        int[] fieldSizes  = {8, 1, 1, 1, 2, 2, 2, 2, 2, 1, 1, 1, 2, 1};
        int[] fieldAligns = {1, 1, 1, 1, 2, 2, 2, 2, 2, 1, 1, 1, 2, 1};
        int DBM_ANT_SIGNAL_BIT = 5;

        int offset = 8;
        for (int bit = 0; bit < 32; bit++) {
            if ((present & (1 << bit)) == 0) continue;
            if (bit >= fieldSizes.length) break;

            int align = fieldAligns[bit];
            if (align > 1 && offset % align != 0) {
                offset += align - (offset % align);
            }

            if (bit == DBM_ANT_SIGNAL_BIT) {
                if (offset < rt.length) {
                    int raw = rt[offset] & 0xFF;
                    return raw > 127 ? raw - 256 : raw;
                }
                break;
            }
            offset += fieldSizes[bit];
        }
        return -95;
    }

    // ── Beacon / Probe Response parser ────────────────────────────────────────
    private void parseBeacon(byte[] raw, int signalDbm) {
        Rat.AP ap = new Rat.AP();
        ap.bssid  = formatMac(raw, 10);
        ap.signal = signalDbm;

        boolean hasRsn  = false;
        boolean hasWpa  = false;
        boolean hasCcmp = false;
        boolean hasTkip = false;
        boolean hasWps  = false;

        int offset = 36;
        while (offset + 1 < raw.length) {
            int id  = raw[offset] & 0xFF;
            int len = (offset + 1 < raw.length) ? raw[offset + 1] & 0xFF : 0;
            if (offset + 2 + len > raw.length) break;

            switch (id) {
                case 0 -> {
                    if (len == 0) {
                        ap.ssid = "<hidden>";
                    } else {
                        byte[] ssidBytes = Arrays.copyOfRange(raw, offset + 2, offset + 2 + len);
                        if (isPrintableUtf8(ssidBytes)) {
                            String ssid = new String(ssidBytes,
                                    java.nio.charset.StandardCharsets.UTF_8).trim();
                            ap.ssid = ssid.isEmpty() ? "<hidden>" : ssid;
                        } else {
                            ap.ssid = "<hidden>";
                        }
                    }
                }
                case 3 -> { if (len >= 1) ap.channel = raw[offset + 2] & 0xFF; }
                case 48 -> {
                    hasRsn = true;
                    if (len >= 8) {
                        int rsn = offset + 2;
                        int pairwiseCount = (raw[rsn + 6] & 0xFF) | ((raw[rsn + 7] & 0xFF) << 8);
                        int pairwiseOff   = rsn + 8;
                        for (int i = 0; i < pairwiseCount && pairwiseOff + 4 <= offset + 2 + len; i++) {
                            int cipherType = raw[pairwiseOff + 3] & 0xFF;
                            if (cipherType == 4) hasCcmp = true;
                            if (cipherType == 2) hasTkip = true;
                            pairwiseOff += 4;
                        }
                    }
                }
                case 221 -> {
                    if (len >= 4) {
                        if ((raw[offset+2]&0xFF)==0x00 && (raw[offset+3]&0xFF)==0x50
                                && (raw[offset+4]&0xFF)==0xF2 && (raw[offset+5]&0xFF)==0x01)
                            hasWpa = true;
                        if ((raw[offset+2]&0xFF)==0x00 && (raw[offset+3]&0xFF)==0x50
                                && (raw[offset+4]&0xFF)==0xF2 && (raw[offset+5]&0xFF)==0x04)
                            hasWps = true;
                    }
                }
            }
            offset += 2 + len;
        }

        if (hasRsn) {
            if (hasCcmp && !hasTkip)  ap.security = hasWps ? "WPA2 (WPS!)" : "WPA2";
            else if (hasCcmp)          ap.security = "WPA2/WPA3";
            else                       ap.security = "WPA2-TKIP";
        } else if (hasWpa) {
            ap.security = hasTkip ? "WPA-TKIP" : "WPA";
        } else {
            ap.security = "OPEN";
        }

        // ── CHANGE 3: mark channel active in hopper ──────────────────────────
        if (hopper != null && ap.channel > 0) {
            hopper.markChannelActive(ap.channel);
        }

        rat.onAccessPointDiscovered(ap);
    }

    // ── Probe Request parser ──────────────────────────────────────────────────
    private void parseProbeReq(byte[] raw) {
        String mac = formatMac(raw, 10);
        int offset = 24;
        while (offset + 1 < raw.length) {
            int id  = raw[offset] & 0xFF;
            int len = raw[offset + 1] & 0xFF;
            if (id == 0) {
                String ssid = new String(raw, offset + 2,
                        Math.min(len, raw.length - offset - 2),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                rat.onClientProbe(mac, ssid.isEmpty() ? "<any>" : ssid);
                return;
            }
            offset += 2 + Math.max(1, len);
        }
        rat.onClientProbe(mac, "<any>");
    }

    // ── Deauth parser ─────────────────────────────────────────────────────────
    private void parseDeauth(byte[] raw) {
        if (raw.length < 22) return;
        String dst = formatMac(raw, 4);
        String src = formatMac(raw, 10);
        rat.onDeauthAttack(src, dst, 1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private String formatMac(byte[] raw, int offset) {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                raw[offset], raw[offset+1], raw[offset+2],
                raw[offset+3], raw[offset+4], raw[offset+5]);
    }

    private boolean isPrintableUtf8(byte[] bytes) {
        try {
            String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return s.chars().allMatch(c -> c >= 0x20 && c < 0xFFFD);
        } catch (Exception e) {
            return false;
        }
    }

    // ── CHANGE 4: stop hopper in stop() ──────────────────────────────────────
    public void stop() {
        running = false;
        if (hopper != null) hopper.stop();
        for (PcapHandle h : handles) {
            try { h.breakLoop(); } catch (Exception ignored) {}
            try { h.close();     } catch (Exception ignored) {}
        }
        pool.shutdownNow();
    }

    @Override
    public void close() { stop(); }
}