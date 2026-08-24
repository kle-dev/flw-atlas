#!/usr/bin/env node
/**
 * Builds the Flowable Atlas documentation site from `site/` into `build/site/`.
 *
 * Why hand-rolled: the repo is JVM-only and deliberately dependency-free, and Atlas already owns a
 * design system in core/src/main/resources/frontend/explorer.css. Adding a Python or npm toolchain to
 * get a site that looks like every other generated site is a bad trade. Same bet as
 * scripts/mkgif.js (a dependency-free GIF encoder): small, readable, and ours.
 *
 * The Markdown support is a STRICT SUBSET and the renderer FAILS on anything outside it rather than
 * emitting something subtly wrong — a docs site that silently mis-renders is worse than one that
 * refuses to build. Supported: ATX headings (1-4), paragraphs, `-`/`*` and `1.` lists (one nesting
 * level), fenced code, tables with a `|---|` separator row, blockquotes, `---` rules, and raw-HTML
 * blocks (a line starting with `<` at column 0, running to the next blank line). Inline: `code`,
 * **bold**, *italic*, [links](url), ![images](src) and the raw tags listed in RAW_INLINE_TAGS.
 *
 * Page sources may also use {{mockup:<name>}}, which inlines site/mockups/<name>.html.
 *
 * Usage:  node scripts/site-build.mjs [--out <dir>] [--demo <dir>] [--serve] [--strict]
 *         --strict turns a missing generated screenshot from a warning into a failure (CI uses it)
 *         (--serve only prints the file:// URL — the output needs no server)
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const SITE = path.join(ROOT, 'site');
const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const OUT = path.resolve(argOf('--out', path.join(ROOT, 'build/site')));

/** The raw inline tags a page may use for typography. Anything else stays escaped. */
const RAW_INLINE_TAGS = /&lt;(\/?)(kbd|b|i|em|strong|br|sup|sub)&gt;/g;
/** Placeholder for an extracted code span. Chosen so no page could contain it by accident. */
const CODE_MARK = (n) => `⦙CODE${n}⦙`;

const STRICT = args.includes('--strict');
const problems = [];
const warnings = [];
const fail = (where, msg) => problems.push(`${where}: ${msg}`);

const read = (p) => fs.readFileSync(p, 'utf8');
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const escAttr = (s) => esc(s).replace(/"/g, '&quot;');

/** GitHub-style heading slug, so the anchor a human guesses is the anchor that exists. */
const slugify = (text) => text.toLowerCase()
  .replace(/<[^>]+>/g, '').replace(/[`*]/g, '')
  .replace(/[^\w\- ]+/g, '').trim().replace(/\s+/g, '-');

/* --------------------------------------------------------------- inline markdown */

function inline(src, where) {
  const spans = [];
  let s = src.replace(/`([^`]+)`/g, (_, code) => {
    spans.push(`<code>${esc(code)}</code>`);
    return CODE_MARK(spans.length - 1);
  });

  s = esc(s);
  s = s.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_, alt, url) =>
    `<img src="${escAttr(url)}" alt="${escAttr(alt)}" loading="lazy">`);
  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, text, href) => {
    const external = /^(https?:)?\/\//.test(href);
    return `<a href="${escAttr(href)}"${external ? ' target="_blank" rel="noopener"' : ''}>${text}</a>`;
  });
  s = s.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>');
  s = s.replace(/(^|[\s(—])\*([^*\n]+)\*/g, '$1<i>$2</i>');
  s = s.replace(RAW_INLINE_TAGS, '<$1$2>');

  if (s.includes('**')) fail(where, `unbalanced ** in: ${src.slice(0, 60)}`);
  if (/&lt;\/?[a-z]/.test(s)) fail(where, `raw tag that is not in the allowed set: ${src.slice(0, 60)}`);
  return s.replace(/⦙CODE(\d+)⦙/g, (_, n) => spans[Number(n)]);
}

/* ---------------------------------------------------------------- block markdown */

function renderMarkdown(md, where) {
  const lines = md.replace(/\r\n?/g, '\n').split('\n');
  const out = [];
  const headings = [];
  let i = 0;

  /** One list, `-`/`*` or `1.`, with at most one nested level (two-space indent). */
  function list(kind) {
    const re = kind === 'ul' ? /^(\s*)[-*] (.*)$/ : /^(\s*)\d+\. (.*)$/;
    const items = [];
    while (i < lines.length) {
      const m = lines[i].match(re);
      if (m) {
        if (m[1].length >= 2 && items.length) items[items.length - 1].sub.push(lines[i].slice(2));
        else items.push({ text: m[2], sub: [] });
        i++;
        continue;
      }
      // A continuation line: indented, not blank, and not the start of another block.
      if (items.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*(\d+\.|[-*]) /.test(lines[i])) {
        const last = items[items.length - 1];
        if (last.sub.length) last.sub.push(lines[i].trim());
        else last.text += ' ' + lines[i].trim();
        i++;
        continue;
      }
      break;
    }
    const body = items.map((it) => {
      const sub = it.sub.length ? renderMarkdown(it.sub.join('\n'), where).html : '';
      return `<li>${inline(it.text, where)}${sub}</li>`;
    }).join('\n');
    return `<${kind}>\n${body}\n</${kind}>`;
  }

  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }

    if (line.startsWith('<')) {                                  // raw HTML block
      const buf = [];
      while (i < lines.length && lines[i].trim()) { buf.push(lines[i]); i++; }
      const block = buf.join('\n');
      // Drop HTML comments: CHANGELOG.md carries maintainer notes that are not for readers.
      if (!/^<!--/.test(block)) out.push(block);
      continue;
    }

    if (line.startsWith('```')) {                                // fenced code
      const lang = line.slice(3).trim();
      const buf = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) { buf.push(lines[i]); i++; }
      if (i >= lines.length) { fail(where, 'unterminated ``` code fence'); break; }
      i++;
      out.push(`<pre><code${lang ? ` class="lang-${escAttr(lang)}"` : ''}>${esc(buf.join('\n'))}</code></pre>`);
      continue;
    }

    const h = line.match(/^(#{1,4}) (.*)$/);                     // heading
    if (h) {
      const level = h[1].length;
      const id = slugify(h[2]);
      headings.push({ level, text: inline(h[2], where), id, raw: h[2] });
      const anchor = level > 1 ? `<a class="anchor" href="#${id}" aria-label="Permalink">#</a>` : '';
      out.push(`<h${level} id="${id}">${inline(h[2], where)}${anchor}</h${level}>`);
      i++;
      continue;
    }

    // table: header row + |---| separator
    if (line.startsWith('|') && i + 1 < lines.length && /^\|[\s:|-]+\|$/.test(lines[i + 1])) {
      // Split on unescaped pipes only, so a cell can contain a literal one as `\|` — which the
      // frontend dialect's `|>` operator needs.
      const cells = (r) => r.replace(/^\||\|$/g, '')
        .split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, '|'));
      const head = cells(line);
      i += 2;
      const rows = [];
      while (i < lines.length && lines[i].startsWith('|')) { rows.push(cells(lines[i])); i++; }
      const th = head.map((c) => `<th>${inline(c, where)}</th>`).join('');
      const tb = rows.map((r) => {
        if (r.length !== head.length) {
          fail(where, `table row has ${r.length} cells but the header has ${head.length}: ${r[0]}`);
        }
        return `<tr>${r.map((c) => `<td>${inline(c, where)}</td>`).join('')}</tr>`;
      }).join('\n');
      out.push(`<div class="tablewrap"><table>\n<thead><tr>${th}</tr></thead>\n<tbody>\n${tb}\n</tbody>\n</table></div>`);
      continue;
    }

    if (line.startsWith('> ')) {                                 // blockquote
      const buf = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) { buf.push(lines[i].replace(/^>\s?/, '')); i++; }
      out.push(`<blockquote>${renderMarkdown(buf.join('\n'), where).html}</blockquote>`);
      continue;
    }

    if (/^---+$/.test(line)) { out.push('<hr>'); i++; continue; }
    if (/^\s*[-*] /.test(line)) { out.push(list('ul')); continue; }
    if (/^\s*\d+\. /.test(line)) { out.push(list('ol')); continue; }

    const buf = [];                                              // paragraph
    while (i < lines.length && lines[i].trim() &&
           !/^(#{1,4} |```|\||>\s|---+$|\s*[-*] |\s*\d+\. |<)/.test(lines[i])) {
      buf.push(lines[i].trim());
      i++;
    }
    if (buf.length) out.push(`<p>${inline(buf.join(' '), where)}</p>`);
    else { fail(where, `cannot parse line: ${line.slice(0, 70)}`); i++; }
  }

  return { html: out.join('\n'), headings };
}

/* ---------------------------------------------------------------------- inputs */

const nav = JSON.parse(read(path.join(SITE, 'nav.json')));

/* The version is read from the build, never from nav.json: a docs site that claims a version the
   project does not have is worse than one that does not mention a version at all. Pages write
   {{VERSION}} and get whatever `./gradlew` would produce. */
const buildKts = read(path.join(ROOT, 'build.gradle.kts'));
const versionMatch = buildKts.match(/^\s*version = "([^"]+)"/m);
if (!versionMatch) fail('build.gradle.kts', 'cannot find the project version — the site would state a stale one');
const VERSION = versionMatch ? versionMatch[1] : '0.0.0';

const layout = read(path.join(SITE, 'layout.html'));
const pages = nav.groups.flatMap((g) => g.pages.map((p) => ({ ...p, group: g.title })));

const depthOf = (slug) => (slug === '' ? 0 : slug.split('/').length);
const baseOf = (slug) => '../'.repeat(depthOf(slug));
const linkTo = (from, slug) => (slug === '' ? baseOf(from) || './' : baseOf(from) + slug + '/');

const navHtml = (current) => nav.groups.map((g) => {
  const links = g.pages.map((p) => {
    const cur = p.slug === current ? ' aria-current="page"' : '';
    return `      <a href="${linkTo(current, p.slug)}"${cur}>${esc(p.nav || p.title)}</a>`;
  }).join('\n');
  return `    <div class="navgroup">\n      <div class="gt">${esc(g.title)}</div>\n${links}\n    </div>`;
}).join('\n');

function tocHtml(headings) {
  const items = headings.filter((h) => h.level === 2 || h.level === 3);
  if (items.length < 2) return '';
  const links = items.map((h) => `      <a class="h${h.level}" href="#${h.id}">${h.text}</a>`).join('\n');
  return `    <div class="gt">On this page</div>\n${links}`;
}

function pagerHtml(idx) {
  const here = pages[idx].slug;
  const cell = (p, dir) => (p
    ? `<a class="${dir}" href="${linkTo(here, p.slug)}">` +
      `<span class="dir">${dir === 'prev' ? 'Previous' : 'Next'}</span>` +
      `<span class="ttl">${esc(p.title)}</span></a>`
    : '<span style="flex:1"></span>');
  if (!pages[idx - 1] && !pages[idx + 1]) return '';
  return `<nav class="pager">${cell(pages[idx - 1], 'prev')}${cell(pages[idx + 1], 'next')}</nav>`;
}

/**
 * {{mockup:name}} -> the fragment in site/mockups/name.html.
 *
 * Deliberately applied to the RENDERED HTML, not to the Markdown source: a mockup is a multi-line
 * HTML tree with blank lines in it, and feeding that through the block parser makes the parser try to
 * read `<div class="pglbl">…` as a paragraph. Keeping the token intact until after rendering means a
 * page writes one tidy line and the mockup's own markup is never reinterpreted.
 */
function inlineMockups(html, where) {
  return html.replace(/\{\{mockup:([\w-]+)\}\}/g, (_, name) => {
    const file = path.join(SITE, 'mockups', name + '.html');
    if (!fs.existsSync(file)) { fail(where, `unknown mockup "${name}" (no site/mockups/${name}.html)`); return ''; }
    return read(file).trim();
  });
}

/* ---------------------------------------------------------------------- output */

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const searchIndex = [];

for (let idx = 0; idx < pages.length; idx++) {
  const page = pages[idx];
  const srcPath = page.source ? path.join(ROOT, page.source) : path.join(SITE, 'pages', page.file + '.md');
  if (!fs.existsSync(srcPath)) {
    fail(page.file, `missing source ${path.relative(ROOT, srcPath)}`);
    continue;
  }
  const where = path.relative(ROOT, srcPath);
  const md = read(srcPath).replace(/\{\{VERSION\}\}/g, VERSION);
  const rendered = renderMarkdown(md, where);
  const html = inlineMockups(rendered.html, where);
  const headings = rendered.headings;

  // The first plain paragraph is the meta description.
  const lede = (md.match(/^(?![#>|]|```|[-*] |<|\d+\. )(\S.*)$/m) || ['', page.title])[1]
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/[*`]/g, '').trim();

  const out = layout
    .replace(/\{\{TITLE\}\}/g, escAttr(page.slug === ''
      ? `${nav.site.title} — documentation`
      : `${page.title} · ${nav.site.title}`))
    .replace(/\{\{DESCRIPTION\}\}/g, escAttr(lede.slice(0, 180)))
    .replace(/\{\{SITE_URL\}\}/g, nav.site.url)
    .replace(/\{\{BASE\}\}/g, baseOf(page.slug))
    .replace(/\{\{REPO\}\}/g, nav.site.repo)
    .replace(/\{\{VERSION\}\}/g, VERSION)
    .replace(/\{\{NAV\}\}/g, navHtml(page.slug))
    .replace(/\{\{TOC\}\}/g, tocHtml(headings))
    .replace(/\{\{PAGER\}\}/g, pagerHtml(idx))
    .replace(/\{\{CONTENT\}\}/g, html);

  const dir = page.slug === '' ? OUT : path.join(OUT, page.slug);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'index.html'), out);

  // One search entry per heading, carrying the prose beneath it. The palette resolves `slug` +
  // `anchor` against its own base, so the index works from disk and from the deployed host alike.
  for (const sec of md.split(/^(?=#{1,3} )/m)) {
    const h = sec.match(/^(#{1,3}) (.*)$/m);
    if (!h) continue;
    const text = sec.slice(h[0].length)
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/^\|.*$/gm, ' ')
      .replace(/<[^>]*>/g, ' ')
      .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
      .replace(/[*`>#|]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    searchIndex.push({
      title: h[2].replace(/[*`]/g, ''),
      page: page.title,
      slug: page.slug,
      anchor: h[1].length === 1 ? '' : slugify(h[2]),
      level: h[1].length,
      text: text.slice(0, 400),
    });
  }
}

/* ---------------------------------------------------------------------- assets */

const assetsOut = path.join(OUT, 'assets');
function copyTree(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    if (e.isDirectory()) copyTree(path.join(from, e.name), path.join(to, e.name));
    else fs.copyFileSync(path.join(from, e.name), path.join(to, e.name));
  }
}
copyTree(path.join(SITE, 'assets'), assetsOut);
if (!fs.existsSync(path.join(SITE, 'assets/favicon.svg'))) fail('site/assets', 'favicon.svg is missing');

/* Splice the product's own embedded Geist faces onto the site stylesheet: the docs then use the
   exact font the explorer does, with no third-party request. Source: scripts/embed-geist.mjs. */
const explorerCss = read(path.join(ROOT, 'core/src/main/resources/frontend/explorer.css'));
const geist = explorerCss.match(/\/\* __GEIST_EMBED_BEGIN__[\s\S]*?__GEIST_EMBED_END__ \*\//);
if (!geist) fail('explorer.css', 'the __GEIST_EMBED_BEGIN__/END__ block is gone — site typography would silently fall back to system fonts');
else fs.appendFileSync(path.join(assetsOut, 'site.css'), '\n\n' + geist[0] + '\n');

fs.writeFileSync(path.join(assetsOut, 'search.json'), JSON.stringify(searchIndex));

/* ------------------------------------------------------------------- live demo */
/* The demo is the real product, not a copy of it: these are the artifacts the CLI wrote for
   site/flowable-demo. Pages link and iframe them, so a reader clicks through the actual explorer.
   Produced by `./gradlew siteDemo`; absent on a plain content build, which only warns. */
const DEMO_IN = path.resolve(argOf('--demo', path.join(ROOT, 'build/site-demo')));
if (fs.existsSync(DEMO_IN)) {
  const demoOut = path.join(OUT, 'demo');
  fs.mkdirSync(demoOut, { recursive: true });
  const friendly = {
    '.explorer.html': 'explorer.html', '.summary.md': 'summary.md', '.overview.md': 'overview.md',
    '.graph.json': 'graph.json', '.CLAUDE.md': 'CLAUDE.md',
  };
  for (const f of fs.readdirSync(DEMO_IN)) {
    const from = path.join(DEMO_IN, f);
    if (fs.statSync(from).isDirectory()) { copyTree(from, path.join(demoOut, f)); continue; }
    const suffix = Object.keys(friendly).find((sfx) => f.endsWith(sfx));
    fs.copyFileSync(from, path.join(demoOut, suffix ? friendly[suffix] : f));
  }
  console.log(`site-build: live demo copied from ${path.relative(ROOT, DEMO_IN)}`);
}

/* GitHub Pages would otherwise run Jekyll over this and drop paths it considers private. */
fs.writeFileSync(path.join(OUT, '.nojekyll'), '');

const indexHtml = path.join(OUT, 'index.html');
if (fs.existsSync(indexHtml)) {
  fs.writeFileSync(path.join(OUT, '404.html'), read(indexHtml).replace(
    /<main id="main" class="content">[\s\S]*?<\/main>/,
    '<main id="main" class="content"><h1>Page not found</h1>' +
    `<p>That page does not exist. Start from the <a href="${nav.site.url}/">documentation home</a>.</p></main>`));
}

/* --------------------------------------------------------------- version check */
/* A literal version in a page is how a docs site starts lying. Pages must write {{VERSION}}. */
for (const page of pages) {
  if (page.source) continue;                       // CHANGELOG.md is a list of versions by definition
  const src = read(path.join(SITE, 'pages', page.file + '.md'));
  for (const m of src.matchAll(/\b\d+\.\d+\.\d+\b/g)) {
    if (m[0] === VERSION) fail(page.file, `hardcoded version "${m[0]}" — write {{VERSION}} instead`);
  }
}

/* ------------------------------------------------------------------ link check */
/* Every internal link and image must resolve to something this build wrote. Docs whose links rot are
   worse than no docs, and this is the cheapest place to catch it. */
for (const page of pages) {
  const file = path.join(OUT, page.slug, 'index.html');
  if (!fs.existsSync(file)) continue;
  for (const m of read(file).matchAll(/(?:href|src)="([^"]+)"/g)) {
    const href = m[1];
    if (/^(https?:|mailto:|#|data:|\/)/.test(href)) continue;
    const target = path.resolve(path.join(OUT, page.slug), href.replace(/[#?].*$/, ''));
    if (!fs.existsSync(target) && !fs.existsSync(path.join(target, 'index.html'))) {
      // Two things are produced by the build pipeline rather than committed: the screenshots
      // (scripts/site-shots.mjs) and the live demo artifacts (the CLI, over site/flowable-demo).
      // Nothing binary or generated belongs in git — the repo's *.png rule exists to keep customer
      // screenshots out. Their absence must not block local content work, but must never reach a
      // deploy: hence --strict, which the Pages workflow passes.
      const generated = /assets\/img\/.+\.png$/.test(href) || /(^|\/)demo\//.test(href);
      if (generated && !STRICT) {
        warnings.push(`${page.file}: "${href}" is not built yet — run scripts/site-shots.mjs ` +
          '(screenshots) or the demo step of .github/workflows/pages.yml (live demo)');
      }
      else fail(page.file, `dead link "${href}" → ${path.relative(OUT, target)} does not exist`);
    }
  }
}

/* ---------------------------------------------------------------------- report */

if (problems.length) {
  console.error('site-build: FAILED\n');
  for (const p of problems) console.error('  • ' + p);
  console.error(`\n${problems.length} problem(s). Nothing was published.`);
  process.exit(1);
}

for (const w of warnings) console.warn('site-build: warning — ' + w);

const countFiles = (dir) => fs.readdirSync(dir, { withFileTypes: true })
  .reduce((n, e) => n + (e.isDirectory() ? countFiles(path.join(dir, e.name)) : 1), 0);
console.log(`site-build: ${pages.length} pages · ${searchIndex.length} search entries · ` +
  `${countFiles(OUT)} files → ${path.relative(ROOT, OUT)}`);
if (args.includes('--serve')) console.log(`open file://${indexHtml}`);
