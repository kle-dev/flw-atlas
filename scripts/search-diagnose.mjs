#!/usr/bin/env node
/**
 * Answers "why does ⌘K not find this?" against a REPORT THAT ALREADY EXISTS, using that report's own
 * embedded search engine and its own data — not this checkout's.
 *
 * A search failure has three possible causes and they need different fixes, so the first job is to tell
 * them apart:
 *   1. the string never reached the report   → an extraction problem (parser / project layout)
 *   2. it is in the report but scores nothing → a matching problem (tokens, facets)
 *   3. it matches but the page does not draw it → a presentation problem (paging, sections)
 * Guessing which one it is from a symptom is how four releases got spent on the wrong two.
 *
 * Usage:  node scripts/search-diagnose.mjs <report.explorer.html> "<query>"
 */
import fs from 'fs';

const [file, query] = process.argv.slice(2);
if (!file || !query) {
  console.error('usage: node scripts/search-diagnose.mjs <report.explorer.html> "<query>"');
  process.exit(2);
}
const html = fs.readFileSync(file, 'utf8');

// ---------- what generated this report ----------
const ver = html.match(/id="atlasver"[^>]*>([^<]*)</);
console.log(`report:    ${file}`);
console.log(`generator: ${ver ? ver[1].trim() : '(no version element — a report older than 0.15)'}`);

// ---------- the report's OWN engine, so this diagnoses the version in the file ----------
const S = '/*__SEARCH_CORE_START__*/', E = '/*__SEARCH_CORE_END__*/';
const a = html.indexOf(S), b = html.indexOf(E);
if (a < 0 || b < 0) {
  console.error('\nThis report has no search-core block: it predates the searchable label/description ' +
    'work entirely. Regenerate it with a current Atlas.');
  process.exit(1);
}
const eng = new Function(`"use strict";${html.slice(a + S.length, b)};return {
  qParse, scoreIndex, searchIndex, matchWhere, hayTokens, SX_ENV };`)();
const lift = name => {
  const at = html.indexOf(`\nfunction ${name}(`);
  return at < 0 ? '' : html.slice(at, html.indexOf('\n}\n', at) + 3);
};
eng.SX_ENV.elementNames = new Function(
  `${lift('caseCriteria')}${lift('elementNames')};return typeof elementNames==='function'?elementNames:()=>new Map();`)();
const tmSrc = html.match(/const TM\s*=\s*\{[\s\S]*?\n\};/);
if (tmSrc) eng.SX_ENV.TM = new Function(`${tmSrc[0]}return TM;`)();

const DATA = JSON.parse(html.match(/<script type="application\/json" id="atlas-data">([\s\S]*?)<\/script>/)[1]);
const nodes = DATA.nodes || [];
const indeg = new Map();
for (const e of DATA.edges || []) indeg.set(e.to, (indeg.get(e.to) || 0) + 1);
console.log(`nodes:     ${nodes.length}`);

// ---------- 0. is every rendered caption actually indexed? ----------
// The invariant behind "the Fields section shows this label and the search does not find it".
{
  const missing = [];
  for (const n of nodes) {
    if (n.type !== 'form' && n.type !== 'page') continue;
    const lab = eng.searchIndex(n).lab || '';
    for (const f of (n.data || {}).fields || [])
      if (f.label && !lab.includes(String(f.label).toLowerCase()))
        missing.push(`${n.label} › ${f.id} = ${JSON.stringify(f.label)}`);
  }
  if (missing.length) {
    console.log(`\n0. *** ${missing.length} caption(s) the Fields section renders are NOT in the index:`);
    for (const m of missing.slice(0, 15)) console.log(`      ${m}`);
    console.log('   That is an INDEXING bug. Send this list.');
  } else {
    console.log('\n0. Every field caption the Fields section renders is in the index. OK.');
  }
}

// ---------- 1. is the string in the report at all? ----------
const needle = query.replace(/^\w+:\s*/, '').toLowerCase();
console.log(`\n1. Is "${needle}" anywhere in the payload?`);
const carriers = [];
for (const n of nodes) {
  const ix = eng.searchIndex(n);
  const where = ['lab', 'desc', 'name', 'key', 'mem', 'text', 'file']
    .filter(f => (ix[f] || '').includes(needle));
  if (where.length) carriers.push({ n, where });
}
if (!carriers.length) {
  console.log(`   NO. Not in any node's data — this is an EXTRACTION problem, not a search problem.`);
  console.log(`   Nothing ⌘K can do. Send the model file itself (the file on disk, not its editorJson).`);
  process.exit(0);
}
console.log(`   YES, on ${carriers.length} node(s):`);
for (const c of carriers.slice(0, 8)) console.log(`     ${c.n.type.padEnd(14)} ${c.n.label}   [in ${c.where.join(', ')}]`);
if (carriers.length > 8) console.log(`     … ${carriers.length - 8} more`);

// ---------- 2. does the query match them? ----------
const p = eng.qParse(query);
console.log(`\n2. Parsed query: terms=${JSON.stringify(p.terms)} phrases=${JSON.stringify(p.phrases)} facets=${JSON.stringify(p.facets)}`);
const hits = [];
for (const n of nodes) {
  const r = eng.scoreIndex(eng.searchIndex(n), p, indeg.get(n.id) || 0);
  if (r) hits.push({ n, score: r.score, fields: r.fields });
}
hits.sort((x, y) => y.score - x.score || (x.n.label < y.n.label ? -1 : 1));
console.log(`   ${hits.length} hit(s).`);
// A facet is a deliberate filter, so a node carrying the word in some OTHER field is correctly
// excluded and must not be reported as a defect — only a node the facet was pointed AT counts.
const facetField = { lab: 'lab', desc: 'desc', type: 'type', file: 'file', key: 'key', section: null, id: null };
const targeted = Object.keys(p.facets).map(k => facetField[k]).filter(Boolean);
const missed = carriers
  .filter(c => !hits.some(h => h.n.id === c.n.id))
  .filter(c => !targeted.length || c.where.some(f => targeted.includes(f)));
if (missed.length) {
  console.log(`   *** ${missed.length} node(s) CARRY the string in the field you asked for and still do`);
  console.log(`       NOT match — a MATCHING problem:`);
  for (const m of missed.slice(0, 5)) console.log(`       ${m.n.type} ${m.n.label} [in ${m.where.join(', ')}]`);
} else if (targeted.length) {
  console.log(`   (nodes carrying it in another field are excluded by the facet on purpose)`);
}

// ---------- 3. would the first page show them? ----------
const SECTIONS = (html.match(/const SECTIONS\s*=\s*(\[[^\]]*\])/) || [])[1];
const secOrder = SECTIONS ? JSON.parse(SECTIONS.replace(/'/g, '"')) : [];
const page = (html.includes('function palWindow(')
  ? new Function('searchIndex', 'PAL_SEC_MIN',
      `${lift('palWindow')}return palWindow;`)(eng.searchIndex, Number((html.match(/const PAL_SEC_MIN=(\d+)/) || [0, 3])[1]))
  : (list, shown) => list.slice(0, shown))(hits, Number((html.match(/PAL_PAGE=(\d+)/) || [0, 60])[1]));
const bySec = {};
for (const h of page) (bySec[eng.searchIndex(h.n).section] ??= []).push(h);
const order = secOrder.filter(s => bySec[s]).concat(Object.keys(bySec).filter(s => secOrder.indexOf(s) < 0).sort());
console.log(`\n3. The first page draws ${page.length} of ${hits.length} row(s), in this order:`);
for (const s of order) {
  console.log(`   ${s} (${bySec[s].length})`);
  for (const h of bySec[s].slice(0, 4)) {
    const w = eng.matchWhere(h.n, p, h.fields);
    console.log(`      ${String(h.score).padStart(5)}  ${h.n.type.padEnd(14)} ${h.n.label}${w && w.hint ? '   « ' + w.hint : ''}`);
  }
  if (bySec[s].length > 4) console.log(`      … ${bySec[s].length - 4} more in this group`);
}
const hidden = carriers.filter(c => hits.some(h => h.n.id === c.n.id) && !page.some(h => h.n.id === c.n.id));
if (hidden.length) {
  console.log(`\n   *** ${hidden.length} matching node(s) are NOT on the first page — a PRESENTATION problem:`);
  for (const h of hidden.slice(0, 5)) console.log(`       ${h.n.type} ${h.n.label}`);
}
