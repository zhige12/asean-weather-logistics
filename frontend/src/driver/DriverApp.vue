<template>
  <div class="phone" :class="{ 'nav-mode': navigating || previewing }">
    <!-- ====== HOME PAGE ====== -->
    <div v-if="!navigating && !previewing" class="home-page">
      <div class="home-hero">
        <div class="home-logo">
          <span class="logo-icon">🚛</span>
          <h1>跨境物流导航</h1>
          <p class="home-sub">东盟 · 气象智能预警 · 安全护航</p>
        </div>
      </div>

      <div class="home-form">
        <div class="form-card">
          <div class="form-row">
            <label class="form-label">出发地</label>
            <div class="form-input-wrap">
              <span class="input-icon from">起</span>
              <input v-model="originId" list="node-suggestions"
                     placeholder="输入城市名或 ID，如 南宁"
                     @focus="$event.target.select()" />
            </div>
          </div>
          <div class="form-swap" @click="swapOD">
            <span>⇅</span>
          </div>
          <div class="form-row">
            <label class="form-label">目的地</label>
            <div class="form-input-wrap">
              <span class="input-icon to">终</span>
              <input v-model="destinationId" list="node-suggestions"
                     placeholder="输入城市名或 ID，如 河内"
                     @focus="$event.target.select()" />
            </div>
          </div>
          <datalist id="node-suggestions">
            <option v-for="(name, id) in NODE_NAMES" :key="id" :value="name">{{ id }}</option>
          </datalist>

          <div class="quick-picks">
            <span class="quick-label">常用路线</span>
            <div class="quick-chips">
              <button v-for="r in quickRoutes" :key="r.key" class="quick-chip"
                      @click="originId = r.from; destinationId = r.to">
                {{ r.label }}
              </button>
            </div>
          </div>

          <button class="start-btn" :disabled="routeLoading || candidateLoading || !originId || !destinationId"
                  @click="btnAction">
            <span v-if="routeLoading || candidateLoading" class="spinner"></span>
            <span v-if="routeLoading">路线计算中…</span>
            <span v-else-if="candidateLoading">搜索路线中…</span>
            <span v-else>{{ candidates.length ? '查看路线预览' : '搜索路线并进入地图预览' }}</span>
          </button>
        </div>

        <!-- 多路线候选 -->
        <div v-if="candidates.length && !navigating && !previewing" class="cand-section">
          <div class="cand-header">
            <span>可选路线（{{ candidates.length }} 条）</span>
            <span class="cand-hint">点选一条进入地图预览</span>
          </div>
          <div v-for="c in candidates" :key="c.key" class="cand-item" :class="{ sel: c.key === selectedKey }"
               @click="openPreview(c.key)">
            <div class="cand-left">
              <span class="cand-radio" :class="{ on: c.key === selectedKey }"></span>
              <div class="cand-info">
                <div class="cand-title">{{ c.label }}</div>
                <div class="cand-via">{{ c.via }}</div>
                <div class="cand-meta">
                  <span class="meta-tag">{{ c.hours }}h</span>
                  <span class="meta-tag">{{ c.distanceKm }}km</span>
                  <span class="meta-tag" :class="{ warn: c.riskCount > 0 }">
                    {{ c.riskCount > 0 ? '⚠ ' + c.riskCount + ' 风险' : '✓ 无风险' }}
                  </span>
                </div>
              </div>
            </div>
            <div class="cand-right">
              <span v-if="c.hazardProbability >= 0" class="prob-pill" :class="probClass(c.hazardProbability)">
                {{ c.hazardProbability }}%
              </span>
              <span class="prob-arrow" v-if="c.key === selectedKey">▶</span>
            </div>
          </div>
        </div>

        <!-- 司机/货物信息快捷入口 -->
        <div class="home-bottom">
          <!-- 模式标识（剧本第一/四幕） -->
          <div class="mode-bar" :class="mode === 'LOGISTICS' ? 'logi' : 'pub'">
            <span class="mode-name">
              {{ mode === 'LOGISTICS' ? '📋 物流任务模式' : '🧭 普通导航模式' }}
            </span>
            <span class="mode-hint">
              {{ mode === 'LOGISTICS' ? (task ? task.taskId : '') : '免费导航 · 天气与口岸提醒' }}
            </span>
            <button v-if="mode === 'LOGISTICS' && task && task.status !== 'ACCEPTED'"
                    class="mode-act accept" :disabled="taskAccepting" @click="acceptTask">
              {{ taskAccepting ? '接单中…' : '确认接单' }}
            </button>
            <span v-else-if="mode === 'LOGISTICS'" class="mode-act done">已接单</span>
            <span v-if="mode === 'LOGISTICS'" class="mode-act-exit" @click="exitLogisticsMode">退出任务</span>
          </div>

          <!-- 路线状态条（绿色=畅通，剧本第四幕）；仅物流任务模式显示 -->
          <div v-if="mode === 'LOGISTICS'" class="status-strip" :class="statusClass">
            <span class="strip-dot"></span>
            <span class="strip-text">{{ statusText }}</span>
          </div>

          <div class="info-bar">
            <span class="driver-tag">{{ driverName }} · {{ cargo.plate }}</span>
            <span class="cargo-tag">{{ cargo.name }} {{ cargo.weight }}t</span>
            <span class="link" @click="tab = 'me'">{{ mode === 'LOGISTICS' ? '详情 ›' : '编辑 ›' }}</span>
          </div>
          <nav class="home-nav">
            <span :class="{ active: tab === 'route' }" @click="tab = 'route'">路线</span>
            <span :class="{ active: tab === 'task', 'has-task': taskChange && !taskChange.confirmed }" @click="tab = 'task'">
              任务<span v-if="taskChange && !taskChange.confirmed" class="task-dot"></span>
            </span>
            <span :class="{ active: tab === 'alert' }" @click="tab = 'alert'">预警</span>
            <span :class="{ active: tab === 'kb' }" @click="tab = 'kb'">知识库</span>
            <span :class="{ active: tab === 'me' }" @click="tab = 'me'">我</span>
          </nav>

          <!-- 非首页 tab 内容（任务/预警/知识库/我） -->
          <div v-if="tab !== 'route'" class="tab-content">
            <button class="drv-back-btn" @click="tab = 'route'">
              <span class="back-arrow">‹</span> 返回
            </button>
            <!-- ===== 任务变更（调度大屏对齐：第五幕） ===== -->
            <div v-show="tab === 'task'">
              <section class="card task-card" :class="{ confirmed: taskChange && taskChange.confirmed }">
                <div class="card-head">
                  <span>📱 任务变更通知</span>
                  <span v-if="taskChange" class="task-state" :class="taskChange.confirmed ? 'ok' : 'pending'">
                    {{ taskChange.confirmed ? '✅ 已确认' : '⏳ 待确认' }}
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
                    <div class="rights-title">🛡 您的权益保障</div>
                    <div class="rights-item">✓ 随船期间按出勤计算工时，额外发放随船补贴</div>
                    <div class="rights-item">✓ 车辆滚装段已购买专项运输险</div>
                    <div class="rights-item">✓ 船上安排司机休息舱位，含餐饮</div>
                    <div class="rights-item">✓ 抵达海防港后公司安排返程交通</div>
                    <div class="rights-item">✓ 本次变更属不可抗力调度调整，不视为司机违约</div>
                  </div>
                  <button v-if="!taskChange.confirmed" class="confirm-task-btn" :disabled="taskConfirming" @click="confirmTaskChange">
                    {{ taskConfirming ? '确认中…' : '✅ 确认接收' }}
                  </button>
                  <button v-else class="confirm-task-btn done" disabled>已确认接收 · 新路线已同步</button>
                  <div class="task-hint">如有异议，请联系调度中心。切换运输方式是调度端的决策权限。</div>
                </template>
                <div v-else class="empty">暂无任务变更。调度中心确认方案后，任务变更指令将实时推送到这里。</div>
              </section>

              <!-- AI 方案建议（让司机理解调度决策依据） -->
              <section class="card">
                <div class="card-head">
                  <span>🧠 AI 方案建议</span>
                  <button class="refresh" @click="loadAgentPlans" :disabled="agentPlansLoading">{{ agentPlansLoading ? '分析中…' : '查看方案' }}</button>
                </div>
                <template v-if="agentPlans">
                  <div v-for="p in agentPlans.plans" :key="p.id" class="plan-item" :class="{ rec: p.id === (agentPlans.plans.find(x=>x.id==='B')?'B':'A') }">
                    <div class="plan-head">
                      <span class="plan-badge">{{ p.id }}</span>
                      <span class="plan-name">{{ p.name }}</span>
                    </div>
                    <div class="plan-meta">
                      <span :class="{ warn: p.extraHours > 0 }">⏱ {{ p.extraHours != null ? (p.extraHours>0?'+':'')+p.extraHours+'h' : '不可用' }}</span>
                      <span :class="{ ok: p.costDeltaYuan < 0 }">💰 {{ p.costDeltaYuan != null ? (p.costDeltaYuan>0?'+':'')+p.costDeltaYuan+'元' : '-' }}</span>
                      <span class="plan-risk">货损{{ p.damageRisk }}</span>
                    </div>
                  </div>
                  <div class="plan-rec">💡 {{ agentPlans.recommendation }}</div>
                </template>
                <div v-else class="empty">点击查看调度端 AI 生成的三方案对比</div>
              </section>

              <!-- 触达状态（本人视角） -->
              <section class="card" v-if="outreachStatus.total">
                <div class="card-head"><span>📡 触达状态</span><span class="count">{{ outreachStatus.confirmed }}/{{ outreachStatus.total }}</span></div>
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
                <div v-else class="empty">点击生成 DeepSeek 中越双语预警</div>
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
              <section class="card">
                <div class="card-head"><span>司机信息</span></div>
                <!-- 物流任务模式下车辆与货物由派单下发，司机不可改，
                     避免演示中被改动导致大屏与司机端信息对不上 -->
                <div v-if="mode === 'LOGISTICS'" class="me-locked">
                  📋 物流任务模式：车牌与货物信息以公司派单为准，不可修改
                </div>
                <div class="me-row"><span class="me-label">姓名</span><input v-model="driverName" class="me-input" /></div>
                <div class="me-row"><span class="me-label">车牌</span><input v-model="cargo.plate" class="me-input" :disabled="mode === 'LOGISTICS'" /></div>
                <div class="me-row"><span class="me-label">车型</span>
                  <select v-model="truckType" class="me-input">
                    <option>冷藏半挂</option><option>普货半挂</option><option>危化罐车</option><option>大件平板</option><option>厢式中卡</option>
                  </select>
                </div>
                <div class="me-row"><span class="me-label">货物</span><input v-model="cargo.name" class="me-input" :disabled="mode === 'LOGISTICS'" /></div>
                <div class="me-row"><span class="me-label">重量(t)</span><input v-model.number="cargo.weight" type="number" class="me-input" :disabled="mode === 'LOGISTICS'" /></div>
                <div class="me-row"><span class="me-label">温控</span><input v-model="cargo.temp" class="me-input" :disabled="mode === 'LOGISTICS'" /></div>
                <div class="me-row"><span class="me-label">语音</span>
                  <button class="refresh" @click="voiceOn = !voiceOn">{{ voiceOn ? '已开启' : '已关闭' }}</button>
                </div>
                <button class="save-btn" @click="saveDriverInfo">保存信息</button>
              </section>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ====== FULL-SCREEN NAVIGATION ====== -->
    <!-- ====== FULL-SCREEN MAP（路线预览 / 导航中）====== -->
    <div v-if="navigating || previewing" class="nav-screen" :class="{ split: previewing }">
      <!-- 地图区域：占上方剩余空间。预览时面板固定在下方，与地图上下分栏、互不重叠 -->
      <div class="nav-map-wrap">
        <!-- 地图 -->
        <div ref="mapEl" class="nav-map"></div>

        <!-- 地图图例 -->
        <div class="nav-legend">
          <span><i class="lg lg-route"></i>安全路段</span>
          <span><i class="lg lg-risk"></i>风险路段</span>
          <span><i class="lg lg-port"></i>口岸</span>
        </div>

        <!-- 顶部状态栏（预览 / 导航共用）-->
        <div ref="topStack" class="nav-topbar">
          <button class="nav-back" :title="navigating ? '退出导航' : '返回首页'"
                  @click="navigating ? requestExit() : exitPreview()">
            <span>✕</span>
          </button>
          <div class="nav-route-label">
            <span class="nav-from">{{ originName }}</span>
            <span class="nav-arrow">→</span>
            <span class="nav-to">{{ destinationName }}</span>
          </div>
          <div class="nav-status" :class="navigating ? statusClass : previewStatusClass">
            <span class="status-dot"></span>
            <span>{{ navigating ? statusText : previewStatusText }}</span>
          </div>
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
                <div class="preview-title"><span class="pv-ico">🧭</span>路线预览</div>
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
                   class="pv-item" :class="{ sel: c.key === selectedKey, safest: c.key === safestKey, risky: isHighRisk(c) }"
                   :style="{ animationDelay: (i * 60) + 'ms' }"
                   @click="selectCandidate(c.key)">
                <span class="cand-radio" :class="{ on: c.key === selectedKey }"></span>
                <div class="pv-info">
                  <div class="pv-title">
                    {{ c.label }}
                    <span v-if="c.key === safestKey" class="pv-flag safest-flag">AI 最安全</span>
                    <span v-else-if="c.key === selectedKey" class="pv-flag">已选</span>
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
                  <span v-if="c.hazardProbability >= 0" class="prob-pill glow" :class="probClass(c.hazardProbability)">
                    {{ c.hazardProbability }}%
                  </span>
                  <span v-else class="prob-pill unknown">暂无预测</span>
                  <button class="pv-detail-btn" :disabled="c.hazardProbability < 0"
                          @click.stop="openHazardDetail(c)">
                    展开详情 ▾
                  </button>
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
          <div class="nav-info-main">/
            <div class="nav-eta">
              <span class="eta-num">{{ route.estimatedHours ? route.estimatedHours.toFixed(1) : '--' }}</span>
              <span class="eta-unit">小时</span>
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
            <!-- 水运方案：明示公水联运走平陆运河，司机一眼知道自己在哪种运输方式上 -->
            <span v-if="isCanalPlan" class="canal-tag">🚢 公水联运 · 平陆运河</span>
          </div>
          <div class="eta-bar"><span class="eta-bar-fill"></span></div>
        </div>

        <!-- 操作按钮 -->
        <div class="nav-actions">
          <button class="nav-btn" @click="locateMe" title="我的位置">
            <span>📍</span>
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
                {{ opt.hazardProbability }}%
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
    </div>
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
import { normalizeForTTS, detectLanguage, HAZARD_VN_MAP } from '../utils/ttsNormalizer.js'

// 口岸坐标（真实经纬度），供司机端地图标注
const PORTS = [
  { id: 'E4', name: '友谊关', lat: 21.9717, lng: 106.7086 },
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
// 公水联运方案的第一程是「南宁 → 南宁港六景作业区」，属国内段，不能标「跨境」。
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

/** 模拟行驶节拍（毫秒）与速度（米/秒，≈90km/h）。按里程平滑推进，避免按折线点跳。 */
const SIM_TICK_MS = 100
const SIM_SPEED_MPS = 25

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
  data() {
    return {
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
      showReroute: false,
      voiceOn: true,
      // 行程（司机可手动选择/输入起点终点）
      originId: 'NN',
      destinationId: 'HN',
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
      // 当前执行的调度方案：'A'=公路绕行 / 'B'=公水联运 / 'C'=原地等待 / null=非调度（司机自己规划的行程）。
      // 之前 planId 只在 _switchNavForPlan/_autoStartDispatchNav 的参数里用过就丢了，
      // 导致播报和状态卡无从判断"当前是不是水运方案"。改为落库到组件状态，供全流程取用。
      activePlanId: null,
      // GPS 定位状态
      gpsStatus: 'searching', // searching | locked | unavailable | simulating
      // 导航状态
      routeLoading: false,
      candidateLoading: false,
      navigating: false,
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
      // 多路线候选（司机自选）
      candidates: [],
      selectedKey: 'recommended',
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
      _voiceCooldown: 0,
      // === 调度大屏功能对齐（11幕演示）===
      // 任务变更通知（调度下发）：中越双语 + 权益保障 + 确认接收
      taskChange: null,
      taskConfirming: false,
      taskLang: 'zh',
      // 触达状态（本人确认状态同步）
      outreachStatus: { targets: [], total: 0, confirmed: 0, pending: 0 },
      // AI 方案建议（A/B/C 三方案对比，供司机理解调度决策依据）
      agentPlans: null,
      agentPlansLoading: false,
      // 底图源：false=在线 OSM（默认），true=离线瓦片（断网兜底）
      _useOfflineTiles: false,
      _osmFailCount: 0,
      _tileSourceDetected: false,
      _lastVoiceText: '',
      _lastVoiceAt: 0,
      // 播报世代号：抢占时递增，旧播报链路的回调据此自行作废，避免旧内容继续播
      _voiceSeq: 0,
      // 最近一次 _cancelVoice() 的 Promise：起播前要 await 它，确保旧语音（尤其原生 TTS）真的停了
      _cancelPromise: null,
      // 高优先级播报占用标记（导航开始播报用）：占用期间其它播报一律不许抢占
      _voiceLocked: false,
      _voiceLockTimer: null,
      // 语音链路诊断：仅 URL 带 ?voicedebug=1 时在导航页显示（不影响正常演示）
      voiceDebugOn: false,
      voiceDebug: '',
      // 绕行播报去重：同一起灾害事件只播报一遍
      // （SSE 推送、轮询兜底、重取路线会并发触发 checkPush，文本细节不同会绕过普通去重）
      _hazardVoiceKey: '',
      // 灾害即时提示的去重键（当前灾害边集合），避免同一批灾害重复弹窗/播报
      _hazardNotifyKey: '',
      _hazardVoiceAt: 0,
      // 全屏导航菜单
      showNavMenu: false,
      // 退出导航二次确认弹层
      showExitConfirm: false,
      map: null,
      baseLine: null,
      routeLine: null,
      riskLine: null,
      portMarkers: []
    }
  },
  computed: {
    // 当前路线上的风险段（后端 riskSegments 下发的是全量风险，需按路径边过滤，
    // 保证「风险路段」列表与地图上标红的路段完全一致）
    pathRisks() { return this._risksOnRoute(this.route) },
    // 预计到达终点时刻 = 当前时间 + 全程耗时
    etaArrival() {
      if (!this.route.estimatedHours) return '--'
      const d = new Date(Date.now() + this.route.estimatedHours * 3600 * 1000)
      return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Shanghai' })
    },
    etaArrivalVN() {
      if (!this.route.estimatedHours) return '--'
      const d = new Date(Date.now() + this.route.estimatedHours * 3600 * 1000)
      return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Ho_Chi_Minh' })
    },
    // TTS 播报用口语化日期格式：9月8日15点30分
    etaArrivalZh() {
      if (!this.route.estimatedHours) return '--'
      const d = new Date(Date.now() + this.route.estimatedHours * 3600 * 1000)
      return `${d.getMonth() + 1}月${d.getDate()}日${d.getHours()}点${d.getMinutes() === 0 ? '' : d.getMinutes() + '分'}`
    },
    // TTS 播报用越南语口语化日期格式：ngày 8 tháng 9, 15 giờ 30 phút
    etaArrivalViTTS() {
      if (!this.route.estimatedHours) return '--'
      const d = new Date(Date.now() + this.route.estimatedHours * 3600 * 1000)
      const parts = new Intl.DateTimeFormat('en-US', {
        timeZone: 'Asia/Ho_Chi_Minh', month: 'numeric', day: 'numeric',
        hour: '2-digit', minute: '2-digit', hour12: false
      }).formatToParts(d)
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
    /** 当前是否在「公水联运（平陆运河）」方案上——播报/状态卡据此提到平陆运河 */
    isCanalPlan() { return this.activePlanId === 'B' },
    /** 运输方式简述（中文），供出发播报用。措辞避免与上一句"到南宁港六景作业区"重复 */
    transportBriefZh() {
      return this.isCanalPlan()
        ? '本次为公水联运，车辆在港区交接后，您随船经平陆运河至钦州港，再海运至越南海防港'
        : ''
    },
    /** 运输方式简述（越南语）。kênh đào Bình Lục = 平陆运河，与后端触达文案用词一致 */
    transportBriefVi() {
      return this.isCanalPlan()
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
      if (this.route.rerouted) return 'rerouted'
      // 用 pathRisks（已按本路线过滤）：直接用 route.riskSegments 是全网风险，
      // 别处熔断也会让状态条显示"风险预警"，而本程其实全线通畅。
      if (this.pathRisks.length) return 'warn'
      return 'ok'
    },
    statusText() {
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
        unavailable: 'gps-unavailable', simulating: 'gps-simulating'
      }[this.gpsStatus] || 'gps-searching'
    },
    gpsLabel() {
      return {
        searching: '搜索中…', locked: '已定位 ✓',
        unavailable: '不可用 ✗', simulating: '模拟行驶 △'
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
    originId(v) {
      this.cargo.from = NODE_NAMES[v] || v
      this._resetCandidates()
    },
    destinationId(v) {
      this.cargo.to = NODE_NAMES[v] || v
      this._resetCandidates()
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
    // 先探测网络/OSM 可达性决定底图源，再建图（避免离线环境开局先闪一片灰）
    this._detectTileSource().finally(() => {
      this._tileSourceDetected = true
      this.initMap()
    })
    this._initRipple()
    this.connectAgent()
    this.loadCustoms()
    // 越方司机默认越南语播报/展示
    if (this.driverRole === 'DRIVER_VN') this.taskLang = 'vi'
    // 刷新/断线重连后仍能回到物流任务模式（"拔网线照样跑"的一部分）
    this._loadCurrentTask()
    // 实时时钟
    this._clockTimer = setInterval(() => { this.$forceUpdate() }, 1000)
    // 语音引擎保活：每 30s ping 一次，防止 Chrome 自动暂停
    this._voiceKeepAlive = setInterval(() => {
      if ('speechSynthesis' in window && window.speechSynthesis.paused) {
        window.speechSynthesis.resume()
      }
    }, 30000)
    // 轮询兜底：15s 一次（SSE 为主通道）
    this.timer = setInterval(this.refreshAll, 15000)
    // AI 预警不再自动定时刷新（由用户点击触发，避免文案消失和重复播报）
  },
  beforeUnmount() {
    clearInterval(this.timer)
    if (this._rippleHandler) document.removeEventListener('pointerdown', this._rippleHandler)
    if (this.riskTimer) clearInterval(this.riskTimer)
    if (this._clockTimer) clearInterval(this._clockTimer)
    if (this._voiceKeepAlive) clearInterval(this._voiceKeepAlive)
    // 取消正在进行的播报（web speech + 原生 TTS）
    this._cancelVoice()
    // 清掉高优先级占用定时器，避免卸载后回调还在跑
    if (this._voiceLockTimer) { clearTimeout(this._voiceLockTimer); this._voiceLockTimer = null }
    this._voiceLocked = false
    if (this._riskMarkers) {
      this._riskMarkers.forEach(m => { try { this.map.removeLayer(m); } catch (e) {} })
    }
    if (this._meMarker) { try { this.map.removeLayer(this._meMarker); } catch (e) {} }
    this._stopRealTimeGPS()
    if (this._mapRO) { this._mapRO.disconnect(); this._mapRO = null }
    // 取消待执行的缩放重绘帧，避免 map 已销毁后回调仍去 drawRoute
    if (this._zoomRedrawRaf) { cancelAnimationFrame(this._zoomRedrawRaf); this._zoomRedrawRaf = null }
    if (this._agentEs) this._agentEs.close()
    if (this._routeFlow) { this._routeFlow.destroy(); this._routeFlow = null }
    if (this.map) this.map.remove()
    if (window.speechSynthesis) window.speechSynthesis.cancel()
  },
  methods: {
    /**
     * 取某条路线上**真正命中**的风险段。
     *
     * 后端下发的 riskSegments 是**全网风险列表**（RouteService 里直接塞的 currentRisks()），
     * 必须按该路线的 pathEdgeIds 过滤。不过滤就会把别处的风险当成"本路线前方风险"：
     * 南宁→南宁港六景作业区那一程就曾被误报「暴雨」，而那条风险在友谊关，根本不在这条路上。
     *
     * 注意：这是方法不是计算属性 —— 它要接收任意 route 对象（checkPush/_buildStatus* 传入的
     * 未必是 this.route）。曾误放进 computed 块，被当函数调用时直接抛 is not a function。
     */
    _risksOnRoute(r) {
      const segs = (r && r.riskSegments) || []
      if (!segs.length) return []
      const ids = new Set(((r && r.pathEdgeIds) || []).map(String))
      if (!ids.size) return segs
      return segs.filter(x => x && ids.has(String(x.edgeId)))
    },
    // ---------- 地图（计划书 4.4） ----------
    /**
     * 底图默认用 D 盘离线瓦片（EPSG:4326，z7-z13，经后端 /tiles/** 提供，不依赖外网，
     * 拔网线也能显示）。注意：4326 瓦片必须设 crs: L.CRS.EPSG4326，否则默认 3857 会整图错位。
     * 若离线瓦片不可用（换了机器/目录不存在），累计 tileerror 后自动重建为 OSM 在线底图。
     */
    initMap() {
      if (!this.$refs.mapEl) return
      // 地图重建后原特效层失效，置空由 drawRoute 重建
      if (this._routeFlow) { this._routeFlow.destroy(); this._routeFlow = null }
      this._buildMap()
    },
    _buildMap() {
      // 重建地图前先断开旧的尺寸监听，避免还在监听悬空的旧容器
      if (this._mapRO) { this._mapRO.disconnect(); this._mapRO = null }
      // 同理取消待执行的缩放重绘帧（旧 map 的 zoomend 可能刚排了一帧）
      if (this._zoomRedrawRaf) { cancelAnimationFrame(this._zoomRedrawRaf); this._zoomRedrawRaf = null }
      if (this.map) {
        this.map.remove()
        this.map = null
      }
      // 地图重建后原特效层失效，置空由 drawRoute 重建
      if (this._routeFlow) { this._routeFlow.destroy(); this._routeFlow = null }
      // 底图双模式：默认在线 OSM（EPSG:3857），离线/不可达时切回离线瓦片（EPSG:4326）。
      // 两套 crs 的索引规则不同，bounds/缩放约束也不同，全部由 _mapConfig 提供。
      const cfg = this._mapConfig()
      this._mapBounds = cfg.bounds
      // 离线 4326 模式才需要「铺满屏幕」的最小缩放锁（在线 3857 全球瓦片无此问题）
      const coverZoom = cfg.bounds ? this._minZoomForCoverage() : cfg.minZoom
      this.map = L.map(this.$refs.mapEl, {
        crs: cfg.crs,
        zoomControl: true,
        attributionControl: !!cfg.bounds, // 离线 4326 无 attribution（在线由瓦片层自带）
        // zoomSnap:0 → 捏合是连续分数级缩放，这是「高德那种丝滑」的关键，
        // 配合 Leaflet 的 CSS transform 缩放动画，手势期间图层整体平滑跟随。
        zoomSnap: 0,
        minZoom: coverZoom,
        maxZoom: cfg.maxZoom,
        // 关掉缩放动画，与调度大屏保持一致（大屏路线箭头能贴住地图，就靠这一条）。
        //
        // 背景：Leaflet 的动画缩放会给 mapPane 挂 CSS transform，从旧级别插值到新级别。
        // 期间任何 marker 定位都会被"再缩放一次"，routeFlow 每帧给箭头 setLatLng 时
        // 表现就是箭头跟不上缩放速度（已在 frame() 里按 map._animatingZoom 暂停写入，
        // 但整屏图层在 transform 动画中重栅格化仍会造成掉帧）。
        // 关掉动画后缩放置换，zoomend 紧随其后，箭头与路线一次性摆到正确位置。
        //
        // 代价：双指捏合不再有平滑过渡（会直接跳到位）。这是"箭头贴得住"与
        // "捏合丝滑"之间的取舍，当前按需求选了前者。
        // 若要两者兼得，下一步是把 preferCanvas 打开（长折线渲染快很多），
        // 但那会让 risk-blink 失效——它依赖 routeLine.getElement() 挂 CSS 类名，
        // canvas 渲染时返回 null，需先把红段闪烁改成 setStyle(opacity) 的 JS 驱动。
        zoomAnimation: false,
        ...(cfg.bounds ? { maxBounds: cfg.bounds, maxBoundsViscosity: 1.0 } : {}),
        bounceAtZoomLimits: false
      }).setView(cfg.center, Math.min(cfg.maxZoom, Math.ceil(coverZoom)))

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
      // 手势一结束（zoomend）立刻按新 zoom 重塑形 —— 不用 setTimeout 防抖。
      // 依据（已核对 Leaflet 源码）：捏合过程中 TouchZoom 走的是 map._move(center, zoom, {pinch:true})，
      // 只触发 zoom / move，**不会**触发 zoomend；手势结束才调一次 _animateZoom，
      // 其完成回调才触发一次 zoomend。所以这里不存在"手势中被高频调用"的问题，
      // 立即重绘是安全的，原来那个 140ms 防抖只是白白拖后了贴路几何的校正。
      this._zoomRedrawRaf = null
      const redrawOnZoom = () => {
        if (this._zoomRedrawRaf) return
        this._zoomRedrawRaf = requestAnimationFrame(() => {
          this._zoomRedrawRaf = null
          if (!this.map || !this.route || !this.route.pathCoords) return
          this.drawRoute()
        })
      }
      // 只监听 zoomend（与调度大屏一致）。
      // reshapeForZoom 的抽稀阈值只取决于 zoom：它在「地图像素」空间里算点到线段距离，
      // 而拖动会让所有点等量平移、距离结果不变，所以 moveend 无需重算。
      // 原来还挂着 moveend，每拖动一次就整条重建（含流光/箭头/风险标记），纯属浪费，
      // 也会和缩放重绘抢主线程，加剧「线跟不上」。
      this.map.on('zoomend', redrawOnZoom)

      // 底图瓦片层：默认在线 OSM；瓦片加载失败（离线/被墙/超时）累计后自动切回离线 4326 瓦片
      const layer = L.tileLayer(cfg.tileUrl, cfg.tileOpts)
      if (!this._useOfflineTiles) {
        this._osmFailCount = 0
        layer.on('tileerror', () => {
          this._osmFailCount++
          // 连续若干张瓦片失败才判定 OSM 不可达，避免个别丢包误切
          if (this._osmFailCount >= 6 && !this._useOfflineTiles) {
            console.warn('OSM 在线瓦片不可达，切换离线瓦片底图')
            this._useOfflineTiles = true
            this._rebuildWithOffline()
          }
        })
      }
      layer.addTo(this.map)

      // 原路线（灰虚线）在下，当前路线（蓝线）在上，风险段（红线）最上
      this.baseLine = L.polyline([], { color: '#9aa5b1', weight: 3, opacity: 0.75, dashArray: '6 6' }).addTo(this.map)
      this.routeLine = L.polyline([], { color: '#2563eb', weight: 5, opacity: 0.95 }).addTo(this.map)
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
      this.drawRoute()
    },
    /** 在线瓦片不可达 → 重建为离线 4326 底图（保留当前中心/缩放与路线） */
    _rebuildWithOffline() {
      const center = this.map ? this.map.getCenter() : null
      this._buildMap()
      if (center && this.map) {
        // 切到离线后缩放到覆盖范围内能容纳的级别
        const z = Math.min(13, Math.max(this.map.getMinZoom(), this.map.getZoom()))
        this.map.setView([center.lat, center.lng], z)
      }
      this.drawRoute()
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
    /** 启动时探测网络/OSM 可达性，决定初始底图（避免离线环境开局先闪一片灰） */
    async _detectTileSource() {
      // 浏览器报离线：直接用离线瓦片，不再尝试 OSM
      if (typeof navigator !== 'undefined' && navigator.onLine === false) {
        this._useOfflineTiles = true
        return
      }
      // 试拉一张 OSM 瓦片（no-cors，只要能返回就算可达）；超时/失败 → 离线瓦片
      try {
        const ctrl = new AbortController()
        const t = setTimeout(() => ctrl.abort(), 4000)
        await fetch('https://tile.openstreetmap.org/6/50/26.png', { mode: 'no-cors', cache: 'no-store', signal: ctrl.signal })
        clearTimeout(t)
        this._useOfflineTiles = false
      } catch (e) {
        this._useOfflineTiles = true
      }
      // 网络状态变化：恢复在线时下次建图优先 OSM；掉线时立即切离线
      window.addEventListener('online', () => { this._useOfflineTiles = false; if (this.map) this._buildMap() })
      window.addEventListener('offline', () => { this._useOfflineTiles = true; if (this.map) this._rebuildWithOffline() })
    },
    /**
     * 决定本次使用哪种底图坐标系配置。
     * 默认在线 OSM（EPSG:3857）；检测到离线 / OSM 不可达时用离线瓦片（EPSG:4326）。
     * 两套配置的 crs / 边界 / 缩放范围完全不同，必须在建图前确定。
     */
    _mapConfig() {
      if (this._useOfflineTiles) {
        return {
          crs: L.CRS.EPSG4326,
          tileUrl: '/tiles/{z}/{x}/{y}.jpg',
          tileOpts: { minZoom: 7, maxZoom: 13, minNativeZoom: 8, tileSize: 256 },
          bounds: L.latLngBounds([[20.49, 101.68], [24.01, 109.51]]),
          minZoom: null, // 由 _minZoomForCoverage 动态算（4326 铺满约束）
          maxZoom: 13,
          center: [21.6, 106.8]
        }
      }
      // 在线 OSM：3857，无需硬边界与铺满约束（全球瓦片，任意缩放都有图）
      return {
        crs: L.CRS.EPSG3857,
        tileUrl: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
        tileOpts: { minZoom: 6, maxZoom: 19, tileSize: 256, attribution: '© OpenStreetMap contributors' },
        bounds: null,
        minZoom: 6,
        maxZoom: 19,
        center: [21.6, 106.8]
      }
    },
    /**
     * 让底图「铺满屏幕」所需的最小缩放级别。
     * EPSG4326 下每度像素 = 512 * 2^zoom / 360（缩放 0 级为两块 256px 瓦片覆盖全球），
     * 只要视口换算成经纬度后不超过瓦片覆盖范围，屏幕上就不会出现灰白空白。
     */
    _minZoomForCoverage() {
      if (!this._mapBounds) return 7
      const el = this.$refs.mapEl
      // 地图已建好就用 Leaflet 的尺寸；还没建（初始化）就用容器实际像素尺寸
      const size = this.map ? this.map.getSize() : (el ? L.point(el.clientWidth, el.clientHeight) : null)
      if (!size || !size.x || !size.y) return 7
      const lngSpan = this._mapBounds.getEast() - this._mapBounds.getWest()
      const latSpan = this._mapBounds.getNorth() - this._mapBounds.getSouth()
      if (lngSpan <= 0 || latSpan <= 0) return 7
      // 每度像素需 >= max(视口宽 / 经度跨度, 视口高 / 纬度跨度)
      const needPxPerDeg = Math.max(size.x / lngSpan, size.y / latSpan)
      // 保留小数：分数级缩放下最小级别正好等于「刚好铺满」，多出的小数留给路线适配
      const z = Math.log2((needPxPerDeg * 360) / 512) + 0.003 // +0.003 兜掉浮点误差，避免边缘露一条灰缝
      return Math.min(13, Math.max(7, z))
    },
    /** 锁死最小缩放级别：缩小到会出现空白之前就停住，保证整张地图始终固定在屏幕上 */
    _applyCoverageZoom() {
      this._applyMapMinZoom()
    },
    /**
     * 计算「让整条路线完整落在可视区」所需的缩放级别。
     * 只统计顶部状态栏以下、底部留白以上的可视区（左右各留 22px）。
     */
    _routeFitZoom() {
      if (!this.map || !this.route.pathCoords || !this.route.pathCoords.length) return null
      const size = this.map.getSize()
      const availW = size.x - 44
      const availH = size.y - this._topOverlayPadding() - this._bottomOverlayPadding()
      if (availW <= 40 || availH <= 40) return null
      const b = L.latLngBounds(this.route.pathCoords)
      const lngSpan = b.getEast() - b.getWest()
      const latSpan = b.getNorth() - b.getSouth()
      if (lngSpan <= 0 || latSpan <= 0) return null
      // 每度像素 <= min(可用宽/经度跨度, 可用高/纬度跨度)，再换算成缩放级别
      const needPxPerDeg = Math.min(availW / lngSpan, availH / latSpan)
      const z = Math.log2((needPxPerDeg * 360) / 512)
      return Math.max(7, Math.min(13, z)) // 离线瓦片最低 z7，不再往下
    },
    /**
     * 统一设置地图最小缩放级别：
     * - 平时锁在「瓦片铺满屏幕」那一级（分数级，正好铺满、不留灰边），防止整张底图被拖出灰边；
     * - 预览时若整条路线比「铺满级别」看到的范围还大，则临时放宽到「刚好装下整条路线」那一级
     *   （分数级），保证路线完整可见。属于极少数情况，绝大多数时候不会触发。
     */
    _applyMapMinZoom() {
      if (!this.map) return
      // 在线 OSM（3857）无离线瓦片覆盖约束：minZoom 已由建图时 cfg.minZoom 设定，此处不再收紧
      if (!this._mapBounds) {
        if (this.previewing) {
          const rz = this._routeFitZoom()
          if (rz != null && this.map.options.minZoom !== Math.min(6, rz)) {
            // 预览时允许缩到能看全路线（不低于全球底图下限 6）
            this.map.setMinZoom(Math.max(6, Math.min(6, rz)))
          }
        }
        return
      }
      let floor = this._minZoomForCoverage()
      if (this.previewing) {
        const rz = this._routeFitZoom()
        if (rz != null) floor = Math.min(floor, rz)
      }
      if (this.map.options.minZoom !== floor) this.map.setMinZoom(floor)
    },
    // 用后端下发的 pathCoords 绘制（司机端不加载 35MB 路网）
    drawRoute() {
      if (!this.map || !this.route.pathCoords) return
      const z = (this.map.getZoom && this.map.getZoom()) || 8
      // 按当前 zoom 把路径几何重新塑形：远景加密（避免直线穿山）、近景抽稀（性能）
      const cur = reshapeForZoom(this.route.pathCoords || [], z, this.map)
      // 未绕行时基准线与当前线重合，baseLine 会被清空 —— 那就没必要塑形。
      // 原来无条件算一遍再丢弃，而 reshapeForZoom（Douglas-Peucker）在几千个点上并不便宜，
      // 每次重绘白跑一遍是缩放卡顿的一部分。
      const base = this.route.rerouted
        ? reshapeForZoom(this.route.baselinePathCoords || [], z, this.map)
        : []
      // 把塑形后的坐标回写，便于缩放/拖动事件复用
      this._displayCur = cur
      this._displayBase = base
      this.baseLine.setLatLngs(this.route.rerouted ? base : [])

      // 预览阶段候选只有聚合风险（hazardProbability / riskEdgeIds），
      // 无逐段风险可用时，整条路线标红闪烁，与右侧红色风险卡片呼应
      const previewRisky = this.previewing && !this.navigating && this.isHighRisk(this.selectedCandidate)
      const riskSet = this._riskEdgeSet()

      // 核心：按「风险 / 安全」把路径拆成连续多段 —— 风险段红、安全段绿
      const groups = this._buildColoredGroups(z, riskSet)
      const safeGroups = groups.filter(g => !g.risk).map(g => g.pts)
      const riskGroups = groups.filter(g => g.risk).map(g => g.pts)
      // 预览高风险但拿不到逐段风险时，整条按风险处理
      const wholeRisk = previewRisky && riskGroups.length === 0
      const safeDraw = wholeRisk ? [] : safeGroups
      const riskDraw = wholeRisk ? [cur] : riskGroups

      // 底层静态线：作为 Flow 特效之外的兜底，配色与 Flow 保持一致
      const baseWidth = this.navigating ? 7 : 5
      this.routeLine.setStyle({ color: '#2563eb', weight: baseWidth, opacity: 1, dashArray: null })
      this.routeLine.setLatLngs(safeDraw)
      // 风险段：Flow 特效层（markerPane）更高，会把这里细红线整条盖住，
      // 因此用更宽的半透明红色脉冲光晕，让风险段在地图上一眼可辨
      this.riskLine.setStyle({ color: '#e53935', weight: baseWidth + 9, opacity: 0.45, dashArray: null })
      this.riskLine.setLatLngs(riskDraw)
      // Leaflet 的 setStyle 不处理 className，闪烁类名需直接作用到 SVG 元素上
      const routeEl = this.routeLine.getElement && this.routeLine.getElement()
      if (routeEl) {
        if (wholeRisk) L.DomUtil.addClass(routeEl, 'risk-blink')
        else L.DomUtil.removeClass(routeEl, 'risk-blink')
      }
      const riskEl = this.riskLine.getElement && this.riskLine.getElement()
      if (riskEl) {
        if (riskDraw.length) L.DomUtil.addClass(riskEl, 'risk-blink')
        else L.DomUtil.removeClass(riskEl, 'risk-blink')
      }

      // 前进箭头 + 流光带（外发光 + 流光 + 核心线 + 箭头 + 起终点脉冲）
      // 特效层在 markerPane（600）之上，必须按段着色，否则单色特效会盖住红线
      if (cur.length > 1) {
        const segs = []
        if (!wholeRisk) groups.forEach(g => segs.push({ latlngs: g.pts, color: g.risk ? '#e94560' : '#2563eb' }))
        if (wholeRisk || !segs.length) segs.push({ latlngs: cur, color: '#e94560' })
        if (!this._routeFlow) this._routeFlow = createRouteFlow(this.map, { color: '#2563eb' })
        this._routeFlow.setSegments(segs)
      } else if (this._routeFlow) {
        this._routeFlow.setSegments([])
      }

      // 风险段额外加发光标记（采样打点，避免密集几何生成上千个 marker）
      this._updateRiskMarkers(riskDraw)
    },
    /** 当前路径上命中风险的边 ID 集合（导航用 riskSegments，预览用候选自带 riskEdgeIds） */
    _riskEdgeSet() {
      return riskEdgeSet(this.route.riskSegments, this.route.riskEdgeIds)
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
      if (!navigator.geolocation) {
        this.showPush('info', '定位', '当前设备不支持定位功能')
        return
      }
      navigator.geolocation.getCurrentPosition(
        pos => {
          const lat = pos.coords.latitude
          const lng = pos.coords.longitude
          if (!this.map) return
          // 定位即回到导航视角（放大 + 恢复跟随）
          if (this.navigating) {
            this.navFollowing = true
            this.navOffView = false
          }
          this.map.setView([lat, lng], this.navigating ? this._navMaxZoom() : 12, { animate: true })
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
      if (this._meMarker) {
        try { this.map.removeLayer(this._meMarker) } catch (e) {}
        this._meMarker = null
      }
      const icon = L.divIcon({
        className: 'nav-tri-marker',
        // 「当前位置」标签直接画进图标 HTML，**不用 permanent Tooltip**。
        // Leaflet 的 Tooltip._updatePosition（DivOverlay 基类）没有 `!this._map` 守卫，
        // 标记被移除/重建的瞬间它若仍在监听 zoom，setView 派发事件时就会抛
        // "Cannot read properties of null (reading 'latLngToLayerPoint')"，
        // 进而中断 Leaflet 的 _resetView → moveend 不触发 → 渲染器 _zoom 变陈旧
        // → 路线线宽被放大成巨大色块（严重时白屏）。
        html: '<div class="nav-tri-label">当前位置</div><div class="nav-tri-arrow"></div><div class="nav-tri-dot"></div>',
        iconSize: [36, 42],
        iconAnchor: [18, 21]
      })
      this._meMarker = L.marker([lat, lng], {
        icon,
        zIndexOffset: 1000,
        interactive: false
      }).addTo(this.map)
    },
    /** 开启 GPS 实时定位，Capacitor 原生定位优先，降级浏览器 geolocation，再降级模拟行驶 */
    _startRealTimeGPS() {
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
            // ① 模拟行驶已在跑：车辆位置以路线为准，定位结果不再移动车辆/视野
            if (this._triTimer) return
            // ② 模拟没跑时（非导航态）仍做合理性校验，避免代理定位到国外把视野拽走
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
          // ① 模拟行驶已在跑：车辆位置以路线为准，定位结果不再移动车辆/视野
          if (this._triTimer) return
          // ② 模拟没跑时（非导航态）仍做合理性校验，避免代理定位到国外把视野拽走
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
      this._triTimer = setInterval(() => {
        if (!this.navigating) { this._stopTripSimulation(); return }
        this._simDistM = Math.min(this._simDistM + SIM_SPEED_MPS * SIM_TICK_MS / 1000, this._simTotalM)
        const p = this._simPointAt(this._simDistM)
        if (p && this._meMarker) {
          this._meMarker.setLatLng(p)
          this._followVehicle(p)
        }
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
    },
    fitRoute(silent) {
      // 行驶中点「全览路线」＝主动接管视角，暂停跟随并给出回到导航视角入口
      // silent=true 用于导航开场自动全览，不算用户接管，保持跟随状态
      if (this.navigating && silent !== true) this._pauseFollow()
      if (!this.map || !this.route.pathCoords || !this.route.pathCoords.length) return
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
    /** 导航视角缩放级别：直接拉到当前底图最大级（在线 OSM z19 / 离线瓦片 z13），公路细节清晰可见 */
    _navMaxZoom() {
      return this.map ? this.map.getMaxZoom() : this.navZoom
    },
    /** 进入导航视角：自动放大到车辆当前位置（对齐高德/百度「点开始导航即放大跟随」）*/
    zoomToNav() {
      if (!this.map) return
      // 开场视角以**路线起点**为准（模拟行驶也正是从起点出发），
      // 不再优先取 _meMarker：它可能已被真实定位带偏 —— 开了代理时浏览器定位基于出口 IP，
      // 会报到国外（实测报到日本），于是开场就把地图拽走。
      // 取路线起点后，场面先落在路线头上，随后再由 _followVehicle 跟随车辆前进。
      const c = (this.route.pathCoords && this.route.pathCoords[0]) || null
      const p = c ? L.latLng(c[0], c[1]) : (this._meMarker ? this._meMarker.getLatLng() : null)
      if (p) {
        this.navFollowing = true
        this.navOffView = false
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
    /** 车辆位置更新时的跟随（已退出导航或处于自由视角时不打扰地图）*/
    _followVehicle(pt) {
      if (!this.map || !this.navigating || !this.navFollowing || !this._navZoomReady) return
      // 保持当前缩放级别（用户可自由放大缩小），只做偏移平移 → 路线稳定居中
      this._centerOn(pt, this.map.getZoom(), 0.6)
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
      this.previewing = false
      this.previewCollapsed = false
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
      // 清空语音队列并打断当前播报
      this._cancelVoice()
      this._voiceQueue.length = 0
      this._voiceCooldown = 0
      this._lastVoiceText = ''
      this._lastVoiceAt = 0
      // 重置灾害播报去重：下一次行程遇到同类灾害仍要正常播报
      this._hazardVoiceKey = ''
      this._hazardVoiceAt = 0
      this._stopRealTimeGPS()
      // 结束本次行程：停止关注这条路线，避免后续无关风险推送打扰（重新规划时会再次注册）
      axios.post('/api/agent/unregister', null, {
        params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId }
      }).catch(() => {})
      if (this.map) this.$nextTick(() => this.map.invalidateSize())
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
    // 进入全屏大地图预览（选择路线这一步）；此时还未开始导航，不启动 GPS/Agent
    async enterPreview() {
      if (this.navigating) return
      this.showNavMenu = false
      this.previewing = true
      this.previewCollapsed = false
      await this.$nextTick()
      this.initMap()
      // 进入预览即按新视口（地图在上、面板在下）适配整条路线
      this.$nextTick(() => { this.fitRoute(false) })
    },
    // 预览页返回首页：保留已搜索的候选路线，方便再次进入预览
    exitPreview() {
      this.previewing = false
      this.previewCollapsed = false
      this.showNavMenu = false
      this.pushMsg = ''
      // 退出预览后恢复「铺满屏幕」的最小缩放，避免留下预览时放宽的级别
      if (this.map) this.$nextTick(() => { this.map.invalidateSize(); this._applyCoverageZoom() })
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
      this.candidateLoading = true
      try {
        const r = await axios.get('/api/route/candidates', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold' },
          timeout: 30000
        })
        // 后端按边相似度去重，同一走廊的两种走法仍可能返回两条（末端口岸支线不同）；
        // 这里按「途经」指纹再合并一次，杜绝"同一条路两张卡、红绿自相矛盾"
        this.candidates = this._mergeCorridorDuplicates((r.data && r.data.candidates) || [])
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
        this.showPush('success', '路线规划完成',
          `为您找到 ${this.candidates.length} 条可选路线`
          + (safest ? `，最安全的是「${safest.label}」${this.topHazardName(safest)}概率 ${safest.hazardProbability}%` : '')
          + '，请在地图上点选一条后开始导航')
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
      this.selectedKey = key
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
      })
    },
    // 3) 按司机所选路线开始导航（choice 贯穿导航轮询，路线不再被系统推荐强制替换）
    async startNavigation() {
      if (this.routeLoading || this.candidateLoading) return
      if (!this.candidates.length) {
        await this.searchRoutes()
        if (!this.candidates.length) return
      }
      this._warmUpSpeech()
      // 司机自行规划出发 = 非调度行程，清掉方案标记，
      // 否则上一单是水运（B）时会把"经平陆运河"残留到这一次播报里
      this.activePlanId = null
      this.routeLoading = true
      try {
        // 注册为 Agent 活跃任务（灾害触发时自动重算并推送）
        axios.post('/api/agent/register', null, { params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId } }).catch(() => {})
        // 行程启动信号：调度大屏据此自动触发一次 AI 六维分析（常态方案对比），
        // 由调度员人工确认路线后再下发任务指令给司机
        axios.post('/api/trip/start', null, { params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId } }).catch(() => {})
        const plan = await axios.get('/api/route/plan-with-weather', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold', choice: this.selectedKey },
          timeout: 30000
        })
        const resp = plan.data.route || plan.data
        this.route = resp
        this.online = true
        this.warning = plan.data.aiWarning || plan.data.warning || ''
        this.risks = plan.data.risks || this.risks
        if (resp.pathNodeIds && resp.pathNodeIds.length) {
          const via = this._viaFromNodes(resp.pathNodeIds)
          if (via) this.viaText = via
        }
        this.checkPush(resp)
        this.navigating = true
        this.previewing = false
        this.previewCollapsed = false
        this.navFollowing = true
        this.navOffView = false
        this.showNavMenu = false
        await this.$nextTick()
        // 预览阶段已用同一地图容器建好地图，这里不重建，避免闪屏
        if (!this.map) this.initMap()
        // 由预览切到导航：恢复「铺满屏幕」的最小缩放（预览时可能为适配路线放宽过）
        this._applyCoverageZoom()
        // 开启 GPS 实时定位（演示环境 GPS 不可用时自动降级模拟行驶）
        this._startRealTimeGPS()
        // 开场视角：直接落到模拟起点（不再"先全览再放大"，那个开场会让视角显得乱跳）
        this._navZoomReady = false
        this.$nextTick(() => {
          // 由预览切到导航：面板从「上下分栏」变回浮层，地图区域随之变大，
          // 先让 Leaflet 重新量测尺寸再定位，否则会按旧尺寸算出错误视野。
          if (this.map) this.map.invalidateSize()
          // 进入导航时重置居中偏移，让本次只重新量测一次（之后导航中冻结，避免漂移）
          this._navCenterOffset = null
          // 保留原来的「先全览 → 再放大到起点跟随」节奏；延迟从 1100ms 缩到 420ms，
          // 既有放大进入的观感，又不至于让人以为视角在乱跳。
          this.fitRoute(true)
          // 大幅视图变化后强制重投影：避免渲染器残留 scale / _bounds 偏差（线被放大成色块）
          this._resyncMapRenderer()
          this._navZoomTimer = setTimeout(() => {
            this._navZoomTimer = null
            this.zoomToNav()          // 放大到「模拟起点」（路线首点）
          }, 420)
        })
        // 导航开始后自动拉取途径城市天气
        this.loadWeatherForRoute().catch(() => {})
        this.showPush('success', '导航已开始', `路线：${this.routeTitle}，预计 ${resp.estimatedHours || '--'} 小时，Agent 已实时守护`)
        // 出发语音播报：中文 + 越南语（只读一次，不重复）
        this._announceDeparture()
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
          this.route = { ...this.route, ...resp }
          this.navigating = true
          this.previewing = false
          this.previewCollapsed = false
          this.navFollowing = true
          this.navOffView = false
          this.showNavMenu = false
          await this.$nextTick()
          if (!this.map) this.initMap()
          this._applyCoverageZoom()
          this._startRealTimeGPS()
          this.showPush('success', '离线导航模式', `已加载演示路线，预计 ${resp.estimatedHours || '--'} 小时`)
          this.$nextTick(() => {
            if (this.map) this.map.invalidateSize()
            this.fitRoute(true)
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
      // 已选好这条线（含未开始导航）→ 注册关注，风险注入后该路线会被重算推送
      axios.post('/api/agent/register', null, {
        params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId }
      }).catch(() => {})
      // withAi=false：本方法不消费 aiWarning，而 AI 文案要秒级生成，
      // 它同时挂在 15 秒轮询和灾害触发的重取路径上，开着会让路线更新明显滞后。
      const plan = await axios.get('/api/route/plan-with-weather', {
        params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold', choice: this.selectedKey, withAi: false },
        timeout: 30000
      })
      const resp = plan.data.route || plan.data
      this.route = resp
      this.online = true
      this.risks = plan.data.risks || this.risks
      if (resp.pathNodeIds && resp.pathNodeIds.length) {
        const via = this._viaFromNodes(resp.pathNodeIds)
        if (via) this.viaText = via
      }
      this.checkPush(resp)
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
      this.previewing = false
      this.previewCollapsed = false
      this.navFollowing = true
      this.navOffView = false
      this.candidates = []
      this.selectedKey = 'recommended'
      this.viaText = ''
      this.route = {}
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
        // 灾害真的发生并触发绕行：语音播报具体灾害 + 弹出替代路线供司机点选
        const reason = segs.length ? segs[segs.length - 1].reason : '路段风险'
        this.showPush('success', '路线已绕行',
          `${reason}，已切换绕行路线，预计延误 ${r.extraHours}h。请按新路线行驶，或点选其他方案。`)
        this.loadRerouteOptions(reason).catch(() => {})
        this.lastRerouted = r.rerouted
        this.lastRiskCount = segs.length
        return true
      }
      if (segs.length > this.lastRiskCount) {
        // 新增风险（尚未绕行）：同样播报并提供替代方案
        const reason = segs[segs.length - 1].reason
        this.showPush('danger', '新增气象风险', `${reason}。正在为您规划替代路线…`)
        this.loadRerouteOptions(reason).catch(() => {})
        this.lastRerouted = r.rerouted
        this.lastRiskCount = segs.length
        return true
      }
      this.lastRerouted = r.rerouted
      this.lastRiskCount = segs.length
      return false
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
          // 未开始导航但已选好这条线（或目的地是越南端）：风险变化要立即预警并静默刷新路线，
          // 让司机在上路前就知道前方有灾、路线已调整；只是没强提醒打断。
          if (!this.navigating) {
            this._watchPreviewRisk(data)
            return
          }
          // 灾害必须**先提示**：下面的分支（局部绕行 / 重新拉取路线）都要多一次 HTTP 往返，
          // 其中 fetchRoute 走 /api/route/plan-with-weather，注入风险后实测 4 秒以上。
          // 等它回来才提示，司机端会明显滞后于大屏（后端 SSE 只需 ~350ms）。
          this._notifyHazard(data)
          if (!data.route) {
            // 硬熔断：所有路线均不可通行，红色告知司机停车等待调度指令
            this.showPush('danger', '无可用路径', data.advice || '所有路线均不可通行，请停车等待调度指令。')
            return
          }
          // 司机选了备选/最快路线：Agent 推送（系统推荐线）不强制覆盖其选择，
          // 提示后按司机所选走廊重新拉取（自动避开新增风险）
          if (this.navigating && this.selectedKey && this.selectedKey !== 'recommended') {
            this.showPush('info', 'Agent 实时更新',
              (data.advice || '已按最新风险重新评估，正在刷新您所选的路线…') +
              `（${new Date(data.ts || Date.now()).toLocaleTimeString('zh-CN', { hour12: false })}）`)
            this.fetchRoute().catch(() => {})
            return
          }
          // 尝试局部绕行：有 GPS + 有灾害边 ID + 有当前路线 → 调 /api/route/detour
          if (data.hazardEdgeIds && data.hazardEdgeIds.length > 0
              && this.route && this.route.pathEdgeIds && this.route.pathEdgeIds.length > 0) {
            this._tryDetour(data).catch(() => {
              this._applyAgentRoute(data)
            })
            return
          }
          this._applyAgentRoute(data)
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
        if (isNew && !driverTarget.confirmed) {
          // 新任务变更：震动 + 语音播报 + 弹出提醒，并切到任务 tab
          this._notifyTaskChange(data)
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
      }
    },
    /** 司机确认接单：大屏派单面板实时由"待接单"变为"已接单" */
    async acceptTask() {
      if (!this.task || this.task.status === 'ACCEPTED' || this.taskAccepting) return
      this.taskAccepting = true
      try {
        const { data } = await axios.post('/api/task/accept', {
          taskId: this.task.taskId, driverName: this.driverName
        })
        if (data && data.task) this.task = data.task
        this.showPush('ok', '已接单', `${this.task.taskId} 已接单，调度中心已收到回执`)
      } catch (e) {
        console.error('accept task failed', e)
      } finally {
        this.taskAccepting = false
      }
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
      this.taskConfirming = true
      try {
        await axios.post('/api/outreach/confirm', { targetId: this.taskChange.id })
        this.taskChange.confirmed = true
        this.showPush('ok', '已确认', '任务变更已确认接收，正在自动切换到调度路线导航…')
        const planId = this.taskChange.planId
        // 先按方案切换目的地并重算路线（B=港区 / A=公路绕行 / C=仅提示等待）
        await this._switchNavForPlan(planId)
        // 然后直接按调度路线进入导航（不再要求司机手动点"开始导航"）
        if (planId !== 'C') this._autoStartDispatchNav(planId)
      } catch (e) {
        console.error('confirm task failed', e)
      } finally {
        this.taskConfirming = false
      }
    },
    /**
     * 按调度方案联动导航（双向切换闭环）：
     * B=公水联运 → 导航目标切到南宁港六景作业区（路网节点 LJ，南宁以东郁江畔）；
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
      // 首次切方案前记住司机原行程目的地，供 B→A 改派时恢复
      if (!this._planSavedDestination) this._planSavedDestination = this.destinationId
      if (planId === 'C') {
        this.showPush('info', '调度指令', '双线风险，请就近停靠安全区域等待，保持冷链机组运行。')
        return
      }
      const target = planId === 'B' ? 'LJ' : this._planSavedDestination
      this.destinationId = target
      try {
        // 重新注册 Agent 活跃任务（目的地已变，守护目标同步切换）
        axios.post('/api/agent/register', null, {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId }
        }).catch(() => {})
        const plan = await axios.get('/api/route/plan-with-weather', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold', withAi: false },
          timeout: 30000
        })
        const resp = plan.data.route || plan.data
        this.route = resp // watcher 自动重绘地图
        this.risks = plan.data.risks || this.risks
        this.online = true
        this._syncTripProgress()
        if (resp.pathNodeIds && resp.pathNodeIds.length) {
          const via = this._viaFromNodes(resp.pathNodeIds)
          if (via) this.viaText = via
        }
        this.$nextTick(() => { if (this.map && resp.pathCoords) this.fitRoute(false) })
        if (planId === 'B') {
          this.showPush('info', '导航已更新',
            '公水联运（平陆运河）：请按新路线前往南宁港六景作业区交接车辆，之后随船经平陆运河转海运至越南海防港。')
          this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text: '导航目标已切换：南宁港六景作业区' })
        } else {
          this.showPush('info', '导航已更新', `公路方案：新路线已下发（${this.routeTitle}），按导航行驶。`)
          this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text: '已切换公路绕行方案，路线已重算' })
        }
      } catch (e) {
        console.error('plan nav switch failed', e)
        this.showPush('warn', '路线更新失败', '网络异常，请稍后手动重新规划。')
      }
    },
    /**
     * 确认调度指令后自动进入导航：复用 _switchNavForPlan 已重算好的 this.route，
     * 跳过候选路线搜索，直接进入跟随视角（全览 → 拉满缩放跟随车辆）。
     */
    async _autoStartDispatchNav(planId) {
      if (!this.route || !this.route.pathCoords || !this.route.pathCoords.length) return
      try {
        this._warmUpSpeech()
        // 选中推荐路线贯穿导航轮询（调度路线即推荐路线）
        this.selectedKey = 'recommended'
        this.candidates = [{
          key: 'recommended',
          coords: this.route.pathCoords,
          edgeIds: this.route.pathEdgeIds || [],
          edgeSpans: this.route.pathEdgeSpans || [],
          label: planId === 'B' ? '公水联运（调度路线）' : '公路方案（调度路线）'
        }]
        this.navigating = true
        this.previewing = false
        this.previewCollapsed = false
        this.navFollowing = true
        this.navOffView = false
        this.showNavMenu = false
        await this.$nextTick()
        if (!this.map) this.initMap()
        this._applyCoverageZoom()
        this._startRealTimeGPS()
        this._navZoomReady = false
        this.$nextTick(() => {
          if (this.map) this.map.invalidateSize()
          // 进入导航时重置居中偏移，让本次只重新量测一次（之后导航中冻结，避免漂移）
          this._navCenterOffset = null
          // 保留原来的「先全览 → 再放大到起点跟随」节奏；延迟 1100ms → 420ms
          this.fitRoute(true)
          // 大幅视图变化后强制重投影：避免渲染器残留 scale / _bounds 偏差（线被放大成色块）
          this._resyncMapRenderer()
          this._navZoomTimer = setTimeout(() => {
            this._navZoomTimer = null
            this.zoomToNav()          // 放大到「模拟起点」（路线首点）
          }, 420)
        })
        this.loadWeatherForRoute().catch(() => {})
        this.showPush('success', '已按调度路线导航',
          planId === 'B' ? '目标：南宁港六景作业区（公水联运·平陆运河），Agent 已实时守护' : `路线：${this.routeTitle}，Agent 已实时守护`)
        this._announceDeparture()
        if (this.riskTimer) clearInterval(this.riskTimer)
        this.riskTimer = setInterval(() => { this.refreshForecast(false) }, 480000)
        this.pushLog.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
          text: planId === 'B' ? '已确认调度指令，自动导航至南宁港六景作业区（公水联运·平陆运河）' : '已确认调度指令，自动导航公路绕行路线' })
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
     * 收到灾害推送时**立刻**提示，不等后续「局部绕行 / 重新拉取路线」返回。
     * 按"当前灾害边集合"去重：同一批灾害只提醒一次，避免 SSE 重复推送刷屏；
     * 风险清空后重置去重键，这样同一场景关掉再打开能再次提醒。
     */
    _notifyHazard(data) {
      const ids = (data.hazardEdgeIds || []).slice().sort()
      if (!ids.length) {
        this._hazardNotifyKey = ''
        return
      }
      const key = ids.join(',')
      if (key === this._hazardNotifyKey) return
      this._hazardNotifyKey = key
      const risks = data.risks || []
      const reason = risks.length ? risks[risks.length - 1].reason : '检测到路段气象风险'
      this.showPush('danger', '⚠ 前方灾害', `${reason}。正在为您重新规划路线…`)
      this._hazardNotifiedAt = Date.now()
      // 强提醒：司机在路上主要靠听，震动 + 语音与任务变更提醒保持同一套处理
      if (navigator.vibrate) navigator.vibrate([200, 100, 200])
      if (this.voiceOn) {
        const speak = reason.length > 50 ? reason.slice(0, 50) : reason
        this._interruptAndSpeak([`气象预警。${speak}`])
      }
    },
    /**
     * 未开始导航（已选好线 / 目的地越南端）收到风险更新的处理：
     * 顶部给一个"前方路段出现风险"预警（不打断），并把已规划好的路线静默刷新为绕开风险的新路线，
     * 让司机上路前就知道路线已调整。风险清空后自动撤下预警。
     */
    _watchPreviewRisk(data) {
      // 没规划过路线（纯粹停在首页/导航页没选目的地）→ 不打扰
      if (!this.route || !this.route.pathCoords || !this.route.pathCoords.length) return
      const hasRisk = !!(data.risks && data.risks.length) || !!(data.hazardEdgeIds && data.hazardEdgeIds.length)
      if (hasRisk && data.route && data.route.pathCoords) {
        const reason = (data.risks && data.risks.length ? data.risks[data.risks.length - 1].reason : '') || '沿线出现气象风险'
        this.route = data.route
        this.risks = data.risks || []
        this._syncTripProgress()
        this.showPush('warning', '⚠ 前方路段风险', `${reason}。您规划的路线已重新计算（绕开风险段），出发前请查看。`)
      } else if (!hasRisk) {
        // 风险清空：静默刷新为常态路线，撤下预警
        if (data.route && data.route.pathCoords) {
          this.route = data.route
          this.risks = []
          this._syncTripProgress()
        }
      }
    },
    /** 将 Agent 推送的全程重算路线应用到当前导航（回退逻辑） */
    _applyAgentRoute(data) {
      this.route = data.route
      this.risks = data.risks || []
      this.online = true
      this._syncTripProgress()
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
      if (!pos) { this._triIdx = 0; return }
      let minDist = Infinity
      let minIdx = 0
      for (let i = 0; i < coords.length; i++) {
        const c = coords[i]
        const d = Math.hypot(c[0] - pos.lat, c[1] - pos.lng)
        if (d < minDist) { minDist = d; minIdx = i }
      }
      this._triIdx = minIdx
      // 换路线后重新标定模拟里程：累计里程表是按旧坐标数组算的，
      // 与新路线长度不一致会导致 _simPointAt 取到错误位置（车辆瞬间跳走）。
      const cum = [0]
      for (let i = 1; i < coords.length; i++) {
        cum.push(cum[i - 1] + this._distM(coords[i - 1], coords[i]))
      }
      this._simCum = cum
      this._simTotalM = cum[cum.length - 1]
      this._simDistM = cum[minIdx] || 0
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
      this.route = detour
      this.risks = r.data.risks || data.risks || []
      this.online = true
      this._syncTripProgress()
      const delayInfo = detour.extraHours > 0 ? '（延误 ' + detour.extraHours.toFixed(1) + 'h）' : ''
      const ts = new Date(data.ts || Date.now()).toLocaleTimeString('zh-CN', { hour12: false })
      // 类型必须是 CSS 里定义过的（info/success/warn/danger）；
      // 原来写的 'warning' 没有对应样式，提示会渲染成一条没有底色的白条，几乎看不见
      this.showPush(detour.rerouted ? 'warn' : 'info', '局部绕行避灾',
        `⚠ 前方灾害，已从当前位置局部绕行${delayInfo}（${ts}）`)
    },
    /** 根据 GPS 位置计算在路线上的进度比例 (0~1) */
    _computeProgressRatio(lat, lng) {
      if (!this.route || !this.route.pathCoords || !this.route.pathCoords.length) return 0.3
      const coords = this.route.pathCoords
      let minDist = Infinity
      let minIdx = 0
      for (let i = 0; i < coords.length; i++) {
        const c = coords[i]
        if (!c || c.lat == null || c.lon == null) continue
        const d = Math.hypot(c.lat - lat, c.lon - lng)
        if (d < minDist) { minDist = d; minIdx = i }
      }
      return Math.min(0.95, Math.max(0.05, minIdx / Math.max(1, coords.length - 1)))
    },
    fmtYuan(v) {
      return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
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
     * @param opts  force=true 无视高优先级占用强行播报；lock=true 本条为高优先级，
     *              占用期间别的播报既不能抢占、也不入队（导航开始播报用）
     */
    speakQueue(texts, opts) {
      if (!this.voiceOn) { this._vdbg('拦截：语音已关闭(voiceOn=false)'); return }
      const o = opts || {}
      // 高优先级播报（导航开始）正在念：其它播报一律让路
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
      // 记下取消的 Promise，起播前 await —— 保证「旧的停干净了」再开口，
      // 否则原生 stop() 与新 speak() 竞态，新播报会被掐掉。
      this._cancelPromise = this._cancelVoice()
      this._voiceQueue.length = 0
      this._voiceQueue.push(list)
      // 高优先级：锁住语音通道，直到本条念完（或超时兜底）
      let lockSec = 0
      if (o.lock) {
        lockSec = Math.round(this._estimateSpeechMs(list) / 1000)
        this._lockVoice(this._estimateSpeechMs(list))
      }
      this._vdbg('入队 ' + list.length + ' 条' + (lockSec ? '（上锁 ' + lockSec + 's）' : '')
        + '｜首条：' + String(list[0]).slice(0, 18))
      this._flushVoice(true)
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
     * 锁定语音通道：期间 speakQueue 的普通播报一律丢弃（导航开始播报专用）。
     * 带超时兜底——若 onend/onerror 都没回来（WebView 上偶发），到点自动解锁，
     * 避免通道被永久占死导致此后再无语音。
     */
    _lockVoice(ms) {
      this._voiceLocked = true
      if (this._voiceLockTimer) clearTimeout(this._voiceLockTimer)
      this._voiceLockTimer = setTimeout(() => {
        this._voiceLockTimer = null
        this._voiceLocked = false
      }, ms)
    },
    /** 解锁语音通道（播报链条正常念完时调用） */
    _unlockVoice() {
      this._voiceLocked = false
      if (this._voiceLockTimer) { clearTimeout(this._voiceLockTimer); this._voiceLockTimer = null }
    },

    /**
     * 取消当前正在进行的播报：清掉计时器、作废旧播报链路、停掉 web speech 与原生 TTS。
     * 递增 _voiceSeq 后，旧链路里所有 onend / onerror / 降级回调都会自行退出，不会续播旧内容。
     *
     * 返回 Promise：调用方要 await 它，确认"旧的真的停了"再起播新的。
     * 原实现把原生 stop() 发出去就不管，与新的 speak() 竞态 —— 这是播报乱的主因。
     */
    _cancelVoice() {
      if (this._voiceTimer) { clearTimeout(this._voiceTimer); this._voiceTimer = null }
      this._voiceSeq += 1
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

    /** immediate=true：不等冷却，立刻起播（抢占场景）。留 80ms 让浏览器 cancel 生效，否则新语音会被吞掉 */
    _flushVoice(immediate) {
      if (this._voiceTimer) return // 正在播报或等待
      const since = Date.now() - this._voiceCooldown
      // 抢占时不再靠固定延时（原来 80ms），改为起播前等引擎真正空闲，见下
      const delay = immediate ? 0 : Math.max(0, 1500 - since)
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
      }, delay)
    },
    _doSpeak(texts, i, seq) {
      if (seq !== this._voiceSeq) return // 已被抢占，整条旧播报链路作废
      if (i >= texts.length) {
        this._voiceCooldown = Date.now()
        // 整条念完：放开语音通道（未上锁时是空操作），高优先级播报到此结束
        this._unlockVoice()
        if (this._voiceQueue.length) {
          this._voiceTimer = setTimeout(() => {
            this._voiceTimer = null
            this._flushVoice()
          }, 1500)
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
        this._capacitorSpeak(clean, lang).then(ok => {
          if (seq !== this._voiceSeq) return
          if (ok) next()
          else this._speakWeb(clean, lang, next)   // 原生也不可用 → 退回 Web
        })
        return
      }
      const syn0 = window.speechSynthesis
      this._vdbg('第' + (i + 1) + '/' + texts.length + '条 → Web：synth=' + (syn0 ? '有' : '无')
        + ' speaking=' + (syn0 ? syn0.speaking : '-')
        + ' pending=' + (syn0 ? syn0.pending : '-')
        + ' voices=' + (syn0 && syn0.getVoices ? syn0.getVoices().length : '-'))
      this._speakWeb(clean, lang, next)
    },
    /** 是否运行在 Capacitor 原生容器内（普通浏览器里 isNativePlatform() 返回 false） */
    _isNativeShell() {
      const c = window.Capacitor
      return !!(c && typeof c.isNativePlatform === 'function' && c.isNativePlatform())
    },
    /** Web Speech 播报一条；不可用或失败时退回原生 TTS */
    async _speakWeb(clean, lang, next) {
      const synth = window.speechSynthesis
      if (!synth) {
        this._vdbg('无 speechSynthesis → 退回原生')
        this._capacitorSpeak(clean, lang).then(ok => { if (ok) next() })
        return
      }
      // Chrome 的 getVoices() 是异步填充的，首次（或没有可用语音时）返回空数组 ——
      // 此时 speak() 找不到匹配语音会**静默无声且不报错**。先等语音列表就绪。
      await this._waitVoicesReady(1500)
      if (synth.paused) synth.resume()
      const u = new SpeechSynthesisUtterance(clean)
      u.lang = lang
      u.rate = 0.95
      u.volume = 1
      // onstart 是关键诊断点：引擎被静默吞掉时它永不触发（Android WebView 的典型表现）
      u.onstart = () => this._vdbg('Web 已开始发声')
      u.onend = () => { this._vdbg('Web 播完'); next() }
      // 被抢占时（cancel 触发的 canceled/interrupted）next() 内部会因 seq 不符自行作废
      u.onerror = (e) => {
        this._vdbg('Web 出错：' + ((e && e.error) || '未知') + ' → 退回原生')
        this._capacitorSpeak(clean, lang).then(ok => { if (ok) next() })
      }
      synth.speak(u)
      this._vdbg('已调用 synth.speak()（voices=' + (synth.getVoices ? synth.getVoices().length : '-') + '）')
    },
    /**
     * 等 Web Speech 的语音列表就绪。
     * Chrome 里 getVoices() 首帧常为空，需等 voiceschanged；等不到就超时放行（不做无限等待）。
     */
    _waitVoicesReady(maxMs) {
      const synth = window.speechSynthesis
      if (!synth || !synth.getVoices || synth.getVoices().length) return Promise.resolve()
      const limit = maxMs || 1500
      const t0 = Date.now()
      return new Promise(resolve => {
        const done = () => { try { synth.removeEventListener('voiceschanged', done) } catch (e) {} resolve() }
        try { synth.addEventListener('voiceschanged', done) } catch (e) {}
        const tick = () => {
          if (synth.getVoices().length || Date.now() - t0 >= limit) return done()
          setTimeout(tick, 100)
        }
        tick()
      })
    },
    /** Capacitor 原生 TTS（Android/iOS WebView 语音播报）。返回是否成功，供上层决定要不要退回 Web */
    async _capacitorSpeak(text, lang) {
      try {
        // 用缓存的模块实例（与 _stopNativeTts 同一个），保证 stop/speak 的调用顺序
        const m = await loadNativeTts()
        if (!m || !m.TextToSpeech) return false
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
     */
    _interruptAndSpeak(texts) {
      if (!this.voiceOn) return
      this._lastVoiceText = ''
      this._lastVoiceAt = 0
      this.speakQueue(texts)
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
        const u = new SpeechSynthesisUtterance('')
        u.volume = 0
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
      const txt = String(reason || '')
      if (/暴雨|强降雨|特大暴雨/.test(txt)) return '暴雨'
      if (/塌方|滑坡|泥石流/.test(txt)) return '塌方'
      if (/台风|大风|风暴/.test(txt)) return '大风'
      if (/大雾|低能见度|能见度/.test(txt)) return '大雾'
      if (/高温|热浪/.test(txt)) return '高温'
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
    _buildStatusZh() {
      const r = this.route
      const parts = []
      // 运输方式：水运方案要点明「平陆运河」。状态播报同时用于出发播报和灾害播报，
      // 放这里可以让三处播报都带上，不必各写一遍。
      if (this.isCanalPlan) parts.push('本程为公水联运，经平陆运河')
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
      parts.push(`预计北京时间${this.etaArrivalZh}、越南时间${this.etaArrivalVN}到达`)
      return parts.join('。')
    },

    /**
     * 出发语音播报：中文 + 越南语（中越双语），只读一次
     */
    _announceDeparture() {
      const r = this.route
      const originVN = NODE_NAME_VN[this.resolvedOriginId] || this.resolvedOriginId
      const destVN = NODE_NAME_VN[this.resolvedDestinationId] || this.resolvedDestinationId
      const estHours = r.estimatedHours || 0
      // 全程里程：整数就显示整数，否则一位小数（原来只报耗时、没报里程）
      const kmRaw = r.totalDistanceKm
      const km = (kmRaw == null || isNaN(kmRaw)) ? null : Math.round(kmRaw * 10) / 10
      const zhRun = km != null ? `全程约${km}公里，预计行驶${estHours}小时` : `预计行驶${estHours}小时`
      const viRun = km != null ? `Toàn tuyến khoảng ${km} km, dự kiến hành trình ${estHours} giờ`
                               : `Dự kiến hành trình ${estHours} giờ`

      // 中文播报（水运方案会插入"经平陆运河"那句；非水运时为空串，用 filter 去掉）
      const zhParts = [
        `导航开始，从${this.originName}到${this.destinationName}`,
        this.transportBriefZh,
        zhRun,
        this._buildStatusZh(),
        'Agent 已开启实时守护，祝您一路平安'
      ].filter(Boolean)
      const zhText = zhParts.join('。')

      // 越南语播报
      const viParts = [
        `Bắt đầu điều hướng từ ${originVN} đến ${destVN}`,
        this.transportBriefVi,
        viRun,
        this._buildStatusVi(),
        'Hệ thống Agent đã kích hoạt giám sát thời gian thực. Chúc bạn thượng lộ bình an'
      ].filter(Boolean)
      const viText = viParts.join('. ')

      // 最高优先级：force 抢占当前一切播报；lock 让本条念完之前别的播报不许抢占它。
      // 这是"导航开始 + 全程里程 + 预计耗时"这类关键信息，被灾害/轮询播报打断会听不全。
      this._vdbg('announceDeparture：里程=' + km + 'km 耗时=' + estHours + 'h，准备入队')
      this.speakQueue([zhText, viText], { force: true, lock: true })
    },

    /**
     * 构建越南语状态播报文本：延误 + 天气 + 通关 + 货损 + 风险 + 到达时间
     */
    _buildStatusVi() {
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
      parts.push(`Dự kiến đến vào ${this.etaArrivalViTTS} giờ Việt Nam`)
      return parts.join('. ')
    },

    /**
     * 灾害发生时的语音播报：说清"什么灾害 + 在哪 + 延误 + 天气 + 替代路线"
     */
    announceHazard(reason, optionCount) {
      const where = this.viaText || this.routeTitle
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
      this.speakQueue([
        zh,
        `Cảnh báo khẩn cấp: ${hazardVN} tại ${where}。Dự kiến chậm ${this.route.extraHours || 0} giờ。${vnAdvice}`
      ])
    },
    /** 拉取当前 O/D 的可选路线（含灾害概率），作为灾害发生后的绕行方案 */
    async loadRerouteOptions(reason) {
      if (this.rerouteLoading) return
      // 绕行面板已经开着：同一事件不再重复拉取与播报，等司机点选或关闭后再响应新事件
      if (this.rerouteOptions.length) return
      this.rerouteLoading = true
      try {
        const r = await axios.get('/api/route/candidates', {
          params: { originId: this.resolvedOriginId, destinationId: this.resolvedDestinationId, cargoType: 'cold' },
          timeout: 30000
        })
        const list = r.data.candidates || []
        // 展示所有候选走廊（按途经指纹合并同走廊重复项），司机自行选择
        this.rerouteOptions = this._mergeCorridorDuplicates(list)
        this.announceHazard(reason, this.rerouteOptions.length)
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
          this.speakQueue([
            `请注意，当前路线${this.topHazardName(r.data)}概率上升至百分之${p}。${status}`,
            `Cảnh báo: xác suất thiên tai trên tuyến đường hiện tại tăng lên ${p} phần trăm。Thời gian trễ dự kiến ${this.route.extraHours || 0} giờ。`
          ])
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
          this._interruptAndSpeak(vi ? [zh, vi] : [zh, 'Cảnh báo thời tiết đã được cập nhật。Vui lòng kiểm tra và lái xe cẩn thận。'])
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
  --prism-1:#6366f1; --prism-2:#38bdf8; --prism-3:#22d3ee; --prism-4:#a855f7; --prism-5:#ec4899;
  --prism-grad: linear-gradient(135deg, var(--prism-1) 0%, var(--prism-2) 36%, var(--prism-3) 54%, var(--prism-4) 78%, var(--prism-5) 100%);
  max-width: 420px; margin: 0 auto; min-height: 100vh;
  background-color: #f2f5fb;
  background-image:
    radial-gradient(ellipse 62% 40% at 10% 4%, rgba(99,102,241,.22), transparent 68%),
    radial-gradient(ellipse 58% 36% at 92% 12%, rgba(56,189,248,.20), transparent 68%),
    radial-gradient(ellipse 64% 42% at 86% 84%, rgba(168,85,247,.18), transparent 68%),
    radial-gradient(ellipse 58% 38% at 8% 92%, rgba(34,211,238,.17), transparent 68%);
  font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif; color: #1a1a2e; position: relative; box-shadow: 0 0 40px rgba(0,0,0,.08); }
.phone.nav-mode { max-width: 100%; }
.home-page { min-height: 100vh; display: flex; flex-direction: column; }
.home-hero { position: relative; overflow: hidden; background: var(--prism-grad); padding: 48px 24px 36px; text-align: center; }
/* 棱彩流光：一道高光缓慢扫过 hero（手机上只保留这一处动画，避免 WebView 里持续重绘） */
.home-hero::after { content: ''; position: absolute; inset: 0; pointer-events: none;
  background: linear-gradient(115deg, transparent 32%, rgba(255,255,255,.30) 48%, transparent 64%);
  background-size: 260% 100%; animation: hero-sheen 7s ease-in-out infinite; }
@keyframes hero-sheen { 0%, 12% { background-position: 135% 0; } 88%, 100% { background-position: -35% 0; } }
.home-logo h1 { color: #fff; font-size: 24px; font-weight: 800; margin: 0; letter-spacing: 1px; }
.home-sub { color: rgba(255,255,255,.75); font-size: 13px; margin-top: 6px; }
.logo-icon { font-size: 48px; display: block; margin-bottom: 12px; }
.home-form { flex: 1; padding: 0 16px; margin-top: -20px; position: relative; z-index: 2; }
.form-card { background: #fff; border-radius: 16px; padding: 20px 16px; box-shadow: 0 4px 24px rgba(0,0,0,.08); }
.form-row { margin-bottom: 12px; }
.form-label { font-size: 12px; color: #999; font-weight: 600; text-transform: uppercase; letter-spacing: .5px; margin-bottom: 6px; display: block; }
.form-input-wrap { display: flex; align-items: center; gap: 10px; background: #f5f7fa; border-radius: 12px; padding: 4px 14px; border: 2px solid transparent; transition: border-color .2s; }
.form-input-wrap:focus-within { border-color: var(--prism-1); background: #fff; }
.input-icon { width: 28px; height: 28px; border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 800; color: #fff; flex-shrink: 0; }
.input-icon.from { background: #34d399; }
.input-icon.to { background: #e53935; }
.form-input-wrap input { flex: 1; border: none; background: transparent; font-size: 15px; color: #1a1a2e; outline: none; padding: 10px 0; }
.form-input-wrap input::placeholder { color: #bbb; }
.form-swap { display: flex; justify-content: center; margin: -6px 0; }
.form-swap span { width: 32px; height: 32px; border-radius: 50%; background: #fff; border: 2px solid #e8ecf1; display: flex; align-items: center; justify-content: center; font-size: 14px; color: var(--prism-1); cursor: pointer; transition: all .2s; box-shadow: 0 2px 8px rgba(0,0,0,.06); }
.form-swap span:active { transform: scale(.9); background: #e3f2fd; }
.quick-picks { margin: 16px 0; }
.quick-label { font-size: 11px; color: #999; font-weight: 600; display: block; margin-bottom: 8px; }
.quick-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.quick-chip { padding: 6px 12px; border-radius: 20px; border: 1px solid #e3f2fd; background: #f5f9ff; color: var(--prism-1); font-size: 12px; cursor: pointer; transition: all .2s; font-weight: 500; }
.quick-chip:hover { background: #e3f2fd; border-color: #bbdefb; }
.quick-chip:active { transform: scale(.96); }
.start-btn { width: 100%; margin-top: 4px; padding: 16px; background: var(--prism-grad); border: none; border-radius: 14px; color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; transition: all .2s; box-shadow: 0 6px 20px rgba(99,102,241,.34); display: flex; align-items: center; justify-content: center; gap: 8px; }
.start-btn:active { transform: scale(.98); }
.start-btn:disabled { opacity: .5; cursor: not-allowed; }
.cand-section { margin-top: 12px; }
.cand-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; padding: 0 4px; }
.cand-header span:first-child { font-size: 13px; font-weight: 700; color: #1a1a2e; }
.cand-hint { font-size: 11px; color: #999; }
.cand-item { padding: 14px; border-radius: 12px; margin-bottom: 8px; cursor: pointer; background: #fff; border: 2px solid #eef2f6; transition: all .2s; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 1px 4px rgba(0,0,0,.03); }
.cand-item:active { transform: scale(.99); }
.cand-item.sel { border-color: var(--prism-1); background: #f0f6ff; box-shadow: 0 0 0 3px rgba(99,102,241,.08); }
.cand-left { display: flex; align-items: flex-start; gap: 10px; flex: 1; min-width: 0; }
.cand-radio { width: 18px; height: 18px; border-radius: 50%; border: 2px solid #c8d6e5; flex-shrink: 0; margin-top: 2px; transition: all .2s; }
.cand-radio.on { border-color: var(--prism-1); background: var(--prism-grad); box-shadow: inset 0 0 0 3px #fff; }
.cand-info { flex: 1; min-width: 0; }
.cand-title { font-size: 14px; font-weight: 700; color: #1a1a2e; }
.cand-via { font-size: 11px; color: #999; margin-top: 2px; }
.cand-meta { display: flex; gap: 6px; margin-top: 6px; }
.meta-tag { font-size: 11px; padding: 2px 8px; border-radius: 10px; background: #f0f2f5; color: #666; font-weight: 600; }
.meta-tag.warn { background: #fff3e0; color: #c2410c; }
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
.mode-act.accept { background: linear-gradient(135deg, var(--prism-1), var(--prism-4)); color: #fff; }
.mode-act.accept:disabled { opacity: .6; cursor: default; }
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
.home-nav span.active { background: linear-gradient(135deg, rgba(99,102,241,.18), rgba(56,189,248,.16) 45%, rgba(168,85,247,.18)); color: var(--prism-1); }
.tab-content { margin-top: 12px; padding-bottom: 80px; }
.card { background: #fff; border-radius: 14px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 6px rgba(0,0,0,.04); }
.card-head { display: flex; justify-content: space-between; align-items: center; font-size: 14px; font-weight: 700; margin-bottom: 8px; color: #1a1a2e; }
.count { font-size: 11px; font-weight: 700; padding: 2px 10px; border-radius: 20px; color: #fff; background: #e53935; }
.refresh { font-size: 11px; padding: 4px 12px; border-radius: 14px; background: #e3f2fd; color: var(--prism-1); border: none; cursor: pointer; font-weight: 600; transition: all .2s; }
.refresh:hover { background: #bbdefb; }
.refresh:disabled { opacity: .5; }
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
.me-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.me-label { font-size: 12px; color: #999; width: 60px; flex-shrink: 0; font-weight: 600; }
.me-input { flex: 1; padding: 8px 12px; border-radius: 8px; border: 1px solid #e8ecf1; font-size: 13px; color: #333; background: #fafbfc; }
.me-input:focus { outline: none; border-color: var(--prism-1); }
.save-btn { width: 100%; padding: 10px; border-radius: 10px; border: none; background: linear-gradient(135deg, var(--prism-1), var(--prism-4)); color: #fff; font-size: 13px; font-weight: 600; cursor: pointer; }
.nav-screen { position: fixed; inset: 0; z-index: 100; background-color: #eef2fa; background-image: radial-gradient(ellipse 72% 42% at 18% 0%, rgba(99,102,241,.16), transparent 68%), radial-gradient(ellipse 72% 42% at 86% 100%, rgba(168,85,247,.14), transparent 68%); overscroll-behavior: none; display: flex; flex-direction: column; }
/* 地图区域：占据上方剩余空间。地图自身设 z-index:0 形成独立层叠上下文，把 Leaflet 内部图层
   （tilePane 等 pane 的 z-index 高达 200~700）锁在里面，否则它们会盖住顶部状态栏(z-index:10)
   和底部面板(z-index:12)——面板“被底图盖住”就是这个原因 */
.nav-map-wrap { position: relative; flex: 1 1 auto; min-height: 0; }
.nav-map { position: absolute; inset: 0; z-index: 0; touch-action: none; }
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
.status-dot { width: 8px; height: 8px; border-radius: 50%; }
.nav-status.ok .status-dot { background: #4caf50; }
.nav-status.warn .status-dot { background: #ea7a2e; }
.nav-status.rerouted .status-dot { background: #f44336; }
.nav-legend { position: absolute; top: 90px; right: 12px; z-index: 10; display: flex; flex-direction: column; gap: 4px; background: rgba(255,255,255,.85); backdrop-filter: blur(10px); padding: 8px 10px; border-radius: 10px; font-size: 10px; color: #666; }
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
.eta-num { font-size: 32px; font-weight: 800; color: #1a1a2e; line-height: 1; }
.eta-unit { font-size: 13px; color: #666; font-weight: 600; }
.nav-dist { display: flex; align-items: baseline; gap: 2px; }
.dist-num { font-size: 20px; font-weight: 700; color: #555; }
.dist-unit { font-size: 11px; color: #999; }
.nav-risk-badge { margin-left: auto; font-size: 12px; font-weight: 700; padding: 4px 12px; border-radius: 20px; }
.nav-risk-badge.ok { background: #e8f5e9; color: #16a34a; }
.nav-risk-badge.warn { background: #fff3e0; color: #c2410c; }
.nav-risk-badge.rerouted { background: #fce4ec; color: #c62828; }
.nav-info-sub { display: flex; align-items: center; gap: 8px; margin-top: 8px; font-size: 12px; color: #888; }
.delay-tag { background: #fce4ec; color: #c62828; padding: 2px 8px; border-radius: 8px; font-weight: 600; font-size: 11px; }
/* 水运方案标签：用棱彩主色，与"公水联运·平陆运河"呼应 */
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
.eta-arrival { display: inline-flex; align-items: center; color: var(--prism-1); font-weight: 700; }
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
  .eta-sheen, .eta-bar-fill, .badge-dot, .pulse-dot::after, .eta-card { animation: none !important; }
}
.nav-actions { display: flex; gap: 8px; margin-top: 10px; justify-content: flex-end; flex-wrap: wrap; }
/* 路线预览（浮在地图底图之上的选路面板，路线留白由 fitRoute 计算，避免被面板遮挡） */
.preview-card { position: relative; overflow: hidden; display: flex; flex-direction: column; padding: 14px; background: linear-gradient(150deg, rgba(255,255,255,.96) 0%, rgba(237,247,255,.94) 58%, rgba(226,242,250,.95) 100%); backdrop-filter: blur(18px); border: 1px solid rgba(99,102,241,.16); box-shadow: 0 16px 38px rgba(12,26,45,.24), inset 0 1px 0 rgba(255,255,255,.9); animation: eta-in .45s cubic-bezier(.2,.9,.3,1.2) both; }
.preview-head { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 10px; flex-shrink: 0; }
.preview-head-left { flex: 1; min-width: 0; }
.preview-title { display: flex; align-items: center; font-size: 15px; font-weight: 800; color: #1a1a2e; }
.pv-ico { margin-right: 6px; font-size: 15px; animation: ico-float 2.6s ease-in-out infinite; }
@keyframes ico-float { 0%, 100% { transform: translateY(0) rotate(-6deg); } 50% { transform: translateY(-3px) rotate(6deg); } }
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
  .pv-item, .pv-item.sel, .safest-flag, .prob-pill.high.glow, .preview-card, .preview-start::after, .pv-ico { animation: none !important; }
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
.hazard-detail-panel .kb-head { align-items: center; padding-bottom: 10px; border-bottom: 1px solid rgba(99,102,241,.14); }
.hazard-detail-panel .kb-head > span:first-child { background: linear-gradient(90deg, var(--prism-1), var(--prism-3), var(--prism-4)); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
.kb-head { font-size: 14px; font-weight: 700; margin-bottom: 12px; display: flex; justify-content: space-between; }
.kb-close { font-size: 16px; cursor: pointer; color: #999; }
@media (prefers-reduced-motion: reduce) { .kb-panel, .hd-item, .hd-bar-in { animation: none !important; } }
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
</style><style>
@keyframes risk-blink { 0%,100% { opacity: 0.7; } 50% { opacity: 1; } }
.risk-blink { animation: risk-blink 0.8s ease-in-out infinite; }
.port-tip { background: rgba(0,0,0,0.8) !important; border: none !important; color: #fff !important; font-size: 12px !important; font-weight: 700 !important; padding: 4px 8px !important; border-radius: 4px !important; }
.nav-tri-marker { background: none !important; border: none !important; }
/* 口岸标记：圆点 + 常驻名称都画在同一个 divIcon 内（替代 permanent Tooltip） */
.port-label-wrap { background: none !important; border: none !important; }
.port-dot { position: absolute; left: -6px; top: -6px; width: 12px; height: 12px; border-radius: 50%;
  background: #f39c12; border: 2px solid #fff; box-shadow: 0 1px 4px rgba(0,0,0,.35); }
.port-label-text { position: absolute; left: 0; top: -26px; transform: translateX(-50%); white-space: nowrap;
  background: rgba(0,0,0,.78); color: #fff; font-size: 11px; font-weight: 700;
  padding: 2px 7px; border-radius: 6px; pointer-events: none; }
/* 「当前位置」标签：直接画在标记图标内（替代原来的 permanent Tooltip，避免其空 _map 抛异常） */
.nav-tri-label { position: absolute; top: -20px; left: 50%; transform: translateX(-50%); white-space: nowrap;
  background: rgba(0,0,0,.78); color: #fff; font-size: 11px; font-weight: 700;
  padding: 2px 7px; border-radius: 6px; pointer-events: none; }
.nav-tri-arrow { width: 0; height: 0; border-left: 16px solid transparent; border-right: 16px solid transparent; border-bottom: 32px solid var(--prism-2); filter: drop-shadow(0 2px 4px rgba(50,70,120,.14)); }
.nav-tri-arrow::after { content: ''; position: absolute; top: 28px; left: -5px; width: 10px; height: 10px; background: var(--prism-2); border-radius: 50%; box-shadow: 0 0 6px rgba(30,136,229,.6); }
.nav-tri-dot { width: 20px; height: 20px; background: radial-gradient(circle, #fff 30%, var(--prism-2) 70%); border-radius: 50%; margin: -10px auto 0; border: 3px solid var(--prism-2); box-shadow: 0 0 8px rgba(30,136,229,.5); animation: nav-pulse 1.5s ease-in-out infinite; }
@keyframes nav-pulse { 0%,100% { transform: scale(1); opacity: 1; } 50% { transform: scale(1.15); opacity: .85; } }
.gps-searching { animation: gps-blink 0.8s ease-in-out infinite; }
@keyframes gps-blink { 0%,100% { opacity: 1; } 50% { opacity: .3; } }

/* ============================================================
   浅色主题覆盖 · 高级玻璃拟态（仅换配色不动布局）
   导航/地图界面保持原样以保可读性，只覆盖首页与各 tab 卡片
   ============================================================ */
.phone:not(.nav-mode) {
  background:
    radial-gradient(ellipse 120% 60% at 50% -10%, rgba(79,109,245,.14), transparent 60%),
    radial-gradient(ellipse 80% 40% at 90% 110%, rgba(124,92,255,.10), transparent 55%),
    linear-gradient(180deg, #f3f6fc 0%, #e9eef9 50%, #eef3fb 100%);
  color: #33415c;
}
.phone:not(.nav-mode) .home-hero {
  background:
    radial-gradient(ellipse 90% 120% at 50% 0%, rgba(79,109,245,.22), transparent 65%),
    linear-gradient(160deg, #4f6df5 0%, #6b8afd 55%, #7c5cff 100%);
  border-bottom: 1px solid rgba(79,109,245,.22);
  box-shadow: 0 12px 40px rgba(20,80,200,.18);
}
.phone:not(.nav-mode) .home-logo h1 {
  color: #ffffff;
  text-shadow: 0 2px 16px rgba(30,40,90,.35), 0 0 40px rgba(255,255,255,.25);
  letter-spacing: 2px;
}
.phone:not(.nav-mode) .home-logo .logo-icon {
  display: inline-block;
  filter: drop-shadow(0 0 14px rgba(255,255,255,.55));
  animation: hero-float 3.2s ease-in-out infinite;
}
@keyframes hero-float { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-6px); } }
.phone:not(.nav-mode) .home-sub { color: #dbe4ff; text-shadow: 0 1px 8px rgba(30,40,90,.3); }

/* 表单卡 / 通用卡片 → 浅色玻璃拟态 + 微光描边 */
.phone:not(.nav-mode) .form-card,
.phone:not(.nav-mode) .card {
  background: linear-gradient(165deg, rgba(255,255,255,.72), rgba(255,255,255,.82));
  border: 1px solid rgba(105,125,175,.18);
  box-shadow:
    0 8px 32px rgba(50,70,120,.16),
    inset 0 1px 0 rgba(140,160,220,.08);
  backdrop-filter: blur(14px);
  border-radius: 16px;
  transition: border-color .25s, box-shadow .25s, transform .12s;
}
.phone:not(.nav-mode) .card:hover {
  border-color: rgba(105,125,175,.32);
  box-shadow:
    0 10px 36px rgba(50,70,120,.15),
    0 0 24px rgba(70,120,255,.10),
    inset 0 1px 0 rgba(140,160,220,.12);
}
.phone:not(.nav-mode) .card-head { color: #1f2d4d; }
.phone:not(.nav-mode) .form-label,
.phone:not(.nav-mode) .me-label,
.phone:not(.nav-mode) .cand-header { color: #64748f; }

/* 输入框 */
.phone:not(.nav-mode) .form-input-wrap { background: #f4f6fc; border-color: #dbe3f2; }
.phone:not(.nav-mode) .form-input-wrap:focus-within { border-color: #4f6df5; background: #fff; }
.phone:not(.nav-mode) .form-input-wrap input { color: #1f2d4d; }
.phone:not(.nav-mode) .form-input-wrap input::placeholder { color: #9aa7bd; }
.phone:not(.nav-mode) .me-input { background: #f4f6fc; border-color: #dbe3f2; color: #1f2d4d; }

/* 快捷路线 chip / 知识 chip */
.phone:not(.nav-mode) .quick-chip,
.phone:not(.nav-mode) .chip { background: #ffffff; border-color: #dbe3f2; color: #4f6df5; }
.phone:not(.nav-mode) .quick-chip:hover,
.phone:not(.nav-mode) .chip:hover { background: #f6f8fd; border-color: #4f6df5; }

/* 主按钮保持高亮渐变（深色下更醒目） */
.phone:not(.nav-mode) .start-btn { background: linear-gradient(135deg, #4f6df5, #6b8afd); box-shadow: 0 4px 16px rgba(79,109,245,.45); }

/* 候选路线卡 */
.phone:not(.nav-mode) .cand-item { background: #ffffff; border-color: #eef1f8; }
.phone:not(.nav-mode) .cand-item.sel { border-color: #4f6df5; background: #f6f8fd; box-shadow: 0 0 0 3px rgba(79,109,245,.15); }
.phone:not(.nav-mode) .cand-title { color: #1f2d4d; }
.phone:not(.nav-mode) .cand-via { color: #64748f; }
.phone:not(.nav-mode) .meta-tag { background: #eef1f8; color: #64748f; }
.phone:not(.nav-mode) .meta-tag.warn { background: rgba(225,29,72,.15); color: #e11d48; }
.phone:not(.nav-mode) .cand-radio.on { border-color: #4f6df5; background: #4f6df5; }

/* 底部信息条 & 导航 */
.phone:not(.nav-mode) .info-bar { color: #64748f; }
.phone:not(.nav-mode) .driver-tag { color: #1f2d4d; }
.phone:not(.nav-mode) .cargo-tag { background: #eef1f8; color: #64748f; }
.phone:not(.nav-mode) .home-nav { background: #ffffff; border: 1px solid #eef1f8; }
.phone:not(.nav-mode) .home-nav span { color: #64748f; }
.phone:not(.nav-mode) .home-nav span.active { background: rgba(79,109,245,.15); color: #4f6df5; }
.phone:not(.nav-mode) .home-nav .has-task { color: #e11d48; }

/* 风险/预警/知识 卡内容 */
.phone:not(.nav-mode) .empty { color: #9aa7bd; }
.phone:not(.nav-mode) .refresh { background: rgba(79,109,245,.15); color: #4f6df5; }
.phone:not(.nav-mode) .refresh:hover { background: rgba(79,109,245,.28); }
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
.phone:not(.nav-mode) .save-btn { background: #4f6df5; }

/* 任务变更卡（深色版） */
.phone:not(.nav-mode) .task-card { border-left-color: #ea7a2e; }
.phone:not(.nav-mode) .task-card.confirmed { border-left-color: #22a35a; }
.phone:not(.nav-mode) .task-state.ok { color: #16a34a; }
.phone:not(.nav-mode) .task-state.pending { color: #e11d48; }
.phone:not(.nav-mode) .task-channel { background: #eef1f8; color: #64748f; }
.phone:not(.nav-mode) .task-plan { background: rgba(79,109,245,.15); color: #4f6df5; }
.phone:not(.nav-mode) .lang-btn { background: #ffffff; border-color: #dbe3f2; color: #64748f; }
.phone:not(.nav-mode) .lang-btn.on { border-color: #4f6df5; color: #4f6df5; background: rgba(79,109,245,.15); }
.phone:not(.nav-mode) .speak-btn { background: #ffffff; border-color: #dbe3f2; color: #64748f; }
.phone:not(.nav-mode) .task-msg { background: #f4f6fc; color: #1f2d4d; border: 1px solid #e2e8f5; }
.phone:not(.nav-mode) .rights-box { background: linear-gradient(135deg, rgba(22,163,74,.14), rgba(22,163,74,.06)); border-color: rgba(22,163,74,.4); }
.phone:not(.nav-mode) .rights-title { color: #16a34a; }
.phone:not(.nav-mode) .rights-item { color: #15803d; }
.phone:not(.nav-mode) .confirm-task-btn { background: linear-gradient(135deg, #1fa84a, #34d399); box-shadow: 0 4px 12px rgba(22,163,74,.4); }
.phone:not(.nav-mode) .confirm-task-btn.done { background: #eef1f8; color: #16a34a; }
.phone:not(.nav-mode) .task-hint { color: #9aa7bd; }
.phone:not(.nav-mode) .plan-item { background: #f6f8fd; border-color: #eef1f8; }
.phone:not(.nav-mode) .plan-item.rec { border-color: #22a35a; background: rgba(22,163,74,.08); }
.phone:not(.nav-mode) .plan-badge { background: rgba(79,109,245,.15); color: #4f6df5; }
.phone:not(.nav-mode) .plan-name { color: #1f2d4d; }
.phone:not(.nav-mode) .plan-meta { color: #64748f; }
.phone:not(.nav-mode) .plan-meta .warn { color: #e11d48; }
.phone:not(.nav-mode) .plan-meta .ok { color: #16a34a; }
.phone:not(.nav-mode) .plan-risk { color: #e11d48; }
.phone:not(.nav-mode) .plan-rec { background: rgba(124,92,255,.12); color: #7c5cff; }
.phone:not(.nav-mode) .touch-bar { background: #eef1f8; }
.phone:not(.nav-mode) .touch-fill { background: linear-gradient(90deg, #1fa84a, #22a35a); }
.phone:not(.nav-mode) .touch-text { color: #64748f; }

/* 顶部推送条（深色可读） */
.phone:not(.nav-mode) .push-banner { box-shadow: 0 8px 24px rgba(50,70,120,.15); }

/* ---------- 发光特效 + 点击反馈（深色区全局） ---------- */
/* 主按钮：霓虹辉光 + 按压下沉 */
.phone:not(.nav-mode) .start-btn {
  background: linear-gradient(135deg, #4f6df5, #6b8afd 55%, #7c5cff);
  box-shadow:
    0 6px 22px rgba(79,109,245,.45),
    0 0 32px rgba(109,138,255,.28),
    inset 0 1px 0 rgba(255,255,255,.22);
  transition: transform .12s, box-shadow .25s, filter .2s;
}
.phone:not(.nav-mode) .start-btn:hover:not(:disabled) {
  filter: brightness(1.08);
  box-shadow:
    0 8px 26px rgba(79,109,245,.55),
    0 0 44px rgba(109,138,255,.4),
    inset 0 1px 0 rgba(255,255,255,.28);
}
.phone:not(.nav-mode) .start-btn:active:not(:disabled) {
  transform: scale(.965) translateY(1px);
  box-shadow: 0 2px 10px rgba(79,109,245,.35), 0 0 18px rgba(109,138,255,.2);
}

/* 确认接收按钮：翠绿辉光 */
.phone:not(.nav-mode) .confirm-task-btn {
  background: linear-gradient(135deg, #1fa84a, #34d399);
  box-shadow:
    0 6px 20px rgba(46,204,113,.4),
    0 0 30px rgba(52,211,153,.28),
    inset 0 1px 0 rgba(255,255,255,.22);
  transition: transform .12s, box-shadow .25s, filter .2s;
}
.phone:not(.nav-mode) .confirm-task-btn:active:not(:disabled) {
  transform: scale(.96) translateY(1px);
  filter: brightness(.94);
  box-shadow: 0 2px 8px rgba(46,204,113,.3);
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
  transform: scale(.96);
  filter: brightness(1.25);
}

/* 底部导航 tab：按压点亮 + 选中辉光 */
.phone:not(.nav-mode) .home-nav span { transition: transform .12s, background .2s, color .2s, box-shadow .25s; }
.phone:not(.nav-mode) .home-nav span:active { transform: scale(.9); }
.phone:not(.nav-mode) .home-nav span.active {
  background: rgba(79,109,245,.16);
  color: #4f6df5;
  box-shadow: 0 0 16px rgba(79,109,245,.22), inset 0 1px 0 rgba(140,160,220,.15);
}

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
  color: #4f6df5; font-size: 13px; font-weight: 600; cursor: pointer;
  box-shadow: 0 4px 14px rgba(50,70,120,.14), inset 0 1px 0 rgba(140,160,220,.1);
  backdrop-filter: blur(10px);
  transition: transform .12s, box-shadow .25s, color .2s;
}
.drv-back-btn .back-arrow { font-size: 15px; line-height: 1; }
.drv-back-btn:hover { color: #3556d4; box-shadow: 0 6px 18px rgba(0,0,0,.4), 0 0 18px rgba(79,109,245,.2); }
.drv-back-btn:active { transform: scale(.93); }
</style>