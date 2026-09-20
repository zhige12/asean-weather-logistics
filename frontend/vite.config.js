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
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        driver: resolve(__dirname, 'driver.html')
      },
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/leaflet')) return 'leaflet'
          if (id.includes('node_modules/maplibre-gl') || id.includes('@maplibre')) return 'maplibre'
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
      // 本地矢量底图瓦片也要代理到后端，否则 /tiles/... 会被 Vite 当 SPA 路由返回 HTML，
      // MapLibre 解析失败导致底图道路全部不渲染（只剩中文地名等 Leaflet DOM 层）
      '/tiles': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false
      }
    }
  }
})