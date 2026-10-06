// Shared scenario fixtures for the JS <-> Kotlin differential oracle.
// Each scenario describes a character, a config and a user turn. The JS runner
// (run-js.mjs) and the Kotlin test both consume the SAME JSON so their outputs
// can be compared field by field.

export const FIXED_NOW = '2026-10-04T10:00:00.000Z';

// The Kotlin model stores the profile in `staticProfile`/`dynamicState`,
// while the legacy JS uses `basicInfo`. Emit both shapes from one source.
export function toNativeCharacter(legacy) {
  const b = legacy.basicInfo || {};
  const d = legacy.dynamicState || {};
  const mem = legacy.memory || {};
  const lt = mem.longTerm || {};
  return {
    id: legacy.id,
    name: b.name || '',
    emoji: b.avatar || '',
    description: b.personality || '',
    isGroup: legacy.entityType === 'group',
    staticProfile: {
      gender: b.gender || '',
      age: b.age || '',
      race: b.race || '',
      appearance: b.appearance || '',
      personality: b.personality || '',
      values: b.values || '',
      fears: b.fears || '',
      background: b.background || '',
      keyEvents: b.keyEvents || '',
      speakingStyle: b.speakingStyle || '',
      language: b.language || '',
      userAddress: b.userAddress || '',
    },
    dynamicState: {
      currentSituation: d.currentSituation || '',
      currentLocation: d.currentLocation || '',
      currentMood: d.currentMood || '',
      currentOccupation: d.currentOccupation || '',
      currentGoal: d.currentGoal || '',
      currentRelationship: d.currentRelationship || '',
      currentImportantOthers: d.currentImportantOthers || '',
    },
    instant: (mem.instant || []).map((m) => ({ id: m.id, role: m.role, content: m.content, timestamp: m.timestamp, internalOnly: m.internalOnly === true })),
    shortTerm: mem.shortTerm || [],
    longTerm: []
      .concat((lt.userProfile || []).map((x) => ({ ...x, category: 'userProfile', subject: 'user' })))
      .concat((lt.relationship || []).map((x) => ({ ...x, category: 'relationship', subject: 'relationship' })))
      .concat((lt.events || []).map((x) => ({ ...x, category: 'events', subject: 'legacy' })))
      .concat((lt.promises || []).map((x) => ({ ...x, category: 'promises', subject: 'relationship' })))
      .concat((lt.habits || []).map((x) => ({ ...x, category: 'habits', subject: 'user' })))
      .map((x) => ({
        id: x.id, category: x.category, subject: x.subject, key: x.key, value: x.value,
        tags: x.tags || [], importance: x.importance || 0, sourceMessageIds: x.sourceMessageIds || [],
        evidence: x.evidence || '', eventTime: x.eventTime || null, dueAt: x.dueAt || null,
        promisor: x.promisor || null, promisee: x.promisee || null, status: x.status || 'active',
        createdAt: x.createdAt || null, updatedAt: x.updatedAt || null,
      })),
    lorebook: (legacy.lorebook || []).map((e) => ({
      id: e.id, name: e.name, content: e.content, keywords: e.keywords || [],
      alwaysActive: e.alwaysActive === true, origin: e.origin === 'user' ? 'user' : 'ai',
    })),
    stickers: [],
    pendingRecall: mem.pendingRecall || null,
    scenes: [],
  };
}

/** A base character shared by most scenarios. */
function baseCharacter(overrides = {}) {
  return {
    id: 'c1',
    entityType: 'character',
    basicInfo: {
      name: '林晚',
      avatar: '🌙',
      gender: '女',
      age: '24',
      race: '人类',
      appearance: '长发',
      personality: '冷静克制，话不多。',
      values: '重视承诺',
      fears: '害怕被遗忘',
      background: '在一座海边小城长大。',
      keyEvents: '三年前离开家乡。',
      speakingStyle: '句子偏短，偶尔用“……”。',
      language: '普通话',
      userAddress: '你',
    },
    dynamicState: {
      currentSituation: '傍晚在海边散步。',
      currentLocation: '海边栈道',
      currentMood: '平静',
      currentOccupation: '自由撰稿人',
      currentGoal: '写完一篇稿子',
      currentRelationship: '与用户是朋友',
      currentImportantOthers: '无',
    },
    memory: {
      instant: [
        { id: 'm1', role: 'user', content: '我们上周约好今天来看海。', timestamp: '2026-10-04T09:58:00.000Z' },
        { id: 'm2', role: 'assistant', content: '嗯，我记着。', timestamp: '2026-10-04T09:58:05.000Z' },
      ],
      shortTerm: [],
      longTerm: {
        userProfile: [
          { id: 'lt1', category: 'userProfile', key: '用户姓名', value: '用户叫阿哲', tags: ['名字'], importance: 7, subject: 'user', sourceMessageIds: ['m1'], evidence: '我叫阿哲', createdAt: '2026-10-01T00:00:00.000Z', updatedAt: '2026-10-01T00:00:00.000Z', status: 'active' },
        ],
        relationship: [],
        events: [],
        promises: [],
        habits: [],
      },
      pendingRecall: null,
    },
    lorebook: [
      { id: 'lb1', name: '海边小城', content: '城市名叫“潮汐镇”，终年多雾。', keywords: ['潮汐镇', '海边'], alwaysActive: false, origin: 'user', misses: 0 },
      { id: 'lb2', name: '世界规则', content: '这个世界没有魔法。', keywords: [], alwaysActive: true, origin: 'ai', misses: 0 },
    ],
    stickers: [],
    ...overrides,
  };
}

export const SCENARIOS = [
  {
    id: 'basic-user-turn',
    description: '普通一轮：用户发言、有短期记忆与世界书，检查 system prompt 与 volatile 上下文',
    config: { apiPlatform: 'deepseek', modelName: 'deepseek-flash', temperature: 0.8, stream: true, reasoningEffort: 'medium', proactiveEnabled: false, styleCritique: false, quickReplyRepair: false },
    character: baseCharacter(),
    query: '阿哲：今天的海很好看。',
    phase: 'auto',
  },
  {
    id: 'lorebook-keyword-hit',
    description: '世界书关键词命中：查询中包含“潮汐镇”',
    config: { apiPlatform: 'deepseek', modelName: 'deepseek-flash', temperature: 0.8, stream: true, reasoningEffort: 'medium', proactiveEnabled: false, styleCritique: false, quickReplyRepair: false },
    character: baseCharacter(),
    query: '还记得潮汐镇的路吗？',
    phase: 'auto',
  },
  {
    id: 'promise-context',
    description: '存在未完成约定：应出现在 volatile 的承诺区',
    config: { apiPlatform: 'deepseek', modelName: 'deepseek-flash', temperature: 0.8, stream: true, reasoningEffort: 'medium', proactiveEnabled: false, styleCritique: false, quickReplyRepair: false },
    character: baseCharacter({
      memory: {
        instant: [{ id: 'm1', role: 'user', content: '下周一起去看展吧。', timestamp: '2026-10-04T09:58:00.000Z' }],
        shortTerm: [],
        longTerm: {
          userProfile: [], relationship: [], events: [], habits: [],
          promises: [{ id: 'p1', category: 'promises', key: '一起看展', value: '用户与角色约定2026-10-11一起看展', tags: ['约定'], importance: 8, subject: 'relationship', promisor: 'relationship', promisee: 'relationship', dueAt: '2026-10-11T02:00:00.000Z', status: 'active', createdAt: '2026-10-01T00:00:00.000Z', updatedAt: '2026-10-01T00:00:00.000Z' }],
        },
        pendingRecall: null,
      },
    }),
    query: '我们之前约了什么来着？',
    phase: 'auto',
  },
  {
    id: 'submit-phase-body',
    description: '阶段二请求体：锁定 submit_response 工具',
    config: { apiPlatform: 'deepseek', modelName: 'deepseek-flash', temperature: 0.8, stream: false, reasoningEffort: 'medium', proactiveEnabled: false, styleCritique: false, quickReplyRepair: false },
    character: baseCharacter(),
    query: '继续。',
    phase: 'submit',
  },
  {
    id: 'promise-out-of-window',
    description: '存在一条与查询无关且到期日超出 ±(1d/7d) 窗口的承诺：不得注入 volatile 的承诺区',
    config: { apiPlatform: 'deepseek', modelName: 'deepseek-flash', temperature: 0.8, stream: true, reasoningEffort: 'medium', proactiveEnabled: false, styleCritique: false, quickReplyRepair: false },
    character: baseCharacter({
      memory: {
        instant: [{ id: 'm1', role: 'user', content: '今天想吃点甜的。', timestamp: '2026-10-04T09:58:00.000Z' }],
        shortTerm: [],
        longTerm: {
          userProfile: [], relationship: [], events: [], habits: [],
          promises: [{ id: 'p9', category: 'promises', key: '遥远旅行', value: '用户与角色约定很久以后去旅行', tags: ['约定'], importance: 5, subject: 'relationship', promisor: 'relationship', promisee: 'relationship', dueAt: '2027-06-01T02:00:00.000Z', status: 'active', createdAt: '2026-10-01T00:00:00.000Z', updatedAt: '2026-10-01T00:00:00.000Z' }],
        },
        pendingRecall: null,
      },
    }),
    query: '今天天气怎么样？',
    phase: 'auto',
  },
  {
    id: 'lorebook-recent-only',
    description: '世界书关键词只出现在最近一条消息、不在本轮 query：仍应注入相关条目',
    config: { apiPlatform: 'deepseek', modelName: 'deepseek-flash', temperature: 0.8, stream: true, reasoningEffort: 'medium', proactiveEnabled: false, styleCritique: false, quickReplyRepair: false },
    character: baseCharacter({
      memory: {
        instant: [
          { id: 'm1', role: 'assistant', content: '我们刚才路过了那座灯塔。', timestamp: '2026-10-04T09:57:00.000Z' },
          { id: 'm2', role: 'user', content: '嗯，风很大。', timestamp: '2026-10-04T09:58:00.000Z' },
        ],
        shortTerm: [],
        longTerm: { userProfile: [], relationship: [], events: [], promises: [], habits: [] },
        pendingRecall: null,
      },
      lorebook: [
        { id: 'lb3', name: '灯塔', content: '海边的灯塔每到夜里会亮起。', keywords: ['灯塔'], alwaysActive: false, origin: 'user', misses: 0 },
      ],
    }),
    query: '接下来去哪儿？',
    phase: 'auto',
  },
  {
    id: 'role-char-cap',
    description: '超长角色设定：非群角色上下文应被裁剪到 8000 字上限',
    config: { apiPlatform: 'deepseek', modelName: 'deepseek-flash', temperature: 0.8, stream: true, reasoningEffort: 'medium', proactiveEnabled: false, styleCritique: false, quickReplyRepair: false },
    character: baseCharacter({
      basicInfo: {
        name: '林晚',
        avatar: '🌙',
        background: '背'.repeat(9000),
      },
    }),
    query: '在吗？',
    phase: 'auto',
  },
];

