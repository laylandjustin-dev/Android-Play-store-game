"""Tests for chessbot.config: defaults, YAML round trip, validation."""

from __future__ import annotations

import dataclasses
from pathlib import Path

import pytest
import yaml

from chessbot.config import (
    FIELD_HELP,
    Config,
    ConfigError,
    config_from_dict,
    config_schema,
    config_to_dict,
    load_config,
    save_config,
    validate_config,
)

ROOT = Path(__file__).resolve().parent.parent
CONFIG_FILES = sorted((ROOT / "configs").glob("*.yaml"))


def all_keys() -> list[str]:
    cfg = Config()
    keys = ["run_dir"]
    for f in dataclasses.fields(cfg):
        if f.name == "run_dir":
            continue
        keys += [f"{f.name}.{sf.name}" for sf in dataclasses.fields(getattr(cfg, f.name))]
    return keys


def test_defaults_are_valid():
    assert validate_config(Config()) == []
    assert load_config(None) == Config()
    assert config_from_dict({}) == Config()
    assert config_from_dict(None) == Config()


def test_yaml_round_trip(tmp_path):
    cfg = Config(run_dir=str(tmp_path / "r"))
    cfg.model.channels = 48
    cfg.search.time_limit = 2.5
    cfg.training.seed = 7
    cfg.ui.bot_checkpoint = None
    path = tmp_path / "sub" / "cfg.yaml"
    save_config(cfg, path)
    assert path.is_file()
    loaded = load_config(path)
    assert loaded == cfg
    assert config_to_dict(loaded) == config_to_dict(cfg)


def test_config_files_exist():
    names = {p.name for p in CONFIG_FILES}
    assert {"default.yaml", "quick.yaml", "strong.yaml"} <= names


@pytest.mark.parametrize("path", CONFIG_FILES, ids=lambda p: p.name)
def test_every_shipped_yaml_loads(path):
    cfg = load_config(path)
    assert validate_config(cfg) == []


def test_default_yaml_matches_builtin_defaults_and_lists_every_key():
    path = ROOT / "configs" / "default.yaml"
    assert load_config(path) == Config()
    raw = yaml.safe_load(path.read_text())
    present = {"run_dir"} | {f"{s}.{k}" for s, v in raw.items() if isinstance(v, dict) for k in v}
    assert present == set(all_keys())


def test_quick_yaml_is_small():
    cfg = load_config(ROOT / "configs" / "quick.yaml")
    assert cfg.model.channels == 32 and cfg.model.res_blocks == 2
    assert cfg.mcts.simulations == 32
    assert cfg.selfplay.games_per_iteration == 4 and cfg.selfplay.max_moves == 120
    assert cfg.training.steps_per_iteration == 50 and cfg.training.batch_size == 64
    assert cfg.training.min_buffer_size == 128
    assert cfg.run_dir == "runs/quick"


def test_strong_yaml_is_larger_than_default():
    strong, default = load_config(ROOT / "configs" / "strong.yaml"), Config()
    assert strong.model.channels > default.model.channels
    assert strong.model.res_blocks > default.model.res_blocks
    assert strong.mcts.simulations > default.mcts.simulations
    assert strong.selfplay.games_per_iteration > default.selfplay.games_per_iteration


def test_unknown_section_and_key_rejected():
    with pytest.raises(ConfigError) as ei:
        config_from_dict({"bogus": {}, "model": {"chanels": 3}})
    errs = ei.value.errors
    assert any(e.startswith("bogus:") and "unknown section" in e for e in errs)
    assert any(e.startswith("model.chanels:") and "unknown setting" in e for e in errs)


def test_type_and_range_errors_are_all_collected():
    with pytest.raises(ConfigError) as ei:
        config_from_dict({
            "model": {"channels": "lots", "res_blocks": -1},
            "mcts": {"simulations": 0, "c_puct": "x"},
            "training": {"optimizer": "rmsprop", "batch_size": True},
            "ui": {"port": 70000},
        })
    errs = ei.value.errors
    joined = "\n".join(errs)
    assert "model.channels: expected integer" in joined
    assert "mcts.c_puct: expected number" in joined
    assert "training.batch_size: expected integer" in joined      # bools are not ints
    assert "model.res_blocks: must be in [0, 40]" in joined
    assert "mcts.simulations: must be >= 1" in joined
    assert "training.optimizer" in joined
    assert "ui.port" in joined
    assert len(errs) == 7
    assert "Invalid configuration" in str(ei.value)


def test_null_for_non_nullable_rejected_and_nullable_accepted():
    with pytest.raises(ConfigError) as ei:
        config_from_dict({"model": {"channels": None}})
    assert ei.value.errors == ["model.channels: must not be null"]
    cfg = config_from_dict({"search": {"time_limit": None}, "training": {"seed": None},
                            "ui": {"bot_checkpoint": None}})
    assert cfg.search.time_limit is None and cfg.training.seed is None and cfg.ui.bot_checkpoint is None


def test_numeric_coercion():
    cfg = config_from_dict({"training": {"learning_rate": 1}, "model": {"channels": 32.0}})
    assert cfg.training.learning_rate == 1.0 and isinstance(cfg.training.learning_rate, float)
    assert cfg.model.channels == 32 and isinstance(cfg.model.channels, int)
    with pytest.raises(ConfigError):
        config_from_dict({"model": {"channels": 32.5}})


def test_cross_field_rules():
    with pytest.raises(ConfigError) as ei:
        config_from_dict({"training": {"min_buffer_size": 100, "replay_buffer_size": 50}})
    assert any("min_buffer_size" in e for e in ei.value.errors)
    with pytest.raises(ConfigError) as ei:
        config_from_dict({"evaluation": {"opponent": "checkpoint"}})
    assert any("baseline_checkpoint" in e for e in ei.value.errors)
    cfg = config_from_dict({"evaluation": {"opponent": "checkpoint", "baseline_checkpoint": "iter_000001.pt"}})
    assert cfg.evaluation.opponent == "checkpoint"


def test_partial_dict_merges_defaults():
    cfg = config_from_dict({"mcts": {"simulations": 5}, "run_dir": "x"})
    default = Config()
    assert cfg.mcts.simulations == 5 and cfg.run_dir == "x"
    assert cfg.mcts.c_puct == default.mcts.c_puct
    assert cfg.model == default.model and cfg.training == default.training


def test_section_must_be_mapping_and_top_level_mapping(tmp_path):
    with pytest.raises(ConfigError) as ei:
        config_from_dict({"model": 5})
    assert ei.value.errors == ["model: expected a mapping"]
    assert config_from_dict({"model": None}) == Config()   # empty section in YAML
    p = tmp_path / "list.yaml"
    p.write_text("- 1\n- 2\n")
    with pytest.raises(ConfigError):
        load_config(p)
    empty = tmp_path / "empty.yaml"
    empty.write_text("# nothing\n")
    assert load_config(empty) == Config()


def test_save_config_refuses_invalid(tmp_path):
    cfg = Config()
    cfg.mcts.simulations = 0
    with pytest.raises(ConfigError):
        save_config(cfg, tmp_path / "bad.yaml")
    assert not (tmp_path / "bad.yaml").exists()


def test_validate_config_reports_wrong_types_without_crashing():
    cfg = Config()
    cfg.model.channels = "many"      # type: ignore[assignment]
    cfg.search.depth = 0
    errs = validate_config(cfg)
    assert "model.channels: expected integer, got 'many'" in errs
    assert "search.depth: must be in [1, 64]" in errs


def test_schema_and_help_cover_every_field():
    keys = all_keys()
    schema = config_schema()
    schema_keys = [f["key"] for g in schema for f in g["fields"]]
    assert schema_keys == keys
    assert set(FIELD_HELP) == set(keys)
    assert all(FIELD_HELP[k] for k in keys)
    by_key = {f["key"]: f for g in schema for f in g["fields"]}
    assert by_key["search.time_limit"]["nullable"] is True
    assert by_key["search.time_limit"]["type"] == "float"
    assert by_key["training.optimizer"]["choices"] == ["adam", "sgd"]


def test_configuration_doc_mentions_every_key():
    doc = (ROOT / "docs" / "CONFIGURATION.md").read_text()
    missing = [k for k in all_keys() if f"`{k}`" not in doc]
    assert missing == []
