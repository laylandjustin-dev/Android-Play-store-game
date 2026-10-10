import chess
import pytest

from chessbot.players import RandomPlayer, make_player


def test_random_player_legal_and_seeded():
    board = chess.Board()
    a, b = RandomPlayer(seed=1), RandomPlayer(seed=1)
    for _ in range(20):
        m = a.select_move(board)
        assert m in board.legal_moves
        assert b.select_move(board) == m
        board.push(m)


def test_unknown_kind_rejected(tiny_config):
    with pytest.raises(ValueError):
        make_player("stockfish", tiny_config)


@pytest.mark.parametrize("kind", ["alphabeta", "mcts"])
def test_players_return_legal_moves(kind, tiny_config):
    player = make_player(kind, tiny_config, seed=0)
    board = chess.Board("r3k2r/pPppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
    fen = board.fen()
    move = player.select_move(board)
    assert move in board.legal_moves
    assert board.fen() == fen


def test_missing_checkpoint_raises(tiny_config):
    with pytest.raises(FileNotFoundError):
        make_player("alphabeta_nn", tiny_config, checkpoint="latest")
    with pytest.raises(FileNotFoundError):
        make_player("mcts", tiny_config, checkpoint="nope.pt")


def test_leaf_batch_size_setting_is_honoured(tiny_config, monkeypatch):
    import chessbot.ml.selfplay as selfplay
    from chessbot.ml.model import PolicyValueNet

    tiny_config.mcts.leaf_batch_size = 3
    assert make_player("mcts", tiny_config).mcts.leaf_batch_size == 3
    tiny_config.mcts.leaf_batch_size = None
    assert make_player("mcts", tiny_config).mcts.leaf_batch_size == 1

    seen = []
    real = selfplay.MCTS

    def spy(*a, **kw):
        seen.append(kw["leaf_batch_size"])
        return real(*a, **kw)

    monkeypatch.setattr(selfplay, "MCTS", spy)
    tiny_config.mcts.leaf_batch_size = 2
    tiny_config.selfplay.max_moves = 4
    selfplay.play_selfplay_game(PolicyValueNet(tiny_config.model), tiny_config.mcts,
                                tiny_config.selfplay, rng=0)
    assert seen == [2]
