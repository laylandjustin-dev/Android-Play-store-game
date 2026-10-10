import chess
import numpy as np
import pytest
import torch

from chessbot.config import Config, MCTSConfig, ModelConfig, SelfPlayConfig
from chessbot.ml.checkpoints import (list_checkpoints, load_checkpoint, load_model,
                                     prune_checkpoints, resolve_checkpoint, save_checkpoint)
from chessbot.ml.encoding import NUM_PLANES, POLICY_SIZE, encode_board, legal_move_mask
from chessbot.ml.model import PolicyValueNet
from chessbot.ml.replay_buffer import ReplayBuffer
from chessbot.ml.selfplay import play_selfplay_game

TINY = ModelConfig(channels=16, res_blocks=1, value_hidden=32)


def make_sample(rng, k):
    state = (rng.random((NUM_PLANES, 8, 8)) < 0.2).astype(np.float32)
    policy = np.zeros(POLICY_SIZE, dtype=np.float32)
    idx = rng.choice(POLICY_SIZE, size=k, replace=False)
    policy[idx] = rng.random(k).astype(np.float32) + 0.01
    policy /= policy.sum()
    return state, policy, float(rng.choice([-1.0, 0.0, 1.0]))


def test_buffer_ring_and_capacity():
    rng = np.random.default_rng(0)
    buf = ReplayBuffer(5, seed=0)
    samples = [make_sample(rng, 10) for _ in range(8)]
    for s in samples[:3]:
        buf.add(*s)
    assert len(buf) == 3
    buf.add_game(samples[3:])
    assert len(buf) == 5 and buf.total_added == 8
    states, policies, values = buf.get_all()
    for got, exp in zip(states, samples[3:]):           # oldest 3 overwritten
        assert np.array_equal(got, exp[0])
    np.testing.assert_allclose(policies, np.stack([s[1] for s in samples[3:]]), atol=2e-3)
    np.testing.assert_array_equal(values, [s[2] for s in samples[3:]])
    s, p, v = buf.sample(16)
    assert s.shape == (16, NUM_PLANES, 8, 8) and s.dtype == np.float32
    assert p.shape == (16, POLICY_SIZE) and p.dtype == np.float32
    assert v.shape == (16,) and v.dtype == np.float32
    np.testing.assert_allclose(p.sum(axis=1), 1.0, atol=1e-5)


def test_buffer_seeded_sampling():
    rng = np.random.default_rng(1)
    data = [make_sample(rng, 5) for _ in range(20)]
    a, b = ReplayBuffer(50, seed=3), ReplayBuffer(50, seed=3)
    a.add_game(data)
    b.add_game(data)
    for x, y in zip(a.sample(8), b.sample(8)):
        assert np.array_equal(x, y)


def test_buffer_save_load_round_trip(tmp_path):
    rng = np.random.default_rng(2)
    buf = ReplayBuffer(10, seed=0)
    data = [make_sample(rng, 218) for _ in range(14)]   # wraps around; 218 = max legal moves
    buf.add_game(data)
    path = tmp_path / "buf.npz"
    buf.save(path)
    loaded = ReplayBuffer.load(path)
    assert loaded.capacity == 10 and len(loaded) == 10
    for x, y in zip(buf.get_all(), loaded.get_all()):
        assert np.array_equal(x, y)
    # loading into a smaller buffer keeps the newest samples
    small = ReplayBuffer.load(path, capacity=4)
    assert len(small) == 4
    assert np.array_equal(small.get_all()[0], buf.get_all()[0][-4:])
    small.add(*data[0])   # still a working ring buffer
    assert len(small) == 4
    # file is compact: far smaller than dense float32 policies would be
    assert path.stat().st_size < 10 * POLICY_SIZE * 4


def check_value_targets(rec):
    assert len(rec.samples) == len(rec.moves)
    for ply, (state, policy, value) in enumerate(rec.samples):
        assert state.shape == (NUM_PLANES, 8, 8)
        assert policy.shape == (POLICY_SIZE,)
        assert abs(policy.sum() - 1.0) < 1e-5
        if rec.result == 0.5:
            assert value == 0.0
        else:
            white_to_move = ply % 2 == 0
            white_won = rec.result == 1.0
            assert value == (1.0 if white_to_move == white_won else -1.0)


def test_selfplay_game_completes():
    torch.manual_seed(0)
    model = PolicyValueNet(TINY).eval()
    mcts = MCTSConfig(simulations=8, temperature_moves=10)
    rec = play_selfplay_game(model, mcts, SelfPlayConfig(max_moves=30), rng=np.random.default_rng(0))
    assert 1 <= len(rec.moves) <= 30
    assert rec.result in (0.0, 0.5, 1.0)
    if len(rec.moves) == 30:
        assert rec.termination == "max_moves" and rec.result == 0.5
    check_value_targets(rec)
    # visit distributions are restricted to legal moves of the recorded position
    b = chess.Board()
    for (state, policy, _), uci in zip(rec.samples, rec.moves):
        assert np.array_equal(state, encode_board(b))
        assert not np.any(policy[~legal_move_mask(b)])
        b.push_uci(uci)


def test_selfplay_decisive_value_targets():
    """From a mate-in-one, white mates and the target is +1 for the mover."""
    torch.manual_seed(0)
    model = PolicyValueNet(TINY).eval()
    start = chess.Board("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1")
    rec = play_selfplay_game(model, MCTSConfig(simulations=200, temperature_moves=0),
                             SelfPlayConfig(max_moves=10), rng=0, start_board=start)
    assert rec.moves == ["a1a8"]
    assert rec.termination == "checkmate" and rec.result == 1.0
    assert rec.samples[0][2] == 1.0


def test_selfplay_repetition_termination():
    torch.manual_seed(0)
    model = PolicyValueNet(TINY).eval()
    b = chess.Board()
    for _ in range(2):
        for uci in ["g1f3", "g8f6", "f3g1", "f6g8"]:
            b.push_uci(uci)
    rec = play_selfplay_game(model, MCTSConfig(simulations=4), SelfPlayConfig(max_moves=10),
                             rng=0, start_board=b)
    assert rec.termination == "repetition" and rec.result == 0.5 and rec.moves == []


def test_checkpoint_round_trip(tmp_path):
    torch.manual_seed(0)
    cfg = Config(run_dir=str(tmp_path / "run"))
    cfg.model = TINY
    model = PolicyValueNet(TINY)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    # take a step so BN stats / optimizer state are non-trivial
    x = torch.randn(4, NUM_PLANES, 8, 8)
    loss = sum(t.sum() for t in model(x))
    loss.backward()
    opt.step()
    model.eval()
    d = cfg.checkpoint_dir
    p = save_checkpoint(d / "iter_000003.pt", model, opt, 3, 17, cfg, {"note": "x"})
    save_checkpoint(d / "iter_000001.pt", model, opt, 1, 5, cfg)
    save_checkpoint(d / "latest.pt", model, opt, 3, 17, cfg)
    ck = load_checkpoint(p)
    for key in ("model_state", "optimizer_state", "iteration", "global_step", "model_config",
                "config", "timestamp", "extra"):
        assert key in ck
    assert ck["iteration"] == 3 and ck["global_step"] == 17 and ck["extra"] == {"note": "x"}
    assert ck["config"]["model"]["channels"] == 16
    loaded = load_model(p)
    assert not loaded.training
    with torch.no_grad():
        a, b = model(x), loaded(x)
    assert torch.equal(a[0], b[0]) and torch.equal(a[1], b[1])
    assert not list(d.glob("*.tmp")) and not list(d.glob(".*tmp"))

    infos = list_checkpoints(d)
    assert [i.iteration for i in infos] == [1, 3]
    assert infos[1].global_step == 17 and infos[1].size_bytes > 0 and infos[1].timestamp

    assert resolve_checkpoint("latest", d) == d / "latest.pt"
    assert resolve_checkpoint("iter_000001.pt", d) == d / "iter_000001.pt"
    assert resolve_checkpoint(str(p), d) == p
    assert resolve_checkpoint("nope.pt", d) is None
    assert resolve_checkpoint(None, d) is None
    assert resolve_checkpoint("latest", tmp_path / "empty") is None

    prune_checkpoints(d, keep_last=1)
    assert [i.iteration for i in list_checkpoints(d)] == [3]
    assert (d / "latest.pt").exists()
