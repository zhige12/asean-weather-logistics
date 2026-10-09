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
- **离线矢量底图**：本地 planetiler 生成 MVT 瓦片，后端 `/tiles` 托管，完全离线可用、无第三方在线依赖

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

**矢量瓦片**：用 planetiler 从同一 PBF 生成 z0-14 越南 MVT 瓦片（`tools/data/tiles/`），OpenMapTiles schema，样式文件 `frontend/src/data/local-base-style.js`。

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

**配置**：`WebConfig` 配 CORS（`/api/**` 全开放）+ 把 `tools/data/tiles` 映射为 `/tiles/**` 托管矢量瓦片。

### 3. 前端（Vue 3.5 + Vite + Leaflet 1.9.4 + MapLibre GL 5.0）

双入口：`src/main.js`（调度大屏）、`src/driver/main.js`（司机端），`vite.config.js` 双入口打包 + `/api` 代理到 8080。

| 文件 | 核心功能 |
|---|---|
| `src/App.vue` | 调度大屏：路径规划（下拉/地图选点）、灾害场景注入、实时气象、通关时效调整、路线对比、AI 双语预警、AI 灾害预测、知识库检索、当前风险列表；订阅 SSE + 15 秒轮询兜底 |
| `src/components/MapView.vue` | 地图组件（Leaflet + MapLibre 混用）：底图四级切源（本地矢量→高德→OSM→Carto）+ 瓦片自愈巡检；中文地名分级注记（`zh-places.json`）；口岸节点（divIcon）、风险边（橙色）、口岸虚拟边（红色）、路线（通畅绿/绕行红）、基准路线（灰虚线）、地图选点 |
| `src/driver/DriverApp.vue` | 司机端（手机竖屏）：状态卡（通畅/预警/绕行）、沿线实时气象、风险路段列表、AI 双语预警、通关提示、知识库速查 |
| `src/data/local-base-style.js` | MapLibre 矢量底图样式（OpenMapTiles schema），数据源指向 `/tiles/{z}/{x}/{y}.pbf` |
| `src/data/zh-places.json` | 80+ 中文地名注记，level 1-3 分级显隐 |

### 4. 数据合规确认（答辩加分项）

- 地图底图与路网数据均派生自 OpenStreetMap（ODbL 许可），界面已标注 `© OpenStreetMap contributors (ODbL)`
- 默认使用**本地自建矢量瓦片**，不依赖官方在线瓦片服务，规避 Tile Usage Policy 限制
- 初赛展示为非商业内部使用，ODbL 共享义务不触发，无版权纠纷风险

### 5. 联调与运维

- 后端：`gradlew bootRun`，bootrun*.log 记录各次启动
- 前端：Vite dev server（5173），`/api` 代理后端
- 数据验证：瓦片接口 `/tiles/...pbf` 返回 200；路网接口 `/api/route/network` 返回 200（23MB 全量加载正常）
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
| 地图缩放空白/路线消失 | 首次缩放后画面空白，需缩放两次才显示路线 | 诊断：数据链路全正常（瓦片 200、network 200），定位为 Leaflet 缩放动画与 MapLibre GL 5 相机管线兼容问题（`maplibre-gl-leaflet` 在 zoomAnimation 中对 WebGL canvas 做 transform，与 v5 不兼容）。曾尝试 `zoomAnimation: false` 修复，但效果更差（全程空白），**已回退恢复 `zoomAnimation: true`** |
| （待验证）缩放兼容性 | 缩放动画仍可能存在与 MapLibre GL 5 的不稳定表现 | 待后续：升级 `maplibre-gl` 版本、给插件打补丁或改用纯 MapLibre 渲染 |
| 路线不贴合道路（穿山/切弯） | 路网转换脚本 DP 抽稀容差 0.0006°（≈66m）过粗，52382/62090 条边几何只剩起终点 2 点，山区弯道全部被切成直线 | **根治**：`convert_vietnam_pbf.py` 容差降到 0.0001°（≈11m）重新生成（节点/边数量不变，路由不受影响），>1km 长边平均几何 18.5 点；JSON 改紧凑输出，体积 36MB→22MB。配套：`RouteService.toCoords` 新增 `pathEdgeSpans`（每条边在 pathCoords 中的下标区间），RouteResponse/候选 DTO 下发；司机端风险段标红与 `focusRisk` 改按区间取点（几何加密后点数≠边数，原下标直取会错位） |
| （已修复）AI 文案谎称本地 | `aiPowered` 仅表示“模型返回了非空文本”，不区分命中哪级通道；本地 Ollama 一超时/显存被占就静默降级到在线 DeepSeek，而 `AgentPanel.vue` 仍按「本地大模型」展示，与 README“数据不出境”直接矛盾（实测：11434 挂时 reroute 结果全由 api.deepseek.com 生成） | `DeepseekService` 新增 `chatWithChannel()` 返回 `AiReply{text,channel,model}`（保留 `chat()` 旧签名不影响其他调用方）；RiskAgent / SolutionAgent / TouchAgent 三个结果 record 各加 `aiChannel`；`DecisionOrchestrator` 取 `worstChannel()` 汇总并输出 `aiChannelLabel`；前端改成四档真实通道标签（本地/在线非本地/规则模板/预生成未记录），每个智能体节点各自标注。旧 decision-cache 无法核实来源，读到旧记录时强制降为 `unrecorded` |
| 方案 C「原地等待」车标仍前进 | 大屏下发原地等待、司机端确认接收后，导航里的车辆图标照旧沿路线爬。`_switchNavForPlan('C')` 只 `showPush` 就 return，驱动图标前进的模拟行驶定时器 `_triTimer` 从头到尾没被停过 | 新增 `waitHold` 停驶态：`_applyWaitHold()` 关掉 GPS 探测与推进定时器、把车钉在当前里程位置；`_start/_resumeTripSimulation`、`_startRealTimeGPS`、`_tryCapacitor/_BrowserGPS` 及其定位回调、tick 均按 `waitHold` 短路（否则底图整图重建的恢复分支、`await` 权限后迟到的 watchPosition 回调会把车重新开起来）；导航底部加「⏸ 原地等待中 · 车辆停驶」条 + 「恢复行驶」按钮（从当前里程续跑、不回起点）；改派 A/B、司机自行出发、退出导航均清除停驶态 |
| 弱机上「点什么都没反应」（不只是地图卡） | 同事电脑跑离线包：拖图卡，点各种按钮也发滞；同一份代码在本机顺畅 | 根因不在地图，在**常驻逐帧重绘**：`.panel`（360px 宽、满屏高，所有按钮都在里面）同时挂 `backdrop-filter: blur(20px)` + `prism-drift`（动 background-position）+ `prism-glow`（动 inset box-shadow），`.app-header` 与全屏极光层同类，routeFlow 的 `.rf-arrow-pulse` 以 60fps 重算 `filter: drop-shadow()`——background-position / box-shadow / filter 三者都交不给合成器，只能整幅重绘，主线程被绘制占满后输入事件排队，于是“点什么都没反应”。修：新增 `frontend/src/utils/perfMode.js`，用 `<html data-perf-lite="on">` + 注入 `!important` 样式一次性关掉这些装饰（刻意不用 `* { animation: none }`，否则连加载转圈也一起停掉），`MapView` 按 `isLite()` 以 `lite/fps:24/maxArrows:8` 重建特效层（切换时还要清 `_lastRouteSig`，否则被“同一张图”免疫直接 return）；判定分三层：用户手点（写 localStorage，优先）→ 静态弱机信号（≤2 核 / ≤4GB / 系统要求减少动效）→ 首屏 5 秒后实测 1.2 秒帧率，`fps<40` 或单帧 >250ms 则自动降级且**不写** localStorage。实测（CDP + CPU 节流）：20x 节流下采样到 5fps / 645ms → 自动开启、按钮变「⚡ 轻界面（自动）」、`.panel` animation 转 none、localStorage 仍为 null；不节流时 175fps / 38ms → 保持满特效不误伤。后端同批改两处内存陷阱：`MEM_CACHE_MAX_ENTRIES` 由写死 10 万改为 `tianditu.mem-cache-entries`（默认 2 万，包内 5000——包内总共才 29493 张瓦片，且磁盘才是主缓存），`start.bat` 的 `-Xmx3g` 改为按物理内存自适应（≥16GB 3g / ≥8GB 2g / 其余 1g）并在黑窗打印检测值 |

---

## 四、遗留事项 / 风险提示

1. **缩放兼容性问题未根治**：当前保留 `zoomAnimation: true`（与问题出现前行为一致），"首次缩放需二次触发"的问题仍存在，暂不阻塞演示（可通过先缩放一次规避），答辩前建议优先解决。
2. **DeepSeek API key** 已迁移至 `.env.local`（`DEEPSEEK_FALLBACK_KEY`），不在 `application.properties` 中硬编码。
3. **OSM 在线源仅作备用**：对外发布前建议移除高德/OSM/Carto 在线源，只保留本地瓦片，并在关于页写明数据来源。
4. 旧版测试路网 `road-network.json`（1KB）与主路网并存，未清理。

---

## 五、关键技术决策备忘

- **路网数据**：弃用早期 OSMnx 在线下载方案（`download_road.py`），改用离线 PBF + 自研解析脚本，保证可复现、可离线。
- **底图方案**：弃用在线栅格，改 planetiler 本地矢量瓦片，全离线、无授权风险，是演示核心亮点。
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
