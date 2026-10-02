// ==================== Global State & Config ====================
const STORAGE_KEY = 'deeptalking_data_v1';
const DEBUG_STORAGE_KEY = 'deeptalking_last_empty_v1';
const DEBUG_REPLY_STORAGE_KEY = 'deeptalking_last_reply_v1';
const STORAGE_RECOVERY_KEY = STORAGE_KEY + '_recovery';
function escapeHtml(str) {
  return String(str == null ? '' : str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function renderMathExpression(tex, display) {
  var tag = display ? 'div' : 'span';
  var className = display ? 'math-display' : 'math-inline';
  try {
    return '<' + tag + ' class="' + className + '">' + window.katex.renderToString(toText(tex).trim(), { displayMode: display, throwOnError: false, strict: 'ignore', trust: false }) + '</' + tag + '>';
  } catch (error) {
    return '<' + tag + ' class="' + className + '">' + escapeHtml(tex) + '</' + tag + '>';
  }
}

function formatMessageContent(text) {
  var tokens = [];
  function token(html) { var marker = '\uE000' + tokens.length + '\uE001'; tokens.push(html); return marker; }
  var source = toText(text).replace(/```([^\n]*)\n([\s\S]*?)```/g, function(match, language, code) {
    return token('<pre><code data-language="' + escapeHtml(language.trim()) + '">' + escapeHtml(code) + '</code></pre>');
  }).replace(/\$\$([\s\S]+?)\$\$/g, function(match, tex) {
    return token(renderMathExpression(tex, true));
  }).replace(/\\\[([\s\S]+?)\\\]/g, function(match, tex) {
    return token(renderMathExpression(tex, true));
  }).replace(/\\\(([\s\S]+?)\\\)/g, function(match, tex) {
    return token(renderMathExpression(tex, false));
  }).replace(/(^|[^\\$])\$([^$\n]+)\$/g, function(match, prefix, tex) {
    return prefix + token(renderMathExpression(tex, false));
  });
  var lines = escapeHtml(source).split('\n');
  var html = [];
  var listType = '';
  function closeList() { if (listType) { html.push('</' + listType + '>'); listType = ''; } }
  function inline(value) {
    return value.replace(/`([^`\n]+)`/g, function(match, code) { return token('<code>' + code + '</code>'); })
      .replace(/!\[([^\]]*)\]\((https?:\/\/[^\s)]+)\)/g, function(match, alt, url) { return token('<img class="message-image" src="' + url + '" alt="' + alt + '" loading="lazy" onerror="this.remove()" onclick="openImagePreview(this.src)">'); })
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
      .replace(/\(([^()\n]+)\)|（([^（）\n]+)）/g, function(match) { return '<span class="action-text">' + match + '</span>'; })
      .replace(/\*\*([^*\n]+)\*\*|__([^_\n]+)__/g, function(match, a, b) { return '<strong>' + (a || b) + '</strong>'; })
      .replace(/~~([^~\n]+)~~/g, '<del>$1</del>')
      .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, function(match, pre, a) { return pre + '<em>' + a + '</em>'; })
      .replace(/(^|[^_])_([^_\n]+)_(?!_)/g, function(match, pre, a) { return pre + '<em>' + a + '</em>'; });
  }
  lines.forEach(function(line) {
    var match;
    if ((match = line.match(/^(#{1,3})\s+(.+)$/))) { closeList(); html.push('<h' + match[1].length + '>' + inline(match[2]) + '</h' + match[1].length + '>'); return; }
    if ((match = line.match(/^\s*[-*+]\s+(.+)$/))) { if (listType && listType !== 'ul') closeList(); if (!listType) { html.push('<ul>'); listType = 'ul'; } html.push('<li>' + inline(match[1]) + '</li>'); return; }
    if ((match = line.match(/^\s*\d+[.)]\s+(.+)$/))) { if (listType && listType !== 'ol') closeList(); if (!listType) { html.push('<ol>'); listType = 'ol'; } html.push('<li>' + inline(match[1]) + '</li>'); return; }
    closeList();
    if ((match = line.match(/^>\s?(.*)$/))) html.push('<blockquote>' + inline(match[1]) + '</blockquote>');
    else if (line) html.push('<div class="md-paragraph">' + inline(line) + '</div>');
    else html.push('<br>');
  });
  closeList();
  return html.join('').replace(/\uE000(\d+)\uE001/g, function(match, index) { return tokens[Number(index)] || ''; });
}
const DEFAULT_CONFIG = {
  apiPlatform: 'deepseek',
  apiBaseUrl: 'https://api.deepseek.com/v1',
  apiKey: '',
  modelName: 'deepseek-flash',
  temperature: 0.8,
  stream: true,
  reasoningEffort: 'medium',
  proactiveEnabled: true,
  styleCritique: true,
  quickReplyRepair: true,
  platformSettings: {}
};
const APP_VERSION = '__APP_VERSION__';
const PLATFORM_CONFIGS = {
  deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-flash', models: ['deepseek-flash'] },
  opencode: { baseUrl: 'https://opencode.ai/zen/go/v1', model: 'deepseek-flash', keepV1InResponses: true, models: ['deepseek-flash'] },
  custom: {}
};

let state = {
  config: { ...DEFAULT_CONFIG },
  characters: {},
  activeCharacterId: null,
  activeTheme: '',
  isProcessing: false,
  sidebarOpen: false,
  editingCharacterId: null,
  editingCharacterDraft: null,
  editingGroupMemberIndex: null,
  quickReplies: [],
  quickReplyCharacterId: null,
  quickReplyMessageId: null,
  pendingImages: [],
  editingStickerId: null,
  activityLog: []
};
const STICKER_TAGS = ['开心', '大笑', '难过', '委屈', '生气', '无语', '惊讶', '疑惑', '害羞', '得意', '爱意', '亲亲', '抱抱', '加油', '点赞', '拒绝', '睡觉', '干杯'];
const API_LIMITS = { auxiliaryTimeoutMs: 90000, requestMetrics: 60, prefixSnapshots: 12 };
const STICKER_LIMITS = {
  maxDimension: 384,
  targetBytes: 120 * 1024,
  maxTagChars: 6
};

const MEMORY_LIMITS = {
  instant: 160,
  instantTrimFloor: 40,
  shortTerm: 80,
  shortTermTrimFloor: 20,
  longTermPerCategory: 40,
  pendingRecall: 6,
  analysisBatch: 40,
  summarySources: 160
};
const PROMPT_LIMITS = {
  roleChars: 8000,
  memberChars: 900
};
const MEMORY_SUBJECTS = ['user', 'relationship', 'world', 'character', 'legacy'];
const PROMISE_STATUSES = ['active', 'resolved', 'cancelled'];
const DYNAMIC_STATE_FIELDS = [
  { key: 'currentSituation', label: '当前处境', type: 'textarea' },
  { key: 'currentLocation', label: '当前位置/场景' },
  { key: 'currentMood', label: '当前情绪' },
  { key: 'currentOccupation', label: '当前职业/身份' },
  { key: 'currentGoal', label: '当前目标' },
  { key: 'currentRelationship', label: '当前关系' },
  { key: 'currentImportantOthers', label: '当前重要他人' }
];
// 群组动态状态只保留"全员共用"的两项：共同处境 + 共同场景
const GROUP_SHARED_DYNAMIC_FIELDS = ['currentSituation', 'currentLocation'];
const STATIC_PROFILE_FIELDS = {
  gender: { label: '性别' }, age: { label: '年龄' }, race: { label: '种族' },
  appearance: { label: '外貌特征' }, personality: { label: '性格特征' }, values: { label: '价值观' },
  fears: { label: '恐惧/弱点' }, background: { label: '个人背景' }, keyEvents: { label: '关键过往（里程碑）' },
  speakingStyle: { label: '说话风格' }, language: { label: '语言/方言' },
  userAddress: { label: '对用户的称呼' }
};
// 群组实体不再镜像任何静态字段到 basicInfo（世界观并入 groupInfo.description），故共享静态字段为空
const GROUP_SHARED_STATIC_FIELDS = [];
// 字段结构迁移版本：旧版动态/静态字段在新版中被合并，需一次性迁移
const FIELDS_MIGRATION_VERSION = '1.2.0';
// 模型不需要的字段：不注入提示词（头像类只用于界面展示）
const NON_PROMPT_BASIC_FIELDS = ['avatar', 'avatarPixel'];
// 本轮节奏骨架：每轮随机取一条注入 volatile，避免回复结构雷同（只描述节奏，不输出标签）
const NARRATIVE_PATTERNS = [
  { label: '动作/描写 → 对白 → 动作/描写', hint: '先一两句场景、动作或情绪描写，再说 1-2 句台词，最后回到动作或环境收束。' },
  { label: '短句开场 → 描写 → 对白', hint: '先用一个很短的句子或独字反应（如“……嗯。”）开场，接一两句描写或心理，再落到 1-2 句台词。' },
  { label: '对白 → 描写 → 短句收尾', hint: '先直接说 1-2 句台词，中间穿插动作或环境描写，最后用一句很短的话或一个小动作收住。' },
  { label: '描写 → 短句 → 描写', hint: '以描写起手，中间插一句短促的台词或动作，再回到描写或环境。' },
  { label: '短句 → 描写 → 短句', hint: '首尾都用很短的句子，中间用一段描写承上启下，整体节奏偏顿挫。' },
  { label: '描写 → 对白 → 短句', hint: '先铺一小段描写或动作，再说 1-2 句台词，最后用一个短促的动作或半句话收住。' },
  { label: '对白 → 描写 → 对白', hint: '台词与描写交替：一句台词、一段描写、再一句台词，像真实对话里的停顿与反应。' },
  { label: '短句 → 描写 → 较长描写', hint: '先用短句点出反应，再用两段递进的描写展开当下的状态与氛围，但不做总结拔高。' },
  { label: '描写 → 对白 → 描写 → 对白', hint: '描写与台词两组交替推进，信息密度较高，但每句都要短，不要写成大段独白。' },
  { label: '对白 → 描写 → 描写', hint: '先给台词，再用两段描写承接（动作、表情、环境），收尾留在描写上，不要加总结句。' }
];
// 世界书（lorebook）：世界层设定的唯一去处。条目分两种模式——alwaysActive（常驻，每轮都注入）
// 与关键词命中（只在相关时注入）。AI 会在剧情推进中自动维护条目；用户可随时手改。
// 来源 origin 决定保护级别：user 条目永不被模型覆盖、也不参与自动淘汰。
const LOREBOOK_LIMITS = {
  entries: 200,
  keywordsPerEntry: 20,
  nameChars: 60,
  contentChars: 2000,
  injectEntries: 6,
  injectChars: 1400,
  scanMessages: 6,
  autoEntriesPerPass: 3,
  evictionMisses: 3,
  consolidateSpan: 8
};

// 记忆注入预算（字符）：检索记忆与摘要记忆共用一个池子，某一路没用完的额度让给另一路
const CONTEXT_BUDGET = {
  retrievedChars: 1200,
  summaryChars: 1600,
  sceneSummaries: 2,
  sceneInjectionChars: 600,
  sceneSpan: 24,
  volatileChars: 6000
};

// 语气/风格遵从度（attention）：把最容易违反的约束放到生成点最近处，并做客户端违规检测
const STYLE_GUARD = {
  reviewEveryTurns: 3,       // 每 N 轮追加一次「语气回顾」
  lookbackReplies: 2,        // 违规检测回看的近期回复条数
  anchorStyleChars: 300,     // 语气锚里说话风格的截断长度
  anchorSamples: 2,          // 语气锚里回灌的示例台词条数
  reusePhraseChars: 8,       // 视为"复用近期意象"的最短共享片段长度
  critiqueMaxChars: 1600,    // 修订稿长度上限，超过则回退原文
  critiqueMinRatio: 0.5,     // 修订稿短于原文该比例时视为异常，回退原文
  critiqueMaxRatio: 2.0      // 修订稿长于原文该比例时视为异常，回退原文
};

// 陈词滥调表（与规则 2.6 的示例一致，用于客户端违规检测）
const STYLE_CLICHES = ['心湖泛起涟漪', '勾起嘴角', '嘴角勾起', '空气中弥漫着', '不易察觉', '意味深长', '微微一愣', '眼神暗了暗', '不能自已', '宠溺地', '邪魅一笑'];

// 违规标签 → 给模型的纠正说明（内部提醒用，不展示给用户）
const STYLE_VIOLATION_LABELS = {
  metaTalk: '出现元话术（如"好的我改成/我会这样说话/收到，我调整"）——应直接演出，不要说明自己在调整',
  speaksForUser: '替用户写了台词或行动——用户的话只能由用户自己说',
  cliche: '出现陈词滥调（如"心湖泛起涟漪""勾起嘴角""空气中弥漫着"）',
  reusedImagery: '复用了近期用过的比喻、意象或收尾方式',
  toneDrift: '没有体现设定的说话风格（口癖/句尾/称呼），或句长过于整齐缺少起伏',
  recitedLore: '复述了世界书或设定条目本身，应当只在需要时自然引用'
};

// 快速回应（quickReplies）必须是"用户下一句要发给角色的话"：客户端做视角校验，
// 命中问题就换一个专门扮演"用户本人"的后台小调用重写（不阻塞正文、不占主链路请求）。
const QUICK_REPLY_GUARD = {
  maxChars: 60,          // 单条上限（超过则判为不像用户随手打的一句话）
  mirrorChars: 8,        // 与角色本轮/上轮台词逐字重合多少字即判为"复述角色台词"
  repairMaxChars: 40     // 换位生成时要求单条不超过的字数
};

const QUICK_REPLY_ISSUE_LABELS = {
  missing: '没有给出两条可用短句',
  placeholder: '把示例占位文字（如"短句一/短句二"）当成了真内容',
  action: '带了括号动作或旁白（快速回应只写用户会打的字）',
  mirrored: '复述或镜像了角色刚说过的话',
  characterName: '出现了角色自己的名字，像是在用角色口吻说话',
  tooLong: '单条过长，不像用户随手打的一句话'
};

// 占位符/模板串：模型偶尔会照抄 JSON 示例里的"短句一""用户下一句1"
const QUICK_REPLY_PLACEHOLDERS = ['短句一', '短句二', '短句1', '短句2', '用户下一句', '用户下一句1', '用户下一句2', '示例一', '示例二', '...', '…'];
