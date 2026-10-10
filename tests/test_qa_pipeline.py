"""End-to-end QA of the self-play training pipeline on a tiny config.

Skips cleanly if the ML package is not implemented yet.
"""

from __future__ import annotations

import dataclasses
import json
import math

import chess
import pytest

pytest.importorskip("torch")
trainer_mod = pytest.importorskip("chessbot.ml.trainer")
checkpoints = pytest.importorskip("chessbot.ml.checkpoints")

import torch  # noqa: E402

from chessbot.arena import play_game  # noqa: E402
from chessbot.config import Config, config_from_dict, config_to_dict  # noqa: E402
from chessbot.players import RandomPlayer, make_player  # noqa: E402

TRAIN_KEYS = {
    "type", "iteration", "global_step", "timestamp", "games", "positions",
    "avg_game_length", "white_wins", "black_wins", "draws", "policy_loss",
    "value_loss", "total_loss", "learning_rate", "buffer_size",
    "selfplay_seconds", "train_seconds", "checkpoint",
}


def _read_metrics(cfg: Config) -> list[dict]:
    with open(cfg.metrics_path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def _clone(cfg: Config) -> Config:
    return config_from_dict(config_to_dict(cfg))


@pytest.fixture(scope="module")
def trained_run(tmp_path_factory):
    """Run 2 tiny training iterations once; shared by the tests below."""

    tmp = tmp_path_factory.mktemp("pipeline")
    cfg = Config(run_dir=str(tmp / "run"))
    cfg.model.channels, cfg.model.res_blocks, cfg.model.value_hidden = 16, 1, 32
    cfg.search.depth = 2
    cfg.mcts.simulations = 8
    cfg.selfplay.games_per_iteration = 2
    cfg.selfplay.max_moves = 30
    cfg.training.steps_per_iteration = 5
    cfg.training.batch_size = 16
    cfg.training.min_buffer_size = 16
    cfg.training.replay_buffer_size = 2000
    cfg.training.device = "cpu"
    cfg.training.seed = 0
    cfg.evaluation.games = 2
    cfg.evaluation.max_moves = 40

    logs: list[str] = []
    trainer = trainer_mod.Trainer(cfg, resume=False, log_fn=logs.append)
    initial = {k: v.detach().clone() for k, v in trainer.model.state_dict().items()}
    metrics = trainer.run(2)
    return {"cfg": cfg, "trainer": trainer, "initial": initial,
            "metrics": metrics, "logs": logs}


def test_two_iterations_produce_checkpoints(trained_run):
    cfg, trainer = trained_run["cfg"], trained_run["trainer"]
    assert trainer.iteration == 2
    assert len(trained_run["metrics"]) == 2
    assert (cfg.checkpoint_dir / "latest.pt").is_file()
    infos = checkpoints.list_checkpoints(cfg.checkpoint_dir)
    iters = [i.iteration for i in infos]
    assert 2 in iters, iters
    assert iters == sorted(iters)
    assert checkpoints.resolve_checkpoint("latest", cfg.checkpoint_dir) is not None
    ck = checkpoints.load_checkpoint(cfg.checkpoint_dir / "latest.pt")
    assert ck.get("iteration") == 2


def test_metrics_well_formed(trained_run):
    cfg = trained_run["cfg"]
    recs = [r for r in _read_metrics(cfg) if r.get("type") == "train"]
    assert len(recs) == 2
    for n, r in enumerate(recs, start=1):
        missing = TRAIN_KEYS - r.keys()
        assert not missing, f"metrics record missing {missing}"
        assert r["iteration"] == n
        assert r["games"] == cfg.selfplay.games_per_iteration
        assert r["white_wins"] + r["black_wins"] + r["draws"] == r["games"]
        assert r["positions"] > 0 and r["buffer_size"] > 0
        assert 0 < r["avg_game_length"] <= cfg.selfplay.max_moves
        assert r["selfplay_seconds"] > 0
        trained = r["buffer_size"] >= cfg.training.min_buffer_size
        if trained:
            for k in ("policy_loss", "value_loss", "total_loss"):
                assert isinstance(r[k], (int, float)) and math.isfinite(r[k]), (k, r[k])
            # total = policy + w * value (allow for float accumulation)
            exp = r["policy_loss"] + cfg.training.value_loss_weight * r["value_loss"]
            assert r["total_loss"] == pytest.approx(exp, rel=1e-3, abs=1e-4)
    assert recs[1]["global_step"] >= recs[0]["global_step"]
    assert recs[1]["buffer_size"] >= recs[0]["buffer_size"]


def test_weights_changed_by_training(trained_run):
    cfg = trained_run["cfg"]
    recs = [r for r in _read_metrics(cfg) if r.get("type") == "train"]
    if all(r["global_step"] == 0 for r in recs):
        pytest.skip("no gradient steps were taken (buffer below min_buffer_size)")
    model = checkpoints.load_model(cfg.checkpoint_dir / "latest.pt")
    initial = trained_run["initial"]
    diff = 0.0
    for k, v in model.state_dict().items():
        if v.dtype.is_floating_point:
            diff += (v.float() - initial[k].float()).abs().sum().item()
    assert diff > 0.0


def test_latest_checkpoint_plays_legal_game(trained_run):
    cfg = trained_run["cfg"]
    player = make_player("mcts", cfg, "latest", seed=0, simulations=8)
    assert "latest" in player.name or "iter" in player.name
    rec = play_game(player, RandomPlayer(0), max_moves=30)
    board = chess.Board()
    for u in rec["moves"]:
        mv = chess.Move.from_uci(u)
        assert mv in board.legal_moves
        board.push(mv)


def test_resume_continues_iteration(trained_run):
    cfg = _clone(trained_run["cfg"])
    t2 = trainer_mod.Trainer(cfg, resume=True)
    assert t2.iteration == 2
    assert t2.global_step == trained_run["trainer"].global_step
    assert len(t2.buffer) > 0  # replay buffer restored from disk
    # restored weights equal the saved latest checkpoint
    saved = checkpoints.load_model(cfg.checkpoint_dir / "latest.pt").state_dict()
    for k, v in t2.model.state_dict().items():
        assert torch.equal(v.cpu(), saved[k].cpu()), k
    m = t2.run_iteration()
    assert t2.iteration == 3
    assert m["iteration"] == 3
    recs = [r for r in _read_metrics(cfg) if r.get("type") == "train"]
    assert [r["iteration"] for r in recs] == [1, 2, 3]


def test_resume_with_changed_model_config_raises(trained_run):
    cfg = _clone(trained_run["cfg"])
    cfg = dataclasses.replace(cfg, model=dataclasses.replace(cfg.model, channels=32))
    with pytest.raises(Exception) as ei:
        trainer_mod.Trainer(cfg, resume=True)
    msg = str(ei.value).lower()
    assert "channels" in msg or "model" in msg, msg


def test_resume_false_starts_fresh(tiny_config):
    t = trainer_mod.Trainer(tiny_config, resume=False)
    assert t.iteration == 0 and t.global_step == 0
