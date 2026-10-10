#!/usr/bin/env python
"""Evaluate a trained checkpoint (MCTS player) against an opponent.

Examples::

    python scripts/evaluate_checkpoints.py --config configs/default.yaml
    python scripts/evaluate_checkpoints.py --candidate iter_000005.pt --opponent alphabeta --games 20
    python scripts/evaluate_checkpoints.py --opponent checkpoint --baseline iter_000001.pt

Plays ``--games`` games with chessbot.arena.play_match (colours alternate,
openings cycled from chessbot.arena.DEFAULT_OPENINGS unless --no-openings),
prints W/D/L, score and an Elo estimate with a 95% interval, and appends one
``{"type": "eval", ...}`` JSON line to ``config.metrics_path``. Every number
written comes from games actually played in this run.

``--candidate untrained`` evaluates a freshly initialised network (useful as
a sanity baseline; nothing is loaded from disk).
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from chessbot.arena import DEFAULT_OPENINGS, play_match  # noqa: E402
from chessbot.config import load_config  # noqa: E402

_ITER_RE = re.compile(r"iter_(\d+)\.pt$")


def _checkpoint_iteration(path: Path) -> int | None:
    try:
        from chessbot.ml.checkpoints import load_checkpoint

        it = load_checkpoint(path).get("iteration")
        if it is not None:
            return int(it)
    except Exception:  # pragma: no cover - fall back to file name
        pass
    m = _ITER_RE.search(path.name)
    return int(m.group(1)) if m else None


def _finite(x: float) -> float | None:
    return float(x) if math.isfinite(x) else None


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--config", default=None, help="YAML config (default: built-in defaults)")
    p.add_argument("--candidate", default="latest",
                   help="checkpoint spec: 'latest', file name in checkpoint dir, path, or 'untrained'")
    p.add_argument("--opponent", choices=["random", "alphabeta", "checkpoint"], default=None,
                   help="default: config.evaluation.opponent")
    p.add_argument("--baseline", default=None,
                   help="baseline checkpoint spec for --opponent checkpoint "
                        "(default: config.evaluation.baseline_checkpoint)")
    p.add_argument("--games", type=int, default=None, help="default: config.evaluation.games")
    p.add_argument("--simulations", type=int, default=None,
                   help="MCTS simulations per move (default: evaluation.simulations or mcts.simulations)")
    p.add_argument("--max-moves", type=int, default=None, help="default: config.evaluation.max_moves")
    p.add_argument("--seed", type=int, default=0, help="seed for random/MCTS opponents")
    p.add_argument("--no-openings", action="store_true",
                   help="start every game from the initial position")
    p.add_argument("--no-log", action="store_true", help="do not append to metrics.jsonl")
    p.add_argument("--quiet", action="store_true", help="no per-game output")
    return p


def main(argv: list[str] | None = None) -> dict:
    args = build_parser().parse_args(argv)
    cfg = load_config(args.config)
    ev = cfg.evaluation
    games = args.games if args.games is not None else ev.games
    max_moves = args.max_moves if args.max_moves is not None else ev.max_moves
    sims = args.simulations or ev.simulations or cfg.mcts.simulations
    opponent_kind = args.opponent or ev.opponent
    if games < 1 or max_moves < 1 or sims < 1:
        raise SystemExit("--games, --max-moves and --simulations must be >= 1")

    from chessbot.players import AlphaBetaPlayer, MCTSPlayer, RandomPlayer
    from chessbot.ml.checkpoints import resolve_checkpoint

    # Candidate
    if args.candidate.lower() == "untrained":
        cand_path, cand_iter = None, None
        candidate = MCTSPlayer(cfg, checkpoint=None, simulations=sims, seed=args.seed)
        cand_label = "untrained"
    else:
        cand_path = resolve_checkpoint(args.candidate, cfg.checkpoint_dir)
        if cand_path is None:
            raise SystemExit(f"candidate checkpoint not found: {args.candidate!r} "
                             f"(checkpoint dir {cfg.checkpoint_dir})")
        candidate = MCTSPlayer(cfg, checkpoint=cand_path, simulations=sims, seed=args.seed)
        cand_iter = _checkpoint_iteration(cand_path)
        cand_label = str(cand_path)

    # Opponent
    if opponent_kind == "random":
        opponent = RandomPlayer(seed=args.seed + 1)
        opp_label = "random"
    elif opponent_kind == "alphabeta":
        opponent = AlphaBetaPlayer(cfg)
        opp_label = f"alphabeta(depth={cfg.search.depth}, time_limit={cfg.search.time_limit})"
    else:
        spec = args.baseline or ev.baseline_checkpoint
        if not spec:
            raise SystemExit("--opponent checkpoint requires --baseline (or evaluation.baseline_checkpoint)")
        base_path = resolve_checkpoint(spec, cfg.checkpoint_dir)
        if base_path is None:
            raise SystemExit(f"baseline checkpoint not found: {spec!r}")
        opponent = MCTSPlayer(cfg, checkpoint=base_path, simulations=sims, seed=args.seed + 1)
        opp_label = f"checkpoint:{base_path}"

    print(f"Candidate: {candidate.name} ({cand_label}, iteration={cand_iter}, sims={sims})")
    print(f"Opponent : {opponent.name} ({opp_label})")
    print(f"Games    : {games}, max_moves={max_moves}, openings="
          f"{'none' if args.no_openings else len(DEFAULT_OPENINGS)}")

    def progress(i, rec):
        if not args.quiet:
            side = "W" if rec["a_is_white"] else "B"
            print(f"  game {i + 1:>3}/{games}: candidate={side} {rec['result']:<7} "
                  f"{rec['termination']:<22} plies={rec['plies']}", flush=True)

    t0 = time.perf_counter()
    res = play_match(candidate, opponent, games=games, max_moves=max_moves,
                     openings=None if args.no_openings else DEFAULT_OPENINGS,
                     alternate_colors=True, progress=progress)
    elapsed = time.perf_counter() - t0
    elo, lo, hi = res.elo_diff()

    print(f"\nResult   : W {res.wins} / D {res.draws} / L {res.losses}  "
          f"score={res.score:.3f}")
    print(f"Elo diff : {elo:+.1f}  (95% CI {lo:+.1f} .. {hi:+.1f})  "
          f"[{elapsed:.1f}s, {elapsed / games:.2f}s/game]")
    if games < 30:
        print("Note     : fewer than 30 games -- the Elo interval is very wide.")

    record = {
        "type": "eval",
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "iteration": cand_iter,
        "candidate": cand_label,
        "opponent": opp_label,
        "games": res.n,
        "wins": res.wins,
        "draws": res.draws,
        "losses": res.losses,
        "score": res.score,
        "elo": _finite(elo),
        "elo_low": _finite(lo),
        "elo_high": _finite(hi),
        "simulations": sims,
        "max_moves": max_moves,
        "seconds": round(elapsed, 3),
    }
    if not args.no_log:
        path = Path(cfg.metrics_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record) + "\n")
        print(f"Logged   : {path}")
    return record


if __name__ == "__main__":
    main()
