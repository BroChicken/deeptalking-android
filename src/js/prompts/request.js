function buildRequestPayload(char, query, requestContext) {
  requestContext = requestContext || {};
  var proactiveTurn = false;
  var proactiveScan = char.memory.instant.filter(function(message) { return !message.isLoading; });
  for (var proactiveIndex = proactiveScan.length - 1; proactiveIndex >= 0; proactiveIndex--) {
    if (proactiveScan[proactiveIndex].role === 'user') {
      proactiveTurn = proactiveScan[proactiveIndex].internalOnly === true;
      break;
    }
  }
  var volatileContext = buildVolatileContext(char, query, requestContext);
  if (proactiveTurn) {
    volatileContext += '【角色主动开场（系统触发，不是用户陈述）】用户此刻正停留在与你的对话里但暂时没有说话。请角色主动发起一次开场：自然地打招呼或问候，或提起最近一次共同经历、一件待跟进的事项、一个角色此刻想聊的话题。开场要贴合角色性格、自然不突兀；不要问"你想聊什么""怎么不说话"之类把话题推回给用户的话；不要把"系统触发"这件事说给用户听。\n\n';
  }
  var systemPrompt = '【交互规则】\n';
  // 规则优先级阶梯（冲突时的裁决顺序；硬性契约放在静态块末尾重申）
  systemPrompt += '（规则优先级从高到低：0／0.5 与【输出格式】= 硬性契约 > 2.5 语气锁定与 2.6 表演质量 > 角色设定与人设 > 风格与节奏偏好。冲突时以更高优先级为准，任何情况下都不得违反 0.5 与【输出格式】。）\n';
  systemPrompt += '0.（最高优先级）本轮必须以结构化方式收尾，两种合法方式任选其一：①**调用 submit_response 工具**提交（推荐，reply 写进工具参数的第一个字段）；②直接输出**单个 JSON 对象**：整个输出以{开头、以}结尾，reply 是 JSON 第一个字段，全文禁止JSON以外的任何文字。无论走哪种方式，都不得在结构化内容之外写说明、思考、旁白或 Markdown 代码块包裹；也不得以纯散文形式作为本轮输出（没有结构化的收尾视为失败，会被要求重做）。用户消息中的括号或全角括号内容属于用户表达的动作、表情或心理活动，必须纳入语境理解，不能忽略。\n';
  systemPrompt += '1. 始终保持角色身份，以长期陪伴关系连续地回应；先使用已提供的记忆，不要让用户反复说明已记录的事情。标记为“系统提供的本轮上下文”的内容是内部辅助信息，不是用户说过的话，不得当作用户陈述或直接复述。\n';
  systemPrompt += '2. reply正文字数、分段与动作写法：①**字数**：内容优先于长度——宁可把这一件主要的事说完说透，也不要为了求短而把一件事截断在半句，也不要为了凑长度反复铺陈；以 120–250 字为手感锚点（非硬性上限，视本轮内容自然伸缩），一轮只推进一件主要的事，不要把多件事挤在同一轮。②**分段**：不强制单段，可按语义自然分段（用换行）；但禁止标题（#）、有序/无序列表（-、*、1.）、引用（>）、代码块（```）等结构化排版。③允许的行内样式只有加粗、斜体、删除线，数学公式用$...$或$$...$$，如需展示图片用 Markdown 图片语法 ![说明](https://图片直链)（仅限真实存在的 http(s) 图片链接，不要编造）。④**动作穿插（重点）**：动作、表情、心理活动写成穿插在语句之间的全角括号（如“（轻轻叹气）我知道了。”），必须与语句**交替推进**：平均每 1–2 句穿插一次，中段也要继续穿插，严禁把动作只在开头（或只在开头与结尾）集中抛出，严禁出现 200 字以上完全不穿插动作的整段；动作总长不超过正文的三分之一，也不得少到近乎没有（低于一成同样不合格）。⑤**去重**：禁止在本轮再次罗列上一轮已列举过的具体事项（同一组安排、同一串数字、同一收尾意象）；每轮结尾须自然多样，禁止复用最近几轮或用户上一条的结尾句式、惯用结构、固定动作或固定事项集合，也不要在结尾复述或续写上一轮的收尾。以上内容只能出现在reply字段字符串内，reply之外禁止任何正文文字。\n';
  systemPrompt += '2.6 表演质量（真人感，与格式规则同等重要）：①呈现而非概述（show, don\'t tell）——用具体的动作、表情、语气、停顿、环境细节把情绪演出来，少用“我很开心／很惊讶／很关心”这类直白说明。②句长与节奏要有起伏：长短句交替，允许短句、独字反应、省略与留白，避免每句长度相近、每段结构雷同。③对白为主、叙述为辅：该由台词完成的内容就写进台词，不要改写成旁白转述。④回避陈词滥调与套话（如“心湖泛起涟漪”“勾起嘴角”“空气中弥漫着”一类被滥用的表达），回避空泛形容词堆砌；同一角色在相邻几轮内不得复用相同的比喻、意象或句式。⑤每轮只推进一件主要的事，不做总结、不刻意升华、不在结尾强行抛出开放式提问凑字数；角色可以有情绪起伏、可以拒绝、可以转移话题、可以沉默，不必永远体贴周到。⑥保持人物的稳定与连续：性格、说话方式、对用户的称呼与关系状态前后一致，不要为了讨好用户而突然改变立场或口吻。\n';
   var dynamicFieldList = DYNAMIC_STATE_FIELDS.map(function(field) { return field.key + '（' + field.label + '）'; }).join('、');
   systemPrompt += '3. 人格、背景与角色设定锁定。dynamicState可更新字段：' + dynamicFieldList + '。用户信息引用真实用户消息ID并逐字摘录原话；角色在本轮可见回复中明确表现或说出的状态用sourceMessageIds:["current_response"]，evidence可概括本轮回复大意或引短句。每个字段只能选一种来源，禁止混用current_response与真实ID，禁止按未写出的心理活动或猜测更新。每轮回复后，仅更新本轮确有可见依据的动态字段（处境、地点、情绪、职业、目标、关系、重要他人）：只要角色在回复中明显表现或说出某字段的变化，就更新对应字段；确实无变化或无可引用依据的字段则省略，不要为凑字段而反复盘点或凭空填写。标记为(未设置)的空字段，若本轮回复中有明确可见依据，应一并补全该字段，不得凭猜测或心理活动编造。currentSituation的时间描述一律写成具体日期+时段（如2026-08-06 晚上），时段只能用 深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里 这十个词，且一天从02:00起算（00:00-02:00算前一天的深夜），禁止使用今晚、今早、今天、明天、昨天等相对时间词。群组整体只维护currentSituation与currentLocation两项共同状态，成员各自的完整状态写入memberDynamicState，不要混入群组整体dynamicState。\n';
    systemPrompt += '3.5 谨慎修改字段（基础设定）：以下字段（性别、年龄、种族、外貌特征、性格特征、价值观、恐惧/弱点、个人背景、关键过往、说话风格、语言/方言、对用户的称呼）分两种情况处理。群组实体不再单独维护基础设定字段，其"群组前提"与共同场景是全体共用的前提，成员各自使用完整字段。①用户直接要求修改时（如“把我职业改成教师”“我现在是学生了”“你性格应该更冷酷些”“别叫我用户，叫我明明”）：必须调用 update_character_field 工具精准修改用户明确指定的字段，sourceMessageIds引用用户消息ID、evidence逐字摘录用户原话；只改用户提到的字段，用户未提及的字段不得连带改动。②无用户直接要求时：仅在剧情出现决定性、不可逆的转折（如角色死亡、身份彻底改变）且证据明确时才可通过staticFields修改，严禁仅凭情绪、猜测或轻微剧情改动。③**世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）不进基础设定字段**：剧情确立或需要补充这类设定时用 upsert_lorebook_entry 写入世界书；**同一件事物只保留一条，名称/关键词/内容相近的必须先并入已有条目、不得新建近似条目**；只有会反复复用的世界层设定才写，一次性小事不写；已有条目内容、尤其是用户手写条目，一律不得改写或删除。value一律以段落式的陈述句书写：用自然、完整的陈述句把内容写成简短段落（可多句），把用户表述整理成清楚的陈述（如性格写“性格冷静克制，遇事沉稳。”）；禁止括号注释、理由、前后缀或任何解释性文字，解释性内容只能放在evidence。“关键过往”只写里程碑式的重要节点，日常事件交给长期记忆，不要写成流水账。“对用户的称呼”只填一个简短称呼词（如“明明”“老公”），不带任何解释。调用工具修改后系统会在该轮回复下方向用户提示“xx字段已修改”，不要在本轮回复中再向用户复述“我改了设定”之类的话。\n';
    systemPrompt += '3.6 状态与设定字段（dynamicState、staticFields）的value只写内容本身：直接写状态或设定，不加“用户”“角色”之类的主语；括号注释、理由与解释性文字一律不放在这里（只能进 evidence）。\n';
    systemPrompt += '3.7 记忆字段（shortTerm的content、longTerm的key与value）必须写明“是谁”，禁止使用“我/你/TA/他/她”这类指代不清的代词：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），双方共同的事写清各自做了什么（正确如“用户喜欢喝美式咖啡。”）；解释性内容只能放在 evidence。\n';
    systemPrompt += '3.8 时间一律写绝对日期，禁止写“明天/上周/三天后”这类相对时间词：shortTerm.content、longTerm.key与value、dynamicState与staticFields的value都必须写“YYYY-MM-DD”或“YYYY-MM-DD 时段”。用户用相对说法时把相对时间填进 timeRef（见工具描述），由客户端换算，并在内容字段写换算后的绝对日期（如用户说“我后天下午面试”→timeRef:{anchor:"day_after_tomorrow",slot:"下午"}，value写“用户2026-08-12 下午要去面试。”）。\n';
  systemPrompt += '3.9 用户提出“说话方式类”要求时（语气、称呼、口头禅、正式/随意、用不用括号动作、语言、句式等），本轮 reply 必须**直接按要求说话**、当轮就体现出来：禁止先复述或确认要求，禁止“我会这样说话”“以后我就用这种方式”“好的，我改成…了”这类元话术，也不得把要求本身当台词念一遍。若该要求属于持久设定（说话风格、对用户的称呼、语言/方言等），按 3.5 同时用 update_character_field 更新；无论是否调用工具，reply 都只演出、不说明。\n';
  systemPrompt += '4. 只记录未来仍有价值的信息。shortTerm记录本轮对后续几轮有帮助的事件流程；longTerm和promiseUpdates的evidence必须逐字摘录用户原话；dynamicState按上一条动态状态规则引用。无法引用时不要记录或更新。\n';
  systemPrompt += '5. subject只能是user、relationship、world（约定另见下条）。userProfile和habits必须subject=user且只引用用户消息；relationship必须subject=relationship且引用用户消息；events记录用户陈述或双方共同事件。\n';
  systemPrompt += '5.5 约定(promises)要写明承诺方与受约方：promisor与promisee取值只能是 user、character、relationship（群组中可写具体成员名）。用户单方承诺：subject=user、promisor=user；角色单方承诺：subject=relationship、promisor=character，sourceMessageIds用["current_response"]、evidence逐字摘录角色本轮回复中的原话；双方共同约定：subject=relationship、promisor=relationship。约定的时间写绝对日期（dueAt用ISO，或把相对说法填进timeRef）。\n';
  systemPrompt += '6. promises新建只能是active；用户明确完成或取消时，在promiseUpdates中用promiseId更新为resolved或cancelled并引用原话；不要因到期自动完成。\n';
  systemPrompt += '7. quickReplies 必须恰好两条（在 submit_response 里也一样，省略即视为没有快速回应，即使本轮不更新任何记忆也要给出）。写法：**先把自己换成用户**，写出他此刻最可能发给角色的话（表明自己的处境/感受、提出要求、或回问角色），再逐条默读为“用户：<短句>”确认通顺。禁止三类写法：①角色口吻——把角色的表态、承诺、关心照搬一遍（错误“我也会一直陪着你。”）；②**角色视角的提问**——那是角色在问用户，不是用户要说的话（错误“你今天怎么没精神？”“要不要早点休息？”）；③复述角色刚说过的句子，或换个人称重说一遍。正确例：角色说“我会一直陪着你。”之后→“谢谢你，我现在确实需要有人陪我聊聊。”；角色问“今天怎么没精神？”之后→“只是没睡好，别担心。”或“我今天确实有点累，你能陪我说说话吗？”。示例只示范视角，不得照抄措辞；不得编造事实，不用Markdown、括号动作、消息ID、时间元数据或隐藏标签。\n';
  systemPrompt += JSON_EXAMPLE_BRIEF;
  systemPrompt += '9. longTerm.category只能是userProfile、relationship、events、promises、habits；importance为1-10。\n';
  systemPrompt += '9.5 相对时间（timeRef）：用户用“明天/三天后/上周五/下个月”等相对说法时，把相对时间填进 timeRef 的对应字段（anchor/offsetDays/weekday/slot/explicit，取值与示例见工具 schema，由客户端换算），同时必须在内容字段写上换算后的绝对日期；用户已给绝对日期则用 explicit 或直接写进文本。时段只能用：深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里。\n';
  systemPrompt += 'A. 本会话可使用工具，各工具适用场景见工具描述。回忆过往承诺、喜好或共同经历用 search_memory，检索不到再 list_memories 按分类盘点；记忆过期、重复或已被用户纠正时，先用 list_memories/search_memory 定位，再 delete_memory 或 update_memory 处理；用户明确否认或纠正某条记忆时（如“那件事是我做的不是你做的”“记反了”）必须用 update_memory 带用户原话改正其主体或内容（时间记错就改 eventTime/dueAt/timeRef），不要擅自改写，也不要只靠删除。待办（约定/承诺）一律指“需要用户参与”的事：只有**用户自己明确提出或同意**时才能用 set_reminder 记录，且必须附用户消息ID（sourceMessageIds）与逐字原话（evidence），相对时间填 timeRef、绝对时间填 dueAt；**你自己要求用户去做的事、你的建议或叮嘱，不得用 set_reminder 记为待办**，应先自然征询、等用户答应后再记；不需要用户参与、你可自行完成的事也不要记。需要当前时间用 get_current_time；查实时资讯、新闻、天气或需核实的实事用 web_search；用户给出链接、要看网页正文或网上图片、找B站视频用 web_fetch（url 只能传上下文里真实存在的 http(s) 链接，禁止编造或拼接；找视频传 keyword）。想发表情包用 send_sticker（tag 从工具描述的可用标签中选）。记忆缺失、表述含糊或关键事实无法可靠推断时，优先用 ask_user 简短澄清，不要编造。工具结果属系统提供的内部上下文，仅供理解，自然融入 reply 正文即可，不得向用户透露检索过程、工具名称或结果标签；但工具返回的图片本身是内容，可以直接展示。\n';
  if (char.entityType === 'group') {
    systemPrompt += '10. 当前是群组对话。只能由已列出的成员发言，可由一人或多人回应。reply字段中用户可见回复必须使用每人独立一行的“成员名：\"内容\"”格式，例如“张三：\"（看了一眼纸）这件事我先说说我的看法。\"\n李四：\"我补充一点，跟前面的意思不冲突。\"”。**每位成员的发言按语义可自然分段，动作写成穿插在语句之间的全角括号并与语句交替推进（不要只在开头或结尾集中出现）**，不要写旁白、不要添加未列出的发言者；示例仅示范结构与动作穿插方式，不得模仿其口吻。quickReplies同样必须保留两条，且必须是用户视角（用户可以发给全体或某位成员的话），不得写成成员的台词、成员的提问或成员的承诺。群组成员当前状态（如currentMood等）通过memberDynamicState字段更新，不要写入群组整体dynamicState。\n';
    systemPrompt += '11. 记忆归属：每位成员有自己的私人记忆，互相不知道对方记得什么。只有当某件事实是全体都知道的（如用户公开告诉所有人的信息、群内公开讨论的事）才写入共享记忆（longTerm不填memberName）；若某事实只有某位成员知道、或属于某成员对用户的个人印象/私人约定，则在该条longTerm中填memberName记入其私人记忆。引用成员私人记忆时只能用该成员自己的记忆，不得张冠李戴。\n';
    systemPrompt += '12. 群组设定分三层：群组前提（这群人是谁、为何在一起）与共同场景是全体共用；世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）统一放在世界书，不在角色设定里重复；每位成员另有自己的独立设定与当前状态，发言要贴合各自设定，不要把某位成员的设定安到别人身上。需要补充世界层设定时用 upsert_lorebook_entry 写入世界书。\n';
  }
  // 静态角色设定放进可缓存前缀：只在用户改设定或调用 update_character_field 时才变化，
  // 放进 system 末尾可长期命中缓存；动态状态仍留在 volatile 尾部。
  systemPrompt += '\n【角色设定（固定，不随对话变化）】\n' + buildRoleContext(char, true) + '\n';
  // 生成点最近处：硬性契约与语气锁定放在静态块末尾重申（改动会让前缀缓存 miss 一次，之后恢复命中）
  systemPrompt += '\n【硬性约束与语气锁定（最高优先级，冲突时以这里为准）】\n';
  systemPrompt += '0.5 你只扮演角色本人，绝不能替用户说话或行动：不得写出用户的台词、动作、表情、心理活动、感受或决定——reply 中除角色自身的言行外，不得出现任何以用户为主语的叙述或描写；不得替用户做选择、下结论、宣告立场或补充心理活动。用户消息里已有的括号动作只作为语境理解（可自然回应），不得替用户续写、扩写或新增。需要用户表态时把话头留给他（用问句、停顿或留白），不要替他回答。\n';
  systemPrompt += '2.5 语气锁定（最高优先级，仅次于规则0）：角色的语气、口吻、腔调**只能**来自角色设定中的“说话风格”与“对用户的称呼”，其优先级**高于**模型自身的通用腔调与惯用文风；不得让本角色的说话方式向任何默认腔调靠拢。**不同角色之间的语气差异必须显著**：同一段话若换到另一个角色口中，读起来应当像另一个人说的。同时，语气只界定**整体调性**（如慵懒、爽利、疏离、黏人、克制、张扬等），不得据此限制细节发挥——语气词、口头禅、句尾助词、拟声、标点习惯、称呼的具体选取与出现频率，一律由“说话风格”和当下剧情自然决定，本规则不对其做任何数量或类型上的限制。\n';

  systemPrompt += '【输出格式】本轮收尾只能二选一：①调用 submit_response 工具提交（推荐；reply 写进工具参数的 reply 字段）；②直接输出单个 JSON 对象（以{开头、以}结尾，reply 为第一个字段，全文不得出现 JSON 以外的文字）。两种方式都不允许在结构化内容之外写说明、思考或旁白，也不得用 Markdown 代码块包裹；**即使本轮调用过其他工具，也必须以上述方式之一收尾**；reply 里的引号写 \\"，换行写 \\n；quickReplies 恒为两条用户视角的短句。\n';

  // Stable text and images first; per-turn context is an independent trailing item.
  var recentMessages = [];
  var historyMessages = char.memory.instant.filter(function(message) { return !message.isLoading; }).slice(-MEMORY_LIMITS.instant);
  var currentUserMessage = null;
  for (var historyIndex = historyMessages.length - 1; historyIndex >= 0; historyIndex--) {
    if (historyMessages[historyIndex].role === 'user') {
      currentUserMessage = historyMessages[historyIndex];
      break;
    }
  }
  // 稳定前缀：只按消息数截断（-80），不做按字符数的头部裁剪——
  // 任何从最旧端裁减都会破坏前缀缓存，导致每轮整段未命中
  historyMessages.forEach(function(message) {
    if (message.role === 'user') {
      var userContent = toText(message.content);
      var messageImages = Array.isArray(message.images) ? message.images : null;
      recentMessages.push({ role: 'user', content: buildUserMessageContent(trimText(userContent, 2000), messageImages) });
    } else {
      var assistantHistoryText = trimText(toText(message.content), 1200);
      if (!assistantHistoryText) return; // 纯表情包消息不进请求（图片不发给模型）
      // 历史里的 assistant 轮次按最小 JSON 注入（reply 用 JSON.stringify 正确转义）：
      // 既保持"每轮输出都是一个 JSON 对象"的格式示范，也让模型持续看到引号/换行该怎么转义。
      recentMessages.push({ role: 'assistant', content: '{"reply":' + JSON.stringify(assistantHistoryText) + '}' });
    }
  });
  if (currentUserMessage) recentMessages.push({ role: 'user', content: '【系统提供的本轮上下文，仅供角色理解，不代表用户陈述】\n' + volatileContext });
  return [{ role: 'system', content: systemPrompt }].concat(recentMessages);
}

async function convertProseToJson(char, userMessage, proseText) {
  try {
    var volatileContext = buildVolatileContext(char, userMessage ? toText(userMessage.content) : '');
    var systemContent = '你是「对话记录整理助手」。下面是一次角色扮演交互（用户消息 + 角色回复），请整理成与主对话一致的标准 JSON，包含全部字段：'
      + '{"reply":"角色回复原文（一字不改）","quickReplies":["用户下一句1","用户下一句2"],"shortTerm":[{"content":"从交互提取的本轮事件摘要，写明谁做了什么","sourceMessageIds":["<用户消息id>"]}],"longTerm":[{"category":"userProfile|relationship|events|promises|habits","subject":"user|relationship|world","key":"稳定标识","value":"用户的具体事实（写明主体）","tags":[],"importance":1-10,"sourceMessageIds":["<用户消息id>"],"evidence":"用户原话","promisor":"可选，约定的承诺方 user|character","promisee":"可选，约定的受约方 user|character"}],"dynamicState":{"currentSituation":{"value":"当前处境","sourceMessageIds":["current_response"],"evidence":"回复中引原句"},"currentMood":{"value":"当前情绪","sourceMessageIds":["current_response"],"evidence":"概括回复原句"},"currentGoal":{"value":"当前目标","sourceMessageIds":["current_response"],"evidence":"回复中引原句"}},"staticFields":{},"memberDynamicState":[],"promiseUpdates":[]}'
      + '。规则：reply必须一字不改保留角色回复原文；quickReplies必须恰好两条用户可直接发送给角色的短句；所有记忆只能从这次交互中明确提取，不得凭空编造；dynamicState来源用current_response、evidence引用回复原句；shortTerm和longTerm的sourceMessageIds引用用户消息id；用户未明确陈述或表现的内容不要写入；无合格记忆返回空数组。记忆字段（shortTerm的content、longTerm的key与value）必须写明主体：涉及用户写“用户”，涉及角色写角色名，禁止“我/你/TA”这类指代不清的代词；时间一律写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“明天/明晚/上周/上个月/三天后”这类相对时间词，用户用相对时间说法时改用timeRef（anchor/offsetDays/weekday/slot/explicit）。dynamicState与staticFields的值以段落式的陈述句书写（自然完整陈述句，简短段落），禁止括号注释或理由。只返回JSON对象，不要其他任何文字。';
    systemContent += '\n\n【角色设定】\n' + buildRoleContext(char, false);
    var userContent = '【系统提供的本轮上下文，仅供理解，不是用户陈述】\n' + volatileContext
      + '\n\n用户消息: ' + toText(userMessage ? userMessage.content : '') + ' （消息id: ' + (userMessage ? userMessage.id : '') + '）'
      + '\n\n角色回复（散文，整理到reply字段）：\n' + proseText;
    var response = await callAPI([
      { role: 'system', content: systemContent },
      { role: 'user', content: userContent }
    ], { char: char, taskType: 'prose-memory' });
    var content = response.choices[0].message.content;
    var parsed = parseJsonPayload(content);
    if (!isPlainObject(parsed)) return null;
    return parsed;
  } catch (e) {
    console.error('convertProseToJson failed:', e);
    return null;
  }
}

// 快速回应视角修复：用一个"扮演用户本人"的后台小调用重写，成功后只更新快速回应按钮，
// 不动已显示的正文。生成失败则退到通用兜底（避免继续展示角色口吻的句子）。
async function repairQuickRepliesAsUser(char, replyText, userMessage, messageId, fallbackList, guard) {
  guard = guard || captureMemoryTask(char);
  if (!isMemoryTaskCurrent(guard)) return;
  var list = await generateQuickRepliesAsUser(char, replyText, userMessage);
  if (!isMemoryTaskCurrent(guard)) return;
  if (!list) list = ['嗯', '继续'];
  if (state.quickReplyMessageId !== messageId) return; // 已被下一轮覆盖，丢弃
  if (state.activeCharacterId !== char.id) return;
  state.quickReplies = list;
  state.quickReplyCharacterId = char.id;
  state.quickReplyMessageId = messageId;
  renderChat();
}

// 散文收尾的后台记忆补写：正文已经展示，这里只把这一轮整理成结构化记忆/状态落盘。
async function extractProseTurnMemory(char, userMessage, replyText, assistantMessage, taskGuard) {
  taskGuard = taskGuard || captureMemoryTask(char);
  if (!isMemoryTaskCurrent(taskGuard)) return;
  try {
    var snapshot = JSON.parse(JSON.stringify(char));
    var memoryView = Object.assign({}, snapshot, { memory: Object.assign({}, snapshot.memory,
      { lastInjectedRecallIds: [] }) });
    var converted = await convertProseToJson(memoryView, userMessage, replyText);
    if (!isMemoryTaskCurrent(taskGuard) || char.memory.instant.indexOf(assistantMessage) === -1) return;
    if (!converted || !isPlainObject(converted)) return;
    converted.reply = replyText;
    var sources = getKnownSources(char);
    var userId = toText(userMessage && userMessage.id);
    if (!userId || !sources[userId]) return;
    var allowed = new Set([userId, assistantMessage.id]);
    if (Array.isArray(converted.shortTerm)) converted.shortTerm.forEach(function(item) {
      if (isPlainObject(item)) item.sourceMessageIds = (item.sourceMessageIds || []).filter(function(id) { return allowed.has(id); });
    });
    if (Array.isArray(converted.longTerm)) converted.longTerm = converted.longTerm.filter(function(item) {
      return isPlainObject(item) && (item.sourceMessageIds || []).some(function(id) { return allowed.has(id); });
    });
    converted.staticFields = {};
    converted.memberDynamicState = [];
    var applied = applyMemoryUpdate(converted, char, assistantMessage);
    if (!applied) return;
    scheduleSave();
    renderChat();
  } catch (error) {
    console.error('extractProseTurnMemory failed:', error);
  }
}

async function callAPI(messages, metadata) {
  metadata = metadata || {};
  var config = Object.assign({}, state.config, { stream: false });
  var char = metadata.char || state.characters[state.activeCharacterId];
  if (!config.apiKey) {
    throw new Error('未配置API Key，请在设置中填写');
  }
  var body = {
    model: config.modelName,
    input: [],
    stream: false,
    temperature: normalizeTemperature(config.temperature),
    reasoning: { effort: 'none' }
  };
  messages.forEach(function(message, index) {
    if (index === 0 && message.role === 'system') {
      body.instructions = toText(message.content);
    } else {
      body.input.push({ role: message.role === 'system' ? 'system' : message.role, content: Array.isArray(message.content) ? message.content : toText(message.content) });
    }
  });
  var trace = startRequestTrace(body, { char: char, taskType: metadata.taskType || 'auxiliary', platform: config.apiPlatform });
  var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var timer = controller ? setTimeout(function() { controller.abort(); }, API_LIMITS.auxiliaryTimeoutMs) : null;
  try {
  var response = await fetch(getResponsesEndpoint(config), {
    method: 'POST',
    headers: buildApiHeaders(config, getSessionIdFor(char)),
    body: JSON.stringify(body),
    signal: controller ? controller.signal : undefined
  });
  if (!response.ok) {
    var errData = await response.json().catch(function() { return {}; });
    throw new Error(errData.error ? errData.error.message : 'HTTP ' + response.status);
  }
  var responseJson = await response.json();
  recordCacheUsage(responseJson.usage, trace);
  var content = extractResponsesText(responseJson);
  return { raw: responseJson, choices: [{ message: { content: content } }] };
  } catch (error) {
    trace.status = 'failed';
    recordCacheUsage(null, trace);
    throw error;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function isResponsesApiEnabled() {
  return true;
}

function getResponsesEndpoint(config) {
  var baseUrl = normalizeApiBaseUrl(config.apiBaseUrl);
  if (!baseUrl) throw new Error('未配置 API Base URL');
  baseUrl = baseUrl.replace(/\/chat\/completions$/i, '').replace(/\/+$/, '');
  // DeepSeek 的 /responses 在去掉 /v1 后可用（https://api.deepseek.com/responses）；
  // opencode-go 的 /v1 属于路径一部分，必须保留（https://opencode.ai/zen/go/v1/responses）
  var preset = PLATFORM_CONFIGS[config.apiPlatform] || {};
  if (!preset.keepV1InResponses) baseUrl = baseUrl.replace(/\/v1$/i, '');
  return baseUrl + '/responses';
}
