// ==================== 联网内容获取：直连 → Microlink 摘要 / B站 API ====================
function stripHtmlTags(value) {
  return toText(value)
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/\s+/g, ' ')
    .trim();
}

async function searchWebContent(query) {
  var text = trimText(query, 200);
  if (!text) return JSON.stringify({ ok: false, reason: '缺少搜索关键词 query' });
  var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var timer = controller ? setTimeout(function() { controller.abort(); }, 15000) : null;
  try {
    var res = await fetch('https://www.bing.com/search?format=rss&q=' + encodeURIComponent(text),
      { cache: 'no-store', signal: controller ? controller.signal : undefined });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    var xml = new DOMParser().parseFromString(await res.text(), 'application/xml');
    if (xml.querySelector('parsererror')) throw new Error('搜索响应不是 RSS');
    var results = Array.prototype.slice.call(xml.querySelectorAll('item')).map(function(item) {
      var get = function(tag) { var node = item.querySelector(tag); return node ? node.textContent.trim() : ''; };
      return { title: trimText(get('title'), 160), url: get('link'), description: trimText(get('description'), 400) };
    }).filter(function(item) { return item.title && /^https?:\/\//i.test(item.url); }).slice(0, 6);
    return JSON.stringify({ ok: results.length > 0, query: text, results: results,
      reason: results.length ? undefined : '没有取得搜索结果，不得编造，可请用户提供链接' });
  } catch (error) {
    return JSON.stringify({ ok: false, query: text, reason: '搜索服务不可用：' + (error.message || String(error)) + '。可请用户提供链接，不得声称已搜索成功' });
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function normalizeBiliPic(url) {
  var u = toText(url).trim();
  if (!u) return '';
  if (u.indexOf('//') === 0) return 'https:' + u;
  return u.replace(/^http:\/\//i, 'https://');
}

function extractBiliVideoId(url) {
  var u = toText(url);
  var m = u.match(/\/video\/(BV[0-9A-Za-z]+)/i);
  if (m) return { bvid: m[1] };
  m = u.match(/\/video\/av(\d+)/i);
  if (m) return { aid: m[1] };
  return null;
}

function extractBiliSearchKeyword(url) {
  var m = toText(url).match(/[?&]keyword=([^&#]+)/);
  if (!m) return '';
  try { return decodeURIComponent(m[1]).trim(); } catch (e) { return toText(m[1]).trim(); }
}

async function fetchBiliJson(path, params) {
  var qs = Object.keys(params || {}).map(function(k) {
    return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]);
  }).join('&');
  var url = 'https://api.bilibili.com' + path + (qs ? '?' + qs : '');
  var res = await fetch(url, { method: 'GET', cache: 'no-store', referrer: 'https://www.bilibili.com/' });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  var data = await res.json();
  if (!data || Number(data.code) !== 0) throw new Error(toText(data && data.message, '接口返回异常') + '（code ' + toText(data && data.code) + '）');
  return data.data;
}

async function resolveBiliShortLink(url) {
  try {
    var res = await fetch(url, { method: 'GET', redirect: 'follow', cache: 'no-store' });
    var direct = extractBiliVideoId(res.url || '');
    if (direct) return direct;
    var html = await res.text().catch(function() { return ''; });
    var m = toText(html).match(/(BV[0-9A-Za-z]{10})/);
    return m ? { bvid: m[1] } : null;
  } catch (e) {
    return null;
  }
}

async function biliSearchVideos(keyword) {
  var kw = toText(keyword).trim();
  if (!kw) return JSON.stringify({ ok: false, platform: 'bilibili', reason: '缺少搜索关键词' });
  try {
    var data = await fetchBiliJson('/x/web-interface/search/all/v2', { keyword: kw, page: 1 });
    var sections = Array.isArray(data && data.result) ? data.result : [];
    var videoSection = sections.filter(function(s) { return s && s.result_type === 'video'; })[0];
    var items = Array.isArray(videoSection && videoSection.data) ? videoSection.data : [];
    var results = items.filter(function(it) { return it && toText(it.bvid); }).slice(0, 8).map(function(it) {
      return {
        bvid: toText(it.bvid),
        title: stripHtmlTags(it.title),
        author: toText(it.author),
        play: it.play == null ? 0 : it.play,
        duration: toText(it.duration),
        url: 'https://www.bilibili.com/video/' + toText(it.bvid),
        cover: normalizeBiliPic(it.pic)
      };
    });
    if (results.length === 0) return JSON.stringify({ ok: false, platform: 'bilibili', keyword: kw, reason: '没有搜到相关视频，可换个关键词再试' });
    return JSON.stringify({ ok: true, platform: 'bilibili', keyword: kw, count: results.length, results: results });
  } catch (e) {
    return JSON.stringify({ ok: false, platform: 'bilibili', keyword: kw, reason: 'B站搜索失败：' + (e && e.message ? e.message : String(e)) });
  }
}

async function biliVideoDetail(id) {
  try {
    if (!id) throw new Error('未能识别视频ID');
    var params = id.bvid ? { bvid: id.bvid } : { aid: id.aid };
    var data = await fetchBiliJson('/x/web-interface/view', params);
    if (!data) throw new Error('未返回视频数据');
    var cover = normalizeBiliPic(data.pic);
    var payload = {
      ok: true,
      platform: 'bilibili',
      bvid: toText(data.bvid),
      title: toText(data.title),
      author: toText(data.owner && data.owner.name),
      duration: Number(data.duration) || 0,
      play: Number(data.stat && data.stat.view) || 0,
      like: Number(data.stat && data.stat.like) || 0,
      danmaku: Number(data.stat && data.stat.danmaku) || 0,
      desc: trimText(toText(data.desc), 800),
      cover: cover,
      url: 'https://www.bilibili.com/video/' + toText(data.bvid)
    };
    if (cover) payload.note = '封面图见 cover 字段，可在 reply 中用 ![封面](URL) 展示';
    var text = JSON.stringify(payload);
    if (cover) return [{ type: 'input_text', text: text }, { type: 'input_image', image_url: cover, detail: 'low' }];
    return text;
  } catch (e) {
    return JSON.stringify({ ok: false, platform: 'bilibili', reason: 'B站视频读取失败：' + (e && e.message ? e.message : String(e)) });
  }
}

async function fetchViaMicrolink(url) {
  try {
    var res = await fetch('https://api.microlink.io/?url=' + encodeURIComponent(url), { method: 'GET', cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    var json = await res.json();
    var d = (json && json.data) || null;
    if (!d) throw new Error('未返回摘要数据');
    var image = toText(d.image && d.image.url);
    var payload = {
      ok: true,
      via: 'microlink',
      url: toText(d.url, url),
      title: toText(d.title),
      description: trimText(toText(d.description), 600),
      publisher: toText(d.publisher),
      author: toText(d.author),
      date: toText(d.date),
      image: image
    };
    if (!payload.title && !payload.description && !image) {
      return JSON.stringify({ ok: false, url: url, reason: '无法获取可读内容（直连与摘要服务均失败）' });
    }
    if (image) payload.note = '主图见 image 字段，可在 reply 中用 ![图](URL) 展示';
    var text = JSON.stringify(payload);
    if (image) return [{ type: 'input_text', text: text }, { type: 'input_image', image_url: image, detail: 'low' }];
    return text;
  } catch (e) {
    return JSON.stringify({ ok: false, url: url, reason: '抓取失败（直连与摘要服务均不可用）：' + (e && e.message ? e.message : String(e)) });
  }
}

async function fetchWebContent(url) {
  var looksImage = /\.(png|jpe?g|gif|webp|bmp|svg)(\?|#|$)/i.test(url);
  try {
    var res = await fetch(url, { method: 'GET', redirect: 'follow', cache: 'no-store' });
    var contentType = (res.headers.get('content-type') || '').toLowerCase();
    if (res.ok && contentType.indexOf('image/') === 0) {
      // 由服务端下载该图片并作为图片内容交给模型查看
      return imageToolResult(url);
    }
    if (res.ok) {
      var text = await res.text();
      var readable = htmlToReadableText(text);
      if (readable.length >= 80) {
        return JSON.stringify({ ok: true, url: url, content: trimText(readable, 6000) });
      }
      if (looksImage) return imageToolResult(url);
      return await fetchViaMicrolink(url);
    }
    if (looksImage) return imageToolResult(url);
    return await fetchViaMicrolink(url);
  } catch (e) {
    // 跨域/网络失败：图片交给服务端按图片下载；其余改用摘要服务兜底
    if (looksImage) return imageToolResult(url);
    return await fetchViaMicrolink(url);
  }
}
