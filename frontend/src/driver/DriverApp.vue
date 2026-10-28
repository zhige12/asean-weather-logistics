<template>
  <div class="phone" :class="{ 'nav-mode': navigating || previewing, 'phone-mini': !pcLayout }">
    <!-- ====== HOME PAGE（高德风格：全屏地图 + 悬浮搜索 + 宫格 + 胶囊 Tab）====== -->
    <div v-if="!navigating && !previewing" class="home-page amap">
      <!-- 全屏可交互地图背景 -->
      <div ref="homeMapEl" class="home-map"></div>

      <!-- 右侧浮动地图控件 -->
      <div class="hm-ctrl">
        <button class="hm-ctrl-btn" title="放大" @click="homeZoom(1)">＋</button>
        <button class="hm-ctrl-btn" title="缩小" @click="homeZoom(-1)">－</button>
        <!-- 图钉用内联 SVG：📍 emoji 在 Android WebView 上渲染成圆头“棒棒糖”样式，跨平台不一致 -->
        <button class="hm-ctrl-btn" title="回到网络中心" @click="homeLocate()">
          <svg class="pin-ico" viewBox="0 0 24 24" aria-hidden="true">
            <path fill-rule="evenodd" d="M12 2a7.5 7.5 0 0 0-7.5 7.5c0 5.1 6.1 11.5 7.1 12.4a.57.57 0 0 0 .8 0c1-.9 7.1-7.3 7.1-12.4A7.5 7.5 0 0 0 12 2Zm0 5.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z"/>
          </svg>
        </button>
        <button class="hm-ctrl-btn base-btn" title="切换底图" @click="cycleBase">{{ baseName }}</button>
        <!-- 整机界面语言切换（国际化演示）：中文 ↔ Tiếng Việt，字典翻译见 i18n.js -->
        <button class="hm-ctrl-btn lang-toggle" :class="{ on: uiLang === 'vi' }" title="界面语言 Ngôn ngữ" @click="toggleUiLang">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
            <circle cx="12" cy="12" r="9"/><path d="M3.2 9.5h17.6M3.2 14.5h17.6"/><path d="M12 3c2.6 2.4 4 5.6 4 9s-1.4 6.6-4 9c-2.6-2.4-4-5.6-4-9s1.4-6.6 4-9Z"/>
          </svg>
          <span class="lang-tag">{{ uiLang === 'zh' ? 'VN' : '中' }}</span>
        </button>
        <!-- 桌面端专属：布局切换（PC 整窗大屏 ↔ 移动版 420px 手机窄列），手机上不显示 -->
        <button v-if="isDesktop" class="hm-ctrl-btn layout-btn" :title="pcLayout ? '切换到移动版（手机小屏样式）' : '切换到PC版（整窗大屏样式）'" @click="toggleLayoutMode">
          <svg v-if="pcLayout" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="7.5" y="2.5" width="9" height="19" rx="2"/><path d="M10.5 18.8h3"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true">
            <rect x="2.5" y="4.5" width="19" height="12.5" rx="1.5"/><path d="M8 20.5h8M12 17v3.5"/>
          </svg>
        </button>
        <!-- 桌面端专属：一键浏览器全屏（演示大屏态）；手机端不显示 -->
        <button v-if="isDesktop" class="hm-ctrl-btn fs-btn" :title="isFullscreen ? '退出全屏' : '全屏显示'" @click="toggleFullscreen">
          <svg v-if="!isFullscreen" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true">
            <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/>
          </svg>
        </button>
      </div>

      <!-- 模式 / 路线状态浮标 -->
      <div class="hm-modechip" :class="mode === 'LOGISTICS' ? 'logi' : 'pub'">
        <span class="mode-name">{{ mode === 'LOGISTICS' ? '物流任务模式' : '普通导航模式' }}</span>
        <template v-if="mode === 'LOGISTICS'">
          <span class="strip-dot"></span>
          <span class="strip-text">{{ statusText }}</span>
          <button class="mode-act preview" @click="showOrderDetail = true">📋 预览订单</button>
          <span v-if="task && task.status === 'ACCEPTED'" class="mode-act done">已接单</span>
          <span class="mode-act-exit" @click="exitLogisticsMode">退出</span>
        </template>
        <span v-else class="mode-hint">免费导航 · 天气与口岸提醒</span>
      </div>

      <!-- 悬浮搜索框：收起单行 / 展开起终点输入；宫格收缩时下沉到宫格原位置 -->
      <div class="hm-search" :class="{ open: homeSearchOpen, lowered: homeCollapsed && !homeSearchOpen }">
        <div v-if="!homeSearchOpen" class="hm-search-bar" @click="homeSearchOpen = true">
          <!-- 放大镜：同样细线 SVG，与麦克风/宫格图标成套 -->
          <span class="hm-ico hm-mic">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#64748b"
                 stroke-width="1.6" stroke-linecap="round">
              <circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>
            </svg>
          </span>
          <span class="hm-ph">查找地点、规划路线</span>
          <!-- 麦克风：以前是个纯装饰 SVG（点了只靠冒泡展开搜索框），现在指向 AI志宁——
               语音输入入口统一收在小球里（原生系统语音识别优先，浏览器降级 Web Speech） -->
          <button type="button" class="hm-ico hm-mic hm-mic-btn" title="对 AI志宁说话" @click.stop="openAiAssistant">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#64748b"
                 stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
              <rect x="9" y="3" width="6" height="11" rx="3"/>
              <path d="M5.5 11.5a6.5 6.5 0 0 0 13 0"/><path d="M12 18v3"/><path d="M8.5 21h7"/>
            </svg>
          </button>
        </div>
        <div v-else class="hm-search-panel">
          <!-- 起终点一体输入盒：左侧绿-虚线-红站点轨道 + 右侧交换按钮（高德/滴滴式），替代盒式双输入框 -->
          <div class="od-box">
            <div class="od-rail"><span class="od-dot from"></span><span class="od-line"></span><span class="od-dot to"></span></div>
            <div class="od-fields">
              <input v-model="originId" list="node-suggestions" placeholder="从哪里出发？如 南宁" @focus="$event.target.select()" />
              <div class="od-divider"></div>
              <input v-model="destinationId" list="node-suggestions" placeholder="要去哪里？如 河内" @focus="$event.target.select()" />
            </div>
            <button class="od-swap" title="交换起终点" @click="swapOD">⇅</button>
          </div>
          <datalist id="node-suggestions">
            <option v-for="p in placeSuggestions" :key="p.name" :value="p.name">{{ p.tag }}</option>
          </datalist>
          <div class="quick-picks">
            <span class="quick-label">常用路线</span>
            <div class="quick-chips">
              <button v-for="r in quickRoutes" :key="r.key" class="quick-chip" @click="originId = r.from; destinationId = r.to">{{ r.label }}</button>
            </div>
          </div>
          <!-- 多路线候选 -->
          <div v-if="candidates.length" class="cand-section">
            <div class="cand-header">
              <span>可选路线（{{ candidates.length }} 条）</span>
              <span class="cand-hint">点选一条进入地图预览</span>
            </div>
            <div v-for="c in candidates" :key="c.key" class="cand-item" :class="{ sel: selectedMode === 'road' && c.key === selectedKey }" @click="openPreview(c.key)">
              <div class="cand-left">
                <span class="cand-radio" :class="{ on: selectedMode === 'road' && c.key === selectedKey }"></span>
                <div class="cand-info">
                  <div class="cand-title">{{ c.label }}</div>
                  <div class="cand-via">{{ c.via }}</div>
                  <div class="cand-meta">
                    <span class="meta-tag">{{ c.hours }}h</span>
                    <span class="meta-tag">{{ c.distanceKm }}km</span>
                    <span class="meta-tag" :class="{ warn: c.riskCount > 0 }">{{ c.riskCount > 0 ? '⚠ ' + c.riskCount + ' 风险' : '✓ 无风险' }}</span>
                  </div>
                </div>
              </div>
              <div class="cand-right">
                <span v-if="c.hazardProbability >= 0" class="prob-pill" :title="c.hazardProbability < 15 && !c.hazardOccurred ? '灾害概率 ' + c.hazardProbability + '%（低于阈值视为通畅）' : ''" :class="probClass(c.hazardProbability, c.hazardOccurred)">{{ probText(c) }}</span>
                <span class="prob-arrow" v-if="selectedMode === 'road' && c.key === selectedKey">▶</span>
              </div>
            </div>
            <!-- 可坐船推荐位（平陆运河走廊）：与公路候选并列，不带货也能全程坐船出境；禁航时标红并说明情况 -->
            <div v-if="canalOption" class="cand-item canal-cand" :class="{ sel: selectedMode === 'canal', 'canal-blocked': canalBlockedNow }" @click="selectCanal">
              <div class="cand-left">
                <span class="cand-radio" :class="{ on: selectedMode === 'canal' }"></span>
                <div class="cand-info">
                  <div class="cand-title">🚢 {{ canalOption.label }}</div>
                  <div class="cand-via">{{ canalOption.via }}</div>
                  <div class="cand-meta">
                    <span class="meta-tag">{{ canalOption.hours }}h</span>
                    <span class="meta-tag">{{ canalOption.distanceKm }}km</span>
                    <span v-if="!canalBlockedNow" class="meta-tag ok">✓ 不受公路熔断影响</span>
                    <span v-else class="meta-tag blocked">🚫 {{ canalBlockReasonNow }}</span>
                  </div>
                </div>
              </div>
              <div class="cand-right">
                <span v-if="!canalBlockedNow" class="canal-badge">可坐船</span>
                <span v-else class="canal-badge blocked">🚫 已禁航</span>
                <span class="prob-arrow" v-if="selectedMode === 'canal'">▶</span>
              </div>
            </div>
          </div>
          <div class="hm-search-actions">
            <button class="hm-close" @click="homeSearchOpen = false">收起</button>
            <button class="start-btn" :disabled="routeLoading || candidateLoading || !originId || !destinationId" @click="btnAction">
              <span v-if="routeLoading || candidateLoading" class="spinner"></span>
              <span v-if="routeLoading">路线计算中…</span>
              <span v-else-if="candidateLoading">搜索路线中…</span>
              <span v-else>{{ candidates.length ? '查看路线预览' : '搜索路线并进入地图预览' }}</span>
            </button>
          </div>
        </div>
      </div>

      <!-- 宫格快捷入口（物流功能）：在其上上下滑动可整体收起，只留顶部搜索框 -->
      <div v-show="!homeSearchOpen" class="hm-grid" :class="{ collapsed: homeCollapsed }"
           @pointerdown="onGridSwipeStart" @pointermove="onGridSwipeMove" @pointerup="onGridSwipeEnd" @pointercancel="onGridSwipeEnd">
        <button v-for="g in homeGrid" :key="g.key" class="hm-grid-item" @click="onHomeGrid(g.key)">
          <span class="hm-grid-ico" :style="{ color: g.color }">
            <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor"
                 stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" v-html="g.svg"></svg>
          </span>
          <span class="hm-grid-label">{{ g.label }}</span>
        </button>
      </div>

      <!-- 收缩把手：展开时宫格上方小箭头（点/下拉收起），收起时胶囊（点/上拉展开） -->
      <button v-show="!homeSearchOpen && !homeCollapsed" class="hm-collapse-grip"
              @click="tapCollapseGrip" @pointerdown="onGridSwipeStart" @pointermove="onGridSwipeMove" @pointerup="onGridSwipeEnd" @pointercancel="onGridSwipeEnd">⌄</button>
      <button v-show="!homeSearchOpen && homeCollapsed" class="hm-expand-pill"
              @click="tapExpandPill" @pointerdown="onGridSwipeStart" @pointermove="onGridSwipeMove" @pointerup="onGridSwipeEnd" @pointercancel="onGridSwipeEnd">⌃ 功能</button>

      <!-- 去X 导航卡片 -->
      <div v-if="homeGoCard && !homeSearchOpen" class="hm-gocard" :class="{ collapsed: homeCollapsed }">
        <span class="hm-go-ico">🚗</span>
        <div class="hm-go-info">
          <div class="hm-go-title">去{{ destinationName }}</div>
          <div class="hm-go-meta">{{ goKm }}公里 · {{ goMin }}分钟</div>
          <div class="hm-go-bar"></div>
        </div>
        <button class="hm-go-btn" @click="btnAction">导航</button>
      </div>

      <!-- 底部胶囊 Tab -->
      <nav class="hm-tabbar">
        <button v-for="t in homeTabs" :key="t.key" class="hm-tab" :class="{ active: tab === t.key }" @click="tab = t.key">
          <span class="hm-tab-ico">{{ t.icon }}</span>
          <span class="hm-tab-label">{{ t.label }}</span>
          <span v-if="t.key === 'task' && taskChange && !taskChange.confirmed" class="hm-tab-dot"></span>
        </button>
      </nav>

      <!-- 非首页 tab：底部抽屉 -->
      <div v-if="tab !== 'route'" class="hm-sheet">
        <div class="hm-sheet-head">
          <span>{{ tabTitle }}</span>
          <button class="hm-sheet-close" @click="tab = 'route'">✕</button>
        </div>
        <div class="hm-sheet-body">
            <!-- ===== 任务变更（调度大屏对齐：第五幕） ===== -->
            <div v-show="tab === 'task'">
              <section class="card task-card" :class="{ confirmed: taskChange && taskChange.confirmed }">
                <div class="card-head">
                  <span>任务变更通知</span>
                  <span v-if="taskChange" class="task-state" :class="taskChange.confirmed ? 'ok' : 'pending'">
                    {{ taskChange.confirmed ? '已确认' : '待确认' }}
                  </span>
                </div>
                <template v-if="taskChange">
                  <div class="task-meta">
                    <span class="task-channel">{{ taskChange.channel || 'App推送' }}</span>
                    <span class="task-plan">方案 {{ taskChange.planId }}</span>
                  </div>
                  <!-- 语言切换 -->
                  <div class="task-lang">
                    <button class="lang-btn" :class="{ on: taskLang === 'zh' }" @click="taskLang = 'zh'">🇨🇳 中文</button>
                    <button class="lang-btn" :class="{ on: taskLang === 'vi' }" @click="taskLang = 'vi'">🇻🇳 Tiếng Việt</button>
                    <button class="speak-btn" @click="speakTask(taskLang)" title="语音播报">🔊 播报</button>
                  </div>
                  <pre class="task-msg">{{ taskMessageText }}</pre>
                  <!-- 权益保障包高亮 -->
                  <div class="rights-box">
                    <div class="rights-title">您的权益保障</div>
                    <div class="rights-item">✓ 随船期间按出勤计算工时，额外发放随船补贴</div>
                    <div class="rights-item">✓ 车辆滚装段已购买专项运输险</div>
                    <div class="rights-item">✓ 船上安排司机休息舱位，含餐饮</div>
                    <div class="rights-item">✓ 抵达海防港后公司安排返程交通</div>
                    <div class="rights-item">✓ 本次变更属不可抗力调度调整，不视为司机违约</div>
                  </div>
                  <button v-if="!taskChange.confirmed" class="confirm-task-btn" :disabled="taskConfirming" @click="confirmTaskChange">
                    {{ taskConfirming ? '确认中…' : '确认接收' }}
                  </button>
                  <button v-else class="confirm-task-btn done" disabled>已确认接收 · 新路线已同步</button>
                  <div class="task-hint">如有异议，请联系调度中心。切换运输方式是调度端的决策权限。</div>
                </template>
                <div v-else class="empty">暂无任务变更。调度中心确认方案后，任务变更指令将实时推送到这里。</div>
              </section>

              <!-- AI 方案建议（让司机理解调度决策依据）：过“接单闸门”——未接单不展示调度端的 A/B/C
                   运输方式决策（那是调度端权限），确认接单后才可查看，避免与“司机只收执行指令”的定位矛盾。 -->
              <section class="card">
                <div class="card-head">
                  <span>AI 方案建议</span>
                  <button v-if="isTaskAccepted" class="refresh" @click="loadAgentPlans" :disabled="agentPlansLoading">{{ agentPlansLoading ? '分析中…' : '查看方案' }}</button>
                </div>
                <div v-if="!isTaskAccepted" class="empty">确认接单后，这里可查看调度端 AI 的路线决策依据。切换运输方式是调度端的决策权限。</div>
                <template v-else-if="agentPlans">
                  <div v-for="p in agentPlans.plans" :key="p.id" class="plan-item" :class="{ rec: p.id === (agentPlans.plans.find(x=>x.id==='B')?'B':'A') }">
                    <div class="plan-head">
                      <span class="plan-badge">{{ p.id }}</span>
                      <span class="plan-name">{{ p.name }}</span>
                    </div>
                    <div class="plan-meta">
                      <span :class="{ warn: p.extraHours > 0 }">时效 {{ p.extraHours != null ? (p.extraHours>0?'+':'')+p.extraHours+'h' : '不可用' }}</span>
                      <span :class="{ ok: p.costDeltaYuan < 0 }">费用 {{ p.costDeltaYuan != null ? (p.costDeltaYuan>0?'+':'')+p.costDeltaYuan+'元' : '-' }}</span>
                      <span class="plan-risk">货损 {{ p.damageRisk }}</span>
                    </div>
                  </div>
                  <div class="plan-rec">{{ agentPlans.recommendation }}</div>
                </template>
                <div v-else class="empty">点击查看调度端 AI 生成的三方案对比</div>
              </section>

              <!-- 触达状态（本人视角） -->
              <section class="card" v-if="outreachStatus.total">
                <div class="card-head"><span>触达状态</span><span class="count">{{ outreachStatus.confirmed }}/{{ outreachStatus.total }}</span></div>
                <div class="touch-bar">
                  <div class="touch-fill" :style="{ width: (outreachStatus.confirmed / outreachStatus.total * 100) + '%' }"></div>
                </div>
                <div class="touch-text">{{ outreachStatus.confirmed }} 人已确认，{{ outreachStatus.pending }} 人待确认<span v-if="outreachStatus.escalated">（{{ outreachStatus.escalated }} 人已升级语音外呼）</span></div>
              </section>
            </div>
            <div v-show="tab === 'alert'">
              <section class="card">
                <div class="card-head">
                  <span>AI 双语预警</span>
                  <button class="refresh" @click="buildWarning" :disabled="warningLoading">{{ warningLoading ? '生成中…' : '生成预警' }}</button>
                </div>
                <div v-if="warning" class="warning-box">{{ warning }}</div>
                <div v-else class="empty">点击生成 AI 中越双语预警</div>
              </section>
              <section class="card">
                <div class="card-head"><span>当前风险清单</span><span class="count">{{ risks.length }}</span></div>
                <div v-if="risks.length" class="risk-list">
                  <div v-for="r in risks" :key="r.edgeId" class="risk-item" :class="sev(r.severity)">
                    <div class="risk-reason">{{ r.reason }}</div>
                    <div class="risk-edge">{{ r.edgeId }} · {{ r.severity }}</div>
                  </div>
                </div>
                <div v-else class="empty">当前无生效风险</div>
              </section>
            </div>
            <div v-show="tab === 'kb'">
              <section class="card">
                <div class="card-head"><span>行业知识速查</span></div>
                <div class="kb-chips">
                  <button v-for="t in tips" :key="t" class="chip" @click="searchTips(t)">{{ t }}</button>
                </div>
                <div v-if="kbResults.length" class="kb-results">
                  <div v-for="k in kbResults" :key="k.id" class="kb-result">
                    <div class="kb-title">{{ k.id }} {{ k.title }}</div>
                    <div class="kb-content">{{ k.content }}</div>
                  </div>
                </div>
                <div v-else class="empty">点上方标签检索应对建议</div>
              </section>
            </div>
            <div v-show="tab === 'me'">
              <!-- 资料头卡：头像 + 姓名 + 车牌/车型标签，先亮身份再列明细 -->
              <section class="me-hero">
                <div class="me-avatar">{{ (driverName || '司').slice(0, 1) }}</div>
                <div class="me-hero-info">
                  <div class="me-hero-name">{{ driverName || '未设置姓名' }}</div>
                  <div class="me-hero-sub">
                    <span class="me-plate">{{ cargo.plate || '未登记车牌' }}</span>
                    <span class="me-truck">{{ truckType }}</span>
                  </div>
                </div>
                <span v-if="mode === 'LOGISTICS'" class="me-mode-tag">任务模式</span>
              </section>
              <!-- 物流任务模式下车辆与货物由派单下发，司机不可改，
                   避免演示中被改动导致大屏与司机端信息对不上 -->
              <div v-if="mode === 'LOGISTICS'" class="me-locked">
                物流任务模式：车牌与货物信息以公司派单为准，不可修改
              </div>
              <!-- 分组列表：无边框行内编辑，右对齐取值，接近微信/货拉拉式表单 -->
              <section class="me-group">
                <div class="me-row"><span class="me-label">姓名</span><input v-model="driverName" class="me-input" placeholder="请输入姓名" /></div>
                <div class="me-row"><span class="me-label">车牌</span><input v-model="cargo.plate" class="me-input" :disabled="mode === 'LOGISTICS'" placeholder="如 桂A·D12345" /></div>
                <div class="me-row"><span class="me-label">车型</span>
                  <select v-model="truckType" class="me-input me-select">
                    <option>冷藏半挂</option><option>普货半挂</option><option>危化罐车</option><option>大件平板</option><option>厢式中卡</option>
                  </select>
                </div>
                <div class="me-row"><span class="me-label">货物</span><input v-model="cargo.name" class="me-input" :disabled="mode === 'LOGISTICS'" placeholder="请输入货物名称" /></div>
                <div class="me-row"><span class="me-label">重量(t)</span><input v-model.number="cargo.weight" type="number" class="me-input" :disabled="mode === 'LOGISTICS'" placeholder="0" /></div>
                <div class="me-row"><span class="me-label">温控</span><input v-model="cargo.temp" class="me-input" :disabled="mode === 'LOGISTICS'" placeholder="如 冷鲜 2~6℃" /></div>
                <div class="me-row"><span class="me-label">语音播报</span>
                  <button class="me-switch" :class="{ on: voiceOn }" role="switch" :aria-checked="voiceOn" @click="voiceOn = !voiceOn"><span class="me-knob"></span></button>
                </div>
              </section>
              <button class="save-btn" @click="saveDriverInfo">保存信息</button>
            </div>
        </div>
      </div>

      <!-- 订单详情预览弹层：收到派单后查看订单信息 → 确认接单 / 拒绝此单 -->
      <transition name="fade">
        <div v-if="showOrderDetail && task" class="kb-overlay" @click.self="showOrderDetail = false">
          <div class="kb-panel order-panel">
            <div class="kb-head">订单信息 <span class="kb-close" @click="showOrderDetail = false">✕</span></div>
            <div class="order-state" :class="task.status === 'ACCEPTED' ? 'ok' : (task.status === 'REJECTED' ? 'bad' : 'wait')">
              {{ task.status === 'ACCEPTED' ? '✅ 已接单' : (task.status === 'REJECTED' ? '❌ 已拒单' : '⏳ 待接单') }}
              · {{ task.taskId }}
            </div>
            <div class="order-cargo">
              <span class="oc-ico">📦</span>
              <div class="oc-main">
                <div class="oc-name">{{ task.cargoName }}</div>
                <div class="oc-sub">{{ task.weightT }} 吨 · {{ task.temp }}</div>
              </div>
            </div>
            <div class="order-od">
              <div class="ood-row"><span class="ood-dot from"></span><span class="ood-label">起运</span><span class="ood-val">{{ nodeName(task.originId) }}</span></div>
              <div class="ood-row"><span class="ood-dot to"></span><span class="ood-label">目的</span><span class="ood-val">{{ nodeName(task.destinationId) }}</span></div>
            </div>
            <div class="order-kv"><span>承运司机</span><b>{{ task.driverName }}</b></div>
            <div class="order-kv"><span>车牌</span><b>{{ task.plate }}</b></div>
            <div class="order-kv"><span>送达时限</span><b>{{ task.deadline }}</b></div>
            <div v-if="task.routeLabel" class="order-kv"><span>调度下发路线</span><b>{{ task.routeLabel }}</b></div>
            <div v-if="task.routeSummary" class="order-kv"><span>路线摘要</span><b>{{ task.routeSummary }}</b></div>
            <div v-if="task.hazardProbability >= 0" class="order-kv"><span>AI 灾害概率</span><b :class="probClass(task.hazardProbability)">{{ task.hazardProbability }}%</b></div>
            <div v-if="task.aiAnalysis" class="order-ai">
              <div class="oa-title">🌦 出车前 AI 气象分析</div>
              <div class="oa-text">{{ task.aiAnalysis }}</div>
            </div>
            <div v-if="task.status !== 'ACCEPTED' && task.status !== 'REJECTED'" class="order-actions">
              <button class="order-btn reject" :disabled="taskAccepting || taskRejecting" @click="rejectTask">{{ taskRejecting ? '处理中…' : '❌ 拒绝此单' }}</button>
              <button class="order-btn accept" :disabled="taskAccepting || taskRejecting" @click="acceptTask">{{ taskAccepting ? '接单中…' : '✅ 确认接单' }}</button>
            </div>
            <div v-else class="order-done-hint">{{ task.status === 'ACCEPTED' ? '已接单，正在按调度路线导航。' : '已拒单，已退回普通导航模式。' }}</div>
          </div>
        </div>
      </transition>
    </div>

    <!-- ====== FULL-SCREEN NAVIGATION ====== -->
    <!-- ====== FULL-SCREEN MAP（路线预览 / 导航中）====== -->
    <div v-if="navigating || previewing" class="nav-screen" :class="{ split: previewing }">
      <!-- 地图区域：占上方剩余空间。预览时面板固定在下方，与地图上下分栏、互不重叠 -->
      <div class="nav-map-wrap">
        <!-- 地图 -->
        <div ref="mapEl" :key="_mapRenderKey" class="nav-map"></div>

        <!-- 底图加载遮罩：瓦片没到位前给明确加载态，取代裸白屏 -->
        <div v-if="mapVeil" class="map-veil">
          <span class="map-veil-spin"></span>
          <span class="map-veil-text">地图加载中…</span>
        </div>

        <!-- 地图图例 -->
        <div class="nav-legend">
          <span><i class="lg lg-route"></i>安全路段</span>
          <span><i class="lg lg-risk"></i>风险路段</span>
          <span><i class="lg lg-port"></i>口岸</span>
        </div>

        <!-- 手动切换底图：底图1 天地图影像 → 底图2 矢量 → 底图3 OSM → 底图4 D 盘离线，循环 -->
        <button class="base-switch-btn" title="切换底图" @click="cycleBase">🗺 {{ baseName }}</button>

        <!-- 顶部状态栏（预览 / 导航共用）-->
        <div ref="topStack" class="nav-topbar">
          <button class="nav-back" :title="navigating ? '退出导航' : '返回首页'"
                  @click="navigating ? requestExit() : exitPreview()">
            <span>✕</span>
          </button>
          <!-- 导航态也可切界面语言（与首页同一开关） -->
          <button class="nav-back nav-lang" :class="{ on: uiLang === 'vi' }" title="界面语言 Ngôn ngữ" @click="toggleUiLang">{{ uiLang === 'zh' ? 'VN' : '中' }}</button>
          <div class="nav-route-label">
            <span class="nav-from">{{ originName }}</span>
            <span class="nav-arrow">→</span>
            <span class="nav-to">{{ destinationName }}</span>
          </div>
          <div class="nav-status" :class="navigating ? statusClass : previewStatusClass">
            <span class="status-dot"></span>
            <span>{{ navigating ? statusText : previewStatusText }}</span>
          </div>
          <!-- 桌面端专属：导航态同样可切 移动版/PC版 布局与全屏 -->
          <button v-if="isDesktop" class="nav-fs-btn" :title="pcLayout ? '切换到移动版（手机小屏样式）' : '切换到PC版（整窗大屏样式）'" @click="toggleLayoutMode">
            <svg v-if="pcLayout" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="7.5" y="2.5" width="9" height="19" rx="2"/><path d="M10.5 18.8h3"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" aria-hidden="true">
              <rect x="2.5" y="4.5" width="19" height="12.5" rx="1.5"/><path d="M8 20.5h8M12 17v3.5"/>
            </svg>
          </button>
          <button v-if="isDesktop" class="nav-fs-btn" :title="isFullscreen ? '退出全屏' : '全屏显示'" @click="toggleFullscreen">
            <svg v-if="!isFullscreen" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>
            </svg>
            <svg v-else viewBox="0 0 24 24" aria-hidden="true">
              <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- 语音链路诊断：仅 ?voicedebug=1 时显示，用于定位"没声音"卡在哪一步 -->
      <div v-if="voiceDebugOn" class="voice-debug">🔊 {{ voiceDebug || '（尚无语音事件）' }}</div>

      <!-- 底部面板栈：导航模式下浮在地图底部；预览模式下改为上下分栏、固定在地图下方 -->
      <div ref="bottomStack" class="nav-bottom">
        <!-- Agent / 灾害推送 -->
        <transition name="slide-up">
          <div v-if="pushMsg" ref="pushBar" class="nav-push" :class="pushType" @click="pushMsg = ''">
            <span class="push-icon">⚠</span>
            <span class="push-text">{{ pushTitle }}：{{ pushMsg }}</span>
          </div>
        </transition>

        <!-- ====== 预览模式：路线点选面板（上下分栏，固定在地图下方，不遮挡路线）====== -->
        <template v-if="previewing">
          <div class="nav-info-card preview-card">
            <div class="eta-sheen"></div>
            <div class="preview-head">
              <div class="preview-head-left">
                <div class="preview-title">路线预览</div>
                <div class="preview-sub">共 {{ candidates.length }} 条可选路线 · 预计到达 {{ etaArrival }}</div>
              </div>
              <div class="nav-risk-badge" :class="previewStatusClass">
                <span class="badge-dot"></span>{{ previewStatusText }}
              </div>
              <button class="preview-toggle" :title="previewCollapsed ? '展开路线列表' : '收起路线列表'"
                      @click="togglePreviewCollapsed">{{ previewCollapsed ? '展开 ▴' : '收起 ▾' }}</button>
            </div>
            <div v-show="!previewCollapsed" ref="previewList" class="preview-list">
              <div v-for="(c, i) in candidates" :key="c.key"
                   class="pv-item" :class="{ sel: selectedMode === 'road' && c.key === selectedKey, safest: c.key === safestKey, risky: isHighRisk(c) }"
                   :style="{ animationDelay: (i * 60) + 'ms' }"
                   @click="selectCandidate(c.key)">
                <span class="cand-radio" :class="{ on: selectedMode === 'road' && c.key === selectedKey }"></span>
                <div class="pv-info">
                  <div class="pv-title">
                    {{ c.label }}
                    <span v-if="c.key === safestKey" class="pv-flag safest-flag">AI 最安全</span>
                    <span v-else-if="selectedMode === 'road' && c.key === selectedKey" class="pv-flag">已选</span>
                    <!-- 全域受阻：这些候选都是把已封闭路段降级放行才「能走」的，卡片必须自己说清楚 -->
                    <span v-if="c.notRecommended" class="pv-flag blocked-flag">不建议通行</span>
                  </div>
                  <div class="pv-via">{{ c.via }}</div>
                  <div class="pv-meta">
                    <span class="meta-tag">{{ c.hours }}h</span>
                    <span class="meta-tag">{{ c.distanceKm }}km</span>
                    <span class="meta-tag" :class="{ warn: c.riskCount > 0 }">
                      {{ c.riskCount > 0 ? '⚠ ' + c.riskCount + ' 处风险' : '✓ 无风险' }}
                    </span>
                  </div>
                </div>
                <div class="pv-right">
                  <span v-if="c.hazardProbability >= 0" class="prob-pill glow" :class="probClass(c.hazardProbability, c.hazardOccurred)">
                    {{ probText(c) }}
                  </span>
                  <span v-else class="prob-pill unknown">暂无预测</span>
                  <button class="pv-detail-btn" :disabled="c.hazardProbability < 0"
                          @click.stop="openHazardDetail(c)">
                    展开详情 ▾
                  </button>
                </div>
              </div>
              <!-- 可坐船推荐位（平陆运河走廊）：与公路候选并列，点选后「开始导航」先导航至南宁港六景上船 -->
              <div v-if="canalOption" class="pv-item canal-cand" :class="{ sel: selectedMode === 'canal', 'canal-blocked': canalBlockedNow }" @click="selectCanal">
                <span class="cand-radio" :class="{ on: selectedMode === 'canal' }"></span>
                <div class="pv-info">
                  <div class="pv-title">🚢 {{ canalOption.label }}<span v-if="!canalBlockedNow" class="pv-flag canal-flag">可坐船</span><span v-else class="pv-flag canal-flag blocked">🚫 已禁航</span></div>
                  <div class="pv-via">{{ canalOption.via }}</div>
                  <div class="pv-meta">
                    <span class="meta-tag">{{ canalOption.hours }}h</span>
                    <span class="meta-tag">{{ canalOption.distanceKm }}km</span>
                    <span v-if="!canalBlockedNow" class="meta-tag ok">✓ 不受公路熔断影响</span>
                    <span v-else class="meta-tag blocked">🚫 {{ canalBlockReasonNow }}，暂不可选</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div class="preview-actions">
            <button class="nav-btn" @click="fitRoute()" title="全览路线">
              <span>🔍</span>
            </button>
            <button class="start-btn preview-start" :disabled="routeLoading || !candidates.length" @click="startNavigation">
              <span v-if="routeLoading" class="spinner"></span>
              <span v-else>开始导航</span>
            </button>
          </div>
        </template>

        <!-- ====== 导航模式：ETA / 操作按钮 / 菜单 ====== -->
        <template v-if="navigating">
        <div class="nav-info-card eta-card">
          <div class="eta-sheen"></div>
          <div class="nav-info-main">
            <div class="nav-eta">
              <span class="eta-num">{{ navHoursDisplay }}</span>
              <span class="eta-unit">{{ navEtaUnit }}</span>
            </div>
            <div class="nav-dist">
              <span class="dist-num">{{ route.totalDistanceKm || '--' }}</span>
              <span class="dist-unit">km</span>
            </div>
            <div class="nav-risk-badge" :class="statusClass">
              <span class="badge-dot"></span>{{ statusText }}
            </div>
          </div>
          <div class="nav-info-sub">
            <span class="eta-arrival"><i class="pulse-dot"></i>预计到达 {{ etaArrival }}</span>
            <span class="eta-live" v-if="navigating">导航中</span>
            <span v-if="route.extraHours > 0" class="delay-tag">延误 +{{ route.extraHours }}h</span>
            <!-- 水运方案：明示陆水联运走平陆运河，司机一眼知道自己在哪种运输方式上 -->
            <span v-if="isCanalPlan" class="canal-tag">🚢 陆水联运 · 平陆运河</span>
          </div>
          <div class="eta-bar"><span class="eta-bar-fill"></span></div>
        </div>

        <!-- 调度方案 C 生效中：明写车辆已停驶，并给司机一个手动解除等待的入口 -->
        <div v-if="waitHold" class="hold-card">
          <div class="hold-main">
            <div class="hold-title">⏸ 原地等待中 · 车辆停驶</div>
            <div class="hold-sub">已按调度指令停在当前位置，车标不再前进；冷链机组保持运行，待风险解除后恢复行驶。</div>
          </div>
          <button class="hold-resume" @click="resumeFromHold">恢复行驶</button>
        </div>

        <!-- 操作按钮 -->
        <div class="nav-actions">
          <button class="nav-btn" @click="locateMe" title="我的位置">
            <svg class="pin-ico" viewBox="0 0 24 24" aria-hidden="true">
              <path fill-rule="evenodd" d="M12 2a7.5 7.5 0 0 0-7.5 7.5c0 5.1 6.1 11.5 7.1 12.4a.57.57 0 0 0 .8 0c1-.9 7.1-7.3 7.1-12.4A7.5 7.5 0 0 0 12 2Zm0 5.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8Z"/>
            </svg>
          </button>
          <button class="nav-btn" @click="fitRoute()" title="全览路线">
            <span>🔍</span>
          </button>
          <button class="nav-btn" @click="refreshWeather" title="气象">
            <span>🌤</span>
          </button>
          <button class="nav-btn exit-btn" @click="requestExit" title="退出导航">
            <span>退出导航</span>
          </button>
          <button class="nav-btn menu-btn" @click="showNavMenu = !showNavMenu" title="更多">
            <span>☰</span>
          </button>
        </div>

        <!-- 展开菜单：快捷查看风险/预警 -->
        <div v-if="showNavMenu" class="nav-menu">
          <div class="nav-menu-section">
            <div class="nav-menu-title">风险路段</div>
            <div v-if="pathRisks.length" class="risk-list">
              <div v-for="r in pathRisks" :key="r.edgeId" class="risk-item" :class="sev(r.severity)" @click="focusRisk(r)">
                <div class="risk-reason">{{ r.reason }}</div>
                <div class="risk-edge">{{ r.edgeId }} · 点击定位</div>
              </div>
            </div>
            <div v-else class="empty">全线通畅</div>
          </div>
          <div class="nav-menu-section">
            <div class="nav-menu-title">通关提示</div>
            <div class="customs-line" v-for="c in customsList" :key="c.portId">
              <span>{{ c.portName }}</span>
              <span class="ch-time">{{ c.currentHours }}h</span>
              <span v-if="c.adjusted" class="ch-adjust">拥堵</span>
            </div>
          </div>
          <div class="nav-menu-section" v-if="weatherPoints.length">
            <div class="nav-menu-title">沿线天气</div>
            <div class="weather-strip">
              <div v-for="w in weatherPoints.slice(0, 6)" :key="w.nodeId" class="weather-dot">
                <span class="wd-name">{{ w.nodeName }}</span>
                <span class="wd-temp">{{ Math.round(w.temperatureC) }}°</span>
                <span class="wd-rain" v-if="w.precipitationMm > 0">💧</span>
              </div>
            </div>
          </div>
          <div class="nav-menu-section">
            <button class="nav-exit-btn" @click="requestExit">退出导航</button>
          </div>
        </div>
        </template>

        <!-- 自由视角时的一键回到导航视角（贴在浮层上方，同高德/百度）-->
        <button v-if="navigating && navOffView" class="recenter-btn" @click="zoomToNav">
          <span class="rc-ico">◎</span>回到当前位置
        </button>
      </div>

      <!-- 绕行方案弹层 -->
      <transition name="fade">
        <div v-if="showReroute" class="kb-overlay" @click.self="showReroute = false">
          <div class="kb-panel">
            <div class="kb-head">绕行方案详情 <span class="kb-close" @click="showReroute = false">✕</span></div>
            <div class="cmp-row"><span class="cmp-label">原定口岸</span><span>友谊关（E4）</span></div>
            <div class="cmp-row"><span class="cmp-label">改用口岸</span><span>芒街（E9）</span></div>
            <div class="cmp-row"><span class="cmp-label">原耗时</span><span>{{ route.baselineHours }}h</span></div>
            <div class="cmp-row"><span class="cmp-label">新耗时</span><span>{{ route.currentHours }}h</span></div>
            <div class="cmp-row warn"><span class="cmp-label">额外延误</span><span>+{{ route.extraHours }}h</span></div>
            <div class="advice-box">{{ route.rerouted ? '建议按新路线行驶，已同步越南语提示。' : '当前无需绕行。' }}</div>
          </div>
        </div>
      </transition>

      <!-- 灾害概率详情弹层 -->
      <transition name="fade">
        <div v-if="hazardDetail" class="kb-overlay" @click.self="hazardDetail = null">
          <div class="kb-panel hazard-detail-panel">
            <div class="kb-head">{{ hazardDetail.title }} · 灾害概率明细 <span class="kb-close" @click="hazardDetail = null">✕</span></div>
            <div class="hd-total">
              <span class="hd-total-label">{{ topHazardName(hazardDetail) }}概率</span>
              <span class="prob-pill" :class="probClass(hazardDetail.probability)">{{ hazardDetail.probability }}%</span>
            </div>
            <div v-for="b in hazardDetail.breakdown" :key="b.key" class="hd-item">
              <div class="hd-item-top">
                <span class="hd-name">{{ b.label }}</span>
                <span class="hd-pct" :class="probClass(b.probability)">{{ b.probability }}%</span>
              </div>
              <div class="hd-bar"><div class="hd-bar-in" :class="probClass(b.probability)" :style="{ width: Math.max(b.probability, 2) + '%' }"></div></div>
              <div class="hd-reason">{{ b.reason }}</div>
            </div>
          </div>
        </div>
      </transition>

      <!-- 全域受阻：台风这类区域性灾害同时盖住友谊关/芒街/河口等全部出境走廊、平陆运河也禁航时，
           绕行只是从一个风险区换进另一个风险区。所以不自动换线、不自动局部绕行，
           改为按本地进度给出「返回起点 / 就近停靠等待 / 暂缓发车」，由司机点一下才执行 -->
      <transition name="fade">
        <div v-if="corridorNotice" class="corridor-panel">
          <div class="hazard-head">
            <span class="hazard-icon">🌀</span>
            <div class="hazard-head-text">
              <div class="hazard-title">{{ corridorNotice.title }}</div>
              <div class="hazard-sub">{{ corridorNotice.actShort }} · {{ corridorNotice.waitText || '待灾害解除后复判' }}</div>
            </div>
            <button class="hazard-close" @click="dismissCorridorNotice" title="本轮不再提醒">✕</button>
          </div>
          <div class="cp-text">{{ corridorNotice.text }}</div>
          <div v-if="corridorNotice.blockedText" class="cp-blocked">{{ corridorNotice.blockedText }}</div>
          <div class="cp-basis">{{ corridorNotice.basis }}</div>
          <div class="hazard-actions">
            <button v-if="waitHold" class="hazard-btn ok" @click="resumeFromHold">解除等待 · 恢复行驶</button>
            <button v-else class="hazard-btn danger" @click="executeCorridorAction">{{ corridorNotice.primaryLabel }}</button>
            <button class="hazard-btn ghost" @click="dismissCorridorNotice">暂不处置</button>
          </div>
        </div>
      </transition>

      <!-- 接单模式遇险：路线标红 + 弹警告 + 风险语音之后，司机端**不自动绕路**，
           只把「正在等待调度中心下发换线指令」挂在屏底，换线入口仍是 outreach-update。
           不抢全域受阻面板的位置：走廊全封时该听的是「返回/停靠」，不是干等换线 -->
      <transition name="fade">
        <div v-if="waitDispatch && !corridorNotice" class="wait-panel">
          <div class="hazard-head">
            <span class="hazard-icon">⏳</span>
            <div class="hazard-head-text">
              <div class="hazard-title">正在等待调度中心下发换线指令</div>
              <div class="hazard-sub">已等待 {{ waitDispatchElapsed() }} · 本条路线已标红，不自动绕行</div>
            </div>
            <button class="hazard-close" @click="dismissWaitDispatch" title="知道了">✕</button>
          </div>
          <div class="wp-text">{{ waitDispatch.reason }}。请保持当前路线、减速慢行，换线指令到达后会提示您确认接收。</div>
          <div class="hazard-actions">
            <button v-if="waitHold" class="hazard-btn ok" @click="resumeFromHold">解除等待 · 恢复行驶</button>
            <button v-else class="hazard-btn danger" @click="holdForDispatch">就近安全停靠等待</button>
            <button class="hazard-btn ghost" @click="dismissWaitDispatch">知道了</button>
          </div>
        </div>
      </transition>

      <!-- 绕行方案点选：灾害触发后的换路面板（高风险灾害标红） -->
      <transition name="fade">
        <div v-if="rerouteOptions.length" class="hazard-panel">
          <div class="hazard-head">
            <span class="hazard-icon">⚠</span>
            <div class="hazard-head-text">
              <div class="hazard-title">{{ announceText || '前方发生灾害，请选择行驶方案' }}</div>
              <div class="hazard-sub">已规划 {{ rerouteOptions.length }} 条替代路线 · 点选即切换</div>
            </div>
            <button class="hazard-close" @click="rerouteOptions = []">✕</button>
          </div>

          <!-- 全部候选都有风险：明确告知，并引导司机不要盲选 -->
          <div v-if="allRerouteRisky" class="rz-warn">
            <span class="rz-warn-ico">⚠</span>
            <span>当前区域内所有替代路线均存在风险（灾害为区域性天气影响）。建议就近安全停车等待调度指令；如必须行驶，请选择<b>风险最低</b>的一条（已置顶）。</span>
          </div>

          <div v-for="opt in rerouteRanked" :key="opt.key" class="hazard-opt"
               :class="{ sel: opt.key === selectedKey, risky: isHighRisk(opt), best: opt.key === safestRerouteKey && opt.key !== selectedKey }"
               @click="applyRerouteOption(opt.key)">
            <div class="hazard-opt-top">
              <span class="hazard-opt-name">{{ optLabel(opt) }}</span>
              <span v-if="opt.key === selectedKey" class="rz-badge now">当前路线</span>
              <span v-else-if="opt.key === safestRerouteKey" class="rz-badge best">风险最低</span>
              <span v-if="isHighRisk(opt)" class="rz-badge danger">高风险 · 不推荐</span>
              <span v-if="opt.hazardProbability >= 0" class="prob-pill" :class="probClass(opt.hazardProbability, opt.hazardOccurred)">
                {{ probText(opt) }}
              </span>
              <span v-else class="prob-pill unknown">暂无预测</span>
            </div>
            <div class="hazard-opt-via">{{ opt.via }}</div>
            <div class="hazard-opt-bottom">
              <span class="hazard-opt-meta">{{ opt.hours }}h · {{ opt.distanceKm }}km</span>
              <div class="rz-tags">
                <span v-for="t in hazardTags(opt)" :key="t.label" class="rz-tag" :class="{ hot: t.high }">
                  {{ t.label }}{{ t.probability > 0 ? ' ' + t.probability + '%' : '' }}
                </span>
              </div>
            </div>
          </div>

          <div class="hazard-actions">
            <button class="hazard-btn ghost" @click="rerouteOptions = []">保持原路线</button>
          </div>
        </div>
      </transition>

      <!-- 退出导航二次确认 -->
      <transition name="fade">
        <div v-if="showExitConfirm" class="kb-overlay" @click.self="showExitConfirm = false">
          <div class="kb-panel exit-panel">
            <div class="kb-head">退出导航 <span class="kb-close" @click="showExitConfirm = false">✕</span></div>
            <div class="exit-tip">退出后将停止 GPS 定位、Agent 实时守护与语音播报，返回首页。当前路线可随时重新开始导航。</div>
            <div class="exit-btns">
              <button class="exit-choice cancel" @click="showExitConfirm = false">继续导航</button>
              <button class="exit-choice danger" @click="confirmExit">确认退出</button>
            </div>
          </div>
        </div>
      </transition>

      <!-- 调度任务变更：导航中弹出的「确认接收」抽屉（贴底，不遮挡上方调度路线预览） -->
      <transition name="fade">
        <div v-if="showDispatchConfirm && taskChange" class="dispatch-overlay" :class="{ collapsed: dispatchConfirmCollapsed }">
          <div class="dispatch-sheet">
            <div class="dc-head">
              <span class="dc-title">📱 调度任务变更 · 请确认接收</span>
              <button class="dc-toggle" @click="dispatchConfirmCollapsed = !dispatchConfirmCollapsed">
                {{ dispatchConfirmCollapsed ? '展开详情 ▴' : '看路线 ▾' }}
              </button>
            </div>
            <div v-show="!dispatchConfirmCollapsed" class="dc-body">
              <div class="dc-plan">
                <span class="task-channel">{{ taskChange.channel || 'App推送' }}</span>
                <span class="task-plan">方案 {{ taskChange.planId }}</span>
              </div>
              <div class="task-lang">
                <button class="lang-btn" :class="{ on: taskLang === 'zh' }" @click="taskLang = 'zh'">🇨🇳 中文</button>
                <button class="lang-btn" :class="{ on: taskLang === 'vi' }" @click="taskLang = 'vi'">🇻🇳 Tiếng Việt</button>
                <button class="speak-btn" @click="speakTask(taskLang)" title="语音播报">🔊 播报</button>
              </div>
              <pre class="task-msg">{{ taskMessageText }}</pre>
              <div class="rights-box">
                <div class="rights-title">您的权益保障</div>
                <div class="rights-item">✓ 随船期间按出勤计算工时，额外发放随船补贴</div>
                <div class="rights-item">✓ 车辆滚装段已购买专项运输险</div>
                <div class="rights-item">✓ 船上安排司机休息舱位，含餐饮</div>
                <div class="rights-item">✓ 抵达海防港后公司安排返程交通</div>
                <div class="rights-item">✓ 本次变更属不可抗力调度调整，不视为司机违约</div>
              </div>
            </div>
            <button class="confirm-task-btn" :disabled="taskConfirming" @click="confirmTaskChange">
              {{ taskConfirming ? '处理中…' : '✅ 确认接收并进入导航' }}
            </button>
            <div class="dc-hint">上方地图为调度中心下发的新路线预览，可点「看路线」查看后再确认接收。</div>
          </div>
        </div>
      </transition>
    </div>

    <!-- 左边缘侧滑返回指示条：仅拖动时点亮，跟手展示进度（不影响点击） -->
    <div class="swipe-edge" :class="{ on: swipeEdgeProgress > 0 }" aria-hidden="true">
      <span class="swipe-edge-glow" :style="{ transform: 'scaleX(' + swipeEdgeProgress + ')' }"></span>
      <span class="swipe-edge-chev" :style="{
              transform: 'translateX(' + (-16 + swipeEdgeProgress * 22) + 'px) scale(' + (0.7 + swipeEdgeProgress * 0.3) + ')',
              opacity: 0.35 + swipeEdgeProgress * 0.65
            }">‹</span>
    </div>

    <!-- AI 志宁悬浮小球（司机端）：点一下展开对话，说“导航去河内”即进入路线规划，
         查天气/查风险/口岸时效等只读指令由后端回结论直接展示（absolute 定位在 .phone 容器内） -->
    <AiBall ref="aiBallRef" pos="absolute" right="14px" bottom="84px" panel-width="min(390px, calc(100vw - 28px))">
      <DriverCopilot
        :origin-id="resolvedOriginId"
        :destination-id="resolvedDestinationId"
        :lang="uiLang === 'vi' ? 'vi-VN' : 'zh-CN'"
        @command="onAiCommand"
      />
    </AiBall>
  </div>
</template>

<script>
import axios from 'axios'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { reshapeForZoom } from '../utils/pathReshape.js'
import { Geolocation } from '@capacitor/geolocation'
import { createRouteFlow } from '../utils/routeFlow.js'
import { splitRouteByRisk, riskEdgeSet } from '../utils/routeSegments.js'
import { BASE_CHAIN, baseDef, makeTileLayer, tileUrlFromDef } from '../utils/tileSources.js'
// Leaflet Canvas 渲染器销毁竞态防护（_redraw 异步排队、销毁后 _ctx 已删仍回调 → reading 'save' 报错）
import '../utils/leafletCanvasGuard.js'
import { normalizeForTTS, detectLanguage, HAZARD_VN_MAP } from '../utils/ttsNormalizer.js'
import { fetchPlaceList, resolvePlaceToNode, apiErrorMessage } from '../utils/geocoder.js'
import { hasAdvisory, resolveAction, advisoryView, advisorySpeakText, ACTION_SHORT } from '../utils/corridorAdvisory.js'
import { createUiTranslator } from './i18n.js'
// AI 志宁悬浮小球与司机端对话舱：一句话驱动导航/查天气/查风险等既有能力
import AiBall from '../components/AiBall.vue'
import DriverCopilot from './DriverCopilot.vue'

/**
 * EPSG:3857 墨卡托 Y（单位：度当量）：lat → 180/π · ln(tan(π/4 + φ/2))。
 * 纬度方向不能用度数直接算跨度（高纬度被拉伸），铺满/适配缩放公式都靠它换算。
 */
function mercY(latDeg) {
  const phi = latDeg * Math.PI / 180
  return Math.log(Math.tan(Math.PI / 4 + phi / 2)) * 180 / Math.PI
}

/**
 * 底图 settle 之后允许的新增瓦片错误数，超过即判定「底图半坏」。
 * 一屏约 20~40 张瓦片 × 影像/注记两层，6 张取不到就已经是肉眼可见的模糊补位块了。
 */
const BASE_DEGRADE_ERROR_BUDGET = 6

// 底图短名（手动切换按钮显示用，序号制命名，与大屏同序）：底图1=天地图影像 / 底图2=天地图矢量 / 底图3=OSM / 底图4=D 盘离线瓦片
const BASE_SHORT_NAMES = { tianditu: '底图1', tdtvec: '底图2', osm: '底图3', vector: '底图4' }

// 口岸坐标（真实经纬度），供司机端地图标注
const PORTS = [
  { id: 'E4', name: '友谊关', lat: 21.9778, lng: 106.7145 },
  { id: 'E9', name: '芒街', lat: 21.5217, lng: 107.9672 },
  { id: 'E36', name: '河口', lat: 22.5089, lng: 103.9403 }
]

// 常用节点名称映射（司机可手动输入/选择）
const NODE_NAMES = {
  NN: '南宁', LJ: '南宁港六景作业区', CZ: '崇左', PX: '凭祥', YGG: '友谊关口岸', DX: '东兴', HK: '河口口岸',
  DD: '同登口岸', LS: '谅山', BG: '北江', BN: '北宁', HN: '河内', MC: '芒街口岸',
  HL: '下龙', HD: '海阳', HP: '海防', ND: '南定', NB: '宁平', TH: '清化', VH: '荣市',
  HT: '河静', DQH: '洞海', QT: '广治', HUE: '顺化', DN: '岘港', TY: '太原', TQ: '宣光',
  YB: '安沛', LC: '老街', SPA: '沙巴', HB: '和平', SL: '山萝', DB: '奠边府'
}

// 中国境内节点（其余为越南侧）。用于判断这一程是否跨境：
// 陆水联运方案的第一程是「南宁 → 南宁港六景作业区」，属国内段，不能标「跨境」。
const CN_NODE_IDS = new Set(['NN', 'LJ', 'CZ', 'PX', 'YGG', 'DX', 'HK'])

/**
 * 原生 TTS（Capacitor）模块缓存。
 * stop() 与 speak() 必须共用同一个模块实例，调用顺序才有保证；
 * 原实现每次现场 import()，两条链路各自解析、互不知晓先后，
 * stop() 可能落在新的 speak() 之后把新播报掐掉 —— 播报忽有忽无、串音的主因之一。
 */
let _nativeTtsPromise = null
function loadNativeTts() {
  if (!_nativeTtsPromise) {
    _nativeTtsPromise = import('@capacitor-community/text-to-speech').catch(() => null)
  }
  return _nativeTtsPromise
}

/** 模拟行驶节拍（毫秒）与速度（米/秒）。按里程平滑推进，避免按折线点跳。
 *  速度 50m/s（180km/h，真实卡车 25m/s 的 2 倍演示速）：比真实速度略快让车标动起来
 *  有导航感，剩余里程/耗时每分钟仍减 ~3km/3分，分钟位大数字持续可见下降；
 *  再往上调会显得穿楼悬浮、不像货车在跑。trim 阈值随本速度自动缩放。 */
const SIM_TICK_MS = 100
const SIM_SPEED_MPS = 50

// 名称 → ID 反向映射（司机输入中文/越南语名称时自动解析）
const NAME_TO_ID = {}
for (const [id, name] of Object.entries(NODE_NAMES)) {
  NAME_TO_ID[name] = id
  NAME_TO_ID[name.toLowerCase()] = id
  NAME_TO_ID[id] = id
  NAME_TO_ID[id.toLowerCase()] = id
}
// 越南语名称也加入映射（司机可以输入越南语地名）
const NODE_NAME_VN = {
  NN: 'Nam Ninh', LJ: 'cảng Nam Ninh (Lục Cảnh)', CZ: 'Sùng Tả', PX: 'Bằng Tường', YGG: 'cửa khẩu Hữu Nghị Quan',
  DX: 'Đông Hưng', HK: 'cửa khẩu Hà Khẩu',
  DD: 'cửa khẩu Đồng Đăng', LS: 'Lạng Sơn', BG: 'Bắc Giang', BN: 'Bắc Ninh',
  HN: 'Hà Nội', MC: 'cửa khẩu Móng Cái',
  HL: 'Hạ Long', HD: 'Hải Dương', HP: 'Hải Phòng', ND: 'Nam Định',
  NB: 'Ninh Bình', TH: 'Thanh Hóa', VH: 'Vinh',
  HT: 'Hà Tĩnh', DQH: 'Đồng Hới', QT: 'Quảng Trị', HUE: 'Huế', DN: 'Đà Nẵng',
  TY: 'Thái Nguyên', TQ: 'Tuyên Quang', YB: 'Yên Bái', LC: 'Lào Cai',
  SPA: 'Sa Pa', HB: 'Hòa Bình', SL: 'Sơn La', DB: 'Điện Biên Phủ'
}
for (const [id, vn] of Object.entries(NODE_NAME_VN)) {
  NAME_TO_ID[vn] = id
  NAME_TO_ID[vn.toLowerCase()] = id
}

export default {
  name: 'DriverApp',
  components: { AiBall, DriverCopilot },
  data() {
    return {
      // 换线跳变审计（黑匣子）：watchdog 每 400ms 采样车标/线首，跳变当场 console.warn
      _auditLastCar: null,
      _auditLastC0: null,
      _auditTimer: null,
      route: {},
      weatherPoints: [],
      risks: [],
      warning: '',
      warningLoading: false,
      customsList: [],
      online: false,
      pushMsg: '',
      pushTitle: '',
      pushType: 'info',
      pushLog: [],
      quickTips: false,
      kbResults: [],
      tips: ['泥石流', '冷链', '台风', '暴雨', '大雾', '高温'],
      timer: null,
      lastRerouted: false,
      lastRiskCount: 0,
      tab: 'route',
      // 整机界面语言：zh=中文 / vi=越南语。DOM 层字典翻译（i18n.js），未命中回落中文；
      // 持久化到 localStorage，刷新/重开仍保持所选语言
      uiLang: (() => { try { return localStorage.getItem('driver_ui_lang') === 'vi' ? 'vi' : 'zh' } catch (e) { return 'zh' } })(),
      showReroute: false,
      voiceOn: true,
      // 行程（司机可手动选择/输入起点终点）
      originId: 'NN',
      destinationId: 'HN',
      // 后端地名库（/api/geocode/list）：下拉候选 + 冷门地名地理编码兜底，离线时为空数组
      placeList: [],
      // 货物信息（计划书 4.2：车牌号 + 货物信息）
      cargo: { plate: '桂A·D12345', name: '火龙果', weight: 18, temp: '冷鲜 2~6°C', from: '南宁', to: '河内' },
      // 可编辑司机信息
      driverName: '阿明',
      truckType: '冷藏半挂',
      driverInfoSaved: true,
      // === 双模式（剧本第一/四幕）===
      // PUBLIC   = 普通导航模式：免费，谁都能用，只有导航 + 天气/口岸提醒
      // LOGISTICS= 物流任务模式：公司派单后自动进入，车牌/货物/状态条以派单为准
      mode: 'PUBLIC',
      // 当前运输任务（派单下发的原始对象）
      task: null,
      taskAccepting: false,
      // 派单到达时后台预取的候选路线快照：{ key: '起点>终点', at, data }。
      // 司机还在看订单详情页时就把 25 轮算路跑完，点「接单」直接复用，不再等 7~18s
      _taskCandPrefetch: null,
      // 拒单请求进行中
      taskRejecting: false,
      // 订单详情预览弹层（收到派单后查看货物/起终点/AI 分析，并在此接单或拒单）
      showOrderDetail: false,
      // 当前执行的调度方案：'A'=公路绕行 / 'B'=陆水联运 / 'C'=原地等待 / null=非调度（司机自己规划的行程）。
      // 之前 planId 只在 _switchNavForPlan/_autoStartDispatchNav 的参数里用过就丢了，
      // 导致播报和状态卡无从判断"当前是不是水运方案"。改为落库到组件状态，供全流程取用。
      activePlanId: null,
      // 调度方案切换期间的抑制标志：防止 destinationId watcher 触发 _resetCandidates 清空路线
      _switchingPlan: false,
      // 方案 C「原地等待」生效中：车辆停驶，模拟行驶不许推进、也不许被任何降级/重建路径重新拉起。
      // 原来收到 C 只弹一条提示、定时器照跑 —— 屏上写「就近停靠等待」，车标却继续沿路线前进。
      waitHold: false,
      // 全域受阻建议（后端下发）：区域性灾害同时盖住全部出境走廊、平陆运河也禁航。
      // 判据只认后端那一份，本端不用本地进度去重算它成不成立，只用它复算「返回还是就地等」。
      corridorAdvisory: null,
      // 呈现给司机的建议卡（已按本地进度纠正动作）；null=不显示
      corridorNotice: null,
      // 接单模式下遇险的当前处置：不自动绕路，挂一张「正在等待调度中心下发换线指令」卡。
      // { reason, at }；null=不显示。调度下发到达 / 风险解除 / 重新开始导航时清掉
      waitDispatch: null,
      // GPS 定位状态
      gpsStatus: 'searching', // searching | locked | unavailable | simulating | hold
      // 导航状态
      routeLoading: false,
      candidateLoading: false,
      navigating: false,
      // 本次导航出发时刻：ETA 显示/播报都以此为锚，避免轮询刷新后到达时间被反复重算
      navigationStartedAt: 0,
      // 实时速度推算的「剩余小时」：null=尚未算出（退回出发锚点）；导航中随行驶进度动态刷新，
      // 让预计时间从「出发时的一次性全程粗估」变成「按实际快慢不断修正的动态值」
      navRemainHours: null,
      // 全屏大地图预览（选路线阶段）：预览中尚未开始导航，无 GPS 守护
      previewing: false,
      // 预览面板列表收起：收起后浮层更矮，露出更多底图与路线
      previewCollapsed: false,
      // 导航视角：像高德/百度那样开始导航即自动放大并跟随车辆（离线瓦片最高 z13）
      navZoom: 13,
      navFollowing: true, // 是否跟随车辆位置自动平移
      navOffView: false,  // 拖动地图/全览/查看风险点后进入自由视角，显示「回到当前位置」
      // GPS 实时定位
      _gpsWatchId: null,
      // 模拟行驶降级（GPS 不可用时）
      _triIdx: 0,
      _triTimer: null,
      // 模拟行驶按里程推进用的状态：累计里程表 / 当前里程 / 总里程
      _simCum: null,
      _simDistM: 0,
      _simTotalM: 0,
      // 导航中的居中偏移（冻结值，进入导航时重置为 null 以重新量测一次）
      _navCenterOffset: null,
      // 车标/相机的 60fps 视觉插值状态：数据层仍按 SIM_TICK_MS 推进，rAF 里按时间线性插值绘制。
      // _visSeg = { a: 本段起点, b: 本段终点, t0: 起始时刻, dur: 时长 }，见 _paintVehicle
      _visSeg: null,
      _visRaf: null,
      _visPos: null,
      _visEl: null,
      // 上一 tick 到达时刻：实测相邻 tick 真实间隔，给插值段定时长（见 _setVisSegment）
      _visTickAt: null,
      // 车标旋转层 DOM 缓存（60fps 每帧改 transform，不再 querySelector）
      _carRotEl: null,
      // 多路线候选（司机自选）
      candidates: [],
      selectedKey: 'recommended',
      // 平陆运河走廊的「可坐船」推荐位（常态，与公路候选并列）；null=本 O/D 不能坐船
      canalOption: null,
      // 运河禁航实时状态（canal-block/canal-recover SSE 维护）：null=未收到事件，看 canalOption 快照
      canalBlockLive: null,
      // 当前选中的出行方式：'road'=公路候选 / 'canal'=坐船（陆水联运）
      selectedMode: 'road',
      viaText: '',
      // 灾害概率预测（出发前展示百分比 / 途中实时刷新）
      forecast: null,
      forecastLoading: false,
      // 灾害发生后的绕行方案（供司机点选切换）
      rerouteOptions: [],
      rerouteLoading: false,
      announceText: '',
      // 灾害概率「查看详情」弹层
      hazardDetail: null,
      // 语音播报（抢占式）：新播报立即打断正在播的内容
      _voiceQueue: [],
      _voiceTimer: null,
      // 语音列表是否曾经就绪（就绪后的瞬时返空不再傻等超时，见 _waitVoicesReady）
      _voicesSeenOnce: false,
      // === 调度大屏功能对齐（11幕演示）===
      // 任务变更通知（调度下发）：中越双语 + 权益保障 + 确认接收
      taskChange: null,
      taskConfirming: false,
      taskLang: 'zh',
      // 导航中收到调度任务变更：先播报天气灾害 → 切到调度路线预览 → 弹出「确认接收」抽屉
      showDispatchConfirm: false,
      dispatchConfirmCollapsed: false,
      // 已在预览阶段完成路线切换/重算的方案 id：确认时无需再次重算，直接进导航
      _dispatchPreviewPlanId: null,
      // 编排进行中的重入保护（outreach-update 可能短时间多次触发）
      _dispatchHandling: false,
      // 触达状态（本人确认状态同步）
      outreachStatus: { targets: [], total: 0, confirmed: 0, pending: 0 },
      // AI 方案建议（A/B/C 三方案对比，供司机理解调度决策依据）
      agentPlans: null,
      agentPlansLoading: false,
      // 底图：默认在线底图1 天地图影像，按「底图1 → 底图2 → 底图3 → 底图4」自动降级，也可手动循环切换。
      // 在线源在探测窗口内无任一瓦片成功（断网/403）即降到下一个；降到末级 D 盘离线时，
      // 因那套 jpg 是 EPSG:4326、与主图 3857 不同系，需整图重建（见 _enterOfflineD）。
      // 网络恢复后自动切回在线天地图。导航图与首页图各持一份降级状态，互不影响。
      // _useOfflineD=true 表示当前处于 D 盘离线（EPSG:4326）模式。
      _useOfflineD: false,
      // 当前底图短名（手动切换按钮显示）：底图1 / 底图2 / 底图3 / 底图4
      baseName: '底图1',
      _navBase: null,
      _homeBase: null,
      // risk-blink 的 JS 脉冲（preferCanvas 后折线无 SVG 元素可挂 CSS 类名）
      _riskBlinkTimer: null,
      _riskBlinkPhase: false,
      _riskBlinkState: { route: false, risk: false },
      _mapHealthTimers: [],
      _mapHardRecovered: false,
      // 改变 key 会让 Vue 直接替换整个地图 DOM，彻底摆脱旧 Leaflet 内部状态
      _mapRenderKey: 0,
      // 底图加载遮罩：瓦片没到位时显示加载态，避免白屏
      mapVeil: false,
      _lastVoiceText: '',
      _lastVoiceAt: 0,
      // 播报世代号：抢占时递增，旧播报链路的回调据此自行作废，避免旧内容继续播
      _voiceSeq: 0,
      // 最近一次 _cancelVoice() 的 Promise：起播前要 await 它，确保旧语音（尤其原生 TTS）真的停了
      _cancelPromise: null,
      // 独占播报占用标记（导航开始播报专用）：占用期间**非安全类**播报一律不许抢占/取消；
      // 带 force 的安全播报（灾害预警 / 运河禁航 / 全域受阻）仍能打断它——安全信息优先于行程信息
      _voiceLocked: false,
      _voiceLockTimer: null,
      _criticalVoiceSeq: 0,
      // 出发播报的开场核心行（起讫点 + 全程里程 + 耗时 + ETA 都在这一句）及其播报世代：
      // 出发播报被灾害这类 force 播报打断时，挂到灾害播报队尾补读一次（见 speakQueue）。
      // chunks 是这句按标点拆好的短句，随播报推进逐条出列（见 _doSpeak）：补读只补
      // **还没完整念到的那几段**，否则整句从头重来，司机会听两遍「导航开始，从A到B」
      _departureIntro: '',
      _departureIntroChunks: [],
      _departureIntroSeq: 0,
      // 最近一次确认真正开始发声的播报世代；用于出发播报"未出声自动重试"
      _voiceLastStartAt: 0,
      _voiceLastStartSeq: 0,
      _voiceActiveSeq: 0,
      // 语音链路诊断：仅 URL 带 ?voicedebug=1 时在导航页显示（不影响正常演示）
      voiceDebugOn: false,
      voiceDebug: '',
      // 绕行播报去重：同一起灾害事件只播报一遍
      // （SSE 推送、轮询兜底、重取路线会并发触发 checkPush，文本细节不同会绕过普通去重）
      _hazardVoiceKey: '',
      // 灾害即时提示的去重键（当前灾害边集合），避免同一批灾害重复弹窗/播报
      _hazardNotifyKey: '',
      _hazardVoiceAt: 0,
      // 绕行期间记住「风险真正落在哪条走廊 + 什么灾害」，供风险解除切回原路线时播报
      // （切回时 this.risks 已清空，reason 拿不到，只能提前快照）
      _lastHazardBrief: null,
      // 全屏导航菜单
      showNavMenu: false,
      // 首页（高德风格）悬浮搜索框是否展开
      homeSearchOpen: false,
      // 首页宫格/导航卡是否被下滑收起（只留搜索框，见 hm-collapse-grip / hm-expand-pill）
      homeCollapsed: false,
      // 桌面端（≥900px）标识：显示全屏入口并启用整窗布局；isFullscreen 跟踪 Fullscreen API 状态
      isDesktop: false,
      isFullscreen: false,
      // 桌面布局模式：true=PC 整窗大屏 / false=移动版 420px 手机窄列（仅 ≥900px 有意义，localStorage 持久化）
      pcLayout: (() => { try { return localStorage.getItem('driver_pc_layout') !== 'mini' } catch (e) { return true } })(),
      // 首页概览地图独立实例（与导航地图 this.map 互不干扰）
      _homeMap: null,
      // 退出导航二次确认弹层
      showExitConfirm: false,
      // 左边缘侧滑返回的跟手进度（0~1）：驱动边缘指示条的位移/透明度，松手达阈值即返回上一级
      swipeEdgeProgress: 0,
      map: null,
      baseLine: null,
      routeLine: null,
      riskLine: null,
      portMarkers: []
    }
  },
  computed: {
    // 起终点下拉候选：内置节点名 + 后端地名库（含平陆运河各节点/口岸，约 50 条），按名字去重
    placeSuggestions() {
      const seen = new Set()
      const out = []
      for (const [id, name] of Object.entries(NODE_NAMES)) {
        if (seen.has(name)) continue
        seen.add(name)
        out.push({ name, tag: id })
      }
      for (const p of this.placeList) {
        if (!p || !p.name || seen.has(p.name)) continue
        seen.add(p.name)
        out.push({ name: p.name, tag: p.tag || '' })
      }
      return out
    },
    // 当前路线上的风险段（后端 riskSegments 下发的是全量风险，需按路径边过滤，
    // 保证「风险路段」列表与地图上标红的路段完全一致）
    pathRisks() { return this._risksOnRoute(this.route) },
    // 预计到达时刻：导航中以出发时刻 + 当前全程耗时为锚，避免 15s 轮询刷新后 ETA 漂移；
    // 预览/未开始导航时优先采用后端按天气/通关算好的 estimatedArrival。
    etaDate() {
      if (!this.route.estimatedHours) return null
      if (this.navigating && this.navigationStartedAt) {
        // 实时 ETA：以「当前时刻 + 按实际速度推算的剩余时间」为准，开得比计划快则提前、
        // 受阻/绕行则顺延；剩余时间尚未由行驶进度算出时，退回「出发时刻 + 全程计划耗时」的粗估锚点。
        if (this.navRemainHours != null) {
          return new Date(Date.now() + this.navRemainHours * 3600 * 1000)
        }
        return new Date(this.navigationStartedAt + this.route.estimatedHours * 3600 * 1000)
      }
      const backendEta = Date.parse(this.route.estimatedArrival || '')
      if (!Number.isNaN(backendEta)) return new Date(backendEta)
      return new Date(Date.now() + this.route.estimatedHours * 3600 * 1000)
    },
    // 导航中大数字改为「实时速度推算的剩余耗时」：起步≈全程计划耗时，之后随实际速度提前/顺延。
    // 粒度到分钟：0.1h 粒度要 3 分多钟才跳一格，观感就是「不会减少」；分钟位每分钟都在减，
    // 不足 1 小时时大数字直接切换成分钟数。
    navHoursDisplay() {
      if (this.navigating && this.navRemainHours != null) {
        const totalMin = Math.max(0, Math.round(this.navRemainHours * 60))
        return totalMin >= 60 ? String(Math.floor(totalMin / 60)) : String(totalMin)
      }
      return this.route.estimatedHours ? this.route.estimatedHours.toFixed(1) : '--'
    },
    /** navHoursDisplay 的数值版（供播报与屏幕同源）：无可用数字时返回 null */
    navHoursDisplayNum() {
      const h = this.route && this.route.estimatedHours
      if (!h) return null
      if (this.navigating && this.navRemainHours != null) return Math.max(0, this.navRemainHours)
      return Number(h)
    },
    navEtaUnit() {
      if (this.navigating && this.navRemainHours != null) {
        const totalMin = Math.max(0, Math.round(this.navRemainHours * 60))
        return totalMin >= 60 ? `小时 ${totalMin % 60}分后到达` : '分钟后到达'
      }
      return '小时'
    },
    etaArrival() {
      if (!this.etaDate) return '--'
      return this.etaDate.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Shanghai' })
    },
    etaArrivalVN() {
      if (!this.etaDate) return '--'
      return this.etaDate.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Ho_Chi_Minh' })
    },
    // TTS 播报用口语化日期格式：9月8日15点30分（固定按北京时间）
    etaArrivalZh() {
      if (!this.etaDate) return '--'
      const parts = new Intl.DateTimeFormat('en-US', {
        timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric',
        hour: '2-digit', minute: '2-digit', hour12: false
      }).formatToParts(this.etaDate)
      const get = t => (parts.find(p => p.type === t) || {}).value || '0'
      return `${parseInt(get('month'))}月${parseInt(get('day'))}日${parseInt(get('hour'))}点${get('minute') === '00' ? '' : parseInt(get('minute')) + '分'}`
    },
    // TTS 播报用越南语口语化日期格式：ngày 8 tháng 9, 15 giờ 30 phút
    etaArrivalViTTS() {
      if (!this.etaDate) return '--'
      const parts = new Intl.DateTimeFormat('en-US', {
        timeZone: 'Asia/Ho_Chi_Minh', month: 'numeric', day: 'numeric',
        hour: '2-digit', minute: '2-digit', hour12: false
      }).formatToParts(this.etaDate)
      const get = t => (parts.find(p => p.type === t) || {}).value || '0'
      const h = parseInt(get('hour')), m = parseInt(get('minute'))
      return `ngày ${get('day')} tháng ${get('month')}, ${h} giờ ${m === 0 ? '' : m + ' phút'}`
    },
    chinaTime() {
      return new Date().toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone: 'Asia/Shanghai' })
    },
    vietnamTime() {
      return new Date().toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone: 'Asia/Ho_Chi_Minh' })
    },
    originName() { return NODE_NAMES[this.resolvedOriginId] || this.originId },
    /** 首页是否可见（非预览/非导航）：控制首页概览地图的建/销 */
    homeVisible() { return !this.navigating && !this.previewing },
    /** 首页宫格快捷入口（物流功能）
     *  演示用克制配色：白磨砂圆底 + 细线 SVG 图标（24 视窗 stroke 风），
     *  图标线条统一走浅蓝/浅绿清新系，文字用亮蓝（见 .hm-grid-label） */
    homeGrid() {
      return [
        { key: 'task', label: '物流任务', color: '#5ab4f0',
          svg: '<rect x="8.2" y="3" width="7.6" height="3.6" rx="1.2"/><path d="M15.8 4.8H17a2 2 0 0 1 2 2v11.4a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6.8a2 2 0 0 1 2-2h1.2"/><path d="M8.8 11.2h6.4M8.8 14.6h4.6"/>' },
        { key: 'alert', label: '风险预警', color: '#57d0a5',
          svg: '<path d="M12 4.6 20.6 19.4H3.4Z"/><path d="M12 10v4.2"/><path d="M12 17.1h.01"/>' },
        { key: 'plans', label: 'AI方案', color: '#7cc4f5',
          svg: '<path d="M11 4.2 12.7 9 17.5 10.7 12.7 12.4 11 17.2 9.3 12.4 4.5 10.7 9.3 9Z"/><path d="M17.6 14.4 18.4 16.6 20.6 17.4 18.4 18.2 17.6 20.4 16.8 18.2 14.6 17.4 16.8 16.6Z"/>' },
        { key: 'kb', label: '知识库', color: '#7fdca0',
          svg: '<path d="M12 6.6C10.6 5.2 8.7 4.7 5.2 4.7v12.6c3.5 0 5.4.5 6.8 1.9 1.4-1.4 3.3-1.9 6.8-1.9V4.7c-3.5 0-5.4.5-6.8 1.9Z"/><path d="M12 6.6v12.6"/>' },
        { key: 'me', label: '我的车辆', color: '#66bdf2',
          svg: '<path d="M3 6.8h10.4v8.4H3z"/><path d="M13.4 9.6H17l3 3.4v2.2h-6.6"/><circle cx="7" cy="17.4" r="1.6"/><circle cx="16.4" cy="17.4" r="1.6"/>' }
      ]
    },
    /** 首页底部胶囊 Tab */
    homeTabs() {
      return [
        { key: 'route', icon: '🧭', label: '首页' },
        { key: 'task', icon: '📋', label: '任务' },
        { key: 'alert', icon: '⚠️', label: '预警' },
        { key: 'kb', icon: '📚', label: '知识库' },
        { key: 'me', icon: '👤', label: '我的' }
      ]
    },
    /** 底部抽屉标题 */
    tabTitle() {
      const t = this.homeTabs.find(x => x.key === this.tab)
      return t ? t.label : ''
    },
    /** 是否展示「去X 导航」卡片：已有候选或已规划路线 */
    homeGoCard() {
      return !!(this.candidates.length || (this.route && this.route.pathCoords && this.route.pathCoords.length))
    },
    goKm() {
      const c = this.selectedCandidate
      const km = c ? c.distanceKm : this.route.totalDistanceKm
      return km != null ? Math.round(km * 10) / 10 : '--'
    },
    goMin() {
      const c = this.selectedCandidate
      const h = c ? c.hours : this.route.estimatedHours
      return h != null ? Math.round(h * 60) : '--'
    },
    destinationName() { return NODE_NAMES[this.resolvedDestinationId] || this.destinationId },
    /** 把用户输入（ID 或中文名）解析为节点 ID */
    resolvedOriginId() { return this._resolveNodeId(this.originId) },
    // 司机端身份：driver.html?driver=vn 打开则扮演越方接力司机（第⑨幕），否则中方司机
    driverRole() {
      try {
        return new URLSearchParams(window.location.search).get('driver') === 'vn'
          ? 'DRIVER_VN' : 'DRIVER'
      } catch (e) { return 'DRIVER' }
    },
    /** 当前是否在「陆水联运（平陆运河）」方案上——播报/状态卡据此提到平陆运河 */
    isCanalPlan() { return this.activePlanId === 'B' },
    /** 运河当前是否禁航：SSE 实时状态优先，其次用后端随 canalOption 下发的静态快照 */
    canalBlockedNow() {
      if (this.canalBlockLive) return this.canalBlockLive.blocked
      return !!(this.canalOption && this.canalOption.canalBlocked)
    },
    canalBlockReasonNow() {
      if (this.canalBlockLive && this.canalBlockLive.blocked && this.canalBlockLive.reason) return this.canalBlockLive.reason
      return (this.canalOption && this.canalOption.canalBlockedReason) || '平陆运河触发通航安全红线'
    },
    /**
     * 接单闸门：未接单（无任务/待接/已拒）时，调度大屏的任何控制指令
     * （确认切换方案下发、任务变更、自动导航、改起终点）一律不生效——
     * 司机先接单，接单才等于把"路线控制权"交给调度；派单本身（task-assigned）
     * 是待接订单的投递入口，不在此限制之列。
     */
    isTaskAccepted() {
      return this.mode === 'LOGISTICS' && !!this.task && this.task.status === 'ACCEPTED'
    },
    /** 运输方式简述（中文），供出发播报用。措辞避免与上一句"到南宁港六景作业区"重复 */
    transportBriefZh() {
      return this.isCanalPlan
        ? '本次为陆水联运，车辆在港区交接后，您随船经平陆运河至钦州港，再海运至越南海防港'
        : ''
    },
    /** 运输方式简述（越南语）。kênh đào Bình Lục = 平陆运河，与后端触达文案用词一致 */
    transportBriefVi() {
      return this.isCanalPlan
        ? 'Chuyến này là vận tải liên hợp đường bộ - đường thủy, sau khi bàn giao xe tại cảng, '
          + 'anh đi cùng tàu qua kênh đào Bình Lục đến cảng Khâm Châu, rồi đi tiếp bằng đường biển đến cảng Hải Phòng'
        : ''
    },
    // 任务通知正文：越方司机以越南语为主文案（message=越语/messageZh=中文），
    // 中方司机反之（message=中文/messageVi=越语）
    taskMessageText() {
      const t = this.taskChange
      if (!t) return ''
      if (this.driverRole === 'DRIVER_VN') {
        return this.taskLang === 'zh' ? (t.messageZh || t.message) : t.message
      }
      return this.taskLang === 'vi' ? (t.messageVi || t.message) : t.message
    },
    resolvedDestinationId() { return this._resolveNodeId(this.destinationId) },
    routeTitle() {
      const cross = !CN_NODE_IDS.has(this.resolvedDestinationId)
      return `${this.originName} → ${this.destinationName}${cross ? '（跨境）' : ''}`
    },
    statusClass() {
      // 停驶等待优先于路况着色：此刻画面里车辆本来就不该在动
      if (this.waitHold) return 'hold'
      if (this.route.rerouted) return 'rerouted'
      // 用 pathRisks（已按本路线过滤）：直接用 route.riskSegments 是全网风险，
      // 别处熔断也会让状态条显示"风险预警"，而本程其实全线通畅。
      if (this.pathRisks.length) return 'warn'
      return 'ok'
    },
    statusText() {
      if (this.waitHold) return '原地等待 · 车辆停驶'
      if (this.route.rerouted) return '已熔断 · 自动绕行'
      if (this.pathRisks.length) return '风险预警 · 谨慎驾驶'
      return '全线通畅'
    },
    // 预览阶段的状态徽标：按所选候选路线的风险段数展示
    previewStatusClass() {
      const c = this.candidates.find(x => x.key === this.selectedKey)
      return c && c.riskCount > 0 ? 'warn' : 'ok'
    },
    previewStatusText() {
      const c = this.candidates.find(x => x.key === this.selectedKey)
      if (!c) return '待选路线'
      return c.riskCount > 0 ? `${c.riskCount} 处风险` : '全线通畅'
    },
    // 当前选中的候选路线对象（预览阶段地图标红 / 状态徽标共用）
    selectedCandidate() {
      return this.candidates.find(x => x.key === this.selectedKey) || null
    },
    // AI 预测概率最低的候选路线（用于「AI 最安全」徽标；无预测数据时返回空）
    safestKey() {
      const withProb = this.candidates.filter(c => c.hazardProbability >= 0)
      if (!withProb.length) return ''
      let best = withProb[0]
      withProb.forEach(c => { if (c.hazardProbability < best.hazardProbability) best = c })
      return best.key
    },
    // 绕行方案按实际灾害概率升序排列（未知概率排最后）——最安全的置顶，
    // 不再盲信后端的「推荐路线」角色标签（灾害为区域性天气时，被标为推荐的走廊未必真安全）
    rerouteRanked() {
      const rank = p => (p == null || p < 0) ? Infinity : p
      return [...this.rerouteOptions].sort((a, b) => rank(a.hazardProbability) - rank(b.hazardProbability))
    },
    // 风险最低的那条（面板里标绿「风险最低」）
    safestRerouteKey() {
      return this.rerouteRanked.length ? this.rerouteRanked[0].key : ''
    },
    // 所有备选都是高风险：说明按当前天气已无安全走廊，需给出停车建议
    allRerouteRisky() {
      return this.rerouteOptions.length > 0 && this.rerouteOptions.every(o => this.isHighRisk(o))
    },
    // 主按钮三态：搜索候选并进入预览 → 查看预览 → 导航中
    btnLabel() {
      if (this.routeLoading) return '路线计算中…'
      if (this.candidateLoading) return '搜索路线中…'
      if (this.navigating) return '导航中 · Agent 实时守护'
      return this.candidates.length ? '查看路线预览' : '搜索路线并进入地图预览'
    },
    gpsClass() {
      return {
        searching: 'gps-searching', locked: 'gps-locked',
        unavailable: 'gps-unavailable', simulating: 'gps-simulating', hold: 'gps-hold'
      }[this.gpsStatus] || 'gps-searching'
    },
    gpsLabel() {
      return {
        searching: '搜索中…', locked: '已定位 ✓',
        unavailable: '不可用 ✗', simulating: '模拟行驶 △', hold: '停驶等待 ▮'
      }[this.gpsStatus] || '搜索中…'
    },
    quickRoutes() {
      return [
        { key: 'nn-hn', label: '南宁 → 河内（友谊关）', from: 'NN', to: 'HN' },
        { key: 'nn-hp', label: '南宁 → 海防（芒街）', from: 'NN', to: 'HP' },
        { key: 'px-hn', label: '凭祥 → 河内（同登）', from: 'PX', to: 'HN' },
        { key: 'km-hn', label: '昆明 → 河内（河口）', from: 'HK', to: 'HN' }
      ]
    }
  },
  watch: {
    // 路线变化（Agent 推送或轮询刷新）时同步重绘地图
    route: {
      handler() { this.drawRoute() },
      deep: false
    },
    // 风险集合变化也要重绘：drawRoute 是按风险集给路线分段着色的，
    // 若只监听 route，风险单独变化（例如熔断注入）时导航线不会立刻变红。
    risks: {
      handler() { this.drawRoute() },
      deep: false
    },
    // 开始/结束导航：重置实时 ETA——剩余时间改由模拟行驶 tick 依实际速度重新推算，
    // 开始时先按全程计划耗时给个粗估，避免首帧空白
    navigating(v) {
      this._etaCalcAt = 0
      this.navRemainHours = null
      if (v) this._updateLiveEta(true)
    },
    originId(v) {
      this.cargo.from = NODE_NAMES[v] || v
      this._resetCandidates()
    },
    destinationId(v) {
      this.cargo.to = NODE_NAMES[v] || v
      this._resetCandidates()
    },
    // 首页概览地图生命周期：回到首页建图，离开首页（预览/导航）销毁，
    // 避免与导航地图 this.map 抢瓦片与内存。
    homeVisible(v) {
      if (v) this.$nextTick(() => this._buildHomeMap())
      else this._destroyHomeMap()
    },
    // 绕行方案详情弹层打开时自动语音播报
    showReroute(v) {
      if (v) {
        this.$nextTick(() => {
          const reason = this.extractHazardKeyword(this.latestHazardReason())
          const fromPort = this.inferPortFromNodeIds(this.route.baselinePathNodeIds) || '原口岸'
          const toPort = this.inferPortFromNodeIds(this.route.pathNodeIds) || '绕行口岸'
          const status = this._buildStatusZh()
          this.speakQueue([
            `请注意，${fromPort}沿线因${reason}受阻，已自动切换${toPort}。${status}`,
            `Cảnh báo: Tuyến đường qua ${fromPort} bị ảnh hưởng bởi ${reason}。Hệ thống đã tự động chuyển sang ${toPort}。Thời gian trễ dự kiến ${this.route.extraHours || 0} giờ。${status}`
          ])
        })
      }
    }
  },
  mounted() {
    // 语音诊断开关：?voicedebug=1 时在导航页显示语音链路日志，便于定位"没声音"卡在哪一步。
    // 默认关闭，不影响正式演示。
    try {
      this.voiceDebugOn = new URLSearchParams(window.location.search).get('voicedebug') === '1'
    } catch (e) { /* 忽略 */ }
    if (this.voiceDebugOn) this._vdbg('诊断开启 · native=' + this._isNativeShell())
    // 底图按自动降级链挂载（底图1 天地图影像 → 底图2 矢量 → 底图3 OSM → 底图4 D 盘离线），
    // 直接建图。
    this.$nextTick(() => {
      this.initMap()
      // 首页（高德风格）概览地图：仅在首页可见时建图
      if (this.homeVisible) this._buildHomeMap()
    })
    this._initRipple()
    this._initSwipeBack()
    this.connectAgent()
    this.loadCustoms()
    // 拉后端地名库（约 50 条，含平陆运河各节点/口岸）：
    // 带 nodeId 的条目直接补进 NAME_TO_ID，让司机输中文名也能走既有的按节点算路流程；
    // 未带 nodeId 的冷门地名由 _normalizeOD 走地理编码+吸附兜底。失败不影响既有内置节点。
    fetchPlaceList().then(list => {
      this.placeList = list || []
      for (const p of this.placeList) {
        if (p && p.name && p.nodeId) {
          NAME_TO_ID[p.name] = p.nodeId
          NAME_TO_ID[p.name.toLowerCase()] = p.nodeId
        }
      }
    }).catch(() => {})
    // 越方司机默认越南语播报/展示
    if (this.driverRole === 'DRIVER_VN') this.taskLang = 'vi'
    // 刷新/断线重连后仍能回到物流任务模式（"拔网线照样跑"的一部分）
    this._loadCurrentTask()
    // 实时时钟
    this._clockTimer = setInterval(() => { this.$forceUpdate() }, 1000)
    // 语音引擎保活：Chrome 对长句（>15s）会静默暂停且不触发 onend，
    // 每 10s resume 一次保活，否则出发播报这类长文本会念到一半停住、队列卡死
    this._voiceKeepAlive = setInterval(() => {
      if ('speechSynthesis' in window) {
        const s = window.speechSynthesis
        if (s.speaking || s.paused) s.resume()
      }
    }, 10000)
    // 轮询兜底：15s 一次（SSE 为主通道）
    this.timer = setInterval(this.refreshAll, 15000)
    // 换线跳变审计 watchdog：用户环境（5173 dev）仍能复现起点跳，但无头端到端复现零跳——
    // 说明存在未知覆盖入口。这里不靠推理，车标与线首每 400ms 采样一次，
    // 单步位移 >0.01°（≈1.1km）就 console.warn 现场（含状态标志），配合 _auditSwap 的来源日志定位真凶。
    this._auditTimer = setInterval(() => {
      try {
        const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false })
        const st = { nav: this.navigating, prev: this.previewing, dp: this._dispatchPreviewPlanId, simKm: Math.round((this._simDistM || 0) / 100) / 10 }
        if (this._meMarker && this.map) {
          const p = this._meMarker.getLatLng()
          const lc = this._auditLastCar
          if (lc && Math.hypot(p.lat - lc[0], p.lng - lc[1]) > 0.01) {
            console.warn(`[AUDIT ${ts}] CAR-JUMP Δ≈${(Math.hypot(p.lat - lc[0], p.lng - lc[1]) * 111).toFixed(1)}km`, { from: lc.map(v => Math.round(v * 1e4) / 1e4), to: [Math.round(p.lat * 1e4) / 1e4, Math.round(p.lng * 1e4) / 1e4], ...st })
          }
          this._auditLastCar = [p.lat, p.lng]
        }
        const c0 = (this.route && this.route.pathCoords && this.route.pathCoords[0]) || null
        const l0 = this._auditLastC0
        if (c0 && c0.length >= 2 && l0 && Math.hypot(c0[0] - l0[0], c0[1] - l0[1]) > 0.01) {
          console.warn(`[AUDIT ${ts}] C0-JUMP Δ≈${(Math.hypot(c0[0] - l0[0], c0[1] - l0[1]) * 111).toFixed(1)}km`, { from: l0.map(v => Math.round(v * 1e4) / 1e4), to: [Math.round(c0[0] * 1e4) / 1e4, Math.round(c0[1] * 1e4) / 1e4], km: this.route.totalDistanceKm, ...st })
        }
        this._auditLastC0 = (c0 && c0.length >= 2) ? [c0[0], c0[1]] : null
      } catch (e) { /* 审计绝不能反伤主流程 */ }
    }, 400)
    // AI 预警不再自动定时刷新（由用户点击触发，避免文案消失和重复播报）
    // 首页加载即请求定位权限：Android 原生弹窗在 Capacitor 桥下触发，
    // 司机一进 App 就授权，不必等到点「开始导航」。已授权/已永久拒绝时系统不再弹。
    this._requestLocationOnLaunch()
    // 桌面端识别 + 全屏状态监听：PC 打开司机端时显示全屏入口并启用整窗布局
    this._desktopMq = window.matchMedia ? window.matchMedia('(min-width: 900px)') : null
    if (this._desktopMq) {
      this.isDesktop = this._desktopMq.matches
      this._onDesktopMq = (e) => { this.isDesktop = e.matches }
      if (this._desktopMq.addEventListener) this._desktopMq.addEventListener('change', this._onDesktopMq)
      else if (this._desktopMq.addListener) this._desktopMq.addListener(this._onDesktopMq)
    }
    this._onFullscreenChange = () => { this.isFullscreen = !!document.fullscreenElement }
    document.addEventListener('fullscreenchange', this._onFullscreenChange)
    // 整机中越切换：恢复上次语言并启动 DOM 翻译层（导航/抽屉里的动态文本由
    // 翻译器内部的 MutationObserver 增量重译，这里只管初始态）
    this._uiTranslator = createUiTranslator(() => this.$el)
    if (this.uiLang === 'vi') this._uiTranslator.setLang('vi')
  },
  beforeUnmount() {
    clearInterval(this.timer)
    if (this._auditTimer) clearInterval(this._auditTimer)
    if (this._rippleHandler) document.removeEventListener('pointerdown', this._rippleHandler)
    if (this._swipeStartHandler) document.removeEventListener('pointerdown', this._swipeStartHandler)
    if (this._swipeMoveHandler) document.removeEventListener('pointermove', this._swipeMoveHandler)
    if (this._swipeEndHandler) document.removeEventListener('pointerup', this._swipeEndHandler)
    if (this._swipeCancelHandler) document.removeEventListener('pointercancel', this._swipeCancelHandler)
    if (this.riskTimer) clearInterval(this.riskTimer)
    if (this._clockTimer) clearInterval(this._clockTimer)
    if (this._voiceKeepAlive) clearInterval(this._voiceKeepAlive)
    // 取消正在进行的播报（web speech + 原生 TTS）
    this._cancelVoice(true)
    // 清掉高优先级占用定时器，避免卸载后回调还在跑
    if (this._voiceLockTimer) { clearTimeout(this._voiceLockTimer); this._voiceLockTimer = null }
    this._voiceLocked = false
    this._criticalVoiceSeq = 0
    this._departureIntro = ''
    this._departureIntroChunks = []
    this._departureIntroSeq = 0
    this._stopRealTimeGPS()
    if (this._agentEs) this._agentEs.close()
    this._destroyMap()
    this._destroyHomeMap()
    if (this._desktopMq && this._onDesktopMq) {
      if (this._desktopMq.removeEventListener) this._desktopMq.removeEventListener('change', this._onDesktopMq)
      else if (this._desktopMq.removeListener) this._desktopMq.removeListener(this._onDesktopMq)
    }
    if (this._onFullscreenChange) document.removeEventListener('fullscreenchange', this._onFullscreenChange)
    if (this._uiTranslator) { this._uiTranslator.destroy(); this._uiTranslator = null }
    if (window.speechSynthesis) window.speechSynthesis.cancel()
  },
  methods: {
    /** 整机界面语言切换：中文 ↔ Tiếng Việt（字典见 i18n.js，未覆盖文案回落中文） */
    toggleUiLang() {
      this.uiLang = this.uiLang === 'zh' ? 'vi' : 'zh'
      try { localStorage.setItem('driver_ui_lang', this.uiLang) } catch (e) { /* 忽略 */ }
      if (this._uiTranslator) this._uiTranslator.setLang(this.uiLang)
    },
    /**
     * 取某条路线上**真正命中**的风险段。
     *
     * 后端下发的 riskSegments 是**全网风险列表**（RouteService 里直接塞的 currentRisks()），
     * 必须按该路线的 pathEdgeIds 过滤。不过滤就会把别处的风险当成"本路线前方风险"：
     * 南宁→南宁港六景作业区那一程就曾被误报「暴雨」，而那条风险在友谊关，根本不在这条路上。
     *
     * 注意：这是方法不是计算属性 —— 它要接收任意 route 对象（checkPush/_buildStatus* 传入的
     * 未必是 this.route）。曾误放进 computed 块，被当函数调用时直接抛 is not a function。
     *
     * this.route 还要额外并入 this.risks：接单模式不自动换线时后端新风险只落到 risks，
     * route.riskSegments 是滞后快照，不并就会出现在「红线 + 灾害弹窗」同屏时
     * 状态条仍写“全线通畅”的自相矛盾。
     */
    _risksOnRoute(r) {
      const segs = (r && r.riskSegments) || []
      const pool = (r === this.route && this.risks && this.risks.length) ? [].concat(segs, this.risks) : segs
      if (!pool.length) return []
      const ids = new Set(((r && r.pathEdgeIds) || []).map(String))
      const hit = ids.size ? pool.filter(x => x && ids.has(String(x.edgeId))) : pool
      // 两个来源可能给同一条风险边（合并后不能重复计数，风险列表会出现双份条目）
      const seen = new Set()
      return hit.filter(x => {
        const k = String(x.edgeId)
        if (seen.has(k)) return false
        seen.add(k)
        return true
      })
    },
    // ---------- 首页概览地图（高德风格首页背景） ----------
    /**
     * 建首页概览地图：独立于导航地图 this.map 的轻量实例，只铺底图、不画路线，
     * 供首页全屏背景交互（拖动/缩放）。离开首页时由 homeVisible watcher 销毁。
     */
    _buildHomeMap() {
      const el = this.$refs.homeMapEl
      if (!el) return
      if (this._homeMap) { try { this._homeMap.invalidateSize() } catch (e) {} return }
      const cfg = this._mapConfig()
      try {
        this._resetMapContainer(el, true)
        const map = L.map(el, {
          crs: cfg.crs,
          zoomControl: false,
          attributionControl: false,
          zoomSnap: 0,
          // 首页概览只铺底图；导航图里因长折线 Canvas 批量绘制才开 preferCanvas，
          // 这里同样打开保持一致（首页不画路线，无 className 特效依赖）
          preferCanvas: true,
          minZoom: cfg.minZoom,
          maxZoom: cfg.maxZoom,
          zoomAnimation: false,
          // 边界锁放开：与调度大屏一致自由拖拽；仅 4326 离线源保留走廊锁（瓦片只覆盖那里）
          ...(cfg.bounds ? { maxBounds: cfg.bounds, maxBoundsViscosity: 1.0 } : {}),
          bounceAtZoomLimits: false
        })
        this._homeMap = map
        map.setView(cfg.center, cfg.bounds ? 8 : 7)
        // 容器是 inset:0 + 100vh，软键盘弹出（搜起终点）、转屏都会改它的高度。
        // 不重新量测的话 Leaflet 会按旧尺寸铺瓦，底图只盖住一部分视口。
        // 导航地图（_buildMap）本来就有这个监听，首页此前漏了。
        if (typeof ResizeObserver !== 'undefined') {
          this._homeMapRO = new ResizeObserver(() => { if (this._homeMap) this._homeMap.invalidateSize() })
          this._homeMapRO.observe(el)
        }
        // 首页底图与导航地图同一套降级链：底图1 影像 → 底图2 矢量 → 底图3 OSM → 底图4 D 盘离线。
        this._homeBase = { idx: this._baseStartIdx(), map }
        this._mountBase(map, this._homeBase)
      } catch (e) {
        console.error('首页概览地图建图失败', e)
      }
    },
    /** 销毁首页概览地图并清理容器残留 Leaflet 状态 */
    _destroyHomeMap() {
      if (this._homeBase) { if (this._homeBase.timer) clearTimeout(this._homeBase.timer); this._homeBase = null }
      if (this._homeMapRO) { this._homeMapRO.disconnect(); this._homeMapRO = null }
      if (this._homeMap) {
        try { this._homeMap.remove() } catch (e) { /* 忽略 */ }
        this._homeMap = null
      }
      const el = this.$refs.homeMapEl
      this._resetMapContainer(el, !!(el && el._leaflet_id))
    },
    /** 桌面端全屏切换：Fullscreen API 隐藏浏览器边框；地图尺寸由 ResizeObserver 自动重测 */
    toggleFullscreen() {
      try {
        if (!document.fullscreenElement) {
          const p = document.documentElement.requestFullscreen && document.documentElement.requestFullscreen()
          if (p && p.catch) p.catch(() => {})
        } else if (document.exitFullscreen) {
          const p = document.exitFullscreen()
          if (p && p.catch) p.catch(() => {})
        }
      } catch (e) { /* 浏览器拒绝全屏时静默 */ }
    },
    /** 桌面端布局切换：PC 整窗大屏 ↔ 移动版 420px 手机窄列；地图尺寸由 ResizeObserver 自动重测 */
    toggleLayoutMode() {
      this.pcLayout = !this.pcLayout
      try { localStorage.setItem('driver_pc_layout', this.pcLayout ? 'pc' : 'mini') } catch (e) { /* 隐私模式写入失败时静默 */ }
    },
    homeZoom(d) {
      if (this._homeMap) this._homeMap.setZoom(this._homeMap.getZoom() + d)
    },
    homeLocate() {
      if (this._homeMap) this._homeMap.setView(this._mapConfig().center, 9)
    },
    /** 首页宫格点击：跳转到对应功能抽屉 / 展开搜索 */
    onHomeGrid(key) {
      // 刚完成收缩滑动：touchend 后补发的 click 不算点击宫格
      if (this._gsSwiped) return
      if (key === 'plans') {
        this.tab = 'task'
        this.loadAgentPlans()
        return
      }
      this.tab = key
    },
    // ---------- 首页宫格收缩（上下滑动 / 把手点按） ----------
    // 用 Pointer Events 而非 Touch Events：触摸+鼠标统一，且配合
    // .hm-grid/.hm-collapse-grip/.hm-expand-pill 的 touch-action:none，
    // 阻止 WebView 把竖向滑动抢去当页面滚动（不抢就不会发 touchcancel 掩掉手势）
    onGridSwipeStart(e) {
      this._gsx = e.clientX
      this._gsy = e.clientY
      this._gsSwiped = false
    },
    onGridSwipeMove(e) {
      if (this._gsy == null || this._gsSwiped) return
      const dy = e.clientY - this._gsy
      const dx = e.clientX - this._gsx
      // 竖向位移超 30px 且明显大于横向 → 判定为收缩手势：下拉收起、上推展开
      if (Math.abs(dy) > 30 && Math.abs(dy) > Math.abs(dx) * 1.3) {
        this._gsSwiped = true
        this.homeCollapsed = dy > 0
      }
    },
    onGridSwipeEnd() {
      this._gsy = null
      if (!this._gsSwiped) return
      // click 在 pointerup 之后补发，延一拍再清标记，避免滑动被误判成按钮点击
      setTimeout(() => { this._gsSwiped = false }, 120)
    },
    tapCollapseGrip() {
      if (this._gsSwiped) return
      this.homeCollapsed = true
    },
    tapExpandPill() {
      if (this._gsSwiped) return
      this.homeCollapsed = false
    },
    // ---------- 地图（计划书 4.4） ----------
    /**
     * 底图：底图1 天地图影像 → 底图2 天地图矢量 → 底图3 OSM → 底图4 D 盘离线，自动降级，可手动循环切换。
     *
     * 这里同时防止「旧 Leaflet 实例绑定到已被 Vue v-if 移除的容器」：
     * 退出导航/预览后 this.map 仍非空，调度确认再次进入导航时若不校验容器，
     * 会把路线和视角全部写到旧 DOM 上，新页面看起来就是白屏。
     */
    initMap() {
      const el = this.$refs.mapEl
      if (!el) { this._destroyMap(); return }
      if (this.map && this.map.getContainer && this.map.getContainer() !== el) {
        this._destroyMap()
      }
      // 同一容器已在用时不要重建：预览切导航会因此避免闪屏和瓦片重复请求
      if (this.map) {
        this.map.invalidateSize()
        try { this.drawRoute() } catch (e) { console.error('复用地图重绘失败', e) }
        return
      }
      try {
        this._buildMap()
      } catch (e) {
        console.error('地图建图失败，安排自动重建', e)
        this._destroyMap()
        this._scheduleMapHealthChecks()
      }
    },
    _buildMap() {
      const el = this.$refs.mapEl
      if (!el) { this._destroyMap(); return }
      // 旧 Leaflet 可能因 HMR/组件重载残留在同一容器上；不清掉 _leaflet_id 时 L.map 会直接抛错，
      // 页面只剩导航 UI，表现就是调度确认后整屏白屏。
      if (this._riskBlinkTimer) { clearInterval(this._riskBlinkTimer); this._riskBlinkTimer = null }
      this._clearMapHealthChecks()
      // 重建地图前先断开旧的尺寸监听，避免还在监听悬空的旧容器
      if (this._mapRO) { this._mapRO.disconnect(); this._mapRO = null }
      // 同理取消待执行的缩放重绘帧（旧 map 的 zoomend 可能刚排了一帧）
      if (this._zoomRedrawRaf) { cancelAnimationFrame(this._zoomRedrawRaf); this._zoomRedrawRaf = null }
      if (this.map) {
        this.map.remove()
        this.map = null
      }
      // map.remove 后仍可能残留内部 ID；必须清掉再交给 L.map
      this._resetMapContainer(el, true)
      // 地图重建后原特效层失效，置空由 drawRoute 重建
      if (this._routeFlow) { this._routeFlow.destroy(); this._routeFlow = null }
      // crs/边界/缩放约束全部由 _mapConfig 提供（D 盘离线模式时为 4326）。
      const cfg = this._mapConfig()
      this._mapBounds = cfg.bounds
      // 「铺满屏幕」的最小缩放锁：D 盘离线瓦片只覆盖中越走廊，缩太小会露出瓦片覆盖外的空白
      const coverZoom = this._minZoomForCoverage()
      // 地图公共选项：提取成变量，catch 分支「清残留后重试」复用同一份，避免两处漂移
      const mapOpts = {
        crs: cfg.crs,
        zoomControl: true,
        attributionControl: false,
        // 业务折线（路线/风险段/基准线）改走 Canvas 批量绘制 —— 移动端长折线
        // SVG 重排是缩放卡顿的主因之一。
        // 两处 SVG 依赖已分别处理：
        // 1) routeFlow 流光/呼吸特效线在内部显式指定独立 SVG renderer，不受影响；
        // 2) risk-blink 原靠 getElement() 挂 CSS 类名，canvas 下返回 null，
        //    已改为 _setRiskBlink 的 JS opacity 脉冲。
        preferCanvas: true,
        // zoomSnap:0 → 捏合是连续分数级缩放，这是「高德那种丝滑」的关键。
        zoomSnap: 0,
        minZoom: cfg.minZoom,
        maxZoom: cfg.maxZoom,
        // 关掉 Leaflet 的缩放动画：动画期间 mapPane 挂 CSS transform 插值，
        // 业务图层（路线/标记仍由 Leaflet 定位）会被"再缩放一次"造成箭头脱节。
        // 代价：双指捏合直接跳到位、无平滑过渡（"贴得住"优先于"丝滑"，与大屏一致）。
        zoomAnimation: false,
        // 边界锁放开：与调度大屏一致自由拖拽；仅 4326 离线源保留走廊锁（瓦片只覆盖那里）
        ...(cfg.bounds ? { maxBounds: cfg.bounds, maxBoundsViscosity: 1.0 } : {}),
        bounceAtZoomLimits: false
      }
      let map
      try {
        map = L.map(el, mapOpts)
      } catch (e) {
        console.error('Leaflet 初始化失败，清理残留后重试', e)
        this._resetMapContainer(el, true)
        map = L.map(el, mapOpts)
      }
      // 先保存引用再 setView：即使 setView 抛错，后续健康检查也能拿到实例并销毁重建
      this.map = map
      try {
        this.map.setView(cfg.center, Math.min(cfg.maxZoom, Math.ceil(coverZoom)))
      } catch (e) {
        console.error('地图初始视角设置失败，退回安全视角', e)
        this.map.setView(cfg.center, cfg.minZoom)
      }

      // 窗口尺寸变化：先重算「铺满屏幕」的最小缩放，预览中再按新可视区重新适配一次路线，
      // 否则浮层高度按新视口变化后，路线可能又落到浮层下面。
      this.map.on('resize', () => {
        this._applyCoverageZoom()
        if (this.previewing) this.$nextTick(() => this.fitRoute(true))
      })
      this._applyCoverageZoom()

      // 面板高度变化（展开/收起候选列表、推送条出现/消失）会改变地图可视区域，
      // 用 ResizeObserver 让 Leaflet 重新量测容器尺寸，避免地图被裁切、瓦片错位。
      if (typeof ResizeObserver !== 'undefined' && this.$refs.mapEl) {
        this._mapRO = new ResizeObserver(() => { if (this.map) this.map.invalidateSize() })
        this._mapRO.observe(this.$refs.mapEl)
      }

      // 行驶中用户拖动地图即进入自由视角（暂停自动跟随），并显示「回到当前位置」
      this.map.on('dragstart', () => { if (this.navigating) this._pauseFollow() })

      // 缩放 / 拖动结束后按新 zoom 重新塑形路径（远景加密、近景抽稀）。
      // 后端给的 pathCoords 是「节点直连」的折线，缩到省级以下时如果不加密会直线穿山；
      // 用户缩放/拖动地图后必须用新的 zoom 重新算一次，否则线就「错位」了。
      //
      // 必须同时挂 zoomend 和 moveend：本图开了 zoomSnap:0 + zoomAnimation:false，
      // 双指捏合松手走的是 TouchZoom._onTouchEnd → map._resetView(center, _limitZoom(zoom))。
      // 捏合过程中 _move 已把分数级 zoom 逐帧写进 map._zoom，而 zoomSnap:0 时
      // _limitZoom 不做任何取整、原样返回 → _resetView 里 zoomChanged=false →
      // **松手后根本不派发 zoomend**（snap=1 的调度大屏取整后 zoom 变了才会触发）。
      // 只绑 zoomend 的后果：捏合放大后路径几何停留在旧 zoom 的抽稀结果上，
      // 直线弦切过所有弯道 —— 司机端表现即「双指缩放/拖动后线脱离马路」，
      // 且导航跟随的 _centerOn 恒用当前 zoom，之后永远等不到重绘。
      // moveend 在 _moveEnd 里是无条件派发的，用它兜住捏合松手；回调内对比
      // 当前 zoom 与上次绘制级别，纯拖动（zoom 未变）直接跳过，不会重跑整条重建。
      this._zoomRedrawRaf = null
      const redrawOnZoom = () => {
        if (this._zoomRedrawRaf) return
        this._zoomRedrawRaf = requestAnimationFrame(() => {
          this._zoomRedrawRaf = null
          if (!this.map || !this.route || !this.route.pathCoords) return
          // reshapeForZoom 只按整数档位（z|0）决定抽稀/加密强度，本图 zoomSnap:0 下
          // getZoom() 是分数级，与上次绘制值几乎永不相等——旧的全等判断让每次
          // fitBounds/捏合松手都白白重跑一遍完整 drawRoute（换选候选「卡一阵子」主因）。
          // 注释原意即「按抽稀实际生效的整数级」比较，代码对齐意图：跨整数档才重塑。
          if ((this.map.getZoom() | 0) === (this._routeDrawnZoom | 0)) return
          this.drawRoute()
        })
      }
      this.map.on('zoomend', redrawOnZoom)
      this.map.on('moveend', redrawOnZoom)

      // 底图：底图1 影像 → 底图2 矢量 → 底图3 OSM → 底图4 D 盘离线，自动降级。
      // 遮罩在首个可用底图 ready（首张瓦片成功）时收起，见 _navBase.onReady。
      this._navBase = {
        idx: this._baseStartIdx(),
        map: this.map,
        onReady: () => {
          this.mapVeil = false
          if (this._veilTimer) { clearTimeout(this._veilTimer); this._veilTimer = null }
        }
      }
      this._showMapVeil()
      this._mountBase(this.map, this._navBase)
      // 先启动体检：后续路线/标记绘制即使异常，底图仍可加载并触发自动恢复
      this._scheduleMapHealthChecks()

      // 原路线（灰虚线）在下，当前路线（绿线）在中，风险段（红线）最上
      this.baseLine = L.polyline([], { color: '#9aa5b1', weight: 3, opacity: 0.75, dashArray: '6 6' }).addTo(this.map)
      this.routeLine = L.polyline([], { color: '#1ec95f', weight: 5, opacity: 0.95 }).addTo(this.map)
      this.riskLine = L.polyline([], { color: '#e74c3c', weight: 6, opacity: 0.9 }).addTo(this.map)
      this.portMarkers = []
      PORTS.forEach(p => {
        // 口岸标记：圆点与名称都画进 divIcon 的 HTML，**不用 permanent Tooltip**。
        // Leaflet 的 Tooltip._updatePosition（DivOverlay 基类）没有 `!this._map` 守卫，
        // 标记被移除/重建的瞬间它若仍在监听 zoom，setView/fitBounds 派发事件时会抛异常，
        // 进而中断 Leaflet 的 _resetView → moveend 不触发 → 渲染器 _zoom 变陈旧
        // → 路线线宽被放大成巨大色块。大屏（MapView）早就用 divIcon 直接画标签解决了
        // 同样的问题，这里对齐它的做法。
        const m = L.marker([p.lat, p.lng], {
          icon: L.divIcon({
            className: 'port-label-wrap',
            html: `<span class="port-label-text">${p.name}</span><span class="port-dot"></span>`,
            iconSize: [0, 0],
            iconAnchor: [0, 0]
          }),
          interactive: false,
          keyboard: false
        }).addTo(this.map)
        this.portMarkers.push(m)
      })
      // 路线绘制本身异常时也不能影响已经启动的底图加载与健康检查
      try {
        this.drawRoute()
      } catch (e) {
        console.error('路线绘制失败，等待地图健康检查自动恢复', e)
      }
    },
    /** 清掉容器上残留的 Leaflet 状态：force 时连 DOM 和内部 ID 一起重置 */
    _resetMapContainer(el, force = false) {
      if (!el) return
      const staleId = el._leaflet_id
      if (!staleId && !force) return
      try { el.innerHTML = '' } catch (e) { /* 忽略 */ }
      try { delete el._leaflet_id } catch (e) { el._leaflet_id = undefined }
    },
    /** 销毁 Leaflet 实例：退出全屏地图时必须释放，避免下次进入时复用到已移除的 DOM 容器 */
    _destroyMap() {
      const el = this.$refs.mapEl
      if (this._veilTimer) { clearTimeout(this._veilTimer); this._veilTimer = null }
      this.mapVeil = false
      if (this._riskBlinkTimer) { clearInterval(this._riskBlinkTimer); this._riskBlinkTimer = null }
      this._clearMapHealthChecks()
      if (this._mapRO) { this._mapRO.disconnect(); this._mapRO = null }
      if (this._zoomRedrawRaf) { cancelAnimationFrame(this._zoomRedrawRaf); this._zoomRedrawRaf = null }
      // 地图要没了：先停 60fps 绘制循环，别让它每帧去碰已销毁的 marker/pane
      this._stopVisLoop()
      if (this._routeFlow) { this._routeFlow.destroy(); this._routeFlow = null }
      this._riskMarkers = []
      this._meMarker = null
      if (this.map) {
        try { this.map.remove() } catch (e) { /* 忽略 */ }
        this.map = null
      }
      // map.remove() 会级联移除并销毁底图图层，这里只停降级探测定时器 + 断引用
      if (this._navBase) { if (this._navBase.timer) clearTimeout(this._navBase.timer); this._navBase = null }
      this._riskBlinkPhase = false
      this._riskBlinkState = { route: false, risk: false }
      // this.map 丢失但容器仍有 _leaflet_id 时，也必须清掉，否则下次 L.map 会直接失败
      this._resetMapContainer(el, !!(el && el._leaflet_id))
      this.baseLine = null
      this.routeLine = null
      this.riskLine = null
      this.portMarkers = []
      this._corridorLayer = null // 随 map.remove() 级联销毁，这里只断引用，新地图由 _drawCorridor 重建
      this._odLayer = null      // 同上：起终点装饰层随地图销毁，drawRoute 重画
    },
    _clearMapHealthChecks() {
      ;(this._mapHealthTimers || []).forEach(t => clearTimeout(t))
      this._mapHealthTimers = []
    },
    /** 建图后自动体检：先软修复，仍无路线则硬重建；底图未出图则由遮罩 + 健康检查兜底 */
    _scheduleMapHealthChecks() {
      this._clearMapHealthChecks()
      ;[250, 1200, 6500].forEach((delay, attempt) => {
        const t = setTimeout(() => this._recoverNavMap(attempt), delay)
        this._mapHealthTimers.push(t)
      })
    },
    _mapHasVisibleRoute() {
      const el = this.routeLine && this.routeLine.getElement ? this.routeLine.getElement() : null
      if (!el) return false
      try {
        if (typeof el.getTotalLength === 'function' && el.getTotalLength() > 1) return true
        const box = typeof el.getBBox === 'function' ? el.getBBox() : null
        return !!(box && (box.width > 0 || box.height > 0))
      } catch (e) {
        return false
      }
    },
    /** 底图健康检查：看是否已有瓦片成功上屏 */
    _mapHasVisibleTiles() {
      const st = this._navBase
      if (!st) return false
      return st.loaded > 0
    },
    _routeProjectedIntoView() {
      const coords = this.route.pathCoords || []
      if (!this.map || !coords.length || !this.$refs.mapEl) return true
      const rect = this.$refs.mapEl.getBoundingClientRect()
      const step = Math.max(1, Math.floor(coords.length / 12))
      for (let i = 0; i < coords.length; i += step) {
        const p = this.map.latLngToContainerPoint(coords[i])
        if (p.x >= -80 && p.x <= rect.width + 80 && p.y >= -80 && p.y <= rect.height + 80) return true
      }
      return false
    },
    _recoverNavMap(attempt) {
      if (!this.navigating && !this.previewing) return
      const el = this.$refs.mapEl
      if (!el) { this._destroyMap(); return }
      if (!this.map || !this.map.getContainer || this.map.getContainer() !== el) {
        console.warn('地图容器失效，自动重建', { attempt })
        this._destroyMap()
        this._mapRenderKey++
        this.$nextTick(() => {
          try {
            this._buildMap()
          } catch (e) {
            console.error('地图健康检查重建失败', e)
            this._scheduleMapHealthChecks()
          }
        })
        return
      }

      // 软修复：重新量测布局、同步 SVG 渲染器并重画路线
      this.map.invalidateSize(true)
      this._applyCoverageZoom()
      this._resyncMapRenderer()
      try {
        this.drawRoute()
      } catch (e) {
        console.error('地图健康检查重绘路线失败', e)
      }
      if (this.mapVeil && this._mapHasVisibleTiles()) this.mapVeil = false
      const hasRouteLayer = !((this.route.pathCoords || []).length) || this._mapHasVisibleRoute()
      const routeInView = !((this.route.pathCoords || []).length) || this._routeProjectedIntoView()
      if (!routeInView) this.fitRoute(true)
      if (!hasRouteLayer && attempt >= 1 && !this._mapHardRecovered) {
        console.warn('路线图层未渲染，自动硬重建地图')
        this._mapHardRecovered = true
        this._destroyMap()
        this._mapRenderKey++
        this.$nextTick(() => {
          try {
            this._buildMap()
            // 硬重建后恢复视角：导航中用 zoomToNav（静默全览已被拦下），预览态仍用 fitRoute
            if (this.navigating) this.zoomToNav(); else this.fitRoute(true)
          } catch (e) {
            console.error('地图硬重建失败，继续自动恢复', e)
            this._scheduleMapHealthChecks()
          }
        })
        return
      }
      if (attempt === 0 && this.navigating && !this._navZoomReady) {
        this.$nextTick(() => this.zoomToNav(0.10, 0, 0))
      }
    },
    /**
     * 全局点击涟漪反馈（事件委托，无需改动各按钮组件）。
     * 命中按钮/卡片类元素时在点击坐标注入波纹扩散动画，松手即反馈。
     */
    _initRipple() {
      const SELECTOR = '.start-btn, .confirm-task-btn, .quick-chip, .chip, .cand-item, .plan-item, .lang-btn, .speak-btn, .refresh, .home-nav span, .drv-back-btn, .save-btn'
      this._rippleHandler = (e) => {
        const host = e.target.closest(SELECTOR)
        if (!host || host.disabled) return
        // 导航/浅色区不加（波纹配色为深色专用）
        if (!host.closest('.phone') || host.closest('.phone.nav-mode')) return
        host.classList.add('ripple-host')
        const rect = host.getBoundingClientRect()
        const ink = document.createElement('span')
        ink.className = 'ripple-ink'
        const size = Math.max(rect.width, rect.height)
        ink.style.width = ink.style.height = size + 'px'
        ink.style.left = (e.clientX - rect.left - size / 2) + 'px'
        ink.style.top = (e.clientY - rect.top - size / 2) + 'px'
        host.appendChild(ink)
        setTimeout(() => ink.remove(), 600)
      }
      document.addEventListener('pointerdown', this._rippleHandler, { passive: true })
    },
    /**
     * 左边缘侧滑返回（iOS 风格）：仅当手势从屏幕左边缘起手、向右水平滑动时触发，
     * 避免与地图拖动、列表滚动冲突。统一走 goBack() 返回栈。
     */
    _initSwipeBack() {
      this._swipe = null
      // 移动版窄列（phone-mini）居中后，列左缘不在视口 0 点：起手区要跟随列左缘，
      // 否则导航屏缩在手机列里、侧滑手势却还贴在整窗左边缘，两者对不上。
      // PC 版/真实手机上列左缘本来就是 0，行为不变。
      const edgeLeft = () => {
        try {
          const r = this.$el && this.$el.getBoundingClientRect && this.$el.getBoundingClientRect()
          if (r && r.left > 0) return r.left
        } catch (e) { /* 取不到几何时退回视口左缘 */ }
        return 0
      }
      this._swipeStartHandler = (e) => {
        const x = e.clientX
        const y = e.clientY
        if (x == null || y == null) { this._swipe = null; return }
        // 仅列左缘 32px 内起手才算侧滑返回（左侧留 6px 容差，列外底色上起手也算）
        const edge = edgeLeft()
        if (x < edge - 6 || x > edge + 32) { this._swipe = null; return }
        this._swipe = { x, y, t: Date.now() }
      }
      this._swipeEndHandler = (e) => {
        const s = this._swipe
        this._swipe = null
        this.swipeEdgeProgress = 0
        if (!s) return
        const x = e.clientX
        const y = e.clientY
        if (x == null || y == null) return
        const dx = x - s.x
        const dy = y - s.y
        const dt = Date.now() - s.t
        // 向右、水平主导、距离与速度足够 → 返回上一级
        if (dx > 60 && Math.abs(dy) < 70 && dx > Math.abs(dy) * 1.4 && dt < 800) {
          this.goBack()
        }
      }
      // 跟手反馈：从左边缘向右水平拖动时点亮边缘指示条，进度拉满（≈60px）时松手即返回
      this._swipeMoveHandler = (e) => {
        const s = this._swipe
        if (!s) return
        const dx = (e.clientX || 0) - s.x
        const dy = (e.clientY || 0) - s.y
        if (dx > 2 && dx > Math.abs(dy) && Math.abs(dy) < 70) {
          const p = Math.min(1, dx / 60)
          if (p !== this.swipeEdgeProgress) this.swipeEdgeProgress = p
        } else if (this.swipeEdgeProgress) {
          this.swipeEdgeProgress = 0
        }
      }
      this._swipeCancelHandler = () => { this._swipe = null; this.swipeEdgeProgress = 0 }
      document.addEventListener('pointerdown', this._swipeStartHandler, { passive: true })
      document.addEventListener('pointermove', this._swipeMoveHandler, { passive: true })
      document.addEventListener('pointerup', this._swipeEndHandler, { passive: true })
      document.addEventListener('pointercancel', this._swipeCancelHandler, { passive: true })
    },
    /**
     * 统一返回栈：先关弹层，再退导航/预览，最后收首页抽屉/搜索框。
     * 侧滑返回、以及后续任何"返回上一级"入口都应走这里。
     */
    goBack() {
      if (this.showExitConfirm) { this.showExitConfirm = false; return }
      if (this.hazardDetail) { this.hazardDetail = null; return }
      if (this.showReroute) { this.showReroute = false; return }
      if (this.showNavMenu) { this.showNavMenu = false; return }
      if (this.rerouteOptions.length) { this.rerouteOptions = []; return }
      if (this.navigating) { this.requestExit(); return }
      if (this.previewing) { this.exitPreview(); return }
      if (this.homeSearchOpen) { this.homeSearchOpen = false; return }
      if (this.tab !== 'route') { this.tab = 'route'; return }
    },
    // ---------- 底图降级链：底图1 天地图影像 → 底图2 天地图矢量 → 底图3 OSM → 底图4 D 盘离线瓦片 ----------
    /** 起始源下标：显式离线（navigator.onLine=false，如拔网线演示）直接落到本地矢量，省掉在线探测等待 */
    _baseStartIdx() {
      // 已进 D 盘离线模式，或浏览器显式离线 → 直接落到末级（D 盘），跳过在线探测
      const navOffline = typeof navigator !== 'undefined' && navigator.onLine === false
      return (this._useOfflineD || navOffline) ? BASE_CHAIN.length - 1 : 0
    },
    _baseChainKey(idx) {
      return BASE_CHAIN[Math.max(0, Math.min(idx, BASE_CHAIN.length - 1))]
    },
    /**
     * 在给定地图上挂载当前源底图，并挂上降级探测。
     * st = { idx, map, key, kind, tile, anno, mb, loaded, errored, settled, timer, onReady }
     * 在线源：探测窗口内一张瓦片都没成功（tileerror 达阈值 / 超时）即降到下一源。
     */
    _mountBase(map, st) {
      if (!map || !st) return
      this._clearBase(map, st)
      const key = this._baseChainKey(st.idx)
      st.key = key
      st.loaded = 0
      st.errored = 0
      st.settled = false

      // 末级：D 盘离线瓦片（EPSG:4326）。主图是 3857，无法把 4326 栅格当一层直接叠（会错位），
      // 故：若当前已是 4326 离线图 → 直接铺 D 盘 jpg 层；否则 → 触发整图重建切到 4326。
      if (key === 'vector') {
        if (map.options && map.options.crs === L.CRS.EPSG4326) {
          const oc = this._mapConfig()
          st.kind = 'raster'
          st.tile = L.tileLayer(oc.tileUrl, oc.tileOpts).addTo(map)
          st.tile.on('tileload', () => { st.loaded++; this._settleBase(st) })
          st.tile.on('tileerror', () => { st.errored++ })
          // 本地同源瓦片，必达；兜底 1.5s 无条件判可用，避免探测卡住遮罩
          st.timer = setTimeout(() => this._settleBase(st), 1500)
          this._onBaseSource(st)
          return
        }
        this._enterOfflineD(st)
        return
      }

      // 在线栅格源（底图1 天地图影像 / 底图2 矢量 / 底图3 OSM）
      const def = baseDef(key)
      if (!def) { st.idx = BASE_CHAIN.length - 1; this._mountBase(map, st); return }
      st.kind = 'raster'
      const tileOpts = {
        // 代理源（底图1/底图2）URL 无 {s} 占位符、def 里没有 subdomains；但 Leaflet 铺瓦时
        // 无条件读 options.subdomains.length（_getSubdomain），显式传 undefined 会覆盖默认值
        // 并在建图阶段直接抛 TypeError，必须兜底给个占位字符串。
        subdomains: def.subdomains || 'abc',
        attribution: def.attribution,
        // detectRetina 在初始化时会把本层 maxZoom 偷偷减 1（TileLayer.initialize），
        // 而地图 maxZoom 仍是 def.maxZoom → 手机放到最大级时 tileZoom > 层 maxZoom，
        // Leaflet _setView 直接把 tileZoom 置 undefined、一张瓦都不铺 → 整屏白底
        // （即“放到最大白屏、缩小一点就恢复”；zoomToNav 开场自动拉到最大级同理）。
        // 视网膜屏在这里补回 +1，被减后恰好落在源最大级，最大级由 maxNativeZoom overzoom 铺瓦。
        maxZoom: (def.maxZoom || 18) + (L.Browser.retina ? 1 : 0),
        // 天地图影像 img_w 中越走廊 >z16 无覆盖（实测 z17/z18 不同坐标全回同一张 4.7KB
        // 的 200 纯白瓦片）；矢量源无此限制，def 未给 maxNativeZoom 时回落到 maxZoom。
        // 视网膜屏要再减 1：detectRetina 的 zoomOffset=+1 是加在**被 maxNativeZoom 夹过
        // 之后**的请求级别上（tileZoom 先夹、URL 再 +1），不扣回去手机实际会请求
        // maxNativeZoom+1 级 → 导航开场自动放到最大级时整屏取到 z17 纯白瓦片，
        // 就是「放大到一定程度白屏、缩小一点就出来」的根因。
        maxNativeZoom: (def.maxNativeZoom || (def.maxZoom || 18)) - (L.Browser.retina ? 1 : 0),
        updateWhenIdle: false,
        keepBuffer: 2,
        crossOrigin: def.crossOrigin || false,
        // 高分辨率渲染：手机是 2~3x 视网膜屏，Leaflet 默认按 1 倍瓦片铺会被拉伸发虚。
        // detectRetina 在视网膜屏上自动请求高一级瓦片按半尺寸铺（影像/注记各自 +1 级 zoom），
        // 道路与地名明显更锐利；桌面 Browser.retina=false 时是空操作。天地图 img_w 有到 z18
        // 的高清瓦片供取用（注记 cia_w 同步 +1 级，标注与影像对齐）。
        detectRetina: true
      }
      // 天地图两级源已走同源后端代理（密钥/缓存都在后端），所有源都是标准 L.tileLayer
      st.tile = makeTileLayer(def.url, tileOpts).addTo(map)
      // 天地图注记层（cia_w，透明 PNG）：叠在影像上显示地名/边界，不参与降级探测
      if (def.annoUrl) {
        st.anno = makeTileLayer(def.annoUrl, { ...tileOpts, attribution: '', className: 'tdt-anno-layer' }).addTo(map)
      }
      st.tile.on('tileload', () => { st.loaded++; this._settleBase(st) })
      st.tile.on('tileerror', () => {
        st.errored++
        // 一张都没成功且已失败多张 → 判定该在线源不可用，立即降级
        if (!st.settled && st.loaded === 0 && st.errored >= 2) this._fallbackBase(st)
        this._onBaseDegraded(st)
      })
      // 探测窗口：请求一直挂起（无 error 也无 load）时，4s 后仍无成功瓦片则降级
      st.timer = setTimeout(() => { if (!st.settled && st.loaded === 0) this._fallbackBase(st) }, 4000)
      this._onBaseSource(st)
    },
    /** 首张瓦片成功 / 矢量首帧：标记该源可用，停掉降级探测，收起遮罩 */
    _settleBase(st) {
      if (!st || st.settled) return
      st.settled = true
      // 记下判定可用时已发生的错误数：settle 之后的**新增**错误才代表底图在运行中坏掉
      st.settledErrored = st.errored
      if (st.timer) { clearTimeout(st.timer); st.timer = null }
      if (typeof st.onReady === 'function') st.onReady()
    },
    /**
     * 底图「已经收下、但运行中持续取不到瓦片」的自愈入口。
     *
     * 为什么必须有它：天地图按 tk 限流（上游回 429）漏掉的那几张瓦片，
     * 会在地图上留下一片跟着地图一起移动的模糊补位块；而 _settleBase 早已
     * 判定该源可用、降级链不会触发 —— 必须在 settle 之后继续盯错误计数。
     *
     * 处理：先原地重挂一次底图（新图层实例 = 全新瓦片缓存，强制重新取瓦，
     * 限流只是瞬时抖动时用户几乎无感）；重挂后仍坏则降级到链中下一个源。
     */
    _onBaseDegraded(st) {
      if (!st || !st.settled || !st.map) return
      // 末级（D 盘离线）没有可降的下一源，重挂也拿不到数据，不做无谓重建
      if (st.idx >= BASE_CHAIN.length - 1) return
      if ((st.errored - (st.settledErrored || 0)) < BASE_DEGRADE_ERROR_BUDGET) return
      if (!st.remounted) {
        st.remounted = true
        console.warn(`底图[${st.key}]持续取不到瓦片，重挂强制重新取瓦`)
        this._mountBase(st.map, st)
        return
      }
      console.warn(`底图[${st.key}]重挂后仍持续失败，降级`)
      this._fallbackBase(st, true)
    },
    /** 降级到链中下一个源；已是最后一级（D 盘离线）则不再降。force 用于突破「已 settle」的判定 */
    _fallbackBase(st, force) {
      if (!st) return
      if (st.settled && !force) return
      if (st.idx >= BASE_CHAIN.length - 1) return
      const from = this._baseChainKey(st.idx)
      st.idx++
      console.warn(`底图[${from}]不可用，降级为[${this._baseChainKey(st.idx)}]`)
      // 降级后把遮罩重新拉起，直到新源 ready，避免降级途中露出空白底
      if (st === this._navBase) this._showMapVeil()
      this._mountBase(st.map, st)
    },
    /**
     * 手动循环切换底图：底图1 天地图影像 → 底图2 天地图矢量 → 底图3 OSM → 底图4 D 盘离线 → 回到底图1。
     * 同为在线 3857 源（底图1 ↔ 底图2 ↔ 底图3）只重挂瓦片层；进出底图4 D 盘离线（4326↔3857）
     * 必须整图重建（与 _enterOfflineD 同一套路，只是方向由用户手动决定，
     * 不挂「网络恢复自动切回」监听——离线是用户主动选的，不该被自动切走）。
     */
    cycleBase() {
      const isHome = this.homeVisible
      const st = isHome ? this._homeBase : this._navBase
      if (!st || !st.map) return
      const nextIdx = (st.idx + 1) % BASE_CHAIN.length
      const nextOffline = this._baseChainKey(nextIdx) === 'vector'
      if (this._useOfflineD === nextOffline) {
        // 同为在线 3857 源：原地重挂图层即可，无需重建地图
        st.idx = nextIdx
        st.remounted = false
        if (st === this._navBase) this._showMapVeil()
        this._mountBase(st.map, st)
        return
      }
      // 跨坐标系：翻转离线标志后整图重建，_baseStartIdx 会自动落到对应级别
      this._useOfflineD = nextOffline
      if (isHome) { this._destroyHomeMap(); this._buildHomeMap() }
      else { this._rebuildMapForBaseSwitch() }
    },
    /**
     * 底图跨坐标系切换（在线 3857 ↔ D 盘离线 4326）时的整图重建，并**完整恢复导航运行时**。
     *
     * 为什么不能只 _destroyMap+_buildMap+zoomToNav：整图重建会销毁 _meMarker，而
     * _destroyMap 不会停模拟行驶定时器（_triTimer 仍空转）、_buildMap 也不恢复车辆；
     * zoomToNav 又把视角拉回**路线起点**。于是导航中切底图后：车辆标记消失、地图跳回起点、
     * 不再跟随 —— 起点若正好落在离线瓦片覆盖边缘还会整屏空白，表现就是"切底图后白屏/坏掉"。
     *
     * 这里在重建前抓取车辆当前位置/缩放/模拟状态，重建后原地恢复：车辆标记回到原位、
     * 模拟从当前进度续跑、视角居中到车辆（而非起点）、自动跟随保持。
     */
    _rebuildMapForBaseSwitch() {
      const isNav = this.navigating
      // 整图重建会销毁这些状态，先抓快照用于重建后恢复
      const vehPos = this._meMarker ? this._meMarker.getLatLng() : null
      const prevZoom = this.map ? this.map.getZoom() : null
      const wasSimulating = !!this._triTimer
      // _destroyMap 不停模拟定时器；先停掉，重建后再从当前进度恢复，避免旧定时器空转
      this._stopTripSimulation()

      this._destroyMap()
      this._buildMap()          // 内部已挂载新底图并 drawRoute
      try { this.drawRoute() } catch (e) { console.error('切换底图后重绘路线失败', e) }

      if (isNav) {
        // 车辆标记 + 模拟行驶原地恢复（从当前进度续跑，不回起点）
        if (wasSimulating) this._resumeTripSimulation()
        else if (vehPos) this._placeTriMarker(vehPos.lat, vehPos.lng)
        // 视角居中到车辆当前位置（缺省退回路线起点），保持自动跟随
        this.navFollowing = true
        this.navOffView = false
        this._navZoomReady = true
        const target = vehPos ||
          (this.route.pathCoords && this.route.pathCoords[0]
            ? L.latLng(this.route.pathCoords[0][0], this.route.pathCoords[0][1]) : null)
        const z = prevZoom != null ? Math.min(prevZoom, this._navMaxZoom()) : this._navMaxZoom()
        if (target) { this._centerOn(target, z, 0, false); this._resyncMapRenderer() }
        else this.zoomToNav()
      } else if (this.previewing) {
        this.fitRoute(false)
      }
    },
    /**
     * 切到 D 盘离线瓦片：因那套 jpg 是 EPSG:4326、与主图 3857 不同系，无法就地叠层，
     * 只能把对应地图整图重建为 4326 模式（_mapConfig 据 _useOfflineD 返回 4326 配置）。
     * 网络恢复后自动切回在线天地图。仅在底图1 影像 + 底图2 矢量 + 底图3 OSM 三个在线源都探测失败时才走到这里，正常在线不受影响。
     */
    _enterOfflineD(st) {
      if (this._useOfflineD) return
      this._useOfflineD = true
      console.warn('在线底图（底图1 影像/底图2 矢量/底图3 OSM）均不可用，重建为底图4 D 盘离线瓦片（EPSG:4326）')
      const isNav = st === this._navBase
      const rebuild = () => {
        if (isNav) { this._rebuildMapForBaseSwitch() }
        else { this._destroyHomeMap(); this._buildHomeMap() }
      }
      rebuild()
      // 网络恢复：清标志、重建回在线天地图，只挂一次
      const onOnline = () => {
        window.removeEventListener('online', onOnline)
        if (!this._useOfflineD) return
        this._useOfflineD = false
        console.warn('网络恢复，切回在线天地图底图')
        rebuild()
      }
      window.addEventListener('online', onOnline)
    },
    /** 源切换后同步地图 maxZoom：在线栅格 18/19，D 盘离线 13/14（更高级别由 overzoom 拉伸） */
    _onBaseSource(st) {
      if (!st || !st.map) return
      // 同步手动切换按钮上显示的底图短名
      this.baseName = BASE_SHORT_NAMES[st.key] || st.key
      const def = baseDef(st.key)
      const mz = st.key === 'vector' ? (this._useOfflineD ? 13 : 14) : (def && def.maxZoom ? def.maxZoom : 18)
      try { if (st.map.options.maxZoom !== mz) st.map.setMaxZoom(mz) } catch (e) { /* 忽略 */ }
    },
    /** 清理某张地图上的底图图层与探测定时器（map.remove 会级联，这里主要停定时器/断引用） */
    _clearBase(map, st) {
      if (!st) return
      if (st.timer) { clearTimeout(st.timer); st.timer = null }
      if (st.tile) { try { map.removeLayer(st.tile) } catch (e) { /* 忽略 */ } st.tile = null }
      if (st.anno) { try { map.removeLayer(st.anno) } catch (e) { /* 忽略 */ } st.anno = null }
      st.kind = null
      st.settled = false
      // remounted 是「这条降级链上重挂过几次」的跨挂载计数，不能随单次挂载清掉，否则会无限重挂
      st.settledErrored = 0
    },
    /**
     * 底图加载遮罩：首个可用底图 ready 时由 _navBase.onReady 收起；
     * 已 ready（复用地图）立即收起，事件缺失时 8s 兜底收起，不会永久盖住路线。
     */
    _showMapVeil() {
      this.mapVeil = true
      if (this._veilTimer) { clearTimeout(this._veilTimer); this._veilTimer = null }
      if (this._mapHasVisibleTiles()) { this.mapVeil = false; return }
      this._veilTimer = setTimeout(() => { this.mapVeil = false }, 8000)
    },
    /**
     * 进入导航的瞬间：视角会做一次大缩放/平移（fitRoute→zoomToNav），在线底图需要
     * 重新拉一批新瓦片，铺满前 Leaflet 容器会露出灰白底 —— 这就是“开始导航后白屏”的观感。
     * 这里在切视角前“有条件地”重新拉起遮罩：先给 250ms 宽限，快的网络首批瓦片已到→
     * 全程不闪遮罩；确实慢才显示“地图加载中”，直到当前视野铺满（TileLayer load）或 3s 兜底收起。
     * 仅对在线栅格源生效；D 盘离线瓦片本地必达，无需遮罩。
     */
    _veilUntilViewPaint() {
      const st = this._navBase
      if (!st || st.kind !== 'raster' || !st.tile) return
      let done = false
      const hide = () => {
        done = true
        this.mapVeil = false
        if (this._veilTimer) { clearTimeout(this._veilTimer); this._veilTimer = null }
      }
      // 宽限期内已有瓦片成功→视为不慢，直接取消弹遮罩，避免快网下每进一次导航都闪一下
      try { st.tile.once('tileload', () => { if (!this.mapVeil) done = true }) } catch (e) { /* 忽略 */ }
      setTimeout(() => {
        if (done || !this._navBase || this._navBase !== st) return
        this.mapVeil = true
        try { st.tile.once('load', hide) } catch (e) { /* 忽略 */ }
        this._veilTimer = setTimeout(hide, 3000)
      }, 250)
    },
    /**
     * 底图配置（EPSG:3857）：默认在线天地图，maxZoom 由 _onBaseSource 按当前源实时校正
     *（在线栅格 18/19，D 盘离线 13/14）。边界锁在中越走廊：无论哪种底图，视野都聚焦业务运营区。
     */
    _mapConfig() {
      // D 盘离线模式：那套离线栅格是 EPSG:4326、只覆盖中越走廊 z7~z13，
      // 必须用 4326 坐标系建图并把范围/缩放锁到瓦片覆盖内，否则会整图错位、露白。
      if (this._useOfflineD) {
        return {
          crs: L.CRS.EPSG4326,
          bounds: L.latLngBounds([[20.49, 101.68], [24.01, 109.51]]),
          minZoom: 5,
          maxZoom: 18,
          center: [21.6, 106.8],
          offlineD: true,
          tileUrl: '/tiles/{z}/{x}/{y}.jpg',
          tileOpts: { minZoom: 5, maxZoom: 18, minNativeZoom: 7, maxNativeZoom: 13, tileSize: 256, attribution: '© OpenStreetMap（离线）' }
        }
      }
      return {
        crs: L.CRS.EPSG3857,
        // 边界锁放开（与调度大屏一致）：在线底图全球可用，不再锁中越走廊
        minZoom: 3,
        maxZoom: 18,  // 在线底图上限；降级到 D 盘离线时由 _onBaseSource 收到 13/14
        center: [21.6, 106.8]
      }
    },
    /**
     * 让底图「铺满屏幕」所需的最小缩放级别（EPSG:3857）。
     * z 级世界像素 = 256·2^z；经度线性：屏幕宽需占满覆盖经度跨度，
     * 纬度按墨卡托 Y 跨度换算；解 256·2^z ≥ max(w·360/lngSpan, h·360/mercSpanY)。
     */
    _minZoomForCoverage() {
      if (!this._mapBounds) return 7
      // D 盘离线（4326）：等距圆柱每度像素 = 512·2^z/360，两轴同率，按走廊经纬跨度铺满求解
      if (this._useOfflineD) {
        const el4326 = this.$refs.mapEl
        const size4326 = this.map ? this.map.getSize() : (el4326 ? L.point(el4326.clientWidth, el4326.clientHeight) : null)
        if (!size4326 || !size4326.x || !size4326.y) return 7
        const lngSpan4326 = this._mapBounds.getEast() - this._mapBounds.getWest()
        const latSpan4326 = this._mapBounds.getNorth() - this._mapBounds.getSouth()
        if (lngSpan4326 <= 0 || latSpan4326 <= 0) return 7
        const need4326 = Math.max(size4326.x * 360 / lngSpan4326, size4326.y * 360 / latSpan4326)
        return Math.min(13, Math.max(7, Math.log2(need4326 / 512) + 0.003))
      }
      const el = this.$refs.mapEl
      // 地图已建好就用 Leaflet 的尺寸；还没建（初始化）就用容器实际像素尺寸
      const size = this.map ? this.map.getSize() : (el ? L.point(el.clientWidth, el.clientHeight) : null)
      if (!size || !size.x || !size.y) return 7
      const lngSpan = this._mapBounds.getEast() - this._mapBounds.getWest()
      const mercSpanY = Math.abs(mercY(this._mapBounds.getNorth()) - mercY(this._mapBounds.getSouth()))
      if (lngSpan <= 0 || mercSpanY <= 0) return 7
      // 视口换算成「世界像素」需求：宽/高各自除以覆盖跨度（360° 对应世界一周）
      const needWorldPx = Math.max(size.x * 360 / lngSpan, size.y * 360 / mercSpanY)
      // 保留小数：分数级缩放下最小级别正好等于「刚好铺满」，多出的小数留给路线适配
      const z = Math.log2(needWorldPx / 256) + 0.003 // +0.003 兜掉浮点误差，避免边缘露一条灰缝
      return Math.min(14, Math.max(7, z))
    },
    /** 锁死最小缩放级别：缩小到会出现空白之前就停住，保证整张地图始终固定在屏幕上 */
    _applyCoverageZoom() {
      this._applyMapMinZoom()
    },
    /**
     * 计算「让整条路线完整落在可视区」所需的缩放级别（EPSG:3857）。
     * 只统计顶部状态栏以下、底部留白以上的可视区（左右各留 22px）。
     */
    _routeFitZoom() {
      if (!this.map || !this.route.pathCoords || !this.route.pathCoords.length) return null
      const size = this.map.getSize()
      const availW = size.x - 44
      const availH = size.y - this._topOverlayPadding() - this._bottomOverlayPadding()
      if (availW <= 40 || availH <= 40) return null
      // D 盘离线（4326）：跨度用线性经纬度，别用 3857 的 mercY
      if (this._useOfflineD) {
        const bb = L.latLngBounds(this.route.pathCoords)
        const sLng = bb.getEast() - bb.getWest()
        const sLat = bb.getNorth() - bb.getSouth()
        if (sLng <= 0 || sLat <= 0) return null
        const need = Math.min(availW * 360 / sLng, availH * 360 / sLat)
        return Math.max(7, Math.min(13, Math.log2(need / 512)))
      }
      const b = L.latLngBounds(this.route.pathCoords)
      const lngSpan = b.getEast() - b.getWest()
      const mercSpanY = Math.abs(mercY(b.getNorth()) - mercY(b.getSouth()))
      if (lngSpan <= 0 || mercSpanY <= 0) return null
      // 可视区换算成「世界像素」上限：256·2^z ≤ min(可用宽·360/经度跨度, 可用高·360/墨卡托跨度)
      const maxWorldPx = Math.min(availW * 360 / lngSpan, availH * 360 / mercSpanY)
      const z = Math.log2(maxWorldPx / 256)
      return Math.max(7, Math.min(14, z)) // 矢量瓦片覆盖 z7~z14，不再往下
    },
    /**
     * 统一设置地图最小缩放级别：
     * - 平时锁在「瓦片铺满屏幕」那一级（分数级，正好铺满、不留灰边），防止整张底图被拖出灰边；
     * - 预览时若整条路线比「铺满级别」看到的范围还大，则临时放宽到「刚好装下整条路线」那一级
     *   （分数级），保证路线完整可见。属于极少数情况，绝大多数时候不会触发。
     */
    _applyMapMinZoom() {
      if (!this.map) return
      // 缩放锁放开：下限直接用底图配置的 minZoom，不再按「铺满走廊」收紧；
      // 仅预览时若整条路线比下限视野还大，才临时放宽到装下路线的那一级
      let floor = (this._mapConfig() || {}).minZoom || 3
      if (this.previewing) {
        const rz = this._routeFitZoom()
        if (rz != null) floor = Math.min(floor, rz)
      }
      if (this.map.options.minZoom !== floor) this.map.setMinZoom(floor)
    },
    // 用后端下发的 pathCoords 绘制（司机端不加载 35MB 路网）
    drawRoute(opts) {
      if (!this.map || !this.route.pathCoords) return
      // trimOnly：导航中「剪掉已走过线头」（_trimPassedRoute，每 ~1.6s 一次）触发的重绘。
      // 此时终点旗帜/后续走廊/风险打点这些「只随整条路线或缩放变化、与线头无关」的图层
      // 没有任何变化，跳过它们的拆除重建，只更新主线/风险线/特效层几何——配合下面 cur() 的
      // 双份 reshape 合并，把每次修剪的重绘成本砍到最低，消除「走几秒卡一下」的周期性掉帧。
      const trimOnly = !!(opts && opts.trimOnly)
      const z = (this.map.getZoom && this.map.getZoom()) || 8
      // 记住本次绘制用的 zoom：moveend 兜底重绘（见 _buildMap 里 redrawOnZoom）据此
      // 判断几何是否已过时，zoom 未变就跳过，避免每次平移都重建整条路线。
      this._routeDrawnZoom = z
      // 按当前 zoom 把路径几何重新塑形：远景加密（避免直线穿山）、近景抽稀（性能）。
      // cur 改惰性求值：只有「整条染红」或「桌面 routeFlow 特效层」才真正用到它，
      // 手机端安全路线下这两个分支都不走，若无条件计算等于对几千个点白跑一遍
      // DP 抽稀 + 测地加密 —— 换选/缩放卡顿的主要前端成本，故用到才算且只算一次。
      let _curCache = null
      const cur = () => (_curCache || (_curCache = reshapeForZoom(this.route.pathCoords || [], z, this.map)))
      // 未绕行时基准线与当前线重合，baseLine 会被清空 —— 那就没必要塑形。
      const base = this.route.rerouted
        ? reshapeForZoom(this.route.baselinePathCoords || [], z, this.map)
        : []
      this.baseLine.setLatLngs(this.route.rerouted ? base : [])

      // 预览阶段候选只有聚合风险（hazardProbability / riskEdgeIds），
      // 无逐段风险可用时，整条路线标红闪烁，与右侧红色风险卡片呼应
      const previewRisky = this.previewing && !this.navigating && this.isHighRisk(this.selectedCandidate)
      const riskSet = this._riskEdgeSet()

      // 核心：按「风险 / 安全」把路径拆成连续多段，用来判定「本条线到底命没命中当前灾害」
      const groups = this._buildColoredGroups(z, riskSet)
      const riskGroups = groups.filter(g => g.risk).map(g => g.pts)
      // 预览高风险但拿不到逐段风险时，整条按风险处理
      const previewWhole = previewRisky && riskGroups.length === 0
      /*
       * 命中即整条染红，不命中即整条绿。
       * 旧做法只把受灾那一截染红、其余保持亮绿：司机在车上是贴着路面看的，
       * 一屏里往往全是安全段，灾害触发了线却「看起来还是绿的」，反馈等于没有。
       * 整条红的语义也更准——「这条线现在不能走」，而不是「这条线里有一小段要小心」。
       * 换到不命中风险的路线（调度下发新线 / 风险解除）后 groups.some(risk) 自然为假，整条回到绿。
       */
      const lineRisk = previewWhole || riskGroups.length > 0
      // 红色光晕只压在实际受灾那一截上，指出「灾害在这个位置」
      const riskDraw = previewWhole ? [cur()] : riskGroups
      // 安全线（无风险边）时 splitRouteByRisk 只产出一段=整条，其塑形结果与 cur() 逐字相同；
      // 预先把这段塞进 cur 缓存，让下面 routeFlow 不再对整条 pathCoords 白跑第二遍
      // DP 抽稀 + 折返/回溯尖刺清理——导航每 ~1.6s 修剪一次，这第二遍全量 reshape 正是掉帧主因。
      if (!lineRisk && groups.length === 1 && groups[0] && !groups[0].risk) _curCache = groups[0].pts

      // 底层静态线：作为 Flow 特效之外的兜底，配色与 Flow 保持一致
      const baseWidth = this.navigating ? 7 : 5
      this.routeLine.setStyle({
        color: lineRisk ? '#e53935' : '#1ec95f', weight: baseWidth, opacity: 1, dashArray: null
      })
      const linePts = lineRisk ? [cur()] : groups.filter(g => !g.risk).map(g => g.pts)
      this._routeLinePts = linePts   // 零帧同步钩子：_tetherRouteHead 每 tick 改首点 setLatLngs
      this.routeLine.setLatLngs(linePts)
      // 风险段：Flow 特效层（markerPane）更高，会把这里细红线整条盖住，
      // 因此用更宽的半透明红色脉冲光晕，让风险段在地图上一眼可辨
      this.riskLine.setStyle({ color: '#e53935', weight: baseWidth + 9, opacity: 0.45, dashArray: null })
      this._riskLinePts = riskDraw
      this.riskLine.setLatLngs(riskDraw)
      // 风险闪烁：preferCanvas 后折线无 SVG 元素可挂 risk-blink 类名，改 JS opacity 脉冲。
      // 整条受灾时连主线一起闪，比只晃一下光晕更难被当成误触。
      this._setRiskBlink(lineRisk, riskDraw.length > 0)

      // 前进箭头 + 流动主线（高德风：深描边 + 粗主线 + 白色 V 形前进箭头）
      // 特效层在 markerPane（600）之上，整条同色即可，不必再按段拆（拆了也只会被同色覆盖）
      // 手机端同样保留该样式：用下面 lite + staticArrows 配置——箭头等距静态铺放不起 rAF、
      // 关 drop-shadow 与流光 CSS，逐帧成本≈0；换线重建的开销也已随 redrawOnZoom
      // 只按整数档触发而减半，故手机与桌面观感一致（白色 V 形前进箭头）。
      if (cur().length > 1) {
        // 安全=亮绿+深绿描边；风险=红+暗红描边，与风险卡片/红线语义一致
        const SAFE = '#1ec95f'; const SAFE_CASE = '#0a7a3c'
        const RISK = '#ef4444'; const RISK_CASE = '#8f1d1d'
        const segs = [{
          latlngs: cur(),
          color: lineRisk ? RISK : SAFE,
          casing: lineRisk ? RISK_CASE : SAFE_CASE
        }]
        // 移动端降配：staticArrows 箭头等距固定不推进（不起 rAF 循环，省 CPU）；
        // lite 关闭 drop-shadow 滤镜与流光/脉冲 CSS 动画（CSS 动画不受 fps 约束，是滑动掉帧主因）。
        // 大屏不传这些，保持全特效。coreWidth 10 + casingBorder 6 ≈ 高德主线视觉粗度。
        // 静态箭头零逐帧开销：改为视口内等距铺放后，密度与缩放无关，放大跟随也不会空。
        // spacingPx 48（手机上约每屏 8~12 个箭头，看着像高德那种连续方向标）、
        // maxArrows 64（仅在整条路线尽缩尽览时兜底封顶）。
        if (!this._routeFlow) this._routeFlow = createRouteFlow(this.map, {
          color: SAFE, casing: SAFE_CASE,
          arrowShape: 'chevron', arrowColor: '#ffffff',
          coreWidth: 10, lite: true, spacingPx: 48, maxArrows: 64, staticArrows: true
        })
        this._routeFlow.setSegments(segs)
      } else if (this._routeFlow) {
        this._routeFlow.setSegments([])
      }

      // 风险段额外加发光标记（采样打点，避免密集几何生成上千个 marker）
      // 导航修剪重绘跳过：风险打点落在车前方的风险段上，剪线头不会移动它们
      if (!trimOnly) this._updateRiskMarkers(riskDraw)

      // 陆水联运（方案B）：司机公路段到南宁港为止，但后续「运河→海运→越南公路」要提前规划画出来，
      // 让司机在图上看到货物之后的完整去向。仅方案B显示，不参与导航播报与进度计算。
      // 走廊是固定几何，剪线头不改它——导航修剪重绘跳过
      if (!trimOnly) this._drawCorridor()

      // 起终点装饰（高德风）：终点🚩旗帜标记 + 红色虚线位移直线连接起终点，
      // 预览/导航都随路线重绘（绕行后终点不变，起点随新路线首点更新）
      // 终点旗帜/位移虚线只随整条路线变化，剪线头时跳过（导航中「起」气泡本就不画）
      if (!trimOnly) this._drawOdDecoration()
    },
    /**
     * 起终点装饰层：路线首点画绿色「起」气泡，末点画红色「🚩终」旗帜 + 目的地名称；
     * 起终点之间拉一条红色虚线（两点最短位移，不参与交互）。
     * 位移线放独立 pane（zIndex 350，在 overlayPane 路线之下），不与绿/红路线抢像素；
     * 标记用 divIcon 常驻标签同套路（不用 permanent Tooltip，避开 _updatePosition 空 _map 竞态）。
     */
    _drawOdDecoration() {
      if (!this.map) return
      if (this._odLayer) {
        try { this.map.removeLayer(this._odLayer) } catch (e) { /* 忽略 */ }
        this._odLayer = null
      }
      const pts = this.route.pathCoords || []
      if (pts.length < 2) return
      const start = pts[0]
      const end = pts[pts.length - 1]
      // 预览选定坐船：主线终点是六景港上船点，旗帜标签不再写行程目的地（如河内），避免图文不符
      const endName = this._canalCorridorActive() ? '南宁港六景·上船' : (this.destinationName || '')
      if (!this.map.getPane('odPane')) {
        const pane = this.map.createPane('odPane')
        pane.style.zIndex = 350
      }
      const layer = L.layerGroup()
      L.polyline([start, end], {
        pane: 'odPane', color: '#e53935', weight: 2.5, opacity: 0.9,
        dashArray: '10 8', interactive: false
      }).addTo(layer)
      // 导航中不画「起」气泡：已走路线被 _trimPassedRoute 实时剪掉，
      // 线首永远就是车标（融合式导航车标自带起点语义）；预览态照旧
      if (!this.navigating) {
        L.marker(start, {
          icon: L.divIcon({
            className: 'od-wrap', html: '<span class="od-bubble od-bubble-start">起</span>',
            iconSize: [0, 0], iconAnchor: [0, 0]
          }),
          interactive: false, keyboard: false
        }).addTo(layer)
      }
      L.marker(end, {
        icon: L.divIcon({
          className: 'od-wrap',
          html: `<span class="od-end-dot"></span><span class="od-bubble od-bubble-end">🚩 终</span><span class="od-end-name">${endName}</span>`,
          iconSize: [0, 0], iconAnchor: [0, 0]
        }),
        interactive: false, keyboard: false
      }).addTo(layer)
      layer.addTo(this.map)
      this._odLayer = layer
    },
    /** 拉取并缓存陆水联运后续走廊几何（一次会话一次请求，失败静默不影响导航） */
    async _ensureCorridorData() {
      if (this._corridorData || this._corridorFetching) return this._corridorData
      this._corridorFetching = true
      try {
        const { data } = await axios.get('/api/canal/corridor', { timeout: 15000 })
        this._corridorData = data
        return data
      } catch (e) {
        console.warn('陆水联运走廊总览拉取失败', e)
        return null
      } finally {
        this._corridorFetching = false
      }
    },
    /**
     * 绘制/清除「后续走廊」：运河蓝点线、海运青虚线、越南公路橙虚线，
     * 节点（六景/钦州港/海防/河内）用 divIcon 常驻标签（与口岸标记同套路，
     * 不用 permanent Tooltip——Tooltip._updatePosition 缺 _map 守卫是已知坑）；
     * 线体点开用 Popup（DivOverlay 里 Popup 带守卫，比 Tooltip 稳）。
     */
    _drawCorridor() {
      if (!this.map) return
      const corridorActive = this._canalCorridorActive()
      const d = corridorActive ? this._corridorData : null
      if (this._corridorLayer) {
        try { this.map.removeLayer(this._corridorLayer) } catch (e) { /* 忽略 */ }
        this._corridorLayer = null
      }
      // 首次进入上船方案（导航中方案B 或 预览选定坐船）：异步拉一次走廊几何，到手后补画（不阻塞本帧路线重绘）
      if (corridorActive && !this._corridorData && !this._corridorFetching) {
        this._ensureCorridorData().then(dd => {
          if (dd && this._canalCorridorActive()) this._drawCorridor()
        })
      }
      if (!d) return
      const styles = {
        canal: { color: '#0ea5e9', weight: 5, opacity: 0.9, dashArray: '2 10' },    // 内河运河：蓝点线
        sea: { color: '#06b6d4', weight: 4, opacity: 0.85, dashArray: '14 10' },    // 海运航线：青虚线
        road: { color: '#f59e0b', weight: 5, opacity: 0.9, dashArray: '10 8' }      // 越方公路：橙虚线
      }
      const layer = L.layerGroup()
      ;(d.legs || []).forEach(leg => {
        const path = leg.path || []
        if (path.length < 2) return
        const pl = L.polyline(path, styles[leg.mode] || styles.road)
        pl.bindPopup(`<b>${leg.name}</b><br>${leg.from} → ${leg.to} · ${leg.km}km / 约${leg.hours}h<br><span style="opacity:.75">${leg.note || ''}</span>`, { maxWidth: 260 })
        layer.addLayer(pl)
      })
      ;(d.nodes || []).forEach(n => {
        if (!n.pos) return
        layer.addLayer(L.marker(n.pos, {
          icon: L.divIcon({
            className: 'corridor-label-wrap',
            html: `<span class="corridor-label-text">${n.icon || ''} ${n.name}</span><span class="corridor-dot" style="background:${n.color || '#0ea5e9'}"></span>`,
            iconSize: [0, 0],
            iconAnchor: [0, 0]
          }),
          interactive: false,
          keyboard: false
        }))
      })
      layer.addTo(this.map)
      this._corridorLayer = layer
    },
    /**
     * risk-blink 的 JS 脉冲版：地图开 preferCanvas 后 routeLine/riskLine 的
     * getElement() 返回 null，原 CSS 类名（.risk-blink，0.8s ease-in-out）挂不上去。
     * 改为 400ms 定时器交替 setStyle(opacity)，视觉与原 CSS 等效。
     * route=整条高亮闪（预览高风险兜底），risk=红色光晕段闪，两者独立开关。
     */
    _setRiskBlink(routeBlink, riskBlink) {
      this._riskBlinkState = { route: !!routeBlink, risk: !!riskBlink }
      const need = routeBlink || riskBlink
      if (need && !this._riskBlinkTimer) {
        this._riskBlinkPhase = false
        this._applyRiskBlink()
        this._riskBlinkTimer = setInterval(() => {
          this._riskBlinkPhase = !this._riskBlinkPhase
          this._applyRiskBlink()
        }, 400)
      } else if (!need && this._riskBlinkTimer) {
        clearInterval(this._riskBlinkTimer)
        this._riskBlinkTimer = null
        this._riskBlinkPhase = false
        this._applyRiskBlink()
      }
    },
    /** 按当前相位写两条线的透明度；关灯时回到 drawRoute 设定的基线值（route 1 / risk 0.45） */
    _applyRiskBlink() {
      if (!this.routeLine || !this.riskLine) return
      const s = this._riskBlinkState || { route: false, risk: false }
      const on = !this._riskBlinkPhase
      if (s.route) this.routeLine.setStyle({ opacity: on ? 0.95 : 0.35 })
      else this.routeLine.setStyle({ opacity: 1 })
      if (s.risk) this.riskLine.setStyle({ opacity: on ? 0.45 : 0.15 })
      else this.riskLine.setStyle({ opacity: 0.45 })
    },
    /**
     * 当前路径上命中风险的边 ID 集合（导航用 riskSegments，预览用候选自带 riskEdgeIds）。
     *
     * 额外并入 this.risks：那是 SSE/轮询**刚收到**的风险清单，比 route.riskSegments 快一拍。
     * 接单模式已不再自动换线（换线权在调度中心），this.route 会长时间停在司机正在跑的
     * 那一条上，只读 route.riskSegments 就得等后端整条重算回来才变红——司机的眼睛比耳朵慢半拍。
     * riskEdgeSet 本身不过滤路径，真正判定「在不在本线上」是在 splitRouteByRisk 里按
     * pathEdgeIds 做的，所以把全网风险混进来也只会标红确实属于这条路线的那些边。
     */
    _riskEdgeSet() {
      return riskEdgeSet(
        [].concat(this.route.riskSegments || [], this.risks || []),
        this.route.riskEdgeIds
      )
    },
    /**
     * 把路径按「风险 / 安全」拆成连续多段（相邻同类边合并），并逐段按 zoom 塑形。
     * 拆段逻辑复用 utils/routeSegments.js，与大屏（MapView）保持一致；
     * Douglas-Peucker 会保留每段首尾点，因此段与段之间不会断开。
     */
    _buildColoredGroups(z, riskSet) {
      const rawCur = this.route.pathCoords || []
      return splitRouteByRisk(rawCur, this.route.pathEdgeIds, this.route.pathEdgeSpans, riskSet)
        .map(g => ({ risk: g.risk, pts: reshapeForZoom(g.pts, z, this.map) }))
    },
    /** 风险段叠加发光标记（按段采样打点，控制 marker 数量） */
    _updateRiskMarkers(riskPolys) {
      if (this._riskMarkers) {
        this._riskMarkers.forEach(m => { try { this.map.removeLayer(m); } catch (e) {} })
      }
      this._riskMarkers = []
      if (!riskPolys || !riskPolys.length) return
      const all = []
      riskPolys.forEach(poly => { (poly || []).forEach(p => all.push(p)) })
      if (!all.length) return
      const step = Math.max(1, Math.ceil(all.length / 24))
      for (let i = 0; i < all.length; i += step) {
        const m = L.circleMarker(all[i], {
          radius: 5,
          color: '#e94560',
          fillColor: '#e94560',
          fillOpacity: 0.6,
          weight: 2,
          opacity: 0.8,
          pane: 'markerPane',
          interactive: false
        }).addTo(this.map)
        this._riskMarkers.push(m)
      }
    },
    /** 定位到司机当前位置，使用导航三角标（计划书 4.4「显示当前位置」） */
    locateMe() {
      // 导航/预览/停驶期：车辆位置以路线模拟为准（与 GPS 回调同一守门），按钮退化为
      // 「回到车辆视角」。否则浏览器定位把车标摆到真实位置（市区湖泊/办公楼），
      // 下一次轮询/换线拼接就从车位斜着拉几公里弦线穿楼接回路线。
      if (this.navigating || this.previewing || this.waitHold) {
        this.zoomToNav()
        return
      }
      if (!navigator.geolocation) {
        this.showPush('info', '定位', '当前设备不支持定位功能')
        return
      }
      navigator.geolocation.getCurrentPosition(
        pos => {
          const lat = pos.coords.latitude
          const lng = pos.coords.longitude
          if (!this.map) return
          this.map.setView([lat, lng], 12, { animate: true })
          this._placeTriMarker(lat, lng)
          this.showPush('success', '定位成功', `当前位置 ${lat.toFixed(3)}, ${lng.toFixed(3)}`)
        },
        () => { /* HTTP 下 GPS 不可用，静默走模拟行驶 */ },
        { enableHighAccuracy: true, timeout: 8000 }
      )
    },
    /** 在指定坐标放置导航三角标（类似高德/百度导航的蓝色方向箭头） */
    _placeTriMarker(lat, lng) {
      if (!this.map) return
      if (!this.map.getPane('carPane')) {
        const cp = this.map.createPane('carPane')
        cp.style.zIndex = 640
      }
      if (this._meMarker) {
        try { this.map.removeLayer(this._meMarker) } catch (e) {}
        this._meMarker = null
      }
      const icon = L.divIcon({
        className: 'nav-tri-marker car-smooth',
        // 融合式导航车标（参照高德）：罗盘双圈 + 行进方向蓝色扇形光带 + 白底蓝箭头。
        // 导航中不再另画「起」气泡（_drawOdDecoration 按 navigating 跳过），
        // 已走路线由 _trimPassedRoute 实时剪短——车标就是移动的起点。
        // 朝向由 .nav-car-rot 的 transform 实时更新（见 _updateCarBearing，直接改 DOM 不重建图标）。
        html: '<div class="nav-car">' +
          '<div class="nav-car-ring"></div><div class="nav-car-ring2"></div>' +
          // 罗盘方位字：固定在圈上随圈不动（不放进 rot 层），参照高德指南针样式
          '<div class="nav-car-dir nav-car-dir-n">北</div>' +
          '<div class="nav-car-dir nav-car-dir-e">东</div>' +
          '<div class="nav-car-dir nav-car-dir-s">南</div>' +
          '<div class="nav-car-dir nav-car-dir-w">西</div>' +
          // 层级自下而上：扇形→箭头，全部放进 rot 层一起转向（无白圆底，箭头自带白描边）
          '<div class="nav-car-rot"><div class="nav-car-fan"></div>' +
          '<svg class="nav-car-arrow" viewBox="0 0 24 24" aria-hidden="true">' +
          '<path fill="#2b6ef3" stroke="#fff" stroke-width="1.5" stroke-linejoin="round" d="M12 3.2 19.4 20 12 16.1 4.6 20Z"/>' +
          '</svg></div></div>',
        iconSize: [130, 130],
        iconAnchor: [65, 65]
      })
      this._meMarker = L.marker([lat, lng], {
        icon,
        // 独立 carPane（z640）：routeFlow 高亮绿线也画在 markerPane，线头修剪到车标位置后
        // 会把同 pane 里的箭头/圆圈盖住（实测放大截图：绿线压在箭头/圈上）；
        // 提层后车标永远全图最顶（高于 markerPane 600、低于 tooltip 650 不挡弹层）
        pane: 'carPane',
        zIndexOffset: 1000,
        interactive: false
      }).addTo(this.map)
      // 新 DOM：旋转层缓存与「已关过渡」标记都很随旧元素一起作废
      this._carRotEl = null
      this._visEl = null
      // 落图时车就在这个位置：把插值起点同步为它，避免第一帧从旧位置滑过来
      this._visPos = [lat, lng]
      // 落图后立即补一次朝向（新 DOM 默认指北，不补会先竖着再转正）
      this._updateCarBearing()
    },
    /** 当前行驶方位角（度，正北=0 顺时针）：取模拟里程所在线段的方向 */
    _simBearing() {
      const coords = this.route && this.route.pathCoords
      const cum = this._simCum
      if (!coords || !cum || coords.length < 2 || cum.length !== coords.length) return 0
      const d = this._simDistM || 0
      let lo = 0, hi = cum.length - 1
      while (lo < hi - 1) {
        const mid = (lo + hi) >> 1
        if (cum[mid] <= d) lo = mid; else hi = mid
      }
      const a = coords[lo], b = coords[hi]
      if (!a || !b || a.length < 2 || b.length < 2) return 0
      const y = (b[1] - a[1]) * Math.cos(a[0] * Math.PI / 180)
      const x = b[0] - a[0]
      if (!y && !x) return 0
      return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360
    },
    /** 把车标扇形/箭头转到当前行驶方向：直接改 DOM transform，避开逐 tick setIcon 重建 */
    _updateCarBearing() {
      try {
        if (!this._meMarker) return
        // 60fps 每帧调用：旋转层缓存一次，别每帧穿透查询（元素被重建时 isConnected 自然失效）
        let rot = this._carRotEl
        if (!rot || rot.isConnected === false) {
          const el = this._meMarker.getElement && this._meMarker.getElement()
          rot = el && el.querySelector && el.querySelector('.nav-car-rot')
          this._carRotEl = rot || null
        }
        if (rot) rot.style.transform = 'rotate(' + this._simBearing().toFixed(1) + 'deg)'
      } catch (e) { /* 视觉增强，失败不影响导航 */ }
    },
    /**
     * 实时剪短已走过的路线：导航中每前进 ~40m 就把「起点→车当前位置」段从
     * route 里真实剪掉（coords/edgeIds/spans/nodeIds 同步位移，里程/耗时同步扣减，
     * 与 _stitchRouteFromCar 截断分支同一套口径），并把模拟里程表归零重建筑——
     * 视觉上路线头部跟着车标实时消失，车标永远钉在剩余路线的首点（=移动的起点）。
     * 注意：就地修改 this.route 不换引用，route watcher（deep:false）不会触发，
     * 必须手动 drawRoute 重绘（内部会连带刷新起终点装饰与风险着色）。
     */
    _trimPassedRoute() {
      if (!this.navigating) return
      const r = this.route
      const coords = r && r.pathCoords
      const cum = this._simCum
      if (!coords || coords.length < 2 || !cum || cum.length !== coords.length) return
      const d = this._simDistM || 0
      // 修剪阈值随模拟速度缩放（保持 ~1.6s 一次全量重绘的节奏，避免提速后重绘风暴）；
      // 两次修剪之间的视觉尾巴由 tick 里的 _tetherRouteHead 零帧钉在车标上兜底，
      // 阈值放大不影响「线头贴车标」的观感
      if (d < SIM_SPEED_MPS * 1.6) return
      let lo = 0, hi = cum.length - 1
      while (lo < hi - 1) {
        const mid = (lo + hi) >> 1
        if (cum[mid] <= d) lo = mid; else hi = mid
      }
      // 接近终点：剩余不足两个点就不剪，避免把线剪空
      if (hi >= coords.length - 1) return
      const a = coords[lo], b = coords[hi]
      const seg = cum[hi] - cum[lo]
      const f = seg > 0 ? Math.min(1, (d - cum[lo]) / seg) : 0
      const p = [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f]
      const spans = r.pathEdgeSpans || []
      // 车在 coords[lo]→coords[hi] 段内（二分收敛后 hi=lo+1）：cum[lo]≤d<cum[hi]，
      // 即 coords[lo] 已驶过、coords[hi] 还在车前方。剩余路线应是 [p, coords[hi], coords[hi+1], ...]
      // ——只把已过的 coords[lo] 换成车位 p 作新线首点，前方顶点 coords[hi] 必须保留。
      // 旧写法 coords.slice(hi) 又 newCoords[0]=p 把 coords[hi] 覆盖掉，等于连前方那个转角顶点
      // 一起丢了，线从 p 直连 coords[hi+1]：coords[hi] 若正好是绕开某街区的拐弯，这条 p→coords[hi+1]
      // 就是切角的斜弦。车每前进一次修剪就吞掉前方一个转角，表现为「车一直朝折角走、折角却一直
      // 往前跑，线把角切掉穿进建筑物」。改为前插 p 并保留 coords[hi]（与 _stitchRouteFromCar 截断分支同口径）。
      let eIdx = spans.findIndex(sp => sp && sp[1] >= hi)
      if (eIdx < 0) eIdx = 0
      const newCoords = [p].concat(coords.slice(hi).map(c => [c[0], c[1]]))
      r.pathCoords = newCoords
      r.pathEdgeIds = (r.pathEdgeIds || []).slice(eIdx)
      // 下标位移 shift=hi-1：new[k]=coords[hi+k-1]（k≥1），new[0]=p 落在车当前所在边内；
      // eIdx 取首个末端到达 coords[hi] 的边（车当前所在边），其 [lo,hi] 段裁后映射为 [0,1]
      r.pathEdgeSpans = spans.slice(eIdx).map(sp => [Math.max(0, sp[0] - (hi - 1)), sp[1] - (hi - 1)])
      r.pathNodeIds = (r.pathNodeIds || []).slice(eIdx)
      this._adjustRouteMetrics(r, -d / 1000)
      // 里程表归零重建：车就在新线首点，继续从这里往前推进/截断
      const cum2 = [0]
      for (let i = 1; i < newCoords.length; i++) cum2.push(cum2[i - 1] + this._distM(newCoords[i - 1], newCoords[i]))
      this._simCum = cum2
      this._simTotalM = cum2[cum2.length - 1]
      this._simDistM = 0
      this._triIdx = 0
      // 修剪重绘走轻量路径：主线/风险线/特效层照常更新，跳过终点装饰/走廊/风险打点的拆除重建
      this.drawRoute({ trimOnly: true })
    },
    /**
     * 零帧同步：每个模拟 tick（100ms）把「已绘制路线图层」的首点直接钉到车当前位置。
     * 数据层修剪（_trimPassedRoute，40m 一次全量重绘）负责里程/风险/ETA 对齐，
     * 但两次修剪之间车标连续走、线头不动，高缩放下就是用户看到的「小尾巴」。
     * 这里只改首点坐标 + setLatLngs 重建 path（不重建图层/不重跑 DP/不重排箭头），
     * 每 tick 成本微秒级——视觉线头与车标彻底重合，尾巴归零。
     */
    _tetherRouteHead(p) {
      if (!this.navigating) return
      const head = [p[0], p[1]]
      /*
       * 结构不变量（对照未改动过的交付版本：它从不改线首，画出来的每个点都是后端数据点，
       * 所以永远不会出现「不是路面」的线段）。改线首是唯一会往折线里塞非数据点的动作。
       * 旧护栏「车到首段线段垂距 ≤25m」有漏洞：轮询换线后首段是「车标→新线第二个顶点」
       * 的几百米长弦，旧线平行紧贴新线时车到这条弦的垂距天然很小、判据放行，线头被拽到
       * 路外的车标上，整根首段变成斜穿建筑物的斜弦（用户反馈「车前方一段总是斜着、还会
       * 穿建筑物」真凶）。新护栏改成「线头只能沿首段滑动」：取车标在首段上的线性插值位置、
       * 并限制在 [线头, 第二点] 之间——线头永远落在折线上，斜弦在结构上不可能再出现。
       */
      const lp = this._routeLinePts
      const arr = lp && lp[0]
      let tetherPt = null
      if (arr && arr.length > 1) {
        if (this._distM(arr[0], head) <= 200 && this._pointSegDistM(head, arr[0], arr[1]) <= 25) {
          const cosLat = Math.max(0.2, Math.cos(head[0] * Math.PI / 180))
          const ax = arr[0][0], ay = arr[0][1] * cosLat
          const dx = arr[1][0] - ax, dy = arr[1][1] * cosLat - ay
          const l2 = dx * dx + dy * dy
          let t = l2 > 0 ? ((head[0] - ax) * dx + (head[1] * cosLat - ay) * dy) / l2 : 0
          t = t < 0 ? 0 : (t > 1 ? 1 : t)
          const slid = [ax + dx * t, (ay + dy * t) / cosLat]
          // 只准沿线前进：滑动点必须比旧线头更靠近第二点（倒退说明车标异常，不拽）
          if (this._distM(slid, arr[1]) < this._distM(arr[0], arr[1])) tetherPt = slid
        }
      } else {
        const c0 = this.route && this.route.pathCoords && this.route.pathCoords[0]
        tetherPt = (c0 && this._distM(c0, head) <= 60) ? head : null
      }
      if (!tetherPt) return
      // 高德风主线（routeFlow：描边/光晕/流光/核心 4 层 + 线首脉冲点）
      try { this._routeFlow && this._routeFlow.tetherHead && this._routeFlow.tetherHead(tetherPt) } catch (e) { /* 特效层异常不影响导航 */ }
      // 底层兜底线：安全时第一组、风险时整条 cur()，首点都是线首
      if (arr && arr.length && this.routeLine) {
        arr[0] = tetherPt
        this.routeLine.setLatLngs(lp)
      }
      // 风险光晕：只有风险段真的从线首起才绑（否则会把中段风险段的起点拽歪）
      const rp = this._riskLinePts
      if (rp && rp[0] && rp[0].length && this.riskLine) {
        const c0 = this.route.pathCoords && this.route.pathCoords[0]
        if (c0 && this._distM(rp[0][0], c0) < 80) {
          rp[0][0] = tetherPt
          this.riskLine.setLatLngs(rp)
        }
      }
    },
    /** 点 p 到线段 ab 的最短距离（米）：用于判定车标是否真的落在线首那一段路面上 */
    _pointSegDistM(p, a, b) {
      // 统一投到局部平面再算：北向=纬度差、东向=经度差×cos(纬度)，
      // 两个分量都还是「度」，最后整体 ×111320 折成米。
      // 早期写法在东向分量上先乘了一次 cos、取残差时又除了一次，
      // 25m 阈值下会偏几米，判据就不可信了。
      const cosLat = Math.max(0.2, Math.cos(p[0] * Math.PI / 180))
      const px = p[0], py = p[1] * cosLat
      const ax = a[0], ay = a[1] * cosLat
      const dx = b[0] - ax, dy = b[1] * cosLat - ay
      const len2 = dx * dx + dy * dy
      let t = len2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0
      t = t < 0 ? 0 : (t > 1 ? 1 : t)
      return Math.hypot(px - ax - dx * t, py - ay - dy * t) * 111320
    },
    /** 进首页即弹一次定位授权框（仅请求权限，不启动持续定位；失败/非原生环境静默忽略） */
    async _requestLocationOnLaunch() {
      try {
        const perm = await Geolocation.checkPermissions()
        if (perm.location === 'granted') return
        await Geolocation.requestPermissions()
      } catch (e) { /* 插件不可用或浏览器无该能力，忽略，不影响后续「开始导航」时的定位流程 */ }
    },
    /** 开启 GPS 实时定位，Capacitor 原生定位优先，降级浏览器 geolocation，再降级模拟行驶 */
    _startRealTimeGPS() {
      // 停驶等待中不得重开定位/模拟（恢复行驶走 resumeFromHold）
      if (this.waitHold) return
      this._stopRealTimeGPS()
      const coords = this.route.pathCoords
      if (coords && coords.length > 0) {
        this._placeTriMarker(coords[0][0], coords[0][1])
      }
      // 车辆位置**从模拟起点（路线首点）开始**：先把模拟行驶起起来，位置就锚定在路线起点，
      // 随后沿路线推进。真实定位仍然探测（能力保留、权限与状态流程不变），
      // 但模拟一旦在跑，定位结果不再移动车辆与视野（见两个回调里的 _triTimer 判断），
      // 避免开着代理时定位到国外、或定位到办公室把地图拽离路线。
      if (this.navigating) this._startTripSimulation()
      this._tryCapacitorGPS(coords)
    },
    async _tryCapacitorGPS(coords) {
      // 停驶等待中直接退出：本方法在 await 权限后才注册 watchPosition，
      // 若只在 _applyWaitHold 里清旧监听，这个迟到的回调仍会把车标拽到真实定位点
      if (this.waitHold) return
      this.gpsStatus = 'searching'
      try {
        // 检查权限
        const perm = await Geolocation.checkPermissions()
        if (perm.location !== 'granted') {
          const req = await Geolocation.requestPermissions()
          if (req.location !== 'granted') {
            this._tryBrowserGPS(coords)
            return
          }
        }
        this._gpsWatchId = Geolocation.watchPosition(
          { enableHighAccuracy: true, timeout: 15000, maximumAge: 5000 },
          (pos, err) => {
            if (err || !pos) return
            // Capacitor Geolocation（含其 Web 实现）返回的是 W3C 结构：{ coords: { latitude, longitude }, timestamp }。
            // 原来读的是 pos.latitude/pos.longitude → 恒为 undefined →
            // marker.setLatLng([undefined, undefined]) 抛 "Invalid LatLng object"，
            // 而且跟随/落点全部失效。这里按标准结构取，并保留扁平结构的兜底。
            const c = pos.coords || pos
            const lat = c.latitude
            const lng = c.longitude
            if (typeof lat !== 'number' || typeof lng !== 'number') return
            // ① 车辆位置以路线/模拟为准：模拟在跑、导航中、或调度预览期（_enterDispatchPreview
            // 先 _stopRealTimeGPS 停了模拟，但 watchPosition 还在推）一律不许定位动车——
            // 「切换后车标从金湖广场跳到仙葫公园」真凶：预览窗口里真实定位把车标拽到
            // 电脑实际位置，确认时 _autoStartDispatchNav 又拿这个污染位置当 keepPos 重锚。
            if (this._triTimer || this.navigating || this.previewing) return
            // ② 停驶等待中：真实定位同样不许动车（车标只能停在等待位置）
            if (this.waitHold) return
            // ③ 模拟没跑时（非导航态）仍做合理性校验，避免代理定位到国外把视野拽走
            if (!this._isNearRoute(lat, lng)) return
            this.gpsStatus = 'locked'
            if (this._meMarker) {
              this._meMarker.setLatLng([lat, lng])
            } else {
              this._placeTriMarker(lat, lng)
            }
            this._followVehicle([lat, lng])
          }
        )
        // 3 秒后仍未收到位置则降级
        const checkTimer = setTimeout(() => {
          if (this.gpsStatus === 'searching') {
            this._tryBrowserGPS(coords)
          }
        }, 3000)
        this._gpsCheckTimer = checkTimer
      } catch (e) {
        this._tryBrowserGPS(coords)
      }
    },
    /**
     * 定位点是否落在当前路线走廊附近（用于过滤代理/VPN 导致的错误定位）。
     *
     * 开了代理时，浏览器定位是基于**出口 IP** 的，可能报到国外（实测报到日本）；
     * 一旦采信就会把车辆标记和地图视野一起拽走 —— 表现就是"点开始导航跑到日本去了"。
     * 路线未知时不限制（返回 true），避免误杀正常定位。
     */
    _isNearRoute(lat, lng) {
      const pc = this.route && this.route.pathCoords
      if (!pc || pc.length < 2) return true
      const b = L.latLngBounds(pc)
      // 容差约 50km：纬度 0.45°，经度按当前纬度折算
      const padLat = 0.45
      const padLng = 0.45 / Math.max(0.2, Math.cos(lat * Math.PI / 180))
      return lat >= b.getSouth() - padLat && lat <= b.getNorth() + padLat
        && lng >= b.getWest() - padLng && lng <= b.getEast() + padLng
    },
    _tryBrowserGPS(coords) {
      // 停驶等待中不重开定位（同上：本方法可能由已排队超时的定时器迟到拉起）
      if (this.waitHold) return
      if (!navigator.geolocation) {
        this.gpsStatus = 'unavailable'
        this.showPush('info', 'GPS 不可用', '浏览器不支持定位，已切换模拟行驶')
        if (this.navigating) this._startTripSimulation()
        return
      }
      this.gpsStatus = 'searching'
      let gpsReceived = false
      this._gpsWatchId = navigator.geolocation.watchPosition(
        pos => {
          const lat = pos.coords.latitude
          const lng = pos.coords.longitude
          if (typeof lat !== 'number' || typeof lng !== 'number') return
          // ① 同 Capacitor 分支：模拟/导航/预览期定位冻结车标（预览期停模拟后
          // watchPosition 仍会推送，把车标拽到电脑实际位置——起点大跳真凶）
          if (this._triTimer || this.navigating || this.previewing) return
          // ② 停驶等待中：真实定位同样不许动车
          if (this.waitHold) return
          // ③ 模拟没跑时（非导航态）仍做合理性校验，避免代理定位到国外把视野拽走
          if (!this._isNearRoute(lat, lng)) return
          gpsReceived = true
          this.gpsStatus = 'locked'
          if (this._meMarker) {
            this._meMarker.setLatLng([lat, lng])
          } else {
            this._placeTriMarker(lat, lng)
          }
          this._followVehicle([lat, lng])
        },
        (err) => {
          // 不立即报错：watchPosition 可能因权限问题报 PERMISSION_DENIED
          if (err && err.code === 1) {
            // PERMISSION_DENIED: 用户拒绝了，不再重试
            this.gpsStatus = 'unavailable'
            this.showPush('info', 'GPS 未授权', '请在系统设置中开启定位权限')
          }
          if (!gpsReceived && this.navigating) {
            this._startTripSimulation()
          }
        },
        { enableHighAccuracy: true, timeout: 15000, maximumAge: 5000 }
      )
      // 5 秒后仍未收到位置则标记不可用
      const checkTimer = setTimeout(() => {
        if (this.gpsStatus === 'searching') {
          this.gpsStatus = 'unavailable'
          if (!gpsReceived && this.navigating) {
            this._startTripSimulation()
          }
        }
      }, 5000)
      this._gpsCheckTimer = checkTimer
    },
    /** GPS 不可用时回退：沿路线逐步前移三角标 */
    _startTripSimulation() {
      // **已经在跑就直接返回**：原来每次进来都先停再起、把车辆挪回路线起点、进度归零，
      // 而 GPS 探测失败路径（_tryBrowserGPS 的错误分支）也会调它 ——
      // 于是"跑了几秒又被拉回起点再跳一次"，表现为连跳几秒才回来。
      if (this._triTimer) return
      // 停驶等待中：任何入口（GPS 降级重试、底图重建恢复）都不准把车重新开起来
      if (this.waitHold) return
      const coords = this.route.pathCoords
      if (!coords || coords.length < 2) return
      this.gpsStatus = 'simulating'
      // 车辆从**模拟起点**（路线首点）出发
      this._placeTriMarker(coords[0][0], coords[0][1])
      // 按累计里程平滑推进：路线上相邻点平均可达 1.3km，按点跳在导航级缩放(z19)下
      // 每次平移上千像素，看起来就是"一秒一跳"。改为每 100ms 前进 速度×0.1s 并插值。
      const cum = [0]
      for (let i = 1; i < coords.length; i++) {
        cum.push(cum[i - 1] + this._distM(coords[i - 1], coords[i]))
      }
      this._simCum = cum
      this._simTotalM = cum[cum.length - 1]
      this._simDistM = 0
      this._runTripTimer()
    },
    /**
     * 从**当前进度**（_simDistM）继续模拟行驶，不重置里程、不把车辆拉回起点。
     * 底图跨坐标系切换会整图重建、销毁 _meMarker；重建后用它把车辆原地恢复，
     * 表现为"切底图后车辆仍在原位继续前进"，而不是消失或跳回起点。
     */
    _resumeTripSimulation() {
      if (this._triTimer) return
      if (this.waitHold) return
      const coords = this.route.pathCoords
      // 进度/累计里程缺失（异常路径）时退回从起点重新开始
      if (!coords || coords.length < 2 || !this._simCum) { this._startTripSimulation(); return }
      this.gpsStatus = 'simulating'
      const p = this._simPointAt(this._simDistM || 0)
      if (p) this._placeTriMarker(p[0], p[1])
      this._runTripTimer()
    },
    /** 模拟行驶推进定时器：_startTripSimulation 从头起、_resumeTripSimulation 续跑，共用同一段逻辑 */
    _runTripTimer() {
      this._triTimer = setInterval(() => {
        if (!this.navigating || this.waitHold) { this._stopTripSimulation(); return }
        this._simDistM = Math.min(this._simDistM + SIM_SPEED_MPS * SIM_TICK_MS / 1000, this._simTotalM)
        let p = this._simPointAt(this._simDistM)
        if (!p) {
          // 路线刚被替换、里程表与新 coords 长度不一致（_simPointAt 会返回 null）：
          // 不处理就会让车标原地冻住——就地重标定一次再取点。
          this._syncTripProgress()
          p = this._simPointAt(this._simDistM)
        }
        if (p && this.map) {
          // 车标显示位置吸附到「已绘制的路线线」上：p 是按原始 pathCoords 里程插值得到的，
          // 而屏幕上那条线是 reshapeForZoom(pathCoords)（抽稀 + 去尖刺后）的结果，二者在
          // 被简化掉的弯道上会差出几十米——纯模拟行驶没有真实 GPS，车标理应严格压在你看到的
          // 这条线上，而不是漂在路面外的原始顶点连线上（用户反馈「行驶中途车标偶尔不贴着路线走」）。
          const dp = this._snapToDrawnLine(p)
          // 车辆标记就地自愈：整图重建（底图降级 / 健康检查硬重建 / 容器失效重建）会走
          // _destroyMap 把 _meMarker 置空、却不停本定时器，导致重建后 if(_meMarker) 恒假、
          // 车标永远画不出来 —— 表现就是「开一会儿 / 缩放拖动一下 GPS 标记就消失了」。
          // 只要发现标记不在当前地图上（null 或已脱离本 map），就用当前里程坐标重新落一枚。
          if (!this._meMarker || this._meMarker._map !== this.map) {
            this._placeTriMarker(dp[0], dp[1])
          }
          // 车标位置 / 朝向 / 相机跟随改由 rAF 里的 _paintVehicle 按时间插值绘制（60fps）：
          // 旧写法在这里 setLatLng + _centerOn(...,0.6)，而 Leaflet 的 setView 每次都先打断
          // 上一段缓动再加速重起 → 相机每秒「冲刺-减速」10 次，与车标的线性补间不同步，
          // 屏幕上车标与底图就是「一帧一帧」。这里只把本段区间交给绘制循环
          this._setVisSegment(dp)
          // 零帧同步：线首几何每 tick 钉到车位置，两次数据修剪之间不再有尾巴
          this._tetherRouteHead(p)
        }
        // 数据层实时剪短已走过的路线（每 ~40m 一次全量重绘；tick 里的 _tetherRouteHead
        // 负责两次修剪之间把线首几何零帧钉在车标上，视觉无尾巴）
        this._trimPassedRoute()
        // 周期把行程进度报给后端：大屏方案生成需要知道车开到哪了，
        // 否则车已开过南宁港还会被推荐"陆水联运=掉头回港口"
        this._reportTripProgress()
        // ETA 随实时速度动态刷新：进度和已用时都在变，剩余时间要跟着修正
        this._updateLiveEta()
        if (this._simDistM >= this._simTotalM) this._stopTripSimulation()
      }, SIM_TICK_MS)
    },
    /** 相邻两点的球面距离（米） */
    _distM(a, b) {
      const R = 6371000, rad = Math.PI / 180
      const dLat = (b[0] - a[0]) * rad, dLng = (b[1] - a[1]) * rad
      const la1 = a[0] * rad, la2 = b[0] * rad
      const h = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLng / 2) ** 2
      return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)))
    },
    /** 沿路线累计里程 dist 处的插值坐标（二分定位所在线段后线性插值） */
    _simPointAt(dist) {
      const coords = this.route.pathCoords
      const cum = this._simCum
      if (!coords || !cum || coords.length < 2 || cum.length !== coords.length) return null
      let lo = 0, hi = cum.length - 1
      while (lo < hi - 1) {
        const mid = (lo + hi) >> 1
        if (cum[mid] <= dist) lo = mid; else hi = mid
      }
      const seg = cum[hi] - cum[lo]
      const f = seg > 0 ? (dist - cum[lo]) / seg : 0
      const a = coords[lo], b = coords[hi]
      return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f]
    },
    /**
     * 把模拟里程取到的点投影到「已绘制的路线折线」(_routeLinePts) 上。
     * 绘制线 = reshapeForZoom(pathCoords)，与车所依据的原始 pathCoords 在被抽稀/去尖刺
     * 的弯道上会有几十米偏差；投影让车标始终压在你实际看到的那条绿线上。投影点超过 60m
     * 视为绘制线尚未跟上（换线/重绘瞬间），退回原始点，避免吸附到尚未更新的旧线。
     */
    _snapToDrawnLine(p) {
      const lp = this._routeLinePts
      if (!p || !lp || !lp.length) return p
      let best = null, bestD = Infinity
      for (const grp of lp) {
        if (!grp || grp.length < 2) continue
        const near = this._nearestOnPolyline(grp, { lat: p[0], lng: p[1] })
        if (near && near.distM < bestD) { bestD = near.distM; best = near.proj }
      }
      return (best && bestD <= 60) ? best : p
    },
    _stopRealTimeGPS() {
      if (this._gpsCheckTimer) { clearTimeout(this._gpsCheckTimer); this._gpsCheckTimer = null }
      if (this._gpsWatchId != null) {
        // Capacitor watchPosition 返回 string callbackId，浏览器返回 number
        try {
          if (typeof this._gpsWatchId === 'string') {
            Geolocation.clearWatch({ id: this._gpsWatchId }).catch(() => {})
          } else {
            navigator.geolocation.clearWatch(this._gpsWatchId)
          }
        } catch (e) { /* ignore */ }
        this._gpsWatchId = null
      }
      this._stopTripSimulation()
    },
    _stopTripSimulation() {
      if (this._triTimer) { clearInterval(this._triTimer); this._triTimer = null }
      this._stopVisLoop()
    },
    /**
     * 执行调度方案 C「原地等待」：车辆必须真的停下来。
     *
     * 仅靠 UI 上写一行「请就近停靠」不算执行：模拟行驶定时器仍在每秒推进里程，
     * 车标与视角照旧沿路线前进。这里停掉 GPS 探测与推进定时器、把车钉在当前里程位置，
     * 并由 waitHold 拦住后续所有会重新起模拟的入口（GPS 不可用降级、底图跨坐标系重建
     * 时的 _resumeTripSimulation、健康检查硬重建），否则会看到"停一下又自己动起来"。
     */
    _applyWaitHold() {
      if (this.waitHold) return
      this.waitHold = true
      this._stopRealTimeGPS()
      this.gpsStatus = 'hold'
      // 车辆留在当前位置；若整图重建把标记弄丢了，用当前里程坐标补一枚（不回路线起点）
      if (this.map && (!this._meMarker || this._meMarker._map !== this.map)) {
        const p = this._simPointAt(this._simDistM || 0)
          || (this.route.pathCoords && this.route.pathCoords[0])
        if (p) this._placeTriMarker(p[0], p[1])
      }
      this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        text: '执行调度方案 C：原地等待，车辆停驶（模拟行驶已暂停）' })
      if (this.voiceOn) this.speakQueue(['已按调度指令停驶等待，车辆位置保持不变，请保持冷链机组运行。'])
    },
    /** 解除停驶：从停住的那个里程继续前进（不回起点、不重置进度） */
    _releaseWaitHold() {
      if (!this.waitHold) return false
      this.waitHold = false
      if (this.navigating) this._resumeTripSimulation()
      else this.gpsStatus = 'unavailable'
      return true
    },
    /** 「恢复行驶」入口：等待到点 / 风险解除后由司机手动继续 */
    resumeFromHold() {
      if (!this._releaseWaitHold()) return
      this.showPush('ok', '已恢复行驶', '车辆从当前位置沿当前路线继续前进。')
      this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        text: '司机解除原地等待，恢复行驶' })
      if (this.voiceOn) this.speakQueue(['原地等待结束，已恢复行驶，请按导航继续前进。'])
    },
    /**
     * 实时速度驱动的「剩余时间」估计——预计时间不再钉死在出发时刻。
     *
     * 思路：一开始几乎没进度，只能按全程计划耗时给个大概预测；随着车辆真的往前开，
     * 用「已用时 ÷ 已行驶比例」外推实测平均速度，据此重算剩余时间——开得比计划快 ETA 提前、
     * 遇堵/绕行顺延。起步阶段样本小、瞬时外推抖动大，故按进度线性加权，约 15% 进度后完全采信实测速度。
     * 注意：这是「实时速度」而非「计划速度」——estimatedHours 只用于起步粗估与按里程等比缩放。
     */
    _updateLiveEta(force) {
      const now = Date.now()
      // 模拟 tick 每 100ms 调一次，这里限流到约每秒刷新一次（ETA 只到分钟粒度，无需 10Hz）
      if (!force && this._etaCalcAt && now - this._etaCalcAt < 1000) return
      this._etaCalcAt = now
      const planned = this.route && this.route.estimatedHours
      if (!this.navigating || !planned) { this.navRemainHours = null; return }
      const total = this._simTotalM || 0
      const startedAt = this.navigationStartedAt || 0
      // 里程表还没标定（刚点开始导航、模拟尚未起来）：先按全程计划耗时粗估
      if (!total || !startedAt) { this.navRemainHours = planned; return }
      const p = Math.max(0, Math.min(1, (this._simDistM || 0) / total))
      // 剩余里程按原计划等比缩放：这是任何时刻都成立的「大概预测」基线
      const byPlan = planned * (1 - p)
      const elapsedH = (now - startedAt) / 3600000
      let remain = byPlan
      // 已走完一小段且用时可信时，叠加实测平均速度外推（这才是「实时」部分）
      if (p > 0.02 && elapsedH > 0) {
        const bySpeed = elapsedH * (1 - p) / p
        const w = Math.min(1, p / 0.15)
        remain = byPlan * (1 - w) + bySpeed * w
      }
      this.navRemainHours = remain
    },
    /** 导航中每 ~30 秒上报一次行程进度（模拟行驶与真实 GPS 共用的位置变化都会经过这里） */
    _reportTripProgress() {
      if (!this.navigating || !this._simTotalM) return
      const now = Date.now()
      if (this._lastProgressReportAt && now - this._lastProgressReportAt < 30000) return
      this._lastProgressReportAt = now
      const ratio = Math.max(0, Math.min(1, (this._simDistM || 0) / this._simTotalM))
      axios.post('/api/trip/progress', null, { params: { ratio: ratio.toFixed(3) } }).catch(() => {})
    },
    fitRoute(silent) {
      // 行驶中点「全览路线」＝主动接管视角，暂停跟随并给出回到导航视角入口
      // silent=true 用于导航开场自动全览，不算用户接管，保持跟随状态
      if (this.navigating && silent !== true) this._pauseFollow()
      if (!this.map || !this.route.pathCoords || !this.route.pathCoords.length) return
      // 导航中一律拒绝「静默全览」（开场 fitRoute(true) / 健康检查 _recoverNavMap / resize / 重绘
      // 兜底触发的 fitRoute(true)）。这些带动画的 fitBounds 会把 zoomToNav 刚拉到的最大跟车级
      // 又拽回整条路线的概览级（用户反馈「进导航放大到最大 → 往上走一段 → 又自动缩到最小」真凶）。
      // 关键：不能只在 _navZoomReady 后拦——健康检查 attempt0(250ms) 的 fitRoute(true) 比 zoomToNav
      // 更早触发，那时 _navZoomReady 仍为 false，只靠 ready 拦不住，且带动画的 fitBounds 会逐帧覆盖
      // 紧随其后的 setView。导航中跟车视角由 _followVehicle 每 tick 维持、开场由 zoomToNav 定位，
      // 无需静默全览兜底；用户主动点「全览路线」(silent!==true) 仍照常全览。
      if (silent === true && this.navigating) return
      // 顶部状态栏仍浮在地图上，按实测高度换算成上留白；预览为上下分栏、面板在地图之外，
      // 底部只需少量余量。路线因此完整落在状态栏以下的可视区里，不会被任何面板压住。
      const topPad = this._topOverlayPadding()
      const bottomPad = this._bottomOverlayPadding()
      // 极端情况（路线超出瓦片覆盖范围）先放宽最小缩放，保证整条路线可见
      this._applyMapMinZoom()
      try {
        this.map.fitBounds(L.latLngBounds(this.route.pathCoords), {
          paddingTopLeft: [22, topPad],
          paddingBottomRight: [22, bottomPad],
          animate: true
        })
      } catch (e) {
        // 同 _centerOn：监听器抛异常会中断 _resetView，补一次重投影把渲染器状态拉回一致
        this._vdbg('fitBounds 抛出：' + ((e && e.message) || e))
        this._resyncMapRenderer()
      }
    },
    /** 量取顶部状态栏高度，作为路线适配的上方留白 */
    _topOverlayPadding() {
      const el = this.$refs.topStack
      const h = el ? el.offsetHeight : 0
      return Math.max(8, (h || 60) + 8)
    },
    /**
     * 量取底部留白，作为路线适配的下方留白。
     * - 预览为「上下分栏」：面板在地图之外，不再压住地图，只留一点视觉余量；
     * - 导航模式面板仍浮在地图底部，按实测高度（排除转瞬即逝的推送条）取景。
     */
    _bottomOverlayPadding() {
      if (this.previewing) return 16
      const el = this.$refs.bottomStack
      const h = el ? el.offsetHeight : 0
      // 推送条是转瞬即逝的 toast，不参与取景：否则它出现/消失会把相机带着缩进缩出，
      // 而且消失后地图会停在缩小状态留下灰边。只按常驻面板（ETA 卡片）取景。
      const push = this.$refs.pushBar
      const pushH = push ? push.offsetHeight + 8 : 0
      return Math.max(24, ((h || 160) - pushH) + 12)
    },
    /**
     * 收起 / 展开候选路线列表。上下分栏下列表高度变化会改变地图可视区域，
     * ResizeObserver 会触发 invalidateSize，Leaflet 的 resize 事件里再重新适配一次路线。
     */
    togglePreviewCollapsed() {
      this.previewCollapsed = !this.previewCollapsed
      this.$nextTick(() => this.fitRoute())
    },
    /** 底部浮层压住地图后，可视区中心相对地图几何中心的像素偏移（正值＝视线中心下移）*/
    _visibleCenterOffset() {
      // 导航中只量一次并冻结：底部浮层高度会随推送条消失、ETA 卡片落位而变化，
      // 若每次跟车都重新量测，居中偏移就会一直变 —— 地图（连带车辆标记）
      // 会持续漂移几秒才稳定，表现就是"当前位置标记一开始往下滑几秒然后才正常"。
      if (this.navigating && this._navCenterOffset != null) return this._navCenterOffset
      const top = this._topOverlayPadding()
      const bottom = this._bottomOverlayPadding()
      const v = Math.round((bottom - top) / 2)
      if (this.navigating) this._navCenterOffset = v
      return v
    },
    /** 居中到目标点：把车辆/路线放在「浮层以上可视区域」的正中，防止被 ETA 卡片遮住 */
    _centerOn(pt, zoom, duration, animate) {
      if (!this.map || !pt) return
      const latlng = L.latLng(pt)
      const dy = this._visibleCenterOffset()
      const center = this.map.unproject(
        this.map.project(latlng, zoom).add([0, dy]), zoom
      )
      try {
        this.map.setView(center, zoom, {
          animate: animate === undefined ? true : animate,
          duration: duration || 0.6,
          noMoveStart: true
        })
      } catch (e) {
        // setView 会同步派发 zoom/viewreset 给所有监听器；只要有一个抛异常，
        // Leaflet 的 _resetView 就会中断 —— moveend 不触发、渲染器 _zoom 保持陈旧，
        // 下一次 _onZoom 会算出巨大的容器 scale（线宽被放大成色块）。
        // 这里补一次重投影，把状态拉回一致，避免一次异常把整张地图弄坏。
        this._vdbg('setView 抛出：' + ((e && e.message) || e))
        this._resyncMapRenderer()
      }
    },
    /**
     * 让 Leaflet 渲染器重新量测容器并重投影所有矢量图层。
     *
     * 为什么需要：Renderer 的 `_zoom` **只在 `_update`（绑定 moveend）里更新**，
     * 而 `_onZoom` 会用它算 `scale` 并**直接 setTransform 到 SVG 容器上**。
     * 若发生「大幅跳级 + 动画中断/未走完 moveend」，容器上就会残留一个非 1 的 scale，
     * 所有线宽被同比放大 —— 表现就是路线变成巨大色块。
     * 容器若曾以隐藏/0 尺寸被量测，_bounds/viewBox 也会算错，那时整屏空白。
     * 这里强制重新量测 + 重投影，把这类残留状态清干净。
     */
    _resyncMapRenderer() {
      if (!this.map) return
      this.map.invalidateSize()
      this.map.eachLayer(l => {
        // 只重投影**矢量图层**（路线 / 风险线 / 标记）。
        // 绝不能对瓦片层调 redraw()：GridLayer.redraw() 会丢弃已加载瓦片并全部重新请求，
        // 底图会空白几秒再补上 —— 表现就是"进导航时闪几秒钟"。
        // 瓦片层本来就跟随视图自动更新，不需要也不该在这里重建。
        try { if (l instanceof L.Path && l.redraw) l.redraw() } catch (e) { /* 忽略 */ }
      })
    },
    /** 导航视角缩放级别：直接拉到当前底图最大级（矢量瓦片 z14，更高等级由 overzoom 放大），公路细节清晰可见 */
    _navMaxZoom() {
      return this.map ? this.map.getMaxZoom() : this.navZoom
    },
    /** 导航视角：开场落到路线起点；导航中点「回到当前位置」回到车标 */
    zoomToNav() {
      if (!this.map) return
      // 开场视角以**路线起点**为准（模拟行驶也正是从起点出发）：开场不取 _meMarker，
      // 它可能已被真实定位带偏 —— 开了代理时浏览器定位基于出口 IP，
      // 会报到国外（实测报到日本），于是开场就把地图拽走。
      // 但**导航进行中**（_navZoomReady 后用户拖图再点「回到当前位置」）必须回到车标：
      // 模拟行驶在跑时车标以路线为准（GPS 不再动车），不存在代理问题；
      // 若仍取 pathCoords[0]，一旦路线被绕行/调度替换过，首点已是新起点节点，
      // 视角就会「一下子跳到芒街」而不是回到车的位置。
      const veh = (this._navZoomReady && this._meMarker && this._meMarker._map === this.map)
        ? this._meMarker.getLatLng() : null
      const c = (this.route.pathCoords && this.route.pathCoords[0]) || null
      const p = veh || (c ? L.latLng(c[0], c[1]) : (this._meMarker ? this._meMarker.getLatLng() : null))
      if (p) {
        this.navFollowing = true
        this.navOffView = false
        // 先停掉任何正在进行中的视图动画（开场/健康检查/resize 触发的带动画 fitBounds）：
        // 否则那帧动画会逐帧覆盖下面这个 setView，把刚拉到的最大跟车级又拽回概览级。
        try { this.map.stop() } catch (e) { /* 无动画时 stop 可能空转，忽略 */ }
        // 导航视角拉到最大缩放，并以可视区中心对齐车辆 → 路线居中且不被浮层遮挡。
        // 这里**刻意不做动画**：从概览级(z7~8)一步跳到 z19 是 10+ 级，
        // 动画要么被 Leaflet 按阈值跳过、要么会中断进行中的动画，
        // 两种情形都可能让 SVG 渲染器残留一个容器 scale（线被放大成巨大色块）。
        this._centerOn(p, this._navMaxZoom(), 0, false)
        // 跳级完成后强制渲染器重投影，清掉任何残留的 scale / _bounds 偏差
        this._resyncMapRenderer()
      } else {
        this.fitRoute()
        this._resyncMapRenderer()
      }
      // 标记导航视角已就绪，此后车辆移动才会自动跟随
      this._navZoomReady = true
    },
    /** 预约导航开场瓦片预取（800ms 防抖：连续点选候选/进出预览只跑一次） */
    _schedulePrefetch() {
      if (this._prefetchTimer) clearTimeout(this._prefetchTimer)
      this._prefetchTimer = setTimeout(() => { this._prefetchTimer = null; this._prefetchNavTiles() }, 800)
    },
    /**
     * 导航开场瓦片预取（预览阶段跑）：进导航时视角一步跳到最大缩放，那批高级别
     * 瓦片原先要等点了「开始导航」才开始请求 —— 这就是"进导航加载地图慢"的主体。
     * 预览时（司机挑路线、网络空闲）以路线起点为中心，把开场视口覆盖的请求级别
     * 瓦片+注记层用 <img> 提前拉进 HTTP 缓存：URL 由 tileUrlFromDef 按与运行时
     * 完全相同的规则生成；天地图走同源代理，响应带 Cache-Control: max-age=30d
     * immutable，于是进导航时 Leaflet 的同 URL 请求直接命中缓存、开场即满屏；
     * 预取同时也在给代理的内存/磁盘缓存预热，几乎不烧配额。
     * fire-and-forget：单张失败无影响（该瓦回落到正常在线取）。
     */
    _prefetchNavTiles() {
      const st = this._navBase
      const def = st ? baseDef(this._baseChainKey(st.idx)) : null
      if (!def) return // D 盘离线是同源本地瓦片，必达，无需预取
      const start = this.route.pathCoords && this.route.pathCoords[0]
      if (!start) return
      // 运行时请求级别（与 _mountBase 的 tileOpts 完全同式）：tileZoom 先被
      // maxNativeZoom（视网膜屏已减 1）夹住，detectRetina 的 zoomOffset 再 +1。
      // 顺序不能反：旧写法 min(navMaxZoom+retina1, maxNativeZoom) 在手机上算出
      // z16，而运行时实际请求 z17，预取全部落空、开场瓦片仍是点了导航才现取。
      const nativeCap = (def.maxNativeZoom || (def.maxZoom || 18)) - (L.Browser.retina ? 1 : 0)
      const tileZ = Math.min(this._navMaxZoom(), nativeCap) + (L.Browser.retina ? 1 : 0)
      // Web Mercator 瓦片坐标（3857 在线源）
      const n = 2 ** tileZ
      const cx = Math.floor((start[1] + 180) / 360 * n)
      const rad = start[0] * Math.PI / 180
      const cy = Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n)
      // 开场视口瓦片数：视网膜屏瓦片按 128px 显示、普通屏 256px；封顶 7 防失控
      const ts = L.Browser.retina ? 128 : 256
      const nx = Math.min(7, Math.ceil(window.innerWidth / ts) + 1)
      const ny = Math.min(7, Math.ceil(window.innerHeight / ts) + 1)
      const sig = def.key + ':' + tileZ + ':' + cx + ':' + cy + ':' + nx + 'x' + ny
      if (this._prefetchSig === sig) return
      this._prefetchSig = sig
      const hx = Math.floor(nx / 2)
      const hy = Math.floor(ny / 2)
      const urls = []
      for (let dx = -hx; dx <= hx; dx++) {
        for (let dy = -hy; dy <= hy; dy++) {
          urls.push(tileUrlFromDef(def, cx + dx, cy + dy, tileZ))
          if (def.annoUrl) urls.push(tileUrlFromDef({ ...def, url: def.annoUrl }, cx + dx, cy + dy, tileZ))
        }
      }
      console.info('[prefetch] 导航开场底图 ' + urls.length + ' 张（z' + tileZ + ' ' + def.key + '）已预约进 HTTP 缓存')
      urls.forEach(u => { const im = new Image(); im.decoding = 'async'; im.src = u })
    },
    /** 车辆位置更新时的跟随（真实 GPS 稀疏更新 / 导航点「回到当前位置」）：带缓动的一步平移 */
    _followVehicle(pt) {
      if (!this.map || !this.navigating || !this.navFollowing || !this._navZoomReady) return
      // 保持当前缩放级别（用户可自由放大缩小），只做偏移平移 → 路线稳定居中
      this._centerOn(pt, this.map.getZoom(), 0.6)
    },
    /**
     * 逐帧跟车（60fps 路径）：把相机硬平移到「车标应处的屏幕位置」。
     *
     * 为什么不能用 _followVehicle 的 Leaflet 动画：它走 setView({animate:true, duration:0.6})，
     * 而模拟行驶每 100ms 就换一次目标 —— Leaflet 的 setView 先 _stop() 打断上一段动画，
     * 再用 ease-out 重起一段 600ms 缓动。于是相机每秒「起步冲刺 + 减速停车」10 次，
     * 车标（CSS 线性补间）与底图（跟随 pane 位移）的速度曲线完全不同步，
     * 屏幕上就是「当前位置一帧一帧地蹦」。这里改用 panBy({animate:false})——与用户
     * 手指拖动地图完全同一条路径（不挂 leaflet-pan-anim、不加任何过渡），
     * 车标与底图逐帧同位移，恒定 60fps。
     */
    _followVehicleFrame(pt) {
      if (!this.map || !this.navigating || !this.navFollowing || !this._navZoomReady) return
      const z = this.map.getZoom()
      const dy = this._visibleCenterOffset()
      const target = this.map.unproject(this.map.project(L.latLng(pt), z).add([0, dy]), z)
      const cur = this.map.getCenter()
      const off = this.map.project(target, z).subtract(this.map.project(cur, z))
      // 亚像素位移丢弃（panBy 内部也会 round）；差到 1px 以上再平，车标始终贴着中心
      if (Math.abs(off.x) < 0.5 && Math.abs(off.y) < 0.5) return
      try { this.map.panBy(off, { animate: false, noMoveStart: true }) } catch (e) { /* 忽略 */ }
    },
    /** 记录一个 tick 的插值区间（上一绘制位置 → 本 tick 目标位置），并启动 60fps 绘制循环 */
    _setVisSegment(pos) {
      if (!pos) return
      const now = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now()
      const from = this._visPos || pos
      // 段时长用**相邻 tick 的真实到达间隔**（限幅 50~500ms，并与上一段间隔取均值平滑）：
      // SIM_TICK_MS=100 只是 setInterval 的名义值——主线程被瓦片绘制/轮询/修剪挤占时，
      // 两次 tick 实际隔 200ms+ 很常见（探针实测 tickGapMax=212ms）。若段长写死 100ms：
      // 车标跑完本段就停在 b 等下一 tick（顿挫十几帧），接着下一段又要用 100ms 补跑
      // 两段的路（瞬时速度≈3× 中位，猛地窜一截）——这就是「车标顿挫」的真凶。
      // 按实测间隔给段定时长后，段到期时刻恰好贴住下一 tick：车标永远在动、速度恒定。
      const gap = this._visTickAt == null ? SIM_TICK_MS : Math.min(500, Math.max(50, now - this._visTickAt))
      this._visTickAt = now
      const dur = this._visSeg && this._visSeg.dur > 0 ? Math.round((this._visSeg.dur + gap) / 2) : gap
      this._visSeg = { a: [from[0], from[1]], b: [pos[0], pos[1]], t0: now, dur }
      this._startVisLoop()
    },
    _startVisLoop() {
      if (this._visRaf) return
      const step = () => {
        this._visRaf = null
        if (!this.navigating || !this.map) return
        this._paintVehicle()
        this._visRaf = requestAnimationFrame(step)
      }
      this._visRaf = requestAnimationFrame(step)
    },
    _stopVisLoop() {
      if (this._visRaf) { cancelAnimationFrame(this._visRaf); this._visRaf = null }
      this._visSeg = null
      this._visPos = null
      this._visTickAt = null   // 下一段从零标定，不得继承旧行程的 tick 节奏
      // 恢复 CSS 过渡：真实 GPS 那种稀疏更新重新交给 .car-smooth 补间
      if (this._visEl) {
        try { this._visEl.style.transition = '' } catch (e) { /* 忽略 */ }
        this._visEl = null
      }
    },
    /** 60fps 绘制一帧：车标位置 / 朝向 / 相机跟随在同一帧里一起推进（三者绝不错帧） */
    _paintVehicle() {
      const seg = this._visSeg
      const m = this._meMarker
      if (!seg || !m || m._map !== this.map) return
      const now = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now()
      let k = seg.dur > 0 ? (now - seg.t0) / seg.dur : 1
      if (!(k >= 0)) k = 0
      if (k > 1) k = 1
      const a = seg.a, b = seg.b
      const pt = k >= 1 ? [b[0], b[1]] : [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k]
      this._visPos = pt
      // 60fps 直跟时关掉 .car-smooth 的 120ms 过渡：否则车标恒定滞后相机一个过渡时长，
      // 相机往前走、车标追不上，相对屏幕就会一直往后掉
      const el = m.getElement && m.getElement()
      if (el && this._visEl !== el) {
        try { el.style.transition = 'none' } catch (e) { /* 忽略 */ }
        this._visEl = el
      }
      m.setLatLng(pt)
      this._updateCarBearing()
      this._followVehicleFrame(pt)
    },
    /** 用户手动操作地图（拖动 / 全览 / 查看风险点）后暂停跟随 */
    _pauseFollow() {
      // 开场自动放大尚未触发时用户已接管视角，取消它
      if (this._navZoomTimer) { clearTimeout(this._navZoomTimer); this._navZoomTimer = null }
      this.navFollowing = false
      if (this.navigating) this.navOffView = true
    },
    // 点击风险项：切到路线 tab 并把地图定位到该风险点
    focusRisk(r) {
      this.tab = 'route'
      // 查看风险点为主动查看，暂停自动跟随，避免地图被车辆位置拽回
      this._pauseFollow()
      this.$nextTick(() => {
        if (!this.map) return
        const edgeIds = this.route.pathEdgeIds || []
        const i = edgeIds.indexOf(r.edgeId)
        const coords = this.route.pathCoords || []
        const spans = this.route.pathEdgeSpans || []
        const sp = i >= 0 ? spans[i] : null
        // 定位到风险边几何区间中点（几何加密后 coords[i] 不再是该边起点）
        let pt = null
        if (sp && sp.length === 2 && coords[sp[0]]) {
          const mid = Math.floor((sp[0] + sp[1]) / 2)
          pt = coords[Math.min(mid, coords.length - 1)]
        } else if (i >= 0) {
          pt = coords[i]
        }
        if (pt) {
          this.map.setView(pt, 10, { animate: true })
        } else {
          this.fitRoute()
        }
        this.showPush('info', '风险定位', `${r.reason}（${r.edgeId}）`)
      })
    },
    switchTab(t) {
      this.tab = t
      if (t === 'route') this.$nextTick(() => { if (this.map) this.map.invalidateSize() })
    },
    // 首页：交换起终点
    swapOD() {
      const o = this.originId
      this.originId = this.destinationId
      this.destinationId = o
    },
    // 退出导航入口：先二次确认，避免行驶中误触直接退出
    requestExit() {
      this.showNavMenu = false
      this.showExitConfirm = true
    },
    confirmExit() {
      this.showExitConfirm = false
      this.exitNavigation()
    },
    // 退出全屏导航，回到首页：停掉定位、守护、定时刷新与语音，避免后台残留
    exitNavigation() {
      this.navigating = false
      this.navigationStartedAt = 0
      this.previewing = false
      this.previewCollapsed = false
      // 调度任务变更编排相关状态一并复位
      this.showDispatchConfirm = false
      this.dispatchConfirmCollapsed = false
      this._dispatchPreviewPlanId = null
      this._dispatchHandling = false
      // 停驶等待属于本程状态：退出导航即结束，不能残留到下一程（否则新行程一出发就被卡住）
      this.waitHold = false
      this.navFollowing = true
      this.navOffView = false
      this.showNavMenu = false
      this.showExitConfirm = false
      this.pushMsg = ''
      this.showReroute = false
      this.rerouteOptions = []
      this.hazardDetail = null
      this.announceText = ''
      this.forecast = null
      this.lastRerouted = false
      this.lastRiskCount = 0
      if (this.riskTimer) { clearInterval(this.riskTimer); this.riskTimer = null }
      if (this._navZoomTimer) { clearTimeout(this._navZoomTimer); this._navZoomTimer = null }
      this._navZoomReady = false
      // 清空语音队列并打断当前播报（司机主动退出时允许解除最高优先级锁）
      this._cancelVoice(true)
      this._voiceQueue.length = 0
      this._lastVoiceText = ''
      this._lastVoiceAt = 0
      this._lastDepartureAt = 0
      // 重置灾害播报去重：下一次行程遇到同类灾害仍要正常播报
      this._hazardVoiceKey = ''
      this._hazardVoiceAt = 0
      this._stopRealTimeGPS()
      // 本次行程结束：一是停止 Agent 关注这条路线，二是清除在途进度信号——
      // 否则下一单分析会拿着上一单的陈旧进度去禁用陆水联运方案
      axios.post('/api/agent/unregister', null, {
        params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId }
      }).catch(() => {})
      axios.post('/api/trip/reset').catch(() => {})
      // 导航屏随后会被 v-if 移除：同步销毁 Leaflet，避免下次确认调度任务时复用旧容器白屏
      this._destroyMap()
    },
    // 主按钮动作：先搜索候选并直接进入全屏大地图预览，司机在预览里点选路线后再点「开始导航」
    async btnAction() {
      if (this.routeLoading || this.candidateLoading || this.navigating || this.previewing) return
      if (!this.candidates.length) {
        await this.searchRoutes()
        return
      }
      this.enterPreview()
    },
    /** 打开 AI志宁悬浮小球（搜索框麦克风图标的落点：语音指令统一走对话舱解析） */
    openAiAssistant() {
      const ball = this.$refs.aiBallRef
      if (ball && ball.show) ball.show()
    },
    /**
     * AI 志宁端侧指令落地：把对话舱解析出的动作映射到司机端既有能力。
     *  - navigate_to：把抽取到的地名写入终点（沿用 _normalizeOD 归一到路网节点），必要时先退回首页，
     *    随即搜索候选并进入路线预览——等价于司机手动输入终点后点「搜索路线」；
     *  - plan_routes：按当前起终点直接搜索并进入预览。
     * 只读查询（天气/风险/口岸/决策）由对话舱展示后端返回的结论，这里不重复处理。
     */
    async onAiCommand(payload) {
      const tool = payload && payload.tool
      if (tool !== 'navigate_to' && tool !== 'plan_routes') return
      // 导航/预览态下先退回首页：避免在旧路线上就地改终点，或被 navigating 挡掉 enterPreview
      if (this.navigating) this.exitNavigation()
      else if (this.previewing) this.exitPreview()
      if (tool === 'navigate_to') {
        const dest = String((payload.destination || (payload.args && payload.args.destination) || '')).trim()
        if (dest) this.destinationId = dest
      }
      this.tab = 'route'
      await this.$nextTick()
      await this.searchRoutes()
    },
    // 进入全屏大地图预览（选择路线这一步）；此时还未开始导航，不启动 GPS/Agent
    async enterPreview() {
      if (this.navigating) return
      this.showNavMenu = false
      this.previewing = true
      this.previewCollapsed = false
      await this.$nextTick()
      this.initMap()
      // 进入预览即按新视口（地图在上、面板在下）适配整条路线
      this.$nextTick(() => { this.fitRoute(false); this._schedulePrefetch() })
    },
    // 预览页返回首页：保留已搜索的候选路线，方便再次进入预览
    exitPreview() {
      this.previewing = false
      this.previewCollapsed = false
      this.showNavMenu = false
      this.pushMsg = ''
      // 预览屏随后会被 v-if 移除：旧 Leaflet 一并销毁，重进预览/导航时重新建图
      this._destroyMap()
    },
    // 首页候选点选：选中该条并直接进入地图预览
    openPreview(key) {
      this.selectCandidate(key, true)
      this.enterPreview()
    },
    // 1) 搜索多条候选路线并预览
    async searchRoutes() {
      if (this.candidateLoading || this.routeLoading) return
      this._warmUpSpeech() // 预热语音引擎，解除浏览器自动播放限制
      // 先把起终点归一到路网节点 id：内置节点/地名库直接命中，冷门地名走地理编码+吸附。
      // 超出覆盖范围（最近节点 > 5km）或查无此地名时给出明确提示，不再往下算路。
      try {
        await this._normalizeOD()
      } catch (e) {
        this.showPush('danger', '无法定位地点', apiErrorMessage(e, '请输入已覆盖区域的地名（广西—越南北部—平陆运河沿线）'))
        return
      }
      this.candidateLoading = true
      try {
        const r = await axios.get('/api/route/candidates', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold' },
          timeout: 30000
        })
        // 后端按边相似度去重，同一走廊的两种走法仍可能返回两条（末端口岸支线不同）；
        // 这里按「途经」指纹再合并一次，杜绝"同一条路两张卡、红绿自相矛盾"
        this.candidates = this._mergeCorridorDuplicates((r.data && r.data.candidates) || [])
        // 全域受阻：这批候选都是把已封闭路段降级放行才“能走”的，先落建议卡；
        // 下面的“规划完成”成功提示让位给它，否则一边说全线封闭、一边报喜
        const blockedNow = this._applyCorridorAdvisory(r.data && r.data.advisory)
        // 走廊命中时后端会并带回 canalOption：把「坐船出境」作为与公路并列的一条推荐（非风险兜底）
        this.canalOption = (r.data && r.data.canalOption) || null
        this.selectedMode = 'road'
        if (!this.candidates.length) throw new Error('当前无可选路线')
        const def = this.candidates.find(c => c.key === 'recommended') || this.candidates[0]
        this.online = true
        this.selectCandidate(def.key, true)
        this.forecast = def
          ? { probability: def.hazardProbability, level: def.hazardLevel, reason: def.hazardReason }
          : null
        // 搜索成功立即进入全屏大地图预览，司机在地图上点选路线
        await this.enterPreview()
        // 提示里带上最高/最低概率，帮司机一眼看出哪条更安全
        const withProb = this.candidates.filter(c => c.hazardProbability >= 0)
        const safest = withProb.length
          ? withProb.reduce((a, b) => (a.hazardProbability <= b.hazardProbability ? a : b))
          : null
        if (!blockedNow) {
          this.showPush('success', '路线规划完成',
            `为您找到 ${this.candidates.length} 条可选路线`
            + (safest ? `，最安全的是「${safest.label}」${this.topHazardName(safest)}概率 ${safest.hazardProbability}%` : '')
            + '，请在地图上点选一条后开始导航')
        }
      } catch (e) {
        this.online = false
        this.showPush('danger', '路线规划失败', e.response?.data?.message || '网络异常，请稍后重试')
      } finally {
        this.candidateLoading = false
      }
    },
    // 2) 点选候选：高亮选中并即时预览（地图 / ETA / 途经摘要同步）
    selectCandidate(key, silent) {
      const c = this.candidates.find(x => x.key === key)
      if (!c) return
      this.selectedMode = 'road'
      this.selectedKey = key
      this._auditSwap('selectCandidate:' + key)
      this.route = {
        pathCoords: c.coords || [],
        pathEdgeIds: c.edgeIds || [],
        pathEdgeSpans: c.edgeSpans || [],
        pathNodeIds: c.nodeIds || [],
        estimatedHours: c.hours,
        totalDistanceKm: c.distanceKm,
        baselineHours: c.hours,
        currentHours: c.hours,
        extraHours: 0,
        rerouted: false,
        riskSegments: [],
        // 候选自带的逐段风险边（后端 toCandidate 下发），供预览阶段红绿分段着色
        riskEdgeIds: c.riskEdgeIds || [],
        baselinePathCoords: [],
        cargoLossYuan: 0
      }
      this.viaText = c.via || ''
      this.$nextTick(() => {
        this.drawRoute()
        if (!silent) this.fitRoute(false)
        // 预览中换选候选：路线起点可能变，重新预约预取（签名未变则自动跳过）
        if (this.previewing) this._schedulePrefetch()
      })
    },
    // 坐船候选点选：切换出行方式为水运，并把地图主线换成「上船公路段 + 后续走廊」——
    // 否则图上还挂着之前公路候选的线（可能经过芒街），与选定的坐船方案自相矛盾
    selectCanal() {
      if (!this.canalOption) return
      // 禁航中不再可选：标红卡片同时阻断点选，避免司机把注定走不了的方案选上
      if (this.canalBlockedNow) {
        this.showPush('warn', '平陆运河禁航中', `${this.canalBlockReasonNow}，水运方案暂不可选，请选一条公路路线`)
        return
      }
      this.selectedMode = 'canal'
      this._drawCanalPreviewRoute()
      this.showPush('info', '已选陆水联运',
        `可全程坐船经平陆运河出境：${this.canalOption.via}，约 ${this.canalOption.hours} 小时。地图已切换到上船方案（实线为公路段至南宁港六景，虚线为运河/海运走廊），点「开始导航」即按此行驶。`)
    },
    /**
     * 预览选定坐船：把地图主线换成 起点→南宁港六景 的上船公路段（真实算路，带风险着色），
     * 后续运河/海运/越方公路由 _drawCorridor 以虚线走廊补全。
     * 不碰 destinationId/候选列表：司机改选公路候选时 selectCandidate 会直接覆盖 route 并清除走廊。
     */
    async _drawCanalPreviewRoute() {
      const cacheKey = `${this.resolvedOriginId}→LJ`
      if (this._canalRoadKey === cacheKey && this._canalRoadRoute) {
        this._auditSwap('canalPreview-cache')
        this.route = this._canalRoadRoute
        this.$nextTick(() => { this.drawRoute(); this.fitRoute(false) })
        return
      }
      try {
        const r = await axios.get('/api/route/plan-with-weather', {
          params: { originId: this.resolvedOriginId, destinationId: 'LJ', cargoType: 'cold', withAi: false },
          timeout: 30000
        })
        const resp = r.data.route || r.data
        // 请求期间司机可能已改选公路候选：过期结果不覆盖地图
        if (this.selectedMode !== 'canal' || !this.previewing) return
        if (!resp.pathCoords || !resp.pathCoords.length) return
        this._canalRoadKey = cacheKey
        this._canalRoadRoute = resp
        this._auditSwap('canalPreview-fetch')
        this.route = resp
        this.risks = r.data.risks || this.risks
        this.$nextTick(() => { this.drawRoute(); this.fitRoute(false) })
      } catch (e) {
        // 上船段拉取失败不阻断选定，但必须把旧公路主线抹掉：
        // 旧线上可能经过芒街，卡片已选坐船、地图却画着公路候选，两者自相矛盾。
        // 抹掉后只留虚线走廊与全程 ETA/里程（来自 canalOption），图面与所选方案一致。
        console.warn('canal preview road leg failed', e)
        this._auditSwap('canalPreview-fail')
        this.route = {
          ...this.route,
          pathCoords: [], pathEdgeIds: [], pathEdgeSpans: [], pathNodeIds: [], riskEdgeIds: [],
          estimatedHours: this.canalOption.hours,
          totalDistanceKm: this.canalOption.distanceKm,
          baselineHours: this.canalOption.hours,
          currentHours: this.canalOption.hours,
          extraHours: 0, rerouted: false, riskSegments: [],
          baselinePathCoords: [], cargoLossYuan: 0
        }
        this.viaText = this.canalOption.via || this.viaText
        this.$nextTick(() => { this.drawRoute() })
      }
    },
    /** 走廊是否应展示：导航中的方案B，或预览中已点选坐船（与 _drawCorridor 共用） */
    _canalCorridorActive() {
      return this.activePlanId === 'B' || (this.previewing && this.selectedMode === 'canal')
    },
    // 3) 按司机所选路线开始导航（choice 贯穿导航轮询，路线不再被系统推荐强制替换）
    async startNavigation() {
      // 调度路线预览态下点「开始导航」= 确认接收调度指令，走确认流程（保留调度方案与导航开始播报）
      if (this._dispatchPreviewPlanId || this.showDispatchConfirm) { return this.confirmTaskChange() }
      if (this.routeLoading || this.candidateLoading) return
      // 坐船（陆水联运）：普通导航下司机主动选择去越南的方式，复用方案B的已验证导航链路
      // （先规划并导航到南宁港六景、图上画出后续运河/海运/越南公路走廊）。
      if (this.selectedMode === 'canal' && this.canalOption) {
        this._warmUpSpeech()
        this.routeLoading = true
        try {
          axios.post('/api/trip/start', null, {
            params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId }
          }).catch(() => {})
          await this._switchNavForPlan('B')
          await this._autoStartDispatchNav('B')
        } catch (e) {
          console.error('canal nav failed', e)
          this.showPush('warn', '水运导航启动失败', '请重试，或改选一条公路路线。')
        } finally {
          this.routeLoading = false
        }
        return
      }
      if (!this.candidates.length) {
        await this.searchRoutes()
        if (!this.candidates.length) return
      }
      this._warmUpSpeech()
      // 司机自行规划出发 = 非调度行程，清掉方案标记，
      // 否则上一单是水运（B）时会把"经平陆运河"残留到这一次播报里
      this.activePlanId = null
      // 司机自己出发 = 新的一程，上一轮的「原地等待」停驶态不能带过来
      this.waitHold = false
      // 同上：上一程的全域受阻建议不能残留（起终点已变，新 O/D 会重新算一份）
      this.corridorAdvisory = null
      this.corridorNotice = null
      // 新一程还没开始，上一轮的「等调度换线」不能跟着带过来
      this.waitDispatch = null
      this._corridorAck = ''
      this._corridorSig = ''
      this.routeLoading = true
      try {
        // 注册为 Agent 活跃任务（灾害触发时自动重算并推送）；公路模式
        axios.post('/api/agent/register', null, { params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, mode: 'road' } }).catch(() => {})
        // 行程启动信号：调度大屏据此自动触发一次 AI 六维分析（常态方案对比），
        // 由调度员人工确认路线后再下发任务指令给司机
        axios.post('/api/trip/start', null, { params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId } }).catch(() => {})
        // withAi:false：开始导航不再等 AI 预警文案（后端 AI 是秒级 LLM 调用，是“进导航加载很久”的主因），
        // 只要几十毫秒的路线/天气/风险结果先秒进导航；AI 文案在下面后台异步回填。
        const plan = await axios.get('/api/route/plan-with-weather', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold', choice: this.selectedKey, withAi: false },
          timeout: 30000
        })
        const resp = plan.data.route || plan.data
        this._auditSwap('startNavigation')
        this.route = resp
        this.online = true
        // withAi:false 下 aiWarning 为空：保留已有文案不清空，避免预警框闪一下空白；真正的 AI 文案后台补
        this.warning = plan.data.aiWarning || plan.data.warning || this.warning || ''
        this.risks = plan.data.risks || this.risks
        if (resp.pathNodeIds && resp.pathNodeIds.length) {
          const via = this._viaFromNodes(resp.pathNodeIds)
          if (via) this.viaText = via
        }
        this.checkPush(resp)
        this.navigationStartedAt = Date.now()
        this._mapHardRecovered = false
        this.navigating = true
        this.previewing = false
        this.previewCollapsed = false
        this.navFollowing = true
        this.navOffView = false
        this.showNavMenu = false
        await this.$nextTick()
        // 预览阶段已用同一地图容器建好地图；initMap 会识别并复用，旧容器则自动重建
        this.initMap()
        // 由预览切到导航：恢复「铺满屏幕」的最小缩放（预览时可能为适配路线放宽过）
        this._applyCoverageZoom()
        // 开启 GPS 实时定位（演示环境 GPS 不可用时自动降级模拟行驶）
        this._startRealTimeGPS()
        // 与调度确认链路同理：模拟刚按新里程表从头起，先强制刷一次剩余时间再播报，
        // 避免出发语音拿到 watcher 用旧里程表算出的残留值（与卡牌不一致）
        this._updateLiveEta(true)
        // 地图已创建后再播导航开始；语音链路任何异常都不能影响地图初始化
        this._announceDepartureSafely()
        // 开场视角：直接落到模拟起点（不再"先全览再放大"，那个开场会让视角显得乱跳）
        this._navZoomReady = false
        this.$nextTick(() => {
          // 由预览切到导航：面板从「上下分栏」变回浮层，地图区域随之变大，
          // 先让 Leaflet 重新量测尺寸再定位，否则会按旧尺寸算出错误视野。
          if (this.map) this.map.invalidateSize()
          // 进入导航时重置居中偏移，让本次只重新量测一次（之后导航中冻结，避免漂移）
          this._navCenterOffset = null
          // 切视角前先“有条件地”拉起遮罩，避免在线瓦片重铺的空窗露成白屏（见 _veilUntilViewPaint）
          this._veilUntilViewPaint()
          // 开场「直接落到模拟起点」——不再先 fitRoute 全览。
          // 旧写法在这里先 fitRoute(true)（带动画的整线概览 fitBounds），420ms 后再 zoomToNav 拉最大级，
          // 但那次带动画的概览会一路覆盖紧随其后的 setView：车刚被放到最大级，就被拽回整条路线的概览级，
          // 表现即用户反馈的「进导航放大到最大 → 往上走一段 → 又自动缩到最小」（实测 fitRoute/zoomToNav
          // 在 1.6s 内交错触发 6 次）。跟车视角由 _followVehicle 每 tick 维持、开场由 zoomToNav 定位，
          // 无需概览兜底；用户主动点「全览路线」仍照常。这里只留一个短延时让 Leaflet 完成 invalidateSize。
          this._resyncMapRenderer()
          this._navZoomTimer = setTimeout(() => {
            this._navZoomTimer = null
            this.zoomToNav()          // 放大到「模拟起点」（路线首点）
          }, 260)
        })
        // 导航开始后自动拉取途径城市天气
        this.loadWeatherForRoute().catch(() => {})
        // AI 预警文案后台补：不进关键路径，返回后静默回填 this.warning（只更新屏幕文案、不打扰出发播报）
        axios.get('/api/route/plan-with-weather', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold', choice: this.selectedKey, withAi: true },
          timeout: 60000
        }).then(p => {
          const w = p.data && (p.data.aiWarning || p.data.warning)
          if (w) this.warning = w
        }).catch(() => {})
        this.showPush('success', '导航已开始', `路线：${this.routeTitle}，预计 ${resp.estimatedHours || '--'} 小时，Agent 已实时守护`)
        // 出发后转为"途中实时预测"：每 3 分钟重新预测一次灾害概率
        if (this.riskTimer) clearInterval(this.riskTimer)
        this.riskTimer = setInterval(() => { this.refreshForecast(false) }, 480000)
        this.refreshForecast(true)
      } catch (e) {
        this.online = false
        // 离线降级：加载本地演示路线，照样进入导航
        try {
          const r = await axios.get('/data/demo-route.json')
          const resp = r.data.route || r.data
          this._auditSwap('startNavigation-offline-fallback')
          this.route = { ...this.route, ...resp }
          this.navigationStartedAt = Date.now()
          this._mapHardRecovered = false
          this.navigating = true
          this.previewing = false
          this.previewCollapsed = false
          this.navFollowing = true
          this.navOffView = false
          this.showNavMenu = false
          await this.$nextTick()
          this.initMap()
          this._applyCoverageZoom()
          this._startRealTimeGPS()
          this._announceDepartureSafely()
          this.showPush('success', '离线导航模式', `已加载演示路线，预计 ${resp.estimatedHours || '--'} 小时`)
          this.$nextTick(() => {
            if (this.map) this.map.invalidateSize()
            // 与在线开场一致：直接落到模拟起点（zoomToNav）——导航中静默全览已被 fitRoute 拦下，
            // 这里不能再靠 fitRoute(true) 定视角。
            this.zoomToNav()
          })
          return
        } catch (_) {
          // 连本地演示路线也没有
        }
        this.showPush('danger', '路线规划失败', e.response?.data?.message || '网络异常，请检查后重试')
      } finally {
        this.routeLoading = false
      }
    },
    // 拉取所选路线最新版本（带天气与风险；choice 保持一致）
    async fetchRoute() {
      // 已选好这条线（含未开始导航）→ 注册关注，风险注入后该路线会被重算推送；
      // mode 标记当前出行方式，运河禁航事件据此点名受影响在途对象
      axios.post('/api/agent/register', null, {
        params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, mode: this._isCanalCommuting() ? 'canal' : 'road' }
      }).catch(() => {})
      // withAi=false：本方法不消费 aiWarning，而 AI 文案要秒级生成，
      // 它同时挂在 15 秒轮询和灾害触发的重取路径上，开着会让路线更新明显滞后。
      // 快照换线前在跑的路线与车位置：轮询重算的起点是 _navOriginId 吸附到的路网节点，
      // 稀疏长边下可距车几十公里——不拼接就是一条从节点起画的新线，
      // 下面 _syncTripProgress 把车吸到最近点=那个节点，表现就是「确认后十几秒起点又大跳」
      // （调度换线拼好的桥接段会被这次无拼接替换整个抹掉）。
      const pollPrevCoords = (this.route && this.route.pathCoords) || []
      const pollPrevEdgeIds = (this.route && this.route.pathEdgeIds) || []
      const pollPrevSpans = (this.route && this.route.pathEdgeSpans) || []
      const plan = await axios.get('/api/route/plan-with-weather', {
        params: { originId: this._navOriginId(), destinationId: this.resolvedDestinationId, cargoType: 'cold', choice: this.selectedKey, withAi: false },
        timeout: 30000
      })
      const resp = plan.data.route || plan.data
      // 把轮询新线对齐到车辆当前位置：车在走廊上→就地截断；吸附节点在车前方/后方→沿当前线桥接
      this._stitchRouteFromCar(resp, this._meMarker ? this._meMarker.getLatLng() : null,
        pollPrevCoords, pollPrevEdgeIds, pollPrevSpans)
      // 同线微抖：这次重算的几何就是正在跑的这条线，不替换路线与里程，
      // 只收风险着色与天气——否则车标每 15s 被重吸附到抖动后的顶点，来回横穿马路
      if (resp._sameLineKeep) {
        this.risks = plan.data.risks || this.risks
        this.online = true
        // 全域受阻建议与线几何无关，同线保留时照常应用（SSE 断线期间的兜底通道）
        this._applyCorridorAdvisory(plan.data.advisory)
        this.loadWeatherForRoute().catch(() => {})
        return
      }
      // 接单模式不自动绕路：后端已按注入的风险把这条 O/D 重算成了绕行线（resp.rerouted），
      // 而下面那句 this.route = resp 是无条件采纳的——等于司机没点任何东西、也没收到调度指令，
      // 车就在 15 秒轮询里自己改道了，刚好绕开刚立起来的「等调度」约束。
      // 因此接单导航中只收风险不收路线：标红 + 挂等待卡，线仍是司机正在跑的那一条。
      // 本线上没命中风险时不拦（那是起终点重算之类的非灾因刷新，不能让车标卡住）。
      const guardRisks = plan.data.risks || resp.riskSegments || []
      const guardCurIds = new Set(((this.route && this.route.pathEdgeIds) || []).map(String))
      const guardMine = guardCurIds.size
        ? guardRisks.filter(x => x && guardCurIds.has(String(x.edgeId)))
        : guardRisks
      if (this.navigating && this.isTaskAccepted && resp.rerouted
          && !(this.route && this.route.rerouted) && guardMine.length) {
        this.online = true
        this._markRouteRisky(guardMine)
        this._notifyHazard({ hazardEdgeIds: [], risks: guardRisks }, guardMine)
        this._armWaitDispatch(guardMine[guardMine.length - 1].reason || '前方路段风险')
        this.loadWeatherForRoute().catch(() => {})
        return
      }
      this._auditSwap('fetchRoute-poll')
      this.route = resp
      this.online = true
      this.risks = plan.data.risks || this.risks
      if (resp.pathNodeIds && resp.pathNodeIds.length) {
        const via = this._viaFromNodes(resp.pathNodeIds)
        if (via) this.viaText = via
      }
      // 轮询用 _navOriginId()（车当前所在节点）重算，新路线坐标数组与旧的 _simCum
      // 里程表长度不一致：不重标定就会让 _simPointAt 每 tick 返回 null（车标冻住不动），
      // 里程累加到旧总里程后 _stopTripSimulation，表现就是“模拟行驶图标有时停下来”。
      this._syncTripProgress()
      // 全域受阻：导航轮询也要接住（SSE 是主通道，但断线重连期间只能靠这条兜底）；
      // 成立时不再走「新增风险→弹换线面板」的常规链路
      const blocked = this._applyCorridorAdvisory(plan.data.advisory)
      if (!blocked) this.checkPush(resp)
      // 轮询刷新时同步更新沿线天气
      this.loadWeatherForRoute().catch(() => {})
    },
    // 轮询兜底：15s 一次（SSE 为主通道）
    async refreshAll() {
      if (!this.navigating) return
      try {
        await this.fetchRoute()
      } catch (e) {
        this.online = false
      }
    },
    // 输入变动时清空旧候选与预览（导航中不清，保持实时守护）
    _resetCandidates() {
      if (this.navigating) return
      // 调度方案切换期间（_switchNavForPlan）会修改 destinationId，
      // 此时不应清空 route / 销毁地图，否则后续 _autoStartDispatchNav 拿不到路线数据，
      // 导致进入导航后地图空白。
      if (this._switchingPlan) return
      this.previewing = false
      this.previewCollapsed = false
      this.navFollowing = true
      this.navOffView = false
      this.candidates = []
      this.selectedKey = 'recommended'
      this.canalOption = null
      this.selectedMode = 'road'
      this.viaText = ''
      this._auditSwap('resetCandidates')
      this.route = {}
      // 起终点变化会让预览容器一起消失，旧地图不能留到下一次复用
      this._destroyMap()
    },
    // 途经摘要兜底：路径节点名映射取前 6 个（状态卡展示）
    _viaFromNodes(nodeIds) {
      const names = []
      for (const id of nodeIds) {
        const n = NODE_NAMES[id]
        if (n && n !== this.originName && n !== this.destinationName) {
          names.push(n)
          if (names.length >= 6) break
        }
      }
      return names.join(' → ')
    },
    /**
     * 同一走廊合并：以「途经摘要」作为走廊指纹。
     *
     * 后端按边集合 Jaccard 相似度(阈值 0.85)去重，但同一条口岸走廊的两种走法
     * 若仅在末端几公里（口岸支线）不同，相似度会掉到阈值以下，于是被当成两条返回；
     * 而灾害概率又是按各自路径的节点采样天气算的，微小的路径差异就会得到
     * 8% 与 93% 这种天差地别的结果 —— 表现就是"同一条路，一张红一张绿"。
     *
     * 这里按走廊指纹合并为一张卡：命名/几何取更合适的一条（当前选中 > 推荐 > 更快），
     * 风险字段统一取组内**最坏**的一条（同一走廊按保守值处理，宁可高估不可低估）。
     */
    _mergeCorridorDuplicates(list) {
      const groups = new Map()
      ;(list || []).forEach(c => {
        if (!c) return
        const k = String(c.via || '').replace(/\s+/g, '') || `#${c.key}`
        if (!groups.has(k)) groups.set(k, [])
        groups.get(k).push(c)
      })
      const prob = p => (p == null || p < 0) ? -1 : p
      return [...groups.values()].map(g => {
        if (g.length === 1) return g[0]
        const rep = g.find(o => o.key === this.selectedKey)
          || g.find(o => o.key === 'recommended')
          || g.reduce((a, b) => ((a.hours || 1e9) <= (b.hours || 1e9) ? a : b))
        const worst = g.reduce((a, b) => (prob(b.hazardProbability) > prob(a.hazardProbability) ? b : a))
        return {
          ...rep,
          hazardProbability: worst.hazardProbability,
          hazardOccurred: g.some(o => o.hazardOccurred),
          hazardLevel: worst.hazardLevel,
          hazardTypes: worst.hazardTypes,
          hazardReason: worst.hazardReason,
          hazardBreakdown: worst.hazardBreakdown,
          hazardHasWeather: worst.hazardHasWeather,
          riskCount: Math.max(...g.map(o => o.riskCount || 0))
        }
      })
    },
    checkPush(r) {
      // 只认本路线上真正命中的风险段（见 _risksOnRoute）：
      // 用全量列表会把别处的风险报成"本路线前方风险"，南宁→六景那一程就误报过「暴雨」。
      const segs = this._risksOnRoute(r)
      if (r.rerouted && !this.lastRerouted) {
        // 灾害真的发生并触发绕行：语音播报具体灾害 + 弹出替代路线供司机点选。
        // 绕行成功时新路线上已无风险段，segs 必为空——致灾原因要从被绕开的
        // 原路线(baseline)边集上找，否则标题永远兜底成笼统的「路段风险」。
        let reason = segs.length ? segs[segs.length - 1].reason : ''
        if (!reason) {
          const baseIds = new Set((r.baselinePathEdgeIds || []).map(String))
          const pool = (this.risks && this.risks.length) ? this.risks : (r.riskSegments || [])
          const onBase = pool.filter(x => x && baseIds.has(String(x.edgeId)))
          if (onBase.length) reason = onBase[onBase.length - 1].reason
        }
        reason = reason || '路段风险'
        // 快照灾害位置+类型：风险解除切回原路线时（见 _announceRiskCleared）this.risks 已清空，
        // 只能靠这里存下的简报才能念出「原路线X处Y风险已解除」
        this._lastHazardBrief = {
          where: this.inferPortFromNodeIds(r.baselinePathNodeIds || r.pathNodeIds || []),
          hazard: this.extractHazardKeyword(reason)
        }
        // 面板可能因「当前已在最优绕行线」被取消（见 loadRerouteOptions），
        // 推送文案不再引导「点选其他方案」，有得选时面板自己会出来
        this.showPush('success', '路线已绕行',
          `${reason}，已切换绕行路线，预计延误 ${r.extraHours}h。请按新路线行驶。`)
        // 接单模式不自动弹绕行面板（换线权在调度，与 SSE route-update 同一 isTaskAccepted 判据）；
        // 这条绕行线已经生效，再挂一个“自己选一条”的面板会与「等调度指令」自相矛盾
        if (!this.isTaskAccepted) this.loadRerouteOptions(reason).catch(() => {})
        this.lastRerouted = r.rerouted
        this.lastRiskCount = segs.length
        return true
      }
      if (!r.rerouted && this.lastRerouted) {
        // 曾在绕行线上、这次拿到的线不再是绕行线 = 风险已解除并切回原路线：
        // 播报「原路线X处Y灾害风险已解除」（详见 _announceRiskCleared）
        this._announceRiskCleared()
        this.lastRerouted = false
        this.lastRiskCount = segs.length
        return true
      }
      if (segs.length > this.lastRiskCount) {
        // 新增风险（尚未绕行）：两种模式给不同的出口——
        // 普通用户自动弹出绕行方案面板自助点选；接单司机只标红 + 等调度，不弹面板
        // （判据用 isTaskAccepted 而非 navigating && isTaskAccepted：刚接单、navigating 标志尚未
        // 置真的窗口里也必须等调度，否则雾/台风会经轮询兜底通道漏出自助换线面板，与 SSE 主通道不一致）
        const reason = segs[segs.length - 1].reason || '前方路段风险'
        if (this.isTaskAccepted) {
          this._markRouteRisky(segs)
          this._armWaitDispatch(reason)
        } else {
          this.showPush('danger', '新增气象风险', `${reason}。已为您准备绕行方案，请在屏幕上点选。`)
          this.loadRerouteOptions(reason).catch(() => {})
        }
        this.lastRerouted = r.rerouted
        this.lastRiskCount = segs.length
        return true
      }
      this.lastRerouted = r.rerouted
      this.lastRiskCount = segs.length
      return false
    },
    /**
     * 全域受阻建议的统一入口：SSE route-update、15 秒轮询、候选拉取三处都汇到这里。
     *
     * 判据完全取自后端（公路全部走廊已封闭 且 运河禁航），本端只补一件事——
     * 后端推送时不知道车在哪，只会按「不在途」给 DEFER；司机端手上有实时进度，
     * 进度越过 customsCrossRatio 就该改 HOLD（已出境，折返要重新通关、不经济），否则改 RETURN。
     *
     * @returns true = 本轮已由全域受阻接管，调用方必须停止自动换线与自动局部绕行
     */
    _applyCorridorAdvisory(a) {
      const prev = this.corridorAdvisory
      const next = hasAdvisory(a) ? a : null
      this.corridorAdvisory = next
      if (!next) {
        if (prev) {
          // 走廊或运河恢复：撤卡并明确告知恢复，否则司机不知道可以走了
          this.corridorNotice = null
          this._corridorAck = ''
          this._corridorSig = ''
          this.showPush('ok', '全域受阻解除', '出境走廊与平陆运河已恢复通行，可按导航继续行驶。')
          if (this.voiceOn) this._interruptAndSpeak(['全域受阻解除，出境走廊与运河已恢复通行，可按导航继续行驶。'])
        }
        return false
      }
      const notice = this._buildCorridorNotice(next)
      // 签名含本地算出的动作：车越过口岸后 DEFER→HOLD 也算局势升级，必须重新提醒
      const sig = ((notice && notice.action) || '') + '|' + String(next.detail || '')
      if (sig === this._corridorAck) {
        // 司机已表态「暂不处置」：不再抢屏，但仍然拦着自动换线（换了也是换进另一个风险区）
        this.corridorNotice = null
        return true
      }
      const fresh = !this.corridorNotice || sig !== this._corridorSig
      this._corridorSig = sig
      this.corridorNotice = notice
      if (fresh) this._notifyCorridorBlock(notice)
      return true
    },
    /**
     * 按本地进度把后端的 advisory 翻成司机看得懂的一张卡。
     *
     * 折返里程：后端只有在拿到在途进度时才算（action=RETURN 那一次），而司机端的 RETURN
     * 是本地复算出来的，后端那一次给的是 DEFER、没带里程——就按当前线已走里程估：
     * 沿原路掉头，回去走的就是刚过来那一段，里程 = 进度 × 全程。
     */
    _buildCorridorNotice(a) {
      const ratio = this._localProgressRatio()
      const action = resolveAction(a, ratio)
      const src = { ...a }
      if (action === 'RETURN' && (src.returnDistanceKm == null || src.returnHours == null)) {
        const total = Number(this.route && this.route.totalDistanceKm) || 0
        const planned = Number(this.route && this.route.estimatedHours) || 0
        if (ratio > 0 && total > 0) {
          src.returnDistanceKm = ratio * total
          src.returnHours = ratio * planned > 0 ? Math.round(ratio * planned * 10) / 10 : null
        }
      }
      const view = advisoryView(src, ratio)
      if (!view) return null
      const origin = a.originName || this.originName || '起点'
      let primaryLabel = '暂缓发车，留在原地待命'
      if (view.action === 'RETURN') {
        primaryLabel = '按建议折返' + origin
          + (src.returnDistanceKm != null ? '（约 ' + Math.round(src.returnDistanceKm) + 'km）' : '')
      } else if (view.action === 'HOLD') {
        primaryLabel = '就近安全停靠等待'
      }
      return {
        action: view.action,
        actShort: ACTION_SHORT[view.action] || '返回 / 等待',
        title: view.title,
        text: view.text,
        waitText: view.waitText,
        blockedText: view.blockedText,
        basis: a.waitBasis || '',
        originName: origin,
        primaryLabel
      }
    },
    /** 全域受阻强提醒：震动 + 顶部推送条 + 抢占语音（只念一句结论，长说明留给卡片） */
    _notifyCorridorBlock(notice) {
      if (!notice) return
      if (navigator.vibrate) navigator.vibrate([300, 120, 300])
      this.showPush('danger', notice.title, notice.text)
      this.pushLog.unshift({
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        text: '全域受阻 · ' + notice.actShort
      })
      // 换线面板里的候选全是降级放行出来的，留着只会误导司机“有得选”
      this.rerouteOptions = []
      if (this.voiceOn) this._interruptAndSpeak([advisorySpeakText(notice, notice.originName)], true)
    },
    /** 车在当前行程线上的进度（0~1）；不在途返回 -1，与后端 progressRatio 同一口径 */
    _localProgressRatio() {
      const pos = this._meMarker ? this._meMarker.getLatLng() : null
      if (pos && Number.isFinite(pos.lat)) return this._computeProgressRatio(pos.lat, pos.lng)
      // 刚进导航、车标还没落位：直接查里程表，口径与 _computeProgressRatio 一致
      if (this.navigating && this._simTotalM) {
        return Math.max(0, Math.min(1, (this._simDistM || 0) / this._simTotalM))
      }
      return -1
    },
    /** 司机点「暂不处置 / ✕」：记住本轮签名，走廊与成因未变就不再抢屏（但仍不自动换线） */
    dismissCorridorNotice() {
      this._corridorAck = this._corridorSig || ''
      this.corridorNotice = null
      this.pushLog.unshift({
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        text: '司机未采纳全域受阻建议，保持当前路线行驶'
      })
    },
    /** 采纳建议：RETURN 走真实折返算路，HOLD/DEFER 复用方案 C 的停驶链路 */
    async executeCorridorAction() {
      const n = this.corridorNotice
      const a = this.corridorAdvisory
      if (!n || !a) return
      if (n.action === 'RETURN') { await this._startReturnToOrigin(a, n); return }
      this.pushLog.unshift({
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        text: '全域受阻 · 司机采纳' + n.actShort
      })
      if (this.navigating) {
        // 已出境或未出境但选择等待：真的把车停下来（停 GPS/模拟推进），不是只在屏上写一行
        this._applyWaitHold()
        this.showPush('warn', '已停靠等待', '已按建议停在当前位置等待解除，冷链机组保持运行；走廊恢复会再提醒。')
      } else {
        this.showPush('warn', '暂缓发车', '已按建议留在' + n.originName + '待命，出境走廊与运河恢复后会再提醒。')
      }
    },
    /**
     * 执行折返：把这一程的终点换成行程起点，从车辆当前所在节点往回算路。
     * 走既有的 searchRoutes 链路（候选→预览→开始导航都按新 O/D 重走），
     * 不自带掉头线——回程同样要过一遍风险图，该绕的还得绕。
     */
    async _startReturnToOrigin(a, n) {
      const backTo = a.originId
      if (!backTo) return
      const cur = this._navOriginId()
      this.pushLog.unshift({
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        text: '全域受阻 · 司机执行折返返回' + n.originName
      })
      this._releaseWaitHold()
      this.rerouteOptions = []
      this.corridorNotice = null
      // 折返是新的一程：本轮签名归档，回程途中同一局势不再按原 O/D 反复弹屏
      this._corridorAck = this._corridorSig || ''
      this.originId = cur
      this.destinationId = backTo
      this.showPush('warn', '正在规划回程', '按建议折返' + n.originName + '，正在从当前位置重新算路…')
      await this.searchRoutes()
    },
    // Agent 实时守护：SSE 订阅，灾害发生后后端立即重算并推送新路线（毫秒级），轮询仅作兜底
    connectAgent() {
      if (this._agentEs) this._agentEs.close()
      this._agentEs = new EventSource('/api/agent/events')
      this._agentEs.addEventListener('route-update', (ev) => {
        try {
          const data = JSON.parse(ev.data)
          // 多条关注路线会各自广播：只处理与自己当前行程（O/D）一致的更新，其余忽略
          if (data.origin && data.destination
              && (data.origin !== this.resolvedOriginId || data.destination !== this.resolvedDestinationId)) {
            return
          }
          // 全域受阻优先于一切自动处置：全部出境走廊已封闭且运河禁航时，
          // 不管是自动换线还是局部绕行，都只把车从一个风险区搬进另一个风险区，
          // 必须交给司机决定“返回还是就地等”。建议是按本 O/D 算的，与灾害边是否命中无关，
          // 所以闸门放在边集过滤之前。
          if (this._applyCorridorAdvisory(data.advisory)) return
          // 未开始导航但已选好这条线（或目的地是越南端）：风险变化要立即预警并静默刷新路线，
          // 让司机在上路前就知道前方有灾、路线已调整；只是没强提醒打断。
          if (!this.navigating) {
            this._watchPreviewRisk(data)
            return
          }
          // 灾害边必须与**当前在跑的这一条行程线**求交集：后端把全网 hazardEdgeIds
          // 原样塞进每条关注路线的推送里，友谊关熔断时，导航去南宁港（NN→LJ，国内段）
          // 的司机也会收到自己这条线的“前方灾害”，并据此误调 /api/route/detour 触发
          // 局部绕行——那条熔断边根本不在这条路上。路线没带边信息（离线降级线）时保持全局清单不误伤。
          const curIds = new Set(((this.route && this.route.pathEdgeIds) || []).map(String))
          const allHaz = data.hazardEdgeIds || []
          const hazIds = curIds.size ? allHaz.filter(id => curIds.has(String(id))) : allHaz
          const risksMine = curIds.size
            ? (data.risks || []).filter(x => x && curIds.has(String(x.edgeId)))
            : (data.risks || [])
          const hazData = { ...data, hazardEdgeIds: hazIds }
          // 本轮灾害既不在当前路线上、风险清单里也没有本路线命中的边：那是别的廊道/别的行程的事，
          // 不打扰；但自己这条线重算失败（route 为空）是硬信息，不能吞
          if (data.route && allHaz.length && !hazIds.length && !risksMine.length) {
            return
          }
          // 灾害必须**先提示**：后面无论哪种模式都还有 HTTP 往返，
          // 其中 fetchRoute 走 /api/route/plan-with-weather，注入风险后实测 4 秒以上。
          // 等它回来才提示，司机端会明显滞后于大屏（后端 SSE 只需 ~350ms）。
          // 标红放在提示之前：_notifyHazard 内部也会标红，但风险解除分支不进它，
          // 这里先上一道保险，保证「有新风险就一定有红线」。
          this._markRouteRisky(risksMine.length ? risksMine : (data.risks || []))
          this._notifyHazard(hazData, risksMine)
          if (!data.route) {
            // 硬熔断：所有路线均不可通行，红色告知司机停车等待调度指令
            this.showPush('danger', '无可用路径', data.advice || '所有路线均不可通行，请停车等待调度指令。')
            return
          }
          const hasRiskNow = hazIds.length > 0 || risksMine.length > 0
          const reason = (risksMine.length ? risksMine[risksMine.length - 1].reason
            : (data.risks && data.risks.length ? data.risks[data.risks.length - 1].reason : ''))
            || '前方路段风险'
          if (!hasRiskNow) {
            // 风险解除：两种模式共用。红线退回绿线、面板一起收起。
            // 只在确实有过灾害时才提示，否则每条无风险的 SSE 事件都会报一次「已解除」刷屏。
            const hadHazard = !!this._hazardNotifyKey || (this.risks || []).length > 0
            this.rerouteOptions = []
            this.waitDispatch = null
            this._hazardNotifyKey = ''
            if (hadHazard) {
              this.risks = []
              this.showPush('ok', '风险解除', data.advice || '沿线风险已解除，通行条件恢复正常。')
            }
            return
          }
          // 未接单的普通用户：气象/调度控制不得自动改变其导航路线。
          // 灾害到达时自动弹出可绕行路线面板，由司机自行点选是否换线——绝不自动换线。
          if (!this.isTaskAccepted) {
            this.loadRerouteOptions(reason)
            return
          }
          // 已接单的物流司机：**不自动绕路**。
          // 换线权在调度中心（原来这里会走 _tryDetour 局部绕行 / _applyAgentRoute 整条换线 /
          // selectedKey≠recommended 时重新拉取路线，三条路都会让车在司机没点的情况下自己改道），
          // 现在只标红 + 警告 + 语音播「正在等待调度中心」，并挂出等待卡；
          // 调度下发经 outreach-update 到达（见 _loadTaskChange），那才是唯一的换线入口。
          // _tryDetour / _applyAgentRoute 方法本体保留未删，恢复自动绕行只需把这一行换回去。
          this._armWaitDispatch(reason)
        } catch (e) {
          console.error('agent event parse failed', e)
        }
      })
      // === 双模式：公司派单 ===
      // 派单到达 → 司机端从「普通导航模式」自动切到「物流任务模式」（不再手填车牌货物）
      this._agentEs.addEventListener('task-assigned', (ev) => {
        try {
          const data = JSON.parse(ev.data)
          const t = data && data.task
          if (t) {
            this._enterLogisticsMode(t)
          } else {
            this._exitLogisticsMode(true)
          }
        } catch (e) {
          console.error('task-assigned parse failed', e)
        }
      })
      // === 调度大屏功能对齐：任务变更触达 ===
      // 调度员确认方案 → outreach-update 广播 → 司机端实时收到任务变更通知（震动+语音+卡片）
      this._agentEs.addEventListener('outreach-update', (ev) => {
        try {
          const status = JSON.parse(ev.data)
          const hadTask = !!this.taskChange
          this.outreachStatus = status
          // 未接单闸门：调度员在大屏点"确认切换方案·下发任务变更指令"，
          // 对还没接单的司机不应产生任何效果——不提醒、不切路线、不自动导航。
          // 顺带清掉旧的 taskChange，避免"待接单阶段残留的变更卡片"被误确认。
          if (!this.isTaskAccepted) {
            this.taskChange = null
            this.showDispatchConfirm = false
            return
          }
          // 找本人角色对应的目标（越方司机窗口取 DRIVER_VN），
          // 拉取任务变更消息（含越南语与权益保障包）
          const driver = (status.targets || []).find(t => t.role === this.driverRole)
          if (driver) {
            this._loadTaskChange(driver, hadTask)
          } else if (!status.total) {
            // 批次被清空（调度端重置），同步清空
            this.taskChange = null
            this.activePlanId = null
          }
        } catch (e) {
          console.error('outreach-update parse failed', e)
        }
      })
      // === 平陆运河禁航反馈（双向切换场景 B 闭环）===
      // 沙盘禁航状态翻转时后端广播 canal-block / canal-recover：
      // 物流任务司机只提示「等调度大屏换线」（换线控制权在调度，经 outreach-update 到达）；
      // 普通用户自助弹出公路替代路线面板（点选即换）
      this._agentEs.addEventListener('canal-block', (ev) => {
        try { this._onCanalBlock(JSON.parse(ev.data)) } catch (e) { console.error('canal-block parse failed', e) }
      })
      this._agentEs.addEventListener('canal-recover', (ev) => {
        try { this._onCanalRecover(JSON.parse(ev.data)) } catch (e) { console.error('canal-recover parse failed', e) }
      })
      this._agentEs.onerror = () => {
        // EventSource 自动重连，仅记录
        console.warn('agent SSE disconnected, will retry')
      }
    },
    /** 拉取司机本人的任务变更通知（第五幕：执行指令 + 权益保障包） */
    async _loadTaskChange(driverTarget, hadTask) {
      try {
        const { data } = await axios.get('/api/outreach/message', { params: { targetId: driverTarget.id } })
        // 新批次判定：目标 id 恒为 driver-1，不能用 id 判重；
        // 比较 批次时间戳 / 方案 id，调度端二次下发（B→A 改派）同样触发强提醒
        const prev = this.taskChange
        const isNew = !hadTask || !prev
          || prev.planId !== data.planId
          || (data.dispatchedAt && prev.dispatchedAt !== data.dispatchedAt)
        this.taskChange = data
        if (isNew) {
          // 调度指令已到达：等待卡完成使命，再挂在那就是重复信息
          this.waitDispatch = null
        }
        if (isNew && !driverTarget.confirmed) {
          if (this.navigating) {
            // 司机正在导航中：走「播报灾害 → 预览调度路线 → 播报天气 → 弹确认接收」编排
            this._handleDispatchDuringNav(data)
          } else {
            // 非导航：震动 + 语音播报 + 弹出提醒，并切到任务 tab
            this._notifyTaskChange(data)
          }
        }
      } catch (e) {
        console.error('load task change failed', e)
      }
    },
    /** 启动/重连时拉取当前运输任务：有任务则直接回到物流任务模式 */
    async _loadCurrentTask() {
      try {
        const { data } = await axios.get('/api/task/current')
        if (data && data.task) this._enterLogisticsMode(data.task)
      } catch (e) {
        /* 无任务或后端不可达：保持普通导航模式 */
      }
    },
    /**
     * 进入物流任务模式（剧本第四幕）：车牌、货物、起终点一律以派单为准。
     * 注意与导航视图态（navigating/previewing，CSS class nav-mode）无关，
     * 这里切换的是"免费普通导航"与"公司物流任务"两种身份。
     */
    _enterLogisticsMode(task) {
      // 拒单任务（本人或越方窗口拒单后的广播）：不进入物流模式，退回普通导航
      if (task && task.status === 'REJECTED') {
        this._exitLogisticsMode(true)
        return
      }
      const first = this.mode !== 'LOGISTICS'
      this.mode = 'LOGISTICS'
      this.task = task
      if (task.plate) this.cargo.plate = task.plate
      if (task.cargoName) this.cargo.name = task.cargoName
      if (task.weightT != null) this.cargo.weight = task.weightT
      if (task.temp) this.cargo.temp = task.temp
      // 越方司机窗口显示越方接力司机，中方窗口显示承运本人
      this.driverName = (this.driverRole === 'DRIVER_VN' && task.driverNameVn)
        ? task.driverNameVn
        : (task.driverName || this.driverName)
      if (task.originId) this.originId = task.originId
      if (task.destinationId) this.destinationId = task.destinationId
      if (task.originId) this.cargo.from = NODE_NAMES[task.originId] || task.originId
      if (task.destinationId) this.cargo.to = NODE_NAMES[task.destinationId] || task.destinationId
      if (first) {
        this.showPush('ok', '📋 已切换到物流任务模式',
          `${task.plate} · ${task.cargoName} ${task.weightT}t，起终点已按派单下发`)
        this.pushLog.unshift({
          time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
          text: `收到公司派单 ${task.taskId}`
        })
        if (this.tab !== 'task') this.tab = 'route'
        // 新派单到达：非导航态下自动弹出订单详情，司机直接预览并接单/拒单
        if (!this.navigating && !this.previewing && task.status !== 'ACCEPTED') {
          this.showOrderDetail = true
        }
        // 派单到达即后台预取候选：接单时直接复用，把等待从「点接单后」挪到「看订单时」
        if (!this.navigating && task.status !== 'ACCEPTED') this._prefetchTaskCandidates()
      }
    },
    /**
     * 派单到达时后台预取候选路线（不碰界面状态：只存原始响应）：
     * 同一起终点 2 分钟内复用；失败静默清空，接单时照常再请求一次。
     */
    _prefetchTaskCandidates() {
      const o = this.resolvedOriginId
      const d = this.resolvedDestinationId
      if (!o || !d) return
      const key = `${o}>${d}`
      const prev = this._taskCandPrefetch
      if (prev && prev.key === key && (prev.data || Date.now() - prev.at < 120000)) return
      // 占位（at=0 且无 data）：重复派单推送不再并发发一次
      this._taskCandPrefetch = { key, at: 0, data: null }
      axios.get('/api/route/candidates', {
        params: { originId: o, destinationId: d, cargoType: 'cold' },
        timeout: 30000
      }).then(r => {
        this._taskCandPrefetch = { key, at: Date.now(), data: (r && r.data) || null }
      }).catch(() => {
        this._taskCandPrefetch = null
      })
    },
    /** 司机确认接单：大屏派单面板实时由"待接单"变为"已接单"，随后按调度选定路线自动进导航 */
    async acceptTask() {
      if (!this.task || this.task.status === 'ACCEPTED' || this.taskAccepting) return
      this.taskAccepting = true
      try {
        const { data } = await axios.post('/api/task/accept', {
          taskId: this.task.taskId, driverName: this.driverName
        })
        if (data && data.task) this.task = data.task
        this.showPush('ok', '已接单', `${this.task.taskId} 已接单，正在按调度路线开始导航…`)
        this.showOrderDetail = false
        // 接单即按调度员选定的路线自动进入导航
        await this._startTaskNavigation()
      } catch (e) {
        console.error('accept task failed', e)
      } finally {
        this.taskAccepting = false
      }
    },
    /** 司机拒单：任务置 REJECTED，退回普通导航模式 */
    async rejectTask() {
      if (!this.task || this.taskRejecting) return
      this.taskRejecting = true
      try {
        await axios.post('/api/task/reject', { taskId: this.task.taskId, driverName: this.driverName })
        this.showOrderDetail = false
        this._exitLogisticsMode(false)
        this.showPush('info', '已拒单', '已拒绝该运单，退回普通导航模式')
      } catch (e) {
        console.error('reject task failed', e)
      } finally {
        this.taskRejecting = false
      }
    },
    /**
     * 接单后按调度员选定的路线自动进入导航：
     * 以任务起终点拉候选，选中 routeChoice 对应的那条，再走标准预览→导航流程。
     */
    async _startTaskNavigation() {
      const t = this.task
      if (!t) return
      this.canalOption = null
      this.selectedMode = 'road'
      if (t.originId) this.originId = t.originId
      if (t.destinationId) this.destinationId = t.destinationId
      this.routeLoading = true
      let canalOpt = null
      // 派单到达时已在后台预取过（见 _prefetchTaskCandidates）：直接复用，省掉 7~18s 算路
      const pf = this._taskCandPrefetch
      const preKey = `${this.resolvedOriginId}>${this.resolvedDestinationId}`
      const prefetched = (pf && pf.data && pf.key === preKey && Date.now() - pf.at < 120000) ? pf.data : null
      try {
        const data = prefetched || (await axios.get('/api/route/candidates', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold' },
          timeout: 30000
        })).data
        this.candidates = this._mergeCorridorDuplicates((data && data.candidates) || [])
        canalOpt = (data && data.canalOption) || null
        // 派单到达时的全域受阻：调度员在大屏已人工确认过一次，这里只把结论告知司机，
        // 不拦住接单（接单与导航链路照旧），司机可随时点卡片上的返回/停靠
        this._applyCorridorAdvisory(data && data.advisory)
      } catch (e) {
        console.error('task route preload failed', e)
      } finally {
        this.routeLoading = false
      }
      // 调度员派的是「坐船出境」（陆水联运）：复用普通导航下司机主动选 canal 的同一条已验证链路
      // （先导航到南宁港六景上船，图上画出运河/海运/越南公路走廊）
      if (t.routeChoice === 'canal' && canalOpt) {
        this.canalOption = canalOpt
        this.selectedMode = 'canal'
        await this.startNavigation()
        return
      }
      if (!this.candidates.length) {
        this.showPush('warn', '路线数据异常', '未获取到调度路线，请在首页手动规划。')
        return
      }
      const want = t.routeChoice || 'recommended'
      const hit = this.candidates.find(c => c.key === want)
        || this.candidates.find(c => c.key === 'recommended')
        || this.candidates[0]
      if (hit) this.selectCandidate(hit.key, true)
      // 复用已验证的预览→导航路径（enterPreview 建图，startNavigation 带 choice 贯穿）
      await this.enterPreview()
      await this.startNavigation()
    },
    /** 节点 id → 中文名（订单详情展示用） */
    nodeName(id) {
      return NODE_NAMES[id] || id || '--'
    },
    /** 退出物流任务（演示复位）：撤销后端派单并退回普通导航模式 */
    async exitLogisticsMode() {
      try { await axios.post('/api/task/reset') } catch (e) { /* 静默 */ }
      this._exitLogisticsMode(false)
    },
    _exitLogisticsMode(silent) {
      const was = this.mode === 'LOGISTICS'
      this.mode = 'PUBLIC'
      this.task = null
      if (was && !silent) {
        this.showPush('info', '已退出物流任务模式', '退回普通导航模式，恢复免费导航功能')
      }
    },
    /** 任务变更到达的强提醒：震动 + 越南语/中文语音 + 顶部推送条 */
    _notifyTaskChange(msg) {
      if (navigator.vibrate) navigator.vibrate([200, 100, 200])
      this.showPush('warn', '📱 任务变更通知', '调度中心已切换方案，请前往「任务」页查看并确认接收。')
      this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text: '收到任务变更通知' })
      if (this.voiceOn) {
        const texts = this._taskVoiceTexts(msg)
        if (texts.length) this._interruptAndSpeak(texts)
      }
      // 不在导航中则自动切到任务 tab，让司机立即看到
      if (!this.navigating && !this.previewing) this.tab = 'task'
    },
    /**
     * 任务变更播报文本：固定「先中文、后越南语」两条，依次播完（_doSpeak 会按数组顺序念）。
     *
     * 两侧文案的「主次键」是相反的，这里统一取齐：
     *   中方司机 message=中文、messageVi=越语；
     *   越方司机 message=越语、messageZh=中文（见后端 driverMessages / driverMessagesVn）。
     * 原实现只播 `messageVi`，结果中方司机听到的是纯越南语、中文根本没念 —— 正是要修的点。
     */
    _taskVoiceTexts(msg) {
      if (!msg) return []
      const vnDriver = this.driverRole === 'DRIVER_VN'
      const zh = (vnDriver ? (msg.messageZh || msg.message) : msg.message) || ''
      const vi = (vnDriver ? msg.message : (msg.messageVi || msg.message)) || ''
      const texts = []
      if (zh) texts.push(this._taskTtsText(zh))
      // 只下发了单语文案时（越语与中文相同）不重复念一遍
      if (vi && vi !== zh) texts.push(this._taskTtsText(vi))
      return texts.filter(Boolean)
    },
    /** 任务通知 TTS 文本：抽取前几行，避免念完整长文 */
    _taskTtsText(full) {
      const lines = String(full).split('\n').filter(l => l.trim() && !l.trim().startsWith('·'))
      return lines.slice(0, 4).join('。')
    },
    /** 司机点击「确认接收」（第五幕）：确认即按调度方案自动进入导航 */
    async confirmTaskChange() {
      if (!this.taskChange || this.taskConfirming) return
      // 未接单闸门（防御式二重校验）：任务页残留的确认按钮同样不允许绕过接单去切路线
      if (!this.isTaskAccepted) {
        this.showDispatchConfirm = false
        this.taskChange = null
        return
      }
      // 在点击事件内预热：后续等待派单确认/路线计算后再播报时，浏览器仍允许语音输出
      this._warmUpSpeech()
      this.taskConfirming = true
      try {
        await axios.post('/api/outreach/confirm', { targetId: this.taskChange.id })
        this.taskChange.confirmed = true
        const planId = this.taskChange.planId
        // 导航中弹出的确认接收抽屉：确认后立即收起
        this.showDispatchConfirm = false
        // 预览阶段已完成路线切换/重算（导航中收到调度）→ 无需再次重算，直接进导航
        const previewed = this._dispatchPreviewPlanId && this._dispatchPreviewPlanId === planId
        this._dispatchPreviewPlanId = null
        this.showPush('ok', '已确认', '任务变更已确认接收，正在切换到调度路线导航…')
        if (!previewed) {
          // 从任务页确认：尚未切目的地，先按方案切换并重算路线（B=港区 / A=公路绕行 / C=仅提示等待）
          await this._switchNavForPlan(planId)
        }
        // 然后直接按调度路线进入导航（不再要求司机手动点"开始导航"），并触发导航开始播报
        // 必须 await：否则内部异常被吞、地图初始化失败时司机端停留在空白导航页
        if (planId !== 'C') await this._autoStartDispatchNav(planId)
      } catch (e) {
        console.error('confirm task failed', e)
        this.showPush('warn', '导航启动异常', '路线已确认但地图加载失败，请点击下方按钮重试。')
      } finally {
        this.taskConfirming = false
      }
    },
    /**
     * 导航中收到调度大屏下发的任务变更时的完整编排：
     * 1) 先播报当前路线的天气灾害（切路线前先抓住原因，重算会覆盖）；
     * 2) 切到调度大屏要调度的路线并进入预览，让司机先看清楚；
     * 3) 播报新路线对应的天气情况；
     * 4) 播报「原路线因…，正在为您规划最新安全路线」；
     * 5) 弹出「确认接收」抽屉，司机确认后由 confirmTaskChange 进入导航并触发导航开始播报。
     */
    async _handleDispatchDuringNav(msg) {
      if (this._dispatchHandling) return
      this._dispatchHandling = true
      const planId = msg && msg.planId
      // 后端据 sandbox 实际阻断态给出：这次「方案A」是风险解除后切回原路线，而不是新的绕行
      const cleared = !!(msg && (msg.riskCleared === true || msg.riskCleared === 'true'))
      // 切路线会重算 route/risks，灾害原因必须在切换前先取
      const hazardReason = this.latestHazardReason() || '前方路段出现气象灾害风险'
      const hazardWord = this.extractHazardKeyword(hazardReason)
      // 越南语播报落到具体灾害词（与 announceHazard 同一张映射表），不能直接念整段中文致灾原因
      const hazardVN = HAZARD_VN_MAP[hazardWord] || hazardWord
      try {
        // 1) 震动
        if (navigator.vibrate) navigator.vibrate([200, 100, 200])
        if (cleared) {
          // 风险解除、调度员下发原路线：这是「恢复正常」，绝不能套「气象灾害预警…正在重新规划」那句绕行话术。
          // 灾害简报优先沿用绕行时的快照；没有就按当前线路走廊 + 现存灾害词补一份（否则切回播报只能兜底成「沿线」）。
          if (!this._lastHazardBrief) {
            this._lastHazardBrief = {
              where: this.inferPortFromNodeIds(this.route && this.route.pathNodeIds),
              hazard: hazardWord
            }
          }
          // 解除播报统一放到路线切换完成后再念一次（见下方），此处只震动给即时反馈，
          // 避免「先念解除、随后又被天气播报打断」的重叠吞字
        } else {
          // 新风险绕行：快照「哪条走廊、什么灾害」触发了本次改道，供后续解除指令的切回播报点名引用
          this._lastHazardBrief = {
            where: this.inferPortFromNodeIds(this.route && this.route.pathNodeIds),
            hazard: hazardWord
          }
          this.showPush('danger', '⚠ 调度任务变更', '前方气象灾害，调度中心已下发新路线，正在为您切换到预览…')
          this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text: '导航中收到调度任务变更，进入路线预览' })
          if (this.voiceOn) {
            // force：调度下发的灾害预警同样优先于出发播报（出发信息改由队尾补读）
            this._interruptAndSpeak([
              `气象灾害预警。${hazardWord}。调度中心已介入，正在为您重新规划安全路线，请注意屏幕。`,
              `Cảnh báo thiên tai. ${hazardVN}. Trung tâm điều độ đã can thiệp, đang lên tuyến đường an toàn mới, vui lòng chú ý màn hình。`
            ], true)
          }
        }

        // C=原地等待：无新路线可预览，保持当前导航，仅弹出确认接收抽屉
        if (planId === 'C') {
          this._dispatchPreviewPlanId = null
          this.dispatchConfirmCollapsed = false
          this.showDispatchConfirm = true
          if (this.voiceOn) {
            this.speakQueue([
              `原路线因${hazardWord}，调度中心建议就近停靠安全区域等待，保持冷链机组运行。请在屏幕上确认接收指令。`,
              `Tuyến đường cũ bị ${hazardVN}. Trung tâm điều độ khuyên dừng xe tại khu vực an toàn gần nhất và chờ, giữ tổ máy lạnh hoạt động. Vui lòng xác nhận lệnh trên màn hình。`
            ])
          }
          return
        }

        // 2) 计算调度路线并进入预览（复用 _switchNavForPlan：切目的地 + 重算路线）
        await this._switchNavForPlan(planId)
        this._dispatchPreviewPlanId = planId
        this._enterDispatchPreview(planId)

        // 3) 拉取新路线沿线天气后再播报
        try { await this.loadWeatherForRoute() } catch (e) { /* 忽略：天气缺失不阻断播报 */ }
        if (cleared) {
          // 切换已完成：一次性播报「原路线X处Y风险已解除，调度中心已为您切换为原路线」
          this._announceRiskCleared({ viaDispatch: true })
        } else if (this.voiceOn) {
          const weather = this._buildStatusZh(false)
          const weatherVi = this._buildStatusVi(false)
          this.speakQueue([
            `已为您切换到调度推荐路线。${weather}`,
            `原路线因${hazardWord}，正在为您规划最新安全路线，请在屏幕上预览后确认接收。`,
            `Đã chuyển sang tuyến đường do trung tâm điều độ đề xuất. ${weatherVi}`,
            `Tuyến đường cũ bị ${hazardVN}, đang lên phương án an toàn mới, vui lòng xem trước và xác nhận trên màn hình。`
          ])
        }

        // 5) 弹出确认接收抽屉
        this.dispatchConfirmCollapsed = false
        this.showDispatchConfirm = true
      } catch (e) {
        console.error('handle dispatch during nav failed', e)
        // 兜底：直接弹确认接收抽屉，司机仍可确认（confirmTaskChange 会重新走 _switchNavForPlan）
        this._dispatchPreviewPlanId = null
        this.dispatchConfirmCollapsed = false
        this.showDispatchConfirm = true
      } finally {
        this._dispatchHandling = false
      }
    },
    /**
     * 进入「调度路线预览」：把 _switchNavForPlan 重算好的 this.route 作为唯一候选，
     * 从跟随视角的导航态切到上下分栏的预览态（暂停 GPS/守护轮询，确认后由 _autoStartDispatchNav 重开）。
     */
    _enterDispatchPreview(planId) {
      // 暂停上一程导航的 GPS/模拟行驶与守护轮询（确认接收后 _autoStartDispatchNav 会重新开启）
      this._stopRealTimeGPS()
      if (this.riskTimer) { clearInterval(this.riskTimer); this.riskTimer = null }
      this._navZoomReady = false
      if (this._navZoomTimer) { clearTimeout(this._navZoomTimer); this._navZoomTimer = null }
      // 用调度路线构造单一候选，进入预览模式
      this.selectedKey = 'recommended'
      this.candidates = [{
        key: 'recommended',
        coords: this.route.pathCoords || [],
        edgeIds: this.route.pathEdgeIds || [],
        edgeSpans: this.route.pathEdgeSpans || [],
        nodeIds: this.route.pathNodeIds || [],
        label: planId === 'B' ? '陆水联运（调度路线）' : '公路方案（调度路线）',
        via: this.viaText,
        hours: this.route.estimatedHours,
        distanceKm: this.route.totalDistanceKm,
        riskCount: this.pathRisks.length,
        hazardProbability: -1
      }]
      this.navigating = false
      this.previewing = true
      this.previewCollapsed = false
      this.showNavMenu = false
      this.navigationStartedAt = 0
      // 由全屏导航切到上下分栏预览：地图容器尺寸变化，需重量测后全览整条调度路线
      this.$nextTick(() => {
        if (this.map) {
          this.map.invalidateSize()
          this.drawRoute()
          this.fitRoute(false)
        }
      })
    },
    /** 收起/关闭导航中弹出的确认接收抽屉（保留任务变更，可稍后从任务页确认） */
    dismissDispatchConfirm() {
      this.showDispatchConfirm = false
    },
    /**
     * 按调度方案联动导航（双向切换闭环）：
     * B=陆水联运 → 导航目标切到南宁港六景作业区（路网节点 LJ，南宁以东郁江畔）；
     * A=公路绕行 → 恢复原目的地重规划（友谊关熔断已注入，Dijkstra 自动绕行芒街）；
     * C=原地等待 → 保持当前路线，仅提示。
     *
     * 注意：这里曾经用凭祥 PX 当"港区代理锚点"，但凭祥是越南边境口岸（友谊关所在），
     * 在南宁西南约 170km，方向与六景作业区完全相反 —— 司机确认水运方案后
     * 会被导航到边境而不是港区。现已为港区补了真实节点 LJ。
     */
    async _switchNavForPlan(planId) {
      // 落库当前方案：播报/状态卡据此判断是否走平陆运河（水运）
      this.activePlanId = planId
      // 记录换线前的整条在跑路线与车辆实际位置：后端算路起点只能吸附到路网节点，
      // 而节点间距可达上百公里——若吸附到车前方的节点，新路线会从车前方起画，
      // 车标又被 _reanchorCarTo 甩过去，表现就是「切换后起点跳了好多距离」。
      // 拿到新路线后用 _stitchRouteFromCar 把「车当前位置 → 该起点节点」的当前路线段
      // 拼到新路线头部，让新线首点严格等于停车位置（起点不再前跳）。
      const prevRouteCoords = (this.route && this.route.pathCoords) || []
      const prevRouteEdgeIds = (this.route && this.route.pathEdgeIds) || []
      const prevRouteSpans = (this.route && this.route.pathEdgeSpans) || []
      const carPos = this._meMarker ? this._meMarker.getLatLng() : null
      // 首次切方案前记住司机原行程目的地，供 B→A 改派时恢复
      if (!this._planSavedDestination) this._planSavedDestination = this.destinationId
      if (planId === 'C') {
        this.showPush('info', '调度指令', '双线风险，请就近停靠安全区域等待，保持冷链机组运行。')
        // 派的是「原地等待」：导航不换线，但车必须真的停下，否则车标继续沿路线爬
        this._applyWaitHold()
        return
      }
      // 改派 A/B：只清停驶标记，不在此恢复模拟行驶 —— 下面会换线重算并由
      // _autoStartDispatchNav 重新起模拟；此时若先 resume，定时器会带着旧路线的
      // _simCum 跳新路线（长度不匹配 → _simPointAt 返回 null → 车反而永久卡住）
      this.waitHold = false
      const target = planId === 'B' ? 'LJ' : this._planSavedDestination
      // 抑制 destinationId watcher 中的 _resetCandidates：
      // 调度切方案会改目的地，但不应清空已有路线/销毁地图，
      // 否则后续 _autoStartDispatchNav 因 route 为空直接 return，司机看不到地图。
      // 注意：Vue 3 watcher 是异步队列刷新（pre-flush），不能在同步代码里立即重置 flag，
      // 必须等到第一个 await 之后（watcher 已在微任务队列中执行完毕）再清除。
      this._switchingPlan = true
      this.destinationId = target
      try {
        // 重新注册 Agent 活跃任务（目的地已变，守护目标同步切换）；
        // 切公路方案时顺手停掉去六景港的运河模式关注，避免禁航事件误点名已换线的行程
        axios.post('/api/agent/register', null, {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, mode: planId === 'B' ? 'canal' : 'road' }
        }).catch(() => {})
        if (planId !== 'B') {
          axios.post('/api/agent/unregister', null, {
            params: { originId: this.resolvedOriginId, destinationId: 'LJ' }
          }).catch(() => {})
        }
        const plan = await axios.get('/api/route/plan-with-weather', {
          // 调度换线也按「当前位置」重算，与自助换线（fetchRoute/loadRerouteOptions 用 _navOriginId）保持一致：
          // 此处 navigating 仍为 true（_enterDispatchPreview 尚未把状态切走），_navOriginId 会吸附到车当前所在节点；
          // 非导航态下它自动回退到行程起点，行为不变。
          params: { originId: this._navOriginId(), destinationId: this.resolvedDestinationId, cargoType: 'cold', withAi: false },
          timeout: 30000
        })
        // await 返回后 Vue 已刷新 watcher 队列，此时安全解除抑制标志
        this._switchingPlan = false
        const resp = plan.data.route || plan.data
        // 关键：路线接口 await 期间模拟行驶定时器仍在推进（navigating 要到 _enterDispatchPreview
        // 才置假），车已沿旧路线向前开了 100~250m（SIM_SPEED 50m/s × 数秒）。若沿用 await 前
        // 快照的 carPos/prevRoute*，拼出来的新线首点会落在车「后方」——预览期车标就悬在新线之外，
        // 确认后 _reanchorCarTo 又把车甩回线首、再沿折返桥接段倒回去，表现就是「切到南宁港先离开
        // 路线乱转一阵、才回到线上」。与 fetchRoute / SSE 拼接同一口径：拼接前重新读取车辆实时
        // 位置与当前（已被 _trimPassedRoute 修剪过的）旧线几何，让新线首点严格等于此刻车位置。
        const carPosNow = this._meMarker ? this._meMarker.getLatLng() : carPos
        const prevCoordsNow = (this.route && this.route.pathCoords) || prevRouteCoords
        const prevEdgeIdsNow = (this.route && this.route.pathEdgeIds) || prevRouteEdgeIds
        const prevSpansNow = (this.route && this.route.pathEdgeSpans) || prevRouteSpans
        // 把新路线起点精确对齐到车辆当前位置（详见 _stitchRouteFromCar）
        this._stitchRouteFromCar(resp, carPosNow, prevCoordsNow, prevEdgeIdsNow, prevSpansNow)
        if (resp._sameLineKeep) {
          // 调度下发的新方案与当前线几何一致（如 A→A 重算）：保留现线几何与剩余里程，
          // 不让车标重吸附到微抖后的顶点；风险/ETA 等非几何字段照常替换
          const cur = this.route || {}
          if (cur.pathCoords && cur.pathCoords.length > 1) {
            resp.pathCoords = cur.pathCoords
            resp.pathEdgeIds = cur.pathEdgeIds
            resp.pathEdgeSpans = cur.pathEdgeSpans
            resp.pathNodeIds = cur.pathNodeIds
            resp.totalDistanceKm = cur.totalDistanceKm
            resp.estimatedHours = cur.estimatedHours
          }
        }
        this._auditSwap('switchNavForPlan-' + planId)
        this.route = resp // watcher 自动重绘地图
        this.risks = plan.data.risks || this.risks
        this.online = true
        this._syncTripProgress()
        // 途经摘要必须跟随新路线重算：换线后上一条走廊文案（如友谊关公路走廊）绝不能残留，
        // 否则「陆水联运」卡片下面还写着「崇左→凭祥→友谊关」，图文自相矛盾。
        // B 方案首程是 南宁→六景港 国内段，几乎无中间命名节点，_viaFromNodes 会返回空，
        // 此时直接用运河走廊文案；A 方案（公路绕行）取绕行线途经节点，取不到则清空而非留旧。
        let via = (resp.pathNodeIds && resp.pathNodeIds.length) ? this._viaFromNodes(resp.pathNodeIds) : ''
        if (planId === 'B') {
          via = (this.canalOption && this.canalOption.via)
            || '南宁港六景作业区（上船）→ 平陆运河 → 钦州港 → 海防'
        }
        this.viaText = via
        this.$nextTick(() => { if (this.map && resp.pathCoords) this.fitRoute(false) })
        if (planId === 'B') {
          this.showPush('info', '导航已更新',
            '陆水联运（平陆运河）：请按新路线前往南宁港六景作业区交接车辆，之后随船经平陆运河转海运至越南海防港。')
          this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text: '导航目标已切换：南宁港六景作业区' })
        } else {
          this.showPush('info', '导航已更新', `公路方案：新路线已下发（${this.routeTitle}），按导航行驶。`)
          this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text: '已切换公路绕行方案，路线已重算' })
        }
      } catch (e) {
        this._switchingPlan = false
        console.error('plan nav switch failed', e)
        this.showPush('warn', '路线更新失败', '网络异常，请稍后手动重新规划。')
      }
    },
    /**
     * 确认调度指令后自动进入导航：复用 _switchNavForPlan 已重算好的 this.route，
     * 跳过候选路线搜索，直接进入跟随视角（全览 → 拉满缩放跟随车辆）。
     */
    async _autoStartDispatchNav(planId) {
      if (!this.route || !this.route.pathCoords || !this.route.pathCoords.length) {
        console.warn('[dispatch-nav] route 无有效 pathCoords，无法进入导航', this.route)
        this.showPush('warn', '路线数据异常', '未获取到有效路线坐标，请返回重新规划。')
        return
      }
      try {
        // 换线重绘前先记录车辆当前实际位置：下面 _startRealTimeGPS 会走 _startTripSimulation
        // 把车标钉到新路线首点 coords[0]、里程归零；调度换线时车若已开到火车东站，
        // 不能因整图重建就把图标甩回金湖广场（路线走廊 南宁→河内 保持不变是允许的）。
        // 重建后用这个快照把车标重锚回当前位置（见 _reanchorCarTo）。
        const keepPos = this._meMarker ? this._meMarker.getLatLng() : null
        this._warmUpSpeech()
        // 选中推荐路线贯穿导航轮询（调度路线即推荐路线）
        this.selectedKey = 'recommended'
        this.candidates = [{
          key: 'recommended',
          coords: this.route.pathCoords,
          edgeIds: this.route.pathEdgeIds || [],
          edgeSpans: this.route.pathEdgeSpans || [],
          label: planId === 'B' ? '陆水联运（调度路线）' : '公路方案（调度路线）'
        }]
        this.navigationStartedAt = Date.now()
        this._mapHardRecovered = false
        this.navigating = true
        this.previewing = false
        this.previewCollapsed = false
        this.navFollowing = true
        this.navOffView = false
        this.showNavMenu = false
        await this.$nextTick()
        // 调度入口直接换一颗全新的地图 DOM：旧 Leaflet ID/pane/HMR 残留都没有机会复用
        this._destroyMap()
        this._mapRenderKey++
        await this.$nextTick()
        // 关键修复：$nextTick 只保证 DOM 已 patch，但浏览器可能尚未完成 layout。
        // 若此时 Leaflet 量测容器得到 0×0，瓦片不会加载 → 整屏空白。
        // 用 requestAnimationFrame 等到下一帧绘制前，确保容器已有真实尺寸。
        await new Promise(resolve => requestAnimationFrame(resolve))
        // 二次保险：若 ref 仍拿不到（极端情况），再等一帧
        if (!this.$refs.mapEl) {
          await new Promise(resolve => requestAnimationFrame(resolve))
        }
        this._scheduleMapHealthChecks()
        this.initMap()
        this._applyCoverageZoom()
        this._startRealTimeGPS()
        // 整图重建 + 从头起模拟会把车标放到新路线首点；这里立刻按换线前的实际位置重锚，
        // 保证「当前车辆图标」停在火车东站这类已行驶到的点，不跳回金湖广场。
        if (keepPos) this._reanchorCarTo(keepPos)
        // 出发播报前强制重算一次剩余时间：navigating 置真时 watcher 的 _updateLiveEta
        // 用的还是上一程残留的 _simDistM/_simTotalM，而播报紧跟在重锚之后、模拟首个 tick
        // 的 1s 限流窗口里拿数——念出来的耗时/到达时间是旧值，与卡牌（tick 后刷新）对不上。
        this._updateLiveEta(true)
        this._announceDepartureSafely()
        this._navZoomReady = false
        this.$nextTick(() => {
          if (this.map) this.map.invalidateSize()
          // 进入导航时重置居中偏移，让本次只重新量测一次（之后导航中冻结，避免漂移）
          this._navCenterOffset = null
          // 保留原来的「先全览 → 再放大跟随」节奏；延迟 1100ms → 420ms
          this.fitRoute(true)
          // 大幅视图变化后强制重投影：避免渲染器残留 scale / _bounds 偏差（线被放大成色块）
          this._resyncMapRenderer()
          this._navZoomTimer = setTimeout(() => {
            this._navZoomTimer = null
            if (keepPos && this._meMarker && this.map) {
              // 开场视角对准车辆当前位置（而非新路线首点 coords[0]）：
              // 否则相机先飞到金湖广场、再被自动跟随拽回，观感就是图标/视角闪回起点。
              this._centerOn(this._meMarker.getLatLng(), this._navMaxZoom(), 0, false)
              this._resyncMapRenderer()
              this._navZoomReady = true
              this.navFollowing = true
              this.navOffView = false
            } else {
              this.zoomToNav()          // 无位置快照（首次派单等）时退回放大到「模拟起点」
            }
          }, 420)
        })
        this.loadWeatherForRoute().catch(() => {})
        this.showPush('success', '已按调度路线导航',
          planId === 'B' ? '目标：南宁港六景作业区（陆水联运·平陆运河），Agent 已实时守护' : `路线：${this.routeTitle}，Agent 已实时守护`)
        if (this.riskTimer) clearInterval(this.riskTimer)
        this.riskTimer = setInterval(() => { this.refreshForecast(false) }, 480000)
        this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
          text: planId === 'B' ? '已确认调度指令，自动导航至南宁港六景作业区（陆水联运·平陆运河）' : '已确认调度指令，自动导航公路绕行路线' })
      } catch (e) {
        console.error('auto dispatch nav failed', e)
      }
    },
    /** 播放任务通知（中文/越南语切换） */  
    speakTask(lang) {
      if (!this.taskChange) return
      const text = lang === 'vi' ? (this.taskChange.messageVi || this.taskChange.message) : this.taskChange.message
      this._interruptAndSpeak([this._taskTtsText(text)])
    },
    /** AI 方案建议：拉取调度大屏同源的三方案对比（让司机理解决策依据） */
    async loadAgentPlans() {
      if (this.agentPlansLoading) return
      this.agentPlansLoading = true
      try {
        const { data } = await axios.get('/api/agents/analysis', {
          params: { originId: this.originId, destinationId: this.destinationId, scenarioId: 'heavy_rain' },
          timeout: 30000
        })
        this.agentPlans = data
      } catch (e) {
        console.error('agent plans failed', e)
      } finally {
        this.agentPlansLoading = false
      }
    },
    /**
     * 把刚收到的风险并进 this.risks —— 这是「路线立刻变红」的开关。
     *
     * 为什么单独一个方法：路线变红原本完全依赖 route.riskSegments，而它只在后端整条重算
     * 回来时才更新。接单模式不再自动换线后，这条更新链路被有意掐断，红线必须改由
     * this.risks 驱动（_riskEdgeSet 已并读它，watch risks → drawRoute 负责重绘）。
     * 按 edgeId 去重**合并**而非整体覆盖：不要顺手把其它廊道已标红的风险抹掉。
     */
    _markRouteRisky(risks) {
      const add = (risks || []).filter(x => x && x.edgeId)
      if (!add.length) return
      const cur = this.risks || []
      const seen = new Set(cur.map(x => String(x && x.edgeId)))
      const merged = cur.concat(add.filter(x => !seen.has(String(x.edgeId))))
      if (merged.length === cur.length) return
      this.risks = merged
    },
    /**
     * 收到灾害推送时**立刻**提示，不等后续「局部绕行 / 重新拉取路线」返回。
     * 按"当前灾害边集合"去重：同一批灾害只提醒一次，避免 SSE 重复推送刷屏；
     * 风险清空后重置去重键，这样同一场景关掉再打开能再次提醒。
     *
     * 一次灾害反馈要三处同时到位，少一处司机就觉得系统没反应：
     *   1) 变红 —— _markRouteRisky 把本线风险并进 this.risks，触发 drawRoute 重绘；
     *   2) 弹窗 —— showPush('danger')；
     *   3) 语音 —— 按模式给不同尾句。
     * 尾句必须与实际控制策略一致：接单模式已不自动绕路，就绝不能再念「正在为您绕行」，
     * 否则司机听一套、屏幕做一套。这里也不说「已上报调度中心」——后端没有司机→调度的
     * 上报端点，只能如实说「正在等待调度中心下发换线指令」。
     *
     * @param risksMine 已按当前路线 pathEdgeIds 过滤过的命中风险（可为空数组）
     */
    _notifyHazard(data, risksMine) {
      const mine = risksMine || []
      // 去重键同时吃灾害边与本线命中风险：只有 hazardEdgeIds 时，
      // 「风险已在这条线上但没被判定为熔断边」那种情况会被整体吞掉
      const ids = [...new Set(
        [].concat(data.hazardEdgeIds || [], mine.map(x => x && x.edgeId).filter(Boolean))
      )].map(String).sort()
      if (!ids.length) {
        this._hazardNotifyKey = ''
        return
      }
      const key = ids.join(',')
      if (key === this._hazardNotifyKey) return
      this._hazardNotifyKey = key
      // 先标红再弹窗：重绘排在 watcher 的下一帧，但红线一定赶在司机抬眼看地图之前
      this._markRouteRisky(mine.length ? mine : (data.risks || []))
      const reason = (mine.length ? mine[mine.length - 1].reason
        : ((data.risks || []).length ? data.risks[data.risks.length - 1].reason : ''))
        || '检测到路段气象风险'
      const port = this.inferPortFromNodeIds(this.route.pathNodeIds)
      const where = port ? `${port}方向` : '前方路段'
      const accepted = this.isTaskAccepted
      this.showPush('danger', '⚠ 前方灾害',
        `${where}：${reason}。本条路线已标红。`
        + (accepted
          ? '正在等待调度中心下发换线指令，请保持当前路线、减速慢行。'
          : '已为您准备绕行方案，请在屏幕上点选。'))
      this._hazardNotifiedAt = Date.now()
      // 强提醒：司机在路上主要靠听，震动 + 语音与任务变更提醒保持同一套处理。
      // 震动加到三段连击，比原来的 [200,100,200] 更难被当成误触忽略掉
      if (navigator.vibrate) navigator.vibrate([300, 120, 300, 120, 300])
      if (this.voiceOn) {
        const speak = reason.length > 50 ? reason.slice(0, 50) : reason
        // force：灾害预警优先于「导航开始」的独占播报，出发信息改由队尾补读，不再整段听不到
        this._interruptAndSpeak([
          `气象预警。${where}${speak}，当前路线已标为红色。`,
          accepted
            ? '正在等待调度中心下发换线指令，请保持当前路线，减速慢行，不要自行改道。'
            : '绕行方案已经准备好，请在屏幕上点选新的路线。'
        ], true)
      }
    },
    /**
     * 接单模式遇险的唯一处置：挂出「正在等待调度中心」状态卡。
     *
     * 幂等按 reason：同一条致灾原因重复进入（15 秒轮询兜底、SSE 重推）不重置 at，
     * 否则卡上的「已等待 X」会被轮询每秒清零，司机永远看不出自己已经等了多久。
     * 文案不写「已上报调度中心」：后端没有司机→调度的上报端点，这里只能描述真实状态——等指令。
     */
    _armWaitDispatch(reason) {
      const r = reason || '前方路段风险'
      if (this.waitDispatch && this.waitDispatch.reason === r) return
      this.waitDispatch = { reason: r, at: Date.now() }
      this.pushLog.unshift({
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        text: `前方灾害（${r}）：已标红本路线，不自动绕路，等待调度中心下发换线指令`
      })
    },
    /** 司机确认知道了，收起等待卡（不改路线、不解除风险，只是不再占屏） */
    dismissWaitDispatch() {
      this.waitDispatch = null
    },
    /**
     * 等待期间就近安全停靠：复用调度方案 C 的停驶实现（真停车，不只是 UI 写一行字）。
     * 不撤等待卡：卡片换成「恢复行驶」按钮，否则司机停完就找不到继续的入口了。
     */
    holdForDispatch() {
      this._applyWaitHold()
      this.showPush('warn', '已停驶等待', '车辆已在当前位置停稳，继续等待调度中心下发换线指令。')
    },
    /**
     * 等待卡上的「已等待」计时。
     *
     * 必须是方法不能是计算属性：computed 把 Date.now() 当普通依赖缓存住，
     * 即使 mounted 里 _clockTimer 每秒 $forceUpdate 也不会让它重算，
     * 表现就是“已等待 0 秒”永远不动。方法跟着渲染跑，才能真正走表。
     */
    waitDispatchElapsed() {
      const at = this.waitDispatch && this.waitDispatch.at
      if (!at) return ''
      const s = Math.max(0, Math.floor((Date.now() - at) / 1000))
      const m = Math.floor(s / 60)
      const sec = String(s % 60).padStart(2, '0')
      return m > 0 ? `${m} 分 ${sec} 秒` : `${s} 秒`
    },
    /**
     * 未开始导航（已选好线 / 目的地越南端）收到风险更新的处理：
     * 顶部给一个"前方路段出现风险"预警（不打断），并把已规划好的路线静默刷新为绕开风险的新路线，
     * 让司机上路前就知道路线已调整。风险清空后自动撤下预警。
     */
    _watchPreviewRisk(data) {
      // 没规划过路线（纯粹停在首页/导航页没选目的地）→ 不打扰
      if (!this.route || !this.route.pathCoords || !this.route.pathCoords.length) return
      // 调度路线预览期间（方案 B/A 已下发、等司机确认接收）：this.route 是按车位置
      // 拼接好的调度线，SSE 整线重算一律不得覆盖——风险激活态「确认后起点大跳」的真凶：
      // 换线注册新 O/D 后后端会广播一条按吸附节点起算的整线，直接覆盖即抹掉桥接段，
      // 确认时车被钉回节点（风险解除态没有这条广播，所以「解除后换线不跳」）。
      // 预览期只收风险着色、不改线；确认后由 fetchRoute 轮询按统一口径拼接刷新。
      if (this.previewing && this._dispatchPreviewPlanId) {
        if (data.risks) this.risks = data.risks
        return
      }
      // 预览已选定坐船：推送的更新路线是公路 O/D 的，不能把上船方案图覆盖回公路线（可能经芒街）；
      // 同时作废上船段缓存，下次点选时按最新风险重拉
      if (this.previewing && this.selectedMode === 'canal') {
        this._canalRoadKey = null
        this._canalRoadRoute = null
        return
      }
      const hasRisk = !!(data.risks && data.risks.length) || !!(data.hazardEdgeIds && data.hazardEdgeIds.length)
      // 后端推送的 data.route 是按路网节点起算的整线：调度换线预览期间（navigating 已置 false）
      // 它会把 _switchNavForPlan 拼好的「车→节点」桥接段整个抹掉，
      // 确认接收时 _autoStartDispatchNav 把车钉到 coords[0]=起点节点 → 确认后起点大跳。
      // 应用前先按车辆当前位置拼接/截断对齐（与 fetchRoute 同一口径）。
      const _stitchIntoRoute = (r) => {
        const prev = this.route || {}
        this._stitchRouteFromCar(r, this._meMarker ? this._meMarker.getLatLng() : null,
          prev.pathCoords || [], prev.pathEdgeIds || [], prev.pathEdgeSpans || [])
        return r
      }
      if (hasRisk && data.route && data.route.pathCoords) {
        const reason = (data.risks && data.risks.length ? data.risks[data.risks.length - 1].reason : '') || '沿线出现气象风险'
        this._auditSwap('sse-preview-risk-hit')
        const stitched = _stitchIntoRoute(data.route)
        this.risks = data.risks || []
        if (!stitched._sameLineKeep) {
          this.route = stitched
          this._syncTripProgress()
        }
        this.showPush('warning', '⚠ 前方路段风险', `${reason}。您规划的路线已重新计算（绕开风险段），出发前请查看。`)
      } else if (!hasRisk) {
        // 风险清空：静默刷新为常态路线，撤下预警
        if (data.route && data.route.pathCoords) {
          this._auditSwap('sse-preview-risk-clear')
          const stitched = _stitchIntoRoute(data.route)
          this.risks = []
          if (!stitched._sameLineKeep) {
            this.route = stitched
            this._syncTripProgress()
          }
        }
      }
    },
    /** 将 Agent 推送的全程重算路线应用到当前导航（回退逻辑） */
    _applyAgentRoute(data) {
      this._auditSwap('applyAgentRoute')
      // 与轮询/SSE 同一口径：外部整线一律先对齐到车辆当前位置再采纳。
      // 不拼接就是一条从路网节点起算的新线，线头可能离车几十公里，
      // 下一帧 tether 把它拽到车标上就是一条横穿城区的斜弦（穿楼/穿湖）
      this._alignIncomingRoute(data.route)
      if (data.route && data.route._sameLineKeep) {
        this.risks = data.risks || this.risks
        this.online = true
      } else {
        this.route = data.route
        this.risks = data.risks || []
        this.online = true
        this._syncTripProgress()
      }
      const pushed = this.checkPush(data.route)
      // 刚在 SSE 入口提示过「前方灾害」，此处不要再用较弱的「Agent 实时更新」把它盖掉
      const justNotified = this._hazardNotifiedAt && (Date.now() - this._hazardNotifiedAt) < 8000
      if (!pushed && !justNotified) {
        this.showPush('info', 'Agent 实时更新',
          (data.advice || '路线已按最新风险重算。') + `（${new Date(data.ts || Date.now()).toLocaleTimeString('zh-CN', { hour12: false })}）`)
      }
    },
    /**
     * 路线被替换（局部绕行/全程重算/调度切换）后同步模拟行驶进度：
     * 新路线是从车辆当前位置起算的，模拟游标必须重置到最近点，
     * 否则旧的 _triIdx 会套到新坐标数组上导致车辆瞬间跳点。
     */
    _syncTripProgress() {
      if (!this._triTimer) return
      const pos = this._meMarker ? this._meMarker.getLatLng() : null
      const coords = this.route && this.route.pathCoords
      if (!coords || coords.length < 2) return
      // 先把里程表按新路线重建：即使拿不到车标位置也要让 _simCum 与新 coords 对齐，
      // 否则 _simPointAt 会因 cum.length !== coords.length 恒返回 null，车标永久冻住。
      const cum = [0]
      for (let i = 1; i < coords.length; i++) {
        cum.push(cum[i - 1] + this._distM(coords[i - 1], coords[i]))
      }
      this._simCum = cum
      this._simTotalM = cum[cum.length - 1]
      if (!pos) {
        // 无车标：把里程钳制到新路线范围内（路线变短时旧 _simDistM 可能越界），不回起点
        this._triIdx = 0
        this._simDistM = Math.min(this._simDistM || 0, this._simTotalM)
        return
      }
      // 吸附到**垂足**而非最近顶点：稀疏长边（顶点间距可达几公里）下按顶点吸附，
      // 车标会沿线方向一次跳掉半条边；更糟的是线头仍停在原处，从这一帧起
      // _tetherRouteHead 会把绘线首点硬拽到车标，画出一条横跨几公里的斜弦——
      // 用户看到的「有时不按路线走、直接穿过建筑物」就是它。
      const near = this._nearestOnPolyline(coords, pos)
      this._triIdx = near.idx
      const segM = this._distM(coords[near.idx], coords[Math.min(near.idx + 1, coords.length - 1)])
      this._simDistM = (cum[near.idx] || 0) + near.t * segM
      // 线头与车标只要还错着位就当场剪齐（不等 40m 修剪阈值）：剪完后 coords[0]
      // 严格等于车位置，任何一帧都不再有可能被拽成斜弦的线首
      if (this._simDistM > SIM_SPEED_MPS * 1.6) {
        if (this._meMarker && this._meMarker._map === this.map) this._meMarker.setLatLng(near.proj)
        this._trimPassedRoute()
      }
    },
    /**
     * 换线/整图重建后把车辆图标与模拟里程**重锚到给定位置**（而非新路线首点）。
     * 用于调度换线：路线走廊可以整体不变（南宁→河内），但代表当前位置的车标不能因为
     * 整图重建 + _startTripSimulation 从头起，就被甩回路线起点（金湖广场）。
     * 取新路线上离 pos 最近的点作为当前里程，车标落在那里，模拟从此续跑。
     */
    _reanchorCarTo(pos) {
      const coords = this.route && this.route.pathCoords
      if (!coords || coords.length < 2 || !pos) return
      // 里程表须已由 _startTripSimulation 建好；缺失就地重建，保证 _simPointAt 能取点
      if (!this._simCum || this._simCum.length !== coords.length) {
        const cum = [0]
        for (let i = 1; i < coords.length; i++) cum.push(cum[i - 1] + this._distM(coords[i - 1], coords[i]))
        this._simCum = cum
        this._simTotalM = cum[cum.length - 1]
      }
      // 同 _syncTripProgress：按垂足而非最近顶点重锚，避免稀疏长边下整段跳点
      const near = this._nearestOnPolyline(coords, pos)
      this._triIdx = near.idx
      const segM = this._distM(coords[near.idx], coords[Math.min(near.idx + 1, coords.length - 1)])
      this._simDistM = (this._simCum[near.idx] || 0) + near.t * segM
      const p = this._simPointAt(this._simDistM) || near.proj
      if (this._meMarker && this._meMarker._map === this.map) this._meMarker.setLatLng(p)
      else this._placeTriMarker(p[0], p[1])
      // 车停在路线中段（调度换线、整图重建恢复）时把已走过的线头剪掉：
      // 留着它，下一帧 tether 就会把线首拽到车标上、拉出一条穿楼斜弦
      if (this._simDistM > SIM_SPEED_MPS * 1.6) this._trimPassedRoute()
    },
    /** 尝试用当前 GPS 位置做局部绕行，只替换灾害段 */
    async _tryDetour(data) {
      const pos = this._meMarker ? this._meMarker.getLatLng() : null
      if (!pos) {
        this._applyAgentRoute(data)
        return
      }
      const ratio = this._computeProgressRatio(pos.lat, pos.lng)
      const body = {
        currentRouteEdgeIds: this.route.pathEdgeIds || [],
        currentRouteNodeIds: this.route.pathNodeIds || [],
        hazardEdgeIds: data.hazardEdgeIds || []
      }
      const r = await axios.post('/api/route/detour', body, {
        params: { destinationId: this.resolvedDestinationId, progressRatio: ratio }
      })
      if (!r.data || !r.data.route) {
        this._applyAgentRoute(data)
        return
      }
      const detour = r.data.route
      this._auditSwap('tryDetour')
      // 局部绕行线同样从灾害段两端节点起算，采纳前先拼回车当前位置
      this._alignIncomingRoute(detour)
      if (detour._sameLineKeep) {
        this.risks = r.data.risks || data.risks || this.risks
        this.online = true
      } else {
        this.route = detour
        this.risks = r.data.risks || data.risks || []
        this.online = true
        this._syncTripProgress()
      }
      const delayInfo = detour.extraHours > 0 ? '（延误 ' + detour.extraHours.toFixed(1) + 'h）' : ''
      const ts = new Date(data.ts || Date.now()).toLocaleTimeString('zh-CN', { hour12: false })
      // 类型必须是 CSS 里定义过的（info/success/warn/danger）；
      // 原来写的 'warning' 没有对应样式，提示会渲染成一条没有底色的白条，几乎看不见
      this.showPush(detour.rerouted ? 'warn' : 'info', '局部绕行避灾',
        `⚠ 前方灾害，已从当前位置局部绕行${delayInfo}（${ts}）`)
    },
    /** 根据 GPS 位置计算在路线上的进度比例 (0~1)，按里程而非点数 */
    _computeProgressRatio(lat, lng) {
      const coords = this.route && this.route.pathCoords
      if (!coords || coords.length < 2) return 0.3
      // pathCoords 是 [lat,lng] 数组（后端 List<double[]>）。老实现按 c.lat/c.lon 对象读，
      // 所有点都被 continue 跳过、minIdx 恒为 0，进度永远钉在下限 ——
      // 在途重算/绕行的起点节点因此一直按行程起点附近算，和车真实位置脱钩。
      let minDist = Infinity
      let minIdx = 0
      for (let i = 0; i < coords.length; i++) {
        const c = coords[i]
        if (!c || c.length < 2) continue
        const d = Math.hypot(c[0] - lat, c[1] - lng)
        if (d < minDist) { minDist = d; minIdx = i }
      }
      // 比例按累计里程换算：点密度不均（城市密、山区稀），按点数占比会错位。
      // 模拟行驶的累计里程表在跑就直接复用，与车辆推进同一口径
      let cum = this._simCum
      if (!cum || cum.length !== coords.length) {
        cum = [0]
        for (let i = 1; i < coords.length; i++) {
          cum.push(cum[i - 1] + this._distM(coords[i - 1], coords[i]))
        }
      }
      const total = cum[cum.length - 1] || 1
      return Math.max(0, Math.min(1, cum[minIdx] / total))
    },
    /**
     * 已有“当前位置”标记且当前路线有节点时，返回车辆当前所在的路网节点作为算路起点，
     * 让绕行/重算/换线从当前位置起画，而不是从行程起点重画整条线（A 方案）。
     * 无定位标记 / 无路径节点时，回退行程起点 resolvedOriginId。
     * （不再以 navigating 为闸门：调度换线确认时 navigating 已被预览态置 false，
     * 若仍按 navigating 判据会误回退到出发地，配合 _switchNavForPlan 的起点拼接使用。）
     */
    _navOriginId() {
      // 只要「有车标 + 有路径节点」就按车辆当前位置吸附算路起点，不再要求 navigating===true：
      // 调度换线的确认链路里 navigating 会被 _enterDispatchPreview 提前置 false，
      // 若仍按 navigating 判据就会回退到行程出发地（resolvedOriginId），
      // 导致从任务页确认 / 异常兜底后确认时，换线起点整条跳回最初出发地。
      const pos = this._meMarker ? this._meMarker.getLatLng() : null
      const nodes = this.route && this.route.pathNodeIds
      if (!pos || !nodes || nodes.length < 2) return this.resolvedOriginId
      // 用 pathEdgeSpans 把车标吸附到**当前所在的边**（spans[i] 是第 i 条边在 pathCoords
      // 中的下标区间）。边 i 连接 nodes[i]→nodes[i+1]：算路起点取这条边的**哪个端点，
      // 由车离哪个端点更近决定**，不能无条件取前方 nodes[i+1]：
      // · 车已走到边终点（火车东站）时取终点 —— 不能退回已走过的起点（金湖广场）；
      // · 车刚从边起点出发（在南宁刚点开始导航）时取起点 —— 否则南宁→崇左这种
      //   上百公里的长边会把算路起点整个推到下一个节点，15s 轮询重算后
      //   _syncTripProgress 把车标吸附到新路线起点，表现为「车标突然跳到崇左」。
      // 老实现把进度比例按点数折算到节点列表 —— 节点间距极不均匀
      // （南宁→芒街一条边就几百公里），刚跑一点起点就会被吸附到远处的口岸，
      // 表现为路线「一下子跳到芒街」。
      const coords = this.route.pathCoords || []
      const spans = this.route.pathEdgeSpans || []
      if (coords.length > 1 && spans.length) {
        let minDist = Infinity
        let m = 0
        for (let i = 0; i < coords.length; i++) {
          const c = coords[i]
          if (!c || c.length < 2) continue
          const d = Math.hypot(c[0] - pos.lat, c[1] - pos.lng)
          if (d < minDist) { minDist = d; m = i }
        }
        for (let i = 0; i < spans.length; i++) {
          const sp = spans[i]
          // 车落在边 i 内 → 比较车到边两端坐标的距离，就近取 nodes[i] 或 nodes[i+1]；
          // 上限 nodes.length-2 保证临到终点仍保留至少一条边
          if (sp && m >= sp[0] && m <= sp[1]) {
            const a = coords[sp[0]]
            const b = coords[Math.min(sp[1], coords.length - 1)]
            const dA = a && a.length >= 2 ? Math.hypot(a[0] - pos.lat, a[1] - pos.lng) : Infinity
            const dB = b && b.length >= 2 ? Math.hypot(b[0] - pos.lat, b[1] - pos.lng) : Infinity
            const idx = dB < dA ? i + 1 : i
            return nodes[Math.min(idx, nodes.length - 2)]
          }
        }
        // 车在最后一条边终点之后（临到终点）：取倒数第二个节点，留一条边的余量
        return nodes[Math.min(spans.length, nodes.length - 2)]
      }
      const ratio = this._computeProgressRatio(pos.lat, pos.lng)
      const idx = Math.min(nodes.length - 2, Math.max(0, Math.round(ratio * (nodes.length - 1))))
      return nodes[idx] || this.resolvedOriginId
    },
    /**
     * 调度换线时把新路线的起点精确对齐到车辆当前位置。
     *
     * 后端 Dijkstra 只能从路网节点起算，而节点间距可达上百公里：_navOriginId 吸附到的
     * 起点节点可能在车前方几十公里，直接用它会让新路线从车前方起画、车标被 _reanchorCarTo
     * 一起拽过去（表现为「切换后起点跳了好多距离」）。这里把「车辆当前位置 → 该起点节点」
     * 这一段**当前正在跑的路线**（起点节点本就取自当前路线，所以这段真实存在）拼到新路线头部，
     * 让新线首点严格等于车当前位置；新路线各边的 spans 按拼接长度整体位移，保持风险着色对齐。
     */
    /**
     * 点到折线的最近位置：逐段算投影（经纬度按 cos(lat) 折算成近似米平面），
     * 返回 { idx: 所在线段起点下标, t: 线段参数[0,1], proj: 垂足坐标, distM: 垂距米 }。
     * 拼接/截断判「车在不在走廊上」必须用垂距：顶点距离受稀疏路网间距支配，会误判。
     */
    _nearestOnPolyline(coords, pos) {
      let best = { idx: 0, t: 0, proj: [coords[0][0], coords[0][1]], distM: Infinity }
      const cosLat = Math.cos(pos.lat * Math.PI / 180)
      for (let i = 0; i + 1 < coords.length; i++) {
        const a = coords[i], b = coords[i + 1]
        if (!a || !b || a.length < 2 || b.length < 2) continue
        const dx = b[0] - a[0]
        const dy = (b[1] - a[1]) * cosLat
        const len2 = dx * dx + dy * dy
        let t = len2 > 0 ? ((pos.lat - a[0]) * dx + (pos.lng - a[1]) * cosLat * dy) / len2 : 0
        t = t < 0 ? 0 : (t > 1 ? 1 : t)
        const px = a[0] + dx * t
        const py = a[1] + (b[1] - a[1]) * t
        const d = Math.hypot(px - pos.lat, (py - pos.lng) * cosLat) * 111320
        if (d < best.distM) best = { idx: i, t, proj: [px, py], distM: d }
      }
      return best
    },
    /**
     * 新线是否只是「同一条线的重算微抖」：当前线均匀采样 ≤25 个点算到新线的垂距，
     * 平均 <30m 视为同一走廊。真换线（不同走廊/改目的地）垂距是公里级，不会误判；
     * 新线比现线短（目的地变了）时现线尾部点垂距大，同样不会误判。
     */
    _isSameLineRewash(newCoords, prevCoords) {
      if (!newCoords || !prevCoords || prevCoords.length < 4) return false
      const step = Math.max(1, Math.floor(prevCoords.length / 25))
      let sum = 0, cnt = 0
      for (let i = 0; i < prevCoords.length; i += step) {
        const c = prevCoords[i]
        if (!c || c.length < 2) continue
        sum += this._nearestOnPolyline(newCoords, { lat: c[0], lng: c[1] }).distM
        cnt++
      }
      return cnt > 0 && sum / cnt < 30
    },
    /**
     * 采纳任何外部整线（Agent 重算 / 局部绕行）前的统一对齐入口：
     * 与 fetchRoute / SSE / 调度换线走同一套拼接口径，把线头钉到车辆当前位置。
     */
    _alignIncomingRoute(r) {
      if (!r || !r.pathCoords) return r
      const prev = this.route || {}
      this._stitchRouteFromCar(r, this._meMarker ? this._meMarker.getLatLng() : null,
        prev.pathCoords || [], prev.pathEdgeIds || [], prev.pathEdgeSpans || [])
      return r
    },
    _stitchRouteFromCar(resp, carPos, prevCoords, prevEdgeIds, prevSpans) {
      const newCoords = resp && resp.pathCoords
      if (!carPos || !newCoords || newCoords.length < 2 || !prevCoords || prevCoords.length < 2) return
      // —— 先判「车已经在新走廊上」：15s 轮询重算最常见的形态 ——
      // 判据用「车到最近线段的垂距」而非「到最近顶点的距离」：城区路网顶点间距可达
      // 几百米～几公里，按顶点判会把「车离新线还差半条街」的换线误判成已在走廊上，
      // 截断后「车→远处顶点」一段直线穿楼（用户反馈「改路线后直接穿房子」根因）；
      // 垂距 ≤150m 才算在走廊上，截断点取垂足投影——车先短距汇入道路再沿路走；
      // 垂距更大则落到下面的桥接分支，沿旧路线（本就贴路）接到新线起点节点。
      const near = this._nearestOnPolyline(newCoords, carPos)
      const onIdx = near.t < 0.02 ? near.idx : near.idx + 1
      if (near.distM < 150) {
        // 同线微抖识别：同一 O/D 重算的新线几何相对当前线只有米级抖动（插值/平行边
        // 选择差异），照单全收会让车标每 15s 被重吸附到抖动后的顶点——表现就是
        // 「车标有时横穿马路、不停变化」。命中则打标返回，调用方保留现线只收风险。
        if (this._isSameLineRewash(newCoords, prevCoords)) {
          resp._sameLineKeep = true
          return
        }
        // 车已贴到新线尽头（到达附近）：没有可续画的剩余走廊，保持原线不动
        if (onIdx >= newCoords.length - 1) return
        const spans = resp.pathEdgeSpans || []
        const car = [carPos.lat, carPos.lng]
        // 城市峡谷 GPS 漂移可达百米级：把车原始位置塞进折线首点，「车→垂足」就是一根
        // 随每次轮询漂移变向的斜弦，正穿车前方那个街区（用户反馈「快到的一段一直在变、
        // 斜穿建筑物」根因；精度圈大图即 GPS 漂移的直接证据）。漂移 >30m 时线头改从
        // 垂足起画（垂足在真实路面上），车标自己漂在路外；≤30m 直连肉眼不可见，
        // 「线头贴车」的观感保留
        const tetherCar = near.distM <= 30
        // 新下标 = 原下标 - shift：必须按「实际丢了多少原点、又前插了几个非数据点」算，
        // 不能直接减 onIdx——垂足在段内时前插了「车标 + 垂足」两个点，而丢掉的只有
        // idx 之前的点，两种分支的下标位移量不同，写死会让整张边区间表错 1~2 格
        //（风险着色打偏、线尾少画一截）。
        const interior = near.t > 0.02 && near.t < 0.98
        const firstOld = interior ? near.idx + 1 : onIdx
        const pre = (interior ? 2 : 1) - (tetherCar ? 0 : 1)
        const shift = firstOld - pre
        // 截断点所在边：第一个「末点仍在保留区内」的 span；之前的整边全部丢弃
        let eIdx = spans.findIndex(sp => sp && sp[1] >= firstOld)
        if (eIdx < 0) eIdx = 0
        // 垂足在线段内部（t 在 0.02～0.98）才插投影点；靠近两端时直接用顶点，
        // 避免在共线处塞一个几乎重合的冗余点
        const head = tetherCar ? [car] : []
        const trimmed = interior
          ? head.concat([near.proj], newCoords.slice(near.idx + 1).map(c => [c[0], c[1]]))
          : head.concat(newCoords.slice(onIdx).map(c => [c[0], c[1]]))
        let removedM = 0
        for (let i = 1; i <= near.idx; i++) removedM += this._distM(newCoords[i - 1], newCoords[i])
        // 精确到垂足：车在线段中间（稀疏长边可达几十公里）时只扣到投影位置，
        // 按整段顶点扣会把里程/ETA 错杀半条边
        removedM += near.t * this._distM(newCoords[near.idx], newCoords[near.idx + 1])
        resp.pathCoords = trimmed
        resp.pathEdgeIds = (resp.pathEdgeIds || []).slice(eIdx)
        resp.pathEdgeSpans = spans.slice(eIdx).map(sp => [Math.max(0, sp[0] - shift), sp[1] - shift])
        // 节点表与边表对齐：nodes[i] 是边 i 的起点，丢弃截断点之前的整段节点
        resp.pathNodeIds = (resp.pathNodeIds || []).slice(eIdx)
        this._adjustRouteMetrics(resp, -removedM / 1000)
        return
      }
      // 车在「换线前当前路线」上的最近**垂足**（而非最近顶点）：车标本就由折线插值得到、
      // 永远在线段中间，稀疏长边（顶点间距可达几公里）下「车→最近顶点」是一段斜穿城区的
      // 弦线——用户反馈「有时不按路线走直接穿建筑物」的残余来源。从所在线段的下一顶点起
      // 沿顶点序推进，桥接段首尾都与现线共线，弦线彻底退化。
      const carNear = this._nearestOnPolyline(prevCoords, carPos)
      // 兜底：车位置偏离当前路线 >300m（被定位按钮/异常链路拖出线外）时，桥接起点
      // 改用现线上的垂足——否则「车位→路线」首段就是公里级斜弦穿楼，顺手把车标吸回线
      if (carNear.distM > 300) {
        carPos = { lat: carNear.proj[0], lng: carNear.proj[1] }
        if (this._meMarker) { try { this._meMarker.setLatLng([carPos.lat, carPos.lng]) } catch (e) { /* 标记已脱离地图时忽略 */ } }
      }
      // 与截断分支同一口径：GPS 漂移 >30m 时桥接首点从垂足起画，不把车原始位置
      // 画成斜弦；≤30m 直连与路面共线，肉眼不可见
      const carOnLine = this._distM([carPos.lat, carPos.lng], carNear.proj) <= 30
      const bridgeHead = carOnLine ? [carPos.lat, carPos.lng] : carNear.proj
      const nodeNear = this._nearestOnPolyline(prevCoords, { lat: newCoords[0][0], lng: newCoords[0][1] })
      const carIdx = carNear.idx
      const nodeIdx = nodeNear.idx
      const P = (i) => [prevCoords[i][0], prevCoords[i][1]]
      // 新路线起点节点也在当前线上（垂距很小）：沿现线接到节点的垂足，全程贴路
      const onLineNode = nodeNear.distM < 300
      // 把「车当前位置」沿当前路线接到「新路线起点节点」，让拼接后首点严格等于车位置。
      // 两种方向都要拼，绝不能只拼前方（这是「确认后起点大跳」反复复发的真正根因）：
      // · 节点在车前方：正向从所在线段下一顶点起取到节点（车继续往前开到该节点再走新走廊）；
      // · 节点在车后方：从所在顶点往回退到节点方向（换线目的地在已走过的方向，
      //   如车已开过南宁往崇左、却要改去六景港，必须先折回南宁）；
      // · 同段：车→节点垂足本就共线，直连即可。
      let bridge
      if (onLineNode) {
        bridge = [bridgeHead]
        if (nodeIdx >= carIdx) {
          for (let i = carIdx + 1; i <= nodeIdx; i++) bridge.push(P(i))
        } else {
          for (let i = carIdx; i >= nodeIdx + 1; i--) bridge.push(P(i))
        }
        bridge.push(nodeNear.proj)
      } else {
        // 节点离开当前走廊（真换线/改目的地）：维持顶点级就近桥接，
        // 尾段「最近顶点→节点」存在短弦线，这是无真实路面算路时的极限
        let nodeIdxV = carIdx; let nodeBest = Infinity
        for (let i = 0; i < prevCoords.length; i++) {
          const c = prevCoords[i]
          if (!c || c.length < 2) continue
          const d = Math.hypot(c[0] - newCoords[0][0], c[1] - newCoords[0][1])
          if (d < nodeBest) { nodeBest = d; nodeIdxV = i }
        }
        if (nodeIdxV > carIdx) {
          // 前方：从车位（或垂足）沿所在线段共线推进到 P(carIdx+1)，再逐顶点到节点
          bridge = [bridgeHead].concat(prevCoords.slice(carIdx + 1, nodeIdxV + 1).map(c => [c[0], c[1]]))
        } else if (nodeIdxV < carIdx) {
          // 后方：先共线退回本段起点顶点 P(carIdx)（不能拿车位直接替换它，否则
          // 车位→上一顶点整段长度的斜弦穿楼），再逐顶点退到节点
          bridge = [bridgeHead, P(carIdx)]
            .concat(prevCoords.slice(nodeIdxV, carIdx).map(c => [c[0], c[1]]).reverse())
        } else {
          // 同段：车与节点本就在同一线段上共线；GPS 漂移过大时保持节点起点不动，
          // 不把新线起点替换成路外的车原始位置
          if (carOnLine) newCoords[0] = bridgeHead
          return
        }
      }
      if (bridge.length < 2) return
      const offset = bridge.length - 1
      // 桥接段沿用「车当前所在的边」的 edgeId（未换线前这段本就是这条边，通常安全）
      let bridgeEdgeId
      if (prevSpans && prevSpans.length && prevEdgeIds && prevEdgeIds.length) {
        bridgeEdgeId = prevEdgeIds[this._edgeIdxAtCoord(prevSpans, carIdx)] || prevEdgeIds[0]
      }
      if (!bridgeEdgeId) bridgeEdgeId = `stitch-${carIdx}`
      // 原新坐标索引 i(>=1) → 拼接后 i+offset；索引 0（节点，与桥接段末点重合）被丢弃
      const shiftSpan = (sp) => {
        if (!sp || sp.length !== 2) return sp
        const a = sp[0] === 0 ? offset : sp[0] + offset
        return [a, sp[1] + offset]
      }
      const mergedSpans = (resp.pathEdgeSpans || []).map(shiftSpan)
      mergedSpans.unshift([0, offset])
      resp.pathCoords = bridge.concat(newCoords.slice(1))
      resp.pathEdgeIds = [bridgeEdgeId].concat(resp.pathEdgeIds || [])
      resp.pathEdgeSpans = mergedSpans
      // 桥接段是「后端整线之外多出来的真实行驶段」：里程/耗时同步补上，
      // 否则卡牌显示的 totalDistanceKm 不含车到起点节点这一段，
      // 与出发播报念的数、以及下一轮轮询截断后的数互相对不上。
      let addedM = 0
      for (let i = 1; i < bridge.length; i++) addedM += this._distM(bridge[i - 1], bridge[i])
      this._adjustRouteMetrics(resp, addedM / 1000)
    },
    /**
     * 拼接/截断新路线后同步修正全程里程与预估耗时（按等比缩放耗时）。
     * 后端只按路网节点整线给数，前端把线头对齐到车位置后，展示口径必须跟着改，
     * 不然「卡牌里程」和「语音播报里程」（同一字段不同时刻取值）会随轮询来回跳。
     */
    _adjustRouteMetrics(resp, deltaKm) {
      if (!resp || !deltaKm) return
      const old = Number(resp.totalDistanceKm)
      if (!Number.isFinite(old) || old <= 0) return
      const next = Math.max(0.1, old + deltaKm)
      const h = Number(resp.estimatedHours)
      resp.totalDistanceKm = Math.round(next * 100) / 100
      // 耗时扣减精度到 1e-4h（≈0.36 分钟）：若只保留 0.1h（6 分钟）粒度，每次 trim
      // 等比扣掉的 0.02h 会被舍入抹平，剩余时间要攒 5 公里才挪一格——分钟显示长时间不动。
      // 展示粒度由 navHoursDisplay 按分钟 round，播报处各自 toFixed，互不影响。
      if (Number.isFinite(h) && h > 0) resp.estimatedHours = Math.round(h * next / old * 10000) / 10000
    },
    /** 给定坐标下标落在哪条边（spans[i]=[起,止] 且边序与 edgeIds 对齐），返回边序号 */
    _edgeIdxAtCoord(spans, coordIdx) {
      if (!spans) return 0
      for (let i = 0; i < spans.length; i++) {
        const sp = spans[i]
        if (sp && sp.length === 2 && coordIdx >= sp[0] && coordIdx <= sp[1]) return i
      }
      return 0
    },
    fmtYuan(v) {
      return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
    },
    /**
     * 换线审计探针：每个替换 this.route 的入口在赋值前调用，留下「谁、什么时候、
     * 换之前车标/线首在哪」的 console 记录。与 watchdog 的 CAR-JUMP/C0-JUMP 同时刻对齐，
     * 就能指名是哪条链路把拼接好的线覆盖成了后端整线（起点大跳真凶）。
     */
    _auditSwap(src) {
      try {
        const car = this._meMarker ? this._meMarker.getLatLng() : null
        const old = this.route && this.route.pathCoords && this.route.pathCoords[0]
        console.info(`[AUDIT ${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] swap-src=${src}`, {
          car: car ? [Math.round(car.lat * 1e4) / 1e4, Math.round(car.lng * 1e4) / 1e4] : null,
          oldC0: old && old.length >= 2 ? [Math.round(old[0] * 1e4) / 1e4, Math.round(old[1] * 1e4) / 1e4] : null,
          oldKm: this.route && this.route.totalDistanceKm,
          nav: this.navigating, prev: this.previewing, dp: this._dispatchPreviewPlanId
        })
      } catch (e) { /* 审计探针不影响主流程 */ }
    },
    showPush(type, title, msg) {
      this.pushType = type
      this.pushTitle = title
      this.pushMsg = msg
      // 推送记录（预警 tab 可回看）
      this.pushLog.unshift({
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' }),
        title
      })
      if (this.pushLog.length > 20) this.pushLog.pop()
      // 注意：推送条已不参与取景（见 _bottomOverlayPadding），这里不再重新适配路线，
      // 避免一条 8 秒就消失的 toast 让相机来回缩进缩出。
      setTimeout(() => { this.pushMsg = '' }, 8000)
    },
    /**
     * 语音播报（抢占式）：立即打断正在播的内容，不等上一段播完。
     * 同一文本 30 秒内仍去重，避免同一事件被 SSE / 轮询 / 重取路线多路触发时重复播报。
     */
    /**
     * 排队并抢占播报最新内容。
     *
     * @param texts 依次朗读的文本
     * @param opts  force=true 安全关键播报（灾害预警 / 运河禁航 / 全域受阻），
     *              即使导航开始播报正占着通道也强行打断它；lock=true 本条为独占播报，
     *              占用期间非 force 的播报既不能抢占、也不入队（导航开始播报用）
     */
    speakQueue(texts, opts) {
      if (!this.voiceOn) { this._vdbg('拦截：语音已关闭(voiceOn=false)'); return }
      const o = opts || {}
      // 导航开始播报（lock）正在念：只有 force 类安全播报能打断，其它一律让路
      if (this._voiceLocked && !o.force) { this._vdbg('拦截：高优先级播报占用中'); return }
      const list = (texts || []).filter(t => t && String(t).trim())
      if (!list.length) { this._vdbg('拦截：文本为空'); return }
      // 去重：用独立时间戳，不受打断与冷却影响。
      // force=true（导航开始这类"必须出声"的播报）跳过去重：
      // 否则「开始导航→返回→再开始导航」间隔不到 30 秒时，第二次会被静默吞掉。
      const key = list.join('|')
      const now = Date.now()
      if (!o.force && key === this._lastVoiceText && now - this._lastVoiceAt < 30000) {
        this._vdbg('拦截：30 秒内重复')
        return
      }
      this._lastVoiceText = key
      this._lastVoiceAt = now
      // 抢占：取消当前播报与排队内容，马上播最新的这条。
      // force 的安全播报此刻有权打断「导航开始」（灾害信息优先）；
      // 反过来非 force 的播报连取消当前播报的权利都没有，出发播报不会被轮询噪音切碎。
      // 出发播报被打断时，它那句「从A到B、全程X公里」不能跟着一起丢：挂到本次队尾补读。
      // 判据是「登记时记录的播报世代仍等于当前世代」——即出发播报还在念、还没被别的链路顶替。
      // 只给非独占播报补读：出发播报自身的重试（force+lock）整段重来，不需要再加一句。
      const introRescue = (o.force && !o.lock && this._departureIntroChunks.length && this._departureIntroSeq === this._voiceSeq)
        ? this._departureIntroChunks.slice() : []
      if (introRescue.length) { this._departureIntro = ''; this._departureIntroChunks = []; this._departureIntroSeq = 0 }
      this._cancelPromise = this._cancelVoice(o.force)
      this._voiceQueue.length = 0
      // 拆句播报：Web Speech 引擎（尤其 Android WebView / Chrome）对单条长文本常在念到一半时
      // 提前触发 onend，把后半段整个丢掉 —— 出发播报表现为「只念了‘导航开始…’就跳到越南语」，
      // 里程 / 耗时 / 到达时间没读出来。按句末标点切成短句逐条读可规避；
      // 去重键与上锁时长仍按整段（list）计，只改真正入队朗读的粒度，跨语言顺序不变。
      this._voiceQueue.push(this._expandToSentences(list))
      // 补读段排在本次播报的最后：先把灾害说完，再回头补行程信息。
      // 直接用登记时拆好的短句（与上面同一套 _expandToSentences 规则，每条都在引擎能读完的长度内），
      // 并且只补还没完整念到的那几段——已念过的不重复，正在念而被掐断的那一条留在里面重读
      if (introRescue.length) this._voiceQueue.push(introRescue)
      // 高优先级：锁住语音通道，直到本条念完（或超时兜底）
      let lockSec = 0
      if (o.lock) {
        lockSec = Math.round(this._estimateSpeechMs(list) / 1000)
        this._lockVoice(this._estimateSpeechMs(list))
      }
      this._vdbg('入队 ' + list.length + ' 条' + (lockSec ? '（上锁 ' + lockSec + 's）' : '')
        + (introRescue.length ? '（队尾补读出发核心行剩余 ' + introRescue.length + ' 段）' : '')
        + '｜首条：' + String(list[0]).slice(0, 18))
      this._flushVoice()
    },
    /**
     * 估算朗读时长（毫秒），用于高优先级播报的占用超时。
     * 中文约 4.5 字/秒、其它字符约 13 字/秒，再留 4 秒余量；下限 8 秒、上限 2 分钟。
     */
    _estimateSpeechMs(list) {
      let ms = 0
      for (const t of list) {
        const s = String(t)
        const cjk = (s.match(/[一-鿿]/g) || []).length
        ms += (cjk / 4.5 + (s.length - cjk) / 13) * 1000
      }
      return Math.min(120000, Math.max(8000, Math.round(ms) + 4000))
    },
    /**
     * 把待播文本按句末标点 / 逗号展开成短句序列，逐条喂给 TTS。
     *
     * 为什么要拆：Web Speech（Android WebView / Chrome）对单条长 utterance 会在没念完时就
     * 提前抛 onend，导致后半段被整个丢弃（出发播报只听得到“导航开始”，里程/ETA 没读）。
     * 切成短句后每条都在引擎能完整读完的长度内，彻底规避截断。
     * 切点：。！？；，（中文）直接在其后切；拉丁语系的 . 与 , 必须紧跟空白才算切点，
     * 以保护 “270.5” 这类小数不被拆坏。
     */
    _expandToSentences(list) {
      const out = []
      for (const t of list) {
        const parts = String(t).split(/(?<=[。！？；，])|(?<=[.,])[ \t\n]+/)
        for (let p of parts) { p = (p || '').trim(); if (p) out.push(p) }
      }
      return out.length ? out : list.slice()
    },
    /**
     * 锁定语音通道：期间 speakQueue 的普通播报一律丢弃（导航开始播报专用）。
     * 带超时兜底——若 onend/onerror 都没回来（WebView 上偶发），到点自动解锁，
     * 避免通道被永久占死导致此后再无语音。
     */
    _lockVoice(ms) {
      const seq = this._voiceSeq
      this._voiceLocked = true
      this._criticalVoiceSeq = seq
      if (this._voiceLockTimer) clearTimeout(this._voiceLockTimer)
      this._voiceLockTimer = setTimeout(() => {
        if (this._criticalVoiceSeq !== seq) return
        this._unlockVoice(seq)
      }, ms)
    },
    /** 解锁语音通道（播报链条正常念完时调用；seq 防止旧链路误开新锁） */
    _unlockVoice(seq = this._criticalVoiceSeq) {
      if (this._criticalVoiceSeq && seq !== this._criticalVoiceSeq) return
      this._voiceLocked = false
      this._criticalVoiceSeq = 0
      if (this._voiceLockTimer) { clearTimeout(this._voiceLockTimer); this._voiceLockTimer = null }
    },

    /**
     * 取消当前正在进行的播报：清掉计时器、作废旧播报链路、停掉 web speech 与原生 TTS。
     * force=false 时不能取消正独占通道的导航开始播报；安全关键播报与司机主动退出/卸载会显式传 true。
     * 递增 _voiceSeq 后，旧链路里所有 onend / onerror / 降级回调都会自行退出，不会续播旧内容。
     */
    _cancelVoice(force = false) {
      if (this._criticalVoiceSeq && !force) {
        this._vdbg('拦截取消：导航开始播报独占中（本条非安全关键播报）')
        return Promise.resolve(false)
      }
      if (this._voiceTimer) { clearTimeout(this._voiceTimer); this._voiceTimer = null }
      this._voiceSeq += 1
      this._unlockVoice(this._criticalVoiceSeq)
      const synth = window.speechSynthesis
      if (synth && (synth.speaking || synth.pending || synth.paused)) {
        try { synth.cancel() } catch (e) { /* 忽略 */ }
      }
      return this._stopNativeTts()
    },
    /**
     * 语音链路诊断输出：仅 ?voicedebug=1 时写屏 + 打 console。
     * 用于定位"开始导航没声音"这类问题——播报链上任何一步被拦/卡住都会在此留痕。
     */
    _vdbg(msg) {
      if (!this.voiceDebugOn) return
      this.voiceDebug = new Date().toLocaleTimeString('zh-CN', { hour12: false }) + ' ' + msg
      console.log('[voice]', msg)
    },
    /**
     * 给 Promise 加超时：超过 ms 就按 fallback 继续。
     * 播报链路上任何 await 都不能无限期挂住 —— Web 端 Capacitor 插件的 stop()
     * 在没有原生桥时不保证 resolve；一旦卡住，新播报永远等不到开口，
     * 而高优先级锁还占着通道，表现为"完全没有语音"。
     */
    _withTimeout(p, ms, fallback) {
      return Promise.race([
        Promise.resolve(p).catch(() => fallback),
        new Promise(resolve => setTimeout(() => resolve(fallback), ms))
      ])
    },
    /** 停掉原生 TTS（Capacitor）并 await 到真正停完；整体带超时，绝不拖死播报 */
    async _stopNativeTts() {
      await this._withTimeout((async () => {
        const m = await loadNativeTts()
        if (m && m.TextToSpeech) await m.TextToSpeech.stop()
      })(), 600, null)
    },
    /**
     * 等 Web Speech 引擎真正空闲再起播。
     * cancel() 是异步的（Android WebView 尤其明显），原先固定等 80ms 常常不够：
     * 抢跑会让新语音被吞掉或只念一半 —— 这是"播报乱"的另一半原因。
     * 最多等 maxMs（默认 400ms），超时就再 cancel 一次兜底，避免彻底卡住不出声。
     */
    _waitSpeechIdle(maxMs) {
      const synth = window.speechSynthesis
      if (!synth) return Promise.resolve()
      const limit = maxMs || 400
      const t0 = Date.now()
      return new Promise(resolve => {
        const tick = () => {
          if (!synth.speaking && !synth.pending && !synth.paused) return resolve()
          if (Date.now() - t0 >= limit) {
            try { synth.cancel() } catch (e) { /* 忽略 */ }
            return resolve()
          }
          setTimeout(tick, 30)
        }
        tick()
      })
    },

    /** 段间不再固定冷却（原 1500ms，连续预警念起来太拖沓）；防串音靠起播前等引擎真正空闲，见 _waitSpeechIdle */
    _flushVoice() {
      if (this._voiceTimer) return // 正在播报或等待
      const seq = this._voiceSeq
      this._voiceTimer = setTimeout(async () => {
        this._voiceTimer = null
        if (seq !== this._voiceSeq) { this._vdbg('放弃起播：已被抢占'); return }
        const texts = this._voiceQueue.shift()
        if (!texts) return
        // 1) 等上一次的原生 stop() 完成（原生与 Web 是两套引擎，都要停干净）。
        //    带超时 —— 这一步绝不能把播报卡死，宁可没停干净也要让新播报出声。
        const pending = this._cancelPromise
        this._cancelPromise = null
        if (pending) await this._withTimeout(pending, 800, null)
        if (seq !== this._voiceSeq) { this._vdbg('放弃起播：等取消期间被抢占'); return }
        // 2) 等 Web Speech 真正空闲，再开口
        await this._waitSpeechIdle()
        if (seq !== this._voiceSeq) { this._vdbg('放弃起播：等空闲期间被抢占'); return }
        this._vdbg('开始起播（校验通过）')
        this._doSpeak(texts, 0, seq)
      }, 0)
    },
    _doSpeak(texts, i, seq) {
      if (seq !== this._voiceSeq) return // 已被抢占，整条旧播报链路作废
      this._voiceActiveSeq = seq
      // 出发播报推进到下一条 = 上一条已完整念完，把它从待补读短句里出列；
      // 正在念的这条可能只念到一半就被打断，所以留在里面，补读时重读一遍才不缺字
      if (i > 0 && this._departureIntroSeq === seq && texts[i - 1] === this._departureIntroChunks[0]) {
        this._departureIntroChunks.shift()
      }
      if (i >= texts.length) {
        // 整条念完：只释放本次最高优先级锁，旧播报回调不能误开新播报的锁
        this._unlockVoice(this._criticalVoiceSeq)
        // 出发播报完整念完且队里没接别的段：那份「被打断才需要补读」的开场行作废，
        // 不清的话很久之后来的 force 播报会凭空补念一句陈年的「导航开始…」
        if (this._departureIntroSeq && this._departureIntroSeq === seq && !this._voiceQueue.length) {
          this._departureIntro = ''
          this._departureIntroChunks = []
          this._departureIntroSeq = 0
        }
        if (this._voiceQueue.length) {
          // 队列里还有整段要连读：不再等 1500ms 冷却，接上立刻续播
          this._voiceTimer = setTimeout(() => {
            this._voiceTimer = null
            this._flushVoice()
          }, 0)
        }
        return
      }
      const text = texts[i]
      const lang = detectLanguage(text)
      const clean = this._cleanVoice(text, lang)
      if (!clean) { this._vdbg('清洗后为空，跳过第' + (i + 1) + '条'); this._doSpeak(texts, i + 1, seq); return }
      const next = () => { if (seq === this._voiceSeq) this._doSpeak(texts, i + 1, seq) }
      // 引擎选择顺序很关键：Capacitor 原生容器里**优先原生 TTS** ——
      // Android WebView 的 window.speechSynthesis 存在但通常不出声、也不触发 onerror，
      // 若按"有 synth 就走 Web"会被静默卡死，永远轮不到挂在 onerror 上的原生兜底。
      if (this._isNativeShell()) {
        this._vdbg('第' + (i + 1) + '/' + texts.length + '条 → 原生TTS：' + clean.slice(0, 18))
        this._capacitorSpeak(clean, lang, seq).then(ok => {
          if (seq !== this._voiceSeq) return
          if (ok) next()
          else this._speakWeb(clean, lang, next, 0, seq)   // 原生也不可用 → 退回 Web
        })
        return
      }
      const syn0 = window.speechSynthesis
      this._vdbg('第' + (i + 1) + '/' + texts.length + '条 → Web：synth=' + (syn0 ? '有' : '无')
        + ' speaking=' + (syn0 ? syn0.speaking : '-')
        + ' pending=' + (syn0 ? syn0.pending : '-')
        + ' voices=' + (syn0 && syn0.getVoices ? syn0.getVoices().length : '-'))
      this._speakWeb(clean, lang, next, 0, seq)
    },
    /** 是否运行在 Capacitor 原生容器内（普通浏览器里 isNativePlatform() 返回 false） */
    _isNativeShell() {
      const c = window.Capacitor
      return !!(c && typeof c.isNativePlatform === 'function' && c.isNativePlatform())
    },
    /** 按 rate=0.95 估算单条短文本的实际朗读时长（ms），不设下限/上限，用于 onend 早到判定。
     *  速度按主流 TTS 引擎实测校准（中文≈ 6 字/秒、拉丁≈ 16 字符/秒）：
     *  原先 4.5/13 估得比真实语速慢，导致每句都被判“早到”并补等差值，句间空隔拉得很长。 */
    _estimateChunkMs(text) {
      const s = String(text)
      const cjk = (s.match(/[\u4e00-\u9fff]/g) || []).length
      const raw = (cjk / 6 + (s.length - cjk) / 16) * 1000
      return raw / 0.95 // 除以 rate
    },
    /** Web Speech 播报一条；1.2 秒未确认出声会自动重试一次，再退回原生 TTS */
    async _speakWeb(clean, lang, next, attempt = 0, voiceSeq = this._voiceSeq) {
      const synth = window.speechSynthesis
      if (!synth) {
        this._vdbg('无 speechSynthesis → 退回原生')
        this._capacitorSpeak(clean, lang, voiceSeq).finally(() => { if (voiceSeq === this._voiceSeq) next() })
        return
      }
      // Chrome 的 getVoices() 是异步填充的，首次（或没有可用语音时）返回空数组 ——
      // 此时 speak() 找不到匹配语音会**静默无声且不报错**。先等语音列表就绪。
      await this._waitVoicesReady(1500)
      if (synth.paused) synth.resume()
      let finished = false
      let started = false
      let speakStartAt = 0 // onstart 时记录真实开始时间，用于判定 onend 是否早到
      const estMs = this._estimateChunkMs(clean) // 本条预估朗读时长（无 floor）
      const finish = () => {
        if (finished) return
        finished = true
        clearTimeout(startTimer)
        clearTimeout(maxTimer)
        next()
      }
      const fallbackNative = () => {
        if (finished) return
        finished = true
        clearTimeout(startTimer)
        clearTimeout(maxTimer)
        this._capacitorSpeak(clean, lang, voiceSeq).finally(() => { if (voiceSeq === this._voiceSeq) next() })
      }
      // 只看 onstart：speak() 被浏览器静默吞掉时不会报错，必须主动看门。
      // 但一旦确认已发声（onstart 已回 / synth 正在念）就绝不能再 cancel 重读：
      // 原先 onstart 不清这个定时器，任何超过 1.2s 的话都会被掐掉从头重念 ——
      // 「导航开始被读好几遍」的主因。
      const startTimer = setTimeout(() => {
        if (finished || started) return
        if (synth.speaking || synth.pending) { started = true; speakStartAt = Date.now(); return }
        this._vdbg('Web 1.2s 未开始发声，尝试恢复')
        finished = true
        try { synth.cancel() } catch (e) { /* 忽略 */ }
        if (attempt < 1) {
          setTimeout(() => {
            if (voiceSeq === this._voiceSeq) this._speakWeb(clean, lang, next, attempt + 1, voiceSeq)
          }, 250)
        } else {
          this._capacitorSpeak(clean, lang, voiceSeq).finally(() => { if (voiceSeq === this._voiceSeq) next() })
        }
      }, 1200)
      // 单条上限看门：onend 丢失（Chrome 长句 bug）时按估算时长+余量强制推进，避免整条队列卡死
      const maxTimer = setTimeout(() => {
        if (finished) return
        this._vdbg('单条播报超时，强制推进下一条')
        try { synth.cancel() } catch (e) { /* 忽略 */ }
        finish()
      }, estMs + 8000)
      const u = new SpeechSynthesisUtterance(clean)
      u.lang = lang
      u.rate = 0.95
      u.volume = 1
      u.onstart = () => {
        if (finished) return
        started = true
        speakStartAt = Date.now()
        clearTimeout(startTimer)
        this._voiceLastStartAt = speakStartAt
        this._voiceLastStartSeq = voiceSeq
        this._vdbg('Web 已开始发声')
      }
      // ===== onend 早到守卫 =====
      // Chrome/Android WebView 的已知 bug：utterance 的 onend 会在音频还没播完时就提前触发。
      // 但只有实际耗时连估算 3/4 都不到才可能是真截断；且补时封顶 400ms：
      // 原先每句都补等到估算值，估算偏慢时句句白等，听起来就是“念完半天才出下一句”。
      // 下一句的 speak() 本身会进引擎队列串行续播，不需要靠干等护尾音。
      u.onend = () => {
        if (finished) return
        const elapsed = speakStartAt ? (Date.now() - speakStartAt) : 0
        const remaining = estMs - elapsed
        if (started && remaining > 300 && elapsed < estMs * 0.75) {
          this._vdbg('Web onend 早到（' + elapsed + 'ms < 预估 ' + Math.round(estMs) + 'ms），补尾 ' + Math.min(remaining, 400) + 'ms')
          setTimeout(() => { finish() }, Math.min(remaining, 400))
        } else {
          this._vdbg('Web 播完')
          finish()
        }
      }
      u.onerror = (e) => {
        if (finished) return
        const err = (e && e.error) || '未知'
        this._vdbg('Web 出错：' + err + ' → 退回原生')
        // cancel/interrupted 是本次被更高优先级播报抢占，不再触发旧链路兜底
        if (err === 'canceled' || err === 'interrupted') { finish(); return }
        fallbackNative()
      }
      synth.speak(u)
      // Chrome「冷启动首句吞字」防护：页面第一次真正出声的那条 utterance，常在 onstart 前
      // 丢掉头 ~300ms——出发播报的第一句「导航开始 …」就常被削成只剩「开始」。后续导航引擎
      // 已热、不再吞头，所以只有「第一次」复现。speak() 后立刻 pause()+resume() 逼引擎先完成
      // 音频缓冲初始化再放行，是社区验证有效的固定写法；对已在发声的 utterance 无副作用。
      try { synth.pause(); synth.resume() } catch (e) { /* 忽略 */ }
      this._vdbg('已调用 synth.speak()（voices=' + (synth.getVoices ? synth.getVoices().length : '-') + '）')
    },
    /**
     * 等 Web Speech 的语音列表就绪。
     * Chrome 里 getVoices() 首帧常为空，需等 voiceschanged；等不到就超时放行（不做无限等待）。
     * 一旦见过非空列表就永久放行：部分 WebView 引擎轮询时会瞬时返空，
     * 不加这个标记的话每句都要白等满 1.5s 超时，句间空隔被拉得很长。
     */
    _waitVoicesReady(maxMs) {
      const synth = window.speechSynthesis
      if (!synth || !synth.getVoices || synth.getVoices().length) { this._voicesSeenOnce = true; return Promise.resolve() }
      if (this._voicesSeenOnce) return Promise.resolve()
      const limit = maxMs || 1500
      const t0 = Date.now()
      return new Promise(resolve => {
        const done = () => { try { synth.removeEventListener('voiceschanged', done) } catch (e) {} resolve() }
        try { synth.addEventListener('voiceschanged', done) } catch (e) {}
        const tick = () => {
          if (synth.getVoices().length || Date.now() - t0 >= limit) { this._voicesSeenOnce = synth.getVoices().length > 0; return done() }
          setTimeout(tick, 100)
        }
        tick()
      })
    },
    /** Capacitor 原生 TTS（Android/iOS WebView 语音播报）。返回是否成功，供上层决定要不要退回 Web */
    async _capacitorSpeak(text, lang, voiceSeq = this._voiceActiveSeq) {
      try {
        // 用缓存的模块实例（与 _stopNativeTts 同一个），保证 stop/speak 的调用顺序
        const m = await loadNativeTts()
        if (!m || !m.TextToSpeech) return false
        this._voiceLastStartAt = Date.now()
        this._voiceLastStartSeq = voiceSeq
        await m.TextToSpeech.speak({
          text,
          lang: lang === 'vi-VN' ? 'vi' : 'zh-CN',
          rate: 0.9,
          pitch: 1.0
        })
        this._vdbg('原生 TTS 已返回')
        return true
      } catch (e) {
        this._vdbg('原生 TTS 失败：' + ((e && e.message) || e))
        return false
      }
    },
    /**
     * 打断当前语音并立即播报新内容（用于预警等重要播报）。
     * speakQueue 现在本身就是抢占式的，这里额外跳过文本去重，保证重要预警一定播出来。
     * force=true：安全关键播报（灾害预警 / 运河禁航 / 全域受阻）允许打断「导航开始播报」的独占锁——
     * 旧实现遇锁直接丢弃且不排队，出发播报念到一半时灾害事件进来就全程无声，只弹卡。
     * 打断后出发播报的开场核心行（起讫点/里程/ETA）由 speakQueue 挂到队尾补读，不会整个听不到。
     */
    _interruptAndSpeak(texts, force) {
      if (!this.voiceOn) return
      // 导航开始播报独占期间，普通预警/任务播报仍只保留界面提示，不抢语音通道
      if (this._criticalVoiceSeq && !force) { this._vdbg('拦截预警播报：导航开始播报中'); return }
      this._lastVoiceText = ''
      this._lastVoiceAt = 0
      this.speakQueue(texts, force ? { force: true } : undefined)
    },
    /** 预热语音引擎：Chrome 要求用户手势后才能播放语音，每次交互都调用保持引擎活跃 */
    _warmUpSpeech() {
      if (!('speechSynthesis' in window)) return
      try {
        const synth = window.speechSynthesis
        // Chrome 长时间不调用会自动暂停，需要 resume
        if (synth.paused) synth.resume()
        // 如果已经在播放或待播放，不打断
        if (synth.speaking || synth.pending) return
        // 空文本在部分浏览器不会真正启动引擎；用零音量短文本完成用户手势授权
        const u = new SpeechSynthesisUtterance('好')
        u.volume = 0
        u.rate = 2
        u.onstart = () => { this._voiceLastStartAt = Date.now() }
        synth.speak(u)
      } catch (e) { /* ignore */ }
    },
    /** 清洗播报文本：使用 TTS 标准化工具，将技术文本转换为自然语音格式 */
    _cleanVoice(text, lang) {
      return normalizeForTTS(text, lang || 'zh-CN');
    },
    /** 概率百分比 → 配色 class：达到高风险阈值(50%)、或灾害已发生，一律标红 */
    probClass(p, occurred) {
      if (occurred) return 'high'
      if (p == null || p < 0) return 'unknown'
      return p >= 50 ? 'high' : 'low'
    },
    /** 低阈值不显示百分比：平静天气下各候选都堆在同一地板值（如 9%）没有区分度，
     * 直接标「通畅」更诚实；已发生灾害或达阈值才显示具体数字（与大屏 AgentPanel 一致） */
    probText(c) {
      const p = c && c.hazardProbability
      if (p == null || p < 0) return '暂无预测'
      if (!c.hazardOccurred && p < 15) return '通畅'
      return p + '%'
    },
    /** 是否为高风险（已发生灾害 / 沿线有风险段 / 综合概率达阈值）——预览卡片与绕行候选项共用红色标记 */
    isHighRisk(opt) {
      if (!opt) return false
      return !!opt.hazardOccurred || opt.riskCount > 0 || opt.hazardProbability >= 50
    },
    /**
     * 绕行面板的显示名：后端的「推荐路线」是固定角色名，若该走廊已被判为高风险，
     * 继续叫「推荐路线」会与红色「高风险·不推荐」徽标自相矛盾，改写为「原推荐路线」。
     */
    optLabel(opt) {
      const label = (opt && opt.label) || '备选路线'
      if (this.isHighRisk(opt) && label.indexOf('推荐') === 0) return '原推荐路线'
      return label
    },
    /** 绕行方案的逐灾种标签：概率最高的前 4 个，达阈值或已发生的标红 */
    hazardTags(opt) {
      const bd = Array.isArray(opt.hazardBreakdown) ? opt.hazardBreakdown : []
      return bd
        .filter(b => b && (b.occurred || (b.probability || 0) >= 10))
        .sort((a, b) => (b.probability || 0) - (a.probability || 0))
        .slice(0, 4)
        .map(b => ({ label: b.label, probability: b.probability || 0, high: !!b.high || !!b.occurred }))
    },
    /** 概率 → 等级文字（正常/高风险） */
    probLevelText(p) {
      if (p == null || p < 0) return '未知'
      return p >= 50 ? '高风险' : '正常'
    },
    extractHazardKeyword(reason) {
      // 播报/标题只念灾害本身：先剥掉「沙盘熔断:」这类系统触发标签前缀（后端熔断边的
      // reason 会写成「沙盘熔断:友谊关…」），否则落到兜底分支会把整串技术文案念出来。
      let txt = String(reason || '').replace(/^\s*(?:沙盘熔断|硬熔断|熔断)\s*[:：]\s*/, '')
      // 降雨既认「暴雨」也认「累计降雨量/降水」：沙盘熔断文案用的是「降雨量」，不补这条就漏判成整句兜底
      if (/暴雨|强降雨|特大暴雨|降雨|降水/.test(txt)) return '暴雨'
      if (/塌方|滑坡|泥石流|山洪/.test(txt)) return '塌方'
      if (/台风|大风|风暴|强对流|强风/.test(txt)) return '大风'
      if (/大雾|浓雾|低能见度|能见度/.test(txt)) return '大雾'
      if (/高温|热浪/.test(txt)) return '高温'
      // 兜底：去掉括号内的技术明细（阈值/触发源），只留可读灾害短语，避免播报念一长串参数
      txt = txt.replace(/[（(][^）)]*[）)]/g, '').trim()
      return txt || '气象灾害'
    },
    /** 从候选或预报中取概率最高的灾害类型名称 */
    topHazardName(obj) {
      if (!obj) return '灾害'
      // 优先从 breakdown 中找概率最高的
      const breakdown = obj.hazardBreakdown || obj.breakdown || []
      if (breakdown.length) {
        const top = breakdown.reduce((a, b) => (b.probability || 0) > (a.probability || 0) ? b : a, breakdown[0])
        return (top.label || this.extractHazardKeyword(top.reason || '')).replace(/灾害|概率/g, '')
      }
      // 其次从 hazardTypes 中取第一个
      const types = obj.hazardTypes || []
      if (types.length) return types[0]
      // 最后从 reason 中提取
      return this.extractHazardKeyword(obj.hazardReason || obj.reason || '')
    },
    inferPortFromNodeIds(nodeIds) {
      const ids = nodeIds || []
      if (ids.includes('MC') || ids.includes('E9')) return '芒街口岸'
      if (ids.includes('YGG') || ids.includes('E4')) return '友谊关口岸'
      if (ids.includes('HK') || ids.includes('E36')) return '河口口岸'
      return ''
    },
    latestHazardReason() {
      // 同样只认本路线命中的风险，避免把别处（如友谊关）的原因显示成"本程灾害"
      const segs = this._risksOnRoute(this.route)
      if (segs.length) return segs[segs.length - 1].reason || ''
      return this.forecast && this.forecast.reason ? this.forecast.reason : ''
    },
    /**
     * 打开「灾害概率详情」：展示暴雨/大风/大雾/高温/泥石流各自的概率、依据与建议。
     * 传候选对象则用该候选的明细；传 null 则用当前导航中的实时预测结果。
     */
    openHazardDetail(c) {
      const src = c || this.forecast
      if (!src) return
      const breakdown = (c && c.hazardBreakdown) || (this.forecast && this.forecast.breakdown) || []
      if (!breakdown.length) {
        this.showPush('info', '暂无明细', '该路线暂无逐灾种预测数据')
        return
      }
      this.hazardDetail = {
        title: c ? `${c.label} ${c.hours}h` : '当前路线',
        probability: c ? c.hazardProbability : this.forecast.probability,
        hasWeather: c ? c.hazardHasWeather : this.forecast.hasWeather,
        // 高概率的排最前（同等高低时按百分比降序）
        breakdown: [...breakdown].sort((a, b) => {
          if (!!b.high !== !!a.high) return b.high ? 1 : -1
          return b.probability - a.probability
        })
      }
    },
    /**
     * 构建详细状态播报文本：延误时间 + 天气 + 通关 + 货损 + 风险摘要
     * 输出适合 TTS 播报的自然语言格式
     */
    _buildStatusZh(includeEta = true) {
      const r = this.route
      const parts = []
      // 运输方式：水运方案要点明「平陆运河」。状态播报同时用于出发播报和灾害播报，
      // 放这里可以让三处播报都带上，不必各写一遍。
      if (this.isCanalPlan) parts.push('本程为陆水联运，经平陆运河')
      // 延误
      const extra = r.extraHours || 0
      if (extra > 0) {
        const hours = Math.floor(extra)
        const mins = Math.round((extra - hours) * 60)
        if (mins > 0) {
          parts.push(`额外延误${hours}小时${mins}分钟`)
        } else {
          parts.push(`额外延误${hours}小时`)
        }
      } else {
        parts.push('当前无延误，路线通畅')
      }
      // 天气
      if (this.weatherPoints && this.weatherPoints.length) {
        const pts = this.weatherPoints.slice(0, 4)
        const temps = pts.map(w => Math.round(w.temperatureC)).filter(t => !isNaN(t))
        const rainy = pts.filter(w => w.precipitationMm > 0)
        if (temps.length) {
          const lo = Math.min(...temps)
          const hi = Math.max(...temps)
          parts.push(`沿线气温最低${lo}度，最高${hi}度`)
        }
        if (rainy.length) {
          const maxPrecip = Math.max(...rainy.map(w => w.precipitationMm)).toFixed(0)
          parts.push(`${rainy.length}个节点有降水，最大降水量${maxPrecip}毫米`)
        }
      }
      // 通关
      const customsH = this.customsList.reduce((s, c) => s + c.currentHours, 0)
      if (customsH > 0) {
        parts.push(`通关等待约${customsH.toFixed(1)}小时`)
      }
      // 货损
      if (r.cargoLossYuan > 0) {
        parts.push(`冷链货损约${this.fmtYuan(r.cargoLossYuan)}元`)
      }
      // 风险（只取本路线命中的，避免把别处风险报成"前方风险"）
      const segs = this._risksOnRoute(r)
      if (segs.length) {
        const reasons = [...new Set(segs.map(s => this.extractHazardKeyword(s.reason)))]
        parts.push(`前方${segs.length}处风险路段：${reasons.join('、')}`)
      }
      // 预计到达 — 使用口语化日期格式
      if (includeEta) {
        parts.push(`预计北京时间${this.etaArrivalZh}、越南时间${this.etaArrivalVN}到达`)
      }
      return parts.join('。')
    },

    /** 预计耗时口语化（越南语）：3.5 → "3 giờ 30 phút"，0.75 → "45 phút" */
    _fmtDurationVi(hours) {
      const h = Number(hours)
      if (!Number.isFinite(h) || h <= 0) return ''
      let totalMin = Math.round(h * 60)
      if (totalMin < 1) totalMin = 1
      const hh = Math.floor(totalMin / 60)
      const mm = totalMin % 60
      if (hh <= 0) return `${mm} phút`
      if (mm <= 0) return `${hh} giờ`
      return `${hh} giờ ${mm} phút`
    },

    /** 出发播报安全入口：语音链路失败只记录日志，绝不能阻断地图/GPS/导航初始化 */
    _announceDepartureSafely() {
      // 连点开始导航 / 调度确认与手动入口叠加时，出发播报会被触发多次；
      // 这里兜底去重：15 秒内只允许一次出发播报
      const now = Date.now()
      if (this._lastDepartureAt && now - this._lastDepartureAt < 15000) {
        this._vdbg('拦截：出发播报 15 秒内已播过')
        return
      }
      this._lastDepartureAt = now
      setTimeout(() => {
        try {
          this._announceDeparture()
        } catch (e) {
          console.error('导航开始播报失败', e)
          // 详情播报失败时至少保证基础播报能出声，且不再影响导航主流程
          try {
            const km = Number(this.route.totalDistanceKm)
            const dur = Number.isFinite(this.navHoursDisplayNum) ? `${this.navHoursDisplayNum.toFixed(1)}小时后到达` : ''
            // 兜底句同样不把「导航开始」单独成句（超短 utterance 会被引擎吞掉开头）
            const text = `导航开始 从${this.originName}到${this.destinationName}，全程约${Number.isFinite(km) ? km : '--'}公里${dur ? '，预计' + dur : ''}`
            const u = new SpeechSynthesisUtterance(normalizeForTTS(text, 'zh-CN'))
            u.lang = 'zh-CN'
            u.rate = 0.95
            u.volume = 1
            window.speechSynthesis && window.speechSynthesis.speak(u)
          } catch (_) { /* 忽略 */ }
        }
      }, 0)
    },
    /**
     * 出发语音播报：中文 + 越南语（中越双语），只读一次
     */
    _announceDeparture() {
      const r = this.route
      const originVN = NODE_NAME_VN[this.resolvedOriginId] || this.resolvedOriginId
      const destVN = NODE_NAME_VN[this.resolvedDestinationId] || this.resolvedDestinationId
      const hoursRaw = Number(r.estimatedHours)
      // 与屏幕 ETA 卡片完全同源：卡片显示的就是 route.totalDistanceKm 原值与
      // navHoursDisplay（导航中=实时剩余小时，保留 1 位小数），播报再各自四舍五入/
      // 口语化成「188.4 公里」「5小时7分钟」就会出现「听的和看的对不上」。
      const kmRaw = Number(r.totalDistanceKm)
      const km = Number.isFinite(kmRaw) && kmRaw > 0 ? kmRaw : null
      const hoursDisplay = Number.isFinite(this.navHoursDisplayNum) ? this.navHoursDisplayNum : hoursRaw
      // 耗时口语化：仅在拿不到与屏幕一致的剩余小时时才退回，保证默认跟卡片同数
      const durZh = Number.isFinite(hoursDisplay) && hoursDisplay > 0 ? `${hoursDisplay.toFixed(1)}小时后到达` : ''
      const durVi = this._fmtDurationVi(hoursDisplay)

      // 开场白（国内导航风格）：一句话把「起讫点 + 全程里程 + 预计耗时 + 到达时间」讲清楚。
      // 这是最高优先级、必须最先被完整听到的关键信息，放在最前面即使后续被打断也不丢核心内容。
      // 「导航开始」必须与起讫点连成同一句、不能带逗号单独成句：
      // _expandToSentences 按逗号切句后，「导航开始」会变成 4 字超短 utterance，
      // 浏览器/WebSpeech 引擎对这种极短句偶发吞掉开头单词（实测只听得到「开始」）。
      const zhOpening = [
        `导航开始 从${this.originName}到${this.destinationName}`,
        km != null ? `全程约${km}公里` : '',
        durZh ? `预计${durZh}` : '',
        this.etaArrivalZh !== '--' ? `预计北京时间${this.etaArrivalZh}到达` : ''
      ].filter(Boolean).join('，')
      const viOpening = [
        `Bắt đầu điều hướng từ ${originVN} đến ${destVN}`,
        km != null ? `Toàn tuyến khoảng ${km} km` : '',
        durVi ? `Dự kiến mất ${durVi}` : '',
        this.etaArrivalViTTS !== '--' ? `Dự kiến đến vào ${this.etaArrivalViTTS} giờ Việt Nam` : ''
      ].filter(Boolean).join(', ')

      // 中文播报（水运方案会插入"经平陆运河"那句；非水运时为空串，用 filter 去掉）
      const zhParts = [
        zhOpening,
        this.transportBriefZh,
        this._buildStatusZh(false),
        'Agent 已开启实时守护，祝您一路平安'
      ].filter(Boolean)
      const zhText = zhParts.join('。')

      // 越南语播报
      const viParts = [
        viOpening,
        this.transportBriefVi,
        this._buildStatusVi(false),
        'Hệ thống Agent đã kích hoạt chế độ giám sát liên tục. Chúc bạn thượng lộ bình an'
      ].filter(Boolean)
      const viText = viParts.join('. ')

      // 独占播报：force 抢占当前一切播报；lock 让本条念完之前，非安全类的轮询/普通播报不许打断它。
      // 这保证「导航开始 + 全程里程 + 预计到达时间」不被一般噪声切碎；
      // 但灾害预警这类 force 播报仍能打断本条（安全信息优先），届时下面登记的开场核心行会补读一次。
      this._vdbg(`announceDeparture：里程=${km}km 耗时=${durZh || '--'} ETA=${this.etaArrivalZh}，准备入队`)
      // 入队并登记「被打断时要补读的那一句」：世代号取 speakQueue 之后的当前值（内部 cancel 已递增完），
      // 且只在确实入队（占用锁已生效）时登记，语音关闭等未入队情形不留脏值。
      // 重试也必须走这里：重试会顶替旧链路开新世代，不把 _departureIntroSeq 一起换掉的话，
      // 重试之后再来灾害就不补读出发信息了。
      const speakDeparture = () => {
        this.speakQueue([zhText, viText], { force: true, lock: true })
        // 登记的短句数组与实际逐条朗读的内容同源（都出自 _expandToSentences），_doSpeak 才能按条对齐；
        // 每次重新入队（含 4.5 秒未出声的自动重试）都要重置进度
        this._departureIntro = this._voiceLocked ? zhOpening : ''
        this._departureIntroChunks = this._voiceLocked ? this._expandToSentences([zhOpening]) : []
        this._departureIntroSeq = this._voiceLocked ? this._voiceSeq : 0
      }
      speakDeparture()
      const seq = this._voiceSeq
      // 4.5 秒后仍没确认本次播报发声，自动重试一次；期间非安全类播报仍被本条的占用锁拦截。
      setTimeout(() => {
        if (!this.navigating || !this.voiceOn) return
        if (seq !== this._voiceSeq || this._voiceLastStartSeq === seq) return
        this._vdbg('出发播报未检测到出声，自动重试一次')
        speakDeparture()
      }, 4500)
    },

    /**
     * 构建越南语状态播报文本：延误 + 天气 + 通关 + 货损 + 风险 + 到达时间
     */
    _buildStatusVi(includeEta = true) {
      const r = this.route
      const parts = []
      // 运输方式（越南语）：与中文播报对应，点明 kênh đào Bình Lục（平陆运河）
      if (this.isCanalPlan) parts.push('Chuyến này là vận tải liên hợp đường bộ - đường thủy, qua kênh đào Bình Lục')
      // 延误
      const extra = r.extraHours || 0
      if (extra > 0) {
        const hours = Math.floor(extra)
        const mins = Math.round((extra - hours) * 60)
        if (mins > 0) {
          parts.push(`Trễ thêm ${hours} giờ ${mins} phút`)
        } else {
          parts.push(`Trễ thêm ${hours} giờ`)
        }
      } else {
        parts.push('Hiện tại không có trì hoãn, tuyến đường thông thoáng')
      }
      // 天气
      if (this.weatherPoints && this.weatherPoints.length) {
        const pts = this.weatherPoints.slice(0, 4)
        const temps = pts.map(w => Math.round(w.temperatureC)).filter(t => !isNaN(t))
        const rainy = pts.filter(w => w.precipitationMm > 0)
        if (temps.length) {
          const lo = Math.min(...temps)
          const hi = Math.max(...temps)
          parts.push(`Nhiệt độ dọc tuyến từ ${lo} đến ${hi} độ C`)
        }
        if (rainy.length) {
          const maxPrecip = Math.max(...rainy.map(w => w.precipitationMm)).toFixed(0)
          parts.push(`${rainy.length} điểm có mưa, lượng mưa lớn nhất ${maxPrecip} mm`)
        }
      }
      // 通关
      const customsH = this.customsList.reduce((s, c) => s + c.currentHours, 0)
      if (customsH > 0) {
        parts.push(`Chờ thông quan khoảng ${customsH.toFixed(1)} giờ`)
      }
      // 货损
      if (r.cargoLossYuan > 0) {
        parts.push(`Thiệt hại hàng lạnh khoảng ${this.fmtYuan(r.cargoLossYuan)} tệ`)
      }
      // 风险（只取本路线命中的，避免把别处风险报成"前方风险"）
      const segs = this._risksOnRoute(r)
      if (segs.length) {
        const reasons = [...new Set(segs.map(s => {
          const hz = this.extractHazardKeyword(s.reason)
          return HAZARD_VN_MAP[hz] || hz
        }))]
        parts.push(`Phía trước có ${segs.length} đoạn đường rủi ro: ${reasons.join(', ')}`)
      }
      // 预计到达
      if (includeEta) {
        parts.push(`Dự kiến đến vào ${this.etaArrivalViTTS} giờ Việt Nam`)
      }
      return parts.join('. ')
    },

    /**
     * 灾害发生时的语音播报：说清"什么灾害 + 在哪 + 延误 + 天气 + 替代路线"
     */
    announceHazard(reason, optionCount) {
      // 标题/播报里的「在哪」必须是风险真正所在的走廊，绝不能用 viaText：
      // viaText 是当前选中候选的途经摘要，绕行后它往往正是那条避险安全线，
      // 于是「友谊关出灾、推荐改走芒街」会被错标成「路段风险 · 东兴 → 芒街口岸」——
      // 把避险线当成了风险点，与下方推荐卡自相矛盾。
      // 绕行场景风险落在原路线(baseline)上，故优先取 baseline 命中的口岸；
      // 尚未绕行(新增风险)时 baseline 即当前线，取其口岸同样正确；
      // 无明确口岸(风险在途中路段而非口岸)时退回整条行程起讫点 routeTitle。
      const where = this.inferPortFromNodeIds(this.route.baselinePathNodeIds)
        || this.inferPortFromNodeIds(this.route.pathNodeIds)
        || this.routeTitle
      const hazard = this.extractHazardKeyword(reason)
      this.announceText = `${hazard} · ${where}`
      // 同一起灾害事件只播报一遍：以「灾害类型 + 起讫点」作为事件标识，
      // 10 分钟内重复触发一律静默（灾害类型不同视为新事件，仍正常播报）。
      // 这是为了解决 SSE 推送 / 轮询兜底 / 重新取路线多路并发导致的重复播报。
      const key = `${hazard}@${this.resolvedOriginId}-${this.resolvedDestinationId}`
      const now = Date.now()
      if (key === this._hazardVoiceKey && now - this._hazardVoiceAt < 600000) return
      this._hazardVoiceKey = key
      this._hazardVoiceAt = now
      const hazardVN = HAZARD_VN_MAP[hazard] || hazard
      const status = this._buildStatusZh()
      // 播报内容按实际风险给出决策建议：全风险 → 提示停车；否则点出风险最低的一条
      const best = this.rerouteRanked[0]
      let zhAdvice = '请谨慎驾驶，注意观察路况。'
      let vnAdvice = 'Vui lòng lái xe cẩn thận và chú ý quan sát đường。'
      if (optionCount > 0 && this.allRerouteRisky) {
        zhAdvice = `区域内${optionCount}条替代路线均有风险，建议就近安全停车等待调度指令，切勿盲目行驶。`
        vnAdvice = `Cả ${optionCount} tuyến thay thế đều có rủi ro, nên dừng xe an toàn chờ điều độ。`
      } else if (optionCount > 0) {
        zhAdvice = `已为您规划${optionCount}条替代路线并按风险排序，风险最低的是${best.label}（${best.hazardProbability}%），请在屏幕上选择。`
        vnAdvice = `Đã có ${optionCount} tuyến thay thế, tuyến ít rủi ro nhất là ${best.label} (${best.hazardProbability}%), vui lòng chọn trên màn hình。`
      }
      const zh = `紧急提示，${where}路段发生${hazard}。${status}。${zhAdvice}`
      // force：灾害播报要能打断正占着通道的「导航开始」播报，不能只弹卡不出声
      this.speakQueue([
        zh,
        `Cảnh báo khẩn cấp: ${hazardVN} tại ${where}。Dự kiến chậm ${this.route.extraHours || 0} giờ。${vnAdvice}`
      ], { force: true })
    },
    /**
     * 风险解除、路线从绕行线切回原路线时的语音播报：
     * 念清「原路线哪条走廊 + 什么灾害已解除 + 已切回原路线」，让司机知道局势恢复正常。
     * 灾害简报取自绕行时快照（this._lastHazardBrief），因为此刻 this.risks 已被清空。
     * @param opt.viaDispatch 为 true 时是「调度中心下发原路线」，动作主语换成调度中心
     *                       （而非普通模式系统自动切回），与调度模式的控制归属一致。
     */
    _announceRiskCleared(opt) {
      const viaDispatch = !!(opt && opt.viaDispatch)
      const b = this._lastHazardBrief
      this._lastHazardBrief = null
      const hazard = b && b.hazard
      const loc = (b && b.where) ? `${b.where}路段` : '沿线'
      const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      // 切回动作的主语：调度模式归因到调度中心，普通模式归因到系统自动
      const switched = viaDispatch ? '调度中心已为您切换为原路线' : '已为您切回原路线'
      const zh = hazard
        ? `风险解除。原路线${loc}的${hazard}风险已解除，${switched}，可按原计划继续行驶。`
        : `风险解除。原路线${loc}风险已解除，${switched}，可按原计划继续行驶。`
      const hazardVN = hazard ? (HAZARD_VN_MAP[hazard] || hazard) : ''
      const viSwitch = viaDispatch
        ? 'Trung tâm điều độ đã chuyển về tuyến đường ban đầu'
        : 'Đã chuyển về tuyến đường ban đầu'
      const vi = hazardVN
        ? `Đã gỡ bỏ rủi ro. Rủi ro ${hazardVN} trên ${loc} của tuyến đường ban đầu đã được gỡ bỏ. ${viSwitch}, tiếp tục theo kế hoạch。`
        : `Đã gỡ bỏ rủi ro. Tuyến đường ban đầu đã thông suốt. ${viSwitch}, tiếp tục theo kế hoạch。`
      this.showPush('ok', viaDispatch ? '调度已恢复原路线' : '已切回原路线', zh)
      const tag = hazard ? `（${hazard}）` : ''
      this.pushLog.unshift({ time, text: `${viaDispatch ? '调度中心下发原路线（风险已解除）' : '风险解除，已切回原路线'}${tag}` })
      // 非 force：这是「恢复正常」的告知性播报，不该打断仍在进行的灾害/出发等安全播报
      if (this.voiceOn) this._interruptAndSpeak([zh, vi])
    },
    /** 当前是否「走平陆运河」通勤态：选定坐船方案 / 联运导航中（目的地=六景港） */
    _isCanalCommuting() {
      return this.isCanalPlan || this.selectedMode === 'canal' || this.resolvedDestinationId === 'LJ'
    },
    /**
     * 平陆运河禁航反馈（canal-block）：只对正在走去平陆运河方向的在途用户生效。
     * 物流任务司机：只提示 + 播报「调度将换线」，实际换线由调度大屏下发任务变更后
     * 走既有 outreach-update 编排（未接单不受大屏控制的产品不变量同样不受影响）；
     * 普通用户：恢复原行程目的地并弹出公路替代路线面板，自助点选即换。
     */
    async _onCanalBlock(data) {
      // 先记实时禁航态（无论是否在去运河途中）：首页/路线预览的坐船推荐位据此标红
      this.canalBlockLive = { blocked: true, reason: (data && data.reason) || '' }
      if (!this._isCanalCommuting()) return
      const reason = (data && data.reason) || '平陆运河触发通航安全红线'
      const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      if (this.mode === 'LOGISTICS') {
        this.showPush('danger', '平陆运河禁航',
          `${reason}。车辆滚装航段暂停，调度中心将在大屏为您改换公路方案，请留意调度任务变更通知并确认接收。`)
        this.pushLog.unshift({ time, text: '平陆运河禁航：等待调度大屏换线' })
        if (this.voiceOn) {
          // force：禁航是安全关键提示，出发播报占用中也要打断出声，不能静默丢弃
          this._interruptAndSpeak([`紧急提示。平陆运河禁航，${reason}。调度中心将为您改换公路路线，请留意调度通知。`], true)
        }
        return
      }
      // 普通用户自助换线
      this.showPush('danger', '平陆运河禁航',
        `${reason}，船舶航段暂停行驶。已为您规划公路替代路线，点选即可切换。`)
      this.pushLog.unshift({ time, text: '平陆运河禁航：已弹出公路替代路线供选择' })
      if (this.voiceOn) {
        // 同样 force：禁航播报不容许被出发播报的占用锁静默吞掉
        this.speakQueue([
          `紧急提示。平陆运河禁航，${reason}。已为您规划公路替代路线，请在屏幕上选择。`,
          `Cảnh báo khẩn cấp: kênh đào Bình Lục cấm hành. Đã lên phương án đường bộ thay thế, vui lòng chọn trên màn hình。`
        ], { force: true })
      }
      // 回到公路出行方式：清掉坐船选定与运河方案标记，不再展示「可坐船」选项
      this.selectedMode = 'road'
      this.activePlanId = null
      this.canalOption = null
      if (this._planSavedDestination && this.resolvedDestinationId === 'LJ') {
        // 导航中把目的地从六景港改回原行程目的地：抑制 watcher 清路线/销毁地图
        // （Vue watcher 异步刷新，必须等一个 tick 后再解除抑制标志）
        this._switchingPlan = true
        const oldDest = this.resolvedDestinationId
        this.destinationId = this._planSavedDestination
        this._planSavedDestination = null
        // 去港口的运河模式关注不再需要，停掉避免下次禁航误点名
        axios.post('/api/agent/unregister', null, {
          params: { originId: this.resolvedOriginId, destinationId: oldDest }
        }).catch(() => {})
        await this.$nextTick()
        this._switchingPlan = false
      }
      // 复用灾害绕行面板：按恢复后的公路 O/D 拉候选并弹出（点选即换）。
      // 禁弹判据在这里不适用：人在运河水运方案上，公路候选都是新选项
      this.loadRerouteOptions('平陆运河禁航', false).catch(() => {})
    },
    /** 运河恢复通航（canal-recover）：仍在运河方案上或执行物流任务的在途用户收到提示 */
    _onCanalRecover(data) {
      // 清除实时禁航态：推荐位回到正常「可坐船」展示（新搜索的后端快照也已是未禁航）
      this.canalBlockLive = { blocked: false, reason: '' }
      if (this.mode !== 'LOGISTICS' && !this._isCanalCommuting()) return
      const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      this.showPush('ok', '平陆运河恢复通航',
        (data && data.reason) || '通航条件回到禁航红线之上，陆水联运方案恢复可用（如需坐船请重新选择水运方案）')
      this.pushLog.unshift({ time, text: '平陆运河恢复通航' })
    },
    /**
     * 「这条候选就是我正在走的那条线吗」——用于灾害只打到别的走廊时不再弹换线面板。
     *
     * 只比 key 不够：后端按边集 Jaccard 出候选，同一条友谊关走廊的两种走法会拿到
     * 不同 key（`alternate` 与 `recommended`），于是司机看到「绕行方案：公路经友谊关」
     * 而脚下就是友谊关——绕了个寂寞。这里补上口岸走廊比对（与 _mergeCorridorDuplicates
     * 同源思路，但用 inferPortFromNodeIds 的口岸节点做指纹，比 via 文本更稳）。
     */
    _isOnCorridor(c) {
      if (!c) return false
      if (c.key === this.selectedKey) return true
      const cur = this.inferPortFromNodeIds(this.route && this.route.pathNodeIds)
      const mine = this.inferPortFromNodeIds(c.nodeIds)
      return !!cur && cur === mine
    },
    /** 拉取当前 O/D 的可选路线（含灾害概率），作为灾害发生后的绕行方案。
     *  suppressIfOnBest：当前已走在风险最低的一条上时不再弹面板（默认开）；
     *  运河禁航传 false——人在水运方案上，公路候选全是新选项，必须弹。 */
    async loadRerouteOptions(reason, suppressIfOnBest = true) {
      if (this.rerouteLoading) return
      // 绕行面板已经开着：同一事件不再重复拉取与播报，等司机点选或关闭后再响应新事件
      if (this.rerouteOptions.length) return
      // 单点保险：接单模式下道路灾害一律不弹「点选即切换」自助面板——换线权在调度中心，
      // 改挂「等待调度」卡（与 SSE route-update / checkPush 的 isTaskAccepted 判据同源）。
      // 运河禁航传 suppressIfOnBest=false（人在水运方案上换公路属调度已定的执行），不在此列。
      if (suppressIfOnBest && this.isTaskAccepted) { this._armWaitDispatch(reason); return }
      this.rerouteLoading = true
      try {
        const r = await axios.get('/api/route/candidates', {
          params: { originId: this._navOriginId(), destinationId: this.resolvedDestinationId, cargoType: 'cold' },
          timeout: 30000
        })
        const list = r.data.candidates || []
        // 全域受阻：这些替代路线本身就是降级放行的产物，弹换线面板只会让司机
        // 以为“有得选”，实际只是换一个风险区——改为返回/停靠建议
        if (this._applyCorridorAdvisory(r.data && r.data.advisory)) return
        // 展示所有候选走廊（按途经指纹合并同走廊重复项），司机自行选择
        const merged = this._mergeCorridorDuplicates(list)
        // 自动绕行后当前线本身就是排序置顶的那条（与面板「风险最低」同一套判据）：
        // 没有更值得换的方案，弹面板只会打扰司机——不弹，只语音告知灾害本身
        // （optionCount 传 0，播报不会引导「请在屏幕上选择」）。
        const rank = p => (p == null || p < 0) ? Infinity : p
        const top = [...merged].sort((a, b) => rank(a.hazardProbability) - rank(b.hazardProbability))[0]
        if (suppressIfOnBest && top && this._isOnCorridor(top)) {
          this.announceHazard(reason, 0)
          return
        }
        this.rerouteOptions = merged
        this.announceHazard(reason, merged.length)
      } catch (e) {
        this.announceHazard(reason, 0)
      } finally {
        this.rerouteLoading = false
      }
    },
    /** 司机点选绕行方案：切换路线并重新规划 */
    async applyRerouteOption(key) {
      this.selectedKey = key
      const opt = this.rerouteOptions.find(o => o.key === key)
      if (opt) {
        this.candidates = this.rerouteOptions
        this.selectCandidate(key, true)
        if (opt.hazardProbability >= 0) this.forecast = { probability: opt.hazardProbability, level: opt.hazardLevel }
      }
      this.rerouteOptions = []
      try {
        await this.fetchRoute()
        const status = this._buildStatusZh()
        this.showPush('success', '已切换路线', `已按您选择的方案重新规划，预计 ${this.route.estimatedHours || '--'} 小时`)
        this.speakQueue([
          `已按您选择的路线重新规划。${status}`,
          `Đã cập nhật tuyến đường mới theo lựa chọn của bạn。Thời gian trễ dự kiến ${this.route.extraHours || 0} giờ。`
        ])
      } catch (e) {
        this.showPush('danger', '切换失败', '路线切换失败，请重试')
      }
    },
    /** 途中实时预测：刷新当前路线的灾害概率；概率显著上升或已发生则提示 */
    async refreshForecast(silent) {
      try {
        const r = await axios.get('/api/ai/route-risk', {
          params: {
            originId: this.originId,
            destinationId: this.destinationId,
            choice: this.selectedKey,
            deep: true
          },
          timeout: 60000
        })
        const prev = this.forecast
        this.forecast = r.data
        const p = r.data.probability
        if (!silent && prev && prev.probability != null && p >= 60 && p - prev.probability >= 20) {
          const status = this._buildStatusZh()
          this.showPush('warn', '概率上升',
            `当前路线${this.topHazardName(r.data)}概率升至 ${p}%（${r.data.level}）：${r.data.reason || ''}`)
          // force：灾害概率骤升属于安全信息，允许打断「导航开始」的独占播报
          this.speakQueue([
            `请注意，当前路线${this.topHazardName(r.data)}概率上升至百分之${p}。${status}`,
            `Cảnh báo: xác suất thiên tai trên tuyến đường hiện tại tăng lên ${p} phần trăm。Thời gian trễ dự kiến ${this.route.extraHours || 0} giờ。`
          ], { force: true })
        }
        return r.data
      } catch (e) {
        return null
      }
    },
    // 拉取途径城市天气（导航后自动+轮询+手动刷新共用）
    async loadWeatherForRoute() {
      try {
        const r = await axios.get('/api/weather/real', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId },
          timeout: 45000
        })
        this.weatherPoints = r.data.points || []
      } catch (e) { /* 天气拉取失败不影响导航主流程 */ }
    },
    // 播报当前 AI 双语预警（按钮触发；中越双语）
    async refreshWeather() {
      try {
        const r = await axios.post('/api/weather/real/refresh', null, {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId },
          timeout: 45000
        })
        this.weatherPoints = r.data.points || []
        this.risks = r.data.risks || []
      } catch (e) {
        this.showPush('info', '实时气象不可用', '已自动切换离线演示模式')
      }
    },
    async buildWarning() {
      if (this.warningLoading) return
      this.warningLoading = true
      // 保留旧文案，不再用"AI 生成中"覆盖，避免文案闪烁消失
      try {
        const r = await axios.get('/api/ai/warning-bilingual', { params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId }, timeout: 60000 })
        const newWarning = r.data && (r.data.message || r.data.warning || r.data.content || '')
        if (newWarning) {
          this.warning = newWarning
          // 从双语内容中完整提取中文和越南语段落，确保播报内容与屏幕文案一致
          const { zh, vi } = this._extractBilingualParts(this.warning)
          // 预警播报打断当前语音，立即播放
          // force：这条由司机主动点「AI 预警」触发，被出发播报的占用锁吞掉会让按钮像是坏了
          this._interruptAndSpeak(vi ? [zh, vi] : [zh, 'Cảnh báo thời tiết đã được cập nhật。Vui lòng kiểm tra và lái xe cẩn thận。'], true)
        }
      } catch (e) {
        if (!this.warning) this.warning = '生成失败，请检查后端服务'
      } finally {
        this.warningLoading = false
      }
    },
    /**
     * 从 AI 双语文本中正确提取中文和越南语完整段落。
     * 支持多种分隔格式：【中文】/【Tiếng Việt】、--- 分隔线、自动语言切换检测。
     * 提取的段落会经过 normalizeForTTS 清洗，确保播报内容与屏幕显示文案一致。
     */
    _extractBilingualParts(text) {
      if (!text) return { zh: '', vi: '' }

      // 越南语特有字符正则（用于语言检测）
      const VI_DIACRITICS = /[àáảãạâầấẩẫậăằắẳẵặèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/i

      // 策略1：按【中文】/【Tiếng Việt】等显式标记分段
      const zhMarker = /【中文】|##?\s*中文|\[中文\]/i
      const viMarker = /【(?:Tiếng\s*Việt|Vietnamese?)】|##?\s*(?:Tiếng\s*Việt|Vietnamese?)|\[(?:Tiếng\s*Việt|Vietnamese?)\]/i

      const zhIdx = text.search(zhMarker)
      const viIdx = text.search(viMarker)

      let zh = '', vi = ''

      if (viIdx >= 0) {
        // 找到越南语标记：之前为中文段，之后为越南语段
        zh = text.substring(zhIdx >= 0 ? zhIdx : 0, viIdx)
        vi = text.substring(viIdx)
        // 清理标记文本
        zh = zh.replace(zhMarker, '').trim()
        vi = vi.replace(viMarker, '').trim()
      } else if (zhIdx >= 0) {
        // 只有中文标记，全文当中文
        zh = text.replace(zhMarker, '').trim()
      }

      // 策略2：无显式标记时，按分隔线（---、***、___）分段
      if (!zh && !vi) {
        const sepMatch = text.match(/\n\s*(?:---+|\*\*\*+|___+)\s*\n/)
        if (sepMatch && sepMatch.index > 0) {
          zh = text.substring(0, sepMatch.index).trim()
          vi = text.substring(sepMatch.index + sepMatch[0].length).trim()
        }
      }

      // 策略3：仍无结果时，逐行按语言归类，合并完整段落
      if (!zh && !vi) {
        const lines = text.split('\n')
        const zhLines = []
        const viLines = []
        let inViSection = false
        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed) continue
          // 检测到越南语字符后切换到越南语段落
          if (!inViSection && VI_DIACRITICS.test(trimmed)) {
            inViSection = true
          }
          if (inViSection) {
            viLines.push(trimmed)
          } else {
            zhLines.push(trimmed)
          }
        }
        zh = zhLines.join('\n')
        vi = viLines.join('\n')
      }

      // 兜底：如果越南语段为空，至少保留中文段
      if (!zh && !vi) {
        zh = text.trim()
      }

      // 通过 normalizeForTTS 清洗（移除 emoji/特殊符号、转换单位等），确保播报与文案一致
      zh = normalizeForTTS(zh, 'zh-CN')
      vi = vi ? normalizeForTTS(vi, 'vi-VN') : ''

      return { zh, vi }
    },
    async loadCustoms() {
      try {
        const r = await axios.get('/api/customs/efficiency')
        this.customsList = r.data || []
      } catch (e) { /* ignore */ }
    },
    async searchTips(t) {
      try {
        const r = await axios.get('/api/knowledge/search', { params: { q: t, limit: 3 } })
        this.kbResults = r.data || []
      } catch (e) { /* ignore */ }
    },
    sev(s) {
      return (s || '').toLowerCase()
    },
    /** 把用户输入（ID 或中文/越南语名称）解析为节点 ID */
    _resolveNodeId(input) {
      if (!input) return input
      const v = input.trim()
      return NAME_TO_ID[v] || NAME_TO_ID[v.toLowerCase()] || v
    },
    /**
     * 将用户输入的起终点归一到路网节点 id（就地回写 this.originId/destinationId）。
     * - 已是内置节点 id / 中越文节点名 / 地名库带 nodeId 的条目 → _resolveNodeId 直接命中；
     * - 其余（冷门地名、只在地名库里但无 nodeId 的口岸/枢纽）→ 地理编码 + 吸附拿 nodeId。
     * 回写后所有依赖 resolvedOriginId/resolvedDestinationId 的下游调用（候选/算路/熔断/陆水联运）全部一致。
     * @throws 查无此地名或超出路网覆盖范围（由调用方转提示）
     */
    async _normalizeOD() {
      const norm = async (val, label) => {
        if (!val || !String(val).trim()) return val
        const id = this._resolveNodeId(val)
        if (NODE_NAMES[id]) return id // 已是已知节点（id 或内置中/越文名）
        const r = await resolvePlaceToNode(String(val).trim(), label) // 地理编码 + 吸附
        return r.nodeId
      }
      const [o, d] = await Promise.all([norm(this.originId, '起点'), norm(this.destinationId, '终点')])
      if (o) this.originId = o
      if (d) this.destinationId = d
    },
    /** 保存司机信息到本地（比赛演示：仅内存保存） */
    saveDriverInfo() {
      this.cargo.from = this.originName
      this.cargo.to = this.destinationName
      this.driverInfoSaved = true
      this.showPush('success', '已保存', '司机信息已更新')
      setTimeout(() => { this.driverInfoSaved = true }, 2000)
    }
  }
}
</script>

<style scoped>
* { box-sizing: border-box; margin: 0; padding: 0; }
/* 棱彩主色（与调度大屏同一套色相）：只用于品牌装饰——hero、主按钮、选中态、装饰流光。
   注意：状态色（绿=通畅 / 橙=预警 / 红=绕行）保持语义不变，那是驾驶场景的安全信号，
   不能为了好看把"警示"变成"品牌色"。 */
.phone {
  /* 品牌蓝：结构/语义主色（选中描边、链接、状态等仍用它） */
  --brand:#1663e6; --brand-deep:#0f4fc4; --brand-soft:#e8f0fe;
  /* 棱彩主色（与调度大屏同一套色相：靛蓝/天蓝/青/紫/品红）：主按钮、hero、选中态、装饰流光 */
  --prism-1:#6366f1; --prism-2:#38bdf8; --prism-3:#22d3ee; --prism-4:#a855f7; --prism-5:#ec4899;
  --prism-grad: linear-gradient(120deg, #6366f1 0%, #38bdf8 28%, #22d3ee 50%, #a855f7 74%, #ec4899 100%);
  max-width: 420px; margin: 0 auto; min-height: 100vh;
  background-color: #f3f5f8;
  font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif; color: #1a1a2e; position: relative; box-shadow: 0 0 40px rgba(0,0,0,.08); }
.phone.nav-mode { max-width: 100%; }
.home-page { min-height: 100vh; display: flex; flex-direction: column; }
/* ===== 高德风格首页：全屏地图 + 悬浮搜索/宫格/去X卡/胶囊 Tab/底部抽屉 ===== */
.home-page.amap { position: relative; min-height: 100vh; overflow: hidden; background: #e8ecf3; }
.home-map { position: absolute; inset: 0; z-index: 0; }
.hm-ctrl { position: absolute; right: 12px; top: 96px; z-index: 5; display: flex; flex-direction: column; gap: 8px; }
.hm-ctrl-btn { width: 44px; height: 44px; border: none; border-radius: 12px; background: #fff; box-shadow: 0 2px 10px rgba(0,0,0,.15); font-size: 20px; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.hm-ctrl-btn.base-btn { font-size: 13px; font-weight: 700; color: #374151; }
.hm-ctrl-btn.lang-toggle { flex-direction: column; gap: 1px; color: #475569; }
.hm-ctrl-btn.lang-toggle .lang-tag { font-size: 10px; font-weight: 800; line-height: 1; }
.hm-ctrl-btn.lang-toggle.on { color: #0a7d43; box-shadow: 0 0 0 2px rgba(10,125,67,.35), 0 2px 10px rgba(0,0,0,.15); }
.nav-back.nav-lang { width: auto; padding: 0 10px; border-radius: 18px; font-size: 12px; font-weight: 800; }
.nav-back.nav-lang.on { color: #0a7d43; background: rgba(10,125,67,.16); }
/* 定位图钉图标：深灰经典水滴形（参照系统定位图标），随按钮文字色 */
.pin-ico { width: 22px; height: 22px; fill: #3d4450; }
.nav-btn .pin-ico { width: 20px; height: 20px; }
.hm-modechip { position: absolute; left: 12px; top: 12px; z-index: 5; display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 999px; background: rgba(255,255,255,.92); box-shadow: 0 2px 10px rgba(0,0,0,.12); font-size: 12px; }
.hm-modechip .mode-name { font-weight: 600; }
.hm-modechip .strip-dot { width: 8px; height: 8px; border-radius: 50%; background: #22c55e; }
.hm-modechip .mode-act { border: none; border-radius: 999px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
.hm-modechip .mode-act.accept { background: #2563eb; color: #fff; }
.hm-modechip .mode-act.preview { background: #eaf1ff; color: #2563eb; }
.hm-modechip .mode-act.done { background: #e8f5e9; color: #2e7d32; }
.hm-modechip .mode-act-exit { color: #94a3b8; cursor: pointer; }
.hm-modechip .mode-hint { color: #64748b; }
.hm-search { position: absolute; left: 12px; right: 12px; z-index: 6; transition: bottom .3s ease; }
.hm-search:not(.open) { bottom: 250px; }
/* 宫格收起后搜索框沉到贴近 Tab 栏，不留中段空白 */
.hm-search:not(.open).lowered { bottom: 118px; }
.hm-search.open { bottom: 76px; }
.hm-search-bar { display: flex; align-items: center; gap: 10px; background: #fff; border-radius: 999px; padding: 14px 18px; box-shadow: 0 4px 18px rgba(0,0,0,.15); cursor: pointer; }
.hm-search-bar .hm-ph { flex: 1; color: #94a3b8; font-size: 15px; }
.hm-search-bar .hm-ico { font-size: 18px; }
.hm-search-bar .hm-mic { display: flex; align-items: center; }
/* 麦克风现在是真入口（弹开 AI志宁）：把 button 默认样式抹平，保持与原装饰图标一致观感 */
.hm-mic-btn { border: none; background: transparent; padding: 0; cursor: pointer; }
.hm-search-panel { background: #fff; border-radius: 16px; padding: 14px; box-shadow: 0 6px 24px rgba(0,0,0,.18); max-height: 60vh; overflow-y: auto; }
.hm-search-actions { display: flex; gap: 8px; margin-top: 10px; }
.hm-search-actions .start-btn { flex: 1; }
.hm-close { border: 1px solid #e2e8f0; background: #f8fafc; border-radius: 10px; padding: 0 14px; cursor: pointer; }
.hm-grid { position: absolute; left: 12px; right: 12px; bottom: 150px; z-index: 5; display: flex; justify-content: space-between; gap: 6px; touch-action: none; }
.hm-grid-item { flex: 1; border: none; background: transparent; display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer; }
.hm-grid-ico { width: 46px; height: 46px; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: rgba(255,255,255,.93); border: 1px solid rgba(15,23,42,.07); box-shadow: 0 2px 8px rgba(15,23,42,.14); }
.hm-grid-label { font-size: 11px; color: #3ec7ff; font-weight: 500; letter-spacing: .5px; text-shadow: 0 1px 4px rgba(0,20,50,.65); }
/* 收缩态：宫格与导航卡下滑淡出，只留搜索框；把手/胶囊负责恢复 */
.hm-grid, .hm-gocard { transition: transform .3s ease, opacity .25s ease; }
.hm-grid.collapsed, .hm-gocard.collapsed { transform: translateY(150%); opacity: 0; pointer-events: none; }
.hm-collapse-grip { position: absolute; left: 50%; transform: translateX(-50%); bottom: 226px; z-index: 5; width: 46px; height: 18px; padding: 0; border: none; border-radius: 9px; background: rgba(255,255,255,.85); color: #64748b; font-size: 12px; line-height: 1; cursor: pointer; box-shadow: 0 1px 4px rgba(15,23,42,.15); touch-action: none; }
/* 收起态恢复胶囊：放在搜索框下方、Tab 栏上方 */
.hm-expand-pill { position: absolute; left: 50%; transform: translateX(-50%); bottom: 80px; z-index: 5; border: none; border-radius: 14px; background: rgba(255,255,255,.92); color: #3ec7ff; font-size: 12px; font-weight: 500; padding: 6px 14px; cursor: pointer; box-shadow: 0 2px 8px rgba(15,23,42,.18); touch-action: none; }
.hm-gocard { position: absolute; left: 12px; right: 12px; bottom: 84px; z-index: 5; display: flex; align-items: center; gap: 12px; background: #fff; border-radius: 16px; padding: 12px 14px; box-shadow: 0 4px 18px rgba(0,0,0,.15); }
.hm-go-ico { width: 40px; height: 40px; border-radius: 10px; background: #e3f2fd; display: flex; align-items: center; justify-content: center; font-size: 20px; }
.hm-go-info { flex: 1; }
.hm-go-title { font-size: 15px; font-weight: 600; color: #1e293b; }
.hm-go-meta { font-size: 12px; color: #64748b; margin-top: 2px; }
.hm-go-bar { height: 3px; border-radius: 2px; background: #22c55e; margin-top: 6px; }
.hm-go-btn { border: none; background: #2563eb; color: #fff; border-radius: 999px; padding: 10px 22px; font-size: 15px; cursor: pointer; }
.hm-tabbar { position: absolute; left: 12px; right: 12px; bottom: 12px; z-index: 6; display: flex; background: #fff; border: 1px solid #eceff3; border-radius: 14px; padding: 4px; box-shadow: 0 2px 10px rgba(16,24,40,.08); }
.hm-tab { flex: 1; position: relative; border: none; background: transparent; border-radius: 10px; padding: 7px 0 6px; display: flex; flex-direction: column; align-items: center; gap: 2px; cursor: pointer; }
.hm-tab.active { background: var(--brand-soft); }
.hm-tab-ico { font-size: 18px; line-height: 1; filter: grayscale(1); opacity: .6; transition: filter .15s, opacity .15s; }
.hm-tab.active .hm-tab-ico { filter: none; opacity: 1; }
.hm-tab-label { font-size: 11px; color: #667085; }
.hm-tab.active .hm-tab-label { color: var(--brand); font-weight: 600; }
.hm-tab-dot { position: absolute; top: 6px; right: 22%; width: 8px; height: 8px; border-radius: 50%; background: #ef4444; }
.hm-sheet { position: absolute; left: 0; right: 0; bottom: 70px; z-index: 8; background: #fff; border-radius: 16px 16px 0 0; border-top: 1px solid #e7eaf0; box-shadow: 0 -8px 24px rgba(16,24,40,.10); max-height: 62vh; display: flex; flex-direction: column; }
.hm-sheet-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px 10px; font-size: 15px; font-weight: 600; color: #111827; border-bottom: 1px solid #f0f2f5; }
.hm-sheet-close { border: none; background: transparent; color: #98a2b3; width: 28px; height: 28px; border-radius: 8px; cursor: pointer; font-size: 14px; transition: background .15s, color .15s; }
.hm-sheet-close:hover { background: #f2f4f7; color: #475467; }
.hm-sheet-body { overflow-y: auto; padding: 10px 12px 20px; background: #f7f8fa; }
.home-hero { position: relative; overflow: hidden; background: var(--prism-grad); padding: 48px 24px 36px; text-align: center; }
.home-logo h1 { color: #fff; font-size: 24px; font-weight: 800; margin: 0; letter-spacing: 1px; }
.home-sub { color: rgba(255,255,255,.75); font-size: 13px; margin-top: 6px; }
.logo-icon { font-size: 48px; display: block; margin-bottom: 12px; }
.home-form { flex: 1; padding: 0 16px; margin-top: -20px; position: relative; z-index: 2; }
.form-card { background: #fff; border-radius: 16px; padding: 20px 16px; box-shadow: 0 4px 24px rgba(0,0,0,.08); }
/* 起终点一体输入盒：左侧站点轨道 + 分隔线 + 右侧交换按钮，替代盒式双输入框 */
.od-box { display: flex; align-items: stretch; gap: 10px; background: #f5f7fa; border-radius: 12px; padding: 6px 12px; }
.od-rail { display: flex; flex-direction: column; align-items: center; padding: 14px 0; }
.od-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.od-dot.from { background: #22a35a; }
.od-dot.to { background: #e53935; }
.od-line { width: 0; flex: 1; margin: 3px 0; border-left: 2px dotted #c7d0dd; }
.od-fields { flex: 1; min-width: 0; }
.od-fields input { width: 100%; border: none; background: transparent; font-size: 15px; color: #1a1a2e; outline: none; padding: 11px 0; }
.od-fields input::placeholder { color: #a7b2c2; }
.od-divider { height: 1px; background: #e3e8ef; }
.od-swap { align-self: center; flex-shrink: 0; width: 30px; height: 30px; border-radius: 50%; background: #fff; border: 1px solid #e3e8ef; color: var(--brand); font-size: 14px; cursor: pointer; display: flex; align-items: center; justify-content: center; box-shadow: 0 1px 4px rgba(16,24,40,.08); transition: transform .25s; }
.od-swap:active { transform: rotate(180deg); }
.quick-picks { margin: 16px 0; }
.quick-label { font-size: 11px; color: #999; font-weight: 600; display: block; margin-bottom: 8px; }
.quick-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.quick-chip { padding: 6px 12px; border-radius: 20px; border: 1px solid #e3f2fd; background: #f5f9ff; color: var(--prism-1); font-size: 12px; cursor: pointer; transition: all .2s; font-weight: 500; }
.quick-chip:hover { background: #e3f2fd; border-color: #bbdefb; }
.quick-chip:active { transform: scale(.96); }
.start-btn { width: 100%; margin-top: 4px; padding: 15px; background: var(--prism-grad); background-size: 220% 100%; border: none; border-radius: 12px; color: #fff; font-size: 16px; font-weight: 600; cursor: pointer; transition: filter .15s, transform .1s; box-shadow: 0 4px 16px rgba(99,102,241,.38); display: flex; align-items: center; justify-content: center; gap: 8px; animation: start-prism-flow 7s ease-in-out infinite; }
.start-btn:active:not(:disabled) { filter: brightness(.9); }
.start-btn:active { transform: scale(.98); }
.start-btn:disabled { opacity: .5; cursor: not-allowed; animation: none; }
@keyframes start-prism-flow { 0% { background-position: 0% 50%; } 50% { background-position: 100% 50%; } 100% { background-position: 0% 50%; } }
.cand-section { margin-top: 12px; }
.cand-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; padding: 0 4px; }
.cand-header span:first-child { font-size: 13px; font-weight: 700; color: #1a1a2e; }
.cand-hint { font-size: 11px; color: #999; }
.cand-item { padding: 14px; border-radius: 12px; margin-bottom: 8px; cursor: pointer; background: #fff; border: 2px solid #eef2f6; transition: all .2s; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 1px 4px rgba(0,0,0,.03); }
.cand-item:active { transform: scale(.99); }
.cand-item.sel { border-color: var(--prism-1); background: #f0f6ff; box-shadow: 0 0 0 3px rgba(99,102,241,.08); }
/* 可坐船推荐位（平陆运河）：与公路候选并列，蓝色水运系区分 */
.canal-cand { border-color: rgba(14,165,233,.30); background: linear-gradient(135deg, #f0f9ff 0%, #ecfeff 100%); }
.canal-cand.sel { border-color: #0ea5e9; box-shadow: 0 0 0 3px rgba(14,165,233,.14); background: linear-gradient(135deg, #e0f2fe 0%, #cffafe 100%); }
/* 禁航态：整卡标红，与正常 cyan 推荐位形成一眼可辨的反差 */
.canal-cand.canal-blocked { border-color: rgba(225,29,72,.55); background: linear-gradient(135deg, #fff1f2 0%, #ffe4e6 100%); opacity: .92; }
.canal-badge { background: linear-gradient(135deg, #0ea5e9, #06b6d4); color: #fff; padding: 3px 10px; border-radius: 10px; font-weight: 700; font-size: 12px; white-space: nowrap; }
.canal-badge.blocked { background: linear-gradient(135deg, #e11d48, #be123c); }
.meta-tag.blocked { color: #e11d48; background: rgba(225,29,72,.10); border-color: rgba(225,29,72,.35); font-weight: 700; }
.pv-flag.canal-flag.blocked { background: rgba(225,29,72,.12); color: #e11d48; border: 1px solid rgba(225,29,72,.4); }
.canal-flag { background: rgba(14,165,233,.14); color: #0369a1; }
.cand-left { display: flex; align-items: flex-start; gap: 10px; flex: 1; min-width: 0; }
.cand-radio { width: 18px; height: 18px; border-radius: 50%; border: 2px solid #c8d6e5; flex-shrink: 0; margin-top: 2px; transition: all .2s; }
.cand-radio.on { border-color: var(--brand); background: var(--brand); box-shadow: inset 0 0 0 3px #fff; }
.cand-info { flex: 1; min-width: 0; }
.cand-title { font-size: 14px; font-weight: 700; color: #1a1a2e; }
.cand-via { font-size: 11px; color: #999; margin-top: 2px; }
.cand-meta { display: flex; gap: 6px; margin-top: 6px; }
.meta-tag { font-size: 11px; padding: 2px 8px; border-radius: 10px; background: #f0f2f5; color: #666; font-weight: 600; }
.meta-tag.warn { background: #fff3e0; color: #c2410c; }
.meta-tag.ok { background: #e7f6ec; color: #16a34a; }
.cand-right { display: flex; flex-direction: column; align-items: center; gap: 4px; flex-shrink: 0; }
.prob-arrow { font-size: 12px; color: var(--prism-1); }
.home-bottom { padding: 16px; padding-bottom: 24px; }
/* 模式标识（剧本第一/四幕）：普通导航模式 vs 物流任务模式 */
.mode-bar {
  display: flex; align-items: center; gap: 8px;
  font-size: 12px; padding: 7px 10px; border-radius: 10px; margin-bottom: 8px;
  border: 1px solid transparent;
}
.mode-bar.pub { background: #f2f5f9; border-color: #dde4ee; }
.mode-bar.logi { background: #eaf3ff; border-color: #b9d4f5; }
.mode-name { font-weight: 700; color: #1a1a2e; }
.mode-hint { color: #6b7a90; font-size: 11px; flex: 1; }
.mode-act {
  border: none; border-radius: 8px; padding: 3px 10px;
  font-size: 11px; font-weight: 700; cursor: pointer;
}
.mode-act.accept { background: var(--brand); color: #fff; }
.mode-act.accept:disabled { opacity: .6; cursor: default; }
.mode-act.preview { background: var(--brand-soft); color: var(--brand); }
.mode-act.done { background: #e4f6ea; color: #16a34a; cursor: default; }
.mode-act-exit { font-size: 11px; color: #8a95a8; cursor: pointer; text-decoration: underline; }
/* 路线状态条：绿色=畅通（剧本第四幕） */
.status-strip {
  display: flex; align-items: center; gap: 7px;
  padding: 7px 12px; border-radius: 10px; margin-bottom: 8px;
  font-size: 12px; font-weight: 700;
}
.status-strip .strip-dot { width: 9px; height: 9px; border-radius: 50%; background: currentColor; }
.status-strip.ok { background: rgba(22,163,74,.12); color: #16a34a; }
.status-strip.warn { background: rgba(217,119,6,.14); color: #d97706; }
.status-strip.rerouted { background: rgba(225,29,72,.14); color: #e11d48; }
.me-locked {
  font-size: 11px; line-height: 1.6; color: var(--prism-1);
  background: #eaf3ff; border: 1px solid #b9d4f5;
  border-radius: 8px; padding: 6px 9px; margin-bottom: 8px;
}
.info-bar { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #666; margin-bottom: 12px; }
.driver-tag { font-weight: 600; color: #1a1a2e; }
.cargo-tag { background: #f0f2f5; padding: 2px 8px; border-radius: 8px; font-size: 11px; }
.link { color: var(--prism-1); cursor: pointer; font-weight: 600; }
.home-nav { display: flex; gap: 4px; background: #fff; border-radius: 12px; padding: 4px; box-shadow: 0 1px 6px rgba(0,0,0,.04); }
.home-nav span { flex: 1; text-align: center; padding: 8px; font-size: 12px; color: #999; cursor: pointer; border-radius: 10px; font-weight: 600; transition: all .2s; }
.home-nav span.active { background: var(--brand-soft); color: var(--brand); }
.tab-content { margin-top: 12px; padding-bottom: 80px; }
.card { background: #fff; border: 1px solid #e7eaf0; border-radius: 12px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(16,24,40,.04); }
.card-head { display: flex; justify-content: space-between; align-items: center; font-size: 14px; font-weight: 600; margin-bottom: 10px; color: #111827; }
/* 卡片标题左侧品牌小竖条：取代 emoji 图标，统一视觉锚点 */
.card-head > span:first-child { display: inline-flex; align-items: center; }
.card-head > span:first-child::before { content: ''; width: 3px; height: 13px; border-radius: 2px; background: var(--brand); margin-right: 7px; }
.count { font-size: 11px; font-weight: 700; padding: 2px 10px; border-radius: 20px; color: #fff; background: #e53935; }
.refresh { font-size: 12px; padding: 5px 12px; border-radius: 8px; background: #fff; color: var(--brand); border: 1px solid #cdd9ee; cursor: pointer; font-weight: 500; transition: background .15s, border-color .15s; }
.refresh:hover { background: var(--brand-soft); border-color: var(--brand); }
.refresh:disabled { opacity: .5; }
/* 语音开关：开启态用绿色 tint，区别于普通操作按钮 */
.refresh.on { background: #e7f6ec; border-color: #b5e3c8; color: #16a34a; }
.empty { font-size: 12px; color: #999; text-align: center; padding: 16px 0; }
.risk-list { display: flex; flex-direction: column; gap: 6px; }
.risk-item { padding: 10px; border-radius: 10px; cursor: pointer; transition: all .2s; border-left: 3px solid #f59e0b; background: #fafafa; }
.risk-item:hover { background: #f5f5f5; }
.risk-item.critical { border-left-color: #e53935; background: #fff5f5; }
.risk-item.high { border-left-color: #ef6c00; background: #fff8f5; }
.risk-reason { font-size: 13px; font-weight: 600; color: #333; }
.risk-edge { font-size: 11px; color: #999; margin-top: 2px; }
.warning-box { background: #fff8e1; padding: 12px; border-radius: 10px; font-size: 12px; line-height: 1.6; white-space: pre-wrap; max-height: 240px; overflow: auto; border: 1px solid #ffe0b2; color: #5d4037; }
.kb-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { padding: 6px 14px; border-radius: 20px; border: 1px solid #e0e0e0; background: #fff; color: #555; font-size: 12px; cursor: pointer; transition: all .2s; font-weight: 500; }
.chip:hover { background: #e3f2fd; border-color: #90caf9; color: var(--prism-1); }
.kb-results { margin-top: 10px; }
.kb-result { border-bottom: 1px solid #f0f0f0; padding: 8px 0; }
.kb-title { font-size: 12px; font-weight: 700; color: var(--prism-1); }
.kb-content { font-size: 12px; color: #666; line-height: 1.5; margin-top: 4px; }
.customs-line { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 13px; color: #333; }
.ch-time { background: #e8f5e9; color: #16a34a; padding: 1px 8px; border-radius: 10px; font-weight: 700; font-size: 12px; }
.ch-adjust { font-size: 10px; color: #c2410c; font-weight: 600; }
/* ===== 「我的」资料页：资料头卡 + 分组列表（无边框行内编辑），避免盒式表单的模板感 ===== */
.me-hero { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e7eaf0; border-radius: 12px; padding: 14px; margin-bottom: 10px; }
.me-avatar { width: 46px; height: 46px; border-radius: 50%; flex-shrink: 0; display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 700; color: #fff; background: linear-gradient(135deg, var(--brand), var(--brand-deep)); }
.me-hero-info { flex: 1; min-width: 0; }
.me-hero-name { font-size: 16px; font-weight: 700; color: #111827; }
.me-hero-sub { display: flex; gap: 6px; margin-top: 4px; flex-wrap: wrap; }
.me-plate { font-size: 11px; font-weight: 700; letter-spacing: .5px; color: var(--brand); background: var(--brand-soft); border-radius: 4px; padding: 1px 6px; }
.me-truck { font-size: 11px; color: #667085; background: #f2f4f7; border-radius: 4px; padding: 1px 6px; }
.me-mode-tag { flex-shrink: 0; font-size: 10px; font-weight: 700; color: #c2410c; background: #fff3e0; border-radius: 999px; padding: 3px 8px; }
.me-group { background: #fff; border: 1px solid #e7eaf0; border-radius: 12px; padding: 0 14px; margin-bottom: 12px; }
.me-row { display: flex; align-items: center; gap: 10px; min-height: 46px; }
.me-row + .me-row { border-top: 1px solid #f0f2f5; }
.me-label { font-size: 13px; color: #667085; width: 64px; flex-shrink: 0; font-weight: 500; }
.me-input { flex: 1; min-width: 0; padding: 8px 0; border: none; background: transparent; font-size: 14px; color: #111827; text-align: right; transition: color .15s; }
.me-input::placeholder { color: #c3ccd9; }
.me-input:focus { outline: none; color: var(--brand); }
.me-input:disabled { color: #98a2b3; -webkit-text-fill-color: #98a2b3; }
.me-select { appearance: none; -webkit-appearance: none; padding-right: 16px; background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' fill='none' stroke='%2398a2b3' stroke-width='1.5' stroke-linecap='round'/%3E%3C/svg%3E") no-repeat right center; }
.me-switch { position: relative; width: 44px; height: 26px; margin-left: auto; flex-shrink: 0; border: none; border-radius: 999px; background: #d5dbe6; cursor: pointer; transition: background .2s; }
.me-switch.on { background: #16a34a; }
.me-knob { position: absolute; top: 2px; left: 2px; width: 22px; height: 22px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(16,24,40,.25); transition: transform .2s; }
.me-switch.on .me-knob { transform: translateX(18px); }
.save-btn { width: 100%; padding: 12px; border-radius: 12px; border: none; background: var(--brand); color: #fff; font-size: 15px; font-weight: 600; letter-spacing: 2px; cursor: pointer; box-shadow: 0 4px 12px rgba(22,99,230,.25); transition: background .15s, transform .12s; }
.save-btn:active { background: var(--brand-deep); transform: scale(.98); }
.nav-screen { position: fixed; inset: 0; z-index: 100; background-color: #eef2fa; background-image: radial-gradient(ellipse 72% 42% at 18% 0%, rgba(99,102,241,.16), transparent 68%), radial-gradient(ellipse 72% 42% at 86% 100%, rgba(168,85,247,.14), transparent 68%); overscroll-behavior: none; display: flex; flex-direction: column; }
/* 地图区域：占据上方剩余空间。地图自身设 z-index:0 形成独立层叠上下文，把 Leaflet 内部图层
   （tilePane 等 pane 的 z-index 高达 200~700）锁在里面，否则它们会盖住顶部状态栏(z-index:10)
   和底部面板(z-index:12)——面板“被底图盖住”就是这个原因 */
.nav-map-wrap { position: relative; flex: 1 1 auto; min-height: 0; }
.nav-map { position: absolute; inset: 0; z-index: 0; touch-action: none; }
/* 底图加载遮罩：瓦片没到位前盖住空白地图区，给出明确加载反馈（不拦截地图手势） */
.map-veil { position: absolute; inset: 0; z-index: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; background: #eef2fa; pointer-events: none; }
.map-veil-spin { width: 26px; height: 26px; border-radius: 50%; border: 3px solid rgba(22,99,230,.2); border-top-color: #1663e6; animation: veil-spin .8s linear infinite; }
@keyframes veil-spin { to { transform: rotate(360deg); } }
.map-veil-text { font-size: 12px; color: #667085; }
/* 自由视角下的一键回中按钮：吸附在底部浮层正上方，浮层高低变化也不会压住路线 */
.recenter-btn { position: absolute; right: 12px; bottom: calc(100% + 4px); z-index: 3; display: flex; align-items: center; gap: 6px; padding: 9px 14px; border: none; border-radius: 999px; background: rgba(99,102,241,.94); color: #fff; font-size: 12px; font-weight: 700; cursor: pointer; box-shadow: 0 6px 18px rgba(12,26,45,.3); backdrop-filter: blur(8px); transition: transform .15s; animation: slide-up .25s ease; }
.recenter-btn:active { transform: scale(.95); }
.rc-ico { font-size: 14px; line-height: 1; }
.nav-topbar { position: absolute; top: 0; left: 0; right: 0; z-index: 10; display: flex; align-items: center; gap: 10px; padding: 12px 14px; padding-top: max(12px, env(safe-area-inset-top)); background: linear-gradient(180deg, rgba(255,255,255,.95) 60%, rgba(255,255,255,0) 100%); }
.nav-back { width: 36px; height: 36px; border-radius: 50%; background: rgba(0,0,0,.12); border: none; color: #333; font-size: 16px; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all .2s; backdrop-filter: blur(10px); }
.nav-back:active { background: rgba(50,70,120,.08); }
.nav-route-label { flex: 1; display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 700; color: #1a1a2e; text-shadow: 0 1px 2px rgba(255,255,255,.8); }
.nav-from, .nav-to { background: rgba(255,255,255,.8); padding: 4px 10px; border-radius: 8px; backdrop-filter: blur(10px); }
.nav-arrow { color: #999; }
.nav-status { display: flex; align-items: center; gap: 4px; font-size: 12px; font-weight: 600; padding: 4px 12px; border-radius: 20px; backdrop-filter: blur(10px); }
.nav-status.ok { background: rgba(232,245,233,.85); color: #16a34a; }
.nav-status.warn { background: rgba(255,248,225,.85); color: #c2410c; }
.nav-status.rerouted { background: rgba(252,228,236,.85); color: #c62828; }
/* 原地等待（方案 C）：与「风险预警」拉开色调，表示这是人为停驶态而不是路况差 */
.nav-status.hold { background: rgba(226,232,240,.9); color: #475569; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; }
.nav-status.ok .status-dot { background: #4caf50; }
.nav-status.warn .status-dot { background: #ea7a2e; }
.nav-status.rerouted .status-dot { background: #f44336; }
.nav-status.hold .status-dot { background: #64748b; }
.nav-legend { position: absolute; top: 90px; right: 12px; z-index: 10; display: flex; flex-direction: column; gap: 4px; background: rgba(255,255,255,.85); backdrop-filter: blur(10px); padding: 8px 10px; border-radius: 10px; font-size: 10px; color: #666; }
.base-switch-btn { position: absolute; top: 178px; right: 12px; z-index: 10; padding: 8px 12px; border: none; border-radius: 999px; background: rgba(255,255,255,.9); backdrop-filter: blur(10px); box-shadow: 0 2px 10px rgba(0,0,0,.15); font-size: 12px; font-weight: 700; color: #374151; cursor: pointer; transition: transform .15s; }
.base-switch-btn:active { transform: scale(.95); }
.lg { display: inline-block; width: 12px; height: 3px; border-radius: 2px; vertical-align: middle; margin-right: 4px; }
.lg-route { background: #2563eb; }
.lg-risk { background: #e53935; }
.lg-port { background: #f39c12; width: 6px; height: 6px; border-radius: 50%; }
/* 底部浮层栈：叠在离线底图之上（z-index 高于地图与图例），容器本身穿透点击，保证地图仍可拖动缩放 */
.nav-bottom { position: absolute; bottom: 0; left: 0; right: 0; z-index: 12; padding: 12px 12px env(safe-area-inset-bottom, 12px); pointer-events: none; }
.nav-bottom > * { pointer-events: auto; }
/* 预览模式：改成「上下分栏」——地图占上方、面板固定在下方，布局上完全不重叠，
   路线再不会被面板压住；也不再需要 fitBounds 躲浮层那一套留白逻辑 */
.nav-screen.split .nav-bottom { position: relative; bottom: auto; left: auto; right: auto; flex: 0 0 auto; pointer-events: auto; background: linear-gradient(180deg, rgba(99,102,241,.07) 0%, #f8fafc 100%); border-top: 1px solid rgba(12,26,45,.08); box-shadow: 0 -10px 26px rgba(12,26,45,.07); }
.nav-push { margin-bottom: 8px; padding: 10px 14px; border-radius: 12px; font-size: 12px; cursor: pointer; display: flex; align-items: center; gap: 8px; backdrop-filter: blur(10px); animation: slide-up .3s ease; }
.nav-push.info { background: rgba(227,242,253,.9); color: #1565c0; }
.nav-push.success { background: rgba(232,245,233,.9); color: #16a34a; }
.nav-push.warn { background: rgba(255,248,225,.9); color: #c2410c; }
.nav-push.danger { background: rgba(252,228,236,.9); color: #c62828; }
/* ok 此前缺样式（任务确认等 3 处用到），补上，与 success 同色系 */
.nav-push.ok { background: rgba(232,245,233,.9); color: #16a34a; }
/* 语音链路诊断条：仅 ?voicedebug=1 时显示，不参与交互、不挡演示 */
.voice-debug {
  position: absolute; top: 62px; left: 10px; right: 10px; z-index: 40;
  background: rgba(17,24,39,.88); color: #7dd3fc;
  font-family: ui-monospace, Consolas, monospace; font-size: 11px; line-height: 1.5;
  padding: 6px 9px; border-radius: 8px; pointer-events: none; word-break: break-all;
}
.push-icon { font-size: 16px; }
@keyframes slide-up { from { transform: translateY(20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
.nav-info-card { background: rgba(255,255,255,.92); backdrop-filter: blur(16px); border-radius: 16px; padding: 14px 16px; box-shadow: 0 4px 20px rgba(0,0,0,.1); }
.nav-info-main { display: flex; align-items: center; gap: 16px; }
.nav-eta { display: flex; align-items: baseline; gap: 2px; }
.eta-num { font-size: 40px; font-weight: 800; color: #1a1a2e; line-height: 1; }
.eta-unit { font-size: 15px; color: #666; font-weight: 600; }
.nav-dist { display: flex; align-items: baseline; gap: 2px; }
.dist-num { font-size: 27px; font-weight: 700; color: #555; }
.dist-unit { font-size: 14px; color: #999; }
.nav-risk-badge { margin-left: auto; font-size: 12px; font-weight: 700; padding: 4px 12px; border-radius: 20px; }
.nav-risk-badge.ok { background: #e8f5e9; color: #16a34a; }
.nav-risk-badge.warn { background: #fff3e0; color: #c2410c; }
.nav-risk-badge.rerouted { background: #fce4ec; color: #c62828; }
.nav-risk-badge.hold { background: #eef2f7; color: #475569; }
.nav-info-sub { display: flex; align-items: center; gap: 8px; margin-top: 8px; font-size: 12px; color: #888; }
.delay-tag { background: #fce4ec; color: #c62828; padding: 2px 8px; border-radius: 8px; font-weight: 600; font-size: 11px; }
/* 水运方案标签：用棱彩主色，与"陆水联运·平陆运河"呼应 */
.canal-tag { background: linear-gradient(135deg, rgba(99,102,241,.16), rgba(56,189,248,.16) 45%, rgba(168,85,247,.16)); color: var(--prism-1); padding: 2px 8px; border-radius: 8px; font-weight: 700; font-size: 11px; }

/* ===== ETA 卡片：浮在地图之上的导航信息卡（配色 + 流光 + 呼吸特效）===== */
.eta-card { position: relative; overflow: hidden; border: 1px solid rgba(99,102,241,.18); background: linear-gradient(135deg, rgba(255,255,255,.96) 0%, rgba(236,247,255,.94) 55%, rgba(224,242,248,.95) 100%); box-shadow: 0 14px 34px rgba(12,26,45,.22), inset 0 1px 0 rgba(255,255,255,.9); animation: eta-in .45s cubic-bezier(.2,.9,.3,1.2) both; }
@keyframes eta-in { from { opacity: 0; transform: translateY(16px) scale(.98); } to { opacity: 1; transform: translateY(0) scale(1); } }
.eta-sheen { position: absolute; top: 0; left: 0; right: 0; height: 3px; background: linear-gradient(90deg, transparent, var(--prism-2), var(--prism-1), var(--prism-4), var(--prism-5), transparent); background-size: 200% 100%; animation: eta-flow 2.8s linear infinite; }
@keyframes eta-flow { from { background-position: 200% 0; } to { background-position: -200% 0; } }
.eta-card .eta-num { background: linear-gradient(135deg, var(--prism-1), var(--prism-3), var(--prism-4)); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
.eta-card .dist-num { color: #37474f; }
.eta-card .nav-risk-badge { display: inline-flex; align-items: center; box-shadow: 0 2px 10px rgba(0,0,0,.06); }
.badge-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: currentColor; margin-right: 6px; animation: dot-breathe 1.8s ease-in-out infinite; }
@keyframes dot-breathe { 0%, 100% { opacity: 1; } 50% { opacity: .3; } }
.eta-arrival { display: inline-flex; align-items: center; color: var(--prism-1); font-weight: 800; font-size: 16px; }
.eta-arrival .pulse-dot { width: 9px; height: 9px; }
.pulse-dot { position: relative; display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #16a34a; margin-right: 7px; }
.pulse-dot::after { content: ''; position: absolute; inset: 0; border-radius: 50%; background: #16a34a; animation: pulse-ring 1.8s ease-out infinite; }
@keyframes pulse-ring { 0% { transform: scale(1); opacity: .55; } 100% { transform: scale(2.6); opacity: 0; } }
.eta-live { font-size: 11px; font-weight: 700; color: #00838f; background: rgba(0,172,193,.14); padding: 2px 8px; border-radius: 8px; }
.eta-bar { position: relative; height: 4px; margin-top: 10px; border-radius: 999px; background: rgba(99,102,241,.12); overflow: hidden; }
.eta-bar-fill { position: absolute; top: 0; height: 100%; border-radius: 999px; background: linear-gradient(90deg, var(--prism-2), var(--prism-1), var(--prism-4)); animation: eta-progress 3.6s ease-in-out infinite; }
@keyframes eta-progress {
  0% { left: -30%; width: 28%; opacity: .85; }
  55% { left: 38%; width: 44%; opacity: 1; }
  100% { left: 100%; width: 28%; opacity: .85; }
}
@media (prefers-reduced-motion: reduce) {
  .eta-sheen, .eta-bar-fill, .badge-dot, .pulse-dot::after, .eta-card, .start-btn { animation: none !important; }
}
.nav-actions { display: flex; gap: 8px; margin-top: 10px; justify-content: flex-end; flex-wrap: wrap; }
/* 停驶等待提示条：导航底部、操作按钮上方，一眼看到「车为什么不动」以及怎么恢复 */
.hold-card { display: flex; align-items: center; gap: 10px; margin-top: 10px; padding: 10px 12px;
  border-radius: 14px; background: rgba(238,242,247,.96); border: 1px solid rgba(100,116,139,.35);
  box-shadow: 0 2px 10px rgba(0,0,0,.06); }
.hold-main { flex: 1; min-width: 0; }
.hold-title { font-size: 13px; font-weight: 700; color: #334155; }
.hold-sub { font-size: 11px; color: #64748f; line-height: 1.4; margin-top: 2px; }
.hold-resume { flex: none; padding: 8px 14px; border: none; border-radius: 20px; font-size: 12px;
  font-weight: 700; color: #fff; background: linear-gradient(135deg, #16a34a, #22c55e); cursor: pointer;
  box-shadow: 0 2px 8px rgba(22,163,74,.3); }
/* 路线预览（浮在地图底图之上的选路面板，路线留白由 fitRoute 计算，避免被面板遮挡） */
.preview-card { position: relative; overflow: hidden; display: flex; flex-direction: column; padding: 14px; background: linear-gradient(150deg, rgba(255,255,255,.96) 0%, rgba(237,247,255,.94) 58%, rgba(226,242,250,.95) 100%); backdrop-filter: blur(18px); border: 1px solid rgba(99,102,241,.16); box-shadow: 0 16px 38px rgba(12,26,45,.24), inset 0 1px 0 rgba(255,255,255,.9); animation: eta-in .45s cubic-bezier(.2,.9,.3,1.2) both; }
.preview-head { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 10px; flex-shrink: 0; }
.preview-head-left { flex: 1; min-width: 0; }
.preview-title { display: flex; align-items: center; font-size: 15px; font-weight: 800; color: #1a1a2e; }
.preview-sub { font-size: 11px; color: #888; margin-top: 3px; }
.preview-toggle { flex-shrink: 0; padding: 5px 10px; border: none; border-radius: 12px; background: rgba(99,102,241,.1); color: var(--prism-1); font-size: 11px; font-weight: 700; white-space: nowrap; cursor: pointer; transition: all .2s; }
.preview-toggle:hover { background: rgba(99,102,241,.18); }
.preview-toggle:active { transform: scale(.95); }
.preview-list { display: flex; flex-direction: column; gap: 8px; max-height: 16vh; overflow-y: auto; -webkit-overflow-scrolling: touch; padding: 2px; }
/* 上下分栏：面板在地图之外，可给列表更多空间。卡片整体高度由视口比例约束，
   超出的部分交给列表内部滚动，绝不压缩卡片本身（见 .pv-item 的 flex-shrink） */
.nav-screen.split .preview-card { max-height: min(46vh, 420px); }
.nav-screen.split .preview-list { flex: 1 1 auto; min-height: 0; max-height: none; }
/* 路线卡片：左侧渐变高亮条 + 错峰入场 + 选中发光呼吸 */
.pv-item { position: relative; display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px 10px 15px; border-radius: 13px; background: linear-gradient(135deg, #ffffff 0%, #f8fbff 100%); border: 2px solid #eef2f6; cursor: pointer; overflow: hidden; flex-shrink: 0; transition: transform .2s, box-shadow .25s, border-color .2s, background .25s; animation: pv-in .34s cubic-bezier(.2,.9,.3,1.1) both; }
@keyframes pv-in { from { opacity: 0; transform: translateX(-12px); } to { opacity: 1; transform: translateX(0); } }
.pv-item::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 5px; background: linear-gradient(180deg, #90a4ae, #3a4657); opacity: .5; transition: opacity .25s, background .25s; }
.pv-item:hover { transform: translateX(2px); box-shadow: 0 6px 18px rgba(12,26,45,.12); border-color: #d6e6f7; }
.pv-item:active { transform: scale(.99); }
.pv-item.sel { border-color: var(--prism-1); background: linear-gradient(135deg, #f2f8ff 0%, #e3f1ff 100%); box-shadow: 0 0 0 3px rgba(99,102,241,.1), 0 8px 22px rgba(99,102,241,.18); animation: pv-in .34s cubic-bezier(.2,.9,.3,1.1) both, pv-breathe 2.6s ease-in-out infinite; }
.pv-item.sel::before { background: linear-gradient(180deg, var(--prism-2), var(--prism-1), var(--prism-4)); opacity: 1; }
@keyframes pv-breathe { 0%, 100% { box-shadow: 0 0 0 3px rgba(99,102,241,.1), 0 8px 22px rgba(99,102,241,.18); } 50% { box-shadow: 0 0 0 5px rgba(99,102,241,.16), 0 10px 26px rgba(99,102,241,.28); } }
.pv-item.safest::before { background: linear-gradient(180deg, #26a69a, #00897b); opacity: 1; }
/* 高风险候选路线：左侧竖条加宽，红色由「径向晕染 + 斜向渐变」从左侧大面积铺开、
   向右渐隐——填充明显但右侧与上下边缘仍留白，不会整卡涂满 */
.pv-item.risky {
  border-color: #ffc9c9;
  background:
    radial-gradient(ellipse 78% 132% at 0% 30%, rgba(229,57,53,.26), rgba(229,57,53,.08) 48%, transparent 74%),
    linear-gradient(112deg, #ffdada 0%, #ffe8e8 36%, #fff5f5 68%, #fffdfd 100%);
  box-shadow: 0 3px 12px rgba(229,57,53,.16), inset 0 0 0 1px rgba(229,57,53,.06);
}
.pv-item.risky::before { width: 8px; background: linear-gradient(180deg, #ff5252, #c62828); opacity: 1; }
.pv-item.risky:hover { border-color: #ef9a9a; box-shadow: 0 6px 20px rgba(229,57,53,.24), inset 0 0 0 1px rgba(229,57,53,.08); }
/* 高风险 + 已选：保留红色填充，同时用蓝描边/蓝呼吸光环表示当前选中 */
.pv-item.risky.sel {
  border-color: var(--prism-1);
  background:
    radial-gradient(ellipse 78% 132% at 0% 30%, rgba(229,57,53,.22), rgba(229,57,53,.07) 50%, transparent 76%),
    linear-gradient(112deg, #ffe0e0 0%, #ffeaea 38%, #f4f9ff 72%, #eff7ff 100%);
}
.pv-info { flex: 1; min-width: 0; }
.pv-title { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 13px; font-weight: 700; color: #1a1a2e; }
.pv-flag { font-size: 10px; font-weight: 700; padding: 1px 7px; border-radius: 8px; background: rgba(99,102,241,.12); color: var(--prism-1); }
.safest-flag { background: linear-gradient(90deg, #26a69a, #009688); color: #fff; box-shadow: 0 2px 8px rgba(0,137,123,.35); animation: badge-breathe 2.2s ease-in-out infinite; }
@keyframes badge-breathe { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.06); } }
.pv-via { font-size: 11px; color: #999; margin-top: 2px; }
.pv-meta { display: flex; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.pv-right { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; flex-shrink: 0; }
.prob-pill.glow { box-shadow: 0 0 0 3px rgba(0,0,0,.03); }
.prob-pill.high.glow { box-shadow: 0 0 0 3px rgba(198,40,40,.12), 0 0 14px rgba(229,57,53,.28); animation: high-breathe 1.6s ease-in-out infinite; }
.prob-pill.low.glow { box-shadow: 0 0 0 3px rgba(46,125,50,.1); }
@keyframes high-breathe { 0%, 100% { box-shadow: 0 0 0 3px rgba(198,40,40,.12), 0 0 14px rgba(229,57,53,.25); } 50% { box-shadow: 0 0 0 5px rgba(198,40,40,.18), 0 0 22px rgba(229,57,53,.45); } }
.pv-detail-btn { display: inline-flex; align-items: center; gap: 2px; padding: 4px 10px; border: none; border-radius: 10px; background: linear-gradient(135deg, rgba(0,172,193,.14), rgba(99,102,241,.16)); color: #0d6e8a; font-size: 11px; font-weight: 700; white-space: nowrap; cursor: pointer; transition: all .2s; }
.pv-detail-btn:hover { background: linear-gradient(135deg, rgba(0,172,193,.26), rgba(99,102,241,.28)); color: #05505f; }
.pv-detail-btn:active { transform: scale(.95); }
.pv-detail-btn:disabled { opacity: .45; cursor: not-allowed; }
.preview-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.preview-start { position: relative; overflow: hidden; flex: 1; width: auto; margin-top: 0; padding: 15px; }
.preview-start::after { content: ''; position: absolute; top: 0; bottom: 0; width: 46%; background: linear-gradient(100deg, transparent, rgba(255,255,255,.42), transparent); animation: start-shine 2.6s linear infinite; }
@keyframes start-shine { from { left: -60%; } to { left: 120%; } }
@media (prefers-reduced-motion: reduce) {
  .pv-item, .pv-item.sel, .safest-flag, .prob-pill.high.glow, .preview-card, .preview-start::after { animation: none !important; }
}
/* 触屏设备（手机/平板）：关闭流光/呼吸/扫光这类纯装饰 CSS 动画（每帧都在耗合成线程），
   风险闪烁的红色脉冲是 JS 驱动的安全语义，不在本块范围内 */
@media (pointer: coarse) {
  .eta-sheen { display: none; }
  .eta-bar-fill { display: none; }
  .eta-card, .preview-card, .pv-item, .pv-item.sel, .safest-flag,
  .prob-pill.high.glow, .preview-start::after, .start-btn,
  .badge-dot, .pulse-dot::after {
    animation: none !important;
  }
}
.nav-btn { width: 44px; height: 44px; border-radius: 50%; background: rgba(255,255,255,.9); border: none; box-shadow: 0 2px 10px rgba(0,0,0,.08); display: flex; align-items: center; justify-content: center; font-size: 18px; cursor: pointer; transition: all .2s; backdrop-filter: blur(10px); }
.nav-btn:active { transform: scale(.92); background: #f0f2f5; }
.nav-btn.exit-btn { width: auto; padding: 0 16px; border-radius: 22px; font-size: 13px; font-weight: 700; color: #c62828; background: rgba(255,255,255,.95); }
.nav-btn.exit-btn:active { background: #fce4ec; }
.nav-exit-btn { width: 100%; padding: 11px; border-radius: 10px; border: 1px solid #f0c8cc; background: #fff5f5; color: #c62828; font-size: 13px; font-weight: 700; cursor: pointer; }
.nav-exit-btn:active { background: #fce4ec; }
.exit-panel { max-height: 40vh; }
.exit-tip { font-size: 13px; color: #666; line-height: 1.6; }
.exit-btns { display: flex; gap: 10px; margin-top: 16px; }
.exit-choice { flex: 1; padding: 12px; border-radius: 10px; border: 1px solid #e0e0e0; background: #f5f5f5; color: #555; font-size: 13px; font-weight: 700; cursor: pointer; }
.exit-choice.cancel:active { background: #eaeaea; }
.exit-choice.danger { background: #c62828; border-color: #c62828; color: #fff; }
.exit-choice.danger:active { background: #a01f1f; }
.nav-menu { background: rgba(255,255,255,.95); backdrop-filter: blur(16px); border-radius: 16px; padding: 14px; margin-top: 8px; max-height: 35vh; overflow: auto; box-shadow: 0 4px 20px rgba(0,0,0,.1); animation: slide-up .3s ease; }
.nav-menu-section { margin-bottom: 14px; }
.nav-menu-section:last-child { margin-bottom: 0; }
.nav-menu-title { font-size: 12px; font-weight: 700; color: #999; text-transform: uppercase; letter-spacing: .5px; margin-bottom: 6px; }
.weather-strip { display: flex; gap: 8px; overflow-x: auto; }
.weather-dot { padding: 8px 10px; border-radius: 10px; background: #f5f9ff; text-align: center; min-width: 60px; flex-shrink: 0; }
.wd-name { font-size: 10px; font-weight: 600; color: var(--prism-1); display: block; }
.wd-temp { font-size: 16px; font-weight: 700; color: #1a1a2e; }
.wd-rain { font-size: 10px; }
.prob-pill { font-size: 11px; padding: 2px 8px; border-radius: 10px; font-weight: 700; }
.prob-pill.high { background: #fce4ec; color: #c62828; }
.prob-pill.low { background: #e8f5e9; color: #16a34a; }
.prob-pill.unknown { background: #f0f2f5; color: #999; }
.kb-overlay { position: fixed; inset: 0; z-index: 200; background: rgba(0,0,0,.4); display: flex; align-items: flex-end; justify-content: center; }
.kb-panel { background: #fff; border-radius: 16px 16px 0 0; padding: 20px; width: 100%; max-width: 420px; max-height: 60vh; overflow-y: auto; animation: kb-up .32s cubic-bezier(.2,.9,.3,1.1) both; }
@keyframes kb-up { from { transform: translateY(28px); opacity: .6; } to { transform: translateY(0); opacity: 1; } }
/* 调度任务变更：导航中弹出的确认接收抽屉（贴底，可收起露出上方路线预览） */
.dispatch-overlay { position: fixed; inset: 0; z-index: 210; display: flex; align-items: flex-end; justify-content: center; pointer-events: none; background: rgba(6,18,34,.22); transition: background .3s; }
.dispatch-overlay.collapsed { background: rgba(6,18,34,.04); }
.dispatch-sheet { pointer-events: auto; background: #fff; border-radius: 16px 16px 0 0; padding: 14px 16px calc(14px + env(safe-area-inset-bottom, 0px)); width: 100%; max-width: 420px; max-height: 74vh; overflow-y: auto; box-shadow: 0 -12px 34px rgba(6,18,34,.28); animation: kb-up .32s cubic-bezier(.2,.9,.3,1.1) both; }
.dc-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.dc-title { font-size: 15px; font-weight: 800; color: #b91c1c; }
.dc-toggle { flex: 0 0 auto; border: 1px solid #e2e8f0; background: #f8fafc; color: #334155; border-radius: 999px; padding: 5px 12px; font-size: 12px; font-weight: 700; cursor: pointer; }
.dc-plan { display: flex; gap: 8px; margin-bottom: 8px; }
.dc-hint { margin-top: 8px; font-size: 11px; color: #94a3b8; text-align: center; line-height: 1.5; }
.hazard-detail-panel .kb-head { align-items: center; padding-bottom: 10px; border-bottom: 1px solid rgba(99,102,241,.14); }
.hazard-detail-panel .kb-head > span:first-child { background: linear-gradient(90deg, var(--prism-1), var(--prism-3), var(--prism-4)); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
.kb-head { font-size: 14px; font-weight: 700; margin-bottom: 12px; display: flex; justify-content: space-between; }
.kb-close { font-size: 16px; cursor: pointer; color: #999; }
@media (prefers-reduced-motion: reduce) { .kb-panel, .hd-item, .hd-bar-in { animation: none !important; } }
/* ===== 订单详情预览弹层（接到派单后查看货物/起终点/AI 分析 → 接单/拒单）===== */
.order-panel { max-height: 78vh; }
.order-state { font-size: 12px; font-weight: 700; padding: 5px 10px; border-radius: 8px; margin-bottom: 12px; display: inline-block; }
.order-state.wait { background: #fff3e0; color: #c2410c; }
.order-state.ok { background: #e4f6ea; color: #16a34a; }
.order-state.bad { background: #fdecec; color: #dc2626; }
.order-cargo { display: flex; align-items: center; gap: 12px; padding: 12px; border-radius: 12px; background: linear-gradient(135deg, #f2f8ff, #eaf1ff); border: 1px solid rgba(99,102,241,.16); margin-bottom: 12px; }
.oc-ico { font-size: 26px; }
.oc-name { font-size: 16px; font-weight: 800; color: #1a1a2e; }
.oc-sub { font-size: 12px; color: #667085; margin-top: 3px; }
.order-od { margin-bottom: 10px; }
.ood-row { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 5px 0; }
.ood-dot { width: 9px; height: 9px; border-radius: 50%; flex-shrink: 0; }
.ood-dot.from { background: #22a35a; }
.ood-dot.to { background: #e53935; }
.ood-label { font-size: 11px; color: #98a2b3; width: 32px; flex-shrink: 0; }
.ood-val { font-weight: 700; color: #1a1a2e; }
.order-kv { display: flex; justify-content: space-between; align-items: center; font-size: 13px; padding: 7px 0; border-top: 1px solid #f0f2f5; }
.order-kv span { color: #667085; }
.order-kv b { color: #1a1a2e; font-weight: 700; text-align: right; }
.order-kv b.high { color: #dc2626; }
.order-kv b.low { color: #16a34a; }
.order-kv b.unknown { color: #98a2b3; }
.order-ai { margin-top: 12px; padding: 10px 12px; border-radius: 10px; background: #f7faff; border: 1px solid rgba(99,102,241,.16); }
.oa-title { font-size: 12px; font-weight: 700; color: var(--prism-1); margin-bottom: 5px; }
.oa-text { font-size: 12px; line-height: 1.7; color: #475569; white-space: pre-wrap; word-break: break-word; }
.order-actions { display: flex; gap: 10px; margin-top: 16px; }
.order-btn { flex: 1; padding: 13px; border-radius: 12px; border: none; font-size: 14px; font-weight: 700; cursor: pointer; transition: transform .12s, filter .15s; }
.order-btn:active:not(:disabled) { transform: scale(.98); }
.order-btn:disabled { opacity: .55; cursor: default; }
.order-btn.accept { background: linear-gradient(135deg, #16a34a, #34d399); color: #fff; box-shadow: 0 4px 12px rgba(46,125,50,.3); }
.order-btn.reject { background: #fff; color: #dc2626; border: 1px solid #f3c7c7; }
.order-done-hint { margin-top: 14px; font-size: 12px; color: #667085; text-align: center; }
.cmp-row { display: flex; justify-content: space-between; margin-top: 6px; font-size: 13px; color: #666; }
.cmp-row.warn { color: #c62828; font-weight: 600; }
.cmp-label { color: #999; }
.advice-box { margin-top: 12px; padding: 10px; border-radius: 10px; background: #e3f2fd; color: var(--prism-1); font-size: 13px; font-weight: 600; text-align: center; }
.hd-total { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; padding: 10px 12px; border-radius: 12px; background: linear-gradient(135deg, rgba(236,247,255,.95), rgba(224,242,248,.9)); border: 1px solid rgba(99,102,241,.14); }
.hd-total-label { font-size: 13px; font-weight: 700; color: var(--prism-1); }
.hd-item { margin-bottom: 10px; padding: 9px 11px; border-radius: 11px; background: #fbfdff; border: 1px solid #eef3f8; animation: hd-in .32s cubic-bezier(.2,.9,.3,1.1) both; }
.hd-item:nth-child(2) { animation-delay: .04s; }
.hd-item:nth-child(3) { animation-delay: .08s; }
.hd-item:nth-child(4) { animation-delay: .12s; }
.hd-item:nth-child(5) { animation-delay: .16s; }
.hd-item:nth-child(6) { animation-delay: .2s; }
@keyframes hd-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
.hd-item-top { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.hd-name { flex: 1; font-weight: 600; color: #263238; }
.hd-pct { font-weight: 800; }
.hd-pct.high { color: #c62828; }
.hd-pct.low { color: #16a34a; }
.hd-bar { position: relative; height: 6px; background: #eef2f6; border-radius: 999px; margin-top: 5px; overflow: hidden; }
.hd-bar-in { height: 100%; border-radius: 999px; transition: width .6s cubic-bezier(.2,.9,.3,1); animation: hd-grow .6s cubic-bezier(.2,.9,.3,1) both; }
@keyframes hd-grow { from { transform: scaleX(0); transform-origin: left; } to { transform: scaleX(1); transform-origin: left; } }
.hd-bar-in.high { background: linear-gradient(90deg, #ff8a65, #e53935); box-shadow: 0 0 10px rgba(229,57,53,.45); }
.hd-bar-in.low { background: linear-gradient(90deg, #80cbc4, #34d399); }
.hd-bar-in.unknown { background: #bdbdbd; }
.hd-reason { font-size: 11px; color: #90a4ae; margin-top: 5px; line-height: 1.5; }
/* ===== 绕行方案面板：灾害触发后的换路选择（高风险灾害用红色标记）===== */
.hazard-panel { position: fixed; bottom: 0; left: 0; right: 0; z-index: 250; background: linear-gradient(180deg, #ffffff 0%, #f6fafd 100%); border-radius: 20px 20px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom, 0px)); box-shadow: 0 -10px 34px rgba(12,26,45,.28), inset 0 1px 0 rgba(255,255,255,.9); max-height: 62vh; overflow: auto; animation: rz-up .32s cubic-bezier(.2,.9,.3,1.1) both; }
@keyframes rz-up { from { transform: translateY(26px); opacity: .5; } to { transform: translateY(0); opacity: 1; } }
/* 顶部高危警示流光条 */
.hazard-panel::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: linear-gradient(90deg, transparent, #e53935, #ea7a2e, #e53935, transparent); background-size: 200% 100%; animation: eta-flow 2.8s linear infinite; }
.hazard-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.hazard-icon { flex-shrink: 0; width: 38px; height: 38px; display: flex; align-items: center; justify-content: center; font-size: 19px; border-radius: 12px; background: linear-gradient(135deg, #ffebee, #ffdfe0); color: #c62828; animation: hazard-pulse 1.8s ease-in-out infinite; }
@keyframes hazard-pulse { 0%, 100% { box-shadow: inset 0 0 0 1px rgba(198,40,40,.18), 0 0 0 0 rgba(229,57,53,.30); } 50% { box-shadow: inset 0 0 0 1px rgba(198,40,40,.24), 0 0 0 9px rgba(229,57,53,0); } }
.hazard-head-text { flex: 1; min-width: 0; }
.hazard-title { font-size: 14px; font-weight: 800; color: #b71c1c; line-height: 1.35; }
.hazard-sub { font-size: 11px; color: #90a4ae; margin-top: 3px; }
.hazard-close { flex-shrink: 0; width: 28px; height: 28px; border: none; border-radius: 50%; background: #f0f2f5; color: #90a4ae; font-size: 13px; cursor: pointer; transition: all .2s; }
.hazard-close:active { transform: scale(.92); background: #e6eef5; }
/* 候选项：左侧竖条绿=安全 / 红=高风险 */
.hazard-opt { position: relative; padding: 12px 12px 12px 16px; border-radius: 14px; margin-bottom: 8px; cursor: pointer; overflow: hidden; background: #fff; border: 2px solid #eef2f6; box-shadow: 0 2px 8px rgba(12,26,45,.04); transition: transform .2s, box-shadow .25s, border-color .2s, background .25s; }
.hazard-opt::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 5px; background: linear-gradient(180deg, #81c784, #34d399); opacity: .9; }
/* 高风险：左侧红条加宽；红色由「径向晕染 + 斜向渐变」从左侧大面积铺开、向右渐隐——
   填充量明显加大，但右侧与上下边缘仍留白，不会把整卡涂满 */
.hazard-opt.risky {
  border-color: #ffc9c9;
  background:
    radial-gradient(ellipse 78% 132% at 0% 32%, rgba(229,57,53,.26), rgba(229,57,53,.08) 48%, transparent 74%),
    linear-gradient(112deg, #ffdada 0%, #ffe8e8 36%, #fff5f5 68%, #fffdfd 100%);
  box-shadow: 0 3px 12px rgba(229,57,53,.16), inset 0 0 0 1px rgba(229,57,53,.06);
}
.hazard-opt.risky::before { width: 8px; background: linear-gradient(180deg, #ff5252, #c62828); }
.hazard-opt:hover { border-color: #d6e6f7; box-shadow: 0 6px 18px rgba(12,26,45,.12); }
.hazard-opt.risky:hover { border-color: #ef9a9a; box-shadow: 0 6px 20px rgba(229,57,53,.24), inset 0 0 0 1px rgba(229,57,53,.08); }
.hazard-opt:active { transform: scale(.99); }
.hazard-opt.sel { border-color: var(--prism-1); background: linear-gradient(135deg, #f2f8ff 0%, #e3f1ff 100%); box-shadow: 0 0 0 3px rgba(99,102,241,.1), 0 6px 18px rgba(99,102,241,.14); }
/* 风险最低的一条：绿色高亮 + 加宽绿条，引导司机优先选择 */
.hazard-opt.best { border-color: #a5d6a7; background: linear-gradient(135deg, #f4fdf6 0%, #e9f8ee 100%); box-shadow: 0 3px 12px rgba(46,125,50,.14), inset 0 0 0 1px rgba(46,125,50,.06); }
.hazard-opt.best::before { width: 8px; background: linear-gradient(180deg, #66bb6a, #16a34a); opacity: 1; }
/* 行内徽标：风险最低 / 高风险不推荐 / 当前路线 */
.rz-badge { flex-shrink: 0; font-size: 10px; font-weight: 800; padding: 2px 7px; border-radius: 8px; white-space: nowrap; }
.rz-badge.best { background: #e8f5e9; color: #16a34a; box-shadow: inset 0 0 0 1px rgba(46,125,50,.2); }
.rz-badge.danger { background: #fce4ec; color: #c62828; box-shadow: inset 0 0 0 1px rgba(198,40,40,.22); }
.rz-badge.now { background: #e3f2fd; color: var(--prism-1); box-shadow: inset 0 0 0 1px rgba(99,102,241,.2); }
/* 全风险警示条：所有候选都不安全时给出停车建议 */
.rz-warn { display: flex; gap: 8px; align-items: flex-start; padding: 10px 12px; margin-bottom: 10px; border-radius: 12px; background: linear-gradient(135deg, #fff4e5 0%, #ffece0 100%); border: 1px solid #ffd8a8; color: #8a4b00; font-size: 11.5px; line-height: 1.55; }
.rz-warn-ico { flex-shrink: 0; font-size: 14px; color: #c2410c; }
.rz-warn b { color: #b71c1c; }
.hazard-opt-top { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.hazard-opt-name { font-size: 13px; font-weight: 700; color: #1a1a2e; flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hazard-opt-via { font-size: 11px; color: #90a4ae; margin-top: 3px; }
.hazard-opt-bottom { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 7px; flex-wrap: wrap; }
.hazard-opt-meta { font-size: 11px; font-weight: 600; color: #607d8b; flex-shrink: 0; }
/* 逐灾种标签：达高风险阈值标红，其余中性灰 */
.rz-tags { display: flex; flex-wrap: wrap; gap: 5px; }
.rz-tag { font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 9px; background: #f0f2f5; color: #7c8ca6; white-space: nowrap; }
.rz-tag.hot { background: #fce4ec; color: #c62828; box-shadow: inset 0 0 0 1px rgba(198,40,40,.2); }
.hazard-actions { display: flex; gap: 10px; margin-top: 10px; }
.hazard-btn { flex: 1; padding: 12px; border-radius: 12px; border: 1px solid #e0e0e0; background: #f5f5f5; color: #555; font-size: 13px; font-weight: 700; cursor: pointer; transition: all .2s; }
.hazard-btn:active { transform: scale(.98); }
.hazard-btn.ghost { background: #fff; border-color: #e3e8ee; color: #607d8b; }
.hazard-btn.ghost:active { background: #f5f7f9; }
/* ===== 全域受阻面板：区域灾害同时封住全部出境走廊与运河，给「返回 / 停靠 / 暂缓」三态动作 =====
   与绕行面板同一形态但层级更高：绕行是「有得选」，全域受阻是「没得选」，
   语义不能混，所以主调改成深红+紫（风暴）而不是绕行的橙色 */
.corridor-panel { position: fixed; bottom: 0; left: 0; right: 0; z-index: 260; background: linear-gradient(180deg, #fff5f5 0%, #f8faff 100%); border-radius: 20px 20px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom, 0px)); box-shadow: 0 -12px 36px rgba(12,26,45,.32), inset 0 1px 0 rgba(255,255,255,.9); max-height: 66vh; overflow: auto; animation: rz-up .32s cubic-bezier(.2,.9,.3,1.1) both; }
.corridor-panel::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: linear-gradient(90deg, transparent, #be123c, #7c3aed, #be123c, transparent); background-size: 200% 100%; animation: eta-flow 2.4s linear infinite; }
.corridor-panel .hazard-title { color: #9f1239; }
/* ===== 接单模式「等待调度中心」面板：不自动绕路时的唯一常驻状态 =====
   形态沿用同一系列底部卡，但语义与绕行面板相反：绕行是「司机自己选一条」，
   这里是「换线权在调度、司机先别动」，所以主调用橙色流光（比全域受阻的深红+紫轻一档，
   也明显不是可点方案列表）；层级 255 卡在绕行面板(250)与全域受阻面板(260)之间 */
.wait-panel { position: fixed; bottom: 0; left: 0; right: 0; z-index: 255; background: linear-gradient(180deg, #fffaf3 0%, #fdf4e9 100%); border-radius: 20px 20px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom, 0px)); box-shadow: 0 -10px 34px rgba(12,26,45,.28), inset 0 1px 0 rgba(255,255,255,.9); animation: rz-up .32s cubic-bezier(.2,.9,.3,1.1) both; }
.wait-panel::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: linear-gradient(90deg, transparent, #ea7a2e, #f5b76a, #ea7a2e, transparent); background-size: 200% 100%; animation: eta-flow 2.2s linear infinite; }
.wait-panel .hazard-title { color: #b45309; }
.wait-panel .hazard-icon { background: linear-gradient(135deg, #fff2e0, #ffe3c7); color: #c2410c; }
.wp-text { font-size: 13px; line-height: 1.7; color: #263238; }
.cp-text { font-size: 13px; line-height: 1.7; color: #263238; }
.cp-blocked { margin-top: 8px; font-size: 12px; line-height: 1.6; color: #9f1239; background: rgba(225,29,72,.08); border: 1px solid rgba(225,29,72,.28); border-radius: 10px; padding: 7px 10px; }
.cp-basis { margin-top: 8px; font-size: 11px; line-height: 1.55; color: #90a4ae; }
.hazard-btn.danger { border: none; background: linear-gradient(135deg, #e11d48, #be123c); color: #fff; box-shadow: 0 4px 12px rgba(190,18,60,.3); }
.hazard-btn.ok { border: none; background: linear-gradient(135deg, #16a34a, #34d399); color: #fff; box-shadow: 0 4px 12px rgba(46,125,50,.28); }
/* 预览候选卡片上的「不建议通行」旗标：与卡片自带的「AI 最安全」同一位置，互不遮挡 */
.pv-flag.blocked-flag { background: rgba(225,29,72,.12); color: #be123c; border: 1px solid rgba(225,29,72,.4); }
.spinner { display: inline-block; width: 16px; height: 16px; border: 2px solid rgba(255,255,255,.3); border-top-color: #fff; border-radius: 50%; animation: spin .6s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.fade-enter-active, .fade-leave-active { transition: opacity .25s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
.nav-mode ::-webkit-scrollbar { width: 0; }
/* ===== 任务变更（调度大屏对齐） ===== */
.home-nav .has-task { position: relative; color: #c2410c; font-weight: 700; }
.task-dot { position: absolute; top: 2px; right: 6px; width: 8px; height: 8px; border-radius: 50%; background: #e53935; animation: taskBlink 1s infinite alternate; }
@keyframes taskBlink { from { opacity: 1; transform: scale(1); } to { opacity: 0.4; transform: scale(1.3); } }
.task-card { border-left: 4px solid #c2410c; }
.task-card.confirmed { border-left-color: #16a34a; }
.task-state { font-size: 12px; font-weight: 700; }
.task-state.ok { color: #16a34a; }
.task-state.pending { color: #c2410c; }
.task-meta { display: flex; gap: 8px; margin-bottom: 8px; }
.task-channel { font-size: 11px; background: #eceff1; color: #546e7a; padding: 2px 8px; border-radius: 8px; }
.task-plan { font-size: 11px; background: #e3f2fd; color: var(--prism-1); padding: 2px 8px; border-radius: 8px; font-weight: 700; }
.task-lang { display: flex; gap: 6px; margin-bottom: 8px; }
.lang-btn { font-size: 12px; padding: 4px 10px; border-radius: 12px; border: 1px solid #3a4657; background: #fff; color: #7c8ca6; cursor: pointer; }
.lang-btn.on { border-color: var(--prism-1); color: var(--prism-1); background: #e3f2fd; font-weight: 600; }
.speak-btn { margin-left: auto; font-size: 12px; padding: 4px 10px; border-radius: 12px; border: 1px solid #3a4657; background: #fff; color: #546e7a; cursor: pointer; }
.task-msg { font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; margin: 0 0 10px; max-height: 240px; overflow-y: auto; color: #263238; font-family: inherit; background: #fafafa; padding: 10px; border-radius: 10px; }
.rights-box { background: linear-gradient(135deg, #e8f5e9 0%, #f1f8e9 100%); border: 1px solid #c8e6c9; border-radius: 10px; padding: 10px 12px; margin-bottom: 10px; }
.rights-title { font-size: 13px; font-weight: 700; color: #16a34a; margin-bottom: 6px; }
.rights-item { font-size: 12px; color: #33691e; line-height: 1.8; }
.confirm-task-btn { width: 100%; padding: 13px; border: none; border-radius: 12px; background: linear-gradient(135deg, #16a34a, #34d399); color: #fff; font-size: 15px; font-weight: 700; cursor: pointer; box-shadow: 0 4px 12px rgba(46,125,50,.3); }
.confirm-task-btn:disabled { opacity: 0.6; }
.confirm-task-btn.done { background: #eceff1; color: #16a34a; box-shadow: none; }
.task-hint { font-size: 11px; color: #90a4ae; margin-top: 8px; text-align: center; line-height: 1.5; }
.plan-item { border: 1px solid #eceff1; border-radius: 10px; padding: 8px 10px; margin-bottom: 6px; }
.plan-item.rec { border-color: #34d399; background: #f1f8e9; }
.plan-head { display: flex; align-items: center; gap: 8px; }
.plan-badge { width: 20px; height: 20px; border-radius: 6px; background: #e3f2fd; color: var(--prism-1); font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.plan-name { font-size: 13px; font-weight: 700; color: #263238; }
.plan-meta { display: flex; gap: 10px; font-size: 12px; margin-top: 4px; color: #546e7a; }
.plan-meta .warn { color: #c2410c; }
.plan-meta .ok { color: #16a34a; }
.plan-risk { color: #c62828; }
.plan-rec { font-size: 12px; color: #6a1b9a; background: #f3e5f5; border-radius: 8px; padding: 8px 10px; line-height: 1.6; margin-top: 6px; }
.touch-bar { height: 8px; border-radius: 4px; background: #eceff1; overflow: hidden; }
.touch-fill { height: 100%; background: linear-gradient(90deg, #34d399, #66bb6a); border-radius: 4px; transition: width 0.5s; }
.touch-text { font-size: 11px; color: #7c8ca6; margin-top: 6px; }
/* 全屏按钮：与地图控件同套白底圆角风格，图标用细线 SVG（展开/收起四角） */
.fs-btn svg, .layout-btn svg { width: 20px; height: 20px; fill: none; stroke: #3d4450; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.nav-fs-btn { margin-left: auto; flex: 0 0 auto; width: 34px; height: 34px; border: none; border-radius: 10px; background: rgba(255,255,255,.92); box-shadow: 0 1px 6px rgba(0,0,0,.12); display: flex; align-items: center; justify-content: center; cursor: pointer; }
.nav-fs-btn svg { width: 18px; height: 18px; fill: none; stroke: #3d4450; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
/* 顶栏里布局键 + 全屏键并排：只有第一个吃 margin-left:auto，第二个紧跟其后 */
.nav-fs-btn ~ .nav-fs-btn { margin-left: 8px; }

/* ============================================================
   桌面端（≥900px）：默认 PC 版铺满整窗（配合全屏按钮做演示大屏），
   悬浮层限宽对齐，避免手机布局被宽屏拉变形；
   切到「移动版」（.phone-mini，原 420px 手机窄列）时以下整窗规则
   全部不生效，各悬浮层自动落回基础手机样式
   ============================================================ */
@media (min-width: 900px) {
  .phone:not(.phone-mini) { max-width: 100%; box-shadow: none; }
  /* 搜索框 / 去X 卡：左对齐限宽；宫格 / Tab 栏 / 抽屉：居中限宽 */
  .phone:not(.phone-mini) .hm-search { left: 16px; right: auto; width: min(440px, calc(100vw - 32px)); }
  .phone:not(.phone-mini) .hm-gocard { left: 16px; right: auto; width: min(440px, calc(100vw - 32px)); }
  .phone:not(.phone-mini) .hm-grid { left: 16px; right: auto; width: min(680px, calc(100vw - 32px)); }
  .phone:not(.phone-mini) .hm-tabbar { left: 50%; right: auto; transform: translateX(-50%); width: min(680px, calc(100vw - 32px)); }
  .phone:not(.phone-mini) .hm-sheet { left: 50%; right: auto; transform: translateX(-50%); width: min(820px, 100vw); }
  .phone:not(.phone-mini) .hm-ctrl { right: 16px; top: 16px; }
  /* 导航底部面板：卡片居中限宽，宽屏下不拉伸满屏 */
  .phone:not(.phone-mini) .nav-bottom > * { max-width: 860px; margin-left: auto; margin-right: auto; }
  .phone:not(.phone-mini) .nav-topbar { padding-left: 16px; padding-right: 16px; }
  /* 移动版窄列两侧留出让位阴影，整窗底色与手机列区分 */
  .phone.phone-mini { box-shadow: 0 0 40px rgba(0,0,0,.08); }

  /* ===== 移动版（phone-mini）导航/预览态：整屏也要缩回手机尺寸 =====
     首页是 420px 居中窄列，但进入导航后有三处会突破这个窄列，与首页观感不一致：
       1) .phone.nav-mode { max-width:100% }（不分 mini/PC）把整窗撑满；
       2) .nav-screen { position:fixed; inset:0 } 相对视口铺满，脱离手机列；
       3) .hazard-panel（绕行详情）{ fixed; left:0; right:0 } 通栏横跨整窗。
     这里逐项收回 420px 手机列内：nav-mode 保持窄列、nav-screen 改 absolute 落入列中、
     hazard-panel 用 max-width + margin auto 居中（不能用 transform 居中，会与 rz-up 动画冲突）。 */
  .phone.phone-mini.nav-mode { max-width: 420px; }
  .phone.phone-mini .nav-screen { position: absolute; }
  .phone.phone-mini .hazard-panel { max-width: 420px; margin-left: auto; margin-right: auto; }
  /* 全域受阻面板与绕行面板同样收回手机列内（同为 fixed 通栏） */
  .phone.phone-mini .corridor-panel { max-width: 420px; margin-left: auto; margin-right: auto; }
  /* 等待调度中心卡也是 fixed 通栏，跟着一起收进手机列 */
  .phone.phone-mini .wait-panel { max-width: 420px; margin-left: auto; margin-right: auto; }
  /* 侧滑返回指示条同样对齐手机列左缘（列宽 420 居中 → 左缘在 50vw - 210px），
     与 _initSwipeBack 里按列矩形算的起手区保持一致 */
  .phone.phone-mini .swipe-edge { left: calc(50vw - 210px); }
}
</style><style>
/* Leaflet 全局防护：调度确认进入导航时，确保地图层不被任何主题样式隐藏 */
.phone.nav-mode .nav-map.leaflet-container {
  position: absolute !important;
  inset: 0 !important;
  visibility: visible !important;
  opacity: 1 !important;
  background: #e8f1fb !important;
  z-index: 1;
}
.phone.nav-mode .nav-map .leaflet-pane,
.phone.nav-mode .nav-map .leaflet-tile,
.phone.nav-mode .nav-map .leaflet-overlay-pane svg {
  visibility: visible !important;
}
@keyframes risk-blink { 0%,100% { opacity: 0.7; } 50% { opacity: 1; } }
.risk-blink { animation: risk-blink 0.8s ease-in-out infinite; }
.port-tip { background: rgba(0,0,0,0.8) !important; border: none !important; color: #fff !important; font-size: 12px !important; font-weight: 700 !important; padding: 4px 8px !important; border-radius: 4px !important; }
.nav-tri-marker { background: none !important; border: none !important; }
/* 车标位置平滑（真实 GPS 稀疏更新专用）：模拟行驶已由 rAF 逐帧插值接管（_paintVehicle 会置
   transition:none，段长按相邻 tick 实测间隔自适应）；真实 GPS 定位仍是几百毫秒一更，
   给承载 translate3d 的图标元素保留一条 120ms 线性过渡，由浏览器补成连续滑动（纯合成层）。
   相机平移走 Leaflet 的 pane transform、不改本元素 transform，故不拖影、不跟手抖动；
   旋转在独立子层 .nav-car-rot 上，也不受影响。 */
.nav-tri-marker.car-smooth { transition: transform 120ms linear; will-change: transform; }
/* 融合式导航车标（参照高德）：罗盘双圈+方位字 + 行进方向扇形光带 + 白底蓝箭头；
   整体 130px 以车标为锚点居中，rot 层由 JS 按方位角实时旋转 */
.nav-car { position: absolute; left: 0; top: 0; width: 130px; height: 130px; pointer-events: none; }
.nav-car-ring { position: absolute; inset: 0; border-radius: 50%;
  background: rgba(150, 195, 255, .10); border: 1px solid rgba(125, 170, 240, .38); }
.nav-car-ring2 { position: absolute; inset: 24px; border-radius: 50%;
  border: 1px solid rgba(125, 170, 240, .30); }
.nav-car-rot { position: absolute; inset: 0; transform-origin: 50% 50%; will-change: transform; }
/* 罗盘方位字：固定在圆圈四侧，不随行进方向旋转；白描边保证深色底图可读 */
.nav-car-dir { position: absolute; font-size: 13px; font-weight: 700; color: #3d4a63;
  text-shadow: 0 0 3px rgba(255,255,255,.9), 0 1px 2px rgba(255,255,255,.8); line-height: 1; }
.nav-car-dir-n { left: 50%; top: 6px; transform: translateX(-50%); }
.nav-car-dir-s { left: 50%; bottom: 6px; transform: translateX(-50%); }
.nav-car-dir-w { left: 8px; top: 50%; transform: translateY(-50%); }
.nav-car-dir-e { right: 8px; top: 50%; transform: translateY(-50%); }
/* 扇形光带：conic 0deg 指向上方，从 -24° 到 +24° 蓝色渐隐，随 rot 层一起转向行驶方向 */
.nav-car-fan { position: absolute; inset: 0; border-radius: 50%;
  background: conic-gradient(from -24deg, rgba(64, 132, 244, .48) 0deg, rgba(64, 132, 244, .14) 34deg, rgba(64, 132, 244, 0) 48deg, rgba(64, 132, 244, 0) 312deg, rgba(64, 132, 244, .14) 326deg, rgba(64, 132, 244, .48) 360deg); }
.nav-car-arrow { position: absolute; left: 50%; top: 50%; width: 44px; height: 44px;
  margin: -23px 0 0 -22px; filter: drop-shadow(0 1px 3px rgba(30, 80, 150, .55)); }
/* 口岸标记：圆点 + 常驻名称都画在同一个 divIcon 内（替代 permanent Tooltip） */
.port-label-wrap { background: none !important; border: none !important; }
.port-dot { position: absolute; left: -6px; top: -6px; width: 12px; height: 12px; border-radius: 50%;
  background: #f39c12; border: 2px solid #fff; box-shadow: 0 1px 4px rgba(0,0,0,.35); }
.port-label-text { position: absolute; left: 0; top: -26px; transform: translateX(-50%); white-space: nowrap;
  background: rgba(0,0,0,.78); color: #fff; font-size: 11px; font-weight: 700;
  padding: 2px 7px; border-radius: 6px; pointer-events: none; }
/* 陆水联运后续走廊节点（方案B）：与口岸标记同套路的 divIcon 常驻标签，深色底区别于公路口岸橙色标 */
.corridor-label-wrap { background: none !important; border: none !important; }
.corridor-dot { position: absolute; left: -5px; top: -5px; width: 10px; height: 10px; border-radius: 50%;
  border: 2px solid #fff; box-shadow: 0 1px 4px rgba(0,0,0,.35); }
.corridor-label-text { position: absolute; left: 0; top: -24px; transform: translateX(-50%); white-space: nowrap;
  background: rgba(8,15,30,.82); color: #e2e8f0; font-size: 10.5px; font-weight: 700;
  padding: 2px 6px; border-radius: 6px; pointer-events: none; }
/* 起终点装饰（高德风）：起点绿气泡 / 终点🚩红旗 + 名称 / 红色虚线位移直线（线体在 odPane，这里是标记） */
.od-wrap { background: none !important; border: none !important; }
.od-bubble { position: absolute; transform: translate(-50%, -50%); white-space: nowrap;
  color: #fff; font-size: 12px; font-weight: 800; line-height: 1;
  padding: 5px 7px; border-radius: 999px; border: 2px solid #fff;
  box-shadow: 0 1px 6px rgba(0,0,0,.4); pointer-events: none; }
.od-bubble-start { background: #22a35a; }
.od-bubble-end { background: #e53935; top: -22px; font-size: 13px; }
.od-end-dot { position: absolute; left: -5px; top: -5px; width: 10px; height: 10px; border-radius: 50%;
  background: #e53935; border: 2px solid #fff; box-shadow: 0 1px 4px rgba(0,0,0,.35); }
.od-end-name { position: absolute; left: 0; top: 12px; transform: translateX(-50%); white-space: nowrap;
  background: rgba(0,0,0,.78); color: #fff; font-size: 11.5px; font-weight: 700;
  padding: 2px 7px; border-radius: 6px; pointer-events: none; }
/* 「当前位置」标签：直接画在标记图标内（替代原来的 permanent Tooltip，避免其空 _map 抛异常） */
.nav-tri-label { position: absolute; top: -20px; left: 50%; transform: translateX(-50%); white-space: nowrap;
  background: rgba(0,0,0,.78); color: #fff; font-size: 11px; font-weight: 700;
  padding: 2px 7px; border-radius: 6px; pointer-events: none; }
/* 当前车辆定位 pin：淡蓝圆形头像（白色人形）+ 下方蓝色针尾，整体带白描边。尺寸偏小。 */
.gps-pin { position: absolute; left: 0; top: 0; width: 30px; height: 40px; }
.gps-pin-head { position: absolute; top: 2px; left: 50%; transform: translateX(-50%);
  width: 26px; height: 26px; border-radius: 50%; box-sizing: border-box;
  background: radial-gradient(circle at 50% 36%, #7cc0f2 0%, #2f80d8 100%);
  border: 2.5px solid #fff; box-shadow: 0 2px 6px rgba(30,80,150,.45);
  display: flex; align-items: center; justify-content: center; }
.gps-pin-head svg { display: block; margin-top: 3px; }
.gps-pin-tail { position: absolute; top: 25px; left: 50%; transform: translateX(-50%);
  width: 0; height: 0; border-left: 5.5px solid transparent; border-right: 5.5px solid transparent;
  border-top: 10px solid #2f80d8; filter: drop-shadow(0 1px 1px rgba(30,80,150,.35)); }
@keyframes nav-pulse { 0%,100% { transform: scale(1); opacity: 1; } 50% { transform: scale(1.15); opacity: .85; } }
.gps-searching { animation: gps-blink 0.8s ease-in-out infinite; }
@keyframes gps-blink { 0%,100% { opacity: 1; } 50% { opacity: .3; } }

/* ============================================================
   浅色主题覆盖 · 高级玻璃拟态（仅换配色不动布局）
   导航/地图界面保持原样以保可读性，只覆盖首页与各 tab 卡片
   ============================================================ */
.phone:not(.nav-mode) {
  background:
    radial-gradient(ellipse 120% 60% at 50% -10%, rgba(22,99,230,.14), transparent 60%),
    radial-gradient(ellipse 80% 40% at 90% 110%, rgba(124,92,255,.10), transparent 55%),
    linear-gradient(180deg, #f3f6fc 0%, #e9eef9 50%, #eef3fb 100%);
  color: #33415c;
}
.phone:not(.nav-mode) .home-hero {
  background: var(--prism-grad);
  border-bottom: 1px solid rgba(15,79,196,.18);
  box-shadow: 0 6px 20px rgba(99,102,241,.22);
}
.phone:not(.nav-mode) .home-logo h1 {
  color: #ffffff;
  text-shadow: 0 2px 16px rgba(30,40,90,.35), 0 0 40px rgba(255,255,255,.25);
  letter-spacing: 2px;
}
.phone:not(.nav-mode) .home-logo .logo-icon { display: inline-block; }
.phone:not(.nav-mode) .home-sub { color: #dbe4ff; text-shadow: 0 1px 8px rgba(30,40,90,.3); }

/* 表单卡 / 通用卡片 → 浅色玻璃拟态 + 微光描边 */
.phone:not(.nav-mode) .form-card,
.phone:not(.nav-mode) .card {
  background: #fff;
  border: 1px solid #e7eaf0;
  box-shadow: 0 1px 2px rgba(16,24,40,.04);
  border-radius: 12px;
}
.phone:not(.nav-mode) .card-head { color: #1f2d4d; }
.phone:not(.nav-mode) .me-label,
.phone:not(.nav-mode) .cand-header { color: #64748f; }

/* 起终点输入盒 */
.phone:not(.nav-mode) .od-box { background: #fff; border: 1px solid #e7eaf0; }
.phone:not(.nav-mode) .od-fields input { color: #1f2d4d; }
.phone:not(.nav-mode) .od-fields input::placeholder { color: #9aa7bd; }
.phone:not(.nav-mode) .me-input { background: transparent; color: #111827; }
.phone:not(.nav-mode) .me-select { background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' fill='none' stroke='%2398a2b3' stroke-width='1.5' stroke-linecap='round'/%3E%3C/svg%3E") no-repeat right center; }
.phone:not(.nav-mode) .me-hero,
.phone:not(.nav-mode) .me-group { background: #fff; border-color: #e7eaf0; }
.phone:not(.nav-mode) .me-hero-name { color: #1f2d4d; }
.phone:not(.nav-mode) .me-truck { background: #eef1f8; color: #64748f; }
.phone:not(.nav-mode) .me-row + .me-row { border-top-color: #eef1f8; }

/* 快捷路线 chip / 知识 chip */
.phone:not(.nav-mode) .quick-chip,
.phone:not(.nav-mode) .chip { background: #ffffff; border-color: #dbe3f2; color: var(--brand); }
.phone:not(.nav-mode) .quick-chip:hover,
.phone:not(.nav-mode) .chip:hover { background: #f6f8fd; border-color: var(--brand); }

/* 主按钮：棱彩渐变（与导航/预览态一致，不再被首页主题刷成纯色） */
.phone:not(.nav-mode) .start-btn { background: var(--prism-grad); background-size: 220% 100%; box-shadow: 0 4px 16px rgba(99,102,241,.38); }

/* 候选路线卡 */
.phone:not(.nav-mode) .cand-item { background: #ffffff; border-color: #eef1f8; }
.phone:not(.nav-mode) .cand-item.sel { border-color: var(--brand); background: #f6f8fd; box-shadow: 0 0 0 3px rgba(22,99,230,.15); }
.phone:not(.nav-mode) .cand-title { color: #1f2d4d; }
.phone:not(.nav-mode) .cand-via { color: #64748f; }
.phone:not(.nav-mode) .meta-tag { background: #eef1f8; color: #64748f; }
.phone:not(.nav-mode) .meta-tag.warn { background: rgba(225,29,72,.15); color: #e11d48; }
.phone:not(.nav-mode) .cand-radio.on { border-color: var(--brand); background: var(--brand); }

/* 底部信息条 & 导航 */
.phone:not(.nav-mode) .info-bar { color: #64748f; }
.phone:not(.nav-mode) .driver-tag { color: #1f2d4d; }
.phone:not(.nav-mode) .cargo-tag { background: #eef1f8; color: #64748f; }
.phone:not(.nav-mode) .home-nav { background: #ffffff; border: 1px solid #eef1f8; }
.phone:not(.nav-mode) .home-nav span { color: #64748f; }
.phone:not(.nav-mode) .home-nav span.active { background: var(--brand-soft); color: var(--brand); }
.phone:not(.nav-mode) .home-nav .has-task { color: #e11d48; }

/* 风险/预警/知识 卡内容 */
.phone:not(.nav-mode) .empty { color: #9aa7bd; }
.phone:not(.nav-mode) .refresh { background: #fff; border-color: #cdd9ee; color: var(--brand); }
.phone:not(.nav-mode) .refresh:hover { background: var(--brand-soft); }
.phone:not(.nav-mode) .refresh.on { background: #e7f6ec; border-color: #b5e3c8; color: #16a34a; }
.phone:not(.nav-mode) .warning-box { background: rgba(217,119,6,.12); border-color: rgba(217,119,6,.4); color: #b45309; }
.phone:not(.nav-mode) .risk-item { background: #f6f8fd; border-left-color: #d97706; }
.phone:not(.nav-mode) .risk-item:hover { background: #eef1f8; }
.phone:not(.nav-mode) .risk-item.critical { border-left-color: #e11d48; background: rgba(225,29,72,.1); }
.phone:not(.nav-mode) .risk-item.high { border-left-color: #ea7a2e; background: rgba(234,122,46,.08); }
.phone:not(.nav-mode) .risk-reason { color: #1f2d4d; }
.phone:not(.nav-mode) .risk-edge { color: #64748f; }
.phone:not(.nav-mode) .kb-result { background: #f6f8fd; border-color: #eef1f8; }
.phone:not(.nav-mode) .kb-title { color: #1f2d4d; }
.phone:not(.nav-mode) .kb-content { color: #64748f; }
.phone:not(.nav-mode) .count { background: #e11d48; }
.phone:not(.nav-mode) .save-btn { background: var(--brand); }

/* 任务变更卡（深色版） */
.phone:not(.nav-mode) .task-card { border-left-color: #ea7a2e; }
.phone:not(.nav-mode) .task-card.confirmed { border-left-color: #22a35a; }
.phone:not(.nav-mode) .task-state.ok { color: #16a34a; }
.phone:not(.nav-mode) .task-state.pending { color: #e11d48; }
.phone:not(.nav-mode) .task-channel { background: #eef1f8; color: #64748f; }
.phone:not(.nav-mode) .task-plan { background: rgba(22,99,230,.15); color: var(--brand); }
.phone:not(.nav-mode) .lang-btn { background: #ffffff; border-color: #dbe3f2; color: #64748f; }
.phone:not(.nav-mode) .lang-btn.on { border-color: var(--brand); color: var(--brand); background: rgba(22,99,230,.15); }
.phone:not(.nav-mode) .speak-btn { background: #ffffff; border-color: #dbe3f2; color: #64748f; }
.phone:not(.nav-mode) .task-msg { background: #f4f6fc; color: #1f2d4d; border: 1px solid #e2e8f5; }
.phone:not(.nav-mode) .rights-box { background: linear-gradient(135deg, rgba(22,163,74,.14), rgba(22,163,74,.06)); border-color: rgba(22,163,74,.4); }
.phone:not(.nav-mode) .rights-title { color: #16a34a; }
.phone:not(.nav-mode) .rights-item { color: #15803d; }
.phone:not(.nav-mode) .confirm-task-btn { background: #16a34a; box-shadow: 0 2px 8px rgba(22,163,74,.28); }
.phone:not(.nav-mode) .confirm-task-btn.done { background: #eef1f8; color: #16a34a; }
.phone:not(.nav-mode) .task-hint { color: #9aa7bd; }
.phone:not(.nav-mode) .plan-item { background: #f6f8fd; border-color: #eef1f8; }
.phone:not(.nav-mode) .plan-item.rec { border-color: #22a35a; background: rgba(22,163,74,.08); }
.phone:not(.nav-mode) .plan-badge { background: rgba(22,99,230,.15); color: var(--brand); }
.phone:not(.nav-mode) .plan-name { color: #1f2d4d; }
.phone:not(.nav-mode) .plan-meta { color: #64748f; }
.phone:not(.nav-mode) .plan-meta .warn { color: #e11d48; }
.phone:not(.nav-mode) .plan-meta .ok { color: #16a34a; }
.phone:not(.nav-mode) .plan-risk { color: #e11d48; }
.phone:not(.nav-mode) .plan-rec { background: rgba(124,92,255,.12); color: var(--brand-deep); }
.phone:not(.nav-mode) .touch-bar { background: #eef1f8; }
.phone:not(.nav-mode) .touch-fill { background: linear-gradient(90deg, #1fa84a, #22a35a); }
.phone:not(.nav-mode) .touch-text { color: #64748f; }

/* 顶部推送条（深色可读） */
.phone:not(.nav-mode) .push-banner { box-shadow: 0 8px 24px rgba(50,70,120,.15); }

/* ---------- 发光特效 + 点击反馈（深色区全局） ---------- */
/* 主按钮：霓虹辉光 + 按压下沉 */
.phone:not(.nav-mode) .start-btn:active:not(:disabled) {
  transform: scale(.98);
  filter: brightness(.9);
  box-shadow: 0 1px 6px rgba(99,102,241,.35);
}

/* 确认接收按钮：翠绿辉光 */
.phone:not(.nav-mode) .confirm-task-btn:active:not(:disabled) {
  transform: scale(.98);
  background: #15803d;
}

/* 可点卡片/列表项：按压缩放 + 亮起 */
.phone:not(.nav-mode) .cand-item,
.phone:not(.nav-mode) .risk-item,
.phone:not(.nav-mode) .plan-item,
.phone:not(.nav-mode) .quick-chip,
.phone:not(.nav-mode) .chip,
.phone:not(.nav-mode) .lang-btn,
.phone:not(.nav-mode) .speak-btn,
.phone:not(.nav-mode) .refresh {
  transition: transform .12s, box-shadow .25s, border-color .2s, filter .2s, background .2s;
}
.phone:not(.nav-mode) .cand-item:active,
.phone:not(.nav-mode) .risk-item:active,
.phone:not(.nav-mode) .plan-item:active,
.phone:not(.nav-mode) .quick-chip:active,
.phone:not(.nav-mode) .chip:active,
.phone:not(.nav-mode) .lang-btn:active,
.phone:not(.nav-mode) .speak-btn:active,
.phone:not(.nav-mode) .refresh:active {
  transform: scale(.98);
}

/* 底部导航 tab：按压点亮 + 选中辉光 */
.phone:not(.nav-mode) .home-nav span { transition: transform .12s, background .2s, color .2s, box-shadow .25s; }
.phone:not(.nav-mode) .home-nav span:active { transform: scale(.9); }

/* 涟漪波纹（JS 注入 .ripple-host > .ripple-ink） */
.ripple-host { position: relative; overflow: hidden; }
.ripple-ink {
  position: absolute; border-radius: 50%; pointer-events: none;
  background: radial-gradient(circle, rgba(140,160,255,.45) 0%, rgba(140,160,255,0) 70%);
  transform: scale(0); animation: ripple-spread .55s ease-out forwards;
}
@keyframes ripple-spread { to { transform: scale(2.6); opacity: 0; } }

/* 返回键：玻璃胶囊 + 辉光 */
.drv-back-btn {
  display: inline-flex; align-items: center; gap: 4px;
  margin: 0 0 10px 2px; padding: 7px 14px 7px 10px;
  border-radius: 999px; border: 1px solid rgba(105,125,175,.25);
  background: linear-gradient(165deg, rgba(255,255,255,.7), rgba(255,255,255,.75));
  color: var(--brand); font-size: 13px; font-weight: 600; cursor: pointer;
  box-shadow: 0 4px 14px rgba(50,70,120,.14), inset 0 1px 0 rgba(140,160,220,.1);
  backdrop-filter: blur(10px);
  transition: transform .12s, box-shadow .25s, color .2s;
}
.drv-back-btn .back-arrow { font-size: 15px; line-height: 1; }
.drv-back-btn:hover { color: #3556d4; box-shadow: 0 6px 18px rgba(0,0,0,.4), 0 0 18px rgba(22,99,230,.2); }
.drv-back-btn:active { transform: scale(.93); }

/* 左边缘侧滑返回指示条：拖动时点亮，位移/透明度由模板按跟手进度内联绑定。
   fixed 贴视口左边缘，z-index 高于导航屏(100)与弹层，pointer-events:none 不拦截点击。 */
.swipe-edge {
  position: fixed; left: 0; top: 0; bottom: 0; width: 40px; z-index: 2147483646;
  display: flex; align-items: center; pointer-events: none;
  opacity: 0; transition: opacity .2s ease;
}
.swipe-edge.on { opacity: 1; transition: none; }
.swipe-edge-glow {
  position: absolute; left: 0; top: 0; bottom: 0; width: 100%;
  background: linear-gradient(90deg, rgba(99,102,241,.30), rgba(99,102,241,0));
  transform: scaleX(0); transform-origin: left center;
}
.swipe-edge-chev {
  position: relative; margin-left: 3px;
  width: 22px; height: 46px; border-radius: 999px;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 20px; font-weight: 700; line-height: 1;
  background: rgba(99,102,241,.9); box-shadow: 0 2px 12px rgba(50,70,120,.35);
  transform: translateX(-16px) scale(.7); opacity: .35;
}
</style>