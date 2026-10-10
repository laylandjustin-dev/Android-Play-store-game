"""Lead-owned integration regressions found during QA review."""

import chess

from chessbot.arena import game_over
from chessbot.web.app import _result_info


def test_claimable_draw_does_not_end_game():
    # 99 halfmoves, white has mate in one: python-chess's claim_draw=True calls this a draw.
    board = chess.Board("7k/R7/6K1/8/8/8/8/8 w - - 99 120")
    assert board.is_game_over(claim_draw=True)
    assert game_over(board) == (False, None, None)
    assert _result_info(board) == (False, None, None)
    board.push_san("Ra8#")
    assert game_over(board) == (True, "1-0", "checkmate")


def test_twofold_repetition_is_not_threefold():
    board = chess.Board()
    for san in ["Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1"]:
        board.push_san(san)
    assert not game_over(board)[0]
    board.push_san("Ng8")   # start position now occurred three times
    assert game_over(board) == (True, "1/2-1/2", "threefold_repetition")
