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
| `convert_vietnam_pbf.py` | 核心转换脚本（越南—广西走廊那份路网的产出者）。把 311MB 的 OSM 越南 PBF（`vietnam-260811.osm.pbf`）按跨境物流走廊 bbox（越南北部+中部到岘港）裁剪，只保留 motorway/trunk/primary/secondary 等级道路；交叉口与锚点城市抽稀为 RoadNode，中间节点合并进边并保留 OSM 真实走向折线（Douglas-Peucker 简化）；保留中国侧手写节点（南宁/崇左/凭祥/友谊关/东兴/河口）与口岸虚拟边 E4/E9/E36。产出 35MB 的 `vietnam-road-network.json`。**现状**：`main()` 已收敛为委托 `corridor_convert.run()`，原先写死常量的编排保留为 `legacy_main()`，专门用来跟走廊配置做逐项对账 |
| `corridor_convert.py` | 把「走廊」从代码里的常量变成数据：`tools/corridors/<name>.json` 描述 bbox、每个 PBF 来源（等级/吸附半径/锚点/id 前缀）、手写节点与口岸虚拟边、是否贴路精修、连通性检查对与输出路径；引擎按配置逐源抽取再合并。三条护栏实测在案：`--check-corridor vietnam-cn` 与旧常量逐项相等（PASS）、缺 PBF 时明确报「把 extract 放到 tools/data/ 下重跑即可，引擎与配置都不需要改」、`--corridor <name> --out` 直接生成第二走廊。idPrefix 用于多来源防撞号 |
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
| `AiMetricsController` | AI 用量计量：`GET /api/ai/metrics`（累计 calls/failures/tokens、分通道 avgMs 与 maxMs、inFlight 与 peakInFlight、lastCall、成本折算） |
| `SpeechController` | 云端语音输入：`POST /api/ai/speech`（multipart `audio`+`lang`，ffmpeg 转 16k wav → 阿里云一句话识别）、`GET /api/ai/speech/status`（凭证是否齐备；缺凭证如实 `enabled:false`，前端按钮置灰而不是假可用） |

**Service 亮点**：

- `RouteService`：Dijkstra 双并行（baseline 无风险 vs current 有风险），边权重 = 距离/限速 + 口岸通关时间 × 气象惩罚系数
- `RouteAgentService`：Agent 实时守护中枢，风险变化 300ms 防抖后毫秒级重算活跃路线并 SSE 广播
- `WeatherSimulator`：风险注入/清除 + 5 组预置灾害场景（暴雨·友谊关 / 大雾·芒街 / 泥石流·越北 / 高温·冷链 / 台风·广宁）
- `RealWeatherService`：比赛 CRA40 实况接口适配，按 bbox + variables 拉取格点并映射到业务节点，10 分钟缓存
- `AIService` / `DeepseekService`：本地 Ollama → 在线 DeepSeek → 内置离线模板（规则引擎），两级降级保证"拔网线可演示"（公司网关通道已移除）
- `OllamaService.chatDetailed()` / `DeepseekService` 的 `AiReply`：解析回包里 OpenAI 兼容的 `usage`（`prompt_tokens`/`completion_tokens`），失败也记账，统一喂给 `AiMetricsService`（`LongAdder` 累计 + 在途并发峰值）；`ai.cost.*-per-mtok` 未配价时回报 `costConfigured=false`，不折一个假金额
- `KnowledgeBaseService`：轻量关键词 RAG 的检索半边——查询分词（CJK 二元组 + ASCII 整词 + 停用词表）→ 字段加权（标题 3／关键词 2／正文 1）× 词稀有度折档（按 df 近似 IDF）→ 覆盖度奖励；`searchDetailed()` 带分数返回，供出处展示与测试断言
- `StartupWarmup`：启动预热跑一次 NN→HN 规划触发 JIT，保证首次点击毫秒级
- `SpeechService`：浏览器语音的服务端识别通道——AK/SK 按阿里云 RPC 签名（HMAC-SHA1）调 CreateToken 换临时 token（进程内缓存、过期前 5 分钟才换、40000004/40000010 清缓存重试）；POST 音频到 NLS 网关一句话识别；ffmpeg 把浏览器容器（webm/opus、mp4/aac）转 16k 单声道 wav；凭证三项任一未配 → `enabled()=false`，与天气 Token 同纪律（不入库、可 `ENC()` 密文）。存在的原因：Web Speech API 识别端在 Google，国内不可达（手机端实测报 network），识别必须挪到境内服务端

**配置**：`WebConfig` 配 CORS（`/api/**` 全开放）+ 把 `frontend/dist` 与离线瓦片目录映射为静态资源；`/tiles/**` 按顺序挂两处：离线栅格目录（`osm.tiles.path`，未指定时在 `data/osm-tiles` 与本机历史目录中探测）与 `tools/data/tiles`（历史 MVT 矢量瓦片，当前前端不请求）。

### 3. 前端（Vue 3.5 + Vite + Leaflet 1.9.4）

双入口：`src/main.js`（调度大屏）、`src/driver/main.js`（司机端），`vite.config.js` 双入口打包 + `/api` 代理到 8080。

| 文件 | 核心功能 |
|---|---|
| `src/App.vue` | 调度大屏：路径规划（下拉/地图选点）、灾害场景注入、实时气象、通关时效调整、路线对比、AI 双语预警、AI 灾害预测、知识库检索、当前风险列表；订阅 SSE + 15 秒轮询兜底 |
| `src/components/MapView.vue` | 地图组件（纯 Leaflet，MapLibre 已弃用）：底图四级降级链（天地图影像→天地图矢量→OSM 在线→本地离线瓦片，源定义在 `src/utils/tileSources.js`）+ 瓦片自愈巡检；中文地名注记由天地图注记层（`cia_w`/`cva_w`）提供；口岸节点（divIcon）、风险边（橙色）、口岸虚拟边（红色）、路线（通畅绿/绕行红）、基准路线（灰虚线）、地图选点 |
| `src/components/PlaceInput.vue` | 调度大屏起终点输入框：关键字联想 + 空输入聚焦展开「常用地点」下拉（地名库 `hot` 标记），候选自带 `nodeId` 时直接填入、否则走 `/api/geocode/snap` 吸附 |
| `src/driver/DriverApp.vue` | 司机端（手机竖屏）：状态卡（通畅/预警/绕行）、沿线实时气象、风险路段列表、AI 双语预警、通关提示、知识库速查；TTS 抢占优先级：灾害预警 / 运河禁航 / 全域受阻（`force`）> 导航开始播报（`lock` 独占）> 其余普通播报 |
| `src/components/AiBall.vue` | 「AI志宁」悬浮小球（两端共用）：圆球内是 `AI` 字样 + 星芒（不放人像），下方挂名字，点开弹出对话面板（`<slot />` 塞各自聊天体）。大屏 `pos="fixed"` 相对视口，司机端 `pos="absolute"` 相对 `.phone` 容器（否则 420px 宽的手机上球会跑到屏幕外）。对外 `show/hide/toggle`，司机端搜索框的麦克风图标就调 `show()` |
| `src/components/VoiceInputButton.vue` | 「长按说话」按钮：按住识别、松手发送；按住 <250ms 自动转成「按一下开始、再按一下结束」（照顾桌面鼠标，按住微拖就误松手）；键盘 Enter/Space 走同一条点触路径（靠 `event.detail === 0` 区分合成 click）；识别中的中间结果实时回显；**服务端通道无中间结果，松手后靠 `onProcessing` 回调把按钮切到独立的「识别中…」态（麦克风图标换成转圈、底色变浅）——否则松手到出字之间界面静止，用户把「没反应」误读成「识别慢」（实测服务端转码+识别合计 <1.5s，慢的感觉主要来自缺这个反馈）**；两条通道都不可用时置灰并在 title 里写明原因，不做「点了没反应」的假按钮 |
| `src/utils/speechInput.js` | 语音输入（STT）三通道：原生容器走 `@capacitor-community/speech-recognition`（Android 系统识别，可装离线语料）；浏览器优先走**服务端识别通道**（MediaRecorder 录音 POST `/api/ai/speech`，后端代理阿里云 NLS，境内可达；启动时探 `/status`，`fd.append('audio', blob)` 字段名与后端 `@RequestParam("audio")` 契约一致）；服务端未启用时才退回 Web Speech 兜底（开发机挂代理可用）。与播报侧「原生优先」对称，全不可用时返回 null 而不是猜一个 |
| `src/driver/DriverCopilot.vue` | 司机端「AI志宁」对话舱（塞进 `AiBall` 的 slot）：走 `/api/copilot/chat`，命中 `navigate_to`/`plan_routes` 时 `emit('command')` 交父组件进入路线规划；`lang` prop 跟随整机语言（zh-CN / vi-VN）传给语音识别 |
| `src/driver/i18n.js` | 司机端整机语言字典（中文 ↔ Tiếng Việt）：`createUiTranslator` 做 DOM 层词典翻译，未命中回落中文。**被 `DriverApp.vue` 直接 import，必须随仓库交出去** |
| `src/utils/aiChannel.js` | AI 通道口径与计量文案的两端共用模块：`channelKind`/`channelLabel`（本地大模型／在线 API·非本地／离线缓存文案／规则解析）、`formatCost`（本次 tokens + 耗时）、`fetchAiMetrics`（取不到就整段不显示）、`formatPool`（进程累计量）。`CopilotPanel.vue` 与 `DriverCopilot.vue` 底部各挂一行常驻状态；知识问答回包带 `sources` 时渲染「依据：KB-xxx 标题」出处行 |

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
| 规则兜底解析把闲聊听成指令 | 加「天气/气象」关键词与导航口令后，`CopilotServiceRuleParseTest.unknown_fallsBackToChat` 变红：「今天天气不错啊」被判成 `query_risk`。审计同一段代码又发现两条没被测试覆盖的：裸「去」在句中也算导航口令（「去掉这个风险」会抽出目的地）、单字「双」算双风险注入（写操作，误触代价最高） | 三条一起收：① 「天气/气象/下雨…」必须再配一个疑问或查询标记才算查风险，纯陈述降级 `chat` 交回模型；② 导航口令表里删掉裸「去」（只承认句首），并给抽出的片段加 `looksLikePlace()` 反碎片闸（含「知道/什么/样/掉/了…」即否）——口令表越全误判越多，闸必须同源存在；③ 双风险只认「双风险/叠加/复合/都注入」等明确说法。补 3 个用例锁死（`weatherQuery_needsInterrogativeMarker` / `navigate_extractsPlaceOnly` / `injectBoth_requiresExplicitWording`），并把「今天天气不错啊」这种反例写成断言而不是口头约定。**另一个教训**：写规则表时以为「怎样」能盖住「怎么样」，实测失败——「怎么样」不含连续子串「怎样」，中文子串匹配必须按实际高频说法逐条列全 |
| 被 import 的前端源文件没入库，评委 clone 后构建直接断链 | `frontend/src` 下五个文件（`AiBall.vue`/`VoiceInputButton.vue`/`speechInput.js`/`DriverCopilot.vue`/`i18n.js`）本地能跑是因为它们在工作区里，`git status` 为 `??`；评委 clone 后 Vite 报 `Failed to resolve import`。同类缺陷上一批是四个 `.java`，本轮新写的 `utils/aiChannel.js`、`AiMetricsController.java`、`AiMetricsService.java`、`AiMetricsServiceTest.java`、`KnowledgeBaseServiceTest.java` 又重演一次 | 不再靠人肉 `git status`：新增 `tools/_clone_integrity.mjs`（从入口扫 import/require 依赖闭包，对照 `git ls-files` 报未入库源码）+ `tools/_make_clone.mjs`（按 git 清单只读复制到 `D:\Temp\clone_view2`，node_modules 用 Junction 挂回去）后真跑 `npm run build` 与 `gradlew cleanTest test`。该审计确实每轮都第一时间抓出了新建文件，修后 `RESULT: PASS`（336 tracked），clone 视角前端构建通过、后端 45 tests 0 failures。**结论：这类缺陷一定会复现，只能靠脚本守** |
| RAG 的检索半边形同虚设 | 知识库只有 10 条，`KnowledgeBaseService` 把**整个问句**当子串去匹配标题/正文——自然语言问句永远不是标题的子串，所以召回恒为 0 条；而 `AIService.knowledgeContext()` 还硬截到 2 条，`CopilotService`（AI志宁）一条都没用过知识库。症状是「有 RAG」只存在于文档里，且不报错 | 召回重写为分词 + 字段加权 + 稀有度（见 §二 2）；知识库 10→32 条、新增 `climate`/`corridor` 分类；`CopilotService` 开只读工具 `query_knowledge` 并接 `answerKnowledge()`（回 `sources`/`answerChannel`/`answerCost`，模型只依据检索条目改写、离线时明标「未经模型改写」、召回为空就不作答）。两个红用例教出来的修正：① 「榴莲运输要保持多少度」top3 没有新条目 KB-021，因高频词「运输」压过稀有词「榴莲」→ 引 df 稀有度加权；② 「从凭祥出口岸要办哪些手续」top1 是 KB-016 而非 KB-001 → 停用词补「哪些/哪个」+ 覆盖度奖励 `2*(hitTokens-1)` + 给 KB-001 补 keywords。新增 `KnowledgeBaseServiceTest` 6 用例跑**真实 JSON**（不用 mock，否则测不出条目本身写坏） |
| 手写 `\uXXXX` 转义中文，造出错字与**不实能力声明** | 写 `knowledge-base.json` 与 Java 注释时直接手写码位转义，产出「早季（应旱季）/「熔」被写成码位相邻的错字/阀值（应阈值）/经流/上披/哥答巴鲁」等一批错字，最严重的是写下「越南语变音符会归一」——`tokenize()` 根本不做音符折叠，这是一句会让答辩当场被验伪的假声明 | 整文件用中文字面量重写（不逐条修补，避免漏网），并把假声明改成如实的「已知局限：带变音符的写法不做归一，请优先用无音符形式或中文名」。同理修掉 Java 注释里同一原因产生的错字。**规则：中文一律走字面量，绝不手写码位转义；能力声明必须能在代码里指出对应那一行，否则写「局限」而不是写「能力」** |
| 走廊配置里写了 Python 风格的隐式字符串拼接，`json.load` 直接炸 | 新写 `tools/corridors/guangxi-cn.json` 与 `thailand.json` 时，`note` 字段按 Python 习惯写成多行相邻字符串（JSON 不支持），解析报 `Expecting ',' delimiter: line 5 column 11`——症状像「文件坏了」，实际是把另一种语言的写法带进了 JSON | 两处 note 合并为单行字符串。**规则：JSON 里没有相邻字符串字面量拼接这回事，长说明要么单行要么 `\n` 转义；写完必须真 `json.load` 一次，不能只看IDE高亮不报警**（同因还造成 `--check-corridor` 把 set 直接交给 `json.dumps` 抛 `TypeError: Object of type set is not JSON serializable`，改为 `_canon()` 递归归一 set/tuple 后才可比） |
| 把 `@PostMapping` 的端点当 GET 验，误判成后端 500 | 验证第二走廊时按浏览器地址栏打 `/api/route/plan-sample`，返回 500，看起来像新路网把规划接口打挂了 | 实际该端点是 `@PostMapping`（`RouteController` 里方法上写得很清楚），GET 进去是方法不匹配；换用 `GET /api/route/plan-with-weather?originId=NN&destinationId=YGG&withAi=false` 后正常返回。第二次踩坑是同一次回包把结果读成顶层字段（实际在 `route` 字段下），`ConvertFrom-Json` 取到空值像是「没规划出路线」。**取证脚本读回包前先看 DTO 层级，接口方法类型看注解而不是看命名** |
| 文档里写的复现命令本身跑不通（`--check-corridor vietnam-cn`） | corridor_convert.py 的文件头注释与 DEV_LOG 都写着「`--check-corridor vietnam-cn` 逐项对账」，而 argparse 里该参数是 `action="store_true"`，照着文档敲就报 `unrecognized arguments: vietnam-cn`——评委（和下一轮的自己）按文档取证时第一步就卡住，比缺功能更伤 | 把旗标改成 `nargs="?" const=DEFAULT_CORRIDOR`：带名字与不带名字两种写法都成立（实测两种都输出「逐项相等 PASS」），`--help` 里写明默认值。**规则：写进文档的每条命令必须当场敲一遍，而不是写完注释就假定它对**（与「自检脚本也要自检」同类：不可执行的复现入口等于没有入口） |
| 开发回路：常驻进程被命令结束连带杀掉 / bootRun 锁住构建输出 | 用 `Start-Process` 后台起 `gradlew bootRun` 与 `ollama serve`，日志报 LISTEN，几秒后连不上——子进程随前台命令结束被清；另一例是跑测试时 `:processResources FAILED — Failed to clean up stale outputs`，因为正在跑的 bootRun JVM 锁着 `build/resources` | 常驻进程一律交给 `is_background=true` 的后台终端（bootRun → `bootrun-current.log`，Ollama → `tools/_ollama_serve.out`）；跑测试前先 `Stop-Process` 掉监听 8080 的 java。另外 `node -e` 内联中文在本 shell 会产生假阴性，改写成 `.mjs` 文件跑；`_dist_verify.mjs` 只扫 `main-*`/`driver-*` 会误报「文案没进包」，因为 rolldown 的 `manualChunks` 把 `utils/aiChannel.js` 提到了 `geocoder-*.js`——改为扫全部 chunk 并回报命中文件名 |
| 阿里云 CreateToken 响应按老版文档解，**拿到 token 却判成失败** | 服务器上凭证配齐后 `/api/ai/speech` 仍回「云端识别失败」；日志里 `CreateToken 失败 http=200 body={"Token":{"Id":"e0b2…","ExpireTime":…}}`——HTTP 200、token 明明在回包里，却被自己的解析判死 | 老版文档写 `{"Data":{"Token":{…}}}`，实测 nls-meta 2019-02-28 直接回 `{"Token":{"Id","ExpireTime"}}`（字段名也从 TokenExpireTime 变成 ExpireTime）。改为两种结构都认。**接第三方 API 第一步先打印真实回包再写解析**，日志里的原始 body 比文档可靠 |
| 一句话识别网关回包是扁平结构，按文档 header/payload 嵌套解会把**成功的识别判成失败** | 修完 token 仍 ok:false，日志 `http=200 status=0 message=` 看不出所以然（status 解出缺省 0）；临时把原始 body 加进日志后真相：`{"status":20000000,"result":"…","message":"SUCCESS"}`——识别早就成功了，是解析把文本丢了 | 官方文档的 `header`/`payload` 嵌套格式是 WebSocket/SDK 路径的，HTTP GET 参数式调 `/stream/v1/asr` 回扁平 JSON。改为顶层优先、嵌套兜底都读。同一轮两次同根：**第三方 API 的响应结构只能拿真实回包定案，文档当假设、日志当证据**（测试音频因合成嗓音发飘识别文本不准，但链路已通：上传→ffmpeg→阿里云→回传文本都有当场证据） |
| AI志宁问天气答非所问：`riskSummary` 读了一个**沙盘根本不存在的键** | 用户问「查一下河内的天气情况」，气泡回「好的，正在查询…气象风险」、结论行永远是「当前无断面熔断，路网通行正常。」——问的是天气，答的是路网通不通，且无论有没有降雨都这一句 | `CopilotService.riskSummary` 读 `state.get("fusedSections")`，而 `DecisionSandboxService.state()` 里只有 `sections`（逐断面 Map），从来没有 `fusedSections`，于是恒为 null、恒走「无熔断」分支。改为遍历 `sections` 逐断面报出实际气象读数（`未来6h累计降雨 Xmm，状态`），熔断数作结尾结论；未推送（`statusText=等待气象数据`）时如实回「尚未收到气象推送」。**验证**：注入暴雨剧本后 `POST /api/copilot/chat` 问「河内天气」回「友谊关口岸 未来6h累计降雨 85mm，已熔断；芒街口岸 62mm，可用（降雨偏高，保持关注）。当前 1 个断面熔断。」（`tools/_srv_copilot_verify.sh`，验后已 reset 回空态）。同根教训：**下游读上游 Map 的键，必须去 put 处对一次，不能凭字段名臆造**（与 §五「字段在类里有、在序列化处漏了」同类） |

---

## 四、遗留事项 / 风险提示

1. **缩放兼容性问题未根治（且需复测）**：当前保留 `zoomAnimation: true`（与问题出现前行为一致），"首次缩放需二次触发"的问题仍存在，暂不阻塞演示（可通过先缩放一次规避），答辩前建议优先解决。注意：该问题当初归因于 MapLibre 桥接，而 MapLibre 现已不在依赖中，所以现象是否已随桥接层消失尚未复测，不能直接当作旧结论的延续。
2. **DeepSeek API key** 已迁移至 `.env.local`（`DEEPSEEK_FALLBACK_KEY`），不在 `application.properties` 中硬编码。
3. **OSM 在线源仅作备用**：对外发布前建议移除高德/OSM/Carto 在线源，只保留本地瓦片，并在关于页写明数据来源。
4. ~~旧版测试路网 `road-network.json`（1KB）与主路网并存，未清理~~ — **已清理**：该文件实际为 0 字节且无任何代码引用（`RouteRepository` 只读 `data/vietnam-road-network.json`），已删；同时删掉 `src/main/resources/data/` 下三个 `.bak` 路网快照（共 ~79MB，`*.bak` 本就 被 `.gitignore` 排除，但留在目录里会被整个文件夹打包带走）。
5. ~~**本轮新增的五个前端源文件仍未入版本库**~~ — **已入库，并改成脚本守**：除那五个前端文件外，本轮新写的 `utils/aiChannel.js`、`AiMetricsController.java`、`AiMetricsService.java`、`AiMetricsServiceTest.java`、`KnowledgeBaseServiceTest.java` 又被同一审计抓出（说明这类缺陷一换轮就会复现）。现行护栏：`tools/_clone_integrity.mjs`（依赖闭包 vs `git ls-files`）报 `RESULT: PASS`（本轮回 348 tracked，新增的两支走廊探针已逐个 `!` 放行），`tools/_make_clone.mjs` 复制出的 clone 视角实跑 `npm run build` 与 `gradlew cleanTest test`（前端构建通过、后端现为 **54 tests / 0 failures**，本轮加 `RouteRepositoryTest` 4 用例与 `SpeechServiceTest` 5 用例后重跑确认）。**提交前必跑这两条，不能只看本地能跑**。
6. **曾随离线包明文外发过的凭证需作废轮换**：`asean-demo/.env.local` 早期版本含真值的气象 Token、天地图密钥池与 DeepSeek Key，现已全部置空，但旧包若已发出，那些凭证应视为已泄露。
7. **地理覆盖实际只有「中国广西—越南」，与项目名的 ASEAN 不对齐**（按评分表「东盟关联与国际化」逐项自查的结果）：地名库 52 条全部落在广西/云南 + 越南北部（hot 名单：南宁、钦州港、凭祥、友谊关、东兴、河口、谅山、芒街、下龙、海防、老街），主路网文件就叫 `data/vietnam-road-network.json`，官方气象 bbox 由业务节点反推（`buildBbox(nodes)`），界面语言只有 zh/vi 两种；地图上点泰国已实测弹「距最近路网节点约 995 km，超出路网覆盖范围」。**本轮（第二走廊可行性验证）已把「同一引擎换数据即换国家」从口号做成可核证据**，三层：① 走廊参数化——`tools/corridors/*.json` + `tools/corridor_convert.py`，`--check-corridor vietnam-cn` 与改造前写死的常量**逐项相等 PASS**（等价性由 `legacy_main()` 保留下来专门用于对账）；② 第二走廊真跑通——用另一份国家级 extract（`guangxi-latest.osm.pbf`）生成 79,906 节点 / 90,103 边，5 个锚点全部 <0.1km 吸附，NN→YGG 连通；③ 后端零改代码——仅设 `route.network-file=file:tools/data/guangxi-road-network.json` 即在 8081 起来并规划出 NN→友谊关 200.8km / 2.04h，回包 40 个路径节点里 **36 个只存在于新生成的走廊文件**，剩下 4 个重合点恰好就是配置里手写的 NN/CZ/PX/YGG 四个锚点（`tools/_corridor_evidence.py` 当场算出，不是口头转录；同脚本报出两份路网全局共用 616 个 OSM 节点 id——旧走廊本身就含中国侧来源，重合是预期的，所以拿「路径完全不重合」当证据会被反例问穿。该脚本依赖生成产物 `tools/data/guangxi-road-network.json` 与 8081 实例，两者都不入库，缺时它直接报「把哪个 PBF 放到哪、跑哪条命令重生」而不是抛栈）。泰国走廊（`tools/corridors/thailand.json`）是**差数据不是差代码**：配置已就绪（9 个锚点、bbox 全覆盖），本仓库环境不做数据下载，引擎明确回报「缺 `thailand-latest.osm.pbf`，放进来就能跑，代码不用改」。**因此剩下的真实工作量不在算路引擎**，由 `tools/_corridor_coupling.py` 量出：**44 处走廊节点/边字面量分布在 14 个类**（`WeatherSimulator` 8、`DecisionSandboxService` 6、`CNRoadGeom` 5、`BriefingController`/`RouteService` 各 4，其余为默认 O/D、联运锚点、预热对、场景注入），另加地名库 52 条、气象 bbox、`decision-cache` 场景 JSON 与界面文案。答辩口径应当是「引擎与数据入口已解耦并验证，换国家的工作量在业务字面量的配置化（已量化为 44 处），不在算路」，而不是「已覆盖东盟」——后者会被一句「那马来西亚呢」问穿，而本轮也没做半成品局部重构（避免一半读配置一半写死）。
8. **官方气象接口取的是固定历史时次，真实降雨驱动不了熔断剧本**：`weather.real.observation-time=202412312000`、`weather.forecast.run-time=202412312000`，`application.properties` 里自己就写着该时次「无降水、6h 累计 0mm…第二幕熔断仍需剧本值驱动」。所以「有效调用主办方接口」这一项的证据是「数据真来自官方」，不是「系统能实时发现灾害」——答辩口径必须按前者说死，否则被追问「现在真实在下雨吗」会当场翻。另外格点→节点是 `nearestIndex` 最近点取值，不是插值，也要主动交代。
9. **AI token/耗时计量已落地；并发闸仍缺**：`AiMetricsService` 累计 calls/failures/promptTokens/completionTokens/totalMs/maxMs 与 inFlight/peakInFlight，`GET /api/ai/metrics` 已开，两端面板底部常驻「当前通道 + 本次 tokens/耗时 + 进程累计量」。实测（本地通道）：意图解析 avg 520ms / 单次约 460 tokens，知识改写多一次调用约 800~1050ms / 550~562 tokens；`costConfigured=false`（未核价就只报 token 不报金额）。**尚未做的部分**：`DeepseekService.ONLINE_TIMEOUT_SECONDS=60`、`OllamaService.TIMEOUT_SECONDS=60` 仍是阻塞调用并占住 Tomcat 工作线程，三智能体一次串行发 3 个请求，两位评委同时点仍是 6 个线程挂 60 秒量级——现在能**看到**并发（peakInFlight）但不能**限住**它，落地时要补的是有界线程池 + 队列满快速失败，而不是继续加大超时。
10. **RAG 已接 AI志宁，但口径是「轻量关键词 RAG」而不是语义检索**：召回已重写（分词 + 字段加权 + df 稀有度 + 覆盖度奖励，见 §三），知识库 32 条 / 7 分类，`query_knowledge` 已是 `CopilotService` 白名单里的只读工具，回答带 `sources` 出处行。**已知边界（必须这样对外说，不要说成向量检索）**：① 词面匹配不是语义检索，同义词未入库就召不回（所以下了 32 条 + 每条手关键词，召回质量靠数据而非模型）；② `tokenize()` 不做音符折叠，带变音符的越南语写法归一不了（已在条目里写明）；③ 无向量库是故意的——32 条规模引 embedding 服务只会把「断网可演示」破掉。若要进一步抬高上限，正确做法是加召回评估集（问句→期望条目编号）再考虑混合检索。
11. **本地通道已恢复，但启动顺序仍会踩冷却**：演示机当前 `127.0.0.1:11434` 已常驻（`ollama serve` 在后台终端），实测 `channel=local:qwen2.5:14b`、意图解析 520ms/383 tokens；另实测 Ollama 冷启动响应 3.4s，未击穿 60s 超时（`tools/_ollama_warm.mjs`）。**风隙在于**：bootRun 先于 Ollama 启动时，`java.net.ConnectException` 连续 2 次会触发 **2 分钟熔断冷却**，期间 `chatDetailed()` 静默返回 NONE 且不报日志，表现为「明明起了 Ollama，屏幕上的通道还是 online」。所以演示手册的顺序必须是**先起 Ollama 再起 bootRun**，并以面板底部那一行作为当场核验（它已从每条气泡里的胶囊收敛为一行常驻，既不噪声又能当场证明是本地跑的）。
12. **浏览器语音已改走服务端阿里云识别，服务器链路实测跑通；待办是真人录音验收与密钥收口**：背景——台式机无任何启用的麦克风设备（唯一活的音频端点是显示器 HDMI 输出），手机浏览器 Web Speech 报 network（识别端在 Google，国内不可达），两头都堵。现方案：MediaRecorder 录音 → `POST /api/ai/speech` → ffmpeg 转码 → 阿里云 NLS 一句话识别（免费试用额度，每日 2 路并发）。已实测：`https://zhi-ning.cn/api/ai/speech/status` 回 `enabled:true`，上传 16k wav 后端到端回传识别文本（`tools/_srv_speech_e2e.sh` + Windows 合成语音样本）。**待办三项**：① 真人录音（浏览器 MediaRecorder 的 webm/opus 路径）尚未现场验收，下次演示前用手机打开 `https://zhi-ning.cn` 长按说话确认；② 阿里云主账号 AccessKey 已写入 `/opt/asean/.env.local`（未入库），这对密钥在本次对话里出现过，**答辩全部结束后应到 RAM 控制台禁用**；③ 一句话识别只配了中文普通话模型，越南语语音输入仍不可用（接口如实回「不支持」，不硬凑），司机端 vi 语言的语音入口在演示口径里要说清。

---

## 五、关键技术决策备忘

- **路网数据**：弃用早期 OSMnx 在线下载方案（`download_road.py`），改用离线 PBF + 自研解析脚本，保证可复现、可离线。
- **底图方案**：走过「在线栅格直连 → planetiler 本地矢量瓦片（MapLibre 渲染）→ 回到纯 Leaflet 栅格」三段。当前实现：天地图在线瓦片（后端同源代理 + 密钥池轮转 + 内存/磁盘两级缓存）为默认，OSM 在线与包内 EPSG:4326 离线栅格（`/tiles/{z}/{x}/{y}.jpg`）为降级末级；`tools/data/tiles` 下的 MVT 瓦片是上一段矢量方案的遗留产物，仍挂在 `/tiles/**` 但已无请求方。取舍口径：观感与授权风险靠「同源代理 + 离线末级」解决，而不是坚持自绘矢量样式——矢量方案要额外养一套样式文件与 WebGL 桥接，而桥接层正是当时缩放空白的来源。
- **气象数据**：初期使用 Open-Meteo / wttr.in 在线源；初赛阶段替换为比赛官方 CRA40 实况 + GOWFS 三小时预报格点接口（`/api/v1/*`），按 bbox + variables 切片返回，配合本地 Dijkstra 做路线风险评估。
- **AI 降级链**：本地 Ollama（优先，数据不出境）→ 在线 DeepSeek → 规则引擎离线模板，保证断网环境演示不翻车；本地通道持有熔断冷却窗口（连续 2 次失败冷却 2 分钟），失败不阻断输出结构。
- **AI 通道溯源**：`DeepseekService.chatWithChannel()` 返回 `{text, channel, model}`，三个智能体各自透传 `aiChannel`，`DecisionOrchestrator` 汇总时取“最远程”的一级（就重不就轻），前端 `AgentPanel.vue` 按通道分五档标签。本地与在线在合规上不等价，“调用成功”不能等同于“本地推理”。
- **实时守护闭环**：SSE + 300ms 防抖重算，演示"突发灾害马上出新路线"。
- **停驶靠状态机而不是文案**：「原地等待」这类"车不该动"的指令，只在界面上写一句话、不停推进定时器，画面就会与指令自相矛盾。所有能动车的入口（模拟 tick、GPS 回调、底图重建后的自动恢复）统一被一个 `waitHold` 标志拦住；解除时用 `_resumeTripSimulation()` 从当前里程继续，而不是 `_startTripSimulation()` 把车搬回路线起点。
- **启动预热**：提前触发 Dijkstra JIT，首点规划毫秒级。风隙：它写死了 NN→HN，换一份不含 HN 的路网就会告警（本轮在广西走廊上实测触发），这不是 bug 而是耦合证据——凡是写死 O/D 的地方都属于走廊业务层，需跟着走廊走。
- **换国家的工作量不在算路引擎，在写死的默认 O/D 与场景字面量**：所以先**量耦合面再决定重构范围**（`tools/_corridor_coupling.py` 按「字符串字面量里出现的走廊节点/边 id」计数，得到 44 处 / 14 个类），而不是凭感觉改。两条可复用的做法：① 参数化旧逻辑时**保留旧写死版本作为对账基准**（`legacy_main()` + `--check-corridor` 逐项相等），“重构没改变行为”才有一句可核的话；② 数据入口属性化后要区分 classpath 与文件系统两种取法（`route.network-file` + `resolveNetworkResource()`），否则脚本生成的 30MB 路网只能靠入库才能跑；对应测试 `RouteRepositoryTest` 4 用例锁住「默认走廊仍是那份 / `file:` 能加载 / 裸绝对路径能加载 / 缺文件时报错必须说出当前生效路径」。
- **计量口径必须区分「能看」与「能控」**：这一轮把 token/耗时/并发都接上了，但只接了计数，没接限流——60s 阻塞调用依然占住工作线程。如实记下边界比只报「已支持计量」重要：`/api/ai/metrics` 现在能回答「这次分析花了多少 token、P95 多久、同时有几个人在等」，还不能回答「等了多久被拒绝」，因为还没有有界池与拒绝计数。
- **溯源信息要从「每条都标」收敛到「一处常驻 + 每条可查」**：早期为了修「谎称本地」把通道标签铺到每条回复，结果是 11px 的胶囊挤掉了用户真正要看的结论。现在的口径：对话面板底部常驻一行（当前通道 + 本次开销 + 进程累计），每条气泡的通道归属留在 `title` 里悬停可查；而 AI 决策面板（`AgentPanel.vue`）的四档标签保留不动——那里通道就是结论的一部分。区分标准是「这条信息是结论还是元信息」，不是「要不要溯源」。
- **RAG 的召回质量必须能当场被验证，而不是拿模型的流畅度当遮丑**：旧版「整句当子串匹配」不会报错，只会默默召 0 条，然后模型自己编——这是 RAG 最危险的失败模式。修完后三件事被写成断言：问句→期望条目 id、不相干问句→必须空结果（错引比不引更糟）、空查询→得分不得虚高。同时离线降级必须明标「未经模型改写」，不能把检索拼接当成模型输出。
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
- **Capacitor 插件的 web 实现会「假装有」，必须先看它能不能真跑**：给 AI志宁加语音输入时选了 Capacitor 原生语音插件，但这个包的 `web.js` 里每个方法都是 `throw this.unimplemented()`——如果按「先试原生再兜底」的顺序无条件调 `requestPermissions()`，浏览器里会先抛一串 not-implemented 再绕回兜底，白绕一圈还多拉一个 JS chunk。改成**先按 `isNativeShell()` 分轨**：原生容器才碰插件，浏览器直接用 Web Speech API。
- **接第三方原生插件前要先读它的源码，文档说的不一定是真的**：`SpeechRecognition.start()` 的声明是 `Promise<{matches?: string[]}>`，看上去「await 拿结果」就行；读 Android 侧 `SpeechRecognition.java` 才知道 `partialResults:true` 时它**当场 `call.resolve()` 返空**（`if (partialResults) call.resolve()`），真正的文本只从 `partialResults` 事件里来；且识别出错时是 `resolve({status:'error'})` 而不是 reject，错误根本不会进 `.catch()`。所以收口时机不能绑在 `start()` 的 Promise 上，而是：结果累加在事件里 + 以 `listeningState:stopped` 为结束信号 + `stop()` 后 1.5s 宽限定时器兜底（事件被后台/省电模式吞掉时不致于让用户白说）+ 12s 硬看门狗防按住不松挂死。
- **语音识别不能假装离线可用**：Web Speech API 在 Chrome 里是把音频送到 Google 服务器识别的，**断网现场演示必然不可用**（错误码就叫 `network`），**国内联网环境同样不可用**（手机端实测：权限给到位、https 安全上下文、录到音，仍报 network——到不了 Google 识别端点）。因此浏览器侧的识别必须挪到境内服务端（MediaRecorder 上传 → 后端代理阿里云 NLS），Web Speech 只留作开发机挂代理时的兜底；密钥只留后端，前端只面对同源接口。仍要分开口径：司机端 App 原生识别可离线是演示主通道，大屏浏览器语音依赖云端，断网现场两者只剩前者。`webErrorText()` 把 `network` 单独翻成中文写进提示，不给一个「网络错误」了事——不要把一个能力写在 README 里却让它在主演示场景下静默失效。
- **装饰图标就是 bug，不是样式问题**：司机端搜索框那个麦克风 SVG 已经挂在那里很久了，无事件、无 `alt`，用户必然以为能语音输入（本次就是被它问到）。要么接上真能力，要么删掉；留着它比缺少该功能更伤。现在它指向 `show()` 弹开 AI志宁，把语音入口收敛到对话舱一个地方，避免同时养两套识别 UI。
- **对话舱里「答案」与「过程回话」不能主次颠倒**：早期为了降噪，把口语确认（`data.say`，如「好的，正在查询…」）放进显眼的气泡、把真正的结构化结论（`data.summary`）沉在下方小字——用户一眼看过去，气泡里那句其实只是「我准备去查」的过程旁白，真正的答案反而小且靠下。现改为**答案优先**：`CopilotPanel.vue` 主气泡渲染 `m.summary || m.say`（有结论就显示结论，无结论的纯对话/错误/确认仍回落到 `say`），`say` 降级为气泡下方一行斜体小字「思考中 · …」（`.cp-think`，12.5px/#7f8ba0，比答案小且淡）。口径：`say` 是过程、`summary` 是结论，屏幕上先给用户要看的结论。司机端 `DriverCopilot.vue` 同构。
- **溯源标签要区分「主输出」与「对话噪声」**：之前为了修「AI 文案谎称本地」，把四档真实通道标签铺到了所有 AI 出口。但对话舱里每条回复前面挂一个「在线 API·非本地」胶囊，挤掉的是用户真正要看的结论（字号还只有 11px）。现收为：气泡不写通道文案，通道归属留在气泡的 `title` 里，悬停/长按可当场核；而 `AgentPanel.vue`（AI 决策分析）的四档标签**保留不动**——那里通道就是结论的一部分，不是元信息噪声。口径：**溯源信息必须可查，但不必每屏都占位**；同时把结论行（`data.summary`）从 11px/#8b95a5 提到 14.5px/#c8d1de（大屏）与 15px/#4b5563（司机端），气泡 13px→15px/15.5px，并给小球面板加 `max-height: calc(100vh - 110px)` + body 滚动，防止字号变大后在 768px 高的笔记本上顶出视口。
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
- **大面板不该常驻左栏，应在地图上「按需展开」**：把地图左上红框区做成通用「任务操作台」（`.task-dock`，`top:12px; left:12px`，窗口靠左排列、两窗并排 `justify-content:flex-start`），承载两个窗口——AI 决策分析（`AgentPanel`）与决策沙盘（`DecisionSandbox`）。用到哪个就地展开哪个（派单「AI 气象分析并规划路线」→ `onPreDispatchAnalysis` 开分析窗；左栏「拉取 GOWFS 数据」→ `launchSandbox` 开沙盘窗并即时 `sandboxRef.pullLive()`）；两个都开则并排（`.dock-row` flex 行，各 `flex:0 1 560px` 随空间收缩）；都不开时 `.is-empty` 整块 `pointer-events:none` 保持透明不挡地图。每窗独立「收起（成标题栏）/✕收回左栏」，工具条另有「全部展开/全部收起」。左栏只留两张 `.dock-entry` 入口卡。`z-index:600` 高于地图图例（400）。纯前端改动，部署只换 `/opt/asean/frontend/dist`、不动 jar、不重启 `asean`（index 带 `no-cache`，换内容哈希即生效）。
- **操作台从地图右上改到左上**：应用户要求窗口靠左。副作用是左上原本有 Leaflet 默认缩放控件，展开的窗口会盖住它 → 在 `MapView.initMap` 里 `this.map.zoomControl.setPosition('bottomleft')` 把缩放按钮移到左下角（右下角是版权署名、左上让给操作台），每次切底图重建地图实例后都会重设。图例 `.map-overlay` 仍在右上，与左上的操作台不再冲突。
- **操作台两窗改为可拖动浮窗（首次按预设槽位出现）**：把 `.dock-row` 由 flex 行改成 `display:contents`（包裹层不生成盒子），`.task-dock` 改为 `inset:0` 铺满地图区作为定位上下文，两窗 `.dock-window` 转 `position:absolute`、`left/top` 由内联 `style` 绑定 `agentPos/sandboxPos`。按住标题栏（`.dw-head` `cursor:move; touch-action:none`）`@pointerdown` 进入拖拽：`startDockDrag` 记录起点、`onDockDragMove` 用「起点 + 指针位移」写回 pos 并按地图区尺寸夹边（至少留一条标题栏可见），`endDockDrag` 收线；`.dw-actions` 加 `@pointerdown.stop` 防点按钮时误拖。首次展开落在预设槽位常量 `DOCK_AGENT_X=12` / `DOCK_SANDBOX_X=12+560+12=584`（探针实测两窗 x 相差 572px = 560+12，并排固定），工具条新增「复位布局」一键回槽位。**关键：因 `.map-area` 是 `flex:1`、位于左栏（1 号）右侧，窗口绝对定位相对地图区——左栏 `panelZoom` 放大时地图区整体右移，两窗随之右移，无需额外换算**（另在 `panelZoom` watcher 与 `resize` 监听里 `clampDockPositions` 兜底防溢出屏外）。headless 探针实测：拖拽沙盘窗 x 980→1038 / y 112→262、点复位又回 980/112，全部通过。
- **左栏入口卡去掉手动「展开」、只靠功能触发自动弹窗**：用户改需求（放弃「两份实时同步」的大改），左栏保持入口卡但去掉「展开」按钮——决策沙盘只留「拉取 GOWFS 数据」（`launchSandbox` 自动开沙盘窗并 `pullLive`），AI 决策分析卡不再有按钮（由派单「AI 气象分析并规划路线」`onPreDispatchAnalysis` 自动 `openDock('agent')`）。`openDock` 方法仍保留（被这两个入口调用）。探针确认去掉按钮后自动弹窗仍正常（GOWFS→1 个窗口、分析→2 个窗口）。
  - 左栏「拉取 GOWFS 数据」入口按钮改为与沙盘内同名按钮相同的绿色实底：App.vue 无 `.btn.live` 定义，且全局默认兜底规则 `.btn:not(.primary):not(.outline)…`（特异度 0,7,0）会把它刷成灰色——需同时在该 `:not()` 排除列表里加 `.live`、并新增 `.btn.live { background:#16a34a; color:#fff }`。headless 实测计算样式 `rgb(22,163,74)`。
  - AI 决策分析入口卡底部加一条「三智能体球」装饰（用户要求）：复用分析窗 `.agent-flow` 的观感——白底圆角盒 + 🔵🟢🟡 渐变球（Windows emoji 渲染成光泽球，与分析窗一致）+ 名称 + `→` 箭头，纯静态装饰、`aria-hidden`、不接状态。探针 + 截图确认三球与名称渲染正常。
- **把「跨境路径规划」起终点框与地图选点/清空/确认规划按钮并入「公司派单」卡**（用户要求）：删除独立的跨境路径规划卡，改用 **Vue 具名插槽 `route-picker`** 把那块 `PlaceInput` 起终点 + 三按钮注入 `TaskDispatchPanel` 卡内（取代原来只能选 4 个固定节点的起点/终点 `<select>`）。**关键：插槽内容编译于父（App.vue）作用域**，所以 `PlaceInput` 的 `ref="originInput/destInput"`、`v-model="draftOriginId/..."`、`confirmSelection/clearRoute/togglePickMode` 与 App 作用域的 `.coords/.row/.btn` scoped 样式全部照常工作，无需把地图选点/算路逻辑往子组件里搬。子组件只保留车牌/司机/货物/重量四个订单字段，新增 `originId/destinationId` props 仅供 od-tag 回显。`confirmSelection` 里的「解析草稿」抽成 `ensureEndpoints()`（返回 bool），`onPreDispatchAnalysis` 改为 `async`、先 `await ensureEndpoints()` 再分析（保证用当前输入框/选点的起终点），并把解析后的 `originId/destinationId` 回填进 `dispatchForm`（子表单不再携带 OD，下游 `/api/task/dispatch` 仍需）。探针实测：旧卡消失、派单卡含 2 个 PlaceInput + 确认/清空/地图选点/分析按钮；点分析 → AI 窗弹出、od-tag `NN → HN`、三智能体分析正常。
- **操作台两窗可拖宽「放大」（与左栏同交互）**：每个 `.dock-window` 右边缘加 `.dw-resizer`（`cursor:col-resize`、`@pointerdown.stop` 不触发标题栏拖动），`startDockResize/onDockResizeMove/endDockResize` 用「起始宽 + 指针水平位移」写回 `agentW/sandboxW`（内联 `width`），夹在 `DOCK_WIN_MIN_W=360` 与「地图区宽 - 窗口左边位 - 边距」之间；双击手柄 `resetDockWinW` 复位单窗宽，工具条「复位布局」同时复位位置与宽度。**为何用 width 而不是像左栏那样用 CSS `zoom`**：左栏是普通 flex 项、`zoom` 只放大宽与字号很干净；而 `.dock-window` 是 `position:absolute` 带内联 `left/top`，`zoom` 会连带把 `left/top`、`max-height` 等几何值一起乘倍 → 位置漂移 + 高度溢出，故只改 `width`（内容回流、字号不变），既满足“拖大”又避开定位坑。探针实测：拖 2 号窗右边缘 +220px → 宽 560→780、内容正常回流；点复位布局 → 宽度回 560。
  - 拖宽时字体也一起放大（用户要求，与左栏 `panelZoom` 一致）：子组件（AgentPanel/DecisionSandbox）字号写死 px，只改 width 不会变大字，必须上 `zoom`。**但不能直接 zoom 整个 `.dock-window`**（会把内联 left/top/max-height 一起乘倍 → 定位漂移 + 高度溢出，就是上面选 width 而不用 zoom 的原因）。做法：新增计算属性 `agentScale=agentW/DOCK_WIN_W`、`sandboxScale`，用 `dwZoomStyle(scale)` 只给内容层 `.dw-head`/`.dw-body` 施加 `zoom:scale` + `width:DOCK_WIN_W px`（内容按基准宽布局、zoom 后视觉宽=当前窗口宽，正好铺满）；外层 `.dock-window` 几何不变，拖拽/调宽/夹边数学全照旧。**坑**：`.dw-body`/`.dw-head` 默认 content-box，强设 width:560 后 padding(24px) 另计 → 横向溢出 24px（探针 overflowX=24）；给两者加 `box-sizing:border-box` 后 overflowX 归 0。探针实测：拖宽 → body zoom 1→1.086、标题 h3 渲染行高 25→27（随 zoom 等比变大）、bodyW==winW 无溢出；复位 → zoom 回 1、行高回 25。
- **离线时语音按钮优雅置灰（不再默默落到 Web Speech）**（用户要求，针对“决赛现场用自己电脑、能连外网但语音可用打字代替”）：`resolveSpeechChannel` 原来在服务端语音 `enabled:false` 时会 `return hasWebSpeech() ? 'web' : null`，导致非 Capacitor 环境下——**尤其是无阿里云凭证的离线演示包**——按钮仍被点亮，用户一点 → `startWeb` 走 Chrome Web Speech → 国内不可达报 network 错，看着像“功能坏了”。修法：`speechInput.js` 中该行改为 `return null`（开发机想手动测 Web Speech 直接 `startListening` 仍可用），`channelUnavailableText` 非原生分支文案同步改为“语音识别未启用（原生与服务端都不可用），请改用文字输入”；`hasWebSpeech` 仍被原生降级路径引用不删。`VoiceInputButton` 本身的 `off/不可说` 渲染链路不变。探针 `build/_voice_gray_probe.mjs`：`Page.addScriptToEvaluateOnNewDocument` 把 `/api/ai/speech/status` 打桩为 `{enabled:false}` 后导航线上页→ `.vb` class 含 `off`、title 含“文字输入”；另 `Page.removeScriptToEvaluateOnNewDocument` + 重新导航撤桩→ `.vb` 非 off、title 为“长按说话 · 云端语音识别”（确认没误伤正常 server 通道）。构建 main-BZ7-cxiz.js，`_fe_deploy` 前端专属上线，公网 index 已返回该 chunk。
- **语音识别“要等挺久”：先把上传体积压下来（前端）**（用户反馈“还是很卡顿”）。先拿真数据定位瓶颈：服务器侧日志（`SpeechController` 已埋耗时分解）实测 转码=113–408ms、阿里云一句话识别=426–1734ms（服务器在 **cn-guangzhou**、NLS 网关在 **cn-shanghai**，跨地域）——转码不是瓶颈，**阿里云批量（非流式）识别是大头，且松手后才并发上传整段**。而体感“很久”还要叠上 **上传 113–190KB** 在公网 HTTPS 上的耗时（手机常见 0.5–1s+）。本次先做零风险、收益最大的一块：`speechInput.js` 的 `startServer` 把 MediaRecorder 改成 **单声道 opus ~24kbps**（`getUserMedia` 加 `channelCount/sampleRate ideal + echoCancellation/noiseSuppression`，`MediaRecorder` 加 `audioBitsPerSecond:24000`；不强制精确 sampleRate 避免部分设备 NotReadableError）。headless 假麦克风探针 `build/_speech_size_probe.mjs` 实测 2s 假音频：默认 24KB → 新参数 4.5KB，**体积降 5.35x**（真实语音同比例，上传快数倍）。构建 main-DPD3iPXi.js，`_fe_deploy` 前端专属上线，公网 index 已返回该 chunk。（假设备本为单声道，探针里 ch=1 看不出单声道约束效果，但码率才是体积主因。）
- **全量部署到服务器（前端 + 后端 jar）**：用户要求部署。前端本次改动已随 `_fe_deploy` 上线（main-Dvco0kaw.js）。后端走 `_srv_upload`→`_srv_apply`→`_srv_verify`：`gradlew bootJar`（UP-TO-DATE、48.96MB，zip 校验 Speech/AiMetrics/KnowledgeBase/Ollama 等新类均 FOUND）；上传至 staging `app.jar.new`；apply 先备份 `app.jar.bak.20261027_184943` 再 `mv` 替换 + `systemctl restart asean`。**关键发现：换前后 app.jar md5 相同（ea93e655…）→ 服务器本就跑着同一构建，那批未提交 Java 改动其实早已部署**，本次等于原地重启。verify：8080=200、asean active、Spring 无异常启动、nginx 8443 与 driver.html=200、公网 index=main-Dvco0kaw.js、/api/outreach/status=200。（_srv_verify.ps1 末行 `grep|head` 有个 `\r` 残留导致 EXIT=127，是脚本小瑕疵非服务问题。）
- **通关时效「真实抓取」（决赛拿分·任务1）**：交付材料把通关时效标了“真实”，但 `CustomsEfficiencyRepository` 其实只读 classpath 静态 `data/customs-efficiency.json`（人工整理），**没有任何抓取**——是最大的真实性隐患。补上真抓取链路：新建 `CustomsBulletinFetcher`（`java.net.http.HttpClient` 真实出站 GET 政府口岸主管部门公开通报页 → 去标签/实体 + UTF-8↔GBK 自适应解码 → 正则 `进口(?:整体)?通关时间[^0-9。]{0,20}(\d+\.?\d*)\s*小时`（出口同理）抽官方口径时长 → 连同出处（sourceUrl / 从正文解析的 publishDate / 以数字为中心截取的 quote 原句 / 真实 fetchedAt）返回）。**沿用气象那套合规纪律**：`min-request-interval` 默认 24h（通报低频）、手动 `refresh` 也只越窗口不越过 30s 硬地板、连续失败开熔断、抓不到/抽不到数字一律 `ok=false` 如实降级到包内基线，绝不用假数冒充。`CustomsController` 加 `GET /api/customs/bulletin?refresh=`；`application.properties` 加 `customs.bulletin.*`（url 可 env 覆盖）。**口径边界写进注释与界面**：公告“进口整体通关时间”是全区口岸平均（含报关/查验/放行全链，量级小时），与包内按口岸的车辆过口时效（`RoadEdge.customsDelayHours` 1~3h）是两个指标，故只作可溯源官方参考展示、不覆盖算路边权，避免混口径把 ETA 打飞。前端 `App.vue` 通关时效子卡加常驻溯源块 `.bulletin-box`（徽标“官方公告·真实抓取”/进出口小时/发布+抓取时刻/🔗来源/含数字原句/回源·缓存·节流计数），`loadBulletin` 挂 mounted、`刷新公告` 按钮走 `refresh=1`。本地 8099 真跑：`/api/customs/bulletin` 回 `ok=true import=11.65 export=0.38 publish=2025-06-18 fetchedAt=2026-10-28 16:45 upstream=1`；`refresh` 在 30s 地板内 → `fromCache=true upstream 不增 throttled=1`（节流合规）；quote 首版因前找句号截断没含数字，改为“以数字为中心取窗口”后原句含 `11.65小时/0.38小时`。headless CDP 探针 `tools/_customs_bulletin_probe.mjs`（拿 Vue 根 proxy→`openDock('sandbox')`→读 `.bulletin-box`）6/6 全绿 PASS，截图确认渲染。构建 main-1miEjiCj.js。
- **气象专用模型 AMRI（决赛拿分·任务2）**：原「气象→风险」散落在 `RouteService.buildRealRisk` 的裸阈值 `Math.max(penalty,…)` 阶梯里，评委看不到「这条路的红是从哪些气象量、按什么公式合成的」。**关键决策：AMRI 纯加法接入，不改 buildRealRisk**——那套离散档位参与 `RealWeatherService.signatureOf` 内容签名退避与 scenario 预生成缓存，改 reason 字符串会破坏退避/缓存与演示稳定性。新建 `AmriRiskModel`（纯静态工具类，零 Spring 依赖）：三段式可核验模型——① logistic S 型隶属度 `m=1/(1+e^(−k(x−x0)))`（能见度取 x0−x 反向），消除「49.9 绿、50.0 红」悬崖；② 加权概率或融合 `index=100·[1−Π(1−wᵢmᵢ)]`（刻画多灾种叠加，优于取 max）；③ 严重度分档 CRITICAL≥80/HIGH≥45/MEDIUM≥18。四灾种 x0/k/w 依据旧硬阈值反推标定（rain 32/0.15/1.0、wind 50/0.10/0.70、vis 300/0.010/0.60 down、temp 36/0.45/0.25），使 index 在旧阈值处恰好落入对应档，口径与算路一致，非黑箱拟合。`describe()` 原样公开全部参数 + 标定曲线采样点。新建 `RiskModelController` 三端点：`GET /api/risk/model`（下发规格/公式/曲线）、`/model/live?originId&destinationId`（用 `syncRealWeatherForRoute` 沿线实时点算各点 AMRI + 逐灾种贡献，标最不利点）、`/model/nowcast`（用 `ContestForecastService.fetchRain6h` 的 `hourlyMm` 多时效降雨，PRE/3 折成 mm/h 逐时效推指数，风/能见度/气温沿用实况基线，给走廊最不利走势线）。`ContestForecastService` 此前已实现但全仓无调用方，本次首次接线作 nowcasting 数据源。前端新建自包含组件 `AmriPanel.vue`（挂左栏「实时气象」卡后），三页签：标定曲线（内联 SVG 画 S 响应 + 3 条分档参考线 + 当前实况工作点圆点）/ 实时工作点（最不利指数大字 + 逐灾种贡献条 + 沿线排名）/ 短临预报（now→+3h→+6h 折线 + 逐点明细），随 originId/destinationId 变化 watch 自动刷新。本地 8099 真跑：`/model` 回 4 灾种 33 点曲线；`/live` 走真实 CRA40 观测源回 4 点最不利 1.6 LOW 逐灾种贡献；`/nowcast` available=true 起报 202412312000 时效 3,6。今日天气平静、指数低是诚实结果（标定曲线本身与天气无关，始终展示 S 型）。headless CDP 探针 `tools/_amri_probe.mjs` 8/8 PASS（卡片在/标题含AMRI/公式行/曲线≥20点/分档线3条/最不利为数字/贡献条4条/短临折线可用），截图确认 S 曲线渲染。
- **多模态生成·双语预警海报（决赛拿分·任务3）**：三件拿分任务里的「多模态」，在「拍图即解析（需视觉模型、依赖外部能力）」与「双语预警海报（结构化风险数据→图像，确定性生成）」之间选后者——对齐评分口径“多模态生成”，且不引入新依赖/新外部模型、演示稳定可重现。现有 `/api/briefing/generate` 只出纯文本简报，海报是它的视觉/多模态延伸。新建自包含组件 `WarningPoster.vue`（挂左栏 AMRI 面板后）：零依赖用原生 Canvas 2D 直绘竖版海报（720×1180 逻辑尺寸、@2x=1440×2360 导出保证文字清晰），数据复用任务2 的 `/api/risk/model/live`（走廊最不利点 AMRI 指数 + 逐灾种贡献 + severity）。海报内容：风险色带（按档变色）+ 中越双语标题「气象风险预警 / Cảnh báo rủi ro thời tiết」+ 线路「南宁·Nam Ninh → 河内·Hà Nội」+ 大指数/100 + 风险档双语徽标 + 四灾种双语值与贡献条 + 按档位自动切换的双语调度建议 + 页脚数据来源(GOWFS CRA40)/生成时刻/AMRI 声明。`toBlob` 导出 PNG，提供下载与点击放大。越南语变音符靠浏览器系统字体（Segoe UI/YaHei）正常渲染；城市越南语名只收录已知走廊节点（CITY_VI 字典），未命中只显中文不臆造。headless CDP 探针 `tools/_poster_probe.mjs` 7/7 PASS（卡片在/点击生成/预览图加载/src为blob/尺寸1440×2360/画布ink>15%/无报错），截图确认双语海报与变音符渲染。任务3 纯前端（复用已部署的 /api/risk/model/live），上线走 `_fe_deploy` 只换 dist 不重启后端。
- **新功能×原有功能对接（沙盘→AMRI→海报联动）**：用户追问「沙盘拖滑杆 AMRI 跟着变吗」——查链路发现两个断点：① `RiskModelController` 只读 `syncRealWeatherForRoute` 实况观测，而沙盘滑杆写的是 `DecisionSandboxService.sim` 参数盒，唯一消费方是 `AIService.applySandboxOverlay`；② 前端 `AmriPanel` 只在挂载/换起终点时刷新，`onSandboxChange` 不碰它。同时发现一个**存量不一致**：降雨滑杆走 `/api/sandbox/rainfall`→`setRainfall` 只写 `forecastMm` 不写 `sim`，而 `simWeatherOverlay()` 在模拟模式取的是 `sim`（`resolvedElements` SIM 分支）——降雨滑杆对 AI 灾种卡不可见，且下次 `applySimParams` 会把 forecastMm 拉回旧 sim 值抹掉刚拖的雨量。修复+接线：① `DecisionSandboxService` 新增 `syncSimFromSections()`（降雨取 forecastMm、台风激活时气压/风取断面台风值、未激活回落中性 1013/20），在 `setRainfall`/`pushScripted`/`setTyphoon`/`clearTyphoon` 四个直写断面的入口调用；② `RiskModelController` 注入沙盘服务，`/model/live` 把 `simWeatherOverlay()`（与 AI 卡同一数据通路、同口径：6h 累计 mm 直接作为降水强度输入）按 nodeId 叠加到对应气象点再算 AMRI，逐点标 `sandboxSim=true` + 顶层 `sandboxNote`；`/model/nowcast` 只叠加风/能见度/气温基线，+3/+6h 降雨仍由官方预报驱动（滑杆不能规定未来下多大雨，语义写进回包注释）；③ 前端 `App.pingAmri(force)` 广播 `amri-refresh` CustomEvent（onSandboxChange/onQuickInject/onCopilotExecuted/实况刷新/15s 心跳），`AmriPanel` 监听后 `loadLive()` 重拉（force 立刻跟、心跳走 8s 节流），标题出「沙盘模拟叠加」紫色徽标 + 说明条、排名行「拟」标记、曲线圆点注记变「沙盘模拟值」；`WarningPoster` 同事件静默重绘已生成的海报，卡片与页脚加「含沙盘模拟值（非实测）/ Giá trị mô phỏng」双语如实标注。模拟值永远不冒充实测：逐点打标 + 界面/海报双处声明。本地 8091 真跑：`/api/sandbox/rainfall {yggMm:90}` → `/model/live` 回 YGG=100 CRITICAL sandboxSim=true；`sim/params` 风/能见度、`RAIN_YGG` 灾害卡、`/api/sandbox/reset` 回落均验证（灾害卡本就经 `applyScenario→setSimParams` 同步滑杆，接线后自然联动）；CDP 探针 `tools/_amri_link_probe.mjs` 10/10 PASS（基线无徽标→拖 95mm 爬升 100→徽标/说明条/拟标记/圆点注记→模拟态生成海报含标注条→复位回落），截图确认全链路同步（顶部守护横幅同时报熔断）。已部署生产（`_srv_upload`+`_srv_apply`，jar md5 `31d21fc→02f3cd2` 真更新，服务重启 active），线上 CDP 探针 10/10 PASS（https://zhi-ning.cn/ 拖滑杆 95mm→YGG=100 徽标/标注条齐现→复位回落 1.6，评委环境无模拟态残留）；`bafd8be` 已 push origin/main。
- **别用 `Teleport` 搬移带 ref、依赖 SSE 的单例组件**：本想做「左栏↔操作台」来回搬移（`<Teleport :disabled="!open">`），结果点击后整页黑屏，控制台报 `Cannot read properties of null (reading 'insertBefore')` 与 `(reading 'emitsOptions')`——根因是子组件在自己的事件处理里先 `emit('expand')` 触发父级把自身搬移、同一 tick 又继续 `emit`，搬移中实例 `parent` 被置空。改成组件常驻操作台、只用 `v-show` 切显隐（与 `AgentPanel` 单例约束一致）后即稳定。教训：凡是「被多处 ref 引用 + 后台持续通信」的组件，位置固定、只切显隐，不要跨容器搬 DOM。headless CDP 探针点击后截图第一时间暴露了黑屏，避免了把崩溃版部署上去。

---

## 六、开发规范（每次改动都遵守）

> 从 §三/§五 反复踩坑里提炼出的硬性约定：与某一次具体决策无关、可长期复用的才写在这里。新改动默认按此执行。

### 验证与取证
- **任何「已修好 / 已生效」的结论都要有当场证据**：能自动化的用 headless CDP 探针（`build/_*.mjs`、`tools/_*.mjs`：`Runtime.evaluate` 读 `getComputedStyle`/DOM 状态 + `Page.captureScreenshot` 出图），不能自动化的至少跑一次真实接口、打印原始回包。没有证据就写「未验证」，不写「应该没问题」。
- **探针要选对观测量**：读几何用地理坐标而非屏幕坐标、读节奏用真实 tick 间隔而非名义常量、读样式用 `getComputedStyle` 而非 class 名——选错坐标系/标称值会造出假 PASS 或假 FAIL。
- **自检脚本也要自检**：探测失败要能区分「服务没起」与「探测路径写错」，静默 skip 比显式报错更危险；跑完必须确认它真产生了预期 PASS 行。

### 构建与部署
- **前端**：`cd frontend; npm run build` → 产物 `dist/assets/main-<hash>.js`；`index.html` 带 `no-cache`，换内容哈希即生效，无需重启。
- **上线只换静态、不动后端**：前端专属部署走 `tools/_fe_deploy.ps1`（tar→scp→备份→换 `/opt/asean/frontend/dist`），不碰 `app.jar`、不重启 `asean`；只有后端改动才需要 `_srv_upload.ps1` + 重启。
- **本地 dev（5173 Vite）与生产（Spring 托管 `frontend/dist`）是两套**：改完提醒用户刷新对应端，别把「本地没热更新」当成「没生效」。

### 版本控制
- **不擅自 `commit`**：只 `git add` 本轮改动的源文件，提交信息留给用户；`.gitignore` 命中的产物/探针脚本（`build/`、多数 `tools/_*`）本就不入库。
- **被 import 的新源文件必须入库**：提交前跑 `tools/_clone_integrity.mjs`（依赖闭包 vs `git ls-files`）+ `tools/_make_clone.mjs` 只读复制后真跑构建/测试，防「本地能跑、评委 clone 后断链」。
- **凭证零入库**：气象 Token / 天地图密钥 / DeepSeek / 阿里云 AK 一律只进 `.env.local`，文档与打包脚本强制校验仓库与前端为零。

### 前端代码约定
- **单例、被多处 ref 引用、依赖后台通信（SSE）的组件：位置固定、只用 `v-show` 切显隐，绝不 `Teleport` 跨容器搬 DOM**（搬移会在自身事件 `emit` 的同一 tick 把实例 `parent` 置空 → 整页黑屏）。
- **加新按钮变体先看全局兜底规则**：`App.vue` 有 `.btn:not(.primary):not(.outline)…`（特异度 0,7,0）会把没被列举的 `.btn` 刷成灰色——新变体必须同时进该 `:not()` 排除列表，否则样式被无声覆盖。
- **中文一律写字面量，绝不手写 `\uXXXX` 码位转义**（会产错字）；能力声明必须能在代码里指出对应那一行，指不出就写「局限」而不是写「能力」。
- **UI 主次**：结论（`summary`）优先于过程旁白（`say`）；溯源/元信息可 hover 查但不每屏占位。

### 终端与脚本（Windows / PowerShell）
- **不要把复杂 PowerShell 塞进 `bash → powershell -Command "..."` 内联**：`$r`/`$_`/`$LASTEXITCODE` 会被中间层剥离成语法错误。逻辑写成 `.ps1` 文件用 `-File` 执行；公网验证用 `curl.exe -s -o 文件` + `findstr`。
- **命令分隔用 `;` 不用 `&&`**（本 shell 不支持 `&&`）。
- **常驻进程交给后台终端（`is_background=true`）**，别用 `Start-Process` 随前台命令结束被连带杀掉；跑测试前先停占用 8080 的 java（bootRun 会锁 `build/resources`）。
