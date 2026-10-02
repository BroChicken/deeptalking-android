// ==================== UI Rendering ====================
function renderCharacterList() {
  var container = document.getElementById('characterList');
  container.innerHTML = '';
  var chars = Object.values(state.characters);
  if (chars.length === 0) {
    container.innerHTML = '<div class="text-center text-muted-c text-sm py-8">暂无角色<br>点击上方按钮创建</div>';
    return;
  }
  chars.forEach(function(char) {
    var isActive = char.id === state.activeCharacterId;
    var card = document.createElement('div');
    var isGroup = char.entityType === 'group';
    card.className = 'character-card p-3 rounded-xl mb-2 border ' + (isActive ? 'active' : 'border-transparent');
    card.dataset.charId = char.id;
    var safeName = escapeHtml(char.basicInfo.name || '');
    var avatarEl = renderAvatarHtml(char, 'w-10 h-10 rounded-full flex-shrink-0', 'w-10 h-10 rounded-full bg-accent-soft flex items-center justify-center text-xl flex-shrink-0');
    var safePersonality = escapeHtml(isGroup ? ((char.members || []).length + ' 位成员 · ' + (char.groupInfo && char.groupInfo.description || '群组对话')) : (char.basicInfo.personality || '暂无描述'));
    var safeId = escapeHtml(char.id);
    card.innerHTML = '<div class="flex items-center gap-3">' +
      avatarEl +
      '<div class="flex-1 min-w-0">' +
      '<div class="font-medium truncate" style="color: var(--text-color);">' + safeName + (isGroup ? ' <span class="text-xs text-muted-c">群组</span>' : '') + '</div>' +
      '<div class="text-xs text-muted-c truncate">' + safePersonality + '</div>' +
      '</div>' +
      '<button data-action="edit" data-char-id="' + safeId + '" class="character-action text-muted-c hover-accent w-10 h-10 inline-flex items-center justify-center" title="编辑角色卡" aria-label="编辑角色卡"><svg class="icon text-sm"><use href="#icon-edit"></use></svg></button>' +
      (isGroup ? '' : '<button data-action="upgrade" data-char-id="' + safeId + '" class="character-action text-muted-c hover-accent w-10 h-10 inline-flex items-center justify-center" title="升级为群组" aria-label="升级为群组"><svg class="icon text-sm"><use href="#icon-users"></use></svg></button>') +
      '<button data-action="delete" data-char-id="' + safeId + '" class="character-action text-muted-c hover-red w-10 h-10 inline-flex items-center justify-center" title="删除角色" aria-label="删除角色"><svg class="icon text-sm"><use href="#icon-trash"></use></svg></button>' +
      '</div>';
    container.appendChild(card);
    if (isGroup && Array.isArray(char.members) && char.members.length > 0) {
      var members = document.createElement('div');
      members.className = 'ml-5 mb-3 pl-3 border-l border-theme space-y-1';
      char.members.forEach(function(member) {
        var row = document.createElement('div');
        row.className = 'character-card flex items-center gap-2 p-2 border border-transparent text-xs text-muted-c';
        row.innerHTML = renderAvatarHtml(member, 'w-6 h-6 rounded-full flex-shrink-0', 'w-6 h-6 rounded-full bg-accent-soft inline-flex items-center justify-center text-sm flex-shrink-0') + '<span class="flex-1 min-w-0"><span class="block truncate" style="color: var(--text-color);">' + escapeHtml(member.basicInfo.name || '未命名成员') + '</span><span class="block truncate">' + escapeHtml(member.roleInGroup || member.basicInfo.personality || '') + '</span></span><button data-member-action="edit" data-group-id="' + safeId + '" data-member-index="' + char.members.indexOf(member) + '" class="character-action text-muted-c hover-accent w-8 h-8 inline-flex items-center justify-center" title="编辑成员角色卡" aria-label="编辑成员角色卡"><svg class="icon text-sm"><use href="#icon-edit"></use></svg></button>';
        members.appendChild(row);
      });
      container.appendChild(members);
    }
  });
}

function isNearChatBottom(container) {
  return !container || container.scrollHeight - container.scrollTop - container.clientHeight < 56;
}

function appendQuickReplyRow(container) {
  if (!Array.isArray(state.quickReplies) || state.quickReplies.length === 0) return;
  var row = document.createElement('div');
  row.className = 'quick-reply-row flex items-start gap-2';
   row.innerHTML = '<div class="quick-reply-avatar-spacer"></div><div class="quick-reply-content"><div class="quick-reply-label">快速回应 · 以用户身份直接回复</div></div>';
  var content = row.querySelector('.quick-reply-content');
  state.quickReplies.forEach(function(reply, index) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'quick-reply-btn';
    button.disabled = state.isProcessing;
    button.dataset.quickReplyIndex = String(index);
    button.textContent = reply;
    content.appendChild(button);
  });
  container.appendChild(row);
}

// 流式更新时缓存消息行 DOM，避免每次 delta 都全列表查找
var messageRowCache = Object.create(null);

function renderChat(forceFollow) {
  var header = document.getElementById('chatHeader');
  var container = document.getElementById('messageContainer');
  var shouldFollow = forceFollow || isNearChatBottom(container);
  messageRowCache = Object.create(null);
  if (!state.activeCharacterId) {
    header.innerHTML = '<span class="text-muted-c text-sm">选择一个角色开始对话</span>';
    container.innerHTML = '<div class="h-full flex flex-col items-center justify-center text-center"><div class="w-24 h-24 rounded-full bg-accent-soft flex items-center justify-center mb-4"><svg class="icon text-4xl text-muted-c"><use href="#icon-bot"></use></svg></div><h2 class="text-xl font-medium text-secondary-c mb-2">欢迎使用 DeepTalking</h2><p class="text-muted-c max-w-md">一个具有三级记忆系统的深度对话应用。点击左上角菜单按钮，创建或选择角色，开始一段有记忆的对话。</p></div>';
    return;
  }
  var char = state.characters[state.activeCharacterId];
  if (!char) {
    state.activeCharacterId = null;
    renderCharacterList();
    renderChat();
    return;
  }
  var safeName = escapeHtml(char.basicInfo.name || '');
  var safeOccupation = escapeHtml((char.dynamicState && char.dynamicState.currentOccupation) || char.basicInfo.occupation || '未知身份');
  var safePersonality = escapeHtml(char.basicInfo.personality || '点击下方输入框开始聊天');
  header.innerHTML = renderAvatarHtml(char, 'w-8 h-8 rounded-full flex-shrink-0', 'w-8 h-8 rounded-full bg-accent-soft flex items-center justify-center text-lg flex-shrink-0') + '<div><div class="chat-character-name font-medium text-sm" style="color: var(--text-color);">' + safeName + '</div><div class="text-xs text-muted-c truncate">' + safeOccupation + '</div></div>';
  var messages = char.memory.instant;
  if (messages.length === 0) {
    container.innerHTML = '<div class="h-full flex flex-col items-center justify-center text-center"><div class="w-20 h-20 rounded-full bg-accent-soft flex items-center justify-center mb-4">' + renderAvatarHtml(char, 'w-20 h-20 rounded-full', 'text-3xl') + '</div><h3 class="text-lg text-secondary-c mb-1">与 ' + safeName + ' 开始对话</h3><p class="text-muted-c text-sm">' + safePersonality + '</p></div>';
    return;
  }
  container.innerHTML = '';
  var latestAssistantIndex = -1;
  messages.forEach(function(msg, index) {
    if (msg.role === 'assistant' && !msg.isLoading) latestAssistantIndex = index;
  });
  messages.forEach(function(msg, index) {
    if (msg.internalOnly) return;
    var div = document.createElement('div');
    div.className = 'flex items-start gap-2 fade-in ' + (msg.role === 'user' ? 'justify-end' : 'justify-start');
    div.dataset.msgId = msg.id || '';
    div.dataset.role = msg.role;
    if (msg.isLoading) {
      div.innerHTML = '<div class="message-avatar">' + renderAvatarHtml(char, 'message-avatar-img', '') + '</div><div class="message-bubble message-ai"><div class="message-content"><div class="typing-indicator"><span></span><span></span><span></span></div></div></div>';
      container.appendChild(div);
      messageRowCache[msg.id || ''] = div;
      return;
    }
    
    var formattedContent = formatMessageContent(msg.content);
    var imagesHtml = (Array.isArray(msg.images) && msg.images.length) ? '<div class="message-images">' + msg.images.map(function(uri) { return '<img src="' + escapeHtml(uri) + '" class="message-image" loading="lazy" alt="图片" onclick="openImagePreview(this.src)">'; }).join('') + '</div>' : '';

    var parsedTs = Date.parse(msg.timestamp);
    var time = isNaN(parsedTs) ? '' : new Date(parsedTs).toLocaleTimeString('zh-CN', {hour: '2-digit', minute:'2-digit'});
    var bubbleClass = msg.role === 'user' ? 'message-user' : 'message-ai';
    var timeClass = msg.role === 'user' ? 'opacity-70' : 'text-muted-c';
    var avatarHtml = msg.role === 'user' ? '' : '<div class="message-avatar">' + renderAvatarHtml(char, 'message-avatar-img', '') + '</div>';
    var safeMsgId = escapeHtml(msg.id || '');
    var actionHtml = (state.isProcessing || !formattedContent) ? '' : (
      '<button class="message-action" data-message-action="copy" data-msg-id="' + safeMsgId + '" title="复制消息" aria-label="复制消息"><svg class="icon text-sm"><use href="#icon-copy"></use></svg></button>' +
      (msg.role === 'user'
        ? '<button class="message-action" data-message-action="edit-resend" data-msg-id="' + safeMsgId + '" title="编辑并重发" aria-label="编辑并重发"><svg class="icon text-sm"><use href="#icon-edit"></use></svg></button>'
        : '<button class="message-action" data-message-action="regenerate" data-msg-id="' + safeMsgId + '" title="重新生成" aria-label="重新生成"><svg class="icon text-sm"><use href="#icon-refresh"></use></svg></button>'));
    var staticChangesHtml = (msg.role === 'assistant' && Array.isArray(msg.staticChanges) && msg.staticChanges.length > 0) ? '<div class="text-xs mt-1" style="color: var(--warning-color, #b45309);">⚡ ' + escapeHtml(msg.staticChanges.join(' · ')) + '已修改</div>' : '';
    var lorebookChangesHtml = (msg.role === 'assistant' && Array.isArray(msg.lorebookChanges) && msg.lorebookChanges.length > 0) ? '<div class="text-xs mt-1" style="color: var(--warning-color, #b45309);">📖 ' + escapeHtml(msg.lorebookChanges.join(' · ')) + '</div>' : '';
    var contentHtml = formattedContent ? '<div class="message-content">' + formattedContent + '</div>' : '';
    div.innerHTML = avatarHtml + '<div class="message-bubble ' + bubbleClass + '">' + imagesHtml + contentHtml + staticChangesHtml + lorebookChangesHtml + '<div class="message-meta"><span class="text-xs ' + timeClass + '">' + time + '</span>' + actionHtml + '</div></div>';
    container.appendChild(div);
    messageRowCache[msg.id || ''] = div;
    if (msg.role === 'assistant' && index === latestAssistantIndex && state.quickReplyCharacterId === char.id && state.quickReplyMessageId === msg.id) appendQuickReplyRow(container);
  });
  if (shouldFollow) container.scrollTop = container.scrollHeight;
}

