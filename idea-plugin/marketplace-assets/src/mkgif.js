// Self-contained animated-GIF builder: no external deps.
// PNG decode via built-in zlib; global-palette median-cut quantize; hand-written GIF89a + LZW.
const fs = require('fs');
const zlib = require('zlib');

// ---------- PNG decode (8-bit, colorType 2/6, non-interlaced, filter method 0) ----------
function decodePNG(buf) {
  let p = 8; // skip signature
  let w, h, ct, idat = [];
  while (p < buf.length) {
    const len = buf.readUInt32BE(p); const type = buf.toString('ascii', p + 4, p + 8);
    const data = buf.subarray(p + 8, p + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); ct = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    p += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const ch = ct === 6 ? 4 : 3;            // channels in source
  const stride = w * ch;
  const out = Buffer.alloc(w * h * 3);    // we only keep RGB
  const cur = Buffer.alloc(stride), prev = Buffer.alloc(stride);
  let rp = 0;
  const pae = (a, b, c) => { const pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c); return pa <= pb && pa <= pc ? a : pb <= pc ? b : c; };
  for (let y = 0; y < h; y++) {
    const f = raw[rp++];
    for (let i = 0; i < stride; i++) {
      const x = raw[rp++]; const a = i >= ch ? cur[i - ch] : 0; const b = prev[i]; const c = i >= ch ? prev[i - ch] : 0;
      let v; if (f === 0) v = x; else if (f === 1) v = x + a; else if (f === 2) v = x + b; else if (f === 3) v = x + ((a + b) >> 1); else v = x + pae(a, b, c);
      cur[i] = v & 255;
    }
    for (let xp = 0; xp < w; xp++) { out[(y * w + xp) * 3] = cur[xp * ch]; out[(y * w + xp) * 3 + 1] = cur[xp * ch + 1]; out[(y * w + xp) * 3 + 2] = cur[xp * ch + 2]; }
    cur.copy(prev);
  }
  return { w, h, rgb: out };
}

// ---------- 2x2 box downsample ----------
function down2(img) {
  const w = img.w >> 1, h = img.h >> 1, src = img.rgb, out = Buffer.alloc(w * h * 3);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    let r = 0, g = 0, b = 0;
    for (let dy = 0; dy < 2; dy++) for (let dx = 0; dx < 2; dx++) {
      const si = ((y * 2 + dy) * img.w + (x * 2 + dx)) * 3; r += src[si]; g += src[si + 1]; b += src[si + 2];
    }
    const oi = (y * w + x) * 3; out[oi] = (r >> 2); out[oi + 1] = (g >> 2); out[oi + 2] = (b >> 2);
  }
  return { w, h, rgb: out };
}

// ---------- median-cut quantization over combined unique colors ----------
function quantize(frames, maxColors) {
  const counts = new Map();
  for (const f of frames) for (let i = 0; i < f.rgb.length; i += 3) {
    const k = (f.rgb[i] << 16) | (f.rgb[i + 1] << 8) | f.rgb[i + 2];
    counts.set(k, (counts.get(k) || 0) + 1);
  }
  const colors = [...counts.keys()].map(k => ({ r: (k >> 16) & 255, g: (k >> 8) & 255, b: k & 255, k, c: counts.get(k) }));
  let boxes = [colors];
  const rangeOf = (box) => {
    let rmin = 255, rmax = 0, gmin = 255, gmax = 0, bmin = 255, bmax = 0;
    for (const c of box) { if (c.r < rmin) rmin = c.r; if (c.r > rmax) rmax = c.r; if (c.g < gmin) gmin = c.g; if (c.g > gmax) gmax = c.g; if (c.b < bmin) bmin = c.b; if (c.b > bmax) bmax = c.b; }
    return { rr: rmax - rmin, gr: gmax - gmin, br: bmax - bmin };
  };
  while (boxes.length < maxColors) {
    // pick box with largest single-channel range and >1 color
    let bi = -1, best = -1, chan = 0;
    for (let i = 0; i < boxes.length; i++) {
      if (boxes[i].length < 2) continue;
      const rg = rangeOf(boxes[i]); const m = Math.max(rg.rr, rg.gr, rg.br);
      if (m > best) { best = m; bi = i; chan = rg.rr >= rg.gr && rg.rr >= rg.br ? 'r' : rg.gr >= rg.br ? 'g' : 'b'; }
    }
    if (bi < 0) break;
    const box = boxes[bi]; box.sort((a, b) => a[chan] - b[chan]);
    const mid = box.length >> 1;
    boxes.splice(bi, 1, box.slice(0, mid), box.slice(mid));
  }
  const palette = []; const map = new Map();
  for (let i = 0; i < boxes.length; i++) {
    let tr = 0, tg = 0, tb = 0, tc = 0;
    for (const c of boxes[i]) { tr += c.r * c.c; tg += c.g * c.c; tb += c.b * c.c; tc += c.c; }
    palette.push([Math.round(tr / tc), Math.round(tg / tc), Math.round(tb / tc)]);
    for (const c of boxes[i]) map.set(c.k, i);
  }
  return { palette, map };
}

function indexFrame(f, map) {
  const idx = new Uint8Array(f.w * f.h);
  for (let i = 0, j = 0; i < f.rgb.length; i += 3, j++) idx[j] = map.get((f.rgb[i] << 16) | (f.rgb[i + 1] << 8) | f.rgb[i + 2]);
  return idx;
}

// ---------- GIF LZW ----------
function lzw(minCode, indices) {
  const out = []; let cur = 0, curBits = 0;
  const clear = 1 << minCode, end = clear + 1;
  let dict, next, codeSize;
  const reset = () => { dict = new Map(); for (let i = 0; i < clear; i++) dict.set(String.fromCharCode(i), i); next = clear + 2; codeSize = minCode + 1; };
  const emit = (code) => { cur |= code << curBits; curBits += codeSize; while (curBits >= 8) { out.push(cur & 255); cur >>= 8; curBits -= 8; } };
  reset(); emit(clear);
  let w = String.fromCharCode(indices[0]);
  for (let i = 1; i < indices.length; i++) {
    const c = String.fromCharCode(indices[i]); const wc = w + c;
    if (dict.has(wc)) { w = wc; }
    else {
      emit(dict.get(w));
      if (next < 4096) { dict.set(wc, next++); if (next - 1 === (1 << codeSize) && codeSize < 12) codeSize++; }
      else { emit(clear); reset(); }
      w = c;
    }
  }
  emit(dict.get(w)); emit(end);
  if (curBits > 0) out.push(cur & 255);
  return out;
}

function subBlocks(bytes) {
  const out = []; for (let i = 0; i < bytes.length; i += 255) { const chunk = bytes.slice(i, i + 255); out.push(chunk.length, ...chunk); } out.push(0); return out;
}

// ---------- assemble GIF ----------
function buildGIF(w, h, palette, frames /* [{idx,delay}] */) {
  let tableSize = 2; while (tableSize < palette.length) tableSize <<= 1;
  const sizeBits = Math.log2(tableSize) - 1;
  const B = [];
  const push = (...xs) => B.push(...xs);
  const u16 = (v) => B.push(v & 255, (v >> 8) & 255);
  push(...[0x47, 0x49, 0x46, 0x38, 0x39, 0x61]); // GIF89a
  u16(w); u16(h);
  push(0x80 | (7 << 4) | sizeBits, 0, 0); // packed(GCT,colorRes,size), bg, aspect
  for (let i = 0; i < tableSize; i++) { const c = palette[i] || [0, 0, 0]; push(c[0], c[1], c[2]); }
  // loop forever
  push(0x21, 0xFF, 0x0B); push(...Buffer.from('NETSCAPE2.0', 'ascii')); push(0x03, 0x01, 0, 0, 0x00);
  const minCode = Math.max(2, Math.ceil(Math.log2(tableSize)));
  for (const fr of frames) {
    push(0x21, 0xF9, 0x04, 0x04); u16(fr.delay); push(0, 0x00); // GCE: disposal=1(do not dispose), delay, no transparency
    push(0x2C); u16(0); u16(0); u16(w); u16(h); push(0x00); // image descriptor, no local table
    push(minCode); push(...subBlocks(lzw(minCode, fr.idx)));
  }
  push(0x3B);
  return Buffer.from(B);
}

// ---------- main ----------
const specs = [
  { file: 'frame0.png', delay: 70 },
  { file: 'frame1.png', delay: 110 },
  { file: 'frame3.png', delay: 130 },
  { file: 'frame5.png', delay: 130 },
];
const imgs = specs.map(s => down2(decodePNG(fs.readFileSync(s.file))));
const { palette, map } = quantize(imgs, 256);
const frames = imgs.map((f, i) => ({ idx: indexFrame(f, map), delay: specs[i].delay }));
const gif = buildGIF(imgs[0].w, imgs[0].h, palette, frames);
fs.writeFileSync('flowable-keys-demo.gif', gif);
console.log('wrote flowable-keys-demo.gif', imgs[0].w + 'x' + imgs[0].h, gif.length, 'bytes,', palette.length, 'colors');
