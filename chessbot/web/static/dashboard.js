// Dashboard: training controls, live status/log, metrics charts.
"use strict";

const chartsEl = document.getElementById("charts");
const noDataEl = document.getElementById("no-data");
const badge = document.getElementById("train-badge");
const startBtn = document.getElementById("train-start");
const stopBtn = document.getElementById("train-stop");
const summaryEl = document.getElementById("train-summary");
const trainErrEl = document.getElementById("train-error");
const logEl = document.getElementById("train-log");
const itersInput = document.getElementById("iterations");

const CHARTS = [
  { id: "loss", title: "Training loss", sub: "Mean over the iteration's gradient steps",
    series: [{ key: "total_loss", label: "total", color: "--series-1" },
             { key: "policy_loss", label: "policy", color: "--series-2" },
             { key: "value_loss", label: "value", color: "--series-3" }], zeroBased: true },
  { id: "len", title: "Average self-play game length", sub: "plies per game",
    series: [{ key: "avg_game_length", label: "plies", color: "--series-1" }], zeroBased: true },
  { id: "wdl", title: "Self-play results per iteration", sub: "games won by white, drawn, won by black",
    series: [{ key: "white_wins", label: "white wins", color: "--series-1" },
             { key: "draws", label: "draws", color: "--series-2" },
             { key: "black_wins", label: "black wins", color: "--series-3" }], zeroBased: true },
  { id: "buf", title: "Replay buffer size", sub: "positions stored",
    series: [{ key: "buffer_size", label: "positions", color: "--series-1" }], zeroBased: true },
];

// Evaluation records (type == "eval", written by `chessbot evaluate` / train --eval-every)
// carry score (0..1) and an Elo-difference estimate. They have different scales, so
// each present key gets its own single-axis chart.
const EVAL_KEYS = [["score", "Evaluation score vs opponent", "(wins + draws/2) / games"],
                   ["elo", "Estimated Elo difference vs opponent", "positive = candidate stronger"]];

const charts = {};
const evalCharts = {};
let lastRunning = null;

function ensureCharts() {
  if (Object.keys(charts).length) return;
  for (const c of CHARTS) {
    charts[c.id] = new LineChart(chartsEl, { title: c.title, sub: c.sub, zeroBased: c.zeroBased,
      integerX: true, series: c.series.map((s) => ({ label: s.label, color: s.color })) });
  }
}

function seriesOf(records, key) {
  return records.filter((r) => typeof r.iteration === "number" && typeof r[key] === "number")
    .map((r) => [r.iteration, r[key]]);
}

function renderTable(table, records) {
  const skip = new Set(["type"]);
  const keys = [];
  for (const r of records) for (const k of Object.keys(r)) {
    if (!skip.has(k) && !keys.includes(k) && (r[k] === null || typeof r[k] !== "object")) keys.push(k);
  }
  table.textContent = "";
  const thead = el("thead", {}, el("tr", {}, ...keys.map((k) => el("th", { text: k }))));
  const tbody = el("tbody");
  for (const r of records) {
    tbody.append(el("tr", {}, ...keys.map((k) => el("td", { class: typeof r[k] === "number" ? "num" : null, text: fmtNum(r[k]) }))));
  }
  table.append(thead, tbody);
}

function renderLast(rec) {
  const table = document.getElementById("last-table");
  table.textContent = "";
  for (const [k, v] of Object.entries(rec)) {
    if (v !== null && typeof v === "object") continue;
    table.append(el("tr", {}, el("th", { text: k }), el("td", { class: typeof v === "number" ? "num" : null, text: fmtNum(v) })));
  }
}

async function loadMetrics() {
  let records = [];
  try { records = await api("GET", "/api/metrics"); } catch (e) { records = []; }
  const train = records.filter((r) => (r.type || "train") === "train");
  const evals = records.filter((r) => r.type === "eval");
  document.getElementById("metrics-count").textContent =
    records.length ? `${train.length} training record(s), ${evals.length} evaluation record(s)` : "";
  noDataEl.hidden = train.length > 0 || evals.length > 0;
  if (train.length) {
    ensureCharts();
    chartsEl.hidden = false;
    for (const c of CHARTS) charts[c.id].setData(c.series.map((s) => seriesOf(train, s.key)));
    document.getElementById("last-section").hidden = false;
    renderLast(train[train.length - 1]);
  } else {
    chartsEl.hidden = !evals.length;
    document.getElementById("last-section").hidden = true;
  }
  const evalSection = document.getElementById("eval-section");
  evalSection.hidden = !evals.length;
  if (evals.length) {
    for (const [key, title, sub] of EVAL_KEYS) {
      const pts = seriesOf(evals, key).filter(([x]) => x >= 0);
      if (!pts.length) continue;
      if (!evalCharts[key]) {
        evalCharts[key] = new LineChart(chartsEl, { title, sub, integerX: true,
          series: [{ label: key, color: "--series-1" }] });
      }
      evalCharts[key].setData([pts]);
    }
    renderTable(document.getElementById("eval-table"), evals);
  }
}

function renderStatus(st) {
  const running = st.running;
  badge.textContent = running ? (st.stop_requested ? "stopping" : "running") : (st.error ? "error" : "idle");
  badge.className = "badge" + (running ? " on" : st.error ? " bad" : "");
  startBtn.disabled = running;
  stopBtn.disabled = !running || st.stop_requested;
  const parts = [];
  if (st.iteration !== null && st.iteration !== undefined) parts.push(`model iteration ${st.iteration}`);
  if (st.target_iterations) parts.push(`${st.completed_iterations}/${st.target_iterations} iterations this session`);
  if (st.started_at) parts.push(`started ${st.started_at}`);
  if (!running && st.finished_at) parts.push(`finished ${st.finished_at}`);
  summaryEl.textContent = parts.join(" · ") || "Training has not been started in this server session.";
  showNotice(trainErrEl, st.error ? `Last error: ${st.error}` : null);
  const atBottom = logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 4;
  logEl.textContent = st.log && st.log.length ? st.log.join("\n") : "No log output yet.";
  if (atBottom) logEl.scrollTop = logEl.scrollHeight;
}

async function pollStatus() {
  let st = null;
  try { st = await api("GET", "/api/training/status"); renderStatus(st); } catch (e) { /* server gone */ }
  const running = st ? st.running : false;
  if (running || lastRunning) await loadMetrics();   // keep charts live; final refresh after stop
  lastRunning = running;
  setTimeout(pollStatus, running ? 2000 : 6000);
}

document.getElementById("train-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  showNotice(trainErrEl, null);
  try {
    const st = await api("POST", "/api/training/start", { iterations: Number(itersInput.value) });
    lastRunning = true;
    renderStatus(st);
  } catch (e) {
    showNotice(trainErrEl, e.message);
  }
});

stopBtn.addEventListener("click", async () => {
  try { renderStatus(await api("POST", "/api/training/stop")); } catch (e) { showNotice(trainErrEl, e.message); }
});

(async function init() {
  try { const cfg = await api("GET", "/api/config"); itersInput.value = cfg.training.iterations; } catch (e) { /* ignore */ }
  await loadMetrics();
  pollStatus();
})();
