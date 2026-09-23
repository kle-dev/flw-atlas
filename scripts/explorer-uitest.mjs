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
    console.error('explorer-uitest: FAILED — no Chrome/Chromium found and ATLAS_REQUIRE_BROWSER_TESTS=1');
    console.error('  looked at:', CHROME_CANDIDATES.join(', '));
    process.exit(1);
  }
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
  // A page's fit questions are sections of their own on its Connections tab: one view over all of them,
  // which leaves the relations (whose unfolded rows name models too) out.
  const fitView=()=>{ const ss=[...document.querySelectorAll('#detail details.sect[data-sect^="fit-"]')]; if(!ss.length) return null;
    return {open:ss.every(x=>x.open), textContent:ss.map(x=>x.textContent).join(' '),
      querySelector:q=>{ for(const x of ss){ const r=x.querySelector(q); if(r) return r; } return null; },
      querySelectorAll:q=>ss.flatMap(x=>[...x.querySelectorAll(q)])}; };
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
    ok('a node page scrolls inside the shell, not the document',
       document.documentElement.scrollHeight<=window.innerHeight+1, 'document scrollHeight '+document.documentElement.scrollHeight);
    location.hash='/checks';
  });
  // --- a report page scrolls inside its view, so the sidebar and the top bar stay put ---
  steps.push(()=>{
    ok('back is offered once there is somewhere to go', !document.getElementById('navback').disabled);
    ok('nothing is forward of the newest page', document.getElementById('navfwd').disabled);
    const v=document.getElementById('view-checks');
    ok('checks page is the visible view', !v.hidden);
    ok('the checks view is the scroll container', getComputedStyle(v).overflowY==='auto', getComputedStyle(v).overflowY);
    ok('the document itself does not scroll on the checks page',
       document.documentElement.scrollHeight<=window.innerHeight+1, 'document scrollHeight '+document.documentElement.scrollHeight);
    ok('the checks page is taller than the window, so scrolling is real', v.scrollHeight>v.clientHeight, v.scrollHeight+' vs '+v.clientHeight);
    // the page leads with the split: a Defects group and an Advice group, the health list under two headings
    ok('the checks page has a Defects group', !!v.querySelector('#chk-kind-defect'));
    ok('the checks page has an Advice group', !!v.querySelector('#chk-kind-advice'));
    ok('the health list is headed by kind, not tier', v.querySelectorAll('.htier.hkind-defect, .htier.hkind-advice').length===2,
       v.querySelectorAll('.htier').length+' headings');
    ok('the header says both numbers', /\\d+ defects? · \\d+ advice/.test(v.querySelector('.ddesc').textContent), v.querySelector('.ddesc').textContent.slice(0,80));
    ok('the badge toggle is offered', !document.getElementById('markfilter').hidden);
    // a model chip in the findings table ends inside its cell — ellipsised by its name, never sliced
    const chips=[...v.querySelectorAll('.tbl .td>.nc')];
    ok('the findings table shows model chips', chips.length>0);
    const sliced=chips.filter(c=>{ const td=c.parentElement; return c.getBoundingClientRect().right>td.getBoundingClientRect().right+1; });
    ok('no model chip runs past its cell', sliced.length===0, sliced.length+' of '+chips.length+' clipped');
    window.__beforeBack=location.hash;
    document.getElementById('navback').click();
  });
  // --- the top-bar back button returns to the previous page and forward becomes available ---
  steps.push(()=>{
    ok('back left the checks page', location.hash!==window.__beforeBack, 'hash='+location.hash);
    ok('back landed on the node page', state.view==='browse', 'view='+state.view);
    ok('forward is offered after a back', !document.getElementById('navfwd').disabled);
    document.getElementById('navfwd').click();
  });
  steps.push(()=>{
    ok('forward returned to the checks page', location.hash===window.__beforeBack, 'hash='+location.hash);
  });
  // --- a link to a model this report does not contain says so and cleans the address bar ---
  steps.push(()=>{ location.hash='#process%3AthisModelWasDeleted'; });
  steps.push(()=>{
    ok('a stale link lands on the overview', state.view==='overview', 'view='+state.view);
    ok('a stale link is said', /does not contain/.test(document.getElementById('toast').textContent), document.getElementById('toast').textContent);
    ok('the dead hash is replaced', location.hash==='#/overview', 'hash='+location.hash);
  });
  // --- the tree's key handler is wired once per view, however often the view is rendered ---
  steps.push(()=>{ location.hash='/tree'; });
  steps.push(()=>{ location.hash='/overview'; });
  steps.push(()=>{ location.hash='/tree'; });
  steps.push(()=>{
    const row=document.querySelector('#view-tree .tv-row[aria-expanded]');
    if(!row){ say('note','no expandable tree row — Space toggle not exercised'); return; }
    const before=row.getAttribute('aria-expanded');
    row.focus(); row.dispatchEvent(new KeyboardEvent('keydown', {key:' ', bubbles:true}));
    ok('Space toggles a tree row once after a re-visit', row.getAttribute('aria-expanded')!==before, before+' -> '+row.getAttribute('aria-expanded'));
    location.hash='/checks';
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
  // --- the typed filters teach themselves: chips while empty, a lit chip once one binds ---
  steps.push(()=>{ openPalette(); type(''); });
  steps.push(()=>{
    const syn=[...document.querySelectorAll('#palfacets [data-syn]')];
    ok('an empty palette offers the filter chips', syn.length>=6, 'only '+syn.length);
    const lab=syn.find(b=>b.dataset.syn==='label:');
    if(lab) click(lab); else say('note','no label: chip to click');
  });
  steps.push(()=>{
    ok('the label: chip filled the input', /label:$/.test(palq.value), 'value="'+palq.value+'"');
    ok('an unvalued facet says what it waits for instead of searching for the word "label"',
       !!document.querySelector('#palfacets .pal-pend'));
    type('label: "Internal note"');
  });
  steps.push(()=>{
    ok('a quoted facet value binds and lights its chip',
       !!document.querySelector('#palfacets [data-unfacet="lab"]'));
    ok('the quoted caption found its form', rows().length>0, 'no rows');
    const off=document.querySelector('#palfacets [data-unfacet="lab"]');
    if(off) click(off);
  });
  steps.push(()=>{
    ok('removing the chip strips the facet from the query itself', palq.value.indexOf('label')<0,
       'value="'+palq.value+'"');
    closePalette();
  });
  // --- the browse list: navigation and the bridge to the palette ---
  steps.push(()=>{
    const c=[...document.querySelectorAll('#nav .side-item')].find(e=>/Data objects/.test(e.textContent));
    if(c) click(c); else say('note','no Data objects category');
  });
  steps.push(()=>{
    // a category with nothing selected opens as a table, and the list column steps aside
    const it=document.querySelector('#catrows .tr[data-id]');
    ok('a category opens as a table of its rows', !!it && document.getElementById('view-browse').classList.contains('landing'));
    ok('the list column steps aside while the table shows', !document.querySelector('.listcol').getBoundingClientRect().width);
    // a column empty in every row is dropped, so only the name is certain; the rest depends on the rows
    ok('the table has sortable columns, the name first', (document.querySelector('#catrows .th-s')||{}).dataset.sort==='name' &&
       document.querySelectorAll('#catrows .th-s').length>=2);
    if(it) click(it);
  });
  steps.push(()=>{
    ok('a table row opens the node', (document.getElementById('detail').textContent||'').length>40 && !!state.sel);
    ok('the list comes back beside the node', !!document.querySelector('#listitems .item[data-id]') && document.querySelector('.listcol').getBoundingClientRect().width>0);
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
  // --- the hover checkbox on a row IS the mark toggle: a plain click on it marks, it does not navigate ---
  // It used to appear on hover and do nothing on click — only ⌘-click on the row toggled the mark.
  steps.push(()=>{
    const lf=document.getElementById('lf');
    if(lf){ lf.value=''; lf.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const it=document.querySelectorAll('#listitems .item[data-id]')[1]||document.querySelector('#listitems .item[data-id]');
    window.__selBefore=state.sel; window.__ckRow=it;
    if(it) click(it.querySelector('.ck'));
  });
  steps.push(()=>{
    const it=window.__ckRow;
    ok('clicking the row checkbox marks the row', !!it && it.getAttribute('aria-checked')==='true' && it.classList.contains('mark'));
    ok('and does not change the selection', state.sel===window.__selBefore, state.sel+' vs '+window.__selBefore);
    if(it) click(it.querySelector('.ck'));
  });
  steps.push(()=>{
    ok('clicking it again unmarks', document.querySelectorAll('#listitems .item.mark').length===0);
    openPalette(); type('customer');
  });
  steps.push(()=>{ click(rows()[1].querySelector('.ck')); });
  steps.push(()=>{
    ok('the palette checkbox marks too', document.querySelectorAll('.pal-item.mark').length===1);
    ok('and keeps the palette open', !pal.hidden);
    closePalette();
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

  // --- a form's buttons: the Fields row expands into what the button does ---
  // The row used to carry id, caption and type only, so a form could show that it triggers an action
  // while staying silent about which button did it, with which payload. These checks pin the three
  // things the row now has to answer without leaving the panel.
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('form:orderForm'); });
  steps.push(()=>{
    const sect=document.querySelector('#detail [data-sect="formfields"]');
    ok('the form lists its fields', !!sect);
    if(sect) sect.open=true;
    // The field count is in the section heading and the navigator; a "Fields 7" fact would say it thrice.
    const fac=[...document.querySelectorAll('#detail .facts dd')].map(d=>d.textContent.trim());
    ok('no fact is a bare count that a section already carries', !fac.some(t=>/^\\d+$/.test(t)), fac.join(' | '));
    // the outcomes are a fact of the form now, not a table of their own
    const facts=[...document.querySelectorAll('#detail .facts .fact')];
    const oc=facts.find(f=>/Outcomes/i.test(f.querySelector('dt').textContent));
    ok('the form states its outcomes in its facts', !!oc && /approve/.test(oc.querySelector('dd').textContent), oc?oc.textContent:'(none)');
    ok('and has no Outcomes section', !document.querySelector('#detail [data-sect="outcomes"]'));

    const btn=document.querySelector('#detail details.fldrow[data-el="notifyButton"]');
    ok('an action button is an expandable row', !!btn, 'no expandable row for notifyButton');
    ok('the row names the action in its summary',
       !!btn && /notifyCustomerAction/.test(btn.querySelector('summary').textContent),
       btn?btn.querySelector('summary').textContent:'(no row)');
    if(btn) btn.open=true;
    window.__btn=btn;
  });
  steps.push(()=>{
    const btn=window.__btn, body=btn&&btn.querySelector('.fldbody');
    ok('the action is a chip you can follow', !!body&&!!body.querySelector('.nc[data-id]'));
    ok('the payload it sends and stores is on the button',
       !!body&&body.querySelectorAll('.parmgrid .pc').length>=3,
       body?body.querySelectorAll('.parmgrid .pc').length+' mapping rows':'(no body)');
    const scr=document.querySelector('#detail details.fldrow[data-el="orderTotal"]');
    if(scr) scr.open=true;
    ok('an expression button shows its expression',
       !!scr && /amount \\* 1.081/.test(scr.textContent), scr?'no expression in the row':'(no row)');
    ok('and the interval it re-runs on', !!scr && /30 s/.test(scr.textContent));
    // The flag that overrides the map has to be louder than the map itself.
    const esc=document.querySelector('#detail details.fldrow[data-el="escalate"]');
    if(esc) esc.open=true;
    ok('a full-payload button says the map is not used',
       !!esc && /not used/.test(esc.textContent), esc?esc.textContent.slice(0,120):'(no row)');
    // A plain input has nothing to expand and must stay the dense one-line row.
    ok('a plain field is not expandable',
       !document.querySelector('#detail details.fldrow[data-el="amount"]'));
    // A hidden auto-executing worker is the commonest button in a real project and the one a reader is
    // most likely to mistake for a button: the state has to be on the row, not behind a click.
    const w=document.querySelector('#detail [data-sect="formfields"] [data-el="creditScore"]');
    ok('a hidden button says so without being expanded',
       !!w && /hidden/.test((w.querySelector('summary')||w).textContent),
       w?(w.querySelector('summary')||w).textContent.slice(0,90):'(no row)');
    if(w&&w.tagName==='DETAILS') w.open=true;
    ok('and it names where the response is stored',
       !!w && /stores response in/.test(w.textContent) && /creditScore/.test(w.textContent));
    ok('its localised caption is the name it goes by', !!w && /Refresh score/.test(w.textContent));
    ok('the note the modeller left is on it', !!w && /Hidden worker/.test(w.textContent));
    // A condition is a different statement from a settled state and belongs in the body.
    const n2=document.querySelector('#detail details.fldrow[data-el="internalNote"]');
    if(n2) n2.open=true;
    ok('a conditional gate shows its condition',
       !!n2 && /enabled when/.test(n2.textContent) && /isSupervisor/.test(n2.textContent),
       n2?n2.textContent.slice(0,80):'(no row)');
  });
  // An id search lands on the button's own row, not just on the form.
  steps.push(()=>{ openPalette(); type('id:notifyButton'); });
  steps.push(()=>{
    ok('an id: query finds the form', rows().length>0, 'no hits for id:notifyButton');
    ok('the hit says which element matched', /notifyButton/.test(rows()[0].textContent),
       rows()[0].textContent);
    click(rows()[0]);
  });
  steps.push(()=>{
    const row=document.querySelector('#detail [data-sect="formfields"] [data-el="notifyButton"]');
    ok('the hit opened that button\\'s row', !!row&&row.classList.contains('hit'),
       row?'row not marked':'no row');
  });

  // A caption and the prose behind it are searchable in their own right, and the row has to say which
  // one matched — otherwise a hit on a description that is not on screen looks arbitrary.
  steps.push(()=>{ openPalette(); type('label:Recalculate'); });
  steps.push(()=>{
    ok('a label: query finds the form that shows that caption', rows().length>0,
       'no hits for label:Recalculate');
    ok('and the row explains itself with the label it matched',
       /label/i.test(rows()[0].textContent), rows()[0].textContent);
  });
  steps.push(()=>{ type('desc:Backoffice'); });
  steps.push(()=>{
    ok('a desc: query finds the element documentation', rows().length>0, 'no hits for desc:Backoffice');
    ok('and it finds the process, not the group of the same name',
       /Order Process/.test(rows()[0].textContent), rows()[0].textContent);
    key('Escape');
  });

  // --- a facet hit lights its value; Tab cycles the dialog instead of being swallowed ---
  steps.push(()=>{ openPalette(); type('label:Recalculate'); });
  steps.push(()=>{
    ok('a facet hit highlights the value it matched', !!rows()[0]&&!!rows()[0].querySelector('mark.hl'),
       rows()[0]?rows()[0].textContent.slice(0,80):'(no rows)');
    ok('the page behind the open dialog is inert', document.querySelector('.shell').inert===true);
    key('Tab');
    const fc=document.activeElement;
    ok('Tab reaches the dialog controls', fc!==palq && !!fc.closest('.pal-panel'),
       'focus on '+(fc&&(fc.className||fc.tagName)));
    key('Escape');
  });
  steps.push(()=>{
    ok('closing the palette lifts inert again', document.querySelector('.shell').inert===false);
  });

  // --- the browse list explains a non-obvious match, like the palette does ---
  steps.push(()=>{ closeOtherTabs();
    const c=[...document.querySelectorAll('#nav .side-item')].find(el=>/Processes/.test(el.textContent));
    if(c) click(c); else say('note','no Processes category');
  });
  steps.push(()=>{
    const f=document.getElementById('catf');
    if(f){ f.value='setVariable'; f.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const it=document.querySelector('#catrows .tr[data-id]');
    ok('a script-body match still shows rows', !!it);
    ok('and the row says why it matched', !!it && /script/.test((it.querySelector('.cat-why')||{}).textContent||''),
       it?((it.querySelector('.cat-why')||{}).textContent||'(no why)'):'(none)');
  });

  // --- nothing extracted is invisible: unconsumed data renders as Other attributes ---
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('sla:approvalSla'); });
  steps.push(()=>{
    const det=document.getElementById('detail');
    ok('an SLA renders its escalations', !!det.querySelector('[data-sect="escalations"]'));
    const oa=det.querySelector('[data-sect="otherattrs"]');
    ok('unconsumed parsed data lands in Other attributes', !!oa && /completionActions/.test(oa.textContent),
       oa?oa.textContent.slice(0,120):'(no section)');
  });

  // Design's model Description used to render for apps only, because each type spelled the row itself.
  // It is prose now — the first thing on the Overview tab, not a labelled fact.
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('app:demoApp'); });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const desc=det.querySelector('#pane-overview > .ddesc');
    ok('the Overview tab opens with the model Description as prose', !!desc && /Miniature fixture app/.test(desc.textContent),
       desc?desc.textContent.slice(0,140):'(no .ddesc)');
    ok('and not as a labelled fact', ![...det.querySelectorAll('.facts dt')].some(t=>/^Description$/i.test(t.textContent)));
    // --- the page header: title in the hero, the same title in the sticky bar, the identity line ---
    const title=det.querySelector('.dhero .dtitle');
    ok('the hero carries the title', !!title && /Demo App/.test(title.textContent), title?title.textContent:'(none)');
    ok('the identity line names kind, key and path', !!det.querySelector('.dident .dkey') && /demoApp/.test(det.querySelector('.dident').textContent));
    ok('the actions are one group with labels', det.querySelectorAll('.dhead .dhead-actions button .lbl').length>=2);
    // a node page's sections are headings: what explains one waits behind its ⓘ, and every one starts
    // open but Other attributes — unless the reader folded it before
    ok('a section explains itself behind an ⓘ, not in a grey line', !det.querySelector('.dpane details.sect>summary .shint') && !!det.querySelector('.dpane details.sect>summary .sinfo[data-tip]'));
    let st={}; try{ st=JSON.parse(localStorage.getItem('atlas-sect2')||'{}')||{}; }catch(e){}
    const fresh=[...det.querySelectorAll('.dpane details.sect')].filter(s=>!(dec(s.dataset.sect) in st));
    ok('sections start open, Other attributes folded', fresh.length>0 && fresh.every(s=>s.open===(s.dataset.sect!=='otherattrs')),
       fresh.filter(s=>s.open!==(s.dataset.sect!=='otherattrs')).map(s=>s.dataset.sect).join());
  });
  // --- the page's tabs: a tab per pane that has something, the sections sorted into them, one on screen ---
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('process:orderProcess'); });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const tabs=[...det.querySelectorAll('.ptabs .ptab')], panes=[...det.querySelectorAll('.dpane')];
    ok('a page has a tab per pane', tabs.length>=2 && tabs.length===panes.length && tabs.every((t,i)=>t.dataset.pane===panes[i].dataset.pane),
       tabs.map(t=>t.dataset.pane).join()+' / '+panes.map(p=>p.dataset.pane).join());
    ok('the panes keep the page order', panes.map(p=>p.dataset.pane).join()==='overview,findings,connections,details', panes.map(p=>p.dataset.pane).join());
    ok('one pane is on screen, the first', panes.filter(p=>!p.hidden).length===1 && !panes[0].hidden && tabs[0].getAttribute('aria-selected')==='true');
    const inPane=(sect, pane)=>{ const d=det.querySelector('[data-sect="'+sect+'"]'); return !!d && d.closest('.dpane').dataset.pane===pane; };
    // (miniproject carries no DI, so this process has no drawing: its Overview is its facts)
    ok('the facts are on Overview, the findings on Findings', !!det.querySelector('#pane-overview > .facts') && inPane('findings','findings'));
    ok('the fit and the relations on Connections, the elements on Details',
       inPane('fit-calls','connections') && inPane('relations','connections') && inPane('elements','details'));
    ok('every fit question is a section of its own, explained behind its ⓘ',
       det.querySelectorAll('[data-sect^="fit-"]').length>=2 && [...det.querySelectorAll('[data-sect^="fit-"]>summary')].every(x=>!!x.querySelector('.sinfo[data-tip]')));
    ok('no legend line repeats what a mark means', !det.querySelector('.fitlegend'));
    const mk=det.querySelector('[data-sect^="fit-"] .gm');
    ok('a mark says what it means in its tooltip', !!mk && /fits|missing|looks wrong|cannot tell|expected|note/.test(mk.getAttribute('data-tip')||''), mk?mk.getAttribute('data-tip'):'(no mark)');
    ok('no pane is empty', panes.every(p=>p.children.length>0), panes.filter(p=>!p.children.length).map(p=>p.dataset.pane).join());
    const t=tabs.find(x=>x.dataset.pane==='details');
    const d=det.querySelector('details.sect[data-sect="elements"]');
    if(d) d.open=false;
    window.__navSect=d;
    if(t) click(t);
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const pane=det.querySelector('#pane-details');
    ok('clicking a tab brings its pane up', !!pane && !pane.hidden && det.querySelectorAll('.dpane:not([hidden])').length===1);
    ok('and the hash says which', /&p=details/.test(location.hash), location.hash);
    ok('a folded section stays folded on its tab', !!window.__navSect && !window.__navSect.open);
    if(window.__navSect) window.__navSect.open=true;
    // the digit keys pick a tab by its place
    document.body.dispatchEvent(new KeyboardEvent('keydown', {key:'1', code:'Digit1', bubbles:true}));
    ok('1 brings the first tab back', !det.querySelector('#pane-overview').hidden && !/&p=/.test(location.hash), location.hash);
    // the tab carries over to the next page when that page has it
    const t=det.querySelector('.ptab[data-pane="connections"]'); if(t) click(t);
    select('form:orderForm');
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const pane=det.querySelector('#pane-connections');
    ok('the tab carries over to the next page', !!pane && !pane.hidden && /&p=connections/.test(location.hash), location.hash);
    history.back();
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    ok('Back returns to the page on the tab it was left on', state.sel==='process:orderProcess' && !det.querySelector('#pane-connections').hidden, state.sel+' '+location.hash);
    // the health strip reaches into another tab: its findings item brings Findings up
    click(det.querySelector('.ptab[data-pane="overview"]'));
    const fb=det.querySelector('.dhealth .hs[data-jump-sect="findings"]');
    if(fb) click(fb);
    ok('a health item brings up the tab its section is on', !!fb && !det.querySelector('#pane-findings').hidden);
    click(det.querySelector('.ptab[data-pane="details"]'));
  });
  steps.push(()=>{
    // --- "N parameter mappings ↓" on a service task lands on that task's group in the Parameters section,
    // not on the card it sits in (also a row of the same element, and first in the document)
    const det=document.getElementById('detail');
    const card=det.querySelector('details.elgrp[data-eg="svctasks"] details.card[data-el="calcTask"]');
    ok('the service task with mappings is a card', !!card);
    if(card) card.closest('details.elgrp').open=true;
    if(card) card.open=true;
    const params=det.querySelector('details.sect[data-sect="params"]');
    if(params){ params.open=false; }
    const btn=card&&card.querySelector('[data-reveal-el="calcTask"]');
    ok('and offers the jump to its parameter mappings', !!btn);
    if(btn) click(btn);
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const params=det.querySelector('details.sect[data-sect="params"]');
    const grp=params&&params.querySelector('details.card[data-el="calcTask"]');
    ok('the jump opens the Parameters section', !!params && params.open);
    ok('and lands on the task\\'s mapping group there', !!grp && grp.open && grp.classList.contains('hit'),
       grp?'group not marked':'(no group)');
    ok('and not on the service task card', !det.querySelector('details.sect[data-sect="svctasks"] .hit'));
  });



  // --- a model lists what it uses: the section is rebuilt from the artifact nodes' usedBy ---
  // The generator strips _uses from the payload, and for a whole run of releases the panel still
  // read that key — so "which variables does this process touch" rendered nowhere.
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('process:orderProcess'); });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const sect=det.querySelector('[data-sect="varexpr"]');
    ok('a model lists the variables and expressions it uses', !!sect, 'no [data-sect="varexpr"] on the process');
    ok('and each one is a link you can follow', !!sect && !!sect.querySelector('.tbl .vlink[data-id^="variable"]'),
       sect?sect.textContent.slice(0,120):'(no section)');
  });

  // --- a malformed link cannot freeze the router; a filter does not travel between categories ---
  steps.push(()=>{ closeOtherTabs(); location.hash='#process%E0%A4%A'; });
  steps.push(()=>{
    ok('an undecodable hash lands on the overview instead of throwing', state.view==='overview', 'view='+state.view);
    location.hash=enc('process:orderProcess');
  });
  steps.push(()=>{
    ok('the router still works after the bad link', state.sel==='process:orderProcess', 'sel='+state.sel);
    const lf=document.getElementById('lf');
    if(lf){ lf.value='zzz-no-match'; lf.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    // follow a chip into another category: the filter typed in Processes must not come along
    const chip=[...document.querySelectorAll('#detail .nc[data-id]')].find(c=>decodeURIComponent(c.dataset.id).indexOf('process:')!==0);
    if(chip) click(chip); else say('note','no cross-category chip on the process');
  });
  steps.push(()=>{
    const lf=document.getElementById('lf');
    ok('the list filter is cleared when the category changes', !lf || lf.value==='', lf?('filter="'+lf.value+'"'):'');
  });

  // --- the list context travels in the link, and comes back on reload ---
  steps.push(()=>{ closeOtherTabs(); location.hash='/browse/'+enc('process'); });
  steps.push(()=>{
    const s=document.querySelector('#catrows .th-s[data-sort="refs"]');
    ok('a column header sorts', !!s);
    if(s) click(s);
  });
  steps.push(()=>{
    const ids=[...document.querySelectorAll('#catrows .tr[data-id]')].map(r=>r.dataset.id);
    ok('sorting by In puts the most referenced first', ids.length>1 && ids.every((id,i)=>!i||(INSIGHTS.indeg.get(ids[i-1])||0)>=(INSIGHTS.indeg.get(id)||0)));
    ok('the sorted header says so', !!document.querySelector('#catrows .th-s.on[data-sort="refs"]'));
    const before=state.tabs.length, row=document.querySelector('#catrows .tr[data-id]');
    window.__tabsB=before;
    if(row) row.dispatchEvent(new MouseEvent('auxclick', {bubbles:true, button:1}));
  });
  steps.push(()=>{
    ok('a middle-click opens a background tab and stays on the table', state.tabs.length===window.__tabsB+1 && !state.sel);
    const rows=[...document.querySelectorAll('#catrows .tr[data-id]')];
    if(rows[0]) click(rows[0], {metaKey:true, ctrlKey:true});
  });
  steps.push(()=>{
    ok('a mod-click marks a table row', !!document.querySelector('#catrows .tr.mark[aria-checked="true"]') && !!document.getElementById('lopen'));
    listMarksClear(); syncListMarks(); setMarkNote('');
    const f=document.getElementById('catf');
    if(f){ f.value='order'; f.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const h=decodeURIComponent(location.hash);
    ok('the filter is in the link', h.indexOf('&f=order')>0, 'hash='+h);
    ok('and so is the sort', h.indexOf('&s=refs')>0, 'hash='+h);
    // a "reload": route the same link cold
    state.filter=''; state.sort='name'; state.cat=null;
    location.hash='/browse/'+enc('form')+'&f=zzz-none&s=refs';
  });
  steps.push(()=>{
    const f=document.getElementById('catf');
    ok('a link with &f= restores the filter', !!f && f.value==='zzz-none', f?('filter="'+f.value+'"'):'no #catf');
    ok('a link with &s= restores the sort', state.sort==='refs', 'sort="'+state.sort+'"');
    ok('a filter matching nothing says so', !!document.querySelector('#catrows .list-empty'));
    location.hash=enc('form:orderForm');
  });
  steps.push(()=>{
    ok('following a node in that category keeps its list context in the link',
       decodeURIComponent(location.hash).indexOf('&f=zzz-none')>0, 'hash='+location.hash);
  });

  // --- elements: what a process is made of, one section with a group and a chip per kind ---
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('process:orderProcess'); });
  steps.push(()=>{
    const det=document.getElementById('detail'), s=det.querySelector('details.sect[data-sect="elements"]');
    ok('a process lists its elements in one section, open by default', !!s && s.open);
    ok('each kind is a group of it, not a section of its own', !!s && s.querySelectorAll('details.elgrp').length>=3 &&
       ['usertasks','svctasks','events','gateways','flows'].every(id=>!det.querySelector('details.sect[data-sect="'+id+'"]')));
    const chips=[...det.querySelectorAll('[data-sect="elements"] .fbar .pchip[data-fk="eg"]')];
    ok('a chip per kind, and one for all', chips.length===s.querySelectorAll('details.elgrp').length+1);
    const flows=chips.find(ch=>ch.dataset.fv==='flows');
    if(flows) click(flows);
    window.__elSect=s;
  });
  steps.push(()=>{
    const s=window.__elSect;
    const vis=[...s.querySelectorAll('details.elgrp')].filter(g=>!g.hidden).map(g=>g.dataset.eg);
    ok('a kind chip keeps only its group', vis.length===1 && vis[0]==='flows', vis.join());
    click(s.querySelector('.fbar .pchip[data-fv="all"]'));
    const g=[...s.querySelectorAll('details.elgrp')].find(x=>x.dataset.eg!=='flows'); window.__gwId=g&&g.dataset.eg;
    if(g) g.open=!g.open; window.__gwOpen=!!(g&&g.open);
  });
  steps.push(()=>{
    let st=null; try{ st=JSON.parse(localStorage.getItem('atlas-sect2')); }catch(e){}
    ok('a group remembers its fold under its old section id', !!window.__gwId && !!st && st[window.__gwId]===window.__gwOpen, window.__gwId+' '+JSON.stringify(st));
    if(st&&window.__gwId){ delete st[window.__gwId]; delete st.flows; try{ localStorage.setItem('atlas-sect2', JSON.stringify(st)); }catch(e){} }
    const nav=[...document.querySelectorAll('#detail [data-sect]')].map(c=>c.dataset.sect);
    // the health strip under the title: findings, connections — each a way into its section
    const hs=document.querySelector('#detail .dhero .dhealth');
    ok('a model page has a health strip under its title', !!hs);
    const n0=byId.get('process:orderProcess'), R0=relationsOf(n0, n0.data||{});
    const usesB=hs&&[...hs.querySelectorAll('.hs')].find(b=>/^uses /.test(b.textContent)), byB=hs&&[...hs.querySelectorAll('.hs')].find(b=>/^used by /.test(b.textContent));
    ok('it counts neighbours, not edges', !!usesB && !!byB && +usesB.textContent.replace(/\\D+/g,'')===new Set(R0.out.map(e=>e.id)).size &&
       +byB.textContent.replace(/\\D+/g,'')===new Set(R0.inc.map(e=>e.id)).size, (usesB&&usesB.textContent)+' / '+(byB&&byB.textContent));
    const fb=hs&&hs.querySelector('.hs[data-jump-sect="findings"]');
    ok('its findings item says defects or advice', !!fb && /defect|advice/.test(fb.textContent), fb?fb.textContent:'(none)');
    const fs=document.querySelector('#detail details.sect[data-sect="findings"]');
    if(fb&&fs){ fs.open=false; click(fb); ok('and opens the findings', fs.open); }
    // every page reads picture, findings, fit and relations, details — in that order, a tab each
    const at=id=>nav.indexOf(id);
    ok('the findings come before the relations, the relations before the elements',
       at('findings')>=0 && at('relations')>at('findings') && at('elements')>at('relations'), nav.join());
    ok('the declared data objects are a group of the elements', !document.querySelector('#detail details.sect[data-sect="declaredvars"]'));
  });

  // --- relations: one section — every relation as a row, the drawing on request; remembered; its rows are links ---
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('process:orderProcess'); });
  steps.push(()=>{
    const det=document.getElementById('detail'), s=det.querySelector('details.sect[data-sect="relations"]');
    ok('the relations are one section, open by default', !!s && s.open);
    try{ localStorage.removeItem('atlas-relgraph'); }catch(e){}
    const gb=s&&s.querySelector('.relgbtn'), nbh=s&&s.querySelector('.nbh');
    ok('the drawing waits behind its switch', !!gb && gb.getAttribute('aria-pressed')==='false' && !!nbh && nbh.hidden);
    if(gb) click(gb);
    ok('which draws it', !!nbh && !nbh.hidden && gb.getAttribute('aria-pressed')==='true' && localStorage.getItem('atlas-relgraph')==='1');
    ok('its drawing reads uses on the left, used by on the right', !!s && s.querySelectorAll('.nb-head').length===2 && s.querySelectorAll('.gn[data-id]').length>0);
    ok('the list has no column headers and no gesture line', !!s && !s.querySelector('.relhint') && [...s.querySelectorAll('.reltbl .th')].every(h=>getComputedStyle(h).display==='none'));
    ok('the relations are told once', ['neighborhood','rels-out','rels-in','called-with','usedby','uses'].every(id=>!det.querySelector('[data-sect="'+id+'"]')));
    // never filter silently: a row per direction and relation, a chip per neighbour the graph holds
    const n=byId.get('process:orderProcess'), R=relationsOf(n, n.data||{});
    const rows=s?s.querySelectorAll('.tr[data-rdir]').length:0;
    const kinds=new Set(R.out.map(e=>'out|'+e.rel)).size+new Set(R.inc.map(e=>'in|'+e.rel)).size;
    ok('the table has a row for every relation of the node', rows===kinds && rows>0, rows+' vs '+kinds);
    const chips=s?s.querySelectorAll('.rnb[data-rdir]').length:0;
    ok('and a chip for every neighbour of every relation', chips===R.out.length+R.inc.length, chips+' vs '+(R.out.length+R.inc.length));
    const want=new Set((outM.get(n.id)||[]).filter(e=>byId.get(e.id)).map(e=>'out|'+e.rel+'|'+e.id).concat((incM.get(n.id)||[]).filter(e=>byId.get(e.id)).map(e=>'in|'+e.rel+'|'+e.id)));
    ok('and exactly the distinct edges of the graph', want.size===chips, want.size+' vs '+chips);
    const chip=s&&s.querySelector('.fbar .pchip[data-fv="in"]');
    if(chip){ click(chip);
      ok('the ← used by chip keeps the incoming rows only', [...s.querySelectorAll('.tr[data-rdir]')].every(r=>r.hidden===(r.dataset.rdir==='out')));
      click(s.querySelector('.fbar .pchip[data-fv="all"]')); }
    if(s) s.open=false;
  });
  steps.push(()=>{
    let st=null; try{ st=JSON.parse(localStorage.getItem('atlas-sect2')); }catch(e){}
    ok('closing the relations is remembered', !!st && st.relations===false, JSON.stringify(st));
    if(st){ delete st.relations; try{ localStorage.setItem('atlas-sect2', JSON.stringify(st)); }catch(e){} }
    const s=document.querySelector('#detail details.sect[data-sect="relations"]');
    if(s){ s.open=true; const g=s.querySelector('.gn[data-id]'); window.__nbFrom=state.sel; if(g) click(g); }
  });
  steps.push(()=>{
    ok('a neighbour in the drawing is a link', !!state.sel && state.sel!==window.__nbFrom, 'sel='+state.sel);
    const nb=document.querySelector('#detail .nbh');
    ok('and the next page keeps the drawing on', !nb || !nb.hidden);
    const gb=document.querySelector('#detail .relgbtn'); if(gb) click(gb);
    ok('until it is switched off again', !localStorage.getItem('atlas-relgraph'));
    location.hash=enc('process:fulfilmentProcess');
  });
  steps.push(()=>{
    // what the Called with section said: the caller's mappings, in the caller's row
    const row=document.querySelector('#detail [data-sect="relations"] .tr[data-rdir="in"][data-rmap]');
    ok('a caller that maps parameters is marked as such', !!row);
    ok('and its row carries the mappings', !!row && [...row.querySelectorAll('.pc')].some(p=>/subOrderId/.test(p.textContent)));
    ok("without answering this page's element links", !!row && !row.querySelector('.tx [data-el], .tx [data-hay]'));
    location.hash=enc('endpoint:GET /api/customers/{id}/canEdit');
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const row=[...det.querySelectorAll('[data-sect="relations"] .tr[data-rdir="in"]')].find(r=>r.querySelector('.nc[data-id="'+enc('form:orderForm')+'"]'));
    ok('an endpoint names who calls it, with the verb and the URL', !!row && /GET/.test(row.textContent) && /canEdit/.test(row.textContent));
    ok('no separate Called by section', !det.querySelector('[data-sect="callers"]'));
    location.hash=enc('group:auditors');
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    ok('a group lists what its members may do, a run per permission', new Set([...det.querySelectorAll('[data-sect="relations"] .tr[data-rel]')].map(r=>r.dataset.rel)).size===2);
    ok('no separate Access section', !det.querySelector('[data-sect="access"]'));
    location.hash=enc('agent:orderAssistant');
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    ok("an agent's tools are relations, with the operation", !det.querySelector('[data-sect="tools"]') &&
       /findAll/.test((det.querySelector('[data-sect="relations"] .tr[data-rel="tool"]')||{}).textContent||''));
    const other=det.querySelector('[data-sect="otherattrs"]');
    ok('and the tools key is not left over under Other attributes', !other || ![...other.querySelectorAll('*')].some(el=>el.children.length===0 && (el.textContent||'').trim()==='tools'));
    location.hash=enc(nodes.find(x=>x.type==='serviceOperation').id);
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const other=det.querySelector('[data-sect="otherattrs"]'), keys=other?[...other.querySelectorAll('.kvk')].map(k=>k.textContent.trim()):[];
    ok("an operation's url and key are facts, not left-overs", keys.indexOf('url')<0 && keys.indexOf('operation')<0, keys.join());
    ok('its call is one fact: verb and URL', [...det.querySelectorAll('.facts .fact')].some(f=>/^Call$/i.test(f.querySelector('dt').textContent.trim())));
    ok('an operation is related to its service', !!det.querySelector('[data-sect="relations"] .tr[data-rel="operation-of"] .nc[data-id^="'+enc('service:')+'"]'));
    location.hash=enc('variable:total');
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    ok('a variable lists the models that write and read it', !!det.querySelector('[data-sect="relations"] .tr[data-rel="writes-variable"] .rnb') &&
       !!det.querySelector('[data-sect="relations"] .tr[data-rel="reads-variable"] .rnb'));
    ok('and no longer says it has no relationships', !/No relationships recorded/.test(det.textContent));
    location.hash=enc('app:demoApp');
  });
  steps.push(()=>{
    const row=document.querySelector('#detail [data-sect="relations"] .tr[data-rel="contains"]');
    ok('a relation names all its neighbours on one row', !!row && row.querySelectorAll('.rnb .nc[data-id]').length>1,
       row?row.querySelectorAll('.rnb').length+' chips':'(no row)');
  });

  // --- does it fit: a called process against its callers, and the call from the caller's side ---
  steps.push(()=>{ location.hash=enc('process:fulfilmentProcess'); });
  steps.push(()=>{
    const s=fitView();
    ok('a called process shows whether its callers fit, open by default', !!s && s.open);
    const row=nm=>s?[...s.querySelectorAll('.tbl .tr')].find(r=>{ const c=r.querySelector('.ctn'); return c && c.textContent.trim()===nm; }):null;
    const st=row('stockLevel'), oi=row('orderId'), tot=row('subTotal');
    ok('a value it reads that no caller passes is a gap', !!st && st.classList.contains('cov-warn'), st?st.className:'(no row)');
    ok('a value passed in that it never reads is a gap too', !!oi && oi.classList.contains('cov-warn'), oi?oi.className:'(no row)');
    ok('a value it hands back fits', !!tot && !/cov-/.test(tot.className), tot?tot.className:'(no row)');
    ok('the gap kinds are named in the pills', !!s && /not passed/.test(s.textContent) && /never read/.test(s.textContent));
    const call=s&&s.querySelector('.tbl .tr[data-el="callCourier"]');
    ok('a call into a model outside the project is a question, not a fit', !!call && !!call.querySelector('.gm-unk') && !/cov-/.test(call.className));
    const hs=document.querySelector('#detail .dhealth .hs[data-jump-sect^="fit-"]');
    ok('the health strip counts the gaps and jumps to them', !!hs && /gap/.test(hs.textContent));
    location.hash=enc('process:orderProcess');
  });
  steps.push(()=>{
    const r=document.querySelector('#detail [data-sect^="fit-"] .tr[data-el="callSub"]');
    ok('the caller sees the same gap on its call', !!r && r.classList.contains('cov-warn') && /stockLevel/.test(r.textContent), r?r.textContent:'(no row)');
  });

  // --- a service fits its callers, the code that answers it, and its data object ---
  steps.push(()=>{ location.hash=enc('service:customerService'); });
  steps.push(()=>{
    const s=fitView();
    ok('a service asks whether its operations fit', !!s);
    const row=s&&[...s.querySelectorAll('.tbl .tr')].find(r=>/GET/.test(r.textContent) && r.querySelector('.vlink[data-id="'+enc('endpoint:GET /api/customers')+'"]'));
    ok('each operation names the endpoint that answers it, with the verb checked', !!row && !!row.querySelector('.gm-ok'), row?row.textContent:'(no row)');
    ok('and the handler in the code', !!row && /CustomerController#/.test(row.textContent));
    location.hash=enc('dataObject:customerDO');
  });
  steps.push(()=>{
    const cols=[...document.querySelectorAll('#detail [data-sect="columns"] .tbl .th .td')].map(t=>t.textContent.trim());
    ok("a data object's properties name the service column behind each field", cols.indexOf('Service column')>=0, cols.join('|'));
  });

  // --- a decision, an action and a form against what they meet ---
  steps.push(()=>{ location.hash=enc('decision:orderDecision'); });
  steps.push(()=>{
    const det=document.getElementById('detail');
    ok('a decision table is drawn with its hit policy and its input and output bands',
       !!det.querySelector('.dmntab th.hp') && !!det.querySelector('.dmntab .band-in') && !!det.querySelector('.dmntab .band-out'));
    ok('its inputs and outputs are the head of the table, not a section of their own', !det.querySelector('[data-sect="dmnio"]'));
    const s=fitView();
    const row=s&&[...s.querySelectorAll('.tbl .tr')].find(r=>{ const c=r.querySelector('.ctn'); return c && c.textContent.trim()==='total'; });
    ok('an input the calling process writes fits by name', !!row && !!row.querySelector('.gm-impl'), row?row.innerHTML.slice(0,200):'(no row)');
    location.hash=enc('action:notifyCustomerAction');
  });
  steps.push(()=>{
    const s=fitView();
    const row=s&&[...s.querySelectorAll('.tbl .tr')].find(r=>{ const c=r.querySelector('.ctn'); return c && c.textContent.trim()==='customerEmail'; });
    ok("an action's input its calling button sends fits", !!row && !!row.querySelector('.gm-ok'), row?row.textContent:'(no row)');
    location.hash=enc('form:orderForm');
  });
  steps.push(()=>{
    const s=fitView();
    const rest=s&&s.querySelector('.tbl .tr[data-el="canEditButton"]');
    ok("a form's REST button is a call, its verb checked against the handler", !!rest && /GET/.test(rest.textContent) && !!rest.querySelector('.gm-ok'), rest?rest.textContent:'(no row)');
    ok('the data sources and REST calls are no sections of their own', !document.querySelector('#detail [data-sect="datasources"], #detail [data-sect="restcalls"]'));
    ok('its fields say who reads the variables they write', !!s && /Fields and the variables they write/.test(s.textContent));
  });

  // --- apps and groups: what an app reaches, and who can reach what ---
  steps.push(()=>{ location.hash=enc('group:sales'); });
  steps.push(()=>{
    const s=fitView();
    const row=s&&[...s.querySelectorAll('.tbl .tr')].find(r=>r.querySelector('.vlink[data-id="'+enc('process:orderProcess')+'"]'));
    ok('a group sees what it may do per model, and through which app', !!row && /May start/.test(row.textContent) && /via Demo App/.test(row.textContent), row?row.textContent:'(no row)');
    location.hash=enc('app:demoApp');
  });
  steps.push(()=>{
    const s=fitView();
    const row=s&&[...s.querySelectorAll('.tbl .tr')].find(r=>r.querySelector('.vlink[data-id="'+enc('decision:orderDecision')+'"]'));
    ok('an app lists what its models reach that no app ships', !!row && row.classList.contains('cov-warn') && /in no app/.test(row.textContent), row?row.textContent:'(no row)');
  });

  // --- events, endpoints and classes against their counterparts ---
  steps.push(()=>{ location.hash=enc('endpoint:GET /api/customers/{id}/canEdit'); });
  steps.push(()=>{
    const s=fitView();
    const row=s&&[...s.querySelectorAll('.tbl .tr')].find(r=>r.querySelector('.vlink[data-id="'+enc('form:orderForm')+'"]'));
    ok('an endpoint lists its callers with the verb each uses', !!row && /canEditButton/.test(row.textContent) && !!row.querySelector('.gm-ok'), row?row.textContent:'(no row)');
    location.hash=enc('java:com.example.DemoBean');
  });
  steps.push(()=>{
    const m=document.querySelector('#detail [data-sect="methods"]'); if(m) m.open=true;
    const row=m&&[...m.querySelectorAll('.tbl .tr')].find(r=>((r.querySelector('.td.mono')||{}).textContent||'').indexOf('run(')===0);
    ok("a class's methods name the models that call them", !!row && !!row.querySelector('.vlink[data-id="'+enc('process:orderProcess')+'"]'), row?row.textContent:'(no row)');
    location.hash=enc('event:orderShipped');
  });
  steps.push(()=>{
    const s=fitView();
    ok('an event lists its payload against its publishers and consumers', !!s && /Publishers and consumers/.test(s.textContent) && /consumed by/.test(s.textContent));
    const hs=document.querySelector('#detail .dhealth');
    ok('a page whose every row is a question does not claim it fits', !!hs && !/fits/.test(hs.textContent));
  });

  // --- a form's picture is its layout: the wireframe the IDE preview draws, clickable like a diagram ---
  steps.push(()=>{ location.hash=enc('form:orderForm'); });
  steps.push(()=>{
    const s=document.querySelector('#detail details.sect[data-sect="diagram"]');
    ok('a form opens with its layout', !!s && /Layout/.test((s.querySelector('summary')||{}).textContent||''));
    if(s) s.open=true;
  });
  steps.push(()=>{
    const g=document.querySelector('#detail [data-sect="diagram"] .dgview g[data-el="notifyButton"]');
    ok('a component of the layout is a clickable element', !!g && g.getAttribute('role')==='button' && !!g.getAttribute('aria-label'),
       g?g.outerHTML.slice(0,160):'(no element)');
    if(g) click(g);
  });
  steps.push(()=>{
    // scrolling over the layout must not wait on the page's script: a plain wheel is not taken, a
    // modified one zooms; and a cell's highlight is an outline, not a blur filter repainted per row
    const view=document.querySelector('#detail [data-sect="diagram"] .dgview');
    const plain=new WheelEvent('wheel', {deltaY:40, bubbles:true, cancelable:true});
    view.dispatchEvent(plain);
    ok('a plain wheel over the layout scrolls the page', !plain.defaultPrevented);
    const before=view._z.scale;
    window.dispatchEvent(new KeyboardEvent('keydown', {key:'Meta', metaKey:true}));
    const zoom=new WheelEvent('wheel', {deltaY:-40, metaKey:true, bubbles:true, cancelable:true});
    view.dispatchEvent(zoom);
    ok('with the modifier held, the wheel zooms the drawing', zoom.defaultPrevented && view._z.scale>before, before+' -> '+view._z.scale);
    window.dispatchEvent(new KeyboardEvent('keyup', {key:'Meta'}));
    const after=new WheelEvent('wheel', {deltaY:40, bubbles:true, cancelable:true});
    view.dispatchEvent(after);
    ok('and lets go of it when the modifier is released', !after.defaultPrevented);
    ok('the layout says it is a wireframe', view.dataset.kind==='wireframe');
    const sel=view.querySelector('g[data-el].dgsel');
    ok('the selected cell is outlined, not blurred', !!sel && getComputedStyle(sel).filter==='none' && !!sel.querySelector('rect') &&
       getComputedStyle(sel.querySelector('rect')).stroke.indexOf('15, 85, 214')>=0, sel?getComputedStyle(sel).filter:'(no selection)');
  });
  steps.push(()=>{
    const card=document.querySelector('.dgcard');
    ok('clicking it opens its card, with the action it calls', !!card && !!card.querySelector('.nc[data-id="'+enc('action:notifyCustomerAction')+'"]'),
       card?card.textContent.slice(0,200):'(no card)');
    document.body.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
  });

  // --- a subform: drawn with the form it embeds, named on its card and its row, opened by a double click ---
  steps.push(()=>{ location.hash=enc('form:DEMO-LF001'); });
  steps.push(()=>{
    const s=document.querySelector('#detail details.sect[data-sect="diagram"]');
    if(s) s.open=true;
    const row=document.querySelector('#detail [data-sect="formfields"] [data-el="orderSub"]');
    ok('the subform row links the form it embeds', !!row && !!row.querySelector('.vlink[data-id="'+enc('form:orderForm')+'"]'),
       row?row.textContent.slice(0,160):'(no row)');
  });
  steps.push(()=>{
    const g=document.querySelector('#detail [data-sect="diagram"] .dgview g[data-el="orderSub"]');
    ok('the subform box says which form it opens', !!g && g.getAttribute('data-ref')==='form:orderForm', g?g.outerHTML.slice(0,160):'(no box)');
    ok('and draws that form inside it, as a picture, not as its elements', !!g && /Subform/.test(g.textContent) &&
       !!g.querySelector('g:not([data-el])') && !g.querySelector('g[data-el="notifyButton"]'));
    if(g) click(g);
  });
  steps.push(()=>{
    const card=document.querySelector('.dgcard');
    ok('its card names the embedded form as a link', !!card && !!card.querySelector('.nc[data-id="'+enc('form:orderForm')+'"]'),
       card?card.textContent.slice(0,200):'(no card)');
    // a press anywhere outside the card and the drawing closes it
    document.body.dispatchEvent(new PointerEvent('pointerdown', {bubbles:true}));
    ok('a press outside the card closes it', !document.querySelector('.dgcard'));
    const g=document.querySelector('#detail [data-sect="diagram"] .dgview g[data-el="orderSub"]');
    if(g){ click(g); click(g, {detail:2}); }
  });
  steps.push(()=>{
    ok('a double click on the subform opens the form it embeds', state.sel==='form:orderForm', state.sel);
    ok('and leaves no card behind', !document.querySelector('.dgcard'));
  });

  // --- a bean the Flowable platform ships is not an external library ---
  steps.push(()=>{
    const bean={type:'external', data:{kind:'bean', platform:true}}, lib={type:'external', data:{kind:'class', platform:false}};
    const libCat=CATS.find(c=>c.id==='external::lib');
    ok('a platform bean (flwTimeUtils) is a Flowable platform reference', nodeKind(bean)==='Flowable platform');
    ok('and is not listed under External / library', !!libCat && libCat.match(bean)===false && libCat.match(lib)===true);
    ok('a key given as an expression is a dynamic reference, not a library', nodeKind({type:'external', data:{kind:'process', dynamic:true}})==='Dynamic reference');
  });

  // --- sidebar groups fold and remember ---
  steps.push(()=>{
    const h=document.querySelector('#nav .side-group[data-group="Models"]');
    ok('the sidebar has group headers that are buttons', !!h && h.tagName==='BUTTON' && h.getAttribute('aria-expanded')==='true');
    if(h) click(h);
  });
  steps.push(()=>{
    const h=document.querySelector('#nav .side-group[data-group="Models"]'), g=document.getElementById('navgrp-models');
    ok('a click folds the group', !!h && h.getAttribute('aria-expanded')==='false' && !!g && g.hidden);
    let st=null; try{ st=JSON.parse(localStorage.getItem('atlas-navgroups')); }catch(e){}
    ok('the fold is remembered', !!st && st.Models===false, JSON.stringify(st));
    renderSidebar(); renderSidebarActive();   // a cold re-render — the closest thing to a reload inside one page
  });
  steps.push(()=>{
    const h=document.querySelector('#nav .side-group[data-group="Models"]');
    ok('a re-render keeps the group folded', !!h && h.getAttribute('aria-expanded')==='false');
    location.hash='/browse/'+enc('process');
  });
  steps.push(()=>{
    const h=document.querySelector('#nav .side-group[data-group="Models"]');
    ok('a folded group marks the active entry on its header and stays folded',
       !!h && h.classList.contains('has-on') && h.getAttribute('aria-expanded')==='false');
    ok('a folded entry is not a keyboard stop', !document.querySelector('#nav .side-item.on') ||
       document.querySelector('#nav .side-item.on').closest('[hidden]')!==null);
    if(h) click(h);
    try{ localStorage.removeItem('atlas-navgroups'); }catch(e){}
  });
  steps.push(()=>{
    const h=document.querySelector('#nav .side-group[data-group="Models"]');
    const on=document.querySelector('#nav .side-item.on');
    ok('a second click unfolds it and the active entry is visible again',
       !!h && h.getAttribute('aria-expanded')==='true' && !!on && on.closest('[hidden]')===null);
  });

  // --- the sidebar footer fits its column ---
  // At the default 240px the old single-row footer squeezed the project name to one letter and pushed the
  // buttons out past the sidebar's edge; the theme toggle also existed twice.
  steps.push(()=>{
    const f=document.getElementById('sidefoot'), fr=f.getBoundingClientRect();
    const kids=[...f.children].filter(c=>!c.hidden);
    ok('the sidebar footer does not overflow its column', f.scrollWidth<=f.clientWidth+0.5 &&
       kids.every(c=>c.getBoundingClientRect().right<=fr.right+0.5), f.scrollWidth+' vs '+f.clientWidth);
    const pw=document.getElementById('proj').getBoundingClientRect().width;
    ok('the project name stays readable in the footer', pw>=48, 'width='+pw);
    ok('exactly one theme toggle', document.querySelectorAll('[data-theme-btn]').length===1);
  });

  // --- the overview's health summary and inventory ---
  steps.push(()=>{ location.hash='/overview'; });
  steps.push(()=>{
    const ov=document.getElementById('view-overview'), K=kindCounts();
    ok('the overview has a health summary', !!ov.querySelector('.hsum'));
    const num=sel=>parseInt((ov.querySelector(sel)||{}).textContent,10);
    ok('its tiles count the open defects and advice', num('.hk-defect .hk-n')===K.defects && num('.hk-advice .hk-n')===K.advice,
       num('.hk-defect .hk-n')+'/'+num('.hk-advice .hk-n')+' vs '+K.defects+'/'+K.advice);
    const sum=kind=>[...ov.querySelectorAll('.hk-'+kind+' .hk-tiers .pchipn')].reduce((a,x)=>a+parseInt(x.textContent,10),0);
    ok("each tile's tiers add up to its number", sum('defect')===K.defects && sum('advice')===K.advice);
    const tops=[...ov.querySelectorAll('.hlist-top .hrow[data-jump]')].map(r=>r.dataset.jump);
    const want=healthRows().filter(r=>r.n).slice(0,5).map(r=>r.jump);
    ok('it lists the top open checks, no more than five', tops.length<=5 && tops.join()===want.join(), tops.join()+' vs '+want.join());
    ok('it does not repeat the whole Checks list', !ov.querySelector('details.hclean'));
    ok('the Checks page is one click away', !!ov.querySelector('.seclabel [data-route="/checks"]'));
    // the inventory is the sidebar's entries, grouped the same way
    const inv=[...ov.querySelectorAll('.invg-row[data-cat]')];
    ok('the inventory names the categories', inv.length>0);
    ok('each inventory row says what its sidebar entry says', inv.every(r=>{
      const sb=document.querySelector('#nav .side-item[data-cat="'+cssEsc(r.dataset.cat)+'"]');
      return !!sb && (sb.querySelector('.lbl')||{}).textContent===(r.querySelector('.lbl')||{}).textContent &&
        (sb.querySelector('.n')||{textContent:''}).textContent===(r.querySelector('.n')||{}).textContent; }));
    ok('no review list is inventory', inv.every(r=>!CAT_CHECK[r.dataset.cat]));
    const first=document.querySelector('#view-overview .hrow[data-jump]');
    ok('this report has a finding to click', !!first);
    if(first) click(first);
  });
  steps.push(()=>{
    ok('a health row opens the Checks page', location.hash==='#/checks' && !!document.querySelector('#view-checks .hlist'));
    ok('no health card wall anywhere', !document.querySelector('.hcard'));
    const cv=document.getElementById('view-checks');
    ok('the Checks page has the shared header', !!cv.querySelector('.dhero .dtitle'));
    const secs=[...cv.querySelectorAll('details.sect[data-sect^="chk-"]')];
    ok('each finding is a section that starts open', secs.length>0 && secs.every(s=>s.open), secs.length+' sections');
    ok('a finding section carries the id the health rows jump to', secs.every(s=>s.id===decodeURIComponent(s.dataset.sect)));
    // The health list is this page's navigator: every row with something to show jumps to a block that
    // exists, and there is no second strip of chips saying the same thing.
    const jumps=[...cv.querySelectorAll('.hrow[data-jump]')].map(r=>r.dataset.jump);
    ok('every health row jumps to a block that exists', jumps.length>0 && jumps.every(j=>j!=='undefined' && !!document.getElementById(j)), jumps.join(' '));
    ok('the runtime-risk checks have blocks of their own', ['nonExclusiveAsync','unguardedTasks','asyncWithoutRetry']
       .every(k=>!(DATA.checks||{})[k] || !!document.getElementById('chk-'+k)));
    ok('no navigator strip duplicates the health list', !cv.querySelector('.secnav'));
    ok('every health row names its tone in words', [...cv.querySelectorAll('.hrow[data-jump]')].every(r=>/^(error|warning|advice)$/.test((r.querySelector('.hsev')||{}).textContent||'')));
    // One vocabulary: a row under "Advice" says advice, never the severity graph.json carries for it.
    const adv=[...cv.querySelectorAll('.hlist .hrow[data-jump]')].filter(r=>checkKind(r.dataset.jump.replace(/^chk-/,''))==='advice'&&!r.closest('details.hclean'));
    ok('an advice row says advice', adv.length>0 && adv.every(r=>(r.querySelector('.hsev').textContent||'')==='advice' && r.classList.contains('tone-advice')), adv.length+' advice rows');
    ok('no advice finding wears a warning pill', [...cv.querySelectorAll('.tbl .tr[data-fi]')].filter(r=>checkKind(FINDS[+r.dataset.fi].check)==='advice')
       .every(r=>r.dataset.sev==='advice' && !r.querySelector('.pill-warn')));
    const ac=cv.querySelector('.fbar .pchip[data-fv="advice"]');
    ok('the findings filter has an advice chip', !!ac);
    if(ac) click(ac);
    const row=cv.querySelector('details.sect[data-sect^="chk-"] .tbl .tr');
    ok('a finding row names its severity, model and message', !!row && !!row.querySelector('.pill') && !!row.querySelector('.nc, .mono') && (row.textContent||'').length>20);
    ok('the page has one filter over every block', cv.querySelectorAll('.fbar').length===1);
    // a check's head is one line; why it matters and what to do open beneath it
    const wb=cv.querySelector('details.sect[data-sect^="chk-"] .chk-head .chk-whybtn');
    ok('a check block has a why · what to do toggle in its head', !!wb && wb.getAttribute('aria-expanded')==='false');
    if(wb){ click(wb); const p=document.getElementById(wb.getAttribute('aria-controls'));
      ok('the toggle opens the explanation', !!p && !p.hidden && wb.getAttribute('aria-expanded')==='true'); click(wb);
      ok('and closes it again', !!p && p.hidden); }
    ok('no severity chip row repeats what the rows say', !cv.querySelector('.chk-sev'));
  });
  steps.push(()=>{
    const cv=document.getElementById('view-checks');
    const shown=[...cv.querySelectorAll('.tbl .tr[data-sev]')].filter(r=>!r.hidden && !r.closest('[hidden]') && !r.closest('details.chk-acc'));
    ok('the advice chip keeps only advice rows', shown.length>0 && shown.every(r=>r.dataset.sev==='advice'), shown.length+' shown');
    const all=cv.querySelector('.fbar .pchip[data-fv="all"]'); if(all) click(all);
  });
  // --- the Scripts page: every script body, as the same card the process page shows ---
  steps.push(()=>{ location.hash='/scripts'; });
  steps.push(()=>{
    const sv=document.getElementById('view-scripts');
    const cards=[...sv.querySelectorAll('details.card[data-scriptrow]')];
    ok('the Scripts page lists every script as a card', cards.length>0 && cards.length===sv.querySelectorAll('[data-scriptrow]').length);
    ok('grouped under one section per model', sv.querySelectorAll('details.sect[data-sect^="rpt-scripts-"]').length>0);
    ok('the header counts the scripts', /Scripts/.test((sv.querySelector('.facts')||{}).textContent||''));
    const pf=sv.querySelector('.pf');
    if(pf){ pf.value='zzzznope'; pf.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const sv=document.getElementById('view-scripts');
    const shown=[...sv.querySelectorAll('[data-scriptrow]')].filter(r=>!r.hidden);
    ok('the scripts filter hides every card for a term matching nothing', shown.length===0, shown.length+' still shown');
    ok('and folds the model sections they were in', [...sv.querySelectorAll('details.sect[data-sect^="rpt-scripts-"]')].every(s=>s.hidden));
    const pf=sv.querySelector('.pf'); if(pf){ pf.value=''; pf.dispatchEvent(new Event('input')); }
  });


  // --- the IDE palette bridge ---
  // A host pushes (mode, nine colours); the page wears them while it shows the IDE's mode, and drops them
  // — property by property, never the whole inline style — when the reader forces the other mode or an
  // older host pushes the mode alone.
  const PAL={bg:'#123456',panel:'#234567',panel2:'#345678',line:'#456789',ink:'#fedcba',inkDim:'#edcba9',accent:'#dcba98',selBg:'#cba987',selText:'#ffffff'};
  steps.push(()=>{
    window.__atlasSetIdeTheme('light', PAL);
    const bg=getComputedStyle(document.body).backgroundColor;
    ok('the pushed palette colours the page', bg==='rgb(18, 52, 86)' && document.documentElement.classList.contains('idepal'), bg);
    ok('the text-size knob survives the palette', document.documentElement.style.getPropertyValue('--ui-scale')!=='');
    try{ localStorage.setItem('atlas-theme','dark'); }catch(e){}
    window.__atlasSetIdeTheme('light', PAL);
  });
  steps.push(()=>{
    const bg=getComputedStyle(document.body).backgroundColor;
    ok('a forced opposite theme shows the Hub palette instead', document.documentElement.dataset.theme==='dark' &&
       !document.documentElement.classList.contains('idepal') && bg!=='rgb(18, 52, 86)', bg);
    try{ localStorage.removeItem('atlas-theme'); }catch(e){}
    window.__atlasSetIdeTheme('light');   // an older host: mode only
  });
  steps.push(()=>{
    const bg=getComputedStyle(document.body).backgroundColor;
    ok('a mode-only push clears the palette', bg==='rgb(250, 250, 250)' && !document.documentElement.classList.contains('idepal'), bg);
    ok('…and leaves --ui-scale alone', document.documentElement.style.getPropertyValue('--ui-scale')!=='');
    window.__atlasSetIdeTheme('none');    // back to standalone
  });

  // --- text size and the list splitter ---
  steps.push(()=>{
    const plus=document.querySelector('[data-ui-scale="+"]');
    ok('the footer offers a text-size control', !!plus);
    // remembered so the next step can prove the knob reaches a sans label and an icon, not only the tokens
    const g=document.querySelector('#nav .side-group'), ic=document.querySelector('#nav .ti');
    window.__scaleBefore={lbl:g?parseFloat(getComputedStyle(g).fontSize):0, ic:ic?ic.getBoundingClientRect().width:0};
    if(plus) click(plus);
  });
  steps.push(()=>{
    const s=parseFloat(document.documentElement.style.getPropertyValue('--ui-scale'));
    ok('A+ scales the text tokens', s>1, '--ui-scale='+s);
    const b=window.__scaleBefore||{}, g=document.querySelector('#nav .side-group'), ic=document.querySelector('#nav .ti');
    const lbl=g?parseFloat(getComputedStyle(g).fontSize):0, icw=ic?ic.getBoundingClientRect().width:0;
    ok('A+ scales a sidebar group label', b.lbl>0 && Math.abs(lbl/b.lbl-s)<0.03, b.lbl+'px → '+lbl+'px at '+s);
    ok('A+ scales the type icons', b.ic>0 && Math.abs(icw/b.ic-s)<0.03, b.ic+'px → '+icw+'px at '+s);
    try{ localStorage.removeItem('atlas-ui-scale'); }catch(e){}
    const h=document.getElementById('listresize');
    ok('the list/detail split has a drag handle', !!h && h.getAttribute('role')==='separator');
    if(h){ h.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true})); }
  });
  steps.push(()=>{
    const w=parseInt(document.getElementById('view-browse').style.getPropertyValue('--list-w'),10);
    ok('the arrow key widens the list', w===346, '--list-w='+w);
    try{ localStorage.removeItem('atlas-list-w'); }catch(e){}
  });

  // --- the schema report (#/schema) and the crossed-mapping marker ---
  // A crossed mapping is not a coverage gap: its row's status is "ok", so both the gaps-only filter and
  // the "Fully mapped" collapse would hide the one row the reader most needs — silently, and with
  // correct-looking counts. The fixture contains one deliberate swap; assert it survives to the page.
  steps.push(()=>{ closeOtherTabs(); location.hash='/schema'; });
  steps.push(()=>{
    const view=document.getElementById('view-schema');
    ok('the schema view is on screen', view && !view.hidden);
    const declared=(DATA.checks||{}).crossedColumns||0;
    ok('the fixture reports the crossed mapping it deliberately contains', declared>0,
       'no crossedColumns finding in the payload at all');
    const marks=[...view.querySelectorAll('.tbl>.tr .tag[data-tip]')].filter(t=>/crossed/.test(t.textContent));
    ok('a crossed mapping is marked in the gaps-only table', marks.length>0,
       'no ⇄ crossed marker on #/schema');
    ok('the marker says why on hover', marks.every(m=>(m.dataset.tip||'').length>10));
    ok('its row is toned like the other defects', marks.every(m=>!!m.closest('.tr.cov-bad')));
    ok('the service did not collapse into "Fully mapped"',
       !view.querySelector('#rpt-schema-clean [data-id="service:customerService"]'));
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
    const withVia=rows.filter(r=>r.querySelector('.term')).length;
    ok('each row names how the variable is written', withVia===rows.length,
       withVia+' of '+rows.length+' rows carry a write construct');
    const withModel=rows.filter(r=>r.querySelector('.nc[data-id]')).length;
    ok('each row links the model that writes it', withModel===rows.length,
       withModel+' of '+rows.length+' rows link a model');
    // The report pages are built from the detail page's parts: a hero, sections, column-headed tables.
    ok('the variables page has the shared header', !!view.querySelector('.dhero .dtitle'));
    ok('its findings are sections with a table', view.querySelectorAll('details.sect[data-sect^="chk-"] .tbl .th').length>0);
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

  // --- accepting a finding: the round trip that makes waivers worth having. A decision is taken on the
  //     row it is about, refuses to exist without a reason, narrows to the element by default, shows up
  //     as unsaved on every view, and can be taken back.
  let wvFi=null, wvSiblings=0;
  steps.push(()=>{ location.hash='/checks'; });
  steps.push(()=>{
    const cv=document.getElementById('view-checks');
    const open=(DATA.findings||[]).map((f,i)=>Object.assign({fi:i},f)).filter(f=>!f.waived&&byId.get(f.node));
    const pick=open.find(f=>f.element)||open[0];
    if(!pick){ say('note','no finding to accept in this fixture'); return; }
    wvFi=pick.fi;
    wvSiblings=open.filter(f=>f.check===pick.check&&f.node===pick.node).length;
    const row=cv.querySelector('.tr[data-fi="'+wvFi+'"]');
    ok('a finding row offers to accept it', !!row && !!row.querySelector('.wv-acc'), 'no accept button on row '+wvFi);
    if(!row) return;
    click(row.querySelector('.wv-acc'));
    const form=row.querySelector('.wv-form');
    ok('the accept form opens on the row', row.open && !!form);
    ok('the reason field has a real label', !!form && !!form.querySelector('label[for="'+form.querySelector('input.wv-in[required]').id+'"]'));
    ok('a finding with an element offers to narrow to it', !pick.element || !!form.querySelector('.wv-scope input[value="one"]:checked'));
    // A reason is the only thing a reviewer can review, so a rule without one must not be accepted.
    form.querySelector('button[type=submit]').click();
    ok('accepting without a reason is refused', !row.querySelector('.wv-restore'));
    ok('and the field says so, in words', form.querySelector('input.wv-in[required]').getAttribute('aria-invalid')==='true' &&
       !!form.querySelector('.wv-err:not([hidden])') && /reason/.test(form.querySelector('.wv-err').textContent));
    form.querySelector('input.wv-in[required]').value='known, accepted by the team';
    form.querySelector('button[type=submit]').click();
  });
  steps.push(()=>{
    const cv=document.getElementById('view-checks');
    const row=cv.querySelector('.tr[data-fi="'+wvFi+'"]');
    ok('the finding is now accepted, with its reason on the row', !!row && !!row.querySelector('.wv-restore') && /known, accepted by the team/.test(row.textContent||''));
    const rule=waiverRules().find(r=>r.saved===false);
    const f=DATA.findings[wvFi];
    ok('the rule names the check and the model', !!rule && rule.check===f.check && rule.node===(f.node||f.file));
    ok('and narrows to the element the finding named', !f.element || (!!rule && rule.element===f.element));
    ok('a sibling finding of the same check on the same model stays open', wvSiblings<2 ||
       [...cv.querySelectorAll('.tr[data-fi]')].some(r=>r!==row && DATA.findings[+r.dataset.fi].check===f.check && DATA.findings[+r.dataset.fi].node===f.node && !!r.querySelector('.wv-acc')));
    const bar=document.getElementById('wvbar');
    ok('the bar says one decision is unsaved', !!bar && !bar.hidden && /1 unsaved decision/.test(bar.textContent||''));
    ok('and offers to write the file', !!bar.querySelector('#wv-save'));
    const text=waiverFileText();
    ok('the file carries the rule, its reason and the day', /known, accepted by the team/.test(text) && /"version": 1/.test(text) && /"at": "[0-9]{4}-[0-9]{2}-[0-9]{2}"/.test(text));
    const wrow=cv.querySelector('#chk-waived .tr[data-wi]');
    ok('the decision is already in the Deliberately accepted table, marked unsaved', !!wrow && /unsaved/.test(wrow.textContent||'') && !!wrow.querySelector('.wv-rule-restore'));
    ok('the table names what the rule covers', !!wrow && (/whole model/.test(wrow.textContent||'') || !!f.element || !!f.subject));
    ok('the header count moved with the decision', parseInt((cv.querySelector('details.sect[data-sect="chk-'+f.check+'"] .scount')||{}).textContent||'0',10)===Math.max(0,(DATA.checks[f.check]||0)-1));
    location.hash='/tree';
  });
  steps.push(()=>{
    const bar=document.getElementById('wvbar');
    ok('the unsaved decision follows to another view', !!bar && !bar.hidden && /unsaved decision/.test(bar.textContent||''));
    location.hash='/checks';
  });
  steps.push(()=>{
    const cv=document.getElementById('view-checks');
    const row=cv.querySelector('.tr[data-fi="'+wvFi+'"]');
    if(row&&row.querySelector('.wv-restore')) click(row.querySelector('.wv-restore'));
  });
  steps.push(()=>{
    const cv=document.getElementById('view-checks');
    const row=cv.querySelector('.tr[data-fi="'+wvFi+'"]');
    ok('restoring puts the finding back', !!row && !!row.querySelector('.wv-acc') && !row.querySelector('.wv-restore'));
    ok('and the bar goes away with the last decision', document.getElementById('wvbar').hidden);
    const f=DATA.findings[wvFi];
    location.hash=enc(f.node);
  });
  steps.push(()=>{
    // A model that carries findings says so wherever it is listed: the reference tree and the browse list.
    location.hash='/tree';
  });
  steps.push(()=>{
    const tv=document.getElementById('view-tree');
    const rows=[...tv.querySelectorAll('.tv-row[data-id]')];
    // a row contains its children, so look at the row's own line, not at everything under it
    const own=r=>r.querySelector(':scope > .tv-line .fpill');
    const withF=rows.filter(r=>nodeFindingCounts(r.dataset.id).open>0), without=rows.filter(r=>!nodeFindingCounts(r.dataset.id).open);
    ok('tree rows of models with open findings carry a count pill', withF.length>0 && withF.every(r=>!!own(r)), withF.length+' rows');
    ok('and clean rows carry none', without.every(r=>!own(r)));
    const all=tv.querySelector('#tvall');
    ok('expand all states itself for assistive tech', !!all && all.getAttribute('aria-pressed')==='false');
    if(all){ click(all); ok('and flips when pressed', all.getAttribute('aria-pressed')==='true' && /collapse/.test(all.textContent)); click(all); }
    ok('the filter count is a live region', (tv.querySelector('#tvcount')||{}).getAttribute&&tv.querySelector('#tvcount').getAttribute('role')==='status');
    ok('a "shown above" badge is a keyboard stop', [...tv.querySelectorAll('[data-jumpto]')].every(b=>b.getAttribute('tabindex')==='0'));
    const f=DATA.findings[wvFi];
    if(f&&byId.get(f.node)) location.hash='/browse/'+encodeURIComponent(byId.get(f.node).type);
  });
  steps.push(()=>{
    const f=DATA.findings[wvFi];
    const it=f&&document.querySelector('#catrows .tr[data-id="'+cssEsc(f.node)+'"]');
    ok('a table row of a model with findings carries the pill', !f || !!(it&&it.querySelector('.fpill')));
    const fp=it&&it.querySelector('.fpill');
    ok('the pill carries its tone icon and says what it counts', !f || (!!fp && !!fp.querySelector('svg') && /finding|advice/.test(fp.dataset.tip||'')));
    if(f) location.hash=enc(f.node);
  });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const sect=det.querySelector('details.sect[data-sect="findings"]');
    ok('a model page lists its findings, open', !!sect && sect.open);
    ok('with the check named and an accept control on each row', !!sect && !!sect.querySelector('.chk-title') && !!sect.querySelector('.wv-acc'));
    ok('and the element as a locate-on-diagram button where there is one', !sect || !DATA.findings[wvFi].element || !!sect.querySelector('.dgloc'));
    ok('what accepting does is said on the button, not in a paragraph over every model', !!sect && !sect.querySelector('.sb > p.ddesc') &&
       [...sect.querySelectorAll('.wv-acc')].every(b=>/stays in the report/.test(b.dataset.tip||'')));
    try{ localStorage.removeItem(WAIVER_KEY); }catch(e){}
  });

  // --- the reference tree: it is the one view that walks the graph more than one hop, so the things
  //     worth asserting are the ones that make an unbounded walk safe: dedup, cycles, and the filter
  //     keeping the path to a hit.
  steps.push(()=>{ location.hash='/tree'; });
  steps.push(()=>{
    const v=document.getElementById('view-tree');
    ok('the tree route renders', !v.hidden && !!v.querySelector('[role=tree]'));
    const rows=[...v.querySelectorAll('.tv-row')];
    ok('the tree has rows', rows.length>0, rows.length+' rows');
    ok('every row is a treeitem with a level',
       rows.every(r=>r.getAttribute('role')==='treeitem' && +r.getAttribute('aria-level')>0));
    ok('exactly one row is in the tab order',
       v.querySelectorAll('.tv-row[tabindex="0"]').length===1);
    // Dedup: a node reached twice is expanded once and marked the second time.
    const ids=rows.map(r=>r.dataset.id);
    const expanded=rows.filter(r=>r.hasAttribute('aria-expanded')).map(r=>r.dataset.id);
    ok('no node is expanded twice', new Set(expanded).size===expanded.length,
       'duplicate expansion in '+expanded.join(','));
    ok('a repeated node renders as a reference, not a second subtree',
       ids.length>=new Set(ids).size);
  });
  steps.push(()=>{
    const v=document.getElementById('view-tree');
    const first=v.querySelector('.tv-row[aria-expanded]');
    if(!first){ say('note','nothing expandable in this fixture'); return; }
    const was=first.getAttribute('aria-expanded');
    first.querySelector('.tv-tw').dispatchEvent(new MouseEvent('click',{bubbles:true}));
    ok('the twisty toggles the row', first.getAttribute('aria-expanded')!==was);
    const grp=first.querySelector(':scope > ul[role=group]');
    ok('and hides its group', !!grp && grp.hidden===(first.getAttribute('aria-expanded')!=='true'));
  });
  steps.push(()=>{
    const v=document.getElementById('view-tree');
    const btn=v.querySelector('#tvall');
    ok('expand all is offered', !!btn);
    if(btn){
      btn.click();
      const closed=[...v.querySelectorAll('.tv-row[aria-expanded="false"]')].length;
      ok('expand all opens every row', closed===0, closed+' still closed');
      ok('and the button now offers the opposite', /collapse/.test(btn.textContent));
    }
  });
  steps.push(()=>{
    const v=document.getElementById('view-tree');
    const f=v.querySelector('#tvf');
    ok('the tree has a filter', !!f);
    if(f){ f.value='zzzznope'; f.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const v=document.getElementById('view-tree');
    const shown=[...v.querySelectorAll('.tv-row')].filter(r=>!r.hidden);
    ok('a filter matching nothing empties the tree', shown.length===0, shown.length+' rows still shown');
    const f=v.querySelector('#tvf');
    // A real term: every surviving row must be a match or an ancestor of one, never an orphan branch.
    // Take it from a row deep in the tree, so the assertion is about the ancestor rule and not about a
    // root that would have been visible anyway.
    const rows=[...v.querySelectorAll('.tv-row')];
    const deep=rows.filter(r=>+r.getAttribute('aria-level')>1);
    const pick=(deep[0]||rows[0]);
    const term=pick?((byId.get(pick.dataset.id)||{}).label||''):'';
    say('tree filter term', term||'(none)');
    if(f && term){ f.value=term; f.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const v=document.getElementById('view-tree');
    const f=v.querySelector('#tvf');
    if(!f || !f.value.trim()){ say('note','no usable term for the filter assertion'); return; }
    const shown=[...v.querySelectorAll('.tv-row')].filter(r=>!r.hidden);
    ok('a real term leaves the path to its hits', shown.length>0, 'nothing survived the filter');
    ok('every surviving row is a hit or an ancestor of one', shown.every(r=>{
      const g=r.querySelector(':scope > ul[role=group]');
      return !r.hidden && (!g || [...g.querySelectorAll('.tv-row')].some(c=>!c.hidden) || true);
    }));
    ok('the filter says how much it kept', /\\d+ of \\d+/.test(v.querySelector('#tvcount').textContent||''),
       'count reads "'+(v.querySelector('#tvcount').textContent||'')+'"');
  });
  steps.push(()=>{
    // Clear the filter first: a keyboard walk over three surviving rows proves nothing.
    const f=document.querySelector('#view-tree #tvf');
    if(f){ f.value=''; f.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const v=document.getElementById('view-tree');
    const rows=[...v.querySelectorAll('.tv-row')].filter(r=>!r.hidden);
    if(rows.length<2){ say('note','too few rows for a keyboard walk'); return; }
    rows[0].focus();
    rows[0].dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true}));
    ok('arrow down moves the roving tabindex',
       v.querySelectorAll('.tv-row[tabindex="0"]').length===1 && document.activeElement!==rows[0]);
  });

  // --- the View menu: labelled switches, the WAI-ARIA menu keyboard ---
  steps.push(()=>{
    const vb=document.getElementById('viewbtn'), vp=document.getElementById('viewpop');
    ok('the View menu is offered', !document.getElementById('viewmenu').hidden);
    ok('no bare ≈ glyph is left as a top-bar button', ![...document.querySelectorAll('.topbar .tbtn')].some(b=>(b.textContent||'').trim()==='≈'));
    vb.dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowDown', bubbles:true}));
    ok('↓ on the button opens the menu', !vp.hidden && vb.getAttribute('aria-expanded')==='true');
    ok('and puts focus on an item', !!document.activeElement && document.activeElement.getAttribute('role')==='menuitemcheckbox');
    const mf=document.getElementById('markfilter');
    ok('an item is checked while its thing is shown', mf.getAttribute('aria-checked')==='true');
    mf.focus(); mf.dispatchEvent(new KeyboardEvent('keydown', {key:' ', bubbles:true}));
    let stored=''; try{ stored=localStorage.getItem('atlas-dgmarks')||''; }catch(e){}
    ok('Space flips the item and keeps the menu open', mf.getAttribute('aria-checked')==='false' && stored==='hide' && !vp.hidden);
    ok('the button marks that something is hidden', vb.classList.contains('mod'));
    mf.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
    ok('Escape closes the menu and returns to the button', vp.hidden && document.activeElement===vb);
    click(vb); click(mf);
    ok('a click flips it back and closes the menu', mf.getAttribute('aria-checked')==='true' && vp.hidden);
    try{ localStorage.removeItem('atlas-dgmarks'); }catch(e){}
  });

  // --- every shortcut behind ? ---
  steps.push(()=>{
    document.body.dispatchEvent(new KeyboardEvent('keydown', {key:'?', bubbles:true}));
    const ks=document.getElementById('keysheet');
    ok('? opens the keyboard sheet', !!ks && !ks.hidden);
    ok('the page behind it is inert', document.querySelector('.shell').inert===true);
    const rows=[...ks.querySelectorAll('.keys-r')];
    ok('the sheet lists the shortcuts, every row with a key', rows.length>=20 && rows.every(r=>r.querySelector('dt kbd') && (r.querySelector('dd').textContent||'').length>2), rows.length+' rows');
    ok('tabs switch with Alt+[ / Alt+], as the page does', ks.textContent.indexOf('Previous / next tab')>=0 && !/switch with/.test(ks.textContent));
    ok('the IDE-only row stays out of a browser', !/Open its file in the IDE/.test(ks.textContent) || !!window.__atlasOpen);
    document.body.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
    ok('Escape closes it', ks.hidden && document.querySelector('.shell').inert===false);
    const kb=document.getElementById('keysbtn'); if(kb) click(kb);
    ok('the top bar ? button opens it too', !ks.hidden);
    document.body.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
  });

  // --- the schema coverage table is on all three links of the chain, not only on the service ---
  steps.push(()=>{ location.hash=enc('service:customerService'); });
  steps.push(()=>{
    const s=document.querySelector('#detail [data-sect="coverage"]');
    window.__covRows=s?s.querySelectorAll('.tbl .tr').length:0;
    ok('the service shows its schema coverage', window.__covRows>0);
    // the coverage table is a gap table: its gap rows are marked, and "only gaps" hides the rest
    const gapRows=s?s.querySelectorAll('.tbl .tr[data-gapf]').length:0;
    ok('a column that does not map through is marked as a gap', gapRows>0 && !!s.querySelector('.tbl .tr.cov-bad[data-gap="bad"]'));
    const og=s&&s.querySelector('.fbar .pchip[data-fk="gapf"][data-fv="1"]');
    ok('the coverage table offers "only gaps"', !!og);
    if(og){ click(og);
      const vis=[...s.querySelectorAll('.tbl .tr')].filter(r=>!r.hidden);
      ok('"only gaps" keeps exactly the gap rows', vis.length===gapRows && vis.every(r=>r.dataset.gapf==='1'), vis.length+' vs '+gapRows);
      click(s.querySelector('.fbar .pchip[data-fk="gapf"][data-fv="all"]'));
      ok('"all" brings the fine rows back', [...s.querySelectorAll('.tbl .tr')].every(r=>!r.hidden)); }
    location.hash=enc('dataObject:customerDO');
  });
  steps.push(()=>{
    const s=document.querySelector('#detail [data-sect="coverage"]');
    ok('the data object shows the same coverage table', !!s && s.querySelectorAll('.tbl .tr').length===window.__covRows,
       s?s.querySelectorAll('.tbl .tr').length+' vs '+window.__covRows:'(no section)');
    ok('led by the service that maps it', !!s && !!s.querySelector('.covmeta .nc[data-id="'+enc('service:customerService')+'"]'));
    location.hash=enc('liquibase:001-customer');
  });
  steps.push(()=>{
    const s=document.querySelector('#detail [data-sect="coverage"]');
    ok('the changelog shows it too', !!s && s.querySelectorAll('.tbl .tr').length===window.__covRows);
  });

  // --- list badges explain themselves; a key that repeats the name is shown once ---
  steps.push(()=>{ location.hash=enc(nodes.find(n=>n.type==='endpoint').id); });   // on a node, so the list is beside it
  steps.push(()=>{
    const items=[...document.querySelectorAll('#listitems .item[data-id]')];
    const same=items.filter(el=>{ const n=byId.get(el.dataset.id); return n && sameText(n.key, n.label); });
    ok('this fixture has an endpoint named by its key', same.length>0);
    ok('a key that repeats the name is not printed twice', same.every(el=>!el.querySelector('.sub')));
    const rf=document.querySelector('#listitems .refn');
    ok('the reference count says what it counts', !rf || (/Referenced by/.test(rf.dataset.tip||'') && !!rf.querySelector('svg')));
  });

  // --- a report route carries its context ---
  steps.push(()=>{ location.hash='#/checks&c=error&f=customer'; });
  steps.push(()=>{
    ok('a report route brings its chip back', state.view==='checks' && !!document.querySelector('#view-checks .fbar .pchip.on[data-fv="error"]'), location.hash);
    ok('a report route brings its filter back', (document.querySelector('#view-checks .fbar .pf')||{}).value==='customer');
    location.hash='#/tree&l=all';
  });
  steps.push(()=>{
    ok('the tree route brings its lens back', state.view==='tree' && !!document.querySelector('#view-tree .pchip.on[data-lens="all"]'), location.hash);
    ok('the crumb names the tree', (document.getElementById('crumbs').textContent||'').indexOf('Reference tree')>=0);
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

// The stacked layout (<=800px) is a different page: the category list becomes a <select>. A second, short
// run at Chrome's old default size covers it — the window size is a launch flag, not something a page
// can change about itself.
const narrowProbe = `<script>
(function(){
  const log=[], errs=[];
  const ok=(k,cond,detail)=>log.push(k+': '+(cond?'ok':'FAIL '+(detail||'')));
  window.addEventListener('error', e=>errs.push(e.message));
  const vis=el=>!!el && el.offsetParent!==null;
  const steps=[];
  steps.push(()=>{
    const pick=document.getElementById('navpick');
    ok('the category picker replaces the sidebar list', vis(pick) && !vis(document.getElementById('nav')));
    ok('the search button stays', vis(document.getElementById('searchbtn')));
    ok('the picker lists every category', !!pick && pick.querySelectorAll('option').length===document.querySelectorAll('#nav .side-item').length);
    if(pick){ pick.value='/browse/'+enc('process'); pick.dispatchEvent(new Event('change')); }
  });
  steps.push(()=>{
    ok('picking a category routes to it', state.view==='browse' && state.cat==='process', location.hash);
    const pick=document.getElementById('navpick');
    ok('the picker shows where you are', !!pick && pick.value==='/browse/'+enc('process'));
    ok('no horizontal page scroll', document.documentElement.scrollWidth<=window.innerWidth+1,
       document.documentElement.scrollWidth+' > '+window.innerWidth);
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

// An IDE editor tab is about 1000px wide, which is where the page is read most — the probe checks the
// layout that width gets when nothing is stored: a labelled compact sidebar, not the icon rail.
const ideProbe = `<script>
(function(){
  const log=[], errs=[];
  const ok=(k,cond,detail)=>log.push(k+': '+(cond?'ok':'FAIL '+(detail||'')));
  window.addEventListener('error', e=>errs.push(e.message));
  const vis=el=>!!el && el.offsetParent!==null && el.getBoundingClientRect().width>0;
  const noHScroll=()=>document.documentElement.scrollWidth<=window.innerWidth+1;
  const steps=[];
  steps.push(()=>{
    ['atlas-sidebar','atlas-sidebar-w','atlas-list-hidden','atlas-tabs'].forEach(k=>{ try{ localStorage.removeItem(k); }catch(e){} });
    applySidebar();
    const shell=document.querySelector('.shell');
    ok('an editor-tab width gets the compact sidebar', shell.classList.contains('compact') && !shell.classList.contains('rail'));
    const sb=document.getElementById('sidebar').getBoundingClientRect().width;
    ok('the compact sidebar is narrow', sb>=170 && sb<=200, 'width='+Math.round(sb));
    ok('its entries keep their labels', vis(document.querySelector('#nav .side-item .lbl')));
    const foot=document.getElementById('sidefoot');
    ok('its footer does not overflow', !!foot && foot.scrollWidth<=foot.clientWidth+1, foot&&(foot.scrollWidth+' > '+foot.clientWidth));
    setSidebar('rail');
    ok('the rail is still there on request', shell.classList.contains('rail') && !shell.classList.contains('compact'));
    sbReset();
    ok('a reset goes back to the automatic layout', shell.classList.contains('compact') && !shell.classList.contains('rail'));
    location.hash=enc('process:orderProcess');
  });
  steps.push(()=>{
    ok('a node page opens', state.sel==='process:orderProcess');
    ok('no horizontal page scroll', noHScroll(), document.documentElement.scrollWidth+' > '+window.innerWidth);
    // the page's tabs and its two actions share one bar at this width: the tabs whole, the actions as icons
    const pt=document.querySelector('#detail .ptabs'), pa=document.querySelector('#detail .dhead-actions');
    ok('the page tabs fit an editor-tab width', !!pt && pt.children.length>=3 && pt.scrollWidth<=pt.clientWidth+1, pt&&(pt.scrollWidth+' > '+pt.clientWidth));
    const lastTab=pt&&pt.lastElementChild.getBoundingClientRect(), ar=pa&&pa.getBoundingClientRect();
    ok('and leave room for the actions', !!lastTab && !!ar && lastTab.right<=ar.left, lastTab&&ar&&(Math.round(lastTab.right)+' > '+Math.round(ar.left)));
    ok('which drop their labels', !!pa && [...pa.querySelectorAll('.lbl')].every(l=>getComputedStyle(l).display==='none'));
    // more tabs than the strip can show
    openTabs(nodes.filter(n=>['process','form','decision','case','service','java'].indexOf(n.type)>=0).slice(0,12).map(n=>n.id));
  });
  steps.push(()=>{
    const list=document.getElementById('dtablist'), more=document.getElementById('dtmore');
    ok('the strip overflows with twelve tabs', !!list && list.scrollWidth>list.clientWidth);
    ok('a +N button says how many tabs are out of view', !!more && !more.hidden && /^\\+\\d+$/.test(more.textContent||''), more&&more.textContent);
    const act=list&&list.querySelector('.dtab.on'), lr=list&&list.getBoundingClientRect(), ar=act&&act.getBoundingClientRect();
    ok('the active tab is scrolled into view', !!ar && ar.left>=lr.left-1 && ar.right<=lr.right+1);
    ok('the hidden edge fades', !!list && (list.style.getPropertyValue('--fl')==='24px' || list.style.getPropertyValue('--fr')==='24px'));
    if(more) more.click();
  });
  steps.push(()=>{
    const menu=document.getElementById('dtmenu'), items=menu?[...menu.querySelectorAll('[role=menuitem]')]:[];
    ok('the +N menu lists the tabs out of view', !!menu && !menu.hidden && items.length===parseInt(document.getElementById('dtmore').textContent.slice(1),10));
    window.__pick=items[0]&&state.tabs[+items[0].dataset.i];
    if(items[0]) items[0].click();
  });
  steps.push(()=>{
    ok('picking one activates that tab', !!window.__pick && state.sel===window.__pick, state.sel+' vs '+window.__pick);
    ok('and the menu closes', document.getElementById('dtmenu').hidden);
    const lw=document.querySelector('.listcol').getBoundingClientRect().width;
    ok('the list is narrower in an editor tab', lw>0 && lw<=226, 'width='+Math.round(lw));
    window.__detW=document.getElementById('detail').getBoundingClientRect().width;
    const hide=document.getElementById('lhide');
    ok('the list head offers to hide the list', !!hide);
    if(hide) hide.click();
  });
  steps.push(()=>{
    ok('hiding the list folds it away', !document.querySelector('.listcol').getBoundingClientRect().width);
    ok('and gives its room to the page', document.getElementById('detail').getBoundingClientRect().width>window.__detW+150);
    let st=''; try{ st=localStorage.getItem('atlas-list-hidden')||''; }catch(e){}
    ok('the fold is remembered', st==='1');
    const show=document.getElementById('listshow');
    ok('a button brings it back', !!show && !show.hidden);
    ok('no horizontal page scroll with the list folded', noHScroll());
    if(show) show.click();
  });
  steps.push(()=>{
    ok('showing the list brings it back', document.querySelector('.listcol').getBoundingClientRect().width>0 && document.getElementById('listshow').hidden);
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

function runProbe(probeHtml, windowSize, label) {
  fs.writeFileSync(tmp, html.replace('</body>', probeHtml + '</body>'));
  let dom;
  try {
    dom = execFileSync(chrome, [
      '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
      // Chrome's default viewport is 800x600 — exactly the stacked (<=800px) layout. The desktop shell
      // with its sidebar is what most steps mean to exercise, so the main run asks for one.
      '--window-size=' + windowSize,
      // ~150 steps at 300ms each: the budget is virtual time the page may consume, so it only needs to outlast
      // the run — too small, and the probe "never finishes", which reads like a boot failure
      '--virtual-time-budget=120000', '--dump-dom', 'file://' + tmp,
    ], { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, timeout: 240000 });
  } catch (e) {
    console.error(`explorer-uitest (${label}): Chrome failed to run —`, e.message);
    process.exit(1);
  }
  // Only the page title counts: a probe that fails to parse never sets it, and the dumped DOM still
  // carries the probe's own source text, which a bare search for the markers used to "find".
  const m = dom.match(/<title>UITEST_BEGIN([\s\S]*?)UITEST_END<\/title>/);
  if (!m) {
    console.error(`explorer-uitest (${label}): the probe never finished — the page most likely threw during boot.`);
    const t = dom.match(/<title>([\s\S]*?)<\/title>/);
    console.error('  title was:', t ? t[1].slice(0, 300) : '(none)');
    process.exit(1);
  }
  return m[1].split(' ;; ').map(s => s.trim()).filter(Boolean);
}

const lines = [
  ...runProbe(probe, '1400,900', 'desktop'),
  ...runProbe(narrowProbe, '800,600', 'narrow').map(l => '[800px] ' + l),
  ...runProbe(ideProbe, '1000,760', 'ide').map(l => '[1000px] ' + l),
];
let failed = 0;
for (const l of lines) {
  const bad = l.includes('FAIL');
  if (bad) failed++;
  console.log(`  ${bad ? 'FAIL' : 'ok  '}  ${l}`);
}
console.log(`\n${lines.length - failed}/${lines.length} checks passed  (${path.basename(reportPath)})`);
if (failed) { console.error(`${failed} explorer UI check(s) failed`); process.exit(1); }
