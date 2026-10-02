// ==================== Character Management ====================
function createCharacterObj(name, avatar, personality, background) {
  var id = 'char_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
  return {
    id: id,
    entityType: 'character',
    basicInfo: {
      name: name || '新角色', avatar: avatar || '👤', avatarPixel: null, gender: '', age: '', race: '',
      appearance: '', personality: personality || '', values: '',
      fears: '', background: background || '', keyEvents: '',
      speakingStyle: '', language: '',
      userAddress: ''
    },
    dynamicState: { currentSituation: '', currentLocation: '', currentMood: '', currentOccupation: '', currentGoal: '', currentRelationship: '', currentImportantOthers: '' },
    dynamicStateMeta: {},
    stickers: [],
    lorebook: [],
    memory: {
      instant: [], shortTerm: [],
      longTerm: { userProfile: [], relationship: [], events: [], promises: [], habits: [] },
      scenes: [],
      sceneState: { key: '', startCount: 0, messageCount: 0 },
      counters: { extractionRetryAt: null, analysisRetryAt: null, sceneRetryAt: null, lorebookRetryAt: null, extractionFailures: 0, analysisFailures: 0, sceneFailures: 0, lorebookFailures: 0, lorebookScannedCount: 0 },
      pendingRecall: [],
      lastInjectedRecallIds: [],
      revision: 0
    }
  };
}

// 群组实体：basicInfo 只保留 name/avatar/avatarPixel，其余设定一律以 groupInfo 为准（不再镜像）
function createGroupObj(name, avatar, description, members, cleanMembers) {
  var group = createCharacterObj(name || '新群组', avatar || '👥', description || '', '');
  group.entityType = 'group';
  group.basicInfo.personality = '';
  group.basicInfo.background = '';
  group.groupInfo = {
    name: group.basicInfo.name,
    avatar: group.basicInfo.avatar,
    avatarPixel: group.basicInfo.avatarPixel,
    description: description || '',
    scene: '',
    interactionRules: ''
  };
  group.members = normalizeGroupMembers(members, cleanMembers === true);
  return group;
}

function addCharacter(character) {
  // 新建/升级产生的实体天然是新结构，直接标记为已迁移，避免下次启动又被询问
  if (character) {
    character.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION;
    (character.members || []).forEach(function(member) { member.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION; });
  }
  state.characters[character.id] = character;
  scheduleSave();
  renderCharacterList();
  selectCharacter(character.id);
}

function deleteCharacter(id) {
  if (!confirm('确定删除该角色及其所有记忆数据？')) return;
  delete state.characters[id];
  if (state.activeCharacterId === id) state.activeCharacterId = null;
  scheduleSave();
  renderCharacterList();
  renderChat();
}

function selectCharacter(id) {
  if (!state.characters[id]) return;
  invalidateQuickReplies();
  state.activeCharacterId = id;
  state.editingStickerId = null;
  toggleStickerPanel(false);
  scheduleSave();
  renderCharacterList();
  renderChat();
  scheduleProactiveCheck();
  if (window.innerWidth < 768) toggleSidebar(false);
}

