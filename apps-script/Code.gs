/*************************************************************
 * 충주 KT대리점 칠금점 · 개통장부 공용 저장소
 * Google Apps Script 웹앱 (doGet / doPost)
 *
 *  · 메인 시트(SHEET_NAME = '전체_통합') 한 곳에 모든 직원 기록이 쌓입니다.
 *  · '담당자' 열로 직원을 구분하고, 직원별 보기 탭은 FILTER 로 자동 생성됩니다.
 *  · 앱(kt-store-manager)에서 매장 PIN 을 함께 보내야 읽기/쓰기가 됩니다.
 *
 *  설치 : 스프레드시트 → 확장 프로그램 → Apps Script 에 이 파일 전체를 붙여넣고
 *         배포 → 새 배포 → 웹앱 (실행: 나 / 액세스: 모든 사용자) → 배포
 *         나온 주소(https://script.google.com/macros/s/…/exec)를 앱의
 *         '더보기 → 공용 장부 연동'에 넣으면 끝.
 *************************************************************/

/* ===== 매장 설정 ===== */
var SHEET_NAME   = '전체_통합';          // 메인(통합) 시트 이름 — 앱의 데이터가 쌓이는 곳
var STORE_PIN    = '5001';               // 매장 접속 PIN (조회·등록·수정)
var ADMIN_PIN    = '0525';               // 사장님(관리자) PIN — 삭제·엑셀·백업은 이 PIN 이 있어야
var SPREADSHEET_ID = '1DjKKQT9K2ryDKjRL88B31pMhdqgqlzEX39uDbG8F4E4'; // 시트에 붙여 만든 경우 비워도 됨
var STAFF_NAMES  = ['김태양', '김설화', '이건엽', '권영한'];   // 직원별 보기 탭을 미리 만들 이름들 (직원 4명)
var CREATE_STAFF_TABS = true;            // 담당자 이름이 처음 등장할 때 직원별 보기 탭 자동 생성

/* ===== 컬럼 (순서 고정) ===== */
var COLS = ['id', '개통일', '고객명', '연락처', '생년월일', '개통유형', '기종', '요금제',
            '변경요금제', '요금제변경일자',
            '부가서비스', '부가서비스유지', '부가서비스해지일자',
            '중고폰', '중고폰금액', '무선리베이트', '유선리베이트', '메모', '담당자', '수정일시'];

/* 금액(숫자)으로 저장하는 열 */
var MONEY_COLS = ['중고폰금액', '무선리베이트', '유선리베이트'];

/* 앞자리 0(010105)이나 날짜 문자열이 숫자로 바뀌지 않도록 텍스트로 고정하는 열 */
var TEXT_COLS = ['id', '개통일', '연락처', '생년월일', '요금제변경일자', '부가서비스해지일자', '수정일시'];

/* 예전 장부의 단일 리베이트 열 — 시트를 처음 열 때 무선/유선으로 나눠 옮긴다 */
var LEGACY_REBATE_COL = '리베이트';
var WIRED_TYPES = ['인터넷+TV 신규가입', '인터넷+TV 약정갱신', '인터넷 약정갱신'];

/* =========================================================
   웹앱 진입점
   ========================================================= */

/** 조회 : {URL}?pin=5001&action=list  (JSONP 는 &callback=함수명) */
function doGet(e) {
  var p = (e && e.parameter) || {};
  var cb = String(p.callback || '');
  var out;
  try {
    checkPin_(p.pin);
    var action = String(p.action || 'list');
    if (action === 'list') {
      out = { ok: true, action: 'list', sheet: SHEET_NAME, records: readAll_(), serverTime: nowIso_() };
    } else if (action === 'ping') {
      var sh = getDataSheet_();
      out = { ok: true, action: 'ping', sheet: SHEET_NAME,
              count: Math.max(0, sh.getLastRow() - 1), serverTime: nowIso_() };
    } else {
      throw new Error('지원하지 않는 요청입니다: ' + action);
    }
  } catch (err) {
    out = { ok: false, error: errMessage_(err) };
  }
  return jsonOut_(out, cb);
}

/** 저장/삭제 : 본문 {"pin":"5001","action":"upsert"|"delete","record":{…},"id":"…"} */
function doPost(e) {
  var req = {};
  try {
    if (e && e.postData && e.postData.contents) req = JSON.parse(e.postData.contents);
    else req = (e && e.parameter) || {};
  } catch (err) {
    req = {};
  }
  var out;
  try {
    checkPin_(req.pin);
    var action = String(req.action || 'upsert');
    if (action === 'upsert') {
      out = upsert_(req.record || {});
    } else if (action === 'delete') {
      checkAdminPin_(req.adminPin);
      out = remove_(req.id || (req.record && req.record.id));
    } else if (action === 'list') {
      out = { ok: true, action: 'list', sheet: SHEET_NAME, records: readAll_(), serverTime: nowIso_() };
    } else {
      throw new Error('지원하지 않는 요청입니다: ' + action);
    }
  } catch (err) {
    out = { ok: false, error: errMessage_(err) };
  }
  return jsonOut_(out, '');
}

/* =========================================================
   인증 · 공통
   ========================================================= */

function checkAdminPin_(pin) {
  var got = String(pin == null ? '' : pin).trim();
  if (got !== String(ADMIN_PIN)) throw new Error('삭제는 관리자 PIN이 있어야 됩니다.');
}

function checkPin_(pin) {
  var got = String(pin == null ? '' : pin).trim();
  if (got !== String(STORE_PIN)) throw new Error('매장 PIN이 맞지 않습니다.');
}

function jsonOut_(obj, cb) {
  var body = JSON.stringify(obj);
  if (cb && /^[A-Za-z_$][\w$.]*$/.test(cb)) {
    return ContentService.createTextOutput(cb + '(' + body + ');')
      .setMimeType(ContentService.MimeType.JAVASCRIPT);
  }
  return ContentService.createTextOutput(body).setMimeType(ContentService.MimeType.JSON);
}

function errMessage_(err) {
  return String((err && err.message) || err || '알 수 없는 오류');
}

function nowIso_() {
  return Utilities.formatDate(new Date(), tz_(), "yyyy-MM-dd'T'HH:mm:ssXXX");
}

function tz_() {
  try {
    var tz = getSpreadsheet_().getSpreadsheetTimeZone();
    if (tz) return tz;
  } catch (err) {}
  return Session.getScriptTimeZone() || 'Asia/Seoul';
}

function pad2_(n) { return (n < 10 ? '0' : '') + n; }

/** '1,500,000원' 같은 표기도 숫자로 */
function num_(v) {
  if (typeof v === 'number') return isFinite(v) ? v : 0;
  var s = String(v == null ? '' : v).replace(/[^0-9.\-]/g, '');
  if (!s || s === '-' || s === '.') return 0;
  var n = parseFloat(s);
  return isFinite(n) ? n : 0;
}

/** 유선(인터넷·TV) 개통인가? (예전 '인터넷+TV' 포함) */
function isWiredType_(t) {
  var s = String(t == null ? '' : t).replace(/\s/g, '');
  if (!s) return false;
  for (var i = 0; i < WIRED_TYPES.length; i++) {
    if (WIRED_TYPES[i].replace(/\s/g, '') === s) return true;
  }
  return /인터넷|TV|티비|iptv/i.test(s);
}

/** 엑셀 일련번호(46000 등)는 날짜로, 나머지는 문자열 그대로 */
function dateToText_(v, tz) {
  if (v instanceof Date) {
    if (isNaN(v.getTime())) return '';
    return Utilities.formatDate(v, tz, 'yyyy-MM-dd');
  }
  if (typeof v === 'number' && v > 20000 && v < 80000) {
    var ms = Date.UTC(1899, 11, 30) + Math.round(v) * 86400000;
    var d = new Date(ms);
    return d.getUTCFullYear() + '-' + pad2_(d.getUTCMonth() + 1) + '-' + pad2_(d.getUTCDate());
  }
  return String(v == null ? '' : v);
}

/* =========================================================
   시트 접근
   ========================================================= */

function getSpreadsheet_() {
  var ss = null;
  try { ss = SpreadsheetApp.getActiveSpreadsheet(); } catch (err) { ss = null; }
  if (!ss && SPREADSHEET_ID) ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  if (!ss) throw new Error('스프레드시트를 열 수 없습니다. SPREADSHEET_ID 를 확인해 주세요.');
  return ss;
}

/** 메인(통합) 시트. 없으면 새로 만들고 헤더를 깐다. */
function getDataSheet_() {
  var ss = getSpreadsheet_();
  var sh = ss.getSheetByName(SHEET_NAME);
  if (!sh) {
    sh = ss.insertSheet(SHEET_NAME);
    writeHeader_(sh, 1, COLS.length);
  }
  return sh;
}

function writeHeader_(sh, row, width) {
  sh.getRange(row, 1, 1, COLS.length).setValues([COLS.slice()]);
  sh.getRange(row, 1, 1, width || COLS.length)
    .setFontWeight('bold')
    .setBackground('#0B57D0')
    .setFontColor('#FFFFFF');
  if (row === 1) sh.setFrozenRows(1);
}

/** 1행 헤더를 읽어 '컬럼명 → 열 번호(0부터)' 지도를 만든다. */
function headerMap_(sh) {
  var width = Math.max(sh.getLastColumn(), COLS.length);
  var cur = sh.getRange(1, 1, 1, width).getValues()[0];
  var map = {}, found = 0;
  for (var i = 0; i < cur.length; i++) {
    var name = String(cur[i] || '').trim();
    if (!name) continue;
    for (var c = 0; c < COLS.length; c++) {
      if (COLS[c] === name && map[name] === undefined) { map[name] = i; found++; break; }
    }
  }
  // 헤더가 비었거나 일부만 있어도 동작하도록 기본 순서로 보정
  for (var k = 0; k < COLS.length; k++) {
    if (map[COLS[k]] === undefined) map[COLS[k]] = k;
  }
  return { map: map, width: width, found: found };
}

function ensureHeader_(sh) {
  // 예전 형식(단일 리베이트) 시트면 새 형식으로 옮긴다
  try { migrateSheet_(sh); } catch (err) { /* 옮기기 실패해도 읽기·쓰기는 계속 */ }
  var idx = headerMap_(sh);
  if (idx.found < 3 && sh.getLastRow() <= 1) {
    writeHeader_(sh, 1, COLS.length);
    idx = headerMap_(sh);
  }
  return idx;
}

/** 1행 헤더를 문자열 배열로 */
function readHeader_(sh) {
  var width = Math.max(sh.getLastColumn(), COLS.length);
  return sh.getRange(1, 1, 1, width).getValues()[0].map(function (v) {
    return String(v == null ? '' : v).trim();
  });
}

/**
 * 헤더가 새 형식(무선리베이트·유선리베이트)이 아니면,
 * 예전 열 이름을 지켜 값을 새 열 순서로 옮기고 헤더를 다시 그린다.
 * 예전 단일 '리베이트' 값은 개통유형을 보고 유선/무선으로 나네 넣는다.
 * 이미 새 형식이면 아무 것도 하지 않고 false 를 돌려준다.
 */
function migrateSheet_(sh) {
  var head = readHeader_(sh);
  var allNew = head.indexOf('무선리베이트') >= 0 && head.indexOf('유선리베이트') >= 0;
  if (allNew && head.indexOf(LEGACY_REBATE_COL) < 0) {
    for (var n = 0; n < COLS.length; n++) {
      if (head.indexOf(COLS[n]) < 0) { allNew = false; break; }
    }
  } else {
    allNew = false;
  }
  if (allNew) return false;

  var last = sh.getLastRow();
  if (last < 2) {
    writeHeader_(sh, 1, COLS.length);
    return true;
  }

  var tz = tz_();
  var width = Math.max(head.length, COLS.length);
  var values = sh.getRange(2, 1, last - 1, width).getValues();
  var rows = [];
  for (var r = 0; r < values.length; r++) {
    var src = values[r];
    var byName = {};
    for (var i = 0; i < head.length; i++) {
      if (head[i]) byName[head[i]] = src[i];
    }
    if (byName['무선리베이트'] == null && byName['유선리베이트'] == null) {
      var legacy = num_(byName[LEGACY_REBATE_COL]);
      if (legacy) {
        if (isWiredType_(byName['개통유형'])) byName['유선리베이트'] = legacy;
        else byName['무선리베이트'] = legacy;
      }
    }
    var line = [], any = false;
    for (var k = 0; k < COLS.length; k++) {
      var name = COLS[k];
      var v = byName[name];
      var cell;
      if (name === 'id') cell = String(v == null ? '' : v).trim();
      else if (MONEY_COLS.indexOf(name) >= 0) cell = num_(v);
      else cell = dateToText_(v, tz);
      line.push(cell);
      if (String(cell == null ? '' : cell).trim() !== '') any = true;
    }
    if (!any) continue;
    if (!line[0]) line[0] = 'row' + (r + 2);
    rows.push(line);
  }

  // 헤더 + 데이터를 한 번에 새 순서로 덮어쓴다 (clear 후 쓰기보다 중간에 끼어들 틈이 적다)
  var block = [COLS.slice()].concat(rows);
  sh.getRange(1, 1, block.length, COLS.length).setValues(block);
  sh.getRange(1, 1, 1, COLS.length)
    .setFontWeight('bold')
    .setBackground('#0B57D0')
    .setFontColor('#FFFFFF');
  sh.setFrozenRows(1);
  var idx = headerMap_(sh);
  for (var t = 0; t < TEXT_COLS.length; t++) {
    sh.getRange(2, idx.map[TEXT_COLS[t]] + 1, Math.max(rows.length, 1)).setNumberFormat('@');
  }
  return true;
}

function colLetter_(n) { // 1부터 시작
  var s = '';
  while (n > 0) {
    var m = (n - 1) % 26;
    s = String.fromCharCode(65 + m) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}

/** 전체 기록 읽기 */
function readAll_() {
  var sh = getDataSheet_();
  var idx = ensureHeader_(sh);
  var last = sh.getLastRow();
  if (last < 2) return [];
  var tz = tz_();
  var values = sh.getRange(2, 1, last - 1, idx.width).getValues();
  var out = [];
  for (var r = 0; r < values.length; r++) {
    var row = values[r];
    var rec = {}, any = false;
    for (var c = 0; c < COLS.length; c++) {
      var name = COLS[c];
      var v = row[idx.map[name]];
      var s = dateToText_(v, tz).trim();
      if (MONEY_COLS.indexOf(name) >= 0) rec[name] = num_(v);
      else rec[name] = s;
      if (s) any = true;
    }
    if (!any) continue;
    if (!rec.id) rec.id = 'row' + (r + 2);
    out.push(rec);
  }
  return out;
}

/** id 가 있는 행 번호(1부터). 없으면 0 */
function findRow_(sh, idx, id) {
  var last = sh.getLastRow();
  if (last < 2) return 0;
  var col = idx.map['id'] + 1;
  var values = sh.getRange(2, col, last - 1, 1).getValues();
  var key = String(id || '').trim();
  for (var i = 0; i < values.length; i++) {
    if (String(values[i][0] || '').trim() === key) return i + 2;
  }
  return 0;
}

/** 한 행 쓰기 (헤더 순서가 달라도 맞춰서) */
function writeRow_(sh, idx, rowIdx, rec) {
  var values = [];
  for (var i = 0; i < idx.width; i++) values.push('');
  for (var c = 0; c < COLS.length; c++) {
    var name = COLS[c];
    var v = rec[name] == null ? '' : rec[name];
    values[idx.map[name]] = (MONEY_COLS.indexOf(name) >= 0) ? num_(v) : String(v);
  }
  for (var t = 0; t < TEXT_COLS.length; t++) {
    sh.getRange(rowIdx, idx.map[TEXT_COLS[t]] + 1).setNumberFormat('@');
  }
  sh.getRange(rowIdx, 1, 1, idx.width).setValues([values]);
}

/* =========================================================
   저장 · 삭제
   ========================================================= */

function upsert_(rec) {
  if (!rec || !rec.id) throw new Error('기록 id가 없습니다.');
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) throw new Error('다른 저장이 진행 중입니다. 잠시 뒤 다시 시도해 주세요.');
  try {
    var sh = getDataSheet_();
    var idx = ensureHeader_(sh);
    var serverNow = nowIso_();
    var clean = {};
    for (var c = 0; c < COLS.length; c++) {
      var name = COLS[c];
      clean[name] = rec[name] == null ? '' : String(rec[name]).trim();
    }
    clean.id = String(rec.id).trim();
    clean.수정일시 = clean.수정일시 || serverNow;

    var rowIdx = findRow_(sh, idx, clean.id);
    var mode = rowIdx > 0 ? 'updated' : 'appended';
    if (rowIdx <= 0) rowIdx = Math.max(sh.getLastRow() + 1, 2);
    writeRow_(sh, idx, rowIdx, clean);

    if (CREATE_STAFF_TABS && clean.담당자) {
      try { ensureStaffTab_(clean.담당자); } catch (err) { /* 탭 생성 실패는 저장에 영향 없음 */ }
    }
    return { ok: true, action: 'upsert', id: clean.id, mode: mode,
             수정일시: clean.수정일시, serverTime: serverNow };
  } finally {
    lock.releaseLock();
  }
}

function remove_(id) {
  var key = String(id || '').trim();
  if (!key) throw new Error('삭제할 기록 id가 없습니다.');
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) throw new Error('다른 저장이 진행 중입니다. 잠시 뒤 다시 시도해 주세요.');
  try {
    var sh = getDataSheet_();
    var idx = ensureHeader_(sh);
    var row = findRow_(sh, idx, key);
    if (row > 0) sh.deleteRow(row);
    return { ok: true, action: 'delete', id: key, removed: row > 0, serverTime: nowIso_() };
  } finally {
    lock.releaseLock();
  }
}

/* =========================================================
   직원별 보기 탭 (담당자 이름으로 자동 분류)
   ========================================================= */

function staffTabName_(name) {
  var n = String(name || '').trim();
  if (!n) return '';
  n = n.replace(/[\\\/\?\*\[\]:]/g, ' ').replace(/\s+/g, ' ').trim();
  if (n.length > 24) n = n.slice(0, 24);
  return '담당_' + n;
}

/** 담당자 이름 하나에 대한 보기 탭을 (없으면) 만든다. */
function ensureStaffTab_(name) {
  var tabName = staffTabName_(name);
  if (!tabName) return null;
  var ss = getSpreadsheet_();
  var exist = ss.getSheetByName(tabName);
  if (exist) return exist;
  if (ss.getSheets().length >= 180) return null;

  var data = getDataSheet_();
  var idx = headerMap_(data);
  var lastCol = colLetter_(idx.width);
  var staffCol = colLetter_(idx.map['담당자'] + 1);
  var crit = String(name).replace(/"/g, '""');

  var sh = ss.insertSheet(tabName);
  writeHeader_(sh, 1, idx.width);
  sh.getRange(2, 1).setFormula(
    '=IFERROR(SORT(FILTER(\'' + SHEET_NAME + '\'!A2:' + lastCol +
    ', \'' + SHEET_NAME + '\'!' + staffCol + '2:' + staffCol + '="' + crit + '"), 2, FALSE), "아직 기록이 없습니다.")'
  );
  sh.getRange(1, 1).setNote(
    '자동 보기 탭입니다. 직접 고치지 말고 "' + SHEET_NAME + '" 탭에서 등록·수정하세요. ' +
    '앱에서 담당자를 "' + name + '"(으)로 저장한 기록이 자동으로 모입니다.'
  );
  return sh;
}

/** 직원별 보기 탭을 한 번에 만들거나 갱신 (에디터에서 직접 실행해도 됨) */
function createStaffTabs() {
  var names = STAFF_NAMES.slice();
  // 시트에 이미 기록된 담당자도 함께
  var rows = readAll_();
  for (var i = 0; i < rows.length; i++) {
    var n = String(rows[i].담당자 || '').trim();
    if (n && names.indexOf(n) < 0) names.push(n);
  }
  for (var j = 0; j < names.length; j++) ensureStaffTab_(names[j]);
  return names.join(', ');
}

/** 메뉴에서 바로 실행할 수 있는 초기 설정 (1회) */
function setup() {
  var sh = getDataSheet_();
  ensureHeader_(sh);
  createStaffTabs();
  return '설정 완료 · 메인 시트: ' + SHEET_NAME;
}

/** 배포 주소를 다시 만들었을 때 확인용 */
function whoami() {
  return { sheet: SHEET_NAME, pinLength: String(STORE_PIN).length, adminPinLength: String(ADMIN_PIN).length, staff: STAFF_NAMES };
}
