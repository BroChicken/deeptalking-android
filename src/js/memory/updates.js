function retrieveRelevantMemories(char, query, memoryStore) {
  memoryStore = memoryStore || char.memory;
  var longTerm = memoryStore.longTerm || {};
  var terms = getSearchTerms(query);
  var now = Date.now();
  var candidates = [];
  Object.keys(longTerm).forEach(function(category) {
    longTerm[category].forEach(function(item) {
      var searchable = (toText(item.key) + ' ' + toText(item.value) + ' ' + (item.tags || []).join(' ')).toLowerCase();
      var score = computeEffectiveImportance(item, now) * 0.45;
      var matched = 0;
      terms.forEach(function(term) {
        if (searchable.indexOf(term) !== -1) {
          score += term.length > 2 ? 4 : 2;
          matched++;
        }
      });
      if (matched === 0) return;
      var memoryTime = Date.parse(item.eventTime || item.createdAt || '') || 0;
      var ageDays = memoryTime ? Math.max(0, (now - memoryTime) / 86400000) : 9999;
      score += Math.max(0, 2 - ageDays / 90);
      if (toText(item.arcOf)) score += 3;
      candidates.push({ item: item, category: category, score: score });
    });
  });
  candidates.sort(function(a, b) { return b.score - a.score; });
  var selected = [];
  var seen = new Set();
  (memoryStore.pendingRecall || []).forEach(function(item) {
    var id = item.id || 'pending:' + item.key;
    if (!seen.has(id) && selected.length < 12) {
      selected.push({ item: item, category: 'recalled', score: 999 });
      seen.add(id);
    }
  });
  candidates.forEach(function(entry) {
    var id = entry.item.id || entry.category + ':' + entry.item.key;
    if (selected.length >= 12 || seen.has(id)) return;
    seen.add(id);
    selected.push(entry);
  });
  var finalList = selected.slice(0, 12);
  return finalList.map(function(entry) {
    return { item: entry.item, category: entry.category, score: Math.round(entry.score * 100) / 100 };
  });
}

function getKnownSources(char) {
  var sources = Object.create(null);
  char.memory.instant.forEach(function(message) {
    if (message.id && !message.isLoading) {
      sources[message.id] = { role: message.role, text: message.content };
    }
  });
  char.memory.shortTerm.forEach(function(item) {
    (item.sourceMessageIds || []).forEach(function(id, index) {
      if (!sources[id]) {
        var originalUserText = (item.userEvidence || []).find(function(entry) { return entry.sourceMessageId === id; });
        var sourceRole = (item.sourceRoles || [])[index] || '';
        sources[id] = { role: originalUserText ? 'user' : (sourceRole === 'assistant' ? 'assistant' : ''), text: originalUserText ? originalUserText.text : item.content };
      }
    });
  });
  return sources;
}

function resolveMemorySources(char, item, sources) {
  var sourceIds = Array.isArray(item.sourceMessageIds) ? item.sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean).slice(0, 8) : [];
  sources = sources || getKnownSources(char);
  var resolved = sourceIds.map(function(id) { return { id: id, source: sources[id] }; });
  if (resolved.length === 0 || resolved.some(function(entry) { return !entry.source || !entry.source.role; })) return null;
  return resolved;
}

// 约定来源校验：用户的约定要用户原话；角色的约定允许 current_response 或角色消息
function resolvePromiseSources(char, item, assistantMessage, sources) {
  var ids = Array.isArray(item.sourceMessageIds) ? item.sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean).slice(0, 8) : [];
  var evidence = trimText(item.evidence, 300);
  if (evidence.length < 2) return null;
  var promisor = toText(item.promisor);
  if (promisor === 'character') {
    if (ids.indexOf('current_response') !== -1) {
      if (!assistantMessage || !evidenceMatchesSummary(assistantMessage.content, evidence)) return null;
      return [{ id: assistantMessage.id, source: { role: 'assistant', text: assistantMessage.content } }];
    }
    var resolvedAssistant = resolveMemorySources(char, item, sources);
    if (!resolvedAssistant) return null;
    if (!resolvedAssistant.some(function(entry) {
      return entry.source.role === 'assistant' && toText(entry.source.text).indexOf(evidence) !== -1;
    })) return null;
    return resolvedAssistant;
  }
  return hasValidUserEvidence(char, ids, evidence, sources);
}

function isValidAutomaticMemory(char, item, sources, assistantMessage) {
  if (!isPlainObject(item) || !char.memory.longTerm[item.category]) return false;
  var subject = toText(item.subject);
  if (MEMORY_SUBJECTS.indexOf(subject) === -1 || subject === 'legacy') return false;
  var evidence = trimText(item.evidence, 300);
  if (evidence.length < 2) return false;
  if (item.category === 'promises') {
    if (toText(item.promisor) === 'character') {
      if (subject === 'user') return false;
      return Boolean(resolvePromiseSources(char, item, assistantMessage, sources));
    }
    if (subject === 'character') return false;
    var promiseSources = resolveMemorySources(char, item, sources);
    if (!promiseSources || !promiseSources.some(function(entry) { return toText(entry.source.text).indexOf(evidence) !== -1; })) return false;
    var promiseRoles = promiseSources.map(function(entry) { return entry.source.role; });
    return (subject === 'user' && promiseRoles.every(function(role) { return role === 'user'; })) || (subject === 'relationship' && promiseRoles.indexOf('user') !== -1);
  }
  if (subject === 'character') return false;
  var sourcesResolved = resolveMemorySources(char, item, sources);
  if (!sourcesResolved || !sourcesResolved.some(function(entry) { return toText(entry.source.text).indexOf(evidence) !== -1; })) return false;
  var roles = sourcesResolved.map(function(entry) { return entry.source.role; });
  var allUser = roles.every(function(role) { return role === 'user'; });
  var includesUser = roles.indexOf('user') !== -1;
  if (item.category === 'userProfile' || item.category === 'habits') return subject === 'user' && allUser;
  if (item.category === 'relationship') return subject === 'relationship' && includesUser;
  if (item.category === 'events') return Boolean(getEventIdentity(item.eventTime, item.participants, item.location)) && ((subject === 'user' && allUser) || ((subject === 'relationship' || subject === 'world') && includesUser));
  return false;
}

function normalizeParticipants(value) {
  return (Array.isArray(value) ? value : []).map(function(item) { return trimText(item, 80); }).filter(Boolean).slice(0, 8).sort(function(a, b) { return a.localeCompare(b, 'zh-CN'); });
}

function getEventIdentity(eventTime, participants, location) {
  var date = new Date(normalizeTimestamp(eventTime, ''));
  if (isNaN(date.getTime())) return '';
  var logicalDay = getLogicalDayDate(date);
  return logicalDay.getFullYear() + '年' + (logicalDay.getMonth() + 1) + '月' + logicalDay.getDate() + '日[' + getTimeSlot(date) + ']';
}

function reconcileLegacyMemories(char) {
  var repaired = 0;
  var seen = Object.create(null);
  var mergedOut = new Set();
  char.memory.shortTerm.forEach(function(item, index) {
    var identity = getEventIdentity(item.eventTime, item.participants, item.location);
    if (!identity) return;
    if (!seen[identity]) {
      seen[identity] = { index: index, item: item };
      return;
    }
    var primary = seen[identity];
    var old = primary.item;
    var newer = item;
    var merged = {
      id: old.id,
      content: mergeTimelineContent(old, newer),
      timestamp: earlierTimestamp(old.timestamp, newer.timestamp),
      analyzedAt: null,
      sourceMessageIds: Array.from(new Set((old.sourceMessageIds || []).concat(newer.sourceMessageIds || []))).slice(0, 8),
      sourceRoles: Array.from(new Set((old.sourceRoles || []).concat(newer.sourceRoles || []))).slice(0, 8),
      userEvidence: mergeUserEvidence(old.userEvidence, newer.userEvidence),
      eventTime: earlierTimestamp(old.eventTime, newer.eventTime),
      participants: Array.from(new Set(normalizeParticipants((old.participants || []).concat(newer.participants || [])))).slice(0, 8),
      location: mergeLocations(old.location, newer.location)
    };
    char.memory.shortTerm[primary.index] = merged;
    seen[identity].item = merged;
    mergedOut.add(index);
    repaired++;
  });
  if (mergedOut.size > 0) {
    char.memory.shortTerm = char.memory.shortTerm.filter(function(item, index) { return !mergedOut.has(index); });
  }
  Object.keys(char.memory.longTerm || {}).forEach(function(category) {
    if (category !== 'events') return;
    var list = char.memory.longTerm[category];
    if (!Array.isArray(list)) return;
    var groups = Object.create(null);
    list.forEach(function(item, index) {
      var identity = getEventIdentity(item.eventTime, item.participants, item.location);
      if (!identity) return;
      if (!groups[identity]) groups[identity] = [];
      groups[identity].push({ index: index, item: item });
    });
    var dropIndexes = new Set();
    Object.keys(groups).forEach(function(identity) {
      var group = groups[identity];
      if (group.length < 2) return;
      var primary = group[0];
      group.slice(1).forEach(function(entry) {
        var old = primary.item;
        var newer = entry.item;
        old.value = mergeTimelineContent(old, newer);
        old.tags = Array.from(new Set((old.tags || []).concat(newer.tags || []))).slice(0, 8);
        old.importance = Math.max(Number(old.importance) || 0, Number(newer.importance) || 0);
        old.sourceMessageIds = Array.from(new Set((old.sourceMessageIds || []).concat(newer.sourceMessageIds || []))).slice(0, 8);
        old.sourceRoles = Array.from(new Set((old.sourceRoles || []).concat(newer.sourceRoles || []))).slice(0, 8);
        old.evidence = (old.evidence || newer.evidence || '');
        old.eventTime = earlierTimestamp(old.eventTime, newer.eventTime);
        old.participants = Array.from(new Set(normalizeParticipants((old.participants || []).concat(newer.participants || [])))).slice(0, 8);
        old.location = mergeLocations(old.location, newer.location);
        old.updatedAt = newer.updatedAt || old.updatedAt;
        dropIndexes.add(entry.index);
        repaired++;
      });
    });
    if (dropIndexes.size > 0) {
      char.memory.longTerm[category] = list.filter(function(item, index) { return !dropIndexes.has(index); });
    }
  });
  return repaired;
}

// 旧数据兼容：把已存文本里的相对时间词按该条自身的时间戳换算为绝对日期（幂等，只改内容字段，不动evidence）
function convertLegacyMemoryStore(store, dynamicMetaHost, staticMetaHost) {
  if (!store) return 0;
  var converted = 0;
  var instantTimes = (store.instant || []).map(function(message) { return Date.parse(message.timestamp || ''); }).filter(function(value) { return !isNaN(value); });
  var fallbackTime = instantTimes.length > 0 ? new Date(instantTimes[instantTimes.length - 1]) : new Date();
  (store.shortTerm || []).forEach(function(item) {
    var base = item.eventTime || item.timestamp;
    var next = parseRelativeText(item.content, base);
    if (next !== item.content) { item.content = next; converted++; }
  });
  Object.keys(store.longTerm || {}).forEach(function(category) {
    (store.longTerm[category] || []).forEach(function(item) {
      var base = item.eventTime || item.recordedAt || item.createdAt || fallbackTime;
      var nextKey = parseRelativeText(item.key, base);
      var nextValue = parseRelativeText(item.value, base);
      if (nextKey !== item.key) { item.key = nextKey; converted++; }
      if (nextValue !== item.value) { item.value = nextValue; converted++; }
    });
  });
  var dynamicMeta = (dynamicMetaHost && dynamicMetaHost.dynamicStateMeta) || {};
  DYNAMIC_STATE_FIELDS.forEach(function(field) {
    var host = dynamicMetaHost || {};
    var value = toText(host.dynamicState && host.dynamicState[field.key]);
    if (!value) return;
    var meta = dynamicMeta[field.key] || {};
    var next = parseRelativeText(value, meta.updatedAt || fallbackTime);
    if (next !== value) { host.dynamicState[field.key] = next; converted++; }
  });
  var staticMeta = (staticMetaHost && staticMetaHost.staticFieldMeta) || {};
  Object.keys(STATIC_PROFILE_FIELDS).forEach(function(key) {
    var host = staticMetaHost || {};
    var value = toText(host.basicInfo && host.basicInfo[key]);
    if (!value) return;
    var meta = staticMeta[key] || {};
    var base = meta.updatedAt;
    if (!base) return;
    var next = parseRelativeText(value, base);
    if (next !== value) { host.basicInfo[key] = next; converted++; }
  });
  return converted;
}

function convertLegacyRelativeTimes(character) {
  if (!character || !character.memory) return 0;
  if (Number(character.timeParseVersion) === TIME_PARSE_VERSION) return 0;
  var converted = convertLegacyMemoryStore(character.memory, character, character);
  (character.members || []).forEach(function(member) {
    converted += convertLegacyMemoryStore(member.memory, member, member);
  });
  character.timeParseVersion = TIME_PARSE_VERSION;
  return converted;
}

function mergeTimelineContent(a, b) {
  var aTime = Date.parse(a.eventTime || '') || 0;
  var bTime = Date.parse(b.eventTime || '') || 0;
  var first = aTime <= bTime ? a : b;
  var second = aTime <= bTime ? b : a;
  return trimText(first.content || first.value, 900) + '\n' + trimText(second.content || second.value, 900);
}

function earlierTimestamp(a, b) {
  var aTime = Date.parse(a || '');
  var bTime = Date.parse(b || '');
  if (isNaN(aTime)) return b || a;
  if (isNaN(bTime)) return a || b;
  return aTime <= bTime ? a : b;
}

function mergeLocations(a, b) {
  var set = Array.from(new Set([trimText(a, 160), trimText(b, 160)].filter(Boolean)));
  return set.join('、') || '未说明';
}

function mergeUserEvidence(a, b) {
  var list = (a || []).concat(b || []);
  return list.filter(function(entry, index, arr) {
    return arr.findIndex(function(other) { return other.sourceMessageId === entry.sourceMessageId && other.text === entry.text; }) === index;
  }).slice(0, 8);
}

function addShortTermMemory(char, content, timestamp, sourceData, sources) {
  var text = trimText(content, 500);
  if (!text) return false;
  var sourceIds = sourceData && Array.isArray(sourceData.sourceMessageIds) ? Array.from(new Set(sourceData.sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean))).slice(0, MEMORY_LIMITS.summarySources) : [];
  var knownSources = sources || getKnownSources(char);
  if (sourceIds.length === 0 || sourceIds.some(function(id) { return !knownSources[id]; })) return false;
  var sourceRoles = sourceIds.map(function(id) { return knownSources[id].role; });
  var userEvidence = sourceIds.map(function(id) {
    var source = knownSources[id];
    return source.role === 'user' ? { sourceMessageId: id, text: trimText(source.text, 300) } : null;
  }).filter(Boolean);
  var eventTime = normalizeTimestamp(sourceData && sourceData.eventTime, timestamp || new Date().toISOString());
  var resolvedTime = resolveTimeRef(sourceData && sourceData.timeRef, eventTime);
  if (resolvedTime && resolvedTime.iso) eventTime = resolvedTime.iso;
  text = parseRelativeText(text, (resolvedTime && resolvedTime.iso) ? resolvedTime.iso : eventTime);
  var participants = normalizeParticipants(sourceData && sourceData.participants);
  var location = trimText(sourceData && sourceData.location, 160);
  var eventIdentity = getEventIdentity(eventTime, participants, location);
  var existing = eventIdentity && char.memory.shortTerm.find(function(item) {
    return item.content === text && getEventIdentity(item.eventTime, item.participants, item.location) === eventIdentity;
  });
  if (existing) {
    existing.timestamp = timestamp || existing.timestamp;
    existing.sourceMessageIds = Array.from(new Set((existing.sourceMessageIds || []).concat(sourceIds))).slice(-MEMORY_LIMITS.summarySources);
    existing.sourceRoles = existing.sourceMessageIds.map(function(id) { return knownSources[id] ? knownSources[id].role : ''; });
    existing.userEvidence = (existing.userEvidence || []).concat(userEvidence).filter(function(entry, index, list) {
      return list.findIndex(function(other) { return other.sourceMessageId === entry.sourceMessageId && other.text === entry.text; }) === index;
    }).filter(function(entry) { return existing.sourceMessageIds.indexOf(entry.sourceMessageId) !== -1; }).slice(-MEMORY_LIMITS.summarySources);
    return true;
  }
  char.memory.shortTerm.push({
    id: createMemoryId('short'),
    content: text,
    timestamp: timestamp || new Date().toISOString(),
    analyzedAt: null,
    revision: 1,
    analyzedRevision: 0,
    lorebookScannedRevision: 0,
    sourceMessageIds: sourceIds,
    sourceRoles: sourceRoles,
    userEvidence: userEvidence,
    eventTime: eventTime,
    participants: participants,
    location: location
  });
  char.memory.shortTerm = trimShortTermList(char.memory.shortTerm);
  return true;
}

function hasValidUserEvidence(char, sourceMessageIds, evidence, sources) {
  var sourceIds = Array.isArray(sourceMessageIds) ? sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean).slice(0, 8) : [];
  sources = sources || getKnownSources(char);
  var excerpt = trimText(evidence, 300);
  if (sourceIds.length === 0 || !excerpt) return null;
  var resolved = sourceIds.map(function(id) { return { id: id, source: sources[id] }; });
  if (resolved.some(function(entry) { return !entry.source; })) return null;
  if (!resolved.some(function(entry) { return entry.source.role === 'user' && evidenceMatchesSource(entry.source.text, excerpt); })) return null;
  return resolved;
}

function evidenceMatchesSource(source, evidence) {
  var normalize = function(value) {
    return toText(value).toLowerCase().replace(/[\s\u3000，。！？、；：,.!?;:'"“”‘’（）()【】\[\]{}]/g, '');
  };
  var sourceText = normalize(source);
  var evidenceText = normalize(evidence);
  return evidenceText.length >= 2 && sourceText.indexOf(evidenceText) !== -1;
}

// Summarized evidence still needs multiple overlapping substantive fragments.
function evidenceMatchesSummary(source, evidence) {
  if (evidenceMatchesSource(source, evidence)) return true;
  var ev = toText(evidence).trim();
  var src = toText(source).toLowerCase();
  if (ev.length < 4 || src.length < 4) return false;
  var pairs = [];
  var matches = 0;
  for (var i = 0; i + 2 <= ev.length; i++) {
    var pair = ev.substr(i, 2).toLowerCase();
    if (!/^[\u4e00-\u9fff\w]{2}$/.test(pair) || pairs.indexOf(pair) !== -1) continue;
    pairs.push(pair);
    if (src.indexOf(pair) !== -1) matches++;
  }
  return matches >= 2 && matches / Math.max(1, pairs.length) >= 0.35;
}

function hasStaticEditIntent(field, resolvedSources) {
  var aliases = {
    gender: /性别|gender/, age: /年龄|岁|age/, race: /种族|race|species/,
    appearance: /外貌|长相|头发|衣服|appearance/, personality: /性格|personality/,
    values: /价值观|values/, fears: /恐惧|弱点|害怕|fears/,
    background: /背景|经历|background/, keyEvents: /关键过往|里程碑|key.events/,
    speakingStyle: /说话|语气|口吻|风格|speaking.style|tone/,
    language: /语言|方言|中文|英文|language/,
    userAddress: /叫我|称呼|喊我|call.me|address/
  };
  return (resolvedSources || []).some(function(entry) {
    var text = toText(entry.source && entry.source.text);
    if (/不要(?:修改|改变|改|换)|别(?:修改|改|换)|do not change|don't change/i.test(text)) return false;
    return entry.source.role === 'user' && aliases[field] && aliases[field].test(text.toLowerCase())
      && /改|修改|设置|设定|换|叫我|喊我|别叫|不要|说话.{0,12}(点|些|一点)|用.{0,12}(说|讲)|请.{0,16}(说|讲)|change|set|call.me|speak|use /i.test(text);
  });
}

function resolveDynamicStateSources(char, sourceMessageIds, evidence, assistantMessage, sources) {
  var ids = Array.isArray(sourceMessageIds) ? sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean).slice(0, 8) : [];
  if (ids.indexOf('current_response') !== -1) {
    if (ids.length !== 1 || !assistantMessage || !evidenceMatchesSummary(assistantMessage.content, evidence)) return null;
    return [{ id: assistantMessage.id, source: { role: 'assistant', text: assistantMessage.content } }];
  }
  return hasValidUserEvidence(char, ids, evidence, sources);
}

function applyDynamicStateUpdates(char, updates, assistantMessage, target, sources) {
  if (!isPlainObject(updates)) return 0;
  target = target || char;
  if (!isPlainObject(target.dynamicState)) target.dynamicState = {};
  if (!isPlainObject(target.dynamicStateMeta)) target.dynamicStateMeta = {};
  var accepted = 0;
  DYNAMIC_STATE_FIELDS.forEach(function(field) {
    var update = updates[field.key];
    if (!isPlainObject(update)) return;
    var dynamicResolvedTime = resolveTimeRef(update.timeRef, new Date());
    var value = trimText(cleanFieldValue(field.key, sanitizeDynamicStateField(field.key, update.value, (dynamicResolvedTime && dynamicResolvedTime.iso) || null)), 700);
    var sourcesResolved = resolveDynamicStateSources(char, update.sourceMessageIds, update.evidence, assistantMessage, sources);
    if (!value || !sourcesResolved) return;
    target.dynamicState[field.key] = value;
    target.dynamicStateMeta[field.key] = {
      sourceMessageIds: sourcesResolved.map(function(entry) { return entry.id; }),
      evidence: trimText(update.evidence, 300),
      updatedAt: new Date().toISOString()
    };
    accepted++;
  });
  return accepted;
}

// 谨慎修改字段：仅当模型给出极其明确的剧情依据时才更新 basicInfo 的静态设定字段。
// 返回被修改字段的中文标签数组，供该轮回复气泡下提示用户。
function applyStaticFieldUpdates(char, updates, assistantMessage, sources) {
  if (!isPlainObject(updates)) return [];
  if (!isPlainObject(char.basicInfo)) return [];
  var changedLabels = [];
  Object.keys(STATIC_PROFILE_FIELDS).forEach(function(key) {
    if (char.entityType === 'group' && GROUP_SHARED_STATIC_FIELDS.indexOf(key) === -1) return;
    var update = updates[key];
    if (!isPlainObject(update)) return;
    var staticResolvedTime = resolveTimeRef(update.timeRef, new Date());
    var value = trimText(cleanFieldValue(key, parseRelativeText(toText(update.value), (staticResolvedTime && staticResolvedTime.iso) || new Date())), 700);
    var sourcesResolved = hasValidUserEvidence(char, update.sourceMessageIds, update.evidence, sources);
    if (!value || !sourcesResolved || !hasStaticEditIntent(key, sourcesResolved)) return;
    if (!('staticFieldMeta' in char)) char.staticFieldMeta = {};
    char.staticFieldMeta[key] = {
      sourceMessageIds: sourcesResolved.map(function(entry) { return entry.id; }),
      evidence: trimText(update.evidence, 300),
      updatedAt: new Date().toISOString()
    };
    char.basicInfo[key] = value;
    changedLabels.push(STATIC_PROFILE_FIELDS[key].label);
  });
  return changedLabels;
}

function applyMemberDynamicStateUpdates(char, updates, assistantMessage, sources) {
  if (char.entityType !== 'group' || !Array.isArray(updates)) return 0;
  var accepted = 0;
  updates.forEach(function(item) {
    if (!isPlainObject(item)) return;
    var name = trimText(item.memberName, 100).toLowerCase();
    var member = (char.members || []).find(function(candidate) {
      return trimText(candidate.basicInfo && candidate.basicInfo.name, 100).toLowerCase() === name;
    });
    if (!member) return;
    accepted += applyDynamicStateUpdates(char, item.dynamicState, assistantMessage, member, sources);
  });
  return accepted;
}

function consumePromiseUpdates(char, updates, sources) {
  if (!Array.isArray(updates)) return 0;
  var accepted = 0;
  updates.forEach(function(update) {
    if (!isPlainObject(update)) return;
    var status = toText(update.status);
    if (status !== 'resolved' && status !== 'cancelled') return;
    var promiseId = toText(update.promiseId);
    var promise = null;
    var promiseStores = [char.memory];
    if (char.entityType === 'group' && Array.isArray(char.members)) {
      char.members.forEach(function(member) {
        if (member && member.memory) promiseStores.push(member.memory);
      });
    }
    for (var storeIndex = 0; storeIndex < promiseStores.length && !promise; storeIndex++) {
      promise = (promiseStores[storeIndex].longTerm && promiseStores[storeIndex].longTerm.promises || []).find(function(item) {
        return item.id === promiseId && item.status === 'active';
      });
    }
    var sourcesResolved = hasValidUserEvidence(char, update.sourceMessageIds, update.evidence, sources);
    if (!promise || !sourcesResolved) return;
    promise.status = status;
    promise.sourceMessageIds = sourcesResolved.map(function(entry) { return entry.id; });
    promise.sourceRoles = sourcesResolved.map(function(entry) { return entry.source.role; });
    promise.evidence = trimText(update.evidence, 300);
    promise.updatedAt = new Date().toISOString();
    accepted++;
  });
  return accepted;
}

function shouldCaptureUserTurn(text) {
  var value = toText(text).trim();
  if (value.length < 3) return false;
  return !/^(你好|您好|嗨|哈喽|早安|晚安|在吗|谢谢|好的|嗯+|哈哈+)[！!。.，,\s]*$/.test(value);
}

// 记忆冲突启发式：把文本拆成词/字，比较重叠度；重叠过低视为语义冲突
function tokenizeForCompare(text) {
  var normalized = String(text).toLowerCase().replace(/[^\w\u4e00-\u9fa5]/g, ' ');
  var terms = [];
  normalized.split(/\s+/).forEach(function(chunk) {
    if (!chunk) return;
    if (!/[\u4e00-\u9fa5]/.test(chunk)) { terms.push(chunk); return; }
    // 中文切"单字 + 双字"：给句子加主体前缀（如"用户…"）后仍高度重叠，不会被误判为语义冲突
    for (var i = 0; i < chunk.length; i++) {
      terms.push(chunk.charAt(i));
      if (i + 2 <= chunk.length) terms.push(chunk.substr(i, 2));
    }
  });
  return terms;
}

function memoriesSemanticallyDiffer(a, b) {
  var tokensA = tokenizeForCompare(a);
  var tokensB = tokenizeForCompare(b);
  if (!tokensA.length || !tokensB.length) return false;
  var smaller = tokensA.length <= tokensB.length ? tokensA : tokensB;
  var larger = tokensA.length <= tokensB.length ? tokensB : tokensA;
  var hits = smaller.filter(function(term) { return larger.indexOf(term) !== -1; }).length;
  return hits / larger.length < 0.35;
}

function upsertLongTermMemory(char, item, sources, memoryStore, assistantMessage) {
  memoryStore = memoryStore || char.memory;
  if (!isValidAutomaticMemory(char, item, sources, assistantMessage)) return false;
  var characterPromise = item.category === 'promises' && toText(item.promisor) === 'character';
  var resolvedSources = characterPromise
    ? resolvePromiseSources(char, item, assistantMessage, sources)
    : resolveMemorySources(char, item, sources);
  if (!resolvedSources) return false;
  var category = item.category;
  var now = new Date().toISOString();
  var resolvedTime = resolveTimeRef(item.timeRef, item._sourceTs || now);
  var timeBase = (resolvedTime && resolvedTime.iso) ? resolvedTime.iso : (item.eventTime || item._sourceTs || now);
  var key = parseRelativeText(trimText(item.key, 100), timeBase);
  var value = parseRelativeText(trimText(item.value, 900), timeBase);
  if (!key || !value) return false;
  if (!memoryStore.longTerm) memoryStore.longTerm = { userProfile: [], relationship: [], events: [], promises: [], habits: [] };
  if (!memoryStore.longTerm[category]) memoryStore.longTerm[category] = [];
  var list = memoryStore.longTerm[category];
  var eventTime = normalizeTimestamp(item.eventTime, (resolvedTime && resolvedTime.iso) ? resolvedTime.iso : (item._sourceTs || now));
  var participants = normalizeParticipants(item.participants);
  var location = trimText(item.location, 160);
  var eventIdentity = category === 'events' ? getEventIdentity(eventTime, participants, location) : '';
  var existing = list.find(function(memory) {
    var sameKey = toText(memory.key).toLowerCase() === key.toLowerCase();
    return sameKey && (!eventIdentity || getEventIdentity(memory.eventTime, memory.participants, memory.location) === eventIdentity);
  });
  if (category === 'promises' && existing && existing.status !== 'active') return false;
  var importance = Number(item.importance);
  importance = Number.isFinite(importance) ? Math.max(0, Math.min(10, importance)) : 5;
  var tags = Array.isArray(item.tags) ? item.tags.map(function(tag) { return trimText(tag, 30); }).filter(Boolean).slice(0, 8) : [];
  var status = 'active';
  var dueAt = category === 'promises'
    ? (normalizeTimestamp(item.dueAt, null) || ((resolvedTime && resolvedTime.iso) ? resolvedTime.iso : null))
    : null;
  if (existing) {
    var incomingValue = value;
    var existingValue = existing.value || '';
    if (memoriesSemanticallyDiffer(incomingValue, existingValue)) {
      // 语义冲突：不静默覆盖，保留双方证据并标记，交由 resolveMemoryConflicts 自动裁决
      existing.conflicts = Array.isArray(existing.conflicts) ? existing.conflicts.slice(0, 4) : [];
      if (!existing.conflicts.some(function(c) { return c.value === incomingValue; })) {
        existing.conflicts.push({
          value: incomingValue,
          evidence: trimText(item.evidence, 300),
          sourceMessageIds: (item.sourceMessageIds || []).slice(0, 8),
          at: now
        });
      }
      if (!existing.conflictedAt) existing.conflictedAt = now;
      existing.tags = Array.from(new Set((existing.tags || []).concat(tags))).slice(0, 8);
      existing.importance = Math.max(Number(existing.importance) || 0, importance);
      existing.sourceMessageIds = Array.from(new Set((existing.sourceMessageIds || []).concat(item.sourceMessageIds || []))).slice(0, 8);
      if (toText(item.arcOf)) existing.arcOf = toText(item.arcOf);
      if (toText(item.arcStage)) existing.arcStage = toText(item.arcStage);
      existing.updatedAt = eventTime || now;
    } else {
      existing.value = value;
      existing.tags = Array.from(new Set((existing.tags || []).concat(tags))).slice(0, 8);
      existing.importance = Math.max(Number(existing.importance) || 0, importance);
      existing.subject = item.subject;
      existing.sourceMessageIds = item.sourceMessageIds.slice(0, 8);
      existing.sourceRoles = resolvedSources.map(function(entry) { return entry.source.role; });
      existing.evidence = trimText(item.evidence, 300);
      existing.eventTime = eventTime || existing.eventTime || now;
      if (category === 'events') {
        existing.participants = participants;
        existing.location = location;
      }
      if (category === 'promises') {
        existing.status = status;
        existing.dueAt = dueAt || existing.dueAt || null;
        existing.promisor = trimText(item.promisor, 40);
        existing.promisee = trimText(item.promisee, 40);
      }
      existing.updatedAt = eventTime || now;
      if (existing.conflictedAt) existing.conflictedAt = null;
      if (existing.conflicts) existing.conflicts = [];
      if (toText(item.arcOf)) existing.arcOf = toText(item.arcOf);
      if (toText(item.arcStage)) existing.arcStage = toText(item.arcStage);
    }
  } else {
    list.push({
      id: createMemoryId('mem'),
      key: key, value: value, tags: tags, importance: importance,
      subject: item.subject,
      sourceMessageIds: item.sourceMessageIds.slice(0, 8),
      sourceRoles: resolvedSources.map(function(entry) { return entry.source.role; }),
      evidence: trimText(item.evidence, 300),
      eventTime: eventTime || now,
      participants: category === 'events' ? participants : [],
      location: category === 'events' ? location : '',
      dueAt: dueAt,
      promisor: trimText(item.promisor, 40),
      promisee: trimText(item.promisee, 40),
      status: status,
      arcOf: toText(item.arcOf),
      arcStage: toText(item.arcStage),
      recordedAt: now,
      createdAt: eventTime || now, updatedAt: eventTime || now, lastRecalled: null, recallCount: 0, relatedTo: []
    });
  }
  memoryStore.longTerm[category] = pruneMemoryList(list, MEMORY_LIMITS.longTermPerCategory);
  return true;
}

// 冲突自动裁决：全自动合并 conflictedAt 的记忆。胜出规则：更新时间晚 > 证据更长 > 保持原主值。用户全程无感。
function resolveMemoryConflicts(character) {
  if (!character || !character.memory || !character.memory.longTerm) return;
  var now = new Date().toISOString();
  Object.keys(character.memory.longTerm).forEach(function(category) {
    var list = character.memory.longTerm[category];
    if (!Array.isArray(list)) return;
    list.forEach(function(item) {
      var conflicts = Array.isArray(item.conflicts) ? item.conflicts : [];
      if (conflicts.length === 0 || !item.conflictedAt) return;
      var candidates = [{ value: item.value, evidence: item.evidence || '', sourceMessageIds: item.sourceMessageIds || [], updatedAt: item.updatedAt || item.createdAt || '', isMain: true }];
      conflicts.forEach(function(conflict) {
        candidates.push({ value: conflict.value, evidence: conflict.evidence || '', sourceMessageIds: conflict.sourceMessageIds || [], updatedAt: conflict.at || '', isMain: false });
      });
      candidates.sort(function(a, b) {
        var timeA = Date.parse(a.updatedAt) || 0;
        var timeB = Date.parse(b.updatedAt) || 0;
        if (timeA !== timeB) return timeB - timeA;
        var evidenceA = toText(a.evidence).length;
        var evidenceB = toText(b.evidence).length;
        if (evidenceA !== evidenceB) return evidenceB - evidenceA;
        return a.isMain ? -1 : 1;
      });
      var winner = candidates[0];
      if (winner) {
        item.value = winner.value;
        item.evidence = winner.evidence;
        item.sourceMessageIds = Array.from(new Set((item.sourceMessageIds || []).concat(winner.sourceMessageIds))).slice(0, 8);
      }
      item.conflictedAt = null;
      item.conflicts = [];
      item.updatedAt = now;
    });
  });
}

function applyMemoryUpdate(memUpdate, char, assistantMessage) {
  if (!isPlainObject(memUpdate)) return null;

  // 一次性构建已知消息来源表，本轮内所有记忆写入共用，避免逐条重建
  var sources = getKnownSources(char);
  memUpdate._accepted = { shortTerm: 0, longTerm: 0, dynamicState: 0, memberDynamicState: 0, promises: 0 };
  if (Array.isArray(memUpdate.shortTerm)) {
    memUpdate.shortTerm.forEach(function(item) {
      var value = isPlainObject(item) ? (item.content || item.value || item.point) : item;
      if (addShortTermMemory(char, value, new Date().toISOString(), item, sources)) memUpdate._accepted.shortTerm++;
    });
  }
  if (Array.isArray(memUpdate.longTerm)) {
    memUpdate.longTerm.forEach(function(item) {
      if (!isPlainObject(item)) return;
      var targetStore = null;
      if (char.entityType === 'group' && toText(item.memberName)) {
        targetStore = getMemberMemory(char, item.memberName);
        if (!targetStore) return;
      }
      if (upsertLongTermMemory(char, item, sources, targetStore, assistantMessage)) memUpdate._accepted.longTerm++;
    });
  }
  memUpdate._accepted.dynamicState = applyDynamicStateUpdates(char, memUpdate.dynamicState, assistantMessage, null, sources);
  memUpdate._accepted.memberDynamicState = applyMemberDynamicStateUpdates(char, memUpdate.memberDynamicState, assistantMessage, sources);
  memUpdate._accepted.promises = consumePromiseUpdates(char, memUpdate.promiseUpdates, sources);
  if (isPlainObject(memUpdate.recall)) handleRecall(memUpdate.recall, char);
  var staticChangedLabels = applyStaticFieldUpdates(char, memUpdate.staticFields, assistantMessage, sources);
  if (staticChangedLabels.length > 0 && isPlainObject(assistantMessage)) {
    assistantMessage.staticChanges = staticChangedLabels;
  }
  trimCharacterMemory(char);
  return memUpdate;
}

function parseMemoryFromText(content, char, assistantMessage) {
  var memUpdate = null;
  var memMatch = content.match(/<\s*MEM_UPDATE\s*>([\s\S]*?)<\/\s*MEM_UPDATE\s*>/i);
  if (memMatch) {
    try {
      memUpdate = parseJsonPayload(memMatch[1]);
    } catch (e) {
      console.error('Failed to parse memory update JSON:', e);
    }
  }
  return applyMemoryUpdate(memUpdate, char, assistantMessage);
}
