/* Regression tests for WebHome's startup race (Node built-ins only). */
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const root = path.join(__dirname, '..');
const app = fs.readFileSync(path.join(root, 'app/src/main/assets/caiguale/app.js'), 'utf8');
const native = fs.readFileSync(path.join(root, 'app/src/main/java/com/fongmi/android/tv/web/HomeWebController.java'), 'utf8');
const leanback = fs.readFileSync(path.join(root, 'app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java'), 'utf8');
const settings = fs.readFileSync(path.join(root, 'app/src/main/java/com/fongmi/android/tv/setting/Setting.java'), 'utf8');
const first = app.indexOf('  var BRIDGE_POLL_MS =');
const end = app.indexOf('  function describeError(error) {', first);
assert(first > 0 && end > first, 'Bridge startup helpers not found');
const helperSource = 'function text(value) { return value == null ? "" : String(value).trim(); }\n' +
  app.slice(first, end) + '\nthis.testHelpers = {sdkInvoke:sdkInvoke,waitForBridgeReady:waitForBridgeReady};';

function setup() {
  const handlers = new Map();
  const queue = [];
  const calls = [];
  const window = {
    addEventListener(name, cb) { if (!handlers.has(name)) handlers.set(name, new Set()); handlers.get(name).add(cb); },
    removeEventListener(name, cb) { if (handlers.has(name)) handlers.get(name).delete(cb); },
    dispatch(name) { for (const cb of [...(handlers.get(name) || [])]) cb(); }
  };
  const context = {window, Promise, setTimeout(fn) { queue.push(fn); return queue.length; },
    clearTimeout() { /* Queue cancellation is not required for the simulated clock. */ }};
  vm.runInNewContext(helperSource, context, {filename:'app.js bridge startup section'});
  return {window, calls, queue, sdkInvoke:context.testHelpers.sdkInvoke,
    installSdk() { window.fongmi = {invoke(method, payload) {
      calls.push({method, payload, token:window.__fmBridgeToken});
      return Promise.resolve({items:[]});
    }}; window.dispatch('fmsdk'); },
    installToken() { window.__fmBridgeToken='test-token-only'; window.dispatch('fmbridge-ready'); },
    async tick() { const pending = queue.splice(0); for (const fn of pending) fn(); await Promise.resolve(); }
  };
}

(async () => {
  // Initial boot with no SDK/token must wait, not call an untrusted native bridge.
  const a = setup();
  const waiting = a.sdkInvoke('vod.sites',{});
  await a.tick();
  assert.equal(a.calls.length, 0);
  a.installSdk(); await a.tick();
  assert.equal(a.calls.length, 0, 'SDK alone must not authorize requests');
  a.installToken();
  await waiting;
  assert.equal(a.calls.length, 1);
  assert.equal(a.calls[0].method,'vod.sites');
  assert.equal(a.calls[0].token,'test-token-only');

  // A token arriving before the SDK is equally valid, with no duplicate call.
  const b = setup();
  const pending = b.sdkInvoke('vod.home',{siteKey:'unit-test'});
  b.installToken();
  await b.tick();
  assert.equal(b.calls.length,0);
  b.installSdk();
  await pending;
  assert.equal(b.calls.length,1);
  assert.equal(b.calls[0].payload.siteKey,'unit-test');

  // A missing bridge fails closed within the bounded polling window.
  const c=setup();
  let reason='';
  const timeout=c.sdkInvoke('vod.sites',{}).catch(e=>{reason=e.code;});
  for(let n=0;n<32;n++) await c.tick();
  await timeout;
  assert.equal(reason,'BRIDGE_TIMEOUT');
  assert.equal(c.calls.length,0);

  // Static guardrails: token remains required; avoid early unsafe permission redirect.
  assert(native.includes("window.dispatchEvent(new Event('fmbridge-ready'))"),'native token ready signal missing');
  assert(native.includes('newBridgeToken()'),'per-load token missing');
  assert(leanback.includes('if (!com.fongmi.android.tv.BuildConfig.FEATURE_EXO_ONLY)'), 'EXO-only startup permission guard missing');
  assert(settings.includes('Locale.getDefault()'), 'system language fallback missing');
  console.log('Bridge readiness: 3 runtime scenarios + native/UI guardrails passed');
})().catch(error=>{console.error(error);process.exit(1);});
