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
const diags = DATA.diagnostics || [];
const cfns = DATA.customFunctions;
const cfnDiags = (cfns && cfns.diagnostics) || [];
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
  'rel:action-channel': ['Offered on channel', 'Where the action appears in the UI.'],
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
       {id:'external::missing',label:'Missing model refs',sec:'Checks',      color:color('external'), icon:'invalidExpr', match:n=>n.type==='external'&&n.data.missingModel},
       {id:'external::lib',  label:'External / library',  sec:'Other',       color:color('external'), icon:'external', match:n=>n.type==='external'&&!n.data.flowableApi&&!n.data.route&&!n.data.missingModel}
      ].forEach(c=>{ const count=byType.external.filter(c.match).length; if(count) cats.push(Object.assign({count}, c)); });
    } else {
      const m = TM[t]||[t,'Other'];
      cats.push({id:t,label:m[0],sec:m[1],color:color(t),icon:t,count:byType[t].length,match:n=>n.type===t});
    }
  });
  // a review list: forms that nothing links to (orphaned UI models worth pruning)
  const unusedForms = nodes.filter(isUnusedForm);
  if(unusedForms.length) cats.push({id:'unused-form', label:'Forms · unused', sec:'Checks',
    color:color('form'), icon:'form', count:unusedForms.length, match:isUnusedForm});
  // The two other "registered but never called" findings get review lists of their own, so the Checks
  // page's "open the list" lands on the 3 unused operations and not on all 40 (same rule as Findings.kt).
  const isUnusedOp = n => n.type==='serviceOperation' && !((n.data||{}).usedBy||[]).length;
  const unusedOps = nodes.filter(isUnusedOp);
  if(unusedOps.length) cats.push({id:'unused-op', label:'Service operations · unused', sec:'Checks',
    color:color('serviceOperation'), icon:'serviceOperation', count:unusedOps.length, match:isUnusedOp});
  const isUnusedFn = n => n.type==='customFunction' && !((n.data||{}).usedBy||[]).length;
  const unusedFns = nodes.filter(isUnusedFn);
  if(unusedFns.length) cats.push({id:'unused-fn', label:'Custom functions · unused', sec:'Checks',
    color:color('customFunction'), icon:'customFunction', count:unusedFns.length, match:isUnusedFn});
  // Review lists for flagged expressions/bindings. Structural syntax errors make an
  // expression *invalid*; catalog findings (unknown function/namespace — the catalog may
  // simply not know a project-registered function) only make it *suspect*.
  const isExprN = n => n.type==='expression'||n.type==='binding';
  const hasErr = n => isExprN(n) && (n.data.problems||[]).some(p=>p.severity==='error');
  const hasWarnOnly = n => isExprN(n) && (n.data.problems||[]).length && !(n.data.problems||[]).some(p=>p.severity==='error');
  const invalidExprs = nodes.filter(hasErr);
  if(invalidExprs.length) cats.push({id:'invalid-expr', label:'Invalid — syntax ⚠', sec:'Checks',
    color:color('invalidExpr'), icon:'invalidExpr', count:invalidExprs.length, match:hasErr});
  const suspectExprs = nodes.filter(hasWarnOnly);
  if(suspectExprs.length) cats.push({id:'suspect-expr', label:'Suspect — review', sec:'Checks',
    color:color('suspectExpr'), icon:'suspectExpr', count:suspectExprs.length, match:hasWarnOnly});
  // A changelog nobody references, or one superseded by a later revision, is a schema surprise waiting.
  const isChangelogIssue = n => n.type==='liquibase' &&
    ['orphan','superseded'].indexOf(((n.data||{}).authority||{}).status)>=0;
  const clIssues = nodes.filter(isChangelogIssue);
  if(clIssues.length) cats.push({id:'changelog-issue', label:'Changelogs · orphan / superseded', sec:'Checks',
    color:color('liquibase'), icon:'liquibase', count:clIssues.length, match:isChangelogIssue});
  // Variables whose only evidence is a bare identifier in a script — probably real, not provable.
  const isGuessedVar = n => n.type==='variable' && (n.data||{}).heuristic===true;
  const guessed = nodes.filter(isGuessedVar);
  if(guessed.length) cats.push({id:'guessed-var', label:'Variables · script guess ≈', sec:'Checks',
    color:color('variable'), icon:'variable', count:guessed.length, match:isGuessedVar});
  // Something writes them and nothing reads them. Kept beside the script-guess list so all three
  // variable reviews read as one family; the full report with the definition sites is #/variables.
  const isUnusedVar = n => n.type==='variable' && (n.data||{}).unread===true;
  const unusedVars = nodes.filter(isUnusedVar);
  if(unusedVars.length) cats.push({id:'unused-var', label:'Variables · never read', sec:'Checks',
    color:color('variable'), icon:'variable', count:unusedVars.length, match:isUnusedVar});
  const isUnreadInput = n => n.type==='variable' && ((n.data||{}).unreadIn||[]).length>0;
  const unreadInputs = nodes.filter(isUnreadInput);
  if(unreadInputs.length) cats.push({id:'unread-input', label:'Variables · unread call input', sec:'Checks',
    color:color('variable'), icon:'variable', count:unreadInputs.length, match:isUnreadInput});
  // Models with a script whose body (or scriptFormat) fails the structural syntax check.
  const scriptIssueModels = new Set(allScripts().filter(s=>(s.problems||[]).length).map(s=>s.model));
  if(scriptIssueModels.size) cats.push({id:'script-syntax', label:'Scripts · syntax ⚠', sec:'Checks',
    color:color('invalidExpr'), icon:'scripts', count:scriptIssueModels.size, match:n=>scriptIssueModels.has(n.id)});
  cats.sort((a,b)=> (SECTIONS.indexOf(a.sec)-SECTIONS.indexOf(b.sec)) || a.label.localeCompare(b.label));
  return cats;
}
const CATS = categories();

// ---------- insights (dashboard fuel) — one edge pass + one node pass at boot ----------
let INSIGHTS = null;
function computeInsights(){
  const indeg = new Map(), containsByApp = new Map(), openAppByApp = new Map(), entryPoints = [];
  edges.forEach(e=>{
    if(e.rel==='contains'){ containsByApp.set(e.s,(containsByApp.get(e.s)||0)+1); return; }
    const src = byId.get(e.s);
    if(src && src.type==='group'){
      if(e.rel==='open-app') openAppByApp.set(e.t,(openAppByApp.get(e.t)||0)+1);
      else if(e.rel==='start' && byId.get(e.t)) entryPoints.push({group:e.s, model:e.t});
      return;                                    // access edges don't count as "references"
    }
    if(byId.get(e.t)) indeg.set(e.t,(indeg.get(e.t)||0)+1);
  });
  const hotspots = [...indeg.entries()].filter(x=>x[1]>0 && byId.get(x[0]))
    .sort((a,b)=> b[1]-a[1] || byId.get(a[0]).label.localeCompare(byId.get(b[0]).label))
    .slice(0,10).map(x=>({id:x[0], count:x[1]}));
  // Denominators for the dashboard ("3 of 16 services have schema gaps"). The numerators are the
  // health counts, which come from :core — see below.
  const isExprN = n => n.type==='expression'||n.type==='binding';
  let totalExprs=0, totalForms=0, totalChangelogs=0, totalCovServices=0, totalOps=0, totalFns=0;
  // Variables Atlas could prove a direction for, and the ones it declined to judge. The first is the
  // denominator the unused-variable counts are quoted against; the second is the report's own caveat —
  // how many names it stayed quiet about, which is what makes the ones it does name trustworthy.
  let totalDirectedVars=0, silentVars=0;
  nodes.forEach(n=>{
    const d=n.data||{};
    if(isExprN(n)) totalExprs++;
    else if(n.type==='form') totalForms++;
    else if(n.type==='liquibase') totalChangelogs++;
    else if(n.type==='service'){ if((d.schemaCoverage||{}).counts) totalCovServices++; }
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
  // The health counts come precomputed from :core (Findings.kt), which now derives them for every
  // artifact — the Markdown reports, graph.json, the CLI status line and this page. They used to be
  // computed here only, which is why no text artifact could state a single one of them, and why
  // "script issues" meant "scripts carrying a finding" here but "findings" everywhere else.
  // The node passes above still feed the facets and the dashboard totals.
  const CHK = DATA.checks || {};
  const health = { parseIssues: CHK.parseIssues||0, invalidExpr: CHK.invalidExpr||0,
                   suspectExpr: CHK.suspectExpr||0, scriptIssues: CHK.scriptIssues||0,
                   unusedForms: CHK.unusedForms||0, changelogIssues: CHK.changelogIssues||0,
                   schemaGaps: CHK.schemaGaps||0, missingRefs: CHK.missingRefs||0,
                   guessedVars: CHK.guessedVars||0, unusedOps: CHK.unusedOps||0,
                   unusedFns: CHK.unusedFns||0,
                   unusedVars: CHK.unusedVars||0, unreadInputs: CHK.unreadInputs||0 };
  INSIGHTS = { indeg, hotspots, apps, entryPoints,
    totalExprs, totalForms, totalChangelogs, totalCovServices, totalOps, totalFns,
    totalDirectedVars, silentVars,
    totalScripts: scripts.length,
    health,
    // what the Checks tab counts in its badge: every open finding, in one number
    checksOpen: CHK.open || 0 };
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
function parseHash(){
  const raw = location.hash.slice(1);
  if(!raw || raw==='/overview') return {view:'overview'};
  if(raw==='/schema') return {view:'schema'};
  if(raw==='/scripts') return {view:'scripts'};
  if(raw==='/checks') return {view:'checks'};
  if(raw==='/variables') return {view:'variables'};
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
  const out={q:'', e:'', f:undefined, s:undefined};
  pairs.forEach(p=>{ const i=p.indexOf('='); if(i<0) return; const k=p.slice(0,i); if(k in out) out[k]=dec(p.slice(i+1)); });
  return out;
}
// Keep the URL's &f=/&s= in step with the list, without a history entry or a re-route (replaceState),
// so a reload or a copied link brings the filter and the sort back — the panel's context, not only its
// node. `&q=`/`&e=` travel the same way once a selection carries them.
function syncHashContext(){
  if(state.view!=='browse') return;
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
  document.getElementById('view-browse').hidden = v!=='browse';
}
let _navCount = 0;
function route(){
  closePalette();
  _navCount++;
  const r = parseHash();
  state.focus = r.q || '';
  state.focusEl = r.e || '';
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
function renderSidebar(){
  const nav = document.getElementById('nav'); nav.innerHTML='';
  const mkItem = (html, title) => {
    const el=document.createElement('div');
    el.className='side-item'; el.setAttribute('role','button'); el.tabIndex=0;
    // No tooltip on a menu entry: the label and count are right there, and a bubble popping up under
    // the cursor while you slide down the list is pure noise. In rail mode the sidebar flies out on
    // hover, so the label is never actually hidden. The text stays available to screen readers.
    el.setAttribute('aria-pressed','false'); el.setAttribute('aria-label', title); el.innerHTML=html;
    el.onkeydown=e=>{
      if(e.key==='Enter'||e.key===' '){ e.preventDefault(); el.click(); }
      else if(e.key==='ArrowDown'||e.key==='ArrowUp'){
        e.preventDefault();
        const items=[...nav.querySelectorAll('.side-item')];
        const i=items.indexOf(el)+(e.key==='ArrowDown'?1:-1);
        if(items[i]) items[i].focus();
      }
    };
    return el;
  };
  const ov = mkItem(typeIcon('overview',{color:'var(--accent)'})+'<span class="lbl">Overview</span>','Overview');
  ov.dataset.route='/overview';
  ov.onclick=()=>{ location.hash='/overview'; };
  nav.appendChild(ov);
  // A tab belongs to a section like any other list — "Script tasks" is an Integration thing, the
  // review reports belong under Checks. `pri` keeps a section's tabs above its drill-down lists.
  const items=[...CATS];
  const scriptCount=allScripts().length;
  if(scriptCount) items.push({route:'/scripts', label:'Script tasks', sec:'Integration', pri:0, icon:'scripts',
    color:color('process'), count:scriptCount,
    tip:'Script tasks ('+scriptCount+') — every script task, listener script and bot script'});
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
  let cur='';
  items.forEach(c=>{
    if(c.sec!==cur){ cur=c.sec; const h=document.createElement('div'); h.className='side-group'; h.textContent=cur; nav.appendChild(h); }
    const el = mkItem(typeIcon(c.icon,{color:c.color})+'<span class="lbl">'+esc(c.label)+'</span>'+
                      (c.count?'<span class="n">'+c.count+'</span>':''),
                      c.tip||(c.label+' ('+c.count+')'));
    if(c.route){ el.dataset.route=c.route; el.onclick=()=>{ location.hash=c.route; }; }
    else { el.dataset.cat=c.id; el.onclick=()=>{ location.hash='/browse/'+enc(c.id); }; }
    nav.appendChild(el);
  });
  // footer warning chip — routes to the dashboard and reveals the diagnostics list
  if(diags.length+cfnDiags.length){
    const chip=document.getElementById('diagchip');
    const n=diags.length+cfnDiags.length;
    chip.hidden=false;
    chip.innerHTML='⚠<span class="wtxt">&nbsp;'+n+' parse issue'+(n>1?'s':'')+'</span>';
    chip.setAttribute('aria-label',
      n+' parse issue'+(n>1?'s':'')+' — files the generator could not fully analyze');
    chip.onclick=()=>{
      _checkJump='chk-parse';
      if(state.view==='checks') renderChecks(); else location.hash='/checks';
    };
  }
}
function renderSidebarActive(){
  document.querySelectorAll('#nav .side-item').forEach(el=>{
    const on = el.dataset.route ? el.dataset.route==='/'+state.view
                                : (state.view==='browse' && state.cat===el.dataset.cat);
    el.classList.toggle('on', on);
    el.setAttribute('aria-pressed', on?'true':'false');
  });
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
  const st=DATA.stats||{}, H=INSIGHTS.health;
  let h='<div class="dash">';
  const suN=st.suspectEdges||0, dyN=st.dynamicEdges||0;
  const uncertain=(suN||dyN)?' · '+[suN?suN+' suspect':'',dyN?dyN+' dynamic':''].filter(Boolean).join(' + ')
    +' <span title="suspect = loose/cross-type match — dynamic = expression-valued reference">link'+((suN+dyN)>1?'s':'')+'</span>':'';
  h+='<div class="dash-title">'+esc(DATA.project)+'</div>'+
     '<div class="dash-sub">'+nodes.length+' nodes · '+edges.length+' links'+uncertain+' across the model &amp; code graph</div>';
  // inventory
  h+='<div class="seclabel">Inventory</div><div class="metrics">';
  [['Models',st.models,'published model files'],['Java classes',st.java,'scanned source classes'],
   ['REST endpoints',st.endpoints,'served by controllers'],['User groups',st.groups,'referenced in access rules']]
    .forEach(m=>{ h+='<div class="metric"><div class="mk">'+m[0]+'</div><div class="mv">'+(m[1]||0)+'</div><div class="ms">'+m[2]+'</div></div>'; });
  h+='</div>';
  // health — the same cards the Checks tab shows; the overview stays a summary and links there for the
  // findings themselves (one place to review, instead of two that drift apart)
  const cardsHtml=healthCardsHtml();
  if(cardsHtml){
    const open=INSIGHTS.checksOpen;
    h+='<div class="seclabel row">Health'+
       '<button class="dgbtn" data-route="/checks">'+
       (open?open+' finding'+(open>1?'s':'')+' to review ↗':'open Checks ↗')+'</button></div>'+cardsHtml;
  }
  // hotspots
  if(INSIGHTS.hotspots.length){
    h+='<div class="seclabel">Hotspots — most referenced</div><div class="dashrows">';
    INSIGHTS.hotspots.forEach(x=>{
      const n=byId.get(x.id);
      h+='<div class="dashrow" data-id="'+enc(x.id)+'" role="link" tabindex="0">'+
         nodeIcon(n)+
         '<span class="nm">'+esc(n.label)+'</span><span class="ty">'+esc(nodeKind(n))+'</span>'+
         '<span class="pill">'+x.count+' refs</span></div>';
    });
    h+='</div>';
  }
  // apps
  if(INSIGHTS.apps.length){
    h+='<div class="seclabel">Apps</div><div class="dashrows">';
    INSIGHTS.apps.forEach(a=>{
      const n=byId.get(a.id); if(!n) return;
      h+='<div class="dashrow" data-id="'+enc(a.id)+'" role="link" tabindex="0">'+
         typeIcon('app')+
         '<span class="nm">'+esc(n.label)+'</span>'+
         (a.groups?'<span class="ty">'+a.groups+' group'+(a.groups>1?'s':'')+' can open</span>':'')+
         '<span class="pill">'+a.models+' models</span></div>';
    });
    h+='</div>';
  }
  // entry points — who can start what
  if(INSIGHTS.entryPoints.length){
    const eps=INSIGHTS.entryPoints.slice(0,50);
    h+='<div class="seclabel">Entry points — who can start what</div><div class="dashrows">';
    eps.forEach(ep=>{
      h+='<div class="dashrow">'+nodeChip(ep.group)+'<span class="sep">can start</span>'+nodeChip(ep.model)+'</div>';
    });
    if(INSIGHTS.entryPoints.length>eps.length)
      h+='<div class="dashrow muted">+ '+(INSIGHTS.entryPoints.length-eps.length)+' more</div>';
    h+='</div>';
  }
  h+='</div>';
  v.innerHTML=h;
  wireNodeLinks(v, '[data-id]', {first:e=>{
    const jump=e.target.closest('[data-jump]');
    if(jump){ _checkJump=jump.dataset.jump; location.hash='/checks'; return true; }
    const rtEl=e.target.closest('[data-route]');
    if(rtEl){ location.hash=rtEl.dataset.route; return true; }
    const catEl=e.target.closest('[data-cat]');
    if(catEl){ location.hash='/browse/'+enc(catEl.dataset.cat); return true; }
  }});
}

// ---------- schema coverage: one renderer for the service detail AND the schema tab ----------
// `onlyGaps` filters the table to the problem rows (the schema tab's view of the world);
// `leadChipId` puts the owning service's chip first on the meta line.
function schemaCoverageHtml(sc, onlyGaps, leadChipId){
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
  if(badges) b+='<div class="covbadges">'+badges+'</div>';
  const rowCls={'no-service':'cov-bad','no-dataobject':'cov-warn','extra-service':'cov-info','ok':''};
  const miss='<span class="miss">✗ not mapped</span>';
  const rows=onlyGaps?(sc.rows||[]).filter(r=>r.status!=='ok'):(sc.rows||[]);
  if(rows.length){
    b+='<div class="covwrap"><table class="cov"><thead><tr>'+
       '<th>Liquibase column</th><th>Service mapping</th><th>Data object field</th></tr></thead><tbody>';
    rows.forEach(r=>{
      const lbCell = r.inLiquibase
        ? '<span>'+esc(r.sql)+'</span>'+(r.sqlType?' <span class="muted">'+esc(r.sqlType)+'</span>':'')
        : '<span class="miss">— not in changelog</span>';
      const svCell = r.inService
        ? '<span>'+esc(r.service||r.serviceCol||'')+'</span>'+
          (r.serviceCol&&looseCol(r.serviceCol)!==looseCol(r.service||'')?' <span class="muted">'+esc(r.serviceCol)+'</span>':'')+
          (r.serviceType?' <span class="muted">'+esc(r.serviceType)+'</span>':'')
        : miss;
      const doCell = (r.dataObjects&&r.dataObjects.length)
        ? r.dataObjects.map(x=>'<span>'+esc(x.field)+'</span>'+
            ((sc.dataObjects||[]).length>1?' <span class="muted">'+esc(x.do)+'</span>':'')).join(', ')
        : (r.inLiquibase||r.inService?miss:'');
      b+='<tr class="'+(rowCls[r.status]||'')+'"><td>'+lbCell+'</td><td>'+svCell+'</td><td>'+doCell+'</td></tr>';
    });
    b+='</tbody></table></div>';
  }
  if(onlyGaps&&ct.ok) b+='<div class="muted" style="font-size:var(--text-xs);margin:var(--space-1) 0 0">+ '+ct.ok+' column'+(ct.ok>1?'s':'')+' mapped through cleanly — full table on the service page</div>';
  return b;
}

// ---------- schema-gaps tab (#/schema) ----------
// The dashboard's "Schema gaps" number, unfolded: every service with coverage data, its problem rows
// front and center, fully-mapped services collapsed to a chip row at the bottom.
function renderSchema(){
  const v=document.getElementById('view-schema');
  const svcs=nodes.filter(n=>n.type==='service'&&(n.data||{}).schemaCoverage&&((n.data.schemaCoverage.rows||[]).length))
    .map(n=>{ const c=n.data.schemaCoverage.counts||{};
      return {n, sc:n.data.schemaCoverage, gaps:(c.noService||0)+(c.noDataObject||0), extra:c.extra||0}; })
    .sort((a,b)=> (b.gaps+b.extra)-(a.gaps+a.extra) || a.n.label.localeCompare(b.n.label));
  const dirty=svcs.filter(s=>s.gaps||s.extra), clean=svcs.filter(s=>!s.gaps&&!s.extra);
  const total=svcs.reduce((a,s)=>a+s.gaps,0);
  let h='<div class="dash">';
  h+='<div class="dash-title">Schema gaps</div>'+
     '<div class="dash-sub">Liquibase → Service → Data object — '+
     (svcs.length===0?'no service declares schema coverage data'
      :total?total+' column'+(total>1?'s':'')+' not mapped through, in '+dirty.length+' of '+svcs.length+' service'+(svcs.length>1?'s':'')
      :'every column of all '+svcs.length+' service'+(svcs.length>1?'s':'')+' maps through cleanly')+'</div>';
  if(!svcs.length){
    h+='<div class="estate"><div class="estate-ic" aria-hidden="true">▦</div>'+
       '<div class="et">Nothing to check</div>'+
       '<div class="eh">No service in this project references a Liquibase changelog, so there is no schema to compare against.</div></div>';
  }
  dirty.forEach(s=>{
    h+='<div class="seclabel">'+esc(s.n.label)+(s.sc.table?' — <span class="mono">'+esc(s.sc.table)+'</span>':'')+'</div>'+
       '<div class="schemasvc">'+schemaCoverageHtml(s.sc, true, s.n.id)+'</div>';
  });
  if(clean.length){
    h+='<div class="seclabel">Fully mapped ('+clean.length+')</div><div class="nodechips">'+
       clean.map(s=>nodeChip(s.n.id)).join('')+'</div>';
  }
  h+='</div>';
  v.innerHTML=h;
  wireNodeLinks(v, '[data-id]');
}

// ---------- checks view (#/checks) ----------
// Everything Atlas cannot answer for you, on one page: parse issues, flagged expressions, schema gaps,
// models nothing references, references to models that do not exist, and the variables only a script
// guess supports. The sidebar's Checks section holds this tab plus the drill-down list per finding.
const CHECK_CARDS = [
  {k:'parseIssues', label:'Parse issues', bad:true, jump:'chk-parse',
   sub:c=>c?'files the analyzer could not fully read':'all files analyzed cleanly', show:()=>true},
  {k:'invalidExpr', label:'Invalid expressions', bad:true, cat:'invalid-expr', jump:'chk-invalid',
   sub:c=>c?'syntax errors in ${ } / {{ }}':'no syntax errors', show:()=>INSIGHTS.totalExprs>0},
  {k:'suspectExpr', label:'Suspect expressions', cat:'suspect-expr', jump:'chk-suspect',
   sub:c=>c?'flagged for review by the catalog':'nothing flagged', show:()=>INSIGHTS.totalExprs>0},
  {k:'scriptIssues', label:'Script syntax', bad:true, cat:'script-syntax', jump:'chk-scripts',
   sub:c=>c?'syntax & binding findings in script bodies':'all scripts scan clean', show:()=>INSIGHTS.totalScripts>0},
  {k:'schemaGaps', label:'Schema gaps', bad:true, route:'/schema', jump:'chk-schema',
   sub:c=>c?'columns not mapped through Liquibase → service → data object':'all columns mapped through',
   show:()=>INSIGHTS.totalCovServices>0},
  {k:'missingRefs', label:'Missing model refs', bad:true, cat:'external::missing', jump:'chk-missing',
   sub:c=>c?'a key is referenced but no model defines it':'every referenced key resolves', show:()=>true},
  {k:'unusedForms', label:'Unused forms', cat:'unused-form', jump:'chk-unusedforms',
   sub:c=>c?'no model links to them':'every form is referenced', show:()=>INSIGHTS.totalForms>0},
  {k:'changelogIssues', label:'Changelog issues', cat:'changelog-issue', jump:'chk-changelogs',
   sub:c=>c?'orphan or superseded changelogs':'all changelogs are authoritative',
   show:()=>INSIGHTS.totalChangelogs>0},
  {k:'guessedVars', label:'Variables · script guess', cat:'guessed-var', jump:'chk-guessed',
   sub:c=>c?'only a bare identifier in a script names them':'every variable is declared somewhere',
   show:()=>true},
  {k:'unusedOps', label:'Unused operations', cat:'unused-op', jump:'chk-unusedops',
   sub:c=>c?c+' of '+INSIGHTS.totalOps+' operations are never called from a model':'every operation is used',
   show:()=>INSIGHTS.totalOps>0},
  {k:'unusedFns', label:'Unused custom functions', cat:'unused-fn', jump:'chk-unusedfns',
   sub:c=>c?c+' of '+INSIGHTS.totalFns+' functions are never called':'every function is used',
   show:()=>INSIGHTS.totalFns>0},
  {k:'unusedVars', label:'Variables · never read', route:'/variables', jump:'chk-unusedvars',
   sub:c=>c?c+' of '+INSIGHTS.totalDirectedVars+' variables are written but nothing reads them'
          :'every variable that is written is read somewhere',
   show:()=>INSIGHTS.totalDirectedVars>0},
  {k:'unreadInputs', label:'Variables · unread call input', route:'/variables', jump:'chk-unreadinputs',
   sub:c=>c?'mapped into a called model that never reads them':'every mapped input is read by its callee',
   show:()=>INSIGHTS.totalDirectedVars>0},
];
/** The health grid. [keys] narrows it to a subset, so a page can show only the cards it is about. */
function healthCardsHtml(keys){
  const H=INSIGHTS.health;
  const cards=CHECK_CARDS.filter(c=>(!keys||keys.indexOf(c.k)>=0)&&c.show());
  if(!cards.length) return '';
  return '<div class="health">'+cards.map(c=>{
    const n=H[c.k], tone=n===0?'ok':(c.bad?'bad':'warn');
    const attrs=n>0?' data-jump="'+c.jump+'" role="button" tabindex="0"':'';
    return '<div class="hcard tone-'+tone+(n>0?' click':'')+'"'+attrs+'>'+
      '<div class="mk">'+esc(c.label)+'</div><div class="mv">'+n+'</div>'+
      '<div class="ms">'+esc(c.sub(n))+'</div></div>';
  }).join('')+'</div>';
}
/** One finding's section: its heading, the count, and — when the finding has a browse list of its own —
 *  the button into it. Nothing at all when the count is zero, so a page lists only what it found. */
function findingBlock(id,title,count,body,cat){
  if(!count) return '';
  const list=cat&&CATS.some(x=>x.id===cat)
    ? '<button class="dgbtn" data-cat="'+esc(cat)+'">open the list ↗</button>' : '';
  return '<div class="seclabel row" id="'+id+'">'+
    esc(title)+' <span class="muted">'+count+'</span>'+list+'</div>'+body;
}
/** The click hook a report page hands [wireNodeLinks]: `data-jump` scrolls to a section of this page,
 *  `data-cat` opens a finding's browse list, `data-route` switches tab. Returning true says the click
 *  was a navigation of its own and must not also be read as a node link. */
function reportNav(e){
  const jump=e.target.closest('[data-jump]');
  if(jump){ const t=document.getElementById(jump.dataset.jump);
    if(t) t.scrollIntoView({block:'start'}); return true; }
  const cat=e.target.closest('[data-cat]');
  if(cat){ location.hash='/browse/'+enc(cat.dataset.cat); return true; }
  const route=e.target.closest('[data-route]');
  if(route){ location.hash=route.dataset.route; return true; }
}
function renderChecks(){
  const v=document.getElementById('view-checks');
  const H=INSIGHTS.health, st=DATA.stats||{};
  const open=INSIGHTS.checksOpen;
  let h='<div class="dash">';
  h+='<div class="dash-title">Checks</div>'+
     '<div class="dash-sub">'+(open
       ? open+' finding'+(open>1?'s':'')+' worth a look — none of them is automatically a bug, each one is a '+
         'question Atlas cannot answer on its own'
       : 'nothing flagged — no parse issues, no broken expressions, nothing unused or unproven')+'</div>';
  h+=healthCardsHtml();
  // one block per finding, with the actual items rather than only a number
  const chips=list=>'<div class="nodechips">'+list.map(n=>nodeChip(n.id)).join('')+'</div>';
  // parse issues — the analyzer's own honesty about what it could not read
  if(diags.length+cfnDiags.length){
    h+=findingBlock('chk-parse','Parse issues', diags.length+cfnDiags.length,
      '<div class="dashrows">'+
      diags.map(d=>'<div class="dp-row"><span class="dp-kind">'+esc(d.kind)+'</span>'+
        '<span class="dp-path mono">'+esc(d.path)+'</span><span class="dp-msg">'+esc(d.message)+'</span></div>').join('')+
      cfnDiags.map(m=>'<div class="dp-row"><span class="dp-kind">custom-fn</span>'+
        '<span class="dp-msg">'+esc(m)+'</span></div>').join('')+'</div>');
  }
  // flagged expressions: the message and who uses them, so the fix is one click away
  const exprRows=list=>'<div class="dashrows">'+list.slice(0,60).map(n=>{
    const pr=(n.data||{}).problems||[];
    return '<div class="dashrow" style="align-items:flex-start;flex-wrap:wrap">'+
      '<span class="nm mono" data-id="'+enc(n.id)+'" role="link" tabindex="0" style="flex:1;min-width:200px">'+
        esc(n.label)+'</span>'+
      '<span class="ty">'+esc(pr.map(p=>p.message).join(' · '))+'</span>'+
      ((n.data||{}).usedBy||[]).slice(0,4).map(id=>byId.get(id)?nodeChip(id):'').join('')+
      '</div>';
  }).join('')+(list.length>60?'<div class="dashrow muted">+ '+(list.length-60)+' more — open the list</div>':'')+'</div>';
  const byCat=id=>{ const c=CATS.find(x=>x.id===id); return c?nodes.filter(c.match):[]; };
  h+=findingBlock('chk-invalid','Invalid expressions — syntax', H.invalidExpr, exprRows(byCat('invalid-expr')), 'invalid-expr');
  h+=findingBlock('chk-suspect','Suspect expressions — review', H.suspectExpr, exprRows(byCat('suspect-expr')), 'suspect-expr');
  // script syntax findings: model, element, language, then each finding with its line and code
  if(H.scriptIssues){
    const rows=allScripts().filter(s=>(s.problems||[]).length);
    h+=findingBlock('chk-scripts','Script syntax findings', H.scriptIssues,
      '<div class="dashrows">'+rows.map(s=>{
        const kind=scriptKindLabel(s);
        const title=s.elName||s.el||(s.group==='bot'?s.modelLabel:kind);
        return '<div class="dashrow" style="align-items:flex-start;flex-wrap:wrap">'+
          nodeChip(s.model)+
          '<span class="nm mono" style="min-width:140px">'+esc(title)+'</span>'+
          '<span class="pt">'+esc(kind)+'</span>'+
          (s.lang?'<span class="pt">'+esc(s.lang)+'</span>':'')+
          '<span class="ty" style="flex-basis:100%;display:flex;flex-direction:column;gap:2px">'+
            s.problems.map(p=>'<span><span style="color:var(--'+(p.severity==='error'?'bad':'warn')+'-text)">'+
              esc(p.severity)+'</span> '+esc(p.message)+
              (p.line?' <span class="muted">· line '+p.line+'</span>':'')+
              (p.snippet?' <span class="mono muted">'+esc(p.snippet)+'</span>':'')+'</span>').join('')+
          '</span></div>';
      }).join('')+
      '<div class="dashrow"><button class="dgbtn" data-route="/scripts">open the scripts tab ↗</button></div>'+
      '</div>', 'script-syntax');
  }
  // schema gaps: the per-service summary; the full column table lives in its own tab
  if(H.schemaGaps){
    const svcs=nodes.filter(n=>n.type==='service'&&((n.data||{}).schemaCoverage||{}).counts)
      .map(n=>{ const c=n.data.schemaCoverage.counts; return {n, gaps:(c.noService||0)+(c.noDataObject||0)}; })
      .filter(x=>x.gaps).sort((a,b)=>b.gaps-a.gaps);
    h+=findingBlock('chk-schema','Schema gaps', H.schemaGaps,
      '<div class="dashrows">'+svcs.map(x=>'<div class="dashrow">'+nodeChip(x.n.id)+
        '<span class="ty">'+x.gaps+' column'+(x.gaps>1?'s':'')+' not mapped through</span></div>').join('')+
      '<div class="dashrow"><button class="dgbtn" data-route="/schema">open the full report ↗</button></div></div>');
  }
  h+=findingBlock('chk-missing','Missing model references', H.missingRefs, chips(byCat('external::missing')), 'external::missing');
  h+=findingBlock('chk-unusedforms','Unused forms', H.unusedForms, chips(byCat('unused-form')), 'unused-form');
  h+=findingBlock('chk-changelogs','Changelogs · orphan / superseded', H.changelogIssues,
    chips(byCat('changelog-issue')), 'changelog-issue');
  h+=findingBlock('chk-guessed','Variables · only a script guess ≈', H.guessedVars, chips(byCat('guessed-var')), 'guessed-var');
  // The two unused-variable checks summarise as chips here; the full report names the write to delete.
  h+=findingBlock('chk-unusedvars','Variables · written, never read', H.unusedVars,
    '<div class="dashrows"><div class="dashrow" style="display:block">'+chips(byCat('unused-var'))+'</div>'+
    '<div class="dashrow"><button class="dgbtn" data-route="/variables">open the full report ↗</button></div>'+
    '</div>', 'unused-var');
  h+=findingBlock('chk-unreadinputs','Variables · mapped into a model that never reads them', H.unreadInputs,
    '<div class="dashrows"><div class="dashrow" style="display:block">'+chips(byCat('unread-input'))+'</div>'+
    '<div class="dashrow"><button class="dgbtn" data-route="/variables">open the full report ↗</button></div>'+
    '</div>', 'unread-input');
  h+=findingBlock('chk-unusedops','Unused service operations', H.unusedOps,
    chips(nodes.filter(n=>n.type==='serviceOperation'&&!((n.data||{}).usedBy||[]).length)), 'unused-op');
  h+=findingBlock('chk-unusedfns','Unused custom functions', H.unusedFns,
    chips(nodes.filter(n=>n.type==='customFunction'&&!((n.data||{}).usedBy||[]).length)), 'unused-fn');
  // uncertain edges are a property of the graph, not of one node — say so once
  const suN=st.suspectEdges||0, dyN=st.dynamicEdges||0;
  if(suN+dyN){
    h+='<div class="seclabel">Uncertain links <span class="muted">'+(suN+dyN)+'</span></div>'+
       '<div class="dashrows"><div class="dashrow muted">'+
       (suN?suN+' suspect (≈ resolved by a loose or cross-type match)':'')+
       (suN&&dyN?' · ':'')+(dyN?dyN+' dynamic (ƒ expression-valued reference)':'')+
       ' — the ≈ button in the toolbar hides them everywhere.</div></div>';
  }
  if(!open) h+='<div class="estate"><div class="estate-ic" aria-hidden="true">✓</div>'+
    '<div class="et">Nothing to check</div>'+
    '<div class="eh">No parse issue, no flagged expression, no unused or unresolved model.</div></div>';
  h+='</div>';
  v.innerHTML=h;
  wireNodeLinks(v, '[data-id]', {first:reportNav});
  // arrived from a health card or the parse-issue chip: land on the block it asked for
  if(_checkJump){
    const target=document.getElementById(_checkJump);
    _checkJump=null;
    if(target) requestAnimationFrame(()=>target.scrollIntoView({block:'start'}));
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
function varRowHtml(n, opts){
  const d=n.data||{};
  const writes=d.writes||[];
  // The models a write happens in, deduped — a variable written by three script tasks of one process
  // should say that process once.
  const models=[...new Set(writes.map(w=>w.model))];
  const jumps=writes.filter(w=>w.element).slice(0,4)
    .map(w=>elJumpHtml(w.model, w.element, w.elementName||w.element, 'Open this element in its model')).join('');
  const vias=[...new Set(writes.map(w=>w.via))];
  const hay=[n.label, vias.map(x=>term('via',x).label).join(' '),
    models.map(m=>(byId.get(m)||{}).label||m).join(' '),
    (d.unreadIn||[]).map(m=>(byId.get(m)||{}).label||m).join(' ')].join(' ').toLowerCase();
  return '<div class="dashrow varrow" data-varrow data-via="'+esc(vias[0]||'')+'"'+
    ' data-hay="'+esc(hay)+'">'+
    '<span class="nm mono" data-id="'+enc(n.id)+'" role="link" tabindex="0">'+esc(n.label)+'</span>'+
    '<span class="varvias">'+writes.map(w=>varSiteLabel(w, n.label)).join('')+'</span>'+
    '<span class="nodechips">'+models.map(m=>nodeChip(m)).join('')+
      (opts&&opts.callee ? '<span class="varnote">never read in</span>'+
        (d.unreadIn||[]).map(m=>nodeChip(m)).join('') : '')+'</span>'+
    jumps+
    '<span class="vcount"><span class="vw">'+(d.writeCount||0)+' written</span> · '+
      '<span class="vr">'+(d.readCount||0)+' read</span></span>'+
    '</div>';
}

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

  let h='<div class="dash">';
  h+='<div class="dash-title">Unused variables</div>'+
     '<div class="dash-sub">'+(open
       ? open+' of '+total+' variable'+(total>1?'s':'')+' worth a look — something writes them and '+
         'nothing Atlas can see reads them back'
       : 'nothing flagged — every variable that is written is read somewhere in these models')+'</div>';
  // Only the two checks this page has blocks for. The script-guess card belongs to the Checks tab: its
  // `jump` names a block that does not exist here, so showing it would be a card that does nothing.
  h+=healthCardsHtml(['unusedVars','unreadInputs']);

  if(open){
    // one filter row over both blocks: the write construct, and free text over names and models
    const flagged=unread.concat(unreadIn);
    // A chip selects on the row's *first* write construct — the one varRowHtml puts in `data-via` — so
    // that is what its count has to be, or the number would promise rows the filter does not show.
    const perVia=new Map();
    flagged.forEach(n=>{ const first=(((n.data||{}).writes||[])[0]||{}).via;
      if(first) perVia.set(first,(perVia.get(first)||0)+1); });
    const vias=[...new Set(flagged.flatMap(n=>((n.data||{}).writes||[]).map(w=>w.via)))];
    const chip=(id,label,n)=>'<button class="pchip'+(id==='all'?' on':'')+'" data-via="'+esc(id)+'">'+
      esc(label)+'<span class="pchipn">'+n+'</span></button>';
    h+='<div class="pbar"><input class="pf" type="search" placeholder="filter variables — name, model…" '+
       'aria-label="Filter unused variables">'+
       chip('all','All',open)+
       vias.filter(x=>perVia.get(x)).map(x=>chip(x, term('via',x).label, perVia.get(x))).join('')+
       '<span class="pcount"></span></div>';
  }
  h+=findingBlock('chk-unusedvars','Written, never read', unread.length,
    '<div class="dashrows">'+unread.map(n=>varRowHtml(n)).join('')+'</div>');
  h+=findingBlock('chk-unreadinputs','Mapped into a model that never reads it', unreadIn.length,
    '<div class="dashrows">'+unreadIn.map(n=>varRowHtml(n,{callee:true})).join('')+'</div>');
  if(!open){
    h+='<div class="estate"><div class="estate-ic" aria-hidden="true">✓</div>'+
       '<div class="et">Nothing written and forgotten</div>'+
       '<div class="eh">Every variable a model writes is read somewhere — by an expression, a script, '+
       'a form field, a decision or a called model.</div></div>';
  }
  h+=findingBlock('chk-declaredvars','Declared — readers live outside the models', declared.length,
    '<div class="dashrows">'+declared.map(n=>
      '<div class="dashrow"><span class="nm mono" data-id="'+enc(n.id)+'" role="link" tabindex="0">'+
      esc(n.label)+'</span><span class="nodechips">'+
      ((n.data||{}).usedBy||[]).map(m=>nodeChip(m)).join('')+'</span></div>').join('')+
    '</div><div class="muted" style="padding:var(--space-2) 0">An app variable, a data-object column or '+
    'an extracted variable is read by the Work UI, a query or a dashboard — none of which Atlas parses. '+
    'They are listed here rather than reported, because "unused" would be a guess.</div>');

  // What this page cannot know. The count of names Atlas declined to judge is the honest denominator of
  // everything above it, and the reason the rows above can be trusted.
  h+='<div class="seclabel" id="chk-varcaveat">What Atlas cannot see</div>'+
     '<div class="dashrows"><div class="dashrow" style="display:block">'+
     '<div class="muted">Atlas stayed silent about <strong>'+silent+'</strong> further variable'+
     (silent===1?'':'s')+' it would otherwise have listed, because it saw one of these:</div>'+
     '<ul class="varwhy">'+(DATA.silenceRules||[]).map(r=>'<li>'+esc(r)+'</li>').join('')+'</ul>'+
     '<div class="muted">Query, dashboard and master-data models are not analysed for variable '+
     'references, and the Work UI or any REST client is outside this project. A variable listed above is '+
     'one that nothing <em>in these models</em> reads — not proof that nothing anywhere does.</div>'+
     '</div></div>';
  h+='</div>';
  v.innerHTML=h;

  const pf=v.querySelector('.pf'), count=v.querySelector('.pcount');
  if(pf){
    const chips=[...v.querySelectorAll('.pchip[data-via]')];
    const apply=()=>{
      const q=(pf.value||'').trim().toLowerCase();
      const via=(chips.find(c=>c.classList.contains('on'))||{dataset:{}}).dataset.via||'all';
      let shown=0;
      v.querySelectorAll('[data-varrow]').forEach(r=>{
        const on=(via==='all'||r.dataset.via===via)&&(!q||(r.dataset.hay||'').indexOf(q)>=0);
        r.hidden=!on; if(on) shown++;
      });
      count.textContent=(q||via!=='all')?shown+' of '+open:'';
    };
    pf.oninput=debounce(apply,120);
    chips.forEach(c=>c.onclick=()=>{ chips.forEach(x=>x.classList.toggle('on', x===c)); apply(); });
  }
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
  return '<span class="pt" style="color:var(--'+tone+'-text)">⚠ '+pr.length+'</span>';
}
/** The findings of one script, as rows: severity, message, line and the offending source line. */
function scriptProblemsHtml(problems){
  const pr=problems||[];
  if(!pr.length) return '';
  return '<div style="display:flex;flex-direction:column;gap:2px;padding:4px 10px 0">'+
    pr.map(p=>'<div><span style="color:var(--'+(p.severity==='error'?'bad':'warn')+'-text)">'+
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
  let h='<div class="dash">';
  h+='<div class="dash-title">Script tasks</div>'+
     '<div class="dash-sub">'+(all.length
       ? all.length+' script'+(all.length>1?'s':'')+' in '+models.length+' model'+(models.length>1?'s':'')+
         ' · '+totalLines+' line'+(totalLines>1?'s':'')+
         (withIssues?' · <span style="color:var(--bad-text)">⚠ '+withIssues+' with syntax findings</span>':'')+
         ' — script tasks, listener scripts and bot scripts, with the variables each one touches'
       : 'no model in this project carries a script')+'</div>';
  if(!all.length){
    h+='<div class="estate"><div class="estate-ic" aria-hidden="true">{ }</div>'+
       '<div class="et">No script tasks</div>'+
       '<div class="eh">Nothing to show — no script task, listener script or bot script was found.</div></div>';
  } else {
    // chips narrow by kind (same single-select pattern as the parameter sections), the text box searches
    // names, languages and the code itself
    const chip=(id,label,n)=>'<button class="pchip'+(id==='all'?' on':'')+'" data-group="'+id+'">'+
      esc(label)+'<span class="pchipn">'+n+'</span></button>';
    h+='<div class="pbar"><input class="pf" type="search" placeholder="filter scripts — name, language, code…" '+
       'aria-label="Filter script tasks">'+
       chip('all','All',all.length)+
       SCRIPT_GROUPS.filter(g=>all.some(s=>s.group===g.id))
         .map(g=>chip(g.id,g.label,all.filter(s=>s.group===g.id).length)).join('')+
       '<button class="pchip" id="scriptsall"></button><span class="pcount"></span></div>';
    models.forEach(mid=>{
      const rows=byModel.get(mid);
      h+='<div class="seclabel row">'+
         nodeChip(mid)+'<span class="muted">'+rows.length+' script'+(rows.length>1?'s':'')+'</span></div>';
      h+=rows.map(s=>{
        const vars=varIdx.get(s.model+'|'+(s.el==null?'':s.el))||[];
        const chips=vars.map(x=>'<span class="'+(x.api?'':'muted ')+'">'+
          vlink('variable:'+x.name, (x.api?'':'≈ ')+x.name)+'</span>').join(' ');
        // a bot script IS its model, and a model-level listener has only its kind to go by
        const kind=scriptKindLabel(s);
        const title=s.elName||s.el||(s.group==='bot'?s.modelLabel:kind);
        const jump=elJumpHtml(s.model, s.el, 'in model', 'Open this element in its model');
        const hay=[title, kind, s.lang||'', s.el||'', (byId.get(mid)||{}).label||'', s.body||'',
          (s.problems||[]).map(p=>p.message).join(' ')].join(' ').toLowerCase();
        // a handful of scripts: show the code straight away; a big project starts collapsed
        return '<details class="op" data-scriptrow data-group="'+esc(s.group)+'"'+
          (all.length<=6||(s.problems||[]).length?' open':'')+' data-hay="'+esc(hay)+'">'+
          '<summary><span class="opname">'+esc(title)+'</span>'+
          (s.el&&s.el!==title?'<span class="opid">'+esc(String(s.el))+'</span>':'')+
          '<span class="pt">'+esc(kind)+'</span>'+
          (s.lang?'<span class="pt">'+esc(s.lang)+'</span>':'')+
          '<span class="pt">'+lines(s)+' line'+(lines(s)>1?'s':'')+'</span>'+
          scriptIssueBadge(s.problems)+
          (s.out?'<span class="pd" style="color:var(--ok-text)">out</span> <span class="mono">'+
            paramSide(s.out)+'</span>':'')+
          (chips?'<span style="flex:1;display:flex;gap:6px;flex-wrap:wrap;min-width:0">'+chips+'</span>':'')+
          jump+'</summary>'+
          (s.doc?'<div class="muted" style="padding:4px 10px 0">'+esc(s.doc)+'</div>':'')+
          scriptProblemsHtml(s.problems)+
          codeBoxHtml(s.body, s.lang, s.problems)+'</details>';
      }).join('');
    });
  }
  h+='</div>';
  v.innerHTML=h;
  // kind chips + one text filter over every row: model, element, language and the code itself
  const pf=v.querySelector('.pf'), count=v.querySelector('.pcount');
  if(pf){
    const chips=[...v.querySelectorAll('.pchip[data-group]')];
    const apply=()=>{
      const q=(pf.value||'').trim().toLowerCase();
      const group=(chips.find(c=>c.classList.contains('on'))||{dataset:{}}).dataset.group||'all';
      let shown=0;
      v.querySelectorAll('[data-scriptrow]').forEach(r=>{
        const on=(group==='all'||r.dataset.group===group) && (!q||(r.dataset.hay||'').indexOf(q)>=0);
        r.hidden=!on; if(on) shown++;
        if(q&&on) r.open=true;
      });
      // hide a model heading whose scripts are all filtered out
      v.querySelectorAll('.seclabel').forEach(lab=>{
        let any=false;
        for(let e=lab.nextElementSibling; e&&!e.classList.contains('seclabel'); e=e.nextElementSibling){
          if(e.hasAttribute('data-scriptrow')&&!e.hidden) any=true;
        }
        lab.hidden=!any;
      });
      count.textContent=(q||group!=='all')?shown+' of '+all.length:'';
      syncAll();
    };
    pf.oninput=debounce(apply,120);
    chips.forEach(c=>c.onclick=()=>{ chips.forEach(x=>x.classList.toggle('on', x===c)); apply(); });
    // one control for every body at once — reading a project's scripts top to bottom is the point of
    // this view, and clicking 40 triangles is not
    const all2=()=>[...v.querySelectorAll('[data-scriptrow]')].filter(r=>!r.hidden);
    const toggle=v.querySelector('#scriptsall');
    const syncAll=()=>{ const rows=all2();
      toggle.textContent=(rows.length&&rows.every(r=>r.open))?'⇕ collapse all':'⇕ expand all'; };
    toggle.onclick=()=>{ const rows=all2(), open=!rows.every(r=>r.open);
      rows.forEach(r=>{ r.open=open; }); syncAll(); };
    v.querySelectorAll('[data-scriptrow]').forEach(r=>r.addEventListener('toggle',syncAll));
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
      '<div class="meta"><div class="nm">'+hlHtml(n.label, parsed)+authBadge(n)+
      '</div><div class="sub" title="'+esc(sub)+'">'+hlHtml(sub, parsed)+'</div></div>'+
      (rn?'<span class="refn" title="referenced by '+rn+' node'+(rn>1?'s':'')+'">'+rn+'</span>':'')+
      '<span class="ck" aria-hidden="true">✓</span>';
    // ⌘/Ctrl+click toggles and Shift+click extends — the list-selection convention, not the
    // browser's "open in new tab" one (middle-click and ⌘/Ctrl+Enter cover that).
    el.onclick=e=>{
      if(modKey(e)){
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
// you walk the graph. Everything defaults to closed except the diagram — see DEFAULT_OPEN_SECTIONS.
const SECT_STORE='atlas-sect';
const DEFAULT_OPEN_SECTIONS={diagram:true};
function sectAll(){ try{ return JSON.parse(localStorage.getItem(SECT_STORE)||'{}')||{}; }catch(e){ return {}; } }
function sectRemember(id, open){
  try{ const m=sectAll(); m[id]=open; localStorage.setItem(SECT_STORE, JSON.stringify(m)); }catch(e){}
}
function sectIsOpen(id){
  const m=sectAll();
  return id in m ? !!m[id] : !!DEFAULT_OPEN_SECTIONS[id];
}
// `titleHtml` is pre-built markup (it carries counts/summaries); an empty body renders nothing at all.
function section(id, titleHtml, bodyHtml){
  if(!bodyHtml) return '';
  return '<details class="sect" data-sect="'+enc(id)+'"'+(sectIsOpen(id)?' open':'')+'>'+
    '<summary>'+titleHtml+'</summary><div class="sb">'+bodyHtml+'</div></details>';
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
// One collapsible group of rows, headed by the declaring element and *what it calls*.
// `hasDg`: the node has a diagram — the group gets a ⌖ locate button targeting its element.
function paramGroupHtml(g, extraBody, hasDg){
  const label=g.name||g.element||'—';
  // "serviceTask · service-registry" is exact but internal; Design calls it a "Service registry task"
  const ty=elementTerm(g.type, g.sub);
  // The element id, when the label isn't already it: that is what you search for in the BPMN/CMMN XML or
  // pick out on the diagram, and a named task would otherwise never show it. Click to copy.
  const eid=(g.element!=null&&String(g.element)!==label)
    ? '<span class="opid">'+esc(String(g.element))+'</span>'+copyBtn(String(g.element),'element id') : '';
  const loc=(hasDg&&g.element!=null)?locateBtn(String(g.element), g.name):'';
  // the callee by name in the summary (visible without expanding) …
  const callee=g.refKey?'<span class="opref">→ '+esc(String(g.refKey))+'</span>':'';
  const cid=calleeNodeId(g);
  // … and as a chip in the body, where a click cannot fight the summary's own toggle
  const chip=cid?'<div class="opchips">'+nodeChip(cid)+'</div>':'';
  return '<details class="op" open'+dataEl(g.element)+'><summary><span class="opname">'+esc(label)+'</span>'+eid+loc+callee+
    '<span class="opcount">'+g.rows.length+' param'+(g.rows.length>1?'s':'')+'</span>'+
    '<span class="opkey">'+ty+'</span></summary>'+(extraBody||'')+chip+
    '<div class="parmgrid">'+g.rows.map(paramRow).join('')+'</div></details>';
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
  let head='';
  if(list.length>=PARAM_FILTER_FROM){
    const c={}; list.forEach(p=>{ c[p.dir]=(c[p.dir]||0)+1; });
    const chip=(d,lbl,n)=>'<button class="pchip'+(d==='all'?' on':'')+'" data-dir="'+d+'">'+esc(lbl)+
      '<span class="pchipn">'+n+'</span></button>';
    head='<div class="pbar"><input class="pf" type="search" placeholder="filter parameters…" '+
      'aria-label="Filter parameters">'+chip('all','all',list.length)+
      ['in','out','error-out'].filter(d=>c[d]).map(d=>chip(d,d,c[d])).join('')+'</div>';
  }
  return section('params','Parameters ('+list.length+') — '+esc(paramSummary(list)),
    head+gs.map(g=>paramGroupHtml(g, null, hasDg)).join(''));
}

// Live filter over an already-rendered Parameters section: text + direction, pure show/hide. Element
// groups whose every row is filtered out collapse away so the remaining ones stay easy to scan.
function wireParamFilter(det){
  const bar=det.querySelector('.pbar');
  if(!bar) return;
  const input=bar.querySelector('.pf'), chips=[...bar.querySelectorAll('.pchip')];
  const sect=bar.closest('.sb');
  const apply=()=>{
    const q=(input.value||'').trim().toLowerCase();
    const dir=(chips.find(c=>c.classList.contains('on'))||{}).dataset.dir||'all';
    sect.querySelectorAll('details.op').forEach(grp=>{
      let shown=0;
      grp.querySelectorAll('.pc').forEach(row=>{
        const ok=(dir==='all'||row.dataset.dir===dir) && (!q||(row.dataset.hay||'').indexOf(q)>=0);
        row.hidden=!ok; if(ok) shown++;
      });
      grp.hidden=!shown;
      if(shown) grp.open=true;
    });
  };
  input.addEventListener('input', debounce(apply,120));
  chips.forEach(c=>c.onclick=()=>{ chips.forEach(x=>x.classList.toggle('on', x===c)); apply(); });
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
// one label/value line in an expanded component, in the row rhythm of the list around it
function fldLine(label, html){
  return '<div class="oprow" style="border:none">'+(label?'<span class="muted">'+esc(label)+'</span>':'')+
    '<span style="flex:1;min-width:0">'+html+'</span></div>';
}
/**
 * One row of a form/page's Fields list.
 *
 * A component that *acts* — every button flavour, a select bound to a data object — expands in place:
 * what it invokes, the settings that decide what pressing it sends, and the payload it maps in and out.
 * Before this the row carried id, caption and type, so a form could show that it triggers an action
 * while staying silent about which button did it, with which values, under which condition. A plain
 * input has nothing to add and stays the dense one-line row it always was.
 */
function fieldRowHtml(f, d){
  const id=f.id==null?'':String(f.id);
  const req=(f.required===true||f.required==='true')?'<span class="pt" title="Required field">required</span>':'';
  // A link button's caption *is* its `value`, so the value column would only say it twice.
  const val=(f.value!=null&&f.value!==''&&String(f.value)!==String(f.label==null?'':f.label))
    ?'<span class="muted">←</span> <span class="mono">'+paramSide(String(f.value))+'</span>':'';
  const ty=termHtml('el', f.type, 'pt')||'<span class="pt">'+esc(f.type||'')+'</span>';
  const callee=f.callee, mode=f.payloadMode, st=f.settings||{};
  // a model callee is a node to jump to; a REST callee is a URL, which belongs in the body where it fits
  const cid=(callee&&callee.kind&&callee.kind!=='rest')?callee.kind+':'+callee.key:null;
  const ps=(d.ioParameters||[]).filter(p=>String(p.element)===id);
  const rcs=(d.restCalls||[]).filter(r=>String(r.where)===id);
  // The overridden map is still in the model — and still rendered in the Parameters section — so the
  // row that announces the override says which map stopped being the contract.
  const overridden=dir=>ps.some(p=>p.dir===dir)
    ? ' <span class="muted">— the '+(dir==='in'?'send payload map':'response map')+' below is not used</span>' : '';
  // A definitive gate is a fact about the row, so it is stated on the row: 252 of 338 buttons in one real
  // project are `visible:false` — auto-executing workers nobody ever presses — and a reader had no way to
  // tell them from a button.
  const gates=FGATES.filter(g=>st[g[0]]===g[1]).map(g=>termHtml('gate',g[2],'pt')).join('');
  let b='';
  // What the modeller wrote about it, first: it usually explains everything below.
  if(f.description) b+=fldLine('note','<span class="muted">'+esc(String(f.description))+'</span>');
  // The values a select/radio can take — static options, or the expression that computes them.
  if((f.options||[]).length) b+=fldLine('options','<span class="mono">'+esc(f.options.slice(0,20).join(' · '))+'</span>'+
    (f.options.length>20?' <span class="muted">+'+(f.options.length-20)+' more</span>':''));
  if(f.optionsExpression) b+=fldLine('options from','<span class="mono">'+esc(String(f.optionsExpression))+'</span>');
  // Every localised caption, not just the one that names the row — all of them are searchable.
  if((f.i18nLabels||[]).length) b+=fldLine('translations', f.i18nLabels.map(l=>
    '<span class="pt">'+esc(String(l.locale||''))+'</span> '+esc(String(l.label||''))).join(' · '));
  if(cid){ const chip=nodeChip(cid); if(chip) b+='<div class="opchips">'+chip+'</div>'; }
  rcs.forEach(r=>{ b+=fldLine('endpoint','<span class="pt">'+esc(r.method||'')+'</span> '+
    '<span class="mono" style="word-break:break-all">'+esc(r.url||'')+'</span>'+
    (r.path?' <span class="muted">→</span> <span class="mono">'+esc(r.path)+'</span>':'')); });
  if(callee&&callee.kind==='rest'&&!rcs.length)
    b+=fldLine('endpoint','<span class="mono" style="word-break:break-all">'+esc(String(callee.key))+'</span>');
  // Then where the result lands. The summary shows that binding in the value column like every other row;
  // this says what it *means* on a button, which is the opposite of what it means on an input: the button
  // writes it. (`valueExpression`, below, is which part of a response gets written.)
  if(f.stores) b+=fldLine(f.type==='restButton'?'stores response in':'stores result in',
    '<span class="mono">'+paramSide(String(f.stores))+'</span>');
  if(mode&&mode.send) b+=fldLine('sends', termHtml('pmode',mode.send,'pt')+overridden('in'));
  if(mode&&mode.receive) b+=fldLine('stores', termHtml('pmode',mode.receive,'pt')+overridden('out'));
  // The expression an expression button evaluates *is* what the button is, so it leads — as code, in the
  // same box a service task's script field gets. Then the values, then the plain on/off flags.
  if(st.script!=null&&st.script!==''&&st.script!==true)
    b+='<div class="parmgrid"><div class="pc" style="display:block"><span class="pd">expression</span>'+
      '<pre class="scriptbox" style="margin:4px 0 2px">'+esc(String(st.script))+'</pre></div></div>';
  // A gate already stated in the summary has nothing left to say here; a conditional one has everything.
  const said=new Set(FGATES.filter(g=>st[g[0]]===g[1]).map(g=>g[0]));
  const keys=Object.keys(st).filter(k=>k!=='script'&&st[k]!==true&&!said.has(k));
  keys.sort((a,z)=>(FSET_ORDER.indexOf(a)+1||99)-(FSET_ORDER.indexOf(z)+1||99));
  keys.forEach(k=>{
    b+=fldLine(FSET_LABEL[k]||k,'<span class="mono">'+esc(fsetValue(k,st[k]))+'</span>');
  });
  const flags=Object.keys(st).filter(k=>st[k]===true&&!said.has(k));
  if(flags.length) b+=fldLine('', flags.map(k=>'<span class="pt">'+esc(FSET_LABEL[k]||k)+'</span>').join(' '));
  if(ps.length){
    const shown=ps.slice(0,PARAM_ROWS_INLINE);
    b+='<div class="parmgrid">'+shown.map(paramRow).join('')+'</div>'+
      (ps.length>shown.length?'<div class="muted" style="font-size:var(--text-2xs);padding:2px var(--space-3)">+ '+
        (ps.length-shown.length)+' more in the Parameters section</div>':'');
  }
  const head='<span class="mono fldid">'+fieldLink(f.id)+'</span>'+
    '<span class="muted fldname">'+esc(f.label==null?'':String(f.label))+'</span>'+
    (cid?'<span class="opref">→ '+esc(String(callee.key))+'</span>':'')+val+
    (ps.length?'<span class="pt">'+esc(paramSummary(ps))+'</span>':'')+ty+req+gates;
  if(!b) return '<div class="oprow"'+dataEl(f.id)+'>'+head+'</div>';
  return '<details class="fldrow"'+dataEl(f.id)+'><summary>'+head+'</summary>'+
    '<div class="fldbody">'+b+'</div></details>';
}
// Above this many mapping rows the component's own body stops being a summary; the rest stay one click
// away in the Parameters section, which lists every mapping of the model with a filter of its own.
const PARAM_ROWS_INLINE=10;

function describe(n){
  const d=n.data||{}, rows=[];
  const add=(k,v)=>{ if(v!==undefined&&v!==null&&v!==''&&!(Array.isArray(v)&&!v.length)) rows.push([k,v]); };
  // count rows only when there is something to count — a grid of zeros is noise, not information
  const addCount=(k,v)=>{ if(v) rows.push([k,v]); };
  // split a comma/semicolon group list, drop dynamic ${…}/{{…}} entries, link each to its group node
  const addStarters=v=>{ const p=String(v==null?'':v).split(/[,;]/).map(s=>s.trim()).filter(g=>g&&!/\$\{|\{\{/.test(g));
    if(p.length) rows.push(['Starter groups',{html:p.map(g=>vlink('group:'+g,g)).join(', ')}]); };
  // a list of names, each linked to its variable node when one exists (else plain text)
  const varList=a=>({html:(a||[]).filter(x=>x!=null&&x!=='').map(x=>vlink('variable:'+String(x).split('.')[0], x)).join(', ')});
  // Design's model Description, before any per-type row: it is the sentence that says why the model
  // exists, it reads the same on every type, and a panel that shows it only for apps is the reason
  // nobody knew it was there. Types that add a `documentation` row keep it — on a process the two are
  // different fields (Design's metadata vs. the BPMN element's own text).
  add('Description',d.description);
  if(n.type==='process'){ addStarters(d.candidateStarterGroups); addCount('User tasks',(d.userTasks||[]).length);
    addCount('Service tasks',(d.serviceTasks||[]).length); addCount('Call activities',(d.callActivities||[]).length);
    addCount('Script tasks',(d.scriptTasks||[]).length); addCount('Decision tasks',(d.ruleTasks||[]).length);
    addCount('Subprocesses',(d.subProcesses||[]).length);
    addCount('Events',(d.events||[]).filter(e=>e.def||e.name).length);
    add('Parameters', paramSummary(d.ioParameters)); add('Documentation',d.documentation); }
  else if(n.type==='case'){ addStarters(d.candidateStarterGroups);
    if(d.initiatorVariableName) rows.push(['Initiator var',{html:vlink('variable:'+d.initiatorVariableName, d.initiatorVariableName)}]);
    addCount('Milestones',(d.milestones||[]).length); addCount('Event listeners',(d.eventListeners||[]).length);
    add('Parameters', paramSummary(d.ioParameters)); add('Documentation',d.documentation); }
  else if(n.type==='decision'){ if(d.decisionService) add('Kind','Decision service');
    // a decision service's members, each a decision of its own
    if(d.decisionService&&(d.decisions||[]).length)
      rows.push(['Decisions',{html:d.decisions.map(k=>byId.get('decision:'+k)?vlink('decision:'+k,k):esc(String(k))).join(', ')}]);
    add('Hit policy',d.hitPolicy); addCount('Rules',d.ruleCount);
    if((d.inputs||[]).length) rows.push(['Inputs',varList(d.inputs)]);
    // the expression behind a labelled input — that is what actually reads a variable
    if((d.inputExpressions||[]).length && String(d.inputExpressions)!==String(d.inputs))
      rows.push(['Input expressions',varList(d.inputExpressions)]);
    if((d.outputs||[]).length) rows.push(['Outputs',varList(d.outputs)]); }
  else if(n.type==='form'||n.type==='page'){ addCount('Fields',(d.fields||[]).length);
    addCount('Data sources',(d.dataSources||[]).length);
    add('Outcomes',(d.outcomes||[]).map(o=>o.value).filter(Boolean).join(', ')); }
  else if(n.type==='app'){ add('Theme',d.theme);
    addCount('Variables',(d.variables||[]).length); addCount('Pages',(d.pages||[]).length);
    const ga=String(d.groupsAccess||'').split(/[,;]/).map(s=>s.trim()).filter(Boolean);
    if(ga.length) rows.push(['Groups with access',{html:ga.map(g=>vlink('group:'+g,g)).join(', ')}]); }
  else if(n.type==='dataDictionary'){ add('Types',(d.types||[]).length&&(d.types||[]).join(', ')); }
  else if(n.type==='securityPolicy'){ add('Type',d.type); addCount('Permissions',(d.permissions||[]).length); }
  else if(n.type==='dataObject'){ add('Type',d.dataObjectType); add('Data source',d.sourceId);
    if(d.service) rows.push(['Backing service',{html:vlink('service:'+d.service, d.service, 'Service model '+d.service)}]);
    // When backed by a service, surface that service's physical table here and link the name back to the service node.
    const svc=d.service&&byId.get('service:'+d.service), tbl=d.serviceTableName||(svc&&(svc.data||{}).tableName);
    if(tbl) rows.push(['Table',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Provided by service '+esc(d.service)+'">'+esc(tbl)+'</span>', copy:tbl}]);
    if(d.dictionary) rows.push(['Data dictionary',{html:vlink('dataDictionary:'+d.dictionary, d.dictionary)}]);
    addCount('Columns',(d.fields||[]).length); }
  else if(n.type==='service'){ add('Type',d.type); add('Base URL',d.baseUrl); add('Auth',d.auth); add('Table',d.tableName);
    if(d.referencedLiquibaseModelKey){ const lid=(byId.get('liquibase:'+d.referencedLiquibaseModelKey)&&'liquibase:'+d.referencedLiquibaseModelKey)||outTo(n.id,'schema');
      rows.push(['Liquibase model',{html:vlink(lid, d.referencedLiquibaseModelKey)}]); }
    addCount('Columns',(d.columns||[]).length); addCount('Operations',(d.operations||[]).length);
    if(d.schemaCoverage){ const c=d.schemaCoverage.counts||{}; const g=(c.noService||0)+(c.noDataObject||0); if(g) add('Schema gaps',g+' of '+(c.total||0)+' columns'); } }
  else if(n.type==='serviceOperation'){
    if(d.service) rows.push(['Service',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Defined by service '+esc(d.service)+'">'+esc(d.service)+'</span>'}]);
    add('Name',d.name); add('Method',d.method); add('URL',d.fullUrl||d.url);
    add('Params',(d.params||[]).map(p=>p.name+(p.type?': '+p.type:'')).join(', '));
    add('Used by', (d.usedBy||[]).length+' model(s)'); }
  else if(n.type==='agent'){
    // compose only what is there — "Vendor / model: /" and "API endpoint: undefined" were rows once
    add('Vendor / model',[d.aiVendor,d.modelName].filter(Boolean).join(' / '));
    add('Temperature',d.temperature);
    if(d.enableApiEndpoint!=null) add('API endpoint', d.enableApiEndpoint?'enabled':'disabled');
    addCount('Tools',(d.tools||[]).length); addCount('Operations',(d.operations||[]).length);
    if(d.knowledgeBase) rows.push(['Knowledge base',{html:vlink('knowledgeBase:'+d.knowledgeBase, d.knowledgeBase)}]); }
  else if(n.type==='channel'){ add('Direction',d.channelType); add('Type',d.type); add('Topics',(d.topics||[]).join(', ')); add('Destination',d.destination);
    if(d.eventKey&&d.eventKey.fixedValue) rows.push(['Event',{html:vlink('event:'+d.eventKey.fixedValue, d.eventKey.fixedValue)}]); }
  else if(n.type==='event'){
    // payload entries are `{name, type, …}` records (older payloads were bare names)
    const pl=(d.payload||[]).map(p=>(p&&typeof p==='object')?p:{name:p});
    if(pl.length) rows.push(['Payload',varList(pl.map(p=>p.name))]);
    add('Correlation',(d.correlation||[]).join(', ')); }
  else if(n.type==='java'){ add('Package',d.package); add('Roles',(d.roles||[]).join(', ')); add('Bot key',d.botKey); add('Implements',(d.interfaces||[]).join(', ')); addCount('Methods',(d.methods||[]).length); add('Called from models',(d.calledMethods||[]).join(', ')); }
  else if(n.type==='endpoint'){ add('Method',d.http); add('Path',d.path);
    if(d.controller||d.handler) rows.push(['Handler',{html:vlink(incFrom(n.id,'serves'), [d.controller,d.handler].filter(Boolean).join('#')), copy:d.controller||undefined}]); }  // FQN for 'Go to Class'
  else if(n.type==='method'){ if(d.name) rows.push(['Method',{html:esc(d.name)+'()', copy:d.name}]);  // copy the bare name for IntelliJ 'Go to Symbol'
    if(d.class) rows.push(['Declared in',{html:vlink(d.declaredIn||'java:'+d.class, d.class), copy:d.class}]); }  // FQN for 'Go to Class'
  else if(n.type==='query'){ add('Source index',d.sourceIndex); add('Type',d.type);
    // parameters are `{name, type, …}` records (the legacy regex pass produced bare names)
    add('Parameters',(d.parameters||[]).map(p=>(p&&typeof p==='object')?p.name:p).filter(Boolean).join(', '));
    add('Sort by',(d.sortParameters||[]).join(', '));
    add('Aggregations',(d.aggregations||[]).join(', '));
    add('Filters by groups',(d.groups||[]).length); }
  else if(n.type==='sla'){ add('Type',d.slaType||d.scopeType); add('Calendar',d.businessCalendarType);
    if(d.completionDueDateValue!=null) add('Completion due',d.completionDueDateValue+' '+(d.completionDueDateTimeUnit||''));
    else add('Completion due',d.completionDueDateExpression);
    if(d.inProgressStartDueDateValue!=null) add('In-progress due',d.inProgressStartDueDateValue+' '+(d.inProgressStartDueDateTimeUnit||''));
    if(d.inProgressStartOnClaim!=null) add('Starts on claim',String(d.inProgressStartOnClaim));
    add('Task',d.taskDefinitionKey);
    addCount('Escalations',(d.escalations||[]).length); addCount('Thresholds',(d.thresholds||[]).length); }
  else if(n.type==='sequence'){ add('Format',d.format);
    add('Start',d.start!=null?d.start:d.startValue); add('Increment',d.increment);
    if(d.cycle) add('Cycle','true'); }
  else if(n.type==='template'){ add('Type',d.templateType||d.documentType||d.type);
    addCount('Variations',(d.variations||[]).length);
    if((d.variationParameters||[]).length) rows.push(['Variation parameters',
      {html:d.variationParameters.map(p=>esc(String(p.name||''))+(p.defaultValue!=null?' <span class="pt">'+esc(String(p.defaultValue))+'</span>':'')).join(', ')}]); }
  else if(n.type==='knowledgeBase'){ add('Type',d.type); add('Input source',d.inputSource);
    add('Content path',d.contentItemsPath); add('Top K',d.topK); add('Similarity',d.similarityThreshold);
    const vs=d.vectorStore||{};
    if(vs.type) add('Vector store',vs.type+(vs.embeddingModel?' · '+vs.embeddingModel:''));
    if(vs.credentials) add('Credentials',vs.credentials);
    addCount('Sources',(d.sources||[]).length); }
  else if(n.type==='variableExtractor'){ add('Source index',d.sourceIndex);
    addCount('Extractors',(d.extractors||[]).length);
    if((d.fullTextVariables||[]).length) rows.push(['Full-text',varList(d.fullTextVariables)]); }
  else if(n.type==='document'){ if(d.versioning!=null) add('Versioning',String(d.versioning));
    add('Initial state',d.initialState); add('Initial type',d.initialType);
    addCount('Variables',(d.variables||[]).length); add('AI instructions',d.aiInstructions); }
  else if(n.type==='action'){
    // Link the bot to whatever the graph resolved (action --bot--> java:<fqn> | bot:<key> | model node):
    // a Java bot keeps its class chip; any other resolved bot gets an inline link; only a truly
    // unresolved bot stays plain text.
    const be=(outM.get(n.id)||[]).find(e=>e.rel==='bot');
    if(be && byId.get(be.id)){ const bl=d.botKey||byId.get(be.id).label;
      rows.push(['Bot',{html: be.id.indexOf('java:')===0 ? jchip(be.id, bl) : vlink(be.id, bl)}]); }
    else add('Bot',d.botKey);
    if(d.formKey){ const fid=(byId.get('form:'+d.formKey)&&'form:'+d.formKey)||(byId.get('page:'+d.formKey)&&'page:'+d.formKey)||outTo(n.id,'action-form');
      rows.push(['Form',{html:vlink(fid, d.formKey)}]); }
    if(d.signalName){
      // start-instance bots carry a model key in signalName; other bots a real signal name
      const isP=d.botKey==='bpmn-start-process-instance-bot', isC=d.botKey==='cmmn-start-case-instance-bot';
      const sid=isP?'process:'+d.signalName:isC?'case:'+d.signalName:'signal:'+d.signalName;
      rows.push([isP?'Starts process':isC?'Starts case':'Triggers signal',{html:vlink(sid, d.signalName)}]);
    }
    add('Scope',d.scopeType); add('Parameters', paramSummary(d.ioParameters));
    if(d.script) add('Script',d.scriptLanguage||'script');
    const pg=(d.permissionGroups||[]).filter(g=>typeof g==='string'&&g);
    if(pg.length) rows.push(['Allowed groups',{html:pg.map(g=>vlink('group:'+g,g)).join(', ')}]);
    const chs=(d.channels||[]).map(c=>typeof c==='string'?c:(c&&c.key)).filter(Boolean);
    if(chs.length) rows.push(['Channels',{html:chs.map(c=>vlink('channel:'+c,c)).join(', ')}]); }
  else if(n.type==='bot'){ add('Kind',d.platform?'Flowable platform bot':'project-defined bot'); }
  else if(n.type==='liquibase'){ const a=d.authority||{};
    add('Status', a.status==='live'?'live (authoritative)':a.status==='superseded'?'superseded revision':a.status==='orphan'?'orphan — unreferenced':undefined);
    if((a.referencedBy||[]).length) rows.push(['Referenced by',{html:a.referencedBy.map(k=>vlink('service:'+k, k)).join(', ')}]);
    if((a.supersededBy||[]).length) rows.push(['Live definition',{html:a.supersededBy.map(k=>vlink('liquibase:'+k, k)).join(', ')}]);
    add('Tables',(d.effectiveTables||d.tables||[]).join(', ')); add('Columns',(d.columns||[]).length); }
  else if(n.type==='expression'||n.type==='binding'){ add('Used by', (d.usedBy||[]).length+' model(s)');
    const pr=d.problems||[]; if(pr.length){ const ec=pr.filter(p=>p.severity==='error').length, wc=pr.length-ec;
      add('Problems',[ec?ec+' error'+(ec>1?'s':''):'', wc?wc+' warning'+(wc>1?'s':''):''].filter(Boolean).join(', ')); } }
  else if(n.type==='variable'){ add('Scope',(d.scopes||[]).join(', ')); add('Used in', (d.usages||[]).length+' model(s)');
    add('As parameter', paramSummary(d.ioParams));
    // Written vs read, which is the fact a reader acts on — and the verdict, when there is one.
    if(d.writeCount||d.readCount) add('Direction', (d.writeCount||0)+' written · '+(d.readCount||0)+' read');
    if(d.unread) rows.push(['Verdict',{html:'<span class="pt" data-tip="Something writes this variable '+
      'and nothing Atlas can see reads it back. Query and dashboard models, master data and the Work UI '+
      'are not analysed, so check those before deleting it.">never read</span>',copy:null}]);
    if((d.unreadIn||[]).length) rows.push(['Verdict',{html:'<span class="pt" data-tip="The variable is '+
      'mapped into a called model that never reads it — the mapping has no effect there.">unread in '+
      esc(d.unreadIn.map(m=>(byId.get(m)||{}).label||m).join(', '))+'</span>',copy:null}]);
    if(d.readsUnknown) rows.push(['Verdict',{html:'<span class="pt" data-tip="Atlas would have listed '+
      'this as unread but declined to: it saw a construct whose direction it cannot determine, or a '+
      'reader outside the models it parses.">readers unknown</span>',copy:null}]);
    // nothing but a bare identifier in a script says this exists — same ≈ vocabulary as uncertain links
    if(d.heuristic) rows.push(['Evidence',{html:'<span class="pt" data-tip="Only a bare identifier in a '+
      'script body names this variable — Flowable puts scope variables into the script binding, so it is '+
      'probably real, but Atlas cannot prove it.">≈ script read only</span>',copy:null}]); }
  else if(n.type==='string'){ add('Used in', (d.usages||[]).length+' model(s)'); }
  else if(n.type==='customFunction'){
    add('Kind', d.kind==='namespace'?('namespace '+(d.namespace||'?')+'.*'):d.kind==='flw'?'flw.* member':'top-level');
    add('Signature', (d.member||n.label||'')+'('+(d.signature!=null?d.signature:'…')+')');
    add('Registered in',(d.sources||[]).join(', ')); add('Used by', (d.usedBy||[]).length+' form(s) / model(s)'); }
  else if(n.type==='external'){ add('Kind',d.flowableApi?'Flowable platform API':d.route?'In-app navigation route':d.platform?'Flowable platform bean':d.missingModel?'Missing model reference ('+(d.kind||'model')+')':d.dynamic?'Dynamic reference (expression) — expected '+(d.kind||'model'):(d.external_url?'External URL':d.kind||'external')); if(d.method&&d.method!=='(button)') add('Method',d.method); }
  else { Object.keys(d).forEach(k=>{
    // property probes, not reads: reading `d[k]` here would mark every container as consumed for the
    // "Other attributes" fallback while rendering only the scalars
    const desc=Object.getOwnPropertyDescriptor(d,k), v=desc&&desc.value;
    if(k!=='description'&&(typeof v==='string'||typeof v==='number')) add(k,d[k]); }); }
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

function detailExtra(n){
  const d=n.data||{}; let h='';
  const hasDg=!!d.diagram;                      // rows for diagram elements get a ⌖ locate button
  const EM=elementNames(n);                     // element id -> name/type, for readable references
  const loc=(id,name)=>hasDg&&id!=null&&id!==''?locateBtn(String(id), name):'';
  // element *name* with the raw id as tooltip — shared by the flow-shaped sections
  const elRef=id=>{ const nm=elName(EM,id);
    return '<span'+(nm!==String(id)?' data-tip="'+esc(String(id))+'"':'')+'>'+esc(nm)+'</span>'; };
  // What this model passes into, and takes back out of, everything it calls.
  if((d.ioParameters||[]).length) h+=paramSection(d.ioParameters, hasDg);
  // The mirror image: what this node actually receives from its callers. A payload is modelled on the
  // *calling* side (a form button, a call activity), so without this you would have to visit every caller
  // to see whether the names line up with what the callee expects. `refKind` mirrors the node type, so
  // matching on both is what keeps a service and a data object of the same key apart.
  {
    const callers=[];
    (incM.get(n.id)||[]).forEach(e=>{
      const src=byId.get(e.id); if(!src) return;
      const rows=((src.data||{}).ioParameters||[]).filter(p=>p.refKind===n.type && p.refKey===n.key);
      if(rows.length) callers.push({id:e.id, rows});
    });
    const total=callers.reduce((a,c)=>a+c.rows.length,0);
    if(total) h+=section('called-with','Called with ('+total+') — '+esc(paramSummary(callers.flatMap(c=>c.rows))),
      // here the interesting other side is the *caller*, so its chip replaces the callee's
      callers.map(c=>paramGroups(c.rows).map(g=>
        paramGroupHtml({...g, refKey:null, refKind:null}, '<div class="opchips">'+nodeChip(c.id)+'</div>')
      ).join('')).join(''));
  }
  // ---------- model structure — data parsed from the model file, linked into the graph ----------
  // Every value that names something else is a link when the target exists: a field id → its variable,
  // a task → its form and candidate groups, a plan item → the process/case/decision it starts.
  // (Field injections live inside each task's entry in the Service tasks section below.)
  if(n.type==='process' && (d.userTasks||[]).length){
    h+=section('usertasks','User tasks ('+d.userTasks.length+')','<div class="oplist">'+
      d.userTasks.map(t=>{
        const eid=(t.id&&t.id!==(t.name||t.id))?'<span class="opid">'+esc(t.id)+'</span>':'';
        const bits=[
          t.formKey?'<span class="muted">form</span> '+vlink('form:'+t.formKey, t.formKey):'',
          t.candidateGroups?'<span class="muted">groups</span> '+groupLinksHtml(t.candidateGroups):'',
          t.assignee?'<span class="muted">assignee</span> '+esc(t.assignee):'',
        ].filter(Boolean).join(' ');
        const extra=[t.dueDate?'due '+t.dueDate:'',t.priority?'priority '+t.priority:'',t.category||'']
          .filter(Boolean).map(x=>'<span class="pt">'+esc(x)+'</span>').join('');
        return '<div class="oprow"'+dataEl(t.id)+'><span style="min-width:150px">'+esc(t.name||t.id||'')+'</span>'+eid+loc(t.id,t.name)+
          '<span style="flex:1;display:flex;gap:8px;flex-wrap:wrap">'+bits+'</span>'+extra+'</div>';
      }).join('')+'</div>');
  }
  // One collapsible script task — shared by BPMN <scriptTask> and CMMN <task flowable:type="script">.
  const scriptTaskHtml=t=>{
    const fmtV=t.format||t.scriptFormat;
    const rv=t.resultVariable?'<span class="pd" style="color:var(--ok-text)">out</span> <span class="mono">'+paramSide(t.resultVariable)+'</span>':'';
    const fmt=fmtV?'<span class="pt">'+esc(fmtV)+'</span>':'';
    const eid=(t.id&&t.id!==(t.name||t.id))?'<span class="opid">'+esc(t.id)+'</span>':'';
    const body=t.script?codeBoxHtml(t.script, fmtV, t.problems)
      :'<div class="muted" style="padding:4px 10px">no script body</div>';
    return '<details class="op"'+dataEl(t.id)+((t.problems||[]).length?' open':'')+
      '><summary><span class="opname">'+esc(t.name||t.id||'')+'</span>'+eid+loc(t.id,t.name)+fmt+
      scriptIssueBadge(t.problems)+rv+'</summary>'+scriptProblemsHtml(t.problems)+body+'</details>';
  };
  if(n.type==='process' && (d.scriptTasks||[]).length){
    h+=section('scripttasks','Script tasks ('+d.scriptTasks.length+')', d.scriptTasks.map(scriptTaskHtml).join(''));
  }
  // CMMN keeps its script tasks in the plan tree — surface their bodies just like BPMN script tasks.
  if(n.type==='case' && d.planModel){
    const cs=[];
    (function walk(nd){ if(nd.script||(nd.problems||[]).length) cs.push(nd); (nd.children||[]).forEach(walk); })(d.planModel);
    if(cs.length) h+=section('scripttasks','Script tasks ('+cs.length+')', cs.map(scriptTaskHtml).join(''));
  }
  if(n.type==='process' && (d.events||[]).length){
    const evs=d.events.filter(e=>e.def||e.name);
    if(evs.length) h+=section('events','Events ('+evs.length+')','<div class="oplist">'+
      evs.map(e=>'<div class="oprow"'+dataEl(e.id)+'><span style="min-width:150px">'+esc(e.name||e.id||'')+'</span>'+loc(e.id,e.name)+
        '<span class="opkey">'+elementTerm(e.type)+'</span>'+(e.def?'<span class="pt">'+esc(e.def)+'</span>':'')+
        // a boundary event's host activity, and whether triggering interrupts it
        (e.attachedTo?'<span class="muted">on</span> '+elRef(e.attachedTo)+
          (String(e.cancelActivity)==='false'?'<span class="pt">non-interrupting</span>':''):'')+
        (e.value?'<span class="mono" style="color:var(--ink-faint)">'+esc(e.value)+'</span>':'')+'</div>').join('')+'</div>');
  }
  if(n.type==='process' && (d.multiInstance||[]).length){
    h+=section('multiinstance','Multi-instance ('+d.multiInstance.length+')','<div class="oplist">'+
      d.multiInstance.map(m=>'<div class="oprow"'+dataEl(m.activity)+'><span class="muted" style="min-width:150px">'+esc(elName(EM,m.activity||''))+'</span>'+loc(m.activity)+
        (m.collection?'<span class="muted">over</span><span class="mono">'+paramSide(m.collection)+'</span>':'')+
        (m.elementVariable?'<span class="muted">as</span><span class="mono">'+paramSide(m.elementVariable)+'</span>':'')+
        (m.sequential==='true'?'<span class="pt">sequential</span>':'')+
        (m.cardinality?'<span class="pt">× '+esc(m.cardinality)+'</span>':'')+'</div>').join('')+'</div>');
  }
  if(n.type==='process' && (d.conditions||[]).length){
    // Element *names* instead of raw ids (the id stays as a tooltip), a ⌖ that highlights the flow's
    // arrow on the diagram, and gateway grouping via the from-element — the old raw `sid-… → sid-…`
    // rows were impossible to map to anything.
    h+=section('conditions','Sequence flow conditions ('+d.conditions.length+')','<div class="oplist">'+
      d.conditions.map(c=>'<div class="oprow"'+dataEl(c.id)+'>'+
        '<span class="cflow" style="min-width:150px">'+elRef(c.from)+' <span class="pa">→</span> '+elRef(c.to)+'</span>'+
        loc(c.id)+
        '<span class="mono" style="flex:1">'+esc(c.condition||'')+'</span></div>').join('')+'</div>');
  }
  // The full topology, in document order — what runs after what, the most basic question about a
  // process. `conditions` above stays as the conditional subset matched to the diagram's edges.
  if(n.type==='process' && (d.flows||[]).length){
    h+=section('flows','Sequence flows ('+d.flows.length+')','<div class="oplist">'+
      d.flows.map(f=>'<div class="oprow"'+dataEl(f.id)+'>'+
        '<span class="cflow" style="min-width:150px">'+elRef(f.from)+' <span class="pa">→</span> '+elRef(f.to)+'</span>'+
        loc(f.id)+
        (f.name?'<span class="muted">'+esc(f.name)+'</span>':'')+
        (f.default?'<span class="pt" data-tip="Taken when no other outgoing flow’s condition matches">default</span>':'')+
        (f.condition?'<span class="mono" style="flex:1">'+esc(f.condition)+'</span>':'')+
        '</div>').join('')+'</div>');
  }
  // Lanes: who works which part of the process — until now painted in the diagram and stated nowhere.
  if(n.type==='process' && (d.lanes||[]).length){
    h+=section('lanes','Lanes ('+d.lanes.length+')','<div class="oplist">'+
      d.lanes.map(l=>'<div class="oprow"'+dataEl(l.id)+'><span style="min-width:150px">'+esc(l.name||l.id||'')+'</span>'+loc(l.id,l.name)+
        '<span style="flex:1;display:flex;gap:6px;flex-wrap:wrap">'+
        (l.elements||[]).map(id=>'<span class="pt" data-tip="'+esc(String(id))+'">'+esc(elName(EM,id))+'</span>').join('')+
        '</span></div>').join('')+'</div>');
  }
  // The process's own <dataObject> declarations — its variables, with type and default.
  if(n.type==='process' && (d.dataObjects||[]).length){
    h+=section('declaredvars','Declared data objects ('+d.dataObjects.length+')','<div class="oplist">'+
      d.dataObjects.map(o=>{ const nm=o.name||o.id||'';
        return '<div class="oprow"'+dataEl(o.id)+'><span style="min-width:150px">'+vlink('variable:'+nm, nm)+'</span>'+
          (o.type?'<span class="pt">'+esc(String(o.type).replace(/^xsd:/,''))+'</span>':'')+
          (o.default!=null&&o.default!==''?'<span class="muted">default</span><span class="mono">'+esc(String(o.default))+'</span>':'')+
          '</div>'; }).join('')+'</div>');
  }
  // Two things every element can carry, both previously dropped on the floor: the documentation the
  // modeller wrote about it, and the listeners it runs (only the process/case level and BPMN user tasks
  // were read before, so an execution listener on a service task existed nowhere in Atlas).
  if(n.type==='process'||n.type==='case'){
    const recs=elementRecords(n);
    const docs=recs.filter(r=>r.documentation);
    if(docs.length) h+=section('eldocs','Documentation ('+docs.length+')','<div class="oplist">'+
      docs.map(r=>'<div class="oprow"'+dataEl(r.id)+'><span style="min-width:150px">'+esc(r.name||r.id||'')+'</span>'+
        loc(r.id,r.name)+'<span style="flex:1">'+esc(r.documentation)+'</span></div>').join('')+'</div>');
    const ls=[].concat(
      (d.listeners||[]).map(l=>({owner:null, l})),
      ...recs.map(r=>(r.listeners||[]).map(l=>({owner:r, l}))),
    ).filter(x=>x.l&&(x.l.class||x.l.expression||x.l.delegateExpression||x.l.script));
    // Design keeps execution, task and lifecycle listeners in separate property groups — one section
    // each, named the way Design names them, instead of one pile called "Listeners".
    const byKind=new Map();
    ls.forEach(x=>{ const k=x.l.kind||'listener';
      if(!byKind.has(k)) byKind.set(k,[]); byKind.get(k).push(x); });
    [...byKind.keys()].sort().forEach(kind=>{
      const items=byKind.get(kind);
      h+=section('listeners-'+kind, plural(term('el',kind).label)+' ('+items.length+')','<div class="oplist">'+
        items.map(({owner:o, l})=>{
          const impl=l.class?vlink('java:'+l.class, l.class):esc(l.expression||l.delegateExpression||(l.script?'(script)':''));
          const who=o?'<span style="min-width:150px">'+esc(o.name||o.id||'')+'</span>'+loc(o.id,o.name)
                     :'<span class="muted" style="min-width:150px">'+esc(nodeKind(n))+'</span>';
          return '<div class="oprow"'+(o?dataEl(o.id):'')+'>'+who+
            (l.event?'<span class="pt">'+esc(l.event)+'</span>':'')+
            '<span class="mono" style="flex:1">'+impl+'</span></div>';
        }).join('')+'</div>');
    });
  }
  // The decision table itself. Only the row *count* used to survive parsing, so the conditions and
  // values that are the actual business logic were neither visible nor findable.
  if(n.type==='decision' && (d.rules||[]).length){
    const ann=d.rules.some(r=>r.annotation);
    // `o` marks where the inputs end and the outputs begin
    const cell=(tag,v,i)=>'<'+tag+(i===0?' class="o"':'')+'>'+esc(v==null||v===''?'—':String(v))+'</'+tag+'>';
    const row=r=>'<tr>'+(r.inputs||[]).map(c=>cell('td',c,-1)).join('')+
      (r.outputs||[]).map((c,i)=>cell('td',c,i)).join('')+
      (ann?'<td>'+esc(r.annotation||'')+'</td>':'')+'</tr>';
    h+=section('dmnrules','Rules ('+(d.ruleCount||d.rules.length)+')',
      '<div class="dmntab"><table><thead><tr>'+
      (d.inputs||[]).map(x=>cell('th',x,-1)).join('')+
      (d.outputs||[]).map((x,i)=>cell('th',x,i)).join('')+
      (ann?'<th>annotation</th>':'')+'</tr></thead><tbody>'+
      d.rules.map(row).join('')+'</tbody></table>'+
      (d.rulesTruncated?'<div class="muted" style="padding:4px 0">showing '+d.rules.length+' of '+
        d.rulesTruncated+' rules</div>':'')+'</div>');
  }
  if(n.type==='case' && d.planModel){
    const CRIT=caseCriteria(d);
    // the item's entry/exit criteria, each with its sentry's condition — right where the item is listed
    const critsOf=nd=>CRIT.filter(c=>(c.planItemDef!=null&&String(c.planItemDef)===String(nd.id))||
                                     (c.planItemDef==null&&c.planItem&&c.planItem===nd.name))
      .map(criterionChip).join(' ');
    const planItem=nd=>{
      const kids=(nd.children||[]);
      const label=esc(nd.name||nd.id||'');
      if(nd.type==='stage'||nd.type==='planFragment'||nd.type==='casePlanModel'){
        return '<details class="uses" open><summary>'+(nd.type==='casePlanModel'?'Plan model':(label||nd.type))+
          ' <span class="muted">('+kids.length+' item'+(kids.length===1?'':'s')+')</span> '+critsOf(nd)+'</summary>'+
          '<div class="plantree">'+kids.map(planItem).join('')+'</div></details>';
      }
      const rules=nd.rules?Object.keys(nd.rules)
        .map(r=>({repetitionRule:'repeatable',requiredRule:'required',manualActivationRule:'manual'}[r]||r))
        .map(t=>'<span class="pt">'+esc(t)+'</span>').join(''):'';
      const bits=[
        nd.formKey?'<span class="muted">form</span> '+vlink('form:'+nd.formKey, nd.formKey):'',
        nd.processRef?'<span class="muted">process</span> '+vlink('process:'+nd.processRef, nd.processRef):'',
        nd.caseRef?'<span class="muted">case</span> '+vlink('case:'+nd.caseRef, nd.caseRef):'',
        nd.decisionRef?'<span class="muted">decision</span> '+vlink('decision:'+nd.decisionRef, nd.decisionRef):'',
        nd.candidateGroups?'<span class="muted">groups</span> '+groupLinksHtml(nd.candidateGroups):'',
        critsOf(nd),
      ].filter(Boolean).join(' ');
      return '<div class="oprow" style="border:none"'+dataEl(nd.id)+'><span style="min-width:150px">'+(nd.type==='milestone'?'◆ ':'')+label+'</span>'+loc(nd.id,nd.name)+
        '<span class="opkey">'+elementTerm(nd.type, nd.serviceTaskType||undefined)+'</span>'+
        (bits?'<span style="flex:1;display:flex;gap:8px;flex-wrap:wrap">'+bits+'</span>':'')+rules+'</div>';
    };
    h+=section('plan','Case plan model — stages & plan items', planItem(d.planModel));
  }
  if(n.type==='case' && (d.sentries||[]).length){
    const CRIT=caseCriteria(d);
    const ss=d.sentries.filter(s=>s.condition||(s.onParts||[]).length);
    // name each sentry by what it guards ("entry of Review", not "sentry3"); the raw id stays a tooltip
    if(ss.length) h+=section('sentries','Sentries — entry / exit criteria ('+ss.length+')','<div class="oplist">'+
      ss.map(s=>{
        const uses=CRIT.filter(c=>String(c.sentryRef)===String(s.id))
          .map(c=>(c.type==='entryCriterion'?'entry of ':'exit of ')+
                  elName(EM, c.planItemDef!=null?c.planItemDef:(c.planItem||'?')));
        const who=uses.length
          ? '<span style="min-width:150px" data-tip="'+esc(String(s.id||''))+'">'+esc(uses.join(', '))+'</span>'
          : '<span class="muted" style="min-width:150px">'+esc(s.id||'')+'</span>';
        return '<div class="oprow"'+dataEl(s.id)+'>'+who+
          ((s.onParts||[]).length?'<span class="pt">on '+esc(s.onParts.filter(Boolean).join(', '))+'</span>':'')+
          '<span class="mono" style="flex:1">'+esc(s.condition||'')+'</span></div>';
      }).join('')+'</div>');
  }
  if(n.type==='case' && (d.eventListeners||[]).length){
    h+=section('eventlisteners','Event listeners ('+d.eventListeners.length+')','<div class="oplist">'+
      d.eventListeners.map(e=>{
        const bits=[
          e.timer?'<span class="mono">'+esc(e.timer)+'</span>':'',
          e.eventType?'<span class="muted">event</span> '+vlink('event:'+e.eventType, e.eventType):'',
          e.signalRef?'<span class="muted">signal</span> '+vlink('signal:'+e.signalRef, e.signalRef):'',
        ].filter(Boolean).join(' ');
        return '<div class="oprow"'+dataEl(e.id)+'><span style="min-width:150px">'+esc(e.name||e.id||'')+'</span>'+loc(e.id,e.name)+
          '<span class="opkey">'+elementTerm(e.type)+'</span><span style="flex:1;display:flex;gap:8px;flex-wrap:wrap">'+bits+'</span></div>';
      }).join('')+'</div>');
  }
  if((n.type==='form'||n.type==='page') && (d.fields||[]).length){
    h+=section('formfields','Fields ('+d.fields.length+')','<div class="oplist fldlist">'+
      d.fields.map(f=>fieldRowHtml(f, d)).join('')+'</div>');
  }
  // Subforms — parsed and referenced for years, rendered nowhere until now.
  if((n.type==='form'||n.type==='page') && (d.subforms||[]).length){
    h+=section('subforms','Subforms ('+d.subforms.length+')','<div class="nodechips">'+
      d.subforms.map(k=>byId.get('form:'+k)?nodeChip('form:'+k)
        :'<span class="nc"><span class="nm">'+esc(String(k))+'</span><span class="ty">form</span></span>').join('')+'</div>');
  }
  if((n.type==='form'||n.type==='page') && (d.dataSources||[]).length){
    h+=section('datasources','Data sources ('+d.dataSources.length+')','<div class="oplist">'+
      d.dataSources.map(s=>{
        const tgt=s.kind==='dataObject'?vlink('dataObject:'+s.key, s.key)
          :s.kind==='service'?vlink('service:'+s.key, s.key):esc(s.url||s.key||'');
        const op=s.op?'<span class="muted">operation</span> <span class="mono">'+esc(s.op)+'</span>':'';
        return '<div class="oprow">'+termHtml('kind-ds', s.kind, 'pt')+'<span class="mono" style="flex:1">'+tgt+'</span>'+op+'</div>';
      }).join('')+'</div>');
  }
  // What this form/page calls over HTTP — a REST button's endpoint, which used to be recorded only as a
  // graph edge and so could not be read off the model or searched for.
  if((n.type==='form'||n.type==='page') && (d.restCalls||[]).length){
    h+=section('restcalls','REST calls ('+d.restCalls.length+')','<div class="oplist">'+
      d.restCalls.map(r=>{
        const path=r.path?'<span class="muted">response</span> <span class="mono">'+esc(r.path)+'</span>':'';
        return '<div class="oprow"><span class="pt">'+esc(r.method||'')+'</span>'+
          '<span class="mono" style="flex:1" title="'+esc(r.url||'')+'">'+esc(r.url||'')+'</span>'+
          '<span class="opid">'+fieldLink(r.where)+'</span>'+path+'</div>';
      }).join('')+'</div>');
  }
  if(n.type==='securityPolicy' && (d.permissions||[]).length){
    h+=section('permissions','Permissions ('+d.permissions.length+') — who may do what','<div class="oplist">'+
      d.permissions.map(p=>'<div class="oprow"><span style="min-width:180px">'+esc(p.label||p.key||'')+'</span>'+
        (p.label&&p.key&&p.label!==p.key?'<span class="opid">'+esc(p.key)+'</span>':'')+
        '<span style="flex:1">'+(p.roles||[]).map(r=>vlink('group:'+r,r)).join(', ')+'</span></div>').join('')+'</div>');
  }
  // A dictionary's types with their declared properties — the names list alone said nothing.
  if(n.type==='dataDictionary' && (d.typeDefs||[]).length){
    h+=section('dicttypes','Type definitions ('+d.typeDefs.length+')',
      d.typeDefs.map(t=>{ const props=t.properties||[];
        const head='<span class="opname">'+esc(String(t.name||''))+'</span>'+
          (t.parent?'<span class="pt">extends '+esc(String(t.parent))+'</span>':'');
        if(!props.length) return '<div class="op flat">'+head+'</div>';
        return '<details class="op"><summary>'+head+'<span class="opcount">'+props.length+' propert'+(props.length>1?'ies':'y')+'</span></summary>'+
          '<div class="parmgrid">'+props.map(p=>'<div class="pc"><span class="pn">'+esc(String(p.name||''))+'</span>'+
            (p.type?'<span class="pt">'+esc(String(p.type))+'</span>':'')+'</div>').join('')+'</div></details>';
      }).join(''));
  }
  // An SLA's consequences: what happens, when, relative to which deadline.
  if(n.type==='sla' && (d.escalations||[]).length){
    h+=section('escalations','Escalations ('+d.escalations.length+')','<div class="oplist">'+
      d.escalations.map(e=>'<div class="oprow"><span style="min-width:150px">'+esc(String(e.stepId||e.on||''))+'</span>'+
        (e.timeValue!=null?'<span class="pt">'+esc(String(e.timeValue))+' '+esc(String(e.timeUnit||''))+' '+esc(String(e.relativeType||''))+'</span>':'')+
        (e.action?'<span class="muted">'+esc(String(e.action))+'</span>':'')+
        (e.starts?vlink(byId.get('process:'+e.starts)?'process:'+e.starts:'case:'+e.starts, e.starts):'')+
        (e.assignee?'<span class="mono">'+esc(String(e.assignee))+'</span>':'')+
        (e.condition?'<span class="mono" style="flex:1">'+esc(String(e.condition))+'</span>':'')+
        '</div>').join('')+'</div>');
  }
  if(n.type==='sla' && (d.thresholds||[]).length){
    h+=section('thresholds','Thresholds ('+d.thresholds.length+')','<div class="oplist">'+
      d.thresholds.map(t=>'<div class="oprow"><span style="min-width:150px">'+esc(String(t.type||''))+'</span>'+
        '<span class="mono">'+esc(String(t.duration||''))+'</span></div>').join('')+'</div>');
  }
  // The template's actual text — the thing a reader searches for.
  if(n.type==='template' && (d.content||(d.variations||[]).length)){
    let b='';
    if(d.content) b+=codeBoxHtml(d.content, 'freemarker');
    (d.variations||[]).forEach(v=>{
      const params=v.parameters?Object.entries(v.parameters).map(([k,val])=>'<span class="pt">'+esc(k)+': '+esc(String(val))+'</span>').join(''):'';
      b+='<div style="margin:6px 0">'+(params?'<div class="opchips">'+params+'</div>':'')+
        (v.text?codeBoxHtml(v.text,'freemarker'):(v.resource!=null?'<div class="muted" style="padding:2px 10px">resource: '+esc(String(v.resource))+'</div>':''))+'</div>';
    });
    h+=section('templatebody','Template body', b);
  }
  // A query's contract and body: parameters, legacy columns, and the search template it runs.
  if(n.type==='query' && ((d.parameters||[]).length||(d.columns||[]).length||d.templateContent)){
    let b='';
    if((d.parameters||[]).length) b+='<div class="parmgrid">'+d.parameters.map(p=>
      '<div class="pc"><span class="pn">'+esc(String(p.name||''))+'</span>'+
      (p.type?'<span class="pt">'+esc(String(p.type))+'</span>':'')+
      (p.required?'<span class="pd">required</span>':'')+
      (p.label?'<span class="muted">'+esc(String(p.label))+'</span>':'')+'</div>').join('')+'</div>';
    if((d.columns||[]).length) b+='<div class="oplist">'+d.columns.map(c=>
      '<div class="oprow"><span>'+esc(String(c.name||''))+'</span><span class="muted">'+esc(String(c.label||''))+'</span>'+
      (c.variableName?'<span class="mono" style="margin-left:auto">'+vlink('variable:'+c.variableName, c.variableName)+'</span>':'')+'</div>').join('')+'</div>';
    if(d.templateContent) b+=codeBoxHtml(d.templateContent, 'json');
    h+=section('querydef','Query definition', b);
  }
  // A document model's per-action forms and who may do what with it.
  if(n.type==='document' && (d.forms||(d.actionPermissions||[]).length)){
    let b='';
    if(d.forms) b+='<div class="oplist">'+Object.entries(d.forms).map(([op,fk])=>
      '<div class="oprow"><span class="pt">'+esc(op)+'</span><span style="flex:1">'+
      (byId.get('form:'+fk)?nodeChip('form:'+fk):esc(String(fk)))+'</span></div>').join('')+'</div>';
    if((d.actionPermissions||[]).length) b+='<div class="oplist">'+d.actionPermissions.map(a=>
      '<div class="oprow"><span class="pt">'+esc(String(a.action||''))+'</span><span style="flex:1">'+
      (a.groups||[]).map(g=>vlink('group:'+g,g)).join(', ')+'</span></div>').join('')+'</div>';
    h+=section('docconfig','Forms & permissions', b);
  }
  // Which variable each extractor writes, from which scope's payload.
  if(n.type==='variableExtractor' && (d.extractors||[]).length){
    h+=section('extractors','Extracted variables ('+d.extractors.length+')','<div class="oplist">'+
      d.extractors.map(x=>{ const sid=x.scope?(byId.get('process:'+x.scope)?'process:'+x.scope:(byId.get('case:'+x.scope)?'case:'+x.scope:null)):null;
        return '<div class="oprow">'+
          (sid?nodeChip(sid):(x.scope?'<span class="muted">'+esc(String(x.scope))+'</span>':''))+
          '<span class="mono">'+esc(String(x.from||x.path||''))+'</span><span class="pa">→</span>'+
          '<span class="mono">'+vlink('variable:'+x.to, x.to)+'</span>'+
          (x.type?'<span class="pt">'+esc(String(x.type))+'</span>':'')+'</div>'; }).join('')+'</div>');
  }
  // Where a knowledge base's documents come from.
  if(n.type==='knowledgeBase' && (d.sources||[]).length){
    h+=section('kbsources','Sources ('+d.sources.length+')','<div class="oplist">'+
      d.sources.map(s=>'<div class="oprow"><span class="pt">'+esc(String(s.type||''))+'</span>'+
        '<span class="mono" style="flex:1">'+esc(String(s.path||''))+'</span></div>').join('')+'</div>');
  }
  // An event payload's full contract, when the model states more than names.
  if(n.type==='event' && (d.payload||[]).some(p=>p&&typeof p==='object'&&(p.type||p.required))){
    h+=section('payload','Payload ('+d.payload.length+')','<div class="parmgrid">'+
      d.payload.map(p=>{ const o=(p&&typeof p==='object')?p:{name:p};
        return '<div class="pc"><span class="pn">'+esc(String(o.name||''))+'</span>'+
          (o.type?'<span class="pt">'+esc(String(o.type))+'</span>':'')+
          (o.required?'<span class="pd">required</span>':'')+
          (o.correlation?'<span class="pd">correlates</span>':'')+'</div>'; }).join('')+'</div>');
  }
  if(n.type==='agent' && (d.tools||[]).length){
    h+=section('tools','Tools ('+d.tools.length+') — what the agent may call','<div class="nodechips">'+
      d.tools.map(t=>{const id=(t.type||'service')+':'+(t.key||'');
        return byId.get(id)?nodeChip(id):'<span class="nc"><span class="nm">'+esc(t.key||'')+'</span><span class="ty">'+esc(t.type||'')+'</span></span>';}).join('')+'</div>');
  }
  if(n.type==='agent' && (d.operations||[]).length){
    h+=section('agentops','Operations ('+d.operations.length+')',
      d.operations.map(o=>{
        const msgs=[['system',o.systemMessage],['user',o.userMessage]].filter(m=>m[1]);
        const key=(o.key&&o.key!==(o.name||o.key))?'<span class="opkey">'+esc(o.key)+'</span>':'';
        if(!msgs.length) return '<div class="op flat"><span class="opname">'+esc(o.name||o.key||'')+'</span>'+key+'</div>';
        // prompts are multi-paragraph text now that the parser keeps them whole — a code box, not a one-liner
        return '<details class="op"><summary><span class="opname">'+esc(o.name||o.key||'')+'</span>'+key+
          '<span class="opcount">'+msgs.length+' prompt'+(msgs.length>1?'s':'')+'</span></summary>'+
          '<div class="parmgrid">'+msgs.map(m=>'<div class="pc" style="display:block"><span class="pd">'+m[0]+'</span>'+
            '<pre class="scriptbox" style="margin:4px 0 2px;white-space:pre-wrap">'+esc(m[1])+'</pre></div>').join('')+'</div></details>';
      }).join(''));
  }
  if(n.type==='app' && (d.variables||[]).length){
    h+=section('appvars','App variables ('+d.variables.length+')','<div class="oplist">'+
      d.variables.map(v=>'<div class="oprow"><span class="mono" style="flex:1">'+fieldLink(v.key)+'</span>'+
        (v.type?'<span class="pt">'+esc(v.type)+'</span>':'')+'</div>').join('')+'</div>');
  }
  if(n.type==='app' && (d.pages||[]).length){
    h+=section('apppages','Pages ('+d.pages.length+')','<div class="nodechips">'+
      d.pages.map(p=>byId.get('page:'+p.key)?nodeChip('page:'+p.key)
        :'<span class="nc"><span class="nm">'+esc(p.key||'')+'</span><span class="ty">page</span></span>').join('')+'</div>');
  }
  if(n.type==='action' && (d.script||(d.scriptProblems||[]).length)){
    h+=section('script','Bot script'+(d.scriptLanguage?' ('+esc(d.scriptLanguage)+')':'')+
      ((d.scriptProblems||[]).length?' '+scriptIssueBadge(d.scriptProblems):''),
      scriptProblemsHtml(d.scriptProblems)+
      codeBoxHtml(d.script, d.scriptLanguage, d.scriptProblems));
  }
  if(n.type==='service' && (d.operations||[]).length){
    h+=section('ops','Operations ('+d.operations.length+')',
      d.operations.map(o=>{
        const verb=o.method?'<span class="verb" style="color:'+color("endpoint")+'">'+esc(o.method)+'</span>':'';
        const title='<span class="opname">'+esc(o.fullUrl||o.url||o.name||'')+'</span>';
        // link the key to the operation's own node (its "where used" page)
        const opid='serviceOperation:'+n.key+'#'+(o.key||'');
        const key=((o.key&&byId.get(opid))
          ? '<span class="opkey vlink" data-id="'+enc(opid)+'" tabindex="0" role="link" title="Show where '+esc(o.key)+' is used">'+esc(o.key)+'</span>'
          : '<span class="opkey">'+esc(o.key||'')+'</span>')+copyBtn(o.key,'operation key');
        // An operation's contract has two halves: what a caller must supply and what it gets back.
        const decl=(o.params||[]).map(p=>['in',p]).concat((o.outParams||[]).map(p=>['out',p]));
        if(!decl.length) return '<div class="op flat">'+verb+title+'<span class="opcount">no params</span>'+key+'</div>';
        return '<details class="op"><summary>'+verb+title+
          '<span class="opcount">'+paramSummary(decl.map(([dir])=>({dir})))+'</span>'+key+'</summary>'+
          '<div class="parmgrid">'+decl.map(([dir,p])=>
            '<div class="pc"><span class="pd" style="color:var('+PDIR_COLOR[dir]+')">'+dir+'</span>'+
            '<span class="pn">'+esc(p.name)+'</span>'+
            (p.type?'<span class="pt">'+esc(p.type)+'</span>':'')+
            (p.required?'<span class="pd">required</span>':'')+
            (p.default!=null?'<span class="muted">default '+esc(String(p.default))+'</span>':'')+'</div>').join('')+'</div></details>';
      }).join(''));
  }
  if(n.type==='service' && d.schemaCoverage && (d.schemaCoverage.rows||[]).length){
    h+=section('coverage','Schema coverage — Liquibase → Service → Data object',
      schemaCoverageHtml(d.schemaCoverage, false));
  }
  else if(n.type==='service' && (d.columns||[]).length){
    h+=section('columns','Column mappings ('+d.columns.length+')','<div class="oplist">'+
      d.columns.map(c=>'<div class="oprow"><span>'+esc(c.name||'')+'</span>'+
        (c.columnName&&c.columnName!==c.name?'<span class="muted">'+esc(c.columnName)+'</span>':'')+
        (c.type?'<span class="mono fldtype">'+esc(c.type)+'</span>':'')+
        '</div>').join('')+'</div>');
  }
  if(n.type==='java' && (d.endpoints||[]).length){
    h+=section('endpoints','Endpoints served','<div class="oplist">'+
      d.endpoints.map(e=>'<div class="oprow"><span class="verb" style="color:'+color("endpoint")+'">'+esc(e.http)+'</span><span>'+esc(e.path)+'</span><span class="muted">'+esc(e.handler)+'() '+lineRef(n.file,e.line)+'</span></div>').join('')+'</div>');
  }
  if(n.type==='java' && (d.methods||[]).length){
    const cm=new Set(d.calledMethods||[]);
    h+=section('methods','Declared methods ('+d.methods.length+')','<div class="oplist">'+
      d.methods.slice(0,80).map(m=>'<div class="oprow"><span>'+esc(m.name)+'('+m.params+')</span><span class="muted">'+lineRef(n.file,m.line)+(cm.has(m.name)?'  ◀ called by models':'')+'</span></div>').join('')+'</div>');
  }
  if((n.type==='process') && (d.serviceTasks||[]).length){
    // One entry per task with everything it owns folded in — implementation, callee, result variable,
    // field injections, a jump to its parameter mappings. Replaces the old flat list that dumped the
    // raw class/expression string plus two more sections (Field injections, Parameters) repeating the
    // same task names.
    const st=d.serviceTasks.filter(s=>s.class||s.delegateExpression||s.expression||s.type);
    if(st.length) h+=section('svctasks','Service tasks ('+st.length+')',
      st.map(s=>{
        const label=s.name||s.id||'';
        const eid=(s.id&&s.id!==label)?'<span class="opid">'+esc(s.id)+'</span>':'';
        const ty=elementTerm('serviceTask', s.type||undefined);
        const impl=s.class||s.delegateExpression||s.expression||'';
        // short impl for the summary: class basename / trimmed expression — the full string lives in the body
        const short=impl?(s.class?s.class.split('.').pop():(impl.length>36?impl.slice(0,35)+'…':impl)):'';
        const rv=s.resultVariable
          ? '<span class="pd" style="color:var(--ok-text)">out</span> <span class="mono">'+paramSide(s.resultVariable)+'</span>' : '';
        const fields=s.fields||{}; const fks=Object.keys(fields);
        const callee=stCalleeChip(s);
        const pn=(d.ioParameters||[]).filter(p=>String(p.element)===String(s.id)).length;
        // body rows: only what the summary can't carry
        let b='';
        if(impl) b+='<div class="oprow" style="border:none"><span class="muted">impl</span><span class="mono" style="flex:1;word-break:break-all">'+esc(impl)+'</span>'+implLink(s)+'</div>';
        if(s.operationKey) b+='<div class="oprow" style="border:none"><span class="muted">operation</span><span class="mono">'+esc(s.operationKey)+'</span></div>';
        if(s.topic) b+='<div class="oprow" style="border:none"><span class="muted">topic</span>'+vlink('topic:'+s.topic, s.topic)+'</div>';
        if(s.caseDefinitionKey) b+='<div class="oprow" style="border:none"><span class="muted">starts case</span>'+vlink('case:'+s.caseDefinitionKey, s.caseDefinitionKey)+'</div>';
        if(callee) b+='<div class="opchips">'+callee+'</div>';
        if(fks.length){
          // a script field is code, not a one-line value — show it as one
          b+='<div class="parmgrid">'+fks.map(k=>{
            const v=fields[k]==null?'':String(fields[k]);
            return k==='script'
              ? '<div class="pc" style="display:block"><span class="pd">script</span><pre class="scriptbox" style="margin:4px 0 2px">'+esc(v)+'</pre></div>'
              : '<div class="pc"><span class="pn">'+esc(k)+'</span><span class="pt" style="max-width:60%;overflow:hidden;text-overflow:ellipsis" data-tip="'+esc(v)+'">'+esc(v)+'</span></div>';
          }).join('')+'</div>';
        }
        if(pn) b+='<div class="opchips"><button type="button" class="dgbtn" data-reveal-el="'+esc(String(s.id))+'">'+pn+' parameter mapping'+(pn>1?'s':'')+' ↓</button></div>';
        if(!b) return '<div class="op flat"'+dataEl(s.id)+'><span class="opname">'+esc(label)+'</span>'+eid+loc(s.id,s.name)+
          (short?'<span class="muted mono" style="overflow:hidden;text-overflow:ellipsis">'+esc(short)+'</span>':'')+rv+
          '<span class="opcount"></span><span class="opkey">'+ty+'</span></div>';
        return '<details class="op"'+dataEl(s.id)+'><summary><span class="opname">'+esc(label)+'</span>'+eid+loc(s.id,s.name)+
          (short?'<span class="muted mono" style="overflow:hidden;text-overflow:ellipsis;flex:none;max-width:32%">'+esc(short)+'</span>':'')+rv+
          '<span class="opcount">'+(fks.length?fks.length+' field'+(fks.length>1?'s':''):'')+'</span>'+
          '<span class="opkey">'+ty+'</span></summary>'+b+'</details>';
      }).join(''));
  }
  if(n.type==='dataObject' && (d.columns||[]).length){
    h+=section('columns','Field mappings ('+d.columns.length+')','<div class="oplist">'+
      d.columns.map(c=>'<div class="oprow"><span>'+esc(c.name)+'</span><span class="muted">'+esc(c.label||'')+'</span>'+
        (c.refDataObject?'<span class="vlink" data-id="'+enc('dataObject:'+c.refDataObject)+'" tabindex="0" role="link">→ '+esc(c.refDataObject)+(c.relationship?' ('+esc(c.relationship)+')':'')+'</span>':'')+
        (c.type?'<span class="mono fldtype">'+esc(c.type)+'</span>':'')+
        '</div>').join('')+'</div>');
  }
  if(n.type==='liquibase'){
    const a=d.authority||{};
    if(a.status==='superseded'){ const chips=(a.supersededBy||[]).map(k=>nodeChip('liquibase:'+k)).join('');
      h+='<div class="authnote authnote-old">⚠ Superseded revision — the live definition of <b>'+esc((d.effectiveTables||[]).join(', '))+'</b> is referenced elsewhere. These columns reflect an older revision of the same table.'+(chips?'<div>'+chips+'</div>':'')+'</div>'; }
    else if(a.status==='orphan'){
      h+='<div class="authnote authnote-orphan">⚠ Orphan changelog — no service or data object references it. It may be dead/legacy or referenced only at runtime.</div>'; }
  }
  if(n.type==='liquibase' && (d.columns||[]).length){
    const cov=d.coverage;                    // present only when a service references this changelog
    const inS=cov?new Set(cov.service||[]):null, inD=cov?new Set(cov.dataObject||[]):null;
    const stOf=k=>!inS.has(k)?'bad':(!inD.has(k)?'warn':'good');
    const stTitle={bad:'not mapped by any service',warn:'mapped in service, but no data object field',good:'mapped through to a data object'};
    const byT={}; d.columns.forEach(c=>{ (byT[c.table||'(table)']=byT[c.table||'(table)']||[]).push(c); });
    let b='';
    if(cov) b+='<div class="covlegend">'+
      '<span><span class="covdot" style="background:'+covColor('bad')+'"></span>not in service</span>'+
      '<span><span class="covdot" style="background:'+covColor('warn')+'"></span>not in data object</span>'+
      '<span><span class="covdot" style="background:'+covColor('good')+'"></span>mapped through</span></div>';
    Object.keys(byT).forEach(t=>{
      b+='<div style="margin:6px 0 12px"><div class="muted mono" style="margin-bottom:4px">'+esc(t)+'</div><div class="oplist">'+
        byT[t].map(c=>{ const st=cov?stOf(looseCol(c.name)):null;
          return '<div class="oprow'+(st==='bad'?' cov-bad':st==='warn'?' cov-warn':'')+'">'+
          (cov?'<span class="covdot" title="'+stTitle[st]+'" style="background:'+covColor(st)+'"></span>':'')+
          '<span>'+esc(c.name)+'</span>'+
          (c.type?'<span class="mono fldtype">'+esc(c.type)+'</span>':'')+
          '</div>'; }).join('')+'</div></div>';
    });
    h+=section('columns','Columns ('+d.columns.length+')'+(cov?' — mapping coverage':''), b);
  }
  if((n.type==='expression'||n.type==='binding') && (d.problems||[]).length){
    h+=section('problems','Problems ('+d.problems.length+')','<div class="oplist">'+
      d.problems.map(p=>{
        const isErr=p.severity==='error';
        const col=isErr?color('invalidExpr'):color('suspectExpr');
        const snip=p.snippet||'';
        return '<div class="oprow"><span class="verb" style="color:'+col+'">'+(isErr?'error':'warning')+'</span>'+
          '<span style="flex:1">'+esc(p.message)+'</span>'+
          (snip?'<span class="mono snip">'+esc(snip)+'</span>':'')+
          '</div>';
      }).join('')+'</div>');
  }
  if((n.type==='expression'||n.type==='binding'||n.type==='customFunction'||n.type==='serviceOperation') && (d.usedBy||[]).length){
    h+=section('usedby','Used by ('+d.usedBy.length+')','<div class="nodechips">'+d.usedBy.map(nodeChip).join('')+'</div>');
  }
  if(n.type==='serviceOperation' && !(d.usedBy||[]).length){
    h+='<div class="authnote authnote-orphan">No service button, data-object field or CMMN service mapping in the scanned models calls this operation.</div>';
  }
  // a frontend binding links to the custom function(s) it calls; a custom function links back to the
  // exact bindings that call it (in addition to the forms/models under "Used by").
  if(n.type==='binding' && (d.calls||[]).length){
    h+=section('calls','Calls custom functions 🧩 ('+d.calls.length+')','<div class="nodechips">'+d.calls.map(nodeChip).join('')+'</div>');
  }
  if(n.type==='customFunction' && (d.bindings||[]).length){
    h+=section('inbindings','Called in bindings ('+d.bindings.length+')','<div class="nodechips">'+d.bindings.map(nodeChip).join('')+'</div>');
  }
  if(n.type==='customFunction' && !(d.usedBy||[]).length){
    h+='<div class="authnote authnote-orphan">Registered via <b>externals.additionalData</b> but no <code>{{…}}</code> binding in the scanned models calls it.</div>';
  }
  // Written where, read where — the two lists the "never read" verdict rests on, so a reader can check
  // the reasoning instead of taking the verdict on faith. Each row jumps to its element in the model.
  if(n.type==='variable' && ((d.writes||[]).length||(d.reads||[]).length)){
    const siteRow=(s,verb,col)=>'<div class="oprow">'+
      '<span class="verb" style="color:var('+col+')">'+verb+'</span>'+
      '<span style="flex:1">'+nodeChip(s.model)+
        elJumpHtml(s.model, s.element, s.elementName||s.element)+
        (s.scope?'<span class="pd">in scope</span>'+nodeChip(s.scope):'')+
        (s.scopeUnresolved?'<span class="pd" data-tip="The called model is not part of this project, so '+
          'Atlas cannot tell whether anything there reads the variable.">callee not in project</span>':'')+
      '</span>'+
      termHtml('via', s.via, 'pt')+'</div>';
    h+=section('rw','Written / read ('+(d.writeCount||0)+' / '+(d.readCount||0)+')','<div class="oplist">'+
      (d.writes||[]).map(s=>siteRow(s,'writes','--bad-text')).join('')+
      (d.reads||[]).map(s=>siteRow(s, s.guess?'≈ reads':'reads', s.guess?'--ink-faint':'--ok-text')).join('')+
      '</div>');
  }
  // The data-flow view of a variable: every in/out mapping that reads or writes it, and where.
  if(n.type==='variable' && (d.ioParams||[]).length){
    h+=section('passedas','Passed as parameter ('+paramSummary(d.ioParams)+')','<div class="oplist">'+
      d.ioParams.map(p=>'<div class="oprow">'+
        '<span class="verb" style="color:var('+(PDIR_COLOR[p.dir]||'--ink-faint')+')">'+esc(p.dir)+'</span>'+
        '<span style="flex:1">'+nodeChip(p.model)+
          (p.element?'<span class="mono" style="color:var(--ink-faint)"> @'+esc(p.element)+'</span>':'')+'</span>'+
        '<span class="mono" style="font-size:var(--text-2xs)">'+paramFlowHtml(p)+'</span>'+
      '</div>').join('')+'</div>');
  }
  // The scripts that touch this variable. Each row jumps to that script's own row in its model — the
  // answer to "where is this variable actually set?" used to require reading every script by hand.
  if(n.type==='variable' && (d.scriptSites||[]).length){
    h+=section('inscripts','In scripts ('+d.scriptSites.length+')','<div class="oplist">'+
      d.scriptSites.map(s=>'<div class="oprow">'+
        '<span class="verb" style="color:var('+(s.api?'--ok-text':'--ink-faint')+')">'+
          (s.api?'sets / reads':'≈ reads')+'</span>'+
        '<span style="flex:1">'+nodeChip(s.model)+
          elJumpHtml(s.model, s.element, s.elementName||s.element)+
        '</span>'+
        (s.elementType?'<span class="pt">'+esc(s.elementType)+'</span>':'')+
      '</div>').join('')+'</div>');
  }
  if((n.type==='variable'||n.type==='string') && (d.usages||[]).length){
    let b='';
    d.usages.forEach(u=>{
      b+='<div style="margin:6px 0 12px">'+nodeChip(u.model)+
         '<div class="oplist" style="margin-top:5px">'+
         (u.snippets||[]).map(s=>'<div class="oprow"><span class="mono">'+esc(s)+'</span></div>').join('')+
         '</div></div>';
    });
    h+=section('usedin','Used in ('+d.usages.length+' models) — effective occurrences', b);
  }
  // Reverse direction: a model lists all the variables/expressions/strings it uses (collapsible).
  // Derived from the artifact nodes' `usedBy` (see usesIndex) — the payload carries no `_uses`.
  const uses=usesOf(n.id);
  if(uses){
    const ord=[['variable','Variables'],['expression','Backend expressions ${ }'],
               ['binding','Frontend bindings {{ }}'],['customFunction','Custom functions 🧩'],
               ['serviceOperation','Service operations'],['string','String literals']];
    let parts='';
    ord.forEach(([t,lbl])=>{ const ids=uses[t]; if(ids&&ids.length)
      parts+='<details class="uses"><summary>'+lbl+' ('+ids.length+')</summary><div class="nodechips">'+ids.map(nodeChip).join('')+'</div></details>'; });
    h+=section('uses','Uses — variables &amp; expressions', parts);
  }
  return h;
}

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
const GRAPH_MAX_NEIGHBORS = 26;
function neighborhoodSvg(n){
  // Collect unique neighbors with direction + relation (a node can appear on both sides).
  const seen=new Map();
  (outM.get(n.id)||[]).forEach(e=>{ if(byId.get(e.id)&&!seen.has(e.id)) seen.set(e.id,{id:e.id,rel:e.rel,dir:'out'}); });
  (incM.get(n.id)||[]).forEach(e=>{ if(byId.get(e.id)&&!seen.has(e.id)) seen.set(e.id,{id:e.id,rel:e.rel,dir:'in'}); });
  const all=[...seen.values()];
  if(!all.length) return '';
  const shown=all.slice(0,GRAPH_MAX_NEIGHBORS);
  const W=680,H=340,CX=W/2,CY=H/2,RX=CX-130,RY=CY-40;
  const trunc=(s,len)=>s.length>len?s.slice(0,len-1)+'…':s;
  let g='';
  shown.forEach((e,i)=>{
    const nn=byId.get(e.id);
    const a=-Math.PI/2 + i*2*Math.PI/shown.length;
    const x=CX+RX*Math.cos(a), y=CY+RY*Math.sin(a);
    const dash=e.dir==='in'?' stroke-dasharray="4 3"':'';
    const dim=(e.sus||e.dyn)?' stroke-opacity="0.45"':'';
    const flagTxt=e.sus?' (suspect)':e.dyn?' (dynamic)':'';
    // tooltips via data-tip (the DOM bubble), not <title> children — SVG-native tooltips never
    // render in the embedded JCEF viewer
    const relTerm=term('rel', e.rel).label;
    g+='<line x1="'+CX+'" y1="'+CY+'" x2="'+x.toFixed(1)+'" y2="'+y.toFixed(1)+'" stroke="var(--line2)" stroke-width="1"'+dash+dim+' data-tip="'+esc(relTerm+flagTxt+(e.dir==='in'?' (incoming)':''))+'"/>';
    const anchor=Math.cos(a)>0.25?'start':Math.cos(a)<-0.25?'end':'middle';
    const tx=x+(anchor==='start'?9:anchor==='end'?-9:0), ty=y+(anchor==='middle'?(Math.sin(a)>0?16:-10):4);
    g+='<g class="gn" data-id="'+enc(e.id)+'" tabindex="0" role="link" style="cursor:pointer"'+
       ' data-tip="'+esc(nn.label+' — '+relTerm)+'" aria-label="'+esc(nn.label+' — '+relTerm)+'">'+
       '<circle cx="'+x.toFixed(1)+'" cy="'+y.toFixed(1)+'" r="5" fill="'+nodeColor(nn)+'"/>'+
       '<text x="'+tx.toFixed(1)+'" y="'+ty.toFixed(1)+'" text-anchor="'+anchor+'" font-size="10" font-family="var(--mono)" fill="var(--ink-dim)">'+esc(trunc(nn.label,26))+'</text></g>';
  });
  // center node on top of the lines
  g+='<circle cx="'+CX+'" cy="'+CY+'" r="8" fill="'+nodeColor(n)+'" stroke="var(--panel)" stroke-width="2"/>'+
     '<text x="'+CX+'" y="'+(CY+22)+'" text-anchor="middle" font-size="11" font-weight="600" font-family="var(--mono)" fill="var(--ink)">'+esc(trunc(n.label,32))+'</text>';
  const more=all.length>shown.length?'<div class="muted nbmore">showing '+shown.length+' of '+all.length+' neighbors — the full list is below</div>':'';
  return '<details class="uses" open><summary>Neighborhood — solid: uses, dashed: used by</summary>'+
    '<div style="padding:4px 10px 8px">'+more+
    '<svg viewBox="0 0 '+W+' '+H+'" style="width:100%;max-width:820px;display:block" role="img" aria-label="Relationship graph of '+esc(n.label)+'">'+g+'</svg></div></details>';
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
    return '<div class="oprow kvrow"><span class="muted kvk">'+
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
      return '<div class="oprow kvrow">'+kvValueHtml(v.slice(0,KV_MAX_ROWS).join(', '))+'</div>';
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
      'switch with <b>'+alt+'1…9</b> or <b>'+alt+'←→</b> · close with <b>'+alt+'W</b>.</div></div>';
    return;
  }
  const n=byId.get(state.sel);
  // Recording wrapper: every data key describe()/diagramView()/detailExtra() (and their helpers —
  // elementNames, elementRecords, caseCriteria, paramSection all receive this node) actually reads is
  // marked consumed; the "Other attributes" fallback below renders the rest.
  const consumed=new Set();
  const rn={...n, data:new Proxy(n.data||{}, {
    get:(t,k)=>{ if(typeof k==='string') consumed.add(k); return t[k]; },
  })};
  const out=groupRels(outM.get(n.id)), inc=groupRels(incM.get(n.id));
  let h='';
  const kindHint=term('type', n.type).hint;
  h+='<div class="dhead">'+
     '<span class="dkind"'+(kindHint?' title="'+esc(kindHint)+'"':'')+'>'+nodeIcon(n)+esc(nodeKind(n))+'</span>'+
     '<span class="dhead-sp"></span>'+
     (_navCount>1?'<button id="back">'+uiIcon('back')+'back</button>':'')+
     '<button id="sectall" title="Expand or collapse every section on this page">'+uiIcon('expand')+'expand all</button>'+
     '<button id="permalink" title="Copy a shareable link to this node">'+uiIcon('link')+'copy link</button></div>';
  h+='<div class="dbody">';
  h+='<div class="dtitle">'+esc(n.label)+authBadge(n)+'</div>';
  h+='<div class="dkey mono">'+esc(n.key)+copyBtn(n.key,'key')+'</div>';
  if(n.file) h+='<div class="dfile" title="click to copy" data-copy="'+enc(n.file)+'"><span class="fp">'+esc(n.file)+'</span>'+copyBtn(n.file,'path')+openBtn(n.file)+'</div>';
  const rows=describe(rn);
  if(rows.length){ h+='<div class="grid">'+rows.map(r=>{
      const v=r[1], isHtml=v&&v.html!==undefined;
      const shown=isHtml?v.html:esc(String(v));
      // auto-copy scalar values; link rows opt in via a `copy:` payload. Skip counts (numbers).
      const ct=isHtml?(v.copy!=null?String(v.copy):null):(typeof v==='number'?null:String(v));
      return '<div class="cell"><div class="k">'+esc(r[0])+'</div><div class="v mono">'+shown+copyBtn(ct,r[0])+'</div></div>';
    }).join('')+'</div>'; }
  h+=diagramView(rn);
  h+=neighborhoodSvg(n);
  h+=detailExtra(rn);
  // Whatever no renderer above consumed. Identity fields live in the header; HAY_SKIP is the same
  // bookkeeping the search index skips.
  {
    const skip=new Set([...HAY_SKIP,'key','name','file','description','modelType','type','label']);
    const rest=Object.keys(n.data||{}).filter(k=>!consumed.has(k)&&!skip.has(k)&&kvTruthy((n.data||{})[k]));
    if(rest.length) h+=section('otherattrs','Other attributes ('+rest.length+')',
      rest.map(k=>kvEntry(k,(n.data||{})[k],0)).join(''));
  }
  // The gesture is stated where the reference chips actually are. Walking a fan of references is the
  // case it exists for: without it, every chip you follow costs you the node you started from.
  const relHint='<div class="relhint">click follows · <b>'+MODK+'-click</b> or middle-click opens a tab</div>';
  const relBody=g=>relHint+Object.keys(g).sort().map(rel=>
    '<div class="relgrp"><div class="lab">'+termHtml('rel', rel)+'</div><div class="nodechips">'+
    [...g[rel].values()].map(e=>nodeChip(e.id,e)).join('')+'</div></div>').join('');
  // outgoing
  const ok=Object.keys(out).sort();
  if(ok.length) h+=section('rels-out','Uses / references ('+ok.reduce((a,k)=>a+out[k].size,0)+')', relBody(out));
  // incoming
  const ik=Object.keys(inc).sort();
  if(ik.length) h+=section('rels-in','Used by / referenced from ('+ik.reduce((a,k)=>a+inc[k].size,0)+')', relBody(inc));
  if(!ok.length && !ik.length) h+='<p class="muted" style="margin-top:18px">No relationships recorded for this node.</p>';
  h+='</div>';
  det.innerHTML=h;
  det.scrollTop=0;
  const b=document.getElementById('back'); if(b) b.onclick=()=>history.back();
  // Remember every section's open state, and offer one control to flip them all at once.
  const sects=[...det.querySelectorAll('details.sect')];
  sects.forEach(s=>s.addEventListener('toggle',()=>sectRemember(dec(s.dataset.sect), s.open)));
  const sa=document.getElementById('sectall');
  if(sa){
    const sync=()=>{ sa.textContent = sects.every(s=>s.open) ? '⇕ collapse all' : '⇕ expand all'; };
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
    const done=()=>{ pl.textContent='✓ link copied'; setTimeout(()=>{ pl.textContent='🔗 copy link'; },1500); };
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
  det.querySelectorAll('.cpy').forEach(b=>{
    b.onclick=e=>{ e.stopPropagation();   // don't navigate the chip/link this button sits inside
      atlasCopy(dec(b.dataset.copy), ()=>{ if(b.dataset.busy) return; b.dataset.busy='1';
        const old=b.innerHTML; b.classList.add('ok'); b.innerHTML=CPY_OK_SVG;
        setTimeout(()=>{ b.classList.remove('ok'); b.innerHTML=old; delete b.dataset.busy; },1200); }); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' ') e.stopPropagation(); };   // keep Enter/Space from the parent's nav
  });
  wireOpenButtons(det);                 // ↗ open the file / file:line in the IDE (no-ops in a browser)
  // ⌖ locate-on-diagram buttons; preventDefault keeps a click inside a <summary> from toggling it
  det.querySelectorAll('.dgloc').forEach(b=>{
    b.onclick=e=>{ e.preventDefault(); e.stopPropagation(); locateOnDiagram(det, b.dataset.elRef, b.dataset.elName); };
    b.onkeydown=e=>{ if(e.key==='Enter'||e.key===' ') e.stopPropagation(); };
  });
  // "N parameter mappings ↓" inside a service task — jumps to that element's mapping group
  det.querySelectorAll('[data-reveal-el]').forEach(b=>{
    b.onclick=e=>{ e.stopPropagation(); revealByEl(det, b.dataset.revealEl); };
  });
  wireParamFilter(det);
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
  const tryFit=()=>{ if(!z._fitted && view.clientWidth>0){ z.fit(); z._fitted=true; } };
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
function revealByEl(det, elId){
  const rows=[...det.querySelectorAll('[data-el]')]
    .filter(x=>x.dataset.el===String(elId) && !x.closest('.dgview'));
  if(!rows.length) return false;
  det.querySelectorAll('.hit').forEach(x=>x.classList.remove('hit'));
  rows.forEach(el=>{
    for(let p=el.parentElement; p&&p!==det; p=p.parentElement){ if(p.tagName==='DETAILS') p.open=true; }
    if(el.tagName==='DETAILS') el.open=true;
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
    const g=t&&t.closest?t.closest('[data-el]'):null;
    if(!g||!view.contains(g)){ hideDgCard(); dgSelect(view, null); return; }
    dgSelect(view, g);
    showDgCard(view, g, e, inModal);
  });
  // Shapes are focusable (the renderer stamps tabindex/role): Enter or Space on one is a click.
  view.addEventListener('keydown', e=>{
    if(e.key!=='Enter' && e.key!==' ') return;
    const g=e.target&&e.target.closest?e.target.closest('[data-el]'):null;
    if(!g||!view.contains(g)) return;
    e.preventDefault();
    g.dispatchEvent(new MouseEvent('click',{bubbles:true}));
  });
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
    // …and the other half of a `&e=` link: the element on the canvas, not only its rows.
    locateOnDiagram(det, state.focusEl);
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
  if(!rows.length) rows=pick('details.op, .dgcond, .dmntab td');
  if(!rows.length) rows=pick('.cell');
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
  // One tab is no choice — showing a strip for it would be chrome that never earns its space.
  if(state.tabs.length<2){ bar.hidden=true; bar.innerHTML=''; return; }
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
function palRenderFacets(counts, total, typeCounts, parsed){
  const bar=document.getElementById('palfacets');
  if(!bar) return;
  const pending=parsed&&parsed.pending?PAL_FACET_HELP.find(f=>f.k===parsed.pending):null;
  if(!counts){
    // No result set means no count row — but an empty palette is exactly when the typed filters are
    // worth teaching, and this bar is where their live chips will appear once one is used. A chip
    // inserts its prefix; a facet typed through the colon (`label:`) highlights its chip and says
    // what it is waiting for, instead of degrading into a search for the word "label".
    let s='<div class="pal-frow pal-syn">'+
      (pending?'<span class="pal-pend">'+esc(pending.t)+' now type its value — quote a multi-word one</span>'
              :'<span class="pal-in">narrow</span>');
    PAL_FACET_HELP.forEach(f=>{
      s+='<button class="pchip'+(pending===f?' on':'')+'" type="button" data-syn="'+esc(f.t)+'" title="'+esc(f.hint)+'">'+
         esc(f.t)+'<span class="pchipn">'+esc(f.gloss)+'</span></button>';
    });
    if(!pending) s+='<span class="pal-synq" title="Quotes match contiguously — alone as a phrase, after a facet as its value">"…" exact</span>';
    bar.hidden=false; bar.innerHTML=s+'</div>';
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
  bar.innerHTML=h;
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
    // ⌘/Ctrl+click toggles, Shift+click extends — same convention as the browse list.
    if(modKey(ev)){
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
    rebuildAdj(); paint();
    if(state.view==='browse') renderDetail();
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
window.__atlasSetIdeTheme=t=>{
  window.__ideTheme=(t==='light'||t==='dark')?t:null;
  applyThemePref();
};
function themePref(){ let p=null; try{ p=localStorage.getItem('atlas-theme'); }catch(e){} return p||(window.__ideTheme?'auto':'light'); }
function applyThemePref(){
  const pref=themePref();
  const sys=window.__ideTheme||(matchMedia('(prefers-color-scheme: light)').matches?'light':'dark');
  const theme = pref==='auto'?sys:pref;
  document.documentElement.dataset.theme = theme;
  const mt=document.querySelector('meta[name=theme-color]');
  if(mt) mt.content = theme==='dark'?'#0c141c':'#ffffff';
  document.querySelectorAll('[data-theme-btn]').forEach(b=>{
    b.textContent = pref==='auto'?'◐':(pref==='light'?'☀':'☾');
    const tip='Theme: '+pref+(pref==='auto'&&window.__ideTheme?' (follows IDE)':'')+' — click to switch';
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
