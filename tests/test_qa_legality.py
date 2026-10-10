"""QA legality verification.

1. Environment sanity: python-chess perft against published reference values.
2. Integration legality: every player kind (random / alphabeta / mcts with an
   untrained tiny network) must return legal moves from many random and
   special-move positions, must not mutate the board it is given, and must
   play complete legal games through the arena. Integration tests skip
   cleanly if the engine / ML modules are not implemented yet.
"""

from __future__ import annotations

import random

import chess
import pytest

from chessbot.arena import play_game
from chessbot.players import RandomPlayer

KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"


def perft(board: chess.Board, depth: int) -> int:
    if depth == 0:
        return 1
    if depth == 1:
        return board.legal_moves.count()
    n = 0
    for mv in board.legal_moves:
        board.push(mv)
        n += perft(board, depth - 1)
        board.pop()
    return n


@pytest.mark.parametrize("fen,depth,expected", [
    (chess.STARTING_FEN, 1, 20),
    (chess.STARTING_FEN, 2, 400),
    (chess.STARTING_FEN, 3, 8902),
    (KIWIPETE, 1, 48),
    (KIWIPETE, 2, 2039),
    # Position 3 from the chessprogramming wiki (en passant / pins / checks)
    ("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 3, 2812),
    # Position 4 (castling, promotions incl. underpromotions)
    ("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 2, 264),
])
def test_perft_reference_values(fen, depth, expected):
    board = chess.Board(fen)
    assert perft(board, depth) == expected
    assert board.fen() == fen  # perft restores the board


# --------------------------------------------------------------------------
# Position sets
# --------------------------------------------------------------------------
SPECIAL_POSITIONS = {
    # both sides may castle on both wings
    "castling_both": "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1",
    # black to move, may castle; white rights gone
    "castling_black": "r3k2r/pppq1ppp/2npbn2/4p3/4P3/2NPBN2/PPPQ1PPP/R4RK1 b kq - 0 1",
    # castling through an attacked square is illegal (f1 attacked by bishop)
    "castling_through_check": "4k3/8/8/8/2b5/8/8/4K2R w K - 0 1",
    # white en passant exf6 available
    "en_passant_white": "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
    # black en passant dxe3 available
    "en_passant_black": "rnbqkbnr/ppp1pppp/8/8/3pP3/5N2/PPPP1PPP/RNBQKB1R b KQkq e3 0 3",
    # en passant that would expose the king is illegal (horizontal pin)
    "en_passant_pinned": "8/8/8/K2pP2r/8/8/8/7k w - d6 0 1",
    # promotion (straight and capturing), all four piece types legal
    "promotion_white": "1n5k/P7/8/8/8/8/8/K7 w - - 0 1",
    "promotion_black": "k7/8/8/8/8/8/p7/1N5K b - - 0 1",
    # underpromotion: e8=Q / e8=R stalemate-or-lose positions etc.
    "underpromotion": "8/4P1k1/8/8/8/8/1p6/K7 w - - 0 1",
    # in check: only evasions legal
    "in_check": "rnbqkbnr/ppp2ppp/8/1B1pp3/4P3/8/PPPP1PPP/RNBQK1NR b KQkq - 1 3",
    # single legal move
    "single_move": "7k/8/8/8/8/8/6q1/7K w - - 0 1",
    "kiwipete": KIWIPETE,
}


def random_positions(n: int, seed: int = 1234, max_plies: int = 80) -> list[chess.Board]:
    rng = random.Random(seed)
    out: list[chess.Board] = []
    while len(out) < n:
        board = chess.Board()
        for _ in range(rng.randint(0, max_plies)):
            if board.is_game_over():
                break
            board.push(rng.choice(list(board.legal_moves)))
        if not board.is_game_over(claim_draw=False):
            out.append(board)
    return out


def test_special_positions_valid():
    for name, fen in SPECIAL_POSITIONS.items():
        b = chess.Board(fen)
        assert b.is_valid(), name
        assert any(b.legal_moves), name
    b = chess.Board(SPECIAL_POSITIONS["castling_through_check"])
    assert chess.Move.from_uci("e1g1") not in b.legal_moves
    b = chess.Board(SPECIAL_POSITIONS["en_passant_pinned"])
    assert chess.Move.from_uci("e5d6") not in b.legal_moves
    b = chess.Board(SPECIAL_POSITIONS["en_passant_white"])
    assert b.has_legal_en_passant()
    b = chess.Board(SPECIAL_POSITIONS["single_move"])
    assert b.legal_moves.count() == 1


def _snapshot(board: chess.Board):
    return board.fen(), list(board.move_stack), board.castling_rights, board.ep_square


def _check_player_on_positions(player, boards):
    for board in boards:
        before = _snapshot(board)
        mv = player.select_move(board)
        assert isinstance(mv, chess.Move), f"{player.name} returned {mv!r}"
        assert mv in board.legal_moves, f"{player.name} illegal {mv} in {board.fen()}"
        assert _snapshot(board) == before, f"{player.name} mutated board {before[0]}"


def _special_boards():
    boards = [chess.Board(f) for f in SPECIAL_POSITIONS.values()]
    # also a board with move history (repetition-relevant)
    b = chess.Board()
    for u in ["g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1"]:
        b.push_uci(u)
    boards.append(b)
    return boards


# --------------------------------------------------------------------------
# Random player (always available)
# --------------------------------------------------------------------------
def test_random_player_legal_everywhere():
    _check_player_on_positions(RandomPlayer(0), random_positions(50) + _special_boards())


# --------------------------------------------------------------------------
# Alpha-beta
# --------------------------------------------------------------------------
@pytest.fixture
def alphabeta(tiny_config):
    pytest.importorskip("chessbot.engine.search")
    pytest.importorskip("chessbot.engine.evaluation")
    from chessbot.players import AlphaBetaPlayer

    return AlphaBetaPlayer(tiny_config)


def test_alphabeta_legal_random_positions(alphabeta):
    _check_player_on_positions(alphabeta, random_positions(30, seed=7))


def test_alphabeta_legal_special_positions(alphabeta):
    _check_player_on_positions(alphabeta, _special_boards())


def test_alphabeta_search_does_not_mutate_and_reports(tiny_config):
    pytest.importorskip("chessbot.engine.search")
    from chessbot.engine.search import AlphaBetaSearcher

    searcher = AlphaBetaSearcher(config=tiny_config.search)
    for fen in SPECIAL_POSITIONS.values():
        board = chess.Board(fen)
        before = _snapshot(board)
        res = searcher.search(board, depth=2)
        assert _snapshot(board) == before
        assert res.best_move in board.legal_moves
        assert res.depth >= 1 and res.nodes > 0
        # PV, if given, must be a legal line starting with best_move
        if res.pv:
            assert res.pv[0] == res.best_move
            tmp = board.copy()
            for mv in res.pv:
                assert mv in tmp.legal_moves
                tmp.push(mv)


def test_alphabeta_no_legal_moves_returns_none(tiny_config):
    pytest.importorskip("chessbot.engine.search")
    from chessbot.engine.search import AlphaBetaSearcher

    searcher = AlphaBetaSearcher(config=tiny_config.search)
    mate = chess.Board("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
    assert searcher.search(mate, depth=2).best_move is None
    stale = chess.Board("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
    assert searcher.search(stale, depth=2).best_move is None


def test_alphabeta_finds_mate_in_one_and_promotes(tiny_config):
    pytest.importorskip("chessbot.engine.search")
    from chessbot.engine.evaluation import MATE_SCORE
    from chessbot.engine.search import AlphaBetaSearcher

    searcher = AlphaBetaSearcher(config=tiny_config.search)
    res = searcher.search(chess.Board("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"), depth=2)
    assert res.best_move == chess.Move.from_uci("a1a8")
    assert res.score >= MATE_SCORE - 10
    searcher.clear()
    # Black king attacks e7: promoting now is the only way to keep the pawn.
    # (In a quiet position a depth-2 search may legitimately delay the
    # promotion because quiescence already sees it.)
    res = searcher.search(chess.Board("8/4P3/5k2/8/8/8/8/K7 w - - 0 1"), depth=2)
    assert res.best_move == chess.Move.from_uci("e7e8q")


def test_alphabeta_full_games_legal(alphabeta):
    for seed in range(2):
        rec = play_game(alphabeta, RandomPlayer(seed), max_moves=40)
        _replay(rec)
        rec = play_game(RandomPlayer(seed), alphabeta, max_moves=40)
        _replay(rec)


# --------------------------------------------------------------------------
# MCTS (untrained tiny network)
# --------------------------------------------------------------------------
@pytest.fixture
def mcts_player(tiny_config):
    pytest.importorskip("torch")
    pytest.importorskip("chessbot.ml.mcts")
    pytest.importorskip("chessbot.ml.model")
    pytest.importorskip("chessbot.ml.checkpoints")
    from chessbot.players import MCTSPlayer

    return MCTSPlayer(tiny_config, checkpoint=None, simulations=8, seed=0)


def test_mcts_legal_random_positions(mcts_player):
    _check_player_on_positions(mcts_player, random_positions(25, seed=11))


def test_mcts_legal_special_positions(mcts_player):
    _check_player_on_positions(mcts_player, _special_boards())


def test_mcts_visits_only_legal_and_no_mutation(tiny_config):
    pytest.importorskip("chessbot.ml.mcts")
    import numpy as np

    from chessbot.ml.mcts import MCTS
    from chessbot.ml.model import PolicyValueNet

    model = PolicyValueNet(tiny_config.model).eval()
    mcts = MCTS(model, tiny_config.mcts, device="cpu", rng=np.random.default_rng(0))
    for board in _special_boards():
        before = _snapshot(board)
        visits = mcts.run(board, add_noise=True)
        assert _snapshot(board) == before
        assert visits, board.fen()
        assert all(m in board.legal_moves for m in visits)
        move, dist = mcts.select_move(board, temperature=1.0, add_noise=True)
        assert move in board.legal_moves
        assert all(m in board.legal_moves for m in dist)
        assert abs(sum(dist.values()) - 1.0) < 1e-5
        assert _snapshot(board) == before


def test_encoding_bijective_on_special_moves():
    pytest.importorskip("chessbot.ml.encoding")
    from chessbot.ml.encoding import POLICY_SIZE, index_to_move, legal_move_mask, move_to_index

    boards = _special_boards() + random_positions(40, seed=3)
    saw_under = saw_ep = saw_castle = False
    for board in boards:
        idxs = set()
        for mv in board.legal_moves:
            i = move_to_index(mv, board)
            assert 0 <= i < POLICY_SIZE
            assert index_to_move(i, board) == mv, (board.fen(), mv)
            idxs.add(i)
            saw_under |= mv.promotion not in (None, chess.QUEEN)
            saw_ep |= board.is_en_passant(mv)
            saw_castle |= board.is_castling(mv)
        assert len(idxs) == board.legal_moves.count()
        mask = legal_move_mask(board)
        assert mask.sum() == len(idxs) and all(mask[i] for i in idxs)
    assert saw_under and saw_ep and saw_castle


def test_mcts_full_games_legal(mcts_player):
    rec = play_game(mcts_player, RandomPlayer(0), max_moves=30)
    _replay(rec)
    rec = play_game(RandomPlayer(1), mcts_player, max_moves=30)
    _replay(rec)


def _replay(rec):
    board = chess.Board(rec["start_fen"])
    for u in rec["moves"]:
        mv = chess.Move.from_uci(u)
        assert mv in board.legal_moves
        board.push(mv)
