/** ===============================R.A.T (NOT A REMOTE ACCESS TROJAN : JUST THOUGHT OF AN ACTUAL RAT THAT WAS CRAWLING THROUGH MY CEILING HENCE THE NAME... NOTHING SPECIAL TO IT==========
*
*
*
*                                                                               AUTHOR-> ISHMAEL D.TEMBO 
*                                                                     CREATED FROM JANUARY 3RD -> NOVEMBER 27
*								      ALIAS (CAUSE THEY ARE COOL) -> NIGHTFALL35
*								    
*
* 								     GITHUB : Nightfall35.......................
*
*
*								     ABOUT : (PLEASE READ if interested of coyrse )
*								     EMAIL : ishamelgoku@gmail.com
*  								     
*
*								     DISCLAIMER: I AM NOT A NETWORK ENGINEER : JUST A JAVA OBESSED FOOL
*                                               
* Rat is java based wifi wardriving tool inspired by the watch dog video game and honestly just thought this shit would be cool .  Started off as an attempt at creating a simple hybrid (of which you will find on my github profile...) java tcp/udp server and now we have this .... picture this : - >   You sit in an You sit in a café → open the dashboard → within 60 seconds the entire street lights up like a Christmas tree made of other people’s Wi-Fi.
You hear beeps every time someone leaves their network open.
You watch, in real time, as someone tries to set up a fake hotspot → a red circle flashes → three seconds later it’s gone → they will never know why their evil twin failed.

BLACK ICE v2
"Every beacon is a heartbeat. Every open network is a scream.
I hear them all."

A pure-Java, zero-dependency 802.11 surveillance lattice that turns any monitor-mode Wi-Fi adapter into a real-time city-scale RF intelligence platform.

Born in Lusaka, Zambia — 2025.

What it does:
- Passively captures every 802.11 frame in range (beacons, probes, deauths)
- Builds a live, breathing map of the invisible Wi-Fi layer around you
- Instantly identifies open networks, rogue APs, and evil twins
- Autonomously defends against deauthentication attacks
- Displays everything on a dystopian cyberpunk dashboard at http://localhost:8080

Written entirely in Java using only:
- Pcap4j + Npcap/libpcap (raw packet access)
- JDK’s built-in com.sun.net.httpserver (no Node.js, no Python, no bloat)

Runs anywhere Java runs — Windows, Linux, Raspberry Pi, even Android with Termux + root( i think ,yet to test this . if you do please inform me on my email).

No external tools.  
No Python.  
No aircrack.  
Just Java and an Alfa card = total RF domination.

Features
• Real-time Leaflet map with color-coded markers  
  Red = open (free meat) • Green = strong • Orange = normal  
• Server-Sent Events live updates (lighter than WebSocket)  
• Evil-twin auto-detection + optional auto-purge  
• Counter-deauth revenge protocol  
• OUI vendor lookup (Cisco, TP-Link, Apple, etc.)  
• Loud console alerts + terminal bell on open networks  
• Single JAR deployable

Legal note  
This tool is for authorized security research, education, and testing on networks you own or have explicit written permission to analyze.  
Active transmission features are disabled by default and must only be used where legally permitted.

Created by an anonymous operator in Lusaka, Zambia — November 2025  ( i tried being cool , sue me its my first completed project )
“For the day when the sky is full of silent signals, and only some of us can hear them.”

The city is now transparent.  
Welcome to the real 2049.



SORRY FOR THE LACK OF COMMENTS .... :) BUT HERE IS  A BREAKDOWN OF HOW IT WORKS :
 
**/ 






import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.pcap4j.core.PcapNetworkInterface;

public class Rat {
    private final Map<String, AP> seenById = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final int httpPort;
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private PassiveScanner passiveScanner;
    private HttpServer httpServer;
    private Deauther deauther = null;
    private final Map<String, String> ouiMap = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<HttpExchange> websocketClients = new CopyOnWriteArrayList<>();
    private SwarmAi ai;
    private String myMac = "00-15-5D-BF-D7-5A";
    private boolean counterMode = true;

    public Rat(int port) {
        this.httpPort = port;
        loadBuiltInOuIs();
        background.submit(this::downloadAndCacheIeeeOui);
        ai = new SwarmAi(this);

        // Auto counter-deauth when someone attacks me
        onDeauthAttack = (src, dst, count) -> {
            if (dst.equalsIgnoreCase(myMac) || dst.equalsIgnoreCase("FF:FF:FF:FF:FF:FF")) {
                printlnStrongAlert("ATTACK DETECTED → " + src + " deauthing YOU → COUNTER-ATTACK ENGAGED");
                if (deauther != null && counterMode) {
                    deauther.deauth(src, src, 0); // infinite revenge
                }
            }
        };
    }

    public void start() {
        println("==================================================");
        println(" RAT SWARM v2 — FULL PASSIVE + ACTIVE MODE");
        println("==================================================");

        passiveScanner = new PassiveScanner(this);
        passiveScanner.start();

        startHttpServer();

        scheduler.scheduleAtFixedRate(this::broadcastFullUpdate, 1, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        println("DASHBOARD → http://localhost:" + httpPort);
        println("==================================================");
    }

    private void broadcastFullUpdate() {
        if (websocketClients.isEmpty()) return;
        String json = buildFullJson();
        Iterator<HttpExchange> it = websocketClients.iterator();
        while (it.hasNext()) {
            HttpExchange ex = it.next();
            try {
               	String msg ="data: " +json+ "\n\n";
		ex.getResponseBody().write(msg.getBytes(StandardCharsets.UTF_8));
		ex.getResponseBody().flush();
            } catch (Exception e) {
                it.remove();
                try { ex.close(); } catch (Exception ignored) {}
            }
        }
    }

    public String buildFullJson() {
	StringBuilder sb = new StringBuilder("{\"type\":\"full\",\"aps\":{");
	boolean first = true;
	long now = System.currentTimeMillis();
	for(Map.Entry<String, AP> e : seenById.entrySet()) {
		AP ap = e.getValue();
		
		if(now - ap.lastSeen > 3000000) continue;
		if(!first) sb.append(",");
		first = false;
	
		String vendor = getVendorFromBssid(ap.bssid);
		sb.append("\"").append(e.getKey()).append("\":{")
		   .append("\"ssid\":\"").append(jsonEscape(ap.ssid)).append("\",")
		   .append("\"bssid\":\"").append(jsonEscape(ap.bssid)).append("\",")
		   .append("\"security\":\"").append(jsonEscape(ap.security)).append("\",")
		   .append("\"signal\":").append(ap.signal).append(",")
		   .append("\"channel\":").append(ap.channel).append(",")
		   .append("\"vendor\":\"").append(jsonEscape(vendor != null ? vendor :"unknown")).append("\",")
		   .append("\"lat\":").append(ap.lat).append(",")
		   .append("\"lon\":").append(ap.lon)
		   .append("}");
	}
	sb.append("}}");
	return sb.toString();
    }

    public void onAccessPointDiscovered(AP ap) {
    String id = !ap.bssid.isEmpty() ? ap.bssid : ap.ssid;
    AP existing = seenById.get(id);

    // current device GPS (fallback)
    double currentLat = (gps != null && gps.getLat() != 0.0) ? gps.getLat() : 40.7589;
    double currentLon = (gps != null && gps.getLon() != 0.0) ? gps.getLon() : -73.9851;

    // estimate distance in meters from RSSI
    double distanceMeters = signalToDistance(ap.signal - 5); // small antenna compensation

    // Add this sample to AP.samples (create if new)
    if (existing == null) {
        // first time we see it: store AP and create struct
        seenById.put(id, ap);
        existing = ap;
    }

    // push a new sample
    synchronized (existing.samples) {
        existing.samples.addLast(new Sample(currentLat, currentLon, distanceMeters));
        // keep last N samples (choose 20)
        while (existing.samples.size() > 20) existing.samples.removeFirst();
    }

    // Try trilateration if we have 3+ samples with decent geometry
    double newLat = existing.lat;
    double newLon = existing.lon;
    if (existing.samples.size() >= 3) {
        // pick 3 samples with decent timestamp spacing and different positions
        List<Sample> s = new ArrayList<>(existing.samples);
        // pick last, mid, earliest to get geometric spread
        Sample s1 = s.get(0);
        Sample s2 = s.get(s.size() / 2);
        Sample s3 = s.get(s.size() - 1);

        // ensure they are not extremely close to each other
        boolean ok = true;
        double minMeters = 2.0; // require at least a few meters separation
        double d12 = distanceBetweenMeters(s1.lat, s1.lon, s2.lat, s2.lon);
        double d13 = distanceBetweenMeters(s1.lat, s1.lon, s3.lat, s3.lon);
        double d23 = distanceBetweenMeters(s2.lat, s2.lon, s3.lat, s3.lon);
        if (d12 < minMeters || d13 < minMeters || d23 < minMeters) ok = false;

        if (ok) {
            double[] trilat = Trilateration.trilaterateGPS(
                    s1.lat, s1.lon, s1.distance,
                    s2.lat, s2.lon, s2.distance,
                    s3.lat, s3.lon, s3.distance);
            if (trilat != null) {
                // init kalman if needed
                if (existing.kalman == null) {
                    existing.kalman = new Kalman2D(4.0, 0.01, 0.1);
                    existing.kalman.init(trilat[0], trilat[1]);
                } else {
                    // predict using approximate elapsed time from lastSeen
                    double dt = Math.max(0.02, (System.currentTimeMillis() - existing.lastSeen) / 1000.0);
                    existing.kalman.predict(dt);
                    existing.kalman.update(trilat[0], trilat[1]);
                }
                newLat = existing.kalman.getX();
                newLon = existing.kalman.getY();
            }
        }
    }

    // fallback: if not enough samples or trilateration failed, we can still
    // set a weak estimate: use current device coords plus bearing bias removed
    if (existing.kalman == null) {
        // rough fallback: place it on circle around current location
        // (keeps behavior similar to old code but deterministic: use last sample bearing relative to device movement if available)
        newLat = currentLat;
        newLon = currentLon;
    }

    // update AP
    existing.lat = newLat;
    existing.lon = newLon;
    existing.lastSeen = System.currentTimeMillis();
    existing.signal = Math.max(existing.signal, ap.signal);
    existing.ssid = ap.ssid.isEmpty() ? existing.ssid : ap.ssid;
    existing.security = ap.security;
    existing.channel = ap.channel;

    // Alerts and UI unchanged
    if (ap == existing) {
        // new discovery
        printlnAlert("NEW AP -> " + summarize(existing));
        if (isOpen(existing)) {
            printlnStrongAlert("OPEN NETWORK -> " + existing.ssid);
            soundBell();
        }
    }

    ai.seeAP(existing.bssid, existing.ssid, existing.channel, existing.security);
}
    private double distanceBetweenMeters(double lat1, double lon1, double lat2, double lon2) {
    double R = 6371000.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
               Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
               Math.sin(dLon/2) * Math.sin(dLon/2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    return R * c;
}

    public void onClientProbe(String mac, String ssid) {
        println("CLIENT PROBE → " + mac + " → \"" + (ssid.isEmpty() ? "<any>" : ssid) + "\"");
    }

    public void onDeauthAttack(String src, String dst, int count) {
        printlnStrongAlert("DEAUTH ATTACK → " + src + " → " + dst);
        soundBell();
        if (onDeauthAttack != null) onDeauthAttack.accept(src, dst, count);
    }

    @FunctionalInterface
    interface DeauthCallback { void accept(String src, String dst, int count); }
    private DeauthCallback onDeauthAttack;

    // Called by SwarmAi
    public void evilTwinDetected(String ssid, String realBssid, String fakeBssid, int channel) {
        printlnStrongAlert("EVIL TWIN / ROGUE AP DETECTED");
        printlnStrongAlert("    SSID: " + ssid);
        printlnStrongAlert("    LEGITIMATE → " + realBssid);
        printlnStrongAlert("    FAKE / ROGUE → " + fakeBssid + " (channel " + channel + ")");
        printlnStrongAlert("    AUTO-NUKE ENGAGED");
        soundBell();

        if (deauther != null) {
            new Thread(() -> deauther.deauth("FF:FF:FF:FF:FF:FF", fakeBssid, 0)).start();
        }
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);

            httpServer.createContext("/ws", exchange -> {
		if(!"GET".equals(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			return;
		}

		Headers h =exchange.getResponseHeaders();
		h.set("Content-Type","text/event-stream");
		h.set("Cache-Control","no-cache");
		h.set("Connection","keep-alive");

		exchange.sendResponseHeaders(200,0);
	
		websocketClients.add(exchange);
	    });

            httpServer.createContext("/", exchange -> {
                if ("GET".equals(exchange.getRequestMethod())) {
                    byte[] html = DASHBOARD_HTML.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, html.length);
                    exchange.getResponseBody().write(html);
                    exchange.close();
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            });

            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            println("Web dashboard → http://localhost:" + httpPort);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void shutdown() {
        println("\nShutting down RAT SWARM...");
        if (passiveScanner != null) passiveScanner.stop();
        scheduler.shutdownNow();
        background.shutdownNow();
        if (httpServer != null) httpServer.stop(1);
        println("Swarm offline.");
    }

    private String summarize(AP ap) {
        String v = getVendorFromBssid(ap.bssid);
        return String.format("SSID='%s' BSSID=%s (%s) SEC='%s' SIG=%ddBm CH=%d",
                ap.ssid.isEmpty() ? "<hidden>" : ap.ssid, safe(ap.bssid),
                v != null ? v : "?", safe(ap.security), ap.signal, ap.channel);
    }

    private boolean isOpen(AP ap) {
        String s = ap.security == null ? "" : ap.security.toUpperCase();
        return s.contains("OPEN") || s.isEmpty();
    }

    private String jsonEscape(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder();
    for (char c : s.toCharArray()) {
        switch (c) {
            case '\\': sb.append("\\\\"); break;
            case '\"': sb.append("\\\""); break;
            case '\b': sb.append("\\b"); break;
            case '\f': sb.append("\\f"); break;
            case '\n': sb.append("\\n"); break;
            case '\r': sb.append("\\r"); break;
            case '\t': sb.append("\\t"); break;
            default:
                if (c < 0x20 || c > 0x7E) {
                    sb.append(String.format("\\u%04x", (int)c));
                } else {
                    sb.append(c);
                }
        }
    }
    
    return sb.toString();
}
    private String safe(String s) { return s == null ? "" : s; }

    public void println(String s) { System.out.println(timestamp() + " " + s); }
    public void printlnAlert(String s) { System.out.println(timestamp() + " [ALERT] " + s); }
    public void printlnStrongAlert(String s) { System.out.println(timestamp() + " [CRITICAL] " + s); }
    public String timestamp() { return "[" + LocalDateTime.now().format(dtf) + "]"; }
    public void soundBell() { System.out.print("\007"); System.out.flush(); }

    private void loadBuiltInOuIs() {
        ouiMap.put("001122", "Cisco");
        ouiMap.put("44650D", "Cisco Meraki");
        ouiMap.put("A4C3F0", "Apple");
        ouiMap.put("7C9EBD", "TP-Link");
    }

    private void downloadAndCacheIeeeOui() {/**Kept around to avoid compilation erros**/ }

    private String getVendorFromBssid(String bssid) {
        if (bssid == null) return null;
        String c = bssid.replace(":", "").toUpperCase();
        return c.length() >= 6 ? ouiMap.get(c.substring(0, 6)) : null;
    }

    public void enableDeauthOn(PcapNetworkInterface nif) {
        try {
            deauther = new Deauther(nif);
            println("[WEAPON] DEAUTH CANNON ARMED AND READY");
        } catch (Exception e) {
            println("[WEAPON] Monitor mode required for injection");
        }
    }

    //this returns an approximate distance in meters ( or it should)
    private double signalToDistance(int rssi) {
	double freq =2437.0;
	double exp = 2.7;
	return Math.pow(10, (27.55 - (20 * Math.log10(freq)) + Math.abs(rssi)) /(20 * exp));
   }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new Rat(port).start();
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
    }
   
    public static class Trilateration {
	 
	public static class Vec2 {
		public final double x, y;
		public Vec2(double x, double y) { this.x= x; this.y=y;}
	}
	
	public static Vec2 toXY(double lat, double lon, double lat0, double lon0) {
		double R = 6371000.0;// Earth radius in meters
		doubel x = Math.toRadians(lon - lon0) * R * Math.cos(Math.toRadians(lat0));
		doubel y = Math.toRadians(lat -lat0) * R;
		return new Vec2(x,y);
	}

	public static double[] toLatLon(double x, double y, double lat0, double lon0) {
		double R = 6371000.0;
		double lat = lat0 + Math.toDegrees(y/R);
		double lon = lon0 + Math.toDegrees(x / (R * Math.cos(Math.toRadians(lat0))));
		return new double[]{lat,lon};

	}
	
	public static Vec2 trilaterate(Vec2 p1, Vec2 p2, Vec2 p3, double r1, double r2, double r3) {
		double exx =p2.x -p1.x;
		double exy =p2.y -p1.y;
		double d=Math.hypot(exx, exy);
		if(d==0) return null;

		double exNormx = exx /d;
		double exNormy = exy /d;
		
		double p3p1x = p3.x - p1.x;
		double p3p1y = p3.y - p1.y;
		double i = exNormx * p3p1x + exNormy * p3p1y;
		
		double auxx = p3p1x - i * exNormx;
		double auxy = p3p1y - i * exNormy;
		double j = Math.hypot(auxx ,auxy);

		if(j ==0 ) return null;
	
		double eyx = auxx /j;
		double eyy = auxy / j;
		
		double x =(r1*r1 - r2*r2 + d*d) / (2*d);
		double y =(r1*r1 - r3*r3 + i*i + j*j -2*i*x) / (2*j);
		
		double finalx = p1.x + x * exNormx + y * eyx;
		double finaly = p1.y + x * exNormy + y * eyy;
		return new Vec2(finalx,finaly);
	}
	
	public static double[] trilaterateGPS(
		double lat1 , double lon1, double r1,
		double lat2 , double lon2, double r2,
		double lat3 , double lon3, double r3)  {
	
		double lat0 = lat1;
		double lon0 = lon1;
		
		Vec2 p1 = toXY(lat1, lon1, lat0,lon0);
		Vec2 p2 = toXY(lat2, lon2, lat0,lon0);
		Vec2 p3 = toXY(lat3 ,lon3 , lat0, lon0);

		Vec2 res = trilaterate(p1, p2, p3 ,r1 ,r2 ,r3);
		if(res == null) return null;
		return toLatLon(res.x, res.y, lat0, lon0);
	}
   }

  
	
	public static class Kalman2D {
    // State vector: [x, y, vx, vy]
    private final double[] x = new double[4]; // state
    private final double[][] P = new double[4][4]; // covariance
    private final double[][] Q = new double[4][4]; // process noise
    private final double[][] R = new double[2][2]; // measurement noise

    public Kalman2D(double measVar, double processPosVar, double processVelVar) {
        // Initialize covariances
        for (int i=0;i<4;i++) for (int j=0;j<4;j++) P[i][j] = 0.0;
        // small initial uncertainty
        P[0][0] = P[1][1] = 1;
        P[2][2] = P[3][3] = 1;

        // process noise Q: tune as needed
        Q[0][0] = processPosVar; Q[1][1] = processPosVar;
        Q[2][2] = processVelVar; Q[3][3] = processVelVar;

        // measurement noise R for x,y
        R[0][0] = measVar; R[1][1] = measVar;
    }

    // initialize state position (x,y) and zero velocity
    public void init(double px, double py) {
        x[0] = px; x[1] = py; x[2] = 0.0; x[3] = 0.0;
    }

    // Predict step for time delta t (seconds)
    public void predict(double dt) {
        // State transition: x' = F x
        // F = [[1 0 dt 0],
        //      [0 1 0 dt],
        //      [0 0 1  0],
        //      [0 0 0  1]]
        double[] xpred = new double[4];
        xpred[0] = x[0] + x[2] * dt;
        xpred[1] = x[1] + x[3] * dt;
        xpred[2] = x[2];
        xpred[3] = x[3];

        // P' = F P F^T + Q
        double[][] Pnew = new double[4][4];
        for (int i=0;i<4;i++) for (int j=0;j<4;j++) Pnew[i][j] = 0.0;

        // manual multiplication for this small F
        // compute Pnew = F * P * F^T + Q
        for (int i=0;i<4;i++) {
            for (int j=0;j<4;j++) {
                double sum = 0;
                for (int k=0;k<4;k++) {
                    double Fi_k = ((i==0||i==1) && (k==2||k==3)) ? ((i==0 && k==2) ? dt : (i==1 && k==3) ? dt : 0) :
                                  ((i==k) ? 1.0 : 0.0);
                    double F_jk = ((j==0||j==1) && (k==2||k==3)) ? ((j==0 && k==2) ? dt : (j==1 && k==3) ? dt : 0) :
                                  ((j==k) ? 1.0 : 0.0);
                    // This is not fully general but adequate for small matrix
                    // Simpler: compute explicit Pnew entries
                }
            }
        }

        // Simpler explicit calculation for Pnew (derived for constant-velocity)
        // Pnew[0][0] = P00 + dt*(P02+P20) + dt*dt*P22 + Q00  etc.
        double P00 = P[0][0], P01 = P[0][1], P02 = P[0][2], P03 = P[0][3];
        double P10 = P[1][0], P11 = P[1][1], P12 = P[1][2], P13 = P[1][3];
        double P20 = P[2][0], P21 = P[2][1], P22 = P[2][2], P23 = P[2][3];
        double P30 = P[3][0], P31 = P[3][1], P32 = P[3][2], P33 = P[3][3];

        double dt2 = dt*dt;
        double[][] Pp = new double[4][4];
        Pp[0][0] = P00 + dt*(P02 + P20) + dt2*P22 + Q[0][0];
        Pp[0][1] = P01 + dt*(P03 + P21) + dt2*P23 + Q[0][1];
        Pp[1][0] = P10 + dt*(P12 + P30) + dt2*P32 + Q[1][0];
        Pp[1][1] = P11 + dt*(P13 + P31) + dt2*P33 + Q[1][1];

        Pp[0][2] = P02 + dt*P22;
        Pp[0][3] = P03 + dt*P23;
        Pp[1][2] = P12 + dt*P32;
        Pp[1][3] = P13 + dt*P33;

        Pp[2][0] = P20 + dt*P22;
        Pp[2][1] = P21 + dt*P23;
        Pp[3][0] = P30 + dt*P32;
        Pp[3][1] = P31 + dt*P33;

        Pp[2][2] = P22 + Q[2][2];
        Pp[2][3] = P23 + Q[2][3];
        Pp[3][2] = P32 + Q[3][2];
        Pp[3][3] = P33 + Q[3][3];

        // copy back
        for (int i=0;i<4;i++) for (int j=0;j<4;j++) P[i][j] = Pp[i][j];

        // finally state
        System.arraycopy(xpred, 0, x, 0, 4);
    }

    // Update with a measurement z = [mx, my]
    public void update(double mx, double my) {
        // measurement matrix H = [[1 0 0 0], [0 1 0 0]]
        // S = HPH^T + R -> 2x2
        double S00 = P[0][0] + R[0][0];
        double S01 = P[0][1] + R[0][1];
        double S10 = P[1][0] + R[1][0];
        double S11 = P[1][1] + R[1][1];

        // invert S (2x2)
        double det = S00*S11 - S01*S10;
        if (Math.abs(det) < 1e-9) return;
        double inv00 = S11 / det;
        double inv01 = -S01 / det;
        double inv10 = -S10 / det;
        double inv11 = S00 / det;

        // Compute Kalman gain K = P * H^T * S^-1  -> 4x2
        double[] K0 = new double[2];
        double[] K1 = new double[2];
        double[] K2 = new double[2];
        double[] K3 = new double[2];

        // P * H^T is first two columns of P
        K0[0] = P[0][0]*inv00 + P[0][1]*inv10; K0[1] = P[0][0]*inv01 + P[0][1]*inv11;
        K1[0] = P[1][0]*inv00 + P[1][1]*inv10; K1[1] = P[1][0]*inv01 + P[1][1]*inv11;
        K2[0] = P[2][0]*inv00 + P[2][1]*inv10; K2[1] = P[2][0]*inv01 + P[2][1]*inv11;
        K3[0] = P[3][0]*inv00 + P[3][1]*inv10; K3[1] = P[3][0]*inv01 + P[3][1]*inv11;

        // measurement residual
        double[] y = new double[]{ mx - x[0], my - x[1] };

        // x = x + K*y
        x[0] += K0[0]*y[0] + K0[1]*y[1];
        x[1] += K1[0]*y[0] + K1[1]*y[1];
        x[2] += K2[0]*y[0] + K2[1]*y[1];
        x[3] += K3[0]*y[0] + K3[1]*y[1];

        // P = (I - K*H) * P -> update covariance (optimized for H)
        double[][] KH = new double[4][4]; // (K*H) effect only on first two columns
        for (int r=0;r<4;r++) {
            KH[r][0] = (r==0?K0[0]: r==1?K1[0] : r==2?K2[0] : K3[0]);
            KH[r][1] = (r==0?K0[1]: r==1?K1[1] : r==2?K2[1] : K3[1]);
            KH[r][2] = 0; KH[r][3] = 0;
        }
        double[][] IminusKH = new double[4][4];
        for (int i=0;i<4;i++) for (int j=0;j<4;j++) {
            double ij = (i==j)?1.0:0.0;
            IminusKH[i][j] = ij - KH[i][j];
        }
        double[][] Pnew = new double[4][4];
        for (int i=0;i<4;i++) for (int j=0;j<4;j++) {
            double sum=0;
            for (int k=0;k<4;k++) sum += IminusKH[i][k] * P[k][j];
            Pnew[i][j] = sum;
        }
        for (int i=0;i<4;i++) for (int j=0;j<4;j++) P[i][j] = Pnew[i][j];
    }

    public double getX() { return x[0]; }
    public double getY() { return x[1]; }
}

		
    public static class AP {
        public String ssid = "<hidden>";
        public String bssid = "";
        public String security = "OPEN";
        public int signal = -100;
        public int channel = 0;
	public double lat = 0.0;
	public double lon = 0.0;
	public long lastSeen = System.currentTimeMillis();

	public final Deque<Sample> samples = new ArrayDeque<>();
	public kalman2D kalman = null;
    }

    public static class sample {
	public final double lat;
	public final double lon;
	public final double distance;
	public final long ts;

	public sample(double lat, double lon , double distance) {
		this.lat=lat;
		this.lon=lon;
		this.distance = distance;
		this.ts =System.currentTimeMillis();
	}
   }

    private static final String DASHBOARD_HTML = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>RAT SWARM v2</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<style>
  body{margin:0;background:#000;color:#0f0;font-family:monospace;overflow:hidden}
  #map{width:100vw;height:100vh}
  .header{position:fixed;top:0;left:0;right:0;z-index:1000;background:#000c;padding:10px;font-size:14px;border-bottom:1px solid #0f0}
  @keyframes blink{0%,100%{opacity:1}50%{opacity:0.3}}
</style></head><body>
<div class="header">
  <strong>RAT SWARM v2</strong> | <span id="count">0</span> APs | <span id="status">Connecting...</span>
</div>
<div id="map"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const map = L.map('map').setView([-15.3875, 28.3228], 15);  // Change to Lusaka
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);

const markers = {};
let userLocationSet = false;

navigator.geolocation?.watchPosition(pos => {
    const lat = pos.coords.latitude;
    const lon = pos.coords.longitude;
    map.setView([lat, lon], 16);
    if (!userLocationSet) {
        L.marker([lat, lon]).addTo(map).bindPopup("YOU ARE HERE").openPopup();
        userLocationSet = true;
    }
});

const evt = new EventSource('/ws');
evt.onopen = () => document.getElementById('status').textContent = 'LIVE';

evt.onmessage = e => {
    const d = JSON.parse(e.data);
    if (d.type !== 'full') return;
    
    document.getElementById('count').textContent = Object.keys(d.aps).length;

    Object.entries(d.aps).forEach(([key, ap]) => {
        if (markers[key]) {
            // Update existing marker position & popup
            markers[key].setLatLng([ap.lat, ap.lon]);
            markers[key].setPopupContent(
                `<b>${ap.ssid}</b><br>${ap.bssid}<br>${ap.security}<br>${ap.signal}dBm<br>${ap.vendor}`
            );
        } else {
            // Create new marker
            const color = ap.security.includes('OPEN') ? 'red' : 
                         ap.signal > -65 ? 'green' : 'orange';
            const marker = L.circleMarker([ap.lat, ap.lon], {
                radius: 8,
                color: color,
                fillOpacity: 0.8
            }).addTo(map);
            
            marker.bindPopup(
                `<b>${ap.ssid}</b><br>${ap.bssid}<br>${ap.security}<br>${ap.signal}dBm<br>${ap.vendor}`
            );
            markers[key] = marker;
        }
    });
};
</script>
</body></html>
""";

    private static class WebSocketHandshake {
        static String accept(String key) {
            String magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(magic.getBytes(StandardCharsets.UTF_8));
                return java.util.Base64.getEncoder().encodeToString(digest);
            } catch (Exception e) { return ""; }
        }
    }
    
    public Deauther getDeauther() {
    return deauther;
}

}