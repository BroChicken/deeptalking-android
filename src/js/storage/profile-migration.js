// ==================== 1.2.0 旧字段结构 AI 迁移 ====================
// 本地合并（normalize 时已完成）是兜底；此处在启动时询问用户是否用 AI 把旧字段内容
// 重新整理进新字段，直到迁移完成（或用户勾选"以后不再询问"后跳过）为止。
var FIELD_MIGRATION_SKIP_KEY = 'deeptalking_field_migration_skip_v1';
var FIELD_MIGRATION_TARGETS = [];
var LOREBOOK_MIGRATION_SKIP_KEY = 'deeptalking_lorebook_migration_skip_v1';

function lorebookMigrationSkipRequested() {
  try { return localStorage.getItem(LOREBOOK_MIGRATION_SKIP_KEY) === '1'; } catch (e) { return false; }
}

function markLorebookMigrationSkip() {
  try { localStorage.setItem(LOREBOOK_MIGRATION_SKIP_KEY, '1'); } catch (e) {}
}

function collectFieldMigrationTargets(rawCharacters) {
  FIELD_MIGRATION_TARGETS = [];
  if (!isPlainObject(rawCharacters)) return;
  Object.keys(rawCharacters).forEach(function(id) {
    var raw = rawCharacters[id];
    if (!isPlainObject(raw)) return;
    if (raw.fieldsMigrationVersion === FIELDS_MIGRATION_VERSION) {
      // 群组成员也可能未迁移（以 fieldsMigrationVersion 标记判断）
      var members = Array.isArray(raw.members) ? raw.members : [];
      var pendingMembers = members.filter(function(m) {
        return isPlainObject(m) && m.fieldsMigrationVersion !== FIELDS_MIGRATION_VERSION;
      });
      if (pendingMembers.length > 0) FIELD_MIGRATION_TARGETS.push({ id: id, memberCount: pendingMembers.length });
      return;
    }
    FIELD_MIGRATION_TARGETS.push({ id: id, memberCount: (Array.isArray(raw.members) ? raw.members.length : 0) });
  });
}

function fieldMigrationSkipRequested() {
  try { return localStorage.getItem(FIELD_MIGRATION_SKIP_KEY) === '1'; } catch (e) { return false; }
}

function markFieldMigrationSkip() {
  try { localStorage.setItem(FIELD_MIGRATION_SKIP_KEY, '1'); } catch (e) {}
}

// 收集单个实体（角色/成员）需要交给 AI 的字段映射：旧字段原文 + 目标字段现状
function buildFieldMigrationPayload(entity) {
  var raw = {};
  var ds = entity.dynamicState || {};
  var bi = entity.basicInfo || {};
  raw.fields = {
    currentSituation: toText(ds.currentSituation),
    currentLocation: toText(ds.currentLocation),
    currentMood: toText(ds.currentMood),
    currentOccupation: toText(ds.currentOccupation),
    currentGoal: toText(ds.currentGoal),
    currentRelationship: toText(ds.currentRelationship),
    currentImportantOthers: toText(ds.currentImportantOthers)
  };
  raw.background = toText(bi.background);
  // 本地合并后 currentSituation 里可能同时含"处境"和原"近期进展"，交由 AI 去重与整理
  return {
    name: toText(bi.name),
    entityType: entity.entityType === 'group' ? 'group' : 'character',
    dynamicState: raw.fields,
    background: raw.background
  };
}

// 用 AI 把旧字段内容整理进新字段结构；只允许返回新版字段。
async function aiRemapFieldsForEntity(entity) {
  var payload = buildFieldMigrationPayload(entity);
  var fieldList = DYNAMIC_STATE_FIELDS.map(function(f) { return f.key + '（' + f.label + '）'; }).join('、');
  var prompt = '下面是一个角色/成员的当前字段文本。请把它整理成新版字段结构：\n'
    + '动态状态字段只能是：' + fieldList + '；静态设定只保留 background（背景故事）。\n'
    + '要求：①把内容按其语义归入最贴切的字段，同一件事只留在最合适的一个字段里，消除重复与啰嗦；'
    + '②currentSituation 专注"此刻正在做什么/共同处境"，currentGoal 写"接下来想做什么"，currentMood 写情绪，不要互相重复；'
    + '③时间一律保留/写成绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），不得写"今天/明天/昨晚"这类相对时间；'
    + '④只整理已有信息，不得编造未出现的事实；信息不足的字段可省略；'
    + '⑤每个字段值写成自然、完整、简短的陈述句，禁止括号注释与理由说明。\n'
    + '现有内容（JSON）:\n' + JSON.stringify(payload, null, 2)
    + '\n只返回JSON对象：{"dynamicState":{...},"background":"..."}，键为字段英文名。';
  var response = await callAPI([
    { role: 'system', content: '你是角色字段结构迁移助手。只返回JSON。' },
    { role: 'user', content: prompt }
  ]);
  var data = parseJsonPayload(response.choices[0].message.content);
  if (!isPlainObject(data)) return false;
  var ds = isPlainObject(data.dynamicState) ? data.dynamicState : {};
  DYNAMIC_STATE_FIELDS.forEach(function(field) {
    if (ds[field.key] == null) return;
    var value = trimText(cleanFieldValue(field.key, sanitizeDynamicStateField(field.key, toText(ds[field.key]))), 700);
    if (value) entity.dynamicState[field.key] = value;
  });
  if (data.background != null && entity.entityType !== 'group') {
    var bg = trimText(cleanFieldValue('background', toText(data.background)), 800);
    if (bg) entity.basicInfo.background = bg;
  }
  return true;
}

// 弹出迁移确认框（含"以后不再询问"勾选）。返回 Promise<{run:boolean, skipForever:boolean}>
function askFieldMigration() {
  return new Promise(function(resolve) {
    var overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;inset:0;z-index:9999;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;padding:16px;';
    overlay.innerHTML = '<div style="max-width:30rem;width:100%;background:var(--input-bg,#1c1c1e);color:var(--text-color,#eee);border:1px solid var(--input-border,#333);border-radius:14px;padding:18px;">'
      + '<div style="font-weight:600;margin-bottom:8px;">字段结构升级</div>'
      + '<p style="font-size:13px;line-height:1.6;margin:0 0 12px;">检测到旧版字段结构。是否用 AI 把旧字段内容整理迁移到新版结构？（不迁移也可用，只是内容可能有些重复或错位）</p>'
      + '<label style="display:flex;align-items:center;gap:8px;font-size:12px;margin-bottom:14px;"><input type="checkbox" id="migrateSkipForever"> 以后不再询问</label>'
      + '<div style="display:flex;gap:8px;justify-content:flex-end;">'
      + '<button id="migrateSkip" class="btn-secondary" style="padding:7px 14px;border-radius:10px;font-size:13px;">跳过</button>'
      + '<button id="migrateRun" class="btn-secondary" style="padding:7px 14px;border-radius:10px;font-size:13px;">开始迁移</button>'
      + '</div></div>';
    document.body.appendChild(overlay);
    function finish(run) {
      var skipForever = !!(overlay.querySelector('#migrateSkipForever') && overlay.querySelector('#migrateSkipForever').checked);
      overlay.remove();
      resolve({ run: run, skipForever: skipForever });
    }
    overlay.querySelector('#migrateRun').addEventListener('click', function() { finish(true); });
    overlay.querySelector('#migrateSkip').addEventListener('click', function() { finish(false); });
  });
}

async function maybeMigrateFieldStructure() {
  if (window.__fieldMigrationRunning) return;
  if (FIELD_MIGRATION_TARGETS.length === 0) return;
  if (fieldMigrationSkipRequested()) return;
  window.__fieldMigrationRunning = true;
  try {
    var answer = await askFieldMigration();
    if (answer.skipForever) {
      markFieldMigrationSkip();
      // 勾选后即使跳过也不再询问：仍需把目标标记为已迁移，避免下次重新收集
      FIELD_MIGRATION_TARGETS.forEach(function(target) {
        var entity = state.characters[target.id];
        if (entity) markEntityFieldMigrated(entity);
      });
      scheduleSave();
      return;
    }
    if (!answer.run) return;
    if (!state.config || !state.config.apiKey) {
      alert('未配置 API Key，无法调用 AI 迁移。已按本地合并结果保留，下次启动会再次询问。');
      return;
    }
    for (var i = 0; i < FIELD_MIGRATION_TARGETS.length; i++) {
      var entity = state.characters[FIELD_MIGRATION_TARGETS[i].id];
      if (!entity) continue;
      await migrateEntityFields(entity);
      for (var m = 0; m < (entity.members || []).length; m++) {
        await migrateEntityFields(entity.members[m]);
      }
      markEntityFieldMigrated(entity);
      scheduleSave();
    }
    renderCharacterList();
    alert('字段结构迁移完成。');
  } catch (error) {
    console.warn('字段迁移失败:', error && error.message);
    alert('字段迁移失败：' + (error && error.message ? error.message : '未知错误') + '，已保留本地合并结果，下次启动可重试。');
  } finally {
    window.__fieldMigrationRunning = false;
    FIELD_MIGRATION_TARGETS = [];
  }
}

function markEntityFieldMigrated(entity) {
  if (!entity) return;
  entity.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION;
  (entity.members || []).forEach(function(member) { member.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION; });
}

