// Leaflet 1.9 销毁/缩放竞态防护（一次性 monkeypatch，两端共用）。
//
// 本模块集中收敛「地图/图层被反复销毁重建」时 Leaflet 内部回调读到空引用的几类已知竞态：
//   1) Canvas 渲染器销毁后异步重绘读空 _ctx（reading 'save'）；
//   2) 瓦片层(GridLayer)已脱离地图(_map=null)却仍收到缩放事件，读空 _map（reading 'project'）；
//   3) 幽灵瓦片层/标记层的 _animateZoom 直接读 _map（reading '_latLngToNewLayerPoint'）。
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
// 竞态 3：幽灵图层的 _animateZoom 直接读 this._map
//         → reading '_latLngToNewLayerPoint' of null（缩放时黑屏崩屏）
//
// 现象：缩放动画期间偶发 TypeError: Cannot read properties of null
//       (reading '_latLngToNewLayerPoint') at GridLayer._animateZoom / Marker._animateZoom
//       ← Map.fire('zoomanim') ← Map._animateZoom。
//
// 成因：与竞态 2 同源的幽灵图层，但崩点更早——_animateZoom 函数体开头就直接
//       读 this._map._latLngToNewLayerPoint(...)，走不到已被守卫的 _setView。
//       两类崩溃点都已在线上堆栈中确认：
//         - GridLayer._animateZoom：幽灵瓦片层（缩放中途切底图/重建地图）；
//         - Marker._animateZoom：幽灵 divIcon 标记（口岸标签/风险点，图层组被
//           removeLayer 后退订未生效，仍收 zoomanim）。
//       这个异常会从 Map.fire('zoomanim') 的监听器列表中间打断广播，导致后续监听器
//       （含 Map 自身的 _animateZoom 收尾）收不到事件，地图卡在 leaflet-zoom-anim
//       状态 —— 观感就是「缩放时突然黑屏」。
//
// 处理：两个原型的 _animateZoom 顶部加同样的幂等守卫；应用层另用 _afterZoomAnim
//       把重建操作延迟到 zoomend，双保险。
if (L && L.GridLayer && L.GridLayer.prototype && !L.GridLayer.prototype.__animateZoomGuarded) {
  const gproto2 = L.GridLayer.prototype
  const origAnimateZoom = gproto2._animateZoom
  gproto2._animateZoom = function () {
    if (!this._map) return
    return origAnimateZoom.apply(this, arguments)
  }
  gproto2.__animateZoomGuarded = true
}

if (L && L.Marker && L.Marker.prototype && !L.Marker.prototype.__animateZoomGuarded) {
  const mkproto = L.Marker.prototype
  const origMarkerAnimateZoom = mkproto._animateZoom
  if (typeof origMarkerAnimateZoom === 'function') {
    mkproto._animateZoom = function () {
      // 已脱离地图（_map 为 null）：跳过，避免读空 _map._latLngToNewLayerPoint
      if (!this._map) return
      return origMarkerAnimateZoom.apply(this, arguments)
    }
  }
  mkproto.__animateZoomGuarded = true
}

// ---------------------------------------------------------------------------
// 竞态 3b：Tooltip/Popup（DivOverlay 系）_animateZoom 读空 _map（同一崩溃签名的另一处崩点）
//
// 现象：TypeError: Cannot read properties of null (reading '_latLngToNewLayerPoint')
//       at Tooltip._animateZoom / DivOverlay._animateZoom ← Map.fire('zoomanim')。
//
// 成因：Tooltip/Popup 通过 getEvents 订阅 zoomanim→_animateZoom、move→_updatePosition。
//       图层被 removeLayer / 地图销毁重建时若退订未生效（与竞态 2/3 同源，司机端
//       公水联运走廊 polyline.bindPopup 在导航开始整层移除后已在线上堆栈中确认），
//       幽灵浮层仍会收到缩放广播，_animateZoom 开头直接读
//       this._map._latLngToNewLayerPoint(...) → 对 null 取属性抛错，
//       从监听器列表中间打断 zoomanim 广播 → 地图卡在 leaflet-zoom-anim → 白屏。
//
// 处理：DivOverlay（Popup/Tooltip 共同父类）与 Tooltip 自身的 _animateZoom /
//       _updatePosition 顶部加同样的幂等守卫。
function __guardDivOverlayProto(proto, tag) {
  if (!proto || proto[tag]) return
  const origAnimate = proto._animateZoom
  if (typeof origAnimate === 'function') {
    proto._animateZoom = function () {
      if (!this._map) return
      return origAnimate.apply(this, arguments)
    }
  }
  const origUpdate = proto._updatePosition
  if (typeof origUpdate === 'function') {
    proto._updatePosition = function () {
      // _updatePosition 里 this._map.latLngToLayerPoint 同样读空 _map
      if (!this._map) return
      return origUpdate.apply(this, arguments)
    }
  }
  proto[tag] = true
}
if (L) {
  __guardDivOverlayProto(L.DivOverlay && L.DivOverlay.prototype, '__animateZoomGuarded')
  __guardDivOverlayProto(L.Tooltip && L.Tooltip.prototype, '__animateZoomGuarded')
}
