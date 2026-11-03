<template>
  <div class="portal">
    <!-- 顶栏：左侧竖排组合标（棱彩针 / 志宁 / ZHINING）+ 右侧小功能菜单（照腾讯官网版式：logo 左、导航右） -->
    <header class="topbar">
      <div class="brand" @click="go({ id: 'home' })">
        <!-- 左：棱彩组合标（针 / 志宁 / ZHINING）——即“新 logo”，保持不动 -->
        <div class="brand-lock">
          <span class="logo-pin" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="30" height="30">
              <defs><linearGradient id="zg" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#ff2d55"/><stop offset=".17" stop-color="#ff8a00"/>
                <stop offset=".34" stop-color="#ffd400"/><stop offset=".5" stop-color="#16a34a"/>
                <stop offset=".67" stop-color="#00c2ff"/><stop offset=".84" stop-color="#2b6bff"/>
                <stop offset="1" stop-color="#7c3aed"/>
              </linearGradient></defs>
              <path fill="url(#zg)" d="M12 2C7.6 2 4 5.6 4 10c0 5.2 6.5 11.3 7.4 12.1.3.3.9.3 1.2 0C13.5 21.3 20 15.2 20 10c0-4.4-3.6-8-8-8z"/>
              <circle cx="12" cy="10" r="3" fill="#fff"/>
            </svg>
          </span>
          <div class="brand-txt">
            <b>志宁</b>
            <i>ZHINING</i>
          </div>
        </div>
        <!-- 右：黑体字标（腾讯官网那种一行横排：英文在前、中文在后） -->
        <div class="brand-word">
          <i>ZHINING</i>
          <b>志宁</b>
        </div>
      </div>
      <nav class="nav">
        <button v-for="m in menu" :key="m.id"
                :class="['nav-btn', { on: active === m.id }]"
                @click="onMenu(m)">{{ m.label }}</button>
      </nav>
      <div class="src-chip" :title="'数据源：' + srcLabel">
        <span class="dot" :class="srcFresh ? 'ok' : 'cache'"></span>{{ srcLabel || '连接中…' }}
      </div>
      <!-- 右上角语言切换：简 / EN / VI（复用 driver/i18n.js 的整机翻译器，未命中的中文原样回落） -->
      <div class="lang-switch" role="group" aria-label="界面语言 Language">
        <button v-for="l in langs" :key="l.id"
                :class="['lang-seg', { on: uiLang === l.id }]"
                @click="setLang(l.id)">{{ l.label }}</button>
      </div>
      <div class="user-chip" v-if="session.name" :title="'演示级会话 · 服务重启后需重新登录'">
        👤 {{ session.name }}
        <button class="logout" @click="doLogout">退出</button>
      </div>
      <button v-else class="loginlink" @click="auth.show = true">登录 / 注册</button>
    </header>

    <main class="body">
      <!-- 门户首页：政务/企业门户式三段——公告条 + 数据概览卡 + 服务入口卡（均读后端真实数据） -->
      <section v-if="active === 'home'" class="home">
        <!-- ① 公告条 -->
        <!-- 公告条：区域态势数据到位后改为播报「本区域当前最不利城市」，未到位时回落门户说明 -->
        <div class="notice">
          <span class="nc-tag">公告</span>
          <span class="nc-txt">{{ noticeText }}</span>
          <span class="nc-src"><i class="dot" :class="srcFresh ? 'ok' : 'cache'"></i>{{ srcLabel || '连接中…' }} · 版本 {{ buildTag }}</span>
        </div>

        <!-- ② 首页空白区：暂用司机端开屏动画原样填充（想好放什么后再替换） -->
        <div class="home-boot" aria-hidden="true">
          <div class="hb-inner">
            <div class="hb-word"><span class="bc k1">致</span><span class="bc k2">力</span><span class="bc k6">，</span><span class="bc k3">致</span><span class="bc k4">宁</span><span class="bc k5">。</span></div>
            <div class="hb-slogan">跨境物流气象预警</div>
            <div class="hb-bar"><i></i></div>
            <div class="hb-tip">正在连接导航服务…</div>
          </div>
          <div class="hb-brand">ZLN-Map</div>
        </div>
      </section>

      <!-- 只读视图：加载态 / 错误态 / 内容 -->
      <section v-else class="view">
        <div v-if="st(active).loading" class="tip">载入中…</div>
        <div v-else-if="st(active).error" class="tip err">{{ st(active).error }}
          <button class="retry" @click="reload(active)">重试</button>
        </div>
        <template v-else>

          <!-- 东盟态势：区域热点城市（东盟 10 国 + 中国枢纽）实况气象与单点 AMRI，数据来自官方 CRA40 格点 -->
          <div v-if="active === 'asean'" class="grid">
            <div class="card-blk span2">
              <h3>区域气象态势
                <span class="mut">{{ aseanHeadSub() }}</span>
              </h3>
              <div class="bands">
                <span class="band CRITICAL">CRITICAL {{ (data.asean.row.severityBands || {}).CRITICAL || 0 }}</span>
                <span class="band HIGH">HIGH {{ (data.asean.row.severityBands || {}).HIGH || 0 }}</span>
                <span class="band MEDIUM">MEDIUM {{ (data.asean.row.severityBands || {}).MEDIUM || 0 }}</span>
                <span class="band LOW">LOW {{ (data.asean.row.severityBands || {}).LOW || 0 }}</span>
                <button class="retry" :disabled="aseanRefreshing" @click="refreshAsean">
                  {{ aseanRefreshing ? '刷新中…' : '刷新区域数据' }}
                </button>
              </div>
              <div v-if="data.asean.row.regionPeak" class="worst">
                <div class="w-idx" :class="'sev-' + data.asean.row.regionPeak.severity">{{ fmt.num(data.asean.row.regionPeak.index) }}</div>
                <div class="w-meta">
                  <b>{{ pName(data.asean.row.regionPeak) }}<span class="mut"> · {{ pCountry(data.asean.row.regionPeak) }}</span></b>
                  <span class="sev-tag" :class="'sev-' + data.asean.row.regionPeak.severity">{{ data.asean.row.regionPeak.severity }}</span>
                  <span class="mut">{{ aseanPeakMeta() }}</span>
                </div>
              </div>
              <p v-if="data.asean.row.stale && data.asean.row.count" class="foot warn mut">缓存已超过设定时长，下列读数为最近一次成功回源的值，不代表此刻实况。</p>
              <p v-if="data.asean.row.error" class="foot err">{{ aseanErrorLine() }}</p>
            </div>

            <div class="card-blk span2">
              <h3>热点城市风险分布 <span class="mut">按 AMRI 指数降序 · 高风险置顶</span></h3>
              <div class="acities">
                <div v-for="c in data.asean.row.cities" :key="c.cityId" class="aci">
                  <div class="aci-head">
                    <b class="aci-nm">{{ pName(c) }}</b>
                    <span class="aci-idx" :class="'sev-' + c.severity">{{ fmt.num(c.index) }}</span>
                  </div>
                  <div class="aci-sub mut">{{ pCountry(c) }} · {{ pRole(c) }}</div>
                  <div class="aci-tags">
                    <span class="sev-tag" :class="'sev-' + c.severity">{{ c.severity }}</span>
                    <span class="hz">{{ t(c.mainHazard) }}</span>
                    <span v-if="c.corridor" class="cbadge">走廊</span>
                  </div>
                  <div class="aci-wx mut">{{ aseanWx(c) }}</div>
                  <!-- 官方额外要素（日最高/最低温、总/低云量）：拿不到的项直接不写这一栏，不拿 0 充数 -->
                  <div class="aci-wx mut">{{ aseanExt(c) }}</div>
                  <!-- 前瞻预报：源没给这一项（forecastCities=0）时整栏不写，也不在每张卡上排一串「—」占位 -->
                  <div v-if="data.asean.row.forecastCities" class="aci-wx mut">{{ aseanFc(c) }}</div>
                </div>
              </div>
              <p class="foot mut">{{ data.asean.row.coverageNote }}</p>
              <p class="foot mut">{{ data.asean.row.routingNote }}</p>
              <p class="foot mut">{{ aseanExtNote() }}</p>
              <p class="foot mut">{{ data.asean.row.forecastNote }}</p>
              <p class="foot mut">{{ aseanStatLine() }}</p>
            </div>
          </div>

          <!-- 实时气象 -->
          <div v-if="active === 'weather'" class="grid">
            <div class="card-blk span2">
              <h3>沿线节点实况气象 <span class="mut">{{ data.weather.servedSourceLabel }} · {{ data.weather.servedFresh ? '本次真实回源' : '节流窗口内缓存' }}</span></h3>
              <table class="tb">
                <thead><tr><th>节点</th><th>气温℃</th><th>湿度%</th><th>降水mm</th><th>风km/h</th><th>能见度m</th><th>气压hPa</th><th>取时</th></tr></thead>
                <tbody>
                  <tr v-for="p in data.weather.points" :key="p.nodeId">
                    <td class="nm">{{ p.nodeName }}</td>
                    <td>{{ fmt.num(p.temperatureC) }}</td>
                    <td>{{ fmt.num(p.humidityPct, 0) }}</td>
                    <td>{{ fmt.num(p.precipitationMm) }}</td>
                    <td>{{ fmt.num(p.windKph) }}</td>
                    <td>{{ fmt.num(p.visibilityM, 0) }}</td>
                    <td>{{ fmt.num(p.pressureHpa, 0) }}</td>
                    <td class="mut">{{ fmt.time(p.fetchedAt) }}</td>
                  </tr>
                </tbody>
              </table>
              <p class="foot mut">节点为当前走廊沿线城市/口岸观测点（真实 CRA40 格点就近取值），非全站地理覆盖。</p>
            </div>
          </div>

          <!-- AMRI 风险 -->
          <div v-if="active === 'amri'" class="grid">
            <div class="card-blk">
              <h3>气象风险指数 <span class="mut">{{ data.amri.model && data.amri.model.fullName }}</span></h3>
              <p class="formula">{{ data.amri.model && data.amri.model.formula }}</p>
              <div class="bands">
                <span class="band CRITICAL">CRITICAL ≥ {{ data.amri.model && data.amri.model.bands.CRITICAL }}</span>
                <span class="band HIGH">HIGH ≥ {{ data.amri.model && data.amri.model.bands.HIGH }}</span>
                <span class="band MEDIUM">MEDIUM ≥ {{ data.amri.model && data.amri.model.bands.MEDIUM }}</span>
                <span class="band LOW">LOW</span>
              </div>
              <table class="tb sm">
                <thead><tr><th>灾种</th><th>单位</th><th>x0</th><th>k</th><th>权重</th><th>方向</th></tr></thead>
                <tbody>
                  <tr v-for="h in (data.amri.model && data.amri.model.hazards) || []" :key="h.id">
                    <td class="nm">{{ h.label }}</td><td>{{ h.unit }}</td><td>{{ h.x0 }}</td>
                    <td>{{ h.k }}</td><td>{{ h.weight }}</td><td>{{ h.direction === 'down' ? '反向↓' : '正向↑' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="card-blk">
              <h3>当前走廊最不利点 <span class="mut">NN→HN 沿线 · {{ data.amri.live && data.amri.live.count }} 点</span></h3>
              <div v-if="data.amri.live && data.amri.live.worst" class="worst">
                <div class="w-idx" :class="'sev-' + data.amri.live.worst.severity">{{ fmt.num(data.amri.live.worst.index) }}</div>
                <div class="w-meta">
                  <b>{{ data.amri.live.worst.nodeName }}</b>
                  <span class="sev-tag" :class="'sev-' + data.amri.live.worst.severity">{{ data.amri.live.worst.severity }}</span>
                  <span class="mut" v-if="data.amri.live.worst.sandboxSim">含沙盘模拟值（非实测）</span>
                </div>
              </div>
              <div v-for="c in (data.amri.live && data.amri.live.worst && data.amri.live.worst.contributions) || []" :key="c.id" class="contrib">
                <span class="cl">{{ c.label }}</span>
                <span class="cbar"><i :style="{ width: Math.min(100, c.impact) + '%' }"></i></span>
                <span class="cv">{{ fmt.num(c.value) }}{{ c.unit }}</span>
              </div>
              <table class="tb sm">
                <thead><tr><th>点</th><th>指数</th><th>档</th></tr></thead>
                <tbody>
                  <tr v-for="p in (data.amri.live && data.amri.live.points) || []" :key="p.nodeId">
                    <td class="nm">{{ p.nodeName }}</td><td>{{ fmt.num(p.index) }}</td>
                    <td><span class="sev-tag" :class="'sev-' + p.severity">{{ p.severity }}</span></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 水运 -->
          <div v-if="active === 'waterway'" class="grid">
            <div class="card-blk span2">
              <h3>平陆运河支流风险差异化预测
                <span class="mut">{{ data.water.live && data.water.live.sourceLabel }}{{ (data.water.live && data.water.live.dataAvailable) ? '' : ' · 暂无实时雨强（按示意/保守处理）' }}</span>
              </h3>
              <div class="trib">
                <div v-for="t in (data.water.live && data.water.live.tributaries) || []" :key="t.id" class="titem">
                  <div class="t-head"><b>{{ t.name }}</b><span class="mut">{{ t.riskType }} · 等级{{ t.level }}</span></div>
                  <div class="tbar"><i :style="{ width: Math.min(100, t.probabilityPct) + '%' }"></i><em>{{ t.probabilityPct }}%</em></div>
                  <p class="mut small">{{ t.explanation }}</p>
                </div>
              </div>
              <p class="foot mut" v-if="data.water.live">干流顶托设计流速 {{ fmt.num(data.water.live.inducedCurrentMs) }} m/s；{{ data.water.live.formula }}</p>
            </div>
            <div class="card-blk">
              <h3>陆水联运方案 <span class="mut">{{ data.water.inter && data.water.inter.name }}</span></h3>
              <div v-for="(l, i) in (data.water.inter && data.water.inter.legs) || []" :key="i" class="leg">
                <span class="lg-mode">{{ l.mode }}</span>
                <span class="lg-route">{{ l.from }} → {{ l.to }}</span>
                <span class="lg-num">{{ fmt.num(l.km, 0) }}km · {{ fmt.num(l.hours) }}h<template v-if="l.costYuan"> · ¥{{ fmt.num(l.costYuan, 0) }}</template></span>
              </div>
              <div class="leg total">
                <span class="lg-mode">合计</span>
                <span class="lg-route mut">{{ data.water.inter && data.water.inter.damageRisk }}</span>
                <span class="lg-num">{{ fmt.num(data.water.inter && data.water.inter.totalHours) }}h · ¥{{ fmt.num(data.water.inter && data.water.inter.totalCostYuan, 0) }}</span>
              </div>
              <p class="foot mut" v-if="data.water.inter">{{ data.water.inter.damageNote }}</p>
            </div>
            <div class="card-blk">
              <h3>运河通航观测接入状态 <span class="mut">只读展示</span></h3>
              <dl class="kv">
                <dt>数据源</dt><dd>{{ (data.water.st && data.water.st.configuredSource) || '—' }}</dd>
                <dt>官方接口</dt><dd>{{ (data.water.st && data.water.st.officialApiConfigured) ? '已配置' : '未配置（赛事阶段经合作协议获取）' }}</dd>
                <dt>最近观测</dt><dd>{{ (data.water.st && data.water.st.lastObservation) ? '有' : '暂无' }}</dd>
                <dt>累计失败</dt><dd>{{ fmt.num((data.water.st && data.water.st.fetchFailures), 0) }}</dd>
              </dl>
              <p class="foot mut" v-if="data.water.st">{{ data.water.st.note }}</p>
              <p class="foot mut">本页仅呈现接入链路状态；禁航红线推演请在调度大屏决策沙盘进行。</p>
            </div>
          </div>

          <!-- 通关时效 -->
          <div v-if="active === 'customs'" class="grid">
            <div class="card-blk">
              <h3>官方通关公告（真实抓取） <span class="mut" v-if="data.customs.bul && data.customs.bul.ok">{{ data.customs.bul.publishDate }}</span></h3>
              <template v-if="data.customs.bul && data.customs.bul.ok">
                <div class="big2">
                  <div><b>{{ fmt.num(data.customs.bul.importHours, 2) }}</b><span class="mut">进口整体通关（小时）</span></div>
                  <div><b>{{ fmt.num(data.customs.bul.exportHours, 2) }}</b><span class="mut">出口整体通关（小时）</span></div>
                </div>
                <blockquote class="quote">「{{ data.customs.bul.quote }}」</blockquote>
                <p class="foot mut">来源：<a :href="data.customs.bul.sourceUrl" target="_blank" rel="noopener">{{ data.customs.bul.source }}</a>
                  · 抓取 {{ data.customs.bul.fetchedAtText }}
                  · 回源{{ (data.customs.bul.stats && data.customs.bul.stats.upstreamRequests) || 0 }}/命中{{ (data.customs.bul.stats && data.customs.bul.stats.cacheHits) || 0 }}/节流{{ (data.customs.bul.stats && data.customs.bul.stats.throttled) || 0 }}</p>
              </template>
              <p v-else class="foot err">暂无可用公告（{{ (data.customs.bul && data.customs.bul.error) || '未抓到官方数字' }}），以包内基线为准。</p>
            </div>
            <div class="card-blk">
              <h3>各口岸车辆过口时效 <span class="mut">与上方公告口径不同（此为单车次过口，小时量级）</span></h3>
              <table class="tb">
                <thead><tr><th>口岸</th><th>基准h</th><th>当前h</th><th>平/峰/节</th><th>普/冷/危/超</th></tr></thead>
                <tbody>
                  <tr v-for="e in data.customs.eff" :key="e.portId">
                    <td class="nm">{{ e.portName }}</td>
                    <td>{{ fmt.num(e.baseHours) }}</td>
                    <td :class="{ warn: e.adjusted }">{{ fmt.num(e.currentHours) }}</td>
                    <td class="mut">{{ fmt.num(e.periods.normal) }}/{{ fmt.num(e.periods.peak) }}/{{ fmt.num(e.periods.holiday) }}</td>
                    <td class="mut">{{ fmt.num(e.cargoTypes.general) }}/{{ fmt.num(e.cargoTypes.cold) }}/{{ fmt.num(e.cargoTypes.dangerous) }}/{{ fmt.num(e.cargoTypes.oversized) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

        </template>
      </section>
    </main>

    <!-- 门户 AI志宁·快速导航：复用调度大屏/司机端同款悬浮球，点开才展开对话；锚点桌面右上角、窄屏右下角 -->
    <!-- 手机端（≤820px）顶栏导航已占一整行，悬浮球改锚右下角（:top 传空即回落 bottom 锚定、面板向上展开），避免压住导航条 -->
    <AiBall pos="fixed" :top="ballTop" right="18px" panel-width="380px" :label="t('AI志宁 · 快速导航')" draggable>
      <div class="ai-ball-chat">
        <p class="ai-cap">只能帮你打开功能、跳转导航与回答知识库问题；模拟/推演由调度大屏的 AI 助手负责</p>
        <div class="ai-msgs" ref="aiListEl">
          <div v-for="(m, i) in ai.messages" :key="i" class="ai-msg" :class="m.role">
            <div v-if="m.say && m.summary" class="ai-think">{{ m.say }}</div>
            <div class="ai-bubble" :class="{ err: m.error }" :title="m.channel ? '指令解析来源：' + chanLabel(m.channel) : null">{{ m.summary || m.say }}</div>
            <div v-if="m.sources && m.sources.length" class="ai-srcs">依据：<span v-for="s in m.sources" :key="s.id">{{ s.id }} {{ s.title }}</span></div>
          </div>
          <div v-if="ai.busy" class="ai-msg assistant"><div class="ai-bubble">解析中…</div></div>
        </div>
        <div class="ai-quicks">
          <button v-for="q in ai.quicks" :key="q" class="qchip" :disabled="ai.busy" @click="ask(q)">{{ q }}</button>
        </div>
        <form class="ai-input" @submit.prevent="ask()">
          <input v-model="ai.input" :disabled="ai.busy" placeholder="试试：打开司机端 / 导航去河内" />
          <button type="submit" :disabled="ai.busy || !ai.input.trim()">发送</button>
        </form>
      </div>
    </AiBall>

    <footer class="foot-bar mut">
      纯只读门户 · 数据均来自本服务后端接口 · 版本 {{ buildTag }}
    </footer>

    <!-- 登录/注册壳：顶栏「登录/注册」手动弹出（非首屏强弹）；演示阶段全功能免登录，游客态可体验包括 AI 导航在内的完整功能，登录仅为可选 -->
    <div v-if="auth.show" class="auth-mask">
      <div class="auth-card">
        <h3>{{ auth.mode === 'login' ? '登录任务门户' : '注册演示账号' }}</h3>
        <p class="mut">演示级账号：纯内存存储、服务重启即清空；不拦截大屏与司机端的直接访问。</p>
        <label>用户名 <input v-model.trim="auth.username" :disabled="auth.busy" @keyup.enter="submitAuth" placeholder="至少 2 个字符" /></label>
        <label>口令 <input v-model="auth.password" type="password" :disabled="auth.busy" @keyup.enter="submitAuth" placeholder="至少 4 位" /></label>
        <label>验证码
          <span class="auth-captcha">
            <input v-model.trim="auth.captcha" :disabled="auth.busy" @keyup.enter="submitAuth" maxlength="4" placeholder="4 位字符" />
            <img v-if="auth.captchaImg" :src="auth.captchaImg" alt="验证码" title="点击刷新" @click="loadCaptcha" />
            <button v-else type="button" @click="loadCaptcha">获取验证码</button>
          </span>
        </label>
        <p v-if="auth.msg" class="auth-err">{{ auth.msg }}</p>
        <div class="auth-btns">
          <button class="primary" :disabled="auth.busy" @click="submitAuth">{{ auth.mode === 'login' ? '登录' : '注册并登录' }}</button>
          <button :disabled="auth.busy" @click="auth.mode = auth.mode === 'login' ? 'reg' : 'login'">{{ auth.mode === 'login' ? '去注册' : '去登录' }}</button>
          <button v-if="!session.name" class="ghost" @click="guest">先逛逛（游客态）</button>
        </div>
        <p v-if="auth.accounts !== null" class="mut tiny">演示库当前 {{ auth.accounts }} 个账号（本服务实例，重启归零）</p>
      </div>
    </div>
  </div>
</template>

<script>
import { api, fmt } from './api.js'
import AiBall from '../components/AiBall.vue'
import { createUiTranslator, localizePlace, translate } from '../driver/i18n.js'

// 门户 AI 回包的 action → 前端动作。open/navigate 只跳页面，knowledge/say 只说话——
// 界面层同样没有任何沙盘/推演执行入口，与后端 PortalCopilotService 的收窄双保险。
const EXTERNAL_TARGET = { dashboard: '/dashboard.html', driver: '/driver.html' }

// 公告条在未拿到区域态势时的回落文案（门户能力描述，不含任何实时数字）。
// 同一句话写三种语言而不是丢给 DOM 翻译器逐词拆：整段口语拆词会拼出半中半英的句子，观感不如直接给写好的译文。
const NOTICE_FALLBACK_ZH = '志宁护航 · 面向东盟跨境物流的气象感知调度门户，已接入 CRA40 实况与官方通关公告，各视图数据均来自后端真实接口、可溯源。'
const NOTICE_FALLBACK_EN = 'ZHINING Escort · weather-aware dispatch portal for ASEAN cross-border logistics, fed by CRA40 observations and official customs bulletins; every view reads from real backend endpoints and is traceable.'
const NOTICE_FALLBACK_VI = 'ZHINING Escort · cổng điều phối cảm biến thời tiết cho logistics xuyên biên giới ASEAN, dùng quan trắc CRA40 và thông báo hải quan chính thức; mọi số liệu đều đọc từ backend thật và truy vết được.'

export default {
  components: { AiBall },
  data() {
    return {
      fmt,
      active: 'home',
      srcLabel: '',
      srcFresh: false,
      buildTag: (typeof __BUILD_TAG__ !== 'undefined' ? __BUILD_TAG__ : ''),
      // 区域态势手动刷新态：一次回源要拉满整张东盟 bbox 的格点（百 KB 级、秒级耗时），按钮必须防连点——
      // 后端的 refresh 只越节流窗口、不越熔断，前端再给一个无限点击入口没有意义
      aseanRefreshing: false,
      // 界面语言：简(zh) / EN / VI；复用 driver/i18n.js 的 DOM 整机翻译器，字典未命中即回落中文，可增量补齐
      uiLang: (() => { try { const v = localStorage.getItem('portal_ui_lang'); return v === 'en' || v === 'vi' ? v : 'zh' } catch (e) { return 'zh' } })(),
      langs: [{ id: 'zh', label: '简' }, { id: 'en', label: 'EN' }, { id: 'vi', label: 'VI' }],
      // 窄屏（手机）标志：驱动顶栏两行化与悬浮球换锚点，由 matchMedia 实时同步
      isNarrow: typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(max-width: 820px)').matches,
      // 身份壳：会话以服务端为准，本地 name 只做刷新前的即时展示
      session: { checked: false, name: '' },
      auth: { show: false, mode: 'login', username: '', password: '', captcha: '', captchaImg: '', msg: '', busy: false, accounts: null },
      // 门户 AI志宁·快速导航
      ai: {
        input: '',
        busy: false,
        quicks: ['打开司机端', '进入调度大屏', '导航去河内', '看看水运', '冷链货物要什么单证'],
        messages: [{
          role: 'assistant',
          say: '我是门户 AI志宁（快速导航）。可以说：打开司机端 / 导航去河内 / 冷链货物要什么单证。场景模拟、禁航推演这类操作由调度大屏的 AI 助手负责。',
          channel: 'rule'
        }]
      },
      // 每个只读视图的加载态
      states: {
        asean: { loading: false, error: '', loaded: false },
        weather: { loading: false, error: '', loaded: false },
        amri: { loading: false, error: '', loaded: false },
        waterway: { loading: false, error: '', loaded: false },
        customs: { loading: false, error: '', loaded: false }
      },
      data: {
        // 东盟态势：一个接口带回「城市 × 实况要素 × AMRI 单点指数 + 区域聚合」，row 为空对象表示尚未取到
        asean: { row: {} },
        weather: { servedSourceLabel: '', servedFresh: false, points: [] },
        amri: { model: null, live: null },
        water: { live: null, inter: null, st: null },
        customs: { bul: null, eff: [] }
      },
      views: [
        { id: 'home', label: '主页' },
        { id: 'asean', label: '东盟态势' },
        { id: 'weather', label: '实时气象' },
        { id: 'amri', label: 'AMRI 风险' },
        { id: 'waterway', label: '水运' },
        { id: 'customs', label: '通关时效' }
      ],
      // 顶栏右上角小功能菜单：内部视图切换 + 大屏/司机端外链直达（与卡片区同源）
      menu: [
        { id: 'home', label: '主页' },
        { id: 'dashboard', label: '调度大屏', external: '/dashboard.html' },
        { id: 'driver', label: '司机端', external: '/driver.html' },
        { id: 'asean', label: '东盟态势' },
        { id: 'weather', label: '实时气象' },
        { id: 'amri', label: 'AMRI 风险' },
        { id: 'waterway', label: '水运' },
        { id: 'customs', label: '通关时效' }
      ],
      cards: [
        { id: 'dashboard', title: '调度大屏', desc: '多路线协同调度操作台 · 算路/沙盘/触达', icon: '🖥️', color: '#4f6df5', external: '/dashboard.html' },
        { id: 'driver', title: '司机端', desc: '跨境货运司机气象预警与智能绕行助手', icon: '🚚', color: '#22b8cf', external: '/driver.html' },
        { id: 'asean', title: '东盟态势', desc: '东盟十国 + 中国枢纽热点城市实况与单点风险', icon: '🌏', color: '#0ea5e9' },
        { id: 'weather', title: '实时气象', desc: '沿线节点实况气象（只读 · 真实数据源）', icon: '🌡️', color: '#16a34a' },
        { id: 'amri', title: 'AMRI 风险', desc: '气象-路网可解释风险指数与当前最不利点', icon: '⚠️', color: '#f5a600' },
        { id: 'waterway', title: '水运', desc: '平陆运河支流风险 · 联运方案 · 通航接入', icon: '🛳️', color: '#0ea5e9' },
        { id: 'customs', title: '通关时效', desc: '官方通关公告（真实抓取）· 口岸过口时效', icon: '🛃', color: '#7c3aed' }
      ]
    }
  },
  computed: {
    // 首页概览卡：从各视图已加载的真实 data 派生摘要，不另拉接口、不臆造字段（缺数据时模板降级为 —/加载中）
    homeStat() {
      const pts = this.data.weather.points || []
      const wet = pts.length ? pts.reduce((a, b) => ((b.precipitationMm || 0) > (a.precipitationMm || 0) ? b : a), pts[0]) : null
      const worst = this.data.amri.live && this.data.amri.live.worst
      const bul = this.data.customs.bul
      const trib = (this.data.water.live && this.data.water.live.tributaries) || []
      const topTrib = trib.length ? trib.reduce((a, b) => ((b.probabilityPct || 0) > (a.probabilityPct || 0) ? b : a), trib[0]) : null
      return {
        weather: { nodes: pts.length, wet },
        amri: worst ? { index: worst.index, severity: worst.severity, node: worst.nodeName } : null,
        customs: bul && bul.ok ? { imp: bul.importHours, exp: bul.exportHours } : null,
        water: { topTrib }
      }
    },
    // 浏览器标签页标题随当前视图变化：「<视图名> - ZHINING 志宁」（home 显示「主页」，与顶栏菜单「主页」同名）
    // <title> 被 DOM 翻译器故意跳过（不影响页面可见文本），所以这里按语种自己取译文
    pageTitle() {
      const map = { home: '主页', asean: '东盟态势', weather: '实时气象', amri: 'AMRI 风险', waterway: '水运', customs: '通关时效' }
      const zh = map[this.active] || '志宁护航'
      if (this.uiLang === 'zh') return zh + ' - ZHINING 志宁'
      return translate(zh, this.uiLang) + ' - ZHINING'
    },
    // 公告条：区域态势到位后播报「本区域当前最不利城市」——文案全部来自后端同一份缓存，不另算一套；
    // 未到位（还没成功回源过 / 请求失败）时回落门户说明，绝不写死一个「看起来像实时」的风险数字
    noticeText() {
      const r = this.data.asean.row || {}
      const peak = r.regionPeak
      const lang = this.uiLang
      if (!peak || !r.count) {
        return lang === 'en' ? NOTICE_FALLBACK_EN : lang === 'vi' ? NOTICE_FALLBACK_VI : NOTICE_FALLBACK_ZH
      }
      const bands = r.severityBands || {}
      const hi = (bands.HIGH || 0) + (bands.CRITICAL || 0)
      const num = fmt.num(peak.index)
      const tail = r.stale
        ? (lang === 'en' ? ' (values from the last successful fetch, older than the configured window)'
          : lang === 'vi' ? ' (lấy từ lần về nguồn thành công gần nhất, đã quá cửa sổ quy định)'
            : '（读数取自最近一次成功回源，已超过设定时长）')
        : (lang === 'en' ? ` (source ${translate(r.sourceLabel || '—', 'en')})`
          : lang === 'vi' ? ` (nguồn ${translate(r.sourceLabel || '—', 'vi')})`
            : `（来源 ${r.sourceLabel || '—'}）`)
      // 同一条播报、同一批后端数字，只换描述语言；不因为切了语种就把风险等级改词重写
      if (lang === 'en') {
        return `ASEAN live situation: ${hi} of ${r.count} hotspot cities at HIGH or above; ` + `worst now ${this.pName(peak)} (${this.pCountry(peak)}) AMRI ${num} · ${peak.severity}, main hazard ${translate(peak.mainHazard, 'en')}` + tail
      }
      if (lang === 'vi') {
        return `Tình hình khu vực trực tiếp: ${hi}/${r.count} đô thị trọng điểm ở HIGH trở lên; ` + `bất lợi nhất lúc này ${this.pName(peak)} (${this.pCountry(peak)}) AMRI ${num} · ${peak.severity}, rủi ro chính ${translate(peak.mainHazard, 'vi')}` + tail
      }
      return `东盟区域实况：${r.count} 个热点城市中 ${hi} 个处于 HIGH 及以上，` + `当前最不利城市 ${this.pName(peak)}（${this.pCountry(peak)}）AMRI ${num} · ${peak.severity}，主灾种 ${peak.mainHazard}` + tail
    },
    // AiBall 的 top 为空即回落 bottom 锚定（桌面态仍是右上角 70px，行为不变）
    ballTop() { return this.isNarrow ? '' : '70px' }
  },
  watch: {
    // immediate：首屏就按当前视图设标题；后续切视图自动更新（调度大屏/司机端为外链，离开本页后由各自页面接管标题）
    pageTitle: { immediate: true, handler(t) { document.title = t } },
    // 打开登录/注册壳、或在两种模式间切换时，都取一张新验证码（答案与本次会话绑定、单次消费）
    'auth.show'(v) { if (v) this.loadCaptcha() },
    'auth.mode'() { this.loadCaptcha() }
  },
  methods: {
    st(id) { return this.states[id] || { loading: false, error: '', loaded: false } },
    // 专名（地名/国名/城市定位）按界面语言取值：vi 只认词典里本就收录的专名，其余走后端拉丁名。
    // 依赖 uiLang（响应式），切语言时这些列会跟着重算，不需要重建数据。
    pName(o) { return o ? localizePlace(o.name, this.uiLang, o.nameEn) : '' },
    pCountry(o) { return o ? localizePlace(o.country, this.uiLang, o.countryEn) : '' },
    pRole(o) { return o ? localizePlace(o.role, this.uiLang, o.roleEn) : '' },
    // 单条标签按当前语种取译文（zh 原样返回）。
    // 为什么卡片读数行必须走这里、而不能交给 DOM 整机翻译器：Vue 会把同一元素里的
    // 「静态标签 + 插值」合并成一个文本节点（实测形如 "气温 24.8℃ · 降水 0mm"），
    // DOM 层只做整串精确匹配，这种混合串必然未命中→回落中文（实测 EN 态残留 96 个中文节点）。
    // 这类行只能在渲染期把标签逐个译掉；纯静态文案仍由翻译器负责，不重复处理。
    t(s) { return s ? translate(s, this.uiLang) : s },
    aseanHeadSub() {
      const r = this.data.asean.row || {}
      const fresh = this.t(r.servedFresh ? '本次真实回源' : '节流窗口内缓存')
      return `${r.sourceLabel || '—'} · ${fresh} · ` +
        `${this.t('覆盖')} ${r.countries || 0} ${this.t('国')} · ${r.count || 0} ${this.t('城')}`
    },
    aseanPeakMeta() {
      const p = this.data.asean.row.regionPeak || {}
      return `${this.t('本区域当前最不利城市')} · ${this.t('主灾种')} ${this.t(p.mainHazard)}`
    },
    aseanErrorLine() {
      const r = this.data.asean.row || {}
      // 上游原始错误串不译也不遮掩：它就是排查时的第一手信息
      return `${this.t('区域取数异常')}: ${r.error || ''}${this.t('（已降级为上次成功结果，不以空表冒充「区域无风险」）')}`
    },
    aseanWx(c) {
      return `${this.t('气温')} ${fmt.num(c.temperatureC)}℃ · ${this.t('降水')} ${fmt.num(c.precipitationMm)}mm · ` +
        `${this.t('风速')} ${fmt.num(c.windKph)}km/h · ${this.t('能见度')} ${fmt.num(c.visibilityM, 0)}m`
    },
    aseanExt(c) {
      const line = `${this.t('日最高')} ${fmt.num(c.tempMaxC)}℃ · ${this.t('日最低')} ${fmt.num(c.tempMinC)}℃ · ` +
        `${this.t('云量')} ${fmt.num(c.cloudPct, 0)}%`
      // 低云量取不到就不写这一栏：不拿 0% 冒充「晴空无低云」
      if (c.lowCloudPct === null || c.lowCloudPct === undefined) return line
      return `${line} · ${this.t('低云量')} ${fmt.num(c.lowCloudPct, 0)}%`
    },
    aseanFc(c) {
      return `${this.t('未来3h 降水')} ${fmt.num(c.rainNext3hMm)}mm · ${this.t('未来6h 累计')} ${fmt.num(c.rainNext6hMm)}mm`
    },
    aseanExtNote() {
      const note = this.t((this.data.asean.row || {}).extendedElementNote || '')
      const heat = this.t('高温预警沿用 AMRI 气温项的标定中心 36℃（日最高温只作读数展示，不另起一套阈值）。')
      if (!note) return heat
      // 后端注脚本身不带句末标点（中文用「；」续句），非中文补一个句号，否则两句会黏在一起
      return this.uiLang === 'zh' ? `${note}；${heat}` : `${note}. ${heat}`
    },
    aseanStatLine() {
      const r = this.data.asean.row || {}
      const s = r.stats || {}
      // 审计计数照实上报：评委能从这里看出节流/熔断真的在工作，而不是每次都打上游
      return `${this.t('取数')} ${fmt.time(r.lastSuccessAt)} · ` +
        `${this.t('回源')} ${s.upstreamRequests || 0} / ${this.t('命中')} ${s.cacheHits || 0} / ${this.t('节流')} ${s.throttled || 0} · ` +
        `${this.t('本轮耗时')} ${r.elapsedMs || 0}ms`
    },
    go(v) {
      if (v.id === 'home') { this.active = 'home'; return }
      this.active = v.id
      this.ensure(v.id)
    },
    onMenu(m) {
      // 外链项（调度大屏/司机端）直接导航；其余切只读视图
      if (m.external) { window.location.href = m.external; return }
      this.go({ id: m.id })
    },
    openCard(c) {
      if (c.external) { window.location.href = c.external; return }
      this.active = c.id
      this.ensure(c.id)
    },
    reload(id) { this.states[id].loaded = false; this.ensure(id, true) },
    ensure(id, force) {
      const s = this.states[id]
      if (!s) return
      if (s.loaded && !force) return
      s.loading = true; s.error = ''
      this['load_' + id]().then(() => { s.loaded = true; s.loading = false })
        .catch((e) => { s.loading = false; s.error = (e && e.message) || '加载失败' })
    },
    // 各视图的只读加载：任一子请求失败不整体崩，界面按字段是否可用降级
    load_weather() {
      return Promise.all([api.weatherReal(), api.weatherSource()]).then(([real, src]) => {
        if (!real.ok) return Promise.reject(new Error('气象数据暂不可用：' + real.error))
        this.data.weather = {
          servedSourceLabel: real.data.servedSourceLabel || real.data.sourceLabel || '',
          servedFresh: !!real.data.servedFresh,
          points: real.data.points || []
        }
        if (src.ok) { this.srcLabel = src.data.currentLabel || ''; this.srcFresh = !!src.data.servedFresh }
      })
    },
    load_amri() {
      return Promise.all([api.riskModel(), api.riskModelLive('NN', 'HN')]).then(([m, l]) => {
        if (!m.ok) return Promise.reject(new Error('风险模型规格暂不可用：' + m.error))
        this.data.amri = { model: (m.data && m.data.model) || null, live: l.ok ? l.data : null }
      })
    },
    load_waterway() {
      return Promise.all([api.tributariesLive(), api.intermodal(), api.waterwayStatus()]).then(([t, i, s]) => {
        if (!t.ok) return Promise.reject(new Error('支流风险暂不可用：' + t.error))
        this.data.water = {
          live: t.data,
          inter: i.ok ? i.data : null,
          st: s.ok ? s.data : null
        }
      })
    },
    load_asean() {
      return api.aseanSituation(false).then((r) => {
        if (!r.ok) return Promise.reject(new Error('区域态势暂不可用：' + r.error))
        const row = r.data || {}
        this.data.asean.row = row
        // 一次都没成功回源过（上游不可达且无历史缓存）时如实失败：
        // 空表渲染成「23 城全是 LOW」会被读成「区域无风险」，那是最坏的一种假数据
        if (!row.count) {
          return Promise.reject(new Error('区域态势暂无观测数据：' + (row.error || '上游未返回格点场')))
        }
      })
    },
    refreshAsean() {
      if (this.aseanRefreshing) return
      this.aseanRefreshing = true
      api.aseanSituation(true)
        .then((r) => {
          this.aseanRefreshing = false
          if (r.ok && r.data && r.data.count) this.data.asean.row = r.data
        })
        .catch(() => { this.aseanRefreshing = false })
    },
    load_customs() {
      return Promise.all([api.customsBulletin(), api.customsEfficiency()]).then(([b, e]) => {
        if (!e.ok) return Promise.reject(new Error('口岸时效暂不可用：' + e.error))
        this.data.customs = { bul: b.ok ? b.data : { ok: false, error: b.error }, eff: e.data || [] }
      })
    },
    // ---- 身份壳 ----
    refreshSession() {
      return api.authSession().then((r) => {
        this.session.checked = true
        if (r.ok && r.data && r.data.ok) { this.session.name = r.data.name; this.auth.accounts = r.data.accounts ?? null }
        else { this.session.name = '' }
      })
    },
    submitAuth() {
      const u = this.auth.username, p = this.auth.password, c = this.auth.captcha
      if (!u || !p) { this.auth.msg = '请填写用户名与口令'; return }
      if (!c) { this.auth.msg = '请输入验证码'; return }
      this.auth.busy = true; this.auth.msg = ''
      const call = this.auth.mode === 'login' ? api.authLogin(u, p, c) : api.authRegister(u, p, c)
      call.then((r) => {
        this.auth.busy = false
        const d = r.data || {}
        if (r.ok && d.ok) {
          this.session.name = d.name
          this.auth.accounts = d.accounts ?? this.auth.accounts
          this.auth.show = false; this.auth.password = ''; this.auth.captcha = ''
        } else {
          this.auth.msg = d.message || r.error || (this.auth.mode === 'login' ? '登录失败' : '注册失败')
          if (typeof d.accounts === 'number') this.auth.accounts = d.accounts
          // 验证码单次消费：无论对错这次用的码已作废，清空并换一张
          this.auth.captcha = ''
          this.loadCaptcha()
        }
      })
    },
    loadCaptcha() {
      this.auth.captcha = ''
      api.authCaptcha().then((r) => { if (r.ok && r.data && r.data.image) this.auth.captchaImg = r.data.image })
    },
    guest() {
      // 游客态：只收起登录壳，不强拦任何视图/外链——比赛演示链路（大屏/司机端）必须免登录可达
      this.auth.show = false
    },
    doLogout() {
      api.authLogout().then(() => { this.session.name = ''; this.auth.show = true })
    },
    // ---- 门户 AI志宁·快速导航 ----
    chanLabel(ch) {
      if (!ch) return '规则解析'
      if (String(ch).startsWith('local')) return '本地大模型'
      if (String(ch).startsWith('online')) return '在线 API'
      return '规则解析'
    },
    // 切换界面语言：更新选中态、持久化到本地，并驱动整机翻译器
    setLang(id) {
      if (id === this.uiLang) return
      this.uiLang = id
      try { localStorage.setItem('portal_ui_lang', id) } catch (e) { /* 忽略 */ }
      if (this._uiTranslator) this._uiTranslator.setLang(id)
    },
    ask(text) {
      const msg = (text ?? this.ai.input).trim()
      if (!msg || this.ai.busy) return
      // 游客态即可用：门户 AI 快速导航不再要求登录（后端 /api/copilot/portal 本就无鉴权），演示阶段全功能免登录体验
      this.ai.input = ''
      this.ai.messages.push({ role: 'user', say: msg })
      this.ai.busy = true
      this.aiScroll()
      api.portalChat(msg).then((r) => {
        this.ai.busy = false
        if (!r.ok || !r.data) {
          this.ai.messages.push({ role: 'assistant', say: '请求失败：' + (r.error || '服务未就绪'), channel: null, error: true })
          return
        }
        const d = r.data
        const isKnowledge = d.action === 'knowledge'
        const item = {
          role: 'assistant',
          say: d.say || '',
          // 知识库回复：主气泡给结论 summary，say 降为思考过程行（与大屏同一主次约定）
          summary: isKnowledge && d.data && d.data.summary ? d.data.summary : '',
          sources: isKnowledge && d.data && Array.isArray(d.data.sources) ? d.data.sources : [],
          channel: d.channel
        }
        this.ai.messages.push(item)
        this.aiScroll()
        this.execPortalAction(d)
      })
    },
    execPortalAction(d) {
      const args = d.args || {}
      // 给跳转留一拍，先让用户看见 AI 的确认气泡再切页
      if (d.action === 'open') {
        const t = args.target
        if (!t) return
        setTimeout(() => {
          if (EXTERNAL_TARGET[t]) { window.location.href = EXTERNAL_TARGET[t]; return }
          const v = this.views.find((x) => x.id === t)
          if (v) this.go(v)
        }, 600)
      } else if (d.action === 'navigate') {
        const dest = args.destination || ''
        if (!dest) return
        setTimeout(() => { window.location.href = '/driver.html?nav=' + encodeURIComponent(dest) }, 600)
      }
    },
    aiScroll() {
      this.$nextTick(() => {
        const el = this.$refs.aiListEl
        if (el) el.scrollTop = el.scrollHeight
      })
    }
  },
  beforeUnmount() {
    // 界面语言翻译器：离开门户时断开 MutationObserver，避免对已卸载 DOM 继续观察
    if (this._uiTranslator) { this._uiTranslator.destroy(); this._uiTranslator = null }
    if (this._mq && this._onMq) {
      // Safari <14 只有 addListener，两式都兼容写
      this._mq.removeEventListener ? this._mq.removeEventListener('change', this._onMq) : this._mq.removeListener(this._onMq)
      this._mq = null; this._onMq = null
    }
  },
  mounted() {
    // 窄屏监听：旋转/分屏时同步 isNarrow（顶栏两行化与悬浮球锚点都跟着它）
    if (window.matchMedia) {
      this._mq = window.matchMedia('(max-width: 820px)')
      this._onMq = (e) => { this.isNarrow = e.matches }
      this._mq.addEventListener ? this._mq.addEventListener('change', this._onMq) : this._mq.addListener(this._onMq)
      this.isNarrow = this._mq.matches
    }
    // 界面语言：建 DOM 整机翻译器（简/EN/VI，复用 driver/i18n.js）；字典未命中的中文原样回落
    this._uiTranslator = createUiTranslator(() => this.$el)
    if (this.uiLang !== 'zh') this._uiTranslator.setLang(this.uiLang)
    // 深链回跳：AI 导航失败/用户从司机端回门户时可用 #view=xxx 直指某视图
    const mv = /[#&?]view=(\w+)/.exec(window.location.hash + window.location.search)
    // 身份检查：只拉一次会话决定顶栏显身份还是显「登录/注册」，不自动弹壳——未登录默认游客态
    this.refreshSession()
    if (mv) {
      const v = this.views.find((x) => x.id === mv[1])
      // home 不算真的深链目标（它就是默认视图）：这里必须不能 return，
      // 否则会跳过下面公告条要的 asean 预取，首页通知栏会永久停在回落文案
      if (v && v.id !== 'home') { this.active = v.id; this.ensure(v.id); return }
    }
    // 顶栏数据源小徽标：轻量拉一次 source，失败不影响门户
    api.weatherSource().then((r) => {
      if (r.ok) { this.srcLabel = r.data.currentLabel || ''; this.srcFresh = !!r.data.servedFresh }
    })
    // 首页概览卡：预取各视图的真实数据（ensure 已去重，后续切到对应视图不重复拉）；
    //  ASEAN 一并预取，是因为公告条要播报区域最不利城市——它得和面板同一份数据，不能各拉各的
    if (this.active === 'home') { this.ensure('asean'); this.ensure('weather'); this.ensure('amri'); this.ensure('customs'); this.ensure('waterway') }
  }
}
</script>

<style scoped>
.portal { min-height: 100vh; display: flex; flex-direction: column; color: #1e2b45; background: #fff; overflow-x: clip;
  font-family: system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif; }
/* 顶栏 */
.topbar { display: flex; align-items: center; gap: 18px; padding: 12px 22px;
  background: #fff;
  border-bottom: 1px solid #e6ecf7; box-shadow: 0 1px 8px rgba(20,40,80,.05); position: sticky; top: 0; z-index: 10; }
.brand { display: flex; flex-direction: row; align-items: center; gap: 10px; cursor: pointer; user-select: none; }
.brand-lock { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.logo-pin { display: flex; align-items: center; filter: drop-shadow(0 2px 5px rgba(0,0,0,.28)); }
.brand-txt { display: flex; flex-direction: column; align-items: center; line-height: 1.05; }
.brand-txt b { font-size: 11px; font-weight: 800; letter-spacing: 1.5px; padding-left: 1.5px;
  background: linear-gradient(100deg, #ff2d55, #ff8a00, #ffd400, #16a34a, #00c2ff, #2b6bff, #7c3aed);
  -webkit-background-clip: text; background-clip: text; color: transparent; -webkit-text-fill-color: transparent; }
.brand-txt i { font-size: 7px; font-weight: 700; font-style: normal; letter-spacing: 1.5px; padding-left: 1.5px;
  background: linear-gradient(100deg, #ff8a00, #ffd400, #16a34a, #00c2ff, #7c3aed);
  -webkit-background-clip: text; background-clip: text; color: transparent; -webkit-text-fill-color: transparent; }
/* 右侧黑体字标：腾讯官网式一行横排（英文在前、中文在后，基线对齐）——换用系统内置最接近腾讯字标的几何重黑体（英文 Arial Black、中文 黑体 SimHei）+ 加粗 900 */
.brand-word { display: flex; flex-direction: row; align-items: baseline; gap: 6px; flex: none; white-space: nowrap; }
.brand-word b { font-family: 'SimHei', 'Microsoft YaHei', 'PingFang SC', sans-serif; font-size: 19px; font-weight: 900; letter-spacing: 1px; white-space: nowrap; }
.brand-word i { font-family: 'Arial Black', 'Arial Bold', Gadget, 'Microsoft YaHei', sans-serif; font-size: 19px; font-style: normal; font-weight: 900; letter-spacing: .5px; white-space: nowrap; }
/* 字标配色：亮蓝色（橙色又换回亮蓝）——蓝调渐变描字、不加动画 */
.brand-word b, .brand-word i { background: linear-gradient(120deg, #3aa0ff, #1f6fe0); -webkit-background-clip: text; background-clip: text; color: transparent; -webkit-text-fill-color: transparent; }
.nav { display: flex; gap: 4px; margin-left: auto; flex-wrap: wrap; justify-content: flex-end; }
.nav-btn { padding: 7px 14px; border-radius: 999px; border: 1px solid transparent; cursor: pointer;
  background: #f0f4fb; color: #33435f; font-size: 14px; font-weight: 700; transition: .15s; }
.nav-btn:hover { background: #e3ecfb; }
.nav-btn.on { background: #2b62d9; color: #fff; font-weight: 800; }
.src-chip { font-size: 12px; color: #4a5c7c; font-weight: 600; display: flex; align-items: center; gap: 6px;
  background: #eef3fb; padding: 5px 11px; border-radius: 999px; }
.src-chip .dot { width: 8px; height: 8px; border-radius: 50%; }
.src-chip .dot.ok { background: #35d07f; } .src-chip .dot.cache { background: #f5a600; }
/* 右上角语言切换：简 / EN / VI（当前语言蓝字+下划线，其余灰字） */
.lang-switch { display: flex; align-items: center; gap: 6px; flex: none; }
.lang-seg { position: relative; border: none; background: transparent; cursor: pointer;
  font-size: 14px; font-weight: 700; color: #9aa6bb; padding: 2px 2px 5px; line-height: 1; transition: .15s; }
.lang-seg:hover { color: #2b62d9; }
.lang-seg.on { color: #1f6fe0; }
.lang-seg.on::after { content: ''; position: absolute; left: 0; right: 0; bottom: 0; height: 2px; border-radius: 2px; background: #1f6fe0; }
/* 身份壳顶栏入口 */
.user-chip { font-size: 13px; color: #33406a; display: flex; align-items: center; gap: 8px;
  background: #eef3fb; padding: 5px 12px; border-radius: 999px; }
.user-chip .logout { border: none; background: transparent; color: #7c8aa5; cursor: pointer; font-size: 12px; text-decoration: underline; }
.user-chip .logout:hover { color: #2b62d9; }
.loginlink { border: 1px solid #c9d8f2; background: #fff; color: #33406a; font-weight: 600;
  padding: 5px 14px; border-radius: 999px; cursor: pointer; font-size: 13px; }
.loginlink:hover { background: #f0f4fb; }
/* 主体 */
/* 主体：box-sizing 必须显式声明——门户未引 style.css、全站没有 border-box 复位，
   否则 width:100% 再加左右内边距会让内容盒超出视口，多出的部分被 .portal 的 overflow-x: clip 无声裁掉（手机上尤其明显） */
.body { flex: 1; box-sizing: border-box; padding: 22px 26px; max-width: 1200px; width: 100%; margin: 0 auto; }
/* 首页：公告条 + 暂用开屏动画填充的空白区 */
.home { min-height: 52vh; }
/* ② 首页空白区：司机端开屏动画同款（致力致宁逐字浮现+流光、进度条、标语），仅把全屏 fixed 遮罩改为填满首页内容区，视觉参数原样照搬 */
.home-boot { position: relative; overflow: hidden; min-height: calc(100vh - 200px); display: flex; flex-direction: column; align-items: center; justify-content: center;
  width: 100vw; margin-left: calc(50% - 50vw); border-radius: 0; background: linear-gradient(180deg, #ffffff 0%, #f2f8ff 62%, #e7f1ff 100%); }
.home-boot::before { content: ''; position: absolute; left: -20%; top: -30%; width: 80%; height: 80%; background: radial-gradient(circle, rgba(59,130,246,.16), transparent 62%); animation: hb-float 7s ease-in-out infinite alternate; }
.home-boot::after { content: ''; position: absolute; right: -25%; bottom: -35%; width: 85%; height: 85%; background: radial-gradient(circle, rgba(14,165,233,.14), transparent 62%); animation: hb-float 9s ease-in-out infinite alternate-reverse; }
@keyframes hb-float { from { transform: translate(0,0); } to { transform: translate(60px, 50px); } }
.hb-inner { position: relative; z-index: 1; display: flex; flex-direction: column; align-items: center; }
.hb-word { display: grid; grid-template-columns: repeat(4, auto); gap: 14px 12px; align-items: center; }
.hb-word .bc { display: inline-block; font-size: 76px; font-weight: 800; line-height: 1; letter-spacing: 2px; opacity: 0; background: linear-gradient(120deg, #ff2d55, #f5a600, #16a34a, #0ea5e9, #7c3aed, #ff2d55); background-size: 280% 100%; -webkit-background-clip: text; background-clip: text; color: transparent; -webkit-text-fill-color: transparent; animation: hb-reveal 5.2s cubic-bezier(.2,.7,.3,1) infinite, hb-shine 3.6s linear infinite; }
.hb-word .bc.k1 { grid-column: 1; grid-row: 1; animation-delay: .20s, 2.2s; }
.hb-word .bc.k2 { grid-column: 2; grid-row: 1; animation-delay: .45s, 2.2s; }
.hb-word .bc.k3 { grid-column: 2; grid-row: 2; animation-delay: .95s, 2.2s; }
.hb-word .bc.k4 { grid-column: 3; grid-row: 2; animation-delay: 1.20s, 2.2s; }
.hb-word .bc.k5 { grid-column: 4; grid-row: 2; align-self: end; font-size: 44px; animation-delay: 1.45s, 2.2s; }
.hb-word .bc.k6 { grid-column: 3; grid-row: 1; align-self: end; font-size: 44px; animation-delay: .70s, 2.2s; }
@keyframes hb-reveal { 0% { opacity: 0; transform: translateY(28px) scale(.82); filter: blur(10px); } 14%, 82% { opacity: 1; transform: none; filter: blur(0); } 100% { opacity: 0; transform: translateY(-18px) scale(.9); filter: blur(8px); } }
@keyframes hb-shine { 0% { background-position: 220% 0; } 100% { background-position: -220% 0; } }
.hb-slogan { margin-top: 22px; font-size: 14px; letter-spacing: 6px; color: rgba(30,41,59,.66); opacity: 0; animation: hb-fade 1s ease 1.3s forwards; }
@keyframes hb-fade { to { opacity: 1; } }
.hb-bar { position: relative; margin-top: 30px; width: 190px; height: 4px; border-radius: 4px; background: rgba(15,23,42,.10); overflow: hidden; opacity: 0; animation: hb-fade .8s ease 1.5s forwards; }
.hb-bar i { position: absolute; top: 0; left: -42%; width: 42%; height: 100%; border-radius: 4px; background: linear-gradient(90deg, transparent, #f5a600, #16a34a, #0ea5e9, #7c3aed, transparent); animation: hb-slide 1.35s ease-in-out infinite; }
@keyframes hb-slide { 0% { left: -42%; } 100% { left: 100%; } }
.hb-tip { margin-top: 16px; font-size: 12px; letter-spacing: 1px; color: rgba(30,41,59,.55); opacity: 0; animation: hb-fade .8s ease 1.7s forwards; }
.hb-brand { position: absolute; bottom: 26px; left: 0; right: 0; text-align: center; font-size: 11px; letter-spacing: 3px; color: rgba(30,41,59,.4); }
@media (prefers-reduced-motion: reduce) { .hb-word .bc { opacity: 1; animation: hb-shine 3.6s linear infinite; } .hb-bar i { animation: hb-slide 1.35s ease-in-out infinite; } .home-boot::before, .home-boot::after { animation: none; } }
/* ① 公告条 */
.notice { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 11px 16px; margin-bottom: 22px;
  background: linear-gradient(90deg, #eef4ff, #f7fbff); border: 1px solid #dbe8fb; border-left: 4px solid #2b62d9; border-radius: 10px; }
.nc-tag { flex: none; font-size: 12px; font-weight: 700; color: #fff; background: #2b62d9; padding: 3px 10px; border-radius: 999px; }
.nc-txt { font-size: 14px; color: #2a3a58; flex: 1; min-width: 240px; }
.nc-src { font-size: 12px; color: #5a6b88; display: flex; align-items: center; gap: 6px; }
.nc-src .dot { width: 8px; height: 8px; border-radius: 50%; background: #f5a600; }
.nc-src .dot.ok { background: #35d07f; }
/* 首页“运行概览 + 服务入口”两块的样式已随内容移除，明日设首页时重新设 */
/* 门户 AI志宁·快速导航（聊天样式供右上角 AiBall 默认插槽内容复用） */
.ai-cap { font-size: 12px; margin: 0 0 8px; }
.ai-msgs { max-height: 220px; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; padding: 4px 2px; }
.ai-msg { display: flex; flex-direction: column; }
.ai-msg.user { align-items: flex-end; }
.ai-msg.assistant { align-items: flex-start; }
.ai-bubble { max-width: 78%; padding: 8px 13px; border-radius: 12px; font-size: 15px; line-height: 1.55; }
.ai-msg.user .ai-bubble { background: #4f9bff; color: #04122b; }
.ai-msg.assistant .ai-bubble { background: #f6f9ff; color: #1e2b45; }
.ai-msg.assistant .ai-bubble.err { background: #3a1620; color: #ffb0b0; }
.ai-think { font-size: 12px; font-style: italic; color: #9db8e8; margin-bottom: 3px; }
.ai-srcs { font-size: 11px; color: #93b2e6; margin-top: 4px; }
.ai-quicks { display: flex; gap: 6px; flex-wrap: wrap; margin: 8px 0; }
.qchip { padding: 4px 12px; border-radius: 999px; border: 1px dashed rgba(140,175,240,.45);
  background: transparent; color: #cfe0ff; font-size: 12px; cursor: pointer; }
.qchip:hover:not(:disabled) { background: rgba(255,255,255,.1); }
.qchip:disabled { opacity: .5; cursor: default; }
.ai-input { display: flex; gap: 8px; }
.ai-input input { flex: 1; padding: 9px 13px; border-radius: 10px; border: 1px solid rgba(140,175,240,.35);
  background: rgba(6,16,34,.55); color: #e8eefc; font-size: 14px; outline: none; }
.ai-input input:focus { border-color: #4f9bff; }
.ai-input button { padding: 9px 20px; border-radius: 10px; border: none; cursor: pointer; font-size: 14px;
  background: linear-gradient(90deg,#4f9bff,#4f6df5); color: #fff; font-weight: 600; }
.ai-input button:disabled { opacity: .55; cursor: default; }
/* 只读视图 */
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.span2 { grid-column: 1 / -1; }
.card-blk { background: #f8fbff; color: #1e2b45; border-radius: 14px; padding: 16px 18px; border: 1px solid #e2ebf9; box-shadow: 0 2px 10px rgba(20,40,80,.05); }
.card-blk h3 { margin: 0 0 12px; font-size: 16px; display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.mut { color: #7c8aa5; font-size: 12px; font-weight: 400; }
.small { font-size: 12px; }
.tip { padding: 40px; text-align: center; color: #7c8aa5; font-size: 15px; }
.tip.err { color: #c02626; }
.retry { margin-left: 12px; padding: 4px 14px; border-radius: 8px; border: 1px solid #2b62d9; background: #fff; color: #2b62d9; cursor: pointer; }
/* 表格 */
.tb { width: 100%; border-collapse: collapse; font-size: 13px; }
.tb th { text-align: left; color: #5a6b88; font-weight: 600; padding: 6px 8px; border-bottom: 2px solid #dbe6fb; }
.tb td { padding: 6px 8px; border-bottom: 1px solid #eaf1fd; }
.tb.sm { font-size: 12px; margin-top: 10px; }
.tb .nm { font-weight: 600; }
.tb .warn { color: #d97706; font-weight: 700; }
.foot { margin: 12px 0 0; } .foot.err { color: #c02626; }
a { color: #2b62d9; }
/* AMRI */
.formula { background: #eef4ff; border-radius: 8px; padding: 8px 10px; font-family: ui-monospace, Consolas, monospace; font-size: 12px; color: #33406a; }
.bands { display: flex; gap: 6px; flex-wrap: wrap; margin: 8px 0; }
.band { font-size: 11px; padding: 3px 9px; border-radius: 999px; color: #fff; }
.band.CRITICAL { background: #dc2626; } .band.HIGH { background: #ea8c00; } .band.MEDIUM { background: #d4b106; } .band.LOW { background: #16a34a; }
.worst { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; }
.w-idx { font-size: 46px; font-weight: 800; line-height: 1; padding: 6px 16px; border-radius: 12px; color: #fff; background: #16a34a; }
.w-idx.sev-HIGH { background: #ea8c00; } .w-idx.sev-CRITICAL { background: #dc2626; } .w-idx.sev-MEDIUM { background: #d4b106; }
.w-meta { display: flex; flex-direction: column; gap: 4px; }
.sev-tag { font-size: 11px; padding: 2px 8px; border-radius: 6px; color: #fff; background: #16a34a; align-self: flex-start; }
.sev-tag.sev-HIGH { background: #ea8c00; } .sev-tag.sev-CRITICAL { background: #dc2626; } .sev-tag.sev-MEDIUM { background: #d4b106; }
.contrib { display: flex; align-items: center; gap: 8px; margin: 5px 0; font-size: 13px; }
.contrib .cl { width: 52px; color: #5a6b88; } .contrib .cv { width: 88px; text-align: right; color: #33406a; }
.cbar { flex: 1; height: 8px; background: #e6eefc; border-radius: 6px; overflow: hidden; }
.cbar i { display: block; height: 100%; background: linear-gradient(90deg,#4f9bff,#7c3aed); }
/* 东盟态势：城市卡网格。auto-fill + minmax 让列数随宽度自适应（窄屏自动塌成单列，不必再写一份 media query） */
.acities { display: grid; grid-template-columns: repeat(auto-fill, minmax(212px, 1fr)); gap: 10px; }
.aci { background: #fff; border: 1px solid #e2ebf9; border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 6px; }
.aci-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.aci-nm { font-size: 15px; }
/* 指数角标沿用 .w-idx 的色阶，只是缩到卡片尺寸——同一套风险色在两个视图里必须完全一致 */
.aci-idx { flex: none; font-size: 17px; font-weight: 800; line-height: 1; padding: 3px 8px; border-radius: 8px; color: #fff; background: #16a34a; }
.aci-idx.sev-MEDIUM { background: #d4b106; } .aci-idx.sev-HIGH { background: #ea8c00; } .aci-idx.sev-CRITICAL { background: #dc2626; }
.aci-tags { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.aci-tags .hz { font-size: 11px; padding: 2px 8px; border-radius: 6px; background: #eef3fb; color: #33435f; }
.cbadge { font-size: 11px; padding: 2px 7px; border-radius: 6px; background: #2b62d9; color: #fff; }
.aci-wx { font-size: 11px; line-height: 1.6; }
/* 水运 */
.trib { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.titem { background: #eef4ff; border-radius: 10px; padding: 10px 12px; }
.t-head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
.tbar { position: relative; height: 14px; background: #dce7fb; border-radius: 7px; margin: 8px 0 4px; overflow: hidden; }
.tbar i { display: block; height: 100%; background: linear-gradient(90deg,#22b8cf,#0ea5e9); }
.tbar em { position: absolute; right: 6px; top: 0; font-style: normal; font-size: 11px; color: #0b3a53; line-height: 14px; }
.leg { display: flex; align-items: center; gap: 10px; padding: 6px 0; border-bottom: 1px dashed #dbe6fb; font-size: 13px; }
.leg.total { border-bottom: none; font-weight: 700; }
.lg-mode { min-width: 46px; font-weight: 600; color: #0e7490; } .lg-route { flex: 1; } .lg-num { color: #5a6b88; }
.kv { display: grid; grid-template-columns: auto 1fr; gap: 6px 14px; margin: 0; font-size: 13px; }
.kv dt { color: #5a6b88; } .kv dd { margin: 0; }
/* 通关 */
.big2 { display: flex; gap: 26px; margin-bottom: 10px; }
.big2 > div { display: flex; flex-direction: column; } .big2 b { font-size: 30px; color: #7c3aed; } .big2 span { font-size: 12px; }
.quote { margin: 8px 0; padding: 10px 12px; background: #f2ecff; border-left: 3px solid #7c3aed; border-radius: 6px; font-size: 13px; color: #3b2e63; }
.foot-bar { padding: 10px 26px; font-size: 12px; text-align: center; background: #f5f8fd; border-top: 1px solid #e6ecf7; }
/* 登录/注册壳 */
.auth-mask { position: fixed; inset: 0; z-index: 50; background: rgba(4,10,24,.72); backdrop-filter: blur(3px);
  display: flex; align-items: center; justify-content: center; }
.auth-card { width: min(400px, 92vw); background: #f6f9ff; color: #1e2b45; border-radius: 16px; padding: 22px 24px;
  display: flex; flex-direction: column; gap: 10px; box-shadow: 0 24px 60px rgba(0,0,0,.5); }
.auth-card h3 { margin: 0; font-size: 18px; }
.auth-card label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: #5a6b88; }
.auth-card input { padding: 9px 12px; border-radius: 9px; border: 1px solid #c9d8f2; font-size: 14px; color: #1e2b45; background: #fff; }
.auth-card input:focus { outline: none; border-color: #4f6df5; }
.auth-captcha { display: flex; align-items: center; gap: 8px; }
.auth-captcha input { flex: 1; }
.auth-captcha img { height: 38px; width: 114px; border-radius: 6px; border: 1px solid #c9d8f2; cursor: pointer; background: #f6f9ff; }
.auth-captcha button { height: 38px; padding: 0 12px; border: 1px dashed #c9d8f2; background: #fff; color: #5a6b88; border-radius: 6px; cursor: pointer; font-size: 12px; white-space: nowrap; }
.auth-err { color: #c02626; font-size: 13px; margin: 0; }
.auth-btns { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 4px; }
.auth-btns button { padding: 8px 16px; border-radius: 9px; border: 1px solid #c9d8f2; background: #fff; color: #33406a; cursor: pointer; font-size: 14px; }
.auth-btns button.primary { background: linear-gradient(90deg,#4f6df5,#4f9bff); border: none; color: #fff; font-weight: 700; }
.auth-btns button.ghost { border-style: dashed; color: #7c8aa5; }
.auth-btns button:disabled { opacity: .55; cursor: default; }
.tiny { font-size: 11px; }
/* ===== 手机端适配（≤820px）=====
   门户原本是纯桌面一行式顶栏：品牌 + 7 个导航 + 数据源 + 语言切换 + 登录。手机上放不下时 .nav 会折成
   右侧竖排一列，把后面的控件顶出视口右缘，而 .portal 的 overflow-x: clip 又将其裁掉且不可横滚
   ——表现为「语言切换 / 登录注册在手机上根本看不见」。这里把顶栏拆成「品牌行 + 导航横滑行」，溢出只留在 .nav 内部滚动。 */
@media (max-width: 820px) {
  .grid, .trib { grid-template-columns: 1fr; }
  .topbar { flex-wrap: wrap; gap: 8px 10px; padding: 10px 14px; }
  /* 竖排组合标里已经写了 志宁 / ZHINING，横排字标是同义重复 → 窄屏收起，给语言切换和登录腾宽度 */
  .brand-word { display: none; }
  .brand { order: 1; margin-right: auto; }
  .src-chip { order: 2; max-width: 108px; padding: 5px 9px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  .lang-switch { order: 3; }
  .user-chip { order: 4; padding: 5px 10px; font-size: 12px; white-space: nowrap; }
  .loginlink { order: 4; padding: 5px 11px; font-size: 12px; white-space: nowrap; }
  /* 导航独占整行、横向滑动：不换行就不会挤成竖列，也不会有元素被顶到屏外 */
  .nav { order: 5; flex-basis: 100%; margin-left: 0; justify-content: flex-start; flex-wrap: nowrap;
    overflow-x: auto; -webkit-overflow-scrolling: touch; scrollbar-width: none; padding-bottom: 4px; }
  .nav::-webkit-scrollbar { display: none; }
  .nav-btn { flex: none; padding: 6px 13px; font-size: 13px; }
  .body { padding: 14px; }
  /* 首页开屏：4 列 × 76px 字在 390px 屏上远超屏宽、右半被裁 → 缩字号与标点尺寸 */
  .home-boot { min-height: calc(100vh - 250px); }
  .hb-word { gap: 10px 8px; }
  .hb-word .bc { font-size: 40px; letter-spacing: 1px; }
  .hb-word .bc.k5, .hb-word .bc.k6 { font-size: 24px; }
  .hb-slogan { margin-top: 16px; font-size: 12px; letter-spacing: 3px; }
  .hb-bar { width: 150px; margin-top: 22px; }
  /* 宽表（实时气象 8 列、通关 5 列）改卡片内横向滚动，单元格不换行才能拖出右侧列 */
  .card-blk { padding: 13px 12px; overflow-x: auto; -webkit-overflow-scrolling: touch; }
  .tb { font-size: 12px; }
  .tb th, .tb td { padding: 6px 6px; white-space: nowrap; }
  /* 公告条改两行栅格：标签 + 正文占一行、数据源与版本另起一行（否则正文被 flex 挤成窄条、竖排八行） */
  .notice { display: grid; grid-template-columns: auto 1fr; align-items: start; gap: 6px 8px; padding: 10px 12px; }
  .nc-txt { font-size: 13px; min-width: 0; }
  .nc-src { grid-column: 1 / -1; }
  .w-idx { font-size: 34px; padding: 4px 12px; }
  .contrib .cl { width: 46px; } .contrib .cv { width: 74px; }
  .big2 { gap: 16px; } .big2 b { font-size: 24px; }
  .foot-bar { padding: 10px 14px; }
  /* 登录壳：验证码图 114px + 输入框在窄屏偏挤，图收小一点 */
  .auth-captcha img { width: 96px; }
}
</style>
