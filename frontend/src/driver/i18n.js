/**
 * 司机端整机界面多语言切换（国际化）：中文 ↔ 越南语 ↔ 英语。
 * 设计取舍：不做全量模板重构（8000 行文件改动风险高），而用「中文原文 → 目标语」字典 +
 * t() 包裹函数渐进覆盖。字典未命中的字符串原样回落中文，界面永不出现空白或 key 泄漏；
 * 后续每补一个词条就多覆盖一处，可增量完善。
 * 专名兜底：动态拼接串（如城市名、口岸名）即便整体不在字典里，也先按术语表逐词替换，
 * 让外语司机至少能认出南宁/河内/友谊关这些地名。
 * 多字典：vi / en 各一份精确字典 + 术语表，按当前语言选一份用（见 UI_DICTS / UI_TERMS）。
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

  // ===== 门户（HomeApp）顶栏与通用标签 =====
  '主页': 'Trang chính',
  '调度大屏': 'Bảng điều phối',
  '司机端': 'Tài xế',
  '实时气象': 'Thời tiết trực tiếp',
  'AMRI 风险': 'Rủi ro AMRI',
  '水运': 'Vận tải thủy',
  '通关时效': 'Thời gian thông quan',
  '登录 / 注册': 'Đăng nhập / Đăng ký',
  '公告': 'Thông báo',
  '数据源': 'Nguồn dữ liệu',
  '连接中…': 'Đang kết nối…',
  '载入中…': 'Đang tải…',
  '重试': 'Thử lại',
  '跨境物流气象预警': 'Cảnh báo thời tiết logistics xuyên biên giới',
  // 门户「东盟态势」视图（标题/徽标/单位词；动态拼接靠术语逐词补）
  '东盟态势': 'Tình hình ASEAN',
  '区域气象态势': 'Tình hình thời tiết khu vực',
  '热点城市风险分布': 'Phân bố rủi ro tại các đô thị trọng điểm',
  '按 AMRI 指数降序 · 高风险置顶': 'Sắp xếp theo chỉ số AMRI giảm dần · rủi ro cao nhất ở đầu',
  '刷新区域数据': 'Làm mới dữ liệu khu vực',
  '刷新中…': 'Đang làm mới…',
  '走廊': 'Hành lang',
  '本次真实回源': 'Lấy mới từ nguồn',
  '节流窗口内缓存': 'Dùng cache trong cửa sổ chặn nhịp',
  '本区域当前最不利城市': 'Đô thị bất lợi nhất khu vực lúc này',
  '主灾种': 'Loại rủi ro chính',
  '覆盖': 'Phủ',
  '国': 'quốc gia',
  '城': 'đô thị',
  '气温': 'Nhiệt độ',
  '降水': 'Mưa',
  '风': 'Gió',
  '能见度': 'Tầm nhìn',
  // AMRI 灾种贡献的 label 枚举（后端动态下发，不是界面静态文案）：
  // 漏一个就会在满屏拉丁文里夹出中文，逐词术语表也接不住「风速」这种组合词
  '降雨': 'Lượng mưa',
  '风速': 'Tốc độ gió',
  '正常': 'Bình thường',
  // 取数审计行：标签与数字同处在一个文本节点里，只能渲染期逐段译
  '取数': 'Lấy dữ liệu',
  '回源': 'về nguồn',
  '命中': 'đụng cache',
  '节流': 'chặn nhịp',
  '本轮耗时': 'vòng',
  // 悬浮球标签是 AiBall 的自定义 prop，DOM 翻译器只遍历文本节点与 placeholder/title，接不到它，只能渲染期绑
  'AI志宁 · 快速导航': 'AI ZHINING · điều hướng nhanh',
  '缓存已超过设定时长，下列读数为最近一次成功回源的值，不代表此刻实况。':
    'Bộ nhớ đã vượt cửa sổ quy định; các số liệu dưới đây là giá trị lần về nguồn thành công gần nhất, không phản ánh thực tế lúc này.',
  '区域取数异常': 'Về nguồn khu vực có lỗi',
  '（已降级为上次成功结果，不以空表冒充「区域无风险」）':
    ' (đã giáng cấp về kết quả thành công trước đó, không dùng bảng trống để giả «khu vực không có rủi ro»)',
  '日最高': 'Cao nhất ngày',
  '日最低': 'Thấp nhất ngày',
  '云量': 'Lượng mây',
  '低云量': 'Mây thấp',
  '未来3h 降水': 'Mưa 3h tới',
  '未来6h 累计': 'Tổng 6h tới',
  '离线模拟': 'Mô phỏng ngoại tuyến',
  // 后端下发的长注脚（数据溯源说明）：按整串精确匹配，文案改了就是未命中→回落中文，不会译错
  '城市为区域热点固定点位（真实 CRA40 格点就近取值），非全站地理覆盖；路线规划仍仅覆盖中国—越南走廊路网。':
    'Các đô thị là điểm quan trắc cố định trong khu vực (lấy từ lưới CRA40 thật, chọn ô gần nhất), không phủ toàn bộ địa lý; việc quy hoạch tuyến vẫn chỉ bao phủ mạng đường bộ hành lang Trung Quốc—Việt Nam.',
  '本视图只出城市单点气象风险，不出路线：跨东盟城市对在当前路网中没有边，拿来算路会造出不存在的路。':
    'Khung nhìn này chỉ đưa rủi ro thời tiết từng đô thị, không vẽ tuyến: các cặp đô thị ASEAN chưa có cung trong mạng đường bộ hiện tại, đem tính tuyến sẽ tạo ra con đường không tồn tại.',
  '已取满官方实测开放的 11 个要素（主链路 7 个 + 日最高/最低气温与总/低云量 4 个）':
    'Đã lấy đủ 11 yếu tố mà giao diện chính thức mở (7 yếu tố luồng chính + 4 yếu tố nhiệt cao/thấp nhất ngày và tổng/thấp lượng mây)',
  '当前数据源未返回日较差/云量要素，界面按「—」显示，不填默认值':
    'Nguồn dữ liệu hiện tại không trả về yếu tố chênh nhiệt trong ngày/lượng mây, giao diện hiển thị «—», không điền giá trị mặc định',
  '未来 3h/6h 累计降雨取自数据源的前瞻预报（与走廊沙盘同一套起报时次与时效），只作态势前瞻，不并入本视图的 AMRI 单点指数，也不参与算路熔断判定':
    'Lượng mưa tích lũy 3h/6h tới lấy từ bản tin dự báo của nguồn dữ liệu (cùng thời điểm khởi báo và thời hiệu với hộp cát hành lang), chỉ để xem trước tình hình, không gộp vào chỉ số AMRI từng điểm, cũng không tham gia phán định đứt luồng khi tính tuyến',
  '当前数据源未返回前瞻预报，界面按「—」显示，不拿 0 mm 冒充未来无雨':
    'Nguồn dữ liệu hiện tại không trả về bản tin dự báo sắp tới, giao diện hiển thị «—», không lấy 0 mm để giả không mưa trong tương lai',
  '高温预警沿用 AMRI 气温项的标定中心 36℃（日最高温只作读数展示，不另起一套阈值）。':
    'Cảnh báo nóng theo ngưỡng gốc 36℃ của hạng mục nhiệt độ AMRI (nhiệt cao nhất ngày chỉ để đọc, không đặt thêm bộ ngưỡng khác).',
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

// ===== 英语界面字典（国际化第三语言；key 同为中文原文，未命中回落中文）=====
export const EN_UI = {
  // 顶部控件 / 悬浮按钮
  '放大': 'Zoom in',
  '缩小': 'Zoom out',
  '回到网络中心': 'Recenter',
  '切换底图': 'Change basemap',
  '卫星影像': 'Satellite',
  '矢量地图': 'Vector map',
  '标准地图': 'Standard map',
  '离线地图': 'Offline map',
  '切换到移动版（手机小屏样式）': 'Switch to mobile',
  '切换到PC版（整窗大屏样式）': 'Switch to PC',
  '退出全屏': 'Exit fullscreen',
  '全屏显示': 'Fullscreen',

  // 模式 / 状态浮标
  '物流任务模式': 'Logistics task mode',
  '普通导航模式': 'Standard navigation mode',
  '预览订单': 'Preview order',
  '已接单': 'Accepted',
  '退出': 'Exit',
  '免费导航 · 天气与口岸提醒': 'Free navigation · Weather & border-crossing alerts',

  // 搜索 / 路线候选
  '查找地点、规划路线': 'Search places, plan routes',
  '从哪里出发？如 南宁': 'Where from? e.g. Nanning',
  '要去哪里？如 河内': 'Where to? e.g. Hanoi',
  '交换起终点': 'Swap origin/destination',
  '常用路线': 'Frequent routes',
  '可选路线': 'Available routes',
  '点选一条进入地图预览': 'Select a route to preview on the map',
  '风险': 'risk',
  '无风险': 'No risk',
  '处风险': 'risk points',
  '灾害概率': 'Hazard probability',
  '低于阈值视为通畅': 'Below threshold, treated as clear',
  '可坐船': 'Ferry available',
  '已禁航': 'Navigation banned',
  '不受公路熔断影响': 'Unaffected by road closure',
  '平陆运河触发通航安全红线': 'Binh Luc Canal breached navigation safety limit',
  '收起': 'Collapse',
  '功能': 'Features',
  '路线计算中…': 'Calculating route…',
  '搜索路线中…': 'Searching routes…',
  '查看路线预览': 'View route preview',
  '搜索路线并进入地图预览': 'Search routes and preview on map',
  '导航中 · Agent 实时守护': 'Navigating · Agent live guard',
  '导航': 'Navigate',
  '去': 'To',
  '公里': 'km',
  '分钟': 'min',

  // 底部 Tab / 抽屉标题
  '首页': 'Home',
  '任务': 'Task',
  '预警': 'Alerts',
  '知识库': 'Knowledge',
  '我的': 'Me',
  '运单': 'Waybill',
  '物流任务': 'Logistics task',
  '风险预警': 'Risk alert',
  'AI方案': 'AI plan',
  '我的车辆': 'My vehicle',

  // 状态条
  '全线通畅': 'Route clear',
  '风险预警 · 谨慎驾驶': 'Risk alert · Drive carefully',
  '已熔断 · 自动绕行': 'Closed · Auto rerouting',
  '原地等待 · 车辆停驶': 'Hold in place · Vehicle stopped',
  '待选路线': 'No route selected',
  'GPS 搜索中…': 'Acquiring GPS…',
  '搜索中…': 'Searching…',
  '已定位 ✓': 'Located ✓',
  '不可用 ✗': 'Unavailable ✗',
  '模拟行驶 △': 'Simulated △',
  '停驶等待 ▮': 'Stopped ▮',

  // 地图 / 预览 / 导航 HUD
  '地图加载中…': 'Loading map…',
  '安全路段': 'Safe segment',
  '风险路段': 'Risk segment',
  '口岸': 'Border crossing',
  '退出导航': 'Exit navigation',
  '返回首页': 'Back to home',
  '路线预览': 'Route preview',
  '共': 'Total',
  '条可选路线': 'available routes',
  '预计到达': 'ETA',
  '展开路线列表': 'Expand route list',
  '收起路线列表': 'Collapse route list',
  '展开 ▴': 'Expand ▴',
  '收起 ▾': 'Collapse ▾',
  'AI 最安全': 'AI safest',
  '已选': 'Selected',
  '不建议通行': 'Not recommended',
  '暂无预测': 'No forecast yet',
  '展开详情 ▾': 'Details ▾',
  '展开详情': 'Details',
  '全览路线': 'Overview route',
  '开始导航': 'Start navigation',
  '导航中': 'Navigating',
  '延误': 'Delay',
  '陆水联运 · 平陆运河': 'Road-waterway intermodal · Binh Luc Canal',
  '原地等待中 · 车辆停驶': 'Holding in place · Vehicle stopped',
  '已按调度指令停在当前位置，车标不再前进；冷链机组保持运行，待风险解除后恢复行驶。':
    'Stopped in place per dispatch order; the vehicle holds position while the refrigeration unit keeps running, and will resume once the risk clears.',
  '恢复行驶': 'Resume',
  '我的位置': 'My location',
  '气象': 'Weather',

  // 任务抽屉
  '任务变更通知': 'Task change notice',
  '已确认': 'Confirmed',
  '待确认': 'Pending',
  '您的权益保障': 'Your entitlements are protected',
  '确认中…': 'Confirming…',
  '确认接收': 'Confirm receipt',
  '已确认接收 · 新路线已同步': 'Confirmed · New route synced',
  'AI 方案建议': 'AI plan suggestion',
  '查看方案': 'View plan',
  '分析中…': 'Analyzing…',
  '时效': 'Timeliness',
  '费用': 'Cost',
  '货损': 'Cargo damage',
  '不可用': 'Unavailable',
  '触达状态': 'Delivery status',
  'AI 双语预警': 'AI bilingual alert',
  '生成中…': 'Generating…',
  '生成预警': 'Generate alert',
  '当前风险清单': 'Current risk list',
  '当前无生效风险': 'No active risk',
  '行业知识速查': 'Industry knowledge quick lookup',

  // 我的
  '未设置姓名': 'Name not set',
  '未登记车牌': 'Plate not registered',
  '任务模式': 'Task mode',
  '姓名': 'Name',
  '请输入姓名': 'Enter name',
  '车牌': 'Plate',
  '车型': 'Vehicle type',
  '货物': 'Cargo',
  '请输入货物名称': 'Enter cargo name',
  '重量(t)': 'Weight (t)',
  '温控': 'Temperature',
  '语音播报': 'Voice broadcast',
  '保存信息': 'Save info',

  // 订单详情弹层
  '订单信息': 'Order info',
  '✅ 已接单': '✅ Accepted',
  '❌ 已拒单': '❌ Declined',
  '⏳ 待接单': '⏳ Awaiting acceptance',
  '吨': 't',
  '起运': 'Origin',
  '目的': 'Destination',
  '承运司机': 'Driver',
  '送达时限': 'Delivery deadline',
  '调度下发路线': 'Dispatched route',
  '路线摘要': 'Route summary',
  'AI 灾害概率': 'AI hazard probability',
  '出车前 AI 气象分析': 'Pre-departure AI weather analysis',
  '处理中…': 'Processing…',
  '接单中…': 'Accepting…',
  '✅ 确认接单': '✅ Confirm order',
  '跨境': 'cross-border',
  '待接单': 'Awaiting acceptance',
  '已拒单': 'Declined',

  // 通用按钮
  '播报': 'Read',

  // 门户（HomeApp）顶栏与通用标签
  '主页': 'Home',
  '调度大屏': 'Dispatch board',
  '司机端': 'Driver',
  '实时气象': 'Live weather',
  'AMRI 风险': 'AMRI risk',
  '水运': 'Waterway',
  '通关时效': 'Customs clearance',
  '登录 / 注册': 'Log in / Sign up',
  '公告': 'Notice',
  '数据源': 'Data source',
  '连接中…': 'Connecting…',
  '载入中…': 'Loading…',
  '重试': 'Retry',
  '跨境物流气象预警': 'Cross-border logistics weather alerts',
  // 门户「东盟态势」视图（标题/徽标/单位词；动态拼接靠术语逐词补）
  '东盟态势': 'ASEAN situation',
  '区域气象态势': 'Regional weather situation',
  '热点城市风险分布': 'Hotspot city risk distribution',
  '按 AMRI 指数降序 · 高风险置顶': 'Sorted by AMRI index, high risk first',
  '刷新区域数据': 'Refresh regional data',
  '刷新中…': 'Refreshing…',
  '走廊': 'Corridor',
  '本次真实回源': 'Fetched live',
  '节流窗口内缓存': 'Cached (within throttle window)',
  '本区域当前最不利城市': 'Most adverse city in the region right now',
  '主灾种': 'Main hazard',
  '覆盖': 'Covers',
  '国': 'countries',
  '城': 'cities',
  '气温': 'Temp',
  '降水': 'Precip',
  '风': 'Wind',
  '能见度': 'Visibility',
  // AMRI 灾种贡献的 label 枚举（后端动态下发）：同上，未命中就会中英夹杂
  '降雨': 'Rainfall',
  '风速': 'Wind speed',
  '正常': 'Normal',
  '取数': 'Fetched',
  '回源': 'upstream',
  '命中': 'cache',
  '节流': 'throttled',
  '本轮耗时': 'round',
  'AI志宁 · 快速导航': 'AI ZHINING · quick navigation',
  '缓存已超过设定时长，下列读数为最近一次成功回源的值，不代表此刻实况。':
    'The cache is older than the configured window; the readings below are from the last successful fetch and do not describe the current moment.',
  '区域取数异常': 'Regional fetch error',
  '（已降级为上次成功结果，不以空表冒充「区域无风险」）':
    ' (degraded to the last successful result; an empty table is never presented as "no risk in the region")',
  '日最高': 'Daily max',
  '日最低': 'Daily min',
  '云量': 'Cloud cover',
  '低云量': 'Low cloud',
  '未来3h 降水': 'Next 3h rain',
  '未来6h 累计': 'Next 6h total',
  '离线模拟': 'Offline simulation',
  // 后端下发的长注脚（数据溯源说明），同样按整串精确匹配
  '城市为区域热点固定点位（真实 CRA40 格点就近取值），非全站地理覆盖；路线规划仍仅覆盖中国—越南走廊路网。':
    'Cities are fixed regional observation points (nearest cell of the real CRA40 grid), not full geographic coverage; route planning still covers only the China–Vietnam corridor road network.',
  '本视图只出城市单点气象风险，不出路线：跨东盟城市对在当前路网中没有边，拿来算路会造出不存在的路。':
    'This view reports per-city weather risk only, no routes: city pairs across ASEAN have no edges in the current road network, so routing them would invent roads that do not exist.',
  '已取满官方实测开放的 11 个要素（主链路 7 个 + 日最高/最低气温与总/低云量 4 个）':
    'All 11 variables the official API was verified to expose are fetched (7 on the routing path + daily max/min temperature and total/low cloud cover)',
  '当前数据源未返回日较差/云量要素，界面按「—」显示，不填默认值':
    'The current source returned no daily temperature range or cloud cover; the UI shows "—" instead of a default value',
  '未来 3h/6h 累计降雨取自数据源的前瞻预报（与走廊沙盘同一套起报时次与时效），只作态势前瞻，不并入本视图的 AMRI 单点指数，也不参与算路熔断判定':
    'Next 3h/6h accumulated rain comes from the source forecast (same run time and lead hours as the corridor sandbox); it is forward-looking only — not folded into the per-city AMRI index and not used for routing closure decisions',
  '当前数据源未返回前瞻预报，界面按「—」显示，不拿 0 mm 冒充未来无雨':
    'The current source provides no forecast; the UI shows "—" rather than 0 mm pretending "no rain ahead"',
  '高温预警沿用 AMRI 气温项的标定中心 36℃（日最高温只作读数展示，不另起一套阈值）。':
    'The heat warning keeps the AMRI temperature term calibrated at 36 °C (daily max temperature is a reading only, not a second threshold set).',
}

// 英语专名/术语兜底（城市、口岸、灾害等；长词优先，逐词替换）
const EN_TERMS = [
  ['友谊关', 'Friendship Pass'],
  ['平陆运河', 'Binh Luc Canal'],
  ['钦州港', 'Qinzhou Port'],
  ['海防港', 'Haiphong Port'],
  ['南宁港', 'Nanning Port'],
  ['六景', 'Liujing'],
  ['芒街', 'Mong Cai'],
  ['同登', 'Dong Dang'],
  ['河口', 'Haikou (Laocai)'],
  ['凭祥', 'Pingxiang'],
  ['昆明', 'Kunming'],
  ['海防', 'Haiphong'],
  ['河内', 'Hanoi'],
  ['南宁', 'Nanning'],
  ['东兴', 'Dongxing'],
  ['老街', 'Lao Cai'],
  ['谅山', 'Lang Son'],
  ['广西', 'Guangxi'],
  ['云南', 'Yunnan'],
  ['中国', 'China'],
  ['越南', 'Vietnam'],
  ['熔断', 'closure'],
  ['禁航', 'navigation ban'],
  ['绕行', 'detour'],
  ['口岸', 'border crossing'],
  ['风险', 'risk'],
  ['暴雨', 'heavy rain'],
  ['台风', 'typhoon'],
  ['大雾', 'dense fog'],
  ['公路', 'road'],
  ['海运', 'sea freight'],
  ['随船', 'on-board'],
  ['跨境', 'cross-border'],
  ['推荐', 'recommended'],
  ['超阈值', 'over threshold'],
  ['已生效', 'in effect'],
]
EN_TERMS.sort((a, b) => b[0].length - a[0].length)

// 多字典索引：按当前语言选一份精确字典与术语表（zh 不在表内 → translate 原样返回）
const UI_DICTS = { vi: VI_UI, en: EN_UI }
const UI_TERMS = { vi: VI_TERMS, en: EN_TERMS }

/**
 * 中文 → 目标语言（vi/en）。先查整串精确字典；未命中则逐术语兜底替换。
 * 两者都没命中时原样返回（回落中文），绝不返回空或 key。
 */
export function translate(zh, lang) {
  if (zh == null) return zh
  const dict = UI_DICTS[lang]
  if (!dict) return zh
  const exact = dict[zh]
  if (exact) return exact
  let out = zh
  for (const [cn, tr] of (UI_TERMS[lang] || [])) {
    if (out.includes(cn)) out = out.split(cn).join(tr)
  }
  return out
}

/** 兼容旧签名：中文 → 越南语（内部走通用 translate）。 */
export function translateToVi(zh) {
  return translate(zh, 'vi')
}

/** 当前语言下是否要做界面替换（vi 或 en 才译，zh 原样） */
export function shouldTranslate(lang) {
  return lang === 'vi' || lang === 'en'
}

/**
 * 专名（地名/国名/城市定位）取目标语言写法。
 * <p>为什么不能直接把中文丢给 {@link translate} 逐词替换：术语表里只收录了走廊沿线的十几个专名，
 * 逐词拆一个没收录的地名会拼出「看着像译名、实际不存在」的词（专名错比不译更难发现）。
 * 所以这里只走两道可靠的路：
 * <ul>
 *   <li>vi：整词命中已有词典（VI_UI 精确表 + VI_TERMS 里本就存在的专名）才用；</li>
 *   <li>其余情况一律用后端随城市表下发的拉丁名（nameEn/countryEn/roleEn）；</li>
 *   <li>两边都没有才回落中文。</li>
 * </ul>
 * 不臆造越南语译写：拿不到就降一级用国际写法，而不是编一个看着像对的译名。
 */
export function localizePlace(zh, lang, latin) {
  if (lang === 'zh' || !shouldTranslate(lang)) return zh
  if (lang === 'vi') {
    const key = (zh || '').trim()
    if (key && VI_UI[key]) return VI_UI[key]
    const hit = VI_TERMS.find(([cn]) => cn === key)
    if (hit) return hit[1]
  }
  const l = (latin || '').trim()
  return l || zh
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
    const dict = UI_DICTS[lang] || {}
    // 先按整串精确匹配（兼顾「AI方案」这类拉丁开头的词条，不能被前缀剥掉）
    if (dict[key]) {
      const out = dict[key]
      return /[\u00C0-\u024Fa-zA-Z]$/.test(out) ? out + ' ' : out
    }
    // 剥离前导 emoji/符号（如「📋 预览订单」「✅ 已接单」），用剩余中文核心查字典
    const m = key.match(/^[^\u4E00-\u9FFF]*[\s\S]*/)
    const core = m[0].replace(/^[^\u4E00-\u9FFF]*/, '')
    const head = key.slice(0, key.length - core.length)
    if (!core) return null
    let out = null
    if (dict[core]) out = head + dict[core]
    else {
      const w = translate(core, lang)
      if (w !== core) out = head + w
    }
    if (!out || out === key) return null
    // 紧邻下一文本节点是字母/词（如「去」+城市名）时补空格，避免粘字
    return /[\u00C0-\u024Fa-zA-Z]$/.test(out) ? out + ' ' : out
  }

  function walkAndApply(root) {
    applying = true
    const translateNow = shouldTranslate(lang)
    try {
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT)
      let n
      while ((n = walker.nextNode())) {
        if (n.nodeType === Node.TEXT_NODE) {
          const parent = n.parentElement
          if (!parent || SKIP_TAGS.has(parent.tagName)) continue
          if (translateNow) {
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
            if (translateNow) {
              const tv = translate(v.trim(), lang)
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
    if (!shouldTranslate(lang) || applying) return
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
      // 先在上一门外语下把节点还原回中文底，再译到新语言：
      // 否则 vi→en 直接切会在越南语文本上查中文字典，命中不了、换不动（三态循环必需这一步）。
      if (shouldTranslate(lang)) {
        lang = 'zh'
        walkAndApply(root())
      }
      lang = next
      walkAndApply(root())
      if (shouldTranslate(next) && !observer) {
        observer = new MutationObserver(scheduleReapply)
        observer.observe(root(), { subtree: true, childList: true, characterData: true })
      }
      if (!shouldTranslate(next) && observer) {
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
