#!/usr/bin/env node
/**
 * Runtime test for the Remote Development stub (core/src/main/resources/frontend/remote-stub.html), driven
 * in headless Chrome over the DevTools protocol.
 *
 * Under Remote Dev the plugin does not load the report's file — the thin client would pull it from the host
 * 16 KB at a time. It loads this stub at the report's URL; the stub pulls the report through the IDE's JS
 * bridge, caches it in localStorage and replaces itself with it (RemoteExplorerPage.kt). Three things can
 * rot there that nothing else in the build would see: the stub's placeholders drift from what the Kotlin
 * stamps, document.write stops producing a working page, or the cache stops persisting for the file://
 * origin. So this stands in for the IDE — a bridge that serves the report in parts and raises the bridge
 * event — opens the stub in two Chrome processes sharing one profile, and checks that the first fetched
 * every part, the second fetched nothing, and both ended in a booted explorer that knows it runs inside
 * the IDE. A third open, bridge failing and profile empty, must end in the stub's own error card.
 *
 * The DevTools protocol over --remote-debugging-pipe rather than --dump-dom: the cache test needs a
 * persistent --user-data-dir, and headless Chrome with a fresh profile never returns under
 * --virtual-time-budget (observed with Chrome 152). Real time it is — and the pipe needs no port, no
 * WebSocket and no Node version in particular.
 *
 * Usage:  node scripts/remote-stub-uitest.mjs <report.explorer.html> --stub <remote-stub.html> [--chrome <path>]
 * Wired up as `./gradlew :cli:remoteStubUiTest`, which skips when Chrome is not installed.
 */
import fs from 'fs';
import path from 'path';
import os from 'os';
import crypto from 'crypto';
import { spawn } from 'child_process';

const args = process.argv.slice(2);
const flagValue = f => (args.indexOf(f) >= 0 ? args[args.indexOf(f) + 1] : null);
const stubPath = flagValue('--stub');
const chromeArg = flagValue('--chrome');
const reportPath = args.find((a, i) => !a.startsWith('--') && !['--stub', '--chrome'].includes(args[i - 1]));
if (!reportPath || !stubPath) {
  console.error('usage: node scripts/remote-stub-uitest.mjs <report.explorer.html> --stub <remote-stub.html> [--chrome <path>]');
  process.exit(2);
}
const CHROME_CANDIDATES = [
  chromeArg,
  process.env.CHROME_PATH,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);
const chrome = CHROME_CANDIDATES.find(p => { try { fs.accessSync(p, fs.constants.X_OK); return true; } catch { return false; } });
if (!chrome) {
  if (process.env.ATLAS_REQUIRE_BROWSER_TESTS === '1') {
    console.error('remote-stub-uitest: FAILED — no Chrome/Chromium found and ATLAS_REQUIRE_BROWSER_TESTS=1');
    console.error('  looked at:', CHROME_CANDIDATES.join(', '));
    process.exit(1);
  }
  console.log('remote-stub-uitest: skipped — no Chrome/Chromium found');
  process.exit(0);
}

// ---- what RemoteExplorerPage.kt does: hash the file, split it into parts, stamp the stub ----
const PART_CHARS = 512 * 1024;
const reportBytes = fs.readFileSync(reportPath);
const report = reportBytes.toString('utf8');
if (!report.includes('id="atlas-data"')) { console.error('remote-stub-uitest: not an explorer report'); process.exit(2); }
const hash = crypto.createHash('sha256').update(reportBytes).digest('hex');
const parts = [];
for (let start = 0; start < report.length;) {
  let end = Math.min(start + PART_CHARS, report.length);
  if (end < report.length && /[\uD800-\uDBFF]/.test(report[end - 1])) end--;
  parts.push(report.slice(start, end));
  start = end;
}
const template = fs.readFileSync(stubPath, 'utf8');
for (const ph of ['__ATLAS_HASH__', '__ATLAS_PARTS__', '__ATLAS_SIZE__']) {
  if (!template.includes(ph)) { console.error(`remote-stub-uitest: the stub template has lost its ${ph} placeholder`); process.exit(1); }
}
const stub = template.replace('__ATLAS_HASH__', hash).replace('__ATLAS_PARTS__', String(parts.length)).replace('__ATLAS_SIZE__', '1 MB');
if (stub.includes('__ATLAS_')) { console.error('remote-stub-uitest: a placeholder survived stamping'); process.exit(1); }

// ---- the IDE, as far as the stub can tell: the bridge the editor injects after the stub has loaded ----
// The verdict is appended to the *report's* body once it has booted (or to the stub's when it gave up).
// The poll timer belongs to the window and survives document.open(); so does window.__atlasTest.
const harness = mode => `<script>
(function(){
  var PARTS=${JSON.stringify(parts).replace(/<\//g, '<\\/')};
  var MODE=${JSON.stringify(mode)};
  window.__atlasTest={calls:0,polls:0};
  // The editor installs all three bridges together; the page marks itself as IDE-hosted only when
  // __atlasOpen exists, so the stand-in has to carry it for that check to mean anything.
  window.__atlasOpen=function(){}; window.__atlasCopy=function(){};
  window.__atlasFetch=function(i, ok, fail){
    window.__atlasTest.calls++;
    setTimeout(function(){ if(MODE==='fail') fail(1,'bridge down (test)'); else ok(PARTS[i]); }, 5);
  };
  window.dispatchEvent(new Event('atlas-ide-bridge'));
  (function poll(){
    window.__atlasTest.polls++;
    var nav=document.querySelector('#nav .side-item'), failCard=document.querySelector('.boot-fail');
    if(!nav && !failCard){ setTimeout(poll, 50); return; }
    var lines=[];
    lines.push('page booted: '+(nav?'ok':'FAIL '+(failCard?'stub error card instead: '+failCard.textContent.slice(0,160):'nothing rendered')));
    lines.push('error card: '+(failCard?'shown — '+failCard.textContent.slice(0,120):'none'));
    lines.push('bridge calls: '+window.__atlasTest.calls);
    lines.push('ide class: '+(document.documentElement.classList.contains('ide')?'ok':'FAIL'));
    lines.push('theme: '+document.documentElement.dataset.theme);
    lines.push('url kept: '+(location.search.indexOf('ideTheme=dark')>=0?'ok':'FAIL '+location.href));
    lines.push('bridges kept: '+(typeof window.__atlasFetch==='function'?'ok':'FAIL'));
    var out=document.createElement('pre'); out.id='uitest-result'; out.textContent=lines.join(' ;; ');
    document.body.appendChild(out);
  })();
})();
</script>`;

const sleep = ms => new Promise(r => setTimeout(r, ms));
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'atlas-remote-stub-'));

/**
 * One headless Chrome on [profile], spoken to over --remote-debugging-pipe (fd 3 in, fd 4 out, JSON messages
 * NUL-terminated); [fn] gets a CDP `send(method, params)` bound to the browser's first page target.
 */
async function withChrome(profile, fn) {
  fs.mkdirSync(profile, { recursive: true });
  const proc = spawn(chrome, [
    '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars', '--window-size=1400,900',
    '--no-first-run', '--disable-background-networking', '--disable-component-update', '--disable-sync',
    '--user-data-dir=' + profile, '--remote-debugging-pipe', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'] });
  let stderr = ''; proc.stderr.on('data', d => { stderr += d; });
  const exited = new Promise(res => proc.on('exit', res));
  let seq = 0, buf = ''; const pending = new Map();
  proc.stdio[4].on('data', d => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\0')) >= 0) {
      const m = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1);
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    }
  });
  const call = (method, params = {}, sessionId) => new Promise((res, rej) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); rej(new Error(method + ': no answer in 20s. ' + stderr.slice(-300))); }, 20000);
    pending.set(id, m => { clearTimeout(timer); m.error ? rej(new Error(method + ': ' + m.error.message)) : res(m.result); });
    proc.stdio[3].write(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }) + '\0');
  });
  try {
    const page = (await call('Target.getTargets')).targetInfos.find(t => t.type === 'page');
    if (!page) throw new Error('Chrome opened no page target. ' + stderr.slice(-300));
    const { sessionId } = await call('Target.attachToTarget', { targetId: page.targetId, flatten: true });
    return await fn((method, params) => call(method, params, sessionId));
  } finally {
    // Chrome writes localStorage lazily and flushes it on a *clean* shutdown. Killing it and racing a
    // 3s sleep meant a slow runner could start the next run on the same profile while the last one was
    // still writing — which is the "[reopen] bridge calls: expected 0, got 2" flake: the same commit
    // passed on one runner and failed on another. Browser.close is the clean shutdown; the wait is long
    // enough to mean something, and says so rather than moving on in silence.
    await call('Browser.close').catch(() => proc.kill());
    const exitedCleanly = await Promise.race([exited.then(() => true), sleep(20000).then(() => false)]);
    if (!exitedCleanly) {
      console.error('remote-stub-uitest: Chrome did not exit within 20s — killing it. A [reopen] result ' +
        'after this is not trustworthy: the profile may hold a half-written localStorage.');
      proc.kill('SIGKILL');
      await Promise.race([exited, sleep(5000)]);
    }
  }
}

/** Open the stamped stub (+ the harness in [mode]) on [profile]; returns the harness's lines. */
async function open(label, mode, profile) {
  const page = path.join(work, label + '.html');
  fs.writeFileSync(page, stub.replace('</body>', harness(mode) + '</body>'));
  return withChrome(profile, async send => {
    await send('Page.navigate', { url: 'file://' + page + '?ideTheme=dark' });
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
      const r = await send('Runtime.evaluate', {
        expression: "(function(){ var e=document.getElementById('uitest-result'); return e ? e.textContent : ''; })()",
        returnByValue: true,
      });
      if (r.result && r.result.value) return r.result.value.split(' ;; ').map(s => s.trim()).filter(Boolean);
      await sleep(100);
    }
    // What the page ended up as, in the terms this test cares about — a bare DOM dump says very little,
    // and the interesting failure (the stub's document.open() aborting the parse before this harness ran,
    // so the window has no bridges and no poll) is invisible in one.
    const said = await send('Runtime.evaluate', {
      expression: `(function(){
        var t=window.__atlasTest, b=document.getElementById('atlas-boot-msg');
        return JSON.stringify({
          harness: t ? 'ran (' + t.polls + ' polls, ' + t.calls + ' bridge calls)' : 'NEVER RAN — the parser was aborted before it',
          bridges: typeof window.__atlasFetch, readyState: document.readyState, title: document.title,
          bootMsg: b && b.textContent, sideItems: document.querySelectorAll('#nav .side-item').length,
          report: !!document.getElementById('atlas-data'), htmlChars: document.documentElement.outerHTML.length,
        }, null, 1);
      })()`,
      returnByValue: true,
    });
    throw new Error(`${label}: neither the explorer nor the stub's error card appeared within 30s — the page is ${said.result && said.result.value}`);
  });
}

let firstOpen, reopen, bridgeDown;
try {
  const profile = path.join(work, 'profile');
  firstOpen = await open('first-open', 'serve', profile);
  reopen = await open('reopen', 'serve', profile);
  bridgeDown = await open('bridge-down', 'fail', path.join(work, 'profile-empty'));
} catch (e) {
  console.error('remote-stub-uitest: ' + e.message);
  process.exit(1);
}

const expect = (lines, key, want, label) => {
  const l = lines.find(x => x.startsWith(key + ': '));
  const got = l ? l.slice(key.length + 2) : '(missing)';
  return `${label}${key}: ${got === want ? 'ok' : 'FAIL (expected ' + want + ', got ' + got + ')'}`;
};
const summary = lines => lines.filter(l => !l.startsWith('bridge calls') && !l.startsWith('error card'));
const errorCard = bridgeDown.find(l => l.startsWith('error card: shown')) || '';
const lines = [
  ...summary(firstOpen).map(l => '[first open] ' + l),
  expect(firstOpen, 'bridge calls', String(parts.length), '[first open] '),
  ...summary(reopen).map(l => '[reopen] ' + l),
  // The second open of the same report — a new Chrome on the same profile — comes out of localStorage:
  // not one part crosses the bridge.
  expect(reopen, 'bridge calls', '0', '[reopen] '),
  '[bridge down] the stub shows its error card: ' + (errorCard ? 'ok' : 'FAIL'),
  '[bridge down] the card says what failed: ' + (/bridge down/.test(errorCard) ? 'ok' : 'FAIL ' + errorCard),
];
let failed = 0;
for (const l of lines) {
  const bad = l.includes('FAIL');
  if (bad) failed++;
  console.log(`  ${bad ? 'FAIL' : 'ok  '}  ${l}`);
}
console.log(`\n${lines.length - failed}/${lines.length} checks passed  (${path.basename(reportPath)} → ${parts.length} part(s), stub ${stub.length} chars)`);
if (failed) { console.error(`${failed} remote-stub check(s) failed`); process.exit(1); }
