// Local storage layer: everything lives in IndexedDB on this device.
const DB_NAME = 'games-library';
const DB_VERSION = 1;

let dbPromise;

function open() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains('items')) {
        const s = db.createObjectStore('items', { keyPath: 'id' });
        s.createIndex('type', 'type');
        s.createIndex('barcode', 'barcode');
      }
      if (!db.objectStoreNames.contains('settings')) {
        db.createObjectStore('settings', { keyPath: 'key' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

function tx(store, mode, fn) {
  return open().then(db => new Promise((resolve, reject) => {
    const t = db.transaction(store, mode);
    const s = t.objectStore(store);
    let result;
    Promise.resolve(fn(s)).then(r => { result = r; });
    t.oncomplete = () => resolve(result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  }));
}

function reqP(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export const db = {
  allItems: () => tx('items', 'readonly', s => reqP(s.getAll())),
  getItem: id => tx('items', 'readonly', s => reqP(s.get(id))),
  putItem: item => tx('items', 'readwrite', s => reqP(s.put(item))),
  deleteItem: id => tx('items', 'readwrite', s => reqP(s.delete(id))),
  clearItems: () => tx('items', 'readwrite', s => reqP(s.clear())),
  putMany: items => tx('items', 'readwrite', s => Promise.all(items.map(i => reqP(s.put(i))))),
  async findByBarcode(code) {
    if (!code) return [];
    return tx('items', 'readonly', s => reqP(s.index('barcode').getAll(code)));
  },
  async getSetting(key, fallback) {
    const row = await tx('settings', 'readonly', s => reqP(s.get(key)));
    return row ? row.value : fallback;
  },
  setSetting: (key, value) => tx('settings', 'readwrite', s => reqP(s.put({ key, value }))),
};

export function newId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Date.now().toString(36) + Math.random().toString(36).slice(2);
}
