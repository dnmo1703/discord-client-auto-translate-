(() => {
  if (location.origin !== 'https://discord.com' || window !== top ||
      !window.DiscordRuTranslate || window.discordRuInstalled) return;
  window.discordRuInstalled = true;

  const selector = '[id^="message-content-"]';
  const pending = new Map();
  const controls = new WeakMap();
  const changed = new Set();
  let nextId = 0;
  let frame = 0;

  DiscordRuTranslate.onmessage = ({data}) => {
    let result;
    try { result = JSON.parse(data); } catch (_) { return; }
    pending.get(result.id)?.(result);
  };

  function addTranslation(message) {
    if (!message.isConnected) return;
    const text = message.innerText.trim();
    const old = controls.get(message);
    if (old?.container.isConnected && old.text === text) return;
    old?.container.remove();
    if (!text) return;

    const container = document.createElement('div');
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = 'Перевести';
    button.style.cssText = 'color:inherit;background:transparent;border:0;cursor:pointer;' +
      'text-decoration:underline;padding:4px 0;font-size:.85em';
    container.append(button);
    button.addEventListener('click', event => {
      event.preventDefault();
      event.stopPropagation();
      if (button.disabled) return;
      const previous = container.querySelector('[role="status"]');
      if (previous) {
        previous.remove();
        if (button.textContent === 'Скрыть') {
          button.textContent = 'Перевести';
          return;
        }
      }
      const id = ++nextId;
      button.disabled = true;
      button.textContent = 'Перевожу…';
      const finish = result => {
        pending.delete(id);
        clearTimeout(timeout);
        if (!container.isConnected) return;
        button.disabled = false;
        button.textContent = result.text ? 'Скрыть' : 'Повторить';
        const output = document.createElement('div');
        output.setAttribute('role', 'status');
        output.style.cssText = 'border-inline-start:2px solid;padding:4px 8px;white-space:pre-wrap';
        output.textContent = result.text || result.error || 'Перевод недоступен.';
        container.append(output);
      };
      const timeout = setTimeout(() => finish({error: 'Сервер перевода не ответил.'}), 45000);
      pending.set(id, finish);
      try { DiscordRuTranslate.postMessage(JSON.stringify({id, text})); }
      catch (_) { finish({error: 'Перевод недоступен.'}); }
    });
    controls.set(message, {container, text});
    message.after(container);
  }

  function queue(node) {
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const parent = node.closest(selector);
    if (parent) changed.add(parent);
    node.querySelectorAll(selector).forEach(message => changed.add(message));
  }

  function flush() {
    frame = 0;
    changed.forEach(addTranslation);
    changed.clear();
  }

  function start() {
    if (!document.body) return;
    queue(document.body);
    flush();
    new MutationObserver(records => {
      records.forEach(record => {
        const target = record.target.parentElement?.closest(selector) ||
          (record.target.nodeType === Node.ELEMENT_NODE && record.target.closest(selector));
        if (target) changed.add(target);
        record.addedNodes.forEach(queue);
      });
      if (changed.size && !frame) frame = requestAnimationFrame(flush);
    }).observe(document.body, {childList: true, subtree: true, characterData: true});
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, {once: true});
  else start();
})();
