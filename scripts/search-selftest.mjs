#!/usr/bin/env node
/**
 * Behaviour test for the Atlas explorer search.
 *
 * The search lives in a browser asset (core/src/main/resources/frontend/explorer.js), so no Gradle test
 * can reach it: the goldens are Markdown/JSON reports and RenderersSmokeTest only checks that certain
 * strings are present. This script closes that gap. It extracts the block between the
 * __SEARCH_CORE_START__ / __SEARCH_CORE_END__ sentinels — which is written to be free of DOM and module
 * globals for exactly this reason — evaluates it, and runs a table of queries against a real generated
 * report.
 *
 * Usage:  node scripts/search-selftest.mjs <path-to-*.explorer.html>
 * Exits non-zero with a diff on the first failing case. Wired up as `./gradlew :cli:searchSelfTest`.
 */
import fs from 'fs';
import path from 'path';
import url from 'url';

const HERE = path.dirname(url.fileURLToPath(import.meta.url));
const JS_PATH = path.join(HERE, '..', 'core', 'src', 'main', 'resources', 'frontend', 'explorer.js');

// ---------- load the engine ----------
const source = fs.readFileSync(JS_PATH, 'utf8');
const START = '/*__SEARCH_CORE_START__*/', END = '/*__SEARCH_CORE_END__*/';
const a = source.indexOf(START), b = source.indexOf(END);
if (a < 0 || b < 0 || b < a) {
  console.error(`search-selftest: sentinels not found in ${JS_PATH}.\n` +
    'The search engine must stay wrapped in /*__SEARCH_CORE_START__*/ … /*__SEARCH_CORE_END__*/ ' +
    'so it can be tested outside the browser.');
  process.exit(2);
}
const block = source.slice(a + START.length, b);

// The engine's only outside dependencies are injected through SX_ENV, so a stub is enough here: the
// element-name walk only adds process/case element ids, which none of the cases below rely on.
const engine = new Function(`"use strict";${block};return {
  qParse, hayTokens, scoreIndex, searchIndex, hlite, fuzzyScore, SX_ENV
};`)();

// ---------- load the report ----------
const reportPath = process.argv[2];
if (!reportPath) { console.error('usage: node scripts/search-selftest.mjs <report.explorer.html>'); process.exit(2); }
const html = fs.readFileSync(reportPath, 'utf8');
const island = html.match(/<script type="application\/json" id="atlas-data">([\s\S]*?)<\/script>/);
if (!island) { console.error(`search-selftest: no #atlas-data island in ${reportPath}`); process.exit(2); }
const DATA = JSON.parse(island[1]);
const nodes = DATA.nodes || [];
if (!nodes.length) { console.error('search-selftest: report has no nodes'); process.exit(2); }

// Mirror the type→(label, section) table the app injects. Only the sections used below matter; anything
// missing falls back to 'Other', exactly as in the browser.
engine.SX_ENV.TM = { dataObject: ['Data objects', 'Models'], process: ['Processes', 'Models'],
  form: ['Forms', 'Models'], service: ['Services', 'Integration'], variable: ['Variables', 'Variables'] };

/** Rank every node for `q`, best first — the same pipeline palRender() runs. */
function search(q) {
  const parsed = engine.qParse(q);
  const hits = [];
  for (const n of nodes) {
    const r = engine.scoreIndex(engine.searchIndex(n), parsed, 0);
    if (r) hits.push({ n, score: r.score });
  }
  hits.sort((x, y) => y.score - x.score || (x.n.label < y.n.label ? -1 : x.n.label > y.n.label ? 1 : 0));
  return hits;
}
const rankOf = (hits, id) => hits.findIndex(h => h.n.id === id);

// ---------- cases ----------
// Everything here is content of the checked-in miniproject fixture (data objects "Customer"/customerDO
// with a customerName column, and "Priority"/priorityMD). The fixture feeds the three goldens, so it
// must not be edited to make a case pass — pick a different case instead.
const DO_CUSTOMER = 'dataObject:customerDO';
const DO_PRIORITY = 'dataObject:priorityMD';

const cases = [
  // --- the regression the whole change is about: words are independent and order-free ---
  { q: 'customer',          top: DO_CUSTOMER, why: 'plain name match ranks first' },
  { q: 'customer data',     has: DO_CUSTOMER, why: 'extra word must not throw the hit away' },
  { q: 'cust',              has: DO_CUSTOMER, why: 'prefix of a name token' },
  { q: 'customerDO',        top: DO_CUSTOMER, why: 'the key itself' },
  { q: 'customer do',       has: DO_CUSTOMER, why: 'camelCase key split into two words' },
  { q: 'do customer',       has: DO_CUSTOMER, why: 'reversed word order finds the same node' },
  { q: 'priority',          top: DO_PRIORITY, why: 'plain name match ranks first' },
  { q: 'priority md',       has: DO_PRIORITY, why: 'space where the key has none (priorityMD)' },
  { q: 'md priority',       has: DO_PRIORITY, why: 'and in the other order' },
  { q: 'priorityMD',        top: DO_PRIORITY, why: 'exact key' },

  // --- members: a column name/label must surface the data object that declares it ---
  { q: 'customerName',      has: DO_CUSTOMER, why: 'column name is indexed' },
  { q: 'customer name',     has: DO_CUSTOMER, why: 'column name reachable as two words' },

  // --- separators are interchangeable in the query ---
  { q: 'customer-do',       has: DO_CUSTOMER, why: 'hyphen behaves like a space' },
  { q: 'customer_do',       has: DO_CUSTOMER, why: 'underscore behaves like a space' },
  { q: 'customer   do',     has: DO_CUSTOMER, why: 'repeated whitespace collapses' },

  // --- quoted phrases stay contiguous ---
  { q: '"customer"',        has: DO_CUSTOMER, why: 'quoted single word still matches' },
  { q: '"do customer"',     none: true,       why: 'a quoted phrase must NOT match out of order' },

  // --- inline facets are hard filters (the typed equivalent of the palette's facet chips) ---
  { q: 'customer t:dataObject', has: DO_CUSTOMER, why: 'type facet keeps the data object' },
  { q: 'customer t:process',    missing: DO_CUSTOMER, why: 'type facet excludes it' },
  { q: 'customer t:data',       has: DO_CUSTOMER, why: 'type facet also matches Design\'s wording' },
  { q: 'customer in:Models',    has: DO_CUSTOMER, why: 'section facet keeps the data object' },
  { q: 'customer in:Code',      missing: DO_CUSTOMER, why: 'section facet excludes it' },
  { q: 'customer key:customerDO', has: DO_CUSTOMER, why: 'key facet' },
  { q: 'customer key:nope',     none: true,        why: 'key facet that matches nothing' },

  // --- AND semantics: a word that matches nothing drops the node ---
  { q: 'customer zzzznope',  none: true, why: 'every word has to match' },
];

let failed = 0, ran = 0;
for (const c of cases) {
  ran++;
  const hits = search(c.q);
  const ids = hits.map(h => h.n.id);
  const show = hits.slice(0, 4).map((h, i) => `${i}:${h.n.label}`).join(' | ') || '(none)';
  const fail = m => { failed++; console.error(`FAIL  ${JSON.stringify(c.q).padEnd(30)} ${m}\n      ${c.why}\n      top: ${show}`); };

  if (c.none && hits.length)                     fail(`expected no hits, got ${hits.length}`);
  else if (c.top && ids[0] !== c.top)            fail(`expected ${c.top} first, rank=${rankOf(hits, c.top)}`);
  else if (c.has && !ids.includes(c.has))        fail(`expected ${c.has} among ${hits.length} hits`);
  else if (c.missing && ids.includes(c.missing)) fail(`expected ${c.missing} to be excluded`);
  else console.log(`ok    ${JSON.stringify(c.q).padEnd(30)} ${c.why}`);
}

// ---------- unit-level checks on the pieces ----------
function unit(name, got, want) {
  ran++;
  const g = JSON.stringify(got), w = JSON.stringify(want);
  if (g !== w) { failed++; console.error(`FAIL  ${name}\n      got  ${g}\n      want ${w}`); }
  else console.log(`ok    ${name}`);
}
unit('hayTokens splits camelCase + digits',
  engine.hayTokens('DEMO-D05 outreachTemplateKey').filter(t => ['demo','d05','d','05','outreach','template','key'].includes(t)).length >= 7,
  true);
unit('qParse separates facets from terms',
  (p => [p.terms, p.facets])(engine.qParse('shopping template t:dataObject')),
  [['shopping','template'], { type: 'dataobject' }]);
unit('qParse keeps a quoted phrase whole',
  engine.qParse('"get outreach" key').phrases, ['get outreach']);
unit('hlite marks every occurrence, merged and escaped-safe',
  engine.hlite('Template of a template', engine.qParse('template')).map(s => (s.hit ? '[' : '') + s.t + (s.hit ? ']' : '')).join(''),
  '[Template] of a [template]');
unit('hlite leaves a non-matching string in one piece',
  engine.hlite('nothing here', engine.qParse('zzz')), [{ t: 'nothing here', hit: false }]);
unit('fuzzyScore finds a typo as a subsequence', engine.fuzzyScore('customer', 'custmer') > 0, true);
unit('fuzzyScore rejects a non-subsequence', engine.fuzzyScore('customer', 'zzz'), 0);

// A zero-result query must still be able to suggest something (the palette's "did you mean").
ran++;
const sug = nodes.map(n => engine.fuzzyScore(engine.searchIndex(n).name, 'custmer')).filter(s => s > 0);
if (!sug.length) { failed++; console.error('FAIL  typo query yields no suggestion candidates'); }
else console.log('ok    typo query yields suggestion candidates');

console.log(`\n${ran - failed}/${ran} passed  (report: ${path.basename(reportPath)}, ${nodes.length} nodes)`);
if (failed) { console.error(`${failed} search self-test case(s) failed`); process.exit(1); }
