"""Alpha-beta (negamax / PVS) search with iterative deepening.

Features: transposition table (bounded, exact/lower/upper bounds, mate-score
ply adjustment), move ordering (TT move, MVV-LVA, promotions, killers,
history heuristic, optional external ``move_prior`` for quiet moves),
quiescence search (captures + queen promotions, check evasions), check
extension, null-move pruning, late-move reductions, draw detection
(repetition, 50-move rule, insufficient material) and a wall-clock time limit.

Scores are centipawns from the side-to-move's perspective; mate is
``MATE_SCORE - ply``.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable

import chess

from chessbot.config import SearchConfig
from chessbot.engine.evaluation import (
    MATE_SCORE,
    PIECE_VALUES,
    ClassicalEvaluator,
    Evaluator,
)

INF = float(MATE_SCORE * 10)
MATE_BOUND = MATE_SCORE - 1000  # |score| >= this means "mate found"
MAX_PLY = 128

TT_EXACT, TT_LOWER, TT_UPPER = 0, 1, 2

_VAL = (0, PIECE_VALUES[chess.PAWN], PIECE_VALUES[chess.KNIGHT], PIECE_VALUES[chess.BISHOP],
        PIECE_VALUES[chess.ROOK], PIECE_VALUES[chess.QUEEN], PIECE_VALUES[chess.KING])

# Move-ordering score bands.
_S_TT = 10_000_000
_S_CAPTURE = 1_000_000
_S_PROMO = 900_000
_S_KILLER1 = 800_000
_S_KILLER2 = 790_000
_PRIOR_SCALE = 500_000
_HISTORY_MAX = 200_000

MovePrior = Callable[[chess.Board, list[chess.Move]], list[float]]


@dataclass
class SearchResult:
    best_move: chess.Move | None
    score: float            # cp, side-to-move perspective
    depth: int              # deepest fully completed iteration
    nodes: int
    pv: list[chess.Move] = field(default_factory=list)
    time_seconds: float = 0.0


class _Timeout(Exception):
    pass


def _to_tt(score: float, ply: int) -> float:
    if score >= MATE_BOUND:
        return score + ply
    if score <= -MATE_BOUND:
        return score - ply
    return score


def _from_tt(score: float, ply: int) -> float:
    if score >= MATE_BOUND:
        return score - ply
    if score <= -MATE_BOUND:
        return score + ply
    return score


class AlphaBetaSearcher:
    """Iterative-deepening negamax alpha-beta searcher."""

    #: check the clock every (mask + 1) nodes
    CLOCK_MASK = 63

    def __init__(self, evaluator: Evaluator | None = None,
                 config: SearchConfig | None = None,
                 move_prior: MovePrior | None = None):
        self.evaluator = evaluator if evaluator is not None else ClassicalEvaluator()
        self.config = config if config is not None else SearchConfig()
        self.move_prior = move_prior
        self.use_null_move = True
        self.use_lmr = True
        self._nodes = 0
        self._deadline: float | None = None
        self._abortable = False
        self._keys: list = []
        self._root_best: chess.Move | None = None
        self._root_depth = 1
        self.clear()

    # ------------------------------------------------------------------ API
    def clear(self) -> None:
        """Reset transposition table, killer moves and history heuristic."""
        self._tt: dict = {}
        self._killers: list[list[chess.Move | None]] = [[None, None] for _ in range(MAX_PLY + 1)]
        self._history: list[list[int]] = [[0] * 4096, [0] * 4096]
        self._pv: list[list[chess.Move]] = [[] for _ in range(MAX_PLY + 2)]
        self._root_best = None

    def search(self, board: chess.Board, depth: int | None = None,
               time_limit: float | None = None) -> SearchResult:
        start = time.perf_counter()
        max_depth = depth if depth is not None else self.config.depth
        max_depth = max(1, min(int(max_depth), MAX_PLY // 2))
        limit = time_limit if time_limit is not None else self.config.time_limit

        b = board.copy()
        legal = list(b.legal_moves)
        if not legal:
            score = -MATE_SCORE if b.is_check() else 0.0
            return SearchResult(None, float(score), 0, 0, [], time.perf_counter() - start)

        # Positions since the last irreversible move, for repetition detection.
        hist_keys: list = []
        tmp = b.copy()
        for _ in range(min(tmp.halfmove_clock, len(tmp.move_stack))):
            tmp.pop()
            hist_keys.append(tmp._transposition_key())
        hist_keys.reverse()
        self._keys = hist_keys
        root_keys_len = len(hist_keys)
        root_stack_len = len(b.move_stack)

        self._nodes = 0
        self._deadline = (start + limit) if limit else None
        self._root_best = None
        # Age history scores between searches.
        for h in self._history:
            for i in range(4096):
                if h[i]:
                    h[i] >>= 1
        for k in self._killers:
            k[0] = k[1] = None

        best_move = legal[0]
        best_score = 0.0
        best_pv: list[chess.Move] = [legal[0]]
        completed = 0

        for d in range(1, max_depth + 1):
            self._abortable = d > 1 and self._deadline is not None
            self._root_depth = d
            try:
                score = self._negamax(b, d, -INF, INF, 0, True)
            except _Timeout:
                while len(b.move_stack) > root_stack_len:
                    b.pop()
                del self._keys[root_keys_len:]
                break
            pv = list(self._pv[0])
            if pv:
                best_move = pv[0]
                best_pv = pv
            best_score = score
            completed = d
            self._root_best = best_move
            if abs(score) >= MATE_BOUND and MATE_SCORE - abs(score) <= d:
                break  # forced mate found within the searched horizon
            if self._deadline is not None:
                if time.perf_counter() - start > 0.5 * limit:
                    break

        self._abortable = False
        return SearchResult(best_move, float(best_score), completed, self._nodes,
                            best_pv, time.perf_counter() - start)

    # ------------------------------------------------------------- internals
    def _tick(self) -> None:
        if (self._abortable and (self._nodes & self.CLOCK_MASK) == 0
                and time.perf_counter() >= self._deadline):
            raise _Timeout

    def _store(self, key, depth: int, flag: int, score: float, move, ply: int) -> None:
        size = self.config.tt_size
        if size <= 0:
            return
        tt = self._tt
        if key not in tt and len(tt) >= size:
            # FIFO eviction (dicts preserve insertion order).
            del tt[next(iter(tt))]
        tt[key] = (depth, flag, _to_tt(score, ply), move)

    def _order(self, board: chess.Board, moves: list[chess.Move], tt_move,
               ply: int, depth: int) -> list[tuple[float, int, chess.Move, bool]]:
        piece_type_at = board.piece_type_at
        ep = board.ep_square
        killers = self._killers[ply]
        k1, k2 = killers[0], killers[1]
        hist = self._history[board.turn]
        prior_map = None
        if self.move_prior is not None and (ply == 0 or depth >= 2):
            quiets = [m for m in moves
                      if not m.promotion and piece_type_at(m.to_square) is None
                      and not (m.to_square == ep and piece_type_at(m.from_square) == chess.PAWN)]
            if quiets:
                pri = self.move_prior(board, quiets)
                prior_map = dict(zip(quiets, pri))
        out = []
        for i, m in enumerate(moves):
            victim = piece_type_at(m.to_square)
            if victim is None and m.to_square == ep and piece_type_at(m.from_square) == chess.PAWN:
                victim = chess.PAWN
            quiet = victim is None and not m.promotion
            if m == tt_move:
                s = _S_TT
            elif victim is not None:
                s = _S_CAPTURE + 10 * _VAL[victim] - _VAL[piece_type_at(m.from_square)]
                if m.promotion:
                    s += _VAL[m.promotion]
            elif m.promotion:
                s = _S_PROMO + _VAL[m.promotion]
            elif m == k1:
                s = _S_KILLER1
            elif m == k2:
                s = _S_KILLER2
            else:
                s = hist[(m.from_square << 6) | m.to_square]
                if prior_map is not None:
                    s += float(prior_map.get(m, 0.0)) * _PRIOR_SCALE
            out.append((s, i, m, quiet))
        out.sort(reverse=True, key=lambda t: (t[0], -t[1]))
        return out

    def _evaluate(self, board: chess.Board) -> float:
        return self.evaluator.evaluate(board)

    def _negamax(self, board: chess.Board, depth: int, alpha: float, beta: float,
                 ply: int, pv_node: bool) -> float:
        self._pv[ply] = []
        self._nodes += 1
        self._tick()

        key = board._transposition_key()
        if ply > 0:
            h = board.halfmove_clock
            if h >= 4 and key in self._keys[-h:]:
                return 0.0
            if not (board.pawns | board.rooks | board.queens) and board.is_insufficient_material():
                return 0.0
            # Mate-distance pruning.
            if alpha < -MATE_SCORE + ply:
                alpha = -MATE_SCORE + ply
            if beta > MATE_SCORE - ply - 1:
                beta = MATE_SCORE - ply - 1
            if alpha >= beta:
                return alpha
            if ply >= MAX_PLY:
                return self._evaluate(board)

        in_check = board.is_check()
        if in_check and ply <= 2 * self._root_depth + 4:
            depth += 1

        if depth <= 0:
            if self.config.quiescence:
                return self._quiesce(board, alpha, beta, ply, self.config.quiescence_depth)
            return self._evaluate(board)

        tt_move = None
        use_tt = self.config.tt_size > 0
        if use_tt:
            entry = self._tt.get(key)
            if entry is not None:
                e_depth, flag, e_score, tt_move = entry
                if e_depth >= depth and not pv_node and ply > 0:
                    s = _from_tt(e_score, ply)
                    if flag == TT_EXACT:
                        return s
                    if flag == TT_LOWER and s >= beta:
                        return s
                    if flag == TT_UPPER and s <= alpha:
                        return s
        if ply == 0 and tt_move is None:
            tt_move = self._root_best

        # Null-move pruning (skipped in check, PV nodes and pawn-only endings).
        if (self.use_null_move and not pv_node and not in_check and depth >= 3 and ply > 0
                and abs(beta) < MATE_BOUND
                and (board.occupied_co[board.turn]
                     & ~(board.pawns | board.kings))):
            if self._evaluate(board) >= beta:
                r = 3 if depth >= 6 else 2
                self._keys.append(key)
                board.push(chess.Move.null())
                try:
                    score = -self._negamax(board, depth - 1 - r, -beta, -beta + 1, ply + 1, False)
                finally:
                    board.pop()
                    self._keys.pop()
                if score >= beta:
                    return beta

        moves = list(board.legal_moves)
        if not moves:
            return (-MATE_SCORE + ply) if in_check else 0.0
        if ply > 0 and board.halfmove_clock >= 100:
            return 0.0

        ordered = self._order(board, moves, tt_move, ply, depth)
        killers = self._killers[ply]
        orig_alpha = alpha
        best = -INF
        best_move = None
        searched = 0
        keys = self._keys
        keys.append(key)
        push, pop = board.push, board.pop
        for _, _, m, quiet in ordered:
            push(m)
            if searched == 0:
                score = -self._negamax(board, depth - 1, -beta, -alpha, ply + 1, pv_node)
            else:
                r = 0
                if (self.use_lmr and quiet and depth >= 3 and searched >= 3 and not in_check
                        and m != killers[0] and m != killers[1] and not board.is_check()):
                    r = 2 if (searched >= 8 and depth >= 5) else 1
                score = -self._negamax(board, depth - 1 - r, -alpha - 1, -alpha, ply + 1, False)
                if r and score > alpha:
                    score = -self._negamax(board, depth - 1, -alpha - 1, -alpha, ply + 1, False)
                if pv_node and alpha < score < beta:
                    score = -self._negamax(board, depth - 1, -beta, -alpha, ply + 1, True)
            pop()
            searched += 1
            if score > best:
                best = score
                best_move = m
                if score > alpha:
                    alpha = score
                    self._pv[ply] = [m] + self._pv[ply + 1]
                    if alpha >= beta:
                        if quiet:
                            if killers[0] != m:
                                killers[1] = killers[0]
                                killers[0] = m
                            hist = self._history[board.turn]
                            idx = (m.from_square << 6) | m.to_square
                            hist[idx] += depth * depth
                            if hist[idx] > _HISTORY_MAX:
                                for i in range(4096):
                                    hist[i] >>= 1
                        break
        keys.pop()

        if use_tt:
            if best <= orig_alpha:
                flag = TT_UPPER
            elif best >= beta:
                flag = TT_LOWER
            else:
                flag = TT_EXACT
            self._store(key, depth, flag, best, best_move, ply)
        return best

    def _quiesce(self, board: chess.Board, alpha: float, beta: float,
                 ply: int, qdepth: int) -> float:
        self._pv[ply] = []
        self._nodes += 1
        self._tick()

        if ply >= MAX_PLY:
            return self._evaluate(board)

        piece_type_at = board.piece_type_at
        if board.is_check():
            moves = list(board.legal_moves)
            if not moves:
                return -MATE_SCORE + ply
            if qdepth <= 0:
                return self._evaluate(board)
            ep = board.ep_square
            scored = []
            for i, m in enumerate(moves):
                victim = piece_type_at(m.to_square)
                if victim is None and m.to_square == ep and piece_type_at(m.from_square) == chess.PAWN:
                    victim = chess.PAWN
                s = (10 * _VAL[victim] - _VAL[piece_type_at(m.from_square)]) if victim else -100_000
                scored.append((s, i, m))
            scored.sort(reverse=True, key=lambda t: (t[0], -t[1]))
            best = -INF
            for _, _, m in scored:
                board.push(m)
                score = -self._quiesce(board, -beta, -alpha, ply + 1, qdepth - 1)
                board.pop()
                if score > best:
                    best = score
                    if score > alpha:
                        alpha = score
                        self._pv[ply] = [m] + self._pv[ply + 1]
                        if alpha >= beta:
                            break
            return best

        stand = self._evaluate(board)
        if stand >= beta:
            return stand
        if qdepth <= 0:
            return stand
        if stand > alpha:
            alpha = stand
        best = stand

        ep = board.ep_square
        candidates = []
        for m in board.generate_legal_captures():
            if m.promotion and m.promotion != chess.QUEEN:
                continue
            victim = piece_type_at(m.to_square)
            if victim is None:  # en passant
                victim = chess.PAWN
            gain = _VAL[victim] + (_VAL[m.promotion] - _VAL[chess.PAWN] if m.promotion else 0)
            if stand + gain + 200 < alpha:
                continue  # delta pruning
            candidates.append((10 * gain - _VAL[piece_type_at(m.from_square)], m))
        # Quiet queen promotions.
        if board.turn == chess.WHITE:
            pawns7 = board.pawns & board.occupied_co[chess.WHITE] & chess.BB_RANK_7
            to_mask = chess.BB_RANK_8 & ~board.occupied
        else:
            pawns7 = board.pawns & board.occupied_co[chess.BLACK] & chess.BB_RANK_2
            to_mask = chess.BB_RANK_1 & ~board.occupied
        if pawns7 and to_mask:
            for m in board.generate_legal_moves(pawns7, to_mask):
                if m.promotion == chess.QUEEN:
                    candidates.append((10 * (_VAL[chess.QUEEN] - _VAL[chess.PAWN]), m))
        if not candidates:
            return best
        candidates.sort(key=lambda t: t[0], reverse=True)
        for _, m in candidates:
            board.push(m)
            score = -self._quiesce(board, -beta, -alpha, ply + 1, qdepth - 1)
            board.pop()
            if score > best:
                best = score
                if score > alpha:
                    alpha = score
                    self._pv[ply] = [m] + self._pv[ply + 1]
                    if alpha >= beta:
                        break
        return best
