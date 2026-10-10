"""AlphaZero training loop: self-play -> replay buffer -> gradient steps -> checkpoint."""

from __future__ import annotations

import dataclasses
import json
import random
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

import numpy as np
import torch
import torch.nn.functional as F

from chessbot.config import Config
from chessbot.ml.checkpoints import (LATEST_NAME, iter_checkpoint_name, load_checkpoint,
                                     prune_checkpoints, save_checkpoint)
from chessbot.ml.model import PolicyValueNet, select_device
from chessbot.ml.replay_buffer import ReplayBuffer
from chessbot.ml.selfplay import play_selfplay_game

METRIC_KEYS = ("type", "iteration", "global_step", "timestamp", "games", "positions",
               "avg_game_length", "white_wins", "black_wins", "draws", "policy_loss",
               "value_loss", "total_loss", "learning_rate", "buffer_size",
               "selfplay_seconds", "train_seconds", "checkpoint")


class CheckpointMismatchError(ValueError):
    """The checkpoint being resumed was trained with a different model architecture."""


class _Stopped(Exception):
    pass


def _seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed % (2 ** 32))
    torch.manual_seed(seed)


class Trainer:
    def __init__(self, config: Config, resume: bool = True,
                 log_fn: Callable[[str], None] | None = None):
        self.config = config
        self.log_fn = log_fn or (lambda msg: None)
        self.iteration = 0
        self.global_step = 0
        self.device = select_device(config.training.device)
        t = config.training
        if t.seed is not None:
            _seed_everything(t.seed)
        self.model = PolicyValueNet(config.model).to(self.device)
        self.optimizer = self._make_optimizer()
        self.buffer = ReplayBuffer(t.replay_buffer_size, seed=t.seed)
        self.run_dir = Path(config.run_dir)
        self.checkpoint_dir = config.checkpoint_dir
        self._stop_event: threading.Event | None = None
        self.resumed_from: Path | None = None
        if resume:
            self._try_resume()

    # ----------------------------------------------------------------- setup
    def _make_optimizer(self) -> torch.optim.Optimizer:
        t = self.config.training
        params = self.model.parameters()
        if t.optimizer == "sgd":
            return torch.optim.SGD(params, lr=t.learning_rate, momentum=t.momentum,
                                   weight_decay=t.weight_decay, nesterov=t.momentum > 0)
        if t.optimizer == "adam":
            return torch.optim.Adam(params, lr=t.learning_rate, weight_decay=t.weight_decay)
        raise ValueError(f"unknown optimizer {t.optimizer!r}")

    def _try_resume(self) -> None:
        latest = self.checkpoint_dir / LATEST_NAME
        if not latest.is_file():
            return
        ckpt = load_checkpoint(latest, map_location="cpu")
        stored = ckpt.get("model_config", {})
        current = dataclasses.asdict(self.config.model)
        if stored != current:
            raise CheckpointMismatchError(
                f"cannot resume from {latest}: it was trained with model config {stored}, "
                f"but the current config has {current}. Use a new run_dir, restore the "
                f"original model settings, or start with resume=False (fresh training).")
        self.model.load_state_dict(ckpt["model_state"])
        if ckpt.get("optimizer_state") is not None:
            try:
                self.optimizer.load_state_dict(ckpt["optimizer_state"])
                # learning rate / weight decay always follow the current config
                for g in self.optimizer.param_groups:
                    g["lr"] = self.config.training.learning_rate
                    g["weight_decay"] = self.config.training.weight_decay
            except (ValueError, KeyError) as exc:   # e.g. optimizer type changed
                self.log_fn(f"Optimizer state not restored ({exc}); using a fresh optimizer")
        self.iteration = int(ckpt.get("iteration", 0))
        self.global_step = int(ckpt.get("global_step", 0))
        bp = self.config.buffer_path
        if bp.is_file():
            self.buffer = ReplayBuffer.load(bp, capacity=self.config.training.replay_buffer_size,
                                            seed=self._derived_seed(1))
        self.resumed_from = latest
        self.log_fn(f"Resumed from {latest}: iteration {self.iteration}, "
                    f"global_step {self.global_step}, buffer {len(self.buffer)} samples")

    def _derived_seed(self, salt: int) -> int | None:
        seed = self.config.training.seed
        if seed is None:
            return None
        return int(np.random.SeedSequence([seed, self.iteration, salt]).generate_state(1)[0])

    # -------------------------------------------------------------- training
    def train_steps(self, steps: int) -> dict:
        """Run ``steps`` gradient steps on uniform buffer samples; return mean losses."""
        if len(self.buffer) == 0:
            raise ValueError("replay buffer is empty")
        t = self.config.training
        self.model.train()
        sums = {"policy_loss": 0.0, "value_loss": 0.0, "total_loss": 0.0}
        for _ in range(steps):
            states, policies, values = self.buffer.sample(t.batch_size)
            x = torch.from_numpy(states).to(self.device)
            pi = torch.from_numpy(policies).to(self.device)
            z = torch.from_numpy(values).to(self.device).unsqueeze(1)
            logits, v = self.model(x)
            policy_loss = -(pi * F.log_softmax(logits, dim=1)).sum(dim=1).mean()
            value_loss = F.mse_loss(v, z)
            loss = policy_loss + t.value_loss_weight * value_loss
            self.optimizer.zero_grad(set_to_none=True)
            loss.backward()
            self.optimizer.step()
            self.global_step += 1
            sums["policy_loss"] += policy_loss.item()
            sums["value_loss"] += value_loss.item()
            sums["total_loss"] += loss.item()
        self.model.eval()
        return {k: (v / steps if steps else None) for k, v in sums.items()}

    def _check_stop(self) -> None:
        if self._stop_event is not None and self._stop_event.is_set():
            raise _Stopped()

    def run_iteration(self) -> dict:
        """One iteration: self-play games -> train (if buffer is big enough) -> save."""
        cfg, t = self.config, self.config.training
        it = self.iteration + 1
        self.model.eval()
        rng = np.random.default_rng(self._derived_seed(0))
        games = []
        t0 = time.perf_counter()
        for g in range(cfg.selfplay.games_per_iteration):
            self._check_stop()
            rec = play_selfplay_game(self.model, cfg.mcts, cfg.selfplay, device=self.device, rng=rng)
            games.append(rec)
            self.log_fn(f"[iter {it}] game {g + 1}/{cfg.selfplay.games_per_iteration}: "
                        f"{len(rec.moves)} plies, result {rec.result}, {rec.termination}, "
                        f"{rec.seconds:.1f}s")
        self._check_stop()
        selfplay_seconds = time.perf_counter() - t0
        for rec in games:
            self.buffer.add_game(rec.samples)

        losses = {"policy_loss": None, "value_loss": None, "total_loss": None}
        t1 = time.perf_counter()
        if len(self.buffer) >= t.min_buffer_size and t.steps_per_iteration > 0:
            losses = self.train_steps(t.steps_per_iteration)
            self.log_fn(f"[iter {it}] trained {t.steps_per_iteration} steps: "
                        f"policy {losses['policy_loss']:.4f}, value {losses['value_loss']:.4f}, "
                        f"total {losses['total_loss']:.4f}")
        else:
            self.log_fn(f"[iter {it}] buffer {len(self.buffer)} < min_buffer_size "
                        f"{t.min_buffer_size}: training skipped")
        train_seconds = time.perf_counter() - t1
        self.iteration = it

        ckpt_path = None
        self.checkpoint_dir.mkdir(parents=True, exist_ok=True)
        extra = {"buffer_size": len(self.buffer)}
        if it % t.checkpoint_every == 0:
            ckpt_path = save_checkpoint(self.checkpoint_dir / iter_checkpoint_name(it), self.model,
                                        self.optimizer, it, self.global_step, cfg, extra)
            prune_checkpoints(self.checkpoint_dir, t.keep_last)
        latest = save_checkpoint(self.checkpoint_dir / LATEST_NAME, self.model, self.optimizer,
                                 it, self.global_step, cfg, extra)
        self.buffer.save(cfg.buffer_path)

        plies = [len(r.moves) for r in games]
        metrics = {
            "type": "train",
            "iteration": it,
            "global_step": self.global_step,
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "games": len(games),
            "positions": sum(len(r.samples) for r in games),
            "avg_game_length": float(np.mean(plies)) if plies else 0.0,
            "white_wins": sum(r.result == 1.0 for r in games),
            "black_wins": sum(r.result == 0.0 for r in games),
            "draws": sum(r.result == 0.5 for r in games),
            "policy_loss": losses["policy_loss"],
            "value_loss": losses["value_loss"],
            "total_loss": losses["total_loss"],
            "learning_rate": float(self.optimizer.param_groups[0]["lr"]),
            "buffer_size": len(self.buffer),
            "selfplay_seconds": round(selfplay_seconds, 4),
            "train_seconds": round(train_seconds, 4),
            "checkpoint": str(ckpt_path if ckpt_path is not None else latest),
        }
        assert tuple(metrics) == METRIC_KEYS
        cfg.metrics_path.parent.mkdir(parents=True, exist_ok=True)
        with open(cfg.metrics_path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(metrics) + "\n")
        self.log_fn(f"[iter {it}] done: {metrics['positions']} positions, buffer "
                    f"{metrics['buffer_size']}, W/B/D {metrics['white_wins']}/"
                    f"{metrics['black_wins']}/{metrics['draws']}, saved {Path(metrics['checkpoint']).name}")
        return metrics

    def run(self, iterations: int | None = None,
            stop_event: threading.Event | None = None,
            on_iteration_end: Callable[[dict], None] | None = None) -> list[dict]:
        """Run ``iterations`` iterations (default ``config.training.iterations``).

        ``stop_event`` is checked before each iteration and between self-play
        games; a partially completed iteration is abandoned without saving,
        so the on-disk state always corresponds to the last full iteration.
        """
        n = self.config.training.iterations if iterations is None else int(iterations)
        self._stop_event = stop_event
        results: list[dict] = []
        try:
            for _ in range(n):
                if stop_event is not None and stop_event.is_set():
                    break
                try:
                    metrics = self.run_iteration()
                except _Stopped:
                    self.log_fn(f"Stop requested: abandoned iteration {self.iteration + 1}")
                    break
                results.append(metrics)
                if on_iteration_end is not None:
                    on_iteration_end(metrics)
        finally:
            self._stop_event = None
        if stop_event is not None and stop_event.is_set():
            self.log_fn(f"Training stopped after iteration {self.iteration}")
        return results
