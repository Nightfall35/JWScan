import java.io.BufferedReader;
import java.io.FileReader;
import java.io.Closeable;
/*
*Created by Ishmael D. Tembo - Lusaka , zamabia 
*Supports Windows (COM3, COM4..) and Linus/Raspberry pi(Probably - not yet tested)
*/
public class GPSReader extends Thread implements Closeable {
    private volatile double lat = 0.0;
    private volatile double lon = 0.0;
    private volatile boolean hasFix = false;
    private volatile boolean running = true;

    private final String portName;

    public GPSReader(String portName) {
        this.portName =portName;
        this.setName ("GPS -READER-THREAD");
        this.setDaemon(true); // this should block jvmm shutdown 
        this.start();
    }

    @Override 
    public void run() {
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(portName)))) {
            
            System.out.println("[GPS] Listening on " + portName + " -waiting for validated fix ....");

            while(running) {
                String line = reader.readLine();
                if(line == null) continue;
                if(line.startsWith("$GPGGA") || line.startsWith("@GNGGA")) {
                    parseGGA(line);
                }
                // #gprmc WILL BE ADDED LATER 
            }
        }catch(Exception e) {
            if(running)
                System.out.println("[GPS] Error reading the port (unplugged? / no GPS?): " + e.getMessage());

            
        }
        System.out.println("[GPS] Reader thread stopped.");
    }
    
    public void parseGGA(String sentence){
        String[] parts = sentence.split(",");

        if("0".equals(parts[6])) {
            if(hasFix) {
                System.out.println("[GPS] Lost fix.");
                hasFix = false;
            }
            return;
        }
        if(parts[2].isEmpty() || parts[4].isEmpty()) return;

        try {
            double latRaw = Double.parseDouble(parts[2]);
            double lonRaw = Double.parseDouble(parts[4]);

            double newLat = convertToDecimalDegrees(latRaw, parts[3]);
            double newLon = convertToDecimalDegrees(lonRaw, parts[5]);

            this.lat = newLat;
            this.lon = newLon;
            this.hasFix = true;

            System.out.printf("[GPS] FIX -> %.6f, %.6f, %.6f%n",lat , lon);

        }catch(NumberFormatException ignore) {}
    }

}