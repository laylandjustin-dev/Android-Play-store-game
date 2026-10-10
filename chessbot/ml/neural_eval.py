"""Network-backed evaluator satisfying the engine's duck-typed ``Evaluator`` interface.

Deliberately does not import engine code: anything with
``evaluate(board) -> float`` (centipawns, side to move) works with the searcher.
"""

from __future__ import annotations

from collections import OrderedDict

import chess
import numpy as np
import torch

from chessbot.ml.encoding import encode_board, move_to_index
from chessbot.ml.mcts import DEFAULT_INFERENCE_THREADS, limit_torch_threads
from chessbot.ml.model import select_device

MATE_SCORE = 100_000   # mirrors chessbot.engine.evaluation.MATE_SCORE (not imported on purpose)


class NeuralEvaluator:
    def __init__(self, model, device="cpu", scale_cp: float = 600.0, cache_size: int = 50_000,
                 inference_threads: int | None = DEFAULT_INFERENCE_THREADS):
        self.device = select_device(device)
        self.model = model.to(self.device)
        self.model.eval()
        self.scale_cp = float(scale_cp)
        self.cache_size = int(cache_size)
        self.inference_threads = inference_threads
        self._cache: OrderedDict = OrderedDict()   # position key -> (logits np, value)

    def _key(self, board: chess.Board):
        # The encoding also depends on the halfmove clock and repetition status.
        return (board._transposition_key(), board.halfmove_clock, board.is_repetition(2))

    @torch.inference_mode()
    def _infer(self, board: chess.Board) -> tuple[np.ndarray, float]:
        key = self._key(board)
        hit = self._cache.get(key)
        if hit is not None:
            self._cache.move_to_end(key)
            return hit
        x = torch.from_numpy(encode_board(board)).unsqueeze(0).to(self.device)
        with limit_torch_threads(self.inference_threads):
            logits, value = self.model(x)
        out = (logits[0].float().cpu().numpy(), float(value.item()))
        if self.cache_size > 0:
            self._cache[key] = out
            if len(self._cache) > self.cache_size:
                self._cache.popitem(last=False)
        return out

    def evaluate(self, board: chess.Board) -> float:
        """Centipawn-like score for the side to move: network value * scale_cp.

        Checkmate -> -MATE_SCORE, stalemate / insufficient material /
        50-move / threefold repetition -> 0.
        """
        if not any(board.generate_legal_moves()):
            return -float(MATE_SCORE) if board.is_check() else 0.0
        if board.is_insufficient_material() or board.halfmove_clock >= 100 or board.is_repetition(3):
            return 0.0
        return self._infer(board)[1] * self.scale_cp

    def move_prior(self, board: chess.Board, moves) -> list[float]:
        """Softmax priors (over ``moves`` only) aligned with ``moves``."""
        moves = list(moves)
        if not moves:
            return []
        logits, _ = self._infer(board)
        sel = np.array([logits[move_to_index(m, board)] for m in moves], dtype=np.float64)
        sel -= sel.max()
        p = np.exp(sel)
        p /= p.sum()
        return [float(v) for v in p]

    def clear(self) -> None:
        self._cache.clear()
