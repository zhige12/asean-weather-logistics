<template>
  <div class="eta-indicator">
    <div class="eta-main">
      <span class="eta-value">{{ formattedHours }}</span>
      <span class="eta-label">预计到达时间</span>
    </div>

    <div class="eta-breakdown">
      <div class="eta-row">
        <span class="eta-desc">基准路线</span>
        <span>{{ routeData.baselineHours }}h / {{ nodeCount }}节点</span>
      </div>

      <div class="eta-row" :class="{ 'eta-delayed': routeData.extraHours > 0 }">
        <span class="eta-desc">实时影响</span>
        <span>
          {{ routeData.extraHours > 0 ? `+${routeData.extraHours}小时` : '无延误' }}
          <span v-if="hasWeatherImpact" class="impact-tag weather">气象</span>
          <span v-if="hasRiskImpact" class="impact-tag risk">风险</span>
          <span v-if="hasCustomsImpact" class="impact-tag customs">通关</span>
        </span>
      </div>
    </div>

    <div class="eta-progress">
      <div class="eta-progress-bar" :style="progressStyle"></div>
      <div class="eta-progress-labels">
        <span>起点</span>
        <span>ETA: {{ formattedHours }}</span>
        <span>终点</span>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ETAIndicator',
  props: {
    routeData: {
      type: Object,
      required: true
    }
  },
  computed: {
    formattedHours() {
      return this.routeData.currentHours?.toFixed(1) || '0.0';
    },
    nodeCount() {
      return this.routeData.baselinePathNodeIds?.length || 0;
    },
    hasWeatherImpact() {
      const segs = this.routeData.riskSegments || [];
      return segs.some(r => /暴雨|大雾|台风|高温|降水|能见度|天气/.test(r.reason || ''));
    },
    hasRiskImpact() {
      return this.routeData.riskSegments && this.routeData.riskSegments.length > 0;
    },
    hasCustomsImpact() {
      // 额外延误 > 0 视为通关/路况综合影响（后端无单独 customsDelay 字段）
      return this.routeData.extraHours > 0;
    },
    progressStyle() {
      const base = this.routeData.baselineHours || 1;
      const progress = Math.min(100, (this.routeData.currentHours / (base * 1.5)) * 100);
      return {
        width: `${progress}%`,
        backgroundColor: progress > 90 ? '#c62828' : progress > 70 ? '#FFC107' : '#4CAF50'
      };
    }
  }
}
</script>

<style scoped>
.eta-indicator {
  background: #f8f9fa;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}

.eta-main {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 12px;
}

.eta-value {
  font-size: 24px;
  font-weight: bold;
  color: #0b5394;
}

.eta-label {
  font-size: 13px;
  color: #666;
  margin-top: 4px;
}

.eta-breakdown {
  margin-bottom: 12px;
}

.eta-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 13px;
  color: #555;
}

.eta-row.eta-delayed {
  color: #c62828;
  font-weight: 500;
}

.eta-desc {
  color: #333;
  font-weight: 600;
}

.impact-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 12px;
  font-size: 11px;
}

.impact-tag.weather { background: #e3f2fd; color: #0b5394; }
.impact-tag.risk { background: #ffebee; color: #c62828; }
.impact-tag.customs { background: #f1f8e9; color: #388e3c; }

.eta-progress {
  background: #e9ecef;
  border-radius: 4px;
  overflow: hidden;
  height: 6px;
}

.eta-progress-bar {
  height: 100%;
  transition: width 0.3s ease;
}

.eta-progress-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 11px;
  color: #777;
}
</style>