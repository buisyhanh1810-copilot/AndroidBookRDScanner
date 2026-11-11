/* BookCore – thuật toán tách trang sách thuần JavaScript (không cần thư viện ngoài).
   Ảnh vào: {w,h,data:Uint8ClampedArray RGBA}. Giả định camera ngang, sách mở nằm ngang. */
const BookCore = (function () {
'use strict';
const M = Math;
const odd = n => { n = M.trunc(n); return n % 2 === 1 ? n : n + 1; };
const clip = (v, a, b) => v < a ? a : (v > b ? b : v);
function pct(arr, p) {
  const s = Float64Array.from(arr).sort();
  if (!s.length) return 0;
  const idx = p / 100 * (s.length - 1), lo = M.floor(idx), hi = M.min(lo + 1, s.length - 1);
  return s[lo] + (s[hi] - s[lo]) * (idx - lo);
}
const median = a => pct(a, 50);
const V = (x, y) => ({ x, y });
const vadd = (a, b) => V(a.x + b.x, a.y + b.y), vsub = (a, b) => V(a.x - b.x, a.y - b.y);
const vmul = (a, k) => V(a.x * k, a.y * k), vneg = a => V(-a.x, -a.y);
const dot = (a, b) => a.x * b.x + a.y * b.y, vlen = a => M.hypot(a.x, a.y);
const Ln = (p, d, o) => ({ p, d, o });

// ------------------------------------------------------------ ảnh cơ bản
function areaWeights(sn, dn) {
  const sc = sn / dn, st = new Int32Array(dn), wt = [];
  for (let d = 0; d < dn; d++) {
    const a = d * sc, b = (d + 1) * sc, i0 = M.floor(a), i1 = M.min(sn - 1, M.ceil(b) - 1), ws = []; let sum = 0;
    for (let i = i0; i <= i1; i++) { const w = M.min(b, i + 1) - M.max(a, i); ws.push(w); sum += w; }
    st[d] = i0; wt.push(ws.map(w => w / sum));
  }
  return { st, wt };
}
/** Thu nhỏ theo diện tích. src: mảng interleaved ch kênh. Trả Float32Array. */
function resizeArea(src, sw, sh, nw, nh, ch) {
  const hx = areaWeights(sw, nw), vy = areaWeights(sh, nh);
  const tmp = new Float32Array(sh * nw * ch);
  for (let y = 0; y < sh; y++) for (let x = 0; x < nw; x++) {
    const i0 = hx.st[x], ws = hx.wt[x];
    for (let c = 0; c < ch; c++) { let s = 0; for (let k = 0; k < ws.length; k++) s += src[(y * sw + i0 + k) * ch + c] * ws[k]; tmp[(y * nw + x) * ch + c] = s; }
  }
  const out = new Float32Array(nh * nw * ch);
  for (let y = 0; y < nh; y++) { const j0 = vy.st[y], ws = vy.wt[y];
    for (let x = 0; x < nw; x++) for (let c = 0; c < ch; c++) { let s = 0; for (let k = 0; k < ws.length; k++) s += tmp[((j0 + k) * nw + x) * ch + c] * ws[k]; out[(y * nw + x) * ch + c] = s; } }
  return out;
}
const LIN = new Float64Array(256).map((_, i) => { const c = i / 255; return c <= 0.04045 ? c / 12.92 : M.pow((c + 0.055) / 1.055, 2.4); });
function linf(c) { c /= 255; return c <= 0.04045 ? c / 12.92 : M.pow((c + 0.055) / 1.055, 2.4); }
const fLab = t => t > 0.008856 ? M.cbrt(t) : 7.787 * t + 16 / 116;
/** RGBA (Uint8/Float) -> {w,h,L8:Uint8Array,A:Float32Array,B:Float32Array} (a,b đã trừ 128). */
function toLab(rgba, w, h) {
  const L8 = new Uint8Array(w * h), A = new Float32Array(w * h), B = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) {
    const R = linf(rgba[i * 4]), G = linf(rgba[i * 4 + 1]), Bb = linf(rgba[i * 4 + 2]);
    const X = (0.412453 * R + 0.357580 * G + 0.180423 * Bb) / 0.950456, Y = 0.212671 * R + 0.715160 * G + 0.072169 * Bb, Z = (0.019334 * R + 0.119193 * G + 0.950227 * Bb) / 1.088754;
    const fy = fLab(Y);
    const L = Y > 0.008856 ? 116 * fy - 16 : 903.3 * Y;
    L8[i] = M.round(clip(L * 255 / 100, 0, 255));
    A[i] = 500 * (fLab(X) - fy); B[i] = 200 * (fy - fLab(Z));
  }
  return { w, h, L8, A, B };
}
/** Cực đại/cực tiểu cửa sổ chữ nhật kw x kh (tách được). */
function morph(src, w, h, kw, kh, isMax, Type) {
  const rx = kw >> 1, ry = kh >> 1, T = Type || src.constructor;
  const tmp = new T(w * h), out = new T(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    let v = src[y * w + x];
    const a = M.max(0, x - rx), b = M.min(w - 1, x + rx);
    for (let k = a; k <= b; k++) { const s = src[y * w + k]; if (isMax ? s > v : s < v) v = s; }
    tmp[y * w + x] = v;
  }
  for (let y = 0; y < h; y++) { const a = M.max(0, y - ry), b = M.min(h - 1, y + ry);
    for (let x = 0; x < w; x++) { let v = tmp[y * w + x]; for (let k = a; k <= b; k++) { const s = tmp[k * w + x]; if (isMax ? s > v : s < v) v = s; } out[y * w + x] = v; } }
  return out;
}
const dilate = (s, w, h, kw, kh) => morph(s, w, h, kw, kh, true);
const erode = (s, w, h, kw, kh) => morph(s, w, h, kw, kh, false);
const closeM = (s, w, h, kw, kh) => erode(dilate(s, w, h, kw, kh), w, h, kw, kh);
function gauss(src, w, h, sigma) {
  const r = M.max(1, M.ceil(sigma * 3)), k = new Float64Array(2 * r + 1); let sum = 0;
  for (let i = -r; i <= r; i++) { k[i + r] = M.exp(-i * i / (2 * sigma * sigma)); sum += k[i + r]; }
  for (let i = 0; i < k.length; i++) k[i] /= sum;
  const tmp = new Float32Array(w * h), out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { let s = 0; for (let i = -r; i <= r; i++) s += src[y * w + clip(x + i, 0, w - 1)] * k[i + r]; tmp[y * w + x] = s; }
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { let s = 0; for (let i = -r; i <= r; i++) s += tmp[clip(y + i, 0, h - 1) * w + x] * k[i + r]; out[y * w + x] = s; }
  return out;
}
function otsu(u8) {
  const hist = new Float64Array(256); for (let i = 0; i < u8.length; i++) hist[u8[i]]++;
  const tot = u8.length; let sumAll = 0; for (let i = 0; i < 256; i++) sumAll += i * hist[i];
  let wB = 0, sB = 0, best = -1, thr = 0;
  for (let t = 0; t < 256; t++) { wB += hist[t]; if (!wB) continue; const wF = tot - wB; if (!wF) break; sB += t * hist[t];
    const mB = sB / wB, mF = (sumAll - sB) / wF, v = wB * wF * (mB - mF) * (mB - mF); if (v > best) { best = v; thr = t; } }
  return thr;
}
/** Thành phần liên thông 8 láng giềng. Trả {labels,n,x0,y0,x1,y1,area} (chỉ số 1..n). */
function ccl(bin, w, h) {
  const labels = new Int32Array(w * h), par = new Int32Array((w * h >> 1) + 2); let n = 0;
  const find = a => { while (par[a] !== a) { par[a] = par[par[a]]; a = par[a]; } return a; };
  const uni = (a, b) => { a = find(a); b = find(b); if (a !== b) { if (a < b) par[b] = a; else par[a] = b; } };
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (!bin[y * w + x]) continue;
    const nb = [];
    if (x > 0 && labels[y * w + x - 1]) nb.push(labels[y * w + x - 1]);
    if (y > 0) { for (let dx = -1; dx <= 1; dx++) { const xx = x + dx; if (xx >= 0 && xx < w && labels[(y - 1) * w + xx]) nb.push(labels[(y - 1) * w + xx]); } }
    if (!nb.length) { n++; par[n] = n; labels[y * w + x] = n; } else { let m = nb[0]; for (const v of nb) if (v < m) m = v; labels[y * w + x] = m; for (const v of nb) uni(m, v); }
  }
  const map = new Int32Array(n + 1); let c = 0;
  for (let i = 1; i <= n; i++) { if (find(i) === i) map[i] = ++c; }
  for (let i = 1; i <= n; i++) map[i] = map[find(i)];
  const x0 = new Int32Array(c + 1).fill(1 << 30), y0 = new Int32Array(c + 1).fill(1 << 30), x1 = new Int32Array(c + 1).fill(-1), y1 = new Int32Array(c + 1).fill(-1), area = new Int32Array(c + 1);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const l = labels[y * w + x]; if (!l) continue; const m = map[l]; labels[y * w + x] = m; area[m]++; if (x < x0[m]) x0[m] = x; if (x > x1[m]) x1[m] = x; if (y < y0[m]) y0[m] = y; if (y > y1[m]) y1[m] = y; }
  return { labels, n: c + 1, x0, y0, x1, y1, area };
}
function boxMean(src, w, h, kw, kh) {
  const I = new Float64Array((w + 1) * (h + 1));
  for (let y = 0; y < h; y++) { let row = 0; for (let x = 0; x < w; x++) { row += src[y * w + x]; I[(y + 1) * (w + 1) + x + 1] = I[y * (w + 1) + x + 1] + row; } }
  const rx = kw >> 1, ry = kh >> 1, out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const a = M.max(0, x - rx), b = M.min(w, x + rx + 1), c = M.max(0, y - ry), d = M.min(h, y + ry + 1);
    out[y * w + x] = (I[d * (w + 1) + b] - I[c * (w + 1) + b] - I[d * (w + 1) + a] + I[c * (w + 1) + a]) / (kw * kh);
  }
  return out;
}
function bil(a, w, h, x0, y0) {
  const x = clip(x0, 0, w - 1.001), y = clip(y0, 0, h - 1.001), xi = M.trunc(x), yi = M.trunc(y), fx = x - xi, fy = y - yi, i = yi * w + xi;
  return a[i] * (1 - fx) * (1 - fy) + a[i + 1] * fx * (1 - fy) + a[i + w] * (1 - fx) * fy + a[i + w + 1] * fx * fy;
}

// ------------------------------------------------------------ hình học
function orderV(p) {
  const by = f => p.reduce((a, b) => f(b) < f(a) ? b : a);
  return [by(q => q.x + q.y), by(q => q.y - q.x), by(q => -(q.x + q.y)), by(q => -(q.y - q.x))];
}
function area(q) { let s = 0; for (let i = 0; i < q.length; i++) { const a = q[i], b = q[(i + 1) % q.length]; s += a.x * b.y - b.x * a.y; } return M.abs(s) / 2; }
function isConvex(q) {
  let sign = 0;
  for (let i = 0; i < 4; i++) { const a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4]; const cr = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x); const s = cr > 0 ? 1 : cr < 0 ? -1 : 0; if (!s) return false; if (!sign) sign = s; else if (s !== sign) return false; }
  return true;
}
function inter(l1, l2) {
  const det = -l1.d.x * l2.d.y + l2.d.x * l1.d.y; if (M.abs(det) < 1e-6) return null;
  const r = vsub(l2.p, l1.p), t = (r.x * (-l2.d.y) - (-l2.d.x) * r.y) / det; return vadd(l1.p, vmul(l1.d, t));
}
function quadFromLines(L) {
  const q = []; for (let i = 0; i < 4; i++) { const p = inter(L[(i + 3) % 4], L[i]); if (!p || !isFinite(p.x) || !isFinite(p.y)) return null; q.push(p); }
  return orderV(q);
}
function convexHull(pts) { // pts: [{x,y}], monotone chain
  const p = pts.slice().sort((a, b) => a.x - b.x || a.y - b.y); const u = [];
  const cross = (o, a, b) => (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
  const lo = []; for (const q of p) { while (lo.length >= 2 && cross(lo[lo.length - 2], lo[lo.length - 1], q) <= 0) lo.pop(); lo.push(q); }
  const up = []; for (let i = p.length - 1; i >= 0; i--) { const q = p[i]; while (up.length >= 2 && cross(up[up.length - 2], up[up.length - 1], q) <= 0) up.pop(); up.push(q); }
  lo.pop(); up.pop(); return lo.concat(up);
}
function dpChain(pts, i0, i1, eps, keep) { // Douglas-Peucker trên pts[i0..i1] (đa giác kín, chỉ số vòng)
  const n = pts.length; if (((i1 - i0 + n) % n) < 2) return;
  const a = pts[i0], b = pts[i1]; const dx = b.x - a.x, dy = b.y - a.y, L = M.hypot(dx, dy) || 1e-9;
  let best = -1, bi = -1; let i = (i0 + 1) % n;
  while (i !== i1) { const d = M.abs((pts[i].x - a.x) * dy - (pts[i].y - a.y) * dx) / L; if (d > best) { best = d; bi = i; } i = (i + 1) % n; }
  if (best > eps) { keep.add(bi); dpChain(pts, i0, bi, eps, keep); dpChain(pts, bi, i1, eps, keep); }
}
function approxPoly(hull, eps) {
  const n = hull.length; if (n <= 3) return hull.slice();
  let far = 0, fd = -1; for (let i = 1; i < n; i++) { const d = M.hypot(hull[i].x - hull[0].x, hull[i].y - hull[0].y); if (d > fd) { fd = d; far = i; } }
  const keep = new Set([0, far]); dpChain(hull, 0, far, eps, keep); dpChain(hull, far, 0, eps, keep);
  return [...keep].sort((a, b) => a - b).map(i => hull[i]);
}
function perimeter(h) { let s = 0; for (let i = 0; i < h.length; i++) { const a = h[i], b = h[(i + 1) % h.length]; s += M.hypot(a.x - b.x, a.y - b.y); } return s; }
function minAreaBox(hull) {
  let best = null;
  for (let i = 0; i < hull.length; i++) {
    const a = hull[i], b = hull[(i + 1) % hull.length], L = M.hypot(b.x - a.x, b.y - a.y); if (L < 1e-9) continue;
    const ux = (b.x - a.x) / L, uy = (b.y - a.y) / L; let mnx = 1e18, mxx = -1e18, mny = 1e18, mxy = -1e18;
    for (const p of hull) { const px = (p.x - a.x) * ux + (p.y - a.y) * uy, py = -(p.x - a.x) * uy + (p.y - a.y) * ux; if (px < mnx) mnx = px; if (px > mxx) mxx = px; if (py < mny) mny = py; if (py > mxy) mxy = py; }
    const ar = (mxx - mnx) * (mxy - mny);
    if (!best || ar < best.ar) best = { ar, c: [[mnx, mny], [mxx, mny], [mxx, mxy], [mnx, mxy]].map(([x, y]) => V(a.x + x * ux - y * uy, a.y + x * uy + y * ux)) };
  }
  return orderV(best.c);
}
function solve8(A, b) {
  const n = 8; for (let i = 0; i < n; i++) A[i].push(b[i]);
  for (let c = 0; c < n; c++) { let p = c; for (let r = c + 1; r < n; r++) if (M.abs(A[r][c]) > M.abs(A[p][c])) p = r; [A[c], A[p]] = [A[p], A[c]];
    for (let r = c + 1; r < n; r++) { const f = A[r][c] / A[c][c]; for (let k = c; k <= n; k++) A[r][k] -= f * A[c][k]; } }
  const x = new Array(n); for (let r = n - 1; r >= 0; r--) { let s = A[r][n]; for (let k = r + 1; k < n; k++) s -= A[r][k] * x[k]; x[r] = s / A[r][r]; } return x;
}
/** Ma trận 3x3 (mảng 9) ánh xạ src[i] -> dst[i]. */
function perspective(src, dst) {
  const A = [], b = [];
  for (let i = 0; i < 4; i++) { const { x, y } = src[i], { x: u, y: v } = dst[i];
    A.push([x, y, 1, 0, 0, 0, -u * x, -u * y]); b.push(u); A.push([0, 0, 0, x, y, 1, -v * x, -v * y]); b.push(v); }
  const h = solve8(A, b); return [...h, 1];
}
function inv3(m) {
  const [a, b, c, d, e, f, g, h, i] = m, A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g, det = a * A + b * B + c * C;
  return [A / det, -(b * i - c * h) / det, (b * f - c * e) / det, B / det, (a * i - c * g) / det, -(a * f - c * d) / det, C / det, -(a * h - b * g) / det, (a * e - b * d) / det];
}
function applyH(m, x, y) { const w = m[6] * x + m[7] * y + m[8]; return V((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w); }
/** warp RGBA: M ánh xạ ảnh nguồn -> ảnh đích (ow x oh). Nội suy song tuyến, biên lặp lại. */
function warp(img, Mf, ow, oh) {
  const mi = inv3(Mf), out = new Uint8ClampedArray(ow * oh * 4), { w, h, data } = img;
  for (let y = 0; y < oh; y++) for (let x = 0; x < ow; x++) {
    const ww = mi[6] * x + mi[7] * y + mi[8], sx = clip((mi[0] * x + mi[1] * y + mi[2]) / ww, 0, w - 1.001), sy = clip((mi[3] * x + mi[4] * y + mi[5]) / ww, 0, h - 1.001);
    const xi = M.trunc(sx), yi = M.trunc(sy), fx = sx - xi, fy = sy - yi, i = (yi * w + xi) * 4, j = i + w * 4, o = (y * ow + x) * 4;
    for (let c = 0; c < 3; c++) out[o + c] = data[i + c] * (1 - fx) * (1 - fy) + data[i + 4 + c] * fx * (1 - fy) + data[j + c] * (1 - fx) * fy + data[j + 4 + c] * fx * fy;
    out[o + 3] = 255;
  }
  return { w: ow, h: oh, data: out };
}
function resizeRGBA(img, nw, nh) {
  const f = resizeArea(img.data, img.w, img.h, nw, nh, 4), o = new Uint8ClampedArray(nw * nh * 4); for (let i = 0; i < o.length; i++) o[i] = f[i]; return { w: nw, h: nh, data: o };
}

// ------------------------------------------------------------ mặt nạ chữ
function textMask(L8, w, h, cx0, cx1) {
  const k = odd(M.max(9, 0.025 * M.max(h, w)));
  const cl = closeM(L8, w, h, k, k), bh = new Uint8Array(w * h);
  for (let i = 0; i < w * h; i++) bh[i] = M.max(0, cl[i] - L8[i]);
  const thr = M.max(otsu(bh), 14), wthr = M.max(8, 0.5 * thr), w2 = M.max(6, 0.35 * thr);
  const strong = new Uint8Array(w * h), weak = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const i = y * w + x, v = bh[i]; strong[i] = v > thr ? 1 : 0; weak[i] = (cx0 >= 0 && x >= cx0 && x < cx1) ? (v > w2 ? 1 : 0) : (v > wthr ? 1 : 0); }
  const near = dilate(strong, w, h, 15, 15), out = new Uint8Array(w * h); for (let i = 0; i < w * h; i++) out[i] = weak[i] & near[i]; return out;
}
function textPoints(L8, w, h, long) {
  const tm = textMask(L8, w, h, -1, -1), c0 = ccl(tm, w, h), ok0 = new Uint8Array(c0.n);
  for (let i = 1; i < c0.n; i++) { const cw = c0.x1[i] - c0.x0[i] + 1, ch = c0.y1[i] - c0.y0[i] + 1; if (cw <= 0.07 * long && ch <= 0.035 * long && c0.area[i] >= 3 && M.max(cw, ch) / M.max(1, M.min(cw, ch)) < 12) ok0[i] = 1; }
  const tm2 = new Uint8Array(w * h), tmf = new Float32Array(w * h); for (let i = 0; i < w * h; i++) { tm2[i] = ok0[c0.labels[i]]; tmf[i] = tm2[i]; }
  const kd = odd(M.max(15, 0.03 * long)), dens = boxMean(tmf, w, h, kd, kd), core = new Uint8Array(w * h); for (let i = 0; i < w * h; i++) core[i] = dens[i] > 0.10 ? 1 : 0;
  const cc = closeM(core, w, h, odd(0.03 * w), odd(0.02 * h)), c1 = ccl(cc, w, h), keep = new Uint8Array(c1.n);
  for (let i = 1; i < c1.n; i++) keep[i] = c1.area[i] >= 0.02 * h * w ? 1 : 0;
  const xs = [], ys = []; for (let i = 0; i < w * h; i++) if (tm2[i] && keep[c1.labels[i]]) { xs.push(i % w); ys.push((i / w) | 0); }
  const stride = M.max(1, M.ceil(xs.length / 8000)), sx = [], sy = []; for (let k = 0; k < xs.length; k += stride) { sx.push(xs[k]); sy.push(ys[k]); }
  return { xs: Float64Array.from(sx), ys: Float64Array.from(sy), n: sx.length };
}
const proj = (tp, o) => { const a = new Float64Array(tp.n); for (let i = 0; i < tp.n; i++) a[i] = tp.xs[i] * o.x + tp.ys[i] * o.y; return a; };

// ------------------------------------------------------------ 4 đường biên khối chữ
function hullOf(tp) { const pts = []; for (let i = 0; i < tp.n; i++) pts.push(V(tp.xs[i], tp.ys[i])); return convexHull(pts); }
function linesA(tp, hull) {
  const peri = perimeter(hull); let quad = null;
  for (let eps = 0.01; eps <= 0.15; eps += 0.005) { const ap = approxPoly(hull, eps * peri); if (ap.length === 4) { quad = ap; break; } if (ap.length < 4) break; }
  const box = orderV(quad || minAreaBox(hull)), cen = V(box.reduce((s, p) => s + p.x, 0) / 4, box.reduce((s, p) => s + p.y, 0) / 4), out = [];
  for (let i = 0; i < 4; i++) {
    const a = box[i], b = box[(i + 1) % 4], len = vlen(vsub(b, a)); if (len < 1e-6) return null;
    const d = vmul(vsub(b, a), 1 / len); let o = V(d.y, -d.x); if (dot(o, vsub(vmul(vadd(a, b), .5), cen)) < 0) o = vneg(o);
    const pr = new Float64Array(tp.n); for (let k = 0; k < tp.n; k++) pr[k] = (tp.xs[k] - a.x) * o.x + (tp.ys[k] - a.y) * o.y;
    out.push(Ln(vadd(a, vmul(o, M.max(pct(pr, 99.8), 0))), d, o));
  }
  return out;
}
function linesB(tp, hull) {
  const box = minAreaBox(hull), boxC = V(box.reduce((s, p) => s + p.x, 0) / 4, box.reduce((s, p) => s + p.y, 0) / 4), cen = V(hull.reduce((s, p) => s + p.x, 0) / hull.length, hull.reduce((s, p) => s + p.y, 0) / hull.length), n = hull.length, out = [];
  for (let i = 0; i < 4; i++) {
    const a = box[i], b = box[(i + 1) % 4], l0 = vlen(vsub(b, a)); if (l0 < 1e-6) return null;
    const dd = vmul(vsub(b, a), 1 / l0); let o0 = V(dd.y, -dd.x); if (dot(o0, vsub(vmul(vadd(a, b), .5), boxC)) < 0) o0 = vneg(o0);
    const d0 = V(-o0.y, o0.x); let num = 0, den = 0;
    for (let j = 0; j < n; j++) {
      const p = hull[j], q = hull[(j + 1) % n]; let e = vsub(q, p); const len = vlen(e); if (len < 1e-3) continue; e = vmul(e, 1 / len);
      let nn = V(e.y, -e.x); if (dot(nn, vsub(vmul(vadd(p, q), .5), cen)) < 0) nn = vneg(nn);
      if (M.acos(clip(dot(nn, o0), -1, 1)) < 20 * M.PI / 180) { if (dot(e, d0) < 0) e = vneg(e); num += len * M.atan2(d0.x * e.y - d0.y * e.x, dot(d0, e)); den += len; }
    }
    const th = den > 0 ? num / den : 0, c = M.cos(th), s = M.sin(th), d = V(c * d0.x - s * d0.y, s * d0.x + c * d0.y); let o = V(d.y, -d.x); if (dot(o, o0) < 0) o = vneg(o);
    out.push(Ln(vmul(o, pct(proj(tp, o), 99.8)), d, o));
  }
  return out;
}
function tightness(tp, ln, long, nb = 24) {
  const pd = proj(tp, ln.d), po = proj(tp, ln.o), base = dot(ln.p, ln.o), lo = pct(pd, 5), hi = pct(pd, 95), cap = 0.1 * long, step = (hi - lo) / nb; if (step <= 0) return 1e9;
  const mins = new Float64Array(nb).fill(1e18), cnt = new Int32Array(nb);
  for (let i = 0; i < pd.length; i++) { if (pd[i] < lo || pd[i] >= hi) continue; const k = M.min(nb - 1, M.trunc((pd[i] - lo) / step)); cnt[k]++; const d = M.max(base - po[i], 0); if (d < mins[k]) mins[k] = d; }
  let sum = 0, m = 0; for (let k = 0; k < nb; k++) if (cnt[k] >= 3) { sum += M.min(mins[k], cap); m++; } return m >= 6 ? sum / m : 1e9;
}
function supportFrac(tp, ln, long, nb = 24) {
  const pd = proj(tp, ln.d), po = proj(tp, ln.o), base = dot(ln.p, ln.o), lo = pct(pd, 2), hi = pct(pd, 98), step = (hi - lo) / nb; if (step <= 0) return 0;
  const mins = new Float64Array(nb).fill(1e18), cnt = new Int32Array(nb);
  for (let i = 0; i < pd.length; i++) { if (pd[i] < lo || pd[i] >= hi) continue; const k = M.min(nb - 1, M.trunc((pd[i] - lo) / step)); cnt[k]++; const d = M.max(base - po[i], 0); if (d < mins[k]) mins[k] = d; }
  let hit = 0; for (let k = 0; k < nb; k++) if (cnt[k] >= 2 && mins[k] < 0.014 * long) hit++; return hit / nb;
}
function textLines(tp, long) {
  const hull = hullOf(tp); if (hull.length < 3) return null;
  let la = null, lb = null; try { la = linesA(tp, hull); } catch (e) {} try { lb = linesB(tp, hull); } catch (e) {}
  const cands = [la, lb].filter(Boolean); if (!cands.length) return null; if (cands.length === 1) return cands[0];
  const fin = [];
  for (let i = 0; i < 4; i++) { let best = cands[0][i], bs = tightness(tp, best, long); for (let c = 1; c < cands.length; c++) { const s = tightness(tp, cands[c][i], long); if (s < bs) { bs = s; best = cands[c][i]; } } fin.push(best); }
  const sup = fin.map(l => supportFrac(tp, l, long));
  for (const [i, j] of [[0, 2], [2, 0], [1, 3], [3, 1]]) {
    const ang = M.acos(clip(M.abs(dot(fin[i].d, fin[j].d)), 0, 1)) * 180 / M.PI;
    if (sup[i] < 0.45 && sup[j] >= 0.6 && ang > 14) { const dj = fin[j].d, di = dot(dj, fin[i].d) > 0 ? dj : vneg(dj); let oo = V(di.y, -di.x); if (dot(oo, fin[i].o) < 0) oo = vneg(oo); fin[i] = Ln(vmul(oo, pct(proj(tp, oo), 99.8)), di, oo); }
  }
  const q = quadFromLines(fin); if (q && isConvex(q)) return fin; return la || lb;
}

// ------------------------------------------------------------ bắt dính mép giấy
/** lab: {w,h,L8,A,B} của ảnh nhỏ. prior: 4 đường [trên,phải,dưới,trái]; snapIds: tập cạnh cần dò. */
function snapSides(lab, tp, prior, pq, s, sup, snapIds) {
  const { w, h } = lab, long = M.max(w, h), kc = odd(M.max(9, 0.02 * long));
  const lf = Float32Array.from(lab.L8), lc = closeM(lf, w, h, kc, kc, Float32Array), ls = gauss(lc, w, h, 1.0);
  const roi = [], cx0 = M.trunc(.3 * w), cx1 = M.trunc(.7 * w), cy0 = M.trunc(.3 * h), cy1 = M.trunc(.7 * h);
  for (let y = cy0; y < cy1; y++) for (let x = cx0; x < cx1; x++) roi.push(ls[y * w + x]);
  const bigP = pct(roi, 85), t60 = pct(roi, 60), sa = [], sb = [];
  for (let y = cy0; y < cy1; y++) for (let x = cx0; x < cx1; x++) if (ls[y * w + x] >= t60) { sa.push(lab.A[y * w + x]); sb.push(lab.B[y * w + x]); }
  const ma = median(sa), mb = median(sb), cr = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) { const d = M.hypot(lab.A[i] - ma, lab.B[i] - mb) / 12; cr[i] = M.exp(-d * d); }
  const chrom = gauss(cr, w, h, 2.0), scales = [2, 4, 7, 11], gam = [1, .97, .93, .88], K = 28, finalL = [];
  for (let i = 0; i < 4; i++) {
    const pl = prior[i], o = pl.o;
    if (!snapIds.has(i)) { finalL.push(pl); continue; }
    const weak = sup[i] < 0.45, band = M.trunc(M.max(16, 2.4 * s * (weak ? 2.4 : 1))), a = pq[i], b = pq[(i + 1) % 4], n = 2 * band + 1;
    const cx = new Float64Array(K), cy = new Float64Array(K); for (let k = 0; k < K; k++) { const t = 0.1 + 0.8 * k / (K - 1); cx[k] = a.x + (b.x - a.x) * t; cy[k] = a.y + (b.y - a.y) * t; }
    const E = [], Pc = [], inb = [], lr = new Float64Array(n), cvv = new Float64Array(n);
    for (let k = 0; k < K; k++) {
      const ek = new Float64Array(n), pk = new Float64Array(n), ib = new Uint8Array(n);
      for (let j = 0; j < n; j++) { const u = j - band, px = cx[k] + u * o.x, py = cy[k] + u * o.y; lr[j] = bil(ls, w, h, px, py); cvv[j] = bil(chrom, w, h, px, py); ib[j] = (px >= 0 && px <= w - 1 && py >= 0 && py <= h - 1) ? 1 : 0; }
      const cl = new Float64Array(n + 1), cc = new Float64Array(n + 1); for (let j = 0; j < n; j++) { cl[j + 1] = cl[j] + lr[j]; cc[j + 1] = cc[j] + cvv[j]; }
      for (let si = 0; si < 4; si++) { const sc = scales[si], g = gam[si];
        for (let j = sc; j < n - sc; j++) {
          const dl = (cl[j] - cl[j - sc]) / sc - (cl[j + sc + 1] - cl[j + 1]) / sc;
          ek[j] = M.max(ek[j], g * (M.max(dl, 0) + 0.6 * M.max(-dl, 0)));
          pk[j] = M.max(pk[j], g * clip((cc[j] - cc[j - sc]) / sc - (cc[j + sc + 1] - cc[j + 1]) / sc, 0, 1));
        } }
      E.push(ek); Pc.push(pk); inb.push(ib);
    }
    const allE = new Float64Array(K * n); for (let k = 0; k < K; k++) allE.set(E[k], k * n);
    const eNorm = M.max(pct(allE, 97), 0.05 * bigP), ut = pct(proj(tp, o), 99.7) - dot(pl.p, o);
    const px_ = new Float64Array(K), py_ = new Float64Array(K), conf = new Float64Array(K), S = new Float64Array(n);
    for (let k = 0; k < K; k++) {
      for (let j = 0; j < n; j++) {
        const u = j - band, en = clip(E[k][j] / eNorm, 0, 1); E[k][j] = en;
        const gate = clip((u - ut - 0.12 * s) / (0.1 * s), 0.05, 1), z = u / (0.6 * s + 1e-6) * 0.9, pri = M.exp(-0.5 * z * z);
        S[j] = (!inb[k][j] || j < 6 || j >= n - 6) ? 0 : gate * (0.60 * en + 0.30 * Pc[k][j] + 0.10 * pri);
      }
      let jb = 0; for (let j = 1; j < n; j++) if (S[j] > S[jb]) jb = j;
      const smax = S[jb]; let ex = -1; for (let j = 0; j < n; j++) if (!(j >= jb - 7 && j < jb + 8) && S[j] > ex) ex = S[j];
      conf[k] = clip((smax - M.max(ex, median(S))) / 0.25, 0, 1) * clip(E[k][jb] / 0.35, 0, 1);
      px_[k] = cx[k] + o.x * (jb - band); py_[k] = cy[k] + o.y * (jb - band);
    }
    // RANSAC có trọng số (bộ sinh số giả ngẫu nhiên cố định => kết quả lặp lại được)
    let seed = (11 + i) >>> 0; const rnd = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
    const tol = 0.005 * long, valid = []; for (let k = 0; k < K; k++) if (conf[k] > 0.2) valid.push(k);
    let bestIn = null, bsc = 0; const sl = vlen(vsub(b, a));
    if (valid.length >= 5) for (let it = 0; it < 120; it++) {
      const p = valid[M.trunc(rnd() * valid.length)], q = valid[M.trunc(rnd() * valid.length)]; if (p === q) continue;
      let dx = px_[q] - px_[p], dy = py_[q] - py_[p]; const ln = M.hypot(dx, dy); if (ln < 0.25 * sl) continue; dx /= ln; dy /= ln;
      const inl = new Uint8Array(K); let sc3 = 0;
      for (let k = 0; k < K; k++) { const d = M.abs((px_[k] - px_[p]) * (-dy) + (py_[k] - py_[p]) * dx); if (d < tol && conf[k] > 0.2) { inl[k] = 1; sc3 += conf[k]; } }
      if (sc3 > bsc) { bsc = sc3; bestIn = inl; }
    }
    let line = pl; const nIn = bestIn ? bestIn.reduce((s2, v) => s2 + v, 0) : 0;
    if (bestIn && nIn >= 5 && bsc / nIn > 0.45 && nIn >= 0.22 * K) {
      let sw = 0, mx = 0, my = 0; for (let k = 0; k < K; k++) if (bestIn[k]) { sw += conf[k]; mx += conf[k] * px_[k]; my += conf[k] * py_[k]; } mx /= sw; my /= sw;
      let sxx = 0, sxy = 0, syy = 0; for (let k = 0; k < K; k++) if (bestIn[k]) { const dx = px_[k] - mx, dy = py_[k] - my; sxx += conf[k] * dx * dx; sxy += conf[k] * dx * dy; syy += conf[k] * dy * dy; }
      const ang = 0.5 * M.atan2(2 * sxy, sxx - syy); let dv = V(M.cos(ang), M.sin(ang)); if (dot(dv, pl.d) < 0) dv = vneg(dv);
      let o3 = V(dv.y, -dv.x); if (dot(o3, o) < 0) o3 = vneg(o3);
      if (M.abs(dot(dv, pl.d)) > M.cos((weak ? 22 : 8) * M.PI / 180)) line = Ln(V(mx, my), dv, o3);
    }
    finalL.push(line);
  }
  return finalL;
}
function safePush(finalL, tp, s, padCm) {
  return finalL.map(ln => { const need = pct(proj(tp, ln.o), 99.9) + padCm * s - dot(ln.p, ln.o); return need > 0 ? Ln(vadd(ln.p, vmul(ln.o, need)), ln.d, ln.o) : ln; });
}
function smallOf(img, long = 700) {
  const sc = long / M.max(img.w, img.h), w = M.max(1, M.round(img.w * sc)), h = M.max(1, M.round(img.h * sc));
  const f = resizeArea(img.data, img.w, img.h, w, h, 4); return { sc: w / img.w, sc_y: h / img.h, w, h, f };
}
/** Phân tích: 4 góc sách + ngữ cảnh (ảnh nhỏ, Lab, điểm chữ). null nếu không thấy chữ. */
function analyze(img, Sw, Sh) {
  const sm = smallOf(img), { w, h } = sm, long = M.max(w, h), lab = toLab(sm.f, w, h);
  const tp = textPoints(lab.L8, w, h, long); if (tp.n < 200) return null;
  const lines = textLines(tp, long); if (!lines) return null;
  const tq = quadFromLines(lines); if (!tq) return null;
  const pw = Sw / 2, mo = 0.095 * pw, mt = 0.060 * Sh, mb = 0.065 * Sh, tw = Sw - 2 * (mo + 0.075 * pw), th = Sh - (mt + mb);
  const topw = (vlen(vsub(tq[1], tq[0])) + vlen(vsub(tq[2], tq[3]))) / 2, sideh = (vlen(vsub(tq[3], tq[0])) + vlen(vsub(tq[2], tq[1]))) / 2;
  const s = M.sqrt((topw / tw) * (sideh / th)); if (!isFinite(s) || s <= 1e-3) return null;
  const expM = [mt, mo, mb, mo], prior = lines.map((l, i) => Ln(vadd(l.p, vmul(l.o, expM[i] * s)), l.d, l.o)), pq = quadFromLines(prior); if (!pq) return null;
  const sup = lines.map(l => supportFrac(tp, l, long));
  const fin = safePush(snapSides(lab, tp, prior, pq, s, sup, new Set([0, 1, 2, 3])), tp, s, 0.15);
  let q = quadFromLines(fin), ok = !!q; if (ok) { const ar = area(q), ap = area(pq); ok = isConvex(q) && ar > 0.8 * ap && ar < 1.35 * ap; } if (!ok) q = pq;
  const res = orderV(q.map(p => V(clip(p.x, 0, w - 1) / sm.sc, clip(p.y, 0, h - 1) / sm.sc_y))); return { q: res, sm, lab, tp, long, w, h };
}
function detectQuad(img, Sw, Sh) { const a = analyze(img, Sw, Sh); return a && a.q; }
/** Tứ giác DỰ ĐOÁN riêng cho 1 trang từ khối chữ của chính trang đó (tự theo độ nghiêng/phối cảnh của trang).
 *  side: 0 trái, 1 phải. gT,gB: hai đầu đường gáy (toạ độ ảnh nhỏ). Trả tứ giác (toạ độ ảnh gốc) hoặc null. */
function pageQuadFromText(A, side, gT, gB, Sw, Sh) {
  const { tp, long, w, sm } = A, pw = Sw / 2, mo = 0.095 * pw, mi = 0.075 * pw, mt = 0.060 * Sh, mb = 0.065 * Sh;
  const xl = y => gT.x + (gB.x - gT.x) * (y - gT.y) / (gB.y - gT.y), xs = [], ys = [];
  for (let i = 0; i < tp.n; i++) { const d = tp.xs[i] - xl(tp.ys[i]); if ((side === 0 && d < -0.008 * w) || (side === 1 && d > 0.008 * w)) { xs.push(tp.xs[i]); ys.push(tp.ys[i]); } }
  if (xs.length < 150) return null;
  const tpp = { xs: Float64Array.from(xs), ys: Float64Array.from(ys), n: xs.length };
  const lines = textLines(tpp, long); if (!lines) return null;
  const tq = quadFromLines(lines); if (!tq) return null;
  const tw = pw - (mo + mi), th = Sh - (mt + mb);
  const topw = (vlen(vsub(tq[1], tq[0])) + vlen(vsub(tq[2], tq[3]))) / 2, sideh = (vlen(vsub(tq[3], tq[0])) + vlen(vsub(tq[2], tq[1]))) / 2;
  const s = M.sqrt((topw / tw) * (sideh / th)); if (!isFinite(s) || s <= 1e-3) return null;
  const expM = side === 0 ? [mt, mi, mb, mo] : [mt, mo, mb, mi]; // trên, phải, dưới, trái
  const prior = lines.map((l, i) => Ln(vadd(l.p, vmul(l.o, expM[i] * s)), l.d, l.o));
  const inner = side === 0 ? 1 : 3, mg = V((gT.x + gB.x) / 2, (gT.y + gB.y) / 2), il = prior[inner], t = dot(vsub(mg, il.p), il.o);
  if (t < 0) prior[inner] = Ln(vadd(il.p, vmul(il.o, t)), il.d, il.o); // mép trong không vượt qua đường gáy
  const q = quadFromLines(prior); if (!q || !isConvex(q)) return null;
  return { q: orderV(q.map(p => V(p.x / sm.sc, p.y / sm.sc_y))), s: s / sm.sc };
}
/** Bước 2 (từng trang): crop thô kèm nền -> dò lại các cạnh ngoài. inner=[x0,y0,x1,y1] trong toạ độ crop; s = px/cm của crop. */
function refinePage(crop, inner, s, outer) {
  const { w, h } = crop, long = M.max(w, h), lab = toLab(crop.data, w, h), tp = textPoints(lab.L8, w, h, long); if (tp.n < 100) return null;
  const [x0, y0, x1, y1] = inner, prior = [Ln(V(0, y0), V(1, 0), V(0, -1)), Ln(V(x1, 0), V(0, 1), V(1, 0)), Ln(V(0, y1), V(1, 0), V(0, 1)), Ln(V(x0, 0), V(0, 1), V(-1, 0))];
  const pq = quadFromLines(prior); if (!pq) return null;
  const fin = safePush(snapSides(lab, tp, prior, pq, s, [1, 1, 1, 1], new Set(outer)), tp, s, 0.15);
  let q = quadFromLines(fin), ok = !!q; if (ok) { const ar = area(q), ap = area(pq); ok = isConvex(q) && ar > 0.8 * ap && ar < 1.25 * ap; } if (!ok) q = pq;
  return orderV(q);
}

// ------------------------------------------------------------ gáy sách
function findCut(rgbaSpread) { // rgbaSpread: {w,h,data} ; trả về {x: phần trăm 0..1, risky}
  const sc = 700 / rgbaSpread.w, w = 700, h = M.max(8, M.round(rgbaSpread.h * sc));
  const f = resizeArea(rgbaSpread.data, rgbaSpread.w, rgbaSpread.h, w, h, 4), g = new Uint8Array(w * h);
  for (let i = 0; i < w * h; i++) g[i] = M.round(0.299 * f[i * 4] + 0.587 * f[i * 4 + 1] + 0.114 * f[i * 4 + 2]);
  return findCutGray(g, w, h);
}
function findCutGray(g, w, h) {
  const kc = odd(M.max(9, 0.025 * M.max(h, w))), lc = closeM(Float32Array.from(g), w, h, kc, kc, Float32Array), x0 = M.trunc(.3 * w), x1 = M.trunc(.7 * w);
  const tm = textMask(g, w, h, x0, x1), mx = M.trunc(.03 * w), my = M.trunc(.03 * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (y < my || y >= h - my || x < mx || x >= w - mx) tm[y * w + x] = 0;
  const ya = M.trunc(.1 * h), yb = M.trunc(.9 * h), col = new Float32Array(w);
  for (let x = 0; x < w; x++) { let s = 0; for (let y = ya; y < yb; y++) s += lc[y * w + x]; col[x] = s / (yb - ya); }
  const kk = odd(M.max(5, w / 40)), cm = gauss(col, w, 1, (0.3 * ((kk - 1) * 0.5 - 1) + 0.8)), ref = pct(Array.from(cm.slice(M.trunc(.1 * w), M.trunc(.9 * w))), 85);
  const dn = new Float64Array(w).map((_, x) => clip(clip(1 - cm[x] / ref, 0, 1) / 0.15, 0, 1));
  const raw = new Uint8Array(w); for (let x = 0; x < w; x++) { let s = 0; for (let y = 0; y < h; y++) s += tm[y * w + x]; raw[x] = s / h > 0.004 ? 1 : 0; }
  const tc = new Uint8Array(w); for (let x = 0; x < w; x++) tc[x] = (raw[x] || (x > 0 && raw[x - 1]) || (x < w - 1 && raw[x + 1])) ? 1 : 0;
  const dist = new Float64Array(w); let last = -1e9; for (let x = 0; x < w; x++) { if (tc[x]) last = x; dist[x] = x - last; } last = 1e9; for (let x = w - 1; x >= 0; x--) { if (tc[x]) last = x; dist[x] = M.min(dist[x], last - x); }
  let xb = w >> 1, bv = -1e9;
  for (let x = x0; x < x1; x++) { const gap = clip(dist[x] / (0.025 * w), 0, 1), z = (x - w / 2) / (0.08 * w), s = 0.55 * dn[x] + 0.25 * gap + 0.20 * M.exp(-0.5 * z * z); if (s > bv) { bv = s; xb = x; } }
  const margin = M.max(2, M.trunc(0.008 * w)); let xl = xb; while (xl > 0 && !tc[xl]) xl--; let xr = xb; while (xr < w - 1 && !tc[xr]) xr++;
  let xf, risky; if (xr - xl >= 2 * margin + 2) { const ctr = (xl + xr) / 2, gp = xr - xl; xf = clip(clip(xb, ctr - .25 * gp, ctr + .25 * gp), xl + margin, xr - margin); risky = false; } else { xf = xb; risky = true; }
  return { x: xf / w, risky };
}

// ------------------------------------------------------------ tách trang
/** Trả {pages:[ảnh RGBA], ppc, ok}. Mỗi trang: homography riêng sau khi cắt thô kèm nền và dò lại mép. */
function splitSpread(img, Sw, Sh, opt) {
  opt = opt || {};
  const A = analyze(img, Sw, Sh); if (!A) return null; const q = A.q;
  const pxW = (vlen(vsub(q[1], q[0])) + vlen(vsub(q[2], q[3]))) / 2, pxH = (vlen(vsub(q[3], q[0])) + vlen(vsub(q[2], q[1]))) / 2;
  const ppc = M.max(20, M.min(M.sqrt((pxW / Sw) * (pxH / Sh)), 300 / 2.54)), pw = M.round(Sw / 2 * ppc), H = M.round(Sh * ppc);
  // ảnh nắn tạm (rộng 700 px) để dò đường gáy ở nửa trên/dưới
  const tw = 700, th = M.round(700 * Sh / Sw), dst = [V(0, 0), V(tw - 1, 0), V(tw - 1, th - 1), V(0, th - 1)];
  const Mq = perspective(q, dst), Mi = inv3(Mq), spread = warp(img, Mq, tw, th), h2 = th >> 1;
  const half = (y0, y1) => ({ w: tw, h: y1 - y0, data: spread.data.subarray(y0 * tw * 4, y1 * tw * 4) });
  let xt = clip(findCut(half(0, h2)).x * tw, 0.38 * tw, 0.62 * tw), xb = clip(findCut(half(h2, th)).x * tw, 0.38 * tw, 0.62 * tw);
  if (M.abs(xt - xb) > 0.08 * tw) xt = xb = (xt + xb) / 2;
  const k = (xb - xt) / (th / 2), gt = applyH(Mi, xt - k * th / 4, 0), gb = applyH(Mi, xb + k * th / 4, th - 1);
  // khung thô mặc định (từ tứ giác sách + gáy) – dùng khi trang thiếu chữ
  let rough = [[q[0], gt, gb, q[3]], [gt, q[1], q[2], gb]];
  // khung thô THEO CHỮ từng trang: mỗi trang tự theo độ nghiêng/phối cảnh của chính nó
  const gTs = V(gt.x * A.sm.sc, gt.y * A.sm.sc_y), gBs = V(gb.x * A.sm.sc, gb.y * A.sm.sc_y);
  const pq = [0, 1].map(side => { try { return pageQuadFromText(A, side, gTs, gBs, Sw, Sh); } catch (e) { return null; } });
  if (opt.textRough === true) rough = rough.map((r, side) => { const p = pq[side]; if (!p) return r; const ar = area(p.q), ar0 = area(r); return (ar > 0.6 * ar0 && ar < 1.6 * ar0) ? p.q : r; });
  const m = M.round(1.5 * ppc), gm = M.round(0.2 * ppc), pages = [], mats = [];
  const d2 = [V(0, 0), V(pw - 1, 0), V(pw - 1, H - 1), V(0, H - 1)];
  for (let side = 0; side < 2; side++) {
    const rq = rough[side], x0 = side === 0 ? m : gm, cw = m + pw + gm, ch = H + 2 * m, cs = 700 / M.max(cw, ch); // crop thu nhỏ còn ~700 px
    const dIn = [V(x0 * cs, m * cs), V((x0 + pw - 1) * cs, m * cs), V((x0 + pw - 1) * cs, (m + H - 1) * cs), V(x0 * cs, (m + H - 1) * cs)];
    const mm = perspective(rq, dIn), crop = warp(img, mm, M.max(8, M.round(cw * cs)), M.max(8, M.round(ch * cs)));
    let finalQ = rq;
    try {
      const q2 = refinePage(crop, [x0 * cs, m * cs, (x0 + pw) * cs, (m + H) * cs], ppc * cs, side === 0 ? [0, 2, 3] : [0, 1, 2]);
      if (q2) { const mi2 = inv3(mm); finalQ = q2.map(p => applyH(mi2, p.x, p.y)); }
    } catch (e) {}
    const Mf = perspective(finalQ, d2); mats.push(Mf); pages.push(warp(img, Mf, pw, H));
  }
  return { pages, ppc, quad: q, mats, dbg: { gt, gb, rough: rough.map(r => r.map(p => [p.x, p.y])), pq: pq.map(p => p && p.q.map(v => [v.x, v.y])) } };
}

// ------------------------------------------------------------ làm sạch từng trang
function grayOf(img) { const g = new Uint8Array(img.w * img.h); for (let i = 0; i < g.length; i++) g[i] = M.round(0.299 * img.data[i * 4] + 0.587 * img.data[i * 4 + 1] + 0.114 * img.data[i * 4 + 2]); return g; }
function flatten(g, w, h) {
  const nw = M.max(8, M.round(w * .25)), nh = M.max(8, M.round(h * .25)), sm = resizeArea(g, w, h, nw, nh, 1);
  const ks = M.max(odd(M.max(nw, nh) / 18), 9), bg = gauss(dilate(sm, nw, nh, ks, ks, Float32Array), nw, nh, 0.3 * ((ks - 1) * 0.5 - 1) + 0.8), out = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const b = bil(bg, nw, nh, (x + .5) * nw / w - .5, (y + .5) * nh / h - .5); out[y * w + x] = clip(g[y * w + x] / M.max(b, 1) * 255, 0, 255); }
  return out;
}
function levels(g) { const hist = new Int32Array(256); for (let i = 0; i < g.length; i++) hist[g[i]]++; let acc = 0, p = 0; const tg = g.length * .01; for (let i = 0; i < 256; i++) { acc += hist[i]; if (acc >= tg) { p = i; break; } }
  const lo = M.min(p, 110), hi = 235; const lut = new Uint8Array(256).map((_, i) => clip((i - lo) / (hi - lo), 0, 1) * 255); const o = new Uint8Array(g.length); for (let i = 0; i < g.length; i++) o[i] = lut[g[i]]; return o; }
function estimateSkew(g, w, h) {
  const sc = 500 / w, nw = 500, nh = M.max(8, M.round(h * sc)), s = resizeArea(g, w, h, nw, nh, 1), u8 = Uint8Array.from(s, v => M.round(v)), t = otsu(u8);
  const mx = M.trunc(nw * .06), my = M.trunc(nh * .06), iw = nw - 2 * mx, ih = nh - 2 * my, m = new Uint8Array(iw * ih);
  for (let y = 0; y < ih; y++) for (let x = 0; x < iw; x++) m[y * iw + x] = u8[(y + my) * nw + x + mx] <= t ? 1 : 0;
  let best = 0, bs = -1; const cx = iw / 2, cy = ih / 2;
  for (let a = -40; a <= 40; a++) {
    const ang = a * 0.125 * M.PI / 180, c = M.cos(ang), s2 = M.sin(ang), rows = new Float64Array(ih);
    for (let y = 0; y < ih; y++) for (let x = 0; x < iw; x++) { if (!m[y * iw + x]) continue; const dx = x - cx, dy = y - cy, ry = M.round(cy + (-dx * s2 + dy * c)); if (ry >= 0 && ry < ih) rows[ry]++; }
    let sc2 = 0; for (let i = 1; i < ih; i++) { const d = rows[i] - rows[i - 1]; sc2 += d * d; } if (sc2 > bs) { bs = sc2; best = a * 0.125; }
  }
  return best; // độ, cùng quy ước: dương = cần xoay trang theo chiều kim đồng hồ... (kiểm thử bằng ảnh xoay)
}
function rotateGray(g, w, h, deg) { // xoay quanh tâm, nền trắng
  const a = -deg * M.PI / 180, c = M.cos(a), s = M.sin(a), cx = w / 2, cy = h / 2, o = new Uint8Array(w * h).fill(255);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const dx = x - cx, dy = y - cy, sx = c * dx - s * dy + cx, sy = s * dx + c * dy + cy; if (sx >= 0 && sx <= w - 1.001 && sy >= 0 && sy <= h - 1.001) { const xi = M.trunc(sx), yi = M.trunc(sy), fx = sx - xi, fy = sy - yi, i = yi * w + xi; o[y * w + x] = g[i] * (1 - fx) * (1 - fy) + g[i + 1] * fx * (1 - fy) + g[i + w] * (1 - fx) * fy + g[i + w + 1] * fx * fy; } }
  return o;
}
function cropToContent(bw, W, H, marginPx) {
  const ink = new Uint8Array(W * H); for (let i = 0; i < ink.length; i++) ink[i] = bw[i] === 0 ? 1 : 0;
  const c = ccl(ink, W, H), minArea = M.max(8, M.trunc(W * H * 3e-6)), L = [], T = [], R = [], B = [], rm = new Uint8Array(c.n); let nrm = 0;
  for (let i = 1; i < c.n; i++) { const touches = c.x0[i] <= 1 || c.y0[i] <= 1 || c.x1[i] >= W - 2 || c.y1[i] >= H - 2; if (touches) { if (nrm < 300) { rm[i] = 1; nrm++; } } else if (c.area[i] >= minArea) { L.push(c.x0[i]); T.push(c.y0[i]); R.push(c.x1[i] + 1); B.push(c.y1[i] + 1); } }
  if (nrm) for (let i = 0; i < W * H; i++) if (rm[c.labels[i]] && c.labels[i]) bw[i] = 255;
  if (L.length < 5) return { data: bw, w: W, h: H };
  const q = M.min(1, M.trunc(L.length * .001)), srt = a => Int32Array.from(a).sort(), l = srt(L)[q], t = srt(T)[q], r = srt(R)[R.length - 1 - q], b = srt(B)[B.length - 1 - q], m = marginPx;
  const PW = W + 2 * m, PH = H + 2 * m, x0 = l, y0 = t, x1 = M.min(PW, r + 2 * m), y1 = M.min(PH, b + 2 * m);
  if (x1 - x0 < 50 || y1 - y0 < 50) return { data: bw, w: W, h: H };
  const ow = x1 - x0, oh = y1 - y0, out = new Uint8Array(ow * oh).fill(255);
  for (let y = 0; y < oh; y++) for (let x = 0; x < ow; x++) { const sx = x + x0 - m, sy = y + y0 - m; if (sx >= 0 && sx < W && sy >= 0 && sy < H) out[y * ow + x] = bw[sy * W + sx]; }
  return { data: out, w: ow, h: oh };
}
/** mode: 'bw' | 'gray' | 'color'. Trả {w,h,gray:Uint8Array} hoặc {w,h,rgba}. */
function cleanPage(page, ppc, o) {
  if (o.mode === 'color') return { w: page.w, h: page.h, rgba: page.data };
  const ix = M.trunc(page.w * .008), iy = M.trunc(page.h * .008), w = page.w - 2 * ix, h = page.h - 2 * iy, g0 = grayOf(page), g = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) g[y * w + x] = g0[(y + iy) * page.w + x + ix];
  let gray = levels(flatten(g, w, h));
  if (o.deskew !== false) { const a = estimateSkew(gray, w, h); if (M.abs(a) > 0.15) gray = rotateGray(gray, w, h, a); }
  if (o.mode === 'gray') return { w, h, gray };
  const t = otsu(gray), bw = new Uint8Array(w * h); for (let i = 0; i < bw.length; i++) bw[i] = gray[i] > t ? 255 : 0;
  if (o.cropContent === false) return { w, h, gray: bw };
  const r = cropToContent(bw, w, h, M.max(0, M.trunc(o.marginCm * ppc))); return { w: r.w, h: r.h, gray: r.data };
}
function mergeSide(a, b) { // ghép 2 kết quả cleanPage cạnh nhau, đệm trắng cho cùng chiều cao
  const hh = M.max(a.h, b.h), w = a.w + b.w;
  if (a.gray) { const o = new Uint8Array(w * hh).fill(255);
    for (const [p, ox] of [[a, 0], [b, a.w]]) { const top = (hh - p.h) >> 1; for (let y = 0; y < p.h; y++) o.set(p.gray.subarray(y * p.w, (y + 1) * p.w), (y + top) * w + ox); }
    return { w, h: hh, gray: o }; }
  const o = new Uint8ClampedArray(w * hh * 4).fill(255);
  for (const [p, ox] of [[a, 0], [b, a.w]]) { const top = (hh - p.h) >> 1; for (let y = 0; y < p.h; y++) o.set(p.rgba.subarray(y * p.w * 4, (y + 1) * p.w * 4), ((y + top) * w + ox) * 4); }
  return { w, h: hh, rgba: o };
}
/** Toàn bộ: ảnh -> danh sách kết quả (1 hoặc 2 trang, hoặc 1 ảnh ghép). */
function processImage(img, o) {
  const r = splitSpread(img, o.spreadW, o.spreadH); if (!r) return null;
  let outs = r.pages.map(p => cleanPage(p, r.ppc, o));
  if (o.splitMode === 'single') outs = [mergeSide(outs[0], outs[1])];
  else if (o.merge) outs = [mergeSide(outs[0], outs[1])];
  return { outs, ppc: r.ppc, quad: r.quad, mats: r.mats };
}
return { toLab, textPoints, detectQuad, findCut, splitSpread, cleanPage, mergeSide, processImage, perspective, warp, resizeRGBA, resizeArea, _:{ccl,otsu,morph,gauss} };
})();
if (typeof module !== 'undefined') module.exports = BookCore;
