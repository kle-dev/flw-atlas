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

const engine = new Function(`"use strict";${block};return {
  qParse, hayTokens, scoreIndex, searchIndex, matchWhere, hlite, fuzzyScore, suggestScore, SX_ENV
};`)();

/** Lift a whole top-level function out of explorer.js by name, or say so and stop. Plain string
 *  scanning rather than a regex: the marker it looks for is `\n}` at column zero, which is exactly
 *  how every top-level function in the file ends. */
function lift(name) {
  const at = source.indexOf(`\nfunction ${name}(`);
  const end = at < 0 ? -1 : source.indexOf('\n}\n', at);
  if (at < 0 || end < 0) {
    console.error(`search-selftest: function ${name}() not found in ${JS_PATH} — was it renamed?\n` +
      'The engine needs it through SX_ENV; a stub would silently empty a haystack.');
    process.exit(2);
  }
  return source.slice(at, end + 3);
}
// elementNames() is the one thing the engine takes from the app, and it is not optional: `lab` gets
// every BPMN/CMMN element's *name* from it and `ids` every element *id*, so the empty-Map stub this
// used to pass would let the label cases pass for the wrong reason. Both functions are pure, so they
// are lifted out of the source the same way the engine block is.
engine.SX_ENV.elementNames = new Function(
  `"use strict";${lift('caseCriteria')}${lift('elementNames')};return elementNames;`)();

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
// The fixture form, whose buttons carry the ids / callees / expressions the `id:` cases look up.
const FORM_ORDER = 'form:orderForm';
// The label / description cases below read fixture content that is there for its own sake:
// order.bpmn documents its process and two of its elements, demo.app has a Description,
// order-form.form labels its fields and gives one component a description, order-decision.dmn
// annotates a rule, customer.data labels its columns and case.policy its permissions.
const APP_DEMO = 'app:demoApp';
const PROC_ORDER = 'process:orderProcess';
const DEC_ORDER = 'decision:orderDecision';
const POLICY_ORDER = 'securityPolicy:orderPolicy';

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

  // --- `id:` looks a model element up by its identifier, and only by that ---
  { q: 'id:notifyButton',   top: FORM_ORDER, why: 'a form button id finds the form that declares it' },
  { q: 'id:orderTotal',     has: FORM_ORDER, why: 'an expression button id, whose caption says nothing like it' },
  { q: 'id:callSub',        has: 'process:orderProcess', why: 'a BPMN element id finds its process' },
  { q: 'id:notify',         has: FORM_ORDER, why: 'a fragment of an id is enough' },
  { q: 'id:Recalculate',    none: true,      why: 'a caption is not an id — that is the point of the facet' },
  { q: 'id:zzzznope',       none: true,      why: 'an id that exists nowhere' },
  { q: 'zzzznope id:notifyButton', none: true, why: 'the facet narrows, but the terms still have to match' },
  { q: 'customer id:notifyButton', has: FORM_ORDER, why: 'and a term that does match keeps the node' },

  // --- what a button invokes, and the expression it evaluates, are searchable ---
  { q: 'notifyCustomerAction', has: FORM_ORDER, why: 'the action a button triggers surfaces the form' },
  { q: 'orderTotal',           has: FORM_ORDER, why: 'the target an expression button writes' },

  // --- an explicit facet is a FILTER: it returns everything that has the thing, and nothing that
  //     merely mentions it. A variable's own label is its identifier, not a caption, so `label:` must
  //     not answer with the variable a field binds to — which is what buried the forms. ---
  { q: 'label:orderTotal', missing: 'variable:orderTotal', why: "a variable's identifier is not a label" },
  { q: 'label:order',      missing: 'expression:${total > 100}', why: 'nor is an expression\'s text' },
  { q: 'label:amount',     missing: 'binding:{{amount}}',   why: 'nor a binding\'s' },

  // --- `label:` narrows to the captions a person reads, wherever they sit ---
  { q: 'label:recalculate', has: FORM_ORDER,   why: 'a button caption is a label' },
  { q: 'label:approve',     has: FORM_ORDER,   why: 'an outcome button caption is a label' },
  { q: 'label:priority',    has: DO_CUSTOMER,  why: 'a data object column label' },
  { q: 'label:update',      has: POLICY_ORDER, why: 'a permission label' },
  { q: 'label:approval',    has: PROC_ORDER,   why: 'a BPMN element name is its caption on the canvas' },
  { q: 'label:internal',    has: FORM_ORDER,   why: 'a field label, reachable by one of its words' },
  // The mirror image of the `id:Recalculate` case above: an id is not a caption either.
  { q: 'label:notifyButton', none: true,       why: 'an element id is not a label' },
  { q: 'label:zzzznope',    none: true,        why: 'a caption that exists nowhere' },
  { q: 'zzzznope label:approve', none: true,   why: 'the facet narrows, but the terms still have to match' },

  // --- `desc:` narrows to the prose the modeller wrote about the thing ---
  { q: 'desc:miniature',    top: APP_DEMO,     why: "Design's model Description on the app" },
  { q: 'desc:entry',        has: PROC_ORDER,   why: 'the process documentation' },
  { q: 'desc:backoffice',   top: PROC_ORDER,   why: 'a BPMN element documentation — and NOT the group of that name' },
  { q: 'desc:hidden',       top: FORM_ORDER,   why: 'a form component description' },
  { q: 'desc:approval',     has: DEC_ORDER,    why: 'a DMN rule annotation' },
  { q: 'desc:zzzznope',     none: true,        why: 'a description that exists nowhere' },

  // --- a facet tolerates the space and the casing people actually type ---
  { q: 'desc: backoffice',  top: PROC_ORDER,  why: 'a space after the colon is how people type it first' },
  { q: 'label: recalculate', has: FORM_ORDER, why: 'and it holds for every facet, not just desc:' },

  // --- a facet value in quotes: the only way to ask a facet for a multi-word caption. The regression:
  //     the phrase pass used to strip the quotes first, so `label: "Internal note"` degraded into the
  //     TERM `label` plus a phrase — three arbitrary nodes mentioning the word "label" instead of the
  //     form that carries the caption. ---
  { q: 'label: "Internal note"', has: FORM_ORDER, why: 'quoted facet value, spaced — the query people actually type' },
  { q: 'label:"internal note"',  has: FORM_ORDER, why: 'unspaced and lowercased, same answer' },
  { q: 'label:"note internal"',  none: true,      why: 'a quoted facet value stays contiguous, like any phrase' },
  { q: 'zzzznope label: "Internal note"', none: true, why: 'terms beside a quoted facet still AND in' },
  { q: 'DESC: BACKOFFICE',  top: PROC_ORDER,  why: 'prefix and value are both case-insensitive' },
  { q: 'CUSTOMER T: DATAOBJECT', has: DO_CUSTOMER, why: 'so are the terms beside it' },

  // --- and the point of it all: prose is findable without knowing the facet exists ---
  { q: 'backoffice checks', top: PROC_ORDER, why: 'two words that only ever occur in one documentation' },
  { q: 'shipping stamp',    has: PROC_ORDER, why: 'an element documentation, as a plain query' },

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
unit('qParse separates the label/desc facets',
  (p => [p.terms, p.facets])(engine.qParse('order label:Approve desc:Backoffice')),
  [['order'], { lab: 'approve', desc: 'backoffice' }]);
unit('qParse accepts a space after the colon',
  [engine.qParse('desc: approval').facets, engine.qParse('label:  courier').facets],
  [{ desc: 'approval' }, { lab: 'courier' }]);
// The regression this guards: without the space the query became the two TERMS `label` and `courier`,
// and answered with whichever node had the word "label" in a script body — a silent wrong hit.
unit('a spaced facet leaves no stray term behind', engine.qParse('label: courier').terms, []);
unit('qParse is case-insensitive on both halves',
  engine.qParse('DESC:Approval').facets, { desc: 'approval' });
unit('qParse accepts the desc: aliases',
  [engine.qParse('description:x').facets, engine.qParse('doc:x').facets],
  [{ desc: 'x' }, { desc: 'x' }]);
unit('qParse keeps a quoted phrase whole',
  engine.qParse('"get outreach" key').phrases, ['get outreach']);
// The regression this guards: the phrase pass ran first and ate the quotes, so the query below became
// the TERM `label` plus the phrase `mein label` — answering with nodes that merely mention the word
// "label" instead of everything carrying that caption.
unit('qParse binds a quoted value to its facet, not to the phrase list',
  (p => [p.terms, p.phrases, p.facets])(engine.qParse('label: "Mein Label"')),
  [[], [], { lab: 'mein label' }]);
unit('a quoted facet value works unspaced, for every facet',
  [engine.qParse('desc:"two words"').facets, engine.qParse('key:"x"').facets],
  [{ desc: 'two words' }, { key: 'x' }]);
unit('a quoted phrase containing a facet-shaped word stays a phrase',
  (p => [p.phrases, p.facets])(engine.qParse('"the label: thing"')),
  [['the label: thing'], {}]);
unit('a facet typed through the colon but unvalued is pending, never the term "label"',
  (p => [p.terms, p.facets, p.pending])(engine.qParse('customer label:')),
  [['customer'], {}, 'lab']);
unit('a lone pending facet keeps the query empty instead of searching for the prefix word',
  (p => [p.empty, p.pending])(engine.qParse('label: ')),
  [true, 'lab']);
unit('hlite marks every occurrence, merged and escaped-safe',
  engine.hlite('Template of a template', engine.qParse('template')).map(s => (s.hit ? '[' : '') + s.t + (s.hit ? ']' : '')).join(''),
  '[Template] of a [template]');
unit('hlite leaves a non-matching string in one piece',
  engine.hlite('nothing here', engine.qParse('zzz')), [{ t: 'nothing here', hit: false }]);
unit('fuzzyScore finds a typo as a subsequence', engine.fuzzyScore('customer', 'custmer') > 0, true);
unit('fuzzyScore rejects a non-subsequence', engine.fuzzyScore('customer', 'zzz'), 0);
// The regression this guards: `label:Recalculate` returned the right rows with zero <mark> on them —
// facet values never entered the highlight needles, so a facet hit looked like no hit at all.
unit('hlite marks a bound facet value like a term',
  engine.hlite('Recalculate total', engine.qParse('label:Recalculate')).some(s => s.hit), true);
unit('hlite does not highlight a bucket facet value (type:)',
  engine.hlite('form of forms', engine.qParse('t:form')).some(s => s.hit), false);
// The regressions these guard: the old suggester glued `custmer nam` into the single needle
// `custmernam` (matching nothing sensible), and scanned only name/key — a mistyped *caption* got no
// suggestion at all.
unit('suggestScore matches each mistyped word on its own',
  engine.suggestScore({ name: 'customer name', key: 'customerName', lab: '' }, ['custmer', 'nam']) > 0, true);
unit('suggestScore reaches captions, not just name and key',
  engine.suggestScore({ name: 'order form', key: 'orderForm', lab: 'kundennummer\nrecalculate' }, ['recalculte']) > 0, true);
unit('suggestScore is AND across words',
  engine.suggestScore({ name: 'customer name', key: 'customerName', lab: '' }, ['custmer', 'zzz']), 0);

// Every caption the Fields section renders MUST be in the index. This is the invariant that a report
// of "labels are not searchable" actually tests, and nothing checked it — which is why three fixes went
// to extraction and matching before the real cause (the page not drawing the row) was found.
{
  const missing = [];
  for (const n of nodes) {
    if (n.type !== 'form' && n.type !== 'page') continue;
    const lab = engine.searchIndex(n).lab;
    for (const f of (n.data || {}).fields || [])
      if (f.label && !lab.includes(String(f.label).toLowerCase())) missing.push(`${n.id}#${f.id}=${f.label}`);
  }
  unit('every field caption the panel renders is in the search index', missing, []);
}

// palWindow() belongs to the palette, not the engine, but it is pure and it is the reason a form whose
// caption matched could be absent from the screen while the search had found it. Lifted the same way.
const palWindow = new Function('searchIndex', `${lift('palWindow')}return palWindow;`)(engine.searchIndex);
{
  const isModels = h => engine.searchIndex(h.n).section === 'Models';
  const others = nodes.filter(n => engine.searchIndex(n).section !== 'Models').map(n => ({ n }));
  const models = nodes.filter(n => engine.searchIndex(n).section === 'Models').map(n => ({ n }));
  // The worst case the palette actually hits: every other section outranks Models, and there are more
  // of those than the page holds. A plain score cut renders no Models group at all.
  const list = others.concat(models);
  const secs = new Set(list.map(h => engine.searchIndex(h.n).section)).size;
  ran++;
  if (others.length <= secs || models.length < 2) {
    failed++;
    console.error('FAIL  palWindow case cannot be built from this report');
  } else console.log('ok    palWindow case is meaningful (' + others.length + ' non-Models hits)');
  unit('a plain score cut would hide the Models group', list.slice(0, secs).some(isModels), false);
  unit('the shared page keeps a slot for every section', palWindow(list, secs).some(isModels), true);
  unit('…and still fills the page', palWindow(list, secs).length, secs);
  // The regression that a per-section FLOOR introduced: three rows guaranteed became three rows total,
  // so ten forms carrying the searched caption were reported as three.
  const shown = Math.min(20, list.length - 1);
  unit('a section gets a share of the page, not a fixed floor',
    palWindow(list, shown).filter(isModels).length >= Math.min(models.length, Math.floor(shown / secs)),
    true);
  unit('a section that runs out leaves its share to the others', palWindow(list, shown).length, shown);
  unit('a page that fits everything is returned untouched', palWindow(list, list.length + 1), list);
}

// The row's "why did this match" hint. A facet query has to be explained by a field OF THAT KIND:
// answering `label:volume` with "id · expectedVolume" names the very field the facet exists to exclude.
function whyOf(q, id) {
  const p = engine.qParse(q);
  const n = nodes.find(x => x.id === id);
  const r = n && engine.scoreIndex(engine.searchIndex(n), p, 0);
  const w = r && engine.matchWhere(n, p, r.fields);
  return w ? w.hint : null;
}
// The hint leads with the matched text itself — it is what was searched for, and it is what the
// highlighter lights up; the owner follows after @.
unit('a label: hit is explained by the caption it matched',
  whyOf('label:Recalculate', FORM_ORDER), 'label · Recalculate @orderTotal');
unit('a label: hit on a task caption names that element',
  whyOf('label:approval', PROC_ORDER), 'label · Decide approval');
unit('a desc: hit is explained by the sentence it came from',
  whyOf('desc:Backoffice', PROC_ORDER), 'doc · Backoffice checks the order total before it shi… @approveTask');
unit("a label: hit on the node's own label leaves the hint to the key",
  whyOf('label:priority', DO_PRIORITY), null);

// A zero-result query must still be able to suggest something (the palette's "did you mean").
ran++;
const sug = nodes.map(n => engine.fuzzyScore(engine.searchIndex(n).name, 'custmer')).filter(s => s > 0);
if (!sug.length) { failed++; console.error('FAIL  typo query yields no suggestion candidates'); }
else console.log('ok    typo query yields suggestion candidates');

console.log(`\n${ran - failed}/${ran} passed  (report: ${path.basename(reportPath)}, ${nodes.length} nodes)`);
if (failed) { console.error(`${failed} search self-test case(s) failed`); process.exit(1); }
