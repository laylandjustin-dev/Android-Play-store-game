// Verifies browser/bot.js against PyTorch reference outputs in model.json.
//   npm i chess.js@0.10.3   (in any directory on NODE_PATH)
//   node browser/test_bot.mjs browser/model.json
import { createRequire } from "module";
import fs from "fs";

const require = createRequire(import.meta.url);
globalThis.Chess = require("chess.js").Chess;
globalThis.performance ??= { now: () => Date.now() };
const { Game, encode, moveIndex, Net, MCTS, AlphaBeta } = require("./bot.js").ChessBot;

const spec = JSON.parse(fs.readFileSync(process.argv[2] || new URL("./model.json", import.meta.url)));
const net = new Net(spec.net);
let failures = 0;
const check = (cond, msg) => { if (!cond) { failures++; console.error("FAIL:", msg); } };

let maxPriorErr = 0, maxValueErr = 0, maxEvalErr = 0;
for (const t of spec.tests) {
  const g = new Game(t.fen);
  const legal = g.legal();
  check(legal.length === Object.keys(t.indices).length, `${t.fen}: legal move count`);
  const x = encode(g, legal);
  for (let p = 0; p < 20; p++) {
    let s = 0;
    for (let i = 0; i < 64; i++) s += x[p * 64 + i];
    check(Math.abs(s - t.planes_sum[p]) < 1e-4, `${t.fen}: plane ${p} sum ${s} != ${t.planes_sum[p]}`);
  }
  for (const m of legal) {
    const uci = m.from + m.to + (m.promotion || "");
    check(moveIndex(m, g.turn()) === t.indices[uci], `${t.fen}: index of ${uci}`);
  }
  const { priors, value } = net.predict(g, legal);
  legal.forEach((m, i) => {
    const uci = m.from + m.to + (m.promotion || "");
    maxPriorErr = Math.max(maxPriorErr, Math.abs(priors[i] - t.priors[uci]));
  });
  maxValueErr = Math.max(maxValueErr, Math.abs(value - t.value));
  const ab = new AlphaBeta(spec.eval);
  maxEvalErr = Math.max(maxEvalErr, Math.abs(ab.evaluate(g) - t.simple_eval));
}
check(maxPriorErr < 1e-4, `prior error ${maxPriorErr}`);
check(maxValueErr < 1e-4, `value error ${maxValueErr}`);
check(maxEvalErr < 1e-6, `eval error ${maxEvalErr}`);

// MCTS must reproduce PyTorch MCTS visit counts exactly (same algorithm, deterministic).
let mctsCompared = 0;
for (const t of spec.tests.filter((t) => t.mcts_visits)) {
  const r = await new MCTS(net, { simulations: spec.mcts_sims }).search(new Game(t.fen));
  const got = {};
  for (const m of r.allVisits) if (m.visits) got[m.uci] = m.visits;
  check(JSON.stringify(Object.entries(got).sort()) === JSON.stringify(Object.entries(t.mcts_visits).sort()),
        `${t.fen}: MCTS visits differ\n  js ${JSON.stringify(got)}\n  py ${JSON.stringify(t.mcts_visits)}`);
  mctsCompared++;
}

// Search sanity: alpha-beta must find mate in one. (The trained net's MCTS
// misses this one, exactly like the Python MCTS: prior of Ra8# is ~2e-5.)
const mate1 = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1";   // Ra8#
const ab = await new AlphaBeta(spec.eval, { depth: 3, timeLimit: 10 }).search(new Game(mate1));
check(ab.move.san === "Ra8#", `alpha-beta mate in 1: got ${ab.move.san}`);

// Legality / board restoration over a short self-play game.
const g = new Game();
const mcts = new MCTS(net, { simulations: 16 });
for (let ply = 0; ply < 30 && g.terminal() === null; ply++) {
  const fen = g.chess.fen();
  const r = await (ply % 2 ? mcts.search(g) : new AlphaBeta(spec.eval, { depth: 2 }).search(g));
  check(g.chess.fen() === fen, "search must restore the board");
  check(g.legal().some((m) => m.san === r.move.san), `illegal move ${r.move.san}`);
  g.push(r.move);
}

const t0 = performance.now();
for (let i = 0; i < 50; i++) net.predict(new Game());
const msPerEval = (performance.now() - t0) / 50;

console.log(JSON.stringify({ positions: spec.tests.length, maxPriorErr, maxValueErr, maxEvalErr,
  abMate: ab.move.san, abNodes: ab.nodes, mctsCompared, msPerEval: +msPerEval.toFixed(2) }));
if (failures) { console.error(`${failures} failure(s)`); process.exit(1); }
console.log("OK");
