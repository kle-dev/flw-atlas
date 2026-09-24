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
 * the IDE. A third open, bridge failing and profile empty, must end in the stub's own error card. Then the
 * two things a large report depends on: a stall (one part never answered) must end in a card whose Retry
 * asks only for what is missing and boots the explorer, and a synthetic ~18 MB report, served slowly, must
 * show progress that only ever grows, boot, and come out of the cache on the next open.
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
import zlib from 'zlib';
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

// ---- what RemoteExplorerPage.kt does: hash the file, gzip it, Base64 it, split it into parts, stamp the stub ----
const PART_CHARS = 256 * 1024;
const reportBytes = fs.readFileSync(reportPath);
if (!reportBytes.toString('utf8').includes('id="atlas-data"')) { console.error('remote-stub-uitest: not an explorer report'); process.exit(2); }
const template = fs.readFileSync(stubPath, 'utf8');
for (const ph of ['__ATLAS_HASH__', '__ATLAS_PARTS__', '__ATLAS_SIZE__', '__ATLAS_WIRE__']) {
  if (!template.includes(ph)) { console.error(`remote-stub-uitest: the stub template has lost its ${ph} placeholder`); process.exit(1); }
}
function stamp(bytes) {
  const hash = crypto.createHash('sha256').update(bytes).digest('hex');
  const wire = zlib.gzipSync(bytes).toString('base64');
  const parts = [];
  for (let i = 0; i < wire.length; i += PART_CHARS) parts.push(wire.slice(i, i + PART_CHARS));
  const stub = template.replace('__ATLAS_HASH__', hash).replace('__ATLAS_PARTS__', String(parts.length))
    .replace('__ATLAS_SIZE__', (bytes.length / 1e6).toFixed(1) + ' MB').replace('__ATLAS_WIRE__', String(wire.length));
  if (stub.includes('__ATLAS_')) { console.error('remote-stub-uitest: a placeholder survived stamping'); process.exit(1); }
  return { parts, stub, bytes: bytes.length, wire: wire.length };
}
const small = stamp(reportBytes);
// A large project's report, as far as the transfer can tell: the same page with ~17 MB of island-like JSON
// in a comment the explorer never reads — it has to be carried, unpacked and written all the same.
const filler = [];
for (let i = 0, n = 0; n < 17_000_000; i++) {
  const l = `{"id":"process:DEMO-P${i}","type":"process","label":"Demo process ${i}","file":"processes/demo-${i % 977}.bpmn","data":{"line":${(i * 7919) % 4099},"refs":${i % 13}}},\n`;
  filler.push(l); n += l.length;
}
const big = stamp(Buffer.from(reportBytes.toString('utf8').replace('</body>', '<!-- ' + filler.join('') + ' --></body>'), 'utf8'));

// ---- the IDE, as far as the stub can tell: the bridge the editor injects after the stub has loaded ----
// The verdict is appended to the *report's* body once it has booted (or to the stub's when it gave up).
// The poll timer belongs to the window and survives document.open(); so does window.__atlasTest.
const harness = (mode, parts) => `<script>
(function(){
  var PARTS=${JSON.stringify(parts).replace(/<\//g, '<\\/')};
  var MODE=${JSON.stringify(mode)};
  window.__atlasTest={calls:0,polls:0,asked:[],bars:[],texts:[],stallCard:'',afterRetry:0};
  // stall: part 2 is never answered the first time it is asked for, and the fuse is short
  if(MODE==='stall') window.__atlasStubStallMs=800;
  // The editor installs all three bridges together; the page marks itself as IDE-hosted only when
  // __atlasOpen exists, so the stand-in has to carry it for that check to mean anything.
  window.__atlasOpen=function(){}; window.__atlasCopy=function(){};
  window.__atlasFetch=function(i, ok, fail){
    var t=window.__atlasTest; t.calls++; t.asked.push(i);
    if(t.stallCard) t.afterRetry++; else if(i===1) t.beforeCard=(t.beforeCard||0)+1;
    if(MODE==='stall' && i===1 && !t.stallCard) return;
    setTimeout(function(){
      if(MODE==='fail'){ fail(1,'bridge down (test)'); return; }
      ok(PARTS[i]);
      var bar=document.getElementById('atlas-boot-bar'), p=document.getElementById('atlas-boot-prog');
      if(bar) t.bars.push(parseFloat(bar.style.width)||0);
      if(p) t.texts.push(p.textContent);
    }, MODE==='slow'?60:5);
  };
  window.dispatchEvent(new Event('atlas-ide-bridge'));
  (function poll(){
    var t=window.__atlasTest; t.polls++;
    var nav=document.querySelector('#nav .side-item'), failCard=document.querySelector('.boot-fail');
    var retry=document.getElementById('atlas-boot-retry');
    if(MODE==='stall' && retry && !t.stallCard){ t.stallCard=failCard.textContent; retry.click(); setTimeout(poll, 50); return; }
    if(!nav && !failCard){ setTimeout(poll, 50); return; }
    var lines=[];
    lines.push('page booted: '+(nav?'ok':'FAIL '+(failCard?'stub error card instead: '+failCard.textContent.slice(0,160):'nothing rendered')));
    lines.push('error card: '+(failCard?'shown — '+failCard.textContent.slice(0,160):'none'));
    lines.push('bridge calls: '+t.calls);
    lines.push('ide class: '+(document.documentElement.classList.contains('ide')?'ok':'FAIL'));
    lines.push('theme: '+document.documentElement.dataset.theme);
    lines.push('url kept: '+(location.search.indexOf('ideTheme=dark')>=0?'ok':'FAIL '+location.href));
    lines.push('bridges kept: '+(typeof window.__atlasFetch==='function'?'ok':'FAIL'));
    lines.push('stall card: '+(t.stallCard||'none'));
    lines.push('asked after retry: '+t.afterRetry);
    lines.push('part 2 asked before the card: '+(t.beforeCard||0));
    lines.push('bars: '+t.bars.join(','));
    lines.push('last text: '+(t.texts[t.texts.length-1]||''));
    lines.push('mid text: '+(t.texts[Math.floor(t.texts.length/2)]||''));
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

/** Open the stamped stub of [report] (+ the harness in [mode]) on [profile]; returns the harness's lines. */
async function open(label, mode, profile, report = small) {
  const page = path.join(work, label + '.html');
  fs.writeFileSync(page, report.stub.replace('</body>', harness(mode, report.parts) + '</body>'));
  return withChrome(profile, async send => {
    await send('Page.navigate', { url: 'file://' + page + '?ideTheme=dark' });
    const deadline = Date.now() + 60000;
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
    throw new Error(`${label}: neither the explorer nor the stub's error card appeared within 60s — the page is ${said.result && said.result.value}`);
  });
}

let firstOpen, reopen, bridgeDown, stalled, bigFirst, bigReopen;
try {
  const profile = path.join(work, 'profile');
  firstOpen = await open('first-open', 'serve', profile);
  reopen = await open('reopen', 'serve', profile);
  bridgeDown = await open('bridge-down', 'fail', path.join(work, 'profile-empty'));
  stalled = await open('stall', 'stall', path.join(work, 'profile-stall'));
  const bigProfile = path.join(work, 'profile-big');
  bigFirst = await open('big-first', 'slow', bigProfile, big);
  bigReopen = await open('big-reopen', 'serve', bigProfile, big);
} catch (e) {
  console.error('remote-stub-uitest: ' + e.message);
  process.exit(1);
}

const val = (lines, key) => { const l = lines.find(x => x.startsWith(key + ': ')); return l ? l.slice(key.length + 2) : '(missing)'; };
const expect = (lines, key, want, label) => {
  const got = val(lines, key);
  return `${label}${key}: ${got === want ? 'ok' : 'FAIL (expected ' + want + ', got ' + got + ')'}`;
};
const DIAG = ['bridge calls', 'error card', 'stall card', 'asked after retry', 'part 2 asked before the card', 'bars', 'last text', 'mid text'];
const summary = lines => lines.filter(l => !DIAG.some(k => l.startsWith(k + ':')));
const errorCard = bridgeDown.find(l => l.startsWith('error card: shown')) || '';
const bars = val(bigFirst, 'bars').split(',').map(Number);
const midText = val(bigFirst, 'mid text');
const lines = [
  ...summary(firstOpen).map(l => '[first open] ' + l),
  expect(firstOpen, 'bridge calls', String(small.parts.length), '[first open] '),
  ...summary(reopen).map(l => '[reopen] ' + l),
  // The second open of the same report — a new Chrome on the same profile — comes out of localStorage:
  // not one part crosses the bridge.
  expect(reopen, 'bridge calls', '0', '[reopen] '),
  '[bridge down] the stub shows its error card: ' + (errorCard ? 'ok' : 'FAIL'),
  '[bridge down] the card says what failed: ' + (/bridge down/.test(errorCard) ? 'ok' : 'FAIL ' + errorCard),
  // A part that is never answered, even when asked again, ends in a card that says what arrived, and Retry
  // asks for that part only.
  '[stall] the card says what arrived: ' + (new RegExp('1 of ' + small.parts.length + ' parts').test(val(stalled, 'stall card')) ? 'ok' : 'FAIL ' + val(stalled, 'stall card')),
  // a lost answer is asked for once more on its own before the card appears
  expect(stalled, 'part 2 asked before the card', '2', '[stall] '),
  expect(stalled, 'asked after retry', '1', '[stall] '),
  ...summary(stalled).map(l => '[stall → retry] ' + l),
  // ~18 MB: progress on every part, only ever growing, then booted — and the next open transfers nothing.
  `[big] ${(big.bytes / 1e6).toFixed(1)} MB travels as ${big.parts.length} parts: ` + (big.parts.length >= 5 ? 'ok' : 'FAIL'),
  '[big] a progress step per part: ' + (bars.length === big.parts.length ? 'ok' : 'FAIL ' + bars.length + ' of ' + big.parts.length),
  '[big] progress only grows, to 100%: ' + (bars.every((b, i) => i === 0 || b >= bars[i - 1]) && bars[bars.length - 1] === 100 ? 'ok' : 'FAIL ' + bars.join(',')),
  '[big] the line says how much and how fast: ' + (/ of .*\/s · about /.test(midText) ? 'ok' : 'FAIL ' + midText),
  ...summary(bigFirst).map(l => '[big] ' + l),
  '[big] it is small enough to keep: ' + (big.wire <= 4000000 ? 'ok' : 'FAIL ' + big.wire + ' chars'),
  expect(bigReopen, 'bridge calls', '0', '[big reopen] '),
  ...summary(bigReopen).map(l => '[big reopen] ' + l),
];
let failed = 0;
for (const l of lines) {
  const bad = l.includes('FAIL');
  if (bad) failed++;
  console.log(`  ${bad ? 'FAIL' : 'ok  '}  ${l}`);
}
console.log(`\n${lines.length - failed}/${lines.length} checks passed  (${path.basename(reportPath)} → ${small.parts.length} part(s), stub ${small.stub.length} chars; big → ${big.parts.length} parts)`);
if (failed) { console.error(`${failed} remote-stub check(s) failed`); process.exit(1); }
