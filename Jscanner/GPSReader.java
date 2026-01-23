
import java.io.*;

public class GPSReader extends Thread {
    private volatile double lat = 0.0, lon = 0.0;
    private volatile boolean running = true;

    public GPSReader(String comPort) throws Exception {
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(comPort)))) {
                System.out.println("[GPS] Listening on " + comPort + " — waiting for fix...");
                while (running) {
                    String line = br.readLine();
                    if (line != null && (line.startsWith("$GPGGA") || line.startsWith("$GNGGA"))) {
                        parseGGA(line);
                    }
                }
            } catch (Exception e) {
                System.out.println("[GPS] Port error (normal if no GPS): " + e.getMessage());
            }
        }).start();
    }

    private void parseGGA(String line) {
        String[] p = line.split(",");
        if (p.length < 10 || p[2].isEmpty() || p[4].isEmpty()) return;
        try {
            double latRaw = Double.parseDouble(p[2]);
            double lonRaw = Double.parseDouble(p[4]);
            lat = (int)(latRaw / 100) + (latRaw % 100) / 60.0;
            lon = (int)(lonRaw / 100) + (lonRaw % 100) / 60.0;
            if (p[5].equals("W")) lon = -lon;
            if (p[3].equals("S")) lat = -lat;
            System.out.printf("[GPS] FIX → %.6f, %.6f%n", lat, lon);
        } catch (Exception ignored) {}
    }

    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public void shutdown() { running = false; }
}