/**
 * 司机端整机界面中越切换（国际化）。
 * 设计取舍：不做全量模板重构（8000 行文件改动风险高），而用「中文原文 → 越南语」字典 +
 * t() 包裹函数渐进覆盖。字典未命中的字符串原样回落中文，界面永不出现空白或 key 泄漏；
 * 后续每补一个词条就多覆盖一处，可增量完善。
 * 专名兜底：动态拼接串（如城市名、口岸名）即便整体不在字典里，也先按术语表逐词替换，
 * 让越南语司机至少能认出南宁/河内/友谊关这些地名。
 */

// 界面 chrome 精确字典：key 为界面上的中文原文
export const VI_UI = {
  // ===== 顶部控件 / 悬浮按钮 =====
  '放大': 'Phóng to',
  '缩小': 'Thu nhỏ',
  '回到网络中心': 'Về trung tâm',
  '切换底图': 'Đổi nền bản đồ',
  '卫星影像': 'Ảnh vệ tinh',
  '矢量地图': 'Bản đồ vector',
  '标准地图': 'Bản đồ chuẩn',
  '离线地图': 'Bản đồ ngoại tuyến',
  '切换到移动版（手机小屏样式）': 'Chuyển sang bản di động',
  '切换到PC版（整窗大屏样式）': 'Chuyển sang bản PC',
  '退出全屏': 'Thoát toàn màn hình',
  '全屏显示': 'Toàn màn hình',

  // ===== 模式 / 状态浮标 =====
  '物流任务模式': 'Chế độ nhiệm vụ vận tải',
  '普通导航模式': 'Chế độ điều hướng phổ thông',
  '预览订单': 'Xem trước đơn',
  '已接单': 'Đã nhận đơn',
  '退出': 'Thoát',
  '免费导航 · 天气与口岸提醒': 'Điều hướng miễn phí · Nhắc thời tiết và cửa khẩu',

  // ===== 搜索 / 路线候选 =====
  '查找地点、规划路线': 'Tìm địa điểm, lập kế hoạch tuyến',
  '从哪里出发？如 南宁': 'Bắt đầu từ đâu? VD: Nam Ninh',
  '要去哪里？如 河内': 'Đi đến đâu? VD: Hà Nội',
  '交换起终点': 'Đổi điểm đầu/cuối',
  '常用路线': 'Tuyến thường dùng',
  '可选路线': 'Tuyến khả dụng',
  '点选一条进入地图预览': 'Chọn một tuyến để xem trước',
  '风险': 'rủi ro',
  '无风险': 'Không rủi ro',
  '处风险': 'điểm rủi ro',
  '灾害概率': 'Xác suất thiên tai',
  '低于阈值视为通畅': 'Dưới ngưỡng, coi là thông suốt',
  '可坐船': 'Có thể đi tàu',
  '已禁航': 'Đã cấm luồng',
  '不受公路熔断影响': 'Không bị ảnh hưởng đứt đường bộ',
  '平陆运河触发通航安全红线': 'Kênh Bình Lục chạm ngưỡng an toàn hàng hải',
  '收起': 'Thu gọn',
  '功能': 'Chức năng',
  '路线计算中…': 'Đang tính tuyến…',
  '搜索路线中…': 'Đang tìm tuyến…',
  '查看路线预览': 'Xem trước tuyến',
  '搜索路线并进入地图预览': 'Tìm tuyến và xem trước bản đồ',
  '导航中 · Agent 实时守护': 'Đang dẫn đường · Agent giám sát thời gian thực',
  '导航': 'Dẫn đường',
  '去': 'Đi tới',
  '公里': 'km',
  '分钟': 'phút',

  // ===== 底部 Tab / 抽屉标题 =====
  '首页': 'Trang chủ',
  '任务': 'Nhiệm vụ',
  '预警': 'Cảnh báo',
  '知识库': 'Kiến thức',
  '我的': 'Cá nhân',
  '运单': 'Đơn hàng',
  '物流任务': 'Nhiệm vụ vận tải',
  '风险预警': 'Cảnh báo rủi ro',
  'AI方案': 'Phương án AI',
  '我的车辆': 'Xe của tôi',

  // ===== 状态条 =====
  '全线通畅': 'Toàn tuyến thông suốt',
  '风险预警 · 谨慎驾驶': 'Cảnh báo rủi ro · Lái thận trọng',
  '已熔断 · 自动绕行': 'Đã đứt · Tự động đi vòng',
  '原地等待 · 车辆停驶': 'Dừng chờ tại chỗ · Xe tắt máy',
  '待选路线': 'Chưa chọn tuyến',
  'GPS 搜索中…': 'Đang tìm GPS…',
  '搜索中…': 'Đang tìm…',
  '已定位 ✓': 'Đã định vị ✓',
  '不可用 ✗': 'Không khả dụng ✗',
  '模拟行驶 △': 'Mô phỏng行驶 △',
  '停驶等待 ▮': 'Dừng chờ ▮',

  // ===== 地图 / 预览 / 导航 HUD =====
  '地图加载中…': 'Đang tải bản đồ…',
  '安全路段': 'Đoạn an toàn',
  '风险路段': 'Đoạn rủi ro',
  '口岸': 'Cửa khẩu',
  '退出导航': 'Thoát dẫn đường',
  '返回首页': 'Về trang chủ',
  '路线预览': 'Xem trước tuyến',
  '共': 'Tổng',
  '条可选路线': 'tuyến khả dụng',
  '预计到达': 'Dự kiến đến',
  '展开路线列表': 'Mở danh sách tuyến',
  '收起路线列表': 'Thu danh sách tuyến',
  '展开 ▴': 'Mở ▴',
  '收起 ▾': 'Thu ▾',
  'AI 最安全': 'AI an toàn nhất',
  '已选': 'Đã chọn',
  '不建议通行': 'Không khuyến nghị lưu thông',
  '暂无预测': 'Chưa có dự báo',
  '展开详情 ▾': 'Xem chi tiết ▾',
  '展开详情': 'Xem chi tiết',
  '全览路线': 'Xem toàn tuyến',
  '开始导航': 'Bắt đầu dẫn đường',
  '导航中': 'Đang dẫn đường',
  '延误': 'Trễ',
  '陆水联运 · 平陆运河': 'Vận tải liên hợp bộ-thủy · Kênh Bình Lục',
  '原地等待中 · 车辆停驶': 'Đang dừng chờ · Xe tắt máy',
  '已按调度指令停在当前位置，车标不再前进；冷链机组保持运行，待风险解除后恢复行驶。':
    'Đã dừng tại chỗ theo lệnh điều phối; xe không tiến, tổ lạnh vẫn chạy, sẽ tiếp tục sau khi gỡ rủi ro.',
  '恢复行驶': 'Tiếp tục chạy',
  '我的位置': 'Vị trí của tôi',
  '气象': 'Thời tiết',

  // ===== 任务抽屉 =====
  '任务变更通知': 'Thông báo thay đổi nhiệm vụ',
  '已确认': 'Đã xác nhận',
  '待确认': 'Chờ xác nhận',
  '您的权益保障': 'Quyền lợi được bảo đảm',
  '✓ 随船期间按出勤计算工时，额外发放随船补贴': '✓ Thời gian theo tàu tính công, kèm phụ cấp theo tàu',
  '✓ 车辆滚装段已购买专项运输险': '✓ Đoạn xe滚装 đã mua bảo hiểm vận chuyển chuyên biệt',
  '✓ 船上安排司机休息舱位，含餐饮': '✓ Trên tàu bố trí chỗ nghỉ cho tài xế, gồm ăn uống',
  '✓ 抵达海防港后公司安排返程交通': '✓ Đến cảng Hải Phòng, công ty thu xếp phương tiện về',
  '✓ 本次变更属不可抗力调度调整，不视为司机违约': '✓ Thay đổi này do bất khả kháng, không tính tài xế vi phạm',
  '确认中…': 'Đang xác nhận…',
  '确认接收': 'Xác nhận tiếp nhận',
  '已确认接收 · 新路线已同步': 'Đã xác nhận · Tuyến mới đã đồng bộ',
  '如有异议，请联系调度中心。切换运输方式是调度端的决策权限。':
    'Nếu có thắc mắc, liên hệ trung tâm điều phối. Đổi phương thức vận tải là quyền của điều phối.',
  '暂无任务变更。调度中心确认方案后，任务变更指令将实时推送到这里。':
    'Chưa có thay đổi nhiệm vụ. Sau khi điều phối xác nhận, lệnh sẽ được đẩy tới đây.',
  'AI 方案建议': 'Phương án đề xuất bởi AI',
  '查看方案': 'Xem phương án',
  '分析中…': 'Đang phân tích…',
  '确认接单后，这里可查看调度端 AI 的路线决策依据。切换运输方式是调度端的决策权限。':
    'Sau khi nhận đơn, xem tại đây căn cứ quyết định tuyến của AI điều phối.',
  '点击查看调度端 AI 生成的三方案对比': 'Nhấn để xem so sánh ba phương án của AI điều phối',
  '时效': 'Thời gian',
  '费用': 'Chi phí',
  '货损': 'Hư hỏng hàng',
  '不可用': 'Không khả dụng',
  '触达状态': 'Trạng thái tiếp nhận',
  '人已确认，': 'đã xác nhận, ',
  '人待确认': 'chờ xác nhận',
  '人已升级语音外呼': 'đã nâng lên gọi thoại',
  'AI 双语预警': 'Cảnh báo song ngữ AI',
  '生成中…': 'Đang tạo…',
  '生成预警': 'Tạo cảnh báo',
  '点击生成 AI 中越双语预警': 'Nhấn để tạo cảnh báo song ngữ Trung-Việt',
  '当前风险清单': 'Danh sách rủi ro hiện tại',
  '当前无生效风险': 'Hiện không có rủi ro hiệu lực',
  '行业知识速查': 'Tra cứu nhanh kiến thức ngành',
  '点上方标签检索应对建议': 'Nhấn nhãn phía trên để tìm khuyến nghị',

  // ===== 我的 =====
  '未设置姓名': 'Chưa đặt tên',
  '未登记车牌': 'Chưa đăng ký biển số',
  '任务模式': 'Chế độ nhiệm vụ',
  '物流任务模式：车牌与货物信息以公司派单为准，不可修改':
    'Chế độ vận tải: biển số và hàng theo lệnh công ty, không sửa được',
  '姓名': 'Họ tên',
  '请输入姓名': 'Nhập họ tên',
  '车牌': 'Biển số',
  '如 桂A·D12345': 'VD: 桂A·D12345',
  '车型': 'Loại xe',
  '货物': 'Hàng',
  '请输入货物名称': 'Nhập tên hàng',
  '重量(t)': 'Trọng lượng (t)',
  '温控': 'Nhiệt độ',
  '如 冷鲜 2~6℃': 'VD: lạnh 2~6℃',
  '语音播报': 'Đọc giọng',
  '保存信息': 'Lưu thông tin',
  '冷藏半挂': 'Rơ-moóc lạnh',
  '普货半挂': 'Rơ-moóc hàng thường',
  '危化罐车': 'Xe xi-tăng hóa chất',
  '大件平板': 'Rơ-moóc phẳng hàng大件',
  '厢式中卡': 'Xe thùng cỡ trung',

  // ===== 订单详情弹层 =====
  '订单信息': 'Thông tin đơn hàng',
  '✅ 已接单': '✅ Đã nhận đơn',
  '❌ 已拒单': '❌ Đã từ chối',
  '⏳ 待接单': '⏳ Chờ nhận đơn',
  '吨': 'tấn',
  '起运': 'Điểm gửi',
  '目的': 'Đích',
  '承运司机': 'Tài xế',
  '送达时限': 'Giới hạn giao',
  '调度下发路线': 'Tuyến điều phối gửi',
  '路线摘要': 'Tóm tắt tuyến',
  'AI 灾害概率': 'Xác suất thiên tai AI',
  '🌦 出车前 AI 气象分析': '🌦 AI phân tích thời tiết trước chuyến',
  '出车前 AI 气象分析': 'AI phân tích thời tiết trước chuyến',
  '处理中…': 'Đang xử lý…',
  '❌ 拒绝此单': '❌ Từ chối đơn',
  '接单中…': 'Đang nhận đơn…',
  '✅ 确认接单': '✅ Xác nhận đơn',
  '已接单，正在按调度路线导航。': 'Đã nhận đơn, đang dẫn đường theo tuyến điều phối.',
  '已拒单，已退回普通导航模式。': 'Đã từ chối, trở về chế độ điều hướng phổ thông.',
  '跨境': 'xuyên biên giới',

  '待接单': 'Chờ nhận đơn',
  '已拒单': 'Đã từ chối đơn',
  '底图1': 'Bản đồ 1',
  '底图2': 'Bản đồ 2',
  '底图3': 'Bản đồ 3',
  '底图4': 'Bản đồ 4',

  // ===== 通用按钮 =====
  '播报': 'Đọc',
}

// 专名/术语兜底：动态拼接串里出现的城市、口岸等，逐词替换（长词优先，避免子串误替）
const VI_TERMS = [
  ['友谊关', 'Hữu Nghị Quan'],
  ['平陆运河', 'kênh đào Bình Lục'],
  ['钦州港', 'cảng Khâm Châu'],
  ['海防港', 'cảng Hải Phòng'],
  ['南宁港', 'cảng Nam Ninh'],
  ['六景', 'Lục Cảnh'],
  ['芒街', 'Móng Cái'],
  ['同登', 'Đồng Đăng'],
  ['河口', 'Hà Khẩu'],
  ['凭祥', 'Bằng Tường'],
  ['昆明', 'Côn Minh'],
  ['海防', 'Hải Phòng'],
  ['河内', 'Hà Nội'],
  ['南宁', 'Nam Ninh'],
  ['东兴', 'Hưng Đông'],
  ['老街', 'Lào Cai'],
  ['谅山', 'Lạng Sơn'],
  ['广西', 'Quảng Tây'],
  ['云南', 'Vân Nam'],
  ['中国', 'Trung Quốc'],
  ['越南', 'Việt Nam'],
  ['熔断', 'đứt luồng'],
  ['禁航', 'cấm luồng'],
  ['绕行', 'đi vòng'],
  ['口岸', 'cửa khẩu'],
  ['风险', 'rủi ro'],
  ['暴雨', 'mưa lớn'],
  ['台风', 'bão'],
  ['大雾', 'sương mù dày'],
  ['公路', 'đường bộ'],
  ['海运', 'đường biển'],
  ['随船', 'theo tàu'],
  ['跨境', 'xuyên biên giới'],
  ['推荐', 'khuyến nghị'],
  ['超阈值', 'vượt ngưỡng'],
  ['已生效', 'đang hiệu lực'],
]
// 长词优先，防止「南宁港」被「南宁」先截断；只做多字术语替换，避免单字误伤
VI_TERMS.sort((a, b) => b[0].length - a[0].length)

/**
 * 中文 → 越南语。先查整串精确字典；未命中则逐术语兜底替换。
 * 两者都没命中时原样返回（回落中文），绝不返回空或 key。
 */
export function translateToVi(zh) {
  if (zh == null) return zh
  const exact = VI_UI[zh]
  if (exact) return exact
  let out = zh
  for (const [cn, vi] of VI_TERMS) {
    if (out.includes(cn)) out = out.split(cn).join(vi)
  }
  return out
}

/** 当前语言下是否要做越南语替换 */
export function shouldTranslate(lang) {
  return lang === 'vi'
}

/**
 * DOM 层整机翻译器：遍历文本节点与 input 的 placeholder / 按钮 title，
 * 用字典把中文换成越南语；切回中文时逐节点还原原文（节点上挂 __zh 备份）。
 * 之所以在 DOM 层做而不是重构 8000 行模板：
 * - 覆盖全量界面（含弹层/抽屉/详情），不用逐处包 t()；
 * - Vue 按 vnode 原值（中文）重渲染文本，MutationObserver 增量重译保证翻译跟手；
 * - 未命中字典的节点保持中文，天然优雅回落。
 * 代价是非惯用 Vue 方案，但对「演示可整机切换语言」这一目标是改动面最小、回归风险最低的做法。
 */
const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'TITLE', 'TEXTAREA'])

export function createUiTranslator(rootEl) {
  let lang = 'zh'
  let observer = null
  let applying = false
  let timer = null

  function nodeTextTranslate(raw) {
    const key = raw.trim()
    if (!key) return null
    // 先按整串精确匹配（兼顾「AI方案」这类拉丁开头的词条，不能被前缀剥掉）
    if (VI_UI[key]) {
      const out = VI_UI[key]
      return /[\u00C0-\u024Fa-zA-Z]$/.test(out) ? out + ' ' : out
    }
    // 剥离前导 emoji/符号（如「📋 预览订单」「✅ 已接单」），用剩余中文核心查字典
    const m = key.match(/^[^\u4E00-\u9FFF]*[\s\S]*/)
    const core = m[0].replace(/^[^\u4E00-\u9FFF]*/, '')
    const head = key.slice(0, key.length - core.length)
    if (!core) return null
    let out = null
    if (VI_UI[core]) out = head + VI_UI[core]
    else {
      const w = translateToVi(core)
      if (w !== core) out = head + w
    }
    if (!out || out === key) return null
    // 紧邻下一文本节点是字母/词（如「去」+城市名）时补空格，避免粘字
    return /[\u00C0-\u024Fa-zA-Z]$/.test(out) ? out + ' ' : out
  }

  function walkAndApply(root) {
    applying = true
    try {
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT)
      let n
      while ((n = walker.nextNode())) {
        if (n.nodeType === Node.TEXT_NODE) {
          const parent = n.parentElement
          if (!parent || SKIP_TAGS.has(parent.tagName)) continue
          if (lang === 'vi') {
            const t = nodeTextTranslate(n.data)
            if (t) {
              if (n.__zh === undefined) n.__zh = n.data
              n.data = t
            }
          } else if (n.__zh !== undefined) {
            n.data = n.__zh
            delete n.__zh
          }
        } else {
          // 属性翻译：placeholder / title 是司机端提示语的主要载体
          for (const attr of ['placeholder', 'title']) {
            const v = n.getAttribute && n.getAttribute(attr)
            if (!v) continue
            if (lang === 'vi') {
              const tv = translateToVi(v.trim())
              if (tv !== v.trim()) {
                if (n['__zh_' + attr] === undefined) n['__zh_' + attr] = v
                n.setAttribute(attr, tv)
              }
            } else if (n['__zh_' + attr] !== undefined) {
              n.setAttribute(attr, n['__zh_' + attr])
              delete n['__zh_' + attr]
            }
          }
        }
      }
    } finally {
      applying = false
    }
  }

  function scheduleReapply() {
    if (lang !== 'vi' || applying) return
    // rAF 级节流：导航中每帧都有节点变动，300ms 合并一次遍历足够跟手
    if (timer) return
    timer = setTimeout(() => { timer = null; walkAndApply(root()) }, 300)
  }

  function root() {
    const r = typeof rootEl === 'function' ? rootEl() : rootEl
    return (r && r.nodeType === 1) ? r : document.body
  }

  return {
    setLang(next) {
      lang = next
      walkAndApply(root())
      if (next === 'vi' && !observer) {
        observer = new MutationObserver(scheduleReapply)
        observer.observe(root(), { subtree: true, childList: true, characterData: true })
      }
      if (next !== 'vi' && observer) {
        observer.disconnect()
        observer = null
      }
    },
    destroy() {
      if (observer) observer.disconnect()
      observer = null
      if (timer) clearTimeout(timer)
    },
  }
}
