package themythofcrawl.collab;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Json;

/**
 * Pure Java 21 2-way Google Sheets Bridge (via Apps Script WebApp URL).
 * <p>
 * Workflow: work live in Google Sheets inside Burp (with real-time colored cells for
 * {@code Done (A)}, {@code Info (A)}, {@code Missing}, {@code OOS (A)}, {@code Conflict},
 * {@code Doing (A)} + custom tags), then export the Final colored 4-sheet {@code .xlsx}
 * for Microsoft Excel Online / SharePoint.
 */
public final class GoogleSheetsBridge implements AutoCloseable {

    /**
     * Ready-to-paste Google Apps Script (~85 lines) users drop into
     * Extensions -> Apps Script -> Deploy as Web App (Anyone with link).
     */
    public static final String APPS_SCRIPT_TEMPLATE = """
            // theMythOf-JCrawl V_5.0_beta — Google Sheets Real-Time Sync Bridge
            // 1. Open Google Sheet -> Extensions -> Apps Script -> Paste this script
            // 2. Deploy -> New deployment -> Web app -> Execute as: Me -> Who has access: Anyone
            // 3. Copy Web App URL and paste into theMythOf-JCrawl -> Excel -> Google Sheets...

            const SHEET_NAME = 'API_Sync';
            const HEADERS = ['Key', 'Status', 'Method', 'Host', 'Path', 'Branch', 'HTTP', 'Tags', 'Owners', 'Manual Note', 'UpdatedBy'];
            const COLORS = {
              DONE:     { bg: '#DCFCE7', fg: '#166534' },
              INFO:     { bg: '#FEF9C3', fg: '#854D0E' },
              MISSING:  { bg: '#FEE2E2', fg: '#991B1B' },
              OOS:      { bg: '#E2E8F0', fg: '#475569' },
              CONFLICT: { bg: '#FFEDD5', fg: '#9A3412' },
              DOING:    { bg: '#DBEAFE', fg: '#1E40AF' }
            };

            function getSheet_() {
              const ss = SpreadsheetApp.getActiveSpreadsheet();
              let sh = ss.getSheetByName(SHEET_NAME);
              if (!sh) {
                sh = ss.insertSheet(SHEET_NAME);
                sh.appendRow(HEADERS);
                sh.getRange(1, 1, 1, HEADERS.length).setFontWeight('bold').setBackground('#2563EB').setFontColor('#FFFFFF');
                sh.setFrozenRows(1);
              }
              return sh;
            }

            function doGet(e) {
              const sh = getSheet_();
              const data = sh.getDataRange().getValues();
              const rows = [];
              for (let i = 1; i < data.length; i++) {
                const r = data[i];
                if (!r[0] && !r[4]) continue;
                rows.push({
                  key: String(r[0] || ''), status: String(r[1] || ''), method: String(r[2] || ''),
                  host: String(r[3] || ''), path: String(r[4] || ''), branch: String(r[5] || ''),
                  httpStatus: Number(r[6] || 0), tags: String(r[7] || ''), owners: String(r[8] || ''),
                  note: String(r[9] || ''), updatedBy: String(r[10] || '')
                });
              }
              return ContentService.createTextOutput(JSON.stringify({ ok: true, rows: rows }))
                .setMimeType(ContentService.MimeType.JSON);
            }

            function doPost(e) {
              const lock = LockService.getScriptLock();
              lock.waitLock(10000);
              try {
                const payload = JSON.parse(e.postData.contents || '{}');
                const incoming = payload.rows || [];
                const sh = getSheet_();
                const data = sh.getDataRange().getValues();
                const rowIndexByKey = {};
                for (let i = 1; i < data.length; i++) {
                  if (data[i][0]) rowIndexByKey[String(data[i][0])] = i + 1;
                }
                let updated = 0;
                incoming.forEach(function(item) {
                  const k = String(item.key || '');
                  if (!k) return;
                  let rIdx = rowIndexByKey[k];
                  let existingManualNote = '';
                  if (rIdx) {
                    existingManualNote = String(sh.getRange(rIdx, 10).getValue() || '');
                  } else {
                    rIdx = sh.getLastRow() + 1;
                    rowIndexByKey[k] = rIdx;
                  }
                  const finalNote = existingManualNote || String(item.note || '');
                  const rowVals = [[
                    k, item.statusDisplay || '', item.method || '', item.host || '', item.path || '',
                    item.branch || '', item.httpStatus || '', item.tags || '', item.owners || '',
                    finalNote, item.updatedBy || ''
                  ]];
                  sh.getRange(rIdx, 1, 1, HEADERS.length).setValues(rowVals);
                  const tone = COLORS[String(item.statusCode || 'INFO').toUpperCase()] || COLORS.INFO;
                  sh.getRange(rIdx, 2).setBackground(tone.bg).setFontColor(tone.fg).setFontWeight('bold');
                  updated++;
                });
                return ContentService.createTextOutput(JSON.stringify({ ok: true, updated: updated }))
                  .setMimeType(ContentService.MimeType.JSON);
              } finally {
                lock.releaseLock();
              }
            }
            """;

    private final InventoryCatalog catalog;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private volatile String webAppUrl = "";
    private volatile boolean autoSync = false;
    private volatile String lastStatus = "Chưa cấu hình Google Sheets";
    private ScheduledExecutorService timer;

    public GoogleSheetsBridge(InventoryCatalog catalog) {
        this.catalog = catalog;
    }

    public void addListener(Runnable r) {
        if (r != null) listeners.addIfAbsent(r);
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public String webAppUrl() { return webAppUrl; }
    public boolean isAutoSync() { return autoSync; }
    public String lastStatus() { return lastStatus; }

    public synchronized void configure(String url, boolean enableAutoSync, Engine engine) {
        this.webAppUrl = url == null ? "" : url.trim();
        this.autoSync = enableAutoSync && !this.webAppUrl.isEmpty();
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        if (this.autoSync && engine != null) {
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "JCrawl-gsheets-sync");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleWithFixedDelay(() -> {
                if (dirty.compareAndSet(true, false)) {
                    try {
                        pushAll(engine);
                    } catch (Exception ex) {
                        lastStatus = "Lỗi đẩy Google Sheets: " + ex.getMessage();
                        fireChanged();
                    }
                }
            }, 3, 3, TimeUnit.SECONDS);
            lastStatus = "Tự động đồng bộ Google Sheets (3s/lần)";
        } else if (!this.webAppUrl.isEmpty()) {
            lastStatus = "Đã lưu URL Google Sheets (Đồng bộ thủ công)";
        } else {
            lastStatus = "Chưa cấu hình Google Sheets";
        }
        fireChanged();
    }

    public void markDirty() {
        if (autoSync && !webAppUrl.isEmpty()) {
            dirty.set(true);
        }
    }

    /** Pushes all synthesized Inventory rows + colors to the configured Google Sheets WebApp. */
    public int pushAll(Engine engine) throws IOException, InterruptedException {
        if (webAppUrl.isEmpty()) {
            throw new IllegalStateException("Chưa nhập URL Google Sheets Web App.");
        }
        List<InventoryCatalog.InventoryRow> rows = catalog.synthesize(
                engine, InventoryCatalog.ViewContext.TIMELINE, null, null, null);
        List<Map<String, Object>> payloadRows = new ArrayList<>(rows.size());
        for (InventoryCatalog.InventoryRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key());
            m.put("statusCode", r.status().name());
            m.put("statusDisplay", r.statusDisplay());
            m.put("method", r.method());
            m.put("host", r.host());
            m.put("path", r.path());
            m.put("branch", r.branchSummary());
            m.put("httpStatus", r.httpStatus() > 0 ? r.httpStatus() : "");
            m.put("tags", r.tagsDisplay());
            m.put("owners", r.ownersDisplay());
            m.put("note", r.note());
            m.put("updatedBy", catalog.memberName());
            payloadRows.add(m);
        }
        String json = Json.write(Map.of("rows", payloadRows));
        HttpRequest req = HttpRequest.newBuilder(URI.create(webAppUrl))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            lastStatus = "Google Sheets trả HTTP " + resp.statusCode();
            fireChanged();
            throw new IOException(lastStatus);
        }
        lastStatus = "Đã đồng bộ " + payloadRows.size() + " dòng lên Google Sheets";
        fireChanged();
        return payloadRows.size();
    }

    /** Pulls remote updates (statuses, tags, manual notes) from the Google Sheet into {@link InventoryCatalog}. */
    public int pullAll() throws IOException, InterruptedException {
        if (webAppUrl.isEmpty()) {
            throw new IllegalStateException("Chưa nhập URL Google Sheets Web App.");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(webAppUrl))
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            lastStatus = "Lỗi kéo Google Sheets (HTTP " + resp.statusCode() + ")";
            fireChanged();
            throw new IOException(lastStatus);
        }
        Object parsed = Json.parse(resp.body());
        int count = 0;
        if (parsed instanceof Map<?, ?> map && map.get("rows") instanceof List<?> rows) {
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> rm)) continue;
                String method = str(rm.get("method"));
                String host = str(rm.get("host"));
                String path = str(rm.get("path"));
                if (path.isEmpty()) continue;
                String owner = str(rm.get("updatedBy"));
                if (owner.isEmpty()) owner = str(rm.get("owners"));
                if (owner.isEmpty()) owner = "Sheet";
                Set<String> tags = new LinkedHashSet<>();
                for (String t : str(rm.get("tags")).split("[,;]")) {
                    if (!t.isBlank()) tags.add(t.trim());
                }
                int httpSt = 0;
                if (rm.get("httpStatus") instanceof Number n) httpSt = n.intValue();
                catalog.applyRemoteUpdate(
                        owner,
                        method.isEmpty() ? "GET" : method,
                        host,
                        path,
                        InventoryCatalog.ItemStatus.parse(str(rm.get("status"))),
                        httpSt,
                        str(rm.get("branch")),
                        tags,
                        str(rm.get("note")),
                        System.currentTimeMillis());
                count++;
            }
        }
        lastStatus = "Đã kéo " + count + " dòng từ Google Sheets";
        fireChanged();
        return count;
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
