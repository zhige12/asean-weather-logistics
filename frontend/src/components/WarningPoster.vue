<template>
  <section class="card poster-card">
    <h3>
      多模态 · 中越英三语预警海报
      <span class="route-state" :class="genStateClass">{{ genStateText }}</span>
    </h3>
    <div class="muted poster-hint">
      把当前走廊的 AMRI 风险研判，一键生成<b>中 / 越 / 英三语</b>预警海报（结构化风险数据 → 图像，多模态生成）；
      可下载 PNG 发到司机群 / 口岸联络，离线也能看。文案与配色按风险档位自动切换。
    </div>
    <div class="row" style="margin-top:8px">
      <button class="btn primary" :disabled="busy" @click="generate">
        {{ busy ? '生成中…' : (posterUrl ? '重新生成海报' : '生成三语预警海报') }}
      </button>
      <button v-if="posterUrl" class="btn outline" @click="download">下载 PNG</button>
    </div>
    <div v-if="errorMsg" class="poster-err">{{ errorMsg }}</div>
    <div v-if="live && live.sandboxSim" class="muted poster-sim">
      当前走廊含<b>沙盘模拟值</b>（滑杆推演、非实测），海报页脚已如实标注。
    </div>
    <div v-if="posterUrl" class="poster-preview">
      <img :src="posterUrl" alt="中越英三语预警海报" @click="openBig" />
      <div class="muted poster-tip">点击海报可放大查看 · 底部「下载 PNG」保存分享</div>
    </div>
    <!-- 画布离屏用于绘制，不参与布局 -->
    <canvas ref="cv" class="poster-canvas" />
  </section>
</template>

<script>
import axios from 'axios';

// 中越双语字典：城市名仅收录已知走廊节点，未收录者如实只显示中文，绝不臆造越南语
const CITY_VI = {
  南宁: 'Nam Ninh', 河内: 'Hà Nội', 同登: 'Đồng Đăng', 友谊关: 'Cửa Hữu Nghị',
  芒街: 'Móng Cái', 河口: 'Lào Cai', 凭祥: 'Bằng Tường', 谅山: 'Lạng Sơn',
  海防: 'Hải Phòng', 下龙: 'Hạ Long', 老街: 'Lào Cai', 太原: 'Thái Nguyên'
};
const CITY_EN = {
  南宁: 'Nanning', 河内: 'Hanoi', 同登: 'Dong Dang', 友谊关: 'Friendship Pass',
  芒街: 'Mong Cai', 河口: 'Lao Cai', 凭祥: 'Pingxiang', 谅山: 'Lang Son',
  海防: 'Hai Phong', 下龙: 'Ha Long', 老街: 'Lao Cai', 太原: 'Thai Nguyen'
};
const SEV = {
  CRITICAL: { cn: '极高风险', vi: 'Rủi ro rất cao', en: 'Very high risk', color: '#d32f2f',
    advCn: '建议暂缓发运或改道，避开强降雨 / 大雾时段；已在途车辆就近安全点待命，调度全程值守。',
    advVi: 'Khuyến nghị hoãn hoặc đổi tuyến, tránh mưa lớn / sương mù; xe đang chạy chờ tại điểm an toàn gần nhất, điều phối giám sát 24/7.',
    advEn: 'Postpone or reroute shipments; avoid heavy-rain / dense-fog windows. Vehicles in transit hold at the nearest safe point with dispatcher monitoring throughout.' },
  HIGH: { cn: '高风险', vi: 'Rủi ro cao', en: 'High risk', color: '#f57c00',
    advCn: '谨慎派单，预留缓冲时间并准备公路 / 水运互备方案，全程开启气象守护与动态重算。',
    advVi: 'Điều phối thận trọng, dự phòng thời gian và phương án thay thế đường bộ / đường thủy, bật giám sát thời tiết và tính lại tuyến liên tục.',
    advEn: 'Dispatch cautiously with time buffers and road / waterway backup plans; keep weather guard and dynamic rerouting on.' },
  MEDIUM: { cn: '中等风险', vi: 'Rủi ro trung bình', en: 'Medium risk', color: '#f9a825',
    advCn: '关注沿线天气变化，备好备选路线；冷链 / 危化货物加强在途监控。',
    advVi: 'Theo dõi diễn biến thời tiết dọc tuyến, chuẩn bị tuyến dự phòng; tăng giám sát hàng lạnh / hàng nguy hiểm.',
    advEn: 'Watch weather along the route and prepare alternates; strengthen in-transit monitoring for cold-chain / hazardous cargo.' },
  LOW: { cn: '低风险', vi: 'Rủi ro thấp', en: 'Low risk', color: '#2e7d32',
    advCn: '当前沿线气象条件良好，可按计划正常发运，系统持续后台守护。',
    advVi: 'Điều kiện thời tiết dọc tuyến đang tốt, có thể vận chuyển theo kế hoạch; hệ thống tiếp tục giám sát nền.',
    advEn: 'Weather along the route is currently good; ship as planned while the system keeps monitoring in the background.' }
};
const HZ = {
  rain: { cn: '降雨', vi: 'Lượng mưa', en: 'Rainfall', unit: 'mm/h' },
  wind: { cn: '风速', vi: 'Tốc độ gió', en: 'Wind speed', unit: 'km/h' },
  vis: { cn: '能见度', vi: 'Tầm nhìn', en: 'Visibility', unit: 'm' },
  temp: { cn: '气温', vi: 'Nhiệt độ', en: 'Temperature', unit: '°C' }
};
const SRC_CN = { 'contest-observation': 'GOWFS CRA40 官方观测', 'open-meteo': 'Open-Meteo 实况', simulated: '离线模拟', none: '缓存/降级' };

// 逻辑设计尺寸（竖版海报，适合手机分享）；实际按 SCALE 倍绘制保证文字清晰
const W = 720;
const H = 1320;
const SCALE = 2;

export default {
  name: 'WarningPoster',
  props: {
    originId: { type: String, default: 'NN' },
    destinationId: { type: String, default: 'HN' },
    originName: { type: String, default: '南宁' },
    destinationName: { type: String, default: '河内' }
  },
  data() {
    return { busy: false, posterUrl: '', errorMsg: '', live: null, model: null };
  },
  mounted() {
    // 已生成过海报时，沙盘滑杆/灾害卡变动就静默重绘：海报跟得上研判，不靠手动刷新
    this._onAmriEvent = () => { if (this.posterUrl && !this.busy) this.generate(); };
    window.addEventListener('amri-refresh', this._onAmriEvent);
  },
  beforeUnmount() {
    if (this._onAmriEvent) window.removeEventListener('amri-refresh', this._onAmriEvent);
    if (this.posterUrl) URL.revokeObjectURL(this.posterUrl);
  },
  computed: {
    genStateClass() {
      const s = this.live && this.live.worst ? this.live.worst.severity : null;
      if (s === 'CRITICAL' || s === 'HIGH') return 'blocked';
      if (s === 'MEDIUM') return 'warn';
      return this.posterUrl ? 'ok' : '';
    },
    genStateText() {
      if (!this.live || !this.live.worst) return this.posterUrl ? '已生成' : '待生成';
      return this.live.worst.severity + ' · ' + (SEV[this.live.worst.severity] || {}).cn;
    }
  },
  methods: {
    async generate() {
      this.busy = true;
      this.errorMsg = '';
      try {
        const od = { originId: this.originId, destinationId: this.destinationId };
        const [live, model] = await Promise.all([
          axios.get('/api/risk/model/live', { params: od, timeout: 45000 }),
          this.model ? Promise.resolve({ data: this.model }) : axios.get('/api/risk/model', { timeout: 15000 })
        ]);
        this.live = live.data;
        this.model = model.data.model || model.data;
        if (!this.live.worst) {
          this.errorMsg = this.live.message || '暂无沿线气象点，无法生成海报（请先在「实时气象」拉取数据）。';
          return;
        }
        this.render();
      } catch (e) {
        this.errorMsg = '生成失败：' + (e.response?.data?.message || e.message || '接口暂不可达');
      } finally {
        this.busy = false;
      }
    },
    render() {
      const cv = this.$refs.cv;
      cv.width = W * SCALE;
      cv.height = H * SCALE;
      const ctx = cv.getContext('2d');
      ctx.scale(SCALE, SCALE);
      const worst = this.live.worst;
      const sev = SEV[worst.severity] || SEV.LOW;

      // 背景
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, W, H);

      // 顶部风险色带（三语：中 / 越 / 英）
      ctx.fillStyle = sev.color;
      ctx.fillRect(0, 0, W, 214);
      ctx.fillStyle = '#ffffff';
      ctx.textBaseline = 'alphabetic';
      ctx.font = 'bold 44px "Microsoft YaHei", "Segoe UI", sans-serif';
      ctx.fillText('气象风险预警', 40, 66);
      ctx.font = '24px "Segoe UI", "Arial", sans-serif';
      ctx.fillText('Cảnh báo rủi ro thời tiết', 40, 100);
      ctx.fillText('Weather Risk Alert', 40, 130);
      ctx.font = '20px "Microsoft YaHei", sans-serif';
      ctx.fillText('跨境公路物流 · 东盟气象路网', 40, 164);
      ctx.font = '16px "Segoe UI", "Arial", sans-serif';
      ctx.fillText('Cross-border Road Logistics · ASEAN Meteorological Route Network', 40, 190);

      // 走廊线路（中文主行 + 越/英拉丁副行，避免单行三语放不下被右边缘裁切）
      let y = 262;
      const oVi = CITY_VI[this.originName] || '';
      const dVi = CITY_VI[this.destinationName] || '';
      const oEn = CITY_EN[this.originName] || '';
      const dEn = CITY_EN[this.destinationName] || '';
      const oTag = [oVi, oEn].filter(Boolean).join(' / ');
      const dTag = [dVi, dEn].filter(Boolean).join(' / ');
      ctx.fillStyle = '#37474f';
      ctx.font = 'bold 34px "Microsoft YaHei", sans-serif';
      ctx.fillText(`${this.originName}  →  ${this.destinationName}`, 40, y);
      if (oTag || dTag) {
        y += 30;
        ctx.fillStyle = '#78909c';
        ctx.font = '21px "Segoe UI", "Arial", sans-serif';
        ctx.fillText(`${oTag || this.originName}  →  ${dTag || this.destinationName}`, 40, y);
      }

      // 最不利点
      y += 40;
      ctx.fillStyle = '#78909c';
      ctx.font = '20px "Microsoft YaHei", sans-serif';
      ctx.fillText(`沿线最不利点 · Điểm bất lợi nhất · Worst point: ${worst.nodeName || worst.nodeId}`, 40, y);

      // AMRI 大指数
      y += 30;
      ctx.fillStyle = sev.color;
      ctx.font = 'bold 132px "Segoe UI", "Microsoft YaHei", sans-serif';
      ctx.fillText(String(worst.index), 40, y + 118);
      const idxW = ctx.measureText(String(worst.index)).width;
      ctx.fillStyle = '#455a64';
      ctx.font = 'bold 30px "Segoe UI", sans-serif';
      ctx.fillText('/ 100', 40 + idxW + 12, y + 78);
      ctx.font = '24px "Microsoft YaHei", sans-serif';
      ctx.fillStyle = '#607d8b';
      ctx.fillText('AMRI 风险指数', 40 + idxW + 12, y + 112);
      // 风险档徽标（右侧，三语）
      const badgeX = W - 40 - 240;
      ctx.fillStyle = sev.color;
      this.roundRect(ctx, badgeX, y + 18, 240, 122, 14);
      ctx.fill();
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 32px "Microsoft YaHei", sans-serif';
      ctx.fillText(sev.cn, badgeX + 22, y + 58);
      ctx.font = '20px "Segoe UI", sans-serif';
      ctx.fillText(sev.vi, badgeX + 22, y + 90);
      ctx.fillText(sev.en, badgeX + 22, y + 118);

      y += 190;

      // 分隔线
      ctx.strokeStyle = '#eceff1';
      ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(40, y); ctx.lineTo(W - 40, y); ctx.stroke();

      // 四灾种贡献（2×2，三语）
      y += 34;
      const cells = worst.contributions || [];
      const colW = (W - 80) / 2;
      const rowH = 112;
      cells.forEach((c, i) => {
        const cx = 40 + (i % 2) * colW;
        const cy = y + Math.floor(i / 2) * rowH;
        const hz = HZ[c.id] || { cn: c.label, vi: '', en: '', unit: c.unit };
        ctx.fillStyle = '#37474f';
        ctx.font = 'bold 25px "Microsoft YaHei", sans-serif';
        ctx.fillText(`${hz.cn}`, cx, cy + 20);
        ctx.fillStyle = '#90a4ae';
        ctx.font = '16px "Segoe UI", sans-serif';
        ctx.fillText([hz.vi, hz.en].filter(Boolean).join(' / '), cx, cy + 42);
        ctx.fillStyle = sevColorFor(c.membership);
        ctx.font = 'bold 30px "Segoe UI", "Microsoft YaHei", sans-serif';
        const valTxt = c.value == null ? '缺测' : `${c.value} ${c.unit}`;
        ctx.fillText(valTxt, cx + 150, cy + 26);
        // 贡献条
        const barX = cx, barY = cy + 72, barW = colW - 30;
        ctx.fillStyle = '#eceff1';
        this.roundRect(ctx, barX, barY, barW, 12, 6); ctx.fill();
        ctx.fillStyle = sevColorFor(c.membership);
        this.roundRect(ctx, barX, barY, Math.max(6, barW * Math.min(1, c.membership)), 12, 6); ctx.fill();
      });

      y += 2 * rowH + 10;

      // 建议（三语）
      ctx.strokeStyle = '#eceff1';
      ctx.beginPath(); ctx.moveTo(40, y); ctx.lineTo(W - 40, y); ctx.stroke();
      y += 34;
      ctx.fillStyle = sev.color;
      ctx.font = 'bold 24px "Microsoft YaHei", sans-serif';
      ctx.fillText('调度建议 · Khuyến nghị điều phối · Dispatch Advisory', 40, y);
      y += 32;
      ctx.fillStyle = '#37474f';
      ctx.font = '22px "Microsoft YaHei", sans-serif';
      y = this.wrap(ctx, sev.advCn, 40, y, W - 80, 32);
      y += 4;
      ctx.fillStyle = '#78909c';
      ctx.font = 'italic 18px "Segoe UI", sans-serif';
      y = this.wrap(ctx, sev.advVi, 40, y, W - 80, 26);
      y += 2;
      ctx.fillStyle = '#90a4ae';
      ctx.font = 'italic 18px "Segoe UI", sans-serif';
      y = this.wrap(ctx, sev.advEn, 40, y, W - 80, 26);

      // 页脚（三语）
      const src = SRC_CN[this.live.servedSource] || this.live.servedSourceLabel || this.live.servedSource || '—';
      ctx.fillStyle = '#b0bec5';
      ctx.font = '17px "Microsoft YaHei", sans-serif';
      const now = new Date().toLocaleString('zh-CN', { hour12: false });
      let fy = H - 60;
      if (this.live.sandboxSim) {
        // 如实标注：滑杆推演值不是实测，海报流转到人手里也不能假装是官方数据
        ctx.fillStyle = '#b71c1c';
        ctx.font = 'bold 17px "Microsoft YaHei", sans-serif';
        ctx.fillText('＊沙盘模拟值（非实测）/ Mô phỏng / Simulated (not measured)', 40, fy);
        fy += 24;
        ctx.fillStyle = '#b0bec5';
        ctx.font = '17px "Microsoft YaHei", sans-serif';
      }
      ctx.fillText(`数据来源 Nguồn / Source: ${src} · 模型 Mô hình: AMRI`, 40, fy);
      ctx.fillText(`生成 Thời gian / Generated: ${now} · 东盟气象路网风险指数 (ASEAN Meteorological Route Index)`, 40, fy + 24);

      // 导出
      if (this.posterUrl) URL.revokeObjectURL(this.posterUrl);
      cv.toBlob(blob => {
        this.posterUrl = URL.createObjectURL(blob);
      }, 'image/png');
    },
    roundRect(ctx, x, y, w, h, r) {
      ctx.beginPath();
      ctx.moveTo(x + r, y);
      ctx.arcTo(x + w, y, x + w, y + h, r);
      ctx.arcTo(x + w, y + h, x, y + h, r);
      ctx.arcTo(x, y + h, x, y, r);
      ctx.arcTo(x, y, x + w, y, r);
      ctx.closePath();
    },
    wrap(ctx, text, x, y, maxW, lh) {
      let line = '';
      for (const ch of text) {
        const test = line + ch;
        if (ctx.measureText(test).width > maxW && line) {
          ctx.fillText(line, x, y);
          line = ch;
          y += lh;
        } else {
          line = test;
        }
      }
      if (line) { ctx.fillText(line, x, y); y += lh; }
      return y;
    },
    download() {
      if (!this.posterUrl) return;
      const a = document.createElement('a');
      a.href = this.posterUrl;
      a.download = `气象预警海报_${this.originName}_${this.destinationName}_${Date.now()}.png`;
      a.click();
    },
    openBig() {
      if (this.posterUrl) window.open(this.posterUrl, '_blank');
    }
  }
};

// 按隶属度给单项贡献配色（与面板一致的低/中/高/极高语义）
function sevColorFor(membership) {
  const m = membership || 0;
  if (m >= 0.8) return '#d32f2f';
  if (m >= 0.5) return '#f57c00';
  if (m >= 0.25) return '#f9a825';
  return '#2e7d32';
}
</script>

<style scoped>
.poster-card .poster-hint { line-height: 1.6; }
.poster-sim { margin-top: 6px; color: #6d28d9; }
.poster-err { color: #c62828; font-size: 12px; margin-top: 6px; }
.poster-preview { margin-top: 10px; }
.poster-preview img {
  width: 100%; max-width: 320px; display: block; margin: 0 auto; border-radius: 10px;
  border: 1px solid #e0e3ea; box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12); cursor: zoom-in;
}
.poster-tip { text-align: center; margin-top: 6px; }
.poster-canvas { display: none; }
</style>
