import { createApp } from 'vue'
import HomeApp from './HomeApp.vue'

const app = createApp(HomeApp)

// 门户是纯只读信息页，出错不该白屏：兜底打印，并保证首屏遮罩一定被移除
app.config.errorHandler = (err, instance, info) => {
  console.error('[portal]', err, info)
}

app.mount('#app')

// 移除首屏遮罩（#boot 在 #app 之外，不会被 Vue 替换，需手动摘）
const veil = document.getElementById('boot')
if (veil) veil.parentNode && veil.parentNode.removeChild(veil)
