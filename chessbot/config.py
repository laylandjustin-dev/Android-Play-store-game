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
    leaf_batch_size: int | None = None  # leaves per network call; None = auto (self-play) / 1 (play)


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


_INVALID = object()  # sentinel returned by _coerce when the value has the wrong type


def _coerce(value: Any, annotation: str, where: str, errors: list[str]) -> Any:
    """Type-check/coerce a scalar against the dataclass annotation string.

    On a type error a message is appended to ``errors`` and the sentinel
    ``_INVALID`` is returned so callers can keep the (valid) default instead.
    """
    optional = "None" in annotation
    if value is None:
        if optional:
            return None
        errors.append(f"{where}: must not be null")
        return _INVALID
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
    return _INVALID


def config_from_dict(d: dict[str, Any] | None) -> Config:
    """Build a Config from a (possibly partial) dict. Missing keys use defaults.

    Every problem is collected before raising: unknown sections/keys, type
    errors and range/enum errors (range checks run on all well-typed values,
    so a single ``ConfigError`` can report e.g. a type error in one field and a
    range error in another).
    """
    if d is not None and not isinstance(d, dict):
        raise ConfigError(["top level must be a mapping"])
    d = dict(d or {})
    errors: list[str] = []
    bad_fields: set[str] = set()
    cfg = Config()
    for key, value in d.items():
        if not isinstance(key, str):
            errors.append(f"{key!r}: unknown section")
            continue
        if key == "run_dir":
            coerced = _coerce(value, "str", "run_dir", errors)
            if coerced is _INVALID:
                bad_fields.add("run_dir")
            else:
                cfg.run_dir = coerced
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
                where = f"{key}.{sub_key}"
                coerced = _coerce(sub_val, str(fields[sub_key].type), where, errors)
                if coerced is _INVALID:
                    bad_fields.add(where)
                else:
                    setattr(section, sub_key, coerced)
        else:
            errors.append(f"{key}: unknown section")
    # Range checks on the remaining (well-typed or default) values. Skip
    # messages about fields that already have a type error to avoid noise.
    for msg in validate_config(cfg):
        if msg.split(":", 1)[0] not in bad_fields:
            errors.append(msg)
    if errors:
        raise ConfigError(errors)
    return cfg


def validate_config(cfg: Config) -> list[str]:
    """Range/enum checks. Returns a list of human-readable problems."""
    e: list[str] = []

    # Type pass first: a Config built in code (not via config_from_dict) may
    # hold wrongly-typed values; report them instead of crashing on compare.
    bad: set[str] = set()
    if not isinstance(cfg.run_dir, str):
        e.append(f"run_dir: expected string, got {cfg.run_dir!r}")
        bad.add("run_dir")
    for name in _SECTIONS:
        section = getattr(cfg, name)
        for f in dataclasses.fields(section):
            where = f"{name}.{f.name}"
            tmp: list[str] = []
            value = getattr(section, f.name)
            _coerce(value, str(f.type), where, tmp)
            if tmp:
                e.extend(tmp)
                bad.add(where)

    def check(cond: Any, msg: str) -> None:
        if msg.split(":", 1)[0] in bad:
            return
        try:
            ok = cond() if callable(cond) else cond
        except TypeError:
            ok = False
        if not ok:
            e.append(msg)

    m, s, t, mc, sp, ev, ui = (cfg.model, cfg.search, cfg.training, cfg.mcts,
                              cfg.selfplay, cfg.evaluation, cfg.ui)
    check(lambda: bool(cfg.run_dir), "run_dir: must not be empty")
    check(lambda: 1 <= m.channels <= 512, "model.channels: must be in [1, 512]")
    check(lambda: 0 <= m.res_blocks <= 40, "model.res_blocks: must be in [0, 40]")
    check(lambda: 1 <= m.value_hidden <= 2048, "model.value_hidden: must be in [1, 2048]")
    check(lambda: 1 <= s.depth <= 64, "search.depth: must be in [1, 64]")
    check(lambda: s.time_limit is None or s.time_limit > 0, "search.time_limit: must be > 0 or null")
    check(lambda: 0 <= s.quiescence_depth <= 32, "search.quiescence_depth: must be in [0, 32]")
    check(lambda: s.tt_size >= 0, "search.tt_size: must be >= 0")
    check(lambda: mc.simulations >= 1, "mcts.simulations: must be >= 1")
    check(lambda: mc.c_puct > 0, "mcts.c_puct: must be > 0")
    check(lambda: mc.dirichlet_alpha > 0, "mcts.dirichlet_alpha: must be > 0")
    check(lambda: 0 <= mc.dirichlet_epsilon <= 1, "mcts.dirichlet_epsilon: must be in [0, 1]")
    check(lambda: mc.temperature >= 0, "mcts.temperature: must be >= 0")
    check(lambda: mc.temperature_moves >= 0, "mcts.temperature_moves: must be >= 0")
    check(lambda: mc.leaf_batch_size is None or 1 <= mc.leaf_batch_size <= 256,
          "mcts.leaf_batch_size: must be in [1, 256] or null")
    check(lambda: sp.games_per_iteration >= 1, "selfplay.games_per_iteration: must be >= 1")
    check(lambda: sp.max_moves >= 1, "selfplay.max_moves: must be >= 1")
    check(lambda: t.iterations >= 1, "training.iterations: must be >= 1")
    check(lambda: t.steps_per_iteration >= 0, "training.steps_per_iteration: must be >= 0")
    check(lambda: t.batch_size >= 1, "training.batch_size: must be >= 1")
    check(lambda: 0 < t.learning_rate <= 10, "training.learning_rate: must be in (0, 10]")
    check(lambda: t.weight_decay >= 0, "training.weight_decay: must be >= 0")
    check(lambda: t.optimizer in ("adam", "sgd"), "training.optimizer: must be 'adam' or 'sgd'")
    check(lambda: 0 <= t.momentum < 1, "training.momentum: must be in [0, 1)")
    check(lambda: t.value_loss_weight >= 0, "training.value_loss_weight: must be >= 0")
    check(lambda: t.replay_buffer_size >= 1, "training.replay_buffer_size: must be >= 1")
    check(lambda: t.min_buffer_size >= 1, "training.min_buffer_size: must be >= 1")
    check(lambda: t.min_buffer_size <= t.replay_buffer_size,
          "training.min_buffer_size: must be <= training.replay_buffer_size")
    check(lambda: t.checkpoint_every >= 1, "training.checkpoint_every: must be >= 1")
    check(lambda: t.keep_last >= 0, "training.keep_last: must be >= 0")
    check(lambda: t.device in ("auto", "cpu", "cuda"), "training.device: must be auto, cpu or cuda")
    check(lambda: ev.games >= 1, "evaluation.games: must be >= 1")
    check(lambda: ev.opponent in ("random", "alphabeta", "checkpoint"),
          "evaluation.opponent: must be random, alphabeta or checkpoint")
    check(lambda: ev.opponent != "checkpoint" or bool(ev.baseline_checkpoint),
          "evaluation.baseline_checkpoint: required when opponent is 'checkpoint'")
    check(lambda: ev.max_moves >= 1, "evaluation.max_moves: must be >= 1")
    check(lambda: ev.simulations is None or ev.simulations >= 1, "evaluation.simulations: must be >= 1 or null")
    check(lambda: bool(ui.host), "ui.host: must not be empty")
    check(lambda: 1 <= ui.port <= 65535, "ui.port: must be in [1, 65535]")
    check(lambda: ui.bot_player in PLAYER_KINDS, f"ui.bot_player: must be one of {', '.join(PLAYER_KINDS)}")
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


# ---------------------------------------------------------------------------
# Field metadata (used by the web settings page; mirrors docs/CONFIGURATION.md)
# ---------------------------------------------------------------------------

FIELD_HELP: dict[str, str] = {
    "run_dir": "Directory for checkpoints, replay buffer and metrics of this run.",
    "model.channels": "Conv filters per residual block. More = more capacity, slower and more memory.",
    "model.res_blocks": "Residual blocks in the tower (network depth). More = stronger but slower.",
    "model.value_hidden": "Hidden units in the value head's fully connected layer.",
    "search.depth": "Max alpha-beta iterative-deepening depth in plies. +1 ply is much slower but stronger.",
    "search.time_limit": "Seconds per alpha-beta move (blank = depth limit only). Stops deepening when exceeded.",
    "search.quiescence": "Extend leaves with captures/promotions to avoid horizon blunders.",
    "search.quiescence_depth": "Max extra plies searched in quiescence.",
    "search.tt_size": "Max transposition-table entries (0 disables). Bigger = fewer re-searches, more RAM.",
    "mcts.simulations": "Network evaluations per MCTS move. More = stronger, proportionally slower.",
    "mcts.c_puct": "PUCT exploration constant. Higher trusts priors/explores more; lower exploits visits.",
    "mcts.dirichlet_alpha": "Concentration of root Dirichlet noise in self-play (smaller = spikier).",
    "mcts.dirichlet_epsilon": "Weight of root noise vs network prior in self-play (0 disables noise).",
    "mcts.temperature": "Sampling temperature for the opening plies of self-play (1 = proportional to visits).",
    "mcts.temperature_moves": "Number of plies sampled with temperature before switching to greedy moves.",
    "mcts.leaf_batch_size": "Leaves evaluated per network call (virtual loss). Blank = auto in self-play "
                            "(sims/8, max 8), 1 when playing. Higher = faster, slightly weaker search.",
    "selfplay.games_per_iteration": "Self-play games generated each iteration (more data per iteration, slower).",
    "selfplay.max_moves": "Plies after which a self-play game is adjudicated a draw.",
    "training.iterations": "Iterations run by `chessbot train` and the default for the dashboard's start button.",
    "training.steps_per_iteration": "Gradient steps per iteration on replay-buffer samples (0 = self-play only).",
    "training.batch_size": "Samples per gradient step. Larger = smoother gradients, more memory.",
    "training.learning_rate": "Optimizer step size. Too high diverges, too low learns slowly.",
    "training.weight_decay": "L2 regularisation applied by the optimizer.",
    "training.optimizer": "Optimizer: adam (robust default) or sgd (with momentum).",
    "training.momentum": "SGD momentum (ignored by adam).",
    "training.value_loss_weight": "Weight of the value MSE relative to the policy cross-entropy.",
    "training.replay_buffer_size": "Max stored positions; oldest are dropped first.",
    "training.min_buffer_size": "Training is skipped until the buffer holds at least this many positions.",
    "training.checkpoint_every": "Save iter_*.pt every N iterations (latest.pt is updated every iteration).",
    "training.keep_last": "Number of iter_*.pt checkpoints kept on disk (0 = keep all).",
    "training.device": "Torch device: auto (cuda if available), cpu or cuda.",
    "training.seed": "Random seed for reproducible runs (blank = nondeterministic).",
    "evaluation.games": "Games per evaluation match. More = tighter Elo estimate, slower.",
    "evaluation.opponent": "Evaluation opponent: random, alphabeta or a baseline checkpoint.",
    "evaluation.baseline_checkpoint": "Checkpoint used as opponent when opponent = checkpoint.",
    "evaluation.max_moves": "Plies after which an evaluation game is scored a draw.",
    "evaluation.simulations": "MCTS simulations during evaluation (blank = mcts.simulations).",
    "ui.host": "Interface the web server binds to (127.0.0.1 = local only, 0.0.0.0 = all).",
    "ui.port": "TCP port of the web server.",
    "ui.bot_player": "Default bot type on the play page.",
    "ui.bot_checkpoint": "Default checkpoint for the bot: latest, a file name, or blank for an untrained net.",
}

FIELD_CHOICES: dict[str, tuple[str, ...]] = {
    "training.optimizer": ("adam", "sgd"),
    "training.device": ("auto", "cpu", "cuda"),
    "evaluation.opponent": ("random", "alphabeta", "checkpoint"),
    "ui.bot_player": PLAYER_KINDS,
}


def config_schema(cfg: Config | None = None) -> list[dict[str, Any]]:
    """Describe every setting for UIs: grouped by section, in declaration order.

    Returns ``[{"section": name, "fields": [{"key", "name", "type", "nullable",
    "default", "value", "choices", "help"}, ...]}, ...]``; the top-level
    ``run_dir`` is reported under section ``"general"``.
    """
    cfg = cfg or Config()
    defaults = Config()
    groups: list[dict[str, Any]] = [{
        "section": "general",
        "fields": [{
            "key": "run_dir", "name": "run_dir", "type": "str", "nullable": False,
            "default": defaults.run_dir, "value": cfg.run_dir, "choices": None,
            "help": FIELD_HELP.get("run_dir", ""),
        }],
    }]
    for name in _SECTIONS:
        fields = []
        for f in dataclasses.fields(getattr(defaults, name)):
            ann = str(f.type)
            key = f"{name}.{f.name}"
            choices = FIELD_CHOICES.get(key)
            fields.append({
                "key": key,
                "name": f.name,
                "type": ann.replace("| None", "").strip(),
                "nullable": "None" in ann,
                "default": getattr(getattr(defaults, name), f.name),
                "value": getattr(getattr(cfg, name), f.name),
                "choices": list(choices) if choices else None,
                "help": FIELD_HELP.get(key, ""),
            })
        groups.append({"section": name, "fields": fields})
    return groups
