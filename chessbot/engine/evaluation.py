"""Static evaluation for the classical alpha-beta engine.

Scores are centipawns from the **side-to-move's** perspective (negamax
convention). ``MATE_SCORE`` is the base value used by the search for mates
(``MATE_SCORE - ply``).

The classical evaluator is a tapered PeSTO-style evaluation: material plus
middlegame/endgame piece-square tables blended by game phase, with a few cheap
extras (bishop pair, passed/doubled/isolated pawns, rooks on open files).
All terms are computed from bitboards so it stays reasonably fast in pure
Python.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

import chess

MATE_SCORE = 100_000


@runtime_checkable
class Evaluator(Protocol):
    def evaluate(self, board: chess.Board) -> float:  # cp, side to move
        ...


# ---------------------------------------------------------------------------
# PeSTO tables (Ronald Friederich's values). Laid out visually, index 0 = a8,
# i.e. for a *white* piece on python-chess square ``sq`` use ``table[sq ^ 56]``
# and for a black piece ``table[sq]``.
# ---------------------------------------------------------------------------

MG_VALUE = {chess.PAWN: 82, chess.KNIGHT: 337, chess.BISHOP: 365,
            chess.ROOK: 477, chess.QUEEN: 1025, chess.KING: 0}
EG_VALUE = {chess.PAWN: 94, chess.KNIGHT: 281, chess.BISHOP: 297,
            chess.ROOK: 512, chess.QUEEN: 936, chess.KING: 0}

# Simple values used by search heuristics (MVV-LVA, delta pruning).
PIECE_VALUES = {chess.PAWN: 100, chess.KNIGHT: 320, chess.BISHOP: 330,
                chess.ROOK: 500, chess.QUEEN: 900, chess.KING: 20_000}

_MG_PAWN = [
    0, 0, 0, 0, 0, 0, 0, 0,
    98, 134, 61, 95, 68, 126, 34, -11,
    -6, 7, 26, 31, 65, 56, 25, -20,
    -14, 13, 6, 21, 23, 12, 17, -23,
    -27, -2, -5, 12, 17, 6, 10, -25,
    -26, -4, -4, -10, 3, 3, 33, -12,
    -35, -1, -20, -23, -15, 24, 38, -22,
    0, 0, 0, 0, 0, 0, 0, 0,
]
_EG_PAWN = [
    0, 0, 0, 0, 0, 0, 0, 0,
    178, 173, 158, 134, 147, 132, 165, 187,
    94, 100, 85, 67, 56, 53, 82, 84,
    32, 24, 13, 5, -2, 4, 17, 17,
    13, 9, -3, -7, -7, -8, 3, -1,
    4, 7, -6, 1, 0, -5, -1, -8,
    13, 8, 8, 10, 13, 0, 2, -7,
    0, 0, 0, 0, 0, 0, 0, 0,
]
_MG_KNIGHT = [
    -167, -89, -34, -49, 61, -97, -15, -107,
    -73, -41, 72, 36, 23, 62, 7, -17,
    -47, 60, 37, 65, 84, 129, 73, 44,
    -9, 17, 19, 53, 37, 69, 18, 22,
    -13, 4, 16, 13, 28, 19, 21, -8,
    -23, -9, 12, 10, 19, 17, 25, -16,
    -29, -53, -12, -3, -1, 18, -14, -19,
    -105, -21, -58, -33, -17, -28, -19, -23,
]
_EG_KNIGHT = [
    -58, -38, -13, -28, -31, -27, -63, -99,
    -25, -8, -25, -2, -9, -25, -24, -52,
    -24, -20, 10, 9, -1, -9, -19, -41,
    -17, 3, 22, 22, 22, 11, 8, -18,
    -18, -6, 16, 25, 16, 17, 4, -18,
    -23, -3, -1, 15, 10, -3, -20, -22,
    -42, -20, -10, -5, -2, -20, -23, -44,
    -29, -51, -23, -15, -22, -18, -50, -64,
]
_MG_BISHOP = [
    -29, 4, -82, -37, -25, -42, 7, -8,
    -26, 16, -18, -13, 30, 59, 18, -47,
    -16, 37, 43, 40, 35, 50, 37, -2,
    -4, 5, 19, 50, 37, 37, 7, -2,
    -6, 13, 13, 26, 34, 12, 10, 4,
    0, 15, 15, 15, 14, 27, 18, 10,
    4, 15, 16, 0, 7, 21, 33, 1,
    -33, -3, -14, -21, -13, -12, -39, -21,
]
_EG_BISHOP = [
    -14, -21, -11, -8, -7, -9, -17, -24,
    -8, -4, 7, -12, -3, -13, -4, -14,
    2, -8, 0, -1, -2, 6, 0, 4,
    -3, 9, 12, 9, 14, 10, 3, 2,
    -6, 3, 13, 19, 7, 10, -3, -9,
    -12, -3, 8, 10, 13, 3, -7, -15,
    -14, -18, -7, -1, 4, -9, -15, -27,
    -23, -9, -23, -5, -9, -16, -5, -17,
]
_MG_ROOK = [
    32, 42, 32, 51, 63, 9, 31, 43,
    27, 32, 58, 62, 80, 67, 26, 44,
    -5, 19, 26, 36, 17, 45, 61, 16,
    -24, -11, 7, 26, 24, 35, -8, -20,
    -36, -26, -12, -1, 9, -7, 6, -23,
    -45, -25, -16, -17, 3, 0, -5, -33,
    -44, -16, -20, -9, -1, 11, -6, -71,
    -19, -13, 1, 17, 16, 7, -37, -26,
]
_EG_ROOK = [
    13, 10, 18, 15, 12, 12, 8, 5,
    11, 13, 13, 11, -3, 3, 8, 3,
    7, 7, 7, 5, 4, -3, -5, -3,
    4, 3, 13, 1, 2, 1, -1, 2,
    3, 5, 8, 4, -5, -6, -8, -11,
    -4, 0, -5, -1, -7, -12, -8, -16,
    -6, -6, 0, 2, -9, -9, -11, -3,
    -9, 2, 3, -1, -5, -13, 4, -20,
]
_MG_QUEEN = [
    -28, 0, 29, 12, 59, 44, 43, 45,
    -24, -39, -5, 1, -16, 57, 28, 54,
    -13, -17, 7, 8, 29, 56, 47, 57,
    -27, -27, -16, -16, -1, 17, -2, 1,
    -9, -26, -9, -10, -2, -4, 3, -3,
    -14, 2, -11, -2, -5, 2, 14, 5,
    -35, -8, 11, 2, 8, 15, -3, 1,
    -1, -18, -9, 10, -15, -25, -31, -50,
]
_EG_QUEEN = [
    -9, 22, 22, 27, 27, 19, 10, 20,
    -17, 20, 32, 41, 58, 25, 30, 0,
    -20, 6, 9, 49, 47, 35, 19, 9,
    3, 22, 24, 45, 57, 40, 57, 36,
    -18, 28, 19, 47, 31, 34, 39, 23,
    -16, -27, 15, 6, 9, 17, 10, 5,
    -22, -23, -30, -16, -16, -23, -36, -32,
    -33, -28, -22, -43, -5, -32, -20, -41,
]
_MG_KING = [
    -65, 23, 16, -15, -56, -34, 2, 13,
    29, -1, -20, -7, -8, -4, -38, -29,
    -9, 24, 2, -16, -20, 6, 22, -22,
    -17, -20, -12, -27, -30, -25, -14, -36,
    -49, -1, -27, -39, -46, -44, -33, -51,
    -14, -14, -22, -46, -44, -30, -15, -27,
    1, 7, -8, -64, -43, -16, 9, 8,
    -15, 36, 12, -54, 8, -28, 24, 14,
]
_EG_KING = [
    -74, -35, -18, -18, -11, 15, 4, -17,
    -12, 17, 14, 17, 17, 38, 23, 11,
    10, 17, 23, 15, 20, 45, 44, 13,
    -8, 22, 24, 27, 26, 33, 26, 3,
    -18, -4, 21, 24, 27, 23, 9, -11,
    -19, -3, 11, 21, 23, 16, 7, -9,
    -27, -11, 4, 13, 14, 4, -5, -17,
    -53, -34, -21, -11, -28, -14, -24, -43,
]

_MG_TABLES = {chess.PAWN: _MG_PAWN, chess.KNIGHT: _MG_KNIGHT, chess.BISHOP: _MG_BISHOP,
              chess.ROOK: _MG_ROOK, chess.QUEEN: _MG_QUEEN, chess.KING: _MG_KING}
_EG_TABLES = {chess.PAWN: _EG_PAWN, chess.KNIGHT: _EG_KNIGHT, chess.BISHOP: _EG_BISHOP,
              chess.ROOK: _EG_ROOK, chess.QUEEN: _EG_QUEEN, chess.KING: _EG_KING}

for _t in list(_MG_TABLES.values()) + list(_EG_TABLES.values()):
    assert len(_t) == 64

# Phase weight per piece type; 24 = full opening material.
PHASE_INC = (0, 0, 1, 1, 2, 4, 0)
MAX_PHASE = 24


def _build(tables, values):
    """[color][piece_type][square] -> material + PST (python-chess squares)."""
    out = [[None] * 7, [None] * 7]  # index 0 = black, 1 = white
    for pt in chess.PIECE_TYPES:
        tab, val = tables[pt], values[pt]
        out[1][pt] = [val + tab[sq ^ 56] for sq in chess.SQUARES]
        out[0][pt] = [val + tab[sq] for sq in chess.SQUARES]
    return out


MG_PST = _build(_MG_TABLES, MG_VALUE)
EG_PST = _build(_EG_TABLES, EG_VALUE)

# Pawn-structure masks.
_FILE_MASKS = [chess.BB_FILES[f] for f in range(8)]
_ADJ_FILES = [
    (_FILE_MASKS[f - 1] if f > 0 else 0) | (_FILE_MASKS[f + 1] if f < 7 else 0)
    for f in range(8)
]


def _passed_masks(color: bool) -> list[int]:
    masks = []
    for sq in chess.SQUARES:
        f, r = chess.square_file(sq), chess.square_rank(sq)
        files = _FILE_MASKS[f] | _ADJ_FILES[f]
        ahead = 0
        ranks = range(r + 1, 8) if color == chess.WHITE else range(0, r)
        for rr in ranks:
            ahead |= chess.BB_RANKS[rr]
        masks.append(files & ahead)
    return masks


PASSED_MASK = {chess.WHITE: _passed_masks(chess.WHITE), chess.BLACK: _passed_masks(chess.BLACK)}

# Bonus by *relative* rank (0 = own back rank .. 7 = promotion rank).
PASSED_MG = (0, 5, 5, 10, 20, 35, 60, 0)
PASSED_EG = (0, 10, 15, 25, 45, 70, 110, 0)
DOUBLED_MG, DOUBLED_EG = 10, 20
ISOLATED_MG, ISOLATED_EG = 10, 10
BISHOP_PAIR_MG, BISHOP_PAIR_EG = 30, 50
ROOK_OPEN_MG, ROOK_OPEN_EG = 25, 10
ROOK_SEMI_MG, ROOK_SEMI_EG = 12, 5

_popcount = int.bit_count
_scan = chess.scan_forward


def _pawn_terms(own: int, enemy: int, color: bool) -> tuple[int, int]:
    mg = eg = 0
    passed = PASSED_MASK[color]
    for sq in _scan(own):
        if not (passed[sq] & enemy):
            rr = (sq >> 3) if color else 7 - (sq >> 3)
            mg += PASSED_MG[rr]
            eg += PASSED_EG[rr]
    for f in range(8):
        n = _popcount(own & _FILE_MASKS[f])
        if n:
            if n > 1:
                mg -= DOUBLED_MG * (n - 1)
                eg -= DOUBLED_EG * (n - 1)
            if not (own & _ADJ_FILES[f]):
                mg -= ISOLATED_MG * n
                eg -= ISOLATED_EG * n
    return mg, eg


class ClassicalEvaluator:
    """Material + tapered PSTs + bishop pair, pawn structure, rook files."""

    def __init__(self, pawn_cache_size: int = 50_000):
        self._pawn_cache: dict[tuple[int, int], tuple[int, int]] = {}
        self._pawn_cache_size = pawn_cache_size

    def _pawns(self, wp: int, bp: int) -> tuple[int, int]:
        key = (wp, bp)
        hit = self._pawn_cache.get(key)
        if hit is not None:
            return hit
        wmg, weg = _pawn_terms(wp, bp, chess.WHITE)
        bmg, beg = _pawn_terms(bp, wp, chess.BLACK)
        res = (wmg - bmg, weg - beg)
        if len(self._pawn_cache) >= self._pawn_cache_size:
            self._pawn_cache.clear()
        self._pawn_cache[key] = res
        return res

    def evaluate(self, board: chess.Board) -> float:
        occ_w = board.occupied_co[chess.WHITE]
        occ_b = board.occupied_co[chess.BLACK]
        bbs = (0, board.pawns, board.knights, board.bishops,
               board.rooks, board.queens, board.kings)
        mg = eg = 0
        phase = 0
        w_mg, w_eg, b_mg, b_eg = MG_PST[1], EG_PST[1], MG_PST[0], EG_PST[0]
        for pt in range(1, 7):
            bb = bbs[pt]
            if not bb:
                continue
            wbb = bb & occ_w
            bbb = bb & occ_b
            if wbb:
                mt, et = w_mg[pt], w_eg[pt]
                for sq in _scan(wbb):
                    mg += mt[sq]
                    eg += et[sq]
            if bbb:
                mt, et = b_mg[pt], b_eg[pt]
                for sq in _scan(bbb):
                    mg -= mt[sq]
                    eg -= et[sq]
            inc = PHASE_INC[pt]
            if inc:
                phase += inc * _popcount(bb)

        # Bishop pair.
        if _popcount(board.bishops & occ_w) >= 2:
            mg += BISHOP_PAIR_MG
            eg += BISHOP_PAIR_EG
        if _popcount(board.bishops & occ_b) >= 2:
            mg -= BISHOP_PAIR_MG
            eg -= BISHOP_PAIR_EG

        # Pawn structure (cached by pawn bitboards).
        wp = board.pawns & occ_w
        bp = board.pawns & occ_b
        if wp or bp:
            pmg, peg = self._pawns(wp, bp)
            mg += pmg
            eg += peg

        # Rooks on open / semi-open files.
        rooks = board.rooks
        if rooks:
            for sq in _scan(rooks & occ_w):
                fm = _FILE_MASKS[sq & 7]
                if not (fm & wp):
                    if fm & bp:
                        mg += ROOK_SEMI_MG
                        eg += ROOK_SEMI_EG
                    else:
                        mg += ROOK_OPEN_MG
                        eg += ROOK_OPEN_EG
            for sq in _scan(rooks & occ_b):
                fm = _FILE_MASKS[sq & 7]
                if not (fm & bp):
                    if fm & wp:
                        mg -= ROOK_SEMI_MG
                        eg -= ROOK_SEMI_EG
                    else:
                        mg -= ROOK_OPEN_MG
                        eg -= ROOK_OPEN_EG

        if phase > MAX_PHASE:
            phase = MAX_PHASE
        score = (mg * phase + eg * (MAX_PHASE - phase)) / MAX_PHASE
        return score if board.turn == chess.WHITE else -score
