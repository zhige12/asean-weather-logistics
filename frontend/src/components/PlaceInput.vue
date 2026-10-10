<template>
  <div class="pi-wrap">
    <label class="pi-label">{{ label }}</label>
    <input
      class="pi-input"
      v-model="text"
      :placeholder="placeholder"
      autocomplete="off"
      @input="onType"
      @focus="onFocus"
      @blur="onBlur"
      @keydown.down.prevent="move(1)"
      @keydown.up.prevent="move(-1)"
      @keydown.esc="open = false"
      @keyup.enter="onEnter"
    />
    <div v-if="open" class="pi-pop">
      <div v-if="loading" class="pi-item pi-muted">搜索中…</div>
      <div
        v-for="(it, i) in items"
        :key="i"
        class="pi-item"
        :class="{ on: i === active }"
        @mousedown.prevent="pick(it)"
        @mouseenter="active = i"
      >
        <span class="pi-name">{{ it.name }}</span>
        <span class="pi-tag">{{ it.tag || (it.source === 'local' ? '地名库' : '在线') }}</span>
      </div>
      <div v-if="!loading && !items.length" class="pi-item pi-muted">
        无匹配候选，可直接按回车在线解析「{{ text }}」
      </div>
    </div>
    <div v-if="errMsg" class="pi-err">{{ errMsg }}</div>
  </div>
</template>

<script>
import axios from 'axios'
import { geocodeName, snapCoords, apiErrorMessage } from '../utils/geocoder.js'

/**
 * 导航软件式起终点输入框：自由输入 + 关键字联想下拉 + 选中即吸附路网节点。
 * - 候选来自 GET /api/geocode/suggest（本地地名库前缀/包含命中优先，不足补天地图 POI）；
 * - 点选/回车选中后调 /api/geocode/snap 吸附成 nodeId，v-model 输出节点 id、name 输出显示名；
 * - ensure() 供宿主在「确认规划」时兜底：用户只打字没选候选时，在线解析当前文本。
 */
export default {
  name: 'PlaceInput',
  props: {
    label: { type: String, default: '' },
    placeholder: { type: String, default: '' },
    modelValue: { type: String, default: '' },
    name: { type: String, default: '' }
  },
  emits: ['update:modelValue', 'update:name'],
  data() {
    return {
      text: this.name || '',
      items: [],
      open: false,
      loading: false,
      active: 0,
      errMsg: '',
      picked: null,
      _timer: null
    }
  },
  watch: {
    // 宿主程序性改显示名（地图选点/规划结果回填）时同步输入框
    name(v) {
      this.text = v || ''
      this.picked = v ? { name: v, nodeId: this.modelValue } : null
    }
  },
  methods: {
    onType() {
      this.errMsg = ''
      this.picked = null
      clearTimeout(this._timer)
      this._timer = setTimeout(() => this.fetchSuggest(), 220)
    },
    onFocus() {
      if (this.text.trim()) {
        this.open = true
        if (!this.items.length) this.fetchSuggest()
      }
    },
    onBlur() {
      // 候选项 mousedown.prevent 先于 blur 触发，这里延迟关闭只为点击外部时收起
      setTimeout(() => { this.open = false }, 120)
    },
    move(d) {
      if (!this.open || !this.items.length) return
      this.active = (this.active + d + this.items.length) % this.items.length
    },
    onEnter() {
      if (this.open && this.items.length) {
        this.pick(this.items[Math.max(0, this.active)])
        return
      }
      // 没选候选直接回车：立即在线解析当前文本
      this.ensure().then(r => { if (!r.ok) this.errMsg = r.msg })
    },
    async fetchSuggest() {
      const q = this.text.trim()
      if (!q) { this.items = []; this.open = false; return }
      this.loading = true
      try {
        const r = await axios.get('/api/geocode/suggest', { params: { q }, timeout: 8000 })
        // 请求回来时用户又改了字：丢弃过期结果
        if (q !== this.text.trim()) return
        this.items = (r.data && r.data.suggestions) || []
        this.active = 0
        this.open = true
      } catch (e) {
        this.items = []
        this.open = !!this.text.trim()
      } finally {
        this.loading = false
      }
    },
    async pick(it) {
      this.errMsg = ''
      this.loading = true
      try {
        const s = await snapCoords(it.lat, it.lng, it.name)
        this.picked = { name: it.name, nodeId: s.nodeId }
        this.$emit('update:modelValue', s.nodeId)
        this.$emit('update:name', it.name)
        this.text = it.name
        this.open = false
      } catch (e) {
        this.errMsg = apiErrorMessage(e, '该地点吸附路网失败')
      } finally {
        this.loading = false
      }
    },
    /** 宿主「确认规划」前调用：把当前文本解析成 {ok, nodeId, name} 或 {ok:false, msg} */
    async ensure() {
      const q = this.text.trim()
      if (!q) return { ok: false, msg: `请输入${this.label}` }
      if (this.picked && this.picked.name === q) {
        return { ok: true, nodeId: this.picked.nodeId, name: q }
      }
      try {
        const g = await geocodeName(q)
        const s = await snapCoords(g.lat, g.lng, g.name || q)
        this.picked = { name: q, nodeId: s.nodeId }
        this.$emit('update:modelValue', s.nodeId)
        this.$emit('update:name', q)
        return { ok: true, nodeId: s.nodeId, name: q }
      } catch (e) {
        return { ok: false, msg: `${this.label}「${q}」无法解析：${apiErrorMessage(e, '请换个说法或从候选中选择')}` }
      }
    }
  }
}
</script>

<style scoped>
.pi-wrap { position: relative; display: block; }
.pi-label { display: block; font-size: 12px; color: #6b7280; margin-bottom: 4px; }
.pi-input {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid rgba(120, 140, 180, 0.4);
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  outline: none;
}
.pi-input:focus { border-color: #2563eb; box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12); }
.pi-pop {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: 60;
  max-height: 240px;
  overflow-y: auto;
  background: #fff;
  border: 1px solid rgba(120, 140, 180, 0.35);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18);
}
.pi-item {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  font-size: 12.5px;
  cursor: pointer;
}
.pi-item.on { background: rgba(37, 99, 235, 0.08); }
.pi-name { color: #111827; }
.pi-tag { color: #9aa5b8; font-size: 11px; align-self: center; white-space: nowrap;
  max-width: 52%; overflow: hidden; text-overflow: ellipsis; }
.pi-muted { color: #9aa5b8; cursor: default; }
.pi-err { margin-top: 4px; font-size: 11px; color: #b91c1c; }
</style>
