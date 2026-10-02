// 统一时间口径：逻辑日从 02:00 开始（00:00-02:00 属于前一天的深夜），时段固定为下面 10 段
const DAY_START_HOUR = 2;
const TIME_SLOT_TABLE = [
  { slot: '深夜', start: 0, end: 4, isoHour: 2 },
  { slot: '凌晨', start: 4, end: 6, isoHour: 4 },
  { slot: '清晨', start: 6, end: 7.5, isoHour: 6 },
  { slot: '早晨', start: 7.5, end: 10.5, isoHour: 7.5 },
  { slot: '上午', start: 10.5, end: 12, isoHour: 10.5 },
  { slot: '中午', start: 12, end: 13.5, isoHour: 12 },
  { slot: '下午', start: 13.5, end: 17, isoHour: 13.5 },
  { slot: '傍晚', start: 17, end: 19, isoHour: 17 },
  { slot: '晚上', start: 19, end: 22, isoHour: 19 },
  { slot: '夜里', start: 22, end: 24, isoHour: 22 }
];
const TIME_SLOTS = TIME_SLOT_TABLE.map(function(entry) { return entry.slot; });
const TIME_SLOT_ALIASES = { '半夜': '深夜', '早上': '早晨' };
const TIME_PARSE_VERSION = 1;
const TIME_REF_ANCHORS = ['today', 'tomorrow', 'yesterday', 'day_after_tomorrow', 'day_before_yesterday', 'this_week', 'next_week', 'last_week', 'this_month', 'next_month', 'last_month', 'this_year', 'next_year', 'last_year'];
const TIME_REF_ANCHOR_LABELS = { today: '今天', tomorrow: '明天', yesterday: '昨天', day_after_tomorrow: '后天', day_before_yesterday: '前天', this_week: '本周', next_week: '下周', last_week: '上周', this_month: '本月', next_month: '下个月', last_month: '上个月', this_year: '今年', next_year: '明年', last_year: '去年' };
const TIME_REF_WEEKDAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
// 锚点后紧跟这些字说明它在构词（“今天气”“今日头条”），不参与替换
const RELATIVE_ANCHOR_BLOCKERS = { '今天': ['气'], '今日': ['头'], '明天': ['理'] };
const RELATIVE_DAY_ANCHORS = [
  { word: '大后天', offset: 3 }, { word: '大前天', offset: -3 },
  { word: '后天', offset: 2 }, { word: '前天', offset: -2 },
  { word: '明天', offset: 1 }, { word: '明日', offset: 1 }, { word: '次日', offset: 1 }, { word: '翌日', offset: 1 },
  { word: '今天', offset: 0 }, { word: '今日', offset: 0 }, { word: '当天', offset: 0 }, { word: '当日', offset: 0 },
  { word: '昨天', offset: -1 }, { word: '昨日', offset: -1 }
];
const RELATIVE_SHORT_ANCHORS = [{ word: '明', offset: 1 }, { word: '今', offset: 0 }, { word: '昨', offset: -1 }];
const RELATIVE_SHORT_SLOTS = { '早': '早晨', '晨': '清晨', '午': '中午', '晚': '晚上', '夜': '夜里' };
const RELATIVE_CHINESE_NUMBERS = { '一': 1, '两': 2, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10 };

function buildRelativeTimePhrases() {
  var slotWords = TIME_SLOTS.concat(Object.keys(TIME_SLOT_ALIASES));
  var phrases = [];
  RELATIVE_DAY_ANCHORS.forEach(function(anchor) {
    slotWords.forEach(function(slotWord) {
      phrases.push({ literal: anchor.word + slotWord, offset: anchor.offset, slot: slotWord, anchorWord: anchor.word });
    });
    phrases.push({ literal: anchor.word, offset: anchor.offset, slot: '', anchorWord: anchor.word });
  });
  RELATIVE_SHORT_ANCHORS.forEach(function(anchor) {
    Object.keys(RELATIVE_SHORT_SLOTS).forEach(function(shortSlot) {
      phrases.push({ literal: anchor.word + shortSlot, offset: anchor.offset, slot: RELATIVE_SHORT_SLOTS[shortSlot], anchorWord: '' });
    });
  });
  return phrases.sort(function(a, b) { return b.literal.length - a.literal.length; });
}
const RELATIVE_TIME_PHRASES = buildRelativeTimePhrases();

function padTwoDigits(value) { return (value < 10 ? '0' : '') + value; }

function normalizeTimeSlot(value) {
  var slot = toText(value).trim();
  if (TIME_SLOTS.indexOf(slot) !== -1) return slot;
  return TIME_SLOT_ALIASES[slot] || '';
}

function getTimeSlot(date) {
  if (!(date instanceof Date) || isNaN(date.getTime())) return '';
  var hour = date.getHours() + date.getMinutes() / 60;
  for (var index = 0; index < TIME_SLOT_TABLE.length; index++) {
    var entry = TIME_SLOT_TABLE[index];
    if (hour >= entry.start && hour < entry.end) return entry.slot;
  }
  return '深夜';
}

// 逻辑日：减去 DAY_START_HOUR 后的日历日
function getLogicalDayDate(value) {
  var date = value instanceof Date ? new Date(value.getTime()) : new Date(value || Date.now());
  if (isNaN(date.getTime())) date = new Date();
  date.setHours(date.getHours() - DAY_START_HOUR);
  date.setHours(0, 0, 0, 0);
  return date;
}

function formatDayLabel(date) {
  return date.getFullYear() + '-' + padTwoDigits(date.getMonth() + 1) + '-' + padTwoDigits(date.getDate());
}

function shiftDays(date, days) {
  var next = new Date(date.getTime());
  next.setDate(next.getDate() + days);
  return next;
}

function getWeekStart(date) {
  var day = date.getDay();
  return shiftDays(date, day === 0 ? -6 : 1 - day);
}

function buildResolvedTime(dayDate, slot, rangeLabel) {
  var normalizedSlot = normalizeTimeSlot(slot);
  var dayLabel = dayDate ? formatDayLabel(dayDate) : null;
  var range = rangeLabel || null;
  var text = dayLabel ? (dayLabel + (normalizedSlot ? ' ' + normalizedSlot : '')) : (range ? (range + (normalizedSlot ? ' ' + normalizedSlot : '')) : '');
  if (!text) return null;
  var iso = null;
  if (dayDate && normalizedSlot) {
    var entry = TIME_SLOT_TABLE.filter(function(item) { return item.slot === normalizedSlot; })[0];
    var hour = entry.isoHour;
    var wholeHour = Math.floor(hour);
    var resolved = new Date(dayDate.getTime());
    resolved.setHours(wholeHour, Math.round((hour - wholeHour) * 60), 0, 0);
    iso = resolved.toISOString();
  }
  return { day: dayLabel, range: range, slot: normalizedSlot, text: text, iso: iso };
}

// timeRef 词元 → 绝对时间；返回 null 表示无法解析
function resolveTimeRef(ref, baseDate) {
  if (!isPlainObject(ref)) return null;
  var base = baseDate instanceof Date ? baseDate : new Date(baseDate || Date.now());
  if (isNaN(base.getTime())) base = new Date();
  var slot = normalizeTimeSlot(ref.slot);
  var explicitRaw = toText(ref.explicit).trim();
  if (explicitRaw) {
    var dateOnly = explicitRaw.match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$/);
    var explicitDate = dateOnly ? new Date(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3])) : new Date(explicitRaw);
    if (dateOnly && (explicitDate.getFullYear() !== Number(dateOnly[1]) || explicitDate.getMonth() + 1 !== Number(dateOnly[2]) || explicitDate.getDate() !== Number(dateOnly[3]))) return null;
    if (isNaN(explicitDate.getTime())) explicitDate = new Date(explicitRaw.replace(/[/.]/g, '-'));
    if (!isNaN(explicitDate.getTime())) {
      if (!dateOnly && /[T ]\d{2}:\d{2}/.test(explicitRaw)) {
        return { day: formatDayLabel(explicitDate), range: null, slot: getTimeSlot(explicitDate),
          text: explicitRaw, iso: explicitDate.toISOString() };
      }
      var explicitDay = new Date(explicitDate.getTime());
      explicitDay.setHours(0, 0, 0, 0);
      return buildResolvedTime(explicitDay, slot, null);
    }
    return null;
  }
  var anchor = toText(ref.anchor).trim().toLowerCase();
  if (!anchor && (ref.offsetDays != null || ref.slot)) anchor = 'today';
  if (TIME_REF_ANCHORS.indexOf(anchor) === -1) return null;
  var logicalDay = getLogicalDayDate(base);
  var weekday = TIME_REF_WEEKDAYS.indexOf(toText(ref.weekday).trim().toLowerCase());
  var extraDays = Number(ref.offsetDays);
  extraDays = Number.isFinite(extraDays) ? Math.max(-60, Math.min(60, Math.floor(extraDays))) : 0;
  var dayAnchors = { today: 0, tomorrow: 1, yesterday: -1, day_after_tomorrow: 2, day_before_yesterday: -2 };
  if (Object.prototype.hasOwnProperty.call(dayAnchors, anchor)) {
    return buildResolvedTime(shiftDays(logicalDay, dayAnchors[anchor] + extraDays), slot, null);
  }
  if (anchor === 'this_week' || anchor === 'next_week' || anchor === 'last_week') {
    var weekStart = shiftDays(getWeekStart(logicalDay), anchor === 'next_week' ? 7 : (anchor === 'last_week' ? -7 : 0));
    if (weekday >= 0) return buildResolvedTime(shiftDays(weekStart, weekday), slot, null);
    return buildResolvedTime(null, slot, formatDayLabel(weekStart) + '～' + formatDayLabel(shiftDays(weekStart, 6)));
  }
  if (anchor === 'this_month' || anchor === 'next_month' || anchor === 'last_month') {
    var monthShift = anchor === 'next_month' ? 1 : (anchor === 'last_month' ? -1 : 0);
    var monthAnchor = new Date(logicalDay.getFullYear(), logicalDay.getMonth() + monthShift, 1);
    if (weekday >= 0) {
      var weekdayOffset = (weekday - ((monthAnchor.getDay() + 6) % 7) + 7) % 7;
      return buildResolvedTime(shiftDays(monthAnchor, weekdayOffset), slot, null);
    }
    return buildResolvedTime(null, slot, monthAnchor.getFullYear() + '-' + padTwoDigits(monthAnchor.getMonth() + 1) + '（' + TIME_REF_ANCHOR_LABELS[anchor] + '）');
  }
  if (anchor === 'this_year' || anchor === 'next_year' || anchor === 'last_year') {
    var yearShift = anchor === 'next_year' ? 1 : (anchor === 'last_year' ? -1 : 0);
    return buildResolvedTime(null, slot, (logicalDay.getFullYear() + yearShift) + '年（' + TIME_REF_ANCHOR_LABELS[anchor] + '）');
  }
  return null;
}

// 自由文本里的相对时间 → 绝对日期（旧数据兼容与写入兜底）
function parseRelativeText(text, baseDate) {
  var value = toText(text);
  if (!value) return value;
  var base = baseDate instanceof Date ? baseDate : new Date(baseDate || Date.now());
  if (isNaN(base.getTime())) base = new Date();
  var logicalDay = getLogicalDayDate(base);
  var result = value;
  RELATIVE_TIME_PHRASES.forEach(function(phrase) {
    var fromIndex = 0;
    while (true) {
      var index = result.indexOf(phrase.literal, fromIndex);
      if (index === -1) return;
      var nextChar = result.slice(index + phrase.literal.length, index + phrase.literal.length + 1);
      var blockers = RELATIVE_ANCHOR_BLOCKERS[phrase.anchorWord] || [];
      if (!phrase.slot && blockers.indexOf(nextChar) !== -1) { fromIndex = index + phrase.literal.length; continue; }
      var replacement = formatDayLabel(shiftDays(logicalDay, phrase.offset)) + (phrase.slot ? ' ' + normalizeTimeSlot(phrase.slot) : '');
      result = result.slice(0, index) + replacement + result.slice(index + phrase.literal.length);
      fromIndex = index + replacement.length;
    }
  });
  result = result.replace(/(这|本|上|下)周([一二三四五六日天])/g, function(match, prefix, weekdayChar) {
    var weekdayIndex = '一二三四五六日'.indexOf(weekdayChar);
    if (weekdayIndex === -1) weekdayIndex = 6;
    var weekStart = shiftDays(getWeekStart(logicalDay), prefix === '下' ? 7 : (prefix === '上' ? -7 : 0));
    return formatDayLabel(shiftDays(weekStart, weekdayIndex));
  });
  result = result.replace(/(这个月|本月|上个月|下个月|上月|下月)/g, function(match) {
    var monthShift = (match === '上个月' || match === '上月') ? -1 : ((match === '下个月' || match === '下月') ? 1 : 0);
    var monthAnchor = new Date(logicalDay.getFullYear(), logicalDay.getMonth() + monthShift, 1);
    return monthAnchor.getFullYear() + '-' + padTwoDigits(monthAnchor.getMonth() + 1);
  });
  result = result.replace(/(今年|去年|明年)/g, function(match) {
    var yearShift = match === '去年' ? -1 : (match === '明年' ? 1 : 0);
    return (logicalDay.getFullYear() + yearShift) + '年';
  });
  result = result.replace(/(\d{1,3}|[一两二三四五六七八九十])\s*天\s*(以后|之后|以前|之前|后|前)/g, function(match, amountText, directionText) {
    var amount = /^\d+$/.test(amountText) ? Number(amountText) : (RELATIVE_CHINESE_NUMBERS[amountText] || 0);
    var isFuture = ['以后', '之后', '后'].indexOf(directionText) !== -1;
    return formatDayLabel(shiftDays(logicalDay, isFuture ? amount : -amount));
  });
  return result;
}

function sanitizeRelativeTime(text, baseDate) {
  return parseRelativeText(text, baseDate || new Date());
}

// 动态状态字段写入时统一换算为绝对日期
function sanitizeDynamicStateField(key, value, baseDate) {
  return parseRelativeText(value, baseDate || new Date());
}

// 清洗模型返回的字段值。分层处理，避免误伤需要叙述的字段：
// - Tier1（所有字段）：剥离"字段名："前缀；只剥离含元信息词的括号（"（用户要求）"），
//   不含元信息词的括号是内容（"（那天下着暴雨）"）原样保留。
// - Tier2（仅短事实字段）：再按句剥离说明句式（"用户希望我…"、"这是用户的要求"…）。
//   叙事字段（背景/关键过往/近期进展等）不做句级删除，防止删掉正常叙述。
const FIELD_META_MARKERS = ['用户', '角色', '玩家', '希望', '要求', '因为', '由于', '所以', '说明', '备注', '设定', '剧情', '依据', '来源', '规则', '指令', '系统', 'evidence'];
const SHORT_FACT_FIELDS = ['gender', 'age', 'race', 'language', 'userAddress', 'roleInGroup', 'currentOccupation', 'currentLocation', 'currentMood', 'currentGoal'];

function isMetaParenthetical(inner) {
  var text = toText(inner);
  return FIELD_META_MARKERS.some(function(marker) { return text.indexOf(marker) !== -1; });
}

function stripMetaParentheticals(text) {
  var previous;
  var result = text;
  do {
    previous = result;
    result = result.replace(/（([^（）()]*)）|\(([^（）()]*)\)/g, function(match, fullWidth, halfWidth) {
      var inner = fullWidth != null ? fullWidth : (halfWidth != null ? halfWidth : '');
      return isMetaParenthetical(inner) ? ' ' : match;
    });
  } while (result !== previous);
  return result;
}

function splitSentences(text) {
  var chunks = [];
  var buffer = '';
  for (var index = 0; index < text.length; index++) {
    buffer += text[index];
    if ('。！？；，,'.indexOf(text[index]) !== -1) {
      chunks.push(buffer);
      buffer = '';
    }
  }
  if (buffer) chunks.push(buffer);
  return chunks;
}

function isExplanatorySentence(sentence) {
  var text = toText(sentence).trim();
  if (!text) return true;
  var hasActor = /用户|角色|玩家/.test(text);
  var hasMetaVerb = /希望|要求|提到|决定|改成|改为|设定|剧情需要|说明|备注/.test(text);
  var startsMeta = /^(这是|原因是|所以|因此|因为|由于|其实|说明|备注)/.test(text);
  if (startsMeta && hasActor) return true;
  if (hasActor && hasMetaVerb) return true;
  return false;
}

function cleanFieldValue(key, value) {
  var text = toText(value);
  var fieldKeys = {};
  var fieldLabels = [];
  Object.keys(STATIC_PROFILE_FIELDS).forEach(function(k) { fieldKeys[k] = true; fieldLabels.push(STATIC_PROFILE_FIELDS[k].label); });
  DYNAMIC_STATE_FIELDS.forEach(function(f) { fieldKeys[f.key] = true; fieldLabels.push(f.label); });
  // 剥离开头字段名前缀：如"职业：教师"、"当前目标: xxx"、"性格特征：冷静"
  var prefixMatch = text.match(/^\s*[^：:\n]{1,24}[：:]\s*/);
  if (prefixMatch) {
    var prefix = prefixMatch[0].replace(/[：:]/g, '').trim();
    var prefixIsField = fieldKeys[prefix] || fieldLabels.some(function(label) {
      return label === prefix || (label.length >= 3 && prefix.length >= 2 && (label.indexOf(prefix) !== -1 || prefix.indexOf(label) !== -1));
    });
    if (prefixIsField) {
      text = text.slice(prefixMatch[0].length);
    }
  }
  text = stripMetaParentheticals(text);
  if (SHORT_FACT_FIELDS.indexOf(key) !== -1) {
    text = splitSentences(text).filter(function(sentence) { return !isExplanatorySentence(sentence); }).join('');
    text = text.replace(/[，,。；;、\s]+$/, '');
  }
  return trimText(text.replace(/\s+/g, ' ').trim(), 700);
}
