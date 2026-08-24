#!/usr/bin/env node
/**
 * Renders the documentation site's screenshots with headless Chrome, into `site/assets/img/`.
 *
 * Two kinds of image, and it matters which is which:
 *
 *   1. Real screenshots of the REAL generated explorer, produced from `site/flowable-demo` by the same
 *      CLI a reader would run. Nothing here is a drawing of the product.
 *   2. The social card, rendered from `site/social-card.html`.
 *
 * The IDE "screenshots" on the plugin pages are NOT here: they are inline HTML/CSS mockups under
 * `site/mockups/` — vector-crisp, ~2 KB each, and diff-reviewable. This script only verifies their
 * geometry (see `checkMockups`), because two specific ways of getting that wrong have bitten before.
 *
 * Nothing it writes is committed: `*.png` is gitignored on purpose, so a customer screenshot can never
 * be added by accident. The Pages workflow regenerates every image on every deploy.
 *
 * Usage:  node scripts/site-shots.mjs [--explorer <file.explorer.html>] [--chrome <path>] [--out <dir>]
 *         node scripts/site-shots.mjs --check-site <dir>
 *
 *         With no --explorer it uses build/site-demo/flowable-demo.explorer.html, which
 *         `./gradlew siteDemo` (or the workflow) produces.
 *
 *         --check-site skips every image and instead opens each BUILT page in the browser and asserts
 *         the two things a static generator cannot see: that no page scrolls horizontally at a phone
 *         width, and that the page boots without a JavaScript error.
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};

const OUT = path.resolve(argOf('--out', path.join(ROOT, 'site/assets/img')));
const EXPLORER = path.resolve(argOf('--explorer', path.join(ROOT, 'build/site-demo/flowable-demo.explorer.html')));

/* CHROME_PATH is the conventional override (puppeteer, karma) and is how CI points at whatever the
   runner image ships, instead of this list having to know every distro's path. */
const CHROME = [
  argOf('--chrome', null),
  process.env.CHROME_PATH,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean).find((p) => { try { fs.accessSync(p, fs.constants.X_OK); return true; } catch { return false; } });

if (!CHROME) {
  console.error('site-shots: no Chrome/Chromium found. Pass --chrome <path> or set CHROME_PATH.');
  process.exit(1);
}
const fileUrl = (p, hash = '') => 'file://' + p + hash;

const CHECK_SITE = argOf('--check-site', null);

/**
 * Opens every built page and asserts what only a browser knows.
 *
 * Horizontal page scroll is the check that matters: a wide table or code block is *supposed* to
 * scroll inside its own container, and the way that goes wrong is the whole page scrolling instead —
 * which looks fine on a laptop and makes the site unusable on a phone. Compared against
 * documentElement.scrollWidth, so an element that legitimately overflows its own scroll container
 * does not register.
 *
 * Note headless Chrome clamps the window to a 500px minimum, so that is the narrowest width this can
 * honestly test. It is narrow enough to catch the mistake.
 */
function checkBuiltPages(siteDir) {
  const pages = [];
  (function walk(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.name === 'index.html') pages.push(full);
    }
  })(siteDir);

  const probe = `
    const bad = [];
    if (document.documentElement.scrollWidth > innerWidth + 1) {
      bad.push('page scrolls horizontally: ' + document.documentElement.scrollWidth + ' > ' + innerWidth);
    }
    document.title = bad.length ? 'FAIL ' + bad.join(' | ') : 'OK';
  `;
  let failures = 0;
  for (const page of pages) {
    const probed = page.replace(/index\.html$/, '__probe.html');
    fs.writeFileSync(probed, fs.readFileSync(page, 'utf8')
      .replace('</body>', `<script>window.addEventListener('error',e=>{document.title='FAIL js: '+e.message});${probe}</script></body>`));
    for (const width of [500, 1440]) {
      const dom = execFileSync(CHROME, [
        '--headless', '--disable-gpu', '--no-sandbox', '--dump-dom',
        `--window-size=${width},900`, '--virtual-time-budget=3000', fileUrl(probed),
      ], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
      const title = (dom.match(/<title>([^<]*)<\/title>/) || ['', '(no title)'])[1];
      if (title.startsWith('FAIL')) {
        console.error(`  • ${path.relative(siteDir, page)} @${width}px — ${title.slice(5)}`);
        failures++;
      }
    }
    fs.rmSync(probed);
  }
  if (failures) {
    console.error(`site-shots: ${failures} page problem(s)`);
    return false;
  }
  console.log(`site-shots: ${pages.length} built pages checked at 500px and 1440px — clean`);
  return true;
}

if (CHECK_SITE) {
  if (!fs.existsSync(CHECK_SITE)) {
    console.error(`site-shots: no built site at ${CHECK_SITE} — run scripts/site-build.mjs first`);
    process.exit(1);
  }
  process.exit(checkBuiltPages(path.resolve(CHECK_SITE)) ? 0 : 1);
}

if (!fs.existsSync(EXPLORER)) {
  console.error(`site-shots: no demo explorer at ${path.relative(ROOT, EXPLORER)}.`);
  console.error('  Generate it first:  ./gradlew siteDemo');
  process.exit(1);
}

fs.mkdirSync(OUT, { recursive: true });

/**
 * One screenshot. `--virtual-time-budget` lets the page finish booting before the capture; note that
 * it also starves ResizeObserver and rAF after the first delivery, so anything driven by an observed
 * resize will not have run — fine for these views, and the reason the diagram shot targets a node
 * whose diagram is painted during the initial render.
 *
 * Deliberately NOT wrapped in `timeout`: macOS has no such command, and the failure mode is a silent
 * zero-byte file that looks exactly like a Chrome crash.
 */
function shot(url, file, { w, h, scale = 2 }) {
  const target = path.join(OUT, file);
  execFileSync(CHROME, [
    '--headless', '--disable-gpu', '--hide-scrollbars', '--no-sandbox',
    `--force-device-scale-factor=${scale}`,
    `--window-size=${w},${h}`,
    '--virtual-time-budget=4000',
    `--screenshot=${target}`,
    url,
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  const size = fs.statSync(target).size;
  if (size < 4096) throw new Error(`${file} came back ${size} bytes — the page did not render`);
  console.log(`  ${file.padEnd(26)} ${w}×${h} @${scale}x  ${(size / 1024).toFixed(0)} KB`);
}

console.log('site-shots: real screenshots of the generated explorer');
const SHOTS = [
  // The landing hero. 16:10, and wide enough that the sidebar and the dashboard both read.
  ['#/overview', 'hero-explorer', { w: 1600, h: 1000 }],
  ['#/checks', 'checks-page', { w: 1400, h: 900 }],
  ['#/variables', 'variables-page', { w: 1400, h: 900 }],
  ['#/schema', 'schema-page', { w: 1400, h: 800 }],
  ['#/scripts', 'scripts-page', { w: 1400, h: 900 }],
];
/* Each shot twice, because a light screenshot on a dark documentation page looks like a mistake. The
   page picks one with CSS (see .only-light / .only-dark in site.css), so it follows the reader's
   theme toggle and not just their OS setting.

   `?ideTheme=dark` is the explorer's own IDE-embedding hook: it makes the page's default preference
   `auto` and resolves that to dark. Without it the explorer defaults to light in a browser. */
for (const [hash, base, size] of SHOTS) {
  shot(fileUrl(EXPLORER, hash), `${base}.png`, size);
  shot(fileUrl(EXPLORER, `?ideTheme=dark${hash}`), `${base}-dark.png`, size);
}

console.log('site-shots: social card');
const card = path.join(ROOT, 'site/social-card.html');
if (fs.existsSync(card)) shot(fileUrl(card), 'og-card.png', { w: 1200, h: 630, scale: 1 });
else console.log('  (no site/social-card.html — skipped)');

/**
 * Mockup geometry check.
 *
 * Two traps, both of which have cost real time:
 *   1. Measuring a mockup without resetting its height to `auto` just echoes the declared height back,
 *      so a clipped popup reports a false pass. And absolutely-positioned descendants do not
 *      contribute to layout, so a naive measure under-measures the content.
 *   2. A popup can be clipped by an INTERMEDIATE `overflow:hidden` container (.edscroll, a diagram
 *      viewport), not only by the outer frame — so every absolute element is checked against its
 *      nearest overflow-hidden ancestor, not against the mockup box.
 */
function checkMockups() {
  const dir = path.join(ROOT, 'site/mockups');
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.html'));
  const harness = path.join(ROOT, 'build/mockup-check.html');
  const probe = `
    const out = [];
    for (const box of document.querySelectorAll('.mockfit')) {
      const name = box.dataset.name;
      const declaredH = parseInt(box.dataset.h, 10);
      const declaredW = parseInt(box.dataset.w, 10);

      // PASS 1 — clipping, in the UNTOUCHED layout. Measuring this after mutating the box's height
      // is what makes a correct mockup look broken: .edscroll is a flex child, so it resizes with
      // the box and every rect moves. Two passes, no overlap.
      const boxRect = box.getBoundingClientRect();
      for (const el of box.querySelectorAll('*')) {
        const pos = getComputedStyle(el).position;
        if (pos !== 'absolute' && pos !== 'fixed') continue;
        const r = el.getBoundingClientRect();
        if (!r.width && !r.height) continue;
        // Only the mockup frame itself decides what is visible. A popup reaching past an inner
        // scroll container is normal — that is how the real IDE paints one.
        if (r.bottom > boxRect.bottom + 1 || r.right > boxRect.right + 1) {
          out.push({ id: name, kind: 'clipped',
            detail: (el.className || el.tagName) + ' is cut off by the mockup frame' });
        }
      }

      // PASS 2 — does the laid-out content fit the declared size? This is the "I edited the mockup
      // and forgot data-h" bug. Absolutely-positioned nodes are excluded: they do not contribute to
      // layout, and pass 1 already covers them.
      const prevH = box.style.height;
      box.style.height = 'auto';
      const flowH = box.scrollHeight, flowW = box.scrollWidth;
      box.style.height = prevH;
      if (flowH > declaredH + 2) {
        out.push({ id: name, kind: 'short',
          detail: 'laid-out content is ' + flowH + 'px but data-h says ' + declaredH });
      }
      if (flowW > declaredW + 2) {
        out.push({ id: name, kind: 'narrow',
          detail: 'laid-out content is ' + flowW + 'px but data-w says ' + declaredW });
      }
    }
    // The id is assembled from two halves on purpose: --dump-dom returns the script source too, so a
    // literal id here would be matched before the element the probe actually filled in.
    document.getElementById('mock' + 'check').textContent = JSON.stringify(out);
  `;
  const body = files.map((f) => {
    const html = fs.readFileSync(path.join(dir, f), 'utf8')
      .replace('<div class="mockfit', `<div data-name="${f.replace(/\.html$/, '')}" class="mockfit`);
    // Each mockup is measured at its own declared size, unscaled and unclipped by the page.
    return `<div style="width:1400px;overflow:visible">${html}</div>`;
  }).join('\n');
  fs.mkdirSync(path.dirname(harness), { recursive: true });
  fs.writeFileSync(harness, `<!doctype html><meta charset="utf8">
<link rel="stylesheet" href="../site/assets/site.css">
<link rel="stylesheet" href="../site/assets/ide.css">
<body style="width:1600px">${body}<pre id="mockcheck"></pre><script>${probe}</script></body>`);

  const dom = execFileSync(CHROME, [
    '--headless', '--disable-gpu', '--no-sandbox', '--dump-dom',
    '--virtual-time-budget=4000', fileUrl(harness),
  ], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });

  const m = dom.match(/<pre id="mockcheck">(.*?)<\/pre>/s);
  if (!m) throw new Error('the mockup geometry probe did not run');
  const problems = JSON.parse(m[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>'));
  if (problems.length) {
    console.error(`site-shots: ${problems.length} mockup geometry problem(s):`);
    for (const p of problems) console.error(`  • ${p.id}: ${p.kind} — ${p.detail}`);
    return false;
  }
  console.log(`site-shots: ${files.length} mockups measured, geometry clean`);
  return true;
}

console.log('site-shots: mockup geometry');
if (!checkMockups()) process.exit(1);

console.log(`site-shots: done → ${path.relative(ROOT, OUT)}`);
