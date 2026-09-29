#!/usr/bin/env node
/**
 * Runtime test for the ER diagram designer — `<project>.erd.html`, a page of its own beside the explorer
 * (core/src/main/resources/frontend/erd.*) — driven in headless Chrome over the DevTools protocol with real input.
 *
 * Real input, not dispatched DOM events: the canvas captures the pointer (setPointerCapture) for every drag,
 * and a synthetic MouseEvent skips the pointer pipeline entirely — it would pass while a real mouse fails
 * (the diagram card's click bug, 2026-07-28). So every drag and click here is Input.dispatchMouseEvent at
 * coordinates measured after the route has landed, and every key is Input.dispatchKeyEvent.
 *
 * What it proves, on the demo project's page (`--erd`):
 *  - the page opens on the diagram the project keeps (docs/orders.atlas-erd.json), drawn with its relation;
 *  - a table dragged from the list lands on the canvas, the + button adds one, a proposal from the models
 *    can be taken, a relation drawn from a card's dot gets a name and a count at each end, drawn as crow's feet,
 *    and turns round keeping them;
 *  - a frame drawn over two tables holds them: it is named, moves with them, resizes by a corner, keeps them
 *    together when the diagram is arranged, and goes without them;
 *  - a card expands and folds, a column can be dragged to the top, a colour set and undone; every card expands
 *    and collapses at once;
 *  - a search (⌘F) finds columns across every table, dims what does not match, marks what does, and goes to a
 *    result — adding its table when the diagram does not have it;
 *  - the diagram survives a reload (localStorage), round-trips through its file format, exports a picture
 *    without the canvas handles, arranges a heap of tables by their relations (and undoes that in one step),
 *    presents full-page and comes back on Escape, deletes with Delete;
 *  - the page does not scroll sideways at a narrow width;
 * and, on an explorer, that none of it is there: no sidebar entry, no code, and a `#/erd` link (a 0.28 one)
 * falls back to the overview.
 *
 * Usage:  node scripts/erd-uitest.mjs <project.erd.html> --plain <project.explorer.html> [--chrome <path>]
 * Wired up as `./gradlew :cli:erdUiTest`, which skips when node or Chrome is missing (unless ATLAS_REQUIRE_BROWSER_TESTS=1).
 */
import fs from 'fs';
import path from 'path';
import os from 'os';
import { spawn } from 'child_process';

const args = process.argv.slice(2);
const flagValue = f => (args.indexOf(f) >= 0 ? args[args.indexOf(f) + 1] : null);
const plainPath = flagValue('--plain');
const chromeArg = flagValue('--chrome');
const reportPath = args.find((a, i) => !a.startsWith('--') && !['--plain', '--chrome'].includes(args[i - 1]));
if (!reportPath || !plainPath) {
  console.error('usage: node scripts/erd-uitest.mjs <project.erd.html> --plain <project.explorer.html> [--chrome <path>]');
  process.exit(2);
}
const CHROME_CANDIDATES = [
  chromeArg, process.env.CHROME_PATH,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser',
].filter(Boolean);
const chrome = CHROME_CANDIDATES.find(p => { try { fs.accessSync(p, fs.constants.X_OK); return true; } catch { return false; } });
if (!chrome) {
  if (process.env.ATLAS_REQUIRE_BROWSER_TESTS === '1') {
    console.error('erd-uitest: FAILED — no Chrome/Chromium found and ATLAS_REQUIRE_BROWSER_TESTS=1');
    process.exit(1);
  }
  console.log('erd-uitest: skipped — no Chrome/Chromium found');
  process.exit(0);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
let failed = 0, passed = 0;
function ok(label, cond, detail) {
  if (cond) { passed++; return; }
  failed++;
  console.error('FAIL ' + label + (detail !== undefined ? ' — ' + JSON.stringify(detail) : ''));
}

/** One headless Chrome on a fresh profile over --remote-debugging-pipe; [fn] gets a small page driver. */
async function withChrome(fn) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'atlas-erd-uitest-'));
  const proc = spawn(chrome, [
    '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars', '--window-size=1440,900',
    '--no-first-run', '--disable-background-networking', '--disable-component-update', '--disable-sync',
    '--user-data-dir=' + profile, '--remote-debugging-pipe', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'] });
  let stderr = ''; proc.stderr.on('data', d => { stderr += d; });
  const exited = new Promise(res => proc.on('exit', res));
  let seq = 0, buf = ''; const pending = new Map(); const errors = [];
  proc.stdio[4].on('data', d => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\0')) >= 0) {
      const m = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1);
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
      else if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.exception
        ? m.params.exceptionDetails.exception.description : m.params.exceptionDetails.text);
    }
  });
  const call = (method, params = {}, sessionId) => new Promise((res, rej) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); rej(new Error(method + ': no answer in 20s. ' + stderr.slice(-300))); }, 20000);
    pending.set(id, m => { clearTimeout(timer); m.error ? rej(new Error(method + ': ' + m.error.message)) : res(m.result); });
    proc.stdio[3].write(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }) + '\0');
  });
  try {
    const target = (await call('Target.getTargets')).targetInfos.find(t => t.type === 'page');
    const { sessionId } = await call('Target.attachToTarget', { targetId: target.targetId, flatten: true });
    const send = (m, p) => call(m, p, sessionId);
    await send('Runtime.enable');
    await send('Page.enable');
    const page = {
      errors,
      async open(url) { await send('Page.navigate', { url }); await page.waitFor('document.readyState==="complete" && !document.getElementById("atlas-boot")', 15000); await sleep(300); },
      async eval(expr) {
        const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
        if (r.exceptionDetails) throw new Error('eval failed: ' + expr.slice(0, 120) + ' — ' + (r.exceptionDetails.exception || {}).description);
        return r.result.value;
      },
      async waitFor(expr, ms = 5000) {
        const until = Date.now() + ms;
        while (Date.now() < until) { try { if (await page.eval('!!(' + expr + ')')) return true; } catch (e) { /* not yet */ } await sleep(80); }
        return false;
      },
      /** The centre of the first element matching [sel] (or a point relative to its box), in page pixels. */
      async at(sel, fx = 0.5, fy = 0.5) {
        const p = await page.eval(`(function(){ const e=document.querySelector(${JSON.stringify(sel)}); if(!e) return null;
          const r=e.getBoundingClientRect(); return {x:r.left+r.width*${fx}, y:r.top+r.height*${fy}}; })()`);
        if (!p) throw new Error('no element for ' + sel);
        return p;
      },
      async mouse(type, p, buttons = 0) {
        await send('Input.dispatchMouseEvent', { type, x: p.x, y: p.y, button: type === 'mouseMoved' && !buttons ? 'none' : 'left', buttons, clickCount: type === 'mouseMoved' ? 0 : 1 });
      },
      async click(p) { await page.mouse('mouseMoved', p); await page.mouse('mousePressed', p, 1); await page.mouse('mouseReleased', p); await sleep(120); },
      async drag(from, to, steps = 8) {
        await page.mouse('mouseMoved', from); await page.mouse('mousePressed', from, 1);
        for (let i = 1; i <= steps; i++) { await page.mouse('mouseMoved', { x: from.x + (to.x - from.x) * i / steps, y: from.y + (to.y - from.y) * i / steps }, 1); await sleep(16); }
        await page.mouse('mouseReleased', to); await sleep(150);
      },
      async key(key, code, modifiers = 0) {
        // Enter needs its text to submit a form, the way a real key press carries it; with a modifier
        // held nothing is typed, so no text then.
        const VK = { Enter: 13, Escape: 27, Delete: 46, Backspace: 8 };
        const text = key === 'Enter' ? '\r' : (key.length === 1 && !(modifiers & 6) ? key : undefined);
        await send('Input.dispatchKeyEvent', { type: 'keyDown', key, code, modifiers, text,
          windowsVirtualKeyCode: VK[key] || (key.length === 1 ? key.toUpperCase().charCodeAt(0) : undefined) });
        await send('Input.dispatchKeyEvent', { type: 'keyUp', key, code, modifiers });
        await sleep(120);
      },
      async type(text) { await send('Input.insertText', { text }); await sleep(120); },
      async viewport(w, h) { await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false }); await sleep(250); },
    };
    return await fn(page);
  } finally {
    await call('Browser.close').catch(() => proc.kill());
    await Promise.race([exited, sleep(10000)]);
    try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) { /* a temp dir */ }
  }
}

const T = 'ATLAS_ERD._test';
const erdUrl = 'file://' + path.resolve(reportPath);

await withChrome(async page => {
  await page.open(erdUrl);
  ok('the page is the designer', await page.eval(`!!document.querySelector('.erd') && document.getElementById('erd-proj').textContent==='flowable-demo'`));
  ok('…and says which Atlas made it, when', /Atlas .* generated/.test(await page.eval(`document.getElementById('erd-prov').textContent`)));
  ok('the list holds the project’s tables', await page.eval(`document.querySelectorAll('.erd-item').length`) === 3);
  ok('it opens on the diagram the project keeps', await page.eval(`${T}.active().name`) === 'Orders and customers',
    await page.eval(`${T}.active().name`));
  ok('…with its tables drawn', await page.eval(`document.querySelectorAll('.erd-card').length`) === 2);
  ok('…and its relation, named', await page.eval(`[...document.querySelectorAll('.erd-rel text')].some(t=>/placed by/.test(t.textContent))`));
  ok('a drawn relation hides the proposal for the same pair', await page.eval(`document.querySelectorAll('.erd-sug').length`) === 0);
  ok('the status says where the diagram comes from', /docs\/orders\.atlas-erd\.json/.test(await page.eval(`document.querySelector('.erd-status').textContent`)));

  // ---- the list's width: dragged wider for a long table name, remembered, reset ----
  const sideW = () => page.eval(`Math.round(document.querySelector('.erd-side').getBoundingClientRect().width)`);
  ok('the list starts at its default width', await sideW() === 264, await sideW());
  const edge = await page.at('.erd-side-resize');
  await page.drag(edge, { x: edge.x + 160, y: edge.y }, 8);
  ok('dragging the list’s edge widens it', Math.abs(await sideW() - 424) <= 3, await sideW());
  ok('…the canvas gives way', Math.abs(await page.eval(`Math.round(document.querySelector('.erd-main').getBoundingClientRect().left)`) - await sideW()) <= 1);
  ok('…and the width is remembered', await page.eval(`localStorage.getItem('atlas-erd-side-w')`) === String(await sideW()));
  const edge2 = await page.at('.erd-side-resize');
  await page.drag(edge2, { x: edge2.x + 900, y: edge2.y }, 8);
  ok('it stops at 640px', await sideW() === 640, await sideW());
  await page.eval(`document.querySelector('.erd-side-resize').dispatchEvent(new MouseEvent('dblclick', {bubbles:true}))`);
  ok('a double click puts it back', await sideW() === 264 && await page.eval(`localStorage.getItem('atlas-erd-side-w')`) === null, await sideW());
  await page.eval(`document.querySelector('.erd-side-resize').focus()`);
  await page.key('ArrowRight', 'ArrowRight');
  ok('→ widens it by 16px', await sideW() === 280, await sideW());
  await page.key('Home', 'Home');
  ok('Home resets it', await sideW() === 264, await sideW());

  // ---- a new diagram, from the menu ----
  await page.click(await page.at('[data-act=menu]'));
  ok('the diagram menu opens', await page.waitFor(`document.querySelector('.erd-pop[data-kind=menu]')`));
  await page.click(await page.at('[data-mi=new]'));
  ok('…asks for a name', await page.waitFor(`document.querySelector('.erd-pop[data-kind=ask] input')`));
  await page.key('Enter', 'Enter');
  ok('a new, empty diagram is active', await page.waitFor(`${T}.active().tables.length===0 && !${T}.active().origin`));
  ok('…with the hint to drag tables in', await page.eval(`!document.querySelector('.erd-empty').hidden`));

  // ---- drag a table from the list onto the canvas ----
  const item = await page.at('.erd-item[data-key="ORD_ORDER"]', 0.3, 0.5);
  const drop = await page.at('.erd-canvas', 0.3, 0.35);
  await page.drag(item, drop, 12);
  ok('a table dragged from the list lands on the canvas', await page.waitFor(`document.querySelector('.erd-card[data-key="ORD_ORDER"]')`),
    await page.eval(`${T}.active().tables.map(t=>t.key)`));
  const placed = await page.eval(`(function(){ const b=document.querySelector('.erd-card[data-key="ORD_ORDER"] .erd-box').getBoundingClientRect(); return {x:b.left, y:b.top}; })()`);
  ok('…where it was dropped', Math.abs(placed.x - (drop.x - 40)) < 60 && Math.abs(placed.y - (drop.y - 16)) < 60, { placed, drop });
  ok('the list marks it as on the canvas', await page.eval(`document.querySelector('.erd-item[data-key="ORD_ORDER"]').classList.contains('on')`));
  ok('five columns show on a folded card', await page.eval(`document.querySelectorAll('.erd-card[data-key="ORD_ORDER"] .erd-row').length`) === 5);

  // ---- the list: the data object's key to copy, and what is behind a table, before it is on the canvas ----
  ok('the list offers the data object key beside its name', await page.eval(`document.querySelector('.erd-item[data-key="CUST_CUSTOMER"] .erd-lcpy').dataset.copy`) === 'customerDO');
  await page.eval(`document.querySelector('.erd-item[data-key="CUST_CUSTOMER"] .erd-ibtn').style.opacity='1'`);
  await page.click(await page.at('.erd-item[data-key="CUST_CUSTOMER"] .erd-ibtn'));
  ok('…and its (i) says what is behind the table', await page.waitFor(`document.querySelector('.erd-pop[data-kind=info] .erd-info')`) &&
    /customerService/.test(await page.eval(`document.querySelector('.erd-pop[data-kind=info]').textContent`)));
  await page.key('Escape', 'Escape');
  ok('…and a click there adds nothing', await page.eval(`!${T}.active().tables.some(t=>t.key==='CUST_CUSTOMER')`));

  // ---- the + button, then the proposal the models make ----
  await page.eval(`document.querySelector('.erd-item[data-key="CUST_CUSTOMER"] .erd-add').style.opacity='1'`);
  await page.click(await page.at('.erd-item[data-key="CUST_CUSTOMER"] .erd-add'));
  ok('+ adds a table', await page.waitFor(`document.querySelector('.erd-card[data-key="CUST_CUSTOMER"]')`));
  // move it clear of the first card so the relation has room
  await page.drag(await page.at('.erd-card[data-key="CUST_CUSTOMER"] .erd-head', 0.35, 0.5), await page.at('.erd-canvas', 0.75, 0.3), 10);
  ok('a card can be moved', await page.eval(`${T}.active().tables.find(t=>t.key==='CUST_CUSTOMER').x`) > 0);
  ok('the models propose the order → customer relation', await page.waitFor(`document.querySelector('.erd-sug')`));
  await page.click(await page.at('.erd-sug .erd-sug-add'));
  const rel1 = await page.eval(`${T}.active().relations[0]`);
  ok('a proposal becomes a relation, with the model’s name and cardinality', rel1 && rel1.label === 'Customer' && rel1.cardinality === '1:1'
    && rel1.from === 'ORD_ORDER' && rel1.to === 'CUST_CUSTOMER', rel1);

  // ---- expand and fold ----
  await page.click(await page.at('.erd-card[data-key="ORD_ORDER"] .erd-foot'));
  ok('the footer unfolds every column', await page.eval(`document.querySelectorAll('.erd-card[data-key="ORD_ORDER"] .erd-row').length`) > 5);
  await page.click(await page.at('.erd-card[data-key="ORD_ORDER"] .erd-foot'));
  ok('…and folds them again', await page.eval(`document.querySelectorAll('.erd-card[data-key="ORD_ORDER"] .erd-row').length`) === 5);

  // ---- reorder: the third column to the top ----
  const third = await page.eval(`document.querySelectorAll('.erd-card[data-key="ORD_ORDER"] .erd-row')[2].dataset.col`);
  const grip = await page.at(`.erd-card[data-key="ORD_ORDER"] .erd-row[data-col="${third}"] .erd-grip`);
  const top = await page.at(`.erd-card[data-key="ORD_ORDER"] .erd-row`, 0.5, 0.05);
  await page.drag(grip, { x: grip.x, y: top.y - 2 }, 8);
  ok('a column dragged to the top leads the card', await page.eval(`document.querySelector('.erd-card[data-key="ORD_ORDER"] .erd-row').dataset.col`) === third,
    await page.eval(`${T}.active().tables[0].order`));

  // ---- draw a relation from the card's dot, name it, pick n:1 ----
  const dot = await page.at('.erd-card[data-key="ORD_ORDER"] .erd-link-dot');
  const target = await page.at('.erd-card[data-key="CUST_CUSTOMER"] .erd-box', 0.5, 0.7);
  await page.drag(dot, target, 12);
  ok('a relation drawn from the dot opens its panel', await page.waitFor(`document.querySelector('.erd-pop[data-kind=rel]')`));
  ok('…with the name field focused', await page.eval(`document.activeElement && document.activeElement.dataset.f==='label'`));
  await page.type('places');
  ok('a new relation starts as exactly one to zero or more', (await page.eval(`${T}.active().relations[1]`)).cardinality === '1:0..n');
  await page.click(await page.at('.erd-pop [data-end="0"][data-v="0..n"]'));
  await page.click(await page.at('.erd-pop [data-end="1"][data-v="0..1"]'));
  const rel2 = await page.eval(`${T}.active().relations[1]`);
  ok('each end takes its own count', rel2 && rel2.label === 'places' && rel2.cardinality === '0..n:0..1', rel2);
  ok('…and says what it means', /^Many to one: each Order has zero or one Customer, each Customer zero or more Order\.$/.test(
    await page.eval(`document.querySelector('.erd-say').textContent`)), await page.eval(`document.querySelector('.erd-say').textContent`));
  const marks = await page.eval(`(function(){ const g=document.querySelector('.erd-rel[data-rel="${rel2.id}"]');
    return {rings:g.querySelectorAll('circle').length, counts:[...g.querySelectorAll('text')].map(t=>t.textContent)}; })()`);
  ok('…the line ends in a ring for “none” at both ends, the counts beside them', marks.rings === 2 &&
    ['0..n', '0..1'].every(c => marks.counts.includes(c)), marks);
  await page.click(await page.at('.erd-pop [data-pa=swap]'));
  const turned = await page.eval(`${T}.active().relations[1]`);
  ok('⇄ turns the relation round, each table keeping its own end', turned.from === 'CUST_CUSTOMER' && turned.cardinality === '0..1:0..n', turned);
  await page.click(await page.at('.erd-pop [data-pa=swap]'));
  await page.key('Escape', 'Escape');
  ok('Escape closes the panel', await page.eval(`document.querySelector('.erd-pop').hidden`));
  ok('two relations between one pair get their own lanes', await page.eval(`document.querySelectorAll('.erd-rel').length`) === 2);

  // ---- colour, then undo it ----
  await page.eval(`document.querySelector('.erd-card[data-key="ORD_ORDER"] .erd-tools').style.opacity='1'`);
  await page.click(await page.at('.erd-card[data-key="ORD_ORDER"] .erd-tools'));
  ok('⋯ opens the table’s panel', await page.waitFor(`document.querySelector('.erd-pop[data-kind=table]')`));
  ok('what is behind the table waits behind its (i)', await page.eval(`document.querySelector('.erd-pop .erd-infowrap').hidden`));
  await page.click(await page.at('.erd-pop .erd-ibtn'));
  ok('(i) shows its changelog, service and data object, each key to copy', await page.eval(`!document.querySelector('.erd-pop .erd-infowrap').hidden`) &&
    await page.eval(`[...document.querySelectorAll('.erd-pop .erd-info dt')].map(d=>d.textContent).join(',')`) === 'Changelog,Service,Data object' &&
    await page.eval(`[...document.querySelectorAll('.erd-pop .erd-info .erd-cpy')].map(b=>b.dataset.copy).join(',')`) === 'orderService,orderDO',
    await page.eval(`[...document.querySelectorAll('.erd-pop .erd-info .erd-cpy')].map(b=>b.dataset.copy)`));
  ok('…no name in it is cut off', await page.eval(`[...document.querySelectorAll('.erd-pop .erd-info .erd-in, .erd-pop .erd-info .erd-key')].every(e=>e.scrollWidth<=e.clientWidth+1)`));
  ok('the table’s own name is copied from the panel’s title, not repeated under it', await page.eval(`document.querySelector('.erd-pop .erd-pop-h .erd-cpy').dataset.copy`) === 'ord_order' &&
    !/ord_order/.test(await page.eval(`document.querySelector('.erd-pop .erd-info').textContent`)));
  await page.click(await page.at('.erd-pop .erd-swatch[data-color="#0e9f6e"]'));
  ok('a swatch colours the table', await page.eval(`${T}.active().tables[0].color`) === '#0e9f6e');
  ok('…and its header', /#0e9f6e/i.test(await page.eval(`document.querySelector('.erd-card[data-key="ORD_ORDER"] .erd-head').getAttribute('style')`)));
  await page.eval(`document.querySelector('.erd-pop [data-f=alias]').focus()`);
  await page.eval(`(function(){ const i=document.querySelector('.erd-pop [data-f=alias]'); i.select(); })()`);
  await page.type('Bestellung');
  ok('the business name is the card’s title', await page.eval(`${T}.active().tables[0].alias`) === 'Bestellung');
  await page.key('Escape', 'Escape');
  await page.click(await page.at('.erd-canvas', 0.95, 0.95));
  await page.key('z', 'KeyZ', 2);   // Ctrl+Z: the handler answers ⌘ and Ctrl alike
  ok('undo takes back the name, typed as one step', await page.eval(`${T}.active().tables[0].alias`) === 'Order', await page.eval(`${T}.active().tables[0].alias`));
  await page.key('z', 'KeyZ', 2);
  ok('…then the colour', await page.eval(`${T}.active().tables[0].color`) === '');
  await page.key('z', 'KeyZ', 2 | 8);   // Ctrl+Shift+Z
  ok('redo puts the colour back', await page.eval(`${T}.active().tables[0].color`) === '#0e9f6e');

  // ---- a frame over both tables: named, moved with them, resized ----
  const cardBox = k => page.eval(`(function(){ const r=document.querySelector('.erd-card[data-key="${k}"] .erd-box').getBoundingClientRect(); return {x:r.left, y:r.top, w:r.width, h:r.height}; })()`);
  const bo = await cardBox('ORD_ORDER'), bc = await cardBox('CUST_CUSTOMER');
  await page.key('f', 'KeyF');
  ok('F takes the frame tool', await page.eval(`${T}.state().tool==='frame' && document.querySelector('[data-act=frame]').getAttribute('aria-pressed')==='true'`));
  await page.drag({ x: Math.min(bo.x, bc.x) - 30, y: Math.min(bo.y, bc.y) - 70 }, { x: Math.max(bo.x + bo.w, bc.x + bc.w) + 30, y: Math.max(bo.y + bo.h, bc.y + bc.h) + 30 }, 12);
  ok('a frame is drawn where the drag went', await page.waitFor(`${T}.active().frames.length===1`), await page.eval(`${T}.active().frames`));
  ok('…and the tool is put away', await page.eval(`${T}.state().tool===null`));
  ok('…its panel opens with the name focused', await page.eval(`!!document.querySelector('.erd-pop[data-kind=frame]') && document.activeElement.dataset.f==='name'`));
  ok('…and says it holds both tables', /Holds 2 tables/.test(await page.eval(`document.querySelector('.erd-pop[data-kind=frame]').textContent`)));
  await page.type('Sales');
  ok('the name is on the frame’s tab', await page.eval(`document.querySelector('.erd-frame .erd-ftab').textContent`) === 'Sales');
  await page.key('Escape', 'Escape');
  const places = () => page.eval(`(function(){ const d=${T}.active(), f=d.frames[0]; return {f:[f.x,f.y], o:[d.tables[0].x,d.tables[0].y], c:[d.tables[1].x,d.tables[1].y]}; })()`);
  const p0 = await places();
  const tab = await page.at('.erd-frame .erd-ftab rect');
  await page.drag(tab, { x: tab.x + 80, y: tab.y + 40 }, 8);
  const p1 = await places(), dx = k => [p1[k][0] - p0[k][0], p1[k][1] - p0[k][1]];
  ok('dragging its tab moves the frame and the tables it holds, together', dx('f')[0] > 0 && dx('f')[1] > 0 &&
    JSON.stringify(dx('o')) === JSON.stringify(dx('f')) && JSON.stringify(dx('c')) === JSON.stringify(dx('f')), { p0, p1 });
  await page.key('z', 'KeyZ', 2);
  ok('…one undo puts all three back', JSON.stringify(await places()) === JSON.stringify(p0), await places());
  await page.click(await page.at('.erd-frame .erd-ftab rect'));
  ok('a click on the tab opens the frame’s panel', await page.waitFor(`document.querySelector('.erd-pop[data-kind=frame]')`));
  await page.key('Escape', 'Escape');
  ok('…and the selected frame shows its four corners', await page.eval(`document.querySelectorAll('.erd-frame.sel .erd-fh').length`) === 4);
  const w0 = await page.eval(`${T}.active().frames[0].w`), se = await page.at('.erd-fh[data-h=se]');
  await page.drag(se, { x: se.x + 60, y: se.y + 40 }, 6);
  ok('a corner resizes it', await page.eval(`${T}.active().frames[0].w`) > w0 && JSON.stringify((await places()).o) === JSON.stringify(p0.o));

  // ---- it survives a reload ----
  const before = await page.eval(`JSON.stringify(${T}.docOf().tables.map(t=>[t.table,t.x,t.y,t.color]))`);
  await sleep(400);                    // the save is debounced
  await page.open(erdUrl);
  ok('after a reload the same diagram is active', await page.eval(`${T}.active().tables.length`) === 2
    && await page.eval(`JSON.stringify(${T}.docOf().tables.map(t=>[t.table,t.x,t.y,t.color]))`) === before);
  ok('…with both relations', await page.eval(`${T}.active().relations.length`) === 2);
  ok('…and its frame, named', await page.eval(`${T}.active().frames.length===1 && ${T}.active().frames[0].name==='Sales'`));
  ok('…and the project’s diagram is still there', await page.eval(`${T}.state().diagrams.some(d=>d.origin && d.origin.file==='docs/orders.atlas-erd.json')`));

  // ---- the file format round trip, and the picture ----
  const doc = await page.eval(`${T}.docOf()`);
  ok('the file names its format and version', doc.format === 'atlas-erd' && doc.version === 2 && doc.project === 'flowable-demo');
  ok('…carries the frame and the counts at each end', doc.frames.length === 1 && doc.frames[0].name === 'Sales' && doc.relations[1].cardinality === '0..n:0..1', doc.frames);
  ok('…carries the live columns', doc.tables.find(t => t.table.toLowerCase() === 'ord_order').columns.length >= 8);
  await page.eval(`${T}.importText(JSON.stringify(${T}.docOf()))`);
  ok('an exported file imports as a new diagram', await page.eval(`${T}.active().name`) !== doc.name
    && await page.eval(`${T}.active().tables.length`) === 2 && await page.eval(`${T}.active().relations.length`) === 2
    && await page.eval(`${T}.active().frames.length`) === 1);
  const pic = await page.eval(`${T}.pictureSvg()`);
  ok('the picture is a standalone SVG', pic.svg.startsWith('<svg xmlns="http://www.w3.org/2000/svg"') && pic.w > 200 && pic.h > 100);
  ok('…without the canvas handles', !/erd-link|erd-grip|erd-tools|erd-relhit|erd-fedge|erd-fh/.test(pic.svg));
  ok('…with the frame and its name', /class="erd-frame"/.test(pic.svg) && />Sales</.test(pic.svg));
  ok('…in paper colours, not page references', !/var\(--/.test(pic.svg));
  ok('…with the letters it was drawn in', /@font-face/.test(pic.svg));

  // ---- arrange: three tables dropped on top of each other, laid out by their relations ----
  await page.eval(`${T}.importText(JSON.stringify({format:'atlas-erd', version:1, name:'A heap', tables:[
    {table:'ord_order', x:100, y:100}, {table:'cust_customer', x:120, y:110}, {table:'legacy_audit', x:140, y:120}],
    relations:[{id:'r1', from:'ord_order', to:'cust_customer', cardinality:'n:1', label:'placed by'}]}))`);
  const boxes = `(function(){ return [...document.querySelectorAll('.erd-card')].map(c=>{ const r=c.querySelector('.erd-box').getBoundingClientRect();
    return {k:c.dataset.key, x:r.left, y:r.top, w:r.width, h:r.height}; }); })()`;
  const overlap = bs => bs.some((a, i) => bs.slice(i + 1).some(b => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h));
  ok('the heap overlaps before arranging', overlap(await page.eval(boxes)));
  const heap = await page.eval(`JSON.stringify(${T}.active().tables.map(t=>[t.key,t.x,t.y]))`);
  await page.click(await page.at('[data-act=arrange]'));
  ok('Arrange lays the tables out', await page.waitFor(`/Arranged/.test(document.getElementById('toast').textContent)`, 3000));
  const laid = await page.eval(boxes);
  ok('…with no table on another', !overlap(laid), laid);
  const at = k => laid.find(b => b.k === k);
  ok('…the one side of the relation left of its many side', at('CUST_CUSTOMER').x + at('CUST_CUSTOMER').w <= at('ORD_ORDER').x, laid);
  await page.key('z', 'KeyZ', 2);
  ok('one undo puts the heap back', await page.eval(`JSON.stringify(${T}.active().tables.map(t=>[t.key,t.x,t.y]))`) === heap);
  await page.key('z', 'KeyZ', 2 | 8);
  ok('…and redo arranges it again', !overlap(await page.eval(boxes)));

  // ---- arrange with a frame: its tables stay together in it, the other table out of it ----
  await page.eval(`${T}.importText(JSON.stringify({format:'atlas-erd', version:2, name:'A framed heap', tables:[
    {table:'ord_order', x:100, y:100}, {table:'cust_customer', x:120, y:110}, {table:'legacy_audit', x:900, y:600}],
    relations:[{id:'r1', from:'ord_order', to:'cust_customer', cardinality:'0..n:1', label:'placed by'}],
    frames:[{id:'f1', name:'Sales', x:40, y:40, w:500, h:400}]}))`);
  await page.eval(`document.getElementById('toast').textContent=''`);
  await page.click(await page.at('[data-act=arrange]'));
  ok('Arrange lays out a diagram with a frame', await page.waitFor(`/Arranged/.test(document.getElementById('toast').textContent)`, 3000));
  const fbox = await page.eval(`(function(){ const r=document.querySelector('.erd-frame .erd-fbox').getBoundingClientRect(); return {x:r.left, y:r.top, w:r.width, h:r.height}; })()`);
  const inF = b => b.x >= fbox.x && b.y >= fbox.y && b.x + b.w <= fbox.x + fbox.w && b.y + b.h <= fbox.y + fbox.h;
  const framed = await page.eval(boxes), fb = k => framed.find(b => b.k === k);
  ok('…keeping the frame’s tables in it', inF(fb('ORD_ORDER')) && inF(fb('CUST_CUSTOMER')) && !overlap([fb('ORD_ORDER'), fb('CUST_CUSTOMER')]), { fbox, framed });
  ok('…and the other table out of it', !overlap([fb('LEGACY_AUDIT'), fbox]), { fbox, framed });
  await page.click(await page.at('.erd-frame .erd-ftab rect'));
  await page.key('Escape', 'Escape');
  await page.key('Delete', 'Delete');
  ok('Delete removes a selected frame, and nothing it held', await page.eval(`${T}.active().frames.length===0 && ${T}.active().tables.length===3`));

  // ---- present, and back ----
  await page.click(await page.at('[data-act=present]'));
  ok('Present hides everything but the diagram', await page.eval(`document.documentElement.classList.contains('erd-presenting') && !document.querySelector('.erd-top').offsetParent && !document.querySelector('.erd-side').offsetParent`));
  await page.key('Escape', 'Escape');
  ok('Escape leaves the presentation', await page.eval(`!document.documentElement.classList.contains('erd-presenting') && !!document.querySelector('.erd-side').offsetParent`));

  // ---- delete a relation with the key ----
  const n = await page.eval(`${T}.active().relations.length`);
  await page.click(await page.at('.erd-rel .erd-pill'));
  ok('clicking a relation selects it', await page.waitFor(`document.querySelector('.erd-rel.sel')`));
  await page.key('Escape', 'Escape');        // closes the panel the click opened; the relation stays selected
  await page.key('Delete', 'Delete');
  ok('Delete removes the selected relation', await page.eval(`${T}.active().relations.length`) === n - 1);

  // ---- every card at once ----
  await page.click(await page.at('[data-act=expand-all]'));
  ok('Expand all unfolds every card', await page.eval(`${T}.active().tables.every(t=>t.expanded)`) &&
    await page.eval(`document.querySelectorAll('.erd-card[data-key="ORD_ORDER"] .erd-row').length`) > 5);
  const rects = `(function(){ return [...document.querySelectorAll('.erd-card .erd-box')].map(b=>{ const r=b.getBoundingClientRect(); return {x:r.left,y:r.top,w:r.width,h:r.height}; }); })()`;
  ok('…and moves cards down rather than cover them', !overlap(await page.eval(rects)));
  await page.click(await page.at('[data-act=collapse-all]'));
  ok('Collapse all folds them back to five', await page.eval(`${T}.active().tables.every(t=>!t.expanded)`) &&
    await page.eval(`document.querySelectorAll('.erd-card[data-key="ORD_ORDER"] .erd-row').length`) === 5);

  // ---- search: across every table, on the diagram or not ----
  await page.eval(`${T}.importText(JSON.stringify({format:'atlas-erd', version:1, name:'Only customers', tables:[{table:'cust_customer', x:0, y:0}]}))`);
  await page.key('f', 'KeyF', 2);                     // Ctrl+F: the page's search, not the browser's
  ok('Ctrl+F goes to the search', await page.eval(`document.activeElement===document.querySelector('.erd-q')`));
  await page.type('order_no');
  ok('the results list what matches', await page.waitFor(`!document.querySelector('.erd-results').hidden && document.querySelectorAll('.erd-res').length===1`));
  ok('…a column of a table not on the diagram, said so', await page.eval(`/ord_order\\.order_no_/.test(document.querySelector('.erd-res').textContent) && !!document.querySelector('.erd-res .erd-roff')`));
  ok('the diagram steps back from what does not match', await page.eval(`document.querySelector('.erd-card[data-key="CUST_CUSTOMER"]').classList.contains('dim')`));
  await page.key('Enter', 'Enter');
  ok('choosing it adds the table and goes to it', await page.waitFor(`${T}.active().tables.some(t=>t.key==='ORD_ORDER') && ${T}.state().sel && ${T}.state().sel.key==='ORD_ORDER'`));
  ok('…with the column found marked', await page.eval(`!!document.querySelector('.erd-card[data-key="ORD_ORDER"] .erd-row[data-col="order_no_"] .erd-rowmark')`));
  await page.click(await page.at('.erd-q'));
  await page.key('Escape', 'Escape'); await page.key('Escape', 'Escape');
  ok('Escape closes the list, then clears the search', await page.eval(`${T}.state().q==='' && !document.querySelector('.erd-card.dim')`));
  await page.eval(`${T}.search('delivery')`);
  ok('a folded card shows the columns a search finds beyond its first five',
    await page.eval(`!!document.querySelector('.erd-card[data-key="ORD_ORDER"] .erd-row[data-col="delivery_zip_"] .erd-rowmark')`));
  await page.eval(`${T}.search('')`);

  // ---- narrow ----
  await page.viewport(700, 800);
  ok('no sideways scroll at 700px', await page.eval(`document.documentElement.scrollWidth<=document.documentElement.clientWidth+1`));
  ok('…the list stacks above the canvas', await page.eval(`(function(){ const a=document.querySelector('.erd-side').getBoundingClientRect(), b=document.querySelector('.erd-canvas').getBoundingClientRect(); return a.bottom<=b.top+1 && b.height>150; })()`));
  ok('no script errors on the designer page', page.errors.length === 0, page.errors);
});

await withChrome(async page => {
  await page.open('file://' + path.resolve(plainPath) + '#/erd');
  ok('the explorer has no designer entry', await page.eval(`!document.querySelector('#nav [data-route="/erd"]')`));
  ok('…no designer code', await page.eval(`typeof window.ATLAS_ERD==='undefined' && !document.querySelector('.erd')`));
  ok('…and #/erd falls back to the overview', await page.eval(`!document.getElementById('view-overview').hidden && location.hash==='#/overview'`));
  ok('no script errors on the plain page', page.errors.length === 0, page.errors);
});

if (failed) { console.error(`erd-uitest: ${failed} failed, ${passed} passed`); process.exit(1); }
console.log(`erd-uitest: all ${passed} checks passed`);
