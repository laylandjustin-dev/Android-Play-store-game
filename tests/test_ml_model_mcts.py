import random

import chess
import numpy as np
import pytest
import torch

from chessbot.config import MCTSConfig, ModelConfig
from chessbot.ml.encoding import NUM_PLANES, POLICY_SIZE
from chessbot.ml.mcts import MCTS, terminal_value
from chessbot.ml.model import PolicyValueNet, select_device
from chessbot.ml.neural_eval import NeuralEvaluator

TINY = ModelConfig(channels=16, res_blocks=1, value_hidden=32)
MATE_IN_ONE_WHITE = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1"   # Ra8#
MATE_IN_ONE_BLACK = "r5k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1"   # Ra1#


@pytest.fixture
def net():
    torch.manual_seed(0)
    return PolicyValueNet(TINY).eval()


def test_select_device():
    assert select_device("cpu").type == "cpu"
    assert select_device("auto").type in ("cpu", "cuda")
    with pytest.raises(ValueError):
        select_device("tpu")


def test_forward_shapes_and_value_range(net):
    x = torch.randn(5, NUM_PLANES, 8, 8) * 3
    logits, value = net(x)
    assert logits.shape == (5, POLICY_SIZE)
    assert value.shape == (5, 1)
    assert torch.all(value >= -1) and torch.all(value <= 1)
    default = PolicyValueNet(ModelConfig())
    lg, v = default(torch.zeros(2, NUM_PLANES, 8, 8))
    assert lg.shape == (2, POLICY_SIZE) and v.shape == (2, 1)


def test_predict_legal_priors(net):
    b = chess.Board("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1")
    net.train()
    priors, value = net.predict(b)
    assert net.training   # mode restored
    assert set(priors) == set(b.legal_moves)
    assert abs(sum(priors.values()) - 1.0) < 1e-5
    assert all(p >= 0 for p in priors.values())
    assert -1.0 <= value <= 1.0


def test_terminal_values():
    assert terminal_value(chess.Board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1")) == -1.0   # mated
    assert terminal_value(chess.Board("7k/8/6QK/8/8/8/8/8 b - - 0 1")) == 0.0      # stalemate
    assert terminal_value(chess.Board("7k/8/8/8/8/8/8/6NK w - - 0 1")) == 0.0      # insufficient
    assert terminal_value(chess.Board("7k/8/8/8/8/8/8/5RRK w - - 100 80")) == 0.0  # 50-move
    b = chess.Board()
    for _ in range(2):
        for uci in ["g1f3", "g8f6", "f3g1", "f6g8"]:
            b.push_uci(uci)
    assert terminal_value(b) == 0.0                                               # threefold
    assert terminal_value(chess.Board()) is None


@pytest.mark.parametrize("leaf_batch", [1, 4])
def test_mcts_legal_moves_and_visit_counts(net, leaf_batch):
    rng = random.Random(7)
    cfg = MCTSConfig(simulations=24)
    mcts = MCTS(net, cfg, device="cpu", rng=np.random.default_rng(0), leaf_batch_size=leaf_batch)
    b = chess.Board()
    for _ in range(12):
        counts = mcts.run(b, add_noise=True)
        assert set(counts) == set(b.legal_moves)
        assert sum(counts.values()) == cfg.simulations
        move, dist = mcts.select_move(b, temperature=1.0, add_noise=True)
        assert move in b.legal_moves
        assert abs(sum(dist.values()) - 1.0) < 1e-9
        b.push(rng.choice(list(b.legal_moves)))
        if b.is_game_over():
            break


def test_mcts_does_not_mutate_board(net):
    b = chess.Board("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")
    b.push_uci("e2e4")
    fen, stack = b.fen(), list(b.move_stack)
    MCTS(net, MCTSConfig(simulations=16), rng=0).run(b)
    assert b.fen() == fen and b.move_stack == stack


@pytest.mark.parametrize("fen,mate", [(MATE_IN_ONE_WHITE, "a1a8"), (MATE_IN_ONE_BLACK, "a8a1")])
@pytest.mark.parametrize("leaf_batch", [1, 8])
def test_mcts_finds_mate_in_one_with_untrained_net(net, fen, mate, leaf_batch):
    mcts = MCTS(net, MCTSConfig(simulations=200), rng=0, leaf_batch_size=leaf_batch)
    move, dist = mcts.select_move(chess.Board(fen), temperature=0.0)
    assert move.uci() == mate
    assert dist[move] > 0.5


def test_mcts_terminal_root(net):
    mated = chess.Board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1")
    mcts = MCTS(net, MCTSConfig(simulations=8), rng=0)
    assert mcts.run(mated) == {}
    with pytest.raises(ValueError):
        mcts.select_move(mated)


def test_temperature_zero_is_deterministic(net):
    b = chess.Board()
    m1, _ = MCTS(net, MCTSConfig(simulations=32), rng=1).select_move(b, 0.0)
    m2, _ = MCTS(net, MCTSConfig(simulations=32), rng=2).select_move(b, 0.0)
    assert m1 == m2


def test_neural_evaluator(net):
    ev = NeuralEvaluator(net, device="cpu", scale_cp=600)
    b = chess.Board()
    score = ev.evaluate(b)
    assert -600 <= score <= 600
    _, v = net.predict(b)
    assert score == pytest.approx(v * 600, abs=1e-3)
    assert ev.evaluate(chess.Board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1")) == -100000
    assert ev.evaluate(chess.Board("7k/8/6QK/8/8/8/8/8 b - - 0 1")) == 0
    moves = list(b.legal_moves)
    pri = ev.move_prior(b, moves)
    assert len(pri) == len(moves) and abs(sum(pri) - 1) < 1e-6
    assert ev.move_prior(b, []) == []
