<template>
  <section class="card growth-card">
    <h3>
      成长路径 · 三步走
      <span class="route-state">先证明能做，再证明能长大</span>
    </h3>

    <div class="growth-flow">
      <div v-for="(s, i) in steps" :key="i" class="growth-step" :class="{ current: i === 0 }">
        <div class="step-marker">
          <span class="step-num">{{ i + 1 }}</span>
          <span class="step-tag" :class="{ now: i === 0 }">{{ s.tag }}</span>
        </div>
        <div class="step-title">{{ s.title }}</div>
        <div class="step-position">{{ s.position }}</div>
        <div class="step-actions">{{ s.actions }}</div>
      </div>
    </div>

    <div class="growth-summary">
      公路端先跑通，水运端可延伸，平台可开放。它的价值不仅在于当前能做什么，更在于它能长成什么。
    </div>
  </section>
</template>

<script setup>
const steps = [
  {
    tag: "当前",
    title: "决策引擎",
    position: "跑通公路端原型",
    actions: "验证动态风险引擎与多角色触达闭环",
  },
  {
    tag: "1年内",
    title: "开放接口",
    position: "物流公司可基于引擎开发专属智能体",
    actions: "1-2 家公司试点 10 辆车 3 个月",
  },
  {
    tag: "2-3年",
    title: "众创平台",
    position: "跨境物流气象智能体开发平台",
    actions: "提供数据、工具、标准接口，吸引 TMS、保险等入驻",
  },
];
</script>

<style scoped>
.growth-card {
  border: 1px solid rgba(22, 163, 74, 0.3);
}
.growth-flow {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.growth-step {
  flex: 1;
  border: 1px solid rgba(30, 50, 90, 0.12);
  border-radius: 10px;
  padding: 10px;
  background: rgba(255,255,255,0.60);
  position: relative;
}
.growth-step.current {
  border-color: rgba(22, 163, 74, 0.5);
  background: rgba(22, 163, 74, 0.06);
}
.growth-step:not(:last-child)::after {
  content: "→";
  position: absolute;
  right: -9px;
  top: 40%;
  color: #16a34a;
  font-weight: 700;
  z-index: 1;
}
.step-marker {
  display: flex;
  align-items: center;
  gap: 6px;
}
.step-num {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(79, 109, 245, 0.2);
  color: #4f6df5;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.current .step-num {
  background: rgba(22, 163, 74, 0.25);
  color: #16a34a;
}
.step-tag {
  font-size: 10px;
  color: #64748f;
}
.step-tag.now {
  color: #16a34a;
  font-weight: 700;
}
.step-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-h, #1c2a44);
  margin-top: 6px;
}
.step-position {
  font-size: 11px;
  color: #5b6b85;
  margin-top: 4px;
  line-height: 1.5;
}
.step-actions {
  font-size: 10px;
  color: #64748f;
  margin-top: 4px;
  line-height: 1.5;
}
.growth-summary {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(30, 50, 90, 0.14);
  font-size: 11px;
  color: #16a34a;
  line-height: 1.6;
}
</style>
