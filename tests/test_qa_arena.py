"""QA tests for chessbot.arena: referee correctness and Elo maths."""

from __future__ import annotations

import math

import chess
import pytest

from chessbot.arena import (
    DEFAULT_OPENINGS,
    IllegalMoveError,
    MatchResult,
    elo_from_score,
    is_improvement,
    play_game,
    play_match,
)
from chessbot.players import RandomPlayer


class ScriptedPlayer:
    """Plays a fixed list of UCI moves (cycled), recording what it was shown."""

    def __init__(self, ucis, name="scripted"):
        self.name = name
        self.ucis = list(ucis)
        self.i = 0
        self.resets = 0
        self.boards_seen: list[chess.Board] = []

    def select_move(self, board):
        self.boards_seen.append(board)
        mv = chess.Move.from_uci(self.ucis[self.i % len(self.ucis)])
        self.i += 1
        return mv

    def reset(self):
        self.resets += 1
        self.i = 0


class CountingRandom(RandomPlayer):
    def __init__(self, seed, name):
        super().__init__(seed)
        self.name = name
        self.resets = 0

    def reset(self):
        self.resets += 1


def replay(record) -> chess.Board:
    board = chess.Board(record["start_fen"])
    for u in record["moves"]:
        mv = chess.Move.from_uci(u)
        assert mv in board.legal_moves, f"illegal move {u} in {board.fen()}"
        board.push(mv)
    return board


# ---------------------------------------------------------------- play_game
def test_random_vs_random_completes_legally():
    for seed in range(6):
        rec = play_game(RandomPlayer(seed), RandomPlayer(seed + 100), max_moves=400)
        assert set(["white", "black", "result", "moves", "termination"]) <= rec.keys()
        assert rec["result"] in ("1-0", "0-1", "1/2-1/2")
        board = replay(rec)
        assert board.fen() == rec["final_fen"]
        if rec["termination"] == "checkmate":
            assert board.is_checkmate()
            assert rec["result"] == ("0-1" if board.turn == chess.WHITE else "1-0")
        elif rec["termination"] == "max_moves":
            assert len(rec["moves"]) == 400 and rec["result"] == "1/2-1/2"
        else:
            assert rec["result"] == "1/2-1/2"
            assert board.outcome(claim_draw=True) is not None


def test_fools_mate_checkmate_detection():
    white = ScriptedPlayer(["f2f3", "g2g4"], "w")
    black = ScriptedPlayer(["e7e5", "d8h4"], "b")
    rec = play_game(white, black)
    assert rec["result"] == "0-1"
    assert rec["termination"] == "checkmate"
    assert rec["moves"] == ["f2f3", "e7e5", "g2g4", "d8h4"]


def test_illegal_move_raises_with_player_name():
    white = ScriptedPlayer(["e2e5"], "bad_bot")  # pawn cannot jump three squares
    with pytest.raises(IllegalMoveError) as ei:
        play_game(white, RandomPlayer(0))
    assert "bad_bot" in str(ei.value)
    assert ei.value.player_name == "bad_bot"


def test_non_move_return_raises():
    class StrPlayer:
        name = "stringy"

        def select_move(self, board):
            return "e2e4"

        def reset(self):
            pass

    with pytest.raises(IllegalMoveError, match="stringy"):
        play_game(StrPlayer(), RandomPlayer(0))


def test_null_and_pseudolegal_moves_rejected():
    # Black to move in check from a bishop: a pseudo-legal move ignoring the
    # check must be rejected.
    fen = "rnbqkbnr/ppp2ppp/8/1B1pp3/4P3/8/PPPP1PPP/RNBQK1NR b KQkq - 1 3"
    board = chess.Board(fen)
    assert board.is_check()
    pseudo = chess.Move.from_uci("a7a6")
    assert board.is_pseudo_legal(pseudo) and not board.is_legal(pseudo)
    with pytest.raises(IllegalMoveError):
        play_game(RandomPlayer(0), ScriptedPlayer(["a7a6"], "pseudo"), start_fen=fen)
    with pytest.raises(IllegalMoveError):
        play_game(ScriptedPlayer(["0000"], "nullmover"), RandomPlayer(0))


def test_player_cannot_mutate_referee_board():
    class Vandal:
        name = "vandal"

        def __init__(self, seed):
            self.rng = RandomPlayer(seed)

        def select_move(self, board):
            mv = self.rng.select_move(board)
            board.clear()          # wreck the board we were handed
            board.push(chess.Move.null())
            return mv

        def reset(self):
            pass

    rec = play_game(Vandal(1), Vandal(2), max_moves=60)
    replay(rec)  # every move legal on the real board
    assert len(rec["moves"]) > 0


def test_player_receives_copy_with_history():
    white = ScriptedPlayer(["g1f3", "f3g1"], "w")
    black = ScriptedPlayer(["g8f6", "f6g8"], "b")
    play_game(white, black, max_moves=3)
    # The second board shown to white has two plies of history.
    assert len(white.boards_seen[1].move_stack) == 2


def test_max_moves_adjudication():
    rec = play_game(RandomPlayer(3), RandomPlayer(4), max_moves=10)
    assert len(rec["moves"]) <= 10
    if len(rec["moves"]) == 10:
        assert rec["termination"] == "max_moves"
        assert rec["result"] == "1/2-1/2"
    rec0 = play_game(RandomPlayer(3), RandomPlayer(4), max_moves=0)
    assert rec0["moves"] == [] and rec0["termination"] == "max_moves"


def test_threefold_repetition_claimed():
    white = ScriptedPlayer(["g1f3", "f3g1"], "w")
    black = ScriptedPlayer(["g8f6", "f6g8"], "b")
    rec = play_game(white, black, max_moves=100)
    assert rec["termination"] == "threefold_repetition"
    assert rec["result"] == "1/2-1/2"
    assert len(rec["moves"]) == 8


def test_fifty_move_rule_claimed():
    fen = "8/8/8/4k3/8/8/R7/4K3 w - - 99 80"
    rec = play_game(RandomPlayer(0), RandomPlayer(1), start_fen=fen)
    assert rec["termination"] == "fifty_moves"
    assert rec["result"] == "1/2-1/2"
    assert len(rec["moves"]) == 1


def test_mate_on_hundredth_halfmove_beats_fifty_move_claim():
    # halfmove clock 99: the arena must not claim the draw in advance, and a
    # mate delivered on the 100th halfmove counts as a win.
    fen = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 99 80"
    rec = play_game(ScriptedPlayer(["a1a8"], "w"), RandomPlayer(0), start_fen=fen)
    assert rec["termination"] == "checkmate" and rec["result"] == "1-0"


def test_no_threefold_claim_in_advance():
    # After 7 plies white *could* repeat a third time; the game must continue
    # until the repetition actually happens (ply 8). Here white deviates.
    white = ScriptedPlayer(["g1f3", "f3g1", "g1f3", "f3g1", "b1c3"], "w")
    black = ScriptedPlayer(["g8f6", "f6g8", "g8f6", "f6g8", "b8c6"], "b")
    rec = play_game(white, black, max_moves=12)
    assert rec["termination"] in ("threefold_repetition", "max_moves")
    assert len(rec["moves"]) >= 8


def test_insufficient_material_and_stalemate():
    rec = play_game(RandomPlayer(0), RandomPlayer(1), start_fen="8/8/8/4k3/8/8/8/4K3 w - - 0 1")
    assert rec["termination"] == "insufficient_material" and rec["moves"] == []
    rec = play_game(RandomPlayer(0), RandomPlayer(1), start_fen="7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
    assert rec["termination"] == "stalemate" and rec["result"] == "1/2-1/2"


# ---------------------------------------------------------------- play_match
def test_match_alternates_colors_and_resets():
    a, b = CountingRandom(1, "A"), CountingRandom(2, "B")
    res = play_match(a, b, games=6, max_moves=60)
    assert res.wins + res.draws + res.losses == 6 == len(res.games)
    for i, g in enumerate(res.games):
        if i % 2 == 0:
            assert (g["white"], g["black"]) == ("A", "B") and g["a_is_white"]
        else:
            assert (g["white"], g["black"]) == ("B", "A") and not g["a_is_white"]
        replay(g)
    assert a.resets == 6 and b.resets == 6
    # Tally recomputed from game records must match.
    pts = sum(g["a_score"] for g in res.games)
    assert math.isclose(pts, res.wins + 0.5 * res.draws)


def test_match_no_alternation():
    res = play_match(CountingRandom(1, "A"), CountingRandom(2, "B"), games=3,
                     max_moves=20, alternate_colors=False)
    assert all(g["white"] == "A" for g in res.games)


def test_match_openings_cycled_in_colour_pairs():
    res = play_match(RandomPlayer(1), RandomPlayer(2), games=6, max_moves=4,
                     openings=DEFAULT_OPENINGS[:2])
    starts = [g["start_fen"] for g in res.games]
    o0, o1 = DEFAULT_OPENINGS[0], DEFAULT_OPENINGS[1]
    assert starts == [o0, o0, o1, o1, o0, o0]


def test_default_openings_valid_and_balanced():
    assert len(DEFAULT_OPENINGS) >= 8
    assert len(set(DEFAULT_OPENINGS)) == len(DEFAULT_OPENINGS)
    for fen in DEFAULT_OPENINGS:
        board = chess.Board(fen)
        assert board.is_valid()
        assert board.turn == chess.WHITE
        assert board.outcome() is None
        # material is equal (no captures in the opening lines)
        for pt in chess.PIECE_TYPES:
            assert len(board.pieces(pt, chess.WHITE)) == len(board.pieces(pt, chess.BLACK))


def test_scripted_mate_result_attributed_to_correct_player():
    # B always plays the fool's-mate side; with A as white, B wins; A loses.
    a = ScriptedPlayer(["f2f3", "g2g4"], "A")
    b = ScriptedPlayer(["e7e5", "d8h4"], "B")
    res = play_match(a, b, games=1, alternate_colors=False)
    assert (res.wins, res.draws, res.losses) == (0, 0, 1)
    assert res.score == 0.0


# ---------------------------------------------------------------- Elo maths
def test_elo_50_percent_is_zero():
    r = MatchResult(wins=5, losses=5, draws=0)
    est, lo, hi = r.elo_diff()
    assert est == pytest.approx(0.0, abs=1e-9)
    assert lo < 0 < hi
    assert lo == pytest.approx(-hi)
    assert MatchResult(wins=0, losses=0, draws=10).elo_diff()[0] == pytest.approx(0.0)


def test_elo_75_percent():
    r = MatchResult(wins=30, losses=10, draws=0)
    assert r.score == 0.75
    est, lo, hi = r.elo_diff()
    assert est == pytest.approx(190.85, abs=0.05)
    assert lo < est < hi
    assert elo_from_score(0.25) == pytest.approx(-190.85, abs=0.05)


def test_elo_ci_narrows_with_more_games():
    small = MatchResult(wins=6, losses=2, draws=2)
    big = MatchResult(wins=60, losses=20, draws=20)
    assert small.score == big.score
    _, slo, shi = small.elo_diff()
    _, blo, bhi = big.elo_diff()
    assert (bhi - blo) < (shi - slo)


def test_elo_smoothing_at_extremes_is_finite():
    allwin = MatchResult(wins=10)
    est, lo, hi = allwin.elo_diff()
    assert all(math.isfinite(x) for x in (est, lo, hi))
    assert est == pytest.approx(elo_from_score(10.5 / 11))
    assert lo < est <= hi and est > 0
    allloss = MatchResult(losses=10)
    e2, l2, h2 = allloss.elo_diff()
    assert e2 == pytest.approx(-est)
    assert l2 <= e2 < h2
    alldraw = MatchResult(draws=10)
    e3, l3, h3 = alldraw.elo_diff()
    assert l3 < e3 < h3  # variance floor keeps interval non-degenerate


def test_elo_empty_match():
    est, lo, hi = MatchResult().elo_diff()
    assert est == 0.0 and lo == -math.inf and hi == math.inf
    assert MatchResult().score == 0.5


def test_is_improvement():
    assert is_improvement(MatchResult(wins=80, losses=10, draws=10))
    assert not is_improvement(MatchResult(wins=6, losses=4, draws=0))
    assert not is_improvement(MatchResult(wins=10, losses=10, draws=0))
    strong = MatchResult(wins=80, losses=10, draws=10)
    lo = strong.elo_diff()[1]
    assert is_improvement(strong, threshold_elo=lo - 1)
    assert not is_improvement(strong, threshold_elo=lo + 1)
