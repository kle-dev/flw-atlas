// If anything in the synchronous boot below throws — a truncated data island, a malformed node — the
// loading overlay would sit there forever and say nothing. The first uncaught error before boot
// completes replaces the overlay's card with the error and what to do about it.
let _booted=false;
window.addEventListener('error', e=>{ if(!_booted) bootFailed(e.error||e.message); });
function bootFailed(err){
  const card=document.querySelector('#atlas-boot .boot-card'); if(!card) return;
  const text=String((err&&err.stack)||err||'unknown error');
  card.innerHTML='<div class="boot-fail"><b>This explorer could not start.</b>'+
    '<pre>'+text.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))+'</pre>'+
    'Regenerate the page with Atlas (Tools → Flowable Atlas → Generate, or the CLI). If it fails again, the text above is what to report.</div>';
}
// Data arrives as a JSON island (<script type="application/json" id="atlas-data">):
// JSON.parse is faster than a JS literal for large payloads and needs no JS escaping.
const DATA = JSON.parse(document.getElementById('atlas-data').textContent);
const nodes = DATA.nodes, edges = DATA.edges;
const byId = new Map(nodes.map(n => [n.id, n]));
// Node-type labels. Wording follows Flowable Design's own `modelType.*` strings so a term you read here
// is the term you look for in Design — "Decision tables", not "Decisions"; "AI agents", not "Agents".
const TM = {
  app:['Apps','Models'],process:['Processes','Models'],case:['Cases','Models'],
  decision:['Decision tables','Models'],form:['Forms','Models'],page:['Pages','Models'],
  dataObject:['Data objects','Models'],dataDictionary:['Data dictionaries','Models'],
  masterData:['Master data','Models'],
  service:['Services','Integration'],serviceOperation:['Service operations','Integration'],agent:['AI agents','Integration'],
  channel:['Channels','Integration'],event:['Events','Integration'],knowledgeBase:['Knowledge bases','Integration'],
  signal:['Signals','Integration'],message:['Messages','Integration'],error:['Errors','Integration'],
  escalation:['Escalations','Integration'],topic:['External Worker topics','Integration'],
  endpoint:['REST endpoints','Code'],java:['Java classes','Code'],method:['Java methods','Code'],liquibase:['Liquibase changelogs','Code'],
  action:['Actions','Integration'],bot:['Bots','Integration'],
  query:['Queries','Other'],template:['Templates','Other'],sequence:['Sequences','Other'],
  document:['Content','Other'],variableExtractor:['Variable extractors','Other'],
  sla:['SLAs','Other'],dashboardComponent:['Dashboard components','Other'],
  palette:['Palettes','Other'],
  securityPolicy:['Security policies','Access'],group:['User groups','Access'],
  variable:['Variables','Variables'],
  expression:['Backend expressions ${ }','Expressions'],binding:['Frontend bindings {{ }}','Expressions'],
  string:['String literals','Expressions'],customFunction:['Custom functions 🧩','Expressions'],
  external:['External / library','Other'],
};

// ---------- node-type icons ----------
// One stroke icon per node type (Lucide, ISC — see THIRD-PARTY-NOTICES.md), keyed like the --c-* palette
// so the icon and its colour come from the same name. Values are the inner markup of a 24×24 icon, not
// a whole <svg>: the same string is wrapped for HTML by typeIcon() and dropped into the neighborhood
// diagram as a <g>. Bare coloured dots used to stand for forty types whose hues nobody can tell apart.
//
// Lucide Icons — ISC License. Copyright (c) 2026 Lucide Icons and Contributors (https://lucide.dev).
// Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is
// hereby granted, provided that the above copyright notice and this permission notice appear in all
// copies. THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS
// SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR
// BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER
// RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
// TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
// circle, code, database, radio, search and table-2 derive from Feather — MIT License, Copyright (c)
// 2013-present Cole Bemis; the full text is in THIRD-PARTY-NOTICES.md.
const TYPE_ICONS={
  app:'<rect width="7" height="7" x="3" y="3" rx="1"/><rect width="7" height="7" x="14" y="3" rx="1"/><rect width="7" height="7" x="14" y="14" rx="1"/><rect width="7" height="7" x="3" y="14" rx="1"/>',
  process:'<rect width="8" height="8" x="3" y="3" rx="2"/><path d="M7 11v4a2 2 0 0 0 2 2h4"/><rect width="8" height="8" x="13" y="13" rx="2"/>',
  case:'<path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"/><path d="M8 10v4"/><path d="M12 10v2"/><path d="M16 10v6"/>',
  decision:'<path d="M9 3H5a2 2 0 0 0-2 2v4m6-6h10a2 2 0 0 1 2 2v4M9 3v18m0 0h10a2 2 0 0 0 2-2V9M9 21H5a2 2 0 0 1-2-2V9m0 0h18"/>',
  form:'<rect width="8" height="4" x="8" y="2" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><path d="M12 11h4"/><path d="M12 16h4"/><path d="M8 11h.01"/><path d="M8 16h.01"/>',
  page:'<rect width="18" height="7" x="3" y="3" rx="1"/><rect width="9" height="7" x="3" y="14" rx="1"/><rect width="5" height="7" x="16" y="14" rx="1"/>',
  dataObject:'<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5V19A9 3 0 0 0 21 19V5"/><path d="M3 12A9 3 0 0 0 21 12"/>',
  dataDictionary:'<path d="M12 5v16"/><path d="M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z"/>',
  masterData:'<rect width="20" height="5" x="2" y="3" rx="1"/><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"/><path d="M10 12h4"/>',
  service:'<path d="M12 22v-5"/><path d="M15 8V2"/><path d="M17 8a1 1 0 0 1 1 1v4a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1z"/><path d="M9 8V2"/>',
  serviceOperation:'<path d="m16 3 4 4-4 4"/><path d="M20 7H4"/><path d="m8 21-4-4 4-4"/><path d="M4 17h16"/>',
  agent:'<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
  channel:'<path d="M16.247 7.761a6 6 0 0 1 0 8.478"/><path d="M19.075 4.933a10 10 0 0 1 0 14.134"/><path d="M4.925 19.067a10 10 0 0 1 0-14.134"/><path d="M7.753 16.239a6 6 0 0 1 0-8.478"/><circle cx="12" cy="12" r="2"/>',
  event:'<path d="M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z"/>',
  knowledgeBase:'<path d="m16 6 4 14"/><path d="M12 6v14"/><path d="M8 8v12"/><path d="M4 4v16"/>',
  signal:'<path d="M4.9 16.1C1 12.2 1 5.8 4.9 1.9"/><path d="M7.8 4.7a6.14 6.14 0 0 0-.8 7.5"/><circle cx="12" cy="9" r="2"/><path d="M16.2 4.8c2 2 2.26 5.11.8 7.47"/><path d="M19.1 1.9a9.96 9.96 0 0 1 0 14.1"/><path d="M9.5 18h5"/><path d="m8 22 4-11 4 11"/>',
  message:'<path d="m22 7-8.991 5.727a2 2 0 0 1-2.009 0L2 7"/><rect x="2" y="4" width="20" height="16" rx="2"/>',
  error:'<path d="M12 16h.01"/><path d="M12 8v4"/><path d="M15.312 2a2 2 0 0 1 1.414.586l4.688 4.688A2 2 0 0 1 22 8.688v6.624a2 2 0 0 1-.586 1.414l-4.688 4.688a2 2 0 0 1-1.414.586H8.688a2 2 0 0 1-1.414-.586l-4.688-4.688A2 2 0 0 1 2 15.312V8.688a2 2 0 0 1 .586-1.414l4.688-4.688A2 2 0 0 1 8.688 2z"/>',
  escalation:'<circle cx="12" cy="12" r="10"/><path d="m16 12-4-4-4 4"/><path d="M12 16V8"/>',
  topic:'<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
  endpoint:'<circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"/><path d="M2 12h20"/>',
  java:'<path d="M10 2v2"/><path d="M14 2v2"/><path d="M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1"/><path d="M6 2v2"/>',
  method:'<rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><path d="M9 17c2 0 2.8-1 2.8-2.8V10c0-2 1-3.3 3.2-3"/><path d="M9 11.2h5.7"/>',
  liquibase:'<path d="M15 3v18"/><rect width="18" height="18" x="3" y="3" rx="2"/><path d="M21 9H3"/><path d="M21 15H3"/>',
  action:'<path d="M14 4.1 12 6"/><path d="m5.1 8-2.9-.8"/><path d="m6 12-1.9 2"/><path d="M7.2 2.2 8 5.1"/><path d="M9.037 9.69a.498.498 0 0 1 .653-.653l11 4.5a.5.5 0 0 1-.074.949l-4.349 1.041a1 1 0 0 0-.74.739l-1.04 4.35a.5.5 0 0 1-.95.074z"/>',
  bot:'<path d="M12 6V2H8"/><path d="M15 11v2"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="M20 16a2 2 0 0 1-2 2H8.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 4 20.286V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z"/><path d="M9 11v2"/>',
  query:'<path d="m21 21-4.34-4.34"/><circle cx="11" cy="11" r="8"/>',
  template:'<path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/>',
  sequence:'<path d="M11 5h10"/><path d="M11 12h10"/><path d="M11 19h10"/><path d="M4 4h1v5"/><path d="M4 9h2"/><path d="M6.5 20H3.4c0-1 2.6-1.925 2.6-3.5a1.5 1.5 0 0 0-2.6-1.02"/>',
  document:'<path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5"/>',
  variableExtractor:'<path d="M10 20a1 1 0 0 0 .553.895l2 1A1 1 0 0 0 14 21v-7a2 2 0 0 1 .517-1.341L21.74 4.67A1 1 0 0 0 21 3H3a1 1 0 0 0-.742 1.67l7.225 7.989A2 2 0 0 1 10 14z"/>',
  sla:'<line x1="10" x2="14" y1="2" y2="2"/><line x1="12" x2="15" y1="14" y2="11"/><circle cx="12" cy="14" r="8"/>',
  dashboardComponent:'<path d="M3 3v16a2 2 0 0 0 2 2h16"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
  palette:'<path d="M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z"/><circle cx="13.5" cy="6.5" r=".5" fill="currentColor"/><circle cx="17.5" cy="10.5" r=".5" fill="currentColor"/><circle cx="6.5" cy="12.5" r=".5" fill="currentColor"/><circle cx="8.5" cy="7.5" r=".5" fill="currentColor"/>',
  securityPolicy:'<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
  group:'<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><path d="M16 3.128a4 4 0 0 1 0 7.744"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><circle cx="9" cy="7" r="4"/>',
  variable:'<path d="M8 21s-4-3-4-9 4-9 4-9"/><path d="M16 3s4 3 4 9-4 9-4 9"/><line x1="15" x2="9" y1="9" y2="15"/><line x1="9" x2="15" y1="9" y2="15"/>',
  expression:'<path d="m16 18 6-6-6-6"/><path d="m8 6-6 6 6 6"/>',
  binding:'<path d="M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5c0 1.1.9 2 2 2h1"/><path d="M16 21h1a2 2 0 0 0 2-2v-5c0-1.1.9-2 2-2a2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1"/>',
  string:'<path d="M16 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z"/><path d="M5 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z"/>',
  customFunction:'<path d="M15.39 4.39a1 1 0 0 0 1.68-.474 2.5 2.5 0 1 1 3.014 3.015 1 1 0 0 0-.474 1.68l1.683 1.682a2.414 2.414 0 0 1 0 3.414L19.61 15.39a1 1 0 0 1-1.68-.474 2.5 2.5 0 1 0-3.014 3.015 1 1 0 0 1 .474 1.68l-1.683 1.682a2.414 2.414 0 0 1-3.414 0L8.61 19.61a1 1 0 0 0-1.68.474 2.5 2.5 0 1 1-3.014-3.015 1 1 0 0 0 .474-1.68l-1.683-1.682a2.414 2.414 0 0 1 0-3.414L4.39 8.61a1 1 0 0 1 1.68.474 2.5 2.5 0 1 0 3.014-3.015 1 1 0 0 1-.474-1.68l1.683-1.682a2.414 2.414 0 0 1 3.414 0z"/>',
  external:'<path d="M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z"/><path d="M12 22V12"/><polyline points="3.29 7 12 12 20.71 7"/><path d="m7.5 4.27 9 5.15"/>',
  invalidExpr:'<circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/>',
  suspectExpr:'<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
  overview:'<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
  scripts:'<path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5"/><path d="M10 12.5 8 15l2 2.5"/><path d="m14 12.5 2 2.5-2 2.5"/>',
  checks:'<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
  schema:'<path d="M12 3v18"/><rect width="18" height="18" x="3" y="3" rx="2"/><path d="M3 9h18"/><path d="M3 15h18"/>',
  tree:'<path d="M3 5h6v4H3z"/><path d="M15 3h6v4h-6z"/><path d="M15 15h6v4h-6z"/><path d="M6 9v8h9"/><path d="M15 5H9"/>',
  _:'<circle cx="12" cy="12" r="10"/>',
};
// The icon carries no width/height: CSS sizes .ti in --ui-scale units, so A−/A+ scales icons with
// their labels. Colour stays a var() reference like color(), so a theme switch restyles without re-render.
// Chrome glyphs (back, expand all, copy link, disclosure chevron) — also Lucide, also inline SVG: the
// characters they replace (← ⇕ 🔗 ▸) are outside the embedded Geist subset and rendered in the system face.
const UI_ICONS={
  back:'<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
  expand:'<path d="m7 15 5 5 5-5"/><path d="m7 9 5-5 5 5"/>',
  link:'<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
  chevron:'<path d="m6 9 6 6 6-6"/>',
  check:'<path d="M20 6 9 17l-5-5"/>',
};
function uiIcon(name){
  return '<svg class="ui ui-'+name+'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"'+
    ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+(UI_ICONS[name]||'')+'</svg>';
}
function typeIcon(t, o){
  o=o||{};
  const body=TYPE_ICONS[t]||TYPE_ICONS._;
  return '<svg class="ti'+(o.cls?' '+o.cls:'')+'" viewBox="0 0 24 24" fill="none" stroke="currentColor"'+
    ' stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"'+
    ' style="color:'+(o.color||color(t))+'">'+body+'</svg>';
}
// external nodes split three ways (Flowable API / navigation route / library), exactly as nodeColor() does.
function nodeIcon(n, o){
  const d=(n&&n.data)||{};
  const t=n.type==='external'?(d.flowableApi?'endpoint':d.route?'page':'external'):n.type;
  return typeIcon(t, Object.assign({color:nodeColor(n)}, o||{}));
}

// ---------- Flowable Design vocabulary ----------
// Atlas's internal names (`ruleTask-decision`, `sendPayloadMapping`, `workAction`) are precise but only
// mean something if you built Atlas. This table gives each one the word Design uses plus a sentence
// explaining it, shown as a tooltip. Namespaces: `type:` node kinds, `el:` model elements,
// `kind:` parameter mappings, `rel:` relationships. `term()` falls back to the raw key, so an entry that
// is missing here degrades to today's behaviour instead of disappearing.
const DESIGN_TERMS = {
  // --- node kinds: the hint, the label lives in TM ---
  'type:decision': [null, 'A DMN decision table — inputs, outputs and the rules between them.'],
  'type:agent': [null, 'An AI agent model: the LLM, its instructions, tools and operations.'],
  'type:service': [null, 'A Service Registry entry — a reusable REST, MCP, database, script or expression integration with named operations.'],
  'type:dataObject': [null, 'A structured business object, backed by a service or by master data.'],
  'type:action': [null, 'An action that a user or the system can trigger on a scoped object; it is dispatched to a bot.'],
  'type:bot': [null, 'The BotService that performs an action at runtime, looked up by its bot key.'],
  'type:document': [null, 'A content/document model.'],
  'type:page': [null, 'A FlowApp page — the same component model as a form, but for navigation targets.'],
  'type:securityPolicy': [null, 'Permission definitions that gate what a role may see and do.'],
  'type:sla': [null, 'Service-level thresholds attached to a process, case or task.'],
  'type:sequence': [null, 'A number sequence used to generate business keys and references.'],
  'type:variableExtractor': [null, 'Extracts variables out of a payload so they can be indexed and queried.'],
  'type:knowledgeBase': [null, 'The document collection an AI agent retrieves from.'],
  'type:masterData': [null, 'A managed reference list — countries, currencies, categories.'],
  'type:dataDictionary': [null, 'Reusable typed structures that data objects, services and forms share.'],
  'type:serviceOperation': [null, 'One named operation of a service, with its declared input and output parameters.'],
  'type:topic': [null, 'The queue name an External Worker task publishes to.'],
  // --- model elements (elementType / elementSubType of a parameter group) ---
  'el:userTask': ['User task', 'A task a person completes, usually through a form.'],
  'el:humanTask': ['Human task', 'The CMMN equivalent of a user task.'],
  'el:serviceTask': ['Service task', 'Runs logic automatically — Java, an expression or one of the Flowable task types.'],
  'el:serviceTask/service-registry': ['Service registry task', 'Calls an operation of a Service Registry entry.'],
  'el:serviceTask/agent': ['AI Agent', 'Hands the mapped values to an AI agent model and maps its answer back.'],
  'el:serviceTask/http': ['HTTP task', 'Calls a URL directly, configured through field injections.'],
  'el:serviceTask/dmn': ['Decision task', 'Evaluates a decision table; the mapping is derived from the table itself.'],
  'el:serviceTask/mail': ['Email task', 'Sends an email, optionally rendered from a template model.'],
  'el:serviceTask/data-object': ['Data object task', 'Creates, looks up, updates, deletes or searches a data object.'],
  'el:serviceTask/init-variables': ['Initialize variables', 'Declares variables and their initial values.'],
  'el:serviceTask/send-event': ['Send event task', 'Publishes an event onto a channel.'],
  'el:serviceTask/external-worker': ['External Worker task', 'Parks the work on a topic for an external worker to pick up.'],
  'el:serviceTask/case': ['Case task', 'Starts a case from a process.'],
  'el:serviceTask/audit': ['Audit', 'Writes an audit entry.'],
  'el:serviceTask/script': ['Script task', 'Runs an inline script and can store its result in a variable.'],
  'el:serviceTask/generate-document': ['Generate Document', 'Renders a document from a template model.'],
  'el:scriptTask': ['Script task', 'Runs an inline script and can store its result in a variable.'],
  'el:sendTask': ['Send task', 'Sends a message.'],
  'el:manualTask': ['Manual task', 'Work done outside the engine — recorded, not executed.'],
  'el:subProcess': ['Sub-process', 'A group of elements that runs inside the parent instance.'],
  'el:transaction': ['Transaction', 'A sub-process whose work is undone by compensation if it fails.'],
  'el:adhocSubProcess': ['Ad-hoc sub-process', 'Contained activities run in any order, chosen at runtime.'],
  'el:exclusiveGateway': ['Exclusive gateway', 'Takes exactly one outgoing flow — the first condition that is true.'],
  'el:parallelGateway': ['Parallel gateway', 'Splits into all outgoing flows and joins by waiting for all incoming ones.'],
  'el:inclusiveGateway': ['Inclusive gateway', 'Takes every outgoing flow whose condition is true.'],
  'el:eventBasedGateway': ['Event gateway', 'Waits for whichever of the following events happens first.'],
  'el:complexGateway': ['Complex gateway', 'Custom split/join behaviour.'],
  'el:sequenceFlow': ['Sequence flow', 'The arrow that orders two elements; a condition makes it optional.'],
  'el:callActivity': ['Call activity', 'Invokes another process; in and out parameters move variables between the two.'],
  'el:processTask': ['Process task', 'Starts a process from a case.'],
  'el:caseTask': ['Case task', 'Starts a sub-case from a case.'],
  'el:startEvent': ['Start event', 'Where an instance begins.'],
  'el:endEvent': ['End event', 'Where a path finishes.'],
  'el:boundaryEvent': ['Boundary event', 'Attached to an activity and triggered while it runs.'],
  'el:receiveTask': ['Receive task', 'Waits for a message or an event.'],
  'el:workAction': ['Action button', 'A button on a form or page that invokes an action.'],
  'el:restButton': ['REST button', 'A button that calls a URL directly.'],
  'el:workInvokeService': ['Service button', 'A button that calls a Service Registry operation.'],
  'el:workAgentButton': ['Agent button', 'A button that asks an AI agent.'],
  'el:scriptButton': ['Expression button', 'Evaluates an expression and stores the result in its own binding.'],
  'el:outcomeButton': ['Outcome button', 'Completes the task with an outcome.'],
  'el:linkButton': ['Link button', 'Opens a URL; it calls nothing.'],
  'el:createInstanceButton': ['Create-instance button', 'Starts a process or case.'],
  'el:workUserEventListenerButton': ['User event button', 'Triggers a user event listener of the case.'],
  'el:actionBot': ['Action bot', 'The bot an action is dispatched to at runtime.'],
  'el:task': ['Task', 'A plain task; its flowable:type decides what it does.'],
  'el:decisionTask': ['Decision task', 'Evaluates a decision table from a case.'],
  'el:humanTaskWithService': ['Human task with service', 'A human task combined with a service call.'],
  'el:milestone': ['Milestone', 'A named point the case reaches when its conditions are met.'],
  'el:entryCriterion': ['Entry criterion', 'The plan item becomes available once this sentry is satisfied.'],
  'el:exitCriterion': ['Exit criterion', 'The plan item (or stage) terminates once this sentry is satisfied.'],
  'el:stage': ['Stage', 'A group of plan items that activates and completes together.'],
  'el:planFragment': ['Plan fragment', 'A reusable group of plan items.'],
  'el:timerEventListener': ['Timer', 'Fires on a schedule or after a duration.'],
  'el:userEventListener': ['User event listener', 'Triggered manually by a user.'],
  'el:signalEventListener': ['Signal listener', 'Waits for a signal by name.'],
  'el:variableEventListener': ['Variable listener', 'Fires when a variable changes.'],
  'el:intermediateCatchEvent': ['Intermediate catch event', 'Waits mid-flow for a timer, message or signal.'],
  'el:intermediateThrowEvent': ['Intermediate throw event', 'Publishes a signal/message mid-flow.'],
  'el:eventListener': ['Event listener', 'A case element that waits for something — a timer, a user, a signal or a variable change.'],
  'el:casePlanModel': ['Case plan model', 'The root stage of a case: everything the case can do lives inside it.'],
  // --- listeners: what runs alongside an element rather than as one ---
  'el:executionListener': ['Execution listener', 'Runs when the element starts or ends — a Java class, an expression or a script.'],
  'el:taskListener': ['Task listener', 'Runs on a user task’s lifecycle: create, assignment, complete or delete.'],
  'el:planItemLifecycleListener': ['Lifecycle listener', 'Runs when a plan item changes state — available, active, completed, terminated.'],
  // --- data-source kinds on a form/page component ---
  'kind-ds:dataObject': ['Data object', 'Rows or options come from a data object lookup.'],
  'kind-ds:service': ['Service', 'Rows or options come from a Service Registry operation.'],
  'kind-ds:rest': ['REST', 'Rows or options come from a URL.'],
  // --- parameter mapping kinds ---
  'kind:in': ['In parameter', 'Copies a variable from the calling scope into the called one.'],
  'kind:out': ['Out parameter', 'Copies a variable from the called scope back into the caller.'],
  'kind:inputParameter': ['Input parameter', 'A value handed to the call, named as the callee declares it.'],
  'kind:outputParameter': ['Output parameter', 'A value from the response, stored in a variable.'],
  'kind:errorOutputParameter': ['Error output parameter', 'Mapped only when the call fails; the regular output mapping is then skipped.'],
  'kind:outputVariableName': ['Output variable', 'The variable the whole result is stored in.'],
  'kind:resultVariable': ['Result variable', 'The variable the task writes its result to.'],
  'kind:variableMapping': ['Variable', 'A variable declared with its initial value.'],
  'kind:eventInParameter': ['Event payload (out)', 'Fills a field of the event payload being published.'],
  'kind:eventOutParameter': ['Event payload (in)', 'Reads a field of the received event payload into a variable.'],
  'kind:sendPayloadMapping': ['Send payload map', 'The values handed to the call — a script-based action reads them with flw.getInput(…).'],
  'kind:responsePayloadMapping': ['Store response attributes', 'Writes parts of the response back into the form; a script action sets them with flw.setOutput(…).'],
  'kind:errorResponsePayloadMapping': ['Error response map', 'Mapped instead of the response when the call fails.'],
  'kind:dataObjectDataTableCreatePayloadMapping': ['Create payload map', 'The values a data table sends when creating a row.'],
  'kind:header': ['HTTP header', 'Sent as a request header rather than in the body.'],
  // Which payload side a button flag puts in force. The runtime picks one: a full-payload/full-response
  // flag wins over the explicit map, and the map it beats is then never read.
  'pmode:full-payload': ['the whole form payload', 'The button posts the entire form payload, so its send payload map is ignored.'],
  'pmode:full-scope': ['the whole scope', 'The button posts the scope it sits in (a subform row, a list item), so its send payload map is ignored.'],
  'pmode:full-response': ['the whole response', 'Every attribute of the response is written back into the form payload, so the response map is ignored.'],
  'pmode:full-response-in-scope': ['the whole response, into the scope', 'Every attribute of the response is written into the surrounding scope rather than the form payload, so the response map is ignored.'],
  // A component's state when the model settles it outright, rather than leaving it to a condition.
  'gate:hidden': ['hidden', 'visible: false — this component never renders. A hidden button that auto-executes is a worker, not something anyone presses.'],
  'gate:disabled': ['disabled', 'enabled: false — it renders but cannot be used, unless it also runs while disabled.'],
  'gate:not submitted': ['not submitted', 'ignore: true — its value is computed but left out of the payload.'],
  'kind:signalVariable': ['Signal variable', 'Copied into the signalled instance as a variable.'],
  'kind:config': ['Bot configuration', 'A bot-specific setting from the action model, not a variable.'],
  'kind:flwScript': ['Script payload', 'Read or written by the action script through flw.getInput(…) / flw.setOutput(…).'],
  'kind:field': ['Field injection', 'Static configuration on the task rather than a variable mapping.'],
  // --- relationships ---
  'rel:contains': ['App contains', 'The app packages this model for deployment.'],
  'rel:callActivity': ['Call activity → process', 'A call activity in this process invokes that process.'],
  'rel:processTask': ['Process task → process', 'A process task in this case starts that process.'],
  'rel:caseTask': ['Case task → case', 'A case task starts that case.'],
  'rel:decisionTask': ['Decision task → decision table', 'A decision task evaluates that decision table.'],
  'rel:ruleTask-decision': ['Decision task → decision table', 'A decision task evaluates that decision table.'],
  'rel:serviceTask-class': ['Service task → Java class', 'The task runs that class as a JavaDelegate.'],
  'rel:serviceTask-delegate': ['Service task → bean', 'The task runs that Spring bean via a delegate expression.'],
  'rel:task-delegate': ['Task → bean', 'The case task runs that Spring bean via a delegate expression.'],
  'rel:serviceTask-expression': ['Service task → bean', 'The task calls a method on that Spring bean via an expression.'],
  'rel:task-expression': ['Task → bean', 'The case task calls a method on that Spring bean via an expression.'],
  'rel:serviceMapping': ['Service registry task → service', 'The task calls an operation of that service.'],
  'rel:dataObjectMapping': ['Data object task → data object', 'The task creates, reads, updates, deletes or searches that data object.'],
  'rel:agentMapping': ['AI Agent → agent model', 'The task hands its input to that agent model.'],
  'rel:userTask-form': ['User task → form', 'That form is rendered when the task is worked on.'],
  'rel:humanTask-form': ['Human task → form', 'That form is rendered when the task is worked on.'],
  'rel:task-form': ['Task → form', 'That form is rendered for the task.'],
  'rel:start-form': ['Start form', 'That form is filled in before the instance starts.'],
  'rel:work-form': ['Work form', 'The form shown while working on the instance.'],
  'rel:casePage-form': ['Case page → form', 'A tab of the case page renders that form.'],
  'rel:task-form-mapping': ['Form key passed in', 'The form is chosen at runtime by an in-mapping onto formKey.'],
  'rel:subform': ['Contains subform', 'That form is embedded as a subform.'],
  'rel:outcome-form': ['Outcome → form', 'Choosing that outcome opens the form.'],
  'rel:field-dataObject': ['Field → data object', 'A component reads its options or rows from that data object.'],
  'rel:field-service': ['Field → service', 'A component reads its options or rows from that service operation.'],
  'rel:field-agent': ['Field → agent model', 'An agent button on this form asks that agent.'],
  'rel:triggers-action': ['Action button → action', 'A button on this form or page invokes that action.'],
  'rel:starts-process': ['Bot starts process', 'The action’s bot starts an instance of that process.'],
  'rel:starts-case': ['Bot starts case', 'The action’s bot starts an instance of that case.'],
  'rel:triggers-signal': ['Sends signal', 'The action signals a waiting instance by that signal name.'],
  'rel:sends-event': ['Publishes event', 'This model publishes that event onto a channel.'],
  'rel:receives-event': ['Consumes event', 'This model is triggered by, or waits for, that event.'],
  'rel:trigger-event': ['Triggered by event', 'The event that resumes a send-and-receive task.'],
  'rel:via-channel': ['Uses channel', 'Events travel over that channel.'],
  'rel:external-topic': ['External Worker topic', 'Work is parked on that topic for an external worker.'],
  'rel:queries-dataObject': ['Queries data object', 'A data-source URL queries that data object.'],
  'rel:runs-query': ['Runs query', 'A data source runs that query model.'],
  'rel:uses-sequence': ['Uses sequence', 'Business keys come from that number sequence.'],
  'rel:data-dictionary': ['Uses data dictionary', 'Types are taken from that data dictionary.'],
  'rel:typed-by-dictionary': ['Typed by data dictionary', 'A parameter’s type is defined in that data dictionary.'],
  'rel:backed-by-service': ['Backed by service', 'The data object reads and writes through that service.'],
  'rel:schema': ['Table schema', 'The Liquibase changelog that defines the physical table.'],
  'rel:serves': ['Serves endpoint', 'That controller method handles the endpoint.'],
  'rel:rest-call': ['Calls endpoint', 'A component or task calls that REST endpoint.'],
  'rel:bot': ['Dispatched to bot', 'The action is executed by that bot.'],
  'rel:action-form': ['Action → form', 'That form collects the action’s payload before it runs.'],
  'rel:assign': ['Assigned to', 'Who may work on it.'],
  'rel:start': ['May start', 'Who may start an instance.'],
  'rel:owner': ['Owner', 'Who owns the instance or task.'],
  'rel:watcher': ['Watcher', 'Who follows it without working on it.'],
  // data-object permissions, as the security policy spells them
  'rel:createInstances': ['May create', 'Who may create instances of that data object.'],
  'rel:queryInstances': ['May query', 'Who may search instances of that data object.'],
  'rel:updateInstances': ['May update', 'Who may change instances of that data object.'],
  'rel:deleteInstances': ['May delete', 'Who may delete instances of that data object.'],
  'rel:read': ['May read', 'Who may read it.'],
  'rel:query': ['May query', 'Who may search it.'],
  'rel:update': ['May update', 'Who may change it.'],
  'rel:open-app': ['May open app', 'Who may open the app.'],
  'rel:references': ['Code references key', 'A Java string literal equal to that model key.'],
  'rel:relates-to': ['Relates to', 'A field of this model points at that model.'],
  'rel:declared-in': ['Declared in', 'Where the method is declared.'],
  'rel:requires': ['Requires decision', 'This decision needs that decision’s result (DRD).'],
  'rel:contains-decision': ['Contains decision', 'The decision service bundles that decision table.'],
  'rel:knowledgeBase': ['Uses knowledge base', 'The agent retrieves from that document collection.'],
  'rel:tool': ['Uses tool', 'The agent may call that model as a tool.'],
  'rel:guardrail': ['Guardrail', 'That model checks the agent’s input or output.'],
  'rel:evaluator': ['Evaluator', 'That model scores the agent’s answers.'],
  'rel:message-template': ['Prompt template', 'The agent’s prompt is rendered from that template.'],
  'rel:documentAgent': ['Document agent', 'Documents are delegated to that agent.'],
  'rel:classifies-document': ['Classifies document', 'The agent files documents into that content model.'],
  'rel:agent-event': ['Agent event', 'The external agent communicates through that event.'],
  'rel:channel-event': ['Carries event', 'The channel delivers that event type.'],
  'rel:service-dataObject': ['Service → data object', 'The service declares that data object as its reference type.'],
  'rel:body-template': ['Body template', 'The operation’s request body is rendered from that template.'],
  'rel:queryModel': ['Runs query', 'That query model provides the rows.'],
  'rel:extracts-from': ['Extracts from', 'Variables are extracted from instances of that model.'],
  'rel:template-form': ['Template form', 'The template’s parameters are collected with that form.'],
  'rel:worker-topic': ['Polls topic', 'The Java worker subscribes to that External Worker topic.'],
  'rel:filters-by-group': ['Filters by group', 'The query restricts results to members of that group.'],
  'rel:navigates-to': ['Navigates to', 'A button or link opens that in-app route.'],
  'rel:calls': ['Calls method', 'An expression or task calls that Java method.'],
  'rel:uses': ['Uses class', 'The class depends on that class.'],
  'rel:throws-signal': ['Throws signal', 'Publishes that signal for others to catch.'],
  'rel:catches-signal': ['Catches signal', 'Waits for that signal.'],
  'rel:throws-message': ['Sends message', 'Sends that message.'],
  'rel:catches-message': ['Receives message', 'Waits for that message.'],
  'rel:throws-error': ['Throws error', 'Raises that error code.'],
  'rel:catches-error': ['Catches error', 'Handles that error code.'],
  'rel:throws-escalation': ['Throws escalation', 'Raises that escalation.'],
  'rel:catches-escalation': ['Catches escalation', 'Handles that escalation.'],
  'rel:sla-definition-key': ['SLA', 'That SLA model’s thresholds apply here.'],
  'rel:security-policy-model': ['Security policy', 'That policy gates what roles may see and do here.'],
  'rel:eventType': ['Event type', 'The model publishes or consumes that event.'],
  'rel:channelKey': ['Channel', 'Events travel over that channel.'],
  'rel:datatable-detail-form': ['Data table detail form', 'The expandable row detail renders that form.'],
  'rel:static-form': ['Static form', 'A case-view element renders that form.'],
  'rel:manual-start-form': ['Manual start form', 'Manually starting the plan item opens that form.'],
  'rel:static-decision': ['Static decision table', 'A case-view element evaluates that decision table.'],
  'rel:inbound-channel': ['Received on channel', 'The event arrives over that channel.'],
  'rel:outbound-channel': ['Sent on channel', 'The event is published over that channel.'],
  'rel:relates-to-service': ['Relates to service', 'A column relation joins to that service’s table.'],
  'rel:watch': ['May watch', 'Who is added as a watcher.'],
  'rel:participate': ['May participate', 'Who participates in the instance.'],
  'rel:trigger': ['May trigger', 'Who may trigger the event listener.'],
  'rel:manually-start': ['May start manually', 'Who may manually start the plan item.'],
  'rel:use': ['May use', 'Who may use it.'],
  'rel:view': ['May view', 'Who may view it.'],
  'rel:document-create-form': ['Document create form', 'Creating a document opens that form.'],
  'rel:document-edit-form': ['Document edit form', 'Editing a document opens that form.'],
  'rel:document-view-form': ['Document view form', 'Viewing a document opens that form.'],
  'rel:download': ['May download', 'Who may download it.'],
  'rel:escalation-starts': ['Escalation starts', 'Missing the SLA target starts that model.'],
  'rel:queries-process': ['Queries process', 'The query lists instances of that process.'],
  'rel:sla-of-process': ['SLA of process', 'The SLA applies to instances of that process.'],
  'rel:dataObjectDataTableCreateFormKey': ['Data table create form', 'Creating a row opens that form.'],
  'rel:dataObjectDataTableEditFormKey': ['Data table edit form', 'Editing a row opens that form.'],
  'rel:dataObjectDataTableViewFormKey': ['Data table view form', 'Viewing a row opens that form.'],
  // How a variable comes to be read or written. Each one is a construct a modeller recognises, because
  // "written but never read" is only actionable if it also says *where* the write is.
  'via:inParameter': ['In parameter', 'The caller maps a value into the called model under this name.'],
  'via:inParameterSource': ['In parameter (source)', 'The value handed to a called model is read from this variable.'],
  'via:outParameter': ['Out parameter', 'The called model maps a value back into this variable.'],
  'via:outParameterSource': ['Out parameter (source)', 'The value handed back is read from this variable of the called model.'],
  'via:resultVariable': ['Result variable', 'The task stores its result under this name.'],
  'via:outputVariableName': ['Output variable', 'The service or decision result lands in this variable.'],
  'via:outputParameter': ['Output parameter', 'The call maps a result field into this variable.'],
  'via:errorOutputParameter': ['Error output parameter', 'A failed call maps its error into this variable.'],
  'via:eventInParameter': ['Event in parameter', 'The variable is read to be sent out on an event payload.'],
  'via:eventOutParameter': ['Event out parameter', 'A received event payload field lands in this variable.'],
  'via:variableMapping': ['Init variable', 'An Init-Variables task sets this variable when the instance starts.'],
  'via:signalVariable': ['Signal variable', 'The action reads this variable to pass it into the instance it signals.'],
  'via:responsePayloadMapping': ['Stored response attribute', "A button's response is stored in this variable."],
  'via:errorResponsePayloadMapping': ['Stored error attribute', "A failed button call is stored in this variable."],
  'via:flwPayload': ['Action payload', "The bot script reads or writes this key of the action's payload."],
  'via:scriptApi': ['Script', 'A script sets or gets the variable through the Flowable API.'],
  'via:scriptRead': ['Script ≈ read', 'A bare identifier in a script body — probably a read, not provable.'],
  'via:expression': ['Expression', 'A ${…} expression reads this variable.'],
  'via:variablesFn': ['variables: function', 'A ${variables:…()} call names this variable as a string.'],
  'via:binding': ['Binding', 'A {{…}} binding reads this variable.'],
  'via:formField': ['Form field', 'A form field bound to this variable: prefilled from it, written back on submit.'],
  'via:formProperty': ['Form property', 'A legacy BPMN form property, rendered as a field.'],
  'via:formOutcome': ['Outcome variable', 'The form stores the chosen outcome here.'],
  'via:dmnInput': ['Decision input', 'A decision table input expression reads this variable.'],
  'via:dmnOutput': ['Decision output', 'A decision table writes its result to this variable.'],
  'via:dataObject': ['Data object', 'A process-level variable declaration.'],
  'via:multiInstanceElement': ['Element variable', 'Each item of a multi-instance collection is written here.'],
  'via:multiInstanceCollection': ['Collection', 'The collection a multi-instance loop iterates over.'],
  'via:initiator': ['Initiator variable', 'The engine writes the starting user here.'],
  'via:variableExtractor': ['Extracted variable', 'Pulled out of a payload so queries can index it.'],
  'via:javaApi': ['Java', 'A Java class sets or gets the variable through the engine API.'],
};
// [label, hint] for a namespaced key, falling back to the raw key with no hint.
function term(ns, key){
  if(key==null||key==='') return {label:'', hint:''};
  const e=DESIGN_TERMS[ns+':'+key];
  if(e) return {label:e[0]||String(key), hint:e[1]||''};
  // Relations Atlas *composes* instead of taking from a vocabulary: a listener relation carries its
  // event (`taskListener:complete`) and a bean call names the method (`calls asText()`). Resolve them
  // from their stem — dumping the raw string on the reader is what the vocabulary is here to prevent.
  if(ns==='rel'){
    const m=String(key).match(/^([A-Za-z]+):(.+)$/);
    const base=m&&DESIGN_TERMS['el:'+m[1]];
    if(base) return {label:base[0]+' ('+m[2]+')', hint:base[1]||''};
    if(/^calls .+\(\)$/.test(key)) return {label:String(key), hint:(DESIGN_TERMS['rel:calls']||[])[1]||''};
  }
  return {label:String(key), hint:''};
}
// A term rendered as text plus a native tooltip, so hovering explains it.
function termHtml(ns, key, cls){
  const t=term(ns,key);
  if(!t.label) return '';
  const c='term'+(cls?' '+cls:'');
  return '<span class="'+c+'"'+(t.hint?' title="'+esc(t.hint)+'"':'')+'>'+esc(t.label)+'</span>';
}
// Section headings say "Execution listeners" where a single row says "Execution listener".
const plural = s => !s ? s : (/s$/.test(s) ? s : s+'s');
const SECTIONS = ['Models','Integration','Code','Expressions','Checks','Variables','Access','Other'];
// Colors are emitted as var() references, not resolved values: the browser resolves them
// at paint time, so a theme switch restyles everything without any re-render (and there is
// no getComputedStyle per node, which used to force a style recalculation in large lists).
const color = t => 'var(--c-'+t+', #79848f)';
const covColor = k => 'var(--cov-'+k+', #79848f)';
const debounce = (fn,ms) => { let t; return function(){ clearTimeout(t); t=setTimeout(()=>fn.apply(this,arguments),ms); }; };
const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform||'');
const MODK = IS_MAC ? '⌘' : 'Ctrl';
// The "toggle this one" modifier for list selection. Platform-exact on purpose: on a Mac,
// Ctrl+click also raises the context menu, so accepting it there would fire both.
const modKey = e => IS_MAC ? e.metaKey : e.ctrlKey;
const looseCol = s => String(s==null?'':s).toLowerCase().replace(/[^a-z0-9]/g,'');
// external nodes split into Flowable API / navigation routes / real third-party deps.
const nodeColor = n => (n && n.type==='external')
  ? (n.data&&n.data.flowableApi?color('endpoint'):n.data&&n.data.route?color('page'):color('external'))
  : color(n?n.type:'');
const nodeKind = n => (n.type!=='external')
  ? (TM[n.type]?TM[n.type][0]:n.type)
  : (n.data.flowableApi?'Flowable API':n.data.route?'Navigation route':'External / library');

// adjacency — entries carry the edge's suspect/dynamic flags so chips, relation lists and the
// ego graph can mark uncertain links; rebuilt when the uncertain-links toggle flips.
const outM = new Map(), incM = new Map();
let hideUncertain = false;
try{ hideUncertain = localStorage.getItem('atlas-uncertain')==='hide'; }catch(e){}
const push = (m,k,v)=>{ if(!m.has(k)) m.set(k,[]); m.get(k).push(v); };
function rebuildAdj(){
  outM.clear(); incM.clear();
  edges.forEach(e=>{
    if(hideUncertain && (e.suspect||e.dynamic)) return;
    push(outM,e.s,{rel:e.rel,id:e.t,sus:!!e.suspect,dyn:!!e.dynamic});
    push(incM,e.t,{rel:e.rel,id:e.s,sus:!!e.suspect,dyn:!!e.dynamic});
  });
}
rebuildAdj();

// bean name -> java node id (for direct links from ${bean.method()} expressions)
const beanToNode = new Map();
nodes.filter(n=>n.type==='java').forEach(n=>{
  (n.data.beanNames||[]).forEach(b=>beanToNode.set(b,n.id));
  const dc=n.label.charAt(0).toLowerCase()+n.label.slice(1);
  if(!beanToNode.has(dc)) beanToNode.set(dc,n.id);
});

// a form is "unused / unlinked" when nothing functionally references it — i.e. it
// has no incoming edge other than app 'contains' membership (every form sits in an
// app, so that edge alone does not count as being used).
const isUnusedForm = n => n.type==='form' && !(incM.get(n.id)||[]).some(e=>e.rel!=='contains');

// state — the URL hash is the single source of truth for navigation (routes below);
// `view` mirrors the active route, `cat`/`sel` drive the browse columns.
// `focus` is the search term the current selection was reached with — highlighted in the detail panel.
// `focusEl` is the model element a search hit came from — the detail panel opens that row directly.
// `tabs`/`tab` are the open detail tabs (node ids + active index). Invariant: while the browse view
// shows a node, `sel === tabs[tab]` — that is what keeps every existing `state.sel` reader correct.
let state = {view:'overview', cat:null, sel:null, filter:'', sort:'name', focus:'', focusEl:'',
             tabs:[], tab:-1};
// the tree's lens was written to localStorage on every switch and never read back — a reload reset it
try{ state.treeLens=localStorage.getItem('atlas-tree-lens')||undefined; }catch(e){}

// ---------- categories ----------
function categories(){
  const byType = {};
  nodes.forEach(n => (byType[n.type] = byType[n.type]||[]).push(n));
  const cats = [];
  Object.keys(byType).forEach(t=>{
    if(t==='java'){
      const roles = {};
      byType.java.forEach(n=>(n.data.roles||[]).forEach(r=>roles[r]=(roles[r]||0)+1));
      Object.keys(roles).sort().forEach(r=>cats.push({
        id:'java::'+r, label:'Java · '+r, sec:'Code', color:color('java'), icon:'java', count:roles[r],
        match:n=>n.type==='java' && (n.data.roles||[]).includes(r)}));
    } else if(t==='variable'){
      // group variables by the model type(s) that use them (process / form / case / java …)
      const scopes = {};
      byType.variable.forEach(n=>(n.data.scopes||[]).forEach(s=>scopes[s]=(scopes[s]||0)+1));
      Object.keys(scopes).sort().forEach(s=>cats.push({
        id:'variable::'+s, label:'Variable · '+s, sec:'Variables',
        color:color('variable'), icon:'variable', count:scopes[s], match:n=>n.type==='variable' && (n.data.scopes||[]).includes(s)}));
      // Cross-cutting lens: the variables that actually travel through an in/out parameter mapping.
      const isParamVar=n=>n.type==='variable' && ((n.data||{}).ioParams||[]).length>0;
      const pc=byType.variable.filter(isParamVar).length;
      if(pc) cats.push({id:'variable::parameter', label:'Variable · parameter', sec:'Variables',
        color:color('variable'), icon:'variable', count:pc, match:isParamVar});
    } else if(t==='external'){
      // external nodes are not all "library": split out Flowable platform API calls
      // (endpoints.*) and in-app navigation routes (#/...) from real third-party deps.
      [{id:'external::api',  label:'Flowable API',        sec:'Integration', color:color('endpoint'), icon:'endpoint', match:n=>n.type==='external'&&n.data.flowableApi},
       {id:'external::route',label:'Navigation · routes', sec:'Other',       color:color('page'),     icon:'page',     match:n=>n.type==='external'&&n.data.route},
       {id:'external::missing',label:checkTitle('missingRefs'),sec:'Checks',      color:color('external'), icon:'invalidExpr', match:n=>n.type==='external'&&n.data.missingModel},
       {id:'external::lib',  label:'External / library',  sec:'Other',       color:color('external'), icon:'external', match:n=>n.type==='external'&&!n.data.flowableApi&&!n.data.route&&!n.data.missingModel}
      ].forEach(c=>{ const count=byType.external.filter(c.match).length; if(count) cats.push(Object.assign({count}, c)); });
    } else {
      const m = TM[t]||[t,'Other'];
      cats.push({id:t,label:m[0],sec:m[1],color:color(t),icon:t,count:byType[t].length,match:n=>n.type===t});
    }
  });
  // a review list: forms that nothing links to (orphaned UI models worth pruning)
  const unusedForms = nodes.filter(isUnusedForm);
  if(unusedForms.length) cats.push({id:'unused-form', label:checkTitle('unusedForms'), sec:'Checks',
    color:color('form'), icon:'form', count:unusedForms.length, match:isUnusedForm});
  // The two other "registered but never called" findings get review lists of their own, so the Checks
  // page's "open the list" lands on the 3 unused operations and not on all 40 (same rule as Findings.kt).
  const isUnusedOp = n => n.type==='serviceOperation' && !((n.data||{}).usedBy||[]).length;
  const unusedOps = nodes.filter(isUnusedOp);
  if(unusedOps.length) cats.push({id:'unused-op', label:checkTitle('unusedOps'), sec:'Checks',
    color:color('serviceOperation'), icon:'serviceOperation', count:unusedOps.length, match:isUnusedOp});
  const isUnusedFn = n => n.type==='customFunction' && !((n.data||{}).usedBy||[]).length;
  const unusedFns = nodes.filter(isUnusedFn);
  if(unusedFns.length) cats.push({id:'unused-fn', label:checkTitle('unusedFns'), sec:'Checks',
    color:color('customFunction'), icon:'customFunction', count:unusedFns.length, match:isUnusedFn});
  // Review lists for flagged expressions/bindings. Structural syntax errors make an
  // expression *invalid*; catalog findings (unknown function/namespace — the catalog may
  // simply not know a project-registered function) only make it *suspect*.
  const isExprN = n => n.type==='expression'||n.type==='binding';
  const hasErr = n => isExprN(n) && (n.data.problems||[]).some(p=>p.severity==='error');
  const hasWarnOnly = n => isExprN(n) && (n.data.problems||[]).length && !(n.data.problems||[]).some(p=>p.severity==='error');
  const invalidExprs = nodes.filter(hasErr);
  if(invalidExprs.length) cats.push({id:'invalid-expr', label:checkTitle('invalidExpr'), sec:'Checks',
    color:color('invalidExpr'), icon:'invalidExpr', count:invalidExprs.length, match:hasErr});
  const suspectExprs = nodes.filter(hasWarnOnly);
  if(suspectExprs.length) cats.push({id:'suspect-expr', label:checkTitle('suspectExpr'), sec:'Checks',
    color:color('suspectExpr'), icon:'suspectExpr', count:suspectExprs.length, match:hasWarnOnly});
  // A changelog nobody references, or one superseded by a later revision, is a schema surprise waiting.
  const isChangelogIssue = n => n.type==='liquibase' &&
    ['orphan','superseded'].indexOf(((n.data||{}).authority||{}).status)>=0;
  const clIssues = nodes.filter(isChangelogIssue);
  if(clIssues.length) cats.push({id:'changelog-issue', label:checkTitle('changelogIssues'), sec:'Checks',
    color:color('liquibase'), icon:'liquibase', count:clIssues.length, match:isChangelogIssue});
  // Variables whose only evidence is a bare identifier in a script — probably real, not provable.
  const isGuessedVar = n => n.type==='variable' && (n.data||{}).heuristic===true;
  const guessed = nodes.filter(isGuessedVar);
  if(guessed.length) cats.push({id:'guessed-var', label:checkTitle('guessedVars'), sec:'Checks',
    color:color('variable'), icon:'variable', count:guessed.length, match:isGuessedVar});
  // Something writes them and nothing reads them. Kept beside the script-guess list so all three
  // variable reviews read as one family; the full report with the definition sites is #/variables.
  const isUnusedVar = n => n.type==='variable' && (n.data||{}).unread===true;
  const unusedVars = nodes.filter(isUnusedVar);
  if(unusedVars.length) cats.push({id:'unused-var', label:checkTitle('unusedVars'), sec:'Checks',
    color:color('variable'), icon:'variable', count:unusedVars.length, match:isUnusedVar});
  const isUnreadInput = n => n.type==='variable' && ((n.data||{}).unreadIn||[]).length>0;
  const unreadInputs = nodes.filter(isUnreadInput);
  if(unreadInputs.length) cats.push({id:'unread-input', label:checkTitle('unreadInputs'), sec:'Checks',
    color:color('variable'), icon:'variable', count:unreadInputs.length, match:isUnreadInput});
  // The DMN twin of the unused form. A decision service is the caller of its tables, never one itself,
  // and a group's access is not a use (same rule as Findings.kt).
  const isUnusedDecision = n => n.type==='decision' && !(n.data||{}).decisionService &&
    !(incM.get(n.id)||[]).some(e=>e.rel!=='contains' && (byId.get(e.id)||{}).type!=='group');
  const unusedDecisions = nodes.filter(isUnusedDecision);
  if(unusedDecisions.length) cats.push({id:'unused-decision', label:checkTitle('unusedDecisions'), sec:'Checks',
    color:color('decision'), icon:'decision', count:unusedDecisions.length, match:isUnusedDecision});
  // Models with a script whose body (or scriptFormat) fails the structural syntax check.
  const scriptIssueModels = new Set(allScripts().filter(s=>(s.problems||[]).length).map(s=>s.model));
  if(scriptIssueModels.size) cats.push({id:'script-syntax', label:checkTitle('scriptIssues'), sec:'Checks',
    color:color('invalidExpr'), icon:'scripts', count:scriptIssueModels.size, match:n=>scriptIssueModels.has(n.id)});
  cats.sort((a,b)=> (SECTIONS.indexOf(a.sec)-SECTIONS.indexOf(b.sec)) || a.label.localeCompare(b.label));
  return cats;
}
const CATS = categories();
/** A review list that mirrors a check. Its sidebar count is the check's *open* count, so a decision taken
 *  on the page moves it: the node count alone still said "Unused forms 3" after all three were accepted. */
const CAT_CHECK={'unused-form':'unusedForms','unused-op':'unusedOps','unused-fn':'unusedFns','unused-decision':'unusedDecisions',
  'invalid-expr':'invalidExpr','suspect-expr':'suspectExpr','script-syntax':'scriptIssues','changelog-issue':'changelogIssues',
  'guessed-var':'guessedVars','unused-var':'unusedVars','unread-input':'unreadInputs','external::missing':'missingRefs'};

// ---------- findings: the itemised list :core ships, and what a local decision does to it ----------
// Every number on the Checks page — a block's count, a health row, the sidebar badge — is derived from
// FINDS through waiverFor(), never from the nodes and never from a second counter. So a row and the
// number above it cannot disagree, including after a reader accepts something here and before the page
// is regenerated.
let FINDS=[], FIND_BY_NODE=new Map();
function indexFindings(){
  FINDS=(DATA.findings||[]).map((f,i)=>Object.assign({fi:i}, f));
  FIND_BY_NODE=new Map();
  FINDS.forEach(f=>{ const k=f.node||f.file; if(!k) return;
    if(!FIND_BY_NODE.has(k)) FIND_BY_NODE.set(k,[]); FIND_BY_NODE.get(k).push(f); });
}
const todayIso=()=>new Date().toISOString().slice(0,10);
/** Expired the day *after* `until`, as :core reads it: the day itself is still covered. */
const waiverExpired=r=>!!(r&&r.until&&/^\d{4}-\d{2}-\d{2}$/.test(r.until)&&todayIso()>r.until);
/** The rule covering a finding, or null. The one place that decides "accepted" — and it reads the
 *  rules as they stand now, local additions and restores included. Same key as Waivers.covers: check
 *  and node (a parse finding's file), with element and subject narrowing only when the rule names them. */
function waiverFor(f){
  const node=f.node||f.file, rules=waiverRules();
  for(let i=0;i<rules.length;i++){ const r=rules[i];
    if(r.check===f.check && r.node===node && (!r.element||r.element===f.element) &&
       (!r.subject||r.subject===f.subject) && !waiverExpired(r)) return r; }
  return null;
}
let _fcache=null; const _nfc=new Map();
/** Open and accepted counts per check, the worst open severity per check, and the two totals. */
function findingCounts(){
  if(_fcache) return _fcache;
  const open={}, waived={}, worst={}; let openN=0, waivedN=0;
  FINDS.forEach(f=>{
    if(waiverFor(f)){ waived[f.check]=(waived[f.check]||0)+1; waivedN++; return; }
    open[f.check]=(open[f.check]||0)+1; openN++;
    if(f.severity==='error'||!worst[f.check]) worst[f.check]=f.severity||'warning';
  });
  return _fcache={open, waived, worst, openN, waivedN};
}
/** What one node carries: open findings, accepted ones, and the worst open severity — for badges. */
function nodeFindingCounts(id){
  if(_nfc.has(id)) return _nfc.get(id);
  let open=0, waived=0, worst=null;
  (FIND_BY_NODE.get(id)||[]).forEach(f=>{ if(waiverFor(f)) waived++; else { open++; if(f.severity==='error'||!worst) worst=f.severity||'warning'; } });
  const r={open, waived, worst}; _nfc.set(id, r); return r;
}
/** A small count of a node's open findings, coloured by the worst one — nothing when there are none.
 *  Worn by tree rows and list items, so a model with five findings no longer looks like a clean one. */
function findPillHtml(id){
  const c=nodeFindingCounts(id);
  if(!c.open){
    // Every finding on it accepted: still worth a mark, or an accepted form in a list called "unused"
    // looks exactly like a clean one.
    if(!c.waived) return '';
    const l=c.waived+' accepted finding'+(c.waived>1?'s':'');
    return '<span class="pill fpill pill-ok" aria-label="'+l+'" data-tip="'+l+' — see Findings on this model">✓</span>';
  }
  const lbl=c.open+' open finding'+(c.open>1?'s':'');
  return '<span class="pill fpill '+(c.worst==='error'?'pill-bad':'pill-warn')+'" aria-label="'+lbl+'" data-tip="'+lbl+' — see Findings on this model">'+c.open+'</span>';
}
/** The catalog's title for a check id, or the id itself for one the catalog does not name. */
function checkTitle(id){ const c=(DATA.checkCatalog||[]).find(x=>x.id===id); return c?c.title:id; }
/** `check id -> open count`, with a zero for every catalogued check — the sidebar sums two of these. */
function healthMap(){
  const C=findingCounts(), h={};
  (DATA.checkCatalog||[]).forEach(c=>{ h[c.id]=C.open[c.id]||0; });
  Object.keys(C.open).forEach(k=>{ h[k]=C.open[k]; });
  return h;
}
/** After a rule is added or dropped: every derived count is stale, and so is the sidebar's badge. */
function findingsChanged(){
  _fcache=null; _nfc.clear(); _wvCache=null;
  if(INSIGHTS){ INSIGHTS.health=healthMap(); INSIGHTS.checksOpen=findingCounts().openN; }
}

// ---------- insights (dashboard fuel) — one edge pass + one node pass at boot ----------
let INSIGHTS = null;
function computeInsights(){
  const indeg = new Map(), containsByApp = new Map(), openAppByApp = new Map(), entryPoints = [];
  edges.forEach(e=>{
    if(hideUncertain && (e.suspect||e.dynamic)) return;   // hidden everywhere means here too
    if(e.rel==='contains'){ containsByApp.set(e.s,(containsByApp.get(e.s)||0)+1); return; }
    const src = byId.get(e.s);
    if(src && src.type==='group'){
      if(e.rel==='open-app') openAppByApp.set(e.t,(openAppByApp.get(e.t)||0)+1);
      else if(e.rel==='start' && byId.get(e.t)) entryPoints.push({group:e.s, model:e.t});
      return;                                    // access edges don't count as "references"
    }
    if(byId.get(e.t)) indeg.set(e.t,(indeg.get(e.t)||0)+1);
  });
  // The project's own central artifacts: a platform bean, a URL or a security policy is referenced by
  // every model that uses it and would take the whole list (the summary excludes the same types).
  const HOTSPOT_EXCLUDED = new Set(['external','securityPolicy','group']);
  const hotspots = [...indeg.entries()].filter(x=>x[1]>0 && byId.get(x[0]) && !HOTSPOT_EXCLUDED.has(byId.get(x[0]).type))
    .sort((a,b)=> b[1]-a[1] || byId.get(a[0]).label.localeCompare(byId.get(b[0]).label))
    .slice(0,10).map(x=>({id:x[0], count:x[1]}));
  // Denominators for the dashboard ("3 of 16 services have schema gaps"). The numerators are the
  // health counts, which come from :core — see below.
  const isExprN = n => n.type==='expression'||n.type==='binding';
  let totalExprs=0, totalForms=0, totalChangelogs=0, totalCovServices=0, totalColServices=0, totalOps=0, totalFns=0;
  // Variables Atlas could prove a direction for, and the ones it declined to judge. The first is the
  // denominator the unused-variable counts are quoted against; the second is the report's own caveat —
  // how many names it stayed quiet about, which is what makes the ones it does name trustworthy.
  let totalDirectedVars=0, silentVars=0;
  // denominators for the checks that had none: what carries a literal secret, what is a query, what is a table
  let totalQueries=0, totalDecisionTables=0, totalSecretBearers=0;
  const SECRET_BEARERS=new Set(['service','channel','agent','knowledgeBase','process','case']);
  nodes.forEach(n=>{
    const d=n.data||{};
    if(SECRET_BEARERS.has(n.type)) totalSecretBearers++;
    if(n.type==='query') totalQueries++;
    else if(n.type==='decision' && !d.decisionService) totalDecisionTables++;
    if(isExprN(n)) totalExprs++;
    else if(n.type==='form') totalForms++;
    else if(n.type==='liquibase') totalChangelogs++;
    else if(n.type==='service'){ if((d.schemaCoverage||{}).counts) totalCovServices++;
      if((d.columns||[]).length) totalColServices++; }
    else if(n.type==='serviceOperation') totalOps++;
    else if(n.type==='customFunction') totalFns++;
    else if(n.type==='variable'){
      if(d.writeCount||d.readCount) totalDirectedVars++;
      if(d.readsUnknown) silentVars++;
    }
  });
  const apps = nodes.filter(n=>n.type==='app')
    .map(a=>({id:a.id, models:containsByApp.get(a.id)||0, groups:openAppByApp.get(a.id)||0}))
    .sort((a,b)=>b.models-a.models);
  const scripts = allScripts();
  // The health counts are read off the findings :core ships (Findings.kt computes them once for every
  // artifact — the Markdown reports, graph.json, the CLI status line and this page). Derived here from
  // the same list rather than copied from `checks`, so a decision taken on this page moves them too.
  const FC = findingCounts();
  const health = healthMap();
  INSIGHTS = { indeg, hotspots, apps, entryPoints,
    totalExprs, totalForms, totalChangelogs, totalCovServices, totalColServices, totalOps, totalFns,
    totalDirectedVars, silentVars, totalQueries, totalDecisionTables, totalSecretBearers,
    totalModels: (DATA.stats||{}).modelCount||0,
    totalScripts: scripts.length,
    // the denominator for the runtime-risk cards: without a process there is nothing to say about
    // async jobs or error paths, and a card reading "0" would look like a verdict rather than a gap
    totalProcesses: nodes.filter(n=>n.type==='process').length,
    health,
    // what the Checks tab counts in its badge: every open finding, in one number
    checksOpen: FC.openN };
}

// ---------- router — the hash is the single source of truth and the history ----------
// ''              -> overview (default)
// #/overview      -> overview
// #/schema        -> schema-gaps report (Liquibase → Service → Data object)
// #/checks        -> everything worth a look, in one place
// #/scripts       -> every script body in the project
// #/browse/<cat>  -> browse, category list without selection
// #<nodeId>       -> legacy permalink format: browse with that node selected (kept so
//                    every previously copied link keeps working). enc() escapes '/', so
//                    dispatching on the RAW leading '/' before decoding is unambiguous.
// #/checks&f=…&c=error&a=1 / #/tree&l=all&f=… -> a report with its own context: `f` filter text,
//                    `c` the chip, `a` show-accepted, `l` the tree's lens — so a reload or a copied link
//                    brings the report back as it was left, the way `&f=`/`&s=` already did for a list.
const REPORT_VIEWS={'/schema':'schema','/scripts':'scripts','/checks':'checks','/variables':'variables','/tree':'tree'};
function parseHash(){
  const raw = location.hash.slice(1);
  if(!raw || raw==='/overview') return {view:'overview'};
  const first=raw.split('&')[0];
  if(REPORT_VIEWS[first]){
    const ctx=hashContext(raw.split('&').slice(1));
    return {view:REPORT_VIEWS[first], rf:ctx.f, rc:ctx.c, acc:ctx.a, lens:ctx.l};
  }
  if(raw.indexOf('/browse/')===0){
    const parts = raw.slice(8).split('&');
    const cat = dec(parts[0]), ctx = hashContext(parts.slice(1));
    return CATS.some(c=>c.id===cat) ? {view:'browse', cat, f:ctx.f, s:ctx.s} : {view:'overview'};
  }
  if(raw.charAt(0)==='/') return {view:'overview'};      // unknown route
  // A node route may carry the search term that led here (&q=), the element the hit came from (&e=)
  // and the list context the panel had (&f= filter, &s= sort): `#<encId>&q=…&e=…&f=…&s=…`. Every part
  // is URI-encoded, so a literal '&' cannot occur inside one and the split is unambiguous.
  const parts = raw.split('&');
  const id = dec(parts[0]), ctx = hashContext(parts.slice(1));
  return byId.get(id) ? {view:'browse', sel:id, q:ctx.q, e:ctx.e, f:ctx.f, s:ctx.s} : {view:'overview'};
}
/** The `k=v` pairs behind a route's first part; unknown keys are ignored, absent ones stay undefined. */
function hashContext(pairs){
  const out={q:'', e:'', f:undefined, s:undefined, c:undefined, a:undefined, l:undefined};
  pairs.forEach(p=>{ const i=p.indexOf('='); if(i<0) return; const k=p.slice(0,i); if(k in out) out[k]=dec(p.slice(i+1)); });
  return out;
}
// Keep the URL's &f=/&s= in step with the list, without a history entry or a re-route (replaceState),
// so a reload or a copied link brings the filter and the sort back — the panel's context, not only its
// node. `&q=`/`&e=` travel the same way once a selection carries them.
function syncHashContext(){
  if(state.view!=='browse'){
    const base=Object.keys(REPORT_VIEWS).find(k=>REPORT_VIEWS[k]===state.view);
    if(!base) return;
    let h=base;
    if(state.rf) h+='&f='+enc(state.rf);
    if(state.rc) h+='&c='+enc(state.rc);
    if(state.acc) h+='&a='+enc(state.acc);
    if(state.view==='tree' && state.treeLens && state.treeLens!=='models') h+='&l='+enc(state.treeLens);
    if(location.hash.slice(1)!==h){ try{ history.replaceState(null, '', '#'+h); }catch(e){} }
    return;
  }
  const base=state.sel?enc(state.sel):(state.cat?'/browse/'+enc(state.cat):'');
  if(!base) return;
  let h=base;
  if(state.sel){ if(state.focus) h+='&q='+enc(state.focus); if(state.focusEl) h+='&e='+enc(state.focusEl); }
  if(state.filter) h+='&f='+enc(state.filter);
  if(state.sort && state.sort!=='name') h+='&s='+enc(state.sort);
  if(location.hash.slice(1)!==h){ try{ history.replaceState(null, '', '#'+h); }catch(e){} }
}
function showView(v){
  if(v!=='browse') listMarksClear();          // a multi-selection cannot outlive the list it was made in
  document.getElementById('view-overview').hidden = v!=='overview';
  document.getElementById('view-schema').hidden = v!=='schema';
  document.getElementById('view-scripts').hidden = v!=='scripts';
  document.getElementById('view-checks').hidden = v!=='checks';
  document.getElementById('view-variables').hidden = v!=='variables';
  document.getElementById('view-tree').hidden = v!=='tree';
  document.getElementById('view-browse').hidden = v!=='browse';
}
let _navCount = 0;
/** After a route swapped the view, the element that was clicked is usually gone with the old markup and
 *  focus has fallen to <body>; a screen reader is then nowhere. Put it on the new view's heading — but
 *  only then: a reader walking the list with the arrow keys keeps the list. */
function focusViewHeading(){
  const a=document.activeElement; if(a && a!==document.body) return;
  const v=document.querySelector('.view:not([hidden])'); if(!v) return;
  const h=v.querySelector('.dtitle, .dash-title'); if(!h) return;
  if(!h.hasAttribute('tabindex')) h.setAttribute('tabindex','-1');
  try{ h.focus({preventScroll:true}); }catch(e){}
}
/** Redraw the current view in place — for a setting that changes what every view shows, without the
 *  focus move and history a real navigation carries. */
function rerenderView(){
  switch(state.view){
    case 'overview': renderDashboard(); break;
    case 'schema': renderSchema(); break;
    case 'scripts': renderScripts(); break;
    case 'tree': renderTree(); break;
    case 'checks': renderChecks(); break;
    case 'variables': renderVariables(); break;
    default: renderDetail();
  }
}
function route(){
  closePalette();
  _navCount++;
  renderWaiverBar();
  if(_navCount>1) setTimeout(focusViewHeading, 0);
  const r = parseHash();
  state.focus = r.q || '';
  state.focusEl = r.e || '';
  state.rf = r.rf || ''; state.rc = r.rc || ''; state.acc = r.acc || '';   // a report route's own context
  if(r.lens) state.treeLens = r.lens;
  if(r.view==='overview'){
    state.view='overview'; state.sel=null;
    showView('overview'); renderDashboard();
    renderSidebarActive(); renderCrumbs();
  } else if(r.view==='schema'){
    state.view='schema'; state.sel=null;
    showView('schema'); renderSchema();
    renderSidebarActive(); renderCrumbs();
  } else if(r.view==='scripts'){
    state.view='scripts'; state.sel=null;
    showView('scripts'); renderScripts();
    renderSidebarActive(); renderCrumbs();
  } else if(r.view==='tree'){
    state.view='tree'; state.sel=null;
    showView('tree'); renderTree();
    renderSidebarActive(); renderCrumbs();
  } else if(r.view==='checks'){
    state.view='checks'; state.sel=null;
    showView('checks'); renderChecks();
    renderSidebarActive(); renderCrumbs();
  } else if(r.view==='variables'){
    state.view='variables'; state.sel=null;
    showView('variables'); renderVariables();
    renderSidebarActive(); renderCrumbs();
  } else if(r.sel){
    applySelection(r.sel, {filter:r.f, sort:r.s});        // handles view/list/detail/crumbs
  } else {
    state.view='browse';
    if(state.cat!==r.cat){ state.cat=r.cat; state.filter=''; state.sort='name'; listMarksClear(); }
    if(r.f!=null) state.filter=r.f;                      // a reload or a shared link brings the list context back
    if(r.s!=null) state.sort=r.s;
    rememberTabScroll();
    // The category listing has no active node, so no tab renders as current. `state.tab` is NOT
    // reset here: it stays a valid write pointer, because "no active tab" would make syncTabsWith
    // append — and then every sidebar category visit would silently grow the tab set.
    state.sel=null;
    showView('browse'); renderList(); renderTabs(); renderDetail();
    renderSidebarActive(); renderCrumbs();
    syncHashContext();
  }
}

// ---------- sidebar ----------
// Which groups are folded, remembered like the detail sections are (atlas-sect). Absent = open.
const NAVG_STORE='atlas-navgroups';
function navGroups(){ try{ return JSON.parse(localStorage.getItem(NAVG_STORE)||'{}')||{}; }catch(e){ return {}; } }
function navGroupRemember(sec, open){
  try{ const m=navGroups(); m[sec]=open; localStorage.setItem(NAVG_STORE, JSON.stringify(m)); }catch(e){}
}
function renderSidebar(){
  const nav = document.getElementById('nav'); nav.innerHTML='';
  // One keyboard model for headers and entries: ↑/↓ walk what is visible (a folded group's entries are
  // skipped), ← folds the group you are in and lands on its header, → unfolds a folded header,
  // Home/End jump. Headers are real <button>s, so Enter/Space toggle them natively.
  const stops=()=>[...nav.querySelectorAll('.side-group, .side-item')].filter(x=>!x.closest('[hidden]'));
  const navKeys = el => e => {
    const isHdr=el.classList.contains('side-group');
    if(e.key==='Enter'||e.key===' '){ if(!isHdr){ e.preventDefault(); el.click(); } }
    else if(e.key==='ArrowDown'||e.key==='ArrowUp'){
      e.preventDefault();
      const s=stops(), i=s.indexOf(el)+(e.key==='ArrowDown'?1:-1);
      if(s[i]) s[i].focus();
    }
    else if(e.key==='Home'||e.key==='End'){ e.preventDefault(); const s=stops(); (e.key==='Home'?s[0]:s[s.length-1]).focus(); }
    else if(e.key==='ArrowLeft'){
      const hdr=isHdr?el:(el.parentElement&&el.parentElement.previousElementSibling);
      if(hdr&&hdr.classList.contains('side-group')){ e.preventDefault(); if(hdr.getAttribute('aria-expanded')==='true') hdr.click(); hdr.focus(); }
    }
    else if(e.key==='ArrowRight'){
      if(isHdr&&el.getAttribute('aria-expanded')==='false'){ e.preventDefault(); el.click(); }
    }
  };
  const mkItem = (html, title) => {
    const el=document.createElement('div');
    el.className='side-item'; el.setAttribute('role','button'); el.tabIndex=0;
    // No tooltip on a menu entry: the label and count are right there, and a bubble popping up under
    // the cursor while you slide down the list is pure noise. In rail mode the sidebar flies out on
    // hover, so the label is never actually hidden. The text stays available to screen readers.
    el.setAttribute('aria-pressed','false'); el.setAttribute('aria-label', title); el.innerHTML=html;
    el.onkeydown=navKeys(el);
    return el;
  };
  const ov = mkItem(typeIcon('overview',{color:'var(--accent)'})+'<span class="lbl">Overview</span>','Overview');
  ov.dataset.route='/overview';
  ov.onclick=()=>{ location.hash='/overview'; };
  nav.appendChild(ov);
  // A tab belongs to a section like any other list — "Script tasks" is an Integration thing, the
  // review reports belong under Checks. `pri` keeps a section's tabs above its drill-down lists.
  const C0=findingCounts();
  const items=CATS.map(c=>CAT_CHECK[c.id]?Object.assign({}, c, {count:C0.open[CAT_CHECK[c.id]]||0}):c);
  const scriptCount=allScripts().length;
  if(scriptCount) items.push({route:'/scripts', label:'Script tasks', sec:'Integration', pri:0, icon:'scripts',
    color:color('process'), count:scriptCount,
    tip:'Script tasks ('+scriptCount+') — every script task, listener script and bot script'});
  items.push({route:'/tree', label:'Reference tree', sec:'Models', pri:0, icon:'tree',
    color:color('process'),
    tip:'What each app starts, and what those models reach — every relation except app membership'});
  const openChecks=INSIGHTS.checksOpen;
  items.push({route:'/checks', label:'Checks', sec:'Checks', pri:0, icon:'checks',
    color:covColor(openChecks?'bad':'good'), count:openChecks,
    tip:'Everything worth a look — parse issues, flagged expressions, schema gaps, unused and unproven models'});
  if(INSIGHTS.totalCovServices>0){
    const gaps=INSIGHTS.health.schemaGaps;
    items.push({route:'/schema', label:'Schema gaps', sec:'Checks', pri:1, icon:'schema',
      color:covColor(gaps?'bad':'good'), count:gaps,
      tip:'Schema gaps — Liquibase → Service → Data object coverage'});
  }
  if(INSIGHTS.totalDirectedVars>0){
    const unusedVars=INSIGHTS.health.unusedVars+INSIGHTS.health.unreadInputs;
    items.push({route:'/variables', label:'Unused variables', sec:'Variables', pri:0, icon:'variable',
      color:covColor(unusedVars?'bad':'good'), count:unusedVars,
      tip:'Variables something writes and nothing reads, and inputs mapped into a model that never '+
          'reads them'});
  }
  items.sort((a,b)=> (SECTIONS.indexOf(a.sec)-SECTIONS.indexOf(b.sec)) ||
                     ((a.pri==null?2:a.pri)-(b.pri==null?2:b.pri)) || a.label.localeCompare(b.label));
  // Groups fold. The header is a <button aria-expanded>, its entries live in a <div role=group> that
  // hides with the `hidden` attribute, and the fold is remembered per section. Sixty entries in eight
  // groups is the ordinary size of this list; the reader decides which groups earn their space.
  // The same walk fills #navpick, the <select> that stands in for the list below 800px (CSS decides).
  const folded=navGroups();
  const pick=document.getElementById('navpick');
  pick.innerHTML='<option value="/overview">Overview</option>';
  let cur='', grp=null, og=null;
  items.forEach(c=>{
    if(c.sec!==cur){
      cur=c.sec;
      const sec=cur;   // `cur` keeps moving; the click handler below must remember this group's name
      const id='navgrp-'+sec.toLowerCase(), isOpen=folded[sec]!==false, n=items.filter(x=>x.sec===sec).length;
      const btn=document.createElement('button');
      btn.type='button'; btn.className='side-group'; btn.dataset.group=sec;
      btn.setAttribute('aria-expanded', String(isOpen)); btn.setAttribute('aria-controls', id);
      btn.innerHTML=uiIcon('chevron')+'<span class="lbl">'+esc(sec)+'</span><span class="n">'+n+'</span>';
      const g=document.createElement('div');
      g.className='side-grp'; g.id=id; g.setAttribute('role','group'); g.setAttribute('aria-label', sec); g.hidden=!isOpen;
      btn.onclick=()=>{ const o=g.hidden; g.hidden=!o; btn.setAttribute('aria-expanded', String(o)); navGroupRemember(sec, o); };
      btn.onkeydown=navKeys(btn);
      nav.appendChild(btn); nav.appendChild(g); grp=g;
      og=document.createElement('optgroup'); og.label=sec; pick.appendChild(og);
    }
    const el = mkItem(typeIcon(c.icon,{color:c.color})+'<span class="lbl">'+esc(c.label)+'</span>'+
                      (c.count?'<span class="n">'+c.count+'</span>':''),
                      c.tip||(c.label+' ('+c.count+')'));
    const target=c.route||('/browse/'+enc(c.id));
    if(c.route) el.dataset.route=c.route; else el.dataset.cat=c.id;
    el.onclick=()=>{ location.hash=target; };
    grp.appendChild(el);
    const opt=document.createElement('option');
    opt.value=target; opt.textContent=c.label+(c.count?' · '+c.count:''); og.appendChild(opt);
  });
  pick.onchange=()=>{ if(pick.value) location.hash=pick.value; };
  // footer warning chip — routes to the Checks page and lands on the parse block. Counted from the
  // findings, like everything else: a parse issue the team accepted is not a warning any more.
  {
    const chip=document.getElementById('diagchip');
    const n=findingCounts().open.parseIssues||0;
    chip.hidden=!n;
    if(n){
      chip.innerHTML='⚠<span class="wtxt">&nbsp;'+n+' parse issue'+(n>1?'s':'')+'</span>';
      chip.setAttribute('aria-label',
        n+' parse issue'+(n>1?'s':'')+' — files the generator could not fully analyze');
      chip.onclick=()=>{
        _checkJump='chk-parseIssues';
        if(state.view==='checks') renderChecks(); else location.hash='/checks';
      };
    }
  }
}
function renderSidebarActive(){
  document.querySelectorAll('#nav .side-item').forEach(el=>{
    const on = el.dataset.route ? el.dataset.route==='/'+state.view
                                : (state.view==='browse' && state.cat===el.dataset.cat);
    el.classList.toggle('on', on);
    el.setAttribute('aria-pressed', on?'true':'false');
  });
  // A folded group whose entry is the active one says so on its header — and stays folded: the fold was
  // the reader's choice, and a sidebar that reopens itself on every navigation is one nobody can shape.
  document.querySelectorAll('#nav .side-group').forEach(h=>{
    const g=document.getElementById(h.getAttribute('aria-controls'));
    h.classList.toggle('has-on', !!(g&&g.querySelector('.side-item.on')));
  });
  const pick=document.getElementById('navpick');
  if(pick) pick.value = state.view==='browse' ? (state.cat?'/browse/'+enc(state.cat):'') : '/'+state.view;
}

// ---------- topbar breadcrumbs ----------
function renderCrumbs(){
  const c=document.getElementById('crumbs');
  const sep='<span class="crumb-sep">/</span>';
  const link=(txt,href,ic)=>'<a class="crumb" href="'+href+'">'+(ic||'')+esc(txt)+'</a>';
  const cur=(txt,ic)=>'<span class="crumb cur">'+(ic||'')+esc(txt)+'</span>';
  let h, title;
  if(state.view==='overview'){
    h=link(DATA.project,'#/overview')+sep+cur('Overview');
    title='Flowable Atlas — '+DATA.project;
  } else if(state.view==='schema'){
    h=link(DATA.project,'#/overview')+sep+cur('Schema gaps');
    title='Schema gaps — Flowable Atlas';
  } else if(state.view==='scripts'){
    h=link(DATA.project,'#/overview')+sep+cur('Script tasks');
    title='Script tasks — Flowable Atlas';
  } else if(state.view==='checks'){
    h=link(DATA.project,'#/overview')+sep+cur('Checks');
    title='Checks — Flowable Atlas';
  } else if(state.view==='variables'){
    h=link(DATA.project,'#/overview')+sep+cur('Unused variables');
    title='Unused variables — Flowable Atlas';
  } else if(state.view==='tree'){
    h=link(DATA.project,'#/overview')+sep+cur('Reference tree');
    title='Reference tree — Flowable Atlas';
  } else {
    const cat=CATS.find(x=>x.id===state.cat);
    const n=state.sel&&byId.get(state.sel);
    h=link(DATA.project,'#/overview');
    const ci=typeIcon(cat?cat.icon:'_',{color:cat?cat.color:''});
    if(cat) h+=sep+(n?link(cat.label,'#/browse/'+enc(cat.id),ci):cur(cat.label,ci));
    if(n) h+=sep+cur(n.label,nodeIcon(n));
    title=(n?n.label:(cat?cat.label:'Browse'))+' — Flowable Atlas';
  }
  c.innerHTML=h;
  document.title=title;
}

// ---------- dashboard (#/overview) ----------
// A health card on the overview is a shortcut into the Checks tab: remember which block it wants and
// let `renderChecks()` scroll there once the route has landed.
let _checkJump=null;
function renderDashboard(){
  const v=document.getElementById('view-overview');
  const st=DATA.stats||{};
  let h='<div class="dash">';
  const suN=st.suspectEdges||0, dyN=st.dynamicEdges||0;
  const uncertain=(suN||dyN)?' · '+[suN?suN+' suspect':'',dyN?dyN+' dynamic':''].filter(Boolean).join(' + ')
    +' <span data-tip="suspect = loose/cross-type match — dynamic = expression-valued reference">link'+((suN+dyN)>1?'s':'')+'</span>':'';
  h+='<div class="dash-title">'+esc(DATA.project)+'</div>'+
     '<div class="dash-sub">'+nodes.length+' nodes · '+edges.length+' links'+uncertain+' across the model &amp; code graph</div>';
  h+='<div class="seclabel">Inventory</div>'+inventoryHtml();
  // Two columns from here: health beside hotspots, apps beside entry points — the four things a reader
  // came for, above the fold on an ordinary screen instead of below a grid of health cards.
  h+='<div class="dash-cols">';
  // health — the same list the Checks tab opens with; the overview stays a summary and links there for the
  // findings themselves (one place to review, instead of two that drift apart)
  const list=healthListHtml();
  if(list){
    const open=INSIGHTS.checksOpen;
    h+='<div class="dash-col"><div class="seclabel row">Health'+
       '<button class="dgbtn" data-route="/checks">'+
       (open?open+' finding'+(open>1?'s':'')+' to review ↗':'open Checks ↗')+'</button></div>'+list+'</div>';
  }
  // hotspots
  if(INSIGHTS.hotspots.length){
    h+='<div class="dash-col"><div class="seclabel">Hotspots — most referenced</div><div class="dashrows">';
    INSIGHTS.hotspots.forEach(x=>{
      const n=byId.get(x.id);
      h+='<div class="dashrow" data-id="'+enc(x.id)+'" role="link" tabindex="0">'+
         nodeIcon(n)+
         '<span class="nm">'+esc(n.label)+'</span><span class="ty">'+esc(nodeKind(n))+'</span>'+
         '<span class="pill">'+x.count+' refs</span></div>';
    });
    h+='</div></div>';
  }
  // apps
  if(INSIGHTS.apps.length){
    h+='<div class="dash-col"><div class="seclabel">Apps</div><div class="dashrows">';
    INSIGHTS.apps.forEach(a=>{
      const n=byId.get(a.id); if(!n) return;
      h+='<div class="dashrow" data-id="'+enc(a.id)+'" role="link" tabindex="0">'+
         typeIcon('app')+
         '<span class="nm">'+esc(n.label)+'</span>'+
         (a.groups?'<span class="ty">'+a.groups+' group'+(a.groups>1?'s':'')+' can open</span>':'')+
         '<span class="pill">'+a.models+' models</span></div>';
    });
    h+='</div></div>';
  }
  // entry points — who can start what
  if(INSIGHTS.entryPoints.length){
    const eps=INSIGHTS.entryPoints.slice(0,50);
    h+='<div class="dash-col"><div class="seclabel">Entry points — who can start what</div><div class="dashrows">';
    eps.forEach(ep=>{
      h+='<div class="dashrow">'+nodeChip(ep.group)+'<span class="sep">can start</span>'+nodeChip(ep.model)+'</div>';
    });
    if(INSIGHTS.entryPoints.length>eps.length)
      h+='<div class="dashrow muted">+ '+(INSIGHTS.entryPoints.length-eps.length)+' more</div>';
    h+='</div></div>';
  }
  h+='</div></div>';
  v.innerHTML=h;
  wireNodeLinks(v, '[data-id]', {first:reportNav});
}
// One chip per node type present — the icon, the count, Design's name — in sidebar order; a chip opens the
// type's browse list. The four metric cards this replaces (models / Java / endpoints / groups) were four
// numbers with forty pixels of air around each; the strip says the same and names every type it counted.
// Derived nodes (variables, expressions, bindings, literals, externals) are not inventory and stay out.
function inventoryHtml(){
  const byType={};
  nodes.forEach(n=>{ byType[n.type]=(byType[n.type]||0)+1; });
  const skip=new Set(['variable','expression','binding','string','external']);
  const order=t=>SECTIONS.indexOf((TM[t]||[t,'Other'])[1]);
  const types=Object.keys(byType).filter(t=>!skip.has(t))
    .sort((a,b)=>order(a)-order(b)||(byType[b]-byType[a])||a.localeCompare(b));
  if(!types.length) return '';
  return '<div class="inv">'+types.map(t=>{
    const attrs=CATS.some(c=>c.id===t)?' data-cat="'+esc(t)+'" role="link" tabindex="0"':'';
    return '<span class="invc"'+attrs+'>'+typeIcon(t)+'<b>'+byType[t]+'</b>'+esc(typeLabel(t))+'</span>';
  }).join('')+'</div>';
}

// ---------- schema coverage: one renderer for the service detail AND the schema tab ----------
// `onlyGaps` filters the table to the problem rows (the schema tab's view of the world);
// `leadChipId` puts the owning service's chip first on the meta line;
// `crossed` is the owning service's `crossedColumns` (from :core) — a crossed mapping is *not* a
// coverage gap (every column maps through, just to the wrong field), so without the marker the one
// row a reader most needs to see is the one that looks cleanest.
/** loose field name -> why its mapping is suspect, for the marker's tooltip. */
function crossedIndex(list){
  const m=new Map();
  (list||[]).forEach(g=>{
    const ms=g.mappings||[];
    ms.forEach((p,i)=>{
      const other=ms[(i+1)%ms.length]||{};
      m.set(looseCol(p.field), g.kind==='crossed'
        ? 'This table also has '+g.expected+', the column this field name points at'+
          (g.otherField?' — '+g.otherField+' maps that one':', and no field maps it')
        : g.kind==='swapped'
          ? 'Looks swapped with '+other.field+', which maps '+other.column
          : 'Part of a rotation: '+ms.map(x=>x.field+' → '+x.column).join(', '));
    });
  });
  return m;
}
function schemaCoverageHtml(sc, onlyGaps, leadChipId, crossed){
  const crossIdx=crossedIndex(crossed);
  const crossOf=r=>crossIdx.get(looseCol(r.service||''));
  const ct=sc.counts||{};
  let b='';
  // owning service / source changelog / backing data objects (clickable)
  let meta=leadChipId?nodeChip(leadChipId):'';
  if(sc.liquibase){ const lc=nodeChip('liquibase:'+sc.liquibase); if(lc) meta+='<span class="muted">changelog</span>'+lc; }
  (sc.dataObjects||[]).forEach(k=>{ const dc=nodeChip('dataObject:'+k); if(dc) meta+=dc; });
  if(meta) b+='<div class="covmeta">'+meta+'</div>';
  // gap summary
  let badges='';
  if(ct.noService) badges+='<span class="cov-badge cov-bad">'+ct.noService+' not mapped in service</span>';
  if(ct.noDataObject) badges+='<span class="cov-badge cov-warn">'+ct.noDataObject+' not in data object</span>';
  if(ct.extra) badges+='<span class="cov-badge cov-info">'+ct.extra+' not in Liquibase</span>';
  if(ct.ok) badges+='<span class="cov-badge cov-good">'+ct.ok+' mapped through</span>';
  const nCross=(sc.rows||[]).filter(crossOf).length;
  if(nCross) badges+='<span class="cov-badge cov-bad">'+nCross+' look'+(nCross>1?'':'s')+' crossed</span>';
  if(badges) b+='<div class="covbadges">'+badges+'</div>';
  const rowCls={'no-service':'cov-bad','no-dataobject':'cov-warn','extra-service':'cov-info','ok':''};
  const miss='<span class="miss">✗ not mapped</span>';
  const rows=onlyGaps?(sc.rows||[]).filter(r=>r.status!=='ok'||crossOf(r)):(sc.rows||[]);
  if(rows.length){
    b+=tbl([{k:'lb',label:'Liquibase column',w:'minmax(14ch,1.4fr)',mono:true},{k:'sv',label:'Service mapping',w:'minmax(14ch,1.4fr)',mono:true},{k:'do',label:'Data object field',w:'minmax(12ch,1.2fr)',mono:true}],
      rows.map(r=>{
        const lb = r.inLiquibase ? esc(r.sql)+(r.sqlType?' <span class="muted">'+esc(r.sqlType)+'</span>':'') : '<span class="miss">— not in changelog</span>';
        const cr = crossOf(r);
        const sv = r.inService ? esc(r.service||r.serviceCol||'')+
            (r.serviceCol&&looseCol(r.serviceCol)!==looseCol(r.service||'')?' <span class="muted">'+esc(r.serviceCol)+'</span>':'')+
            (r.serviceType?' <span class="muted">'+esc(r.serviceType)+'</span>':'')+
            (cr?' <span class="tag sev-bad" data-tip="'+esc(cr)+'">⇄ crossed</span>':'') : miss;
        const dob = (r.dataObjects&&r.dataObjects.length)
          ? r.dataObjects.map(x=>esc(x.field)+((sc.dataObjects||[]).length>1?' <span class="muted">'+esc(x.do)+'</span>':'')).join(', ')
          : (r.inLiquibase||r.inService?miss:'');
        return {cls:cr?'cov-bad':(rowCls[r.status]||''), hay:elHay(r.sql,r.service,r.serviceCol,(r.dataObjects||[]).map(x=>x.field).join(' ')), cells:{lb, sv, do:dob}};
      }), {placeholder:'filter columns…'});
  }
  if(onlyGaps&&ct.ok) b+='<div class="tbl-more muted">+ '+ct.ok+' column'+(ct.ok>1?'s':'')+' mapped through cleanly — full table on the service page</div>';
  return b;
}

// ---------- schema-gaps tab (#/schema) ----------
// The dashboard's "Schema gaps" number, unfolded: every service with coverage data, its problem rows
// front and center, fully-mapped services collapsed to a chip row at the bottom.
function renderSchema(){
  const v=document.getElementById('view-schema');
  const svcs=nodes.filter(n=>n.type==='service'&&(n.data||{}).schemaCoverage&&((n.data.schemaCoverage.rows||[]).length))
    .map(n=>{ const c=n.data.schemaCoverage.counts||{};
      return {n, sc:n.data.schemaCoverage,
              cross:(n.data.crossedColumns||[]).reduce((a,g)=>a+(g.mappings||[]).length,0),
              gaps:(c.noService||0)+(c.noDataObject||0), extra:c.extra||0}; })
    .sort((a,b)=> (b.gaps+b.extra+b.cross)-(a.gaps+a.extra+a.cross) || a.n.label.localeCompare(b.n.label));
  const dirty=svcs.filter(s=>s.gaps||s.extra||s.cross), clean=svcs.filter(s=>!s.gaps&&!s.extra&&!s.cross);
  const total=svcs.reduce((a,s)=>a+s.gaps,0), crossTotal=svcs.reduce((a,s)=>a+s.cross,0);
  _sectReg=[];
  let b='';
  dirty.forEach(s=>{
    b+=section('rpt-schema-'+s.n.key, nodeIcon(s.n)+' '+esc(s.n.label)+(s.sc.table?' <span class="mono muted">'+esc(s.sc.table)+'</span>':''),
      schemaCoverageHtml(s.sc, true, s.n.id, s.n.data.crossedColumns),
      {count:s.gaps+s.extra+s.cross, nav:s.n.label,
       hint:s.cross?'columns that do not map through, or pair with the wrong field'
                   :'columns that do not map through'});
  });
  if(clean.length) b+=section('rpt-schema-clean','Fully mapped','<div class="nodechips">'+clean.map(s=>nodeChip(s.n.id)).join('')+'</div>',
    {count:clean.length, hint:'every column of these services maps through cleanly'});
  const reg=_sectReg; _sectReg=null;
  let h='<div class="dash" data-fscope>';
  h+=pageHeader({icon:'schema', color:color('schema'), title:'Schema gaps', sub:'Liquibase → Service → Data object — '+
    (svcs.length===0?'no service declares schema coverage data'
     :total?total+' column'+(total>1?'s':'')+' not mapped through, in '+dirty.length+' of '+svcs.length+' service'+(svcs.length>1?'s':'')
     :'every column of all '+svcs.length+' service'+(svcs.length>1?'s':'')+' maps through cleanly')+
    (crossTotal?' · '+crossTotal+' mapping'+(crossTotal>1?'s':'')+' look'+(crossTotal>1?'':'s')+' crossed':'')});
  if(!svcs.length){
    h+='<div class="estate"><div class="estate-ic" aria-hidden="true">▦</div>'+
       '<div class="et">Nothing to check</div>'+
       '<div class="eh">No service in this project references a Liquibase changelog, so there is no schema to compare against.</div></div>';
  }
  h+=secnavHtml(reg)+b;
  h+='</div>';
  v.innerHTML=h;
  wireReport(v);
  wireNodeLinks(v, '[data-id]');
}

// ---------- checks view (#/checks) ----------
// One block per catalogued check, its rows the findings :core shipped. What each check *is* — title,
// severity, the one-liner for both states, why it matters, what to do, where the docs are — comes from
// DATA.checkCatalog. This table holds only what wires a check into this page: the browse list and the
// report tab a block links to, when the row is worth showing at all, and what a clean row can say it
// examined. A check the catalog names but this table does not renders with the defaults.
const CHECK_META={
  invalidExpr:{cat:'invalid-expr', show:()=>INSIGHTS.totalExprs>0, examined:()=>[INSIGHTS.totalExprs,'expression']},
  suspectExpr:{cat:'suspect-expr', show:()=>INSIGHTS.totalExprs>0, examined:()=>[INSIGHTS.totalExprs,'expression']},
  scriptIssues:{cat:'script-syntax', route:'/scripts', show:()=>INSIGHTS.totalScripts>0, examined:()=>[INSIGHTS.totalScripts,'script']},
  nonExclusiveAsync:{show:()=>INSIGHTS.totalProcesses>0, examined:()=>[INSIGHTS.totalProcesses,'process']},
  unguardedTasks:{show:()=>INSIGHTS.totalProcesses>0, examined:()=>[INSIGHTS.totalProcesses,'process']},
  asyncWithoutRetry:{show:()=>INSIGHTS.totalProcesses>0, examined:()=>[INSIGHTS.totalProcesses,'process']},
  schemaGaps:{route:'/schema', show:()=>INSIGHTS.totalCovServices>0, examined:()=>[INSIGHTS.totalCovServices,'service']},
  missingRefs:{cat:'external::missing'},
  crossedColumns:{route:'/schema', show:()=>INSIGHTS.totalColServices>0, examined:()=>[INSIGHTS.totalColServices,'service']},
  unusedForms:{cat:'unused-form', show:()=>INSIGHTS.totalForms>0, examined:()=>[INSIGHTS.totalForms,'form']},
  changelogIssues:{cat:'changelog-issue', show:()=>INSIGHTS.totalChangelogs>0, examined:()=>[INSIGHTS.totalChangelogs,'changelog']},
  guessedVars:{cat:'guessed-var'},
  unusedOps:{cat:'unused-op', show:()=>INSIGHTS.totalOps>0, examined:()=>[INSIGHTS.totalOps,'operation']},
  unusedFns:{cat:'unused-fn', show:()=>INSIGHTS.totalFns>0, examined:()=>[INSIGHTS.totalFns,'function']},
  unusedVars:{cat:'unused-var', route:'/variables', show:()=>INSIGHTS.totalDirectedVars>0, examined:()=>[INSIGHTS.totalDirectedVars,'variable']},
  unreadInputs:{cat:'unread-input', route:'/variables', show:()=>INSIGHTS.totalDirectedVars>0, examined:()=>[INSIGHTS.totalDirectedVars,'variable']},
  // The six that had no face: no "open the list", no denominator when clean — so half the health list said
  // what it had checked and half did not.
  hardcodedSecrets:{show:()=>INSIGHTS.totalSecretBearers>0, examined:()=>[INSIGHTS.totalSecretBearers,'model']},
  unsafeQueries:{show:()=>INSIGHTS.totalQueries>0, examined:()=>[INSIGHTS.totalQueries,'query']},
  unusedDecisions:{cat:'unused-decision', show:()=>INSIGHTS.totalDecisionTables>0, examined:()=>[INSIGHTS.totalDecisionTables,'decision table']},
  leftoverMarkers:{examined:()=>[INSIGHTS.totalModels,'model']},
  gatewayNoDefault:{show:()=>INSIGHTS.totalProcesses>0, examined:()=>[INSIGHTS.totalProcesses,'process']},
  implicitSplit:{show:()=>INSIGHTS.totalProcesses>0, examined:()=>[INSIGHTS.totalProcesses,'process']},
};
const metaOf=id=>CHECK_META[id]||{};
const ROUTE_LABEL={'/schema':'open the schema report','/scripts':'open the scripts tab','/variables':'open the full report'};
const routeBtn=(route,label)=>'<button type="button" class="dgbtn" data-route="'+esc(route)+'">'+esc(label||ROUTE_LABEL[route]||route)+' ↗</button>';
/** The catalog in reading order — plus, so nothing is ever dropped on the floor, any check :core
 *  emitted that this page's catalog does not name. */
function checksInOrder(){
  const cat=(DATA.checkCatalog||[]).slice(), seen=new Set(cat.map(c=>c.id));
  FINDS.forEach(f=>{ if(!seen.has(f.check)){ seen.add(f.check);
    cat.push({id:f.check, title:f.check, severity:'', what:'', clean:'', why:'', fix:'', docs:''}); } });
  return cat;
}
/** The health list: one row per check — tone bar, count, severity, name, one-line reason — sorted bad →
 *  warn → clean, the clean ones folded under a single summary line. [keys] narrows it to a subset, so a
 *  page shows only the checks it has blocks for. The severity is the worst *open* finding's, not a flag
 *  on the check: a check that can emit both reads as an error only where it actually did. */
const TONE_RANK={bad:0,warn:1,ok:2};
function healthRows(keys){
  const C=findingCounts();
  return checksInOrder().filter(c=>(!keys||keys.indexOf(c.id)>=0)&&(metaOf(c.id).show||(()=>true))()).map(c=>{
    const m=metaOf(c.id), n=C.open[c.id]||0, w=C.waived[c.id]||0;
    const sev=n?(C.worst[c.id]||'warning'):'', tone=n?(sev==='error'?'bad':'warn'):'ok';
    const ex=(!n&&m.examined)?m.examined():null;
    let sub=n?c.what:c.clean;
    if(!n&&ex&&ex[0]) sub+=(sub?' — ':'')+ex[0]+' '+ex[1]+(ex[0]>1?'s':'')+' checked';
    if(w) sub+=(sub?' · ':'')+w+' accepted';
    return {k:c.id, label:c.title, n, w, sev, tone, sub, tier:c.tier||'', jump:'chk-'+c.id};
  // The catalog's tiers are the reading order every surface uses; sorting by tone alone put the first
  // health row on the last block of the Checks page.
  }).sort((a,b)=>(TIER_RANK[a.tier]??9)-(TIER_RANK[b.tier]??9) || TONE_RANK[a.tone]-TONE_RANK[b.tone] || b.n-a.n || a.label.localeCompare(b.label));
}
const TIER_RANK={broken:0, runtime:1, unfinished:2, noise:3};
const TIER_LABEL={broken:'Broken', runtime:'Runtime behaviour', unfinished:'Unfinished', noise:'Unused & unproven'};
function healthListHtml(keys){
  const rows=healthRows(keys);
  if(!rows.length) return '';
  // `data-jump` is what reportNav() already understands: a block on this page scrolls into view, a block
  // on the Checks page is opened there. A row with nothing open and nothing accepted has nowhere to go.
  const row=r=>'<div class="hrow tone-'+r.tone+'"'+((r.n>0||r.w>0)?' data-jump="'+esc(r.jump)+'" role="button" tabindex="0"':'')+'>'+
    '<span class="hbar"></span><span class="hn">'+r.n+'</span>'+
    '<span class="hsev">'+esc(r.sev)+'</span>'+
    '<span class="hl">'+esc(r.label)+'</span><span class="hs">'+esc(r.sub)+'</span></div>';
  const open=rows.filter(r=>r.n>0), clean=rows.filter(r=>!r.n);
  let h='<div class="hlist">', lastTier=null;
  open.forEach(r=>{ if(r.tier!==lastTier && TIER_LABEL[r.tier]){ h+='<div class="htier">'+esc(TIER_LABEL[r.tier])+'</div>'; lastTier=r.tier; } h+=row(r); });
  if(!open.length) h+='<div class="hrow tone-ok hall"><span class="hbar"></span><span class="hn">'+uiIcon('check')+'</span><span class="hsev"></span>'+
    '<span class="hl">All '+clean.length+' checks clean</span><span class="hs">'+(clean.some(r=>r.w)?'nothing open — what was accepted is listed below':'nothing flagged in this report')+'</span></div>';
  if(clean.length) h+='<details class="hclean"><summary>'+clean.length+' check'+(clean.length>1?'s':'')+' with nothing open</summary>'+
    clean.map(row).join('')+'</details>';
  return h+'</div>';
}
/** One finding's section: heading, count, the items, and — when the finding has a browse list of its
 *  own — the button into it. Nothing at all when the count is zero, so a page lists only what it found.
 *  The section carries `id` too: the health rows' `data-jump` and the overview's hand-off land on it. */
function findingBlock(id,title,count,body,cat,extraTools){
  if(!count) return '';
  const list=cat&&CATS.some(x=>x.id===cat)
    ? '<button type="button" class="dgbtn" data-cat="'+esc(cat)+'">open the list ↗</button>' : '';
  const tools=[list, extraTools||''].filter(Boolean).join(' ');
  return section(id, esc(title), body, {count, attrs:' id="'+esc(id)+'"', tools:tools?'<div class="toolrow">'+tools+'</div>':''});
}
/** The header of a report page — the hero without the sticky bar: icon tile, title, one line saying
 *  what the page is, and the page's own numbers as facts. */
function pageHeader(o){
  const col=o.color||'var(--accent)';
  return '<div class="dhero rpt"><div class="dhero-top"><span class="dtile" style="--tc:'+col+'">'+typeIcon(o.icon||'_',{color:col})+'</span>'+
    '<div class="dhero-main"><div class="dtitle">'+esc(o.title)+'</div>'+(o.sub?'<p class="ddesc">'+o.sub+'</p>':'')+'</div></div>'+
    props(o.facts||[],{cls:'facts'})+'</div>';
}
/** What every report view needs once its HTML is in place: section memory, navigator chips, filters,
 *  copy buttons, open-in-IDE. The node-link wiring stays with the caller (each page has its own hook). */
function wireReport(v){
  v.querySelectorAll('details.sect').forEach(s=>s.addEventListener('toggle',()=>sectRemember(dec(s.dataset.sect), s.open)));
  v.querySelectorAll('[data-jump-sect]').forEach(b=>{
    const open=()=>{ const d=v.querySelector('details.sect[data-sect="'+b.dataset.jumpSect+'"]'); if(!d) return;
      d.open=true; sectRemember(dec(b.dataset.jumpSect), true); d.scrollIntoView({block:'start'}); };
    b.onclick=e=>{ e.stopPropagation(); open(); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' '){ e.preventDefault(); open(); } };
  });
  wireSectionFilter(v);
  wireCopyButtons(v);
  wireOpenButtons(v);
}
/** The click hook a report page hands [wireNodeLinks]: `data-jump` scrolls to a section of this page,
 *  `data-cat` opens a finding's browse list, `data-route` switches tab. Returning true says the click
 *  was a navigation of its own and must not also be read as a node link. */
function reportNav(e){
  const jump=e.target.closest('[data-jump]');
  if(jump){
    // A block rendered earlier on a page that is now hidden still has its id; scrolling to it would do
    // nothing visible. Only a target on the page you are looking at counts as "here".
    // The page the row is on is asked first: two pages may carry a block of the same id (the variables
    // report and the Checks page both have chk-unusedVars), and the one on screen is the one meant.
    const view=jump.closest('.view'), id=jump.dataset.jump;
    const t=(view&&view.querySelector('[id="'+cssEsc(id)+'"]'))||document.getElementById(id);
    if(t && !t.closest('.view[hidden]')){ if(t.tagName==='DETAILS'){ t.open=true; sectRemember(jump.dataset.jump, true); } t.scrollIntoView({block:'start'}); }
    else { _checkJump=jump.dataset.jump; location.hash='/checks'; }   // every finding block lives on Checks
    return true;
  }
  const cat=e.target.closest('[data-cat]');
  if(cat){ location.hash='/browse/'+enc(cat.dataset.cat); return true; }
  const route=e.target.closest('[data-route]');
  if(route){ location.hash=route.dataset.route; return true; }
}
// ---------- reference tree (#/tree) ----------
// What the explorer could not answer before: "what does this app actually start, and what does that
// reach?" Relationships were single-hop everywhere — two chip lists and a one-level neighbourhood SVG
// — so following a chain meant clicking through it and losing your place each time.
//
// Three levels of meaning, and `contains` is used only for the first:
//   1. the app, as a grouping header (plus "Outside any app", which is a finding in itself)
//   2. its functional roots — a model nothing points at except its app
//   3. everything those reach, recursively, over every other relation
//
// `contains` cannot be the spine: it is app -> model, exactly one level, so every form in an app sits
// at depth 1 and the `process -> form` edge that explains why the form exists arrives later and renders
// as a back-reference. Measured on a large real project that shape made 787 of 1319 rows back-refs.
/*__TREE_CORE_START__*/
const TREE_MAX_ROWS=20000;   // a hard stop with a banner, not a freeze: rows are bounded by roots+edges
const TREE_MAX_DEPTH=40;     // belt and braces; the deepest real chain seen is 6
const TREE_DEFAULT_DEPTH=2;  // open on first paint
// Node types that are not Design models. The `models` lens hides them, which is what "model tree"
// means; `all` shows the expressions, variables and Java the graph also holds.
const TREE_NON_MODEL=['java','method','endpoint','expression','binding','variable','string','group','serviceOperation'];
/**
 * Build the forest. Pure: it reads the adjacency maps and returns rows, so it can be tested without a
 * DOM. One row per visit — `{id, depth, rel, app, ref, cycle, kids, parents}` — where `ref:true` marks
 * a node already expanded elsewhere and `cycle:true` an edge back into the current path.
 *
 * Deduplicating on first visit (breadth-first) is what keeps this bounded: rows == roots + traversed
 * edges, exactly. Rebuilding a shared subtree per path instead is exponential — the same graph that
 * produces ~1000 rows here ran past 200000 before being stopped.
 */
function treeBuild(o){
  o=o||{};
  const lens=o.lens||'models';
  const nonModel=new Set(TREE_NON_MODEL);
  const keep=id=>{ const n=byId.get(id); if(!n) return false; return lens==='all' || !nonModel.has(n.type); };
  const out=id=>(outM.get(id)||[]).filter(e=>e.rel!=='contains' && keep(e.id));

  // App membership, and the models that belong to no app.
  const appOf=new Map(), appIds=[];
  edges.forEach(e=>{ if(e.rel==='contains' && !appOf.has(e.t)) appOf.set(e.t, e.s); });
  nodes.forEach(n=>{ if(n.type==='app') appIds.push(n.id); });

  // A functional root is a model nothing points at — app membership does not count as use, the same
  // distinction the unused-form check makes.
  const pointedAt=new Set();
  edges.forEach(e=>{ if(e.rel!=='contains' && keep(e.s) && keep(e.t)) pointedAt.add(e.t); });
  const isRoot=id=>keep(id) && !pointedAt.has(id) && (byId.get(id)||{}).type!=='app';

  const rows=[]; const owner=new Map(); let truncated=false;
  function walk(id, depth, rel, appId, path){
    if(rows.length>=TREE_MAX_ROWS){ truncated=true; return; }
    const parents=(incM.get(id)||[]).filter(e=>e.rel!=='contains').length;
    if(path.has(id)){ rows.push({id, depth, rel, app:appId, cycle:true, kids:0, parents}); return; }
    if(owner.has(id)){ rows.push({id, depth, rel, app:appId, ref:true, kids:0, parents}); return; }
    owner.set(id, rows.length);
    const kids=depth>=TREE_MAX_DEPTH?[]:out(id);
    rows.push({id, depth, rel, app:appId, kids:kids.length, parents});
    const next=new Set(path); next.add(id);
    kids.forEach(e=>walk(e.id, depth+1, e.rel, appId, next));
  }

  const groups=[];
  appIds.forEach(a=>{
    const roots=nodes.filter(n=>appOf.get(n.id)===a && isRoot(n.id)).map(n=>n.id);
    if(roots.length) groups.push({app:a, roots});
  });
  const loose=nodes.filter(n=>!appOf.has(n.id) && isRoot(n.id)).map(n=>n.id);
  if(loose.length) groups.push({app:null, roots:loose});

  groups.forEach(g=>{
    g.start=rows.length;
    g.roots.forEach(r=>walk(r, 1, null, g.app, new Set()));
    g.rowCount=rows.length-g.start;
  });
  // Anything the walk never reached — a model in a cycle no root leads into, say. Saying "0 unreachable"
  // is a claim worth being able to make, so it is counted rather than assumed.
  let unreachable=0;
  nodes.forEach(n=>{ if(keep(n.id) && n.type!=='app' && !owner.has(n.id)) unreachable++; });
  return {groups, rows, truncated, unreachable, depth:rows.reduce((m,r)=>Math.max(m,r.depth),0)};
}
/*__TREE_CORE_END__*/

/** One row. The twisty is a button so it is not read as a node link; the label is the node link, and
 *  `wireNodeLinks` gives it the same click/⌘-click behaviour every chip in the app has. */
function treeRowHtml(r, idx, openDepth){
  const n=byId.get(r.id)||{}; const t=n.type||'?';
  const hasKids=r.kids>0 && !r.ref && !r.cycle;
  const open=hasKids && r.depth<openDepth;
  const badge=[];
  if(r.cycle) badge.push('<span class="tv-b tv-cyc">cycle</span>');
  if(r.ref) badge.push('<span class="tv-b tv-ref" data-jumpto="'+esc(r.id)+'" role="button" tabindex="0">shown above</span>');
  if((n.data||{}).missingModel) badge.push('<span class="tv-b tv-miss">missing model</span>');
  if(r.parents>1 && !r.ref) badge.push('<span class="tv-b tv-par">+'+(r.parents-1)+' more parents</span>');
  return '<li role="treeitem" class="tv-row'+(hasKids?' tv-has':'')+'" data-id="'+esc(r.id)+'" data-idx="'+idx+'"'+(r.ref?' data-ref="1"':'')+
    ' aria-level="'+r.depth+'"'+(hasKids?' aria-expanded="'+(open?'true':'false')+'"':'')+' tabindex="-1">'+
    '<span class="tv-line" style="--lvl:'+r.depth+'">'+
      (hasKids?'<button type="button" class="tv-tw" aria-hidden="true" tabindex="-1"></button>':'<span class="tv-tw tv-leaf" aria-hidden="true"></span>')+
      typeIcon(t,{color:color(t)})+
      '<span class="tv-label" data-id="'+esc(r.id)+'">'+esc(n.label||r.id)+'</span>'+
      // Two models can carry the same label — a demo project has two "Review Case" — so the key is
      // shown whenever it is not already the label. Without it the tree is ambiguous exactly where it
      // matters most, on the row a reader is trying to tell apart from another one.
      (n.key && n.key!==n.label?'<span class="tv-key">'+esc(n.key)+'</span>':'')+
      findPillHtml(r.id)+
      (r.rel?'<span class="tv-rel">'+termHtml('rel', r.rel)+'</span>':'')+
      badge.join('')+
    '</span>';
}
function renderTree(){
  const v=document.getElementById('view-tree');
  const lens=state.treeLens||'models';
  const openDepth=state.treeDepth||TREE_DEFAULT_DEPTH;
  const T=treeBuild({lens});
  let body='';
  T.groups.forEach(g=>{
    const app=g.app?byId.get(g.app):null;
    const title=app?esc(app.label||app.id):'Outside any app';
    body+='<div class="tv-group"><div class="tv-gh">'+typeIcon('app',{color:color('app')})+'<b>'+title+'</b>'+
      '<span class="muted">'+g.roots.length+' root'+(g.roots.length>1?'s':'')+'</span></div>';
    body+='<ul role="tree" aria-label="'+title+'">';
    const rows=T.rows.slice(g.start, g.start+g.rowCount);
    let stack=[];
    rows.forEach((r,i)=>{
      while(stack.length && stack[stack.length-1]>=r.depth){ body+='</ul></li>'; stack.pop(); }
      body+=treeRowHtml(r, g.start+i, openDepth);
      if(r.kids>0 && !r.ref && !r.cycle){
        body+='<ul role="group"'+(r.depth<openDepth?'':' hidden')+'>';
        stack.push(r.depth);
      } else body+='</li>';
    });
    while(stack.length){ body+='</ul></li>'; stack.pop(); }
    body+='</ul></div>';
  });
  const facts=[['Roots', String(T.groups.reduce((a,g)=>a+g.roots.length,0))], ['Rows', String(T.rows.length)],
               ['Depth', String(T.depth)], ['Unreachable', String(T.unreachable)]];
  let h='<div class="dash" data-fscope>';
  h+=pageHeader({icon:'tree', color:color('process'), title:'Reference tree',
      sub:'What each app starts, and what those models reach — every relation except app membership, '+
          'followed as far as it goes', facts});
  h+='<div class="pbar fbar tv-bar">'+
     '<input class="pf" id="tvf" type="search" placeholder="filter the tree — t:form, key:…, or any word" aria-label="Filter the tree">'+
     '<span class="pchip'+(lens==='models'?' on':'')+'" data-lens="models" role="button" tabindex="0">models</span>'+
     '<span class="pchip'+(lens==='all'?' on':'')+'" data-lens="all" role="button" tabindex="0">everything</span>'+
     '<button type="button" class="dgbtn" id="tvall" aria-pressed="false">expand all</button>'+
     '<span class="pcount" id="tvcount" role="status"></span></div>';
  if(T.truncated) h+='<p class="ddesc">Stopped at '+TREE_MAX_ROWS+' rows — this graph is larger than the tree renders.</p>';
  h+=body||'<div class="estate"><div class="et">Nothing to show</div><div class="eh">No model in this project is a root.</div></div>';
  h+='</div>';
  // A lens switch rebuilds the whole view; the filter you typed and "expand all" must survive it.
  const keepF=(v.querySelector('#tvf')||{}).value||state.rf||'';
  v.innerHTML=h;
  wireTree(v);
  if(state.treeExpanded) v.querySelectorAll('.tv-row[aria-expanded]').forEach(li=>treeToggle(li, true));
  const tf=v.querySelector('#tvf');
  if(tf){
    if(keepF){ tf.value=keepF; tf.dispatchEvent(new Event('input')); }
    tf.addEventListener('input', ()=>{ state.rf=(tf.value||'').trim(); syncHashContext(); });
  }
  wireNodeLinks(v, '.tv-label', {first:treeChrome});
}
/** Clicks that belong to the tree itself rather than to the node a row names. */
function treeChrome(e){
  const tw=e.target.closest('.tv-tw:not(.tv-leaf)');
  if(tw){ treeToggle(tw.closest('.tv-row')); return true; }
  const jump=e.target.closest('[data-jumpto]');
  if(jump){
    const t=document.querySelector('.tv-row[data-id="'+cssEsc(jump.dataset.jumpto)+'"]:not([data-ref])');
    if(t){ treeReveal(t); t.scrollIntoView({block:'center'}); t.classList.add('tv-flash');
           setTimeout(()=>t.classList.remove('tv-flash'), 1200); }
    return true;
  }
}
function treeToggle(li, force){
  if(!li || li.getAttribute('aria-expanded')==null) return;
  const open = force!=null ? force : li.getAttribute('aria-expanded')!=='true';
  li.setAttribute('aria-expanded', open?'true':'false');
  const g=li.querySelector(':scope > ul[role=group]');
  if(g) g.hidden=!open;
}
/** Open every ancestor of a row, so a filter hit or a "shown above" jump lands somewhere visible. */
function treeReveal(li){
  let p=li.parentElement;
  while(p){ if(p.tagName==='UL' && p.getAttribute('role')==='group'){ p.hidden=false;
      const owner=p.closest('li.tv-row'); if(owner) owner.setAttribute('aria-expanded','true'); p=owner; }
    p=p?p.parentElement:null; }
}
function wireTree(v){
  const rows=[...v.querySelectorAll('.tv-row')];
  if(rows.length) rows[0].tabIndex=0;
  const visible=()=>rows.filter(r=>r.offsetParent!==null);
  const focus=li=>{ rows.forEach(r=>r.tabIndex=-1); li.tabIndex=0; li.focus(); };
  v.addEventListener('keydown', e=>{
    // A "shown above" badge is a stop of its own: Enter or Space follows it, and nothing else here applies.
    const jt=e.target.closest&&e.target.closest('[data-jumpto]');
    if(jt){ if(e.key==='Enter'||e.key===' '){ e.preventDefault(); jt.click(); } return; }
    const li=e.target.closest('.tv-row'); if(!li) return;
    const vis=visible(), i=vis.indexOf(li);
    const k=e.key;
    if(k==='ArrowDown'){ if(vis[i+1]) focus(vis[i+1]); }
    else if(k==='ArrowUp'){ if(vis[i-1]) focus(vis[i-1]); }
    else if(k==='ArrowRight'){ if(li.getAttribute('aria-expanded')==='false') treeToggle(li,true); else if(vis[i+1]&&+vis[i+1].getAttribute('aria-level')>+li.getAttribute('aria-level')) focus(vis[i+1]); }
    else if(k==='ArrowLeft'){ if(li.getAttribute('aria-expanded')==='true') treeToggle(li,false);
      else { const lvl=+li.getAttribute('aria-level'); for(let j=i-1;j>=0;j--) if(+vis[j].getAttribute('aria-level')<lvl){ focus(vis[j]); break; } } }
    else if(k==='Home'){ if(vis[0]) focus(vis[0]); }
    else if(k==='End'){ if(vis[vis.length-1]) focus(vis[vis.length-1]); }
    else if(k===' '){ treeToggle(li); }
    else if(k==='Enter'){ const lab=li.querySelector('.tv-label'); if(lab) lab.click(); return; }
    else return;
    e.preventDefault();
  });
  // The button's state is state, not its label: a fresh render starts folded to the default depth.
  const allBtn=v.querySelector('#tvall');
  state.treeExpanded=!!state.treeExpanded;   // kept across a re-render; the button below toggles it
  if(allBtn){
    const sync=()=>{ allBtn.setAttribute('aria-pressed', state.treeExpanded?'true':'false'); allBtn.textContent=state.treeExpanded?'collapse all':'expand all'; };
    sync();
    allBtn.onclick=()=>{ state.treeExpanded=!state.treeExpanded;
      v.querySelectorAll('.tv-row[aria-expanded]').forEach(li=>treeToggle(li, state.treeExpanded)); sync(); };
  }
  v.querySelectorAll('.pchip[data-lens]').forEach(c=>{
    const go=()=>{ state.treeLens=c.dataset.lens; try{ localStorage.setItem('atlas-tree-lens', c.dataset.lens); }catch(e){} syncHashContext(); renderTree(); };
    c.onclick=go; c.onkeydown=e=>{ if(e.key==='Enter'||e.key===' '){ e.preventDefault(); go(); } };
  });
  const f=v.querySelector('#tvf'), count=v.querySelector('#tvcount');
  if(f) f.addEventListener('input', debounce(()=>{
    const q=qParse(f.value.trim());
    const all=[...v.querySelectorAll('.tv-row')];
    if(!f.value.trim()){ all.forEach(r=>r.hidden=false); if(count) count.textContent=''; return; }
    // A row survives if it matches or an ancestor of a match: a tree that hides the path to a hit has
    // hidden the hit.
    const hit=new Set();
    all.forEach(r=>{ const n=byId.get(r.dataset.id); if(n && scoreNode(n,q)) hit.add(r); });
    all.forEach(r=>r.hidden=true);
    hit.forEach(r=>{ r.hidden=false; treeReveal(r);
      let p=r.parentElement; while(p){ const o=p.closest?p.closest('li.tv-row'):null; if(!o) break; o.hidden=false; p=o.parentElement; } });
    if(count) count.textContent=hit.size+' of '+all.length;
  }, 120));
}
// ---------- waivers: accepting a finding, and getting that decision into the file ----------
// The page can mark and un-mark; only an explicit Export (or Save, inside the IDE) writes waivers.json.
// It has to work that way: an explorer opened from the filesystem cannot read or write a neighbouring
// file, and a report silently editing a file in someone's repository would be a surprise even where it
// can. So the page holds a *diff* over what the file said when it was generated, and says how many
// changes are waiting.
const WAIVER_KEY='atlas-waivers-'+(DATA.project||'_');
const waiverId=r=>[r.check, r.node, r.element||'', r.subject||''].join(' ');
/** The local diff: rules added here, ids of file rules dropped here — each marked `saved` once a Save
 *  or Export wrote it, so the bar can count what is still only in this browser. */
function waiverLocal(){
  let l; try{ l=JSON.parse(localStorage.getItem(WAIVER_KEY)||'{}')||{}; }catch(e){ l={}; }
  l.add=Array.isArray(l.add)?l.add:[];
  l.remove=(Array.isArray(l.remove)?l.remove:[]).map(x=>typeof x==='string'?{id:x}:x);
  return l;
}
function waiverSetLocal(l){ try{ localStorage.setItem(WAIVER_KEY, JSON.stringify(l)); }catch(e){} }
/** The rules as they stand now: what the file carried, minus removals, with local additions on top —
 *  a local rule with a file rule's identity is an edit and wins. Cached until a rule changes, because
 *  waiverFor() asks for every finding on a page. */
let _wvCache=null;
function waiverRules(){
  if(_wvCache) return _wvCache;
  const loc=waiverLocal(), gone=new Set(loc.remove.map(x=>x.id)), m=new Map();
  (((DATA.waivers||{}).rules)||[]).forEach(r=>{ if(gone.has(waiverId(r))) return;
    m.set(waiverId(r), {check:r.check, node:r.node, element:r.element, subject:r.subject, reason:r.reason, by:r.by, at:r.at, until:r.until, saved:true}); });
  loc.add.forEach(r=>m.set(waiverId(r), Object.assign({}, r, {saved:!!r.saved})));
  return _wvCache=[...m.values()];
}
/** Decisions taken here that no file has yet. */
function waiverPending(){ const l=waiverLocal(); return l.add.filter(r=>!r.saved).length+l.remove.filter(x=>!x.saved).length; }
function waiverAdd(rule){
  const loc=waiverLocal(), id=waiverId(rule);
  loc.add=loc.add.filter(r=>waiverId(r)!==id); loc.add.push(Object.assign({}, rule, {saved:false}));
  loc.remove=loc.remove.filter(x=>x.id!==id);
  waiverSetLocal(loc); waiverChanged();
}
function waiverDrop(rule){
  const loc=waiverLocal(), id=waiverId(rule);
  const local=loc.add.find(r=>waiverId(r)===id);
  loc.add=loc.add.filter(r=>waiverId(r)!==id);
  // A rule the file carries — or one already saved from here — needs a tombstone so the next save drops
  // it; forgetting one that was never saved is just forgetting it.
  if(!local||local.saved){ if(!loc.remove.some(x=>x.id===id)) loc.remove.push({id, saved:false}); }
  waiverSetLocal(loc); waiverChanged();
}
/** Everything in the diff is in the file now. The decisions stay applied on the page until it is
 *  regenerated, which is when reconcile() finds them in DATA.waivers and lets the diff go. */
function waiverMarkSaved(){
  const loc=waiverLocal();
  loc.add.forEach(r=>{ r.saved=true; }); loc.remove.forEach(x=>{ x.saved=true; });
  waiverSetLocal(loc); _wvCache=null; findingsChanged();
}
/** Throw the unsaved decisions away — the second click of a two-step button. */
function waiverDiscard(){
  const loc=waiverLocal();
  loc.add=loc.add.filter(r=>r.saved); loc.remove=loc.remove.filter(x=>x.saved);
  waiverSetLocal(loc); waiverChanged();
}
/** At boot: a local addition the file now carries, or a local removal of a rule the file no longer has,
 *  is a diff that has landed — after a regeneration the bar empties itself. A saved entry the file
 *  contradicts was reverted by hand, and the file wins. */
function waiverReconcile(){
  const loc=waiverLocal(), file=new Map();
  (((DATA.waivers||{}).rules)||[]).forEach(r=>file.set(waiverId(r), r));
  const same=(a,b)=>!!b && (a.reason||'')===(b.reason||'') && (a.by||'')===(b.by||'') && (a.until||'')===(b.until||'');
  const add=loc.add.filter(r=>{ const f=file.get(waiverId(r)); return r.saved ? false : !same(r,f); });
  const remove=loc.remove.filter(x=>x.saved ? false : file.has(x.id));
  if(add.length!==loc.add.length||remove.length!==loc.remove.length){ loc.add=add; loc.remove=remove; waiverSetLocal(loc); _wvCache=null; }
}
/** A decision changed: the counts follow, the sidebar badge follows, the page re-renders, and the
 *  control the reader was on gets the focus back. */
let _wvFocus=null;
function waiverChanged(){
  // A decision re-renders the page; without a row to return to, come back to where the reader was —
  // discarding a draft or restoring a rule used to land at the top of a 300-row page.
  const view=document.querySelector('.view:not([hidden])');
  const top=view?view.scrollTop:0, winTop=window.scrollY;
  findingsChanged(); renderSidebar(); route(); renderWaiverBar();
  if(_wvFocus==null){ if(view) view.scrollTop=top; window.scrollTo(0, winTop); }
  if(_wvFocus!=null){
    const el=document.querySelector('.view:not([hidden]) [data-fi="'+_wvFocus+'"] .wv-restore, .view:not([hidden]) [data-fi="'+_wvFocus+'"] .wv-acc');
    if(el) el.focus();
    _wvFocus=null;
  }
}
/*__WAIVER_CORE_START__*/
// The file, byte for byte as :core's Waivers.serialize writes it — two writers exist for this format,
// and the moment they disagree on a key or the sort order the file churns in every diff. Pure: no DOM,
// no DATA; scripts/waiver-selftest.mjs slices this block out and runs it against the Kotlin writer.
// Sort keys compare by UTF-16 code unit, as Kotlin's String.compareTo does — not by locale.
function waiverSerialize(rules, notes, atlasVersion, createdWith){
  const cmp=(a,b)=>a<b?-1:a>b?1:0;
  const wkey=r=>[r.check, r.node, r.element||'', r.subject||''].join(' ');
  const nkey=n=>[n.node, n.check||'', n.element||'', n.subject||'', n.text].join(' ');
  const waivers=(rules||[]).slice().sort((a,b)=>cmp(wkey(a),wkey(b))).map(r=>{
    const o={check:r.check, node:r.node};
    if(r.element) o.element=r.element;
    if(r.subject) o.subject=r.subject;
    o.reason=r.reason||'';
    if(r.by) o.by=r.by;
    if(r.at) o.at=r.at;
    if(r.until) o.until=r.until;
    return o;
  });
  const ns=(notes||[]).slice().sort((a,b)=>cmp(nkey(a),nkey(b))).map(n=>{
    const o={node:n.node};
    if(n.check) o.check=n.check;
    if(n.element) o.element=n.element;
    if(n.subject) o.subject=n.subject;
    o.text=n.text;
    o.importance=n.importance||'normal';
    if(n.by) o.by=n.by;
    if(n.at) o.at=n.at;
    return o;
  });
  return JSON.stringify({version:1, createdWith:createdWith||atlasVersion||'', updatedWith:atlasVersion||'',
    waivers, notes:ns}, null, 2)+'\n';
}
/*__WAIVER_CORE_END__*/
function waiverFileText(){
  const W=DATA.waivers||{};
  return waiverSerialize(waiverRules(), W.notes||[], DATA.atlasVersion||'', W.createdWith);
}
/** Save inside the IDE, download everywhere else. The IDE answers through __atlasWaiversSaved, because a
 *  write can fail and a bar that says "saved" over a file that is not is the one lie this must not tell;
 *  a download cannot report back, so it is taken as done. */
function waiverExport(){
  const text=waiverFileText();
  if(window.__atlasSaveWaivers){
    // …with what this page started from, so the IDE can keep a rule the file gained since generation
    const base=(((DATA.waivers||{}).rules)||[]).map(waiverId).concat((((DATA.waivers||{}).notes)||[]).map(n=>[n.node,n.check||'',n.element||'',n.subject||'',n.text].join(' ')));
    window.__atlasSaveWaivers(JSON.stringify({text, base})); return;
  }
  const a=document.createElement('a');
  a.href=URL.createObjectURL(new Blob([text], {type:'application/json'}));
  a.download='waivers.json'; document.body.appendChild(a); a.click();
  setTimeout(()=>{ URL.revokeObjectURL(a.href); a.remove(); }, 0);
  waiverMarkSaved(); renderWaiverBar();
  toast('waivers.json exported — put it beside the report and regenerate');
}
window.__atlasWaiversSaved=function(ok){
  if(ok){ waiverMarkSaved(); toast('Saved to waivers.json'); }
  else toast('Could not save waivers.json — see the IDE notification');
  renderWaiverBar();
};
/** The bar under the top bar, on every view: what is unsaved and the one button that writes it, or
 *  what was saved and is waiting for a regeneration. Hidden when there is nothing to say. */
function renderWaiverBar(){
  const bar=document.getElementById('wvbar'); if(!bar) return;
  const loc=waiverLocal(), pend=waiverPending();
  const savedN=loc.add.filter(r=>r.saved).length+loc.remove.filter(x=>x.saved).length;
  if(!pend && !savedN){ bar.hidden=true; bar.innerHTML=''; bar.classList.remove('saved'); return; }
  bar.hidden=false;
  const ide=!!window.__atlasSaveWaivers;
  if(pend){
    const adds=loc.add.filter(r=>!r.saved).length, rems=loc.remove.filter(x=>!x.saved).length;
    bar.classList.remove('saved');
    bar.innerHTML='<b>'+pend+' unsaved decision'+(pend>1?'s':'')+'</b>'+
      '<span class="wvb-d">'+[adds?adds+' accepted':'', rems?rems+' restored':''].filter(Boolean).join(' · ')+
      ' — applied on this page, not yet in waivers.json</span><span class="wvb-sp"></span>'+
      '<button type="button" class="dgbtn" id="wv-save">'+(ide?'Save to waivers.json':'Export waivers.json')+'</button>'+
      '<button type="button" class="dgbtn wvb-discard" id="wv-discard">discard</button>';
    bar.querySelector('#wv-save').onclick=waiverExport;
    const d=bar.querySelector('#wv-discard');
    d.onclick=()=>{ if(d.dataset.arm){ waiverDiscard(); return; }
      d.dataset.arm='1'; d.textContent='discard '+pend+'? click again';
      setTimeout(()=>{ if(d.isConnected){ delete d.dataset.arm; d.textContent='discard'; } }, 4000); };
  } else {
    bar.classList.add('saved');
    bar.innerHTML='<b>'+savedN+' decision'+(savedN>1?'s':'')+' saved</b><span class="wvb-d">'+
      (ide?'regenerating the explorer — the counts, badges and the CI gate follow'
          :'put waivers.json beside the report and regenerate — the counts, badges and the CI gate follow')+'</span>';
  }
}
window.addEventListener('atlas-ide-bridge', renderWaiverBar);
// ---------- accepting a finding: the form, and the findings a model carries ----------
/** The form under a finding's row: a reason (required — the only part a reviewer can review), an
 *  optional expiry, the author, and — when the finding names an element or a subject — whether the rule
 *  covers this finding alone or every finding of the check on the model. Prefilled from [rule] to edit. */
function acceptFormHtml(f, rule){
  const id='wv'+f.fi, r=rule||{}, scoped=!!(f.element||f.subject);
  const same=scoped?FINDS.filter(x=>x.check===f.check&&(x.node||x.file)===(f.node||f.file)).length:0;
  const cat=(DATA.checkCatalog||[]).find(c=>c.id===f.check)||{title:f.check};
  const narrow=rule?!!(r.element||r.subject):true;
  return '<form class="wv-form" data-fi="'+f.fi+'" novalidate>'+
    '<div class="wv-fld wv-grow"><label for="'+id+'-r">Why is this acceptable?</label>'+
      '<input id="'+id+'-r" class="wv-in" required aria-required="true" aria-describedby="'+id+'-e" value="'+esc(r.reason||'')+
      '" placeholder="e.g. resolved at deploy time from the shared repository">'+
      '<span id="'+id+'-e" class="wv-err" role="alert" hidden></span></div>'+
    '<div class="wv-fld"><label for="'+id+'-u">until <span class="muted">optional</span></label>'+
      '<input id="'+id+'-u" class="wv-in wv-until" type="date" value="'+esc(r.until||'')+'"></div>'+
    '<div class="wv-fld"><label for="'+id+'-b">by</label><input id="'+id+'-b" class="wv-in wv-by" value="'+esc(r.by||DATA.waiverAuthor||'')+'"></div>'+
    (scoped?'<fieldset class="wv-scope"><legend>Covers</legend>'+
      '<label><input type="radio" name="'+id+'-s" value="one"'+(narrow?' checked':'')+'> this finding only</label>'+
      '<label><input type="radio" name="'+id+'-s" value="all"'+(narrow?'':' checked')+'> every '+esc(cat.title)+' finding on this model'+
      (same>1?' <span class="muted">('+same+')</span>':'')+'</label></fieldset>':'')+
    '<div class="wv-act"><button type="submit" class="dgbtn">'+(rule?'save the change':'accept')+'</button>'+
    '<button type="button" class="dgbtn wv-cancel">cancel</button></div></form>';
}
/** Wire every accept control under [root]: the buttons that open a row's form, the forms themselves,
 *  and the edit/restore controls on an accepted row. Validation speaks: an empty reason or a past
 *  expiry gets a sentence in a live region, not a red border. */
function wireAccept(root){
  const openRow=b=>{ const row=b.closest('details.tr'); if(!row) return; row.open=true;
    const inp=row.querySelector('.wv-form input.wv-in[required]'); if(inp){ inp.focus(); inp.select(); } };
  root.querySelectorAll('.wv-acc, .wv-edit').forEach(b=>b.onclick=e=>{ e.preventDefault(); e.stopPropagation(); openRow(b); });
  root.querySelectorAll('.wv-restore').forEach(b=>b.onclick=e=>{ e.preventDefault(); e.stopPropagation();
    const f=FINDS[+b.dataset.fi], rule=f&&waiverFor(f); if(rule){ _wvFocus=f.fi; waiverDrop(rule); } });
  root.querySelectorAll('.wv-form').forEach(form=>{
    const f=FINDS[+form.dataset.fi]; if(!f) return;
    const reason=form.querySelector('input.wv-in[required]'), err=form.querySelector('.wv-err');
    const until=form.querySelector('.wv-until'), by=form.querySelector('.wv-by');
    const fail=(el,msg)=>{ el.setAttribute('aria-invalid','true'); err.textContent=msg; err.hidden=false; el.focus(); };
    const clear=()=>{ reason.removeAttribute('aria-invalid'); until.removeAttribute('aria-invalid'); err.hidden=true; err.textContent=''; };
    reason.addEventListener('input', clear); until.addEventListener('input', clear);
    const submit=()=>{
      const why=(reason.value||'').trim();
      if(!why){ fail(reason, 'A reason is required — it is the only part of a waiver a reviewer can review.'); return; }
      const u=(until.value||'').trim();
      if(u && (!/^\d{4}-\d{2}-\d{2}$/.test(u) || u<=todayIso())){ fail(until, 'until must be a day after today, as YYYY-MM-DD.'); return; }
      const scope=form.querySelector('input[type=radio]:checked'), one=!scope||scope.value==='one';
      const rule={check:f.check, node:f.node||f.file, reason:why};
      if(one&&f.element) rule.element=f.element;
      if(one&&f.subject) rule.subject=f.subject;
      const who=(by.value||'').trim(); if(who) rule.by=who;
      rule.at=todayIso();
      if(u) rule.until=u;
      // An edit that widens a narrowed rule leaves the narrow one behind; drop it first so the file
      // does not carry both.
      const before=waiverFor(f); if(before && waiverId(before)!==waiverId(rule)) waiverDrop(before);
      _wvFocus=f.fi; waiverAdd(rule);
    };
    form.onsubmit=e=>{ e.preventDefault(); e.stopPropagation(); submit(); };
    form.querySelector('.wv-cancel').onclick=e=>{ e.preventDefault(); e.stopPropagation(); clear();
      const row=form.closest('details.tr'); if(row) row.open=false; };
    // Keys inside the form are the form's: Enter submits, nothing bubbles to a row, a list or the tree.
    form.onkeydown=e=>{ e.stopPropagation();
      if(e.key==='Enter'&&e.target.tagName==='INPUT'&&e.target.type!=='radio'){ e.preventDefault(); submit(); } };
    form.onclick=e=>e.stopPropagation();
  });
}
/** The findings a model carries, grouped by check with the catalog's explanation, each row with its
 *  accept control — the one place a reader has the context to judge one, and now also the diagram
 *  beside it. Nothing at all for a model with nothing to say. */
function nodeFindingsHtml(n){
  const all=FIND_BY_NODE.get(n.id)||[]; if(!all.length) return '';
  const open=all.filter(f=>!waiverFor(f)), acc=all.length-open.length;
  const byCheck={}; all.forEach(f=>{ (byCheck[f.check]=byCheck[f.check]||[]).push(f); });
  let body='<p class="ddesc">Accepting one keeps it in the report, in its own section, and out of the counts and '+
    'the CI gate. Nothing is written until you save — the bar at the top says what is still unsaved.</p>';
  checksInOrder().forEach(c=>{ const rows=byCheck[c.id]; if(!rows) return;
    const sevs=[...new Set(rows.filter(f=>!waiverFor(f)).map(f=>f.severity||'warning'))].sort();
    body+='<div class="chk-head"><div class="chk-title">'+esc(c.title)+' '+sevs.map(sevPill).join(' ')+'</div>'+
      (c.what?'<p class="ddesc">'+esc(c.what)+'</p>':'')+
      ((c.why||c.fix||c.docs)?'<details class="chk-more"><summary>why it matters · what to do</summary>'+
        (c.why?'<p>'+esc(c.why)+'</p>':'')+(c.fix?'<p>'+esc(c.fix)+'</p>':'')+
        (c.docs?'<a class="dgbtn" href="'+esc(c.docs)+'" target="_blank" rel="noopener">read the docs ↗</a>':'')+'</details>':'')+
      '</div>'+findingTable(rows, {onNode:true});
  });
  return section('findings','Findings on this model', body, {count:open.length, hint:acc?acc+' accepted':'', attrs:' id="findings"'});
}
/** What the project decided to live with, rule by rule — the Checks page's last section. Rendered from
 *  the rules as they stand now (the file's, minus what was restored here, plus what was accepted here),
 *  so a decision taken a moment ago is already in the table and one taken back is already gone. A
 *  rule's troubles — matched nothing, expired, no reason, a model that is gone — sit on its own row:
 *  the file's health is its rows' health, not a list beside them. */
const ruleCovers=(r,f)=>r.check===f.check && r.node===(f.node||f.file) &&
  (!r.element||r.element===f.element) && (!r.subject||r.subject===f.subject);
function waivedBlockHtml(){
  const W=DATA.waivers||{}, rules=waiverRules();
  let out='';
  if(rules.length){
    const rows=rules.map((r,i)=>{
      const hits=FINDS.filter(f=>ruleCovers(r,f)).length, expired=waiverExpired(r);
      const known=byId.has(r.node)||FIND_BY_NODE.has(r.node);
      const states=[];
      if(!r.saved) states.push('<span class="pill pill-info">unsaved</span>');
      if(expired) states.push('<span class="pill pill-bad">expired</span>');
      else if(!hits) states.push('<span class="pill pill-warn">matched nothing</span>');
      if(!r.reason) states.push('<span class="pill pill-warn">no reason</span>');
      return {hay:elHay(r.check, r.node, r.element, r.subject, r.reason, r.by), attrs:' data-wi="'+i+'"',
        cls:(expired||!hits||!r.reason)?'cov-warn':'',
        cells:{check:tag(r.check),
          model:(byId.get(r.node)?nodeChip(r.node):'<span class="mono">'+esc(r.node)+'</span>')+
                (known?'':' <span class="pill pill-bad">missing</span>'),
          scope:[r.element, r.subject].filter(Boolean).map(esc).join(' · ')||'<span class="muted">whole model</span>',
          why:r.reason?esc(r.reason):'<i class="muted">no reason given</i>',
          who:[r.by?esc(r.by):'', r.at?esc(r.at):'', r.until?'until '+esc(r.until):''].filter(Boolean).join(' · '),
          state:states.join(' ')+(hits?'<span class="muted"> '+hits+' finding'+(hits>1?'s':'')+'</span>':''),
          act:'<button type="button" class="dgbtn wv-rule-restore" data-wi="'+i+'">restore</button>'}};
    });
    out+=section('chk-waived','Deliberately accepted',
      '<p class="ddesc">One row per rule in waivers.json — what it covers, why, who decided, and how many findings it matched '+
      'this run. A rule that matches nothing, has run out, or never said why is marked on its row.</p>'+
      tbl([{k:'check',label:'Check',w:'minmax(10ch,.9fr)',cls:'tags'},
           {k:'model',label:'Model',w:'minmax(12ch,1.4fr)'},
           {k:'scope',label:'Element · subject',w:'minmax(10ch,1fr)',mono:true,opt:true},
           {k:'why',label:'Accepted because',w:'minmax(20ch,2.6fr)',cls:'wrap'},
           {k:'who',label:'By · when',w:'minmax(10ch,1fr)',opt:true},
           {k:'state',label:'',w:'minmax(9ch,.9fr)',cls:'tags'},
           {k:'act',label:'',w:'minmax(7ch,.5fr)',cls:'tags wv-cell'}], rows, {filter:false}),
      {count:rules.length, attrs:' id="chk-waived"'});
  }
  // The one thing left that is about the file rather than a rule: it could not be fully read.
  const problems=W.problems||[];
  if(problems.length){
    out+=section('chk-waiver-health','Waiver file could not be fully read',
      '<ul class="varwhy">'+problems.map(n=>'<li>'+esc(n)+'</li>').join('')+'</ul>', {count:problems.length, attrs:' id="chk-waiver-health"'});
  }
  const notes=W.notes||[];
  if(notes.length){
    const imp=n=>'<span class="pill '+(n.importance==='high'?'pill-bad':n.importance==='low'?'':'pill-info')+'">'+esc(n.importance||'normal')+'</span>';
    out+=section('chk-waiver-notes','Review notes',
      tbl([{k:'model',label:'Model',w:'minmax(12ch,1.4fr)'},{k:'imp',label:'',w:'minmax(7ch,.6fr)',cls:'tags'},
           {k:'text',label:'Note',w:'minmax(24ch,3fr)',cls:'wrap'},
           {k:'scope',label:'Check · element · subject',w:'minmax(10ch,1fr)',mono:true,opt:true},
           {k:'who',label:'By · when',w:'minmax(10ch,1fr)',opt:true}],
        notes.map(n=>({hay:elHay(n.node, n.text, n.check, n.by), cells:{
          model:byId.get(n.node)?nodeChip(n.node):'<span class="mono">'+esc(n.node)+'</span>', imp:imp(n), text:esc(n.text),
          scope:[n.check, n.element, n.subject].filter(Boolean).map(esc).join(' · '),
          who:[n.by, n.at].filter(Boolean).map(esc).join(' · ')}})), {filter:false}),
      {count:notes.length, attrs:' id="chk-waiver-notes"', hint:'remarks that change no count, kept with the project so the next reader finds them'});
  }
  return out;
}
// ---------- the findings themselves ----------
const FIND_CAP=200;
const sevPill=s=>'<span class="pill '+(s==='error'?'pill-bad':'pill-warn')+'">'+esc(s||'warning')+'</span>';
const fileBase=p=>String(p||'').split('/').pop();
const SHOWACC_KEY='atlas-chk-showacc';
function showAccepted(){ try{ return localStorage.getItem(SHOWACC_KEY)==='1'; }catch(e){ return false; } }
/** What a rule says about the finding it covers: the reason, who, until when — and whether it is saved. */
function acceptedNoteHtml(rule){
  return '<div class="wv-why"><span class="pill pill-ok">accepted</span> '+
    (rule.reason?esc(rule.reason):'<i>no reason given</i>')+
    (rule.by?' <span class="muted">· '+esc(rule.by)+'</span>':'')+
    (rule.until?' <span class="muted">· until '+esc(rule.until)+'</span>':'')+
    (rule.saved===false?' <span class="pill pill-info">unsaved</span>':'')+'</div>';
}
const FIND_COLS=[
  {k:'sev',label:'',w:'minmax(7ch,.55fr)',cls:'tags'},
  {k:'model',label:'Model',w:'minmax(12ch,1.3fr)'},
  {k:'el',label:'Element',w:'minmax(10ch,1fr)',opt:true},
  {k:'msg',label:'Finding',w:'minmax(24ch,3fr)',cls:'wrap'},
  {k:'where',label:'File',w:'minmax(10ch,1fr)',mono:true,opt:true},
  {k:'act',label:'',w:'minmax(9ch,.8fr)',cls:'tags wv-cell'},
];
/** One finding as a table row: severity as a word, the model as a chip, the element as a jump into the
 *  model (or, on the model's own page, a locate-on-diagram button), the message, the file with the
 *  open-in-IDE button, and the accept control. The row's body is the accept form, so the decision is
 *  taken where the finding is read. */
function findingRow(f, o){
  o=o||{};
  const n=byId.get(f.node), rule=waiverFor(f);
  const names=n?elementNames(n):null, el=(f.element&&names)?names.get(String(f.element)):null;
  const elLabel=(el&&el.name)||f.element||'';
  const elCell=!f.element ? ((f.subject&&f.check!=='invalidExpr'&&f.check!=='suspectExpr')?'<span class="mono muted">'+esc(f.subject)+'</span>':'')
    : !n ? '<span class="mono">'+esc(f.element)+'</span>'
    : o.onNode ? locateBtn(f.element, elLabel)+' '+esc(elLabel)
    : elJumpHtml(f.node, f.element, elLabel, 'Open this element in its model');
  return {hay:elHay(f.label, f.message, f.element, elLabel, f.subject, f.check, f.severity, n?nodeKind(n):'', f.file),
    attrs:' data-sev="'+esc(f.severity||'warning')+'" data-fi="'+f.fi+'"', cls:rule?'wv-done':'',
    body:acceptFormHtml(f, rule), bodyCls:'wv-body',
    cells:{
      sev:sevPill(f.severity),
      model:n?nodeChip(f.node):'<span class="mono">'+esc(f.node||f.label||'')+'</span>',
      el:elCell,
      msg:esc(f.message)+(f.snippet?' <span class="mono muted">'+esc(f.snippet)+'</span>':'')+(rule?acceptedNoteHtml(rule):''),
      where:f.file?'<span class="fp">'+esc(fileBase(f.file))+'</span>'+lineRef(f.file,f.line)+openBtn(f.file,f.line):'',
      act:rule?'<button type="button" class="dgbtn wv-edit" data-fi="'+f.fi+'">edit</button><button type="button" class="dgbtn wv-restore" data-fi="'+f.fi+'">restore</button>'
              :'<button type="button" class="dgbtn wv-acc" data-fi="'+f.fi+'">accept…</button>',
    }};
}
function findingTable(rows, o){
  o=o||{};
  const cols=o.onNode?FIND_COLS.filter(c=>c.k!=='model'):FIND_COLS;
  return tbl(cols, rows.map(f=>findingRow(f,o)), {filter:false, more:o.more});
}
/** One check's block: the catalog's explanation, the open findings, and the accepted ones folded under
 *  them. Rendered whenever there is anything at all — a check whose every finding was accepted keeps its
 *  block, because "what did we agree to carry" is a question the page has to keep answering. */
function checkBlockHtml(c, all){
  const open=all.filter(f=>!waiverFor(f)), acc=all.filter(f=>waiverFor(f));
  if(!open.length && !acc.length) return '';
  const m=metaOf(c.id);
  const sevs=[...new Set(open.map(f=>f.severity||'warning'))].sort();
  const head='<div class="chk-head">'+
    (sevs.length?'<span class="chk-sev">'+sevs.map(sevPill).join(' ')+'</span>':'')+
    (c.what?'<p class="ddesc">'+esc(c.what)+'</p>':'')+
    ((c.why||c.fix||c.docs)?'<details class="chk-more"><summary>why it matters · what to do</summary>'+
      (c.why?'<p>'+esc(c.why)+'</p>':'')+(c.fix?'<p>'+esc(c.fix)+'</p>':'')+
      (c.docs?'<a class="dgbtn" href="'+esc(c.docs)+'" target="_blank" rel="noopener">read the docs ↗</a>':'')+'</details>':'')+
    '</div>';
  const tools=[m.cat&&CATS.some(x=>x.id===m.cat)?'<button type="button" class="dgbtn" data-cat="'+esc(m.cat)+'">open the list ↗</button>':'',
               m.route?routeBtn(m.route):''].filter(Boolean).join(' ');
  let body=head+findingTable(open.slice(0,FIND_CAP), {more:open.length>FIND_CAP?'showing '+FIND_CAP+' of '+open.length+' — narrow the filter, or open the list':''});
  if(acc.length) body+='<details class="chk-acc"'+(showAccepted()?' open':'')+'><summary>'+acc.length+' accepted — kept in the report, out of the counts and the gate</summary>'+
    findingTable(acc)+'</details>';
  return section('chk-'+c.id, esc(c.title), body, {count:open.length, hint:acc.length?acc.length+' accepted':'',
    attrs:' id="chk-'+esc(c.id)+'"', tools:tools?'<div class="toolrow">'+tools+'</div>':''});
}
function renderChecks(){
  const v=document.getElementById('view-checks');
  const st=DATA.stats||{}, C=findingCounts();
  _sectReg=[];
  let b='';
  const byCheck={}; FINDS.forEach(f=>{ (byCheck[f.check]=byCheck[f.check]||[]).push(f); });
  checksInOrder().forEach(c=>{ b+=checkBlockHtml(c, byCheck[c.id]||[]); });
  // uncertain edges are a property of the graph, not of one node — say so once
  const suN=st.suspectEdges||0, dyN=st.dynamicEdges||0;
  if(suN+dyN){
    b+=section('chk-uncertain','Uncertain links','<p class="ddesc">'+
       (suN?suN+' suspect (≈ resolved by a loose or cross-type match)':'')+(suN&&dyN?' · ':'')+
       (dyN?dyN+' dynamic (ƒ expression-valued reference)':'')+' — the ≈ button in the toolbar hides them everywhere.</p>',
       {count:suN+dyN, attrs:' id="chk-uncertain"'});
  }
  // What this project decided to live with, rule by rule. Its own section, not a strike-through in the
  // blocks above: "what is wrong" and "what did we agree to carry, and why" are two different questions,
  // and the second one is worth nothing without its reasons.
  b+=waivedBlockHtml();
  _sectReg=null;   // the health list above is this page's navigator; a second strip repeated it (0.25.0)
  let h='<div class="dash" data-fscope>';
  h+=pageHeader({icon:'checks', color:color('checks'), title:'Checks', sub:C.openN
       ? C.openN+' finding'+(C.openN>1?'s':'')+' worth a look — none of them is automatically a bug, each one is a question Atlas cannot answer on its own'+
         (C.waivedN?' · '+C.waivedN+' already accepted':'')
       : C.waivedN ? 'nothing open — '+C.waivedN+' finding'+(C.waivedN>1?'s':'')+' deliberately accepted, listed below with the reasons'
       : 'nothing flagged — no parse issues, no broken expressions, nothing unused or unproven'});
  h+=healthListHtml();
  // the URL's `&a=` wins over the remembered preference, so a copied link shows what its author saw
  const accShown=()=>state.acc==='1'?true:state.acc==='0'?false:showAccepted();
  if(C.openN||C.waivedN){
    const errN=FINDS.filter(f=>f.severity==='error'&&!waiverFor(f)).length;
    h+=filterBar({placeholder:'filter findings — a model, an element, a word of the message…', label:'Filter findings',
      chips:[{fk:'sev',fv:'all',label:'all',n:C.openN},{fk:'sev',fv:'error',label:'error',n:errN},{fk:'sev',fv:'warning',label:'warning',n:C.openN-errN}],
      extra:C.waivedN?'<button type="button" class="pchip'+(accShown()?' on':'')+'" id="chk-showacc" aria-pressed="'+(accShown()?'true':'false')+
        '">show accepted<span class="pchipn">'+C.waivedN+'</span></button>':''});
  }
  h+=b;
  if(!C.openN && !C.waivedN) h+='<div class="estate"><div class="estate-ic" aria-hidden="true">✓</div>'+
    '<div class="et">Nothing to check</div>'+
    '<div class="eh">No parse issue, no flagged expression, no unused or unresolved model.</div></div>';
  h+='</div>';
  v.innerHTML=h;
  wireReport(v);
  wireAccept(v);
  v.querySelectorAll('.wv-rule-restore').forEach(b=>b.onclick=e=>{ e.preventDefault(); e.stopPropagation();
    const r=waiverRules()[+b.dataset.wi]; if(r) waiverDrop(r); });
  const sa=v.querySelector('#chk-showacc');
  if(sa) sa.onclick=()=>{ const on=sa.getAttribute('aria-pressed')!=='true';
    sa.setAttribute('aria-pressed', on?'true':'false'); sa.classList.toggle('on', on);
    try{ localStorage.setItem(SHOWACC_KEY, on?'1':'0'); }catch(e){}
    state.acc=on?'1':'0'; syncHashContext();
    v.querySelectorAll('details.chk-acc').forEach(d=>{ d.open=on; }); };
  if(accShown()) v.querySelectorAll('details.chk-acc').forEach(d=>{ d.open=true; });
  wireNodeLinks(v, '[data-goto],[data-id]', {first:reportNav});
  // arrived from a health row or the parse-issue chip: land on the block it asked for
  if(_checkJump){
    const target=document.getElementById(_checkJump);
    _checkJump=null;
    if(target){ if(target.tagName==='DETAILS') target.open=true; requestAnimationFrame(()=>target.scrollIntoView({block:'start'})); }
  }
}

// ---------- unused variables view (#/variables) ----------
// Every variable something writes and nothing reads, with the write to delete. The graph knows where
// each name is read and written (`writes`/`reads` on a variable node, computed in :core); this page is
// what makes that answerable without opening a single model.
//
// The caveat block at the bottom is not decoration. This check can only ever say "nothing *in these
// models* reads it", and a reader who does not know what Atlas cannot see would take it for more.

/** Design's word for a write/read site, with the element it happens on — `Script · Stamp order`. */
function varSiteLabel(s, varName){
  const where=s.elementName||s.element;
  // A DMN output's element id *is* the variable name; repeating it says nothing.
  const show=(where!=null&&String(where)!==varName) ? String(where) : '';
  return termHtml('via', s.via)+(show?'<span class="opid">'+esc(show)+'</span>':'');
}

/** One row per variable: name, how it is written, where, and the read/write tally. */
function varRow(n, opts){
  const d=n.data||{};
  const writes=d.writes||[];
  // The models a write happens in, deduped — a variable written by three script tasks of one process
  // should say that process once.
  const models=[...new Set(writes.map(w=>w.model))];
  const jumps=writes.filter(w=>w.element).slice(0,4)
    .map(w=>elJumpHtml(w.model, w.element, w.elementName||w.element, 'Open this element in its model')).join(' ');
  const vias=[...new Set(writes.map(w=>w.via))];
  const hay=[n.label, vias.map(x=>term('via',x).label).join(' '),
    models.map(m=>(byId.get(m)||{}).label||m).join(' '),
    (d.unreadIn||[]).map(m=>(byId.get(m)||{}).label||m).join(' ')].join(' ');
  return {hay, attrs:' data-varrow data-via="'+esc(vias[0]||'')+'"', cells:{
    name:vlink(n.id, n.label), via:writes.map(w=>varSiteLabel(w, n.label)).join(' '),
    model:models.map(m=>nodeChip(m)).join(''), el:jumps,
    never:(opts&&opts.callee)?(d.unreadIn||[]).map(m=>nodeChip(m)).join(''):'',
    tally:'<span class="vw">'+(d.writeCount||0)+' written</span> · <span class="vr">'+(d.readCount||0)+' read</span>'}};
}
const VAR_COLS=[{k:'name',label:'Variable',w:'minmax(12ch,1.2fr)',mono:true},{k:'via',label:'Written via',w:'minmax(12ch,1.2fr)',cls:'tags',opt:true},
  {k:'model',label:'Model',w:'minmax(14ch,1.6fr)',cls:'tags'},{k:'el',label:'Element',w:'minmax(10ch,1.2fr)',opt:true},{k:'tally',label:'',w:'minmax(12ch,1fr)',cls:'num faint',opt:true}];
const VAR_COLS_CALLEE=VAR_COLS.slice(0,4).concat([{k:'never',label:'Never read in',w:'minmax(14ch,1.6fr)',cls:'tags'}], VAR_COLS.slice(4));

function renderVariables(){
  const v=document.getElementById('view-variables');
  // Sorted once: the three lists below are subsets of this one and inherit its order.
  const vars=nodes.filter(n=>n.type==='variable').sort((a,b)=>a.label.localeCompare(b.label));
  const unread=vars.filter(n=>(n.data||{}).unread===true);
  const unreadIn=vars.filter(n=>((n.data||{}).unreadIn||[]).length>0);
  // Declared, but with no direction Atlas can prove — an app variable, a data-object column, an
  // extracted variable. Their readers are the Work UI, a query or a dashboard, so they are listed as
  // what they are rather than accused of being unused.
  const declared=vars.filter(n=>!(n.data||{}).writeCount&&!(n.data||{}).readCount);
  const open=unread.length+unreadIn.length;
  const total=INSIGHTS.totalDirectedVars, silent=INSIGHTS.silentVars;
  _sectReg=[];
  let b='';
  b+=findingBlock('chk-unusedVars','Written, never read', unread.length, tbl(VAR_COLS, unread.map(n=>varRow(n)), {filter:false}));
  b+=findingBlock('chk-unreadInputs','Mapped into a model that never reads it', unreadIn.length, tbl(VAR_COLS_CALLEE, unreadIn.map(n=>varRow(n,{callee:true})), {filter:false}));
  b+=findingBlock('chk-declaredvars','Declared — readers live outside the models', declared.length,
    tbl([{k:'name',label:'Variable',w:'minmax(12ch,1fr)',mono:true},{k:'u',label:'Declared by',w:'minmax(16ch,3fr)',cls:'tags'}],
      declared.map(n=>({hay:n.label, cells:{name:vlink(n.id, n.label), u:((n.data||{}).usedBy||[]).map(m=>nodeChip(m)).join('')}})), {filter:false})+
    '<p class="ddesc">An app variable, a data-object column or an extracted variable is read by the Work UI, a query or a dashboard — '+
    'none of which Atlas parses. They are listed here rather than reported, because "unused" would be a guess.</p>');
  // What this page cannot know. The count of names Atlas declined to judge is the honest denominator of
  // everything above it, and the reason the rows above can be trusted.
  b+=section('chk-varcaveat','What Atlas cannot see',
     '<p class="ddesc">Atlas stayed silent about <strong>'+silent+'</strong> further variable'+(silent===1?'':'s')+
     ' it would otherwise have listed, because it saw one of these:</p>'+
     '<ul class="varwhy">'+(DATA.silenceRules||[]).map(r=>'<li>'+esc(r)+'</li>').join('')+'</ul>'+
     '<p class="ddesc">Query, dashboard and master-data models are not analysed for variable references, and the Work UI or any REST client '+
     'is outside this project. A variable listed above is one that nothing <em>in these models</em> reads — not proof that nothing anywhere does.</p>',
     {attrs:' id="chk-varcaveat"', hint:'the limits of the verdict above'});
  const reg=_sectReg; _sectReg=null;
  let h='<div class="dash" data-fscope>';
  h+=pageHeader({icon:'variable', color:color('variable'), title:'Unused variables', sub:open
       ? open+' of '+total+' variable'+(total>1?'s':'')+' worth a look — something writes them and nothing Atlas can see reads them back'
       : 'nothing flagged — every variable that is written is read somewhere in these models'});
  // Only the two checks this page has blocks for. The script-guess card belongs to the Checks tab: its
  // `jump` names a block that does not exist here, so showing it would be a card that does nothing.
  h+=healthListHtml(['unusedVars','unreadInputs']);
  if(open){
    // one filter row over both blocks: the write construct, and free text over names and models. A chip
    // selects on the row's *first* write construct — the one varRow puts in `data-via` — so that is what
    // its count has to be, or the number would promise rows the filter does not show.
    const flagged=unread.concat(unreadIn);
    const perVia=new Map();
    flagged.forEach(n=>{ const first=(((n.data||{}).writes||[])[0]||{}).via; if(first) perVia.set(first,(perVia.get(first)||0)+1); });
    const vias=[...new Set(flagged.flatMap(n=>((n.data||{}).writes||[]).map(w=>w.via)))].filter(x=>perVia.get(x));
    h+='<div class="pagebar">'+filterBar({placeholder:'filter variables — name, model…', label:'Filter unused variables', total:open,
      chips:[{fk:'via',fv:'all',label:'All',n:open}].concat(vias.map(x=>({fk:'via',fv:x,label:term('via',x).label,n:perVia.get(x)})))})+'</div>';
  }
  h+=secnavHtml(reg)+b;
  if(!open) h+='<div class="estate"><div class="estate-ic" aria-hidden="true">✓</div>'+
       '<div class="et">Nothing written and forgotten</div>'+
       '<div class="eh">Every variable a model writes is read somewhere — by an expression, a script, a form field, a decision or a called model.</div></div>';
  h+='</div>';
  v.innerHTML=h;
  wireReport(v);
  // The health cards of this page jump within it, so there is no `_checkJump` hand-off to honour here:
  // the only route that sets one is `/checks`, which consumes it itself.
  wireNodeLinks(v, '[data-goto],[data-id]', {first:reportNav});
}

// ---------- script tasks view (every script body in the project, in one place) ----------
// A script is not a node of its own — it lives inside a script task, a CMMN plan item, a listener or a
// bot — so "show me all the code in this project" used to mean opening every model in turn. Rebuilt on
// each visit from the payload; there is nothing to cache and the counts stay honest.
// `elKind` is the Design element the script belongs to (`scriptTask`, `executionListener`, …); `group`
// is the coarse bucket the filter chips work on.
function allScripts(){
  const out=[];
  // a row with findings but no body (an empty script task) still deserves a row — that IS the finding
  const add=(n,o)=>{ if(o.body||(o.problems||[]).length)
    out.push(Object.assign({model:n.id, modelLabel:n.label, modelType:n.type}, o)); };
  nodes.forEach(n=>{
    const d=n.data||{};
    if(n.type==='process'){
      (d.scriptTasks||[]).forEach(t=>add(n,{group:'script', elKind:'scriptTask', el:t.id, elName:t.name,
        lang:t.format||t.scriptFormat, body:t.script, doc:t.documentation, out:t.resultVariable,
        problems:t.problems||[]}));
    }
    if(n.type==='case' && d.planModel){
      // CMMN keeps its script tasks in the plan tree (`<task flowable:type="script">`)
      (function walk(nd){
        if(nd.script||(nd.problems||[]).length) add(n,{group:'script', elKind:'serviceTask/script',
          el:nd.id, elName:nd.name, lang:nd.scriptFormat, body:nd.script, doc:nd.documentation,
          problems:nd.problems||[]});
        (nd.children||[]).forEach(walk);
      })(d.planModel);
    }
    if(n.type==='process'||n.type==='case'){
      const listener=(r,l)=>({group:'listener', elKind:l.kind, event:l.event,
        el:r?r.id:null, elName:r?r.name:null, body:l.script, problems:l.problems||[]});
      (d.listeners||[]).forEach(l=>add(n, listener(null,l)));
      elementRecords(n).forEach(r=>(r.listeners||[]).forEach(l=>add(n, listener(r,l))));
    }
    if(n.type==='action') add(n,{group:'bot', lang:d.scriptLanguage, body:d.script,
      problems:d.scriptProblems||[]});
  });
  return out;
}
/** Chip buckets, in reading order. A bot script has no Design element of its own — it *is* the action. */
const SCRIPT_GROUPS=[{id:'script', label:'Script tasks'},{id:'listener', label:'Listeners'},
                     {id:'bot', label:'Bot scripts'}];
/** The Design words for one script row: its element term plus the lifecycle event it hangs off. */
function scriptKindLabel(s){
  if(s.group==='bot') return 'Bot script';
  const base=term('el', s.elKind).label || 'Script';
  return s.event ? base+' · '+s.event : base;
}
/** ⚠ n — red when any finding is an error, amber when everything is a warning. */
function scriptIssueBadge(problems){
  const pr=problems||[];
  if(!pr.length) return '';
  const tone=pr.some(p=>p.severity==='error')?'bad':'warn';
  return '<span class="pt sev-'+tone+'">⚠ '+pr.length+'</span>';
}
/** The findings of one script, as rows: severity, message, line and the offending source line. */
function scriptProblemsHtml(problems){
  const pr=problems||[];
  if(!pr.length) return '';
  return '<div class="code-p">'+
    pr.map(p=>'<div class="code-pr"><span class="sev sev-'+(p.severity==='error'?'bad':'warn')+'">'+
      esc(p.severity)+'</span> '+esc(p.message)+
      (p.line?' <span class="muted">· line '+p.line+'</span>':'')+
      (p.snippet?' <span class="mono muted">'+esc(p.snippet)+'</span>':'')+'</div>').join('')+'</div>';
}
// ---------- tiny script highlighter — display only, so the worst case is a token staying plain ----------
const HL_KEYWORDS={
  groovy:'def var final if else for while do switch case break continue return try catch finally throw '+
    'new class interface enum extends implements import package assert in instanceof null true false this super void',
  js:'const let var function if else for while do switch case break continue return try catch finally throw '+
    'new class extends import from export await async yield typeof instanceof delete void in of null undefined true false this super',
  py:'def class if elif else for while try except finally raise return import from as with lambda pass '+
    'break continue global nonlocal yield assert in is not and or del None True False',
};
function hlFamily(lang){
  const l=String(lang||'').toLowerCase();
  if(l==='groovy') return 'groovy';
  if(['javascript','js','ecmascript','nashorn','graal.js'].indexOf(l)>=0) return 'js';
  if(l==='python'||l==='jython') return 'py';
  return null;
}
/** Escaped HTML with comment/string/number/keyword tokens wrapped; `${…}` interpolation inside a
 *  string is colored as code. Multi-line tokens close and reopen their span on every line, so the
 *  result can be split on '\n' without breaking markup. */
function hlScript(src, lang){
  const fam=hlFamily(lang);
  if(!fam) return esc(src);
  const kw=new Set(HL_KEYWORDS[fam].split(' '));
  const wrap=(cls,text)=>text.split('\n')
    .map(seg=>seg?'<span class="tok-'+cls+'">'+esc(seg)+'</span>':'').join('\n');
  const string=text=>{
    if(fam==='py') return wrap('s', text);
    let out='', i=0, m; const re=/\$\{[^}\n]*\}/g;
    while((m=re.exec(text))){ out+=wrap('s',text.slice(i,m.index))+wrap('i',m[0]); i=m.index+m[0].length; }
    return out+wrap('s',text.slice(i));
  };
  const re= fam==='py'
    ? /(#[^\n]*)|('''[\s\S]*?(?:'''|$)|"""[\s\S]*?(?:"""|$)|'(?:\\.|[^'\\\n])*'?|"(?:\\.|[^"\\\n])*"?)|\b(\d[\w.]*)\b|\b([A-Za-z_]\w*)\b/g
    : /(\/\*[\s\S]*?(?:\*\/|$)|\/\/[^\n]*)|('''[\s\S]*?(?:'''|$)|"""[\s\S]*?(?:"""|$)|`[\s\S]*?(?:`|$)|'(?:\\.|[^'\\\n])*'?|"(?:\\.|[^"\\\n])*"?)|\b(\d[\w.]*)\b|\b([A-Za-z_$]\w*)\b/g;
  let out='', last=0, m;
  while((m=re.exec(src))){
    out+=esc(src.slice(last, m.index));
    if(m[1]) out+=wrap('c', m[1]);
    else if(m[2]) out+=string(m[2]);
    else if(m[3]) out+=wrap('n', m[3]);
    else out+= kw.has(m[4]) ? wrap('k', m[4]) : esc(m[4]);
    last=m.index+m[0].length;
  }
  return out+esc(src.slice(last));
}
/** The read-only code viewer: line numbers, syntax colors, and the problem lines marked with the
 *  finding's message on hover. Replaces the bare `<pre class="scriptbox">` wherever a script shows. */
function codeBoxHtml(body, lang, problems){
  if(body==null||body==='') return '';
  const byLine={};
  (problems||[]).forEach(p=>{ if(p.line) (byLine[p.line]=byLine[p.line]||[]).push(p); });
  const lines=hlScript(String(body), lang).split('\n');
  return '<pre class="scriptbox code">'+lines.map((l,i)=>{
    const pr=byLine[i+1];
    const cls='cl'+(pr?(pr.some(p=>p.severity==='error')?' cl-bad':' cl-warn'):'');
    const tip=pr?' title="'+esc(pr.map(p=>p.message).join(' · '))+'"':'';
    return '<span class="'+cls+'"'+tip+'><span class="lno">'+(i+1)+'</span>'+l+'</span>';
  }).join('')+'</pre>';
}
/** model id → `{type: [artifact ids]}`: the variables, expressions, bindings, string literals, custom
 *  functions and service operations a model uses, inverted from those nodes' `usedBy` lists. None of
 *  them has an edge (they would flood every ego graph and hotspot count), and the generator strips the
 *  equivalent `_uses` map from the payload because this is its exact transpose. Built once, on demand. */
const USES_TYPES=new Set(['variable','expression','binding','string','customFunction','serviceOperation']);
let _usesIdx=null;
function usesIndex(){
  const m=new Map();
  nodes.forEach(n=>{
    if(!USES_TYPES.has(n.type)) return;
    ((n.data||{}).usedBy||[]).forEach(mid=>{
      if(!byId.has(mid)) return;
      let e=m.get(mid); if(!e){ e={}; m.set(mid,e); }
      (e[n.type]=e[n.type]||[]).push(n.id);
    });
  });
  m.forEach(e=>{ Object.keys(e).forEach(t=>e[t].sort()); });
  return m;
}
function usesOf(id){ if(!_usesIdx) _usesIdx=usesIndex(); return _usesIdx.get(id); }
/** `"<model>|<element>"` → the variables that script touches, inverted from the variable nodes. */
function scriptVarIndex(){
  const m=new Map();
  nodes.forEach(n=>{
    if(n.type!=='variable') return;
    ((n.data||{}).scriptSites||[]).forEach(s=>{
      const k=s.model+'|'+(s.element==null?'':s.element);
      if(!m.has(k)) m.set(k,[]);
      m.get(k).push({name:n.label, api:s.api});
    });
  });
  return m;
}
function renderScripts(){
  const v=document.getElementById('view-scripts');
  const all=allScripts(), varIdx=scriptVarIndex();
  const lines=s=>String(s.body).split('\n').length;
  const byModel=new Map();
  all.forEach(s=>{ if(!byModel.has(s.model)) byModel.set(s.model,[]); byModel.get(s.model).push(s); });
  const models=[...byModel.keys()].sort((a,b)=>{
    const la=(byId.get(a)||{}).label||a, lb=(byId.get(b)||{}).label||b;
    return la.localeCompare(lb);
  });
  const totalLines=all.reduce((a,s)=>a+lines(s),0);
  const withIssues=all.filter(s=>(s.problems||[]).length).length;
  _sectReg=[];
  let b='';
  models.forEach(mid=>{
    const rows=byModel.get(mid), mn=byId.get(mid)||{label:mid};
    b+=section('rpt-scripts-'+mid, nodeIcon(mn)+' '+esc(mn.label), cards(rows.map(s=>{
      const vars=varIdx.get(s.model+'|'+(s.el==null?'':s.el))||[];
      const chips=vars.map(x=>'<span class="'+(x.api?'':'muted ')+'mono">'+vlink('variable:'+x.name, (x.api?'':'≈ ')+x.name)+'</span>').join(' ');
      // a bot script IS its model, and a model-level listener has only its kind to go by
      const kind=scriptKindLabel(s);
      const title=s.elName||s.el||(s.group==='bot'?s.modelLabel:kind);
      return scriptCard({id:s.el, name:title, format:s.lang, script:s.body, documentation:s.doc, resultVariable:s.out, problems:s.problems}, null,
        {extra:tag(kind)+(chips?'<span class="card-vars">'+chips+'</span>':''), right:(s.el?elJumpHtml(s.model, s.el, 'in model', 'Open this element in its model'):''),
         attrs:' data-scriptrow data-group="'+esc(s.group)+'"', open:all.length<=6, hay:mn.label+' '+kind});
    })), {count:rows.length, nav:mn.label, attrs:' data-model="'+enc(mid)+'"'});
  });
  const reg=_sectReg; _sectReg=null;
  let h='<div class="dash" data-fscope>';
  h+=pageHeader({icon:'scripts', color:color('scripts'), title:'Script tasks',
    sub:all.length?'script tasks, listener scripts and bot scripts — every script body in the project, with the variables each one touches':'no model in this project carries a script',
    facts:all.length?[['Scripts',all.length],['Models',models.length],['Lines',totalLines],
      ['With findings',{html:withIssues?'<span class="sev sev-bad">⚠ '+withIssues+'</span>':'0',copy:null}]]:[]});
  if(!all.length){
    h+='<div class="estate"><div class="estate-ic" aria-hidden="true">{ }</div>'+
       '<div class="et">No script tasks</div>'+
       '<div class="eh">Nothing to show — no script task, listener script or bot script was found.</div></div>';
  } else {
    // chips narrow by kind (same single-select pattern as every filter bar), the text box searches names,
    // languages and the code itself; one control opens or closes every body at once — reading a
    // project's scripts top to bottom is the point of this view, and clicking 40 triangles is not
    h+='<div class="pagebar">'+filterBar({placeholder:'filter scripts — name, language, code…', label:'Filter script tasks', total:all.length,
      chips:[{fk:'group',fv:'all',label:'All',n:all.length}].concat(SCRIPT_GROUPS.filter(g=>all.some(s=>s.group===g.id))
        .map(g=>({fk:'group',fv:g.id,label:g.label,n:all.filter(s=>s.group===g.id).length}))),
      extra:'<button type="button" class="pchip" id="scriptsall"></button>'})+'</div>';
    h+=secnavHtml(reg)+b;
  }
  h+='</div>';
  v.innerHTML=h;
  wireReport(v);
  const toggle=v.querySelector('#scriptsall');
  if(toggle){
    const rows=()=>[...v.querySelectorAll('[data-scriptrow]')].filter(r=>!r.hidden);
    const syncAll=()=>{ const rs=rows(); toggle.textContent=(rs.length&&rs.every(r=>r.open))?'⇕ collapse all':'⇕ expand all'; };
    toggle.onclick=()=>{ const rs=rows(), open=!rs.every(r=>r.open); rs.forEach(r=>{ r.open=open; }); syncAll(); };
    v.querySelectorAll('[data-scriptrow]').forEach(r=>r.addEventListener('toggle',syncAll));
    v.querySelectorAll('.fbar .pf, .fbar .pchip').forEach(el=>el.addEventListener(el.tagName==='INPUT'?'input':'click', ()=>setTimeout(syncAll,150)));
    syncAll();
  }
  wireNodeLinks(v, '[data-goto],[data-id]');
}

// ---------- browse: list column ----------
// ---------- list multi-selection ----------
// Marks are kept by NODE ID, never by row index: renderItems() rebuilds the rows on every filter
// keystroke and sort change, and an index-keyed set would silently point at different nodes.
// `listAnchor` is the id a Shift range extends from.
let listMarks=new Set(), listAnchor=null;
function listMarksClear(){ listMarks.clear(); listAnchor=null; }
/** Ids of the rows currently in the DOM — a Shift range or ⌘A can only span what is rendered. */
function listRenderedIds(){
  return [...document.querySelectorAll('#listitems .item[data-id]')].map(el=>el.dataset.id);
}
/** Add ids up to the cap. Returns the number refused, so the caller can say so instead of
 *  pretending: marking more than MAX_TABS would promise an "open all" that cannot be kept. */
function listMarkAdd(ids){
  let refused=0;
  ids.forEach(id=>{
    if(listMarks.has(id)) return;
    if(listMarks.size>=MAX_TABS){ refused++; return; }
    listMarks.add(id);
  });
  return refused;
}
function listMarkRange(fromId, toId){
  const ids=listRenderedIds();
  const a=ids.indexOf(fromId), b=ids.indexOf(toId);
  if(a<0||b<0) return 0;
  return listMarkAdd(ids.slice(Math.min(a,b), Math.max(a,b)+1));
}
/** Repaint marks only. Deliberately NOT syncListSelection(): that one scrolls the selected row into
 *  view, which would yank the list back to the open node on every Shift+Arrow. */
function syncListMarks(){
  document.querySelectorAll('#listitems .item[data-id]').forEach(el=>{
    const mk=listMarks.has(el.dataset.id);
    el.classList.toggle('mark', mk);
    el.setAttribute('aria-checked', mk?'true':'false');
  });
}
const CAP_NOTE=()=>'marking stops at '+MAX_TABS+' — that is the tab limit';
// A pending "n did not fit" line. It has to survive the async hash round-trip that opening tabs
// goes through, so it lives here rather than being passed into the render call that would lose it.
let _markNote='';
function setMarkNote(s){ _markNote=s||''; renderListMarkBar(); }
/** The "N marked → open" bar in the list head; also where an over-the-cap warning surfaces. */
function renderListMarkBar(){
  const box=document.getElementById('lmark');
  if(!box) return;
  const n=listMarks.size;
  if(!n && !_markNote){ box.innerHTML=''; return; }
  // The live region carries a bare counter, not the button label: announcing "open 4 in tabs,
  // Enter" on every Shift+Arrow keystroke makes a screen reader unusable.
  box.innerHTML=(n?'<button class="lh-open" id="lopen" data-tip="Open every marked item as a detail tab">'+
      'open '+n+' in tab'+(n>1?'s':'')+' · Enter</button>':'')+
    '<span class="vh" aria-live="polite">'+(n?n+' marked':'')+'</span>'+
    (_markNote?'<div class="lh-note">'+esc(_markNote)+'</div>':'');
  const b=document.getElementById('lopen');
  if(b) b.onclick=()=>openMarkedList();
}
/** Open the marked rows as tabs. `background` keeps the current tab active. */
function openMarkedList(background){
  if(!listMarks.size) return;
  const ids=listRenderedIds().filter(id=>listMarks.has(id));   // keep the on-screen order
  const r=openTabs(ids.length?ids:[...listMarks], {background:!!background});
  listMarksClear();
  // Marks only — the route that openTabs kicked off runs syncListSelection() and owns the scroll.
  syncListMarks();
  setMarkNote(r.dropped ? r.dropped+' not opened — '+MAX_TABS+' tabs is the limit' : '');
}

function renderList(){
  const cat = CATS.find(c=>c.id===state.cat);
  const list = document.getElementById('list'); list.innerHTML='';
  if(!cat) return;
  const head=document.createElement('div'); head.className='listhead';
  head.innerHTML='<div class="t"><span>'+esc(cat.label)+'</span><span class="muted">'+cat.count+'</span></div>'+
    '<div class="lh-controls"><input id="lf" placeholder="filter '+esc(cat.label.toLowerCase())+'…" aria-label="Filter list">'+
    '<select id="lsort" aria-label="Sort list"><option value="name">Name</option>'+
    '<option value="refs">Most referenced</option><option value="file">File</option></select></div>'+
    '<div id="lwider"></div><div id="lmark"></div>';
  list.appendChild(head);
  const wrap=document.createElement('div'); wrap.id='listitems';
  wrap.setAttribute('role','listbox');
  wrap.setAttribute('aria-label',cat.label);
  list.appendChild(wrap);
  renderItems(cat, wrap);
  // The input lives outside the re-rendered items wrap, so typing never loses focus.
  const lf=document.getElementById('lf'); lf.value=state.filter;
  lf.oninput=debounce(()=>{ state.filter=lf.value; renderItems(cat, wrap); syncHashContext(); },120);
  const ls=document.getElementById('lsort'); ls.value=state.sort;
  ls.onchange=()=>{ state.sort=ls.value; renderItems(cat, wrap); syncHashContext(); };
  // Arrow/Enter keyboard navigation over the items (roving focus), plus Shift+Arrow multi-select.
  wrap.onkeydown=e=>{
    const els=[...wrap.querySelectorAll('.item[data-id]')];
    const i=els.indexOf(document.activeElement);
    const mod=e.metaKey||e.ctrlKey;
    if(e.key==='ArrowDown'||e.key==='ArrowUp'){
      e.preventDefault();
      const j=e.key==='ArrowDown'?Math.min(i+1,els.length-1):Math.max(i-1,0);
      if(!els[j]) return;
      if(e.shiftKey && i>=0){
        // Anchor at the row we started from, then paint the whole span each time — re-painting
        // beats tracking increments, because shrinking a range has to unmark too.
        if(listAnchor===null) listAnchor=els[i].dataset.id;
        listMarks.clear();
        const refused=listMarkRange(listAnchor, els[j].dataset.id);
        syncListMarks(); setMarkNote(refused?CAP_NOTE():'');
      }
      els[j].focus();
    } else if(e.key==='Home'&&els[0]){ e.preventDefault(); els[0].focus(); }
    else if(e.key==='End'&&els[els.length-1]){ e.preventDefault(); els[els.length-1].focus(); }
    else if(mod && (e.key==='a'||e.key==='A')){
      // Only the rendered rows: renderItems() chunks at LIST_CHUNK, and marking thousands of
      // off-DOM nodes would promise an "open all" the tab cap cannot keep anyway.
      e.preventDefault();
      listMarks.clear();
      const refused=listMarkAdd(els.map(el=>el.dataset.id));
      listAnchor=els.length?els[0].dataset.id:null;
      syncListMarks(); setMarkNote(refused?CAP_NOTE():'');
    }
    else if(e.key===' ' && i>=0){
      e.preventDefault();                                  // Space toggles the mark under the cursor
      const id=els[i].dataset.id;
      let refused=0;
      if(listMarks.has(id)) listMarks.delete(id); else { refused=listMarkAdd([id]); listAnchor=id; }
      syncListMarks(); setMarkNote(refused?CAP_NOTE():'');
    }
    else if(e.key==='Enter'){
      e.preventDefault();
      if(listMarks.size) openMarkedList(mod);              // ⌘/Ctrl+Enter → keep the current tab
      else if(i>=0){
        if(mod) openTabs([els[i].dataset.id], {background:true});
        else select(els[i].dataset.id);
      }
    }
    else if(e.key==='Escape' && listMarks.size){
      e.preventDefault(); listMarksClear(); syncListMarks(); setMarkNote('');
    }
  };
  renderListMarkBar();
}

/**
 * How many nodes OUTSIDE this category the same words would find. Identity-only matching (name / key /
 * file) rather than the full scored pass: this runs on every keystroke, and the number only has to be
 * honest that there is more to find elsewhere — ⌘K then shows the real, deeper result set.
 */
function countOutsideCat(cat, parsed){
  if(parsed.empty) return 0;
  let n=0;
  for(let i=0;i<nodes.length;i++){
    const node=nodes[i];
    if(cat.match(node)) continue;
    const ix=searchIndex(node);
    let ok=true;
    for(let j=0;j<parsed.terms.length && ok;j++){
      const t=parsed.terms[j];
      ok=ix.name.indexOf(t)>=0||ix.key.indexOf(t)>=0||ix.file.indexOf(t)>=0;
    }
    for(let j=0;j<parsed.phrases.length && ok;j++){
      const p=parsed.phrases[j];
      ok=ix.name.indexOf(p)>=0||ix.key.indexOf(p)>=0;
    }
    if(ok) n++;
  }
  return n;
}
/**
 * The bridge between the two searches. The list filter only ever looks inside the selected category,
 * which is correct but reads as "Atlas cannot find it" when you are standing in the wrong one. So when
 * the words match something elsewhere, say so and hand the term to ⌘K, which searches everything.
 */
function renderListBridge(cat, parsed, shown){
  const box=document.getElementById('lwider');
  if(!box) return;
  const outside=countOutsideCat(cat, parsed);
  if(!outside){ box.innerHTML=''; return; }
  box.innerHTML='<button class="lh-wider" type="button" id="lwiderbtn">'+
    (shown?'':'Nothing here — ')+outside+' match'+(outside>1?'es':'')+
    ' in other categories · search everything</button>';
  document.getElementById('lwiderbtn').onclick=()=>openPalette(state.filter);
}

// Incremental rendering: 200 rows at a time, the IntersectionObserver on a trailing
// sentinel appends the next chunk when it scrolls into view — every item of a large
// category is reachable by scrolling (the old hard cap cut off at 600).
const LIST_CHUNK=200;
let _listIO=null;
function renderItems(cat, wrap){
  if(_listIO){ _listIO.disconnect(); _listIO=null; }
  wrap.innerHTML='';
  let items = nodes.filter(cat.match);
  // Same engine as ⌘K: words count independently and in any order, and a hyphen or a camel hump is a
  // word boundary. Before this, the box was a single raw substring test over name/key/file only, so
  // "customer name" found nothing in a category full of nodes matching both words.
  const parsed = qParse(state.filter);
  if(!parsed.empty){
    const ranked=[];
    items.forEach(n=>{ const r=scoreNode(n, parsed); if(r) ranked.push({n, score:r.score}); });
    ranked.sort((a,b)=>b.score-a.score||a.n.label.localeCompare(b.n.label));
    items=ranked.map(x=>x.n);
  }
  if(state.sort==='refs')
    items.sort((a,b)=>(INSIGHTS.indeg.get(b.id)||0)-(INSIGHTS.indeg.get(a.id)||0)||a.label.localeCompare(b.label));
  else if(state.sort==='file')
    items.sort((a,b)=>String(a.file||'').localeCompare(String(b.file||''))||a.label.localeCompare(b.label));
  else if(parsed.empty)
    items.sort((a,b)=>a.label.localeCompare(b.label));
  // else: an explicit sort wins, but plain "Name" yields to the relevance order above.
  renderListBridge(cat, parsed, items.length);
  if(!items.length){
    // "nothing found" is the one answer a find-it-fast tool must state; a blank column stated nothing
    wrap.innerHTML='<div class="estate list-empty"><div class="et">'+(parsed.empty?'Nothing in ':'No match in ')+esc(cat.label)+'</div>'+
      '<div class="eh">'+(parsed.empty?'':'Nothing here matches “'+esc(state.filter)+'” — ')+
      '<button type="button" class="dgbtn" id="lemptypal">search everything ('+(IS_MAC?'⌘':'Ctrl+')+'K)</button></div></div>';
    const b=wrap.querySelector('#lemptypal'); if(b) b.onclick=()=>openPalette(state.filter);
    return;
  }
  const sentinel=document.createElement('div'); sentinel.className='sentinel';
  wrap.appendChild(sentinel);
  let idx=0;
  function makeItem(n,i){
    const el=document.createElement('div');
    el.className='item'+(state.sel===n.id?' on':'')+(listMarks.has(n.id)?' mark':'');
    el.dataset.id=n.id;
    el.setAttribute('role','option');
    // Two independent states, two attributes: aria-selected is the node the detail panel shows,
    // aria-checked is "marked, comes along on Enter". Overloading aria-selected with both would
    // make the multi-selection unreadable to a screen reader.
    el.setAttribute('aria-selected', state.sel===n.id?'true':'false');
    el.setAttribute('aria-checked', listMarks.has(n.id)?'true':'false');
    el.tabIndex=-1;
    const rn=INSIGHTS.indeg.get(n.id)||0;
    // Why this row matched, same as in the palette: a hit from a script body or a mapping used to
    // show a row with no visible reason at all. Falls back to the key — the line it always showed.
    const w=parsed.empty?null:matchWhere(n, parsed);
    const sub=(w&&w.hint)||n.key;
    el.innerHTML=nodeIcon(n)+
      '<div class="meta"><div class="nm">'+hlHtml(n.label, parsed)+authBadge(n)+findPillHtml(n.id)+
      '</div><div class="sub" title="'+esc(sub)+'">'+hlHtml(sub, parsed)+'</div></div>'+
      (rn?'<span class="refn" title="referenced by '+rn+' node'+(rn>1?'s':'')+'">'+rn+'</span>':'')+
      '<span class="ck" aria-hidden="true">✓</span>';
    // ⌘/Ctrl+click toggles and Shift+click extends — the list-selection convention, not the
    // browser's "open in new tab" one (middle-click and ⌘/Ctrl+Enter cover that). The box that
    // appears on hover is the same toggle: a checkbox you can see is a checkbox you can click.
    el.onclick=e=>{
      if(modKey(e) || (e.target.closest&&e.target.closest('.ck'))){
        e.preventDefault();
        let refused=0;
        if(listMarks.has(n.id)) listMarks.delete(n.id); else { refused=listMarkAdd([n.id]); listAnchor=n.id; }
        syncListMarks(); setMarkNote(refused?CAP_NOTE():''); return;
      }
      if(e.shiftKey){
        e.preventDefault();
        if(listAnchor===null) listAnchor=state.sel||n.id;
        listMarks.clear();
        const refused=listMarkRange(listAnchor, n.id);
        syncListMarks(); setMarkNote(refused?CAP_NOTE():''); return;
      }
      if(listMarks.size){ listMarksClear(); setMarkNote(''); }
      // carry the filter term and the matched element, exactly like a palette hit — the detail panel
      // then opens and highlights the row the match came from
      select(n.id, parsed.empty?undefined:state.filter, (w&&w.el)||undefined);
    };
    el.onmousedown=e=>{ if(e.button===1) e.preventDefault(); };   // no autoscroll cursor
    el.onauxclick=e=>{ if(e.button===1){ e.preventDefault(); openTabs([n.id], {background:true}); } };
    return el;
  }
  function append(){
    const slice=items.slice(idx, idx+LIST_CHUNK);
    slice.forEach((n,i)=>wrap.insertBefore(makeItem(n,i), sentinel));
    if(idx===0 && wrap.querySelector('.item')) wrap.querySelector('.item').tabIndex=0;
    idx+=slice.length;
    if(idx>=items.length){ if(_listIO){ _listIO.disconnect(); _listIO=null; } sentinel.remove(); }
  }
  _listIO=new IntersectionObserver(es=>{ if(es.some(e=>e.isIntersecting)) append(); },
                                   {root: wrap.closest('.listcol'), rootMargin:'600px'});
  _listIO.observe(sentinel);
  append();
}

// Selection within the current category only toggles classes — no full list rebuild.
function syncListSelection(){
  let hit=null;
  document.querySelectorAll('#list .item[data-id]').forEach(el=>{
    const on = el.dataset.id===state.sel, mk = listMarks.has(el.dataset.id);
    el.classList.toggle('on', on);
    el.classList.toggle('mark', mk);
    el.setAttribute('aria-selected', on?'true':'false');
    el.setAttribute('aria-checked', mk?'true':'false');
    if(on) hit=el;
  });
  if(hit) hit.scrollIntoView({block:'nearest'});
}

// ---------- detail ----------
// `f` (optional) is the adjacency entry — a suspect/dynamic link gets a marker + dashed chip.
function nodeChip(id,f){
  const n=byId.get(id); if(!n) return '';
  const cls=f&&f.sus?' nc-sus':f&&f.dyn?' nc-dyn':'';
  const flag=f&&f.sus?'<span class="ncflag" title="suspect — loose or cross-type match">≈</span>'
           :f&&f.dyn?'<span class="ncflag" title="dynamic — reference is an expression">ƒ</span>':'';
  return '<span class="nc'+cls+'" data-id="'+enc(id)+'" tabindex="0" role="link">'+nodeIcon(n)+
    '<span class="nm">'+esc(n.label)+'</span>'+flag+'<span class="ty">'+esc(nodeKind(n))+'</span>'+copyBtn(n.key,nodeKind(n)+' key')+'</span>';
}
// `label ↓` — one element inside a model, opened where it lives. `data-goto`/`data-goto-el` is the
// contract wireNodeLinks() reads; `tip` is optional because in the detail pane the surrounding row
// already says what the jump does.
function elJumpHtml(model, element, label, tip){
  if(!element) return '';
  return '<span class="opref" data-goto="'+enc(model)+'" data-goto-el="'+esc(String(element))+'"'+
    ' tabindex="0" role="link" style="cursor:pointer"'+(tip?' data-tip="'+esc(tip)+'"':'')+'>'+
    esc(label)+' ↓</span>';
}
// rel -> Map(id -> adjacency entry) — the Map keeps per-target flags while deduping ids.
function groupRels(arr){ const g={}; (arr||[]).forEach(x=>{ (g[x.rel]=g[x.rel]||new Map()).set(x.id,x); }); return g; }
// Small badge marking a changelog as the live definition of its table vs a superseded/orphan revision.
function authBadge(n){
  if(n.type!=='liquibase') return '';
  const a=(n.data||{}).authority; if(!a||!a.status) return '';
  if(a.status==='live'){ const by=(a.referencedBy||[]).join(', ');
    return '<span class="pill pill-ok" title="Live / authoritative'+(by?' — referenced by '+esc(by):'')+'">live</span>'; }
  if(a.status==='superseded'){ const by=(a.supersededBy||[]).join(', ');
    return '<span class="pill pill-warn" title="Superseded — the same table is provided by '+esc(by||'a referenced changelog')+'">superseded</span>'; }
  return '<span class="pill pill-bad" title="Orphan — not referenced by any service or data object">orphan</span>';
}

// inline link to a node id if it exists in the graph, else plain escaped text —
// so every conversion below degrades to the old static text when the target isn't resolved.
function vlink(id, text, title){
  return byId.get(id)
    ? '<span class="vlink" data-id="'+enc(id)+'"'+(title?' title="'+esc(title)+'"':'')+
      ' tabindex="0" role="link">'+esc(text)+'</span>'
    : esc(text==null?'':text);
}
// first neighbor id reachable from `id` over relation `rel` (outgoing / incoming) — used when a
// value can't be turned into a node id directly but the resolver already computed the edge.
const outTo  = (id,rel)=>{ const e=(outM.get(id)||[]).find(x=>x.rel===rel); return e&&e.id; };
const incFrom= (id,rel)=>{ const e=(incM.get(id)||[]).find(x=>x.rel===rel); return e&&e.id; };

// ---------- collapsible detail sections ----------
// Every block in the detail panel is a <details> so a node with 35 parameters can still be skimmed.
// Open/closed is remembered per SECTION (not per node) in localStorage: a section you open stays open as
// you walk the graph. Everything defaults to closed except the diagram and the neighborhood — and the
// one section that IS the model (a form's fields, a service's operations) — see DEFAULT_OPEN_SECTIONS.
const SECT_STORE='atlas-sect';
const DEFAULT_OPEN_SECTIONS={diagram:true, neighborhood:true, findings:true, formfields:true, columns:true, usertasks:true, svctasks:true, scripttasks:true,
  plan:true, ops:true, dmnio:true, dmnrules:true, permissions:true, escalations:true, rw:true, payload:true, dicttypes:true, agentops:true,
  endpoints:true, script:true, templatebody:true, extractors:true, coverage:true, problems:true, opparams:true, usedby:true};
function sectAll(){ try{ return JSON.parse(localStorage.getItem(SECT_STORE)||'{}')||{}; }catch(e){ return {}; } }
function sectRemember(id, open){
  try{ const m=sectAll(); m[id]=open; localStorage.setItem(SECT_STORE, JSON.stringify(m)); }catch(e){}
}
function sectIsOpen(id){
  const m=sectAll();
  // a report page's findings are its point — its sections start open; a node page starts folded
  return id in m ? !!m[id] : (!!DEFAULT_OPEN_SECTIONS[id] || /^(chk|rpt)-/.test(id));
}
// What rendered, in page order: `section()` appends to it while a page is being built and the section
// navigator reads it afterwards — so the navigator lists exactly the sections that exist, never one that
// was skipped for an empty body. Null outside a render, so stray callers register nothing.
let _sectReg=null;
const stripTags=s=>String(s==null?'':s).replace(/<[^>]*>/g,'').replace(/&amp;/g,'&').replace(/&lt;/g,'<')
  .replace(/&gt;/g,'>').replace(/&quot;/g,'"').replace(/\s+/g,' ').trim();
/**
 * `titleHtml` is pre-built markup (callers escape); an empty body renders nothing at all. `o.count` is
 * shown as a pill after the title and travels into the navigator; `o.hint` is the one-line explanation
 * beside it; `o.tools` (a filter bar, a button row) sits at the top of the body — not in the summary,
 * where a click would also toggle the section; `o.nav` overrides the navigator label. A legacy title
 * of the form "Fields (7) — …" is split into title and count so the navigator reads the same for every
 * section.
 */
function section(id, titleHtml, bodyHtml, o){
  if(!bodyHtml) return '';
  o=o||{};
  let count=o.count!=null?o.count:null, title=titleHtml;
  if(count==null){
    const m=/^([\s\S]*?)\s*\((\d+)\)(\s*—[\s\S]*)?$/.exec(stripTags(titleHtml));
    if(m){ count=+m[2]; title=m[1]+(m[3]||''); }
  }
  if(_sectReg) _sectReg.push({id, title:o.nav||stripTags(title).replace(/\s*—[\s\S]*$/,''), count});
  return '<details class="sect" data-sect="'+enc(id)+'"'+(o.attrs||'')+(sectIsOpen(id)?' open':'')+'>'+
    '<summary><span class="st">'+(o.count!=null||count==null?titleHtml:esc(title))+'</span>'+
    (count!=null?'<span class="scount">'+esc(String(count))+'</span>':'')+
    (o.hint?'<span class="shint">'+esc(o.hint)+'</span>':'')+'</summary>'+
    '<div class="sb">'+(o.tools?'<div class="sbar">'+o.tools+'</div>':'')+bodyHtml+'</div></details>';
}

// ---------- detail components ----------
// Every section body is built from a handful of parts, so a table on the form page and a table on the
// Checks page are the same table: `tbl` (column-headed rows, expandable when a row has more to say),
// `cards` (one element with a body — a script task, an operation, a parameter group), `props` (label /
// value pairs — the facts under the title, the details inside a card), `codeblk` (a script with its
// language, size and findings), `filterBar` (text + chips over the rows below it). Names, labels and
// captions are set in the sans face; identifiers, expressions, URLs, paths and code are monospace —
// `mono:true` on a column or a value says so, nothing else does.
const TBL_FILTER_FROM=12;   // above this many rows a table gets a filter of its own
const hayAttr=h=>(h==null||h==='')?'':' data-hay="'+esc(String(h).toLowerCase())+'"';
/**
 * cols: [{k, label, w, cls, mono, opt}] — `w` is a grid track that is never content-sized (`Nch`, `Nfr`,
 * `minmax(Nch,Nfr)`), so rows laid out independently still line up; `opt` marks a column that drops
 * under the row in a narrow panel instead of being clipped. rows: [{el, hay, cells:{k:html}, body, open,
 * cls}] — a row with a body is a <details> (native toggle; a row is not a section, so nothing is
 * remembered). Cell values are HTML — callers escape. `o.filter` (false | min rows) adds a filter bar,
 * `o.more` a trailing "+N more" line, `o.empty` the text shown for an empty list.
 */
function tbl(cols, rows, o){
  o=o||{};
  if(!rows||!rows.length) return o.empty?'<div class="muted tbl-empty">'+esc(o.empty)+'</div>':'';
  const tracks='1.1em '+cols.map(c=>c.w||'minmax(0,1fr)').join(' ');
  const tdCls=c=>'td'+(c.cls?' '+c.cls:'')+(c.mono?' mono':'')+(c.opt?' opt':'');
  const head='<div class="th" aria-hidden="true"><span class="td tdc"></span>'+
    cols.map(c=>'<span class="'+tdCls(c)+'">'+esc(c.label||'')+'</span>').join('')+'</div>';
  const body=rows.map(r=>{
    const cells=cols.map(c=>{ const v=r.cells[c.k]; return '<span class="'+tdCls(c)+'">'+(v==null?'':v)+'</span>'; }).join('');
    const attrs=dataEl(r.el)+hayAttr(r.hay)+(r.attrs||'');
    if(r.body) return '<details class="tr'+(r.cls?' '+r.cls:'')+'"'+attrs+(r.open?' open':'')+
      '><summary class="trs"><span class="td tdc">'+uiIcon('chevron')+'</span>'+cells+'</summary>'+
      '<div class="tx'+(r.bodyCls?' '+r.bodyCls:'')+'">'+r.body+'</div></details>';
    return '<div class="tr'+(r.cls?' '+r.cls:'')+'"'+attrs+'><span class="td tdc"></span>'+cells+'</div>';
  }).join('');
  const filt=(o.filter!==false && rows.length>=(typeof o.filter==='number'?o.filter:TBL_FILTER_FROM))
    ? filterBar({placeholder:o.placeholder||'filter rows…', total:rows.length, chips:o.chips}) : '';
  return filt+'<div class="tbl'+(o.cls?' '+o.cls:'')+'" style="--cols:'+tracks+'">'+head+body+'</div>'+
    (o.more?'<div class="tbl-more muted">'+esc(o.more)+'</div>':'');
}
/** items: [{el, hay, name, id, badges:[html], right, body, open, cls, attrs}] — `name` is HTML; a chip
 *  never goes in the head (its click would fight the toggle), a `vlink` may. No body → a flat card. */
function cards(items, o){
  o=o||{};
  items=(items||[]).filter(Boolean);
  if(!items.length) return '';
  return '<div class="cards'+(o.cls?' '+o.cls:'')+'">'+items.map(it=>{
    const attrs=dataEl(it.el)+hayAttr(it.hay)+(it.attrs||'');
    const badges=(it.badges||[]).filter(Boolean);
    const head='<span class="card-n">'+(it.name||'')+'</span>'+
      (it.id!=null&&it.id!==''?'<span class="card-id mono">'+esc(String(it.id))+'</span>':'')+
      (badges.length?'<span class="card-b">'+badges.join('')+'</span>':'')+
      (it.right?'<span class="card-r">'+it.right+'</span>':'');
    if(!it.body) return '<div class="card flat'+(it.cls?' '+it.cls:'')+'"'+attrs+'><div class="card-h"><span class="tdc"></span>'+head+'</div></div>';
    return '<details class="card'+(it.cls?' '+it.cls:'')+'"'+attrs+(it.open?' open':'')+
      '><summary class="card-h"><span class="tdc">'+uiIcon('chevron')+'</span>'+head+'</summary>'+
      '<div class="card-x">'+it.body+'</div></details>';
  }).join('')+'</div>';
}
/** rows: [[label, value], …] — value a string (escaped, copyable) or {html, copy, mono}; `copy:null`
 *  opts out of the copy button. `o.cls` picks the variant (`facts` under the title). */
function props(rows, o){
  o=o||{};
  rows=(rows||[]).filter(r=>r&&r[1]!==undefined&&r[1]!==null&&r[1]!==''&&!(Array.isArray(r[1])&&!r[1].length));
  if(!rows.length) return '';
  return '<dl class="props'+(o.cls?' '+o.cls:'')+'">'+rows.map(r=>{
    const v=r[1], isO=v&&typeof v==='object'&&v.html!==undefined;
    const shown=isO?v.html:esc(String(v));
    const ct=isO?(v.copy!=null?String(v.copy):null):(typeof v==='number'?null:String(v));
    return '<div class="fact"><dt>'+esc(r[0])+'</dt><dd'+(isO&&v.mono?' class="mono"':'')+'>'+shown+copyBtn(ct,r[0])+'</dd></div>';
  }).join('')+'</dl>';
}
/** A script or template body with its header — language, size, findings, copy. `o.wrap` for prose
 *  (prompts, documentation) that should wrap rather than scroll; `o.label` names what the code is. */
function codeblk(src, lang, problems, o){
  if(src==null||src==='') return '';
  o=o||{};
  const pr=problems||[], n=String(src).split('\n').length;
  return '<div class="codeblk'+(o.wrap?' wrap':'')+'">'+
    '<div class="code-h">'+(lang?'<span class="tag">'+esc(lang)+'</span>':'')+
    (o.label?'<span class="code-l">'+o.label+'</span>':'')+
    '<span class="muted">'+n+' line'+(n>1?'s':'')+'</span>'+scriptIssueBadge(pr)+
    '<span class="code-sp"></span>'+copyBtn(String(src),'code')+'</div>'+
    scriptProblemsHtml(pr)+codeBoxHtml(src, lang, pr)+'</div>';
}
/**
 * A text box plus optional chips over the rows that follow it in the same section body. Chips are
 * single-select: `{fk, fv, label, n}` keeps only rows whose `data-<fk>` equals `fv` (`fv:'all'` keeps
 * all). wireSectionFilter() does the work; this only draws.
 */
function filterBar(o){
  o=o||{};
  const chips=(o.chips||[]).map((c,i)=>'<button type="button" class="pchip'+((c.on||(i===0&&c.fv==='all'))?' on':'')+
    '" data-fk="'+esc(c.fk||'')+'" data-fv="'+esc(c.fv)+'">'+esc(c.label)+
    (c.n!=null?'<span class="pchipn">'+esc(String(c.n))+'</span>':'')+'</button>').join('');
  return '<div class="pbar fbar"><input class="pf" type="search" placeholder="'+esc(o.placeholder||'filter…')+
    '" aria-label="'+esc(o.label||o.placeholder||'Filter')+'">'+chips+(o.extra||'')+'<span class="pcount" role="status"></span></div>';
}
/**
 * Live filter behind every filter bar under `root`: text over each row's `data-hay` (or its text), one
 * chip group per bar. A row is a leaf `[data-hay]` (a table row, a mapping, a card without rows); a
 * container (a card, an expandable row, a `.fgroup`) with leaf rows inside hides when all of them are
 * hidden and opens when a match is inside it. Pure show/hide — nothing re-renders.
 */
function wireSectionFilter(root){
  root.querySelectorAll('.fbar').forEach(bar=>{
    const scope=bar.closest('.sb')||bar.closest('[data-fscope]')||bar.parentElement;
    const input=bar.querySelector('.pf'), chips=[...bar.querySelectorAll('.pchip[data-fv]')], count=bar.querySelector('.pcount');
    const all=[...scope.querySelectorAll('[data-hay]')].filter(el=>!el.closest('.fbar'));
    const leaves=all.filter(el=>!el.querySelector('[data-hay]'));
    // The count answers the chips: accepted rows and the rule/notes tables are filtered too, but "12 of
    // 340" against a chip saying 42 open contradicted itself.
    const counted=leaves.filter(el=>!el.closest('details.chk-acc, #chk-waived, #chk-notes'));
    const containers=[...scope.querySelectorAll('details.card, details.tr, details.sect, details.chk-acc, .fgroup')].filter(c=>c.querySelector('[data-hay]'));
    // A report page's own bar keeps its text and chip in the URL (see syncHashContext) and starts from it.
    const pageBar=state.view!=='browse' && scope.classList.contains('dash');
    if(pageBar){
      if(state.rf) input.value=state.rf;
      const pre=state.rc && chips.find(x=>x.dataset.fv===state.rc);
      if(pre) chips.forEach(x=>x.classList.toggle('on', x===pre));
    }
    const apply=()=>{
      const q=(input.value||'').trim().toLowerCase();
      const on=chips.find(c=>c.classList.contains('on'));
      const fk=on&&on.dataset.fk, fv=on?on.dataset.fv:'all';
      let shown=0;
      leaves.forEach(row=>{
        const okC=(!fk||fv==='all'||row.dataset[fk]===fv);
        const okQ=!q||(row.dataset.hay||row.textContent||'').toLowerCase().indexOf(q)>=0;
        const ok=okC&&okQ; row.hidden=!ok; if(ok && counted.indexOf(row)>=0) shown++;
      });
      containers.forEach(c=>{
        const any=[...c.querySelectorAll('[data-hay]')].some(r=>!r.hidden&&!r.querySelector('[data-hay]'));
        c.hidden=!any; if(any&&(q||fv!=='all')) c.open=true;
      });
      if(count) count.textContent=(q||fv!=='all')?shown+' of '+counted.length:'';
      if(pageBar){ state.rf=(input.value||'').trim(); state.rc=(fk&&fv&&fv!=='all')?fv:''; syncHashContext(); }
    };
    input.addEventListener('input', debounce(apply,120));
    chips.forEach(c=>c.onclick=()=>{ chips.forEach(x=>x.classList.toggle('on', x===c)); apply(); });
    if(pageBar && (state.rf||state.rc)) apply();
  });
}

// ---------- the page header: sticky bar + hero ----------
/** Kind · key ⧉ · path ⧉ ↗ — the identity line under the title. */
function identLine(n){
  const hint=term('type', n.type).hint;
  return '<div class="dident">'+
    '<span class="dkindw"'+(hint?' data-tip="'+esc(hint)+'"':'')+'>'+esc(nodeKind(n))+'</span>'+
    '<span class="dsep" aria-hidden="true">·</span><span class="dkey mono">'+esc(n.key)+copyBtn(n.key,'key')+'</span>'+
    (n.file?'<span class="dsep" aria-hidden="true">·</span><span class="dfile" data-tip="Click to copy the path" data-copy="'+enc(n.file)+
      '"><span class="fp">'+esc(n.file)+'</span>'+copyBtn(n.file,'path')+openBtn(n.file)+'</span>':'')+
    '</div>';
}
/** Icon tile, title, identity line, Design's description as prose, and the facts strip. The tile's
 *  tint derives from the same --c-<type> token the icon uses, so it survives the IDE palette as the
 *  icons do. */
function heroHtml(n, facts){
  const d=n.data||{};
  return '<div class="dhero">'+
    '<div class="dhero-top"><span class="dtile" style="--tc:'+nodeColor(n)+'">'+nodeIcon(n)+'</span>'+
    '<div class="dhero-main"><div class="dtitle">'+esc(n.label)+authBadge(n)+'</div>'+identLine(n)+
    (d.description?'<p class="ddesc">'+esc(String(d.description))+'</p>':'')+'</div></div>'+
    props(facts,{cls:'facts'})+'</div>';
}
/** One chip per rendered section, in page order — the answer to a twenty-section page. Fewer than
 *  three sections need no map. */
function secnavHtml(reg){
  if(!reg||reg.length<3) return '';
  return '<nav class="secnav" aria-label="Sections on this page">'+reg.map(s=>
    '<button type="button" class="snc" data-jump-sect="'+enc(s.id)+'">'+esc(s.title)+
    (s.count!=null?'<span class="snn">'+esc(String(s.count))+'</span>':'')+'</button>').join('')+'</nav>';
}
// ---------- in/out parameters ----------
// A model's `parameters` is one flat list of {element,elementName,elementType,elementSubType,dir,kind,
// source,target,…} records — every flavour of Flowable variable mapping normalised to source -> target.
const PDIR_COLOR={'in':'--info-text','out':'--ok-text','error-out':'--bad-text'};
// "3 in · 1 out" — direction tally in a fixed order, so the label reads the same everywhere.
function paramSummary(list){
  const c={}; (list||[]).forEach(p=>{ c[p.dir]=(c[p.dir]||0)+1; });
  return ['in','out','error-out'].filter(k=>c[k]).map(k=>c[k]+' '+k).join(' · ');
}
// group by declaring element, first-seen order (which is document order)
function paramGroups(list){
  const g=new Map();
  (list||[]).forEach(p=>{
    const k=p.element==null?'':String(p.element);
    if(!g.has(k)) g.set(k,{element:p.element,name:p.elementName,type:p.elementType,sub:p.elementSubType,
                           refKind:p.refKind,refKey:p.refKey,rows:[]});
    g.get(k).rows.push(p);
  });
  return [...g.values()];
}
// The model a group's parameters are mapped onto. `rest` is a URL, not a model — there is no node to link.
function calleeNodeId(g){
  if(!g.refKey || !g.refKind || g.refKind==='rest') return null;
  const id=g.refKind+':'+g.refKey;
  return byId.get(id) ? id : null;
}
// One group of mapping rows, headed by the declaring element and *what it calls* — a card item for cards().
// `hasDg`: the node has a diagram — the group gets a ⌖ locate button targeting its element.
function paramGroupHtml(g, extraBody, hasDg){
  const label=g.name||g.element||'—';
  // The element id, when the label isn't already it: that is what you search for in the BPMN/CMMN XML or
  // pick out on the diagram, and a named task would otherwise never show it.
  const eid=(g.element!=null&&String(g.element)!==label)?g.element:null;
  const loc=(hasDg&&g.element!=null)?locateBtn(String(g.element), g.name):'';
  // the callee by name in the head (visible without expanding) … and as a chip in the body, where a
  // click cannot fight the toggle
  const cid=calleeNodeId(g);
  const chip=cid?'<div class="nodechips">'+nodeChip(cid)+'</div>':'';
  return {el:g.element, name:esc(label)+loc, id:eid, open:true,
    badges:[g.refKey?'<span class="opref">→ '+esc(String(g.refKey))+'</span>':'', kindTag(g.type, g.sub)],
    right:g.rows.length+' param'+(g.rows.length>1?'s':''),
    body:(extraBody||'')+chip+'<div class="parmgrid">'+g.rows.map(paramRow).join('')+'</div>'};
}
// data-el attribute for a detail row/group attributed to a model element — the reveal contract with
// the diagram (revealByEl / dgCardHtml match on it).
function dataEl(id){ return (id==null||id==='')?'':' data-el="'+esc(String(id))+'"'; }
// ⌖ — pans the diagram to the element and highlights it (wired in renderDetail).
function locateBtn(id, name){
  return '<button type="button" class="dgloc" data-el-ref="'+esc(String(id))+'"'+
    (name?' data-el-name="'+esc(String(name))+'"':'')+
    ' data-tip="Show on diagram" aria-label="Show on diagram">'+LOC_SVG+'</button>';
}
// A mapping side may be a backend variable, a frontend `{{…}}` binding (form buttons map bindings), or
// neither — a callee-side contract name or an expression. Try each node kind, then fall back to text.
function paramSide(x){
  const asVar=vlink('variable:'+x, x);
  if(byId.get('variable:'+x)) return asVar;
  if(String(x).indexOf('{{')>=0 && byId.get('binding:'+x)) return vlink('binding:'+x, x);
  return asVar;                                   // vlink already degraded to escaped text
}
// split a comma/semicolon group list, drop dynamic ${…}/{{…}} entries, link each to its group node
const groupLinksHtml=v=>String(v==null?'':v).split(/[,;]/).map(s=>s.trim()).filter(g=>g&&!/\$\{|\{\{/.test(g))
  .map(g=>vlink('group:'+g,g)).join(', ');
// Design's name for an element type, from `elementType` plus the `flowable:type` refinement.
function elementTerm(type, sub){
  if(!type) return '';
  if(DESIGN_TERMS['el:'+type+'/'+sub]) return termHtml('el', type+'/'+sub);
  // a CMMN <task flowable:type="…"> is the same thing as a BPMN service task of that type,
  // so the serviceTask/* terms cover both dialects
  if(sub && type==='task' && DESIGN_TERMS['el:serviceTask/'+sub]) return termHtml('el', 'serviceTask/'+sub);
  if(DESIGN_TERMS['el:'+type]) return termHtml('el', type)+(sub?'<span class="opsub"> · '+esc(sub)+'</span>':'');
  return esc([type,sub].filter(Boolean).join(' · '));
}
function paramFlowHtml(p){
  const arrow=' <span class="pa">→</span> ';
  const has=x=>x!=null&&x!=='';
  // A one-sided mapping still gets its arrow: `→ total` reads as "the result lands in total", where a bare
  // `total` would leave you guessing which end of the flow you are looking at.
  if(has(p.source)&&has(p.target)) return paramSide(p.source)+arrow+paramSide(p.target);
  if(has(p.target)) return arrow.trimStart()+paramSide(p.target);
  if(has(p.source)) return paramSide(p.source)+arrow.trimEnd();
  return '';
}
function paramRow(p){
  // the mapping kind gets Design's wording plus a tooltip; type/transient stay as the model spells them
  const tags=termHtml('kind', p.kind, 'pt')+
    [p.type,p.transient?'transient':''].filter(Boolean).map(t=>'<span class="pt">'+esc(t)+'</span>').join('');
  // data-dir / data-hay let the filter and the search highlight work without re-rendering or text parsing
  return '<div class="pc" data-dir="'+esc(p.dir)+'" data-hay="'+esc(paramHaystack(p).toLowerCase())+'">'+
    '<span class="pd" style="color:var('+(PDIR_COLOR[p.dir]||'--ink-faint')+')">'+esc(p.dir)+'</span>'+
    '<span class="pn">'+paramFlowHtml(p)+'</span>'+tags+'</div>';
}
// Above this many rows a flat list stops being readable, so the section gets a filter of its own.
const PARAM_FILTER_FROM=12;
function paramSection(list, hasDg){
  const gs=paramGroups(list);
  let tools='';
  if(list.length>=PARAM_FILTER_FROM){
    const c={}; list.forEach(p=>{ c[p.dir]=(c[p.dir]||0)+1; });
    tools=filterBar({placeholder:'filter parameters…', label:'Filter parameters', total:list.length,
      chips:[{fk:'dir',fv:'all',label:'all',n:list.length}]
        .concat(['in','out','error-out'].filter(d=>c[d]).map(d=>({fk:'dir',fv:d,label:d,n:c[d]})))});
  }
  return section('params','Parameters', cards(gs.map(g=>paramGroupHtml(g, null, hasDg))),
    {count:list.length, hint:paramSummary(list), tools});
}

// ---------- form / page components ----------
// A field id like `customer.email` binds the variable root `customer`. Top-level because the Fields
// rows and the REST-call rows both link ids this way.
function fieldLink(id){
  const s=String(id==null?'':id), r=s.replace(/^\$/,'').split('.')[0].split('[')[0];
  return byId.get('variable:'+r)
    ? '<span class="vlink" data-id="'+enc('variable:'+r)+'" tabindex="0" role="link">'+esc(s)+'</span>' : esc(s);
}
// What a button setting is called for a reader. Design has no dialog label for several of them, so the
// wording says what the setting *does* — the raw key is the fallback, as everywhere else.
const FSET_LABEL={script:'expression', timer:'re-runs every', autoExecute:'auto-execute',
  executeAlways:'runs while disabled', method:'method', path:'response path',
  valueExpression:'value expression', navigationUrl:'then opens', scopeType:'scope',
  scopeId:'scope id', scopeDefinitionId:'scope definition',
  invokeActionUrl:'invoke url', invokeServiceUrl:'invoke url', target:'opens in', primary:'primary',
  ignoreValidation:'skips validation', ignorePayload:'sends no payload', keepInForm:'stays in the form',
  visible:'visible when', enabled:'enabled when', ignore:'value dropped when'};
// The order the body reads in, whatever order the model happened to store: what it evaluates, then what
// it does with the result, then when it applies, then the plain on/off flags (which fall out last).
const FSET_ORDER=['script','timer','method','path','valueExpression','navigationUrl','target',
  'scopeType','scopeId','scopeDefinitionId','invokeActionUrl','invokeServiceUrl',
  'visible','enabled','ignore'];
// Whether it renders, can be used, and is submitted. A literal settles it — that belongs in the summary,
// because you must not have to expand a row to learn the button never appears; an expression makes it
// conditional, which belongs in the body where it fits. `ignore`'s default is the opposite of the others'.
const FGATES=[['visible',false,'hidden'],['enabled',false,'disabled'],['ignore',true,'not submitted']];
// `timer` is the `setInterval` delay the form runtime uses, i.e. milliseconds — shown as the interval a
// reader thinks in.
function fsetValue(k,v){
  if(k!=='timer') return String(v);
  const ms=Number(v);
  return !isFinite(ms)?String(v):(ms%1000?ms+' ms':(ms/1000)+' s');
}
/**
 * The facts under the title — one entry per node type, each pushing [label, value] rows through the
 * helpers in `x`: `add` (a plain value, copyable), `mono` (an identifier, expression or path), `addCount`
 * (a count with no section of its own — a count a section already carries belongs in that section's
 * heading, not here), `addStarters`, `varList`. Design's `description` is the hero's prose, not a fact.
 * `page` reads like a form and a `binding` like an expression; `_` renders whatever scalars an untyped
 * node carries. The rows are rendered by props() in the hero.
 */
const FACTS={
  process(n,d,x){ x.addStarters(d.candidateStarterGroups); x.add('Documentation',d.documentation); },
  case(n,d,x){ x.addStarters(d.candidateStarterGroups);
    if(d.initiatorVariableName) x.rows.push(['Initiator var',{html:vlink('variable:'+d.initiatorVariableName, d.initiatorVariableName)}]);
    x.add('Documentation',d.documentation); },
  decision(n,d,x){ if(d.decisionService) x.add('Kind','Decision service');
    // a decision service's members, each a decision of its own
    if(d.decisionService&&(d.decisions||[]).length)
      x.rows.push(['Decisions',{html:d.decisions.map(k=>byId.get('decision:'+k)?vlink('decision:'+k,k):esc(String(k))).join(', ')}]);
    x.add('Hit policy',d.hitPolicy);
  },
  form(n,d,x){},
  page:'form',
  app(n,d,x){ x.add('Theme',d.theme);
    const ga=String(d.groupsAccess||'').split(/[,;]/).map(s=>s.trim()).filter(Boolean);
    if(ga.length) x.rows.push(['Groups with access',{html:ga.map(g=>vlink('group:'+g,g)).join(', ')}]); },
  dataDictionary(n,d,x){ x.mono('Types',(d.types||[]).length&&(d.types||[]).join(', ')); },
  securityPolicy(n,d,x){ x.add('Type',d.type); },
  dataObject(n,d,x){ x.add('Type',d.dataObjectType); x.mono('Data source',d.sourceId);
    if(d.service) x.rows.push(['Backing service',{html:vlink('service:'+d.service, d.service, 'Service model '+d.service)}]);
    // When backed by a service, surface that service's physical table here and link the name back to the service node.
    const svc=d.service&&byId.get('service:'+d.service), tbl=d.serviceTableName||(svc&&(svc.data||{}).tableName);
    if(tbl) x.rows.push(['Table',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Provided by service '+esc(d.service)+'">'+esc(tbl)+'</span>', copy:tbl}]);
    if(d.dictionary) x.rows.push(['Data dictionary',{html:vlink('dataDictionary:'+d.dictionary, d.dictionary)}]); },
  service(n,d,x){ x.add('Type',d.type); x.mono('Base URL',d.baseUrl); x.add('Auth',d.auth); x.mono('Table',d.tableName);
    if(d.referencedLiquibaseModelKey){ const lid=(byId.get('liquibase:'+d.referencedLiquibaseModelKey)&&'liquibase:'+d.referencedLiquibaseModelKey)||outTo(n.id,'schema');
      x.rows.push(['Liquibase model',{html:vlink(lid, d.referencedLiquibaseModelKey)}]); }
    if(d.schemaCoverage){ const c=d.schemaCoverage.counts||{}; const g=(c.noService||0)+(c.noDataObject||0); if(g) x.add('Schema gaps',g+' of '+(c.total||0)+' columns'); } },
  serviceOperation(n,d,x){
    if(d.service) x.rows.push(['Service',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Defined by service '+esc(d.service)+'">'+esc(d.service)+'</span>'}]);
    x.add('Name',d.name); x.mono('Method',d.method); x.mono('URL',d.fullUrl||d.url);
  },
  agent(n,d,x){
    // compose only what is there — "Vendor / model: /" and "API endpoint: undefined" were rows once
    x.add('Vendor / model',[d.aiVendor,d.modelName].filter(Boolean).join(' / '));
    x.add('Temperature',d.temperature);
    if(d.enableApiEndpoint!=null) x.add('API endpoint', d.enableApiEndpoint?'enabled':'disabled');
    if(d.knowledgeBase) x.rows.push(['Knowledge base',{html:vlink('knowledgeBase:'+d.knowledgeBase, d.knowledgeBase)}]); },
  channel(n,d,x){ x.add('Direction',d.channelType); x.add('Type',d.type); x.mono('Topics',(d.topics||[]).join(', ')); x.mono('Destination',d.destination);
    if(d.eventKey&&d.eventKey.fixedValue) x.rows.push(['Event',{html:vlink('event:'+d.eventKey.fixedValue, d.eventKey.fixedValue)}]); },
  event(n,d,x){
    // payload entries are `{name, type, …}` records (older payloads were bare names)
    x.mono('Correlation',(d.correlation||[]).join(', ')); },
  java(n,d,x){ x.mono('Package',d.package); x.add('Roles',(d.roles||[]).join(', ')); x.add('Bot key',d.botKey); x.mono('Implements',(d.interfaces||[]).join(', ')); },
  endpoint(n,d,x){ x.mono('Method',d.http); x.mono('Path',d.path);
    if(d.controller||d.handler) x.rows.push(['Handler',{html:vlink(incFrom(n.id,'serves'), [d.controller,d.handler].filter(Boolean).join('#')), copy:d.controller||undefined}]); },  // FQN for 'Go to Class'
  method(n,d,x){ if(d.name) x.rows.push(['Method',{html:esc(d.name)+'()', copy:d.name}]);  // copy the bare name for IntelliJ 'Go to Symbol'
    if(d.class) x.rows.push(['Declared in',{html:vlink(d.declaredIn||'java:'+d.class, d.class), copy:d.class}]); },  // FQN for 'Go to Class'
  query(n,d,x){ x.mono('Source index',d.sourceIndex); x.add('Type',d.type);
    // parameters are `{name, type, …}` records (the legacy regex pass produced bare names)
    x.add('Parameters',(d.parameters||[]).map(p=>(p&&typeof p==='object')?p.name:p).filter(Boolean).join(', '));
    x.mono('Sort by',(d.sortParameters||[]).join(', '));
    x.mono('Aggregations',(d.aggregations||[]).join(', '));
    x.add('Filters by groups',(d.groups||[]).length); },
  sla(n,d,x){ x.add('Type',d.slaType||d.scopeType); x.mono('Calendar',d.businessCalendarType);
    if(d.completionDueDateValue!=null) x.add('Completion due',d.completionDueDateValue+' '+(d.completionDueDateTimeUnit||''));
    else x.add('Completion due',d.completionDueDateExpression);
    if(d.inProgressStartDueDateValue!=null) x.add('In-progress due',d.inProgressStartDueDateValue+' '+(d.inProgressStartDueDateTimeUnit||''));
    if(d.inProgressStartOnClaim!=null) x.add('Starts on claim',String(d.inProgressStartOnClaim));
    x.mono('Task',d.taskDefinitionKey); },
  sequence(n,d,x){ x.mono('Format',d.format);
    x.add('Start',d.start!=null?d.start:d.startValue); x.add('Increment',d.increment);
    if(d.cycle) x.add('Cycle','true'); },
  template(n,d,x){ x.add('Type',d.templateType||d.documentType||d.type);
    x.addCount('Variations',(d.variations||[]).length);
    if((d.variationParameters||[]).length) x.rows.push(['Variation parameters',
      {html:d.variationParameters.map(p=>esc(String(p.name||''))+(p.defaultValue!=null?' <span class="pt">'+esc(String(p.defaultValue))+'</span>':'')).join(', ')}]); },
  knowledgeBase(n,d,x){ x.add('Type',d.type); x.mono('Input source',d.inputSource);
    x.mono('Content path',d.contentItemsPath); x.add('Top K',d.topK); x.add('Similarity',d.similarityThreshold);
    const vs=d.vectorStore||{};
    if(vs.type) x.add('Vector store',vs.type+(vs.embeddingModel?' · '+vs.embeddingModel:''));
    if(vs.credentials) x.add('Credentials',vs.credentials); },
  variableExtractor(n,d,x){ x.mono('Source index',d.sourceIndex);
    if((d.fullTextVariables||[]).length) x.rows.push(['Full-text',x.varList(d.fullTextVariables)]); },
  document(n,d,x){ if(d.versioning!=null) x.add('Versioning',String(d.versioning));
    x.add('Initial state',d.initialState); x.add('Initial type',d.initialType);
    x.addCount('Variables',(d.variables||[]).length); x.add('AI instructions',d.aiInstructions); },
  action(n,d,x){
    // Link the bot to whatever the graph resolved (action --bot--> java:<fqn> | bot:<key> | model node):
    // a Java bot keeps its class chip; any other resolved bot gets an inline link; only a truly
    // unresolved bot stays plain text.
    const be=(outM.get(n.id)||[]).find(e=>e.rel==='bot');
    if(be && byId.get(be.id)){ const bl=d.botKey||byId.get(be.id).label;
      x.rows.push(['Bot',{html: be.id.indexOf('java:')===0 ? jchip(be.id, bl) : vlink(be.id, bl)}]); }
    else x.add('Bot',d.botKey);
    if(d.formKey){ const fid=(byId.get('form:'+d.formKey)&&'form:'+d.formKey)||(byId.get('page:'+d.formKey)&&'page:'+d.formKey)||outTo(n.id,'action-form');
      x.rows.push(['Form',{html:vlink(fid, d.formKey)}]); }
    if(d.signalName){
      // start-instance bots carry a model key in signalName; other bots a real signal name
      const isP=d.botKey==='bpmn-start-process-instance-bot', isC=d.botKey==='cmmn-start-case-instance-bot';
      const sid=isP?'process:'+d.signalName:isC?'case:'+d.signalName:'signal:'+d.signalName;
      x.rows.push([isP?'Starts process':isC?'Starts case':'Triggers signal',{html:vlink(sid, d.signalName)}]);
    }
    x.mono('Scope',d.scopeType);
    if(d.script) x.add('Script',d.scriptLanguage||'script');
    const pg=(d.permissionGroups||[]).filter(g=>typeof g==='string'&&g);
    if(pg.length) x.rows.push(['Allowed groups',{html:pg.map(g=>vlink('group:'+g,g)).join(', ')}]);
    const chs=(d.channels||[]).map(c=>typeof c==='string'?c:(c&&c.key)).filter(Boolean);
    if(chs.length) x.rows.push(['Channels',{html:chs.map(c=>vlink('channel:'+c,c)).join(', ')}]); },
  bot(n,d,x){ x.add('Kind',d.platform?'Flowable platform bot':'project-defined bot'); },
  liquibase(n,d,x){ const a=d.authority||{};
    x.add('Status', a.status==='live'?'live (authoritative)':a.status==='superseded'?'superseded revision':a.status==='orphan'?'orphan — unreferenced':undefined);
    if((a.referencedBy||[]).length) x.rows.push(['Referenced by',{html:a.referencedBy.map(k=>vlink('service:'+k, k)).join(', ')}]);
    if((a.supersededBy||[]).length) x.rows.push(['Live definition',{html:a.supersededBy.map(k=>vlink('liquibase:'+k, k)).join(', ')}]);
    const tables=d.tables||[], eff=d.effectiveTables||[];     // both read: the raw list is the same fact as the effective one
    x.mono('Tables',(eff.length?eff:tables).join(', ')); },


  expression(n,d,x){
    const pr=d.problems||[]; if(pr.length){ const ec=pr.filter(p=>p.severity==='error').length, wc=pr.length-ec;
      x.add('Problems',[ec?ec+' error'+(ec>1?'s':''):'', wc?wc+' warning'+(wc>1?'s':''):''].filter(Boolean).join(', ')); } },
  binding:'expression',
  variable(n,d,x){ x.mono('Scope',(d.scopes||[]).join(', '));
    // Written vs read, which is the fact a reader acts on — and the verdict, when there is one.
    if(d.writeCount||d.readCount) x.add('Direction', (d.writeCount||0)+' written · '+(d.readCount||0)+' read');
    if(d.unread) x.rows.push(['Verdict',{html:'<span class="pt" data-tip="Something writes this variable '+
      'and nothing Atlas can see reads it back. Query and dashboard models, master data and the Work UI '+
      'are not analysed, so check those before deleting it.">never read</span>',copy:null}]);
    if((d.unreadIn||[]).length) x.rows.push(['Verdict',{html:'<span class="pt" data-tip="The variable is '+
      'mapped into a called model that never reads it — the mapping has no effect there.">unread in '+
      esc(d.unreadIn.map(m=>(byId.get(m)||{}).label||m).join(', '))+'</span>',copy:null}]);
    if(d.readsUnknown) x.rows.push(['Verdict',{html:'<span class="pt" data-tip="Atlas would have listed '+
      'this as unread but declined to: it saw a construct whose direction it cannot determine, or a '+
      'reader outside the models it parses.">readers unknown</span>',copy:null}]);
    // nothing but a bare identifier in a script says this exists — same ≈ vocabulary as uncertain links
    if(d.heuristic) x.rows.push(['Evidence',{html:'<span class="pt" data-tip="Only a bare identifier in a '+
      'script body names this variable — Flowable puts scope variables into the script binding, so it is '+
      'probably real, but Atlas cannot prove it.">≈ script read only</span>',copy:null}]); },
  string(n,d,x){},
  customFunction(n,d,x){
    x.add('Kind', d.kind==='namespace'?('namespace '+(d.namespace||'?')+'.*'):d.kind==='flw'?'flw.* member':'top-level');
    x.mono('Signature', (d.member||n.label||'')+'('+(d.signature!=null?d.signature:'…')+')');
    x.mono('Registered in',(d.sources||[]).join(', ')); },
  external(n,d,x){ x.add('Kind',d.flowableApi?'Flowable platform API':d.route?'In-app navigation route':d.platform?'Flowable platform bean':d.missingModel?'Missing model reference ('+(d.kind||'model')+')':d.dynamic?'Dynamic reference (expression) — expected '+(d.kind||'model'):(d.external_url?'External URL':d.kind||'external')); if(d.method&&d.method!=='(button)') x.mono('Method',d.method); },
  _(n,d,x){ Object.keys(d).forEach(k=>{
    // property probes, not reads: reading `d[k]` here would mark every container as consumed for the
    // "Other attributes" fallback while rendering only the scalars
    const desc=Object.getOwnPropertyDescriptor(d,k), v=desc&&desc.value;
    if(k!=='description'&&(typeof v==='string'||typeof v==='number')) x.add(k,d[k]); }); }
};
function factsFor(n){
  const d=n.data||{}, rows=[];
  const add=(k,v)=>{ if(v!==undefined&&v!==null&&v!==''&&!(Array.isArray(v)&&!v.length)) rows.push([k,v]); };
  const mono=(k,v)=>{ if(v!==undefined&&v!==null&&v!==''&&!(Array.isArray(v)&&!v.length)) rows.push([k,{html:esc(String(v)),copy:String(v),mono:true}]); };
  // count rows only when there is something to count — a grid of zeros is noise, not information
  const addCount=(k,v)=>{ if(v) rows.push([k,v]); };
  // split a comma/semicolon group list, drop dynamic ${…}/{{…}} entries, link each to its group node
  const addStarters=v=>{ const p=String(v==null?'':v).split(/[,;]/).map(s=>s.trim()).filter(g=>g&&!/\$\{|\{\{/.test(g));
    if(p.length) rows.push(['Starter groups',{html:p.map(g=>vlink('group:'+g,g)).join(', ')}]); };
  // a list of names, each linked to its variable node when one exists (else plain text)
  const varList=a=>({html:(a||[]).filter(x=>x!=null&&x!=='').map(x=>vlink('variable:'+String(x).split('.')[0], x)).join(', '), mono:true});
  let f=FACTS[n.type]||FACTS._; if(typeof f==='string') f=FACTS[f];
  f(n, d, {add, mono, addCount, addStarters, varList, rows});
  // Model-level references a process/case declares (SLA, security policy, event, channel, dictionary,
  // sequence) — the answer to "which SLA governs this" without scanning the edge groups below.
  const MR={'sla-definition-key':['SLA','sla'],'security-policy-model':['Security policy','securityPolicy'],
    'eventType':['Event type','event'],'channelKey':['Channel','channel'],
    'data-dictionary':['Data dictionary','dataDictionary'],'sequence':['Sequence','sequence']};
  (d.modelRefs||[]).forEach(r=>{ if(!r||r.key==null) return;
    const mr=MR[r.rel]||[r.rel,null];
    const id=mr[1]&&byId.get(mr[1]+':'+r.key)?mr[1]+':'+r.key:null;
    rows.push([mr[0],{html:id?vlink(id,r.key):esc(String(r.key))}]); });
  return rows;
}

// ---------- the page schema ----------
// One ordered list of sections per node type — reading order, not code order: what the model IS
// (its fields, its properties, its tasks), then how it behaves, then what flows through it, then what
// it is connected to. Every entry is {id, title, hint, count, build}; `build` returns the body HTML
// ('' skips the section), `count` feeds the heading and the navigator. Types not listed here still
// render through detailExtra() until their page has moved over; the recording proxy around n.data
// keeps "Other attributes" honest either way.
/** Everything a section builder needs, computed once per render. */
function detailCtx(n){
  const d=n.data||{}, hasDg=!!d.diagram, EM=elementNames(n);
  const loc=(id,name)=>hasDg&&id!=null&&id!==''?locateBtn(String(id), name):'';
  // element *name* with the raw id as tooltip — shared by the flow-shaped sections
  const elRef=id=>{ const nm=elName(EM,id);
    return '<span'+(nm!==String(id)?' data-tip="'+esc(String(id))+'"':'')+'>'+esc(nm)+'</span>'; };
  return {d, hasDg, EM, loc, elRef};
}
const tag=s=>(s==null||s==='')?'':'<span class="tag">'+esc(String(s))+'</span>';
const S={};
// --- form / page ---
const FIELD_COLS=[
  {k:'id',    label:'Id',      w:'minmax(12ch,1.2fr)', mono:true},
  {k:'label', label:'Caption', w:'minmax(10ch,1.4fr)', cls:'dim', opt:true},
  {k:'type',  label:'Type',    w:'minmax(9ch,.9fr)',   cls:'tags'},
  {k:'value', label:'Bound to / calls', w:'minmax(12ch,1.6fr)', mono:true, opt:true},
  {k:'flags', label:'',        w:'minmax(8ch,1fr)',    cls:'tags'},
];
S.fields={id:'formfields', title:'Fields', hint:'every component, what it is bound to, and what a button does',
  count:(n,c)=>(c.d.fields||[]).length,
  build:(n,c)=>{ const fs=c.d.fields||[]; if(!fs.length) return '';
    return tbl(FIELD_COLS, fs.map(f=>fieldRow(f,c.d)), {placeholder:'filter fields — id, caption, type…'}); }};
S.outcomes={id:'outcomes', title:'Outcomes', hint:'the buttons that complete the task, value and caption',
  count:(n,c)=>(c.d.outcomes||[]).length,
  build:(n,c)=>{ const os=(c.d.outcomes||[]).filter(o=>o&&(o.value||o.label)); if(!os.length) return '';
    return tbl([{k:'value',label:'Value',w:'minmax(10ch,1fr)',mono:true},{k:'label',label:'Caption',w:'minmax(10ch,2fr)',cls:'dim'}],
      os.map(o=>({hay:(o.value||'')+' '+(o.label||''), cells:{value:esc(o.value||''), label:esc(o.label||'')}}))); }};
S.dataSources={id:'datasources', title:'Data sources', hint:'where selects, tables and lists take their rows from',
  count:(n,c)=>(c.d.dataSources||[]).length,
  build:(n,c)=>{ const ss=c.d.dataSources||[]; if(!ss.length) return '';
    return tbl([{k:'kind',label:'Kind',w:'minmax(8ch,.6fr)',cls:'tags'},{k:'src',label:'Source',w:'minmax(14ch,2fr)',mono:true},
                {k:'op',label:'Operation',w:'minmax(8ch,1fr)',mono:true,opt:true}],
      ss.map(s=>({hay:(s.kind||'')+' '+(s.key||s.url||'')+' '+(s.op||''), cells:{
        kind:termHtml('kind-ds', s.kind, 'tag'),
        src:s.kind==='dataObject'?vlink('dataObject:'+s.key, s.key):s.kind==='service'?vlink('service:'+s.key, s.key):esc(s.url||s.key||''),
        op:esc(s.op||'')}}))); }};
S.restCalls={id:'restcalls', title:'REST calls', hint:'what this form calls over HTTP, and which button does it',
  count:(n,c)=>(c.d.restCalls||[]).length,
  build:(n,c)=>{ const rs=c.d.restCalls||[]; if(!rs.length) return '';
    return tbl([{k:'method',label:'Method',w:'7ch',cls:'tags'},{k:'url',label:'URL',w:'minmax(16ch,3fr)',mono:true},
                {k:'where',label:'Button',w:'minmax(8ch,1fr)',mono:true,opt:true},{k:'path',label:'Response path',w:'minmax(8ch,1fr)',mono:true,opt:true}],
      rs.map(r=>({el:r.where, hay:(r.method||'')+' '+(r.url||'')+' '+(r.where||''), cells:{
        method:tag(r.method), url:esc(r.url||''), where:fieldLink(r.where), path:esc(r.path||'')}}))); }};
S.subforms={id:'subforms', title:'Subforms', hint:'forms embedded in this one',
  count:(n,c)=>(c.d.subforms||[]).length,
  build:(n,c)=>{ const sf=c.d.subforms||[]; if(!sf.length) return '';
    return '<div class="nodechips">'+sf.map(k=>byId.get('form:'+k)?nodeChip('form:'+k)
      :'<span class="nc"><span class="nm">'+esc(String(k))+'</span><span class="ty">form</span></span>').join('')+'</div>'; }};
// --- data object ---
S.properties={id:'columns', title:'Properties', hint:'the fields of the object, typed, with the objects they point at',
  count:(n,c)=>(c.d.columns||[]).length,
  build:(n,c)=>{ const cs=c.d.columns||[]; void c.d.fields;   // `fields` is columns[].name again — read, so it does not surface as an "other attribute"
    if(!cs.length) return '';
    return tbl([{k:'name',label:'Name',w:'minmax(10ch,1.2fr)',mono:true},{k:'label',label:'Label',w:'minmax(10ch,1.4fr)',cls:'dim',opt:true},

                {k:'type',label:'Type',w:'minmax(8ch,.8fr)',cls:'tags'},{k:'ref',label:'Relation',w:'minmax(10ch,1.2fr)',opt:true}],
      cs.map(col=>({hay:(col.name||'')+' '+(col.label||'')+' '+(col.type||'')+' '+(col.refDataObject||''), cells:{
        name:esc(col.name||''), label:esc(col.label||''), type:tag(col.type),
        ref:col.refDataObject?vlink('dataObject:'+col.refDataObject, '→ '+col.refDataObject)+(col.relationship?' <span class="muted">'+esc(col.relationship)+'</span>':''):''}}))); }};
// --- process & case: the elements, in the order a reader asks about them ---
/** A Design term wrapped as a tag — the element kind at the end of a row. */
const kindTag=(type,sub)=>{ const t=elementTerm(type, sub); return t?'<span class="tag">'+t+'</span>':''; };
/** Element name with its id (when the id is not the name) and the ⌖ locate button. */
function elCell(c, rec){
  const nm=rec.name||rec.id||'';
  return esc(nm)+c.loc(rec.id, rec.name)+
    (rec.id&&rec.id!==nm?'<span class="card-id mono">'+esc(String(rec.id))+'</span>':'');
}
/** Execution flags every flow node may carry — dropped by every section until now. */
function elementTags(rec){
  const t=[];
  if(String(rec.async)==='true') t.push(tag('async'));
  if(String(rec.asyncLeave)==='true') t.push(tag('async leave'));
  if(rec.skipExpression) t.push('<span class="tag" data-tip="'+esc('skipped when '+rec.skipExpression)+'">skip if…</span>');
  if(String(rec.eventSubProcess)==='true') t.push(tag('event sub-process'));
  return t.join('');
}
const elHay=(...xs)=>xs.filter(x=>x!=null&&x!=='').join(' ');
S.userTasks={id:'usertasks', title:'User tasks', hint:'who works on what, with which form',
  count:(n,c)=>(c.d.userTasks||[]).length,
  build:(n,c)=>{ const ts=c.d.userTasks||[]; if(!ts.length) return '';
    return tbl([{k:'task',label:'Task',w:'minmax(14ch,1.6fr)'},{k:'form',label:'Form',w:'minmax(10ch,1.2fr)',mono:true,opt:true},
                {k:'groups',label:'Candidate groups',w:'minmax(10ch,1.2fr)',opt:true},{k:'assignee',label:'Assignee',w:'minmax(8ch,1fr)',mono:true,opt:true},
                {k:'tags',label:'',w:'minmax(8ch,1fr)',cls:'tags'}],
      ts.map(t=>({el:t.id, hay:elHay(t.name,t.id,t.formKey,t.candidateGroups,t.assignee), cells:{
        task:elCell(c,t), form:t.formKey?vlink('form:'+t.formKey, t.formKey):'', groups:groupLinksHtml(t.candidateGroups),
        assignee:esc(t.assignee||''),
        tags:[t.dueDate?tag('due '+t.dueDate):'', t.priority?tag('priority '+t.priority):'', t.category?tag(t.category):'', elementTags(t)].join('')}}))); }};
/** One service task, with everything it owns folded in — implementation, callee, result variable, field
 *  injections, a jump to its parameter mappings. */
function serviceTaskCard(s, c){
  const d=c.d, label=s.name||s.id||'';
  const impl=s.class||s.delegateExpression||s.expression||'';
  const short=impl?(s.class?s.class.split('.').pop():(impl.length>36?impl.slice(0,35)+'…':impl)):'';
  const rt=(d.ruleTasks||[]).find(r=>String(r.id)===String(s.id));
  const fields=s.fields||{}, fks=Object.keys(fields);
  const pn=(d.ioParameters||[]).filter(p=>String(p.element)===String(s.id)).length;
  const rows=[];
  if(impl) rows.push(['implementation',{html:'<span class="mono">'+esc(impl)+'</span> '+implLink(s), copy:impl}]);
  if(s.operationKey) rows.push(['operation',{html:esc(s.operationKey), mono:true, copy:s.operationKey}]);
  if(s.topic) rows.push(['topic',{html:vlink('topic:'+s.topic, s.topic), copy:null}]);
  if(s.caseDefinitionKey) rows.push(['starts case',{html:vlink('case:'+s.caseDefinitionKey, s.caseDefinitionKey), copy:null}]);
  if(rt&&rt.decisionRef) rows.push(['decision table',{html:vlink('decision:'+rt.decisionRef, rt.decisionRef), copy:null}]);
  if(s.resultVariable) rows.push(['result variable',{html:paramSide(s.resultVariable), mono:true, copy:null}]);
  if(s.skipExpression) rows.push(['skipped when',{html:esc(s.skipExpression), mono:true, copy:s.skipExpression}]);
  let b=props(rows);
  const callee=stCalleeChip(s); if(callee) b+='<div class="nodechips">'+callee+'</div>';
  if(fks.length){
    // a script field is code, not a one-line value — show it as one
    const plain=fks.filter(k=>k!=='script');
    if(plain.length) b+=tbl([{k:'f',label:'Field injection',w:'minmax(10ch,1fr)',mono:true},{k:'v',label:'Value',w:'minmax(14ch,2fr)',mono:true,cls:'wrap'}],
      plain.map(k=>({hay:k+' '+fields[k], cells:{f:esc(k), v:esc(fields[k]==null?'':String(fields[k]))}})), {filter:false});
    if(fks.indexOf('script')>=0) b+=codeblk(String(fields.script||''), fields.scriptFormat||null, null, {label:'script field'});
  }
  if(pn) b+='<div class="tbl-more"><button type="button" class="dgbtn" data-reveal-el="'+esc(String(s.id))+'">'+pn+' parameter mapping'+(pn>1?'s':'')+' ↓</button></div>';
  return {el:s.id, hay:elHay(label,s.id,impl,s.type,s.operationKey,s.topic), name:esc(label)+c.loc(s.id,s.name), id:s.id!==label?s.id:null,
    badges:[kindTag('serviceTask', s.type||undefined), short?'<span class="mono muted card-short">'+esc(short)+'</span>':'',
      s.resultVariable?'<span class="dir" data-dir="out">out</span> <span class="mono">'+paramSide(s.resultVariable)+'</span>':'', elementTags(s)],
    right:fks.length?fks.length+' field'+(fks.length>1?'s':''):'', body:b};
}
S.serviceTasks={id:'svctasks', title:'Service tasks', hint:'what runs automatically — Java, an expression, or a Flowable task type',
  count:(n,c)=>(c.d.serviceTasks||[]).length,
  build:(n,c)=>{ const st=c.d.serviceTasks||[]; if(!st.length) return ''; return cards(st.map(s=>serviceTaskCard(s,c))); }};
/** One script task — shared by BPMN <scriptTask>, CMMN <task flowable:type="script"> and the Scripts page. */
function scriptCard(t, c, o){
  o=o||{};
  const fmt=t.format||t.scriptFormat, label=t.name||t.id||'';
  const body=(t.documentation?'<p class="ddesc card-doc">'+esc(t.documentation)+'</p>':'')+
    (t.script?codeblk(t.script, fmt, t.problems):'<div class="muted tbl-empty">no script body</div>');
  return {el:t.id, hay:elHay(label,t.id,fmt,t.script,o.hay), name:esc(label)+(c?c.loc(t.id,t.name):''), id:t.id&&t.id!==label?t.id:null,
    badges:[tag(fmt), t.resultVariable?'<span class="dir" data-dir="out">out</span> <span class="mono">'+paramSide(t.resultVariable)+'</span>':'',
      scriptIssueBadge(t.problems), elementTags(t), o.extra||''],
    right:o.right||'', body, open:!!(t.problems||[]).length||!!o.open, attrs:o.attrs||''};
}
S.scriptTasks={id:'scripttasks', title:'Script tasks', hint:'the code the process runs inline, with what it writes',
  count:(n,c)=>(c.d.scriptTasks||[]).length,
  build:(n,c)=>{ const ts=c.d.scriptTasks||[]; if(!ts.length) return ''; return cards(ts.map(t=>scriptCard(t,c))); }};
/** CMMN keeps its script tasks in the plan tree — surface their bodies just like BPMN script tasks. */
S.caseScripts={id:'scripttasks', title:'Script tasks', hint:'the code the case runs inline',
  count:(n,c)=>caseScriptItems(c.d).length,
  build:(n,c)=>{ const cs=caseScriptItems(c.d); if(!cs.length) return ''; return cards(cs.map(t=>scriptCard(t,c))); }};
function caseScriptItems(d){
  const cs=[]; if(d.planModel)(function walk(nd){ if(nd.script||(nd.problems||[]).length) cs.push(nd); (nd.children||[]).forEach(walk); })(d.planModel);
  return cs;
}
S.decisionTasks={id:'decisiontasks', title:'Decision tasks', hint:'business rule tasks and the decision table each one evaluates',
  count:(n,c)=>decisionTaskRows(c.d).length,
  build:(n,c)=>{ const rs=decisionTaskRows(c.d); if(!rs.length) return '';
    return tbl([{k:'task',label:'Task',w:'minmax(14ch,1.6fr)'},{k:'dec',label:'Decision table',w:'minmax(12ch,1.4fr)',mono:true},{k:'tags',label:'',w:'minmax(6ch,.6fr)',cls:'tags'}],
      rs.map(r=>({el:r.id, hay:elHay(r.name,r.id,r.decisionRef), cells:{task:elCell(c,r), dec:r.decisionRef?vlink('decision:'+r.decisionRef, r.decisionRef):'', tags:elementTags(r)}}))); }};
// a decision task modelled as a service task of type dmn already has its card above
function decisionTaskRows(d){ const st=new Set((d.serviceTasks||[]).map(s=>String(s.id))); return (d.ruleTasks||[]).filter(r=>!st.has(String(r.id))); }
S.callActivities={id:'callactivities', title:'Call activities & sub-processes', hint:'the processes this one calls, and the parts that run inside it',
  count:(n,c)=>(c.d.callActivities||[]).length+(c.d.subProcesses||[]).length,
  build:(n,c)=>{ const d=c.d, rows=[];
    (d.callActivities||[]).forEach(a=>rows.push({el:a.id, hay:elHay(a.name,a.id,a.calledElement), cells:{
      el:elCell(c,a), kind:kindTag('callActivity'),
      calls:a.calledElement?(byId.get('process:'+a.calledElement)?vlink('process:'+a.calledElement, a.calledElement):'<span class="mono">'+esc(a.calledElement)+'</span>'):'',
      tags:(a.calledElementType?tag(a.calledElementType):'')+elementTags(a)}}));
    (d.subProcesses||[]).forEach(s=>rows.push({el:s.id, hay:elHay(s.name,s.id,s.type), cells:{
      el:elCell(c,s), kind:kindTag(s.type||'subProcess'), calls:'', tags:elementTags(s)}}));
    if(!rows.length) return '';
    return tbl([{k:'el',label:'Element',w:'minmax(14ch,1.6fr)'},{k:'kind',label:'Kind',w:'minmax(10ch,1fr)',cls:'tags'},
                {k:'calls',label:'Calls',w:'minmax(12ch,1.4fr)',opt:true},{k:'tags',label:'',w:'minmax(6ch,.8fr)',cls:'tags'}], rows); }};
S.otherTasks={id:'othertasks', title:'Other tasks', hint:'receive, send and manual tasks — work the engine records rather than executes',
  count:(n,c)=>(c.d.otherTasks||[]).length,
  build:(n,c)=>{ const ts=c.d.otherTasks||[]; if(!ts.length) return '';
    return tbl([{k:'task',label:'Task',w:'minmax(14ch,2fr)'},{k:'kind',label:'Kind',w:'minmax(10ch,1fr)',cls:'tags'},{k:'tags',label:'',w:'minmax(6ch,.8fr)',cls:'tags'}],
      ts.map(t=>({el:t.id, hay:elHay(t.name,t.id,t.type), cells:{task:elCell(c,t), kind:kindTag(t.type), tags:elementTags(t)}}))); }};
S.events={id:'events', title:'Events', hint:'where the process starts, ends, waits and gets interrupted',
  count:(n,c)=>(c.d.events||[]).length,
  build:(n,c)=>{ const evs=c.d.events||[]; if(!evs.length) return '';
    return tbl([{k:'ev',label:'Event',w:'minmax(12ch,1.4fr)'},{k:'kind',label:'Kind',w:'minmax(10ch,1fr)',cls:'tags'},
                {k:'def',label:'Definition',w:'minmax(8ch,.8fr)',cls:'tags',opt:true},{k:'value',label:'Value',w:'minmax(10ch,1.4fr)',mono:true,opt:true},
                {k:'on',label:'Attached to',w:'minmax(10ch,1.2fr)',opt:true}],
      evs.map(e=>({el:e.id, hay:elHay(e.name,e.id,e.type,e.def,e.value), cells:{
        ev:elCell(c,e), kind:kindTag(e.type), def:tag(e.def), value:esc(e.value||''),
        // a boundary event's host activity, and whether triggering interrupts it
        on:e.attachedTo?c.elRef(e.attachedTo)+(String(e.cancelActivity)==='false'?' '+tag('non-interrupting'):''):''}}))); }};
S.gateways={id:'gateways', title:'Gateways', hint:'where the flow splits and joins',
  count:(n,c)=>(c.d.gateways||[]).length,
  build:(n,c)=>{ const gs=c.d.gateways||[]; if(!gs.length) return '';
    const flowTo=id=>{ const f=(c.d.flows||[]).find(x=>String(x.id)===String(id)); return f?'→ '+c.elRef(f.to):esc(String(id)); };
    return tbl([{k:'gw',label:'Gateway',w:'minmax(12ch,1.6fr)'},{k:'kind',label:'Kind',w:'minmax(10ch,1fr)',cls:'tags'},
                {k:'def',label:'Default flow',w:'minmax(10ch,1.2fr)',opt:true},{k:'tags',label:'',w:'minmax(6ch,.6fr)',cls:'tags'}],
      gs.map(g=>({el:g.id, hay:elHay(g.name,g.id,g.type), cells:{gw:elCell(c,g), kind:kindTag(g.type), def:g.default?flowTo(g.default):'', tags:elementTags(g)}}))); }};
/** The full topology in document order — what runs after what — with each flow's condition beside it.
 *  Any condition the parser recorded for a flow that is not in the flow list is appended, so nothing
 *  the old separate "conditions" section showed can go missing. */
S.flows={id:'flows', title:'Sequence flows', hint:'what runs after what; a condition makes a flow optional',
  count:(n,c)=>flowRows(c.d).length,
  build:(n,c)=>{ const fs=flowRows(c.d); if(!fs.length) return '';
    return tbl([{k:'flow',label:'From → to',w:'minmax(18ch,2fr)'},{k:'name',label:'Name',w:'minmax(8ch,.9fr)',cls:'dim',opt:true},
                {k:'cond',label:'Condition',w:'minmax(14ch,2fr)',mono:true,cls:'wrap',opt:true},{k:'tags',label:'',w:'minmax(6ch,.6fr)',cls:'tags'}],
      fs.map(f=>({el:f.id, hay:elHay(f.name,f.id,f.condition,elName(c.EM,f.from),elName(c.EM,f.to)), cells:{
        flow:c.elRef(f.from)+' <span class="pa">→</span> '+c.elRef(f.to)+c.loc(f.id), name:esc(f.name||''), cond:esc(f.condition||''),
        tags:f.default?'<span class="tag" data-tip="Taken when no other outgoing flow’s condition matches">default</span>':''}})),
      {placeholder:'filter flows — element, condition…'}); }};
function flowRows(d){
  const fs=(d.flows||[]).slice(), ids=new Set(fs.map(f=>String(f.id)));
  (d.conditions||[]).forEach(cd=>{ if(!ids.has(String(cd.id))) fs.push(cd); });
  return fs;
}
S.lanes={id:'lanes', title:'Lanes', hint:'who works which part of the process',
  count:(n,c)=>(c.d.lanes||[]).length,
  build:(n,c)=>{ const ls=c.d.lanes||[]; if(!ls.length) return '';
    return tbl([{k:'lane',label:'Lane',w:'minmax(12ch,1fr)'},{k:'els',label:'Elements',w:'minmax(16ch,3fr)',cls:'tags'}],
      ls.map(l=>({el:l.id, hay:elHay(l.name,l.id), cells:{lane:elCell(c,l),
        els:(l.elements||[]).map(id=>'<span class="tag" data-tip="'+esc(String(id))+'">'+esc(elName(c.EM,id))+'</span>').join('')}}))); }};
S.multiInstance={id:'multiinstance', title:'Multi-instance', hint:'activities that repeat over a collection',
  count:(n,c)=>(c.d.multiInstance||[]).length,
  build:(n,c)=>{ const ms=c.d.multiInstance||[]; if(!ms.length) return '';
    return tbl([{k:'act',label:'Activity',w:'minmax(12ch,1.4fr)'},{k:'over',label:'Collection',w:'minmax(10ch,1.2fr)',mono:true},
                {k:'as',label:'Element variable',w:'minmax(10ch,1.2fr)',mono:true,opt:true},{k:'tags',label:'',w:'minmax(8ch,.8fr)',cls:'tags'}],
      ms.map(m=>({el:m.activity, hay:elHay(elName(c.EM,m.activity),m.collection,m.elementVariable), cells:{
        act:esc(elName(c.EM,m.activity||''))+c.loc(m.activity), over:m.collection?paramSide(m.collection):'', as:m.elementVariable?paramSide(m.elementVariable):'',
        tags:(m.sequential==='true'?tag('sequential'):'')+(m.cardinality?tag('× '+m.cardinality):'')}}))); }};
S.declaredVars={id:'declaredvars', title:'Declared data objects', hint:'the process’s own variable declarations, with type and default',
  count:(n,c)=>(c.d.dataObjects||[]).length,
  build:(n,c)=>{ const os=c.d.dataObjects||[]; if(!os.length) return '';
    return tbl([{k:'v',label:'Variable',w:'minmax(12ch,1.4fr)',mono:true},{k:'type',label:'Type',w:'minmax(8ch,.8fr)',cls:'tags'},{k:'def',label:'Default',w:'minmax(10ch,1.4fr)',mono:true,opt:true}],
      os.map(o=>{ const nm=o.name||o.id||''; return {el:o.id, hay:elHay(nm,o.type,o.default), cells:{
        v:vlink('variable:'+nm, nm), type:tag(o.type?String(o.type).replace(/^xsd:/,''):''), def:o.default!=null&&o.default!==''?esc(String(o.default)):''}}; })); }};

/** Design keeps execution, task and lifecycle listeners in separate property groups — one section each,
 *  named the way Design names them. A script listener expands into its code. */
S.listeners={raw:true, build:(n,c)=>{
  const d=c.d, recs=elementRecords(n);
  const ls=[].concat((d.listeners||[]).map(l=>({owner:null, l})), ...recs.map(r=>(r.listeners||[]).map(l=>({owner:r, l}))))
    .filter(x=>x.l&&(x.l.class||x.l.expression||x.l.delegateExpression||x.l.script));
  const byKind=new Map();
  ls.forEach(x=>{ const k=x.l.kind||'listener'; if(!byKind.has(k)) byKind.set(k,[]); byKind.get(k).push(x); });
  return [...byKind.keys()].sort().map(kind=>{
    const items=byKind.get(kind), title=plural(term('el',kind).label);
    return section('listeners-'+kind, esc(title),
      tbl([{k:'where',label:'On',w:'minmax(12ch,1.4fr)'},{k:'event',label:'Event',w:'minmax(7ch,.6fr)',cls:'tags'},{k:'impl',label:'Implementation',w:'minmax(16ch,2.4fr)',mono:true,cls:'wrap'}],
        items.map(({owner:o, l})=>{
          const impl=l.class?vlink('java:'+l.class, l.class):esc(l.expression||l.delegateExpression||(l.script?'(script)':''));
          return {el:o?o.id:null, hay:elHay(o&&o.name,o&&o.id,l.event,l.class,l.expression,l.delegateExpression), cells:{
            where:o?elCell(c,o):'<span class="muted">'+esc(nodeKind(n))+'</span>', event:tag(l.event),
            impl:impl+(l.class?implLink({class:l.class}):'')},
            body:l.script?codeblk(l.script, l.scriptFormat, l.problems):''}; })),
      {count:items.length, hint:term('el',kind).hint});
  }).join('');
}};
S.eldocs={id:'eldocs', title:'Documentation', hint:'what the modeller wrote about each element',
  count:(n,c)=>elementRecords(n).filter(r=>r.documentation).length,
  build:(n,c)=>{ const docs=elementRecords(n).filter(r=>r.documentation); if(!docs.length) return '';
    return tbl([{k:'el',label:'Element',w:'minmax(12ch,1fr)'},{k:'text',label:'Text',w:'minmax(20ch,3fr)',cls:'wrap dim'}],
      docs.map(r=>({el:r.id, hay:elHay(r.name,r.id,r.documentation), cells:{el:elCell(c,r), text:esc(r.documentation)}}))); }};
// --- case: the plan tree, its sentries and listeners ---
const PLAN_ITEM_COLS=[{k:'item',label:'Plan item',w:'minmax(14ch,1.6fr)'},{k:'kind',label:'Kind',w:'minmax(10ch,1fr)',cls:'tags'},
  {k:'refs',label:'Refers to',w:'minmax(12ch,1.4fr)',opt:true},{k:'rules',label:'',w:'minmax(8ch,.8fr)',cls:'tags',opt:true},
  {k:'crit',label:'Criteria',w:'minmax(12ch,1.4fr)',cls:'tags',opt:true}];
function planTreeHtml(nd, c, CRIT){
  // the item's entry/exit criteria, each with its sentry's condition — right where the item is listed
  const critsOf=x=>CRIT.filter(k=>(k.planItemDef!=null&&String(k.planItemDef)===String(x.id))||(k.planItemDef==null&&k.planItem&&k.planItem===x.name)).map(criterionChip).join(' ');
  const kids=nd.children||[];
  const isStage=x=>x.type==='stage'||x.type==='planFragment'||x.type==='casePlanModel';
  const items=kids.filter(x=>!isStage(x)), stages=kids.filter(isStage);
  const rulesOf=x=>x.rules?Object.keys(x.rules).map(r=>({repetitionRule:'repeatable',requiredRule:'required',manualActivationRule:'manual'}[r]||r)).map(tag).join(''):'';
  const refs=x=>[
    x.formKey?'<span class="muted">form</span> '+vlink('form:'+x.formKey, x.formKey):'',
    x.processRef?'<span class="muted">process</span> '+vlink('process:'+x.processRef, x.processRef):'',
    x.caseRef?'<span class="muted">case</span> '+vlink('case:'+x.caseRef, x.caseRef):'',
    x.decisionRef?'<span class="muted">decision</span> '+vlink('decision:'+x.decisionRef, x.decisionRef):'',
    x.candidateGroups?'<span class="muted">groups</span> '+groupLinksHtml(x.candidateGroups):'',
  ].filter(Boolean).join(' · ');
  let b='';
  if(items.length) b+=tbl(PLAN_ITEM_COLS, items.map(x=>({el:x.id, hay:elHay(x.name,x.id,x.type,x.formKey,x.processRef,x.caseRef,x.decisionRef), cells:{
    item:(x.type==='milestone'?'◆ ':'')+elCell(c,x), kind:kindTag(x.type, x.serviceTaskType||undefined), refs:refs(x), rules:rulesOf(x)+elementTags(x), crit:critsOf(x)}})), {filter:false});
  stages.forEach(s=>{ b+=cards([{el:s.id, hay:elHay(s.name,s.id), open:true,
    name:esc(s.type==='casePlanModel'?'Plan model':(s.name||s.id||s.type))+c.loc(s.id,s.name), id:s.type!=='casePlanModel'&&s.id!==(s.name||s.id)?s.id:null,
    badges:[kindTag(s.type), critsOf(s), rulesOf(s), String(s.autoComplete)==='true'?tag('auto-complete'):''],
    right:(s.children||[]).length+' item'+((s.children||[]).length===1?'':'s'), body:planTreeHtml(s, c, CRIT)}]); });
  return b;
}
S.plan={id:'plan', title:'Case plan model', hint:'stages and plan items, with their criteria, rules and the models they start',
  build:(n,c)=>{ const pm=c.d.planModel; if(!pm) return ''; return planTreeHtml({children:[pm]}, c, caseCriteria(c.d)); }};
S.sentries={id:'sentries', title:'Sentries', hint:'entry and exit criteria — the conditions that make a plan item available or end it',
  count:(n,c)=>sentryRows(c.d).length,
  build:(n,c)=>{ const ss=sentryRows(c.d); if(!ss.length) return ''; const CRIT=caseCriteria(c.d);
    return tbl([{k:'guards',label:'Guards',w:'minmax(14ch,1.6fr)'},{k:'on',label:'On parts',w:'minmax(10ch,1fr)',cls:'tags',opt:true},{k:'cond',label:'Condition',w:'minmax(14ch,2fr)',mono:true,cls:'wrap'}],
      ss.map(s=>{ const uses=CRIT.filter(k=>String(k.sentryRef)===String(s.id))
          .map(k=>(k.type==='entryCriterion'?'entry of ':'exit of ')+elName(c.EM, k.planItemDef!=null?k.planItemDef:(k.planItem||'?')));
        return {el:s.id, hay:elHay(s.id,s.condition,uses.join(' ')), cells:{
          guards:(uses.length?'<span data-tip="'+esc(String(s.id||''))+'">'+esc(uses.join(', '))+'</span>':'<span class="muted">'+esc(s.id||'')+'</span>')+c.loc(s.id),
          on:(s.onParts||[]).filter(Boolean).map(tag).join(''), cond:esc(s.condition||'')}}; })); }};

function sentryRows(d){ return (d.sentries||[]).filter(s=>s.condition||(s.onParts||[]).length); }
S.eventListeners={id:'eventlisteners', title:'Event listeners', hint:'what the case waits for — a timer, a user, a signal, an event',
  count:(n,c)=>(c.d.eventListeners||[]).length,
  build:(n,c)=>{ const es=c.d.eventListeners||[]; if(!es.length) return '';
    return tbl([{k:'l',label:'Listener',w:'minmax(12ch,1.4fr)'},{k:'kind',label:'Kind',w:'minmax(10ch,1fr)',cls:'tags'},{k:'trig',label:'Trigger',w:'minmax(12ch,1.6fr)',mono:true,opt:true}],
      es.map(e=>({el:e.id, hay:elHay(e.name,e.id,e.type,e.timer,e.eventType,e.signalRef), cells:{
        l:elCell(c,e), kind:kindTag(e.type),
        trig:[e.timer?esc(e.timer):'', e.eventType?'<span class="muted">event</span> '+vlink('event:'+e.eventType, e.eventType):'',
              e.signalRef?'<span class="muted">signal</span> '+vlink('signal:'+e.signalRef, e.signalRef):''].filter(Boolean).join(' · ')}}))); }};
// --- decision tables ---
/** Inputs and outputs with what each one reads or writes — `inputDefs`/`outputDefs` when the parser has
 *  them, the bare name lists otherwise. Names link to their variable nodes. */
S.dmnIO={id:'dmnio', title:'Inputs & outputs', hint:'what the table reads, in which order, and what it writes',
  count:(n,c)=>dmnIORows(c.d).length,
  build:(n,c)=>{ const rs=dmnIORows(c.d); if(!rs.length) return '';
    return tbl([{k:'dir',label:'',w:'5ch',cls:'tags'},{k:'label',label:'Label',w:'minmax(10ch,1.2fr)'},{k:'expr',label:'Expression / variable',w:'minmax(12ch,1.6fr)',mono:true},
                {k:'type',label:'Type',w:'minmax(7ch,.7fr)',cls:'tags',opt:true},{k:'allowed',label:'Allowed values',w:'minmax(10ch,1.4fr)',mono:true,opt:true,cls:'wrap'}],
      rs.map(r=>({hay:elHay(r.label,r.expr,r.type), cells:{dir:'<span class="dir" data-dir="'+r.dir+'">'+r.dir+'</span>', label:esc(r.label||''),
        expr:r.expr?vlink('variable:'+String(r.expr).split('.')[0], r.expr):'', type:tag(r.type), allowed:esc(r.allowed||'')}}))); }};
function dmnIORows(d){
  const out=[], ie=d.inputExpressions||[];   // the expression behind a labelled input — that is what actually reads a variable
  const ins=(d.inputDefs||[]).length?d.inputDefs:(d.inputs||[]).map((x,i)=>({label:x, expression:ie[i]}));
  ins.forEach(x=>{ const o=(x&&typeof x==='object')?x:{label:x}; out.push({dir:'in', label:o.label, expr:o.expression||(o.label&&!(d.inputDefs||[]).length?o.label:''), type:o.type, allowed:Array.isArray(o.allowed)?o.allowed.join(', '):o.allowed}); });
  const outs=(d.outputDefs||[]).length?d.outputDefs:(d.outputs||[]).map(x=>({label:x, name:x}));
  outs.forEach(x=>{ const o=(x&&typeof x==='object')?x:{label:x,name:x}; out.push({dir:'out', label:o.label, expr:o.name||o.label, type:o.type, allowed:Array.isArray(o.allowed)?o.allowed.join(', '):o.allowed}); });
  return out;
}
/** The decision table itself — the conditions and values that are the actual business logic. A wide
 *  table scrolls inside the section, never sideways. */
S.dmnRules={id:'dmnrules', title:'Rules', hint:'the decision table — inputs left of the divider, outputs right',
  count:(n,c)=>c.d.ruleCount||(c.d.rules||[]).length,
  build:(n,c)=>{ const d=c.d; if(!(d.rules||[]).length) return '';
    const ann=d.rules.some(r=>r.annotation);
    // `o` marks where the inputs end and the outputs begin
    const cell=(t,v,i)=>'<'+t+(i===0?' class="o"':'')+'>'+esc(v==null||v===''?'—':String(v))+'</'+t+'>';
    const row=r=>'<tr>'+(r.inputs||[]).map(x=>cell('td',x,-1)).join('')+(r.outputs||[]).map((x,i)=>cell('td',x,i)).join('')+(ann?'<td>'+esc(r.annotation||'')+'</td>':'')+'</tr>';
    return '<div class="dmntab"><table><thead><tr>'+(d.inputs||[]).map(x=>cell('th',x,-1)).join('')+(d.outputs||[]).map((x,i)=>cell('th',x,i)).join('')+
      (ann?'<th>annotation</th>':'')+'</tr></thead><tbody>'+d.rules.map(row).join('')+'</tbody></table>'+
      (d.rulesTruncated?'<div class="tbl-more muted">showing '+d.rules.length+' of '+d.rulesTruncated+' rules</div>':'')+'</div>'; }};
// --- access, dictionaries, SLAs, templates, queries, documents, extractors, knowledge bases, events ---
S.permissions={id:'permissions', title:'Permissions', hint:'who may do what',
  count:(n,c)=>(c.d.permissions||[]).length,
  build:(n,c)=>{ const ps=c.d.permissions||[]; if(!ps.length) return '';
    return tbl([{k:'perm',label:'Permission',w:'minmax(14ch,1.4fr)'},{k:'key',label:'Key',w:'minmax(10ch,1fr)',mono:true,cls:'faint',opt:true},{k:'roles',label:'Roles',w:'minmax(14ch,2fr)',cls:'wrap'}],
      ps.map(p=>({hay:elHay(p.label,p.key,(p.roles||[]).join(' ')), cells:{perm:esc(p.label||p.key||''), key:p.label&&p.key&&p.label!==p.key?esc(p.key):'',
        roles:(p.roles||[]).map(r=>vlink('group:'+r,r)).join(', ')}}))); }};
S.dictTypes={id:'dicttypes', title:'Type definitions', hint:'the reusable structures, each with its properties',
  count:(n,c)=>(c.d.typeDefs||[]).length,
  build:(n,c)=>{ const ts=c.d.typeDefs||[]; if(!ts.length) return '';
    return cards(ts.map(t=>{ const ps=t.properties||[];
      return {hay:elHay(t.name,t.parent,ps.map(p=>p.name).join(' ')), name:esc(String(t.name||'')), badges:[t.parent?tag('extends '+t.parent):''],
        right:ps.length?ps.length+' propert'+(ps.length>1?'ies':'y'):'', open:true,
        body:ps.length?tbl([{k:'p',label:'Property',w:'minmax(12ch,1.4fr)',mono:true},{k:'t',label:'Type',w:'minmax(8ch,1fr)',cls:'tags'}],
          ps.map(p=>({hay:elHay(p.name,p.type), cells:{p:esc(String(p.name||'')), t:tag(p.type)}})), {filter:false}):''}; })); }};
S.escalations={id:'escalations', title:'Escalations', hint:'what happens, when, relative to which deadline',
  count:(n,c)=>(c.d.escalations||[]).length,
  build:(n,c)=>{ const es=c.d.escalations||[]; if(!es.length) return '';
    return tbl([{k:'step',label:'Step',w:'minmax(10ch,1fr)'},{k:'when',label:'When',w:'minmax(10ch,1fr)',cls:'tags'},{k:'action',label:'Action',w:'minmax(10ch,1fr)',cls:'dim',opt:true},
                {k:'starts',label:'Starts',w:'minmax(10ch,1fr)',opt:true},{k:'who',label:'Assignee',w:'minmax(8ch,.8fr)',mono:true,opt:true},{k:'cond',label:'Condition',w:'minmax(12ch,1.6fr)',mono:true,cls:'wrap',opt:true}],
      es.map(e=>({hay:elHay(e.stepId,e.on,e.action,e.starts,e.assignee,e.condition), cells:{
        step:esc(String(e.stepId||e.on||'')), when:e.timeValue!=null?tag(String(e.timeValue)+' '+(e.timeUnit||'')+' '+(e.relativeType||'')):'', action:esc(String(e.action||'')),
        starts:e.starts?vlink(byId.get('process:'+e.starts)?'process:'+e.starts:'case:'+e.starts, e.starts):'', who:esc(String(e.assignee||'')), cond:esc(String(e.condition||''))}}))); }};
S.thresholds={id:'thresholds', title:'Thresholds', hint:'the targets the SLA is measured against',
  count:(n,c)=>(c.d.thresholds||[]).length,
  build:(n,c)=>{ const ts=c.d.thresholds||[]; if(!ts.length) return '';
    return tbl([{k:'type',label:'Type',w:'minmax(12ch,1fr)'},{k:'dur',label:'Duration',w:'minmax(10ch,1fr)',mono:true}],
      ts.map(t=>({hay:elHay(t.type,t.duration), cells:{type:esc(String(t.type||'')), dur:esc(String(t.duration||''))}}))); }};
/** The template's actual text — the thing a reader searches for — and each variation with its parameters. */
S.templateBody={id:'templatebody', title:'Template body', hint:'the text, and every variation of it',
  build:(n,c)=>{ const d=c.d; if(!(d.content||(d.variations||[]).length)) return '';
    let b=codeblk(d.content, 'freemarker', null, {wrap:true});
    const vs=(d.variations||[]);
    if(vs.length) b+=cards(vs.map((v,i)=>{ const params=v.parameters?Object.entries(v.parameters):[];
      return {hay:elHay(...params.flat(), v.text), name:'Variation '+(i+1), badges:params.map(([k,val])=>tag(k+': '+val)), open:true,
        body:v.text?codeblk(v.text,'freemarker',null,{wrap:true}):(v.resource!=null?props([['resource',{html:esc(String(v.resource)),mono:true,copy:String(v.resource)}]]):'')}; }));
    return b; }};
S.queryParams={id:'querydef', title:'Query parameters', hint:'what a caller may filter by',
  count:(n,c)=>(c.d.parameters||[]).length,
  build:(n,c)=>{ const ps=c.d.parameters||[]; if(!ps.length) return '';
    return tbl([{k:'name',label:'Name',w:'minmax(12ch,1.2fr)',mono:true},{k:'type',label:'Type',w:'minmax(7ch,.7fr)',cls:'tags'},{k:'label',label:'Label',w:'minmax(10ch,1.4fr)',cls:'dim',opt:true},{k:'req',label:'',w:'minmax(6ch,.6fr)',cls:'tags'}],
      ps.map(p=>{ const o=(p&&typeof p==='object')?p:{name:p}; return {hay:elHay(o.name,o.type,o.label), cells:{name:esc(String(o.name||'')), type:tag(o.type), label:esc(String(o.label||'')), req:o.required?tag('required'):''}}; })); }};
S.queryCols={id:'querycols', title:'Result columns', hint:'the columns a result row carries, and the variable each one reads',
  count:(n,c)=>(c.d.columns||[]).length,
  build:(n,c)=>{ const cs=c.d.columns||[]; if(!cs.length) return '';
    return tbl([{k:'name',label:'Column',w:'minmax(12ch,1.2fr)',mono:true},{k:'label',label:'Label',w:'minmax(10ch,1.4fr)',cls:'dim',opt:true},{k:'v',label:'Variable',w:'minmax(10ch,1.2fr)',mono:true}],
      cs.map(col=>({hay:elHay(col.name,col.label,col.variableName), cells:{name:esc(String(col.name||'')), label:esc(String(col.label||'')), v:col.variableName?vlink('variable:'+col.variableName, col.variableName):''}}))); }};
S.queryTpl={id:'querytpl', title:'Search template', hint:'the query body the index runs',
  build:(n,c)=>codeblk(c.d.templateContent, 'json')};
S.docConfig={id:'docconfig', title:'Forms & permissions', hint:'the form each action opens, and who may perform it',
  build:(n,c)=>{ const d=c.d; if(!(d.forms||(d.actionPermissions||[]).length)) return '';
    let b='';
    if(d.forms) b+=tbl([{k:'op',label:'Action',w:'minmax(8ch,.8fr)',cls:'tags'},{k:'form',label:'Form',w:'minmax(14ch,2fr)'}],
      Object.entries(d.forms).map(([op,fk])=>({hay:elHay(op,fk), cells:{op:tag(op), form:byId.get('form:'+fk)?vlink('form:'+fk, fk):esc(String(fk))}})), {filter:false});
    if((d.actionPermissions||[]).length) b+=tbl([{k:'a',label:'Action',w:'minmax(8ch,.8fr)',cls:'tags'},{k:'g',label:'Groups',w:'minmax(14ch,2fr)',cls:'wrap'}],
      d.actionPermissions.map(a=>({hay:elHay(a.action,(a.groups||[]).join(' ')), cells:{a:tag(a.action), g:(a.groups||[]).map(g=>vlink('group:'+g,g)).join(', ')}})), {filter:false});
    return b; }};
S.docVars={id:'docvars', title:'Variables', hint:'the metadata a document carries',
  count:(n,c)=>(c.d.variables||[]).length,
  build:(n,c)=>{ const vs=c.d.variables||[]; if(!vs.length) return '';
    return tbl([{k:'k',label:'Key',w:'minmax(12ch,1.6fr)',mono:true},{k:'t',label:'Type',w:'minmax(8ch,1fr)',cls:'tags'}],
      vs.map(v=>({hay:elHay(v.key,v.type), cells:{k:fieldLink(v.key), t:tag(v.type)}}))); }};
S.extractors={id:'extractors', title:'Extracted variables', hint:'which indexed variable is written, from which scope’s payload',
  count:(n,c)=>(c.d.extractors||[]).length,
  build:(n,c)=>{ const xs=c.d.extractors||[]; if(!xs.length) return '';
    return tbl([{k:'scope',label:'Scope',w:'minmax(10ch,1fr)'},{k:'from',label:'From',w:'minmax(12ch,1.4fr)',mono:true},{k:'to',label:'→ Variable',w:'minmax(10ch,1.2fr)',mono:true},{k:'type',label:'Type',w:'minmax(7ch,.7fr)',cls:'tags',opt:true}],
      xs.map(x=>{ const sid=x.scope?(byId.get('process:'+x.scope)?'process:'+x.scope:(byId.get('case:'+x.scope)?'case:'+x.scope:null)):null;
        return {hay:elHay(x.scope,x.from,x.path,x.to,x.type), cells:{scope:sid?vlink(sid,(byId.get(sid)||{}).label||x.scope):esc(String(x.scope||'')),
          from:esc(String(x.from||x.path||'')), to:vlink('variable:'+x.to, x.to), type:tag(x.type)}}; })); }};
S.kbSources={id:'kbsources', title:'Sources', hint:'where the knowledge base’s documents come from',
  count:(n,c)=>(c.d.sources||[]).length,
  build:(n,c)=>{ const ss=c.d.sources||[]; if(!ss.length) return '';
    return tbl([{k:'type',label:'Type',w:'minmax(8ch,.8fr)',cls:'tags'},{k:'path',label:'Path',w:'minmax(16ch,3fr)',mono:true,cls:'wrap'}],
      ss.map(s=>({hay:elHay(s.type,s.path), cells:{type:tag(s.type), path:esc(String(s.path||''))}}))); }};
S.payload={id:'payload', title:'Payload', hint:'the event’s fields — the contract every publisher and consumer maps onto',
  count:(n,c)=>(c.d.payload||[]).length,
  build:(n,c)=>{ const pl=c.d.payload||[]; if(!pl.length) return '';
    return tbl([{k:'name',label:'Field',w:'minmax(12ch,1.4fr)',mono:true},{k:'type',label:'Type',w:'minmax(7ch,.8fr)',cls:'tags'},{k:'flags',label:'',w:'minmax(10ch,1fr)',cls:'tags'}],
      pl.map(p=>{ const o=(p&&typeof p==='object')?p:{name:p}; return {hay:elHay(o.name,o.type), cells:{name:vlink('variable:'+String(o.name||'').split('.')[0], o.name||''), type:tag(o.type),
        flags:(o.required?tag('required'):'')+(o.correlation?'<span class="tag" data-tip="Used to match the event to a waiting instance">correlates</span>':'')}}; })); }};
// --- agents, apps, actions ---
S.tools={id:'tools', title:'Tools', hint:'what the agent may call',
  count:(n,c)=>(c.d.tools||[]).length,
  build:(n,c)=>{ const ts=c.d.tools||[]; if(!ts.length) return '';
    return '<div class="nodechips">'+ts.map(t=>{ const id=(t.type||'service')+':'+(t.key||'');
      return byId.get(id)?nodeChip(id):'<span class="nc"><span class="nm">'+esc(t.key||'')+'</span><span class="ty">'+esc(t.type||'')+'</span></span>'; }).join('')+'</div>'; }};
S.agentOps={id:'agentops', title:'Operations', hint:'each operation with the prompts it sends the model',
  count:(n,c)=>(c.d.operations||[]).length,
  build:(n,c)=>{ const os=c.d.operations||[]; if(!os.length) return '';
    return cards(os.map(o=>{ const msgs=[['system',o.systemMessage],['user',o.userMessage]].filter(m=>m[1]);
      return {hay:elHay(o.name,o.key,o.systemMessage,o.userMessage), name:esc(o.name||o.key||''), id:o.key&&o.key!==(o.name||o.key)?o.key:null,
        right:msgs.length?msgs.length+' prompt'+(msgs.length>1?'s':''):'',
        body:msgs.map(m=>codeblk(m[1], null, null, {label:m[0]+' prompt', wrap:true})).join('')}; })); }};
S.appVars={id:'appvars', title:'App variables', hint:'variables every model in the app can read',
  count:(n,c)=>(c.d.variables||[]).length,
  build:(n,c)=>{ const vs=c.d.variables||[]; if(!vs.length) return '';
    return tbl([{k:'k',label:'Key',w:'minmax(12ch,1.6fr)',mono:true},{k:'t',label:'Type',w:'minmax(8ch,1fr)',cls:'tags'}],
      vs.map(v=>({hay:elHay(v.key,v.type), cells:{k:fieldLink(v.key), t:tag(v.type)}}))); }};
S.appPages={id:'apppages', title:'Pages', hint:'the pages the app navigates between',
  count:(n,c)=>(c.d.pages||[]).length,
  build:(n,c)=>{ const ps=c.d.pages||[]; if(!ps.length) return '';
    return '<div class="nodechips">'+ps.map(p=>byId.get('page:'+p.key)?nodeChip('page:'+p.key):'<span class="nc"><span class="nm">'+esc(p.key||'')+'</span><span class="ty">page</span></span>').join('')+'</div>'; }};
S.botScript={id:'script', title:'Bot script', hint:'what the action runs when it is triggered',
  build:(n,c)=>{ const d=c.d; if(!(d.script||(d.scriptProblems||[]).length)) return '';
    return d.script?codeblk(d.script, d.scriptLanguage, d.scriptProblems):scriptProblemsHtml(d.scriptProblems); }};
// --- services and their operations ---
/** An operation's contract has two halves: what a caller must supply and what it gets back. */
function opParamRows(o){
  return (o.params||[]).map(p=>['in',p]).concat((o.outParams||[]).map(p=>['out',p])).map(([dir,p])=>({hay:elHay(dir,p.name,p.type), cells:{
    dir:'<span class="dir" data-dir="'+dir+'">'+dir+'</span>', name:esc(p.name||''), type:tag(p.type), req:p.required?tag('required'):'', def:p.default!=null?esc(String(p.default)):''}}));
}
const OP_PARAM_COLS=[{k:'dir',label:'',w:'5ch',cls:'tags'},{k:'name',label:'Parameter',w:'minmax(12ch,1.6fr)',mono:true},{k:'type',label:'Type',w:'minmax(7ch,.8fr)',cls:'tags'},
  {k:'req',label:'',w:'minmax(6ch,.6fr)',cls:'tags',opt:true},{k:'def',label:'Default',w:'minmax(8ch,1fr)',mono:true,opt:true}];
S.ops={id:'ops', title:'Operations', hint:'what the service offers, and what each call takes and returns',
  count:(n,c)=>(c.d.operations||[]).length,
  build:(n,c)=>{ const os=c.d.operations||[]; if(!os.length) return '';
    return cards(os.map(o=>{
      // link the key to the operation's own node (its "where used" page)
      const opid='serviceOperation:'+n.key+'#'+(o.key||'');
      const key=o.key?(byId.get(opid)?'<span class="vlink mono" data-id="'+enc(opid)+'" tabindex="0" role="link" data-tip="Show where '+esc(o.key)+' is used">'+esc(o.key)+'</span>':'<span class="mono">'+esc(o.key)+'</span>'):'';
      const rows=opParamRows(o);
      return {hay:elHay(o.key,o.name,o.method,o.url,o.fullUrl), name:(o.method?'<span class="tag verb">'+esc(o.method)+'</span> ':'')+'<span class="mono">'+esc(o.fullUrl||o.url||o.name||'')+'</span>',
        badges:[o.name&&o.name!==(o.fullUrl||o.url)?'<span class="muted">'+esc(o.name)+'</span>':'', key], right:rows.length?paramSummary(rows.map(r=>({dir:r.cells.dir.indexOf('"in"')>0?'in':'out'}))):'no params',
        body:rows.length?tbl(OP_PARAM_COLS, rows, {filter:false}):''}; })); }};
S.coverage={id:'coverage', title:'Schema coverage', hint:'Liquibase → service → data object: every column, and where the chain breaks',
  build:(n,c)=>{ const sc=c.d.schemaCoverage;
    return (sc&&(sc.rows||[]).length)?schemaCoverageHtml(sc, false, null, c.d.crossedColumns):''; }};
S.svcColumns={id:'columns', title:'Column mappings', hint:'the service’s fields and the table columns behind them',
  count:(n,c)=>(c.d.columns||[]).length,
  build:(n,c)=>{ const d=c.d, cs=d.columns||[];   // read before the early return: the coverage table shows the same columns
    if(d.schemaCoverage&&(d.schemaCoverage.rows||[]).length) return ''; if(!cs.length) return '';
    const crossIdx=crossedIndex(d.crossedColumns);      // no changelog to compare against, same defect
    return tbl([{k:'name',label:'Field',w:'minmax(12ch,1.4fr)',mono:true},{k:'col',label:'Column',w:'minmax(10ch,1.2fr)',mono:true,cls:'faint',opt:true},{k:'type',label:'Type',w:'minmax(8ch,.8fr)',cls:'tags'}],
      cs.map(col=>{ const cr=crossIdx.get(looseCol(col.name||''));
        return {hay:elHay(col.name,col.columnName,col.type), cls:cr?'cov-bad':'', cells:{
          name:esc(col.name||'')+(cr?' <span class="tag sev-bad" data-tip="'+esc(cr)+'">⇄ crossed</span>':''),
          col:col.columnName&&col.columnName!==col.name?esc(col.columnName):'', type:tag(col.type)}}; })); }};
S.opParams={id:'opparams', title:'Parameters', hint:'what a caller supplies, and what comes back',
  count:(n,c)=>(c.d.params||[]).length+(c.d.outParams||[]).length,
  build:(n,c)=>{ const rows=opParamRows(c.d); return rows.length?tbl(OP_PARAM_COLS, rows, {filter:false}):''; }};
S.usedBy={id:'usedby', title:'Used by', hint:'the models that use this',
  count:(n,c)=>(c.d.usedBy||[]).length,
  build:(n,c)=>{ const ids=c.d.usedBy||[]; return ids.length?'<div class="nodechips">'+ids.map(nodeChip).join('')+'</div>':''; }};
S.opOrphan={raw:true, build:(n,c)=>(c.d.usedBy||[]).length?'':'<div class="authnote authnote-orphan">No service button, data-object field or CMMN service mapping in the scanned models calls this operation.</div>'};
// --- code ---
S.endpoints={id:'endpoints', title:'Endpoints served', hint:'the REST routes this class handles',
  count:(n,c)=>(c.d.endpoints||[]).length,
  build:(n,c)=>{ const es=c.d.endpoints||[]; if(!es.length) return '';
    return tbl([{k:'verb',label:'Verb',w:'7ch',cls:'tags'},{k:'path',label:'Path',w:'minmax(16ch,2.4fr)',mono:true},{k:'h',label:'Handler',w:'minmax(12ch,1.4fr)',mono:true,opt:true}],
      es.map(e=>({hay:elHay(e.http,e.path,e.handler), cells:{verb:'<span class="tag verb">'+esc(e.http||'')+'</span>', path:esc(e.path||''), h:esc(e.handler||'')+'() '+lineRef(n.file,e.line)}}))); }};
S.methods={id:'methods', title:'Declared methods', hint:'every method, and which ones a model calls',
  count:(n,c)=>(c.d.methods||[]).length,
  build:(n,c)=>{ const ms=c.d.methods||[]; if(!ms.length) return ''; const cm=new Set(c.d.calledMethods||[]);
    return tbl([{k:'m',label:'Method',w:'minmax(16ch,2.4fr)',mono:true},{k:'line',label:'Line',w:'minmax(6ch,.6fr)',mono:true,cls:'faint'},{k:'tags',label:'',w:'minmax(10ch,1fr)',cls:'tags'}],
      ms.map(m=>({hay:elHay(m.name,m.params), cells:{m:esc(m.name)+'('+esc(String(m.params==null?'':m.params))+')', line:lineRef(n.file,m.line),
        tags:cm.has(m.name)?'<span class="tag" data-tip="A model expression or task calls this method">◀ called by models</span>':''}})), {placeholder:'filter methods…'}); }};
S.lqBanner={raw:true, build:(n,c)=>{ const d=c.d, a=d.authority||{};
  if(a.status==='superseded'){ const chips=(a.supersededBy||[]).map(k=>nodeChip('liquibase:'+k)).join('');
    return '<div class="authnote authnote-old">⚠ Superseded revision — the live definition of <b>'+esc((d.effectiveTables||[]).join(', '))+'</b> is referenced elsewhere. These columns reflect an older revision of the same table.'+(chips?'<div class="nodechips">'+chips+'</div>':'')+'</div>'; }
  if(a.status==='orphan') return '<div class="authnote authnote-orphan">⚠ Orphan changelog — no service or data object references it. It may be dead/legacy or referenced only at runtime.</div>';
  return ''; }};
/** Every column the changelog declares, per table, with how far each one is mapped through when a
 *  service references the changelog. */
S.lqColumns={id:'columns', title:'Columns', hint:'per table; the dot says how far a column is mapped through',
  count:(n,c)=>(c.d.columns||[]).length,
  build:(n,c)=>{ const d=c.d, cs=d.columns||[]; if(!cs.length) return '';
    const cov=d.coverage;                    // present only when a service references this changelog
    const inS=cov?new Set(cov.service||[]):null, inD=cov?new Set(cov.dataObject||[]):null;
    const stOf=k=>!inS.has(k)?'bad':(!inD.has(k)?'warn':'good');
    const stTitle={bad:'not mapped by any service',warn:'mapped in service, but no data object field',good:'mapped through to a data object'};
    const byT={}; cs.forEach(x=>{ (byT[x.table||'(table)']=byT[x.table||'(table)']||[]).push(x); });
    let b='';
    if(cov) b+='<div class="covlegend"><span><span class="covdot" style="background:'+covColor('bad')+'"></span>not in service</span>'+
      '<span><span class="covdot" style="background:'+covColor('warn')+'"></span>not in data object</span>'+
      '<span><span class="covdot" style="background:'+covColor('good')+'"></span>mapped through</span></div>';
    Object.keys(byT).forEach(t=>{
      b+='<div class="sublab mono">'+esc(t)+'</div>'+tbl([{k:'dot',label:'',w:'1.2em',cls:'tags'},{k:'name',label:'Column',w:'minmax(12ch,1.6fr)',mono:true},{k:'type',label:'Type',w:'minmax(8ch,1fr)',mono:true,cls:'faint'}],
        byT[t].map(x=>{ const st=cov?stOf(looseCol(x.name)):null;
          return {hay:elHay(x.name,x.type), cls:st==='bad'?'cov-bad':st==='warn'?'cov-warn':'', cells:{
            dot:cov?'<span class="covdot" data-tip="'+stTitle[st]+'" style="background:'+covColor(st)+'"></span>':'', name:esc(x.name), type:esc(x.type||'')}}; }), {filter:false});
    });
    return b; }};
// --- expressions, bindings, functions ---
S.problems={id:'problems', title:'Problems', hint:'what the validator found in this expression',
  count:(n,c)=>(c.d.problems||[]).length,
  build:(n,c)=>{ const ps=c.d.problems||[]; if(!ps.length) return '';
    return tbl([{k:'sev',label:'',w:'8ch',cls:'tags'},{k:'msg',label:'Finding',w:'minmax(20ch,3fr)',cls:'wrap'},{k:'snip',label:'Snippet',w:'minmax(12ch,1.4fr)',mono:true,cls:'faint',opt:true}],
      ps.map(p=>{ const bad=p.severity==='error'; return {hay:elHay(p.severity,p.message,p.snippet), cells:{
        sev:'<span class="sev sev-'+(bad?'bad':'warn')+'">'+(bad?'error':'warning')+'</span>', msg:esc(p.message||''), snip:esc(p.snippet||'')}}; }), {filter:false}); }};
S.calls={id:'calls', title:'Calls custom functions 🧩', hint:'the project functions this binding invokes',
  count:(n,c)=>(c.d.calls||[]).length,
  build:(n,c)=>{ const ids=c.d.calls||[]; return ids.length?'<div class="nodechips">'+ids.map(nodeChip).join('')+'</div>':''; }};
S.inBindings={id:'inbindings', title:'Called in bindings', hint:'the exact {{…}} bindings that call it',
  count:(n,c)=>(c.d.bindings||[]).length,
  build:(n,c)=>{ const ids=c.d.bindings||[]; return ids.length?'<div class="nodechips">'+ids.map(nodeChip).join('')+'</div>':''; }};
S.fnOrphan={raw:true, build:(n,c)=>(c.d.usedBy||[]).length?'':'<div class="authnote authnote-orphan">Registered via <b>externals.additionalData</b> but no <code>{{…}}</code> binding in the scanned models calls it.</div>'};
// --- variables and string literals ---
/** Written where, read where — the two lists the "never read" verdict rests on, so a reader can check the
 *  reasoning instead of taking the verdict on faith. Each row jumps to its element in the model. */
S.rw={id:'rw', title:'Written / read', hint:'every write and every read Atlas found, with the construct it came from',
  count:(n,c)=>(c.d.writes||[]).length+(c.d.reads||[]).length,
  build:(n,c)=>{ const d=c.d, ws=d.writes||[], rs=d.reads||[]; if(!ws.length&&!rs.length) return '';
    const row=(s,verb,tone)=>({hay:elHay(verb,(byId.get(s.model)||{}).label,s.elementName,s.element,s.via), cells:{
      verb:'<span class="sev sev-'+tone+'">'+verb+'</span>', model:vlink(s.model,(byId.get(s.model)||{}).label||s.model),
      el:elJumpHtml(s.model, s.element, s.elementName||s.element), via:termHtml('via', s.via, 'tag'),
      scope:(s.scope?'<span class="muted">in</span> '+vlink(s.scope,(byId.get(s.scope)||{}).label||s.scope):'')+
        (s.scopeUnresolved?'<span class="tag" data-tip="The called model is not part of this project, so Atlas cannot tell whether anything there reads the variable.">callee not in project</span>':'')}});
    return tbl([{k:'verb',label:'',w:'8ch',cls:'tags'},{k:'model',label:'Model',w:'minmax(12ch,1.4fr)'},{k:'el',label:'Element',w:'minmax(10ch,1.2fr)',opt:true},
                {k:'via',label:'Via',w:'minmax(10ch,1.2fr)',cls:'tags',opt:true},{k:'scope',label:'Scope',w:'minmax(10ch,1fr)',cls:'tags',opt:true}],
      ws.map(s=>row(s,'writes','bad')).concat(rs.map(s=>row(s, s.guess?'≈ reads':'reads', s.guess?'faint':'ok'))), {placeholder:'filter sites — model, element, via…'}); }};
S.passedAs={id:'passedas', title:'Passed as parameter', hint:'every in/out mapping that reads or writes it, and where',
  count:(n,c)=>(c.d.ioParams||[]).length,
  build:(n,c)=>{ const ps=c.d.ioParams||[]; if(!ps.length) return '';
    return tbl([{k:'dir',label:'',w:'8ch',cls:'tags'},{k:'model',label:'Model',w:'minmax(12ch,1.4fr)'},{k:'el',label:'Element',w:'minmax(10ch,1fr)',mono:true,cls:'faint',opt:true},{k:'flow',label:'Mapping',w:'minmax(14ch,2fr)',mono:true}],
      ps.map(p=>({hay:elHay(p.dir,(byId.get(p.model)||{}).label,p.element,p.source,p.target), cells:{dir:'<span class="dir" data-dir="'+esc(p.dir)+'">'+esc(p.dir)+'</span>',
        model:vlink(p.model,(byId.get(p.model)||{}).label||p.model), el:esc(p.element||''), flow:paramFlowHtml(p)}}))); }};
S.inScripts={id:'inscripts', title:'In scripts', hint:'the scripts that touch this variable — each row jumps to the script',
  count:(n,c)=>(c.d.scriptSites||[]).length,
  build:(n,c)=>{ const ss=c.d.scriptSites||[]; if(!ss.length) return '';
    return tbl([{k:'verb',label:'',w:'10ch',cls:'tags'},{k:'model',label:'Model',w:'minmax(12ch,1.4fr)'},{k:'el',label:'Script',w:'minmax(10ch,1.2fr)',opt:true},{k:'kind',label:'Kind',w:'minmax(8ch,.8fr)',cls:'tags',opt:true}],
      ss.map(s=>({hay:elHay((byId.get(s.model)||{}).label,s.elementName,s.element,s.elementType), cells:{
        verb:'<span class="sev sev-'+(s.api?'ok':'faint')+'">'+(s.api?'sets / reads':'≈ reads')+'</span>', model:vlink(s.model,(byId.get(s.model)||{}).label||s.model),
        el:elJumpHtml(s.model, s.element, s.elementName||s.element), kind:tag(s.elementType)}}))); }};
S.usedIn={id:'usedin', title:'Used in', hint:'every effective occurrence, per model',
  count:(n,c)=>(c.d.usages||[]).length,
  build:(n,c)=>{ const us=c.d.usages||[]; if(!us.length) return '';
    const rows=[]; us.forEach(u=>{ const lbl=(byId.get(u.model)||{}).label||u.model; const sn=(u.snippets||[]);
      if(!sn.length) rows.push({hay:lbl, cells:{model:vlink(u.model,lbl), snip:''}});
      sn.forEach((s,i)=>rows.push({hay:elHay(lbl,s), cells:{model:i===0?vlink(u.model,lbl):'', snip:esc(s)}})); });
    return tbl([{k:'model',label:'Model',w:'minmax(12ch,1fr)'},{k:'snip',label:'Occurrence',w:'minmax(20ch,3fr)',mono:true,cls:'wrap'}], rows, {placeholder:'filter occurrences…'}); }};
// --- shared tail: what flows through the model, and what it uses ---
S.params={id:'params', title:'Parameters', build:(n,c)=>(c.d.ioParameters||[]).length?paramSection(c.d.ioParameters, c.hasDg):'', raw:true};
S.calledWith={id:'called-with', title:'Called with', build:(n,c)=>calledWithSection(n), raw:true};
S.uses={id:'uses', title:'Uses', build:(n,c)=>usesSection(n), raw:true};
const PAGE_TAIL=[S.params, S.calledWith, S.uses];
const PAGES={
  process:[S.userTasks, S.serviceTasks, S.scriptTasks, S.decisionTasks, S.callActivities, S.otherTasks, S.events, S.gateways,
           S.flows, S.lanes, S.multiInstance, S.declaredVars, S.listeners, S.eldocs],
  case:[S.plan, S.sentries, S.eventListeners, S.caseScripts, S.listeners, S.eldocs],
  form:[S.fields, S.outcomes, S.dataSources, S.restCalls, S.subforms],
  page:'form',
  dataObject:[S.properties],
  decision:[S.dmnIO, S.dmnRules],
  service:[S.ops, S.coverage, S.svcColumns],
  serviceOperation:[S.opParams, S.usedBy, S.opOrphan],
  app:[S.appPages, S.appVars],
  agent:[S.agentOps, S.tools],
  action:[S.botScript],
  event:[S.payload],
  dataDictionary:[S.dictTypes],
  securityPolicy:[S.permissions],
  sla:[S.escalations, S.thresholds],
  template:[S.templateBody],
  query:[S.queryParams, S.queryCols, S.queryTpl],
  document:[S.docConfig, S.docVars],
  variableExtractor:[S.extractors],
  knowledgeBase:[S.kbSources],
  java:[S.endpoints, S.methods],
  liquibase:[S.lqBanner, S.lqColumns],
  expression:[S.problems, S.usedBy],
  binding:[S.problems, S.calls, S.usedBy],
  customFunction:[S.inBindings, S.usedBy, S.fnOrphan],
  variable:[S.rw, S.passedAs, S.inScripts, S.usedIn],
  string:[S.usedIn],
  _:[],
};
/** The typed sections of a page plus the shared tail. A `raw` builder returns finished markup (a section
 *  that owns its heading and count, or a banner). */
function renderSections(n, c){
  let list=PAGES[n.type]||PAGES._; if(typeof list==='string') list=PAGES[list];
  return list.concat(PAGE_TAIL).map(s=>{
    const body=s.build(n,c); if(!body) return '';
    if(s.raw) return body;
    return section(s.id, esc(s.title), body, {count:s.count?s.count(n,c):null, hint:s.hint, nav:s.nav});
  }).join('');
}
// The mirror image of Parameters: what this node actually receives from its callers. A payload is modelled
// on the *calling* side (a form button, a call activity), so without this you would have to visit every
// caller to see whether the names line up with what the callee expects. `refKind` mirrors the node type,
// so matching on both is what keeps a service and a data object of the same key apart.
function calledWithSection(n){
  const callers=[];
  (incM.get(n.id)||[]).forEach(e=>{
    const src=byId.get(e.id); if(!src) return;
    const rows=((src.data||{}).ioParameters||[]).filter(p=>p.refKind===n.type && p.refKey===n.key);
    if(rows.length) callers.push({id:e.id, rows});
  });
  const total=callers.reduce((a,c)=>a+c.rows.length,0);
  if(!total) return '';
  return section('called-with','Called with',
    // here the interesting other side is the *caller*, so its chip replaces the callee's
    cards(callers.flatMap(c=>paramGroups(c.rows).map(g=>
      paramGroupHtml({...g, refKey:null, refKind:null}, '<div class="nodechips">'+nodeChip(c.id)+'</div>')
    ))), {count:total, hint:paramSummary(callers.flatMap(c=>c.rows))+' from '+callers.length+' caller'+(callers.length>1?'s':'')});
}
// Reverse direction: a model lists all the variables/expressions/strings it uses (collapsible).
// Derived from the artifact nodes' `usedBy` (see usesIndex) — the payload carries no `_uses`.
function usesSection(n){
  const uses=usesOf(n.id);
  if(!uses) return '';
  const ord=[['variable','Variables'],['expression','Backend expressions ${ }'],
             ['binding','Frontend bindings {{ }}'],['customFunction','Custom functions 🧩'],
             ['serviceOperation','Service operations'],['string','String literals']];
  let parts='', total=0;
  ord.forEach(([t,lbl])=>{ const ids=uses[t]; if(ids&&ids.length){ total+=ids.length;
    parts+='<details class="uses"><summary>'+lbl+' ('+ids.length+')</summary><div class="nodechips">'+ids.map(nodeChip).join('')+'</div></details>'; } });
  return section('uses','Uses — variables &amp; expressions', parts, {count:total, nav:'Uses',
    hint:'the variables, expressions, bindings and functions this model touches'});
}

/**
 * One row of a form/page's Fields table.
 *
 * A component that *acts* — every button flavour, a select bound to a data object — expands in place:
 * what it invokes, the settings that decide what pressing it sends, and the payload it maps in and out.
 * A plain input has nothing to add and stays a one-line row. Whether it renders, can be used, and is
 * submitted is stated on the row when a literal settles it (`FGATES`) — you must not have to expand a
 * row to learn the button never appears; a condition instead goes in the body where it fits.
 */
function fieldRow(f, d){
  const id=f.id==null?'':String(f.id);
  const callee=f.callee, mode=f.payloadMode, st=f.settings||{};
  // a model callee is a node to jump to; a REST callee is a URL, which belongs in the body where it fits
  const cid=(callee&&callee.kind&&callee.kind!=='rest')?callee.kind+':'+callee.key:null;
  const ps=(d.ioParameters||[]).filter(p=>String(p.element)===id);
  const rcs=(d.restCalls||[]).filter(r=>String(r.where)===id);
  // The overridden map is still in the model — and still rendered in the Parameters section — so the
  // row that announces the override says which map stopped being the contract.
  const overridden=dir=>ps.some(p=>p.dir===dir)
    ? ' <span class="muted">— the '+(dir==='in'?'send payload map':'response map')+' below is not used</span>' : '';
  const gates=FGATES.filter(g=>st[g[0]]===g[1]).map(g=>termHtml('gate',g[2],'tag')).join('');
  const rows=[];
  // What the modeller wrote about it, first: it usually explains everything below.
  if(f.description) rows.push(['note', esc(String(f.description))]);
  // The values a select/radio can take — static options, or the expression that computes them.
  if((f.options||[]).length) rows.push(['options','<span class="mono">'+esc(f.options.slice(0,20).join(' · '))+'</span>'+
    (f.options.length>20?' <span class="muted">+'+(f.options.length-20)+' more</span>':'')]);
  if(f.optionsExpression) rows.push(['options from','<span class="mono">'+esc(String(f.optionsExpression))+'</span>']);
  // Every localised caption, not just the one that names the row — all of them are searchable.
  if((f.i18nLabels||[]).length) rows.push(['translations', f.i18nLabels.map(l=>
    tag(l.locale)+' '+esc(String(l.label||''))).join(' · ')]);
  rcs.forEach(r=>rows.push(['endpoint', tag(r.method)+' <span class="mono">'+esc(r.url||'')+'</span>'+
    (r.path?' <span class="muted">→</span> <span class="mono">'+esc(r.path)+'</span>':'')]));
  if(callee&&callee.kind==='rest'&&!rcs.length) rows.push(['endpoint','<span class="mono">'+esc(String(callee.key))+'</span>']);
  // Where the result lands: on a button this is the opposite of what a binding means on an input — the
  // button writes it. (`valueExpression`, below, is which part of a response gets written.)
  if(f.stores) rows.push([f.type==='restButton'?'stores response in':'stores result in','<span class="mono">'+paramSide(String(f.stores))+'</span>']);
  if(mode&&mode.send) rows.push(['sends', termHtml('pmode',mode.send,'tag')+overridden('in')]);
  if(mode&&mode.receive) rows.push(['stores', termHtml('pmode',mode.receive,'tag')+overridden('out')]);
  // A gate already stated in the summary has nothing left to say here; a conditional one has everything.
  const said=new Set(FGATES.filter(g=>st[g[0]]===g[1]).map(g=>g[0]));
  const keys=Object.keys(st).filter(k=>k!=='script'&&st[k]!==true&&!said.has(k));
  keys.sort((a,z)=>(FSET_ORDER.indexOf(a)+1||99)-(FSET_ORDER.indexOf(z)+1||99));
  keys.forEach(k=>rows.push([FSET_LABEL[k]||k,'<span class="mono">'+esc(fsetValue(k,st[k]))+'</span>']));
  const flags=Object.keys(st).filter(k=>st[k]===true&&!said.has(k));
  if(flags.length) rows.push(['flags', flags.map(k=>tag(FSET_LABEL[k]||k)).join(' ')]);
  let b='';
  if(cid){ const chip=nodeChip(cid); if(chip) b+='<div class="nodechips">'+chip+'</div>'; }
  b+=props(rows.map(r=>[r[0],{html:r[1],copy:null}]));
  // The expression an expression button evaluates *is* what the button is — code, in the same box a
  // script task gets.
  if(st.script!=null&&st.script!==''&&st.script!==true) b+=codeblk(String(st.script), null, null, {label:'expression', wrap:true});
  if(ps.length){
    const shown=ps.slice(0,PARAM_ROWS_INLINE);
    b+='<div class="parmgrid">'+shown.map(paramRow).join('')+'</div>'+
      (ps.length>shown.length?'<div class="muted tbl-more">+ '+(ps.length-shown.length)+' more in the Parameters section</div>':'');
  }
  // A link button's caption *is* its `value`, so the value column would only say it twice.
  const val=(f.value!=null&&f.value!==''&&String(f.value)!==String(f.label==null?'':f.label))
    ?'<span class="muted">←</span> '+paramSide(String(f.value)):'';
  const ty=termHtml('el', f.type, 'tag')||tag(f.type);
  const req=(f.required===true||f.required==='true')?'<span class="tag" data-tip="Required field">required</span>':'';
  const hay=[id, f.label, f.type, f.value, callee&&callee.key, f.description].filter(Boolean).join(' ');
  return {el:f.id, hay, body:b, cls:b?'fldrow':'', bodyCls:'fldbody', cells:{
    id:fieldLink(f.id), label:esc(f.label==null?'':String(f.label)), type:ty,
    value:(cid?'<span class="opref">→ '+esc(String(callee.key))+'</span> ':'')+val,
    flags:(ps.length?tag(paramSummary(ps)):'')+req+gates}};
}
// Above this many mapping rows the component's own body stops being a summary; the rest stay one click
// away in the Parameters section, which lists every mapping of the model with a filter of its own.
const PARAM_ROWS_INLINE=10;

// ---------- rendered model diagram (BPMN/CMMN/DMN), when Atlas embedded one ----------
function diagramView(n){
  const svg = n.data && n.data.diagram;
  if(!svg) return '';
  // Atlas-generated, script-free SVG; scale it to fit the panel while keeping its aspect ratio.
  // The SVG keeps its intrinsic size; the viewport scales it. A diagram of 40 elements is unreadable
  // squeezed into the panel, so it gets zoom, pan and a fullscreen view instead of `max-width:100%`.
  return section('diagram','Diagram',
    '<div class="dgbar">'+
      '<button data-z="out" title="Zoom out">−</button>'+
      '<button data-z="fit" title="Fit to width">fit</button>'+
      '<button data-z="in" title="Zoom in">+</button>'+
      '<span class="dgpct">100%</span>'+
      '<button data-z="full" title="Open full screen">⤢ full screen</button>'+
      '<span class="dghint">click an element for details · drag to pan · '+MODK+' + scroll to zoom</span>'+
    '</div>'+
    '<div class="dgview"><div class="dgpan">'+svg+'</div></div>');
}

// ---------- neighborhood graph (ego view: selected node + 1-hop neighbors) ----------
// Per side — what this node uses on the left, what uses it on the right — sorted by how referenced the
// neighbour is and cut here; the two chip sections below carry the rest, and a "+N more" row jumps there.
const GRAPH_MAX_PER_SIDE = 12;
function neighborhoodSvg(n){
  // A neighbour can sit on both sides (a form the process opens that also writes back to it) and then
  // appears in both columns — that is the truth of the graph. The radial star this replaces put it on
  // whichever side was seen first and encoded direction as dashing, which nobody read.
  const side=m=>{ const seen=new Map(); (m.get(n.id)||[]).forEach(e=>{ if(byId.get(e.id)&&!seen.has(e.id)) seen.set(e.id,e); }); return [...seen.values()]; };
  const rank=(a,b)=>(INSIGHTS.indeg.get(b.id)||0)-(INSIGHTS.indeg.get(a.id)||0)||byId.get(a.id).label.localeCompare(byId.get(b.id).label);
  const L=side(outM).sort(rank), R=side(incM).sort(rank);
  const total=L.length+R.length;
  if(!total) return '';
  const l=L.slice(0,GRAPH_MAX_PER_SIDE), r=R.slice(0,GRAPH_MAX_PER_SIDE);
  const moreL=L.length-l.length, moreR=R.length-r.length;
  // viewBox units; the <svg> scales to the column width and keeps this aspect, so no resize code
  const W=680, COLW=220, ROW=22, HEAD=16, PAD=8, CX=W/2;
  const nL=l.length+(moreL?1:0), nR=r.length+(moreR?1:0);
  const H=HEAD+2*PAD+ROW*Math.max(nL,nR,3);
  const yc=HEAD+PAD+(H-HEAD-2*PAD)/2;
  const y0=cnt=>HEAD+PAD+(H-HEAD-2*PAD-cnt*ROW)/2+ROW/2;
  const trunc=(s,len)=>s.length>len?s.slice(0,len-1)+'…':s;
  const f=v=>v.toFixed(1);
  // the same TYPE_ICONS body typeIcon() wraps for HTML, placed on the SVG grid
  const icon=(nn,x,y,size)=>{
    const d=nn.data||{}, t=nn.type==='external'?(d.flowableApi?'endpoint':d.route?'page':'external'):nn.type;
    return '<g transform="translate('+f(x-size/2)+' '+f(y-size/2)+') scale('+(size/24).toFixed(3)+')" fill="none" stroke="'+nodeColor(nn)+
      '" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">'+(TYPE_ICONS[t]||TYPE_ICONS._)+'</g>';
  };
  let g='<defs><marker id="nbh-arr" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">'+
    '<path d="M1 1 7 4 1 7" fill="none" stroke="var(--line2)" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></marker></defs>';
  g+='<text class="nb-head" x="'+COLW+'" y="'+(HEAD-4)+'" text-anchor="end">USES</text>'+
     '<text class="nb-head" x="'+(W-COLW)+'" y="'+(HEAD-4)+'" text-anchor="start">USED BY</text>';
  // tooltips via data-tip (the DOM bubble), not <title> children — SVG-native tooltips never render in JCEF
  const rowHtml=(e,y,left)=>{
    const nn=byId.get(e.id), relTerm=term('rel', e.rel).label;
    const flag=e.sus?' ≈':e.dyn?' ƒ':'', flagTxt=e.sus?' (suspect)':e.dyn?' (dynamic)':'';
    const dim=(e.sus||e.dyn)?' stroke-dasharray="4 3" stroke-opacity=".45"':'';
    const ix=left?COLW-7:W-COLW+7, tx=left?COLW-18:W-COLW+18;
    // the arrow points the way the reference goes: out of the node into what it uses, out of a user into the node
    const path=left
      ? 'M'+(CX-16)+' '+f(yc)+' C'+(CX-70)+' '+f(yc)+' '+(CX-70)+' '+f(y)+' '+(COLW+8)+' '+f(y)
      : 'M'+(W-COLW-8)+' '+f(y)+' C'+(CX+70)+' '+f(y)+' '+(CX+70)+' '+f(yc)+' '+(CX+16)+' '+f(yc);
    return '<path d="'+path+'" fill="none" stroke="var(--line2)" stroke-width="1" marker-end="url(#nbh-arr)"'+dim+
             ' data-tip="'+esc(relTerm+flagTxt)+'"/>'+
           '<g class="gn" data-id="'+enc(e.id)+'" tabindex="0" role="link" style="cursor:pointer"'+
             ' data-tip="'+esc(nn.label+' — '+relTerm+flagTxt)+'" aria-label="'+esc(nn.label+' — '+relTerm+flagTxt)+'">'+
             icon(nn,ix,y,14)+
             '<text class="nb-label" x="'+tx+'" y="'+f(y+4)+'" text-anchor="'+(left?'end':'start')+'">'+esc(trunc(nn.label,26))+flag+'</text></g>';
  };
  const moreHtml=(cnt,x,y,left,sect)=>'<text class="nb-more" x="'+x+'" y="'+f(y+4)+'" text-anchor="'+(left?'end':'start')+
    '" data-jump-sect="'+sect+'" tabindex="0" role="button">+'+cnt+' more…</text>';
  let y=y0(nL); l.forEach(e=>{ g+=rowHtml(e,y,true); y+=ROW; }); if(moreL) g+=moreHtml(moreL,COLW-18,y,true,'rels-out');
  y=y0(nR); r.forEach(e=>{ g+=rowHtml(e,y,false); y+=ROW; }); if(moreR) g+=moreHtml(moreR,W-COLW+18,y,false,'rels-in');
  // the node itself, on top of the connectors
  g+='<circle cx="'+CX+'" cy="'+f(yc)+'" r="15" fill="var(--panel)" stroke="'+nodeColor(n)+'" stroke-width="1.5"/>'+icon(n,CX,yc,16)+
     '<text class="nb-self" x="'+CX+'" y="'+f(yc+30)+'" text-anchor="middle">'+esc(trunc(n.label,30))+'</text>';
  const svg='<svg class="nbh-svg" viewBox="0 0 '+W+' '+H+'" preserveAspectRatio="xMidYMin meet" role="img"'+
    ' aria-label="'+esc('Relationship graph of '+n.label+': what it uses on the left, what uses it on the right')+'">'+g+'</svg>';
  // a section like every other: remembered, and part of "expand all"
  return section('neighborhood','Neighborhood <span class="muted">('+total+')</span>','<div class="nbh">'+svg+'</div>');
}

// Resolve a service-task implementation to a clickable Java node chip + method.
function implLink(s){
  if(s.class){ const id='java:'+s.class; if(byId.get(id)) return jchip(id, s.class); return ''; }
  const ex=s.expression||s.delegateExpression||'';
  const m=ex.match(/[#$]\{\s*([A-Za-z_]\w*)(?:\s*\.\s*([A-Za-z_]\w*)\s*\()?/);
  if(m){ const id=beanToNode.get(m[1]); if(id) return jchip(id,(byId.get(id).label)+(m[2]?'.'+m[2]+'()':'')); }
  return '';
}
function jchip(id,label){
  const k=(byId.get(id)||{}).key||label;
  return '<span class="nc" data-id="'+enc(id)+'" tabindex="0" role="link" style="flex:none">'+typeIcon('java')+'<span class="nm">'+esc(label)+'</span>'+copyBtn(k,'class')+'</span>';
}

// ---------- "Other attributes" — the structural guarantee that nothing extracted is invisible ----------
// renderDetail wraps the node's data in a recording proxy: every key a specific renderer *reads* is
// consumed, and whatever remains is rendered here as a generic key/value tree. A future parser field
// is therefore visible by default; writing it a proper section later removes it from this list with
// zero bookkeeping. (The Kotlin mirror: PayloadCompletenessTest keeps the payload projection honest.)
const KV_MAX_ROWS=200;   // per container — a pathological model must not freeze the panel
const KV_MAX_TEXT=4000;  // per value — same cap the search index uses
function kvValueHtml(v){
  return '<span class="mono kvv">'+
    esc(String(v).slice(0,KV_MAX_TEXT))+'</span>';
}
function kvEntry(k,v,depth){
  if(v==null||v==='') return '';
  if(typeof v!=='object')
    return '<div class="kvrow"><span class="muted kvk">'+
      esc(String(k))+'</span>'+kvValueHtml(v)+'</div>';
  const inner=kvTree(v,depth+1);
  if(!inner) return '';
  return '<details class="uses"'+(depth<1?' open':'')+'><summary>'+esc(String(k))+'</summary>'+
    '<div class="kvsub">'+inner+'</div></details>';
}
function kvTree(v,depth){
  depth=depth||0;
  if(v==null) return '';
  if(Array.isArray(v)){
    if(!v.length) return '';
    if(v.every(x=>x==null||typeof x!=='object'))
      return '<div class="kvrow">'+kvValueHtml(v.slice(0,KV_MAX_ROWS).join(', '))+'</div>';
    return v.slice(0,KV_MAX_ROWS).map((x,i)=>kvEntry(
      x&&typeof x==='object'?(x.id||x.name||x.key||('#'+(i+1))):String(x), x, depth)).join('');
  }
  if(typeof v==='object') return Object.keys(v).slice(0,KV_MAX_ROWS).map(k=>kvEntry(k, v[k], depth)).join('');
  return kvValueHtml(v);
}
const kvTruthy=v=>!(v==null||v===''||(Array.isArray(v)&&!v.length)||
  (typeof v==='object'&&!Array.isArray(v)&&!Object.keys(v).length));

function renderDetail(){
  const det=document.getElementById('detail');
  // The info card lives on <body> now, so it survives this re-render — drop it, or it would keep
  // showing an element of the model we are navigating away from.
  hideDgCard();
  if(!state.sel || !byId.get(state.sel)){
    const alt=IS_MAC?'⌥':'Alt+';
    det.innerHTML='<div class="estate"><div class="estate-ic" aria-hidden="true">⌕</div>'+
      '<div class="et">'+(state.cat?'Nothing selected':'Flowable Atlas')+'</div>'+
      '<div class="eh">Pick an item from the list — click any relationship to travel the graph.<br>'+
      'Search everything with <b>/</b> or <b>'+MODK+'K</b> · '+
      'mark several with <b>⇧↑↓</b> or <b>'+MODK+'-click</b> and press <b>Enter</b> to open them as tabs · '+
      'switch with <b>'+alt+'1…9</b> or <b>'+alt+'←→</b> · close with <b>'+alt+'W</b> · '+
      'on a node, <b>c</b> copies its key'+(window.__atlasOpen?' and <b>o</b> opens its file in the IDE':'')+'.</div></div>';
    return;
  }
  const n=byId.get(state.sel);
  // Recording wrapper: every data key factsFor()/diagramView()/detailExtra() (and their helpers —
  // elementNames, elementRecords, caseCriteria, paramSection all receive this node) actually reads is
  // marked consumed; the "Other attributes" fallback below renders the rest.
  const consumed=new Set();
  const rn={...n, data:new Proxy(n.data||{}, {
    get:(t,k)=>{ if(typeof k==='string') consumed.add(k); return t[k]; },
  })};
  const out=groupRels(outM.get(n.id)), inc=groupRels(incM.get(n.id));
  // Facts first (the hero), then every section into a string — the navigator needs to know what
  // rendered before the header that carries it can be written.
  const facts=factsFor(rn);
  _sectReg=[];
  let body='';
  body+=diagramView(rn);
  body+=neighborhoodSvg(n);
  // The findings sit right under the diagram they are about — this is where a reader has the context
  // to judge one, and the locate button puts the element in view.
  body+=nodeFindingsHtml(n);
  body+=renderSections(rn, detailCtx(rn));
  // Whatever no renderer above consumed. Identity fields live in the header; HAY_SKIP is the same
  // bookkeeping the search index skips.
  {
    const skip=new Set([...HAY_SKIP,'key','name','file','description','modelType','type','label']);
    const rest=Object.keys(n.data||{}).filter(k=>!consumed.has(k)&&!skip.has(k)&&kvTruthy((n.data||{})[k]));
    if(rest.length) body+=section('otherattrs','Other attributes',
      rest.map(k=>kvEntry(k,(n.data||{})[k],0)).join(''), {count:rest.length,
        hint:'parsed data no section above shows — a dedicated section is an upgrade, not a precondition'});
  }
  // The gesture is stated where the reference chips actually are. Walking a fan of references is the
  // case it exists for: without it, every chip you follow costs you the node you started from.
  const relHint='<div class="relhint">click follows · <b>'+MODK+'-click</b> or middle-click opens a tab</div>';
  const relBody=g=>relHint+Object.keys(g).sort().map(rel=>
    '<div class="relgrp"><div class="lab">'+termHtml('rel', rel)+'</div><div class="nodechips">'+
    [...g[rel].values()].map(e=>nodeChip(e.id,e)).join('')+'</div></div>').join('');
  const ok=Object.keys(out).sort(), ik=Object.keys(inc).sort();
  if(ok.length) body+=section('rels-out','Uses / references', relBody(out), {count:ok.reduce((a,k)=>a+out[k].size,0), nav:'References'});

  if(ik.length) body+=section('rels-in','Used by / referenced from', relBody(inc), {count:ik.reduce((a,k)=>a+inc[k].size,0), nav:'Used by'});
  if(!ok.length && !ik.length) body+='<p class="muted" style="margin-top:18px">No relationships recorded for this node.</p>';
  const reg=_sectReg; _sectReg=null;
  // The sticky bar: kind on the left, the actions right. The title stays in the body — at 26px it is
  // the one thing a reader should not have pinned over what they are reading.
  const kindHint=term('type', n.type).hint;
  let h='<div class="dhead">'+
     '<span class="dkind"'+(kindHint?' data-tip="'+esc(kindHint)+'"':'')+'>'+nodeIcon(n)+esc(nodeKind(n))+'</span>'+
     '<span class="dhead-actions">'+
     (_navCount>1?'<button id="back" data-tip="Back to the previous node">'+uiIcon('back')+'<span class="lbl">back</span></button>':'')+
     '<button id="sectall" data-tip="Expand or collapse every section on this page">'+uiIcon('expand')+'<span class="lbl">expand all</span></button>'+
     '<button id="permalink" data-tip="Copy a shareable link to this node">'+uiIcon('link')+'<span class="lbl">copy link</span></button>'+
     '</span></div>';
  h+='<div class="dbody">'+heroHtml(n, facts)+secnavHtml(reg)+body+'</div>';
  det.innerHTML=h;
  det.scrollTop=0;
  const b=document.getElementById('back'); if(b) b.onclick=()=>history.back();
  // Remember every section's open state, and offer one control to flip them all at once.
  const sects=[...det.querySelectorAll('details.sect')];
  sects.forEach(s=>s.addEventListener('toggle',()=>sectRemember(dec(s.dataset.sect), s.open)));
  const sa=document.getElementById('sectall');
  if(sa){
    const lbl=sa.querySelector('.lbl');
    const sync=()=>{ lbl.textContent = sects.every(s=>s.open) ? 'collapse all' : 'expand all'; };
    sync();
    sects.forEach(s=>s.addEventListener('toggle',sync));
    sa.onclick=()=>{ const open=!sects.every(s=>s.open);
      sects.forEach(s=>{ s.open=open; sectRemember(dec(s.dataset.sect), open); }); sync(); };
    if(!sects.length) sa.hidden=true;
  }
  const pl=document.getElementById('permalink');
  if(pl) pl.onclick=()=>{
    // strip ?ideTheme=… (IDE embedding seed) — a stale param in a shared link only confuses
    const url=location.search?location.href.replace(location.search,''):location.href;
    const lbl=pl.querySelector('.lbl');
    const done=()=>{ lbl.textContent='link copied'; pl.classList.add('ok'); setTimeout(()=>{ lbl.textContent='copy link'; pl.classList.remove('ok'); },1500); };
    // The same path as every other copy button — inside the IDE, navigator.clipboard is blocked and
    // window.__atlasCopy is the only route; this button used to be the one that fell through to prompt().
    atlasCopy(url, done);
  };
  // A relationship chip is a link, not a list row, so ⌘/Ctrl+click and middle-click follow the
  // browser convention here. `[data-goto]` joins the same contract: it travels to another node AND
  // lands on one of its elements (a variable → the script that sets it), which openTabs carries too.
  // Innermost match wins, so a `[data-goto]` inside a chip resolves to the element, not the node.
  wireNodeLinks(det, '.nc, .gn, .vlink, [data-goto]');
  // clicking the path (but not its copy icon) copies too — routed through atlasCopy so the "copied"
  // hint only shows on real success and the child copy button survives (no textContent nuke).
  const fp=det.querySelector('.dfile');
  if(fp) fp.onclick=e=>{ if(e.target.closest('.cpy')) return;
    atlasCopy(dec(fp.dataset.copy), ()=>{ fp.classList.add('copied'); setTimeout(()=>fp.classList.remove('copied'),1200); }); };
  wireAccept(det);
  wireCopyButtons(det);
  wireOpenButtons(det);                 // ↗ open the file / file:line in the IDE (no-ops in a browser)
  // ⌖ locate-on-diagram buttons; preventDefault keeps a click inside a <summary> from toggling it
  det.querySelectorAll('.dgloc').forEach(b=>{
    b.onclick=e=>{ e.preventDefault(); e.stopPropagation(); locateOnDiagram(det, b.dataset.elRef, b.dataset.elName); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' ') e.stopPropagation(); };
  });
  // "N parameter mappings ↓" inside a service task — jumps to that element's group in the Parameters section
  det.querySelectorAll('[data-reveal-el]').forEach(b=>{
    b.onclick=e=>{ e.stopPropagation(); revealByEl(det, b.dataset.revealEl, 'details.sect[data-sect="params"]'); };
  });

  // The section navigator's chips and the neighborhood's "+N more" — open the section and scroll to it
  det.querySelectorAll('[data-jump-sect]').forEach(b=>{
    const open=()=>{
      const d=det.querySelector('details.sect[data-sect="'+b.dataset.jumpSect+'"]'); if(!d) return;
      d.open=true; sectRemember(dec(b.dataset.jumpSect), true); d.scrollIntoView({block:'start'});
    };
    b.onclick=e=>{ e.stopPropagation(); open(); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' '){ e.preventDefault(); open(); } };
  });
  wireSectionFilter(det);
  wireDiagram(det);
  applyFocus(det);
}

// ---------- diagram zoom / pan ----------
// One controller per viewport: the SVG is never re-rendered, only transformed, so panning and zooming
// cost nothing regardless of how many elements the diagram has.
// `opts.modWheel` (the inline diagram): a plain wheel scrolls the PAGE as everywhere else — zooming
// needs ⌘/Ctrl held (a trackpad pinch reports ctrlKey, so pinch-zoom keeps working). Without it the
// diagram swallows every scroll that happens to pass over it. The fullscreen modal zooms freely.
function zoomable(view, opts){
  opts=opts||{};
  const pan=view.querySelector('.dgpan'), svg=pan&&pan.querySelector('svg');
  if(!svg) return null;
  const z={scale:1, tx:0, ty:0, view, pan, svg, moved:false};
  view._z=z;                                       // reached through the DOM by locate/click handlers
  z.apply=()=>{
    pan.style.transform='translate('+z.tx+'px,'+z.ty+'px) scale('+z.scale+')';
    const pct=view.parentElement&&view.parentElement.querySelector('.dgpct');
    if(pct) pct.textContent=Math.round(z.scale*100)+'%';
  };
  // "fit" means fit the width — the usual reason a diagram is unreadable is that it is wider than the panel
  z.fit=()=>{
    const w=svg.getAttribute('width');
    const natural=w?parseFloat(w):svg.getBoundingClientRect().width/z.scale;
    // clientWidth is 0 while the section is collapsed — never derive a zero/negative scale from it
    z.scale=(natural>0&&view.clientWidth>16)?Math.min(1, (view.clientWidth-8)/natural):1;
    // Full screen has a fixed height too: a tall case diagram fitted to the width alone was still cut
    // off at the bottom. (The inline view sizes its height from the scale below, so width is all.)
    if(!opts.modWheel){
      const hN=parseFloat(svg.getAttribute('height'))||0;
      if(hN>0 && view.clientHeight>16) z.scale=Math.min(z.scale, (view.clientHeight-8)/hN);
    }
    z.userZoomed=false;
    z.tx=0; z.ty=0; z.apply();
    // Inline only: a transform doesn't shrink layout height, so a wide diagram scaled down would
    // leave a tall white gap under itself — size the viewport to the scaled drawing instead.
    if(opts.modWheel){
      const hAttr=parseFloat(svg.getAttribute('height'))||0;
      if(hAttr>0) view.style.height=Math.round(Math.max(120, Math.min(hAttr*z.scale+2, window.innerHeight*0.6)))+'px';
    }
  };
  z.zoom=(factor, ox, oy)=>{
    z.userZoomed=true;                             // a hand-set scale is kept across panel resizes
    const next=Math.min(8, Math.max(0.1, z.scale*factor));
    if(ox!=null){                                  // keep the point under the cursor put
      const k=next/z.scale;
      z.tx=ox-(ox-z.tx)*k; z.ty=oy-(oy-z.ty)*k;
    }
    z.scale=next; z.apply();
  };
  view.addEventListener('wheel', e=>{
    if(opts.modWheel && !e.ctrlKey && !e.metaKey){ wheelHint(view); return; }   // let the page scroll
    e.preventDefault();
    const r=view.getBoundingClientRect();
    z.zoom(e.deltaY<0?1.12:1/1.12, e.clientX-r.left, e.clientY-r.top);
  }, {passive:false});
  view.addEventListener('pointerdown', e=>{
    if(e.button!==0) return;
    // setPointerCapture retargets the eventual `click` to the view itself, so e.target there never
    // reaches the SVG element that was pressed — remember the real press target for the click handler.
    z.downTarget=e.target;
    view.setPointerCapture(e.pointerId); view.classList.add('grabbing');
    const sx=e.clientX-z.tx, sy=e.clientY-z.ty, ox=e.clientX, oy=e.clientY;
    z.moved=false;
    const move=ev=>{
      if(Math.abs(ev.clientX-ox)+Math.abs(ev.clientY-oy)>4) z.moved=true;   // a pan, not a click
      z.tx=ev.clientX-sx; z.ty=ev.clientY-sy; z.apply();
    };
    const up=()=>{ view.classList.remove('grabbing');
      view.removeEventListener('pointermove',move); view.removeEventListener('pointerup',up);
      view.removeEventListener('pointercancel',up); };
    view.addEventListener('pointermove',move); view.addEventListener('pointerup',up);
    // A cancelled gesture (a touch interrupted, the JCEF surface losing the pointer) never fires
    // pointerup — without this the diagram kept panning with no button held.
    view.addEventListener('pointercancel',up);
  });
  return z;
}
// A transient "how do I zoom" pill, shown when a plain wheel passes over the inline diagram —
// the page scrolled as expected, this just teaches the modifier.
function wheelHint(view){
  let h=view.querySelector('.dgwheelhint');
  if(!h){
    h=document.createElement('div'); h.className='dgwheelhint';
    h.textContent=MODK+' + scroll to zoom';
    view.appendChild(h);
  }
  h.classList.add('show');
  clearTimeout(h._t); h._t=setTimeout(()=>h.classList.remove('show'), 1100);
}
function wireZoomButtons(bar, z){
  bar.querySelectorAll('button[data-z]').forEach(b=>{
    const a=b.dataset.z;
    if(a==='full') return;                          // handled by the caller — it owns the modal
    b.onclick=()=>{ if(a==='in') z.zoom(1.25); else if(a==='out') z.zoom(1/1.25); else z.fit(); };
  });
}
function wireDiagram(det){
  const view=det.querySelector('.dgview');
  if(!view) return;
  const z=zoomable(view, {modWheel:true});
  if(!z) return;
  // A collapsed section has no layout (clientWidth 0) — fit on the first real layout instead.
  const tryFit=()=>{ if(!z._fitted && view.clientWidth>0){ z.fit(); z._fitted=true; dgMarkFindings(view, state.sel&&byId.get(state.sel)); } };
  tryFit();
  const sect=view.closest('details');
  if(sect) sect.addEventListener('toggle',()=>{ if(sect.open) tryFit(); });
  // The panel changes width (the splitter, the IDE tool window) and the drawing kept its old scale —
  // small in a tall box, or clipped. Re-fit on a real width change unless the user zoomed by hand.
  if(window.ResizeObserver){
    let lastW=view.clientWidth;
    new ResizeObserver(()=>{
      const w=view.clientWidth;
      if(w>0 && Math.abs(w-lastW)>8){ lastW=w; if(z._fitted && !z.userZoomed) z.fit(); }
    }).observe(view);
  }
  liftSvgTitles(view);
  wireDgClicks(view, false);
  const bar=det.querySelector('.dgbar');
  wireZoomButtons(bar, z);
  const full=bar.querySelector('button[data-z="full"]');
  if(full) full.onclick=()=>openDiagramModal(z.svg);
}

// ---------- fullscreen diagram ----------
const dgmodal=document.getElementById('dgmodal');
let _dgZoom=null;
function openDiagramModal(svg){
  if(!dgmodal) return;
  const pan=dgmodal.querySelector('.dgpan');
  pan.innerHTML='';
  pan.appendChild(svg.cloneNode(true));            // a clone: the inline diagram stays as it was
  const t=document.getElementById('dgtitle');
  const n=state.sel&&byId.get(state.sel);
  if(t) t.textContent=n?n.label:'';
  dgmodal.hidden=false;
  const view=document.getElementById('dgmodalview');
  view.scrollTop=0;
  _dgZoom=zoomable(view);
  if(_dgZoom){ _dgZoom.fit(); wireZoomButtons(dgmodal.querySelector('.dgbar'), _dgZoom); }
  liftSvgTitles(view);
  wireDgClicks(view, true);
}
function closeDiagramModal(){
  if(!dgmodal||dgmodal.hidden) return;
  hideDgCard();
  dgmodal.hidden=true;
  dgmodal.querySelector('.dgpan').innerHTML='';    // drop the clone; a big SVG is worth reclaiming
  _dgZoom=null;
}
if(dgmodal){
  dgmodal.addEventListener('mousedown',e=>{ if(e.target.closest('[data-close]')) closeDiagramModal(); });
  document.addEventListener('keydown',e=>{
    if(dgmodal.hidden || !_dgZoom) return;
    if(e.key==='Escape'){ if(_dgCard){ dgCardEscape(); } else closeDiagramModal(); }
    else if(e.key==='+'||e.key==='='){ e.preventDefault(); _dgZoom.zoom(1.25); }
    else if(e.key==='-'){ e.preventDefault(); _dgZoom.zoom(1/1.25); }
    else if(e.key==='0'){ e.preventDefault(); _dgZoom.fit(); }
  });
}
document.addEventListener('keydown',e=>{ if(e.key==='Escape'&&_dgCard&&(!dgmodal||dgmodal.hidden)) dgCardEscape(); });
/** Escape unwinds the card one step at a time: the overlay shrinks back first, the card closes second
 *  — same reasoning as the palette's marks-then-panel chain. */
function dgCardEscape(){
  if(!_dgCard) return;
  if(_dgCard.classList.contains('big')) setDgCardBig(_dgCard, false); else hideDgCard();
}

// ---------- diagram interactivity ----------
// The renderer stamps every shape/edge group with its model element id (`data-el`), which is the same
// id the parsed data attributes things to (parameters, tasks, flow conditions). That one contract gives
// both directions: click a canvas element → an info card + "show in details"; click ⌖ on a detail row →
// the diagram pans to, and highlights, that element.
const LOC_SVG='<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="6.5"/><path d="M12 2.5v4M12 17.5v4M2.5 12h4M17.5 12h4"/></svg>';
function cssEsc(s){ return (window.CSS&&CSS.escape)?CSS.escape(String(s)):String(s).replace(/["\\\]]/g,'\\$&'); }

// The renderer keeps native <title> children for no-JS viewers (the exported .svg files), but the
// embedded JCEF viewer never shows them — lift each into the data-tip bubble, the one tooltip path
// that works everywhere. Lifted before any clone, so the modal copy inherits the attributes.
function liftSvgTitles(view){
  view.querySelectorAll('svg g > title').forEach(t=>{
    const g=t.parentNode;
    if(!g.hasAttribute('data-tip')) g.setAttribute('data-tip', t.textContent);
    g.removeChild(t);
  });
}

// element id -> {name, type, sub} from every element list the parser produced for this node.
function elementNames(n){
  const d=n.data||{}, m=new Map();
  const put=(id,name,type,sub)=>{ if(id==null||id==='') return; const k=String(id);
    if(!m.has(k)) m.set(k,{name:name||'', type:type||'', sub:sub||null}); };
  (d.userTasks||[]).forEach(t=>put(t.id,t.name,'userTask'));
  (d.serviceTasks||[]).forEach(t=>put(t.id,t.name,'serviceTask',t.type));
  (d.scriptTasks||[]).forEach(t=>put(t.id,t.name,'scriptTask'));
  (d.callActivities||[]).forEach(t=>put(t.id,t.name,'callActivity'));
  (d.subProcesses||[]).forEach(t=>put(t.id,t.name,t.type||'subProcess'));
  (d.ruleTasks||[]).forEach(t=>put(t.id,t.name,'serviceTask','dmn'));
  (d.events||[]).forEach(e=>put(e.id,e.name,e.type));
  (d.gateways||[]).forEach(g=>put(g.id,g.name,g.type));
  (d.otherTasks||[]).forEach(t=>put(t.id,t.name,t.type));
  (d.eventListeners||[]).forEach(e=>put(e.id,e.name,e.type));
  (d.lanes||[]).forEach(l=>put(l.id,l.name,'lane'));
  (d.milestones||[]).forEach(x=>{ if(x&&typeof x==='object') put(x.id,x.name,'milestone'); });
  if(d.planModel)(function walk(nd){ put(nd.id,nd.name,nd.type,nd.serviceTaskType); (nd.children||[]).forEach(walk); })(d.planModel);
  // criterion diamonds: named after the plan item they guard, typed entry/exitCriterion.
  // (Resolve via the definition already indexed above — `planItem` may be a raw definition id.)
  caseCriteria(d).forEach(c=>{
    const def=c.planItemDef!=null?m.get(String(c.planItemDef)):null;
    put(c.id, (def&&def.name)||c.planItem, c.type);
  });
  return m;
}
function elName(em, id){ const e=em.get(String(id)); return (e&&e.name)?e.name:String(id==null?'':id); }
// Every element record of a model, whatever list it lives in. Used by the views that are about a
// property elements *share* (documentation, listeners) rather than about one element type.
function elementRecords(n){
  const d=n.data||{}, out=[];
  const push=arr=>(arr||[]).forEach(r=>{ if(r&&typeof r==='object') out.push(r); });
  push(d.userTasks); push(d.serviceTasks); push(d.scriptTasks); push(d.ruleTasks);
  push(d.callActivities); push(d.subProcesses); push(d.events); push(d.gateways);
  push(d.otherTasks); push(d.eventListeners); push(d.milestones);
  if(d.planModel)(function walk(nd){ out.push(nd); (nd.children||[]).forEach(walk); })(d.planModel);
  return out;
}
// A case's entry/exit criteria (from the plan tree), each joined with its sentry's condition/on-parts.
function caseCriteria(d){
  if(!d.planModel) return [];
  const byS=new Map((d.sentries||[]).map(s=>[String(s.id), s]));
  const out=[];
  (function walk(nd){
    (nd.criteria||[]).forEach(c=>{
      const s=c.sentryRef!=null?byS.get(String(c.sentryRef)):null;
      out.push({id:c.id, planItem:c.planItem, planItemDef:c.planItemDef, type:c.type, sentryRef:c.sentryRef,
                condition:(s&&s.condition)||'', onParts:((s&&s.onParts)||[]).filter(Boolean)});
    });
    (nd.children||[]).forEach(walk);
  })(d.planModel);
  return out;
}
// entry ◇ / exit ◆ chip with the sentry's condition (or its on-parts when there is no if-part).
function criterionChip(c){
  const what=c.condition?'<span class="mono" style="color:var(--ink-faint);font-size:var(--text-2xs)">'+esc(c.condition)+'</span>'
    :(c.onParts.length?'<span class="muted" style="font-size:var(--text-2xs)">on '+esc(c.onParts.join(', '))+'</span>':'');
  return '<span style="display:inline-flex;gap:4px;align-items:baseline">'+
    '<span class="pt">'+(c.type==='entryCriterion'?'entry ◇':'exit ◆')+'</span>'+what+'</span>';
}
// The case plan item carrying this id (CMMN keeps its per-element facts in the plan tree).
function planItemById(d, id){
  let hit=null;
  if(d.planModel)(function walk(nd){ if(String(nd.id)===String(id)) hit=hit||nd; (nd.children||[]).forEach(walk); })(d.planModel);
  return hit;
}

function dgSelect(view, g){
  view.querySelectorAll('.dgsel').forEach(x=>x.classList.remove('dgsel'));
  if(g) g.classList.add('dgsel');
}
// Pan so the element sits centered in the viewport (screen px = user units × scale, since the SVG's
// width/height equal its viewBox size).
function dgCenter(z, g){
  try{
    const bb=g.getBBox(), vb=z.svg.viewBox.baseVal;
    z.tx=z.view.clientWidth/2-(bb.x+bb.width/2-vb.x)*z.scale;
    z.ty=z.view.clientHeight/2-(bb.y+bb.height/2-vb.y)*z.scale;
    z.apply();
  }catch(e){}
}
/** A badge on every diagram element that carries an open finding — the count, coloured by the worst
 *  one. Drawn into the SVG so it pans and zooms with the shape; a click on it is a click on the shape,
 *  and the card that opens lists the findings with their accept controls. Needs layout for getBBox, so
 *  it runs when the diagram is fitted, i.e. when its section is actually shown. */
function dgMarkFindings(view, n){
  const svg=view&&view.querySelector('svg'); if(!svg||!n) return;
  svg.querySelectorAll('.dgmark').forEach(x=>x.remove());
  const byEl=new Map();
  (FIND_BY_NODE.get(n.id)||[]).forEach(f=>{ if(f.element==null||waiverFor(f)) return;
    const k=String(f.element), m=byEl.get(k)||{n:0, worst:'warning'}; m.n++; if(f.severity==='error') m.worst='error'; byEl.set(k, m); });
  if(!byEl.size) return;
  const names=elementNames(n), NS='http://www.w3.org/2000/svg';
  byEl.forEach((m, el)=>{
    const g=dgFind(view, el, (names.get(el)||{}).name); if(!g) return;
    let bb; try{ bb=g.getBBox(); }catch(e){ return; }
    if(!bb||!(bb.width>0)) return;
    const label=(names.get(el)||{}).name||el, lbl=m.n+' finding'+(m.n>1?'s':'')+' on '+label;
    const mk=document.createElementNS(NS,'g');
    mk.setAttribute('class','dgmark dgmark-'+(m.worst==='error'?'bad':'warn'));
    mk.setAttribute('data-mark-el', String(g.dataset.el));
    mk.setAttribute('role','button'); mk.setAttribute('tabindex','0'); mk.setAttribute('aria-label', lbl);
    const c=document.createElementNS(NS,'circle'); c.setAttribute('cx', bb.x+bb.width); c.setAttribute('cy', bb.y); c.setAttribute('r','7.5');
    const t=document.createElementNS(NS,'text'); t.setAttribute('x', bb.x+bb.width); t.setAttribute('y', bb.y); t.textContent=String(m.n);
    const title=document.createElementNS(NS,'title'); title.textContent=lbl;
    mk.appendChild(title); mk.appendChild(c); mk.appendChild(t); svg.appendChild(mk);
  });
}
// A diagram group for the element: by id, falling back to the tooltip name (CMMN DI references plan
// item ids while the parsed tree keys definitions by their own id — the name bridges the two).
function dgFind(view, elId, name){
  let g=view.querySelector('[data-el="'+cssEsc(elId)+'"]');
  if(!g && name){
    g=[...view.querySelectorAll('[data-el]')].find(x=>{
      const t=x.getAttribute('data-tip')||'';
      return t===name || t.indexOf(name+' — ')===0;
    })||null;
  }
  return g;
}
// ⌖ on a detail row: open the diagram section, highlight the element and pan to it.
function locateOnDiagram(det, elId, name){
  const sect=det.querySelector('details.sect[data-sect="diagram"]');
  if(!sect) return;
  sect.open=true;
  const view=sect.querySelector('.dgview'), z=view&&view._z;
  if(!z) return;
  if(!z._fitted && view.clientWidth>0){ z.fit(); z._fitted=true; }   // first reveal of a kept-closed section
  const g=dgFind(view, elId, name);
  if(!g) return;
  dgSelect(view, g);
  dgCenter(z, g);
  sect.scrollIntoView({block:'nearest'});
}
// The other direction: open every detail row/group attributed to this element and flash it.
// `scope` (a selector) narrows the search to one section: the "N parameter mappings ↓" button on a
// service task must land on that task's group in the Parameters section, not on the card it sits in —
// which is also a [data-el] row of the same element, and comes first in the document.
function revealByEl(det, elId, scope){
  const root=scope?det.querySelector(scope):det;
  if(!root) return false;
  const rows=[...root.querySelectorAll('[data-el]')]
    .filter(x=>x.dataset.el===String(elId) && !x.closest('.dgview'));
  if(!rows.length) return false;
  det.querySelectorAll('.hit').forEach(x=>x.classList.remove('hit'));
  rows.forEach(el=>{
    for(let p=el.parentElement; p&&p!==det; p=p.parentElement){ if(p.tagName==='DETAILS') p.open=true; }
    if(el.tagName==='DETAILS') el.open=true;
    el.hidden=false;                    // a row a section filter had hidden is shown again
    el.classList.add('hit','flash');
  });
  requestAnimationFrame(()=>rows[0].scrollIntoView({block:'center'}));
  setTimeout(()=>det.querySelectorAll('.flash').forEach(x=>x.classList.remove('flash')), 1800);
  return true;
}

// ---------- element info card (click a diagram element) ----------
let _dgCard=null, _dgScrim=null;
function hideDgCard(){
  if(_dgCard&&_dgCard._ro) _dgCard._ro.disconnect();     // else every card ever shown stays observed
  if(_dgCard&&_dgCard.parentNode) _dgCard.parentNode.removeChild(_dgCard);
  _dgCard=null; dgScrim(false);
}
// The card is a small window on <body> — `position:fixed`, NOT a child of the diagram viewport. That is
// what lets it be dragged and resized far past the drawing area: in a narrow IDE tool window the
// viewport is much smaller than the space a card full of parameters needs, and a card clamped to it
// would stay unusably tiny. It still *starts* docked to the viewport's top-right corner (out of the
// drawing, and stable while you click through elements). Size and the corner offset are remembered, so
// the docking carries over between the inline view and the (wider) fullscreen modal. Corner-docked it
// can only be dragged bigger to the right/down though, so ⤢ (`setDgCardBig`) trades the corner for a
// centered, page-wide overlay.
const DGCARD_STORE='atlas-dgcard';
function dgCardPrefs(){ try{ return JSON.parse(localStorage.getItem(DGCARD_STORE)||'{}')||{}; }catch(err){ return {}; } }
function dgCardRemember(patch){
  try{ localStorage.setItem(DGCARD_STORE, JSON.stringify(Object.assign(dgCardPrefs(), patch))); }catch(err){}
}
function placeDgCard(view, card){
  if(dgCardPrefs().big){ setDgCardBig(card, true, false); return; }   // stay expanded across clicks
  dgDockCard(view, card);
}
/** The docked geometry: remembered size, parked at the viewport's top-right corner. */
function dgDockCard(view, card){
  if(!view){ clampDgCard(card); return; }
  const p=dgCardPrefs(), r=view.getBoundingClientRect();
  if(p.w) card.style.width=Math.max(240, Math.min(p.w, window.innerWidth-16))+'px';
  if(p.h) card.style.height=Math.max(90, Math.min(p.h, window.innerHeight-16))+'px';
  const rx=p.rx!=null?p.rx:8, ty=p.ty!=null?p.ty:8;
  card.style.left=(r.right-card.offsetWidth-rx)+'px';
  card.style.top =(r.top+ty)+'px';
  clampDgCard(card);
}
// ---- docked corner window ⇄ full-page overlay ----
// Docked, the card can only be dragged bigger down and to the RIGHT (that is where the native
// `resize:both` handle is) while it *starts* at the diagram's top-right corner — so in a narrow IDE
// tool window there is nothing left to grow into and the card seems unable to escape the diagram.
// ⤢ lifts it out of the corner into a centered overlay over the whole page, above even the fullscreen
// diagram modal, which is where an element with twenty parameters is actually readable. The choice is
// remembered because the card is rebuilt from scratch on every element click.
/** Overlay geometry: as wide as reading a parameter table wants, and only as TALL as the content —
 *  capped at the viewport. A fixed 92vh box would turn a two-row start event into a white wall. */
function dgCardBigRect(){
  const p=dgCardPrefs(), maxW=Math.max(240, window.innerWidth-32);
  return {w:Math.max(240, Math.min(p.bw||Math.min(1100, maxW), maxW)),
          h:p.bh?Math.max(90, Math.min(p.bh, Math.round(window.innerHeight*0.92))):null};
}
function setDgCardBig(card, on, remember){
  if(on){
    const r=dgCardBigRect();
    card.classList.add('big');
    card.style.width=r.w+'px';
    card.style.height=r.h?r.h+'px':'';                 // no remembered height → hug the content
    // centered only after the width lands: until then the content height is unknown
    card.style.left=Math.round((window.innerWidth-r.w)/2)+'px';
    card.style.top =Math.max(8, Math.round((window.innerHeight-card.offsetHeight)/2))+'px';
    dgScrim(true);
  }else{
    card.classList.remove('big');
    card.style.width=''; card.style.height='';           // back to the remembered docked size
    dgScrim(false);
    dgDockCard(card._view, card);
  }
  if(remember!==false) dgCardRemember({big:!!on});
  const b=card.querySelector('.dgcard-max');
  if(b){
    const lbl=on?'Shrink back to the diagram corner':'Expand to a full-page overlay';
    b.textContent=on?'⤡':'⤢';
    b.setAttribute('aria-label',lbl); b.setAttribute('data-tip',lbl);
    b.setAttribute('aria-pressed',on?'true':'false');
  }
}
/** The dim layer behind an expanded card. Its own element at z-index 119 so it also covers the
 *  fullscreen diagram modal (which reuses .palette's layer at 100). Clicking it shrinks the card
 *  back rather than closing it: expanding is a reading mode, and dismissing would cost you the
 *  element you clicked to get here. ✕ / Escape still close. */
function dgScrim(on){
  if(on){
    if(_dgScrim) return;
    _dgScrim=document.createElement('div');
    _dgScrim.className='dgscrim';
    // shrink on `click`, not on `pointerdown`: removing the scrim under a pressed pointer leaves the
    // following click with no stable target (it would land on whatever is now underneath).
    _dgScrim.addEventListener('pointerdown',e=>e.stopPropagation());
    _dgScrim.addEventListener('click',e=>{ e.stopPropagation(); if(_dgCard) setDgCardBig(_dgCard,false); });
    document.body.appendChild(_dgScrim);
  }else if(_dgScrim){
    if(_dgScrim.parentNode) _dgScrim.parentNode.removeChild(_dgScrim);
    _dgScrim=null;
  }
}
/** Keep the card reachable inside the window — after placing it, a drag, or a window resize. */
function clampDgCard(card){
  const x=parseFloat(card.style.left)||0, y=parseFloat(card.style.top)||0;
  card.style.left=Math.max(4, Math.min(x, window.innerWidth-48))+'px';
  card.style.top =Math.max(4, Math.min(y, window.innerHeight-28))+'px';
}
function wireDgCardMoveResize(view, card){
  const head=card.querySelector('.dgcard-head');
  // Double-click the header to expand/shrink, as a window title bar does.
  if(head) head.addEventListener('dblclick', e=>{
    if(e.target.closest('button')) return;
    setDgCardBig(card, !card.classList.contains('big'));
  });
  if(head) head.addEventListener('pointerdown', e=>{
    if(e.target.closest('button')) return;                 // the ✕ stays a click
    if(card.classList.contains('big')) return;             // expanded, it is centered, not draggable
    e.preventDefault(); e.stopPropagation();
    const sx=e.clientX-card.offsetLeft, sy=e.clientY-card.offsetTop;
    const move=ev=>{
      // clamped to the WINDOW, not to the diagram — the card is free to sit anywhere on the page
      card.style.left=Math.max(4, Math.min(ev.clientX-sx, window.innerWidth-48))+'px';
      card.style.top =Math.max(4, Math.min(ev.clientY-sy, window.innerHeight-28))+'px';
    };
    const up=()=>{
      document.removeEventListener('pointermove',move); document.removeEventListener('pointerup',up);
      const r=view.getBoundingClientRect();
      dgCardRemember({rx:Math.round(r.right-(card.offsetLeft+card.offsetWidth)), ty:Math.round(card.offsetTop-r.top)});
    };
    document.addEventListener('pointermove',move); document.addEventListener('pointerup',up);
  });
  // native corner resize (CSS resize:both) — remember the size the user settles on
  if(window.ResizeObserver){
    let first=true;
    const ro=new ResizeObserver(()=>{
      if(first){ first=false; return; }                    // the observe() call itself fires once
      clearTimeout(card._rszT);
      card._rszT=setTimeout(()=>{
        if(!card.isConnected) return;
        // The two modes keep their own size, so shrinking back never inherits the overlay's dimensions.
        if(card.classList.contains('big')) dgCardRemember({bw:card.offsetWidth, bh:card.offsetHeight});
        else dgCardRemember({w:card.offsetWidth, h:card.offsetHeight});
      }, 300);
    });
    ro.observe(card);
    card._ro=ro;                                           // disconnected by hideDgCard()
  }
}
window.addEventListener('resize',()=>{
  if(!_dgCard) return;
  if(_dgCard.classList.contains('big')) setDgCardBig(_dgCard, true, false);   // re-center on the new size
  else clampDgCard(_dgCard);
});
function wireDgClicks(view, inModal){
  if(view._dgClicksWired) return;                     // the modal view persists across opens
  view._dgClicksWired=true;
  view.addEventListener('click', e=>{
    const z=view._z;
    // Pointer capture (the pan handler) retargets real clicks to the view — resolve the element from
    // the remembered press target; synthetic/keyboard clicks (no pointerdown) fall back to e.target.
    const pressed=z&&z.downTarget; if(z) z.downTarget=null;
    if(z&&z.moved){ z.moved=false; return; }          // that was a pan, not a click
    const t=(pressed&&pressed.isConnected)?pressed:e.target;
    const g=dgTargetOf(view, t);
    if(!g||!view.contains(g)){ hideDgCard(); dgSelect(view, null); return; }
    dgSelect(view, g);
    showDgCard(view, g, e, inModal);
  });
  // Shapes are focusable (the renderer stamps tabindex/role): Enter or Space on one is a click.
  view.addEventListener('keydown', e=>{
    if(e.key!=='Enter' && e.key!==' ') return;
    const g=dgTargetOf(view, e.target);
    if(!g||!view.contains(g)) return;
    e.preventDefault();
    g.dispatchEvent(new MouseEvent('click',{bubbles:true}));
  });
}
/** The shape a click or a key landed on — through a finding marker to the shape it marks. */
function dgTargetOf(view, t){
  if(!t||!t.closest) return null;
  const mk=t.closest('[data-mark-el]');
  if(mk) return view.querySelector('[data-el="'+cssEsc(mk.getAttribute('data-mark-el'))+'"]');
  return t.closest('[data-el]');
}
// The id the parsed data knows this diagram element by. Usually data-el itself; CMMN DI references
// plan item ids while the parsed plan tree keys the *definitions* — there the element name bridges.
function dgEffectiveId(n, g){
  const em=elementNames(n), elId=g.dataset.el;
  if(em.has(String(elId))) return elId;
  const tip=g.getAttribute('data-tip')||'';
  const name=tip.indexOf(' — ')>=0?tip.slice(0,tip.indexOf(' — ')):tip;
  if(name){ for(const [k,v] of em){ if(v.name===name) return k; } }
  return elId;
}
function showDgCard(view, g, e, inModal){
  hideDgCard();
  const n=state.sel&&byId.get(state.sel);
  if(!n) return;
  const elId=dgEffectiveId(n, g);
  // The selection joins the URL (`&e=`), so a copied link or a reload lands on this element — without
  // a history entry or a re-route, which replaceState (in syncHashContext) guarantees and setting
  // location.hash would not.
  state.focusEl=String(elId); syncHashContext();
  const card=document.createElement('div'); card.className='dgcard';
  card.setAttribute('role','dialog');
  card.setAttribute('aria-label','Element details — drag the header to move, drag the corner to resize, ⤢ to expand over the page');
  card.innerHTML=dgCardHtml(n, elId, g);
  // on <body>, not in the view: see placeDgCard. `_view` keeps the originating viewport reachable.
  card._view=view;
  document.body.appendChild(card);
  placeDgCard(view, card);
  wireDgCardMoveResize(view, card);
  _dgCard=card;
  // The card is injected after renderDetail's wiring pass, so wire its own affordances here.
  card.addEventListener('pointerdown',ev=>ev.stopPropagation());   // no pan from inside the card
  card.addEventListener('click',ev=>ev.stopPropagation());
  card.addEventListener('wheel',ev=>ev.stopPropagation());         // the card scrolls itself
  card.querySelector('.dgcard-x').onclick=()=>{ hideDgCard(); dgSelect(view, null); };
  const mx=card.querySelector('.dgcard-max');
  if(mx) mx.onclick=()=>setDgCardBig(card, !card.classList.contains('big'));
  const dj=card.querySelector('[data-dgdetails]');
  if(dj) dj.onclick=()=>{
    hideDgCard();
    if(inModal) closeDiagramModal();
    revealByEl(document.getElementById('detail'), dj.getAttribute('data-dgdetails')||elId);
  };
  // Navigating away tears the card down and leaves the fullscreen diagram — but a BACKGROUND open
  // must not: ⌘-clicking callee after callee out of one diagram is the whole point, so only the
  // card closes there and the diagram stays where it was.
  wireNodeLinks(card, '.vlink,.nc', {before:bg=>{ hideDgCard(); if(inModal&&!bg) closeDiagramModal(); }});
  card.querySelectorAll('.cpy').forEach(b=>{
    b.onclick=ev=>{ ev.stopPropagation();
      atlasCopy(dec(b.dataset.copy), ()=>{ b.classList.add('ok'); setTimeout(()=>b.classList.remove('ok'),1200); }); };
  });
  // The findings on the element: restore here; accepting lands on the finding's own row under the
  // diagram, where the form has room — the card is a window, not a place to type a paragraph.
  wireAccept(card);
  card.querySelectorAll('[data-dgaccept]').forEach(b=>b.onclick=ev=>{ ev.preventDefault(); ev.stopPropagation();
    const fi=b.getAttribute('data-dgaccept');
    hideDgCard(); if(inModal) closeDiagramModal();
    const det=document.getElementById('detail'), row=det&&det.querySelector('.tr[data-fi="'+cssEsc(fi)+'"]');
    if(!row) return;
    for(let q=row.parentElement; q&&q!==det; q=q.parentElement){ if(q.tagName==='DETAILS') q.open=true; }
    row.open=true; row.scrollIntoView({block:'center'});
    const inp=row.querySelector('.wv-form input.wv-in[required]'); if(inp) inp.focus();
  });
}
// Callee chips for a service-task record — which model the task actually talks to.
function stCalleeChip(st){
  const ids=[st.serviceModelKey&&'service:'+st.serviceModelKey,
             st.dataObjectKey&&'dataObject:'+st.dataObjectKey,
             st.agentModelKey&&'agent:'+st.agentModelKey].filter(Boolean).filter(id=>byId.get(id));
  return ids.map(nodeChip).join('');
}
function dgCardHtml(n, elId, g){
  const d=n.data||{}, em=elementNames(n);
  const info=em.get(String(elId))||{};
  const tip=g.getAttribute('data-tip')||'';
  let name=info.name || (tip.indexOf(' — ')>=0?tip.slice(0,tip.indexOf(' — ')):tip);
  let tyHtml=info.type?elementTerm(info.type, info.sub||undefined):'';
  if(!tyHtml && tip.indexOf(' — ')>=0) tyHtml='<span class="term">'+esc(tip.slice(tip.indexOf(' — ')+3))+'</span>';
  const rows=[];
  const row=(k,v)=>{ if(v) rows.push('<div class="dgrow"><span class="k">'+esc(k)+'</span><span class="v">'+v+'</span></div>'); };
  const sameId=x=>String(x)===String(elId);
  // -- task-flavour facts, from whichever element list owns this id --
  const st=(d.serviceTasks||[]).find(t=>sameId(t.id));
  if(st){
    const impl=st.class||st.delegateExpression||st.expression||'';
    if(impl) row('impl','<span class="mono">'+esc(impl)+'</span> '+implLink(st));
    if(st.resultVariable) row('result','<span class="mono">'+paramSide(st.resultVariable)+'</span>');
    const callee=stCalleeChip(st); if(callee) row('calls', callee);
    if(st.operationKey) row('operation','<span class="mono">'+esc(st.operationKey)+'</span>');
    if(st.topic) row('topic', vlink('topic:'+st.topic, st.topic));
    if(st.caseDefinitionKey) row('starts case', vlink('case:'+st.caseDefinitionKey, st.caseDefinitionKey));
  }
  const ut=(d.userTasks||[]).find(t=>sameId(t.id));
  if(ut){
    if(ut.formKey) row('form', vlink('form:'+ut.formKey, ut.formKey));
    if(ut.assignee) row('assignee','<span class="mono">'+esc(ut.assignee)+'</span>');
    if(ut.candidateGroups) row('groups', groupLinksHtml(ut.candidateGroups));
  }
  const ca=(d.callActivities||[]).find(t=>sameId(t.id));
  if(ca&&ca.calledElement) row('calls', vlink('process:'+ca.calledElement, ca.calledElement));
  const rt=(d.ruleTasks||[]).find(t=>sameId(t.id));
  if(rt&&rt.decisionRef) row('decision', vlink('decision:'+rt.decisionRef, rt.decisionRef));
  const ev=(d.events||[]).find(x=>sameId(x.id))||(d.eventListeners||[]).find(x=>sameId(x.id));
  if(ev&&(ev.def||ev.timer)) row(ev.def||'timer','<span class="mono">'+esc(ev.value||ev.timer||'')+'</span>');
  const pi=n.type==='case'?planItemById(d, elId):null;
  if(pi){
    if(pi.formKey) row('form', vlink('form:'+pi.formKey, pi.formKey));
    if(pi.processRef) row('process', vlink('process:'+pi.processRef, pi.processRef));
    if(pi.caseRef) row('case', vlink('case:'+pi.caseRef, pi.caseRef));
    if(pi.decisionRef) row('decision', vlink('decision:'+pi.decisionRef, pi.decisionRef));
    if(pi.candidateGroups) row('groups', groupLinksHtml(pi.candidateGroups));
  }
  // CMMN criterion diamond: its sentry's condition + the plan item it guards
  const crit=n.type==='case'?caseCriteria(d).find(c=>sameId(c.id)):null;
  if(crit){
    row('guards', esc(elName(em, crit.planItemDef!=null?crit.planItemDef:(crit.planItem||''))));
    if(crit.condition) row('condition','<code class="mono" style="font-size:var(--text-xs)">'+esc(crit.condition)+'</code>');
    if(crit.onParts.length) row('on', esc(crit.onParts.join(', ')));
  }
  // a DMN DRD shape is a decision table of its own — link straight to its node
  if(n.type==='decision'&&byId.get('decision:'+elId)&&('decision:'+elId)!==n.id) row('model', nodeChip('decision:'+elId));
  const mi=(d.multiInstance||[]).find(m=>sameId(m.activity));
  if(mi) row('multi-instance',(mi.collection?'over <span class="mono">'+paramSide(mi.collection)+'</span>':'')+
    (mi.elementVariable?' as <span class="mono">'+paramSide(mi.elementVariable)+'</span>':'')+
    (mi.sequential==='true'?' · sequential':''));
  // -- parameters + the variables they touch --
  const ps=(d.ioParameters||[]).filter(p=>sameId(p.element));
  const vars=new Set();
  const addVar=x=>{ const r=String(x==null?'':x).replace(/^\$/,'').split('.')[0].split('[')[0];
    if(r&&byId.get('variable:'+r)) vars.add(r); };
  ps.forEach(p=>{ addVar(p.source); addVar(p.target); });
  [st&&st.resultVariable, mi&&mi.collection, mi&&mi.elementVariable].forEach(x=>{ if(x) addVar(x); });
  const sc=(d.scriptTasks||[]).find(t=>sameId(t.id))||(pi&&pi.script?pi:null);
  if(sc&&sc.resultVariable) addVar(sc.resultVariable);
  if(vars.size) row('variables', [...vars].map(v=>vlink('variable:'+v, v)).join(', '));
  let body='';
  if(ps.length){
    const shown=ps.slice(0,10);
    body+='<div class="dgsec">Parameters ('+ps.length+') · '+esc(paramSummary(ps))+'</div>'+
      '<div class="parmgrid">'+shown.map(paramRow).join('')+'</div>'+
      (ps.length>shown.length?'<div class="muted" style="font-size:var(--text-2xs);padding:2px 0">+ '+(ps.length-shown.length)+' more in the Parameters section</div>':'');
  }
  // -- flow conditions: the clicked flow's own, or every outgoing flow of the clicked element --
  const selfC=(d.conditions||[]).find(c=>sameId(c.id));
  const outC=(d.conditions||[]).filter(c=>sameId(c.from));
  if(selfC) body+='<div class="dgsec">Condition</div><div class="dgcond">'+
    '<span class="cflow">'+esc(elName(em,selfC.from))+' → '+esc(elName(em,selfC.to))+'</span>'+
    '<code>'+esc(selfC.condition||'')+'</code></div>';
  if(outC.length) body+='<div class="dgsec">Outgoing flow conditions ('+outC.length+')</div>'+
    outC.map(c=>'<div class="dgcond"><span class="cflow">→ '+esc(elName(em,c.to))+'</span>'+
      '<code>'+esc(c.condition||'')+'</code></div>').join('');
  // CMMN plan item: its entry/exit criteria with their sentry conditions
  if(pi){
    const cs=caseCriteria(d).filter(c=>c.planItemDef!=null&&sameId(c.planItemDef));
    if(cs.length) body+='<div class="dgsec">Entry / exit criteria ('+cs.length+')</div>'+
      cs.map(c=>'<div class="dgcond"><span class="cflow">'+(c.type==='entryCriterion'?'entry ◇':'exit ◆')+'</span>'+
        '<code>'+esc(c.condition||(c.onParts.length?'on '+c.onParts.join(', '):'—'))+'</code></div>').join('');
  }
  // -- script preview --
  if(sc&&sc.script){
    const lines=String(sc.script).split('\n');
    body+='<div class="dgsec">Script'+(sc.format||sc.scriptFormat?' ('+esc(sc.format||sc.scriptFormat)+')':'')+'</div>'+
      '<pre class="scriptbox" style="margin:2px 0">'+esc(lines.slice(0,5).join('\n'))+(lines.length>5?'\n…':'')+'</pre>';
  }
  // -- what the modeller wrote about this element, and the listeners it runs --
  const rec=elementRecords(n).find(r=>sameId(r.id));
  if(rec&&rec.documentation) body+='<div class="dgsec">Documentation</div>'+
    '<div style="font-size:var(--text-xs)">'+esc(rec.documentation)+'</div>';
  const recLs=((rec&&rec.listeners)||[]).filter(l=>l.class||l.expression||l.delegateExpression||l.script);
  if(recLs.length) body+='<div class="dgsec">Listeners ('+recLs.length+')</div>'+
    recLs.map(l=>'<div class="dgcond"><span class="cflow">'+
      esc([term('el', l.kind).label, l.event].filter(Boolean).join(' · '))+'</span>'+
      '<code>'+esc(l.class||l.expression||l.delegateExpression||'(script)')+'</code></div>').join('');
  // -- the findings on this element: what the marker on the shape was counting --
  const fs=(FIND_BY_NODE.get(n.id)||[]).filter(f=>f.element!=null&&sameId(f.element));
  if(fs.length) body+='<div class="dgsec">Findings ('+fs.length+')</div>'+fs.map(f=>{ const rule=waiverFor(f);
    return '<div class="dgfind" data-fi="'+f.fi+'">'+sevPill(f.severity)+' <span class="tag">'+esc(checkTitle(f.check))+'</span> '+esc(f.message)+
      (rule?acceptedNoteHtml(rule):'')+'<div class="dgfind-act">'+
      (rule?'<button type="button" class="dgbtn wv-restore" data-fi="'+f.fi+'">restore</button>'
           :'<button type="button" class="dgbtn" data-dgaccept="'+f.fi+'">accept…</button>')+'</div></div>'; }).join('');
  const det=document.getElementById('detail');
  // a criterion has no detail row of its own — its "details" are the guarded plan item's row
  const revealId=crit&&crit.planItemDef!=null?String(crit.planItemDef):String(elId);
  const hasRows=det&&[...det.querySelectorAll('[data-el]')].some(x=>x.dataset.el===revealId&&!x.closest('.dgview'));
  return '<div class="dgcard-head"><span class="dgcard-title">'+esc(name||elId)+'</span>'+
    (tyHtml?'<span class="dgcard-ty">'+tyHtml+'</span>':'')+
    '<button class="dgcard-max" aria-label="Expand to a full-page overlay" aria-pressed="false"'+
    ' data-tip="Expand to a full-page overlay">⤢</button>'+
    '<button class="dgcard-x" aria-label="Close">×</button></div>'+
    '<div class="dgcard-id mono">'+esc(elId)+copyBtn(elId,'element id')+'</div>'+
    rows.join('')+body+
    (hasRows?'<div class="dgcard-foot"><button class="dgbtn" data-dgdetails="'+esc(revealId)+'">Show in details ↓</button></div>':'');
}

// A search hit lands on the node, not on the row that matched — so find the matching rows, open every
// collapsed ancestor, mark them all and scroll the first one into view. When the hit carried the
// element it came from (a script task, a flow condition), that element's rows win: they are the exact
// place, not a text guess.
function applyFocus(det){
  if(state.focusEl && revealByEl(det, state.focusEl)){
    // …and the other half of a `&e=` link: the element on the canvas, not only its rows. With the name,
    // because CMMN's DI references plan items while the parsed tree keys definitions — the ⌖ button
    // always passed it, a deep link never did, and the same element lit up from one and not the other.
    const nn=byId.get(state.sel);
    locateOnDiagram(det, state.focusEl, nn?((elementNames(nn).get(state.focusEl)||{}).name):undefined);
    return;
  }
  const raw=(state.focus||'').trim();
  if(!raw) return;
  // The engine's grammar, not the raw string: strip facets, keep words and phrases. The old matcher
  // did one contiguous indexOf of the whole query, so a faceted or multi-word hit (which the engine
  // matched word-by-word) highlighted nothing on this page.
  const parsed=qParse(raw);
  const needles=parsed.phrases.concat(parsed.terms,
    SX_HL_FACETS.filter(k=>parsed.facets[k]).map(k=>parsed.facets[k]));
  if(!needles.length) return;
  const textOf=el=>(el.dataset.hay||el.textContent||'').toLowerCase();
  // a row carrying every word wins; failing that, any word — same AND-first contract as the search
  const pick=sel=>{
    const els=[...det.querySelectorAll(sel)];
    const all=els.filter(el=>{ const t=textOf(el); return needles.every(nd=>t.indexOf(nd)>=0); });
    return all.length?all:els.filter(el=>{ const t=textOf(el); return needles.some(nd=>t.indexOf(nd)>=0); });
  };
  let rows=pick('.pc, .oprow');
  // script bodies / operation blocks, flow conditions and DMN cells — a free-text hit usually lands here
  if(!rows.length) rows=pick('details.card, details.op, .dgcond, .dmntab td');
  if(!rows.length) rows=pick('.fact');
  if(!rows.length) return;
  rows.forEach(el=>{ el.classList.add('hit'); if(el.tagName==='DETAILS') el.open=true; });
  for(let p=rows[0].parentElement; p && p!==det; p=p.parentElement){
    if(p.tagName==='DETAILS') p.open=true;
  }
  rows[0].classList.add('flash');
  // the panel was just replaced; let layout settle before scrolling
  requestAnimationFrame(()=>rows[0].scrollIntoView({block:'center'}));
  setTimeout(()=>det.querySelectorAll('.flash').forEach(e=>e.classList.remove('flash')), 1600);
}

// Navigation: select() only moves the URL hash; the hashchange listener routes. That makes
// the hash the single source of truth — browser back/forward, bookmarks and copied links all
// go through the same path.
// `q` is the search term that led here and `el` the model element the hit came from (a script task, a
// flow condition …) — both ride along in the hash so Back/Forward and "copy link" reproduce the
// highlight without any extra plumbing.
function select(id, q, el){
  if(!byId.get(id)) return;
  const hash=encodeURIComponent(id)+(q?'&q='+encodeURIComponent(q):'')+
             (el?'&e='+encodeURIComponent(el):'');
  if(location.hash.slice(1)===hash){ state.focus=q||''; state.focusEl=el||''; applySelection(id); return; }
  location.hash=hash;
}

function applySelection(id, ctx){
  if(!byId.get(id)) return;
  ctx=ctx||{};
  // Read before state.view is overwritten. The overview / schema / scripts / checks routes have no
  // tab of their own AND hide the strip, so letting the active tab travel from there would drop a
  // node the user never saw leave. From those views a link APPENDS; from the browse list it does
  // not — there the strip is on screen and following a row is following a link in the active tab.
  const fromNonNodeView = state.view!=='browse';
  state.view='browse'; showView('browse');
  rememberTabScroll();                     // before the panel is replaced
  syncTabsWith(id, fromNonNodeView);       // reconcile the tab set with what the hash asks for
  state.sel=id;
  pushRecent(id);
  const n=byId.get(id);
  // Keep the current category if it already contains this node (so clicking within
  // e.g. "Java · delegate" stays there) — only re-sync when it doesn't match.
  const cur=CATS.find(c=>c.id===state.cat);
  let catChanged=false;
  if(!cur || !cur.match(n)){
    let cat;
    if(n.type==='java'){
      const prio=['controller','delegate','listener','bot','service','repository','configuration','component','other'];
      const r=(n.data.roles||[]).slice().sort((a,b)=>prio.indexOf(a)-prio.indexOf(b))[0];
      cat=CATS.find(c=>c.id==='java::'+r);
    } else if(n.type==='variable'){
      cat=CATS.find(c=>c.id==='variable::'+(n.data.scopes||[])[0]);
    }
    cat=cat||CATS.find(c=>c.id===n.type);
    // A filter typed in one category has no business in the next — following a chip out of a
    // filtered Forms list used to open the Java list pre-filtered, reading "Nothing here".
    if(cat && cat.id!==state.cat){ state.cat=cat.id; state.filter=''; state.sort='name'; catChanged=true; }
  }
  // The list context a link or a reload carries (&f=, &s=) wins over whatever the panel had.
  if(ctx.filter!=null && ctx.filter!==state.filter){ state.filter=ctx.filter; catChanged=true; }
  if(ctx.sort!=null && ctx.sort!==state.sort){ state.sort=ctx.sort; catChanged=true; }
  if(catChanged || !document.getElementById('listitems')) renderList();
  syncListSelection();
  renderTabs();
  renderDetail();
  restoreTabScroll();                      // after renderDetail(), which resets scrollTop to 0
  renderSidebarActive(); renderCrumbs();
  syncHashContext();
}

// ---------- detail tabs ----------
// A tab is a VIEWPORT WITH HISTORY, not a pinned node: following a relationship chip moves the
// active tab, exactly like a link followed inside a browser tab. Only one detail panel is ever
// live — renderDetail() writes into #detail and mints globally unique child ids (#back, #sectall,
// #permalink), so N simultaneous panels would collide. Switching tabs simply re-renders.
//
// The hash still carries ONLY the active node (grammar unchanged, so permalinks and "copy link"
// keep working); the tab SET lives in localStorage. Putting the whole set in the hash would make
// every shared link unreadable and push a history entry per opened tab.
// 12, not 20: Alt+1..9 only reaches nine, and a strip of twenty tabs is a row of unreadable
// slivers. Marks are capped at the same number, so "open them all" can always keep its promise.
const MAX_TABS=12, TABS_STORE='atlas-tabs';
// Per-tab view state: scroll offset plus the search term/element the tab was opened with, so
// switching back to a tab opened from ⌘K still highlights the hit that put it there. Keyed by node
// id (a node can occupy at most one tab) and pruned on close.
let _tabView={};
// During boot a permalink must ADD to the restored set, not overwrite the remembered active tab.
let _tabsBooting=true;

function tabsRemember(){
  // Scoped to the project: every report on a file:// origin shares one localStorage, and two
  // reports of the same codebase share node ids — an unscoped record would restore foreign tabs.
  try{ localStorage.setItem(TABS_STORE,
    JSON.stringify({p:DATA.project, ids:state.tabs, active:state.tab})); }catch(e){}
}
function tabsRestore(){
  try{
    const p=JSON.parse(localStorage.getItem(TABS_STORE)||'{}')||{};
    if(p.p!==DATA.project){ state.tabs=[]; state.tab=-1; return; }
    // A regenerated explorer can have a different node set — drop ids that no longer resolve.
    const ids=(p.ids||[]).filter(id=>byId.get(id)).slice(0,MAX_TABS);
    state.tabs=ids;
    state.tab=(typeof p.active==='number' && p.active>=0 && p.active<ids.length) ? p.active : (ids.length?0:-1);
  }catch(e){ state.tabs=[]; state.tab=-1; }
}

/**
 * Reconcile the tab set with the node the hash just asked for.
 * `append` forces a new tab instead of letting the active one travel — see applySelection().
 */
function syncTabsWith(id, append){
  const at=state.tabs.indexOf(id);
  if(at>=0){ state.tab=at; }                                  // already open → just activate it
  else if(append || _tabsBooting || state.tab<0 || state.tab>=state.tabs.length){
    // Boot (a permalink alongside the restored set) or no active tab → append rather than replace,
    // so restoring tabs and opening a shared link never costs the user a tab.
    if(state.tabs.length>=MAX_TABS){
      // Say so: openTabs() reports a refused open, and this path silently destroyed the oldest tab.
      const gone=evictTab(), n=gone&&byId.get(gone);
      toast('closed “'+(n?n.label:gone)+'” — '+MAX_TABS+' tabs is the limit');
    }
    state.tabs.push(id); state.tab=state.tabs.length-1;
  } else {
    state.tabs[state.tab]=id;                                 // the active tab travels
  }
  tabsRemember();
}
/** Make room under the cap by dropping the leftmost tab that is not the active one; returns its id. */
function evictTab(){
  let i=state.tabs.findIndex((id,ix)=>ix!==state.tab);
  if(i<0) i=0;
  const [gone]=state.tabs.splice(i,1);
  delete _tabView[gone];
  if(state.tab>i) state.tab--;
  return gone;
}

function rememberTabScroll(){
  const det=document.getElementById('detail');
  if(!det || !state.sel) return;
  const v=_tabView[state.sel]||{};
  v.y=det.scrollTop; v.q=state.focus||''; v.el=state.focusEl||'';
  _tabView[state.sel]=v;
}
function restoreTabScroll(){
  // A search hit owns the scroll position (applyFocus scrolls the matching row into view) — don't
  // fight it. Otherwise return to where this node was left, or stay at the top for a fresh one.
  if(state.focus || state.focusEl) return;
  const det=document.getElementById('detail'), v=_tabView[state.sel];
  if(!det || !v || !v.y) return;
  det.scrollTop=v.y;
  // The inline diagram fits itself asynchronously, which can clamp the offset we just set.
  requestAnimationFrame(()=>{ if(det.isConnected && !state.focus && !state.focusEl) det.scrollTop=v.y; });
}

function renderTabs(){
  const bar=document.getElementById('dtabs');
  if(!bar) return;
  // From the first tab, not the second: a strip that only appeared once two tabs existed was how a
  // reader who opened one node after another never learned that tabs — and Alt+1…9 — existed.
  if(!state.tabs.length){ bar.hidden=true; bar.innerHTML=''; return; }
  bar.hidden=false;
  // "close others" is a plain button, so it lives OUTSIDE the tablist: a role=tablist must contain
  // nothing but tabs, and the scroll container is the tablist itself.
  const rows=state.tabs.map((id,i)=>{
    const n=byId.get(id); if(!n) return '';
    const on=id===state.sel;                      // derived from the selection, never from an index
    // Only the first nine are reachable by number, so only they advertise one.
    const hint=i<9 ? '  ('+(IS_MAC?'⌥':'Alt+')+(i+1)+')' : '';
    return '<div class="dtab'+(on?' on':'')+'" id="dtab-'+i+'" role="tab" data-i="'+i+'"'+
      ' aria-selected="'+on+'" tabindex="'+(on?0:-1)+'" data-tip="'+esc(n.label+' · '+nodeKind(n)+hint)+'">'+
      nodeIcon(n)+
      '<span class="nm">'+esc(n.label)+'</span>'+
      '<button class="x" tabindex="-1" aria-label="'+esc('Close '+n.label)+'" data-close-i="'+i+'">×</button></div>';
  }).join('');
  bar.innerHTML='<div class="dtablist" id="dtablist" role="tablist" aria-label="Open nodes">'+rows+'</div>'+
    '<button class="dtclose" id="dtcloseall" data-tip="Close every tab but the active one">close others</button>';
  const list=bar.querySelector('#dtablist');
  list.querySelectorAll('.dtab').forEach(t=>{
    t.onclick=e=>{
      const x=e.target.closest('.x');
      if(x){ e.stopPropagation(); closeTab(+x.dataset.closeI); return; }
      activateTab(+t.dataset.i);
    };
    // Middle-click closes, as in every editor and browser. mousedown must be swallowed too, or
    // Chrome starts autoscroll on the way.
    t.onmousedown=e=>{ if(e.button===1) e.preventDefault(); };
    t.onauxclick=e=>{ if(e.button===1){ e.preventDefault(); closeTab(+t.dataset.i); } };
    t.onkeydown=e=>{
      const tabs=[...list.querySelectorAll('.dtab')], i=tabs.indexOf(t);
      if(e.key==='ArrowRight'||e.key==='ArrowLeft'){
        e.preventDefault();
        const j=e.key==='ArrowRight'?Math.min(i+1,tabs.length-1):Math.max(i-1,0);
        if(!tabs[j]) return;
        tabs.forEach(o=>{ o.tabIndex=-1; });     // exactly one tab stop, or Tab walks the whole strip
        tabs[j].tabIndex=0; tabs[j].focus();
      } else if(e.key==='Enter'||e.key===' '){ e.preventDefault(); activateTab(+t.dataset.i); }
      else if(e.key==='Delete'||e.key==='Backspace'){ e.preventDefault(); closeTab(+t.dataset.i); }
    };
  });
  bar.querySelector('#dtcloseall').onclick=()=>closeOtherTabs();
  const act=list.querySelector('.dtab.on');
  if(act) act.scrollIntoView({block:'nearest', inline:'nearest'});
}

/**
 * Open `ids` as tabs. Returns {opened, dropped} — `dropped` is how many did not fit under MAX_TABS,
 * which the caller reports rather than swallowing (a silent cap reads as "opened everything").
 * `opts.background` keeps the current tab active.
 */
function openTabs(ids, opts){
  opts=opts||{};
  const want=(ids||[]).filter(id=>byId.get(id));
  if(!want.length) return {opened:0, dropped:0};
  let dropped=0, first=null;
  want.forEach(id=>{
    if(state.tabs.indexOf(id)<0){
      if(state.tabs.length>=MAX_TABS){ dropped++; return; }
      state.tabs.push(id);
    }
    if(first===null) first=id;
  });
  tabsRemember();
  renderTabs();                       // paint the new tabs now; select()'s route lands a frame later
  if(!opts.background && first!==null){
    state.tab=state.tabs.indexOf(first);
    select(first, opts.q, opts.el);
  }
  return {opened:want.length-dropped, dropped};
}

/**
 * One transient message at a time. Only used where the tab strip cannot speak for itself: a
 * background open from a route that hides it, or an open the cap refused. One timer, so a burst of
 * ⌘-clicks extends a single line instead of flickering.
 */
let _toastT=null;
function toast(msg){
  const box=document.getElementById('toast');
  if(!box||!msg) return;
  box.textContent=msg;
  box.classList.add('show');
  clearTimeout(_toastT);
  _toastT=setTimeout(()=>box.classList.remove('show'), 2200);
}

/**
 * Wire a container so every node link inside it obeys ONE navigation contract (the doc block above):
 *   plain click       → navigate; arriving from a view with no tab of its own APPENDS a tab
 *   ⌘/Ctrl · middle   → background tab, stay where you are
 *   Enter / Space     → mirrors the click, modifier included
 * Delegated on `root`: the view renderers replace their whole innerHTML on every filter keystroke,
 * and one delegated handler survives that where N per-element handlers would have to be re-attached.
 * Assigned as a property (not addEventListener) so re-wiring a re-rendered container cannot stack
 * duplicate handlers.
 *
 * `sel`             which descendants are node links. They carry `data-id`, or `data-goto`
 *                   (+ `data-goto-el`) when the target is a specific element inside that node.
 * `opts.first(e)`   the container's OTHER clickables — an anchor jump, a route, a category. Runs
 *                   before the link lookup and wins when it returns true. No element carries both
 *                   kinds of attribute, so the order between them is free.
 * `opts.before(bg)` cleanup for a container that has to close itself (the diagram card). `bg` is
 *                   true for a background open, which is how the diagram stays up while you collect
 *                   tabs out of it.
 */
function wireNodeLinks(root, sel, opts){
  opts=opts||{};
  const target=e=>{
    const el=e.target.closest?e.target.closest(sel):null;
    if(!el) return null;
    const id=dec(el.dataset.goto||el.dataset.id||'');
    return byId.get(id) ? {id, el:el.dataset.gotoEl||''} : null;
  };
  // preventDefault, not just for form-ish targets: a link inside a <summary> would otherwise
  // navigate AND toggle the section it sits in (same reason the ⌖ buttons do it).
  const go=(t,bg)=>{
    if(opts.before) opts.before(bg);
    if(!bg){ select(t.id, '', t.el); return; }
    const r=openTabs([t.id], {background:true});
    const n=byId.get(t.id);
    // A refused open has nowhere to report itself, and on the routes that hide the strip even a
    // successful one is invisible — the toast covers exactly those two cases.
    if(r.dropped) toast('not opened — '+MAX_TABS+' tabs is the limit');
    else if(state.view!=='browse'&&n) toast('opened “'+n.label+'” in a tab');
  };
  root.onclick=e=>{
    if(opts.first&&opts.first(e)) return;
    const t=target(e);
    if(!t) return;
    e.preventDefault();
    go(t, modKey(e));
  };
  root.onmousedown=e=>{ if(e.button===1&&target(e)) e.preventDefault(); };   // no autoscroll cursor
  root.onauxclick=e=>{
    if(e.button!==1) return;
    const t=target(e);
    if(!t) return;
    e.preventDefault();
    go(t, true);
  };
  root.onkeydown=e=>{
    if(e.key!=='Enter'&&e.key!==' ') return;
    if(opts.first&&opts.first(e)){ e.preventDefault(); return; }
    const t=target(e);
    if(!t) return;
    e.preventDefault();
    go(t, modKey(e));
  };
}

function activateTab(i){
  const id=state.tabs[i];
  if(id==null || !byId.get(id)) return;
  if(state.sel===id){ state.tab=i; renderTabs(); return; }    // already on screen
  rememberTabScroll();
  state.tab=i;
  // Route through select() so the hash stays the source of truth. Replay the search term this tab
  // was opened with, so a tab opened from ⌘K still highlights the hit that put it there.
  const v=_tabView[id]||{};
  state.focus=''; state.focusEl='';
  select(id, v.q||'', v.el||'');
}

function closeTab(i){
  if(i<0 || i>=state.tabs.length) return;
  const wasActive=state.tabs[i]===state.sel;
  const [gone]=state.tabs.splice(i,1);
  delete _tabView[gone];
  if(i<state.tab || state.tab>=state.tabs.length) state.tab--;
  if(state.tab<0) state.tab=state.tabs.length?0:-1;
  tabsRemember();
  if(!state.tabs.length){
    // Fall back to the category listing — the existing "browse, nothing selected" route. Assigning
    // an unchanged hash fires no hashchange, so render directly in that case or the strip goes stale.
    const h = state.cat ? '/browse/'+enc(state.cat) : '/overview';
    if(location.hash.slice(1)===h){ state.sel=null; renderTabs(); renderDetail(); renderCrumbs(); }
    else location.hash=h;
    return;
  }
  if(!wasActive){ renderTabs(); return; }        // the shown node is untouched — repaint the strip
  state.focus=''; state.focusEl='';
  select(state.tabs[state.tab]);                 // right neighbour, or the new last one
}

function closeOtherTabs(){
  if(state.tabs.length<2) return;
  // Keep whatever is on screen; if nothing is (the category route), keep the write pointer's tab.
  const keep=state.sel && state.tabs.indexOf(state.sel)>=0 ? state.sel
           : state.tabs[Math.max(0,Math.min(state.tab,state.tabs.length-1))];
  state.tabs.forEach(id=>{ if(id!==keep) delete _tabView[id]; });
  state.tabs=[keep]; state.tab=0;
  tabsRemember(); renderTabs();
}

function cycleTab(delta){
  if(state.tabs.length<2) return;
  // Step from what is on screen; fall back to the write pointer on the category route.
  const from=state.sel!=null&&state.tabs.indexOf(state.sel)>=0 ? state.tabs.indexOf(state.sel)
           : (state.tab<0?0:state.tab);
  activateTab((from+delta+state.tabs.length)%state.tabs.length);
}

/*__SEARCH_CORE_START__*/
// ---------- search engine: index, query parsing, scoring, highlighting ----------
// Everything between the two __SEARCH_CORE__ sentinels is PURE: no DOM, no module globals except the
// injected SX_ENV below. scripts/search-selftest.mjs extracts exactly this block and runs it against a
// freshly generated report, which is the only automated coverage the search has — keep it that way.
//
// The two things the engine needs from the rest of the app, injected rather than closed over so the
// block stays standalone. Assigned right after the block (TM and elementNames both exist by then).
const SX_ENV={TM:{}, elementNames:()=>new Map()};

// ---------- search index (shared by the command palette and the browse list) ----------
// One field per haystack, because "find everything" and "rank sensibly" pull in opposite directions:
// the scorer weights them (see SX_FIELDS), so a name hit can never be buried by a script-body hit.
//   name / key  — the node's own identity, also kept pre-tokenised (see hayTokens).
//   lab         — every caption a *person* reads on screen: the node's own label, a form field's or a
//                 data object column's label, an outcome button's caption, a permission's label, a
//                 BPMN/CMMN element's name, a decision table's column headers. Collected by field NAME
//                 during the walk (HAY_LAB_KEYS), so a label a parser starts emitting somewhere new is
//                 searchable without a change here. `label:` narrows to it.
//   desc        — the prose the modeller wrote *about* the thing: Design's model Description, BPMN /
//                 CMMN `documentation` (model and element), a form component's description, a DMN
//                 rule's annotation. Same collect-by-name trick (HAY_DESC_KEYS); `desc:` narrows to it.
//                 Kept out of `text` on purpose — a sentence somebody wrote to explain a model is not
//                 the same kind of evidence as a script body that happens to contain the word.
//   file / type — where it lives and what it is; `type` carries Design's wording too, so `t:` works.
//   mem         — names of things that are NOT nodes of their own (element ids, in/out parameters, form
//                 fields, columns, permissions …). Enumerated on purpose: their shape carries meaning.
//                 Includes the bot key: a Java bot's getKey() and an action's botKey field both live in
//                 data.botKey, so ⌘K finds the bot class AND its callers.
//   text        — every other string in node.data, collected by a generic deep walk WITH provenance.
//                 This is what makes a script body, an element's documentation, a flow condition or a
//                 field injection findable at all. Enumerating those field by field kept losing the
//                 race with the parsers — a walk cannot fall behind. Not tokenised: a script body would
//                 blow the token array up for no ranking benefit, so it is matched by substring.
const HAY_SKIP=new Set([
  // `diagram` is the rendered SVG (a wall of path data) — indexing it would make every query match
  // every model. The rest is node-id bookkeeping the palette already navigates by.
  'diagram','_uses','usedBy','usages','scopes','_idx','_search',
]);
// The index is built in the browser and never embedded in the report, so a generous entry cap costs
// runtime memory only — not a byte of report size. 400 silently lost the tail of big models.
const HAY_MAX_VALUE=4000, HAY_MAX_ENTRIES=1200;
// Field names that carry a caption / a description, wherever in a node they sit. Nested `name` is
// deliberately NOT a label key: on a column, a parameter or an operation it is the technical
// identifier, and `mem` already carries those — a BPMN/CMMN element's name reaches `lab` through
// elementNames(), the one place that knows those names really are the canvas captions.
const HAY_LAB_KEYS=new Set(['label','elementName']);
// Node kinds whose own `label` is NOT a caption anybody wrote: a variable's identifier, an expression's
// or a binding's source text, a string literal, a Java FQN, a method signature, a REST path, a changelog
// file name, a worker topic, a group id. Atlas synthesised every one of them out of something else.
// `label:` means "the words a person reads in a model", and putting these in it is what made
// `label:octo` answer with the variable `octoCaseId` — the identifier a field binds to, not its caption.
// Those nodes stay findable by name, key and free text, exactly as before; they are simply not labels.
const LAB_NOT_A_CAPTION=new Set(['variable','expression','binding','string','customFunction','external',
  'java','method','endpoint','liquibase','topic','group']);
const HAY_DESC_KEYS=new Set(['description','documentation','annotation']);
// Labels and descriptions get their own cap, not a share of HAY_MAX_ENTRIES: `id`/`type`/`value` fill
// the generic bag four times faster than labels arrive, so a 500-field form would have lost the tail of
// its captions — the half of the form a search most needs to reach.
const HAY_MAX_NAMED=3000;
// Field name → what to call it in the "why did this match" hint.
const HAY_LABEL={
  script:'script', documentation:'doc', condition:'condition', conditions:'condition',
  delegateExpression:'delegate', expression:'expression', class:'class', formKey:'form',
  candidateGroups:'groups', candidateUsers:'users', assignee:'assignee', resultVariable:'result var',
  inputs:'DMN input', outputs:'DMN output', rules:'DMN rule', inputExpressions:'DMN input',
  annotation:'DMN annotation', fields:'field', topic:'topic', url:'url', tableName:'table',
  elementName:'element', label:'label', description:'description',
};
// ---------- query parsing + scoring ----------
// The old matcher did `haystack.indexOf(wholeQuery)`, which made word order and adjacency mandatory:
// "shopping template" found nothing even when a data object was named "… Shopping list template",
// and "demo d05" found nothing where "demo-d05" found plenty. Terms are now independent and AND-ed.

// Splits BOTH the query and the haystacks. Splitting the query too is the point: `demo d05`,
// `demo-d05` and `demo_d05` all reduce to the terms [demo, d05] and therefore mean the same search.
const SX_SEP=/[\s\-_./:,;()[\]{}<>"'`|\\+*=?!@#$%^~]+/;

/** Lowercase tokens of `s`, split at separators AND at camelCase / letter↔digit boundaries. The
 *  undivided part is kept too, so `outreachTemplateKey` yields
 *  [outreachtemplatekey, outreach, template, key] and `DEMO-D05` yields [demo, d05, d, 05].
 *  That is what lets a term after a hyphen or inside a camel hump still count as a word start. */
function hayTokens(s){
  if(s==null||s==='') return [];
  const out=[];
  String(s).split(SX_SEP).forEach(part=>{
    if(!part) return;
    out.push(part.toLowerCase());
    const subs=part.replace(/([a-z0-9])([A-Z])/g,'$1 $2')      // aB     → a|B
                   .replace(/([A-Z]+)([A-Z][a-z])/g,'$1 $2')   // ABCd   → AB|Cd
                   .replace(/([A-Za-z])([0-9])/g,'$1 $2')      // d05    → d|05
                   .replace(/([0-9])([A-Za-z])/g,'$1 $2')      // 05d    → 05|d
                   .split(' ');
    if(subs.length>1) subs.forEach(t=>{ if(t) out.push(t.toLowerCase()); });
  });
  return out;
}

const SX_FACET_KEYS={t:'type',type:'type',file:'file',key:'key',in:'section',id:'id',
  label:'lab',desc:'desc',description:'desc',doc:'desc'};
/**
 * Parse a raw query into `{terms, phrases, facets, pending}`.
 *  - terms   — order-independent, ALL must match somewhere (AND)
 *  - phrases — `"…"` quoted, must match contiguously
 *  - facets  — inline `t:`/`type:`/`file:`/`key:`/`in:`/`id:`/`label:`/`desc:` hard filters
 *  - pending — a facet typed through the colon but not given a value yet (`label:` at the end)
 */
function qParse(q){
  const raw=String(q==null?'':q).trim();
  const parsed={raw, terms:[], phrases:[], facets:{}, pending:null, empty:true};
  if(!raw) return parsed;
  // A facet whose VALUE is quoted comes out before the phrase pass, or it never comes out at all:
  // `label: "Mein Label"` is the only way to ask a facet for a multi-word caption, and the phrase pass
  // used to strip the quotes first — leaving `label:` with nothing to bind, so the facet regex below
  // gave up and the word "label" degraded into a free-text term. The query then answered with whichever
  // nodes happened to contain both the word "label" and the phrase — three arbitrary hits instead of
  // every node carrying that caption. The value stays contiguous, exactly as a quoted phrase would.
  let rest=raw.replace(/(^|\s)(description|label|type|desc|file|doc|key|in|id|t):\s*"([^"]*)"/gi,(m,pre,k,inner)=>{
    const v=inner.trim().toLowerCase();
    if(v) parsed.facets[SX_FACET_KEYS[k.toLowerCase()]]=v;
    return ' ';
  });
  // Quoted phrases next: inside quotes the separators are literal, so a user who really wants an
  // adjacent match can still ask for one. After the facet-value pass, so `"label: foo"` stays a phrase.
  rest=rest.replace(/"([^"]*)"/g,(m,inner)=>{
    const p=inner.trim().toLowerCase();
    if(p) parsed.phrases.push(p);
    return ' ';
  });
  // `\s*` after the colon because `desc: approval` is what people type first, and without it the query
  // did not merely miss — it read as the two terms `desc` and `approval` and answered with whichever
  // node happened to have the word "desc" in a script body. A silent wrong hit is worse than none.
  // Longest alternative first so the intent is readable; the engine would backtrack into it either way.
  // Case is irrelevant on both halves: the prefix through /i, the value through toLowerCase() below
  // against haystacks that are lowercased when the index is built.
  rest=rest.replace(/(^|\s)(description|label|type|desc|file|doc|key|in|id|t):\s*(\S+)/gi,(m,pre,k,v)=>{
    parsed.facets[SX_FACET_KEYS[k.toLowerCase()]]=v.toLowerCase();
    return ' ';
  });
  // A facet with no value yet is someone mid-thought (they clicked the `label:` chip, or typed the
  // colon and are about to type the caption). It must not fall through as the term "label" — that is
  // the degraded query this parser exists to prevent — so it is claimed here and surfaced as `pending`
  // for the palette to explain instead of answering.
  rest=rest.replace(/(^|\s)(description|label|type|desc|file|doc|key|in|id|t):\s*$/i,(m,pre,k)=>{
    parsed.pending=SX_FACET_KEYS[k.toLowerCase()];
    return ' ';
  });
  rest.split(SX_SEP).forEach(t=>{ if(t) parsed.terms.push(t.toLowerCase()); });
  parsed.empty=!parsed.terms.length && !parsed.phrases.length && !Object.keys(parsed.facets).length;
  return parsed;
}

// Field weights. `ex` = the term IS a whole token, `pre` = a token starts with it, `sub` = it occurs
// somewhere. A name hit must always outrank a free-text hit, which is what the old tiers were for —
// the difference is that now every term is scored on its own and the scores add up.
// What kind of thing is this, in terms of "is it what people mean when they search"? A model you can
// open in Design outranks an incidental mention of the same word in a string literal, an expression or
// an external library symbol. Without this, "template" ranked a library symbol above the data object
// actually named "… Shopping list template", because the shorter name scored better on coverage.
const SX_KIND_BOOST={
  app:200, process:200, case:200, decision:200, dataObject:200,
  form:190, page:190, dataDictionary:190, masterData:190, template:170,
  action:150, agent:150, service:150, query:150, securityPolicy:150,
  channel:140, event:140, knowledgeBase:140, sequence:140, document:140,
  variableExtractor:140, sla:140, dashboardComponent:140, palette:100,
  bot:130, serviceOperation:120, topic:120, signal:120, message:120, error:120, escalation:120,
  group:110, endpoint:100, java:90, liquibase:90, method:70, variable:60,
  customFunction:40, expression:10, binding:10, string:0, external:0,
};
// Ordered by weight, descending: the phrase loop in scoreIndex() takes the first field that contains
// the phrase, so the order is part of the ranking, not cosmetic.
const SX_FIELDS=[
  {f:'name', ex:1000, pre:700, sub:500},
  {f:'key',  ex:900,  pre:650, sub:450},
  // A caption sits between the node's own name and its internal members: it is what somebody typed for
  // a human to read, so "Kundennummer" finding the form that shows it outranks an element id match.
  {f:'lab',  ex:600,  pre:430, sub:300},
  {f:'mem',  ex:400,  pre:300, sub:200},
  // Below members, above free text: a description explains the thing, but the words in it are prose —
  // an incidental "customer" in a sentence must not outrank a parameter actually named customer.
  {f:'desc', ex:280,  pre:230, sub:180},
  {f:'file', ex:170,  pre:150, sub:120},
  {f:'type', ex:150,  pre:140, sub:110},
  {f:'text', ex:90,   pre:90,  sub:60},
];

/** Best score for one term across all fields of a prepared index. `null` = this term matched nothing. */
function termScore(sx, term){
  let best=null;
  for(let i=0;i<SX_FIELDS.length;i++){
    const spec=SX_FIELDS[i], s=sx[spec.f];
    if(!s) continue;
    let sc=0;
    const toks=sx[spec.f+'Tok'];
    if(toks){
      for(let j=0;j<toks.length;j++){
        const t=toks[j];
        if(t===term){ sc=spec.ex; break; }
        if(sc<spec.pre && t.lastIndexOf(term,0)===0) sc=spec.pre;
      }
    }
    if(!sc && s.indexOf(term)>=0) sc=spec.sub;
    if(sc && (!best||sc>best.score)) best={score:sc, field:spec.f};
  }
  return best;
}

/**
 * Score a parsed query against one prepared index (see searchIndex). Returns `{score, fields}` or
 * `null` when the node is not a match. `indeg` is an importance prior: a heavily referenced model
 * beats an obscure string literal that happens to contain the same word.
 */
function scoreIndex(sx, parsed, indeg){
  if(!sx || !parsed || parsed.empty) return null;
  const fc=parsed.facets;
  if(fc.type && (sx.type||'').indexOf(fc.type)<0) return null;
  if(fc.file && (sx.file||'').indexOf(fc.file)<0) return null;
  if(fc.key && (sx.key||'').indexOf(fc.key)<0) return null;
  if(fc.lab && (sx.lab||'').indexOf(fc.lab)<0) return null;
  if(fc.desc && (sx.desc||'').indexOf(fc.desc)<0) return null;
  if(fc.id && (sx.ids||'').indexOf(fc.id)<0) return null;
  if(fc.section && (sx.section||'').toLowerCase().indexOf(fc.section)<0) return null;
  let score=0;
  const fields={};
  for(let i=0;i<parsed.phrases.length;i++){
    const ph=parsed.phrases[i];
    let sc=0, fld='';
    for(let j=0;j<SX_FIELDS.length;j++){
      const spec=SX_FIELDS[j], s=sx[spec.f];
      if(s && s.indexOf(ph)>=0){ sc=spec.sub; fld=spec.f; break; }
    }
    if(!sc) return null;                         // an explicit phrase is a hard requirement
    score+=sc+200; fields[fld]=1;
  }
  for(let i=0;i<parsed.terms.length;i++){
    const b=termScore(sx, parsed.terms[i]);
    if(!b) return null;                          // AND: one unmatched term drops the node
    score+=b.score; fields[b.field]=1;
  }
  if(parsed.terms.length>1){
    // The whole query, in order, inside the name — "shopping list template" should still beat a node
    // that merely contains those three words in three unrelated places.
    if(sx.name && sx.name.indexOf(parsed.terms.join(' '))>=0) score+=400;
    // A caption that reads exactly like what was typed is nearly as strong a signal as the node's own
    // name: someone who types "OCTO ID" in full means the form that displays those words, not the two
    // words happening to land in the same haystack. Below the name bonus, and never both.
    else if(sx.lab && sx.lab.indexOf(parsed.terms.join(' '))>=0) score+=300;
    if(Object.keys(fields).length===1) score+=150;
  }
  if(sx.name){
    // Coverage: how much of the name the query actually accounts for. Replaces the old
    // "shorter label wins" sort, which ranked by an accident of naming rather than by fit.
    let chars=0;
    for(let i=0;i<parsed.terms.length;i++)
      if(sx.name.indexOf(parsed.terms[i])>=0) chars+=parsed.terms[i].length;
    score+=Math.round(200*Math.min(1, chars/Math.max(8, sx.name.length)));
  }
  score+=sx.kind||0;
  score+=Math.min(indeg||0,20)*3;
  return {score, fields};
}

/** Loose subsequence score, for "did you mean…" ONLY — far too permissive to rank real hits with. */
function fuzzyScore(s, term){
  if(!s||!term) return 0;
  const low=String(s).toLowerCase();
  let i=0, gaps=0, first=-1;
  for(let j=0;j<low.length && i<term.length;j++){
    if(low[j]===term[i]){ if(first<0) first=j; i++; }
    else if(first>=0) gaps++;
  }
  if(i<term.length) return 0;
  return Math.max(1, 1000-gaps*8-first*4-Math.max(0, low.length-term.length));
}

/** The facet keys whose value is text a reader sees on a row — these highlight like terms. */
const SX_HL_FACETS=['lab','desc','key','id'];

/**
 * "Did you mean" score of one node's index against the query's words: every word must come close to
 * the name, the key or one caption line (AND — same contract as the real search), and closeness is
 * [fuzzyScore]'s subsequence measure. Caption lines are scanned because a mistyped *caption* is the
 * most common misspelling of all — the old name/key-only scan had no suggestion for it. The lab blob
 * is line-per-caption; the scan is capped so the zero-result path stays cheap.
 */
function suggestScore(ix, needles){
  const labs=ix.lab?ix.lab.split('\n').slice(0,60):[];
  let total=0;
  for(let j=0;j<needles.length;j++){
    const t=needles[j];
    let sc=Math.max(fuzzyScore(ix.name, t), fuzzyScore(ix.key, t));
    for(let i=0;i<labs.length&&sc<1000;i++) sc=Math.max(sc, fuzzyScore(labs[i], t));
    if(sc<=0) return 0;
    total+=sc;
  }
  return total;
}

/**
 * Split `text` into `{t, hit}` segments covering every matched range of `parsed`. Returns segments
 * rather than HTML on purpose: the caller escapes each one, so a highlight can never inject markup
 * out of model data.
 */
function hlite(text, parsed){
  const s=String(text==null?'':text);
  if(!s||!parsed||parsed.empty) return [{t:s, hit:false}];
  const low=s.toLowerCase();
  // Bound facet values highlight like terms: `label:Recalculate` finds rows BY that caption, so the
  // caption must light up. Only the content facets — a type:/in:/file: value names a bucket, not text.
  const fvals=SX_HL_FACETS.filter(k=>parsed.facets[k]).map(k=>parsed.facets[k]);
  const needles=parsed.phrases.concat(parsed.terms, fvals);
  const marks=[];
  for(let i=0;i<needles.length;i++){
    const nd=needles[i];
    if(!nd) continue;
    let from=0, at;
    while((at=low.indexOf(nd, from))>=0){ marks.push([at, at+nd.length]); from=at+nd.length; }
  }
  if(!marks.length) return [{t:s, hit:false}];
  marks.sort((a,b)=>a[0]-b[0]||a[1]-b[1]);
  const merged=[];
  for(let i=0;i<marks.length;i++){
    const last=merged[merged.length-1];
    if(last && marks[i][0]<=last[1]) last[1]=Math.max(last[1], marks[i][1]);
    else merged.push([marks[i][0], marks[i][1]]);
  }
  const out=[]; let pos=0;
  for(let i=0;i<merged.length;i++){
    if(merged[i][0]>pos) out.push({t:s.slice(pos, merged[i][0]), hit:false});
    out.push({t:s.slice(merged[i][0], merged[i][1]), hit:true});
    pos=merged[i][1];
  }
  if(pos<s.length) out.push({t:s.slice(pos), hit:false});
  return out;
}

/**
 * Deep-walk a value, pushing one `{k, id, v}` entry per string found: `k` is the field it came from,
 * `id` the nearest enclosing object's id/name/key — for a script body that is the script task's
 * element id, which is what lets a hit jump straight to the right row.
 * Strings only: numbers and booleans ("true", counts) match everything and mean nothing here.
 */
function walkHay(v, key, owner, out, lab, desc){
  if(v==null) return;
  // Three bags, three caps: the walk may only give up once ALL of them are full. Returning at the
  // generic cap alone is what would starve `lab`/`desc` on a large model (see HAY_MAX_NAMED).
  if(out.length>=HAY_MAX_ENTRIES && lab.length>=HAY_MAX_NAMED && desc.length>=HAY_MAX_NAMED) return;
  if(typeof v==='string'){
    if(!v) return;
    const t=v.length>HAY_MAX_VALUE?v.slice(0,HAY_MAX_VALUE):v;
    if(out.length<HAY_MAX_ENTRIES) out.push({k:key, id:owner, v:t});
    // A caption/description stays in `out` as well: that is what lets matchWhere() explain the hit
    // ("label · amount"), and `text` is a superset by design.
    if(HAY_LAB_KEYS.has(key)){ if(lab.length<HAY_MAX_NAMED) lab.push(t); }
    else if(HAY_DESC_KEYS.has(key) && desc.length<HAY_MAX_NAMED) desc.push(t);
    return;
  }
  if(Array.isArray(v)){ v.forEach(x=>walkHay(x, key, owner, out, lab, desc)); return; }
  if(typeof v!=='object') return;
  // the element this sub-object belongs to — `where` is how a form/page REST call names its button
  const own=v.id||v.name||v.key||v.where||owner;
  for(const k in v){ if(!HAY_SKIP.has(k)) walkHay(v[k], k, own, out, lab, desc); }
}
function searchIndex(n){
  if(n._idx) return n._idx;                    // node data never changes at runtime — build once
  const d=n.data||{};
  let s='';
  // Built once and reused three times below (members, `lab`, `ids`): elementNames() rebuilds its Map
  // on every call, and this is the hot path the palette pays for on the first keystroke.
  const els=(n.type==='process'||n.type==='case')?SX_ENV.elementNames(n):null;
  // model element ids + names (tasks, gateways, events, plan items) — an element id from the BPMN
  // XML or the diagram surfaces its model in ⌘K
  if(els) s+=' '+[...els.entries()].map(([id,e])=>id+' '+(e.name||'')).join(' ');
  // A column's own name matters as much as its label ("customerName" is what a script writes), and the
  // referenced data object is how you find the owner of a relation — both were tier-3-only before.
  if(n.type==='dataObject') s+=' '+(d.fields||[]).join(' ')+' '+(d.serviceTableName||'')+' '+
    (d.columns||[]).map(c=>(c.name||'')+' '+(c.label||'')+' '+(c.type||'')+' '+
      (c.refDataObject||'')+' '+(c.relationship||'')).join(' ');
  if(n.type==='service') s+=' '+(d.columns||[]).map(c=>(c.name||'')+' '+(c.columnName||'')+' '+(c.type||'')).join(' ');
  if(n.type==='liquibase') s+=' '+(d.columns||[]).map(c=>(c.name||'')+' '+(c.type||'')).join(' ');
  // In/out parameters are not nodes of their own, so without this a parameter name would never surface
  // the process/case/action that passes it. Both sides of a mapping match — the caller's variable AND
  // the callee's contract name (a service parameter, an event payload field).
  if((d.ioParameters||[]).length) s+=' '+d.ioParameters.map(paramHaystack).join(' ');
  if(n.type==='variable') s+=' '+(d.ioParams||[]).map(paramHaystack).join(' ');
  if(n.type==='service') s+=' '+(d.operations||[]).map(o=>
    (o.params||[]).concat(o.outParams||[]).map(p=>p.name||'').join(' ')).join(' ');
  if(n.type==='serviceOperation') s+=' '+(d.params||[]).concat(d.outParams||[])
    .map(p=>(p.name||'')+' '+(p.type||'')).join(' ');
  // form/page fields, app variables, agent tools, policy permissions and dictionary types are not
  // nodes of their own — index them here so their names surface the model that declares them.
  if(n.type==='form'||n.type==='page') s+=' '+(d.fields||[]).map(f=>(f.id||'')+' '+(f.label||'')+
      // What a button invokes and the expression it evaluates are how people look for a button
      // ("which form calls notifyCustomer?", "where is that {{total}} computed?").
      ' '+((f.callee||{}).key||'')+' '+((f.settings||{}).script||'')).join(' ')+
    // A REST button's endpoint is the thing people search a form by ("which page calls /canEdit?"), so it
    // ranks as a member rather than sinking to free text. Templated hosts ({{endpoints.*}}) match on any
    // path fragment because the whole URL is one searchable string.
    ' '+(d.restCalls||[]).map(r=>(r.where||'')+' '+(r.method||'')+' '+(r.url||'')).join(' ');
  if(n.type==='app') s+=' '+(d.variables||[]).map(v=>v.key||'').join(' ');
  if(n.type==='agent') s+=' '+(d.tools||[]).map(t=>t.key||'').join(' ');
  if(n.type==='securityPolicy') s+=' '+(d.permissions||[]).map(p=>(p.key||'')+' '+(p.label||'')+' '+(p.roles||[]).join(' ')).join(' ');
  if(n.type==='dataDictionary') s+=' '+(d.types||[]).join(' ');
  // `id:` searches identifiers and nothing else — the model key plus every element id the model
  // declares. Kept apart from the member haystack on purpose: `id:save` must not match a *caption* that
  // reads "Save", which is exactly the confusion that made looking a button up by its id hopeless.
  const ids=[n.key];
  if(els) ids.push(...els.keys());
  if(n.type==='form'||n.type==='page'){
    (d.fields||[]).forEach(f=>ids.push(f.id));
    (d.restCalls||[]).forEach(r=>ids.push(r.where));
  }
  (d.ioParameters||[]).forEach(p=>ids.push(p.element));
  const entries=[], labs=[], descs=[];
  for(const k in d){ if(!HAY_SKIP.has(k)) walkHay(d[k], k, null, entries, labs, descs); }
  // The label people mean first is the node's own, and the walk only ever sees node.data — unless this
  // kind's label is an identifier rather than a caption (see LAB_NOT_A_CAPTION).
  if(n.label!=null&&n.label!==''&&!LAB_NOT_A_CAPTION.has(n.type)) labs.push(String(n.label));
  // A BPMN/CMMN element's `name` IS its caption on the canvas — the one context in which a nested
  // `name` is a label rather than an identifier, which is why it is added here and not by key.
  if(els) for(const e of els.values()) if(e.name) labs.push(String(e.name));
  // A decision table's input/output prefer their label over the expression behind them (see the DMN
  // parser), so the column headers of a decision are captions too.
  if(n.type==='decision')
    (d.inputs||[]).concat(d.outputs||[]).forEach(x=>{ if(typeof x==='string'&&x) labs.push(x); });
  // Tokenised from the ORIGINAL case: hayTokens() splits at camelCase humps, which a pre-lowercased
  // join would have thrown away.
  // Joined by a newline, not a space: the phrase test in scoreIndex() must not be able to match across
  // two unrelated captions ("Second field" + "One human task" would otherwise contain "field one").
  const labRaw=labs.join('\n'), descRaw=descs.join('\n');
  const name=String(n.label==null?'':n.label).toLowerCase();
  const key=String(n.key==null?'':n.key).toLowerCase();
  const file=String(n.file||'').toLowerCase();
  const tm=SX_ENV.TM[n.type]||[];
  const mem=(s+' '+(d.botKey||'')).toLowerCase();
  n._idx={
    name, key, file, mem,
    lab:labRaw.toLowerCase(), desc:descRaw.toLowerCase(),
    ids:ids.filter(x=>x!=null&&x!=='').join(' ').toLowerCase(),
    // Both the internal type and the Design wording, so `t:do`, `t:dataobject` and `t:data` all work.
    type:(n.type+' '+(tm[0]||'')).toLowerCase(),
    section:n.type==='external'?(d.flowableApi?'Integration':'Other'):(tm[1]||'Other'),
    kind:SX_KIND_BOOST[n.type]||0,
    text:entries.map(e=>e.v).join('\n').toLowerCase(),
    // Free text is deliberately NOT tokenised — a script body would blow the token array up for no
    // ranking benefit; it is matched by substring at the lowest weight.
    nameTok:hayTokens(n.label), keyTok:hayTokens(n.key),
    fileTok:hayTokens(n.file), memTok:hayTokens(mem),
    labTok:hayTokens(labRaw), descTok:hayTokens(descRaw),
    entries,
  };
  return n._idx;
}
function paramHaystack(p){ return (p.source||'')+' '+(p.target||'')+' '+(p.element||'')+' '+(p.kind||''); }
// Why did this node match, and where? When the hit did not come from the node's own name, the palette
// shows the mapping / field it came from instead of the key — otherwise the match looks arbitrary —
// and `el` carries the element id so the detail panel can open exactly that row.
// `fields` is the winning-field set from scoreIndex: a pure name/key hit needs no explanation.
function matchWhere(n,parsed,fields){
  if(!parsed||parsed.empty) return null;
  // An `id:` search asked for one element by name, so the answer is that element — named in original
  // case, because `el` has to match the row's `data-el` for the panel to open it.
  const fid=parsed.facets&&parsed.facets.id;
  if(fid){
    const d=n.data||{};
    const cands=[].concat(
      (n.type==='process'||n.type==='case')?[...SX_ENV.elementNames(n).keys()]:[],
      (d.fields||[]).map(f=>f.id), (d.restCalls||[]).map(r=>r.where),
      (d.ioParameters||[]).map(p=>p.element));
    const el=cands.find(x=>x!=null&&x!==''&&String(x).toLowerCase().indexOf(fid)>=0);
    if(el!=null) return {hint:'id '+String(el), el:String(el)};
  }
  // If the name or the key carried the match, the row already shows it: the label and the key are both
  // rendered with the hit highlighted, so the key stays the more useful hint. Explaining a name match by
  // digging through the walked entries produced hints like a bare "key" — the field the value came from,
  // which is exactly the thing the row was already displaying.
  if(fields && (fields.name || fields.key)) return null;
  // A facet-only query (`label:save`, `desc:approval`) has no term to explain the hit with, and the
  // facet IS the reason — the same case the `id:` branch above handles. Its value joins the needles, so
  // the walk below names the caption or the sentence that matched and hands back its element: clicking
  // the row opens the field that reads "Save", not just the form that contains it.
  const ffac=(parsed.facets&&parsed.facets.lab)||null, fdes=(parsed.facets&&parsed.facets.desc)||null;
  if(fields && !fields.mem && !fields.text && !fields.file && !fields.lab && !fields.desc
     && !ffac && !fdes) return null;
  const needles=parsed.phrases.concat(parsed.terms);
  if(ffac) needles.push(ffac);
  if(fdes) needles.push(fdes);
  const anyIn=s=>{ const t=String(s||'').toLowerCase(); return needles.some(nd=>t.indexOf(nd)>=0); };
  const p=((n.data||{}).ioParameters||[]).find(x=>anyIn(paramHaystack(x)));
  if(p){
    const flow=[p.source,p.target].filter(x=>x!=null&&x!=='').join(' → ');
    return {hint:p.dir+' '+flow+(p.element?' @'+p.element:''), el:p.element||''};
  }
  const ent=searchIndex(n).entries;
  // A `label:` / `desc:` facet has to be explained by a field OF THAT KIND. The plain scan below takes
  // the first entry that merely CONTAINS the word, which answered `label:volume` with "id ·
  // expectedVolume" and `label:update` with "key · update" — naming the very field the facet exists to
  // exclude, and contradicting what the reader had just typed.
  if(ffac||fdes){
    const keys=ffac?HAY_LAB_KEYS:HAY_DESC_KEYS;
    const hit=ent.find(x=>keys.has(x.k)&&anyIn(x.v));
    // The matched text leads the hint: it is what the reader searched for, and it is what the
    // highlighter can light up — "label · orderTotal" named the owner but never showed the caption.
    if(hit){ const v=String(hit.v), short=v.length>48?v.slice(0,47)+'…':v;
      return {hint:(HAY_LABEL[hit.k]||hit.k)+' · '+short+(hit.id?' @'+hit.id:''), el:hit.id||''}; }
    // A BPMN/CMMN element's name never reaches the walked entries — it arrives through elementNames() —
    // so a `label:` hit on a task's caption has to be looked up where it actually lives.
    if(ffac && (n.type==='process'||n.type==='case'))
      for(const [id,el] of SX_ENV.elementNames(n))
        if(anyIn(el.name)) return {hint:'label · '+el.name, el:String(id)};
    // Nothing but the node's OWN label matched, and the row is already showing that. Falling through
    // would dig up some unrelated entry and hint the bare word "key"; the key itself is more use.
    if(ffac && String(n.label==null?'':n.label).toLowerCase().indexOf(ffac)>=0) return null;
  }
  const e=ent.find(x=>anyIn(x.v));
  if(!e) return null;
  return {hint:(HAY_LABEL[e.k]||e.k)+(e.id?' · '+e.id:''), el:e.id||''};
}
/*__SEARCH_CORE_END__*/
SX_ENV.TM=TM;
SX_ENV.elementNames=elementNames;

// ---------- glue between the pure engine and the app (globals live out here on purpose) ----------
/** Score one node against a parsed query. The ranking itself is in the engine above. */
function scoreNode(n, parsed){
  return scoreIndex(searchIndex(n), parsed, INSIGHTS?(INSIGHTS.indeg.get(n.id)||0):0);
}
/**
 * Build every node's index once, in idle slices after boot. Without this the first keystroke pays for
 * the whole deep walk at once, which on a 3000-node report is a visible stall in the palette.
 */
function prewarmSearchIndex(){
  let i=0;
  const idle=window.requestIdleCallback||(cb=>setTimeout(()=>cb({timeRemaining:()=>8}),60));
  const step=deadline=>{
    while(i<nodes.length && (!deadline||deadline.timeRemaining()>2)) searchIndex(nodes[i++]);
    if(i<nodes.length) idle(step);
  };
  idle(step);
}

// ---------- command palette (⌘K) ----------
const pal=document.getElementById('palette'), palq=document.getElementById('palq'), palres=document.getElementById('palresults');
const palFoot=document.getElementById('palfoot');
const palPanel=pal?pal.querySelector('.pal-panel'):null;
let palList=[], palSel=-1, _palPrevFocus=null;
// Multi-pick: marks by NODE ID (palRender rebuilds the rows on every keystroke, so indices are
// worthless), plus the row a Shift range extends from. Cleared whenever the query changes — a mark
// on a hit that is no longer listed is a trap, not a feature.
let palMarks=new Set(), palAnchor=-1;
function palMarksClear(){ palMarks.clear(); palAnchor=-1; }
function palMarkRange(a,b){
  if(a<0||b<0) return 0;
  let refused=0;
  for(let i=Math.min(a,b); i<=Math.max(a,b); i++){
    if(!palList[i]) continue;
    if(palMarks.has(palList[i].n.id)) continue;
    if(palMarks.size>=MAX_TABS){ refused++; continue; }   // never mark more than can be opened
    palMarks.add(palList[i].n.id);
  }
  return refused;
}
let _palNote='';
function palRenderFoot(){
  if(!palFoot) return;
  const n=palMarks.size;
  palFoot.hidden=!n && !_palNote;
  if(palFoot.hidden) return;
  palFoot.innerHTML=(n?'<span><b>'+n+'</b> marked</span><span>↵ open '+
      (n>1?'all '+n+' in tabs':'in a tab')+'</span><span>'+MODK+'↵ open and keep searching</span>':'')+
    (_palNote?'<span class="pf-note">'+esc(_palNote)+'</span>':'');
}
/** Open the marked hits as tabs. `keepOpen` (⌘/Ctrl+Enter) leaves the palette up and the current
 *  tab active, so several queries can be batched into tabs without reopening ⌘K each time. */
function openMarkedPal(keepOpen){
  if(!palMarks.size) return;
  const hits=palList.filter(h=>palMarks.has(h.n.id));                // keep the listed order
  const q=palq.value.trim();
  // Seed each tab's view state with ITS OWN hit context, so switching to the 4th tab of a batch
  // highlights the 4th match — not the first one's.
  hits.forEach(h=>{ _tabView[h.n.id]=Object.assign(_tabView[h.n.id]||{}, {q, el:h.el||''}); });
  const first=hits[0];
  palMarksClear();
  if(!keepOpen) closePalette();
  const r=openTabs(hits.map(h=>h.n.id), {background:!!keepOpen, q, el:first?first.el:''});
  const note=r.dropped ? r.dropped+' not opened — '+MAX_TABS+' tabs is the limit' : '';
  // Report where the user is still looking: the palette footer if it stays up, else the list head.
  if(keepOpen){ _palNote=note; palRender(); palq.focus(); } else setMarkNote(note);
}
// The panel is resizable from its bottom-right corner (CSS `resize:both`) and the size is remembered,
// mirroring the diagram card (see DGCARD_STORE). A default-width palette ellipses the "why it matched"
// hint, which for a REST call is the endpoint URL — the one thing you were searching for.
const PAL_STORE='atlas-palette', PAL_MIN_W=320, PAL_MIN_H=180;
function palPrefs(){ try{ return JSON.parse(localStorage.getItem(PAL_STORE)||'{}')||{}; }catch(err){ return {}; } }
function palRemember(patch){
  try{ localStorage.setItem(PAL_STORE, JSON.stringify(Object.assign(palPrefs(), patch))); }catch(err){}
}
/** Apply the remembered size, clamped to the current window (a size stored on a wider screen must not
 *  push the panel off-view). `.sized` hands the result list the panel's height instead of the 320px cap. */
function applyPalSize(){
  if(!palPanel) return;
  const p=palPrefs();
  if(!p.w && !p.h){ palPanel.classList.remove('sized'); palPanel.style.width=''; palPanel.style.height=''; return; }
  if(p.w) palPanel.style.width=Math.max(PAL_MIN_W, Math.min(p.w, window.innerWidth-24))+'px';
  if(p.h){
    palPanel.style.height=Math.max(PAL_MIN_H, Math.min(p.h, window.innerHeight-48))+'px';
    palPanel.classList.add('sized');
  }
}
function resetPalSize(){
  try{ localStorage.removeItem(PAL_STORE); }catch(err){}
  applyPalSize();
}
function wirePaletteResize(){
  if(!palPanel) return;
  // Double-click the corner to get the default size back, as on the sidebar's drag handle. The handle
  // has no element of its own, so this fires on a dblclick in the bottom-right ~18px of the panel.
  palPanel.addEventListener('dblclick', e=>{
    const r=palPanel.getBoundingClientRect();
    if(e.clientX>r.right-18 && e.clientY>r.bottom-18) resetPalSize();
  });
  if(!window.ResizeObserver) return;
  let first=true;
  new ResizeObserver(()=>{
    if(first){ first=false; return; }              // the observe() call itself fires once
    if(pal.hidden) return;                         // closing/reopening is not a user resize
    clearTimeout(palPanel._rszT);
    palPanel._rszT=setTimeout(()=>{
      if(!palPanel.isConnected || pal.hidden) return;
      palPanel.classList.add('sized');
      palRemember({w:palPanel.offsetWidth, h:palPanel.offsetHeight});
    }, 300);
  }).observe(palPanel);
}
/** `prefill` seeds the query — the list-filter bridge hands over the term you already typed there,
 *  so you never retype it just to widen the search past one category. */
function openPalette(prefill){
  if(!pal.hidden) return;
  hideDgCard();                                  // the card floats above the palette (z-index 120 > 100)
  _palPrevFocus=document.activeElement;
  pal.hidden=false;
  // A real modal: the page behind the dialog is inert (unfocusable, invisible to a screen reader)
  // while it is open. #palette lives outside .shell, so this cannot disable the dialog itself.
  try{ document.querySelector('.shell').inert=true; }catch(e){}
  // With a prefill, palAuto lets palRender() pick the best-scoring row; without one there is nothing
  // ranked to select (the empty query lists Recent).
  palq.value=prefill||''; palSel=-1;
  palShown=PAL_PAGE; palFacet=''; palType=''; palAuto=!!prefill; palMarksClear();
  applyPalSize();
  palRender(); palq.focus(); palq.select();
}
function closePalette(){
  if(pal.hidden) return;
  pal.hidden=true;
  try{ document.querySelector('.shell').inert=false; }catch(e){}
  palMarksClear(); _palNote=''; if(palFoot) palFoot.hidden=true;
  try{ if(_palPrevFocus && document.contains(_palPrevFocus)) _palPrevFocus.focus(); }catch(e){}
  _palPrevFocus=null;
}
// Scoped to the project like the tabs (tabsRemember): every report on a file:// origin shares one
// localStorage, and an unscoped list filled its eight slots with another report's ids — filtered out
// on read, so the Recent list shrank every time you switched reports.
const RECENT_STORE='atlas-recent:'+(DATA.project||'');
function getRecents(){
  try{ return (JSON.parse(localStorage.getItem(RECENT_STORE)||'[]')||[]).filter(id=>byId.get(id)); }
  catch(e){ return []; }
}
function pushRecent(id){
  try{
    const r=getRecents().filter(x=>x!==id); r.unshift(id);
    localStorage.setItem(RECENT_STORE, JSON.stringify(r.slice(0,8)));
  }catch(e){}
}
// How many hits are ranked at all, and how many of those are rendered before the "show more" button.
// The old code scored everything but sliced at 60 with no way to reach the rest — a genuine hit could
// sit at rank 61 and simply never appear.
const PAL_LIMIT=400, PAL_PAGE=60;
// Rows every section with hits is guaranteed on the visible page. See palWindow().
/**
 * The rows to render out of `list` (already best-first): the page is shared out across the sections
 * round-robin, taking each one's next-best hit in turn, rather than cut off by score.
 *
 * Sections render in a fixed order precisely so that a Java class cannot push the whole Models group
 * below Code — but taking the top `shown` BY SCORE undid exactly that, and the two attempts before this
 * one are worth recording. A score cut left the Models group undrawn entirely. Guaranteeing each section
 * three rows fixed that and turned the floor into a ceiling: with 190 hits over ten forms, Models got its
 * three and the rest of the page went to higher-scoring nodes, so ten forms all carrying the searched
 * caption were shown as three. A floor was never what was needed; a fair share is.
 *
 * A section that runs out of hits leaves its share to the others, so a result set that really is all one
 * kind still fills the page with it. Order inside a section is untouched, and the best hit overall is
 * always in — it is the first thing taken in the first round.
 */
function palWindow(list, shown){
  if(list.length<=shown) return list;
  const queues=new Map();
  for(let i=0;i<list.length;i++){
    const sec=searchIndex(list[i].n).section;
    if(!queues.has(sec)) queues.set(sec, []);
    queues.get(sec).push(i);
  }
  const keep=new Set();
  let served=true;
  while(keep.size<shown && served){
    served=false;
    for(const q of queues.values()){
      if(keep.size>=shown) break;
      if(!q.length) continue;
      keep.add(q.shift());
      served=true;
    }
  }
  return list.filter((_,i)=>keep.has(i));
}
let palShown=PAL_PAGE;        // grows via the "show more" button; reset on every query change
let palFacet='';              // active section chip ('' = all) — Models / Integration / Code / …
let palType='';               // active category chip within that section ('' = all of it)
// Sections render in a fixed order, so the best hit is not necessarily the first row. `palAuto` means
// "the selection is still the engine's choice" — set on every new query, cleared as soon as the user
// moves the cursor themselves, so an arrow keypress is never overruled by the next re-render.
let palAuto=true;
/** Escape and wrap the matched ranges of `s` — every segment goes through esc(), so a highlight can
 *  never smuggle markup out of a model name. Shared by the palette and the browse list. */
function hlHtml(s, parsed){
  return hlite(s, parsed).map(seg=>seg.hit?'<mark class="hl">'+esc(seg.t)+'</mark>':esc(seg.t)).join('');
}
/** The facet row: a live result count plus one chip per section present in the hit set. Single-select,
 *  reusing the .pchip pattern from the scripts view. Counts are over ALL hits, so a chip's number does
 *  not shift as you page more rows in. */
/** Design's own wording for a node type ("Data objects", not "dataObject"), as used in the sidebar. */
function typeLabel(t){ return (TM[t] && TM[t][0]) || t; }
/**
 * Two tiers, because "where do I look" and "what am I looking for" are different questions: the top row
 * picks a section, and once one is picked the second row narrows to a single category inside it (Data
 * objects, Forms, Java classes …). Both stay small this way — a flat list of every node type would be
 * 20-odd chips. The `t:` query prefix does the same thing for people who would rather type.
 */
/** The typed filters, in the order the chips teach them. `k` is the canonical facet key qParse
 *  produces, `t` what a person types (the long alias, because `t:` explains nothing), `re` every
 *  alias that parses to it (for removal), `gloss` the two words on the chip that say what the prefix
 *  means, and the title carries the sentence for whoever hovers. */
const PAL_FACET_HELP=[
  {k:'lab',    t:'label:', re:'label',                gloss:'caption',     hint:'Only captions people read — field labels, element names, column labels. Multi-word: label: "Customer name"'},
  {k:'desc',   t:'desc:',  re:'description|desc|doc', gloss:'description', hint:'Only the prose the modeller wrote — documentation, descriptions, annotations'},
  {k:'key',    t:'key:',   re:'key',                  gloss:'model key',   hint:'The model key'},
  {k:'id',     t:'id:',    re:'id',                   gloss:'element id',  hint:'Element ids — a button, a task, a mapping target'},
  {k:'type',   t:'type:',  re:'t|type',               gloss:'e.g. form',   hint:'Kind of node — type:form, type:process, type:dataObject (t: for short)'},
  {k:'file',   t:'file:',  re:'file',                 gloss:'path',        hint:'Source file path'},
  {k:'section',t:'in:',    re:'in',                   gloss:'section',     hint:'Result section — in:Models, in:Code'},
];
let palSynOpen=false;   // the grammar row under a result set, opened by its chip
function palRenderFacets(counts, total, typeCounts, parsed){
  const bar=document.getElementById('palfacets');
  if(!bar) return;
  const pending=parsed&&parsed.pending?PAL_FACET_HELP.find(f=>f.k===parsed.pending):null;
  // The typed filters — `label:`, `key:`, `type:`, `in:` — as chips that insert their prefix; a facet
  // typed through the colon highlights its chip and says what it is waiting for.
  const synRow=()=>{
    let s='<div class="pal-frow pal-syn">'+
      (pending?'<span class="pal-pend">'+esc(pending.t)+' now type its value — quote a multi-word one</span>'
              :'<span class="pal-in">narrow</span>');
    PAL_FACET_HELP.forEach(f=>{
      s+='<button class="pchip'+(pending===f?' on':'')+'" type="button" data-syn="'+esc(f.t)+'" title="'+esc(f.hint)+'">'+
         esc(f.t)+'<span class="pchipn">'+esc(f.gloss)+'</span></button>';
    });
    if(!pending) s+='<span class="pal-synq" title="Quotes match contiguously — alone as a phrase, after a facet as its value">"…" exact</span>';
    return s+'</div>';
  };
  if(!counts){
    // No result set means no count row — but an empty palette is exactly when the typed filters are
    // worth teaching, and this bar is where their live chips will appear once one is used.
    bar.hidden=false; bar.innerHTML=synRow();
    palWireSyntax(bar);
    return;
  }
  bar.hidden=false;
  const secs=SECTIONS.filter(s=>counts[s])
    .concat(Object.keys(counts).filter(s=>SECTIONS.indexOf(s)<0).sort());
  // The bare count is the live region — announcing the rows themselves on every keystroke would make
  // the palette unusable with a screen reader (same reasoning as the list's mark bar).
  let h='<div class="pal-frow"><span class="pal-count">'+total+(total===1?' result':' results')+
        (total>PAL_LIMIT?' · top '+PAL_LIMIT+' listed':'')+'</span>'+
        '<span class="vh" aria-live="polite">'+total+' results</span>';
  // Every inline facet that bound gets a lit chip: the proof it took effect (the silent failure this
  // bar exists to prevent), the reminder it is still on, and — clicked — the way out of it.
  if(parsed) PAL_FACET_HELP.forEach(f=>{
    const v=parsed.facets[f.k];
    if(v) h+='<button class="pchip on" type="button" data-unfacet="'+esc(f.k)+'"'+
             ' title="Remove this filter from the query">'+esc(f.t)+' '+esc(v)+'<span class="pchipn">×</span></button>';
  });
  if(pending) h+='<span class="pal-pend">'+esc(pending.t)+' now type its value</span>';
  // The grammar used to vanish the moment a query matched anything — a reader who always gets some
  // result never learned it existed. One chip keeps it a click away; the row it opens is the same one.
  h+='<button class="pchip pal-more" type="button" data-syn-toggle aria-expanded="'+(palSynOpen?'true':'false')+
     '" title="The typed filters: label:, key:, type:, in:…">narrow '+(palSynOpen?'▴':'▾')+'</button>';
  if(secs.length>1){
    h+='<button class="pchip'+(palFacet?'':' on')+'" type="button" data-facet=""'+
       ' aria-pressed="'+(!palFacet)+'">All</button>';
    secs.forEach(s=>{
      h+='<button class="pchip'+(palFacet===s?' on':'')+'" type="button" data-facet="'+esc(s)+'"'+
         ' aria-pressed="'+(palFacet===s)+'">'+esc(s)+'<span class="pchipn">'+counts[s]+'</span></button>';
    });
  }
  h+='</div>';
  // Only worth a second row when the section actually splits into more than one category.
  const types=typeCounts?Object.keys(typeCounts).sort((a,b)=>
    typeCounts[b]-typeCounts[a]||typeLabel(a).localeCompare(typeLabel(b))):[];
  if(types.length>1){
    h+='<div class="pal-frow pal-frow2"><span class="pal-in">in</span>'+
       '<button class="pchip'+(palType?'':' on')+'" type="button" data-type=""'+
       ' aria-pressed="'+(!palType)+'">All '+esc(palFacet)+'</button>';
    types.forEach(t=>{
      h+='<button class="pchip'+(palType===t?' on':'')+'" type="button" data-type="'+esc(t)+'"'+
         ' aria-pressed="'+(palType===t)+'">'+esc(typeLabel(t))+
         '<span class="pchipn">'+typeCounts[t]+'</span></button>';
    });
    h+='</div>';
  }
  if(palSynOpen) h+=synRow();
  bar.innerHTML=h;
  const tog=bar.querySelector('[data-syn-toggle]');
  if(tog) tog.onclick=()=>{ palSynOpen=!palSynOpen; palRender(); palq.focus(); };
  if(palSynOpen) palWireSyntax(bar);
  const reset=()=>{ palShown=PAL_PAGE; palAuto=true; palMarksClear(); _palNote=''; palRender(); palq.focus(); };
  bar.querySelectorAll('[data-facet]').forEach(b=>b.onclick=()=>{
    palFacet=b.dataset.facet||''; palType='';        // a new section invalidates the category below it
    reset();
  });
  bar.querySelectorAll('[data-type]').forEach(b=>b.onclick=()=>{ palType=b.dataset.type||''; reset(); });
  // Removing a facet means editing the QUERY, not some side state — the chip mirrors typed text, so
  // its × strips that text (under any of its aliases, quoted or bare) and lets a re-parse do the rest.
  bar.querySelectorAll('[data-unfacet]').forEach(b=>b.onclick=()=>{
    const f=PAL_FACET_HELP.find(x=>x.k===b.dataset.unfacet);
    if(f) palq.value=palq.value
      .replace(new RegExp('(^|\\s)(?:'+f.re+'):\\s*("[^"]*"|\\S+)','gi'),' ')
      .replace(/\s+/g,' ').trim();
    reset();
  });
}
/** Chip → query text: append the prefix and hand the cursor back, so the value can be typed at once.
 *  A dangling prefix already at the end is replaced, not stacked — clicking `desc:` after `label:`
 *  means "I picked the other one", and `label: desc:` would parse `desc:` as label's value. */
function palWireSyntax(bar){
  bar.querySelectorAll('[data-syn]').forEach(b=>b.onclick=()=>{
    const v=palq.value.replace(/(^|\s)(description|label|type|desc|file|doc|key|in|id|t):\s*$/i,'$1').replace(/\s+$/,'');
    palq.value=(v?v+' ':'')+b.dataset.syn;
    palShown=PAL_PAGE; palAuto=true; palMarksClear(); _palNote='';
    palRender(); palq.focus();
    palq.setSelectionRange(palq.value.length, palq.value.length);
  });
}
/** Up to 5 "did you mean" nodes. Subsequence matching only, and only ever shown on a zero-result
 *  query — it is far too loose to mix into the real ranking. Words are matched one by one (the old
 *  `join('')` glued `custmer nam` into one needle no real name resembles). */
function palSuggest(parsed){
  const needles=parsed.terms.concat(parsed.phrases).filter(t=>t.length>=3);
  if(!needles.length) return [];
  const out=[];
  nodes.forEach(n=>{
    const sc=suggestScore(searchIndex(n), needles);
    if(sc>0) out.push({n, sc});
  });
  out.sort((a,b)=>b.sc-a.sc||(a.n.label<b.n.label?-1:1));
  return out.slice(0,5).map(x=>x.n);
}
/** Zero-result state. Three situations, three messages: a bare "No matches" for a query that only a
 *  facet chip filtered away is actively misleading, so say which chip did it. */
function palEmptyHtml(parsed, hidden){
  if(parsed.empty)
    return '<div class="pal-empty">Nothing recent yet — visit a few nodes and they will show up here</div>';
  // Defensive: palRender() drops a chip the moment it has no hits left, so today this cannot be reached.
  // It stays because the alternative failure — a bare "No matches" while an invisible filter is doing the
  // hiding — is the single most confusing thing a search can say, and the next filter added here might
  // not clear itself.
  if(hidden>0)
    return '<div class="pal-empty">No matches in <b>'+esc(palFacet)+'</b> — but '+hidden+' elsewhere.'+
      '<div class="pal-sug"><button class="pal-link" type="button" id="palclearfacet">'+
      'Search all sections</button></div></div>';
  const sug=palSuggest(parsed);
  return '<div class="pal-empty">No matches for <b>'+esc(parsed.raw)+'</b>'+
    (sug.length?'<div class="pal-sug">Did you mean '+sug.map(n=>
      '<button class="pal-link" type="button" data-sug="'+esc(n.id)+'">'+esc(n.label)+'</button>'
      ).join(' · ')+'</div>':'')+
    '<div class="pal-tip">Every word has to match, in any order. Searched: names, keys, labels and '+
    'descriptions, files, element ids, script bodies, documentation, conditions, endpoints, groups. '+
    'Narrow with <code>label:</code> <code>desc:</code> <code>key:</code> <code>id:</code> '+
    '<code>type:</code> <code>file:</code> <code>in:</code>; quote <code>"…"</code> for an exact '+
    'phrase — alone, or as a facet value: <code>label: "Customer name"</code>. '+
    'Open this search anywhere with <code>/</code> or '+MODK+'K.</div></div>';
}
function palRender(){
  const raw=palq.value.trim();
  const parsed=qParse(raw);
  let groups=[], dropped=0, total=0, facetCounts=null, typeCounts=null, hidden=0;
  if(parsed.empty){
    palFacet=''; palType='';
    const rec=getRecents().map(id=>byId.get(id));
    if(rec.length) groups=[{label:'Recent', items:rec.map(n=>({n}))}];
  } else {
    const scored=[];
    nodes.forEach(n=>{
      const r=scoreNode(n, parsed);
      if(r) scored.push({n, score:r.score, fields:r.fields});
    });
    // Best fit first; the label is only a tiebreak, so ranking no longer hinges on name length.
    scored.sort((a,b)=>b.score-a.score||(a.n.label<b.n.label?-1:a.n.label>b.n.label?1:0));
    total=scored.length;
    // Facet counts are computed over ALL hits, so a chip's number does not change as you page in more.
    facetCounts={};
    scored.forEach(hit=>{ const s=searchIndex(hit.n).section; facetCounts[s]=(facetCounts[s]||0)+1; });
    if(palFacet && !facetCounts[palFacet]) palFacet='';       // the chip no longer applies
    // Second tier: which category inside the chosen section (Data objects, Forms, Java classes …).
    // Counted over that section's hits only, so the numbers add up to the section chip's own count.
    if(palFacet){
      typeCounts={};
      scored.forEach(hit=>{
        if(searchIndex(hit.n).section!==palFacet) return;
        typeCounts[hit.n.type]=(typeCounts[hit.n.type]||0)+1;
      });
      if(palType && !typeCounts[palType]) palType='';
    } else palType='';                                        // no section picked → no category to pick
    let list=scored;
    if(palFacet) list=list.filter(hit=>searchIndex(hit.n).section===palFacet);
    if(palType) list=list.filter(hit=>hit.n.type===palType);
    hidden=total-list.length;
    if(list.length>PAL_LIMIT) list=list.slice(0, PAL_LIMIT);   // the count line says when this bites
    dropped=Math.max(0, list.length-palShown);
    const bySec={};
    palWindow(list, palShown).forEach(hit=>{
      const s=searchIndex(hit.n).section;
      (bySec[s]=bySec[s]||[]).push(hit);
    });
    // Fixed section order — models first, then integration, then code. Sorting the sections by their
    // best score (what this did before) meant a Java class could push the whole Models group below
    // Code because it happened to score a few points higher, and the grouping shuffled between
    // keystrokes. A grouped list exists to be predictable; the best hit is still what Enter opens,
    // wherever it sits (see palAuto).
    SECTIONS.filter(s=>bySec[s]).forEach(s=>groups.push({label:s, items:bySec[s]}));
    // Anything whose section is not in SECTIONS would otherwise be dropped silently.
    Object.keys(bySec).filter(s=>SECTIONS.indexOf(s)<0).sort()
      .forEach(s=>groups.push({label:s, items:bySec[s]}));
  }
  // Preselect the highest-scoring row, not row 0: with a fixed section order the engine's best hit can
  // sit anywhere in the list, and Enter has to open that one.
  if(palAuto && !parsed.empty){
    let bi=-1, bs=-Infinity, i=0;
    groups.forEach(g=>g.items.forEach(hit=>{
      if((hit.score||0)>bs){ bs=hit.score||0; bi=i; }
      i++;
    }));
    palSel=bi;
    palAuto=false;
  }
  palList=[]; let h='';
  groups.forEach(g=>{
    // presentation: a listbox may only own options — the heading is visual structure, not an option
    h+='<div class="pal-group" role="presentation">'+esc(g.label)+'</div>';
    g.items.forEach(hit=>{
      const n=hit.n, i=palList.length;
      // Hits that did not come from the name explain themselves: "script · scriptTask1", "doc · order".
      const w=parsed.empty?null:matchWhere(n, parsed, hit.fields);
      palList.push({n, el:(w&&w.el)||''});
      const hint=(w&&w.hint)||n.key;
      // title on both: whatever the panel width clips is still readable on hover, without resizing.
      const mk=palMarks.has(n.id);
      h+='<div class="pal-item'+(i===palSel?' sel':'')+(mk?' mark':'')+'" id="pal-'+i+'" role="option"'+
         ' aria-selected="'+(i===palSel)+'" aria-checked="'+mk+'" data-i="'+i+'">'+
         '<span class="ck" aria-hidden="true">✓</span>'+
         nodeIcon(n)+
         '<span class="nm" title="'+esc(n.label)+'">'+hlHtml(n.label, parsed)+'</span>'+
         '<span class="hint" title="'+esc(hint)+'">'+hlHtml(hint, parsed)+'</span></div>';
    });
  });
  if(!h) h=palEmptyHtml(parsed, hidden);
  else if(dropped) h+='<button class="pal-more" type="button" id="palmore">'+
    'Show '+Math.min(dropped, PAL_PAGE)+' more of '+dropped+' remaining</button>';
  palRenderFacets(facetCounts, total, typeCounts, parsed);
  palres.innerHTML=h;
  const more=document.getElementById('palmore');
  if(more) more.onclick=()=>{ palShown+=PAL_PAGE; palRender(); palq.focus(); };
  const clearFacet=document.getElementById('palclearfacet');
  if(clearFacet) clearFacet.onclick=()=>{
    palFacet=''; palType=''; palShown=PAL_PAGE; palAuto=true; palRender(); palq.focus();
  };
  palres.querySelectorAll('[data-sug]').forEach(b=>b.onclick=()=>{
    // Take the suggestion as the new query rather than opening it blind — the user still gets to see
    // what else that spelling turns up.
    const n=byId.get(b.dataset.sug);
    if(!n) return;
    palq.value=n.label; palAuto=true; palShown=PAL_PAGE; palFacet=''; palType='';
    palRender(); palq.focus();
  });
  if(palSel>=0){
    palq.setAttribute('aria-activedescendant','pal-'+palSel);
    const el=document.getElementById('pal-'+palSel); if(el) el.scrollIntoView({block:'nearest'});
  } else palq.removeAttribute('aria-activedescendant');
  palres.querySelectorAll('.pal-item').forEach(el=>el.onclick=ev=>{
    const i=+el.dataset.i, hit=palList[i];
    if(!hit) return;
    // ⌘/Ctrl+click toggles, Shift+click extends — same convention as the browse list; the hover
    // checkbox itself is the toggle too.
    if(modKey(ev) || (ev.target.closest&&ev.target.closest('.ck'))){

      ev.preventDefault();
      if(palMarks.has(hit.n.id)) palMarks.delete(hit.n.id);
      else if(palMarks.size<MAX_TABS){ palMarks.add(hit.n.id); palAnchor=i; }
      palSel=i; palRender(); palq.focus(); return;
    }
    if(ev.shiftKey){
      ev.preventDefault();
      if(palAnchor<0) palAnchor=palSel>=0?palSel:i;
      palMarks.clear(); palMarkRange(palAnchor, i);
      palSel=i; palRender(); palq.focus(); return;
    }
    // `raw`, matching the Enter path exactly — it rides along as the term the detail panel highlights.
    palMarksClear(); closePalette(); select(hit.n.id, raw, hit.el);
  });
  palRenderFoot();
}
// A changed query means a different result set — marks that pointed into the old one would open
// nodes the user can no longer see, so they go with it (same reason palSel resets).
// palSel starts at 0, not -1: the top hit is preselected, so typing and pressing Enter opens the best
// match. With -1 the first Enter did nothing at all. The section chip survives a refinement (palRender
// drops it once it has no hits left), but the paging offset does not.
palq.addEventListener('input', debounce(()=>{
  palAuto=true; palShown=PAL_PAGE; palMarksClear(); _palNote=''; palRender();
},120));
palq.addEventListener('keydown',e=>{
  const mod=modKey(e);
  if(e.key==='ArrowDown'||e.key==='ArrowUp'){
    e.preventDefault();
    const j=e.key==='ArrowDown'?Math.min(palSel+1,palList.length-1):Math.max(palSel-1,0);
    if(e.shiftKey && palList.length){
      // marks changed → rows changed → a real re-render
      if(palAnchor<0) palAnchor=palSel>=0?palSel:j;
      palMarks.clear();
      _palNote=palMarkRange(palAnchor, j)?('marking stops at '+MAX_TABS+' — that is the tab limit'):'';
      palSel=j; palRender();
    } else {
      // a plain arrow moves one class — rebuilding all 60 rows (and re-running matchWhere on each)
      // per keypress made large corpora feel sticky
      palSel=j; palSelUpdate();
    }
  }
  else if(e.key==='Enter'){
    // ⌘/Ctrl+Enter batches: open what is marked, keep the palette up for the next query. Without
    // marks it opens the highlighted hit in a background tab, same as in the list.
    if(palMarks.size){ e.preventDefault(); openMarkedPal(mod); }
    else if(palList[palSel]){
      e.preventDefault();
      const hit=palList[palSel];
      if(mod){ openTabs([hit.n.id], {background:true}); palq.focus(); }
      else { closePalette(); select(hit.n.id, palq.value.trim(), hit.el); }
    }
  }
  else if(e.key==='Escape'){
    // Escape drops the marks first, the panel second — losing a careful multi-pick to a stray
    // Escape is worse than pressing it twice.
    // stopPropagation, or the document-level Escape handler below closes the panel anyway.
    if(palMarks.size){ e.preventDefault(); e.stopPropagation(); palMarksClear(); _palNote=''; palRender(); }
    else closePalette();
  }
});
/** Move the selection highlight without rebuilding the list. */
function palSelUpdate(){
  palres.querySelectorAll('.pal-item.sel').forEach(el=>{
    el.classList.remove('sel'); el.setAttribute('aria-selected','false'); });
  const el=palSel>=0?document.getElementById('pal-'+palSel):null;
  if(el){
    el.classList.add('sel'); el.setAttribute('aria-selected','true');
    el.scrollIntoView({block:'nearest'});
    palq.setAttribute('aria-activedescendant','pal-'+palSel);
  } else palq.removeAttribute('aria-activedescendant');
}
// Tab cycles the dialog's own controls — input, facet chips, unfacet ×, "Show more", "Did you mean" —
// instead of being swallowed. The old trap made every discovery affordance mouse-only. Bound on the
// dialog (not the input) so Tab keeps cycling once focus sits on a chip; focus never leaves the dialog
// because the page behind it is inert.
pal.addEventListener('keydown',e=>{
  if(e.key!=='Tab') return;
  e.preventDefault();
  const els=[palq, ...palPanel.querySelectorAll('button')].filter(el=>el.offsetParent!==null && !el.disabled);
  if(!els.length) return;
  const i=els.indexOf(document.activeElement);
  els[(i+(e.shiftKey?-1:1)+els.length)%els.length].focus();
});
pal.addEventListener('mousedown',e=>{ if(e.target.closest('[data-close]')) closePalette(); });
document.addEventListener('keydown',e=>{
  if((e.metaKey||e.ctrlKey) && (e.key==='k'||e.key==='K')){
    e.preventDefault();
    // The fullscreen diagram sits on the palette's layer and later in the DOM: opening the palette
    // underneath it meant typing into an invisible input. Searching leaves full screen first.
    if(dgmodal && !dgmodal.hidden) closeDiagramModal();
    pal.hidden?openPalette():closePalette();
  } else if(e.key==='/' && pal.hidden && !e.target.closest('input,textarea,select,[contenteditable]')){
    e.preventDefault(); openPalette();                     // guarded: '/' typed in a filter stays there
  } else if(e.key==='Escape' && !pal.hidden){
    closePalette();
  } else if((e.key==='c'||e.key==='o') && !e.metaKey && !e.ctrlKey && !e.altKey && pal.hidden && state.view==='browse' && state.sel
            && (!dgmodal || dgmodal.hidden) && !e.target.closest('input,textarea,select,[contenteditable]')){
    // The two things a reader does after finding a node — copy its key, open its file — had no key of
    // their own while everything around them (⌘K, /, Alt+n) taught that this page is keyboard-driven.
    const n=byId.get(state.sel); if(!n) return;
    if(e.key==='c'){ e.preventDefault(); atlasCopy(n.key, ()=>{}); }
    else if(n.file && window.__atlasOpen){ e.preventDefault(); atlasOpen(n.file); }
  } else if(e.altKey && !e.metaKey && pal.hidden && state.view==='browse'
            && (!dgmodal || dgmodal.hidden)
            && !e.target.closest('input,textarea,select,[contenteditable]')){
    // …and never inside a text field: on a Mac, Alt+←/→ is word-wise caret movement in the list filter.
    // Tab shortcuts are deliberately Alt-based: Chrome reserves ⌘/Ctrl+1..9, ⌘W and Ctrl+Tab for
    // itself and a page cannot preventDefault them, and inside the IDE ⌘W would close the JCEF
    // editor tab. Everything goes through e.code, because Alt+1 yields '¡' and Alt+[ yields '“'
    // on a Mac layout.
    const d=/^Digit([1-9])$/.exec(e.code||'');
    if(d){ e.preventDefault(); activateTab(+d[1]-1); }
    // Brackets are the portable pair: on Windows/Linux Alt+←/→ is the browser's Back/Forward and
    // is not reliably preventable, so both are bound and either one works everywhere.
    else if(e.code==='BracketRight' || e.key==='ArrowRight'){ e.preventDefault(); cycleTab(1); }
    else if(e.code==='BracketLeft'  || e.key==='ArrowLeft'){ e.preventDefault(); cycleTab(-1); }
    else if(e.code==='KeyW'){
      e.preventDefault();
      const i=state.sel!=null?state.tabs.indexOf(state.sel):state.tab;
      if(i>=0) closeTab(i);
    }
  }
});
// The footer says when this page was generated — "Atlas 0.20.0 · generated 3 days ago", the exact time
// on hover — because a page mailed to a reviewer cannot otherwise say how old it is.
function stampProvenance(){
  const el=document.getElementById('atlasver'); if(!el||!DATA.generatedAt) return;
  const t=Date.parse(DATA.generatedAt); if(isNaN(t)) return;
  const d=new Date(t), diff=Math.max(0, Date.now()-t);
  const m=Math.round(diff/60000), h=Math.round(diff/3600000), days=Math.round(diff/86400000);
  const rel=m<2?'just now':m<60?m+' min ago':h<48?h+' h ago':days<60?days+' days ago':Math.round(days/30)+' months ago';
  const ver=DATA.atlasVersion?'Atlas '+DATA.atlasVersion:el.textContent;
  el.textContent=ver+' · '+rel;
  el.setAttribute('data-tip','Generated '+d.toLocaleString()+' by '+ver);   // JCEF shows no title= tooltips
  el.removeAttribute('title');
}
function wireSearchTrigger(){
  // Wrapped, not passed by reference: openPalette takes a prefill string and a click Event is not one.
  document.getElementById('searchbtn').onclick=()=>openPalette();
  document.getElementById('searchkbd').textContent = IS_MAC?'⌘K':'Ctrl K';
  // Reload the page — recovery for the occasional hung/stale explorer. The page is loaded from a
  // file:// URL both in a browser and in the JCEF IDE tab, so a plain reload re-reads it cleanly.
  const rb=document.getElementById('reloadbtn');
  if(rb) rb.onclick=()=>location.reload();
}

// ---------- uncertain-links toggle (suspect ≈ / dynamic ƒ edges) ----------
function wireLinkFilter(){
  const b=document.getElementById('linkfilter');
  const st=DATA.stats||{}, su=st.suspectEdges||0, dy=st.dynamicEdges||0;
  if(!b || !(su+dy)) return;              // nothing flagged — keep the button hidden
  b.hidden=false;
  const paint=()=>{
    b.classList.toggle('off', hideUncertain);
    b.setAttribute('aria-pressed', hideUncertain?'true':'false');
    const tip=(hideUncertain?'Uncertain links hidden':'Uncertain links shown')+' — '+
      su+' suspect (≈ loose/cross-type match), '+dy+' dynamic (ƒ expression-valued). Click to toggle.';
    b.setAttribute('data-tip', tip); b.setAttribute('aria-label', tip);   // data-tip drives the hover bubble
  };
  paint();
  b.onclick=()=>{
    hideUncertain=!hideUncertain;
    try{ localStorage.setItem('atlas-uncertain', hideUncertain?'hide':'show'); }catch(e){}
    rebuildAdj(); computeInsights(); paint();
    renderSidebar(); rerenderView();                        // the tree and the overview count edges too
  };
}

// ---------- utils ----------
function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function enc(s){ return encodeURIComponent(s); }
// A malformed `%` in a hand-edited or truncated link used to throw out of route() on every hashchange,
// freezing navigation for good; an undecodable part simply resolves to nothing (→ the overview).
function dec(s){ try{ return decodeURIComponent(s); }catch(e){ return ''; } }

// ---------- copy ----------
// feather "copy" (two overlapping rounded rects) + a check for the success flash.
const CPY_SVG='<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
const CPY_OK_SVG='<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>';
// A copy-to-clipboard icon button; the payload rides in data-copy (URI-encoded), wired by the
// delegated handler in renderDetail. `what` names the thing in the tooltip ("Copy key", …).
function copyBtn(text,what){
  if(text==null||text==='') return '';
  const lbl='Copy'+(what?' '+what:'');
  return '<button type="button" class="cpy" data-copy="'+enc(String(text))+'" title="'+esc(lbl)+'" aria-label="'+esc(lbl)+'">'+CPY_SVG+'</button>';
}
// ---------- open in the IDE ----------
// Inside IntelliJ the host injects window.__atlasOpen(file, line) (see AtlasFileEditor): a file path
// or a `file:line` in this page opens the source in an editor tab. That is the seam the product is
// built on — models are read here, code is edited there — and until now the path was only copyable.
// The buttons render always and show only under html.ide, which the bridge's arrival sets, so a page
// opened in a plain browser never offers a jump it cannot make.
const OPN_SVG='<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>';
function openBtn(file,line){
  if(!file) return '';
  const lbl='Open in IDE'+(line?' at line '+line:'');
  return '<button type="button" class="cpy opn" data-open="'+enc(String(file))+'"'+(line?' data-line="'+esc(String(line))+'"':'')+
    ' data-tip="'+esc(lbl)+'" aria-label="'+esc(lbl)+'">'+OPN_SVG+'</button>';
}
/** `:12` that opens the file at that line in the IDE (plain text elsewhere). */
function lineRef(file,line){
  if(line==null||line==='') return '';
  return file?'<span class="opn-line" data-open="'+enc(String(file))+'" data-line="'+esc(String(line))+'" data-tip="Open in IDE at line '+esc(String(line))+'">:'+esc(String(line))+'</span>':':'+esc(String(line));
}
function atlasOpen(file,line){
  if(!window.__atlasOpen) return false;
  try{ window.__atlasOpen(String(file), String(line==null?'':line)); return true; }catch(e){ return false; }
}
/** Every copy button under `root`: copies through atlasCopy and flashes a check mark on success. */
function wireCopyButtons(root){
  root.querySelectorAll('.cpy').forEach(b=>{
    b.onclick=e=>{ e.stopPropagation(); e.preventDefault();   // don't navigate the chip/link or toggle the row this button sits in
      atlasCopy(dec(b.dataset.copy), ()=>{ if(b.dataset.busy) return; b.dataset.busy='1';
        const old=b.innerHTML; b.classList.add('ok'); b.innerHTML=CPY_OK_SVG;
        setTimeout(()=>{ b.classList.remove('ok'); b.innerHTML=old; delete b.dataset.busy; },1200); }); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' ') e.stopPropagation(); };   // keep Enter/Space from the parent's nav
  });
}
function wireOpenButtons(root){
  root.querySelectorAll('[data-open]').forEach(b=>{
    b.onclick=e=>{ e.preventDefault(); e.stopPropagation(); atlasOpen(dec(b.dataset.open), b.dataset.line||''); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' ') e.stopPropagation(); };
  });
}
function markIdeBridge(){ if(window.__atlasOpen) document.documentElement.classList.add('ide'); }
window.addEventListener('atlas-ide-bridge', markIdeBridge);
markIdeBridge();
// Single copy path for every affordance. Order: IDE bridge → clipboard API → execCommand → prompt.
// onOk fires only on genuine success, so the UI never shows a false "✓ copied" (the embedded JCEF
// file:// viewer blocks navigator.clipboard — window.__atlasCopy is injected there by the IDE host).
function atlasCopy(text,onOk){
  text=String(text==null?'':text);
  const ok=()=>{ if(onOk) onOk(); };
  if(window.__atlasCopy){ try{ window.__atlasCopy(text); ok(); return; }catch(e){} }
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(text).then(ok,()=>{ if(execCopy(text)) ok(); else prompt('Copy:',text); });
    return;
  }
  if(execCopy(text)){ ok(); return; }
  prompt('Copy:',text);
}
function execCopy(text){
  try{
    const ta=document.createElement('textarea'); ta.value=text; ta.setAttribute('readonly','');
    ta.style.position='fixed'; ta.style.top='0'; ta.style.left='0'; ta.style.opacity='0';
    document.body.appendChild(ta); ta.focus(); ta.select();
    const done=document.execCommand('copy'); document.body.removeChild(ta); return done;
  }catch(e){ return false; }
}

// ---------- theme ----------
// Preference cycle: light → dark → auto (follow the OS). Light is the default — it is the
// Flowable Hub look. JS always resolves the effective theme onto <html data-theme=…>, so the
// CSS needs only one dark-override block; because all node colors are emitted as var()
// references, a switch restyles without re-rendering.
//
// IDE embedding contract: when the page runs inside the IntelliJ JCEF viewer, the IDE seeds
// ?ideTheme=light|dark on the URL and pushes live theme switches via window.__atlasSetIdeTheme.
// The IDE theme is the resolution source for the 'auto' preference (never a hard lock): embedded,
// the default preference becomes 'auto' so the page follows the IDE out of the box, while an
// explicit light/dark from the in-page toggle still wins; cycling back to auto resumes following.
// In a plain browser (no param, no push) the behavior is unchanged.
window.__ideTheme=(()=>{ try{
  const t=new URLSearchParams(location.search).get('ideTheme');
  return (t==='light'||t==='dark')?t:null;
}catch(e){ return null; } })();
// The IDE's own colours, nine of them (IdePalette.kt), so the embedded page wears the LaF instead of the
// Hub palette: seeded from ?idePal=<6hex.6hex…> (no flash on load), replaced by the live push. Any value
// that is not #rrggbb voids the whole set — these strings end up in style properties.
const IDE_PAL_KEYS=['bg','panel','panel2','line','ink','inkDim','accent','selBg','selText'];
function sanitizePal(p){
  if(!p||typeof p!=='object') return null;
  const out={};
  for(const k of IDE_PAL_KEYS){ const v=String(p[k]||''); if(!/^#[0-9a-f]{6}$/i.test(v)) return null; out[k]=v.toLowerCase(); }
  return out;
}
window.__idePal=(()=>{ try{
  const raw=new URLSearchParams(location.search).get('idePal'); if(!raw) return null;
  const parts=raw.split('.'); if(parts.length!==IDE_PAL_KEYS.length) return null;
  const p={}; IDE_PAL_KEYS.forEach((k,i)=>{ p[k]='#'+parts[i]; }); return sanitizePal(p);
}catch(e){ return null; } })();
window.__atlasSetIdeTheme=(t,pal)=>{
  window.__ideTheme=(t==='light'||t==='dark')?t:null;
  window.__idePal=sanitizePal(pal);   // the 1-arg call of an older host clears it: no palette, Hub colours
  applyThemePref();
};
// Exactly the properties applyIdePalette() sets — and the ones clearIdePalette() removes. Never
// style.cssText='': --ui-scale lives on the same element. The tone colours, the --c-* type palette,
// overlays and the search highlight stay from the [data-theme] block; only the surfaces, the ink, the
// accent and what derives from them change. The derivations keep hover/active/selection legible on any
// LaF instead of guessing nine more colours.
const IDE_PAL_PROPS=['--bg','--panel','--panel2','--line','--line2','--ink','--ink-dim','--ink-faint','--accent','--accent-hover',
  '--accent-subtle','--hover','--active','--input','--sel-text','--sel-bar','--on-accent','--focus','--scroll','--scroll-hover'];
function applyIdePalette(p){
  const st=document.documentElement.style, mix=(a,pct,b)=>'color-mix(in srgb, '+a+' '+pct+'%, '+b+')';
  st.setProperty('--bg',p.bg); st.setProperty('--panel',p.panel); st.setProperty('--panel2',p.panel2);
  st.setProperty('--line',p.line); st.setProperty('--line2',mix(p.line,70,p.ink));
  st.setProperty('--ink',p.ink); st.setProperty('--ink-dim',p.inkDim); st.setProperty('--ink-faint',mix(p.inkDim,70,p.panel));
  st.setProperty('--accent',p.accent); st.setProperty('--accent-hover',mix(p.accent,80,p.ink)); st.setProperty('--accent-subtle',mix(p.accent,12,p.panel));
  st.setProperty('--hover',mix(p.ink,6,p.panel)); st.setProperty('--active',mix(p.selBg,30,p.panel)); st.setProperty('--input',p.panel);
  st.setProperty('--sel-text',mix(p.selBg,65,p.ink)); st.setProperty('--sel-bar',p.accent); st.setProperty('--on-accent',p.selText);
  st.setProperty('--focus',mix(p.accent,20,'transparent')); st.setProperty('--scroll',mix(p.line,70,p.ink)); st.setProperty('--scroll-hover',mix(p.inkDim,70,p.panel));
  document.documentElement.classList.add('idepal');
}
function clearIdePalette(){
  const st=document.documentElement.style;
  IDE_PAL_PROPS.forEach(k=>st.removeProperty(k));
  document.documentElement.classList.remove('idepal');
}
function themePref(){ let p=null; try{ p=localStorage.getItem('atlas-theme'); }catch(e){} return p||(window.__ideTheme?'auto':'light'); }
function applyThemePref(){
  const pref=themePref();
  const sys=window.__ideTheme||(matchMedia('(prefers-color-scheme: light)').matches?'light':'dark');
  const theme = pref==='auto'?sys:pref;
  document.documentElement.dataset.theme = theme;
  // The IDE's colours apply while the page shows the IDE's own mode — following it, or asked for the same
  // mode explicitly. A light page forced inside a dark IDE gets the Hub light palette, not IDE-dark greys
  // under light tokens.
  const usePal=!!(window.__idePal&&window.__ideTheme&&theme===window.__ideTheme);
  if(usePal) applyIdePalette(window.__idePal); else clearIdePalette();
  const mt=document.querySelector('meta[name=theme-color]');
  if(mt) mt.content = usePal?window.__idePal.bg:(theme==='dark'?'#0c141c':'#ffffff');
  document.querySelectorAll('[data-theme-btn]').forEach(b=>{
    b.textContent = pref==='auto'?'◐':(pref==='light'?'☀':'☾');
    const tip='Theme: '+pref+(pref==='auto'&&window.__ideTheme?' (follows IDE)':'')+(usePal?' · IDE colours':'')+' — click to switch';
    b.setAttribute('data-tip', tip); b.setAttribute('aria-label', tip);   // data-tip drives the hover bubble
  });
}
function cycleTheme(){
  const next={light:'dark', dark:'auto', auto:'light'}[themePref()];
  try{ localStorage.setItem('atlas-theme', next); }catch(e){}   // private mode / file:// quirks
  applyThemePref();
}
document.querySelectorAll('[data-theme-btn]').forEach(b=>b.onclick=cycleTheme);
matchMedia('(prefers-color-scheme: light)').addEventListener('change',applyThemePref);
applyThemePref();

// ---------- text size ----------
// Every font size is a px token, and the IDE's embedded browser applies none of the IDE's font scaling
// — so metadata at 10–11px stayed 10–11px on a dense monitor. `--ui-scale` multiplies the --text-*
// tokens (explorer.css); A−/A+ in the footer step it and the choice is remembered per browser.
const UI_SCALES=[0.85,1,1.15,1.3,1.5];
function uiScale(){ let v=NaN; try{ v=parseFloat(localStorage.getItem('atlas-ui-scale')); }catch(e){} return UI_SCALES.indexOf(v)>=0?v:1; }
function applyUiScale(){
  const s=uiScale();
  document.documentElement.style.setProperty('--ui-scale', String(s));
  document.querySelectorAll('[data-ui-scale]').forEach(b=>{
    const i=UI_SCALES.indexOf(s), dir=b.dataset.uiScale;
    b.disabled = dir==='+' ? i>=UI_SCALES.length-1 : i<=0;
    const tip=(dir==='+'?'Larger text':'Smaller text')+' (now '+Math.round(s*100)+'%)';
    b.setAttribute('data-tip', tip); b.setAttribute('aria-label', tip);
  });
}
function stepUiScale(dir){
  const i=UI_SCALES.indexOf(uiScale()), j=Math.max(0, Math.min(UI_SCALES.length-1, i+(dir==='+'?1:-1)));
  try{ localStorage.setItem('atlas-ui-scale', String(UI_SCALES[j])); }catch(e){}
  applyUiScale();
}
document.querySelectorAll('[data-ui-scale]').forEach(b=>b.onclick=()=>stepUiScale(b.dataset.uiScale));
applyUiScale();

// ---------- hover tooltips ----------
// A DOM bubble for elements carrying [data-tip]. Native title= tooltips don't render in the embedded
// JCEF viewer (off-screen rendering, especially over Remote Dev), so we draw our own — it shows
// identically in the IDE and a plain browser. Every title= in the page (term hints, copy buttons,
// badge explanations) is lifted into data-tip on first hover/focus: one code path serves them all,
// and nothing depends on the native tooltip the IDE never shows. Reads the attribute at hover time,
// so the dynamic link-filter / theme text is always current.
const _tip=document.createElement('div'); _tip.className='atlas-tip'; _tip.setAttribute('role','tooltip');
let _tipFor=null;
// Move a native title= into data-tip (once): the browser stops racing us with its own tooltip and
// the text keeps working where native tooltips don't. The text stays reachable for screen readers.
function liftTitle(el){
  if(el.hasAttribute('data-tip')) return el;
  const t=el.getAttribute('title'); if(!t) return el;
  el.setAttribute('data-tip', t); el.removeAttribute('title');
  if(!el.hasAttribute('aria-label')) el.setAttribute('aria-label', t);
  return el;
}
function tipTarget(t){ return t && t.closest ? t.closest('[data-tip],[title]') : null; }
function showTip(el){
  const t=el.getAttribute('data-tip'); if(!t){ hideTip(); return; }
  _tipFor=el; _tip.textContent=t;
  if(!_tip.parentNode) document.body.appendChild(_tip);
  const r=el.getBoundingClientRect(), tr=_tip.getBoundingClientRect();
  const left=Math.max(8, Math.min(r.left, window.innerWidth-tr.width-8));   // right-align onto screen
  let top=r.bottom+6;
  if(top+tr.height>window.innerHeight-8) top=r.top-tr.height-6;             // flip above if no room below
  _tip.style.left=left+'px'; _tip.style.top=Math.max(8,top)+'px';
  requestAnimationFrame(()=>_tip.classList.add('show'));
}
function hideTip(){
  clearTimeout(_tipT); _tipT=null;
  _tipFor=null; _tip.classList.remove('show'); if(_tip.parentNode) _tip.parentNode.removeChild(_tip);
}
// Hover tooltips wait — a bubble that appears the instant the cursor passes over something turns every
// mouse movement across a list into a flicker. Keyboard focus shows it immediately: there the tooltip
// is the answer to a deliberate question.
const TIP_DELAY=450;
let _tipT=null;
document.addEventListener('mouseover',e=>{
  let el=tipTarget(e.target);
  if(!el){ if(_tipFor||_tipT) hideTip(); return; }
  if(el===_tipFor) return;
  clearTimeout(_tipT);
  _tipT=setTimeout(()=>{ _tipT=null; showTip(liftTitle(el)); }, TIP_DELAY);
});
document.addEventListener('mouseout',e=>{
  const el=tipTarget(e.target);
  if(_tipT && el && !el.contains(e.relatedTarget)){ clearTimeout(_tipT); _tipT=null; }
  if(_tipFor && el===_tipFor && !_tipFor.contains(e.relatedTarget)) hideTip();
});
document.addEventListener('focusin',e=>{ let el=tipTarget(e.target); if(el){ el=liftTitle(el); showTip(el); } else if(_tipFor) hideTip(); });
window.addEventListener('scroll',()=>{ if(_tipFor||_tipT) hideTip(); }, true);

// ---------- sidebar resize (IntelliJ-style drag handle) ----------
// The expanded width lives in the --sidebar-w custom property; the collapsed
// "rail" is the .shell.rail class. Both are user-controllable via the drag
// handle (#sideresize) and remembered. atlas-sidebar='rail'|'wide' records an
// explicit choice; with none stored the rail auto-engages below 1100px, which
// preserves the old media-query behavior. localStorage is wrapped in try/catch
// for private-mode / file:// quirks, matching the theme prefs above.
const SB_MIN=180, SB_MAX=480, SB_DEF=240, SB_COLLAPSE=140, SB_RAIL=64;
const _sbNarrow=matchMedia('(max-width:1100px)');
function sbPref(){ try{ return localStorage.getItem('atlas-sidebar'); }catch(e){ return null; } }
function sbWidth(){
  let w=NaN; try{ w=parseInt(localStorage.getItem('atlas-sidebar-w'),10); }catch(e){}
  return (w>=SB_MIN&&w<=SB_MAX)?w:SB_DEF;
}
function sbClamp(v){ return Math.max(SB_MIN,Math.min(SB_MAX,v)); }
function applySidebar(){
  const shell=document.querySelector('.shell'); if(!shell) return;
  const pref=sbPref();                              // 'rail' | 'wide' | null(auto)
  const rail = pref ? pref==='rail' : _sbNarrow.matches;
  const w=sbWidth();
  shell.style.setProperty('--sidebar-w', w+'px');
  shell.classList.toggle('rail', rail);
  const h=document.getElementById('sideresize');
  if(h){
    h.setAttribute('aria-valuenow', rail?'0':String(w));
    h.setAttribute('aria-label', rail?'Sidebar collapsed — drag to expand'
                                      :'Sidebar width '+w+'px — drag to resize');
  }
}
function setSidebar(state, w){                       // persist an explicit choice, then re-apply
  try{ localStorage.setItem('atlas-sidebar', state); }catch(e){}
  if(w!=null){ try{ localStorage.setItem('atlas-sidebar-w', String(w)); }catch(e){} }
  applySidebar();
}
function wireSidebarResize(){
  const shell=document.querySelector('.shell');
  const h=document.getElementById('sideresize');
  if(!shell||!h) return;
  let startX=0, startW=0, dragging=false;
  h.addEventListener('pointerdown',e=>{
    dragging=true; startX=e.clientX;
    startW=shell.classList.contains('rail')?SB_RAIL:sbWidth();
    try{ h.setPointerCapture(e.pointerId); }catch(_){}
    shell.classList.add('dragging'); e.preventDefault();
  });
  h.addEventListener('pointermove',e=>{
    if(!dragging) return;
    const raw=startW+(e.clientX-startX);
    if(raw<SB_COLLAPSE){ shell.classList.add('rail'); }
    else{ shell.classList.remove('rail'); shell.style.setProperty('--sidebar-w', sbClamp(raw)+'px'); }
  });
  const end=e=>{
    if(!dragging) return; dragging=false;
    shell.classList.remove('dragging');
    try{ h.releasePointerCapture(e.pointerId); }catch(_){}
    if(shell.classList.contains('rail')) setSidebar('rail');
    else setSidebar('wide', parseInt(shell.style.getPropertyValue('--sidebar-w'),10)||SB_DEF);
  };
  h.addEventListener('pointerup',end);
  h.addEventListener('pointercancel',end);
  h.addEventListener('dblclick',()=>setSidebar('wide',SB_DEF));   // reset to default width
  h.addEventListener('keydown',e=>{
    if(e.key==='ArrowLeft'||e.key==='ArrowRight'){
      e.preventDefault();
      const base=shell.classList.contains('rail')?SB_MIN:sbWidth();
      setSidebar('wide', sbClamp(base+(e.key==='ArrowRight'?16:-16)));
    } else if(e.key==='Home'){ e.preventDefault(); setSidebar('wide',SB_DEF); }
  });
  // Re-evaluate the auto default on viewport crossings, but only while the
  // user has not made an explicit choice.
  _sbNarrow.addEventListener('change',()=>{ if(!sbPref()) applySidebar(); });
}

// The list/detail split. The sidebar has had a drag handle with a remembered width for a while; the
// browse split was a fixed 330px, which in a narrow IDE tool window let the list eat half the panel.
// Same contract as the sidebar handle: drag, ←/→ by 16px, Home resets, double-click resets.
const LW_MIN=200, LW_MAX=640, LW_DEF=330;
function lwClamp(v){ return Math.max(LW_MIN, Math.min(LW_MAX, v)); }
function listWidth(){ let w=NaN; try{ w=parseInt(localStorage.getItem('atlas-list-w'),10); }catch(e){} return (w>=LW_MIN&&w<=LW_MAX)?w:LW_DEF; }
function setListWidth(w){
  const v=lwClamp(w), vb=document.getElementById('view-browse');
  if(vb) vb.style.setProperty('--list-w', v+'px');
  try{ if(v===LW_DEF) localStorage.removeItem('atlas-list-w'); else localStorage.setItem('atlas-list-w', String(v)); }catch(e){}
}
function wireListResize(){
  const h=document.getElementById('listresize'), vb=document.getElementById('view-browse');
  if(!h||!vb) return;
  setListWidth(listWidth());
  let startX=0, startW=0, dragging=false;
  h.addEventListener('pointerdown',e=>{
    dragging=true; startX=e.clientX; startW=parseInt(vb.style.getPropertyValue('--list-w'),10)||LW_DEF;
    try{ h.setPointerCapture(e.pointerId); }catch(_){}
    e.preventDefault();
  });
  h.addEventListener('pointermove',e=>{ if(dragging) vb.style.setProperty('--list-w', lwClamp(startW+(e.clientX-startX))+'px'); });
  const end=e=>{
    if(!dragging) return; dragging=false;
    try{ h.releasePointerCapture(e.pointerId); }catch(_){}
    setListWidth(parseInt(vb.style.getPropertyValue('--list-w'),10)||LW_DEF);
  };
  h.addEventListener('pointerup',end);
  h.addEventListener('pointercancel',end);
  h.addEventListener('dblclick',()=>setListWidth(LW_DEF));
  h.addEventListener('keydown',e=>{
    if(e.key==='ArrowLeft'||e.key==='ArrowRight'){ e.preventDefault(); setListWidth(listWidth()+(e.key==='ArrowRight'?16:-16)); }
    else if(e.key==='Home'){ e.preventDefault(); setListWidth(LW_DEF); }
  });
}

// In rail mode the collapsed sidebar flies out on :hover/:focus-within. A mouse
// click on a nav item (or a footer button) leaves that element focused, so
// :focus-within stays true and the rail never collapses when the pointer leaves.
// Drop the focus after pointer-initiated clicks so the fly-out closes on mouse-out.
// Keyboard activation reports detail:0 (Enter/Space synthesize el.click()) and is
// left alone, so Tab users keep the fly-out until they move focus away themselves.
function wireRailAutoCollapse(){
  const shell=document.querySelector('.shell');
  const sidebar=document.getElementById('sidebar');
  if(!shell||!sidebar) return;
  sidebar.addEventListener('click',e=>{
    if(e.detail===0) return;                                    // keyboard-synthesized click
    if(!shell.classList.contains('rail')) return;               // only the collapsed rail flies out
    const a=document.activeElement;
    if(a&&a!==document.body&&sidebar.contains(a)) a.blur();      // release :focus-within → collapse on mouse-out
  });
}

// ---------- boot ----------
document.getElementById('proj').textContent=DATA.project;
indexFindings();
waiverReconcile();
computeInsights();
renderSidebar();
applySidebar();
wireSidebarResize();
wireListResize();
wireRailAutoCollapse();
wireSearchTrigger();
stampProvenance();
wirePaletteResize();
wireLinkFilter();
tabsRestore();                  // before route(): a permalink then ADDS to the restored set
window.addEventListener('hashchange',route);
route();
_tabsBooting=false;             // from here on, following a link moves the active tab instead
prewarmSearchIndex();           // after the first render: the first ⌘K query should not pay for the walk

// ---------- boot done: dismiss the loading overlay ----------
// The overlay (explorer.html #atlas-boot) covered the file read + this synchronous boot;
// fade it out now that the initial view is rendered, then remove it after the transition.
_booted=true;
const _boot=document.getElementById('atlas-boot');
if(_boot){ _boot.classList.add('boot--done'); setTimeout(()=>_boot.remove(),400); }
