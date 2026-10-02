// ==================== 世界书一次性迁移 ====================
// 把旧数据里写在"群组前提/个人背景"中的世界层内容（地点/组织/专有名词/历史/规则）
// 整理成世界书条目。原文默认保留，用户可勾选同时精简原文。
function collectLorebookMigrationTargets() {
  var targets = [];
  Object.keys(state.characters || {}).forEach(function(charId) {
    var entity = state.characters[charId];
    if (!entity || entity.lorebookMigratedAt) return;
    var sourceText = entity.entityType === 'group'
      ? toText(entity.groupInfo && entity.groupInfo.description)
      : toText(entity.basicInfo && entity.basicInfo.background);
    if (trimText(sourceText, 4000).length >= 40) targets.push({ id: charId, entity: entity, sourceText: trimText(sourceText, 2000) });
    else entity.lorebookMigratedAt = new Date().toISOString();
  });
  return targets;
}

function askLorebookMigration(count) {
  return new Promise(function(resolve) {
    var overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;inset:0;z-index:9999;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;padding:16px;';
    overlay.innerHTML = '<div style="max-width:30rem;width:100%;background:var(--input-bg,#1c1c1e);color:var(--text-color,#eee);border:1px solid var(--input-border,#333);border-radius:14px;padding:18px;">'
      + '<div style="font-weight:600;margin-bottom:8px;">世界书整理</div>'
      + '<p style="font-size:13px;line-height:1.6;margin:0 0 12px;">检测到 ' + count + ' 个角色/群组的描述里含有世界观类内容。世界书已改为<b>由 AI 自动维护</b>：是否用 AI 把这些内容整理成世界书条目？（不整理也能用，只是世界观会一直常驻占字数）</p>'
      + '<label style="display:flex;align-items:center;gap:8px;font-size:12px;margin-bottom:8px;"><input type="checkbox" id="lorebookTrimSource"> 同时把已迁出的内容从原文里精简掉</label>'
      + '<label style="display:flex;align-items:center;gap:8px;font-size:12px;margin-bottom:14px;"><input type="checkbox" id="lorebookMigrateSkipForever"> 以后不再询问</label>'
      + '<div style="display:flex;gap:8px;justify-content:flex-end;">'
      + '<button id="lorebookMigrateSkip" class="btn-secondary" style="padding:7px 14px;border-radius:10px;font-size:13px;">跳过</button>'
      + '<button id="lorebookMigrateRun" class="btn-secondary" style="padding:7px 14px;border-radius:10px;font-size:13px;">开始整理</button>'
      + '</div></div>';
    document.body.appendChild(overlay);
    function finish(run) {
      var trimSource = !!(overlay.querySelector('#lorebookTrimSource') && overlay.querySelector('#lorebookTrimSource').checked);
      var skipForever = !!(overlay.querySelector('#lorebookMigrateSkipForever') && overlay.querySelector('#lorebookMigrateSkipForever').checked);
      overlay.remove();
      resolve({ run: run, trimSource: trimSource, skipForever: skipForever });
    }
    overlay.querySelector('#lorebookMigrateRun').addEventListener('click', function() { finish(true); });
    overlay.querySelector('#lorebookMigrateSkip').addEventListener('click', function() { finish(false); });
  });
}

// 用 AI 把原文里的世界层内容拆成世界书条目；用户手写条目不受影响（upsert 会拒绝覆盖）
async function migrateWorldLoreForEntity(entity, trimSource) {
  var isGroup = entity.entityType === 'group';
  var sourceText = isGroup ? toText(entity.groupInfo && entity.groupInfo.description) : toText(entity.basicInfo && entity.basicInfo.background);
  if (!trimText(sourceText, 2000)) return true;
  var existing = normalizeLorebook(entity.lorebook).map(function(entry) { return { name: entry.name, keywords: entry.keywords, alwaysActive: entry.alwaysActive }; });
  var prompt = '下面是一个' + (isGroup ? '群组前提' : '角色的个人背景') + '文本，其中可能混有世界观类内容（时代/世界观、地点、组织、专有名词、历史、规则）。\n'
    + '请把其中的**世界层设定**整理成世界书条目；只整理原文已有的信息，禁止编造或扩写；角色/群组本人的性格、经历、关系不要抽成条目。\n'
    + '每条给出 name（条目名）、keywords（剧情里可能出现的称呼）、content（该条目本身的信息，不要理由与解释）、alwaysActive（世界前提/规则这类需要每轮生效的设 true，其余 false）。最多 6 条；没有世界层内容就返回空数组。\n'
    + (trimSource ? '另外请给出精简后的原文：删掉已经抽成条目的世界观内容，只保留角色/群组本人相关的部分，其他内容一字不改；没有可精简的就原样返回。\n' : '')
    + '只返回 JSON：{"entries":[{"name":"","keywords":[],"content":"","alwaysActive":false}]' + (trimSource ? ',"trimmedSource":""' : '') + '}。\n\n'
    + '现有世界书条目：' + (existing.length ? JSON.stringify(existing) : '（空）')
    + '\n\n' + (isGroup ? '群组前提' : '个人背景') + '：\n' + trimText(sourceText, 2000);
  var response = await callAPI([
    { role: 'system', content: '你是世界书整理助手。只返回JSON。' },
    { role: 'user', content: prompt }
  ]);
  var data = parseJsonPayload(response.choices[0].message.content);
  if (!isPlainObject(data)) return false;
  var entries = Array.isArray(data.entries) ? data.entries.slice(0, 6) : [];
  entries.forEach(function(item) {
    if (!isPlainObject(item) || !toText(item.name).trim() || !toText(item.content).trim()) return;
    upsertLorebookEntry(entity, {
      name: item.name,
      content: item.content,
      keywords: item.keywords,
      alwaysActive: item.alwaysActive === true
    }, { evidence: '世界书一次性迁移：整理自原有设定文本' });
  });
  if (trimSource && data.trimmedSource != null) {
    var trimmed = trimText(cleanFieldValue(isGroup ? 'description' : 'background', toText(data.trimmedSource)), isGroup ? 1200 : 800);
    // 只在确实变短且非空时替换，避免 AI 反而写长
    if (trimmed && trimmed.length < sourceText.trim().length) {
      if (isGroup) entity.groupInfo.description = trimmed;
      else entity.basicInfo.background = trimmed;
    }
  }
  entity.lorebookMigratedAt = new Date().toISOString();
  return true;
}

async function maybeMigrateLorebookWorld() {
  if (window.__lorebookMigrationRunning) return;
  if (lorebookMigrationSkipRequested()) return;
  var targets = collectLorebookMigrationTargets();
  if (targets.length === 0) return;
  if (!state.config || !state.config.apiKey) {
    targets.forEach(function(target) { target.entity.lorebookMigratedAt = null; });
    return;
  }
  window.__lorebookMigrationRunning = true;
  try {
    var answer = await askLorebookMigration(targets.length);
    if (answer.skipForever) {
      markLorebookMigrationSkip();
      targets.forEach(function(target) { target.entity.lorebookMigratedAt = new Date().toISOString(); });
      scheduleSave();
      return;
    }
    if (!answer.run) {
      // 本次跳过不标记，下次启动再问
      targets.forEach(function(target) { target.entity.lorebookMigratedAt = null; });
      return;
    }
    for (var i = 0; i < targets.length; i++) {
      setActivity('正在整理世界书（' + (i + 1) + '/' + targets.length + '）…');
      await migrateWorldLoreForEntity(targets[i].entity, answer.trimSource);
    }
    scheduleSave();
    renderCharacterList();
    setActivity('');
    alert('世界书整理完成。');
  } catch (error) {
    console.warn('世界书迁移失败:', error && error.message);
    setActivity('');
    alert('世界书整理失败：' + (error && error.message ? error.message : '未知错误') + '，下次启动可重试。');
  } finally {
    window.__lorebookMigrationRunning = false;
  }
}

async function migrateEntityFields(entity) {
  if (!entity || entity.fieldsMigrationVersion === FIELDS_MIGRATION_VERSION) return;
  if (entity.entityType === 'group') {
    // 群组只整理描述与共同状态
    var groupDesc = toText(entity.groupInfo && entity.groupInfo.description);
    if (groupDesc) {
      var ds = entity.dynamicState || {};
      var prompt = '下面是群组的前提与共同状态文本，请整理：只返回JSON {"description":"整合后的群组前提（去重；只保留这群人是谁、为何在一起，世界观/地点/组织等世界层内容不要塞进来）","currentSituation":"当前共同处境（绝对日期）","currentLocation":"共同场景"}。只整理已有信息，不编造。\n' + JSON.stringify({ description: groupDesc, currentSituation: toText(ds.currentSituation), currentLocation: toText(ds.currentLocation) });
      var response = await callAPI([
        { role: 'system', content: '你是角色字段结构迁移助手。只返回JSON。' },
        { role: 'user', content: prompt }
      ]);
      var data = parseJsonPayload(response.choices[0].message.content);
      if (isPlainObject(data)) {
        if (data.description != null) {
          var newDesc = trimText(cleanFieldValue('description', toText(data.description)), 1200);
          if (newDesc) entity.groupInfo.description = newDesc;
        }
        if (data.currentSituation != null) entity.dynamicState.currentSituation = toText(data.currentSituation);
        if (data.currentLocation != null) entity.dynamicState.currentLocation = toText(data.currentLocation);
      }
    }
    entity.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION;
    return;
  }
  await aiRemapFieldsForEntity(entity);
  entity.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION;
}

