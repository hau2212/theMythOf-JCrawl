package themythofcrawl.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        DOING_BLUE(7);

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
    public record OutSheet(String name, List<String> header, List<List<Object>> rows, int[] widths) {
    }

    /**
     * V_10.4: an image placed at a data cell (row 0 = first data row, under the header). The row grows to fit it.
     * Written as OOXML drawing parts (sheet -> xl/drawings/drawingN.xml -> xl/media/imageK.png|jpeg), one-cell anchor.
     */
    public record Picture(int row, int col, byte[] data, String ext, int widthPx, int heightPx) {
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
        java.util.Map<Integer, Integer> rowPx = new java.util.HashMap<>();
        for (Picture p : pics) {
            rowPx.merge(p.row() + 1, p.heightPx() + 8, Math::max);
        }
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" ")
                .append("activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int i = 0; i < s.widths().length; i++) {
            x.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(s.widths()[i]).append("\" customWidth=\"1\"/>");
        }
        x.append("</cols><sheetData>");
        List<List<Object>> all = new ArrayList<>();
        all.add(new ArrayList<>(s.header()));
        all.addAll(s.rows());
        for (int r = 0; r < all.size(); r++) {
            Integer px = rowPx.get(r);
            x.append("<row r=\"").append(r + 1).append('"');
            if (px != null) {
                x.append(" ht=\"").append(Math.round(px * 0.75)).append("\" customHeight=\"1\"");
            }
            x.append('>');
            List<Object> row = all.get(r);
            for (int c = 0; c < row.size(); c++) {
                Object raw = row.get(c);
                if (raw == null) {
                    continue;
                }
                int styleIdx = r == 0 ? CellTone.HEADER.styleIndex : CellTone.DEFAULT.styleIndex;
                Object v = raw;
                if (raw instanceof StyledCell sc) {
                    v = sc.value();
                    if (v == null) {
                        continue;
                    }
                    styleIdx = sc.tone().styleIndex;
                }
                String ref = column(c) + (r + 1);
                String style = styleIdx > 0 ? " s=\"" + styleIdx + "\"" : "";
                if (r > 0 && (v instanceof Integer || v instanceof Long || v instanceof Double)) {
                    x.append("<c r=\"").append(ref).append('"').append(style).append("><v>").append(v).append("</v></c>");
                } else {
                    x.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style)
                            .append("><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>");
                }
            }
            x.append("</row>");
        }
        x.append("</sheetData><autoFilter ref=\"A1:").append(column(s.header().size() - 1))
                .append(s.rows().size() + 1).append("\"/>");
        if (!pics.isEmpty()) {
            x.append("<drawing r:id=\"rId1\"/>");
        }
        x.append("</worksheet>");
        return x.toString();
    }

    private static final long EMU_PER_PX = 9525L;

    private static String drawingXml(List<Picture> pics) {
        StringBuilder d = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + NS_REL + "\">");
        for (int i = 0; i < pics.size(); i++) {
            Picture p = pics.get(i);
            long cx = p.widthPx() * EMU_PER_PX;
            long cy = p.heightPx() * EMU_PER_PX;
            d.append("<xdr:oneCellAnchor><xdr:from><xdr:col>").append(p.col()).append("</xdr:col><xdr:colOff>38100</xdr:colOff>")
                    .append("<xdr:row>").append(p.row() + 1).append("</xdr:row><xdr:rowOff>38100</xdr:rowOff></xdr:from>")
                    .append("<xdr:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                    .append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"").append(i + 2).append("\" name=\"Ảnh ").append(i + 1)
                    .append("\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr>")
                    .append("<xdr:blipFill><a:blip r:embed=\"rId").append(i + 1).append("\"/><a:stretch><a:fillRect/></a:stretch>")
                    .append("</xdr:blipFill><xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx)
                    .append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
                    .append("</xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>");
        }
        return d.append("</xdr:wsDr>").toString();
    }

    public static byte[] write(List<OutSheet> sheets) throws IOException {
        return write(sheets, List.of());
    }

    /** @param pictures pictures per sheet (same index as {@code sheets}; missing / empty = none) */
    public static byte[] write(List<OutSheet> sheets, List<List<Picture>> pictures) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int media = 0;
        int drawings = 0;
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
                if (!pics.isEmpty()) {
                    drawings++;
                    put(z, "xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" "
                            + "standalone=\"yes\"?><Relationships xmlns=\"" + NS_PKG + "\"><Relationship Id=\"rId1\" Type=\""
                            + NS_REL + "/drawing\" Target=\"../drawings/drawing" + drawings + ".xml\"/></Relationships>");
                    put(z, "xl/drawings/drawing" + drawings + ".xml", drawingXml(pics));
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
                names.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i)
                        .append("\" hidden=\"1\">&apos;").append(esc(name)).append("&apos;!$A$1:$")
                        .append(column(s.header().size() - 1)).append('$').append(s.rows().size() + 1)
                        .append("</definedName>");
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
                    + NS_MAIN + "\" xmlns:r=\"" + NS_REL + "\"><sheets>" + sheetTags + "</sheets><definedNames>"
                    + names + "</definedNames></workbook>");
            put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"" + NS_PKG + "\">" + rels + "</Relationships>");
            put(z, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\""
                    + NS_MAIN + "\">"
                    + "<fonts count=\"8\">"
                    + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF166534\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF854D0E\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF991B1B\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF475569\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF9A3412\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FF1E40AF\"/><name val=\"Calibri\"/></font>"
                    + "</fonts>"
                    + "<fills count=\"9\">"
                    + "<fill><patternFill patternType=\"none\"/></fill>"
                    + "<fill><patternFill patternType=\"gray125\"/></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF2563EB\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCFCE7\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFEF9C3\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFEE2E2\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE2E8F0\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFEDD5\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDBEAFE\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "</fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"8\">"
                    + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"5\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"5\" fillId=\"6\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"6\" fillId=\"7\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"7\" fillId=\"8\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
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
