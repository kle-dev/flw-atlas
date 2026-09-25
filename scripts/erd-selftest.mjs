#!/usr/bin/env node
/**
 * Behaviour test for the ER diagram designer's pure core (core/src/main/resources/frontend/ext/erd.js).
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
const JS_PATH = path.join(HERE, '..', 'core', 'src', 'main', 'resources', 'frontend', 'ext', 'erd.js');
const source = fs.readFileSync(JS_PATH, 'utf8');
const START = '/*__ERD_CORE_START__*/', END = '/*__ERD_CORE_END__*/';
const a = source.indexOf(START), b = source.indexOf(END);
if (a < 0 || b < 0 || b < a) {
  console.error(`erd-selftest: sentinels not found in ${JS_PATH} — the core must stay between __ERD_CORE_START__ and __ERD_CORE_END__.`);
  process.exit(2);
}
const E = new Function(`"use strict";${source.slice(a + START.length, b)};
  return {erdKey, erdCatalog, erdDefaultOrder, erdOrder, erdSuggestions, erdNormalize, erdToDoc, erdSame, erdEnds, erdContrast, erdSlug,
    ERD_FORMAT, ERD_FORMAT_VERSION, ERD_CARDINALITIES};`)();

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
  {id:'service:orderService', type:'service', key:'orderService', label:'Order Service', data:{tableName:'ORD_ORDER', columns:[]}},
  {id:'service:auditService', type:'service', key:'auditService', label:'Audit Service', data:{tableName:'aud_entry',
    columns:[{name:'id', columnName:'id_', type:'string'}, {name:'when', columnName:'when_', type:'date'}, {name:'x', columnName:'id_', type:'string'}]}},
  {id:'service:restService', type:'service', key:'restService', label:'REST', data:{}},
  {id:'dataObject:orderDO', type:'dataObject', key:'orderDO', label:'Order', data:{name:'Order', serviceTableName:'ord_order',
    columns:[{name:'customer', label:'Customer', type:'DATA-OBJECT', refDataObject:'customerDO', relationship:'one-to-one'},
             {name:'lines', label:'Lines', type:'DATA-OBJECT', refDataObject:'lineDO', relationship:'one-to-many'}]}},
  {id:'dataObject:customerDO', type:'dataObject', key:'customerDO', label:'Customer', data:{name:'Customer', serviceTableName:'cust_customer'}},
  {id:'dataObject:customerDO2', type:'dataObject', key:'customerDO2', label:'Customer (lite)', data:{name:'Customer (lite)', serviceTableName:'CUST_CUSTOMER'}},
];
const edges = [{s:'service:auditService', t:'service:orderService', rel:'relates-to-service'},
               {s:'service:restService', t:'service:orderService', rel:'relates-to-service'}];

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
eq('a table only a service knows: its mapped columns, once', [audit.source, audit.columns.map(c => c.name)], ['service', ['id_', 'when_']]);
ok('…with the service’s logical types, marked', audit.columns.every(c => c.logical) && audit.columns[1].type === 'date');
eq('no tables, no crash', E.erdCatalog(null), []);

// ---- column order ----
eq('primary key first, then the changelog order', E.erdDefaultOrder(order.columns), ['id_', 'order_no_', 'customer_id_']);
eq('a remembered order keeps its place; a gone column drops, a new one joins at the end',
  E.erdOrder(['CUSTOMER_ID_', 'dropped_', 'id_'], order.columns), ['customer_id_', 'id_', 'order_no_']);
eq('an empty memory is the default order', E.erdOrder([], order.columns), E.erdDefaultOrder(order.columns));

// ---- suggestions ----
const sug = E.erdSuggestions(nodes, edges);
eq('what the models state, between tables only', sug.map(s => [s.id, s.from, s.to, s.cardinality, s.label]), [
  ['dataObject:orderDO#customer', 'ORD_ORDER', 'CUST_CUSTOMER', '1:1', 'Customer'],
  ['service:auditService>orderService', 'AUD_ENTRY', 'ORD_ORDER', 'n:1', ''],
]);
ok('each says where it comes from', sug.every(s => s.why && s.why.length > 10));

// ---- the file format ----
const doc = {format:'atlas-erd', version:1, name:'Orders', project:'demo', tables:[
  {table:'ORD_ORDER', alias:'Order', x:10.4, y:'nope', color:'#E8590C', expanded:true, order:['id_', 5], columns:[{name:'id_', type:'VARCHAR(64)', pk:true}, {type:'x'}]},
  {table:'ord_order', alias:'duplicate'},
  {table:'CUST_CUSTOMER', color:'red'},
  {alias:'no table'},
], relations:[
  {id:'r1', from:'ord_order', to:'CUST_CUSTOMER', cardinality:'n:1', label:'placed by', fromColumn:'customer_id_', toColumn:'id_'},
  {id:'r1', from:'ORD_ORDER', to:'CUST_CUSTOMER', cardinality:'many'},
  {from:'ORD_ORDER', to:'NOWHERE'},
], dismissed:['x', 3]};
const d = E.erdNormalize(doc);
eq('tables: one per name, junk dropped', d.tables.map(t => t.key), ['ORD_ORDER', 'CUST_CUSTOMER']);
eq('numbers rounded, a bad one defaulted', [d.tables[0].x, typeof d.tables[0].y], [10, 'number']);
eq('a colour is a 6-digit hex or nothing', d.tables.map(t => t.color), ['#e8590c', '']);
eq('order keeps strings only; columns need a name', [d.tables[0].order, d.tables[0].columns.length], [['id_'], 1]);
eq('relations: ids unique, cardinality known, both ends present', d.relations.map(r => [r.id, r.from, r.cardinality]),
  [['r1', 'ORD_ORDER', 'n:1'], ['r1_', 'ORD_ORDER', '1:n']]);
eq('dismissed keeps strings', d.dismissed, ['x']);
const back = E.erdToDoc(d, {project:'demo', atlasVersion:'9.9.9', exportedAt:'2026-01-01T00:00:00Z'});
eq('the file names tables as written', back.relations[0].from, 'ORD_ORDER');
eq('…and round-trips', E.erdToDoc(E.erdNormalize(back)), E.erdToDoc(d));
ok('a primary key is written only where there is one', back.tables[0].columns[0].pk === true && !('pk' in (E.erdToDoc({tables:[{name:'T', key:'T', x:0, y:0, columns:[{name:'a', type:null}]}], relations:[]}).tables[0].columns[0])));
ok('same content is the same diagram', E.erdSame(d, E.erdNormalize(back)));
ok('a moved table is not', !E.erdSame(d, Object.assign({}, d, {tables:[Object.assign({}, d.tables[0], {x:99}), d.tables[1]]})));
throws('refuses what is not a diagram', () => E.erdNormalize({format:'other', version:1}), /not an Atlas ER diagram/);
throws('refuses a newer format', () => E.erdNormalize({format:'atlas-erd', version:2}), /newer Atlas/);
throws('refuses a missing version', () => E.erdNormalize({format:'atlas-erd'}), /version/);
throws('refuses a list', () => E.erdNormalize([]), /object/);
eq('an empty diagram is fine, and named', E.erdNormalize({format:'atlas-erd', version:1}).name, 'Imported diagram');

// ---- small helpers ----
eq('cardinality ends', [E.erdEnds('1:n'), E.erdEnds('n:m'), E.erdEnds('n:1')], [['1','n'], ['n','m'], ['n','1']]);
eq('ink on a colour', [E.erdContrast('#f59f00'), E.erdContrast('#2f6fed'), E.erdContrast('nope')], ['#131e29', '#ffffff', '']);
eq('file names', [E.erdSlug('Orders & Customers'), E.erdSlug('Übersicht Konten'), E.erdSlug('  ')], ['orders-customers', 'ubersicht-konten', 'diagram']);

if (failed) { console.error(`erd-selftest: ${failed} failed, ${passed} passed`); process.exit(1); }
console.log(`erd-selftest: all ${passed} checks passed`);
