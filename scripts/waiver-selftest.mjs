#!/usr/bin/env node
/**
 * Parity check for the waivers.json writer.
 *
 * Two writers exist for that file: Waivers.serialize in :core and waiverSerialize in the explorer
 * (core/src/main/resources/frontend/explorer.js). The moment they disagree on a key, a default or the
 * sort order, the file churns in every diff and a reviewer stops reading it. This script lifts the
 * browser writer out of its __WAIVER_CORE_START__ … __WAIVER_CORE_END__ block — written to be
 * free of DOM and page globals for exactly this reason — and prints what it writes for a given set, so
 * WaiverWriterParityTest can compare the bytes with the Kotlin writer's.
 *
 * Usage:  node scripts/waiver-selftest.mjs <input.json>
 *   input.json = {atlasVersion, createdWith?, rules: [{check, node, element?, subject?, reason, by?, at?, until?}],
 *                 notes: [{node, check?, element?, subject?, text, importance?, by?, at?}]}
 * Without an argument a small built-in set is written, for a look by eye.
 */
import fs from 'fs';
import path from 'path';
import url from 'url';

const HERE = path.dirname(url.fileURLToPath(import.meta.url));
const JS_PATH = path.join(HERE, '..', 'core', 'src', 'main', 'resources', 'frontend', 'explorer.js');

const source = fs.readFileSync(JS_PATH, 'utf8');
const START = '/*__WAIVER_CORE_START__*/', END = '/*__WAIVER_CORE_END__*/';
const a = source.indexOf(START), b = source.indexOf(END);
if (a < 0 || b < 0 || b < a) {
  console.error(`waiver-selftest: sentinels not found in ${JS_PATH}.\n` +
    'The writer must stay wrapped in the __WAIVER_CORE_START__ … __WAIVER_CORE_END__ sentinels so it can be tested outside the browser.');
  process.exit(2);
}
const { waiverSerialize } = new Function(`"use strict";${source.slice(a + START.length, b)};return { waiverSerialize };`)();

const FIXTURE = {
  atlasVersion: '0.0.0', createdWith: '0.0.0',
  rules: [{ check: 'unusedForms', node: 'form:DEMO-F014', reason: 'kept for the pilot', by: 'team-orders', at: '2026-09-11' }],
  notes: [{ node: 'form:DEMO-F014', text: 'replace with the new intake form in Q3', importance: 'high' }],
};
const input = process.argv[2] ? JSON.parse(fs.readFileSync(process.argv[2], 'utf8')) : FIXTURE;
process.stdout.write(waiverSerialize(input.rules || [], input.notes || [], input.atlasVersion || '', input.createdWith));
