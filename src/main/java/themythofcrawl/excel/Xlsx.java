package themythofcrawl.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Minimal .xlsx reader / writer on the JDK alone (java.util.zip + the JDK's DOM parser), so it works
 * inside Burp's extension class loader with no third-party jar.
 */
public final class Xlsx {
    private static final long MAX_PART = 40L * 1024 * 1024;
    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PKG = "http://schemas.openxmlformats.org/package/2006/relationships";

    private Xlsx() {
    }

    /** One worksheet: name + rows (each row a list of String / Long / Double / Boolean / null). */
    public record Sheet(String name, List<List<Object>> rows) {
    }

    /** Semantic Excel cell styles rendered natively in Microsoft Excel Online, Desktop Excel, and Google Sheets. */
    public enum CellTone {
        DEFAULT(0),
        HEADER(1),
        DONE_GREEN(2),
        INFO_YELLOW(3),
        MISSING_RED(4),
        OOS_GRAY(5),
        CONFLICT_ORANGE(6),
        DOING_BLUE(7),
        DENIED_SLATE(8),
        ERROR_RED(9),
        QA_PURPLE(10),
        BOLD(11),
        WRAP(12),
        TITLE(13),
        PERCENT(14),
        LINK(15),
        HINT(16);

        final int styleIndex;

        CellTone(int styleIndex) {
            this.styleIndex = styleIndex;
        }
    }

    /** Wrapper for a cell value with an explicit Excel fill/font style. */
    public record StyledCell(Object value, CellTone tone) {
        public static StyledCell of(Object value, CellTone tone) {
            return new StyledCell(value, tone == null ? CellTone.DEFAULT : tone);
        }
    }

    /** V_11.4: a formula (without '=') and the value it gives now, so viewers that do not recalculate still show it. */
    public record Formula(String expr, Object cached, CellTone tone) {
        public static Formula of(String expr, Object cached) {
            return new Formula(expr, cached, CellTone.DEFAULT);
        }
    }

    /** V_11.4: a clustered bar chart placed on the sheet (cells A1-style ranges like 'Tổng quan'!$B$3:$D$3). */
    public record Chart(String title, String categories, List<String[]> series, int fromCol, int fromRow, int toCol, int toRow) {
    }

    /**
     * V_11.4 sheet layout: frozen header / auto-filter (normal tables), hidden columns (the row code), explicit row
     * heights, an optional chart.
     */
    public record Layout(boolean header, Set<Integer> hiddenCols, Map<Integer, Integer> rowHeightsPx, Chart chart) {
        public static final Layout TABLE = new Layout(true, Set.of(), Map.of(), null);

        public static Layout table(Set<Integer> hidden) {
            return new Layout(true, hidden, Map.of(), null);
        }
    }

    // ------------------------------------------------------------------ reading
    private static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        // Spreadsheet parts never declare a DTD: refusing them rules out entity-expansion / XXE.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static Element part(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) {
            throw new IOException("Thiếu phần " + name + " - đây có đúng là file .xlsx không?");
        }
        if (e.getSize() > MAX_PART) {
            throw new IOException("Phần " + name + " quá lớn");
        }
        try (InputStream in = z.getInputStream(e)) {
            Document doc = builder().parse(in);
            return doc.getDocumentElement();
        } catch (SAXException | ParserConfigurationException ex) {
            throw new IOException("XML không hợp lệ trong " + name + ": " + ex.getMessage(), ex);
        }
    }

    private static String local(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        collect(root, localName, out);
        return out;
    }

    private static void collect(Node n, String localName, List<Element> out) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                if (localName.equals(local(c))) {
                    out.add((Element) c);
                }
                collect(c, localName, out);
            }
        }
    }

    private static List<Element> children(Element e, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && localName.equals(local(c))) {
                out.add((Element) c);
            }
        }
        return out;
    }

    private static String allText(Element e, String localName) {
        StringBuilder sb = new StringBuilder();
        for (Element t : descendants(e, localName)) {
            sb.append(t.getTextContent());
        }
        return sb.toString();
    }

    public static List<String> sheetNames(Path file) throws IOException {
        try (ZipFile z = new ZipFile(file.toFile())) {
            List<String> names = new ArrayList<>();
            for (Element s : descendants(part(z, "xl/workbook.xml"), "sheet")) {
                names.add(s.getAttribute("name"));
            }
            return names;
        }
    }

    private static int columnIndex(String ref) {
        int n = 0;
        for (char ch : ref.toCharArray()) {
            char u = Character.toUpperCase(ch);
            if (u < 'A' || u > 'Z') {
                break;
            }
            n = n * 26 + (u - 'A' + 1);
        }
        return n - 1;
    }

    public static Sheet read(Path file, int sheetIndex) throws IOException {
        try (ZipFile z = new ZipFile(file.toFile())) {
            List<Element> sheets = descendants(part(z, "xl/workbook.xml"), "sheet");
            if (sheets.isEmpty()) {
                throw new IOException("File không có sheet nào");
            }
            Element sheet = sheets.get(Math.max(0, Math.min(sheetIndex, sheets.size() - 1)));
            String relId = sheet.getAttributeNS(NS_REL, "id");
            if (relId.isEmpty()) {
                relId = sheet.getAttributeNS("http://purl.oclc.org/ooxml/officeDocument/relationships", "id");
            }
            String target = "worksheets/sheet" + (sheetIndex + 1) + ".xml";
            if (!relId.isEmpty() && z.getEntry("xl/_rels/workbook.xml.rels") != null) {
                for (Element rel : descendants(part(z, "xl/_rels/workbook.xml.rels"), "Relationship")) {
                    if (relId.equals(rel.getAttribute("Id"))) {
                        target = rel.getAttribute("Target");
                    }
                }
            }
            String partName = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            List<String> shared = new ArrayList<>();
            if (z.getEntry("xl/sharedStrings.xml") != null) {
                for (Element si : children(part(z, "xl/sharedStrings.xml"), "si")) {
                    shared.add(allText(si, "t"));
                }
            }
            List<List<Object>> rows = new ArrayList<>();
            for (Element row : descendants(part(z, partName), "row")) {
                List<Object> cells = new ArrayList<>();
                int seq = 0;
                for (Element c : children(row, "c")) {
                    String ref = c.getAttribute("r");
                    int col = ref.isEmpty() ? seq : columnIndex(ref);
                    seq = col + 1;
                    String kind = c.getAttribute("t");
                    List<Element> vs = children(c, "v");
                    String v = vs.isEmpty() ? null : vs.get(0).getTextContent();
                    Object value;
                    switch (kind) {
                        case "s" -> {
                            int i = v == null ? -1 : Integer.parseInt(v.trim());
                            value = i >= 0 && i < shared.size() ? shared.get(i) : null;
                        }
                        case "inlineStr" -> value = allText(c, "t");
                        case "b" -> value = "1".equals(v);
                        case "str", "e" -> value = v;
                        default -> value = number(v);
                    }
                    while (cells.size() <= col) {
                        cells.add(null);
                    }
                    cells.set(col, value);
                }
                rows.add(cells);
            }
            return new Sheet(sheet.getAttribute("name"), rows);
        }
    }

    /** V_11.4: every sheet of a workbook (name + rows). */
    public static List<Sheet> readAll(Path file) throws IOException {
        List<Sheet> out = new ArrayList<>();
        int n = sheetNames(file).size();
        for (int i = 0; i < n; i++) {
            out.add(read(file, i));
        }
        return out;
    }

    /** A picture found in a workbook: sheet name, 0-based row / column of its top-left cell, bytes. */
    public record InPicture(String sheet, int row, int col, byte[] data, String ext) {
    }

    /** V_11.4: pictures placed on the sheets (to bring back screenshots a client pasted into a report). */
    public static List<InPicture> readPictures(Path file) throws IOException {
        List<InPicture> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(file.toFile())) {
            List<Element> sheets = descendants(part(z, "xl/workbook.xml"), "sheet");
            Map<String, String> relTarget = new java.util.HashMap<>();
            if (z.getEntry("xl/_rels/workbook.xml.rels") != null) {
                for (Element rel : descendants(part(z, "xl/_rels/workbook.xml.rels"), "Relationship")) {
                    relTarget.put(rel.getAttribute("Id"), rel.getAttribute("Target"));
                }
            }
            for (int i = 0; i < sheets.size(); i++) {
                Element sh = sheets.get(i);
                String relId = sh.getAttributeNS(NS_REL, "id");
                String target = relTarget.getOrDefault(relId, "worksheets/sheet" + (i + 1) + ".xml");
                String sheetPart = resolve("xl/workbook.xml", target);
                String sheetRels = relsOf(sheetPart);
                if (z.getEntry(sheetRels) == null) {
                    continue;
                }
                for (Element rel : descendants(part(z, sheetRels), "Relationship")) {
                    if (!rel.getAttribute("Type").endsWith("/drawing")) {
                        continue;
                    }
                    String drawing = resolve(sheetPart, rel.getAttribute("Target"));
                    if (z.getEntry(drawing) == null) {
                        continue;
                    }
                    Map<String, String> media = new java.util.HashMap<>();
                    String drels = relsOf(drawing);
                    if (z.getEntry(drels) != null) {
                        for (Element r : descendants(part(z, drels), "Relationship")) {
                            media.put(r.getAttribute("Id"), resolve(drawing, r.getAttribute("Target")));
                        }
                    }
                    Element root = part(z, drawing);
                    List<Element> anchors = new ArrayList<>(descendants(root, "oneCellAnchor"));
                    anchors.addAll(descendants(root, "twoCellAnchor"));
                    anchors.addAll(descendants(root, "absoluteAnchor"));
                    for (Element a : anchors) {
                        List<Element> from = children(a, "from");
                        int row = 0;
                        int col = 0;
                        if (!from.isEmpty()) {
                            row = intText(children(from.get(0), "row"));
                            col = intText(children(from.get(0), "col"));
                        }
                        for (Element blip : descendants(a, "blip")) {
                            String emb = blip.getAttributeNS(NS_REL, "embed");
                            String mp = media.get(emb);
                            ZipEntry me = mp == null ? null : z.getEntry(mp);
                            if (me == null || me.getSize() > MAX_PART) {
                                continue;
                            }
                            try (InputStream in = z.getInputStream(me)) {
                                String ext = mp.substring(mp.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                                out.add(new InPicture(sh.getAttribute("name"), row, col, in.readAllBytes(), ext));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static int intText(List<Element> es) {
        try {
            return es.isEmpty() ? 0 : Integer.parseInt(es.get(0).getTextContent().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String relsOf(String part) {
        int s = part.lastIndexOf('/');
        return part.substring(0, s + 1) + "_rels/" + part.substring(s + 1) + ".rels";
    }

    /** Resolves a relationship target against the part that holds it ("../media/a.png" from xl/drawings/x.xml). */
    static String resolve(String fromPart, String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        String base = fromPart.contains("/") ? fromPart.substring(0, fromPart.lastIndexOf('/')) : "";
        for (String seg : base.split("/")) {
            if (!seg.isEmpty()) {
                stack.addLast(seg);
            }
        }
        for (String seg : target.split("/")) {
            if (seg.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else if (!seg.isEmpty() && !seg.equals(".")) {
                stack.addLast(seg);
            }
        }
        return String.join("/", stack);
    }

    private static Object number(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
        } catch (NumberFormatException e) {
            return v;
        }
    }

    // ------------------------------------------------------------------ writing
    /** A sheet to write: header row (bold, blue, frozen, auto-filter) + data rows + column widths. */
    public record OutSheet(String name, List<String> header, List<List<Object>> rows, int[] widths, Layout layout) {
        public OutSheet(String name, List<String> header, List<List<Object>> rows, int[] widths) {
            this(name, header, rows, widths, Layout.TABLE);
        }

        public OutSheet {
            layout = layout == null ? Layout.TABLE : layout;
        }
    }

    /**
     * V_10.4: an image placed at a data cell (row 0 = first data row, under the header). The row grows to fit it.
     * Written as OOXML drawing parts (sheet -> xl/drawings/drawingN.xml -> xl/media/imageK.png|jpeg), one-cell anchor.
     */
    public record Picture(int row, int col, byte[] data, String ext, int widthPx, int heightPx, int offsetYPx) {
        public Picture(int row, int col, byte[] data, String ext, int widthPx, int heightPx) {
            this(row, col, data, ext, widthPx, heightPx, 4);
        }
    }

    private static String esc(Object value) {
        String s = String.valueOf(value);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0xFFFE || c == 0xFFFF) {
                continue;
            }
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String column(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static String sheetXml(OutSheet s) {
        return sheetXml(s, List.of());
    }

    private static String sheetXml(OutSheet s, List<Picture> pics) {
        boolean table = s.layout().header() && !s.header().isEmpty();
        java.util.Map<Integer, Integer> rowPx = new java.util.HashMap<>();
        int off = table ? 1 : 0;
        for (Picture p : pics) {
            rowPx.merge(p.row() + off, p.heightPx() + p.offsetYPx() + 6, Math::max);
        }
        s.layout().rowHeightsPx().forEach((r, px) -> rowPx.merge(r + off, px, Math::max));
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">")
                .append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
                .append("<sheetViews><sheetView workbookViewId=\"0\">");
        if (table) {
            x.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>");
        }
        x.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>");
        int cols = Math.max(s.widths().length, s.layout().hiddenCols().stream().mapToInt(i -> i + 1).max().orElse(0));
        if (cols > 0) {
            x.append("<cols>");
            for (int i = 0; i < cols; i++) {
                boolean hid = s.layout().hiddenCols().contains(i);
                int w = i < s.widths().length ? s.widths()[i] : 12;
                x.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                        .append("\" width=\"").append(w).append("\" customWidth=\"1\"").append(hid ? " hidden=\"1\"" : "").append("/>");
            }
            x.append("</cols>");
        }
        x.append("<sheetData>");
        List<List<Object>> all = new ArrayList<>();
        if (table) {
            all.add(new ArrayList<>(s.header()));
        }
        all.addAll(s.rows());
        for (int r = 0; r < all.size(); r++) {
            Integer px = rowPx.get(r);
            x.append("<row r=\"").append(r + 1).append('"');
            if (px != null) {
                x.append(" ht=\"").append(Math.round(px * 0.75)).append("\" customHeight=\"1\"");
            }
            x.append('>');
            List<Object> row = all.get(r);
            boolean headerRow = table && r == 0;
            for (int c = 0; c < row.size(); c++) {
                Object raw = row.get(c);
                if (raw == null) {
                    continue;
                }
                int styleIdx = headerRow ? CellTone.HEADER.styleIndex : CellTone.DEFAULT.styleIndex;
                Object v = raw;
                if (raw instanceof StyledCell sc) {
                    v = sc.value();
                    if (v == null) {
                        continue;
                    }
                    styleIdx = sc.tone().styleIndex;
                }
                String ref = column(c) + (r + 1);
                if (v instanceof Formula f) {
                    int st = f.tone() == null || f.tone() == CellTone.DEFAULT ? styleIdx : f.tone().styleIndex;
                    String style = st > 0 ? " s=\"" + st + "\"" : "";
                    Object cv = f.cached();
                    boolean num = cv instanceof Integer || cv instanceof Long || cv instanceof Double;
                    x.append("<c r=\"").append(ref).append('"').append(style).append(num ? "" : " t=\"str\"").append("><f>")
                            .append(esc(f.expr())).append("</f>");
                    if (cv != null) {
                        x.append("<v>").append(esc(cv)).append("</v>");
                    }
                    x.append("</c>");
                    continue;
                }
                String style = styleIdx > 0 ? " s=\"" + styleIdx + "\"" : "";
                if (!headerRow && (v instanceof Integer || v instanceof Long || v instanceof Double)) {
                    x.append("<c r=\"").append(ref).append('"').append(style).append("><v>").append(v).append("</v></c>");
                } else {
                    x.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style)
                            .append("><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>");
                }
            }
            x.append("</row>");
        }
        x.append("</sheetData>");
        if (table) {
            x.append("<autoFilter ref=\"A1:").append(column(s.header().size() - 1)).append(s.rows().size() + 1).append("\"/>");
        }
        // print: landscape, all columns on one page width
        x.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
                .append("<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        if (!pics.isEmpty() || s.layout().chart() != null) {
            x.append("<drawing r:id=\"rId1\"/>");
        }
        x.append("</worksheet>");
        return x.toString();
    }

    private static final long EMU_PER_PX = 9525L;

    private static String drawingXml(List<Picture> pics, Chart chart, int headerRows) {
        StringBuilder d = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + NS_REL + "\">");
        for (int i = 0; i < pics.size(); i++) {
            Picture p = pics.get(i);
            long cx = p.widthPx() * EMU_PER_PX;
            long cy = p.heightPx() * EMU_PER_PX;
            d.append("<xdr:oneCellAnchor><xdr:from><xdr:col>").append(p.col()).append("</xdr:col><xdr:colOff>38100</xdr:colOff>")
                    .append("<xdr:row>").append(p.row() + headerRows).append("</xdr:row><xdr:rowOff>").append(p.offsetYPx() * EMU_PER_PX).append("</xdr:rowOff></xdr:from>")
                    .append("<xdr:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                    .append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"").append(i + 2).append("\" name=\"Ảnh ").append(i + 1)
                    .append("\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr>")
                    .append("<xdr:blipFill><a:blip r:embed=\"rId").append(i + 1).append("\"/><a:stretch><a:fillRect/></a:stretch>")
                    .append("</xdr:blipFill><xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx)
                    .append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
                    .append("</xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>");
        }
        if (chart != null) {
            d.append("<xdr:twoCellAnchor><xdr:from><xdr:col>").append(chart.fromCol()).append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>")
                    .append(chart.fromRow()).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>").append(chart.toCol())
                    .append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(chart.toRow())
                    .append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr>")
                    .append("<xdr:cNvPr id=\"").append(pics.size() + 2).append("\" name=\"Biểu đồ\"/><xdr:cNvGraphicFramePr/>")
                    .append("</xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm>")
                    .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">")
                    .append("<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"rId")
                    .append(pics.size() + 1).append("\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>");
        }
        return d.append("</xdr:wsDr>").toString();
    }

    private static final String[] SERIES_COLORS = {"16A34A", "64748B", "DC2626", "F59E0B", "2563EB", "7C3AED"};

    private static String chartXml(Chart ch) {
        StringBuilder c = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + NS_REL + "\">"
                + "<c:roundedCorners val=\"0\"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>")
                .append(esc(ch.title())).append("</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title>")
                .append("<c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/><c:barChart><c:barDir val=\"col\"/>")
                .append("<c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>");
        for (int i = 0; i < ch.series().size(); i++) {
            String[] s = ch.series().get(i);
            c.append("<c:ser><c:idx val=\"").append(i).append("\"/><c:order val=\"").append(i).append("\"/><c:tx><c:strRef><c:f>")
                    .append(esc(s[0])).append("</c:f></c:strRef></c:tx><c:spPr><a:solidFill><a:srgbClr val=\"")
                    .append(SERIES_COLORS[i % SERIES_COLORS.length]).append("\"/></a:solidFill></c:spPr>")
                    .append("<c:invertIfNegative val=\"0\"/><c:cat><c:strRef><c:f>").append(esc(ch.categories()))
                    .append("</c:f></c:strRef></c:cat><c:val><c:numRef><c:f>").append(esc(s[1])).append("</c:f></c:numRef></c:val></c:ser>");
        }
        c.append("<c:gapWidth val=\"80\"/><c:axId val=\"111\"/><c:axId val=\"222\"/></c:barChart>")
                .append("<c:catAx><c:axId val=\"111\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/>")
                .append("<c:axPos val=\"b\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"222\"/><c:crosses val=\"autoZero\"/>")
                .append("<c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/></c:catAx>")
                .append("<c:valAx><c:axId val=\"222\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/>")
                .append("<c:axPos val=\"l\"/><c:majorGridlines/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/>")
                .append("<c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"111\"/><c:crosses val=\"autoZero\"/>")
                .append("<c:crossBetween val=\"between\"/></c:valAx></c:plotArea><c:legend><c:legendPos val=\"b\"/>")
                .append("<c:overlay val=\"0\"/></c:legend><c:plotVisOnly val=\"1\"/></c:chart></c:chartSpace>");
        return c.toString();
    }

    public static byte[] write(List<OutSheet> sheets) throws IOException {
        return write(sheets, List.of());
    }

    /** @param pictures pictures per sheet (same index as {@code sheets}; missing / empty = none) */
    public static byte[] write(List<OutSheet> sheets, List<List<Picture>> pictures) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int media = 0;
        int drawings = 0;
        int charts = 0;
        try (ZipOutputStream z = new ZipOutputStream(buf)) {
            StringBuilder overrides = new StringBuilder()
                    .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/")
                    .append("vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                    .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/")
                    .append("vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder sheetTags = new StringBuilder();
            StringBuilder rels = new StringBuilder();
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < sheets.size(); i++) {
                OutSheet s = sheets.get(i);
                String name = s.name().length() > 31 ? s.name().substring(0, 31) : s.name();
                List<Picture> pics = i < pictures.size() && pictures.get(i) != null ? pictures.get(i) : List.of();
                put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(s, pics));
                Chart chart = s.layout().chart();
                if (!pics.isEmpty() || chart != null) {
                    drawings++;
                    put(z, "xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" "
                            + "standalone=\"yes\"?><Relationships xmlns=\"" + NS_PKG + "\"><Relationship Id=\"rId1\" Type=\""
                            + NS_REL + "/drawing\" Target=\"../drawings/drawing" + drawings + ".xml\"/></Relationships>");
                    boolean table = s.layout().header() && !s.header().isEmpty();
                    put(z, "xl/drawings/drawing" + drawings + ".xml", drawingXml(pics, chart, table ? 1 : 0));
                    StringBuilder drels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<Relationships xmlns=\"" + NS_PKG + "\">");
                    for (int k = 0; k < pics.size(); k++) {
                        Picture p = pics.get(k);
                        media++;
                        String ext = "jpeg".equals(p.ext()) || "jpg".equals(p.ext()) ? "jpeg" : "png";
                        String file = "image" + media + "." + ext;
                        z.putNextEntry(new ZipEntry("xl/media/" + file));
                        z.write(p.data());
                        z.closeEntry();
                        drels.append("<Relationship Id=\"rId").append(k + 1).append("\" Type=\"").append(NS_REL)
                                .append("/image\" Target=\"../media/").append(file).append("\"/>");
                    }
                    if (chart != null) {
                        charts++;
                        put(z, "xl/charts/chart" + charts + ".xml", chartXml(chart));
                        drels.append("<Relationship Id=\"rId").append(pics.size() + 1).append("\" Type=\"").append(NS_REL)
                                .append("/chart\" Target=\"../charts/chart").append(charts).append(".xml\"/>");
                        overrides.append("<Override PartName=\"/xl/charts/chart").append(charts)
                                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>");
                    }
                    put(z, "xl/drawings/_rels/drawing" + drawings + ".xml.rels", drels.append("</Relationships>").toString());
                    overrides.append("<Override PartName=\"/xl/drawings/drawing").append(drawings)
                            .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
                }
                overrides.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                        .append(".xml\" ContentType=\"application/")
                        .append("vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                sheetTags.append("<sheet name=\"").append(esc(name)).append("\" sheetId=\"").append(i + 1)
                        .append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(NS_REL)
                        .append("/worksheet\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
                if (s.layout().header() && !s.header().isEmpty()) {
                    names.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i)
                            .append("\" hidden=\"1\">&apos;").append(esc(name).replace("'", "''")).append("&apos;!$A$1:$")
                            .append(column(s.header().size() - 1)).append('$').append(s.rows().size() + 1)
                            .append("</definedName>");
                }
            }
            rels.append("<Relationship Id=\"rId").append(sheets.size() + 1).append("\" Type=\"").append(NS_REL)
                    .append("/styles\" Target=\"styles.xml\"/>");
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                    + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" + overrides + "</Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\""
                    + NS_PKG + "\"><Relationship Id=\"rId1\" Type=\"" + NS_REL
                    + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\""
                    + NS_MAIN + "\" xmlns:r=\"" + NS_REL + "\"><sheets>" + sheetTags + "</sheets>"
                    + (names.length() == 0 ? "" : "<definedNames>" + names + "</definedNames>")
                    + "<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>");
            put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"" + NS_PKG + "\">" + rels + "</Relationships>");
            put(z, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\""
                    + NS_MAIN + "\">"
                    + "<fonts count=\"15\">"
                    + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF166534\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF854D0E\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF991B1B\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF475569\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF9A3412\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF1E40AF\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF334155\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FFB91C1C\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF6D28D9\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"15\"/><color rgb=\"FF1E3A8A\"/><name val=\"Calibri\"/></font>"
                    + "<font><u/><sz val=\"11\"/><color rgb=\"FF2563EB\"/><name val=\"Calibri\"/></font>"
                    + "<font><i/><sz val=\"10\"/><color rgb=\"FF64748B\"/><name val=\"Calibri\"/></font>"
                    + "</fonts>"
                    + "<fills count=\"12\">"
                    + "<fill><patternFill patternType=\"none\"/></fill>"
                    + "<fill><patternFill patternType=\"gray125\"/></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF2563EB\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCFCE7\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFEF9C3\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFEE2E2\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE2E8F0\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFEDD5\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDBEAFE\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFCBD5E1\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFCA5A5\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEDE9FE\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "</fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"17\">"
                    + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"5\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"5\" fillId=\"6\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"6\" fillId=\"7\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"7\" fillId=\"8\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"8\" fillId=\"9\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"9\" fillId=\"10\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"10\" fillId=\"11\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"11\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">"
                    + "<alignment wrapText=\"1\" vertical=\"top\"/></xf>"
                    + "<xf numFmtId=\"0\" fontId=\"12\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                    + "<xf numFmtId=\"9\" fontId=\"11\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyNumberFormat=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"13\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"14\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\">"
                    + "<alignment wrapText=\"1\" vertical=\"top\"/></xf>"
                    + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                    + "</styleSheet>");
        }
        return buf.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

}
