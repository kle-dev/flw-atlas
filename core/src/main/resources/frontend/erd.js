/* Flowable Atlas — the ER diagram designer: the script of `<project>.erd.html`, a page of its own beside the
   explorer (ErdHtmlRenderer.kt writes it: `--erd`, `--all`, or the artifact in Settings → Generation).

   A page to explain a project's data model to people who never read a changelog: drag the project's tables
   onto a canvas, show each with its first columns, draw the relations between them with a name and a
   cardinality, colour them. The tables are the ones Atlas already knows — the schema the Liquibase
   changelogs leave behind once they have run, and database services whose table no changelog creates — so
   a diagram is a *view* of the live schema: it stores layout and decisions, never a second copy of the
   columns it could get wrong.

   It used to be a page inside the explorer, and moved out because the explorer carries the whole graph —
   on a large project more than a Remote Development client will open — while this page needs only the
   tables. It shares the explorer's stylesheet (tokens, fonts, controls) and nothing of its script: the
   few helpers it needs (escaping, the toast, the copy bridge, the theme) are here, below. */

/*__ERD_CORE_START__*/
// The pure part — no DOM, no explorer globals — so scripts/erd-selftest.mjs can run it in Node.
// Version 2 added frames and a count at each end of a relation, version 3 relations over several columns:
// an older page would read such a file with its frames gone, `0..n` defaulted or a relation's columns lost,
// so it is refused there rather than half-read. Older files are read as they are — their `1:n` is a valid
// cardinality, their one column a valid column list, of this version too.
const ERD_FORMAT='atlas-erd', ERD_FORMAT_VERSION=3;
/**
 * What one end of a relation can say: how many rows of the table at that end one row of the other table
 * has — exactly one, zero or one, one or more, zero or more. A minimum (0 or 1) and a maximum (1 or many),
 * which is all crow's-foot notation can draw, written as UML writes multiplicities, with `n` for many. A
 * relation is two of them, `from:to` — `0..n:1` is "each order has exactly one customer, each customer zero
 * or more orders" — so every pairing (0:1, 1:0..1, 0..n:0..n …) is one of these sixteen.
 */
const ERD_ENDS=['1','0..1','1..n','0..n'];
const ERD_END_WORDS={'1':'exactly one', '0..1':'zero or one', '1..n':'one or more', '0..n':'zero or more'};
/** Room a frame keeps around what it holds: its name tab sits in the top band. On the 4px grid. */
const ERD_FRAME_PAD=28, ERD_FRAME_TOP=56, ERD_FRAME_MIN_W=160, ERD_FRAME_MIN_H=100;
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
 * One end of a relation as this page writes it, or null: the four of [ERD_ENDS], and what people write by
 * hand for them — `n`, `m` and `*` are many with no minimum (UML's `*` is `0..*`), `1..1` is one.
 */
function erdEnd(s){
  const t=String(s==null?'':s).trim().toLowerCase().replace(/\s+/g,'').replace(/[m*]/g,'n');
  return t==='1'||t==='1..1'?'1' : t==='0..1'?'0..1' : t==='1..n'?'1..n' : t==='n'||t==='0..n'?'0..n' : null;
}
/** A relation's cardinality as this page writes it (`1:n` → `1:0..n`, `n:m` → `0..n:0..n`), or null. */
function erdCardinality(s){
  const p=String(s==null?'':s).split(':');
  if(p.length!==2) return null;
  const a=erdEnd(p[0]), b=erdEnd(p[1]);
  return a && b ? a+':'+b : null;
}
/** Whether an end is at most one — the "one" side of a relation, for arranging. */
const erdEndOne=e=>e==='1'||e==='0..1';

/**
 * Relations the models already state, as proposals, each over the columns it joins when the models say:
 *
 * - a database service's column relation — its column refers to a column of another service's table
 *   (`customer_id_` → `customerService.id`): a lookup, many rows to one, over exactly those columns;
 * - a data object field that references another data object. Its relationship type is the field's own
 *   count — one-to-one 1:1, one-to-many 1:0..n, as the model says — and its columns are those of the field's
 *   mapping in the object's service, when that mapping is a column relation.
 *
 * Two proposals over the same columns are one relation told twice: they become one, named as the data
 * object names it, counted as the service's schema counts it. So is a data object field without columns
 * that is the only one between two tables with a single proposal over columns. Anything else stays apart —
 * two fields of one object that both refer to addresses are two relations. Both ends must be tables; the
 * id is stable, so a dismissed proposal stays dismissed across reloads and in the diagram file.
 */
function erdSuggestions(nodes){
  const svc=new Map(), doOf=new Map();
  (nodes||[]).forEach(n=>{
    if(!n) return;
    const d=n.data||{};
    if(n.type==='service' && d.tableName) svc.set(n.key, {key:n.key, label:n.label||n.key, table:erdKey(d.tableName), columns:d.columns||[]});
    if(n.type==='dataObject' && d.serviceTableName) doOf.set(n.key, {table:erdKey(d.serviceTableName), service:d.service||null});
  });
  // the column a relation refers to: the referenced service's mapping of that name, else the name as written
  const target=rel=>{
    const t=rel && svc.get(rel.service);
    if(!t) return null;
    const m=t.columns.find(c=>c && c.name===rel.column);
    return {table:t.table, label:t.label, column:(m && m.columnName) || rel.column || ''};
  };
  const fromSvc=[], fromDo=[], seen=new Set();
  svc.forEach(s=>s.columns.forEach(c=>{
    const t=c && c.columnName && target(c.relation);
    if(!t) return;
    const id='service:'+s.key+'#'+c.columnName;
    if(seen.has(id)) return;
    seen.add(id);
    fromSvc.push({id, from:s.table, to:t.table, pairs:[{from:c.columnName, to:t.column}], cardinality:'0..n:1', label:'',
      why:'Service '+s.label+' — column '+c.columnName+' refers to '+t.label+(c.relation.column?'.'+c.relation.column:'')});
  }));
  (nodes||[]).forEach(n=>{
    if(!n || n.type!=='dataObject' || !doOf.has(n.key)) return;
    const own=doOf.get(n.key), s=own.service && svc.get(own.service);
    ((n.data||{}).columns||[]).forEach(c=>{
      if(!c || !c.refDataObject || !doOf.has(c.refDataObject)) return;
      const id='dataObject:'+n.key+'#'+c.name;
      if(seen.has(id)) return;
      seen.add(id);
      const m=s && s.columns.find(x=>x && x.name===c.name && x.columnName), t=m && target(m.relation);
      fromDo.push({id, from:own.table, to:doOf.get(c.refDataObject).table, pairs:t?[{from:m.columnName, to:t.column}]:[],
        cardinality:c.relationship==='one-to-many'?'1:0..n':'1:1', label:c.label||c.name||'',
        why:'Data object '+(n.label||n.key)+' — field '+(c.label||c.name)+' refers to '+c.refDataObject+' ('+(c.relationship||'relation')+')'});
    });
  });
  const colsOf=sg=>sg.pairs.map(p=>erdKey(p.from)).sort().join(',');
  const sig=sg=>sg.from+'>'+sg.to+'#'+colsOf(sg);
  const pair=sg=>[sg.from, sg.to].sort().join('\u0000');
  // the service's count and the data object's name and id: the id a dismissal was stored under stays
  const merge=(sv, dob)=>Object.assign({}, sv, {id:dob.id, label:dob.label||sv.label, why:dob.why+' · '+sv.why});
  const out=fromSvc.slice();
  fromDo.forEach(dob=>{
    const same=dob.pairs.length ? out.findIndex(x=>x.id.indexOf('service:')===0 && sig(x)===sig(dob))
      : (()=>{
        const onPair=out.filter(x=>pair(x)===pair(dob) && x.pairs.length && x.id.indexOf('service:')===0);
        const alone=fromDo.filter(x=>pair(x)===pair(dob) && !x.pairs.length).length===1;
        return onPair.length===1 && alone ? out.indexOf(onPair[0]) : -1;
      })();
    if(same>=0) out[same]=merge(out[same], dob); else out.push(dob);
  });
  // the data objects' own first, as the models name them; then what only a service says
  return out.filter(x=>x.id.indexOf('dataObject:')===0).concat(out.filter(x=>x.id.indexOf('dataObject:')!==0));
}

/**
 * The proposals a diagram does not have yet: both tables on it ([onCanvas]), not dismissed, not drawn. A
 * drawn relation accounts for one proposal between its two tables — the one over its columns; else, the
 * first one left that names no columns; else, drawn without columns itself, the first one left at all — so a
 * second relation between two tables (a billing and a delivery address) is still proposed once the first is
 * drawn, and one drawn by hand hides what it already shows.
 */
function erdOpenSuggestions(suggestions, relations, onCanvas, dismissed){
  const pair=x=>[x.from, x.to].sort().join('\u0000');
  const cset=list=>list.map(erdKey).filter(Boolean).sort().join(',');
  const left=(suggestions||[]).filter(sg=>onCanvas.has(sg.from) && onCanvas.has(sg.to) && (dismissed||[]).indexOf(sg.id)<0);
  const take=sg=>{ left.splice(left.indexOf(sg), 1); };
  const rest=[];
  (relations||[]).forEach(r=>{
    const pairs=(r.pairs||[]).filter(p=>p.from||p.to);
    // the relation's columns on the table the proposal starts from — the side its foreign key is on
    const hit=pairs.length && left.find(sg=>pair(sg)===pair(r) && sg.pairs.length &&
      cset(sg.pairs.map(p=>p.from))===cset(pairs.map(p=>r.from===sg.from?p.from:p.to)));
    if(hit) take(hit); else rest.push({r, cols:pairs.length>0});
  });
  rest.forEach(({r, cols})=>{
    const hit=left.find(sg=>pair(sg)===pair(r) && !sg.pairs.length) || (!cols && left.find(sg=>pair(sg)===pair(r)));
    if(hit) take(hit);
  });
  return left;
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
    relations.push({id, from, to, pairs:erdPairs(r.fromColumn, r.toColumn),
      cardinality:erdCardinality(r.cardinality)||'1:0..n', label:str(r.label,200)});
  });
  const frames=[], fids=new Set();
  (Array.isArray(doc.frames)?doc.frames:[]).slice(0,200).forEach((fr,i)=>{
    if(!fr || typeof fr!=='object') return;
    let id=str(fr.id,64)||('f'+(i+1));
    while(fids.has(id)) id+='_';
    fids.add(id);
    frames.push({id, name:str(fr.name,120), x:num(fr.x, 0), y:num(fr.y, 0),
      w:Math.max(ERD_FRAME_MIN_W, num(fr.w, 480)), h:Math.max(ERD_FRAME_MIN_H, num(fr.h, 320)),
      color:/^#[0-9a-f]{6}$/i.test(fr.color||'')?fr.color.toLowerCase():''});
  });
  return {name:str(doc.name,120).trim()||'Imported diagram', tables, relations, frames,
    dismissed:(Array.isArray(doc.dismissed)?doc.dismissed:[]).filter(s=>typeof s==='string').slice(0,1000)};
}

/**
 * The columns a relation joins, as the file writes them — `fromColumn` and `toColumn` each a name, or for a
 * relation over several columns a list of them, position by position, as SQL writes `FOREIGN KEY (a, b)
 * REFERENCES t (x, y)` — read into pairs. A side may leave a column out (`''`); a pair with neither is none.
 */
function erdPairs(fromColumn, toColumn){
  const list=v=>(Array.isArray(v)?v:[v]).slice(0,64).map(x=>typeof x==='string'?x.slice(0,128):'');
  const a=list(fromColumn), b=list(toColumn), out=[];
  for(let i=0; i<Math.max(a.length, b.length); i++) if(a[i] || b[i]) out.push({from:a[i]||'', to:b[i]||''});
  return out;
}
/** A diagram as the file format (see site/pages/erd.md). Relations name their tables as written. */
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
    ...columnsOut(r.pairs), cardinality:r.cardinality, label:r.label||''}));
  doc.frames=(d.frames||[]).map(fr=>({id:fr.id, name:fr.name||'', x:Math.round(fr.x), y:Math.round(fr.y),
    w:Math.round(fr.w), h:Math.round(fr.h), color:fr.color||''}));
  doc.dismissed=(d.dismissed||[]).slice();
  return doc;
}

/** A relation's columns for the file: one name each side for one pair, lists for several (see [erdPairs]). */
function columnsOut(pairs){
  const ps=(pairs||[]).filter(p=>p.from||p.to);
  if(ps.length>1) return {fromColumn:ps.map(p=>p.from||''), toColumn:ps.map(p=>p.to||'')};
  return {fromColumn:(ps[0]&&ps[0].from)||'', toColumn:(ps[0]&&ps[0].to)||''};
}
/** Whether two diagrams say the same thing — the "changed here" test for a diagram from a project file. */
function erdSame(a, b){ return JSON.stringify(erdToDoc(a))===JSON.stringify(erdToDoc(b)); }

/** The two ends of a cardinality, from-side first: `1:0..n` is exactly one at the from-side, zero or more at
 *  the to-side. */
function erdEnds(card){ return (erdCardinality(card)||'1:0..n').split(':'); }

/** Readable text on a coloured header: dark ink on a light colour, white on a dark one (WCAG luminance). */
function erdContrast(hex){
  const m=/^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex||'');
  if(!m) return '';
  const lin=v=>{ v/=255; return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055, 2.4); };
  const L=0.2126*lin(parseInt(m[1],16))+0.7152*lin(parseInt(m[2],16))+0.0722*lin(parseInt(m[3],16));
  return L>0.4?'#131e29':'#ffffff';
}

/** Whether a table answers a search by its own names: table name, business name, the data objects that read it
 *  (by name or key) and the services that map it (by key). */
function erdTableMatches(name, alias, dataObjects, q, services){
  if(!q) return false;
  return [name, alias].concat((dataObjects||[]).map(d=>d && d.name), (dataObjects||[]).map(d=>d && d.key),
    (services||[]).map(id=>String(id).replace(/^service:/,''))).some(n=>n && String(n).toLowerCase().indexOf(q)>=0);
}

/** The columns a search finds in a table — by name, or by type (`varchar(4000)`) — as their keys. */
function erdColumnHits(columns, q){
  const out=new Set();
  if(!q) return out;
  (columns||[]).forEach(c=>{ if(String(c.name).toLowerCase().indexOf(q)>=0 || String(c.type||'').toLowerCase().indexOf(q)>=0) out.add(erdKey(c.name)); });
  return out;
}

/**
 * A search over every table of the project and every one of its columns, on the diagram or not. Tables and
 * columns the diagram shows come first; then an exact name before one that starts with the query before
 * one that contains it, names before types; then the table's name and the column's place in it. [query]
 * is matched case-insensitively as one piece of text — a column is usually looked for by a fragment of
 * its name (`customer`, `_id_`), and splitting it into words would find less, not more.
 */
function erdSearch(catalog, query, onCanvas, limit){
  const q=String(query||'').trim().toLowerCase();
  const res={items:[], total:0, tables:0, columns:0};
  if(!q) return res;
  const on=onCanvas||new Set(), all=[];
  const rank=n=>{ n=String(n||'').toLowerCase(); return n===q?0:n.indexOf(q)===0?1:n.indexOf(q)>=0?2:9; };
  (catalog||[]).forEach(t=>{
    const isOn=on.has(t.key);
    const tr=Math.min(rank(t.name), rank(t.alias), ...(t.dataObjects||[]).map(d=>Math.min(rank(d.name), rank(d.key))),
      ...(t.services||[]).map(id=>rank(String(id).replace(/^service:/,''))));
    if(tr<9){ all.push({kind:'table', key:t.key, table:t.name, alias:t.alias||'', count:(t.columns||[]).length, on:isOn, score:tr, pos:-1}); res.tables++; }
    (t.columns||[]).forEach((c,i)=>{
      const nr=rank(c.name), ty=String(c.type||'').toLowerCase().indexOf(q)>=0;
      if(nr===9 && !ty) return;
      all.push({kind:'column', key:t.key, table:t.name, alias:t.alias||'', column:c.name, type:c.type||'', pk:!!c.pk, on:isOn,
        score:nr<9?nr+3:7, pos:i});
      res.columns++;
    });
  });
  all.sort((a,b)=>(Number(b.on)-Number(a.on)) || (a.score-b.score) || a.table.localeCompare(b.table) || (a.pos-b.pos));
  res.total=all.length;
  res.items=all.slice(0, limit||80);
  return res;
}

/** A file name for a diagram: `Orders & customers` → `orders-customers`. */
function erdSlug(name){
  return String(name||'').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g,'')
    .replace(/[^a-z0-9]+/g,'-').replace(/^-+|-+$/g,'').slice(0,60)||'diagram';
}
/**
 * Where every table goes when the reader asks the page to arrange them — a layered drawing, the usual way
 * to make a graph readable (Sugiyama): relations point from their "one" side to their "many" side (the
 * direction a reader follows: a customer, then its orders), so each relation's one side sits in a column
 * left of its many side; the order inside a column is chosen to cross as few relation lines as possible;
 * each table then sits level with the tables it relates to, and the columns leave room for the relation
 * names between them. Tables that relate to nothing, and separate groups, are packed underneath.
 *
 * [nodes] are `{key, w, h, x, y}` (size and current place), [edges] `{from, to, cardinality, gap}` where
 * `gap` is the width a relation's name needs between two columns. Returns `{key: {x, y}}`, the arrangement's
 * top-left where the current drawing's was. Deterministic: the same input gives the same picture, and the
 * current vertical order breaks ties, so arranging twice changes nothing the second time.
 */
function erdArrange(nodes, edges, opts){
  opts=opts||{};
  const GAP_Y=opts.gapY||40, GAP_X=opts.gapX||110, GAP_GROUP=opts.gapGroup||100, DUMMY_H=24;
  const byKey=new Map((nodes||[]).map(n=>[n.key, n]));
  const cmpPlace=(a,b)=>(a.y-b.y)||(a.x-b.x)||(a.key<b.key?-1:a.key>b.key?1:0);

  // ---- relations as arcs, from the "one" side to the "many" side ----
  const arcs=[];
  (edges||[]).forEach(e=>{
    if(!byKey.has(e.from) || !byKey.has(e.to) || e.from===e.to) return;
    const [a,b]=erdEnds(e.cardinality);
    const flip=!erdEndOne(a) && erdEndOne(b);     // n:1 — the one side is the target
    arcs.push({p:flip?e.to:e.from, c:flip?e.from:e.to, gap:e.gap||0});
  });

  // ---- groups: tables connected by any relation ----
  const parent=new Map([...byKey.keys()].map(k=>[k,k]));
  const find=k=>{ while(parent.get(k)!==k){ parent.set(k, parent.get(parent.get(k))); k=parent.get(k); } return k; };
  arcs.forEach(a=>{ const x=find(a.p), y=find(a.c); if(x!==y) parent.set(x<y?y:x, x<y?x:y); });
  const groups=new Map();
  [...byKey.values()].sort(cmpPlace).forEach(n=>{ const r=find(n.key); if(!groups.has(r)) groups.set(r, []); groups.get(r).push(n); });

  const blocks=[];                                // each: {w, h, pos:{key:{x,y}}, size, top}
  groups.forEach(members=>{
    if(members.length===1){ const n=members[0]; blocks.push({w:n.w, h:n.h, pos:{[n.key]:{x:0, y:0}}, size:1, top:n}); return; }
    blocks.push(layered(members, arcs.filter(a=>members.some(m=>m.key===a.p))));
  });

  function layered(members, garcs){
    const keys=members.map(m=>m.key), rank=new Map(members.map((m,i)=>[m.key,i]));   // current reading order
    // Cycles (a relation back to an ancestor) cannot be layered: reverse the arcs a depth-first walk in
    // reading order meets as back edges — the fewest a reader would notice turned round.
    const out=new Map(keys.map(k=>[k,[]]));
    garcs.forEach(a=>out.get(a.p).push(a));
    out.forEach(l=>l.sort((x,y)=>rank.get(x.c)-rank.get(y.c)));
    const state=new Map(), dag=[];
    const visit=root=>{
      const stack=[[root,0]]; state.set(root,1);
      while(stack.length){
        const top=stack[stack.length-1], list=out.get(top[0]);
        if(top[1]>=list.length){ state.set(top[0],2); stack.pop(); continue; }
        const a=list[top[1]++], s=state.get(a.c);
        if(s===1) dag.push({p:a.c, c:a.p, gap:a.gap});
        else { dag.push(a); if(!s){ state.set(a.c,1); stack.push([a.c,0]); } }
      }
    };
    const indeg=new Map(keys.map(k=>[k,0]));
    garcs.forEach(a=>indeg.set(a.c, indeg.get(a.c)+1));
    keys.filter(k=>!indeg.get(k)).concat(keys).forEach(k=>{ if(!state.get(k)) visit(k); });

    // Layers: the longest path from a table nothing points at; then each such root moves right, next to
    // its nearest child, so a relation spans as few columns as it can.
    const ins=new Map(keys.map(k=>[k,[]])), outs=new Map(keys.map(k=>[k,[]]));
    const seenArc=new Set(), arcsU=[];
    dag.forEach(a=>{ const id=a.p+'\u0000'+a.c; if(seenArc.has(id)) return; seenArc.add(id); arcsU.push(a); ins.get(a.c).push(a); outs.get(a.p).push(a); });
    const layer=new Map(), deg=new Map(keys.map(k=>[k, ins.get(k).length])), queue=keys.filter(k=>!deg.get(k)), topo=[];
    while(queue.length){ const k=queue.shift(); topo.push(k); outs.get(k).forEach(a=>{ deg.set(a.c, deg.get(a.c)-1); if(!deg.get(a.c)) queue.push(a.c); }); }
    topo.forEach(k=>layer.set(k, ins.get(k).reduce((m,a)=>Math.max(m, layer.get(a.p)+1), 0)));
    topo.slice().reverse().forEach(k=>{ if(!ins.get(k).length && outs.get(k).length) layer.set(k, Math.min(...outs.get(k).map(a=>layer.get(a.c)))-1); });

    // A relation across several columns gets a placeholder in each column it passes, so it has a lane.
    const items=new Map(members.map(m=>[m.key, {id:m.key, h:m.h, w:m.w, real:true, up:[], down:[]}]));
    const links=[];
    let dummy=0;
    arcsU.forEach(a=>{
      let prev=a.p;
      for(let l=layer.get(a.p)+1; l<layer.get(a.c); l++){
        const id='\u0001'+(dummy++);
        items.set(id, {id, h:DUMMY_H, w:0, real:false, up:[], down:[]}); layer.set(id, l);
        links.push([prev, id]); prev=id;
      }
      links.push([prev, a.c]);
    });
    links.forEach(([u,v])=>{ items.get(u).down.push(v); items.get(v).up.push(u); });
    const L=Math.max(...[...layer.values()])+1, layers=[...Array(L)].map(()=>[]);
    // start from the reading order: an arrangement close to what the reader had is easier to follow
    const seed=new Map(members.map(m=>[m.key, m.y+m.h/2]));
    [...items.keys()].forEach(id=>layers[layer.get(id)].push(id));
    const guess=id=>{ if(seed.has(id)) return seed.get(id); const it=items.get(id); let s=0, n=0; it.up.concat(it.down).forEach(v=>{ if(seed.has(v)){ s+=seed.get(v); n++; } }); return n?s/n:0; };
    layers.forEach(ls=>ls.sort((a,b)=>(guess(a)-guess(b)) || (a<b?-1:1)));

    // Order inside the columns: barycentre sweeps, keeping the order with the fewest crossings.
    const pos=new Map();
    const index=()=>layers.forEach(ls=>ls.forEach((id,i)=>pos.set(id,i)));
    const crossings=()=>{
      let c=0;
      for(let l=0;l<L-1;l++){
        const e=[];
        layers[l].forEach(u=>items.get(u).down.forEach(v=>e.push([pos.get(u), pos.get(v)])));
        for(let i=0;i<e.length;i++) for(let j=i+1;j<e.length;j++) if((e[i][0]-e[j][0])*(e[i][1]-e[j][1])<0) c++;
      }
      return c;
    };
    index();
    let best=layers.map(ls=>ls.slice()), bestC=crossings();
    for(let it=0; it<8 && bestC>0; it++){
      const down=it%2===0;
      for(let s=0; s<L-1; s++){
        const l=down?s+1:L-2-s, side=down?'up':'down';
        const bary=new Map(layers[l].map(id=>{ const nb=items.get(id)[side]; return [id, nb.length?nb.reduce((t,v)=>t+pos.get(v),0)/nb.length:pos.get(id)]; }));
        layers[l].sort((a,b)=>(bary.get(a)-bary.get(b)) || (pos.get(a)-pos.get(b)));
        layers[l].forEach((id,i)=>pos.set(id,i));
      }
      const c=crossings();
      if(c<bestC){ bestC=c; best=layers.map(ls=>ls.slice()); }
    }
    best.forEach((ls,l)=>{ layers[l]=ls; });
    index();

    // Heights: each table level with the ones it relates to, as far as the column lets it — an
    // order-keeping least-squares placement, swept left and right a few times.
    const top=new Map();
    layers.forEach(ls=>{ let y=0; ls.forEach(id=>{ top.set(id,y); y+=items.get(id).h+GAP_Y; }); });
    const centre=id=>top.get(id)+items.get(id).h/2;
    const settle=(ls, want)=>{
      const blocks=[];
      ls.forEach(id=>{
        const h=items.get(id).h;
        let b={ids:[id], H:h, q:want.get(id)-h/2, n:1};
        while(blocks.length){
          const p=blocks[blocks.length-1];
          if(p.q/p.n+p.H+GAP_Y<=b.q/b.n) break;
          blocks.pop();
          b={ids:p.ids.concat(b.ids), H:p.H+GAP_Y+b.H, q:p.q+b.q-b.n*(p.H+GAP_Y), n:p.n+b.n};
        }
        blocks.push(b);
      });
      blocks.forEach(b=>{ let y=b.q/b.n; b.ids.forEach(id=>{ top.set(id,y); y+=items.get(id).h+GAP_Y; }); });
    };
    for(let it=0; it<6; it++){
      const down=it%2===0;
      for(let s=0; s<L-1; s++){
        const l=down?s+1:L-2-s, side=down?'up':'down';
        settle(layers[l], new Map(layers[l].map(id=>{ const nb=items.get(id)[side];
          return [id, nb.length?nb.reduce((t,v)=>t+centre(v),0)/nb.length:centre(id)]; })));
      }
    }
    // last, both sides at once, so a table between two columns sits between its neighbours
    layers.forEach(ls=>settle(ls, new Map(ls.map(id=>{ const it=items.get(id), nb=it.up.concat(it.down); return [id, nb.length?nb.reduce((t,v)=>t+centre(v),0)/nb.length:centre(id)]; }))));

    // Columns: each as wide as its widest table, with room for the widest relation name crossing the gap.
    const widths=layers.map(ls=>Math.max(0, ...ls.map(id=>items.get(id).w)));
    const gaps=[...Array(Math.max(0,L-1))].map(()=>GAP_X);
    arcsU.forEach(a=>{ for(let l=layer.get(a.p); l<layer.get(a.c); l++) gaps[l]=Math.max(gaps[l], a.gap+60); });
    const colX=[0];
    for(let l=1;l<L;l++) colX[l]=colX[l-1]+widths[l-1]+gaps[l-1];
    let minY=Infinity, maxY=-Infinity;
    members.forEach(m=>{ minY=Math.min(minY, top.get(m.key)); maxY=Math.max(maxY, top.get(m.key)+m.h); });
    const out2={};
    members.forEach(m=>{ out2[m.key]={x:colX[layer.get(m.key)], y:top.get(m.key)-minY}; });
    return {w:colX[L-1]+widths[L-1], h:maxY-minY, pos:out2, size:members.length, top:members[0]};
  }

  // ---- pack the groups: the largest first, in rows about as wide as the whole is tall ----
  blocks.sort((a,b)=>(b.size-a.size) || cmpPlace(a.top, b.top));
  const area=blocks.reduce((s,b)=>s+(b.w+GAP_GROUP)*(b.h+GAP_GROUP),0);
  const rowW=Math.max(blocks.length?Math.max(...blocks.map(b=>b.w)):0, Math.sqrt(area)*1.5);
  let x=0, y=0, rowH=0;
  const res={};
  blocks.forEach(b=>{
    if(x>0 && x+b.w>rowW){ x=0; y+=rowH+GAP_GROUP; rowH=0; }
    Object.keys(b.pos).forEach(k=>{ res[k]={x:x+b.pos[k].x, y:y+b.pos[k].y}; });
    x+=b.w+GAP_GROUP; rowH=Math.max(rowH, b.h);
  });
  // where the drawing was, on the canvas's 4px grid
  const all=[...byKey.values()];
  const ox=all.length?Math.min(...all.map(n=>n.x)):0, oy=all.length?Math.min(...all.map(n=>n.y)):0;
  Object.keys(res).forEach(k=>{ res[k]={x:Math.round((ox+res[k].x)/4)*4, y:Math.round((oy+res[k].y)/4)*4}; });
  return res;
}

/**
 * What each frame holds. A frame holds what is inside it — no list of members to keep in step: a table
 * whose centre lies inside it, a frame lying wholly inside it; when several frames qualify, the smallest,
 * so a frame drawn inside a frame belongs to it and its tables to the inner one. [tables] are
 * `{key, x, y, w, h}`. Returns `{table, frame}`: Maps from a table key and a frame id to the id of the
 * frame holding it; what no frame holds is absent.
 */
function erdFrameParents(frames, tables){
  const list=(frames||[]).map((f,i)=>({f, i, a:f.w*f.h}));
  // a strict order — by area, then the later-drawn frame the smaller of two equal ones — so no two frames
  // can hold each other
  const smaller=(p,q)=>p.a<q.a || (p.a===q.a && p.i>q.i);
  const inside=(f, x, y)=>x>=f.x && x<=f.x+f.w && y>=f.y && y<=f.y+f.h;
  const least=c=>c.reduce((m,o)=>!m || smaller(o,m) ? o : m, null);
  const table=new Map(), frame=new Map();
  (tables||[]).forEach(t=>{
    const c=least(list.filter(o=>inside(o.f, t.x+t.w/2, t.y+t.h/2)));
    if(c) table.set(t.key, c.f.id);
  });
  list.forEach(p=>{
    const c=least(list.filter(o=>o!==p && smaller(p,o) && inside(o.f, p.f.x, p.f.y) && inside(o.f, p.f.x+p.f.w, p.f.y+p.f.h)));
    if(c) frame.set(p.f.id, c.f.id);
  });
  return {table, frame};
}

/**
 * [erdArrange] for a diagram with frames: a frame keeps its tables together. Each frame is arranged on its
 * own first — its tables (and the frames inside it) by the relations between them — and fitted around the
 * result; then, one level out, it takes part as a single block, related to whatever its tables relate to,
 * so a frame of customers still stands left of the orders outside it. [frames] are `{id, x, y, w, h}`,
 * with an optional `minW` (the room its name needs). Returns `{tables: {key: {x, y}}, frames: {id: {x, y,
 * w, h}}}`; a frame that holds nothing keeps its size and moves as a block.
 */
function erdArrangeFramed(nodes, edges, frames, opts){
  frames=frames||[];
  if(!frames.length) return {tables:erdArrange(nodes, edges, opts), frames:{}};
  const par=erdFrameParents(frames, nodes), FR='\u0002';
  const tablesOut={}, framesOut={};
  // the child of [cid] (a frame id, or null for the canvas) that holds table [k], or null when [cid] does not
  const rep=(k, cid)=>{
    let c=par.table.get(k)||null;
    if(c===cid) return k;
    while(c){ const p=par.frame.get(c)||null; if(p===cid) return FR+c; c=p; }
    return null;
  };
  // a container's children arranged, relative to its content's top-left
  const measure=cid=>{
    const kids=(nodes||[]).filter(n=>(par.table.get(n.key)||null)===cid).map(n=>({key:n.key, w:n.w, h:n.h, x:n.x, y:n.y}));
    const sub=new Map();
    frames.filter(f=>(par.frame.get(f.id)||null)===cid).forEach(f=>{
      const m=measure(f.id);
      const w=m.empty?f.w:Math.max(f.minW||0, ERD_FRAME_MIN_W, m.w+2*ERD_FRAME_PAD);
      const h=m.empty?f.h:Math.max(ERD_FRAME_MIN_H, m.h+ERD_FRAME_TOP+ERD_FRAME_PAD);
      sub.set(f.id, Object.assign(m, {fw:w, fh:h}));
      kids.push({key:FR+f.id, w, h, x:f.x, y:f.y});
    });
    if(!kids.length) return {empty:true, rel:{}, sub, w:0, h:0, x0:0, y0:0};
    const es=[];
    (edges||[]).forEach(e=>{ const a=rep(e.from, cid), b=rep(e.to, cid); if(a && b && a!==b) es.push(Object.assign({}, e, {from:a, to:b})); });
    const p=erdArrange(kids, es, opts);
    let x0=Infinity, y0=Infinity, x1=-Infinity, y1=-Infinity;
    kids.forEach(k=>{ const q=p[k.key]; x0=Math.min(x0,q.x); y0=Math.min(y0,q.y); x1=Math.max(x1,q.x+k.w); y1=Math.max(y1,q.y+k.h); });
    const rel={};
    kids.forEach(k=>{ rel[k.key]={x:p[k.key].x-x0, y:p[k.key].y-y0}; });
    return {empty:false, rel, sub, w:x1-x0, h:y1-y0, x0, y0};
  };
  const place=(m, ox, oy)=>{
    Object.keys(m.rel).forEach(k=>{
      const q={x:ox+m.rel[k].x, y:oy+m.rel[k].y};
      if(k.charAt(0)!==FR){ tablesOut[k]=q; return; }
      const s=m.sub.get(k.slice(1));
      framesOut[k.slice(1)]={x:q.x, y:q.y, w:s.fw, h:s.fh};
      if(!s.empty) place(s, q.x+ERD_FRAME_PAD, q.y+ERD_FRAME_TOP);
    });
  };
  const top=measure(null);
  place(top, top.x0, top.y0);          // the canvas's arrangement is already where the drawing was
  return {tables:tablesOut, frames:framesOut};
}
/*__ERD_CORE_END__*/

(function(){
'use strict';

// ---------- the page: the few things explorer.js gives its pages, for this one ----------
const DATA=JSON.parse(document.getElementById('atlas-data').textContent);
const esc=s=>String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const enc=encodeURIComponent;
const MODK=/Mac|iPhone|iPad/.test(navigator.platform||'')?'⌘':'Ctrl';
const cssEsc=v=>(window.CSS && CSS.escape)?CSS.escape(v):String(v).replace(/["\\\]\[]/g,'\\$&');
const byId=new Map((DATA.nodes||[]).map(n=>[n.id, n]));
let _toastT=0;
function toast(msg){
  const box=document.getElementById('toast');
  if(!box || !msg) return;
  box.textContent=msg; box.classList.add('show');
  clearTimeout(_toastT); _toastT=setTimeout(()=>box.classList.remove('show'), 2400);
}
/** Copy through the IDE's bridge where there is one (the embedded browser has no clipboard of its own). */
function atlasCopy(text, onOk){
  const fallback=()=>{
    const ta=document.createElement('textarea'); ta.value=text; document.body.appendChild(ta); ta.select();
    try{ document.execCommand('copy'); if(onOk) onOk(); }catch(e){ toast('Could not copy'); }
    ta.remove();
  };
  if(window.__atlasCopy){ window.__atlasCopy(text); if(onOk) onOk(); return; }
  if(navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(()=>{ if(onOk) onOk(); }, fallback);
  else fallback();
}
/** Hover help on [data-tip]: the IDE's embedded browser draws no native title tooltip (explorer.css .atlas-tip). */
(function(){
  let tip=null, timer=0, on=null;
  const hide=()=>{ clearTimeout(timer); on=null; if(tip) tip.classList.remove('show'); };
  document.addEventListener('mouseover', e=>{
    const el=e.target.closest && e.target.closest('[data-tip]');
    if(el===on) return;
    hide();
    if(!el || !el.getAttribute('data-tip')) return;
    on=el;
    timer=setTimeout(()=>{
      if(!tip){ tip=document.createElement('div'); tip.className='atlas-tip'; document.body.appendChild(tip); }
      tip.textContent=el.getAttribute('data-tip');
      const r=el.getBoundingClientRect(), w=tip.offsetWidth, h=tip.offsetHeight;
      tip.style.left=Math.max(6, Math.min(innerWidth-w-6, r.left+r.width/2-w/2))+'px';
      tip.style.top=(r.bottom+6+h>innerHeight ? r.top-h-6 : r.bottom+6)+'px';
      tip.classList.add('show');
    }, 450);
  });
  document.addEventListener('pointerdown', hide, true);
  document.addEventListener('scroll', hide, true);
})();
// The theme — the preference, the IDE's mode and colours (?ideTheme, ?idePal, the live __atlasSetIdeTheme),
// the header's button — is the explorer's own code, inlined before this script (ErdHtmlRenderer): one
// implementation, so the page looks like the explorer inside the IDE, and the two share the preference.
/** Inside the IDE the page gets bridges to copy and to open files; it says so on <html>, as the explorer does. */
const markIde=()=>{ if(window.__atlasOpen) document.documentElement.classList.add('ide'); };
window.addEventListener('atlas-ide-bridge', markIde);

const VISIBLE=5;
const ROW=22, HEAD1=32, HEAD2=44, FOOT=22, WMIN=200, WMAX=420;
// Single quotes inside: these end up in style="…" attributes, where a double quote would close the attribute.
const SANS="Geist, -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif";
const MONO="'SFMono-Regular', ui-monospace, Menlo, Monaco, Consolas, monospace";
const F_TITLE='600 13px '+SANS, F_SUB='11px '+MONO, F_ROW='12px '+SANS, F_TYPE='11px '+MONO, F_PILL='12px '+SANS;
/** The page's colours, as references: a theme switch restyles the canvas without a redraw. */
const PAGE={panel:'var(--panel)', line:'var(--line2)', head:'var(--panel2)', ink:'var(--ink)', dim:'var(--ink-dim)',
  faint:'var(--ink-faint)', accent:'var(--accent)', bg:'var(--bg)', pill:'var(--panel)', mark:'var(--hl-bg)'};
/** …and an exported picture's, as values: it leaves the page, so it cannot refer to it — and it is light,
 *  because it ends up on a slide or in a document, whatever theme the page was in. */
const PAPER={panel:'#ffffff', line:'#c9ced4', head:'#f1f5f9', ink:'#131e29', dim:'#4c5b6a', faint:'#79848f',
  accent:'#0f55d6', bg:'#ffffff', pill:'#ffffff'};

let S=null;          // the page's state, built on first use (the explorer's globals exist only after its boot)

// ---------- state: catalog, diagrams, persistence ----------
function state(){
  if(S) return S;
  const catalog=erdCatalog(DATA.nodes||[]);
  S={catalog, byKey:new Map(catalog.map(t=>[t.key,t])), suggestions:erdSuggestions(DATA.nodes||[]),
    diagrams:[], activeId:null, undo:[], redo:[], sel:null, pop:null, root:null, filter:'', present:false,
    storageOk:true, projectErrors:[], lastCoalesce:null, drag:null, dirtyTimer:0, q:'', results:[], resIdx:0, tool:null};
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
  (DATA.diagrams||[]).forEach(p=>{
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
  relations:[], frames:[], dismissed:[], stored:false, origin:null}; }
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
const snap=d=>JSON.stringify({name:d.name, tables:d.tables, relations:d.relations, frames:d.frames||[], dismissed:d.dismissed});
function restore(d, json){ const o=JSON.parse(json); d.name=o.name; d.tables=o.tables; d.relations=o.relations; d.frames=o.frames; d.dismissed=o.dismissed; }
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
function layout(t, q, d){
  const e=effective(t), byK=new Map(e.columns.map(c=>[erdKey(c.name), c]));
  const ordered=e.order.map(n=>byK.get(erdKey(n))).filter(Boolean);
  // While a search is on, a folded card also shows the columns it found, where they stand in its order: the
  // one column of forty the reader is after, without unfolding the other thirty-four. The columns a relation
  // joins show the same way, always: the line meets its column's row, so the row has to be there.
  const hits=q?erdColumnHits(ordered, q):null, joined=joinedColumns(d||(S && active()), t.key);
  const shown=t.expanded?ordered:ordered.filter((c,i)=>i<VISIBLE || (hits && hits.has(erdKey(c.name))) || joined.has(erdKey(c.name)));
  const live=S && S.byKey.get(t.key);
  const match=!q || !!(hits && hits.size) || erdTableMatches(t.name, t.alias, live && live.dataObjects, q, live && live.services);
  const title=t.alias||t.name, sub=t.alias?t.name:'';
  const head=sub?HEAD2:HEAD1;
  let w=Math.max(WMIN, tw(title,F_TITLE)+56, sub?tw(sub,F_SUB)+40:0);
  ordered.forEach(c=>{ w=Math.max(w, 30+tw(c.name,F_ROW)+18+tw(c.type||'',F_TYPE)+12); });
  w=Math.min(WMAX, Math.ceil(w));
  const foot=ordered.length>VISIBLE || e.ghost;
  const rows=shown.map((c,i)=>({c, y:head+3+i*ROW}));
  const h=head+(shown.length?shown.length*ROW+6:0)+(foot?FOOT:0);
  return {t, x:t.x, y:t.y, w, h, head, title, sub, rows, total:ordered.length, more:ordered.length-shown.length,
    foot, ghost:e.ghost, ordered, hits, match};
}
/** The columns of table [key] the diagram's relations join, as keys. */
function joinedColumns(d, key){
  const out=new Set();
  ((d && d.relations)||[]).forEach(r=>(r.pairs||[]).forEach(p=>{
    if(r.from===key && p.from) out.add(erdKey(p.from));
    if(r.to===key && p.to) out.add(erdKey(p.to));
  }));
  return out;
}
/** The point a relation leaves a card from, and the direction it leaves in. */
function sideOf(A, B){
  if(A===B) return ['right','right'];
  if(B.x>A.x+A.w+24) return ['right','left'];
  if(B.x+B.w+24<A.x) return ['left','right'];
  return B.y+B.h/2>A.y+A.h/2 ? ['bottom','top'] : ['top','bottom'];
}
const NORMAL={left:[-1,0], right:[1,0], top:[0,-1], bottom:[0,1]};
function anchorOf(L, side, columns, offset){
  let y=L.y+L.head/2, x=L.x+L.w/2;
  if(side==='left'||side==='right'){
    // over several columns, from the middle of the rows the card shows of them
    const rows=(columns||[]).map(c=>c && L.rows.find(r=>erdKey(r.c.name)===erdKey(c))).filter(Boolean);
    y=rows.length ? L.y+rows.reduce((t,r)=>t+r.y, 0)/rows.length+ROW/2 : y+offset;
    x=side==='left'?L.x:L.x+L.w;
  } else {
    x+=offset;
    y=side==='top'?L.y:L.y+L.h;
  }
  return {x, y, n:NORMAL[side]};
}
/** A relation's route: a cubic curve leaving each card square to its side, parallel relations fanned out. */
/**
 * A relation's route: a cubic curve leaving each card square to its side, parallel relations fanned out. A
 * relation over columns meets each card at the rows of its columns — from the side, so also when the cards
 * stand one above the other, and forking just outside the card to each of its rows when it joins several:
 * the line never points at a row it does not join.
 */
function route(r, lays, fan){
  const A=lays.get(r.from), B=lays.get(r.to);
  if(!A || !B) return null;
  const ps=(r.pairs||[]).filter(p=>p.from||p.to), colsA=ps.map(p=>p.from).filter(Boolean), colsB=ps.map(p=>p.to).filter(Boolean);
  if(A===B && !colsA.length && !colsB.length){
    const p1={x:A.x+A.w, y:A.y+A.head/2+fan*10, n:[1,0]}, p2={x:A.x+A.w, y:A.y+Math.min(A.h-8, A.head+30)+fan*10, n:[1,0]};
    const k=48+Math.abs(fan)*12;
    return {p1, p2, c1:{x:p1.x+k, y:p1.y-10}, c2:{x:p2.x+k, y:p2.y+10}};
  }
  let [sa, sb]=sideOf(A, B);
  if((colsA.length || colsB.length) && (sa==='top' || sa==='bottom')){ sa='right'; sb='right'; }
  const e1=endAt(A, sa, colsA, fan), e2=endAt(B, sb, colsB, fan), p1=e1.p, p2=e2.p;
  const k=Math.max(36, Math.min(160, Math.hypot(p2.x-p1.x, p2.y-p1.y)/3));
  return {p1, p2, fork1:e1.fork, fork2:e2.fork, c1:{x:p1.x+p1.n[0]*k, y:p1.y+p1.n[1]*k}, c2:{x:p2.x+p2.n[0]*k, y:p2.y+p2.n[1]*k}};
}
/** How far outside the card a relation over several columns forks to their rows. */
const FORK=12;
/** Where one end of a relation meets its card: the row of its column, the fork to the rows of its columns,
 *  or — no column the card shows — the side's middle, fanned out. */
function endAt(L, side, cols, fan){
  if((side==='left' || side==='right') && cols.length){
    const seen=new Set(), ys=[];
    cols.forEach(c=>{ const k=erdKey(c), row=L.rows.find(x=>erdKey(x.c.name)===k); if(row && !seen.has(k)){ seen.add(k); ys.push(L.y+row.y+ROW/2); } });
    const n=NORMAL[side], x=side==='left'?L.x:L.x+L.w;
    if(ys.length===1) return {p:{x, y:ys[0], n}};
    if(ys.length>1){
      const xs=x+n[0]*FORK, y0=Math.min(...ys), y1=Math.max(...ys);
      return {p:{x:xs, y:(y0+y1)/2, n}, fork:{x, xs, ys}};
    }
  }
  return {p:anchorOf(L, side, null, fan*14)};
}
/** A fork's strokes: a spine outside the card, a short stub to each joined row, a dot where each meets it. */
function forkSvg(fk, stroke, width, dash){
  if(!fk) return '';
  const y0=Math.min(...fk.ys), y1=Math.max(...fk.ys);
  return '<path d="M'+f(fk.xs)+','+f(y0)+' V'+f(y1)+fk.ys.map(y=>' M'+f(fk.x)+','+f(y)+' H'+f(fk.xs)).join('')+'"'+
    st({fill:'none', stroke, 'stroke-width':width, 'stroke-dasharray':dash||''})+'/>'+
    fk.ys.map(y=>'<circle cx="'+f(fk.x)+'" cy="'+f(y)+'" r="2.5"'+st({fill:stroke})+'/>').join('');
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
/**
 * One end's crow's foot, as lines and a ring, leaving point [p] in direction [n]: the maximum at the card —
 * a bar for one, a foot for many — and the minimum just outside it — a bar for at least one, a ring for
 * none. So `1` is ‖, `0..1` o|, `1..n` |<, `0..n` o<. [k] scales it, for the panel's buttons.
 */
function endShapes(p, n, end, k){
  const [nx,ny]=n, tx=-ny, ty=nx;
  const at=d=>({x:p.x+nx*d*k, y:p.y+ny*d*k}), across=(q,s)=>[{x:q.x+tx*s*k, y:q.y+ty*s*k}, {x:q.x-tx*s*k, y:q.y-ty*s*k}];
  const many=!erdEndOne(end), zero=end==='0..1'||end==='0..n', lines=[];
  if(many){ const q=at(12), [a,b]=across(p,7); lines.push([q,a], [q,b], [q,p]); }
  else lines.push(across(at(7),6));
  let ring=null;
  if(zero) ring={c:at(many?20:16), r:4*k};
  else lines.push(across(at(many?17:12),6));
  return {lines, ring};
}
/** The cardinality mark at one end, and its count beside it — the crow's foot is the notation, the count
 *  for whoever has never seen one. */
function endMark(p, end, C){
  const {lines, ring}=endShapes(p, p.n, end, 1), [nx,ny]=p.n, tx=-ny, ty=nx, out=[];
  const S={stroke:C.dim, 'stroke-width':1.5, fill:'none'};
  lines.forEach(([a,b])=>out.push('<path d="M'+f(a.x)+','+f(a.y)+' L'+f(b.x)+','+f(b.y)+'"'+st(S)+'/>'));
  // filled with the canvas, so the line does not run through the ring
  if(ring) out.push('<circle cx="'+f(ring.c.x)+'" cy="'+f(ring.c.y)+'" r="'+ring.r+'"'+st({stroke:C.dim, 'stroke-width':1.5, fill:C.bg})+'/>');
  // beside the line, clear of the foot: under or over a level line, left or right of an upright one — then
  // anchored at its near end, as `0..n` is four letters wide
  const lx=p.x+nx*14+tx*12, ly=p.y+ny*14+ty*12;
  const anchor=Math.abs(tx)>0.5?(tx>0?'start':'end'):'middle';
  out.push(textEl(lx, ly+4, end, '600 11px '+SANS, C.dim, ' text-anchor="'+anchor+'"'));
  return out.join('');
}
function relationSvg(r, g, C, o){
  const [ea, eb]=erdEnds(r.cardinality), d=pathD(g), sel=o.sel;
  let s='<g class="erd-rel'+(sel?' sel':'')+'" data-rel="'+esc(r.id)+'">';
  s+='<path d="'+d+'"'+st({fill:'none', stroke:sel?C.accent:C.dim, 'stroke-width':sel?2.25:1.5})+'/>';
  if(g.fork1 || g.fork2) s+='<g class="erd-fork">'+forkSvg(g.fork1, sel?C.accent:C.dim, sel?2.25:1.5)+forkSvg(g.fork2, sel?C.accent:C.dim, sel?2.25:1.5)+'</g>';
  s+=endMark(g.p1, ea, C)+endMark(g.p2, eb, C);
  if(o.interactive) s+='<path class="erd-relhit" d="'+d+'"'+st({fill:'none', stroke:'transparent', 'stroke-width':14})+'/>';
  const text=r.label?r.label+'  ·  '+r.cardinality:'';
  if(text){
    const m=g.pill||mid(g), w=tw(text, F_PILL)+18;
    s+='<g class="erd-pill"><rect x="'+f(m.x-w/2)+'" y="'+f(m.y-11)+'" width="'+f(w)+'" height="22" rx="11"'+
       st({fill:C.pill, stroke:sel?C.accent:C.line, 'stroke-width':1})+'/>'+textEl(m.x, m.y+4, text, F_PILL, C.ink, ' text-anchor="middle"')+'</g>';
  }
  return s+'</g>';
}
function suggestionSvg(sg, g, C){
  const m=g.pill||mid(g), text='+ '+(sg.label||'suggested')+'  ·  '+sg.cardinality, w=tw(text, F_PILL)+18;
  return '<g class="erd-sug" data-sug="'+esc(sg.id)+'" data-tip="'+esc(sg.why+' — click to add this relation, × to dismiss it')+'">'+
    '<path d="'+pathD(g)+'"'+st({fill:'none', stroke:C.faint, 'stroke-width':1.25, 'stroke-dasharray':'5 4'})+'/>'+
    forkSvg(g.fork1, C.faint, 1.25, '3 3')+forkSvg(g.fork2, C.faint, 1.25, '3 3')+
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
  const sq=o.q?(L.match?' found':' dim'):'';
  let s='<g class="erd-card'+(sel?' sel':'')+(L.ghost?' ghost':'')+sq+'" data-key="'+esc(t.key)+'" transform="translate('+f(L.x)+','+f(L.y)+')">';
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
    if(L.hits && L.hits.has(erdKey(c.name))) s+='<rect class="erd-rowmark" x="1" width="'+(w-2)+'" height="'+ROW+'"'+st({fill:C.mark})+'/>';
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
const F_FRAME='600 12px '+SANS, FRAME_TAB_H=24;
/** The width a frame's name tab takes — and so the least a frame can be, for Arrange to leave it readable. */
const frameTabW=fr=>tw(fr.name||'Frame', F_FRAME)+22;
/**
 * A frame: a tinted area behind the tables it holds, its name on a tab in its top-left corner. The tab and
 * the frame's edge are what take the pointer — the area inside stays the canvas, to pan and to drop on — and
 * a selected frame shows its four corners to resize it by.
 */
function frameSvg(fr, C, o){
  const col=fr.color, sel=o.sel, x=fr.x, y=fr.y, w=fr.w, h=fr.h;
  let s='<g class="erd-frame'+(sel?' sel':'')+'" data-frame="'+esc(fr.id)+'">';
  s+='<rect class="erd-fbox" x="'+f(x)+'" y="'+f(y)+'" width="'+f(w)+'" height="'+f(h)+'" rx="12"'+
     st({fill:col||C.faint, 'fill-opacity':col?0.07:0.05, stroke:sel?C.accent:(col||C.line), 'stroke-width':sel?2:1.5})+'/>';
  if(o.interactive) s+='<rect class="erd-fedge" x="'+f(x)+'" y="'+f(y)+'" width="'+f(w)+'" height="'+f(h)+'" rx="12"'+
     st({fill:'none', stroke:'transparent', 'stroke-width':12})+'/>';
  // unnamed, the tab still shows on the canvas — it is the handle — but not in an exported picture
  const name=fr.name||(o.interactive?'Frame':'');
  if(name){
    const tabW=Math.min(w-16, frameTabW(fr)), ink=col?erdContrast(col):(fr.name?C.ink:C.faint);
    s+='<g class="erd-ftab"><rect x="'+f(x+8)+'" y="'+f(y+8)+'" width="'+f(tabW)+'" height="'+FRAME_TAB_H+'" rx="6"'+
       st({fill:col||C.head, stroke:col?'':C.line, 'stroke-width':1})+'/>'+
       textEl(x+19, y+24.5, clip(name, F_FRAME, tabW-22), fr.name?F_FRAME:'italic '+F_FRAME, ink)+'</g>';
  }
  if(o.interactive && sel){
    [['nw',x,y],['ne',x+w,y],['sw',x,y+h],['se',x+w,y+h]].forEach(([k,cx,cy])=>{
      s+='<rect class="erd-fh" data-h="'+k+'" x="'+f(cx-5)+'" y="'+f(cy-5)+'" width="10" height="10" rx="2"'+
         st({fill:C.panel, stroke:C.accent, 'stroke-width':1.5})+'/>';
    });
  }
  return s+'</g>';
}
/** Frames back to front: a larger one first, so a frame inside another is drawn — and clicked — on top. */
const framesBackToFront=d=>(d.frames||[]).map((fr,i)=>({fr,i})).sort((a,b)=>(b.fr.w*b.fr.h-a.fr.w*a.fr.h) || (a.i-b.i)).map(x=>x.fr);
/** The whole diagram as SVG markup: the canvas draws it with the page's colours and its handles, an export
 *  with paper colours and nothing interactive — one drawing, so the picture is what the screen showed. */
function sceneSvg(d, C, o){
  const lays=new Map(d.tables.map(t=>[t.key, layout(t, o.q, d)]));
  const rels=d.relations.filter(r=>lays.has(r.from)&&lays.has(r.to));
  const sugs=o.suggestions?visibleSuggestions(d, lays):[];
  const fan=fans(rels.concat(sugs));
  const geo=new Map();
  rels.concat(sugs).forEach(x=>{ const g=route(x, lays, fan.get(x.id)||0); if(g) geo.set(x.id, g); });
  spreadPills(rels.filter(r=>r.label && geo.has(r.id)).map(r=>({g:geo.get(r.id), w:tw(r.label+'  ·  '+r.cardinality, F_PILL)+18}))
    .concat(sugs.filter(sg=>geo.has(sg.id)).map(sg=>({g:geo.get(sg.id), w:tw('+ '+(sg.label||'suggested')+'  ·  '+sg.cardinality, F_PILL)+42}))));
  let s='<g class="erd-frames">';
  framesBackToFront(d).forEach(fr=>{ s+=frameSvg(fr, C, {interactive:o.interactive, sel:o.sel&&o.sel.kind==='frame'&&o.sel.id===fr.id}); });
  s+='</g><g class="erd-rels">';
  rels.forEach(r=>{ const g=geo.get(r.id); if(g) s+=relationSvg(r, g, C, {interactive:o.interactive, sel:o.sel&&o.sel.kind==='rel'&&o.sel.id===r.id}); });
  s+='</g><g class="erd-sugs">';
  sugs.forEach(sg=>{ const g=geo.get(sg.id); if(g) s+=suggestionSvg(sg, g, C); });
  s+='</g><g class="erd-cards">';
  d.tables.forEach(t=>{ s+=cardSvg(lays.get(t.key), C, {interactive:o.interactive, q:o.q, sel:o.sel&&o.sel.kind==='table'&&o.sel.key===t.key}); });
  return {markup:s+'</g>', lays};
}
/**
 * Relation names that would sit on top of each other — two lines meeting in one gap between columns — are
 * moved apart vertically, the lower one below the upper, so every name stays readable. Each keeps its
 * horizontal place on its line; `g.pill` is where its name is drawn.
 */
function spreadPills(pills){
  const H=22, PAD=4;
  pills.forEach(p=>{ p.c=mid(p.g); });
  pills.sort((a,b)=>(a.c.y-b.c.y) || (a.c.x-b.c.x));
  pills.forEach((p,i)=>{
    for(let pass=0; pass<pills.length; pass++){
      const hit=pills.slice(0,i).find(q=>Math.abs(p.c.x-q.c.x)<(p.w+q.w)/2+PAD && Math.abs(p.c.y-q.c.y)<H+PAD);
      if(!hit) break;
      p.c={x:p.c.x, y:hit.c.y+H+PAD};
    }
    p.g.pill=p.c;
  });
}
/** Proposals worth showing: both tables on the canvas, not dismissed, and not already drawn by hand. */
function visibleSuggestions(d, lays){ return erdOpenSuggestions(S.suggestions, d.relations, new Set(lays.keys()), d.dismissed); }
/** What the drawing covers — its tables and its frames — with [pad] around it; null for an empty one. */
function bounds(lays, pad, frames){
  let x0=Infinity, y0=Infinity, x1=-Infinity, y1=-Infinity;
  lays.forEach(L=>{ x0=Math.min(x0,L.x); y0=Math.min(y0,L.y); x1=Math.max(x1,L.x+L.w); y1=Math.max(y1,L.y+L.h); });
  (frames||[]).forEach(F=>{ x0=Math.min(x0,F.x); y0=Math.min(y0,F.y); x1=Math.max(x1,F.x+F.w); y1=Math.max(y1,F.y+F.h); });
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
  root.classList.add('erd-host');
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
      '<div class="erd-side-resize" role="separator" aria-orientation="vertical" tabindex="0" aria-valuemin="'+SIDE_MIN+'" '+
        'aria-valuemax="'+SIDE_MAX+'"></div>'+
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
          '<span class="erd-sep"></span>'+
          btn('arrange', ico('<rect width="7" height="6" x="2" y="3" rx="1"/><rect width="7" height="6" x="15" y="3" rx="1"/>'+
            '<rect width="7" height="6" x="15" y="15" rx="1"/><path d="M9 6h6"/><path d="M18.5 9v6"/>')+'<span>Arrange</span>',
            'Arrange — lay the tables out by their relations: each one side left of its many side, crossings kept low ('+MODK+'Z undoes it)',
            ' erd-arrangebtn')+
          btn('expand-all', ico('<path d="m7 15 5 5 5-5"/><path d="m7 9 5-5 5 5"/>'), 'Expand all — every table shows all its columns')+
          btn('collapse-all', ico('<path d="m7 20 5-5 5 5"/><path d="m7 4 5 5 5-5"/>'), 'Collapse all — every table back to its first '+VISIBLE+' columns')+
          btn('frame', ico('<path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/>'+
            '<path d="M7 21H5a2 2 0 0 1-2-2v-2"/><rect width="7" height="5" x="7" y="7" rx="1"/><rect width="7" height="5" x="10" y="12" rx="1"/>')+
            '<span>Frame</span>', 'Frame — drag over tables to put them in a frame you can name (F)', ' erd-framebtn')+
          '<span class="erd-sep"></span>'+
          '<div class="erd-qwrap">'+ico('<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>')+
            '<input class="erd-q" type="search" placeholder="Search tables and columns" aria-label="Search every table and column of the project" '+
              'autocomplete="off" spellcheck="false" aria-controls="erd-results" aria-expanded="false">'+
            '<kbd class="kbd erd-qkbd">'+MODK+'F</kbd>'+
            '<div class="erd-results" id="erd-results" role="listbox" aria-label="Search results" hidden></div></div>'+
          '<span class="erd-grow"></span>'+
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
  els={root, box:q('.erd'), list:q('.erd-list'), filter:q('.erd-filter'), pick:q('.erd-pick'), status:document.getElementById('erd-status'),
    pct:q('.erd-pct'), canvas:q('.erd-canvas'), svg:q('.erd-svg'), world:q('.erd-world'), overlay:q('.erd-overlay'),
    hint:q('.erd-empty'), pop:q('.erd-pop'), file:q('.erd-file'), bar:q('.erd-bar'),
    search:q('.erd-q'), results:q('.erd-results'), qwrap:q('.erd-qwrap'), sideResize:q('.erd-side-resize')};
  wire();
}
// ---------- the list's width ----------
// A table name of forty characters does not fit the list's 264px. Its right edge drags, as the explorer's
// sidebar does — the same contract: ←/→ by 16px, Home or a double click resets — and the width is
// remembered in this browser: it is how one person likes the page, not part of any diagram.
const SIDE_MIN=200, SIDE_MAX=640, SIDE_DEF=264, SIDE_KEY='atlas-erd-side-w';
function sideClamp(v){ return Math.round(Math.max(SIDE_MIN, Math.min(SIDE_MAX, v))); }
function sideWidth(){
  let w=NaN; try{ w=parseInt(localStorage.getItem(SIDE_KEY), 10); }catch(e){}
  return w>=SIDE_MIN && w<=SIDE_MAX ? w : SIDE_DEF;
}
function applySide(w, keep){
  els.box.style.setProperty('--erd-side-w', w+'px');
  els.sideResize.setAttribute('aria-valuenow', String(w));
  els.sideResize.setAttribute('aria-label', 'Table list width '+w+'px — drag to resize, double-click to reset');
  if(keep===undefined) return;
  try{ if(keep) localStorage.setItem(SIDE_KEY, String(w)); else localStorage.removeItem(SIDE_KEY); }catch(e){}
}
function wireSideResize(){
  const h=els.sideResize;
  applySide(sideWidth());
  let startX=0, startW=0, dragging=false;
  h.addEventListener('pointerdown', e=>{
    dragging=true; startX=e.clientX; startW=els.side().getBoundingClientRect().width;
    try{ h.setPointerCapture(e.pointerId); }catch(_){}
    els.box.classList.add('resizing'); e.preventDefault();
  });
  h.addEventListener('pointermove', e=>{ if(dragging) applySide(sideClamp(startW+e.clientX-startX)); });
  const end=e=>{
    if(!dragging) return; dragging=false;
    els.box.classList.remove('resizing');
    try{ h.releasePointerCapture(e.pointerId); }catch(_){}
    applySide(sideClamp(els.side().getBoundingClientRect().width), true);
  };
  h.addEventListener('pointerup', end);
  h.addEventListener('pointercancel', end);
  h.addEventListener('dblclick', ()=>applySide(SIDE_DEF, false));
  h.addEventListener('keydown', e=>{
    if(e.key==='ArrowLeft' || e.key==='ArrowRight'){
      e.preventDefault();
      applySide(sideClamp(els.side().getBoundingClientRect().width+(e.key==='ArrowRight'?16:-16)), true);
    } else if(e.key==='Home'){ e.preventDefault(); applySide(SIDE_DEF, false); }
  });
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
    // the one data object that reads the table is its business name: its key, copied from here, before the
    // table is even on the canvas
    const doKey=t.dataObjects.length===1 && t.alias===t.dataObjects[0].name ? t.dataObjects[0].key : '';
    const alias=t.alias&&t.alias!==t.name?esc(t.alias)+(doKey?copyBtn(doKey, 'Copy the data object key '+doKey, 'erd-lcpy'):''):'';
    const sub=[alias, t.columns.length+' column'+(t.columns.length===1?'':'s'),
      t.source==='service'?'<span class="erd-src-svc" data-tip="No changelog creates this table — the columns are the service’s mappings, the types its logical ones">service model</span>':'']
      .filter(Boolean).join(' · ');
    return '<div class="erd-item'+(on.has(t.key)?' on':'')+'" role="option" tabindex="-1" data-key="'+esc(t.key)+'" aria-selected="'+on.has(t.key)+'">'+
      '<div class="erd-item-main"><div class="erd-item-n">'+esc(t.name)+'</div><div class="erd-item-s">'+sub+(hit?' · '+hit:'')+'</div></div>'+
      infoBtn(t.key)+
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
  const {markup, lays}=sceneSvg(d, PAGE, {interactive:true, suggestions:!S.present, sel:S.sel, q:S.q});
  els.world.innerHTML=markup;
  els.lays=lays;
  applyView();
  els.hint.hidden=d.tables.length>0 || (d.frames||[]).length>0;
  els.canvas.classList.toggle('has-sel', !!S.sel);
  els.canvas.classList.toggle('searching', !!S.q);
  els.canvas.classList.toggle('framing', S.tool==='frame');
  const fb=els.bar.querySelector('[data-act=frame]');
  if(fb) fb.setAttribute('aria-pressed', String(S.tool==='frame'));
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
  const v=view(), b=bounds(els.lays||new Map(), 32, active().frames), r=els.svg.getBoundingClientRect();
  if(!b || r.width<50 || r.height<50){ v.s=1; v.tx=40; v.ty=40; applyView(); return; }
  // presenting, a small diagram may grow to fill the room it has — it is being shown across a table
  v.s=Math.max(0.2, Math.min(S.present?1.6:1.25, r.width/b.w, r.height/b.h));
  v.tx=(r.width-b.w*v.s)/2-b.x*v.s; v.ty=(r.height-b.h*v.s)/2-b.y*v.s;
  applyView();
}
function center(key, column){
  const L=els.lays&&els.lays.get(key); if(!L) return;
  const v=view(), r=els.svg.getBoundingClientRect();
  const row=column && L.rows.find(x=>erdKey(x.c.name)===erdKey(column));
  v.tx=r.width/2-(L.x+L.w/2)*v.s; v.ty=r.height/2-(row?L.y+row.y+ROW/2:L.y+Math.min(L.h,160)/2)*v.s;
  applyView();
}

// ---------- editing ----------
function selected(){
  const d=active(), s=S.sel;
  if(!s) return null;
  if(s.kind==='table') return d.tables.find(t=>t.key===s.key)||null;
  if(s.kind==='frame') return (d.frames||[]).find(x=>x.id===s.id)||null;
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
  // a frame goes, what it held stays where it is
  else if(s.kind==='frame') mutate(d=>{ d.frames=d.frames.filter(x=>x.id!==s.id); });
  else mutate(d=>{ d.relations=d.relations.filter(r=>r.id!==s.id); });
  S.sel=null; closePop(); draw();
}
function newRelId(d){ let i=d.relations.length+1; while(d.relations.some(r=>r.id==='r'+i)) i++; return 'r'+i; }
function addRelation(from, to, toColumn, extra){
  let id=null;
  mutate(d=>{ id=newRelId(d); d.relations.push(Object.assign({id, from, to, pairs:toColumn?[{from:'', to:toColumn}]:[], cardinality:'1:0..n', label:''}, extra||{})); });
  return id;
}
/**
 * Lay every table out by its relations (erdArrange; erdArrangeFramed keeps each frame's tables together and
 * fits the frame around them), as one undo step. The cards and frames glide to their new places so the
 * reader can follow where each one went — unless the system asks for less motion.
 */
function arrange(){
  const d=active();
  if(d.tables.length<2){ toast('Arrange needs at least two tables on the canvas'); return; }
  closePop();
  const lays=els.lays||new Map();
  const nodes=boxesOf(d);
  // a relation's name, and a proposal's, need room between the two columns it joins
  const edges=d.relations.map(r=>({from:r.from, to:r.to, cardinality:r.cardinality,
      gap:r.label?tw(r.label+'  ·  '+r.cardinality, F_PILL)+18:0}))
    .concat(visibleSuggestions(d, lays).map(sg=>({from:sg.from, to:sg.to, cardinality:sg.cardinality,
      gap:tw('+ '+(sg.label||'suggested')+'  ·  '+sg.cardinality, F_PILL)+46})));
  const res=erdArrangeFramed(nodes, edges, d.frames.map(fr=>({id:fr.id, x:fr.x, y:fr.y, w:fr.w, h:fr.h, minW:frameTabW(fr)+16}))),
    target=res.tables, frameTo=res.frames, before=snap(d);
  const from=new Map(d.tables.map(t=>[t.key, {x:t.x, y:t.y}]));
  const fromF=new Map(d.frames.map(fr=>[fr.id, {x:fr.x, y:fr.y, w:fr.w, h:fr.h}]));
  const done=()=>{
    d.tables.forEach(t=>{ const p=target[t.key]; if(p){ t.x=p.x; t.y=p.y; } });
    d.frames.forEach(fr=>{ const p=frameTo[fr.id]; if(p) Object.assign(fr, p); });
    commitGesture(before);
    fit();
    const moved=d.tables.filter(t=>{ const a=from.get(t.key); return a.x!==t.x || a.y!==t.y; }).length+
      d.frames.filter(fr=>{ const a=fromF.get(fr.id); return a.x!==fr.x || a.y!==fr.y || a.w!==fr.w || a.h!==fr.h; }).length;
    toast(moved?'Arranged '+d.tables.length+' tables — '+MODK+'Z puts them back':'Already arranged — nothing moved');
  };
  let still=false;
  try{ still=matchMedia('(prefers-reduced-motion: reduce)').matches; }catch(e){}
  if(still){ done(); return; }
  const t0=performance.now(), MS=320;
  const step=now=>{
    const k=Math.min(1, (now-t0)/MS), e=1-Math.pow(1-k, 3);     // ease out: fast start, gentle landing
    d.tables.forEach(t=>{ const a=from.get(t.key), b=target[t.key]; if(a && b){ t.x=a.x+(b.x-a.x)*e; t.y=a.y+(b.y-a.y)*e; } });
    d.frames.forEach(fr=>{ const a=fromF.get(fr.id), b=frameTo[fr.id]; if(a && b) ['x','y','w','h'].forEach(p=>{ fr[p]=a[p]+(b[p]-a[p])*e; }); });
    draw();
    if(k<1) requestAnimationFrame(step); else done();
  };
  requestAnimationFrame(step);
}
/** Every card as the box it takes on the canvas now — what frames and Arrange measure against. */
function boxesOf(d){
  const lays=els && els.lays;
  return d.tables.map(t=>{ const L=(lays && lays.get(t.key))||layout(t, S.q); return {key:t.key, w:L.w, h:L.h, x:t.x, y:t.y}; });
}
/** The tables and frames inside frame [id] — directly or in a frame inside it — as they are now. */
function frameContents(d, id, boxes){
  const par=erdFrameParents(d.frames, boxes||boxesOf(d));
  const within=f=>{ while(f){ if(f===id) return true; f=par.frame.get(f); } return false; };
  return {tables:d.tables.filter(t=>within(par.table.get(t.key))), frames:d.frames.filter(x=>x.id!==id && within(par.frame.get(x.id)))};
}
/**
 * Change the cards' size ([fn]: unfold, fold) without them slipping out of their frames: a frame holds a card
 * by its centre, and unfolding moves the centre down. Each frame grows, never shrinks, to go on holding
 * what it held — and so does the frame around it.
 */
function keepFramed(d, fn){
  if(!(d.frames||[]).length){ fn(d); return; }
  const par=erdFrameParents(d.frames, boxesOf(d));
  fn(d);
  const byId=new Map(d.frames.map(x=>[x.id, x]));
  const grow=(id, x1, y1)=>{
    for(let fr=byId.get(id); fr; fr=byId.get(par.frame.get(fr.id))){
      fr.w=Math.max(fr.w, Math.ceil((x1+ERD_FRAME_PAD-fr.x)/4)*4); fr.h=Math.max(fr.h, Math.ceil((y1+ERD_FRAME_PAD-fr.y)/4)*4);
      x1=fr.x+fr.w; y1=fr.y+fr.h;
    }
  };
  d.tables.forEach(t=>{ const id=par.table.get(t.key); if(!id) return; const L=layout(t, S.q); grow(id, t.x+L.w, t.y+L.h); });
}
function newFrameId(d){ let i=d.frames.length+1; while(d.frames.some(x=>x.id==='f'+i)) i++; return 'f'+i; }
/** A frame on the canvas at [r] (world coordinates), selected, its panel open to name it. */
function addFrame(r){
  let id=null;
  mutate(d=>{ id=newFrameId(d); d.frames.push({id, name:'', x:Math.round(r.x/4)*4, y:Math.round(r.y/4)*4,
    w:Math.max(ERD_FRAME_MIN_W, Math.round(r.w/4)*4), h:Math.max(ERD_FRAME_MIN_H, Math.round(r.h/4)*4), color:''}); });
  if(!id) return;
  S.sel={kind:'frame', id}; draw();
  openFramePop(id, true);
}
/** The frame drawn again around what it holds, with room for its name — after the tables have moved on. */
function fitFrame(id){
  const d=active(), fr=d.frames.find(x=>x.id===id);
  if(!fr) return;
  const lays=els.lays||new Map(), c=frameContents(d, id);
  const boxes=c.tables.map(t=>{ const L=lays.get(t.key)||layout(t, S.q); return {x:t.x, y:t.y, w:L.w, h:L.h}; }).concat(c.frames);
  if(!boxes.length){ toast('Nothing in this frame to fit it to — drag tables into it first'); return; }
  const b=bounds(new Map(boxes.map((x,i)=>[i, x])), 0);
  mutate(x=>{ const g=x.frames.find(y=>y.id===id); if(!g) return;
    g.x=Math.floor((b.x-ERD_FRAME_PAD)/4)*4; g.y=Math.floor((b.y-ERD_FRAME_TOP)/4)*4;
    g.w=Math.max(ERD_FRAME_MIN_W, frameTabW(g)+16, Math.ceil((b.x+b.w+ERD_FRAME_PAD-g.x)/4)*4);
    g.h=Math.max(ERD_FRAME_MIN_H, Math.ceil((b.y+b.h+ERD_FRAME_PAD-g.y)/4)*4); });
}
function setTool(tool){
  S.tool=tool;
  if(tool==='frame'){ closePop(); toast('Drag over the tables to frame them — Esc cancels'); }
  draw();
}
/** Every card unfolded to all its columns, or folded back to the first five — one undo step either way. */
function setAllExpanded(on){
  const d=active();
  if(!d.tables.length){ toast('No tables on the canvas yet'); return; }
  const n=d.tables.filter(t=>!!t.expanded!==on).length;
  if(!n){ toast(on?'Every table already shows all its columns':'Every table already shows its first '+VISIBLE+' columns'); return; }
  mutate(x=>keepFramed(x, x=>{ x.tables.forEach(t=>{ t.expanded=on; }); if(on) makeRoom(x); }));
  toast((on?'Expanded ':'Collapsed ')+n+' table'+(n===1?'':'s')+' — '+MODK+'Z undoes it');
}
/**
 * Cards that grew now reach over the ones below them: move those down, top to bottom, just far enough —
 * a card only moves for one it shares columns of the canvas with, so the layout keeps its shape. Folding
 * back moves nothing: the room stays, and Arrange closes it up.
 */
function makeRoom(d){
  const GAP=24;
  const boxes=d.tables.map(t=>{ const L=layout(t, S.q); return {t, w:L.w, h:L.h}; });
  // again until nothing moves: a card pushed down can pass one that was below it, and must then clear it
  for(let round=0; round<boxes.length+1; round++){
    let moved=false;
    boxes.sort((p,q)=>(p.t.y-q.t.y) || (p.t.x-q.t.x));
    boxes.forEach((cur,i)=>{
      const over=boxes.slice(0,i).find(o=>o.t.x<cur.t.x+cur.w+GAP && cur.t.x<o.t.x+o.w+GAP && cur.t.y<o.t.y+o.h+GAP);
      if(over){ cur.t.y=Math.ceil((over.t.y+over.h+GAP)/4)*4; moved=true; }
    });
    if(!moved) break;
  }
}

// ---------- search ----------
/** The query, for the canvas and the result list: lower-cased, trimmed, empty when there is none. */
function setSearch(text){
  const q=String(text||'').trim().toLowerCase();
  if(els.search && els.search.value!==text && text!=null) els.search.value=text;
  if(q===S.q) { renderResults(); return; }
  S.q=q; S.resIdx=0;
  renderResults(); draw();
}
const markQ=text=>{
  const t=String(text==null?'':text), i=S.q?t.toLowerCase().indexOf(S.q):-1;
  return i<0?esc(t):esc(t.slice(0,i))+'<mark>'+esc(t.slice(i, i+S.q.length))+'</mark>'+esc(t.slice(i+S.q.length));
};
function renderResults(){
  const box=els.results;
  if(!box) return;
  const open=!!S.q && document.activeElement===els.search;
  els.search.setAttribute('aria-expanded', String(open));
  if(!open){ box.hidden=true; return; }
  const r=erdSearch(S.catalog, S.q, new Set(active().tables.map(t=>t.key)), 60);
  S.results=r.items;
  if(S.resIdx>=r.items.length) S.resIdx=0;
  let h='<div class="erd-rhead">'+(r.total?r.tables+' table'+(r.tables===1?'':'s')+' · '+r.columns+' column'+(r.columns===1?'':'s')
    :'Nothing matches “'+esc(S.q)+'”')+'</div>';
  r.items.forEach((it,i)=>{
    const main=it.kind==='table'
      ? '<span class="erd-rname">'+markQ(it.table)+'</span>'+(it.alias?'<span class="erd-rsub">'+markQ(it.alias)+'</span>':'')
      : '<span class="erd-rsub">'+esc(it.table)+'.</span><span class="erd-rname">'+(it.pk?'<b class="erd-pk">PK</b>':'')+markQ(it.column)+'</span>';
    h+='<div class="erd-res'+(i===S.resIdx?' on':'')+'" role="option" aria-selected="'+(i===S.resIdx)+'" data-i="'+i+'">'+
      '<span class="erd-rkind">'+(it.kind==='table'?'table':'column')+'</span><span class="erd-rmain">'+main+'</span>'+
      '<span class="erd-rtype">'+(it.kind==='table'?it.count+' columns':markQ(it.type))+'</span>'+
      (it.on?'':'<span class="erd-roff" data-tip="Not on this diagram — choosing it adds the table">add</span>')+'</div>';
  });
  if(r.total>r.items.length) h+='<div class="erd-rmore">'+(r.total-r.items.length)+' more — type more of the name</div>';
  box.innerHTML=h; box.hidden=false;
  const cur=box.querySelector('.erd-res.on'); if(cur) cur.scrollIntoView({block:'nearest'});
}
/** Go to what was found: the card centred on the column, added to the diagram first when it is not on it. */
function openResult(it){
  if(!it) return;
  const d=active();
  if(!d.tables.some(t=>t.key===it.key)) addTable(it.key);
  S.sel={kind:'table', key:it.key};
  draw();
  center(it.key, it.column);
  els.results.hidden=true;
  els.search.setAttribute('aria-expanded','false');
}
function focusSearch(){ if(!els || !els.search) return; els.search.focus(); els.search.select(); renderResults(); }
function wireSearch(){
  const inp=els.search;
  inp.addEventListener('input', ()=>setSearch(inp.value));
  inp.addEventListener('focus', ()=>renderResults());
  inp.addEventListener('blur', ()=>setTimeout(()=>{ if(document.activeElement!==inp){ els.results.hidden=true; inp.setAttribute('aria-expanded','false'); } }, 120));
  inp.addEventListener('keydown', e=>{
    const n=S.results.length;
    if(e.key==='ArrowDown' || e.key==='ArrowUp'){
      e.preventDefault();
      if(n){ S.resIdx=(S.resIdx+(e.key==='ArrowDown'?1:-1)+n)%n; renderResults(); }
    } else if(e.key==='Enter'){
      e.preventDefault();
      if(n) openResult(S.results[S.resIdx]);
    } else if(e.key==='Escape'){
      // first the list, then the query; the page's own Escape (panels, presenting) waits for the next one
      e.preventDefault(); e.stopPropagation();
      if(!els.results.hidden){ els.results.hidden=true; inp.setAttribute('aria-expanded','false'); }
      else if(inp.value){ inp.value=''; setSearch(''); }
      else inp.blur();
    }
  });
  // pointerdown, not click: a click would blur the field first and close the list under the pointer
  els.results.addEventListener('pointerdown', e=>{
    const row=e.target.closest('.erd-res');
    if(!row) return;
    e.preventDefault();
    openResult(S.results[+row.dataset.i]);
  });
}
function acceptSuggestion(id){
  const sg=S.suggestions.find(x=>x.id===id);
  if(!sg) return;
  const rid=addRelation(sg.from, sg.to, '', {cardinality:sg.cardinality, label:sg.label, pairs:sg.pairs.map(p=>({from:p.from, to:p.to}))});
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
  els.side=()=>els.box.querySelector('.erd-side');
  wireSideResize();
  wireSearch();
  els.filter.addEventListener('input', ()=>{ S.filter=els.filter.value; renderList(); });
  els.list.addEventListener('pointerdown', onListDown);
  els.list.addEventListener('click', e=>{
    const cp=e.target.closest('[data-copy]');
    if(cp){ const k=cp.dataset.copy; atlasCopy(k, ()=>toast('Copied '+k)); return; }
    const ib=e.target.closest('[data-info]');
    if(ib){ openInfoPop(ib.dataset.info, ib); return; }
    const add=e.target.closest('[data-add]');
    if(add){ addTable(add.dataset.add); return; }
    const it=e.target.closest('.erd-item');
    if(it && it.classList.contains('on')){ select({kind:'table', key:it.dataset.key}); center(it.dataset.key); }
  });
  els.list.addEventListener('dblclick', e=>{ const it=e.target.closest('.erd-item'); if(it && !e.target.closest('[data-add],[data-copy],[data-info]')) addTable(it.dataset.key); });
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
  // the frame tool draws wherever the drag starts, over a card too: a frame is drawn around tables
  if(S.tool==='frame'){ g={kind:'frame-draw', start}; }
  else if(t.closest('.erd-sug-x')){ g={kind:'click', up:()=>dismissSuggestion(t.closest('.erd-sug').dataset.sug)}; }
  else if(t.closest('.erd-sug')){ g={kind:'click', up:()=>acceptSuggestion(t.closest('.erd-sug').dataset.sug)}; }
  else if(t.closest('.erd-rel')){
    const id=t.closest('.erd-rel').dataset.rel;
    g={kind:'click', up:ev=>{ select({kind:'rel', id}); openRelPop(id, ev); }};
  }
  else if(t.closest('.erd-fh')){
    const fr=d.frames.find(x=>x.id===t.closest('.erd-frame').dataset.frame);
    g={kind:'frame-size', fr, h:t.closest('.erd-fh').dataset.h, r0:{x:fr.x, y:fr.y, w:fr.w, h:fr.h}, start, before:snap(d)};
  }
  else if(t.closest('.erd-ftab') || t.closest('.erd-fedge')){
    // a frame moves with everything it holds, as they are when the drag starts
    const id=t.closest('.erd-frame').dataset.frame, fr=d.frames.find(x=>x.id===id), c=frameContents(d, id);
    g={kind:'frame-move', id, start, before:snap(d), items:[fr].concat(c.frames, c.tables).map(o=>({o, x:o.x, y:o.y}))};
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
    g={kind:'click', up:()=>mutate(d=>keepFramed(d, d=>{ const x=d.tables.find(x=>x.key===key); if(x) x.expanded=!x.expanded; }))};
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
    else if(g.kind==='frame-draw' && g.moved){ frameDrawPreview(g, p); }
    else if(g.kind==='frame-move' && g.moved){
      const dx=Math.round((p.x-g.start.x)/4)*4, dy=Math.round((p.y-g.start.y)/4)*4;
      g.items.forEach(it=>{ it.o.x=it.x+dx; it.o.y=it.y+dy; });
      if(!S.sel || S.sel.id!==g.id) S.sel={kind:'frame', id:g.id};
      frame();
    }
    else if(g.kind==='frame-size' && g.moved){ resizeFrame(g, p); frame(); }
    else if(g.kind==='reorder' && g.moved){ reorderPreview(g, p); }
  };
  const up=ev=>{
    svg.removeEventListener('pointermove', move); svg.removeEventListener('pointerup', up); svg.removeEventListener('pointercancel', cancel);
    S.drag=null;
    els.overlay.innerHTML='';
    els.root.querySelectorAll('.erd-card.target').forEach(c=>c.classList.remove('target'));
    if(g.kind==='click'){ if(!g.moved) g.up(ev); return; }
    if(g.kind==='frame-draw'){
      S.tool=null;
      const p=toWorld(ev.clientX, ev.clientY), w=Math.abs(p.x-g.start.x), h=Math.abs(p.y-g.start.y);
      // a click, or a drag too small to mean a size, puts down a frame of a useful size where it was
      if(!g.moved || w<24 || h<24) addFrame({x:g.start.x, y:g.start.y, w:480, h:320});
      else addFrame({x:Math.min(p.x, g.start.x), y:Math.min(p.y, g.start.y), w, h});
      return;
    }
    if(g.kind==='frame-move'){
      if(g.moved){ commitGesture(g.before); return; }
      select({kind:'frame', id:g.id}); openFramePop(g.id);
      return;
    }
    if(g.kind==='frame-size'){ if(g.moved) commitGesture(g.before); else draw(); return; }
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
    if(['move','reorder','frame-move','frame-size'].indexOf(g.kind)>=0 && g.moved) restore(active(), g.before);
    if(g.kind==='frame-draw') S.tool=null;
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
/** The frame being drawn, and the cards it is going to hold — those whose centre is inside it. */
function frameDrawPreview(g, p){
  const x=Math.min(g.start.x, p.x), y=Math.min(g.start.y, p.y), w=Math.abs(p.x-g.start.x), h=Math.abs(p.y-g.start.y);
  els.overlay.innerHTML='<rect x="'+f(x)+'" y="'+f(y)+'" width="'+f(w)+'" height="'+f(h)+'" rx="12"'+
    st({fill:PAGE.accent, 'fill-opacity':0.06, stroke:PAGE.accent, 'stroke-width':1.5, 'stroke-dasharray':'6 4'})+'/>';
  (els.lays||new Map()).forEach((L, key)=>{
    const cx=L.x+L.w/2, cy=L.y+L.h/2, on=cx>=x && cx<=x+w && cy>=y && cy<=y+h;
    const el=els.world.querySelector('.erd-card[data-key="'+cssEsc(key)+'"]');
    if(el) el.classList.toggle('target', on);
  });
}
/** A frame dragged by a corner: that corner follows the pointer, the opposite one stays, never below the least size. */
function resizeFrame(g, p){
  const r=g.r0, dx=p.x-g.start.x, dy=p.y-g.start.y, q=v=>Math.round(v/4)*4;
  let x0=r.x, y0=r.y, x1=r.x+r.w, y1=r.y+r.h;
  if(g.h.indexOf('w')>=0) x0=Math.min(x1-ERD_FRAME_MIN_W, q(r.x+dx)); else x1=Math.max(x0+ERD_FRAME_MIN_W, q(x1+dx));
  if(g.h.indexOf('n')>=0) y0=Math.min(y1-ERD_FRAME_MIN_H, q(r.y+dy)); else y1=Math.max(y0+ERD_FRAME_MIN_H, q(y1+dy));
  Object.assign(g.fr, {x:x0, y:y0, w:x1-x0, h:y1-y0});
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
  if(!it || e.button!==0 || e.target.closest('[data-add],[data-copy],[data-info]')) return;
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
  const e=effective(t);
  const html='<div class="erd-pop-h">'+popTitle(t.name)+'<span class="erd-grow"></span>'+infoBtn(key)+closeBtn()+'</div>'+
    '<div class="erd-infowrap" hidden>'+infoHtml(key)+'</div>'+
    (e.ghost?'<p class="erd-warn">This project’s schema has no table of this name — the card shows the columns the diagram was saved with.</p>':'')+
    '<label class="erd-fld"><span>Business name</span><input data-f="alias" value="'+esc(t.alias)+'" placeholder="'+esc(t.name)+'" autofocus></label>'+
    swatchesHtml(t.color)+
    '<div class="erd-fld"><span>Columns <em>the first '+VISIBLE+' show on a folded card</em></span>'+
      (e.order.length>8?'<input class="erd-colq" type="search" placeholder="Filter the '+e.order.length+' columns" aria-label="Filter the columns" value="'+
        esc(S.q && erdColumnHits(e.columns, S.q).size?S.q:'')+'">':'')+
      '<ol class="erd-cols"></ol>'+
      '<label class="erd-check"><input type="checkbox" data-f="expanded"'+(t.expanded?' checked':'')+'> Show all columns on the card</label></div>'+
    '<div class="erd-pop-a"><button type="button" class="tbtn erd-danger" data-pa="remove">Remove from the diagram</button></div>';
  openPop('table', html, nearOfCard(key));
  renderPopCols(key);
  const pop=els.pop;
  pop.querySelector('[data-f=alias]').addEventListener('input', ev=>{ const val=ev.target.value; mutate(d=>{ const x=d.tables.find(x=>x.key===key); if(x) x.alias=val.slice(0,120); }, 'alias:'+key); });
  const tableOf=d=>d.tables.find(x=>x.key===key);
  pop.querySelector('[data-f=color]').addEventListener('input', ev=>setColor(tableOf, ev.target.value, 'color:'+key));
  const cq=pop.querySelector('.erd-colq');
  if(cq) cq.addEventListener('input', ()=>renderPopCols(key));
  pop.querySelector('[data-f=expanded]').addEventListener('change', ev=>{ const on=ev.target.checked; mutate(d=>keepFramed(d, d=>{ const x=d.tables.find(x=>x.key===key); if(x) x.expanded=on; })); });
  pop.addEventListener('click', ev=>{
    const ib=ev.target.closest('[data-info]');
    if(ib){ const w=pop.querySelector('.erd-infowrap'); w.hidden=!w.hidden; ib.classList.toggle('on', !w.hidden); return; }
    const cp=ev.target.closest('[data-copy]');
    if(cp){ const k=cp.dataset.copy; atlasCopy(k, ()=>toast('Copied '+k)); return; }
    const op=ev.target.closest('[data-open]');
    if(op && window.__atlasOpen){ window.__atlasOpen(op.dataset.open); return; }
    const sw=ev.target.closest('[data-color]');
    if(sw){ setColor(tableOf, sw.dataset.color); return; }
    const mv=ev.target.closest('[data-mv]');
    if(mv){ moveCol(key, mv.dataset.col, +mv.dataset.mv); return; }
    const a=ev.target.closest('[data-pa]');
    if(a && a.dataset.pa==='remove'){ S.sel={kind:'table', key}; removeSelected(); }
    if(ev.target.closest('[data-pa=close]')) closePop();
  });
}
/**
 * What is behind a table: its changelog, the services that map it and the data objects that read it — each
 * name on a line of its own and its key under it, to copy (the key is what code and models refer to it by:
 * definitionKey("…"), a service task's serviceKey). A label column and nothing else: name and key side by side
 * squeezed a long changelog name to its first letters, and a row for the table repeated the panel's title,
 * which carries its own copy button. Behind an (i) badge, in the table panel and in the list: it is
 * reference, not the diagram.
 */
function infoHtml(key){
  const c=S.byKey.get(key);
  if(!c) return '<p class="erd-warn">Not in this project’s schema.</p>';
  const entry=(id, label, k)=>'<div class="erd-ie"><div class="erd-in">'+srcLink(id, label)+'</div>'+(k?'<div class="erd-ik">'+keyChip(k)+'</div>':'')+'</div>';
  const group=(one, many, items)=>items.length?'<dt>'+(items.length>1?many:one)+'</dt><dd>'+items.join('')+'</dd>':'';
  const changelog=c.changelog?[entry(c.changelog, (byId.get(c.changelog) || {}).label || c.changelog, null)]:[];
  const services=c.services.map(id=>{ const n=byId.get(id); return entry(id, n?n.label:id, n && n.key); });
  const dataObjects=c.dataObjects.map(x=>entry(x.id, x.name, x.key));
  return '<dl class="erd-info">'+group('Changelog', 'Changelogs', changelog)+group('Service', 'Services', services)+
    group('Data object', 'Data objects', dataObjects)+'</dl>';
}
/** The panel's title — the table's name — with the button that copies it. */
function popTitle(name){
  return '<span class="erd-pop-t">'+esc(name)+'</span>'+copyBtn(name, 'Copy the table name');
}
function infoBtn(key){
  return '<button type="button" class="erd-ibtn" data-info="'+esc(key)+'" data-tip="What is behind this table — its changelog, services and data objects, with their keys" '+
    'aria-label="What is behind '+esc(key)+'">i</button>';
}
/** The same table, opened from the list — for a table that is not on the canvas yet. */
function openInfoPop(key, el){
  const c=S.byKey.get(key), r=el.getBoundingClientRect();
  openPop('info', '<div class="erd-pop-h">'+popTitle((c && c.name) || key)+'<span class="erd-grow"></span>'+closeBtn()+'</div>'+infoHtml(key), {x:r.right+8, y:r.top-10});
  els.pop.addEventListener('click', ev=>{
    const cp=ev.target.closest('[data-copy]');
    if(cp){ const k=cp.dataset.copy; atlasCopy(k, ()=>toast('Copied '+k)); return; }
    const op=ev.target.closest('[data-open]');
    if(op && window.__atlasOpen){ window.__atlasOpen(op.dataset.open); return; }
    if(ev.target.closest('[data-pa=close]')) closePop();
  });
}
/** A model key in the table panel, with a button that copies it. */
function keyChip(key){
  if(!key) return '';
  return '<code class="erd-key">'+esc(key)+'</code>'+copyBtn(key, 'Copy the key');
}
function copyBtn(text, tip, cls){
  return '<button type="button" class="erd-cpy'+(cls?' '+cls:'')+'" data-copy="'+esc(text)+'" data-tip="'+esc(tip)+'" aria-label="Copy '+esc(text)+'">'+
    ico('<rect width="14" height="14" x="8" y="8" rx="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>')+'</button>';
}
/**
 * Where a table comes from, as something to follow: inside the IDE the model file opens in the editor; in a
 * browser, the model's page in the explorer written beside this one, when there is one; else just its name.
 */
function srcLink(id, label){
  const n=byId.get(id), file=n && n.file;
  if(window.__atlasOpen && file)
    return '<button type="button" class="erd-open" data-open="'+esc(file)+'" data-tip="Open '+esc(file)+' in the editor">'+esc(label)+'</button>';
  if(DATA.explorer)
    return '<a href="'+esc(DATA.explorer)+'#'+enc(id)+'" target="_blank" rel="noopener" data-tip="Open its page in the Atlas explorer">'+esc(label)+'</a>';
  return '<span'+(file?' data-tip="'+esc(file)+'"':'')+'>'+esc(label)+'</span>';
}
/** The colour field of a table's or a frame's panel: no colour, the eight swatches, any colour. */
function swatchesHtml(color){
  return '<div class="erd-fld"><span>Colour</span><div class="erd-sw">'+
    '<button type="button" class="erd-swatch none'+(color?'':' on')+'" data-color="" data-tip="No colour" aria-label="No colour"></button>'+
    ERD_SWATCHES.map(h=>'<button type="button" class="erd-swatch'+(color===h?' on':'')+'" data-color="'+h+'" style="background:'+h+'" aria-label="Colour '+h+'"></button>').join('')+
    '<label class="erd-swatch custom'+(color&&ERD_SWATCHES.indexOf(color)<0?' on':'')+'" data-tip="Any colour"><input type="color" data-f="color" value="'+(color||'#2f6fed')+'" aria-label="Any colour"></label>'+
  '</div></div>';
}
/** Colour what [find] picks out of the diagram — a table or a frame; [coalesce] folds a run of picks into one undo step. */
function setColor(find, hex, coalesce){
  mutate(d=>{ const x=find(d); if(x) x.color=/^#[0-9a-f]{6}$/i.test(hex||'')?hex.toLowerCase():''; }, coalesce||null);
  els.pop.querySelectorAll('.erd-swatch').forEach(s=>s.classList.toggle('on', (s.dataset.color||'')===(hex||'') ||
    (s.classList.contains('custom') && hex && ERD_SWATCHES.indexOf(hex.toLowerCase())<0)));
}
function renderPopCols(key, focusCol, focusDir){
  const t=active().tables.find(x=>x.key===key), ol=els.pop.querySelector('.erd-cols');
  if(!t || !ol) return;
  const e=effective(t), byK=new Map(e.columns.map(c=>[erdKey(c.name), c]));
  const fq=els.pop.querySelector('.erd-colq'), cq=fq?fq.value.trim().toLowerCase():'';
  const hit=cq?erdColumnHits(e.columns, cq):null;
  ol.innerHTML=e.order.map((n,i)=>{
    const c=byK.get(erdKey(n))||{name:n};
    // filtered, a column still moves within the whole order: ↑ and ↓ are about its place, not the list's
    if(hit && !hit.has(erdKey(c.name))) return '';
    return '<li class="'+(i===VISIBLE-1?'cut':'')+'"><span class="erd-cn">'+(c.pk?'<b class="erd-pk" data-tip="Primary key">PK</b>':'')+esc(c.name)+'</span>'+
      '<span class="erd-ct">'+esc(c.type||'')+'</span>'+
      '<button type="button" data-mv="-1" data-col="'+esc(c.name)+'"'+(i===0?' disabled':'')+' aria-label="Move '+esc(c.name)+' up">↑</button>'+
      '<button type="button" data-mv="1" data-col="'+esc(c.name)+'"'+(i===e.order.length-1?' disabled':'')+' aria-label="Move '+esc(c.name)+' down">↓</button></li>';
  }).join('')||'<li class="erd-cnone">No column matches</li>';
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
  const ends=erdEnds(r.cardinality);
  const opts=(t, cur)=>'<option value="">— the table —</option>'+(t?effective(t).order.map(n=>'<option'+(erdKey(n)===erdKey(cur)?' selected':'')+'>'+esc(n)+'</option>').join(''):'');
  const html='<div class="erd-pop-h"><span class="erd-pop-t">Relation</span>'+closeBtn()+'</div>'+
    '<div class="erd-ends"><b>'+esc(nm(A))+'</b><span>→</span><b>'+esc(nm(B))+'</b>'+
      '<button type="button" class="tbtn" data-pa="swap" data-tip="Swap the direction">⇄</button></div>'+
    '<label class="erd-fld"><span>Name</span><input data-f="label" value="'+esc(r.label)+'" placeholder="e.g. places, belongs to"'+(fresh||!r.label?' autofocus':'')+'></label>'+
    // one row per end, in the order the cardinality is written: how many of this table per row of the other
    '<div class="erd-fld"><span>Cardinality</span>'+endRow(0, nm(A), nm(B), ends[0])+endRow(1, nm(B), nm(A), ends[1])+
    '<div class="erd-say"></div></div>'+
    '<div class="erd-fld"><span>Columns <em>several, for a key over several columns</em></span><div class="erd-pairs"></div>'+
      '<button type="button" class="tbtn erd-addpair" data-pa="addpair" data-tip="Join on one more column — position by position, as a key over several columns does">'+
      '+ Column pair</button></div>'+
    '<div class="erd-pop-a"><button type="button" class="tbtn erd-danger" data-pa="delete">Delete the relation</button></div>';
  const g=els.lays && route(r, els.lays, 0), svgR=els.svg.getBoundingClientRect(), v=view();
  const near=ev&&ev.clientX!=null?{x:ev.clientX+14, y:ev.clientY-20}:g?{x:svgR.left+mid(g).x*v.s+v.tx+14, y:svgR.top+mid(g).y*v.s+v.ty-20}:null;
  openPop('rel', html, near);
  const pop=els.pop;
  const say=()=>{
    const x=active().relations.find(x=>x.id===id); if(!x) return;
    const a=nm(active().tables.find(t=>t.key===x.from)), b=nm(active().tables.find(t=>t.key===x.to));
    const [ea, eb]=erdEnds(x.cardinality), one=erdEndOne;
    const kind=one(ea)?(one(eb)?'One to one':'One to many'):(one(eb)?'Many to one':'Many to many');
    pop.querySelector('.erd-say').textContent=kind+': each '+a+' has '+ERD_END_WORDS[eb]+' '+b+', each '+b+' '+ERD_END_WORDS[ea]+' '+a+'.';
  };
  say();
  pop.querySelector('[data-f=label]').addEventListener('input', e2=>{ const val=e2.target.value; mutate(d=>{ const x=d.relations.find(x=>x.id===id); if(x) x.label=val.slice(0,200); }, 'label:'+id); });
  // The rows being edited, one more than the relation has while an added one is still empty: a relation keeps
  // only the pairs that name a column, and an empty row is only a place to choose one.
  const rows=(r.pairs||[]).map(p=>({from:p.from, to:p.to}));
  if(!rows.length) rows.push({from:'', to:''});
  const savePairs=()=>mutate(d=>{ const x=d.relations.find(x=>x.id===id); if(x) x.pairs=rows.filter(p=>p.from||p.to).map(p=>({from:p.from, to:p.to})); });
  const renderPairs=()=>{
    const x=active().relations.find(x=>x.id===id); if(!x) return;
    const a=active().tables.find(t=>t.key===x.from), b=active().tables.find(t=>t.key===x.to);
    pop.querySelector('.erd-pairs').innerHTML='<div class="erd-pairh"><span>'+esc(nm(a))+'</span><span></span><span>'+esc(nm(b))+'</span><span></span></div>'+
      rows.map((p,i)=>'<div class="erd-pair"><select data-side="from" data-i="'+i+'" aria-label="Column of '+esc(nm(a))+'">'+opts(a, p.from)+'</select>'+
        '<span class="erd-arrow">→</span><select data-side="to" data-i="'+i+'" aria-label="Column of '+esc(nm(b))+'">'+opts(b, p.to)+'</select>'+
        '<button type="button" class="erd-x" data-pa="delpair" data-i="'+i+'" data-tip="Remove this column pair" aria-label="Remove this column pair">×</button></div>').join('');
  };
  renderPairs();
  pop.addEventListener('change', e2=>{
    const sel=e2.target.closest('select[data-side]');
    if(!sel) return;
    rows[+sel.dataset.i][sel.dataset.side]=sel.value;
    savePairs();
  });
  pop.addEventListener('click', e2=>{
    const c=e2.target.closest('[data-end]');
    if(c){
      const i=+c.dataset.end, val=c.dataset.v;
      mutate(d=>{ const x=d.relations.find(x=>x.id===id); if(!x) return; const e=erdEnds(x.cardinality); e[i]=val; x.cardinality=e.join(':'); });
      pop.querySelectorAll('[data-end="'+i+'"]').forEach(b=>b.setAttribute('aria-checked', String(b.dataset.v===val))); say(); return;
    }
    const a=e2.target.closest('[data-pa]');
    if(!a) return;
    if(a.dataset.pa==='delete'){ S.sel={kind:'rel', id}; removeSelected(); }
    else if(a.dataset.pa==='addpair'){
      rows.push({from:'', to:''}); renderPairs();
      const last=pop.querySelectorAll('.erd-pair select[data-side=from]'); if(last.length) last[last.length-1].focus();
    }
    else if(a.dataset.pa==='delpair'){
      rows.splice(+a.dataset.i, 1);
      if(!rows.length) rows.push({from:'', to:''});
      savePairs(); renderPairs();
    }
    else if(a.dataset.pa==='swap'){
      // the relation turned round, read from the other table: each table keeps its own end
      mutate(d=>{ const x=d.relations.find(x=>x.id===id); if(!x) return; [x.from,x.to]=[x.to,x.from]; x.pairs=(x.pairs||[]).map(p=>({from:p.to, to:p.from}));
        x.cardinality=erdEnds(x.cardinality).reverse().join(':'); });
      openRelPop(id, null);
    }
    else if(a.dataset.pa==='close') closePop();
  });
}
/** A frame's panel: its name, its colour, fitting it to what it holds, deleting it (what it holds stays). */
function openFramePop(id, fresh){
  const d=active(), fr=d.frames.find(x=>x.id===id);
  if(!fr) return;
  const n=frameContents(d, id).tables.length;
  const html='<div class="erd-pop-h"><span class="erd-pop-t">Frame</span>'+closeBtn()+'</div>'+
    '<label class="erd-fld"><span>Name</span><input data-f="name" value="'+esc(fr.name)+'" maxlength="120" placeholder="e.g. Sales, Accounting"'+
      (fresh||!fr.name?' autofocus':'')+'></label>'+
    swatchesHtml(fr.color)+
    '<p class="erd-fnote">'+(n?'Holds '+n+' table'+(n===1?'':'s')+' — they move with it.':'Holds no table yet — drag tables into it.')+'</p>'+
    '<div class="erd-pop-a"><button type="button" class="tbtn" data-pa="fit" data-tip="Draw the frame around the tables it holds, with room for its name">'+
      'Fit to its tables</button><span class="erd-grow"></span>'+
      '<button type="button" class="tbtn erd-danger" data-pa="delete" data-tip="The tables it holds stay where they are">Delete the frame</button></div>';
  const r=els.svg.getBoundingClientRect(), v=view();
  openPop('frame', html, {x:r.left+(fr.x+8)*v.s+v.tx, y:r.top+(fr.y+8+FRAME_TAB_H)*v.s+v.ty+8});
  const pop=els.pop, frameOf=d=>d.frames.find(x=>x.id===id);
  pop.querySelector('[data-f=name]').addEventListener('input', ev=>{ const val=ev.target.value; mutate(d=>{ const x=frameOf(d); if(x) x.name=val.slice(0,120); }, 'fname:'+id); });
  pop.querySelector('[data-f=color]').addEventListener('input', ev=>setColor(frameOf, ev.target.value, 'fcolor:'+id));
  pop.addEventListener('click', ev=>{
    const sw=ev.target.closest('[data-color]');
    if(sw){ setColor(frameOf, sw.dataset.color); return; }
    const a=ev.target.closest('[data-pa]');
    if(!a) return;
    if(a.dataset.pa==='fit') fitFrame(id);
    else if(a.dataset.pa==='delete'){ S.sel={kind:'frame', id}; removeSelected(); }
    else if(a.dataset.pa==='close') closePop();
  });
}
/** One end's choice in the relation panel: how many [self] per [other], each count with its crow's foot. */
function endRow(i, self, other, cur){
  return '<div class="erd-endrow"><div class="erd-endlbl"><b>'+esc(self)+'</b> per '+esc(other)+'</div>'+
    '<div class="erd-seg erd-seg-end" role="radiogroup" aria-label="How many '+esc(self)+' per '+esc(other)+'">'+
    ERD_ENDS.map(e=>'<button type="button" role="radio" data-end="'+i+'" data-v="'+e+'" aria-checked="'+(cur===e)+'" '+
      'data-tip="'+esc(ERD_END_WORDS[e].charAt(0).toUpperCase()+ERD_END_WORDS[e].slice(1)+' '+self+' per '+other)+'">'+endGlyph(e)+'<span>'+e+'</span></button>').join('')+
  '</div></div>';
}
/** An end's crow's foot, small, the way the canvas draws it at a card on the right. */
function endGlyph(end){
  const {lines, ring}=endShapes({x:21, y:7}, [-1,0], end, 0.7);
  return '<svg class="erd-glyph" viewBox="0 0 24 14" width="24" height="14" aria-hidden="true"><path d="M1 7H21M21 1v12"/>'+
    lines.map(([a,b])=>'<path d="M'+f(a.x)+','+f(a.y)+' L'+f(b.x)+','+f(b.y)+'"/>').join('')+
    (ring?'<circle cx="'+f(ring.c.x)+'" cy="'+f(ring.c.y)+'" r="'+f(ring.r)+'"/>':'')+'</svg>';
}
function closeBtn(){ return '<button type="button" class="erd-x" data-pa="close" aria-label="Close">×</button>'; }

// ---------- diagrams: switch, new, rename, duplicate, delete, import, export ----------
function switchTo(id){
  if(!S.diagrams.some(d=>d.id===id)) return;
  S.activeId=id; S.undo=[]; S.redo=[]; S.sel=null; S.lastCoalesce=null; S.tool=null;
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
    case 'arrange': arrange(); break;
    case 'frame': setTool(S.tool==='frame'?null:'frame'); break;
    case 'expand-all': setAllExpanded(true); break;
    case 'collapse-all': setAllExpanded(false); break;
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
        duplicate:()=>{ const n=Object.assign(JSON.parse(JSON.stringify({name:d.name, tables:d.tables, relations:d.relations, frames:d.frames||[], dismissed:d.dismissed})),
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
  let b=bounds(lays, 40, d.frames);
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
  if(S.present){ S.sel=null; S.tool=null; }  // a selection outline is an editing aid, not part of what is shown
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
  if(!S || !els || els.empty) return;
  if((e.metaKey||e.ctrlKey) && !e.altKey && (e.key==='f'||e.key==='F')){ e.preventDefault(); focusSearch(); return; }
  if(e.key==='/' && !typing(e.target)){ e.preventDefault(); focusSearch(); return; }
  if(e.key==='Escape'){
    if(S.tool){ setTool(null); e.preventDefault(); }
    else if(S.pop){ closePop(); e.preventDefault(); }
    else if(S.present){ present(false); e.preventDefault(); }
    else if(S.sel && !typing(e.target)){ S.sel=null; draw(); }
    return;
  }
  if(typing(e.target)) return;
  const mod=e.metaKey||e.ctrlKey;
  if(mod && (e.key==='z'||e.key==='Z')){ e.preventDefault(); if(e.shiftKey) redo(); else undo(); }
  else if(mod && (e.key==='y'||e.key==='Y')){ e.preventDefault(); redo(); }
  else if(!mod && (e.key==='Delete'||e.key==='Backspace') && S.sel){ e.preventDefault(); removeSelected(); }
  else if(!mod && !e.altKey && (e.key==='+'||e.key==='=')){ e.preventDefault(); zoomAt(1.25); }
  else if(!mod && !e.altKey && e.key==='-'){ e.preventDefault(); zoomAt(1/1.25); }
  else if(!mod && !e.altKey && e.key==='0'){ e.preventDefault(); fit(); }
  else if(!mod && !e.altKey && (e.key==='f'||e.key==='F') && !S.present){ e.preventDefault(); setTool(S.tool==='frame'?null:'frame'); }
});
document.addEventListener('paste', e=>{
  if(!S || !els || els.empty || typing(e.target)) return;
  const text=e.clipboardData && e.clipboardData.getData('text');
  if(text && /"format"\s*:\s*"atlas-erd"/.test(text)){ e.preventDefault(); importText(text, 'The pasted diagram'); }
});
document.addEventListener('pointerdown', e=>{
  if(els && els.results && !els.results.hidden && !(els.qwrap && els.qwrap.contains(e.target))) els.results.hidden=true;
  if(!S || !S.pop || !els || !els.pop) return;
  if(els.pop.contains(e.target) || e.target.closest && e.target.closest('.erd-menubtn,.erd-svg')) return;
  closePop();
}, true);

// ---------- boot ----------
document.getElementById('erd-proj').textContent=DATA.project;
(function(){
  // provenance, as the explorer's footer says it: a page that has travelled can still say how old it is
  const el=document.getElementById('erd-prov'), t=Date.parse(DATA.generatedAt||'');
  if(!el || isNaN(t)) return;
  const m=Math.round((Date.now()-t)/60000), h=Math.round(m/60), d=Math.round(h/24);
  el.textContent='Atlas '+(DATA.atlasVersion||'')+' · generated '+(m<2?'just now':m<60?m+' min ago':h<48?h+' h ago':d+' days ago');
  el.dataset.tip=new Date(t).toLocaleString();
})();
markIde();
render(document.getElementById('erd-root'));
// For the UI test: the state and the actions a test drives, without a second way in for the page itself.
window.ATLAS_ERD={_test:{state:()=>S, active:()=>S&&active(), fit:()=>fit(), importText:(t)=>importText(t,'test'),
  docOf:()=>docOf(), pictureSvg:()=>pictureSvg(), addTable:(k,p)=>addTable(k,p), arrange:()=>arrange(),
  search:(q)=>setSearch(q), setAllExpanded:(on)=>setAllExpanded(on)}};
})();
