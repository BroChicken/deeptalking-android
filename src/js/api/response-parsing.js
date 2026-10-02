// 模型常把真实换行写成字面量 \n（双转义，显示为 "\\n\\n"），这里还原为真实换行；\\n 保持原样显示
function unescapeLiteralNewlines(text) {
  return String(text)
    .replace(/\\\\n/g, '\u0000') // \\n -> 占位（保留为字面 \n）
    .replace(/\\n/g, '\n')       // \n -> 真实换行
    .replace(/\u0000/g, '\\n');  // 还原占位
}

function getDisplayText(text) {
  var result = String(text);
  var fullMatch = result.match(/<\s*MEM_UPDATE\s*>[\s\S]*?<\/\s*MEM_UPDATE\s*>/i);
  if (fullMatch) {
    result = result.replace(fullMatch[0], '').trim();
  } else {
    var partialIdx = result.search(/<\s*MEM_UPDATE\s*>/i);
    if (partialIdx !== -1) {
      result = result.substring(0, partialIdx).trim();
    }
  }
  return unescapeLiteralNewlines(result);
}

function updateMessageBubble(messageId, text) {
  var container = document.getElementById('messageContainer');
  if (!container) return;
  var row = messageRowCache ? messageRowCache[messageId] : null;
  if (!row || !row.isConnected) {
    var rows = container.querySelectorAll('[data-role="assistant"][data-msg-id]');
    row = Array.prototype.find.call(rows, function(item) { return item.dataset.msgId === messageId; });
    if (row && messageRowCache) messageRowCache[messageId] = row;
  }
  if (!row) return;
  var contentDiv = row.querySelector('.message-content');
  if (!contentDiv) return;
  var shouldFollow = isNearChatBottom(container);
  contentDiv.className = 'message-content';
  contentDiv.innerHTML = formatMessageContent(text);
  if (shouldFollow) container.scrollTop = container.scrollHeight;
}

// 模型经常把 reply 等自由文本字段里的引号与换行原样写进 JSON 字符串（群组回复尤其常见：
// 成员名后的 "台词" 带半角引号，成员之间还有真实换行），导致 JSON.parse 直接失败。
// 这里对已知自由文本字段做定向修复：取 "key":" 之后到最近一个 "下一个键名": 或 "} 之前的
// 原文，先解码已有转义再交给 JSON.stringify 重新转义拼回，使整段 JSON 可解析——解析成功后
// quickReplies/shortTerm/longTerm/dynamicState 等记忆字段也一并保住，不必再触发"整理回复"。
const JSON_FREETEXT_KEYS = ['reply', 'content', 'value', 'key', 'evidence', 'question', 'summary', 'description', 'note', 'reason'];

function decodeJsonEscapes(raw) {
  return String(raw).replace(/\\(u[0-9a-fA-F]{4}|.)/g, function(match, code) {
    if (code.charAt(0) === 'u') return String.fromCharCode(parseInt(code.slice(1), 16));
    if (code === 'n') return '\n';
    if (code === 'r') return '\r';
    if (code === 't') return '\t';
    if (code === 'b') return '\b';
    if (code === 'f') return '\f';
    if (code === '"' || code === '\\' || code === '/') return code;
    return match;
  });
}

// 旧策略（保留作为兜底）：非贪婪正则 + 下一个键名/闭合符作为字段终点。
// 缺点是无法区分"字段内部未转义引号"与"字段真正的结束引号"，嵌套对象里出现
// value/evidence 含引号时会把字段提前截断（见 docs/PARSE_GRAPH.md F1）。
function repairFreetextFieldsLegacy(raw) {
  var source = toText(raw);
  if (!source) return source;
  var pattern = new RegExp('("(?:' + JSON_FREETEXT_KEYS.join('|') + ')"\\s*:\\s*")([\\s\\S]*?)("(?:\\s*,\\s*"[A-Za-z_][A-Za-z0-9_]*"\\s*:|\\s*[}\\]]))', 'g');
  return source.replace(pattern, function(match, head, body, tail) {
    // head 已含开引号、tail 已含闭引号，这里只取 JSON.stringify 的转义内容
    return head + JSON.stringify(decodeJsonEscapes(body)).slice(1, -1) + tail;
  });
}

// 判断从某个下标起、跳过空白后是否是一个合法的 JSON 结构后继（, "key": / } / ]）。
function looksLikeJsonContinuation(text, index) {
  var i = index;
  while (i < text.length && /\s/.test(text[i])) i++;
  if (i >= text.length) return false;
  var ch = text[i];
  if (ch === '}' || ch === ']') return true;
  if (ch !== ',') return false;
  i++;
  while (i < text.length && /\s/.test(text[i])) i++;
  if (text[i] === '"') {
    // ,"key" 的形式
    var j = i + 1;
    while (j < text.length && text[j] !== '"') {
      if (text[j] === '\\') j++;
      j++;
    }
    j++;
    while (j < text.length && /\s/.test(text[j])) j++;
    return text[j] === ':';
  }
  // ,{ 或 ,[ （数组元素为对象）
  return text[i] === '{' || text[i] === '[';
}

// 新策略（引号配对感知）：对每个自由文本字段，从其开引号起逐字符扫描；遇到未转义的
// 引号时，只有"其后是合法 JSON 后继"才认定为真正的结束引号，否则视为值内部的散引号，
// 一并转义。为避免 "…"x" } tail" 里值内部的引号因后跟 } 被误判为结束，附加消歧：
// 若该引号是 }/] 型后继，且其后仍存在别的候选结束引号，则跳过后面的更优候选（真正的
// 结束引号通常是本字段内最靠后的、结构自洽的那个）。
function repairFreetextFieldsByScan(raw) {
  var source = toText(raw);
  if (!source) return source;
  var keyPattern = new RegExp('"(' + JSON_FREETEXT_KEYS.join('|') + ')"\\s*:', 'g');
  var out = '';
  var cursor = 0;
  var match;
  var guard = 0;
  while ((match = keyPattern.exec(source)) !== null && guard++ < 200) {
    var keyEnd = match.index + match[0].length;
    var i = keyEnd;
    while (i < source.length && /\s/.test(source[i])) i++;
    if (source[i] !== '"') continue;
    var openQuote = i;
    var body = '';
    var escaped = false;
    var j = openQuote + 1;
    var closed = false;
    while (j < source.length) {
      var ch = source[j];
      if (escaped) { body += ch; escaped = false; j++; continue; }
      if (ch === '\\') { body += ch; escaped = true; j++; continue; }
      if (ch === '"' && looksLikeJsonContinuation(source, j + 1)) {
        // 消歧：若是 }/] 型后继且后面还有候选，则跳过后面的候选
        var isCloseType = false;
        var peek = j + 1;
        while (peek < source.length && /\s/.test(source[peek])) peek++;
        if (source[peek] === '}' || source[peek] === ']') isCloseType = true;
        if (isCloseType && hasLaterCandidate(source, j + 1)) {
          body += ch; j++; continue;
        }
        closed = true;
        break;
      }
      body += ch;
      j++;
    }
    if (!closed) continue;
    out += source.slice(cursor, openQuote + 1);
    out += JSON.stringify(decodeJsonEscapes(body)).slice(1, -1);
    out += '"';
    cursor = j + 1;
    keyPattern.lastIndex = cursor;
  }
  out += source.slice(cursor);
  return out;
}

// 在 fromIndex 之后是否还存在"疑似结束引号"（其后为合法 JSON 后继）的候选。
function hasLaterCandidate(source, fromIndex) {
  var escaped = false;
  for (var j = fromIndex; j < source.length; j++) {
    var ch = source[j];
    if (escaped) { escaped = false; continue; }
    if (ch === '\\') { escaped = true; continue; }
    if (ch === '"' && looksLikeJsonContinuation(source, j + 1)) return true;
  }
  return false;
}

// 数组型字段（如 quickReplies）的单条字符串修复：对 "field": [ "..",".." ] 里的每个
// 字符串项用同样的"引号配对感知"重转义，处理数组项自身含未转义引号的情况。
const JSON_STRING_ARRAY_KEYS = ['quickReplies'];
function repairStringArrayField(raw, key) {
  var source = toText(raw);
  var keyMatch = source.match(new RegExp('"' + key + '"\\s*:\\s*\\['));
  if (!keyMatch) return source;
  var start = source.indexOf('[', keyMatch.index + keyMatch[0].length - 1);
  // 找到与之配对的 ']'（跳过字符串内部）
  var i = start + 1;
  var depth = 1;
  var inStr = false;
  var escaped = false;
  var end = -1;
  while (i < source.length) {
    var ch = source[i];
    if (inStr) {
      if (escaped) { escaped = false; }
      else if (ch === '\\') { escaped = true; }
      else if (ch === '"') { inStr = false; }
    } else {
      if (ch === '"') inStr = true;
      else if (ch === '[') depth++;
      else if (ch === ']') { depth--; if (depth === 0) { end = i; break; } }
    }
    i++;
  }
  if (end === -1) return source;
  var inner = source.slice(start + 1, end);
  // 按顶层逗号切分数组项，逐个把含散引号的裸串修正
  var parts = [];
  var buf = '';
  var inS = false, esc = false, dep = 0;
  for (var k = 0; k < inner.length; k++) {
    var c = inner[k];
    buf += c;
    if (inS) {
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inS = false;
      continue;
    }
    if (c === '"') { inS = true; continue; }
    if (c === '[' || c === '{') dep++;
    else if (c === ']' || c === '}') dep--;
    else if (c === ',' && dep === 0) { parts.push(buf.slice(0, -1)); buf = ''; }
  }
  if (buf.trim()) parts.push(buf);
  var fixedParts = parts.map(function(part) {
    var t = part.trim();
    if (t.charAt(0) !== '"') return part;
    // 提取裸串体：去掉首引号，逐字符找真正的结束（其后为 , 或 ] 或空）
    var body = '';
    var es = false;
    var idx = 1;
    var closed = false;
    while (idx < t.length) {
      var cc = t[idx];
      if (es) { body += cc; es = false; idx++; continue; }
      if (cc === '\\') { body += cc; es = true; idx++; continue; }
      if (cc === '"') {
        var rest = t.slice(idx + 1).trim();
        if (rest === '' || rest.charAt(0) === ',') { closed = true; break; }
        body += cc; idx++; continue;
      }
      body += cc; idx++;
    }
    if (!closed) return part;
    return '"' + JSON.stringify(decodeJsonEscapes(body)).slice(1, -1) + '"';
  });
  return source.slice(0, start + 1) + fixedParts.join(',') + source.slice(end);
}

// 定向修复：先试用更安全的扫描式修复；能整段解析就用它，否则退回旧策略（保持既有能力为下限）。
function repairFreetextFields(raw) {
  var source = toText(raw);
  if (!source) return source;
  var scans = [];
  JSON_STRING_ARRAY_KEYS.forEach(function(key) {
    try { scans.push(repairStringArrayField(source, key)); } catch (e) { /* 忽略 */ }
  });
  try { scans.push(repairFreetextFieldsByScan(source)); } catch (scanError) { /* 忽略 */ }
  var attempts = scans.slice();
  try { attempts.push(repairFreetextFieldsLegacy(source)); } catch (legacyError) { /* 忽略 */ }
  // 组合：先对数组项修复，再做字段扫描
  try {
    var combined = source;
    JSON_STRING_ARRAY_KEYS.forEach(function(key) { combined = repairStringArrayField(combined, key); });
    attempts.push(repairFreetextFieldsByScan(combined));
  } catch (comboError) { /* 忽略 */ }
  for (var i = 0; i < attempts.length; i++) {
    var candidate = attempts[i];
    if (!candidate) continue;
    try { JSON.parse(candidate); return candidate; } catch (strictError) { /* 试去尾逗号 */ }
    try { JSON.parse(candidate.replace(/,\s*([}\]])/g, '$1')); return candidate; } catch (looseError) { /* 下一个 */ }
  }
  // 都无法整段解析时，返回扫描式结果（通常仍能修复，交由 parseJsonPayload 的多级兜底）
  return attempts[0] || source;
}

function parseJsonPayload(text) {
  var cleaned = toText(text).trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
  if (!cleaned) throw new Error('JSON内容为空');
  var safeJson = function(value) {
    var result = '';
    var inString = false;
    var escaped = false;
    for (var i = 0; i < value.length; i++) {
      var ch = value.charAt(i);
      if (inString) {
        if (escaped) { escaped = false; result += ch; continue; }
        if (ch === '\\') { escaped = true; result += ch; continue; }
        if (ch === '"') { inString = false; result += ch; continue; }
        var code = ch.charCodeAt(0);
        if (code < 0x20) {
          result += '\\u' + ('0000' + code.toString(16)).slice(-4);
          continue;
        }
        result += ch;
      } else {
        if (ch === '"') inString = true;
        result += ch;
      }
    }
    return result;
  };
  try {
    return JSON.parse(cleaned);
  } catch (firstError) {
    try {
      return JSON.parse(cleaned.replace(/,\s*([}\]])/g, '$1')); // 尾随逗号清理
    } catch (trailingCommaError) { /* 继续尝试其他兜底 */ }
    var repairedAttempts = [];
    try { repairedAttempts.push(repairFreetextFields(cleaned)); } catch (repairError) { /* 忽略 */ }
    var shapeStart = cleaned.indexOf('{');
    var shapeEnd = cleaned.lastIndexOf('}');
    if (shapeStart !== -1 && shapeEnd > shapeStart) {
      try { repairedAttempts.push(repairFreetextFields(cleaned.slice(shapeStart, shapeEnd + 1))); } catch (shapeError) { /* 忽略 */ }
    }
    for (var repairedIndex = 0; repairedIndex < repairedAttempts.length; repairedIndex++) {
      var repairedText = repairedAttempts[repairedIndex];
      if (!repairedText) continue;
      try {
        return JSON.parse(repairedText);
      } catch (strictRepairedError) {
        try {
          return JSON.parse(repairedText.replace(/,\s*([}\]])/g, '$1'));
        } catch (looseRepairedError) { /* 继续尝试其他兜底 */ }
      }
    }
    var pairs = [['{', '}'], ['[', ']']];
    for (var i = 0; i < pairs.length; i++) {
      var start = cleaned.indexOf(pairs[i][0]);
      var end = cleaned.lastIndexOf(pairs[i][1]);
      if (start !== -1 && end > start) {
        try {
          return JSON.parse(safeJson(cleaned.slice(start, end + 1)).replace(/,\s*([}\]])/g, '$1'));
        } catch (e) { /* try the other JSON shape */ }
      }
    }
    try {
      return JSON.parse(safeJson(cleaned).replace(/,\s*([}\]])/g, '$1'));
    } catch (finalError) { /* give up */ }
    throw firstError;
  }
}

function getTimeContext(value) {
  var date = value instanceof Date ? value : new Date(value || Date.now());
  if (isNaN(date.getTime())) date = new Date();
  var resolved = Intl.DateTimeFormat().resolvedOptions();
  var timeZone = resolved.timeZone || '本地时区';
  var local = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'long',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
    timeZone: timeZone
  }).format(date);
  var offsetMinutes = -date.getTimezoneOffset();
  var sign = offsetMinutes >= 0 ? '+' : '-';
  var absoluteOffset = Math.abs(offsetMinutes);
  var offset = 'UTC' + sign + String(Math.floor(absoluteOffset / 60)).padStart(2, '0') + ':' + String(absoluteOffset % 60).padStart(2, '0');
  return { local: local, iso: date.toISOString(), timeZone: timeZone, offset: offset };
}

function sanitizeShortTermLine(line, now) {
  return parseRelativeText(line, now || new Date());
}

function formatContextTime(timestamp) {
  var context = getTimeContext(timestamp);
  return context.local + ' (' + context.timeZone + ', ' + context.offset + ')';
}

function appendWithinLimit(lines, line, limit) {
  var used = lines.join('\n').length;
  if (used >= limit) return false;
  lines.push(trimText(line, Math.max(0, limit - used)));
  return true;
}

// 主体标签：把记忆条目的主体(S/O)映射为角色视角的说法
function actorLabel(char, subject) {
  var s = toText(subject);
  if (s === 'user') return normalizeUserAddress(char && char.basicInfo && char.basicInfo.userAddress) || '对方';
  if (s === 'character') return toText(char && char.basicInfo && char.basicInfo.name) || '角色本人';
  if (s === 'relationship') return '你们';
  if (s === 'world') return '背景';
  return '相关记忆';
}

// 绝不把"用户"发给模型：统一替换为角色对用户的称呼（空则"对方"）
function maskUserWord(char, text) {
  var raw = toText(text);
  if (!raw || raw.indexOf('用户') === -1) return raw;
  var address = normalizeUserAddress(char && char.basicInfo && char.basicInfo.userAddress) || '对方';
  var replacement = address.replace(/用户/g, '') || '对方';
  return raw.split('用户').join(replacement);
}

// 短期记忆的参与人：把"用户"换成称呼
function formatParticipants(char, participants) {
  return (Array.isArray(participants) ? participants : []).map(function(name) {
    return maskUserWord(char, trimText(name, 40));
  }).filter(Boolean).join('、');
}

function getSearchTerms(text) {
  var normalized = toText(text).toLowerCase();
  var terms = normalized.match(/[a-z0-9_]{2,}|[\u4e00-\u9fff]{2,}/g) || [];
  var expanded = [];
  terms.forEach(function(term) {
    expanded.push(term);
    if (/^[\u4e00-\u9fff]+$/.test(term) && term.length > 2) {
      for (var i = 0; i < term.length - 1; i++) expanded.push(term.slice(i, i + 2));
    }
  });
  var stop = ['不知道', '知道', '不知', '什么', '怎么', '这个', '那个', '自己', '你的', '我的',
    '不是', '是的', '一次', '次了', '之前', '现在', '时候', '刚才', '谢谢', '好的', '用户', '角色',
    'know', 'dont', 'what', 'this', 'that', 'please', 'your', 'with'];
  return Array.from(new Set(expanded)).filter(function(term) { return stop.indexOf(term) === -1; }).slice(0, 80);
}

function buildMemoryQuery(char, query) {
  var text = toText(query).trim();
  if (!text || !/(填过|记错|记反|不是|不止|那件|那个|之前|刚才|it\b|that\b)/i.test(text)) return text;
  var messages = (char.memory.instant || []).filter(function(message) { return !message.isLoading && !message.internalOnly; });
  if (messages.length && messages[messages.length - 1].role === 'user' && messages[messages.length - 1].content === text) messages.pop();
  var context = messages.slice(-2).map(function(message) {
    return trimText(toText(message.content).replace(/[（(][^）)]*[）)]/g, ''), 600);
  }).join('\n');
  return text + '\n' + context;
}
