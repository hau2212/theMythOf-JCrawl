package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.UIManager;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * Specialized, HTML-free, zero-paint-allocation table cell renderer. Renders branch accent stripes,
 * semantic audit pills with Java2D icons, HTTP method badges, color-coded HTTP status codes, dimmed
 * parameter segments in monospace paths, signed deltas (+/-), right-aligned numbers, and left-bold
 * sortable headers with ellipsis tooltips.
 */
public class AuditRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
    public enum Mode { TEXT, COPY, LINK, DUP }

    private enum ColKind { TEXT, AUDIT_PILL, METHOD_PILL, HTTP_STATUS, PATH, NUMBER, DELTA, TIME, COPY, EXCEL_PILL, DUP_PILL }

    private static final int MAX_CFG_COLS = 32;
    private static final String HOVER_ROW_PROP = "tmoc.hoverRow";
    private static final String HOVER_INSTALLED_PROP = "tmoc.hoverInstalled";

    private final AuditRows rows;
    private final Mode mode;
    private final ColKind[] colKinds = new ColKind[MAX_CFG_COLS];
    private final boolean[] boldCols = new boolean[MAX_CFG_COLS];
    private IntFunction<String> tooltip;
    private IntFunction<String> pathUrlFn;
    private int pathUrlCol = -1;

    // Theme lookup cache (O(1) generation + bg/fg check)
    private Theme cachedTheme;
    private int cachedGen = -1;
    private Color cachedBg;
    private Color cachedFg;

    // Reusable per-cell paint state (EDT-only)
    private char[] buf = new char[256];
    private int textLen;
    private ColKind activeKind = ColKind.TEXT;
    private Theme activeTheme;
    private Color cellBg;
    private Color cellFg;
    private Color branchColor;
    private Color dotColor;
    private Color pillBg;
    private Color pillBorder;
    private Color pillFg;
    private Font cellFont;
    private Icon cellIcon;
    private boolean firstViewCol;
    private boolean cellSelected;
    private boolean isOrigRow;
    private int pathTruncFrom = -1;

    public AuditRenderer(AuditRows rows, Integer statusCol, Integer auditCol, Integer methodCol, int... bold) {
        this(rows, statusCol, auditCol, methodCol, Mode.TEXT, bold);
    }

    public AuditRenderer(AuditRows rows, Integer statusCol, Integer auditCol, Integer methodCol, Mode mode, int... bold) {
        this.rows = rows;
        this.mode = mode == null ? Mode.TEXT : mode;
        for (int i = 0; i < MAX_CFG_COLS; i++) {
            colKinds[i] = ColKind.TEXT;
        }
        if (bold != null) {
            for (int b : bold) {
                if (b >= 0 && b < MAX_CFG_COLS) {
                    boldCols[b] = true;
                }
            }
        }
        if (auditCol != null && auditCol >= 0 && auditCol < MAX_CFG_COLS) {
            colKinds[auditCol] = ColKind.AUDIT_PILL;
            boldCols[auditCol] = true;
        }
        if (methodCol != null && methodCol >= 0 && methodCol < MAX_CFG_COLS) {
            colKinds[methodCol] = ColKind.METHOD_PILL;
            boldCols[methodCol] = true;
        }
        if (statusCol != null && statusCol >= 0 && statusCol < MAX_CFG_COLS) {
            colKinds[statusCol] = ColKind.HTTP_STATUS;
        }
        setOpaque(true);
    }

    public AuditRenderer tooltip(IntFunction<String> f) {
        this.tooltip = f;
        return this;
    }

    public AuditRenderer pathCol(int col, IntFunction<String> urlFn) {
        if (col >= 0 && col < MAX_CFG_COLS) {
            colKinds[col] = ColKind.PATH;
        }
        this.pathUrlCol = col;
        this.pathUrlFn = urlFn;
        return this;
    }

    public AuditRenderer pathCols(int... cols) {
        if (cols != null) {
            for (int c : cols) {
                if (c >= 0 && c < MAX_CFG_COLS) {
                    colKinds[c] = ColKind.PATH;
                }
            }
        }
        return this;
    }

    public AuditRenderer numberCols(int... cols) {
        if (cols != null) {
            for (int c : cols) {
                if (c >= 0 && c < MAX_CFG_COLS) {
                    colKinds[c] = ColKind.NUMBER;
                }
            }
        }
        return this;
    }

    public AuditRenderer deltaCol(int col) {
        if (col >= 0 && col < MAX_CFG_COLS) {
            colKinds[col] = ColKind.DELTA;
        }
        return this;
    }

    public AuditRenderer timeCols(int... cols) {
        if (cols != null) {
            for (int c : cols) {
                if (c >= 0 && c < MAX_CFG_COLS) {
                    colKinds[c] = ColKind.TIME;
                }
            }
        }
        return this;
    }

    public AuditRenderer auditCols(int... cols) {
        if (cols != null) {
            for (int c : cols) {
                if (c >= 0 && c < MAX_CFG_COLS) {
                    colKinds[c] = ColKind.AUDIT_PILL;
                    boldCols[c] = true;
                }
            }
        }
        return this;
    }

    private Theme resolveTheme(Color bg, Color fg) {
        int gen = Theme.generation();
        if (cachedTheme != null && cachedGen == gen && Objects.equals(cachedBg, bg) && Objects.equals(cachedFg, fg)) {
            return cachedTheme;
        }
        Theme t = Theme.of(bg, fg);
        cachedTheme = t;
        cachedGen = gen;
        cachedBg = bg;
        cachedFg = fg;
        return t;
    }

    private void ensureBuf(int len) {
        if (len > buf.length) {
            int next = Math.max(len + 32, buf.length * 2);
            buf = new char[next];
        }
    }

    private void loadText(String s) {
        if (s == null || s.isEmpty()) {
            textLen = 0;
            return;
        }
        int n = s.length();
        ensureBuf(n);
        s.getChars(0, n, buf, 0);
        textLen = n;
    }

    private void formatSignedDelta(long d) {
        ensureBuf(24);
        boolean neg = d < 0;
        long v = neg ? -d : d;
        if (d == 0) {
            buf[0] = '0';
            textLen = 1;
            return;
        }
        int pos = buf.length;
        while (v > 0) {
            buf[--pos] = (char) ('0' + (v % 10));
            v /= 10;
        }
        buf[--pos] = neg ? '-' : '+';
        int n = buf.length - pos;
        System.arraycopy(buf, pos, buf, 0, n);
        textLen = n;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row,
                                                   int column) {
        Color tBg = table != null ? table.getBackground() : null;
        Color tFg = table != null ? table.getForeground() : null;
        Theme th = resolveTheme(tBg, tFg);
        this.activeTheme = th;
        this.cellSelected = selected;
        this.firstViewCol = column == 0;
        this.pathTruncFrom = -1;
        this.cellIcon = null;
        this.pillBg = null;
        this.pillBorder = null;
        this.pillFg = null;

        int mr = row;
        int mc = column;
        if (table != null) {
            if (row >= 0 && row < table.getRowCount()) {
                mr = table.convertRowIndexToModel(row);
            }
            if (column >= 0 && column < table.getColumnCount()) {
                mc = table.convertColumnIndexToModel(column);
            }
        }

        Object accent = rows != null && mr >= 0 ? rows.accentAt(mr) : null;
        AuditLevel lvl = rows != null && mr >= 0 ? rows.levelAt(mr) : AuditLevel.NONE;
        if (lvl == null) {
            lvl = AuditLevel.NONE;
        }
        this.isOrigRow = "ORIG".equals(accent);
        this.branchColor = th.branchAccent(accent);
        this.dotColor = isOrigRow ? th.orig.base() : (lvl == AuditLevel.NONE ? th.dotNone : th.audit(lvl).base());

        boolean hovered = false;
        if (table != null && row >= 0) {
            Object hp = table.getClientProperty(HOVER_ROW_PROP);
            hovered = hp instanceof Integer hi && hi == row;
        }
        if (selected) {
            this.cellBg = th.selectionBg;
        } else if (hovered) {
            this.cellBg = th.tinted(lvl) ? th.audit(lvl).fillStrong() : th.hover;
        } else if (isOrigRow) {
            this.cellBg = th.orig.fill();
        } else {
            this.cellBg = th.rowBackground(lvl, Math.max(0, row));
        }

        ColKind kind = switch (mode) {
            case COPY -> ColKind.COPY;
            case LINK -> ColKind.EXCEL_PILL;
            case DUP -> ColKind.DUP_PILL;
            default -> mc >= 0 && mc < MAX_CFG_COLS ? colKinds[mc] : ColKind.TEXT;
        };
        this.activeKind = kind;

        boolean isBold = (mc >= 0 && mc < MAX_CFG_COLS && boldCols[mc]) || isOrigRow;
        String raw = value == null ? "" : String.valueOf(value);

        switch (kind) {
            case COPY -> {
                this.textLen = 0;
                this.cellFont = th.baseFont;
                this.cellFg = selected ? th.selectionFg : th.muted;
                this.cellIcon = Icons.copy(selected ? th.selectionFg : th.muted);
            }
            case AUDIT_PILL -> {
                String clean = Icons.stripLeadingSymbol(raw);
                loadText(clean);
                this.cellFont = th.smallBoldFont;
                if (textLen > 0) {
                    Theme.Token tok = isOrigRow ? th.orig : th.audit(lvl);
                    this.pillBg = selected ? tok.fillStrong() : tok.fill();
                    this.pillBorder = tok.border();
                    this.pillFg = tok.text();
                    this.cellFg = tok.text();
                    Icon sym = Icons.forLeadingSymbol(raw, th, tok.text());
                    if (sym != null) {
                        this.cellIcon = sym;
                    } else if (isOrigRow) {
                        this.cellIcon = Icons.pin(tok.text(), 12);
                    } else if (lvl != AuditLevel.NONE) {
                        this.cellIcon = Icons.forAudit(lvl, tok.text());
                    }
                } else {
                    this.cellFg = selected ? th.selectionFg : th.fg;
                }
            }
            case METHOD_PILL -> {
                loadText(raw);
                this.cellFont = th.smallBoldFont;
                if (textLen > 0) {
                    Theme.Token mt = th.method(raw);
                    this.pillBg = selected ? mt.fillStrong() : mt.fill();
                    this.pillBorder = mt.border();
                    this.pillFg = mt.text();
                    this.cellFg = mt.text();
                } else {
                    this.cellFg = selected ? th.selectionFg : th.fg;
                }
            }
            case EXCEL_PILL -> {
                String clean = Icons.stripLeadingSymbol(raw);
                loadText(clean);
                this.cellFont = th.smallBoldFont;
                if (textLen > 0) {
                    this.pillBg = selected ? th.info.fillStrong() : th.info.fill();
                    this.pillBorder = th.info.border();
                    this.pillFg = th.info.text();
                    this.cellFg = th.info.text();
                    this.cellIcon = Icons.excel(th.info.text(), 12);
                } else {
                    this.cellFg = selected ? th.selectionFg : th.muted;
                }
            }
            case DUP_PILL -> {
                String clean = Icons.stripLeadingSymbol(raw);
                loadText(clean);
                this.cellFont = th.smallBoldFont;
                if (textLen > 0) {
                    this.pillBg = selected ? th.dup.fillStrong() : th.dup.fill();
                    this.pillBorder = th.dup.border();
                    this.pillFg = th.dup.text();
                    this.cellFg = th.dup.text();
                    this.cellIcon = Icons.dup(th.dup.text(), 12);
                } else {
                    this.cellFg = selected ? th.selectionFg : th.muted;
                }
            }
            case HTTP_STATUS -> {
                loadText(raw);
                this.cellFont = th.boldFont;
                int code = value instanceof Number n ? n.intValue() : parseAsciiInt(raw);
                this.cellFg = selected ? th.selectionFg : (code > 0 ? th.statusColor(code) : th.muted);
            }
            case NUMBER -> {
                loadText(raw);
                this.cellFont = isBold ? th.monoBoldFont : th.monoFont;
                this.cellFg = selected ? th.selectionFg : (isOrigRow ? th.orig.text() : th.fg);
            }
            case TIME -> {
                loadText(raw);
                this.cellFont = isBold ? th.monoBoldFont : th.monoFont;
                this.cellFg = selected ? th.selectionFg : th.muted;
            }
            case DELTA -> {
                long delta = value instanceof Number n ? n.longValue() : parseSignedAsciiLong(raw);
                if (value == null || raw.isEmpty() || delta == Long.MIN_VALUE) {
                    textLen = 0;
                    this.cellFont = th.monoFont;
                    this.cellFg = selected ? th.selectionFg : th.muted;
                } else {
                    formatSignedDelta(delta);
                    this.cellFont = delta == 0 ? th.monoFont : th.monoBoldFont;
                    if (selected) {
                        this.cellFg = th.selectionFg;
                    } else if (delta > 0) {
                        this.cellFg = th.success.text();
                    } else if (delta < 0) {
                        this.cellFg = th.danger.text();
                    } else {
                        this.cellFg = th.muted;
                    }
                }
            }
            case PATH -> {
                loadText(Icons.stripInlineSymbols(raw));
                this.cellFont = isBold ? th.monoBoldFont : th.monoFont;
                this.cellFg = selected ? th.selectionFg : (isOrigRow ? th.orig.text() : th.fg);
            }
            default -> {
                Icon sym = raw.isEmpty() ? null : Icons.forLeadingSymbol(raw, th, selected ? th.selectionFg : null);
                if (sym != null) {
                    this.cellIcon = sym;
                    loadText(Icons.stripLeadingSymbol(raw));
                } else {
                    loadText(Icons.stripInlineSymbols(raw));
                }
                this.cellFont = isBold ? th.boldFont : th.baseFont;
                if (selected) {
                    this.cellFg = th.selectionFg;
                } else if (isOrigRow) {
                    this.cellFg = th.orig.text();
                } else {
                    this.cellFg = th.fg;
                }
            }
        }

        if (tooltip != null && mr >= 0) {
            setToolTipText(tooltip.apply(mr));
        } else if (kind == ColKind.PATH && pathUrlFn != null && mc == pathUrlCol && mr >= 0) {
            String u = pathUrlFn.apply(mr);
            setToolTipText(u != null && !u.isEmpty() ? u : null);
        } else if (table != null && column >= 0 && column < table.getColumnCount() && textLen > 0) {
            int colW = table.getColumnModel().getColumn(column).getWidth();
            FontMetrics fm = table.getFontMetrics(cellFont);
            int extra = th.scaled(firstViewCol ? 28 : 14) + (cellIcon != null ? cellIcon.getIconWidth() + th.scaled(6) : 0);
            int tw = fm.charsWidth(buf, 0, textLen);
            setToolTipText(tw + extra > colW ? new String(buf, 0, textLen) : null);
        } else {
            setToolTipText(null);
        }

        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme th = activeTheme != null ? activeTheme : Theme.current();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(cellBg != null ? cellBg : th.surface);
            g2.fillRect(0, 0, w, h);

            int stripeW = Math.max(3, th.scaled(3));
            int padX = th.scaled(6);
            if (firstViewCol && branchColor != null) {
                g2.setColor(branchColor);
                g2.fillRect(0, 0, stripeW, h);
            }

            int x = firstViewCol ? (branchColor != null ? stripeW : 0) + padX : padX;
            if (firstViewCol && (activeKind != ColKind.COPY || w >= th.scaled(34))) {
                int dotD = Math.max(6, th.scaled(7));
                g2.setColor(dotColor != null ? dotColor : th.dotNone);
                g2.fillOval(x, (h - dotD) / 2, dotD, dotD);
                x += dotD + th.scaled(5);
            }

            int maxRight = w - padX;
            int avail = Math.max(0, maxRight - x);

            switch (activeKind) {
                case COPY -> {
                    if (cellIcon != null) {
                        int ix = x + Math.max(0, (maxRight - x - cellIcon.getIconWidth()) / 2);
                        cellIcon.paintIcon(this, g2, ix, (h - cellIcon.getIconHeight()) / 2);
                    }
                }
                case AUDIT_PILL, METHOD_PILL, EXCEL_PILL, DUP_PILL -> paintPill(g2, th, x, h, avail);
                case HTTP_STATUS -> paintCentered(g2, th, x, h, avail);
                case NUMBER, DELTA, TIME -> paintRight(g2, th, x, maxRight, h, avail);
                case PATH -> paintPath(g2, th, x, h, avail);
                default -> paintPlain(g2, th, x, h, avail);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintPill(Graphics2D g2, Theme th, int x, int h, int avail) {
        if (textLen <= 0 || avail < th.scaled(12)) {
            return;
        }
        Font f = cellFont != null ? cellFont : th.smallBoldFont;
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics(f);
        int ip = th.scaled(6);
        int igap = th.scaled(4);
        int icW = cellIcon != null ? cellIcon.getIconWidth() + igap : 0;
        int maxTextW = Math.max(0, avail - ip * 2 - icW);
        int drawLen = fitChars(fm, buf, textLen, maxTextW);
        int drawnW = drawLen > 0 ? fm.charsWidth(buf, 0, drawLen) : 0;
        int pillW = Math.min(avail, ip * 2 + icW + drawnW);
        int pillH = Math.min(h - th.scaled(4), th.scaled(20));
        int pillY = (h - pillH) / 2;
        int arc = activeKind == ColKind.METHOD_PILL ? Math.min(pillH, th.scaled(6)) : pillH;

        g2.setColor(pillBg != null ? pillBg : th.neutral.fill());
        g2.fillRoundRect(x, pillY, pillW - 1, pillH - 1, arc, arc);
        g2.setColor(pillBorder != null ? pillBorder : th.border);
        g2.drawRoundRect(x, pillY, pillW - 1, pillH - 1, arc, arc);

        int cx = x + ip;
        if (cellIcon != null) {
            cellIcon.paintIcon(this, g2, cx, pillY + (pillH - cellIcon.getIconHeight()) / 2);
            cx += cellIcon.getIconWidth() + igap;
        }
        if (drawLen > 0) {
            g2.setColor(pillFg != null ? pillFg : th.fg);
            int ty = pillY + (pillH - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawChars(buf, 0, drawLen, cx, ty);
        }
    }

    private void paintCentered(Graphics2D g2, Theme th, int x, int h, int avail) {
        if (textLen <= 0 || avail <= 4) {
            return;
        }
        Font f = cellFont != null ? cellFont : th.boldFont;
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics(f);
        int drawLen = fitChars(fm, buf, textLen, avail);
        if (drawLen <= 0) {
            return;
        }
        int tw = fm.charsWidth(buf, 0, drawLen);
        int tx = x + Math.max(0, (avail - tw) / 2);
        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(cellFg != null ? cellFg : th.fg);
        g2.drawChars(buf, 0, drawLen, tx, ty);
    }

    private void paintRight(Graphics2D g2, Theme th, int x, int maxRight, int h, int avail) {
        if (textLen <= 0 || avail <= 4) {
            return;
        }
        Font f = cellFont != null ? cellFont : th.monoFont;
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics(f);
        int drawLen = fitChars(fm, buf, textLen, avail);
        if (drawLen <= 0) {
            return;
        }
        int tw = fm.charsWidth(buf, 0, drawLen);
        int tx = Math.max(x, maxRight - tw);
        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(cellFg != null ? cellFg : th.fg);
        g2.drawChars(buf, 0, drawLen, tx, ty);
    }

    private void paintPath(Graphics2D g2, Theme th, int x, int h, int avail) {
        if (textLen <= 0 || avail <= 4) {
            return;
        }
        Font f = cellFont != null ? cellFont : th.monoFont;
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics(f);
        int drawLen = fitChars(fm, buf, textLen, avail);
        if (drawLen <= 0) {
            return;
        }
        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
        Color baseCol = cellFg != null ? cellFg : th.fg;
        Color mutedCol = cellSelected ? th.selectionFg : th.muted;
        drawPathBuffer(g2, fm, buf, drawLen, pathTruncFrom, x, ty, baseCol, mutedCol);
    }

    private void paintPlain(Graphics2D g2, Theme th, int x, int h, int avail) {
        int cx = x;
        int rem = avail;
        if (cellIcon != null) {
            cellIcon.paintIcon(this, g2, cx, (h - cellIcon.getIconHeight()) / 2);
            int advance = cellIcon.getIconWidth() + th.scaled(5);
            cx += advance;
            rem = Math.max(0, rem - advance);
        }
        if (textLen <= 0 || rem <= 4) {
            return;
        }
        Font f = cellFont != null ? cellFont : th.baseFont;
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics(f);
        int drawLen = fitChars(fm, buf, textLen, rem);
        if (drawLen <= 0) {
            return;
        }
        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(cellFg != null ? cellFg : th.fg);
        g2.drawChars(buf, 0, drawLen, cx, ty);
    }

    /** Truncates {@code buffer[0..len)} in-place with {@code "..."} if wider than {@code maxW}. */
    private int fitChars(FontMetrics fm, char[] buffer, int len, int maxW) {
        pathTruncFrom = -1;
        if (len <= 0 || maxW <= 0) {
            return 0;
        }
        if (fm.charsWidth(buffer, 0, len) <= maxW) {
            return len;
        }
        int ellW = fm.stringWidth("...");
        if (maxW <= ellW) {
            return 0;
        }
        int lo = 0;
        int hi = len;
        int limit = maxW - ellW;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (fm.charsWidth(buffer, 0, mid) <= limit) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        ensureBuf(lo + 3);
        buf[lo] = '.';
        buf[lo + 1] = '.';
        buf[lo + 2] = '.';
        pathTruncFrom = lo;
        return lo + 3;
    }

    /** Draws a path/endpoint buffer with query string, {@code {id}}, {@code :param}, and numeric IDs dimmed. */
    static void drawPathBuffer(Graphics2D g2, FontMetrics fm, char[] buf, int len, int truncAt, int startX, int baselineY,
                               Color baseCol, Color mutedCol) {
        int endMain = truncAt >= 0 ? Math.min(len, truncAt) : len;
        int cx = startX;
        int i = 0;
        while (i < endMain) {
            char c = buf[i];
            if (c == '?') {
                int qLen = endMain - i;
                g2.setColor(mutedCol);
                g2.drawChars(buf, i, qLen, cx, baselineY);
                cx += fm.charsWidth(buf, i, qLen);
                i = endMain;
                break;
            }
            if (c == '/') {
                g2.setColor(baseCol);
                g2.drawChars(buf, i, 1, cx, baselineY);
                cx += fm.charWidth('/');
                i++;
                continue;
            }
            if (c == ' ') {
                g2.setColor(baseCol);
                g2.drawChars(buf, i, 1, cx, baselineY);
                cx += fm.charWidth(' ');
                i++;
                continue;
            }
            int j = i + 1;
            while (j < endMain && buf[j] != '/' && buf[j] != '?' && buf[j] != ' ') {
                j++;
            }
            int segLen = j - i;
            boolean dim = isParamSegment(buf, i, segLen);
            g2.setColor(dim ? mutedCol : baseCol);
            g2.drawChars(buf, i, segLen, cx, baselineY);
            cx += fm.charsWidth(buf, i, segLen);
            i = j;
        }
        if (truncAt >= 0 && truncAt < len) {
            int rem = len - truncAt;
            g2.setColor(mutedCol);
            g2.drawChars(buf, truncAt, rem, cx, baselineY);
        }
    }

    static boolean isParamSegment(char[] buf, int start, int len) {
        if (len <= 0) {
            return false;
        }
        char first = buf[start];
        char last = buf[start + len - 1];
        if ((first == '{' && last == '}') || (first == '[' && last == ']') || (first == '<' && last == '>')
                || (first == '(' && last == ')') || first == ':' || (first == '*' && len == 1)) {
            return true;
        }
        for (int k = 0; k < len; k++) {
            char ch = buf[start + k];
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static int parseAsciiInt(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        int val = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            val = val * 10 + (ch - '0');
        }
        return val;
    }

    private static long parseSignedAsciiLong(String s) {
        if (s == null || s.isEmpty()) {
            return Long.MIN_VALUE;
        }
        int i = 0;
        boolean neg = false;
        char first = s.charAt(0);
        if (first == '-') {
            neg = true;
            i = 1;
        } else if (first == '+') {
            i = 1;
        }
        if (i >= s.length()) {
            return Long.MIN_VALUE;
        }
        long val = 0;
        for (; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return Long.MIN_VALUE;
            }
            val = val * 10 + (ch - '0');
        }
        return neg ? -val : val;
    }

    /** Tracks mouse hover row via {@code "tmoc.hoverRow"} and repaints only changed rows. */
    public static void installHover(JTable table) {
        if (table == null || Boolean.TRUE.equals(table.getClientProperty(HOVER_INSTALLED_PROP))) {
            return;
        }
        table.putClientProperty(HOVER_INSTALLED_PROP, Boolean.TRUE);
        MouseAdapter ma = new MouseAdapter() {
            private void updateHover(int next) {
                Object curObj = table.getClientProperty(HOVER_ROW_PROP);
                int prev = curObj instanceof Integer p ? p : -1;
                if (prev == next) {
                    return;
                }
                table.putClientProperty(HOVER_ROW_PROP, next);
                repaintRow(table, prev);
                repaintRow(table, next);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(table.rowAtPoint(e.getPoint()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                updateHover(-1);
            }
        };
        table.addMouseMotionListener(ma);
        table.addMouseListener(ma);
    }

    private static void repaintRow(JTable table, int viewRow) {
        if (viewRow >= 0 && viewRow < table.getRowCount() && table.getColumnCount() > 0) {
            Rectangle r = table.getCellRect(viewRow, 0, true);
            r.x = 0;
            r.width = table.getWidth();
            table.repaint(r);
        }
    }

    /** Left-aligned bold table header with subtle divider lines, sort arrow, and ellipsis tooltip when narrow. */
    public static final class HeaderRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private char[] hBuf = new char[128];
        private int hLen;
        private Theme theme;
        private Theme cachedTheme;
        private int cachedGen = -1;
        private Color cachedBg;
        private Color cachedFg;
        private SortOrder sortOrder = SortOrder.UNSORTED;
        private Color bg;
        private Color fg;

        private Theme resolveHeaderTheme(Color b, Color f) {
            int gen = Theme.generation();
            if (cachedTheme != null && cachedGen == gen && Objects.equals(cachedBg, b) && Objects.equals(cachedFg, f)) {
                return cachedTheme;
            }
            Theme t = Theme.of(b, f);
            cachedTheme = t;
            cachedGen = gen;
            cachedBg = b;
            cachedFg = f;
            return t;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JTableHeader hdr = table != null ? table.getTableHeader() : null;
            Color hbg = hdr != null ? hdr.getBackground() : UIManager.getColor("TableHeader.background");
            Color hfg = hdr != null ? hdr.getForeground() : UIManager.getColor("TableHeader.foreground");
            Theme th = resolveHeaderTheme(hbg != null ? hbg : (table != null ? table.getBackground() : null),
                    hfg != null ? hfg : (table != null ? table.getForeground() : null));
            this.theme = th;
            this.bg = th.surfaceAlt;
            this.fg = th.fg;

            String title = value == null ? "" : String.valueOf(value);
            if (title.length() > hBuf.length) {
                hBuf = new char[Math.max(title.length() + 16, hBuf.length * 2)];
            }
            hLen = title.length();
            if (hLen > 0) {
                title.getChars(0, hLen, hBuf, 0);
            }

            this.sortOrder = SortOrder.UNSORTED;
            if (table != null && table.getRowSorter() != null && column >= 0 && column < table.getColumnCount()) {
                int mc = table.convertColumnIndexToModel(column);
                List<? extends RowSorter.SortKey> keys = table.getRowSorter().getSortKeys();
                for (RowSorter.SortKey k : keys) {
                    if (k.getColumn() == mc && k.getSortOrder() != SortOrder.UNSORTED) {
                        this.sortOrder = k.getSortOrder();
                        break;
                    }
                }
            }

            if (table != null && column >= 0 && column < table.getColumnCount() && hLen > 0) {
                int colW = table.getColumnModel().getColumn(column).getWidth();
                int sortReserve = sortOrder != SortOrder.UNSORTED ? th.scaled(14) : 0;
                int avail = Math.max(0, colW - th.scaled(12) - sortReserve);
                int tw = getFontMetrics(th.boldFont).charsWidth(hBuf, 0, hLen);
                setToolTipText(tw > avail ? title : null);
            } else {
                setToolTipText(null);
            }
            return this;
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            Theme th = theme != null ? theme : Theme.current();
            FontMetrics fm = getFontMetrics(th.boldFont);
            int tw = hLen > 0 ? fm.charsWidth(hBuf, 0, hLen) : 0;
            return new java.awt.Dimension(Math.max(24, tw + th.scaled(16)), Math.max(Theme.ROW_TABLE, th.scaled(26)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = theme != null ? theme : Theme.current();
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                g2.setColor(bg != null ? bg : th.surfaceAlt);
                g2.fillRect(0, 0, w, h);

                g2.setColor(th.border);
                g2.drawLine(0, h - 1, w - 1, h - 1);
                g2.setColor(th.grid);
                g2.drawLine(w - 1, th.scaled(4), w - 1, h - th.scaled(5));

                int padL = th.scaled(6);
                int padR = th.scaled(6);
                int arrowW = sortOrder != SortOrder.UNSORTED ? th.scaled(8) : 0;
                int arrowGap = sortOrder != SortOrder.UNSORTED ? th.scaled(5) : 0;
                int avail = Math.max(0, w - padL - padR - arrowW - arrowGap);

                int drawLen = hLen;
                g2.setFont(th.boldFont);
                FontMetrics fm = g2.getFontMetrics(th.boldFont);
                if (drawLen > 0 && fm.charsWidth(hBuf, 0, drawLen) > avail) {
                    int ellW = fm.stringWidth("...");
                    if (avail <= ellW) {
                        drawLen = 0;
                    } else {
                        int lo = 0;
                        int hi = drawLen;
                        int limit = avail - ellW;
                        while (lo < hi) {
                            int mid = (lo + hi + 1) >>> 1;
                            if (fm.charsWidth(hBuf, 0, mid) <= limit) {
                                lo = mid;
                            } else {
                                hi = mid - 1;
                            }
                        }
                        if (lo + 3 > hBuf.length) {
                            hBuf = java.util.Arrays.copyOf(hBuf, lo + 8);
                        }
                        hBuf[lo] = '.';
                        hBuf[lo + 1] = '.';
                        hBuf[lo + 2] = '.';
                        drawLen = lo + 3;
                    }
                }
                if (drawLen > 0) {
                    g2.setColor(fg != null ? fg : th.fg);
                    int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawChars(hBuf, 0, drawLen, padL, ty);
                }
                if (sortOrder != SortOrder.UNSORTED && w > padL + arrowW) {
                    int ax = w - padR - arrowW;
                    int ay = h / 2;
                    int half = Math.max(3, arrowW / 2);
                    g2.setColor(th.accent.text());
                    if (sortOrder == SortOrder.ASCENDING) {
                        int[] xs = {ax, ax + half, ax + arrowW};
                        int[] ys = {ay + 2, ay - 2, ay + 2};
                        g2.fillPolygon(xs, ys, 3);
                    } else {
                        int[] xs = {ax, ax + half, ax + arrowW};
                        int[] ys = {ay - 2, ay + 2, ay - 2};
                        g2.fillPolygon(xs, ys, 3);
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
