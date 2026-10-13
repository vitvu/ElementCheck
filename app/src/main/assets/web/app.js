'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const ROW_H = 22;
  const FLAGS = ['clickable', 'longClickable', 'contextClickable', 'checkable', 'checked', 'enabled', 'focusable',
    'focused', 'a11yFocused', 'selected', 'scrollable', 'editable', 'password', 'multiLine', 'visible', 'important',
    'dismissable', 'contentInvalid', 'canOpenPopup', 'heading', 'screenReaderFocusable', 'showingHint',
    'textEntryKey', 'a11yDataSensitive'];

  const S = {
    data: null,          // snapshot / saved tree JSON
    source: 'live',      // 'live' or capture id
    img: null,           // HTMLImageElement
    imgUrl: null,
    nodes: [],           // by node.i
    wins: [],            // windows sorted top-most first
    selected: null,
    hover: null,
    collapsed: new Set(),// xpaths (or "win:<id>")
    rows: [],            // visible tree rows
    matches: new Set(), matchList: [], matchPos: -1,
    xml: null,
    live: false, busy: false, lastVersion: -1, lastSnapAt: 0,
    cycle: [], cyclePos: 0, cycleAt: null,
  };

  // ------------------------------------------------------------------ utils
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const shortCls = (c) => (c || '').split('.').pop() || 'node';
  const shortId = (r) => { const k = (r || '').indexOf(':id/'); return k >= 0 ? r.slice(k + 4) : (r || ''); };
  const trunc = (s, n) => (s.length > n ? s.slice(0, n) + '…' : s);
  const area = (b) => Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const boundsStr = (b) => `[${b[0]},${b[1]}][${b[2]},${b[3]}]`;

  function toast(msg, ms = 2200) {
    const t = $('toast');
    t.textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(toast.h);
    toast.h = setTimeout(() => t.classList.add('hidden'), ms);
  }
  function status(msg) { $('status').textContent = msg; }

  async function api(path, body, method) {
    const opt = body !== undefined ? { method: method || 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } } : { method: method || 'GET' };
    const r = await fetch(path, opt);
    const j = await r.json();
    if (j && j.ok === false) throw new Error(j.error || 'error');
    return j;
  }

  function loadImage(url) {
    return new Promise((res) => {
      if (!url) return res(null);
      const im = new Image();
      im.onload = () => res(im);
      im.onerror = () => res(null);
      im.src = url;
    });
  }

  function xq(s) { // XPath string literal
    if (!s.includes("'")) return `'${s}'`;
    if (!s.includes('"')) return `"${s}"`;
    return 'concat(' + s.split("'").map((p) => `'${p}'`).join(`,"'",`) + ')';
  }

  function download(name, blob) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  // ------------------------------------------------------------------ data
  function setData(d, img, imgUrl) {
    const prevXpath = S.selected ? S.selected.xpath : null;
    S.data = d;
    S.img = img;
    S.imgUrl = imgUrl;
    S.nodes = [];
    S.xml = null;
    S.wins = (d.windows || []).slice().sort((a, b) => b.layer - a.layer);
    for (const w of S.wins) {
      w.nodes = [];
      if (w.root) index(w.root, null, w);
    }
    S.selected = prevXpath ? S.nodes.find((n) => n && n.xpath === prevXpath) || null : null;
    S.hover = null;
    $('device').textContent = `${d.device || ''} · Android ${d.release || '?'} (API ${d.sdk || '?'}) · ${d.w}×${d.h}`;
    status(`${d.pkg || ''}${d.activity ? ' / ' + shortCls(d.activity) : ''} · ${d.nodeCount || S.nodes.length} nodes · ${new Date(d.time).toLocaleTimeString()}`);
    runFind();
    layoutScreen();
    renderTree();
    renderDetails();
  }

  function index(n, parent, win) {
    n.parent = parent;
    n.winRef = win;
    S.nodes[n.i] = n;
    win.nodes.push(n);
    if (n.ch) for (const c of n.ch) index(c, n, win);
  }

  // uiautomator-compatible XML, used for XPath evaluation and export.
  function buildXml() {
    if (S.xml) return S.xml;
    const doc = document.implementation.createDocument(null, 'hierarchy', null);
    const root = doc.documentElement;
    root.setAttribute('rotation', String((S.data.rotation || 0) / 90));
    const add = (parentEl, n) => {
      let name = (n.cls || 'node').replace(/[^\w.\-]/g, '_');
      if (!/^[A-Za-z_]/.test(name)) name = '_' + name;
      const e = doc.createElement(name);
      const set = (k, v) => e.setAttribute(k, v == null ? '' : String(v));
      set('index', n.index);
      set('text', n.text || '');
      set('resource-id', n.rid || '');
      set('class', n.cls || '');
      set('package', n.pkg || '');
      set('content-desc', n.desc || '');
      set('checkable', !!n.checkable); set('checked', !!n.checked);
      set('clickable', !!n.clickable); set('enabled', !!n.enabled);
      set('focusable', !!n.focusable); set('focused', !!n.focused);
      set('scrollable', !!n.scrollable); set('long-clickable', !!n.longClickable);
      set('password', !!n.password); set('selected', !!n.selected);
      set('displayed', !!n.visible);
      if (n.hint) set('hint', n.hint);
      set('bounds', boundsStr(n.b));
      set('ec-i', n.i);
      parentEl.appendChild(e);
      if (n.ch) for (const c of n.ch) add(e, c);
    };
    for (const w of S.data.windows || []) if (w.root) add(root, w.root);
    S.xml = doc;
    return doc;
  }

  function evalXpath(expr) {
    const doc = buildXml();
    const res = doc.evaluate(expr, doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
    const out = [];
    for (let k = 0; k < res.snapshotLength; k++) {
      const el = res.snapshotItem(k);
      const i = el.getAttribute && el.getAttribute('ec-i');
      if (i != null && S.nodes[+i]) out.push(S.nodes[+i]);
    }
    return out;
  }

  function countXpath(expr) { try { return evalXpath(expr).length; } catch (e) { return -1; } }

  // ------------------------------------------------------------------ live / sources
  async function refresh() {
    if (S.busy) return;
    S.busy = true;
    try {
      const d = await api(`/api/snapshot?scale=${$('scale').value}&q=70`);
      const url = d.hasImage ? `/api/screen.jpg?seq=${d.seq}` : null;
      const img = await loadImage(url);
      if (S.source === 'live') setData(d, img, url);
      S.lastSnapAt = Date.now();
      if (!d.hasImage && d.sdk < 30) status('Chưa có quyền chụp (Android ≤10): chấp nhận hộp thoại trên điện thoại.');
    } catch (e) {
      status('Lỗi: ' + e.message);
    } finally {
      S.busy = false;
    }
  }

  async function liveLoop() {
    while (S.live) {
      try {
        const v = await api('/api/version');
        if (v.v !== S.lastVersion || Date.now() - S.lastSnapAt > 5000) {
          S.lastVersion = v.v;
          await refresh();
        }
      } catch (e) { status('Mất kết nối…'); await sleep(1000); }
      await sleep(250);
    }
  }

  function setLive(on) {
    if (on && S.source !== 'live') { $('source').value = 'live'; S.source = 'live'; updateSourceUi(); }
    S.live = on;
    $('btnLive').classList.toggle('on', on);
    $('btnLive').textContent = on ? '■ Realtime' : '▶ Realtime';
    if (on) liveLoop();
  }

  async function loadCaptures(selectId) {
    try {
      const list = await api('/api/captures');
      const sel = $('source');
      sel.innerHTML = '<option value="live">● Live device</option>' + list.map((c) => {
        const m = c.meta || {};
        return `<option value="${esc(c.id)}">💾 ${esc(c.id)} · ${esc(shortCls(m.pkg || ''))} · ${m.nodes || 0}n</option>`;
      }).join('');
      sel.value = selectId || S.source;
      if (sel.value !== (selectId || S.source)) sel.value = 'live';
    } catch (e) { /* ignore */ }
  }

  async function openCapture(id) {
    setLive(false);
    S.source = id;
    $('source').value = id;
    updateSourceUi();
    try {
      const d = await api(`/api/captures/${id}/tree.json`);
      const url = d.hasImage ? `/api/captures/${id}/screen.jpg` : null;
      setData(d, await loadImage(url), url);
    } catch (e) { toast('Không mở được: ' + e.message); }
  }

  function updateSourceUi() {
    const live = S.source === 'live';
    $('btnDelete').classList.toggle('hidden', live);
    $('actions').classList.toggle('hidden', !live || !S.selected);
  }

  // ------------------------------------------------------------------ screen
  const imgCanvas = $('imgCanvas'), boxCanvas = $('boxCanvas');
  const view = { k: 1, w: 0, h: 0, dpr: 1 };

  function layoutScreen() {
    if (!S.data) return;
    const pane = $('screenPane');
    const W = pane.clientWidth - 16, H = pane.clientHeight - 16;
    const sw = S.data.w || (S.img ? S.img.width : 1080), sh = S.data.h || (S.img ? S.img.height : 1920);
    const k = Math.min(W / sw, H / sh);
    view.k = k; view.w = Math.round(sw * k); view.h = Math.round(sh * k);
    view.dpr = window.devicePixelRatio || 1;
    const wrap = $('screenWrap');
    wrap.style.width = view.w + 'px';
    wrap.style.height = view.h + 'px';
    for (const c of [imgCanvas, boxCanvas]) {
      c.style.width = view.w + 'px';
      c.style.height = view.h + 'px';
      c.width = Math.round(view.w * view.dpr);
      c.height = Math.round(view.h * view.dpr);
    }
    const ctx = imgCanvas.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, imgCanvas.width, imgCanvas.height);
    if (S.img) {
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(S.img, 0, 0, imgCanvas.width, imgCanvas.height);
    } else {
      ctx.fillStyle = '#64748b';
      ctx.font = `${14 * view.dpr}px system-ui`;
      ctx.fillText('(không có ảnh — chỉ có cây phần tử)', 12 * view.dpr, 24 * view.dpr);
    }
    drawBoxes();
  }

  function drawBoxes() {
    const ctx = boxCanvas.getContext('2d');
    const s = view.k * view.dpr;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, boxCanvas.width, boxCanvas.height);
    if (!S.data) return;
    const rect = (b) => ctx.rect(b[0] * s + 0.5, b[1] * s + 0.5, (b[2] - b[0]) * s, (b[3] - b[1]) * s);

    if ($('optBoxes').checked) {
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (const n of S.nodes) if (n && !n.clickable && area(n.b) > 0) rect(n.b);
      ctx.strokeStyle = 'rgba(56,189,248,.35)';
      ctx.stroke();
      ctx.beginPath();
      for (const n of S.nodes) if (n && n.clickable && area(n.b) > 0) rect(n.b);
      ctx.strokeStyle = 'rgba(74,222,128,.6)';
      ctx.stroke();
    }
    if (S.matches.size) {
      ctx.beginPath();
      for (const n of S.matches) rect(n.b);
      ctx.fillStyle = 'rgba(250,204,21,.18)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(250,204,21,.9)';
      ctx.lineWidth = 1.5 * view.dpr;
      ctx.stroke();
    }
    if (S.hover && S.hover !== S.selected) {
      ctx.beginPath(); rect(S.hover.b);
      ctx.setLineDash([4 * view.dpr, 3 * view.dpr]);
      ctx.strokeStyle = '#facc15'; ctx.lineWidth = 1.5 * view.dpr; ctx.stroke();
      ctx.setLineDash([]);
    }
    if (S.selected) {
      ctx.beginPath(); rect(S.selected.b);
      ctx.fillStyle = 'rgba(244,63,94,.22)'; ctx.fill();
      ctx.strokeStyle = '#f43f5e'; ctx.lineWidth = 2 * view.dpr; ctx.stroke();
    }
  }

  function toDevice(ev) {
    const r = boxCanvas.getBoundingClientRect();
    return { x: (ev.clientX - r.left) / view.k, y: (ev.clientY - r.top) / view.k };
  }

  /** Candidates under a point, topmost window first, smallest element first. */
  function hitTest(x, y) {
    for (const w of S.wins) {
      const wb = w.b;
      if (wb && !(x >= wb[0] && x < wb[2] && y >= wb[1] && y < wb[3])) continue;
      const hits = w.nodes.filter((n) => area(n.b) > 0 && x >= n.b[0] && x < n.b[2] && y >= n.b[1] && y < n.b[3]);
      if (hits.length) return hits.sort((a, b) => area(a.b) - area(b.b));
    }
    return [];
  }

  let down = null;
  boxCanvas.addEventListener('mousedown', (e) => { down = { p: toDevice(e), t: Date.now() }; });
  boxCanvas.addEventListener('mouseup', async (e) => {
    if (!down || !S.data) return;
    const p = toDevice(e), d = down; down = null;
    if ($('optControl').checked && S.source === 'live') {
      const dist = Math.hypot(p.x - d.p.x, p.y - d.p.y), dt = Date.now() - d.t;
      let g;
      if (dist < 12) g = { type: dt > 500 ? 'long' : 'tap', x: d.p.x, y: d.p.y, duration: dt > 500 ? dt : 0 };
      else g = { type: 'swipe', x: d.p.x, y: d.p.y, x2: p.x, y2: p.y, duration: Math.min(2000, Math.max(120, dt)) };
      try { await api('/api/gesture', g); } catch (err) { toast(err.message); }
      setTimeout(refresh, 700);
      return;
    }
    const same = S.cycleAt && Math.hypot(p.x - S.cycleAt.x, p.y - S.cycleAt.y) < 6;
    if (!same) { S.cycle = hitTest(p.x, p.y); S.cyclePos = 0; S.cycleAt = p; }
    else if (S.cycle.length) S.cyclePos = (S.cyclePos + 1) % S.cycle.length;
    if (S.cycle.length) select(S.cycle[S.cyclePos], true);
  });
  boxCanvas.addEventListener('mousemove', (e) => {
    if (!S.data) return;
    const p = toDevice(e);
    const h = hitTest(p.x, p.y)[0] || null;
    const tip = $('tip');
    if (h) {
      tip.textContent = `${shortCls(h.cls)}${h.rid ? ' #' + shortId(h.rid) : ''}${h.text ? ' "' + trunc(h.text, 30) + '"' : ''}`;
      tip.classList.remove('hidden');
      const r = boxCanvas.getBoundingClientRect();
      let tx = e.clientX - r.left + 12, ty = e.clientY - r.top + 12;
      if (tx + 200 > view.w) tx = Math.max(0, tx - 220);
      if (ty + 24 > view.h) ty -= 36;
      tip.style.left = tx + 'px'; tip.style.top = ty + 'px';
    } else tip.classList.add('hidden');
    if (h !== S.hover) { S.hover = h; drawBoxes(); }
  });
  boxCanvas.addEventListener('mouseleave', () => { S.hover = null; $('tip').classList.add('hidden'); drawBoxes(); });

  // ------------------------------------------------------------------ tree (virtualised)
  const treeEl = $('tree'), treeInner = $('treeInner');

  function buildRows() {
    const rows = [];
    const walk = (n, depth) => {
      rows.push({ n, depth });
      if (n.ch && n.ch.length && !S.collapsed.has(n.xpath)) for (const c of n.ch) walk(c, depth + 1);
    };
    for (const w of S.wins) {
      const key = 'win:' + w.id;
      rows.push({ w, depth: 0, key });
      if (w.root && !S.collapsed.has(key)) walk(w.root, 1);
    }
    S.rows = rows;
    treeInner.style.height = rows.length * ROW_H + 'px';
  }

  function renderTree() { buildRows(); paintTree(); }

  function paintTree() {
    const top = treeEl.scrollTop, h = treeEl.clientHeight;
    const a = Math.max(0, Math.floor(top / ROW_H) - 15), b = Math.min(S.rows.length, Math.ceil((top + h) / ROW_H) + 15);
    let html = '';
    for (let k = a; k < b; k++) {
      const r = S.rows[k];
      const pad = 4 + r.depth * 14;
      if (r.w) {
        const open = !S.collapsed.has(r.key);
        html += `<div class="tr" data-row="${k}" style="top:${k * ROW_H}px;padding-left:${pad}px"><span class="tw" data-tw="1">${open ? '▾' : '▸'}</span><span class="win">⧉ ${esc(r.w.type)}${r.w.title ? ' · ' + esc(r.w.title) : ''}</span> <span class="tf">layer ${r.w.layer}${r.w.active ? ' · active' : ''}</span></div>`;
        continue;
      }
      const n = r.n;
      const has = n.ch && n.ch.length;
      const cls = 'tr' + (n === S.selected ? ' sel' : '') + (S.matches.has(n) ? ' match' : '') + (n.visible ? '' : ' dim');
      let label = `<span class="tc">${esc(shortCls(n.cls))}</span>`;
      if (n.rid) label += ` <span class="ti">#${esc(shortId(n.rid))}</span>`;
      if (n.text) label += ` <span class="tt">"${esc(trunc(n.text, 40))}"</span>`;
      if (n.desc) label += ` <span class="td">{${esc(trunc(n.desc, 30))}}</span>`;
      const f = (n.clickable ? 'C' : '') + (n.scrollable ? 'S' : '') + (n.editable ? 'E' : '') + (n.checkable ? 'K' : '');
      if (f) label += ` <span class="tf">${f}</span>`;
      html += `<div class="${cls}" data-row="${k}" style="top:${k * ROW_H}px;padding-left:${pad}px"><span class="tw" data-tw="1">${has ? (S.collapsed.has(n.xpath) ? '▸' : '▾') : ''}</span>${label}</div>`;
    }
    treeInner.innerHTML = html;
  }

  let paintPending = false;
  treeEl.addEventListener('scroll', () => {
    if (paintPending) return;
    paintPending = true;
    requestAnimationFrame(() => { paintPending = false; paintTree(); });
  });

  treeInner.addEventListener('click', (e) => {
    const rowEl = e.target.closest('.tr');
    if (!rowEl) return;
    const r = S.rows[+rowEl.dataset.row];
    if (!r) return;
    const key = r.w ? r.key : r.n.xpath;
    if (e.target.dataset.tw || r.w) {
      if (r.w || (r.n.ch && r.n.ch.length)) {
        if (S.collapsed.has(key)) S.collapsed.delete(key); else S.collapsed.add(key);
        renderTree();
      }
      if (r.w) return;
    }
    select(r.n, false);
  });
  treeInner.addEventListener('mousemove', (e) => {
    const rowEl = e.target.closest('.tr');
    const r = rowEl && S.rows[+rowEl.dataset.row];
    const n = r && r.n ? r.n : null;
    if (n !== S.hover) { S.hover = n; drawBoxes(); }
  });
  treeInner.addEventListener('mouseleave', () => { S.hover = null; drawBoxes(); });

  function reveal(n) {
    let changed = false;
    for (let p = n.parent; p; p = p.parent) if (S.collapsed.delete(p.xpath)) changed = true;
    if (S.collapsed.delete('win:' + n.winRef.id)) changed = true;
    if (changed) buildRows();
    const k = S.rows.findIndex((r) => r.n === n);
    if (k >= 0) {
      const y = k * ROW_H;
      if (y < treeEl.scrollTop || y > treeEl.scrollTop + treeEl.clientHeight - ROW_H * 2) {
        treeEl.scrollTop = y - treeEl.clientHeight / 3;
      }
    }
    paintTree();
  }

  function select(n, fromScreen) {
    S.selected = n;
    if (n) reveal(n); else paintTree();
    drawBoxes();
    renderDetails();
    if (!fromScreen) S.cycleAt = null;
  }

  // ------------------------------------------------------------------ find
  function runFind() {
    S.matches = new Set();
    const xp = $('xpath').value.trim(), q = $('search').value.trim().toLowerCase();
    let info = '';
    if (xp && S.data) {
      try {
        const res = evalXpath(xp);
        res.forEach((n) => S.matches.add(n));
        info = `XPath: ${res.length} kết quả`;
      } catch (e) { info = 'XPath lỗi: ' + e.message; }
    } else if (q && S.data) {
      for (const n of S.nodes) {
        if (!n) continue;
        if ((n.text || '').toLowerCase().includes(q) || (n.rid || '').toLowerCase().includes(q) ||
            (n.desc || '').toLowerCase().includes(q) || (n.cls || '').toLowerCase().includes(q)) S.matches.add(n);
      }
      info = `${S.matches.size} kết quả`;
    }
    S.matchList = [...S.matches];
    S.matchPos = -1;
    $('findInfo').textContent = info;
  }

  function nextMatch() {
    if (!S.matchList.length) return;
    S.matchPos = (S.matchPos + 1) % S.matchList.length;
    select(S.matchList[S.matchPos], false);
    $('findInfo').textContent = `${S.matchPos + 1}/${S.matchList.length}`;
  }

  let findTimer;
  for (const id of ['search', 'xpath']) {
    $(id).addEventListener('input', () => {
      if (id === 'search' && $('search').value) $('xpath').value = '';
      if (id === 'xpath' && $('xpath').value) $('search').value = '';
      clearTimeout(findTimer);
      findTimer = setTimeout(() => { runFind(); paintTree(); drawBoxes(); }, 150);
    });
    $(id).addEventListener('keydown', (e) => { if (e.key === 'Enter') { runFind(); nextMatch(); } });
  }

  // ------------------------------------------------------------------ details
  function locators(n) {
    const out = [];
    const add = (kind, expr, xp) => out.push({ kind, expr, count: xp ? countXpath(xp) : null });
    if (n.rid) add('XPath id', `//*[@resource-id=${xq(n.rid)}]`, `//*[@resource-id=${xq(n.rid)}]`);
    if (n.text) add('XPath text', `//*[@text=${xq(n.text)}]`, `//*[@text=${xq(n.text)}]`);
    if (n.desc) add('XPath desc', `//*[@content-desc=${xq(n.desc)}]`, `//*[@content-desc=${xq(n.desc)}]`);
    if (n.rid && n.text) {
      const e = `//*[@resource-id=${xq(n.rid)} and @text=${xq(n.text)}]`;
      add('XPath id+text', e, e);
    }
    const tag = (n.cls || 'node').replace(/[^\w.\-]/g, '_');
    const attr = n.rid ? `@resource-id=${xq(n.rid)}` : n.text ? `@text=${xq(n.text)}` : n.desc ? `@content-desc=${xq(n.desc)}` : null;
    if (attr) {
      const base = `//${tag}[${attr}]`;
      const all = (() => { try { return evalXpath(base); } catch (e) { return []; } })();
      if (all.length > 1) {
        const pos = all.indexOf(n) + 1;
        if (pos > 0) add('XPath indexed', `(${base})[${pos}]`, `(${base})[${pos}]`);
      } else add('XPath class+attr', base, base);
    }
    add('XPath absolute', n.xpath, n.xpath);
    const ui = [];
    if (n.rid) ui.push(`.resourceId("${n.rid}")`);
    else if (n.text) ui.push(`.text("${n.text}")`);
    else if (n.desc) ui.push(`.description("${n.desc}")`);
    else ui.push(`.className("${n.cls}")`);
    out.push({ kind: 'UiSelector', expr: 'new UiSelector()' + ui.join('') });
    if (n.rid) out.push({ kind: 'Appium id', expr: n.rid });
    if (n.desc) out.push({ kind: 'Accessibility id', expr: n.desc });
    return out;
  }

  function renderDetails() {
    updateSourceUi();
    const el = $('details');
    const n = S.selected;
    if (!n) { el.innerHTML = '<p class="muted">Chọn một phần tử trên ảnh hoặc trong cây.</p>'; return; }
    const row = (k, v) => (v === undefined || v === null || v === '' ? '' : `<tr><td>${esc(k)}</td><td class="v">${esc(v)}</td></tr>`);
    const tbl = (title, body) => (body ? `<h4>${title}</h4><table>${body}</table>` : '');
    const b = n.b, bp = n.bp || [0, 0, 0, 0];

    let h = '';
    h += tbl('Thông tin', [
      row('class', n.cls), row('resource-id', n.rid), row('text', n.text), row('content-desc', n.desc),
      row('package', n.pkg), row('hint', n.hint), row('tooltip', n.tooltip), row('pane title', n.paneTitle),
      row('state desc', n.stateDesc), row('container title', n.containerTitle), row('error', n.error),
      row('labeled by', n.labeledBy), row('unique id', n.uniqueId),
    ].join(''));

    h += '<h4>Locators</h4><table>' + locators(n).map((l) => {
      const badge = l.count == null ? '' : l.count === 1 ? '<span class="uniq">unique</span>' : l.count > 1 ? `<span class="multi">${l.count} matches</span>` : '';
      return `<tr><td>${esc(l.kind)}${badge}</td><td class="v">${esc(l.expr)}</td></tr>`;
    }).join('') + '</table>';

    h += tbl('Vị trí', [
      row('bounds', boundsStr(b)),
      row('size', `${b[2] - b[0]} × ${b[3] - b[1]}`),
      row('center', `${Math.round((b[0] + b[2]) / 2)}, ${Math.round((b[1] + b[3]) / 2)}`),
      row('bounds in parent', boundsStr(bp)),
      row('index', n.index), row('depth', n.depth), row('drawing order', n.drawingOrder),
      row('children', n.childCount), row('window', `${n.winRef.type} #${n.win} (layer ${n.winRef.layer})`),
      row('node #', n.i),
    ].join(''));

    h += '<h4>Trạng thái</h4><table>' + FLAGS.map((f) =>
      `<tr><td>${f}</td><td class="${n[f] ? 'yes' : 'no'}">${n[f] ? '✓ true' : '✗ false'}</td></tr>`).join('') + '</table>';

    h += tbl('Nhập liệu', [
      row('input type', n.inputType != null ? `${n.inputType} (0x${n.inputType.toString(16)})` : null),
      row('max length', n.maxTextLength),
      row('selection', n.selStart != null ? `${n.selStart} → ${n.selEnd}` : null),
      row('live region', n.liveRegion), row('granularities', n.granularities),
    ].join(''));

    if (n.collection || n.collectionItem || n.range) {
      h += tbl('Collection / Range', [
        row('collection', n.collection && JSON.stringify(n.collection)),
        row('collection item', n.collectionItem && JSON.stringify(n.collectionItem)),
        row('range', n.range && JSON.stringify(n.range)),
      ].join(''));
    }
    if (n.actions) h += `<h4>Actions (${n.actions.length})</h4><div>${n.actions.map((a) => `<span class="chip">${esc(a)}</span>`).join('')}</div>`;
    if (n.extras) h += `<h4>Extras</h4><div>${n.extras.map((a) => `<span class="chip">${esc(a)}</span>`).join('')}</div>`;

    const path = [];
    for (let p = n; p; p = p.parent) path.unshift(p);
    h += '<h4>Đường dẫn</h4><div>' + path.map((p) =>
      `<span class="chip" data-goto="${p.i}" style="cursor:pointer">${esc(shortCls(p.cls))}${p.rid ? '#' + esc(shortId(p.rid)) : ''}</span>`).join(' › ') + '</div>';
    el.innerHTML = h;
  }

  $('details').addEventListener('click', (e) => {
    const g = e.target.closest('[data-goto]');
    if (g) { select(S.nodes[+g.dataset.goto], false); return; }
    const td = e.target.closest('td.v');
    if (td) {
      const text = td.textContent;
      (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(
        () => toast('Đã copy'), () => { fallbackCopy(text); toast('Đã copy'); });
    }
  });

  function fallbackCopy(text) {
    const t = document.createElement('textarea');
    t.value = text; document.body.appendChild(t); t.select();
    try { document.execCommand('copy'); } catch (e) { /* ignore */ }
    t.remove();
  }

  // ------------------------------------------------------------------ actions
  $('actions').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-act]');
    if (!b || !S.selected || S.source !== 'live') return;
    try {
      await api('/api/action', { seq: S.data.seq, i: S.selected.i, action: b.dataset.act, text: $('setTextVal').value });
      toast(b.dataset.act + ' ✓');
    } catch (err) { toast('Lỗi: ' + err.message); }
    setTimeout(refresh, 600);
  });

  document.querySelectorAll('[data-global]').forEach((b) => b.addEventListener('click', async () => {
    try { await api('/api/global', { action: b.dataset.global }); } catch (err) { toast(err.message); }
    if (S.source === 'live') setTimeout(refresh, 700);
  }));

  $('btnRefresh').addEventListener('click', () => {
    if (S.source !== 'live') { S.source = 'live'; $('source').value = 'live'; updateSourceUi(); }
    refresh();
  });
  $('btnLive').addEventListener('click', () => setLive(!S.live));
  $('btnCapture').addEventListener('click', async () => {
    const btn = $('btnCapture');
    btn.disabled = true;
    try {
      const r = await api('/api/capture', {});
      toast(`Đã lưu ${r.id} (${r.nodes} nodes)`);
      await loadCaptures(S.source);
    } catch (err) { toast('Lỗi: ' + err.message); }
    btn.disabled = false;
  });
  $('source').addEventListener('change', (e) => {
    const v = e.target.value;
    if (v === 'live') { S.source = 'live'; updateSourceUi(); refresh(); } else openCapture(v);
  });
  $('source').addEventListener('focus', () => loadCaptures());
  $('btnDelete').addEventListener('click', async () => {
    if (S.source === 'live' || !confirm('Xoá capture ' + S.source + '?')) return;
    try { await api(`/api/captures/${S.source}`, undefined, 'DELETE'); } catch (err) { toast(err.message); }
    S.source = 'live';
    await loadCaptures('live');
    refresh();
  });
  $('optBoxes').addEventListener('change', drawBoxes);
  $('scale').addEventListener('change', () => { if (S.source === 'live') refresh(); });

  const stamp = () => (S.source === 'live' ? 'live_' + (S.data ? S.data.seq : 0) : S.source);
  $('btnJson').addEventListener('click', () => {
    if (!S.data) return;
    const clean = JSON.stringify(S.data, (k, v) => (k === 'parent' || k === 'winRef' || k === 'nodes' ? undefined : v), 2);
    download(`elementcheck_${stamp()}.json`, new Blob([clean], { type: 'application/json' }));
  });
  $('btnXml').addEventListener('click', () => {
    if (!S.data) return;
    const doc = buildXml().cloneNode(true);
    doc.querySelectorAll('[ec-i]').forEach((e) => e.removeAttribute('ec-i'));
    const xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n" + new XMLSerializer().serializeToString(doc);
    download(`elementcheck_${stamp()}.xml`, new Blob([xml], { type: 'application/xml' }));
  });
  $('btnPng').addEventListener('click', async () => {
    if (!S.imgUrl) return toast('Không có ảnh');
    download(`elementcheck_${stamp()}.jpg`, await (await fetch(S.imgUrl)).blob());
  });

  // ------------------------------------------------------------------ keyboard
  document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT' || e.ctrlKey || e.metaKey || e.altKey) return;
    const k = e.key;
    if (k === 'r' || k === 'R') $('btnRefresh').click();
    else if (k === 'l' || k === 'L') setLive(!S.live);
    else if (k === 'c' || k === 'C') $('btnCapture').click();
    else if (k === 't' || k === 'T') $('optControl').checked = !$('optControl').checked;
    else if (k === 'Escape') select(null, false);
    else if (S.selected && (k === 'ArrowDown' || k === 'ArrowUp')) {
      const idx = S.rows.findIndex((r) => r.n === S.selected);
      for (let j = idx + (k === 'ArrowDown' ? 1 : -1); j >= 0 && j < S.rows.length; j += (k === 'ArrowDown' ? 1 : -1)) {
        if (S.rows[j].n) { select(S.rows[j].n, false); break; }
      }
      e.preventDefault();
    } else if (S.selected && (k === 'ArrowLeft' || k === 'ArrowRight')) {
      const n = S.selected;
      if (k === 'ArrowLeft') {
        if (n.ch && n.ch.length && !S.collapsed.has(n.xpath)) { S.collapsed.add(n.xpath); renderTree(); }
        else if (n.parent) select(n.parent, false);
      } else if (n.ch && n.ch.length) {
        if (S.collapsed.delete(n.xpath)) renderTree(); else select(n.ch[0], false);
      }
      e.preventDefault();
    }
  });

  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { layoutScreen(); paintTree(); }, 100);
  });

  // ------------------------------------------------------------------ boot
  loadCaptures('live');
  refresh();
})();
