// ==================== Viewport Height ====================
function updateAppHeight() {
  var vh = window.visualViewport ? window.visualViewport.height : window.innerHeight;
  document.documentElement.style.setProperty('--app-height', vh + 'px');
}

// ==================== Init ====================
window.addEventListener('DOMContentLoaded', function() {
  loadData();
  var versionBadge = document.getElementById('appVersionBadge');
  if (versionBadge) versionBadge.textContent = 'v' + APP_VERSION;
  renderDebugInfo();
  applyTheme();
  renderCharacterList();
  renderChat();
  loadSettingsForm();
  updateAppHeight();
  runInitialMemoryMigration();
  scheduleProactiveCheck();
  setTimeout(autoFillStaticFields, 3000);
  setTimeout(maybeMigrateFieldStructure, 1200);
  setTimeout(maybeMigrateLorebookWorld, 2400);
  document.addEventListener('visibilitychange', function() {
    if (document.visibilityState === 'visible') {
      scheduleProactiveCheck();
    } else {
      clearProactiveCheck();
    }
  });
  var proactiveInputEl = document.getElementById('messageInput');
  if (proactiveInputEl) proactiveInputEl.addEventListener('input', clearProactiveCheck);

  // Event delegation for character list
  document.getElementById('characterList').addEventListener('click', function(e) {
    var memberAction = e.target.closest('[data-member-action]');
    if (memberAction) {
      e.stopPropagation();
      if (memberAction.dataset.memberAction === 'edit') openGroupMemberModal(memberAction.dataset.groupId, parseInt(memberAction.dataset.memberIndex, 10));
      return;
    }
    var actionBtn = e.target.closest('[data-action]');
    if (actionBtn) {
      e.stopPropagation();
      var action = actionBtn.dataset.action;
      var charId = actionBtn.dataset.charId;
      if (action === 'edit') openCharacterModal(charId);
      else if (action === 'upgrade') upgradeToGroup(charId);
      else if (action === 'delete') deleteCharacter(charId);
      return;
    }
    var card = e.target.closest('[data-char-id]');
    if (card) {
      selectCharacter(card.dataset.charId);
    }
  });

  document.getElementById('messageContainer').addEventListener('click', function(e) {
    var quickReplyButton = e.target.closest('[data-quick-reply-index]');
    if (quickReplyButton) {
      var quickReplyIndex = parseInt(quickReplyButton.dataset.quickReplyIndex, 10);
      var quickReplyText = state.quickReplies[quickReplyIndex];
      if (quickReplyText) sendMessage({ text: quickReplyText });
      return;
    }
    var actionBtn = e.target.closest('[data-message-action]');
    if (!actionBtn) return;
    var messageId = actionBtn.dataset.msgId;
    if (actionBtn.dataset.messageAction === 'copy') copyMessage(messageId);
    else if (actionBtn.dataset.messageAction === 'edit-resend') editAndResendMessage(messageId);
    else if (actionBtn.dataset.messageAction === 'regenerate') regenerateReply(messageId);
  });

  document.addEventListener('click', function(event) {
    if (!event.target.closest('.theme-menu-wrap')) toggleThemeMenu(false);
    if (!event.target.closest('#stickerPanel') && !event.target.closest('[data-sticker-toggle]')) toggleStickerPanel(false);
  });
});

window.addEventListener('resize', function() {
  if (window.innerWidth >= 768) {
    document.getElementById('sidebarOverlay').classList.add('hidden');
  }
  updateAppHeight();
});

if (window.visualViewport) {
  window.visualViewport.addEventListener('resize', updateAppHeight);
}

// 页面关闭/隐藏前把防抖中的保存立即落盘
window.addEventListener('pagehide', function() {
  flushScheduledSave();
});
window.addEventListener('visibilitychange', function() {
  if (document.visibilityState === 'hidden') flushScheduledSave();
});
