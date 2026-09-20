// 东盟跨境物流气象导航 · Service Worker
const CACHE = 'asean-weather-v1'
const ASSETS = [
  '/',
  '/index.html',
  '/driver.html',
  '/manifest.json'
]

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c => c.addAll(ASSETS).catch(() => {}))
  )
  self.skipWaiting()
})

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    )
  )
  self.clients.claim()
})

// 网络优先：API/tiles 直连，静态资源缓存兜底
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url)
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/tiles/')) return
  e.respondWith(
    fetch(e.request).catch(() => caches.match(e.request))
  )
})