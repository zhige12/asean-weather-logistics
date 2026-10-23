# 复现与运行说明

面向东盟跨境物流的突发气象预警与多路线协同调度平台。后端 Spring Boot 4.1.0（REST，默认端口 8080），前端 Vue 3 + Vite + Leaflet 1.9.4（Canvas 渲染），司机端可 Capacitor 打包 Android，也可直接用浏览器打开。

## 一、快速编译运行（不填任何密钥即可跑通全部核心功能）

1. **环境**：JDK 21 及以上（本机没有时 Gradle toolchain 会自动下载）；Node 20 及以上。Gradle 无需另装，用仓库自带的 `gradlew`。
2. **配置**：`copy .env.example .env.local`（可以全部留空，官方数据要核验时再填自己的凭证）。
3. **后端编译 + 单元测试**：`gradlew.bat compileJava compileTestJava test`
4. **前端构建**：`cd frontend && npm ci && npm run build`（产物 `frontend/dist` 由后端静态托管）。
5. **启动**：`gradlew.bat bootRun`，随后打开
   - 调度大屏　`http://localhost:8080/`
   - 司机端　　　`http://localhost:8080/driver.html`

> 国内直连 Maven Central / npm / services.gradle.org 偶发较慢时，可配置国内镜像或代理加速。

## 二、开箱即用（数据随包，无需网络与密钥）

以下核心能力只依赖已随源码分发到 `src/main/resources/data/` 的数据，编译即可复现，逻辑与开发者机器完全一致：

- 跨境路网算路与逐边风险打分（`vietnam-road-network.json`，约 8.6 万节点 / 9.8 万边）；
- 天气 → 风险 → 算路熔断联动、台风气压+风速双条件判定、陆水联运双向切换；
- 决策沙盘（拉气压/风速滑杆实时推演熔断、改走、禁航）、分级叫应与决策留痕；
- 司机端任务接收 / 换线 / 双语语音、SSE 实时推送；
- 通关时效（`customs-efficiency.json`）、行业知识库（`knowledge-base.json`）、地名库（`place-names.json`，52 条）。

## 三、需自行申请凭证才能解锁的三项（不填则自动降级，功能不中断）

界面来源标签会如实显示当前实际生效的通道，不伪造；各项凭证的申请途径与降级行为如下表所示。

| 能力 | 需要的凭证（写入 `.env.local`） | 缺失时的降级行为 |
|---|---|---|
| 官方气象 CRA40 实况/预报 | `WEATHER_REAL_TOKEN`（组委会随参赛通知下发） | 降级公网 Open-Meteo → 内置模拟 |
| 影像 / 矢量底图 | `TIANDITU_KEYS`（服务端类型密钥 + 出口 IP 白名单） | `/api/tianditu/**` 返回 503，前端降级 OSM 在线；无网络则底图偏空 |
| 在线大模型推理 | `DEEPSEEK_FALLBACK_KEY` | 优先本地 Ollama；都没有则落离线文案池 / 规则模板 |

## 四、与可运行 Demo 包的差异

本源码包出于**脱敏**（绝不携带任何真实密钥）与**体积**考虑，不含：`.env.local`、离线影像瓦片缓存、`decision-cache` 预生成文案池。

若需要"拔网线也能出高清影像 + 官方真实气象 + 真实模型输出"的完整演示效果，请使用随附的 **`asean-demo-offline.zip`**（内嵌 JRE、预热瓦片、决策缓存，双击 `start.bat` 即可）——源码包与 Demo 包互补交付。
