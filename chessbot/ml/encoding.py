"""Board and move encodings for the policy/value network.

Everything is expressed from the **side-to-move's perspective**: when black is
to move the board is mirrored vertically (rank 1 <-> rank 8, files unchanged),
so the network always sees its own pieces moving "up" the board.

Board planes (``NUM_PLANES = 20``, each 8x8, row index = rank, col = file,
in the flipped frame):

====  =====================================================================
 0-5  own pieces: pawn, knight, bishop, rook, queen, king
 6-11 opponent pieces: pawn, knight, bishop, rook, queen, king
 12   own king-side castling right (all ones / zeros)
 13   own queen-side castling right
 14   opponent king-side castling right
 15   opponent queen-side castling right
 16   en-passant target square (single one), only if an e.p. capture is legal
 17   halfmove clock / 100 (constant plane, clipped to 1)
 18   repetition: ones if the current position has occurred before
 19   constant ones (lets convolutions detect the board edge)
====  =====================================================================

Move encoding (AlphaZero style, ``POLICY_SIZE = 73 * 64 = 4672``):
``index = plane * 64 + from_square`` where ``from_square`` is in the flipped
frame and ``plane`` is

* 0-55: "queen" moves, ``direction * 7 + (distance - 1)`` with directions
  N, NE, E, SE, S, SW, W, NW (in the flipped frame). Covers all king, queen,
  rook, bishop, pawn moves, castling (king moves two squares) and
  promotions to a queen.
* 56-63: knight moves.
* 64-72: under-promotions, ``64 + 3 * piece + direction`` with piece in
  (knight, bishop, rook) and direction in (capture towards file-1, push,
  capture towards file+1).

Because the frame is relative to the side to move, the mapping depends on the
board only through ``board.turn`` (and, when decoding, on whether the moving
piece is a pawn reaching the last rank, to restore queen promotions).
"""

from __future__ import annotations

import chess
import numpy as np

NUM_PLANES = 20
POLICY_SIZE = 73 * 64

_PIECE_ORDER = (chess.PAWN, chess.KNIGHT, chess.BISHOP, chess.ROOK, chess.QUEEN, chess.KING)

# (d_rank, d_file) for the 8 queen directions: N, NE, E, SE, S, SW, W, NW.
_QUEEN_DIRS = ((1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1))
_KNIGHT_DELTAS = ((2, 1), (1, 2), (-1, 2), (-2, 1), (-2, -1), (-1, -2), (1, -2), (2, -1))
_UNDERPROMO_PIECES = (chess.KNIGHT, chess.BISHOP, chess.ROOK)

# (d_rank, d_file) -> plane, for queen and knight moves.
_DELTA_TO_PLANE: dict[tuple[int, int], int] = {}
_PLANE_TO_DELTA: list[tuple[int, int]] = []
for _d, (_dr, _df) in enumerate(_QUEEN_DIRS):
    for _dist in range(1, 8):
        _DELTA_TO_PLANE[(_dr * _dist, _df * _dist)] = len(_PLANE_TO_DELTA)
        _PLANE_TO_DELTA.append((_dr * _dist, _df * _dist))
for _dr, _df in _KNIGHT_DELTAS:
    _DELTA_TO_PLANE[(_dr, _df)] = len(_PLANE_TO_DELTA)
    _PLANE_TO_DELTA.append((_dr, _df))
for _piece in _UNDERPROMO_PIECES:
    for _df in (-1, 0, 1):
        _PLANE_TO_DELTA.append((1, _df))
assert len(_PLANE_TO_DELTA) == 73


def _flip(square: int, turn: bool) -> int:
    return square if turn == chess.WHITE else square ^ 56


def encode_board(board: chess.Board) -> np.ndarray:
    """Encode ``board`` as float32 planes of shape (NUM_PLANES, 8, 8)."""
    planes = np.zeros((NUM_PLANES, 8, 8), dtype=np.float32)
    us = board.turn
    flat = planes.reshape(NUM_PLANES, 64)
    for i, piece_type in enumerate(_PIECE_ORDER):
        own = board.pieces_mask(piece_type, us)
        opp = board.pieces_mask(piece_type, not us)
        if us == chess.BLACK:
            own = chess.flip_vertical(own)
            opp = chess.flip_vertical(opp)
        for sq in chess.scan_forward(own):
            flat[i, sq] = 1.0
        for sq in chess.scan_forward(opp):
            flat[6 + i, sq] = 1.0
    if board.has_kingside_castling_rights(us):
        planes[12] = 1.0
    if board.has_queenside_castling_rights(us):
        planes[13] = 1.0
    if board.has_kingside_castling_rights(not us):
        planes[14] = 1.0
    if board.has_queenside_castling_rights(not us):
        planes[15] = 1.0
    if board.ep_square is not None and board.has_legal_en_passant():
        flat[16, _flip(board.ep_square, us)] = 1.0
    planes[17] = min(board.halfmove_clock / 100.0, 1.0)
    if board.is_repetition(2):
        planes[18] = 1.0
    planes[19] = 1.0
    return planes


def move_to_index(move: chess.Move, board: chess.Board) -> int:
    """Policy index of ``move`` (must be pseudo-legal in ``board``)."""
    us = board.turn
    frm = _flip(move.from_square, us)
    to = _flip(move.to_square, us)
    dr = (to >> 3) - (frm >> 3)
    df = (to & 7) - (frm & 7)
    if move.promotion is not None and move.promotion != chess.QUEEN:
        plane = 64 + 3 * _UNDERPROMO_PIECES.index(move.promotion) + (df + 1)
    else:
        try:
            plane = _DELTA_TO_PLANE[(dr, df)]
        except KeyError:
            raise ValueError(f"move {move.uci()} cannot be encoded") from None
    return plane * 64 + frm


def index_to_move(index: int, board: chess.Board) -> chess.Move:
    """Inverse of :func:`move_to_index` for the given position."""
    if not 0 <= index < POLICY_SIZE:
        raise ValueError(f"policy index {index} out of range")
    plane, frm = divmod(int(index), 64)
    dr, df = _PLANE_TO_DELTA[plane]
    r, f = (frm >> 3) + dr, (frm & 7) + df
    if not (0 <= r < 8 and 0 <= f < 8):
        raise ValueError(f"policy index {index} points off the board")
    to = r * 8 + f
    us = board.turn
    from_sq, to_sq = _flip(frm, us), _flip(to, us)
    promotion = None
    if plane >= 64:
        promotion = _UNDERPROMO_PIECES[(plane - 64) // 3]
    elif r == 7 and board.piece_type_at(from_sq) == chess.PAWN:
        promotion = chess.QUEEN
    return chess.Move(from_sq, to_sq, promotion=promotion)


def legal_move_indices(board: chess.Board) -> tuple[list[chess.Move], np.ndarray]:
    """Legal moves and their policy indices (int64 array, same order)."""
    moves = list(board.legal_moves)
    idx = np.fromiter((move_to_index(m, board) for m in moves), dtype=np.int64, count=len(moves))
    return moves, idx


def legal_move_mask(board: chess.Board) -> np.ndarray:
    """Boolean (POLICY_SIZE,) mask of the legal moves in ``board``."""
    mask = np.zeros(POLICY_SIZE, dtype=bool)
    _, idx = legal_move_indices(board)
    mask[idx] = True
    return mask
