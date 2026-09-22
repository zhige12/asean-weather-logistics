import L from 'leaflet';

/**
 * 导航路线"流动感"多层特效（支持按段着色）：
 *   1. 外发光层：半透明粗线，带呼吸脉冲
 *   2. 流光带：虚线层 CSS stroke-dashoffset 动画
 *   3. 核心线：细实线，路线主体
 *   4. 前进箭头：三角箭头沿路径匀速前进，自动旋转到路段朝向
 *   5. 起终点脉冲标记
 *
 * 按段着色（安全段绿 / 风险段红）：
 *   setSegments([{ latlngs: [[lat,lng],...], color: '#00e676' }, ...])
 * 兼容旧接口：setLatLngs(latlngs) / setColor(color) —— 单色整条路线。
 */

const STYLE_ID = 'route-flow-style';

function ensureStyle() {
  if (document.getElementById(STYLE_ID)) return;
  const css = `
.rf-outer { fill: none; stroke-linecap: round; pointer-events: none; }
.rf-outer-pulse { animation: rf-opulse 1.5s ease-in-out infinite; }
@keyframes rf-opulse { 0%,100% { opacity: 0.25; } 50% { opacity: 0.55; } }
.rf-glow { fill: none; stroke-linecap: round; pointer-events: none; }
.rf-glow-anim { animation: rf-dash 0.9s linear infinite; }
@keyframes rf-dash { from { stroke-dashoffset: 52; } to { stroke-dashoffset: 0; } }
.rf-core { fill: none; stroke-linecap: round; pointer-events: none; }
/* 主线深色描边（高德风）：比 core 宽出一圈，仅在调用方传了 casing 颜色时创建 */
.rf-casing { fill: none; stroke-linecap: round; pointer-events: none; }
.rf-arrow-outer {
  width: 0; height: 0; display: flex; align-items: center; justify-content: center;
  pointer-events: none;
}
.rf-arrow {
  position: absolute;
  width: 18px; height: 18px;
  background: currentColor;
  clip-path: polygon(0% 0%, 100% 50%, 0% 100%, 26% 50%);
  filter: drop-shadow(0 0 4px rgba(0,0,0,0.7)) drop-shadow(0 0 2px rgba(255,255,255,0.5));
  transform-origin: 50% 50%;
  will-change: transform;
}
/* V 形前进箭头（❯ -chevron）：导航线白色箭头用，旋转/推进逻辑与实心三角完同一套 */
.rf-chev {
  position: absolute;
  width: 15px; height: 15px;
  background: currentColor;
  clip-path: polygon(0% 0%, 35% 0%, 100% 50%, 35% 100%, 0% 100%, 62% 50%);
  filter: drop-shadow(0 1px 2px rgba(0,0,0,0.55));
  transform-origin: 50% 50%;
  will-change: transform;
}
.rf-chev-lite { filter: drop-shadow(0 1px 2px rgba(0,0,0,0.55)); }
.rf-arrow-pulse { animation: rf-apulse 1.15s ease-in-out infinite; }
@keyframes rf-apulse {
  0%, 100% { opacity: 0.5; filter: drop-shadow(0 0 2px rgba(0,0,0,0.5)); }
  50% { opacity: 1; filter: drop-shadow(0 0 6px rgba(0,0,0,0.8)) drop-shadow(0 0 3px rgba(255,255,255,0.7)); }
}
.rf-arrow-lite { filter: none; opacity: 0.95; }
.rf-endpoint-pulse { animation: rf-epulse 1.2s ease-in-out infinite; }
@keyframes rf-epulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .rf-outer-pulse, .rf-glow-anim, .rf-arrow-pulse, .rf-endpoint-pulse { animation: none; }
}
`;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = css;
  document.head.appendChild(style);
}

const DEG = Math.PI / 180;

function bearing(a, b) {
  const lat1 = a[0] * DEG;
  const lat2 = b[0] * DEG;
  const dLon = (b[1] - a[1]) * DEG;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

export function createRouteFlow(map, options = {}) {
  ensureStyle();
  const opts = Object.assign({
    color: '#2563eb',
    speedPxPerSec: 88,
    spacingPx: 110,
    minArrows: 3,
    maxArrows: 16,
    glowWidth: 10,
    outerWidth: 22,
    coreWidth: 6,
    pane: 'markerPane',
    // 箭头 JS 循环帧率上限：移动端 WebView 传 15 降 CPU，PC 保持 60
    fps: 60,
    // 轻量特效模式（移动端）：关闭箭头 drop-shadow 滤镜与脉冲/流光 CSS 动画，
    // 这些动画不受 fps 限帧约束、在手机 WebView 上是滑动掉帧主因。大屏保持 false。
    lite: false,
    // ---- 高德风主线（均为可选，不传保持旧样式，大屏不受影响）----
    // 描边色：段未自带 casing 时用这个；传了才画 casing 层
    casing: null,
    // casing 比 core 宽的像素数（两侧合计）
    casingBorder: 6,
    // 箭头形状：'solid' 实心三角（默认）/ 'chevron' V 形前进箭头
    arrowShape: 'solid',
    // 箭头颜色：默认跟随段色；导航线传 '#fff' 用白色箭头
    arrowColor: null
  }, options);

  // 流光/呼吸动画靠 SVG className 的 CSS keyframes 驱动，地图开 preferCanvas 后
  // L.polyline 默认落到 Canvas 渲染器上，className/getElement 全部失效。
  // 这里显式给特效线指定一个独立 SVG 渲染器（挂到特效 pane），与底图渲染策略解耦。
  const flowRenderer = L.svg({ pane: opts.pane });
  const lite = !!opts.lite;
  const frameInterval = 1000 / Math.max(1, opts.fps);

  let tracks = [];
  let color = opts.color;
  let pxPerM = 0.0002;
  let raf = null;
  let lastTs = 0;
  let lastArrowTs = 0; // fps 限帧用：上次真正写箭头的时间戳
  let destroyed = false;
  // 用户手势（拖拽/捏合/滚轮）计数：进行中暂停箭头写入并隐藏 outer/glow 特效层，
  // 只留 core 主线，降低低端设备 WebView 滑动时的渲染开销。
  // 注意只统计带 originalEvent 的手势事件：司机端导航跟随每 1~2s 程序化 setView，
  // 若也算手势会导致流光层高频闪烁隐藏。
  let userGesture = 0;

  // 起终点脉冲标记
  let startMarker = null;
  let endMarker = null;

  function makeArrow() {
    const shape = opts.arrowShape === 'chevron' ? 'rf-chev' : 'rf-arrow';
    const icon = L.divIcon({
      className: 'rf-arrow-outer',
      // 轻量模式：去掉 rf-arrow-pulse（filter/opacity 关键帧动画），配合 .rf-arrow-lite 关闭 drop-shadow
      html: lite ? `<div class="${shape} ${shape}-lite"></div>` : `<div class="${shape} rf-arrow-pulse"></div>`,
      iconSize: [0, 0]
    });
    const marker = L.marker([0, 0], { icon, pane: opts.pane, interactive: false, keyboard: false });
    return { marker, el: null };
  }

  /** 为一段几何创建描边+三层线 + 累计距离表；casingColor 为空则不画描边（旧样式） */
  function makeTrack(segColor, pts, casingColor) {
    const t = {
      color: segColor,
      casingColor: casingColor || opts.casing || null,
      pts,
      cum: [0],
      total: 0,
      progress: 0,
      spacingM: 0,
      arrows: [],
      outer: L.polyline(pts, {
        color: segColor,
        weight: opts.outerWidth,
        opacity: 0.4,
        className: lite ? 'rf-outer' : 'rf-outer rf-outer-pulse',
        pane: opts.pane,
        renderer: flowRenderer,
        interactive: false
      }),
      glow: L.polyline(pts, {
        color: segColor,
        weight: opts.glowWidth,
        opacity: 0.9,
        dashArray: '22 56',
        className: lite ? 'rf-glow' : 'rf-glow rf-glow-anim',
        pane: opts.pane,
        renderer: flowRenderer,
        interactive: false
      }),
      core: L.polyline(pts, {
        color: segColor,
        weight: opts.coreWidth,
        opacity: 0.98,
        className: 'rf-core',
        pane: opts.pane,
        renderer: flowRenderer,
        interactive: false
      })
    };
    if (t.casingColor) {
      // 插在 core 之下（addLayers 顺序控制层级）、手势期不隐藏：它就是主线轮廓的一部分
      t.casing = L.polyline(pts, {
        color: t.casingColor,
        weight: opts.coreWidth + opts.casingBorder,
        opacity: 0.95,
        className: 'rf-casing',
        pane: opts.pane,
        renderer: flowRenderer,
        interactive: false
      });
    }
    for (let i = 1; i < pts.length; i++) {
      t.total += map.distance(L.latLng(pts[i - 1]), L.latLng(pts[i]));
      t.cum.push(t.total);
    }
    return t;
  }

  function removeTrack(t) {
    t.arrows.forEach(a => { try { map.removeLayer(a.marker); } catch (e) {} });
    t.arrows = [];
    if (t.outer._map) map.removeLayer(t.outer);
    if (t.casing && t.casing._map) map.removeLayer(t.casing);
    if (t.glow._map) map.removeLayer(t.glow);
    if (t.core._map) map.removeLayer(t.core);
  }

  function clearTracks() {
    tracks.forEach(removeTrack);
    tracks = [];
  }

  /** 用最长的一段估算「米 → 屏幕像素」比例（所有段同缩放级别，比例一致） */
  function measurePxPerM() {
    let ref = null;
    for (const t of tracks) if (!ref || t.total > ref.total) ref = t;
    if (!ref || ref.pts.length < 2 || ref.total <= 0) return;
    const step = Math.max(1, Math.floor(ref.pts.length / 40));
    let px = 0;
    let m = 0;
    for (let i = step; i < ref.pts.length; i += step) {
      const a = map.latLngToContainerPoint(L.latLng(ref.pts[i - step]));
      const b = map.latLngToContainerPoint(L.latLng(ref.pts[i]));
      px += a.distanceTo(b);
      m += ref.cum[i] - ref.cum[i - step];
    }
    pxPerM = m > 0 ? Math.max(px / m, 1e-7) : 2e-4;
  }

  /** 计算各段箭头数量：按长度分配，全局上限内每段至少 1 个 */
  function allocateArrows() {
    const spacingM = opts.spacingPx / pxPerM;
    const perTrackMin = tracks.length === 1 ? opts.minArrows : 1;
    const wants = tracks.map(t => {
      if (t.total <= 0 || t.pts.length < 2) return 0;
      return Math.max(perTrackMin, Math.round(t.total / spacingM));
    });
    const sum = wants.reduce((a, b) => a + b, 0);
    if (sum > opts.maxArrows) {
      const ratio = opts.maxArrows / sum;
      for (let i = 0; i < wants.length; i++) {
        if (wants[i] > 0) wants[i] = Math.max(1, Math.round(wants[i] * ratio));
      }
    }
    return wants;
  }

  function layoutTrack(t, n) {
    while (t.arrows.length > n) {
      const a = t.arrows.pop();
      try { map.removeLayer(a.marker); } catch (e) {}
    }
    while (t.arrows.length < n) {
      const a = makeArrow();
      a.marker.addTo(map);
      t.arrows.push(a);
    }
    t.spacingM = n > 0 ? t.total / n : 0;
    t.arrows.forEach(a => {
      if (!a.el) a.el = a.marker.getElement() && a.marker.getElement().firstChild;
      if (a.el) a.el.style.color = opts.arrowColor || t.color;
    });
  }

  function layout() {
    if (!tracks.length) return;
    measurePxPerM();
    const wants = allocateArrows();
    tracks.forEach((t, i) => layoutTrack(t, wants[i]));
  }

  function pointAt(t, d) {
    let lo = 0;
    let hi = t.cum.length - 1;
    while (lo < hi - 1) {
      const mid = (lo + hi) >> 1;
      if (t.cum[mid] <= d) lo = mid; else hi = mid;
    }
    const segLen = t.cum[hi] - t.cum[lo];
    const f = segLen > 0 ? (d - t.cum[lo]) / segLen : 0;
    const a = t.pts[lo];
    const b = t.pts[hi];
    return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
  }

  function clearEndpoints() {
    if (startMarker) { try { map.removeLayer(startMarker); } catch (e) {} startMarker = null; }
    if (endMarker) { try { map.removeLayer(endMarker); } catch (e) {} endMarker = null; }
  }

  function updateEndpoints() {
    clearEndpoints();
    if (!tracks.length) return;
    const first = tracks[0];
    const last = tracks[tracks.length - 1];
    if (first.pts.length < 2 || last.pts.length < 2) return;
    startMarker = L.circleMarker(first.pts[0], {
      radius: 7,
      color: '#fff',
      weight: 3,
      fillColor: first.color,
      fillOpacity: 1,
      pane: opts.pane,
      interactive: false,
      className: 'rf-endpoint-pulse'
    }).addTo(map);
    endMarker = L.circleMarker(last.pts[last.pts.length - 1], {
      radius: 7,
      color: '#fff',
      weight: 3,
      fillColor: last.color,
      fillOpacity: 1,
      pane: opts.pane,
      interactive: false,
      className: 'rf-endpoint-pulse'
    }).addTo(map);
  }

  function frame(ts) {
    if (destroyed) return;
    if (!lastTs) lastTs = ts;
    const dt = Math.min((ts - lastTs) / 1000, 0.05);
    lastTs = ts;
    const speedM = opts.speedPxPerSec / pxPerM;
    // 以下任一情况都跳过本帧的箭头写入：
    // 1) 用户手势进行中（拖拽/捏合，见 userGesture）；
    // 2) 缩放动画插值中 —— 保留 _animatingZoom/lastZoomTs 老判据兼容无 move 事件的程序化缩放；
    // 3) 未到帧率预算（1000/fps）——移动端 15fps 限帧。
    //
    // 缩放判据的背景（已核对 Leaflet 源码）：_animateZoom 一开始就调用
    // _move(center, 目标zoom, ...)，而 _move 第一件事是 this._zoom = zoom —— 也就是
    // 动画刚起步，map._zoom 已经是「目标级别」，同时 mapPane 还在用 CSS transform
    // 从旧级别插值过去。此时 marker.setLatLng() 会按目标级别算好像素位置写进 translate3d，
    // 之后又被 pane 的 transform 缩放一次 = 双重变换，表现就是箭头「跟不上缩放速度」。
    // pane 的 transform 本来就会让箭头随地图平滑缩放并始终贴合路线，所以动画期间跳过即可。
    // _animatingZoom 是 Leaflet 自己的标志位，动画结束由 transitionend 清除，另有
    // setTimeout(...,250) 兜底，不会卡住；Leaflet 内部各组件也是用同一个判断跳过动画期的定位。
    // _animatingZoom 只覆盖按钮/程序化的动画缩放；手机双指捏合走 TouchZoom，
    // 仅发 zoom/zoomend 而不置 _animatingZoom。用最近一次 zoom 事件时间戳兜住捏合：
    // 缩放进行中暂停写箭头位置，避免与 Leaflet 对 markerPane 的逐帧重定位叠加成
    // “双重变换”（即“箭头/线跟不上缩放”）。停手 ~120ms 或 zoomend 后自动恢复；
    // 即便 zoomend 因异常漏触发，时间戳过期也会自愈，不会把箭头永久冻住。
    const zoomAnimating = !!map._animatingZoom || (ts - lastZoomTs) < 120;
    if (userGesture === 0 && !zoomAnimating && ts - lastArrowTs >= frameInterval) {
      lastArrowTs = ts;
      for (const t of tracks) {
        if (t.total <= 0 || !t.arrows.length) continue;
        t.progress = (t.progress + speedM * dt) % t.total;
        for (let i = 0; i < t.arrows.length; i++) {
          const d = (t.progress + i * t.spacingM) % t.total;
          const p = pointAt(t, d);
          t.arrows[i].marker.setLatLng(p);
          const ahead = Math.min(t.total, d + Math.max(60, t.spacingM * 0.08));
          const q = pointAt(t, ahead);
          const ang = bearing(p, q);
          const el = t.arrows[i].el || (t.arrows[i].marker.getElement() && t.arrows[i].marker.getElement().firstChild);
          if (el) {
            t.arrows[i].el = el;
            el.style.transform = `rotate(${(ang - 90).toFixed(1)}deg)`;
          }
        }
      }
    }
    raf = requestAnimationFrame(frame);
  }

  function start() {
    if (raf == null && !destroyed) {
      lastTs = 0;
      raf = requestAnimationFrame(frame);
    }
  }

  function stop() {
    if (raf != null) {
      cancelAnimationFrame(raf);
      raf = null;
    }
  }

  function addLayers() {
    tracks.forEach(t => {
      if (!t.outer._map) t.outer.addTo(map);
      // 层级：外发光 → 描边 → 流光带 → 核心线（后加在上）
      if (t.casing && !t.casing._map) t.casing.addTo(map);
      if (!t.glow._map) t.glow.addTo(map);
      if (!t.core._map) t.core.addTo(map);
    });
  }

  /** 按段设置路线：segments = [{ latlngs, color }]，顺序即行进顺序 */
  function setSegments(segments) {
    clearTracks();
    clearEndpoints();
    const segs = (segments || [])
      .map(s => ({
        color: (s && s.color) || color,
        casing: (s && s.casing) || opts.casing || null,
        pts: ((s && s.latlngs) || []).filter(p => p && p.length >= 2)
      }))
      .filter(s => s.pts.length >= 2);
    if (!segs.length) {
      stop();
      return;
    }
    tracks = segs.map(s => makeTrack(s.color, s.pts, s.casing));
    addLayers();
    layout();
    updateEndpoints();
    start();
  }

  // 记录最近一次缩放事件时刻（zoom 在捏合过程中会连续触发），供 frame() 判定“正在缩放”
  let lastZoomTs = 0;
  const markZoom = () => { lastZoomTs = performance.now(); };
  map.on('zoom', markZoom);
  const onZoom = () => { if (tracks.length) layout(); };
  map.on('zoomend', onZoom);
  
  // 用户手势期隐藏：movestart/zoomstart（带 originalEvent 才算）隐藏 outer/glow 特效层，
  // moveend/zoomend 恢复原透明度并重排箭头。用计数而非布尔，避免缩放+平移重叠时提前恢复。
  const isUserEvent = e => !!(e && e.originalEvent);
  const setGestureVisible = (show) => {
    tracks.forEach(t => {
      t.outer.setStyle({ opacity: show ? 0.4 : 0 });
      t.glow.setStyle({ opacity: show ? 0.9 : 0 });
    });
  };
  const onGestureStart = e => {
    if (!isUserEvent(e)) return
    userGesture++
    if (userGesture === 1) setGestureVisible(false)
  };
  const onGestureEnd = e => {
    if (!isUserEvent(e)) return
    userGesture = Math.max(0, userGesture - 1)
    if (userGesture === 0) {
      setGestureVisible(true)
      if (tracks.length) layout()
    }
  };
  map.on('movestart', onGestureStart);
  map.on('zoomstart', onGestureStart);
  map.on('moveend', onGestureEnd);
  map.on('zoomend', onGestureEnd);

  return {
    /** 兼容旧接口：整条路线单色 */
    setLatLngs(next) {
      setSegments([{ latlngs: next, color }]);
    },
    /** 按段着色：安全段绿 / 风险段红 */
    setSegments,
    setColor(c) {
      color = c;
      tracks.forEach(t => {
        t.color = c;
        t.outer.setStyle({ color: c });
        t.glow.setStyle({ color: c });
        t.core.setStyle({ color: c });
        t.arrows.forEach(a => { if (a.el) a.el.style.color = c; });
      });
      if (startMarker) startMarker.setStyle({ fillColor: c });
      if (endMarker) endMarker.setStyle({ fillColor: c });
    },
    start,
    stop,
    /**
     * 把路线与端点标记提到同 pane 顶层。
     * 大屏在主线之后绘制备选候选虚线（同一走廊），会把主线连同流光/箭头盖住，
     * 视觉上像"主线断了一截/消失"，画完备选后调用此方法把主线提回来。
     */
    bringToFront() {
      tracks.forEach(t => {
        [t.outer, t.casing, t.glow, t.core].forEach(l => {
          try { if (l && l._map && l.bringToFront) l.bringToFront(); } catch (e) { /* 已移除 */ }
        });
        t.arrows.forEach(a => {
          // 箭头是 DOM 元素（divIcon），重新 append 即置于同级末尾
          try { if (a.el && a.el.parentNode) a.el.parentNode.appendChild(a.el); } catch (e) { /* 已移除 */ }
        });
      });
      [startMarker, endMarker].forEach(l => {
        try { if (l && l._map && l.bringToFront) l.bringToFront(); } catch (e) { /* 已移除 */ }
      });
    },
    destroy() {
      destroyed = true;
      stop();
      map.off('zoom', markZoom);
      map.off('zoomend', onZoom);
      map.off('movestart', onGestureStart);
      map.off('zoomstart', onGestureStart);
      map.off('moveend', onGestureEnd);
      map.off('zoomend', onGestureEnd);
      clearTracks();
      clearEndpoints();
    }
  };
}
