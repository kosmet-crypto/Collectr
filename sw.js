/* Collectr service worker: offline support for the web version.
   Bump VERSION when shipping changes to the app shell list below. */
const VERSION = 'collectr-v1';
const SHELL = [
  './',
  './index.html',
  './manifest.json',
  './catalog/lego-foil.json',
  './vendor/xlsx.full.min.js',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './icons/apple-touch-icon.png'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function putInCache(req, res){
  if(res && (res.ok || res.type === 'opaque')){
    const copy = res.clone();
    caches.open(VERSION).then(c => c.put(req, copy));
  }
  return res;
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if(req.method !== 'GET') return;
  const url = new URL(req.url);

  // Page and catalog: network first so updates show up, cached copy when offline.
  if(req.mode === 'navigate' || (url.origin === self.location.origin && url.pathname.endsWith('.json'))){
    const key = req.mode === 'navigate' ? './index.html' : req;
    e.respondWith(fetch(req).then(res => putInCache(key, res)).catch(() => caches.match(key)));
    return;
  }

  // Same-origin assets and LEGO pictures: cache first, fill cache on miss.
  const isPic = url.hostname === 'img.bricklink.com' || url.hostname === 'cdn.rebrickable.com';
  if(url.origin === self.location.origin || isPic){
    e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => putInCache(req, res))));
  }
});
