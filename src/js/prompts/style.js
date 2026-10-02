// ==================== 语气/风格遵从（attention）====================
// 从说话风格里抽出示例台词：约定格式为「调性描述；示例：<台词1> / <台词2> / <台词3>」
function extractSpeakingSamples(style) {
  var text = toText(style);
  var idx = text.indexOf('示例');
  if (idx === -1) return [];
  var tail = text.slice(idx).replace(/^示例\s*[：:]?\s*/, '');
  return tail.split(/\s*[\/／]\s*|\n+/)
    .map(function(part) { return trimText(toText(part).replace(/[「」『』“”"]/g, '').trim(), 80); })
    .filter(function(part) { return part.length >= 2; })
    .slice(0, 3);
}

// 语气锚：把说话风格与示例台词放到生成点最近处（volatile 末尾），是遵从度最高的一条
function buildStyleAnchor(char) {
  var info = (char && char.basicInfo) || {};
  var style = trimText(toText(info.speakingStyle), STYLE_GUARD.anchorStyleChars);
  if (!style) return '';
  var samples = extractSpeakingSamples(style).slice(0, STYLE_GUARD.anchorSamples);
  var address = normalizeUserAddress(info.userAddress);
  var lines = ['【本轮语气锚（离生成最近，优先级高于一切风格偏好）】'];
  lines.push('说话风格：' + style);
  if (samples.length) {
    lines.push('照此口吻说话（只借用语气，不要照抄内容）：' + samples.map(function(sample) { return '「' + sample + '」'; }).join(' '));
  }
  if (address) lines.push('对用户的称呼：' + address);
  lines.push('本轮必须：用自己的口吻说话；不复述设定、不解释自己在怎么做；不替用户说话或行动；收尾不与上一轮雷同。');
  return lines.join('\n') + '\n\n';
}

// 客户端违规检测（纯函数）：返回命中标签数组，供下一轮内部提醒与 critique 使用
function detectStyleViolations(reply, char, prevReplies) {
  var text = toText(reply);
  var hits = [];
  if (!text.trim()) return hits;
  var info = (char && char.basicInfo) || {};
  var address = normalizeUserAddress(info.userAddress);

  if (/(好的|收到|明白|知道了)[，,。、]?\s*(我)?(会|改成|改用|调整|照做|注意|这样(说话|说))|以后我(就)?(用|按)这(种|个)(方式|语气)|我(会)?照你说的(来|说|做)/.test(text)) {
    hits.push('metaTalk');
  }
  var quotedUserLine = new RegExp('(^|\\n)\\s*(用户|' + (address ? address.replace(/[^0-9A-Za-z\u4e00-\u9fff]/g, '') + '|' : '') + '你)\\s*[：:]\\s*[「『“"]');
  if (quotedUserLine.test(text)) hits.push('speaksForUser');
  if (STYLE_CLICHES.some(function(word) { return text.indexOf(word) !== -1; })) hits.push('cliche');

  var priors = (prevReplies || []).filter(Boolean).map(function(item) { return toText(item); });
  if (priors.length) {
    var currentEnding = extractReplyEnding(text);
    if (currentEnding && priors.some(function(prior) { return extractReplyEnding(prior) === currentEnding; })) {
      hits.push('reusedImagery');
    } else if (sharesDistinctivePhrase([address, info.name].filter(Boolean).reduce(function(value, name) {
      return value.split(name).join(' ');
    }, text), priors.map(function(prior) {
      return [address, info.name].filter(Boolean).reduce(function(value, name) { return value.split(name).join(' '); }, prior);
    }))) {
      hits.push('reusedImagery');
    }
  }

  // 口癖类判据太容易误报（示例台词常共享通用词），只保留"句长过于整齐"这一条硬信号
  if (isSentenceLengthUniform(text)) hits.push('toneDrift');

  var entries = (char && Array.isArray(char.lorebook)) ? char.lorebook : [];
  if (entries.some(function(entry) {
    var name = toText(entry && entry.name).trim();
    if (!name || name.length < 2 || text.indexOf(name) === -1) return false;
    var content = toText(entry && entry.content).trim();
    return content.length >= 12 && text.indexOf(content.slice(0, 12)) !== -1;
  })) hits.push('recitedLore');

  return hits.filter(function(item, index) { return hits.indexOf(item) === index; });
}

// 句长是否过于整齐（≥5 句且极差很小）——真人感要求长短句起伏
function isSentenceLengthUniform(text) {
  var sentences = toText(text).split(/[。！？!?…]+/).map(function(part) { return part.replace(/\s+/g, ''); }).filter(function(part) { return part.length > 0; });
  if (sentences.length < 5) return false;
  var lengths = sentences.map(function(part) { return part.length; });
  var min = Math.min.apply(null, lengths);
  var max = Math.max.apply(null, lengths);
  return min >= 8 && (max - min) <= 4;
}

// 与近期回复共享 N 字以上的独特片段（比喻/意象复用）
function sharesDistinctivePhrase(text, priors) {
  var span = STYLE_GUARD.reusePhraseChars;
  var current = toText(text).replace(/\s+/g, '');
  if (current.length < span) return false;
  var priorsText = priors.map(function(prior) { return toText(prior).replace(/\s+/g, ''); });
  for (var i = 0; i + span <= current.length; i++) {
    var chunk = current.slice(i, i + span);
    if (!/[^\u4e00-\u9fff]/.test(chunk) && priorsText.some(function(prior) { return prior.indexOf(chunk) !== -1; })) return true;
  }
  return false;
}

// 上一轮回复的违规标签（内部提醒的数据来源）
function getLastStyleViolations(char) {
  var messages = (char && char.memory && Array.isArray(char.memory.instant)) ? char.memory.instant : [];
  for (var i = messages.length - 1; i >= 0; i--) {
    var message = messages[i];
    if (!message || message.isLoading || message.role !== 'assistant') continue;
    return Array.isArray(message.styleViolations) ? message.styleViolations.filter(function(key) { return STYLE_VIOLATION_LABELS[key]; }) : [];
  }
  return [];
}

// 最近 N 条 assistant 回复里出现过的违规标签（每 N 轮回顾用）
function collectRecentStyleViolations(char, limit) {
  var messages = (char && char.memory && Array.isArray(char.memory.instant)) ? char.memory.instant : [];
  var collected = [];
  for (var i = messages.length - 1; i >= 0 && collected.length < limit; i--) {
    var message = messages[i];
    if (!message || message.isLoading || message.role !== 'assistant') continue;
    collected.push(message);
  }
  var labels = [];
  collected.forEach(function(message) {
    (Array.isArray(message.styleViolations) ? message.styleViolations : []).forEach(function(key) {
      if (STYLE_VIOLATION_LABELS[key] && labels.indexOf(key) === -1) labels.push(key);
    });
  });
  return labels;
}

// 快速回应视角校验（纯函数）：只判断"它像不像用户会打的字"。
// 返回问题标签数组，空数组=可用。误报代价低（最坏情况是多跑一次后台换位生成）。
function detectQuickReplyIssues(replies, char, replyText) {
  var list = Array.isArray(replies) ? replies.map(function(item) { return toText(item).trim(); }).filter(Boolean) : [];
  var hits = [];
  if (list.length < 2) hits.push('missing');
  var spoken = toText(replyText).replace(/\s+/g, '');
  list.forEach(function(line) {
    var compact = line.replace(/\s+/g, '');
    if (QUICK_REPLY_PLACEHOLDERS.indexOf(line) !== -1 || /^短句[一二1-9]$/.test(line)) { hits.push('placeholder'); return; }
    if (/^[（(]/.test(line) || /[（(][^）)]{1,20}[）)]/.test(line)) { hits.push('action'); return; }
    if (line.length > QUICK_REPLY_GUARD.maxChars) { hits.push('tooLong'); return; }
    var charName = toText(char && char.basicInfo && char.basicInfo.name).trim();
    if (charName.length >= 2 && line.indexOf(charName) !== -1) { hits.push('characterName'); return; }
    if (compact.length >= 4 && spoken.indexOf(compact) !== -1) { hits.push('mirrored'); return; }
    if (compact.length >= QUICK_REPLY_GUARD.mirrorChars) {
      for (var i = 0; i + QUICK_REPLY_GUARD.mirrorChars <= compact.length; i += 2) {
        var chunk = compact.slice(i, i + QUICK_REPLY_GUARD.mirrorChars);
        if (!/[^\u4e00-\u9fff]/.test(chunk) && spoken.indexOf(chunk) !== -1) { hits.push('mirrored'); return; }
      }
    }
  });
  return hits.filter(function(item, index) { return hits.indexOf(item) === index; });
}

// 上一条回复的快速回应问题（内部提醒的数据来源）
function getLastQuickReplyIssues(char) {
  var messages = (char && char.memory && Array.isArray(char.memory.instant)) ? char.memory.instant : [];
  for (var i = messages.length - 1; i >= 0; i--) {
    var message = messages[i];
    if (!message || message.isLoading || message.role !== 'assistant') continue;
    return Array.isArray(message.quickReplyIssues) ? message.quickReplyIssues.filter(function(key) { return QUICK_REPLY_ISSUE_LABELS[key]; }) : [];
  }
  return [];
}

// 上一轮快速回应视角错误时的内部提醒（只进上下文，不展示给用户）
function buildQuickReplyPerspectiveReminder(char) {
  var labels = getLastQuickReplyIssues(char);
  if (labels.length === 0) return '';
  return '【上一轮快速回应（内部提醒）】' + labels.map(function(key) { return QUICK_REPLY_ISSUE_LABELS[key]; }).join('；')
    + '。快速回应必须是用户本人下一句要发给角色的话：写之前先把自己当成用户，写完再逐条默读一遍"用户：<短句>"确认；不得是角色的台词、角色的提问或角色的表态。\n\n';
}

// 换位生成用的提示词（纯函数，便于单测）：system 身份就是"用户本人"，与被扮演的角色无关
function buildQuickReplyAsUserPrompt(char, replyText) {
  var address = normalizeUserAddress(char && char.basicInfo && char.basicInfo.userAddress);
  var charName = toText(char && char.basicInfo && char.basicInfo.name) || '对方';
  var system = '你就是这位用户本人，正在手机上和「' + charName + '」聊天。只输出用户此刻最可能打出的两句话，不要扮演' + charName + '，不要写旁白或动作，不要解释。';
  var user = '「' + charName + '」刚对你说：\n' + trimText(toText(replyText), 800) + '\n\n'
    + '请写出你（用户' + (address ? '，对方平时叫你“' + address + '”' : '') + '）此刻最可能发给他的两句话：\n'
    + '①每句都是用户可以原样发送的消息，是"我"（用户自己）的立场、感受、提问或要求；\n'
    + '②不得是' + charName + '会说的话，不得是把' + charName + '刚说的话换个人称复述一遍；\n'
    + '③每条不超过 ' + QUICK_REPLY_GUARD.repairMaxChars + ' 字，不用括号动作、不用 Markdown。\n'
    + '只返回 JSON：{"quickReplies":["句子一","句子二"]}';
  return { system: system, user: user };
}

// 后台换位生成：专门扮演用户的小调用。失败返回 null（调用方保留原值/兜底）。
async function generateQuickRepliesAsUser(char, replyText, userMessage) {
  try {
    var prompt = buildQuickReplyAsUserPrompt(char, replyText);
    var recentUserLines = (char && char.memory && Array.isArray(char.memory.instant) ? char.memory.instant : [])
      .filter(function(message) { return message && !message.isLoading && message.role === 'user'; })
      .slice(-3)
      .map(function(message) { return trimText(toText(message.content), 80); })
      .filter(Boolean);
    var userContent = (recentUserLines.length > 1 ? '【用户最近说过的话，仅用于参考语气，不要照抄】\n' + recentUserLines.slice(0, -1).join('\n') + '\n\n' : '') + prompt.user;
    var response = await callAPI([
      { role: 'system', content: prompt.system },
      { role: 'user', content: userContent }
    ], { char: char, taskType: 'quick-replies' });
    var data = parseJsonPayload(response.choices[0].message.content);
    var list = parseQuickReplyList(data && data.quickReplies ? data.quickReplies : data);
    if (list.length < 2) return null;
    var issues = detectQuickReplyIssues(list, char, replyText);
    if (issues.length > 0) return null;
    return list;
  } catch (error) {
    console.error('generateQuickRepliesAsUser failed:', error);
    return null;
  }
}

// 后台任务串行队列：记忆写入与快速回应补写都不阻塞正文，但要避免互相交错写同一份状态
var backgroundTaskChain = Promise.resolve();
function queueBackgroundTask(task) {
  backgroundTaskChain = backgroundTaskChain.then(function() {
    return task();
  }).catch(function(error) {
    console.error('[DeepTalking] 后台任务失败:', error);
  });
  return backgroundTaskChain;
}

// 阶段事件计数（只进调试面板）：用来在实机上验证"正在整理回复…"是否真的不再每轮出现
function noteStageEvent(key) {
  var stats = isPlainObject(state.config.stageStats) ? state.config.stageStats : {};
  stats[key] = (Number(stats[key]) || 0) + 1;
  stats.updatedAt = new Date().toISOString();
  state.config.stageStats = stats;
}

// 每 N 轮一次的语气回顾（内部提醒；有违规就点名，没有就重申契约）
function buildStyleReview(char) {
  var assistantTurnCount = ((char && char.memory && Array.isArray(char.memory.instant)) ? char.memory.instant : [])
    .filter(function(message) { return message && !message.isLoading && message.role === 'assistant'; }).length;
  if (assistantTurnCount === 0 || assistantTurnCount % STYLE_GUARD.reviewEveryTurns !== 0) return '';
  var labels = collectRecentStyleViolations(char, STYLE_GUARD.reviewEveryTurns);
  var lines = ['【语气回顾（每 ' + STYLE_GUARD.reviewEveryTurns + ' 轮一次，内部提醒）】'];
  lines.push('语气永远以角色设定的说话风格为准，优先级高于你的默认文风与最近的写法。');
  if (labels.length) lines.push('最近几轮出现的问题：' + labels.map(function(key) { return STYLE_VIOLATION_LABELS[key]; }).join('；') + '。');
  lines.push('本轮务必：用自己的口吻、长短句有起伏、不复用近期比喻与收尾、不替用户说话。');
  return lines.join('\n') + '\n\n';
}

// 上一轮违规的内部纠正提醒（只进上下文，不展示给用户）
function buildStyleCorrectionReminder(char) {
  var labels = getLastStyleViolations(char);
  if (labels.length === 0) return '';
  return '【上一轮需要纠正（内部提醒）】' + labels.map(function(key) { return STYLE_VIOLATION_LABELS[key]; }).join('；') + '。本轮必须做到，不要重复上一轮的问题。\n\n';
}

// critique 调用（来源：talemate 的 arc-expand-critique）：一次独立的后置文风修订调用。
// 只改文风、不改剧情；解析失败或长度异常一律回退原文。
async function critiqueReplyStyle(char, reply, violationKeys) {
  var original = toText(reply);
  if (!original.trim()) return original;
  var rules = (violationKeys || []).map(function(key) { return '- ' + (STYLE_VIOLATION_LABELS[key] || key); }).join('\n');
  var prompt = '下面是一段角色扮演回复，以及它违反的文风规则清单。请修订文风。\n'
    + '硬性要求：①只改文风，绝不改动情节、事实、对话含义与人物关系；②保持大体长度与段落数；③不要新增情节要素、不要加解释或旁白；④不得复述规则本身。\n'
    + '违反的规则：\n' + rules + '\n\n'
    + '只返回 JSON：{"reply":"修订后的正文"}，换行写 \\n，双引号写 \\"。\n\n原文：\n' + original;
  try {
    var response = await callAPI([
      { role: 'system', content: '你是文风校对助手。只返回JSON。' },
      { role: 'user', content: prompt }
    ], { char: char, taskType: 'style-critique' });
    var data = parseJsonPayload(response.choices[0].message.content);
    var revised = data && typeof data.reply === 'string' ? unescapeLiteralNewlines(data.reply).trim() : '';
    if (!revised || revised === original) return original;
    if (revised.length > STYLE_GUARD.critiqueMaxChars) return original;
    if (revised.length < original.length * STYLE_GUARD.critiqueMinRatio) return original;
    if (revised.length > original.length * STYLE_GUARD.critiqueMaxRatio) return original;
    return revised;
  } catch (error) {
    console.error('critiqueReplyStyle failed:', error);
    return original;
  }
}

function buildUserMessageContent(text, images) {
  var imgs = Array.isArray(images) ? images.filter(function(uri) { return typeof uri === 'string' && uri; }) : [];
  if (imgs.length === 0) return text;
  var parts = [{ type: 'input_text', text: text }];
  imgs.forEach(function(uri) { parts.push({ type: 'input_image', image_url: uri, detail: 'auto' }); });
  return parts;
}
