function toolActivityHint(name) {
  var hints = {
    get_current_time: '正在确认时间…',
    search_memory: '正在回忆…',
    list_memories: '正在整理记忆…',
    delete_memory: '正在清理记忆…',
    set_reminder: '已记下，正在回应…',
    update_character_field: '已按你的要求调整设定…',
    upsert_lorebook_entry: '已更新世界书…',
    web_search: '正在搜索…',
    web_fetch: '正在读取网页…',
    send_sticker: '正在挑表情…'
  };
  return hints[name] || '正在处理…';
}

// 主对话循环的唯一状态决策（纯函数）：决定下一步动作与右上角要显示的状态文案。
// stage: 'quick-replies-missing' | 'auto-tool-limit' | 'auto-tool' | 'auto-final' | 'submit-retry' | 'empty'
function chatStageDecision(stage, ctx) {
  ctx = ctx || {};
  switch (stage) {
    case 'quick-replies-missing':
      // 快速回应不再阻塞收尾：缺失/非法一律接受正文，由后台换位生成补齐（不再多发一轮"补齐快速回应"）
      return { action: 'accept-reply', activity: '', bubble: '' };
    case 'auto-tool-limit':
      return ctx.hasText
        ? { action: 'accept-text', activity: '工具调用已达上限，直接收尾…', bubble: '' }
        : { action: 'force-submit', activity: '正在整理回复…', bubble: '正在整理回复…' };
    case 'auto-tool':
      return { action: 'run-tool', activity: '工具结果已返回，正在继续推理…', bubble: toolActivityHint(ctx.toolName) };
    case 'auto-final':
      // 只要拿到了正文（合法 JSON 或散文）就直接采用：散文即终稿，记忆/状态交给后台补。
      // 仅当阶段一既无合法 JSON 又没有任何正文时，才退到阶段二强制 submit_response。
      return (ctx.structuredOk || ctx.hasText)
        ? { action: 'accept-text', activity: '', bubble: '' }
        : { action: 'force-submit', activity: '正在整理回复…', bubble: '正在整理回复…' };
    case 'submit-retry':
      return { action: 'retry-submit', activity: '正在重新整理回复…', bubble: '正在重新整理回复…' };
    case 'empty':
      return ctx.attemptsLeft > 0
        ? { action: 'regenerate', activity: '正在重新生成（第2次）…', bubble: '正在重新生成…' }
        : { action: 'fail', activity: '', bubble: '' };
    default:
      return { action: 'unknown', activity: '', bubble: '' };
  }
}

function setActivity(text) {
  state.activityLog = Array.isArray(state.activityLog) ? state.activityLog : [];
  state.activityLog.push({ at: new Date().toISOString(), text: text });
  if (state.activityLog.length > 20) state.activityLog = state.activityLog.slice(-20);
  var element = document.getElementById('activityStatusBar');
  if (!element) return;
  element.textContent = text;
  element.classList.remove('hidden');
  var cacheBar = document.getElementById('cacheStatsBar');
  if (cacheBar && !cacheBar.classList.contains('hidden')) cacheBar.classList.add('hidden');
}

function clearActivity() {
  var element = document.getElementById('activityStatusBar');
  if (!element) return;
  element.classList.add('hidden');
  renderCacheStats();
}

function renderDebugInfo() {
  var wrap = document.getElementById('debugInfoWrap');
  var pre = document.getElementById('debugInfo');
  if (!wrap || !pre) return;
  var parts = [];
  var replyRaw = null;
  try { replyRaw = localStorage.getItem(DEBUG_REPLY_STORAGE_KEY); } catch (e) { replyRaw = null; }
  if (replyRaw) {
    try {
      var replyData = JSON.parse(replyRaw);
      if (isPlainObject(replyData)) {
        parts.push('【最近一次回应】\n' + JSON.stringify(replyData, null, 2));
        if (typeof replyData.fullText === 'string' && replyData.fullText) {
          parts.push('【原始回应（未处理）】\n' + replyData.fullText);
        }
      } else {
        parts.push('【最近一次回应】\n' + replyRaw);
      }
    } catch (e) {
      parts.push('【最近一次回应】\n' + replyRaw);
    }
  }
  if (state.config && Array.isArray(state.config.requestMetrics) && state.config.requestMetrics.length) {
    var metrics = state.config.requestMetrics.slice(-12).map(function(item) {
      return { at: item.at, taskType: item.taskType, characterId: item.characterId,
        inputTokens: item.inputTokens, hitTokens: item.hitTokens, missTokens: item.missTokens, hitRate: item.hitRate };
    });
    parts.push('【最近 API 用量与缓存统计】\n' + JSON.stringify(metrics, null, 2));
  }
  if (parts.length === 0) { wrap.classList.add('hidden'); pre.textContent = ''; return; }
  pre.textContent = parts.join('\n\n');
  wrap.classList.remove('hidden');
}

function copyDebugInfo() {
  var pre = document.getElementById('debugInfo');
  if (!pre || !pre.textContent) return;
  var payload = pre.textContent;
  function fallbackCopy() {
    var textArea = document.createElement('textarea');
    textArea.value = payload;
    textArea.style.position = 'fixed';
    textArea.style.opacity = '0';
    document.body.appendChild(textArea);
    textArea.select();
    try { document.execCommand('copy'); } catch (e) { /* ignore */ }
    document.body.removeChild(textArea);
    alert('调试信息已复制');
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(payload).then(function() { alert('调试信息已复制'); }, fallbackCopy);
  } else {
    fallbackCopy();
  }
}

function openCreateModal() {
  document.getElementById('createModal').classList.remove('hidden');
  document.getElementById('createModal').classList.add('flex');
  document.getElementById('newCharName').value = '';
  document.getElementById('newCharAvatar').value = '';
  document.getElementById('newCharPersonality').value = '';
  document.getElementById('newCharBackground').value = '';
  document.getElementById('quickGenInput').value = '';
  document.getElementById('newEntityType').value = 'character';
  document.getElementById('newGroupMembers').value = '';
  window._tempCharData = null;
  syncCreateEntityForm();
}

function closeCreateModal() {
  document.getElementById('createModal').classList.add('hidden');
  document.getElementById('createModal').classList.remove('flex');
}

function syncCreateEntityForm() {
  var isGroup = document.getElementById('newEntityType').value === 'group';
  document.getElementById('createModalTitle').textContent = isGroup ? '创建新群组' : '创建新角色';
  document.getElementById('newEntityNameLabel').textContent = isGroup ? '群组名称 *' : '角色名称 *';
  document.getElementById('quickGenInput').placeholder = isGroup ? '描述你想要的群组和成员...' : '描述你想要的角色...';
  document.getElementById('newGroupMembersWrap').classList.toggle('hidden', !isGroup);
}

// 获取某平台的独立配置槽（不存在则初始化）
function getPlatformSettings(platform) {
  if (!state.config.platformSettings) state.config.platformSettings = {};
  if (!state.config.platformSettings[platform]) state.config.platformSettings[platform] = {};
  return state.config.platformSettings[platform];
}

// 把当前输入框里的值存入指定平台的槽
function savePlatformFields(platform) {
  var slot = getPlatformSettings(platform);
  slot.baseUrl = normalizeApiBaseUrl(document.getElementById('apiBaseUrl').value);
  slot.apiKey = document.getElementById('apiKey').value.trim();
  slot.modelName = document.getElementById('modelName').value.trim();
}

// 把指定平台的槽/默认值填入输入框
function applyPlatformFields(platform) {
  var slot = getPlatformSettings(platform);
  var preset = PLATFORM_CONFIGS[platform] || {};
  document.getElementById('apiBaseUrl').value = slot.baseUrl || preset.baseUrl || '';
  document.getElementById('apiKey').value = slot.apiKey != null ? slot.apiKey : '';
  var modelEl = document.getElementById('modelName');
  modelEl.value = slot.modelName || preset.model || '';
  renderModelOptions(platform);
}

// 连接诊断：区分「网络可达性」与「接口/CORS」问题
async function testApiConnection() {
  var resultEl = document.getElementById('testConnResult');
  var btn = document.getElementById('testConnBtn');
  if (!resultEl || !btn) return;
  btn.disabled = true;
  btn.textContent = '测试中…';
  resultEl.classList.remove('hidden');
  resultEl.textContent = '正在测试…';
  try {
    var baseUrl = normalizeApiBaseUrl(document.getElementById('apiBaseUrl').value);
    var key = document.getElementById('apiKey').value.trim();
    var platform = document.getElementById('apiPlatform').value;
    var model = document.getElementById('modelName').value.trim() || 'deepseek-flash';
    // ① 站点可达性：no-cors 模式不做 CORS 检查，只测能否建立连接
    var reachable = '未知';
    try {
      await fetch(baseUrl.replace(/\/+$/, '') + '/', { method: 'GET', mode: 'no-cors', cache: 'no-store' });
      reachable = '可达（能建立连接）';
    } catch (e) {
      reachable = '无法连接（' + e.name + '：' + e.message + '）。可能是网络不通、域名无法解析或被拦截';
    }
    // ② 接口调用：正常 cors POST 最小请求
    var endpoint = getResponsesEndpoint({ apiPlatform: platform, apiBaseUrl: baseUrl });
    var postResult = '未知';
    try {
      var res = await fetch(endpoint, {
        method: 'POST',
        headers: buildApiHeaders({ apiPlatform: platform, stream: false, apiKey: key }, 'deeptalking-general'),
        body: JSON.stringify({ model: model, input: [{ role: 'user', content: 'hi' }], max_output_tokens: 50, stream: false, reasoning: { effort: 'none' } })
      });
      var text = await res.text();
      var parsed = null;
      try { parsed = JSON.parse(text); } catch (e) { /* 非 JSON */ }
      postResult = 'HTTP ' + res.status;
      if (parsed && parsed.error) postResult += '，服务端报错：' + (parsed.error.message || parsed.error.type || '');
      else if (parsed && (Array.isArray(parsed.output) || typeof parsed.output_text === 'string')) postResult += '，正常返回';
      else postResult += '（响应不是标准 JSON，可能端点到错了）';
    } catch (e) {
      postResult = '失败（' + e.name + '：' + e.message + '）。若站点可达但此处失败，多半是 CORS 被拦';
    }
    resultEl.textContent = '① 站点可达性：' + reachable + '\n② 接口调用：' + postResult + '\n当前端点：' + endpoint;
  } finally {
    btn.disabled = false;
    btn.textContent = '测试连接';
  }
}

function changePlatform() {
  var from = state.config.apiPlatform;
  var to = document.getElementById('apiPlatform').value;
  if (from === to) {
    renderModelOptions(to);
    renderCacheStats();
    return;
  }
  // 先保存当前平台的输入值，再载入目标平台的值（各自互不覆盖）
  savePlatformFields(from);
  state.config.apiPlatform = to;
  applyPlatformFields(to);
  renderCacheStats();
  saveSettings();
}

function renderModelOptions(platform) {
  var options = document.getElementById('modelOptions');
  var config = PLATFORM_CONFIGS[platform] || PLATFORM_CONFIGS.custom;
  options.innerHTML = (config.models || []).map(function(model) {
    return '<option value="' + escapeHtml(model) + '"></option>';
  }).join('');
}

function saveSettings() {
  var platform = document.getElementById('apiPlatform').value;
  state.config.apiPlatform = platform;
  state.config.apiBaseUrl = normalizeApiBaseUrl(document.getElementById('apiBaseUrl').value);
  state.config.apiKey = document.getElementById('apiKey').value.trim();
  state.config.modelName = document.getElementById('modelName').value.trim();
  state.config.temperature = normalizeTemperature(document.getElementById('apiTemperature').value);
  state.config.stream = document.getElementById('apiStream').checked;
  state.config.reasoningEffort = normalizeReasoningEffort(document.getElementById('apiThinking').value);
  var proactiveToggle = document.getElementById('proactiveToggle');
  if (proactiveToggle) state.config.proactiveEnabled = proactiveToggle.checked;
  var styleCritiqueToggle = document.getElementById('styleCritiqueToggle');
  if (styleCritiqueToggle) state.config.styleCritique = styleCritiqueToggle.checked;
  var quickReplyRepairToggle2 = document.getElementById('quickReplyRepairToggle');
  if (quickReplyRepairToggle2) state.config.quickReplyRepair = quickReplyRepairToggle2.checked;
  savePlatformFields(platform);
  scheduleSave();
}
