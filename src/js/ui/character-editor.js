// ==================== Character Modal Logic ====================
function openCharacterModal(id) {
  var char = state.characters[id];
  if (!char) return;
  state.editingCharacterId = id;
  state.editingGroupMemberIndex = null;
  state.editingCharacterDraft = JSON.parse(JSON.stringify(char));
  document.getElementById('charModalTitle').textContent = '编辑: ' + char.basicInfo.name;
  renderCharacterModalContent(state.editingCharacterDraft);
  document.getElementById('characterModal').classList.remove('hidden');
  document.getElementById('characterModal').classList.add('flex');
  switchModalTab('basic');
}

function openGroupMemberModal(groupId, memberIndex) {
  var group = state.characters[groupId];
  var member = group && group.entityType === 'group' && group.members ? group.members[memberIndex] : null;
  if (!member) return;
  state.editingCharacterId = groupId;
  state.editingGroupMemberIndex = memberIndex;
  state.editingCharacterDraft = JSON.parse(JSON.stringify(member));
  document.getElementById('charModalTitle').textContent = '编辑成员: ' + member.basicInfo.name;
  renderCharacterModalContent(state.editingCharacterDraft);
  document.getElementById('characterModal').classList.remove('hidden');
  document.getElementById('characterModal').classList.add('flex');
  switchModalTab('basic');
}

function closeCharacterModal() {
  document.getElementById('characterModal').classList.add('hidden');
  document.getElementById('characterModal').classList.remove('flex');
  state.editingCharacterId = null;
  state.editingCharacterDraft = null;
  state.editingGroupMemberIndex = null;
}

function switchModalTab(tabName) {
  ['basic', 'state', 'lorebook'].forEach(function(name) {
    document.getElementById('modal-tab-' + name).classList.remove('active');
    document.getElementById('modal-content-' + name).classList.remove('active');
  });
  document.getElementById('modal-tab-' + tabName).classList.add('active');
  document.getElementById('modal-content-' + tabName).classList.add('active');
}

// 世界书编辑：条目直接写回草稿（保存时随角色一起落盘）
// 用户一旦手改内容，条目就转为 origin='user'：从此 AI 不得覆盖，也不会被自动淘汰
function updateLorebookEntry(index, field, value) {
  var draft = state.editingCharacterDraft;
  if (!draft || !Array.isArray(draft.lorebook) || !draft.lorebook[index]) return;
  var entry = draft.lorebook[index];
  if (field === 'keywords') {
    entry.keywords = toText(value).split(/[,，、\n]/).map(function(keyword) { return keyword.trim(); }).filter(Boolean).slice(0, LOREBOOK_LIMITS.keywordsPerEntry);
    if (!(entry.alwaysActive === true) && entry.keywords.length === 0) entry.alwaysActive = true;
    entry.origin = 'user';
  } else if (field === 'enabled') {
    entry.enabled = value === true;
  } else if (field === 'alwaysActive') {
    entry.alwaysActive = value === true;
  } else if (field === 'name') {
    entry.name = toText(value).slice(0, LOREBOOK_LIMITS.nameChars);
    entry.origin = 'user';
  } else if (field === 'content') {
    entry.content = toText(value).slice(0, LOREBOOK_LIMITS.contentChars);
    entry.origin = 'user';
  }
  return entry;
}

function addLorebookEntry() {
  var draft = state.editingCharacterDraft;
  if (!draft) return;
  if (!Array.isArray(draft.lorebook)) draft.lorebook = [];
  if (draft.lorebook.length >= LOREBOOK_LIMITS.entries) { alert('世界书条目已达上限（' + LOREBOOK_LIMITS.entries + ' 条）'); return; }
  draft.lorebook.push({ id: createMemoryId('lore'), name: '', keywords: [], content: '', enabled: true, order: 100, alwaysActive: true, origin: 'user', mentions: 0, lastMentionedAt: null, misses: 0 });
  renderLorebookEditor(draft);
}

// "最近提及"显示：没被命中过就标未命中，不展示精确时间
function formatLorebookMention(entry) {
  if (!entry || !Number(entry.mentions)) return '未命中过';
  var when = Date.parse(entry.lastMentionedAt || '');
  if (!when) return '命中 ' + entry.mentions + ' 次';
  var minutes = Math.max(0, Math.round((Date.now() - when) / 60000));
  var relative = minutes < 1 ? '刚刚' : (minutes < 60 ? minutes + ' 分钟前' : (minutes < 1440 ? Math.round(minutes / 60) + ' 小时前' : Math.round(minutes / 1440) + ' 天前'));
  return '命中 ' + entry.mentions + ' 次 · ' + relative;
}

function removeLorebookEntry(index) {
  var draft = state.editingCharacterDraft;
  if (!draft || !Array.isArray(draft.lorebook)) return;
  draft.lorebook.splice(index, 1);
  renderLorebookEditor(draft);
}

function renderLorebookEditor(draft) {
  var container = document.getElementById('modal-content-lorebook');
  if (!container || !draft) return;
  if (!Array.isArray(draft.lorebook)) draft.lorebook = [];
  var inputStyle = 'background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);';
  var html = '<p class="text-xs text-muted-c mb-3">世界书是<b>世界层设定的唯一去处</b>（时代与世界观、地点、组织、专有名词、历史、规则）。每条可选<b>常驻</b>（每轮都注入）或<b>关键词命中</b>（被提到才注入，更省 token）。条目由 AI 在剧情推进中<b>自动维护</b>，你可以随时修改、禁用或删除；你手改过的条目会被锁定，AI 不会再覆盖它。</p>';
  if (draft.lorebook.length === 0) {
    html += '<p class="text-xs text-muted-c mb-3">还没有条目。可以让 AI 在建卡时生成，或在对话中自动补充。</p>';
  }
  html += '<div class="space-y-3">';
  draft.lorebook.forEach(function(entry, index) {
    var originLabel = entry.origin === 'ai' ? 'AI 写入' : '手写';
    var originColor = entry.origin === 'ai' ? 'var(--text-muted, #8a8a8a)' : 'var(--warning-color, #b45309)';
    html += '<div class="rounded-lg p-3 space-y-2" style="' + inputStyle + '">';
    html += '<div class="flex items-center gap-2"><input type="text" value="' + escapeHtml(entry.name || '') + '" placeholder="条目名（如：赤月王国）" oninput="updateLorebookEntry(' + index + ', \'name\', this.value)" class="flex-1 rounded-lg px-2 py-1.5 text-xs" style="' + inputStyle + '">'
      + '<span class="text-xs whitespace-nowrap" style="color:' + originColor + ';">' + originLabel + '</span>'
      + '<button type="button" onclick="removeLorebookEntry(' + index + ')" class="text-xs text-secondary-c whitespace-nowrap">删除</button></div>';
    html += '<div class="flex items-center gap-3 text-xs text-muted-c">'
      + '<label class="flex items-center gap-1 whitespace-nowrap"><input type="checkbox" ' + (entry.enabled === false ? '' : 'checked ') + 'onchange="updateLorebookEntry(' + index + ', \'enabled\', this.checked)">启用</label>'
      + '<label class="flex items-center gap-1 whitespace-nowrap"><input type="checkbox" ' + (entry.alwaysActive === true ? 'checked ' : '') + 'onchange="updateLorebookEntry(' + index + ', \'alwaysActive\', this.checked)">常驻</label>'
      + '<span class="whitespace-nowrap">' + escapeHtml(formatLorebookMention(entry)) + '</span></div>';
    html += '<input type="text" value="' + escapeHtml((entry.keywords || []).join('、')) + '" placeholder="触发关键词（用、或逗号分隔；常驻条目可留空）" oninput="updateLorebookEntry(' + index + ', \'keywords\', this.value)" class="w-full rounded-lg px-2 py-1.5 text-xs" style="' + inputStyle + '">';
    html += '<textarea rows="2" placeholder="命中后注入的设定内容" oninput="updateLorebookEntry(' + index + ', \'content\', this.value)" class="w-full rounded-lg px-2 py-1.5 text-xs" style="' + inputStyle + '">' + escapeHtml(entry.content || '') + '</textarea>';
    html += '</div>';
  });
  html += '</div>';
  html += '<button type="button" onclick="addLorebookEntry()" class="mt-3 w-full btn-secondary py-2 rounded-lg text-xs text-secondary-c">+ 添加条目</button>';
  container.innerHTML = html;
}

function renderCharacterModalContent(char) {
  var basicContainer = document.getElementById('modal-content-basic');
  if (char.entityType === 'group') {
    var groupInfo = char.groupInfo || {};
    basicContainer.innerHTML = '<div><label class="block text-xs text-muted-c mb-1">群组名称</label><input id="modal_group_name" value="' + escapeHtml(groupInfo.name || char.basicInfo.name) + '" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);"></div>' +
      '<div><label class="block text-xs text-muted-c mb-1">群组头像</label><input id="modal_group_avatar" value="' + escapeHtml(groupInfo.avatar || char.basicInfo.avatar) + '" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);"></div>' +
      '<div><label class="block text-xs text-muted-c mb-1">群组前提</label><textarea id="modal_group_description" rows="2" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);">' + escapeHtml(groupInfo.description) + '</textarea></div>' +
      '<div><label class="block text-xs text-muted-c mb-1">共同场景</label><textarea id="modal_group_scene" rows="2" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);">' + escapeHtml(groupInfo.scene) + '</textarea></div>' +
      '<div><label class="block text-xs text-muted-c mb-1">成员互动规则</label><textarea id="modal_group_rules" rows="3" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);">' + escapeHtml(groupInfo.interactionRules) + '</textarea></div>' +
      '<div class="text-xs text-muted-c">成员：' + escapeHtml((char.members || []).map(function(member) { return member.basicInfo.name; }).join('、')) + '</div>';
  } else {
  var basicFields = [
    { key: 'name', label: '名称' }, { key: 'avatar', label: '头像' }, { key: 'gender', label: '性别' },
    { key: 'age', label: '年龄' }, { key: 'race', label: '种族' },
    { key: 'appearance', label: '外貌特征' },
    { key: 'personality', label: '性格特征' }, { key: 'values', label: '价值观' },
    { key: 'fears', label: '恐惧/弱点' }, { key: 'background', label: '个人背景', type: 'textarea' },
    { key: 'keyEvents', label: '关键过往' },
    { key: 'speakingStyle', label: '说话风格' },
    { key: 'language', label: '语言/方言' },
    { key: 'userAddress', label: '对用户的称呼' }
  ];
  if (state.editingGroupMemberIndex != null) {
    basicFields.push({ key: 'roleInGroup', label: '群内定位' });
  }
  var basicHtml = '';
  basicFields.forEach(function(f) {
    var val = escapeHtml(f.key === 'roleInGroup' ? (char.roleInGroup || '') : (char.basicInfo[f.key] || ''));
    var safeLabel = escapeHtml(f.label);
    if (f.type === 'textarea') {
      basicHtml += '<div><label class="block text-xs text-muted-c mb-1">' + safeLabel + '</label><textarea id="modal_basic_' + f.key + '" rows="2" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);">' + val + '</textarea></div>';
    } else {
      basicHtml += '<div><label class="block text-xs text-muted-c mb-1">' + safeLabel + '</label><input type="text" id="modal_basic_' + f.key + '" value="' + val + '" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);"></div>';
    }
  });
  basicContainer.innerHTML = basicHtml;
  basicContainer.insertAdjacentHTML('beforeend', '<p class="text-xs text-muted-c mt-3 pt-3 border-t border-theme">以上为基础设定（谨慎修改）：仅当剧情出现极其明确的依据时，AI 才会在回复后自动调整这些字段，并会向你提示修改内容。可变状态（如当前目标、职业、关系等）在"动态状态"标签页维护。</p>');
  }
  if (state.editingGroupMemberIndex != null) {
    basicContainer.insertAdjacentHTML('afterbegin', '<div class="mb-4 pb-4 border-b border-theme"><label class="block text-xs text-muted-c mb-1">一句话补全空字段</label><div class="flex gap-2"><input id="memberFillInput" placeholder="例如：她是负责医疗支持的沉稳护士" class="flex-1 rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);"><button type="button" onclick="fillGroupMemberFields()" class="btn-secondary px-3 rounded-lg text-xs text-secondary-c">补全</button></div><p class="text-xs text-muted-c mt-1">只填充当前为空的字段，不覆盖已编辑内容。</p></div>');
  }

  var currentAvatar = sanitizeAvatar(char.basicInfo && char.basicInfo.avatar, char.entityType === 'group' ? '👥' : '👤');
  basicContainer.insertAdjacentHTML('beforeend', '<div class="mt-4 pt-3 border-t border-theme"><label class="block text-xs text-muted-c mb-1">头像（emoji）</label><div class="flex items-center gap-2"><span class="w-10 h-10 rounded-full bg-accent-soft flex items-center justify-center overflow-hidden text-lg">' + escapeHtml(currentAvatar) + '</span><button type="button" onclick="generateEmojiAvatarForDraft()" class="btn-secondary px-3 rounded-lg text-xs text-secondary-c">AI 生成 emoji 头像</button></div></div>');

  var stateContainer = document.getElementById('modal-content-state');
  var stateHtml = '<p class="text-xs text-muted-c mb-3">这些字段反映角色当前状态。你可以直接修改；AI 只会根据对话中明确的用户信息更新。</p><div class="space-y-3">';
  // 群组实体（非成员）只维护"全员共用"的两项动态状态；成员维护完整的 7 项
  var stateFields = char.entityType === 'group' ? DYNAMIC_STATE_FIELDS.filter(function(field) {
    return GROUP_SHARED_DYNAMIC_FIELDS.indexOf(field.key) !== -1;
  }) : DYNAMIC_STATE_FIELDS;
  stateFields.forEach(function(field) {
    var value = escapeHtml(char.dynamicState[field.key] || '');
    if (field.type === 'textarea') {
      stateHtml += '<div><label class="block text-xs text-muted-c mb-1">' + field.label + '</label><textarea id="modal_state_' + field.key + '" rows="3" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);">' + value + '</textarea></div>';
    } else {
      stateHtml += '<div><label class="block text-xs text-muted-c mb-1">' + field.label + '</label><input id="modal_state_' + field.key + '" type="text" value="' + value + '" class="w-full rounded-lg px-2 py-1.5 text-xs" style="background: var(--input-bg); border: 1px solid var(--input-border); color: var(--text-color);"></div>';
    }
  });
  stateContainer.innerHTML = stateHtml + '</div>';
  renderLorebookEditor(char);
}

function collectEditDraftFromDom() {
  if (!state.editingCharacterDraft) return;
  if (state.editingCharacterDraft.entityType === 'group') {
    var group = state.editingCharacterDraft.groupInfo || {};
    group.name = document.getElementById('modal_group_name').value;
    group.avatar = document.getElementById('modal_group_avatar').value;
    group.description = document.getElementById('modal_group_description').value;
    group.scene = document.getElementById('modal_group_scene').value;
    group.interactionRules = document.getElementById('modal_group_rules').value;
    state.editingCharacterDraft.groupInfo = group;
    state.editingCharacterDraft.basicInfo.name = group.name;
    state.editingCharacterDraft.basicInfo.avatar = group.avatar;
    state.editingCharacterDraft.basicInfo.personality = group.description;
    state.editingCharacterDraft.dynamicState.currentLocation = group.scene || state.editingCharacterDraft.dynamicState.currentLocation;
  } else {
  var basicFields = ['name', 'avatar', 'gender', 'age', 'race', 'appearance', 'personality', 'values', 'fears', 'background', 'keyEvents', 'speakingStyle', 'language', 'userAddress'];
  basicFields.forEach(function(field) {
    var input = document.getElementById('modal_basic_' + field);
    if (input) state.editingCharacterDraft.basicInfo[field] = field === 'userAddress' ? normalizeUserAddress(input.value) : parseRelativeText(input.value, new Date());
  });
  var roleInGroupInput = document.getElementById('modal_basic_roleInGroup');
  if (roleInGroupInput) state.editingCharacterDraft.roleInGroup = roleInGroupInput.value;
  }
  DYNAMIC_STATE_FIELDS.forEach(function(field) {
    var input = document.getElementById('modal_state_' + field.key);
    if (input) {
      state.editingCharacterDraft.dynamicState[field.key] = sanitizeDynamicStateField(field.key, input.value);
      delete state.editingCharacterDraft.dynamicStateMeta[field.key];
    }
  });
}

function saveCharacterModal() {
  if (!state.editingCharacterId || !state.editingCharacterDraft) return;
  collectEditDraftFromDom();
  if (state.editingGroupMemberIndex != null) {
    var group = state.characters[state.editingCharacterId];
    if (group && group.entityType === 'group' && group.members[state.editingGroupMemberIndex]) group.members[state.editingGroupMemberIndex] = state.editingCharacterDraft;
  } else {
    state.characters[state.editingCharacterId] = state.editingCharacterDraft;
  }
  scheduleSave();
  renderCharacterList();
  renderChat();
  closeCharacterModal();
}

async function fillGroupMemberFields() {
  if (state.editingGroupMemberIndex == null || !state.editingCharacterDraft || !state.editingCharacterId) return;
  var description = document.getElementById('memberFillInput').value.trim();
  if (!description) { alert('请输入一句话描述'); return; }
  var group = state.characters[state.editingCharacterId];
  if (!group || group.entityType !== 'group') return;
  var member = state.editingCharacterDraft;
  try {
    var response = await callAPI([
      { role: 'system', content: '你是角色卡补全助手。只返回JSON。只能根据群组设定、现有角色卡和用户的一句话补全空字段，不能覆盖或编造与已有字段冲突的信息。已有字段是绝对权威，任何情况下不得改写、润色或替换。' },
      { role: 'user', content: '群组信息:\n' + JSON.stringify(group.groupInfo || {}) + '\n群组其他成员:\n' + JSON.stringify((group.members || []).map(function(item) { return { name: item.basicInfo.name, personality: item.basicInfo.personality, speakingStyle: item.basicInfo.speakingStyle, roleInGroup: item.roleInGroup }; })) + '\n\n待补全成员:\n' + JSON.stringify(member) + '\n\n用户补充:\n' + description + '\n\n返回完整角色字段JSON：name, avatar, gender, age, race, appearance, personality, values, fears, background, keyEvents, speakingStyle, language, userAddress(该成员对用户的称呼，只填一个短称呼词), roleInGroup, dynamicState（包含currentSituation、currentLocation、currentMood、currentOccupation、currentGoal、currentRelationship、currentImportantOthers）。只给出有把握的字段，没有把握就省略，不要为了填满而编造。该成员的说话方式必须与群内其他成员显著不同（看台词就能分辨是谁）。' + SPEAKING_STYLE_SAMPLES_RULE }
    ]);
    var data = parseJsonPayload(response.choices[0].message.content);
    if (!isPlainObject(data)) throw new Error('补全结果格式错误');
    Object.keys(member.basicInfo).forEach(function(key) {
      if (!toText(member.basicInfo[key]).trim() && data[key] != null) {
        if (key === 'avatarPixel') {
          member.basicInfo.avatarPixel = normalizePixelAvatar(data.avatarPixel);
        } else {
          var filledFieldValue = key === 'userAddress' ? normalizeUserAddress(data[key]) : cleanFieldValue(key, parseRelativeText(toText(data[key]), new Date()));
          if (filledFieldValue) member.basicInfo[key] = filledFieldValue;
        }
      }
    });
    if (!toText(member.roleInGroup).trim() && data.roleInGroup != null) member.roleInGroup = cleanFieldValue('roleInGroup', toText(data.roleInGroup));
    if (isPlainObject(data.dynamicState)) {
      Object.keys(member.dynamicState).forEach(function(key) {
        if (!toText(member.dynamicState[key]).trim() && data.dynamicState[key] != null) {
          var filledStateValue = cleanFieldValue(key, sanitizeDynamicStateField(key, toText(data.dynamicState[key])));
          if (filledStateValue) member.dynamicState[key] = filledStateValue;
        }
      });
    }
    renderCharacterModalContent(member);
  } catch (error) {
    alert('补全失败：' + error.message);
  }
}

// 试验失败品：像素头像编辑器按钮，已从 UI 移除，保留实现仅供历史调用。不稳定。
async function generatePixelAvatarForDraft() {
  var draft = state.editingCharacterDraft;
  if (!draft) return;
  var name = toText(draft.basicInfo && draft.basicInfo.name);
  var appearance = toText(draft.basicInfo && draft.basicInfo.appearance);
  var personality = toText(draft.basicInfo && draft.basicInfo.personality);
  var desc = [name, appearance, personality].filter(Boolean).join('，') || '一个神秘角色';
  var btn = document.activeElement && document.activeElement.tagName === 'BUTTON' ? document.activeElement : null;
  if (btn) btn.disabled = true;
  try {
    var grid = await requestPixelAvatar(desc);
    draft.basicInfo = draft.basicInfo || {};
    draft.basicInfo.avatarPixel = grid;
    if (draft.entityType === 'group') {
      draft.groupInfo = draft.groupInfo || {};
      draft.groupInfo.avatarPixel = grid;
    }
    renderCharacterModalContent(draft);
  } catch (error) {
    alert('像素头像生成失败：' + error.message);
  } finally {
    if (btn) btn.disabled = false;
  }
}

// 试验失败品：像素头像清除，与像素头像一并弃用。
function clearPixelAvatarForDraft() {
  var draft = state.editingCharacterDraft;
  if (!draft) return;
  if (draft.basicInfo) draft.basicInfo.avatarPixel = null;
  if (draft.entityType === 'group' && draft.groupInfo) draft.groupInfo.avatarPixel = null;
  renderCharacterModalContent(draft);
}

// 编辑器里的头像生成入口：调用 AI 生成贴切 emoji，稳定可靠
async function generateEmojiAvatarForDraft() {
  var draft = state.editingCharacterDraft;
  if (!draft) return;
  var desc = buildAvatarDescription(draft, draft);
  var btn = document.activeElement && document.activeElement.tagName === 'BUTTON' ? document.activeElement : null;
  if (btn) btn.disabled = true;
  try {
    var emoji = await requestEmojiAvatar(desc);
    draft.basicInfo = draft.basicInfo || {};
    draft.basicInfo.avatar = emoji;
    if (draft.entityType === 'group' && draft.groupInfo) {
      draft.groupInfo.avatar = emoji;
    }
    renderCharacterModalContent(draft);
  } catch (error) {
    alert('emoji 头像生成失败：' + error.message);
  } finally {
    if (btn) btn.disabled = false;
  }
}

function buildAvatarDescription(obj, parent) {
  var bi = obj.basicInfo || {};
  var parts = [bi.name, bi.appearance, bi.personality];
  if (obj.roleInGroup) parts.push(obj.roleInGroup);
  return parts.map(function(v) { return toText(v); }).filter(Boolean).join('，') || '一个神秘角色';
}

// 收集所有默认/损坏头像的修复任务（角色 + 群组成员，含 👤/👥 占位与空/损坏值）
function collectAvatarRepairJobs() {
  var jobs = [];
  var chars = Object.values(state.characters || {});
  chars.forEach(function(char) {
    if (needsAvatarRepair(char)) jobs.push({ char: char, target: char });
    (char.members || []).forEach(function(member) {
      if (needsAvatarRepair(member)) jobs.push({ char: char, target: member });
    });
  });
  return jobs;
}

// 判断某角色/成员头像是否需要修复：损坏值、空值，或仍是 👤/👥 默认占位
function needsAvatarRepair(target) {
  var avatar = target && target.basicInfo ? target.basicInfo.avatar : '';
  if (typeof avatar !== 'string' || !avatar.trim()) return true;
  var v = avatar.trim();
  if (v === '👤' || v === '👥') return true;
  return isAvatarDamaged(v);
}

// 串行为每个头像调用 AI 生成贴切 emoji（统一入口，供自动修复与手动补全共用）
async function repairAvatarJobs(jobs, onProgress) {
  var done = 0, failed = 0, failNames = [];
  for (var i = 0; i < jobs.length; i++) {
    var job = jobs[i];
    var target = job.target;
    var name = (target.basicInfo && target.basicInfo.name) || '?';
    try {
      var emoji = await requestEmojiAvatar(buildAvatarDescription(target, job.char));
      target.basicInfo = target.basicInfo || {};
      target.basicInfo.avatar = emoji;
      if (job.char.entityType === 'group' && target === job.char && job.char.groupInfo) {
        job.char.groupInfo.avatar = emoji;
      }
      delete target.avatarRepairPending;
      done++;
    } catch (error) {
      failed++;
      target.avatarRepairPending = true;
      failNames.push(name);
      console.warn('头像生成失败(' + name + '):', error.message);
    }
    renderCharacterList();
    scheduleSave();
    if (onProgress) onProgress(i + 1, jobs.length);
    if (i < jobs.length - 1) await new Promise(function(resolve) { setTimeout(resolve, 300); });
  }
  return { done: done, failed: failed, failNames: failNames };
}

// 自动修复（启动/导入后调用）：静默失败不再吞掉，但仅处理损坏项，不覆盖 👤/👥 占位（留给手动补全）
async function autoRepairAvatars() {
  if (window.__autoRepairRunning) return;
  var jobs = collectAvatarRepairJobs().filter(function(j) {
    return j.target.avatarRepairPending || isAvatarDamaged(j.target.basicInfo && j.target.basicInfo.avatar);
  });
  if (jobs.length === 0) return;
  if (!state.config || !state.config.apiKey) return; // 无 key 不发起无意义调用，留给手动补全提示
  window.__autoRepairRunning = true;
  var result = await repairAvatarJobs(jobs);
  window.__autoRepairRunning = false;
  renderChat();
  if (result.done > 0 || result.failed > 0) {
    var msg = '已自动生成 ' + result.done + ' 个损坏头像';
    if (result.failed > 0) msg += '，失败 ' + result.failed + ' 个（' + result.failNames.join('、') + '），可稍后在侧边栏点击"补全头像"重试';
    alert(msg);
  }
}

// 手动一键补全（侧边栏"补全头像"按钮）：覆盖所有默认/损坏头像，含 👤/👥 占位
async function repairAllAvatars() {
  if (window.__autoRepairRunning) {
    alert('头像补全正在进行中，请稍候…');
    return;
  }
  var jobs = collectAvatarRepairJobs();
  if (jobs.length === 0) {
    alert('所有角色与群组成员都已使用 emoji 头像，无需补全');
    return;
  }
  if (!state.config || !state.config.apiKey) {
    alert('请先在设置页配置 API Key，再补全头像');
    return;
  }
  if (!confirm('将为 ' + jobs.length + ' 个默认/损坏头像生成贴切 emoji，确定继续？')) return;
  var btn = document.getElementById('repairAvatarsBtn');
  if (btn) { btn.disabled = true; btn.textContent = '补全中…'; }
  window.__autoRepairRunning = true;
  var result = await repairAvatarJobs(jobs);
  window.__autoRepairRunning = false;
  if (btn) { btn.disabled = false; btn.innerHTML = '<svg class="icon"><use href="#icon-refresh"></use></svg> 补全头像'; }
  renderChat();
  var msg = '已生成 ' + result.done + ' 个 emoji 头像';
  if (result.failed > 0) msg += '，失败 ' + result.failed + ' 个（' + result.failNames.join('、') + '），请检查网络后重试';
  alert(msg);
}

