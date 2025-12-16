import java.io.BufferedReader;
import java.io.FileReader;
import java.io.Closeable;

public class GPSReader implements Closeable {
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
}