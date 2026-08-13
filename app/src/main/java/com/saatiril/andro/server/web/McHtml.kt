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
  var socketPort = params.get('socketPort') || '';
  var ws = null;
  var connected = false;
  var authenticated = false;
  var project = null;
  var pingTimer = null;
  var passwordHash = null;

  document.getElementById('ch-label').textContent = 'Ch.' + channel;

  // ── SHA-256 implementation (async, crypto.subtle + correct fallback) ──
  // IMPORTANT: crypto.subtle is ONLY available in secure contexts (HTTPS / localhost).
  // On HTTP LAN (e.g. http://192.168.100.61:3003/mc) crypto.subtle is UNDEFINED,
  // so we MUST have a correct pure-JS fallback. The previous "prime-sieve" fallback
  // here was BROKEN — it returned wrong hashes for every input, so MC could never
  // auth when a session password was set. This correct implementation uses the
  // standard SHA-256 K constants and matches java.security.MessageDigest exactly.
  function rotR(x, n) { return (x >>> n) | (x << (32 - n)); }

  function sha256Fallback(data) {
    var K = new Uint32Array([
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
      0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
      0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ]);
    var msgLen = data.length;
    var bitLen = msgLen * 8;
    var paddedLen = msgLen + 1;
    while (paddedLen % 64 !== 56) paddedLen++;
    paddedLen += 8;
    var padded = new Uint8Array(paddedLen);
    padded.set(data);
    padded[msgLen] = 0x80;
    var view = new DataView(padded.buffer);
    view.setUint32(paddedLen - 8, 0, false);
    view.setUint32(paddedLen - 4, bitLen, false);
    var h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
    var h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
    for (var offset = 0; offset < paddedLen; offset += 64) {
      var w = new Uint32Array(64);
      for (var i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4, false);
      for (var i2 = 16; i2 < 64; i2++) {
        var s0 = rotR(w[i2 - 15], 7) ^ rotR(w[i2 - 15], 18) ^ (w[i2 - 15] >>> 3);
        var s1 = rotR(w[i2 - 2], 17) ^ rotR(w[i2 - 2], 19) ^ (w[i2 - 2] >>> 10);
        w[i2] = (w[i2 - 16] + s0 + w[i2 - 7] + s1) | 0;
      }
      var a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
      for (var j = 0; j < 64; j++) {
        var S1 = rotR(e, 6) ^ rotR(e, 11) ^ rotR(e, 25);
        var ch = (e & f) ^ (~e & g);
        var temp1 = (h + S1 + ch + K[j] + w[j]) | 0;
        var S0 = rotR(a, 2) ^ rotR(a, 13) ^ rotR(a, 22);
        var maj = (a & b) ^ (a & c) ^ (b & c);
        var temp2 = (S0 + maj) | 0;
        h = g; g = f; f = e; e = (d + temp1) | 0;
        d = c; c = b; b = a; a = (temp1 + temp2) | 0;
      }
      h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
      h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
    }
    function hex(x) { return (x >>> 0).toString(16).padStart(8, '0'); }
    return hex(h0) + hex(h1) + hex(h2) + hex(h3) + hex(h4) + hex(h5) + hex(h6) + hex(h7);
  }

  async function sha256(message) {
    if (window.crypto && window.crypto.subtle) {
      try {
        var data = new TextEncoder().encode(message);
        var hashBuffer = await window.crypto.subtle.digest('SHA-256', data);
        var hashArray = Array.from(new Uint8Array(hashBuffer));
        return hashArray.map(function(b){ return b.toString(16).padStart(2,'0'); }).join('');
      } catch(e) { /* fall through to pure-JS */ }
    }
    return sha256Fallback(new TextEncoder().encode(message));
  }

  async function connect() {
    // Hash password before connecting
    if (password && !passwordHash) {
      passwordHash = await sha256(password);
    }

    // Respect ?socketPort= if provided (so the page can be served from any HTTP
    // server while the WebSocket connects to the actual saatiril server).
    // Default: same host:port as the page (Android ktor serves both on one port).
    var wsHost = location.hostname;
    var wsPort = socketPort || location.port;
    var wsUrl = 'ws://' + wsHost + ':' + wsPort + '/?EIO=3&transport=websocket';
    console.log('[MC] connecting to ' + wsUrl);
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
      console.warn('[MC] auth-failed', data);
      // Clear the bad hash so the user can re-enter the password
      var hadHash = !!passwordHash;
      passwordHash = null;
      password = '';
      showPasswordPrompt(hadHash ? (data && data.reason) : null);
    } else if(name === 'auth-requirement') {
      if(data && data.passwordRequired && !passwordHash) {
        showPasswordPrompt(null);
      }
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

  // Password prompt — shown when the server requires a session password and
  // we don't have a valid hash yet. Lets the user type the password and retry
  // without reloading the page.
  function showPasswordPrompt(reason) {
    var errLine = '';
    if (reason === 'session_password_required') {
      errLine = '<div style="color:#fbbf24;font-size:12px;margin-bottom:8px">Password salah, coba lagi.</div>';
    }
    var app = document.getElementById('app');
    app.innerHTML =
      '<div style="text-align:center;padding:40px 20px">' +
      '<div style="font-size:48px;margin-bottom:16px">\uD83D\uDD11</div>' +
      '<div style="color:#fff;font-size:18px;margin-bottom:8px">Password Sesi Diperlukan</div>' +
      '<div style="color:#c4b5fd;font-size:13px;margin-bottom:16px">Masukkan password sesi dari admin</div>' +
      errLine +
      '<input type="password" id="pwd-input" style="width:100%;max-width:300px;padding:12px;border-radius:8px;border:1px solid #533485;background:#2a164a;color:#fff;font-size:16px;text-align:center;margin-bottom:12px" placeholder="Password" autocomplete="current-password" />' +
      '<button id="pwd-btn" style="width:100%;max-width:300px;height:44px;border:none;border-radius:8px;background:#d4af37;color:#1a0b2e;font-size:14px;font-weight:900;cursor:pointer">CONNECT</button>' +
      '</div>';
    var input = document.getElementById('pwd-input');
    var btn = document.getElementById('pwd-btn');
    if (input) {
      input.focus();
      input.addEventListener('keypress', function(e){ if(e.key === 'Enter') submitPwd(); });
    }
    if (btn) btn.addEventListener('click', submitPwd);
  }

  async function submitPwd() {
    var input = document.getElementById('pwd-input');
    var btn = document.getElementById('pwd-btn');
    if (!input || !input.value) return;
    password = input.value;
    if (btn) { btn.disabled = true; btn.textContent = 'Memverifikasi...'; }
    try {
      passwordHash = await sha256(password);
      console.log('[MC] submitPwd hashed: ' + (passwordHash || '').slice(0, 8) + '...');
    } catch(e) {
      console.error('[MC] submitPwd sha256 failed:', e);
      if (btn) { btn.disabled = false; btn.textContent = 'CONNECT'; }
      return;
    }
    // Re-send identify on the existing WS connection (no need to tear down).
    if (ws && ws.readyState === 1) {
      document.getElementById('app').innerHTML = '<div class="loading">Memverifikasi password\u2026</div>';
      var identifyData = {role:'mc', channel:channel};
      if (passwordHash) identifyData.sessionPasswordHash = passwordHash;
      sendEvent('identify', identifyData);
      setTimeout(function(){ if(btn){ btn.disabled = false; btn.textContent = 'CONNECT'; } }, 3000);
    } else {
      // WS not connected — start fresh
      document.getElementById('app').innerHTML = '<div class="loading">Menghubungkan dengan password\u2026</div>';
      connect();
    }
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
