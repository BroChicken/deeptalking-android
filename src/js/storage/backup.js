// ==================== Import/Export ====================
function isIOSDevice() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

function isMobileDevice() {
  return /Mobi|Android|iPhone|iPad|iPod/.test(navigator.userAgent || '') || (navigator.maxTouchPoints && navigator.maxTouchPoints > 1 && /Touch/i.test(navigator.userAgent || ''));
}

// 中文 JSON 安全转 base64（btoa 只支持拉丁字符）
function utf8ToBase64(str) {
  var bytes = new TextEncoder().encode(str);
  var bin = '';
  for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

// 触发一次下载（data URL 或 blob URL）。<a download> 是标准下载方式，
// 主流 Chromium 系浏览器（含迅雷）均支持；不额外 window.open，避免常规浏览器双触发。
function fireDownload(href, fileName) {
  var a = document.createElement('a');
  a.href = href;
  a.download = fileName || 'deeptalking_backup.json';
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  setTimeout(function() { document.body.removeChild(a); }, 10000);
}

// 手机浏览器优先走系统文件分享（可存到微信/文件管理器）；返回 Promise，成功=true，不可用=false
function shareJsonFile(dataStr, fileName) {
  try {
    if (typeof navigator.share !== 'function' || typeof navigator.canShare !== 'function') return Promise.resolve(false);
    var blob = new Blob([dataStr], { type: 'application/json' });
    var file = new File([blob], fileName, { type: 'application/json' });
    if (!navigator.canShare({ files: [file] })) return Promise.resolve(false);
    return navigator.share({ files: [file], title: fileName, text: 'DeepTalking 数据备份' }).then(function() {
      return true;
    }).catch(function(err) {
      console.warn('文件分享取消/失败:', err && err.message ? err.message : err);
      return false;
    });
  } catch (e) {
    console.warn('文件分享初始化失败:', e && e.message ? e.message : e);
    return Promise.resolve(false);
  }
}

// 用 File System Access API 保存文件（弹出系统"保存文件"对话框）。
// 不依赖浏览器下载监听器，data/blob URL 被第三方浏览器忽略时仍能真正保存。
// 返回 Promise：true=已保存，false=不可用/被取消。所有结果写入 lastDownloadLog。
function saveJsonWithPicker(dataStr, fileName) {
  if (typeof window.showSaveFilePicker !== 'function') {
    lastDownloadLog.push('showSaveFilePicker=不存在');
    return Promise.resolve(false);
  }
  var pickerOpts = {
    suggestedName: fileName || 'deeptalking_backup.json',
    types: [{
      description: 'JSON 文件',
      accept: { 'application/json': ['.json'] }
    }]
  };
  var pickerPromise;
  try {
    pickerPromise = window.showSaveFilePicker(pickerOpts);
    lastDownloadLog.push('showSaveFilePicker 已调用(等待用户选择)');
  } catch (e) {
    lastDownloadLog.push('showSaveFilePicker 同步抛错: ' + (e && e.name ? e.name + ': ' : '') + (e && e.message ? e.message : String(e)));
    alert('保存对话框调用失败(' + (e && e.name ? e.name : '未知') + ')：' + (e && e.message ? e.message : String(e)) + '。\n请改用"复制全部"保存。');
    return Promise.resolve(false);
  }
  return pickerPromise.then(function(handle) {
    lastDownloadLog.push('showSaveFilePicker 用户已选位置');
    return handle.createWritable().then(function(writable) {
      return writable.write(dataStr).then(function() {
        return writable.close();
      }).then(function() {
        lastDownloadLog.push('showSaveFilePicker 保存成功');
        return true;
      });
    });
  }).catch(function(err) {
    lastDownloadLog.push('showSaveFilePicker 失败: ' + (err && err.name === 'AbortError' ? '用户取消' : (err && err.name ? err.name + ': ' : '') + (err && err.message ? err.message : String(err))));
    return false;
  });
}

// 触发 JSON 导出。
// WebView 壳（迅雷浏览器 wv / file://）：share 不可用、data/blob 下载被忽略、
// showSaveFilePicker 存在但立即 AbortError——网页无法触发系统文件保存。
// 因此 WebView 下保持弹窗不动，明确引导用"复制全部"保存。
// 常规浏览器：showSaveFilePicker → share → data/blob 下载。
function downloadJsonBlob(dataStr, fileName) {
  fileName = fileName || 'deeptalking_backup.json';
  lastDownloadLog = ['导出触发: ' + fileName];

  // 【原生桥】APK WebView：把 JSON 直接交给原生层写入系统 Downloads，绕过浏览器下载限制
  if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.saveBackup === 'function') {
    lastDownloadLog.push('使用原生导出 AndroidBridge.saveBackup');
    var saveResult = '';
    try {
      saveResult = AndroidBridge.saveBackup(dataStr, fileName) || '';
    } catch (e) {
      saveResult = 'err:' + String(e && e.message ? e.message : e);
    }
    lastDownloadLog.push('原生导出结果: ' + saveResult);
    updateExportDebug();
    if (saveResult.indexOf('ok:') === 0) {
      lastNativeSaveOk = true;
      alert('备份已导出到：' + saveResult.slice(3));
      var exportModal = document.getElementById('exportTextModal');
      if (exportModal) exportModal.style.display = 'none';
      return;
    }
    alert('原生导出失败：' + saveResult.slice(4) + '\n请改用"复制全部"，粘贴到备忘录保存为 ' + fileName + '。');
    return;
  }

  var isWebViewLike = /wv|WebView/i.test(navigator.userAgent || '') || typeof navigator.share !== 'function';
  lastDownloadLog.push('isWebView=' + isWebViewLike);
  lastDownloadLog.push('showSaveFilePicker=' + (typeof window.showSaveFilePicker === 'function' ? 'true' : 'false'));

  if (isWebViewLike) {
    // WebView：先尝试一次 picker（万一支持就成功）；失败则保持弹窗并提示复制
    lastDownloadLog.push('WebView 环境，尝试 showSaveFilePicker');
    saveJsonWithPicker(dataStr, fileName).then(function(picked) {
      if (picked) {
        lastDownloadLog.push('导出完成(保存成功)');
        updateExportDebug();
        var exportModal = document.getElementById('exportTextModal');
        if (exportModal) exportModal.style.display = 'none';
        return;
      }
      lastDownloadLog.push('WebView 无法保存文件，提示用复制');
      alert('当前浏览器(WebView)不支持文件保存/下载。\n请点"复制全部"，粘贴到任意文本/备忘录中保存为 ' + fileName + '。');
    });
    return;
  }

  // 常规浏览器
  saveJsonWithPicker(dataStr, fileName).then(function(picked) {
    if (picked) {
      lastDownloadLog.push('导出完成(保存成功)');
      updateExportDebug();
      var exportModal = document.getElementById('exportTextModal');
      if (exportModal) exportModal.style.display = 'none';
      return;
    }
    shareJsonFile(dataStr, fileName).then(function(shared) {
      lastDownloadLog.push('shareJsonFile=' + (shared ? '成功' : '不可用/取消'));
      if (shared) return;
      fireDataUrlDownload(dataStr, fileName);
    });
  });
}

function fireDataUrlDownload(dataStr, fileName) {
  var dataUrl = null;
  var base64Length = 0;
  try {
    var encoded = utf8ToBase64(dataStr);
    base64Length = encoded.length;
    dataUrl = 'data:application/json;charset=utf-8;base64,' + encoded;
  } catch (e) { dataUrl = null; }
  lastDownloadLog.push('dataUrl生成=' + (dataUrl ? '成功(len=' + base64Length + ')' : '失败'));

  // data URL 总长度过大时浏览器可能拒绝下载，改用 blob URL
  if (dataUrl && base64Length < 1500000) {
    fireDownload(dataUrl, fileName);
    lastDownloadLog.push('已触发 data URL 下载(a.click)');
    // WebView 壳可能静默忽略 data/blob 下载：给用户明确提示可用的替代方案
    if (/wv|WebView/i.test(navigator.userAgent || '') || typeof navigator.share !== 'function') {
      lastDownloadLog.push('WebView 环境，下载可能被忽略');
      alert('已尝试下载。若浏览器无反应（WebView 可能不支持 data/blob 下载），请用"复制全部"按钮复制后另存为 .json 文件。');
    }
    updateExportDebug();
    return;
  }
  var blob = null;
  var url = null;
  try {
    blob = new Blob([dataStr], { type: 'application/json' });
    url = URL.createObjectURL(blob);
  } catch (e) { url = null; }
  lastDownloadLog.push('blob创建=' + (url ? '成功' : '失败'));
  if (url) {
    try {
      fireDownload(url, fileName);
      lastDownloadLog.push('已触发 blob URL 下载(a.click)');
      setTimeout(function() { URL.revokeObjectURL(url); }, 10000);
      updateExportDebug();
      return;
    } catch (err) {
      lastDownloadLog.push('blob 下载抛错: ' + (err && err.message ? err.message : err));
      console.warn('blob 下载失败，改用 data URL:', err);
    }
  }
  if (dataUrl) { fireDownload(dataUrl, fileName); lastDownloadLog.push('已触发 data URL 下载(a.click)'); }
  updateExportDebug();
}

// 刷新导出弹窗中的调试信息
function updateExportDebug() {
  var debugLine = document.getElementById('exportDebugLine');
  if (!debugLine) return;
  var extra = lastDownloadLog && lastDownloadLog.length ? '\n[下载尝试]\n' + lastDownloadLog.join('\n') : '';
  debugLine.textContent = debugLine.getAttribute('data-base') + extra;
}

// 通用复制文本（现代 API 优先，execCommand 兜底）
function copyTextToClipboard(text, successMsg) {
  function done() {
    alert(successMsg || '已复制到剪贴板');
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(function() {
      fallbackCopy(text, successMsg);
    });
  } else {
    fallbackCopy(text, successMsg);
  }
}

function exportAllData() {
  var dataStr = '';
  try {
    // 导出时剔除 apiKey（顶层 + 各平台槽）：备份/分享文件不应包含 API 密钥
    var exportedConfig = {};
    Object.keys(state.config).forEach(function(cfgKey) {
      if (cfgKey === 'apiKey') return;
      if (cfgKey === 'platformSettings' && state.config.platformSettings) {
        var cleanSettings = {};
        Object.keys(state.config.platformSettings).forEach(function(plat) {
          var slot = state.config.platformSettings[plat];
          var cleanSlot = {};
          if (slot) {
            Object.keys(slot).forEach(function(slotKey) {
              if (slotKey === 'apiKey') return;
              cleanSlot[slotKey] = slot[slotKey];
            });
          }
          cleanSettings[plat] = cleanSlot;
        });
        exportedConfig[cfgKey] = cleanSettings;
        return;
      }
      exportedConfig[cfgKey] = state.config[cfgKey];
    });
    var data = {
      config: exportedConfig,
      characters: state.characters,
      activeCharacterId: state.activeCharacterId,
      activeTheme: state.activeTheme,
      exportDate: new Date().toISOString(),
      version: APP_VERSION
    };
    
    dataStr = JSON.stringify(data, null, 2);
    var fileName = 'deeptalking_backup_' + new Date().toISOString().split('T')[0] + '.json';
    
    var blob = new Blob([dataStr], { type: 'application/json' });
    var file = null;
    try {
      file = new File([blob], fileName, { type: 'application/json' });
    } catch (fileError) {
      console.warn('当前环境无法创建分享文件:', fileError);
    }

    // 【方案 1：手机优先系统文件分享（可存到微信/文件管理器/迅雷）】分享成功即完成，取消则回退下载+弹窗
    if (isMobileDevice() && file && navigator.canShare && navigator.canShare({ files: [file] })) {
      navigator.share({
        files: [file],
        title: fileName,
        text: 'DeepTalking 数据备份'
      }).catch(function(error) {
        console.log('分享取消', error);
        showExportModal(dataStr, fileName, blob); // 弹窗内按钮是真实用户手势，由用户主动选择下载/复制
      });
      return;
    }

    // 【方案 2：直接触发浏览器下载（尽力而为，不阻塞）】
    downloadJsonBlob(dataStr, fileName);

    // 【方案 3：兜底弹窗（复制全部 + 手动下载）】原生导出成功时不再弹，其余情况始终显示
    if (!lastNativeSaveOk) showExportModal(dataStr, fileName, blob);
    lastNativeSaveOk = false;

  } catch (error) {
    console.error('导出失败:', error);
    showExportModal(dataStr || '', null, null); // 出错也弹出复制框
  }
}

var lastExportDataStr = null;
var lastExportName = '';
var lastDownloadLog = [];
var lastNativeSaveOk = false;

// 纯净版的兜底弹窗，带一键复制 + 手动下载按钮
function showExportModal(dataStr, fileName, blob) {
  lastExportDataStr = dataStr || null;
  lastExportName = fileName || 'deeptalking_backup.json';
  // 追加而非重置：保留 downloadJsonBlob/picker 的尝试日志，方便排查
  if (!lastDownloadLog || lastDownloadLog.length === 0) {
    lastDownloadLog = ['弹窗显示: ' + (lastExportDataStr ? lastExportDataStr.length + ' 字符' : '空')];
  } else {
    lastDownloadLog.push('弹窗重新显示: ' + (lastExportDataStr ? lastExportDataStr.length + ' 字符' : '空'));
  }
  // 释放上一次的 blob URL
  var prevBtn = document.getElementById('exportDownloadBtn');
  if (prevBtn && prevBtn._dlBlobUrl) {
    try { URL.revokeObjectURL(prevBtn._dlBlobUrl); } catch (e) { /* 忽略 */ }
    delete prevBtn._dlBlobUrl;
  }
  var exportModal = document.getElementById('exportTextModal');
  if (!exportModal) {
    exportModal = document.createElement('div');
    exportModal.id = 'exportTextModal';
    exportModal.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.8);z-index:9999;display:flex;align-items:center;justify-content:center;padding:20px;box-sizing:border-box;';
    
    var innerDiv = document.createElement('div');
    innerDiv.style.cssText = 'background:#fff;padding:20px;border-radius:10px;width:100%;max-width:400px;max-height:80vh;display:flex;flex-direction:column;box-sizing:border-box;';
    
    // 顶部标题和操作按钮
    var headerDiv = document.createElement('div');
    headerDiv.style.cssText = 'display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;margin-bottom:15px;gap:8px;';
    
    var title = document.createElement('p');
    title.innerText = '未自动下载，可复制或手动下载';
    title.style.cssText = 'color:#000;margin:0;font-size:14px;text-align:left;flex:1 1 140px;';
    headerDiv.appendChild(title);

    var debugWrap = document.createElement('div');
    debugWrap.id = 'exportDebugWrap';
    debugWrap.style.cssText = 'width:100%;margin:6px 0 0 0;display:flex;align-items:flex-start;gap:6px;';
    var debugLine = document.createElement('p');
    debugLine.id = 'exportDebugLine';
    debugLine.style.cssText = 'color:#a00;margin:0;font-size:11px;text-align:left;flex:1;word-break:break-all;white-space:pre-wrap;';
    debugWrap.appendChild(debugLine);
    var debugCopyBtn = document.createElement('button');
    debugCopyBtn.innerText = '复制';
    debugCopyBtn.title = '复制调试信息';
    debugCopyBtn.style.cssText = 'background:#666;color:#fff;border:none;border-radius:4px;padding:3px 8px;font-size:11px;cursor:pointer;flex-shrink:0;';
    debugCopyBtn.onclick = function() {
      var text = debugLine.textContent || '';
      copyTextToClipboard(text, '调试信息已复制');
    };
    debugWrap.appendChild(debugCopyBtn);
    headerDiv.appendChild(debugWrap);
    
    var copyBtn = document.createElement('button');
    copyBtn.innerText = '复制全部';
    copyBtn.style.cssText = 'background:#4a90d9;color:#fff;border:none;border-radius:5px;padding:8px 12px;font-size:13px;cursor:pointer;flex-shrink:0;-webkit-tap-highlight-color:transparent;';
    copyBtn.onclick = function() {
      copyExportData();
    };
    headerDiv.appendChild(copyBtn);
    
    var downloadBtn = document.createElement('a');
    downloadBtn.id = 'exportDownloadBtn';
    downloadBtn.innerText = '下载 JSON';
    downloadBtn.download = lastExportName || 'deeptalking_backup.json';
    downloadBtn.href = '';
    downloadBtn.style.cssText = 'display:inline-block;background:#2f8f4e;color:#fff;text-decoration:none;border-radius:5px;padding:8px 12px;font-size:13px;flex-shrink:0;-webkit-tap-highlight-color:transparent;';
    downloadBtn.onclick = function(e) {
      if (!lastExportDataStr) { alert('暂无可下载的数据'); return false; }
      // 统一走 downloadJsonBlob：showSaveFilePicker（真正保存对话框）优先 →
      // 手机分享 → data/blob URL → 复制兜底。
      e.preventDefault();
      downloadJsonBlob(lastExportDataStr, lastExportName);
      return false;
    };
    headerDiv.appendChild(downloadBtn);

    var viewBtn = document.createElement('button');
    viewBtn.id = 'exportViewBtn';
    viewBtn.innerText = '新标签查看';
    viewBtn.title = '在新标签打开 JSON 内容（HTML 包装，任何浏览器可渲染）';
    viewBtn.style.cssText = 'background:#8a6d3b;color:#fff;border:none;border-radius:5px;padding:8px 12px;font-size:13px;cursor:pointer;flex-shrink:0;-webkit-tap-highlight-color:transparent;';
    viewBtn.onclick = function() {
      if (!lastExportDataStr) { alert('暂无可查看的数据'); return; }
      try {
        // 用 text/html 包装：application/json 的 data URL 在部分手机浏览器（如迅雷）会因
        // 无关联应用而打开空白页；HTML 包装可确保任何浏览器都渲染出 JSON 文本。
        var htmlDoc = '<!DOCTYPE html><html><head><meta charset="utf-8">'
          + '<title>' + escapeHtml(lastExportName) + '</title>'
          + '<style>body{margin:0;background:#fff;color:#000;font-family:monospace;font-size:12px;line-height:1.5;padding:12px;word-break:break-all;white-space:pre-wrap;}</style></head>'
          + '<body>' + escapeHtml(lastExportDataStr) + '</body></html>';
        var dataUrl = 'data:text/html;charset=utf-8,' + encodeURIComponent(htmlDoc);
        var win = window.open(dataUrl, '_blank');
        if (!win) alert('浏览器拦截了新窗口，请改用"复制全部"');
      } catch (e) {
        console.error('新标签查看失败:', e);
        alert('无法打开新窗口(' + (e && e.message ? e.message : '未知错误') + ')，请改用"复制全部"');
      }
    };
    headerDiv.appendChild(viewBtn);
    
    innerDiv.appendChild(headerDiv);

    var textarea = document.createElement('textarea');
    textarea.id = 'exportTextarea';
    // 设置为可选择，为了兼容 execCommand
    textarea.readOnly = false; 
    textarea.value = dataStr;
    textarea.style.cssText = 'width:100%;height:200px;color:#000;border:1px solid #ccc;border-radius:5px;padding:10px;font-size:12px;box-sizing:border-box;';
    innerDiv.appendChild(textarea);
    
    var closeBtn = document.createElement('button');
    closeBtn.innerText = '关闭';
    closeBtn.style.cssText = 'margin-top:15px;width:100%;height:45px;background:#666;color:#fff;border:none;border-radius:8px;font-size:14px;cursor:pointer;-webkit-tap-highlight-color:transparent;';
    closeBtn.onclick = function() { 
      exportModal.style.display = 'none'; 
    };
    innerDiv.appendChild(closeBtn);
    
    exportModal.appendChild(innerDiv);
    document.body.appendChild(exportModal);
  } else {
    document.getElementById('exportTextarea').value = dataStr;
    exportModal.style.display = 'flex';
  }
  // 无可下载数据时隐藏下载/查看按钮
  var downloadBtn = document.getElementById('exportDownloadBtn');
  if (downloadBtn) {
    if (lastExportDataStr) {
      downloadBtn.style.display = 'inline-block';
      // 真实 <a download>：用户手指点击由 WebView/浏览器下载监听器接管。
      // blob URL 优先（无长度限制，WebView 下载监听支持好）；data URL 兜底。
      downloadBtn.download = lastExportName || 'deeptalking_backup.json';
      downloadBtn.href = '';
      downloadBtn.title = '手指点击下载 JSON 文件';
      try {
        var dlBlob = new Blob([lastExportDataStr], { type: 'application/json' });
        var dlBlobUrl = URL.createObjectURL(dlBlob);
        downloadBtn.href = dlBlobUrl;
        downloadBtn._dlBlobUrl = dlBlobUrl;
        lastDownloadLog.push('下载链接: blob URL (' + lastExportDataStr.length + ' 字符)');
      } catch (e) {
        try {
          downloadBtn.href = 'data:application/json;charset=utf-8;base64,' + utf8ToBase64(lastExportDataStr);
          lastDownloadLog.push('下载链接: data URL (blob 不可用)');
        } catch (e2) {
          downloadBtn.href = '';
          downloadBtn.title = '下载不可用，请用复制';
          lastDownloadLog.push('下载链接: 生成失败');
        }
      }
    } else {
      downloadBtn.style.display = 'none';
    }
  }
  var viewBtn = document.getElementById('exportViewBtn');
  if (viewBtn) viewBtn.style.display = lastExportDataStr ? '' : 'none';
  // 调试信息：暴露浏览器与导出能力，便于排查"下载无反应"问题
  var debugLine = document.getElementById('exportDebugLine');
  if (debugLine && dataStr) {
    try {
      var dbg = [
        'v' + APP_VERSION,
        'UA: ' + (navigator.userAgent || '未知'),
        'share=' + (typeof navigator.share === 'function') + ', canShare=' + (typeof navigator.canShare === 'function'),
        'clipboard=' + (typeof navigator.clipboard === 'object' && !!navigator.clipboard),
        'showSaveFilePicker=' + (typeof window.showSaveFilePicker === 'function'),
        'File=' + (typeof File === 'function'),
        'Blob=' + (typeof Blob === 'function'),
        'textLen=' + dataStr.length
      ].join('\n');
      debugLine.setAttribute('data-base', dbg);
      var extra = lastDownloadLog && lastDownloadLog.length ? '\n[下载尝试]\n' + lastDownloadLog.join('\n') : '';
      debugLine.textContent = dbg + extra;
      debugLine.style.display = '';
      debugLine.parentNode.style.display = 'flex';
    } catch (e) { debugLine.style.display = 'none'; }
  } else if (debugLine) {
    debugLine.style.display = 'none';
    if (debugLine.parentNode) debugLine.parentNode.style.display = 'none';
  }
}

// 一键复制逻辑
function copyExportData() {
  var textarea = document.getElementById('exportTextarea');
  copyTextToClipboard(textarea ? textarea.value : '', '已复制全部内容到剪贴板');
}

// 安卓兼容性最好的 execCommand 复制方案
function fallbackCopy(text, successMsg) {
  var ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.top = '-9999px';
  ta.style.left = '-9999px';
  document.body.appendChild(ta);
  
  // iOS 和安卓需要聚焦并选择
  ta.focus();
  ta.select();
  ta.setSelectionRange(0, ta.value.length);
  
  try {
    var successful = document.execCommand('copy');
    if (successful) {
      alert(successMsg || '已复制到剪贴板');
    } else {
      alert('复制失败，请手动长按文本框选择');
    }
  } catch (err) {
    alert('复制失败，请手动长按文本框选择');
  }
  
  document.body.removeChild(ta);
}


function decodeImportBuffer(buffer) {
  var bytes = new Uint8Array(buffer);
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch (utfErr) {
    try {
      return new TextDecoder('gbk').decode(bytes);
    } catch (gbkErr) {
      return new TextDecoder('utf-8').decode(bytes);
    }
  }
}

function importData(event) {
  var file = event.target.files[0];
  if (!file) return;
  var reader = new FileReader();
  reader.onload = function(e) {
    try {
      var text = decodeImportBuffer(e.target.result);
      var data = JSON.parse(text);
      var hasVersion = data && data.version != null;
      var normalizedData = normalizeAppData(data, true, hasVersion);
      if (!confirm('导入将覆盖当前所有数据，确定继续？')) return;
      applyNormalizedData(normalizedData, true);
      saveData();
      applyTheme();
      renderCharacterList();
      renderChat();
      loadSettingsForm();
      alert('导入成功');
      setTimeout(autoFillStaticFields, 1500);
      setTimeout(maybeMigrateFieldStructure, 1200);
    } catch (err) {
      alert('导入失败: ' + err.message);
    }
  };
  reader.onerror = function() {
    alert('文件读取失败，请重试');
  };
  reader.readAsArrayBuffer(file);
  event.target.value = '';
}

// 复制单个消息气泡的文本内容
function copyMessage(messageId) {
  var char = state.characters[state.activeCharacterId];
  if (!char) return;
  var messages = (char.memory && char.memory.instant) || [];
  var msg = messages.find(function(item) { return item.id === messageId; });
  if (!msg || msg.isLoading) return;
  var text = typeof msg.content === 'string' ? msg.content : toText(msg.content);
  var roleLabel = msg.role === 'user' ? '用户' : toText(char.basicInfo && char.basicInfo.name || '角色');
  copyTextToClipboard(text, '已复制' + roleLabel + '的消息');
}

