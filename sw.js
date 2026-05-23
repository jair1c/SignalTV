// SignalTV Service Worker — caché offline
const CACHE = 'signaltv-v1';

// Recursos estáticos a cachear en la instalación
const PRECACHE = [
  '/',
  '/index.html',
];

// ── INSTALL: cachear recursos estáticos ──
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(PRECACHE))
      .then(() => self.skipWaiting())
  );
});

// ── ACTIVATE: limpiar caches viejos ──
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// ── FETCH: Network-first con fallback a caché ──
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);

  // Solo manejar requests del mismo origen (no Firebase, no streams externos)
  if (url.origin !== self.location.origin) return;

  // Ignorar requests que no son GET
  if (e.request.method !== 'GET') return;

  e.respondWith(
    fetch(e.request)
      .then(response => {
        // Cachear respuesta exitosa
        if (response && response.status === 200 && response.type !== 'opaque') {
          const clone = response.clone();
          caches.open(CACHE).then(cache => cache.put(e.request, clone));
        }
        return response;
      })
      .catch(() => {
        // Sin red: servir desde caché
        return caches.match(e.request).then(cached => {
          if (cached) return cached;
          // Fallback: index.html para rutas de navegación
          if (e.request.mode === 'navigate') {
            return caches.match('/index.html');
          }
          return new Response('Sin conexión', { status: 503 });
        });
      })
  );
});
