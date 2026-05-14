package com.nightfall35.blackice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.pcap4j.core.PacketListener;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.RadiotapPacket;
import org.pcap4j.packet.UnknownPacket;

/**
 * PassiveScanner — 802.11 frame capture via Npcap/pcap4j.
 *
 * FIXES APPLIED:
 * 1. Radiotap signal extraction now walks field offsets properly
 *    instead of scanning for a 0x0B byte (which is fragile and wrong).
 * 2. Security classification extended: WPA (TKIP-only) distinguished
 *    from WPA2/WPA3 (CCMP), and WPS presence flagged.
 * 3. SSID byte validation added — malformed beacon SSIDs are rejected
 *    rather than forwarded as garbled strings.
 * 4. parseDeauth() correctly maps src/dst from 802.11 address fields
 *    (addr2=transmitter=attacker, addr1=receiver=victim).
 * 5. captureLoop() exception now logged; handle.loop() errors no longer
 *    silently swallow the cause.
 * 6. Adapter filter now accepts Microsoft WiFi Direct adapters (common
 *    on Windows 11) and any interface with a non-empty description
 *    containing "802" (broad fallback for unusual Npcap adapter names).
 */
public class PassiveScanner implements AutoCloseable {

    private final Rat rat;
    private final List<PcapHandle> handles = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

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
    }

    // ── FIX 6: Broader interface filter ─────────────────────────────────────
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

    // ── FIX 5: Log exception cause ───────────────────────────────────────────
    private void captureLoop(PcapHandle handle, String ifName) {
        try {
            handle.loop(-1, (PacketListener) packet -> {
                if (!running) return;

                RadiotapPacket radiotap = packet.get(RadiotapPacket.class);

                // Walk to the deepest (raw 802.11) payload
                Packet payload = packet;
                while (payload.getPayload() != null) payload = payload.getPayload();
                if (!(payload instanceof UnknownPacket)) return;

                byte[] raw80211 = payload.getRawData();
                if (raw80211.length < 24) return;

                int signalDbm = extractSignal(radiotap);

                int fc      = raw80211[0] & 0xFF;
                int type    = (fc >> 2) & 0x3;
                int subtype = (fc >> 4) & 0xF;

                if (type != 0) return; // management frames only

                switch (subtype) {
                    case 8, 5 -> parseBeacon(raw80211, signalDbm);    // Beacon / Probe Response
                    case 4    -> parseProbeReq(raw80211);               // Probe Request
                    case 12   -> parseDeauth(raw80211);                 // Deauthentication
                }
            });
        } catch (Exception e) {
            rat.println("[SCANNER] captureLoop error on " + ifName + ": "
                    + e.getMessage()
                    + (e.getCause() != null ? " (cause: " + e.getCause().getMessage() + ")" : ""));
        }
    }

    // ── FIX 1: Proper radiotap field-offset walking ──────────────────────────
    /**
     * Walks the radiotap header present-bitmap and computes the byte offset
     * of each present field. The DBM_ANTSIGNAL field (bit 5) is then read
     * at the correct offset, not by scanning for a magic byte.
     *
     * Radiotap spec: https://www.radiotap.org/
     */
    private int extractSignal(RadiotapPacket radiotap) {
        if (radiotap == null) return -95;

        byte[] rt = radiotap.getRawData();
        if (rt.length < 8) return -95;

        // Bytes 4-7: present flags (little-endian)
        int present = (rt[4] & 0xFF)
                | ((rt[5] & 0xFF) << 8)
                | ((rt[6] & 0xFF) << 16)
                | ((rt[7] & 0xFF) << 24);

        // Field sizes and alignment requirements per radiotap spec
        // index = bit number in present flags
        int[] fieldSizes  = {8, 1, 1, 1, 2, 2, 2, 2, 2, 1, 1, 1, 2, 1};
        int[] fieldAligns = {1, 1, 1, 1, 2, 2, 2, 2, 2, 1, 1, 1, 2, 1};
        // Bit 5 = DBM_ANTSIGNAL
        int DBM_ANT_SIGNAL_BIT = 5;

        int offset = 8; // start after the 8-byte header
        for (int bit = 0; bit < 32; bit++) {
            if ((present & (1 << bit)) == 0) continue;
            if (bit >= fieldSizes.length) break;

            // Align offset to field's required boundary
            int align = fieldAligns[bit];
            if (align > 1 && offset % align != 0) {
                offset += align - (offset % align);
            }

            if (bit == DBM_ANT_SIGNAL_BIT) {
                if (offset < rt.length) {
                    int raw = rt[offset] & 0xFF;
                    // Values > 127 are negative (two's complement for int8)
                    return raw > 127 ? raw - 256 : raw;
                }
                break;
            }
            offset += fieldSizes[bit];
        }
        return -95;
    }

    // ── FIX 2+3: Improved security classification, SSID validation ──────────
    private void parseBeacon(byte[] raw, int signalDbm) {
        Rat.AP ap = new Rat.AP();
        ap.bssid = formatMac(raw, 10);
        ap.signal = signalDbm;

        boolean hasRsn   = false; // IE 48 = RSN (WPA2/WPA3)
        boolean hasWpa   = false; // IE 221 vendor = WPA
        boolean hasCcmp  = false;
        boolean hasTkip  = false;
        boolean hasWps   = false; // IE 221 with WPS OUI

        int offset = 36;
        while (offset + 1 < raw.length) {
            int id  = raw[offset] & 0xFF;
            int len = (offset + 1 < raw.length) ? raw[offset + 1] & 0xFF : 0;
            if (offset + 2 + len > raw.length) break;

            switch (id) {
                case 0 -> { // SSID
                    // FIX 3: Validate SSID bytes before building a String
                    if (len == 0) {
                        ap.ssid = "<hidden>";
                    } else {
                        byte[] ssidBytes = Arrays.copyOfRange(raw, offset + 2, offset + 2 + len);
                        if (isPrintableUtf8(ssidBytes)) {
                            String ssid = new String(ssidBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
                            ap.ssid = ssid.isEmpty() ? "<hidden>" : ssid;
                        } else {
                            ap.ssid = "<hidden>"; // binary/garbled SSID — skip
                        }
                    }
                }
                case 3 -> { // DS Parameter Set (channel)
                    if (len >= 1) ap.channel = raw[offset + 2] & 0xFF;
                }
                case 48 -> { // RSN (WPA2 / WPA3)
                    hasRsn = true;
                    // Parse RSN to detect CCMP vs TKIP cipher suites
                    if (len >= 8) {
                        int rsn = offset + 2;
                        // Group cipher suite starts at rsn+2
                        // Pairwise count at rsn+6 (2 bytes LE)
                        int pairwiseCount = (raw[rsn + 6] & 0xFF) | ((raw[rsn + 7] & 0xFF) << 8);
                        int pairwiseOff = rsn + 8;
                        for (int i = 0; i < pairwiseCount && pairwiseOff + 4 <= offset + 2 + len; i++) {
                            int cipherType = raw[pairwiseOff + 3] & 0xFF;
                            if (cipherType == 4) hasCcmp = true;  // AES-CCMP
                            if (cipherType == 2) hasTkip = true;   // TKIP
                            pairwiseOff += 4;
                        }
                    }
                }
                case 221 -> { // Vendor-specific
                    if (len >= 4) {
                        // Microsoft WPA OUI: 00-50-F2-01
                        if ((raw[offset+2]&0xFF)==0x00 && (raw[offset+3]&0xFF)==0x50
                                && (raw[offset+4]&0xFF)==0xF2 && (raw[offset+5]&0xFF)==0x01) {
                            hasWpa = true;
                        }
                        // WPS OUI: 00-50-F2-04
                        if (len >= 4 && (raw[offset+2]&0xFF)==0x00 && (raw[offset+3]&0xFF)==0x50
                                && (raw[offset+4]&0xFF)==0xF2 && (raw[offset+5]&0xFF)==0x04) {
                            hasWps = true;
                        }
                    }
                }
            }
            offset += 2 + len;
        }

        // FIX 2: Better security classification
        if (hasRsn) {
            if (hasCcmp && !hasTkip) {
                ap.security = hasWps ? "WPA2 (WPS!)" : "WPA2";
            } else if (hasCcmp) {
                ap.security = "WPA2/WPA3";
            } else {
                ap.security = "WPA2-TKIP"; // downgrade-vulnerable
            }
        } else if (hasWpa) {
            ap.security = hasTkip ? "WPA-TKIP" : "WPA";
        } else {
            ap.security = "OPEN";
        }

        rat.onAccessPointDiscovered(ap);
    }

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
            offset += 2 + Math.max(1, len); // guard against zero-len infinite loop
        }
        rat.onClientProbe(mac, "<any>");
    }

    // ── FIX 4: Correct addr mapping for deauth ───────────────────────────────
    /**
     * In a Deauthentication frame:
     *   addr1 (bytes 4–9)  = destination / victim (who is being kicked)
     *   addr2 (bytes 10–15)= source / attacker    (who sent the frame)
     *   addr3 (bytes 16–21)= BSSID
     */
    private void parseDeauth(byte[] raw) {
        if (raw.length < 22) return;
        String dst = formatMac(raw, 4);  // victim
        String src = formatMac(raw, 10); // attacker/sender
        rat.onDeauthAttack(src, dst, 1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private String formatMac(byte[] raw, int offset) {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                raw[offset], raw[offset+1], raw[offset+2],
                raw[offset+3], raw[offset+4], raw[offset+5]);
    }

    /** Returns true if bytes represent valid UTF-8 with only printable chars. */
    private boolean isPrintableUtf8(byte[] bytes) {
        try {
            String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return s.chars().allMatch(c -> c >= 0x20 && c < 0xFFFD);
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        running = false;
        for (PcapHandle h : handles) {
            try { h.breakLoop(); } catch (Exception ignored) {}
            try { h.close();     } catch (Exception ignored) {}
        }
        pool.shutdownNow();
    }

    @Override
    public void close() { stop(); }
}
