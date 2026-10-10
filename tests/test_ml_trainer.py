import json
import threading

import numpy as np
import pytest
import torch

from chessbot.ml.checkpoints import list_checkpoints, load_checkpoint
from chessbot.ml.encoding import NUM_PLANES, POLICY_SIZE
from chessbot.ml.replay_buffer import ReplayBuffer
from chessbot.ml.trainer import METRIC_KEYS, CheckpointMismatchError, Trainer


def read_metrics(cfg):
    with open(cfg.metrics_path) as fh:
        return [json.loads(line) for line in fh]


def test_one_iteration_writes_everything_and_resumes(tiny_config):
    logs = []
    tr = Trainer(tiny_config, resume=True, log_fn=logs.append)
    assert tr.iteration == 0 and tr.global_step == 0
    m = tr.run_iteration()
    assert tuple(m) == METRIC_KEYS
    assert m["type"] == "train" and m["iteration"] == 1 and m["games"] == 2
    assert m["white_wins"] + m["black_wins"] + m["draws"] == 2
    assert m["positions"] == m["buffer_size"] == len(tr.buffer)
    assert m["avg_game_length"] == pytest.approx(m["positions"] / 2)
    # tiny games produce >= 16 positions -> training happened
    assert m["positions"] >= tiny_config.training.min_buffer_size
    assert m["global_step"] == tiny_config.training.steps_per_iteration == tr.global_step
    for k in ("policy_loss", "value_loss", "total_loss"):
        assert isinstance(m[k], float) and np.isfinite(m[k])
    assert m["learning_rate"] == tiny_config.training.learning_rate
    assert m["selfplay_seconds"] > 0
    d = tiny_config.checkpoint_dir
    assert (d / "iter_000001.pt").exists() and (d / "latest.pt").exists()
    assert tiny_config.buffer_path.exists()
    assert read_metrics(tiny_config) == [m]
    assert logs

    tr2 = Trainer(tiny_config, resume=True)
    assert tr2.iteration == 1 and tr2.global_step == tr.global_step
    assert len(tr2.buffer) == len(tr.buffer)
    for a, b in zip(tr.model.state_dict().values(), tr2.model.state_dict().values()):
        assert torch.equal(a, b)
    assert tr2.optimizer.state_dict()["state"]   # Adam moments restored
    m2 = tr2.run(1)[0]
    assert m2["iteration"] == 2 and m2["global_step"] == 2 * tiny_config.training.steps_per_iteration
    assert m2["buffer_size"] == m["buffer_size"] + m2["positions"]
    assert [x["iteration"] for x in read_metrics(tiny_config)] == [1, 2]
    assert [i.iteration for i in list_checkpoints(d)] == [1, 2]
    assert load_checkpoint(d / "latest.pt")["iteration"] == 2

    fresh = Trainer(tiny_config, resume=False)
    assert fresh.iteration == 0 and len(fresh.buffer) == 0


def test_skip_training_when_buffer_small_and_keep_last(tiny_config):
    tiny_config.training.min_buffer_size = 1000
    tiny_config.training.keep_last = 1
    tiny_config.selfplay.games_per_iteration = 1
    tiny_config.selfplay.max_moves = 6
    tr = Trainer(tiny_config)
    ms = tr.run(2)
    assert len(ms) == 2
    for m in ms:
        assert m["policy_loss"] is None and m["value_loss"] is None and m["total_loss"] is None
        assert m["global_step"] == 0
    assert [i.iteration for i in list_checkpoints(tiny_config.checkpoint_dir)] == [2]


def test_resume_with_different_model_raises(tiny_config):
    tiny_config.selfplay.games_per_iteration = 1
    tiny_config.selfplay.max_moves = 4
    Trainer(tiny_config).run_iteration()
    tiny_config.model.channels = 24
    with pytest.raises(CheckpointMismatchError, match="model config"):
        Trainer(tiny_config, resume=True)


def test_stop_event(tiny_config):
    tiny_config.selfplay.max_moves = 4
    ev = threading.Event()
    ev.set()
    tr = Trainer(tiny_config)
    assert tr.run(3, stop_event=ev) == [] and tr.iteration == 0
    # stop mid-iteration (after the first game): partial iteration is abandoned, nothing saved
    ev2 = threading.Event()

    def log(msg):
        if "game 1/" in msg:
            ev2.set()

    tr = Trainer(tiny_config, log_fn=log)
    seen = []
    assert tr.run(3, stop_event=ev2, on_iteration_end=seen.append) == []
    assert tr.iteration == 0 and len(tr.buffer) == 0 and seen == []
    assert not tiny_config.metrics_path.exists()
    assert not (tiny_config.checkpoint_dir / "latest.pt").exists()


def test_training_really_optimises(tiny_config):
    """Overfitting a fixed small dataset must drive the loss down substantially."""
    tiny_config.model.channels = 32
    tiny_config.training.batch_size = 32
    tiny_config.training.learning_rate = 2e-3
    tiny_config.training.weight_decay = 0.0
    tr = Trainer(tiny_config, resume=False)
    rng = np.random.default_rng(0)
    buf = ReplayBuffer(32, seed=0)
    for _ in range(32):
        state = (rng.random((NUM_PLANES, 8, 8)) < 0.15).astype(np.float32)
        policy = np.zeros(POLICY_SIZE, dtype=np.float32)
        policy[rng.integers(POLICY_SIZE)] = 1.0
        buf.add(state, policy, float(rng.choice([-1.0, 1.0])))
    tr.buffer = buf
    first = tr.train_steps(5)
    for _ in range(12):
        last = tr.train_steps(20)
    assert tr.global_step == 5 + 12 * 20
    assert last["total_loss"] < 0.25 * first["total_loss"], (first, last)
    assert last["policy_loss"] < 0.25 * first["policy_loss"]
    assert last["value_loss"] < 0.5 * first["value_loss"]


def test_sgd_optimizer(tiny_config):
    tiny_config.training.optimizer = "sgd"
    tr = Trainer(tiny_config)
    assert isinstance(tr.optimizer, torch.optim.SGD)
    assert tr.optimizer.param_groups[0]["momentum"] == tiny_config.training.momentum
    assert tr.optimizer.param_groups[0]["weight_decay"] == tiny_config.training.weight_decay
