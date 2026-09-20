<template>
  <section class="card tributary-card">
    <h3>
      平陆运河支流风险 · 差异化预测
      <span v-if="loaded" class="route-state">未来6小时</span>
    </h3>
    <div class="muted trib-hint">
      平陆运河沿线每条支流有独立的特征向量，输出的是概率，不是简单的熔断/不熔断
    </div>

    <div class="slider-row">
      <span class="slider-label">平陆运河支流流域降雨量</span>
      <input
        type="range"
        min="0"
        max="100"
        step="5"
        v-model.number="rainfall"
        class="rain-slider"
        :style="sliderStyle"
        @change="load"
      />
      <span class="rain-value">{{ rainfall }}mm</span>
    </div>

    <div v-if="tributaries.length" class="trib-list">
      <div v-for="t in tributaries" :key="t.id" class="trib-item" :class="'lv-' + levelKey(t.level)">
        <div class="trib-head">
          <span class="trib-name">{{ t.name }}</span>
          <span class="trib-prob" :class="'prob-' + levelKey(t.level)">{{ t.probabilityPct }}%</span>
        </div>
        <div class="trib-risk">{{ t.riskType }} · 风险{{ t.level }}</div>
        <div class="prob-bar">
          <div class="prob-fill" :class="'prob-' + levelKey(t.level)" :style="{ width: t.probabilityPct + '%' }"></div>
        </div>
        <div class="trib-features">
          <span v-for="(v, k) in t.features" :key="k" class="feat-chip">{{ k }}：{{ v }}</span>
        </div>
      </div>
    </div>
    <div v-else class="muted">加载中…</div>

    <div class="trib-note">
      同样的 {{ rainfall }}mm 降雨：{{ summaryText }}
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import axios from "axios";

const rainfall = ref(45);
const tributaries = ref([]);
const loaded = ref(false);

const sliderStyle = computed(() => {
  const pct = rainfall.value;
  return {
    background: `linear-gradient(to right, #4f6df5 0%, #4f6df5 ${pct}%, rgba(30,50,90,0.14) ${pct}%, rgba(30,50,90,0.14) 100%)`,
  };
});

const summaryText = computed(() => {
  if (!tributaries.value.length) return "";
  const sorted = [...tributaries.value].sort((a, b) => b.probabilityPct - a.probabilityPct);
  const top = sorted[0];
  const bottom = sorted[sorted.length - 1];
  return `${top.name}${top.riskType}概率 ${top.probabilityPct}%，而${bottom.name}仅 ${bottom.probabilityPct}%——特征向量决定风险画像`;
});

function levelKey(lv) {
  return lv === "高" ? "high" : lv === "中" ? "mid" : "low";
}

async function load() {
  try {
    const { data } = await axios.get("/api/canal/tributaries", { params: { rainfallMm: rainfall.value } });
    tributaries.value = data.tributaries || [];
    loaded.value = true;
  } catch (e) {
    console.error("tributaries failed", e);
  }
}

onMounted(load);
</script>

<style scoped>
.tributary-card {
  border: 1px solid rgba(79, 109, 245, 0.3);
}
.trib-hint {
  font-size: 11px;
  margin-bottom: 10px;
}
.slider-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.slider-label {
  font-size: 11px;
  color: #64748f;
  white-space: nowrap;
}
.rain-slider {
  flex: 1;
  -webkit-appearance: none;
  appearance: none;
  height: 6px;
  border-radius: 3px;
  outline: none;
  cursor: pointer;
}
.rain-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #4f6df5;
  cursor: grab;
}
.rain-value {
  min-width: 46px;
  text-align: right;
  font-weight: 700;
  font-family: var(--mono, monospace);
  color: #4f6df5;
}
.trib-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.trib-item {
  border: 1px solid rgba(30, 50, 90, 0.12);
  border-radius: 10px;
  padding: 8px 10px;
  background: rgba(255,255,255,0.60);
}
.trib-item.lv-high {
  border-color: rgba(225, 29, 72, 0.45);
}
.trib-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.trib-name {
  font-weight: 700;
  font-size: 13px;
  color: var(--text-h, #1c2a44);
}
.trib-prob {
  font-size: 18px;
  font-weight: 800;
  font-family: var(--mono, monospace);
}
.prob-high { color: #e11d48; }
.prob-mid { color: #d97706; }
.prob-low { color: #16a34a; }
.trib-risk {
  font-size: 11px;
  color: #5b6b85;
  margin-top: 2px;
}
.prob-bar {
  height: 5px;
  border-radius: 3px;
  background: rgba(30, 50, 90, 0.10);
  margin-top: 6px;
  overflow: hidden;
}
.prob-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.5s;
}
.prob-fill.prob-high { background: #e11d48; }
.prob-fill.prob-mid { background: #d97706; }
.prob-fill.prob-low { background: #22a35a; }
.trib-features {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.feat-chip {
  font-size: 10px;
  background: rgba(139, 148, 158, 0.15);
  color: #64748f;
  padding: 2px 6px;
  border-radius: 6px;
}
.trib-note {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(30, 50, 90, 0.14);
  font-size: 11px;
  color: #4f6df5;
  line-height: 1.6;
}
</style>
