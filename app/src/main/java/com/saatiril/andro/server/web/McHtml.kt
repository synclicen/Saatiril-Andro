package com.saatiril.andro.server.web

/**
 * MC Web Page — served by the ktor server at `/mc?channel=1`.
 *
 * This is a self-contained HTML page that:
 *  1. Connects to the server via WebSocket (Engine.IO v3)
 *  2. Authenticates as MC (role=mc, channel=X)
 *  3. Receives SYNC_DB with the project database
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
.header { background:#2a164a; padding:12px 16px; display:flex; align-items:center; gap:8px; border-bottom:1px solid #533485; }
.header .dot { width:10px; height:10px; border-radius:50%; background:#fbbf24; }
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
.connect-screen { padding:20px; }
.connect-screen input { width:100%; padding:12px; background:#2a164a; border:1px solid #533485; border-radius:8px; color:#fff; font-size:16px; margin-bottom:8px; }
.connect-screen button { width:100%; padding:14px; background:#4ade80; color:#1a0b2e; border:none; border-radius:10px; font-size:16px; font-weight:bold; cursor:pointer; }
</style>
</head>
<body>
<div class="header">
  <div class="dot" id="status-dot"></div>
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

  document.getElementById('ch-label').textContent = 'Ch.' + channel;

  function connect() {
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
      // Socket.IO message
      handleSio(payload);
    } else if(type === 3) {
      // pong — ok
    } else if(type === 2) {
      // ping from server — respond with pong
      sendRaw('3');
    }
  }

  function handleSio(payload) {
    if(payload === '0') {
      // socket.io connect ack
      setStatus('authenticating');
      // Send identify
      sendEvent('identify', {role:'mc', channel:channel, password:password ? password : undefined});
      // Start ping
      pingTimer = setInterval(function(){ sendRaw('2'); }, 20000);
    } else if(payload[0] === '2') {
      // Event: 42["event",{data}]
      try {
        var arr = JSON.parse(payload.substring(1));
        var eventName = arr[0];
        var data = arr[1];
        onEvent(eventName, data);
      } catch(e) {}
    }
  }

  function onEvent(name, data) {
    if(name === 'auth-ok') {
      authenticated = true;
      setStatus('connected');
    } else if(name === 'auth-fail') {
      showError('Password salah atau ditolak server');
    } else if(name === 'lan-message') {
      // lan-message wraps: {event: 'SYNC_DB', data: {...}}
      if(data && data.event) {
        handleLanMessage(data.event, data.data);
      }
    } else if(name === 'sync-db') {
      project = data.project || data;
      render();
    }
  }

  function handleLanMessage(event, data) {
    if(event === 'SYNC_DB' || event === 'sync-db') {
      project = data.project || data;
      render();
    } else if(event === 'MC_CALL' || event === 'mc-call') {
      // Update local state
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

  function callStudent(student) {
    sendEvent('lan-message', {event:'MC_CALL', data:{student:student, channel:channel}});
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
    sendEvent('lan-message', {event:'STUDENT_RESET', data:{studentId:studentId, channel:channel}});
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
      prefix = '◆ ';
    } else if(nextPending) {
      displayName = nextPending.nama || nextPending.nim || '';
      displayNim = nextPending.nim || '';
      nameClass = 'white';
      prefix = '▶ ';
    } else {
      displayName = 'Antrean Habis';
      displayNim = '';
      nameClass = 'muted';
      prefix = '';
    }

    var btnClass = 'disabled';
    var btnText = 'HABIS';
    if(hasActive) { btnClass = 'waiting'; btnText = 'TUNGGU OPERATOR…'; }
    else if(nextPending) { btnClass = 'ready'; btnText = 'PANGGIL'; }

    // Sorted queue: active first, then pending, then done
    var queue = students.slice().sort(function(a,b){
      var pa = isActive(a.status) ? 0 : (a.status==='pending' ? 1 : 3);
      var pb = isActive(b.status) ? 0 : (b.status==='pending' ? 1 : 3);
      return pa - pb;
    });

    var html = '';
    // Name card
    html += '<div class="name-card ' + (hasActive ? 'active' : '') + '">';
    html += '<div class="label">' + (hasActive ? 'Sedang Dipanggil' : 'Berikutnya') + '</div>';
    html += '<div class="name ' + nameClass + '">' + prefix + escapeHtml(displayName) + '</div>';
    if(displayNim) html += '<div class="nim">' + escapeHtml(displayNim) + '</div>';
    html += '</div>';

    // PANGGIL button
    if(nextPending && !hasActive) {
      html += '<button class="panggil-btn ' + btnClass + '" onclick="window.__call(' + JSON.stringify(JSON.stringify(nextPending)) + ')">' + btnText + '</button>';
    } else {
      html += '<button class="panggil-btn ' + btnClass + '" disabled>' + btnText + '</button>';
    }

    // Reset button (only if active)
    if(hasActive) {
      html += '<button class="reset-btn" onclick="window.__reset(\'' + active[0].id + '\')">Reset (Ulang)</button>';
    }

    // Stats
    html += '<div class="stats">';
    html += 'Menunggu: <span>' + pending.length + '</span>';
    html += ' Selesai: <span>' + done.length + '</span>';
    html += ' Total: <span>' + students.length + '</span>';
    html += '</div>';

    // Queue
    html += '<div class="queue-title">Antrean Ch.' + channel + ' (' + pending.length + ' menunggu)</div>';
    queue.forEach(function(s, i){
      var a = isActive(s.status);
      var d = s.status === 'done';
      html += '<div class="queue-item ' + (a ? 'active' : '') + (d ? ' done' : '') + '">';
      html += '<span class="num">' + (i+1) + '</span>';
      html += '<span class="dot ' + (a ? 'active' : '') + (d ? 'done' : '') + '"></span>';
      html += '<span class="n">' + escapeHtml(s.nama || s.nim || '(tanpa nama)') + '</span>';
      if(a) html += '<span class="badge">◆</span>';
      if(d) html += '<span class="badge" style="color:#4ade80">✓</span>';
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
  }

  function showError(msg) {
    var app = document.getElementById('app');
    app.innerHTML = '<div class="error-box">' + escapeHtml(msg) + '</div><div class="loading">Mencoba menghubungkan ulang…</div>';
  }

  // Expose for onclick handlers
  window.__call = function(studentJson) {
    var student = JSON.parse(studentJson);
    callStudent(student);
  };
  window.__reset = function(studentId) {
    resetStudent(studentId);
  };

  connect();
})();
</script>
</body>
</html>"""
