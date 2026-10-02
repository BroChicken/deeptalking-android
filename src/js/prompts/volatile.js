function buildVolatileContext(char, query, metadata) {
  metadata = metadata || {};
  var now = new Date();
  var time = getTimeContext(now);
  var memoryQuery = buildMemoryQuery(char, query || '');
  var injected = retrieveRelevantMemories(char, memoryQuery).slice(0, AGENT_TOOL_MEMORY_INJECT_LIMIT);
  metadata.recallIds = injected.filter(function(entry) { return entry.category === 'recalled'; })
    .map(function(entry) { return entry.item.id; });
  var memoryLines = [];
  injected.forEach(function(entry) {
    var item = entry.item;
    appendWithinLimit(memoryLines, '[' + actorLabel(char, item.subject) + '][' + entry.category + '] '
      + maskUserWord(char, trimText(item.key, 80)) + ': ' + maskUserWord(char, trimText(item.value, 400)), CONTEXT_BUDGET.retrievedChars);
  });
  var retrievedUsed = memoryLines.join('\n').length;
  var summaryBudget = CONTEXT_BUDGET.summaryChars + Math.max(0, CONTEXT_BUDGET.retrievedChars - retrievedUsed);
  var shortSource = char.memory.shortTerm.slice(-MEMORY_LIMITS.shortTermTrimFloor);
  var shortLines = [];
  for (var shortIndex = shortSource.length - 1; shortIndex >= 0; shortIndex--) {
    var item = shortSource[shortIndex];
    appendWithinLimit(shortLines, '[' + toText(item.eventTime || item.timestamp).slice(0, 10) + '] '
      + sanitizeShortTermLine(maskUserWord(char, trimText(item.content, 300)), item.eventTime || item.timestamp), summaryBudget);
  }
  shortLines.reverse();

  var recent = char.memory.instant.filter(function(msg) { return !msg.isLoading; }).slice(-14);
  var timeline = recent.map(function(msg) { return msg.id + ' | ' + msg.role + ' | ' + msg.timestamp; }).join('\n');
  var timeBlock = '【时间基准】\n当前本地时间: ' + time.local + '\n时区: ' + time.timeZone + ' (' + time.offset + ')'
    + '\nISO时间: ' + time.iso + '\n逻辑日: ' + formatDayLabel(getLogicalDayDate(now)) + ' ' + getTimeSlot(now) + '\n';
  var timelineBlock = '【消息时间元数据，仅供内部推理】\n' + timeline + '\n';
  var tail = '';
  var endings = getRecentReplyEndings(char, 1);
  if (endings.length) tail += '【结尾多样性】不要重复最近的收尾：' + endings[0] + '\n';
  tail += buildNarrativePatternDirective();
  if (isColdFieldRequest(query)) tail += '【本轮冷场】角色自然开启新话题，不要把选择推回给用户。\n';
  else if (detectTopicSwitch(char, query)) tail += '【本轮话题切换】以用户当前话题为准，不要重提旧话题。\n';
  tail += buildQuickReplyPerspectiveReminder(char);
  tail += buildStyleCorrectionReminder(char);
  tail += buildStyleReview(char);
  var styleAnchor = buildStyleAnchor(char);
  tail = tail.slice(0, 850) + styleAnchor.slice(0, 650);
  var reserved = timeBlock.length + timelineBlock.length + tail.length + 10;
  var budget = Math.max(0, CONTEXT_BUDGET.volatileChars - reserved);
  var blocks = [];
  var used = 0;
  function add(text, limit) {
    var block = toText(text).trim();
    var available = Math.min(limit || budget, budget - used - 2);
    if (!block || available < 80) return '';
    block = block.slice(0, available);
    blocks.push(block);
    used += block.length + 2;
    return block;
  }
  var corrections = recent.filter(function(message) {
    return message.role === 'user' && /填过|记错|记反|不是|不止|纠正|correct|wrong/i.test(toText(message.content));
  }).slice(-2).map(function(message) { return '[' + message.id + '] ' + trimText(message.content, 200); });
  if (corrections.length) add('【用户最新纠正（优先于旧记忆；未明确完成或取消时不得擅自结束约定）】\n' + corrections.join('\n'), 450);
  var stateLines = DYNAMIC_STATE_FIELDS.filter(function(field) {
    return char.entityType !== 'group' || GROUP_SHARED_DYNAMIC_FIELDS.indexOf(field.key) !== -1;
  }).map(function(field) { return field.label + ': ' + trimText(char.dynamicState[field.key], 150); });
  if (char.entityType === 'group') {
    (char.members || []).forEach(function(member) {
      stateLines.push('【' + toText(member.basicInfo.name) + '】' + trimText(buildDynamicStateContext(member), 220));
    });
  }
  add('【角色当前状态，可随对话变化】\n' + stateLines.join('\n'), 1600);
  var lorebookUsed = [];
  var lore = add(buildLorebookContext(char, query, lorebookUsed), 1500);
  metadata.lorebookEntries = lorebookUsed.filter(function(entry) { return lore.indexOf(toText(entry.name) + '：') !== -1; });
  add('【近期摘要记忆】\n' + (shortLines.join('\n') || '无'), summaryBudget + 30);
  add('【本轮主动召回的相关长期记忆】\n' + (memoryLines.join('\n') || '无相关长期记忆')
    + '\n【记忆取用说明】以上条目是按关键词与时间粗略召回的，可能只有部分相关，也可能已经过时；仅在与当前话题自然相关时提及，不要硬提旧事，也不要把它们当成用户刚刚说过的话。'
    + '\n【记忆主体约定】方括号里是"这条记忆讲的是谁"：标为角色名或"背景"的才是角色的事，标为“'
    + (normalizeUserAddress(char.basicInfo && char.basicInfo.userAddress) || '对方') + '”或"你们"的都是对方或双方的事，绝不能当成角色自己做的。',
    CONTEXT_BUDGET.retrievedChars + 240);
  var topics = buildTopicSuggestions(char);
  if (topics.length) add('【可选话题库（仅在需要时自然引出，不要生硬报菜名）】\n'
    + topics.map(function(topic, index) { return (index + 1) + '. ' + topic; }).join('\n'), 500);
  var promises = getPromiseContext(char, memoryQuery);
  if (promises) add('【待跟进承诺】\n' + promises, 500);
  if (char.entityType === 'group') {
    (char.members || []).forEach(function(member) {
      var privateEntries = retrieveRelevantMemories(char, memoryQuery, member.memory).slice(0, 2);
      if (privateEntries.length) add('【' + member.basicInfo.name + '的私人记忆，仅该成员知道】\n'
        + privateEntries.map(function(entry) { return maskUserWord(member, trimText(entry.item.value, 220)); }).join('\n'), 500);
    });
  }
  var sceneLines = (char.memory.scenes || []).slice(-CONTEXT_BUDGET.sceneSummaries)
    .map(function(scene) { return trimText(maskUserWord(char, scene.content), 300); });
  if (sceneLines.length) add('【场景概要（较早情节的压缩记录，只供保持连贯，不是最近发生的事）】\n' + sceneLines.join('\n'), CONTEXT_BUDGET.sceneInjectionChars);
  var gap = getLastReplyGap(char);
  if (gap && gap.reconnect) add('【互动节奏】距上次回复已过 ' + gap.text + '。可温和重连，不施压，不编造期间发生的事。', 180);
  var context = blocks.join('\n\n') + '\n\n' + timeBlock + timelineBlock + '\n' + tail;
  metadata.contextChars = context.length;
  return context;
}
