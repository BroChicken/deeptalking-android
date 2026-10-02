// ==================== Pixel Avatar（试验失败品，已弃用） ====================
// ⚠️ 像素头像生成不稳定（模型输出的 16x16 网格常缺行/缺色/尺寸错误），与 TTS 一样属于
//    试验失败品。仅保留 normalizePixelAvatar / avatarPixel 字段做数据兼容，
//    不再用于头像展示，也不再作为自动修复的生成方式（自动修复改用 AI 生成 emoji）。
var PIXEL_AVATAR_PALETTE = {
  '.': null, 'K': '#1b1b1f', 'W': '#ffffff', 'R': '#e74c3c', 'O': '#e67e22',
  'Y': '#f1c40f', 'G': '#2ecc71', 'C': '#1abc9c', 'B': '#3498db', 'P': '#9b59b6',
  'M': '#e84393', 'N': '#8d6e63', 'S': '#ffdbac', 'H': '#636e72', 'L': '#dfe6e9'
};
var PIXEL_AVATAR_SIZE = 16;

function normalizePixelAvatar(rows) {
  if (!Array.isArray(rows)) return null;
  var cleaned = [];
  for (var i = 0; i < PIXEL_AVATAR_SIZE; i++) {
    var row = String(rows[i] == null ? '' : rows[i]);
    var buf = '';
    for (var j = 0; j < PIXEL_AVATAR_SIZE && j < row.length; j++) {
      var ch = row.charAt(j);
      buf += PIXEL_AVATAR_PALETTE[ch] ? ch : '.';
    }
    while (buf.length < PIXEL_AVATAR_SIZE) buf += '.';
    cleaned.push(buf);
  }
  var hasColor = cleaned.some(function(row) { return /[A-Za-z]/.test(row); });
  return hasColor ? cleaned : null;
}

function pixelAvatarDataUrl(rows) {
  var grid = normalizePixelAvatar(rows);
  if (!grid) return null;
  var scale = 8;
  var canvas = document.createElement('canvas');
  canvas.width = PIXEL_AVATAR_SIZE * scale;
  canvas.height = PIXEL_AVATAR_SIZE * scale;
  var ctx = canvas.getContext('2d');
  for (var y = 0; y < PIXEL_AVATAR_SIZE; y++) {
    for (var x = 0; x < PIXEL_AVATAR_SIZE; x++) {
      var color = PIXEL_AVATAR_PALETTE[grid[y].charAt(x)];
      if (color) {
        ctx.fillStyle = color;
        ctx.fillRect(x * scale, y * scale, scale, scale);
      }
    }
  }
  return canvas.toDataURL('image/png');
}

function isAvatarDamaged(value) {
  var v = typeof value === 'string' ? value.trim() : '';
  if (!v) return true;
  if (v.indexOf('?') >= 0 || v.indexOf('\ufffd') >= 0) return true;
  var hasNonAscii = false;
  for (var i = 0; i < v.length; i++) {
    if (v.charCodeAt(i) > 127) { hasNonAscii = true; break; }
  }
  if (!hasNonAscii) return true;
  return false;
}

function sanitizeAvatar(value, fallback) {
  return isAvatarDamaged(value) ? fallback : value.trim();
}

// 头像一律使用 emoji（像素头像为试验失败品，不稳定，已弃用展示；仅保留数据兼容）
function renderAvatarHtml(char, imgClass, emojiClass) {
  var fallback = char && char.entityType === 'group' ? '👥' : '👤';
  var avatar = sanitizeAvatar(char && char.basicInfo && char.basicInfo.avatar, fallback);
  return '<span class="' + emojiClass + '">' + escapeHtml(avatar) + '</span>';
}

var PIXEL_AVATAR_PROMPT = '为角色绘制16x16像素头像，字段"avatarPixel"为包含16个字符串的数组，每个字符串恰好16个字符。必须用像素块画出具体的角色形象（发型轮廓、脸、眼睛、服饰主色），不能是纯色块或只有边框。字符与颜色对照：.透明 K黑(#1b1b1f) W白 R红 O橙 Y黄 G绿 C青 B蓝 P紫 M粉 N棕 S肤色 H深灰 L浅灰。字符只允许这些，不得出现其他字符。示例一行可能是"....KKSSSSKK...."，其中KK是深色头发、SS是肤色。';

// 建卡/补全类 prompt 共用约束：人设质量与"可直接演出的说话风格示例"（写入 speakingStyle，不新增字段）
var CHARACTER_QUALITY_RULE = '人设质量要求：①不得使用陈词滥调的名字或模板化人设（如"艾尔德里亚"式的套路奇幻名、"高冷大小姐""温柔邻家女孩"这类通用模板）；②personality 写具体的行为倾向（遇到事情会怎么做、在意什么），不要堆砌形容词；③background 只写 3-5 条会影响当下互动的要点，不要写编年史。';
var SPEAKING_STYLE_SAMPLES_RULE = 'speakingStyle 必须写成“整体调性描述；示例：<台词1> / <台词2> / <台词3>”：三条示例台词必须是该角色真的会说的口语短句，要体现口头禅、句尾助词、标点习惯与对用户的称呼，三条之间差异明显（能看出是同一个人、但场景不同）；**禁止换行，三条之间只能用 " / " 分隔**，整个字段不超过 200 字。';

// 试验失败品：像素头像生成接口，已弃用（不稳定，输出网格常残缺）。保留仅供历史数据使用。
async function requestPixelAvatar(desc) {
  var response = await callAPI([
    { role: 'system', content: '你是像素头像设计师。只返回JSON：{"avatarPixel":[16个字符串，每个16字符]}。' },
    { role: 'user', content: PIXEL_AVATAR_PROMPT + '\n角色:' + desc }
  ]);
  var data = parseJsonPayload(response.choices[0].message.content);
  if (!isPlainObject(data) || !Array.isArray(data.avatarPixel)) throw new Error('像素头像生成失败');
  var grid = normalizePixelAvatar(data.avatarPixel);
  if (!grid) throw new Error('像素头像格式无效');
  return grid;
}

function extractEmoji(text) {
  if (!text) return '';
  var match = String(text).match(/([\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}\u{2190}-\u{21FF}])/u);
  return match ? match[1] : '';
}

// 自动修复/手动选择的头像生成方式：调用 AI 挑选一个贴切的 emoji 字符（稳定、无渲染负担）
async function requestEmojiAvatar(desc) {
  var response = await callAPI([
    { role: 'system', content: '你是头像设计师。根据角色描述，从常见 emoji 中挑选一个最贴切、最能代表该角色形象的单字符 emoji（例如 🌸 🐱 🌙 ⚡ 🔥 💧 🍀 👑 🎀 ⭐）。只输出这一个 emoji 字符本身，不要任何文字、解释或标点。' },
    { role: 'user', content: '角色:' + desc }
  ]);
  var emoji = extractEmoji(response.choices[0].message.content);
  if (!emoji) throw new Error('模型未返回有效 emoji');
  return emoji;
}

