function handleRecall(recallRequest, char) {
  var category = toText(recallRequest.category);
  var tags = Array.isArray(recallRequest.tags) ? recallRequest.tags.map(function(tag) { return toText(tag).toLowerCase(); }) : [];
  var results = [];
  if (category && char.memory.longTerm[category]) {
    results = char.memory.longTerm[category].filter(function(item) {
      if (tags.length === 0) return true;
      var searchable = (toText(item.key) + ' ' + toText(item.value) + ' ' + (item.tags || []).join(' ')).toLowerCase();
      return tags.some(function(tag) { return searchable.indexOf(tag) !== -1; });
    });
  }
  results.sort(function(a, b) { return memorySortScore(b) - memorySortScore(a); });
  results = results.slice(0, MEMORY_LIMITS.pendingRecall);
  if (results.length > 0) {
    char.memory.pendingRecall = results;
    var usageNow = new Date().toISOString();
    results.forEach(function(item) {
      item.lastRecalled = usageNow;
      item.recallCount = (Number(item.recallCount) || 0) + 1;
      item.usageCount = (Number(item.usageCount) || 0) + 1;
      item.lastUsageAt = usageNow;
    });
  }
}

function consumeInjectedRecalls(char, injectedIds) {
  var ids = Array.isArray(injectedIds) ? injectedIds : (Array.isArray(char.memory.lastInjectedRecallIds) ? char.memory.lastInjectedRecallIds : []);
  if (ids.length > 0) {
    char.memory.pendingRecall = (char.memory.pendingRecall || []).filter(function(item) { return ids.indexOf(item.id) === -1; });
  }
  char.memory.lastInjectedRecallIds = [];
}

function memoryTaskKeys(task) {
  if (task === 'extraction') return { failures: 'extractionFailures', retryAt: 'extractionRetryAt' };
  if (task === 'scene') return { failures: 'sceneFailures', retryAt: 'sceneRetryAt' };
  if (task === 'lorebook') return { failures: 'lorebookFailures', retryAt: 'lorebookRetryAt' };
  return { failures: 'analysisFailures', retryAt: 'analysisRetryAt' };
}

function scheduleMemoryRetry(counters, task) {
  var keys = memoryTaskKeys(task);
  var failures = Math.min(10, (Number(counters[keys.failures]) || 0) + 1);
  var delayMinutes = Math.min(30, 5 * Math.pow(2, failures - 1));
  counters[keys.failures] = failures;
  counters[keys.retryAt] = new Date(Date.now() + delayMinutes * 60000).toISOString();
}

function resetMemoryRetry(counters, task) {
  var keys = memoryTaskKeys(task);
  counters[keys.failures] = 0;
  counters[keys.retryAt] = null;
}

function canRunMemoryTask(counters, task) {
  var keys = memoryTaskKeys(task);
  return !counters[keys.retryAt] || Date.parse(counters[keys.retryAt]) <= Date.now();
}

function getExtractableInstantMessages(char) {
  return char.memory.instant.slice(0, Math.max(0, char.memory.instant.length - MEMORY_LIMITS.instantTrimFloor)).filter(function(message) {
    return !message.isLoading && !message.extractedAt;
  });
}

// 记忆重要性自学习：被使用（召回/检索命中）的记忆自动升权，长期未被想起的 userProfile/habits 自动降权
function selfLearnMemoryImportance(char) {
  if (!char || !char.memory || !char.memory.longTerm) return;
  var now = Date.now();
  var DAY = 86400000;
  var changed = false;
  Object.keys(char.memory.longTerm).forEach(function(category) {
    char.memory.longTerm[category].forEach(function(item) {
      var learnedBonus = Number.isFinite(Number(item.learnedBonus)) ? Number(item.learnedBonus) : 0;
      var usage = Number(item.usageCount) || 0;
      var lastUsageAt = Date.parse(item.lastUsageAt || '') || 0;
      var newBonus = learnedBonus;
      // 升权：30 天内被使用过，按累计使用量折算（每 2 次 +1，封顶 +3）
      if (lastUsageAt && (now - lastUsageAt) < 30 * DAY) {
        var usageTarget = Math.min(3, Math.floor(usage / 2));
        if (usageTarget > learnedBonus) newBonus = usageTarget;
      }
      // 降权：userProfile/habits 长期未被召回（只降权不删除）
      if (category === 'userProfile' || category === 'habits') {
        var lastRef = Date.parse(item.lastRecalled || item.updatedAt || item.createdAt || '') || 0;
        var daysSince = lastRef ? (now - lastRef) / DAY : 999;
        if (daysSince > 180) newBonus = Math.min(newBonus, -3);
        else if (daysSince > 90) newBonus = Math.min(newBonus, -2);
        else if (daysSince > 45) newBonus = Math.min(newBonus, -1);
      }
      newBonus = Math.max(-3, Math.min(3, newBonus));
      if (newBonus !== learnedBonus) {
        item.learnedBonus = newBonus;
        item.updatedAt = new Date().toISOString();
        changed = true;
      }
    });
  });
  return changed;
}

function getSceneKey(char) {
  var location = toText(char && char.dynamicState && char.dynamicState.currentLocation).trim();
  return trimText(location || '未说明', 80);
}

// 场景级摘要：场景切换（或场景过长）时，把这一段情节压缩成只含剧情要点的记忆
async function summarizeScene(char, sceneKey) {
  var guard = captureMemoryTask(char);
  var start = Number(char.memory.sceneState.startSequence) || 0;
  var messages = char.memory.instant.filter(function(message) { return !message.isLoading && message.sequence > start; }).slice(-MEMORY_LIMITS.instantTrimFloor);
  if (messages.length < 6) return false;
  var speakerName = toText(char.basicInfo && char.basicInfo.name) || '角色';
  var transcript = messages.map(function(message, index) {
    return '[' + (index + 1) + '] ' + (message.role === 'user' ? '用户' : speakerName) + ': ' + trimText(toText(message.content), 500);
  }).join('\n');
  var prompt = '把下面这段已经告一段落的情节压缩成 1-2 段、不超过 300 字的「场景记忆」，供之后长期参考。\n'
    + '只保留会影响后续剧情的要点：谁做了什么、学到或决定了什么；地点、物品、伤势、关系的变化；新的发现或线索；仍未解决的目标、承诺、威胁或期限。\n'
    + '要求：①写明主体（涉及用户写“用户”，涉及角色写角色名），禁止“我/你/TA”这类指代不清的代词；②时间写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“今天/昨天/刚才”这类相对时间词；③不要引用对白原句、不要加标题或 markdown、不要文学化描写；④不得编造原文没有的事实；⑤直接输出摘要正文，不要任何前后缀或解释。\n\n对话内容：\n' + transcript;
  try {
    var response = await callAPI([
      { role: 'system', content: '你是对话记录整理助手。只输出场景摘要正文，不要任何额外文字。' },
      { role: 'user', content: prompt }
    ], { char: char, taskType: 'scene' });
    if (!isMemoryTaskCurrent(guard)) return null;
    var content = toText(response.choices[0].message.content).trim();
    if (content.length < 8) return false;
    char.memory.scenes = (char.memory.scenes || []).concat([{
      id: createMemoryId('scene'),
      key: trimText(toText(sceneKey), 80) || getSceneKey(char),
      content: trimText(content, 2000),
      startedAt: normalizeTimestamp(messages[0].timestamp, null),
      endedAt: normalizeTimestamp(messages[messages.length - 1].timestamp, null),
      createdAt: new Date().toISOString()
    }]).slice(-8);
    return true;
  } catch (error) {
    console.error('summarizeScene failed:', error);
    return false;
  }
}

// 世界书整理任务：把近期已沉淀的事件事实补进世界书，并淘汰长期没人碰的 AI 条目。
// 只沉淀"已经出现在对话里"的内容（用短期记忆作为依据），不凭空发明设定。
async function consolidateLorebook(char) {
  var guard = captureMemoryTask(char);
  var counters = char.memory.counters;
  var books = [char.lorebook];
  if (char.entityType === 'group' && Array.isArray(char.members)) {
    char.members.forEach(function(member) { if (member) books.push(member.lorebook); });
  }
  var fresh = char.memory.shortTerm.filter(function(item) {
    return !item.lorebookScannedAt || (Number(item.lorebookScannedRevision) || 1) !== (Number(item.revision) || 1);
  }).slice(0, MEMORY_LIMITS.analysisBatch);
  if (fresh.length === 0) return true;
  var existingLines = [];
  books.forEach(function(book) {
    (book || []).forEach(function(entry) {
      existingLines.push('- ' + toText(entry.name) + '｜关键词: ' + (entry.keywords || []).join('、') + (entry.alwaysActive ? '｜常驻' : ''));
    });
  });
  var sourceEntries = fresh.map(function(item) {
    return { id: item.id, content: trimText(item.content, 600) };
  });
  var speakerName = toText(char.basicInfo && char.basicInfo.name) || '角色';
  var prompt = '下面是一段角色扮演对话里已经发生的事件摘要，以及现有的世界书条目清单。\n'
    + '请找出其中**已经出现、值得日后复用**的世界层设定（时代/世界观、地点、组织、专有名词、历史、规则、背景事实），整理成世界书条目。\n'
    + '要求：①只写已经出现或已被明确说出的内容，禁止推测、扩写或发明新设定；②已有条目能用就复用它的名字（同名会被更新），只有确实是一条新设定才起新名字；③每条 content 只写该条目本身的信息，不写理由、解释或出处；④keywords 写剧情里可能出现的称呼；若这条是世界前提/规则这类需要每轮生效的设定，把 alwaysActive 设为 true；⑤最多 3 条；没有值得沉淀的就返回空数组。\n'
    + '只返回 JSON：{"entries":[{"name":"条目名","keywords":["触发词"],"content":"设定内容","alwaysActive":false,"sourceShortTermIds":["上面的摘要ID"]}]}。sourceShortTermIds 必须是上面出现过的摘要ID，至少要有一个；没有可引用ID的条目不要返回。\n\n'
    + '现有条目清单：\n' + (existingLines.join('\n') || '（空）')
    + '\n\n事件摘要（角色名：' + speakerName + '）：\n' + sourceEntries.map(function(item) { return '[' + item.id + '] ' + item.content; }).join('\n');
  try {
    var response = await callAPI([
      { role: 'system', content: '你是世界书整理助手。只返回 JSON，不要任何额外文字。' },
      { role: 'user', content: prompt }
    ], { char: char, taskType: 'lorebook' });
    if (!isMemoryTaskCurrent(guard)) return null;
    var parsed = parseJsonPayload(response.choices[0].message.content);
    if (!isPlainObject(parsed) || !Array.isArray(parsed.entries)) throw new Error('世界书整理结果缺少 entries 数组');
    var allowedIds = {};
    sourceEntries.forEach(function(item) { allowedIds[item.id] = true; });
    var items = parsed.entries.slice(0, LOREBOOK_LIMITS.autoEntriesPerPass);
    if (items.some(function(item) {
      return !isPlainObject(item) || !toText(item.name).trim() || !toText(item.content).trim()
        || !Array.isArray(item.sourceShortTermIds) || item.sourceShortTermIds.length === 0
        || item.sourceShortTermIds.some(function(id) { return !allowedIds[id]; });
    })) throw new Error('世界书整理条目或来源无效');
    books.forEach(function(book) { evictStaleLorebookEntries(book); });
    var applied = 0;
    items.forEach(function(item) {
      if (!isPlainObject(item)) return;
      var sourceIds = Array.isArray(item.sourceShortTermIds) ? item.sourceShortTermIds.map(function(id) { return toText(id); }).filter(Boolean) : [];
      if (sourceIds.length === 0 || sourceIds.some(function(id) { return !allowedIds[id]; })) return;
      if (!toText(item.name).trim() || !toText(item.content).trim()) return;
      var result = upsertLorebookEntry(char, {
        name: item.name,
        content: item.content,
        keywords: item.keywords,
        alwaysActive: item.alwaysActive === true
      }, { sourceMessageIds: sourceIds, evidence: trimText(item.content, 300) });
      if (result.ok) applied++;
    });
    // 本轮看过的摘要打标记，避免下一轮重复整理（后续新摘要会再次覆盖同类事实）
    fresh.forEach(function(item) {
      item.lorebookScannedAt = new Date().toISOString();
      item.lorebookScannedRevision = Number(item.revision) || 1;
    });
    trimCharacterMemory(char);
    return true;
  } catch (error) {
    console.error('consolidateLorebook failed:', error);
    return false;
  }
}

async function checkMemoryTriggers(char) {
  ensureMessageSequences(char);
  var counters = char.memory.counters;
  selfLearnMemoryImportance(char);
  var extractableMessages = getExtractableInstantMessages(char);
  if (char.memory.instant.length >= MEMORY_LIMITS.instant && canRunMemoryTask(counters, 'extraction')) {
    setActivity('正在提取短期记忆…');
    var extracted = await extractInstantToShortTerm(char);
    if (extracted) {
      resetMemoryRetry(counters, 'extraction');
      trimCharacterMemory(char);
    } else if (extracted === false) {
      scheduleMemoryRetry(counters, 'extraction');
    } else {
      return false;
    }
  }
  var unanalyzedShortTermCount = char.memory.shortTerm.filter(function(item) { return !item.analyzedAt; }).length;
  if (char.memory.shortTerm.length >= MEMORY_LIMITS.shortTerm && unanalyzedShortTermCount >= (MEMORY_LIMITS.shortTerm - MEMORY_LIMITS.shortTermTrimFloor) && canRunMemoryTask(counters, 'analysis')) {
    setActivity('正在分析长期记忆…');
    var analyzed = await analyzeShortToLongTerm(char);
    if (analyzed) {
      resetMemoryRetry(counters, 'analysis');
    } else if (analyzed === false) {
      scheduleMemoryRetry(counters, 'analysis');
    } else {
      return false;
    }
  }
  if (!isPlainObject(char.memory.sceneState)) char.memory.sceneState = { key: '', startCount: 0, messageCount: 0 };
  var sceneState = char.memory.sceneState;
  var currentSceneKey = getSceneKey(char);
  sceneState.messageCount = char.memory.instant.filter(function(message) {
    return !message.isLoading && message.sequence > sceneState.startSequence;
  }).length;
  var sceneChanged = Boolean(sceneState.key) && sceneState.key !== currentSceneKey;
  var sceneLongEnough = sceneState.messageCount >= CONTEXT_BUDGET.sceneSpan;
  var sceneReady = (sceneChanged || sceneLongEnough) && sceneState.messageCount >= 6;
  if (sceneReady && canRunMemoryTask(counters, 'scene')) {
    setActivity('正在整理场景概要…');
    var summarized = await summarizeScene(char, sceneState.key || currentSceneKey);
    if (summarized) {
      resetMemoryRetry(counters, 'scene');
      char.memory.sceneState = { key: currentSceneKey, startCount: 0,
        startSequence: counters.messageSequence, messageCount: 0 };
    } else if (summarized === false) {
      scheduleMemoryRetry(counters, 'scene');
    } else {
      return false;
    }
  } else if (!sceneReady) {
    sceneState.key = currentSceneKey;
  }
  var lorebookPending = char.memory.shortTerm.filter(function(item) {
    return !item.lorebookScannedAt || (Number(item.lorebookScannedRevision) || 1) !== (Number(item.revision) || 1);
  });
  var lorebookDue = lorebookPending.length >= LOREBOOK_LIMITS.consolidateSpan
    || lorebookPending.some(function(item) { return item.analyzedAt; });
  if (lorebookDue && canRunMemoryTask(counters, 'lorebook')) {
    setActivity('正在整理世界书…');
    var lorebookConsolidated = await consolidateLorebook(char);
    if (lorebookConsolidated) {
      resetMemoryRetry(counters, 'lorebook');
      counters.lorebookScannedCount = (Number(counters.lorebookScannedCount) || 0) + Math.min(lorebookPending.length, MEMORY_LIMITS.analysisBatch);
    } else if (lorebookConsolidated === false) {
      scheduleMemoryRetry(counters, 'lorebook');
    } else {
      return false;
    }
  }
  trimCharacterMemory(char);
  return true;
}

async function extractInstantToShortTerm(char, options) {
  var guard = captureMemoryTask(char);
  options = options || {};
  var migration = options.migration === true;
  if (char.memory.instant.length <= 8) return false;
  var sourceMessages = getExtractableInstantMessages(char).slice(0, MEMORY_LIMITS.summarySources);
  var messagesToExtract = sourceMessages.map(function(message) {
    return { id: message.id, role: message.role, content: trimText(message.content, 600), timestamp: message.timestamp };
  });
  if (messagesToExtract.length === 0) return false;
  var recentEventIdentities = char.memory.shortTerm.slice().sort(function(a, b) {
    return (Date.parse(b.timestamp || '') || 0) - (Date.parse(a.timestamp || '') || 0);
  }).slice(0, 4).map(function(item) {
    return { eventTime: item.eventTime, participants: item.participants, location: item.location, content: trimText(item.content, 240) };
  });
  var prompt = '分析以下带消息ID和时间戳的即时对话，压缩为事件流程摘要。事件按“日期+时段”划分：时段只有深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里十种，且一天从02:00起算（00:00-02:00算前一天的深夜）；同一天同一时段的多个事情视为同一事件，按时间线集中叙述（如“2026-08-01 下午在家先…晚上…”）。同一时段也可能有不同事件，按事实或持续话题区分，不得把无关事件强行合并；同一事件内用时间线连接各阶段，保留仍然有效的关键细节、结果与未决事项。**只保留会影响后续剧情的内容**：谁做了什么、学到或决定了什么，地点/物品/伤势/关系的变化，新的发现或线索，以及仍未解决的目标、承诺、威胁与期限；一次性的寒暄、当下的情绪起伏、闲聊过程一律不记。**每条content不超过120字**，用平实的叙述句写成，禁止文学化描写、比喻、形容词铺陈与对白原文。eventTime写该事件的真实ISO时间，participants为参与人列表，location写地点（没有明确地点写“未说明”）。若内容是“最近短期事件”中某事件的延续，必须原样复用该事件的eventTime、participants和location，并把旧摘要与本批新进展重新组织成一段完整的更新后摘要；系统保留已有摘要并追加新事实，因此不能只返回新增片段：旧摘要中**仍然有效**的信息（未过期的约定、未解决的事、仍然成立的关系与状态）必须原样保留，只丢弃已经过时或已被推翻的部分。content按时间和因果顺序书写，不要逐字摘录。content必须写明是谁做的：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），双方共同的事写清各自做了什么，禁止使用“我、你、TA、他、她”这类指代不清的代词。content中禁止使用“今天、昨天、明天、今晚、今晚、刚刚、刚才、现在、最近、这几天”等相对时间词，一律使用具体日期（如“8月1日下午”）或事件本身描述。每条sourceMessageIds只能引用本批输入消息ID，旧摘要的来源由系统保留。必须覆盖全部输入消息：从第一条到最后一个已处理消息之间的每个消息ID都必须至少出现在一个事件的sourceMessageIds中，不能跳过中间消息，不得遗漏。' + (migration ? '不限事件数量，尽可能完整覆盖本批全部消息。' : '返回JSON数组，最多十五个元素。') + '返回JSON数组：[{"content":"一段完整的更新后事件流程摘要","eventTime":"ISO时间","participants":["用户","角色名"],"location":"地点或未说明","sourceMessageIds":["msg_id"]}]。\n最近短期事件:\n' + JSON.stringify(recentEventIdentities) + '\n对话内容:\n' + JSON.stringify(messagesToExtract);
  try {
    var response = await callAPI([
      { role: 'system', content: '你是一个信息提取助手。只返回JSON数组，不要其他文字。' },
      { role: 'user', content: prompt }
    ], { char: char, taskType: 'extraction' });
    if (!isMemoryTaskCurrent(guard)) return null;
    var content = response.choices[0].message.content;
    var points = parseJsonPayload(content);
    if (Array.isArray(points)) points = points.filter(isPlainObject);
    if (!migration && Array.isArray(points)) points = points.slice(0, 15);
    if (Array.isArray(points) && points.length > 0) {
      var sourceIdSet = new Set(sourceMessages.map(function(message) { return message.id; }));
      var coveredSourceIds = new Set();
      var sourceIndexes = Object.create(null);
      sourceMessages.forEach(function(message, index) { sourceIndexes[message.id] = index; });
      var validBatch = points.every(function(point) {
        var ids = Array.isArray(point.sourceMessageIds) ? Array.from(new Set(point.sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean))) : [];
        point.sourceMessageIds = ids;
        if (!trimText(point.content, 500) || !getEventIdentity(point.eventTime, point.participants, point.location) || ids.length === 0) return false;
        if (ids.some(function(id) { return !sourceIdSet.has(id); })) return false;
        ids.forEach(function(id) { coveredSourceIds.add(id); });
        return true;
      });
      if (!validBatch || coveredSourceIds.size === 0) return false;
      var coveredPrefixLength = 0;
      while (coveredPrefixLength < sourceMessages.length && coveredSourceIds.has(sourceMessages[coveredPrefixLength].id)) coveredPrefixLength++;
      var pointIndexes = new Map();
      points.forEach(function(point) {
        var firstIndex = Infinity;
        point.sourceMessageIds.forEach(function(id) { firstIndex = Math.min(firstIndex, sourceIndexes[id]); });
        pointIndexes.set(point, firstIndex);
      });
      if (coveredPrefixLength > 0) {
        points = points.filter(function(point) { return pointIndexes.get(point) < coveredPrefixLength; });
        if (points.length === 0) return false;
      }
      var accepted = 0;
      var acceptedPoints = [];
      points.forEach(function(point) {
        var pointTime = normalizeTimestamp(point.eventTime, '');
        if (!pointTime) {
          var latestSourceTime = '';
          (point.sourceMessageIds || []).forEach(function(id) {
            var sourceMessage = sourceMessages.find(function(message) { return message.id === id; });
            if (sourceMessage && sourceMessage.timestamp && sourceMessage.timestamp > latestSourceTime) latestSourceTime = sourceMessage.timestamp;
          });
          pointTime = latestSourceTime;
        }
        if (addShortTermMemory(char, point.content, pointTime || new Date().toISOString(), point)) {
          accepted++;
          acceptedPoints.push(point);
        }
      });
      if (accepted === 0) return false;
      var extractedAt = new Date().toISOString();
      var markedIds = new Set();
      acceptedPoints.forEach(function(point) {
        (point.sourceMessageIds || []).forEach(function(id) {
          markedIds.add(id);
        });
      });
      char.memory.instant.forEach(function(message) {
        if (markedIds.has(message.id)) message.extractedAt = extractedAt;
      });
      trimCharacterMemory(char);
      return true;
    }
    return false;
  } catch (e) {
    console.error('Extraction failed:', e);
    return false;
  }
}

async function analyzeShortToLongTerm(char) {
  var guard = captureMemoryTask(char);
  var analyzeLimit = Math.max(0, char.memory.shortTerm.length - MEMORY_LIMITS.shortTermTrimFloor);
  var itemsToAnalyze = char.memory.shortTerm.slice(0, analyzeLimit).filter(function(item) { return !item.analyzedAt; }).slice(0, MEMORY_LIMITS.analysisBatch);
  if (itemsToAnalyze.length === 0) return false;
  var prompt = '分析以下带短期ID、来源消息ID、userEvidence及时间/人物/地点三要素的事件流程摘要，提取未来仍有价值的稳定事实。**价值判据**：只有能跨轮复用、会影响后续对话或关系、或用户明确表达过的信息才记（长期偏好、重要人物与关系、承诺与约定、反复出现的习惯、持续的情节线）；一次性的寒暄客套、当下的情绪起伏、可以随口重说的闲聊、纯场景描写一律不记。**证据不足宁可不记**，不要为了产出而脑补或推演。必须检查全部输入条目，并在analyzedShortTermIds中原样返回全部输入的短期ID；不得遗漏、增加或重复。合并重复或冲突条目；事件、承诺保留必要日期；普通寒暄不要进入长期记忆。每条长期记忆必须在sourceShortTermIds中列出它实际使用的短期条目id；每个列出的短期条目都必须至少贡献一个sourceMessageIds中的消息ID，否则不要列出该短期ID。sourceMessageIds只选择直接支持该事实的消息，不要复制短期条目的全部来源；userProfile和habits只能选择用户消息。evidence只能逐字引用被选择消息对应的userEvidence.text，不能引用摘要或改写。没有可引用的userEvidence时不要输出该条。events按“日期+时段+事实主题”区分：同一天同一时段（深夜/凌晨/清晨/早晨/上午/中午/下午/傍晚/晚上/夜里，一天从02:00起算）的短期事件合并为一条长期事件，同一时段的独立事实不得互相覆盖，key须标明稳定事实主题；合并时eventTime取最早的ISO时间，participants取并集，location取最新（无明确地点写“未说明”）。剧情弧线：若若干短期事件属于同一持续情节或话题线（同一人物线、同一持续事件、同一反复出现的话题），除按时间合并外，还应在其中一条事件条目上标注arcOf（该情节的持续话题或人物线名称，稳定可复用）与arcStage（只能是起始/发展/转折/现状之一，按情节推进阶段标注），arcOf命名一旦确定就保持稳定——同一情节线不得每轮改名或另起新名，续写时沿用已有名称；并将value整合成按时间顺序、带情绪起伏的叙事摘要；同一arcOf只允许一条带弧线标注的条目，其余同线条目按普通事件输出。无论是否提取出长期记忆，成功完成分析都必须返回status:"ok"；没有长期价值时仍需返回完整analyzedShortTermIds和空longTerm。\n分类规则：userProfile/habits仅限用户事实，subject=user；relationship仅限双方关系，subject=relationship；events只记录用户陈述或共同事件；promises只记录用户明确承诺或双方明确约定。promises在此处只能新建为active；完成或取消由主对话的promiseUpdates按承诺ID处理。存在明确期限才填写dueAt。\n条目:\n' + JSON.stringify(itemsToAnalyze) + '\n\n返回JSON格式:\n{"status":"ok","analyzedShortTermIds":["全部输入的short_id"],"longTerm": [{"category": "...", "subject": "user|relationship|world", "key": "稳定且可复用的标识", "value": "...", "tags": [...], "importance": 1-10, "sourceShortTermIds":["short_id"], "sourceMessageIds": ["msg_id"], "evidence": "被选择用户消息中的逐字原话", "eventTime": "ISO时间", "participants":["参与者"],"location":"地点或未说明","status": "active", "dueAt": "ISO时间", "arcOf": "可选，持续情节线名称", "arcStage": "可选，起始/发展/转折/现状"}]}\ncategory可选: userProfile, relationship, events, promises, habits。value同样必须写明主体：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），禁止“我/你/TA”这类指代不清的代词。key与value中的时间一律写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“明天/明晚/上周/上个月/三天后”这类相对时间词。这里只提取用户的约定（promises 的 promisor=user、promisor与promisee只能填 user 或 character）；角色单方承诺由主对话记录，不在此处提取。';
  try {
    var response = await callAPI([
      { role: 'system', content: '你是一个记忆分析助手。只返回JSON，不要其他文字。' },
      { role: 'user', content: prompt }
    ], { char: char, taskType: 'analysis' });
    if (!isMemoryTaskCurrent(guard)) return null;
    var content = response.choices[0].message.content;
    var update = parseJsonPayload(content);
    if (!isPlainObject(update)) throw new Error('记忆分析结果不是对象');
    if (update.status !== 'ok') throw new Error('记忆分析结果缺少成功状态');
    if (!Object.prototype.hasOwnProperty.call(update, 'longTerm')) throw new Error('记忆分析结果缺少longTerm');
    if (!Array.isArray(update.longTerm)) throw new Error('longTerm 格式错误');
    var analyzableShortTermIds = new Set(itemsToAnalyze.map(function(item) { return item.id; }));
    var acknowledgedShortTermIdList = Array.isArray(update.analyzedShortTermIds) ? update.analyzedShortTermIds.map(function(id) { return toText(id); }).filter(Boolean) : [];
    var acknowledgedShortTermIds = new Set(acknowledgedShortTermIdList);
    if (acknowledgedShortTermIdList.length !== analyzableShortTermIds.size || acknowledgedShortTermIds.size !== analyzableShortTermIds.size || acknowledgedShortTermIdList.some(function(id) { return !analyzableShortTermIds.has(id); })) throw new Error('记忆分析未确认全部短期条目');
    var consumedShortTermIds = new Set();
    var analyzeSources = getKnownSources(char);
    var preparedUpdates = update.longTerm.map(function(item) {
      if (!isPlainObject(item) || !char.memory.longTerm[item.category]) throw new Error('长期记忆条目格式错误');
      var sourceShortTermIds = Array.isArray(item.sourceShortTermIds) ? Array.from(new Set(item.sourceShortTermIds.map(function(id) { return toText(id); }).filter(Boolean))) : [];
      if (sourceShortTermIds.length === 0 || sourceShortTermIds.some(function(id) { return !analyzableShortTermIds.has(id); })) throw new Error('长期记忆引用了无效短期ID');
      var claimedShortTermItems = itemsToAnalyze.filter(function(shortTermItem) { return sourceShortTermIds.indexOf(shortTermItem.id) !== -1; });
      var allowedMessageIds = new Set(claimedShortTermItems.reduce(function(ids, shortTermItem) { return ids.concat(shortTermItem.sourceMessageIds || []); }, []));
      var itemMessageIds = Array.isArray(item.sourceMessageIds) ? Array.from(new Set(item.sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean))) : [];
      if (itemMessageIds.length === 0 || itemMessageIds.some(function(id) { return !allowedMessageIds.has(id); })) throw new Error('长期记忆消息来源不属于声明的短期条目');
      if (claimedShortTermItems.some(function(shortTermItem) {
        return !(shortTermItem.sourceMessageIds || []).some(function(id) { return itemMessageIds.indexOf(id) !== -1; });
      })) throw new Error('长期记忆声明了未贡献消息来源的短期条目');
      if (item.category === 'events') {
        var eventIdentity = getEventIdentity(item.eventTime, item.participants, item.location);
        if (!eventIdentity || claimedShortTermItems.some(function(shortTermItem) {
          return getEventIdentity(shortTermItem.eventTime, shortTermItem.participants, shortTermItem.location) !== eventIdentity;
        })) throw new Error('长期事件三要素与声明的短期事件不一致');
      }
      var allowedUserEvidence = claimedShortTermItems.reduce(function(entries, shortTermItem) { return entries.concat(shortTermItem.userEvidence || []); }, []);
      if (!allowedUserEvidence.some(function(entry) { return itemMessageIds.indexOf(entry.sourceMessageId) !== -1 && evidenceMatchesSource(entry.text, item.evidence); })) throw new Error('长期记忆证据不属于声明的短期条目');
      if (!trimText(item.key, 100) || !trimText(item.value, 900) || !isValidAutomaticMemory(char, item, analyzeSources, null)) throw new Error('长期记忆条目未通过证据校验');
      if (!normalizeTimestamp(item.eventTime, '')) {
        var latestSourceTime = '';
        claimedShortTermItems.forEach(function(shortTermItem) {
          if (shortTermItem.timestamp && shortTermItem.timestamp > latestSourceTime) latestSourceTime = shortTermItem.timestamp;
        });
        if (latestSourceTime) item._sourceTs = latestSourceTime;
      }
      return { item: item, sourceShortTermIds: sourceShortTermIds };
    });
    var longTermSnapshot = JSON.parse(JSON.stringify(char.memory.longTerm));
    try {
      preparedUpdates.forEach(function(entry) {
        if (!upsertLongTermMemory(char, entry.item, analyzeSources, undefined, null)) throw new Error('长期记忆条目无法写入');
        entry.sourceShortTermIds.forEach(function(id) { consumedShortTermIds.add(id); });
      });
    } catch (writeError) {
      char.memory.longTerm = longTermSnapshot;
      throw writeError;
    }
    var analyzedAt = new Date().toISOString();
    char.memory.shortTerm.forEach(function(item) {
      if (!analyzableShortTermIds.has(item.id)) return;
      item.analyzedAt = analyzedAt;
      item.analyzedRevision = Number(item.revision) || 1;
    });
    trimCharacterMemory(char);
    return true;
  } catch (e) {
    console.error('Analysis failed:', e);
    return false;
  }
}
