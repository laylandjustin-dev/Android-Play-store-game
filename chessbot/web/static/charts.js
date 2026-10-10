// Minimal dependency-free line charts on <canvas>, with crosshair tooltip.
"use strict";

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || "#888";
}

function niceTicks(min, max, count) {
  if (!isFinite(min) || !isFinite(max)) return [0, 1];
  if (min === max) { const pad = Math.abs(min) * 0.1 || 1; min -= pad; max += pad; }
  const span = max - min;
  const step0 = span / Math.max(1, count);
  const mag = Math.pow(10, Math.floor(Math.log10(step0)));
  const norm = step0 / mag;
  const step = (norm >= 5 ? 10 : norm >= 2 ? 5 : norm >= 1 ? 2 : 1) * mag;
  const lo = Math.floor(min / step) * step;
  const hi = Math.ceil(max / step) * step;
  const ticks = [];
  for (let v = lo; v <= hi + step * 1e-9; v += step) ticks.push(Math.abs(v) < step * 1e-9 ? 0 : v);
  return ticks;
}

function tickLabel(v) {
  const a = Math.abs(v);
  if (a >= 1e6) return (v / 1e6).toFixed(1).replace(/\.0$/, "") + "M";
  if (a >= 1e4) return (v / 1e3).toFixed(0) + "k";
  if (a >= 1000) return (v / 1e3).toFixed(1).replace(/\.0$/, "") + "k";
  if (Number.isInteger(v)) return String(v);
  return String(+v.toPrecision(3));
}

class LineChart {
  /**
   * @param {HTMLElement} parent  container to append the card to
   * @param {object} opts  {title, sub, series:[{label, color}], zeroBased, xLabel, integerY}
   */
  constructor(parent, opts) {
    this.opts = opts;
    this.data = opts.series.map(() => []);
    this.card = el("div", { class: "chart-card" });
    this.card.append(el("h3", { text: opts.title }));
    if (opts.sub) this.card.append(el("p", { class: "sub", text: opts.sub }));
    if (opts.series.length > 1) {
      const legend = el("div", { class: "legend" });
      for (const s of opts.series) {
        const sw = el("span", { class: "sw" });
        sw.style.background = cssVar(s.color);
        sw.dataset.color = s.color;
        legend.append(el("span", {}, sw, s.label));
      }
      this.card.append(legend);
    }
    this.canvas = el("canvas", { role: "img", "aria-label": opts.title });
    this.tooltip = el("div", { class: "tooltip", hidden: true });
    this.card.append(this.canvas, this.tooltip);
    parent.append(this.card);
    this.hoverIdx = null;
    this.canvas.addEventListener("mousemove", (e) => this.onHover(e));
    this.canvas.addEventListener("mouseleave", () => { this.hoverIdx = null; this.tooltip.hidden = true; this.draw(); });
    window.addEventListener("resize", () => this.draw());
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    if (mq.addEventListener) mq.addEventListener("change", () => this.draw());
  }

  /** data: array (one per series) of [x, y] pairs; null/NaN y values are skipped. */
  setData(data) {
    this.data = data.map((pts) => pts.filter(([x, y]) => typeof y === "number" && isFinite(y) && typeof x === "number"));
    this.xs = [...new Set(this.data.flat().map((p) => p[0]))].sort((a, b) => a - b);
    this.draw();
  }

  layout() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const w = Math.max(200, rect.width), h = Math.max(120, rect.height);
    if (this.canvas.width !== Math.round(w * dpr) || this.canvas.height !== Math.round(h * dpr)) {
      this.canvas.width = Math.round(w * dpr);
      this.canvas.height = Math.round(h * dpr);
    }
    const ctx = this.canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return { ctx, w, h, pad: { l: 46, r: 12, t: 8, b: 28 } };
  }

  scales(w, h, pad) {
    const all = this.data.flat();
    let xmin = Math.min(...all.map((p) => p[0])), xmax = Math.max(...all.map((p) => p[0]));
    if (xmin === xmax) { xmin -= 1; xmax += 1; }
    let ymin = Math.min(...all.map((p) => p[1])), ymax = Math.max(...all.map((p) => p[1]));
    if (this.opts.zeroBased) ymin = Math.min(0, ymin);
    const yt = niceTicks(ymin, ymax, 4);
    const y0 = yt[0], y1 = yt[yt.length - 1];
    const xt = niceTicks(xmin, xmax, Math.min(6, Math.max(1, Math.round(xmax - xmin))))
      .filter((v) => v >= xmin && v <= xmax && (!this.opts.integerX || Number.isInteger(v)));
    const sx = (x) => pad.l + ((x - xmin) / (xmax - xmin)) * (w - pad.l - pad.r);
    const sy = (y) => h - pad.b - ((y - y0) / (y1 - y0 || 1)) * (h - pad.t - pad.b);
    return { sx, sy, yt, xt };
  }

  draw() {
    const { ctx, w, h, pad } = this.layout();
    ctx.clearRect(0, 0, w, h);
    const textMuted = cssVar("--muted");
    ctx.font = "11px system-ui, sans-serif";
    if (!this.data.some((d) => d.length)) {
      ctx.fillStyle = textMuted;
      ctx.textAlign = "center";
      ctx.fillText("No data yet", w / 2, h / 2);
      return;
    }
    const { sx, sy, yt, xt } = this.scales(w, h, pad);
    this._sx = sx; this._pad = pad; this._w = w;
    // grid + y labels
    ctx.strokeStyle = cssVar("--grid");
    ctx.lineWidth = 1;
    ctx.fillStyle = textMuted;
    ctx.textAlign = "right";
    ctx.textBaseline = "middle";
    for (const v of yt) {
      const y = Math.round(sy(v)) + 0.5;
      ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(w - pad.r, y); ctx.stroke();
      ctx.fillText(tickLabel(v), pad.l - 6, y);
    }
    // x axis
    ctx.strokeStyle = cssVar("--axis");
    ctx.beginPath(); ctx.moveTo(pad.l, h - pad.b + 0.5); ctx.lineTo(w - pad.r, h - pad.b + 0.5); ctx.stroke();
    ctx.textAlign = "center";
    ctx.textBaseline = "top";
    for (const v of xt) ctx.fillText(tickLabel(v), sx(v), h - pad.b + 5);
    ctx.textAlign = "right";
    ctx.fillText(this.opts.xLabel || "iteration", w - pad.r, h - 12);
    // crosshair
    if (this.hoverIdx !== null && this.xs[this.hoverIdx] !== undefined) {
      const x = Math.round(sx(this.xs[this.hoverIdx])) + 0.5;
      ctx.strokeStyle = cssVar("--axis");
      ctx.beginPath(); ctx.moveTo(x, pad.t); ctx.lineTo(x, h - pad.b); ctx.stroke();
    }
    // series
    const surface = cssVar("--surface");
    this.opts.series.forEach((s, i) => {
      const pts = this.data[i];
      if (!pts.length) return;
      const color = cssVar(s.color);
      ctx.strokeStyle = color;
      ctx.lineWidth = 2;
      ctx.lineJoin = "round";
      ctx.beginPath();
      pts.forEach(([x, y], j) => (j ? ctx.lineTo(sx(x), sy(y)) : ctx.moveTo(sx(x), sy(y))));
      ctx.stroke();
      const showMarkers = pts.length <= 25;
      pts.forEach(([x, y]) => {
        const hovered = this.hoverIdx !== null && x === this.xs[this.hoverIdx];
        if (!showMarkers && !hovered) return;
        ctx.beginPath();
        ctx.arc(sx(x), sy(y), hovered ? 5 : 4, 0, Math.PI * 2);
        ctx.fillStyle = color; ctx.fill();
        ctx.lineWidth = 2; ctx.strokeStyle = surface; ctx.stroke();
      });
    });
  }

  onHover(e) {
    if (!this.xs || !this.xs.length || !this._sx) return;
    const rect = this.canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    let best = 0, bestD = Infinity;
    this.xs.forEach((x, i) => { const d = Math.abs(this._sx(x) - mx); if (d < bestD) { bestD = d; best = i; } });
    this.hoverIdx = best;
    this.draw();
    const x = this.xs[best];
    this.tooltip.textContent = "";
    this.tooltip.append(el("div", { text: `${this.opts.xLabel || "iteration"} ${x}` }));
    this.opts.series.forEach((s, i) => {
      const p = this.data[i].find((q) => q[0] === x);
      if (!p) return;
      const sw = el("span", { class: "sw" });
      sw.style.background = cssVar(s.color);
      this.tooltip.append(el("div", {}, sw, `${s.label}: ${fmtNum(p[1])}`));
    });
    this.tooltip.hidden = false;
    const cardRect = this.card.getBoundingClientRect();
    const tx = e.clientX - cardRect.left + 12;
    const ty = e.clientY - cardRect.top + 12;
    const maxX = cardRect.width - this.tooltip.offsetWidth - 4;
    this.tooltip.style.left = Math.max(4, Math.min(tx, maxX)) + "px";
    this.tooltip.style.top = ty + "px";
  }
}
