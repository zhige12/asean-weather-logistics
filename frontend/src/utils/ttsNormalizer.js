/**
 * TTS (Text-to-Speech) 文本标准化工具
 *
 * 将原始技术文本转换为适合语音播报的自然语言格式。
 * 支持中文 (zh-CN) 和越南语 (vi-VN) 两种语言。
 *
 * 处理规则：
 * 1. 移除 emoji、特殊符号、markdown 格式、HTML 标签
 * 2. 节点ID → 可读名称（如 NN→南宁, E4→友谊关）
 * 3. 单位转换（°C→摄氏度, km/h→公里每小时, mm→毫米 等）
 * 4. 温度范围（2~6°C → 2到6摄氏度）
 * 5. 百分比（50% → 百分之五十）
 * 6. 日期格式（09/08 15:30 → 9月8日15点30分）
 * 7. 路线符号（→ → 至）
 * 8. 语言特定处理（越南语去除中文字符等）
 */

// ============================================================
// 节点ID → 可读名称映射（与 DriverApp.vue 中的 NODE_NAMES 保持一致）
// ============================================================
const NODE_NAME_MAP = {
  NN: '南宁', LJ: '南宁港六景作业区', CZ: '崇左', PX: '凭祥', YGG: '友谊关口岸', DX: '东兴', HK: '河口口岸',
  DD: '同登口岸', LS: '谅山', BG: '北江', BN: '北宁', HN: '河内', MC: '芒街口岸',
  HL: '下龙', HD: '海阳', HP: '海防', ND: '南定', NB: '宁平', TH: '清化', VH: '荣市',
  HT: '河静', DQH: '洞海', QT: '广治', HUE: '顺化', DN: '岘港', TY: '太原', TQ: '宣光',
  YB: '安沛', LC: '老街', SPA: '沙巴', HB: '和平', SL: '山萝', DB: '奠边府',
  E4: '友谊关', E9: '芒街', E36: '河口'
};

const NODE_NAME_VN = {
  NN: 'Nam Ninh', LJ: 'cảng Nam Ninh (Lục Cảnh)', CZ: 'Sùng Tả', PX: 'Bằng Tường', YGG: 'cửa khẩu Hữu Nghị Quan',
  DX: 'Đông Hưng', HK: 'cửa khẩu Hà Khẩu',
  DD: 'cửa khẩu Đồng Đăng', LS: 'Lạng Sơn', BG: 'Bắc Giang', BN: 'Bắc Ninh',
  HN: 'Hà Nội', MC: 'cửa khẩu Móng Cái',
  HL: 'Hạ Long', HD: 'Hải Dương', HP: 'Hải Phòng', ND: 'Nam Định',
  NB: 'Ninh Bình', TH: 'Thanh Hóa', VH: 'Vinh',
  HT: 'Hà Tĩnh', DQH: 'Đồng Hới', QT: 'Quảng Trị', HUE: 'Huế', DN: 'Đà Nẵng',
  TY: 'Thái Nguyên', TQ: 'Tuyên Quang', YB: 'Yên Bái', LC: 'Lào Cai',
  SPA: 'Sa Pa', HB: 'Hòa Bình', SL: 'Sơn La', DB: 'Điện Biên Phủ',
  E4: 'Hữu Nghị Quan', E9: 'Móng Cái', E36: 'Hà Khẩu'
};

const HAZARD_VN_MAP = {
  '暴雨': 'mưa lớn', '大雨': 'mưa to', '强降雨': 'mưa rất to', '特大暴雨': 'mưa cực lớn',
  '塌方': 'sạt lở đất', '滑坡': 'lở đất', '泥石流': 'lũ quét và sạt lở đất', '山洪': 'lũ quét',
  '台风': 'bão', '大风': 'gió mạnh', '风暴': 'bão tố',
  '大雾': 'sương mù dày đặc', '低能见度': 'tầm nhìn thấp', '能见度低': 'tầm nhìn thấp',
  '高温': 'nhiệt độ cao', '热浪': 'sóng nhiệt',
  '气象灾害': 'thiên tai thời tiết', '地质灾害': 'tai biến địa chất',
  '阵风': 'gió giật', '轻雾': 'sương mù nhẹ', '高温冷链': 'nhiệt độ cao ảnh hưởng hàng lạnh'
};

const ZH_UNIT_PATTERNS = [
  { pattern: /(\d+(?:\.\d+)?)\s*km\/h/gi, replacement: '$1公里每小时' },
  { pattern: /(\d+(?:\.\d+)?)\s*mm\/h/gi, replacement: '$1毫米每小时' },
  { pattern: /(\d+(?:\.\d+)?)\s*°C/gi, replacement: '$1摄氏度' },
  { pattern: /(\d+(?:\.\d+)?)\s*mm\b/gi, replacement: '$1毫米' },
  { pattern: /(\d+(?:\.\d+)?)\s*km\b/gi, replacement: '$1公里' },
  { pattern: /(\d+(?:\.\d+)?)\s*m\b/gi, replacement: '$1米' },
  { pattern: /(\d+(?:\.\d+)?)\s*h\b/gi, replacement: '$1小时' },
  { pattern: /(\d+)\s*~\s*(\d+)\s*°C/gi, replacement: '$1到$2摄氏度' },
  { pattern: /(\d+(?:\.\d+)?)\s*~\s*(\d+(?:\.\d+)?)\s*h\b/gi, replacement: '$1到$2小时' },
  { pattern: /(\d+)\s*~\s*(\d+)/g, replacement: '$1到$2' },
];

const VI_UNIT_PATTERNS = [
  { pattern: /(\d+(?:\.\d+)?)\s*km\/h/gi, replacement: '$1 km trên giờ' },
  { pattern: /(\d+(?:\.\d+)?)\s*mm\/h/gi, replacement: '$1 mm trên giờ' },
  { pattern: /(\d+(?:\.\d+)?)\s*°C/gi, replacement: '$1 độ C' },
  { pattern: /(\d+(?:\.\d+)?)\s*mm\b/gi, replacement: '$1 mi-li-mét' },
  { pattern: /(\d+(?:\.\d+)?)\s*km\b/gi, replacement: '$1 ki-lô-mét' },
  { pattern: /(\d+(?:\.\d+)?)\s*m\b/gi, replacement: '$1 mét' },
  { pattern: /(\d+(?:\.\d+)?)\s*h\b/gi, replacement: '$1 giờ' },
  { pattern: /(\d+)\s*~\s*(\d+)\s*°C/gi, replacement: '$1 đến $2 độ C' },
  { pattern: /(\d+(?:\.\d+)?)\s*~\s*(\d+(?:\.\d+)?)\s*h\b/gi, replacement: '$1 đến $2 giờ' },
  { pattern: /(\d+)\s*~\s*(\d+)/g, replacement: '$1 đến $2' },
];

// ============================================================
// 核心：TTS 文本标准化
// ============================================================

export function normalizeForTTS(text, lang) {
  if (!text || typeof text !== 'string') return '';

  let result = text;
  result = stripEmoji(result);
  result = stripMarkdown(result);
  result = stripHtmlTags(result);
  result = stripSpecialChars(result);

  const detectedLang = detectLanguage(result);
  const effectiveLang = lang || detectedLang || 'zh-CN';

  if (effectiveLang === 'vi-VN') {
    result = replaceNodeIds(result, NODE_NAME_VN);
  } else {
    result = replaceNodeIds(result, NODE_NAME_MAP);
  }

  result = result.replace(/→/g, '至');
  result = result.replace(/->/g, '至');
  result = result.replace(/-->/g, '至');

  const unitPatterns = effectiveLang === 'vi-VN' ? VI_UNIT_PATTERNS : ZH_UNIT_PATTERNS;
  for (const { pattern, replacement } of unitPatterns) {
    result = result.replace(pattern, replacement);
  }

  if (effectiveLang === 'vi-VN') {
    result = result.replace(/(\d+(?:\.\d+)?)\s*%/g, '$1 phần trăm');
  } else {
    result = convertPercentagesZh(result);
  }

  if (effectiveLang === 'vi-VN') {
    result = convertDatesVi(result);
  } else {
    result = convertDatesZh(result);
  }

  if (effectiveLang === 'vi-VN') {
    result = cleanVietnameseText(result);
  } else {
    result = cleanChineseText(result);
  }

  result = result.replace(/\s{2,}/g, ' ').trim();
  return result;
}

// ============================================================
// 语言检测
// ============================================================

export function detectLanguage(text) {
  if (!text) return 'zh-CN';
  const cjkCount = (text.match(/[一-鿿㐀-䶿豈-﫿]/g) || []).length;
  const viCount = (text.match(/[àáảãạâầấẩẫậăằắẳẵặèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/gi) || []).length;
  if (cjkCount > viCount && cjkCount > 5) return 'zh-CN';
  if (viCount > 0) return 'vi-VN';
  if (cjkCount > 0) return 'zh-CN';
  const viWords = /\b(cảnh\s+báo|tuyến\s+đường|cửa\s+khẩu|thời\s+tiết|hàng\s+hóa|tài\s+xế|giấy\s+phép|kiểm\s+tra|vui\s+lòng|đề\s+xuất|thay\s+thế|xin\s+lưu\s+ý|chú\s+ý)\b/i;
  if (viWords.test(text)) return 'vi-VN';
  return 'zh-CN';
}

// ============================================================
// 内部辅助函数
// ============================================================

function stripEmoji(text) {
  return text
    .replace(/[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}\u{FE00}-\u{FE0F}\u{200D}]/gu, '')
    .replace(/[\u{1F900}-\u{1F9FF}\u{1FA00}-\u{1FA6F}\u{1FA70}-\u{1FAFF}]/gu, '');
}

function stripMarkdown(text) {
  return text
    .replace(/\*\*?(?!\s)/g, '')
    .replace(/(?<!\s)\*\*?/g, '')
    .replace(/__?/g, '')
    .replace(/~~/g, '')
    .replace(/`{1,3}/g, '')
    .replace(/#{1,6}\s?/g, '')
    .replace(/^[-*+]\s/gm, '')
    .replace(/^>\s?/gm, '');
}

function stripHtmlTags(text) {
  return text.replace(/<[^>]+>/g, '');
}

function stripSpecialChars(text) {
  return text
    .replace(/[★☆●○◆◇▲△▼▽■□]/g, '')
    .replace(/[①②③④⑤⑥⑦⑧⑨⑩]/g, '')
    .replace(/[❶❷❸❹❺❻❼❽❾❿]/g, '')
    .replace(/[【】「」『』《》]/g, '')
    .replace(/[〒〶〄]/g, '');
}

/** 替换节点ID为可读名称 */
function replaceNodeIds(text, nameMap) {
  // 不匹配后面紧跟越南语带声调字母的情况（避免破坏越南语单词如 THỜI）
  return text.replace(/\b([A-Z]{2,4})(?![A-Za-zÀ-ɏḀ-ỿ])/g, (match) => {
    return nameMap[match] || match;
  });
}

function convertPercentagesZh(text) {
  return text.replace(/(\d+(?:\.\d+)?)\s*%/g, (_match, num) => {
    const n = parseFloat(num);
    if (Number.isInteger(n) && n <= 99) {
      return '百分之' + numberToChineseWords(Math.round(n));
    }
    if (n === 100) return '百分之百';
    return '百分之' + num;
  });
}

function numberToChineseWords(n) {
  if (n === 0) return '零';
  if (n < 10) return ['', '一', '二', '三', '四', '五', '六', '七', '八', '九'][n];
  if (n < 20) return '十' + (n % 10 === 0 ? '' : ['', '一', '二', '三', '四', '五', '六', '七', '八', '九'][n % 10]);
  const tens = ['', '', '二', '三', '四', '五', '六', '七', '八', '九'][Math.floor(n / 10)];
  const ones = n % 10 === 0 ? '' : ['', '一', '二', '三', '四', '五', '六', '七', '八', '九'][n % 10];
  return tens + '十' + ones;
}

function convertDatesZh(text) {
  return text.replace(/(\d{1,2})\/(\d{1,2})\s+(\d{1,2}):(\d{2})/g, (_match, m, d, h, min) => {
    const month = parseInt(m, 10);
    const day = parseInt(d, 10);
    const hour = parseInt(h, 10);
    const minute = parseInt(min, 10);
    return `${month}月${day}日${hour}点${minute === 0 ? '' : minute + '分'}`;
  });
}

function convertDatesVi(text) {
  return text.replace(/(\d{1,2})\/(\d{1,2})\s+(\d{1,2}):(\d{2})/g, (_match, m, d, h, min) => {
    const month = parseInt(m, 10);
    const day = parseInt(d, 10);
    const hour = parseInt(h, 10);
    const minute = parseInt(min, 10);
    return `ngày ${day} tháng ${month}, ${hour} giờ ${minute === 0 ? '' : minute + ' phút'}`;
  });
}

/** 常见英文缩写 → 中文翻译（中文 TTS 读不了英文，会逐字母念） */
const EN_TO_ZH = {
  'TIR': '国际运输许可证', 'ETA': '预计到达时间', 'GPS': '卫星定位',
  'SOS': '紧急求救', 'Agent': '智能助手', 'agent': '智能助手',
  'AI': '人工智能', 'RAG': '知识检索增强', 'DeepSeek': '深度求索',
};

/** 英文字母 → 中文拼音读法（用于逐字母读出未知缩写，如 DKNN → 迪凯恩恩） */
const LETTER_PINYIN = {
  'A': '艾', 'B': '比', 'C': '西', 'D': '迪', 'E': '伊', 'F': '艾弗',
  'G': '吉', 'H': '艾奇', 'I': '艾', 'J': '杰', 'K': '凯', 'L': '艾勒',
  'M': '艾姆', 'N': '恩', 'O': '欧', 'P': '皮', 'Q': '丘', 'R': '艾',
  'S': '艾斯', 'T': '提', 'U': '优', 'V': '维', 'W': '达布溜',
  'X': '艾克斯', 'Y': '外', 'Z': '泽',
};

/** 将英文缩写逐字母转为中文拼音读法 */
function lettersToPinyin(text) {
  return text.replace(/\b([A-Za-z]{2,})\b/g, (_match, word) => {
    return word.toUpperCase().split('').map(c => LETTER_PINYIN[c] || c).join('');
  });
}

/** 中文文本清理 */
function cleanChineseText(text) {
  let result = text
    .replace(/[àáảãạâầấẩẫậăằắẳẵặèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/gi, '');

  // 替换已知英文缩写为中文
  for (const [en, zh] of Object.entries(EN_TO_ZH)) {
    result = result.replace(new RegExp('\\b' + en + '\\b', 'gi'), zh);
  }

  // 兜底：剩余英文缩写逐字母转为拼音读法（如 DKNN → 迪凯恩恩）
  result = lettersToPinyin(result);
  // 清理单个残留字母
  result = result.replace(/\b[A-Za-z]\b/g, '');

  return result
    .replace(/[：:]\s*$/, '')
    .replace(/[,，]{2,}/g, '，');
}

/** 越南语文本清理 */
function cleanVietnameseText(text) {
  // 第一步：将所有 CJK 字符替换为空格（而非直接删除）
  // 这样 CJK 清除后残留的拉丁字母会被空格分隔，便于后续识别和清除
  let result = text
    .replace(/[一-鿿㐀-䶿豈-﫿⺀-⻿㇀-㇯⼀-⿏]+/gu, ' ')
    .replace(/\p{sc=Han}+/gu, ' ')
    .replace(/\p{sc=Hiragana}+/gu, ' ')
    .replace(/\p{sc=Katakana}+/gu, ' ')
    .replace(/\p{sc=Hangul}+/gu, ' ');

  // 第二步：移除 CJK 标点和全角符号（也替换为空格）
  result = result
    .replace(/[　-〿]/g, ' ')
    .replace(/[！-～]/g, ' ');

  // 第三步：移除非越南语的孤立拉丁字母残留
  // CJK清除后可能留下零散字母(如 d, k, n)，TTS会逐字母读
  // 只移除孤立的单个字母，保留多字母词（如 Nam, Ninh 等越南语地名）
  // 使用Unicode感知边界，避免把越南语单词首字母误判为孤立字母（如 Bắt 的 B）
  result = result
    .replace(/(?<![A-Za-zÀ-ɏḀ-ỿ])[a-zA-Z](?![A-Za-zÀ-ɏḀ-ỿ])/g, '');

  // 第四步：清理残留的标点碎片和空白
  result = result
    .replace(/[,]{2,}/g, ',')
    .replace(/[.]{2,}/g, '.')
    .replace(/\s{2,}/g, ' ')
    .trim();

  return result;
}

// ============================================================
// 导出
// ============================================================

export { NODE_NAME_MAP, NODE_NAME_VN, HAZARD_VN_MAP };
