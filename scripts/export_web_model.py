"""Export a trained checkpoint (+ engine tables) for the in-browser bot.

Folds every BatchNorm into the preceding convolution, writes the weights as
base64 float32, and records reference outputs computed by PyTorch so the
JavaScript port can be verified against them (see browser/test_bot.mjs).

    python scripts/export_web_model.py --checkpoint runs/demo/checkpoints/latest.pt \
        --out browser/model.json
"""

from __future__ import annotations

import argparse
import base64
import json
import random
import sys
from pathlib import Path

import chess
import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from chessbot.engine import evaluation as ev  # noqa: E402
from chessbot.ml.checkpoints import load_checkpoint, load_model  # noqa: E402
from chessbot.ml.encoding import encode_board, move_to_index  # noqa: E402
from chessbot.ml.mcts import MCTS  # noqa: E402
from chessbot.config import MCTSConfig  # noqa: E402

MCTS_SIMS = 64
TEST_FENS = [
    chess.STARTING_FEN,
    "r3k2r/pPppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "r3k2r/pPppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R b Kq - 3 7",
    "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
    "8/1P6/8/8/8/8/6pk/K7 b - - 0 1",
    "8/1P6/8/8/8/8/6pk/K7 w - - 5 40",
]


def _b64(t: torch.Tensor) -> str:
    return base64.b64encode(t.detach().cpu().numpy().astype("<f4").tobytes()).decode()


def _fold(conv: torch.nn.Conv2d, bn: torch.nn.BatchNorm2d) -> tuple[torch.Tensor, torch.Tensor]:
    scale = bn.weight / torch.sqrt(bn.running_var + bn.eps)
    w = conv.weight * scale[:, None, None, None]
    b = bn.bias - bn.running_mean * scale
    return w, b


def _random_positions(n: int, seed: int) -> list[chess.Board]:
    rng = random.Random(seed)
    out = []
    while len(out) < n:
        b = chess.Board()
        for _ in range(rng.randint(1, 60)):
            moves = list(b.legal_moves)
            if not moves:
                break
            b.push(rng.choice(moves))
        if not b.is_game_over():
            out.append(b)
    return out


def _simple_eval(board: chess.Board) -> int:
    """Material + tapered PST + bishop pair, side to move (what the JS port computes)."""
    mg = eg = phase = 0
    for sq, p in board.piece_map().items():
        c = 1 if p.color == chess.WHITE else 0
        sign = 1 if c else -1
        mg += sign * ev.MG_PST[c][p.piece_type][sq]
        eg += sign * ev.EG_PST[c][p.piece_type][sq]
        phase += ev.PHASE_INC[p.piece_type]
    for color, sign in ((chess.WHITE, 1), (chess.BLACK, -1)):
        if len(board.pieces(chess.BISHOP, color)) >= 2:
            mg += sign * ev.BISHOP_PAIR_MG
            eg += sign * ev.BISHOP_PAIR_EG
    phase = min(phase, ev.MAX_PHASE)
    score = (mg * phase + eg * (ev.MAX_PHASE - phase)) / ev.MAX_PHASE
    return score if board.turn == chess.WHITE else -score


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--out", default="browser/model.json")
    args = ap.parse_args()

    ckpt = load_checkpoint(args.checkpoint)
    model = load_model(args.checkpoint, device="cpu").eval()
    layers = []
    w, b = _fold(model.stem[0], model.stem[1])
    layers.append({"w": _b64(w), "b": _b64(b), "cin": w.shape[1], "cout": w.shape[0], "k": 3})
    for blk in model.tower:
        for conv, bn in ((blk.conv1, blk.bn1), (blk.conv2, blk.bn2)):
            w, b = _fold(conv, bn)
            layers.append({"w": _b64(w), "b": _b64(b), "cin": w.shape[1], "cout": w.shape[0], "k": 3})
    pw, pb = _fold(model.policy_conv[0], model.policy_conv[1])
    vw, vb = _fold(model.value_conv[0], model.value_conv[1])
    net = {
        "channels": model.config.channels,
        "res_blocks": model.config.res_blocks,
        "trunk": layers,
        "policy_conv": {"w": _b64(pw), "b": _b64(pb), "cout": pw.shape[0]},
        "policy_fc": {"w": _b64(model.policy_fc.weight), "b": _b64(model.policy_fc.bias),
                      "in": model.policy_fc.in_features, "out": model.policy_fc.out_features},
        "value_conv": {"w": _b64(vw), "b": _b64(vb), "cout": vw.shape[0]},
        "value_fc1": {"w": _b64(model.value_fc1.weight), "b": _b64(model.value_fc1.bias),
                      "in": model.value_fc1.in_features, "out": model.value_fc1.out_features},
        "value_fc2": {"w": _b64(model.value_fc2.weight), "b": _b64(model.value_fc2.bias),
                      "in": model.value_fc2.in_features, "out": model.value_fc2.out_features},
    }

    tests = []
    mcts = MCTS(model, MCTSConfig(simulations=MCTS_SIMS), device="cpu", leaf_batch_size=1)
    boards = [chess.Board(f) for f in TEST_FENS] + _random_positions(20, seed=7)
    for board in boards:
        x = torch.from_numpy(encode_board(board))[None]
        with torch.no_grad():
            logits, value = model(x)
        moves = list(board.legal_moves)
        idx = [move_to_index(m, board) for m in moves]
        legal = logits[0, idx]
        priors = torch.softmax(legal, 0).tolist()
        tests.append({
            "fen": board.fen(),
            "value": float(value[0, 0]),
            "priors": {m.uci(): p for m, p in zip(moves, priors)},
            "indices": {m.uci(): i for m, i in zip(moves, idx)},
            "planes_sum": encode_board(board).reshape(20, 64).sum(1).tolist(),
            "simple_eval": _simple_eval(board),
        })
        if len(tests) <= len(TEST_FENS) + 4:
            visits = mcts.run(board)
            tests[-1]["mcts_visits"] = {m.uci(): n for m, n in visits.items() if n}

    out = {
        "source": {
            "checkpoint": Path(args.checkpoint).name,
            "iteration": int(ckpt.get("iteration", -1)),
            "global_step": int(ckpt.get("global_step", -1)),
            "timestamp": ckpt.get("timestamp"),
            "parameters": sum(p.numel() for p in model.parameters()),
        },
        "net": net,
        "eval": {
            "mg_pst": [[ev.MG_PST[c][pt] if pt else [] for pt in range(7)] for c in (0, 1)],
            "eg_pst": [[ev.EG_PST[c][pt] if pt else [] for pt in range(7)] for c in (0, 1)],
            "phase_inc": list(ev.PHASE_INC),
            "max_phase": ev.MAX_PHASE,
            "bishop_pair": [ev.BISHOP_PAIR_MG, ev.BISHOP_PAIR_EG],
        },
        "mcts_sims": MCTS_SIMS,
        "tests": tests,
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(out))
    print(f"wrote {args.out} ({Path(args.out).stat().st_size / 1e6:.2f} MB) "
          f"from iteration {out['source']['iteration']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
