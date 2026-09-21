// Leaflet 1.9 Canvas 渲染器已知竞态防护（一次性 monkeypatch，两端共用）。
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
