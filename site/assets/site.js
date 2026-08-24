/* ---------------------------------------------------------------------------
   Flowable Atlas documentation site — the small amount of behaviour the pages
   need. No dependencies, no build step: this file ships as authored.

   Four jobs: the theme cycle, the mobile nav drawer, the ⌘K search palette
   (over the index scripts/site-build.mjs writes), and the two things that
   cannot be expressed in CSS — table-of-contents scroll-spy and scaling a
   fixed-size IDE mockup down to fit its column.
   --------------------------------------------------------------------------- */
'use strict';


/* ---------- theme: light -> dark -> auto, remembered ---------- */
(function theme() {
  const KEY = 'atlas-site-theme';
  const btn = document.querySelector('.themebtn');
  if (!btn) return;
  const GLYPH = { light: '☀', dark: '☾', auto: '◐' };
  const read = () => { try { return localStorage.getItem(KEY) || 'auto'; } catch { return 'auto'; } };

  function apply(pref) {
    const dark = pref === 'dark' ||
      (pref === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    btn.textContent = GLYPH[pref];
    btn.title = 'Theme: ' + pref;
  }
  apply(read());
  btn.addEventListener('click', () => {
    const next = { light: 'dark', dark: 'auto', auto: 'light' }[read()];
    try { localStorage.setItem(KEY, next); } catch {}
    apply(next);
  });
  // Following the system means following it live, not only at load.
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (read() === 'auto') apply('auto');
  });
})();

/* ---------- mobile nav drawer ---------- */
(function nav() {
  const btn = document.querySelector('.navtoggle');
  const bar = document.querySelector('.sidebar');
  if (!btn || !bar) return;
  const set = (open) => {
    bar.classList.toggle('open', open);
    btn.setAttribute('aria-expanded', String(open));
  };
  btn.addEventListener('click', () => set(!bar.classList.contains('open')));
  bar.addEventListener('click', (e) => { if (e.target.closest('a')) set(false); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') set(false); });
  document.addEventListener('click', (e) => {
    if (bar.classList.contains('open') && !e.target.closest('.sidebar,.navtoggle')) set(false);
  });
})();

/* ---------- table-of-contents scroll-spy ---------- */
(function spy() {
  const links = [...document.querySelectorAll('.toc a')];
  if (!links.length) return;
  const targets = links
    .map((a) => ({ a, el: document.getElementById(decodeURIComponent(a.hash.slice(1))) }))
    .filter((t) => t.el);
  if (!targets.length) return;

  let active = null;
  const mark = (t) => {
    if (t === active) return;
    if (active) active.a.classList.remove('on');
    if (t) t.a.classList.add('on');
    active = t;
  };
  const onScroll = () => {
    const top = parseInt(getComputedStyle(document.documentElement)
      .getPropertyValue('--topbar-h')) + 24;
    let current = targets[0];
    for (const t of targets) {
      if (t.el.getBoundingClientRect().top - top <= 0) current = t; else break;
    }
    // At the very bottom the last heading wins, even if it never crossed the line.
    if (innerHeight + scrollY >= document.body.scrollHeight - 4) current = targets[targets.length - 1];
    mark(current);
  };
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();
})();

/* ---------- scale fixed-size IDE mockups to the column ---------- */
(function mockups() {
  const fit = () => {
    for (const box of document.querySelectorAll('.mockfit')) {
      const w = Number(box.dataset.w) || box.scrollWidth;
      const h = Number(box.dataset.h) || box.scrollHeight;
      const pad = parseFloat(getComputedStyle(box.parentElement).paddingLeft) || 0;
      const avail = box.parentElement.clientWidth - pad * 2;
      const k = Math.min(1, avail / w);
      box.style.transform = k < 1 ? `scale(${k})` : '';
      // A mockup narrower than the column is centred; a wider one scales to fill it exactly.
      box.style.marginLeft = k === 1 ? Math.max(0, (avail - w) / 2) + 'px' : '0';
      // The scaled node still occupies its unscaled box, so reserve the real height.
      box.parentElement.style.height = Math.ceil(h * k + pad * 2) + 'px';
    }
  };
  addEventListener('resize', fit);
  addEventListener('load', fit);
  fit();
})();

/* ---------- ⌘K search palette ---------- */
(function search() {
  const pal = document.querySelector('.palette');
  const input = pal && pal.querySelector('input');
  const out = pal && pal.querySelector('.palresults');
  const open = document.querySelector('.searchbtn');
  if (!pal || !input || !out) return;

  let index = null, loading = null, sel = 0, hits = [];

  /* Both the index and every result URL are resolved against the same relative prefix the page
     already uses for its stylesheet, so the palette works from disk and from the deployed host. */
  const basePrefix = () =>
    document.querySelector('link[href$="site.css"]').getAttribute('href').replace(/assets\/site\.css$/, '');
  const indexUrl = () => basePrefix() + 'assets/search.json';
  const hrefOf = (s) => basePrefix() + (s.slug ? s.slug + '/' : '') + (s.anchor ? '#' + s.anchor : '');
  function load() {
    if (index || loading) return loading || Promise.resolve();
    loading = fetch(indexUrl())
      .then((r) => r.json())
      .then((j) => { index = j; })
      .catch(() => { index = []; });
    return loading;
  }

  const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const norm = (s) => s.toLowerCase();

  function highlight(text, terms) {
    let html = esc(text);
    for (const t of terms) {
      if (t.length < 2) continue;
      html = html.replace(new RegExp('(' + t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'ig'),
        '<mark>$1</mark>');
    }
    return html;
  }

  /* Every term must appear somewhere in the section (AND), order-independent —
     the same rule the explorer's palette uses, so the two feel alike. */
  function run(q) {
    const terms = norm(q).split(/[\s,._/-]+/).filter(Boolean);
    if (!terms.length || !index) return [];
    const scored = [];
    for (const s of index) {
      const hay = norm(s.title + ' ' + s.page + ' ' + s.text);
      if (!terms.every((t) => hay.includes(t))) continue;
      let score = 0;
      for (const t of terms) {
        const ti = norm(s.title).indexOf(t);
        if (ti === 0) score += 400; else if (ti > 0) score += 260;
        if (norm(s.page).includes(t)) score += 60;
        if (norm(s.text).includes(t)) score += 40;
      }
      if (s.level === 1) score += 120;          // a page's own H1 outranks its sections
      scored.push({ s, score });
    }
    return scored.sort((a, b) => b.score - a.score || a.s.title.localeCompare(b.s.title))
      .slice(0, 40).map((x) => x.s);
  }

  function render(q) {
    hits = run(q);
    sel = 0;
    if (!q.trim()) { out.innerHTML = '<div class="palempty">Type to search every page.</div>'; return; }
    if (!hits.length) { out.innerHTML = '<div class="palempty">Nothing matches “' + esc(q) + '”.</div>'; return; }
    const terms = norm(q).split(/[\s,._/-]+/).filter(Boolean);
    out.innerHTML = hits.map((h, i) =>
      '<a href="' + esc(hrefOf(h)) + '" class="' + (i === 0 ? 'on' : '') + '">' +
      '<span class="rt">' + highlight(h.title, terms) + '</span>' +
      '<span class="rp">' + esc(h.page) + '</span>' +
      '<span class="rx">' + highlight(h.text.slice(0, 190), terms) + '</span></a>').join('');
  }

  function move(d) {
    const rows = [...out.querySelectorAll('a')];
    if (!rows.length) return;
    rows[sel] && rows[sel].classList.remove('on');
    sel = (sel + d + rows.length) % rows.length;
    rows[sel].classList.add('on');
    rows[sel].scrollIntoView({ block: 'nearest' });
  }

  function show() {
    pal.hidden = false;
    load().then(() => render(input.value));
    input.focus();
    input.select();
  }
  const hide = () => { pal.hidden = true; };

  open && open.addEventListener('click', show);
  input.addEventListener('input', () => render(input.value));
  pal.addEventListener('click', (e) => { if (!e.target.closest('.palbox')) hide(); });

  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); move(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
    else if (e.key === 'Enter') {
      const row = out.querySelectorAll('a')[sel];
      if (row) location.href = row.getAttribute('href');
    } else if (e.key === 'Escape') hide();
  });

  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); pal.hidden ? show() : hide(); }
    else if (e.key === '/' && pal.hidden && !/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName)) {
      e.preventDefault(); show();
    }
  });
})();
