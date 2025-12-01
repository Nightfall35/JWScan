import org.pcap4j.core.*;
import org.pcap4j.packet.*;
import java.util.*;
import java.util.concurrent.*;

public class PassiveScanner implements AutoCloseable {

    private final Rat rat;
    private final List<PcapHandle> handles = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;  // The volatile keyword makes sure all threads see updated values instantly 

    public PassiveScanner(Rat rat) {
        this.rat = rat;
    }

    public void start() {
        List<PcapNetworkInterface> ifaces = getWifiInterfaces();
        if (ifaces.isEmpty()) {
            System.out.println("[ERROR] No Wi-Fi interfaces found. Install Npcap + enable monitor mode if possible.");
            return;
        }

        running = true;
        System.out.println("[OK] Starting native Npcap radiotap scanner on " + ifaces.size() + " interface(s)");

        for (PcapNetworkInterface nif : ifaces) {
            try {
                PcapHandle handle = nif.openLive(
                        65536,
                        PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                        10
                );  //65536 : is the maximum byte size , PROMISCUOUOS : capture everything regardless of destination , 10 : read timeout in 10 ms

                handles.add(handle);
                pool.submit(() -> captureLoop(handle));

            } catch (Exception e) {
                System.out.println("[ERROR] Failed to open " + nif.getName() + " → " + e.getMessage());
            }
        }
    }

    /**
     * Find Wi-Fi-related interfaces that Npcap exposes.
     */
    private List<PcapNetworkInterface> getWifiInterfaces() {
        List<PcapNetworkInterface> list = new ArrayList<>();
        try {
            for (PcapNetworkInterface nif : Pcaps.findAllDevs()) {
                if (nif == null) continue;

                String name = nif.getName() != null ? nif.getName().toLowerCase() : "";
                String desc = nif.getDescription() != null ? nif.getDescription().toLowerCase() : "";

                if (name.contains("wlan") ||
                    name.contains("wi-fi") ||
                    desc.contains("wi-fi") ||
                    desc.contains("wireless") ||
                    name.contains("npcap")) 
                {
                    list.add(nif);
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    /**
     * Capture loop using Pcap4J listener
     */
    private void captureLoop(PcapHandle handle) {
        try {
            handle.loop(-1, (PacketListener) packet -> {

                if (!running) return;

                RadiotapPacket radiotap = packet.get(RadiotapPacket.class);

                // Extract deepest payload (802.11 raw)
                Packet payload = packet;
                while (payload.getPayload() != null)
                    payload = payload.getPayload();

                if (!(payload instanceof UnknownPacket)) return;

                byte[] raw80211 = payload.getRawData();
                if (raw80211.length < 24) return;

                // === Radiotap signal extraction ===
                int signalDbm = -95;

                if (radiotap != null) {
                    byte[] rt = radiotap.getRawData();
                    if (rt.length >= 8) {
                        int present = (rt[4] & 0xFF)
                                | ((rt[5] & 0xFF) << 8)
                                | ((rt[6] & 0xFF) << 16)
                                | ((rt[7] & 0xFF) << 24);

                        if ((present & (1 << 2)) != 0) { // DBM_ANT_SIGNAL bit
                            // simple linear scan fallback
                            for (int i = 8; i < rt.length - 1; i++) {
                                if (rt[i] == 0x0B) { // antenna signal tag
                                    signalDbm = rt[i + 1];
                                    if (signalDbm > 0) signalDbm -= 256;
                                    break;
                                }
                            }
                        }
                    }
                }

                // === Frame Control ===
                int fc = raw80211[0] & 0xFF;
                int type = (fc >> 2) & 0x3;
                int subtype = (fc >> 4) & 0xF;

                if (type != 0) return; // management only

                switch (subtype) {
                    case 8:  // Beacon
                    case 5:  // Probe Response
                        parseBeacon(raw80211, signalDbm);
                        break;

                    case 4:  // Probe Request
                        parseProbeReq(raw80211);
                        break;

                    case 12: // Deauthentication
                        parseDeauth(raw80211);
                        break;
                }
            });
        } catch (Exception ignored) {}
    }

    private void parseBeacon(byte[] raw, int signalDbm) {
        Rat.AP ap = new Rat.AP();

        ap.bssid = String.format(
                "%02X:%02X:%02X:%02X:%02X:%02X",
                raw[10], raw[11], raw[12], raw[13], raw[14], raw[15]
        );

        ap.signal = signalDbm;

        int offset = 36;
        while (offset + 2 < raw.length) {
            int id = raw[offset] & 0xFF;
            int len = raw[offset + 1] & 0xFF;

            if (offset + 2 + len > raw.length) break;

            if (id == 0) {  // SSID
                String ssid = new String(raw, offset + 2, len).trim();
                ap.ssid = ssid.isEmpty() ? "<hidden>" : ssid;
            }
            else if (id == 3 && len >= 1) { // Channel
                ap.channel = raw[offset + 2] & 0xFF;
            }
            else if (id == 48 || id == 50 || id == 221) {
                ap.security = "WPA2/WPA3";
            }

            offset += 2 + len;
        }

        if (ap.security == null) ap.security = "OPEN";

        rat.onAccessPointDiscovered(ap);
    }

    private void parseProbeReq(byte[] raw) {
        String mac = String.format(
                "%02X:%02X:%02X:%02X:%02X:%02X",
                raw[10], raw[11], raw[12], raw[13], raw[14], raw[15]
        );

        int offset = 24;
        while (offset + 2 < raw.length) {
            int id = raw[offset] & 0xFF;
            int len = raw[offset + 1] & 0xFF;

            if (id == 0) { // SSID field
                String ssid = new String(
                        raw,
                        offset + 2,
                        Math.min(len, raw.length - offset - 2)
                ).trim();

                rat.onClientProbe(mac, ssid.isEmpty() ? "<any>" : ssid);
                return;
            }
            offset += 2 + len;
        }

        rat.onClientProbe(mac, "<any>");
    }

    private void parseDeauth(byte[] raw) {
        String src = String.format(
                "%02X:%02X:%02X:%02X:%02X:%02X",
                raw[10], raw[11], raw[12], raw[13], raw[14], raw[15]
        );

        String dst = String.format(
                "%02X:%02X:%02X:%02X:%02X:%02X",
                raw[4], raw[5], raw[6], raw[7], raw[8], raw[9]
        );

        rat.onDeauthAttack(src, dst, 1);
    }

    public void stop() {
        running = false;

        for (PcapHandle h : handles) {
            try { h.breakLoop(); } catch (Exception ignored) {}
            try { h.close(); } catch (Exception ignored) {}
        }

        pool.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }
}
