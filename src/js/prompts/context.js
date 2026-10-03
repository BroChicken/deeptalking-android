// 成员上下文：除头像类外，全部静态字段注入；staticOnly 时不注入动态状态（动态状态走 volatile 段落，保证缓存前缀稳定）
function buildMemberContext(member, staticOnly) {
  var info = (member && member.basicInfo) || {};
  var lines = ['- ' + trimText(info.name, 100) + (toText(member.roleInGroup).trim() ? '（群内定位: ' + trimText(member.roleInGroup, 180) + '）' : '')];
  Object.keys(STATIC_PROFILE_FIELDS).forEach(function(key) {
    var value = trimText(info[key], key === 'background' || key === 'keyEvents' ? 600 : 400);
    if (value) lines.push('  ' + STATIC_PROFILE_FIELDS[key].label + ': ' + maskUserWord(member, value));
  });
  if (staticOnly === true) return lines.join('\n');
  var stateText = buildDynamicStateContext(member);
  if (stateText) lines.push('  当前状态:\n' + trimText(maskUserWord(member, stateText), 700));
  return lines.join('\n');
}

function buildRoleContext(char, staticOnly) {
  if (char.entityType === 'group') {
    var group = char.groupInfo || {};
    var groupLines = [
      '群组名称: ' + trimText(group.name || char.basicInfo.name, 160),
      '群组前提: ' + maskUserWord(char, trimText(group.description || char.basicInfo.personality, 700)),
      '场景: ' + maskUserWord(char, trimText(group.scene || (staticOnly === true ? '' : (char.dynamicState && char.dynamicState.currentLocation)), 500)),
      '互动规则: ' + maskUserWord(char, trimText(group.interactionRules, 700))
    ];
    if (staticOnly !== true) {
      var groupStateText = buildDynamicStateContext(char);
      if (groupStateText) groupLines.push('群组当前状态（全员共用）:\n' + trimText(maskUserWord(char, groupStateText), 1100));
    }
    var memberTexts = (char.members || []).map(function(member) {
      return trimText(buildMemberContext(member, staticOnly === true), PROMPT_LIMITS.memberChars);
    }).filter(Boolean);
    var groupParts = [groupLines.join('\n')];
    if (memberTexts.length) groupParts.push('成员:\n' + memberTexts.join('\n'));
    return trimText(groupParts.join('\n'), PROMPT_LIMITS.roleChars);
  }
  var lines = [];
  appendWithinLimit(lines, '名称: ' + trimText(char.basicInfo.name, 100), PROMPT_LIMITS.roleChars);
  Object.keys(STATIC_PROFILE_FIELDS).forEach(function(key) {
    var value = trimText(char.basicInfo[key], key === 'background' || key === 'keyEvents' ? 800 : 600);
    if (value) appendWithinLimit(lines, STATIC_PROFILE_FIELDS[key].label + ': ' + maskUserWord(char, value), PROMPT_LIMITS.roleChars);
  });
  return lines.join('\n');
}

function buildDynamicStateContext(char) {
  var lines = [];
  var fields = (char && char.entityType === 'group') ? DYNAMIC_STATE_FIELDS.filter(function(field) {
    return GROUP_SHARED_DYNAMIC_FIELDS.indexOf(field.key) !== -1;
  }) : DYNAMIC_STATE_FIELDS;
  fields.forEach(function(field) {
    var value = trimText(char.dynamicState[field.key], 700);
    if (value) {
      lines.push(field.label + ': ' + value);
    } else {
      lines.push(field.label + ': (未设置)');
    }
  });
  return lines.join('\n');
}

function formatElapsedTime(milliseconds) {
  var minutes = Math.max(0, Math.floor(milliseconds / 60000));
  if (minutes < 2) return '刚刚';
  if (minutes < 60) return minutes + '分钟';
  var hours = Math.floor(minutes / 60);
  if (hours < 48) return hours + '小时' + (minutes % 60 ? (minutes % 60) + '分钟' : '');
  var days = Math.floor(hours / 24);
  return days + '天' + (hours % 24 ? (hours % 24) + '小时' : '');
}

function getLastReplyGap(char) {
  var messages = char.memory.instant.filter(function(message) { return !message.isLoading; });
  if (messages.length < 2) return null;
  var current = messages[messages.length - 1];
  for (var i = messages.length - 2; i >= 0; i--) {
    if (messages[i].role !== 'assistant') continue;
    var previousTime = Date.parse(messages[i].timestamp);
    var currentTime = Date.parse(current.timestamp);
    if (!previousTime || !currentTime || currentTime < previousTime) return null;
    var milliseconds = currentTime - previousTime;
    return {
      milliseconds: milliseconds,
      text: formatElapsedTime(milliseconds),
      reconnect: milliseconds >= 6 * 60 * 60 * 1000
    };
  }
  return null;
}

function getPromiseContext(char, query) {
  var now = Date.now();
  var selectedIds = query == null ? null : new Set(retrieveRelevantMemories(char, query).map(function(entry) { return entry.item.id; }));
  var promises = (char.memory.longTerm.promises || []).filter(function(item) {
    if ((item.status || 'active') !== 'active') return false;
    if (!selectedIds || selectedIds.has(item.id)) return true;
    var due = Date.parse(item.dueAt || '');
    return Number.isFinite(due) && due >= now - 86400000 && due <= now + 7 * 86400000;
  }).map(function(item) {
    var dueTime = item.dueAt ? Date.parse(item.dueAt) : 0;
    return { item: item, dueTime: dueTime, overdue: dueTime > 0 && dueTime < now };
  }).sort(function(a, b) {
    if (a.overdue !== b.overdue) return a.overdue ? -1 : 1;
    if (!a.dueTime) return 1;
    if (!b.dueTime) return -1;
    return a.dueTime - b.dueTime;
  }).slice(0, 3);
  if (promises.length === 0) return '';
  var lines = promises.map(function(entry) {
    var item = entry.item;
    var due = entry.dueTime ? '，截止/约定时间：' + formatContextTime(item.dueAt) : '';
    var party = '';
    if (toText(item.promisor).trim() || toText(item.promisee).trim()) {
      party = '[' + (actorLabel(char, item.promisor) || '未指定') + '答应' + (actorLabel(char, item.promisee) || '未指定') + ']';
    }
    return '- [ID:' + item.id + '][' + (entry.overdue ? '待确认，已过期' : '进行中') + ']' + party + ' ' + maskUserWord(char, trimText(item.key, 80)) + ': ' + maskUserWord(char, trimText(item.value, 260)) + due;
  });
  return lines.join('\n');
}

function buildTopicSuggestions(char) {
  var sources = [];
  var info = char.basicInfo || {};
  Object.keys(STATIC_PROFILE_FIELDS).forEach(function(key) {
    var value = trimText(info[key], 200);
    if (value) sources.push(value);
  });
  var dyn = char.dynamicState || {};
  DYNAMIC_STATE_FIELDS.forEach(function(field) {
    var value = trimText(dyn[field.key], 200);
    if (value) sources.push(value);
  });
  var memory = char.memory && char.memory.longTerm || {};
  (memory.userProfile || []).slice(0, 4).forEach(function(item) {
    var label = maskUserWord(char, trimText(item.key, 60)) + (toText(item.value).trim() ? '：' + maskUserWord(char, trimText(item.value, 120)) : '');
    if (label) sources.push(label);
  });
  (memory.habits || []).slice(0, 3).forEach(function(item) {
    var label = maskUserWord(char, trimText(item.key, 60));
    if (label) sources.push('习惯：' + label);
  });
  (memory.events || []).slice(0, 3).forEach(function(item) {
    var label = maskUserWord(char, trimText(item.key, 60));
    if (label) sources.push('最近共同经历：' + label);
  });
  var seen = new Set();
  var result = [];
  sources.forEach(function(source) {
    if (result.length >= 5) return;
    if (seen.has(source)) return;
    seen.add(source);
    result.push(source);
  });
  return result;
}

// 冷场识别：用户表达"不知道聊什么/把话题选择权交给角色"时触发主动开话题
var COLD_FIELD_PATTERNS = [
  /不知道(说|聊)(什么|啥)/, /没(有)?话题/, /(说|聊)(点|些)?(什么|啥)/, /找(个)?话题/,
  /好无聊/, /有点无聊/, /太无聊/, /冷场/, /没意思/, /(你|你来)(说|聊|讲|谈)(吧|点|个)?/,
  /你来(说|聊|讲)/, /你(说|聊|讲)吧/, /(想|要)听你/, /什么(都)?好/, /随你/, /你决定/
];

function isColdFieldRequest(text) {
  var content = toText(text);
  return COLD_FIELD_PATTERNS.some(function(pattern) { return pattern.test(content); });
}

// 话题切换检测：当前用户消息与角色 currentGoal/currentSituation 明显不同即视为切换话题。
// 用双字 bigram 比较，避免"整句短语"匹配不到的误判。
function detectTopicSwitch(char, query) {
  var state = char.dynamicState || {};
  var focusText = (toText(state.currentGoal) + ' ' + toText(state.currentSituation)).trim();
  if (!focusText) return false;
  var lowerQuery = toText(query).toLowerCase();
  if (!lowerQuery) return false;
  function topicBigrams(text) {
    var clean = String(text).toLowerCase().replace(/[\s，。！？、,.;:：'"“”\d]|讨论|关于|正在|目前|最近|感觉|觉得|在聊|谈到/g, '');
    var grams = new Set();
    for (var i = 0; i < clean.length - 1; i++) {
      var gram = clean.substr(i, 2);
      if (/[\u4e00-\u9fa5]/.test(gram)) grams.add(gram);
    }
    return grams;
  }
  var focusGrams = topicBigrams(focusText);
  var queryGrams = topicBigrams(lowerQuery);
  if (focusGrams.size === 0 || queryGrams.size === 0) return false;
  var hits = 0;
  focusGrams.forEach(function(gram) { if (queryGrams.has(gram)) hits++; });
  return hits === 0;
}

function extractReplyEnding(text) {
  var clean = toText(text).replace(/\s+$/, '');
  if (!clean) return '';
  var lines = clean.split('\n');
  var line = '';
  for (var li = lines.length - 1; li >= 0; li--) {
    if (lines[li].trim()) { line = lines[li].trim(); break; }
  }
  if (!line) return '';
  var end = line.length;
  while (end > 0 && /[。！？!?…"'”’）)\]】]/.test(line.charAt(end - 1))) end--;
  var prev = -1;
  for (var k = end - 1; k >= 0; k--) {
    if ('。！？!?…'.indexOf(line.charAt(k)) !== -1) { prev = k; break; }
  }
  return trimText(line.slice(prev + 1), 80);
}

function isMeaningfulEnding(ending) {
  return toText(ending).replace(/[。！？!?…\s"'”’）)\]】]/g, '').length >= 4;
}

function getRecentReplyEndings(char, limit) {
  var count = Number(limit) > 0 ? Number(limit) : 1;
  var endings = [];
  var messages = (char && char.memory && Array.isArray(char.memory.instant)) ? char.memory.instant : [];
  for (var i = messages.length - 1; i >= 0 && endings.length < count; i--) {
    var msg = messages[i];
    if (!msg || msg.role !== 'assistant' || msg.isLoading || !toText(msg.content).trim()) continue;
    var ending = extractReplyEnding(msg.content);
    if (!ending || endings.indexOf(ending) !== -1) continue;
    endings.push(ending);
  }
  return endings;
}

function getRecentReplyTexts(char, limit) {
  return ((char && char.memory && char.memory.instant) || []).filter(function(message) {
    return message.role === 'assistant' && !message.isLoading && toText(message.content).trim();
  }).slice(-(Number(limit) || 2)).map(function(message) { return message.content; });
}

// 仅当本轮收尾与上一条回复的收尾逐字完全相同时，删掉本轮重复的那一份；不做任何改写
function dedupeRepeatedEnding(text, char) {
  var current = toText(text);
  if (!current.trim()) return current;
  var endings = getRecentReplyEndings(char, 1);
  if (endings.length === 0) return current;
  var previous = endings[0];
  var currentEnding = extractReplyEnding(current);
  if (!currentEnding || currentEnding !== previous) return current;
  if (!isMeaningfulEnding(previous) || !isMeaningfulEnding(currentEnding)) return current;
  var idx = current.lastIndexOf(currentEnding);
  if (idx === -1) return current;
  var trimmed = current.slice(0, idx).replace(/\s+$/, '');
  return trimmed || current;
}

function buildNarrativePatternDirective() {
  var pattern = NARRATIVE_PATTERNS[Math.floor(Math.random() * NARRATIVE_PATTERNS.length)] || NARRATIVE_PATTERNS[0];
  return '【本轮节奏骨架（每轮随机给出，仅作节奏参考）】建议本轮按“' + pattern.label + '”的顺序组织内容：' + pattern.hint + '骨架只约束节奏与详略分布，不要写出任何结构标签或说明文字，措辞与语气一律服从角色设定。\n\n';
}
