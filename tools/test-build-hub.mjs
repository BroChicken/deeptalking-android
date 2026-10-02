// Exercise the build CLI in an isolated copy, without changing developer files.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { root } from './build-hub.mjs';

const tempRoot = process.platform === 'win32' ? path.join(os.tmpdir(), 'opencode') : os.tmpdir();
assert(fs.existsSync(tempRoot));
const sandbox = fs.mkdtempSync(path.join(tempRoot, 'hub-build-'));
const resolve = file => path.join(sandbox, file);
const write = (file, value) => {
  fs.mkdirSync(path.dirname(resolve(file)), { recursive: true });
  fs.writeFileSync(resolve(file), value);
};
const run = (...args) => spawnSync(process.execPath, [resolve('tools/build-hub.mjs'), ...args], { encoding: 'utf8' });
let passed = 0;
const expect = (name, result, success, detail = '') => {
  assert.equal(result.status, success ? 0 : 1, name + ': ' + result.stderr);
  if (detail) assert(result.stderr.includes(detail), name + ': ' + result.stderr);
  passed++;
  console.log('PASS ' + name);
};

try {
  fs.cpSync(path.join(root, 'src'), resolve('src'), { recursive: true });
  for (const file of ['tools/build-hub.mjs', 'android-lite/app/build.gradle.kts']) {
    write(file, fs.readFileSync(path.join(root, file)));
  }
  fs.mkdirSync(resolve('android-lite/app/src/main/assets'), { recursive: true });
  expect('Missing artifacts rejected by check', run('--check'), false, 'Stale generated file');
  expect('Build creates both artifacts', run(), true);
  const web = fs.readFileSync(resolve('hub.html'));
  assert(web.equals(fs.readFileSync(resolve('android-lite/app/src/main/assets/hub.html'))));
  expect('Fresh artifacts pass check', run('--check'), true);
  const time = fs.statSync(resolve('hub.html')).mtimeMs;
  expect('Repeated build succeeds', run(), true);
  assert.equal(fs.statSync(resolve('hub.html')).mtimeMs, time, 'Unchanged outputs must not be rewritten');

  const configFile = 'src/js/core/config.js';
  const config = fs.readFileSync(resolve(configFile), 'utf8');
  write(configFile, config + '\n// Source-only change\n');
  expect('Source drift rejected', run('--check'), false, 'Stale generated file');
  assert(fs.readFileSync(resolve('hub.html')).equals(web), 'Check must not write');
  expect('Source changes regenerate assets', run(), true);
  write('android-lite/app/src/main/assets/hub.html', 'stale');
  expect('Android-only drift rejected', run('--check'), false, 'android-lite/app/src/main/assets/hub.html');
  expect('Build repairs Android-only drift', run(), true);
  write(configFile, config);

  const manifestFile = 'src/manifest.json';
  const originalManifest = fs.readFileSync(resolve(manifestFile), 'utf8');
  const manifest = JSON.parse(originalManifest);
  write(manifestFile, JSON.stringify({ ...manifest, scripts: [...manifest.scripts, manifest.scripts[0]] }));
  expect('Duplicate module rejected', run(), false, 'Duplicate source');
  write(manifestFile, JSON.stringify({ ...manifest, scripts: ['../hub.html'] }));
  expect('Source traversal rejected', run(), false, 'Invalid source path');
  write(manifestFile, originalManifest);

  const shellFile = 'src/shell.html';
  const shell = fs.readFileSync(resolve(shellFile), 'utf8');
  write(shellFile, shell + '{{scripts}}');
  expect('Duplicate template token rejected', run(), false, 'Expected one template token');
  write(shellFile, shell.replace('{{scripts}}', ''));
  expect('Missing template token rejected', run(), false, 'Expected one template token');
  write(shellFile, shell);
  write(configFile, config.replace('__APP_VERSION__', 'manual-version'));
  expect('Manual version rejected', run(), false, 'Expected one APP_VERSION token');
  write(configFile, config + '\nfunction {\n');
  expect('Invalid application syntax rejected', run(), false);
  write(configFile, config);

  const gradleFile = 'android-lite/app/build.gradle.kts';
  const gradle = fs.readFileSync(resolve(gradleFile), 'utf8');
  write(gradleFile, gradle.replace(/versionName\s*=\s*"[^"]+"/, 'versionName = "9.8.7"'));
  expect('Version change injected through build', run(), true);
  assert(fs.readFileSync(resolve('hub.html'), 'utf8').includes("const APP_VERSION = '9.8.7';"));
  expect('Versioned outputs remain consistent', run('--check'), true);
  console.log(`Build regression: ${passed} passed`);
} finally {
  fs.rmSync(sandbox, { recursive: true, force: true });
}
