import random

import chess
import numpy as np
import pytest

from chessbot.ml.encoding import (NUM_PLANES, POLICY_SIZE, encode_board, index_to_move,
                                  legal_move_mask, move_to_index)

SPECIAL_FENS = [
    chess.STARTING_FEN,
    # castling available both sides, both colours
    "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1",
    "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1",
    "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
    "r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1",
    # en passant, white and black to move
    "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
    "rnbqkbnr/pppp1ppp/8/8/3Pp3/5N2/PPP1PPPP/RNBQKB1R b KQkq d3 0 3",
    # promotions incl. capture-promotions and under-promotions, both colours
    "1n2k3/P1P5/8/8/8/8/5p1p/4K1N1 w - - 0 1",
    "1n2k3/P1P5/8/8/8/8/5p1p/4K1N1 b - - 0 1",
    "r1r1k3/1P6/8/8/8/8/6p1/4K2R b - - 0 1",
    "r1r1k3/1P6/8/8/8/8/6p1/4K2R w - - 0 1",
    # many legal moves
    "R6R/3Q4/1Q4Q1/4Q3/2Q4Q/Q4Q2/pp1Q4/kBNN1KB1 w - - 0 1",
]


def random_positions(n_games=60, seed=1234):
    rng = random.Random(seed)
    boards = []
    for _ in range(n_games):
        b = chess.Board()
        for _ in range(rng.randint(0, 160)):
            moves = list(b.legal_moves)
            if not moves:
                break
            # bias towards pawn pushes so promotions happen often
            pawn_moves = [m for m in moves if b.piece_type_at(m.from_square) == chess.PAWN]
            b.push(rng.choice(pawn_moves if pawn_moves and rng.random() < 0.4 else moves))
            if rng.random() < 0.15:
                boards.append(b.copy())
        boards.append(b.copy())
    return boards


def all_positions():
    return [chess.Board(f) for f in SPECIAL_FENS] + random_positions()


def test_constants():
    assert POLICY_SIZE == 4672
    assert encode_board(chess.Board()).shape == (NUM_PLANES, 8, 8)
    assert encode_board(chess.Board()).dtype == np.float32


def test_move_round_trip_and_bijective():
    seen = {"promo": 0, "underpromo": 0, "castle": 0, "ep": 0, "black": 0}
    for b in all_positions():
        moves = list(b.legal_moves)
        idx = [move_to_index(m, b) for m in moves]
        assert len(set(idx)) == len(idx), b.fen()
        for m, i in zip(moves, idx):
            assert 0 <= i < POLICY_SIZE
            assert index_to_move(i, b) == m, (b.fen(), m)
            seen["promo"] += m.promotion is not None
            seen["underpromo"] += m.promotion not in (None, chess.QUEEN)
            seen["castle"] += b.is_castling(m)
            seen["ep"] += b.is_en_passant(m)
            seen["black"] += b.turn == chess.BLACK
    for k, v in seen.items():
        assert v > 0, f"no {k} moves exercised"


def test_mask_matches_legal_moves():
    for b in all_positions():
        mask = legal_move_mask(b)
        assert mask.dtype == bool and mask.shape == (POLICY_SIZE,)
        assert int(mask.sum()) == b.legal_moves.count()
        decoded = {index_to_move(int(i), b) for i in np.flatnonzero(mask)}
        assert decoded == set(b.legal_moves)


def test_side_to_move_symmetry():
    """A position and its colour-mirrored twin must look identical to the network."""
    for b in all_positions()[:200]:
        m = b.mirror()
        b2 = chess.Board(b.fen())  # drop move stack so repetition plane is comparable
        assert np.array_equal(encode_board(b2), encode_board(m)), b.fen()
        for mv in b.legal_moves:
            mirrored = chess.Move(chess.square_mirror(mv.from_square),
                                  chess.square_mirror(mv.to_square), mv.promotion)
            assert move_to_index(mv, b) == move_to_index(mirrored, m)


def test_encoding_planes_content():
    b = chess.Board("r3k2r/8/8/8/8/8/8/R3K2R b Kq - 37 1")
    x = encode_board(b)
    # black to move: own king (black, e8) appears on the bottom rank after flipping
    assert x[5, 0, 4] == 1.0 and x[11, 7, 4] == 1.0
    assert x[12].max() == 0 and x[13].min() == 1   # own (black): q only
    assert x[14].min() == 1 and x[15].max() == 0   # opponent (white): K only
    assert np.allclose(x[17], 0.37)
    assert x[19].min() == 1
    ep = chess.Board("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3")
    assert encode_board(ep)[16, 5, 5] == 1.0 and encode_board(ep)[16].sum() == 1


def test_repetition_plane():
    b = chess.Board()
    assert encode_board(b)[18].max() == 0
    for uci in ["g1f3", "g8f6", "f3g1", "f6g8"]:
        b.push_uci(uci)
    assert encode_board(b)[18].min() == 1


def test_invalid_index():
    with pytest.raises(ValueError):
        index_to_move(POLICY_SIZE, chess.Board())
