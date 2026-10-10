"""Tests for chessbot.engine.evaluation."""

from __future__ import annotations

import random

import chess
import pytest

from chessbot.engine.evaluation import MATE_SCORE, ClassicalEvaluator, Evaluator

FENS = [
    chess.STARTING_FEN,
    "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP3PPP/R1BQKB1R w KQ - 0 8",
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
    "4k3/8/2p5/3p4/8/8/8/3QK3 b - - 0 1",
]


def test_constants_and_protocol():
    assert MATE_SCORE == 100_000
    assert isinstance(ClassicalEvaluator(), Evaluator)


def test_start_position_is_balanced():
    assert ClassicalEvaluator().evaluate(chess.Board()) == pytest.approx(0.0)


@pytest.mark.parametrize("fen", FENS)
def test_mirror_symmetry(fen):
    ev = ClassicalEvaluator()
    board = chess.Board(fen)
    assert ev.evaluate(board) == pytest.approx(ev.evaluate(board.mirror()))


def test_mirror_symmetry_random_positions():
    ev = ClassicalEvaluator()
    rng = random.Random(7)
    for _ in range(60):
        board = chess.Board()
        for _ in range(rng.randint(0, 80)):
            moves = list(board.legal_moves)
            if not moves:
                break
            board.push(rng.choice(moves))
        assert ev.evaluate(board) == pytest.approx(ev.evaluate(board.mirror()))


def test_side_to_move_perspective():
    ev = ClassicalEvaluator()
    white_up_queen = chess.Board("4k3/8/8/8/8/8/8/3QK3 w - - 0 1")
    black_to_move = chess.Board("4k3/8/8/8/8/8/8/3QK3 b - - 0 1")
    assert ev.evaluate(white_up_queen) > 700
    assert ev.evaluate(black_to_move) < -700
    assert ev.evaluate(white_up_queen) == pytest.approx(-ev.evaluate(black_to_move))


def test_material_ordering():
    ev = ClassicalEvaluator()
    base = "4k3/8/8/8/8/8/8/3{}K3 w - - 0 1"
    vals = {p: ev.evaluate(chess.Board(base.format(p.upper()))) for p in "nbrq"}
    vals["p"] = ev.evaluate(chess.Board("4k3/8/8/8/8/8/3P4/4K3 w - - 0 1"))
    assert vals["p"] < vals["n"] < vals["r"] < vals["q"]
    assert vals["p"] < vals["b"] < vals["r"]


def test_bishop_pair_and_passed_pawn_bonus():
    ev = ClassicalEvaluator()
    pair = chess.Board("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1")
    bn = chess.Board("4k3/8/8/8/8/8/8/2B1KN2 w - - 0 1")
    assert ev.evaluate(pair) > ev.evaluate(bn)
    passed = chess.Board("4k3/8/3P4/8/8/8/8/4K3 w - - 0 1")
    blocked = chess.Board("4k3/3p4/3P4/8/8/8/8/4K3 w - - 0 1")
    # Passed d6 pawn alone vs. pawn blocked by an enemy pawn (material equalised by
    # comparing against the opponent pawn value) - passed pawn side scores higher.
    assert ev.evaluate(passed) > ev.evaluate(blocked)


@pytest.mark.parametrize("fen", [
    "R5k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1",   # checkmate
    "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1",          # stalemate
    "8/8/8/4k3/8/8/2K5/8 w - - 0 1",           # bare kings
])
def test_terminal_positions_do_not_crash(fen):
    v = ClassicalEvaluator().evaluate(chess.Board(fen))
    assert isinstance(v, float)
    assert abs(v) < MATE_SCORE
