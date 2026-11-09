(function () {
  'use strict';
  var input = document.getElementById('code');
  var btn = document.getElementById('go');
  var msg = document.getElementById('msg');
  var busy = false;

  function show(text, cls) {
    msg.textContent = text;
    msg.className = 'msg ' + (cls || '');
  }

  function done() {
    busy = false;
    btn.disabled = false;
  }

  function submit() {
    if (busy) return;
    var code = input.value.replace(/\D+/g, '');
    if (code.length !== 6) {
      show('Enter the 6 digits shown on the TV.', 'err');
      return;
    }
    busy = true;
    btn.disabled = true;
    show('Connecting…', '');
    fetch('/pair', {
      method: 'POST',
      headers: { 'X-TheBox': '1', 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ code: code })
    }).then(function (r) {
      if (r.status === 204) {
        window.location.href = '/app';
        return;
      }
      return r.json().catch(function () { return {}; }).then(function (j) {
        j = j || {};
        if (r.status === 403 && j.error === 'busy') {
          show('Another phone is already connected to this TV.', 'err');
        } else if (r.status === 403 && typeof j.left === 'number') {
          show(j.left > 0
            ? 'Wrong code. ' + j.left + (j.left === 1 ? ' try left.' : ' tries left.')
            : 'Too many wrong codes. Look at the TV for a new code.', 'err');
        } else if (r.status === 410 && j.error === 'too_many_wrong_codes') {
          show('Too many wrong codes from this phone. On the TV, close phone setup and open it again.', 'err');
        } else if (r.status === 410) {
          show('The TV stopped this session. Open phone setup on the TV again.', 'err');
        } else {
          show('The TV could not accept that. Check the code on the TV and try again.', 'err');
        }
        done();
      });
    }).catch(function () {
      show('The TV did not answer. Check that the TV still shows the code, then try again.', 'err');
      done();
    });
  }

  btn.addEventListener('click', submit);
  document.getElementById('form').addEventListener('submit', function (e) {
    e.preventDefault();
    submit();
  });
  input.focus();
})();
