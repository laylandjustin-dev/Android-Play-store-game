"""PUCT Monte-Carlo Tree Search guided by a policy/value network (AlphaZero).

Conventions
-----------
* ``Node.W[a]`` / ``Node.N[a]`` are the total value / visit count of taking
  action ``a`` at that node, with values from the perspective of the player
  to move **at that node** (i.e. the player choosing ``a``).
* A leaf evaluation ``v`` is from the leaf's side-to-move perspective; it is
  negated at every step while being backed up.
* Terminal leaves: checkmate = -1 for the side to move (so +1 for the player
  who delivered it), stalemate / insufficient material / 50-move rule /
  threefold repetition = 0.

Optional leaf batching (``leaf_batch_size > 1``) collects several leaves per
network call using virtual loss. With the default ``leaf_batch_size=1`` the
search is the exact sequential AlphaZero algorithm.
"""

from __future__ import annotations

import math
import threading
from contextlib import contextmanager

import chess
import numpy as np
import torch

from chessbot.config import MCTSConfig
from chessbot.ml.encoding import encode_board, move_to_index
from chessbot.ml.model import PolicyValueNet, select_device

VIRTUAL_LOSS = 1.0
DEFAULT_INFERENCE_THREADS = 1

_threads_lock = threading.Lock()
_threads_active = 0
_threads_saved: int | None = None


@contextmanager
def limit_torch_threads(n: int | None):
    """Temporarily cap torch intra-op threads (process-wide) while searching.

    MCTS runs many tiny forward passes (batch 1-8). With several OpenMP threads
    these are barely faster on an idle machine and catastrophically slower when
    other processes compete for the cores (measured 0.4 ms -> 155 ms per
    forward with 4 threads on a loaded 4-core box). Nested / concurrent
    searches are reference-counted so the original setting is restored once.
    """
    global _threads_active, _threads_saved
    if n is None:
        yield
        return
    with _threads_lock:
        if _threads_active == 0:
            _threads_saved = torch.get_num_threads()
            if _threads_saved != n:
                torch.set_num_threads(n)
        _threads_active += 1
    try:
        yield
    finally:
        with _threads_lock:
            _threads_active -= 1
            if _threads_active == 0 and _threads_saved is not None:
                if torch.get_num_threads() != _threads_saved:
                    torch.set_num_threads(_threads_saved)
                _threads_saved = None


def terminal_value(board: chess.Board, moves: list[chess.Move] | None = None) -> float | None:
    """Game-theoretic value for the side to move if the game is over, else None."""
    if moves is None:
        moves = list(board.legal_moves)
    if not moves:
        return -1.0 if board.is_check() else 0.0
    if board.is_insufficient_material() or board.halfmove_clock >= 100 or board.is_repetition(3):
        return 0.0
    return None


class Node:
    __slots__ = ("moves", "P", "N", "W", "children", "terminal", "expanded", "pending")

    def __init__(self) -> None:
        self.moves: list[chess.Move] = []
        self.P = self.N = self.W = None
        self.children: list[Node | None] = []
        self.terminal: float | None = None
        self.expanded = False
        self.pending = False

    def expand(self, moves: list[chess.Move], priors: np.ndarray) -> None:
        self.moves = moves
        self.P = priors.astype(np.float64)
        self.N = np.zeros(len(moves), dtype=np.float64)
        self.W = np.zeros(len(moves), dtype=np.float64)
        self.children = [None] * len(moves)
        self.expanded = True


class MCTS:
    def __init__(self, model: PolicyValueNet, config: MCTSConfig, device=None, rng=None,
                 leaf_batch_size: int = 1,
                 inference_threads: int | None = DEFAULT_INFERENCE_THREADS):
        self.model = model
        self.config = config
        if device is not None:
            dev = select_device(device)
            if dev != model.device:
                model.to(dev)
        self.device = model.device
        if rng is None or isinstance(rng, (int, np.integer)):
            rng = np.random.default_rng(rng)
        self.rng = rng
        self.leaf_batch_size = max(1, int(leaf_batch_size))
        self.inference_threads = inference_threads   # None = leave torch's setting alone
        self.last_root: Node | None = None

    # ------------------------------------------------------------------ network
    @torch.inference_mode()
    def _evaluate(self, boards: list[chess.Board], moves_list: list[list[chess.Move]]):
        x = torch.from_numpy(np.stack([encode_board(b) for b in boards])).to(self.device)
        logits, values = self.model(x)
        logits = logits.float().cpu().numpy()
        values = values.float().cpu().numpy()[:, 0]
        out = []
        for i, (b, moves) in enumerate(zip(boards, moves_list)):
            idx = np.fromiter((move_to_index(m, b) for m in moves), dtype=np.int64, count=len(moves))
            sel = logits[i, idx].astype(np.float64)
            sel -= sel.max()
            p = np.exp(sel)
            p /= p.sum()
            out.append((p, float(values[i])))
        return out

    # ---------------------------------------------------------------- selection
    def _select(self, node: Node) -> int:
        n_total = node.N.sum()
        sqrt_total = math.sqrt(n_total) if n_total > 0 else 1.0
        with np.errstate(divide="ignore", invalid="ignore"):
            q = np.where(node.N > 0, node.W / np.maximum(node.N, 1e-9), 0.0)
        u = self.config.c_puct * node.P * sqrt_total / (1.0 + node.N)
        return int(np.argmax(q + u))

    def _add_noise(self, root: Node) -> None:
        if len(root.moves) < 2 or self.config.dirichlet_epsilon <= 0:
            return
        noise = self.rng.dirichlet([self.config.dirichlet_alpha] * len(root.moves))
        eps = self.config.dirichlet_epsilon
        root.P = (1.0 - eps) * root.P + eps * noise

    # ---------------------------------------------------------------------- run
    def run(self, board: chess.Board, add_noise: bool = False) -> dict[chess.Move, int]:
        """Run ``config.simulations`` simulations from ``board``; return root visit counts.

        ``board`` is not modified. Returns ``{}`` if the position is terminal.
        """
        was_training = self.model.training
        if was_training:
            self.model.eval()
        try:
            with limit_torch_threads(self.inference_threads):
                return self._run(board, add_noise)
        finally:
            if was_training:
                self.model.train()

    def _run(self, board: chess.Board, add_noise: bool) -> dict[chess.Move, int]:
        board = board.copy()
        root = Node()
        self.last_root = root
        moves = list(board.legal_moves)
        tv = terminal_value(board, moves)
        if tv is not None:
            root.terminal = tv
            return {}
        (priors, _), = self._evaluate([board], [moves])
        root.expand(moves, priors)
        if add_noise:
            self._add_noise(root)

        sims_done = 0
        target = self.config.simulations
        while sims_done < target:
            batch = []          # (path, leaf, board copy, moves)
            batch_limit = min(self.leaf_batch_size, target - sims_done)
            attempts = 0
            while len(batch) < batch_limit and attempts < 2 * batch_limit:
                attempts += 1
                path: list[tuple[Node, int]] = []
                node = root
                while node.expanded:
                    a = self._select(node)
                    path.append((node, a))
                    board.push(node.moves[a])
                    child = node.children[a]
                    if child is None:
                        child = Node()
                        node.children[a] = child
                    node = child
                    if node.terminal is not None:
                        break
                if node.terminal is None and not node.pending:
                    leaf_moves = list(board.legal_moves)
                    node.terminal = terminal_value(board, leaf_moves)
                    if node.terminal is None:
                        node.pending = True
                        for n, a in path:          # virtual loss
                            n.N[a] += 1
                            n.W[a] -= VIRTUAL_LOSS
                        batch.append((path, node, board.copy(stack=board.halfmove_clock), leaf_moves))
                elif node.pending:
                    # collision with a leaf already queued in this batch
                    for _ in path:
                        board.pop()
                    break
                if node.terminal is not None:
                    self._backup(path, node.terminal)
                    sims_done += 1
                for _ in path:
                    board.pop()
                if sims_done >= target:
                    break
            if batch:
                results = self._evaluate([b for _, _, b, _ in batch], [m for _, _, _, m in batch])
                for (path, node, _, leaf_moves), (priors, value) in zip(batch, results):
                    for n, a in path:              # remove virtual loss
                        n.N[a] -= 1
                        n.W[a] += VIRTUAL_LOSS
                    node.expand(leaf_moves, priors)
                    node.pending = False
                    self._backup(path, value)
                    sims_done += 1
        return {m: int(n) for m, n in zip(root.moves, root.N)}

    @staticmethod
    def _backup(path: list[tuple[Node, int]], value: float) -> None:
        v = value
        for node, a in reversed(path):
            v = -v
            node.N[a] += 1
            node.W[a] += v

    # ------------------------------------------------------------- move choice
    def select_move(self, board: chess.Board, temperature: float = 0.0,
                    add_noise: bool = False) -> tuple[chess.Move, dict[chess.Move, float]]:
        """Search and pick a move.

        temperature 0 -> most visited move; > 0 -> sample proportional to
        visits^(1/T). Returns the move and the normalised visit distribution.
        """
        counts = self.run(board, add_noise=add_noise)
        if not counts:
            raise ValueError("no legal moves: position is terminal")
        moves = list(counts.keys())
        visits = np.array([counts[m] for m in moves], dtype=np.float64)
        total = visits.sum()
        if total <= 0:   # cannot happen with simulations >= 1, but stay safe
            visits = np.ones_like(visits)
            total = visits.sum()
        dist = {m: float(v / total) for m, v in zip(moves, visits)}
        if temperature <= 1e-6:
            best = np.flatnonzero(visits == visits.max())
            if len(best) > 1:   # tie-break on prior
                pri = self.last_root.P[best]
                choice = int(best[int(np.argmax(pri))])
            else:
                choice = int(best[0])
        else:
            logv = np.log(np.maximum(visits, 1e-12)) / temperature
            logv -= logv.max()
            probs = np.exp(logv) * (visits > 0)
            probs /= probs.sum()
            choice = int(self.rng.choice(len(moves), p=probs))
        return moves[choice], dist
