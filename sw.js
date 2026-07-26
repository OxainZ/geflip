/* GE Flip Terminal — service worker.
 * Two jobs: (1) make the app installable + shell-cached so it opens offline,
 * (2) host notifications so a backgrounded/installed PWA can still alert.
 * Prices are ALWAYS fetched live from the network — we never cache API data,
 * only the app shell, so a stale flip is impossible. */
const SHELL = 'geflip-shell-v2';
const SHELL_FILES = ['.', 'index.html', 'manifest.webmanifest',
  'geflip_icon.png', 'geflip_icon_192.png', 'geflip_icon_512.png'];

self.addEventListener('install', (e) => {
  self.skipWaiting();
  // cache each file independently — addAll is atomic, so one missing/renamed asset would
  // silently break the WHOLE offline shell. allSettled lets the rest cache regardless.
  e.waitUntil(caches.open(SHELL).then((c) =>
    Promise.allSettled(SHELL_FILES.map((f) => c.add(f)))));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  // NEVER serve API/price data from cache — always live. Only the same-origin shell is cached.
  const isApi = /prices\.runescape\.wiki|\/api\//.test(url.href);
  if (e.request.method !== 'GET' || isApi || url.origin !== self.location.origin) return;
  e.respondWith(
    fetch(e.request).then((res) => {
      // only cache a good response — never persist a transient 404/500 as the offline shell
      if (res && res.ok) {
        const clone = res.clone();
        caches.open(SHELL).then((c) => c.put(e.request, clone)).catch(() => {});
      }
      return res;
    }).catch(() => caches.match(e.request).then((m) => m || caches.match('index.html')))
  );
});

// tapping a notification focuses (or opens) the app
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((cs) => {
      for (const c of cs) if ('focus' in c) return c.focus();
      if (self.clients.openWindow) return self.clients.openWindow('.');
    })
  );
});
