(function () {
  if (window.__dcTrInjected) return;
  window.__dcTrInjected = true;

  var SEL = '[id^="message-content-"]';
  var pending = {};

  function css() {
    if (document.getElementById('dc-tr-style')) return;
    var s = document.createElement('style');
    s.id = 'dc-tr-style';
    s.textContent =
      '.dc-tr-btn{display:inline-block;margin:2px 0 0 0;padding:0 6px;font-size:12px;line-height:18px;' +
      'border-radius:9px;opacity:.55;cursor:pointer;user-select:none;-webkit-user-select:none;' +
      'background:rgba(127,127,127,.18);color:inherit}' +
      '.dc-tr-btn:active{opacity:1}' +
      '.dc-tr-out{margin:2px 0 4px 0;padding:2px 0 2px 8px;border-left:3px solid #4cc9a6;' +
      'font-style:italic;opacity:.85;white-space:pre-wrap;word-break:break-word}' +
      '.dc-tr-err{border-left-color:#e05d5d}';
    (document.head || document.documentElement).appendChild(s);
  }

  // Пропускаем сообщения, которые уже в основном на кириллице или без букв
  function needsTranslate(text) {
    var letters = text.match(/\p{L}/gu);
    if (!letters || letters.length < 2) return false;
    var cyr = text.match(/[Ѐ-ӿ]/g);
    return !cyr || cyr.length / letters.length < 0.5;
  }

  function idOf(el) { return el.id.replace('message-content-', ''); }

  function addButton(el) {
    if (el.dataset.dcTr === '1') return;
    var text = (el.innerText || '').trim();
    if (!text || !needsTranslate(text)) { el.dataset.dcTr = '1'; return; }
    el.dataset.dcTr = '1';
    var id = idOf(el);
    var next = el.nextElementSibling;
    if (next && next.classList && next.classList.contains('dc-tr-btn')) return;
    var b = document.createElement('div');
    b.className = 'dc-tr-btn';
    b.setAttribute('data-for', id);
    b.textContent = '🌐 Перевести';
    b.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      var out = document.querySelector('.dc-tr-out[data-for="' + id + '"]');
      if (out) { out.remove(); b.textContent = '🌐 Перевести'; return; }
      if (pending[id]) return;
      var src = document.getElementById('message-content-' + id);
      var t = src ? (src.innerText || '').trim() : text;
      pending[id] = true;
      b.textContent = '⏳ Перевожу…';
      try { DCNative.translate(id, t); } catch (e) { done(id, false, String(e), ''); }
    }, true);
    el.insertAdjacentElement('afterend', b);
  }

  function done(id, ok, text, lang) {
    delete pending[id];
    var b = document.querySelector('.dc-tr-btn[data-for="' + id + '"]');
    if (!b) return;
    var out = document.createElement('div');
    out.className = 'dc-tr-out' + (ok ? '' : ' dc-tr-err');
    out.setAttribute('data-for', id);
    out.textContent = ok ? text : ('Не удалось перевести: ' + text);
    b.insertAdjacentElement('afterend', out);
    b.textContent = ok ? ('🌐 ' + (lang ? lang.toUpperCase() + ' → RU · ' : '') + 'скрыть') : '🌐 Повторить';
  }
  window.__dcTrDone = done;

  window.__dcTrSetEnabled = function (on) {
    window.__dcTrOff = !on;
    document.querySelectorAll('.dc-tr-btn,.dc-tr-out').forEach(function (n) { n.style.display = on ? '' : 'none'; });
  };

  var scheduled = false;
  function scan() {
    scheduled = false;
    if (window.__dcTrOff) return;
    css();
    document.querySelectorAll(SEL).forEach(function (el) {
      // React иногда перерисовывает узел — тогда кнопка пропадает, проверяем заново
      var n = el.nextElementSibling;
      if (el.dataset.dcTr === '1' && !(n && n.classList && n.classList.contains('dc-tr-btn'))) {
        var t = (el.innerText || '').trim();
        if (t && needsTranslate(t)) el.dataset.dcTr = '';
      }
      addButton(el);
    });
  }
  function schedule() { if (!scheduled) { scheduled = true; setTimeout(scan, 300); } }

  function start() {
    new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
    schedule();
  }
  if (document.body) start(); else document.addEventListener('DOMContentLoaded', start);
})();
