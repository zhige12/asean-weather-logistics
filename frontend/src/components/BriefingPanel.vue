<template>
  <div class="briefing-panel">
    <div class="briefing-header">
      <button class="btn primary" @click="generateBriefing" :disabled="generating">
        {{ generating ? '生成中…' : '📋 一键生成任务简报' }}
      </button>
      <button v-if="briefingText" class="btn outline" @click="copyBriefing">📋 复制</button>
      <button v-if="briefingText" class="btn outline" @click="downloadBriefing">⬇ 下载</button>
    </div>

    <div v-if="errorMsg" class="briefing-error">{{ errorMsg }}</div>

    <div v-if="briefingText" class="briefing-content">
      <div class="briefing-meta">
        <span>生成时间：{{ briefingTime }}</span>
        <span>简报编号：{{ briefingId }}</span>
      </div>
      <pre class="briefing-body" ref="briefingBody">{{ briefingText }}</pre>
    </div>
  </div>
</template>

<script>
import axios from 'axios';

export default {
  name: 'BriefingPanel',
  props: {
    originId: { type: String, default: 'NN' },
    destinationId: { type: String, default: 'HN' },
    period: { type: String, default: 'normal' },
    cargoType: { type: String, default: 'general' }
  },
  data() {
    return { briefingText: '', generating: false, briefingId: '', briefingTime: '', errorMsg: '' };
  },
  methods: {
    async generateBriefing() {
      this.generating = true;
      this.errorMsg = '';
      this.briefingText = '';
      try {
        const r = await axios.get('/api/briefing/generate', {
          params: {
            originId: this.originId,
            destinationId: this.destinationId,
            period: this.period,
            cargoType: this.cargoType
          },
          timeout: 30000
        });
        this.briefingText = r.data.briefing || '';
        this.briefingTime = new Date().toLocaleString('zh-CN', { hour12: false });
        this.briefingId = 'BN-' + Date.now().toString(36).toUpperCase();
      } catch (e) {
        this.errorMsg = '简报生成失败: ' + (e.response?.data?.message || e.message || '请检查后端服务');
      } finally {
        this.generating = false;
      }
    },
    copyBriefing() {
      const text = this.briefingText;
      if (!text) return;
      navigator.clipboard.writeText(text).then(() => {
        alert('简报已复制到剪贴板');
      }).catch(() => {
        alert('复制失败，请手动选择文本');
      });
    },
    downloadBriefing() {
      const text = this.briefingText;
      if (!text) return;
      const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `任务简报_${this.briefingId}.txt`;
      a.click();
      URL.revokeObjectURL(url);
    }
  }
}
</script>

<style scoped>
.briefing-panel { margin-top: 4px; }
.briefing-header { display: flex; gap: 8px; flex-wrap: wrap; }
.btn { padding: 8px 12px; border-radius: 6px; border: none; cursor: pointer; font-size: 13px; }
.btn.primary { background: #0b5394; color: #fff; }
.btn.outline { background: transparent; color: #1976d2; border: 1px solid #1976d2; }
.btn:disabled { opacity: 0.6; cursor: not-allowed; }
.briefing-error { color: #c62828; font-size: 12px; margin-top: 6px; }
.briefing-content { margin-top: 12px; background: #fafbfc; border: 1px solid #e3e8ee; border-radius: 6px; padding: 12px; }
.briefing-meta { display: flex; justify-content: space-between; font-size: 11px; color: #999; margin-bottom: 10px; }
.briefing-body { white-space: pre-wrap; font-size: 13px; line-height: 1.6; font-family: 'Courier New', monospace; color: #333; background: #fff; padding: 10px; border-radius: 4px; border: 1px solid #eef2f6; max-height: 400px; overflow: auto; }
</style>