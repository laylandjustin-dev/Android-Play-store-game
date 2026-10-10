"""Match play between two players and Elo estimation (QA & Performance).

The arena is the *referee*: it owns the only authoritative board, hands each
player a copy, and verifies every returned move against the real legal move
list before applying it. A player that returns an illegal move (or something
that is not a :class:`chess.Move`) causes :class:`IllegalMoveError`.

Game termination (checked before every move, in this order by python-chess):
checkmate, stalemate, insufficient material, seventy-five-move rule,
fivefold repetition, and the *claimable* draws (threefold repetition,
fifty-move rule) once they have actually occurred on the board, which the
arena always claims on behalf of the players (never "in advance").
If ``max_moves`` plies have been played without a result, the game is
adjudicated a draw with termination ``"max_moves"``.

Elo estimate
------------
``MatchResult.elo_diff()`` converts the match score ``s`` of player A to an
Elo difference with the logistic model ``elo = -400 * log10(1/s - 1)``.

* Smoothing: a score of exactly 0 or 1 maps to -inf/+inf, so in that case
  (and only then) the score is replaced by ``(points + 0.5) / (n + 1)``,
  i.e. one extra pseudo-game scored as half a point. Any other score is
  used unchanged (so 50% -> 0 Elo exactly, 75% -> +190.8 Elo).
* 95% interval: per-game scores ``x_i in {0, 0.5, 1}`` give the sample
  variance ``var = mean((x_i - s)^2)`` around the (possibly smoothed) score
  ``s``; the standard error is ``sqrt(var / n)``. The score interval
  ``s +- 1.96 * se`` is clamped to ``[1e-3, 1 - 1e-3]`` and mapped through
  the same logistic formula. Because uniform results (e.g. all draws) have
  zero sample variance, the per-game variance is floored at
  ``0.25 / (n + 1)`` (the variance a single pseudo-decisive game would add)
  so the interval is never degenerate. With few games the interval is
  wide; that is the honest answer.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any

import chess

__all__ = [
    "IllegalMoveError",
    "MatchResult",
    "DEFAULT_OPENINGS",
    "OPENING_LINES",
    "play_game",
    "play_match",
    "elo_from_score",
    "is_improvement",
]


class IllegalMoveError(RuntimeError):
    """A player returned a move that is not legal in the current position."""

    def __init__(self, player_name: str, move: Any, fen: str):
        self.player_name = player_name
        self.move = move
        self.fen = fen
        super().__init__(
            f"player {player_name!r} returned illegal move {move!r} in position {fen}"
        )


# --------------------------------------------------------------------------
# Openings: short, well-known, roughly balanced lines (built from SAN at import
# so the FENs are guaranteed to be correct).
# --------------------------------------------------------------------------
OPENING_LINES: dict[str, list[str]] = {
    "open_game": ["e4", "e5", "Nf3", "Nc6"],
    "queens_gambit_declined": ["d4", "d5", "c4", "e6"],
    "sicilian": ["e4", "c5", "Nf3", "d6"],
    "french": ["e4", "e6", "d4", "d5"],
    "caro_kann": ["e4", "c6", "d4", "d5"],
    "kings_indian": ["d4", "Nf6", "c4", "g6"],
    "english": ["c4", "e5", "Nc3", "Nf6"],
    "reti": ["Nf3", "d5", "g3", "Nf6"],
}


def _line_to_fen(sans: list[str]) -> str:
    board = chess.Board()
    for san in sans:
        board.push_san(san)
    return board.fen()


DEFAULT_OPENINGS: list[str] = [_line_to_fen(line) for line in OPENING_LINES.values()]


# --------------------------------------------------------------------------
# Single game
# --------------------------------------------------------------------------
_RESULT_STR = {True: "1-0", False: "0-1", None: "1/2-1/2"}


def _game_over(board: chess.Board) -> tuple[bool, tuple[str, str]]:
    """Referee check. Returns (ended, (result, termination)).

    Note: ``board.outcome(claim_draw=True)`` is deliberately NOT used: in
    python-chess it also ends the game when the side to move merely *could*
    reach a threefold repetition / 100 halfmoves with its next move, which
    would force a draw on a side that might be winning. The arena only
    claims draws that have actually occurred on the board.
    """
    outcome = board.outcome(claim_draw=False)
    if outcome is not None:
        return True, (_RESULT_STR[outcome.winner], outcome.termination.name.lower())
    if board.halfmove_clock >= 100:
        return True, ("1/2-1/2", "fifty_moves")
    if board.is_repetition(3):
        return True, ("1/2-1/2", "threefold_repetition")
    return False, ("", "")


def game_over(board: chess.Board) -> tuple[bool, str | None, str | None]:
    """Public referee check: (ended, result like "1-0", termination name)."""
    ended, (result, termination) = _game_over(board)
    return (True, result, termination) if ended else (False, None, None)


def _player_name(player: Any) -> str:
    return str(getattr(player, "name", type(player).__name__))


def play_game(white: Any, black: Any, max_moves: int = 300,
              start_fen: str | None = None) -> dict:
    """Play one game and return a record.

    Returns ``{"white", "black", "result", "moves", "termination"}`` plus the
    informational keys ``"start_fen"``, ``"plies"`` and ``"final_fen"``.
    ``max_moves`` counts plies played in this game (not from the FEN's
    fullmove number).
    """
    if max_moves < 0:
        raise ValueError("max_moves must be >= 0")
    board = chess.Board(start_fen) if start_fen else chess.Board()
    start = board.fen()
    names = {chess.WHITE: _player_name(white), chess.BLACK: _player_name(black)}
    players = {chess.WHITE: white, chess.BLACK: black}
    moves: list[str] = []

    result, termination = "1/2-1/2", "max_moves"
    while True:
        ended, result_t = _game_over(board)
        if ended:
            result, termination = result_t
            break
        if len(moves) >= max_moves:
            break
        side = board.turn
        # The player only ever sees a copy (with move stack, so it can detect
        # repetitions); it cannot corrupt the referee's board.
        move = players[side].select_move(board.copy())
        if not isinstance(move, chess.Move) or not board.is_legal(move):
            raise IllegalMoveError(names[side], move, board.fen())
        moves.append(move.uci())
        board.push(move)

    return {
        "white": names[chess.WHITE],
        "black": names[chess.BLACK],
        "result": result,
        "moves": moves,
        "termination": termination,
        "start_fen": start,
        "plies": len(moves),
        "final_fen": board.fen(),
    }


# --------------------------------------------------------------------------
# Elo maths
# --------------------------------------------------------------------------
_Z95 = 1.959963984540054
_SCORE_CLAMP = 1e-3


def elo_from_score(score: float) -> float:
    """Logistic Elo difference for an expected score in (0, 1)."""
    score = min(max(score, _SCORE_CLAMP), 1.0 - _SCORE_CLAMP)
    return -400.0 * math.log10(1.0 / score - 1.0) + 0.0  # + 0.0 normalises -0.0


@dataclass
class MatchResult:
    wins: int = 0     # from player A's perspective
    losses: int = 0
    draws: int = 0
    games: list[dict] = field(default_factory=list)
    player_a: str = "A"
    player_b: str = "B"

    @property
    def n(self) -> int:
        return self.wins + self.losses + self.draws

    @property
    def score(self) -> float:
        """(wins + 0.5 * draws) / n; 0.5 for an empty match."""
        n = self.n
        if n == 0:
            return 0.5
        return (self.wins + 0.5 * self.draws) / n

    def _smoothed_score(self) -> float:
        n, points = self.n, self.wins + 0.5 * self.draws
        if points <= 0 or points >= n:
            return (points + 0.5) / (n + 1)
        return points / n

    def elo_diff(self) -> tuple[float, float, float]:
        """(estimate, low, high) Elo of A relative to B, 95% interval.

        An empty match returns ``(0.0, -inf, inf)``. See module docstring for
        the smoothing and variance-floor rules.
        """
        n = self.n
        if n == 0:
            return 0.0, -math.inf, math.inf
        s = self._smoothed_score()
        var = (self.wins * (1.0 - s) ** 2 + self.draws * (0.5 - s) ** 2
               + self.losses * (0.0 - s) ** 2) / n
        var = max(var, 0.25 / (n + 1))
        se = math.sqrt(var / n)
        return (elo_from_score(s), elo_from_score(s - _Z95 * se),
                elo_from_score(s + _Z95 * se))

    def summary(self) -> str:
        est, lo, hi = self.elo_diff()
        return (f"{self.player_a} vs {self.player_b}: +{self.wins} ={self.draws} "
                f"-{self.losses} (n={self.n}) score={self.score:.3f} "
                f"elo={est:+.0f} [{lo:+.0f}, {hi:+.0f}]")


def is_improvement(result: MatchResult, threshold_elo: float = 0.0) -> bool:
    """True iff the lower bound of the 95% Elo interval exceeds ``threshold_elo``."""
    return result.elo_diff()[1] > threshold_elo


# --------------------------------------------------------------------------
# Match
# --------------------------------------------------------------------------
def play_match(a: Any, b: Any, games: int, max_moves: int = 300,
               openings: list[str] | None = None, alternate_colors: bool = True,
               progress: Any = None) -> MatchResult:
    """Play ``games`` games between ``a`` and ``b``; result from A's perspective.

    * ``alternate_colors``: A is white in even-numbered games, black in odd.
    * ``openings``: list of start FENs cycled through. With alternating colours
      each opening is used for two consecutive games (one per colour) so the
      pair is colour-balanced. ``None`` starts every game from the initial
      position. Pass :data:`DEFAULT_OPENINGS` for variety (important for
      deterministic players, which otherwise repeat the same game).
    * ``reset()`` is called on both players before every game.
    * ``progress``: optional callable ``(game_index, game_record)``.

    Each game record additionally carries ``"a_is_white"`` and
    ``"a_score"`` (1, 0.5 or 0).
    """
    if games < 0:
        raise ValueError("games must be >= 0")
    if openings is not None and len(openings) == 0:
        openings = None
    res = MatchResult(player_a=_player_name(a), player_b=_player_name(b))
    for i in range(games):
        a_white = (i % 2 == 0) if alternate_colors else True
        fen = None
        if openings:
            idx = (i // 2) if alternate_colors else i
            fen = openings[idx % len(openings)]
        for p in (a, b):
            reset = getattr(p, "reset", None)
            if callable(reset):
                reset()
        white, black = (a, b) if a_white else (b, a)
        rec = play_game(white, black, max_moves=max_moves, start_fen=fen)
        if rec["result"] == "1/2-1/2":
            a_score = 0.5
            res.draws += 1
        elif (rec["result"] == "1-0") == a_white:
            a_score = 1.0
            res.wins += 1
        else:
            a_score = 0.0
            res.losses += 1
        rec["a_is_white"] = a_white
        rec["a_score"] = a_score
        res.games.append(rec)
        if progress is not None:
            progress(i, rec)
    return res
