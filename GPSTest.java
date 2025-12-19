
public class GPSTest {
    public static void main(String[] args) {
        try {
            // Windows: "COM3" or "COM4"
            // Linux/RPi: "/dev/ttyUSB0" or "/dev/ttyACM0"
            GPSReader gps = new GPSReader("/dev/ttyUSB0");

            // Just print location every 5 seconds for testing
            while (true) {
                if (gps.hasFix()) {
                    System.out.println("Current position: " + gps.getLat() + ", " + gps.getLon());
                } else {
                    System.out.println("Waiting for GPS fix...");
                }
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
