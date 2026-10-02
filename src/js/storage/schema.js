// ==================== 1.2.0 字段结构迁移 ====================
// 旧版动态字段合并到新版 7 个字段（本地确定性兜底，空才回填、幂等）：
//   recentDevelopment -> currentSituation
//   currentFocus      -> currentGoal
//   currentEmotionalTendency -> currentMood
//   currentSocialStyle -> 丢弃（已由静态 speakingStyle 承载）
// 返回被合并的旧字段名数组（用于判断是否触发 AI 迁移弹窗）。
function mergeLegacyDynamicFields(entity) {
  if (!entity || !isPlainObject(entity.dynamicState)) return [];
  var ds = entity.dynamicState;
  var merges = [
    { from: 'recentDevelopment', to: 'currentSituation' },
    { from: 'currentFocus', to: 'currentGoal' },
    { from: 'currentEmotionalTendency', to: 'currentMood' }
  ];
  var found = [];
  merges.forEach(function(pair) {
    var legacyValue = toText(ds[pair.from]).trim();
    if (!legacyValue) return;
    found.push(pair.from);
    var current = toText(ds[pair.to]).trim();
    if (current.indexOf(legacyValue) === -1) {
      ds[pair.to] = current ? (current + ' ' + legacyValue) : legacyValue;
    }
  });
  if (toText(ds.currentSocialStyle).trim()) found.push('currentSocialStyle');
  Object.keys(ds).forEach(function(key) {
    if (DYNAMIC_STATE_FIELDS.some(function(field) { return field.key === key; })) return;
    delete ds[key];
  });
  if (isPlainObject(entity.dynamicStateMeta)) {
    Object.keys(entity.dynamicStateMeta).forEach(function(key) {
      if (DYNAMIC_STATE_FIELDS.some(function(field) { return field.key === key; })) return;
      delete entity.dynamicStateMeta[key];
    });
  }
  return found;
}

function normalizeCharacter(rawCharacter, id, strict, skipLegacyRepair) {
  if (!isPlainObject(rawCharacter)) throw new Error('角色数据无效: ' + id);
  if (strict && !isPlainObject(rawCharacter.basicInfo)) throw new Error('角色基础设定缺失: ' + id);

  var character = createCharacterObj();
  character.id = toText(id, character.id);
  character.entityType = rawCharacter.entityType === 'group' ? 'group' : 'character';
  var rawBasicInfo = isPlainObject(rawCharacter.basicInfo) ? rawCharacter.basicInfo : {};
  Object.keys(character.basicInfo).forEach(function(key) {
    if (key === 'avatarPixel') {
      if (Array.isArray(rawBasicInfo.avatarPixel)) character.basicInfo.avatarPixel = normalizePixelAvatar(rawBasicInfo.avatarPixel);
      return;
    }
    if (key === 'avatar') {
      var rawAvatar = rawBasicInfo[key];
      character.basicInfo.avatar = sanitizeAvatar(rawAvatar, character.entityType === 'group' ? '👥' : '👤');
      if (isAvatarDamaged(rawAvatar)) character.avatarRepairPending = true;
      return;
    }
    character.basicInfo[key] = toText(rawBasicInfo[key], character.basicInfo[key]);
  });
  // 旧版 worldView 迁移：并入 background（空才回填，幂等）
  if (!character.basicInfo.background.trim() && toText(rawBasicInfo.worldView).trim()) {
    character.basicInfo.background = toText(rawBasicInfo.worldView);
  }
  character.basicInfo.userAddress = normalizeUserAddress(character.basicInfo.userAddress);

  var rawDynamicState = isPlainObject(rawCharacter.dynamicState) ? rawCharacter.dynamicState : {};
  var rawDynamicStateMeta = isPlainObject(rawCharacter.dynamicStateMeta) ? rawCharacter.dynamicStateMeta : {};
  var dynamicBaseTime = function(key) {
    var meta = rawDynamicStateMeta[key] || {};
    return meta.updatedAt || null;
  };
  // 旧版本数据迁移：曾存于 basicInfo 的可变字段迁入 dynamicState（空才回填，幂等）
  var legacyStaticToDynamic = {
    occupation: 'currentOccupation',
    goals: 'currentGoal',
    relationshipWithUser: 'currentRelationship',
    importantOthers: 'currentImportantOthers'
  };
  Object.keys(legacyStaticToDynamic).forEach(function(legacyKey) {
    var dynKey = legacyStaticToDynamic[legacyKey];
    if (toText(character.dynamicState[dynKey]).trim() && !toText(rawDynamicState[dynKey]).trim()) return;
    if (!toText(rawDynamicState[dynKey]).trim() && toText(rawBasicInfo[legacyKey]).trim()) {
      character.dynamicState[dynKey] = sanitizeDynamicStateField(dynKey, toText(rawBasicInfo[legacyKey]), dynamicBaseTime(dynKey));
    }
  });
  // scene/sceneNotes 曾存于 basicInfo，合并进 currentLocation
  if (!toText(character.dynamicState.currentLocation).trim() && !toText(rawDynamicState.currentLocation).trim()) {
    var mergedScene = [toText(rawBasicInfo.scene), toText(rawBasicInfo.sceneNotes)].map(function(v) { return v.trim(); }).filter(Boolean).join('\n');
    if (mergedScene) character.dynamicState.currentLocation = mergedScene;
  }
  Object.keys(character.dynamicState).forEach(function(key) {
    var fallback = key === 'currentSituation' ? rawBasicInfo.currentSituation : character.dynamicState[key];
    var rawVal = rawDynamicState[key];
    character.dynamicState[key] = sanitizeDynamicStateField(key, (rawVal == null || (typeof rawVal === 'string' && !rawVal.trim())) ? toText(fallback) : toText(rawVal), dynamicBaseTime(key));
  });
  character.dynamicStateMeta = isPlainObject(rawCharacter.dynamicStateMeta) ? rawCharacter.dynamicStateMeta : {};

  var rawMemory = isPlainObject(rawCharacter.memory) ? rawCharacter.memory : {};
  character.memory.instant = Array.isArray(rawMemory.instant) ? rawMemory.instant.filter(function(message) {
    return isPlainObject(message) && !message.isLoading;
  }).map(function(message, index) {
    return {
      id: toText(message.id, character.id + '_msg_' + index),
       role: message.role === 'user' ? 'user' : 'assistant',
        content: toText(message.content),
        sequence: Number.isSafeInteger(message.sequence) ? message.sequence : 0,
       timestamp: toText(message.timestamp, new Date().toISOString()),
        extractedAt: message.extractedAt ? normalizeTimestamp(message.extractedAt, null) : null,
        images: Array.isArray(message.images) ? message.images.filter(function(uri) { return typeof uri === 'string' && uri; }).slice(0, 4) : undefined,
      internalOnly: message.internalOnly === true ? true : undefined,
      staticChanges: Array.isArray(message.staticChanges) ? message.staticChanges.map(function(label) { return toText(label); }).filter(Boolean) : undefined,
      lorebookChanges: Array.isArray(message.lorebookChanges) ? message.lorebookChanges.map(function(label) { return toText(label); }).filter(Boolean) : undefined,
      styleViolations: Array.isArray(message.styleViolations) ? message.styleViolations.filter(function(key) { return STYLE_VIOLATION_LABELS[key]; }) : undefined,
      quickReplyIssues: Array.isArray(message.quickReplyIssues) ? message.quickReplyIssues.filter(function(key) { return QUICK_REPLY_ISSUE_LABELS[key]; }) : undefined
    };
  }) : [];
  character.memory.shortTerm = Array.isArray(rawMemory.shortTerm) ? rawMemory.shortTerm.filter(isPlainObject).map(function(item) {
    var sourceMessageIds = Array.isArray(item.sourceMessageIds) ? item.sourceMessageIds.map(function(sourceId) { return toText(sourceId); }).filter(Boolean).slice(0, MEMORY_LIMITS.summarySources) : [];
    var userEvidence = Array.isArray(item.userEvidence) ? item.userEvidence.filter(isPlainObject).map(function(entry) {
      return { sourceMessageId: toText(entry.sourceMessageId), text: trimText(entry.text, 300) };
    }).filter(function(entry) { return entry.sourceMessageId && entry.text; }).slice(0, MEMORY_LIMITS.summarySources) : [];
    return {
      id: toText(item.id, createMemoryId('short')),
      content: toText(item.content),
      timestamp: toText(item.timestamp, new Date().toISOString()),
      analyzedAt: Date.parse(item.analyzedAt || '') ? toText(item.analyzedAt) : null,
      lorebookScannedAt: Date.parse(item.lorebookScannedAt || '') ? toText(item.lorebookScannedAt) : null,
      revision: Math.max(1, Number(item.revision) || 1),
      analyzedRevision: Number(item.analyzedRevision) || (item.analyzedAt ? 1 : 0),
      lorebookScannedRevision: Number(item.lorebookScannedRevision) || (item.lorebookScannedAt ? 1 : 0),
      sourceMessageIds: sourceMessageIds,
      sourceRoles: Array.isArray(item.sourceRoles) ? item.sourceRoles.map(function(role) { return role === 'user' || role === 'assistant' ? role : ''; }).slice(0, MEMORY_LIMITS.summarySources) : [],
      userEvidence: userEvidence,
      eventTime: normalizeTimestamp(item.eventTime, toText(item.timestamp, new Date().toISOString())),
      participants: Array.isArray(item.participants) ? item.participants.map(function(value) { return trimText(value, 80); }).filter(Boolean).slice(0, 8) : [],
      location: trimText(item.location, 160),
      _sourceTs: item._sourceTs ? normalizeTimestamp(item._sourceTs, null) : null
    };
  }) : [];

  var rawLongTerm = isPlainObject(rawMemory.longTerm) ? rawMemory.longTerm : {};
  Object.keys(character.memory.longTerm).forEach(function(category) {
    var items = Array.isArray(rawLongTerm[category]) ? rawLongTerm[category] : [];
    character.memory.longTerm[category] = dedupeLongTermList(items.map(normalizeLongTermItem).filter(Boolean), category);
  });

  var rawCounters = isPlainObject(rawMemory.counters) ? rawMemory.counters : {};
  character.memory.counters.extractionRetryAt = normalizeRetryAt(rawCounters.extractionRetryAt);
  character.memory.counters.analysisRetryAt = normalizeRetryAt(rawCounters.analysisRetryAt);
  character.memory.counters.sceneRetryAt = normalizeRetryAt(rawCounters.sceneRetryAt);
  character.memory.counters.lorebookRetryAt = normalizeRetryAt(rawCounters.lorebookRetryAt);
  character.memory.counters.extractionFailures = Number.isFinite(Number(rawCounters.extractionFailures)) ? Math.max(0, Number(rawCounters.extractionFailures)) : 0;
  character.memory.counters.analysisFailures = Number.isFinite(Number(rawCounters.analysisFailures)) ? Math.max(0, Number(rawCounters.analysisFailures)) : 0;
  character.memory.counters.sceneFailures = Number.isFinite(Number(rawCounters.sceneFailures)) ? Math.max(0, Number(rawCounters.sceneFailures)) : 0;
  character.memory.counters.lorebookFailures = Number.isFinite(Number(rawCounters.lorebookFailures)) ? Math.max(0, Number(rawCounters.lorebookFailures)) : 0;
  character.memory.counters.lorebookScannedCount = Number.isFinite(Number(rawCounters.lorebookScannedCount)) ? Math.max(0, Number(rawCounters.lorebookScannedCount)) : 0;
  character.memory.counters.messageSequence = Math.max(0, Number(rawCounters.messageSequence) || 0);
  character.memory.scenes = Array.isArray(rawMemory.scenes) ? rawMemory.scenes.filter(isPlainObject).map(function(scene, index) {
    return {
      id: toText(scene.id, 'scene_' + index),
      key: trimText(toText(scene.key), 80),
      content: trimText(toText(scene.content), 2000),
      startedAt: normalizeTimestamp(scene.startedAt, null),
      endedAt: normalizeTimestamp(scene.endedAt, null),
      createdAt: normalizeTimestamp(scene.createdAt, new Date().toISOString())
    };
  }).filter(function(scene) { return scene.content.trim(); }).slice(-8) : [];
  var rawSceneState = isPlainObject(rawMemory.sceneState) ? rawMemory.sceneState : {};
  character.memory.sceneState = {
    key: toText(rawSceneState.key),
    startCount: Number.isFinite(Number(rawSceneState.startCount)) ? Math.max(0, Number(rawSceneState.startCount)) : 0,
    startSequence: Number.isSafeInteger(rawSceneState.startSequence) ? rawSceneState.startSequence : undefined,
    messageCount: 0
  };
  character.memory.pendingRecall = Array.isArray(rawMemory.pendingRecall) ? rawMemory.pendingRecall.map(normalizeLongTermItem).filter(Boolean) : [];
  character.memory.lastInjectedRecallIds = Array.isArray(rawMemory.lastInjectedRecallIds) ? rawMemory.lastInjectedRecallIds.map(function(id) { return toText(id); }).filter(Boolean).slice(0, MEMORY_LIMITS.pendingRecall) : [];
  character.memory.revision = Number.isFinite(Number(rawMemory.revision)) ? Math.max(0, Number(rawMemory.revision)) : 0;
  ensureMessageSequences(character);
  if (character.entityType === 'group') {
    var rawGroupInfo = isPlainObject(rawCharacter.groupInfo) ? rawCharacter.groupInfo : {};
    // 旧版 worldView 并入 groupInfo.description（空才回填，幂等）
    var groupDescription = toText(rawGroupInfo.description, toText(rawBasicInfo.personality));
    if (toText(rawBasicInfo.worldView).trim() && groupDescription.indexOf(toText(rawBasicInfo.worldView).trim()) === -1) {
      groupDescription = groupDescription ? (groupDescription + '\n' + toText(rawBasicInfo.worldView).trim()) : toText(rawBasicInfo.worldView).trim();
    }
    character.groupInfo = {
      name: toText(rawGroupInfo.name, character.basicInfo.name),
      avatar: sanitizeAvatar(rawGroupInfo.avatar, character.basicInfo.avatar),
      avatarPixel: Array.isArray(rawGroupInfo.avatarPixel) ? normalizePixelAvatar(rawGroupInfo.avatarPixel) : character.basicInfo.avatarPixel,
      description: groupDescription,
      scene: toText(rawGroupInfo.scene, character.dynamicState.currentLocation),
      interactionRules: toText(rawGroupInfo.interactionRules)
    };
    if (isAvatarDamaged(rawGroupInfo.avatar) || isAvatarDamaged(character.basicInfo.avatar)) character.avatarRepairPending = true;
    // 群组实体 basicInfo 不再镜像任何设定：只保留 name/avatar/avatarPixel，其余一律以 groupInfo 为准
    character.basicInfo.name = character.groupInfo.name;
    character.basicInfo.avatar = character.groupInfo.avatar;
    character.basicInfo.avatarPixel = character.groupInfo.avatarPixel;
    character.basicInfo.personality = '';
    character.basicInfo.background = '';
    character.basicInfo.keyEvents = '';
    character.basicInfo.gender = '';
    character.basicInfo.age = '';
    character.basicInfo.race = '';
    character.basicInfo.appearance = '';
    character.basicInfo.values = '';
    character.basicInfo.fears = '';
    character.basicInfo.speakingStyle = '';
    character.basicInfo.language = '';
    character.basicInfo.userAddress = '';
    // 群组动态状态收窄为 2 项；groupInfo.scene 为权威，回填 currentLocation
    Object.keys(character.dynamicState).forEach(function(key) {
      if (GROUP_SHARED_DYNAMIC_FIELDS.indexOf(key) === -1) character.dynamicState[key] = '';
    });
    if (toText(character.groupInfo.scene).trim()) {
      character.dynamicState.currentLocation = character.groupInfo.scene;
    }
    character.members = normalizeGroupMembers(rawCharacter.members);
  }
  // 1.2.0 动态字段结构迁移（本地确定性合并，幂等）
  mergeLegacyDynamicFields(character);
  if (rawCharacter.fieldsMigrationVersion === FIELDS_MIGRATION_VERSION) {
    character.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION;
  }
  if (!skipLegacyRepair) {
    var repairCount = reconcileLegacyMemories(character);
    convertLegacyRelativeTimes(character);
    if (repairCount > 0) {
      character.memory.revision = (Number(character.memory.revision) || 0) + 1;
      if (!window.__legacyMemoryRepairs) window.__legacyMemoryRepairs = 0;
      window.__legacyMemoryRepairs += repairCount;
    }
  }
  if (rawCharacter.avatarRepairPending) character.avatarRepairPending = true;
  if (isPlainObject(rawCharacter.staticFillMeta)) character.staticFillMeta = rawCharacter.staticFillMeta;
  if (isPlainObject(rawCharacter.staticFieldMeta)) character.staticFieldMeta = rawCharacter.staticFieldMeta;
  if (Number(rawCharacter.timeParseVersion) === TIME_PARSE_VERSION) character.timeParseVersion = TIME_PARSE_VERSION;
  character.stickers = normalizeStickers(rawCharacter.stickers);
  character.lorebook = normalizeLorebook(rawCharacter.lorebook);
  character.lorebookMigratedAt = normalizeTimestamp(rawCharacter.lorebookMigratedAt, null);
  return character;
}

function normalizeGroupMembers(items, cleanGenerated) {
  var cleanFields = cleanGenerated === true;
  return (Array.isArray(items) ? items : []).filter(isPlainObject).map(function(item, index) {
    var member = createCharacterObj();
    member.id = toText(item.id, 'member_' + Date.now() + '_' + index);
    var source = isPlainObject(item.basicInfo) ? item.basicInfo : item;
    Object.keys(member.basicInfo).forEach(function(key) {
      if (key === 'avatarPixel') {
        if (Array.isArray(source.avatarPixel)) member.basicInfo.avatarPixel = normalizePixelAvatar(source.avatarPixel);
        return;
      }
      if (key === 'avatar') {
        member.basicInfo.avatar = sanitizeAvatar(source[key], '👤');
        if (isAvatarDamaged(source[key])) member.avatarRepairPending = true;
        return;
      }
      member.basicInfo[key] = cleanFields ? cleanFieldValue(key, toText(source[key], member.basicInfo[key])) : toText(source[key], member.basicInfo[key]);
    });
    member.basicInfo.userAddress = normalizeUserAddress(member.basicInfo.userAddress);
    var state = isPlainObject(item.dynamicState) ? item.dynamicState : {};
    var memberDynamicStateMeta = isPlainObject(item.dynamicStateMeta) ? item.dynamicStateMeta : {};
    var memberDynamicBaseTime = function(key) {
      var meta = memberDynamicStateMeta[key] || {};
      return meta.updatedAt || null;
    };
    // 旧版本群组成员数据迁移：曾存于 basicInfo 的可变字段迁入 dynamicState（空才回填）
    var legacyStaticToDynamic = {
      occupation: 'currentOccupation',
      goals: 'currentGoal',
      relationshipWithUser: 'currentRelationship',
      importantOthers: 'currentImportantOthers'
    };
    Object.keys(legacyStaticToDynamic).forEach(function(legacyKey) {
      var dynKey = legacyStaticToDynamic[legacyKey];
      if (!toText(member.dynamicState[dynKey]).trim() && toText(source[legacyKey]).trim()) {
        member.dynamicState[dynKey] = sanitizeDynamicStateField(dynKey, toText(source[legacyKey]), memberDynamicBaseTime(dynKey));
      }
    });
    if (!toText(member.dynamicState.currentLocation).trim()) {
      var mergedScene = [toText(source.scene), toText(source.sceneNotes)].map(function(v) { return v.trim(); }).filter(Boolean).join('\n');
      if (mergedScene) member.dynamicState.currentLocation = mergedScene;
    }
    Object.keys(member.dynamicState).forEach(function(key) {
      var rawVal = state[key];
      member.dynamicState[key] = sanitizeDynamicStateField(key, (rawVal == null || (typeof rawVal === 'string' && !rawVal.trim())) ? toText(member.dynamicState[key]) : toText(rawVal), memberDynamicBaseTime(key));
    });
    member.dynamicStateMeta = isPlainObject(item.dynamicStateMeta) ? item.dynamicStateMeta : {};
    member.roleInGroup = cleanFields ? cleanFieldValue('roleInGroup', toText(item.roleInGroup)) : toText(item.roleInGroup);
    member.lorebook = normalizeLorebook(item.lorebook);
    if (item.avatarRepairPending) member.avatarRepairPending = true;
    if (isPlainObject(item.staticFillMeta)) member.staticFillMeta = item.staticFillMeta;
    if (isPlainObject(item.staticFieldMeta)) member.staticFieldMeta = item.staticFieldMeta;
    if (Number(item.timeParseVersion) === TIME_PARSE_VERSION) member.timeParseVersion = TIME_PARSE_VERSION;
    mergeLegacyDynamicFields(member);
    if (item.fieldsMigrationVersion === FIELDS_MIGRATION_VERSION) member.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION;
    var rawMemberMemory = isPlainObject(item.memory) ? item.memory : null;
    if (rawMemberMemory) {
      var memberLongTerm = isPlainObject(rawMemberMemory.longTerm) ? rawMemberMemory.longTerm : {};
      Object.keys(member.memory.longTerm).forEach(function(category) {
        member.memory.longTerm[category] = dedupeLongTermList(Array.isArray(memberLongTerm[category]) ? memberLongTerm[category].map(normalizeLongTermItem).filter(Boolean) : [], category);
      });
      if (Array.isArray(rawMemberMemory.pendingRecall)) member.memory.pendingRecall = rawMemberMemory.pendingRecall.slice(0, MEMORY_LIMITS.pendingRecall);
    }
    return member;
  }).filter(function(member) { return member.basicInfo.name; }).slice(0, 8);
}

function normalizeStickers(raw) {
  if (!Array.isArray(raw)) return [];
  var result = [];
  var seen = {};
  raw.forEach(function(item) {
    if (!isPlainObject(item)) return;
    var dataUrl = toText(item.dataUrl);
    if (!/^data:image\//i.test(dataUrl)) return;
    if (seen[dataUrl]) return;
    seen[dataUrl] = true;
    var tag = toText(item.tag).replace(/[\s\r\n]+/g, '').slice(0, STICKER_LIMITS.maxTagChars) || '未分类';
    result.push({
      id: toText(item.id) || createMemoryId('stk'),
      dataUrl: dataUrl,
      tag: tag,
      createdAt: normalizeTimestamp(item.createdAt, new Date().toISOString()),
      updatedAt: normalizeTimestamp(item.updatedAt, item.createdAt ? toText(item.createdAt) : new Date().toISOString())
    });
  });
  return result;
}

function normalizeAppData(data, strict, skipLegacyRepair) {
  if (!isPlainObject(data)) throw new Error('数据根节点格式错误');
  if (strict && !isPlainObject(data.characters)) throw new Error('characters 字段缺失或格式错误');
  if (data.config != null && !isPlainObject(data.config)) throw new Error('config 字段格式错误');

  var rawCharacters = data.characters == null ? {} : data.characters;
  if (!isPlainObject(rawCharacters)) throw new Error('characters 字段格式错误');
  var characters = Object.create(null);
  collectFieldMigrationTargets(rawCharacters);
  Object.keys(rawCharacters).forEach(function(id) {
    characters[id] = normalizeCharacter(rawCharacters[id], id, strict, skipLegacyRepair);
  });

  var rawConfig = data.config || {};
  var platform = toText(rawConfig.apiPlatform, DEFAULT_CONFIG.apiPlatform);
  if (!PLATFORM_CONFIGS[platform]) platform = 'custom';
  var modelName = toText(rawConfig.modelName, DEFAULT_CONFIG.modelName);
  if (platform === 'deepseek' && modelName === 'deepseek-chat') modelName = DEFAULT_CONFIG.modelName;
  var apiBaseUrl = normalizeApiBaseUrl(rawConfig.apiBaseUrl || rawConfig.apiEndpoint || PLATFORM_CONFIGS[platform].baseUrl || DEFAULT_CONFIG.apiBaseUrl);
  // 每平台独立配置槽：切换平台时各自的 baseUrl/apiKey/modelName 不互相覆盖
  var platformSettings = {};
  var rawPlatformSettings = isPlainObject(rawConfig.platformSettings) ? rawConfig.platformSettings : {};
  Object.keys(PLATFORM_CONFIGS).forEach(function(key) {
    var raw = isPlainObject(rawPlatformSettings[key]) ? rawPlatformSettings[key] : {};
    var preset = PLATFORM_CONFIGS[key] || {};
    platformSettings[key] = {
      baseUrl: normalizeApiBaseUrl(raw.baseUrl) || preset.baseUrl || '',
      apiKey: toText(raw.apiKey, preset.apiKey || ''),
      modelName: toText(raw.modelName) || preset.model || ''
    };
  });
  // 老配置兼容：把主配置里已保存的 baseUrl/apiKey/modelName 回填到当前平台槽，避免升级后丢失
  if (!platformSettings[platform].baseUrl && apiBaseUrl) platformSettings[platform].baseUrl = apiBaseUrl;
  if (!platformSettings[platform].apiKey && toText(rawConfig.apiKey)) platformSettings[platform].apiKey = toText(rawConfig.apiKey);
  if (!platformSettings[platform].modelName && modelName) platformSettings[platform].modelName = modelName;
  var activeCharacterId = data.activeCharacterId == null ? null : toText(data.activeCharacterId);
  if (!activeCharacterId || !characters[activeCharacterId]) activeCharacterId = Object.keys(characters)[0] || null;
  var activeTheme = ['theme-black', 'theme-blue', 'theme-yellow'].indexOf(data.activeTheme) !== -1 ? data.activeTheme : '';

  // 旧版全局表情包迁移：并入当前激活角色/群组（一次性；新存档不再写全局字段）
  var legacyStickers = normalizeStickers(data.stickers);
  if (legacyStickers.length > 0 && activeCharacterId && characters[activeCharacterId] && characters[activeCharacterId].stickers.length === 0) {
    characters[activeCharacterId].stickers = legacyStickers;
  }

  return {
    config: {
      apiPlatform: platform,
      apiBaseUrl: apiBaseUrl,
      apiKey: toText(rawConfig.apiKey),
      modelName: modelName,
       temperature: normalizeTemperature(rawConfig.temperature),
       stream: rawConfig.stream !== false,
       reasoningEffort: normalizeReasoningEffort(rawConfig.reasoningEffort),
       proactiveEnabled: rawConfig.proactiveEnabled !== false,
       styleCritique: rawConfig.styleCritique !== false,
       quickReplyRepair: rawConfig.quickReplyRepair !== false,
        cacheStats: normalizeCacheStats(rawConfig.cacheStats),
        requestMetrics: Array.isArray(rawConfig.requestMetrics) ? rawConfig.requestMetrics.filter(isPlainObject).slice(-60) : [],
       platformSettings: platformSettings
    },
    characters: characters,
    activeCharacterId: activeCharacterId,
    activeTheme: activeTheme
  };
}

function applyNormalizedData(data, isImport) {
  // 只有「导入」时备份/分享文件不含 API 密钥，才需要保留本地 Key、剔除文件里的 Key。
  // 正常「加载」时必须原样使用本地已存数据，否则会把未使用供应商的 Key 一并删掉。
  var localApiKey = state.config && state.config.apiKey;
  var localSettings = (state.config && state.config.platformSettings) || {};
  var localSlotKeys = {};
  Object.keys(localSettings).forEach(function(plat) {
    if (localSettings[plat] && localSettings[plat].apiKey) {
      localSlotKeys[plat] = localSettings[plat].apiKey;
    }
  });
  state.config = data.config;
  if (!state.config.platformSettings) state.config.platformSettings = {};
  if (isImport) {
    // 各平台槽：优先用本地已有 key，导入文件里的 key 一律不用
    Object.keys(state.config.platformSettings).forEach(function(plat) {
      var slot = state.config.platformSettings[plat];
      if (slot) {
        if (localSlotKeys[plat]) {
          slot.apiKey = localSlotKeys[plat];
        } else {
          delete slot.apiKey;
        }
      }
    });
    if (!state.config.apiKey && localApiKey) {
      state.config.apiKey = localApiKey;
      if (state.config.platformSettings[state.config.apiPlatform]) {
        state.config.platformSettings[state.config.apiPlatform].apiKey = localApiKey;
      }
    }
  }
  state.characters = data.characters;
  state.activeCharacterId = data.activeCharacterId;
  state.activeTheme = data.activeTheme;
  var repairs = window.__legacyMemoryRepairs || 0;
  if (repairs > 0) {
    window.__legacyMemoryRepairs = 0;
    alert('检测到 ' + repairs + ' 条旧格式记忆，已自动修补为新的事件格式。');
  }
}
