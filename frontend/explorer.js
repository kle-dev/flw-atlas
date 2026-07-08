// Data arrives as a JSON island (<script type="application/json" id="atlas-data">):
// JSON.parse is faster than a JS literal for large payloads and needs no JS escaping.
const DATA = JSON.parse(document.getElementById('atlas-data').textContent);
const nodes = DATA.nodes, edges = DATA.edges;
const byId = new Map(nodes.map(n => [n.id, n]));
const TM = {
  app:['Apps','Models'],process:['Processes','Models'],case:['Cases','Models'],
  decision:['Decisions','Models'],form:['Forms','Models'],page:['Pages','Models'],
  dataObject:['Data objects','Models'],dataDictionary:['Data dictionaries','Models'],
  masterData:['Master data','Models'],
  service:['Service models','Integration'],agent:['Agents / bots','Integration'],
  channel:['Channels','Integration'],event:['Events','Integration'],knowledgeBase:['Knowledge bases','Integration'],
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
const color = t => 'var(--c-'+t+', #8aa0b4)';
const covColor = k => 'var(--cov-'+k+', #8aa0b4)';
const debounce = (fn,ms) => { let t; return function(){ clearTimeout(t); t=setTimeout(()=>fn.apply(this,arguments),ms); }; };
const looseCol = s => String(s==null?'':s).toLowerCase().replace(/[^a-z0-9]/g,'');
// external nodes split into Flowable API / navigation routes / real third-party deps.
const nodeColor = n => (n && n.type==='external')
  ? (n.data&&n.data.flowableApi?color('endpoint'):n.data&&n.data.route?color('page'):color('external'))
  : color(n?n.type:'');
const nodeKind = n => (n.type!=='external')
  ? (TM[n.type]?TM[n.type][0]:n.type)
  : (n.data.flowableApi?'Flowable API':n.data.route?'Navigation route':'External / library');

// adjacency
const outM = new Map(), incM = new Map();
const push = (m,k,v)=>{ if(!m.has(k)) m.set(k,[]); m.get(k).push(v); };
edges.forEach(e=>{ push(outM,e.s,{rel:e.rel,id:e.t}); push(incM,e.t,{rel:e.rel,id:e.s}); });

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

// state — navigation history lives in the URL hash (browser back/forward just works);
// `trail` only remembers recently visited labels for the breadcrumb display.
let state = {cat:null, sel:null, trail:[], filter:''};

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
       {id:'external::lib',  label:'External / library',  sec:'Other',       color:color('external'), match:n=>n.type==='external'&&!n.data.flowableApi&&!n.data.route}
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

function renderRail(){
  const rail = document.getElementById('rail'); rail.innerHTML='';
  let cur='';
  CATS.forEach(c=>{
    if(c.sec!==cur){ cur=c.sec; const h=document.createElement('div'); h.className='sec'; h.textContent=cur; rail.appendChild(h); }
    const on = state.cat===c.id;
    const el=document.createElement('div'); el.className='cat'+(on?' on':'');
    el.setAttribute('role','button');
    el.setAttribute('aria-pressed', on?'true':'false');
    el.tabIndex = 0;                              // every category is tabbable; arrows also work
    el.title = c.label+' ('+c.count+')';          // label survives the collapsed ≤1100px rail
    el.innerHTML='<span class="dot" style="background:'+c.color+'"></span><span class="lbl">'+esc(c.label)+'</span><span class="n">'+c.count+'</span>';
    const activate=()=>{ state.cat=c.id; state.filter=''; renderRail(); renderList(); };
    el.onclick=activate;
    el.onkeydown=e=>{
      if(e.key==='Enter'||e.key===' '){ e.preventDefault(); activate(); }
      else if(e.key==='ArrowDown'||e.key==='ArrowUp'){
        e.preventDefault();
        const cats=[...rail.querySelectorAll('.cat')];
        const i=cats.indexOf(el)+(e.key==='ArrowDown'?1:-1);
        if(cats[i]) cats[i].focus();
      }
    };
    rail.appendChild(el);
  });
}

function renderList(){
  const cat = CATS.find(c=>c.id===state.cat);
  const list = document.getElementById('list'); list.innerHTML='';
  if(!cat) return;
  const head=document.createElement('div'); head.className='listhead';
  head.innerHTML='<div class="t"><span>'+esc(cat.label)+'</span><span class="muted">'+cat.count+'</span></div>'+
    '<input id="lf" placeholder="filter '+esc(cat.label.toLowerCase())+'…" aria-label="Filter list">';
  list.appendChild(head);
  const wrap=document.createElement('div'); wrap.id='listitems';
  wrap.setAttribute('role','listbox');
  wrap.setAttribute('aria-label',cat.label);
  list.appendChild(wrap);
  renderItems(cat, wrap);
  // The input lives outside the re-rendered items wrap, so typing never loses focus.
  const lf=document.getElementById('lf'); lf.value=state.filter;
  lf.oninput=debounce(()=>{ state.filter=lf.value; renderItems(cat, wrap); },120);
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
    el.innerHTML='<span class="dot" style="margin-top:5px;background:'+nodeColor(n)+'"></span>'+
      '<div class="meta"><div class="nm">'+esc(n.label)+authBadge(n)+'</div><div class="sub">'+esc(n.key)+'</div></div>';
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
                                   {root: wrap.closest('.col'), rootMargin:'600px'});
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
function nodeChip(id){
  const n=byId.get(id); if(!n) return '';
  return '<span class="nc" data-id="'+enc(id)+'" tabindex="0" role="link"><span class="dot" style="background:'+nodeColor(n)+'"></span>'+
    '<span class="nm">'+esc(n.label)+'</span><span class="ty">'+esc(nodeKind(n))+'</span></span>';
}
function groupRels(arr){ const g={}; (arr||[]).forEach(x=>{ (g[x.rel]=g[x.rel]||new Set()).add(x.id); }); return g; }
// Small badge marking a changelog as the live definition of its table vs a superseded/orphan revision.
function authBadge(n){
  if(n.type!=='liquibase') return '';
  const a=(n.data||{}).authority; if(!a||!a.status) return '';
  if(a.status==='live'){ const by=(a.referencedBy||[]).join(', ');
    return '<span class="authb authb-live" title="Live / authoritative'+(by?' — referenced by '+esc(by):'')+'">live</span>'; }
  if(a.status==='superseded'){ const by=(a.supersededBy||[]).join(', ');
    return '<span class="authb authb-old" title="Superseded — the same table is provided by '+esc(by||'a referenced changelog')+'">superseded</span>'; }
  return '<span class="authb authb-orphan" title="Orphan — not referenced by any service or data object">orphan</span>';
}

function describe(n){
  const d=n.data||{}, rows=[];
  const add=(k,v)=>{ if(v!==undefined&&v!==null&&v!==''&&!(Array.isArray(v)&&!v.length)) rows.push([k,v]); };
  if(n.type==='process'){ add('Starter groups',d.candidateStarterGroups); add('User tasks',(d.userTasks||[]).length);
    add('Service tasks',(d.serviceTasks||[]).length); add('Call activities',(d.callActivities||[]).length);
    add('Documentation',d.documentation); }
  else if(n.type==='case'){ add('Starter groups',d.candidateStarterGroups); add('Initiator var',d.initiatorVariableName); add('Documentation',d.documentation); }
  else if(n.type==='decision'){ add('Hit policy',d.hitPolicy); add('Rules',d.ruleCount); add('Inputs',(d.inputs||[]).join(', ')); add('Outputs',(d.outputs||[]).join(', ')); }
  else if(n.type==='form'||n.type==='page'){ add('Fields',(d.fields||[]).length); add('Outcomes',(d.outcomes||[]).map(o=>o.value).filter(Boolean).join(', ')); }
  else if(n.type==='dataObject'){ add('Type',d.dataObjectType); add('Data source',d.sourceId); add('Backing service',d.service);
    // When backed by a service, surface that service's physical table here and link the name back to the service node.
    const svc=d.service&&byId.get('service:'+d.service), tbl=svc&&(svc.data||{}).tableName;
    if(tbl) rows.push(['Table',{html:'<span class="vlink" data-id="'+enc('service:'+d.service)+'" tabindex="0" role="link" title="Provided by service '+esc(d.service)+'">'+esc(tbl)+'</span>'}]);
    add('Data dictionary',d.dictionary); add('Columns',(d.fields||[]).length); }
  else if(n.type==='service'){ add('Type',d.type); add('Base URL',d.baseUrl); add('Auth',d.auth); add('Table',d.tableName); add('Liquibase model',d.referencedLiquibaseModelKey); add('Columns',(d.columns||[]).length); add('Operations',(d.operations||[]).length);
    if(d.schemaCoverage){ const c=d.schemaCoverage.counts||{}; const g=(c.noService||0)+(c.noDataObject||0); if(g) add('Schema gaps',g+' of '+(c.total||0)+' columns'); } }
  else if(n.type==='agent'){ add('Vendor / model',(d.aiVendor||'')+' / '+(d.modelName||'')); add('Temperature',d.temperature); add('API endpoint',String(d.enableApiEndpoint)); add('Knowledge base',d.knowledgeBase); }
  else if(n.type==='channel'){ add('Direction',d.channelType); add('Type',d.type); add('Topics',(d.topics||[]).join(', ')); add('Destination',d.destination); }
  else if(n.type==='event'){ add('Payload',(d.payload||[]).join(', ')); add('Correlation',(d.correlation||[]).join(', ')); }
  else if(n.type==='java'){ add('Package',d.package); add('Roles',(d.roles||[]).join(', ')); add('Bot key',d.botKey); add('Implements',(d.interfaces||[]).join(', ')); add('Methods',(d.methods||[]).length); add('Called from models',(d.calledMethods||[]).join(', ')); }
  else if(n.type==='endpoint'){ add('Method',d.http); add('Path',d.path); add('Handler',(d.controller||'')+'#'+(d.handler||'')); }
  else if(n.type==='method'){ add('Method',(d.name||'')+'()'); add('Declared in',d.class); }
  else if(n.type==='query'){ add('Source index',d.sourceIndex); add('Parameters',(d.parameters||[]).join(', ')); add('Filters by groups',(d.groups||[]).length); }
  else if(n.type==='action'){ add('Bot',d.botKey); add('Form',d.formKey); add('Triggers signal',d.signalName); add('Scope',d.scopeType); }
  else if(n.type==='bot'){ add('Kind',d.platform?'Flowable platform bot':'project-defined bot'); }
  else if(n.type==='liquibase'){ const a=d.authority||{};
    add('Status', a.status==='live'?'live (authoritative)':a.status==='superseded'?'superseded revision':a.status==='orphan'?'orphan — unreferenced':undefined);
    if((a.referencedBy||[]).length) add('Referenced by',(a.referencedBy||[]).join(', '));
    if((a.supersededBy||[]).length) add('Live definition',(a.supersededBy||[]).join(', '));
    add('Tables',(d.effectiveTables||d.tables||[]).join(', ')); add('Columns',(d.columns||[]).length); }
  else if(n.type==='expression'||n.type==='binding'){ add('Used by', (d.usedBy||[]).length+' model(s)');
    const pr=d.problems||[]; if(pr.length){ const ec=pr.filter(p=>p.severity==='error').length, wc=pr.length-ec;
      add('Problems',[ec?ec+' error'+(ec>1?'s':''):'', wc?wc+' warning'+(wc>1?'s':''):''].filter(Boolean).join(', ')); } }
  else if(n.type==='variable'){ add('Scope',(d.scopes||[]).join(', ')); add('Used in', (d.usages||[]).length+' model(s)'); }
  else if(n.type==='string'){ add('Used in', (d.usages||[]).length+' model(s)'); }
  else if(n.type==='customFunction'){
    add('Kind', d.kind==='namespace'?('namespace '+d.namespace+'.*'):d.kind==='flw'?'flw.* member':'top-level');
    add('Registered in',(d.sources||[]).join(', ')); add('Used by', (d.usedBy||[]).length+' form(s) / model(s)'); }
  else if(n.type==='external'){ add('Kind',d.flowableApi?'Flowable platform API':d.route?'In-app navigation route':d.platform?'Flowable platform bean':(d.external_url?'External URL':d.kind||'external')); if(d.method&&d.method!=='(button)') add('Method',d.method); }
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
        const key='<span class="opkey">'+esc(o.key||'')+'</span>';
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
  if((n.type==='expression'||n.type==='binding'||n.type==='customFunction') && (d.usedBy||[]).length){
    h+='<h3 class="rel">Used by ('+d.usedBy.length+')</h3><div class="nodechips">'+d.usedBy.map(nodeChip).join('')+'</div>';
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
               ['string','String literals']];
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
    g+='<line x1="'+CX+'" y1="'+CY+'" x2="'+x.toFixed(1)+'" y2="'+y.toFixed(1)+'" stroke="var(--line2)" stroke-width="1"'+dash+'><title>'+esc(e.rel)+(e.dir==='in'?' (incoming)':'')+'</title></line>';
    const anchor=Math.cos(a)>0.25?'start':Math.cos(a)<-0.25?'end':'middle';
    const tx=x+(anchor==='start'?9:anchor==='end'?-9:0), ty=y+(anchor==='middle'?(Math.sin(a)>0?16:-10):4);
    g+='<g class="gn" data-id="'+enc(e.id)+'" tabindex="0" role="link" style="cursor:pointer">'+
       '<title>'+esc(nn.label)+' — '+esc(e.rel)+'</title>'+
       '<circle cx="'+x.toFixed(1)+'" cy="'+y.toFixed(1)+'" r="5" fill="'+nodeColor(nn)+'"/>'+
       '<text x="'+tx.toFixed(1)+'" y="'+ty.toFixed(1)+'" text-anchor="'+anchor+'" font-size="10" font-family="var(--mono)" fill="var(--ink-dim)">'+esc(trunc(nn.label,26))+'</text></g>';
  });
  // center node on top of the lines
  g+='<circle cx="'+CX+'" cy="'+CY+'" r="8" fill="'+nodeColor(n)+'" stroke="var(--bg)" stroke-width="2"/>'+
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
    det.innerHTML='<div class="empty"><div class="big">Flowable Atlas</div><div>Pick a category on the left, then an item.<br>Click any relationship to travel the graph.</div></div>';
    return;
  }
  const n=byId.get(state.sel);
  const out=groupRels(outM.get(n.id)), inc=groupRels(incM.get(n.id));
  let h='';
  // crumbs — display only; navigation is the browser history (each select() pushes a hash entry)
  const trail = state.trail.slice(-4).map(id=>{const x=byId.get(id);return x?esc(x.label):'';}).filter(Boolean).join(' › ');
  h+='<div class="crumbs">'+(state.trail.length?'<button id="back">← back</button>':'')+
     '<span class="trail">'+trail+(trail?' › ':'')+'<b style="color:var(--ink)">'+esc(n.label)+'</b></span>'+
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
    ok.forEach(rel=>{ h+='<div class="relgrp"><div class="lab">'+esc(rel)+'</div><div class="nodechips">'+[...out[rel]].map(nodeChip).join('')+'</div></div>'; }); }
  // incoming
  const ik=Object.keys(inc).sort();
  if(ik.length){ h+='<h3 class="rel">Used by / referenced from ('+ik.reduce((a,k)=>a+inc[k].size,0)+')</h3>';
    ik.forEach(rel=>{ h+='<div class="relgrp"><div class="lab">'+esc(rel)+'</div><div class="nodechips">'+[...inc[rel]].map(nodeChip).join('')+'</div></div>'; }); }
  if(!ok.length && !ik.length) h+='<p class="muted" style="margin-top:18px">No relationships recorded for this node.</p>';
  h+='</div>';
  det.innerHTML=h;
  det.scrollTop=0;
  const b=document.getElementById('back'); if(b) b.onclick=()=>history.back();
  const pl=document.getElementById('permalink');
  if(pl) pl.onclick=()=>{
    const url=location.href;
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

// Navigation: select() only moves the URL hash; the hashchange listener renders. That makes
// the hash the single source of truth — browser back/forward, bookmarks and copied links all
// go through the same path (the old internal back-stack competed with browser history).
function select(id){
  if(!byId.get(id)) return;
  if(state.sel===id) return;
  if(dec(location.hash.slice(1))===id) applySelection(id);
  else location.hash=encodeURIComponent(id);
}

function applySelection(id){
  if(!byId.get(id)) return;
  if(state.sel && state.sel!==id){ state.trail.push(state.sel); state.trail=state.trail.slice(-8); }
  state.sel=id;
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
  if(catChanged){ renderRail(); renderList(); syncListSelection(); }
  else syncListSelection();
  renderDetail();
}

// ---------- search ----------
const q=document.getElementById('q'), results=document.getElementById('results');
let resSel=-1, resList=[];
// search haystack: base identity plus a few type-specific extras. A DataObject's
// fields/columns are not nodes of their own, so without this a field name like
// 'crewId' (or its label / type) would never surface its data object.
function searchText(n){
  const d=n.data||{};
  let s=n.label+' '+n.key+' '+(n.file||'')+' '+n.type;
  if(n.type==='dataObject') s+=' '+(d.fields||[]).join(' ')+' '+
    (d.columns||[]).map(c=>(c.label||'')+' '+(c.type||'')).join(' ');
  if(n.type==='service') s+=' '+(d.columns||[]).map(c=>(c.name||'')+' '+(c.columnName||'')+' '+(c.type||'')).join(' ');
  if(n.type==='liquibase') s+=' '+(d.columns||[]).map(c=>(c.name||'')+' '+(c.type||'')).join(' ');
  return s.toLowerCase();
}
function doSearch(){
  const v=q.value.trim().toLowerCase();
  if(!v){ results.classList.remove('on'); return; }
  resList = nodes.filter(n=>searchText(n).includes(v))
                 .sort((a,b)=> a.label.length-b.label.length).slice(0,40);
  results.innerHTML = resList.map((n,i)=>'<div class="r'+(i===resSel?' sel':'')+'" id="sr-'+i+'" role="option" aria-selected="'+(i===resSel)+'" data-id="'+enc(n.id)+'">'+
    '<span class="dot" style="background:'+nodeColor(n)+'"></span><span class="nm">'+esc(n.label)+'</span>'+
    '<span class="ty mono" style="color:var(--ink-faint);font-size:10px">'+esc(nodeKind(n))+'</span>'+
    '<span class="mono" style="margin-left:auto;color:var(--ink-faint);font-size:10px">'+esc(n.key)+'</span></div>').join('')
    || '<div class="r muted">no matches</div>';
  results.classList.add('on');
  q.setAttribute('aria-expanded','true');
  if(resSel>=0) q.setAttribute('aria-activedescendant','sr-'+resSel); else q.removeAttribute('aria-activedescendant');
  results.querySelectorAll('.r[data-id]').forEach(r=>r.onclick=()=>{ select(dec(r.dataset.id)); closeSearch(); });
}
function closeSearch(){ results.classList.remove('on'); q.setAttribute('aria-expanded','false'); q.removeAttribute('aria-activedescendant'); q.blur(); resSel=-1; }
q.addEventListener('input',debounce(()=>{ resSel=-1; doSearch(); },120));
q.addEventListener('keydown',e=>{
  if(e.key==='ArrowDown'){ resSel=Math.min(resSel+1,resList.length-1); doSearch(); e.preventDefault(); }
  else if(e.key==='ArrowUp'){ resSel=Math.max(resSel-1,0); doSearch(); e.preventDefault(); }
  else if(e.key==='Enter' && resList[resSel]){ select(resList[resSel].id); closeSearch(); }
  else if(e.key==='Escape'){ closeSearch(); }
});
document.addEventListener('keydown',e=>{ if(e.key==='/' && document.activeElement!==q){ e.preventDefault(); q.focus(); } });
document.addEventListener('click',e=>{ if(!e.target.closest('.search')) results.classList.remove('on'); });

// ---------- utils ----------
function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function enc(s){ return encodeURIComponent(s); }
function dec(s){ return decodeURIComponent(s); }

// ---------- theme ----------
// Preference cycle: auto (follow the OS) → light → dark. JS always resolves the effective
// theme onto <html data-theme=…>, so the CSS needs only one light-override block; because
// all node colors are emitted as var() references, a switch restyles without re-rendering.
function themePref(){ let p=null; try{ p=localStorage.getItem('atlas-theme'); }catch(e){} return p||'auto'; }
function applyThemePref(){
  const pref=themePref();
  const sys=matchMedia('(prefers-color-scheme: light)').matches?'light':'dark';
  document.documentElement.dataset.theme = pref==='auto'?sys:pref;
  const b=document.getElementById('themebtn');
  if(b){ b.textContent = pref==='auto'?'◐':(pref==='light'?'☀':'☾');
         b.title='Theme: '+pref+' — click to switch'; }
}
document.getElementById('themebtn').onclick=()=>{
  const next={auto:'light', light:'dark', dark:'auto'}[themePref()];
  try{ localStorage.setItem('atlas-theme', next); }catch(e){}   // private mode / file:// quirks
  applyThemePref();
};
matchMedia('(prefers-color-scheme: light)').addEventListener('change',applyThemePref);
applyThemePref();

// ---------- diagnostics (parse/read failures of the generator run) ----------
const diags=DATA.diagnostics||[];
function renderDiagBadge(){
  if(!diags.length) return '';
  return '<span id="diagbtn" title="Files the generator could not fully analyze — the map may be incomplete">⚠ <b>'+diags.length+'</b> parse issue'+(diags.length>1?'s':'')+'</span>';
}
function toggleDiagPanel(){
  const p=document.getElementById('diagpanel');
  document.getElementById('cfnpanel').classList.remove('on');  // panels share the top-right anchor
  if(p.classList.contains('on')){ p.classList.remove('on'); return; }
  p.innerHTML='<div class="dp-head">Files that could not be fully analyzed — their models/relations may be missing from this map.</div>'+
    diags.map(d=>'<div class="dp-row"><span class="dp-kind">'+esc(d.kind)+'</span>'+
      '<span class="dp-path mono">'+esc(d.path)+'</span>'+
      '<span class="dp-msg">'+esc(d.message)+'</span></div>').join('');
  p.classList.add('on');
}

// ---------- custom frontend functions (externals.additionalData) ----------
const cfns=DATA.customFunctions;
const cfnTotal=cfns?Object.values(cfns.namespaces).reduce((a,m)=>a+m.length,0)+cfns.flw.length+cfns.topLevel.length:0;
function renderCustomBadge(){
  if(!cfns||!cfnTotal) return '';
  return '<span id="cfnbtn" title="Project custom functions from flowable.externals.additionalData — read from source and validated precisely">🧩 <b>'+cfnTotal+'</b> custom fn'+(cfnTotal>1?'s':'')+'</span>';
}
function chips(names){ return '<div class="cf-mem">'+names.map(n=>'<span>'+esc(n)+'</span>').join('')+'</div>'; }
function toggleCustomPanel(){
  const p=document.getElementById('cfnpanel');
  document.getElementById('diagpanel').classList.remove('on');  // panels share the top-right anchor
  if(p.classList.contains('on')){ p.classList.remove('on'); return; }
  let h='<div class="dp-head">Custom functions the project injects via <b>flowable.externals.additionalData</b>. '+
    'Read from source, so calls to them validate precisely (a close typo is flagged; unknown names are not).'+
    (cfns.sources.length?' Source: <span class="mono">'+esc(cfns.sources.join(', '))+'</span>':'')+'</div>';
  Object.keys(cfns.namespaces).sort().forEach(ns=>{
    h+='<div class="cf-ns"><b>'+esc(ns)+'.*</b> <span class="cf-src">('+cfns.namespaces[ns].length+')</span>'+chips(cfns.namespaces[ns])+'</div>';
  });
  if(cfns.flw.length) h+='<div class="cf-ns"><b>flw.*</b> <span class="cf-src">(+'+cfns.flw.length+' custom)</span>'+chips(cfns.flw)+'</div>';
  if(cfns.topLevel.length) h+='<div class="cf-ns"><b>top-level</b> <span class="cf-src">('+cfns.topLevel.length+')</span>'+chips(cfns.topLevel)+'</div>';
  (cfns.diagnostics||[]).forEach(d=>{ h+='<div class="dp-row"><span class="dp-kind">note</span><span class="dp-msg">'+esc(d)+'</span></div>'; });
  p.innerHTML=h; p.classList.add('on');
}

// ---------- boot ----------
document.getElementById('proj').textContent=DATA.project;
const st=DATA.stats;
const invalidN=nodes.filter(n=>(n.data.problems||[]).some(p=>p.severity==='error')).length;
const suspectN=nodes.filter(n=>(n.data.problems||[]).length && !(n.data.problems||[]).some(p=>p.severity==='error')).length;
document.getElementById('stats').innerHTML=
  '<span><b>'+nodes.length+'</b> nodes</span><span><b>'+edges.length+'</b> links</span>'+
  '<span><b>'+(st.models||0)+'</b> models</span><span><b>'+(st.java||0)+'</b> java</span><span><b>'+(st.groups||0)+'</b> groups</span>'+
  (invalidN?'<span style="color:'+color('invalidExpr')+'"><b>'+invalidN+'</b> invalid</span>':'')+
  (suspectN?'<span style="color:'+color('suspectExpr')+'"><b>'+suspectN+'</b> suspect</span>':'')+
  renderCustomBadge()+
  renderDiagBadge();
const db=document.getElementById('diagbtn'); if(db) db.onclick=toggleDiagPanel;
const cb=document.getElementById('cfnbtn'); if(cb) cb.onclick=toggleCustomPanel;
document.addEventListener('click',e=>{
  const p=document.getElementById('diagpanel');
  if(p && p.classList.contains('on') && !e.target.closest('#diagpanel') && !e.target.closest('#diagbtn')) p.classList.remove('on');
  const c=document.getElementById('cfnpanel');
  if(c && c.classList.contains('on') && !e.target.closest('#cfnpanel') && !e.target.closest('#cfnbtn')) c.classList.remove('on'); });
state.cat = (CATS.find(c=>c.id==='process')||CATS[0]||{}).id;
renderRail(); renderList(); renderDetail();
const hash=location.hash?decodeURIComponent(location.hash.slice(1)):'';
if(hash && byId.get(hash)) applySelection(hash);
window.addEventListener('hashchange',()=>{ const h=dec(location.hash.slice(1)); if(h&&byId.get(h)&&h!==state.sel) applySelection(h); });
