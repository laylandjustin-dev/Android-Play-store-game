"""Checkpoint save/load/listing.

A checkpoint is a ``torch.save`` dict with keys ``model_state``,
``optimizer_state`` (may be None), ``iteration``, ``global_step``,
``model_config`` (dict), ``config`` (full config dict), ``timestamp`` (ISO 8601,
UTC) and ``extra`` (dict). Files are written atomically (temp file +
``os.replace``) so a crash never leaves a truncated checkpoint behind.
"""

from __future__ import annotations

import dataclasses
import os
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import torch

from chessbot.config import Config, ModelConfig, config_to_dict
from chessbot.ml.model import PolicyValueNet, select_device

ITER_PATTERN = re.compile(r"^iter_(\d+)\.pt$")
LATEST_NAME = "latest.pt"


@dataclass
class CheckpointInfo:
    path: Path
    iteration: int
    global_step: int
    timestamp: str
    size_bytes: int


def iter_checkpoint_name(iteration: int) -> str:
    return f"iter_{iteration:06d}.pt"


def _cpu_state(state):
    if isinstance(state, torch.Tensor):
        return state.detach().cpu()
    if isinstance(state, dict):
        return {k: _cpu_state(v) for k, v in state.items()}
    if isinstance(state, (list, tuple)):
        return type(state)(_cpu_state(v) for v in state)
    return state


def save_checkpoint(path, model, optimizer, iteration: int, global_step: int,
                    config: Config, extra: dict | None = None) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    model_cfg = getattr(model, "config", None) or config.model
    payload = {
        "model_state": _cpu_state(model.state_dict()),
        "optimizer_state": _cpu_state(optimizer.state_dict()) if optimizer is not None else None,
        "iteration": int(iteration),
        "global_step": int(global_step),
        "model_config": dataclasses.asdict(model_cfg),
        "config": config_to_dict(config),
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "extra": dict(extra or {}),
    }
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        torch.save(payload, tmp)
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()
    return path


def load_checkpoint(path, map_location="cpu") -> dict:
    return torch.load(Path(path), map_location=map_location, weights_only=True)


def model_config_from_dict(d: dict) -> ModelConfig:
    names = {f.name for f in dataclasses.fields(ModelConfig)}
    return ModelConfig(**{k: v for k, v in d.items() if k in names})


def load_model(path, device="cpu") -> PolicyValueNet:
    """Rebuild the network from the checkpoint's stored model config; returns it in eval mode."""
    dev = select_device(device)
    ckpt = load_checkpoint(path, map_location="cpu")
    model = PolicyValueNet(model_config_from_dict(ckpt["model_config"]))
    model.load_state_dict(ckpt["model_state"])
    model.to(dev)
    model.eval()
    return model


def list_checkpoints(checkpoint_dir) -> list[CheckpointInfo]:
    """``iter_XXXXXX.pt`` files in ``checkpoint_dir`` sorted by iteration (latest.pt excluded)."""
    d = Path(checkpoint_dir)
    if not d.is_dir():
        return []
    infos = []
    for p in d.iterdir():
        m = ITER_PATTERN.match(p.name)
        if not m or not p.is_file():
            continue
        iteration, global_step, timestamp = int(m.group(1)), -1, ""
        try:
            ckpt = torch.load(p, map_location="cpu", weights_only=True, mmap=True)
            iteration = int(ckpt.get("iteration", iteration))
            global_step = int(ckpt.get("global_step", -1))
            timestamp = str(ckpt.get("timestamp", ""))
        except Exception:  # unreadable file: still list it with what the name tells us
            timestamp = datetime.fromtimestamp(p.stat().st_mtime, timezone.utc).isoformat(timespec="seconds")
        infos.append(CheckpointInfo(path=p, iteration=iteration, global_step=global_step,
                                    timestamp=timestamp, size_bytes=p.stat().st_size))
    infos.sort(key=lambda i: (i.iteration, i.path.name))
    return infos


def resolve_checkpoint(spec: str | None, checkpoint_dir) -> Path | None:
    """Resolve ``"latest"``, a bare filename (within ``checkpoint_dir``) or a path.

    Returns None when ``spec`` is None/empty or nothing exists.
    """
    if spec is None or str(spec).strip() == "":
        return None
    spec = str(spec).strip()
    d = Path(checkpoint_dir)
    if spec == "latest":
        p = d / LATEST_NAME
        return p if p.is_file() else None
    direct = Path(spec)
    if direct.is_file():
        return direct
    candidate = d / spec
    if candidate.is_file():
        return candidate
    if not spec.endswith(".pt") and (d / f"{spec}.pt").is_file():
        return d / f"{spec}.pt"
    return None


def prune_checkpoints(checkpoint_dir, keep_last: int) -> list[Path]:
    """Delete the oldest ``iter_*.pt`` files so at most ``keep_last`` remain (0 = keep all)."""
    if keep_last <= 0:
        return []
    d = Path(checkpoint_dir)
    files = sorted((p for p in d.glob("iter_*.pt") if ITER_PATTERN.match(p.name)),
                   key=lambda p: int(ITER_PATTERN.match(p.name).group(1)))
    removed = []
    for p in files[:-keep_last] if len(files) > keep_last else []:
        p.unlink(missing_ok=True)
        removed.append(p)
    return removed
