"""Smoke tests for scripts/benchmark.py and scripts/evaluate_checkpoints.py."""

from __future__ import annotations

import importlib.util
import json
import math
from pathlib import Path

import pytest

from chessbot.config import save_config

ROOT = Path(__file__).resolve().parent.parent


def _load_script(name: str):
    path = ROOT / "scripts" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(f"qa_script_{name}", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_benchmark_movegen_section(tmp_path, capsys):
    bench = _load_script("benchmark")
    out_json = tmp_path / "b.json"
    out = bench.main(["--sections", "movegen", "--perft-depth", "3", "--json", str(out_json)])
    sec = out["sections"]["movegen"]
    assert sec["status"] == "ok"
    start = next(r for r in sec["rows"] if r["position"] == "start")
    assert start["nodes"] == 8902 and start["nodes_per_sec"] > 0
    assert json.loads(out_json.read_text())["sections"]["movegen"]["status"] == "ok"
    assert "movegen" in capsys.readouterr().out


def test_benchmark_rejects_unknown_section():
    bench = _load_script("benchmark")
    with pytest.raises(SystemExit):
        bench.main(["--sections", "bogus"])


def test_benchmark_all_sections_never_crash(tiny_config, tmp_path):
    """Every section is ok, skipped (module missing) or a reported error."""
    bench = _load_script("benchmark")
    cfg_path = tmp_path / "c.yaml"
    save_config(tiny_config, cfg_path)
    out = bench.main(["--config", str(cfg_path), "--quick", "--depth", "2",
                      "--min-time", "0.05", "--simulations", "4",
                      "--selfplay-max-moves", "6"])
    for name, sec in out["sections"].items():
        assert sec["status"] in ("ok", "skipped"), (name, sec.get("reason"), sec.get("traceback"))


def test_evaluate_untrained_vs_random(tiny_config, tmp_path, capsys):
    pytest.importorskip("chessbot.ml.mcts")
    pytest.importorskip("chessbot.ml.checkpoints")
    ev = _load_script("evaluate_checkpoints")
    cfg_path = tmp_path / "c.yaml"
    save_config(tiny_config, cfg_path)
    rec = ev.main(["--config", str(cfg_path), "--candidate", "untrained",
                   "--opponent", "random", "--games", "2", "--simulations", "4",
                   "--max-moves", "16", "--quiet"])
    assert rec["games"] == 2 == rec["wins"] + rec["draws"] + rec["losses"]
    assert rec["elo_low"] <= rec["elo"] <= rec["elo_high"]
    lines = Path(tiny_config.metrics_path).read_text().splitlines()
    logged = json.loads(lines[-1])
    assert logged["type"] == "eval" and logged == rec
    for k in ("timestamp", "iteration", "candidate", "opponent", "games", "wins", "draws",
              "losses", "score", "elo", "elo_low", "elo_high"):
        assert k in logged
    assert all(math.isfinite(logged[k]) for k in ("score", "elo", "elo_low", "elo_high"))
    out = capsys.readouterr().out
    assert "Elo diff" in out and f"W {rec['wins']} / D {rec['draws']} / L {rec['losses']}" in out


def test_evaluate_missing_checkpoint_exits(tiny_config, tmp_path):
    pytest.importorskip("chessbot.ml.checkpoints")
    ev = _load_script("evaluate_checkpoints")
    cfg_path = tmp_path / "c.yaml"
    save_config(tiny_config, cfg_path)
    with pytest.raises(SystemExit):
        ev.main(["--config", str(cfg_path), "--candidate", "latest", "--games", "1"])


def test_evaluate_checkpoint_vs_checkpoint(tiny_config, tmp_path):
    pytest.importorskip("chessbot.ml.checkpoints")
    pytest.importorskip("chessbot.ml.model")
    from chessbot.ml.checkpoints import save_checkpoint
    from chessbot.ml.model import PolicyValueNet

    for it in (1, 2):
        save_checkpoint(tiny_config.checkpoint_dir / f"iter_{it:06d}.pt",
                        PolicyValueNet(tiny_config.model), None, it, it * 5, tiny_config)
    ev = _load_script("evaluate_checkpoints")
    cfg_path = tmp_path / "c.yaml"
    save_config(tiny_config, cfg_path)
    rec = ev.main(["--config", str(cfg_path), "--candidate", "iter_000002.pt",
                   "--opponent", "checkpoint", "--baseline", "iter_000001.pt",
                   "--games", "2", "--simulations", "4", "--max-moves", "10", "--quiet"])
    assert rec["iteration"] == 2
    assert rec["opponent"].startswith("checkpoint:")
