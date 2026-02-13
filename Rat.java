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
    private OuiDatabase ouiDatabase;
    private WigleGeolocator geolocator;

    public Rat(int port) {
        this.httpPort = port;
        this.ouiDatabase = new OuiDatabase(this);
        this.geolocator = new WigleGeolocator(this);
        
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
        println("Broadcasting to " + websocketClients.size() + " clients: " + json.substring(0, Math.min(100, json.length())) + "...");
        Iterator<HttpExchange> it = websocketClients.iterator();
        while (it.hasNext()) {
            HttpExchange ex = it.next();
            try {
               	String msg ="data: " +json+ "\n\n";
		ex.getResponseBody().write(msg.getBytes(StandardCharsets.UTF_8));
		ex.getResponseBody().flush();
            } catch (Exception e) {
                websocketClients.remove(ex);
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
	
		String vendor = ouiDatabase.lookup(ap.bssid);
        String detailedInfo = ouiDatabase.getDetailedInfo(ap.bssid);

        WigleGeolocator.GeoResult geo = geolocator.geolocate(ap.bssid, ap.ssid);

        double lat, lon ;
        if(geo.success) {
            lat = geo.lat;
            lon = geo.lon;
            ap.source = geo.source;
        }else{ 
            WigleGeolocator.GeoResult approx = geolocator.getApproximateLocation();

            if(approx.success) {
                double offset = 0.01 * (Math.random() - 0.5);
                lat = approx.lat + offset;
                lon = approx.lon + offset;
                ap.source ="Approx" + approx.source;
            }else {
                double baseLat = -15.3875;
                double baseLon = 28.3228;
                lat = baseLat + (Math.random() - 0.5) * 0.01;
                lon = baseLon + (Math.random() - 0.5) * 0.01;
                ap.source = "Random Jitter";
            }
        }

        ap.lat = lat;
        ap.lon = lon;


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

	double baseLat = -15.3875;
	double baseLon = 28.3228;
	double jitterLat =(Math.random() - 0.5) * 0.01;
	double jitterLon =(Math.random() - 0.5) * 0.01;
	ap.lat = baseLat + jitterLat;
	ap.lon = baseLon + jitterLon;
	ap.lastSeen = System.currentTimeMillis();

	if(existing == null) {
		seenById.put(id,ap);
		printlnAlert("NEW AP -> " +summarize(ap));
        println("Total APs in memory "+ seenById.size());
		if(isOpen(ap)) {
			printlnStrongAlert("OPEN NETWORK -> " + ap.ssid);
			soundBell();
		}
	}else{
		existing.ssid = ap.ssid.isEmpty() ? existing.ssid : ap.ssid;
		existing.security = ap.security;
		existing.channel = ap.channel;
		existing.signal = Math.max(existing.signal, ap.signal);
		existing.lat = ap.lat;
		existing.lon = ap.lon;
		existing.lastSeen = ap.lastSeen;
	}
	ai.seeAP(ap.bssid, ap.ssid, ap.channel, ap.security);
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

            httpServer.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                println("Http REQUEST : "+exchange.getRequestMethod() + " "+ path);
                if(path.equals("/") || path.equals("/index.html")){

                        if("GET".equals(exchange.getRequestMethod())) {
                            println("Serving dashboard HTML to " + exchange.getRemoteAddress());
                            byte[]html = DASHBOARD_HTML.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");   
                            exchange.sendResponseHeaders(200,html.length);
                            exchange.getResponseBody().write(html);
                            exchange.getResponseBody().close();
			                
		                }else{ exchange.sendResponseHeaders(405, -1);}

                }else{
                     println("404 for path: "+path); 
                     exchange.sendResponseHeaders(405, -1);
                }
                exchange.close();
            });
		
            httpServer.createContext("/sse", exchange -> {
                println("New SSE connection from " + exchange.getRemoteAddress());
                if(!"GET".equals(exchange.getRequestMethod())){
                    exchange.sendResponseHeaders(405,-1);
                    exchange.close();
                    return;
                }

		Headers h =exchange.getResponseHeaders();
		h.set("Content-Type","text/event-stream");
		h.set("Cache-Control","no-cache");
		h.set("Connection","keep-alive");
        h.set("Access-Control-Allow-Origin","*");
        exchange.sendResponseHeaders(200,0);
        OutputStream os=exchange.getResponseBody();

        try{
		   
		    os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
		    os.flush();
            websocketClients.add(exchange);
            println("Debug:  sent initial sse message");

            while(!Thread.currentThread().isInterrupted()) {
                try{
                    Thread.sleep(15000);
                    os.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();

                }catch(IOException e) {
                    break;
                }
            }
        }catch(Exception e){
            println("Debug: Error sending initial message: "+e.getMessage());

        }finally{
            websocketClients.remove(exchange);
            try { exchange.close();}catch(Exception ignored){}
        }
		
	    });
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
        println("Web dashboard started on -> http://localhost:" + httpPort);
        println("WebSocket endpoint -> ws://localhost:" + httpPort + "/ws");
        } catch (IOException e) {
            println("Failed to start HTTP server: " + e.getMessage());
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

    // NOTE TO SELF : improve function below to broaden oui identifit=ction
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

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new Rat(port).start();
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
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
    public String source = "Unknown";
    }

    
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
    private static final String DASHBOARD_HTML = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>RAT SWARM v2</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<style>
  body {
    margin: 0;
    background: #000;
    color: #0f0;
    font-family: monospace;
    overflow: hidden;
  }
  #map {
    width: 100vw;
    height: 100vh;
  }
  .header {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    z-index: 1000;
    background: rgba(0, 0, 0, 0.8);
    padding: 10px;
    font-size: 14px;
    border-bottom: 1px solid #0f0;
    backdrop-filter: blur(5px);
  }
  .status-connected { color: #0f0; }
  .status-disconnected { color: #f00; }
  .status-connecting { color: #ff0; }
  @keyframes blink {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.3; }
  }
  #ap-list {
    position: fixed;
    top: 50px;
    right: 10px;
    width: 300px;
    max-height: 80vh;
    overflow-y: auto;
    background: rgba(0, 0, 0, 0.8);
    border: 1px solid #0f0;
    padding: 10px;
    font-size: 12px;
    display: none;
  }
  .ap-item {
    margin: 5px 0;
    padding: 5px;
    border-bottom: 1px solid #333;
  }
  .ap-open { color: #f00; }
  .ap-strong { color: #0f0; }
  .ap-weak { color: #ff0; }
</style>
</head>
<body>
<div class="header">
  <strong>RAT SWARM v2</strong> | 
  <span id="count">0</span> APs | 
  Status: <span id="status" class="status-connecting">Connecting...</span> |
  <button onclick="toggleList()">Show List</button>
</div>
<div id="map"></div>
<div id="ap-list">
  <h4>Access Points</h4>
  <div id="ap-list-content"></div>
</div>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const map = L.map('map').setView([-15.3875, 28.3228], 15);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

const markers = {};
let userLocationSet = false;
let apListVisible = false;

if (navigator.geolocation) {
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      const lat = pos.coords.latitude;
      const lon = pos.coords.longitude;
      map.setView([lat, lon], 16);
      L.marker([lat, lon])
        .addTo(map)
        .bindPopup("<b>YOU ARE HERE</b>")
        .openPopup();
      userLocationSet = true;
    },
    (err) => {
      console.log('Geolocation error:', err.message);
    },
    { timeout: 5000 }
  );
}

let evt = null;
let reconnectAttempts = 0;
const maxReconnectAttempts = 10;
const reconnectDelay = 2000;

function connectToServer() {
  const statusEl = document.getElementById('status');
  
  if (evt) {
    evt.close();
  }
  
  statusEl.textContent = 'Connecting...';
  statusEl.className = 'status-connecting';
  
  try {
    evt = new EventSource('/sse');
    
    evt.onopen = () => {
      statusEl.textContent = 'LIVE';
      statusEl.className = 'status-connected';
      reconnectAttempts = 0;
      console.log('Connected to RAT server');
    };
    
    evt.onmessage = (e) => {
      try {
        const d = JSON.parse(e.data);
        if (d.type === 'full') {
          updateDisplay(d.aps);
        }
      } catch (parseError) {
        console.error('Error parsing JSON:', parseError);
      }
    };
    
    evt.onerror = (err) => {
      statusEl.textContent = 'Disconnected';
      statusEl.className = 'status-disconnected';
      console.error('EventSource error:', err);
      
      evt.close();
      
      if (reconnectAttempts < maxReconnectAttempts) {
        reconnectAttempts++;
        console.log('Reconnecting in ' + reconnectDelay + 'ms (attempt ' + reconnectAttempts + '/' + maxReconnectAttempts + ')');
        setTimeout(connectToServer, reconnectDelay);
      } else {
        statusEl.textContent = 'Failed to connect';
        console.error('Max reconnection attempts reached');
      }
    };
    
  } catch (error) {
    console.error('Failed to create EventSource:', error);
    statusEl.textContent = 'Connection failed';
    statusEl.className = 'status-disconnected';
    
    if (reconnectAttempts < maxReconnectAttempts) {
      reconnectAttempts++;
      setTimeout(connectToServer, reconnectDelay);
    }
  }
}

function updateDisplay(aps) {
  const countEl = document.getElementById('count');
  const listContent = document.getElementById('ap-list-content');
  
  countEl.textContent = Object.keys(aps).length;
  
  if (apListVisible) {
    listContent.innerHTML = '';
  }
  
  Object.entries(aps).forEach(([key, ap]) => {
    let color = 'orange';
    let radius = 8;
    
    if (ap.security && ap.security.includes('OPEN')) {
      color = 'red';
      radius = 10;
    } else if (ap.signal > -65) {
      color = 'green';
    }
    
    if (markers[key]) {
      markers[key].setLatLng([ap.lat, ap.lon]);
      markers[key].setStyle({ color: color, radius: radius });
      markers[key].setPopupContent(createPopupContent(ap));
    } else {
      const marker = L.circleMarker([ap.lat, ap.lon], {
        radius: radius,
        color: color,
        fillColor: color,
        fillOpacity: 0.7,
        weight: 2
      }).addTo(map);
      
      marker.bindPopup(createPopupContent(ap));
      markers[key] = marker;
      
      if (apListVisible) {
        const apItem = document.createElement('div');
        apItem.className = 'ap-item';
        if (color === 'red') apItem.classList.add('ap-open');
        else if (color === 'green') apItem.classList.add('ap-strong');
        else apItem.classList.add('ap-weak');
        
        apItem.innerHTML = '<strong>' + ap.ssid + '</strong><br>' +
                           '<small>' + ap.bssid + '</small><br>' +
                           'Sig: ' + ap.signal + 'dBm | Ch: ' + ap.channel + ' | ' + ap.security + '<br>' +
                           'Vendor: ' + ap.vendor;
        listContent.appendChild(apItem);
      }
    }
  });
  
  Object.keys(markers).forEach(key => {
    if (!aps[key]) {
      map.removeLayer(markers[key]);
      delete markers[key];
    }
  });
}

function createPopupContent(ap) {
  return '<div style="font-family: monospace; font-size: 12px;">' +
         '<strong>' + ap.ssid + '</strong><br>' +
         '<small>' + ap.bssid + '</small><br>' +
         '<hr style="margin: 5px 0; border-color: #333;">' +
         '<b>Security:</b> ' + ap.security + '<br>' +
         '<b>Signal:</b> ' + ap.signal + 'dBm<br>' +
         '<b>Channel:</b> ' + ap.channel + '<br>' +
         '<b>Vendor:</b> ' + ap.vendor + '<br>' +
         '<b>Coordinates:</b> ' + ap.lat.toFixed(6) + ', ' + ap.lon.toFixed(6) +
         '</div>';
}

function toggleList() {
  const listEl = document.getElementById('ap-list');
  apListVisible = !apListVisible;
  
  if (apListVisible) {
    listEl.style.display = 'block';
  } else {
    listEl.style.display = 'none';
  }
}

connectToServer();

map.on('click', (e) => {
  console.log('Map clicked at:', e.latlng);
});

setInterval(() => {
  if (evt && evt.readyState === EventSource.CLOSED) {
    console.log('Connection closed, attempting reconnect...');
    connectToServer();
  }
}, 30000);
</script>
</body>
</html>
""";
    public Deauther getDeauther() {
    return deauther;
}



} 
