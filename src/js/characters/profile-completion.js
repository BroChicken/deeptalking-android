// ==================== 空静态字段自动补全（无按钮，静默） ====================
// 只补 STATIC_PROFILE_FIELDS 中当前为空的字段：单角色/成员补全部；群组实体不维护静态字段
function collectStaticFillJobs() {
  var jobs = [];
  Object.keys(state.characters).forEach(function(id) {
    var entity = state.characters[id];
    if (!entity || !entity.basicInfo) return;
    if (entity.entityType === 'group') {
      var groupMissing = GROUP_SHARED_STATIC_FIELDS.filter(function(key) { return !toText(entity.basicInfo[key]).trim(); });
      if (groupMissing.length > 0) jobs.push({ target: entity, host: entity, missing: groupMissing });
      (entity.members || []).forEach(function(member) {
        if (!member || !member.basicInfo) return;
        var memberMissing = Object.keys(STATIC_PROFILE_FIELDS).filter(function(key) { return !toText(member.basicInfo[key]).trim(); });
        if (memberMissing.length > 0) jobs.push({ target: member, host: entity, missing: memberMissing });
      });
    } else {
      var charMissing = Object.keys(STATIC_PROFILE_FIELDS).filter(function(key) { return !toText(entity.basicInfo[key]).trim(); });
      if (charMissing.length > 0) jobs.push({ target: entity, host: entity, missing: charMissing });
    }
  });
  return jobs;
}

function canAttemptStaticFill(target, now) {
  var meta = (target && target.staticFillMeta) || {};
  if (meta.retryAt && Date.parse(meta.retryAt) > now) return false;
  var attempts = Number(meta.failures) || 0;
  var cooldown = attempts > 0 ? Math.min(30 * 60000, 5 * 60000 * Math.pow(2, attempts - 1)) : 24 * 3600000;
  if (meta.attemptedAt && now - Date.parse(meta.attemptedAt) < cooldown) return false;
  return true;
}

async function fillStaticFieldsForJob(job) {
  var target = job.target;
  var guard = captureMemoryTask(job.host);
  var labels = job.missing.map(function(key) { return STATIC_PROFILE_FIELDS[key].label + '(' + key + ')'; }).join('、');
  var contextLines = [];
  if (job.host && job.host !== target && job.host.entityType === 'group') {
    contextLines.push('所属群组设定:\n' + JSON.stringify({
      name: toText(job.host.groupInfo && job.host.groupInfo.name),
      description: toText(job.host.groupInfo && job.host.groupInfo.description),
      scene: toText(job.host.groupInfo && job.host.groupInfo.scene),
      members: (job.host.members || []).map(function(member) { return toText(member.basicInfo && member.basicInfo.name); }).filter(Boolean)
    }));
  }
  var existing = {};
  Object.keys(STATIC_PROFILE_FIELDS).forEach(function(key) {
    if (job.missing.indexOf(key) !== -1) return;
    var value = toText(target.basicInfo[key]).trim();
    if (value) existing[key] = trimText(value, 300);
  });
  var prompt = '请只补全下列【当前为空】的字段：' + labels + '。\n'
    + '要求：只补空字段，绝不修改或覆盖已有设定（已有设定是绝对权威，不得改写、润色或替换）；只根据已知信息合理补写，没有把握的字段直接省略；不要编造与已有设定冲突的内容。'
    + (job.missing.indexOf('speakingStyle') !== -1 ? SPEAKING_STYLE_SAMPLES_RULE : '')
    + (job.host && job.host !== target && job.host.entityType === 'group' && job.missing.indexOf('speakingStyle') !== -1 ? '该成员的说话方式必须与群内其他成员显著不同（看台词就能分辨是谁）。' : '')
    + (job.missing.indexOf('userAddress') !== -1 ? '“对用户的称呼”(userAddress) 只填一个简短称呼词（如“明明”“老公”），不带任何解释。' : '')
    + '\n' + contextLines.join('\n')
    + '\n已有设定:\n' + JSON.stringify(existing)
    + '\n只返回JSON对象，键为字段英文名，值为补写内容。';
  var response = await callAPI([
    { role: 'system', content: '你是角色卡补全助手。只返回JSON。' },
    { role: 'user', content: prompt }
  ]);
  var data = parseJsonPayload(response.choices[0].message.content);
  if (!isMemoryTaskCurrent(guard)) return [];
  if (!isPlainObject(data)) throw new Error('补全结果格式错误');
  var filled = [];
  job.missing.forEach(function(key) {
    if (toText(target.basicInfo[key]).trim()) return;
    if (data[key] == null || !toText(data[key]).trim()) return;
      var value = key === 'userAddress' ? normalizeUserAddress(data[key]) : cleanFieldValue(key, parseRelativeText(toText(data[key]), new Date()));
    if (!value) return;
    target.basicInfo[key] = value;
    filled.push(key);
  });
  return filled;
}

async function autoFillStaticFields() {
  if (window.__staticFillRunning) return;
  if (!state.config || !state.config.apiKey) return;
  var now = Date.now();
  var jobs = collectStaticFillJobs().filter(function(job) { return canAttemptStaticFill(job.target, now); }).slice(0, 3);
  if (jobs.length === 0) return;
  window.__staticFillRunning = true;
  try {
    for (var i = 0; i < jobs.length; i++) {
      var job = jobs[i];
      if (!job.target.staticFillMeta) job.target.staticFillMeta = {};
      var meta = job.target.staticFillMeta;
      meta.attemptedAt = new Date().toISOString();
      try {
        var filled = await fillStaticFieldsForJob(job);
        if (filled.length > 0) {
          meta.failures = 0;
          meta.retryAt = null;
        } else {
          meta.failures = Math.min(10, (Number(meta.failures) || 0) + 1);
          meta.retryAt = new Date(Date.now() + 30 * 60000).toISOString();
        }
      } catch (error) {
        meta.failures = Math.min(10, (Number(meta.failures) || 0) + 1);
        meta.retryAt = new Date(Date.now() + Math.min(30 * 60000, 5 * 60000 * Math.pow(2, meta.failures - 1))).toISOString();
        console.warn('静态字段自动补全失败:', error && error.message);
      }
      scheduleSave();
      renderCharacterList();
      if (i < jobs.length - 1) await new Promise(function(resolve) { setTimeout(resolve, 300); });
    }
  } finally {
    window.__staticFillRunning = false;
  }
}
