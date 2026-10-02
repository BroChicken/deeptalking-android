// ==================== 表情包库（每个角色/群组独立；用户可上传，双方均可发送） ====================
function getActiveStickerHost() {
  return state.activeCharacterId ? (state.characters[state.activeCharacterId] || null) : null;
}

function getHostStickers(host) {
  if (!host) return [];
  if (!Array.isArray(host.stickers)) host.stickers = [];
  return host.stickers;
}

function readFileAsDataUrl(file) {
  return new Promise(function(resolve) {
    var reader = new FileReader();
    reader.onload = function() { resolve(reader.result); };
    reader.onerror = function() { resolve(null); };
    reader.readAsDataURL(file);
  });
}

function triggerStickerUpload() {
  var input = document.getElementById('stickerInput');
  if (input) input.click();
}

function handleStickerPick(event) {
  var host = getActiveStickerHost();
  var files = Array.prototype.slice.call(event.target.files || []);
  event.target.value = '';
  if (!host) return;
  files.forEach(function(file) {
    if (!file || file.type.indexOf('image/') !== 0) return;
    readFileAsDataUrl(file).then(function(dataUrl) {
      if (!dataUrl) return;
      return addStickerFromImage(dataUrl, host).then(function(res) {
        if (res && res.created) tagStickerInBackground(res.sticker, host);
      });
    }).catch(function() { alert('图片处理失败，请换一张试试'); });
  });
}

function compressStickerDataUrl(dataUrl) {
  return new Promise(function(resolve) {
    var img = new Image();
    img.onload = function() {
      try {
        var scale = Math.min(1, STICKER_LIMITS.maxDimension / Math.max(img.width, img.height));
        var w = Math.max(1, Math.round(img.width * scale));
        var h = Math.max(1, Math.round(img.height * scale));
        var canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        var ctx = canvas.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, w, h);
        ctx.drawImage(img, 0, 0, w, h);
        var quality = 0.82;
        var out = canvas.toDataURL('image/jpeg', quality);
        while (out.length * 0.75 > STICKER_LIMITS.targetBytes && quality > 0.4) {
          quality -= 0.12;
          out = canvas.toDataURL('image/jpeg', quality);
        }
        resolve(out);
      } catch (e) {
        resolve(null);
      }
    };
    img.onerror = function() { resolve(null); };
    img.src = dataUrl;
  });
}

async function addStickerFromImage(dataUrl, host) {
  if (!dataUrl || !/^data:image\//i.test(dataUrl)) return null;
  host = host || getActiveStickerHost();
  if (!host) return null;
  var list = getHostStickers(host);
  var rawDup = list.find(function(s) { return s.dataUrl === dataUrl; });
  if (rawDup) return { sticker: rawDup, created: false };
  var stickerUrl = await compressStickerDataUrl(dataUrl);
  if (!stickerUrl) return null;
  var dup = list.find(function(s) { return s.dataUrl === stickerUrl; });
  if (dup) return { sticker: dup, created: false };
  var now = new Date().toISOString();
  var sticker = { id: createMemoryId('stk'), dataUrl: stickerUrl, tag: '未分类', createdAt: now, updatedAt: now };
  list.push(sticker);
  scheduleSave();
  if (host === getActiveStickerHost()) renderStickerPanel();
  return { sticker: sticker, created: true };
}

async function tagStickerImage(dataUrl) {
  try {
    var response = await callAPI([
      { role: 'system', content: '你是表情包分类助手。看这张表情包或图片，从下列标签中选一个最贴切的：' + STICKER_TAGS.join('、') + '。若都不贴切，就给出一个 1 到 ' + STICKER_LIMITS.maxTagChars + ' 字的中文短词。只输出这一个词，不要标点、解释或其它文字。' },
      { role: 'user', content: [ { type: 'input_text', text: '给这张图打一个短词标签。' }, { type: 'input_image', image_url: dataUrl, detail: 'low' } ] }
    ]);
    var raw = toText(response.choices[0].message.content).replace(/[\s\r\n"'“”‘’。，,、！!？?：:；;（）()\[\]]/g, '');
    return raw.slice(0, STICKER_LIMITS.maxTagChars);
  } catch (e) {
    return '';
  }
}

async function tagStickerInBackground(sticker, host) {
  if (!sticker || !state.config.apiKey) return;
  host = host || getActiveStickerHost();
  if (!host) return;
  var tag = await tagStickerImage(sticker.dataUrl);
  if (!tag) return;
  var living = getHostStickers(host).find(function(s) { return s.id === sticker.id; });
  if (!living) return;
  living.tag = tag;
  living.updatedAt = new Date().toISOString();
  scheduleSave();
  if (host === getActiveStickerHost()) renderStickerPanel();
}

function getStickersByTag(host, tag) {
  var list = getHostStickers(host);
  var needle = toText(tag).trim();
  if (!needle) return [];
  var exact = list.filter(function(s) { return s.tag === needle; });
  if (exact.length) return exact;
  return list.filter(function(s) { return toText(s.tag).indexOf(needle) !== -1 || needle.indexOf(toText(s.tag)) !== -1; });
}

function toggleStickerPanel(force) {
  var panel = document.getElementById('stickerPanel');
  if (!panel) return;
  var isHidden = !panel.style.display || panel.style.display === 'none';
  var open = typeof force === 'boolean' ? force : isHidden;
  panel.style.display = open ? 'block' : 'none';
  if (open) renderStickerPanel();
}

function renderStickerPanel() {
  var grid = document.getElementById('stickerGrid');
  if (!grid) return;
  var count = document.getElementById('stickerCount');
  if (state.editingStickerId && !getHostStickers(getActiveStickerHost()).some(function(s) { return s.id === state.editingStickerId; })) {
    state.editingStickerId = null;
  }
  var list = getHostStickers(getActiveStickerHost());
  if (!getActiveStickerHost()) {
    if (count) count.textContent = '';
    grid.innerHTML = '<div class="sticker-empty">先选择一个角色，再管理它的表情包。</div>';
    return;
  }
  if (count) count.textContent = list.length + ' 张';
  if (list.length === 0) {
    grid.innerHTML = '<div class="sticker-empty">还没有表情包。点上方「上传表情」加入，或直接发送图片自动收藏。</div>';
    return;
  }
  grid.innerHTML = list.map(function(s) {
    var tagHtml = (state.editingStickerId === s.id)
      ? '<input class="sticker-tag-input" value="' + escapeHtml(s.tag) + '" onkeydown="if(event.key===\'Enter\'){this.blur()}else if(event.key===\'Escape\'){state.editingStickerId=null;renderStickerPanel()}" onblur="saveStickerTag(\'' + escapeHtml(s.id) + '\', this.value)">'
      : '<span class="sticker-tag" onclick="editStickerTag(\'' + escapeHtml(s.id) + '\')" title="点击改标签">' + escapeHtml(s.tag) + '</span>';
    return '<div class="sticker-cell">' +
      '<img src="' + escapeHtml(s.dataUrl) + '" class="sticker-thumb" alt="' + escapeHtml(s.tag) + '" onclick="sendStickerAsUser(\'' + escapeHtml(s.id) + '\')">' +
      '<div class="sticker-bar">' + tagHtml +
      '<button type="button" class="sticker-del" onclick="deleteSticker(\'' + escapeHtml(s.id) + '\')" aria-label="删除">×</button></div>' +
      '</div>';
  }).join('');
  var input = grid.querySelector('.sticker-tag-input');
  if (input) input.focus();
}

async function sendStickerAsUser(id) {
  var host = getActiveStickerHost();
  var sticker = getHostStickers(host).find(function(s) { return s.id === id; });
  if (!sticker || state.isProcessing) return;
  toggleStickerPanel(false);
  var input = document.getElementById('messageInput');
  // 输入框里若有文字，先作为一条普通消息发出，贴图紧随其后单独成一条，不共享气泡
  if (input && input.value.trim()) {
    await sendMessage();
    if (state.isProcessing || !state.activeCharacterId) return;
  }
  state.pendingImages = [{ dataUrl: sticker.dataUrl }];
  renderImagePreview();
  await sendMessage();
}

function deleteSticker(id) {
  var host = getActiveStickerHost();
  if (!host || !Array.isArray(host.stickers)) return;
  host.stickers = host.stickers.filter(function(s) { return s.id !== id; });
  scheduleSave();
  renderStickerPanel();
}

function editStickerTag(id) {
  state.editingStickerId = id;
  renderStickerPanel();
}

function saveStickerTag(id, value) {
  var host = getActiveStickerHost();
  var sticker = getHostStickers(host).find(function(s) { return s.id === id; });
  if (sticker) {
    var tag = toText(value).replace(/[\s\r\n]+/g, '').slice(0, STICKER_LIMITS.maxTagChars);
    if (tag) {
      sticker.tag = tag;
      sticker.updatedAt = new Date().toISOString();
      scheduleSave();
    }
  }
  state.editingStickerId = null;
  renderStickerPanel();
}

function toggleSidebar(force) {
  var sidebar = document.getElementById('sidebar');
  var overlay = document.getElementById('sidebarOverlay');
  if (force === false) {
    sidebar.classList.add('hidden-sidebar');
    overlay.classList.add('hidden');
    state.sidebarOpen = false;
  } else if (force === true) {
    sidebar.classList.remove('hidden-sidebar');
    if (window.innerWidth < 768) overlay.classList.remove('hidden');
    state.sidebarOpen = true;
  } else {
    sidebar.classList.toggle('hidden-sidebar');
    if (window.innerWidth < 768) overlay.classList.toggle('hidden');
    state.sidebarOpen = !state.sidebarOpen;
  }
}

function switchTab(tab) {
  document.getElementById('tab-characters').classList.remove('active');
  document.getElementById('tab-settings').classList.remove('active');
  document.getElementById('tab-' + tab).classList.add('active');
  document.getElementById('content-characters').classList.add('hidden');
  document.getElementById('content-settings').classList.add('hidden');
  document.getElementById('content-' + tab).classList.remove('hidden');
  if (tab === 'settings') loadSettingsForm();
}

function loadSettingsForm() {
  var platform = state.config.apiPlatform;
  var slot = getPlatformSettings(platform);
  document.getElementById('apiPlatform').value = platform;
  document.getElementById('apiBaseUrl').value = slot.baseUrl || state.config.apiBaseUrl || '';
  document.getElementById('apiKey').value = (slot.apiKey != null ? slot.apiKey : '') || state.config.apiKey || '';
  document.getElementById('apiTemperature').value = normalizeTemperature(state.config.temperature);
  document.getElementById('apiStream').checked = state.config.stream !== false;
  document.getElementById('apiThinking').value = normalizeReasoningEffort(state.config.reasoningEffort);
  var proactiveToggle = document.getElementById('proactiveToggle');
  if (proactiveToggle) proactiveToggle.checked = state.config.proactiveEnabled !== false;
  var styleCritiqueToggle = document.getElementById('styleCritiqueToggle');
  if (styleCritiqueToggle) styleCritiqueToggle.checked = state.config.styleCritique !== false;
  var quickReplyRepairToggle = document.getElementById('quickReplyRepairToggle');
  if (quickReplyRepairToggle) quickReplyRepairToggle.checked = state.config.quickReplyRepair !== false;
  renderModelOptions(platform);
  document.getElementById('modelName').value = slot.modelName || state.config.modelName || '';
  renderCacheStats();
  renderDebugInfo();
  ['apiPlatform', 'apiBaseUrl', 'apiKey', 'modelName', 'apiTemperature', 'apiStream', 'apiThinking', 'proactiveToggle', 'styleCritiqueToggle', 'quickReplyRepairToggle'].forEach(function(id) {
    var el = document.getElementById(id);
    if (el) el.addEventListener('change', saveSettings);
  });
}

function renderCacheStats() {
  var element = document.getElementById('cacheStatsBar');
  if (!element) return;
  var stats = state.config.cacheStats;
  if (!stats) {
    element.textContent = '缓存 --';
    element.classList.remove('hidden');
    return;
  }
  if (stats.hitTokens == null || stats.missTokens == null) {
    element.textContent = '缓存未返回命中数据';
    element.classList.remove('hidden');
    return;
  }
  var total = stats.hitTokens + stats.missTokens;
  var rate = total ? Math.round(stats.hitTokens * 100 / total) : 0;
  var recent = (state.config.requestMetrics || []).filter(function(item) { return item.taskType === 'chat' && item.hitRate != null; }).slice(-10);
  var average = recent.length ? Math.round(recent.reduce(function(sum, item) { return sum + item.hitRate; }, 0) * 100 / recent.length) : rate;
  element.textContent = '缓存命中 ' + rate + '% · 近' + recent.length + '轮均值 ' + average + '%';
  element.title = '本轮 ' + formatCompactNumber(stats.hitTokens) + ' 命中 / ' + formatCompactNumber(stats.missTokens)
    + ' 未命中 token；最近10次对话请求平均 ' + average + '%';
  element.classList.remove('hidden');
}

function formatCompactNumber(value) {
  var number = Number(value);
  if (!Number.isFinite(number)) return String(value);
  if (number >= 1000000) return (number / 1000000).toFixed(1) + 'M';
  if (number >= 10000) return Math.round(number / 1000) + 'k';
  if (number >= 1000) return (number / 1000).toFixed(1) + 'k';
  return String(Math.round(number));
}

// 右上角状态显示的唯一来源：工具名 -> 进度提示（纯函数，便于单测覆盖全部分支）
