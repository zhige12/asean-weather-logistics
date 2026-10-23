import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// Capacitor 内建 web 服务器不返回 CORS 头，但 Vite 生产构建会给所有
// <script type="module"> 和 <link> 加 crossorigin 属性，导致 WebView
// 加载失败页面一片空白。这个插件在 HTML 输出阶段移除 crossorigin。
function removeCrossorigin() {
  return {
    name: 'remove-crossorigin',
    transformIndexHtml(html) {
      return html.replace(/\s+crossorigin(?=[\s>])/g, '')
    }
  }
}

export default defineConfig({
  plugins: [vue(), removeCrossorigin()],
  // 每次构建烧入时间戳：司机端导航卡右下角显版本水印，
  // 用户报缺陷时一眼判断浏览器里是新包还是旧缓存（起点跳变排查被旧包坑过多次）
  define: {
    __BUILD_TAG__: JSON.stringify(new Date().toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }))
  },
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        driver: resolve(__dirname, 'driver.html')
      },
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/leaflet')) return 'leaflet'
        }
      }
    }
  },
  server: {
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false
      },
      // D 盘离线瓦片也要代理到后端，否则 /tiles/... 会被 Vite 当 SPA 路由返回 HTML，
      // 瓦片层收到 HTML 内容全部解析失败，离线底图一片空白
      '/tiles': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false
      }
    }
  }
})