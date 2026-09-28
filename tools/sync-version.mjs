// Keep APP_VERSION in both hub.html copies in sync with android versionName.
//
// Source of truth: android-lite/app/build.gradle.kts (versionName "X.Y.Z").
// Targets:
//   hub.html
//   android-lite/app/src/main/assets/hub.html
//
// Usage:
//   node tools/sync-version.mjs          # rewrite APP_VERSION to match versionName
//   node tools/sync-version.mjs --check  # exit 1 when out of sync (used by tests/CI)
//
// Runs on Windows and on the ubuntu-latest runner (node is preinstalled there).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const gradleFile = path.join(root, 'android-lite', 'app', 'build.gradle.kts');
const targets = [
  path.join(root, 'hub.html'),
  path.join(root, 'android-lite', 'app', 'src', 'main', 'assets', 'hub.html')
];
const check = process.argv.includes('--check');

const gradle = fs.readFileSync(gradleFile, 'utf8');
const match = gradle.match(/versionName\s*=\s*"([^"]+)"/);
if (!match) {
  console.error('sync-version: versionName not found in ' + gradleFile);
  process.exit(2);
}
const version = match[1];
const pattern = /const APP_VERSION = '[^']*';/;

let changed = 0;
let mismatched = 0;
targets.forEach((file) => {
  if (!fs.existsSync(file)) {
    console.error('sync-version: missing file ' + file);
    process.exit(2);
  }
  const src = fs.readFileSync(file, 'utf8');
  if (!pattern.test(src)) {
    console.error('sync-version: APP_VERSION declaration not found in ' + file);
    process.exit(2);
  }
  const current = src.match(/const APP_VERSION = '([^']*)';/)[1];
  if (current === version) return;
  if (check) {
    mismatched++;
    console.error('sync-version: ' + path.relative(root, file) + ' has APP_VERSION ' + current + ', expected ' + version);
    return;
  }
  fs.writeFileSync(file, src.replace(pattern, "const APP_VERSION = '" + version + "';"), 'utf8');
  changed++;
});

if (check) {
  if (mismatched > 0) process.exit(1);
  console.log('sync-version: APP_VERSION matches versionName ' + version);
  process.exit(0);
}
console.log('sync-version: versionName=' + version + ', updated ' + changed + ' file(s)');
