/* Flowable Atlas — the ER diagram designer, the explorer extension "erd" (ExplorerHtmlRenderer inlines this
   file only when it was chosen: `--extension erd`, or Settings → Generation in the plugin).

   A page to explain a project's data model to people who never read a changelog: drag the project's tables
   onto a canvas, show each with its first columns, draw the relations between them with a name and a
   cardinality, colour them. The tables are the ones Atlas already knows — the schema the Liquibase
   changelogs leave behind once they have run, and database services whose table no changelog creates — so
   a diagram is a *view* of the live schema: it stores layout and decisions, never a second copy of the
   columns it could get wrong.

   This script runs before explorer.js (it is its own <script>, ahead of the explorer's) and registers itself
   on window.ATLAS_EXT; everything it borrows from explorer.js (esc, toast, debounce, DATA, …) is only
   touched from functions that run after the explorer has booted. Top-level names are prefixed `erd` /
   `ERD_`: the two scripts share one global scope, and a clash would stop the explorer from loading at all. */

/*__ERD_CORE_START__*/
// The pure part — no DOM, no explorer globals — so scripts/erd-selftest.mjs can run it in Node.
const ERD_FORMAT='atlas-erd', ERD_FORMAT_VERSION=1;
const ERD_CARDINALITIES=['1:1','1:n','n:1','n:m'];
/** Colours a table can wear. Literal hex, not theme tokens: a colour is part of the diagram file and of an
 *  exported picture, and must mean the same thing in a dark IDE, a light browser and a slide. */
const ERD_SWATCHES=['#2f6fed','#0e9f6e','#e8590c','#d6336c','#7048e8','#0c8599','#f59f00','#868e96'];

/** A table's identity: Liquibase and the databases it targets compare table names case-insensitively,
 *  and the replay upper-cases them — `ord_order` in one changelog is `ORD_ORDER` in the next. */
function erdKey(name){ return String(name==null?'':name).trim().toUpperCase(); }

/**
 * Every table the project defines, from the explorer's payload. A table comes from the replayed Liquibase
 * schema when a changelog creates it (a live changelog wins over an orphaned one, and both over a
 * superseded one), else from a database service's `tableName` with the service's column mappings — then
 * the types are the service's logical ones, and the entry says so. Each table knows the services and data
 * objects behind it; the name of the one data object that reads it is its default business name.
 */
function erdCatalog(nodes){
  const RANK={live:0, orphan:1, superseded:2};
  const byKey=new Map();
  (nodes||[]).forEach(n=>{
    if(!n || n.type!=='liquibase') return;
    const d=n.data||{}, status=(d.authority&&d.authority.status)||'live';
    const rank=RANK[status]!=null?RANK[status]:1;
    const groups=new Map();
    (d.columns||[]).forEach(c=>{
      if(!c || !c.name || !c.table) return;
      const k=erdKey(c.table);
      if(!groups.has(k)) groups.set(k, {name:c.table, columns:[], seen:new Set()});
      const g=groups.get(k), ck=erdKey(c.name);
      if(g.seen.has(ck)) return;
      g.seen.add(ck);
      g.columns.push({name:c.name, type:c.type||null, pk:c.pk===true});
    });
    groups.forEach((g,k)=>{
      const cur=byKey.get(k);
      if(cur && cur.rank<=rank) return;              // the first of equals wins: discovery order is stable
      byKey.set(k, {key:k, name:g.name, columns:g.columns, source:'liquibase', status, rank, changelog:n.id,
        services:[], dataObjects:[], alias:''});
    });
  });
  (nodes||[]).forEach(n=>{
    if(!n || n.type!=='service') return;
    const d=n.data||{};
    if(!d.tableName) return;
    const k=erdKey(d.tableName);
    let t=byKey.get(k);
    if(!t){
      const cols=[], seen=new Set();
      (d.columns||[]).forEach(c=>{
        const nm=c&&c.columnName;
        if(!nm || seen.has(erdKey(nm))) return;
        seen.add(erdKey(nm));
        cols.push({name:nm, type:c.type||null, pk:false, logical:true});
      });
      t={key:k, name:d.tableName, columns:cols, source:'service', status:'', rank:3, changelog:null,
        services:[], dataObjects:[], alias:''};
      byKey.set(k, t);
    }
    if(t.services.indexOf(n.id)<0) t.services.push(n.id);
  });
  (nodes||[]).forEach(n=>{
    if(!n || n.type!=='dataObject') return;
    const d=n.data||{};
    if(!d.serviceTableName) return;
    const t=byKey.get(erdKey(d.serviceTableName));
    if(t) t.dataObjects.push({id:n.id, key:n.key, name:d.name||n.label||n.key});
  });
  const out=[...byKey.values()];
  out.forEach(t=>{ if(t.dataObjects.length===1) t.alias=t.dataObjects[0].name; });
  return out.sort((a,b)=>a.name.localeCompare(b.name, undefined, {sensitivity:'base'}) || a.key.localeCompare(b.key));
}

/** The order a table's columns start in: its primary key first — what a reader looks for first — then the
 *  changelog's own order. The first five of it are what a collapsed card shows. */
function erdDefaultOrder(columns){
  return (columns||[]).filter(c=>c.pk).concat((columns||[]).filter(c=>!c.pk)).map(c=>c.name);
}

/** A remembered column order against the columns the table has now: a column that is gone drops out, a new
 *  one joins at the end, in its default place among the other new ones. Case-insensitive, like [erdKey]. */
function erdOrder(order, columns){
  const byK=new Map((columns||[]).map(c=>[erdKey(c.name), c.name]));
  const out=[], used=new Set();
  (order||[]).forEach(nm=>{ const k=erdKey(nm); if(byK.has(k) && !used.has(k)){ used.add(k); out.push(byK.get(k)); } });
  erdDefaultOrder(columns).forEach(nm=>{ const k=erdKey(nm); if(!used.has(k)){ used.add(k); out.push(nm); } });
  return out;
}

/**
 * Relations the models already state, as proposals: a data object field that references another data
 * object (its relationship type becomes the cardinality — one-to-one 1:1, one-to-many 1:n, as the model
 * says), and a database service's column relation to another service. Both ends must be tables; the id is
 * stable, so a dismissed proposal stays dismissed across reloads and in the diagram file.
 */
function erdSuggestions(nodes, edges){
  const tableOfDo=new Map(), tableOfSvc=new Map(), label=new Map();
  (nodes||[]).forEach(n=>{
    const d=(n&&n.data)||{};
    if(!n) return;
    label.set(n.id, n.label||n.key);
    if(n.type==='dataObject' && d.serviceTableName) tableOfDo.set(n.key, erdKey(d.serviceTableName));
    if(n.type==='service' && d.tableName) tableOfSvc.set(n.id, erdKey(d.tableName));
  });
  const out=[], seen=new Set();
  (nodes||[]).forEach(n=>{
    if(!n || n.type!=='dataObject') return;
    const from=tableOfDo.get(n.key);
    if(!from) return;
    ((n.data||{}).columns||[]).forEach(c=>{
      if(!c || !c.refDataObject) return;
      const to=tableOfDo.get(c.refDataObject);
      if(!to) return;
      const id='dataObject:'+n.key+'#'+c.name;
      if(seen.has(id)) return;
      seen.add(id);
      out.push({id, from, to, cardinality:c.relationship==='one-to-many'?'1:n':'1:1', label:c.label||c.name||'',
        why:'Data object '+(n.label||n.key)+' — field '+(c.label||c.name)+' refers to '+c.refDataObject+
            ' ('+(c.relationship||'relation')+')'});
    });
  });
  (edges||[]).forEach(e=>{
    if(!e || e.rel!=='relates-to-service') return;
    const from=tableOfSvc.get(e.s), to=tableOfSvc.get(e.t);
    if(!from || !to) return;
    const id='service:'+String(e.s).replace(/^service:/,'')+'>'+String(e.t).replace(/^service:/,'');
    if(seen.has(id)) return;
    seen.add(id);
    out.push({id, from, to, cardinality:'n:1', label:'',
      why:'Service '+(label.get(e.s)||e.s)+' has a column relation to '+(label.get(e.t)||e.t)});
  });
  return out;
}

/**
 * A diagram file (or a stored diagram) read defensively: it may come from another project, an older or a
 * hand-edited file. Anything that cannot be a table or a relation is dropped rather than failing the whole
 * import; only a file that is not an Atlas ER diagram at all — or one from a newer format — is refused.
 */
function erdNormalize(doc){
  if(!doc || typeof doc!=='object' || Array.isArray(doc)) throw new Error('not a JSON object');
  if(doc.format!==ERD_FORMAT) throw new Error('not an Atlas ER diagram — "format" is not "'+ERD_FORMAT+'"');
  const v=doc.version;
  if(typeof v!=='number' || v<1 || Math.floor(v)!==v) throw new Error('the file has no valid "version"');
  if(v>ERD_FORMAT_VERSION) throw new Error('written by a newer Atlas (format version '+v+'; this page reads '+ERD_FORMAT_VERSION+')');
  const str=(x,max)=>typeof x==='string'?x.slice(0,max||200):'';
  const num=(x,d)=>typeof x==='number'&&isFinite(x)?Math.round(Math.max(-1e6, Math.min(1e6, x))):d;
  const tables=[], keys=new Set();
  (Array.isArray(doc.tables)?doc.tables:[]).forEach((t,i)=>{
    if(!t || typeof t!=='object') return;
    const name=str(t.table,128).trim();
    if(!name) return;
    const k=erdKey(name);
    if(keys.has(k)) return;
    keys.add(k);
    const cols=(Array.isArray(t.columns)?t.columns:[]).filter(c=>c && typeof c.name==='string' && c.name).slice(0,500)
      .map(c=>({name:str(c.name,128), type:typeof c.type==='string'?str(c.type,128):null, pk:c.pk===true}));
    tables.push({key:k, name, alias:str(t.alias,120), x:num(t.x, 40+(i%4)*320), y:num(t.y, 40+Math.floor(i/4)*280),
      color:/^#[0-9a-f]{6}$/i.test(t.color||'')?t.color.toLowerCase():'', expanded:t.expanded===true,
      order:(Array.isArray(t.order)?t.order:[]).filter(s=>typeof s==='string').slice(0,500), columns:cols});
  });
  const relations=[], ids=new Set();
  (Array.isArray(doc.relations)?doc.relations:[]).forEach((r,i)=>{
    if(!r || typeof r!=='object') return;
    const from=erdKey(r.from), to=erdKey(r.to);
    if(!keys.has(from) || !keys.has(to)) return;
    let id=str(r.id,64)||('r'+(i+1));
    while(ids.has(id)) id+='_';
    ids.add(id);
    relations.push({id, from, to, fromColumn:str(r.fromColumn,128), toColumn:str(r.toColumn,128),
      cardinality:ERD_CARDINALITIES.indexOf(r.cardinality)>=0?r.cardinality:'1:n', label:str(r.label,200)});
  });
  return {name:str(doc.name,120).trim()||'Imported diagram', tables, relations,
    dismissed:(Array.isArray(doc.dismissed)?doc.dismissed:[]).filter(s=>typeof s==='string').slice(0,1000)};
}

/** A diagram as the file format (see site/pages/explorer.md). Relations name their tables as written. */
function erdToDoc(d, meta){
  meta=meta||{};
  const nameOf=new Map((d.tables||[]).map(t=>[t.key, t.name]));
  const doc={format:ERD_FORMAT, version:ERD_FORMAT_VERSION, name:d.name||''};
  if(meta.project) doc.project=meta.project;
  if(meta.atlasVersion) doc.atlasVersion=meta.atlasVersion;
  if(meta.exportedAt) doc.exportedAt=meta.exportedAt;
  doc.tables=(d.tables||[]).map(t=>({table:t.name, alias:t.alias||'', x:Math.round(t.x), y:Math.round(t.y),
    color:t.color||'', expanded:!!t.expanded, order:(t.order||[]).slice(),
    columns:(t.columns||[]).map(c=>c.pk?{name:c.name, type:c.type==null?null:c.type, pk:true}:{name:c.name, type:c.type==null?null:c.type})}));
  doc.relations=(d.relations||[]).map(r=>({id:r.id, from:nameOf.get(r.from)||r.from, to:nameOf.get(r.to)||r.to,
    fromColumn:r.fromColumn||'', toColumn:r.toColumn||'', cardinality:r.cardinality, label:r.label||''}));
  doc.dismissed=(d.dismissed||[]).slice();
  return doc;
}

/** Whether two diagrams say the same thing — the "changed here" test for a diagram from a project file. */
function erdSame(a, b){ return JSON.stringify(erdToDoc(a))===JSON.stringify(erdToDoc(b)); }

/** The two ends of a cardinality: `1:n` is one on the from-side and many on the to-side. */
function erdEnds(card){ const p=String(card||'1:n').split(':'); return [p[0]||'1', p[1]||'n']; }

/** Readable text on a coloured header: dark ink on a light colour, white on a dark one (WCAG luminance). */
function erdContrast(hex){
  const m=/^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex||'');
  if(!m) return '';
  const lin=v=>{ v/=255; return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055, 2.4); };
  const L=0.2126*lin(parseInt(m[1],16))+0.7152*lin(parseInt(m[2],16))+0.0722*lin(parseInt(m[3],16));
  return L>0.4?'#131e29':'#ffffff';
}

/** A file name for a diagram: `Orders & customers` → `orders-customers`. */
function erdSlug(name){
  return String(name||'').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g,'')
    .replace(/[^a-z0-9]+/g,'-').replace(/^-+|-+$/g,'').slice(0,60)||'diagram';
}
/*__ERD_CORE_END__*/

(function(){
'use strict';

const ICON='<rect width="8" height="7" x="2" y="3" rx="1"/><path d="M2 6.5h8"/><rect width="8" height="7" x="14" y="14" rx="1"/>'+
  '<path d="M14 17.5h8"/><path d="M10 6.5h3a2 2 0 0 1 2 2V14"/>';
const VISIBLE=5;
const ROW=22, HEAD1=32, HEAD2=44, FOOT=22, WMIN=200, WMAX=420;
// Single quotes inside: these end up in style="…" attributes, where a double quote would close the attribute.
const SANS="Geist, -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif";
const MONO="'SFMono-Regular', ui-monospace, Menlo, Monaco, Consolas, monospace";
const F_TITLE='600 13px '+SANS, F_SUB='11px '+MONO, F_ROW='12px '+SANS, F_TYPE='11px '+MONO, F_PILL='12px '+SANS;
const CARD_TEXT={'1:1':'one to one', '1:n':'one to many', 'n:1':'many to one', 'n:m':'many to many'};
/** The page's colours, as references: a theme switch restyles the canvas without a redraw. */
const PAGE={panel:'var(--panel)', line:'var(--line2)', head:'var(--panel2)', ink:'var(--ink)', dim:'var(--ink-dim)',
  faint:'var(--ink-faint)', accent:'var(--accent)', bg:'var(--bg)', pill:'var(--panel)'};
/** …and an exported picture's, as values: it leaves the page, so it cannot refer to it — and it is light,
 *  because it ends up on a slide or in a document, whatever theme the page was in. */
const PAPER={panel:'#ffffff', line:'#c9ced4', head:'#f1f5f9', ink:'#131e29', dim:'#4c5b6a', faint:'#79848f',
  accent:'#0f55d6', bg:'#ffffff', pill:'#ffffff'};

let S=null;          // the page's state, built on first use (the explorer's globals exist only after its boot)

// ---------- state: catalog, diagrams, persistence ----------
function state(){
  if(S) return S;
  const catalog=erdCatalog(DATA.nodes||[]);
  S={catalog, byKey:new Map(catalog.map(t=>[t.key,t])), suggestions:erdSuggestions(DATA.nodes||[], DATA.edges||[]),
    diagrams:[], activeId:null, undo:[], redo:[], sel:null, pop:null, root:null, filter:'', present:false,
    storageOk:true, projectErrors:[], lastCoalesce:null, drag:null, dirtyTimer:0};
  load();
  return S;
}
const storeKey=()=>'atlas-erd:'+DATA.project;   // every report on file:// shares one origin
function load(){
  let st=null;
  try{ st=JSON.parse(localStorage.getItem(storeKey())||'null'); }catch(e){ st=null; }
  const stored=new Map();
  ((st&&Array.isArray(st.diagrams))?st.diagrams:[]).forEach(x=>{
    try{ const d=erdNormalize(x.doc); stored.set(String(x.id), Object.assign(d, {id:String(x.id), stored:true, origin:null})); }
    catch(e){ /* an entry this page cannot read is dropped from the list, not allowed to break it */ }
  });
  // Diagrams that live in the project (embedded at generation) come first: they are the shared ones.
  (DATA.erdDiagrams||[]).forEach(p=>{
    if(!p || !p.file) return;
    if(p.error || !p.doc){ S.projectErrors.push(p.file+': '+(p.error||'unreadable')); return; }
    let base;
    try{ base=erdNormalize(p.doc); }catch(e){ S.projectErrors.push(p.file+': '+e.message); return; }
    const id='p:'+p.file, local=stored.get(id);
    stored.delete(id);
    S.diagrams.push(Object.assign(local||base, {id, stored:!!local, origin:{file:p.file, base}}));
  });
  stored.forEach(d=>S.diagrams.push(d));
  if(!S.diagrams.length) S.diagrams.push(blank('Diagram 1'));
  S.activeId=(st && S.diagrams.some(d=>d.id===st.active)) ? st.active : S.diagrams[0].id;
}
function blank(name){ return {id:'l:'+Date.now().toString(36)+Math.random().toString(36).slice(2,6), name, tables:[],
  relations:[], dismissed:[], stored:false, origin:null}; }
const active=()=>S.diagrams.find(d=>d.id===S.activeId)||S.diagrams[0];
function persist(){
  clearTimeout(S.dirtyTimer);
  const list=S.diagrams.filter(d=>d.stored).map(d=>({id:d.id, doc:erdToDoc(d)}));
  try{ localStorage.setItem(storeKey(), JSON.stringify({v:1, active:S.activeId, diagrams:list})); S.storageOk=true; }
  catch(e){
    if(S.storageOk) toast('This browser did not keep the diagram — export it to keep it');
    S.storageOk=false;
  }
  renderStatus();
}
function schedulePersist(){ clearTimeout(S.dirtyTimer); S.dirtyTimer=setTimeout(persist, 250); }
const snap=d=>JSON.stringify({name:d.name, tables:d.tables, relations:d.relations, dismissed:d.dismissed});
function restore(d, json){ const o=JSON.parse(json); d.name=o.name; d.tables=o.tables; d.relations=o.relations; d.dismissed=o.dismissed; }
/** Every change goes through here: one undo step, a save, a redraw. [coalesce] folds a run of the same edit
 *  (typing a name) into one step, so ⌘Z undoes the word rather than its last letter. */
function mutate(fn, coalesce){
  const d=active(), before=snap(d);
  fn(d);
  if(snap(d)===before) return false;
  const now=Date.now(), c=S.lastCoalesce;
  if(!(coalesce && c && c.key===coalesce && c.id===d.id && now-c.at<1500)){
    S.undo.push(before);
    if(S.undo.length>100) S.undo.shift();
  }
  S.lastCoalesce=coalesce?{key:coalesce, id:d.id, at:now}:null;
  S.redo=[];
  d.stored=true;
  schedulePersist();
  draw(); renderList(); renderStatus();
  return true;
}
function undo(){ step(S.undo, S.redo); }
function redo(){ step(S.redo, S.undo); }
function step(from, to){
  const d=active();
  if(!from.length) return;
  to.push(snap(d));
  restore(d, from.pop());
  d.stored=true; S.lastCoalesce=null;
  if(S.sel && !selected()) S.sel=null;
  closePop();
  schedulePersist(); draw(); renderList(); renderStatus();
}

/** A table on the canvas against the live schema: its columns as the project has them now (a table the
 *  project no longer has is drawn from the snapshot in the diagram, marked), its order reconciled. */
function effective(t){
  const live=S.byKey.get(t.key), columns=live?live.columns:t.columns;
  return {columns, order:erdOrder(t.order, columns), ghost:!live, live};
}
/** The diagram with every table's columns refreshed from the live schema — what an export writes. */
function fresh(d){
  const c=JSON.parse(JSON.stringify(d));
  c.tables.forEach(t=>{ const e=effective(t); t.columns=e.columns.map(x=>({name:x.name, type:x.type, pk:!!x.pk})); t.order=e.order; });
  return c;
}

// ---------- geometry ----------
let _ctx=null;
function tw(text, font){
  if(!_ctx) _ctx=document.createElement('canvas').getContext('2d');
  _ctx.font=font;
  return _ctx.measureText(String(text)).width;
}
function clip(text, font, max){
  text=String(text==null?'':text);
  if(max<=0) return '';
  if(tw(text,font)<=max) return text;
  let lo=0, hi=text.length;
  while(lo<hi){ const mid=(lo+hi+1)>>1; if(tw(text.slice(0,mid)+'…',font)<=max) lo=mid; else hi=mid-1; }
  return text.slice(0,lo)+'…';
}
/** Where everything on a card sits. The width is set by *all* columns, shown or not, so expanding a card
 *  makes it longer, never wider — the relations around it stay where they were. */
function layout(t){
  const e=effective(t), byK=new Map(e.columns.map(c=>[erdKey(c.name), c]));
  const ordered=e.order.map(n=>byK.get(erdKey(n))).filter(Boolean);
  const shown=t.expanded?ordered:ordered.slice(0, VISIBLE);
  const title=t.alias||t.name, sub=t.alias?t.name:'';
  const head=sub?HEAD2:HEAD1;
  let w=Math.max(WMIN, tw(title,F_TITLE)+56, sub?tw(sub,F_SUB)+40:0);
  ordered.forEach(c=>{ w=Math.max(w, 30+tw(c.name,F_ROW)+18+tw(c.type||'',F_TYPE)+12); });
  w=Math.min(WMAX, Math.ceil(w));
  const foot=ordered.length>VISIBLE || e.ghost;
  const rows=shown.map((c,i)=>({c, y:head+3+i*ROW}));
  const h=head+(shown.length?shown.length*ROW+6:0)+(foot?FOOT:0);
  return {t, x:t.x, y:t.y, w, h, head, title, sub, rows, total:ordered.length, more:ordered.length-shown.length,
    foot, ghost:e.ghost, ordered};
}
/** The point a relation leaves a card from, and the direction it leaves in. */
function sideOf(A, B){
  if(A===B) return ['right','right'];
  if(B.x>A.x+A.w+24) return ['right','left'];
  if(B.x+B.w+24<A.x) return ['left','right'];
  return B.y+B.h/2>A.y+A.h/2 ? ['bottom','top'] : ['top','bottom'];
}
const NORMAL={left:[-1,0], right:[1,0], top:[0,-1], bottom:[0,1]};
function anchorOf(L, side, column, offset){
  let y=L.y+L.head/2, x=L.x+L.w/2;
  if(side==='left'||side==='right'){
    const r=column && L.rows.find(r=>erdKey(r.c.name)===erdKey(column));
    y=r ? L.y+r.y+ROW/2 : y+offset;
    x=side==='left'?L.x:L.x+L.w;
  } else {
    x+=offset;
    y=side==='top'?L.y:L.y+L.h;
  }
  return {x, y, n:NORMAL[side]};
}
/** A relation's route: a cubic curve leaving each card square to its side, parallel relations fanned out. */
function route(r, lays, fan){
  const A=lays.get(r.from), B=lays.get(r.to);
  if(!A || !B) return null;
  if(A===B){
    const p1={x:A.x+A.w, y:A.y+A.head/2+fan*10, n:[1,0]}, p2={x:A.x+A.w, y:A.y+Math.min(A.h-8, A.head+30)+fan*10, n:[1,0]};
    const k=48+Math.abs(fan)*12;
    return {p1, p2, c1:{x:p1.x+k, y:p1.y-10}, c2:{x:p2.x+k, y:p2.y+10}};
  }
  const [sa, sb]=sideOf(A, B);
  const p1=anchorOf(A, sa, r.fromColumn, fan*14), p2=anchorOf(B, sb, r.toColumn, fan*14);
  const k=Math.max(36, Math.min(160, Math.hypot(p2.x-p1.x, p2.y-p1.y)/3));
  return {p1, p2, c1:{x:p1.x+p1.n[0]*k, y:p1.y+p1.n[1]*k}, c2:{x:p2.x+p2.n[0]*k, y:p2.y+p2.n[1]*k}};
}
const mid=g=>({x:(g.p1.x+3*g.c1.x+3*g.c2.x+g.p2.x)/8, y:(g.p1.y+3*g.c1.y+3*g.c2.y+g.p2.y)/8});
const pathD=g=>'M'+f(g.p1.x)+','+f(g.p1.y)+' C'+f(g.c1.x)+','+f(g.c1.y)+' '+f(g.c2.x)+','+f(g.c2.y)+' '+f(g.p2.x)+','+f(g.p2.y);
const f=v=>Math.round(v*10)/10;
/** How many relations share each pair, so each gets its own lane. */
function fans(list){
  const groups=new Map(), out=new Map();
  list.forEach(r=>{ const k=[r.from,r.to].sort().join('\u0000'); if(!groups.has(k)) groups.set(k,[]); groups.get(k).push(r); });
  groups.forEach(g=>g.forEach((r,i)=>out.set(r.id, i-(g.length-1)/2)));
  return out;
}

// ---------- drawing ----------
const st=o=>' style="'+Object.keys(o).filter(k=>o[k]!=null&&o[k]!=='').map(k=>k+':'+o[k]).join(';')+'"';
function textEl(x, y, s, font, fill, extra){
  return '<text x="'+f(x)+'" y="'+f(y)+'"'+(extra||'')+st({font, fill})+'>'+esc(s)+'</text>';
}
/** The cardinality mark at one end: a bar for one, a crow's foot for many, and the letter beside it —
 *  the crow's foot is the notation, the letter is for whoever has never seen one. */
function endMark(p, end, C){
  const [nx,ny]=p.n, tx=-ny, ty=nx, out=[];
  const L=(a,b)=>'<path d="M'+f(a.x)+','+f(a.y)+' L'+f(b.x)+','+f(b.y)+'"'+st({stroke:C.dim, 'stroke-width':1.5, fill:'none'})+'/>';
  if(end==='1'){
    const q={x:p.x+nx*11, y:p.y+ny*11};
    out.push(L({x:q.x+tx*6, y:q.y+ty*6}, {x:q.x-tx*6, y:q.y-ty*6}));
  } else {
    const q={x:p.x+nx*13, y:p.y+ny*13};
    out.push(L(q, {x:p.x+tx*7, y:p.y+ty*7}), L(q, {x:p.x-tx*7, y:p.y-ty*7}), L(q, p));
  }
  const lx=p.x+nx*19+tx*9, ly=p.y+ny*19+ty*9;
  out.push(textEl(lx, ly+4, end, '600 11px '+SANS, C.dim, ' text-anchor="middle"'));
  return out.join('');
}
function relationSvg(r, g, C, o){
  const [ea, eb]=erdEnds(r.cardinality), d=pathD(g), sel=o.sel;
  let s='<g class="erd-rel'+(sel?' sel':'')+'" data-rel="'+esc(r.id)+'">';
  s+='<path d="'+d+'"'+st({fill:'none', stroke:sel?C.accent:C.dim, 'stroke-width':sel?2.25:1.5})+'/>';
  s+=endMark(g.p1, ea, C)+endMark(g.p2, eb, C);
  if(o.interactive) s+='<path class="erd-relhit" d="'+d+'"'+st({fill:'none', stroke:'transparent', 'stroke-width':14})+'/>';
  const text=r.label?r.label+'  ·  '+r.cardinality:'';
  if(text){
    const m=mid(g), w=tw(text, F_PILL)+18;
    s+='<g class="erd-pill"><rect x="'+f(m.x-w/2)+'" y="'+f(m.y-11)+'" width="'+f(w)+'" height="22" rx="11"'+
       st({fill:C.pill, stroke:sel?C.accent:C.line, 'stroke-width':1})+'/>'+textEl(m.x, m.y+4, text, F_PILL, C.ink, ' text-anchor="middle"')+'</g>';
  }
  return s+'</g>';
}
function suggestionSvg(sg, g, C){
  const m=mid(g), text='+ '+(sg.label||'suggested')+'  ·  '+sg.cardinality, w=tw(text, F_PILL)+18;
  return '<g class="erd-sug" data-sug="'+esc(sg.id)+'" data-tip="'+esc(sg.why+' — click to add this relation, × to dismiss it')+'">'+
    '<path d="'+pathD(g)+'"'+st({fill:'none', stroke:C.faint, 'stroke-width':1.25, 'stroke-dasharray':'5 4'})+'/>'+
    '<path class="erd-relhit" d="'+pathD(g)+'"'+st({fill:'none', stroke:'transparent', 'stroke-width':12})+'/>'+
    '<g class="erd-sug-add"><rect x="'+f(m.x-w/2)+'" y="'+f(m.y-11)+'" width="'+f(w)+'" height="22" rx="11"'+
      st({fill:C.pill, stroke:C.faint, 'stroke-width':1, 'stroke-dasharray':'3 3'})+'/>'+
      textEl(m.x, m.y+4, text, F_PILL, C.dim, ' text-anchor="middle"')+'</g>'+
    '<g class="erd-sug-x" data-tip="Dismiss this suggestion"><circle cx="'+f(m.x+w/2+12)+'" cy="'+f(m.y)+'" r="8"'+st({fill:C.pill, stroke:C.faint})+'/>'+
      textEl(m.x+w/2+12, m.y+4, '×', '12px '+SANS, C.dim, ' text-anchor="middle"')+'</g></g>';
}
const KEY_ICON='<circle cx="4" cy="7" r="2.6"/><path d="M6.6 7H13M11 7v2.6M13 7v2"/>';
function cardSvg(L, C, o){
  const t=L.t, col=t.color, hi=col?erdContrast(col):'', sel=o.sel, w=L.w;
  let s='<g class="erd-card'+(sel?' sel':'')+(L.ghost?' ghost':'')+'" data-key="'+esc(t.key)+'" transform="translate('+f(L.x)+','+f(L.y)+')">';
  s+='<rect class="erd-box" width="'+w+'" height="'+L.h+'" rx="8"'+st({fill:C.panel, stroke:sel?C.accent:C.line,
    'stroke-width':sel?2:1, 'stroke-dasharray':L.ghost?'6 4':''})+'/>';
  s+='<path class="erd-head" d="M0,8 a8,8 0 0 1 8,-8 h'+(w-16)+' a8,8 0 0 1 8,8 v'+(L.head-8)+' h-'+w+' z"'+st({fill:col||C.head})+'/>';
  if(!col) s+='<path d="M0,'+L.head+' h'+w+'"'+st({stroke:C.line, 'stroke-width':1})+'/>';
  const maxT=w-(o.interactive?44:24);
  s+=textEl(12, L.sub?19:20.5, clip(L.title, F_TITLE, maxT), F_TITLE, hi||C.ink);
  if(L.sub) s+=textEl(12, 35, clip(L.sub, F_SUB, w-24), F_SUB, hi||C.dim, hi?' opacity=".82"':'');
  if(o.interactive){
    s+='<g class="erd-tools" data-act="edit" data-tip="Edit — name, colour, column order"><rect x="'+(w-30)+'" y="'+(L.head/2-11)+
       '" width="22" height="22" rx="5"'+st({fill:'transparent'})+'/>'+
       textEl(w-19, L.head/2+4.5, '⋯', '700 14px '+SANS, hi||C.dim, ' text-anchor="middle"')+'</g>';
  }
  L.rows.forEach(r=>{
    const c=r.c, typeW=Math.min(tw(c.type||'',F_TYPE), w*0.45), nameMax=w-30-typeW-22;
    s+='<g class="erd-row" data-col="'+esc(c.name)+'" transform="translate(0,'+r.y+')">';
    if(o.interactive) s+='<rect class="erd-rowhit" x="1" width="'+(w-2)+'" height="'+ROW+'"'+st({fill:'transparent'})+'/>';
    if(c.pk) s+='<g transform="translate(9,4)"'+st({fill:'none', stroke:C.dim, 'stroke-width':1.2})+' aria-label="primary key">'+KEY_ICON+'</g>';
    const fr=c.pk?'600 '+F_ROW:F_ROW;
    s+=textEl(26, 15, clip(c.name, fr, nameMax), fr, C.ink);
    if(c.type) s+=textEl(w-10, 15, clip(c.type, F_TYPE, typeW+1), F_TYPE, c.logical?C.faint:C.dim, ' text-anchor="end"');
    if(o.interactive) s+='<g class="erd-grip" data-tip="Drag to reorder — the first 5 show on a folded card">'+
      '<rect x="1" y="3" width="14" height="16" rx="3"'+st({fill:'transparent'})+'/>'+
      '<path d="M6 7h.01M10 7h.01M6 11h.01M10 11h.01M6 15h.01M10 15h.01"'+st({stroke:C.faint, 'stroke-width':2, 'stroke-linecap':'round'})+'/></g>';
    s+='</g>';
  });
  if(L.foot){
    const y=L.h-FOOT;
    s+='<g class="erd-foot"'+(o.interactive&&!L.ghost?' data-act="toggle"':'')+'><path d="M0,'+y+' h'+w+'"'+st({stroke:C.line, 'stroke-width':1})+'/>';
    if(o.interactive) s+='<rect y="'+y+'" width="'+w+'" height="'+FOOT+'"'+st({fill:'transparent'})+'/>';
    const label=L.ghost?'not in this project’s schema':t.expanded?'show fewer':'+ '+L.more+' more';
    s+=textEl(w/2, y+15, label, '11px '+SANS, L.ghost?C.faint:C.dim, ' text-anchor="middle"')+'</g>';
  }
  if(o.interactive){
    s+='<g class="erd-link" data-tip="Drag to another table to draw a relation"><circle cx="'+w+'" cy="'+f(L.head/2)+'" r="11"'+st({fill:'transparent'})+'/>'+
       '<circle class="erd-link-dot" cx="'+w+'" cy="'+f(L.head/2)+'" r="5.5"'+st({fill:C.panel, stroke:C.accent, 'stroke-width':1.75})+'/></g>';
  }
  return s+'</g>';
}
/** The whole diagram as SVG markup: the canvas draws it with the page's colours and its handles, an export
 *  with paper colours and nothing interactive — one drawing, so the picture is what the screen showed. */
function sceneSvg(d, C, o){
  const lays=new Map(d.tables.map(t=>[t.key, layout(t)]));
  const rels=d.relations.filter(r=>lays.has(r.from)&&lays.has(r.to));
  const sugs=o.suggestions?visibleSuggestions(d, lays):[];
  const fan=fans(rels.concat(sugs));
  let s='<g class="erd-rels">';
  rels.forEach(r=>{ const g=route(r, lays, fan.get(r.id)||0); if(g) s+=relationSvg(r, g, C, {interactive:o.interactive, sel:o.sel&&o.sel.kind==='rel'&&o.sel.id===r.id}); });
  s+='</g><g class="erd-sugs">';
  sugs.forEach(sg=>{ const g=route(sg, lays, fan.get(sg.id)||0); if(g) s+=suggestionSvg(sg, g, C); });
  s+='</g><g class="erd-cards">';
  d.tables.forEach(t=>{ s+=cardSvg(lays.get(t.key), C, {interactive:o.interactive, sel:o.sel&&o.sel.kind==='table'&&o.sel.key===t.key}); });
  return {markup:s+'</g>', lays};
}
/** Proposals worth showing: both tables on the canvas, not dismissed, and not already drawn by hand. */
function visibleSuggestions(d, lays){
  // One proposal per pair of tables: a data object and its service often state the same relation twice,
  // and the data object's — which knows the cardinality — comes first.
  const taken=new Set(d.relations.map(r=>[r.from,r.to].sort().join('\u0000')));
  return S.suggestions.filter(sg=>{
    const pair=[sg.from,sg.to].sort().join('\u0000');
    if(!lays.has(sg.from) || !lays.has(sg.to) || d.dismissed.indexOf(sg.id)>=0 || taken.has(pair)) return false;
    taken.add(pair);
    return true;
  });
}
function bounds(lays, pad){
  let x0=Infinity, y0=Infinity, x1=-Infinity, y1=-Infinity;
  lays.forEach(L=>{ x0=Math.min(x0,L.x); y0=Math.min(y0,L.y); x1=Math.max(x1,L.x+L.w); y1=Math.max(y1,L.y+L.h); });
  if(!isFinite(x0)) return null;
  return {x:x0-pad, y:y0-pad, w:x1-x0+2*pad, h:y1-y0+2*pad};
}

// ---------- the page ----------
let els=null;
function view(){ const d=active(); if(!d.view) d.view={tx:40, ty:40, s:1, fitted:false}; return d.view; }
function render(root){
  state();
  if(!els || els.root!==root){ build(root); }
  renderPick(); renderList(); renderStatus();
  const v=view();
  draw();
  if(!v.fitted) requestAnimationFrame(()=>{ fit(); v.fitted=true; });
}
function build(root){
  root.classList.add('view-erd');
  if(!S.catalog.length){
    root.innerHTML='<div class="dash"><div class="erd-none"><div class="dtitle">ER diagram</div>'+
      '<p>This project defines no database tables Atlas can read: no Liquibase changelog creates one, and no database '+
      'service names one. The designer lists the tables of Liquibase changelogs (as they stand after every change set '+
      'has run) and of services with a table name.</p></div></div>';
    els={root, empty:true};
    return;
  }
  root.innerHTML=
    '<div class="erd">'+
      '<aside class="erd-side" aria-label="Tables">'+
        '<div class="erd-side-h"><div class="erd-side-t">Tables <span class="erd-count">'+S.catalog.length+'</span></div>'+
          '<input class="erd-filter" type="search" placeholder="Filter tables or columns…" aria-label="Filter tables or columns"></div>'+
        '<div class="erd-list" role="listbox" aria-label="Tables — drag one onto the canvas"></div>'+
        '<div class="erd-side-foot">Drag a table onto the canvas, or press <b>+</b>.</div>'+
      '</aside>'+
      '<div class="erd-main">'+
        '<div class="erd-bar" role="toolbar" aria-label="Diagram">'+
          '<select class="erd-pick" aria-label="Diagram"></select>'+
          btn('menu','Diagram','Diagrams — new, rename, duplicate, import',' erd-menubtn')+
          '<span class="erd-sep"></span>'+
          btn('undo', ico('<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/>'), 'Undo ('+MODK+'Z)')+
          btn('redo', ico('<path d="m15 14 5-5-5-5"/><path d="M20 9H9.5a5.5 5.5 0 0 0 0 11H13"/>'), 'Redo ('+MODK+'⇧Z)')+
          '<span class="erd-sep"></span>'+
          btn('zoom-out','−','Zoom out (−)')+btn('fit','fit','Fit the diagram (0)')+btn('zoom-in','+','Zoom in (+)')+
          '<span class="erd-pct">100%</span>'+
          '<span class="erd-grow"></span>'+
          '<span class="erd-status" aria-live="polite"></span>'+
          btn('export','Export','Export — diagram file, SVG, PNG',' erd-menubtn')+
          btn('present', ico('<path d="M2 3h20"/><path d="M21 3v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V3"/><path d="m7 21 5-5 5 5"/>')+'<span>Present</span>',
            'Present — hide everything but the diagram (Esc to leave)', ' erd-presentbtn')+
        '</div>'+
        '<div class="erd-canvas" tabindex="0" aria-label="Diagram canvas — drag to pan, '+MODK+'+wheel to zoom">'+
          '<svg class="erd-svg" xmlns="http://www.w3.org/2000/svg"><g class="erd-world"></g><g class="erd-overlay"></g></svg>'+
          '<div class="erd-empty" hidden><div class="erd-empty-t">Drag tables here</div>'+
            '<div>from the list on the left — then drag from a table’s dot to another table to relate them.</div></div>'+
          '<div class="erd-mini" role="toolbar" aria-label="Presentation">'+btn('zoom-out','−','Zoom out')+btn('fit','fit','Fit')+
            btn('zoom-in','+','Zoom in')+btn('present-off','Leave','Leave the presentation (Esc)')+'</div>'+
        '</div>'+
      '</div>'+
      '<div class="erd-pop" hidden role="dialog"></div>'+
      '<input type="file" class="erd-file" accept=".json,application/json" hidden>'+
    '</div>';
  const q=sel=>root.querySelector(sel);
  els={root, box:q('.erd'), list:q('.erd-list'), filter:q('.erd-filter'), pick:q('.erd-pick'), status:q('.erd-status'),
    pct:q('.erd-pct'), canvas:q('.erd-canvas'), svg:q('.erd-svg'), world:q('.erd-world'), overlay:q('.erd-overlay'),
    hint:q('.erd-empty'), pop:q('.erd-pop'), file:q('.erd-file'), bar:q('.erd-bar')};
  wire();
}
function ico(body){ return '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" '+
  'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+body+'</svg>'; }
function btn(act, html, tip, cls){ return '<button type="button" class="tbtn'+(cls||'')+'" data-act="'+act+'" data-tip="'+esc(tip)+'" aria-label="'+esc(tip)+'">'+html+'</button>'; }

function renderPick(){
  if(!els || els.empty) return;
  els.pick.innerHTML=S.diagrams.map(d=>'<option value="'+esc(d.id)+'"'+(d.id===S.activeId?' selected':'')+'>'+
    esc(d.name+(d.origin?' — project':''))+'</option>').join('');
}
function renderStatus(){
  if(!els || els.empty) return;
  const d=active();
  let s;
  if(d.origin){
    const changed=d.stored && !erdSame(d, d.origin.base);
    s=changed?'<span class="erd-changed">changed here</span> · export to update '+esc(d.origin.file)
             :'from '+esc(d.origin.file);
  } else s=S.storageOk?(d.stored?'saved in this browser':'not saved yet'):'<span class="erd-changed">not saved — export it</span>';
  els.status.innerHTML=s;
  const b=els.bar;
  b.querySelector('[data-act=undo]').disabled=!S.undo.length;
  b.querySelector('[data-act=redo]').disabled=!S.redo.length;
}
function renderList(){
  if(!els || els.empty) return;
  const d=active(), on=new Set(d.tables.map(t=>t.key)), q=S.filter.trim().toLowerCase();
  const rows=S.catalog.map(t=>{
    let hit='';
    if(q){
      const inName=(t.name+' '+t.alias+' '+t.dataObjects.map(x=>x.name).join(' ')).toLowerCase().indexOf(q)>=0;
      const col=inName?null:t.columns.find(c=>c.name.toLowerCase().indexOf(q)>=0);
      if(!inName && !col) return '';
      if(col) hit='<span class="erd-hit">'+esc(col.name)+'</span>';
    }
    const sub=[t.alias&&t.alias!==t.name?esc(t.alias):'', t.columns.length+' column'+(t.columns.length===1?'':'s'),
      t.source==='service'?'<span class="erd-src-svc" data-tip="No changelog creates this table — the columns are the service’s mappings, the types its logical ones">service model</span>':'']
      .filter(Boolean).join(' · ');
    return '<div class="erd-item'+(on.has(t.key)?' on':'')+'" role="option" tabindex="-1" data-key="'+esc(t.key)+'" aria-selected="'+on.has(t.key)+'">'+
      '<div class="erd-item-main"><div class="erd-item-n">'+esc(t.name)+'</div><div class="erd-item-s">'+sub+(hit?' · '+hit:'')+'</div></div>'+
      (on.has(t.key)?'<span class="erd-item-on" data-tip="On the canvas — click to find it">'+ico('<path d="M20 6 9 17l-5-5"/>')+'</span>'
                    :'<button type="button" class="erd-add" data-add="'+esc(t.key)+'" data-tip="Add to the canvas" aria-label="Add '+esc(t.name)+' to the canvas">+</button>')+
    '</div>';
  }).join('');
  els.list.innerHTML=rows||'<div class="erd-list-none">No table or column matches.</div>';
  const first=els.list.querySelector('.erd-item');
  if(first) first.tabIndex=0;
}
function draw(){
  if(!els || els.empty) return;
  const d=active();
  const {markup, lays}=sceneSvg(d, PAGE, {interactive:true, suggestions:!S.present, sel:S.sel});
  els.world.innerHTML=markup;
  els.lays=lays;
  applyView();
  els.hint.hidden=d.tables.length>0;
  els.canvas.classList.toggle('has-sel', !!S.sel);
}
function applyView(){
  const v=view();
  els.world.setAttribute('transform', 'translate('+f(v.tx)+','+f(v.ty)+') scale('+v.s+')');
  els.overlay.setAttribute('transform', els.world.getAttribute('transform'));
  // the dot grid moves and scales with the drawing, so a pan reads as moving over a surface
  els.canvas.style.backgroundPosition=f(v.tx)+'px '+f(v.ty)+'px';
  els.canvas.style.backgroundSize=f(20*v.s)+'px '+f(20*v.s)+'px';
  if(els.pct) els.root.querySelectorAll('.erd-pct').forEach(p=>{ p.textContent=Math.round(v.s*100)+'%'; });
}
function toWorld(cx, cy){
  const r=els.svg.getBoundingClientRect(), v=view();
  return {x:(cx-r.left-v.tx)/v.s, y:(cy-r.top-v.ty)/v.s};
}
function zoomAt(k, cx, cy){
  const v=view(), r=els.svg.getBoundingClientRect();
  const ox=cx==null?r.width/2:cx-r.left, oy=cy==null?r.height/2:cy-r.top;
  const next=Math.min(3, Math.max(0.2, v.s*k));
  v.tx=ox-(ox-v.tx)*(next/v.s); v.ty=oy-(oy-v.ty)*(next/v.s); v.s=next;
  applyView();
}
function fit(){
  if(!els || els.empty) return;
  const v=view(), b=bounds(els.lays||new Map(), 32), r=els.svg.getBoundingClientRect();
  if(!b || r.width<50 || r.height<50){ v.s=1; v.tx=40; v.ty=40; applyView(); return; }
  // presenting, a small diagram may grow to fill the room it has — it is being shown across a table
  v.s=Math.max(0.2, Math.min(S.present?1.6:1.25, r.width/b.w, r.height/b.h));
  v.tx=(r.width-b.w*v.s)/2-b.x*v.s; v.ty=(r.height-b.h*v.s)/2-b.y*v.s;
  applyView();
}
function center(key){
  const L=els.lays&&els.lays.get(key); if(!L) return;
  const v=view(), r=els.svg.getBoundingClientRect();
  v.tx=r.width/2-(L.x+L.w/2)*v.s; v.ty=r.height/2-(L.y+Math.min(L.h,160)/2)*v.s;
  applyView();
}

// ---------- editing ----------
function selected(){
  const d=active(), s=S.sel;
  if(!s) return null;
  if(s.kind==='table') return d.tables.find(t=>t.key===s.key)||null;
  return d.relations.find(r=>r.id===s.id)||null;
}
function select(sel){ S.sel=sel; draw(); }
function freeSpot(){
  const v=view(), r=els.svg.getBoundingClientRect(), n=active().tables.length;
  return {x:(r.width/2-v.tx)/v.s-110+(n%5)*28, y:(r.height/2-v.ty)/v.s-60+(n%5)*28};
}
function addTable(key, at){
  const c=S.byKey.get(key), d=active();
  if(!c) return;
  if(d.tables.some(t=>t.key===key)){ select({kind:'table', key}); center(key); return; }
  const p=at||freeSpot();
  mutate(d=>{ d.tables.push({key, name:c.name, alias:c.alias||'', x:Math.round(p.x), y:Math.round(p.y), color:'', expanded:false,
    order:erdDefaultOrder(c.columns), columns:c.columns.map(x=>({name:x.name, type:x.type, pk:!!x.pk}))}); });
  select({kind:'table', key});
}
function removeSelected(){
  const s=S.sel;
  if(!s) return;
  if(s.kind==='table') mutate(d=>{ d.tables=d.tables.filter(t=>t.key!==s.key); d.relations=d.relations.filter(r=>r.from!==s.key&&r.to!==s.key); });
  else mutate(d=>{ d.relations=d.relations.filter(r=>r.id!==s.id); });
  S.sel=null; closePop(); draw();
}
function newRelId(d){ let i=d.relations.length+1; while(d.relations.some(r=>r.id==='r'+i)) i++; return 'r'+i; }
function addRelation(from, to, toColumn, extra){
  let id=null;
  mutate(d=>{ id=newRelId(d); d.relations.push(Object.assign({id, from, to, fromColumn:'', toColumn:toColumn||'', cardinality:'1:n', label:''}, extra||{})); });
  return id;
}
function acceptSuggestion(id){
  const sg=S.suggestions.find(x=>x.id===id);
  if(!sg) return;
  const rid=addRelation(sg.from, sg.to, '', {cardinality:sg.cardinality, label:sg.label});
  if(rid){ S.sel={kind:'rel', id:rid}; draw(); toast('Relation added — click it to name it or change the cardinality'); }
}
function dismissSuggestion(id){ mutate(d=>{ if(d.dismissed.indexOf(id)<0) d.dismissed.push(id); }); }

// ---------- pointer: move, pan, relate, reorder ----------
function wire(){
  const svg=els.svg;
  svg.addEventListener('pointerdown', onDown);
  els.canvas.addEventListener('wheel', e=>{
    e.preventDefault();
    if(e.ctrlKey || e.metaKey){
      // proportional, and a 0 is no direction: the IDE's browser rounds a trackpad's small steps to 0 or ±1
      // (see wheelFactor in explorer.js's zoomable()) — reading 0 as "out" made zooming in impossible there
      const px=e.deltaY*(e.deltaMode===1?16:e.deltaMode===2?600:1);
      if(!px) return;
      const mag=Math.min(0.35, Math.max(0.02, Math.abs(px)*0.01));
      zoomAt(Math.pow(2, px<0?mag:-mag), e.clientX, e.clientY);
    } else {
      const v=view(), k=e.deltaMode===1?16:1;
      v.tx-=(e.shiftKey&&!e.deltaX?e.deltaY:e.deltaX)*k; v.ty-=(e.shiftKey&&!e.deltaX?0:e.deltaY)*k;
      applyView();
    }
  }, {passive:false});
  els.root.addEventListener('click', e=>{
    const b=e.target.closest('[data-act]');
    if(!b || !els.root.contains(b) || b.closest('svg')) return;
    doAct(b.dataset.act, b);
  });
  els.pick.addEventListener('change', ()=>switchTo(els.pick.value));
  els.filter.addEventListener('input', ()=>{ S.filter=els.filter.value; renderList(); });
  els.list.addEventListener('pointerdown', onListDown);
  els.list.addEventListener('click', e=>{
    const add=e.target.closest('[data-add]');
    if(add){ addTable(add.dataset.add); return; }
    const it=e.target.closest('.erd-item');
    if(it && it.classList.contains('on')){ select({kind:'table', key:it.dataset.key}); center(it.dataset.key); }
  });
  els.list.addEventListener('dblclick', e=>{ const it=e.target.closest('.erd-item'); if(it && !e.target.closest('[data-add]')) addTable(it.dataset.key); });
  els.list.addEventListener('keydown', e=>{
    const it=e.target.closest('.erd-item'); if(!it) return;
    if(e.key==='Enter'||e.key==='+'){ e.preventDefault(); addTable(it.dataset.key); }
    else if(e.key==='ArrowDown'||e.key==='ArrowUp'){
      e.preventDefault();
      const all=[...els.list.querySelectorAll('.erd-item')], i=all.indexOf(it)+(e.key==='ArrowDown'?1:-1);
      if(all[i]){ all.forEach(x=>x.tabIndex=-1); all[i].tabIndex=0; all[i].focus(); }
    }
  });
  els.file.addEventListener('change', ()=>{ const fl=els.file.files&&els.file.files[0]; if(fl) readFile(fl); els.file.value=''; });
  els.canvas.addEventListener('dragover', e=>{ if(e.dataTransfer && [...e.dataTransfer.types].indexOf('Files')>=0){ e.preventDefault(); els.canvas.classList.add('drop'); } });
  els.canvas.addEventListener('dragleave', ()=>els.canvas.classList.remove('drop'));
  els.canvas.addEventListener('drop', e=>{
    els.canvas.classList.remove('drop');
    const fl=e.dataTransfer&&e.dataTransfer.files&&e.dataTransfer.files[0];
    if(fl){ e.preventDefault(); readFile(fl); }
  });
}
function onDown(e){
  if(e.button!==0) return;
  closePop();
  // the page's keys (Delete, ⌘Z, +/−) answer outside a text field — leave the one the reader was typing in
  try{ els.canvas.focus({preventScroll:true}); }catch(err){}
  const t=e.target, svg=els.svg, start=toWorld(e.clientX, e.clientY), d=active();
  const cardEl=t.closest('.erd-card'), key=cardEl&&cardEl.dataset.key;
  let g=null;
  if(t.closest('.erd-sug-x')){ g={kind:'click', up:()=>dismissSuggestion(t.closest('.erd-sug').dataset.sug)}; }
  else if(t.closest('.erd-sug')){ g={kind:'click', up:()=>acceptSuggestion(t.closest('.erd-sug').dataset.sug)}; }
  else if(t.closest('.erd-rel')){
    const id=t.closest('.erd-rel').dataset.rel;
    g={kind:'click', up:ev=>{ select({kind:'rel', id}); openRelPop(id, ev); }};
  }
  else if(key && t.closest('.erd-link')){
    const L=els.lays.get(key);
    g={kind:'link', from:key, p:{x:L.x+L.w, y:L.y+L.head/2}};
  }
  else if(key && t.closest('.erd-grip')){
    const tb=d.tables.find(x=>x.key===key), col=t.closest('.erd-row').dataset.col;
    g={kind:'reorder', key, col, before:snap(d), tb};
  }
  else if(key && t.closest('[data-act=toggle]')){
    g={kind:'click', up:()=>mutate(d=>{ const x=d.tables.find(x=>x.key===key); if(x) x.expanded=!x.expanded; })};
  }
  else if(key && t.closest('[data-act=edit]')){
    g={kind:'click', up:()=>{ select({kind:'table', key}); openTablePop(key); }};
  }
  else if(key){
    const tb=d.tables.find(x=>x.key===key);
    g={kind:'move', key, tb, dx:start.x-tb.x, dy:start.y-tb.y, before:snap(d)};
  }
  else {
    const v=view();
    g={kind:'pan', tx:v.tx, ty:v.ty, cx:e.clientX, cy:e.clientY};
  }
  g.x0=e.clientX; g.y0=e.clientY; g.moved=false;
  S.drag=g;
  try{ svg.setPointerCapture(e.pointerId); }catch(err){}
  e.preventDefault();
  const move=ev=>{
    if(Math.abs(ev.clientX-g.x0)+Math.abs(ev.clientY-g.y0)>3) g.moved=true;
    const p=toWorld(ev.clientX, ev.clientY);
    if(g.kind==='pan'){ const v=view(); v.tx=g.tx+ev.clientX-g.cx; v.ty=g.ty+ev.clientY-g.cy; applyView(); }
    else if(g.kind==='move' && g.moved){
      g.tb.x=Math.round((p.x-g.dx)/4)*4; g.tb.y=Math.round((p.y-g.dy)/4)*4;
      if(!S.sel || S.sel.key!==g.key) S.sel={kind:'table', key:g.key};
      frame();
    }
    else if(g.kind==='link'){ linkPreview(g, p, ev); }
    else if(g.kind==='reorder' && g.moved){ reorderPreview(g, p); }
  };
  const up=ev=>{
    svg.removeEventListener('pointermove', move); svg.removeEventListener('pointerup', up); svg.removeEventListener('pointercancel', cancel);
    S.drag=null;
    els.overlay.innerHTML='';
    els.root.querySelectorAll('.erd-card.target').forEach(c=>c.classList.remove('target'));
    if(g.kind==='click'){ if(!g.moved) g.up(ev); return; }
    if(g.kind==='pan'){ if(!g.moved && S.sel){ S.sel=null; draw(); } return; }
    if(g.kind==='move'){
      if(g.moved){ commitGesture(g.before); return; }
      // A double click opens the card's panel. Counted here, not with `dblclick`: the pointer capture
      // retargets that event to the <svg>, where it no longer knows which card was clicked.
      const now=Date.now(), lc=S.lastClick;
      S.lastClick={key:g.key, at:now};
      select({kind:'table', key:g.key});
      if(lc && lc.key===g.key && now-lc.at<400){ S.lastClick=null; openTablePop(g.key); }
      return;
    }
    if(g.kind==='link'){
      const hit=document.elementFromPoint(ev.clientX, ev.clientY), card=hit&&hit.closest&&hit.closest('.erd-card');
      if(card && g.moved && !(card.dataset.key===g.from && hit.closest('.erd-link'))){
        const row=hit.closest('.erd-row');
        const id=addRelation(g.from, card.dataset.key, row?row.dataset.col:'');
        if(id){ S.sel={kind:'rel', id}; draw(); openRelPop(id, ev, true); }
      }
      return;
    }
    if(g.kind==='reorder'){
      if(g.moved && g.target!=null){
        const tb=g.tb, e2=effective(tb), order=e2.order.slice(), from=order.findIndex(n=>erdKey(n)===erdKey(g.col));
        if(from>=0){
          order.splice(from,1);
          order.splice(g.target>from?g.target-1:g.target, 0, g.col);
          tb.order=order;
          commitGesture(g.before);
        }
      }
      draw();
    }
  };
  const cancel=()=>{
    svg.removeEventListener('pointermove', move); svg.removeEventListener('pointerup', up); svg.removeEventListener('pointercancel', cancel);
    if((g.kind==='move'||g.kind==='reorder') && g.moved) restore(active(), g.before);
    S.drag=null; els.overlay.innerHTML=''; draw();
  };
  svg.addEventListener('pointermove', move); svg.addEventListener('pointerup', up);
  // a cancelled gesture (the IDE's browser losing the pointer) fires no pointerup — put everything back
  svg.addEventListener('pointercancel', cancel);
}
let _frame=0;
function frame(){ if(_frame) return; _frame=requestAnimationFrame(()=>{ _frame=0; draw(); }); }
/** A drag changed the diagram in place; record it as one undo step now that it is over. */
function commitGesture(before){
  const d=active();
  if(snap(d)===before) return;
  S.undo.push(before); if(S.undo.length>100) S.undo.shift();
  S.redo=[]; S.lastCoalesce=null; d.stored=true;
  schedulePersist(); draw(); renderList(); renderStatus();
}
function linkPreview(g, p, ev){
  const hit=document.elementFromPoint(ev.clientX, ev.clientY), card=hit&&hit.closest&&hit.closest('.erd-card');
  els.root.querySelectorAll('.erd-card.target').forEach(c=>c.classList.remove('target'));
  if(card && card.dataset.key!==g.from) card.classList.add('target');
  const k=Math.max(30, Math.abs(p.x-g.p.x)/2);
  els.overlay.innerHTML='<path d="M'+f(g.p.x)+','+f(g.p.y)+' C'+f(g.p.x+k)+','+f(g.p.y)+' '+f(p.x-k)+','+f(p.y)+' '+f(p.x)+','+f(p.y)+'"'+
    st({fill:'none', stroke:PAGE.accent, 'stroke-width':2, 'stroke-dasharray':'6 4'})+'/><circle cx="'+f(p.x)+'" cy="'+f(p.y)+'" r="4"'+st({fill:PAGE.accent})+'/>';
}
function reorderPreview(g, p){
  const L=els.lays.get(g.key);
  if(!L || !L.rows.length) return;
  const rel=(p.y-L.y-L.head-3)/ROW;
  g.target=Math.max(0, Math.min(L.rows.length, Math.round(rel)));
  const y=L.y+L.head+3+g.target*ROW;
  els.overlay.innerHTML='<path d="M'+f(L.x+6)+','+f(y)+' h'+f(L.w-12)+'"'+st({stroke:PAGE.accent, 'stroke-width':2.5, 'stroke-linecap':'round'})+'/>';
}
function onListDown(e){
  const it=e.target.closest('.erd-item');
  if(!it || e.button!==0 || e.target.closest('[data-add]')) return;
  const key=it.dataset.key, x0=e.clientX, y0=e.clientY;
  let ghost=null;
  try{ it.setPointerCapture(e.pointerId); }catch(err){}
  const move=ev=>{
    if(!ghost && Math.abs(ev.clientX-x0)+Math.abs(ev.clientY-y0)<5) return;
    if(!ghost){
      ghost=document.createElement('div');
      ghost.className='erd-ghost';
      ghost.textContent=(S.byKey.get(key)||{}).name||key;
      document.body.appendChild(ghost);
    }
    ghost.style.transform='translate('+(ev.clientX+10)+'px,'+(ev.clientY+6)+'px)';
    const r=els.canvas.getBoundingClientRect(), over=ev.clientX>=r.left&&ev.clientX<=r.right&&ev.clientY>=r.top&&ev.clientY<=r.bottom;
    els.canvas.classList.toggle('drop', over);
  };
  const end=(ev, drop)=>{
    it.removeEventListener('pointermove', move); it.removeEventListener('pointerup', upH); it.removeEventListener('pointercancel', cancelH);
    els.canvas.classList.remove('drop');
    if(!ghost) return;
    ghost.remove();
    if(!drop) return;
    const r=els.canvas.getBoundingClientRect();
    if(ev.clientX>=r.left&&ev.clientX<=r.right&&ev.clientY>=r.top&&ev.clientY<=r.bottom){
      const p=toWorld(ev.clientX, ev.clientY);
      addTable(key, {x:p.x-40, y:p.y-16});
    }
  };
  const upH=ev=>end(ev, true), cancelH=ev=>end(ev, false);
  it.addEventListener('pointermove', move); it.addEventListener('pointerup', upH); it.addEventListener('pointercancel', cancelH);
}

// ---------- popovers ----------
function closePop(){ if(els && els.pop && !els.pop.hidden){ els.pop.hidden=true; els.pop.innerHTML=''; S.pop=null; } }
/** One floating panel at a time, placed beside what it is about and kept inside the page. */
function openPop(kind, html, near){
  // A new element each time: the handlers of the last panel go with it instead of piling up on one node.
  const pop=els.pop.cloneNode(false);
  els.pop.replaceWith(pop); els.pop=pop;
  const box=els.box.getBoundingClientRect();
  pop.innerHTML=html; pop.hidden=false; pop.dataset.kind=kind; S.pop=kind;
  const pw=pop.offsetWidth, ph=pop.offsetHeight;
  let x=(near?near.x:box.left+box.width/2)-box.left, y=(near?near.y:box.top+80)-box.top;
  x=Math.max(8, Math.min(box.width-pw-8, x)); y=Math.max(8, Math.min(box.height-ph-8, y));
  pop.style.left=x+'px'; pop.style.top=y+'px';
  const first=pop.querySelector('[autofocus]'); if(first){ first.focus(); if(first.select) first.select(); }
}
function nearOfCard(key){
  const L=els.lays.get(key), r=els.svg.getBoundingClientRect(), v=view();
  return {x:r.left+(L.x+L.w)*v.s+v.tx+12, y:r.top+L.y*v.s+v.ty};
}
function openTablePop(key){
  const d=active(), t=d.tables.find(x=>x.key===key);
  if(!t) return;
  const c=S.byKey.get(key), e=effective(t);
  const src=[];
  if(c && c.changelog){ const n=byId.get(c.changelog); src.push('changelog <a href="#'+enc(c.changelog)+'">'+esc(n?n.label:c.changelog)+'</a>'); }
  if(c) c.services.forEach(id=>{ const n=byId.get(id); src.push('service <a href="#'+enc(id)+'">'+esc(n?n.label:id)+'</a>'); });
  if(c) c.dataObjects.forEach(x=>src.push('data object <a href="#'+enc(x.id)+'">'+esc(x.name)+'</a>'));
  const html='<div class="erd-pop-h"><span class="erd-pop-t">'+esc(t.name)+'</span>'+closeBtn()+'</div>'+
    (e.ghost?'<p class="erd-warn">This project’s schema has no table of this name — the card shows the columns the diagram was saved with.</p>':'')+
    '<label class="erd-fld"><span>Business name</span><input data-f="alias" value="'+esc(t.alias)+'" placeholder="'+esc(t.name)+'" autofocus></label>'+
    '<div class="erd-fld"><span>Colour</span><div class="erd-sw">'+
      '<button type="button" class="erd-swatch none'+(t.color?'':' on')+'" data-color="" data-tip="No colour" aria-label="No colour"></button>'+
      ERD_SWATCHES.map(h=>'<button type="button" class="erd-swatch'+(t.color===h?' on':'')+'" data-color="'+h+'" style="background:'+h+'" aria-label="Colour '+h+'"></button>').join('')+
      '<label class="erd-swatch custom'+(t.color&&ERD_SWATCHES.indexOf(t.color)<0?' on':'')+'" data-tip="Any colour"><input type="color" data-f="color" value="'+(t.color||'#2f6fed')+'" aria-label="Any colour"></label>'+
    '</div></div>'+
    '<div class="erd-fld"><span>Columns <em>the first '+VISIBLE+' show on a folded card</em></span><ol class="erd-cols"></ol>'+
      '<label class="erd-check"><input type="checkbox" data-f="expanded"'+(t.expanded?' checked':'')+'> Show all columns on the card</label></div>'+
    (src.length?'<div class="erd-srcs">From '+src.join(' · ')+'</div>':'')+
    '<div class="erd-pop-a"><button type="button" class="tbtn erd-danger" data-pa="remove">Remove from the diagram</button></div>';
  openPop('table', html, nearOfCard(key));
  renderPopCols(key);
  const pop=els.pop;
  pop.querySelector('[data-f=alias]').addEventListener('input', ev=>{ const val=ev.target.value; mutate(d=>{ const x=d.tables.find(x=>x.key===key); if(x) x.alias=val.slice(0,120); }, 'alias:'+key); });
  pop.querySelector('[data-f=color]').addEventListener('input', ev=>setColor(key, ev.target.value, true));
  pop.querySelector('[data-f=expanded]').addEventListener('change', ev=>{ const on=ev.target.checked; mutate(d=>{ const x=d.tables.find(x=>x.key===key); if(x) x.expanded=on; }); });
  pop.addEventListener('click', ev=>{
    const sw=ev.target.closest('[data-color]');
    if(sw){ setColor(key, sw.dataset.color); return; }
    const mv=ev.target.closest('[data-mv]');
    if(mv){ moveCol(key, mv.dataset.col, +mv.dataset.mv); return; }
    const a=ev.target.closest('[data-pa]');
    if(a && a.dataset.pa==='remove'){ S.sel={kind:'table', key}; removeSelected(); }
    if(ev.target.closest('[data-pa=close]')) closePop();
  });
}
function setColor(key, hex, coalesce){
  mutate(d=>{ const x=d.tables.find(x=>x.key===key); if(x) x.color=/^#[0-9a-f]{6}$/i.test(hex||'')?hex.toLowerCase():''; }, coalesce?'color:'+key:null);
  els.pop.querySelectorAll('.erd-swatch').forEach(s=>s.classList.toggle('on', (s.dataset.color||'')===(hex||'') ||
    (s.classList.contains('custom') && hex && ERD_SWATCHES.indexOf(hex.toLowerCase())<0)));
}
function renderPopCols(key, focusCol, focusDir){
  const t=active().tables.find(x=>x.key===key), ol=els.pop.querySelector('.erd-cols');
  if(!t || !ol) return;
  const e=effective(t), byK=new Map(e.columns.map(c=>[erdKey(c.name), c]));
  ol.innerHTML=e.order.map((n,i)=>{
    const c=byK.get(erdKey(n))||{name:n};
    return '<li class="'+(i===VISIBLE-1?'cut':'')+'"><span class="erd-cn">'+(c.pk?'<b class="erd-pk" data-tip="Primary key">PK</b>':'')+esc(c.name)+'</span>'+
      '<span class="erd-ct">'+esc(c.type||'')+'</span>'+
      '<button type="button" data-mv="-1" data-col="'+esc(c.name)+'"'+(i===0?' disabled':'')+' aria-label="Move '+esc(c.name)+' up">↑</button>'+
      '<button type="button" data-mv="1" data-col="'+esc(c.name)+'"'+(i===e.order.length-1?' disabled':'')+' aria-label="Move '+esc(c.name)+' down">↓</button></li>';
  }).join('');
  if(focusCol){ const b=ol.querySelector('[data-col="'+cssEsc(focusCol)+'"][data-mv="'+focusDir+'"]')||ol.querySelector('[data-col="'+cssEsc(focusCol)+'"]'); if(b) b.focus(); }
}
function moveCol(key, col, dir){
  mutate(d=>{
    const t=d.tables.find(x=>x.key===key); if(!t) return;
    const order=effective(t).order, i=order.findIndex(n=>erdKey(n)===erdKey(col)), j=i+dir;
    if(i<0 || j<0 || j>=order.length) return;
    const [name]=order.splice(i,1);
    order.splice(j, 0, name);
    t.order=order;
  });
  renderPopCols(key, col, dir);
}
function openRelPop(id, ev, fresh){
  const d=active(), r=d.relations.find(x=>x.id===id);
  if(!r) return;
  const A=d.tables.find(t=>t.key===r.from), B=d.tables.find(t=>t.key===r.to);
  const nm=t=>t?(t.alias||t.name):'?';
  const opts=(t, cur)=>'<option value="">— the table —</option>'+(t?effective(t).order.map(n=>'<option'+(erdKey(n)===erdKey(cur)?' selected':'')+'>'+esc(n)+'</option>').join(''):'');
  const html='<div class="erd-pop-h"><span class="erd-pop-t">Relation</span>'+closeBtn()+'</div>'+
    '<div class="erd-ends"><b>'+esc(nm(A))+'</b><span>→</span><b>'+esc(nm(B))+'</b>'+
      '<button type="button" class="tbtn" data-pa="swap" data-tip="Swap the direction">⇄</button></div>'+
    '<label class="erd-fld"><span>Name</span><input data-f="label" value="'+esc(r.label)+'" placeholder="e.g. places, belongs to"'+(fresh||!r.label?' autofocus':'')+'></label>'+
    '<div class="erd-fld"><span>Cardinality</span><div class="erd-seg" role="radiogroup" aria-label="Cardinality">'+
      ERD_CARDINALITIES.map(c=>'<button type="button" role="radio" data-card="'+c+'" aria-checked="'+(r.cardinality===c)+'">'+c+'</button>').join('')+
    '</div><div class="erd-say"></div></div>'+
    '<div class="erd-two"><label class="erd-fld"><span>From column</span><select data-f="fromColumn">'+opts(A, r.fromColumn)+'</select></label>'+
      '<label class="erd-fld"><span>To column</span><select data-f="toColumn">'+opts(B, r.toColumn)+'</select></label></div>'+
    '<div class="erd-pop-a"><button type="button" class="tbtn erd-danger" data-pa="delete">Delete the relation</button></div>';
  const g=els.lays && route(r, els.lays, 0), svgR=els.svg.getBoundingClientRect(), v=view();
  const near=ev&&ev.clientX!=null?{x:ev.clientX+14, y:ev.clientY-20}:g?{x:svgR.left+mid(g).x*v.s+v.tx+14, y:svgR.top+mid(g).y*v.s+v.ty-20}:null;
  openPop('rel', html, near);
  const pop=els.pop;
  const say=()=>{
    const x=active().relations.find(x=>x.id===id); if(!x) return;
    const a=nm(active().tables.find(t=>t.key===x.from)), b=nm(active().tables.find(t=>t.key===x.to));
    const t={'1:1':'Each '+a+' has one '+b+', and each '+b+' one '+a+'.', '1:n':'One '+a+' has many '+b+'.',
      'n:1':'Many '+a+' share one '+b+'.', 'n:m':'Many '+a+' relate to many '+b+'.'}[x.cardinality];
    pop.querySelector('.erd-say').textContent=t||'';
  };
  say();
  pop.querySelector('[data-f=label]').addEventListener('input', e2=>{ const val=e2.target.value; mutate(d=>{ const x=d.relations.find(x=>x.id===id); if(x) x.label=val.slice(0,200); }, 'label:'+id); });
  pop.querySelectorAll('select[data-f]').forEach(s=>s.addEventListener('change', ()=>{ const k=s.dataset.f, val=s.value; mutate(d=>{ const x=d.relations.find(x=>x.id===id); if(x) x[k]=val; }); }));
  pop.addEventListener('click', e2=>{
    const c=e2.target.closest('[data-card]');
    if(c){ const val=c.dataset.card; mutate(d=>{ const x=d.relations.find(x=>x.id===id); if(x) x.cardinality=val; });
      pop.querySelectorAll('[data-card]').forEach(b=>b.setAttribute('aria-checked', String(b.dataset.card===val))); say(); return; }
    const a=e2.target.closest('[data-pa]');
    if(!a) return;
    if(a.dataset.pa==='delete'){ S.sel={kind:'rel', id}; removeSelected(); }
    else if(a.dataset.pa==='swap'){
      mutate(d=>{ const x=d.relations.find(x=>x.id===id); if(!x) return; [x.from,x.to]=[x.to,x.from]; [x.fromColumn,x.toColumn]=[x.toColumn,x.fromColumn]; });
      openRelPop(id, null);
    }
    else if(a.dataset.pa==='close') closePop();
  });
}
function closeBtn(){ return '<button type="button" class="erd-x" data-pa="close" aria-label="Close">×</button>'; }

// ---------- diagrams: switch, new, rename, duplicate, delete, import, export ----------
function switchTo(id){
  if(!S.diagrams.some(d=>d.id===id)) return;
  S.activeId=id; S.undo=[]; S.redo=[]; S.sel=null; S.lastCoalesce=null;
  closePop(); persist(); renderPick(); renderList(); draw(); renderStatus();
  if(!view().fitted) requestAnimationFrame(()=>{ fit(); view().fitted=true; });
}
function uniqueName(base){ let n=base, i=2; while(S.diagrams.some(d=>d.name===n)) n=base+' ('+(i++)+')'; return n; }
function doAct(act, b){
  const d=active(), r=b&&b.getBoundingClientRect(), near=r?{x:r.left, y:r.bottom+6}:null;
  switch(act){
    case 'undo': undo(); break;
    case 'redo': redo(); break;
    case 'zoom-in': zoomAt(1.25); break;
    case 'zoom-out': zoomAt(1/1.25); break;
    case 'fit': fit(); break;
    case 'present': present(true); break;
    case 'present-off': present(false); break;
    case 'menu': {
      if(S.pop==='menu'){ closePop(); break; }
      const changed=d.origin && d.stored && !erdSame(d, d.origin.base);
      openPop('menu', '<div class="erd-menu" role="menu">'+
        mi('new','New diagram')+mi('rename','Rename…')+mi('duplicate','Duplicate')+
        (d.origin?(changed?mi('revert','Revert to '+d.origin.file):''):mi('delete','Delete…'))+
        '<div class="erd-msep"></div>'+mi('import','Import a diagram file…')+
        '<div class="erd-mnote">or drop a <code>.atlas-erd.json</code> onto the canvas'+(isIde()?', or paste one':'')+'</div>'+
        (S.projectErrors.length?'<div class="erd-msep"></div><div class="erd-mnote erd-warn">'+S.projectErrors.map(esc).join('<br>')+'</div>':'')+
      '</div>', near);
      menuWire({
        new:()=>ask('New diagram', uniqueName('Diagram '+(S.diagrams.length+1)), name=>{ const n=blank(name); n.stored=true; S.diagrams.push(n); switchTo(n.id); persist(); }),
        rename:()=>ask('Rename the diagram', d.name, name=>{ mutate(x=>{ x.name=name; }); renderPick(); }),
        duplicate:()=>{ const n=Object.assign(JSON.parse(JSON.stringify({name:d.name, tables:d.tables, relations:d.relations, dismissed:d.dismissed})),
          {id:blank('').id, stored:true, origin:null}); n.name=uniqueName(d.name+' copy'); S.diagrams.push(n); switchTo(n.id); persist(); },
        delete:()=>confirmPop('Delete “'+d.name+'”? It is only in this browser — export it first to keep it.', 'Delete', ()=>{
          S.diagrams=S.diagrams.filter(x=>x.id!==d.id); if(!S.diagrams.length) S.diagrams.push(blank('Diagram 1'));
          switchTo(S.diagrams[0].id); persist(); }),
        revert:()=>confirmPop('Drop the changes made here and show '+d.origin.file+' as it is in the project?', 'Revert', ()=>{
          const base=JSON.parse(JSON.stringify(d.origin.base)); Object.assign(d, base, {stored:false}); S.undo=[]; S.redo=[]; persist(); draw(); renderList(); renderStatus(); }),
        import:()=>els.file.click(),
      });
      break;
    }
    case 'export': {
      if(S.pop==='export'){ closePop(); break; }
      const ide=isIde();
      openPop('export', '<div class="erd-menu" role="menu">'+
        (ide?mi('copy-json','Copy the diagram file (.atlas-erd.json)')+mi('copy-svg','Copy as SVG')
            :mi('json','Diagram file (.atlas-erd.json)')+mi('svg','SVG image')+mi('png','PNG image'))+
        '<div class="erd-mnote">'+(ide?'Downloads need a browser: use Open in Browser for files and PNG.'
          :'The diagram file opens again here or in any Atlas explorer; commit it to the project and every generated explorer carries it.')+'</div>'+
      '</div>', near);
      menuWire({json:exportJson, svg:exportSvg, png:exportPng,
        'copy-json':()=>atlasCopy(JSON.stringify(docOf(), null, 2), ()=>toast('Diagram file copied')),
        'copy-svg':()=>atlasCopy(pictureSvg().svg, ()=>toast('SVG copied'))});
      break;
    }
  }
}
function mi(act, label){ return '<button type="button" role="menuitem" data-mi="'+act+'">'+esc(label)+'</button>'; }
function menuWire(handlers){
  els.pop.addEventListener('click', e=>{
    const b=e.target.closest('[data-mi]');
    if(!b) return;
    const h=handlers[b.dataset.mi];
    closePop();
    if(h) h();
  });
}
function ask(title, value, done){
  openPop('ask', '<div class="erd-pop-h"><span class="erd-pop-t">'+esc(title)+'</span>'+closeBtn()+'</div>'+
    '<form class="erd-ask"><input value="'+esc(value)+'" maxlength="120" autofocus aria-label="'+esc(title)+'">'+
    '<button type="submit" class="tbtn erd-primary">OK</button></form>', null);
  const form=els.pop.querySelector('form');
  form.addEventListener('submit', e=>{ e.preventDefault(); const v=form.querySelector('input').value.trim(); if(!v) return; closePop(); done(v); });
  els.pop.querySelector('[data-pa=close]').addEventListener('click', closePop);
}
function confirmPop(text, label, done){
  openPop('confirm', '<p class="erd-confirm">'+esc(text)+'</p><div class="erd-pop-a">'+
    '<button type="button" class="tbtn" data-pa="close">Cancel</button><button type="button" class="tbtn erd-danger" data-pa="ok" autofocus>'+esc(label)+'</button></div>', null);
  els.pop.addEventListener('click', e=>{ const a=e.target.closest('[data-pa]'); if(!a) return; closePop(); if(a.dataset.pa==='ok') done(); });
}
const isIde=()=>document.documentElement.classList.contains('ide');
function docOf(){ return erdToDoc(fresh(active()), {project:DATA.project, atlasVersion:DATA.atlasVersion, exportedAt:new Date().toISOString()}); }
function download(name, data, mime){
  const blob=data instanceof Blob?data:new Blob([data], {type:mime});
  const a=document.createElement('a');
  a.href=URL.createObjectURL(blob); a.download=name;
  document.body.appendChild(a); a.click();
  setTimeout(()=>{ URL.revokeObjectURL(a.href); a.remove(); }, 1500);
}
function exportJson(){ download(erdSlug(active().name)+'.atlas-erd.json', JSON.stringify(docOf(), null, 2)+'\n', 'application/json'); }
let _fontCss=null;
/** The page's Geist faces, so an exported SVG renders in the same letters wherever it is opened. They are
 *  already embedded in the page as data: URIs (explorer.css), so the picture needs no network either. */
function fontCss(){
  if(_fontCss!=null) return _fontCss;
  let css='';
  document.querySelectorAll('style').forEach(s=>{ (s.textContent.match(/@font-face\s*{[^}]*}/g)||[]).forEach(b=>{ if(/Geist/.test(b)) css+=b+'\n'; }); });
  return (_fontCss=css);
}
/** The diagram as a standalone picture: paper colours, no handles, no proposals, sized to its content. */
function pictureSvg(){
  const d=fresh(active()), {markup, lays}=sceneSvg(d, PAPER, {interactive:false, suggestions:false, sel:null});
  let b=bounds(lays, 40);
  if(!b) b={x:0, y:0, w:320, h:120};
  const w=Math.ceil(b.w), h=Math.ceil(b.h);
  const svg='<svg xmlns="http://www.w3.org/2000/svg" width="'+w+'" height="'+h+'" viewBox="'+f(b.x)+' '+f(b.y)+' '+w+' '+h+'">'+
    '<title>'+esc(d.name)+'</title><style>'+fontCss()+'</style>'+
    '<rect x="'+f(b.x)+'" y="'+f(b.y)+'" width="'+w+'" height="'+h+'" fill="'+PAPER.bg+'"/>'+markup+'</svg>';
  return {svg, w, h};
}
function exportSvg(){ download(erdSlug(active().name)+'.svg', pictureSvg().svg, 'image/svg+xml'); }
function exportPng(){
  const {svg, w, h}=pictureSvg(), img=new Image(), k=2;
  img.onload=()=>{
    const c=document.createElement('canvas');
    c.width=Math.ceil(w*k); c.height=Math.ceil(h*k);
    const g=c.getContext('2d');
    g.scale(k,k); g.drawImage(img, 0, 0, w, h);
    try{ c.toBlob(bl=>{ if(bl) download(erdSlug(active().name)+'.png', bl); else toast('This browser could not produce a PNG — export the SVG instead'); }, 'image/png'); }
    catch(e){ toast('This browser could not produce a PNG — export the SVG instead'); }
  };
  img.onerror=()=>toast('This browser could not produce a PNG — export the SVG instead');
  img.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(svg);
}
function readFile(file){
  const rd=new FileReader();
  rd.onload=()=>importText(String(rd.result||''), file.name);
  rd.onerror=()=>toast('Could not read '+file.name);
  rd.readAsText(file);
}
function importText(text, from){
  let doc;
  try{ doc=JSON.parse(text); }catch(e){ toast((from||'That')+' is not JSON'); return false; }
  let d;
  try{ d=erdNormalize(doc); }catch(e){ toast((from||'That file')+': '+e.message); return false; }
  const n=Object.assign(d, {id:blank('').id, stored:true, origin:null});
  n.name=uniqueName(d.name);
  S.diagrams.push(n);
  switchTo(n.id); persist();
  const gone=n.tables.filter(t=>!S.byKey.has(t.key)).length;
  toast('Imported “'+n.name+'”'+(gone?' — '+gone+' table'+(gone===1?' is':'s are')+' not in this project’s schema':''));
  return true;
}

// ---------- presenting ----------
function present(on){
  if(!els || els.empty) return;
  S.present=!!on;
  if(S.present) S.sel=null;              // a selection outline is an editing aid, not part of what is shown
  document.documentElement.classList.toggle('erd-presenting', S.present);
  els.root.classList.toggle('erd-present', S.present);
  closePop();
  try{
    if(S.present && document.documentElement.requestFullscreen && !document.fullscreenElement) document.documentElement.requestFullscreen().catch(()=>{});
    else if(!S.present && document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(()=>{});
  }catch(e){ /* full screen is a bonus: the IDE's browser may refuse it, the page still hides its chrome */ }
  draw();
  requestAnimationFrame(()=>requestAnimationFrame(fit));
}
document.addEventListener('fullscreenchange', ()=>{ if(!document.fullscreenElement && S && S.present) present(false); });

// ---------- keys and paste ----------
const typing=t=>!!(t && t.closest && t.closest('input,textarea,select,[contenteditable]'));
document.addEventListener('keydown', e=>{
  if(!S || !els || els.empty || !onPage()) return;
  if(e.key==='Escape'){
    if(S.pop){ closePop(); e.preventDefault(); }
    else if(S.present){ present(false); e.preventDefault(); }
    else if(S.sel && !typing(e.target)){ S.sel=null; draw(); }
    return;
  }
  if(typing(e.target) || document.getElementById('palette') && !document.getElementById('palette').hidden) return;
  const mod=e.metaKey||e.ctrlKey;
  if(mod && (e.key==='z'||e.key==='Z')){ e.preventDefault(); if(e.shiftKey) redo(); else undo(); }
  else if(mod && (e.key==='y'||e.key==='Y')){ e.preventDefault(); redo(); }
  else if(!mod && (e.key==='Delete'||e.key==='Backspace') && S.sel){ e.preventDefault(); removeSelected(); }
  else if(!mod && !e.altKey && (e.key==='+'||e.key==='=')){ e.preventDefault(); zoomAt(1.25); }
  else if(!mod && !e.altKey && e.key==='-'){ e.preventDefault(); zoomAt(1/1.25); }
  else if(!mod && !e.altKey && e.key==='0'){ e.preventDefault(); fit(); }
});
document.addEventListener('paste', e=>{
  if(!S || !els || els.empty || !onPage() || typing(e.target)) return;
  const text=e.clipboardData && e.clipboardData.getData('text');
  if(text && /"format"\s*:\s*"atlas-erd"/.test(text)){ e.preventDefault(); importText(text, 'The pasted diagram'); }
});
document.addEventListener('pointerdown', e=>{
  if(!S || !S.pop || !els || !els.pop) return;
  if(els.pop.contains(e.target) || e.target.closest && e.target.closest('.erd-menubtn,.erd-svg')) return;
  closePop();
}, true);
const onPage=()=>{ const v=document.getElementById('view-erd'); return !!(v && !v.hidden); };

// ---------- registration ----------
// The view element is the extension's own: a report without the designer carries no trace of it.
(function(){
  const content=document.getElementById('content');
  if(content && !document.getElementById('view-erd')){
    const sec=document.createElement('section');
    sec.className='view'; sec.id='view-erd'; sec.hidden=true;
    content.appendChild(sec);
  }
})();
window.ATLAS_EXT=window.ATLAS_EXT||{};
window.ATLAS_EXT.erd={
  title:'ER diagram',
  nav(){
    const s=state();
    if(!s.catalog.length) return null;
    if(!TYPE_ICONS.erd) TYPE_ICONS.erd=ICON;
    return {route:'/erd', label:'ER diagram', sec:'Models', pri:0, icon:'erd', color:color('liquibase'), count:s.catalog.length,
      tip:'ER diagram — lay out the project’s '+s.catalog.length+' table'+(s.catalog.length===1?'':'s')+' and draw the relations between them'};
  },
  render,
  leave(){ if(!S) return; if(S.present) present(false); closePop(); }
};
// For the UI test: the state and the actions a test drives, without a second way in for the page itself.
window.ATLAS_EXT.erd._test={state:()=>S, active:()=>S&&active(), fit:()=>fit(), importText:(t)=>importText(t,'test'),
  docOf:()=>docOf(), pictureSvg:()=>pictureSvg(), addTable:(k,p)=>addTable(k,p)};
})();
