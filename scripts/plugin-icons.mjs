#!/usr/bin/env node
// Generates the IntelliJ plugin's icon set — idea-plugin/src/main/resources/icons/atlas/*.svg — from the
// explorer frontend's own icon table (TYPE_ICONS / UI_ICONS in explorer.js) and its --c-* palette
// (explorer.css), so a model type wears the same glyph and the same colour in the Project view, in a
// completion popup and on its explorer page. Before this the plugin had one hand-drawn glyph standing
// in for "the Hub", "a model", and three different gutter markers.
//
// Run it by hand after changing TYPE_ICONS or a --c-* colour, and commit the output (like
// scripts/embed-geist.mjs). core's PluginIconsSyncTest fails the build when the committed files drift
// from their sources, so forgetting to run it is a red build, not a stale icon.
//
//   node scripts/plugin-icons.mjs           write the files
//   node scripts/plugin-icons.mjs --check   exit 1 if any committed file differs from what would be written
//
// Lucide Icons — ISC License. Copyright (c) 2026 Lucide Icons and Contributors (https://lucide.dev).
// Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is
// hereby granted, provided that the above copyright notice and this permission notice appear in all
// copies. THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS
// SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR
// BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER
// RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
// TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
// (The bodies lifted from explorer.js carry the same notice there; the three below are the only ones
// this script adds — compass, shapes, route — and THIRD-PARTY-NOTICES.md counts them.)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const JS = path.join(ROOT, 'core/src/main/resources/frontend/explorer.js');
const CSS = path.join(ROOT, 'core/src/main/resources/frontend/explorer.css');
const MODEL_TYPES = path.join(ROOT, 'core/src/main/kotlin/com/flowable/atlas/model/ModelType.kt');
const OUT = path.join(ROOT, 'idea-plugin/src/main/resources/icons/atlas');
const CHECK = process.argv.includes('--check');

const read = (f) => fs.readFileSync(f, 'utf8');
const fail = (msg) => { console.error(`plugin-icons: ${msg}`); process.exit(1); };

// The IntelliJ New UI's grey icon pair — the same two values the hand-drawn hub.svg / hub_dark.svg use,
// so generated and hand-drawn chrome icons sit next to each other without a visible seam.
const CHROME = { light: '#6C707E', dark: '#CED0D6' };
// A type the palette has no colour for (palette models) borrows the "external" grey rather than
// inventing one here: the palette is the explorer's, and this script does not extend it.
const FALLBACK_COLOUR = 'external';

/* ---------------------------------------------------------------- sources */

function iconTable(js, name) {
  const m = js.match(new RegExp(`\\nconst ${name}=\\{\\n([\\s\\S]*?)\\n\\};\\n`));
  if (!m) fail(`the ${name} table is gone from explorer.js`);
  const out = {};
  for (const line of m[1].split('\n')) {
    const e = line.match(/^\s*(\w+):'(.*)',$/);
    if (e) out[e[1]] = e[2];
  }
  return out;
}
function palette(css, dark) {
  const cut = css.indexOf(':root[data-theme=dark]');
  if (cut < 0) fail('explorer.css has no :root[data-theme=dark] block');
  const half = dark ? css.slice(cut) : css.slice(0, cut);
  const out = {};
  for (const m of half.matchAll(/--c-(\w+):(#[0-9a-fA-F]{6})/g)) out[m[1]] = m[2].toLowerCase();
  return out;
}
function modelTypeIds(kt) {
  const ids = [...kt.matchAll(/^\s+[A-Z_]+\("(\w+)", "[^"]+"\)[,;]$/gm)].map((m) => m[1]);
  if (ids.length === 0) fail('no ModelType entries parsed from ModelType.kt');
  return ids;
}

const js = read(JS);
const css = read(CSS);
const TYPE_ICONS = iconTable(js, 'TYPE_ICONS');
const UI_ICONS = iconTable(js, 'UI_ICONS');
const LIGHT = palette(css, false);
const DARK = palette(css, true);
const TYPES = modelTypeIds(read(MODEL_TYPES));

// Lucide bodies the explorer has no use for. Kept here, not in explorer.js, so the frontend does not
// ship glyphs it never draws.
const EXTRA = {
  compass: '<path d="m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z"/><circle cx="12" cy="12" r="10"/>',
  shapes: '<path d="M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1Z"/><rect x="3" y="14" width="7" height="7" rx="1"/><circle cx="17.5" cy="17.5" r="3.5"/>',
  route: '<circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/>',
};

/* --------------------------------------------------------------- manifest */

// body: 'type:<key>' (TYPE_ICONS) | 'ui:<key>' (UI_ICONS) | 'extra:<key>' (EXTRA)
// colour: 'chrome' (the IDE grey pair) | 'c:<key>' (the --c-<key> pair, light and dark)
// size: the rendered pixel size — 16 everywhere, 12 in the gutter (AllIcons.Gutter.* are 12 px)
const MANIFEST = [
  { name: 'explorer', body: 'extra:compass', colour: 'chrome', size: 16 },  // the Atlas Explorer artifact / actions
  { name: 'model', body: 'extra:shapes', colour: 'chrome', size: 16 },      // "a Flowable model", type unknown
  { name: 'bot', body: 'type:bot', colour: 'chrome', size: 16 },            // a BotService class (Go to Symbol)
  { name: 'endpoint', body: 'type:endpoint', colour: 'chrome', size: 16 },  // a REST handler models call
  { name: 'archive', body: 'type:external', colour: 'c:app', size: 16 },    // a .bar deployment archive
  { name: 'gutter-reference', body: 'ui:link', colour: 'chrome', size: 12 }, // Java symbol referenced from models
  { name: 'gutter-bot', body: 'type:bot', colour: 'chrome', size: 12 },
  { name: 'gutter-endpoint', body: 'type:endpoint', colour: 'chrome', size: 12 },
  { name: 'gutter-diagram', body: 'extra:route', colour: 'chrome', size: 12 }, // a key literal with a diagram
  ...TYPES.map((id) => ({ name: `type-${id}`, body: `type:${id}`, colour: `c:${id}`, size: 16 })),
];

/* ----------------------------------------------------------------- render */

function bodyOf(spec) {
  const [kind, key] = spec.split(':');
  const table = { type: TYPE_ICONS, ui: UI_ICONS, extra: EXTRA }[kind];
  const body = table && table[key];
  if (!body) fail(`unknown icon body "${spec}"`);
  return body;
}
function colourOf(spec, dark) {
  if (spec === 'chrome') return dark ? CHROME.dark : CHROME.light;
  const key = spec.slice(2);
  const pal = dark ? DARK : LIGHT;
  const hex = pal[key] ?? pal[FALLBACK_COLOUR];
  if (!hex) fail(`no --c-${key} (nor --c-${FALLBACK_COLOUR}) in explorer.css`);
  return hex;
}
function svg({ body, colour, size }, dark) {
  const hex = colourOf(colour, dark);
  // IntelliJ's icon loader draws a static file: it has no currentColor to inherit, so the one body that
  // uses it (palette's dots) gets the concrete colour instead.
  const inner = bodyOf(body).replaceAll('fill="currentColor"', `fill="${hex}"`);
  return `<!-- Generated by scripts/plugin-icons.mjs from explorer.js / explorer.css — do not edit. ` +
    `Lucide Icons, ISC License (see THIRD-PARTY-NOTICES.md). -->\n` +
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" ` +
    `stroke="${hex}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${inner}</svg>\n`;
}

/* ------------------------------------------------------------------ write */

const files = new Map();
for (const entry of MANIFEST) {
  files.set(`${entry.name}.svg`, svg(entry, false));
  files.set(`${entry.name}_dark.svg`, svg(entry, true));
}

if (CHECK) {
  const drift = [];
  for (const [name, content] of files) {
    const f = path.join(OUT, name);
    if (!fs.existsSync(f)) drift.push(`${name} (missing)`);
    else if (read(f) !== content) drift.push(name);
  }
  if (drift.length) fail(`${drift.length} icon file(s) differ from their sources — run node scripts/plugin-icons.mjs:\n  ${drift.join('\n  ')}`);
  console.log(`plugin-icons: ${files.size} files up to date`);
} else {
  fs.mkdirSync(OUT, { recursive: true });
  for (const [name, content] of files) fs.writeFileSync(path.join(OUT, name), content);
  console.log(`plugin-icons: wrote ${files.size} files to ${path.relative(ROOT, OUT)}`);
}
