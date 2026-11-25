import org.pcap4j.core.*;
import org.pcap4j.packet.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
 *    Opt-in and off by default: enable with --hop / --channel-hop or
 *    hopper_config.txt (enabled=1). Dwell time, 5/6 GHz bands, and
 *    strategy are read from hopper_config.txt. Channels are marked
 *    active every time a beacon is parsed so the smart hopper weights
 *    dwell time toward channels with real APs.
 * 8. Optional PCAP export via PcapHandle.dumpOpen / PcapDumper. Off by default;
 *    enable with --pcap or pcap_config.txt (enabled=1). Writes timestamped
 *    captures/blackice_<timestamp>_<adapter>.pcap for Wireshark.
 * 9. Always-on passive WPA/WPA2 4-way handshake detection: EAPOL-Key frames
 *    in unencrypted 802.11 data are classified as messages 1/4–4/4 by Key
 *    Information flags. Per-(BSSID, client) state tracks completion only
 *    (no nonces, MICs, or key data are stored). A completed exchange is
 *    counted on the AP and logged.
 */
public class PassiveScanner implements AutoCloseable {

    private final Rat rat;
    private final List<PcapHandle> handles = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    // ── CHANGE 1: hopper field ───────────────────────────────────────────────
    private ChannelHopper hopper = null;

    // ── PCAP export (off by default) ─────────────────────────────────────────
    private boolean pcapExportEnabled = false;
    private String  pcapDir = "captures";
    private final List<PcapSink> pcapSinks = new ArrayList<>();
    private final AtomicBoolean pcapDumpWarned = new AtomicBoolean(false);
    private final AtomicLong eapolFrameCount = new AtomicLong();

    // Passive 4-way handshake sessions: "BSSID|STA" → messages seen.
    // Only a bit mask and timestamp are kept — never nonces, MICs, or key data.
    private final ConcurrentHashMap<String, HsSession> hsSessions = new ConcurrentHashMap<>();
    private static final long HS_SESSION_TTL_MS = 15_000L;

    private static final class HsSession {
        int mask;
        long lastMs;
    }

    private static final class PcapSink {
        final PcapDumper dumper;
        final String path;
        final AtomicLong packets = new AtomicLong();
        PcapSink(PcapDumper dumper, String path) {
            this.dumper = dumper;
            this.path = path;
        }
    }

    public PassiveScanner(Rat rat) {
        this.rat = rat;
    }

    public void start() {
        List<PcapNetworkInterface> ifaces = getWifiInterfaces();
        if (ifaces.isEmpty()) {
            if (Rat.isLinuxOs()) {
                rat.println("[SCANNER] No Wi-Fi interfaces found. On Linux: install libpcap0.8, run as root, and put a wireless adapter in monitor mode (wlan*, wlp*, wlx*).");
            } else {
                rat.println("[SCANNER] No Wi-Fi interfaces found. Install Npcap and enable WinPcap-compatible mode.");
            }
            return;
        }

        running = true;
        pcapExportEnabled = rat.isPcapExportCliEnabled() || readPcapConfigInt("enabled", 0) == 1;
        pcapDir = readPcapConfigStr("dir", "captures");
        if (pcapExportEnabled) {
            try {
                Files.createDirectories(Paths.get(pcapDir));
            } catch (Exception e) {
                rat.println("[PCAP] Could not create dump directory '" + pcapDir + "': " + e.getMessage());
                pcapExportEnabled = false;
            }
        }

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
                final PcapSink sink = pcapExportEnabled ? openPcapDumper(handle, finalNif.getName()) : null;
                pool.submit(() -> captureLoop(handle, finalNif.getName(), sink));

                // Arm deauther on first valid interface
                if (rat.getDeauther() == null) {
                    rat.enableDeauthOn(nif);
                }
            } catch (Exception e) {
                rat.println("[SCANNER] Failed to open " + nif.getName() + " → " + e.getMessage());
            }
        }

        // Start channel hopper on first interface (opt-in, off by default)
        boolean hopEnabled = rat.isChannelHopCliEnabled()
                || readHopperConfig("enabled", 0) == 1;
        if (hopEnabled) {
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
        } else {
            rat.println("[HOPPER] Channel hopping off (enable with --hop or hopper_config.txt enabled=1)");
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

    // ── PCAP export config ───────────────────────────────────────────────────
    private int readPcapConfigInt(String key, int defaultValue) {
        String raw = readPcapConfigRaw(key);
        if (raw == null) return defaultValue;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return defaultValue; }
    }

    private String readPcapConfigStr(String key, String defaultValue) {
        String raw = readPcapConfigRaw(key);
        return raw != null ? raw : defaultValue;
    }

    private String readPcapConfigRaw(String key) {
        try {
            Path f = Paths.get("pcap_config.txt");
            if (!Files.exists(f)) return null;
            for (String line : Files.readAllLines(f)) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equals(key)) {
                    return parts[1].trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private PcapSink openPcapDumper(PcapHandle handle, String ifName) {
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeIf = ifName.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safeIf.isEmpty()) safeIf = "iface";
            if (safeIf.length() > 48) safeIf = safeIf.substring(0, 48);
            Path file = Paths.get(pcapDir, "blackice_" + ts + "_" + safeIf + ".pcap");
            PcapDumper dumper = handle.dumpOpen(file.toString());
            PcapSink sink = new PcapSink(dumper, file.toAbsolutePath().toString());
            synchronized (pcapSinks) { pcapSinks.add(sink); }
            rat.println("[PCAP] Dump started → " + sink.path);
            return sink;
        } catch (Exception e) {
            rat.println("[PCAP] Failed to open dump file: " + e.getMessage());
            return null;
        }
    }

    private void closePcapDumpers() {
        synchronized (pcapSinks) {
            for (PcapSink sink : pcapSinks) {
                closeOneSink(sink);
            }
            pcapSinks.clear();
        }
    }

    private void closeOneSink(PcapSink sink) {
        if (sink == null || sink.dumper == null) return;
        synchronized (sink) {
            try {
                if (sink.dumper.isOpen()) {
                    try { sink.dumper.flush(); } catch (Exception ignored) {}
                    sink.dumper.close();
                    rat.println("[PCAP] Dump stopped → " + sink.path
                            + " (" + sink.packets.get() + " packets)");
                }
            } catch (Exception e) {
                rat.println("[PCAP] Error closing dump file: " + e.getMessage());
            }
        }
    }

    // ── Interface filter ─────────────────────────────────────────────────────
    private List<PcapNetworkInterface> getWifiInterfaces() {
        List<PcapNetworkInterface> list = new ArrayList<>();
        try {
            for (PcapNetworkInterface nif : Pcaps.findAllDevs()) {
                if (nif == null) continue;
                String name = nif.getName() != null ? nif.getName().toLowerCase() : "";
                String desc = nif.getDescription() != null ? nif.getDescription().toLowerCase() : "";

                // Windows: "Wi-Fi" / Npcap descriptions. Linux / Pi: wlan0,
                // wlan0mon, wlp3s0 (predictable names), wlx… (USB adapters), mon0.
                if (name.contains("wlan")
                        || name.startsWith("wlp")
                        || name.startsWith("wlx")
                        || name.startsWith("mon")
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
    private void captureLoop(PcapHandle handle, String ifName, PcapSink sink) {
        try {
            handle.loop(-1, (PacketListener) packet -> {
                if (sink != null) {
                    try {
                        sink.dumper.dump(packet);
                        if (sink.packets.incrementAndGet() % 50 == 0) {
                            sink.dumper.flush();
                        }
                    } catch (Exception e) {
                        if (pcapDumpWarned.compareAndSet(false, true)) {
                            rat.println("[PCAP] Dump write failed: " + e.getMessage()
                                    + " (further errors suppressed)");
                        }
                    }
                }

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

                if (type == 2) {
                    parseDataFrame(raw80211);
                    return;
                }
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
        } finally {
            closeOneSink(sink);
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

    // ── Passive WPA/WPA2 4-way handshake detection (observe only) ────────────
    // Looks at unencrypted 802.11 data frames for LLC/SNAP EtherType 0x888E
    // (EAPOL) and classifies EAPOL-Key Key Information flags as messages 1–4.
    // Group-key handshakes, encrypted frames, and non-Key EAPOL are ignored.
    // No nonces, MICs, or key material are copied or stored.
    private void parseDataFrame(byte[] raw) {
        String[] macs = new String[2];
        int msg = inspectEapolHandshake(raw, macs);
        if (msg <= 0) return;

        String bssid = macs[0];
        String sta   = macs[1];
        if (bssid == null || sta == null) return;

        long now = System.currentTimeMillis();
        String key = bssid + "|" + sta;
        HsSession st = hsSessions.compute(key, (k, existing) -> {
            if (existing == null || now - existing.lastMs > HS_SESSION_TTL_MS) {
                return new HsSession();
            }
            return existing;
        });

        synchronized (st) {
            // A new message-1 starts a fresh exchange (rekey or new association).
            if (msg == 1 && st.mask != 0) {
                st.mask = 0;
            }
            st.mask |= (1 << (msg - 1));
            st.lastMs = now;
            // Complete only when all four messages of this session were seen.
            if (st.mask == 0x0F) {
                st.mask = 0;
                rat.onHandshakeComplete(bssid, sta);
            }
        }

        if (eapolFrameCount.incrementAndGet() % 128 == 0) {
            evictStaleHandshakeSessions(now);
        }
    }

    /**
     * Inspect an 802.11 data frame for a pairwise EAPOL-Key 4-way message.
     * @return message number 1–4, or 0 if this is not a 4-way EAPOL-Key frame
     * @param macs if length >= 2, filled with [BSSID, STA] on a hit
     */
    static int inspectEapolHandshake(byte[] raw, String[] macs) {
        if (raw == null || raw.length < 24 + 8 + 7) return 0;

        int fc0 = raw[0] & 0xFF;
        int fc1 = raw[1] & 0xFF;
        int type    = (fc0 >> 2) & 0x3;
        int subtype = (fc0 >> 4) & 0xF;
        if (type != 2) return 0;

        // Protected bit set → payload is encrypted; handshake frames are not.
        if ((fc1 & 0x40) != 0) return 0;

        boolean toDs   = (fc1 & 0x01) != 0;
        boolean fromDs = (fc1 & 0x02) != 0;
        boolean qos    = (subtype & 0x08) != 0;
        boolean order  = (fc1 & 0x80) != 0;

        // 4-address (WDS) frames are not used for the 4-way handshake.
        if (toDs && fromDs) return 0;

        int hdr = 24;
        if (qos) hdr += 2;
        if (order && qos) hdr += 4; // HT Control when Order is set on a QoS data frame
        if (hdr + 8 + 7 > raw.length) return 0;

        // QoS A-MSDU present (bit 7 of QoS Control) is not an EAPOL frame.
        if (qos) {
            int qosCtrl = (raw[24] & 0xFF) | ((raw[25] & 0xFF) << 8);
            if ((qosCtrl & 0x0080) != 0) return 0;
        }

        if (!isSnapEapol(raw, hdr)) return 0;

        int eapol = hdr + 8;
        int eapolType = raw[eapol + 1] & 0xFF;
        if (eapolType != 3) return 0; // 3 = EAPOL-Key

        int key = eapol + 4;
        int descType = raw[key] & 0xFF;
        // 1 = WPA (RC4), 2 = RSN/WPA2, 0xFE = WPA vendor descriptor
        if (descType != 1 && descType != 2 && descType != 0xFE) return 0;

        int keyInfo = ((raw[key + 1] & 0xFF) << 8) | (raw[key + 2] & 0xFF);
        int msg = classifyPairwiseEapolKey(keyInfo);
        if (msg <= 0) return 0;

        if (macs != null && macs.length >= 2) {
            if (fromDs && !toDs) {
                // AP → STA: Addr2 = BSSID, Addr1 = STA
                macs[0] = formatMacStatic(raw, 10);
                macs[1] = formatMacStatic(raw, 4);
            } else if (toDs && !fromDs) {
                // STA → AP: Addr1 = BSSID, Addr2 = STA
                macs[0] = formatMacStatic(raw, 4);
                macs[1] = formatMacStatic(raw, 10);
            } else {
                // IBSS: Addr3 = BSSID, Addr2 = SA
                macs[0] = formatMacStatic(raw, 16);
                macs[1] = formatMacStatic(raw, 10);
            }
            if (isBroadcastOrEmpty(macs[0]) || isBroadcastOrEmpty(macs[1])) return 0;
        }
        return msg;
    }

    /** LLC/SNAP header with EtherType 0x888E (EAPOL). */
    static boolean isSnapEapol(byte[] raw, int off) {
        return off + 8 <= raw.length
                && (raw[off]     & 0xFF) == 0xAA
                && (raw[off + 1] & 0xFF) == 0xAA
                && (raw[off + 2] & 0xFF) == 0x03
                && (raw[off + 3] & 0xFF) == 0x00
                && (raw[off + 4] & 0xFF) == 0x00
                && (raw[off + 5] & 0xFF) == 0x00
                && (raw[off + 6] & 0xFF) == 0x88
                && (raw[off + 7] & 0xFF) == 0x8E;
    }

    /**
     * Map EAPOL-Key Key Information flags to 4-way message 1–4.
     * Returns 0 for group-key frames, requests, errors, or unrecognised combinations.
     */
    static int classifyPairwiseEapolKey(int keyInfo) {
        final int KEYTYPE  = 0x0008;
        final int ACK      = 0x0080;
        final int MIC      = 0x0100;
        final int SECURE   = 0x0200;
        final int ERROR    = 0x0400;
        final int REQUEST  = 0x0800;

        if ((keyInfo & ERROR) != 0 || (keyInfo & REQUEST) != 0) return 0;
        if ((keyInfo & KEYTYPE) == 0) return 0; // group handshake, not the 4-way

        boolean ack    = (keyInfo & ACK)    != 0;
        boolean mic    = (keyInfo & MIC)    != 0;
        boolean secure = (keyInfo & SECURE) != 0;

        if ( ack && !mic)                return 1;
        if (!ack &&  mic && !secure)     return 2;
        if ( ack &&  mic)                return 3;
        if (!ack &&  mic &&  secure)     return 4;
        return 0;
    }

    private static String formatMacStatic(byte[] raw, int offset) {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                raw[offset], raw[offset+1], raw[offset+2],
                raw[offset+3], raw[offset+4], raw[offset+5]);
    }

    private static boolean isBroadcastOrEmpty(String mac) {
        if (mac == null || mac.isEmpty() || mac.equals("00:00:00:00:00:00")) return true;
        return mac.equals("FF:FF:FF:FF:FF:FF");
    }

    private void evictStaleHandshakeSessions(long now) {
        hsSessions.entrySet().removeIf(e -> now - e.getValue().lastMs > HS_SESSION_TTL_MS);
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
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                pool.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        hsSessions.clear();
        closePcapDumpers();
        for (PcapHandle h : handles) {
            try { h.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void close() { stop(); }
}