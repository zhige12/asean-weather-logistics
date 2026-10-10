<template>
  <!-- embedded=true：作为「开放平台 · 智能体配置器」卡的尾段渲染，不再套一层 card，
       答辩时一页讲完「企业现在就能配 → 企业自己开发 → 全行业众创」 -->
  <section :class="embedded ? 'growth-embed' : 'card growth-card'">
    <h3 v-if="!embedded">
      成长路径 · 三步走
      <span class="route-state">众创贯穿三步</span>
    </h3>
    <div v-else class="ge-head">
      成长路径 · 三步走
      <span class="ge-head-tag">众创贯穿三步</span>
    </div>
    <div class="ge-hint">三步都在做众创，只是参与方一路放大：从一家企业自己配，到全行业在平台上互相配</div>

    <div class="growth-flow">
      <div v-for="(s, i) in steps" :key="i" class="growth-step" :class="{ current: i === 0 }">
        <div class="step-marker">
          <span class="step-num">{{ i + 1 }}</span>
          <span class="step-tag" :class="{ now: i === 0 }">{{ s.tag }}</span>
        </div>
        <div class="step-title">{{ s.title }}</div>
        <div class="step-position">{{ s.position }}</div>
        <div class="step-actions">{{ s.actions }}</div>
        <!-- 每一步都写清「这一步企业自己动手做什么」，众创才有落点而不只是口号 -->
        <div class="step-cocreate">
          <span class="cc-flag">众创 · 第 {{ i + 1 }} 步</span>
          <span class="cc-text">{{ s.cocreate }}</span>
        </div>
      </div>
    </div>

    <div class="growth-thread">
      <span class="gt-label">众创主线</span>
      <span class="gt-text">{{ thread }}</span>
    </div>
  </section>
</template>

<script setup>
defineProps({
  /** 嵌入「开放平台 · 智能体配置器」卡尾段时为 true */
  embedded: { type: Boolean, default: false },
});

const steps = [
  {
    tag: "当前",
    title: "决策引擎 + 模板库",
    position: "跑通公路端原型，同一引擎千企千面",
    actions: "动态风险引擎与多角色触达闭环已上线；智能体模板库内置冷链火龙果 / 电子元件防潮 / 大宗普货三套预设",
    cocreate: "企业自己选模板、拖风险阈值、勾触达名单——参数由用户产生，不是我们代写",
  },
  {
    tag: "1年内",
    title: "开放配置 · 专属智能体",
    position: "物流公司基于引擎开发自己的专属智能体",
    actions: "模板自定义 + 配置 API/SDK 开放，企业可新增货类、阈值、触达规则；1-2 家公司试点 10 辆车 3 个月",
    cocreate: "企业自己写规则、发布模板，沉淀回来充实模板库，不再只有我们预置的 3 个",
  },
  {
    tag: "2-3年",
    title: "众创平台",
    position: "跨境物流气象智能体开发平台",
    actions: "提供数据、工具、标准接口，吸引 TMS、保险、货主入驻",
    cocreate: "模板可发布、可复用、可分成，从「我们替企业配」变成「企业在平台上互相配」",
  },
];

// 答辩口径：三步都在做众创，只是参与方一路放大
const thread =
  "选模板调参数（已上线）→ 企业自己开发专属智能体（1 年内）→ 全行业在平台上互相供给（2-3 年）";
</script>

<style scoped>
.growth-card {
  border: 1px solid rgba(22, 163, 74, 0.3);
}
/* 嵌入态：不重复 card 外观，只当配置器卡的尾段 */
.growth-embed {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ge-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 700;
  color: var(--text-h, #1c2a44);
}
.ge-head-tag {
  font-size: 11px;
  font-weight: 600;
  color: #7c5cff;
}
.ge-hint {
  font-size: 11px;
  color: #5b6b85;
  line-height: 1.6;
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
/* 众创落点：紫色与配置器同一语义（企业自己动手），三步都有，不再只属于第 3 步 */
.step-cocreate {
  margin-top: 6px;
  padding-top: 5px;
  border-top: 1px dashed rgba(124, 92, 255, 0.28);
  font-size: 10px;
  line-height: 1.5;
  color: #5b4a8f;
}
.cc-flag {
  display: inline-block;
  font-size: 9px;
  font-weight: 700;
  color: #7c5cff;
  background: rgba(188, 140, 255, 0.14);
  border-radius: 7px;
  padding: 1px 6px;
  margin-right: 4px;
}
/* 众创主线：一条横贯三步的轴，答辩时指这一句 */
.growth-thread {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 10px;
  padding: 7px 9px;
  border-radius: 9px;
  background: linear-gradient(90deg, rgba(22, 163, 74, 0.08), rgba(188, 140, 255, 0.16));
  border: 1px solid rgba(124, 92, 255, 0.28);
}
.gt-label {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #4f6df5, #7c5cff);
  border-radius: 8px;
  padding: 2px 8px;
}
.gt-text {
  font-size: 11px;
  line-height: 1.6;
  color: #33415c;
  font-weight: 600;
}
.growth-summary {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(30, 50, 90, 0.14);
  font-size: 11px;
  color: #16a34a;
  line-height: 1.6;
}
/* 嵌到 360px 窄栏里时横排会把每一步挤成竖字，改成纵向步骤（箭头跟着转向下） */
.growth-embed .growth-flow {
  flex-direction: column;
  gap: 12px;
  margin-top: 4px;
}
.growth-embed .growth-step:not(:last-child)::after {
  content: "↓";
  left: 50%;
  right: auto;
  top: auto;
  bottom: -13px;
  transform: translateX(-50%);
}
.growth-embed .growth-thread {
  margin-top: 4px;
}
.growth-embed .growth-summary {
  margin-top: 4px;
}
</style>
