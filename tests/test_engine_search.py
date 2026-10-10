"""Tests for chessbot.engine.search.AlphaBetaSearcher."""

from __future__ import annotations

import random
import time

import chess
import pytest

from chessbot.config import SearchConfig
from chessbot.engine import MATE_SCORE, AlphaBetaSearcher, SearchResult
from chessbot.engine.search import MATE_BOUND

MATE_IN_1 = [
    # (fen, description)
    ("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", "white back-rank Ra8#"),
    ("r5k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1", "black back-rank Ra1#"),
    ("r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4", "scholar's mate Qxf7#"),
    ("rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2", "fool's mate Qh4#"),
    ("k7/8/1K6/8/8/8/8/7Q w - - 0 1", "Q+K vs K Qh8#"),
]

MATE_IN_2 = [
    "r2qkb1r/pp2nppp/3p4/2pNN1B1/2BnP3/3P4/PPP2PPP/R2bK2R w KQkq - 1 1",  # 1.Nf6+ gxf6 2.Bxf7#
    "6k1/5ppp/2r5/8/8/8/5PPP/RR4K1 w - - 0 1",                          # 1.Rb8+ Rc8 2.Rxc8#
]

MIDDLEGAME = "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP3PPP/R1BQKB1R w KQ - 0 8"


def _searcher(**cfg) -> AlphaBetaSearcher:
    return AlphaBetaSearcher(config=SearchConfig(**cfg))


def _is_forced_mate_in_2(board: chess.Board, move: chess.Move) -> bool:
    """Brute force: after ``move`` every reply allows a mate in one."""
    b = board.copy()
    b.push(move)
    if b.is_checkmate():
        return True
    replies = list(b.legal_moves)
    if not replies:
        return False
    for reply in replies:
        b.push(reply)
        found = False
        for m in b.legal_moves:
            b.push(m)
            found = b.is_checkmate()
            b.pop()
            if found:
                break
        b.pop()
        if not found:
            return False
    return True


@pytest.mark.parametrize("fen,desc", MATE_IN_1)
def test_mate_in_one(fen, desc):
    board = chess.Board(fen)
    for depth in (1, 3):
        res = _searcher().search(board, depth=depth)
        assert res.best_move in board.legal_moves, desc
        after = board.copy()
        after.push(res.best_move)
        assert after.is_checkmate(), f"{desc}: {res.best_move} is not mate"
        assert res.score == MATE_SCORE - 1


@pytest.mark.parametrize("fen", MATE_IN_2)
def test_mate_in_two(fen):
    board = chess.Board(fen)
    res = _searcher().search(board, depth=4)
    assert res.score == MATE_SCORE - 3
    assert _is_forced_mate_in_2(board, res.best_move)
    assert res.pv[0] == res.best_move
    assert len(res.pv) >= 3


def test_getting_mated_score_is_negative_mate():
    # Black's only move Kb8 allows Qh8#: mated in 2 plies.
    board = chess.Board("k7/8/1K6/8/8/8/8/7Q b - - 0 1")
    res = _searcher().search(board, depth=3)
    assert res.best_move == chess.Move.from_uci("a8b8")
    assert res.score == -(MATE_SCORE - 2)


def test_win_free_queen():
    board = chess.Board("4k3/8/8/3q4/8/4N3/4P3/4K3 w - - 0 1")
    res = _searcher().search(board, depth=3)
    assert res.best_move == chess.Move.from_uci("e3d5")
    assert res.score > 200


def test_avoid_hanging_queen():
    # White queen on d5 attacked by the c6 pawn; it must not be left en prise.
    board = chess.Board("4k3/8/2p5/3Q4/8/8/PPP5/4K3 w - - 0 1")
    res = _searcher().search(board, depth=3)
    assert res.score > 500, res
    b = board.copy()
    b.push(res.best_move)
    # After our move no black capture of the queen should be available for free.
    queen_sq = b.pieces(chess.QUEEN, chess.WHITE)
    assert queen_sq, "queen was given away"
    sq = next(iter(queen_sq))
    assert not b.is_attacked_by(chess.BLACK, sq) or b.is_attacked_by(chess.WHITE, sq)


def test_stalemate_no_moves():
    board = chess.Board("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
    assert board.is_stalemate()
    res = _searcher().search(board, depth=3)
    assert res.best_move is None
    assert res.score == 0


def test_checkmated_no_moves():
    board = chess.Board("R5k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1")
    assert board.is_checkmate()
    res = _searcher().search(board, depth=3)
    assert res.best_move is None
    assert res.score == -MATE_SCORE


def test_avoids_stalemating_when_winning():
    board = chess.Board("k7/8/1Q6/8/8/8/8/7K w - - 0 1")
    res = _searcher().search(board, depth=3)
    b = board.copy()
    b.push(res.best_move)
    assert not b.is_stalemate()
    assert res.score > 500


def test_stalemate_position_scores_zero():
    board = chess.Board("k7/2Q5/1K6/8/8/8/8/8 b - - 0 1")
    assert board.is_stalemate()
    assert _searcher().search(board, depth=2).score == 0


@pytest.mark.parametrize("fen", [
    "8/8/8/4k3/8/8/2K5/8 w - - 0 1",        # K vs K
    "8/8/8/4k3/8/8/2KN4/8 w - - 0 1",       # K+N vs K
    "8/8/8/4k3/8/8/2KB4/8 b - - 0 1",       # K+B vs K
])
def test_insufficient_material_is_draw(fen):
    res = _searcher().search(chess.Board(fen), depth=3)
    assert res.score == 0
    assert res.best_move is not None


def test_fifty_move_rule_is_draw():
    # White is a rook up but the 50-move counter is about to expire with no
    # capture/pawn move or mate available in one move.
    board = chess.Board("8/8/8/4k3/8/8/8/R3K3 w - - 99 120")
    res = _searcher().search(board, depth=3)
    assert res.score == 0


def test_repetition_is_scored_as_draw():
    # White is a queen down; returning the knight repeats an earlier position.
    board = chess.Board("q3k1n1/8/8/8/8/8/8/4K1N1 w - - 0 1")
    no_history = _searcher().search(board, depth=2)
    assert no_history.score < -500
    for uci in ("g1f3", "g8f6", "f3g1", "f6g8"):
        board.push_uci(uci)
    res = _searcher().search(board, depth=2)
    assert res.best_move == chess.Move.from_uci("g1f3")
    assert res.score == 0


def test_board_not_mutated():
    board = chess.Board(MIDDLEGAME)
    board.push_uci("h2h3")
    board.push_uci("a7a6")
    fen, stack = board.fen(), list(board.move_stack)
    _searcher().search(board, depth=3)
    _searcher().search(board, depth=20, time_limit=0.1)  # aborted mid-iteration
    assert board.fen() == fen
    assert board.move_stack == stack


def test_legal_moves_on_random_positions():
    rng = random.Random(1234)
    searcher = _searcher(depth=2)
    checked = 0
    for _ in range(40):
        board = chess.Board()
        for _ in range(rng.randint(0, 60)):
            moves = list(board.legal_moves)
            if not moves:
                break
            board.push(rng.choice(moves))
        res = searcher.search(board)
        if board.is_game_over(claim_draw=False) and not any(board.legal_moves):
            assert res.best_move is None
            continue
        assert res.best_move in board.legal_moves
        assert isinstance(res, SearchResult)
        # PV must be a legal sequence starting with best_move.
        assert res.pv and res.pv[0] == res.best_move
        b = board.copy()
        for m in res.pv:
            assert m in b.legal_moves
            b.push(m)
        checked += 1
    assert checked >= 30


def test_time_limit_honoured():
    board = chess.Board(MIDDLEGAME)
    searcher = _searcher()
    t0 = time.perf_counter()
    res = searcher.search(board, depth=64, time_limit=0.3)
    elapsed = time.perf_counter() - t0
    assert elapsed < 1.0, elapsed
    assert res.best_move in board.legal_moves
    assert res.depth >= 1
    assert res.depth < 64


def test_time_limit_from_config():
    board = chess.Board(MIDDLEGAME)
    searcher = AlphaBetaSearcher(config=SearchConfig(depth=64, time_limit=0.2))
    t0 = time.perf_counter()
    res = searcher.search(board)
    assert time.perf_counter() - t0 < 1.0
    assert res.best_move in board.legal_moves


def test_tiny_time_limit_still_returns_move():
    board = chess.Board(MIDDLEGAME)
    res = _searcher().search(board, depth=64, time_limit=1e-6)
    assert res.best_move in board.legal_moves
    assert res.depth >= 1


def test_depth_defaults_to_config():
    res = _searcher(depth=2).search(chess.Board())
    assert res.depth == 2
    res = _searcher(depth=2).search(chess.Board(), depth=3)
    assert res.depth == 3
    assert res.nodes > 0


@pytest.mark.parametrize("fen", [f for f, _ in MATE_IN_1] + MATE_IN_2)
def test_tt_on_off_same_result_on_tactics(fen):
    board = chess.Board(fen)
    on = _searcher().search(board, depth=4)
    off = _searcher(tt_size=0).search(board, depth=4)
    assert on.score == off.score
    a, b = board.copy(), board.copy()
    a.push(on.best_move)
    b.push(off.best_move)
    assert a.is_checkmate() == b.is_checkmate()


def test_tt_on_off_both_win_material():
    board = chess.Board("4k3/8/8/3q4/8/4N3/4P3/4K3 w - - 0 1")
    on = _searcher().search(board, depth=4)
    off = _searcher(tt_size=0).search(board, depth=4)
    assert on.best_move == off.best_move == chess.Move.from_uci("e3d5")


def test_tt_size_bounded():
    searcher = _searcher(tt_size=500)
    searcher.search(chess.Board(MIDDLEGAME), depth=4)
    assert 0 < len(searcher._tt) <= 500
    off = _searcher(tt_size=0)
    off.search(chess.Board(MIDDLEGAME), depth=3)
    assert len(off._tt) == 0


def test_clear_resets_state():
    searcher = _searcher()
    searcher.search(chess.Board(MIDDLEGAME), depth=3)
    assert searcher._tt
    searcher.clear()
    assert not searcher._tt
    assert all(k == [None, None] for k in searcher._killers)
    assert not any(any(h) for h in searcher._history)


def test_quiescence_prevents_horizon_blunder():
    # Qxd5 wins a pawn at depth 1 but the pawn is defended by c6.
    board = chess.Board("4k3/8/2p5/3p4/8/8/8/3QK3 w - - 0 1")
    bad = chess.Move.from_uci("d1d5")
    no_q = _searcher(quiescence=False).search(board, depth=1)
    assert no_q.best_move == bad  # the horizon effect without quiescence
    with_q = _searcher(quiescence=True).search(board, depth=1)
    assert with_q.best_move != bad
    assert with_q.best_move in board.legal_moves


def test_quiescence_depth_zero_is_static():
    board = chess.Board("4k3/8/2p5/3p4/8/8/8/3QK3 w - - 0 1")
    res = _searcher(quiescence_depth=0).search(board, depth=1)
    assert res.best_move in board.legal_moves


def test_move_prior_hook_called():
    calls = []

    def prior(board, moves):
        assert isinstance(board, chess.Board)
        assert moves and all(m in board.legal_moves for m in moves)
        calls.append(len(moves))
        return [1.0 if m.uci() == "a2a3" else 0.0 for m in moves]

    board = chess.Board()
    searcher = AlphaBetaSearcher(config=SearchConfig(depth=3), move_prior=prior)
    res = searcher.search(board)
    assert calls, "move_prior was never called"
    assert res.best_move in board.legal_moves
    # A prior only affects ordering: depth-1 scores must match a prior-less search.
    plain = _searcher().search(board, depth=1)
    with_prior = AlphaBetaSearcher(move_prior=prior).search(board, depth=1)
    assert plain.score == with_prior.score


def test_neural_style_float_evaluator():
    class Tiny:
        def evaluate(self, board):
            return 0.5 * (len(board.pieces(chess.QUEEN, board.turn))
                          - len(board.pieces(chess.QUEEN, not board.turn))) * 900.0

    board = chess.Board("4k3/8/8/3q4/8/4N3/4P3/4K3 w - - 0 1")
    res = AlphaBetaSearcher(evaluator=Tiny()).search(board, depth=2)
    assert res.best_move == chess.Move.from_uci("e3d5")
    assert isinstance(res.score, float)


def test_mate_scores_flag_bound():
    res = _searcher().search(chess.Board(MATE_IN_2[0]), depth=4)
    assert res.score >= MATE_BOUND
