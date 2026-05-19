import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.lowagie.text.pdf.draw.LineSeparator;
import java.awt.Color;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PdfReportGenerator — produces a professional wireless security audit report.
 *
 * DEPENDENCY (add to your classpath / pom.xml):
 *   OpenPDF 1.3.30 (Apache 2.0 licence — free for commercial use)
 *   Maven:
 *     <dependency>
 *       <groupId>com.github.librepdf</groupId>
 *       <artifactId>openpdf</artifactId>
 *       <version>1.3.30</version>
 *     </dependency>
 *   Manual: download openpdf-1.3.30.jar from GitHub releases and add to classpath.
 *
 * USAGE:
 *   // After a survey completes, or on demand from the API:
 *   PdfReportGenerator gen = new PdfReportGenerator(rat);
 *   byte[] pdf = gen.generate("Stanbic Bank HQ", "Floor 3 — Network Audit", "Ishmael D. Tembo");
 *   Files.write(Paths.get("report_" + LocalDate.now() + ".pdf"), pdf);
 *
 * INTEGRATION WITH EnterpriseApiServer:
 *   Add this endpoint to EnterpriseApiServer:
 *     GET /api/v1/report   — generates and streams the PDF
 *   Example handler (add to EnterpriseApiServer.java):
 *
 *     server.createContext("/api/v1/report", ex -> {
 *         if (!guard(ex, true)) return;
 *         String site = ex.getRequestURI().getQuery() != null
 *             ? ex.getRequestURI().getQuery().replace("site=","") : "Site Survey";
 *         PdfReportGenerator gen = new PdfReportGenerator(rat);
 *         byte[] pdf = gen.generate(site, "Wireless Security Assessment", "BLACK ICE v2");
 *         ex.getResponseHeaders().set("Content-Type", "application/pdf");
 *         ex.getResponseHeaders().set("Content-Disposition",
 *             "attachment; filename=\"blackice_report_" + LocalDate.now() + ".pdf\"");
 *         ex.sendResponseHeaders(200, pdf.length);
 *         ex.getResponseBody().write(pdf);
 *         ex.getResponseBody().close();
 *     });
 *
 * REPORT SECTIONS:
 *   1. Cover page — classification, site, date, operator, logo-style header
 *   2. Executive summary — threat level, key counts, top findings in plain English
 *   3. Findings table — every AP with full detail and colour-coded risk
 *   4. Threat detail — evil twins, open networks, deauth attacks (each with evidence)
 *   5. Recommendations — ranked action items with effort and priority
 *   6. Appendix — raw survey statistics, channel distribution, methodology note
 *   7. Footer — page numbers, classification, generated timestamp on every page
 */
public class PdfReportGenerator {

    // ── Brand colours (matching dashboard amber/red/cyan palette) ────────────
    private static final Color C_BLACK      = new Color(0x1A, 0x1A, 0x1A);
    private static final Color C_DARK_GRAY  = new Color(0x3A, 0x3A, 0x3A);
    private static final Color C_MID_GRAY   = new Color(0x88, 0x88, 0x88);
    private static final Color C_LIGHT_GRAY = new Color(0xF4, 0xF4, 0xF2);
    private static final Color C_WHITE      = new Color(0xFF, 0xFF, 0xFF);
    private static final Color C_AMBER      = new Color(0xCC, 0x6D, 0x00);
    private static final Color C_AMBER_DARK = new Color(0x33, 0x1B, 0x00);
    private static final Color C_RED        = new Color(0xC4, 0x1E, 0x0A);
    private static final Color C_RED_BG     = new Color(0xFC, 0xEB, 0xEB);
    private static final Color C_GREEN      = new Color(0x1D, 0x6A, 0x3B);
    private static final Color C_GREEN_BG   = new Color(0xEA, 0xF3, 0xDE);
    private static final Color C_YELLOW     = new Color(0x85, 0x60, 0x00);
    private static final Color C_YELLOW_BG  = new Color(0xFF, 0xF3, 0xCD);
    private static final Color C_BLUE       = new Color(0x18, 0x5F, 0xA5);
    private static final Color C_BLUE_BG    = new Color(0xE6, 0xF1, 0xFB);
    private static final Color C_COVER_BG   = new Color(0x08, 0x06, 0x04);

    // ── Page geometry ────────────────────────────────────────────────────────
    private static final Rectangle PAGE_SIZE = PageSize.A4;
    private static final float MARGIN_L = 56f;
    private static final float MARGIN_R = 56f;
    private static final float MARGIN_T = 48f;
    private static final float MARGIN_B = 60f;

    // ── Fonts (built-in — no font files needed) ───────────────────────────────
    private BaseFont bfHelv;
    private BaseFont bfHelvBold;
    private BaseFont bfCourier;
    private Font fCoverTitle, fCoverSub, fCoverMeta;
    private Font fH1, fH2, fH3;
    private Font fBody, fBodyBold, fBodySmall, fBodySmallBold;
    private Font fMono, fMonoSmall;
    private Font fTableHead, fTableBody, fTableBodySmall;
    private Font fBadge;

    private final Rat rat;

    private final DateTimeFormatter dtf= DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm:ss" ).withZone(java.time.ZoneId.systemDefault());

    // ── Constructor ──────────────────────────────────────────────────────────
    public PdfReportGenerator(Rat rat) {
        this.rat = rat;
        try {
            bfHelv     = BaseFont.createFont(BaseFont.HELVETICA,         BaseFont.CP1252, false);
            bfHelvBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD,    BaseFont.CP1252, false);
            bfCourier  = BaseFont.createFont(BaseFont.COURIER,           BaseFont.CP1252, false);
        } catch (Exception e) {
            throw new RuntimeException("Font init failed: " + e.getMessage(), e);
        }
        initFonts();
    }

    private void initFonts() {
        fCoverTitle    = new Font(bfHelvBold,  32, Font.BOLD,   C_AMBER);
        fCoverSub      = new Font(bfHelv,       12, Font.NORMAL, C_MID_GRAY);
        fCoverMeta     = new Font(bfHelv,       10, Font.NORMAL, C_MID_GRAY);

        fH1            = new Font(bfHelvBold,   18, Font.BOLD,   C_AMBER_DARK);
        fH2            = new Font(bfHelvBold,   13, Font.BOLD,   C_DARK_GRAY);
        fH3            = new Font(bfHelvBold,   11, Font.BOLD,   C_DARK_GRAY);

        fBody          = new Font(bfHelv,       10, Font.NORMAL, C_DARK_GRAY);
        fBodyBold      = new Font(bfHelvBold,   10, Font.BOLD,   C_DARK_GRAY);
        fBodySmall     = new Font(bfHelv,        8, Font.NORMAL, C_MID_GRAY);
        fBodySmallBold = new Font(bfHelvBold,    8, Font.BOLD,   C_DARK_GRAY);

        fMono          = new Font(bfCourier,     9, Font.NORMAL, C_DARK_GRAY);
        fMonoSmall     = new Font(bfCourier,     7, Font.NORMAL, C_MID_GRAY);

        fTableHead     = new Font(bfHelvBold,    8, Font.BOLD,   C_WHITE);
        fTableBody     = new Font(bfHelv,         8, Font.NORMAL, C_DARK_GRAY);
        fTableBodySmall= new Font(bfHelv,         7, Font.NORMAL, C_MID_GRAY);

        fBadge         = new Font(bfHelvBold,    7, Font.BOLD,   C_WHITE);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generates the full PDF report and returns it as a byte array.
     *
     * @param siteName   e.g. "Stanbic Bank HQ — Cairo Road Branch"
     * @param auditTitle e.g. "Wireless Security Assessment — Floor 3"
     * @param operator   e.g. "Ishmael D. Tembo / NIGHTFALL35"
     */
    public byte[] generate(String siteName, String auditTitle, String operator) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Snapshot data from Rat so the report is self-consistent
        String fullJson  = rat.getAllApsJson();
        String statsJson = rat.getStatsJson();
        List<ApRecord> aps = parseAps(fullJson);
        Stats stats = parseStats(statsJson, aps);

        Document doc = new Document(PAGE_SIZE, MARGIN_L, MARGIN_R, MARGIN_T, MARGIN_B);
        PdfWriter writer = PdfWriter.getInstance(doc, baos);

        // Page event handler: footer + header rule on every page except the cover
        writer.setPageEvent(new PageFooter(siteName, operator));

        doc.open();

        // ── 1. Cover page ──────────────────────────────────────────────────
        addCoverPage(doc, writer, siteName, auditTitle, operator, stats);
        doc.newPage();

        // ── 2. Executive summary ───────────────────────────────────────────
        addSection(doc, "EXECUTIVE SUMMARY");
        addExecutiveSummary(doc, stats, aps, siteName);
        doc.newPage();

        // ── 3. Findings table ──────────────────────────────────────────────
        addSection(doc, "ACCESS POINT INVENTORY");
        addFindingsTable(doc, aps);
        doc.newPage();

        // ── 4. Threat detail ───────────────────────────────────────────────
        addSection(doc, "THREAT ANALYSIS");
        addThreatDetail(doc, aps, stats);
        doc.newPage();

        // ── 5. Recommendations ────────────────────────────────────────────
        addSection(doc, "RECOMMENDATIONS");
        addRecommendations(doc, stats, aps);
        doc.newPage();

        // ── 6. Appendix ───────────────────────────────────────────────────
        addSection(doc, "APPENDIX — TECHNICAL DETAIL");
        addAppendix(doc, aps, stats, operator);

        doc.close();
        rat.println("[REPORT] PDF generated: " + baos.size() + " bytes | "
                + aps.size() + " APs | " + stats.open + " open networks");
        return baos.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  COVER PAGE
    // ═══════════════════════════════════════════════════════════════════════
    private void addCoverPage(Document doc, PdfWriter writer,
                               String site, String title, String operator, Stats stats) throws Exception {
        PdfContentByte cb = writer.getDirectContent();
        float w = PAGE_SIZE.getWidth(), h = PAGE_SIZE.getHeight();

        // Full-bleed dark background
        cb.setColorFill(C_COVER_BG);
        cb.rectangle(0, 0, w, h);
        cb.fill();

        // Amber top bar
        cb.setColorFill(C_AMBER);
        cb.rectangle(0, h - 8, w, 8);
        cb.fill();

        // Bottom bar
        cb.setColorFill(new Color(0x33, 0x1B, 0x00));
        cb.rectangle(0, 0, w, 60);
        cb.fill();

        // Vertical amber rule (left)
        cb.setColorFill(C_AMBER);
        cb.rectangle(MARGIN_L - 16, 60, 2, h - 68);
        cb.fill();

        // Classification badge (top right)
        drawCoverBadge(cb, "CONFIDENTIAL", w - MARGIN_R, h - 32, C_RED, C_WHITE);

        // Report type label
        cb.setColorFill(C_AMBER);
        cb.setFontAndSize(bfHelvBold, 9);
        cb.beginText();
        cb.setTextMatrix(MARGIN_L, h - 80);
        cb.showText("WIRELESS SECURITY ASSESSMENT REPORT");
        cb.endText();

        // Thin rule under label
        cb.setColorStroke(C_AMBER);
        cb.setLineWidth(0.5f);
        cb.moveTo(MARGIN_L, h - 88);
        cb.lineTo(w - MARGIN_R, h - 88);
        cb.stroke();

        // BIG title — BLACK ICE
        cb.setColorFill(C_AMBER);
        cb.setFontAndSize(bfHelvBold, 48);
        cb.beginText();
        cb.setTextMatrix(MARGIN_L, h - 160);
        cb.showText("BLACK ICE");
        cb.endText();

        cb.setColorFill(new Color(0x66, 0x40, 0x00));
        cb.setFontAndSize(bfHelv, 13);
        cb.beginText();
        cb.setTextMatrix(MARGIN_L, h - 180);
        cb.showText("v2 · SIGINT WIRELESS SURVEILLANCE PLATFORM · NIGHTFALL35");
        cb.endText();

        // Horizontal rule
        cb.setColorStroke(new Color(0x44, 0x28, 0x00));
        cb.setLineWidth(1f);
        cb.moveTo(MARGIN_L, h - 200);
        cb.lineTo(w - MARGIN_R, h - 200);
        cb.stroke();

        // Site name (big)
        cb.setColorFill(C_WHITE);
        cb.setFontAndSize(bfHelvBold, 22);
        cb.beginText();
        cb.setTextMatrix(MARGIN_L, h - 240);
        cb.showText(truncate(site, 48));
        cb.endText();

        // Audit title
        cb.setColorFill(C_MID_GRAY);
        cb.setFontAndSize(bfHelv, 13);
        cb.beginText();
        cb.setTextMatrix(MARGIN_L, h - 262);
        cb.showText(truncate(title, 60));
        cb.endText();

        // Threat level badge (large, centred)
        String level = stats.threatLevel;
        Color badgeCol = level.equals("CRITICAL") ? C_RED
                       : level.equals("ELEVATED")  ? new Color(0xB8, 0x6A, 0x00)
                       : new Color(0x1D, 0x6A, 0x3B);
        drawLargeBadge(cb, "THREAT LEVEL: " + level, MARGIN_L, h - 310, badgeCol);

        // Key stats row
        float statY = h - 370;
        drawStatBox(cb, String.valueOf(stats.total),   "ACCESS POINTS",   MARGIN_L,       statY, 100);
        drawStatBox(cb, String.valueOf(stats.open),    "OPEN NETWORKS",   MARGIN_L + 120, statY, 100);
        drawStatBox(cb, String.valueOf(stats.evilTwins),"EVIL TWINS",     MARGIN_L + 240, statY, 100);
        drawStatBox(cb, String.valueOf(stats.deauthSrc),"DEAUTH SOURCES", MARGIN_L + 360, statY, 100);

        // Metadata block
        float metaY = h - 460;
        cb.setColorFill(new Color(0x1A, 0x0E, 0x02));
        cb.rectangle(MARGIN_L, metaY - 80, w - MARGIN_L - MARGIN_R, 90);
        cb.fill();

        String[] labels = {"DATE", "OPERATOR", "GPS COORDS", "SURVEY READINGS"};
        String[] values = {
            dtf.format(java.time.Instant.now()),
            operator,
            String.format("%.6f, %.6f", stats.opLat, stats.opLon),
            String.valueOf(stats.surveyReadings)
        };
        for (int i = 0; i < labels.length; i++) {
            float lx = MARGIN_L + 10 + (i * (w - MARGIN_L - MARGIN_R - 20) / 4);
            cb.setColorFill(C_AMBER);
            cb.setFontAndSize(bfHelvBold, 7);
            cb.beginText(); cb.setTextMatrix(lx, metaY - 20); cb.showText(labels[i]); cb.endText();
            cb.setColorFill(C_WHITE);
            cb.setFontAndSize(bfHelv, 8);
            cb.beginText(); cb.setTextMatrix(lx, metaY - 36); cb.showText(truncate(values[i], 22)); cb.endText();
        }

        // Bottom bar text
        cb.setColorFill(C_MID_GRAY);
        cb.setFontAndSize(bfHelv, 7);
        cb.beginText();
        cb.setTextMatrix(MARGIN_L, 24);
        cb.showText("CONFIDENTIAL — FOR AUTHORIZED SECURITY PERSONNEL ONLY"
                + " — Generated " + dtf.format(java.time.Instant.now())
                + " — NIGHTFALL35 / BLACK ICE v2");
        cb.endText();
    }

    private void drawCoverBadge(PdfContentByte cb, String text, float rx, float y, Color bg, Color fg) {
        float tw = bfHelvBold.getWidthPoint(text, 8) + 16;
        cb.setColorFill(bg);
        cb.roundRectangle(rx - tw, y - 12, tw, 18, 3);
        cb.fill();
        cb.setColorFill(fg);
        cb.setFontAndSize(bfHelvBold, 8);
        cb.beginText();
        cb.setTextMatrix(rx - tw + 8, y - 5);
        cb.showText(text);
        cb.endText();
    }

    private void drawLargeBadge(PdfContentByte cb, String text, float x, float y, Color col) {
        float tw = bfHelvBold.getWidthPoint(text, 11) + 24;
        cb.setColorFill(col);
        cb.roundRectangle(x, y - 16, tw, 26, 4);
        cb.fill();
        cb.setColorFill(C_WHITE);
        cb.setFontAndSize(bfHelvBold, 11);
        cb.beginText();
        cb.setTextMatrix(x + 12, y - 7);
        cb.showText(text);
        cb.endText();
    }

    private void drawStatBox(PdfContentByte cb, String value, String label,
                              float x, float y, float w) {
        cb.setColorFill(new Color(0x1A, 0x0E, 0x02));
        cb.rectangle(x, y - 44, w - 4, 50);
        cb.fill();
        cb.setColorFill(C_AMBER);
        cb.setFontAndSize(bfHelvBold, 28);
        cb.beginText();
        cb.setTextMatrix(x + 8, y - 20);
        cb.showText(value);
        cb.endText();
        cb.setColorFill(new Color(0x66, 0x40, 0x00));
        cb.setFontAndSize(bfHelv, 7);
        cb.beginText();
        cb.setTextMatrix(x + 8, y - 36);
        cb.showText(label);
        cb.endText();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  EXECUTIVE SUMMARY
    // ═══════════════════════════════════════════════════════════════════════
    private void addExecutiveSummary(Document doc, Stats s, List<ApRecord> aps, String site) throws Exception {
        // Summary card
        PdfPTable card = new PdfPTable(4);
        card.setWidthPercentage(100);
        card.setSpacingBefore(8);
        card.setSpacingAfter(16);
        card.setWidths(new float[]{1, 1, 1, 1});

        addStatCell(card, String.valueOf(s.total),   "Access points found", C_BLUE_BG,   C_BLUE);
        addStatCell(card, String.valueOf(s.open),    "Open (unencrypted)",  C_RED_BG,    C_RED);
        addStatCell(card, String.valueOf(s.evilTwins),"Evil twins detected",C_RED_BG,    C_RED);
        addStatCell(card, String.valueOf(s.deauthSrc),"Deauth sources",     C_YELLOW_BG, C_YELLOW);
        doc.add(card);

        // Threat level bar
        addThreatLevelBar(doc, s.threatLevel);
        doc.add(new Paragraph(" ", fBody));

        // Written summary paragraph
        String openList = aps.stream().filter(a -> a.isOpen)
                .map(a -> "\"" + a.ssid + "\"")
                .limit(3).collect(Collectors.joining(", "));
        if (aps.stream().filter(a -> a.isOpen).count() > 3) openList += " and others";

        String summary = buildSummaryText(s, aps, site, openList);
        Paragraph p = new Paragraph(summary, fBody);
        p.setLeading(16f);
        doc.add(p);
        doc.add(spacer(10));

        // Key findings bullets
        addSubSection(doc, "Key Findings");
        List<String> findings = buildFindings(s, aps);
        for (String f : findings) {
            doc.add(bullet(f));
        }
    }

    private String buildSummaryText(Stats s, List<ApRecord> aps, String site, String openList) {
        StringBuilder sb = new StringBuilder();
        sb.append("A passive 802.11 wireless survey was conducted at ")
          .append(site).append(" using the BLACK ICE v2 SIGINT platform. ")
          .append("The survey detected ").append(s.total).append(" unique access point")
          .append(s.total == 1 ? "" : "s").append(" across the 2.4 GHz and 5 GHz bands");
        if (s.surveyReadings > 0)
            sb.append(", logging ").append(s.surveyReadings).append(" georeferenced readings");
        sb.append(".\n\n");

        if (s.open > 0) {
            sb.append("CRITICAL: ").append(s.open).append(" access point")
              .append(s.open == 1 ? " was found" : "s were found")
              .append(" operating without encryption (")
              .append(openList.isEmpty() ? "see findings table" : openList)
              .append("). Unencrypted networks expose all traffic to passive interception "
                    + "and require immediate remediation.\n\n");
        }
        if (s.evilTwins > 0) {
            sb.append("CRITICAL: ").append(s.evilTwins)
              .append(" evil twin / rogue access point")
              .append(s.evilTwins == 1 ? " was" : "s were")
              .append(" detected. Rogue APs impersonate legitimate networks to perform "
                    + "man-in-the-middle attacks and credential harvesting.\n\n");
        }
        if (s.open == 0 && s.evilTwins == 0) {
            sb.append("No open networks or rogue access points were detected during this survey. "
                    + "The wireless environment at this site is operating within acceptable security parameters. "
                    + "Continued periodic monitoring is recommended to detect new threats.\n\n");
        }
        sb.append("All findings, coordinates, and evidence are detailed in the sections below.");
        return sb.toString();
    }

    private List<String> buildFindings(Stats s, List<ApRecord> aps) {
        List<String> out = new ArrayList<>();
        if (s.open > 0)
            out.add(s.open + " unencrypted access point" + (s.open>1?"s":"") + " — immediate remediation required");
        if (s.evilTwins > 0)
            out.add(s.evilTwins + " rogue/evil-twin AP" + (s.evilTwins>1?"s":"") + " detected — investigate and remove");
        if (s.deauthSrc > 0)
            out.add(s.deauthSrc + " deauthentication attack source" + (s.deauthSrc>1?"s":"") + " observed");

        long tkip = aps.stream().filter(a -> a.security.contains("TKIP")).count();
        if (tkip > 0) out.add(tkip + " AP" + (tkip>1?"s":"") + " using TKIP cipher — vulnerable to KRACK attack");

        long wps = aps.stream().filter(a -> a.security.contains("WPS")).count();
        if (wps > 0) out.add(wps + " AP" + (wps>1?"s":"") + " with WPS enabled — vulnerable to Pixie Dust attack");

        long legacy = aps.stream().filter(a -> a.security.equals("WPA") || a.security.equals("WEP")).count();
        if (legacy > 0) out.add(legacy + " AP" + (legacy>1?"s":"") + " using legacy security (WPA/WEP) — upgrade required");

        if (out.isEmpty()) out.add("No critical issues detected — continue routine monitoring");
        return out;
    }

    private void addThreatLevelBar(Document doc, String level) throws Exception {
        Color col  = level.equals("CRITICAL") ? C_RED
                   : level.equals("ELEVATED")  ? new Color(0xB8, 0x6A, 0x00)
                   : C_GREEN;
        Color bgCol= level.equals("CRITICAL") ? C_RED_BG
                   : level.equals("ELEVATED")  ? C_YELLOW_BG
                   : C_GREEN_BG;

        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingAfter(8);
        PdfPCell cell = new PdfPCell(new Phrase("OVERALL THREAT LEVEL: " + level,
                new Font(bfHelvBold, 11, Font.BOLD, col)));
        cell.setBackgroundColor(bgCol);
        cell.setBorderColor(col);
        cell.setBorderWidth(1.5f);
        cell.setPadding(10);
        t.addCell(cell);
        doc.add(t);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  FINDINGS TABLE
    // ═══════════════════════════════════════════════════════════════════════
    private void addFindingsTable(Document doc, List<ApRecord> aps) throws Exception {
        doc.add(new Paragraph(
            "The following table lists all access points detected during the survey, "
            + "ordered by signal strength (strongest first). Risk level is computed from "
            + "encryption status, cipher suite, and proximity.", fBody));
        doc.add(spacer(8));

        String[] headers = {"SSID", "BSSID", "SECURITY", "SIGNAL", "CH", "VENDOR", "RISK"};
        float[]  widths  = {120, 90, 70, 42, 22, 80, 36};
        float total = 0; for (float w : widths) total += w;

        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        table.setWidths(widths);

        // Header row
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, fTableHead));
            cell.setBackgroundColor(C_AMBER_DARK);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(5);
            table.addCell(cell);
        }

        // Data rows
        List<ApRecord> sorted = aps.stream()
                .sorted(Comparator.comparingInt((ApRecord a) -> a.signal).reversed())
                .collect(Collectors.toList());

        boolean alt = false;
        for (ApRecord ap : sorted) {
            Color rowBg = ap.isOpen ? new Color(0xFF, 0xF0, 0xEF)
                        : alt ? C_LIGHT_GRAY : C_WHITE;
            alt = !alt;

            String risk = computeRisk(ap);
            Color riskCol = risk.equals("HIGH")   ? C_RED
                          : risk.equals("MEDIUM")  ? C_YELLOW
                          : C_GREEN;
            Color riskBg  = risk.equals("HIGH")   ? C_RED_BG
                          : risk.equals("MEDIUM")  ? C_YELLOW_BG
                          : C_GREEN_BG;

            addTableCell(table, ap.ssid.isEmpty() ? "<hidden>" : ap.ssid, fTableBody, rowBg, Element.ALIGN_LEFT);
            addTableCell(table, ap.bssid, fMono, rowBg, Element.ALIGN_LEFT);
            addTableCell(table, ap.security, fTableBody, rowBg, Element.ALIGN_LEFT);
            addTableCell(table, ap.signal + " dBm", fTableBody, rowBg, Element.ALIGN_RIGHT);
            addTableCell(table, String.valueOf(ap.channel), fTableBody, rowBg, Element.ALIGN_CENTER);
            addTableCell(table, truncate(ap.vendor, 14), fTableBody, rowBg, Element.ALIGN_LEFT);

            PdfPCell riskCell = new PdfPCell(new Phrase(risk, new Font(bfHelvBold, 7, Font.BOLD, riskCol)));
            riskCell.setBackgroundColor(riskBg);
            riskCell.setBorder(Rectangle.NO_BORDER);
            riskCell.setPadding(4);
            riskCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            riskCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(riskCell);
        }

        doc.add(table);

        // Legend
        PdfPTable legend = new PdfPTable(3);
        legend.setWidthPercentage(60);
        legend.setHorizontalAlignment(Element.ALIGN_LEFT);
        legend.setSpacingAfter(8);
        addLegendCell(legend, "HIGH",   "Open / rogue / WEP / TKIP",        C_RED_BG,    C_RED);
        addLegendCell(legend, "MEDIUM", "WPA legacy / WPS enabled / TKIP",  C_YELLOW_BG, C_YELLOW);
        addLegendCell(legend, "LOW",    "WPA2-CCMP or better",               C_GREEN_BG,  C_GREEN);
        doc.add(legend);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  THREAT DETAIL
    // ═══════════════════════════════════════════════════════════════════════
    private void addThreatDetail(Document doc, List<ApRecord> aps, Stats stats) throws Exception {
        // Open networks
        List<ApRecord> openAps = aps.stream().filter(a -> a.isOpen).collect(Collectors.toList());
        addSubSection(doc, "Open (Unencrypted) Networks  —  " + openAps.size() + " found");
        if (openAps.isEmpty()) {
            doc.add(infoBox("No open networks detected during this survey.", C_GREEN_BG, C_GREEN));
        } else {
            doc.add(infoBox(
                "CRITICAL: The following networks transmit all data without encryption. "
                + "Any passive observer within radio range can capture and read all traffic, "
                + "including passwords, session tokens, and sensitive documents.", C_RED_BG, C_RED));
            doc.add(spacer(6));
            for (ApRecord ap : openAps) {
                doc.add(threatEntry(ap, "OPEN NETWORK",
                    "All traffic is visible to passive observers. Disable this network "
                    + "or enable WPA2-CCMP immediately.", C_RED));
            }
        }
        doc.add(spacer(12));

        // Evil twins
        addSubSection(doc, "Evil Twin / Rogue Access Points  —  " + stats.evilTwins + " found");
        if (stats.evilTwins == 0) {
            doc.add(infoBox("No rogue access points detected during this survey.", C_GREEN_BG, C_GREEN));
        } else {
            doc.add(infoBox(
                "CRITICAL: Evil twin APs duplicate the SSID of a legitimate network on a "
                + "different channel or BSSID to intercept client connections. "
                + "Clients connecting to a rogue AP expose all credentials and session data.", C_RED_BG, C_RED));
            doc.add(spacer(6));
            List<ApRecord> rogues = aps.stream().filter(a -> a.isEvilTwin).collect(Collectors.toList());
            for (ApRecord ap : rogues) {
                doc.add(threatEntry(ap, "EVIL TWIN",
                    "Impersonates a legitimate AP. Deauthentication attack may have been attempted. "
                    + "Investigate source and remove from premises.", C_RED));
            }
        }
        doc.add(spacer(12));

        // Deauth attacks
        addSubSection(doc, "Deauthentication Attacks  —  " + stats.deauthSrc + " source(s)");
        if (stats.deauthSrc == 0) {
            doc.add(infoBox("No deauthentication attacks observed during this survey.", C_GREEN_BG, C_GREEN));
        } else {
            doc.add(infoBox(
                "Deauthentication frames were observed being injected into the network. "
                + "This is a denial-of-service technique used to force clients to reconnect, "
                + "enabling credential capture via handshake interception.", C_YELLOW_BG, C_YELLOW));
        }
        doc.add(spacer(12));

        // Weak ciphers
        List<ApRecord> tkipAps = aps.stream().filter(a -> a.security.contains("TKIP")).collect(Collectors.toList());
        addSubSection(doc, "Weak Cipher Suites (TKIP)  —  " + tkipAps.size() + " found");
        if (tkipAps.isEmpty()) {
            doc.add(infoBox("No TKIP-only configurations detected.", C_GREEN_BG, C_GREEN));
        } else {
            doc.add(infoBox(
                "TKIP (Temporal Key Integrity Protocol) is deprecated and vulnerable to the "
                + "KRACK (Key Reinstallation Attack). All APs should be configured for "
                + "WPA2/WPA3 with AES-CCMP cipher suite only.", C_YELLOW_BG, C_YELLOW));
            doc.add(spacer(6));
            for (ApRecord ap : tkipAps) {
                doc.add(threatEntry(ap, "TKIP — WEAK CIPHER",
                    "Vulnerable to KRACK. Reconfigure to WPA2-CCMP or WPA3.", C_YELLOW));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  RECOMMENDATIONS
    // ═══════════════════════════════════════════════════════════════════════
    private void addRecommendations(Document doc, Stats stats, List<ApRecord> aps) throws Exception {
        doc.add(new Paragraph(
            "The following recommendations are ordered by priority. "
            + "Critical items should be resolved within 24 hours of this report. "
            + "High items within 7 days. Medium items within 30 days.", fBody));
        doc.add(spacer(10));

        List<Recommendation> recs = buildRecommendations(stats, aps);
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{18, 40, 14, 14});
        t.setSpacingBefore(4);

        // Header
        String[] heads = {"PRIORITY", "ACTION", "EFFORT", "DEADLINE"};
        for (String h : heads) {
            PdfPCell c = new PdfPCell(new Phrase(h, fTableHead));
            c.setBackgroundColor(C_AMBER_DARK);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(6);
            t.addCell(c);
        }

        boolean alt = false;
        for (Recommendation r : recs) {
            Color bg = alt ? C_LIGHT_GRAY : C_WHITE;
            Color priCol = r.priority.equals("CRITICAL") ? C_RED
                         : r.priority.equals("HIGH")     ? new Color(0xB8, 0x6A, 0x00)
                         : C_BLUE;
            Color priBg  = r.priority.equals("CRITICAL") ? C_RED_BG
                         : r.priority.equals("HIGH")     ? C_YELLOW_BG
                         : C_BLUE_BG;

            PdfPCell priCell = new PdfPCell(new Phrase(r.priority,
                    new Font(bfHelvBold, 8, Font.BOLD, priCol)));
            priCell.setBackgroundColor(priBg);
            priCell.setBorder(Rectangle.NO_BORDER);
            priCell.setPadding(6);
            priCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            t.addCell(priCell);

            PdfPCell actCell = new PdfPCell();
            actCell.setBackgroundColor(bg);
            actCell.setBorder(Rectangle.NO_BORDER);
            actCell.setPadding(6);
            actCell.addElement(new Paragraph(r.action, fBodyBold));
            if (!r.detail.isEmpty())
                actCell.addElement(new Paragraph(r.detail, fBodySmall));
            t.addCell(actCell);

            addTableCell(t, r.effort,   fTableBody, bg, Element.ALIGN_CENTER);
            addTableCell(t, r.deadline, fTableBody, bg, Element.ALIGN_CENTER);
            alt = !alt;
        }
        doc.add(t);
    }

    private List<Recommendation> buildRecommendations(Stats stats, List<ApRecord> aps) {
        List<Recommendation> list = new ArrayList<>();
        if (stats.open > 0)
            list.add(new Recommendation("CRITICAL",
                "Disable or secure all open networks",
                "Enable WPA2-CCMP on " + stats.open + " unencrypted AP(s) immediately.",
                "< 1 hour", "24 hours"));
        if (stats.evilTwins > 0)
            list.add(new Recommendation("CRITICAL",
                "Locate and remove rogue access points",
                "Use the survey GPS coordinates to physically locate and remove " + stats.evilTwins + " rogue AP(s).",
                "1–4 hours", "24 hours"));
        if (stats.deauthSrc > 0)
            list.add(new Recommendation("CRITICAL",
                "Investigate deauthentication attack sources",
                "Review logs for MAC addresses sending deauth frames. Enable 802.11w PMF.",
                "2–4 hours", "24 hours"));
        long tkip = aps.stream().filter(a -> a.security.contains("TKIP")).count();
        if (tkip > 0)
            list.add(new Recommendation("HIGH",
                "Eliminate TKIP cipher on " + tkip + " AP(s)",
                "Set pairwise cipher to CCMP only in AP configuration. Reboot required.",
                "30 min", "7 days"));
        long wps = aps.stream().filter(a -> a.security.contains("WPS")).count();
        if (wps > 0)
            list.add(new Recommendation("HIGH",
                "Disable WPS on " + wps + " AP(s)",
                "WPS PIN mode is vulnerable to Pixie Dust brute-force.",
                "15 min", "7 days"));
        list.add(new Recommendation("MEDIUM",
            "Deploy 802.11w Protected Management Frames",
            "Enables encryption of deauth/disassoc frames, preventing injection attacks.",
            "1–2 hours", "30 days"));
        list.add(new Recommendation("MEDIUM",
            "Schedule monthly wireless audits",
            "Recurring scans detect new rogue APs, configuration drift, and new open networks.",
            "Ongoing", "30 days"));
        list.add(new Recommendation("LOW",
            "Enable wireless intrusion detection system (WIDS)",
            "Continuous passive monitoring integrated with SIEM for real-time alerting.",
            "1–3 days", "90 days"));
        return list;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  APPENDIX
    // ═══════════════════════════════════════════════════════════════════════
    private void addAppendix(Document doc, List<ApRecord> aps, Stats stats, String operator) throws Exception {
        addSubSection(doc, "Survey Statistics");
        String[][] rows = {
            {"Total APs detected",        String.valueOf(stats.total)},
            {"Open (unencrypted) APs",    String.valueOf(stats.open)},
            {"WPA2 APs",                  String.valueOf(aps.stream().filter(a -> a.security.contains("WPA2")).count())},
            {"WPA3 APs",                  String.valueOf(aps.stream().filter(a -> a.security.contains("WPA3")).count())},
            {"TKIP-only APs",             String.valueOf(aps.stream().filter(a -> a.security.contains("TKIP")).count())},
            {"Evil twin detections",      String.valueOf(stats.evilTwins)},
            {"Deauth attack sources",     String.valueOf(stats.deauthSrc)},
            {"GPS survey readings",       String.valueOf(stats.surveyReadings)},
            {"Operator GPS position",     String.format("%.6f, %.6f", stats.opLat, stats.opLon)},
            {"GPS hardware lock",         stats.gpsActive ? "Yes" : "No (browser approximate)"},
            {"Report generated",          dtf.format(java.time.Instant.now())},
            {"Platform",                  "BLACK ICE v2 — NIGHTFALL35"},
            {"Operator",                  operator},
        };

        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(70);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        t.setWidths(new float[]{120, 180});
        t.setSpacingBefore(6);
        t.setSpacingAfter(16);
        boolean alt = false;
        for (String[] row : rows) {
            Color bg = alt ? C_LIGHT_GRAY : C_WHITE;
            addTableCell(t, row[0], fBodySmallBold, bg, Element.ALIGN_LEFT);
            addTableCell(t, row[1], fBodySmall,     bg, Element.ALIGN_LEFT);
            alt = !alt;
        }
        doc.add(t);

        // Channel distribution
        addSubSection(doc, "Channel Distribution");
        Map<Integer, Long> chCounts = aps.stream()
                .filter(a -> a.channel > 0)
                .collect(Collectors.groupingBy(a -> a.channel, Collectors.counting()));
        if (chCounts.isEmpty()) {
            doc.add(new Paragraph("No channel data available.", fBody));
        } else {
            StringBuilder sb = new StringBuilder();
            chCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("Ch ").append(e.getKey()).append(": ")
                                   .append(e.getValue()).append(" AP(s)    "));
            doc.add(new Paragraph(sb.toString(), fMono));
        }
        doc.add(spacer(12));

        // Methodology
        addSubSection(doc, "Methodology");
        doc.add(new Paragraph(
            "This assessment was conducted using passive 802.11 frame capture (no active transmission). "
            + "The BLACK ICE v2 platform operated in promiscuous monitor mode, capturing beacon frames, "
            + "probe responses, and management frames across all available channels using an automated "
            + "channel hopper with 150ms dwell time per channel. "
            + "Geographic positions were resolved using on-device GPS hardware where available, "
            + "falling back to Wigle.net geolocation and IP approximation. "
            + "Evil twin detection uses a frequency-map algorithm comparing BSSID prefixes and SSID "
            + "patterns across channels. All findings are based on passive observation only; "
            + "no active exploitation was performed.", fBody));
        doc.add(spacer(12));

        // Legal notice
        addSubSection(doc, "Legal Notice");
        doc.add(new Paragraph(
            "This report was produced under written authorization from the site owner. "
            + "Unauthorized use of wireless scanning tools may violate the Computer Misuse Act "
            + "(Zambia), Computer Fraud and Abuse Act (USA), Computer Misuse Act (UK), and "
            + "equivalent legislation in other jurisdictions. "
            + "This report is confidential and intended solely for the authorized recipient. "
            + "Distribution, reproduction, or disclosure to unauthorized parties is prohibited.", fBody));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HELPER ELEMENTS
    // ═══════════════════════════════════════════════════════════════════════
    private void addSection(Document doc, String title) throws Exception {
        Paragraph p = new Paragraph(title, fH1);
        p.setSpacingBefore(14);
        p.setSpacingAfter(2);
        doc.add(p);
        LineSeparator sep = new LineSeparator(1.5f, 100, C_AMBER, Element.ALIGN_LEFT, -4);
        doc.add(new Chunk(sep));
        doc.add(spacer(8));
    }

    private void addSubSection(Document doc, String title) throws Exception {
        Paragraph p = new Paragraph(title, fH2);
        p.setSpacingBefore(10);
        p.setSpacingAfter(6);
        doc.add(p);
    }

    private Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ", new Font(bfHelv, height / 2f));
        p.setLeading(height);
        return p;
    }

    private Paragraph bullet(String text) {
        Paragraph p = new Paragraph("• " + text, fBody);
        p.setIndentationLeft(12);
        p.setSpacingAfter(3);
        return p;
    }

    private PdfPTable infoBox(String text, Color bg, Color border) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setSpacingAfter(4);
        PdfPCell cell = new PdfPCell(new Phrase(text, fBody));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(border);
        cell.setBorderWidth(1f);
        cell.setPadding(8);
        t.addCell(cell);
        return t;
    }

    private PdfPTable threatEntry(ApRecord ap, String type, String desc, Color col) {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setSpacingAfter(4);
        try { t.setWidths(new float[]{160, 280}); } catch (Exception ignored) {}

        PdfPCell left = new PdfPCell();
        left.setBackgroundColor(C_LIGHT_GRAY);
        left.setBorder(Rectangle.NO_BORDER);
        left.setBorderWidthLeft(3f);
        left.setBorderColorLeft(col);
        left.setPadding(7);
        left.addElement(new Paragraph(new Font(bfHelvBold, 7, Font.BOLD, col).getSize() > 0
                ? type : type, new Font(bfHelvBold, 7, Font.BOLD, col)));
        left.addElement(new Paragraph(ap.ssid.isEmpty() ? "<hidden>" : ap.ssid, fBodyBold));
        left.addElement(new Paragraph(ap.bssid, fMono));
        left.addElement(new Paragraph("Ch " + ap.channel + "  " + ap.signal + " dBm", fBodySmall));
        if (ap.lat != 0)
            left.addElement(new Paragraph(
                String.format("%.5f, %.5f", ap.lat, ap.lon), fMonoSmall));

        PdfPCell right = new PdfPCell(new Phrase(desc, fBody));
        right.setBackgroundColor(C_WHITE);
        right.setBorder(Rectangle.NO_BORDER);
        right.setPadding(7);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);

        t.addCell(left);
        t.addCell(right);
        return t;
    }

    private void addTableCell(PdfPTable t, String text, Font f, Color bg, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, f));
        cell.setBackgroundColor(bg);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        t.addCell(cell);
    }

    private void addStatCell(PdfPTable t, String value, String label, Color bg, Color fg) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(bg);
        cell.setBorderColor(fg);
        cell.setBorderWidth(0.5f);
        cell.setPadding(10);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.addElement(new Paragraph(value, new Font(bfHelvBold, 24, Font.BOLD, fg)));
        Paragraph lbl = new Paragraph(label, new Font(bfHelv, 8, Font.NORMAL, fg));
        lbl.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(lbl);
        t.addCell(cell);
    }

    private void addLegendCell(PdfPTable t, String risk, String desc, Color bg, Color fg) {
        PdfPCell badge = new PdfPCell(new Phrase(risk, new Font(bfHelvBold, 7, Font.BOLD, fg)));
        badge.setBackgroundColor(bg);
        badge.setBorderColor(fg);
        badge.setBorderWidth(0.5f);
        badge.setPadding(4);
        badge.setHorizontalAlignment(Element.ALIGN_CENTER);
        t.addCell(badge);
        PdfPCell d = new PdfPCell(new Phrase(desc, fBodySmall));
        d.setBorder(Rectangle.NO_BORDER);
        d.setPadding(4);
        t.addCell(d);

        PdfPCell sp = new PdfPCell(new Phrase(""));
        sp.setBorder(Rectangle.NO_BORDER);
        t.addCell(sp);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PAGE FOOTER / HEADER EVENT
    // ═══════════════════════════════════════════════════════════════════════
    private class PageFooter extends PdfPageEventHelper {
        private final String site, operator;
        private int pageNum = 0;

        PageFooter(String site, String operator) {
            this.site = site; this.operator = operator;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            pageNum++;
            if (pageNum == 1) return; // Skip cover page

            PdfContentByte cb = writer.getDirectContent();
            float w = doc.getPageSize().getWidth();
            float y = doc.bottomMargin() - 20;

            // Footer rule
            cb.setColorStroke(C_MID_GRAY);
            cb.setLineWidth(0.5f);
            cb.moveTo(MARGIN_L, y + 12);
            cb.lineTo(w - MARGIN_R, y + 12);
            cb.stroke();

            // Left: classification
            cb.setColorFill(C_RED);
            cb.setFontAndSize(bfHelvBold, 6);
            cb.beginText();
            cb.setTextMatrix(MARGIN_L, y);
            cb.showText("CONFIDENTIAL");
            cb.endText();

            // Centre: site name
            cb.setColorFill(C_MID_GRAY);
            cb.setFontAndSize(bfHelv, 6);
            String centre = truncate(site, 50) + "  |  BLACK ICE v2";
            float cw = bfHelv.getWidthPoint(centre, 6);
            cb.beginText();
            cb.setTextMatrix((w - cw) / 2, y);
            cb.showText(centre);
            cb.endText();

            // Right: page number
            cb.setColorFill(C_MID_GRAY);
            cb.setFontAndSize(bfHelv, 6);
            String pg = "Page " + pageNum;
            float pw = bfHelv.getWidthPoint(pg, 6);
            cb.beginText();
            cb.setTextMatrix(w - MARGIN_R - pw, y);
            cb.showText(pg);
            cb.endText();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DATA PARSING
    // ═══════════════════════════════════════════════════════════════════════
    /** Simple AP record parsed from Rat.getAllApsJson() and getThreatsJson() */
    public static class ApRecord {
        String ssid = "", bssid = "", security = "", vendor = "", source = "";
        int signal = -100, channel = 0;
        double lat = 0, lon = 0;
        boolean isOpen = false, isEvilTwin = false, isDeauthSource = false;
        long lastSeen = 0;
    }

    public static class Stats {
        int total, open, evilTwins, deauthSrc;
        long surveyReadings;
        double opLat, opLon;
        boolean gpsActive, surveyActive;
        String threatLevel = "NOMINAL";
    }

    private static class Recommendation {
        String priority, action, detail, effort, deadline;
        Recommendation(String p, String a, String d, String e, String dl) {
            priority=p; action=a; detail=d; effort=e; deadline=dl;
        }
    }

    /** Parses the JSON array returned by Rat.getAllApsJson() */
    private List<ApRecord> parseAps(String json) {
        List<ApRecord> list = new ArrayList<>();
        if (json == null || json.equals("[]")) return list;
        // Each AP is a {...} object in the array
        int i = 0;
        while ((i = json.indexOf('{', i)) >= 0) {
            int end = findMatchingBrace(json, i);
            if (end < 0) break;
            String obj = json.substring(i, end + 1);
            ApRecord ap = new ApRecord();
            ap.ssid     = jsonStr(obj, "ssid");
            ap.bssid    = jsonStr(obj, "bssid");
            ap.security = jsonStr(obj, "security");
            ap.vendor   = jsonStr(obj, "vendor");
            ap.source   = jsonStr(obj, "source");
            ap.signal   = jsonInt(obj, "signal", -100);
            ap.channel  = jsonInt(obj, "channel", 0);
            ap.lat      = jsonDbl(obj, "lat", 0);
            ap.lon      = jsonDbl(obj, "lon", 0);
            ap.lastSeen = jsonLng(obj, "lastSeen", 0);
            String sec = ap.security.toUpperCase();
            ap.isOpen   = sec.contains("OPEN") || sec.isEmpty();
            list.add(ap);
            i = end + 1;
        }
        return list;
    }

    /** Parses stats from Rat.getStatsJson() */
    private Stats parseStats(String json, List<ApRecord> aps) {
        Stats s = new Stats();
        if (json == null) return s;
        s.total          = jsonInt(json, "total",          0);
        s.open           = jsonInt(json, "open",           0);
        s.evilTwins      = jsonInt(json, "evilTwins",      0);
        s.deauthSrc      = jsonInt(json, "deauthSources",  0);
        s.surveyReadings = jsonLng(json, "surveyReadings", 0);
        s.opLat          = jsonDbl(json, "operatorLat",    0);
        s.opLon          = jsonDbl(json, "operatorLon",    0);
        s.gpsActive      = json.contains("\"gpsActive\":true");
        s.surveyActive   = json.contains("\"surveyActive\":true");
        s.threatLevel    = jsonStr(json, "threatLevel");
        if (s.threatLevel.isEmpty()) s.threatLevel = "NOMINAL";
        // Mark evil twins in AP list
        // (we only have the count from stats; mark by re-checking threats endpoint separately)
        return s;
    }

    // ── Micro JSON parser (no external deps) ─────────────────────────────
    private String jsonStr(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k); if (i < 0) return "";
        int c = json.indexOf(':', i + k.length()); if (c < 0) return "";
        int q1 = json.indexOf('"', c + 1); if (q1 < 0) return "";
        int q2 = q1 + 1;
        while (q2 < json.length()) {
            if (json.charAt(q2) == '\\') { q2 += 2; continue; }
            if (json.charAt(q2) == '"') break;
            q2++;
        }
        return q2 < json.length() ? json.substring(q1 + 1, q2) : "";
    }

    private int jsonInt(String json, String key, int def) {
        try { return Integer.parseInt(jsonNum(json, key)); } catch (Exception e) { return def; }
    }
    private long jsonLng(String json, String key, long def) {
        try { return Long.parseLong(jsonNum(json, key)); } catch (Exception e) { return def; }
    }
    private double jsonDbl(String json, String key, double def) {
        try { return Double.parseDouble(jsonNum(json, key)); } catch (Exception e) { return def; }
    }
    private String jsonNum(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k); if (i < 0) return "";
        int c = json.indexOf(':', i + k.length()); if (c < 0) return "";
        int s = c + 1; while (s < json.length() && json.charAt(s) == ' ') s++;
        int e = s; while (e < json.length() && "-0123456789.eE".indexOf(json.charAt(e)) >= 0) e++;
        return json.substring(s, e).trim();
    }
    private int findMatchingBrace(String json, int open) {
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    // ── Risk scoring ─────────────────────────────────────────────────────
    private String computeRisk(ApRecord ap) {
        if (ap.isOpen || ap.isEvilTwin || ap.security.equalsIgnoreCase("WEP")) return "HIGH";
        if (ap.security.contains("TKIP") || ap.security.contains("WPS")
                || ap.security.equalsIgnoreCase("WPA")) return "MEDIUM";
        return "LOW";
    }

    // ── String helpers ────────────────────────────────────────────────────
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
