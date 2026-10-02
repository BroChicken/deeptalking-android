function buildSubmitResponseTool() {
  var dynField = buildDynamicStateFieldSchema();
  var dynFieldProperties = {};
  DYNAMIC_STATE_FIELDS.forEach(function(field) { dynFieldProperties[field.key] = dynField; });
  var staticFieldProperties = {};
  Object.keys(STATIC_PROFILE_FIELDS).forEach(function(key) { staticFieldProperties[key] = dynField; });
  return {
    type: 'function',
    name: 'submit_response',
    description: '结束本轮回复：提交用户可见的回复正文与全部记忆更新。每轮对话结束时必须且只能调用一次此工具（推荐用它收尾，而不是在正文里手写 JSON）；回复正文写入 reply 字段。',
    strict: true,
    parameters: {
      type: 'object',
      additionalProperties: false,
      properties: {
        reply: { type: 'string', description: '用户可见的回复正文，支持Markdown；按语义可自然分段；动作、表情、心理活动用括号穿插在语句之间、与语句交替推进（平均每1–2句一次，不要只在开头或结尾集中出现），动作总长不超过回复一半；口吻一律以角色设定的“说话风格”为准' },
        quickReplies: { type: 'array', description: '恰好两条"用户下一句"的短句：用户视角，是用户可以原样发给角色的话（其中"我"只能指用户）。不得是角色的台词、角色的表态或角色对用户的提问（如"你今天怎么了？""要不要早点休息？"），也不得复述角色刚说过的话。写前先把自己换成用户。', minItems: 2, maxItems: 2, items: { type: 'string' } },
        shortTerm: { type: 'array', description: '本轮事件流程摘要（对后续几轮有帮助的信息）；content必须写明谁做了什么并写绝对日期，不得写相对时间词；用户用相对时间表述时填timeRef', items: { type: 'object', additionalProperties: false, properties: { content: { type: 'string' }, sourceMessageIds: { type: 'array', items: { type: 'string' } }, timeRef: buildTimeRefSchema() }, required: ['content'] } },
        longTerm: { type: 'array', description: '未来仍有价值的稳定事实；category 只能是 userProfile|relationship|events|promises|habits；evidence 逐字摘录用户原话；key与value必须写明主体（用户写“用户”，角色写角色名）并写绝对日期，不得写“明晚/上周”这类相对时间词（相对时间改用timeRef）；约定必须填promisor与promisee；群组对话中若该事实只属于某位成员（只有那位成员知道/记得），填 memberName 记入其私人记忆，否则记入群组共享记忆', items: { type: 'object', additionalProperties: false, properties: { category: { type: 'string', enum: ['userProfile', 'relationship', 'events', 'promises', 'habits'] }, subject: { type: 'string', enum: ['user', 'relationship', 'world'] }, key: { type: 'string' }, value: { type: 'string' }, tags: { type: 'array', items: { type: 'string' } }, importance: { type: 'integer', minimum: 1, maximum: 10 }, sourceMessageIds: { type: 'array', items: { type: 'string' } }, evidence: { type: 'string' }, eventTime: { type: 'string' }, dueAt: { type: 'string', description: '可选，约定的截止时间（ISO格式）' }, promisor: { type: 'string', description: '可选但约定必填，承诺方：user（用户）/character（角色自己）/relationship（双方）/群组成员名' }, promisee: { type: 'string', description: '可选但约定必填，受约方：user（用户）/character（角色自己）/relationship（双方）/群组成员名' }, timeRef: buildTimeRefSchema(), memberName: { type: 'string', description: '可选，群组对话中指定记入某位成员的私人记忆（不传则记入群组共享记忆）' } }, required: ['category', 'key', 'value'] } },
        dynamicState: { type: 'object', additionalProperties: false, description: '角色当前动态状态，仅在有明确依据时更新', properties: dynFieldProperties },
        staticFields: { type: 'object', additionalProperties: false, description: '谨慎修改的基础设定字段（性别/年龄/种族/外貌特征/性格特征/价值观/恐惧弱点/背景故事/关键过往/说话风格/语言方言/对用户的称呼）。用户明确要求修改时必须更新；无用户要求时仅在有决定性剧情依据时更新。value以段落式的陈述句书写（自然完整的陈述句），禁止括号、理由或解释性文字', properties: staticFieldProperties },
        memberDynamicState: { type: 'array', description: '群组成员动态状态（仅群组对话使用）', items: { type: 'object', additionalProperties: false, properties: { memberName: { type: 'string' }, dynamicState: { type: 'object', additionalProperties: false, properties: dynFieldProperties } }, required: ['memberName'] } },
        promiseUpdates: { type: 'array', description: '用户明确完成或取消的承诺', items: { type: 'object', additionalProperties: false, properties: { promiseId: { type: 'string' }, status: { type: 'string', enum: ['resolved', 'cancelled'] }, sourceMessageIds: { type: 'array', items: { type: 'string' } }, evidence: { type: 'string' } }, required: ['promiseId', 'status'] } },
        recall: { type: 'object', additionalProperties: false, description: '主动召回记忆的请求', properties: { category: { type: 'string' }, tags: { type: 'array', items: { type: 'string' } } } }
      },
      required: ['reply', 'quickReplies']
    }
  };
}

function extractSubmitResponse(fullResponse) {
  if (!fullResponse || !Array.isArray(fullResponse.output)) return null;
  for (var i = 0; i < fullResponse.output.length; i++) {
    var item = fullResponse.output[i];
    if (item && item.type === 'function_call' && toText(item.name) === 'submit_response') {
      var argsText = toText(item.arguments);
      var args = null;
      try { args = parseJsonPayload(argsText); } catch (e) { args = null; }
      if (isPlainObject(args) && typeof args.reply === 'string' && args.reply) {
        // 协议层守卫：quickReplies 必须恰好两条非空短句。缺失/非法时判为未通过，
        // 交由调用方重试一次（避免 v1.1.2 起模型省略该字段后静默退化为兜底）。
        // 同时带上 reply，保证重试前正文不会丢。
        var normalizedQuickReplies = parseQuickReplyList(args.quickReplies);
        if (normalizedQuickReplies.length >= 2) {
          return { ok: true, obj: args, reply: args.reply, rawArgs: argsText, quickReplies: normalizedQuickReplies };
        }
        // 保留已解析出的其余记忆字段（obj），调用方据此仍能落盘，不必丢掉这一轮的记忆
        return { ok: false, obj: args, rawArgs: argsText, reply: args.reply, reason: 'missing_quick_replies' };
      }
      return { ok: false, rawArgs: argsText };
    }
  }
  return null;
}

function buildResponsesRequestBody(messages, stream, toolState, phase, config) {
  config = config || state.config;
  var body = {
    model: config.modelName,
    input: [],
    stream: Boolean(stream),
    temperature: normalizeTemperature(config.temperature)
  };
  body.max_output_tokens = 8192;
  messages.forEach(function(message, index) {
    if (index === 0 && message.role === 'system') {
      body.instructions = toText(message.content);
    } else {
      body.input.push({ role: message.role === 'system' ? 'system' : message.role, content: Array.isArray(message.content) ? message.content : toText(message.content) });
    }
  });
  if (toolState && Array.isArray(toolState.items)) {
    toolState.items.forEach(function(item) { body.input.push(item); });
  }
  var submitPhase = phase === 'submit';
  // 阶段二（提交回复）：必须强制锁定 submit_response，且 DeepSeek 仅支持 effort=none 时锁定
  body.reasoning = { effort: submitPhase ? 'none' : config.reasoningEffort };
  if (submitPhase) {
    // 阶段二只发 submit_response 一个工具：让模型别无选择，必须产出完整字段（含 quickReplies）。
    // 历史教训（见 docs/PARSE_GRAPH.md）：v1.1.2 曾为命中前缀缓存而改成"全量工具 + 锁定 tool_choice"，
    // 结果海量工具描述弱化了 schema strict 约束，模型倾向只输出 {reply}、省略 quickReplies，
    // 快速回应因此长期退化为兜底「嗯/继续」。工具能力在阶段一（auto）已提供，阶段二是收尾，不需要再调工具。
    body.tools = [buildSubmitResponseTool()];
    body.tool_choice = { type: 'function', name: 'submit_response' };
  } else {
    body.tools = buildAgentTools(toolState && toolState.char);
    body.tool_choice = 'auto';
  }
  return body;
}

function extractResponsesText(response) {
  if (!response) return '';
  if (typeof response.output_text === 'string' && response.output_text) return response.output_text;
  if (Array.isArray(response.output)) {
    var parts = response.output.filter(function(item) { return item && item.type === 'message'; }).map(function(item) {
      return (item.content || []).filter(function(part) { return part && part.type === 'output_text'; }).map(function(part) {
        return toText(part.text);
      }).join('');
    }).join('');
    if (parts) return parts;
  }
  return '';
}

function extractAnyResponseText(response) {
  if (!response) return '';
  var chunks = [];
  function collectTextParts(node) {
    if (!node) return;
    if (Array.isArray(node)) { node.forEach(collectTextParts); return; }
    if (typeof node !== 'object') return;
    if (node.type === 'reasoning') return;
    if (node.type === 'output_text' && typeof node.text === 'string' && node.text.trim()) {
      chunks.push(node.text);
      return;
    }
    if (Array.isArray(node.content)) node.content.forEach(collectTextParts);
  }
  collectTextParts(response && (response.output || response));
  var combined = chunks.join('\n').trim();
  if (!combined && response && typeof response.output_text === 'string' && response.output_text.trim()) return response.output_text;
  return combined;
}

function extractReplyJsonFromAnyOutput(response) {
  if (!response || !Array.isArray(response.output)) return '';
  var candidates = [];
  response.output.forEach(function(item) {
    var texts = [];
    if (item && item.type === 'reasoning') {
      (item.content || []).forEach(function(p) { if (p && typeof p.text === 'string') texts.push(p.text); });
    }
    texts.forEach(function(txt) {
      var idx = txt.indexOf('"reply"');
      if (idx === -1) return;
      var start = txt.lastIndexOf('{', idx);
      if (start === -1) return;
      var candidate = txt.slice(start);
      try {
        var parsed = parseJsonPayload(candidate);
        if (isPlainObject(parsed) && typeof parsed.reply === 'string' && parsed.reply.trim()) {
          candidates.push(candidate);
        }
      } catch (e) { /* not valid */ }
    });
  });
  return candidates.length ? candidates[0] : '';
}

function stripPendingEscape(value) {
  var end = value.length;
  while (end > 0 && value[end - 1] === '\\') end--;
  return (value.length - end) % 2 === 1 ? value.slice(0, -1) : value;
}

function parsePartialReply(partial) {
  partial = stripPendingEscape(partial);
  var candidate = partial;
  for (var attempts = 0; attempts < 12; attempts++) {
    try {
      return JSON.parse(candidate + '"');
    } catch (e) {
      if (candidate.length <= 1) return null;
      candidate = candidate.slice(0, -1);
    }
  }
  return null;
}

function extractReplyFromJson(raw) {
  if (!raw) return null;
  var match = String(raw).match(/"reply"\s*:/);
  if (!match || match.index === undefined) return null;
  var i = match.index + match[0].length;
  while (i < raw.length && /\s/.test(raw[i])) i++;
  if (i >= raw.length || raw[i] !== '"') return null;
  var j = i + 1;
  var backslashes = 0;
  while (j < raw.length) {
    var ch = raw[j];
    if (ch === '\\') { backslashes++; j++; continue; }
    if (ch === '"') {
      if (backslashes % 2 === 0) {
        try {
          return JSON.parse(raw.slice(i, j + 1));
        } catch (e) {
          // 引号/换行未转义时用同一套定向修复再取一次 reply
          try {
            var repairedReplyMatch = repairFreetextFields(raw).match(/"reply"\s*:\s*("(?:[^"\\]|\\.)*")/);
            if (repairedReplyMatch) return JSON.parse(repairedReplyMatch[1]);
          } catch (repairError) { /* 忽略 */ }
          return null;
        }
      }
      backslashes = 0;
      j++;
      continue;
    }
    backslashes = 0;
    j++;
  }
  return parsePartialReply(raw.slice(i));
}

function parseStructuredResponse(outputText) {
  var parsed = null;
  try {
    parsed = parseJsonPayload(outputText);
  } catch (e) {
    parsed = null;
  }
  if (isPlainObject(parsed) && typeof parsed.reply === 'string') {
    // hasQuickReplies：供主循环判断是否可直接采用（缺 quickReplies 时不应 break，需转阶段二补齐）
    var hasQuickReplies = parseQuickReplyList(parsed.quickReplies).length >= 2;
    return { ok: true, obj: parsed, reply: parsed.reply, hasQuickReplies: hasQuickReplies };
  }
  return { ok: false, hasQuickReplies: false };
}

function extractStreamText(event) {
  if (event && event.type === 'error') {
    throw new Error(event.error && event.error.message ? event.error.message : '流式响应错误');
  }
  return event && event.choices && event.choices[0] && event.choices[0].delta ? toText(event.choices[0].delta.content) : '';
}

var requestPrefixSnapshots = new Map();

function hashRequestPart(value) {
  var text = typeof value === 'string' ? value : JSON.stringify(value);
  var hash = 2166136261;
  for (var i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619);
  return (hash >>> 0).toString(16);
}

function startRequestTrace(body, metadata) {
  metadata = metadata || {};
  var characterId = metadata.char && metadata.char.id || null;
  var taskType = metadata.taskType || 'chat';
  var inputs = (body.input || []).filter(function(item) {
    return !(typeof item.content === 'string' && item.content.indexOf('【系统提供的本轮上下文') === 0);
  }).map(function(item) { return JSON.stringify(item); });
  var snapshot = { instructionsHash: hashRequestPart(body.instructions || ''), toolsHash: hashRequestPart(body.tools || []), inputs: inputs };
  var key = [characterId, taskType, body.model, metadata.platform || '', metadata.phase || ''].join('|');
  var previous = requestPrefixSnapshots.get(key);
  var common = 0;
  if (previous) while (common < Math.min(previous.inputs.length, inputs.length) && previous.inputs[common] === inputs[common]) common++;
  var change = !previous ? 'first-request' : previous.instructionsHash !== snapshot.instructionsHash ? 'instructions-changed'
    : previous.toolsHash !== snapshot.toolsHash ? 'tools-changed' : common < previous.inputs.length ? 'history-rebased' : 'history-extended';
  requestPrefixSnapshots.delete(key);
  requestPrefixSnapshots.set(key, snapshot);
  while (requestPrefixSnapshots.size > API_LIMITS.prefixSnapshots) requestPrefixSnapshots.delete(requestPrefixSnapshots.keys().next().value);
  var context = (body.input || []).find(function(item) { return typeof item.content === 'string' && item.content.indexOf('【系统提供的本轮上下文') === 0; });
  return { char: metadata.char, taskType: taskType, model: body.model, platform: metadata.platform || '',
    phase: metadata.phase || '', startedAt: Date.now(), status: 'completed',
    instructionsHash: snapshot.instructionsHash, toolsHash: snapshot.toolsHash,
    historyHash: hashRequestPart(inputs), commonHistoryMessages: common, prefixChange: change,
    contextChars: context ? context.content.length : 0, inputChars: JSON.stringify(body.input || []).length };
}

function recordCacheUsage(usage, metadata) {
  metadata = metadata || {};
  if (!usage || typeof usage !== 'object') {
    if (metadata.status !== 'failed') return;
    usage = {};
  }
  var input = typeof usage.input_tokens === 'number' ? usage.input_tokens
    : (typeof usage.prompt_tokens === 'number' ? usage.prompt_tokens : null);
  var output = typeof usage.output_tokens === 'number' ? usage.output_tokens : null;
  var hit = null;
  var miss = null;
  if (typeof usage.prompt_cache_hit_tokens === 'number' && typeof usage.prompt_cache_miss_tokens === 'number') {
    hit = Math.max(0, usage.prompt_cache_hit_tokens);
    miss = Math.max(0, usage.prompt_cache_miss_tokens);
  } else if (input !== null && typeof (usage.input_tokens_details || {}).cached_tokens === 'number') {
    hit = Math.max(0, usage.input_tokens_details.cached_tokens);
    miss = Math.max(0, input - hit);
  }
  var record = { at: new Date().toISOString(), characterId: metadata.char && metadata.char.id || null,
    taskType: toText(metadata.taskType, 'chat'), model: metadata.model || state.config.modelName,
    platform: metadata.platform || state.config.apiPlatform, phase: metadata.phase || '', status: metadata.status || 'completed',
    durationMs: metadata.startedAt == null ? null : Math.max(0, Date.now() - metadata.startedAt),
    instructionsHash: metadata.instructionsHash || null, toolsHash: metadata.toolsHash || null,
    historyHash: metadata.historyHash || null, prefixChange: metadata.prefixChange || null,
    commonHistoryMessages: metadata.commonHistoryMessages == null ? null : metadata.commonHistoryMessages,
    contextChars: metadata.contextChars == null ? null : metadata.contextChars,
    inputChars: metadata.inputChars == null ? null : metadata.inputChars,
    inputTokens: input === null ? null : Math.floor(input), outputTokens: output === null ? null : Math.floor(output),
    hitTokens: hit === null ? null : Math.floor(hit), missTokens: miss === null ? null : Math.floor(miss),
    hitRate: hit === null || input === null || input === 0 ? null : hit / input };
  if (!Array.isArray(state.config.requestMetrics)) state.config.requestMetrics = [];
  state.config.requestMetrics.push(record);
  state.config.requestMetrics = state.config.requestMetrics.slice(-API_LIMITS.requestMetrics);
  if (record.taskType === 'chat' && record.status !== 'failed') {
    state.config.cacheStats = { hitTokens: record.hitTokens, missTokens: record.missTokens,
      promptTokens: record.inputTokens, updatedAt: record.at };
    renderCacheStats();
  }
  scheduleSave();
}

function invalidateQuickReplies() {
  state.quickReplies = [];
  state.quickReplyCharacterId = null;
  state.quickReplyMessageId = null;
}

function parseQuickReplyList(content) {
  if (isPlainObject(content)) {
    content = content.quickReplies || content.replies || content.suggestions;
  } else if (!Array.isArray(content)) {
    try {
      content = parseJsonPayload(content);
    } catch (e) {
      return [];
    }
  }
  var values = Array.isArray(content) ? content : (content && (content.quickReplies || content.replies || content.suggestions));
  if (!Array.isArray(values)) return [];
  var seen = new Set();
  return values.map(function(item) {
    return isPlainObject(item) ? (item.text || item.content || item.reply) : item;
  }).map(function(item) {
    return trimText(item, 80).replace(/\s+/g, ' ').trim();
  }).filter(function(item) {
    var key = item.toLowerCase();
    if (!item || seen.has(key)) return false;
    seen.add(key);
    return true;
  }).slice(0, 2);
}

function parseQuickRepliesFromText(text) {
  var match = toText(text).match(/["']?quickReplies["']?\s*[:=]\s*(\[[\s\S]*?\])/i);
  if (!match) return [];
  var array;
  try {
    array = parseJsonPayload(match[1]);
  } catch (e) {
    return [];
  }
  if (!Array.isArray(array)) return [];
  var seen = new Set();
  return array.map(function(item) {
    return isPlainObject(item) ? (item.text || item.content || item.reply) : item;
  }).map(function(item) {
    return trimText(item, 80).replace(/\s+/g, ' ').trim();
  }).filter(function(item) {
    var key = item.toLowerCase();
    if (!item || seen.has(key)) return false;
    seen.add(key);
    return true;
  }).slice(0, 2);
}

function runInitialMemoryMigration() {
  if (!state || !state.characters) return;
  if (!state.config || !state.config.apiKey) return;
  Object.keys(state.characters).forEach(function(charId) {
    var char = state.characters[charId];
    if (!char || !char.memory || !Array.isArray(char.memory.instant)) return;
    if (char.memory.instant.length <= MEMORY_LIMITS.instant) return;
    setActivity('正在迁移旧对话为短期记忆…');
    performMigrationExtraction(char).then(function(done) {
      if (done) saveData();
      setActivity('');
    }).catch(function(e) {
      console.error('Initial memory migration failed:', e);
      setActivity('');
    });
  });
}

async function performMigrationExtraction(char) {
  var maxRounds = 6;
  for (var round = 0; round < maxRounds; round++) {
    if (char.memory.instant.length <= MEMORY_LIMITS.instant) return true;
    var extractable = getExtractableInstantMessages(char);
    if (extractable.length === 0) return true;
    var extracted = await extractInstantToShortTerm(char, { migration: true });
    if (!extracted) return false;
    trimCharacterMemory(char);
  }
  return char.memory.instant.length <= MEMORY_LIMITS.instant;
}
