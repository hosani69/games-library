// Metadata lookup from free, keyless public sources:
//   Open Library + Google Books (books), MusicBrainz + Cover Art Archive (soundtracks),
//   UPCitemdb trial (barcodes of games/consoles), Wikipedia + Wikidata (games, consoles, anything).
// Every function returns "candidates" in one shape so the UI can show them and merge them.

export const PLATFORMS = [
  'Nintendo Switch 2', 'Nintendo Switch', 'Wii U', 'Wii', 'GameCube', 'Nintendo 64', 'SNES', 'NES',
  'Nintendo 3DS', 'Nintendo DS', 'Game Boy Advance', 'Game Boy Color', 'Game Boy',
  'PlayStation 5', 'PlayStation 4', 'PlayStation 3', 'PlayStation 2', 'PlayStation', 'PS Vita', 'PSP',
  'Xbox Series X|S', 'Xbox One', 'Xbox 360', 'Xbox',
  'Sega Dreamcast', 'Sega Saturn', 'Sega Genesis / Mega Drive', 'Sega Master System', 'Game Gear',
  'Neo Geo', 'Atari 2600', 'PC', 'Steam Deck', 'Mobile',
];

// Patterns used to spot a platform inside a retail product title.
const PLATFORM_PATTERNS = [
  [/switch\s*2/i, 'Nintendo Switch 2'],
  [/\bswitch\b/i, 'Nintendo Switch'],
  [/\bwii\s*u\b/i, 'Wii U'],
  [/\bwii\b/i, 'Wii'],
  [/gamecube|\bngc\b/i, 'GameCube'],
  [/\b3ds\b/i, 'Nintendo 3DS'],
  [/\bnds\b|nintendo ds\b/i, 'Nintendo DS'],
  [/game ?boy advance|\bgba\b/i, 'Game Boy Advance'],
  [/playstation\s*5|\bps5\b/i, 'PlayStation 5'],
  [/playstation\s*4|\bps4\b/i, 'PlayStation 4'],
  [/playstation\s*3|\bps3\b/i, 'PlayStation 3'],
  [/playstation\s*2|\bps2\b/i, 'PlayStation 2'],
  [/\bvita\b/i, 'PS Vita'],
  [/\bpsp\b/i, 'PSP'],
  [/xbox series|\bxsx\b/i, 'Xbox Series X|S'],
  [/xbox one/i, 'Xbox One'],
  [/xbox 360/i, 'Xbox 360'],
  [/dreamcast/i, 'Sega Dreamcast'],
  [/\bpc\b|windows/i, 'PC'],
];

export function detectPlatform(text) {
  if (!text) return '';
  for (const [re, name] of PLATFORM_PATTERNS) if (re.test(text)) return name;
  return '';
}

// "Super Mario Odyssey - Nintendo Switch (Standard Edition)" -> "Super Mario Odyssey"
export function cleanRetailTitle(t) {
  if (!t) return '';
  let s = t.replace(/\[[^\]]*\]/g, ' ').replace(/\([^)]*\)/g, ' ');
  s = s.split(/\s[-–|]\s/)[0];
  for (const [re] of PLATFORM_PATTERNS) s = s.replace(re, ' ');
  s = s.replace(/\b(nintendo|sony|microsoft|playstation|xbox|video ?game|game|standard|edition|new|sealed|import)\b/gi, ' ');
  return s.replace(/\s+/g, ' ').trim() || t.trim();
}

export function isIsbn(code) {
  const c = (code || '').replace(/[^0-9X]/gi, '');
  return (c.length === 13 && /^97[89]/.test(c)) || (c.length === 10 && /^\d{9}[\dX]$/i.test(c));
}

const TIMEOUT = 12000;

async function getJson(url, opts = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT);
  try {
    const res = await fetch(url, { ...opts, signal: ctrl.signal, headers: { Accept: 'application/json', ...(opts.headers || {}) } });
    if (!res.ok) throw new Error(`${res.status} from ${new URL(url).hostname}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

function cand(source, fields) {
  return {
    source,
    title: '', creator: '', publisher: '', releaseDate: '', genre: '', platform: '',
    description: '', coverUrl: '', isbn: '', pages: '', sourceUrl: '', hltbUrl: '', suggestedType: '',
    ...Object.fromEntries(Object.entries(fields).filter(([, v]) => v !== undefined && v !== null)),
  };
}

const join = arr => (arr || []).filter(Boolean).join(', ');
const httpsify = u => (u || '').replace(/^http:\/\//, 'https://');

/* ---------- Books ---------- */

async function openLibraryIsbn(isbn) {
  const key = `ISBN:${isbn}`;
  const data = await getJson(`https://openlibrary.org/api/books?bibkeys=${key}&format=json&jscmd=data`);
  const b = data[key];
  if (!b) return [];
  return [cand('Open Library', {
    title: b.subtitle ? `${b.title}: ${b.subtitle}` : b.title,
    creator: join((b.authors || []).map(a => a.name)),
    publisher: join((b.publishers || []).map(p => p.name)),
    releaseDate: b.publish_date || '',
    genre: join((b.subjects || []).slice(0, 3).map(s => s.name)),
    pages: b.number_of_pages ? String(b.number_of_pages) : '',
    coverUrl: b.cover ? (b.cover.large || b.cover.medium) : '',
    isbn, sourceUrl: b.url || '', suggestedType: 'book',
  })];
}

async function googleBooks(q) {
  const data = await getJson(`https://www.googleapis.com/books/v1/volumes?maxResults=6&q=${encodeURIComponent(q)}`);
  return (data.items || []).map(it => {
    const v = it.volumeInfo || {};
    const isbn = (v.industryIdentifiers || []).find(x => x.type === 'ISBN_13') || (v.industryIdentifiers || [])[0];
    return cand('Google Books', {
      title: v.subtitle ? `${v.title}: ${v.subtitle}` : v.title,
      creator: join(v.authors), publisher: v.publisher || '', releaseDate: v.publishedDate || '',
      genre: join(v.categories), description: v.description || '',
      pages: v.pageCount ? String(v.pageCount) : '',
      coverUrl: httpsify(v.imageLinks && (v.imageLinks.thumbnail || v.imageLinks.smallThumbnail)),
      isbn: isbn ? isbn.identifier : '', sourceUrl: v.infoLink || '', suggestedType: 'book',
    });
  });
}

async function openLibrarySearch(title) {
  const fields = 'key,title,author_name,first_publish_year,cover_i,isbn,publisher,number_of_pages_median,subject';
  const data = await getJson(`https://openlibrary.org/search.json?limit=6&fields=${fields}&q=${encodeURIComponent(title)}`);
  return (data.docs || []).map(d => cand('Open Library', {
    title: d.title, creator: join(d.author_name), publisher: (d.publisher || [])[0] || '',
    releaseDate: d.first_publish_year ? String(d.first_publish_year) : '',
    genre: join((d.subject || []).slice(0, 3)),
    pages: d.number_of_pages_median ? String(d.number_of_pages_median) : '',
    coverUrl: d.cover_i ? `https://covers.openlibrary.org/b/id/${d.cover_i}-L.jpg` : '',
    isbn: (d.isbn || []).find(i => i.length === 13) || '',
    sourceUrl: `https://openlibrary.org${d.key}`, suggestedType: 'book',
  }));
}

/* ---------- Music / soundtracks ---------- */

function mbRelease(r) {
  return cand('MusicBrainz', {
    title: r.title,
    creator: join((r['artist-credit'] || []).map(a => a.name)),
    publisher: join([...new Set((r['label-info'] || []).map(l => l.label && l.label.name))]),
    releaseDate: r.date || '',
    genre: 'Soundtrack',
    platform: join([...new Set((r.media || []).map(m => m.format))]),
    description: [r.disambiguation, r.country && `Release country: ${r.country}`,
      r['track-count'] && `${r['track-count']} tracks`].filter(Boolean).join('. '),
    coverUrl: `https://coverartarchive.org/release/${r.id}/front-500`,
    sourceUrl: `https://musicbrainz.org/release/${r.id}`, suggestedType: 'ost',
  });
}

async function musicBrainz(query) {
  const data = await getJson(`https://musicbrainz.org/ws/2/release/?fmt=json&limit=6&query=${encodeURIComponent(query)}`);
  return (data.releases || []).filter(r => (r.score || 100) >= 60).map(mbRelease);
}

/* ---------- Retail barcodes ---------- */

async function upcItemDb(code) {
  const data = await getJson(`https://api.upcitemdb.com/prod/trial/lookup?upc=${encodeURIComponent(code)}`);
  return (data.items || []).map(it => {
    const cat = (it.category || '').toLowerCase();
    const suggestedType = /console/.test(cat) ? 'console'
      : /video game|games/.test(cat) ? 'game'
      : /music|cd|vinyl/.test(cat) ? 'ost'
      : /book/.test(cat) ? 'book' : '';
    return cand('UPCitemdb', {
      title: it.title, publisher: it.brand || '', description: it.description || '',
      platform: detectPlatform(it.title), coverUrl: httpsify((it.images || [])[0]),
      sourceUrl: `https://www.upcitemdb.com/upc/${code}`, suggestedType,
    });
  });
}

/* ---------- Wikipedia + Wikidata ---------- */

const WD_PROPS = {
  developer: 'P178', publisher: 'P123', genre: 'P136', platform: 'P400',
  date: 'P577', manufacturer: 'P176', inception: 'P571', author: 'P50', composer: 'P86', hltb: 'P2816',
};

async function wikidataFacts(qid) {
  const ent = await getJson(`https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&origin=*&props=claims&ids=${qid}`);
  const claims = (ent.entities && ent.entities[qid] && ent.entities[qid].claims) || {};
  const vals = p => (claims[p] || []).map(c => c.mainsnak && c.mainsnak.datavalue && c.mainsnak.datavalue.value).filter(Boolean);
  const ids = new Set();
  for (const k of ['developer', 'publisher', 'genre', 'platform', 'manufacturer', 'author', 'composer']) {
    vals(WD_PROPS[k]).slice(0, 6).forEach(v => v.id && ids.add(v.id));
  }
  let labels = {};
  if (ids.size) {
    const lab = await getJson(`https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&origin=*&props=labels&languages=en&ids=${[...ids].slice(0, 50).join('|')}`);
    for (const [id, e] of Object.entries(lab.entities || {})) labels[id] = e.labels && e.labels.en ? e.labels.en.value : '';
  }
  const names = k => vals(WD_PROPS[k]).slice(0, 6).map(v => labels[v.id]).filter(Boolean);
  const time = (vals(WD_PROPS.date)[0] || vals(WD_PROPS.inception)[0] || {}).time || '';
  const hltb = vals(WD_PROPS.hltb)[0];
  return {
    creator: join(names('developer').length ? names('developer') : names('manufacturer').length ? names('manufacturer') : names('author').length ? names('author') : names('composer')),
    publisher: join(names('publisher')),
    genre: join(names('genre').slice(0, 3)),
    platforms: names('platform'),
    releaseDate: time ? time.replace(/^\+/, '').slice(0, 10).replace(/-00/g, '') : '',
    hltbUrl: hltb ? `https://howlongtobeat.com/game/${hltb}` : '',
  };
}

async function wikipedia(query, type) {
  const url = 'https://en.wikipedia.org/w/api.php?action=query&format=json&origin=*&generator=search&gsrlimit=6'
    + '&prop=pageimages|extracts|pageprops&exintro=1&explaintext=1&exsentences=3&exlimit=max'
    + '&piprop=thumbnail&pithumbsize=600&ppprop=wikibase_item&gsrsearch=' + encodeURIComponent(query);
  const data = await getJson(url);
  const pages = Object.values((data.query && data.query.pages) || {}).sort((a, b) => a.index - b.index);
  return pages.map(p => cand('Wikipedia', {
    title: p.title.replace(/\s*\((video game|console|soundtrack|novel|book|album)\)$/i, ''),
    description: p.extract || '',
    coverUrl: p.thumbnail ? p.thumbnail.source : '',
    sourceUrl: `https://en.wikipedia.org/wiki/${encodeURIComponent(p.title.replace(/ /g, '_'))}`,
    suggestedType: type || '',
    _qid: p.pageprops && p.pageprops.wikibase_item,
  }));
}

// Adds Wikidata facts (developer, platforms, release date, HowLongToBeat link...) to a Wikipedia candidate.
export async function enrich(c, platformHint) {
  if (!c._qid || c._enriched) return c;
  try {
    const f = await wikidataFacts(c._qid);
    const platform = platformHint && f.platforms.includes(platformHint) ? platformHint
      : f.platforms.length === 1 ? f.platforms[0] : '';
    Object.assign(c, {
      creator: c.creator || f.creator, publisher: c.publisher || f.publisher, genre: c.genre || f.genre,
      releaseDate: c.releaseDate || f.releaseDate, platform: c.platform || platform,
      hltbUrl: c.hltbUrl || f.hltbUrl, allPlatforms: f.platforms,
    });
  } catch (e) { /* Wikidata is a bonus; keep the Wikipedia data */ }
  c._enriched = true;
  return c;
}

const WIKI_HINT = { game: 'video game', console: 'video game console', ost: 'soundtrack album', book: 'book' };

async function settle(promises) {
  const results = await Promise.allSettled(promises);
  const errors = results.filter(r => r.status === 'rejected').map(r => r.reason && r.reason.message);
  return { list: results.flatMap(r => (r.status === 'fulfilled' ? r.value : [])), errors };
}

/* ---------- Public API ---------- */

// Look up a scanned barcode. Returns { candidates, errors }.
export async function lookupBarcode(code, typeHint) {
  code = (code || '').trim();
  if (isIsbn(code) || typeHint === 'book') {
    const isbn = code.replace(/[^0-9X]/gi, '');
    let { list, errors } = await settle([openLibraryIsbn(isbn)]);
    if (!list.length) {
      const g = await settle([googleBooks(`isbn:${isbn}`)]);
      list = g.list; errors = errors.concat(g.errors);
    }
    return { candidates: list, errors };
  }
  const { list, errors } = await settle([upcItemDb(code), musicBrainz(`barcode:${code}`)]);
  // Retail titles are noisy; for games/consoles find the matching encyclopedia entry for richer data.
  const retail = list.find(c => c.source === 'UPCitemdb');
  if (retail && retail.suggestedType !== 'ost' && typeHint !== 'ost') {
    const type = typeHint || retail.suggestedType || 'game';
    const w = await settle([wikipedia(`${cleanRetailTitle(retail.title)} ${WIKI_HINT[type] || ''}`, type)]);
    const top = w.list[0];
    if (top) {
      await enrich(top, retail.platform);
      // Keep the barcode-specific platform and box photo, add encyclopedia facts.
      list.unshift({
        ...top, source: 'UPCitemdb + Wikipedia', platform: retail.platform || top.platform,
        coverUrl: top.coverUrl || retail.coverUrl, suggestedType: type,
      });
    }
  }
  return { candidates: list, errors };
}

// Search by title (typed by the user or taken from an existing item).
export async function searchTitle(title, type, platform) {
  title = (title || '').trim();
  if (!title) return { candidates: [], errors: [] };
  let jobs;
  if (type === 'book') jobs = [openLibrarySearch(title), googleBooks(title)];
  else if (type === 'ost') jobs = [musicBrainz(`release:(${title}) AND secondarytype:soundtrack`), musicBrainz(`release:(${title})`), wikipedia(`${title} soundtrack`, 'ost')];
  else jobs = [wikipedia(`${title} ${WIKI_HINT[type] || ''}`.trim(), type)];
  const { list, errors } = await settle(jobs);
  // De-duplicate by source + title.
  const seen = new Set();
  const candidates = list.filter(c => {
    const k = c.source + '|' + c.title.toLowerCase() + '|' + c.releaseDate;
    if (seen.has(k)) return false;
    seen.add(k); return true;
  });
  // Pre-enrich the first few Wikipedia hits so the picker shows developer/date.
  await Promise.all(candidates.filter(c => c._qid).slice(0, 3).map(c => enrich(c, platform)));
  return { candidates, errors };
}

const norm = s => (s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();

// Picks a candidate automatically only when it clearly matches (used by "update all missing info").
export function confidentMatch(title, candidates) {
  const t = norm(title);
  if (!t) return candidates[0] || null;
  return candidates.find(c => norm(c.title) === t)
    || candidates.find(c => norm(c.title).startsWith(t) || t.startsWith(norm(c.title)))
    || null;
}

export const META_FIELDS = ['title', 'creator', 'publisher', 'releaseDate', 'genre', 'platform', 'description', 'isbn', 'pages', 'sourceUrl', 'hltbUrl'];
