// ==================== Data Persistence ====================
function saveData() {
  try {
    const dataToSave = {
      config: state.config,
      characters: state.characters,
      activeCharacterId: state.activeCharacterId,
      activeTheme: state.activeTheme,
      version: APP_VERSION
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(dataToSave));
    saveData._warned = false;
    return true;
  } catch (e) {
    console.error('saveData failed:', e);
    if (!saveData._warned) {
      saveData._warned = true;
      alert('数据保存失败，本地存储可能不可用或空间已满。请及时导出备份。');
    }
    return false;
  }
}

// 保存防抖：短时间内多次变更合并为一次落盘，避免每轮 3-4 次全量序列化卡顿
var saveTimer = null;
var savePending = false;
function scheduleSave() {
  savePending = true;
  if (saveTimer) return;
  saveTimer = setTimeout(function() {
    saveTimer = null;
    savePending = false;
    saveData();
  }, 400);
}
function flushScheduledSave() {
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  if (savePending) { savePending = false; saveData(); }
}

function loadData() {
  var saved = null;
  try {
    saved = localStorage.getItem(STORAGE_KEY);
    if (saved) {
      var parsedData = JSON.parse(saved);
      var hasVersion = parsedData && parsedData.version != null;
      applyNormalizedData(normalizeAppData(parsedData, false, hasVersion), false);
    }
  } catch (e) {
    console.error('loadData failed:', e);
    if (saved) {
      try {
        localStorage.setItem(STORAGE_RECOVERY_KEY, saved);
      } catch (backupError) {
        console.error('Failed to preserve damaged data:', backupError);
      }
    }
    if (!loadData._warned) {
      loadData._warned = true;
      alert('本地数据无法读取，页面已使用空白状态启动。原始内容未删除，并尝试保留在恢复备份中。');
    }
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function toText(value, fallback) {
  if (value == null) return fallback || '';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch (e) {
      return fallback || '';
    }
  }
  return String(value);
}

function normalizeLongTermItem(item) {
  if (!isPlainObject(item)) return null;
  var importance = Number(item.importance);
  var recallCount = Number(item.recallCount);
  return {
    id: toText(item.id, createMemoryId('mem')),
    key: toText(item.key),
    value: toText(item.value),
    tags: Array.isArray(item.tags) ? item.tags.map(function(tag) { return toText(tag); }) : [],
    importance: Number.isFinite(importance) ? Math.max(0, Math.min(10, importance)) : 0,
    subject: MEMORY_SUBJECTS.indexOf(toText(item.subject)) !== -1 ? toText(item.subject) : 'legacy',
    sourceMessageIds: Array.isArray(item.sourceMessageIds) ? item.sourceMessageIds.map(function(id) { return toText(id); }).filter(Boolean).slice(0, 8) : [],
    sourceRoles: Array.isArray(item.sourceRoles) ? item.sourceRoles.filter(function(role) { return role === 'user' || role === 'assistant'; }).slice(0, 8) : [],
    evidence: trimText(item.evidence, 300),
    eventTime: normalizeTimestamp(item.eventTime, toText(item.createdAt, new Date().toISOString())),
    participants: Array.isArray(item.participants) ? item.participants.map(function(value) { return trimText(value, 80); }).filter(Boolean).slice(0, 8) : [],
    location: trimText(item.location, 160),
    dueAt: item.dueAt ? normalizeTimestamp(item.dueAt, null) : null,
    promisor: trimText(item.promisor, 40),
    promisee: trimText(item.promisee, 40),
    status: PROMISE_STATUSES.indexOf(toText(item.status)) !== -1 ? toText(item.status) : 'active',
    recordedAt: toText(item.recordedAt, item.createdAt || new Date().toISOString()),
    createdAt: toText(item.createdAt, new Date().toISOString()),
    updatedAt: toText(item.updatedAt, item.createdAt || new Date().toISOString()),
    lastRecalled: item.lastRecalled ? toText(item.lastRecalled) : null,
    recallCount: Number.isFinite(recallCount) && recallCount >= 0 ? recallCount : 0,
    usageCount: Number.isFinite(Number(item.usageCount)) && Number(item.usageCount) >= 0 ? Number(item.usageCount) : 0,
    lastUsageAt: item.lastUsageAt ? toText(item.lastUsageAt) : null,
    learnedBonus: Number.isFinite(Number(item.learnedBonus)) ? Math.max(-3, Math.min(3, Number(item.learnedBonus))) : 0,
    conflictedAt: item.conflictedAt ? toText(item.conflictedAt) : null,
    conflicts: Array.isArray(item.conflicts) ? item.conflicts.map(function(c) { return isPlainObject(c) ? c : null; }).filter(Boolean).slice(0, 4) : [],
    arcOf: toText(item.arcOf),
    arcStage: toText(item.arcStage),
    relatedTo: Array.isArray(item.relatedTo) ? item.relatedTo.map(function(id) { return toText(id); }) : []
  };
}

