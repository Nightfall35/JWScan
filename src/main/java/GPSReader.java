import java.io.*;
import java.nio.file.*;

/*
 * GPSReader — reads NMEA sentences from a serial GPS device.
 * Supports Windows (COM3, COM4...) and Linux/Raspberry Pi (/dev/ttyUSB0, /dev/ttyACM0).
 *
 * To configure the port, create gps_port.txt in the project folder with one line:
 *   COM4          (Windows)
 *   /dev/ttyUSB0  (Linux)
 *
 * Author: Ishmael D. Tembo — Lusaka, Zambia
 */
public class GPSReader extends Thread implements Closeable {

    private volatile double  lat     = 0.0;
    private volatile double  lon     = 0.0;
    private volatile boolean hasFix  = false;
    private volatile boolean running = true;

    private final String portName;

    public GPSReader(String portName) {
        this.portName = portName;
        setName("GPS-READER");
        setDaemon(true); // don't block JVM shutdown
        start();
    }

    @Override
    public void run() {
        System.out.println("[GPS] Opening port: " + portName);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(portName)))) {

            System.out.println("[GPS] Port open — waiting for NMEA fix...");
            while (running) {
                String line = reader.readLine();
                if (line == null) continue;
                // FIX: was '@GNGGA' — should be '$GNGGA' for multi-constellation GPS
                if (line.startsWith("$GPGGA") || line.startsWith("$GNGGA")) {
                    parseGGA(line);
                }
                // GPRMC / GNRMC give speed + heading — add later
            }
        } catch (Exception e) {
            if (running)
                System.out.println("[GPS] Port error (" + portName + "): " + e.getMessage());
        }
        System.out.println("[GPS] Reader thread stopped.");
    }

    public void parseGGA(String sentence) {
        try {
            String[] parts = sentence.split(",");
            if (parts.length < 7) return;

            // parts[6] = fix quality: 0 = no fix
            if ("0".equals(parts[6]) || parts[6].isEmpty()) {
                if (hasFix) {
                    System.out.println("[GPS] Fix lost.");
                    hasFix = false;
                }
                return;
            }

            if (parts[2].isEmpty() || parts[4].isEmpty()) return;

            double newLat = convertToDecimalDegrees(Double.parseDouble(parts[2]), parts[3]);
            double newLon = convertToDecimalDegrees(Double.parseDouble(parts[4]), parts[5]);

            this.lat    = newLat;
            this.lon    = newLon;
            this.hasFix = true;

            System.out.printf("[GPS] FIX -> %.6f, %.6f%n", lat, lon);

        } catch (NumberFormatException ignored) {
            // Malformed sentence — skip silently
        }
    }

    private double convertToDecimalDegrees(double raw, String direction) {
        // NMEA raw values are always positive (direction encoded in S/N/E/W letter)
        // Using Math.abs() protects against malformed sentences with leading minus
        double absRaw = Math.abs(raw);
        double degrees = Math.floor(absRaw / 100.0);
        double minutes = absRaw % 100.0;
        double decimal = degrees + minutes / 60.0;
        if ("S".equalsIgnoreCase(direction) || "W".equalsIgnoreCase(direction))
            return -decimal;
        return decimal;
    }

    public double  getLat()   { return lat;    }
    public double  getLon()   { return lon;    }
    public boolean hasFix()   { return hasFix; }

    @Override
    public void close() {
        running = false;
        interrupt();
    }
}