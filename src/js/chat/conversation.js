// ==================== Chat & API Logic (Streaming/Non-Streaming) ====================
function discardConversationBranch(char, startIndex) {
  var removed = char.memory.instant.slice(startIndex);
  var removedIds = new Set(removed.map(function(message) { return message.id; }).filter(Boolean));
  char.memory.revision = (Number(char.memory.revision) || 0) + 1;
  char.memory.instant = char.memory.instant.slice(0, startIndex);
  function hasRemovedSource(item) {
    return (item.sourceMessageIds || []).some(function(id) { return removedIds.has(id); });
  }
  char.memory.shortTerm = char.memory.shortTerm.filter(function(item) { return !hasRemovedSource(item); });
  Object.keys(char.memory.longTerm).forEach(function(category) {
    char.memory.longTerm[category] = char.memory.longTerm[category].filter(function(item) { return !hasRemovedSource(item); });
  });
  char.memory.pendingRecall = char.memory.pendingRecall.filter(function(item) { return !hasRemovedSource(item); });
  function cleanDynamicStateSources(target) {
    if (!target || !isPlainObject(target.dynamicStateMeta)) return;
    var knownSources = getKnownSources(char);
    Object.keys(target.dynamicStateMeta).forEach(function(key) {
      var meta = target.dynamicStateMeta[key];
      if (!isPlainObject(meta) || !(meta.sourceMessageIds || []).some(function(id) { return removedIds.has(id); })) return;
      var remainingIds = (meta.sourceMessageIds || []).filter(function(id) {
        return knownSources[id] && evidenceMatchesSource(knownSources[id].text, meta.evidence);
      });
      if (remainingIds.length > 0) {
        meta.sourceMessageIds = remainingIds;
      } else {
        if (isPlainObject(target.dynamicState)) target.dynamicState[key] = '';
        delete target.dynamicStateMeta[key];
      }
    });
  }
    cleanDynamicStateSources(char);
    (char.members || []).forEach(cleanDynamicStateSources);
  }

function editAndResendMessage(messageId) {
  if (state.isProcessing || !state.activeCharacterId) return;
  var char = state.characters[state.activeCharacterId];
  var message = char && char.memory.instant.find(function(item) { return item.id === messageId && item.role === 'user'; });
  if (!message) return;
  var text = prompt('编辑并重新发送消息：', message.content);
  if (text == null || !text.trim()) return;
  return sendMessage({ replaceMessageId: messageId, text: text.trim() });
}

function regenerateReply(messageId) {
  if (state.isProcessing || !state.activeCharacterId) return;
  return sendMessage({ regenerateMessageId: messageId });
}

// ==================== 角色主动开口（对应面板停留空闲 1 分钟即开口，不分新老，无冷却） ====================
const PROACTIVE_IDLE_MS = 60000;
var proactiveTimer = null;

function clearProactiveCheck() {
  if (proactiveTimer) { clearTimeout(proactiveTimer); proactiveTimer = null; }
}

function scheduleProactiveCheck() {
  clearProactiveCheck();
  if (!state.config || state.config.proactiveEnabled === false) return;
  if (!state.activeCharacterId || !state.characters[state.activeCharacterId]) return;
  proactiveTimer = setTimeout(function() { tryProactiveOpen(); }, PROACTIVE_IDLE_MS);
}

function canTriggerProactive(char) {
  if (!char) return false;
  if (!state.config || state.config.proactiveEnabled === false) return false;
  if (!state.config.apiKey) return false;
  if (state.isProcessing) return false;
  if (state.activeCharacterId !== char.id) return false;
  if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return false;
  return true;
}

function tryProactiveOpen() {
  proactiveTimer = null;
  if (!state.activeCharacterId) return;
  var char = state.characters[state.activeCharacterId];
  if (!canTriggerProactive(char)) {
    scheduleProactiveCheck();
    return;
  }
  sendMessage({ proactive: true });
}

async function sendMessage(options) {
  if (state.isProcessing || !state.activeCharacterId) return;
  options = options || {};
  var isProactive = options.proactive === true;
  clearProactiveCheck();
  var input = document.getElementById('messageInput');
  var char = state.characters[state.activeCharacterId];
  if (!char) return;
  if (!state.config.apiKey) {
    alert('未配置API Key，请在设置中填写');
    return;
  }

  var text = typeof options.text === 'string' ? options.text.trim() : input.value.trim();
  var pendingImages = (!options.regenerateMessageId && !options.replaceMessageId && !isProactive && Array.isArray(state.pendingImages))
    ? state.pendingImages.map(function(item) { return item && item.dataUrl; }).filter(Boolean)
    : [];
  var userMessage = null;
  if (options.regenerateMessageId) {
    var replyIndex = char.memory.instant.findIndex(function(item) { return item.id === options.regenerateMessageId && item.role === 'assistant' && !item.isLoading; });
    if (replyIndex === -1) return;
    var userIndex = replyIndex - 1;
    while (userIndex >= 0 && char.memory.instant[userIndex].role !== 'user') userIndex--;
    if (userIndex === -1) return;
    if (!confirm('将重新生成此回复，并丢弃它之后的对话分支和相关记忆，继续吗？')) return;
    discardConversationBranch(char, replyIndex);
    userMessage = char.memory.instant[userIndex];
    text = userMessage.content;
  } else if (options.replaceMessageId) {
    var replaceIndex = char.memory.instant.findIndex(function(item) { return item.id === options.replaceMessageId && item.role === 'user'; });
    if (replaceIndex === -1 || !text) return;
    if (!confirm('将以编辑后的消息重新生成回复，并丢弃该消息之后的对话分支和相关记忆，继续吗？')) return;
    discardConversationBranch(char, replaceIndex);
    var timestamp = new Date().toISOString();
    userMessage = { id: createMemoryId('msg'), role: 'user', content: text, timestamp: timestamp };
    char.memory.instant.push(userMessage);
  } else {
    if (!text && !isProactive && pendingImages.length === 0) return;
    var newTimestamp = new Date().toISOString();
    if (isProactive) {
      userMessage = { id: createMemoryId('msg'), role: 'user', content: '', internalOnly: true, timestamp: newTimestamp };
    } else {
      userMessage = { id: createMemoryId('msg'), role: 'user', content: text, timestamp: newTimestamp };
      if (pendingImages.length > 0) userMessage.images = pendingImages;
    }
    char.memory.instant.push(userMessage);
    clearPendingImages();
  }
  char.memory.revision = (Number(char.memory.revision) || 0) + 1;
  invalidateQuickReplies();

  if (!isProactive) {
    input.value = '';
    autoResize(input);
  }
  var loadingMsg = { id: createMemoryId('msg'), role: 'assistant', content: '', timestamp: new Date().toISOString(), isLoading: true };
  char.memory.instant.push(loadingMsg);
  trimCharacterMemory(char);
  renderChat(true);
  scheduleSave();

  state.isProcessing = true;
  state.activityLog = [{ at: new Date().toISOString(), text: isProactive ? '角色主动开口' : '发送消息' }];
  state._receiveLogged = false;
  try {
    var requestContext = {};
    var messages = buildRequestPayload(char, text, requestContext);
    var fullText = '';
    var fullResponse = null;
    var salvagedReply = null;
    var seenEventTypes = [];
    var attemptsLeft = 2;
    var didRetry = false;
    var requestStartTime = Date.now();
    var toolState = { items: [], rounds: 0, char: char, config: Object.assign({}, state.config) };
    var phase = 'auto';
    var submitAttempted = false;
    var structuredFromSubmit = null;
    while (true) {
      var attempt = await performChatRequestWithRetry(messages, text, loadingMsg, toolState, phase);
      fullText = attempt.fullText;
      fullResponse = attempt.fullResponse;
      salvagedReply = attempt.salvagedReply;
      seenEventTypes = attempt.seenEventTypes;
      var submitResult = extractSubmitResponse(fullResponse);
        var infoCalls = (attempt.functionCalls || []).filter(function(call) { return call.name !== 'submit_response'; });
        // 特判：模型只发起 ask_user 时，直接把提问作为本轮回复输出，不进入工具循环
        if (phase === 'auto' && infoCalls.length === 1 && infoCalls[0].name === 'ask_user') {
          var askRaw = infoCalls[0].arguments;
          var askArgs = null;
          try { askArgs = parseJsonPayload(askRaw); } catch (e) { askArgs = null; }
          if (isPlainObject(askArgs) && askArgs.question) {
            fullText = JSON.stringify({ reply: toText(askArgs.question), quickReplies: ['嗯，我明白', '稍等，我想一下'] });
            structuredFromSubmit = { ok: true, reply: toText(askArgs.question), obj: { reply: toText(askArgs.question) }, rawArgs: fullText };
            break;
          }
        }
        if (phase === 'auto' && infoCalls.length) {
          if (toolState.rounds >= MAX_TOOL_ROUNDS || (Number(toolState.executedCalls) || 0) >= MAX_TOOL_CALLS) {
            var toolLimitDecision = chatStageDecision('auto-tool-limit', { hasText: !!(fullText && fullText.trim()) });
            if (toolLimitDecision.action === 'accept-text') break;
            noteStageEvent('forcedSubmitAtToolLimit');
            setActivity(toolLimitDecision.activity);
            phase = 'submit';
            submitAttempted = false;
            updateMessageBubble(loadingMsg.id, getDisplayText(toolLimitDecision.bubble));
            continue;
          }
          toolState.rounds++;
          toolState.executedCalls = (Number(toolState.executedCalls) || 0) + 1;
          // DeepSeek thinking 模式不支持并行 function_call 回传（会 400）：每轮只执行并回传第一个
          // 信息类工具，其余调用丢弃、由模型在下一轮基于结果重发；reasoning 必须先于 function_call。
          var primaryCall = infoCalls[0];
          var usedCallIds = {};
          toolState.items.forEach(function(item) { if (item && item.type === 'function_call') usedCallIds[item.call_id] = true; });
          var primaryCallId = primaryCall.call_id || primaryCall.id || createMemoryId('call');
          if (usedCallIds[primaryCallId]) primaryCallId = createMemoryId('call');
          (attempt.reasoningItems || []).forEach(function(item) { toolState.items.push(item); });
          toolState.items.push({ type: 'function_call', call_id: primaryCallId, name: primaryCall.name, arguments: toText(primaryCall.arguments) });
          var primaryOutput = await executeToolCall(primaryCall, char, toolState);
          toolState.items.push({ type: 'function_call_output', call_id: primaryCallId, output: primaryOutput });
          var toolFailed = false;
          if (typeof primaryOutput === 'string') { try { if (JSON.parse(primaryOutput).ok === false) toolFailed = true; } catch (error) { toolFailed = true; } }
          var toolDecision = chatStageDecision('auto-tool', { toolName: primaryCall.name });
          setActivity(toolDecision.activity);
          updateMessageBubble(loadingMsg.id, getDisplayText(toolDecision.bubble));
          if (!submitResult || toolFailed) continue;
        }
        if (submitResult && (submitResult.ok || submitResult.reason === 'missing_quick_replies')) {
          fullText = submitResult.rawArgs;
          structuredFromSubmit = { ok: true, obj: submitResult.obj, reply: submitResult.reply, rawArgs: submitResult.rawArgs };
          break;
        }
        if (phase === 'auto') {
          // 阶段一结束：拿到正文（合法 JSON 或散文）就直接采用——散文即终稿，不再为了补
          // quickReplies/记忆而强制重发一轮（历史问题：几乎每轮都出现"正在整理回复…"）。
          var autoStructured = parseStructuredResponse(fullText);
          var autoFinalDecision = chatStageDecision('auto-final', { structuredOk: !!(autoStructured && autoStructured.ok), hasQuickReplies: !!(autoStructured && autoStructured.hasQuickReplies), hasText: !!(fullText && fullText.trim()) });
          if (autoFinalDecision.action === 'accept-text') break;
          // 兜底：阶段一既没有合法 JSON 也没有任何正文，才切阶段二强制 submit_response 收尾
          noteStageEvent('forcedSubmitAtAutoFinal');
          // 切阶段二前把阶段一的 reasoning 与正文回传，保持 thinking 上下文连续
          (attempt.reasoningItems || []).forEach(function(item) { toolState.items.push(item); });
          if (fullText && fullText.trim()) {
            toolState.items.push({ role: 'assistant', content: trimText(fullText, 1200) });
          }
          phase = 'submit';
          submitAttempted = false;
          setActivity(autoFinalDecision.activity);
          updateMessageBubble(loadingMsg.id, getDisplayText(autoFinalDecision.bubble));
          continue;
        }
        // 阶段二（强制 submit_response）：走到这里说明没有拿到合法 submit_response
        if (!submitAttempted) {
          var submitRetryDecision = chatStageDecision('submit-retry', {});
          submitAttempted = true;
          attemptsLeft--;
          noteStageEvent('submitRetry');
          setActivity(submitRetryDecision.activity);
          updateMessageBubble(loadingMsg.id, getDisplayText(submitRetryDecision.bubble));
          continue;
        }
      if (fullResponse && !fullText.trim()) fullText = extractResponsesText(fullResponse);
      if (fullResponse && !fullText.trim()) {
        var hiddenJson = extractReplyJsonFromAnyOutput(fullResponse);
        if (hiddenJson && hiddenJson.trim()) fullText = hiddenJson;
      }
      if (fullResponse && !fullText.trim()) fullText = extractAnyResponseText(fullResponse);
      var usableReply = false;
      usableReply = !!(parseStructuredResponse(fullText).ok) || salvagedReply != null || !!(fullText && fullText.trim());
      if (usableReply) break;
      if (attemptsLeft > 0) {
        var retryDecision = chatStageDecision('empty', { attemptsLeft: attemptsLeft });
        attemptsLeft--;
        didRetry = true;
        setActivity(retryDecision.activity);
        updateMessageBubble(loadingMsg.id, getDisplayText(retryDecision.bubble));
        continue;
      }
      break;
    }

    var structured = null;
    structured = structuredFromSubmit || parseStructuredResponse(fullText);
    var hasUsableReply = false;
    hasUsableReply = !!(structured && structured.ok) || salvagedReply != null || !!(fullText && fullText.trim());
    if (!hasUsableReply) {
      if (fullResponse && fullResponse.error) {
        throw new Error(fullResponse.error.message || fullResponse.error.type || 'API 返回错误');
      }
      {
        console.warn('[DeepTalking] 收到无法解析的回复。事件类型:', seenEventTypes, 'fullResponse:', fullResponse);
        var outputSummary = '无';
        if (fullResponse && Array.isArray(fullResponse.output)) {
          outputSummary = fullResponse.output.map(function(item) {
            var itemType = item ? item.type : '?';
            var parts = item && Array.isArray(item.content) ? item.content : [];
            var partDesc = parts.length ? parts.map(function(p) {
              if (!p) return '?';
              var len = typeof p.text === 'string' ? p.text.length : 0;
              var preview = (typeof p.text === 'string' && p.text.trim()) ? JSON.stringify(p.text.slice(0, 30)) : '';
              return p.type + ':len' + len + (preview ? '=' + preview : '=空');
            }).join('/') : '无content';
            return itemType + '[' + partDesc + ']';
          }).join(', ');
        }
        var diag = '版本v' + APP_VERSION
          + '；配置 stream=' + state.config.stream
          + ', reasoning=' + state.config.reasoningEffort + ', model=' + state.config.modelName
          + '；已收到事件: ' + (seenEventTypes.join('、') || '无')
          + '；output条目(' + (fullResponse && Array.isArray(fullResponse.output) ? fullResponse.output.length : '无') + '): ' + outputSummary
          + '；status=' + (fullResponse && fullResponse.status ? fullResponse.status : '无')
          + '；incomplete_details=' + (fullResponse && fullResponse.incomplete_details ? JSON.stringify(fullResponse.incomplete_details) : '无');
        var rawDump = '';
        try {
          rawDump = fullResponse ? JSON.stringify(fullResponse.output || fullResponse).slice(0, 900) : '';
        } catch (e) { rawDump = '序列化失败'; }
        try {
          localStorage.setItem(DEBUG_STORAGE_KEY, JSON.stringify({ at: new Date().toISOString(), config: { stream: state.config.stream, reasoning: state.config.reasoningEffort, model: state.config.modelName }, events: seenEventTypes, output: fullResponse && Array.isArray(fullResponse.output) ? fullResponse.output.slice(0, 5) : (fullResponse && fullResponse.output ? fullResponse.output : null), status: fullResponse && fullResponse.status ? fullResponse.status : null }));
          renderDebugInfo();
        } catch (e) { /* 忽略 */ }
        throw new Error((fullText && fullText.trim() ? '模型未返回可解析的JSON正文（可能返回了思考过程）。' : '收到空回复。') + diag + '。原始响应: ' + rawDump);
      }
      throw new Error('收到空回复');
    }

    // 散文收尾（模型没走 JSON、也没调用 submit_response）时的后台记忆提取标记：
    // 正文直接采用、不等它，记忆/状态由后台"整理助手"补写（不再阻塞、不再重写回复）
    var needsBackgroundMemory = false;
    var displayText;
    var memUpdate;
    if (structured && structured.ok) {
      displayText = unescapeLiteralNewlines(structured.reply);
      loadingMsg.content = displayText; // 先写入正文，供 current_response 证据校验匹配
      memUpdate = applyMemoryUpdate(structured.obj, char, loadingMsg);
      if (toolState && Array.isArray(toolState.staticChanges) && toolState.staticChanges.length > 0) {
        loadingMsg.staticChanges = Array.from(new Set((loadingMsg.staticChanges || []).concat(toolState.staticChanges)));
      }
      if (toolState && Array.isArray(toolState.lorebookChanges) && toolState.lorebookChanges.length > 0) {
        loadingMsg.lorebookChanges = Array.from(new Set((loadingMsg.lorebookChanges || []).concat(toolState.lorebookChanges)));
      }
    } else if (salvagedReply != null) {
      displayText = unescapeLiteralNewlines(salvagedReply);
      loadingMsg.content = displayText;
      memUpdate = parseMemoryFromText(fullText, char, loadingMsg);
    } else {
      displayText = getDisplayText(fullText);
      loadingMsg.content = displayText;
      memUpdate = parseMemoryFromText(fullText, char, loadingMsg);
      if (!memUpdate) needsBackgroundMemory = true;
    }
    displayText = dedupeRepeatedEnding(displayText, char);
    // 语气/风格遵从：先做客户端违规检测；命中且开了修订开关时跑一次 critique（只修文风、不改剧情）
    var styleViolations = detectStyleViolations(displayText, char, getRecentReplyTexts(char, STYLE_GUARD.lookbackReplies));
    if (styleViolations.length > 0 && state.config.styleCritique !== false) {
      setActivity('正在校正文风…');
      var critiqued = await critiqueReplyStyle(char, displayText, styleViolations);
      if (critiqued && critiqued !== displayText) {
        displayText = dedupeRepeatedEnding(critiqued, char);
        styleViolations = detectStyleViolations(displayText, char, getRecentReplyTexts(char, STYLE_GUARD.lookbackReplies));
      }
    }
    loadingMsg.styleViolations = styleViolations;
    loadingMsg.content = displayText;
    loadingMsg.isLoading = false;
    loadingMsg.timestamp = new Date().toISOString();
    consumeInjectedRecalls(char, requestContext.recallIds);
    markLorebookMentions(requestContext.lorebookEntries, new Date().toISOString());
    var stickerMsg = null;
    if (toolState && toolState.stickerToSend && toolState.stickerToSend.dataUrl) {
      // 表情包作为紧跟其后的独立消息（不并入正文气泡，相当于两条消息）
      stickerMsg = {
        id: createMemoryId('msg'),
        role: 'assistant',
        content: '',
        images: [toolState.stickerToSend.dataUrl],
        timestamp: new Date().toISOString()
      };
      var replyIndexNow = char.memory.instant.indexOf(loadingMsg);
      if (replyIndexNow >= 0) char.memory.instant.splice(replyIndexNow + 1, 0, stickerMsg);
      else char.memory.instant.push(stickerMsg);
    }
    var hasStructuredMemory = memUpdate && memUpdate._accepted && (memUpdate._accepted.shortTerm + memUpdate._accepted.longTerm > 0);
    if (!hasStructuredMemory && shouldCaptureUserTurn(text)) {
      addShortTermMemory(char, '本轮事件：用户表示“' + text + '”。尚未形成更完整的事件经过。', userMessage.timestamp, { sourceMessageIds: [userMessage.id], sourceRoles: ['user'] });
    }
    // 快速回应：只做客户端视角校验，不再为了它多发一轮请求。
    // 校验通过就用；不通过（缺失/占位符/复述角色台词/角色口吻）清空并交给后台换位生成。
    var quickReplies = memUpdate ? parseQuickReplyList(memUpdate.quickReplies) : [];
    if (quickReplies.length < 2) {
      var textQuickReplies = parseQuickRepliesFromText(fullText);
      if (textQuickReplies.length > quickReplies.length) quickReplies = textQuickReplies;
    }
    var quickReplyMessageId = stickerMsg ? stickerMsg.id : loadingMsg.id;
    var quickReplyIssues = detectQuickReplyIssues(quickReplies, char, displayText);
    if (quickReplyIssues.length === 0) {
      state.quickReplies = quickReplies;
      state.quickReplyCharacterId = char.id;
      state.quickReplyMessageId = quickReplyMessageId;
    } else {
      // 先留空（不留 '嗯/继续' 这种占位），后台换位生成回来后立即渲染
      state.quickReplies = [];
      state.quickReplyCharacterId = char.id;
      state.quickReplyMessageId = quickReplyMessageId;
      if (state.config.quickReplyRepair !== false) {
        loadingMsg.quickReplyIssues = quickReplyIssues;
        var repairChar = char;
        var repairGuard = captureMemoryTask(char);
        queueBackgroundTask(function() {
          return repairQuickRepliesAsUser(repairChar, displayText, userMessage, quickReplyMessageId, quickReplies, repairGuard);
        });
      } else if (quickReplies.length > 0) {
        // 关掉校正时不做二次生成：模型给什么就用什么
        state.quickReplies = quickReplies;
      }
    }
    try {
      localStorage.setItem(DEBUG_REPLY_STORAGE_KEY, JSON.stringify({
        at: new Date().toISOString(),
        config: { stream: state.config.stream, reasoning: state.config.reasoningEffort, model: state.config.modelName },
        durationMs: Date.now() - requestStartTime,
        displayText: trimText(displayText, 2000),
        fullText: trimText(fullText, 2000),
        quickReplies: quickReplies,
        quickReplyIssues: quickReplyIssues,
        backgroundMemory: needsBackgroundMemory,
        stageStats: state.config.stageStats || null,
        cacheStats: state.config.cacheStats || null,
        activityLog: state.activityLog.slice(-20)
      }));
      renderDebugInfo();
    } catch (e) { /* 忽略 */ }
    if (needsBackgroundMemory) {
      // 散文轮的记忆/状态后台补：把"整理成结构化记忆"从可见链路挪到后台，正文已经显示完毕
      var memoryChar = char;
      var memoryUserMessage = userMessage;
      var memoryReplyText = displayText;
      var memoryTaskGuard = captureMemoryTask(char);
      queueBackgroundTask(function() {
        return extractProseTurnMemory(memoryChar, memoryUserMessage, memoryReplyText, loadingMsg, memoryTaskGuard);
      });
    }
    scheduleSave();
    renderChat();
    await checkMemoryTriggers(char);
    scheduleSave();
    renderChat();
    autoFillStaticFields();
  } catch (error) {
    console.error('API Error:', error);
    var loadingIndex = char.memory.instant.indexOf(loadingMsg);
    if (loadingIndex !== -1) char.memory.instant.splice(loadingIndex, 1);
    trimCharacterMemory(char);
    saveData();
    renderChat();
    alert('回复失败：' + error.message + '。你的消息已保留，可稍后重试。');
  } finally {
    state.isProcessing = false;
    clearActivity();
    renderChat();
    scheduleProactiveCheck();
  }
}

async function performChatRequest(messages, text, loadingMsg, toolState, phase) {
  var config = toolState.config || state.config;
  var body = buildResponsesRequestBody(messages, config.stream, toolState, phase, config);
  var trace = startRequestTrace(body, { char: toolState.char, taskType: 'chat', phase: phase, platform: config.apiPlatform });
  var priorCache = state.config.cacheStats;
  var hadCacheMiss = priorCache && priorCache.hitTokens === 0 && priorCache.promptTokens > 0;
  setActivity(hadCacheMiss ? '缓存未命中，正在更新队列…' : '正在请求 API…');
  var hasAbort = typeof AbortController !== 'undefined';
  var abortController = hasAbort ? new AbortController() : null;
  var hardTimer = setTimeout(function() { if (abortController) abortController.abort(); }, 180000);
  var idleTimer = null;
  function resetIdleTimer() {
    if (!abortController) return;
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(function() { abortController.abort(); }, 60000);
  }
  function clearRequestTimers() {
    if (hardTimer) { clearTimeout(hardTimer); hardTimer = null; }
    if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
  }
  var response;
  var reader = null;
  try {
    response = await fetch(getResponsesEndpoint(config), {
      method: 'POST',
headers: buildApiHeaders(config, getSessionIdFor(toolState.char)),
      body: JSON.stringify(body),
      signal: abortController ? abortController.signal : undefined
    });
  } catch (fetchError) {
    clearRequestTimers();
    if (fetchError && typeof fetchError === 'object') {
      if (!fetchError.status) fetchError.network = true;
      if (fetchError.name === 'AbortError') fetchError.aborted = true;
    }
    trace.status = 'failed';
    recordCacheUsage(null, trace);
    throw fetchError;
  }
  if (!response.ok) {
    var errData = await response.json().catch(function() { return {}; });
    clearRequestTimers();
    var httpError = new Error(errData.error ? errData.error.message : 'HTTP ' + response.status);
    httpError.status = response.status;
    trace.status = 'failed';
    recordCacheUsage(null, trace);
    throw httpError;
  }

  var contentType = (response.headers.get('Content-Type') || '').toLowerCase();
  var isStreaming = contentType.indexOf('text/event-stream') !== -1;
  var fullText = '';
  var webSearchActive = false;
  var streamFinished = false;
  var salvagedReply = null;
  var fullResponse = null;
  var seenEventTypes = [];
  var functionCalls = [];
  var usageRecorded = false;
  try {
  var pendingFunctionCall = null;
  var reasoningItems = [];
  var pendingReasoning = null;
  // 流式逐 token 更新做 rAF 合并，避免每帧都全量重解析 Markdown
  var pendingBubbleText = null;
  var bubbleRafId = null;
  var bubbleScheduler = window.requestAnimationFrame ? function(cb) { return window.requestAnimationFrame(cb); } : function(cb) { return setTimeout(cb, 16); };
  function scheduleBubbleUpdate(text) {
    pendingBubbleText = text;
    if (bubbleRafId != null) return;
    bubbleRafId = bubbleScheduler(function() {
      bubbleRafId = null;
      if (pendingBubbleText != null) {
        var textToRender = pendingBubbleText;
        pendingBubbleText = null;
        updateMessageBubble(loadingMsg.id, textToRender);
      }
    });
  }
  function flushBubbleUpdate() {
    if (bubbleRafId != null) {
      if (window.cancelAnimationFrame) window.cancelAnimationFrame(bubbleRafId); else clearTimeout(bubbleRafId);
      bubbleRafId = null;
    }
    if (pendingBubbleText != null) {
      var textToRender = pendingBubbleText;
      pendingBubbleText = null;
      updateMessageBubble(loadingMsg.id, textToRender);
    }
  }
  var consumeStreamData = function(data) {
    if (!data || data === '[DONE]') return;
    var event;
    try {
      event = JSON.parse(data);
    } catch (parseError) {
      console.warn('[DeepTalking] 忽略无法解析的SSE帧:', String(data).slice(0, 160));
      return;
    }
    {
      if (seenEventTypes.indexOf(event.type) === -1) seenEventTypes.push(event.type);
      if (event.type === 'response.web_search_call.in_progress' || event.type === 'response.web_search_call.searching') {
        webSearchActive = true;
        setActivity('正在联网搜索…');
        scheduleBubbleUpdate(getDisplayText('正在联网搜索…'));
        return;
      }
      if (event.type === 'response.web_search_call.failed' || (event.type === 'response.output_item.done' && event.item && event.item.type === 'web_search_call' && event.item.status === 'failed')) {
        webSearchActive = false;
        setActivity('搜索未能完成，角色将自行应对…');
        return;
      }
      if (event.type === 'response.output_item.added' && event.item && event.item.type === 'reasoning') {
        pendingReasoning = { type: 'reasoning', id: toText(event.item.id), status: toText(event.item.status, 'completed'), content: [], summary: [] };
        return;
      }
      if (event.type === 'response.reasoning_text.delta' && pendingReasoning) {
        var reasoningDelta = toText(event.delta || '');
        if (reasoningDelta) {
          if (pendingReasoning.content.length) pendingReasoning.content[0].text += reasoningDelta;
          else pendingReasoning.content.push({ type: 'reasoning_text', text: reasoningDelta });
        }
        return;
      }
      if (event.type === 'response.output_item.done' && event.item && event.item.type === 'reasoning') {
        var doneReasoning = event.item;
        var existingReasoning = null;
        for (var ri = 0; ri < reasoningItems.length; ri++) {
          if (reasoningItems[ri].id === toText(doneReasoning.id)) { existingReasoning = reasoningItems[ri]; break; }
        }
        if (existingReasoning) {
          if (Array.isArray(doneReasoning.content) && doneReasoning.content.length) {
            existingReasoning.content = doneReasoning.content.map(function(p) { return { type: 'reasoning_text', text: toText(p.text) }; }).filter(function(p) { return p.text; });
          }
          if (Array.isArray(doneReasoning.summary) && doneReasoning.summary.length) {
            existingReasoning.summary = doneReasoning.summary.map(function(p) { return { type: 'reasoning_summary', text: toText(p.text) }; }).filter(function(p) { return p.text; });
          }
          if (doneReasoning.status) existingReasoning.status = toText(doneReasoning.status);
        } else {
          var rContent = Array.isArray(doneReasoning.content) && doneReasoning.content.length
            ? doneReasoning.content.map(function(p) { return { type: 'reasoning_text', text: toText(p.text) }; }).filter(function(p) { return p.text; })
            : (pendingReasoning ? pendingReasoning.content.slice() : []);
          var rSummary = Array.isArray(doneReasoning.summary) && doneReasoning.summary.length
            ? doneReasoning.summary.map(function(p) { return { type: 'reasoning_summary', text: toText(p.text) }; }).filter(function(p) { return p.text; })
            : [];
          if (rContent.length || rSummary.length) {
            reasoningItems.push({ type: 'reasoning', id: toText(doneReasoning.id), status: toText(doneReasoning.status, 'completed'), content: rContent, summary: rSummary });
          }
        }
        if (pendingReasoning && pendingReasoning.id === toText(doneReasoning.id)) pendingReasoning = null;
        return;
      }
      if (event.type === 'response.output_item.added' && event.item && event.item.type === 'function_call') {
        pendingFunctionCall = { call_id: toText(event.item.call_id || event.item.id), name: toText(event.item.name), arguments: '' };
        setActivity('正在调用工具：' + (pendingFunctionCall.name || '未知工具') + '…');
        scheduleBubbleUpdate(getDisplayText('正在查询…'));
        return;
      }
      if (event.type === 'response.function_call_arguments.delta' && pendingFunctionCall) {
        pendingFunctionCall.arguments += toText(event.delta || '');
        if (pendingFunctionCall.name === 'submit_response' && pendingFunctionCall.arguments.trim().charAt(0) === '{') {
          var submitReplySoFar = extractReplyFromJson(pendingFunctionCall.arguments);
          if (submitReplySoFar != null) {
            salvagedReply = submitReplySoFar;
            scheduleBubbleUpdate(getDisplayText(submitReplySoFar));
          }
        }
        return;
      }
      if (event.type === 'response.function_call_arguments.done' && pendingFunctionCall) {
        if (event.arguments) pendingFunctionCall.arguments = toText(event.arguments);
        return;
      }
      if (event.type === 'response.output_item.done' && event.item && event.item.type === 'function_call') {
        var doneName = toText(event.item.name);
        var doneArgs = toText(event.item.arguments);
        var existing = null;
        for (var ci = 0; ci < functionCalls.length; ci++) {
          if (functionCalls[ci].call_id === toText(event.item.call_id || event.item.id)) { existing = functionCalls[ci]; break; }
        }
        if (existing) {
          if (doneName) existing.name = doneName;
          if (doneArgs) existing.arguments = doneArgs;
        } else {
          functionCalls.push({ call_id: toText(event.item.call_id || event.item.id), name: doneName, arguments: doneArgs });
        }
        if (pendingFunctionCall && (!existing || existing.call_id === pendingFunctionCall.call_id)) pendingFunctionCall = null;
        return;
      }
      if (event.type === 'response.output_text.delta' && event.delta) {
        if (webSearchActive) webSearchActive = false;
        if (!state._receiveLogged) { state._receiveLogged = true; setActivity('正在接收回复…'); }
        fullText += event.delta;
        var replySoFar = extractReplyFromJson(fullText);
        if (replySoFar != null) {
          salvagedReply = replySoFar;
          scheduleBubbleUpdate(replySoFar);
        } else if (fullText.trim() && fullText.trim().charAt(0) !== '{') {
          scheduleBubbleUpdate(getDisplayText(fullText));
        }
        return;
      }
      if (event.type === 'response.output_text.done' && event.text && !fullText.trim()) {
        fullText = event.text;
        if (webSearchActive) webSearchActive = false;
        return;
      }
      if (event.type === 'response.completed' || event.type === 'response.incomplete') {
        if (event.response) {
          fullResponse = event.response;
          recordCacheUsage(event.response.usage, trace);
          usageRecorded = true;
        }
        streamFinished = true;
        return;
      }
      if (event.type === 'response.failed' || event.type === 'error') {
        throw new Error(event.error && event.error.message ? event.error.message : '流式响应错误');
      }
      return;
    }
      recordCacheUsage(event.usage, trace);
    var delta = extractStreamText(event);
    if (!delta) return;
    if (!state._receiveLogged) { state._receiveLogged = true; setActivity('正在接收回复…'); }
    fullText += delta;
    scheduleBubbleUpdate(getDisplayText(fullText));
  };
  if (isStreaming) {
    if (!response.body || !response.body.getReader) { clearRequestTimers(); throw new Error('当前浏览器不支持流式响应'); }
    reader = response.body.getReader();
    var decoder = new TextDecoder();
    var buffer = '';
    var frameEventName = '';
    var frameData = [];
    function dispatchSseFrame() {
      if (frameData.length === 0) { frameEventName = ''; return null; }
      var raw = frameData.join('\n').trim();
      var typeName = frameEventName;
      frameEventName = '';
      frameData = [];
      var payload;
      try {
        payload = JSON.parse(raw);
      } catch (parseError) {
        console.warn('[DeepTalking] 忽略无法解析的SSE帧:', String(raw).slice(0, 160));
        return null;
      }
      if (!payload.type && typeName) payload.type = typeName;
      consumeStreamData(JSON.stringify(payload));
      return payload;
    }
    resetIdleTimer();
    while (true) {
      var chunk = await reader.read();
      resetIdleTimer();
      if (chunk.done) { buffer += decoder.decode(); break; }
      buffer += decoder.decode(chunk.value, { stream: true });
      var lines = buffer.split('\n');
      buffer = lines.pop();
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.slice(-1) === '\r') line = line.slice(0, -1);
        if (!line.trim()) {
          dispatchSseFrame();
          if (streamFinished) break;
          continue;
        }
        if (line.indexOf('event:') === 0) { frameEventName = line.slice(6).trim(); continue; }
        if (line.indexOf('data:') === 0) { frameData.push(line.slice(5)); }
      }
      if (streamFinished) break;
    }
    if (!streamFinished) {
      buffer += decoder.decode();
      buffer.split('\n').forEach(function(line) {
        if (line.slice(-1) === '\r') line = line.slice(0, -1);
        if (!line.trim()) { dispatchSseFrame(); return; }
        if (line.indexOf('event:') === 0) { frameEventName = line.slice(6).trim(); return; }
        if (line.indexOf('data:') === 0) { frameData.push(line.slice(5)); }
      });
      dispatchSseFrame();
    }
  } else {
    var responseJson = await response.json();
    recordCacheUsage(responseJson.usage, trace);
    usageRecorded = true;
    fullText = extractResponsesText(responseJson);
    {
      fullResponse = responseJson;
      functionCalls = extractFunctionCalls(fullResponse);
      if (Array.isArray(fullResponse.output)) {
        fullResponse.output.forEach(function(item) {
          if (item && item.type === 'reasoning') {
            var rItem = { type: 'reasoning', id: toText(item.id), status: toText(item.status, 'completed'), content: [], summary: [] };
            if (Array.isArray(item.content)) {
              rItem.content = item.content.map(function(p) { return { type: 'reasoning_text', text: toText(p.text) }; }).filter(function(p) { return p.text; });
            }
            if (Array.isArray(item.summary)) {
              rItem.summary = item.summary.map(function(p) { return { type: 'reasoning_summary', text: toText(p.text) }; }).filter(function(p) { return p.text; });
            }
            if (rItem.content.length || rItem.summary.length) reasoningItems.push(rItem);
          }
        });
      }
    }
  }
  if (pendingFunctionCall && pendingFunctionCall.name) {
    var known = false;
    for (var fci = 0; fci < functionCalls.length; fci++) {
      if (functionCalls[fci].call_id === pendingFunctionCall.call_id) { known = true; break; }
    }
    if (!known) functionCalls.push(pendingFunctionCall);
    pendingFunctionCall = null;
  }
  flushBubbleUpdate();
  clearRequestTimers();
  return { fullText: fullText, fullResponse: fullResponse, salvagedReply: salvagedReply, seenEventTypes: seenEventTypes, functionCalls: functionCalls, reasoningItems: reasoningItems };
  } catch (error) {
    trace.status = 'failed';
    if (!usageRecorded) recordCacheUsage(null, trace);
    throw error;
  } finally {
    clearRequestTimers();
    if (reader) {
      try { await reader.cancel(); } catch (error) { /* Already closed or aborted. */ }
      try { reader.releaseLock(); } catch (error) { /* No outstanding lock. */ }
    }
  }
}
