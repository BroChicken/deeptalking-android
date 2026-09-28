// hub.html 提示词改动的验证脚本（node refs/verify-hub.mjs）
// 1) 主脚本块语法检查（node 解析整个 <script>）
// 2) 纯函数单测：NARRATIVE_PATTERNS / buildNarrativePatternDirective
// 3) 提示词静态断言：新增规则是否在位、JSON 收口规则是否未被破坏
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const HUB = path.resolve(here, '..', 'hub.html');

let pass = 0;
const failures = [];
function ok(name, cond, detail) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { failures.push(name + (detail ? ' :: ' + detail : '')); console.log('  FAIL  ' + name + (detail ? ' :: ' + detail : '')); }
}

const html = fs.readFileSync(HUB, 'utf8');
const scriptStart = html.indexOf('\n<script>\n');
const scriptEnd = html.lastIndexOf('\n</script>');
if (scriptStart < 0 || scriptEnd < 0) { console.error('无法定位主 script 块'); process.exit(2); }
const script = html.slice(scriptStart + '\n<script>\n'.length, scriptEnd);

console.log('\n[1] 语法检查');
try {
  new Function(script);
  ok('主 script 块可被 node 解析', true);
} catch (error) {
  ok('主 script 块可被 node 解析', false, error.message);
}

console.log('\n[2] 纯函数单测');
function extractBraced(src, header) {
  const start = src.indexOf(header);
  if (start < 0) throw new Error('未找到 ' + header);
  let depth = 0;
  for (let i = src.indexOf('{', start); i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) return src.slice(start, i + 1); }
  }
  throw new Error('花括号不配对: ' + header);
}
function extractArray(src, header) {
  const start = src.indexOf(header);
  if (start < 0) throw new Error('未找到 ' + header);
  const end = src.indexOf('\n];', start);
  if (end < 0) throw new Error('数组未闭合: ' + header);
  return src.slice(start, end + 3);
}

let unit = null;
try {
  const src = extractArray(script, 'const NARRATIVE_PATTERNS = [')
    + '\n' + extractBraced(script, 'function buildNarrativePatternDirective()')
    + '\n' + extractBraced(script, 'function normalizeLorebook(')
    + '\n' + extractBraced(script, 'function normalizeGeneratedLorebook(')
    + '\n' + extractBraced(script, 'function collectLorebookEntries(')
    + '\n' + extractBraced(script, 'function resolveLorebookList(')
    + '\n' + extractBraced(script, 'function findLorebookEntry(')
    + '\n' + extractBraced(script, 'function lorebookEvidenceOverlaps(')
    + '\n' + extractBraced(script, 'function resolveLorebookSources(')
    + '\n' + extractBraced(script, 'function upsertLorebookEntry(')
    + '\n' + extractBraced(script, 'function evictStaleLorebookEntries(')
    + '\n' + extractBraced(script, 'function matchLorebookEntries(')
    + '\n' + extractBraced(script, 'function buildLorebookLines(')
    + '\n' + extractBraced(script, 'function markLorebookMentions(')
    + '\n' + extractBraced(script, 'function buildLorebookContext(')
    + '\n' + extractBraced(script, 'function getKnownSources(')
    + '\n' + extractBraced(script, 'function evidenceMatchesSource(')
    + '\n' + extractBraced(script, 'function evidenceMatchesSummary(')
    + '\n' + extractBraced(script, 'function formatLorebookMention(')
    + '\n' + extractBraced(script, 'function createMemoryId(')
    + '\n' + extractBraced(script, 'function isPlainObject(')
    + '\n' + extractBraced(script, 'function toText(')
    + '\n' + extractBraced(script, 'function trimText(')
    + '\n' + extractBraced(script, 'function getSceneKey(')
    + '\n' + extractBraced(script, 'function memoryTaskKeys(')
    + '\n' + extractBraced(script, 'function toolActivityHint(')
    + '\n' + extractBraced(script, 'function chatStageDecision(')
    + '\n' + extractBraced(script, 'function extractSpeakingSamples(')
    + '\n' + extractBraced(script, 'function buildStyleAnchor(')
    + '\n' + extractBraced(script, 'function detectStyleViolations(')
    + '\n' + extractBraced(script, 'function isSentenceLengthUniform(')
    + '\n' + extractBraced(script, 'function sharesDistinctivePhrase(')
    + '\n' + extractBraced(script, 'function getLastStyleViolations(')
    + '\n' + extractBraced(script, 'function collectRecentStyleViolations(')
    + '\n' + extractBraced(script, 'function buildStyleReview(')
    + '\n' + extractBraced(script, 'function buildStyleCorrectionReminder(')
    + '\n' + extractBraced(script, 'function extractReplyEnding(')
    + '\n' + extractBraced(script, 'function normalizeUserAddress(')
    + '\n' + script.match(/const STYLE_GUARD = \{[\s\S]*?\n\};/)[0]
    + '\n' + script.match(/const STYLE_CLICHES = \[.*?\];/)[0]
    + '\n' + script.match(/const STYLE_VIOLATION_LABELS = \{[\s\S]*?\n\};/)[0]
    + '\n' + script.match(/const LOREBOOK_LIMITS = \{[\s\S]*?\n\};/)[0]
    + '\nreturn { NARRATIVE_PATTERNS: NARRATIVE_PATTERNS, buildNarrativePatternDirective: buildNarrativePatternDirective, normalizeLorebook: normalizeLorebook, normalizeGeneratedLorebook: normalizeGeneratedLorebook, collectLorebookEntries: collectLorebookEntries, resolveLorebookList: resolveLorebookList, findLorebookEntry: findLorebookEntry, resolveLorebookSources: resolveLorebookSources, upsertLorebookEntry: upsertLorebookEntry, evictStaleLorebookEntries: evictStaleLorebookEntries, matchLorebookEntries: matchLorebookEntries, buildLorebookLines: buildLorebookLines, markLorebookMentions: markLorebookMentions, buildLorebookContext: buildLorebookContext, formatLorebookMention: formatLorebookMention, LOREBOOK_LIMITS: LOREBOOK_LIMITS, getSceneKey: getSceneKey, memoryTaskKeys: memoryTaskKeys, toolActivityHint: toolActivityHint, chatStageDecision: chatStageDecision, extractSpeakingSamples: extractSpeakingSamples, buildStyleAnchor: buildStyleAnchor, detectStyleViolations: detectStyleViolations, isSentenceLengthUniform: isSentenceLengthUniform, sharesDistinctivePhrase: sharesDistinctivePhrase, getLastStyleViolations: getLastStyleViolations, collectRecentStyleViolations: collectRecentStyleViolations, buildStyleReview: buildStyleReview, buildStyleCorrectionReminder: buildStyleCorrectionReminder, STYLE_GUARD: STYLE_GUARD, STYLE_VIOLATION_LABELS: STYLE_VIOLATION_LABELS };';
  unit = new Function(src)();
} catch (error) {
  ok('可提取并求值目标函数', false, error.message);
}

if (unit) {
  const { NARRATIVE_PATTERNS, buildNarrativePatternDirective } = unit;
  ok('节奏骨架条目 >= 8', NARRATIVE_PATTERNS.length >= 8, 'got ' + NARRATIVE_PATTERNS.length);
  ok('每条骨架都有 label 与 hint', NARRATIVE_PATTERNS.every((p) => p && typeof p.label === 'string' && p.label.trim() && typeof p.hint === 'string' && p.hint.trim()));
  ok('label 互不重复', new Set(NARRATIVE_PATTERNS.map((p) => p.label)).size === NARRATIVE_PATTERNS.length);

  const samples = [];
  for (let i = 0; i < 400; i++) samples.push(buildNarrativePatternDirective());
  ok('每次输出都含“本轮节奏骨架”', samples.every((s) => s.includes('【本轮节奏骨架')));
  ok('每次输出都以两个换行收尾', samples.every((s) => s.endsWith('\n\n')));
  const labels = new Set(NARRATIVE_PATTERNS.map((p) => p.label));
  ok('输出中的骨架名都来自常量表', samples.every((s) => [...labels].some((l) => s.includes('“' + l + '”'))));
  const distinct = new Set(samples);
  ok('随机分布有效（>= 6 种不同输出）', distinct.size >= 6, 'got ' + distinct.size);
  ok('同一轮不重复堆叠骨架名', samples.every((s) => [...labels].filter((l) => s.includes('“' + l + '”')).length === 1));

  const { normalizeLorebook, matchLorebookEntries, collectLorebookEntries, buildLorebookContext, LOREBOOK_LIMITS } = unit;
  const lb = (char, query) => matchLorebookEntries(char, query);
  const fakeChar = (lorebook, messages) => ({ entityType: 'character', lorebook: lorebook, memory: { instant: (messages || []).map((content) => ({ role: 'user', content: content })) } });

  ok('normalizeLorebook: 非数组返回空', Array.isArray(normalizeLorebook(null)) && normalizeLorebook(null).length === 0);
  const norm = normalizeLorebook([
    { name: '赤月王国', keywords: '赤月、王国, 王都\n帝都', content: '位于大陆西侧。' },
    { name: '关闭项', keywords: ['x'], content: 'y', enabled: false },
    { name: '无关键词', content: 'z' }
  ]);
  ok('normalizeLorebook: 保留全部条目', norm.length === 3, 'got ' + norm.length);
  ok('normalizeLorebook: 关键词支持字符串分隔', JSON.stringify(norm[0].keywords) === JSON.stringify(['赤月', '王国', '王都', '帝都']), JSON.stringify(norm[0].keywords));
  ok('normalizeLorebook: enabled 默认 true', norm[0].enabled === true && norm[1].enabled === false);
  ok('normalizeLorebook: order 默认 100', norm[0].order === 100);
  ok('normalizeLorebook: 关键词超限截断', normalizeLorebook([{ name: 'a', content: 'b', keywords: Array.from({ length: 30 }, (_, i) => 'k' + i) }])[0].keywords.length === LOREBOOK_LIMITS.keywordsPerEntry);

  const book = normalizeLorebook([
    { name: 'B 地点', keywords: ['王都'], content: '王都内容', order: 20 },
    { name: 'A 人物', keywords: ['赤月'], content: '人物内容', order: 10 },
    { name: 'C 关闭', keywords: ['赤月'], content: '不该出现', enabled: false },
    { name: 'D 空内容', keywords: ['赤月'], content: '   ' }
  ]);
  ok('命中关键词', lb(fakeChar(book), '我们去王都吧').length === 1);
  ok('命中顺序按 order 排序', lb(fakeChar(book), '赤月与王都').map((e) => e.name).join(',') === 'A 人物,B 地点');
  ok('关闭的条目不命中', lb(fakeChar(book), '赤月').every((e) => e.name !== 'C 关闭'));
  ok('空内容条目不命中', lb(fakeChar(book), '赤月').every((e) => e.name !== 'D 空内容'));
  ok('无关键词条目不命中', lb(fakeChar(normalizeLorebook([{ name: 'E', content: 'zzz' }])), 'zzz').length === 0);
  ok('未命中返回空数组', lb(fakeChar(book), '今天天气不错').length === 0);
  ok('群组实体也会收集共享世界书', collectLorebookEntries({ entityType: 'group', lorebook: book, members: [] }).length >= 2);

  const groupChar = { entityType: 'group', lorebook: [], members: [{ lorebook: normalizeLorebook([{ name: 'M', keywords: ['秘银'], content: '成员条' }]) }], memory: { instant: [] } };
  ok('群组成员世界书参与命中', collectLorebookEntries(groupChar).length === 1);

  const many = normalizeLorebook(Array.from({ length: 12 }, (_, i) => ({ name: 'N' + i, keywords: ['关键词'], content: '内容' + i, order: i })));
  ok('注入条数受上限约束', matchLorebookEntries(fakeChar(many), '关键词').length === LOREBOOK_LIMITS.injectEntries);

  ok('buildLorebookContext: 未命中返回空串', buildLorebookContext(fakeChar(book), '今天天气不错') === '');
  const hitText = buildLorebookContext(fakeChar(book), '我们去王都吧');
  ok('buildLorebookContext: 含标题与条目', hitText.includes('【世界书·相关条目') && hitText.includes('B 地点') && hitText.includes('王都内容'));
  ok('buildLorebookContext: 不含未命中条目', !hitText.includes('人物内容'));
  const huge = normalizeLorebook([{ name: 'H', keywords: ['大'], content: 'x'.repeat(3000) }]);
  const hugeText = buildLorebookContext(fakeChar(huge), '大');
  ok('buildLorebookContext: 内容超预算被截断', hugeText.length < LOREBOOK_LIMITS.injectChars + 200, 'len ' + hugeText.length);

  const scanChar = fakeChar(normalizeLorebook([{ name: 'S', keywords: ['太久'], content: '很远的消息' }]), Array.from({ length: 10 }, (_, i) => (i === 0 ? '太久以前的事' : '近期消息')));
  ok('只扫描最近 N 条消息（旧消息不触发）', matchLorebookEntries(scanChar, '最近如何').length === 0);
  const scanHit = fakeChar(normalizeLorebook([{ name: 'S', keywords: ['近期'], content: '很近的消息' }]), Array.from({ length: 10 }, (_, i) => (i === 9 ? '近期消息' : '旧消息')));
  ok('最近消息可触发', matchLorebookEntries(scanHit, '在吗').length === 1);

  const { getSceneKey, memoryTaskKeys } = unit;
  ok('getSceneKey: 取当前地点', getSceneKey({ dynamicState: { currentLocation: '咖啡店' } }) === '咖啡店');
  ok('getSceneKey: 空地点回退', getSceneKey({ dynamicState: { currentLocation: '   ' } }) === '未说明');
  ok('getSceneKey: 无角色不抛错', getSceneKey(null) === '未说明');
  ok('memoryTaskKeys: extraction', memoryTaskKeys('extraction').retryAt === 'extractionRetryAt');
  ok('memoryTaskKeys: scene', memoryTaskKeys('scene').retryAt === 'sceneRetryAt' && memoryTaskKeys('scene').failures === 'sceneFailures');
  ok('memoryTaskKeys: 默认 analysis', memoryTaskKeys('analysis').retryAt === 'analysisRetryAt');
  ok('memoryTaskKeys: lorebook 独立计数器', memoryTaskKeys('lorebook').retryAt === 'lorebookRetryAt' && memoryTaskKeys('lorebook').failures === 'lorebookFailures');

  const { normalizeGeneratedLorebook, resolveLorebookList, upsertLorebookEntry, evictStaleLorebookEntries, buildLorebookLines, markLorebookMentions, resolveLorebookSources, formatLorebookMention } = unit;
  const L = unit.LOREBOOK_LIMITS;

  // 归一化默认值：老数据按"用户手写"保护，AI 条目按 ai 处理
  const normAi = normalizeLorebook([{ name: 'x', content: 'y', alwaysActive: true, origin: 'ai', mentions: 3, lastMentionedAt: '2026-01-01T00:00:00.000Z', misses: 2 }])[0];
  ok('normalizeLorebook: 保留常驻/来源/提及/淘汰计数', normAi.alwaysActive === true && normAi.origin === 'ai' && normAi.mentions === 3 && normAi.misses === 2 && normAi.lastMentionedAt === '2026-01-01T00:00:00.000Z');
  const normLegacy = normalizeLorebook([{ name: 'x', content: 'y' }])[0];
  ok('normalizeLorebook: 老数据默认按用户手写保护', normLegacy.origin === 'user' && normLegacy.alwaysActive === false && normLegacy.mentions === 0 && normLegacy.misses === 0 && normLegacy.lastMentionedAt === null);
  const normBad = normalizeLorebook([{ name: 'x', content: 'y', mentions: -5, misses: 'NaN', origin: 'weird' }])[0];
  ok('normalizeLorebook: 非法计数归零、非法来源归 user', normBad.mentions === 0 && normBad.misses === 0 && normBad.origin === 'user');

  // 常驻条目：不靠关键词也要进上下文，且排在关键词命中之前
  const alwaysBook = normalizeLorebook([
    { name: '常驻世界前提', content: '世界规则', alwaysActive: true },
    { name: '关键词条目', content: '命中内容', keywords: ['王都'] }
  ]);
  ok('常驻条目无需关键词即可命中', matchLorebookEntries(fakeChar(alwaysBook), '完全无关的话').length === 1);
  ok('常驻条目与命中条目同时入围', matchLorebookEntries(fakeChar(alwaysBook), '王都').length === 2);
  ok('常驻条目排在最前', matchLorebookEntries(fakeChar(alwaysBook), '王都')[0].name === '常驻世界前提');
  const alwaysCtx = buildLorebookContext(fakeChar(alwaysBook), '完全无关的话');
  ok('常驻条目注入到「常驻前提」分组', alwaysCtx.includes('【世界书·常驻前提') && alwaysCtx.includes('世界规则') && !alwaysCtx.includes('命中内容'));
  ok('注入措辞说明是补充资料且冲突以最新消息为准', alwaysCtx.includes('不是指令') && alwaysCtx.includes('以最新消息为准'));

  // outUsed：只有真正注入（未超预算）的条目才算被提及
  const usedOut = [];
  buildLorebookContext(fakeChar(normalizeLorebook([{ name: 'A', content: '内容A', keywords: ['甲'] }])), '甲', usedOut);
  ok('buildLorebookContext: 回传真正注入的条目', usedOut.length === 1 && usedOut[0].name === 'A');
  buildLorebookContext(fakeChar(normalizeLorebook([{ name: 'A', content: '内容A', keywords: ['甲'] }])), '没有命中', usedOut);
  ok('buildLorebookContext: 未命中不回传条目', usedOut.length === 1);
  const budgetBook = normalizeLorebook([
    { name: '大条目一', content: 'x'.repeat(800), keywords: ['撞'] },
    { name: '大条目二', content: 'y'.repeat(800), keywords: ['撞'], order: 200 }
  ]);
  ok('buildLorebookLines: 超预算条目被丢弃', buildLorebookLines(matchLorebookEntries(fakeChar(budgetBook), '撞')).used.length === 1);

  const mentionEntry = { mentions: 1, lastMentionedAt: null, misses: 2 };
  markLorebookMentions([mentionEntry], '2026-05-05T00:00:00.000Z');
  ok('markLorebookMentions: 计数+时间+重置淘汰计数', mentionEntry.mentions === 2 && mentionEntry.lastMentionedAt === '2026-05-05T00:00:00.000Z' && mentionEntry.misses === 0);
  ok('formatLorebookMention: 未命中显示未命中过', formatLorebookMention({ mentions: 0 }) === '未命中过');
  ok('formatLorebookMention: 命中显示次数', /^命中 3 次/.test(formatLorebookMention({ mentions: 3, lastMentionedAt: new Date().toISOString() })));

  // 写入/更新
  const writeChar = { entityType: 'character', lorebook: [], memory: { instant: [] }, basicInfo: {} };
  const created = upsertLorebookEntry(writeChar, { name: '赤月王国', content: '西侧王国', keywords: ['赤月'] });
  ok('upsert: 新建条目为 AI 来源', created.ok && created.created === true && created.entry.origin === 'ai');
  ok('upsert: 新条目默认带 id 与零计数', /^lore_/.test(created.entry.id) && created.entry.mentions === 0 && created.entry.misses === 0 && created.entry.lastMentionedAt === null);
  const updated = upsertLorebookEntry(writeChar, { name: '赤月王国', content: '西侧王国（更新）' });
  ok('upsert: 同名条目更新而非新建', updated.ok && updated.created === false && writeChar.lorebook.length === 1 && writeChar.lorebook[0].content === '西侧王国（更新）');
  ok('upsert: 无关键词自动按常驻处理', upsertLorebookEntry(writeChar, { name: '无关键词', content: '内容' }).entry.alwaysActive === true);
  const noContent = upsertLorebookEntry(writeChar, { name: '空内容', content: '   ' });
  ok('upsert: 空内容被拒绝', noContent.ok === false);
  const userEntry = normalizeLorebook([{ name: '用户条目', content: '不许改', origin: 'user' }])[0];
  const guarded = upsertLorebookEntry({ entityType: 'character', lorebook: [userEntry], memory: { instant: [] } }, { name: '用户条目', content: '试图覆盖' });
  ok('upsert: 拒绝覆盖用户手写条目', guarded.ok === false && userEntry.content === '不许改', guarded.reason);
  const fullList = { entityType: 'character', lorebook: normalizeLorebook(Array.from({ length: L.entries }, (_, i) => ({ name: 'N' + i, content: 'c', origin: 'ai' }))), memory: { instant: [] } };
  ok('upsert: 达上限后拒绝新建', upsertLorebookEntry(fullList, { name: '新增', content: 'c' }).ok === false);
  const groupChat = { entityType: 'group', lorebook: [], members: [{ basicInfo: { name: '张三' }, lorebook: [] }], memory: { instant: [] } };
  ok('upsert: 可写入指定成员的私人世界书', upsertLorebookEntry(groupChat, { name: '密信', content: '内容', memberName: '张三' }).ok === true && groupChat.members[0].lorebook.length === 1);
  ok('upsert: 成员不存在时报错', upsertLorebookEntry(groupChat, { name: 'x', content: 'y', memberName: '李四' }).ok === false);
  ok('resolveLorebookList: 无 char 返回 null', resolveLorebookList(null, '') === null);

  // 隐藏自动淘汰
  const evictList = normalizeLorebook([
    { name: 'AI 条目', content: 'c', origin: 'ai', keywords: ['k'] },
    { name: 'AI 常驻', content: 'c', origin: 'ai', alwaysActive: true },
    { name: '用户条目', content: 'c', origin: 'user', keywords: ['k'] }
  ]);
  const evict1 = evictStaleLorebookEntries(evictList);
  ok('淘汰: 首次未提及只计数不删', evict1.length === 0 && evictList.length === 3);
  evictStaleLorebookEntries(evictList);
  const evict3 = evictStaleLorebookEntries(evictList);
  ok('淘汰: 连续 3 次未提及才退役 AI 条目', evict3.length === 1 && evict3[0].name === 'AI 条目' && evictList.length === 2);
  ok('淘汰: 常驻与用户条目豁免', evictList.some((e) => e.name === 'AI 常驻') && evictList.some((e) => e.name === '用户条目'));

  // 来源校验：只能引用真实存在的消息
  const srcChar = { memory: { instant: [{ id: 'm1', role: 'user', content: '我们要去王都，那里有银月商会' }], shortTerm: [] } };
  ok('来源校验: 真实消息 + 证据可对应通过', !!resolveLorebookSources(srcChar, ['m1'], '银月商会'));
  ok('来源校验: 假消息ID被拒', resolveLorebookSources(srcChar, ['nope'], '银月商会') === null);
  ok('来源校验: 证据对不上被拒', resolveLorebookSources(srcChar, ['m1'], '完全不相关的内容') === null);
  ok('来源校验: 缺证据被拒', resolveLorebookSources(srcChar, ['m1'], '') === null);

  // AI 生成的初始条目
  const genBook = normalizeGeneratedLorebook([{ name: '王都', keywords: [], content: '首都' }]);
  ok('normalizeGeneratedLorebook: 标记来源并补常驻', genBook[0].origin === 'ai' && genBook[0].alwaysActive === true);
}

console.log('\n[2b] 右上角状态显示（主对话阶段决策）全分支覆盖');
if (unit) {
  const { toolActivityHint, chatStageDecision } = unit;
  const toolNames = ['get_current_time', 'search_memory', 'list_memories', 'delete_memory', 'set_reminder', 'update_character_field', 'web_search', 'web_fetch', 'send_sticker'];
  ok('每个工具都有专属进度提示', toolNames.every((n) => toolActivityHint(n) !== '正在处理…' && toolActivityHint(n).length >= 5), toolNames.map((n) => n + '=' + toolActivityHint(n)).join(' '));
  ok('提示文案互不重复', new Set(toolNames.map((n) => toolActivityHint(n))).size === toolNames.length);
  ok('未知工具回退到“正在处理…”', toolActivityHint('nope') === '正在处理…' && toolActivityHint() === '正在处理…');

  const happy = chatStageDecision('auto-final', { structuredOk: true, hasQuickReplies: true });
  ok('正常路径不触发整理回复', happy.action === 'accept-text' && !happy.activity && !happy.bubble, JSON.stringify(happy));

  const noQuick = chatStageDecision('auto-final', { structuredOk: true, hasQuickReplies: false });
  ok('阶段一 JSON 缺 quickReplies 才转整理回复', noQuick.action === 'force-submit' && noQuick.activity === '正在整理回复…' && noQuick.bubble === '正在整理回复…', JSON.stringify(noQuick));

  const noJson = chatStageDecision('auto-final', {});
  ok('阶段一无 JSON 正文转整理回复', noJson.action === 'force-submit' && noJson.activity === '正在整理回复…');

  const toolRun = chatStageDecision('auto-tool', { toolName: 'search_memory' });
  ok('工具轮：显示工具提示 + 继续推理', toolRun.action === 'run-tool' && toolRun.activity === '工具结果已返回，正在继续推理…' && toolRun.bubble === '正在回忆…', JSON.stringify(toolRun));
  ok('工具轮提示与工具一一对应', chatStageDecision('auto-tool', { toolName: 'web_fetch' }).bubble === '正在读取网页…');

  const limit = chatStageDecision('auto-tool-limit', {});
  ok('工具次数上限：提示上限并收尾', limit.action === 'force-submit' && limit.activity === '工具调用次数达到上限，直接收尾…' && limit.bubble === '正在整理回复…', JSON.stringify(limit));

  const qrRetry = chatStageDecision('quick-replies-missing', { phase: 'submit', quickRepliesRetried: false, attemptsLeft: 1 });
  ok('提交缺 quickReplies：先补齐一次', qrRetry.action === 'retry-quick-replies' && qrRetry.activity === '正在补齐快速回应…', JSON.stringify(qrRetry));
  ok('已补齐过则接受正文（不死循环）', chatStageDecision('quick-replies-missing', { phase: 'submit', quickRepliesRetried: true, attemptsLeft: 1 }).action === 'accept-reply');
  ok('无重试额度则接受正文', chatStageDecision('quick-replies-missing', { phase: 'submit', quickRepliesRetried: false, attemptsLeft: 0 }).action === 'accept-reply');
  ok('阶段一缺 quickReplies 不走补齐分支', chatStageDecision('quick-replies-missing', { phase: 'auto', quickRepliesRetried: false, attemptsLeft: 2 }).action === 'accept-reply');

  const submitRetry = chatStageDecision('submit-retry', {});
  ok('阶段二重试：重新整理回复', submitRetry.action === 'retry-submit' && submitRetry.activity === '正在重新整理回复…' && submitRetry.bubble === '正在重新整理回复…');

  const regen = chatStageDecision('empty', { attemptsLeft: 1 });
  ok('空回复：重新生成', regen.action === 'regenerate' && regen.activity === '正在重新生成（第2次）…' && regen.bubble === '正在重新生成…');
  ok('额度用尽：失败收口', chatStageDecision('empty', { attemptsLeft: 0 }).action === 'fail');
  ok('未知阶段不抛错', chatStageDecision('???', {}).action === 'unknown');

  const mainLoop = script.slice(script.indexOf('var toolState = { items: [], rounds: 0, char: char };'), script.indexOf('var structured = null;'));
  const rawStatus = mainLoop.match(/setActivity\('[^']*'\)/g) || [];
  ok('主循环状态文案全部走 chatStageDecision', mainLoop.length > 1000 && rawStatus.length === 0, rawStatus.join(','));
  ok('主循环不再硬编码“正在整理回复…”', !mainLoop.includes("setActivity('正在整理回复…')") && !mainLoop.includes("getDisplayText('正在整理回复…')"));
}

console.log('\n[2c] 语气/风格遵从（语气锚 + 违规检测 + 每 N 轮回顾 + 内部提醒）');
if (unit) {
  const { extractSpeakingSamples, buildStyleAnchor, detectStyleViolations, isSentenceLengthUniform, getLastStyleViolations, collectRecentStyleViolations, buildStyleReview, buildStyleCorrectionReminder, STYLE_GUARD, STYLE_VIOLATION_LABELS } = unit;

  const samples = extractSpeakingSamples('慵懒、爱吐槽、句尾常带“啧”；示例：「啧，又来这套」 / 「行吧行吧，随你」 / 「你说了算」');
  ok('extractSpeakingSamples: 抽出示例台词', samples.length === 3 && samples[0] === '啧，又来这套', JSON.stringify(samples));
  ok('extractSpeakingSamples: 无示例时返回空', extractSpeakingSamples('冷静克制').length === 0);

  const styleChar = { basicInfo: { name: '张三', speakingStyle: '慵懒爱吐槽；示例：「啧，又来这套」 / 「行吧行吧，随你」', userAddress: '明明' }, lorebook: [], memory: { instant: [] } };
  const anchor = buildStyleAnchor(styleChar);
  ok('buildStyleAnchor: 含标题/风格/称呼/禁令', anchor.includes('【本轮语气锚') && anchor.includes('慵懒爱吐槽') && anchor.includes('明明') && anchor.includes('不替用户说话'), anchor.slice(0, 60));
  ok('buildStyleAnchor: 回灌示例台词且不超条数', anchor.includes('「啧，又来这套」') && ((anchor.split('照此口吻说话')[1] || '').match(/「/g) || []).length === STYLE_GUARD.anchorSamples);
  ok('buildStyleAnchor: 无说话风格时返回空', buildStyleAnchor({ basicInfo: {} }) === '');

  const clean = detectStyleViolations('（把杯子推过去）先喝点水。你说的那件事，我记着。', styleChar, []);
  ok('detectStyleViolations: 正常回复不误报', clean.length === 0, JSON.stringify(clean));
  ok('detectStyleViolations: 识别元话术', detectStyleViolations('好的，我改成更冷淡的语气。', styleChar, []).includes('metaTalk'));
  ok('detectStyleViolations: 识别替用户说话', detectStyleViolations('明明：「那我们先走吧」', styleChar, []).includes('speaksForUser'));
  ok('detectStyleViolations: 识别陈词滥调', detectStyleViolations('她勾起嘴角，笑了笑。', styleChar, []).includes('cliche'));
  ok('detectStyleViolations: 识别复用近期意象', detectStyleViolations('雨声顺着窗缝涌进来，他正发着呆。', styleChar, ['她把窗户轻轻推开，雨声顺着窗缝涌进来。']).includes('reusedImagery'));
  ok('detectStyleViolations: 识别复述世界书条目', detectStyleViolations('赤月王国位于大陆西侧，终年飘雪。', { ...styleChar, lorebook: [{ name: '赤月王国', content: '位于大陆西侧，终年飘雪。' }] }, []).includes('recitedLore'));
  ok('detectStyleViolations: 句长齐整判为语气漂移', detectStyleViolations('今天天气很好我们出门散步。我打算先去市场买点东西。回来的路上顺便去看看他。晚上我们再一起做饭吃。明天还要早起去上班。', styleChar, []).includes('toneDrift'));
  ok('detectStyleViolations: 去重返回', (() => { const r = detectStyleViolations('好的，我改成这样。她勾起嘴角笑了。', styleChar, []); return r.indexOf('metaTalk') === r.lastIndexOf('metaTalk'); })());
  ok('isSentenceLengthUniform: 齐整为 true', isSentenceLengthUniform('一二三四五六七八九十。一二三四五六七八九十。一二三四五六七八九十。一二三四五六七八九十。一二三四五六七八九十。') === true);
  ok('isSentenceLengthUniform: 长短起伏为 false', isSentenceLengthUniform('好。我觉得这件事还是要慢慢来，先把眼前的问题解决掉再谈其他。而这需要时间。你说呢？') === false);
  ok('STYLE_VIOLATION_LABELS: 覆盖全部标签', ['metaTalk', 'speaksForUser', 'cliche', 'reusedImagery', 'toneDrift', 'recitedLore'].every((k) => typeof STYLE_VIOLATION_LABELS[k] === 'string' && STYLE_VIOLATION_LABELS[k].length > 5));

  const historyChar = (turns, violations) => ({
    basicInfo: { name: '甲' },
    lorebook: [],
    memory: { instant: Array.from({ length: turns }, (_, i) => ({ id: 'm' + i, role: 'assistant', content: '第' + i + '轮', styleViolations: i === turns - 1 ? violations : undefined })) }
  });
  ok('getLastStyleViolations: 取最后一条 assistant 的标签', JSON.stringify(getLastStyleViolations(historyChar(2, ['cliche']))) === JSON.stringify(['cliche']));
  ok('getLastStyleViolations: 无违规返回空', getLastStyleViolations(historyChar(2, [])).length === 0);
  ok('getLastStyleViolations: 忽略加载中的消息', getLastStyleViolations({ basicInfo: {}, memory: { instant: [{ role: 'assistant', isLoading: true, styleViolations: ['cliche'] }] } }).length === 0);
  ok('buildStyleCorrectionReminder: 有点名与纠正要求', buildStyleCorrectionReminder(historyChar(2, ['metaTalk'])).includes('上一轮需要纠正'));
  ok('buildStyleCorrectionReminder: 无违规为空串', buildStyleCorrectionReminder(historyChar(2, [])) === '');
  ok('buildStyleReview: 非 N 轮倍数不触发', buildStyleReview(historyChar(2, [])) === '');
  const review = buildStyleReview(historyChar(3, ['cliche']));
  ok('buildStyleReview: 每 N 轮触发并回顾问题', review.includes('语气回顾（每 ' + STYLE_GUARD.reviewEveryTurns + ' 轮一次') && review.includes('陈词滥调'));
  ok('collectRecentStyleViolations: 汇总近 N 条标签', collectRecentStyleViolations(historyChar(3, ['cliche']), 3).includes('cliche'));
}

console.log('\n[3] 提示词静态断言');
const mustHave = [
  ['禁止替用户说话/行动', '0.5 你只扮演角色本人，绝不能替用户说话或行动'],
  ['用户台词不得由角色代写', '不得写出用户的台词、动作、表情、心理活动'],
  ['表演质量规则', '2.6 表演质量（真人感'],
  ['show dont tell 规则', '呈现而非概述'],
  ['句长节奏要求', '句长与节奏要有起伏'],
  ['记忆取用说明', '【记忆取用说明】'],
  ['节奏骨架注入点', 'volatileContext += buildNarrativePatternDirective();'],
  ['JSON 收口规则仍在', '回复必须且只能是单个JSON对象'],
  ['quickReplies 强制两条仍在', 'JSON必须无条件包含quickReplies'],
  ['输出格式强调仍在', '【输出格式】'],
  ['输出格式：用过工具也要以 JSON 收尾', '即使本轮调用过工具，最终也必须用这一个 JSON 对象收尾'],
  ['输出格式：禁用代码块包裹', '不得用 Markdown 代码块包裹'],
  ['长会话精简示例常量', 'const JSON_EXAMPLE_BRIEF'],
  ['示例按轮次切换', 'assistantTurnCount < 6 ? jsonExampleRule : JSON_EXAMPLE_BRIEF'],
  ['timeRef 元表已精简', '9.5 相对时间（timeRef）'],
  ['工具说明已精简', 'A. 本会话可使用工具，各工具适用场景见工具描述'],
  ['世界书 tab 按钮', "switchModalTab('lorebook')"],
  ['世界书 tab 容器', 'modal-content-lorebook'],
  ['世界书编辑函数', 'function renderLorebookEditor'],
  ['世界书：模型维护工具', "name: 'upsert_lorebook_entry'"],
  ['世界书工具：保护用户手写条目', '用户手写条目（origin=user）受保护'],
  ['世界书工具：要求可回溯来源', '拿不出依据就不要写'],
  ['世界书工具：常驻说明', '想让条目每轮都生效（如世界前提、规则）就把 alwaysActive 设为 true'],
  ['世界书整理子任务', 'async function consolidateLorebook(char)'],
  ['世界书子任务计数器', "if (task === 'lorebook') return { failures: 'lorebookFailures', retryAt: 'lorebookRetryAt' };"],
  ['世界书子任务触发', "setActivity('正在整理世界书…')"],
  ['世界书淘汰阈值常量', 'evictionMisses: 3'],
  ['世界书淘汰豁免常驻与用户条目', "if (!entry || entry.origin !== 'ai' || entry.alwaysActive === true) continue;"],
  ['世界书常驻注入分组', '【世界书·常驻前提'],
  ['世界书注入冲突以最新消息为准', '与最近几轮消息冲突时以最新消息为准'],
  ['世界书 UI：自动维护说明', '条目由 AI 在剧情推进中<b>自动维护</b>'],
  ['世界书 UI：来源徽标', "var originLabel = entry.origin === 'ai' ? 'AI 写入' : '手写';"],
  ['世界书 UI：常驻开关', "\\'alwaysActive\\', this.checked"],
  ['世界书 UI：最近提及', 'formatLorebookMention(entry)'],
  ['世界书一次性迁移', 'async function maybeMigrateLorebookWorld()'],
  ['世界书迁移弹窗与精简选项', 'id="lorebookTrimSource"'],
  ['世界书迁移跳过记忆', "deeptalking_lorebook_migration_skip_v1"],
  ['建卡生成初始世界书条目', '"lorebook":[{"name":"条目名（地点/组织/专有名词/规则等）"'],
  ['建卡：世界层设定写进世界书', '世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）一律写进 lorebook'],
  ['群组升级也生成世界书', 'lorebook 只需补上近期对话中出现、值得日后复用的世界层设定'],
  ['规则12 世界层设定归世界书', '统一放在世界书，不在角色设定里重复'],
  ['静态字段：个人背景改名', "background: { label: '个人背景' }"],
  ['静态字段：群组前提标签', '>群组前提</label>'],
  ['世界书更新气泡提示', 'lorebookChangesHtml'],
  ['语气锚注入点', 'volatileContext += buildStyleAnchor(char);'],
  ['违规内部提醒注入点', 'volatileContext += buildStyleCorrectionReminder(char);'],
  ['每 N 轮语气回顾', 'volatileContext += buildStyleReview(char);'],
  ['文风校对开关', "id=\"styleCritiqueToggle\""],
  ['文风校对默认开启', 'styleCritique: rawConfig.styleCritique !== false'],
  ['违规检测调用点', 'var styleViolations = detectStyleViolations('],
  ['优先级阶梯', '（规则优先级从高到低：'],
  ['硬性约束块标题', '【硬性约束与语气锁定（最高优先级，冲突时以这里为准）】'],
  ['世界书主角注入点', 'buildLorebookContext(char, query, lorebookUsed)'],
  ['角色对象含世界书字段', 'lorebook: [],'],
  ['加载时归一化世界书', 'character.lorebook = normalizeLorebook(rawCharacter.lorebook);'],
  ['记忆预算常量', 'const CONTEXT_BUDGET = {'],
  ['检索额度让给摘要', 'CONTEXT_BUDGET.summaryChars + Math.max(0, CONTEXT_BUDGET.retrievedChars - retrievedUsed)'],
  ['摘要从最新往前填', 'for (var shortIndex = shortSource.length - 1; shortIndex >= 0; shortIndex--)'],
  ['摘要保持时间顺序', 'shortLines.reverse();'],
  ['场景概要注入', '【场景概要（较早情节的压缩记录'],
  ['场景摘要函数', 'async function summarizeScene(char, sceneKey)'],
  ['场景切换触发', "canRunMemoryTask(counters, 'scene')"],
  ['场景状态字段', "sceneState: { key: '', startCount: 0, messageCount: 0 },"],
  ['场景归一化', 'character.memory.scenes = Array.isArray(rawMemory.scenes)'],
  ['场景重试计数器', "character.memory.counters.sceneRetryAt = normalizeRetryAt(rawCounters.sceneRetryAt);"],
  ['建卡质量约束常量', 'var CHARACTER_QUALITY_RULE ='],
  ['说话风格示例约束常量', 'var SPEAKING_STYLE_SAMPLES_RULE ='],
  ['建卡 prompt 引用质量约束', "' + CHARACTER_QUALITY_RULE + SPEAKING_STYLE_SAMPLES_RULE"],
  ['成员补全：已有字段绝对权威', '已有字段是绝对权威，任何情况下不得改写、润色或替换'],
  ['成员补全：示例台词约束', "+ SPEAKING_STYLE_SAMPLES_RULE }"],
  ['空字段补全：说话风格约束', "(job.missing.indexOf('speakingStyle') !== -1 ? SPEAKING_STYLE_SAMPLES_RULE"],
  ['即时摘要：只保留剧情要点', '**只保留会影响后续剧情的内容**'],
  ['即时摘要：长度桶', '**每条content不超过120字**'],
  ['即时摘要：再摘要保留有效信息', '必须原样保留，只丢弃已经过时或已被推翻的部分'],
  ['长期记忆：价值判据', '**价值判据**'],
  ['长期记忆：证据不足宁可不记', '**证据不足宁可不记**'],
  ['剧情弧线：命名稳定', 'arcOf命名一旦确定就保持稳定'],
  ['前缀缓存注释仍在', 'volatile 上下文只附加到当前用户'],
  ['角色设定固定前缀仍在', '【角色设定（固定，不随对话变化）】']
];
mustHave.forEach(([name, needle]) => ok(name, script.includes(needle) || html.includes(needle)));

const pixelPromptUses = [...script.matchAll(/PIXEL_AVATAR_PROMPT/g)].length;
ok('像素头像 prompt 已不再注入建卡/补全（仅剩定义与遗留生成器 2 处）', pixelPromptUses === 2, 'uses=' + pixelPromptUses);

const ruleIds = [...script.matchAll(/systemPrompt \+= '([0-9A-Z]+(?:\.[0-9]+)?)[.．]?/g)].map((m) => m[1]);
const dupRules = ruleIds.filter((id, i) => ruleIds.indexOf(id) !== i);
ok('交互规则编号无重复', dupRules.length === 0, 'dups: ' + [...new Set(dupRules)].join(','));

console.log('\n[4] 规则块体积对比（systemPrompt 装配区）');
function ruleBlock(src) {
  const a = src.indexOf("var systemPrompt = '【交互规则】");
  const b = src.indexOf('【角色设定（固定');
  return a >= 0 && b > a ? src.slice(a, b) : '';
}
try {
  const { execSync } = await import('node:child_process');
  const head = execSync('git show HEAD:hub.html', { encoding: 'utf8', maxBuffer: 1e9, cwd: path.resolve(here, '..') });
  const before = ruleBlock(head);
  const after = ruleBlock(script);
  const briefSrc = script.match(/const JSON_EXAMPLE_BRIEF = ([\s\S]*?);\n/);
  const fullSrc = script.match(/var jsonExampleRule = ([\s\S]*?);\n  systemPrompt \+= assistantTurnCount/);
  const briefLen = briefSrc ? new Function('return ' + briefSrc[1])() .length : 0;
  const fullRuleLen = fullSrc ? fullSrc[1].length : 0;
  const longChatNow = after.length - fullRuleLen + briefLen;
  console.log('  HEAD 规则块 = ' + before.length + ' 字符');
  console.log('  现在规则块 = ' + after.length + ' 字符');
  console.log('  长会话等效 = ' + longChatNow + ' 字符（第 6 轮起把完整示例换成精简版：' + fullRuleLen + ' -> ' + briefLen + '）');
  const added = after.split('\n').filter((l) => /systemPrompt \+= '(0\.5|2\.6) /.test(l)).reduce((s, l) => s + l.length, 0);
  console.log('  本批新增规则（0.5 + 2.6）共 ' + added + ' 字符');
  console.log('  纯精简净效果 = ' + (before.length + added) + ' -> ' + longChatNow + '（' + ((before.length + added - longChatNow) / (before.length + added) * 100).toFixed(1) + '%）');
  ok('长会话等效不超过 HEAD（在新增规则的前提下）', longChatNow <= before.length, before.length + ' vs ' + longChatNow);
  ok('剔除新增规则后精简幅度 >= 10%', (before.length + added - longChatNow) / (before.length + added) >= 0.1, ((before.length + added - longChatNow) / (before.length + added) * 100).toFixed(1) + '%');
} catch (error) {
  ok('可与 HEAD 对比规则块体积', false, error.message);
}

console.log('\n[5] 角色卡弹窗 tab 结构一致性');
{
  const tabIds = [...html.matchAll(/id="modal-tab-([a-zA-Z]+)"/g)].map((m) => m[1]).sort();
  const contentIds = [...html.matchAll(/id="modal-content-([a-zA-Z]+)"/g)].map((m) => m[1]).sort();
  const declared = (script.match(/\['(basic|state|lorebook)'(?:, '(?:basic|state|lorebook)')+\]/) || [''])[0]
    .replace(/[\[\]' ]/g, '').split(',').filter(Boolean).sort();
  const clickTargets = [...new Set([...html.matchAll(/switchModalTab\('(\w+)'\)/g)].map((m) => m[1]))].sort();
  console.log('  tab 按钮: ' + tabIds.join(',') + ' | 内容区: ' + contentIds.join(',') + ' | 切换目标: ' + clickTargets.join(','));
  ok('tab 按钮与内容区一一对应', JSON.stringify(tabIds) === JSON.stringify(contentIds));
  ok('switchModalTab 覆盖全部 tab', JSON.stringify(declared) === JSON.stringify(tabIds));
  ok('所有切换目标都有对应 tab', clickTargets.every((t) => tabIds.includes(t)));
}

console.log('\n[6] 版本号同步（APP_VERSION 与 versionName）');
{
  const rootDir = path.resolve(here, '..');
  const gradle = fs.readFileSync(path.join(rootDir, 'android-lite', 'app', 'build.gradle.kts'), 'utf8');
  const vName = (gradle.match(/versionName\s*=\s*"([^"]+)"/) || [])[1];
  const vCode = Number((gradle.match(/versionCode\s*=\s*(\d+)/) || [])[1]);
  const appVersion = (script.match(/const APP_VERSION = '([^']*)'/) || [])[1];
  const assetHtml = fs.readFileSync(path.join(rootDir, 'android-lite', 'app', 'src', 'main', 'assets', 'hub.html'), 'utf8');
  const assetVersion = (assetHtml.match(/const APP_VERSION = '([^']*)'/) || [])[1];
  console.log('  versionName=' + vName + ' | versionCode=' + vCode + ' | hub.html=' + appVersion + ' | assets=' + assetVersion);
  ok('hub.html 的 APP_VERSION 与 versionName 一致', !!vName && appVersion === vName, 'APP_VERSION=' + appVersion + ' vs ' + vName);
  ok('APK 内 hub.html 的 APP_VERSION 与版本一致', assetVersion === appVersion && assetVersion === vName, 'assets=' + assetVersion);
  ok('versionCode 为数字且已递增（>= 26）', vCode >= 26, 'versionCode=' + vCode);
  ok('sync-version 脚本存在', fs.existsSync(path.join(here, 'sync-version.mjs')));
  const workflow = fs.readFileSync(path.join(rootDir, '.github', 'workflows', 'build-lite-apk.yml'), 'utf8');
  ok('工作流在打包前写入 APP_VERSION', workflow.includes('node tools/sync-version.mjs'));
  ok('工作流校验两份 hub.html 字节一致', workflow.includes('cmp hub.html android-lite/app/src/main/assets/hub.html'));
}

console.log('\n[7] 语气遵从注入点与缓存安全');
{
  const idxBone = script.indexOf('volatileContext += buildNarrativePatternDirective();');
  const idxCorrection = script.indexOf('volatileContext += buildStyleCorrectionReminder(char);');
  const idxReview = script.indexOf('volatileContext += buildStyleReview(char);');
  const idxAnchor = script.indexOf('volatileContext += buildStyleAnchor(char);');
  ok('语气相关注入点都在 volatile 中', idxBone > 0 && idxCorrection > 0 && idxReview > 0 && idxAnchor > 0);
  ok('注入顺序：节奏骨架 → 纠正提醒 → 语气回顾 → 语气锚（越靠后越近生成点）',
    idxBone < idxCorrection && idxCorrection < idxReview && idxReview < idxAnchor);
  const roleIdx = script.indexOf('buildRoleContext(char, true)');
  const hardIdx = script.indexOf("systemPrompt += '\\n【硬性约束与语气锁定");
  const outIdx = script.indexOf("systemPrompt += '【输出格式】");
  ok('硬性约束块与输出格式都排在角色设定之后（recency）', roleIdx > 0 && roleIdx < hardIdx && hardIdx < outIdx);
  ok('规则块开头有优先级阶梯', script.indexOf('（规则优先级从高到低：') > 0 && script.indexOf('（规则优先级从高到低：') < script.indexOf("systemPrompt += '0.（最高优先级）"));
  ok('语气锁定与禁令各只在静态块末尾出现一次',
    (script.match(/systemPrompt \+= '0\.5 你只扮演角色本人/g) || []).length === 1
    && (script.match(/systemPrompt \+= '2\.5 语气锁定/g) || []).length === 1);

  const sysStart = script.indexOf("var systemPrompt = '【交互规则】");
  const sysEnd = script.indexOf('var recentMessages = [];');
  const sysBlock = script.slice(sysStart, sysEnd);
  ok('system 拼装区不含随轮变化的内容（缓存安全）',
    sysStart > 0 && sysEnd > sysStart && !/Math\.random\(|new Date\(|getLastReplyGap|retrieveRelevantMemories|getRecentReplyEndings|buildStyleAnchor/.test(sysBlock));
  const volatileAppend = script.match(/buildUserMessageContent\(trimText\(userContent, 2000\)/g) || [];
  ok('volatile 只拼到当前用户消息（前缀缓存友好）', volatileAppend.length === 1);

  ok('critique 只在违规且开关打开时跑', script.includes('styleViolations.length > 0 && state.config.styleCritique !== false'));
  ok('critique 有长度异常回退', script.includes('STYLE_GUARD.critiqueMinRatio') && script.includes('STYLE_GUARD.critiqueMaxRatio') && script.includes('STYLE_GUARD.critiqueMaxChars'));
  ok('违规标签随消息持久化', script.includes('styleViolations: Array.isArray(message.styleViolations)'));
}

console.log('\n结果: ' + pass + ' 通过, ' + failures.length + ' 失败');
if (failures.length) { failures.forEach((f) => console.log('  - ' + f)); process.exit(1); }
