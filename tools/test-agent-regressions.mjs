import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { test } from 'node:test';
import { root } from './build-hub.mjs';

process.env.TZ = 'Asia/Shanghai';
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'src/manifest.json'), 'utf8'));
const source = manifest.scripts.map(file => fs.readFileSync(path.join(root, 'src', file), 'utf8')).join('');
const clone = value => JSON.parse(JSON.stringify(value));

function runtime() {
  const storage = new Map();
  const errors = [];
  const box = {
    errors,
    console: { log() {}, warn() {}, error(label, error) { errors.push(String(label) + ': ' + (error && error.message || '')); } }, Intl, URL, AbortController, TextDecoder, TextEncoder,
    Date: class extends Date {
      constructor(...args) { super(...(args.length ? args : ['2026-10-01T18:12:01.439Z'])); }
      static now() { return Date.parse('2026-10-01T18:12:01.439Z'); }
    },
    setTimeout: () => 1, clearTimeout() {}, addEventListener() {}, alert() {}, confirm: () => true,
    document: { visibilityState: 'visible', getElementById: () => ({ value: '', style: {} }) },
    localStorage: { setItem: (key, value) => storage.set(key, value), getItem: key => storage.get(key) || null }
  };
  box.window = box;
  vm.createContext(box);
  vm.runInContext(source, box);
  vm.runInContext(`
    renderChat = function() {}; renderCharacterList = function() {}; renderDebugInfo = function() {};
    renderCacheStats = function() {}; setActivity = function() {}; clearActivity = function() {};
    autoResize = function() {}; clearPendingImages = function() {}; autoFillStaticFields = function() {};
    updateMessageBubble = function() {};
    state.config.apiKey = 'test-only-key'; state.config.proactiveEnabled = false;
    state.config.styleCritique = false; state.config.quickReplyRepair = false;
  `, box);
  const evaluate = (code, values = {}) => {
    Object.assign(box, values);
    return vm.runInContext(code, box);
  };
  const char = evaluate(`(() => {
    const char = createCharacterObj('Test', 'star', 'Calm', 'Researcher');
    char.id = 'test'; state.characters[char.id] = char; state.activeCharacterId = char.id;
    return char;
  })()`);
  return { box, evaluate, char };
}

function message(id, role = 'user', content = 'Fact ' + id, index = 0) {
  return { id, role, content, timestamp: new Date(Date.parse('2026-10-01T18:00:00Z') + index * 1000).toISOString() };
}

test('unrelated response evidence cannot alter dynamic or static fields', () => {
  const r = runtime();
  const result = r.evaluate(`(() => {
    const reply = {id:'a', content:'AAAAAAAAAA'};
    const update = {value:'Formal English', sourceMessageIds:['current_response'], evidence:'ZZZZZZZZ'};
    return {matches:evidenceMatchesSummary(reply.content, update.evidence),
      dynamic:applyDynamicStateUpdates(char, {currentMood:update}, reply),
      static:applyStaticFieldUpdates(char, {speakingStyle:update}, reply).length};
  })()`, { char: r.char });
  assert.equal(result.matches, false);
  assert.equal(result.dynamic, 0);
  assert.equal(result.static, 0);
});

test('static changes require a field-specific user edit request', () => {
  const r = runtime();
  r.char.memory.instant = [message('u', 'user', 'Please change your speaking style to formal English.')];
  const changed = r.evaluate(`applyStaticFieldUpdates(char, {speakingStyle:{
    value:'Formal English', sourceMessageIds:['u'], evidence:char.memory.instant[0].content
  }}, null)`, { char: r.char });
  assert.equal(changed.length, 1);
  r.char.memory.instant.push(message('u2', 'user', 'I enjoy formal English.'));
  assert.equal(r.evaluate(`applyStaticFieldUpdates(char, {personality:{value:'Cold',
    sourceMessageIds:['u2'], evidence:'I enjoy formal English.'}}, null).length`, { char: r.char }), 0);
});

test('explicit timestamps keep their offset and precision; date-only references invent no noon', () => {
  const r = runtime();
  assert.equal(r.evaluate(`resolveTimeRef({explicit:'2026-10-02T02:12:01+08:00'}).iso`), '2026-10-01T18:12:01.000Z');
  assert.equal(r.evaluate(`resolveTimeRef({anchor:'today'}).iso`), null);
  assert.equal(r.evaluate(`resolveTimeRef({anchor:'today'}).day`), '2026-10-02');
  assert.equal(r.evaluate(`resolveTimeRef({})`), null);
  assert.equal(r.evaluate(`resolveTimeRef({anchor:'tomorrow',slot:'下午'}).slot`), '\u4e0b\u5348');
});

test('independent same-slot facts and all extraction sources are retained', () => {
  const r = runtime();
  r.char.memory.instant = Array.from({ length: 12 }, (_, i) => message('u' + i, 'user', 'Verified ' + i, i));
  r.evaluate(`
    addShortTermMemory(char, 'Earlier important fact', char.memory.instant[0].timestamp,
      {sourceMessageIds:['u0']});
    addShortTermMemory(char, 'New independent fact', char.memory.instant[1].timestamp,
      {sourceMessageIds:char.memory.instant.map(m => m.id), timeRef:{anchor:'today'}});
  `, { char: r.char });
  assert.equal(r.char.memory.shortTerm.length, 2);
  assert.equal(r.char.memory.shortTerm[0].content, 'Earlier important fact');
  assert.equal(r.char.memory.shortTerm[1].sourceMessageIds.length, 12);
  assert.equal(r.char.memory.shortTerm[1].eventTime, r.char.memory.instant[1].timestamp);
});

test('summaries remain until BOTH consumers finish; a zero trim budget keeps no completed rows', () => {
  const r = runtime();
  const input = Array.from({ length: 82 }, (_, i) => ({ id: 's' + i, revision: 1,
    analyzedAt: i < 2 ? 'done' : null, analyzedRevision: i < 2 ? 1 : 0,
    lorebookScannedAt: i < 2 ? 'done' : null, lorebookScannedRevision: i < 2 ? 1 : 0 }));
  const output = r.evaluate('trimShortTermList(rows)', { rows: input });
  assert.equal(output.length, 80);
  assert(output.every(item => !item.analyzedAt));
  input[0].lorebookScannedAt = null;
  input[0].lorebookScannedRevision = 0;
  assert(r.evaluate('trimShortTermList(rows)', { rows: input }).some(item => item.id === 's0'));
});

test('long-term analysis hands retained summaries to lorebook processing', async () => {
  const r = runtime();
  r.char.memory.shortTerm = Array.from({ length: 42 }, (_, i) => ({ id: 's' + i,
    content: 'Verified summary ' + i, timestamp: message('t', 'user', '', i).timestamp,
    revision: 1, sourceMessageIds: [], sourceRoles: [], userEvidence: [], analyzedAt: null }));
  r.evaluate(`
    globalThis.calls = 0;
    callAPI = async function() { calls++; return {choices:[{message:{content:JSON.stringify(
      calls === 1 ? {status:'ok', longTerm:[], analyzedShortTermIds:char.memory.shortTerm.slice(0,22).map(s=>s.id)} : {entries:[]}
    )}}]}; };
  `, { char: r.char });
  assert.equal(await r.evaluate('analyzeShortToLongTerm(char)', { char: r.char }), true);
  assert(r.char.memory.shortTerm.some(item => item.analyzedAt));
  assert.equal(await r.evaluate('consolidateLorebook(char)', { char: r.char }), true);
  assert.equal(r.box.calls, 2);
  assert(r.char.memory.shortTerm.every(item => !item.analyzedAt || item.lorebookScannedAt));
});

test('malformed lorebook output changes no markers or eviction counters', async () => {
  const r = runtime();
  r.char.memory.shortTerm = [{ id: 's', revision: 1, content: 'Known place', analyzedAt: 'done' }];
  r.char.lorebook = [{ name: 'Place', origin: 'ai', misses: 0, alwaysActive: false }];
  r.evaluate(`callAPI = async function(){return {choices:[{message:{content:'{}'}}]};};`);
  assert.equal(await r.evaluate('consolidateLorebook(char)', { char: r.char }), false);
  assert.equal(r.char.lorebook[0].misses, 0);
  assert.equal(r.char.memory.shortTerm[0].lorebookScannedAt, undefined);
});

test('scene counts survive high-watermark trimming', async () => {
  const r = runtime();
  r.char.memory.instant = Array.from({ length: 160 }, (_, i) => ({ ...message('m' + i, i % 2 ? 'assistant' : 'user', 'Text', i),
    sequence: i + 1, extractedAt: i < 120 ? 'done' : null }));
  r.char.memory.sceneState = { key: 'Room', startCount: 120, startSequence: 120 };
  r.char.dynamicState.currentLocation = 'Room';
  r.evaluate(`globalThis.sceneCalls=0; summarizeScene=async function(){sceneCalls++;return true};
    trimCharacterMemory(char);`, { char: r.char });
  assert.equal(r.char.memory.instant.length, 40);
  await r.evaluate('checkMemoryTriggers(char)', { char: r.char });
  assert.equal(r.box.sceneCalls, 1);
  assert.equal(r.char.memory.sceneState.startSequence, 160);
});

test('forced recall wins a full candidate list; unrelated generic words retrieve nothing', () => {
  const r = runtime();
  r.char.memory.longTerm.events = Array.from({ length: 20 }, (_, i) => ({ id: 'm' + i, key: 'Known mountain ' + i,
    value: 'Mountain trip', importance: 10, createdAt: message('t').timestamp }));
  r.char.memory.pendingRecall = [{ id: 'forced', key: 'Identity', value: 'Identity fact' }];
  assert.equal(r.evaluate(`retrieveRelevantMemories(char,'mountain')[0].item.id`, { char: r.char }), 'forced');
  r.char.memory.pendingRecall = [];
  assert.equal(r.evaluate(`retrieveRelevantMemories(char,'\u4e0d\u77e5\u9053').length`, { char: r.char }), 0);
});

test('stale prose jobs cannot resurrect removed replies or overwrite another turn', async () => {
  const r = runtime();
  const user = message('u');
  const reply = message('a', 'assistant', 'The room is quiet.');
  r.char.memory.instant = [user, reply];
  r.char.memory.lastInjectedRecallIds = ['old'];
  r.evaluate(`callAPI=function(){return new Promise(resolve=>globalThis.resolveLater=resolve)};`);
  const task = r.evaluate('extractProseTurnMemory(char,user,reply.content,reply)', { char: r.char, user, reply });
  r.char.memory.instant.pop();
  r.char.memory.revision++;
  r.char.dynamicState.currentMood = 'new';
  r.char.memory.lastInjectedRecallIds = ['new'];
  r.box.resolveLater({ choices: [{ message: { content: JSON.stringify({ reply: reply.content,
    dynamicState: { currentMood: { value: 'old', sourceMessageIds: ['current_response'], evidence: 'The room is quiet.' } } }) } }] });
  await task;
  assert.equal(r.char.dynamicState.currentMood, 'new');
  assert.deepEqual(Array.from(r.char.memory.lastInjectedRecallIds), ['new']);
});

test('tool batch executes changes before accepting a combined submit', async () => {
  const r = runtime();
  r.evaluate(`checkMemoryTriggers=async function(){return true};
    performChatRequestWithRetry=async function(){
      const u=char.memory.instant.filter(m=>m.role==='user').at(-1);
      const output=[{type:'function_call',name:'update_character_field',call_id:'edit',arguments:JSON.stringify({
        field:'speakingStyle',value:'Formal English',sourceMessageIds:[u.id],evidence:u.content})},
        {type:'function_call',name:'submit_response',call_id:'submit',arguments:JSON.stringify({reply:'Done.',quickReplies:['Thanks.','More.']})}];
      return {fullText:'',fullResponse:{output},functionCalls:output,reasoningItems:[],seenEventTypes:[]};
    };`, { char: r.char });
  await r.evaluate(`sendMessage({text:'Please change your speaking style to formal English.'})`);
  assert.deepEqual(r.box.errors, []);
  assert.equal(r.char.basicInfo.speakingStyle, 'Formal English');
  assert.equal(r.char.memory.instant.at(-1).content, 'Done.');
});

test('current user text has identical encoding when it becomes historical', () => {
  const r = runtime();
  r.char.memory.instant = [message('u1', 'user', 'long '.repeat(240))];
  const first = clone(r.evaluate('buildRequestPayload(char,char.memory.instant[0].content)', { char: r.char }));
  r.char.memory.instant.push(message('a1', 'assistant', 'Reply'), message('u2', 'user', 'Next'));
  const second = clone(r.evaluate(`buildRequestPayload(char,'Next')`, { char: r.char }));
  assert.deepEqual(first[1], second[1]);
  assert.equal(first[0].content, second[0].content);
  assert(first.at(-1).content.includes('\u7cfb\u7edf\u63d0\u4f9b'));
  assert.equal(r.evaluate('buildVolatileContext(char,\'Next\').length', { char: r.char }) <= 6000, true);
});

test('static group context excludes live location', () => {
  const r = runtime();
  const group = r.evaluate(`createGroupObj('Group','star','Study',[])`);
  group.groupInfo.scene = '';
  group.dynamicState.currentLocation = 'Room A';
  const first = r.evaluate('buildRoleContext(group,true)', { group });
  group.dynamicState.currentLocation = 'Room B';
  assert.equal(r.evaluate('buildRoleContext(group,true)', { group }), first);
});

test('unknown cache usage stays unknown; background usage cannot replace main-chat statistics', () => {
  const r = runtime();
  assert.equal(r.evaluate('normalizeCacheStats({hitTokens:null,missTokens:null,promptTokens:100}).hitTokens'), null);
  r.evaluate(`recordCacheUsage({input_tokens:100,input_tokens_details:{cached_tokens:80}}, {taskType:'chat',characterId:'test'});
    recordCacheUsage({input_tokens:10,input_tokens_details:{cached_tokens:0}}, {taskType:'memory',characterId:'test'});`);
  assert.equal(r.evaluate('state.config.cacheStats.hitTokens'), 80);
  assert.equal(r.evaluate('state.config.requestMetrics.length'), 2);
  assert.equal(r.evaluate('state.config.requestMetrics.at(-1).taskType'), 'memory');
});

const saveFile = path.join(root, 'save/deeptalking_backup_2026-10-02.json');
test('latest save: repaired cursors, full-text repetition and correction-aware retrieval', { skip: !fs.existsSync(saveFile) }, async () => {
  const original = fs.readFileSync(saveFile);
  const r = runtime();
  const saved = JSON.parse(original.toString('utf8'));
  const normalized = r.evaluate('normalizeAppData(saved,false,true)', { saved });
  r.evaluate('state.characters=normalized.characters;state.activeCharacterId=normalized.activeCharacterId;', { normalized });
  const active = normalized.characters[normalized.activeCharacterId];
  assert(active.memory.sceneState.startSequence <= active.memory.counters.messageSequence);
  const query = '\u586b\u8fc7\u4e0d\u6b62\u4e00\u6b21\u4e86';
  const correction = active.memory.instant.findIndex(msg => msg.id === 'msg_1790878140562_c0t5oga');
  const replay = clone(active);
  replay.memory.instant = replay.memory.instant.slice(0, correction + 1);
  const selected = r.evaluate('retrieveRelevantMemories(replay,buildMemoryQuery(replay,query))', { replay, query });
  assert(selected.slice(0, 5).some(entry => entry.item.id === 'mem_1789923489682_2ampfha'));
  const recent = active.memory.instant.filter(msg => msg.role === 'assistant').slice(-8);
  let detected = 0;
  for (const reply of recent) {
    const prior = clone(active);
    prior.memory.instant = prior.memory.instant.slice(0, prior.memory.instant.findIndex(msg => msg.id === reply.id));
    detected += Number(r.evaluate('detectStyleViolations(reply.content,prior,getRecentReplyTexts(prior,2)).includes(\'reusedImagery\')', { reply, prior }));
  }
  assert(detected >= 3);
  assert(fs.readFileSync(saveFile).equals(original), 'Regression must never rewrite the private save');
});
