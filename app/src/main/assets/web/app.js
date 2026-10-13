'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const ROW_H = 28;
  const BOOL_PROPS = [['checkable'], ['checked'], ['clickable'], ['enabled'], ['focusable'], ['focused'],
    ['long-clickable', 'longClickable'], ['scrollable'], ['selected'], ['password'], ['editable'], ['displayed', 'visible']];
  const WIN_LABEL = { application: 'APP', system: 'SYSTEM', inputMethod: 'KEYBOARD' };
  const icon = (id, cls) => `<svg${cls ? ` class="${cls}"` : ''}><use href="#i-${id}"/></svg>`;

  const S = {
    data: null, source: 'live', img: null, nodes: [], wins: [],
    selected: null, hover: null, collapsed: new Set(), rows: [],
    matches: [], matchSet: new Set(), matchPos: -1, xml: null,
    mode: 'inspect', cycle: [], cyclePos: 0, cycleAt: null, busy: false, captures: [], saved: [],
  };

  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const shortCls = (c) => (c || 'node').slice((c || '').lastIndexOf('.') + 1);
  const shortId = (r) => { const k = (r || '').indexOf(':id/'); return k >= 0 ? r.slice(k + 4) : (r || ''); };
  const trunc = (s, n) => (s.length > n ? s.slice(0, n) + '…' : s);
  const area = (b) => Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
  const boundsStr = (b) => `[${b[0]},${b[1]}][${b[2]},${b[3]}]`;
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function toast(msg) {
    const t = $('toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toast.h);
    toast.h = setTimeout(() => t.classList.remove('show'), 2000);
  }

  async function http(path, body, method) {
    const opt = body !== undefined
      ? { method: method || 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } }
      : { method: method || 'GET' };
    const j = await (await fetch(path, opt)).json();
    if (j && j.ok === false) throw new Error(j.error || 'error');
    return j;
  }

  // websocket

  const WS = { sock: null, open: false, seq: 0, pending: new Map(), snapImg: null };

  function wsConnect() {
    const s = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`);
    s.binaryType = 'arraybuffer';
    s.onopen = () => {
      WS.open = true;
      if (S.mode === 'control') send({ t: 'stream', on: true, scale: 0.5 });
    };
    s.onclose = () => {
      WS.open = false;
      for (const p of WS.pending.values()) p.reject(new Error('disconnected'));
      WS.pending.clear();
      setTimeout(wsConnect, 1500);
    };
    s.onmessage = (e) => {
      if (typeof e.data !== 'string') {
        const type = new Uint8Array(e.data, 0, 1)[0];
        const jpeg = new Blob([new Uint8Array(e.data, 1)], { type: 'image/jpeg' });
        if (type === 1) WS.snapImg = jpeg;
        else if (type === 2) onStreamFrame(jpeg);
        return;
      }
      const m = JSON.parse(e.data);
      const p = WS.pending.get(m.id);
      if (!p) return;
      WS.pending.delete(m.id);
      if (m.t === 'err') p.reject(new Error(m.error));
      else p.resolve(m);
    };
    WS.sock = s;
  }

  function send(msg) { if (WS.open) WS.sock.send(JSON.stringify(msg)); }

  function rpc(msg) {
    if (!WS.open) return null;
    const id = ++WS.seq;
    msg.id = id;
    return new Promise((resolve, reject) => {
      WS.pending.set(id, { resolve, reject });
      WS.sock.send(JSON.stringify(msg));
      setTimeout(() => { if (WS.pending.delete(id)) reject(new Error('timeout')); }, 15000);
    });
  }

  async function snapshot() {
    const r = rpc({ t: 'snap', scale: 0.6, q: 80 });
    if (r) {
      WS.snapImg = null;
      const m = await r;
      return { data: m.data, img: WS.snapImg ? await blobImage(WS.snapImg) : null };
    }
    const d = await http('/api/snapshot?scale=0.6&q=80');
    return { data: d, img: d.hasImage ? await urlImage(`/api/screen.jpg?seq=${d.seq}`) : null };
  }

  async function command(msg, httpPath) {
    const r = rpc(msg);
    if (r) return r;
    if (httpPath) return http(httpPath, msg);
    throw new Error('not connected');
  }

  function blobImage(blob) {
    if (window.createImageBitmap) return createImageBitmap(blob).catch(() => null);
    return urlImage(URL.createObjectURL(blob));
  }

  function urlImage(url) {
    return new Promise((res) => {
      const im = new Image();
      im.onload = () => res(im);
      im.onerror = () => res(null);
      im.src = url;
    });
  }

  let streamBusy = false;
  async function onStreamFrame(blob) {
    if (S.mode !== 'control' || streamBusy) { send({ t: 'ack' }); return; }
    streamBusy = true;
    const img = await blobImage(blob);
    if (img && S.data) {
      S.img = img;
      paintImage();
    }
    streamBusy = false;
    send({ t: 'ack' });
  }

  function xq(s) {
    if (!s.includes('"')) return `"${s}"`;
    if (!s.includes("'")) return `'${s}'`;
    return 'concat(' + s.split('"').map((p) => `"${p}"`).join(`,'"',`) + ')';
  }

  async function copy(text) {
    try { await navigator.clipboard.writeText(text); } catch (e) {
      const t = document.createElement('textarea');
      t.value = text; document.body.appendChild(t); t.select();
      document.execCommand('copy'); t.remove();
    }
    toast('Copied');
  }

  // data

  function setData(d, img) {
    const prev = S.selected ? S.selected.xpath : null;
    S.data = d; S.img = img; S.nodes = []; S.xml = null;
    S.wins = (d.windows || []).slice().sort((a, b) => b.layer - a.layer);
    for (const w of S.wins) { w.nodes = []; if (w.root) index(w.root, null, w); }
    S.selected = prev ? S.nodes.find((n) => n && n.xpath === prev) || null : null;
    S.hover = null;
    document.title = `ElementCheck · ${d.pkg || d.device || ''}`;
    runFind();
    layoutScreen();
    renderTree();
    renderProps();
  }

  function index(n, parent, win) {
    n.parent = parent; n.winRef = win;
    S.nodes[n.i] = n;
    win.nodes.push(n);
    if (n.ch) for (const c of n.ch) index(c, n, win);
  }

  function buildXml() {
    if (S.xml) return S.xml;
    const doc = document.implementation.createDocument(null, 'hierarchy', null);
    doc.documentElement.setAttribute('rotation', String((S.data.rotation || 0) / 90));
    const add = (parentEl, n) => {
      let name = (n.cls || 'node').replace(/[^\w.\-]/g, '_');
      if (!/^[A-Za-z_]/.test(name)) name = '_' + name;
      const e = doc.createElement(name);
      const set = (k, v) => e.setAttribute(k, v == null ? '' : String(v));
      set('index', n.index); set('text', n.text || ''); set('resource-id', n.rid || '');
      set('class', n.cls || ''); set('package', n.pkg || ''); set('content-desc', n.desc || '');
      for (const [k, f] of BOOL_PROPS) set(k, !!n[f || k]);
      if (n.hint) set('hint', n.hint);
      set('bounds', boundsStr(n.b));
      set('ec-i', n.i);
      parentEl.appendChild(e);
      if (n.ch) for (const c of n.ch) add(e, c);
    };
    for (const w of S.data.windows || []) if (w.root) add(doc.documentElement, w.root);
    S.xml = doc;
    return doc;
  }

  function evalXpath(expr) {
    const doc = buildXml();
    const res = doc.evaluate(expr, doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
    const out = [];
    for (let k = 0; k < res.snapshotLength; k++) {
      const i = res.snapshotItem(k).getAttribute('ec-i');
      if (i != null && S.nodes[+i]) out.push(S.nodes[+i]);
    }
    return out;
  }

  function xpathLite(n) {
    const tries = [];
    if (n.rid) tries.push(`//*[@resource-id=${xq(n.rid)}]`);
    if (n.text) tries.push(`//*[@text=${xq(n.text)}]`);
    if (n.desc) tries.push(`//*[@content-desc=${xq(n.desc)}]`);
    if (n.rid && n.text) tries.push(`//*[@resource-id=${xq(n.rid)} and @text=${xq(n.text)}]`);
    for (const x of tries) { try { if (evalXpath(x).length === 1) return x; } catch (e) { /* skip */ } }
    if (tries.length) {
      try {
        const k = evalXpath(tries[0]).indexOf(n);
        if (k >= 0) return `(${tries[0]})[${k + 1}]`;
      } catch (e) { /* skip */ }
    }
    return n.xpath;
  }

  // sources

  async function refresh() {
    if (S.busy) return;
    S.busy = true;
    $('btnRefresh').disabled = true;
    try {
      const { data, img } = await snapshot();
      setSource('live');
      setData(data, img);
    } catch (e) {
      toast('Connection failed');
    } finally {
      S.busy = false;
      $('btnRefresh').disabled = false;
    }
  }

  function setSource(id) {
    S.source = id;
    const live = id === 'live';
    $('sourceBar').classList.toggle('hidden', live);
    if (!live) {
      const c = S.captures.find((x) => x.id === id);
      $('sourceName').textContent = c ? capName(c) : id;
      setMode('inspect');
    }
  }

  async function openCapture(id) {
    try {
      const d = await http(`/api/captures/${id}/tree.json`);
      setSource(id);
      setData(d, d.hasImage ? await urlImage(`/api/captures/${id}/screen.jpg`) : null);
      closeCaptures();
    } catch (e) { toast('Failed to open capture'); }
  }

  // captures manager

  const capName = (c) => (c.meta && (c.meta.name || c.meta.pkg)) || c.id;

  async function loadCaptures() {
    try { S.captures = await http('/api/captures'); } catch (e) { S.captures = []; }
    renderCaptures();
  }

  function renderCaptures() {
    $('capCount').textContent = S.captures.length ? `· ${S.captures.length}` : '';
    if (!S.captures.length) { $('capGrid').innerHTML = '<div class="capEmpty">No captures yet</div>'; return; }
    $('capGrid').innerHTML = S.captures.map((c) => {
      const m = c.meta || {};
      const time = m.time ? new Date(m.time).toLocaleString() : '';
      return `<div class="card${c.id === S.source ? ' cur' : ''}" data-id="${esc(c.id)}">
        <div class="thumb" data-act="open" style="background-image:url('/api/captures/${esc(c.id)}/thumb.jpg')"></div>
        <div class="meta"><div class="name" title="${esc(capName(c))}">${esc(capName(c))}</div>
          <div class="sub">${esc(time)} · ${m.nodes || 0} nodes</div></div>
        <div class="acts">
          <button data-act="open" title="Open">${icon('open')}</button>
          <button data-act="rename" title="Rename">${icon('edit')}</button>
          <button data-act="json" title="Download JSON">${icon('download')}</button>
          <span class="grow"></span>
          <button data-act="delete" class="del" title="Delete">${icon('delete')}</button>
        </div></div>`;
    }).join('');
  }

  function download(url, name) {
    const a = document.createElement('a');
    a.href = url; a.download = name; a.click();
  }

  $('capGrid').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-act]');
    const card = e.target.closest('.card');
    if (!b || !card) return;
    const id = card.dataset.id;
    const c = S.captures.find((x) => x.id === id);
    switch (b.dataset.act) {
      case 'open': openCapture(id); break;
      case 'json': download(`/api/captures/${id}/tree.json`, `${id}.json`); download(`/api/captures/${id}/screen.jpg`, `${id}.jpg`); break;
      case 'rename': {
        const nameEl = card.querySelector('.name');
        nameEl.innerHTML = `<input value="${esc(capName(c))}">`;
        const input = nameEl.firstChild;
        input.focus(); input.select();
        const done = async (save) => {
          if (save && input.value.trim()) {
            try { await http(`/api/captures/${id}/meta.json`, { name: input.value.trim() }); } catch (err) { toast(err.message); }
          }
          await loadCaptures();
          if (S.source === id) setSource(id);
        };
        input.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') done(true); else if (ev.key === 'Escape') done(false); });
        input.addEventListener('blur', () => done(true), { once: true });
        break;
      }
      case 'delete':
        if (!confirm(`Delete "${capName(c)}"?`)) return;
        try { await http(`/api/captures/${id}`, undefined, 'DELETE'); } catch (err) { toast(err.message); }
        if (S.source === id) refresh();
        loadCaptures();
        break;
      default: break;
    }
  });

  function openCaptures() { $('capModal').classList.remove('hidden'); loadCaptures(); }
  function closeCaptures() { $('capModal').classList.add('hidden'); }
  $('btnCaptures').addEventListener('click', openCaptures);
  $('capClose').addEventListener('click', closeCaptures);
  $('capModal').addEventListener('click', (e) => { if (e.target.id === 'capModal') closeCaptures(); });

  async function capture() {
    const btns = [$('btnCapture'), $('capNew')];
    btns.forEach((b) => (b.disabled = true));
    try {
      const r = await http('/api/capture', {});
      toast(`Saved · ${r.nodes} nodes`);
      if (!$('capModal').classList.contains('hidden')) loadCaptures();
    } catch (err) { toast(err.message); }
    btns.forEach((b) => (b.disabled = false));
  }
  $('btnCapture').addEventListener('click', capture);
  $('capNew').addEventListener('click', capture);

  // screen

  const imgCanvas = $('imgCanvas'), boxCanvas = $('boxCanvas');
  const view = { k: 1, w: 0, h: 0, dpr: 1 };

  function layoutScreen() {
    if (!S.data) return;
    const box = $('screenBox');
    const W = box.clientWidth - 24, H = box.clientHeight;
    const sw = S.data.w, sh = S.data.h;
    view.k = Math.min(W / sw, H / sh);
    view.w = Math.round(sw * view.k); view.h = Math.round(sh * view.k);
    view.dpr = window.devicePixelRatio || 1;
    const wrap = $('screenWrap');
    wrap.style.width = view.w + 'px'; wrap.style.height = view.h + 'px';
    for (const c of [imgCanvas, boxCanvas]) {
      c.style.width = view.w + 'px'; c.style.height = view.h + 'px';
      c.width = Math.round(view.w * view.dpr); c.height = Math.round(view.h * view.dpr);
    }
    paintImage();
    drawBoxes();
  }

  function paintImage() {
    const ctx = imgCanvas.getContext('2d');
    ctx.clearRect(0, 0, imgCanvas.width, imgCanvas.height);
    if (S.img) {
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(S.img, 0, 0, imgCanvas.width, imgCanvas.height);
    }
  }

  function drawBoxes() {
    const ctx = boxCanvas.getContext('2d');
    const s = view.k * view.dpr;
    ctx.clearRect(0, 0, boxCanvas.width, boxCanvas.height);
    if (!S.data || S.mode === 'control') return;
    const rect = (b) => ctx.rect(b[0] * s + 0.5, b[1] * s + 0.5, (b[2] - b[0]) * s, (b[3] - b[1]) * s);
    if ($('optGrid').checked) {
      ctx.beginPath();
      for (const n of S.nodes) if (n && area(n.b) > 0) rect(n.b);
      ctx.strokeStyle = 'rgba(26,86,219,.55)'; ctx.lineWidth = 1; ctx.stroke();
    }
    if (S.matchSet.size) {
      ctx.beginPath();
      for (const n of S.matchSet) rect(n.b);
      ctx.fillStyle = 'rgba(250,204,21,.25)'; ctx.fill();
    }
    if (S.hover && S.hover !== S.selected) {
      ctx.beginPath(); rect(S.hover.b);
      ctx.fillStyle = 'rgba(26,86,219,.12)'; ctx.fill();
      ctx.strokeStyle = 'rgba(26,86,219,.9)'; ctx.lineWidth = 1.5 * view.dpr; ctx.stroke();
    }
    if (S.selected) {
      ctx.beginPath(); rect(S.selected.b);
      ctx.fillStyle = 'rgba(253,224,71,.45)'; ctx.fill();
      ctx.strokeStyle = '#eab308'; ctx.lineWidth = 2 * view.dpr; ctx.stroke();
    }
  }

  function toDevice(ev) {
    const r = boxCanvas.getBoundingClientRect();
    return { x: (ev.clientX - r.left) / view.k, y: (ev.clientY - r.top) / view.k };
  }

  function hitTest(x, y) {
    for (const w of S.wins) {
      if (w.b && !(x >= w.b[0] && x < w.b[2] && y >= w.b[1] && y < w.b[3])) continue;
      const hits = w.nodes.filter((n) => area(n.b) > 0 && x >= n.b[0] && x < n.b[2] && y >= n.b[1] && y < n.b[3]);
      if (hits.length) return hits.sort((a, b) => area(a.b) - area(b.b));
    }
    return [];
  }

  let down = null;
  boxCanvas.addEventListener('pointerdown', (e) => {
    down = { p: toDevice(e), t: Date.now() };
    boxCanvas.setPointerCapture(e.pointerId);
  });
  boxCanvas.addEventListener('pointerup', (e) => {
    if (!down || !S.data) return;
    const p = toDevice(e), d = down;
    down = null;
    if (S.mode === 'control') {
      const dist = Math.hypot(p.x - d.p.x, p.y - d.p.y), dt = Date.now() - d.t;
      const g = dist < 12
        ? { t: 'gesture', type: dt > 500 ? 'long' : 'tap', x: d.p.x, y: d.p.y, duration: dt > 500 ? dt : 0 }
        : { t: 'gesture', type: 'swipe', x: d.p.x, y: d.p.y, x2: p.x, y2: p.y, duration: Math.min(2000, Math.max(120, dt)) };
      command(g, '/api/gesture').catch((err) => toast(err.message));
      return;
    }
    const same = S.cycleAt && Math.hypot(p.x - S.cycleAt.x, p.y - S.cycleAt.y) < 6;
    if (!same) { S.cycle = hitTest(p.x, p.y); S.cyclePos = 0; S.cycleAt = p; }
    else if (S.cycle.length) S.cyclePos = (S.cyclePos + 1) % S.cycle.length;
    if (S.cycle.length) select(S.cycle[S.cyclePos], true);
  });
  boxCanvas.addEventListener('pointermove', (e) => {
    if (!S.data || S.mode !== 'inspect' || down) return;
    const p = toDevice(e);
    const h = hitTest(p.x, p.y)[0] || null;
    if (h !== S.hover) { S.hover = h; drawBoxes(); }
  });
  boxCanvas.addEventListener('pointerleave', () => { S.hover = null; drawBoxes(); });

  function setMode(mode) {
    if (S.mode === mode) return;
    if (mode === 'control' && S.source !== 'live') { toast('Switch to the device first'); return; }
    S.mode = mode;
    $('modeInspect').classList.toggle('active', mode === 'inspect');
    $('modeControl').classList.toggle('active', mode === 'control');
    $('controlBar').classList.toggle('hidden', mode !== 'control');
    boxCanvas.classList.toggle('control', mode === 'control');
    S.hover = null;
    layoutScreen();
    if (mode === 'control') {
      if (!WS.open) toast('WebSocket not connected');
      send({ t: 'stream', on: true, scale: 0.5 });
    } else {
      send({ t: 'stream', on: false });
      if (S.source === 'live') refresh();
    }
  }
  $('modeInspect').addEventListener('click', () => setMode('inspect'));
  $('modeControl').addEventListener('click', () => setMode('control'));

  async function typeText(enter) {
    const text = $('typeText').value;
    try {
      await command(enter ? { t: 'text', enter: true } : { t: 'text', text });
      if (!enter) $('typeText').value = '';
    } catch (err) { toast(err.message); }
  }
  $('btnSend').addEventListener('click', () => typeText(false));
  $('btnEnter').addEventListener('click', () => typeText(true));
  $('typeText').addEventListener('keydown', (e) => { if (e.key === 'Enter') typeText(false); });

  // tree

  const treeEl = $('tree'), treeInner = $('treeInner');

  function buildRows() {
    const rows = [];
    const walk = (n, depth) => {
      rows.push({ n, depth });
      if (n.ch && n.ch.length && !S.collapsed.has(n.xpath)) for (const c of n.ch) walk(c, depth + 1);
    };
    for (const w of S.wins) {
      if (!w.root) continue;
      const key = 'win:' + w.id;
      rows.push({ w, key, depth: 0 });
      if (!S.collapsed.has(key)) walk(w.root, 1);
    }
    S.rows = rows;
    treeInner.style.height = rows.length * ROW_H + 'px';
    let maxW = 0;
    for (const r of rows) {
      let chars;
      if (r.w) chars = 12 + (r.w.root.pkg || r.w.title || '').length;
      else {
        const n = r.n;
        chars = shortCls(n.cls).length + (n.rid ? shortId(n.rid).length + 3 : 0)
          + (n.text ? Math.min(n.text.length, 60) + 4 : n.desc ? Math.min(n.desc.length, 60) + 2 : 0)
          + (n.scrollable ? 8 : 0) + (n.editable ? 6 : 0) + (n.checkable ? 9 : 0);
      }
      maxW = Math.max(maxW, 4 + r.depth * 14 + 18 + chars * 7.6 + 24);
    }
    treeInner.style.width = Math.ceil(maxW) + 'px';
  }

  function renderTree() { buildRows(); paintTree(); }

  function paintTree() {
    const top = treeEl.scrollTop, h = treeEl.clientHeight;
    const a = Math.max(0, Math.floor(top / ROW_H) - 10);
    const b = Math.min(S.rows.length, Math.ceil((top + h) / ROW_H) + 10);
    let html = '';
    for (let k = a; k < b; k++) {
      const r = S.rows[k];
      const style = `top:${k * ROW_H}px;padding-left:${4 + r.depth * 14}px`;
      if (r.w) {
        const type = WIN_LABEL[r.w.type] ? r.w.type : 'other';
        const label = WIN_LABEL[r.w.type] || r.w.type.toUpperCase();
        const pkg = r.w.root.pkg || r.w.title || '';
        html += `<div class="tr win" data-row="${k}" style="${style}">${icon(S.collapsed.has(r.key) ? 'right' : 'drop', 'tw')}<span class="wl w-${type}">${esc(label)}</span><span class="wp">${esc(pkg)}</span></div>`;
        continue;
      }
      const n = r.n;
      const has = n.ch && n.ch.length;
      const cls = 'tr' + (n === S.selected ? ' sel' : '') + (S.matchSet.has(n) ? ' match' : '');
      const clsName = 'cls' + (n.clickable ? ' click' : '') + (n.visible ? '' : ' hid');
      let h2 = `<span class="${clsName}">${esc(shortCls(n.cls))}</span>`;
      if (n.rid) h2 += `<span class="rid">#${esc(shortId(n.rid))}</span>`;
      if (n.text) h2 += `<span class="str">"${esc(trunc(n.text, 60))}"</span>`;
      else if (n.desc) h2 += `<span class="dsc">${esc(trunc(n.desc, 60))}</span>`;
      if (n.scrollable) h2 += '<span class="tag">scroll</span>';
      if (n.editable) h2 += '<span class="tag">edit</span>';
      if (n.checkable) h2 += `<span class="tag">${n.checked ? 'checked' : 'check'}</span>`;
      html += `<div class="${cls}" data-row="${k}" style="${style}">${icon(S.collapsed.has(n.xpath) ? 'right' : 'drop', 'tw' + (has ? '' : ' none'))}${h2}</div>`;
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
    const el = e.target.closest('.tr');
    const r = el && S.rows[+el.dataset.row];
    if (!r) return;
    const key = r.w ? r.key : r.n.xpath;
    if (r.w || e.target.closest('.tw')) {
      if (r.w || (r.n.ch && r.n.ch.length)) {
        if (!S.collapsed.delete(key)) S.collapsed.add(key);
        renderTree();
      }
      if (r.w) return;
    }
    select(r.n, false);
  });
  treeInner.addEventListener('mousemove', (e) => {
    const el = e.target.closest('.tr');
    const r = el && S.rows[+el.dataset.row];
    const n = r && r.n ? r.n : null;
    if (n !== S.hover) { S.hover = n; drawBoxes(); }
  });
  treeInner.addEventListener('mouseleave', () => { S.hover = null; drawBoxes(); });

  function reveal(n) {
    let changed = S.collapsed.delete('win:' + n.winRef.id);
    for (let p = n.parent; p; p = p.parent) if (S.collapsed.delete(p.xpath)) changed = true;
    if (changed) buildRows();
    const k = S.rows.findIndex((r) => r.n === n);
    if (k >= 0) {
      const y = k * ROW_H;
      if (y < treeEl.scrollTop || y > treeEl.scrollTop + treeEl.clientHeight - ROW_H * 2) {
        treeEl.scrollTop = y - treeEl.clientHeight / 3;
      }
      const x = 4 + S.rows[k].depth * 14;
      if (x < treeEl.scrollLeft || x > treeEl.scrollLeft + treeEl.clientWidth - 120) treeEl.scrollLeft = Math.max(0, x - 40);
    }
    paintTree();
  }

  function select(n, fromScreen) {
    S.selected = n;
    if (n) reveal(n); else paintTree();
    if (!fromScreen) S.cycleAt = null;
    drawBoxes();
    renderProps();
    $('btnSaveNode').disabled = !n;
    renderSaved();
  }

  // props

  function renderProps() {
    const body = $('propBody');
    const n = S.selected;
    if (!n) { body.innerHTML = '<tr><td colspan="2" class="empty">Select an element</td></tr>'; return; }
    const b = n.b;
    const rows = [
      ['Activity', S.data.activity || ''],
      ['XPathLite', xpathLite(n)],
      ['Full Xpath', n.xpath],
      ['ClassName', n.cls],
      ['# element rect', JSON.stringify({ x: b[0], y: b[1], width: b[2] - b[0], height: b[3] - b[1] })],
      ['bounds', boundsStr(b)],
      ['index', n.index],
      ['text', n.text || ''],
      ['resource-id', n.rid || ''],
      ['package', n.pkg || ''],
      ['description', n.desc || ''],
    ];
    const opt = [['hint', n.hint], ['tooltip', n.tooltip], ['pane title', n.paneTitle], ['state description', n.stateDesc],
      ['error', n.error], ['unique id', n.uniqueId], ['labeled by', n.labeledBy],
      ['input type', n.inputType], ['max text length', n.maxTextLength],
      ['collection', n.collection && JSON.stringify(n.collection)],
      ['collection item', n.collectionItem && JSON.stringify(n.collectionItem)],
      ['range', n.range && JSON.stringify(n.range)],
      ['actions', n.actions && n.actions.join(', ')], ['extras', n.extras && n.extras.join(', ')]];
    let html = rows.map(([k, v]) => `<tr><td>${esc(k)}</td><td class="v">${esc(v)}</td></tr>`).join('');
    html += BOOL_PROPS.map(([k, f]) => {
      const v = !!n[f || k];
      return `<tr><td>${k}</td><td><span class="badge ${v ? 't' : 'f'}">${v}</span></td></tr>`;
    }).join('');
    html += opt.filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `<tr><td>${esc(k)}</td><td class="v">${esc(v)}</td></tr>`).join('');
    html += `<tr><td>window</td><td class="v">${esc(WIN_LABEL[n.winRef.type] || n.winRef.type)} · layer ${n.winRef.layer}</td></tr>`;
    body.innerHTML = html;
  }

  $('propBody').addEventListener('click', (e) => {
    const td = e.target.closest('td.v');
    if (td && td.textContent) copy(td.textContent);
  });

  // find

  function runFind() {
    const q = $('search').value.trim();
    let list = [];
    if (q && S.data) {
      if (q.startsWith('/') || q.startsWith('(')) {
        try { list = evalXpath(q); } catch (e) { list = []; }
      } else {
        const l = q.toLowerCase();
        list = S.nodes.filter((n) => n && ((n.text || '').toLowerCase().includes(l) || (n.rid || '').toLowerCase().includes(l) ||
          (n.desc || '').toLowerCase().includes(l) || (n.cls || '').toLowerCase().includes(l)));
      }
    }
    S.matches = list; S.matchSet = new Set(list); S.matchPos = -1;
    $('findCount').textContent = q ? String(list.length) : '';
  }

  function step(dir) {
    if (!S.matches.length) return;
    S.matchPos = (S.matchPos + dir + S.matches.length) % S.matches.length;
    select(S.matches[S.matchPos], false);
    $('findCount').textContent = `${S.matchPos + 1}/${S.matches.length}`;
  }

  let findTimer;
  $('search').addEventListener('input', () => {
    clearTimeout(findTimer);
    findTimer = setTimeout(() => { runFind(); paintTree(); drawBoxes(); }, 150);
  });
  $('search').addEventListener('keydown', (e) => { if (e.key === 'Enter') step(e.shiftKey ? -1 : 1); });
  $('btnPrev').addEventListener('click', () => step(-1));
  $('btnNext').addEventListener('click', () => step(1));

  // toolbar

  $('btnRefresh').addEventListener('click', refresh);
  $('btnLive').addEventListener('click', refresh);
  $('optGrid').addEventListener('change', drawBoxes);
  $('btnCopyXml').addEventListener('click', () => {
    if (!S.data) return;
    const doc = buildXml().cloneNode(true);
    doc.querySelectorAll('[ec-i]').forEach((el) => el.removeAttribute('ec-i'));
    copy("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n" + new XMLSerializer().serializeToString(doc));
  });
  document.querySelectorAll('[data-global]').forEach((b) => b.addEventListener('click', async () => {
    try {
      await command({ t: 'global', action: b.dataset.global }, '/api/global');
      if (S.mode === 'inspect') { await sleep(600); refresh(); }
    } catch (err) { toast(err.message); }
  }));

  document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.ctrlKey || e.metaKey || e.altKey) return;
    const n = S.selected;
    if (e.key === 'Escape') { if (!$('capModal').classList.contains('hidden')) closeCaptures(); else select(null, false); }
    else if (e.key === 'r') refresh();
    else if (e.key === 's' && n) saveNode();
    else if (n && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      const d = e.key === 'ArrowDown' ? 1 : -1;
      for (let j = S.rows.findIndex((r) => r.n === n) + d; j >= 0 && j < S.rows.length; j += d) {
        if (S.rows[j].n) { select(S.rows[j].n, false); break; }
      }
      e.preventDefault();
    } else if (n && e.key === 'ArrowLeft') {
      if (n.ch && n.ch.length && !S.collapsed.has(n.xpath)) { S.collapsed.add(n.xpath); renderTree(); }
      else if (n.parent) select(n.parent, false);
      e.preventDefault();
    } else if (n && e.key === 'ArrowRight' && n.ch && n.ch.length) {
      if (S.collapsed.delete(n.xpath)) renderTree(); else select(n.ch[0], false);
      e.preventDefault();
    }
  });

  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { layoutScreen(); paintTree(); }, 100);
  });

  document.addEventListener('visibilitychange', () => {
    if (S.mode === 'control') send({ t: 'stream', on: !document.hidden, scale: 0.5 });
  });

  // resizable columns

  const mainEl = document.querySelector('main');
  const widths = (() => {
    try { return JSON.parse(localStorage.getItem('ec.widths')) || [340, 420]; } catch (e) { return [340, 420]; }
  })();
  function applyWidths() {
    mainEl.style.setProperty('--w0', widths[0] + 'px');
    mainEl.style.setProperty('--w1', widths[1] + 'px');
  }
  applyWidths();
  let relayout = 0;
  document.querySelectorAll('.gutter').forEach((g) => {
    g.addEventListener('pointerdown', (e) => {
      const i = +g.dataset.gutter, startX = e.clientX, start = widths[i];
      const total = mainEl.clientWidth - 48;
      g.setPointerCapture(e.pointerId);
      g.classList.add('drag');
      document.body.classList.add('resizing');
      const move = (ev) => {
        widths[i] = Math.max(240, Math.min(start + ev.clientX - startX, total - widths[1 - i] - 220));
        applyWidths();
        if (!relayout) relayout = requestAnimationFrame(() => { relayout = 0; layoutScreen(); paintTree(); });
      };
      g.addEventListener('pointermove', move);
      g.addEventListener('pointerup', () => {
        g.removeEventListener('pointermove', move);
        g.classList.remove('drag');
        document.body.classList.remove('resizing');
        try { localStorage.setItem('ec.widths', JSON.stringify(widths)); } catch (err) { /* ignore */ }
        layoutScreen();
      }, { once: true });
    });
    g.addEventListener('dblclick', () => { widths[0] = 340; widths[1] = 420; applyWidths(); layoutScreen(); });
  });

  // saved nodes

  function showTab(saved) {
    $('tabProps').classList.toggle('active', !saved);
    $('tabSaved').classList.toggle('active', saved);
    $('propsView').classList.toggle('hidden', saved);
    $('savedView').classList.toggle('hidden', !saved);
  }
  $('tabProps').addEventListener('click', () => showTab(false));
  $('tabSaved').addEventListener('click', () => showTab(true));

  async function loadSaved() {
    try { S.saved = await http('/api/nodes'); } catch (e) { S.saved = []; }
    renderSaved();
  }

  async function persistSaved() {
    try { await http('/api/nodes', S.saved); } catch (e) { toast('Save failed: ' + e.message); }
    renderSaved();
  }

  function saveNode() {
    const n = S.selected;
    if (!n) return;
    const label = shortId(n.rid) || n.text || n.desc || '';
    S.saved.unshift({
      id: Date.now(), name: shortCls(n.cls) + (label ? ' ' + trunc(label, 30) : ''),
      xpathLite: xpathLite(n), xpath: n.xpath,
      resourceId: n.rid || '', text: n.text || '', contentDesc: n.desc || '', className: n.cls, package: n.pkg || '',
      activity: S.data.activity || '', bounds: boundsStr(n.b), clickable: !!n.clickable,
      source: S.source, time: Date.now(),
    });
    persistSaved();
    toast('Node saved');
  }
  $('btnSaveNode').addEventListener('click', saveNode);

  function renderSaved() {
    $('savedCount').textContent = S.saved.length ? `(${S.saved.length})` : '';
    if (!S.saved.length) {
      $('savedList').innerHTML = '<div class="empty">No saved nodes. Select an element and press Save node.</div>';
      return;
    }
    const cur = S.selected ? S.selected.xpath : null;
    $('savedList').innerHTML = S.saved.map((v) => `<div class="sv${v.xpath === cur ? ' cur' : ''}" data-id="${v.id}">
      <div class="top"><span class="nm" title="${esc(v.name)}">${esc(v.name)}</span>
        <button data-act="find" title="Select on current screen">${icon('search')}</button>
        <button data-act="rename" title="Rename">${icon('edit')}</button>
        <button data-act="delete" class="del" title="Delete">${icon('delete')}</button></div>
      <div class="loc" data-copy="${esc(v.xpathLite)}">${esc(v.xpathLite)}</div>
      ${v.resourceId ? `<div class="loc" data-copy="${esc(v.resourceId)}">${esc(v.resourceId)}</div>` : ''}
      <div class="ctx">${esc(shortCls(v.activity || v.package))} · ${esc(v.bounds)}</div></div>`).join('');
  }

  $('savedList').addEventListener('click', (e) => {
    const loc = e.target.closest('[data-copy]');
    if (loc) { copy(loc.dataset.copy); return; }
    const b = e.target.closest('[data-act]');
    const card = e.target.closest('.sv');
    if (!b || !card) return;
    const v = S.saved.find((x) => String(x.id) === card.dataset.id);
    if (!v) return;
    if (b.dataset.act === 'delete') {
      S.saved = S.saved.filter((x) => x !== v);
      persistSaved();
    } else if (b.dataset.act === 'find') {
      let n = S.nodes.find((x) => x && x.xpath === v.xpath);
      if (!n && S.data) { try { n = evalXpath(v.xpathLite)[0]; } catch (err) { n = null; } }
      if (n) select(n, false); else toast('Not on this screen');
    } else if (b.dataset.act === 'rename') {
      const nm = card.querySelector('.nm');
      nm.innerHTML = `<input value="${esc(v.name)}">`;
      const input = nm.firstChild;
      input.focus();
      input.select();
      input.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter') input.blur();
        else if (ev.key === 'Escape') { input.value = ''; input.blur(); }
      });
      input.addEventListener('blur', () => {
        if (input.value.trim()) v.name = input.value.trim();
        persistSaved();
      }, { once: true });
    }
  });

  function constName(name, used) {
    let c = name.normalize('NFD').replace(/[̀-ͯ]/g, '').toUpperCase()
      .replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'NODE';
    if (/^[0-9]/.test(c)) c = 'N_' + c;
    let out = c, k = 2;
    while (used.has(out)) out = `${c}_${k++}`;
    used.add(out);
    return out;
  }

  $('btnCopyCode').addEventListener('click', () => {
    if (!S.saved.length) return;
    const used = new Set();
    const lines = ['from appium.webdriver.common.appiumby import AppiumBy', ''];
    for (const v of S.saved) lines.push(`${constName(v.name, used)} = (AppiumBy.XPATH, ${JSON.stringify(v.xpathLite)})`);
    copy(lines.join('\n') + '\n');
  });

  function saveBlob(blob, name) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  $('btnExportSaved').addEventListener('click', () => {
    saveBlob(new Blob([JSON.stringify(S.saved, null, 2)], { type: 'application/json' }), 'elementcheck_nodes.json');
  });

  $('btnDownload').addEventListener('click', () => {
    if (!S.data) return;
    const stamp = new Date(S.data.time || Date.now()).toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const base = `${(S.data.pkg || 'screen').replace(/[^\w.]/g, '_')}_${stamp}`;
    const json = JSON.stringify(S.data, (k, v) => (k === 'parent' || k === 'winRef' || k === 'nodes' ? undefined : v), 2);
    saveBlob(new Blob([json], { type: 'application/json' }), base + '.json');
    if (S.img) {
      const c = document.createElement('canvas');
      c.width = S.img.width;
      c.height = S.img.height;
      c.getContext('2d').drawImage(S.img, 0, 0);
      c.toBlob((b) => b && saveBlob(b, base + '.jpg'), 'image/jpeg', 0.9);
    }
  });

  renderProps();
  loadSaved();
  wsConnect();
  setTimeout(refresh, 300);
})();
