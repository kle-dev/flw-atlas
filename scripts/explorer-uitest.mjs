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

  // --- a form's buttons: the Fields row expands into what the button does ---
  // The row used to carry id, caption and type only, so a form could show that it triggers an action
  // while staying silent about which button did it, with which payload. These checks pin the three
  // things the row now has to answer without leaving the panel.
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('form:orderForm'); });
  steps.push(()=>{
    const sect=document.querySelector('#detail [data-sect="formfields"]');
    ok('the form lists its fields', !!sect);
    if(sect) sect.open=true;
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
    const lf=document.getElementById('lf');
    if(lf){ lf.value='setVariable'; lf.dispatchEvent(new Event('input')); }
  });
  steps.push(()=>{
    const it=document.querySelector('#listitems .item[data-id]');
    ok('a script-body match still shows rows', !!it);
    ok('and the row says why it matched', !!it && /script/.test((it.querySelector('.sub')||{}).textContent||''),
       it?((it.querySelector('.sub')||{}).textContent||'(no sub)'):'(none)');
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
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('app:demoApp'); });
  steps.push(()=>{
    const txt=document.getElementById('detail').textContent;
    ok('the detail panel shows the model Description', /Description/.test(txt)&&/Miniature fixture app/.test(txt),
       txt.slice(0,140));
  });

  // --- a model lists what it uses: the section is rebuilt from the artifact nodes' usedBy ---
  // The generator strips _uses from the payload, and for a whole run of releases the panel still
  // read that key — so "which variables does this process touch" rendered nowhere.
  steps.push(()=>{ closeOtherTabs(); location.hash=enc('process:orderProcess'); });
  steps.push(()=>{
    const det=document.getElementById('detail');
    const sect=det.querySelector('[data-sect="uses"]');
    ok('a model lists the variables and expressions it uses', !!sect, 'no [data-sect="uses"] on the process');
    ok('and each one is a chip you can follow', !!sect && !!sect.querySelector('details.uses .nc[data-id^="variable"]'),
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
    const lf=document.getElementById('lf'), ls=document.getElementById('lsort');
    if(lf){ lf.value='order'; lf.dispatchEvent(new Event('input')); }
    if(ls){ ls.value='refs'; ls.dispatchEvent(new Event('change')); }
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
    const lf=document.getElementById('lf'), ls=document.getElementById('lsort');
    ok('a link with &f= restores the filter', !!lf && lf.value==='zzz-none', lf?('filter="'+lf.value+'"'):'no #lf');
    ok('a link with &s= restores the sort', !!ls && ls.value==='refs', ls?('sort="'+ls.value+'"'):'no #lsort');
    location.hash=enc('form:orderForm');
  });
  steps.push(()=>{
    ok('following a node in that category keeps its list context in the link',
       decodeURIComponent(location.hash).indexOf('&f=zzz-none')>0, 'hash='+location.hash);
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

  // --- the overview's health list ---
  steps.push(()=>{ location.hash='/overview'; });
  steps.push(()=>{
    const rows=[...document.querySelectorAll('#view-overview .hlist .hrow')];
    ok('the overview has a health list', rows.length>0);
    const rank=r=>r.classList.contains('tone-bad')?0:r.classList.contains('tone-warn')?1:2;
    ok('health rows are sorted bad → warn → clean', rows.every((r,i)=>!i||rank(rows[i-1])<=rank(r)));
    ok('only rows with findings are links', rows.every(r=>r.classList.contains('hall') ||
       r.hasAttribute('data-jump')===(parseInt(r.querySelector('.hn').textContent,10)>0)));
    ok('clean checks are folded away', rows.filter(r=>r.classList.contains('tone-ok')&&!r.classList.contains('hall')).every(r=>!!r.closest('details.hclean')));
    ok('the inventory names the types', document.querySelectorAll('#view-overview .invc[data-cat]').length>0);
    const first=document.querySelector('#view-overview .hrow[data-jump]');
    ok('this report has a finding to click', !!first);
    if(first) click(first);
  });
  steps.push(()=>{
    ok('a health row opens the Checks page', location.hash==='#/checks' && !!document.querySelector('#view-checks .hlist'));
    ok('no health card wall anywhere', !document.querySelector('.hcard'));
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

function runProbe(probeHtml, windowSize, label) {
  fs.writeFileSync(tmp, html.replace('</body>', probeHtml + '</body>'));
  let dom;
  try {
    dom = execFileSync(chrome, [
      '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
      // Chrome's default viewport is 800x600 — exactly the stacked (<=800px) layout. The desktop shell
      // with its sidebar is what most steps mean to exercise, so the main run asks for one.
      '--window-size=' + windowSize,
      // ~72 steps at 300ms each: the budget is virtual time the page may consume, so it only needs to outlast the run
      '--virtual-time-budget=45000', '--dump-dom', 'file://' + tmp,
    ], { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, timeout: 120000 });
  } catch (e) {
    console.error(`explorer-uitest (${label}): Chrome failed to run —`, e.message);
    process.exit(1);
  }
  const m = dom.match(/UITEST_BEGIN([\s\S]*?)UITEST_END/);
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
];
let failed = 0;
for (const l of lines) {
  const bad = l.includes('FAIL');
  if (bad) failed++;
  console.log(`  ${bad ? 'FAIL' : 'ok  '}  ${l}`);
}
console.log(`\n${lines.length - failed}/${lines.length} checks passed  (${path.basename(reportPath)})`);
if (failed) { console.error(`${failed} explorer UI check(s) failed`); process.exit(1); }
