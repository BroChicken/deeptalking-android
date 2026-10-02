// Deterministic, dependency-free build for both browser and Android WebView.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const targets = [
  'hub.html',
  'android-lite/app/src/main/assets/hub.html'
];

export function readVersion() {
  const gradle = fs.readFileSync(path.join(root, 'android-lite/app/build.gradle.kts'), 'utf8');
  const version = gradle.match(/versionName\s*=\s*"(\d+\.\d+\.\d+)"/)?.[1];
  if (!version) throw new Error('Missing X.Y.Z versionName in Android build configuration');
  return version;
}

export function renderHub() {
  const sourceRoot = path.join(root, 'src');
  const manifest = JSON.parse(fs.readFileSync(path.join(sourceRoot, 'manifest.json'), 'utf8'));
  const listed = [...manifest.styles, ...manifest.scripts];
  if (new Set(listed).size !== listed.length) throw new Error('Duplicate source in manifest');
  const read = (file) => {
    if (path.isAbsolute(file) || file.includes('\\') || file.split('/').includes('..')) {
      throw new Error('Invalid source path: ' + file);
    }
    return fs.readFileSync(path.join(sourceRoot, file), 'utf8');
  };
  let html = read('shell.html');
  const substitute = (token, content) => {
    if (html.split(token).length !== 2) throw new Error('Expected one template token: ' + token);
    html = html.replace(token, () => content);
  };
  for (const file of manifest.styles) substitute('{{' + file + '}}', read(file));
  let script = manifest.scripts.map(read).join('');
  const versionToken = '__APP_VERSION__';
  if (script.split(versionToken).length !== 2) throw new Error('Expected one APP_VERSION token');
  script = script.replace(versionToken, readVersion());
  new Function(script);
  substitute('{{scripts}}', script);
  if (/\{\{(?:scripts|styles\/[^}]+)\}\}/.test(html)) throw new Error('Unresolved template token');
  return html;
}

export function buildHub({ check = false } = {}) {
  const expected = Buffer.from(renderHub(), 'utf8');
  let changed = 0;
  for (const target of targets) {
    const file = path.join(root, target);
    const current = fs.existsSync(file) ? fs.readFileSync(file) : null;
    if (current?.equals(expected)) continue;
    if (check) throw new Error('Stale generated file: ' + target + '. Run node tools/build-hub.mjs');
    fs.writeFileSync(file, expected);
    changed++;
  }
  return changed;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const check = process.argv.includes('--check');
    const changed = buildHub({ check });
    console.log(check ? 'build-hub: both outputs match module sources' : `build-hub: updated ${changed} output(s)`);
  } catch (error) {
    console.error('build-hub: ' + error.message);
    process.exitCode = 1;
  }
}
