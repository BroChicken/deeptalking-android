// ==================== 图片上传（识图） ====================
var MAX_IMAGE_DIMENSION = 1280;
var MAX_IMAGE_BYTES = 2 * 1024 * 1024;
function triggerImagePick() {
  var input = document.getElementById('imageInput');
  if (input) input.click();
}
function handleImagePick(event) {
  var files = Array.prototype.slice.call(event.target.files || []);
  event.target.value = '';
  files.forEach(function(file) {
    if (!file || file.type.indexOf('image/') !== 0) return;
    compressImageFile(file).then(function(dataUrl) {
      if (!dataUrl) return;
      state.pendingImages = Array.isArray(state.pendingImages) ? state.pendingImages : [];
      if (state.pendingImages.length >= 4) { alert('一次最多发送 4 张图片'); return; }
      state.pendingImages.push({ dataUrl: dataUrl });
      renderImagePreview();
    }).catch(function() { alert('图片处理失败，请换一张试试'); });
  });
}
function compressImageFile(file) {
  return new Promise(function(resolve) {
    var reader = new FileReader();
    reader.onload = function() {
      var img = new Image();
      img.onload = function() {
        try {
          var scale = Math.min(1, MAX_IMAGE_DIMENSION / Math.max(img.width, img.height));
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
          var dataUrl = canvas.toDataURL('image/jpeg', quality);
          while (dataUrl.length * 0.75 > MAX_IMAGE_BYTES && quality > 0.4) {
            quality -= 0.12;
            dataUrl = canvas.toDataURL('image/jpeg', quality);
          }
          resolve(dataUrl);
        } catch (e) {
          resolve(null);
        }
      };
      img.onerror = function() { resolve(null); };
      img.src = reader.result;
    };
    reader.onerror = function() { resolve(null); };
    reader.readAsDataURL(file);
  });
}
function renderImagePreview() {
  var row = document.getElementById('imagePreviewRow');
  if (!row) return;
  var list = Array.isArray(state.pendingImages) ? state.pendingImages : [];
  if (list.length === 0) {
    row.style.display = 'none';
    row.innerHTML = '';
    return;
  }
  row.style.display = 'flex';
  row.innerHTML = list.map(function(item, index) {
    return '<div class="image-preview-item"><img src="' + escapeHtml(item.dataUrl) + '" alt="待发送图片"><button type="button" onclick="removePendingImage(' + index + ')" aria-label="移除图片">×</button></div>';
  }).join('');
}
function removePendingImage(index) {
  if (!Array.isArray(state.pendingImages)) return;
  state.pendingImages.splice(index, 1);
  renderImagePreview();
}
function clearPendingImages() {
  state.pendingImages = [];
  renderImagePreview();
}
function openImagePreview(src) {
  var box = document.getElementById('imageLightbox');
  var img = document.getElementById('imageLightboxImg');
  if (!box || !img || !src) return;
  img.src = src;
  box.style.display = 'flex';
}
function closeImagePreview() {
  var box = document.getElementById('imageLightbox');
  var img = document.getElementById('imageLightboxImg');
  if (box) box.style.display = 'none';
  if (img) img.src = '';
}

