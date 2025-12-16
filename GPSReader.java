import java.io.BufferedReader;
import java.io.FileReader;
import java.io.Closeable;

public GPSReader(String portName) throws Exception {
    this.portName = portName;
    this.setName = setName;
    this.setDaemon(true); // this prevents JVMM shutdown 
    this.start();
}