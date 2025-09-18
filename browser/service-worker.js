const CACHE_VERSION = 'dashboard-v1';
const PRECACHE_URLS = [
  './',
  './dashboard.html',
  './dashboard.js',
  './WebRTCManager.js',
  './manifest.webmanifest',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE_VERSION)
      .then((cache) => cache.addAll(PRECACHE_URLS))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.map((key) => {
          if (key !== CACHE_VERSION) {
            return caches.delete(key);
          }
          return undefined;
        }),
      ),
    ),
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') {
    return;
  }

  const { request } = event;
  const url = new URL(request.url);

  if (url.origin !== self.location.origin) {
    return;
  }

  event.respondWith(
    fetch(request)
      .then((response) => {
        const responseClone = response.clone();
        caches.open(CACHE_VERSION).then((cache) => cache.put(request, responseClone));
        return response;
      })
      .catch(() => caches.match(request)),
  );
});

self.addEventListener('message', (event) => {
  const data = event.data;
  if (!data || data.type !== 'detection') {
    return;
  }
  const { count = 0, labels = 'objects', timestamp = Date.now() } = data;
  const title = `YOLO detections: ${count}`;
  const date = new Date(timestamp);
  const body = `${labels} @ ${date.toLocaleTimeString()}`;
  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      tag: `detection-${date.getTime()}`,
      renotify: false,
      data: { timestamp },
      icon: '../images/webrtc-android.png',
      vibrate: [80, 32, 120],
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if ('focus' in client) {
          return client.focus();
        }
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow('./dashboard.html');
      }
      return undefined;
    }),
  );
});
