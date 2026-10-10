import json

import yaml

from chessbot.cli import main
from chessbot.config import config_to_dict, load_config, save_config


def _write(cfg, tmp_path):
    path = tmp_path / "cfg.yaml"
    save_config(cfg, path)
    return str(path)


def test_config_command_roundtrip(tiny_config, tmp_path, capsys):
    path = _write(tiny_config, tmp_path)
    out = tmp_path / "out.yaml"
    assert main(["--config", path, "config", "--write", str(out)]) == 0
    assert config_to_dict(load_config(out)) == config_to_dict(tiny_config)


def test_invalid_config_exit_code(tmp_path, capsys):
    bad = tmp_path / "bad.yaml"
    bad.write_text(yaml.safe_dump({"mcts": {"simulations": 0}}))
    assert main(["--config", str(bad), "config"]) == 2
    assert "mcts.simulations" in capsys.readouterr().err


def test_train_then_evaluate_and_resume(tiny_config, tmp_path):
    tiny_config.selfplay.max_moves = 20
    path = _write(tiny_config, tmp_path)
    assert main(["--config", path, "train", "--iterations", "1"]) == 0
    assert main(["--config", path, "train", "--iterations", "1"]) == 0  # resumes
    assert main(["--config", path, "evaluate", "--games", "2", "--opponent", "random",
                 "--simulations", "4", "--max-moves", "20", "--seed", "0"]) == 0
    records = [json.loads(l) for l in open(tiny_config.metrics_path)]
    train = [r for r in records if r["type"] == "train"]
    evals = [r for r in records if r["type"] == "eval"]
    assert [r["iteration"] for r in train] == [1, 2]
    assert len(evals) == 1 and evals[0]["games"] == 2 and evals[0]["iteration"] == 2
    assert evals[0]["wins"] + evals[0]["draws"] + evals[0]["losses"] == 2
