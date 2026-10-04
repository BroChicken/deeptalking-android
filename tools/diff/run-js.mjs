// JS-side oracle for the differential test.
// Loads the legacy src/js modules in a vm sandbox and, for each scenario,
// produces a normalized snapshot: system prompt, volatile context, the request
// body, and the agent/submit tool names+schemas. The Kotlin test produces the
// same snapshot so they can be compared field by field.
//
// Usage: node tools/diff/run-js.mjs [out.json]
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { SCENARIOS, FIXED_NOW, toNativeCharacter } from './scenarios.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');

process.env.TZ = 'Asia/Shanghai';

const manifest = JSON.parse(fs.readFileSync(path.join(root, 'src/manifest.json'), 'utf8'));
const source = manifest.scripts
  .map((file) => fs.readFileSync(path.join(root, 'src', file), 'utf8'))
  .join('');

function buildSandbox() {
  const storage = new Map();
  const box = {
    console: { log() {}, warn() {}, error() {} },
    Intl,
    URL,
    AbortController,
    TextDecoder,
    TextEncoder,
    Date: class extends Date {
      constructor(...args) {
        super(...(args.length ? args : [FIXED_NOW]));
      }
      static now() {
        return Date.parse(FIXED_NOW);
      }
    },
    setTimeout: () => 1,
    clearTimeout() {},
    addEventListener() {},
    alert() {},
    confirm: () => true,
    fetch: () => Promise.reject(new Error('network disabled in oracle')),
    document: {
      visibilityState: 'visible',
      getElementById: () => ({ value: '', style: {} }),
      querySelectorAll: () => [],
      createElement: () => ({ style: {}, getContext: () => null }),
    },
    localStorage: {
      setItem: (k, v) => storage.set(k, v),
      getItem: (k) => storage.get(k) || null,
    },
  };
  box.window = box;
  vm.createContext(box);
  vm.runInContext(source, box);
  vm.runInContext(
    `renderChat = function() {}; renderCharacterList = function() {}; renderDebugInfo = function() {};
     renderCacheStats = function() {}; setActivity = function() {}; clearActivity = function() {};
     autoResize = function() {}; clearPendingImages = function() {}; autoFillStaticFields = function() {};
     updateMessageBubble = function() {}; saveData = function() {}; scheduleSave = function() {};`,
    box,
  );
  return box;
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function snapshotFor(scenario) {
  const box = buildSandbox();
  box.scenarioConfig = clone(scenario.config);
  box.scenarioCharacter = clone(scenario.character);
  box.scenarioQuery = scenario.query;
  box.scenarioPhase = scenario.phase;

  return vm.runInContext(
    `(() => {
      Object.assign(state.config, scenarioConfig);
      state.config.apiKey = 'test-only-key';
      const char = scenarioCharacter;
      state.characters[char.id] = char;
      state.activeCharacterId = char.id;

      // buildRequestPayload returns the full message array: [system, ...recent].
      const messages = buildRequestPayload(char, scenarioQuery, {});
      const systemPrompt = messages[0] && messages[0].role === 'system' ? messages[0].content : '';
      const volatileContext = buildVolatileContext(char, scenarioQuery, {});
      const body = buildResponsesRequestBody(messages, false, { items: [], char: char }, scenarioPhase, state.config);

      const normalizeTools = (tools) => (tools || []).map((t) => ({
        name: t.name,
        strict: t.strict === true,
        required: (t.parameters && t.parameters.required) || [],
        propertyKeys: Object.keys((t.parameters && t.parameters.properties) || {}).sort(),
      }));

      return JSON.stringify({
        systemPrompt: systemPrompt,
        volatileContext: volatileContext,
        body: {
          model: body.model,
          hasInstructions: typeof body.instructions === 'string' && body.instructions.length > 0,
          toolChoice: body.tool_choice,
          reasoningEffort: body.reasoning && body.reasoning.effort,
          maxOutputTokens: body.max_output_tokens,
          stream: body.stream,
          toolNames: normalizeTools(body.tools),
        },
      });
    })()`,
    box,
  );
}

const output = {};
const nativeScenarios = [];
for (const scenario of SCENARIOS) {
  output[scenario.id] = JSON.parse(snapshotFor(scenario));
  nativeScenarios.push({
    id: scenario.id,
    character: toNativeCharacter(scenario.character),
    config: scenario.config,
    query: scenario.query,
    phase: scenario.phase,
  });
}

const oracleArg = process.argv[2];
const scenariosArg = process.argv[3];
const json = JSON.stringify(output, null, 2);
if (oracleArg) {
  fs.writeFileSync(oracleArg, json);
  console.log('wrote ' + oracleArg + ' (' + Object.keys(output).length + ' scenarios)');
} else {
  console.log(json);
}
if (scenariosArg) {
  fs.writeFileSync(scenariosArg, JSON.stringify({ now: FIXED_NOW, scenarios: nativeScenarios }, null, 2));
  console.log('wrote ' + scenariosArg);
}
