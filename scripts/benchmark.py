#!/usr/bin/env python
"""Performance benchmarks (real wall-clock timing, nothing estimated).

Sections (select with --sections, default all):

* ``movegen``  python-chess legal move generation: perft nodes/s
* ``alphabeta`` alpha-beta time-to-depth, nodes and nodes/s on a few positions
* ``nn``       PolicyValueNet forward throughput at batch 1 and batch 64
* ``mcts``     MCTS simulations/s (untrained network of the configured size)
* ``selfplay`` seconds per self-play game

A section whose modules fail to import is skipped with the reason printed;
a section that raises is reported as an error, and the other sections still
run.

Examples::

    python scripts/benchmark.py
    python scripts/benchmark.py --sections movegen,alphabeta --depth 4
    python scripts/benchmark.py --config configs/default.yaml --json bench.json
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import platform
import sys
import time
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import chess  # noqa: E402

from chessbot.config import load_config  # noqa: E402

SECTIONS = ("movegen", "alphabeta", "nn", "mcts", "selfplay")

POSITIONS = {
    "start": chess.STARTING_FEN,
    "kiwipete": "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "middlegame": "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP3PPP/R2QKB1R w KQ - 0 8",
    "endgame": "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
}


class Skip(Exception):
    """Raised to skip a section (e.g. module not implemented)."""


def _perft(board: chess.Board, depth: int) -> int:
    if depth == 1:
        return board.legal_moves.count()
    n = 0
    for mv in board.legal_moves:
        board.push(mv)
        n += _perft(board, depth - 1)
        board.pop()
    return n


def bench_movegen(cfg, args) -> list[dict]:
    rows = []
    for name in ("start", "kiwipete"):
        depth = args.perft_depth if name == "start" else max(1, args.perft_depth - 1)
        board = chess.Board(POSITIONS[name])
        t0 = time.perf_counter()
        nodes = _perft(board, depth)
        dt = time.perf_counter() - t0
        rows.append({"position": name, "depth": depth, "nodes": nodes,
                     "seconds": round(dt, 4), "nodes_per_sec": round(nodes / dt)})
    return rows


def bench_alphabeta(cfg, args) -> list[dict]:
    try:
        from chessbot.engine.search import AlphaBetaSearcher
        from chessbot.engine.evaluation import ClassicalEvaluator
    except ImportError as e:
        raise Skip(f"engine not importable: {e}")
    search_cfg = dataclasses.replace(cfg.search, time_limit=None)
    rows = []
    for name, fen in POSITIONS.items():
        for depth in range(1, args.depth + 1):
            searcher = AlphaBetaSearcher(ClassicalEvaluator(), search_cfg)
            board = chess.Board(fen)
            t0 = time.perf_counter()
            res = searcher.search(board, depth=depth)
            dt = time.perf_counter() - t0
            rows.append({"position": name, "depth": depth, "completed_depth": res.depth,
                         "nodes": res.nodes, "seconds": round(dt, 4),
                         "nodes_per_sec": round(res.nodes / dt) if dt > 0 else None,
                         "best_move": res.best_move.uci() if res.best_move else None,
                         "score_cp": round(float(res.score), 1)})
    return rows


def _load_model(cfg):
    try:
        import torch
        from chessbot.ml.model import PolicyValueNet
        from chessbot.ml.encoding import encode_board
    except ImportError as e:
        raise Skip(f"ml/torch not importable: {e}")
    model = PolicyValueNet(cfg.model).eval()
    return torch, model, encode_board


def bench_nn(cfg, args) -> list[dict]:
    torch, model, encode_board = _load_model(cfg)
    import numpy as np

    boards = []
    b = chess.Board()
    import random
    rng = random.Random(0)
    while len(boards) < 64:
        if b.is_game_over():
            b = chess.Board()
        b.push(rng.choice(list(b.legal_moves)))
        boards.append(b.copy())
    planes = np.stack([encode_board(x) for x in boards]).astype(np.float32)
    n_params = sum(p.numel() for p in model.parameters())
    rows = []
    default_threads = torch.get_num_threads()
    # Tiny batch-1 forwards are very sensitive to OpenMP thread count when
    # other processes compete for cores, so measure default and 1 thread.
    thread_settings = [args.threads] if args.threads else sorted({default_threads, 1}, reverse=True)
    try:
        for threads in thread_settings:
            torch.set_num_threads(threads)
            for batch in (1, 64):
                x = torch.from_numpy(planes[:batch])
                with torch.no_grad():
                    for _ in range(3):
                        model(x)
                    reps = 0
                    t0 = time.perf_counter()
                    while True:
                        model(x)
                        reps += 1
                        dt = time.perf_counter() - t0
                        if dt >= args.min_time and reps >= 3:
                            break
                rows.append({"batch": batch, "reps": reps,
                             "ms_per_batch": round(1000 * dt / reps, 3),
                             "positions_per_sec": round(batch * reps / dt),
                             "params": n_params, "threads": threads})
    finally:
        torch.set_num_threads(default_threads)
    # encoding cost (CPU, numpy)
    t0 = time.perf_counter()
    for _ in range(5):
        for x in boards:
            encode_board(x)
    dt = time.perf_counter() - t0
    rows.append({"batch": "encode_board", "reps": 5 * len(boards),
                 "ms_per_batch": round(1000 * dt / (5 * len(boards)), 4),
                 "positions_per_sec": round(5 * len(boards) / dt),
                 "params": n_params, "threads": torch.get_num_threads()})
    return rows


def bench_mcts(cfg, args) -> list[dict]:
    torch, model, _ = _load_model(cfg)
    try:
        import numpy as np
        from chessbot.ml.mcts import MCTS
    except ImportError as e:
        raise Skip(f"mcts not importable: {e}")
    mcfg = dataclasses.replace(cfg.mcts, simulations=args.simulations or cfg.mcts.simulations)
    rows = []
    for name in ("start", "middlegame", "endgame"):
        mcts = MCTS(model, mcfg, device="cpu", rng=np.random.default_rng(0))
        board = chess.Board(POSITIONS[name])
        mcts.run(board)  # warm-up
        reps, t0 = 0, time.perf_counter()
        while True:
            mcts.run(board)
            reps += 1
            dt = time.perf_counter() - t0
            if dt >= args.min_time and reps >= 2:
                break
        sims = mcfg.simulations * reps
        rows.append({"position": name, "simulations_per_move": mcfg.simulations,
                     "moves": reps, "seconds_per_move": round(dt / reps, 4),
                     "simulations_per_sec": round(sims / dt)})
    return rows


def bench_selfplay(cfg, args) -> list[dict]:
    torch, model, _ = _load_model(cfg)
    try:
        import numpy as np
        from chessbot.ml.selfplay import play_selfplay_game
    except ImportError as e:
        raise Skip(f"selfplay not importable: {e}")
    mcfg = dataclasses.replace(cfg.mcts, simulations=args.simulations or cfg.mcts.simulations)
    spcfg = dataclasses.replace(cfg.selfplay)
    if args.selfplay_max_moves:
        spcfg.max_moves = args.selfplay_max_moves
    rows = []
    for g in range(args.selfplay_games):
        t0 = time.perf_counter()
        rec = play_selfplay_game(model, mcfg, spcfg, device="cpu",
                                 rng=np.random.default_rng(g))
        dt = time.perf_counter() - t0
        plies = len(rec.moves)
        rows.append({"game": g + 1, "plies": plies, "termination": rec.termination,
                     "seconds": round(dt, 3),
                     "seconds_per_ply": round(dt / plies, 4) if plies else None,
                     "simulations_per_move": mcfg.simulations,
                     "max_moves": spcfg.max_moves})
    return rows


BENCHES = {"movegen": bench_movegen, "alphabeta": bench_alphabeta, "nn": bench_nn,
           "mcts": bench_mcts, "selfplay": bench_selfplay}


def _print_table(title: str, rows: list[dict]) -> None:
    print(f"\n== {title} ==")
    if not rows:
        print("  (no rows)")
        return
    cols = list(rows[0].keys())
    for r in rows[1:]:
        for k in r:
            if k not in cols:
                cols.append(k)
    width = {c: max(len(c), *(len(str(r.get(c, ""))) for r in rows)) for c in cols}
    print("  " + "  ".join(c.ljust(width[c]) for c in cols))
    print("  " + "  ".join("-" * width[c] for c in cols))
    for r in rows:
        print("  " + "  ".join(str(r.get(c, "")).ljust(width[c]) for c in cols))


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="chessbot performance benchmarks")
    p.add_argument("--config", default=None, help="YAML config (model size, mcts, search)")
    p.add_argument("--sections", default=",".join(SECTIONS),
                   help=f"comma-separated subset of {','.join(SECTIONS)}")
    p.add_argument("--perft-depth", type=int, default=4, help="perft depth for start pos")
    p.add_argument("--depth", type=int, default=4, help="max alpha-beta depth")
    p.add_argument("--simulations", type=int, default=None, help="override mcts.simulations")
    p.add_argument("--selfplay-games", type=int, default=1)
    p.add_argument("--selfplay-max-moves", type=int, default=None,
                   help="override selfplay.max_moves for the benchmark")
    p.add_argument("--min-time", type=float, default=1.0,
                   help="minimum seconds per timed measurement (nn, mcts)")
    p.add_argument("--threads", type=int, default=None, help="torch.set_num_threads")
    p.add_argument("--quick", action="store_true",
                   help="small settings: perft 3, depth 3, min-time 0.2, 16 sims, 40-ply self-play")
    p.add_argument("--json", default=None, help="write results to this JSON file")
    return p


def main(argv: list[str] | None = None) -> dict:
    args = build_parser().parse_args(argv)
    if args.quick:
        args.perft_depth, args.depth, args.min_time = 3, 3, 0.2
        args.simulations = args.simulations or 16
        args.selfplay_max_moves = args.selfplay_max_moves or 40
    sections = [s.strip() for s in args.sections.split(",") if s.strip()]
    unknown = [s for s in sections if s not in BENCHES]
    if unknown:
        raise SystemExit(f"unknown sections {unknown}; choose from {SECTIONS}")
    cfg = load_config(args.config)

    env = {"python": platform.python_version(), "platform": platform.platform(),
           "cpu_count": os.cpu_count(), "chess": chess.__version__,
           "model": dataclasses.asdict(cfg.model), "mcts_simulations":
           args.simulations or cfg.mcts.simulations, "search": dataclasses.asdict(cfg.search)}
    try:
        import torch
        env["torch"] = torch.__version__
        if args.threads:
            torch.set_num_threads(args.threads)
        env["torch_threads"] = torch.get_num_threads()
    except ImportError:
        env["torch"] = None
    print("Environment:", json.dumps(env))

    out = {"environment": env, "sections": {}}
    for s in sections:
        t0 = time.perf_counter()
        try:
            rows = BENCHES[s](cfg, args)
            out["sections"][s] = {"status": "ok", "rows": rows,
                                  "wall_seconds": round(time.perf_counter() - t0, 3)}
            _print_table(s, rows)
        except Skip as e:
            out["sections"][s] = {"status": "skipped", "reason": str(e)}
            print(f"\n== {s} == SKIPPED: {e}")
        except Exception as e:  # report and keep going
            out["sections"][s] = {"status": "error", "reason": f"{type(e).__name__}: {e}",
                                  "traceback": traceback.format_exc()}
            print(f"\n== {s} == ERROR: {type(e).__name__}: {e}")
    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(out, fh, indent=2)
        print(f"\nWrote {args.json}")
    return out


if __name__ == "__main__":
    main()
