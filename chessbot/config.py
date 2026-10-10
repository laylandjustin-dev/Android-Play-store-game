"""Configuration schema, YAML load/save and validation.

Every field here is consumed somewhere in the code base; see
docs/CONFIGURATION.md for the documented effect of each setting.
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml


class ConfigError(ValueError):
    """Raised when a configuration is invalid. ``errors`` lists every problem."""

    def __init__(self, errors: list[str]):
        self.errors = list(errors)
        super().__init__("Invalid configuration:\n  - " + "\n  - ".join(self.errors))


@dataclass
class ModelConfig:
    channels: int = 64            # conv filters in the residual tower
    res_blocks: int = 4           # number of residual blocks
    value_hidden: int = 128       # hidden units in the value head


@dataclass
class SearchConfig:
    depth: int = 3                # max iterative-deepening depth (plies)
    time_limit: float | None = None   # seconds per move; None = depth only
    quiescence: bool = True       # enable quiescence search at leaves
    quiescence_depth: int = 6     # max extra plies of captures in quiescence
    tt_size: int = 200_000        # max transposition-table entries


@dataclass
class MCTSConfig:
    simulations: int = 64         # network evaluations per move
    c_puct: float = 1.5           # exploration constant
    dirichlet_alpha: float = 0.3  # root noise concentration (self-play only)
    dirichlet_epsilon: float = 0.25   # root noise weight (self-play only)
    temperature: float = 1.0      # sampling temperature for early moves
    temperature_moves: int = 20   # plies sampled with `temperature`, then greedy


@dataclass
class SelfPlayConfig:
    games_per_iteration: int = 8  # self-play games generated per iteration
    max_moves: int = 200          # plies before a game is adjudicated a draw


@dataclass
class TrainingConfig:
    iterations: int = 10          # iterations for `chessbot train`
    steps_per_iteration: int = 100    # gradient steps per iteration
    batch_size: int = 128
    learning_rate: float = 1e-3
    weight_decay: float = 1e-4
    optimizer: str = "adam"       # "adam" | "sgd"
    momentum: float = 0.9         # used by sgd only
    value_loss_weight: float = 1.0
    replay_buffer_size: int = 50_000
    min_buffer_size: int = 256    # skip training until buffer has this many samples
    checkpoint_every: int = 1     # save every N iterations (latest.pt always updated)
    keep_last: int = 10           # number of iter_*.pt files kept (0 = keep all)
    device: str = "auto"          # "auto" | "cpu" | "cuda"
    seed: int | None = None


@dataclass
class EvaluationConfig:
    games: int = 20
    opponent: str = "random"      # "random" | "alphabeta" | "checkpoint"
    baseline_checkpoint: str | None = None   # used when opponent == "checkpoint"
    max_moves: int = 300
    simulations: int | None = None    # MCTS sims for evaluation (None = mcts.simulations)


@dataclass
class UIConfig:
    host: str = "127.0.0.1"
    port: int = 5000
    bot_player: str = "mcts"      # "mcts" | "alphabeta" | "alphabeta_nn" | "random"
    bot_checkpoint: str | None = "latest"


@dataclass
class Config:
    run_dir: str = "runs/default"
    model: ModelConfig = field(default_factory=ModelConfig)
    search: SearchConfig = field(default_factory=SearchConfig)
    mcts: MCTSConfig = field(default_factory=MCTSConfig)
    selfplay: SelfPlayConfig = field(default_factory=SelfPlayConfig)
    training: TrainingConfig = field(default_factory=TrainingConfig)
    evaluation: EvaluationConfig = field(default_factory=EvaluationConfig)
    ui: UIConfig = field(default_factory=UIConfig)

    @property
    def checkpoint_dir(self) -> Path:
        return Path(self.run_dir) / "checkpoints"

    @property
    def metrics_path(self) -> Path:
        return Path(self.run_dir) / "metrics.jsonl"

    @property
    def buffer_path(self) -> Path:
        return Path(self.run_dir) / "replay_buffer.npz"


_SECTIONS: dict[str, type] = {
    "model": ModelConfig,
    "search": SearchConfig,
    "mcts": MCTSConfig,
    "selfplay": SelfPlayConfig,
    "training": TrainingConfig,
    "evaluation": EvaluationConfig,
    "ui": UIConfig,
}

PLAYER_KINDS = ("random", "alphabeta", "alphabeta_nn", "mcts")


def config_to_dict(cfg: Config) -> dict[str, Any]:
    return dataclasses.asdict(cfg)


def _coerce(value: Any, annotation: str, where: str, errors: list[str]) -> Any:
    """Type-check/coerce a scalar against the dataclass annotation string."""
    optional = "None" in annotation
    if value is None:
        if optional:
            return None
        errors.append(f"{where}: must not be null")
        return None
    base = annotation.replace("| None", "").strip()
    if base == "bool":
        if isinstance(value, bool):
            return value
        errors.append(f"{where}: expected true/false, got {value!r}")
    elif base == "int":
        if isinstance(value, int) and not isinstance(value, bool):
            return value
        if isinstance(value, float) and value.is_integer():
            return int(value)
        errors.append(f"{where}: expected integer, got {value!r}")
    elif base == "float":
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
        errors.append(f"{where}: expected number, got {value!r}")
    elif base == "str":
        if isinstance(value, str):
            return value
        errors.append(f"{where}: expected string, got {value!r}")
    else:  # pragma: no cover - schema programming error
        errors.append(f"{where}: unsupported type {annotation}")
    return value


def config_from_dict(d: dict[str, Any] | None) -> Config:
    """Build a Config from a (possibly partial) dict. Missing keys use defaults."""
    d = dict(d or {})
    errors: list[str] = []
    cfg = Config()
    for key, value in d.items():
        if key == "run_dir":
            cfg.run_dir = _coerce(value, "str", "run_dir", errors)
        elif key in _SECTIONS:
            if value is None:
                continue
            if not isinstance(value, dict):
                errors.append(f"{key}: expected a mapping")
                continue
            section = getattr(cfg, key)
            fields = {f.name: f for f in dataclasses.fields(section)}
            for sub_key, sub_val in value.items():
                if sub_key not in fields:
                    errors.append(f"{key}.{sub_key}: unknown setting")
                    continue
                setattr(section, sub_key,
                        _coerce(sub_val, str(fields[sub_key].type), f"{key}.{sub_key}", errors))
        else:
            errors.append(f"{key}: unknown section")
    if errors:
        raise ConfigError(errors)
    errors = validate_config(cfg)
    if errors:
        raise ConfigError(errors)
    return cfg


def validate_config(cfg: Config) -> list[str]:
    """Range/enum checks. Returns a list of human-readable problems."""
    e: list[str] = []

    def check(cond: bool, msg: str) -> None:
        if not cond:
            e.append(msg)

    m, s, t, mc, sp, ev, ui = (cfg.model, cfg.search, cfg.training, cfg.mcts,
                              cfg.selfplay, cfg.evaluation, cfg.ui)
    check(bool(cfg.run_dir), "run_dir: must not be empty")
    check(1 <= m.channels <= 512, "model.channels: must be in [1, 512]")
    check(0 <= m.res_blocks <= 40, "model.res_blocks: must be in [0, 40]")
    check(1 <= m.value_hidden <= 2048, "model.value_hidden: must be in [1, 2048]")
    check(1 <= s.depth <= 64, "search.depth: must be in [1, 64]")
    check(s.time_limit is None or s.time_limit > 0, "search.time_limit: must be > 0 or null")
    check(0 <= s.quiescence_depth <= 32, "search.quiescence_depth: must be in [0, 32]")
    check(s.tt_size >= 0, "search.tt_size: must be >= 0")
    check(mc.simulations >= 1, "mcts.simulations: must be >= 1")
    check(mc.c_puct > 0, "mcts.c_puct: must be > 0")
    check(mc.dirichlet_alpha > 0, "mcts.dirichlet_alpha: must be > 0")
    check(0 <= mc.dirichlet_epsilon <= 1, "mcts.dirichlet_epsilon: must be in [0, 1]")
    check(mc.temperature >= 0, "mcts.temperature: must be >= 0")
    check(mc.temperature_moves >= 0, "mcts.temperature_moves: must be >= 0")
    check(sp.games_per_iteration >= 1, "selfplay.games_per_iteration: must be >= 1")
    check(sp.max_moves >= 1, "selfplay.max_moves: must be >= 1")
    check(t.iterations >= 1, "training.iterations: must be >= 1")
    check(t.steps_per_iteration >= 0, "training.steps_per_iteration: must be >= 0")
    check(t.batch_size >= 1, "training.batch_size: must be >= 1")
    check(0 < t.learning_rate <= 10, "training.learning_rate: must be in (0, 10]")
    check(t.weight_decay >= 0, "training.weight_decay: must be >= 0")
    check(t.optimizer in ("adam", "sgd"), "training.optimizer: must be 'adam' or 'sgd'")
    check(0 <= t.momentum < 1, "training.momentum: must be in [0, 1)")
    check(t.value_loss_weight >= 0, "training.value_loss_weight: must be >= 0")
    check(t.replay_buffer_size >= 1, "training.replay_buffer_size: must be >= 1")
    check(t.min_buffer_size >= 1, "training.min_buffer_size: must be >= 1")
    check(t.min_buffer_size <= t.replay_buffer_size,
          "training.min_buffer_size: must be <= training.replay_buffer_size")
    check(t.checkpoint_every >= 1, "training.checkpoint_every: must be >= 1")
    check(t.keep_last >= 0, "training.keep_last: must be >= 0")
    check(t.device in ("auto", "cpu", "cuda"), "training.device: must be auto, cpu or cuda")
    check(ev.games >= 1, "evaluation.games: must be >= 1")
    check(ev.opponent in ("random", "alphabeta", "checkpoint"),
          "evaluation.opponent: must be random, alphabeta or checkpoint")
    check(ev.opponent != "checkpoint" or bool(ev.baseline_checkpoint),
          "evaluation.baseline_checkpoint: required when opponent is 'checkpoint'")
    check(ev.max_moves >= 1, "evaluation.max_moves: must be >= 1")
    check(ev.simulations is None or ev.simulations >= 1, "evaluation.simulations: must be >= 1 or null")
    check(bool(ui.host), "ui.host: must not be empty")
    check(1 <= ui.port <= 65535, "ui.port: must be in [1, 65535]")
    check(ui.bot_player in PLAYER_KINDS, f"ui.bot_player: must be one of {', '.join(PLAYER_KINDS)}")
    return e


def load_config(path: str | Path | None = None) -> Config:
    """Load a YAML config. ``None`` returns defaults."""
    if path is None:
        return Config()
    with open(path, "r", encoding="utf-8") as fh:
        data = yaml.safe_load(fh)
    if data is not None and not isinstance(data, dict):
        raise ConfigError([f"{path}: top level must be a mapping"])
    return config_from_dict(data)


def save_config(cfg: Config, path: str | Path) -> None:
    errors = validate_config(cfg)
    if errors:
        raise ConfigError(errors)
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        yaml.safe_dump(config_to_dict(cfg), fh, sort_keys=False)
