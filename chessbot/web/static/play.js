// Play page: clickable board, legal-move highlighting, promotion, history.
"use strict";

const GLYPH = { k: "♚", q: "♛", r: "♜", b: "♝", n: "♞", p: "♟" };
const FILES = "abcdefgh";

let state = null;          // last server state
let flipped = false;       // board orientation (true = black at bottom)
let selected = null;       // selected square name, e.g. "e2"
let busy = false;          // request in flight
let pendingPromo = null;   // {from, to} awaiting promotion choice

const boardEl = document.getElementById("board");
const statusEl = document.getElementById("status");
const warnEl = document.getElementById("warning");
const errEl = document.getElementById("error");
const historyEl = document.getElementById("history");
const promoEl = document.getElementById("promo");
const botNameEl = document.getElementById("bot-name");
const form = document.getElementById("new-game");
const ckptSel = document.getElementById("checkpoint");
const botSel = document.getElementById("bot-kind");

function parseFen(fen) {
  // Returns {sq: "wP"} map for occupied squares.
  const pieces = {};
  const rows = fen.split(" ")[0].split("/");
  rows.forEach((row, i) => {
    const rank = 8 - i;
    let file = 0;
    for (const ch of row) {
      if (/\d/.test(ch)) { file += Number(ch); continue; }
      const color = ch === ch.toUpperCase() ? "w" : "b";
      pieces[FILES[file] + rank] = color + ch.toLowerCase();
      file++;
    }
  });
  return pieces;
}

function humanTurn() {
  return state && !state.no_game && !state.game_over && state.turn === state.human_color && !busy;
}

function movesFrom(sq) {
  if (!state) return [];
  return state.legal_moves.filter((m) => m.slice(0, 2) === sq);
}

function kingSquare(pieces, color) {
  for (const [sq, p] of Object.entries(pieces)) if (p === color + "k") return sq;
  return null;
}

function render(pieces) {
  pieces = pieces || parseFen(state ? state.fen : "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w");
  boardEl.textContent = "";
  const targets = new Set(selected ? movesFrom(selected).map((m) => m.slice(2, 4)) : []);
  const last = state && state.last_move ? [state.last_move.slice(0, 2), state.last_move.slice(2, 4)] : [];
  const checkSq = state && state.check ? kingSquare(pieces, state.turn === "white" ? "w" : "b") : null;
  for (let r = 0; r < 8; r++) {
    for (let f = 0; f < 8; f++) {
      const rank = flipped ? r + 1 : 8 - r;
      const file = flipped ? 7 - f : f;
      const sq = FILES[file] + rank;
      const dark = (file + rank) % 2 === 1;   // a1 (file 0, rank 1) is dark
      const p = pieces[sq];
      const cls = ["sq", dark ? "dark" : "light"];
      if (sq === selected) cls.push("sel");
      if (last.includes(sq)) cls.push("last");
      if (sq === checkSq) cls.push("check");
      if (targets.has(sq)) cls.push("target");
      if (p) cls.push("occupied");
      const label = p ? `${sq} ${p[0] === "w" ? "white" : "black"} ${p[1]}` : sq;
      const btn = el("button", { type: "button", class: cls.join(" "), "data-square": sq, "aria-label": label,
                                 onclick: () => onSquare(sq) });
      if (p) btn.append(el("span", { class: "pc " + p[0], text: GLYPH[p[1]] }));
      if (r === 7) btn.append(el("span", { class: "coord file", text: FILES[file] }));
      if (f === 0) btn.append(el("span", { class: "coord rank", text: String(rank) }));
      boardEl.append(btn);
    }
  }
}

function describeResult(s) {
  const term = (s.termination || "").replace(/_/g, " ");
  if (s.result === "1/2-1/2") return `Draw (${term}).`;
  const winner = s.result === "1-0" ? "white" : "black";
  const you = winner === s.human_color ? "You win" : "The bot wins";
  return `${you}: ${s.result} by ${term}.`;
}

function renderSide() {
  if (!state || state.no_game) {
    statusEl.textContent = "Start a new game.";
  } else if (state.game_over) {
    statusEl.textContent = describeResult(state);
  } else if (busy) {
    statusEl.textContent = "Bot is thinking…";
  } else if (state.turn === state.human_color) {
    statusEl.textContent = "Your move" + (state.check ? " — you are in check!" : ".");
  } else {
    statusEl.textContent = "Bot to move.";
  }
  showNotice(warnEl, state && state.warning);
  botNameEl.textContent = state && state.bot_name ? `Opponent: ${state.bot_name}` : "";
  historyEl.textContent = "";
  const san = (state && state.history_san) || [];
  for (let i = 0; i < san.length; i += 2) {
    historyEl.append(el("li", {}, el("span", { text: san[i] }), el("span", { text: san[i + 1] || "" })));
  }
  historyEl.scrollTop = historyEl.scrollHeight;
}

function setState(s) {
  state = s;
  selected = null;
  render();
  renderSide();
}

function optimisticMove(uci) {
  // Show the human move immediately while the server computes the bot reply.
  const pieces = parseFen(state.fen);
  const from = uci.slice(0, 2), to = uci.slice(2, 4);
  const p = pieces[from];
  if (!p) return pieces;
  delete pieces[from];
  if (p[1] === "p" && from[0] !== to[0] && !pieces[to]) delete pieces[to[0] + from[1]]; // en passant
  if (p[1] === "k" && Math.abs(FILES.indexOf(from[0]) - FILES.indexOf(to[0])) === 2) { // castling
    const rank = from[1];
    const [rf, rt] = to[0] === "g" ? ["h", "f"] : ["a", "d"];
    pieces[rt + rank] = pieces[rf + rank];
    delete pieces[rf + rank];
  }
  pieces[to] = uci.length > 4 ? p[0] + uci[4] : p;
  return pieces;
}

async function sendMove(uci) {
  busy = true;
  showNotice(errEl, null);
  selected = null;
  state = Object.assign({}, state, { last_move: uci, check: false });
  render(optimisticMove(uci));
  renderSide();
  try {
    const s = await api("POST", "/api/game/move", { uci });
    busy = false;
    setState(s);
  } catch (e) {
    busy = false;
    showNotice(errEl, e.message);
    if (e.data && e.data.state) setState(e.data.state); else refresh();
  }
}

function onSquare(sq) {
  if (!humanTurn()) return;
  const pieces = parseFen(state.fen);
  const mine = (p) => p && p[0] === (state.human_color === "white" ? "w" : "b");
  if (selected) {
    const candidates = movesFrom(selected).filter((m) => m.slice(2, 4) === sq);
    if (candidates.length === 1) return sendMove(candidates[0]);
    if (candidates.length > 1) {         // promotion: several moves to the same square
      pendingPromo = { from: selected, to: sq };
      promoEl.hidden = false;
      return;
    }
  }
  selected = mine(pieces[sq]) && sq !== selected ? sq : null;
  render(pieces);
}

promoEl.addEventListener("click", (ev) => {
  const piece = ev.target.closest("button[data-piece]");
  if (!piece || !pendingPromo) return;
  const uci = pendingPromo.from + pendingPromo.to + piece.dataset.piece;
  promoEl.hidden = true;
  pendingPromo = null;
  sendMove(uci);
});
document.getElementById("promo-cancel").addEventListener("click", () => {
  promoEl.hidden = true; pendingPromo = null; selected = null; render();
});

document.getElementById("flip").addEventListener("click", () => { flipped = !flipped; render(); });

form.addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const fd = new FormData(form);
  const body = { color: fd.get("color"), bot: fd.get("bot"), checkpoint: fd.get("checkpoint") || null };
  busy = true;
  showNotice(errEl, null);
  renderSide();
  try {
    const s = await api("POST", "/api/game/new", body);
    flipped = s.human_color === "black";
    busy = false;
    setState(s);
  } catch (e) {
    busy = false;
    showNotice(errEl, e.message);
    renderSide();
  }
});

async function loadCheckpoints() {
  ckptSel.textContent = "";
  let selectedSpec = "latest";
  let models = [];
  try {
    const data = await api("GET", "/api/models");
    models = data.models || [];
    selectedSpec = data.selected === null ? "" : data.selected;
  } catch (e) { /* listing unavailable: keep defaults */ }
  const opts = [["latest", "latest"], ["", "untrained network"]];
  for (const m of models) if (m.name !== "latest.pt") opts.push([m.name, `${m.name} (iter ${m.iteration ?? "?"})`]);
  if (!opts.some(([v]) => v === selectedSpec)) opts.push([selectedSpec, selectedSpec]);
  for (const [v, label] of opts) ckptSel.append(el("option", { value: v, text: label, selected: v === selectedSpec }));
}

async function loadDefaults() {
  try {
    const cfg = await api("GET", "/api/config");
    botSel.value = cfg.ui.bot_player;
  } catch (e) { /* ignore */ }
}

function updateCkptEnabled() {
  const usesNet = botSel.value === "mcts" || botSel.value === "alphabeta_nn";
  ckptSel.disabled = !usesNet;
}
botSel.addEventListener("change", updateCkptEnabled);

async function refresh() {
  try { setState(await api("GET", "/api/game/state")); } catch (e) { showNotice(errEl, e.message); }
}

(async function init() {
  render();
  await Promise.all([loadCheckpoints(), loadDefaults()]);
  updateCkptEnabled();
  await refresh();
  if (state && state.human_color) flipped = state.human_color === "black";
  render();
})();
