(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var TABS = {
    SETUP: [['xtream', 'Xtream'], ['m3u', 'M3U'], ['link', 'Paste a link'], ['restore', 'Restore']],
    RESTORE: [['restore', 'Restore']],
    BACKUP_DOWNLOAD: [['backup', 'Download backup']],
    LOGS_DOWNLOAD: [['logs', 'Download logs']]
  };
  var MSG_ENDED = 'This session has ended. Open phone setup on the TV again.';
  var MSG_GENERIC = 'Something went wrong. Check that the TV still shows the setup screen, then try again.';
  var MSG_NET = 'The TV did not answer. Check that the TV still shows the code, then try again.';

  var maxBytes = 268435456;
  var purpose = null;
  var ended = false;
  var timer = null;
  var inFlight = false;
  var selected = null;
  var lastDownload = null;

  function show(el, on) { el.classList.toggle('hidden', !on); }

  function say(text, cls) {
    var m = $('msg');
    m.textContent = text || '';
    m.className = 'msg ' + (cls || '') + (text ? '' : ' hidden');
  }

  function limitMB() { return Math.round(maxBytes / 1048576); }

  function updateLimits() {
    var els = document.querySelectorAll('.limit');
    for (var i = 0; i < els.length; i++) els[i].textContent = 'Files up to ' + limitMB() + ' MB.';
  }

  function setBusy(on) {
    inFlight = on;
    var b = document.querySelectorAll('button.send');
    for (var i = 0; i < b.length; i++) b[i].disabled = on;
  }

  function endSession() {
    ended = true;
    if (timer) { clearTimeout(timer); timer = null; }
    $('status').textContent = MSG_ENDED;
    show($('statusmsg'), false);
    say(MSG_ENDED, 'err');
    setBusy(true);
  }

  // ---- state polling ----
  function renderState(s) {
    if (s.uploadMaxBytes > 0 && s.uploadMaxBytes !== maxBytes) { maxBytes = s.uploadMaxBytes; updateLimits(); }
    var st = $('status'), sm = $('statusmsg'), text = s.message || '';
    if (s.tv === 'Checking') {
      st.textContent = 'The TV is checking…';
      sm.textContent = text;
      show(sm, !!text);
    } else if (s.tv === 'Done') {
      st.textContent = text || 'Done. Look at the TV.';
      show(sm, false);
    } else {
      st.textContent = 'Connected to your TV.';
      sm.textContent = text;
      show(sm, !!text);
    }
    if (purpose === null) buildTabs(s.purpose);
    renderDownload(s.download);
  }

  function renderDownload(d) {
    if (!d || !d.url) return;
    var link = purpose === 'LOGS_DOWNLOAD' ? $('g-link') : $('b-link');
    if (lastDownload === d.url) return;
    lastDownload = d.url;
    link.setAttribute('href', d.url);
    link.setAttribute('download', d.name || '');
    if (purpose === 'BACKUP_DOWNLOAD') {
      link.textContent = 'Download ' + d.name;
      show($('b-wait'), false);
      show($('b-form'), false);
      show($('b-dl'), true);
    } else if (purpose === 'LOGS_DOWNLOAD') {
      show($('g-wait'), false);
      show($('g-dl'), true);
    }
  }

  function poll() {
    timer = null;
    if (ended || document.visibilityState === 'hidden') return;
    fetch('/api/state', { credentials: 'same-origin', cache: 'no-store' }).then(function (r) {
      if (r.status === 401 || r.status === 403 || r.status === 410) { endSession(); return; }
      if (!r.ok) throw new Error('bad');
      return r.json().then(renderState);
    }).catch(function () {
      if (!ended && purpose === null) $('status').textContent = MSG_NET;
    }).then(function () {
      if (!ended && !timer && document.visibilityState !== 'hidden') timer = setTimeout(poll, 1000);
    });
  }

  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible' && !ended && !timer) poll();
  });

  // ---- tabs ----
  function buildTabs(p) {
    purpose = TABS[p] ? p : 'SETUP';
    var bar = $('tabs');
    TABS[purpose].forEach(function (t, i) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'tab';
      b.textContent = t[1];
      b.setAttribute('role', 'tab');
      b.addEventListener('click', function () { selectTab(t[0]); });
      b.dataset.tab = t[0];
      bar.appendChild(b);
    });
    selectTab(TABS[purpose][0][0]);
  }

  function selectTab(key) {
    selected = key;
    var tabs = $('tabs').children;
    for (var i = 0; i < tabs.length; i++) {
      var on = tabs[i].dataset.tab === key;
      tabs[i].classList.toggle('active', on);
      tabs[i].setAttribute('aria-selected', on ? 'true' : 'false');
    }
    ['xtream', 'm3u', 'link', 'restore', 'backup', 'logs'].forEach(function (k) {
      show($('p-' + k), k === key);
    });
    say('');
  }

  // ---- requests ----
  function describe(r, okText) {
    if (r.status === 202) { say(okText || 'Sent. The TV is checking…', 'ok'); return true; }
    if (r.status === 401 || r.status === 403 || r.status === 410) endSession();
    else if (r.status === 400) say('The TV could not read that. Check the fields and try again.', 'err');
    else if (r.status === 409) say('The TV is not expecting this right now. Look at the TV.', 'err');
    else if (r.status === 413) say('That is too long.', 'err');
    else if (r.status === 507) say('The TV does not have enough free space for this file.', 'err');
    else if (r.status === 503) say('The TV is not ready for this yet. Wait a moment, then send it again.', 'err'); // D-L4
    else say(MSG_GENERIC, 'err');
    return false;
  }

  function request(url, init, okText, onOk, sending) {
    if (inFlight || ended) return;
    setBusy(true);
    say(sending || 'Sending…', '');
    init.method = 'POST';
    init.credentials = 'same-origin';
    init.headers = init.headers || {};
    init.headers['X-TheBox'] = '1';
    fetch(url, init).then(function (r) {
      if (describe(r, okText) && onOk) onOk();
    }).catch(function () {
      say(MSG_NET, 'err');
    }).then(function () {
      if (!ended) setBusy(false);
    });
  }

  function postJson(url, obj, okText, onOk) {
    request(url, {
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(obj)
    }, okText, onOk);
  }

  function val(id) { return $(id).value.trim(); }

  function upload(fileId, kind, okText, onOk) {
    var f = $(fileId).files[0];
    if (!f) { say('Choose a file first.', 'err'); return; }
    if (f.size > maxBytes) {
      say('This file is too big for the TV (limit ' + limitMB() + ' MB).', 'err');
      return;
    }
    request('/api/upload?kind=' + kind + '&name=' + encodeURIComponent(f.name), {
      headers: { 'Content-Type': 'application/octet-stream' },
      body: f
    }, okText || 'Sent. Look at the TV.', onOk, 'Sending…');
  }

  function bind(id, fn) { $(id).addEventListener('click', fn); }

  bind('x-send', function () {
    var server = val('x-server'), user = val('x-user'), pass = $('x-pass').value;
    if (!server || !user || !pass) {
      say('Enter the server URL, username and password.', 'err');
      return;
    }
    postJson('/api/xtream', { name: val('x-name'), server: server, username: user, password: pass, output: $('x-output').value });
  });

  bind('m-send', function () {
    var url = val('m-url'), epg = val('m-epg');
    if (!url) { say('Enter the playlist URL.', 'err'); return; }
    postJson('/api/m3u', { name: val('m-name'), url: url, epgUrls: epg ? [epg] : [] });
  });

  bind('m-fsend', function () { upload('m-file', 'm3u'); });

  bind('l-send', function () {
    var url = val('l-url');
    if (!url) { say('Enter the link.', 'err'); return; }
    postJson('/api/link', { url: url, name: val('l-name') });
  });

  bind('r-send', function () {
    upload('r-file', 'backup', 'Sent. Look at the TV.', function () { show($('r-pass'), true); });
  });

  bind('r-psend', function () {
    postJson('/api/passphrase', { passphrase: $('r-phrase').value });
  });

  bind('b-send', function () {
    var p1 = $('b-p1').value, p2 = $('b-p2').value;
    if (p1 !== p2) { say('The two passphrases do not match. Type them again.', 'err'); return; }
    if (p1 && p1.length < 8) { say('Use at least 8 characters, or leave it empty.', 'err'); return; }
    postJson('/api/passphrase', { passphrase: p1 }, 'Sent. The TV is making the backup…', function () {
      show($('b-form'), false);
      if (!lastDownload) show($('b-wait'), true);
    });
  });

  $('b-link').addEventListener('click', function () { show($('b-saved'), true); });

  // Show/Hide toggles for password fields
  var shows = document.querySelectorAll('button.show');
  for (var i = 0; i < shows.length; i++) {
    shows[i].addEventListener('click', function (e) {
      var b = e.currentTarget, inp = $(b.dataset.for);
      var reveal = inp.type === 'password';
      inp.type = reveal ? 'text' : 'password';
      b.textContent = reveal ? 'Hide' : 'Show';
    });
  }

  updateLimits();
  poll();
})();
