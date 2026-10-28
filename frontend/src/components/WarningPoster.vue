<template>
  <section class="card poster-card">
    <h3>
      多模态 · 双语预警海报
      <span class="route-state" :class="genStateClass">{{ genStateText }}</span>
    </h3>
    <div class="muted poster-hint">
      把当前走廊的 AMRI 风险研判，一键生成<b>中越双语</b>预警海报（结构化风险数据 → 图像，多模态生成）；
      可下载 PNG 发到司机群 / 口岸联络，离线也能看。文案与配色按风险档位自动切换。
    </div>
    <div class="row" style="margin-top:8px">
      <button class="btn primary" :disabled="busy" @click="generate">
        {{ busy ? '生成中…' : (posterUrl ? '重新生成海报' : '生成双语预警海报') }}
      </button>
      <button v-if="posterUrl" class="btn outline" @click="download">下载 PNG</button>
    </div>
    <div v-if="errorMsg" class="poster-err">{{ errorMsg }}</div>
    <div v-if="live && live.sandboxSim" class="muted poster-sim">
      当前走廊含<b>沙盘模拟值</b>（滑杆推演、非实测），海报页脚已如实标注。
    </div>
    <div v-if="posterUrl" class="poster-preview">
      <img :src="posterUrl" alt="双语预警海报" @click="openBig" />
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
const SEV = {
  CRITICAL: { cn: '极高风险', vi: 'Rủi ro rất cao', color: '#d32f2f',
    advCn: '建议暂缓发运或改道，避开强降雨 / 大雾时段；已在途车辆就近安全点待命，调度全程值守。',
    advVi: 'Khuyến nghị hoãn hoặc đổi tuyến, tránh mưa lớn / sương mù; xe đang chạy chờ tại điểm an toàn gần nhất, điều phối giám sát 24/7.' },
  HIGH: { cn: '高风险', vi: 'Rủi ro cao', color: '#f57c00',
    advCn: '谨慎派单，预留缓冲时间并准备公路 / 水运互备方案，全程开启气象守护与动态重算。',
    advVi: 'Điều phối thận trọng, dự phòng thời gian và phương án thay thế đường bộ / đường thủy, bật giám sát thời tiết và tính lại tuyến liên tục.' },
  MEDIUM: { cn: '中等风险', vi: 'Rủi ro trung bình', color: '#f9a825',
    advCn: '关注沿线天气变化，备好备选路线；冷链 / 危化货物加强在途监控。',
    advVi: 'Theo dõi diễn biến thời tiết dọc tuyến, chuẩn bị tuyến dự phòng; tăng giám sát hàng lạnh / hàng nguy hiểm.' },
  LOW: { cn: '低风险', vi: 'Rủi ro thấp', color: '#2e7d32',
    advCn: '当前沿线气象条件良好，可按计划正常发运，系统持续后台守护。',
    advVi: 'Điều kiện thời tiết dọc tuyến đang tốt, có thể vận chuyển theo kế hoạch; hệ thống tiếp tục giám sát nền.' }
};
const HZ = {
  rain: { cn: '降雨', vi: 'Lượng mưa', unit: 'mm/h' },
  wind: { cn: '风速', vi: 'Tốc độ gió', unit: 'km/h' },
  vis: { cn: '能见度', vi: 'Tầm nhìn', unit: 'm' },
  temp: { cn: '气温', vi: 'Nhiệt độ', unit: '°C' }
};
const SRC_CN = { 'contest-observation': 'GOWFS CRA40 官方观测', 'open-meteo': 'Open-Meteo 实况', simulated: '离线模拟', none: '缓存/降级' };

// 逻辑设计尺寸（竖版海报，适合手机分享）；实际按 SCALE 倍绘制保证文字清晰
const W = 720;
const H = 1180;
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

      // 顶部风险色带
      ctx.fillStyle = sev.color;
      ctx.fillRect(0, 0, W, 178);
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 46px "Microsoft YaHei", "Segoe UI", sans-serif';
      ctx.textBaseline = 'alphabetic';
      ctx.fillText('气象风险预警', 40, 74);
      ctx.font = '26px "Segoe UI", "Arial", sans-serif';
      ctx.fillText('Cảnh báo rủi ro thời tiết', 40, 112);
      ctx.font = '22px "Microsoft YaHei", sans-serif';
      ctx.fillText('跨境公路物流 · 东盟气象路网', 40, 150);

      // 走廊线路
      let y = 226;
      ctx.fillStyle = '#37474f';
      ctx.font = 'bold 34px "Microsoft YaHei", sans-serif';
      const oVi = CITY_VI[this.originName] || '';
      const dVi = CITY_VI[this.destinationName] || '';
      ctx.fillText(`${this.originName}${oVi ? ' · ' + oVi : ''}  →  ${this.destinationName}${dVi ? ' · ' + dVi : ''}`, 40, y);

      // 最不利点
      y += 40;
      ctx.fillStyle = '#78909c';
      ctx.font = '22px "Microsoft YaHei", sans-serif';
      ctx.fillText(`沿线最不利点 Điểm bất lợi nhất: ${worst.nodeName || worst.nodeId}`, 40, y);

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
      // 风险档徽标（右侧）
      const badgeX = W - 40 - 220;
      ctx.fillStyle = sev.color;
      this.roundRect(ctx, badgeX, y + 20, 220, 92, 14);
      ctx.fill();
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 34px "Microsoft YaHei", sans-serif';
      ctx.fillText(sev.cn, badgeX + 24, y + 62);
      ctx.font = '22px "Segoe UI", sans-serif';
      ctx.fillText(sev.vi, badgeX + 24, y + 96);

      y += 190;

      // 分隔线
      ctx.strokeStyle = '#eceff1';
      ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(40, y); ctx.lineTo(W - 40, y); ctx.stroke();

      // 四灾种贡献（2×2）
      y += 34;
      const cells = worst.contributions || [];
      const colW = (W - 80) / 2;
      cells.forEach((c, i) => {
        const cx = 40 + (i % 2) * colW;
        const cy = y + Math.floor(i / 2) * 96;
        const hz = HZ[c.id] || { cn: c.label, vi: '', unit: c.unit };
        ctx.fillStyle = '#37474f';
        ctx.font = 'bold 26px "Microsoft YaHei", sans-serif';
        ctx.fillText(`${hz.cn}`, cx, cy + 20);
        ctx.fillStyle = '#90a4ae';
        ctx.font = '18px "Segoe UI", sans-serif';
        ctx.fillText(hz.vi, cx, cy + 44);
        ctx.fillStyle = sevColorFor(c.membership);
        ctx.font = 'bold 30px "Segoe UI", "Microsoft YaHei", sans-serif';
        const valTxt = c.value == null ? '缺测' : `${c.value} ${c.unit}`;
        ctx.fillText(valTxt, cx + 150, cy + 30);
        // 贡献条
        const barX = cx, barY = cy + 58, barW = colW - 30;
        ctx.fillStyle = '#eceff1';
        this.roundRect(ctx, barX, barY, barW, 12, 6); ctx.fill();
        ctx.fillStyle = sevColorFor(c.membership);
        this.roundRect(ctx, barX, barY, Math.max(6, barW * Math.min(1, c.membership)), 12, 6); ctx.fill();
      });

      y += 2 * 96 + 10;

      // 建议（双语）
      ctx.strokeStyle = '#eceff1';
      ctx.beginPath(); ctx.moveTo(40, y); ctx.lineTo(W - 40, y); ctx.stroke();
      y += 34;
      ctx.fillStyle = sev.color;
      ctx.font = 'bold 26px "Microsoft YaHei", sans-serif';
      ctx.fillText('调度建议  Khuyến nghị điều phối', 40, y);
      y += 34;
      ctx.fillStyle = '#37474f';
      ctx.font = '23px "Microsoft YaHei", sans-serif';
      y = this.wrap(ctx, sev.advCn, 40, y, W - 80, 34);
      y += 6;
      ctx.fillStyle = '#78909c';
      ctx.font = 'italic 20px "Segoe UI", sans-serif';
      y = this.wrap(ctx, sev.advVi, 40, y, W - 80, 28);

      // 页脚
      const src = SRC_CN[this.live.servedSource] || this.live.servedSourceLabel || this.live.servedSource || '—';
      ctx.fillStyle = '#b0bec5';
      ctx.font = '18px "Microsoft YaHei", sans-serif';
      const now = new Date().toLocaleString('zh-CN', { hour12: false });
      let fy = H - 66;
      if (this.live.sandboxSim) {
        // 如实标注：滑杆推演值不是实测，海报流转到人手里也不能假装是官方数据
        ctx.fillStyle = '#b71c1c';
        ctx.font = 'bold 18px "Microsoft YaHei", sans-serif';
        ctx.fillText('＊含沙盘模拟值（非实测）/ Giá trị mô phỏng (không phải thực đo)', 40, fy);
        fy += 26;
        ctx.fillStyle = '#b0bec5';
        ctx.font = '18px "Microsoft YaHei", sans-serif';
      }
      ctx.fillText(`数据来源 Nguồn: ${src} · 模型 Mô hình: AMRI`, 40, fy);
      ctx.fillText(`生成 Thời gian: ${now} · 东盟气象路网风险指数 (ASEAN Meteorological Route Index)`, 40, fy + 26);

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
