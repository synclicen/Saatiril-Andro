package com.saatiril.andro.server.web

/**
 * Operator Web Page — served by the ktor server at `/operator?channel=1`.
 *
 * Adapted from Electron's operator-panel.tsx + use-palm-detection.ts.
 *
 * Features:
 *  1. Camera picker — enumerate video devices (front/back/USB capture card)
 *  2. Camera preview — video element with aspect-ratio-locked container
 *  3. Shutter modes — manual, timer-3, timer-5, timer-10, hand trigger
 *  4. Hand trigger — MediaPipe Hands (loaded from CDN, same as Electron)
 *     - Hand appears → 500ms sustain → confirmed
 *     - Hand leaves frame → trigger shutter (respects selected mode)
 *     - 5s cooldown after trigger
 *  5. Photo capture — Canvas API → crop to aspect ratio → base64 → socket
 *  6. Frame overlay — Canvas compositing (if frame URL provided)
 *  7. Real-time — receives MC_CALL, SYNC_DB, STUDENT_RESET from server
 *
 * URL format:
 *   http://192.168.1.5:3003/operator?channel=1
 *   http://192.168.1.5:3003/operator?channel=1&password=secret
 *
 * Note: MediaPipe Hands scripts are loaded from jsdelivr CDN. If the laptop
 * has no internet, hand trigger mode will fail gracefully (fall back to
 * manual/timer). All other features work on LAN only.
 */
const val OPERATOR_HTML = """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Saatiril Operator</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { background:#1a0b2e; color:#fff; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; min-height:100vh; overflow:hidden; }
.header { background:#2a164a; padding:8px 12px; display:flex; align-items:center; gap:8px; border-bottom:1px solid #533485; }
.header .dot { width:10px; height:10px; border-radius:50%; background:#fbbf24; }
.header .dot.connecting { background:#fbbf24; }
.header .dot.authenticating { background:#06b6d4; }
.header .dot.connected { background:#4ade80; }
.header .dot.disconnected { background:#ef4444; }
.header h1 { font-size:14px; color:#d4af37; }
.header .ch { font-size:11px; color:#c4b5fd; margin-left:auto; }
.header .target { font-size:12px; color:#fff; font-weight:bold; margin-left:8px; }
.header .btn-disc { background:#2a164a; border:1px solid #ef4444; color:#ef4444; padding:4px 10px; border-radius:6px; cursor:pointer; font-size:11px; }
.camera-container { position:relative; background:#000; display:flex; align-items:center; justify-content:center; width:100vw; height:calc(100vh - 50px - 90px); }
video { max-width:100%; max-height:100%; object-fit:contain; }
.canvas-hidden { display:none; }
.overlay { position:absolute; pointer-events:none; }
.overlay.tl { top:4px; left:4px; }
.overlay.tr { top:4px; right:4px; }
.overlay.bc { bottom:4px; left:50%; transform:translateX(-50%); }
.overlay.tc { top:4px; left:50%; transform:translateX(-50%); }
.status-badge { background:rgba(0,0,0,0.6); padding:4px 8px; border-radius:4px; font-size:10px; display:flex; align-items:center; gap:4px; }
.status-badge .d { width:5px; height:5px; border-radius:50%; }
.no-signal { text-align:center; color:#c4b5fd; }
.no-signal svg { width:48px; height:48px; opacity:0.5; margin-bottom:8px; }
.timer-overlay { background:rgba(0,0,0,0.7); border-radius:50px; padding:12px 30px; font-size:48px; font-weight:900; color:#d4af37; }
.hand-overlay { background:rgba(0,0,0,0.7); border-radius:16px; padding:8px 12px; font-size:11px; font-weight:bold; color:#d4af37; }
.hand-overlay.confirmed { background:rgba(74,222,128,0.8); color:#1a0b2e; }
.hand-overlay.triggered { background:rgba(212,175,55,0.8); color:#1a0b2e; }
.flash { position:absolute; inset:0; background:#fff; opacity:0; pointer-events:none; transition:opacity 0.1s; }
.flash.show { opacity:0.8; }
.controls { background:#2a164a; padding:8px; border-top:1px solid #533485; height:90px; display:flex; flex-direction:column; gap:4px; }
.modes { display:flex; gap:4px; }
.mode-btn { flex:1; padding:6px; background:#2a164a; border:1px solid #533485; border-radius:4px; color:#c4b5fd; font-size:11px; font-weight:bold; cursor:pointer; text-align:center; }
.mode-btn.active { background:#3b2263; border-color:#d4af37; color:#d4af37; }
.shutter-row { display:flex; gap:4px; align-items:center; }
.shutter-btn { flex:1; height:42px; border:none; border-radius:8px; font-size:13px; font-weight:900; cursor:pointer; }
.shutter-btn.ready { background:#d4af37; color:#1a0b2e; }
.shutter-btn.waiting { background:#533485; color:#d4af37; cursor:wait; }
.shutter-btn.disabled { background:#2a164a; color:#c4b5fd; cursor:default; }
.phase { text-align:center; font-size:10px; color:#06b6d4; font-weight:bold; }
.cam-select { position:absolute; top:4px; right:4px; background:rgba(0,0,0,0.7); color:#fff; border:1px solid #533485; border-radius:4px; padding:4px 8px; font-size:10px; cursor:pointer; }
.error-box { background:#ef4444; color:#fff; padding:8px; border-radius:6px; margin:8px; font-size:11px; }
</style>
</head>
<body>
<div class="header">
  <div class="dot connecting" id="status-dot"></div>
  <h1>OPERATOR</h1>
  <span class="ch" id="ch-label">Ch.1</span>
  <span class="target" id="target-name">Menunggu…</span>
  <button class="btn-disc" onclick="window.__disconnect()">✕</button>
</div>
<div class="camera-container" id="cam-container">
  <video id="video" autoplay playsinline muted></video>
  <canvas id="canvas" class="canvas-hidden"></canvas>
  <div class="flash" id="flash"></div>
  <div class="overlay tl">
    <div class="status-badge"><div class="d" id="cam-dot" style="background:#ef4444"></div><span id="cam-label">No camera</span></div>
  </div>
  <select class="cam-select" id="cam-select" onchange="window.__switchCam(this.value)"></select>
  <div class="overlay tc" id="timer-overlay" style="display:none"><div class="timer-overlay" id="timer-text">3</div></div>
  <div class="overlay tc" id="hand-overlay" style="display:none"><div class="hand-overlay" id="hand-text">Tangan terdeteksi…</div></div>
  <div class="overlay bc" id="no-signal" style="display:none">
    <div class="no-signal">
      <svg viewBox="0 0 24 24" fill="currentColor"><path d="M21 6.5l-3.5 3.5c.83 1.34.83 3.16 0 4.5l3.5 3.5V6.5zM17 10.5l-9 9h9v-9zM3 3l18 18-1.41 1.41L18 18.41V21H6v-3.59l-1.59 1.59L3 18l9-9L3 3z"/></svg>
      <div>NO CAMERA SIGNAL</div>
      <div style="font-size:9px;opacity:0.5">Pilih kamera atau cek USB</div>
    </div>
  </div>
</div>
<div class="controls">
  <div class="phase" id="phase-label">Standby</div>
  <div class="modes">
    <div class="mode-btn active" data-mode="manual" onclick="window.__setMode('manual')">M</div>
    <div class="mode-btn" data-mode="timer-3" onclick="window.__setMode('timer-3')">3s</div>
    <div class="mode-btn" data-mode="timer-5" onclick="window.__setMode('timer-5')">5s</div>
    <div class="mode-btn" data-mode="timer-10" onclick="window.__setMode('timer-10')">10s</div>
    <div class="mode-btn" data-mode="hand" onclick="window.__setMode('hand')">✋</div>
  </div>
  <div class="shutter-row">
    <button class="shutter-btn disabled" id="shutter-btn" onclick="window.__shutter()" disabled>STANDBY</button>
  </div>
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
  var currentTarget = null;
  var capturePhase = 'standby';
  var capturedPhotos = [];
  var isCapturing = false;
  var shutterMode = 'manual';
  var timerCountdown = 0;
  var timerInterval = null;
  var videoStream = null;
  var currentDeviceId = null;
  var pingTimer = null;
  var passwordHash = null;
  var frameImg = null;
  var frameDataUrl = null;
  var aspectRatio = 4/3;
  var photosPerSession = 2;
  var isPhotoshoot = false;

  var handsModel = null;
  var handDetecting = false;
  var handAnimFrame = null;
  var handVisibleSince = 0;
  var handConfirmed = false;
  var handTriggerFired = false;
  var lastTriggerTime = 0;
  var HAND_CONFIRM_SUSTAIN_MS = 500;
  var TRIGGER_COOLDOWN_MS = 5000;
  var palmScriptsLoaded = false;

  document.getElementById('ch-label').textContent = 'Ch.' + channel;

  async function sha256(message) {
    if (window.crypto && window.crypto.subtle) {
      try {
        var data = new TextEncoder().encode(message);
        var hashBuffer = await window.crypto.subtle.digest('SHA-256', data);
        var hashArray = Array.from(new Uint8Array(hashBuffer));
        return hashArray.map(function(b){ return b.toString(16).padStart(2,'0'); }).join('');
      } catch(e) {}
    }
    return message;
  }

  async function connect() {
    if (password && !passwordHash) {
      passwordHash = await sha256(password);
    }
    var wsUrl = 'ws://' + location.host + '/?EIO=3&transport=websocket';
    try { ws = new WebSocket(wsUrl); }
    catch(e) { setTimeout(connect, 3000); return; }
    ws.onmessage = function(ev){ onMessage(ev.data); };
    ws.onclose = function(){ connected=false; authenticated=false; setStatus('disconnected'); setTimeout(connect, 2000); };
    ws.onerror = function(){};
  }

  function onMessage(raw) {
    if(raw.length < 1) return;
    var type = parseInt(raw[0]);
    var payload = raw.substring(1);
    if(type === 0) { connected=true; setStatus('connecting'); sendRaw('40'); }
    else if(type === 4) handleSio(payload);
    else if(type === 2) sendRaw('3');
  }

  function handleSio(payload) {
    if(payload === '0') {
      setStatus('authenticating');
      var data = {role:'operator', channel:channel};
      if(passwordHash) data.sessionPasswordHash = passwordHash;
      sendEvent('identify', data);
      if(pingTimer) clearInterval(pingTimer);
      pingTimer = setInterval(function(){ sendRaw('2'); }, 20000);
    } else if(payload[0] === '2') {
      try {
        var arr = JSON.parse(payload.substring(1));
        onEvent(arr[0], arr[1]);
      } catch(e) {}
    }
  }

  function onEvent(name, data) {
    if(name === 'auth-success') {
      authenticated = true;
      setStatus('connected');
      sendLanMessage('REQUEST_STATE', {role:'operator', channel:channel});
    } else if(name === 'auth-failed') {
      showError('Password salah');
    } else if(name === 'lan-message') {
      if(data && data.event) handleLanMessage(data.event, data.data);
    }
  }

  function handleLanMessage(event, data) {
    if(event === 'SYNC_DB') {
      project = data.project || data;
      if(project && project.config) {
        aspectRatio = parseRatio(project.config.ratio);
        isPhotoshoot = (project.config.mode || '').indexOf('photoshoot') >= 0;
        photosPerSession = isPhotoshoot ? 1 : 2;
        if(project.config.frame && project.config.frame !== '__FRAME_SAVED__' && !frameDataUrl) {
          frameDataUrl = project.config.frame;
          frameImg = new Image();
          frameImg.onload = function(){};
          frameImg.src = frameDataUrl;
        }
      }
      updateTargetFromDb();
    } else if(event === 'MC_CALL') {
      if(data && data.student) {
        currentTarget = data.student;
        capturePhase = 'ready-1';
        capturedPhotos = [];
        renderTarget();
      }
    } else if(event === 'STUDENT_RESET') {
      if(currentTarget && data && data.studentId === currentTarget.id) {
        currentTarget = null;
        capturePhase = 'standby';
        capturedPhotos = [];
        renderTarget();
      }
    } else if(event === 'PHOTOS_SAVED') {
      if(currentTarget && data && data.student && data.student.id === currentTarget.id) {
        currentTarget = null;
        capturePhase = 'standby';
        capturedPhotos = [];
        renderTarget();
      }
    } else if(event === 'STUDENT_DONE') {
      if(currentTarget && data && data.studentId === currentTarget.id) {
        currentTarget = null;
        capturePhase = 'standby';
        capturedPhotos = [];
        renderTarget();
      }
    }
  }

  function updateTargetFromDb() {
    if(!project || !project.database) return;
    var db = project.database;
    var found = db.find(function(s){ return s.status && s.status.indexOf('active') === 0; });
    if(found && (!currentTarget || currentTarget.id !== found.id)) {
      currentTarget = found;
      capturePhase = 'ready-1';
      capturedPhotos = [];
    }
    renderTarget();
  }

  function parseRatio(r) {
    if(!r) return 4/3;
    var parts = r.split(':');
    if(parts.length === 2) {
      var w = parseFloat(parts[0]);
      var h = parseFloat(parts[1]);
      if(w > 0 && h > 0) return w/h;
    }
    return 4/3;
  }

  function sendRaw(msg) { if(ws && ws.readyState === 1) ws.send(msg); }
  function sendEvent(name, data) { sendRaw('42' + JSON.stringify([name, data])); }
  function sendLanMessage(event, data) { sendEvent('lan-message', {event:event, data:data}); }

  async function startCamera(deviceId) {
    try {
      if(videoStream) videoStream.getTracks().forEach(function(t){ t.stop(); });
      var constraints = { video: { width: {ideal:1920}, height:{ideal:1080} }, audio: false };
      if(deviceId) constraints.video.deviceId = { exact: deviceId };
      videoStream = await navigator.mediaDevices.getUserMedia(constraints);
      var video = document.getElementById('video');
      video.srcObject = videoStream;
      currentDeviceId = deviceId || (videoStream.getVideoTracks()[0] && videoStream.getVideoTracks()[0].getSettings().deviceId);
      document.getElementById('cam-dot').style.background = '#4ade80';
      document.getElementById('cam-label').textContent = 'Connected';
      document.getElementById('no-signal').style.display = 'none';
      enumerateCameras();
      return true;
    } catch(e) {
      document.getElementById('cam-dot').style.background = '#ef4444';
      document.getElementById('cam-label').textContent = 'No camera';
      document.getElementById('no-signal').style.display = 'block';
      return false;
    }
  }

  async function enumerateCameras() {
    try {
      var devices = await navigator.mediaDevices.enumerateDevices();
      var videos = devices.filter(function(d){ return d.kind === 'videoinput'; });
      var select = document.getElementById('cam-select');
      select.innerHTML = '';
      videos.forEach(function(d, i){
        var opt = document.createElement('option');
        opt.value = d.deviceId;
        opt.textContent = d.label || ('Camera ' + (i+1));
        if(d.deviceId === currentDeviceId) opt.selected = true;
        select.appendChild(opt);
      });
    } catch(e) {}
  }

  window.__switchCam = function(deviceId) { startCamera(deviceId); };

  function handleCapture() {
    if(!currentTarget || isCapturing) return;
    if(capturePhase !== 'ready-1' && capturePhase !== 'ready-2') return;
    isCapturing = true;

    var video = document.getElementById('video');
    var canvas = document.getElementById('canvas');
    var targetWidth = 1920;
    var targetHeight = Math.round(targetWidth / aspectRatio);
    canvas.width = targetWidth;
    canvas.height = targetHeight;
    var ctx = canvas.getContext('2d');
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, targetWidth, targetHeight);

    if(video && video.readyState >= 2) {
      var vw = video.videoWidth, vh = video.videoHeight;
      var vRatio = vw / vh;
      var sx=0, sy=0, sw=vw, sh=vh;
      if(vRatio > aspectRatio) { sw = vh * aspectRatio; sx = (vw - sw)/2; }
      else { sh = vw / aspectRatio; sy = (vh - sh)/2; }
      ctx.drawImage(video, sx, sy, sw, sh, 0, 0, targetWidth, targetHeight);
    }

    if(frameImg && frameImg.complete && frameImg.naturalWidth > 0) {
      ctx.drawImage(frameImg, 0, 0, targetWidth, targetHeight);
    }

    var dataUrl = canvas.toDataURL('image/jpeg', 0.95);
    capturedPhotos.push(dataUrl);

    var flash = document.getElementById('flash');
    flash.classList.add('show');
    setTimeout(function(){ flash.classList.remove('show'); }, 200);

    if(capturedPhotos.length >= photosPerSession) {
      capturePhase = 'sending';
      sendPhotos();
    } else {
      capturePhase = 'ready-2';
      isCapturing = false;
    }
    renderTarget();
  }

  function sendPhotos() {
    if(!currentTarget) return;
    var filename = buildFilename(currentTarget, capturedPhotos.length, 1);
    sendLanMessage('PHOTOS_SAVED', {
      student: currentTarget,
      photos: capturedPhotos,
      channel: channel,
      version: 1,
      filename: filename
    });
    capturedPhotos = [];
    capturePhase = 'standby';
    isCapturing = false;
    renderTarget();
  }

  function buildFilename(student, photoCount, version) {
    var nim = (student.nim || '').replace(/[^a-zA-Z0-9_-]/g, '');
    var nama = (student.nama || '').replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_]/g, '');
    if(isPhotoshoot) {
      var base = nim + '_' + nama;
      var withCh = channel > 1 ? base + '_Ch' + channel : base;
      return (version > 1 ? withCh + '_v' + version : withCh) + '.jpg';
    } else {
      var results = [];
      for(var i = 0; i < photoCount; i++) {
        var type = i === 0 ? 'Toga' : 'Ijazah';
        var b = nim + '_' + nama + '_' + (i+1) + '_' + type;
        results.push((version > 1 ? b + '_v' + version : b) + '.jpg');
      }
      return results[0] || '';
    }
  }

  function handleShutterClick() {
    if(!currentTarget || isCapturing) return;
    if(shutterMode === 'hand') return;
    if(shutterMode === 'manual') {
      handleCapture();
    } else {
      if(timerInterval) {
        clearInterval(timerInterval);
        timerInterval = null;
        timerCountdown = 0;
        document.getElementById('timer-overlay').style.display = 'none';
      } else {
        var duration = parseInt(shutterMode.split('-')[1]);
        timerCountdown = duration;
        document.getElementById('timer-overlay').style.display = 'block';
        document.getElementById('timer-text').textContent = duration;
        timerInterval = setInterval(function(){
          timerCountdown--;
          if(timerCountdown <= 0) {
            clearInterval(timerInterval);
            timerInterval = null;
            timerCountdown = 0;
            document.getElementById('timer-overlay').style.display = 'none';
            handleCapture();
          } else {
            document.getElementById('timer-text').textContent = timerCountdown;
          }
        }, 1000);
      }
    }
  }

  window.__shutter = handleShutterClick;
  window.__setMode = function(mode) {
    shutterMode = mode;
    document.querySelectorAll('.mode-btn').forEach(function(el){
      el.classList.toggle('active', el.dataset.mode === mode);
    });
    if(mode === 'hand') {
      initHandTrigger();
    } else {
      stopHandTrigger();
    }
  };
  window.__disconnect = function() {
    if(videoStream) videoStream.getTracks().forEach(function(t){ t.stop(); });
    stopHandTrigger();
    sendRaw('41');
    try { ws.close(); } catch(e) {}
    window.close();
  };

  function loadScript(src) {
    return new Promise(function(resolve, reject){
      var existing = document.querySelector('script[src="' + src + '"]');
      if(existing) { resolve(); return; }
      var s = document.createElement('script');
      s.src = src;
      s.async = true;
      s.onload = function(){ resolve(); };
      s.onerror = function(){ reject(new Error('Failed: ' + src)); };
      document.head.appendChild(s);
    });
  }

  async function loadPalmScripts() {
    if(palmScriptsLoaded) return true;
    try {
      await loadScript('https://cdn.jsdelivr.net/npm/@mediapipe/hands@0.4/hands.js');
      await new Promise(function(r){ setTimeout(r, 100); });
      if(typeof window.Hands === 'undefined') throw new Error('MediaPipe Hands global missing');
      palmScriptsLoaded = true;
      return true;
    } catch(e) {
      console.error('[Hand] Script load failed:', e.message);
      return false;
    }
  }

  async function initHandTrigger() {
    if(handsModel) { startHandDetection(); return; }
    var ok = await loadPalmScripts();
    if(!ok) {
      window.__setMode('manual');
      showError('Hand trigger butuh internet untuk load MediaPipe. Gunakan mode manual/timer.');
      setTimeout(function(){ var eb = document.querySelector('.error-box'); if(eb) eb.remove(); }, 5000);
      return;
    }
    try {
      handsModel = new window.Hands({
        locateFile: function(file){ return 'https://cdn.jsdelivr.net/npm/@mediapipe/hands@0.4/' + file; }
      });
      handsModel.setOptions({
        maxNumHands: 1,
        modelComplexity: 1,
        minDetectionConfidence: 0.3,
        minTrackingConfidence: 0.3
      });
      handsModel.onResults(processHandResults);
      var tc = document.createElement('canvas');
      tc.width = 1; tc.height = 1;
      await handsModel.send({ image: tc });
      startHandDetection();
    } catch(e) {
      console.error('[Hand] Init failed:', e);
      window.__setMode('manual');
    }
  }

  function startHandDetection() {
    handDetecting = true;
    handVisibleSince = 0;
    handConfirmed = false;
    handTriggerFired = false;
    detectHandFrame();
  }

  function stopHandTrigger() {
    handDetecting = false;
    if(handAnimFrame) cancelAnimationFrame(handAnimFrame);
    handAnimFrame = null;
    document.getElementById('hand-overlay').style.display = 'none';
  }

  async function detectHandFrame() {
    if(!handDetecting || !handsModel) return;
    var video = document.getElementById('video');
    try {
      await handsModel.send({ image: video });
    } catch(e) {}
    if(handDetecting) handAnimFrame = requestAnimationFrame(detectHandFrame);
  }

  function processHandResults(results) {
    if(!handDetecting) return;
    var hands = results.multiHandLandmarks || [];
    var now = Date.now();
    var handDetected = hands.length > 0;

    if(lastTriggerTime > 0 && now - lastTriggerTime < TRIGGER_COOLDOWN_MS) {
      showHandStatus('triggered', 'Cooldown…');
      return;
    }

    if(handDetected) {
      if(handVisibleSince === 0) {
        handVisibleSince = now;
        handConfirmed = false;
        handTriggerFired = false;
        showHandStatus('detected', 'Tangan terdeteksi…');
      } else if(!handConfirmed && now - handVisibleSince >= HAND_CONFIRM_SUSTAIN_MS) {
        handConfirmed = true;
        showHandStatus('confirmed', 'Tangan ✓ — lepaskan untuk foto');
      }
    } else {
      if(handConfirmed && !handTriggerFired) {
        handTriggerFired = true;
        lastTriggerTime = now;
        showHandStatus('triggered', 'Trigger!');
        handleShutterClick();
      } else {
        document.getElementById('hand-overlay').style.display = 'none';
      }
      handVisibleSince = 0;
      handConfirmed = false;
    }
  }

  function showHandStatus(state, text) {
    var overlay = document.getElementById('hand-overlay');
    var el = document.getElementById('hand-text');
    overlay.style.display = 'block';
    el.textContent = text;
    el.className = 'hand-overlay';
    if(state === 'confirmed') el.className = 'hand-overlay confirmed';
    if(state === 'triggered') el.className = 'hand-overlay triggered';
  }

  function renderTarget() {
    var nameEl = document.getElementById('target-name');
    var phaseEl = document.getElementById('phase-label');
    var btn = document.getElementById('shutter-btn');

    if(!currentTarget) {
      nameEl.textContent = 'Menunggu MC…';
      phaseEl.textContent = 'Standby';
      btn.textContent = 'STANDBY';
      btn.className = 'shutter-btn disabled';
      btn.disabled = true;
      return;
    }
    nameEl.textContent = currentTarget.nama || currentTarget.nim;
    var phaseText = '';
    if(capturePhase === 'ready-1') phaseText = isPhotoshoot ? 'Siap Foto' : 'Pose 1 — Toga';
    else if(capturePhase === 'ready-2') phaseText = 'Pose 2 — Ijazah';
    else if(capturePhase === 'sending') phaseText = 'Mengirim…';
    else phaseText = 'Standby';
    phaseEl.textContent = phaseText;

    if(capturePhase === 'sending') {
      btn.textContent = 'Mengirim…';
      btn.className = 'shutter-btn waiting';
      btn.disabled = true;
    } else if(capturePhase === 'ready-1' || capturePhase === 'ready-2') {
      var label = capturePhase === 'ready-2' ? 'Ijazah' : (isPhotoshoot ? 'Foto' : 'Toga');
      btn.textContent = label;
      btn.className = 'shutter-btn ready';
      btn.disabled = false;
    } else {
      btn.textContent = 'STANDBY';
      btn.className = 'shutter-btn disabled';
      btn.disabled = true;
    }
  }

  function setStatus(state) {
    document.getElementById('status-dot').className = 'dot ' + state;
  }

  function showError(msg) {
    var div = document.createElement('div');
    div.className = 'error-box';
    div.textContent = msg;
    document.body.insertBefore(div, document.body.firstChild);
  }

  if(navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    navigator.mediaDevices.addEventListener('devicechange', enumerateCameras);
    startCamera(null);
  } else {
    showError('Browser tidak mendukung akses kamera. Gunakan Chrome/Firefox terbaru.');
  }
  connect();
})();
</script>
</body>
</html>"""
