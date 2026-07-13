// Data arrives as a JSON island (<script type="application/json" id="atlas-data">):
// JSON.parse is faster than a JS literal for large payloads and needs no JS escaping.
const DATA = JSON.parse(document.getElementById('atlas-data').textContent);
const nodes = DATA.nodes, edges = DATA.edges;
const byId = new Map(nodes.map(n => [n.id, n]));
const diags = DATA.diagnostics || [];
const cfns = DATA.customFunctions;
const cfnDiags = (cfns && cfns.diagnostics) || [];
const TM = {
  app:['Apps','Models'],process:['Processes','Models'],case:['Cases','Models'],
  decision:['Decisions','Models'],form:['Forms','Models'],page:['Pages','Models'],
  dataObject:['Data objects','Models'],dataDictionary:['Data dictionaries','Models'],
  masterData:['Master data','Models'],
  service:['Service models','Integration'],serviceOperation:['Service operations','Integration'],agent:['Agents / bots','Integration'],
  channel:['Channels','Integration'],event:['Events','Integration'],knowledgeBase:['Knowledge bases','Integration'],
  signal:['Signals','Integration'],message:['Messages','Integration'],error:['Errors','Integration'],
  escalation:['Escalations','Integration'],topic:['External worker topics','Integration'],
  endpoint:['REST endpoints','Code'],java:['Java classes','Code'],method:['Java methods','Code'],liquibase:['Liquibase changelogs','Code'],
  action:['Actions','Integration'],bot:['Bots','Integration'],
  query:['Queries','Other'],template:['Templates','Other'],sequence:['Sequences','Other'],
  document:['Documents','Other'],variableExtractor:['Variable extractors','Other'],
  sla:['SLAs','Other'],dashboardComponent:['Dashboard widgets','Other'],
  securityPolicy:['Security policies','Access'],group:['User groups','Access'],
  variable:['Variables','Variables'],
  expression:['Backend expressions ${ }','Expressions'],binding:['Frontend bindings {{ }}','Expressions'],
  string:['String literals','Expressions'],customFunction:['Custom functions 🧩','Expressions'],
  external:['External / library','Other'],
};
const SECTIONS = ['Models','Integration','Code','Expressions','Variables','Access','Other'];
// Colors are emitted as var() references, not resolved values: the browser resolves them
// at paint time, so a theme switch restyles everything without any re-render (and there is
// no getComputedStyle per node, which used to force a style recalculation in large lists).
const color = t => 'var(--c-'+t+', #79848f)';
const covColor = k => 'var(--cov-'+k+', #79848f)';
const debounce = (fn,ms) => { let t; return function(){ clearTimeout(t); t=setTimeout(()=>fn.apply(this,arguments),ms); }; };
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
let state = {view:'overview', cat:null, sel:null, filter:'', sort:'name'};

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
        id:'java::'+r, label:'Java · '+r, sec:'Code', color:color('java'), count:roles[r],
        match:n=>n.type==='java' && (n.data.roles||[]).includes(r)}));
    } else if(t==='variable'){
      // group variables by the model type(s) that use them (process / form / case / java …)
      const scopes = {};
      byType.variable.forEach(n=>(n.data.scopes||[]).forEach(s=>scopes[s]=(scopes[s]||0)+1));
      Object.keys(scopes).sort().forEach(s=>cats.push({
        id:'variable::'+s, label:'Variable · '+s, sec:'Variables',
        color:color('variable'), count:scopes[s], match:n=>n.type==='variable' && (n.data.scopes||[]).includes(s)}));
    } else if(t==='external'){
      // external nodes are not all "library": split out Flowable platform API calls
      // (endpoints.*) and in-app navigation routes (#/...) from real third-party deps.
      [{id:'external::api',  label:'Flowable API',        sec:'Integration', color:color('endpoint'), match:n=>n.type==='external'&&n.data.flowableApi},
       {id:'external::route',label:'Navigation · routes', sec:'Other',       color:color('page'),     match:n=>n.type==='external'&&n.data.route},
       {id:'external::missing',label:'Missing model refs',sec:'Other',       color:color('external'), match:n=>n.type==='external'&&n.data.missingModel},
       {id:'external::lib',  label:'External / library',  sec:'Other',       color:color('external'), match:n=>n.type==='external'&&!n.data.flowableApi&&!n.data.route&&!n.data.missingModel}
      ].forEach(c=>{ const count=byType.external.filter(c.match).length; if(count) cats.push(Object.assign({count}, c)); });
    } else {
      const m = TM[t]||[t,'Other'];
      cats.push({id:t,label:m[0],sec:m[1],color:color(t),count:byType[t].length,match:n=>n.type===t});
    }
  });
  // a review list: forms that nothing links to (orphaned UI models worth pruning)
  const unusedForms = nodes.filter(isUnusedForm);
  if(unusedForms.length) cats.push({id:'unused-form', label:'Forms · unused', sec:'Models',
    color:color('form'), count:unusedForms.length, match:isUnusedForm});
  // Review lists for flagged expressions/bindings. Structural syntax errors make an
  // expression *invalid*; catalog findings (unknown function/namespace — the catalog may
  // simply not know a project-registered function) only make it *suspect*.
  const isExprN = n => n.type==='expression'||n.type==='binding';
  const hasErr = n => isExprN(n) && (n.data.problems||[]).some(p=>p.severity==='error');
  const hasWarnOnly = n => isExprN(n) && (n.data.problems||[]).length && !(n.data.problems||[]).some(p=>p.severity==='error');
  const invalidExprs = nodes.filter(hasErr);
  if(invalidExprs.length) cats.push({id:'invalid-expr', label:'Invalid — syntax ⚠', sec:'Expressions',
    color:color('invalidExpr'), count:invalidExprs.length, match:hasErr});
  const suspectExprs = nodes.filter(hasWarnOnly);
  if(suspectExprs.length) cats.push({id:'suspect-expr', label:'Suspect — review', sec:'Expressions',
    color:color('suspectExpr'), count:suspectExprs.length, match:hasWarnOnly});
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
  const isExprN = n => n.type==='expression'||n.type==='binding';
  let invalidExpr=0, suspectExpr=0, unusedForms=0, changelogIssues=0, schemaGaps=0,
      unusedOps=0, unusedFns=0, totalExprs=0, totalForms=0, totalChangelogs=0,
      totalCovServices=0, totalOps=0, totalFns=0;
  nodes.forEach(n=>{
    const d=n.data||{};
    if(isExprN(n)){ totalExprs++; const pr=d.problems||[];
      if(pr.length){ if(pr.some(p=>p.severity==='error')) invalidExpr++; else suspectExpr++; } }
    else if(n.type==='form'){ totalForms++; if(isUnusedForm(n)) unusedForms++; }
    else if(n.type==='liquibase'){ totalChangelogs++; const st=(d.authority||{}).status;
      if(st==='orphan'||st==='superseded') changelogIssues++; }
    else if(n.type==='service'){ const c=(d.schemaCoverage||{}).counts;
      if(c){ totalCovServices++; schemaGaps+=(c.noService||0)+(c.noDataObject||0); } }
    else if(n.type==='serviceOperation'){ totalOps++; if(!(d.usedBy||[]).length) unusedOps++; }
    else if(n.type==='customFunction'){ totalFns++; if(!(d.usedBy||[]).length) unusedFns++; }
  });
  const apps = nodes.filter(n=>n.type==='app')
    .map(a=>({id:a.id, models:containsByApp.get(a.id)||0, groups:openAppByApp.get(a.id)||0}))
    .sort((a,b)=>b.models-a.models);
  INSIGHTS = { indeg, hotspots, apps, entryPoints,
    totalExprs, totalForms, totalChangelogs, totalCovServices, totalOps, totalFns,
    health: { parseIssues: diags.length+cfnDiags.length, invalidExpr, suspectExpr, unusedForms,
              changelogIssues, schemaGaps, unusedOps, unusedFns } };
}

// ---------- router — the hash is the single source of truth and the history ----------
// ''              -> overview (default)
// #/overview      -> overview
// #/browse/<cat>  -> browse, category list without selection
// #<nodeId>       -> legacy permalink format: browse with that node selected (kept so
//                    every previously copied link keeps working). enc() escapes '/', so
//                    dispatching on the RAW leading '/' before decoding is unambiguous.
function parseHash(){
  const raw = location.hash.slice(1);
  if(!raw || raw==='/overview') return {view:'overview'};
  if(raw.indexOf('/browse/')===0){
    const cat = dec(raw.slice(8));
    return CATS.some(c=>c.id===cat) ? {view:'browse', cat} : {view:'overview'};
  }
  if(raw.charAt(0)==='/') return {view:'overview'};      // unknown route
  const id = dec(raw);
  return byId.get(id) ? {view:'browse', sel:id} : {view:'overview'};
}
function showView(v){
  document.getElementById('view-overview').hidden = v!=='overview';
  document.getElementById('view-browse').hidden = v!=='browse';
}
let _navCount = 0;
function route(){
  closePalette();
  _navCount++;
  const r = parseHash();
  if(r.view==='overview'){
    state.view='overview'; state.sel=null;
    showView('overview'); renderDashboard();
    renderSidebarActive(); renderCrumbs();
  } else if(r.sel){
    applySelection(r.sel);                                // handles view/list/detail/crumbs
  } else {
    state.view='browse';
    if(state.cat!==r.cat){ state.cat=r.cat; state.filter=''; }
    state.sel=null;
    showView('browse'); renderList(); renderDetail();
    renderSidebarActive(); renderCrumbs();
  }
}

// ---------- sidebar ----------
function renderSidebar(){
  const nav = document.getElementById('nav'); nav.innerHTML='';
  const mkItem = (html, title) => {
    const el=document.createElement('div');
    el.className='side-item'; el.setAttribute('role','button'); el.tabIndex=0;
    el.setAttribute('aria-pressed','false'); el.title=title; el.innerHTML=html;
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
  const ov = mkItem('<span class="dot" style="background:var(--accent)"></span><span class="lbl">Overview</span>','Overview');
  ov.dataset.route='/overview';
  ov.onclick=()=>{ location.hash='/overview'; };
  nav.appendChild(ov);
  let cur='';
  CATS.forEach(c=>{
    if(c.sec!==cur){ cur=c.sec; const h=document.createElement('div'); h.className='side-group'; h.textContent=cur; nav.appendChild(h); }
    const el = mkItem('<span class="dot" style="background:'+c.color+'"></span><span class="lbl">'+esc(c.label)+'</span><span class="n">'+c.count+'</span>',
                      c.label+' ('+c.count+')');
    el.dataset.cat=c.id;
    el.onclick=()=>{ location.hash='/browse/'+enc(c.id); };
    nav.appendChild(el);
  });
  // footer warning chip — routes to the dashboard and reveals the diagnostics list
  if(diags.length+cfnDiags.length){
    const chip=document.getElementById('diagchip');
    const n=diags.length+cfnDiags.length;
    chip.hidden=false;
    chip.innerHTML='⚠<span class="wtxt">&nbsp;'+n+' parse issue'+(n>1?'s':'')+'</span>';
    chip.title=n+' parse issue'+(n>1?'s':'')+' — files the generator could not fully analyze';
    chip.onclick=()=>{
      _revealDiag=true;
      if(state.view==='overview') renderDashboard(); else location.hash='/overview';
    };
  }
}
function renderSidebarActive(){
  document.querySelectorAll('#nav .side-item').forEach(el=>{
    const on = el.dataset.route ? state.view==='overview'
                                : (state.view==='browse' && state.cat===el.dataset.cat);
    el.classList.toggle('on', on);
    el.setAttribute('aria-pressed', on?'true':'false');
  });
}

// ---------- topbar breadcrumbs ----------
function renderCrumbs(){
  const c=document.getElementById('crumbs');
  const sep='<span class="crumb-sep">/</span>';
  const link=(txt,href)=>'<a class="crumb" href="'+href+'">'+esc(txt)+'</a>';
  const cur=(txt)=>'<span class="crumb cur">'+esc(txt)+'</span>';
  let h, title;
  if(state.view==='overview'){
    h=link(DATA.project,'#/overview')+sep+cur('Overview');
    title='Flowable Atlas — '+DATA.project;
  } else {
    const cat=CATS.find(x=>x.id===state.cat);
    const n=state.sel&&byId.get(state.sel);
    h=link(DATA.project,'#/overview');
    if(cat) h+=sep+(n?link(cat.label,'#/browse/'+enc(cat.id)):cur(cat.label));
    if(n) h+=sep+cur(n.label);
    title=(n?n.label:(cat?cat.label:'Browse'))+' — Flowable Atlas';
  }
  c.innerHTML=h;
  document.title=title;
}

// ---------- dashboard (#/overview) ----------
let _revealDiag=false;
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
  // health
  const cards=[
    {k:'parseIssues', label:'Parse issues', bad:true, diag:true,
     sub:c=>c?'files the analyzer could not fully read':'all files analyzed cleanly', show:true},
    {k:'invalidExpr', label:'Invalid expressions', bad:true, cat:'invalid-expr',
     sub:c=>c?'syntax errors in ${ } / {{ }}':'no syntax errors', show:INSIGHTS.totalExprs>0},
    {k:'suspectExpr', label:'Suspect expressions', cat:'suspect-expr',
     sub:c=>c?'flagged for review by the catalog':'nothing flagged', show:INSIGHTS.totalExprs>0},
    {k:'unusedForms', label:'Unused forms', cat:'unused-form',
     sub:c=>c?'no model links to them':'every form is referenced', show:INSIGHTS.totalForms>0},
    {k:'changelogIssues', label:'Changelog issues', cat:'liquibase',
     sub:c=>c?'orphan or superseded changelogs':'all changelogs are authoritative', show:INSIGHTS.totalChangelogs>0},
    {k:'schemaGaps', label:'Schema gaps', bad:true, cat:'service',
     sub:c=>c?'columns not mapped through Liquibase → service → data object':'all columns mapped through', show:INSIGHTS.totalCovServices>0},
    {k:'unusedOps', label:'Unused operations', cat:'serviceOperation',
     sub:c=>c?c+' of '+INSIGHTS.totalOps+' operations are never called from a model':'every operation is used', show:INSIGHTS.totalOps>0},
    {k:'unusedFns', label:'Unused custom functions', cat:'customFunction',
     sub:c=>c?c+' of '+INSIGHTS.totalFns+' functions are never called':'every function is used', show:INSIGHTS.totalFns>0},
  ].filter(c=>c.show);
  if(cards.length){
    h+='<div class="seclabel">Health</div><div class="health">';
    cards.forEach(c=>{
      const n=H[c.k];
      const tone=n===0?'ok':(c.bad?'bad':'warn');
      const click=n>0&&(c.diag||(c.cat&&CATS.some(x=>x.id===c.cat)));
      const attrs=click?(c.diag?' data-diag="1"':' data-cat="'+esc(c.cat)+'"')+' role="button" tabindex="0"':'';
      h+='<div class="hcard tone-'+tone+(click?' click':'')+'"'+attrs+'>'+
         '<div class="mk">'+c.label+'</div><div class="mv">'+n+'</div>'+
         '<div class="ms">'+esc(c.sub(n))+'</div></div>';
    });
    h+='</div>';
  }
  // inline diagnostics list (replaces the old floating panel)
  if(diags.length+cfnDiags.length){
    h+='<details id="diaglist"><summary>'+(diags.length+cfnDiags.length)+' parse issue'+
       (diags.length+cfnDiags.length>1?'s':'')+' — the map may be incomplete</summary>';
    h+=diags.map(d=>'<div class="dp-row"><span class="dp-kind">'+esc(d.kind)+'</span>'+
      '<span class="dp-path mono">'+esc(d.path)+'</span><span class="dp-msg">'+esc(d.message)+'</span></div>').join('');
    h+=cfnDiags.map(m=>'<div class="dp-row"><span class="dp-kind">custom-fn</span><span class="dp-msg">'+esc(m)+'</span></div>').join('');
    h+='</details>';
  }
  // hotspots
  if(INSIGHTS.hotspots.length){
    h+='<div class="seclabel">Hotspots — most referenced</div><div class="dashrows">';
    INSIGHTS.hotspots.forEach(x=>{
      const n=byId.get(x.id);
      h+='<div class="dashrow" data-id="'+enc(x.id)+'" role="link" tabindex="0">'+
         '<span class="dot" style="background:'+nodeColor(n)+'"></span>'+
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
         '<span class="dot" style="background:'+color('app')+'"></span>'+
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
  v.onclick=e=>{
    const idEl=e.target.closest('[data-id]');
    if(idEl){ select(dec(idEl.dataset.id)); return; }
    const catEl=e.target.closest('[data-cat]');
    if(catEl){ location.hash='/browse/'+enc(catEl.dataset.cat); return; }
    if(e.target.closest('[data-diag]')) revealDiagList();
  };
  v.onkeydown=e=>{
    if(e.key!=='Enter'&&e.key!==' ') return;
    const t=e.target.closest('[data-id],[data-cat],[data-diag]');
    if(t){ e.preventDefault(); t.click(); }
  };
  if(_revealDiag){ _revealDiag=false; revealDiagList(); }
}
function revealDiagList(){
  const dl=document.getElementById('diaglist');
  if(dl){ dl.open=true; dl.scrollIntoView({block:'center'}); }
}

// ---------- browse: list column ----------
function renderList(){
  const cat = CATS.find(c=>c.id===state.cat);
  const list = document.getElementById('list'); list.innerHTML='';
  if(!cat) return;
  const head=document.createElement('div'); head.className='listhead';
  head.innerHTML='<div class="t"><span>'+esc(cat.label)+'</span><span class="muted">'+cat.count+'</span></div>'+
    '<div class="lh-controls"><input id="lf" placeholder="filter '+esc(cat.label.toLowerCase())+'…" aria-label="Filter list">'+
    '<select id="lsort" aria-label="Sort list"><option value="name">Name</option>'+
    '<option value="refs">Most referenced</option><option value="file">File</option></select></div>';
  list.appendChild(head);
  const wrap=document.createElement('div'); wrap.id='listitems';
  wrap.setAttribute('role','listbox');
  wrap.setAttribute('aria-label',cat.label);
  list.appendChild(wrap);
  renderItems(cat, wrap);
  // The input lives outside the re-rendered items wrap, so typing never loses focus.
  const lf=document.getElementById('lf'); lf.value=state.filter;
  lf.oninput=debounce(()=>{ state.filter=lf.value; renderItems(cat, wrap); },120);
  const ls=document.getElementById('lsort'); ls.value=state.sort;
  ls.onchange=()=>{ state.sort=ls.value; renderItems(cat, wrap); };
  // Arrow/Enter keyboard navigation over the items (roving focus).
  wrap.onkeydown=e=>{
    const els=[...wrap.querySelectorAll('.item[data-id]')];
    const i=els.indexOf(document.activeElement);
    if(e.key==='ArrowDown'||e.key==='ArrowUp'){
      e.preventDefault();
      const j=e.key==='ArrowDown'?Math.min(i+1,els.length-1):Math.max(i-1,0);
      if(els[j]) els[j].focus();
    } else if(e.key==='Home'&&els[0]){ e.preventDefault(); els[0].focus(); }
    else if(e.key==='End'&&els[els.length-1]){ e.preventDefault(); els[els.length-1].focus(); }
    else if((e.key==='Enter'||e.key===' ')&&i>=0){ e.preventDefault(); select(els[i].dataset.id); }
  };
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
  const f = state.filter.toLowerCase();
  if(f) items = items.filter(n => (n.label+' '+n.key+' '+(n.file||'')).toLowerCase().includes(f));
  if(state.sort==='refs')
    items.sort((a,b)=>(INSIGHTS.indeg.get(b.id)||0)-(INSIGHTS.indeg.get(a.id)||0)||a.label.localeCompare(b.label));
  else if(state.sort==='file')
    items.sort((a,b)=>String(a.file||'').localeCompare(String(b.file||''))||a.label.localeCompare(b.label));
  else
    items.sort((a,b)=>a.label.localeCompare(b.label));
  const sentinel=document.createElement('div'); sentinel.className='sentinel';
  wrap.appendChild(sentinel);
  let idx=0;
  function makeItem(n,i){
    const el=document.createElement('div'); el.className='item'+(state.sel===n.id?' on':'');
    el.dataset.id=n.id;
    el.setAttribute('role','option');
    el.setAttribute('aria-selected', state.sel===n.id?'true':'false');
    el.tabIndex=-1;
    el.style.animationDelay=Math.min(i*8,300)+'ms';
    const rn=INSIGHTS.indeg.get(n.id)||0;
    el.innerHTML='<span class="dot" style="margin-top:5px;background:'+nodeColor(n)+'"></span>'+
      '<div class="meta"><div class="nm">'+esc(n.label)+authBadge(n)+'</div><div class="sub">'+esc(n.key)+'</div></div>'+
      (rn?'<span class="refn" title="referenced by '+rn+' node'+(rn>1?'s':'')+'">'+rn+'</span>':'');
    el.onclick=()=>select(n.id);
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
    const on = el.dataset.id===state.sel;
    el.classList.toggle('on', on);
    el.setAttribute('aria-selected', on?'true':'false');
    if(on) hit=el;
  });
  if(hit) hit.scrollIntoView({block:'nearest'});
}

// ---------- detail ----------
function relName(r){ return r; }
// `f` (optional) is the adjacency entry — a suspect/dynamic link gets a marker + dashed chip.
function nodeChip(id,f){
  const n=byId.get(id); if(!n) return '';
  const cls=f&&f.sus?' nc-sus':f&&f.dyn?' nc-dyn':'';
  const flag=f&&f.sus?'<span class="ncflag" title="suspect — loose or cross-type match">≈</span>'
           :f&&f.dyn?'<span class="ncflag" title="dynamic — reference is an expression">ƒ</span>':'';
  return '<span class="nc'+cls+'" data-id="'+enc(id)+'" tabindex="0" role="link"><span class="dot" style="background:'+nodeColor(n)+'"></span>'+
    '<span class="nm">'+esc(n.label)+'</span>'+flag+'<span class="ty">'+esc(nodeKind(n))+'</span></span>';
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

function describe(n){
  const d=n.data||{}, rows=[];
  const add=(k,v)=>{ if(v!==undefined&&v!==null&&v!==''&&!(Array.isArray(v)&&!v.length)) rows.push([k,v]); };
  // split a comma/semicolon group list, drop dynamic ${…}/{{…}} entries, link each to its group node
  const addStarters=v=>{ const p=String(v==null?'':v).split(/[,;]/).map(s=>s.trim()).filter(g=>g&&!/\$\{|\{\{/.test(g));
    if(p.length) rows.push(['Starter groups',{html:p.map(g=>vlink('group:'+g,g)).join(', ')}]); };
  if(n.type==='process'){ addStarters(d.candidateStarterGroups); add('User tasks',(d.userTasks||[]).length);
    add('Service tasks',(d.serviceTasks||[]).length); add('Call activities',(d.callActivities||[]).length);
    add('Documentation',d.documentation); }
  else if(n.type==='case'){ addStarters(d.candidateStarterGroups);
    if(d.initiatorVariableName) rows.push(['Initiator var',{html:vlink('variable:'+d.initiatorVariableName, d.initiatorVariableName)}]);
    add('Documentation',d.documentation); }
  else if(n.type==='decision'){ add('Hit policy',d.hitPolicy); add('Rules',d.ruleCount); add('Inputs',(d.inputs||[]).join(', ')); add('Outputs',(d.outputs||[]).join(', ')); }
  else if(n.type==='form'||n.type==='page'){ add('Fields',(d.fields||[]).length); add('Outcomes',(d.outcomes||[]).map(o=>o.value).filter(Boolean).join(', ')); }
  else if(n.type==='dataObject'){ add('Type',d.dataObjectType); add('Data source',d.sourceId);
    if(d.service) rows.push(['Backing service',{html:vlink('service:'+d.service, d.service, 'Service model '+d.service)}]);
    // When backed by a service, surface that service's physical table here and link the name back to the service node.
    const svc=d.service&&byId.get('service:'+d.service), tbl=d.serviceTableName||(svc&&(svc.data||{}).tableName);
    if(tbl) rows.push(['Table',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Provided by service '+esc(d.service)+'">'+esc(tbl)+'</span>'}]);
    if(d.dictionary) rows.push(['Data dictionary',{html:vlink('dataDictionary:'+d.dictionary, d.dictionary)}]);
    add('Columns',(d.fields||[]).length); }
  else if(n.type==='service'){ add('Type',d.type); add('Base URL',d.baseUrl); add('Auth',d.auth); add('Table',d.tableName);
    if(d.referencedLiquibaseModelKey){ const lid=(byId.get('liquibase:'+d.referencedLiquibaseModelKey)&&'liquibase:'+d.referencedLiquibaseModelKey)||outTo(n.id,'schema');
      rows.push(['Liquibase model',{html:vlink(lid, d.referencedLiquibaseModelKey)}]); }
    add('Columns',(d.columns||[]).length); add('Operations',(d.operations||[]).length);
    if(d.schemaCoverage){ const c=d.schemaCoverage.counts||{}; const g=(c.noService||0)+(c.noDataObject||0); if(g) add('Schema gaps',g+' of '+(c.total||0)+' columns'); } }
  else if(n.type==='serviceOperation'){
    if(d.service) rows.push(['Service',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Defined by service '+esc(d.service)+'">'+esc(d.service)+'</span>'}]);
    add('Name',d.name); add('Method',d.method); add('URL',d.fullUrl||d.url);
    add('Params',(d.params||[]).map(p=>p.name+(p.type?': '+p.type:'')).join(', '));
    add('Used by', (d.usedBy||[]).length+' model(s)'); }
  else if(n.type==='agent'){ add('Vendor / model',(d.aiVendor||'')+' / '+(d.modelName||'')); add('Temperature',d.temperature); add('API endpoint',String(d.enableApiEndpoint));
    if(d.knowledgeBase) rows.push(['Knowledge base',{html:vlink('knowledgeBase:'+d.knowledgeBase, d.knowledgeBase)}]); }
  else if(n.type==='channel'){ add('Direction',d.channelType); add('Type',d.type); add('Topics',(d.topics||[]).join(', ')); add('Destination',d.destination); }
  else if(n.type==='event'){ add('Payload',(d.payload||[]).join(', ')); add('Correlation',(d.correlation||[]).join(', ')); }
  else if(n.type==='java'){ add('Package',d.package); add('Roles',(d.roles||[]).join(', ')); add('Bot key',d.botKey); add('Implements',(d.interfaces||[]).join(', ')); add('Methods',(d.methods||[]).length); add('Called from models',(d.calledMethods||[]).join(', ')); }
  else if(n.type==='endpoint'){ add('Method',d.http); add('Path',d.path);
    rows.push(['Handler',{html:vlink(incFrom(n.id,'serves'), (d.controller||'')+'#'+(d.handler||''))}]); }
  else if(n.type==='method'){ add('Method',(d.name||'')+'()');
    if(d.class) rows.push(['Declared in',{html:vlink(d.declaredIn||'java:'+d.class, d.class)}]); }
  else if(n.type==='query'){ add('Source index',d.sourceIndex); add('Parameters',(d.parameters||[]).join(', ')); add('Filters by groups',(d.groups||[]).length); }
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
    add('Scope',d.scopeType); }
  else if(n.type==='bot'){ add('Kind',d.platform?'Flowable platform bot':'project-defined bot'); }
  else if(n.type==='liquibase'){ const a=d.authority||{};
    add('Status', a.status==='live'?'live (authoritative)':a.status==='superseded'?'superseded revision':a.status==='orphan'?'orphan — unreferenced':undefined);
    if((a.referencedBy||[]).length) rows.push(['Referenced by',{html:a.referencedBy.map(k=>vlink('service:'+k, k)).join(', ')}]);
    if((a.supersededBy||[]).length) rows.push(['Live definition',{html:a.supersededBy.map(k=>vlink('liquibase:'+k, k)).join(', ')}]);
    add('Tables',(d.effectiveTables||d.tables||[]).join(', ')); add('Columns',(d.columns||[]).length); }
  else if(n.type==='expression'||n.type==='binding'){ add('Used by', (d.usedBy||[]).length+' model(s)');
    const pr=d.problems||[]; if(pr.length){ const ec=pr.filter(p=>p.severity==='error').length, wc=pr.length-ec;
      add('Problems',[ec?ec+' error'+(ec>1?'s':''):'', wc?wc+' warning'+(wc>1?'s':''):''].filter(Boolean).join(', ')); } }
  else if(n.type==='variable'){ add('Scope',(d.scopes||[]).join(', ')); add('Used in', (d.usages||[]).length+' model(s)'); }
  else if(n.type==='string'){ add('Used in', (d.usages||[]).length+' model(s)'); }
  else if(n.type==='customFunction'){
    add('Kind', d.kind==='namespace'?('namespace '+d.namespace+'.*'):d.kind==='flw'?'flw.* member':'top-level');
    add('Signature', d.member+'('+(d.signature!=null?d.signature:'…')+')');
    add('Registered in',(d.sources||[]).join(', ')); add('Used by', (d.usedBy||[]).length+' form(s) / model(s)'); }
  else if(n.type==='external'){ add('Kind',d.flowableApi?'Flowable platform API':d.route?'In-app navigation route':d.platform?'Flowable platform bean':d.missingModel?'Missing model reference ('+(d.kind||'model')+')':d.dynamic?'Dynamic reference (expression) — expected '+(d.kind||'model'):(d.external_url?'External URL':d.kind||'external')); if(d.method&&d.method!=='(button)') add('Method',d.method); }
  else { Object.keys(d).forEach(k=>{ const v=d[k]; if(typeof v==='string'||typeof v==='number') add(k,v); }); }
  return rows;
}

function detailExtra(n){
  const d=n.data||{}; let h='';
  if(n.type==='service' && (d.operations||[]).length){
    h+='<h3 class="rel">Operations ('+d.operations.length+')</h3>'+
      d.operations.map(o=>{
        const verb=o.method?'<span class="verb" style="color:'+color("endpoint")+'">'+esc(o.method)+'</span>':'';
        const title='<span class="opname">'+esc(o.fullUrl||o.url||o.name||'')+'</span>';
        // link the key to the operation's own node (its "where used" page)
        const opid='serviceOperation:'+n.key+'#'+(o.key||'');
        const key=(o.key&&byId.get(opid))
          ? '<span class="opkey vlink" data-id="'+enc(opid)+'" tabindex="0" role="link" title="Show where '+esc(o.key)+' is used">'+esc(o.key)+'</span>'
          : '<span class="opkey">'+esc(o.key||'')+'</span>';
        const np=(o.params||[]).length;
        if(!np) return '<div class="op flat">'+verb+title+'<span class="opcount">no params</span>'+key+'</div>';
        return '<details class="op"><summary>'+verb+title+
          '<span class="opcount">'+np+' param'+(np>1?'s':'')+'</span>'+key+'</summary>'+
          '<div class="parmgrid">'+o.params.map(p=>'<div class="pc"><span class="pn">'+esc(p.name)+'</span>'+
            (p.type?'<span class="pt">'+esc(p.type)+'</span>':'')+'</div>').join('')+'</div></details>';
      }).join('');
  }
  if(n.type==='service' && d.schemaCoverage && (d.schemaCoverage.rows||[]).length){
    const sc=d.schemaCoverage, ct=sc.counts||{};
    h+='<h3 class="rel">Schema coverage — Liquibase → Service → DataObject</h3>';
    // source changelog + backing data objects (clickable)
    let meta='';
    if(sc.liquibase){ const lc=nodeChip('liquibase:'+sc.liquibase); if(lc) meta+='<span class="muted">changelog</span>'+lc; }
    (sc.dataObjects||[]).forEach(k=>{ const dc=nodeChip('dataObject:'+k); if(dc) meta+=dc; });
    if(meta) h+='<div class="covmeta">'+meta+'</div>';
    // gap summary
    let badges='';
    if(ct.noService) badges+='<span class="cov-badge cov-bad">'+ct.noService+' not mapped in service</span>';
    if(ct.noDataObject) badges+='<span class="cov-badge cov-warn">'+ct.noDataObject+' not in data object</span>';
    if(ct.extra) badges+='<span class="cov-badge cov-info">'+ct.extra+' not in Liquibase</span>';
    if(ct.ok) badges+='<span class="cov-badge cov-good">'+ct.ok+' mapped through</span>';
    if(badges) h+='<div class="covbadges">'+badges+'</div>';
    const rowCls={'no-service':'cov-bad','no-dataobject':'cov-warn','extra-service':'cov-info','ok':''};
    const miss='<span class="miss">✗ not mapped</span>';
    h+='<div class="covwrap"><table class="cov"><thead><tr>'+
       '<th>Liquibase column</th><th>Service mapping</th><th>Data object field</th></tr></thead><tbody>';
    sc.rows.forEach(r=>{
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
      h+='<tr class="'+(rowCls[r.status]||'')+'"><td>'+lbCell+'</td><td>'+svCell+'</td><td>'+doCell+'</td></tr>';
    });
    h+='</tbody></table></div>';
  }
  else if(n.type==='service' && (d.columns||[]).length){
    h+='<h3 class="rel">Columns / field mappings ('+d.columns.length+')</h3><div class="oplist">'+
      d.columns.map(c=>'<div class="oprow"><span>'+esc(c.name||'')+'</span>'+
        (c.columnName&&c.columnName!==c.name?'<span class="muted">'+esc(c.columnName)+'</span>':'')+
        (c.type?'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">'+esc(c.type)+'</span>':'')+
        '</div>').join('')+'</div>';
  }
  if(n.type==='java' && (d.endpoints||[]).length){
    h+='<h3 class="rel">Endpoints served</h3><div class="oplist">'+
      d.endpoints.map(e=>'<div class="oprow"><span class="verb" style="color:'+color("endpoint")+'">'+esc(e.http)+'</span><span>'+esc(e.path)+'</span><span class="muted">'+esc(e.handler)+'() :'+e.line+'</span></div>').join('')+'</div>';
  }
  if(n.type==='java' && (d.methods||[]).length){
    const cm=new Set(d.calledMethods||[]);
    h+='<h3 class="rel">Declared methods ('+d.methods.length+')</h3><div class="oplist">'+
      d.methods.slice(0,80).map(m=>'<div class="oprow"><span>'+esc(m.name)+'('+m.params+')</span><span class="muted">:'+m.line+(cm.has(m.name)?'  ◀ called by models':'')+'</span></div>').join('')+'</div>';
  }
  if((n.type==='process') && (d.serviceTasks||[]).length){
    const st=d.serviceTasks.filter(s=>s.class||s.delegateExpression||s.expression||s.type);
    if(st.length) h+='<h3 class="rel">Service tasks</h3><div class="oplist">'+
      st.map(s=>'<div class="oprow"><span class="muted" style="min-width:150px">'+esc(s.name||s.id)+'</span>'+
        '<span style="flex:1">'+esc(s.class||s.delegateExpression||s.expression||s.type||'')+'</span>'+
        implLink(s)+'</div>').join('')+'</div>';
  }
  if(n.type==='dataObject' && (d.columns||[]).length){
    h+='<h3 class="rel">Columns / field mappings ('+d.columns.length+')</h3><div class="oplist">'+
      d.columns.map(c=>'<div class="oprow"><span>'+esc(c.name)+'</span><span class="muted">'+esc(c.label||'')+'</span>'+
        (c.refDataObject?'<span class="vlink" data-id="'+enc('dataObject:'+c.refDataObject)+'" tabindex="0" role="link">→ '+esc(c.refDataObject)+(c.relationship?' ('+esc(c.relationship)+')':'')+'</span>':'')+
        (c.type?'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">'+esc(c.type)+'</span>':'')+
        '</div>').join('')+'</div>';
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
    h+='<h3 class="rel">Columns ('+d.columns.length+')'+(cov?' — mapping coverage':'')+'</h3>';
    if(cov) h+='<div class="covlegend">'+
      '<span><span class="covdot" style="background:'+covColor('bad')+'"></span>not in service</span>'+
      '<span><span class="covdot" style="background:'+covColor('warn')+'"></span>not in data object</span>'+
      '<span><span class="covdot" style="background:'+covColor('good')+'"></span>mapped through</span></div>';
    Object.keys(byT).forEach(t=>{
      h+='<div style="margin:6px 0 12px"><div class="muted mono" style="margin-bottom:4px">'+esc(t)+'</div><div class="oplist">'+
        byT[t].map(c=>{ const st=cov?stOf(looseCol(c.name)):null;
          return '<div class="oprow'+(st==='bad'?' cov-bad':st==='warn'?' cov-warn':'')+'">'+
          (cov?'<span class="covdot" title="'+stTitle[st]+'" style="background:'+covColor(st)+'"></span>':'')+
          '<span>'+esc(c.name)+'</span>'+
          (c.type?'<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">'+esc(c.type)+'</span>':'')+
          '</div>'; }).join('')+'</div></div>';
    });
  }
  if((n.type==='expression'||n.type==='binding') && (d.problems||[]).length){
    h+='<h3 class="rel">Problems ('+d.problems.length+')</h3><div class="oplist">'+
      d.problems.map(p=>{
        const isErr=p.severity==='error';
        const col=isErr?color('invalidExpr'):color('suspectExpr');
        const snip=p.snippet||'';
        return '<div class="oprow"><span class="verb" style="color:'+col+'">'+(isErr?'error':'warning')+'</span>'+
          '<span style="flex:1">'+esc(p.message)+'</span>'+
          (snip?'<span class="mono" style="color:var(--ink-faint);font-size:10px">'+esc(snip)+'</span>':'')+
          '</div>';
      }).join('')+'</div>';
  }
  if((n.type==='expression'||n.type==='binding'||n.type==='customFunction'||n.type==='serviceOperation') && (d.usedBy||[]).length){
    h+='<h3 class="rel">Used by ('+d.usedBy.length+')</h3><div class="nodechips">'+d.usedBy.map(nodeChip).join('')+'</div>';
  }
  if(n.type==='serviceOperation' && !(d.usedBy||[]).length){
    h+='<div class="authnote authnote-orphan">No service button, data-object field or CMMN service mapping in the scanned models calls this operation.</div>';
  }
  // a frontend binding links to the custom function(s) it calls; a custom function links back to the
  // exact bindings that call it (in addition to the forms/models under "Used by").
  if(n.type==='binding' && (d.calls||[]).length){
    h+='<h3 class="rel">Calls custom functions 🧩 ('+d.calls.length+')</h3><div class="nodechips">'+d.calls.map(nodeChip).join('')+'</div>';
  }
  if(n.type==='customFunction' && (d.bindings||[]).length){
    h+='<h3 class="rel">Called in bindings ('+d.bindings.length+')</h3><div class="nodechips">'+d.bindings.map(nodeChip).join('')+'</div>';
  }
  if(n.type==='customFunction' && !(d.usedBy||[]).length){
    h+='<div class="authnote authnote-orphan">Registered via <b>externals.additionalData</b> but no <code>{{…}}</code> binding in the scanned models calls it.</div>';
  }
  if((n.type==='variable'||n.type==='string') && (d.usages||[]).length){
    h+='<h3 class="rel">Used in ('+d.usages.length+' models) — effective occurrences</h3>';
    d.usages.forEach(u=>{
      h+='<div style="margin:6px 0 12px">'+nodeChip(u.model)+
         '<div class="oplist" style="margin-top:5px">'+
         (u.snippets||[]).map(s=>'<div class="oprow"><span class="mono">'+esc(s)+'</span></div>').join('')+
         '</div></div>';
    });
  }
  // Reverse direction: a model lists all the variables/expressions/strings it uses (collapsible).
  if(d._uses){
    const ord=[['variable','Variables'],['expression','Backend expressions ${ }'],
               ['binding','Frontend bindings {{ }}'],['customFunction','Custom functions 🧩'],
               ['serviceOperation','Service operations'],['string','String literals']];
    let parts='';
    ord.forEach(([t,lbl])=>{ const ids=(d._uses||{})[t]; if(ids&&ids.length)
      parts+='<details class="uses"><summary>'+lbl+' ('+ids.length+')</summary><div class="nodechips">'+ids.map(nodeChip).join('')+'</div></details>'; });
    if(parts) h+='<h3 class="rel">Uses — variables &amp; expressions</h3>'+parts;
  }
  return h;
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
    g+='<line x1="'+CX+'" y1="'+CY+'" x2="'+x.toFixed(1)+'" y2="'+y.toFixed(1)+'" stroke="var(--line2)" stroke-width="1"'+dash+dim+'><title>'+esc(e.rel)+flagTxt+(e.dir==='in'?' (incoming)':'')+'</title></line>';
    const anchor=Math.cos(a)>0.25?'start':Math.cos(a)<-0.25?'end':'middle';
    const tx=x+(anchor==='start'?9:anchor==='end'?-9:0), ty=y+(anchor==='middle'?(Math.sin(a)>0?16:-10):4);
    g+='<g class="gn" data-id="'+enc(e.id)+'" tabindex="0" role="link" style="cursor:pointer">'+
       '<title>'+esc(nn.label)+' — '+esc(e.rel)+'</title>'+
       '<circle cx="'+x.toFixed(1)+'" cy="'+y.toFixed(1)+'" r="5" fill="'+nodeColor(nn)+'"/>'+
       '<text x="'+tx.toFixed(1)+'" y="'+ty.toFixed(1)+'" text-anchor="'+anchor+'" font-size="10" font-family="var(--mono)" fill="var(--ink-dim)">'+esc(trunc(nn.label,26))+'</text></g>';
  });
  // center node on top of the lines
  g+='<circle cx="'+CX+'" cy="'+CY+'" r="8" fill="'+nodeColor(n)+'" stroke="var(--panel)" stroke-width="2"/>'+
     '<text x="'+CX+'" y="'+(CY+22)+'" text-anchor="middle" font-size="11" font-weight="600" font-family="var(--mono)" fill="var(--ink)">'+esc(trunc(n.label,32))+'</text>';
  const more=all.length>shown.length?'<div class="muted" style="font-size:10.5px;margin:2px 0 6px">showing '+shown.length+' of '+all.length+' neighbors — the full list is below</div>':'';
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
  return '<span class="nc" data-id="'+enc(id)+'" tabindex="0" role="link" style="flex:none"><span class="dot" style="background:'+color('java')+'"></span><span class="nm">'+esc(label)+'</span></span>';
}

function renderDetail(){
  const det=document.getElementById('detail');
  if(!state.sel || !byId.get(state.sel)){
    det.innerHTML='<div class="estate"><div class="estate-ic" aria-hidden="true">⌕</div>'+
      '<div class="et">'+(state.cat?'Nothing selected':'Flowable Atlas')+'</div>'+
      '<div class="eh">Pick an item from the list — click any relationship to travel the graph.</div></div>';
    return;
  }
  const n=byId.get(state.sel);
  const out=groupRels(outM.get(n.id)), inc=groupRels(incM.get(n.id));
  let h='';
  h+='<div class="dhead">'+(_navCount>1?'<button id="back">← back</button>':'')+
     '<button id="permalink" title="Copy a shareable link to this node">🔗 copy link</button></div>';
  h+='<div class="dbody">';
  h+='<span class="chip"><span class="dot" style="background:'+nodeColor(n)+'"></span>'+esc(nodeKind(n))+'</span>';
  h+='<div class="dtitle">'+esc(n.label)+authBadge(n)+'</div>';
  h+='<div class="dkey mono">'+esc(n.key)+'</div>';
  if(n.file) h+='<div class="dfile" title="click to copy" data-copy="'+enc(n.file)+'">'+esc(n.file)+'</div>';
  const rows=describe(n);
  if(rows.length){ h+='<div class="grid">'+rows.map(r=>'<div class="cell"><div class="k">'+esc(r[0])+'</div><div class="v mono">'+(r[1]&&r[1].html!==undefined?r[1].html:esc(String(r[1])))+'</div></div>').join('')+'</div>'; }
  h+=neighborhoodSvg(n);
  h+=detailExtra(n);
  // outgoing
  const ok=Object.keys(out).sort();
  if(ok.length){ h+='<h3 class="rel">Uses / references ('+ok.reduce((a,k)=>a+out[k].size,0)+')</h3>';
    ok.forEach(rel=>{ h+='<div class="relgrp"><div class="lab">'+esc(rel)+'</div><div class="nodechips">'+[...out[rel].values()].map(e=>nodeChip(e.id,e)).join('')+'</div></div>'; }); }
  // incoming
  const ik=Object.keys(inc).sort();
  if(ik.length){ h+='<h3 class="rel">Used by / referenced from ('+ik.reduce((a,k)=>a+inc[k].size,0)+')</h3>';
    ik.forEach(rel=>{ h+='<div class="relgrp"><div class="lab">'+esc(rel)+'</div><div class="nodechips">'+[...inc[rel].values()].map(e=>nodeChip(e.id,e)).join('')+'</div></div>'; }); }
  if(!ok.length && !ik.length) h+='<p class="muted" style="margin-top:18px">No relationships recorded for this node.</p>';
  h+='</div>';
  det.innerHTML=h;
  det.scrollTop=0;
  const b=document.getElementById('back'); if(b) b.onclick=()=>history.back();
  const pl=document.getElementById('permalink');
  if(pl) pl.onclick=()=>{
    // strip ?ideTheme=… (IDE embedding seed) — a stale param in a shared link only confuses
    const url=location.search?location.href.replace(location.search,''):location.href;
    const done=()=>{ pl.textContent='✓ link copied'; setTimeout(()=>{ pl.textContent='🔗 copy link'; },1500); };
    if(navigator.clipboard&&navigator.clipboard.writeText) navigator.clipboard.writeText(url).then(done,()=>prompt('Copy link:',url));
    else prompt('Copy link:',url);   // clipboard API is unavailable on file:// in some browsers
  };
  det.querySelectorAll('.nc, .gn, .vlink').forEach(c=>{
    c.onclick=()=>select(dec(c.dataset.id));
    c.onkeydown=e=>{ if(e.key==='Enter'||e.key===' '){ e.preventDefault(); select(dec(c.dataset.id)); } };
  });
  const fp=det.querySelector('.dfile'); if(fp) fp.onclick=()=>{navigator.clipboard&&navigator.clipboard.writeText(dec(fp.dataset.copy)); fp.textContent='✓ copied — '+dec(fp.dataset.copy); };
}

// Navigation: select() only moves the URL hash; the hashchange listener routes. That makes
// the hash the single source of truth — browser back/forward, bookmarks and copied links all
// go through the same path.
function select(id){
  if(!byId.get(id)) return;
  if(state.sel===id) return;
  if(dec(location.hash.slice(1))===id) applySelection(id);
  else location.hash=encodeURIComponent(id);
}

function applySelection(id){
  if(!byId.get(id)) return;
  state.view='browse'; showView('browse');
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
    if(cat && cat.id!==state.cat){ state.cat=cat.id; catChanged=true; }
  }
  if(catChanged || !document.getElementById('listitems')) renderList();
  syncListSelection();
  renderDetail();
  renderSidebarActive(); renderCrumbs();
}

// ---------- search haystack (shared by the command palette) ----------
// base identity plus a few type-specific extras. A DataObject's fields/columns are not
// nodes of their own, so without this a field name like 'crewId' (or its label / type)
// would never surface its data object.
function searchText(n){
  const d=n.data||{};
  let s=n.label+' '+n.key+' '+(n.file||'')+' '+n.type;
  if(n.type==='dataObject') s+=' '+(d.fields||[]).join(' ')+' '+(d.serviceTableName||'')+' '+
    (d.columns||[]).map(c=>(c.label||'')+' '+(c.type||'')).join(' ');
  if(n.type==='service') s+=' '+(d.columns||[]).map(c=>(c.name||'')+' '+(c.columnName||'')+' '+(c.type||'')).join(' ');
  if(n.type==='liquibase') s+=' '+(d.columns||[]).map(c=>(c.name||'')+' '+(c.type||'')).join(' ');
  return s.toLowerCase();
}

// ---------- command palette (⌘K) ----------
const pal=document.getElementById('palette'), palq=document.getElementById('palq'), palres=document.getElementById('palresults');
let palList=[], palSel=-1, _palPrevFocus=null;
function openPalette(){
  if(!pal.hidden) return;
  _palPrevFocus=document.activeElement;
  pal.hidden=false; palq.value=''; palSel=-1;
  palRender(); palq.focus();
}
function closePalette(){
  if(pal.hidden) return;
  pal.hidden=true;
  try{ if(_palPrevFocus && document.contains(_palPrevFocus)) _palPrevFocus.focus(); }catch(e){}
  _palPrevFocus=null;
}
function getRecents(){
  try{ return (JSON.parse(localStorage.getItem('atlas-recent')||'[]')||[]).filter(id=>byId.get(id)); }
  catch(e){ return []; }
}
function pushRecent(id){
  try{
    const r=getRecents().filter(x=>x!==id); r.unshift(id);
    localStorage.setItem('atlas-recent', JSON.stringify(r.slice(0,8)));
  }catch(e){}
}
function palRender(){
  const v=palq.value.trim().toLowerCase();
  let groups=[];
  if(!v){
    const rec=getRecents().map(id=>byId.get(id));
    if(rec.length) groups=[{label:'Recent', items:rec}];
  } else {
    const hits=nodes.filter(n=>searchText(n).includes(v))
                    .sort((a,b)=>a.label.length-b.label.length).slice(0,40);
    const bySec={};
    hits.forEach(n=>{
      const sec = n.type==='external' ? (n.data&&n.data.flowableApi?'Integration':'Other')
                                      : (TM[n.type]?TM[n.type][1]:'Other');
      (bySec[sec]=bySec[sec]||[]).push(n);
    });
    SECTIONS.forEach(s=>{ if(bySec[s]) groups.push({label:s, items:bySec[s]}); });
  }
  palList=[]; let h='';
  groups.forEach(g=>{
    h+='<div class="pal-group">'+esc(g.label)+'</div>';
    g.items.forEach(n=>{
      const i=palList.length; palList.push(n);
      h+='<div class="pal-item'+(i===palSel?' sel':'')+'" id="pal-'+i+'" role="option" aria-selected="'+(i===palSel)+'" data-i="'+i+'">'+
         '<span class="dot" style="background:'+nodeColor(n)+'"></span>'+
         '<span class="nm">'+esc(n.label)+'</span><span class="hint">'+esc(n.key)+'</span></div>';
    });
  });
  if(!h) h='<div class="pal-empty">'+(v?'No matches':'Nothing recent yet — visit a few nodes and they will show up here')+'</div>';
  palres.innerHTML=h;
  if(palSel>=0){
    palq.setAttribute('aria-activedescendant','pal-'+palSel);
    const el=document.getElementById('pal-'+palSel); if(el) el.scrollIntoView({block:'nearest'});
  } else palq.removeAttribute('aria-activedescendant');
  palres.querySelectorAll('.pal-item').forEach(el=>el.onclick=()=>{
    const n=palList[+el.dataset.i]; closePalette(); select(n.id);
  });
}
palq.addEventListener('input', debounce(()=>{ palSel=-1; palRender(); },120));
palq.addEventListener('keydown',e=>{
  if(e.key==='ArrowDown'){ e.preventDefault(); palSel=Math.min(palSel+1,palList.length-1); palRender(); }
  else if(e.key==='ArrowUp'){ e.preventDefault(); palSel=Math.max(palSel-1,0); palRender(); }
  else if(e.key==='Enter' && palList[palSel]){ const n=palList[palSel]; closePalette(); select(n.id); }
  else if(e.key==='Escape'){ closePalette(); }
  else if(e.key==='Tab'){ e.preventDefault(); }            // the input is the only tabbable — trap
});
pal.addEventListener('mousedown',e=>{ if(e.target.closest('[data-close]')) closePalette(); });
document.addEventListener('keydown',e=>{
  if((e.metaKey||e.ctrlKey) && (e.key==='k'||e.key==='K')){
    e.preventDefault(); pal.hidden?openPalette():closePalette();
  } else if(e.key==='/' && pal.hidden && !e.target.closest('input,textarea,select,[contenteditable]')){
    e.preventDefault(); openPalette();                     // guarded: '/' typed in a filter stays there
  } else if(e.key==='Escape' && !pal.hidden){
    closePalette();
  }
});
function wireSearchTrigger(){
  document.getElementById('searchbtn').onclick=openPalette;
  const mac=/Mac|iPhone|iPad/.test(navigator.platform||'');
  document.getElementById('searchkbd').textContent = mac?'⌘K':'Ctrl K';
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
    b.title=(hideUncertain?'Uncertain links hidden':'Uncertain links shown')+' — '+
      su+' suspect (≈ loose/cross-type match), '+dy+' dynamic (ƒ expression-valued). Click to toggle.';
    b.setAttribute('aria-label', b.title);
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
function dec(s){ return decodeURIComponent(s); }

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
    b.title='Theme: '+pref+(pref==='auto'&&window.__ideTheme?' (follows IDE)':'')+' — click to switch';
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

// ---------- boot ----------
document.getElementById('proj').textContent=DATA.project;
computeInsights();
renderSidebar();
wireSearchTrigger();
wireLinkFilter();
window.addEventListener('hashchange',route);
route();
