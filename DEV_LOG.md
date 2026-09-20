# 东盟跨境物流气象导航平台 · 开发工作日志

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
- `AIService` / `DeepseekService`：DeepSeek 公司网关 → 在线 DeepSeek 备用通道 → 内置离线模板（规则引擎），三级降级保证"拔网线可演示"
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

---

## 三、Bug 修复记录

| 问题 | 现象 | 处理 |
|---|---|---|
| 地图缩放空白/路线消失 | 首次缩放后画面空白，需缩放两次才显示路线 | 诊断：数据链路全正常（瓦片 200、network 200），定位为 Leaflet 缩放动画与 MapLibre GL 5 相机管线兼容问题（`maplibre-gl-leaflet` 在 zoomAnimation 中对 WebGL canvas 做 transform，与 v5 不兼容）。曾尝试 `zoomAnimation: false` 修复，但效果更差（全程空白），**已回退恢复 `zoomAnimation: true`** |
| （待验证）缩放兼容性 | 缩放动画仍可能存在与 MapLibre GL 5 的不稳定表现 | 待后续：升级 `maplibre-gl` 版本、给插件打补丁或改用纯 MapLibre 渲染 |
| 路线不贴合道路（穿山/切弯） | 路网转换脚本 DP 抽稀容差 0.0006°（≈66m）过粗，52382/62090 条边几何只剩起终点 2 点，山区弯道全部被切成直线 | **根治**：`convert_vietnam_pbf.py` 容差降到 0.0001°（≈11m）重新生成（节点/边数量不变，路由不受影响），>1km 长边平均几何 18.5 点；JSON 改紧凑输出，体积 36MB→22MB。配套：`RouteService.toCoords` 新增 `pathEdgeSpans`（每条边在 pathCoords 中的下标区间），RouteResponse/候选 DTO 下发；司机端风险段标红与 `focusRisk` 改按区间取点（几何加密后点数≠边数，原下标直取会错位） |

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
- **AI 降级链**：DeepSeek 公司网关（主）→ 在线 DeepSeek（备）→ 规则引擎离线模板，保证断网环境演示不翻车。
- **实时守护闭环**：SSE + 300ms 防抖重算，演示"突发灾害马上出新路线"。
- **启动预热**：提前触发 Dijkstra JIT，首点规划毫秒级。
