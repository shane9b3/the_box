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
  // parse/parseFields mirror ManualAddressParser (Kotlin, :core), the single owner of the address grammar (closure fix
  // pass, R3); splitFolder mirrors PhoneApi.splitFolder (the parser plus the length caps). PhoneApiNetworkFolderTest's
  // SPLIT_VECTORS run through both (the JS side with node, see the test's KDoc).
  var SCHEME = /^([A-Za-z][A-Za-z0-9+.-]*):\/\//;
  var SMB_PORT = 445;

  function folderParts(raw) {
    var out = [];
    for (var i = 0; i < raw.length; i++) {
      if (/[\u0000-\u001F\u007F]/.test(raw[i])) return null; // C-N4: a control character, before any trim
      var t = raw[i].trim();
      if (t === '' || t === '.') continue;
      if (t === '..') return null;
      out.push(t);
    }
    return out;
  }

  function segments(s) { return folderParts((s || '').split(/[\/\\]/)); }

  function isWholeAddress(s) {
    var t = (s || '').trim();
    return t.indexOf('\\\\') === 0 || t.indexOf('//') === 0 || SCHEME.test(t);
  }

  function percentDecode(s) {
    if (s.indexOf('%') < 0) return s;
    var out = '';
    for (var i = 0; i < s.length;) {
      if (s.charAt(i) === '%') {
        if (!/^[0-9A-Fa-f]{2}$/.test(s.substr(i + 1, 2))) return null;
        out += s.substr(i, 3);
        i += 3;
      } else {
        var end = s.indexOf('%', i);
        if (end < 0) end = s.length;
        try { out += encodeURIComponent(s.substring(i, end)); } catch (e) { return null; }
        i = end;
      }
    }
    try { return decodeURIComponent(out); } catch (e) { return null; } // malformed UTF-8, as the TV refuses it
  }

  function validHost(h) {
    if (!h || h.length > 253) return false;
    if (!/^[A-Za-z0-9]/.test(h) || !/[A-Za-z0-9]$/.test(h) || h.indexOf('..') >= 0) return false;
    if (!/^[A-Za-z0-9._-]+$/.test(h)) return false;
    var labels = h.split('.');
    for (var i = 0; i < labels.length; i++) if (labels[i].length > 63) return false;
    if (labels.every(function (l) { return /^[0-9]*$/.test(l); })) {
      if (labels.length !== 4) return false;
      return labels.every(function (l) { return l.length >= 1 && l.length <= 3 && +l <= 255; });
    }
    return true;
  }

  // {host, port, share, path} or {reason: 'EMPTY' | 'BAD_HOST' | 'BAD_PORT' | 'UNSUPPORTED_SCHEME' | 'BAD_PATH'}
  function parse(input) {
    var s = (input || '').trim();
    if (!s) return { reason: 'EMPTY' };
    var m = SCHEME.exec(s);
    if (m) {
      if (m[1].toLowerCase() !== 'smb') return { reason: 'UNSUPPORTED_SCHEME' };
      s = percentDecode(s.substring(m[0].length));
      if (s === null) return { reason: 'BAD_HOST' };
    } else if (s.indexOf('\\\\') === 0 || s.indexOf('//') === 0) {
      s = s.substring(2);
    }
    var all = s.split(/[\/\\]/), first = -1;
    for (var i = 0; i < all.length; i++) if (/\S/.test(all[i])) { first = i; break; }
    if (first < 0) return { reason: 'EMPTY' };
    var hostPort = all[first].trim(), host = hostPort, port = SMB_PORT, colon = hostPort.indexOf(':');
    if (colon >= 0) {
      if (hostPort.indexOf(':', colon + 1) >= 0) return { reason: 'BAD_HOST' }; // IPv6 is not supported
      host = hostPort.substring(0, colon);
      var p = hostPort.substring(colon + 1);
      if (!/^[0-9]{1,5}$/.test(p)) return { reason: 'BAD_PORT' };
      port = +p;
      if (port < 1 || port > 65535) return { reason: 'BAD_PORT' };
    }
    if (!validHost(host)) return { reason: 'BAD_HOST' };
    var parts = folderParts(all.slice(first + 1));
    if (!parts) return { reason: 'BAD_PATH' };
    return { host: host, port: port, share: parts.length ? parts[0] : null, path: parts.slice(1).join('/') };
  }

  function parseFields(host, share, path) {
    var h = (host || '').trim(), s = (share || '').trim(), whole = isWholeAddress(s);
    var base = parse(whole ? s : h);
    if (base.reason) return base;
    var shareParts = (base.share !== null ? [base.share] : []).concat(base.path ? base.path.split('/') : []);
    if (!whole && s) {
      shareParts = segments(s);
      if (!shareParts) return { reason: 'BAD_PATH' };
    }
    var more = segments(path);
    if (!more) return { reason: 'BAD_PATH' };
    var sh = shareParts.length ? shareParts[0] : null, rest = shareParts.slice(1).concat(more);
    if (sh === null && rest.length) return { reason: 'BAD_PATH' };
    return { host: base.host, port: base.port, share: sh, path: rest.join('/') };
  }

  function splitFolder(host, share, path) {
    var f = parseFields(host, share, path);
    if (f.reason) return null;
    var h = f.port === SMB_PORT ? f.host : f.host + ':' + f.port;
    if (h.length > 255) return null;
    if (f.share !== null && f.share.length > 80) return null;
    if (f.path.length > 1024) return null;
    return { host: h, share: f.share, path: f.path || null };
  }
  // ---- end splitFolder ----

  // SEC-S3: the address the TV signs in to; while the field is read-only it is what the page sends, never the name.
  var contextHost = null;

  function initFolder(f) {
    show($('f-netfolder'), true);
    if (!f) return;
    if (f.host) {
      contextHost = f.host;
      $('n-host').value = f.name && f.name !== f.host ? f.name + ' (' + f.host + ')' : f.host;
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
    if (contextHost) h.value = contextHost; // edit the address, not the shown name
    contextHost = null;
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
    var host = contextHost && $('n-host').readOnly ? contextHost : $('n-host').value;
    if (!host.trim() && !isWholeAddress(shareValue())) { say('Enter the computer\'s name or address.', 'err'); return; }
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
