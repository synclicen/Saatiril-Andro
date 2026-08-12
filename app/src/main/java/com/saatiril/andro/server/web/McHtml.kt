package com.saatiril.andro.server.web

/**
 * MC Web Page — served by the ktor server at `/mc?channel=1`.
 *
 * This is a self-contained HTML page that:
 *  1. Connects to the server via WebSocket (Engine.IO v3)
 *  2. Authenticates as MC (role=mc, channel=X, sessionPasswordHash=SHA256(password))
 *  3. Sends REQUEST_STATE to get the initial project database
 *  4. Renders the queue + PANGGIL button
 *  5. Sends MC_CALL when PANGGIL is pressed
 *  6. Updates in real-time as students are called/photographed
 *
 * No external dependencies — uses vanilla JS + native WebSocket.
 * Works on iPhone Safari, Android Chrome, laptop browsers.
 * LAN-only — no internet needed.
 *
 * URL format:
 *   http://192.168.1.5:3003/mc?channel=1
 *   http://192.168.1.5:3003/mc?channel=1&password=secret
 */
const val MC_HTML = """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Saatiril MC</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { background:#1a0b2e; color:#fff; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; min-height:100vh; }
.header { background:#2a164a; padding:12px 16px; display:flex; align-items:center; gap:8px; border-bottom:1px solid #533485; position:sticky; top:0; z-index:10; }
.header .dot { width:10px; height:10px; border-radius:50%; background:#fbbf24; }
.header .dot.connecting { background:#fbbf24; }
.header .dot.authenticating { background:#06b6d4; }
.header .dot.connected { background:#4ade80; }
.header .dot.disconnected { background:#ef4444; }
.header h1 { font-size:16px; color:#d4af37; }
.header .ch { font-size:12px; color:#c4b5fd; margin-left:auto; }
.container { padding:12px; max-width:600px; margin:0 auto; }
.name-card { background:#2a164a; border:1px solid #533485; border-radius:10px; padding:16px; margin-bottom:8px; }
.name-card.active { border-color:#d4af37; background:#3b2263; }
.name-card .label { font-size:10px; color:#c4b5fd; margin-bottom:4px; text-transform:uppercase; }
.name-card .name { font-size:24px; font-weight:900; line-height:1.2; }
.name-card .name.gold { color:#d4af37; }
.name-card .name.white { color:#fff; }
.name-card .name.muted { color:#c4b5fd; }
.name-card .nim { font-size:12px; color:#c4b5fd; font-family:monospace; margin-top:4px; }
.panggil-btn { width:100%; height:56px; border:none; border-radius:12px; font-size:18px; font-weight:900; cursor:pointer; margin-bottom:8px; }
.panggil-btn.ready { background:#4ade80; color:#1a0b2e; }
.panggil-btn.waiting { background:#533485; color:#d4af37; cursor:wait; }
.panggil-btn.disabled { background:#2a164a; color:#c4b5fd; cursor:default; }
.reset-btn { width:100%; height:44px; border:1px solid #fbbf24; background:transparent; color:#fbbf24; border-radius:10px; font-size:14px; cursor:pointer; margin-bottom:8px; }
.stats { display:flex; gap:12px; font-size:11px; color:#c4b5fd; margin-bottom:8px; }
.stats span { color:#fff; font-weight:bold; }
.queue-title { font-size:12px; color:#c4b5fd; font-weight:bold; margin:8px 0 4px; }
.queue-item { background:#2a164a; border:1px solid #533485; border-radius:6px; padding:8px 10px; margin-bottom:2px; display:flex; align-items:center; gap:6px; }
.queue-item.active { border-color:#d4af37; background:#3b2263; }
.queue-item.done { opacity:0.4; }
.queue-item .num { color:#c4b5fd; font-size:11px; font-family:monospace; width:16px; }
.queue-item .dot { width:6px; height:6px; border-radius:50%; background:#c4b5fd; }
.queue-item .dot.active { background:#d4af37; }
.queue-item .dot.done { background:#4ade80; }
.queue-item .n { font-size:12px; flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.queue-item .badge { font-size:10px; color:#d4af37; }
.loading { text-align:center; padding:40px; color:#c4b5fd; }
.error-box { background:#ef4444; color:#fff; padding:12px; border-radius:8px; margin:8px 0; font-size:13px; }
</style>
</head>
<body>
<div class="header">
  <div class="dot connecting" id="status-dot"></div>
  <h1>SAATIRIL MC</h1>
  <div class="ch" id="ch-label">Ch.1</div>
</div>
<div class="container" id="app">
  <div class="loading">Menghubungkan ke server…</div>
</div>
<script>
(function(){
  var params = new URLSearchParams(window.location.search);
  var channel = parseInt(params.get('channel') || '1');
  var password = params.get('password') || '';
  var ws = null;
  var connected = false;
  var authenticated = false;
  var project = null;
  var pingTimer = null;
  var passwordHash = null;

  document.getElementById('ch-label').textContent = 'Ch.' + channel;

  // ── SHA-256 implementation (sync, for password hashing) ──
  // Uses Web Crypto API (async) with fallback to manual SHA-256.
  async function sha256(message) {
    if (window.crypto && window.crypto.subtle) {
      try {
        var data = new TextEncoder().encode(message);
        var hashBuffer = await window.crypto.subtle.digest('SHA-256', data);
        var hashArray = Array.from(new Uint8Array(hashBuffer));
        return hashArray.map(function(b){ return b.toString(16).padStart(2,'0'); }).join('');
      } catch(e) {
        return sha256Manual(message);
      }
    }
    return sha256Manual(message);
  }

  // Manual SHA-256 fallback (for older browsers without SubtleCrypto)
  function sha256Manual(ascii) {
    function rightRotate(value, amount) { return (value>>>amount) | (value<<(32-amount)); }
    var mathPow = Math.pow, maxWord = mathPow(2, 32), lengthProperty = 'length', result = '';
    var words = [], asciiBitLength = ascii[lengthProperty]*8, hash = sha256Manual.h = sha256Manual.h || [], k = sha256Manual.k = sha256Manual.k || [], primeCounter = k[lengthProperty];
    var isComposite = {}, simpleCounter = 0, hashValue = 2;
    for (; simpleCounter < 64; simpleCounter++) {
      var candidate = hashValue;
      do {
        for (var i = 0; i < simpleCounter; i++) { if (candidate % k[i] === 0) { candidate = candidate / k[i]; isComposite = true; } }
        if (isComposite) { isComposite = false; hashValue += 2; } else { break; }
      } while (true);
      if (primeCounter < 64) { k[primeCounter++] = (mathPow(candidate, .5)*maxWord)|0; }
      hashValue += 2;
    }
    ascii += '\x80';
    while (ascii[lengthProperty]%64 - 56) ascii += '\x00';
    for (i = 0; i < ascii[lengthProperty]; i++) {
      j = ascii.charCodeAt(i);
      if (j>>8) return;
      words[i>>2] |= j << ((3 - i)%4)*8;
    }
    words[words[lengthProperty]] = ((asciiBitLength/maxWord)|0);
    words[words[lengthProperty]] = (asciiBitLength);
    for (j = 0; j < words[lengthProperty];) {
      var w = words.slice(j, j += 16), w2 = [];
      for (i = 0; i < 16; i++) w2[i] = w[i];
      for (i = 16; i < 64; i++) {
        var w15 = w2[i-15], w2_ = w2[i-2];
        var a = rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15>>>3);
        var b = rightRotate(w2_, 17) ^ rightRotate(w2_, 19) ^ (w2_>>>10);
        w2[i] = w2[i-16] + a + w2[i-7] + b;
      }
      var wHash = hash.slice(0);
      var a2 = wHash[0], b2 = wHash[1], c2 = wHash[2], d2 = wHash[3], e2 = wHash[4], f2 = wHash[5], g2 = wHash[6], h2 = wHash[7];
      for (i = 0; i < 64; i++) {
        var a3 = rightRotate(e2, 6) ^ rightRotate(e2, 11) ^ rightRotate(e2, 25);
        var b3 = rightRotate(a2, 2) ^ rightRotate(a2, 13) ^ rightRotate(a2, 22);
        var c3 = (e2 & f2) ^ (~e2 & g2);
        var d3 = (a2 & b2) ^ (a2 & c2) ^ (b2 & c2);
        var temp1 = h2 + a3 + c3 + k[i] + w2[i];
        var temp2 = b3 + d3;
        h2 = g2; g2 = f2; f2 = e2; e2 = (d2 + temp1)|0;
        d2 = c2; c2 = b2; b2 = a2; a2 = (temp1 + temp2)|0;
      }
      hash[0] = (hash[0] + a2)|0; hash[1] = (hash[1] + b2)|0; hash[2] = (hash[2] + c2)|0; hash[3] = (hash[3] + d2)|0;
      hash[4] = (hash[4] + e2)|0; hash[5] = (hash[5] + f2)|0; hash[6] = (hash[6] + g2)|0; hash[7] = (hash[7] + h2)|0;
    }
    for (i = 0; i < 8; i++) { for (j = 28; j >= 0; j -= 4) result += ((hash[i]>>>(j))&0xF).toString(16); }
    return result;
  }

  async function connect() {
    // Hash password before connecting
    if (password && !passwordHash) {
      passwordHash = await sha256(password);
    }

    var wsUrl = 'ws://' + location.host + '/?EIO=3&transport=websocket';
    try {
      ws = new WebSocket(wsUrl);
    } catch(e) {
      showError('Tidak bisa connect: ' + e.message);
      setTimeout(connect, 3000);
      return;
    }
    ws.onopen = function(){};
    ws.onmessage = function(ev){ onMessage(ev.data); };
    ws.onclose = function(){ connected=false; authenticated=false; setStatus('disconnected'); setTimeout(connect, 2000); };
    ws.onerror = function(){};
  }

  function onMessage(raw) {
    if(raw.length < 1) return;
    var type = parseInt(raw[0]);
    var payload = raw.substring(1);
    if(type === 0) {
      // Engine.IO open — send socket.io connect
      connected = true;
      setStatus('connecting');
      sendRaw('40'); // socket.io CONNECT
    } else if(type === 4) {
      handleSio(payload);
    } else if(type === 2) {
      // ping from server — respond with pong
      sendRaw('3');
    }
  }

  function handleSio(payload) {
    if(payload === '0') {
      // socket.io connect ack — send identify
      setStatus('authenticating');
      var identifyData = {role:'mc', channel:channel};
      if (passwordHash) identifyData.sessionPasswordHash = passwordHash;
      sendEvent('identify', identifyData);
      // Start ping
      if(pingTimer) clearInterval(pingTimer);
      pingTimer = setInterval(function(){ sendRaw('2'); }, 20000);
    } else if(payload[0] === '2') {
      // Event: 42["event",{data}]
      try {
        var arr = JSON.parse(payload.substring(1));
        var eventName = arr[0];
        var data = arr[1];
        onEvent(eventName, data);
      } catch(e) { console.error('Parse error:', e, payload); }
    }
  }

  function onEvent(name, data) {
    // Server sends auth-success (not auth-ok)
    if(name === 'auth-success') {
      authenticated = true;
      setStatus('connected');
      // Request initial state — this triggers the server to send SYNC_DB
      sendLanMessage('REQUEST_STATE', {role:'mc', channel:channel});
    } else if(name === 'auth-failed' || name === 'auth-fail') {
      showError('Password salah atau ditolak server. Periksa password sesi di admin.');
    } else if(name === 'lan-message') {
      // lan-message wraps: {event: 'SYNC_DB', data: {...}}
      if(data && data.event) {
        handleLanMessage(data.event, data.data);
      }
    }
  }

  function handleLanMessage(event, data) {
    if(event === 'SYNC_DB' || event === 'sync-db') {
      project = data.project || data;
      render();
    } else if(event === 'MC_CALL' || event === 'mc-call') {
      if(project && data && data.student) {
        var sid = data.student.id;
        if(project.database) {
          project.database = project.database.map(function(s){
            if(s.id === sid) return Object.assign({}, s, {status:'active_' + (data.channel || channel)});
            return s;
          });
        }
        render();
      }
    } else if(event === 'STUDENT_DONE' || event === 'student-done') {
      if(project && data && data.studentId) {
        if(project.database) {
          project.database = project.database.map(function(s){
            if(s.id === data.studentId) return Object.assign({}, s, {status:'done'});
            return s;
          });
        }
        render();
      }
    } else if(event === 'PHOTOS_SAVED' || event === 'photos-saved') {
      if(project && data && data.student) {
        var sid = data.student.id;
        if(project.database) {
          project.database = project.database.map(function(s){
            if(s.id === sid) return Object.assign({}, s, {status:'done'});
            return s;
          });
        }
        render();
      }
    } else if(event === 'STUDENT_RESET' || event === 'student-reset') {
      if(project && data && data.studentId) {
        if(project.database) {
          project.database = project.database.map(function(s){
            if(s.id === data.studentId) return Object.assign({}, s, {status:'pending'});
            return s;
          });
        }
        render();
      }
    }
  }

  function sendRaw(msg) {
    if(ws && ws.readyState === 1) ws.send(msg);
  }
  function sendEvent(name, data) {
    sendRaw('42' + JSON.stringify([name, data]));
  }
  // Send a lan-message wrapped event (matches Android client protocol)
  function sendLanMessage(event, data) {
    sendEvent('lan-message', {event: event, data: data});
  }

  function callStudent(student) {
    sendLanMessage('MC_CALL', {student:student, channel:channel});
    // Optimistic update
    if(project && project.database) {
      project.database = project.database.map(function(s){
        if(s.id === student.id) return Object.assign({}, s, {status:'active_' + channel});
        return s;
      });
      render();
    }
  }

  function resetStudent(studentId) {
    sendLanMessage('STUDENT_RESET', {studentId:studentId, channel:channel});
    if(project && project.database) {
      project.database = project.database.map(function(s){
        if(s.id === studentId) return Object.assign({}, s, {status:'pending'});
        return s;
      });
      render();
    }
  }

  function isActive(status) { return status && status.indexOf('active') === 0; }

  function getChannelStudents() {
    if(!project || !project.database) return [];
    var db = project.database;
    var mode = project.config ? project.config.mode : 'single';
    var isPhotoshoot = mode.indexOf('photoshoot') >= 0;
    if(isPhotoshoot) return db;
    return db.filter(function(s){ return s.assignedChannel === channel; });
  }

  function render() {
    if(!project) {
      document.getElementById('app').innerHTML = '<div class="loading">Menunggu data dari server…</div>';
      return;
    }
    var students = getChannelStudents();
    var pending = students.filter(function(s){ return s.status === 'pending'; });
    var active = students.filter(function(s){ return isActive(s.status); });
    var done = students.filter(function(s){ return s.status === 'done'; });
    var hasActive = active.length > 0;
    var nextPending = pending.length > 0 ? pending[0] : null;

    var displayName, displayNim, nameClass, prefix;
    if(hasActive) {
      displayName = active[0].nama || active[0].nim || '';
      displayNim = active[0].nim || '';
      nameClass = 'gold';
      prefix = '\u25C6 ';
    } else if(nextPending) {
      displayName = nextPending.nama || nextPending.nim || '';
      displayNim = nextPending.nim || '';
      nameClass = 'white';
      prefix = '\u25B6 ';
    } else {
      displayName = 'Antrean Habis';
      displayNim = '';
      nameClass = 'muted';
      prefix = '';
    }

    var btnClass = 'disabled';
    var btnText = 'HABIS';
    if(hasActive) { btnClass = 'waiting'; btnText = 'TUNGGU OPERATOR\u2026'; }
    else if(nextPending) { btnClass = 'ready'; btnText = 'PANGGIL'; }

    // NO sorting — keep original queue order so MC knows "peserta no berapa"
    var queue = students.slice();

    var html = '';
    html += '<div class="name-card ' + (hasActive ? 'active' : '') + '">';
    html += '<div class="label">' + (hasActive ? 'Sedang Dipanggil' : 'Berikutnya') + '</div>';
    html += '<div class="name ' + nameClass + '">' + prefix + escapeHtml(displayName) + '</div>';
    if(displayNim) html += '<div class="nim">' + escapeHtml(displayNim) + '</div>';
    html += '</div>';

    if(nextPending && !hasActive) {
      // Store nextPending in global var — avoids HTML attribute escaping issues
      // with JSON.stringify(double-stringify) which breaks onclick parsing
      window.__nextPending = nextPending;
      html += '<button class="panggil-btn ' + btnClass + '" onclick="window.__call()">' + btnText + '</button>';
    } else {
      html += '<button class="panggil-btn ' + btnClass + '" disabled>' + btnText + '</button>';
    }

    if(hasActive) {
      window.__activeId = active[0].id;
      html += '<button class="reset-btn" onclick="window.__reset()">Reset (Ulang)</button>';
    }

    html += '<div class="stats">';
    html += 'Menunggu: <span>' + pending.length + '</span>';
    html += ' Selesai: <span>' + done.length + '</span>';
    html += ' Total: <span>' + students.length + '</span>';
    html += '</div>';

    html += '<div class="queue-title">Antrean Ch.' + channel + ' (' + pending.length + ' menunggu)</div>';
    queue.forEach(function(s, i){
      var a = isActive(s.status);
      var d = s.status === 'done';
      html += '<div class="queue-item ' + (a ? 'active' : '') + (d ? ' done' : '') + '">';
      html += '<span class="num">' + (i+1) + '</span>';
      html += '<span class="dot ' + (a ? 'active' : '') + (d ? 'done' : '') + '"></span>';
      html += '<span class="n">' + escapeHtml(s.nama || s.nim || '(tanpa nama)') + '</span>';
      if(a) html += '<span class="badge">\u25C6</span>';
      if(d) html += '<span class="badge" style="color:#4ade80">\u2713</span>';
      html += '</div>';
    });

    document.getElementById('app').innerHTML = html;
  }

  function escapeHtml(s) {
    if(!s) return '';
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function setStatus(state) {
    var dot = document.getElementById('status-dot');
    dot.className = 'dot ' + state;
    if(state === 'connected') {
      // Keep the loading text until render() replaces it
      if(!project) {
        document.getElementById('app').innerHTML = '<div class="loading">Terhubung. Meminta data dari server\u2026</div>';
      }
    }
  }

  function showError(msg) {
    var app = document.getElementById('app');
    app.innerHTML = '<div class="error-box">' + escapeHtml(msg) + '</div><div class="loading">Mencoba menghubungkan ulang\u2026</div>';
  }

  window.__call = function() {
    var student = window.__nextPending;
    if(student) callStudent(student);
  };
  window.__reset = function() {
    var studentId = window.__activeId;
    if(studentId) resetStudent(studentId);
  };

  connect();
})();
</script>
</body>
</html>"""
