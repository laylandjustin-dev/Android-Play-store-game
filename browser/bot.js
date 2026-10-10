/*
 * In-browser port of the chessbot players.
 *
 *  - encode():   chessbot/ml/encoding.py (20 planes, AlphaZero 73x64 moves)
 *  - Net:        chessbot/ml/model.py forward pass, BatchNorm folded at export
 *  - MCTS:       chessbot/ml/mcts.py (PUCT, sequential, no root noise)
 *  - AlphaBeta:  a reduced port of chessbot/engine (material + tapered PST +
 *                bishop pair, negamax alpha-beta, quiescence, MVV-LVA)
 *
 * Rules come from chess.js (global `Chess`). Verified against PyTorch
 * reference outputs by browser/test_bot.mjs.
 */
(function (exportTo) {
  "use strict";

  const PIECES = "pnbrqk";
  const QUEEN_DIRS = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];
  const KNIGHT = [[2, 1], [1, 2], [-1, 2], [-2, 1], [-2, -1], [-1, -2], [1, -2], [2, -1]];
  const DELTA_PLANE = new Map();
  QUEEN_DIRS.forEach(([dr, df], d) => {
    for (let dist = 1; dist <= 7; dist++) DELTA_PLANE.set(dr * dist + "," + df * dist, d * 7 + dist - 1);
  });
  KNIGHT.forEach(([dr, df], i) => DELTA_PLANE.set(dr + "," + df, 56 + i));
  const UNDERPROMO = { n: 0, b: 1, r: 2 };

  const sqIndex = (alg) => (alg.charCodeAt(1) - 49) * 8 + (alg.charCodeAt(0) - 97);
  const sqName = (i) => String.fromCharCode(97 + (i & 7)) + String.fromCharCode(49 + (i >> 3));

  function b64ToFloat32(b64) {
    let bytes;
    if (typeof atob === "function") {
      const bin = atob(b64);
      bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    } else {
      bytes = new Uint8Array(Buffer.from(b64, "base64"));
    }
    return new Float32Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 4);
  }

  /* ---------------------------------------------------------------- game state */

  // Position key matching python-chess repetition semantics (ep only if capturable).
  function positionKey(game, legal) {
    const f = game.fen().split(" ");
    const moves = legal || game.moves({ verbose: true });
    const ep = moves.some((m) => m.flags.indexOf("e") >= 0) ? f[3] : "-";
    return f[0] + " " + f[1] + " " + f[2] + " " + ep;
  }

  // Wraps chess.js with repetition counts that survive push/pop.
  class Game {
    constructor(fen) {
      this.chess = fen ? new Chess(fen) : new Chess();
      this.counts = new Map();
      this.keys = [];
      this._record();
    }
    _record(legal) {
      const k = positionKey(this.chess, legal);
      this.keys.push(k);
      this.counts.set(k, (this.counts.get(k) || 0) + 1);
    }
    legal() { return this.chess.moves({ verbose: true }); }
    push(move) {
      const r = this.chess.move({ from: move.from, to: move.to, promotion: move.promotion });
      if (!r) throw new Error("illegal move " + move.from + move.to);
      this._record();
      return r;
    }
    pop() {
      const k = this.keys.pop();
      const c = this.counts.get(k) - 1;
      if (c) this.counts.set(k, c); else this.counts.delete(k);
      return this.chess.undo();
    }
    repetitions() { return this.counts.get(this.keys[this.keys.length - 1]) || 0; }
    halfmove() { return parseInt(this.chess.fen().split(" ")[4], 10); }
    turn() { return this.chess.turn(); }
    // Python terminal_value(): -1 mated, 0 draw, null otherwise (side to move).
    terminal(legal) {
      legal = legal || this.legal();
      if (!legal.length) return this.chess.in_check() ? -1 : 0;
      if (this.chess.insufficient_material() || this.halfmove() >= 100 || this.repetitions() >= 3) return 0;
      return null;
    }
  }

  /* ------------------------------------------------------------------ encoding */

  function moveIndex(move, turn) {
    let frm = sqIndex(move.from), to = sqIndex(move.to);
    if (turn === "b") { frm ^= 56; to ^= 56; }
    const dr = (to >> 3) - (frm >> 3), df = (to & 7) - (frm & 7);
    let plane;
    if (move.promotion && move.promotion !== "q") plane = 64 + 3 * UNDERPROMO[move.promotion] + (df + 1);
    else plane = DELTA_PLANE.get(dr + "," + df);
    if (plane === undefined) throw new Error("cannot encode " + move.from + move.to);
    return plane * 64 + frm;
  }

  function encode(game, legal) {
    const x = new Float32Array(20 * 64);
    const c = game.chess, us = c.turn();
    const flip = us === "b" ? 56 : 0;
    const board = c.board();
    for (let row = 0; row < 8; row++) {
      for (let file = 0; file < 8; file++) {
        const p = board[row][file];
        if (!p) continue;
        const sq = ((7 - row) * 8 + file) ^ flip;
        const plane = PIECES.indexOf(p.type) + (p.color === us ? 0 : 6);
        x[plane * 64 + sq] = 1;
      }
    }
    const f = c.fen().split(" ");
    const cr = f[2];
    const [ok, oq, tk, tq] = us === "w" ? ["K", "Q", "k", "q"] : ["k", "q", "K", "Q"];
    const fill = (plane, v) => x.fill(v, plane * 64, plane * 64 + 64);
    if (cr.indexOf(ok) >= 0) fill(12, 1);
    if (cr.indexOf(oq) >= 0) fill(13, 1);
    if (cr.indexOf(tk) >= 0) fill(14, 1);
    if (cr.indexOf(tq) >= 0) fill(15, 1);
    legal = legal || game.legal();
    if (f[3] !== "-" && legal.some((m) => m.flags.indexOf("e") >= 0)) x[16 * 64 + (sqIndex(f[3]) ^ flip)] = 1;
    fill(17, Math.min(parseInt(f[4], 10) / 100, 1));
    if (game.repetitions() >= 2) fill(18, 1);
    fill(19, 1);
    return x;
  }

  /* ------------------------------------------------------------------- network */

  class Net {
    constructor(spec) {
      const T = (o) => ({ ...o, w: b64ToFloat32(o.w), b: b64ToFloat32(o.b) });
      this.trunk = spec.trunk.map(T);
      this.pconv = T(spec.policy_conv);
      this.pfc = T(spec.policy_fc);
      this.vconv = T(spec.value_conv);
      this.vfc1 = T(spec.value_fc1);
      this.vfc2 = T(spec.value_fc2);
      this.c = spec.channels;
    }
    static conv3(input, cin, layer) {
      const { w, b, cout } = layer;
      const out = new Float32Array(cout * 64);
      for (let co = 0; co < cout; co++) {
        const o = out.subarray(co * 64, co * 64 + 64);
        o.fill(b[co]);
        for (let ci = 0; ci < cin; ci++) {
          const inp = input.subarray(ci * 64, ci * 64 + 64);
          const wb = (co * cin + ci) * 9;
          for (let ky = 0; ky < 3; ky++) {
            for (let kx = 0; kx < 3; kx++) {
              const wv = w[wb + ky * 3 + kx];
              if (wv === 0) continue;
              const dy = ky - 1, dx = kx - 1;
              const y0 = Math.max(0, -dy), y1 = Math.min(8, 8 - dy);
              const x0 = Math.max(0, -dx), x1 = Math.min(8, 8 - dx);
              for (let y = y0; y < y1; y++) {
                const ro = y * 8, ri = (y + dy) * 8 + dx;
                for (let xx = x0; xx < x1; xx++) o[ro + xx] += wv * inp[ri + xx];
              }
            }
          }
        }
      }
      return out;
    }
    static conv1relu(input, cin, layer) {
      const { w, b, cout } = layer;
      const out = new Float32Array(cout * 64);
      for (let co = 0; co < cout; co++) {
        for (let s = 0; s < 64; s++) {
          let acc = b[co];
          for (let ci = 0; ci < cin; ci++) acc += w[co * cin + ci] * input[ci * 64 + s];
          out[co * 64 + s] = acc > 0 ? acc : 0;
        }
      }
      return out;
    }
    static linear(input, layer, only) {
      const { w, b } = layer, n = layer.in;
      const idx = only || null;
      const out = new Float32Array(idx ? idx.length : layer.out);
      for (let k = 0; k < out.length; k++) {
        const o = idx ? idx[k] : k;
        let acc = b[o];
        const base = o * n;
        for (let i = 0; i < n; i++) acc += w[base + i] * input[i];
        out[k] = acc;
      }
      return out;
    }
    // Returns {logits for `indices` (or all 4672), value in [-1, 1]}.
    forward(x, indices) {
      const relu = (a) => { for (let i = 0; i < a.length; i++) if (a[i] < 0) a[i] = 0; return a; };
      let h = relu(Net.conv3(x, 20, this.trunk[0]));
      for (let i = 1; i < this.trunk.length; i += 2) {
        const y = relu(Net.conv3(h, this.c, this.trunk[i]));
        const z = Net.conv3(y, this.c, this.trunk[i + 1]);
        for (let j = 0; j < z.length; j++) z[j] = Math.max(0, z[j] + h[j]);
        h = z;
      }
      const p = Net.conv1relu(h, this.c, this.pconv);
      const logits = Net.linear(p, this.pfc, indices);
      const v = Net.conv1relu(h, this.c, this.vconv);
      const v1 = relu(Net.linear(v, this.vfc1));
      const value = Math.tanh(Net.linear(v1, this.vfc2)[0]);
      return { logits, value };
    }
    // Priors over legal moves (masked softmax, same order as `legal`) and value.
    predict(game, legal) {
      legal = legal || game.legal();
      const turn = game.turn();
      const idx = legal.map((m) => moveIndex(m, turn));
      const { logits, value } = this.forward(encode(game, legal), idx);
      let mx = -Infinity;
      for (const l of logits) mx = Math.max(mx, l);
      let sum = 0;
      const pri = Array.from(logits, (l) => { const e = Math.exp(l - mx); sum += e; return e; });
      return { priors: pri.map((p) => p / sum), value };
    }
  }

  /* ---------------------------------------------------------------------- MCTS */

  class MCTS {
    constructor(net, { simulations = 100, cPuct = 1.5 } = {}) {
      this.net = net; this.sims = simulations; this.c = cPuct;
    }
    _expand(node, game, legal) {
      const { priors, value } = this.net.predict(game, legal);
      node.moves = legal; node.P = priors;
      node.N = new Float64Array(legal.length); node.W = new Float64Array(legal.length);
      node.children = new Array(legal.length).fill(null);
      return value;
    }
    _select(node) {
      let total = 0;
      for (const n of node.N) total += n;
      const sq = total > 0 ? Math.sqrt(total) : 1;
      let best = 0, bestScore = -Infinity;
      for (let a = 0; a < node.moves.length; a++) {
        const q = node.N[a] > 0 ? node.W[a] / node.N[a] : 0;
        const s = q + this.c * node.P[a] * sq / (1 + node.N[a]);
        if (s > bestScore) { bestScore = s; best = a; }
      }
      return best;
    }
    // One simulation from `root` (game positioned at root); returns nothing.
    _simulate(root, game) {
      const path = [];
      let node = root, value, depth = 0;
      for (;;) {
        const a = this._select(node);
        path.push([node, a]);
        game.push(node.moves[a]); depth++;
        let child = node.children[a];
        if (!child) {
          child = node.children[a] = { moves: null, terminal: undefined };
          const legal = game.legal();
          child.terminal = game.terminal(legal);
          value = child.terminal !== null ? child.terminal : this._expand(child, game, legal);
          break;
        }
        if (child.terminal !== null) { value = child.terminal; break; }
        node = child;
      }
      for (let i = 0; i < depth; i++) game.pop();
      let v = value;
      for (let i = path.length - 1; i >= 0; i--) {
        const [n, a] = path[i];
        v = -v; n.N[a] += 1; n.W[a] += v;
      }
    }
    // Async search in chunks so the page stays responsive. onProgress(done, total).
    async search(game, onProgress) {
      const legal = game.legal();
      if (!legal.length) return null;
      const root = { moves: null };
      const rootValue = this._expand(root, game, legal);
      for (let done = 0; done < this.sims;) {
        const chunkEnd = Math.min(this.sims, done + 8);
        for (; done < chunkEnd; done++) this._simulate(root, game);
        if (onProgress) onProgress(done, this.sims);
        await new Promise((r) => setTimeout(r, 0));
      }
      let best = 0;
      for (let a = 1; a < root.moves.length; a++) {
        if (root.N[a] > root.N[best] || (root.N[a] === root.N[best] && root.P[a] > root.P[best])) best = a;
      }
      const total = root.N.reduce((s, n) => s + n, 0);
      const top = root.moves.map((m, a) => ({ san: m.san, visits: root.N[a], prior: root.P[a],
        q: root.N[a] ? root.W[a] / root.N[a] : null }))
        .sort((x, y) => y.visits - x.visits).slice(0, 5);
      const allVisits = root.moves.map((m, a) => ({ uci: m.from + m.to + (m.promotion || ""), visits: root.N[a] }));
      return { move: root.moves[best], value: rootValue, visits: total, top, allVisits };
    }
  }

  /* ----------------------------------------------------------------- alpha-beta */

  const MATE = 100000;
  const VAL = { p: 100, n: 320, b: 330, r: 500, q: 900, k: 0 };

  class AlphaBeta {
    constructor(evalSpec, { depth = 3, timeLimit = 3.0 } = {}) {
      this.e = evalSpec; this.depth = depth; this.timeLimit = timeLimit;
    }
    evaluate(game) {
      const e = this.e, board = game.chess.board();
      let mg = 0, eg = 0, phase = 0, wb = 0, bb = 0;
      for (let row = 0; row < 8; row++) {
        for (let file = 0; file < 8; file++) {
          const p = board[row][file];
          if (!p) continue;
          const sq = (7 - row) * 8 + file, pt = PIECES.indexOf(p.type) + 1;
          const c = p.color === "w" ? 1 : 0, s = c ? 1 : -1;
          mg += s * e.mg_pst[c][pt][sq];
          eg += s * e.eg_pst[c][pt][sq];
          phase += e.phase_inc[pt];
          if (p.type === "b") { if (c) wb++; else bb++; }
        }
      }
      if (wb >= 2) { mg += e.bishop_pair[0]; eg += e.bishop_pair[1]; }
      if (bb >= 2) { mg -= e.bishop_pair[0]; eg -= e.bishop_pair[1]; }
      phase = Math.min(phase, e.max_phase);
      const score = (mg * phase + eg * (e.max_phase - phase)) / e.max_phase;
      return game.turn() === "w" ? score : -score;
    }
    static order(moves, ttMove) {
      const score = (m) => {
        if (ttMove && m.from === ttMove.from && m.to === ttMove.to && m.promotion === ttMove.promotion) return 1e7;
        let s = 0;
        if (m.captured) s += 1e5 + 10 * VAL[m.captured] - VAL[m.piece];
        if (m.promotion) s += 9e4 + VAL[m.promotion];
        return s;
      };
      return moves.map((m) => [score(m), m]).sort((a, b) => b[0] - a[0]).map((x) => x[1]);
    }
    _timeUp() {
      if ((++this.nodes & 255) === 0 && performance.now() > this.deadline) this.aborted = true;
      return this.aborted;
    }
    quiesce(game, alpha, beta, qd) {
      if (this._timeUp()) return 0;
      const stand = this.evaluate(game);
      if (stand >= beta || qd <= 0) return stand;
      if (stand > alpha) alpha = stand;
      const caps = game.legal().filter((m) => m.captured || m.promotion === "q");
      for (const m of AlphaBeta.order(caps)) {
        game.push(m);
        const s = -this.quiesce(game, -beta, -alpha, qd - 1);
        game.pop();
        if (this.aborted) return 0;
        if (s >= beta) return s;
        if (s > alpha) alpha = s;
      }
      return alpha;
    }
    negamax(game, depth, alpha, beta, ply) {
      if (this._timeUp()) return 0;
      const legal = game.legal();
      const t = game.terminal(legal);
      if (t !== null && ply > 0) return t < 0 ? -MATE + ply : 0;
      if (game.repetitions() >= 2 && ply > 0) return 0;
      if (depth <= 0) return this.quiesce(game, alpha, beta, 6);
      let best = -Infinity, bestMove = null;
      const ttMove = ply === 0 ? this.rootBest : null;
      for (const m of AlphaBeta.order(legal, ttMove)) {
        game.push(m);
        const s = -this.negamax(game, depth - 1, -beta, -alpha, ply + 1);
        game.pop();
        if (this.aborted) return 0;
        if (s > best) { best = s; bestMove = m; }
        if (s > alpha) alpha = s;
        if (alpha >= beta) break;
      }
      if (ply === 0) this.iterBest = bestMove;
      return best;
    }
    async search(game) {
      const legal = game.legal();
      if (!legal.length) return null;
      this.deadline = performance.now() + this.timeLimit * 1000;
      this.nodes = 0; this.aborted = false; this.rootBest = null;
      let result = { move: AlphaBeta.order(legal)[0], score: 0, depth: 0 };
      const t0 = performance.now();
      for (let d = 1; d <= this.depth; d++) {
        this.iterBest = null;
        const score = this.negamax(game, d, -Infinity, Infinity, 0);
        if (this.aborted && d > 1) break;
        result = { move: this.iterBest || result.move, score, depth: d };
        this.rootBest = result.move;
        await new Promise((r) => setTimeout(r, 0));
        if (Math.abs(score) > MATE - 100) break;
      }
      result.nodes = this.nodes;
      result.seconds = (performance.now() - t0) / 1000;
      return result;
    }
  }

  exportTo.ChessBot = { Game, encode, moveIndex, Net, MCTS, AlphaBeta, positionKey, sqIndex, sqName, MATE };
})(typeof module !== "undefined" && module.exports ? module.exports : window);
