#!/usr/bin/env node
/**
 * Behaviour test for the ER diagram designer's pure core (core/src/main/resources/frontend/erd.js).
 *
 * The designer decides a few things no picture shows wrong until much later: which tables a project has
 * (and from which changelog, when several define one), what a remembered column order means once the
 * schema has moved on, which relations the models already state, and what a diagram file may contain.
 * Those live in the __ERD_CORE_START__ … __ERD_CORE_END__ block — written free of DOM and page globals for
 * exactly this reason — and this script runs them in Node against small payloads. The page itself (drag,
 * draw, export) is erd-uitest.mjs's.
 *
 * Usage:  node scripts/erd-selftest.mjs        (exit 0 = all passed)
 */
import fs from 'fs';
import path from 'path';
import url from 'url';

const HERE = path.dirname(url.fileURLToPath(import.meta.url));
const JS_PATH = path.join(HERE, '..', 'core', 'src', 'main', 'resources', 'frontend', 'erd.js');
const source = fs.readFileSync(JS_PATH, 'utf8');
const START = '/*__ERD_CORE_START__*/', END = '/*__ERD_CORE_END__*/';
const a = source.indexOf(START), b = source.indexOf(END);
if (a < 0 || b < 0 || b < a) {
  console.error(`erd-selftest: sentinels not found in ${JS_PATH} — the core must stay between __ERD_CORE_START__ and __ERD_CORE_END__.`);
  process.exit(2);
}
const E = new Function(`"use strict";${source.slice(a + START.length, b)};
  return {erdKey, erdCatalog, erdDefaultOrder, erdOrder, erdSuggestions, erdNormalize, erdToDoc, erdSame, erdEnds, erdContrast, erdSlug, erdArrange, erdSearch, erdColumnHits, erdTableMatches,
    erdEnd, erdCardinality, erdEndOne, erdFrameParents, erdArrangeFramed, erdOpenSuggestions, erdPairs,
    ERD_FORMAT, ERD_FORMAT_VERSION, ERD_ENDS, ERD_FRAME_PAD, ERD_FRAME_TOP};`)();

let failed = 0, passed = 0;
function ok(label, cond, detail) {
  if (cond) { passed++; return; }
  failed++;
  console.error('FAIL ' + label + (detail !== undefined ? ' — ' + JSON.stringify(detail) : ''));
}
function eq(label, got, want) { ok(label, JSON.stringify(got) === JSON.stringify(want), {got, want}); }
function throws(label, fn, re) {
  try { fn(); ok(label, false, 'did not throw'); } catch (e) { ok(label, !re || re.test(e.message), e.message); }
}

// ---- a project: two changelogs define ORD_ORDER (one superseded), one table only a service knows ----
const nodes = [
  {id:'liquibase:old', type:'liquibase', key:'old', label:'000-old.xml', data:{authority:{status:'superseded'},
    columns:[{name:'id_', type:'varchar(32)', table:'ord_order'}]}},
  {id:'liquibase:002-order', type:'liquibase', key:'002-order', label:'002-order.xml', data:{authority:{status:'live'},
    columns:[{name:'order_no_', type:'VARCHAR(64)', table:'ord_order'}, {name:'id_', type:'VARCHAR(64)', table:'ord_order', pk:true},
             {name:'ID_', type:'VARCHAR(64)', table:'ORD_ORDER'},     // the same column again, other case: once
             {name:'customer_id_', type:'VARCHAR(64)', table:'ord_order'},
             {name:'id_', type:'VARCHAR(64)', table:'cust_customer', pk:true}, {name:'name_', type:'VARCHAR(255)', table:'cust_customer'}]}},
  {id:'service:orderService', type:'service', key:'orderService', label:'Order Service', data:{tableName:'ORD_ORDER', columns:[
    {name:'id', columnName:'id_', type:'string'},
    {name:'customer', columnName:'customer_id_', type:'object-relation', relation:{service:'customerService', column:'id'}},
    {name:'payer', columnName:'payer_id_', type:'object-relation', relation:{service:'customerService', column:'id'}},
    {name:'lost', columnName:'lost_id_', type:'object-relation', relation:{service:'noSuchService', column:'id'}}]}},
  {id:'service:customerService', type:'service', key:'customerService', label:'Customer Service', data:{tableName:'cust_customer',
    columns:[{name:'id', columnName:'id_', type:'string'}]}},
  {id:'service:auditService', type:'service', key:'auditService', label:'Audit Service', data:{tableName:'aud_entry',
    columns:[{name:'id', columnName:'id_', type:'string'}, {name:'when', columnName:'when_', type:'date'}, {name:'x', columnName:'id_', type:'string'},
             {name:'order', columnName:'order_id_', type:'object-relation', relation:{service:'orderService', column:'id'}}]}},
  {id:'service:restService', type:'service', key:'restService', label:'REST', data:{}},
  {id:'dataObject:orderDO', type:'dataObject', key:'orderDO', label:'Order', data:{name:'Order', serviceTableName:'ord_order', service:'orderService',
    columns:[{name:'customer', label:'Customer', type:'DATA-OBJECT', refDataObject:'customerDO', relationship:'one-to-one'},
             {name:'payer', label:'Payer', type:'DATA-OBJECT', refDataObject:'customerDO', relationship:'one-to-one'},
             {name:'lines', label:'Lines', type:'DATA-OBJECT', refDataObject:'lineDO', relationship:'one-to-many'}]}},
  {id:'dataObject:customerDO', type:'dataObject', key:'customerDO', label:'Customer', data:{name:'Customer', serviceTableName:'cust_customer'}},
  {id:'dataObject:customerDO2', type:'dataObject', key:'customerDO2', label:'Customer (lite)', data:{name:'Customer (lite)', serviceTableName:'CUST_CUSTOMER'}},
];
const cat = E.erdCatalog(nodes);
eq('every table once, sorted by name', cat.map(t => t.key), ['AUD_ENTRY', 'CUST_CUSTOMER', 'ORD_ORDER']);
const order = cat.find(t => t.key === 'ORD_ORDER');
eq('the live changelog wins over a superseded one', order.changelog, 'liquibase:002-order');
eq('columns as the changelog has them, each once', order.columns.map(c => c.name), ['order_no_', 'id_', 'customer_id_']);
eq('the raw SQL type is kept', order.columns[0].type, 'VARCHAR(64)');
eq('the primary key is known', order.columns.filter(c => c.pk).map(c => c.name), ['id_']);
eq('services and data objects behind a table', [order.services, order.dataObjects.map(d => d.key)], [['service:orderService'], ['orderDO']]);
eq('one data object → its name is the business name', order.alias, 'Order');
eq('two data objects → no guess', cat.find(t => t.key === 'CUST_CUSTOMER').alias, '');
const audit = cat.find(t => t.key === 'AUD_ENTRY');
eq('a table only a service knows: its mapped columns, once', [audit.source, audit.columns.map(c => c.name)], ['service', ['id_', 'when_', 'order_id_']]);
ok('…with the service’s logical types, marked', audit.columns.every(c => c.logical) && audit.columns[1].type === 'date');
eq('no tables, no crash', E.erdCatalog(null), []);

// ---- column order ----
eq('primary key first, then the changelog order', E.erdDefaultOrder(order.columns), ['id_', 'order_no_', 'customer_id_']);
eq('a remembered order keeps its place; a gone column drops, a new one joins at the end',
  E.erdOrder(['CUSTOMER_ID_', 'dropped_', 'id_'], order.columns), ['customer_id_', 'id_', 'order_no_']);
eq('an empty memory is the default order', E.erdOrder([], order.columns), E.erdDefaultOrder(order.columns));

// ---- suggestions ----
const sug = E.erdSuggestions(nodes);
const cols = s => s.pairs.map(p => p.from + '→' + p.to).join(',');
eq('what the models state, between tables only, each over its columns', sug.map(s => [s.id, s.from, s.to, cols(s), s.cardinality, s.label]), [
  ['dataObject:orderDO#customer', 'ORD_ORDER', 'CUST_CUSTOMER', 'customer_id_→id_', '0..n:1', 'Customer'],
  ['dataObject:orderDO#payer', 'ORD_ORDER', 'CUST_CUSTOMER', 'payer_id_→id_', '0..n:1', 'Payer'],
  ['service:auditService#order_id_', 'AUD_ENTRY', 'ORD_ORDER', 'order_id_→id_', '0..n:1', ''],
]);
ok('a data object field and its service column are one relation, and says so twice', /field Customer/.test(sug[0].why) && /column customer_id_/.test(sug[0].why), sug[0].why);
ok('each says where it comes from', sug.every(s => s.why && s.why.length > 10));
{ // a field the service does not map to a column: one relation when it is the only one between its tables
  const n = [
    {type:'service', key:'aS', data:{tableName:'A', columns:[{name:'b', columnName:'b_id_', relation:{service:'bS', column:'id'}}]}},
    {type:'service', key:'bS', data:{tableName:'B', columns:[]}},
    {type:'dataObject', key:'aDO', label:'A', data:{serviceTableName:'A', service:'aS', columns:[{name:'theB', label:'The B', refDataObject:'bDO'}]}},
    {type:'dataObject', key:'bDO', data:{serviceTableName:'B'}},
  ];
  eq('…merged into the one over columns', E.erdSuggestions(n).map(s => [s.id, cols(s), s.label]), [['dataObject:aDO#theB', 'b_id_→id', 'The B']]);
  n[2].data.columns.push({name:'otherB', label:'Other B', refDataObject:'bDO'});
  eq('…but two of them are not guessed at', E.erdSuggestions(n).map(s => [s.id, cols(s)]),
    [['dataObject:aDO#theB', ''], ['dataObject:aDO#otherB', ''], ['service:aS#b_id_', 'b_id_→id']]);
}
{ // what a diagram still lacks: a drawn relation accounts for one proposal between its tables
  const on = new Set(['ORD_ORDER', 'CUST_CUSTOMER']);
  const open = (rels, dismissed) => E.erdOpenSuggestions(sug, rels, on, dismissed || []).map(s => s.id);
  eq('both tables on the canvas', open([]), ['dataObject:orderDO#customer', 'dataObject:orderDO#payer']);
  eq('a dismissed one is gone', open([], ['dataObject:orderDO#payer']), ['dataObject:orderDO#customer']);
  eq('a relation over its columns takes that one — the second relation between the tables is still proposed',
    open([{from:'ORD_ORDER', to:'CUST_CUSTOMER', pairs:[{from:'CUSTOMER_ID_', to:'id_'}]}]), ['dataObject:orderDO#payer']);
  eq('…drawn the other way round too', open([{from:'CUST_CUSTOMER', to:'ORD_ORDER', pairs:[{from:'id_', to:'payer_id_'}]}]), ['dataObject:orderDO#customer']);
  eq('a relation without columns takes the first one left', open([{from:'ORD_ORDER', to:'CUST_CUSTOMER', pairs:[]}]), ['dataObject:orderDO#payer']);
  eq('…two take both', open([{from:'ORD_ORDER', to:'CUST_CUSTOMER', pairs:[]}, {from:'CUST_CUSTOMER', to:'ORD_ORDER', pairs:[]}]), []);
  eq('a relation over other columns is another relation', open([{from:'ORD_ORDER', to:'CUST_CUSTOMER', pairs:[{from:'note_', to:''}]}]),
    ['dataObject:orderDO#customer', 'dataObject:orderDO#payer']);
  eq('a table off the canvas, no proposal', E.erdOpenSuggestions(sug, [], new Set(['ORD_ORDER']), []).length, 0);
}

// ---- the file format ----
const doc = {format:'atlas-erd', version:3, name:'Orders', project:'demo', tables:[
  {table:'ORD_ORDER', alias:'Order', x:10.4, y:'nope', color:'#E8590C', expanded:true, order:['id_', 5], columns:[{name:'id_', type:'VARCHAR(64)', pk:true}, {type:'x'}]},
  {table:'ord_order', alias:'duplicate'},
  {table:'CUST_CUSTOMER', color:'red'},
  {alias:'no table'},
], relations:[
  {id:'r1', from:'ord_order', to:'CUST_CUSTOMER', cardinality:'n:1', label:'placed by', fromColumn:'customer_id_', toColumn:'id_'},
  {id:'r1', from:'ORD_ORDER', to:'CUST_CUSTOMER', cardinality:'many'},
  {from:'ORD_ORDER', to:'NOWHERE'},
  {id:'r3', from:'CUST_CUSTOMER', to:'ORD_ORDER', cardinality:'1 : 1..*', fromColumn:['id_', 'no_', 7], toColumn:['customer_id_', 'customer_no_']},
], frames:[
  {id:'f1', name:'Sales', x:-20.6, y:-60, w:900, h:400, color:'#0E9F6E'},
  {id:'f1', name:'  ', x:0, y:0, w:10, h:'tall', color:'green'},
  'not a frame',
], dismissed:['x', 3]};
const d = E.erdNormalize(doc);
eq('tables: one per name, junk dropped', d.tables.map(t => t.key), ['ORD_ORDER', 'CUST_CUSTOMER']);
eq('numbers rounded, a bad one defaulted', [d.tables[0].x, typeof d.tables[0].y], [10, 'number']);
eq('a colour is a 6-digit hex or nothing', d.tables.map(t => t.color), ['#e8590c', '']);
eq('order keeps strings only; columns need a name', [d.tables[0].order, d.tables[0].columns.length], [['id_'], 1]);
eq('relations: ids unique, cardinality read as this page writes it, both ends present', d.relations.map(r => [r.id, r.from, r.cardinality]),
  [['r1', 'ORD_ORDER', '0..n:1'], ['r1_', 'ORD_ORDER', '1:0..n'], ['r3', 'CUST_CUSTOMER', '1:1..n']]);
eq('a relation’s columns: one pair from two names, several from two lists, position by position', d.relations.map(r => r.pairs),
  [[{from:'customer_id_', to:'id_'}], [], [{from:'id_', to:'customer_id_'}, {from:'no_', to:'customer_no_'}]]);
eq('…and written back the same way', E.erdToDoc(d).relations.map(r => [r.fromColumn, r.toColumn]),
  [['customer_id_', 'id_'], ['', ''], [['id_', 'no_'], ['customer_id_', 'customer_no_']]]);
eq('lists of unequal length leave the other side out; a pair with neither is none', [E.erdPairs(['a', 'b'], ['x']), E.erdPairs(['', 3], [null]), E.erdPairs('a', undefined)],
  [[{from:'a', to:'x'}, {from:'b', to:''}], [], [{from:'a', to:''}]]);
eq('frames: ids unique, numbers rounded, at least their least size, a colour or none', d.frames,
  [{id:'f1', name:'Sales', x:-21, y:-60, w:900, h:400, color:'#0e9f6e'}, {id:'f1_', name:'  ', x:0, y:0, w:160, h:320, color:''}]);
eq('dismissed keeps strings', d.dismissed, ['x']);
const back = E.erdToDoc(d, {project:'demo', atlasVersion:'9.9.9', exportedAt:'2026-01-01T00:00:00Z'});
eq('the file names tables as written', back.relations[0].from, 'ORD_ORDER');
eq('…and round-trips', E.erdToDoc(E.erdNormalize(back)), E.erdToDoc(d));
ok('a primary key is written only where there is one', back.tables[0].columns[0].pk === true && !('pk' in (E.erdToDoc({tables:[{name:'T', key:'T', x:0, y:0, columns:[{name:'a', type:null}]}], relations:[]}).tables[0].columns[0])));
ok('same content is the same diagram', E.erdSame(d, E.erdNormalize(back)));
ok('a moved table is not', !E.erdSame(d, Object.assign({}, d, {tables:[Object.assign({}, d.tables[0], {x:99}), d.tables[1]]})));
throws('refuses what is not a diagram', () => E.erdNormalize({format:'other', version:1}), /not an Atlas ER diagram/);
throws('refuses a newer format', () => E.erdNormalize({format:'atlas-erd', version:4}), /newer Atlas/);
{ // a file from before frames and counts at each end reads as it is
  const v1 = E.erdNormalize({format:'atlas-erd', version:1, tables:[{table:'A'}, {table:'B'}],
    relations:[{from:'A', to:'B', cardinality:'1:n'}, {from:'A', to:'B', cardinality:'n:m'}, {from:'B', to:'A', cardinality:'1:1'}]});
  eq('version 1: 1:n is exactly one to zero or more, n:m zero or more at both ends', v1.relations.map(r => r.cardinality), ['1:0..n', '0..n:0..n', '1:1']);
  eq('…and has no frames', v1.frames, []);
  eq('a file is written in the current version', E.erdToDoc(v1).version, E.ERD_FORMAT_VERSION);
}
eq('the frames round-trip', E.erdNormalize(E.erdToDoc(d)).frames, d.frames);
throws('refuses a missing version', () => E.erdNormalize({format:'atlas-erd'}), /version/);
throws('refuses a list', () => E.erdNormalize([]), /object/);
eq('an empty diagram is fine, and named', E.erdNormalize({format:'atlas-erd', version:1}).name, 'Imported diagram');

// ---- small helpers ----
eq('cardinality ends', [E.erdEnds('1:0..n'), E.erdEnds('0..1:1..n'), E.erdEnds('n:1'), E.erdEnds('nonsense')],
  [['1','0..n'], ['0..1','1..n'], ['0..n','1'], ['1','0..n']]);
eq('every end, and how people write them', ['1', '1..1', '0..1', '0 .. 1', '1..n', '1..*', '1..m', '0..n', 'n', 'M', '*', '0..*', '0', '2', ''].map(E.erdEnd),
  ['1', '1', '0..1', '0..1', '1..n', '1..n', '1..n', '0..n', '0..n', '0..n', '0..n', '0..n', null, null, null]);
eq('sixteen cardinalities, each a pair of ends', E.ERD_ENDS.flatMap(a => E.ERD_ENDS.map(b => E.erdCardinality(a + ':' + b))).filter(Boolean).length, 16);
eq('…and nothing else', [E.erdCardinality('1'), E.erdCardinality('1:2'), E.erdCardinality('1:n:m'), E.erdCardinality(null)], [null, null, null, null]);
eq('the one side of a relation', E.ERD_ENDS.map(E.erdEndOne), [true, true, false, false]);
eq('ink on a colour', [E.erdContrast('#f59f00'), E.erdContrast('#2f6fed'), E.erdContrast('nope')], ['#131e29', '#ffffff', '']);
eq('file names', [E.erdSlug('Orders & Customers'), E.erdSlug('Übersicht Konten'), E.erdSlug('  ')], ['orders-customers', 'ubersicht-konten', 'diagram']);

// ---- arranging ----
const box = (key, w = 220, h = 150, x = 0, y = 0) => ({key, w, h, x, y});
const rel = (from, to, cardinality = '1:n', gap = 0) => ({from, to, cardinality, gap});
function overlaps(nodes, pos, margin = 16) {
  const r = nodes.map(n => ({k: n.key, x: pos[n.key].x, y: pos[n.key].y, w: n.w, h: n.h}));
  for (let i = 0; i < r.length; i++) for (let j = i + 1; j < r.length; j++) {
    const a = r[i], b = r[j];
    if (a.x < b.x + b.w + margin && b.x < a.x + a.w + margin && a.y < b.y + b.h + margin && b.y < a.y + a.h + margin) return [a.k, b.k];
  }
  return null;
}
const centre = (n, p) => ({x: p[n.key].x + n.w / 2, y: p[n.key].y + n.h / 2});
function crossings(nodes, edges, pos) {
  const by = new Map(nodes.map(n => [n.key, n]));
  const seg = edges.map(e => [centre(by.get(e.from), pos), centre(by.get(e.to), pos)]);
  const ccw = (a, b, c) => (c.y - a.y) * (b.x - a.x) > (b.y - a.y) * (c.x - a.x);
  let c = 0;
  for (let i = 0; i < seg.length; i++) for (let j = i + 1; j < seg.length; j++) {
    const [a, b] = seg[i], [d, e] = seg[j];
    if ([a, b].some(p => [d, e].some(q => p.x === q.x && p.y === q.y))) continue;   // sharing a table is no crossing
    if (ccw(a, d, e) !== ccw(b, d, e) && ccw(a, b, d) !== ccw(a, b, e)) c++;
  }
  return c;
}

{ // a chain reads left to right, one side first; n:1 turns the arc round
  const nodes = [box('LINE', 200, 120, 0, 0), box('ORDER', 240, 200, 50, 400), box('CUSTOMER', 220, 160, 900, 100)];
  const edges = [rel('CUSTOMER', 'ORDER', '1:n'), rel('LINE', 'ORDER', 'n:1')];
  const p = E.erdArrange(nodes, edges);
  ok('the one side of a relation stands left of its many side', p.CUSTOMER.x < p.ORDER.x && p.ORDER.x < p.LINE.x, p);
  eq('no table overlaps another', overlaps(nodes, p), null);
  eq('the arrangement starts where the drawing was', [Math.min(...Object.values(p).map(q => q.x)), Math.min(...Object.values(p).map(q => q.y))], [0, 0]);
  const again = E.erdArrange(nodes.map(n => Object.assign({}, n, p[n.key])), edges);
  const sorted = o => Object.fromEntries(Object.keys(o).sort().map(k => [k, o[k]]));
  eq('arranging twice changes nothing the second time', sorted(again), sorted(p));
  eq('the same input, the same picture', sorted(E.erdArrange(nodes, edges)), sorted(p));
}
{ // two parents whose children sit the wrong way round: arranging uncrosses them
  const nodes = [box('P1', 200, 120, 0, 0), box('P2', 200, 120, 0, 300), box('C1', 200, 120, 500, 0), box('C2', 200, 120, 500, 300)];
  const edges = [rel('P1', 'C2'), rel('P2', 'C1')];
  eq('before, the two relations cross', crossings(nodes, edges, Object.fromEntries(nodes.map(n => [n.key, {x: n.x, y: n.y}]))), 1);
  const p = E.erdArrange(nodes, edges);
  eq('after, they do not', crossings(nodes, edges, p), 0);
  eq('…and nothing overlaps', overlaps(nodes, p), null);
}
{ // a table sits level with the one it relates to
  const nodes = [box('A', 200, 120, 0, 0), box('B', 200, 300, 0, 400), box('X', 200, 120, 600, 900)];
  const p = E.erdArrange(nodes, [rel('B', 'X')]);
  ok('a child is level with its parent (to the 4px grid)', Math.abs(centre(nodes[2], p).y - centre(nodes[1], p).y) <= 4, p);
}
{ // a relation back to an ancestor, a table on its own, a name that needs room
  const nodes = [box('A'), box('B', 220, 150, 0, 200), box('C', 220, 150, 0, 400), box('LONELY', 180, 90, 0, 600), box('D', 220, 150, 0, 800)];
  const edges = [rel('A', 'B'), rel('B', 'C'), rel('C', 'A'), rel('C', 'D', '1:1', 320)];
  const p = E.erdArrange(nodes, edges);
  eq('a cycle is laid out anyway, every table placed', Object.keys(p).sort(), ['A', 'B', 'C', 'D', 'LONELY']);
  eq('…without overlaps', overlaps(nodes, p), null);
  ok('a long relation name gets the room it needs', p.D.x - (p.C.x + 220) >= 320 + 60 - 4, [p.C, p.D]);
  ok('a table on its own is packed with the rest', p.LONELY.y >= 0);
}
{ // many tables: still no overlap, still quick
  let seed = 7;
  const rnd = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
  const nodes = [...Array(40)].map((_, i) => box('T' + i, 180 + Math.floor(rnd() * 180), 90 + Math.floor(rnd() * 160), Math.floor(rnd() * 2000), Math.floor(rnd() * 2000)));
  const edges = [...Array(55)].map(() => rel('T' + Math.floor(rnd() * 40), 'T' + Math.floor(rnd() * 40), ['1:n', 'n:1', '1:1', 'n:m'][Math.floor(rnd() * 4)]));
  const t0 = Date.now(), p = E.erdArrange(nodes, edges), ms = Date.now() - t0;
  eq('forty tables: every one placed', Object.keys(p).length, 40);
  eq('forty tables: none overlaps', overlaps(nodes, p), null);
  ok('forty tables: fewer crossings than the scattered start', crossings(nodes, edges.filter(e => e.from !== e.to), p) <
    crossings(nodes, edges.filter(e => e.from !== e.to), Object.fromEntries(nodes.map(n => [n.key, {x: n.x, y: n.y}]))));
  ok('forty tables: arranged in well under a second (' + ms + ' ms)', ms < 1000);
}
eq('nothing to arrange', E.erdArrange([], []), {});

// ---- frames ----
{ // what a frame holds: a table by its centre, a frame wholly inside it, the smallest when several do
  const frames = [{id: 'OUT', x: 0, y: 0, w: 1000, h: 800}, {id: 'IN', x: 100, y: 100, w: 400, h: 300},
    {id: 'SAME', x: 0, y: 0, w: 1000, h: 800}, {id: 'CROSS', x: 900, y: 700, w: 300, h: 300}];
  const tables = [box('A', 100, 80, 150, 150), box('B', 100, 80, 600, 150), box('C', 100, 80, 460, 360), box('D', 100, 80, 2000, 0)];
  const p = E.erdFrameParents(frames, tables);
  eq('a table belongs to the smallest frame around its centre', [p.table.get('A'), p.table.get('B'), p.table.get('D')], ['IN', 'SAME', undefined]);
  eq('…by its centre, not its corner', p.table.get('C'), 'SAME');
  eq('a frame inside a frame belongs to it; of two equal ones, the later is inside the earlier; one across an edge to none',
    [p.frame.get('IN'), p.frame.get('SAME'), p.frame.get('OUT'), p.frame.get('CROSS')], ['SAME', 'OUT', undefined, undefined]);
  eq('no frames, no parents', [E.erdFrameParents([], tables).table.size, E.erdFrameParents(null, null).frame.size], [0, 0]);
}
const inside = (b, r) => b.x >= r.x && b.y >= r.y && b.x + b.w <= r.x + r.w && b.y + b.h <= r.y + r.h;
{ // arranging keeps a frame's tables in it, fits the frame around them, and relates the frame as a block
  const nodes = [box('CUSTOMER', 220, 160, 40, 40), box('ADDRESS', 200, 120, 60, 300), box('ORDER', 240, 200, 0, 900), box('LINE', 200, 120, 400, 900)];
  const frames = [{id: 'f1', x: 0, y: 0, w: 400, h: 500, minW: 300}];
  const edges = [rel('CUSTOMER', 'ADDRESS', '1:0..n'), rel('CUSTOMER', 'ORDER', '1:0..n'), rel('ORDER', 'LINE', '1:1..n')];
  const res = E.erdArrangeFramed(nodes, edges, frames);
  eq('every table placed, and the frame', [Object.keys(res.tables).sort(), Object.keys(res.frames)], [['ADDRESS', 'CUSTOMER', 'LINE', 'ORDER'], ['f1']]);
  const F = res.frames.f1, at = k => Object.assign({}, nodes.find(n => n.key === k), res.tables[k]);
  ok('the frame holds its tables, inside its padding', ['CUSTOMER', 'ADDRESS'].every(k => inside(at(k),
    {x: F.x + E.ERD_FRAME_PAD, y: F.y + E.ERD_FRAME_TOP, w: F.w - 2 * E.ERD_FRAME_PAD, h: F.h - E.ERD_FRAME_TOP - E.ERD_FRAME_PAD})), {F, t: res.tables});
  ok('…and fits around them', F.x + F.w === at('ADDRESS').x + 200 + E.ERD_FRAME_PAD && F.x === at('CUSTOMER').x - E.ERD_FRAME_PAD &&
    F.y + F.h === Math.max(at('CUSTOMER').y + 160, at('ADDRESS').y + 120) + E.ERD_FRAME_PAD, {F, t: res.tables});
  ok('…as wide as its name needs', F.w >= 300, F);
  ok('the tables outside stay outside', ['ORDER', 'LINE'].every(k => { const b = at(k); return b.x >= F.x + F.w || b.x + b.w <= F.x || b.y >= F.y + F.h || b.y + b.h <= F.y; }), {F, t: res.tables});
  ok('inside the frame, the one side left of the many side', at('CUSTOMER').x < at('ADDRESS').x, res.tables);
  ok('the frame stands left of what its tables relate to outside it', F.x + F.w <= at('ORDER').x, {F, t: res.tables});
  eq('nothing overlaps outside the frame', overlaps([nodes[2], nodes[3], {key: 'F', w: F.w, h: F.h}], Object.assign({F: {x: F.x, y: F.y}}, res.tables)), null);
  const again = E.erdArrangeFramed(nodes.map(n => Object.assign({}, n, res.tables[n.key])), edges, [Object.assign({id: 'f1', minW: 300}, F)]);
  eq('arranging twice changes nothing the second time', again, res);
}
{ // a frame in a frame, and one that holds nothing
  const nodes = [box('A', 200, 100, 120, 120), box('B', 200, 100, 700, 120), box('C', 200, 100, 1500, 1500)];
  const frames = [{id: 'outer', x: 0, y: 0, w: 1200, h: 600}, {id: 'inner', x: 60, y: 60, w: 400, h: 300}, {id: 'empty', x: 2000, y: 0, w: 300, h: 200}];
  const res = E.erdArrangeFramed(nodes, [rel('A', 'B'), rel('B', 'C')], frames);
  const r = k => Object.assign({}, nodes.find(n => n.key === k), res.tables[k]);
  ok('a table stays in its inner frame', inside(r('A'), res.frames.inner), res);
  ok('the inner frame stays in the outer one, beside its other table', inside(res.frames.inner, res.frames.outer) && inside(r('B'), res.frames.outer), res);
  ok('a frame that holds nothing keeps its size', res.frames.empty.w === 300 && res.frames.empty.h === 200, res.frames.empty);
  eq('…and nothing lands on it', overlaps([nodes[2], {key: 'E', w: 300, h: 200}, {key: 'O', w: res.frames.outer.w, h: res.frames.outer.h}],
    {C: res.tables.C, E: res.frames.empty, O: res.frames.outer}), null);
}
{ // without frames it is erdArrange
  const nodes = [box('X'), box('Y', 220, 150, 400, 0)];
  eq('no frames: the arrangement erdArrange makes', E.erdArrangeFramed(nodes, [rel('X', 'Y')], []), {tables: E.erdArrange(nodes, [rel('X', 'Y')]), frames: {}});
}

// ---- searching ----
{
  const cat = [
    {key: 'ORD_ORDER', name: 'ord_order', alias: 'Order', dataObjects: [{name: 'Order'}],
     columns: [{name: 'id_', type: 'VARCHAR(64)', pk: true}, {name: 'customer_id_', type: 'VARCHAR(64)'}, {name: 'note_', type: 'VARCHAR(4000)'}]},
    {key: 'CUST_CUSTOMER', name: 'cust_customer', alias: 'Customer', dataObjects: [{name: 'Customer'}],
     columns: [{name: 'id_', type: 'VARCHAR(64)', pk: true}, {name: 'customer_no_', type: 'VARCHAR(32)'}]},
    {key: 'DEMO_WIDE', name: 'demo_wide', alias: '', dataObjects: [],
     columns: [...Array(40)].map((_, i) => ({name: 'attr_' + i + '_', type: 'VARCHAR(255)'})).concat([{name: 'CUSTOMER_REF_', type: 'VARCHAR(64)'}])},
  ];
  const r = E.erdSearch(cat, '  Customer ', new Set(['ORD_ORDER']));
  eq('counts tables and columns apart', [r.tables, r.columns, r.total], [1, 3, 4]);
  eq('tables first, then columns; in each the diagram’s own first, then the closest match, the table’s name and the column’s place',
    r.items.map(i => i.kind + ':' + i.table + (i.column ? '.' + i.column : '')),
    ['table:cust_customer', 'column:ord_order.customer_id_', 'column:cust_customer.customer_no_', 'column:demo_wide.CUSTOMER_REF_']);
  const many = [{key: 'DEMO_ORDER', name: 'demo_order', alias: '', dataObjects: [], columns: [{name: 'id_'}]}].concat([...Array(70)].map((_, i) =>
    ({key: 'DEMO_T' + i, name: 'demo_t' + i, alias: '', dataObjects: [], columns: [{name: 'order_id_'}]})));
  eq('a table is not pushed off the list by the columns of the tables on the diagram',
    E.erdSearch(many, 'order', new Set(many.slice(1).map(t => t.key)), 60).items[0].key, 'DEMO_ORDER');
  ok('a result says whether its table is on the diagram', r.items.find(i => i.column === 'customer_id_').on === true && r.items.find(i => i.kind === 'table').on === false);
  eq('a business name finds its table', E.erdSearch(cat, 'order').items.filter(i => i.kind === 'table').map(i => i.key), ['ORD_ORDER']);
  eq('a type finds its columns, after any name', E.erdSearch(cat, '4000').items.map(i => i.column), ['note_']);
  eq('the limit keeps the list short, the count stays whole', [E.erdSearch(cat, 'attr_', null, 10).items.length, E.erdSearch(cat, 'attr_', null, 10).total], [10, 40]);
  eq('no query, no results', E.erdSearch(cat, '   ').total, 0);
  const keyed = [{key: 'ORD_ORDER', name: 'ord_order', alias: '', dataObjects: [{name: 'Order', key: 'orderDO'}], services: ['service:orderService'], columns: []}];
  eq('a data object key finds its table', E.erdSearch(keyed, 'orderdo').items.map(i => i.key), ['ORD_ORDER']);
  eq('…and a service key', E.erdSearch(keyed, 'orderService').items.map(i => i.key), ['ORD_ORDER']);
  ok('…on the canvas too', E.erdTableMatches('x', '', [{name: 'Order', key: 'orderDO'}], 'orderdo') && E.erdTableMatches('x', '', [], 'orderservice', ['service:orderService']));
  eq('the columns a query finds in one table, by name or type', [...E.erdColumnHits(cat[0].columns, 'varchar(4')], ['NOTE_']);
  ok('a table answers by its name, business name or data object', E.erdTableMatches('ord_order', '', [{name: 'Order'}], 'order') &&
    E.erdTableMatches('x', 'Bestellung', [], 'bestell') && !E.erdTableMatches('x', '', [], 'order') && !E.erdTableMatches('x', '', [], ''));
}

if (failed) { console.error(`erd-selftest: ${failed} failed, ${passed} passed`); process.exit(1); }
console.log(`erd-selftest: all ${passed} checks passed`);
