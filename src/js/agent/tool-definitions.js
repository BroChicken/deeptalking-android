// ==================== Agent 工具系统 ====================
const MAX_TOOL_ROUNDS = 5;
const MAX_TOOL_CALLS = 12;
const AGENT_TOOL_MEMORY_INJECT_LIMIT = 5;

function isAgentToolsEnabled() {
  return isResponsesApiEnabled();
}

function buildAgentTools(char) {
  if (!isAgentToolsEnabled()) return [];
  var tools = [
    {
      type: 'function',
      name: 'get_current_time',
      description: '获取当前本地时间与日期（含时区、星期、ISO时间）。用于回答"现在几点/几号/星期几/距离某个时间多久"等问题。',
      parameters: { type: 'object', properties: {}, additionalProperties: false }
    },
    {
      type: 'function',
      name: 'search_memory',
      description: '检索记忆库中与关键词相关的内容：过往约定、用户喜好与习惯、共同经历的事件、关系事实等。需要回忆过去的事情、确认之前的承诺或约定时使用；结果按相关度排序，包含每条记忆的时间、分类、主体（subjectLabel 表示这条记忆讲的是谁）与内容。默认检索全员共享记忆；群组对话中若想检索某位成员的私人记忆，可传入 memberName（该成员自己的记忆，如"张三"。注意：若该成员的私人记忆里没有，也可能是共享记忆，必要时可再不带 memberName 检索一次）。',
      parameters: { type: 'object', properties: { query: { type: 'string', description: '要检索的关键词或短语' }, memberName: { type: 'string', description: '可选，群组对话中指定检索某位成员的私人记忆（不传则检索共享记忆）' } }, required: ['query'], additionalProperties: false }
    },
    {
      type: 'function',
      name: 'list_memories',
      description: '盘点指定分类的长期记忆清单（不含内容详情），用于主动发现过期、重复或错误的记忆。分类：userProfile（用户信息）、relationship（关系）、events（共同事件）、promises（约定）、habits（习惯）。可选按关键词过滤。返回每条记忆的ID、分类、关键词与更新时间。群组对话中可传 memberName 盘点某位成员的私人记忆。',
      parameters: { type: 'object', properties: { category: { type: 'string', enum: ['userProfile', 'relationship', 'events', 'promises', 'habits', ''] }, keyword: { type: 'string', description: '可选过滤关键词' }, limit: { type: 'integer', minimum: 1, maximum: 50 }, memberName: { type: 'string', description: '可选，群组对话中指定盘点某位成员的私人记忆（不传则盘点共享记忆）' } }, additionalProperties: false }
    },
    {
      type: 'function',
      name: 'delete_memory',
      description: '删除一条长期记忆（按ID）。仅用于：内容已被明确推翻或已过时失效、重复记录、或用户明确否认/纠正过的错误记忆。删除前建议先用 list_memories 或 search_memory 确认目标ID。群组对话中删除某位成员私人记忆时传入对应 memberName。',
      parameters: { type: 'object', properties: { id: { type: 'string', description: '要删除的记忆ID' }, memberName: { type: 'string', description: '可选，群组对话中指定删除某位成员的私人记忆（不传则删除共享记忆）' } }, required: ['id'], additionalProperties: false }
    },
    {
      type: 'function',
      name: 'set_reminder',
      description: '记录“需要用户参与”的待办/约定，且**必须由用户自己明确提出或同意**（如用户说“我明天要交报告”“记得提醒我买药”）。调用时必须附用户原话：sourceMessageIds 填用户消息ID、evidence 逐字摘录用户原话。角色要求用户去做的事、角色的建议或叮嘱，一律不得用本工具记录——应先自然征询用户，等用户自己答应后再记。到时间可在对话中温和提起。群组对话中若要记入某位成员的私人记忆可传 memberName。',
      parameters: { type: 'object', properties: { key: { type: 'string', description: '约定/待办的简短标题' }, value: { type: 'string', description: '约定详情，写绝对日期，不要写“明天”这类相对词' }, dueAt: { type: 'string', description: '可选，约定的截止时间（ISO格式），如 2026-08-10T09:00:00' }, timeRef: buildTimeRefSchema(), sourceMessageIds: { type: 'array', items: { type: 'string' }, description: '用户明确提出该待办的消息ID（必须是真实用户消息）' }, evidence: { type: 'string', description: '逐字摘录用户提出该待办的原话' }, memberName: { type: 'string', description: '可选，群组对话中指定记入某位成员的私人记忆（不传则记入共享记忆）' } }, required: ['key', 'value', 'sourceMessageIds', 'evidence'], additionalProperties: false }
    },
    {
      type: 'function',
      name: 'ask_user',
      description: '当记忆缺失、用户表述含糊或无法可靠推断某件关键事实时，向用户提出一个简短澄清问题，避免凭空编造。仅在确实需要澄清且追问不显得突兀时使用；不要滥用，也不要用它代替正常的角色回复。',
      parameters: { type: 'object', properties: { question: { type: 'string', description: '要问用户的问题' } }, required: ['question'], additionalProperties: false }
    },
    {
      type: 'function',
      name: 'update_character_field',
      description: '精准修改角色基础设定字段（性别/年龄/种族/外貌特征/性格特征/价值观/恐惧弱点/背景故事/关键过往/说话风格/语言方言/对用户的称呼）。当用户直接要求修改这些设定（如"把我职业改成教师""我现在是学生了""你性格应该更冷酷些""别叫我用户，叫我明明"）时，必须调用本工具立即生效；value一律以段落式的陈述句书写（用自然、完整的陈述句写成简短段落，如性格"性格冷静克制，遇事沉稳。"），禁止括号注释、理由、整句说明或解释性文字；"对用户的称呼"只填一个简短称呼词、不带任何解释。群组实体不维护基础设定字段（世界观并入群组描述），成员的个人字段请在该成员卡片上修改。不要在submit_response的staticFields里重复提交本工具已改的字段。',
      parameters: { type: 'object', properties: { field: { type: 'string', enum: Object.keys(STATIC_PROFILE_FIELDS), description: '要修改的字段' }, value: { type: 'string', description: '字段值：以段落式的陈述句书写（自然、完整的陈述句，简短段落）；禁止括号注释、理由、解释性文字；涉及日期写绝对日期' }, sourceMessageIds: { type: 'array', items: { type: 'string' }, description: '引用用户消息ID（用户原话所在消息）' }, evidence: { type: 'string', description: '逐字摘录用户要求修改的原话' }, timeRef: buildTimeRefSchema() }, required: ['field', 'value', 'sourceMessageIds', 'evidence'], additionalProperties: false }
    },
    {
      type: 'function',
      name: 'upsert_lorebook_entry',
      description: '写入/更新世界书条目——世界层设定的唯一去处（时代与世界观、地点、组织、专有名词、历史、规则、背景事实）。**同一件事物只能有一条**：先对照现有条目，凡名称相近、关键词相同或内容重叠的都算同一条，必须用它的 entryId/name 更新合并，绝不新建近似条目；已有 AI 条目只补充新信息、不要整段重写。只在近期对话里已经出现/确立、且**会反复复用**的这类设定，或用户补充修正了这类设定时才使用；一次性的小事、可从上下文直接看出的细节不要写。**用户手写条目（origin=user）受保护，不得覆盖或改写**；若现有用户条目已覆盖同一设定，不要重复写入。keywords 写剧情里可能出现的称呼（命中才注入）；只有确实需要每轮生效的世界前提/规则才把 alwaysActive 设为 true（常驻条目数量有限，不要滥用）。必须给出 sourceMessageIds 与 evidence 证明该设定已在对话中出现（本轮新编、尚未落库的内容不要写，等它出现在消息里再由整理任务沉淀）；拿不出依据就不要写。',
      parameters: { type: 'object', properties: {
        name: { type: 'string', description: '条目名（如「赤月王国」「银月商会」）；未给 entryId 时按名字匹配已有条目' },
        content: { type: 'string', description: '命中后注入的设定内容，只写该条目本身的信息，不写理由或解释' },
        keywords: { type: 'array', items: { type: 'string' }, description: '触发关键词（剧情里可能出现的称呼）；常驻条目可留空' },
        alwaysActive: { type: 'boolean', description: 'true=常驻（每轮都注入，用于世界前提/规则）；默认 false=只在关键词命中时注入' },
        entryId: { type: 'string', description: '可选，明确要更新的条目ID（比按名字匹配更精确）' },
        memberName: { type: 'string', description: '可选，群组对话中写入某位成员的私人世界书（不传则写入共享世界书）' },
        sourceMessageIds: { type: 'array', items: { type: 'string' }, description: '该设定所在的消息ID（必须是上下文里真实的已存在消息ID）' },
        evidence: { type: 'string', description: '逐字摘录或紧扣原文的短句，体现该设定' }
      }, required: ['name', 'content', 'sourceMessageIds', 'evidence'], additionalProperties: false }
    },
    {
      type: 'function',
      name: 'web_fetch',
      description: '联网获取内容。三种用法：① 用户给出链接、需要读网页正文时传 url（只传上下文里真实存在的 http(s) 链接，禁止自行编造或拼接 URL）；② 链接是图片（jpg/png/gif/webp 等）时会把图片取回以便你直接查看；③ 用户想找B站/哔哩哔哩视频时传 keyword（关键词从用户意图提取，不要拼搜索网址）。B站视频链接会返回标题、UP主、播放数据、简介与封面图。若网页直连失败或正文为空，会自动改用摘要服务取回标题、描述与主图。',
      parameters: { type: 'object', properties: { url: { type: 'string', description: '要抓取的 http(s) 链接（必须是上下文里真实存在的链接）' }, keyword: { type: 'string', description: 'B站视频搜索关键词（从用户意图提取，如"火影忍者"）' } }, additionalProperties: false }
    },
    {
      type: 'function',
      name: 'update_memory',
      description: '修正一条已存在的长期记忆（按ID）。仅当用户明确否认或纠正某条记忆时使用，例如"那件事是我做的不是你做的""记反了""我不是那个意思"。必须先调用 search_memory 或 list_memories 找到目标ID，再用用户纠正的原话作为 evidence。可改 subject（主体：user=用户 / character=角色自己 / relationship=双方 / world=背景设定）、value（内容，必须写明主体）、key、importance、promisor/promisee（约定的承诺方/受约方）。不要用它新建记忆（新建请用 longTerm 字段或 set_reminder）。',
      parameters: { type: 'object', properties: {
        id: { type: 'string', description: '要修正的记忆ID' },
        subject: { type: 'string', enum: ['user', 'character', 'relationship', 'world'], description: '可选，修正主体' },
        value: { type: 'string', description: '可选，修正后的内容；必须写明主体（用户写"用户"，角色写角色名或"我（角色）"）' },
        key: { type: 'string', description: '可选，修正后的记忆标识' },
        importance: { type: 'integer', minimum: 1, maximum: 10, description: '可选，修正重要性' },
        promisor: { type: 'string', description: '可选，约定的承诺方（user / character / relationship / 群组成员名）' },
        promisee: { type: 'string', description: '可选，约定的受约方（user / character / relationship / 群组成员名）' },
        eventTime: { type: 'string', description: '可选，修正事件发生时间（ISO格式）' },
        dueAt: { type: 'string', description: '可选，修正约定的截止时间（ISO格式）' },
        timeRef: buildTimeRefSchema(),
        sourceMessageIds: { type: 'array', items: { type: 'string' }, description: '用户纠正原话所在的消息ID' },
        evidence: { type: 'string', description: '逐字摘录用户纠正的原话' },
        memberName: { type: 'string', description: '可选，群组中指定某位成员的私人记忆' }
      }, required: ['id', 'sourceMessageIds', 'evidence'], additionalProperties: false }
    }
  ];
  var stickers = (char && Array.isArray(char.stickers)) ? char.stickers : [];
  if (stickers.length > 0) {
    var stickerTags = [];
    stickers.forEach(function(s) { if (stickerTags.indexOf(s.tag) === -1) stickerTags.push(s.tag); });
    tools.push({
      type: 'function',
      name: 'send_sticker',
      description: '给用户发一个表情包（贴图）。tag 必须从这些可用标签中选择其一：' + stickerTags.join('、') + '。当你想用表情包/贴图表达情绪时使用；贴图会作为你的下一条消息单独发出，不需要再用文字描述它。',
      parameters: { type: 'object', properties: { tag: { type: 'string', enum: stickerTags, description: '表情包标签' } }, required: ['tag'], additionalProperties: false }
    });
  }
  tools.push({ type: 'function', name: 'web_search',
    description: '搜索实时网页资料，返回实际取得的标题、链接和摘要。失败或无结果时不得编造搜索结果；可请用户提供链接后用 web_fetch 读取。',
    parameters: { type: 'object', additionalProperties: false,
      properties: { query: { type: 'string', description: '搜索关键词' } }, required: ['query'] } });
  // 收尾工具放进阶段一：模型可以在同一轮里"调完工具 → 调 submit_response 提交"，一次请求收尾；
  // 阶段二仅在阶段一完全没拿到正文时兜底（历史问题：只靠"正文手写 JSON"导致几乎每轮都要多发一轮）。
  tools.push(buildSubmitResponseTool());
  return tools;
}

function htmlToReadableText(html) {
  return toText(html)
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/[ \t\r\f\v]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function imageToolResult(url) {
  return [
    { type: 'input_text', text: '已获取图片。图片URL: ' + url + '（如需展示给用户，可在 reply 中用 ![说明](' + url + ') 直接贴出）' },
    { type: 'input_image', image_url: url, detail: 'auto' }
  ];
}
