'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const ROW_H = 30;
  const BOOL_PROPS = [['checkable'], ['checked'], ['clickable'], ['enabled'], ['focusable'], ['focused'],
    ['long-clickable', 'longClickable'], ['scrollable'], ['selected'], ['password'], ['editable'], ['displayed', 'visible']];
  const EYE = '<svg class="eye" viewBox="0 0 24 24"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>';
  const EYE_OFF = '<svg class="eye" viewBox="0 0 24 24"><path d="M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.3 4.2M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7a9.8 9.8 0 0 0 5.4-1.6"/></svg>';

  const S = {
    data: null, source: 'live', img: null, nodes: [], wins: [],
    selected: null, hover: null, collapsed: new Set(), rows: [],
    matches: [], matchSet: new Set(), matchPos: -1, xml: null,
    inspect: true, cycle: [], cyclePos: 0, cycleAt: null, busy: false,
  };

  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const shortId = (r) => { const k = (r || '').indexOf(':id/'); return k >= 0 ? r.slice(k + 4) : (r || ''); };
  const trunc = (s, n) => (s.length > n ? s.slice(0, n) + '…' : s);
  const area = (b) => Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
  const boundsStr = (b) => `[${b[0]},${b[1]}][${b[2]},${b[3]}]`;

  function toast(msg) {
    const t = $('toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toast.h);
    toast.h = setTimeout(() => t.classList.remove('show'), 2000);
  }

  async function api(path, body, method) {
    const opt = body !== undefined
      ? { method: method || 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } }
      : { method: method || 'GET' };
    const j = await (await fetch(path, opt)).json();
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
        const all = evalXpath(tries[0]);
        const k = all.indexOf(n);
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
      if (S.source !== 'live') { S.source = 'live'; $('source').value = 'live'; }
      const d = await api('/api/snapshot?scale=0.6&q=80');
      setData(d, await loadImage(d.hasImage ? `/api/screen.jpg?seq=${d.seq}` : null));
      $('source').options[0].textContent = d.device || 'Device';
    } catch (e) {
      toast('Connection failed');
    } finally {
      S.busy = false;
      $('btnRefresh').disabled = false;
    }
  }

  async function loadCaptures(keep) {
    try {
      const list = await api('/api/captures');
      const sel = $('source');
      const first = sel.options[0].textContent;
      sel.innerHTML = `<option value="live">${esc(first)}</option>` + list.map((c) => {
        const m = c.meta || {};
        const t = m.time ? new Date(m.time).toLocaleString() : c.id;
        return `<option value="${esc(c.id)}">${esc(t)} · ${esc(m.pkg || '')}</option>`;
      }).join('');
      sel.value = keep || 'live';
    } catch (e) { /* offline */ }
  }

  async function openCapture(id) {
    S.source = id;
    try {
      const d = await api(`/api/captures/${id}/tree.json`);
      setData(d, await loadImage(d.hasImage ? `/api/captures/${id}/screen.jpg` : null));
    } catch (e) { toast('Failed to open capture'); }
  }

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
    const ctx = imgCanvas.getContext('2d');
    ctx.clearRect(0, 0, imgCanvas.width, imgCanvas.height);
    if (S.img) {
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(S.img, 0, 0, imgCanvas.width, imgCanvas.height);
    }
    drawBoxes();
  }

  function drawBoxes() {
    const ctx = boxCanvas.getContext('2d');
    const s = view.k * view.dpr;
    ctx.clearRect(0, 0, boxCanvas.width, boxCanvas.height);
    if (!S.data) return;
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
  boxCanvas.addEventListener('mousedown', (e) => { down = { p: toDevice(e), t: Date.now() }; });
  boxCanvas.addEventListener('mouseup', async (e) => {
    if (!down || !S.data) return;
    const p = toDevice(e), d = down;
    down = null;
    if (!S.inspect) {
      if (S.source !== 'live') return toast('Open the device to control it');
      const dist = Math.hypot(p.x - d.p.x, p.y - d.p.y), dt = Date.now() - d.t;
      const g = dist < 12
        ? { type: dt > 500 ? 'long' : 'tap', x: d.p.x, y: d.p.y, duration: dt > 500 ? dt : 0 }
        : { type: 'swipe', x: d.p.x, y: d.p.y, x2: p.x, y2: p.y, duration: Math.min(2000, Math.max(120, dt)) };
      try { await api('/api/gesture', g); setTimeout(refresh, 600); } catch (err) { toast(err.message); }
      return;
    }
    const same = S.cycleAt && Math.hypot(p.x - S.cycleAt.x, p.y - S.cycleAt.y) < 6;
    if (!same) { S.cycle = hitTest(p.x, p.y); S.cyclePos = 0; S.cycleAt = p; }
    else if (S.cycle.length) S.cyclePos = (S.cyclePos + 1) % S.cycle.length;
    if (S.cycle.length) select(S.cycle[S.cyclePos], true);
  });
  boxCanvas.addEventListener('mousemove', (e) => {
    if (!S.data || !S.inspect) return;
    const p = toDevice(e);
    const h = hitTest(p.x, p.y)[0] || null;
    if (h !== S.hover) { S.hover = h; drawBoxes(); }
  });
  boxCanvas.addEventListener('mouseleave', () => { S.hover = null; drawBoxes(); });

  // tree

  const treeEl = $('tree'), treeInner = $('treeInner');

  function buildRows() {
    const rows = [];
    const walk = (n, depth) => {
      rows.push({ n, depth });
      if (n.ch && n.ch.length && !S.collapsed.has(n.xpath)) for (const c of n.ch) walk(c, depth + 1);
    };
    if (S.data) {
      rows.push({ root: true, depth: 0 });
      if (!S.collapsed.has('/hierarchy')) for (const w of S.wins) if (w.root) walk(w.root, 1);
    }
    S.rows = rows;
    treeInner.style.height = rows.length * ROW_H + 'px';
  }

  function renderTree() { buildRows(); paintTree(); }

  function paintTree() {
    const top = treeEl.scrollTop, h = treeEl.clientHeight;
    const a = Math.max(0, Math.floor(top / ROW_H) - 10);
    const b = Math.min(S.rows.length, Math.ceil((top + h) / ROW_H) + 10);
    let html = '';
    for (let k = a; k < b; k++) {
      const r = S.rows[k];
      const pad = 8 + r.depth * 16;
      const style = `top:${k * ROW_H}px;padding-left:${pad}px`;
      if (r.root) {
        html += `<div class="tr" data-row="${k}" style="${style}"><span class="tw" data-tw="1">${S.collapsed.has('/hierarchy') ? '▶' : '▼'}</span>${EYE}<span class="cls">hierarchy</span></div>`;
        continue;
      }
      const n = r.n;
      const has = n.ch && n.ch.length;
      const cls = 'tr' + (n === S.selected ? ' sel' : '') + (S.matchSet.has(n) ? ' match' : '') + (n.visible ? '' : ' hid');
      let sub = n.rid ? '#' + shortId(n.rid) : n.text ? `"${trunc(n.text, 32)}"` : n.desc ? trunc(n.desc, 32) : '';
      html += `<div class="${cls}" data-row="${k}" style="${style}"><span class="tw" data-tw="1">${has ? (S.collapsed.has(n.xpath) ? '▶' : '▼') : ''}</span>${n.visible ? EYE : EYE_OFF}<span class="cls">${esc(n.cls || 'node')}</span>${sub ? `<span class="sub">${esc(sub)}</span>` : ''}</div>`;
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
    const key = r.root ? '/hierarchy' : r.n.xpath;
    if (r.root || e.target.dataset.tw) {
      if (r.root || (r.n.ch && r.n.ch.length)) {
        if (!S.collapsed.delete(key)) S.collapsed.add(key);
        renderTree();
      }
      if (r.root) return;
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
    let changed = S.collapsed.delete('/hierarchy');
    for (let p = n.parent; p; p = p.parent) if (S.collapsed.delete(p.xpath)) changed = true;
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
    if (!fromScreen) S.cycleAt = null;
    drawBoxes();
    renderProps();
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
    html += `<tr><td>window</td><td class="v">${esc(n.winRef.type)} · layer ${n.winRef.layer}</td></tr>`;
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

  $('btnInspect').addEventListener('click', () => {
    S.inspect = !S.inspect;
    $('btnInspect').classList.toggle('active', S.inspect);
    boxCanvas.classList.toggle('control', !S.inspect);
    S.hover = null; drawBoxes();
  });
  $('btnRefresh').addEventListener('click', refresh);
  $('btnCapture').addEventListener('click', async () => {
    const btn = $('btnCapture');
    btn.disabled = true;
    try {
      const r = await api('/api/capture', {});
      toast(`Saved · ${r.nodes} nodes`);
      await loadCaptures(S.source);
    } catch (err) { toast(err.message); }
    btn.disabled = false;
  });
  $('source').addEventListener('change', (e) => (e.target.value === 'live' ? refresh() : openCapture(e.target.value)));
  $('source').addEventListener('focus', () => loadCaptures(S.source));
  $('optGrid').addEventListener('change', drawBoxes);
  $('btnCopyXml').addEventListener('click', () => {
    if (!S.data) return;
    const doc = buildXml().cloneNode(true);
    doc.querySelectorAll('[ec-i]').forEach((el) => el.removeAttribute('ec-i'));
    copy("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n" + new XMLSerializer().serializeToString(doc));
  });
  document.querySelectorAll('[data-global]').forEach((b) => b.addEventListener('click', async () => {
    try { await api('/api/global', { action: b.dataset.global }); setTimeout(refresh, 700); } catch (err) { toast(err.message); }
  }));

  document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT' || e.ctrlKey || e.metaKey || e.altKey) return;
    const n = S.selected;
    if (e.key === 'r') refresh();
    else if (e.key === 'Escape') select(null, false);
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

  renderProps();
  refresh().then(() => loadCaptures('live'));
})();
