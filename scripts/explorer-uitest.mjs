#!/usr/bin/env node
/**
 * Runtime smoke test for the generated explorer page, driven in headless Chrome.
 *
 * Why this exists: explorer.js is a 4000-line browser asset. `node --check` only proves it parses, and
 * RenderersSmokeTest only proves certain strings are present — neither catches a handler that throws at
 * runtime. A real case: a refactor left the result-row click handler referencing a variable that had
 * been renamed. The palette still opened, still filtered, still worked with Enter; only clicking a row
 * silently did nothing, because the ReferenceError killed the navigation after the panel had closed.
 * Nothing in the build noticed. This does.
 *
 * It asserts the load-bearing interactions rather than appearance: the page boots without errors, every
 * way of activating a hit works (click, ⌘-click, Shift-click, Enter), the facets narrow, and the browse
 * list navigates. Any uncaught error anywhere in the run fails the test.
 *
 * Usage:  node scripts/explorer-uitest.mjs <report.explorer.html> [--chrome <path>]
 * Wired up as `./gradlew :cli:explorerUiTest`, which skips when Chrome is not installed.
 */
import fs from 'fs';
import path from 'path';
import os from 'os';
import { execFileSync } from 'child_process';

const args = process.argv.slice(2);
const reportPath = args.find(a => !a.startsWith('--'));
const chromeArg = args.indexOf('--chrome') >= 0 ? args[args.indexOf('--chrome') + 1] : null;
if (!reportPath) {
  console.error('usage: node scripts/explorer-uitest.mjs <report.explorer.html> [--chrome <path>]');
  process.exit(2);
}
const CHROME_CANDIDATES = [
  chromeArg,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);
const chrome = CHROME_CANDIDATES.find(p => { try { fs.accessSync(p, fs.constants.X_OK); return true; } catch { return false; } });
if (!chrome) {
  console.log('explorer-uitest: skipped — no Chrome/Chromium found');
  process.exit(0);
}

// The probe runs inside the page. It walks a list of steps on a timer (each search re-render is debounced
// by 120ms), collects one line per assertion, and parks the result in document.title where --dump-dom
// can retrieve it. Failures are marked FAIL and counted by the harness below.
const probe = `<script>
(function(){
  const log=[], errs=[];
  const say=(k,v)=>log.push(k+': '+v);
  const ok=(k,cond,detail)=>log.push(k+': '+(cond?'ok':'FAIL '+(detail||'')));
  window.addEventListener('error', e=>errs.push(e.message));
  window.addEventListener('unhandledrejection', e=>errs.push('promise: '+(e.reason&&e.reason.message)));
  const rows=()=>[...document.querySelectorAll('#palresults .pal-item')];
  const click=(el,opt)=>el.dispatchEvent(new MouseEvent('click', Object.assign({bubbles:true}, opt||{})));
  const key=k=>palq.dispatchEvent(new KeyboardEvent('keydown', {key:k, bubbles:true}));
  const type=q=>{ palq.value=q; palq.dispatchEvent(new Event('input')); };
  const steps=[];

  // --- boot ---
  steps.push(()=>{
    ok('boot rendered the sidebar', document.querySelectorAll('#nav .side-item').length>0);
    // The overlay fades for 400ms before it is removed, so "done" is either state — what matters is
    // that boot got far enough to dismiss it rather than dying behind it.
    const bootEl=document.getElementById('atlas-boot');
    ok('boot overlay dismissed', !bootEl || bootEl.classList.contains('boot--done'));
    openPalette(); type('customer');
  });
  // --- plain click must navigate ---
  let picked='';
  steps.push(()=>{
    ok('query returned hits', rows().length>0, 'no rows for "customer"');
    picked=rows()[0].querySelector('.nm').textContent;
    ok('top hit is preselected', !!document.querySelector('.pal-item.sel'));
    ok('hit is highlighted', !!document.querySelector('.pal-item .nm mark.hl'));
    click(rows()[0]);
  });
  steps.push(()=>{
    ok('click closed the palette', pal.hidden);
    ok('click navigated', location.hash.indexOf('#')===0 && location.hash.length>1, 'hash still empty');
    ok('click opened the node', (document.getElementById('detail').textContent||'').indexOf(picked)>=0,
       'detail panel does not mention '+picked);
  });
  // --- mod-click marks, shift-click extends, Enter opens the set ---
  steps.push(()=>{ openPalette(); type('customer'); });
  steps.push(()=>{ click(rows()[1], {metaKey:true, ctrlKey:true}); });
  steps.push(()=>{
    ok('mod-click marked a row', document.querySelectorAll('.pal-item.mark').length===1);
    ok('mod-click kept the palette open', !pal.hidden);
    click(rows()[3], {shiftKey:true});
  });
  steps.push(()=>{
    ok('shift-click extended the range', document.querySelectorAll('.pal-item.mark').length===3,
       'marked '+document.querySelectorAll('.pal-item.mark').length);
    const before=document.querySelectorAll('#dtabs .dtab').length;
    window.__tabsBefore=before;
    key('Enter');
  });
  steps.push(()=>{
    ok('Enter opened the marked hits as tabs',
       document.querySelectorAll('#dtabs .dtab').length>window.__tabsBefore);
  });
  // --- plain Enter on a fresh query opens the best hit ---
  steps.push(()=>{ openPalette(); type('priority'); });
  steps.push(()=>{ key('Enter'); });
  steps.push(()=>{
    ok('Enter navigated', decodeURIComponent(location.hash).indexOf('priority')>=0,
       'hash='+location.hash);
  });
  // --- facets narrow, in two tiers ---
  steps.push(()=>{ openPalette(); type('customer'); });
  steps.push(()=>{
    const secs=[...document.querySelectorAll('#palfacets [data-facet]')];
    ok('section chips rendered', secs.length>1, 'only '+secs.length);
    const groups=[...document.querySelectorAll('#palresults .pal-group')].map(e=>e.textContent);
    const iM=groups.indexOf('Models'), iC=groups.indexOf('Code');
    ok('Models is listed before Code', iM<0||iC<0||iM<iC, groups.join(' > '));
    const code=secs.find(b=>/Code/.test(b.textContent));
    if(code) click(code); else say('note','no Code section in this report');
  });
  steps.push(()=>{
    const before=rows().length;
    const types=[...document.querySelectorAll('#palfacets [data-type]')];
    ok('category chips appeared for the section', types.length>1, 'only '+types.length);
    const one=types.filter(b=>b.dataset.type)[0];
    window.__before=before;
    if(one) click(one);
  });
  steps.push(()=>{
    ok('category chip narrowed the list', rows().length<=window.__before,
       rows().length+' vs '+window.__before);
    ok('selection followed into the narrowed list', !!document.querySelector('.pal-item.sel'));
    closePalette();
  });
  // --- the browse list: navigation and the bridge to the palette ---
  steps.push(()=>{
    const c=[...document.querySelectorAll('#nav .side-item')].find(e=>/Data objects/.test(e.textContent));
    if(c) click(c); else say('note','no Data objects category');
  });
  steps.push(()=>{
    const it=document.querySelector('#listitems .item[data-id]');
    ok('list rendered rows', !!it);
    if(it) click(it);
  });
  steps.push(()=>{
    ok('list click opened the node', (document.getElementById('detail').textContent||'').length>40);
    const lf=document.getElementById('lf');
    ok('list filter present', !!lf);
    if(lf){ lf.value='zzzznope'; lf.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    ok('a filter matching nothing renders no rows',
       document.querySelectorAll('#listitems .item[data-id]').length===0);
    const lf=document.getElementById('lf');
    if(lf){ lf.value='customer'; lf.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    // Standing in a category that cannot hold the term, the bridge must offer the wider search.
    const b=document.getElementById('lwiderbtn');
    say('bridge offered', b?('yes — '+b.textContent.trim()):'no (term also matches in this category)');
  });
  // --- reference links obey the tab contract on EVERY surface, not just the detail panel ---
  // Counted off state.tabs rather than the DOM: the strip is hidden below two tabs and hidden again
  // on the #/checks route, so the rendered markup cannot answer "how many tabs are open".
  steps.push(()=>{
    closeOtherTabs();                              // a known floor, well clear of the 12-tab cap
    location.hash='/checks';
  });
  steps.push(()=>{
    const chips=[...document.querySelectorAll('#view-checks [data-id]')];
    ok('the checks view rendered reference links', chips.length>0, 'no [data-id] on #/checks');
    // The health counts are computed in :core (Findings.kt) and shipped in the payload, so that the
    // page, the Markdown artifacts and the CLI status line cannot disagree. If that wiring breaks, the
    // page silently reports zero findings — assert it is actually reading them.
    const declared=(DATA.checks&&DATA.checks.open)||0;
    ok('the page takes its finding count from the payload', INSIGHTS.checksOpen===declared,
       'INSIGHTS.checksOpen='+INSIGHTS.checksOpen+' vs DATA.checks.open='+declared);
    ok('the fixture reports the findings it deliberately contains', declared>0,
       'no findings in the payload at all');
    window.__refTabs=state.tabs.slice();
    window.__refHash=location.hash;
    window.__refSel=state.sel;
    if(chips.length) click(chips[0], {metaKey:true, ctrlKey:true});
  });
  steps.push(()=>{
    ok('mod-click on a checks reference opened a background tab',
       state.tabs.length===window.__refTabs.length+1,
       state.tabs.length+' tabs vs '+window.__refTabs.length);
    ok('mod-click stayed on the checks route', location.hash===window.__refHash,
       'hash moved to '+location.hash);
    ok('a background open reports itself while the strip is hidden',
       document.getElementById('toast').classList.contains('show'));
  });
  // A plain click from a route that has no tab of its own must APPEND. Overwriting the active tab
  // there is a silent loss: the strip is not on screen, so nothing shows which node just went.
  steps.push(()=>{
    window.__refTabs=state.tabs.slice();
    const fresh=[...document.querySelectorAll('#view-checks [data-id]')]
      .filter(c=>window.__refTabs.indexOf(dec(c.dataset.id))<0);
    if(fresh.length) click(fresh[fresh.length-1]);
    else say('note','every checks reference is already open — append not exercised');
  });
  steps.push(()=>{
    ok('a click from the checks route appends a tab instead of overwriting one',
       state.tabs.length===window.__refTabs.length+1,
       state.tabs.length+' tabs vs '+window.__refTabs.length);
    ok('no previously open tab was lost',
       window.__refTabs.every(id=>state.tabs.indexOf(id)>=0),
       'was ['+window.__refTabs.join(', ')+'] now ['+state.tabs.join(', ')+']');
    ok('the clicked node is the one on screen', state.tabs[state.tab]===state.sel,
       'tab '+state.tab+' holds '+state.tabs[state.tab]+' but sel is '+state.sel);
  });
  // Following a chip inside the detail panel still travels in place — the browser convention the
  // strip is built on. This is the case the append fix must NOT have changed.
  steps.push(()=>{
    window.__refTabs=state.tabs.slice();
    const chip=document.querySelector('#detail .nc[data-id], #detail .vlink[data-id]');
    if(chip) click(chip); else say('note','no reference chip on this node');
  });
  steps.push(()=>{
    ok('a plain click inside the detail panel reuses the active tab',
       state.tabs.length===window.__refTabs.length,
       state.tabs.length+' tabs vs '+window.__refTabs.length);
  });

  // --- the unused-variables report (#/variables) ---
  // The verdict is computed in :core and only stamped onto the nodes, so a broken payload allowlist
  // renders an empty page with correct-looking counts and no error anywhere. Assert the rows exist and
  // that their number is exactly what the payload declares.
  steps.push(()=>{ closeOtherTabs(); location.hash='/variables'; });
  steps.push(()=>{
    const view=document.getElementById('view-variables');
    ok('the unused-variables view is on screen', view && !view.hidden);
    const declared=((DATA.checks||{}).unusedVars||0)+((DATA.checks||{}).unreadInputs||0);
    const rows=[...document.querySelectorAll('#view-variables [data-varrow]')];
    ok('every declared finding has a row', rows.length===declared,
       rows.length+' rows vs '+declared+' declared in the payload');
    ok('the fixture reports the unused variables it deliberately contains', declared>0,
       'no unused-variable findings in the payload at all');
    // Each row must name the write to delete: the construct in Design's words, and the model.
    const withVia=rows.filter(r=>r.querySelector('.varvias .term')).length;
    ok('each row names how the variable is written', withVia===rows.length,
       withVia+' of '+rows.length+' rows carry a write construct');
    const withModel=rows.filter(r=>r.querySelector('.nodechips .nc[data-id]')).length;
    ok('each row links the model that writes it', withModel===rows.length,
       withModel+' of '+rows.length+' rows link a model');
    // The caveat block is what keeps the report honest about its own limits.
    ok('the page states what Atlas cannot see',
       (document.getElementById('chk-varcaveat')||{}) && document.querySelectorAll('#view-variables .varwhy li').length>0);
    window.__varTabs=state.tabs.slice();
    window.__varHash=location.hash;
    if(rows.length) click(rows[0].querySelector('[data-id]'), {metaKey:true, ctrlKey:true});
  });
  steps.push(()=>{
    ok('mod-click on a variable opened a background tab',
       state.tabs.length===window.__varTabs.length+1,
       state.tabs.length+' tabs vs '+window.__varTabs.length);
    ok('mod-click stayed on the variables route', location.hash===window.__varHash,
       'hash moved to '+location.hash);
  });
  steps.push(()=>{
    // The filter narrows on the write construct; a term matching nothing must empty the list rather
    // than silently ignoring the filter.
    const pf=document.querySelector('#view-variables .pf');
    ok('the variables filter is present', !!pf);
    if(pf){ pf.value='zzzznope'; pf.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const shown=[...document.querySelectorAll('#view-variables [data-varrow]')].filter(r=>!r.hidden);
    ok('a filter matching nothing hides every row', shown.length===0, shown.length+' rows still shown');
  });

  let i=0;(function run(){
    if(i>=steps.length){
      log.push('uncaught errors: '+(errs.length?('FAIL '+errs.join(' | ')):'none'));
      document.title='UITEST_BEGIN '+log.join(' ;; ')+' UITEST_END';
      return;
    }
    try{ steps[i++](); }catch(e){ log.push('FAIL threw in step '+i+': '+e.message); }
    setTimeout(run, 300);
  })();
})();
</script>`;

const html = fs.readFileSync(reportPath, 'utf8');
if (!html.includes('</body>')) { console.error('explorer-uitest: not an explorer report'); process.exit(2); }
const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'atlas-uitest-')), 'report.html');
fs.writeFileSync(tmp, html.replace('</body>', probe + '</body>'));

let dom;
try {
  dom = execFileSync(chrome, [
    '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    '--virtual-time-budget=20000', '--dump-dom', 'file://' + tmp,
  ], { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, timeout: 120000 });
} catch (e) {
  console.error('explorer-uitest: Chrome failed to run —', e.message);
  process.exit(1);
}

const m = dom.match(/UITEST_BEGIN([\s\S]*?)UITEST_END/);
if (!m) {
  console.error('explorer-uitest: the probe never finished — the page most likely threw during boot.');
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
if (failed) { console.error(`${failed} explorer UI check(s) failed`); process.exit(1); }
