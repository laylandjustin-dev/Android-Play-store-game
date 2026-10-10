"""Self-play game generation for AlphaZero-style training."""

from __future__ import annotations

from dataclasses import dataclass, field

import chess
import numpy as np

from chessbot.config import MCTSConfig, SelfPlayConfig
from chessbot.ml.encoding import POLICY_SIZE, encode_board, move_to_index
from chessbot.ml.mcts import MCTS


@dataclass
class GameRecord:
    samples: list[tuple[np.ndarray, np.ndarray, float]]  # value from side-to-move perspective
    moves: list[str]           # UCI
    result: float              # 1.0 / 0.5 / 0.0 (white perspective)
    termination: str           # "checkmate", "stalemate", "max_moves", "repetition", ...
    seconds: float = 0.0
    extra: dict = field(default_factory=dict)

    @property
    def num_plies(self) -> int:
        return len(self.moves)


def default_leaf_batch(simulations: int) -> int:
    """Leaf-batch size used for self-play: sims // 8 clamped to [1, 8].

    Keeps at least ~8 sequential batches per move so virtual loss barely
    changes the search, while batching network calls for throughput.
    """
    return int(min(8, max(1, simulations // 8)))


def game_termination(board: chess.Board) -> tuple[str, float] | None:
    """(termination, white-perspective result) if the game is over (incl. claimable draws)."""
    if not any(board.generate_legal_moves()):
        if board.is_check():
            return "checkmate", (0.0 if board.turn == chess.WHITE else 1.0)
        return "stalemate", 0.5
    if board.is_insufficient_material():
        return "insufficient_material", 0.5
    if board.halfmove_clock >= 100:
        return "fifty_moves", 0.5
    if board.is_repetition(3):
        return "repetition", 0.5
    return None


def play_selfplay_game(model, mcts_config: MCTSConfig, selfplay_config: SelfPlayConfig,
                       device=None, rng=None, leaf_batch_size: int | None = None,
                       start_board: chess.Board | None = None) -> GameRecord:
    """Play one self-play game with root Dirichlet noise.

    Plies ``< mcts_config.temperature_moves`` are sampled with
    ``mcts_config.temperature``; later plies take the most-visited move.
    Every ply records ``(encode_board, visit distribution over POLICY_SIZE,
    outcome from that ply's side-to-move perspective)``. A game reaching
    ``selfplay_config.max_moves`` plies is a draw with termination
    ``"max_moves"``. Claimable draws (threefold repetition, 50-move rule)
    end the game immediately.
    """
    import time

    t0 = time.perf_counter()
    if rng is None or isinstance(rng, (int, np.integer)):
        rng = np.random.default_rng(rng)
    if leaf_batch_size is None:
        leaf_batch_size = default_leaf_batch(mcts_config.simulations)
    mcts = MCTS(model, mcts_config, device=device, rng=rng, leaf_batch_size=leaf_batch_size)
    board = start_board.copy() if start_board is not None else chess.Board()

    states: list[np.ndarray] = []
    policies: list[np.ndarray] = []
    turns: list[bool] = []
    moves: list[str] = []
    termination, result = None, 0.5
    while True:
        end = game_termination(board)
        if end is not None:
            termination, result = end
            break
        if len(moves) >= selfplay_config.max_moves:
            termination, result = "max_moves", 0.5
            break
        temperature = mcts_config.temperature if len(moves) < mcts_config.temperature_moves else 0.0
        move, dist = mcts.select_move(board, temperature=temperature, add_noise=True)
        policy = np.zeros(POLICY_SIZE, dtype=np.float32)
        for m, p in dist.items():
            policy[move_to_index(m, board)] = p
        states.append(encode_board(board))
        policies.append(policy)
        turns.append(board.turn)
        moves.append(move.uci())
        board.push(move)

    samples = []
    for state, policy, turn in zip(states, policies, turns):
        if result == 0.5:
            z = 0.0
        else:
            white_won = result == 1.0
            z = 1.0 if (turn == chess.WHITE) == white_won else -1.0
        samples.append((state, policy, z))
    return GameRecord(samples=samples, moves=moves, result=result, termination=termination,
                      seconds=time.perf_counter() - t0)
