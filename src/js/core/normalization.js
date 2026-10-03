// 长会话使用的精简 JSON 示例（新会话前几轮用完整示例，之后换成这一版，省 token）
const JSON_EXAMPLE_BRIEF = '8. JSON格式示例（未更新字段直接省略；无合格记忆返回空数组或空对象，但quickReplies仍必须两条）：{"reply":"（穿插全角括号动作）角色本轮回复正文。","quickReplies":["我慢慢说，你先别催我。","你绕了两条街？也不嫌累。"],"shortTerm":[{"content":"本轮事件摘要，写明谁做了什么并写绝对日期（如2026-08-10 晚上）","sourceMessageIds":["msg_id"],"timeRef":{"anchor":"today"}}],"dynamicState":{"currentMood":{"value":"当前情绪。","sourceMessageIds":["current_response"],"evidence":"概括本轮回复"},"currentSituation":{"value":"当前处境，写具体日期+时段。","sourceMessageIds":["真实ID或current_response"],"evidence":"对应来源逐字摘录"}},"longTerm":[{"category":"userProfile","subject":"user","key":"稳定标识","value":"用户的具体事实。","tags":["关键词"],"importance":5,"sourceMessageIds":["msg_id"],"evidence":"用户原话","eventTime":"ISO时间"}]}。示例仅示范字段结构、动作穿插方式与quickReplies的用户视角，**不得模仿其口吻或措辞**（口吻一律以角色设定的“说话风格”为准）。JSON字符串转义：真实换行写\\n，字面反斜杠写\\\\，双引号写\\"。\n';

function createMemoryId(prefix) {
  return (prefix || 'mem') + '_' + Date.now() + '_' + Math.random().toString(36).slice(2, 9);
}

// 对用户的称呼：单一短词（≤20字），去空白与换行
function normalizeUserAddress(value) {
  return toText(value).replace(/\s+/g, '').slice(0, 20);
}

function normalizeTimestamp(value, fallback) {
  if (value == null || value === '') return fallback;
  var date = new Date(value);
  return isNaN(date.getTime()) ? fallback : date.toISOString();
}

function normalizeRetryAt(value) {
  var timestamp = Date.parse(value || '');
  return timestamp > Date.now() ? new Date(timestamp).toISOString() : null;
}

function normalizeTemperature(value) {
  var temperature = Number(value);
  return Number.isFinite(temperature) ? Math.max(0, Math.min(2, temperature)) : DEFAULT_CONFIG.temperature;
}

const REASONING_EFFORTS = ['none', 'low', 'medium', 'high', 'max'];

function normalizeReasoningEffort(value) {
  return REASONING_EFFORTS.indexOf(value) !== -1 ? value : DEFAULT_CONFIG.reasoningEffort;
}

function normalizeCacheStats(value) {
  if (!isPlainObject(value)) return null;
  var hitTokens = typeof value.hitTokens === 'number' ? value.hitTokens : NaN;
  var missTokens = typeof value.missTokens === 'number' ? value.missTokens : NaN;
  var promptTokens = typeof value.promptTokens === 'number' ? value.promptTokens : NaN;
  var hasCacheFields = Number.isFinite(hitTokens) && Number.isFinite(missTokens);
  if (!hasCacheFields && !Number.isFinite(promptTokens)) return null;
  return {
    hitTokens: hasCacheFields ? Math.max(0, Math.floor(hitTokens)) : null,
    missTokens: hasCacheFields ? Math.max(0, Math.floor(missTokens)) : null,
    promptTokens: Number.isFinite(promptTokens) && promptTokens >= 0 ? Math.floor(promptTokens) : null,
    updatedAt: normalizeTimestamp(value.updatedAt, new Date().toISOString())
  };
}

function normalizeApiBaseUrl(value) {
  return toText(value).trim().replace(/\/+$/, '');
}

function getSessionIdFor(char) {
  return char && char.id ? 'deeptalking-' + char.id : 'deeptalking-general';
}

function getActiveSessionId() {
  return getSessionIdFor(state.characters && state.characters[state.activeCharacterId]);
}

function buildApiHeaders(config, sessionId) {
  if (!config || !toText(config.apiKey).trim()) throw new Error('未配置API Key，请在设置中填写');
  var headers = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + config.apiKey };
  if (config.stream !== false) {
    headers.Accept = 'text/event-stream';
  }
  // opencode Go 网关强制要求每个会话发送稳定 x-opencode-session（缺失返回 400 MissingSessionID）
  if (config.apiPlatform === 'opencode') {
    headers['x-opencode-session'] = sessionId || getActiveSessionId();
  }
  return headers;
}


function trimText(value, maxLength) {
  var text = toText(value).trim();
  return maxLength && text.length > maxLength ? text.slice(0, maxLength) + '…' : text;
}
