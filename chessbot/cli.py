"""Command-line interface: python -m chessbot {train,evaluate,play,serve,config}."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone

import chess

from chessbot.config import ConfigError, load_config, save_config


def _log(msg: str) -> None:
    print(msg, flush=True)


def evaluate_candidate(cfg, candidate: str = "latest", opponent: str | None = None,
                       baseline: str | None = None, games: int | None = None,
                       simulations: int | None = None, max_moves: int | None = None,
                       record: bool = True, seed: int | None = None) -> dict:
    """Play the candidate checkpoint (MCTS) against an opponent; optionally log an eval record."""
    from chessbot.arena import play_match
    from chessbot.ml.checkpoints import load_checkpoint, resolve_checkpoint
    from chessbot.players import MCTSPlayer, make_player

    ev = cfg.evaluation
    opponent = opponent or ev.opponent
    games = games or ev.games
    max_moves = max_moves or ev.max_moves
    sims = simulations or ev.simulations
    path = resolve_checkpoint(candidate, cfg.checkpoint_dir)
    if path is None:
        raise FileNotFoundError(f"candidate checkpoint not found: {candidate}")
    cand = MCTSPlayer(cfg, path, simulations=sims, seed=seed)
    if opponent == "checkpoint":
        base = baseline or ev.baseline_checkpoint
        if not base or resolve_checkpoint(base, cfg.checkpoint_dir) is None:
            raise FileNotFoundError(f"baseline checkpoint not found: {base}")
        opp = MCTSPlayer(cfg, base, simulations=sims, seed=None if seed is None else seed + 1)
    else:
        opp = make_player(opponent, cfg, seed=seed)

    result = play_match(cand, opp, games=games, max_moves=max_moves)
    elo, lo, hi = result.elo_diff()
    rec = {
        "type": "eval",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "iteration": int(load_checkpoint(path).get("iteration", -1)),
        "candidate": cand.name,
        "opponent": opp.name,
        "games": result.wins + result.draws + result.losses,
        "wins": result.wins, "draws": result.draws, "losses": result.losses,
        "score": result.score, "elo": elo, "elo_low": lo, "elo_high": hi,
    }
    if record:
        cfg.metrics_path.parent.mkdir(parents=True, exist_ok=True)
        with open(cfg.metrics_path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(rec) + "\n")
    return rec


def _fmt_eval(rec: dict) -> str:
    return (f"{rec['candidate']} vs {rec['opponent']}: +{rec['wins']} ={rec['draws']} "
            f"-{rec['losses']}  score {rec['score']:.3f}  Elo {rec['elo']:+.0f} "
            f"[{rec['elo_low']:+.0f}, {rec['elo_high']:+.0f}]")


def cmd_train(args, cfg) -> int:
    from chessbot.ml.trainer import Trainer

    trainer = Trainer(cfg, resume=not args.no_resume, log_fn=_log)
    _log(f"Starting at iteration {trainer.iteration}, run_dir={cfg.run_dir}")

    def on_end(metrics: dict) -> None:
        _log(json.dumps({k: metrics.get(k) for k in (
            "iteration", "games", "positions", "policy_loss", "value_loss",
            "total_loss", "buffer_size", "selfplay_seconds", "train_seconds")}))
        if args.eval_every and metrics["iteration"] % args.eval_every == 0:
            rec = evaluate_candidate(cfg, "latest", games=args.eval_games)
            _log("eval: " + _fmt_eval(rec))

    trainer.run(args.iterations, on_iteration_end=on_end)
    return 0


def cmd_evaluate(args, cfg) -> int:
    rec = evaluate_candidate(cfg, args.candidate, args.opponent, args.baseline, args.games,
                             args.simulations, args.max_moves, record=not args.no_record,
                             seed=args.seed)
    print(_fmt_eval(rec))
    return 0


def cmd_play(args, cfg) -> int:
    from chessbot.players import make_player

    from chessbot.ml.checkpoints import resolve_checkpoint

    kind = args.bot or cfg.ui.bot_player
    checkpoint = None
    if kind in ("mcts", "alphabeta_nn"):
        spec = args.checkpoint or cfg.ui.bot_checkpoint
        checkpoint = resolve_checkpoint(spec, cfg.checkpoint_dir) if spec else None
        if checkpoint is None:
            if args.checkpoint:
                print(f"error: checkpoint not found: {args.checkpoint}", file=sys.stderr)
                return 2
            print("warning: no trained checkpoint found; "
                  + ("using an untrained network" if kind == "mcts" else "using classical evaluation"))
            kind = "mcts" if kind == "mcts" else "alphabeta"
    bot = make_player(kind, cfg, checkpoint=None if checkpoint is None else str(checkpoint))
    human = chess.WHITE if args.color == "white" else chess.BLACK
    board = chess.Board()
    print(f"Playing against {bot.name}. Enter moves in UCI (e2e4) or SAN (Nf3); 'quit' to exit.")
    while not board.is_game_over(claim_draw=True):
        print("\n" + board.unicode(borders=True, orientation=human))
        if board.turn == human:
            text = input("your move> ").strip()
            if text in ("quit", "exit"):
                return 0
            try:
                move = board.parse_uci(text) if len(text) >= 4 and text[1].isdigit() else board.parse_san(text)
            except ValueError:
                print("illegal or unparseable move")
                continue
        else:
            move = bot.select_move(board.copy())
            print(f"{bot.name} plays {board.san(move)}")
        board.push(move)
    print("\n" + board.unicode(borders=True, orientation=human))
    print("Game over:", board.result(claim_draw=True))
    return 0


def cmd_serve(args, cfg) -> int:
    from chessbot.web.app import create_app

    app = create_app(cfg, config_path=args.config)
    app.run(host=args.host or cfg.ui.host, port=args.port or cfg.ui.port, threaded=True)
    return 0


def cmd_config(args, cfg) -> int:
    if args.write:
        save_config(cfg, args.write)
        print(f"wrote {args.write}")
    else:
        import yaml
        from chessbot.config import config_to_dict

        print(yaml.safe_dump(config_to_dict(cfg), sort_keys=False))
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="chessbot", description=__doc__)
    p.add_argument("--config", "-c", default=None, help="YAML config file (default: built-in defaults)")
    sub = p.add_subparsers(dest="command", required=True)

    t = sub.add_parser("train", help="run self-play reinforcement learning (resumable)")
    t.add_argument("--iterations", type=int, default=None)
    t.add_argument("--no-resume", action="store_true", help="ignore existing checkpoints")
    t.add_argument("--eval-every", type=int, default=0, help="evaluate latest vs evaluation.opponent every N iterations")
    t.add_argument("--eval-games", type=int, default=None)
    t.set_defaults(func=cmd_train)

    e = sub.add_parser("evaluate", help="match a checkpoint against a baseline")
    e.add_argument("--candidate", default="latest")
    e.add_argument("--opponent", choices=["random", "alphabeta", "checkpoint"], default=None)
    e.add_argument("--baseline", default=None)
    e.add_argument("--games", type=int, default=None)
    e.add_argument("--simulations", type=int, default=None)
    e.add_argument("--max-moves", type=int, default=None)
    e.add_argument("--seed", type=int, default=None)
    e.add_argument("--no-record", action="store_true", help="don't append result to metrics.jsonl")
    e.set_defaults(func=cmd_evaluate)

    pl = sub.add_parser("play", help="play against the bot in the terminal")
    pl.add_argument("--bot", choices=["random", "alphabeta", "alphabeta_nn", "mcts"], default=None)
    pl.add_argument("--checkpoint", default=None)
    pl.add_argument("--color", choices=["white", "black"], default="white")
    pl.set_defaults(func=cmd_play)

    s = sub.add_parser("serve", help="start the web UI (board, dashboard, settings, models)")
    s.add_argument("--host", default=None)
    s.add_argument("--port", type=int, default=None)
    s.set_defaults(func=cmd_serve)

    c = sub.add_parser("config", help="print the effective config or write it to a file")
    c.add_argument("--write", default=None)
    c.set_defaults(func=cmd_config)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        cfg = load_config(args.config)
    except (ConfigError, FileNotFoundError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return args.func(args, cfg)


if __name__ == "__main__":
    sys.exit(main())
