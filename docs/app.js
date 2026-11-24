/* Personal CCTV — fixed web viewer (MQTT over WebSocket) */
(function () {
  'use strict';

  var BROKERS = [
    'wss://broker.emqx.io:8084/mqtt',
    'wss://broker.hivemq.com:8884/mqtt'
  ];
  var BASE = 'homecamlive/';

  var $ = function (id) { return document.getElementById(id); };
  var roomInput = $('roomInput');
  var connPill = $('connPill');
  var brokerText = $('brokerText');
  var fpsText = $('fpsText');
  var eventText = $('eventText');
  var logBox = $('log');
  var placeholder = $('placeholder');
  var stageWrap = $('stageWrap');
  var stage = $('stage');
  var feed = $('feed');
  var dualPane = $('dualPane');
  var feedBack = $('feedBack');
  var feedFront = $('feedFront');
  var stageRoom = $('stageRoom');
  var stageClock = $('stageClock');
  var recBadge = $('recBadge');
  var btnExitFull = $('btnExitFull');
  var btnRecord = $('btnRecord');
  var recVal = $('recVal');
  var micVal = $('micVal');
  var camMicVal = $('camMicVal');
  var camVal = $('camVal');
  var splitVal = $('splitVal');

  var client = null;
  var room = '';
  var brokerIndex = 0;
  var connectedOnce = false;
  var reconnectTimer = null;

  var frameCount = 0;
  var fpsTimer = null;
  var lastFrameBlob = null;

  var audioCtx = null;
  var speakerGain = null;
  var recordGain = null;
  var audioDest = null;
  var nextAudioTime = 0;
  var soundOn = true;
  var camMicOn = true;
  var camFacing = 'BACK';
  var dualOn = false;

  var webRecorder = null;
  var recordChunks = [];
  var recording = false;

  /* ---------- UI helpers ---------- */

  function log(msg, hot) {
    var d = document.createElement('div');
    d.textContent = new Date().toLocaleTimeString() + '  ' + msg;
    if (hot) d.className = 'hot';
    logBox.insertBefore(d, logBox.firstChild);
    while (logBox.children.length > 40) logBox.removeChild(logBox.lastChild);
    eventText.textContent = msg;
  }

  function setConn(state, text) {
    connPill.textContent = text || state;
    connPill.className = 'pill pill-' + state.toLowerCase();
  }

  function tickClock() {
    stageClock.textContent = new Date().toLocaleString();
  }

  function startFpsCounter() {
    if (fpsTimer) return;
    fpsTimer = setInterval(function () {
      fpsText.textContent = frameCount;
      frameCount = 0;
    }, 1000);
  }

  /* ---------- Audio ---------- */

  function initAudio() {
    if (audioCtx) return;
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) { log('AudioContext unsupported', true); return; }
    audioCtx = new AC();

    speakerGain = audioCtx.createGain();
    speakerGain.gain.value = soundOn ? 1 : 0;
    speakerGain.connect(audioCtx.destination);

    recordGain = audioCtx.createGain();
    recordGain.gain.value = 1;
    audioDest = audioCtx.createMediaStreamDestination();
    recordGain.connect(audioDest);

    nextAudioTime = audioCtx.currentTime + 0.05;
    log('Audio pipeline ready');
  }

  function feedAudio(bytes) {
    if (!audioCtx || !bytes || bytes.length < 2) return;
    if (audioCtx.state === 'suspended') audioCtx.resume().catch(function () {});

    var count = Math.floor(bytes.byteLength / 2);
    var buf = audioCtx.createBuffer(1, count, 16000);
    var out = buf.getChannelData(0);
    var dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    for (var i = 0; i < count; i++) {
      out[i] = dv.getInt16(i * 2, true) / 32768;
    }

    var src = audioCtx.createBufferSource();
    src.buffer = buf;
    src.connect(speakerGain);
    src.connect(recordGain);

    var when = Math.max(audioCtx.currentTime + 0.01, nextAudioTime);
    try { src.start(when); } catch (e) { src.start(); }
    nextAudioTime = when + buf.duration;
  }

  /* ---------- Frames ---------- */

  function drawJpeg(canvas, bytes) {
    var blob = new Blob([bytes], { type: 'image/jpeg' });
    if (canvas === feed) lastFrameBlob = blob;

    var done = function (img, w, h) {
      if (canvas.width !== w) canvas.width = w;
      if (canvas.height !== h) canvas.height = h;
      var ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, w, h);
      frameCount++;
      placeholder.hidden = true;
      if (img.close) img.close();
    };

    if (window.createImageBitmap) {
      createImageBitmap(blob).then(function (bmp) { done(bmp, bmp.width, bmp.height); })
        .catch(function () { fallbackImage(blob, canvas, done); });
    } else {
      fallbackImage(blob, canvas, done);
    }
  }

  function fallbackImage(blob, canvas, done) {
    var url = URL.createObjectURL(blob);
    var img = new Image();
    img.onload = function () { done(img, img.naturalWidth, img.naturalHeight); URL.revokeObjectURL(url); };
    img.onerror = function () { URL.revokeObjectURL(url); };
    img.src = url;
  }

  function applyDualUi() {
    dualPane.hidden = !dualOn;
    feed.hidden = dualOn;
    splitVal.textContent = dualOn ? 'DUAL' : 'SINGLE';
  }

  function applyRecordingUi() {
    recVal.textContent = recording ? 'STOP' : 'START';
    btnRecord.classList.toggle('recording', recording);
    recBadge.hidden = !recording;
  }

  function handleStatus(cmd) {
    log('STATUS ' + cmd, true);
    if (cmd === 'CAM_FRONT') camFacing = 'FRONT';
    else if (cmd === 'CAM_BACK') camFacing = 'BACK';
    else if (cmd === 'MIC_ON') camMicOn = true;
    else if (cmd === 'MIC_OFF') camMicOn = false;
    else if (cmd === 'DUAL_ON') dualOn = true;
    else if (cmd === 'DUAL_OFF') dualOn = false;
    else if (cmd === 'DUAL_NO' || cmd === 'DUAL_FAIL') {
      dualOn = false;
      log(cmd === 'DUAL_NO' ? 'Phone does not support dual camera' : 'Second camera failed', true);
    } else if (cmd === 'REC_ON') { recording = true; }
    else if (cmd === 'REC_OFF') { recording = false; }

    camVal.textContent = camFacing;
    camMicVal.textContent = camMicOn ? 'ON' : 'OFF';
    applyDualUi();
    applyRecordingUi();
  }

  /* ---------- MQTT ---------- */

  function publish(cmd) {
    if (!client || !client.connected) { log('Not connected', true); return; }
    client.publish(BASE + room + '/control', cmd, { qos: 0 });
    log('SEND ' + cmd);
  }

  function connectBroker(index) {
    if (client) { try { client.end(true); } catch (e) {} }
    brokerIndex = index;
    var url = BROKERS[index];
    brokerText.textContent = url;
    setConn('off', 'CONNECTING');
    log('Connecting to ' + url);

    var cid = 'hcl-web-' + Math.random().toString(16).slice(2, 10);
    client = mqtt.connect(url, {
      clientId: cid,
      clean: true,
      keepalive: 30,
      connectTimeout: 8000,
      reconnectPeriod: 0
    });

    client.on('connect', function () {
      connectedOnce = true;
      setConn('on', 'ONLINE');
      log('Connected — subscribing Room ' + room, true);
      client.subscribe(BASE + room + '/#', { qos: 0 }, function (err) {
        if (err) log('Subscribe failed: ' + err.message, true);
      });
    });

    client.on('reconnect', function () { setConn('off', 'RECONNECTING'); });

    client.on('message', function (topic, payload) {
      if (topic.indexOf(BASE + room + '/') !== 0) return;
      if (topic.endsWith('/frame') || topic.endsWith('/frame_back')) {
        drawJpeg(feedBack, payload);
        if (!dualOn) drawJpeg(feed, payload);
      } else if (topic.endsWith('/frame_front')) {
        drawJpeg(feedFront, payload);
        if (dualOn) { /* dual panes already show both */ }
      } else if (topic.endsWith('/audio')) {
        if (soundOn) feedAudio(payload);
      } else if (topic.endsWith('/status')) {
        handleStatus(new TextDecoder().decode(payload).trim());
      }
    });

    client.on('error', function (err) {
      log('Relay error: ' + err.message, true);
      if (!connectedOnce && index + 1 < BROKERS.length) {
        setTimeout(function () { connectBroker(index + 1); }, 300);
      }
    });

    client.on('close', function () {
      if (connectedOnce) {
        setConn('off', 'RECONNECTING');
        clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(function () { connectBroker(brokerIndex); }, 3000);
      }
    });
  }

  function connect() {
    room = roomInput.value.trim().toUpperCase();
    if (!/^[A-Z0-9_-]{3,32}$/.test(room)) {
      log('Room ID: 3–32 letters / numbers / - / _', true);
      roomInput.focus();
      return;
    }
    localStorage.setItem('hcl_room', room);
    stageRoom.textContent = 'ROOM ' + room;
    initAudio();
    if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume().catch(function () {});
    connectedOnce = false;
    connectBroker(0);
  }

  function disconnect() {
    clearTimeout(reconnectTimer);
    if (client) { try { client.end(true); } catch (e) {} }
    client = null;
    connectedOnce = false;
    setConn('off', 'OFFLINE');
    brokerText.textContent = '—';
    log('Disconnected');
  }

  /* ---------- Web recording ---------- */

  function startWebRecord() {
    if (!lastFrameBlob) { log('No frame yet — wait for live view', true); return; }
    if (!window.MediaRecorder) { log('MediaRecorder unsupported in this browser', true); return; }
    initAudio();
    if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume().catch(function () {});

    var canvasStream;
    try { canvasStream = feed.captureStream(15); }
    catch (e) { log('captureStream failed: ' + e.message, true); return; }

    var tracks = canvasStream.getVideoTracks().slice();
    if (audioDest) tracks = tracks.concat(audioDest.stream.getAudioTracks());
    var mixed = new MediaStream(tracks);

    var types = [
      'video/webm;codecs=vp8,opus',
      'video/webm;codecs=vp9,opus',
      'video/webm;codecs=vp8',
      'video/webm'
    ];
    var mime = '';
    for (var i = 0; i < types.length; i++) {
      if (MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(types[i])) { mime = types[i]; break; }
    }

    try {
      webRecorder = mime ? new MediaRecorder(mixed, { mimeType: mime }) : new MediaRecorder(mixed);
    } catch (e) { log('Recorder init failed: ' + e.message, true); return; }

    recordChunks = [];
    webRecorder.ondataavailable = function (e) { if (e.data && e.data.size) recordChunks.push(e.data); };
    webRecorder.onstop = function () {
      var type = mime || 'video/webm';
      var blob = new Blob(recordChunks, { type: type });
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = 'PersonalCCTV_' + stamp() + '.webm';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(function () { URL.revokeObjectURL(url); }, 30000);
      log('Recording saved to Downloads (' + Math.round(blob.size / 1024) + ' KB)', true);
    };

    webRecorder.start(1000);
    recording = true;
    applyRecordingUi();
    publish('REC_START');
    log('WEB RECORD started', true);
  }

  function stopWebRecord() {
    if (webRecorder && webRecorder.state !== 'inactive') webRecorder.stop();
    webRecorder = null;
    recording = false;
    applyRecordingUi();
    publish('REC_STOP');
    log('WEB RECORD stopped', true);
  }

  function stamp() {
    var d = new Date(), p = function (n) { return String(n).padStart(2, '0'); };
    return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '_' +
      p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
  }

  /* ---------- Wiring ---------- */

  $('btnConnect').addEventListener('click', connect);
  $('btnDisconnect').addEventListener('click', disconnect);
  roomInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') connect(); });

  $('btnMic').addEventListener('click', function () {
    soundOn = !soundOn;
    if (speakerGain) speakerGain.gain.value = soundOn ? 1 : 0;
    micVal.textContent = soundOn ? 'ON' : 'OFF';
    log('Local audio ' + (soundOn ? 'ON' : 'MUTED'));
  });

  $('btnCamMic').addEventListener('click', function () {
    publish(camMicOn ? 'MIC_OFF' : 'MIC_ON');
  });

  $('btnFlip').addEventListener('click', function () { publish('FLIP'); });

  $('btnSplit').addEventListener('click', function () {
    dualOn = !dualOn;
    applyDualUi();
    publish(dualOn ? 'DUAL_ON' : 'DUAL_OFF');
  });

  $('btnFull').addEventListener('click', function () {
    stageWrap.classList.add('fullscreen');
    btnExitFull.hidden = false;
  });
  btnExitFull.addEventListener('click', function () {
    stageWrap.classList.remove('fullscreen');
    btnExitFull.hidden = true;
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && stageWrap.classList.contains('fullscreen')) {
      stageWrap.classList.remove('fullscreen');
      btnExitFull.hidden = true;
    }
  });

  btnRecord.addEventListener('click', function () {
    if (dualOn) { log('Split view me RECORD pehle SINGLE karo', true); return; }
    if (recording) stopWebRecord(); else startWebRecord();
  });

  $('btnSnapshot').addEventListener('click', function () {
    if (!lastFrameBlob) { log('No frame to snapshot', true); return; }
    var url = URL.createObjectURL(lastFrameBlob);
    var a = document.createElement('a');
    a.href = url;
    a.download = 'PersonalCCTV_' + stamp() + '.jpg';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
    log('Snapshot saved');
  });

  $('btnAbout').addEventListener('click', function () { $('aboutModal').hidden = false; });
  $('btnCloseAbout').addEventListener('click', function () { $('aboutModal').hidden = true; });

  /* ---------- Boot ---------- */

  roomInput.value = localStorage.getItem('hcl_room') || '';
  tickClock();
  setInterval(tickClock, 1000);
  startFpsCounter();
  applyDualUi();
  applyRecordingUi();
  if (roomInput.value.trim()) {
    log('Room loaded — press CONNECT');
  }
})();
