import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class Rat {
    private final Map<String, AP> seenById = new ConcurrentHashMap<>(); // key : BSSID or SSID fallback
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final int runningSeconds;
    private final String osNameLower = System.getProperty("os.name").toLowerCase();
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    public Rat(int runningTime) {
        this.runningSeconds = Math.max(5, runningTime); // run a minimum of 5 seconds
    }

    public void start() {
        println("Rat is waking up.........\nRat has awakened, beginning crawling\nEstimated time (interval: " + runningSeconds + "s). Press ctrl+c to exit.");

        scheduler.scheduleWithFixedDelay(this::scanAndAlert, 0, runningSeconds, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            println("Rat retreating to sleep...");
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            println("stopped.");
        }));
    }

    private void scanAndAlert() {
        try {
            List<AP> aps = scanForAps();
            if (aps.isEmpty()) {
                println(timestamp() + " NO APS FOUND or scanning tool unavailable");
                return;
            }

            Set<String> currentIds = new HashSet<>();
            for (AP ap : aps) {
                String id = (ap.bssid != null && !ap.bssid.isEmpty())
                        ? ap.bssid
                        : (ap.ssid == null ? "<unknown>" : ap.ssid);
                currentIds.add(id);

                if (!seenById.containsKey(id)) {
                    seenById.put(id, ap);
                    printlnAlert("NEW AP discovered: " + summarize(ap));
                    if (isOpen(ap)) {
                        printlnStrongAlert("!! NEW OPEN NETWORK detected: " + summarize(ap));
                        soundBell();
                    }
                } else {
                    AP old = seenById.get(id);
                    if (old != null) {
                        old.ssid = ap.ssid;
                        old.signal = ap.signal;
                        old.security = ap.security;
                        old.bssid = ap.bssid;
                    }
                }
            }

            println(timestamp() + " Crawling complete. Total unique seen: " + seenById.size() +
                    " | currently visible: " + currentIds.size());
        } catch (Exception ex) {
            println(timestamp() + " Scan failed: " + ex.getMessage());
            ex.printStackTrace(System.out);
        }
    }

    private boolean isOpen(AP ap) {
        if (ap == null) return false;
        String s = ap.security == null ? "" : ap.security.toUpperCase();
        return s.isEmpty() || s.equals("OPEN");
    }

    private String summarize(AP ap) {
        return String.format("SSID='%s' BSSID=%s SEC='%s' SIG=%s%%",
                safe(ap.ssid), safe(ap.bssid), safe(ap.security),
                ap.signal >= 0 ? ap.signal : "N/A");
    }

    private List<AP> scanForAps() throws IOException, InterruptedException {
        if (osNameLower.contains("win")) {
            return scanWindows();
        } else {
            if (isCommandAvailable("nmcli")) {
                return scanNmcli();
            } else if (isCommandAvailable("iwlist")) {
                return scanIwlist();
            } else {
                return Collections.emptyList();
            }
        }
    }

    // =================== Windows ===================
    private List<AP> scanWindows() throws IOException, InterruptedException {
        List<String> out = runCommandAndCollect("netsh", "wlan", "show", "networks", "mode=bssid");
        List<AP> aps = new ArrayList<>();
        AP current = null;

        for (String raw : out) {
            String line = raw.trim();
            if (line.startsWith("SSID ")) {
                int idx = line.indexOf(":");
                if (idx >= 0) {
                    String ssid = line.substring(idx + 1).trim();
                    current = new AP();
                    current.ssid = ssid;
                    aps.add(current);
                }
            } else if (line.startsWith("BSSID")) {
                int idx = line.indexOf(":");
                if (idx >= 0 && current != null) {
                    current.bssid = line.substring(idx + 1).trim();
                }
            } else if (line.startsWith("Signal")) {
                int idx = line.indexOf(":");
                if (idx >= 0 && current != null) {
                    String sig = line.substring(idx + 1).trim().replace("%", "");
                    try { current.signal = Integer.parseInt(sig); } catch (Exception ignored) {}
                }
            } else if (line.startsWith("Authentication") || line.startsWith("Encryption")) {
                int idx = line.indexOf(":");
                if (idx >= 0 && current != null) {
                    String v = line.substring(idx + 1).trim();
                    if (current.security == null || current.security.isEmpty()) current.security = v;
                    else current.security += " / " + v;
                }
            }
        }
        return aps;
    }

    // =================== Linux: nmcli ===================
    private List<AP> scanNmcli() throws IOException, InterruptedException {
        List<String> out = runCommandAndCollect("nmcli", "-f", "SSID,BSSID,SECURITY,SIGNAL", "device", "wifi", "list");
        List<AP> aps = new ArrayList<>();
        boolean headerSkipped = false;

        for (String line : out) {
            if (!headerSkipped) { headerSkipped = true; continue; }
            if (line.trim().isEmpty()) continue;

            String[] parts = line.trim().split("\\s{2,}");
            if (parts.length >= 4) {
                AP ap = new AP();
                ap.ssid = parts[0].trim();
                ap.bssid = parts[1].trim();
                ap.security = parts[2].trim();
                try { ap.signal = Integer.parseInt(parts[3].trim()); } catch (Exception e) { ap.signal = -1; }
                aps.add(ap);
            }
        }
        return aps;
    }

    // =================== Linux: iwlist ===================
    private List<AP> scanIwlist() throws IOException, InterruptedException {
        List<String> out = runCommandAndCollect("iwlist", "scanning");
        List<AP> aps = new ArrayList<>();
        AP current = null;

        for (String raw : out) {
            String line = raw.trim();
            if (line.startsWith("Cell")) {
                current = new AP();
                int idx = line.indexOf("Address:");
                if (idx >= 0) current.bssid = line.substring(idx + "Address:".length()).trim();
                aps.add(current);
            } else if (line.startsWith("ESSID:")) {
                if (current != null) current.ssid = line.substring(6).replace("\"", "").trim();
            } else if (line.contains("Encryption key:")) {
                if (current != null) {
                    String v = line.substring(line.indexOf("Encryption key:") + "Encryption key:".length()).trim();
                    if ("off".equalsIgnoreCase(v)) current.security = "OPEN";
                    else if (current.security == null) current.security = "ENCRYPTED";
                }
            } else if (line.contains("WPA") || line.contains("WEP")) {
                if (current != null) {
                    String exist = current.security == null ? "" : current.security + " ";
                    current.security = exist + line.replace("IE:", "").trim();
                }
            }
        }
        return aps;
    }

    // =================== Helpers ===================
    private List<String> runCommandAndCollect(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return lines;
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            Process p = pb.start();
            boolean ok = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            return ok;
        } catch (Exception e) {
            try {
                Process p2 = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                p2.destroy();
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private void println(String s) { System.out.println(s); }

    private void printlnStrongAlert(String s) {
        System.out.println(timestamp() + " [*****CRITICAL ALERT******] " + s);
    }

    private void printlnAlert(String s) {
        System.out.println(timestamp() + " [ALERT] " + s);
    }

    private String timestamp() {
        return "[" + LocalDateTime.now().format(dtf) + "]";
    }

    private void soundBell() {
        System.out.print("\u0007");
        System.out.flush();
    }

    private String safe(String s) { return s == null ? "" : s; }

    private static class AP {
        String ssid = "";
        String bssid = "";
        String security = "";
        int signal = -1;
    }

    public static void main(String[] args) {
        int interval = 30;
        if (args.length >= 1) {
            try { interval = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        Rat scanner = new Rat(interval);
        scanner.start();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {}
    }
}
