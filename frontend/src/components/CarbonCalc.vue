<template>
  <div class="carbon-calc">
    <div class="carbon-header">
      <h4>碳排放计算</h4>
      <div class="row">
        <label class="inline-label">车型
          <select v-model="vehicleType">
            <option value="light">轻型货车 (3.5t)</option>
            <option value="medium">中型货车 (10t)</option>
            <option value="heavy">重型货车 (25t)</option>
            <option value="refrigerated">冷藏车</option>
          </select>
        </label>
        <label class="inline-label">燃油
          <select v-model="fuelType">
            <option value="diesel">柴油</option>
            <option value="gasoline">汽油</option>
            <option value="lng">LNG</option>
            <option value="electric">电动</option>
          </select>
        </label>
      </div>
      <button class="btn primary" @click="calculate">计算碳排放</button>
    </div>

    <div v-if="result" class="carbon-result">
      <div class="carbon-main">
        <span class="carbon-value">{{ result.totalKg }}</span>
        <span class="carbon-unit">kg CO₂</span>
        <span v-if="result.backendKg > 0" class="carbon-backend">后端估算: {{ result.backendKg }} kg</span>
      </div>

      <div class="carbon-bars">
        <div class="carbon-bar-row">
          <span class="bar-label">行驶排放</span>
          <div class="bar-track"><div class="bar-fill driving" :style="{ width: drivingPct + '%' }"></div></div>
          <span class="bar-val">{{ result.drivingKg }} kg</span>
        </div>
        <div class="carbon-bar-row">
          <span class="bar-label">通关怠速</span>
          <div class="bar-track"><div class="bar-fill idle" :style="{ width: idlePct + '%' }"></div></div>
          <span class="bar-val">{{ result.idleKg }} kg</span>
        </div>
        <div class="carbon-bar-row">
          <span class="bar-label">绕行增量</span>
          <div class="bar-track"><div class="bar-fill reroute" :style="{ width: reroutePct + '%' }"></div></div>
          <span class="bar-val">{{ result.rerouteKg }} kg</span>
        </div>
      </div>

      <div class="carbon-equivalent">
        相当于
        <span class="equiv-tree">{{ result.treeEquivalent }} 棵树</span>
        一年的碳吸收量
      </div>

      <div class="carbon-tips" v-if="result.totalKg > 0">
        <div class="tip-title">减排建议</div>
        <div class="tip-item" v-for="(tip, i) in tips" :key="i">{{ tip }}</div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'CarbonCalc',
  props: {
    routeResult: { type: Object, default: null },
    customsList: { type: Array, default: () => [] },
    cargoType: { type: String, default: 'general' }
  },
  data() {
    return {
      vehicleType: 'medium',
      fuelType: 'diesel',
      result: null
    };
  },
  computed: {
    emissionFactor() {
      const factors = {
        light:   { diesel: 0.25, gasoline: 0.28, lng: 0.20, electric: 0.05 },
        medium:  { diesel: 0.50, gasoline: 0.55, lng: 0.40, electric: 0.10 },
        heavy:   { diesel: 0.85, gasoline: 0.92, lng: 0.65, electric: 0.18 },
        refrigerated: { diesel: 0.70, gasoline: 0.75, lng: 0.55, electric: 0.15 }
      };
      return (factors[this.vehicleType] || factors.medium)[this.fuelType] || 0.50;
    },
    drivingPct() {
      if (!this.result || this.result.totalKg === 0) return 0;
      return Math.round((this.result.drivingKg / this.result.totalKg) * 100);
    },
    idlePct() {
      if (!this.result || this.result.totalKg === 0) return 0;
      return Math.round((this.result.idleKg / this.result.totalKg) * 100);
    },
    reroutePct() {
      if (!this.result || this.result.totalKg === 0) return 0;
      return Math.round((this.result.rerouteKg / this.result.totalKg) * 100);
    },
    tips() {
      const t = [];
      if (this.result.rerouteKg > 50) t.push('绕行增加大量排放，建议检查是否有更优路径');
      if (this.result.idleKg > 30) t.push('通关等待时间较长，建议错峰出行或预约绿色通道');
      if (this.fuelType === 'diesel') t.push('考虑切换 LNG 车型可减少约 20% 碳排放');
      if (this.vehicleType === 'heavy') t.push('评估是否可使用中型货车分批运输以降低单趟排放');
      if (this.cargoType === 'cold') t.push('冷链运输碳排放较高，建议优化温控策略');
      if (!t.length) t.push('当前路线碳排放处于合理范围');
      return t;
    }
  },
  methods: {
    calculate() {
      const route = this.routeResult && this.routeResult.route;
      if (!route) {
        alert('请先规划跨境路线');
        return;
      }
      // 后端 RouteResponse 已计算 carbonEmissionKg（按重载卡车 0.35L/km × 柴油 2.68kg/L）
      const backendKg = Math.round((route.carbonEmissionKg || 0) * 10) / 10;

      const distanceKm = route.totalDistanceKm || 0;
      const extraHours = route.extraHours || 0;
      const customsHours = this.customsList.reduce((s, c) => s + c.currentHours, 0);

      // 行驶排放
      const drivingKg = Math.round(distanceKm * this.emissionFactor * 10) / 10;
      // 通关怠速排放（假设怠速 3L/h 柴油 ≈ 8 kg CO₂/h）
      const idleKg = Math.round(customsHours * 8 * 10) / 10;
      // 绕行增量
      const rerouteKg = extraHours > 0 ? Math.round(extraHours * 30 * this.emissionFactor * 10) / 10 : 0;

      const totalKg = Math.round((drivingKg + idleKg + rerouteKg) * 10) / 10;
      const treeEquivalent = Math.round(totalKg / 22);

      this.result = { totalKg, drivingKg, idleKg, rerouteKg, treeEquivalent, backendKg };
    }
  }
}
</script>

<style scoped>
.carbon-calc { margin-top: 4px; }
.carbon-header h4 { margin: 0 0 8px; font-size: 13px; color: #333; }
.row { display: flex; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.inline-label { font-size: 12px; color: #666; }
.inline-label select { margin-left: 4px; padding: 4px; font-size: 12px; }
.btn { padding: 8px 12px; border-radius: 6px; border: none; cursor: pointer; font-size: 13px; }
.btn.primary { background: #16a34a; color: #fff; }
.carbon-result { margin-top: 12px; background: #f1f8e9; border: 1px solid #c8e6c9; border-radius: 6px; padding: 12px; }
.carbon-main { text-align: center; margin-bottom: 10px; }
.carbon-value { font-size: 28px; font-weight: 700; color: #16a34a; }
.carbon-unit { font-size: 14px; color: #666; margin-left: 4px; }
.carbon-backend { display: block; font-size: 11px; color: #888; margin-top: 2px; }
.carbon-bars { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; }
.carbon-bar-row { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.bar-label { width: 60px; color: #555; text-align: right; flex-shrink: 0; }
.bar-track { flex: 1; height: 10px; background: #e0e0e0; border-radius: 5px; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 5px; }
.bar-fill.driving { background: #1976d2; }
.bar-fill.idle { background: #f59e0b; }
.bar-fill.reroute { background: #c62828; }
.bar-val { width: 50px; color: #333; flex-shrink: 0; }
.carbon-equivalent { text-align: center; font-size: 12px; color: #666; margin-bottom: 8px; }
.equiv-tree { font-weight: 700; color: #16a34a; }
.carbon-tips { border-top: 1px dashed #c8e6c9; padding-top: 8px; }
.tip-title { font-size: 12px; font-weight: 600; color: #333; margin-bottom: 4px; }
.tip-item { font-size: 11px; color: #555; padding: 2px 0; }
.tip-item::before { content: '💡 '; }
</style>