const fs = require('fs')
const dir = 'D:/idea project/asean-weather-logistics/frontend/dist/assets'
const drv = fs.readdirSync(dir).find(f => /^driver-.*\.js$/.test(f))
const c = fs.readFileSync(dir + '/' + drv, 'utf8')
console.log('driver bundle =', drv)
console.log('  routeChoice 出现次数 =', c.split('routeChoice').length - 1, '(旧=1, 新=2)')
console.log('  文件 mtime =', fs.statSync(dir + '/' + drv).mtime.toISOString())
// 源码 mtime 对比
const src = 'D:/idea project/asean-weather-logistics/frontend/src/driver/DriverApp.vue'
console.log('  DriverApp.vue mtime =', fs.statSync(src).mtime.toISOString())
