async function executeToolCall(call, char, toolState) {
  var name = toText(call && call.name);
  var rawArgs = toText(call && call.arguments);
  var args = null;
  try {
    args = rawArgs ? parseJsonPayload(rawArgs) : {};
  } catch (e) {
    args = null;
  }
  if (!isPlainObject(args)) args = {};
  try {
    if (name === 'get_current_time') {
      var time = getTimeContext(new Date());
      return JSON.stringify({
        local: time.local,
        iso: time.iso,
        timeZone: time.timeZone,
        offset: time.offset
      });
    }
    if (name === 'search_memory') {
      var query = toText(args.query).trim();
      if (!query) return JSON.stringify({ ok: false, reason: '缺少检索关键词，请补充 query 参数（要回忆的关键词）后再试' });
      var searchStore = getMemberMemory(char, args.memberName) || char.memory;
      var searchHost = resolveMemoryHost(char, args.memberName);
      var entries = retrieveRelevantMemories(char, query, searchStore);
      var searchUsageNow = new Date().toISOString();
      entries.forEach(function(entry) {
        if (!entry.item) return;
        entry.item.usageCount = (Number(entry.item.usageCount) || 0) + 1;
        entry.item.lastUsageAt = searchUsageNow;
      });
      var items = entries.filter(function(entry) {
        return entry.score >= 4;
      }).map(function(entry) {
        var item = entry.item;
        return {
          id: item.id,
          category: entry.category,
          subject: item.subject || 'legacy',
          subjectLabel: actorLabel(searchHost, item.subject),
          key: maskUserWord(searchHost, trimText(item.key, 80)),
          value: maskUserWord(searchHost, trimText(item.value, 420)),
          eventTime: formatContextTime(item.eventTime || item.createdAt),
          importance: Number(item.importance) || 0,
          relevance: entry.score
        };
      }).slice(0, 6);
      return JSON.stringify({ ok: true, query: query, total: items.length, results: items });
    }
    if (name === 'list_memories') {
      var listCategory = toText(args.category);
      var keyword = toText(args.keyword).trim().toLowerCase();
      var limit = Number(args.limit);
      limit = Number.isFinite(limit) ? Math.max(1, Math.min(50, Math.floor(limit))) : 30;
      var listStore = getMemberMemory(char, args.memberName) || char.memory;
      var listHost = resolveMemoryHost(char, args.memberName);
      var listLongTerm = listStore.longTerm || {};
      var listed = [];
      Object.keys(listLongTerm).forEach(function(category) {
        if (listCategory && category !== listCategory) return;
        listLongTerm[category].forEach(function(item) {
          if (keyword) {
            var searchable = (toText(item.key) + ' ' + toText(item.value) + ' ' + (item.tags || []).join(' ')).toLowerCase();
            if (searchable.indexOf(keyword) === -1) return;
          }
          listed.push({
            id: item.id,
            category: category,
            subject: item.subject || 'legacy',
            subjectLabel: actorLabel(listHost, item.subject),
            key: maskUserWord(listHost, trimText(item.key, 100)),
            importance: Number(item.importance) || 0,
            status: item.status || 'active',
            updatedAt: formatContextTime(item.updatedAt || item.createdAt)
          });
        });
      });
      listed.sort(function(a, b) { return b.importance - a.importance; });
      listed = listed.slice(0, limit);
      return JSON.stringify({ ok: true, category: listCategory || 'all', keyword: keyword || null, count: listed.length, items: listed });
    }
    if (name === 'delete_memory') {
      var deleteId = toText(args.id);
      if (!deleteId) return JSON.stringify({ ok: false, reason: '缺少记忆ID id，请先调用 list_memories 获取目标ID后再试' });
      var deleteStore = getMemberMemory(char, args.memberName) || char.memory;
      var deleteLongTerm = deleteStore.longTerm || {};
      var deleted = false;
      Object.keys(deleteLongTerm).forEach(function(category) {
        var list = deleteLongTerm[category] || [];
        for (var di = list.length - 1; di >= 0; di--) {
          if (list[di].id === deleteId) {
            list.splice(di, 1);
            deleted = true;
          }
        }
      });
      return JSON.stringify(deleted
        ? { ok: true, deleted: true, id: deleteId }
        : { ok: false, deleted: false, id: deleteId, reason: '未找到该记忆ID，请先调用 list_memories 查询正确ID后再试' });
    }
    if (name === 'update_memory') {
      var memoryId = toText(args.id).trim();
      if (!memoryId) return JSON.stringify({ ok: false, reason: '缺少记忆ID id，请先调用 search_memory 或 list_memories 获取目标ID' });
      var memoryStore = getMemberMemory(char, args.memberName) || char.memory;
      var memoryLongTerm = memoryStore.longTerm || {};
      var targetMemory = null;
      var targetCategory = '';
      Object.keys(memoryLongTerm).forEach(function(category) {
        var hit = (memoryLongTerm[category] || []).find(function(item) { return item.id === memoryId; });
        if (hit) { targetMemory = hit; targetCategory = category; }
      });
      if (!targetMemory) return JSON.stringify({ ok: false, reason: '找不到该记忆ID（可能已被删除或合并），请重新检索后再试' });
      var memoryEvidence = trimText(args.evidence, 300);
      var memorySourceIds = Array.isArray(args.sourceMessageIds) ? args.sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean).slice(0, 8) : [];
      var memoryKnownSources = getKnownSources(char);
      if (!hasValidUserEvidence(char, memorySourceIds, memoryEvidence, memoryKnownSources)) {
        return JSON.stringify({ ok: false, reason: 'sourceMessageIds 必须是真实的用户消息ID，且 evidence 逐字摘录用户纠正的原话' });
      }
      var nextSubject = toText(args.subject).trim();
      if (nextSubject && ['user', 'relationship', 'world', 'character'].indexOf(nextSubject) === -1) {
        return JSON.stringify({ ok: false, reason: 'subject 只能是 user / relationship / world / character' });
      }
      var nextImportance = Number(args.importance);
      var nextPromisor = trimText(args.promisor, 40);
      var nextPromisee = trimText(args.promisee, 40);
      var nextResolved = resolveTimeRef(args.timeRef, new Date());
      var nextEventTime = args.eventTime ? normalizeTimestamp(args.eventTime, null) : ((nextResolved && nextResolved.iso) || null);
      var nextDueAt = args.dueAt ? normalizeTimestamp(args.dueAt, null) : ((nextResolved && nextResolved.iso) || null);
      if (targetCategory !== 'promises') nextDueAt = null;
      var nextTimeBase = nextEventTime || new Date().toISOString();
      var nextValue = trimText(parseRelativeText(args.value, nextTimeBase), 900);
      var nextKey = trimText(parseRelativeText(args.key, nextTimeBase), 100);
      if (!nextSubject && !nextValue && !nextKey && !Number.isFinite(nextImportance) && !nextPromisor && !nextPromisee && !nextEventTime && !nextDueAt) {
        return JSON.stringify({ ok: false, reason: '至少要修改 subject / value / key / importance / promisor / promisee / eventTime / dueAt 之一' });
      }
      var beforeSnapshot = { value: targetMemory.value, subject: targetMemory.subject, key: targetMemory.key, promisor: targetMemory.promisor, promisee: targetMemory.promisee, eventTime: targetMemory.eventTime, dueAt: targetMemory.dueAt };
      var changedFields = [];
      if (nextSubject) { targetMemory.subject = nextSubject; changedFields.push('subject'); }
      if (nextValue) { targetMemory.value = nextValue; changedFields.push('value'); }
      if (nextKey) { targetMemory.key = nextKey; changedFields.push('key'); }
      if (Number.isFinite(nextImportance)) { targetMemory.importance = Math.max(1, Math.min(10, Math.floor(nextImportance))); changedFields.push('importance'); }
      if (nextPromisor) { targetMemory.promisor = nextPromisor; changedFields.push('promisor'); }
      if (nextPromisee) { targetMemory.promisee = nextPromisee; changedFields.push('promisee'); }
      if (nextEventTime) { targetMemory.eventTime = nextEventTime; changedFields.push('eventTime'); }
      if (nextDueAt) { targetMemory.dueAt = nextDueAt; changedFields.push('dueAt'); }
      // 用户权威纠正：清冲突标记、旧值留痕（最多留 3 条）
      targetMemory.corrections = Array.isArray(targetMemory.corrections) ? targetMemory.corrections.slice(-2) : [];
      targetMemory.corrections.push({ at: new Date().toISOString(), evidence: memoryEvidence, before: beforeSnapshot });
      targetMemory.conflictedAt = null;
      targetMemory.conflicts = [];
      targetMemory.sourceMessageIds = Array.from(new Set((targetMemory.sourceMessageIds || []).concat(memorySourceIds))).slice(0, 8);
      targetMemory.sourceRoles = Array.from(new Set((targetMemory.sourceRoles || []).concat(['user']))).slice(0, 8);
      targetMemory.evidence = memoryEvidence;
      targetMemory.updatedAt = new Date().toISOString();
      var memoryHost = resolveMemoryHost(char, args.memberName);
      return JSON.stringify({
        ok: true,
        id: memoryId,
        category: targetCategory,
        changed: changedFields,
        subject: targetMemory.subject || 'legacy',
        subjectLabel: actorLabel(memoryHost, targetMemory.subject),
        key: maskUserWord(memoryHost, trimText(targetMemory.key, 100)),
        value: maskUserWord(memoryHost, trimText(targetMemory.value, 500))
      });
    }
    if (name === 'set_reminder') {
      var reminderKey = trimText(args.key, 100);
      var reminderValue = trimText(args.value, 900);
      if (!reminderKey || !reminderValue) return JSON.stringify({ ok: false, reason: '缺少 key 或 value' });
      // 待办=需要用户参与的事：只有用户自己明确提出/同意才能记录（复用用户提起路径）。
      // 角色要求用户做X、角色的建议叮嘱没有用户原话 → 拒绝建待办，引导模型先征询用户。
      var reminderSources = hasValidUserEvidence(char, args.sourceMessageIds, args.evidence, getKnownSources(char));
      if (!reminderSources || reminderSources.length === 0) {
        return JSON.stringify({ ok: false, reason: '只能记录用户自己明确提出或同意的待办：请附用户原话 sourceMessageIds 与 evidence；若是你要求用户去做的事，请先在回复中征询用户，等用户答应后再记录' });
      }
      var reminderNow = new Date().toISOString();
      var reminderResolved = resolveTimeRef(args.timeRef, reminderNow);
      var reminderBase = (reminderResolved && reminderResolved.iso) || reminderNow;
      var reminderItem = {
        category: 'promises',
        subject: 'user',
        key: parseRelativeText(reminderKey, reminderBase),
        value: parseRelativeText(reminderValue, reminderBase),
        tags: [],
        importance: 6,
        sourceMessageIds: reminderSources.map(function(entry) { return entry.id; }),
        sourceRoles: reminderSources.map(function(entry) { return entry.source.role; }),
        evidence: trimText(args.evidence, 300),
        eventTime: reminderNow,
        participants: [],
        location: '',
        dueAt: normalizeTimestamp(args.dueAt, null) || ((reminderResolved && reminderResolved.iso) || null),
        promisor: 'user',
        promisee: 'character',
        status: 'active',
        recordedAt: reminderNow,
        createdAt: reminderNow,
        updatedAt: reminderNow,
        lastRecalled: null,
        recallCount: 0,
        relatedTo: []
      };
      var reminderStore = getMemberMemory(char, args.memberName) || char.memory;
      if (!reminderStore.longTerm) reminderStore.longTerm = { userProfile: [], relationship: [], events: [], promises: [], habits: [] };
      if (!reminderStore.longTerm.promises) reminderStore.longTerm.promises = [];
      var promiseList = reminderStore.longTerm.promises;
      var reminderExisting = promiseList.find(function(item) { return toText(item.key).toLowerCase() === reminderKey.toLowerCase(); });
      if (reminderExisting && (reminderExisting.status || 'active') === 'active') {
        reminderExisting.value = reminderValue;
        reminderExisting.dueAt = reminderItem.dueAt || reminderExisting.dueAt;
        reminderExisting.updatedAt = reminderNow;
      } else {
        reminderItem.id = createMemoryId('mem');
        promiseList.push(reminderItem);
        reminderStore.longTerm.promises = pruneMemoryList(promiseList, MEMORY_LIMITS.longTermPerCategory);
      }
      return JSON.stringify({ ok: true, id: reminderExisting ? reminderExisting.id : reminderItem.id, key: reminderKey, dueAt: reminderItem.dueAt ? formatContextTime(reminderItem.dueAt) : null });
    }
    if (name === 'ask_user') {
      var askQuestion = trimText(args.question, 200);
      if (!askQuestion) return JSON.stringify({ ok: false, reason: '缺少问题 question' });
      return JSON.stringify({ ok: true, question: askQuestion });
    }
    if (name === 'update_character_field') {
      var staticFieldKey = toText(args.field);
      if (!STATIC_PROFILE_FIELDS[staticFieldKey]) return JSON.stringify({ ok: false, reason: 'field 必须是基础设定字段之一：' + Object.keys(STATIC_PROFILE_FIELDS).join('、') });
      if (char.entityType === 'group' && GROUP_SHARED_STATIC_FIELDS.indexOf(staticFieldKey) === -1) {
        return JSON.stringify({ ok: false, reason: '群组只有公用字段可改：' + GROUP_SHARED_STATIC_FIELDS.map(function(k) { return STATIC_PROFILE_FIELDS[k].label; }).join('、') + '；成员的个人字段请在该成员卡片上修改' });
      }
      var staticResolved = resolveTimeRef(args.timeRef, new Date());
      var staticValue = trimText(cleanFieldValue(staticFieldKey, parseRelativeText(args.value, (staticResolved && staticResolved.iso) || new Date())), 700);
      var staticSources = hasValidUserEvidence(char, args.sourceMessageIds, args.evidence, getKnownSources(char));
      if (!staticValue) return JSON.stringify({ ok: false, reason: 'value 为空或全部为说明性文字，请只提供内容本身' });
      if (!staticSources || staticSources.length === 0) return JSON.stringify({ ok: false, reason: 'sourceMessageIds 必须是真实用户消息ID，且 evidence 逐字摘录用户原话；找不到可引用来源时不修改' });
      if (!hasStaticEditIntent(staticFieldKey, staticSources)) return JSON.stringify({ ok: false, reason: '用户原话没有要求修改该基础设定字段，不修改' });
      if (!isPlainObject(char.basicInfo)) char.basicInfo = {};
      if (!('staticFieldMeta' in char)) char.staticFieldMeta = {};
      char.staticFieldMeta[staticFieldKey] = {
        sourceMessageIds: staticSources.map(function(entry) { return entry.id; }),
        evidence: trimText(args.evidence, 300),
        updatedAt: new Date().toISOString()
      };
      char.basicInfo[staticFieldKey] = staticValue;
      if (toolState) {
        if (!Array.isArray(toolState.staticChanges)) toolState.staticChanges = [];
        toolState.staticChanges.push(STATIC_PROFILE_FIELDS[staticFieldKey].label);
      }
      scheduleSave();
      return JSON.stringify({ ok: true, field: staticFieldKey, label: STATIC_PROFILE_FIELDS[staticFieldKey].label, value: staticValue, changed: true });
    }
    if (name === 'upsert_lorebook_entry') {
      var lorebookSources = resolveLorebookSources(char, args.sourceMessageIds, args.evidence);
      if (!lorebookSources) return JSON.stringify({ ok: false, reason: 'sourceMessageIds 必须是上下文里真实存在的消息ID，且 evidence 要能对上原话或明确描述；拿不出依据时不要写世界书（本轮新编、还没出现在消息里的内容也不要写）' });
      var lorebookResult = upsertLorebookEntry(char, args, {
        sourceMessageIds: lorebookSources.map(function(entry) { return entry.id; }),
        evidence: args.evidence
      });
      if (!lorebookResult.ok) return JSON.stringify({ ok: false, reason: lorebookResult.reason });
      var lorebookEntry = lorebookResult.entry;
      if (toolState) {
        if (!Array.isArray(toolState.lorebookChanges)) toolState.lorebookChanges = [];
        toolState.lorebookChanges.push((lorebookResult.created ? '新增' : '更新') + '世界书「' + toText(lorebookEntry.name) + '」' + (lorebookEntry.alwaysActive ? '（常驻）' : ''));
      }
      scheduleSave();
      return JSON.stringify({ ok: true, entryId: lorebookEntry.id, name: lorebookEntry.name, alwaysActive: lorebookEntry.alwaysActive, keywords: lorebookEntry.keywords, created: lorebookResult.created, changed: true });
    }
    if (name === 'web_fetch') {
      var fetchKeyword = toText(args.keyword).trim();
      if (fetchKeyword) return await biliSearchVideos(fetchKeyword);
      var fetchUrl = toText(args.url).trim();
      if (!/^https?:\/\//i.test(fetchUrl)) return JSON.stringify({ ok: false, reason: 'url 必须是 http(s) 链接，或改用 keyword 搜索B站视频' });
      if (/^https?:\/\/(www\.)?b23\.tv\//i.test(fetchUrl)) {
        var shortId = await resolveBiliShortLink(fetchUrl);
        if (shortId) return await biliVideoDetail(shortId);
      }
      var biliId = extractBiliVideoId(fetchUrl);
      if (biliId) return await biliVideoDetail(biliId);
      if (/search\.bilibili\.com/i.test(fetchUrl) || /bilibili\.com\/.*\/search/i.test(fetchUrl)) {
        var pageKeyword = extractBiliSearchKeyword(fetchUrl);
        if (pageKeyword) return await biliSearchVideos(pageKeyword);
      }
      return await fetchWebContent(fetchUrl);
    }
    if (name === 'web_search') return await searchWebContent(toText(args.query));
    if (name === 'send_sticker') {
      var wantedTag = toText(args.tag).trim();
      if (!wantedTag) return JSON.stringify({ ok: false, reason: '缺少 tag，请从可用标签中选择一个' });
      var matches = getStickersByTag(char, wantedTag);
      if (matches.length === 0) {
        var allTags = getHostStickers(char).map(function(s) { return s.tag; }).filter(function(v, i, a) { return a.indexOf(v) === i; });
        return JSON.stringify({ ok: false, reason: '没有该标签的表情包；可用标签：' + allTags.join('、') });
      }
      var chosenSticker = matches[matches.length - 1];
      if (toolState) toolState.stickerToSend = { tag: chosenSticker.tag, dataUrl: chosenSticker.dataUrl };
      return JSON.stringify({ ok: true, tag: chosenSticker.tag, sent: true });
    }
    return JSON.stringify({ ok: false, reason: '未知工具: ' + name });
  } catch (e) {
    return JSON.stringify({ ok: false, reason: '工具执行异常: ' + (e && e.message ? e.message : String(e)) });
  }
}

async function executeAgentToolBatch(calls, char, toolState, reasoningItems, allowExecution) {
  (reasoningItems || []).forEach(function(item) { toolState.items.push(item); });
  if (!toolState.callResults) toolState.callResults = Object.create(null);
  var failed = false;
  for (var i = 0; i < calls.length; i++) {
    var call = calls[i];
    var callId = call.call_id || call.id || createMemoryId('call');
    var signature = call.name + '\n' + toText(call.arguments);
    var cached = toolState.callResults[callId];
    if (cached && cached.signature === signature) {
      if (typeof cached.output === 'string') try { if (JSON.parse(cached.output).ok === false) failed = true; } catch (error) { failed = true; }
      continue;
    }
    if (cached) throw new Error('工具调用 ID 重复且参数不一致，停止重复执行');
    var output;
    if (allowExecution === false || (Number(toolState.executedCalls) || 0) >= MAX_TOOL_CALLS) {
      output = JSON.stringify({ ok: false, reason: '工具调用已达上限，请基于已有结果收尾，不得声称未执行的修改已完成' });
    } else {
      toolState.executedCalls = (Number(toolState.executedCalls) || 0) + 1;
      output = await executeToolCall(call, char, toolState);
    }
    toolState.callResults[callId] = { signature: signature, output: output };
    toolState.items.push({ type: 'function_call', call_id: callId, name: call.name, arguments: toText(call.arguments) });
    toolState.items.push({ type: 'function_call_output', call_id: callId, output: output });
    if (typeof output === 'string') try { if (JSON.parse(output).ok === false) failed = true; } catch (error) { failed = true; }
  }
  return { failed: failed };
}

function extractFunctionCalls(fullResponse) {
  if (!fullResponse || !Array.isArray(fullResponse.output)) return [];
  var calls = [];
  fullResponse.output.forEach(function(item) {
    if (item && item.type === 'function_call') {
      calls.push({
        call_id: toText(item.call_id || item.id),
        name: toText(item.name),
        arguments: toText(item.arguments)
      });
    }
  });
  return calls;
}

// submit_response 工具：把结构化输出（reply + 全部记忆更新）交给 API 的 JSON Schema 强制校验
const TIME_REF_FIELD_DESCRIPTION = '相对时间词元：用户用“明天/上周五/上个月/三天后”等相对说法表达时间时必填，客户端会换算成绝对日期。anchor=相对基准，offsetDays=在anchor基础上的天数偏移(-60~60)，weekday=星期，slot=时段，explicit=已经是绝对时间的ISO或YYYY-MM-DD（填了explicit则忽略其余）。content/value内容字段仍须写绝对日期，不得写“明天”这类相对词。';

function buildTimeRefSchema() {
  return {
    type: 'object',
    additionalProperties: false,
    description: TIME_REF_FIELD_DESCRIPTION,
    properties: {
      anchor: { type: 'string', enum: TIME_REF_ANCHORS, description: '相对基准：today/tomorrow/yesterday/day_after_tomorrow/day_before_yesterday/this_week/next_week/last_week/this_month/next_month/last_month/this_year/next_year/last_year' },
      offsetDays: { type: 'integer', minimum: -60, maximum: 60, description: '在anchor基础上的天数偏移，如“三天后”=anchor:today + offsetDays:3' },
      weekday: { type: 'string', enum: TIME_REF_WEEKDAYS, description: '星期，如“上周五”=anchor:last_week + weekday:fri' },
      slot: { type: 'string', enum: TIME_SLOTS, description: '时段，如“明天早上”=anchor:tomorrow + slot:早晨' },
      explicit: { type: 'string', description: '已经是绝对时间时直接填ISO（2026-09-17T09:00:00）或2026-09-17' }
    }
  };
}

function buildDynamicStateFieldSchema() {
  return {
    type: 'object',
    additionalProperties: false,
    properties: {
      value: { type: 'string', description: '字段值：只写内容本身，以段落式的陈述句书写（自然、完整的陈述句，简短段落）；禁止括号注释、理由、解释性文字' },
      sourceMessageIds: { type: 'array', items: { type: 'string' } },
      evidence: { type: 'string' },
      timeRef: buildTimeRefSchema()
    },
    required: ['value']
  };
}
