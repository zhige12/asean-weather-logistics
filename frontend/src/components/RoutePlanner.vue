<template>
  <section class="rp-card">
    <h3 class="rp-title">地名规划路线 <span class="rp-sub">地理编码 → 路网吸附 → Dijkstra</span></h3>
    <div class="rp-row">
      <input v-model="startText" class="rp-input" list="rp-place-list"
             placeholder="起点地名，如 南宁" @keyup.enter="submit" />
      <input v-model="endText" class="rp-input" list="rp-place-list"
             placeholder="终点地名，如 河内" @keyup.enter="submit" />
    </div>
    <datalist id="rp-place-list">
      <option v-for="p in places" :key="p.name" :value="p.name">{{ p.tag }}</option>
    </datalist>
    <div class="rp-row">
      <button class="rp-btn" :disabled="busy" @click="submit">{{ busy ? '规划中…' : '规划路线' }}</button>
      <span class="rp-msg" :class="{ err: !!errMsg }">{{ errMsg || hint }}</span>
    </div>
  </section>
</template>

<script>
import axios from 'axios'
import { fetchPlaceList, geocodeName, apiErrorMessage } from '../utils/geocoder.js'

/**
 * 起终点地名输入 + 规划（PC 调度大屏与手机端共用同一组件、同一后端接口）：
 * 1) 下拉候选来自后端本地地名库（/api/geocode/list，离线可用）；
 * 2) 提交时先地理编码（本地库预匹配零请求，冷门地名才耗天地图配额）；
 * 3) POST /api/route/plan 坐标模式：后端吸附最近路网节点（超 5km 报覆盖范围外），
 *    再走与调度流程完全相同的 Dijkstra + 气象熔断逻辑；
 * 4) 结果以 planned 事件抛出（载荷即后端响应：pathCoords/riskSegments/routeGeoJSON…），
 *    绘制交给宿主（大屏走 applyRouteResult 主链路，司机端走 drawRoute）。
 */
export default {
  name: 'RoutePlanner',
  data() {
    return {
      places: [],
      startText: '',
      endText: '',
      busy: false,
      errMsg: '',
      hint: '支持中文地名，覆盖广西—越南北部—平陆运河沿线'
    }
  },
  mounted() {
    fetchPlaceList().then(list => { this.places = list || [] })
  },
  methods: {
    async submit() {
      if (this.busy) return
      this.errMsg = ''
      if (!this.startText.trim() || !this.endText.trim()) {
        this.errMsg = '请输入起点和终点地名'
        return
      }
      this.busy = true
      try {
        const [s, e] = await Promise.all([
          geocodeName(this.startText),
          geocodeName(this.endText)
        ])
        const r = await axios.post('/api/route/plan', {
          startLat: s.lat, startLng: s.lng,
          endLat: e.lat, endLng: e.lng
        }, { timeout: 20000 })
        this.hint = `${s.name} → ${e.name} · ${Math.round(r.data.totalDistanceKm)}km / 约${r.data.estimatedHours}h`
        this.$emit('planned', r.data, { start: s, end: e })
      } catch (err) {
        this.errMsg = apiErrorMessage(err, '规划失败，请稍后重试')
      } finally {
        this.busy = false
      }
    }
  }
}
</script>

<style scoped>
.rp-card {
  background: rgba(15, 23, 42, 0.82);
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 10px;
  padding: 10px 12px;
  color: #e2e8f0;
  backdrop-filter: blur(6px);
}
.rp-title { margin: 0 0 8px; font-size: 14px; font-weight: 600; }
.rp-sub { font-size: 11px; font-weight: 400; opacity: 0.6; margin-left: 6px; }
.rp-row { display: flex; gap: 8px; align-items: center; margin-top: 6px; flex-wrap: wrap; }
.rp-input {
  flex: 1; min-width: 90px;
  background: rgba(30, 41, 59, 0.9);
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 6px;
  color: #e2e8f0;
  padding: 6px 8px;
  font-size: 13px;
  outline: none;
}
.rp-input:focus { border-color: #38bdf8; }
.rp-btn {
  background: #16a34a; color: #fff; border: none; border-radius: 6px;
  padding: 6px 14px; font-size: 13px; cursor: pointer; white-space: nowrap;
}
.rp-btn:disabled { opacity: 0.55; cursor: default; }
.rp-msg { font-size: 11px; opacity: 0.75; flex: 1; min-width: 120px; }
.rp-msg.err { color: #fca5a5; opacity: 1; }
</style>
