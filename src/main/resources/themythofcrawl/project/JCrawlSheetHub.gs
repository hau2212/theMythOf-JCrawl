// theMythOf-JCrawl V_11.0 - Google Sheet "Làm chung" (team sync hub)
// Cài đặt (PIC, 1 lần):
//   1. Tạo Google Sheet mới -> Tiện ích mở rộng (Extensions) -> Apps Script -> xóa mã mẫu, dán toàn bộ mã này -> Lưu.
//   2. Triển khai (Deploy) -> Triển khai mới (New deployment) -> loại: Ứng dụng web (Web app)
//      Thực thi với tư cách (Execute as): Tôi (Me) · Ai có quyền truy cập (Who has access): Bất kỳ ai (Anyone)
//   3. Cho phép quyền (Google hỏi 1 lần) -> copy URL ứng dụng web -> dán vào JCrawl > Làm chung.
// Dữ liệu gốc nằm ở sheet ẩn "_data" (mỗi dòng 1 bản ghi). Các sheet Tổng quan / Tổng hợp / A / B / Q&A... do JCrawl vẽ lại.
// Sửa tay trên cột tài khoản, Ghi chú, "Khách hàng trả lời" được ghi lại (onEdit) và JCrawl kéo về.

var HUB_VERSION = 2;
var DATA = '_data';
var META = '_meta';
var COLS = ['id', 'type', 'owner', 'account', 'at', 'by', 'deleted', 'base', 'data'];
var CHUNK = 45000;
var TONES = {
  H: ['#2563EB', '#FFFFFF'], DONE: ['#DCFCE7', '#166534'], DENIED: ['#CBD5E1', '#334155'], ERROR: ['#FCA5A5', '#B91C1C'],
  MISSING: ['#FEE2E2', '#991B1B'], DOING: ['#DBEAFE', '#1E40AF'], OOS: ['#E2E8F0', '#475569'], CONFLICT: ['#FFEDD5', '#9A3412'],
  INFO: ['#FEF9C3', '#854D0E'], QA: ['#EDE9FE', '#6D28D9'], B: ['#FFFFFF', '#111827'], T: ['#FFFFFF', '#1E3A8A'], HINT: ['#FFFFFF', '#64748B']
};

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function props_() {
  return PropertiesService.getScriptProperties();
}

function sheet_(name, hidden) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    if (name === DATA) {
      sh.getRange(1, 1, 1, COLS.length).setValues([COLS]);
      sh.setFrozenRows(1);
    }
    if (hidden) {
      sh.hideSheet();
    }
  }
  return sh;
}

function now_() {
  var p = props_();
  var last = Number(p.getProperty('LAST_AT') || 0);
  var t = Math.max(Date.now(), last + 1);
  p.setProperty('LAST_AT', String(t));
  return t;
}

function check_(req) {
  var p = props_();
  var pass = p.getProperty('PASS');
  if (!pass) {
    return 'Chưa khởi tạo: gọi hello trước';
  }
  if (String(req.pass || '') !== pass) {
    return 'Sai mã dự án';
  }
  return '';
}

function doGet(e) {
  return json_({ok: true, hub: HUB_VERSION, project: props_().getProperty('PROJECT') || '', serverTime: Date.now()});
}

function doPost(e) {
  var req;
  try {
    req = JSON.parse((e && e.postData && e.postData.contents) || '{}');
  } catch (err) {
    return json_({ok: false, error: 'JSON hỏng'});
  }
  if (req.action === 'hello') {
    return json_(hello_(req));
  }
  var bad = check_(req);
  if (bad) {
    return json_({ok: false, error: bad});
  }
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(20000);
  } catch (err) {
    return json_({ok: false, error: 'Bận, thử lại', retry: true});
  }
  try {
    switch (req.action) {
      case 'push': return json_(push_(req));
      case 'pull': return json_(pull_(req));
      case 'render': return json_(render_(req));
      case 'image': return json_(image_(req));
      case 'imageget': return json_(imageGet_(req));
      case 'compact': return json_(compact_());
      default: return json_({ok: false, error: 'Không rõ thao tác: ' + req.action});
    }
  } catch (err) {
    return json_({ok: false, error: String(err && err.message || err)});
  } finally {
    lock.releaseLock();
  }
}

function hello_(req) {
  var p = props_();
  var pass = p.getProperty('PASS');
  if (!pass) {
    if (!req.pass) {
      return {ok: false, error: 'Thiếu mã dự án'};
    }
    p.setProperty('PASS', String(req.pass));
    p.setProperty('PROJECT', String(req.project || ''));
    p.setProperty('NAME', String(req.name || ''));
    sheet_(DATA, true);
    sheet_(META, true);
    return {ok: true, created: true, hub: HUB_VERSION, project: req.project || '', serverTime: Date.now()};
  }
  if (String(req.pass || '') !== pass) {
    return {ok: false, error: 'Sai mã dự án'};
  }
  return {ok: true, hub: HUB_VERSION, project: p.getProperty('PROJECT') || '', name: p.getProperty('NAME') || '', serverTime: Date.now()};
}

// ---------------------------------------------------------------- records
function readAll_() {
  var sh = sheet_(DATA, true);
  var last = sh.getLastRow();
  var out = [];
  if (last < 2) {
    return {sheet: sh, rows: out};
  }
  var width = Math.max(COLS.length, sh.getLastColumn());
  var vals = sh.getRange(2, 1, last - 1, width).getValues();
  for (var i = 0; i < vals.length; i++) {
    var v = vals[i];
    if (!v[0]) {
      continue;
    }
    var data = '';
    for (var k = 8; k < v.length; k++) {
      data += v[k] === null || v[k] === undefined ? '' : String(v[k]);
    }
    out.push({row: i + 2, id: String(v[0]), type: String(v[1]), owner: String(v[2]), account: String(v[3]), at: Number(v[4]) || 0,
      by: String(v[5]), deleted: v[6] === true || String(v[6]) === 'true' || String(v[6]) === '1', base: Number(v[7]) || 0, data: data});
  }
  return {sheet: sh, rows: out};
}

function latest_(rows) {
  var by = {};
  for (var i = 0; i < rows.length; i++) {
    var r = rows[i];
    var cur = by[r.id];
    if (!cur || r.at > cur.at || (r.at === cur.at && r.row > cur.row)) {
      by[r.id] = r;
    }
  }
  return by;
}

function cells_(r) {
  var d = typeof r.data === 'string' ? r.data : JSON.stringify(r.data || {});
  var row = [r.id, r.type || '', r.owner || '', r.account || '', r.at, r.by || '', r.deleted ? true : false, r.base || 0];
  if (d.length === 0) {
    row.push('');
  }
  for (var i = 0; i < d.length; i += CHUNK) {
    row.push(d.substring(i, i + CHUNK));
  }
  return row;
}

function sameData_(a, b) {
  return String(a) === String(b);
}

var MANUAL_TYPES = {mark: 1, note: 1, qans: 1, project: 1};

function push_(req) {
  var all = readAll_();
  var sh = all.sheet;
  var cur = latest_(all.rows);
  var append = [];
  var stamped = [];
  var conflicts = 0;
  var recs = req.records || [];
  for (var i = 0; i < recs.length; i++) {
    var r = recs[i];
    if (!r || !r.id) {
      continue;
    }
    var data = typeof r.data === 'string' ? r.data : JSON.stringify(r.data || {});
    var ex = cur[r.id];
    var type = r.type || String(r.id).split(':')[0];
    if (ex && MANUAL_TYPES[type] && (!r.base || ex.at > r.base) && ex.by !== (r.by || '') && !sameData_(ex.data, data)) {
      var cat = now_();
      var cid = 'conflict:' + r.id + ':' + cat;
      append.push(cells_({id: cid, type: 'conflict', owner: r.owner || '', account: r.account || '', at: cat, by: r.by || '',
        deleted: false, base: 0, data: JSON.stringify({target: r.id, mine: data, mineBy: r.by || '', theirs: ex.data, theirsBy: ex.by,
          theirsAt: ex.at})}));
      conflicts++;
    }
    var at = now_();
    var rec = {id: r.id, type: type, owner: r.owner || '', account: r.account || '', at: at, by: r.by || '', deleted: !!r.deleted,
      base: r.base || 0, data: data};
    var cells = cells_(rec);
    if (ex) {
      sh.getRange(ex.row, 1, 1, cells.length).setValues([cells]);
      var width = sh.getLastColumn();
      if (width > cells.length) {
        sh.getRange(ex.row, cells.length + 1, 1, width - cells.length).clearContent();
      }
      ex.at = at;
      ex.data = data;
      ex.by = rec.by;
    } else {
      append.push(cells);
      cur[r.id] = {row: -1, id: r.id, at: at, data: data, by: rec.by};
    }
    stamped.push({id: r.id, at: at});
  }
  if (append.length) {
    var w = 0;
    for (var j = 0; j < append.length; j++) {
      w = Math.max(w, append[j].length);
    }
    for (var k = 0; k < append.length; k++) {
      while (append[k].length < w) {
        append[k].push('');
      }
    }
    sh.getRange(sh.getLastRow() + 1, 1, append.length, w).setValues(append);
  }
  return {ok: true, stamped: stamped, conflicts: conflicts, serverTime: Date.now()};
}

function pull_(req) {
  var since = Number(req.since || 0);
  var all = readAll_();
  var out = [];
  var by = latest_(all.rows);
  for (var id in by) {
    var r = by[id];
    if (r.at > since) {
      out.push({id: r.id, type: r.type, owner: r.owner, account: r.account, at: r.at, by: r.by, deleted: r.deleted, base: r.base,
        data: r.data});
    }
  }
  return {ok: true, records: out, serverTime: Date.now()};
}

function compact_() {
  var all = readAll_();
  var by = latest_(all.rows);
  var keep = [];
  var w = COLS.length;
  for (var id in by) {
    var c = cells_(by[id]);
    w = Math.max(w, c.length);
    keep.push(c);
  }
  for (var i = 0; i < keep.length; i++) {
    while (keep[i].length < w) {
      keep[i].push('');
    }
  }
  var sh = all.sheet;
  var last = sh.getLastRow();
  if (last > 1) {
    sh.getRange(2, 1, last - 1, Math.max(w, sh.getLastColumn())).clearContent();
  }
  if (keep.length) {
    sh.getRange(2, 1, keep.length, w).setValues(keep);
  }
  return {ok: true, before: all.rows.length, after: keep.length};
}

// ---------------------------------------------------------------- rendering the human sheets
function readMeta_() {
  var sh = sheet_(META, true);
  var last = sh.getLastRow();
  var m = {};
  if (last < 1) {
    return m;
  }
  var vals = sh.getRange(1, 1, last, 2).getValues();
  for (var i = 0; i < vals.length; i++) {
    if (vals[i][0]) {
      try {
        m[String(vals[i][0])] = JSON.parse(String(vals[i][1] || '{}'));
      } catch (err) {
        m[String(vals[i][0])] = {};
      }
    }
  }
  return m;
}

function writeMeta_(m) {
  var sh = sheet_(META, true);
  var rows = [];
  for (var k in m) {
    rows.push([k, JSON.stringify(m[k])]);
  }
  var last = sh.getLastRow();
  if (last > 0) {
    sh.getRange(1, 1, last, 2).clearContent();
  }
  if (rows.length) {
    sh.getRange(1, 1, rows.length, 2).setValues(rows);
  }
}

function render_(req) {
  var p = props_();
  var version = Number(req.version || 0);
  var curVersion = Number(p.getProperty('RENDER_VERSION') || 0);
  if (version < curVersion) {
    return {ok: true, skipped: true, version: curVersion};
  }
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var meta = readMeta_();
  var managed = meta['__managed'] || {names: []};
  var now = {};
  var sheets = req.sheets || [];
  for (var i = 0; i < sheets.length; i++) {
    var s = sheets[i];
    now[s.name] = true;
    var sh = ss.getSheetByName(s.name) || ss.insertSheet(s.name);
    var rows = [];
    var width = 1;
    if (s.headerRow && s.header && s.header.length) {
      rows.push(s.header);
    }
    for (var r = 0; r < (s.rows || []).length; r++) {
      rows.push(s.rows[r]);
    }
    for (var a = 0; a < rows.length; a++) {
      width = Math.max(width, rows[a].length);
    }
    for (var b = 0; b < rows.length; b++) {
      var row = rows[b].slice();
      while (row.length < width) {
        row.push('');
      }
      rows[b] = row;
    }
    sh.clear();
    if (rows.length) {
      sh.getRange(1, 1, rows.length, width).setValues(rows);
      var bg = [];
      var fg = [];
      var off = s.headerRow && s.header && s.header.length ? 1 : 0;
      for (var y = 0; y < rows.length; y++) {
        var bgr = [];
        var fgr = [];
        for (var x = 0; x < width; x++) {
          var code = y < off ? 'H' : (s.tones && s.tones[y - off] && s.tones[y - off][x]) || '';
          var t = TONES[code];
          bgr.push(t ? t[0] : '#FFFFFF');
          fgr.push(t ? t[1] : '#111827');
        }
        bg.push(bgr);
        fg.push(fgr);
      }
      var rg = sh.getRange(1, 1, rows.length, width);
      rg.setBackgrounds(bg);
      rg.setFontColors(fg);
      if (off) {
        sh.getRange(1, 1, 1, width).setFontWeight('bold');
        sh.setFrozenRows(1);
      }
    }
    var widths = s.widths || [];
    for (var c = 0; c < widths.length; c++) {
      sh.setColumnWidth(c + 1, Math.max(30, Math.round(widths[c] * 7)));
    }
    var hidden = s.hidden || [];
    for (var h = 0; h < hidden.length; h++) {
      sh.hideColumns(hidden[h] + 1);
    }
    meta[s.name] = {editable: s.editable || {}, headerRow: !!(s.headerRow && s.header && s.header.length), version: version};
  }
  // remove sheets JCrawl drew before and does not draw any more (never the user's own sheets)
  for (var n = 0; n < (managed.names || []).length; n++) {
    var old = managed.names[n];
    if (!now[old]) {
      var osh = ss.getSheetByName(old);
      if (osh && ss.getSheets().length > 1) {
        ss.deleteSheet(osh);
      }
      delete meta[old];
    }
  }
  var names = [];
  for (var nm in now) {
    names.push(nm);
  }
  meta['__managed'] = {names: names};
  writeMeta_(meta);
  p.setProperty('RENDER_VERSION', String(version));
  return {ok: true, version: version, sheets: names.length};
}

// ---------------------------------------------------------------- hand edits -> records
var STATUS_WORDS = ['DONE', 'DENIED', 'ERROR', 'MISSING', 'DOING', 'OOS'];

function statusOf_(text) {
  var t = String(text || '').trim().toUpperCase();
  for (var i = 0; i < STATUS_WORDS.length; i++) {
    if (t.indexOf(STATUS_WORDS[i]) === 0) {
      return STATUS_WORDS[i];
    }
  }
  return '';
}

function onEdit(e) {
  if (!e || !e.range) {
    return;
  }
  var sh = e.range.getSheet();
  var name = sh.getName();
  if (name === DATA || name === META) {
    return;
  }
  var meta = readMeta_()[name];
  if (!meta || !meta.editable) {
    return;
  }
  var who = '';
  try {
    who = Session.getActiveUser().getEmail();
  } catch (err) {
    who = '';
  }
  who = who || 'Google Sheet';
  var r0 = e.range.getRow();
  var c0 = e.range.getColumn();
  var vals = e.range.getValues();
  var out = [];
  for (var y = 0; y < vals.length; y++) {
    var row = r0 + y;
    if (meta.headerRow && row === 1) {
      continue;
    }
    var rowId = String(sh.getRange(row, 1).getValue() || '');
    if (!rowId) {
      continue;
    }
    for (var x = 0; x < vals[y].length; x++) {
      var kind = meta.editable[String(c0 + x - 1)];
      if (!kind) {
        continue;
      }
      var v = String(vals[y][x] === null || vals[y][x] === undefined ? '' : vals[y][x]);
      var at = now_();
      var rec;
      if (kind.indexOf('mark:') === 0) {
        var letter = kind.substring(5);
        var st = statusOf_(v);
        rec = {id: 'mark:' + rowId + ':' + letter, type: 'mark', owner: who, account: letter, at: at, by: who, deleted: false, base: 0,
          data: JSON.stringify({row: rowId, status: st, note: st ? '' : v, via: 'Google Sheet'})};
      } else if (kind === 'note') {
        rec = {id: 'note:' + rowId, type: 'note', owner: who, account: '', at: at, by: who, deleted: false, base: 0,
          data: JSON.stringify({row: rowId, note: v})};
      } else if (kind === 'answer' || kind === 'answer-detail') {
        rec = {id: 'qans:' + rowId, type: 'qans', owner: who, account: '', at: at, by: who, deleted: false, base: 0,
          data: JSON.stringify({answer: v, via: 'Google Sheet'})};
      }
      if (rec) {
        out.push(cells_(rec));
      }
    }
  }
  if (out.length) {
    var dsh = sheet_(DATA, true);
    var w = 0;
    for (var i = 0; i < out.length; i++) {
      w = Math.max(w, out[i].length);
    }
    for (var j = 0; j < out.length; j++) {
      while (out[j].length < w) {
        out[j].push('');
      }
    }
    dsh.getRange(dsh.getLastRow() + 1, 1, out.length, w).setValues(out);
  }
}

// ---------------------------------------------------------------- screenshots on Drive
function folder_() {
  var p = props_();
  var id = p.getProperty('FOLDER');
  if (id) {
    try {
      return DriveApp.getFolderById(id);
    } catch (err) {
      id = '';
    }
  }
  var f = DriveApp.createFolder('JCrawl ảnh Q&A - ' + (p.getProperty('NAME') || 'dự án'));
  p.setProperty('FOLDER', f.getId());
  return f;
}

function image_(req) {
  var bytes = Utilities.base64Decode(String(req.b64 || ''));
  var blob = Utilities.newBlob(bytes, String(req.mime || 'image/png'), String(req.qa || 'qa') + '_' + String(req.name || 'anh.png'));
  var file = folder_().createFile(blob);
  return {ok: true, id: file.getId(), url: file.getUrl()};
}

function imageGet_(req) {
  var file = DriveApp.getFileById(String(req.id || ''));
  var blob = file.getBlob();
  return {ok: true, b64: Utilities.base64Encode(blob.getBytes()), mime: blob.getContentType()};
}
