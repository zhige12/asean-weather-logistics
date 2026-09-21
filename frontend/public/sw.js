// 东盟跨境物流气象导航 · Service Worker
const CACHE = 'asean-weather-v2'
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

// 网络优先：API/瓦片直连，静态资源缓存兜底
self.addEventListener('fetch', e => {
  const req = e.request
  const url = new URL(req.url)
  // 只接管本站同源请求：跨域瓦片（天地图 t*.tianditu.gov.cn / OSM 等）一律放行、交浏览器直连。
  // 否则下面 catch 命中不到缓存会返回 undefined → respondWith 抛「Failed to convert value to 'Response'」
  // → 浏览器把该请求判成 net::ERR_FAILED，导致在线底图整体加载失败并不断降级。
  if (url.origin !== self.location.origin) return
  // API / 本地矢量瓦片直连网络，不缓存
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/tiles/')) return
  // 同源静态资源：网络优先，失败回退缓存；都没有时返回合法的 Response.error()，绝不返回 undefined
  e.respondWith(
    fetch(req).catch(() => caches.match(req).then(hit => hit || Response.error()))
  )
})