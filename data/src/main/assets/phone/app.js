(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var TABS = {
    SETUP: [['xtream', 'Xtream'], ['m3u', 'M3U'], ['link', 'Paste a link'], ['restore', 'Restore']],
    RESTORE: [['restore', 'Restore']],
    BACKUP_DOWNLOAD: [['backup', 'Download backup']],
    LOGS_DOWNLOAD: [['logs', 'Download logs']],
    NETWORK_FOLDER: [['netfolder', 'Network folder']]
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
  // NETWORK_FOLDER (docs/design/m6-storage-locations.md §7.4): the final result stays on screen after the session ends.
  var MSG_TOO_MANY = 'Too many failed sign-ins. Start again on the TV.';
  var finalText = null;
  var finalOk = false;
  var folderWait = false;
  var lastFailures = 0;

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
    if (folderWait || finalText) $('n-send').disabled = true; // the TV is signing in, or the session is over
  }

  function endSession() {
    ended = true;
    if (timer) { clearTimeout(timer); timer = null; }
    $('status').textContent = finalText || MSG_ENDED;
    show($('statusmsg'), false);
    say(finalText || MSG_ENDED, finalOk ? 'ok' : 'err');
    setBusy(true);
  }

  // ---- state polling ----
  function renderState(s) {
    if (s.uploadMaxBytes > 0 && s.uploadMaxBytes !== maxBytes) { maxBytes = s.uploadMaxBytes; updateLimits(); }
    if (s.purpose === 'NETWORK_FOLDER') {
      if (purpose === null) { buildTabs(s.purpose); initFolder(s.folder); }
      renderFolder(s);
      return;
    }
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
    ['xtream', 'm3u', 'link', 'restore', 'backup', 'logs', 'netfolder'].forEach(function (k) {
      show($('p-' + k), k === key);
    });
    say('');
  }

  // ---- requests ----
  function describe(r, okText) {
    if (r.status === 202) { say(okText || 'Sent. The TV is checking…', 'ok'); return true; }
    if (r.status === 401 || r.status === 403 || r.status === 410) endSession();
    else if (r.status === 400) say('The TV could not read that. Check the fields and try again.', 'err');
    else if (r.status === 409 && purpose === 'NETWORK_FOLDER') say('The TV is still signing in. Wait for its answer.', 'err');
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

  // ---- network folder (docs/design/m6-storage-locations.md §7.4) ----
  // splitFolder mirrors PhoneApi.splitFolder (Kotlin); PhoneApiNetworkFolderTest holds the shared vectors.
  var HOST_OK = /^(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(?::[0-9]{1,5})?$/;
  var PORT = /:([0-9]{1,5})$/;

  function segments(s) {
    return (s || '').split(/[\/\\]/).map(function (x) { return x.trim(); })
      .filter(function (x) { return x !== '' && x !== '.'; });
  }

  function uncParts(s) {
    var rest;
    if (s.indexOf('\\\\') === 0 || s.indexOf('//') === 0) rest = s.substring(2);
    else if (/^smb:\/\//i.test(s)) rest = s.substring(6);
    else return null;
    var p = segments(rest);
    return p.length ? p : null;
  }

  function splitFolder(host, share, path) {
    var h = (host || '').trim(), shareParts = [], u;
    if ((u = uncParts(h))) { h = u[0]; shareParts = u.slice(1); }
    var s = (share || '').trim(), us = uncParts(s);
    if (us) { h = us[0]; shareParts = us.slice(1); } else if (s) shareParts = segments(s);
    var sh = shareParts.length ? shareParts[0] : null;
    var pathParts = shareParts.slice(1).concat(segments(path));
    if (!h || h.length > 255 || !HOST_OK.test(h)) return null;
    var port = PORT.exec(h);
    if (port && !(+port[1] >= 1 && +port[1] <= 65535)) return null;
    if (sh === null && pathParts.length) return null;
    if (sh === '..' || pathParts.indexOf('..') >= 0) return null;
    if (sh !== null && sh.length > 80) return null;
    var p = pathParts.join('/');
    if (p.length > 1024) return null;
    return { host: h, share: sh, path: p || null };
  }
  // ---- end splitFolder ----

  function initFolder(f) {
    show($('f-netfolder'), true);
    if (!f) return;
    if (f.host) {
      $('n-host').value = f.host;
      $('n-host').readOnly = true;
      show($('n-change'), true);
    }
    var list = $('n-share-list');
    if (f.shares && f.shares.length) {
      f.shares.forEach(function (name) {
        var o = document.createElement('option');
        o.value = name;
        o.textContent = name;
        list.appendChild(o);
      });
      var other = document.createElement('option');
      other.value = '';
      other.textContent = 'Another shared folder (type or paste it)';
      list.appendChild(other);
      list.value = f.shares.indexOf(f.share) >= 0 ? f.share : f.shares[0];
      show(list, true);
      show($('n-share'), false);
    } else if (f.share) {
      $('n-share').value = f.share;
    }
  }

  function shareListShown() { return !$('n-share-list').classList.contains('hidden'); }

  function shareValue() {
    if (shareListShown() && $('n-share-list').value) return $('n-share-list').value;
    return $('n-share').value;
  }

  function renderFolder(s) {
    var st = $('status'), sm = $('statusmsg'), text = s.message || '', rec = s.received;
    var recText = rec ? (rec.guest ? 'Received from your phone: as a guest.' : 'Received from your phone: user ' + rec.user + '.') : '';
    if (s.ended === 'too_many_sign_ins' || s.tv === 'Ended') {
      finalText = (text ? text + ' ' : '') + MSG_TOO_MANY;
      st.textContent = MSG_TOO_MANY;
      sm.textContent = text;
      show(sm, !!text);
      say(finalText, 'err');
      setBusy(true);
      return;
    }
    if (s.ended === 'signed_in' || s.tv === 'Done') {
      finalText = text || 'Signed in. Choose the folder on the TV.';
      finalOk = true;
      st.textContent = finalText;
      show(sm, false);
      say(finalText, 'ok');
      setBusy(true);
      return;
    }
    folderWait = s.tv === 'Checking';
    if (!inFlight) $('n-send').disabled = folderWait;
    if (folderWait) {
      st.textContent = 'The TV is signing in…';
      sm.textContent = recText;
      show(sm, !!recText);
    } else if (s.tv === 'Retry') {
      st.textContent = 'Not signed in yet. Check the details and send again.';
      sm.textContent = recText;
      show(sm, !!recText);
      if (s.failures !== lastFailures) {
        lastFailures = s.failures;
        var left = (s.maxFailures || 0) - s.failures;
        say(text + (left > 0 ? ' Tries left: ' + left + '.' : ''), 'err');
        $('n-pass').focus();
      }
    } else {
      st.textContent = 'Connected to your TV.';
      sm.textContent = text;
      show(sm, !!text);
    }
  }

  bind('n-change', function () {
    var h = $('n-host');
    h.readOnly = false;
    h.focus();
    h.select();
    show($('n-change'), false);
    // The listed shared folders belong to the old computer.
    show($('n-share-list'), false);
    show($('n-share'), true);
  });

  $('n-share-list').addEventListener('change', function () {
    var typed = !$('n-share-list').value;
    show($('n-share'), typed);
    if (typed) $('n-share').focus();
  });

  $('n-guest').addEventListener('change', function () {
    var guest = $('n-guest').checked;
    ['n-user', 'n-pass', 'n-domain'].forEach(function (id) { $(id).disabled = guest; });
  });

  $('n-form').addEventListener('submit', function (e) {
    e.preventDefault();
    if (finalText) return;
    if (folderWait) { say('The TV is still signing in. Wait for its answer.', 'err'); return; }
    var host = $('n-host').value;
    if (!host.trim() && !uncParts(shareValue().trim())) { say('Enter the computer\'s name or address.', 'err'); return; }
    var f = splitFolder(host, shareValue(), $('n-path').value);
    if (!f) {
      say('Check the computer and the shared folder, for example nas and TV, or paste \\\\NAS\\TV\\Rewind.', 'err');
      return;
    }
    var guest = $('n-guest').checked, user = val('n-user');
    if (!guest && !user) { say('Enter the user name, or tick "This folder allows guests".', 'err'); return; }
    var body = { host: f.host, share: f.share, path: f.path, guest: guest };
    if (!guest) {
      body.user = user;
      body.domain = val('n-domain');
      body.password = $('n-pass').value;
    }
    postJson('/api/network-folder', body, 'Sent. The TV is signing in…', function () {
      // The TV has it now; the page keeps no copy.
      $('n-pass').value = '';
      folderWait = true;
      $('n-send').disabled = true;
    });
    body.password = null;
  });

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
