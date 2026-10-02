// versionName is the only source of APP_VERSION; regenerate both outputs.
// Usage: node tools/sync-version.mjs [--check]
import { buildHub, readVersion } from './build-hub.mjs';

try {
  const check = process.argv.includes('--check');
  const changed = buildHub({ check });
  console.log(`sync-version: versionName=${readVersion()}, ${check ? 'sources and outputs match' : `updated ${changed} file(s)`}`);
} catch (error) {
  console.error('sync-version: ' + error.message);
  process.exitCode = 1;
}
