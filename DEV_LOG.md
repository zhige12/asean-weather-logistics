# 面向东盟跨境物流的突发气象预警与多路线协同调度平台 · 开发工作日志

> 记录项目从零到初赛演示版本的全过程，供答辩与后续迭代参考。

---

## 一、项目定位

面向 **中国（广西南宁）→ 越南（河内）** 跨境公路物流场景的调度平台：

- **智能路线规划**：越南真实 OSM 路网（motorway/trunk/primary/secondary）上 Dijkstra 最短路径 + 气象风险惩罚动态绕行
- **气象风险评估**：Open-Meteo / wttr.in 实时气象 → 风险路段权重
- **AI 灾害预测**：实时气象喂给 DeepSeek，预测未来 6-12 小时灾害并注入风险
- **Agent 实时守护**：风险变化后毫秒级重算路线，SSE 推送新路线到调度大屏和司机端
- **中越双语预警 + 行业知识库 RAG**
- **离线可用的底图能力**：默认走天地图在线瓦片（后端同源代理 + 内存/磁盘两级缓存，磁盘缓存在 `data/tile-cache/{img_w,vec_w,cia_w,cva_w}`），降级末级为包内 EPSG:4326 栅格瓦片 `/tiles/{z}/{x}/{y}.jpg`，断网仍有底图。（早期的 planetiler MVT 矢量瓦片 + MapLibre 渲染方案已弃用，见 §五）

---

## 二、各阶段工作内容

### 1. 数据处理（tools/，Python）

| 脚本 | 做了什么 |
|---|---|
| `convert_vietnam_pbf.py` | 核心转换脚本。把 311MB 的 OSM 越南 PBF（`vietnam-260811.osm.pbf`）按跨境物流走廊 bbox（越南北部+中部到岘港）裁剪，只保留 motorway/trunk/primary/secondary 等级道路；交叉口与锚点城市抽稀为 RoadNode，中间节点合并进边并保留 OSM 真实走向折线（Douglas-Peucker 简化）；保留中国侧手写节点（南宁/崇左/凭祥/友谊关/东兴/河口）与口岸虚拟边 E4/E9/E36。产出 35MB 的 `vietnam-road-network.json` |
| `extract_place_names.py` | 从 PBF 提取地名节点（place=city/town/village），以 `P-` 前缀追加进路网 nodes，作为地图标注节点（不影响 Dijkstra 算路） |
| `level_places.py` | 给地名节点打行政级别（1=首都/直辖市，2=地级市，3=县镇），配合前端缩放分级注记 |
| `probe_namezh.py` | 临时探测脚本，检查 PBF 中地名与中文译名（name:zh）覆盖情况 |
| `verify_network.py` | 最终校验：锚点可达性、关键路径连通性（NN→HN、LS→BG 等）、节点/边 ID 唯一性 |

**矢量瓦片（历史）**：用 planetiler 从同一 PBF 生成 z0-14 越南 MVT 瓦片（`tools/data/tiles/`），OpenMapTiles schema，样式文件 `frontend/src/data/local-base-style.js`。**现状**：前端已全部改为 Leaflet 栅格层，不再请求 `.pbf`，样式文件已不存在（`frontend/src/data/` 现为空目录）；`tools/data/tiles` 仍被 `WebConfig` 注册为 `/tiles/**` 的第二个位置，属无人访问的历史产物。

### 2. 后端（Spring Boot 4.1.0 + Java 21，端口 8080）

核心依赖：JGraphT、GraphHopper 10.2（直接读 PBF）、osmosis PBF 解析、Gson、Lombok；bootRun 配 `-Xms1g -Xmx4g -XX:+UseZGC`。

**Controller 一览**：

| 类 | 职责 / 关键端点 |
|---|---|
| `ContestWeatherController` | 比赛标准接口：`GET /api/v1/variables`、`/observations`、`/forecasts/3h`（Bearer Token 鉴权 + 参数校验 + 代理到 CRA40/GOWFS 上游） |
| `RouteController` | 路径规划：`POST /api/route/plan`、`/plan-with-weather`（气象+AI 预警闭环）、`GET /network`（全路网）、`/candidates`（多路线候选）、`/status`、`/block`、`/unblock`（风险注入）、`/osm/start|stop`（GraphHopper 热加载） |
| `WeatherController` | 气象：`GET /api/weather/real`、`POST /real/refresh`、`POST /risk`、5 组预置灾害场景 `GET /scenarios`、`POST /deepseek/scan` |
| `AgentController` | SSE：`GET /api/agent/events`（SseEmitter 实时守护流）、`POST /register`、`GET /status` |
| `AIController` | AI 预警：`GET /api/ai/warning`（中文）、`/warning-bilingual`（中越双语）、`POST /predict-hazards`（气象+DeepSeek 闭环） |
| `CustomsController` | 通关时效：`GET/POST/DELETE /api/customs/efficiency` |
| `KnowledgeController` | 知识库：`GET /api/knowledge`、`/search`、`/categories` |

**Service 亮点**：

- `RouteService`：Dijkstra 双并行（baseline 无风险 vs current 有风险），边权重 = 距离/限速 + 口岸通关时间 × 气象惩罚系数
- `RouteAgentService`：Agent 实时守护中枢，风险变化 300ms 防抖后毫秒级重算活跃路线并 SSE 广播
- `WeatherSimulator`：风险注入/清除 + 5 组预置灾害场景（暴雨·友谊关 / 大雾·芒街 / 泥石流·越北 / 高温·冷链 / 台风·广宁）
- `RealWeatherService`：比赛 CRA40 实况接口适配，按 bbox + variables 拉取格点并映射到业务节点，10 分钟缓存
- `AIService` / `DeepseekService`：本地 Ollama → 在线 DeepSeek → 内置离线模板（规则引擎），两级降级保证"拔网线可演示"（公司网关通道已移除）
- `StartupWarmup`：启动预热跑一次 NN→HN 规划触发 JIT，保证首次点击毫秒级

**配置**：`WebConfig` 配 CORS（`/api/**` 全开放）+ 把 `frontend/dist` 与离线瓦片目录映射为静态资源；`/tiles/**` 按顺序挂两处：离线栅格目录（`osm.tiles.path`，未指定时在 `data/osm-tiles` 与本机历史目录中探测）与 `tools/data/tiles`（历史 MVT 矢量瓦片，当前前端不请求）。

### 3. 前端（Vue 3.5 + Vite + Leaflet 1.9.4）

双入口：`src/main.js`（调度大屏）、`src/driver/main.js`（司机端），`vite.config.js` 双入口打包 + `/api` 代理到 8080。

| 文件 | 核心功能 |
|---|---|
| `src/App.vue` | 调度大屏：路径规划（下拉/地图选点）、灾害场景注入、实时气象、通关时效调整、路线对比、AI 双语预警、AI 灾害预测、知识库检索、当前风险列表；订阅 SSE + 15 秒轮询兜底 |
| `src/components/MapView.vue` | 地图组件（纯 Leaflet，MapLibre 已弃用）：底图四级降级链（天地图影像→天地图矢量→OSM 在线→本地离线瓦片，源定义在 `src/utils/tileSources.js`）+ 瓦片自愈巡检；中文地名注记由天地图注记层（`cia_w`/`cva_w`）提供；口岸节点（divIcon）、风险边（橙色）、口岸虚拟边（红色）、路线（通畅绿/绕行红）、基准路线（灰虚线）、地图选点 |
| `src/components/PlaceInput.vue` | 调度大屏起终点输入框：关键字联想 + 空输入聚焦展开「常用地点」下拉（地名库 `hot` 标记），候选自带 `nodeId` 时直接填入、否则走 `/api/geocode/snap` 吸附 |
| `src/driver/DriverApp.vue` | 司机端（手机竖屏）：状态卡（通畅/预警/绕行）、沿线实时气象、风险路段列表、AI 双语预警、通关提示、知识库速查；TTS 抢占优先级：灾害预警 / 运河禁航 / 全域受阻（`force`）> 导航开始播报（`lock` 独占）> 其余普通播报 |

### 4. 数据合规确认（答辩加分项）

- 地图底图与路网数据均派生自 OpenStreetMap（ODbL 许可），界面已标注 `© OpenStreetMap contributors (ODbL)`
- 底图默认取**天地图在线瓦片**，但密钥不外泄：前端只走后端同源代理 `/api/tianditu/...`，密钥池轮转 + per-key 熔断 + 内存/磁盘两级缓存，热门瓦片全生命周期只回源一次；探测失败时才降级到 OSM 在线源与包内自建离线栅格瓦片。「不依赖第三方在线服务」只在包内离线末级这一档成立，不是默认状态
- 初赛展示为非商业内部使用，ODbL 共享义务不触发，无版权纠纷风险

### 5. 联调与运维

- 后端：`gradlew bootRun`，bootrun*.log 记录各次启动
- 前端：Vite dev server（5173），`/api` 代理后端
- 数据验证：瓦片接口 `/tiles/...` 返回 200（当时为 MVT `.pbf`，现离线末级为 `.jpg`）；路网接口 `/api/route/network` 返回 200（23MB 全量加载正常）
- 离线演示包 `asean-demo/`（外发 `D:\桌面\asean-demo-offline.zip`，342MB / 32421 条目 / 解压后 375MB）：
  内嵌 jre + app.jar + dist + 29493 张天地图瓦片 + decision-cache；`README.txt`（114 行）= 启动链路（解压 → 双击 start.bat → 打开大屏/司机端地址）
  + 一段「气象数据用的是组委会官方接口 · 可当场核验」：`WEATHER_SOURCE=contest-observation` 且 Token 随包（用户要求评委必须亲自验官方数据，
  规则禁的是进仓库与前端，这两处经打包脚本强制校验为零），README 里告知大屏标题括号内即当前生效源、`/api/weather/rate-stats` 可自查真实回源与
  节流计数、60s 间隔与 10 分钟缓存导致「连点只出网一次」属合规不是卡住，以及三源可一键切换对比；另写明断网/被屏蔽时自动降级不假装官方数据；
  + 一段「AI 决策是怎么跑的」：本包把通道全部打开（本机 Ollama 优先 → 在线 DeepSeek → 规则模板兜底），
  写明这是为了让评委能亲手测出真模型调用、**正式部署会关掉在线通道改走本地/内网**，并给出触发姿势（先沙盘熔断再点「🧠 AI 决策分析」，
  「⚡ 缓存模式」不调模型）、首次调用等几十秒属正常、方案卡数字与模型无关均为本机实时计算；演示动线与 FAQ 已按用户要求删掉；
  `start.bat` 补了 CRLF 归一化（LF-only 批处理会让 cmd 解析 `:findport`/`goto` 端口避让循环出错），
  `tools/_pkg_sync.ps1` 同步产物、`tools/_pkg_rezip.ps1` 密钥审计后重打、`tools/_pkg_unzip_verify.ps1` 解压端到端复验。
  本轮两个打包脚本的修正：`_pkg_rezip.ps1` 改为先建到 `D:\Temp` 再带重试发布到桌面——旧版开头设了
  `SilentlyContinue`，桌面归档被 7-Zip/预览窗格独占时 Remove-Item 与 tar 双双失败被吞掉，脚本照样打印
  REBUILT 并拿旧归档回读校验“通过”，差点把旧包发出去；现在 tar 产物不存在就 exit 1，并打印在线 AI 通道
  状态（LIVE / HALF-ENABLED / OFF），防止把可花钱的 key 无声随包外发；`_pkg_unzip_verify.ps1` 的判据由
  「外发 AI 0 次」改为「aiChannel 必须为 online:* 且无 401/403/429」，并以 `--ollama.enabled=false` 启动
  包内 jar，专门复现评委机器（没有 Ollama）会命中的那条在线兜底路径。实测：`aiChannel=online:deepseek-chat`、
  标签「在线 API（deepseek-chat）· 非本地」、失败日志 0 条、缓存模式仍为 unrecorded。官方气象那一路的实测证据（同样在解压包上跑）：
  `/api/weather/real` 返回 `source=contest-observation`、风险段 2 条；连拉两次后 `rate-stats` 为 `upstreamRequests=1 throttleBlocked=0
  circuitOpens=0 consecutiveFailures=0`，即节流+缓存在外发包里确实生效，不会把评委变成刷官方接口的凶手。
  说明中已把旧版“本服务只监听本机”的失实描述剔掉：实际 `server.address=0.0.0.0`，
  因此简要版不再声称只监听本机，只提醒防火墙勾选「专用网络」。

---

## 三、Bug 修复记录

| 问题 | 现象 | 处理 |
|---|---|---|
| 地图缩放空白/路线消失 | 首次缩放后画面空白，需缩放两次才显示路线 | 诊断：数据链路全正常（瓦片 200、network 200），定位为 Leaflet 缩放动画与 MapLibre GL 5 相机管线兼容问题（`maplibre-gl-leaflet` 在 zoomAnimation 中对 WebGL canvas 做 transform，与 v5 不兼容）。曾尝试 `zoomAnimation: false` 修复，但效果更差（全程空白），**已回退恢复 `zoomAnimation: true`**。**现状**：MapLibre 已从依赖中整体移除、两端统一纯 Leaflet，大屏仍保留 `zoomAnimation: true`（见 §四 1） |
| （待复测）缩放兼容性 | 当时观察到缩放动画可能与 `maplibre-gl-leaflet` 桥接层有不稳定表现 | 后续 MapLibre 已整体移除、两端纯 Leaflet，该现象是否随桥接层消失**尚未复测**；旧条目里的「升级 maplibre-gl / 给插件打补丁 / 改用纯 MapLibre」三个候选已随依赖一并作废，现状收敛为 §四 1 |
| 路线不贴合道路（穿山/切弯） | 路网转换脚本 DP 抽稀容差 0.0006°（≈66m）过粗，52382/62090 条边几何只剩起终点 2 点，山区弯道全部被切成直线 | **根治**：`convert_vietnam_pbf.py` 容差降到 0.0001°（≈11m）重新生成（节点/边数量不变，路由不受影响），>1km 长边平均几何 18.5 点；JSON 改紧凑输出，体积 36MB→22MB。配套：`RouteService.toCoords` 新增 `pathEdgeSpans`（每条边在 pathCoords 中的下标区间），RouteResponse/候选 DTO 下发；司机端风险段标红与 `focusRisk` 改按区间取点（几何加密后点数≠边数，原下标直取会错位） |
| （已修复）AI 文案谎称本地 | `aiPowered` 仅表示“模型返回了非空文本”，不区分命中哪级通道；本地 Ollama 一超时/显存被占就静默降级到在线 DeepSeek，而 `AgentPanel.vue` 仍按「本地大模型」展示，与 README“数据不出境”直接矛盾（实测：11434 挂时 reroute 结果全由 api.deepseek.com 生成） | `DeepseekService` 新增 `chatWithChannel()` 返回 `AiReply{text,channel,model}`（保留 `chat()` 旧签名不影响其他调用方）；RiskAgent / SolutionAgent / TouchAgent 三个结果 record 各加 `aiChannel`；`DecisionOrchestrator` 取 `worstChannel()` 汇总并输出 `aiChannelLabel`；前端改成四档真实通道标签（本地/在线非本地/规则模板/预生成未记录），每个智能体节点各自标注。旧 decision-cache 无法核实来源，读到旧记录时强制降为 `unrecorded` |
| 方案 C「原地等待」车标仍前进 | 大屏下发原地等待、司机端确认接收后，导航里的车辆图标照旧沿路线爬。`_switchNavForPlan('C')` 只 `showPush` 就 return，驱动图标前进的模拟行驶定时器 `_triTimer` 从头到尾没被停过 | 新增 `waitHold` 停驶态：`_applyWaitHold()` 关掉 GPS 探测与推进定时器、把车钉在当前里程位置；`_start/_resumeTripSimulation`、`_startRealTimeGPS`、`_tryCapacitor/_BrowserGPS` 及其定位回调、tick 均按 `waitHold` 短路（否则底图整图重建的恢复分支、`await` 权限后迟到的 watchPosition 回调会把车重新开起来）；导航底部加「⏸ 原地等待中 · 车辆停驶」条 + 「恢复行驶」按钮（从当前里程续跑、不回起点）；改派 A/B、司机自行出发、退出导航均清除停驶态 |
| 弱机上「点什么都没反应」（不只是地图卡） | 同事电脑跑离线包：拖图卡，点各种按钮也发滞；同一份代码在本机顺畅 | 根因不在地图，在**常驻逐帧重绘**：`.panel`（360px 宽、满屏高，所有按钮都在里面）同时挂 `backdrop-filter: blur(20px)` + `prism-drift`（动 background-position）+ `prism-glow`（动 inset box-shadow），`.app-header` 与全屏极光层同类，routeFlow 的 `.rf-arrow-pulse` 以 60fps 重算 `filter: drop-shadow()`——background-position / box-shadow / filter 三者都交不给合成器，只能整幅重绘，主线程被绘制占满后输入事件排队，于是“点什么都没反应”。修：新增 `frontend/src/utils/perfMode.js`，用 `<html data-perf-lite="on">` + 注入 `!important` 样式一次性关掉这些装饰（刻意不用 `* { animation: none }`，否则连加载转圈也一起停掉），`MapView` 按 `isLite()` 以 `lite/fps:24/maxArrows:8` 重建特效层（切换时还要清 `_lastRouteSig`，否则被“同一张图”免疫直接 return）；判定分三层：用户手点（写 localStorage，优先）→ 静态弱机信号（≤2 核 / ≤4GB / 系统要求减少动效）→ 首屏 5 秒后实测 1.2 秒帧率，`fps<40` 或单帧 >250ms 则自动降级且**不写** localStorage。实测（CDP + CPU 节流）：20x 节流下采样到 5fps / 645ms → 自动开启、按钮变「⚡ 轻界面（自动）」、`.panel` animation 转 none、localStorage 仍为 null；不节流时 175fps / 38ms → 保持满特效不误伤。后端同批改两处内存陷阱：`MEM_CACHE_MAX_ENTRIES` 由写死 10 万改为 `tianditu.mem-cache-entries`（默认 2 万，包内 5000——包内总共才 29493 张瓦片，且磁盘才是主缓存），`start.bat` 的 `-Xmx3g` 改为按物理内存自适应（≥16GB 3g / ≥8GB 2g / 其余 1g）并在黑窗打印检测值 |
| `/api/geocode/list` 丢字段，司机端地名库回填是死路径 | `place-names.json` 与 `GeocodeService.Place` 都有 `nodeId`，但 `list()` 只输出 name/lat/lng/tag；`DriverApp.vue` 里 `if (p.nodeId) NAME_TO_ID[p.name] = p.nodeId` 永远拿不到值（症状被内置 `NODE_NAMES` 掩盖，不报错也不生效） | `list()` 补发 `nodeId`（非空才发）与 `hot`；`suggest()` 的本地候选同样带上 `nodeId`，前端点选自带节点 id 的候选直接写入、跳过 `/api/geocode/snap`（少一次请求且离线可用）。CDP 实测：空输入聚焦弹 14 条常用地点，点选「河内」后输入框为“河内”、下拉收起、`/snap` 请求数 0；改输关键字则分组头消失、切回联想候选 |
| 导航开始播报把灾害预警「闷」掉（只弹卡不出声） | 司机点「开始导航」后的十几秒内（出发播报还没念完中越双语）灾害到达：红卡弹了、手机震了，但**语音一个字都没有**；`?voicedebug=1` 上留痕「拦截预警播报：导航开始播报中」 | 优先级被写成两个布尔 `force`/`lock`：出发播报用 `{force:true, lock:true}` 独占通道，`speakQueue` 开头对非 force 直接 return，`_cancelVoice(false)` 见 `_criticalVoiceSeq` 也拒绝取消。问题在于**同类安全信息有两套优先级**：运河禁航、全域受阻传了 `force`，而真正最常触发的灾害入口 `_notifyHazard`（SSE 即时提示）、`announceHazard`（绕行播报）、`refreshForecast`（概率骤升）、`_handleDispatchDuringNav`（调度下发）、`buildWarning`（司机手点 AI 预警）全部没传。修：这 5 处统一补 `force`（非安全类的轮询/普通播报仍照旧被出发锁拦住，原有保护不拆）；同时补一个**抢占后的信息不丢**回路——出发播报入队时把开场核心行（起讫点 + 全程里程 + 耗时 + ETA）与当时的播报世代登记下来，被 force 打断时挂到本次队尾补读一次（走 `_expandToSentences` 拆句，否则长句在 WebView 会被提前 onend 截掉后半句），正常念完、重试、卸载三个出口都要作废该登记；第一版把整句从头重读，于是已念完的段会重复（探针输出里「导航开始，」出现在 [0] 和 [7]）——改为登记**拆句后的数组**、`_doSpeak` 每推进一条就把上一条出列，补读只补「还没完整念到的连续后缀」（正在念而被掐断的那条保留重读，半句不能缺字）；出发播报的「4.5 秒未出声自动重试」改成与首次同一个登记函数，否则重试换了新世代而登记里还存着旧世代，重试之后再来灾害就不补读了。**验证**：`tools/_voice_preempt_probe.mjs`（CDP + 把 `window.speechSynthesis` 换成逐句登记文本的假引擎，直接驱动组件方法复现时序）两轮 PASS：灾害句紧接在出发片段之后出现、补读尾部逐条等于「打断时刻登记在册的待补读段」快照、出发独占期间塞入的普通播报仍然不出声 |
| 地图选点「点了没反应」 | 大屏点「地图选点」后在地图上点两下：无标记、无提示、无规划，看起来像按钮坏了 | 三层原因叠加：① **`App.vue` 压根没把 `pickMode/pickOrigin/pickDestination` 传给 `MapView`**（props 有默认值 false/null，子组件不报错，`map.on('click')` 里的 `if (this.pickMode)` 永远为假，一次 `map-click` 都不发）——补上三个绑定；② `mounted` 与 `initMap` **各绑了一次** `map.on('click')`，一旦①修好就变成一次点击触发两回 `onMapClick`，同一点被当成「起点+终点」并弹「起终点不能是同一节点」——删掉 `mounted` 那次，绑定统一由 `initMap` 负责（每个新地图实例绑一次）；③ 口岸注记、风险/口岸/候选线都是 Leaflet 交互层，它们的 click 优先于地图 click 被消费，点在这些元素上同样没反应——选点模式给地图容器挂 `picking` 类，`:deep(.picking .leaflet-interactive/.place-label-text){pointer-events:none}` 让点击穿透，同时光标变十字作为「现在点地图有效」的可见反馈。另外补两处反馈质量问题：8.6 万路网节点只有 32 个带名字，`n.name || n.id` 会把输入框变成一串节点 ID，改为节点名 → 本地地名库最近地名（「XX附近」）→ 坐标 三级兜底；路网只沿中越走廊密铺（走廊内吸附中位 6km），点到走廊外会默默吸到 300~1000km 外，新增 `PICK_SNAP_MAX_KM=60` 护栏并当场说明。**验证**（CDP 无头点击真实后端）：点河口口岸/芒街口岸注记位置 → 起、终标记落在点击点 1~3px 内、输入框显示「河口口岸附近 / 东兴附近」、自动发出 `plan-with-weather`；点泰国境内 → 弹「距最近路网节点约 995 km，超出路网覆盖范围」而不是默默乱吸 |
| 后台气象守护 7×24 空转（合规风险 + 弱机周期性顿挫） | 21 小时真实回源 **1247 次**，而 `weather.real.observation-time` 是固定时次——上游每次返回的字节逐字相同，全部白打（规则明确「禁止暴力访问」）；同时每轮无条件叫醒 agent 对每条关注路线全量重算（163,326 节点／185,303 边）并向所有 SSE 订阅者广播整套路线载荷，弱机上正好砸在车标动画的帧间隙上，表现为每分钟一次顿挫 | 根因是四件「不报错、只白跑」叠在一起：① 巡检 60s 正贴 `min-request-interval-seconds=60` 下限，等于每轮必然放行一次上游；② `clearRealRisks()` + 逐条 `applyRealRisk()` 各自**无条件** `fireChange()`，「什么都没变」也付一次全量重算＋广播；③ 每轮重跑走廊基线 Dijkstra；④ `syncRealWeather` 每次 `new` 单线程池用完即弃（≈1440 池/天），`buildRealRisk` 对 18.5 万条边各 new 一个 ArrayList。修法：`RealWeatherService` 按**响应内容签名**指数退避（`base << streak`，双重封顶 `min(unchanged-backoff-max-seconds=240, ttl-base)`——第二道封顶是必需的，退避跨过 TTL 会让 `cachedFor` 返回空、实时风险被整体清空、界面从「有数据」变「无数据」）；`WeatherSimulator.syncRealRisks()` 改为差异写入（reason/severity/penalty 逐条比对，无变化不 fireChange，手动注入边不删不覆盖）；`RouteService` 走廊并集缓存（10min TTL）+ 常驻 daemon 拉取线程 + `realPullInFlight` 并发闸 + `buildRealRisk` 懒分配。**注意退避引入的新坑**：`resetThrottle()` 必须连带 `unchangedStreak=0 / lastSignature=null`，否则「切完就点拉取」撞在 4 倍窗口上，会重演早先修过的「切了没反应」；但不得放宽额度（按源间隔 + 跨源间隔两道硬闸仍在）。**验证**：bootRun 在线采样 streak 1→2→3、effInt 120→240s，5 分钟窗口回源 1 次（旧行为 5 次）；冒烟「友谊关熔断→绕芒街 487.3km/rerouted=true，撤销→389.43km/rerouted=false」链路未坏；`rate_audit.mjs` PASS 8/FAIL 0；新增 `WeatherIdlePatrolTest` 6 用例锁死退避与差异写入。另：首跑发现的 `SolutionAgentDetourTriggerTest` 失败经 `git stash` 回 HEAD 复跑证实为**仓库既有红**（10-23 的「未发车闸门」晚于 10-10 的用例），按现行契约拆成未发车/在途两条，不是本轮改坏 |
| 车标顿挫（速度脉冲：停住十几帧→猛地窜一截） | 上一轮盯掉后端空转后，车标仍不是匀速：探针（`tools/_car_hitch_probe.mjs`，CDP 逐 rAF 帧采车标**地理坐标**——相机跟车时屏幕坐标几乎不动，采屏幕坐标会把「跟车正常」误判成「顿挫」）实测 tickGapMax=212ms、帧瞬时速度 P95≈3.2× 中位（160 vs 50 m/s）、移动窗口内 13 帧连续零位移 | 根因不在渲染慢，在**插值段长写死了名义值**：`_setVisSegment` 的 `dur=SIM_TICK_MS=100`，而 setInterval 被瓦片绘制/轮询/修剪挤占后两次 tick 实际隔 200ms+——段跑完 100ms 就停在终点等下一 tick（顿挫十几帧），下一段又要用 100ms 补跑两段的路（速度脉冲）。修：段长按**相邻 tick 真实到达间隔**自适应（限幅 50~500ms、与上一段均值平滑），段到期时刻贴住下一 tick；`_stopVisLoop` 连带清 `_visTickAt` 防止新行程继承旧节奏。验证：重建 dist 后探针两轮（含 6x CPU 节流）stall/jump 均 0，vP95/v中位从 3.4×降到 1.45×。另：`.car-smooth` 的 120ms CSS 补间注释原先按「模拟行驶 tick」口径写的，现已标为真实 GPS 稀疏更新专用（模拟路径 rAF 每帧 setLatLng、transition 被显式置 none） |

---

## 四、遗留事项 / 风险提示

1. **缩放兼容性问题未根治（且需复测）**：当前保留 `zoomAnimation: true`（与问题出现前行为一致），"首次缩放需二次触发"的问题仍存在，暂不阻塞演示（可通过先缩放一次规避），答辩前建议优先解决。注意：该问题当初归因于 MapLibre 桥接，而 MapLibre 现已不在依赖中，所以现象是否已随桥接层消失尚未复测，不能直接当作旧结论的延续。
2. **DeepSeek API key** 已迁移至 `.env.local`（`DEEPSEEK_FALLBACK_KEY`），不在 `application.properties` 中硬编码。
3. **OSM 在线源仅作备用**：对外发布前建议移除高德/OSM/Carto 在线源，只保留本地瓦片，并在关于页写明数据来源。
4. ~~旧版测试路网 `road-network.json`（1KB）与主路网并存，未清理~~ — **已清理**：该文件实际为 0 字节且无任何代码引用（`RouteRepository` 只读 `data/vietnam-road-network.json`），已删；同时删掉 `src/main/resources/data/` 下三个 `.bak` 路网快照（共 ~79MB，`*.bak` 本就 被 `.gitignore` 排除，但留在目录里会被整个文件夹打包带走）。
5. **四个源文件仍未入版本库**（`git status` 为 `??`）：`service/CorridorAdvisoryService.java`、`service/RecommendationPool.java`、`frontend/src/utils/corridorAdvisory.js`、`test/.../SolutionAgentDetourTriggerTest.java`。本地编译与测试能过（文件在磁盘上），但**评委 clone 后会直接编译失败**，提交前必须 `git add`。
6. **曾随离线包明文外发过的凭证需作废轮换**：`asean-demo/.env.local` 早期版本含真值的气象 Token、天地图密钥池与 DeepSeek Key，现已全部置空，但旧包若已发出，那些凭证应视为已泄露。

---

## 五、关键技术决策备忘

- **路网数据**：弃用早期 OSMnx 在线下载方案（`download_road.py`），改用离线 PBF + 自研解析脚本，保证可复现、可离线。
- **底图方案**：走过「在线栅格直连 → planetiler 本地矢量瓦片（MapLibre 渲染）→ 回到纯 Leaflet 栅格」三段。当前实现：天地图在线瓦片（后端同源代理 + 密钥池轮转 + 内存/磁盘两级缓存）为默认，OSM 在线与包内 EPSG:4326 离线栅格（`/tiles/{z}/{x}/{y}.jpg`）为降级末级；`tools/data/tiles` 下的 MVT 瓦片是上一段矢量方案的遗留产物，仍挂在 `/tiles/**` 但已无请求方。取舍口径：观感与授权风险靠「同源代理 + 离线末级」解决，而不是坚持自绘矢量样式——矢量方案要额外养一套样式文件与 WebGL 桥接，而桥接层正是当时缩放空白的来源。
- **气象数据**：初期使用 Open-Meteo / wttr.in 在线源；初赛阶段替换为比赛官方 CRA40 实况 + GOWFS 三小时预报格点接口（`/api/v1/*`），按 bbox + variables 切片返回，配合本地 Dijkstra 做路线风险评估。
- **AI 降级链**：本地 Ollama（优先，数据不出境）→ 在线 DeepSeek → 规则引擎离线模板，保证断网环境演示不翻车；本地通道持有熔断冷却窗口（连续 2 次失败冷却 2 分钟），失败不阻断输出结构。
- **AI 通道溯源**：`DeepseekService.chatWithChannel()` 返回 `{text, channel, model}`，三个智能体各自透传 `aiChannel`，`DecisionOrchestrator` 汇总时取“最远程”的一级（就重不就轻），前端 `AgentPanel.vue` 按通道分五档标签。本地与在线在合规上不等价，“调用成功”不能等同于“本地推理”。
- **实时守护闭环**：SSE + 300ms 防抖重算，演示"突发灾害马上出新路线"。
- **停驶靠状态机而不是文案**：「原地等待」这类"车不该动"的指令，只在界面上写一句话、不停推进定时器，画面就会与指令自相矛盾。所有能动车的入口（模拟 tick、GPS 回调、底图重建后的自动恢复）统一被一个 `waitHold` 标志拦住；解除时用 `_resumeTripSimulation()` 从当前里程继续，而不是 `_startTripSimulation()` 把车搬回路线起点。
- **启动预热**：提前触发 Dijkstra JIT，首点规划毫秒级。
- **外发文档的编码在打包时定死**：纯中文的 `README.txt` 若无 BOM，Windows 记事本可能按 GBK 解码成乱码，
  而 IDE 会在自动保存时反复剔除 BOM。因此 BOM+CRLF 的归一化不放在写作阶段，而是放进
  `_pkg_rezip.ps1` 的打包前步骤，并在成品的 zip 条目里反读校验（首三字节 + CR 计数），
  保证发出去的那一份与写作时的判断无关。
- **弱机保护必须自动，不能靠评委去点按钮**：异步评审时没人读代码、也没人知道屏上有个性能开关，
  “你卡了就自己点一下”等于没有修复。所以把判定做成递进：用户手点优先（写 localStorage）→ 静态硬件
  信号→ 首屏后实测帧率；且**自动判定不持久化**，一次偶发低帧不能把这台机器的观感锁死。
  装饰特效（毛玻璃 / 流光 / 呼吸阴影）是可以全量去掉的，去掉后功能、数据、路线红绿分段、
  加载指示完全不变，不影响任何一个可核验点，所以“自动变轻”比“好看”优先。
- **服务端内存也不能写死**：`-Xmx3g` 与 10 万条瓦片内存缓存都是从开发机（32GB）状态拍出来的，
  搬到了 8GB 笔记本上就是跟系统和浏览器抢内存、抢输了就换页，表现同样是“点什么都没反应”。
  故 launch 脚本按物理内存自适应取堆大小（读不到就退到 1g），瓦片热缓存上限改为可配并默认下调
  （包内才 2.9 万张瓦片、磁盘才是主缓存，内存留 5000 条足够覆盖一场演示）。
- **交付入口做双份，exe 不替换 zip**：安装版是自己写的 SFX（`tools/sfx/Launcher.cs` 用 .NET 4.0
  的 csc 编译，成品 = [launcher][asean-demo-offline.zip][8 字节长度][ASEANPK1]），双击即「解压到同
  目录 → 自动拉起 start.bat」，省掉评委「右键全部提取」这一步——在预览窗口里双击必报 app.jar
  is missing，是包翻车的头号原因。更早的一版用 7-Zip 的 GUI SFX 模块，但它在**无参数（等于真人双击）**
  时才执行 `RunProgram`、带参数就退化成纯解压，这条体验无法自动化验证，只能人工点一次小样；换成
  自写 launcher 后整条链路可由 `tools/_setup_live.ps1` 无人值守跑通（裸启动 → 等 app.jar 出现 →
  等内嵌 java 监听 → 打 `/api/weather/real` → 二次启动跳过解压）。未签名 exe 仍会吃 SmartScreen 蓝窗、
  360/火绒可能直接隔离，所以 zip 必须同时留着当兜底，README 里两种入口并列写清；打包脚本会把成品里
  的 payload 反读出来做 sha256 比对，保证发出去的两个入口是同一份代码。
- **取数结果不能反过来改写用户的选择**：切到「比赛官方接口」后下拉框自己跳回「真实气象」，根因是两条叠在一起的。
  一是后端节流只有**全局一份**状态（一个 `lastRequestAtMs` + 60 秒窗口 + 一个熔断），而守护线程每分钟必然按当前源
  回源一次，于是用户刚切完源、立刻点拉取，一定落在窗口里，只能拿到**上一个源**的缓存；`resetThrottle()` 自身又限频，
  等于切源永远「没反应」。二是前端把响应里的 `source`（语义是「最近一次成功回源的源」）当成 `weatherSourceId`
  （语义是「用户选了谁」）写回下拉框，选择权被回包反向覆盖。**这是把「实际生效」与「用户选择」两个概念混成一个变量**，
  所以修法是拆开而不是加 `if`：下拉框只认后端新增的 `selectedSource`；新增 `servedSource`/`servedFresh` 如实回答
  「屏幕上这批数字到底是谁家的、是不是本次取的」，不一致时界面直接写「已选 X，当前显示的是 Y 的缓存」而不是假装实时。
  节流也跟着按源各自存（`Map<String, SourceState>`，各有自己的窗口/失败计数/静默期，官方 Token 失效不该把 Open-Meteo
  一起熔断），再叠一道**跨源最小间隔** `weather.real.min-source-switch-gap-seconds=5` 兜住「来回切源把每个源的窗口
  变成摆设」。合规复验：`rate_audit` 的 B3 十连发切源 → 真实回源 **0 次**，B1 二十连发支流实况 → **1 次**。
- **切源要作废的不只节流，还有中间层的 TTL**：`RouteService` 自己有一层 5 分钟的 `REAL_SYNC_TTL_MS`，且非 force 的
  请求只等 2 秒（官方接口实际要十几秒）。只放开 `RealWeatherService` 那道闸，15 秒轮询仍被这层挡住，新源永远落不下去，
  故切源接口里必须同时调 `invalidateRealSync()`。前端再按「这个源还要等多久」补拉（等时长问 `/api/weather/rate-stats`
  里该源的窗口，而不是拍一个固定秒数），最多两次，避免变成客户端侧的循环骚扰。
- **瞬时布尔标志既不能作为断言依据，也不能作为界面判据**：验证脚本头一版等 `servedFresh=true` 那一刻，等 116 秒仍
  FAIL——守护线程 60 秒一轮刚好抢在窗口打开的第一次，任何外部轮询读到的都是被下一次缓存读覆盖后的 false。改为查该源
  的 `secondsSinceLastSuccess`（成功时刻的单调时间差，与「尝试时刻」分开记）才拿到稳定证据。
- **自检脚本也要自检**：`rate_audit.mjs` 用 `/api/ping` 判后端是否活着，而真实端点是无 `/api` 前缀的 `/ping`，
  404 后整段动态压测被静默跳过并打印「后端未运行」——看起来一切正常，其实 B1/B2/B3 从未跑过。护栏：探测失败要能
  区分「服务没起」与「探测路径写错」，且这类「静默 skip」比显式报错更危险，改完必须确认它真的产生了 PASS 行。
- **交付图必须跟代码对一次，而不是跟记忆对一次**：`交付材料/08`、`09` 两张架构图的 PNG 比 HTML 旧，
  而 HTML 自身也漏了不少已实现的能力。本轮逐项核过的偏差：官方接口实际是 **CRA40 实况 + GOWFS 3h 预报两条通道**（
  预报独立窗口 30min/静默 600s），且本系统自身也按 `/api/v1/*` 标准对外暴露；AI 推理通道原图写的「本地→企业内网网关→在线→规则模板」
  四档里**网关一档代码中不存在**，真实是 `DeepseekService` 的 local/online/cached 三常量 + 规则模板（`ollama.url` 可指向内网网关，
  但不是独立一档）；图上还缺 `RoadRiskIndex` 多要素风险指数、`TyphoonRiskRule` 气压+风速双要素判据、
  `CorridorAdvisoryService` 全域受阻（折返/等待、两端停止自动派单）、`TaskController` 派单→接单/拒单闭环、
  `GeocodeService` 地名库→天地图 POI→Nominatim 三级与 5km 吸附半径、底图四级降级链与 per-key 熔断、企业画像模板。
  成图用 `tools/_arch_shot.ps1 -Pattern '09-*.html' -H <脚本回报的内容高+10>`，先看它打印的 `content ends at y=` 再定窗口高度，
  避免图底留白或裁切。
- **“能跑”不等于“别人能复现”，要拿一台不存在的机器当假设重看一遍配置**：本轮提交前体检把三类“只在这台开发机上成立”的写法改掉——
  ① `gradle.properties` 写死 `org.gradle.java.home=C:\Program Files\Java\jdk-21...`，别人机器上没这个目录就直接构建失败，
  改为 toolchain `auto-detect` + `auto-download`（路径以注释形式保留供本机指定）；② 离线瓦片目录在 `WebConfig` 里写死成某个本机目录、
  且只能用 `-Dosm.tiles.path` 这种 JVM 参数覆盖，改为 `@Value("${osm.tiles.path:}")` + 候选目录探测（`data/osm-tiles` 优先），
  同时把 `osm.pbf.path` / `osm.graph.cache.path` 从 `D:/idea/...` 默认值改成 `data/...` 相对路径并环境变量化（`OsmDataLoader` 的
  `@Value` 默认值与 `application.properties` 必须一致，否则“改了一处、另一处还在指旧路径”）；③ 数据加工与取证脚本里的绝对路径
  （`gen_place_names.mjs`、`_arch_shot.ps1`、`restart_bootrun.cmd`）一律改成由脚本自身位置推导。
  另外两条经验：文档里点名的取证脚本必须真能随仓库交出去（`tools/_*` 整体 ignore 会让 `01-技术文档` 引用的脚本在评委那边不存在，
  需逐个 `!` 负向放行）；“前端零密钥”要落到文件上而不是口径上——`frontend/.env.local` 里那把 `VITE_TIANDITU_TK` 已无任何代码读取
  （全项目只读 `import.meta.env.PROD`），留着就是一份会被打包带走的死密钥，直接删。
- **字段在类里有、在序列化处漏了，只会静默不生效不会报错**：`Place.nodeId` 存在、JSON 里也有，但 `list()` 手工拼 Map 时没拼它，
  于是下游所有“读 `p.nodeId`”的代码都是死路径。凡是前端/下游读了某个字段，就要反向确认服务端确实下发了该字段（看一次真实回包，
  而不是看 DTO 定义）；同理，**取证脚本的计数正则也要跟代码对一次**：`_api_inventory.mjs` 旧正则强制要求 `@GetMapping(` 有括号，
  把无路径根映射全漏掉，文档里的“94 端点”其实是 96。
- **用布尔开关表达优先级，一定会漏出“同一类消息两套优先级”**：语音通道的规则只有两个布尔 `force`（安全关键，可打断独占）与
  `lock`（独占通道，非 force 丢弃），于是“要不要带 `force`”变成每个调用点各自决定的一件事——运河禁航、全域受阻带了，
  而触发频率最高的五个灾害入口（SSE 即时提示、绕行播报、概率骤升、调度下发、司机手点 AI 预警）全都忘了带，
  表现为“红卡弹了、手机震了、语音一个字没有”。这类缺陷不报错，只能靠**枚举全部调用点列表化对齐**（23 个播报入口一次看全）
  才发现；判据应当是消息的**安全语义**，而不是入口的实现位置。配套教训：抢占式通道必须回答“被打断方的信息去哪了”——
  出发播报被抢走后，起讫点/里程/耗时/ETA 就永久丢了，所以补一条队尾补读回路；而补读登记必须带**世代校验**
  （只在“原播报还在念、还没被别的链路顶替”时生效），否则很久之后的 force 播报会凭空补念一句陈年的“导航开始…”；
  同一条播报的**自动重试会换世代**，首次与重试必须共用同一个登记入口，不然重试过一次之后补读就悄悄失效了。
- **交付清理应当「复制一份再删」，不在原地删**：原项目里 `frontend/dist` 是后端 `WebConfig` 的静态资源目录、`asean-demo` 内含离线瓦片与内嵌 JRE，
  在原地删构建产物等于把本地演示能力一起删掉，删完还很难判断「少没少东西」。改为按 `.gitignore` 的语义**只读复制**出一份提交版
  （`D:/idea project/asean-weather-logistics-submit`，260 文件 / 52.1 MB，剔掉 1452 MB 的依赖、产物、缓存、源数据压缩包与内嵌 JRE），
  原项目一律不动、随时可演示。副本必须能自我核验而不是「看着干净」，本次三条：① 禁止项残留扫描为 0；② 副本与原项目逐文件 MD5 全等
  （证明只是少了东西，不是改了内容）；③ 在副本里从零装依赖 → 构建前端（产物哈希与原项目一致）→ 跑后端测试（11 项全绿），
  让「删掉产物和依赖、重新构建就能恢复」这句话被真跑过一遍。配套教训两条：**白名单按引用关系给，不按扩展名一刀切**——
  `gradle/wrapper/gradle-wrapper.jar` 若随 `*.jar` 一起剔除，评委侧 `gradlew` 直接起不来；被 DEV_LOG/交付文档点名的取证脚本
  （`_pkg_sync.ps1`、`_pkg_rezip.ps1`、`_pkg_unzip_verify.ps1`、`_setup_live.ps1`、`_voice_preempt_probe.mjs`）不在 `.gitignore` 里逐个 `!` 放行，
  仓库内文档就会指向评委那边不存在的文件。**副本不含 `.git`**：历史里带不出凭证（`.env.local` 从未入库，已核），但 51 MB 的仓库对象对交付无用。
- **补读的粒度必须与朗读的粒度同源**：出发播报是被 `_expandToSentences` 拆成一条条短句逐条读的，登记却存了整句，于是「只补没念完的部分」这个意图无法表达，只能整句重读，念过的段跟着重复一遍。改成登记**同一个拆句数组**、由实际推进朗读的那条路径（`_doSpeak`）负责出列，“已读/未读”才有一条干净的判定线；正在念的那一条不能出列（只念到一半就被掐断），宁可重读一句也不能缺字。探针也必须按这个粒度写：在打断时刻快照待补读数组，比对补读尾部与快照是否逐条相等，而不是只数“出发片段出现了几次”——旧探针就是后者，把“整句重读”当成了通过条件。
- **节流「存在」不等于节流「有用」**：`RealWeatherService` 的按源窗口、跨源闸、熔断都在，审计也 PASS，但巡检周期 60s 正贴回源下限 60s，等于每轮必然放行一次——**该看的不是间隔够不够，而是内容会不会变**。上游是固定时次时，1247 次回源拿到的是同一份字节。同理，「无条件 fireChange」让「什么都没变」也要付一次全量重算＋SSE 广播：省请求的判据应当是**响应内容签名**（顺序无关累加、不含 fetchedAt），而不是时间戳；退避还必须先问缓存 TTL（跨过 TTL 会让 `cachedFor` 返回空，界面从有数据变成没数据，这是拿体验换指标）。
- **红测试先复跑基线再定罪，别默认是自己改坏的**：改动后首跑 `11 tests, 1 failed`，正确动作不是回头改代码迁就断言，而是 `git stash push -- <本轮文件>` 在 HEAD 复跑同一用例——基线同样失败即仓库既有红（本例是 10-23 引入的「未发车闸门」晚于 10-10 写的用例），再按**现行契约**重写用例（拆成未发车/在途两条），而不是掩盖它。
- **量「顿挫」要选对坐标系与标称值**：车标顿挫排查里两个坑都差点把结论带偏——① 采样选屏幕坐标会得到 90% 帧零位移的假顿挫（相机本来就把车钉在屏幕中心），必须按地理位移（米/帧）算；② 探针自身也会造伪信号：采样时刻取整毫秒后 dt 可以算出 1ms，速度被放大成假 P95=160m/s，加 `dt≥6ms` 过滤后真信号才露出来（修前 160、修后 61）。同理，代码里写死的**名义节奏**（`dur=SIM_TICK_MS`）与真实节奏（setInterval 实测 100~212ms）是两回事，凡拿定时器周期当动画时长，都要问一句「这个周期被主线程挤占后还是它吗」。
