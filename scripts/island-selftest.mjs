// Self-test of the explorer's inflater — the block between /*__ISLAND_START__*/ and /*__ISLAND_END__*/ in
// explorer.js, which turns a deflated, Base64-encoded data island (ExplorerHtmlRenderer.islandTag) back into
// its JSON. Evaluated here in node, outside the page, and checked against node's own zlib: every block type
// DEFLATE has (stored, fixed and dynamic Huffman), every compression level and strategy that produces a
// different mix of them, text in several scripts, truncated and mislabelled input — and the time a 20 MB
// island takes, because the page inflates it before it can draw anything.
//
// usage: node scripts/island-selftest.mjs [explorer.js]
import fs from 'fs';
import path from 'path';
import vm from 'vm';
import zlib from 'zlib';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const jsPath = process.argv[2] || path.join(here, '..', 'core', 'src', 'main', 'resources', 'frontend', 'explorer.js');
const js = fs.readFileSync(jsPath, 'utf8');
const start = js.indexOf('/*__ISLAND_START__*/'), end = js.indexOf('/*__ISLAND_END__*/');
if (start < 0 || end < start) { console.error('island-selftest: explorer.js has lost its __ISLAND_START__ … __ISLAND_END__ block'); process.exit(1); }
const ctx = vm.createContext({ atob, TextDecoder, Uint8Array, Uint16Array, Int32Array, Error, parseInt, Math });
vm.runInContext(js.slice(start, end) + '\n;globalThis.inflateRaw=inflateRaw; globalThis.atlasIslandText=atlasIslandText;', ctx);
const { inflateRaw, atlasIslandText } = ctx;

let failed = 0, passed = 0;
const ok = (name, cond, detail) => { if (cond) passed++; else { failed++; console.log('  FAIL  ' + name + (detail !== undefined ? '  ' + JSON.stringify(detail) : '')); } };
const same = (a, b) => a.length === b.length && Buffer.from(a.buffer, a.byteOffset, a.length).equals(b);
const throws = f => { try { f(); return false; } catch (e) { return true; } };

// deterministic pseudo-random bytes, so a failure reproduces
let seed = 42;
const rnd = () => (seed = (Math.imul(seed, 1103515245) + 12345) >>> 0) / 4294967296;   // imul: a float product would lose its low bits and cycle
const randomBytes = n => Buffer.from(Array.from({ length: n }, () => Math.floor(rnd() * 256)));
const words = ['process', 'case', 'form', 'dataObject', 'liquibase', 'VARCHAR(255)', '"id":', '"type":', 'DEMO-TABLE_', '{', '}', ',', ' '];
// [noise] is the share of tokens followed by a random number: 0.1 compresses like a model's JSON, more like its ids
const jsonish = (n, noise = 0.1) => { let s = ''; while (s.length < n) s += words[Math.floor(rnd() * words.length)] + (rnd() < noise ? Math.floor(rnd() * 1e9).toString(36) : ''); return Buffer.from(s.slice(0, n)); };

const inputs = {
  empty: Buffer.alloc(0),
  oneByte: Buffer.from('a'),
  short: Buffer.from('{"project":"DEMO-app","nodes":[]}'),
  text: Buffer.from('Überprüfung ✓ 数据对象 — 😀 '.repeat(40)),
  random64k: randomBytes(64 * 1024),              // incompressible: stored blocks
  runs: Buffer.alloc(200000, 0x41),                // one long match after another, distance 1
  jsonish: jsonish(600000),                        // dynamic blocks, long distances
};
const variants = [
  ['level 0', { level: 0 }], ['level 1', { level: 1 }], ['level 6', { level: 6 }], ['level 9', { level: 9 }],
  ['fixed Huffman', { strategy: zlib.constants.Z_FIXED }], ['Huffman only', { strategy: zlib.constants.Z_HUFFMAN_ONLY }],
  ['run-length', { strategy: zlib.constants.Z_RLE }], ['small window', { windowBits: 9, level: 9 }],
];
for (const [name, bytes] of Object.entries(inputs)) {
  for (const [vname, opts] of variants) {
    const z = zlib.deflateRawSync(bytes, opts);
    let got;
    try { got = inflateRaw(new Uint8Array(z), bytes.length); } catch (e) { got = e; }
    ok(`${name}, ${vname}`, got instanceof Uint8Array && same(got, bytes), got instanceof Error ? got.message : undefined);
    // and without the size: the output grows as it goes
    try { got = inflateRaw(new Uint8Array(z), 0); } catch (e) { got = e; }
    ok(`${name}, ${vname}, size unknown`, got instanceof Uint8Array && same(got, bytes), got instanceof Error ? got.message : undefined);
  }
}

// a stream zlib flushes mid-way has an empty stored block and several blocks of mixed types
{
  const bytes = jsonish(120000);
  const d = zlib.createDeflateRaw({ level: 6 });
  const chunks = [];
  d.on('data', c => chunks.push(c));
  await new Promise(res => {
    d.write(bytes.subarray(0, 40000)); d.flush(zlib.constants.Z_SYNC_FLUSH, () => {
      d.params(0, zlib.constants.Z_DEFAULT_STRATEGY, () => {
        d.write(bytes.subarray(40000, 80000)); d.flush(zlib.constants.Z_FULL_FLUSH, () => {
          d.params(9, zlib.constants.Z_FIXED, () => { d.end(bytes.subarray(80000)); d.on('end', res); });
        });
      });
    });
  });
  const got = inflateRaw(new Uint8Array(Buffer.concat(chunks)), bytes.length);
  ok('flushed stream: empty stored block, level change, fixed blocks', same(got, bytes));
}

// what the page reads: the element's attributes decide
{
  const json = JSON.stringify({ project: 'DEMO-app', label: 'Prüfung ✓', nodes: [{ id: 'form:DEMO-F1' }] });
  const plain = { getAttribute: () => null, textContent: json };
  ok('a plain island is read as it is', atlasIslandText(plain) === json);
  const bytes = Buffer.from(json, 'utf8');
  const b64 = zlib.deflateRawSync(bytes).toString('base64');
  const attrs = { 'data-encoding': 'deflate-base64', 'data-size': String(bytes.length) };
  const deflated = { getAttribute: k => attrs[k] ?? null, textContent: '\n' + b64 + '\n' };
  ok('a deflated island reads back as the same JSON, multi-byte text included', atlasIslandText(deflated) === json);
  const wrongSize = { getAttribute: k => (k === 'data-size' ? String(bytes.length + 1) : attrs[k] ?? null), textContent: b64 };
  ok('an island whose size does not match throws', throws(() => atlasIslandText(wrongSize)));
}

// malformed input throws rather than returning half a page
{
  const bytes = jsonish(50000), z = zlib.deflateRawSync(bytes);
  ok('a truncated island throws', throws(() => inflateRaw(new Uint8Array(z.subarray(0, z.length >> 1)), bytes.length)));
  ok('a reserved block type throws', throws(() => inflateRaw(new Uint8Array([0x07, 0x00]), 0)));
  const stored = zlib.deflateRawSync(Buffer.from('DEMO'), { level: 0 });
  const broken = Buffer.from(stored); broken[3] ^= 0xff;   // NLEN no longer the complement of LEN
  ok('a stored block with a broken length throws', throws(() => inflateRaw(new Uint8Array(broken), 0)));
}

// the time a large project's island takes
{
  // compresses about 2x where a real island does 10x: more literals to decode, so the time is an upper bound
  const bytes = jsonish(20 * 1024 * 1024, 0.6);
  const z = new Uint8Array(zlib.deflateRawSync(bytes, { level: 6 }));
  inflateRaw(z, bytes.length);                     // warm up, as the page's one call is not
  const t = process.hrtime.bigint();
  const got = inflateRaw(z, bytes.length);
  const ms = Number(process.hrtime.bigint() - t) / 1e6;
  ok('a 20 MB island inflates correctly', same(got, bytes));
  ok(`a 20 MB island inflates in under a second (${ms.toFixed(0)} ms)`, ms < 1000, ms);
  console.log(`  20 MB island: ${(z.length / 1e6).toFixed(1)} MB deflated, inflated in ${ms.toFixed(0)} ms`);
}

if (failed) { console.log(`island-selftest: ${failed} of ${failed + passed} checks FAILED`); process.exit(1); }
console.log(`island-selftest: all ${passed} checks passed`);
