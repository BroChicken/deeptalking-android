// ==================== One-Sentence Generation ====================
async function quickGenerateCharacter() {
  var desc = document.getElementById('quickGenInput').value.trim();
  if (!desc) return;
  var entityType = document.getElementById('newEntityType').value;
  var btn = document.getElementById('quickGenBtn');
  btn.disabled = true;
  btn.innerHTML = '<svg class="icon icon-spin"><use href="#icon-loader"></use></svg>';
  try {
    var prompt = entityType === 'group'
      ? '根据以下描述生成一个角色群组。只返回JSON：{"groupInfo":{"name":"群组名","avatar":"emoji","description":"群组前提（这群人是谁、为什么在一起，1-2 句；不要写世界观/时代/地点/组织等世界层设定）","scene":"场景","interactionRules":"成员互动规则"},"lorebook":[{"name":"条目名（地点/组织/专有名词/规则等）","keywords":["触发词"],"content":"命中后注入的世界层设定","alwaysActive":false}],"members":[{"name":"成员名","avatar":"emoji","gender":"性别","age":"年龄","race":"种族","appearance":"外貌","personality":"性格","values":"价值观","fears":"恐惧或弱点","background":"背景","keyEvents":"关键过往（里程碑）","speakingStyle":"说话风格","language":"语言","userAddress":"该成员对用户的称呼（一个短称呼词）","roleInGroup":"群内定位","dynamicState":{"currentSituation":"当前处境","currentLocation":"当前位置","currentMood":"当前情绪","currentOccupation":"当前职业/身份","currentGoal":"当前目标","currentRelationship":"当前关系","currentImportantOthers":"当前重要他人"}}]}。群组的 description/scene 是全体成员共用的前提，必须填写；**世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）一律写进 lorebook（2-4 条），绝不能塞进 description 或成员的 background**；lorebook 条目要能被日后复用，keywords 写剧情里可能出现的称呼（常驻内容把 alwaysActive 设为 true），content 只写该条目本身的信息。至少生成两名成员；每名成员必须尽可能填满所有字段，不能只返回名称和性格；成员之间的说话方式必须显著不同（看台词就能分辨是谁）。' + CHARACTER_QUALITY_RULE + SPEAKING_STYLE_SAMPLES_RULE + '\n描述: ' + desc
      : '根据以下描述，生成一个角色设定。返回JSON格式，包含这些字段: name, avatar(emoji), gender, age, race, appearance, personality, values, fears, background(角色个人经历，不要写世界观), keyEvents, speakingStyle, language, userAddress(角色对用户的称呼，只填一个短称呼词), lorebook(数组，2-4 条世界层设定条目，每条 {"name":"条目名","keywords":["触发词"],"content":"命中后注入的世界层设定","alwaysActive":false})。**世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）一律写进 lorebook，不要写进 background。**' + CHARACTER_QUALITY_RULE + SPEAKING_STYLE_SAMPLES_RULE + '\n描述: ' + desc;
    var response = await callAPI([
      { role: 'system', content: entityType === 'group' ? '你是群组角色设计助手。只返回JSON。' : '你是角色设计助手。根据用户描述生成详细角色设定。只返回JSON。' },
      { role: 'user', content: prompt }
    ]);
    var content = response.choices[0].message.content;
    var charData = parseJsonPayload(content);
    if (!isPlainObject(charData)) throw new Error('生成结果格式错误');
    if (entityType === 'group') {
      if (!isPlainObject(charData.groupInfo) || !Array.isArray(charData.members)) throw new Error('群组设定格式错误');
      document.getElementById('newCharName').value = charData.groupInfo.name || '';
      document.getElementById('newCharAvatar').value = charData.groupInfo.avatar || '👥';
      document.getElementById('newCharPersonality').value = charData.groupInfo.description || '';
      document.getElementById('newCharBackground').value = charData.groupInfo.scene || '';
      document.getElementById('newGroupMembers').value = charData.members.map(function(member) { return (member.name || '成员') + '｜' + (member.personality || member.roleInGroup || ''); }).join('\n');
    } else {
      document.getElementById('newCharName').value = charData.name || '';
      document.getElementById('newCharAvatar').value = charData.avatar || '👤';
      document.getElementById('newCharPersonality').value = charData.personality || '';
      document.getElementById('newCharBackground').value = charData.background || '';
    }
    window._tempCharData = charData;
  } catch (e) {
    alert('生成失败: ' + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = '生成';
  }
}

function createCharacter() {
  var name = document.getElementById('newCharName').value.trim();
  var avatar = document.getElementById('newCharAvatar').value.trim() || '👤';
  var personality = document.getElementById('newCharPersonality').value.trim();
  var background = document.getElementById('newCharBackground').value.trim();
  var entityType = document.getElementById('newEntityType').value;
  if (!name) { alert(entityType === 'group' ? '请输入群组名称' : '请输入角色名称'); return; }
  if (entityType === 'group') {
    var members = isPlainObject(window._tempCharData) && Array.isArray(window._tempCharData.members) ? window._tempCharData.members : parseGroupMembers(document.getElementById('newGroupMembers').value);
    if (members.length < 2) { alert('群组至少需要两名成员'); return; }
    var generatedGroup = isPlainObject(window._tempCharData) && isPlainObject(window._tempCharData.groupInfo) ? window._tempCharData.groupInfo : null;
    var group = createGroupObj(name, avatar, personality, members, generatedGroup != null);
    group.groupInfo.scene = background;
    if (generatedGroup) {
      group.groupInfo.interactionRules = cleanFieldValue('interactionRules', toText(generatedGroup.interactionRules));
      // 群组不再镜像基础设定字段：新版响应把世界层设定放在 lorebook 里，不再并进群组前提
      if (!Array.isArray(window._tempCharData.lorebook)) {
        var groupExtraDescriptions = [toText(generatedGroup.description), toText(generatedGroup.worldView), toText(generatedGroup.background)]
          .map(function(part) { return cleanFieldValue('description', part); })
          .filter(function(part) { return part && group.groupInfo.description.indexOf(part) === -1; });
        if (groupExtraDescriptions.length) {
          group.groupInfo.description = [group.groupInfo.description].concat(groupExtraDescriptions).filter(Boolean).join('\n');
        }
      }
      if (Array.isArray(generatedGroup.avatarPixel)) {
        group.basicInfo.avatarPixel = normalizePixelAvatar(generatedGroup.avatarPixel);
        group.groupInfo.avatarPixel = group.basicInfo.avatarPixel;
      }
    }
    if (isPlainObject(window._tempCharData) && Array.isArray(window._tempCharData.lorebook)) {
      group.lorebook = normalizeGeneratedLorebook(window._tempCharData.lorebook);
      dedupeLorebook(group.lorebook);
    }
    window._tempCharData = null;
    addCharacter(group);
    closeCreateModal();
    return;
  }
  var char = createCharacterObj(name, avatar, personality, background);
  if (isPlainObject(window._tempCharData)) {
    Object.keys(char.basicInfo).forEach(function(key) {
      if (window._tempCharData[key] == null) return;
      if (key === 'avatarPixel') {
        char.basicInfo.avatarPixel = normalizePixelAvatar(window._tempCharData.avatarPixel);
        return;
      }
      var generatedFieldValue = key === 'userAddress' ? normalizeUserAddress(window._tempCharData[key]) : cleanFieldValue(key, parseRelativeText(toText(window._tempCharData[key]), new Date()));
      if (generatedFieldValue) char.basicInfo[key] = generatedFieldValue;
    });
    if (isPlainObject(window._tempCharData.dynamicState)) {
      Object.keys(char.dynamicState).forEach(function(key) {
        if (window._tempCharData.dynamicState[key] == null) return;
        var generatedStateValue = cleanFieldValue(key, sanitizeDynamicStateField(key, toText(window._tempCharData.dynamicState[key])));
        if (generatedStateValue) char.dynamicState[key] = generatedStateValue;
      });
    }
    char.basicInfo.name = name;
    char.basicInfo.avatar = avatar;
    char.basicInfo.personality = personality;
    char.basicInfo.background = background;
    if (Array.isArray(window._tempCharData.lorebook)) {
      char.lorebook = normalizeGeneratedLorebook(window._tempCharData.lorebook);
      dedupeLorebook(char.lorebook);
    }
    window._tempCharData = null;
  }
  addCharacter(char);
  closeCreateModal();
}

function parseGroupMembers(value) {
  return toText(value).split('\n').map(function(line) {
    var parts = line.split('｜');
    return { name: parts[0].trim(), personality: (parts[1] || '').trim() };
  }).filter(function(member) { return member.name; });
}

async function upgradeToGroup(id) {
  var char = state.characters[id];
  if (!char || char.entityType === 'group' || state.isProcessing) return;
  if (!confirm('将保留现有对话和记忆，并由 AI 根据当前对话扩展为群组。继续吗？')) return;
  try {
    var transcript = char.memory.instant.filter(function(message) { return !message.isLoading; }).slice(-20).map(function(message) {
      return (message.role === 'user' ? '用户' : char.basicInfo.name) + ': ' + trimText(message.content, 1000);
    }).join('\n');
    var response = await callAPI([
      { role: 'system', content: '你是群组升级助手。只返回JSON。' },
      { role: 'user', content: '将以下角色和近期对话升级为群组。原角色会由系统完整保留，因此绝不能在输出成员列表中再次生成原角色，也不要生成同名或明显重复的变体。请列出所有近期对话中已出现、应成为固定成员的其他角色；若不足一人，再新增一名最适合当前剧情的成员。返回JSON：{"groupInfo":{"name":"群组名","avatar":"emoji","description":"群组前提（这群人是谁、为什么在一起，1-2 句；不要写世界观/时代/地点/组织等世界层设定）","scene":"场景","interactionRules":"成员互动规则"},"lorebook":[{"name":"条目名（地点/组织/专有名词/规则等）","keywords":["触发词"],"content":"命中后注入的世界层设定","alwaysActive":false}],"additionalMembers":[{"name":"新增成员名","avatar":"emoji","gender":"性别","age":"年龄","race":"种族","appearance":"外貌","personality":"性格","values":"价值观","fears":"恐惧或弱点","background":"背景","keyEvents":"关键过往（里程碑）","speakingStyle":"说话风格","language":"语言","userAddress":"该成员对用户的称呼（一个短称呼词）","roleInGroup":"群内定位","dynamicState":{"currentSituation":"当前处境","currentLocation":"当前位置","currentMood":"当前情绪","currentOccupation":"当前职业/身份","currentGoal":"当前目标","currentRelationship":"当前关系","currentImportantOthers":"当前重要他人"}}]}。lorebook 只需补上近期对话中出现、值得日后复用的世界层设定（没有就返回空数组）；additionalMembers只能包含新增成员，至少一名，且姓名必须互不重复；每名成员必须尽可能填满所有字段，不能只返回名称和性格；成员之间的说话方式必须显著不同（看台词就能分辨是谁）。' + CHARACTER_QUALITY_RULE + SPEAKING_STYLE_SAMPLES_RULE + '\n原角色（禁止重复输出）:\n' + JSON.stringify(char.basicInfo) + '\n近期对话:\n' + transcript }
    ]);
    var data = parseJsonPayload(response.choices[0].message.content);
    var additionalMembers = Array.isArray(data && data.additionalMembers) ? data.additionalMembers : (Array.isArray(data && data.members) ? data.members : null);
    if (!isPlainObject(data) || !isPlainObject(data.groupInfo) || !additionalMembers) throw new Error('群组升级结果格式错误');
    var originalMember = { basicInfo: char.basicInfo, dynamicState: char.dynamicState, roleInGroup: '原有成员' };
    var originalName = toText(char.basicInfo.name).trim().toLowerCase();
    var members = normalizeGroupMembers([originalMember].concat(additionalMembers.filter(function(member) {
      return toText(member.name || (member.basicInfo || {}).name).trim().toLowerCase() !== originalName;
    })), true);
    var seenNames = new Set();
    members = members.filter(function(member) {
      var name = toText(member.basicInfo.name).trim().toLowerCase();
      if (!name || seenNames.has(name)) return false;
      seenNames.add(name);
      return true;
    });
    if (members.length < 2) throw new Error('群组至少需要两名成员');
    var groupInfo = {
      name: toText(data.groupInfo.name, char.basicInfo.name + '的群组'),
      avatar: toText(data.groupInfo.avatar, '👥'),
      avatarPixel: Array.isArray(data.groupInfo.avatarPixel) ? normalizePixelAvatar(data.groupInfo.avatarPixel) : char.basicInfo.avatarPixel,
      description: cleanFieldValue('description', toText(data.groupInfo.description)),
      scene: cleanFieldValue('scene', toText(data.groupInfo.scene, char.dynamicState.currentLocation)),
      interactionRules: cleanFieldValue('interactionRules', toText(data.groupInfo.interactionRules))
    };
    // 群组不再镜像基础设定字段：新版响应把世界层设定放在 lorebook 里，不再并进群组前提
    if (!Array.isArray(data.lorebook)) {
      [toText(data.groupInfo.worldView), toText(data.groupInfo.background)].forEach(function(part) {
        var cleaned = cleanFieldValue('description', part);
        if (cleaned && groupInfo.description.indexOf(cleaned) === -1) {
          groupInfo.description = groupInfo.description ? (groupInfo.description + '\n' + cleaned) : cleaned;
        }
      });
    }
    var group = createGroupObj(groupInfo.name, groupInfo.avatar, groupInfo.description, members, true);
    group.groupInfo = groupInfo;
    group.basicInfo.avatarPixel = groupInfo.avatarPixel;
    group.dynamicState = JSON.parse(JSON.stringify(char.dynamicState));
    group.dynamicStateMeta = JSON.parse(JSON.stringify(char.dynamicStateMeta));
    mergeLegacyDynamicFields(group);
    group.memory = JSON.parse(JSON.stringify(char.memory));
    // 原角色的世界书条目随升级并入群组（世界层设定是共用的），再合并本次新生成的条目
    var upgradedBook = normalizeLorebook(char.lorebook).concat(Array.isArray(data.lorebook) ? normalizeGeneratedLorebook(data.lorebook) : []);
    var seenBookNames = Object.create(null);
    group.lorebook = upgradedBook.filter(function(entry) {
      var bookKey = normalizeLorebookName(entry.name) || entry.id;
      if (seenBookNames[bookKey]) return false;
      seenBookNames[bookKey] = true;
      return true;
    });
    dedupeLorebook(group.lorebook);
    addCharacter(group);
  } catch (error) {
    alert('升级失败：' + error.message);
  }
}

