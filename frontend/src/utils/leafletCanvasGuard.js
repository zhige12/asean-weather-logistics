// Leaflet 1.9 销毁/缩放竞态防护（一次性 monkeypatch，两端共用）。
//
// 本模块集中收敛「地图/图层被反复销毁重建」时 Leaflet 内部回调读到空引用的两类已知竞态：
//   1) Canvas 渲染器销毁后异步重绘读空 _ctx（reading 'save'）；
//   2) 瓦片层(GridLayer)已脱离地图(_map=null)却仍收到缩放事件，读空 _map（reading 'project'）；
//   3) MapLibre 桥接层已脱离地图(_glMap=null)却仍收到缩放/平移事件，读空 _glMap（reading 'jumpTo'）。
//
// 现象：控制台偶发 TypeError: Cannot read properties of undefined (reading 'save')
//       at Canvas._clear ← Canvas._redraw。
//
// 成因：Canvas 的重绘 _redraw 是通过 requestAnimFrame 异步排队的；而渲染器被移除时
//       Renderer.onRemove → _destroyContainer 会 cancelAnimFrame(this._redrawRequest) 且
//       delete this._ctx。存在竞态：当 viewreset→_reset→_updatePaths 在渲染器已销毁后被触发时，
//       会同步调用 _redraw → _clear → this._ctx.save()，此时 _ctx 已 undefined → 抛错。
//       （本项目的路线/风险线走 preferCanvas，且频繁 invalidateSize/setView + 地图随 v-if 反复销毁重建，
//        正好放大了这个销毁/重绘竞态。）
//
// 处理：_ctx 已不存在说明这层根本没有可画的绘图上下文，跳过本次重绘即可，不影响正常渲染。
import L from 'leaflet'

if (L && L.Canvas && L.Canvas.prototype && !L.Canvas.prototype.__ctxRedrawGuarded) {
  const proto = L.Canvas.prototype
  const origRedraw = proto._redraw
  proto._redraw = function () {
    // 渲染器已销毁（_ctx 被删）：清掉排队句柄后直接返回，避免下游 _clear/_draw 读空 _ctx
    if (!this._ctx) {
      this._redrawRequest = null
      return
    }
    return origRedraw.apply(this, arguments)
  }
  proto.__ctxRedrawGuarded = true
}

// ---------------------------------------------------------------------------
// 竞态 2：瓦片层(GridLayer)脱离地图后仍收到缩放事件 → reading 'project' of null
//
// 现象：缩放地图时偶发 TypeError: Cannot read properties of null (reading 'project')
//       at GridLayer._updateLevels（leaflet 内 `map.project(map.unproject(map.getPixelOrigin()), zoom)`，
//       其中 map = this._map）。
//
// 成因：GridLayer 通过 getEvents 订阅 zoomanim→_animateZoom、zoom/viewreset→_resetView。
//       正常 removeLayer 会在 fire('remove') 里 map.off(events) 退订；但当图层是在
//       map._loaded 为假、或地图正被销毁/重建（本项目：底图降级链切换、cycleTileSource、
//       _recoverNavMap 硬重建、地图随 v-if 反复销毁重建）时脱离的，退订可能未生效，
//       而 layer._map 已被置为 null。此后该「幽灵瓦片层」仍会收到缩放事件：
//         _animateZoom(e) → _setView(e.center, e.zoom, ...) → _updateLevels()
//       _animateZoom 全程不碰 this._map，直到 _updateLevels 里 `map = this._map` 才第一次
//       读它，于是 `map.project(...)` 直接对 null 取 project → 抛错（崩在 Leaflet 内部事件回调，
//       应用层 try/catch 兜不到）。
//
// 处理：在缩放期进入瓦片网格的两个入口 _setView / _resetView 顶部加幂等守卫——
//       _map 已不在说明这层已脱离地图、没有可更新的视图，直接跳过即可，不影响在图瓦片层。
if (L && L.GridLayer && L.GridLayer.prototype && !L.GridLayer.prototype.__detachedZoomGuarded) {
  const gproto = L.GridLayer.prototype
  const origSetView = gproto._setView
  gproto._setView = function () {
    // 已脱离地图（_map 为 null）：跳过，避免 _updateLevels/_resetGrid 读空 _map
    if (!this._map) return
    return origSetView.apply(this, arguments)
  }
  const origResetView = gproto._resetView
  gproto._resetView = function () {
    // _resetView 会先读 this._map.getCenter()，同样需守卫
    if (!this._map) return
    return origResetView.apply(this, arguments)
  }
  gproto.__detachedZoomGuarded = true
}

// ---------------------------------------------------------------------------
// 竞态 3：MapLibre 桥接层(@maplibre/maplibre-gl-leaflet)脱离地图后仍收缩放/平移事件
//         → reading 'jumpTo' of null
//
// 现象：缩放地图（尤其滚轮/程序化 setZoom）时偶发
//       TypeError: Cannot read properties of null (reading 'jumpTo')
//       at MaplibreGL._pinchZoom ← Map.fire('zoom') ← Map._move ← _resetView ← setView。
//
// 成因：L.MaplibreGL 通过 getEvents 订阅 move/zoomanim/zoom/zoomstart/zoomend/resize，
//       其中 zoom→_pinchZoom 会调 this._glMap.jumpTo(...)。而 onRemove 会
//       this._glMap.remove(); this._glMap = null。与竞态 2 同源：当桥接层在地图
//       销毁/重建、底图降级链切换、loadTiles 重挂等时机被移除，退订未完全生效而
//       _glMap 已置 null 时，该「幽灵桥接层」仍会收到 zoom 事件 → null.jumpTo 抛错。
//       （插件原版 _pinchZoom/_animateZoom/_zoomEnd 均未对 _glMap 做空判断。）
//
// 处理：给插件各缩放/平移回调顶部加幂等守卫——_map 或 _glMap 已不在则跳过，
//       无可渲染的 GL 地图，不影响在图桥接层。插件在本模块之前 import（MapView/DriverApp
//       均先 import '@maplibre/maplibre-gl-leaflet' 再 import 本文件），故 L.MaplibreGL 已注册。
if (L && L.MaplibreGL && L.MaplibreGL.prototype && !L.MaplibreGL.prototype.__detachedEventGuarded) {
  const mproto = L.MaplibreGL.prototype
  // 需要守卫的方法：均为地图事件回调，且依赖 _map 与 _glMap 同时存在才有意义
  const guardedMethods = ['_pinchZoom', '_animateZoom', '_zoomEnd', '_transitionEnd', '_update', '_transformGL', '_resize']
  guardedMethods.forEach((name) => {
    const orig = mproto[name]
    if (typeof orig !== 'function') return
    mproto[name] = function () {
      // 已脱离地图或 GL 子地图已销毁：跳过，避免读空 _glMap/_map
      if (!this._map || !this._glMap) return
      return orig.apply(this, arguments)
    }
  })
  mproto.__detachedEventGuarded = true
}
