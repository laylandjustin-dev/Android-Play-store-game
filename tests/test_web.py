"""Tests for the Flask web UI (chessbot.web.app).

Engine/ML components are faked: games use the RandomPlayer or scripted
players, checkpoint listing uses the file-glob fallback, and training uses a
fake Trainer, so these tests do not depend on chessbot.engine / chessbot.ml.
"""

from __future__ import annotations

import json
import sys
import threading
import types
from dataclasses import dataclass
from pathlib import Path

import chess
import pytest

import chessbot.players as players_mod
import chessbot.web.app as web_app
from chessbot.config import load_config
from chessbot.web.app import create_app, safe_checkpoint_path


class ScriptedPlayer:
    def __init__(self, moves):
        self.name = "scripted"
        self.moves = list(moves)

    def select_move(self, board):
        return chess.Move.from_uci(self.moves.pop(0))

    def reset(self):
        pass


@pytest.fixture
def cfg(tiny_config):
    tiny_config.ui.bot_player = "random"
    return tiny_config


@pytest.fixture
def app(cfg, tmp_path, monkeypatch):
    monkeypatch.setattr(web_app, "_list_checkpoints", web_app._glob_checkpoints)
    a = create_app(cfg, config_path=str(tmp_path / "saved.yaml"))
    a.testing = True
    return a


@pytest.fixture
def client(app):
    return app.test_client()


def new_game(client, **body):
    body.setdefault("color", "white")
    body.setdefault("bot", "random")
    r = client.post("/api/game/new", json=body)
    assert r.status_code == 200, r.get_json()
    return r.get_json()


# ------------------------------------------------------------------ pages

@pytest.mark.parametrize("url", ["/", "/dashboard", "/settings", "/models"])
def test_pages_render(client, url):
    r = client.get(url)
    assert r.status_code == 200
    assert b"<html" in r.data and b"Chess Bot" in r.data


@pytest.mark.parametrize("name", ["style.css", "common.js", "play.js", "charts.js",
                                  "dashboard.js", "settings.js", "models.js"])
def test_static_assets_served(client, name):
    r = client.get(f"/static/{name}")
    assert r.status_code == 200
    r.close()


def test_no_external_assets_in_templates():
    tdir = Path(web_app.__file__).parent / "templates"
    for p in tdir.glob("*.html"):
        text = p.read_text()
        assert "http://" not in text and "https://" not in text, p.name


# ------------------------------------------------------------------ game

def test_state_before_any_game(client):
    s = client.get("/api/game/state").get_json()
    assert s["no_game"] is True and s["fen"] == chess.STARTING_FEN
    r = client.post("/api/game/move", json={"uci": "e2e4"})
    assert r.status_code == 400


def test_new_game_as_white(client):
    s = new_game(client)
    assert s["fen"] == chess.STARTING_FEN
    assert s["turn"] == "white" and s["human_color"] == "white"
    assert len(s["legal_moves"]) == 20 and "e2e4" in s["legal_moves"]
    assert s["game_over"] is False and s["result"] is None and s["history"] == []
    assert s["bot"] == "random"


def test_new_game_as_black_bot_moves_first(client):
    s = new_game(client, color="black")
    assert len(s["history"]) == 1
    assert s["turn"] == "black"
    first = chess.Move.from_uci(s["history"][0])
    assert first in chess.Board().legal_moves
    assert s["last_bot_move"] == s["history"][0]


def test_new_game_validation(client):
    assert client.post("/api/game/new", json={"color": "green"}).status_code == 400
    assert client.post("/api/game/new", json={"bot": "stockfish"}).status_code == 400
    assert client.post("/api/game/new", json={"bot": "mcts", "checkpoint": "../etc/passwd"}).status_code == 400


def test_legal_move_gets_legal_bot_reply(client):
    new_game(client)
    r = client.post("/api/game/move", json={"uci": "e2e4"})
    assert r.status_code == 200
    s = r.get_json()
    assert len(s["history"]) == 2 and s["history"][0] == "e2e4"
    board = chess.Board()
    board.push_uci("e2e4")
    reply = chess.Move.from_uci(s["history"][1])
    assert reply in board.legal_moves
    board.push(reply)
    assert s["fen"] == board.fen()
    assert s["turn"] == "white"
    assert s["history_san"][0] == "e4"
    assert sorted(s["legal_moves"]) == sorted(m.uci() for m in board.legal_moves)


def test_many_moves_with_random_bot_stay_legal(client):
    new_game(client)
    for _ in range(15):
        s = client.get("/api/game/state").get_json()
        if s["game_over"]:
            break
        r = client.post("/api/game/move", json={"uci": s["legal_moves"][0]})
        assert r.status_code == 200
    board = chess.Board()
    for u in client.get("/api/game/state").get_json()["history"]:
        move = chess.Move.from_uci(u)
        assert move in board.legal_moves
        board.push(move)


@pytest.mark.parametrize("bad", ["e2e5", "e7e5", "zz99", "", "e2", "e2e4e4", 42, None])
def test_illegal_or_malformed_move_rejected(client, bad):
    new_game(client)
    r = client.post("/api/game/move", json={"uci": bad})
    assert r.status_code == 400
    assert "error" in r.get_json()
    # board unchanged
    assert client.get("/api/game/state").get_json()["history"] == []


def test_move_when_not_your_turn_and_bot_move_endpoint(app, client, monkeypatch):
    monkeypatch.setattr(players_mod, "make_player",
                        lambda kind, config, checkpoint=None, **kw: ScriptedPlayer(["e2e4"]))
    # Human is black but make the bot not move yet: create the game, then rewind.
    s = new_game(client, color="black")
    assert s["history"] == ["e2e4"]
    r = client.post("/api/game/move", json={"uci": "e2e4"})
    assert r.status_code == 400  # it is black's move; e2e4 is not legal for black
    # bot_move is refused when it is the human's turn
    assert client.post("/api/game/bot_move").status_code == 400
    # simulate a state where the bot is to move and call bot_move
    game = app.extensions["chessbot"]["state"]["game"]
    game.board.pop()
    game.history_san.pop()
    game.player.moves = ["d2d4"]
    r = client.post("/api/game/bot_move")
    assert r.status_code == 200
    assert r.get_json()["history"] == ["d2d4"]


def test_fools_mate_game_over(client, monkeypatch):
    # Bot plays white's losing moves; human (black) delivers fool's mate.
    monkeypatch.setattr(players_mod, "make_player",
                        lambda kind, config, checkpoint=None, **kw: ScriptedPlayer(["f2f3", "g2g4"]))
    s = new_game(client, color="black")
    assert s["history"] == ["f2f3"]
    s = client.post("/api/game/move", json={"uci": "e7e5"}).get_json()
    assert s["history"] == ["f2f3", "e7e5", "g2g4"]
    r = client.post("/api/game/move", json={"uci": "d8h4"})
    assert r.status_code == 200
    s = r.get_json()
    assert s["game_over"] is True
    assert s["result"] == "0-1"
    assert s["termination"] == "checkmate"
    assert s["legal_moves"] == []
    assert s["check"] is True
    assert s["history_san"][-1] == "Qh4#"
    # further moves rejected
    assert client.post("/api/game/move", json={"uci": "a2a3"}).status_code == 400
    assert client.post("/api/game/bot_move").status_code == 400


def test_promotion(app, client):
    new_game(client)
    game = app.extensions["chessbot"]["state"]["game"]
    game.board = chess.Board("7k/P7/8/8/8/8/8/K7 w - - 0 1")
    s = client.get("/api/game/state").get_json()
    assert {"a7a8q", "a7a8r", "a7a8b", "a7a8n"} <= set(s["legal_moves"])
    r = client.post("/api/game/move", json={"uci": "a7a8"})
    assert r.status_code == 400 and "promotion" in r.get_json()["error"]
    r = client.post("/api/game/move", json={"uci": "a7a8n"})
    assert r.status_code == 200
    s = r.get_json()
    assert s["history"][0] == "a7a8n"
    assert game.board.move_stack[0].promotion == chess.KNIGHT


# ------------------------------------------------------------------ bot fallbacks

class Recorder:
    def __init__(self, fail_kinds=()):
        self.calls = []
        self.fail_kinds = set(fail_kinds)

    def __call__(self, kind, config, checkpoint=None, **kw):
        self.calls.append((kind, checkpoint))
        if kind in self.fail_kinds:
            raise ImportError(f"{kind} unavailable")
        p = players_mod.RandomPlayer(seed=0)
        p.name = f"{kind}[{checkpoint}]"
        return p


def test_mcts_missing_checkpoint_falls_back_to_untrained(client, monkeypatch):
    rec = Recorder()
    monkeypatch.setattr(players_mod, "make_player", rec)
    s = new_game(client, bot="mcts", checkpoint="latest")
    assert rec.calls == [("mcts", None)]
    assert s["bot"] == "mcts" and s["checkpoint"] is None
    assert s["warning"] and "untrained" in s["warning"]


def test_mcts_default_checkpoint_from_config(client, monkeypatch, cfg):
    rec = Recorder()
    monkeypatch.setattr(players_mod, "make_player", rec)
    cfg.ui.bot_checkpoint = "iter_000003.pt"
    s = new_game(client, bot="mcts")   # no checkpoint key -> ui.bot_checkpoint
    assert rec.calls == [("mcts", None)]
    assert "iter_000003.pt" in s["warning"]


def test_mcts_existing_checkpoint_is_used(client, monkeypatch, cfg):
    rec = Recorder()
    monkeypatch.setattr(players_mod, "make_player", rec)
    cfg.checkpoint_dir.mkdir(parents=True)
    (cfg.checkpoint_dir / "iter_000002.pt").write_bytes(b"x")
    s = new_game(client, bot="mcts", checkpoint="iter_000002.pt")
    assert rec.calls == [("mcts", "iter_000002.pt")]
    assert s["warning"] is None and s["checkpoint"] == "iter_000002.pt"


def test_alphabeta_nn_missing_checkpoint_falls_back_to_alphabeta(client, monkeypatch):
    rec = Recorder()
    monkeypatch.setattr(players_mod, "make_player", rec)
    s = new_game(client, bot="alphabeta_nn", checkpoint="latest")
    assert rec.calls == [("alphabeta", None)]
    assert s["bot"] == "alphabeta" and "alpha-beta" in s["warning"]


def test_unavailable_modules_fall_back_to_random(client, monkeypatch):
    rec = Recorder(fail_kinds={"mcts", "alphabeta"})
    monkeypatch.setattr(players_mod, "make_player", rec)
    s = new_game(client, bot="mcts", checkpoint=None)
    assert [k for k, _ in rec.calls] == ["mcts", "alphabeta", "random"]
    assert s["bot"] == "random" and "Could not create 'mcts'" in s["warning"]
    assert client.post("/api/game/move", json={"uci": "e2e4"}).status_code == 200


# ------------------------------------------------------------------ config

def test_config_get(client, cfg):
    d = client.get("/api/config").get_json()
    assert d["run_dir"] == cfg.run_dir
    assert d["mcts"]["simulations"] == cfg.mcts.simulations
    schema = client.get("/api/config/schema").get_json()
    assert [g["section"] for g in schema["sections"]][:2] == ["general", "model"]


def test_config_post_valid_applies_and_saves(client, cfg, tmp_path):
    r = client.post("/api/config", json={"mcts": {"simulations": 11}, "model": {"channels": 24}})
    assert r.status_code == 200, r.get_json()
    body = r.get_json()
    assert body["ok"] is True and body["saved"] is True
    assert cfg.mcts.simulations == 11 and cfg.model.channels == 24
    assert cfg.model.res_blocks == 1      # unchanged values preserved (merge with current)
    saved = load_config(tmp_path / "saved.yaml")
    assert saved.mcts.simulations == 11 and saved.model.res_blocks == 1
    assert client.get("/api/config").get_json()["mcts"]["simulations"] == 11


def test_config_post_invalid_returns_all_errors(client, cfg, tmp_path):
    r = client.post("/api/config", json={"mcts": {"simulations": 0, "c_puct": "abc"},
                                         "nope": 1, "ui": {"port": 0}})
    assert r.status_code == 400
    errors = r.get_json()["errors"]
    assert len(errors) == 4
    assert any(e.startswith("mcts.simulations") for e in errors)
    assert any(e.startswith("mcts.c_puct") for e in errors)
    assert any(e.startswith("nope") for e in errors)
    assert any(e.startswith("ui.port") for e in errors)
    assert cfg.mcts.simulations == 8          # unchanged
    assert not (tmp_path / "saved.yaml").exists()


def test_config_post_non_object(client):
    r = client.post("/api/config", data="[1,2]", content_type="application/json")
    assert r.status_code == 400 and r.get_json()["errors"]


def test_config_post_without_path_not_saved(tiny_config):
    a = create_app(tiny_config)
    r = a.test_client().post("/api/config", json={"search": {"depth": 4}})
    assert r.status_code == 200 and r.get_json()["saved"] is False
    assert tiny_config.search.depth == 4


# ------------------------------------------------------------------ metrics

def test_metrics_missing_file(client):
    assert client.get("/api/metrics").get_json() == []


def test_metrics_reads_jsonl_skipping_bad_lines(client, cfg):
    path = Path(cfg.metrics_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    rec1 = {"type": "train", "iteration": 1, "policy_loss": 3.2, "value_loss": 0.9, "total_loss": 4.1}
    rec2 = {"type": "eval", "iteration": 1, "score": 0.7}
    path.write_text(json.dumps(rec1) + "\n{not json\n\n[1, 2]\n" + json.dumps(rec2) + "\n{\"trunc")
    data = client.get("/api/metrics").get_json()
    assert data == [rec1, rec2]
    assert client.get("/api/metrics?type=eval").get_json() == [rec2]


# ------------------------------------------------------------------ models

def make_ckpts(cfg, names=("iter_000001.pt", "iter_000002.pt", "latest.pt")):
    cfg.checkpoint_dir.mkdir(parents=True, exist_ok=True)
    for i, n in enumerate(names):
        (cfg.checkpoint_dir / n).write_bytes(b"0" * (10 + i))


def test_models_list_empty(client):
    d = client.get("/api/models").get_json()
    assert d["models"] == [] and d["selected"] == "latest"


def test_models_list_select_delete(client, cfg, tmp_path):
    make_ckpts(cfg)
    d = client.get("/api/models").get_json()
    names = [m["name"] for m in d["models"]]
    assert set(names) == {"iter_000001.pt", "iter_000002.pt", "latest.pt"}
    m1 = next(m for m in d["models"] if m["name"] == "iter_000001.pt")
    assert m1["iteration"] == 1 and m1["size"] == 10 and m1["timestamp"]
    assert {"name", "iteration", "global_step", "timestamp", "size", "selected"} <= set(m1)
    assert next(m for m in d["models"] if m["name"] == "latest.pt")["selected"] is True

    r = client.post("/api/models/select", json={"name": "iter_000002.pt"})
    assert r.status_code == 200
    assert cfg.ui.bot_checkpoint == "iter_000002.pt"
    assert load_config(tmp_path / "saved.yaml").ui.bot_checkpoint == "iter_000002.pt"
    sel = [m["name"] for m in r.get_json()["models"] if m["selected"]]
    assert sel == ["iter_000002.pt"]

    assert client.post("/api/models/select", json={"name": None}).status_code == 200
    assert cfg.ui.bot_checkpoint is None
    assert client.post("/api/models/select", json={"name": "latest"}).status_code == 200
    assert client.post("/api/models/select", json={"name": "iter_000009.pt"}).status_code == 404
    assert client.post("/api/models/select", json={"name": "../saved.yaml"}).status_code == 400
    assert client.post("/api/models/select", json={}).status_code == 400

    client.post("/api/models/select", json={"name": "iter_000001.pt"})
    r = client.delete("/api/models/iter_000001.pt")
    assert r.status_code == 200
    assert not (cfg.checkpoint_dir / "iter_000001.pt").exists()
    assert cfg.ui.bot_checkpoint == "latest"     # deleted selection resets to latest
    assert client.delete("/api/models/iter_000001.pt").status_code == 404


def test_models_delete_rejects_traversal(client, cfg, tmp_path):
    make_ckpts(cfg)
    secret = Path(cfg.run_dir) / "secret.pt"
    secret.write_text("keep")
    other = cfg.checkpoint_dir / "notes.txt"
    other.write_text("keep")
    for url in ["/api/models/..%2Fsecret.pt", "/api/models/%2E%2E%2Fsecret.pt",
                "/api/models/..", "/api/models/notes.txt", "/api/models/.hidden.pt"]:
        r = client.delete(url)
        assert r.status_code in (400, 404, 405), url
    assert secret.read_text() == "keep" and other.read_text() == "keep"


def test_safe_checkpoint_path(tmp_path):
    d = tmp_path / "ck"
    d.mkdir()
    assert safe_checkpoint_path(d, "iter_000001.pt") == (d / "iter_000001.pt").resolve()
    for bad in ["../x.pt", "..", "a/b.pt", "/etc/passwd", "", ".x", "a\\b.pt", "x..pt", None]:
        assert safe_checkpoint_path(d, bad) is None, bad
    outside = tmp_path / "outside.pt"
    outside.write_text("x")
    (d / "link.pt").symlink_to(outside)
    assert safe_checkpoint_path(d, "link.pt") is None


def test_list_checkpoints_uses_ml_module(monkeypatch, tmp_path):
    @dataclass
    class CheckpointInfo:
        path: Path
        iteration: int
        global_step: int
        timestamp: str
        size_bytes: int

    fake = types.ModuleType("chessbot.ml.checkpoints")
    fake.list_checkpoints = lambda d: [CheckpointInfo(Path(d) / "iter_000004.pt", 4, 400, "2026-01-01T00:00:00", 1234)]
    monkeypatch.setitem(sys.modules, "chessbot.ml.checkpoints", fake)
    out = web_app._list_checkpoints(tmp_path)
    assert out == [{"name": "iter_000004.pt", "iteration": 4, "global_step": 400,
                    "timestamp": "2026-01-01T00:00:00", "size": 1234}]


# ------------------------------------------------------------------ training

class FakeTrainer:
    instances: list["FakeTrainer"] = []
    block = threading.Event()

    def __init__(self, config, resume=True, log_fn=None):
        self.config = config
        self.resume = resume
        self.log = log_fn or (lambda m: None)
        self.iteration = 0
        self.global_step = 0
        FakeTrainer.instances.append(self)

    def run(self, iterations=None, stop_event=None, on_iteration_end=None):
        out = []
        for _ in range(iterations):
            if stop_event is not None and stop_event.is_set():
                break
            FakeTrainer.block.wait(5)
            self.iteration += 1
            m = {"type": "train", "iteration": self.iteration}
            self.log(f"iteration {self.iteration} done")
            out.append(m)
            if on_iteration_end:
                on_iteration_end(m)
        return out


@pytest.fixture
def fake_trainer_app(cfg, monkeypatch):
    FakeTrainer.instances = []
    FakeTrainer.block = threading.Event()
    fake_mod = types.ModuleType("chessbot.ml.trainer")
    fake_mod.Trainer = FakeTrainer
    monkeypatch.setitem(sys.modules, "chessbot.ml.trainer", fake_mod)
    a = create_app(cfg)
    yield a
    FakeTrainer.block.set()
    a.extensions["chessbot"]["training"].join(5)


def test_training_start_status_finish(fake_trainer_app, cfg):
    client = fake_trainer_app.test_client()
    tm = fake_trainer_app.extensions["chessbot"]["training"]
    st = client.get("/api/training/status").get_json()
    assert st["running"] is False and st["log"] == []
    FakeTrainer.block.set()
    r = client.post("/api/training/start", json={"iterations": 3})
    assert r.status_code == 200
    tm.join(5)
    st = client.get("/api/training/status").get_json()
    assert st["running"] is False and st["error"] is None
    assert st["iteration"] == 3 and st["completed_iterations"] == 3
    assert any("iteration 3 done" in line for line in st["log"])
    assert any("finished" in line for line in st["log"])
    t = FakeTrainer.instances[0]
    assert t.resume is True
    assert t.config is not cfg and t.config == cfg     # trainer gets a private copy


def test_training_no_concurrent_runs_and_stop(fake_trainer_app):
    client = fake_trainer_app.test_client()
    tm = fake_trainer_app.extensions["chessbot"]["training"]
    r = client.post("/api/training/start", json={"iterations": 100})
    assert r.status_code == 200 and r.get_json()["running"] is True
    r2 = client.post("/api/training/start", json={"iterations": 1})
    assert r2.status_code == 409
    assert len(FakeTrainer.instances) <= 1
    r = client.post("/api/training/stop")
    assert r.get_json()["stop_sent"] is True and r.get_json()["stop_requested"] is True
    FakeTrainer.block.set()
    tm.join(5)
    st = client.get("/api/training/status").get_json()
    assert st["running"] is False
    assert st["completed_iterations"] < 100
    assert any("stopped by user" in line for line in st["log"])
    # a new run may start after the previous one finished
    assert client.post("/api/training/start", json={"iterations": 1}).status_code == 200
    tm.join(5)
    assert client.post("/api/training/stop").get_json()["stop_sent"] is False


def test_training_bad_iterations(fake_trainer_app):
    client = fake_trainer_app.test_client()
    for bad in [0, -1, "x", 1.5, True]:
        assert client.post("/api/training/start", json={"iterations": bad}).status_code == 400


def test_training_error_reported(cfg):
    def broken(config, resume=True, log_fn=None):
        raise RuntimeError("no GPU here")

    a = create_app(cfg, trainer_factory=broken)
    client = a.test_client()
    assert client.post("/api/training/start", json={"iterations": 1}).status_code == 200
    a.extensions["chessbot"]["training"].join(5)
    st = client.get("/api/training/status").get_json()
    assert st["running"] is False
    assert st["error"] == "RuntimeError: no GPU here"
    assert any("ERROR" in line for line in st["log"])
