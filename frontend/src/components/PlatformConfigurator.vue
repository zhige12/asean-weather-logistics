<template>
  <section class="card platform-card">
    <h3>
      开放平台 · 智能体配置器
      <span class="route-state">千企千面</span>
    </h3>
    <div class="muted plat-hint">同一套引擎，物流公司按自己的风险偏好、货物、触达范围配置专属智能体</div>

    <div v-if="cfg" class="cfg-body">
      <!-- 预设智能体模板库（开放点一）：一键套用行业预设 -->
      <div v-if="cfg.templates" class="cfg-block">
        <div class="cfg-label">
          智能体模板库
          <span class="cfg-value">{{ cfg.activeTemplate ? "已套用预设" : "自定义配置" }}</span>
        </div>
        <div class="tpl-options">
          <button
            v-for="(t, id) in cfg.templates"
            :key="id"
            class="tpl-btn"
            :class="{ active: cfg.activeTemplate === id }"
            @click="applyTemplate(id)"
          >
            <div class="tpl-name">{{ t.name }}</div>
            <div class="tpl-desc">{{ t.desc }}</div>
          </button>
        </div>
        <div class="cfg-effect">
          一键套用行业预设（风险偏好 + 货物类型 + 触达范围），下方参数与方案对比实时联动
        </div>
      </div>

      <!-- 风险容忍度 -->
      <div class="cfg-block">
        <div class="cfg-label">
          风险容忍度
          <span class="cfg-value" :class="'rp-' + cfg.riskProfile">{{ cfg.riskProfileName }}（熔断 {{ cfg.fuseThresholdMm }}mm）</span>
        </div>
        <input
          type="range"
          min="0"
          max="2"
          step="1"
          v-model.number="profileIdx"
          class="profile-slider"
          @change="applyProfile"
        />
        <div class="profile-marks">
          <span :class="{ on: cfg.riskProfile === 'CONSERVATIVE' }">保守 80mm</span>
          <span :class="{ on: cfg.riskProfile === 'BALANCED' }">平衡 100mm</span>
          <span :class="{ on: cfg.riskProfile === 'AGGRESSIVE' }">激进 120mm</span>
        </div>
        <div class="cfg-effect">
          同样 85mm 降雨：保守模式<b class="danger">熔断</b>，平衡/激进模式<b class="ok">仍可通行</b>——方案对比同步更新
        </div>
      </div>

      <!-- 货物类型 -->
      <div class="cfg-block">
        <div class="cfg-label">
          货物类型
          <span class="cfg-value">{{ cfg.cargoTypeName }}</span>
        </div>
        <div class="cargo-options">
          <button
            v-for="(c, id) in cfg.cargoTypes"
            :key="id"
            class="cargo-btn"
            :class="{ active: cfg.cargoType === id }"
            @click="applyCargo(id)"
          >
            <div class="cargo-name">{{ c.name }}</div>
            <div class="cargo-sub">延误 {{ c.damageStartHours }}h 起算货损 · 货值 {{ (c.cargoValueYuan / 10000).toFixed(0) }}万</div>
          </button>
        </div>
        <div class="cfg-effect">
          同样延误 6 小时：{{ cfg.cargoType === 'DRAGON_FRUIT' ? '火龙果货损率 4.8%' : '电子元件货损率 7.2%' }}（{{ cfg.cargo.damageStartHours }}h 启动货损计算）
        </div>
      </div>

      <!-- 触达对象 -->
      <div class="cfg-block">
        <div class="cfg-label">
          触达对象
          <span class="cfg-value">{{ cfg.outreachTargets.length }}/{{ cfg.allTargets.length }} 个角色</span>
        </div>
        <div class="target-options">
          <label v-for="t in cfg.allTargets" :key="t" class="target-check" :class="{ on: targets.has(t) }">
            <input type="checkbox" :checked="targets.has(t)" @change="toggleTarget(t)" />
            <span>{{ cfg.targetNames[t] }}</span>
          </label>
        </div>
        <div class="cfg-effect">
          有的公司只通知调度员和司机，有的还要通知货主和保险——触达名单实时生效
        </div>
      </div>

      <div v-if="savedTip" class="saved-tip">✅ 配置已应用，沙盘与方案对比已联动更新</div>
    </div>
    <div v-else class="muted">加载中…</div>
  </section>
</template>

<script setup>
import { onMounted, ref, watch } from "vue";
import axios from "axios";

const emit = defineEmits(["config-changed"]);

const cfg = ref(null);
const profileIdx = ref(0);
const targets = ref(new Set());
const savedTip = ref(false);

const PROFILES = ["CONSERVATIVE", "BALANCED", "AGGRESSIVE"];

watch(cfg, (v) => {
  if (!v) return;
  profileIdx.value = Math.max(0, PROFILES.indexOf(v.riskProfile));
  targets.value = new Set(v.outreachTargets || []);
});

async function load() {
  try {
    const { data } = await axios.get("/api/platform/config");
    cfg.value = data;
  } catch (e) {
    console.error("platform config load failed", e);
  }
}

async function apply(body) {
  try {
    const { data } = await axios.put("/api/platform/config", body);
    cfg.value = data;
    tip();
    emit("config-changed", data);
  } catch (e) {
    console.error("platform config apply failed", e);
  }
}

function applyProfile() {
  apply({ riskProfile: PROFILES[profileIdx.value] });
}
function applyCargo(id) {
  apply({ cargoType: id });
}
async function applyTemplate(id) {
  try {
    const { data } = await axios.post("/api/platform/template", { id });
    cfg.value = data;
    tip();
    emit("config-changed", data);
  } catch (e) {
    console.error("apply template failed", e);
  }
}
function toggleTarget(t) {
  const next = new Set(targets.value);
  if (next.has(t)) next.delete(t);
  else next.add(t);
  targets.value = next;
  apply({ outreachTargets: [...next] });
}

let tipTimer = null;
function tip() {
  savedTip.value = true;
  clearTimeout(tipTimer);
  tipTimer = setTimeout(() => (savedTip.value = false), 2500);
}

onMounted(load);
defineExpose({ load });
</script>

<style scoped>
.platform-card {
  border: 1px solid rgba(188, 140, 255, 0.4);
}
.plat-hint {
  font-size: 11px;
  margin-bottom: 10px;
}
.cfg-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.cfg-block {
  border: 1px solid rgba(30, 50, 90, 0.10);
  border-radius: 10px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.02);
}
.cfg-label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 700;
  color: var(--text-h, #1c2a44);
  margin-bottom: 8px;
}
.cfg-value {
  font-size: 11px;
  font-weight: 600;
  color: #7c5cff;
}
.cfg-value.rp-CONSERVATIVE { color: #4f6df5; }
.cfg-value.rp-BALANCED { color: #d97706; }
.cfg-value.rp-AGGRESSIVE { color: #e11d48; }
.profile-slider {
  width: 100%;
  -webkit-appearance: none;
  appearance: none;
  height: 6px;
  border-radius: 3px;
  outline: none;
  cursor: pointer;
  background: linear-gradient(to right, #4f6df5, #d97706, #e11d48);
}
.profile-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #7c5cff;
  cursor: grab;
}
.profile-marks {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #64748f;
  margin-top: 4px;
}
.profile-marks .on {
  color: var(--text-h, #1c2a44);
  font-weight: 700;
}
.cfg-effect {
  font-size: 11px;
  color: #5b6b85;
  margin-top: 8px;
  line-height: 1.6;
}
.cfg-effect .danger { color: #e11d48; }
.cfg-effect .ok { color: #16a34a; }
.cargo-options {
  display: flex;
  gap: 8px;
}
.tpl-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.tpl-btn {
  text-align: left;
  border: 1px solid rgba(30, 50, 90, 0.14);
  border-radius: 8px;
  padding: 7px 10px;
  background: rgba(255, 255, 255, 0.6);
  cursor: pointer;
  color: inherit;
  transition: all 0.2s;
}
.tpl-btn.active {
  border-color: #7c5cff;
  background: rgba(188, 140, 255, 0.14);
  box-shadow: 0 0 0 1px rgba(124, 92, 255, 0.3) inset;
}
.tpl-name {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-h, #1c2a44);
}
.tpl-desc {
  font-size: 10px;
  color: #64748f;
  margin-top: 2px;
}
.cargo-btn {
  flex: 1;
  text-align: left;
  border: 1px solid rgba(30, 50, 90, 0.14);
  border-radius: 8px;
  padding: 8px 10px;
  background: rgba(255,255,255,0.60);
  cursor: pointer;
  color: inherit;
}
.cargo-btn.active {
  border-color: #7c5cff;
  background: rgba(188, 140, 255, 0.12);
}
.cargo-name {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-h, #1c2a44);
}
.cargo-sub {
  font-size: 10px;
  color: #64748f;
  margin-top: 2px;
}
.target-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.target-check {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 5px 10px;
  border-radius: 14px;
  border: 1px solid rgba(30, 50, 90, 0.14);
  cursor: pointer;
  color: #64748f;
  transition: all 0.2s;
}
.target-check.on {
  border-color: #7c5cff;
  color: var(--text-h, #1c2a44);
  background: rgba(188, 140, 255, 0.1);
}
.target-check input {
  accent-color: #7c5cff;
}
.saved-tip {
  font-size: 12px;
  color: #16a34a;
  text-align: center;
  animation: fadein 0.3s;
}
@keyframes fadein {
  from { opacity: 0; }
  to { opacity: 1; }
}
</style>
