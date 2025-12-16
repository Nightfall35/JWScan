import java.io.BufferedReader;
import java.io.FileReader;
import java.io.Closeable;

public class GPSReader extends Thread implements Closeable {
    private volatile double lat = 0.0;
    private volatile double lon = 0.0;
    private volatile boolean hashFix = false;
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
    
}