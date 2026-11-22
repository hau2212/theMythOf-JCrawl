// Runs JCrawlSheetHub.gs against an in-memory fake of the Google services it uses, behind a local HTTP server, so
// the Java client can be tested end to end against the real script. Usage: node harness.js <script.gs> <port>
'use strict';
const fs = require('fs');
const http = require('http');
const vm = require('vm');

function Sheet(name) {
  this.name = name;
  this.cells = [];          // rows of values (0-based)
  this.bg = {};
  this.hiddenCols = new Set();
  this.hidden = false;
  this.frozen = 0;
  this.widths = {};
}
Sheet.prototype.getName = function () { return this.name; };
Sheet.prototype.getLastRow = function () {
  for (let r = this.cells.length - 1; r >= 0; r--) {
    if ((this.cells[r] || []).some(v => v !== '' && v !== null && v !== undefined)) return r + 1;
  }
  return 0;
};
Sheet.prototype.getLastColumn = function () {
  let w = 0;
  for (const row of this.cells) {
    if (!row) continue;
    for (let c = row.length - 1; c >= 0; c--) {
      if (row[c] !== '' && row[c] !== null && row[c] !== undefined) { w = Math.max(w, c + 1); break; }
    }
  }
  return w;
};
Sheet.prototype.getRange = function (r, c, nr, nc) { return new Range(this, r, c, nr || 1, nc || 1); };
Sheet.prototype.getDataRange = function () { return this.getRange(1, 1, Math.max(1, this.getLastRow()), Math.max(1, this.getLastColumn())); };
Sheet.prototype.setFrozenRows = function (n) { this.frozen = n; };
Sheet.prototype.hideColumns = function (c) { this.hiddenCols.add(c); };
Sheet.prototype.setColumnWidth = function (c, w) { this.widths[c] = w; };
Sheet.prototype.hideSheet = function () { this.hidden = true; };
Sheet.prototype.clear = function () { this.cells = []; this.bg = {}; this.hiddenCols = new Set(); };
Sheet.prototype.appendRow = function (row) { this.getRange(this.getLastRow() + 1, 1, 1, row.length).setValues([row]); };

function Range(sheet, r, c, nr, nc) { Object.assign(this, {sheet, r, c, nr, nc}); }
Range.prototype.getSheet = function () { return this.sheet; };
Range.prototype.getRow = function () { return this.r; };
Range.prototype.getColumn = function () { return this.c; };
Range.prototype.getValues = function () {
  const out = [];
  for (let y = 0; y < this.nr; y++) {
    const row = this.sheet.cells[this.r - 1 + y] || [];
    const o = [];
    for (let x = 0; x < this.nc; x++) {
      const v = row[this.c - 1 + x];
      o.push(v === undefined || v === null ? '' : v);
    }
    out.push(o);
  }
  return out;
};
Range.prototype.getValue = function () { return this.getValues()[0][0]; };
Range.prototype.setValues = function (vals) {
  if (vals.length !== this.nr || vals.some(r => r.length !== this.nc)) {
    throw new Error('The number of rows/columns in the data does not match the range: ' + vals.length + 'x' + (vals[0] || []).length
      + ' vs ' + this.nr + 'x' + this.nc);
  }
  for (let y = 0; y < this.nr; y++) {
    const ri = this.r - 1 + y;
    while (this.sheet.cells.length <= ri) this.sheet.cells.push([]);
    const row = this.sheet.cells[ri];
    for (let x = 0; x < this.nc; x++) {
      let v = vals[y][x];
      if (typeof v === 'string' && v.length > 50000) throw new Error('Your input contains more than the maximum of 50000 characters in a single cell.');
      row[this.c - 1 + x] = v;
    }
  }
  return this;
};
Range.prototype.setValue = function (v) { return this.setValues([[v]]); };
Range.prototype.clearContent = function () {
  const blank = [];
  for (let y = 0; y < this.nr; y++) blank.push(new Array(this.nc).fill(''));
  return this.setValues(blank);
};
Range.prototype.setBackgrounds = function (m) { this.sheet.bg[this.r + ',' + this.c] = m; return this; };
Range.prototype.setFontColors = function () { return this; };
Range.prototype.setFontWeight = function () { return this; };

const ss = {
  sheets: [new Sheet('Trang tính1')],
  getSheetByName(n) { return this.sheets.find(s => s.name === n) || null; },
  insertSheet(n) { const s = new Sheet(n); this.sheets.push(s); return s; },
  getSheets() { return this.sheets.slice(); },
  deleteSheet(s) { this.sheets = this.sheets.filter(x => x !== s); }
};
const props = {};
const files = {};
let fileSeq = 0;
const ctx = {
  JSON, Math, Date, String, Number, Object, Array, Error, console,
  SpreadsheetApp: {getActiveSpreadsheet: () => ss},
  ContentService: {
    MimeType: {JSON: 'application/json'},
    createTextOutput: (s) => ({body: s, setMimeType() { return this; }})
  },
  LockService: {getScriptLock: () => ({waitLock() {}, tryLock() { return true; }, releaseLock() {}})},
  PropertiesService: {getScriptProperties: () => ({getProperty: (k) => (k in props ? props[k] : null), setProperty: (k, v) => { props[k] = String(v); }})},
  Session: {getActiveUser: () => ({getEmail: () => 'khach@example.test'})},
  Utilities: {
    base64Decode: (s) => Array.from(Buffer.from(s, 'base64')),
    base64Encode: (b) => Buffer.from(b).toString('base64'),
    newBlob: (bytes, mime, name) => ({bytes, mime, name, getBytes() { return bytes; }, getContentType() { return mime; }})
  },
  DriveApp: {
    createFolder: (n) => ({getId: () => 'folder1', createFile: (blob) => { const id = 'f' + (++fileSeq); files[id] = blob; return {getId: () => id, getUrl: () => 'https://drive.test/' + id}; }}),
    getFolderById: (id) => ctx.DriveApp.createFolder(id),
    getFileById: (id) => { if (!files[id]) throw new Error('no file'); return {getBlob: () => files[id]}; }
  }
};
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), ctx, {filename: 'JCrawlSheetHub.gs'});

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', d => { body += d; });
  req.on('end', () => {
    let out;
    try {
      if (req.method === 'GET' && req.url.startsWith('/__sheet')) {
        // test helper: dump a sheet
        const name = decodeURIComponent(req.url.split('?n=')[1] || '');
        const s = ss.getSheetByName(name);
        out = {body: JSON.stringify(s ? {cells: s.cells, hidden: [...s.hiddenCols], frozen: s.frozen} : null)};
      } else if (req.method === 'POST' && req.url.startsWith('/__edit')) {
        // test helper: a person types into a cell -> onEdit
        const e = JSON.parse(body);
        const s = ss.getSheetByName(e.sheet);
        const rg = s.getRange(e.row, e.col, 1, 1);
        rg.setValues([[e.value]]);
        ctx.onEdit({range: rg});
        out = {body: '{"ok":true}'};
      } else if (req.method === 'GET') {
        out = ctx.doGet({});
      } else {
        out = ctx.doPost({postData: {contents: body}});
      }
      res.writeHead(200, {'Content-Type': 'application/json; charset=utf-8'});
      res.end(out.body);
    } catch (err) {
      res.writeHead(500);
      res.end(String(err && err.stack || err));
    }
  });
});
server.listen(Number(process.argv[3] || 0), '127.0.0.1', () => {
  console.log('READY ' + server.address().port);
});
