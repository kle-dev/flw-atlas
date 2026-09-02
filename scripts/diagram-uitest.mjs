#!/usr/bin/env node
/**
 * Runtime test for the diagram element card, driven in headless Chrome.
 *
 * Sibling of explorer-uitest.mjs (which drives the palette and the browse list) and split from it for
 * one reason: this needs a report whose models carry BPMN/CMMN DI, and the miniproject fixture has none.
 *
 * What it guards: the card is a page-level window, NOT a child of the diagram viewport, and ⤢ turns it
 * into an overlay over the whole page. Both are one CSS property away from silently regressing back to
 * "trapped in the diagram" — a `position:absolute`, an `overflow:hidden` ancestor or a lost z-index all
 * look fine in a screenshot of a small diagram and are unusable in a narrow IDE tool window. So the
 * assertions are geometric: the expanded card must be WIDER than the viewport it came from and must
 * escape its bounds, and the scrim must cover the fullscreen diagram modal too.
 *
 * Not covered here: the remembered sizes (w/h docked, bw/bh expanded). Those are written from a
 * ResizeObserver, and --virtual-time-budget delivers only its first callback — the page never paints
 * again, so a resize is never observed. Verify that path against a real-time CDP session instead.
 *
 * Usage:  node scripts/diagram-uitest.mjs <report.explorer.html> [--chrome <path>]
 * Wired up as `./gradlew :cli:diagramUiTest`, which skips when Chrome is not installed.
 */
import fs from 'fs';
import path from 'path';
import os from 'os';
import { execFileSync } from 'child_process';

const args = process.argv.slice(2);
const reportPath = args.find(a => !a.startsWith('--'));
const chromeArg = args.indexOf('--chrome') >= 0 ? args[args.indexOf('--chrome') + 1] : null;
if (!reportPath) {
  console.error('usage: node scripts/diagram-uitest.mjs <report.explorer.html> [--chrome <path>]');
  process.exit(2);
}
const CHROME_CANDIDATES = [
  chromeArg,
  // CHROME_PATH is the conventional override (puppeteer, karma) and is how CI points at whatever the
  // runner image ships, instead of this list having to know every distro's path.
  process.env.CHROME_PATH,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);
const chrome = CHROME_CANDIDATES.find(p => { try { fs.accessSync(p, fs.constants.X_OK); return true; } catch { return false; } });
if (!chrome) {
  // Skipping keeps `./gradlew build` green on a machine without a browser, which is right for a
  // developer. On CI it would be a lie: a green build that never opened the page. ATLAS_REQUIRE_BROWSER
  // _TESTS=1 turns the skip into a failure, so the pipeline cannot claim coverage it does not have.
  if (process.env.ATLAS_REQUIRE_BROWSER_TESTS === '1') {
    console.error('diagram-uitest: FAILED — no Chrome/Chromium found and ATLAS_REQUIRE_BROWSER_TESTS=1');
    console.error('  looked at:', CHROME_CANDIDATES.join(', '));
    process.exit(1);
  }
  console.log('diagram-uitest: skipped — no Chrome/Chromium found');
  process.exit(0);
}

// The probe runs inside the page: it routes to the first model that has a diagram with clickable
// elements, opens the card and walks it through docked → expanded → docked. Result goes into
// document.title, where --dump-dom can retrieve it.
const probe = `<script>
(async function(){
  const log=[], errs=[];
  const say=(k,v)=>log.push(k+': '+v);
  const ok=(k,cond,detail)=>log.push(k+': '+(cond?'ok':'FAIL '+(detail||'')));
  window.addEventListener('error', e=>errs.push(e.message));
  window.addEventListener('unhandledrejection', e=>errs.push('promise: '+(e.reason&&e.reason.message)));
  const tick=ms=>new Promise(r=>setTimeout(r,ms||80));
  const click=(el,opt)=>el.dispatchEvent(new MouseEvent('click', Object.assign({bubbles:true}, opt||{})));
  const esc=()=>document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
  const finish=()=>{
    log.push('uncaught errors: '+(errs.length?('FAIL '+errs.join(' | ')):'none'));
    document.title='UITEST_BEGIN '+log.join(' ;; ')+' UITEST_END';
  };
  try{
    localStorage.removeItem('atlas-dgcard');            // a leftover pref would decide the start state
    // --- find a diagram with clickable elements ---
    let sect=null, view=null, g=null;
    for(const n of nodes.filter(n=>['process','case','decision'].indexOf(n.type)>=0)){
      location.hash='#'+encodeURIComponent(n.id); await tick(300);
      const det=document.getElementById('detail');
      sect=det&&det.querySelector('details.sect[data-sect="diagram"]');
      if(!sect) continue;
      sect.open=true; await tick(250);                  // a kept-closed section only fits on reveal
      view=sect.querySelector('.dgview');
      // a shape, not an edge: edges carry data-el too (their fat invisible twin is the click target)
      g=view&&(view.querySelector('g[data-el][tabindex]')||view.querySelector('[data-el]'));
      if(g){ say('diagram found on', n.id); break; }
    }
    if(!g){ ok('a model with a rendered diagram', false, 'no [data-el] in any .dgview'); return finish(); }
    view.scrollIntoView({block:'center'}); await tick(150);
    // --- shapes are keyboard stops, and the selection joins the link ---
    ok('a shape is focusable', g.getAttribute('tabindex')==='0' && g.getAttribute('role')==='button');
    ok('and named for a screen reader', !!g.getAttribute('aria-label'));
    g.focus(); g.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})); await tick(150);
    ok('Enter on a focused shape opens its card', !!document.querySelector('.dgcard'));
    ok('the element is in the link', decodeURIComponent(location.hash).indexOf('&e='+g.dataset.el)>0, 'hash='+location.hash);
    esc(); await tick(100);
    // a deep link with &e= locates the element on the canvas, not only in its rows
    const nodeId=state.sel; location.hash='#/overview'; await tick(200);
    location.hash='#'+encodeURIComponent(nodeId)+'&e='+encodeURIComponent(g.dataset.el); await tick(400);
    view=document.querySelector('#detail details.sect[data-sect="diagram"] .dgview');
    ok('following a &e= link selects the element on the diagram', !!view && !!view.querySelector('[data-el].dgsel'),
       view?'no .dgsel':'no diagram view');
    g=view&&view.querySelector('[data-el="'+g.dataset.el+'"]'); if(!g){ ok('the element is still there', false); return finish(); }
    view.scrollIntoView({block:'center'}); await tick(150);
    // --- the card: a window on <body>, docked to the viewport corner ---
    click(g); await tick(150);
    let card=document.querySelector('.dgcard');
    ok('clicking an element opens the card', !!card);
    if(!card) return finish();
    ok('the card is a window on <body>', card.parentElement===document.body,
       'parent is .'+card.parentElement.className);
    const vr=()=>view.getBoundingClientRect(), cr=()=>card.getBoundingClientRect();
    const dockW=card.offsetWidth;
    ok('it docks to the diagram top-right corner', Math.abs(cr().right-(vr().right-8))<2,
       'card right '+Math.round(cr().right)+' vs viewport right '+Math.round(vr().right));
    const mx=card.querySelector('.dgcard-max');
    ok('the header offers ⤢', !!mx);
    if(!mx) return finish();
    // --- expanded: an overlay over the page, not a card inside the drawing ---
    click(mx); await tick(150);
    ok('⤢ expands the card', card.classList.contains('big'));
    const scrim=document.querySelector('.dgscrim');
    ok('a scrim is drawn behind it', !!scrim);
    const c=cr(), v=vr();
    ok('the overlay is wider than the diagram viewport', c.width>v.width,
       Math.round(c.width)+'px vs '+Math.round(v.width)+'px');
    ok('the overlay escapes the viewport bounds', c.left<v.left||c.right>v.right,
       'card '+Math.round(c.left)+'–'+Math.round(c.right)+' inside viewport '+Math.round(v.left)+'–'+Math.round(v.right));
    ok('the overlay is centered in the window', Math.abs((c.left+c.right)/2-window.innerWidth/2)<3);
    ok('the overlay stays inside the viewport height', c.height<=window.innerHeight*0.92+1,
       Math.round(c.height)+'px of '+window.innerHeight+'px');
    ok('the overlay hugs its content instead of leaving a white wall',
       card.scrollHeight<=card.clientHeight+2, 'content '+card.scrollHeight+' vs box '+card.clientHeight);
    ok('the card paints above its own scrim',
       (+getComputedStyle(card).zIndex)>(+getComputedStyle(scrim).zIndex));
    ok('the scrim covers the page beside the overlay',
       document.elementFromPoint(4, Math.round(window.innerHeight/2))===scrim);
    ok('the overlay can still be dragged bigger', getComputedStyle(card).resize==='both');
    ok('overlay mode is remembered', JSON.parse(localStorage.getItem('atlas-dgcard')||'{}').big===true);
    // --- it survives clicking through elements, and comes back on demand ---
    hideDgCard(); await tick(80);
    click(g); await tick(150);
    card=document.querySelector('.dgcard');
    ok('the next element opens expanded too', !!card&&card.classList.contains('big'));
    click(document.querySelector('.dgscrim')); await tick(150);
    ok('clicking the scrim shrinks it back', !card.classList.contains('big'));
    ok('the scrim goes with it', !document.querySelector('.dgscrim'));
    ok('the docked size comes back, not the overlay width', card.offsetWidth===dockW,
       card.offsetWidth+'px vs '+dockW+'px');
    ok('and it re-docks to the corner', Math.abs(cr().right-(vr().right-8))<2);
    // --- Escape unwinds one step at a time ---
    click(card.querySelector('.dgcard-max')); await tick(150);
    esc(); await tick(150);
    ok('Escape shrinks the overlay first', !card.classList.contains('big')&&card.isConnected);
    esc(); await tick(150);
    ok('Escape again closes the card', !document.querySelector('.dgcard'));
    // --- and all of it works over the fullscreen diagram, whose overlay owns a lower layer ---
    const full=sect.querySelector('.dgbar button[data-z="full"]');
    if(!full){ say('note','no full-screen button on this diagram'); return finish(); }
    click(full); await tick(300);
    const mview=document.getElementById('dgmodalview');
    const mg=mview&&mview.querySelector('[data-el]');
    if(!mg){ ok('the fullscreen diagram is clickable', false, 'no [data-el] in #dgmodalview'); return finish(); }
    click(mg); await tick(180);
    const mc=document.querySelector('.dgcard');
    ok('the card opens over the fullscreen diagram', !!mc);
    if(!mc) return finish();
    click(mc.querySelector('.dgcard-max')); await tick(180);
    const ms=document.querySelector('.dgscrim');
    const modalZ=+getComputedStyle(document.getElementById('dgmodal')).zIndex;
    ok('the scrim covers the fullscreen modal as well',
       !!ms&&(+getComputedStyle(ms).zIndex)>modalZ,
       'scrim '+(ms&&getComputedStyle(ms).zIndex)+' vs modal '+modalZ);
    const mr=mc.getBoundingClientRect();
    ok('the overlay centers on the window, not inside the modal panel',
       Math.abs((mr.left+mr.right)/2-window.innerWidth/2)<3);
  }catch(e){ log.push('FAIL threw: '+e.message); }
  finish();
})();
</script>`;

const html = fs.readFileSync(reportPath, 'utf8');
if (!html.includes('</body>')) { console.error('diagram-uitest: not an explorer report'); process.exit(2); }
const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'atlas-dguitest-')), 'report.html');
fs.writeFileSync(tmp, html.replace('</body>', probe + '</body>'));

let dom;
try {
  dom = execFileSync(chrome, [
    '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    // A narrow window on purpose: that is where a card trapped in the diagram becomes unusable.
    '--window-size=980,760', '--virtual-time-budget=20000', '--dump-dom', 'file://' + tmp,
  ], { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, timeout: 120000 });
} catch (e) {
  console.error('diagram-uitest: Chrome failed to run —', e.message);
  process.exit(1);
}

const m = dom.match(/UITEST_BEGIN([\s\S]*?)UITEST_END/);
if (!m) {
  console.error('diagram-uitest: the probe never finished — the page most likely threw during boot.');
  const t = dom.match(/<title>([\s\S]*?)<\/title>/);
  console.error('  title was:', t ? t[1].slice(0, 300) : '(none)');
  process.exit(1);
}
const lines = m[1].split(' ;; ').map(s => s.trim()).filter(Boolean);
let failed = 0;
for (const l of lines) {
  const bad = l.includes('FAIL');
  if (bad) failed++;
  console.log(`  ${bad ? 'FAIL' : 'ok  '}  ${l}`);
}
console.log(`\n${lines.length - failed}/${lines.length} checks passed  (${path.basename(reportPath)})`);
if (failed) { console.error(`${failed} diagram UI check(s) failed`); process.exit(1); }
