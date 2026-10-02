// 记忆衰减：按距上次召回的时长衰减有效重要性（未召回越久权重越低）
function computeEffectiveImportance(item, now) {
  now = now || Date.now();
  var importance = Number(item.importance);
  if (!Number.isFinite(importance)) importance = 0;
  var lastRef = Date.parse(item.lastRecalled || item.updatedAt || item.createdAt || '') || 0;
  var daysSince = lastRef ? Math.max(0, (now - lastRef) / 86400000) : 999;
  // 30 天内基本不衰减；30~180 天线性衰减至原始的一半；180 天后衰减至 10%
  var decay = 1;
  if (daysSince > 180) decay = 0.1;
  else if (daysSince > 30) decay = 1 - 0.9 * (daysSince - 30) / 150;
  var learnedBonus = Number.isFinite(Number(item.learnedBonus)) ? Number(item.learnedBonus) : 0;
  var baseImportance = Math.max(0, Math.min(10, importance + learnedBonus));
  return baseImportance * decay;
}

function memorySortScore(item, now) {
  var importance = computeEffectiveImportance(item, now);
  var recallCount = Number(item.recallCount);
  var updatedAt = Date.parse(item.updatedAt || item.createdAt || '') || 0;
  return importance * 1000000000000 +
    (Number.isFinite(recallCount) ? recallCount : 0) * 100000000000 + updatedAt;
}

function pruneMemoryList(items, limit) {
  if (!Array.isArray(items)) return [];
  if (items.length <= limit) return items;
  return items.slice().sort(function(a, b) {
    return memorySortScore(b) - memorySortScore(a);
  }).slice(0, limit);
}

function dedupeLongTermList(items, category) {
  var result = [];
  var seen = new Map();
  (items || []).forEach(function(item) {
    var identity = category === 'events'
      ? getEventIdentity(item.eventTime, item.participants, item.location) + ':' + trimText(item.key).toLowerCase()
      : trimText(item.key).toLowerCase();
    if (!identity || !seen.has(identity)) {
      result.push(item);
      if (identity) seen.set(identity, item);
      return;
    }
    var existing = seen.get(identity);
    var existingTime = Date.parse(existing.updatedAt || existing.createdAt || '') || 0;
    var itemTime = Date.parse(item.updatedAt || item.createdAt || '') || 0;
    if (category === 'events') {
      existing.value = mergeTimelineContent(existing, item);
      existing.eventTime = earlierTimestamp(existing.eventTime, item.eventTime);
      existing.participants = Array.from(new Set(normalizeParticipants((existing.participants || []).concat(item.participants || [])))).slice(0, 8);
      existing.location = mergeLocations(existing.location, item.location);
    } else if (itemTime >= existingTime) {
      if (existing.conflictedAt || item.conflictedAt) {
        existing.conflicts = Array.from(new Set((existing.conflicts || []).concat(item.conflicts || []))).slice(0, 4);
        if (item.value !== existing.value && !existing.conflicts.some(function(c) { return c.value === item.value; })) {
          existing.conflicts.push({ value: item.value, evidence: item.evidence || '', sourceMessageIds: item.sourceMessageIds || [], at: item.updatedAt || item.createdAt || '' });
        }
        existing.conflictedAt = existing.conflictedAt || item.conflictedAt || item.updatedAt || existing.updatedAt;
        existing.sourceMessageIds = Array.from(new Set((existing.sourceMessageIds || []).concat(item.sourceMessageIds || []))).slice(0, 8);
      } else {
        existing.value = item.value || existing.value;
        existing.subject = item.subject || existing.subject;
        existing.sourceMessageIds = item.sourceMessageIds || existing.sourceMessageIds;
        existing.sourceRoles = item.sourceRoles || existing.sourceRoles;
        existing.evidence = item.evidence || existing.evidence;
        existing.status = item.status || existing.status;
        existing.dueAt = item.dueAt || existing.dueAt;
        existing.updatedAt = item.updatedAt || item.createdAt || existing.updatedAt;
      }
    }
    existing.tags = Array.from(new Set((existing.tags || []).concat(item.tags || []))).slice(0, 8);
    existing.importance = Math.max(Number(existing.importance) || 0, Number(item.importance) || 0);
  });
  return result;
}

// Both consumers must acknowledge the same summary revision before pruning.
function trimShortTermList(list) {
  if (!Array.isArray(list)) return [];
  var recentStart = Math.max(0, list.length - MEMORY_LIMITS.shortTermTrimFloor);
  return list.filter(function(item, index) {
    var revision = Number(item.revision) || 1;
    var analyzed = item.analyzedAt && (Number(item.analyzedRevision) || 1) === revision;
    var scanned = item.lorebookScannedAt && (Number(item.lorebookScannedRevision) || 1) === revision;
    return index >= recentStart || !analyzed || !scanned;
  });
}

function ensureMessageSequences(character) {
  var memory = character.memory;
  if (!isPlainObject(memory.counters)) memory.counters = {};
  var last = 0;
  var next = Math.max(0, Number(memory.counters.messageSequence) || 0);
  (memory.instant || []).forEach(function(message) {
    var sequence = Number(message.sequence);
    if (!Number.isSafeInteger(sequence) || sequence <= last) sequence = Math.max(last, next) + 1;
    message.sequence = sequence;
    last = sequence;
    next = Math.max(next, sequence);
  });
  memory.counters.messageSequence = next;
  if (!isPlainObject(memory.sceneState)) memory.sceneState = { key: '', startCount: 0, messageCount: 0 };
  var scene = memory.sceneState;
  if (!Number.isSafeInteger(scene.startSequence)) {
    var scenes = memory.scenes || [];
    var end = scenes.length ? Date.parse(scenes[scenes.length - 1].endedAt || '') : NaN;
    var anchor = 0;
    if (Number.isFinite(end)) {
      (memory.instant || []).forEach(function(message) {
        if (Date.parse(message.timestamp || '') <= end) anchor = message.sequence;
      });
    } else if (scene.startCount > 0 && scene.startCount <= memory.instant.length) {
      anchor = memory.instant[scene.startCount - 1].sequence;
    }
    scene.startSequence = anchor;
  }
  scene.startSequence = Math.max(0, Math.min(next, scene.startSequence));
}

function captureMemoryTask(char) {
  return { character: char, revision: Number(char.memory.revision) || 0,
    lastMessageId: char.memory.instant.length ? char.memory.instant[char.memory.instant.length - 1].id : null };
}

function isMemoryTaskCurrent(task) {
  var char = task && task.character;
  if (!char || state.characters[char.id] !== char || (Number(char.memory.revision) || 0) !== task.revision) return false;
  var last = char.memory.instant.length ? char.memory.instant[char.memory.instant.length - 1].id : null;
  return last === task.lastMessageId;
}

function trimCharacterMemory(character) {
  if (!character || !character.memory) return;
  character.memory.instant = Array.isArray(character.memory.instant) ? character.memory.instant : [];
  ensureMessageSequences(character);
  // Batch trim at the high watermark: only remove extracted messages down to the low
  // watermark, one pass, FIFO. Un-extracted messages are never dropped.
  if (character.memory.instant.length >= MEMORY_LIMITS.instant) {
    var trimFloor = Number(MEMORY_LIMITS.instantTrimFloor);
    if (!Number.isFinite(trimFloor) || trimFloor < 0) trimFloor = MEMORY_LIMITS.instant - 60;
    var removeCount = Math.max(0, character.memory.instant.length - trimFloor);
    var removed = 0;
    character.memory.instant = character.memory.instant.filter(function(message) {
      if (removed < removeCount && Boolean(message.extractedAt)) {
        removed++;
        return false;
      }
      return true;
    });
  }
  character.memory.shortTerm = trimShortTermList(character.memory.shortTerm);
  resolveMemoryConflicts(character);
  character.memory.pendingRecall = Array.isArray(character.memory.pendingRecall) ? character.memory.pendingRecall.slice(0, MEMORY_LIMITS.pendingRecall) : [];
  if (character.entityType === 'group' && Array.isArray(character.members)) {
    character.members.forEach(function(member) {
      if (!member || !member.memory || !member.memory.longTerm) return;
      resolveMemoryConflicts(member);
    });
  }
}

function getMemberMemory(char, memberName) {
  if (char.entityType !== 'group' || !Array.isArray(char.members)) return null;
  var normalizedName = toText(memberName).toLowerCase();
  var member = char.members.find(function(candidate) { return toText(candidate.basicInfo.name).toLowerCase() === normalizedName; });
  if (!member) return null;
  if (!member.memory) member.memory = { instant: [], shortTerm: [], longTerm: { userProfile: [], relationship: [], events: [], promises: [], habits: [] }, counters: {}, pendingRecall: [], lastInjectedRecallIds: [], revision: 0 };
  if (!member.memory.longTerm) member.memory.longTerm = { userProfile: [], relationship: [], events: [], promises: [], habits: [] };
  return member.memory;
}

// 记忆文本的"主体宿主"：群组里指定成员时用该成员（其 userAddress 决定称呼），否则用当前实体
function resolveMemoryHost(char, memberName) {
  if (char && char.entityType === 'group' && Array.isArray(char.members) && toText(memberName).trim()) {
    var wanted = toText(memberName).trim().toLowerCase();
    var found = char.members.find(function(candidate) {
      return toText(candidate.basicInfo && candidate.basicInfo.name).toLowerCase() === wanted;
    });
    if (found) return found;
  }
  return char;
}

// 记忆遗忘机制：长期未被召回的陈年记忆逐步衰减并最终移除。
// - events（历史事件）：随时间推移遗忘（不再被 recall 的重要事件保留更久）
// - promises：带截止时间的承诺过期且长期未提及则清理
// - userProfile / relationship / habits：视为长期资产，只降权不自动删除
function applyMemoryDecay(character, memoryStore) {
  memoryStore = memoryStore || (character && character.memory);
  if (!memoryStore || !memoryStore.longTerm) return;
  var now = Date.now();
  var DAY = 86400000;
  Object.keys(memoryStore.longTerm).forEach(function(category) {
    var list = memoryStore.longTerm[category];
    if (!Array.isArray(list)) return;
    memoryStore.longTerm[category] = list.filter(function(item) {
      var lastRef = Date.parse(item.lastRecalled || item.updatedAt || item.createdAt || '') || 0;
      var daysSince = lastRef ? (now - lastRef) / DAY : 9999;
      if (category === 'events') {
        var importance = Number(item.importance) || 0;
        var maxAge = 30 + importance * 15; // 低重要事件 ~45 天遗忘，高重要事件最长 ~180 天
        return daysSince < maxAge;
      }
      if (category === 'promises') {
        var status = item.status || 'active';
        if (status !== 'active') return daysSince < 90; // 已结束的承诺 90 天清理
        var due = Date.parse(item.dueAt || '') || 0;
        if (due && due < now && daysSince > 30) return false; // 过期且 30 天未提及则遗忘
        return true;
      }
      return true; // userProfile/relationship/habits 不自动删除
    });
  });
}
