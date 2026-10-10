"""Uniform Player interface over the random, alpha-beta and MCTS bots."""

from __future__ import annotations

import random
from pathlib import Path
from typing import Protocol

import chess

from chessbot.config import Config, PLAYER_KINDS


class Player(Protocol):
    name: str

    def select_move(self, board: chess.Board) -> chess.Move: ...

    def reset(self) -> None: ...


class RandomPlayer:
    """Uniformly random legal moves. The weakest baseline."""

    def __init__(self, seed: int | None = None):
        self.name = "random"
        self._rng = random.Random(seed)

    def select_move(self, board: chess.Board) -> chess.Move:
        moves = list(board.legal_moves)
        if not moves:
            raise ValueError("no legal moves")
        return self._rng.choice(moves)

    def reset(self) -> None:
        pass


def _resolve(config: Config, checkpoint: str | Path | None) -> Path | None:
    from chessbot.ml.checkpoints import resolve_checkpoint

    if checkpoint is None:
        return None
    return resolve_checkpoint(str(checkpoint), config.checkpoint_dir)


class AlphaBetaPlayer:
    """Alpha-beta search. With a checkpoint, uses the network as evaluator and move prior."""

    def __init__(self, config: Config, checkpoint: str | Path | None = None):
        from chessbot.engine.evaluation import ClassicalEvaluator
        from chessbot.engine.search import AlphaBetaSearcher

        self.config = config
        evaluator, prior = ClassicalEvaluator(), None
        self.name = "alphabeta"
        path = _resolve(config, checkpoint) if checkpoint else None
        if checkpoint and path is None:
            raise FileNotFoundError(f"checkpoint not found: {checkpoint}")
        if path is not None:
            from chessbot.ml.checkpoints import load_model
            from chessbot.ml.neural_eval import NeuralEvaluator

            nn_eval = NeuralEvaluator(load_model(path, device="cpu"), device="cpu")
            evaluator, prior = nn_eval, nn_eval.move_prior
            self.name = f"alphabeta_nn[{path.name}]"
        self.searcher = AlphaBetaSearcher(evaluator, config.search, move_prior=prior)
        self.last_result = None

    def select_move(self, board: chess.Board) -> chess.Move:
        result = self.searcher.search(board)
        self.last_result = result
        if result.best_move is None:
            raise ValueError("no legal moves")
        return result.best_move

    def reset(self) -> None:
        self.searcher.clear()


class MCTSPlayer:
    """Network-guided MCTS. Without a checkpoint, uses a freshly initialised network."""

    def __init__(self, config: Config, checkpoint: str | Path | None = None,
                 temperature: float = 0.0, simulations: int | None = None, seed: int | None = None):
        import dataclasses

        import numpy as np

        from chessbot.ml.checkpoints import load_model
        from chessbot.ml.mcts import MCTS
        from chessbot.ml.model import PolicyValueNet

        path = _resolve(config, checkpoint) if checkpoint else None
        if checkpoint and path is None:
            raise FileNotFoundError(f"checkpoint not found: {checkpoint}")
        if path is not None:
            self.model = load_model(path, device="cpu")
            self.name = f"mcts[{path.name}]"
        else:
            self.model = PolicyValueNet(config.model)
            self.name = "mcts[untrained]"
        self.model.eval()
        mcts_cfg = dataclasses.replace(config.mcts)
        if simulations is not None:
            mcts_cfg.simulations = simulations
        self.temperature = temperature
        self.mcts = MCTS(self.model, mcts_cfg, device="cpu", rng=np.random.default_rng(seed))

    def select_move(self, board: chess.Board) -> chess.Move:
        move, _ = self.mcts.select_move(board, temperature=self.temperature, add_noise=False)
        return move

    def reset(self) -> None:
        pass


def make_player(kind: str, config: Config, checkpoint: str | None = None,
                seed: int | None = None, simulations: int | None = None) -> Player:
    if kind not in PLAYER_KINDS:
        raise ValueError(f"unknown player kind {kind!r}; expected one of {PLAYER_KINDS}")
    if kind == "random":
        return RandomPlayer(seed=seed)
    if kind == "alphabeta":
        return AlphaBetaPlayer(config)
    if kind == "alphabeta_nn":
        return AlphaBetaPlayer(config, checkpoint=checkpoint or "latest")
    return MCTSPlayer(config, checkpoint=checkpoint, simulations=simulations, seed=seed)
