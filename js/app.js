import { db, newId } from './db.js';
import { scanBarcode, imageToDataUrl, fetchCover } from './camera.js';
import { lookupBarcode, searchTitle, enrich, confidentMatch, PLATFORMS, META_FIELDS, isIsbn } from './meta.js';

/* ---------- Constants ---------- */

const TYPES = [
  { id: 'game', label: 'Game', plural: 'Games', icon: '🎮', creator: 'Developer' },
  { id: 'console', label: 'Console', plural: 'Consoles', icon: '🕹️', creator: 'Manufacturer' },
  { id: 'ost', label: 'Soundtrack', plural: 'Soundtracks', icon: '💿', creator: 'Composer / artist' },
  { id: 'book', label: 'Book', plural: 'Books', icon: '📘', creator: 'Author' },
  { id: 'accessory', label: 'Accessory', plural: 'Accessories', icon: '🎧', creator: 'Manufacturer' },
  { id: 'other', label: 'Other', plural: 'Other', icon: '📦', creator: 'Creator' },
];
const typeOf = id => TYPES.find(t => t.id === id) || TYPES[TYPES.length - 1];

const STATUSES = ['Sealed', 'New', 'Like new', 'Used', 'Damaged', 'Incomplete', 'Digital', 'Wishlist'];

const COMPLETION = [
  { id: 'unplayed', label: 'Not started' },
  { id: 'playing', label: 'Playing' },
  { id: 'paused', label: 'On hold' },
  { id: 'beaten', label: 'Beaten' },
  { id: 'completed', label: '100% done' },
  { id: 'dropped', label: 'Dropped' },
];
const compLabel = id => (COMPLETION.find(c => c.id === id) || COMPLETION[0]).label;
const isDone = s => s === 'beaten' || s === 'completed';

/* ---------- State ---------- */

const state = {
  items: [],
  type: 'all',
  query: '',
  status: '',
  completion: '',
  sort: 'added',
  currency: 'USD',
  editing: null, // item object being edited
};

/* ---------- Helpers ---------- */

const $ = sel => document.querySelector(sel);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const today = () => new Date().toISOString().slice(0, 10);
const num = v => { const n = parseFloat(v); return Number.isFinite(n) ? n : 0; };

function money(n) {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency: state.currency, maximumFractionDigits: 2 }).format(n);
  } catch {
    return `${state.currency} ${n.toFixed(2)}`;
  }
}

let toastTimer;
function toast(msg, ms = 2600) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}

function busy(on, msg = 'Looking it up…') {
  $('#busy').hidden = !on;
  $('#busy-msg').textContent = msg;
}

function openSheet(id) { $(id).hidden = false; }
function closeSheet(id) { $(id).hidden = true; }

document.addEventListener('click', e => {
  const close = e.target.closest('[data-close]');
  if (close) close.closest('.sheet-backdrop').hidden = true;
  else if (e.target.classList.contains('sheet-backdrop') && e.target.id !== 'editor') e.target.hidden = true;
});

function blankItem(type = 'game') {
  return {
    id: newId(), type, title: '', barcode: '', platform: '', region: '', logDate: today(), store: '',
    procurementCost: '', shippingCost: '', status: 'New',
    completion: { state: 'unplayed', percent: 0, hours: '', started: '', finished: '', rating: 0 },
    creator: '', publisher: '', releaseDate: '', genre: '', isbn: '', pages: '', description: '',
    cover: '', sourceUrl: '', hltbUrl: '', notes: '', metaSource: '',
    createdAt: Date.now(), updatedAt: Date.now(),
  };
}

const totalCost = i => num(i.procurementCost) + num(i.shippingCost);

function missingInfo(i) {
  return !i.cover || !i.releaseDate || !i.creator;
}

/* ---------- Library view ---------- */

function setupFilters() {
  const chips = $('#type-chips');
  chips.innerHTML = [{ id: 'all', plural: 'All' }, ...TYPES].map(t =>
    `<button class="chip${t.id === state.type ? ' on' : ''}" data-type="${t.id}">${esc(t.plural)}</button>`).join('');
  chips.onclick = e => {
    const b = e.target.closest('[data-type]');
    if (!b) return;
    state.type = b.dataset.type;
    chips.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', c === b));
    renderLibrary();
  };
  $('#filter-status').innerHTML = '<option value="">Any condition</option>' + STATUSES.map(s => `<option>${s}</option>`).join('');
  $('#filter-completion').innerHTML = '<option value="">Any progress</option>'
    + COMPLETION.map(c => `<option value="${c.id}">${c.label}</option>`).join('')
    + '<option value="backlog">Backlog (not finished)</option>';
  $('#search').oninput = e => { state.query = e.target.value; renderLibrary(); };
  $('#filter-status').onchange = e => { state.status = e.target.value; renderLibrary(); };
  $('#filter-completion').onchange = e => { state.completion = e.target.value; renderLibrary(); };
  $('#sort').onchange = e => { state.sort = e.target.value; renderLibrary(); };
  $('#platform-list').innerHTML = PLATFORMS.map(p => `<option value="${esc(p)}">`).join('');
}

function filtered() {
  const q = state.query.trim().toLowerCase();
  let list = state.items.filter(i => {
    if (state.type !== 'all' && i.type !== state.type) return false;
    if (state.status && i.status !== state.status) return false;
    if (state.completion) {
      if (i.type !== 'game') return false;
      const s = (i.completion || {}).state || 'unplayed';
      if (state.completion === 'backlog' ? (isDone(s) || s === 'dropped') : s !== state.completion) return false;
    }
    if (q) {
      const hay = [i.title, i.platform, i.barcode, i.creator, i.publisher, i.genre, i.store, i.notes, i.region].join(' ').toLowerCase();
      if (!q.split(/\s+/).every(w => hay.includes(w))) return false;
    }
    return true;
  });
  const by = {
    added: (a, b) => (b.logDate || '').localeCompare(a.logDate || '') || b.createdAt - a.createdAt,
    title: (a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }),
    cost: (a, b) => totalCost(b) - totalCost(a),
    release: (a, b) => (a.releaseDate || '9999').localeCompare(b.releaseDate || '9999'),
    platform: (a, b) => (a.platform || '~').localeCompare(b.platform || '~') || a.title.localeCompare(b.title),
  }[state.sort];
  return list.sort(by);
}

function coverHtml(i) {
  return i.cover
    ? `<img src="${esc(i.cover)}" alt="" loading="lazy" onerror="this.remove()">`
    : `<span>${typeOf(i.type).icon}</span>`;
}

function renderLibrary() {
  const list = filtered();
  const grid = $('#grid');
  $('#empty').hidden = state.items.length > 0;
  const spent = list.reduce((s, i) => s + totalCost(i), 0);
  $('#summary').textContent = state.items.length
    ? `${list.length} item${list.length === 1 ? '' : 's'} · ${money(spent)} spent`
    : '';
  grid.innerHTML = list.map(i => {
    const c = i.completion || {};
    const game = i.type === 'game';
    const compBadge = game && c.state && c.state !== 'unplayed'
      ? `<span class="badge right${isDone(c.state) ? ' done' : ''}">${esc(compLabel(c.state))}</span>` : '';
    const sub = [i.platform || typeOf(i.type).label, i.status].filter(Boolean).join(' · ');
    const bar = game && c.percent > 0 ? `<div class="bar"><i style="width:${Math.min(100, c.percent)}%"></i></div>` : '';
    return `<button class="item" data-id="${esc(i.id)}">
      <div class="cover">${coverHtml(i)}<span class="badge">${esc(typeOf(i.type).label)}</span>${compBadge}</div>
      <div class="meta"><div class="t">${esc(i.title || 'Untitled')}</div><div class="s">${esc(sub)}</div>${bar}</div>
    </button>`;
  }).join('');
  if (state.items.length && !list.length) grid.innerHTML = '<p class="muted">Nothing matches these filters.</p>';
}

$('#grid').addEventListener('click', e => {
  const card = e.target.closest('.item');
  if (!card) return;
  const item = state.items.find(i => i.id === card.dataset.id);
  if (item) openEditor(structuredClone(item), false);
});

/* ---------- Views / navigation ---------- */

function showView(name) {
  document.querySelectorAll('.view').forEach(v => { v.hidden = v.id !== `view-${name}`; });
  document.querySelectorAll('.tabbar [data-view]').forEach(b => b.classList.toggle('active', b.dataset.view === name));
  if (name === 'stats') renderStats();
  if (name === 'more') renderMore();
  window.scrollTo(0, 0);
}

document.querySelector('.tabbar').addEventListener('click', e => {
  const b = e.target.closest('button');
  if (!b) return;
  if (b.dataset.view) showView(b.dataset.view);
  if (b.dataset.action === 'add') openSheet('#add-sheet');
});

document.addEventListener('click', async e => {
  const a = e.target.closest('[data-action]');
  if (!a || a.closest('.tabbar')) return;
  const action = a.dataset.action;
  if (!['scan', 'photo', 'manual'].includes(action)) return;
  closeSheet('#add-sheet');
  const defaultType = state.type !== 'all' ? state.type : 'game';
  if (action === 'manual') return openEditor(blankItem(defaultType), true);
  if (action === 'photo') {
    const item = blankItem(defaultType);
    openEditor(item, true);
    $('#cover-camera').click();
    return;
  }
  const code = await scanBarcode();
  if (code) addFromBarcode(code, defaultType);
});

async function addFromBarcode(code, defaultType) {
  const existing = state.items.filter(i => i.barcode === code);
  const item = blankItem(isIsbn(code) ? 'book' : defaultType);
  item.barcode = code;
  if (isIsbn(code)) item.isbn = code;
  openEditor(item, true);
  if (existing.length) showDupe(existing);
  await runLookup(() => lookupBarcode(code, isIsbn(code) ? 'book' : ''), true);
}

function showDupe(existing) {
  const w = $('#dupe-warning');
  w.hidden = false;
  w.innerHTML = `You already have ${existing.length === 1 ? 'this item' : existing.length + ' of this item'}: <b>${esc(existing[0].title || existing[0].barcode)}</b>. Saving adds another copy.`;
}

/* ---------- Editor ---------- */

const form = $('#item-form');
const F = name => form.elements[name];

function openEditor(item, isNew) {
  state.editing = item;
  state.editingIsNew = isNew;
  $('#editor-title').textContent = isNew ? 'New item' : 'Edit item';
  $('#btn-delete').hidden = isNew;
  $('#dupe-warning').hidden = true;
  $('#overwrite').checked = false;
  fillForm(item);
  openSheet('#editor');
  form.scrollTop = 0;
  $('#editor .sheet').scrollTop = 0;
}

function fillForm(item) {
  for (const name of ['title', 'barcode', 'platform', 'region', 'logDate', 'store', 'procurementCost', 'shippingCost',
    'status', 'creator', 'publisher', 'releaseDate', 'genre', 'isbn', 'pages', 'description', 'notes']) {
    F(name).value = item[name] ?? '';
  }
  const c = item.completion || {};
  F('percent').value = c.percent || 0;
  F('hours').value = c.hours ?? '';
  F('started').value = c.started || '';
  F('finished').value = c.finished || '';
  renderTypeSeg();
  renderCompletionSeg();
  renderStars();
  renderCover();
  renderLinks();
  updateTotal();
  $('#percent-out').textContent = `${F('percent').value}%`;
}

// Copies the form back into state.editing.
function readForm() {
  const it = state.editing;
  for (const name of ['title', 'barcode', 'platform', 'region', 'logDate', 'store', 'procurementCost', 'shippingCost',
    'status', 'creator', 'publisher', 'releaseDate', 'genre', 'isbn', 'pages', 'description', 'notes']) {
    it[name] = F(name).value.trim();
  }
  it.completion = {
    ...(it.completion || {}),
    percent: Number(F('percent').value) || 0,
    hours: F('hours').value,
    started: F('started').value,
    finished: F('finished').value,
  };
  return it;
}

function renderTypeSeg() {
  const seg = $('#type-seg');
  const cur = state.editing.type;
  seg.innerHTML = TYPES.map(t => `<button type="button" data-t="${t.id}" class="${t.id === cur ? 'on' : ''}">${t.icon} ${t.label}</button>`).join('');
  document.querySelectorAll('#item-form [data-show]').forEach(el => {
    el.hidden = !el.dataset.show.split(' ').includes(cur);
  });
  $('#creator-label').textContent = typeOf(cur).creator;
}
$('#type-seg').onclick = e => {
  const b = e.target.closest('[data-t]');
  if (!b) return;
  readForm();
  state.editing.type = b.dataset.t;
  renderTypeSeg();
};

function renderCompletionSeg() {
  const cur = (state.editing.completion || {}).state || 'unplayed';
  $('#completion-seg').innerHTML = COMPLETION.map(c => `<button type="button" data-c="${c.id}" class="${c.id === cur ? 'on' : ''}">${c.label}</button>`).join('');
}
$('#completion-seg').onclick = e => {
  const b = e.target.closest('[data-c]');
  if (!b) return;
  readForm();
  const c = state.editing.completion;
  c.state = b.dataset.c;
  if (c.state === 'playing' && !c.started) c.started = today();
  if (isDone(c.state) && !c.finished) c.finished = today();
  if (c.state === 'completed') c.percent = 100;
  fillForm(state.editing);
};

function renderStars() {
  const r = (state.editing.completion || {}).rating || 0;
  $('#stars').innerHTML = [1, 2, 3, 4, 5].map(n => `<button type="button" data-r="${n}" class="${n <= r ? 'on' : ''}" aria-label="${n} star">★</button>`).join('');
}
$('#stars').onclick = e => {
  const b = e.target.closest('[data-r]');
  if (!b) return;
  const c = state.editing.completion;
  const n = Number(b.dataset.r);
  c.rating = c.rating === n ? 0 : n;
  renderStars();
};

F('percent').oninput = e => { $('#percent-out').textContent = `${e.target.value}%`; };
F('procurementCost').oninput = updateTotal;
F('shippingCost').oninput = updateTotal;
function updateTotal() {
  const t = num(F('procurementCost').value) + num(F('shippingCost').value);
  $('#total-line').textContent = t ? `Total cost: ${money(t)}` : '';
}

function renderCover() {
  const c = state.editing.cover;
  $('#cover-box').innerHTML = c ? `<img src="${esc(c)}" alt="Cover" onerror="this.replaceWith('No cover')">` : '<span>No cover</span>';
  $('#btn-cover-remove').hidden = !c;
}

function renderLinks() {
  const it = state.editing;
  const src = $('#source-link');
  src.hidden = !it.sourceUrl;
  if (it.sourceUrl) {
    src.href = it.sourceUrl;
    src.textContent = `Info from ${it.metaSource || new URL(it.sourceUrl).hostname} ↗`;
  }
  const h = $('#hltb-link');
  const q = it.title ? `https://howlongtobeat.com/?q=${encodeURIComponent(it.title)}` : '';
  h.hidden = !(it.hltbUrl || q);
  h.href = it.hltbUrl || q;
}

$('#btn-cover-photo').onclick = () => $('#cover-camera').click();
$('#btn-cover-pick').onclick = () => $('#cover-file').click();
$('#btn-cover-remove').onclick = () => { state.editing.cover = ''; renderCover(); };
for (const id of ['#cover-camera', '#cover-file']) {
  $(id).onchange = async e => {
    const file = e.target.files[0];
    e.target.value = '';
    if (!file) return;
    try {
      state.editing.cover = await imageToDataUrl(file);
      renderCover();
    } catch {
      toast('That file could not be read as an image');
    }
  };
}

$('#btn-scan-field').onclick = async () => {
  const code = await scanBarcode();
  if (!code) return;
  F('barcode').value = code;
  if (isIsbn(code) && !F('isbn').value) F('isbn').value = code;
  readForm();
  const existing = state.items.filter(i => i.barcode === code && i.id !== state.editing.id);
  if (existing.length) showDupe(existing);
  await runLookup(() => lookupBarcode(code, state.editing.type === 'book' || isIsbn(code) ? 'book' : ''), true);
};

$('#btn-find').onclick = async () => {
  const it = readForm();
  if (!it.title && !it.barcode) {
    toast('Type a title or scan a barcode first');
    F('title').focus();
    return;
  }
  await runLookup(async () => {
    let r = { candidates: [], errors: [] };
    if (it.barcode) r = await lookupBarcode(it.barcode, it.type === 'book' ? 'book' : '');
    if (!r.candidates.length && it.title) r = await searchTitle(it.title, it.type, it.platform);
    return r;
  }, false);
};

form.onsubmit = async e => {
  e.preventDefault();
  const it = readForm();
  if (!it.title) {
    toast('Give it a title first');
    F('title').focus();
    return;
  }
  it.updatedAt = Date.now();
  await db.putItem(it);
  const idx = state.items.findIndex(i => i.id === it.id);
  if (idx >= 0) state.items[idx] = it; else state.items.push(it);
  await markChanged();
  closeSheet('#editor');
  renderLibrary();
  toast(state.editingIsNew ? `Added “${it.title}”` : 'Saved');
};

$('#btn-delete').onclick = async () => {
  const it = state.editing;
  if (!confirm(`Delete “${it.title || 'this item'}”? This cannot be undone unless you have a backup.`)) return;
  await db.deleteItem(it.id);
  state.items = state.items.filter(i => i.id !== it.id);
  await markChanged();
  closeSheet('#editor');
  renderLibrary();
  toast('Deleted');
};

/* ---------- Metadata lookup + picker ---------- */

async function runLookup(fn, auto) {
  if (!navigator.onLine) {
    toast('You are offline. Fill it in by hand, or use “Find info” later.');
    return;
  }
  busy(true);
  let res;
  try {
    res = await fn();
  } catch (e) {
    res = { candidates: [], errors: [e.message] };
  } finally {
    busy(false);
  }
  const { candidates, errors } = res;
  if (!candidates.length) {
    toast(auto
      ? 'No match found for that barcode. Type the title and tap “Find info”.'
      : errors.length ? 'Lookup failed. Check your connection and try again.' : 'No matches found. Try a shorter title.', 4000);
    if (auto) F('title').focus();
    return;
  }
  showPicker(candidates);
}

function showPicker(candidates) {
  $('#picker-msg').textContent = 'Tap the right one. Only empty fields are filled unless you tick the box above.';
  $('#picker-list').innerHTML = candidates.map((c, n) => {
    const sub = [c.creator, c.platform, c.releaseDate && c.releaseDate.slice(0, 4), c.publisher].filter(Boolean).join(' · ');
    return `<button type="button" class="cand" data-n="${n}">
      <div class="thumb">${c.coverUrl ? `<img src="${esc(c.coverUrl)}" alt="" loading="lazy" onerror="this.remove()">` : ''}</div>
      <div><div class="src">${esc(c.source)}</div><div class="t">${esc(c.title)}</div><div class="s">${esc(sub)}</div>
      ${c.description ? `<div class="d">${esc(c.description)}</div>` : ''}</div>
    </button>`;
  }).join('');
  $('#picker-list').onclick = async e => {
    const b = e.target.closest('.cand');
    if (!b) return;
    const c = candidates[Number(b.dataset.n)];
    closeSheet('#picker');
    busy(true, 'Filling in details…');
    try {
      await enrich(c, F('platform').value);
      await applyCandidate(readForm(), c, $('#overwrite').checked);
    } finally {
      busy(false);
    }
    fillForm(state.editing);
    toast('Details filled in. Check them and tap Save.');
  };
  openSheet('#picker');
}

async function applyCandidate(item, c, overwrite) {
  const set = (field, value) => {
    if (!value) return;
    if (overwrite || !item[field]) item[field] = value;
  };
  for (const f of META_FIELDS) set(f, c[f]);
  if (c.suggestedType && (overwrite || item.type === 'other')) item.type = c.suggestedType;
  if ((overwrite || !item.cover) && c.coverUrl) item.cover = await fetchCover(c.coverUrl) || item.cover;
  item.metaSource = c.source;
  return item;
}

/* ---------- Bulk "update missing info" ---------- */

const sleep = ms => new Promise(r => setTimeout(r, ms));

$('#btn-bulk-meta').onclick = async () => {
  const btn = $('#btn-bulk-meta');
  const out = $('#bulk-progress');
  if (!navigator.onLine) { out.textContent = 'You are offline.'; return; }
  const todo = state.items.filter(missingInfo);
  if (!todo.length) { out.textContent = 'Every item already has a cover, release date and creator.'; return; }
  btn.disabled = true;
  let updated = 0;
  for (let n = 0; n < todo.length; n++) {
    const it = structuredClone(todo[n]);
    out.textContent = `Checking ${n + 1} of ${todo.length}: ${it.title || it.barcode}`;
    try {
      let match = null;
      if (it.barcode) {
        // A barcode is an exact identifier, so its first result is trusted.
        const r = await lookupBarcode(it.barcode, it.type === 'book' ? 'book' : '');
        match = r.candidates[0] || null;
      }
      if (!match && it.title) {
        let r;
        r = await searchTitle(it.title, it.type, it.platform);
        match = confidentMatch(it.title, r.candidates);
      }
      if (match) {
        await enrich(match, it.platform);
        const before = JSON.stringify(it);
        const title = it.title;
        await applyCandidate(it, match, false);
        it.title = title || it.title;
        if (JSON.stringify(it) !== before) {
          it.updatedAt = Date.now();
          await db.putItem(it);
          state.items[state.items.findIndex(i => i.id === it.id)] = it;
          updated++;
        }
      }
    } catch { /* skip this one */ }
    await sleep(1100); // be polite to the free services (MusicBrainz asks for 1 request per second)
  }
  btn.disabled = false;
  if (updated) await markChanged();
  out.textContent = `Done. Updated ${updated} of ${todo.length} item${todo.length === 1 ? '' : 's'}. Items without a clear match were left alone; open them and use “Find info” to pick one yourself.`;
  renderLibrary();
};

/* ---------- Stats ---------- */

function hbar(label, value, max, right) {
  const pct = max ? Math.round((value / max) * 100) : 0;
  return `<div class="hbar"><span>${esc(label)}</span><div class="track"><i style="width:${pct}%"></i></div><span class="v">${esc(right)}</span></div>`;
}

function renderStats() {
  const items = state.items;
  const el = $('#view-stats');
  if (!items.length) {
    el.innerHTML = '<div class="empty"><p class="big">No stats yet</p><p>Add a few items first.</p></div>';
    return;
  }
  const spent = items.reduce((s, i) => s + totalCost(i), 0);
  const shipping = items.reduce((s, i) => s + num(i.shippingCost), 0);
  const games = items.filter(i => i.type === 'game');
  const done = games.filter(g => isDone((g.completion || {}).state));
  const hours = games.reduce((s, g) => s + num((g.completion || {}).hours), 0);
  const backlog = games.filter(g => !isDone((g.completion || {}).state) && (g.completion || {}).state !== 'dropped' && g.status !== 'Wishlist');

  const byType = TYPES.map(t => {
    const list = items.filter(i => i.type === t.id);
    return { t, n: list.length, cost: list.reduce((s, i) => s + totalCost(i), 0) };
  }).filter(x => x.n);
  const maxType = Math.max(...byType.map(x => x.n));

  const byComp = COMPLETION.map(c => ({ c, n: games.filter(g => ((g.completion || {}).state || 'unplayed') === c.id).length }));
  const maxComp = Math.max(1, ...byComp.map(x => x.n));

  const byStatus = STATUSES.map(s => ({ s, n: items.filter(i => i.status === s).length })).filter(x => x.n);
  const maxStatus = Math.max(1, ...byStatus.map(x => x.n));

  const platforms = {};
  games.forEach(g => { const p = g.platform || 'Unknown'; platforms[p] = (platforms[p] || 0) + 1; });
  const topPlatforms = Object.entries(platforms).sort((a, b) => b[1] - a[1]).slice(0, 8);
  const maxPlat = Math.max(1, ...topPlatforms.map(x => x[1]));

  el.innerHTML = `
    <div class="stat-grid">
      <div class="stat"><div class="n">${items.length}</div><div class="l">Items</div></div>
      <div class="stat"><div class="n">${money(spent)}</div><div class="l">Total spent</div></div>
      <div class="stat"><div class="n">${money(shipping)}</div><div class="l">of it on shipping</div></div>
      <div class="stat"><div class="n">${games.length ? Math.round((done.length / games.length) * 100) : 0}%</div><div class="l">Games finished (${done.length}/${games.length})</div></div>
      <div class="stat"><div class="n">${backlog.length}</div><div class="l">Games in backlog</div></div>
      <div class="stat"><div class="n">${Math.round(hours)}</div><div class="l">Hours played</div></div>
    </div>
    <div class="card"><h2>By type</h2>${byType.map(x => hbar(x.t.plural, x.n, maxType, `${x.n} · ${money(x.cost)}`)).join('')}</div>
    ${games.length ? `<div class="card"><h2>Game completion</h2>${byComp.map(x => hbar(x.c.label, x.n, maxComp, x.n)).join('')}</div>` : ''}
    ${topPlatforms.length ? `<div class="card"><h2>Games by platform</h2>${topPlatforms.map(([p, n]) => hbar(p, n, maxPlat, n)).join('')}</div>` : ''}
    ${byStatus.length ? `<div class="card"><h2>Condition</h2>${byStatus.map(x => hbar(x.s, x.n, maxStatus, x.n)).join('')}</div>` : ''}
  `;
}

/* ---------- Backup / restore ---------- */

async function markChanged() {
  await db.setSetting('lastChange', Date.now());
  renderBackupNag();
}

function daysAgo(ts) {
  return Math.floor((Date.now() - ts) / 86400000);
}

async function renderBackupNag() {
  const last = await db.getSetting('lastBackup', 0);
  const changed = await db.getSetting('lastChange', 0);
  const nag = $('#backup-nag');
  const stale = state.items.length && changed > last && (!last || daysAgo(last) >= 14 || state.items.length - (await db.getSetting('lastBackupCount', 0)) >= 10);
  nag.hidden = !stale;
  if (stale) {
    nag.innerHTML = `${last ? `Last backup was ${daysAgo(last)} days ago.` : 'You have not saved a backup yet.'} Your library only lives on this device. <button class="btn" id="nag-backup">Back up now</button>`;
    $('#nag-backup').onclick = exportBackup;
  }
}

async function renderMore() {
  const last = await db.getSetting('lastBackup', 0);
  $('#backup-info').textContent = last
    ? `Last backup: ${new Date(last).toLocaleString()} (${await db.getSetting('lastBackupCount', 0)} items)`
    : 'No backup saved yet.';
  $('#set-currency').value = state.currency;
  if (navigator.storage && navigator.storage.estimate) {
    const { usage } = await navigator.storage.estimate();
    const persisted = navigator.storage.persisted ? await navigator.storage.persisted() : false;
    $('#storage-info').textContent = `Using ${(usage / 1048576).toFixed(1)} MB on this device. ${persisted ? 'Storage is protected from automatic clean-up.' : ''}`;
  }
}

function download(blob, name) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

const native = window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform();

// Android app: write into Documents/GamesLibrary, or offer the share sheet if that folder is not writable.
async function saveFileNative(blob, name, title) {
  const { Filesystem, Share } = window.Capacitor.Plugins;
  const data = await blob.text();
  try {
    await Filesystem.writeFile({ path: `GamesLibrary/${name}`, data, directory: 'DOCUMENTS', encoding: 'utf8', recursive: true });
    toast(`Saved to Documents/GamesLibrary/${name}`, 4000);
    return true;
  } catch {
    const { uri } = await Filesystem.writeFile({ path: name, data, directory: 'CACHE', encoding: 'utf8' });
    try {
      await Share.share({ title, files: [uri] });
      return true;
    } catch {
      return false;
    }
  }
}

async function saveFile(blob, name, title) {
  if (native) return saveFileNative(blob, name, title);
  const file = new File([blob], name, { type: blob.type });
  // On phones, the share sheet lets you save straight to Files / Drive / a USB stick.
  if (navigator.canShare && navigator.canShare({ files: [file] }) && /Android|iPhone|iPad/i.test(navigator.userAgent)) {
    try {
      await navigator.share({ files: [file], title });
      return true;
    } catch (e) {
      if (e.name === 'AbortError') return false;
    }
  }
  download(blob, name);
  return true;
}

async function exportBackup() {
  const items = await db.allItems();
  const data = {
    app: 'games-library', version: 1, exportedAt: new Date().toISOString(),
    settings: { currency: state.currency }, items,
  };
  const blob = new Blob([JSON.stringify(data)], { type: 'application/json' });
  const ok = await saveFile(blob, `games-library-backup-${today()}.json`, 'Games Library backup');
  if (!ok) return;
  await db.setSetting('lastBackup', Date.now());
  await db.setSetting('lastBackupCount', items.length);
  renderBackupNag();
  renderMore();
  if (!native) toast(`Backup of ${items.length} items saved`);
}
$('#btn-export').onclick = exportBackup;

$('#btn-csv').onclick = async () => {
  const cols = ['type', 'title', 'platform', 'barcode', 'logDate', 'procurementCost', 'shippingCost', 'total', 'status',
    'completion', 'percent', 'hours', 'started', 'finished', 'rating', 'store', 'region', 'creator', 'publisher',
    'releaseDate', 'genre', 'isbn', 'pages', 'notes'];
  const cell = v => {
    const s = String(v ?? '');
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const rows = state.items.map(i => {
    const c = i.completion || {};
    const r = { ...i, total: totalCost(i).toFixed(2), completion: i.type === 'game' ? compLabel(c.state) : '',
      percent: i.type === 'game' ? c.percent : '', hours: c.hours, started: c.started, finished: c.finished, rating: c.rating || '' };
    return cols.map(k => cell(r[k])).join(',');
  });
  const blob = new Blob(['﻿' + [cols.join(','), ...rows].join('\n')], { type: 'text/csv' });
  await saveFile(blob, `games-library-${today()}.csv`, 'Games Library spreadsheet');
};

$('#btn-import').onclick = () => $('#import-file').click();
$('#import-file').onchange = async e => {
  const file = e.target.files[0];
  e.target.value = '';
  if (!file) return;
  let data;
  try {
    data = JSON.parse(await file.text());
    if (data.app !== 'games-library' || !Array.isArray(data.items)) throw new Error();
  } catch {
    toast('That file is not a Games Library backup', 4000);
    return;
  }
  const replace = state.items.length
    ? confirm(`This backup has ${data.items.length} items.\n\nOK = replace your current library (${state.items.length} items) with the backup.\nCancel = merge: keep both, newer edits win.`)
    : true;
  const valid = data.items.filter(i => i && i.id && typeof i.title === 'string');
  if (replace) {
    await db.clearItems();
    await db.putMany(valid);
  } else {
    const mine = new Map(state.items.map(i => [i.id, i]));
    await db.putMany(valid.filter(i => !mine.has(i.id) || (i.updatedAt || 0) > (mine.get(i.id).updatedAt || 0)));
  }
  if (data.settings && data.settings.currency && replace) {
    state.currency = data.settings.currency;
    await db.setSetting('currency', state.currency);
  }
  state.items = await db.allItems();
  await db.setSetting('lastBackup', Date.now());
  await db.setSetting('lastBackupCount', state.items.length);
  renderLibrary();
  renderMore();
  renderBackupNag();
  toast(`Restored. You now have ${state.items.length} items.`, 3500);
};

$('#set-currency').onchange = async e => {
  const v = e.target.value.trim().toUpperCase();
  try {
    new Intl.NumberFormat(undefined, { style: 'currency', currency: v });
  } catch {
    toast('Use a 3-letter currency code like USD, EUR, GBP or AED');
    e.target.value = state.currency;
    return;
  }
  state.currency = v;
  await db.setSetting('currency', v);
  renderLibrary();
  toast(`Currency set to ${v}`);
};

/* ---------- Start ---------- */

function guessCurrency() {
  try {
    const region = new Intl.Locale(navigator.language).maximize().region;
    const map = { US: 'USD', GB: 'GBP', AE: 'AED', SA: 'SAR', KW: 'KWD', QA: 'QAR', BH: 'BHD', OM: 'OMR', JP: 'JPY', CA: 'CAD', AU: 'AUD', IN: 'INR', CH: 'CHF', EG: 'EGP',
      DE: 'EUR', FR: 'EUR', ES: 'EUR', IT: 'EUR', NL: 'EUR', IE: 'EUR', PT: 'EUR', BE: 'EUR', AT: 'EUR', FI: 'EUR' };
    return map[region] || 'USD';
  } catch {
    return 'USD';
  }
}

function updateOnline() { $('#offline-badge').hidden = navigator.onLine; }
window.addEventListener('online', updateOnline);
window.addEventListener('offline', updateOnline);

async function start() {
  state.currency = await db.getSetting('currency', null) || guessCurrency();
  state.items = await db.allItems();
  setupFilters();
  $('#status-select').innerHTML = STATUSES.map(s => `<option>${s}</option>`).join('');
  renderLibrary();
  renderBackupNag();
  updateOnline();
  // Ask the browser not to evict the library when space runs low.
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
  // Android file pickers often don't recognise .json, so let any file be chosen there.
  if (native) $('#import-file').removeAttribute('accept');
  if ('serviceWorker' in navigator && location.protocol !== 'file:' && !native) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
}

start();
