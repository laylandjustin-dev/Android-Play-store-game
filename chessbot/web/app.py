"""Flask web UI: play against the bot, training dashboard, settings and models.

All state (current game, training thread) lives in memory inside the app
object created by :func:`create_app`; it is designed for a single local user.
Engine/ML modules are imported lazily so the UI works (with graceful
fallbacks) even when those modules are unavailable.
"""

from __future__ import annotations

import collections
import copy
import json
import random
import re
import threading
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import chess
from flask import Flask, jsonify, render_template, request

from chessbot.config import (
    PLAYER_KINDS,
    Config,
    ConfigError,
    config_from_dict,
    config_schema,
    config_to_dict,
    save_config,
)

_SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_ITER_RE = re.compile(r"(\d+)")


# ---------------------------------------------------------------------------
# Checkpoint helpers
# ---------------------------------------------------------------------------

def _glob_checkpoints(checkpoint_dir: Path) -> list[dict[str, Any]]:
    """Fallback listing that only looks at file names/stat (no torch needed)."""
    checkpoint_dir = Path(checkpoint_dir)
    if not checkpoint_dir.is_dir():
        return []
    out = []
    for p in checkpoint_dir.glob("*.pt"):
        if not p.is_file():
            continue
        m = _ITER_RE.search(p.stem)
        st = p.stat()
        out.append({
            "name": p.name,
            "iteration": int(m.group(1)) if m else None,
            "global_step": None,
            "timestamp": datetime.fromtimestamp(st.st_mtime, timezone.utc).isoformat(timespec="seconds"),
            "size": st.st_size,
        })
    out.sort(key=lambda r: (r["iteration"] is None, r["iteration"] or 0, r["name"]))
    return out


def _list_checkpoints(checkpoint_dir: Path) -> list[dict[str, Any]]:
    """List checkpoints via chessbot.ml.checkpoints, falling back to a file glob."""
    try:
        from chessbot.ml.checkpoints import list_checkpoints
    except Exception:  # ML module not available (yet)
        return _glob_checkpoints(checkpoint_dir)
    try:
        infos = list_checkpoints(checkpoint_dir)
    except Exception:
        return _glob_checkpoints(checkpoint_dir)
    out = []
    for info in infos:
        path = Path(info.path)
        out.append({
            "name": path.name,
            "iteration": info.iteration,
            "global_step": info.global_step,
            "timestamp": info.timestamp,
            "size": info.size_bytes,
        })
    return out


def safe_checkpoint_path(checkpoint_dir: Path, name: str) -> Path | None:
    """Return the path of ``name`` inside ``checkpoint_dir`` or None if unsafe.

    Rejects anything containing path separators, ``..``, absolute paths or
    names that resolve outside the checkpoint directory (e.g. via symlinks).
    """
    if not isinstance(name, str) or not _SAFE_NAME.match(name) or ".." in name:
        return None
    base = Path(checkpoint_dir).resolve()
    candidate = (base / name).resolve()
    if candidate.parent != base:
        return None
    return candidate


def _checkpoint_spec_ok(spec: str | None) -> bool:
    """A bot checkpoint spec from the UI: None/'' (untrained), 'latest' or a bare file name."""
    if spec in (None, "", "latest"):
        return True
    return isinstance(spec, str) and bool(_SAFE_NAME.match(spec)) and ".." not in spec


def _checkpoint_exists(config: Config, spec: str) -> bool:
    if spec == "latest":
        try:
            from chessbot.ml.checkpoints import resolve_checkpoint

            return resolve_checkpoint("latest", config.checkpoint_dir) is not None
        except Exception:
            ckdir = config.checkpoint_dir
            return (ckdir / "latest.pt").is_file() or bool(_glob_checkpoints(ckdir))
    path = safe_checkpoint_path(config.checkpoint_dir, spec)
    return path is not None and path.is_file()


# ---------------------------------------------------------------------------
# Game session
# ---------------------------------------------------------------------------

def _result_info(board: chess.Board) -> tuple[bool, str | None, str | None]:
    outcome = board.outcome(claim_draw=True)
    if outcome is None:
        return False, None, None
    return True, outcome.result(), outcome.termination.name.lower()


class GameSession:
    def __init__(self, human_color: chess.Color, bot_kind: str, checkpoint: str | None,
                 player: Any, bot_name: str, warning: str | None):
        self.board = chess.Board()
        self.human_color = human_color
        self.bot_kind = bot_kind
        self.checkpoint = checkpoint
        self.player = player
        self.bot_name = bot_name
        self.warnings: list[str] = [warning] if warning else []
        self.history_san: list[str] = []
        self.last_bot_move: str | None = None

    def push(self, move: chess.Move) -> None:
        self.history_san.append(self.board.san(move))
        self.board.push(move)

    def state(self) -> dict[str, Any]:
        b = self.board
        over, result, termination = _result_info(b)
        return {
            "fen": b.fen(),
            "legal_moves": [] if over else [m.uci() for m in b.legal_moves],
            "turn": "white" if b.turn == chess.WHITE else "black",
            "game_over": over,
            "result": result,
            "termination": termination,
            "history": [m.uci() for m in b.move_stack],
            "history_san": list(self.history_san),
            "last_move": b.move_stack[-1].uci() if b.move_stack else None,
            "last_bot_move": self.last_bot_move,
            "check": b.is_check(),
            "human_color": "white" if self.human_color == chess.WHITE else "black",
            "bot": self.bot_kind,
            "bot_name": self.bot_name,
            "checkpoint": self.checkpoint,
            "warning": " ".join(self.warnings) if self.warnings else None,
        }


def _make_bot(config: Config, kind: str,
              checkpoint: str | None) -> tuple[Any, str, str | None, str | None]:
    """Create a bot with graceful fallbacks.

    Returns ``(player, kind_used, checkpoint_used, warning)``. A missing
    checkpoint makes ``mcts`` use an untrained network and ``alphabeta_nn``
    fall back to classical ``alphabeta``. If construction still fails (e.g.
    the ML/engine modules are unavailable) we fall further back towards the
    random player, reporting every step in ``warning``.
    """
    from chessbot.players import make_player

    warnings: list[str] = []
    ckpt = checkpoint or None
    if kind == "alphabeta_nn" and ckpt is None:
        ckpt = "latest"
    if kind in ("mcts", "alphabeta_nn") and ckpt is not None and not _checkpoint_exists(config, ckpt):
        if kind == "mcts":
            warnings.append(f"Checkpoint '{ckpt}' not found; using an untrained network.")
            ckpt = None
        else:
            warnings.append(f"Checkpoint '{ckpt}' not found; falling back to classical alpha-beta.")
            kind, ckpt = "alphabeta", None

    chain: list[tuple[str, str | None]] = [(kind, ckpt)]
    if kind == "mcts" and ckpt is not None:
        chain.append(("mcts", None))
    if kind != "random":
        if kind != "alphabeta":
            chain.append(("alphabeta", None))
        chain.append(("random", None))

    last_exc: Exception | None = None
    for i, (k, c) in enumerate(chain):
        try:
            player = make_player(k, config, checkpoint=c)
        except Exception as exc:  # missing module, unreadable checkpoint, ...
            last_exc = exc
            warnings.append(f"Could not create '{k}' bot ({type(exc).__name__}: {exc}).")
            continue
        if i > 0:
            warnings.append(f"Using '{k}'" + (" with an untrained network" if k == "mcts" else "")
                            + " instead.")
        return player, k, c, " ".join(warnings) or None
    raise RuntimeError(f"could not create any bot: {last_exc}")


# ---------------------------------------------------------------------------
# Training manager
# ---------------------------------------------------------------------------

class TrainingManager:
    """Runs ``Trainer.run`` in a background thread; at most one at a time."""

    def __init__(self, trainer_factory: Callable[..., Any] | None = None, max_log: int = 300):
        self._factory = trainer_factory
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._trainer: Any = None
        self.logs: collections.deque[str] = collections.deque(maxlen=max_log)
        self.error: str | None = None
        self.iteration: int | None = None
        self.target_iterations: int | None = None
        self.completed_iterations = 0
        self.started_at: str | None = None
        self.finished_at: str | None = None
        self.last_metrics: dict | None = None

    def _log(self, msg: str) -> None:
        stamp = time.strftime("%H:%M:%S")
        for line in str(msg).splitlines() or [""]:
            self.logs.append(f"[{stamp}] {line}")

    @property
    def running(self) -> bool:
        t = self._thread
        return t is not None and t.is_alive()

    def start(self, config: Config, iterations: int) -> bool:
        with self._lock:
            if self.running:
                return False
            self._stop = threading.Event()
            self.error = None
            self.iteration = None
            self.completed_iterations = 0
            self.last_metrics = None
            self.target_iterations = iterations
            self.started_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
            self.finished_at = None
            self._trainer = None
            cfg = copy.deepcopy(config)  # later settings edits must not affect a live run
            self._thread = threading.Thread(target=self._run, args=(cfg, iterations, self._stop),
                                            name="chessbot-training", daemon=True)
            self._thread.start()
            return True

    def _factory_or_default(self) -> Callable[..., Any]:
        if self._factory is not None:
            return self._factory
        from chessbot.ml.trainer import Trainer

        return Trainer

    def _on_iteration_end(self, metrics: dict) -> None:
        self.completed_iterations += 1
        if isinstance(metrics, dict):
            self.last_metrics = metrics
            if "iteration" in metrics:
                self.iteration = metrics["iteration"]

    def _run(self, cfg: Config, iterations: int, stop: threading.Event) -> None:
        self._log(f"Starting training: {iterations} iteration(s), run_dir={cfg.run_dir}")
        try:
            factory = self._factory_or_default()
            trainer = factory(cfg, resume=True, log_fn=self._log)
            self._trainer = trainer
            self.iteration = getattr(trainer, "iteration", None)
            trainer.run(iterations, stop_event=stop, on_iteration_end=self._on_iteration_end)
            self._log("Training stopped by user." if stop.is_set() else "Training finished.")
        except Exception as exc:
            self.error = f"{type(exc).__name__}: {exc}"
            self._log("ERROR: " + self.error)
            for line in traceback.format_exc().splitlines()[-8:]:
                self.logs.append("    " + line)
        finally:
            trainer = self._trainer
            if trainer is not None and getattr(trainer, "iteration", None) is not None:
                self.iteration = trainer.iteration
            self.finished_at = datetime.now(timezone.utc).isoformat(timespec="seconds")

    def stop(self) -> bool:
        if not self.running:
            return False
        self._stop.set()
        self._log("Stop requested; finishing the current step...")
        return True

    def join(self, timeout: float | None = None) -> None:
        t = self._thread
        if t is not None:
            t.join(timeout)

    def status(self, n_logs: int = 100) -> dict[str, Any]:
        trainer = self._trainer
        iteration = self.iteration
        if self.running and trainer is not None and getattr(trainer, "iteration", None) is not None:
            iteration = trainer.iteration
        return {
            "running": self.running,
            "stop_requested": self._stop.is_set(),
            "iteration": iteration,
            "completed_iterations": self.completed_iterations,
            "target_iterations": self.target_iterations,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "error": self.error,
            "last_metrics": self.last_metrics,
            "log": list(self.logs)[-n_logs:],
        }


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------

def read_metrics(path: Path) -> list[dict]:
    path = Path(path)
    if not path.is_file():
        return []
    records = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except (json.JSONDecodeError, ValueError):
                continue
            if isinstance(rec, dict):
                records.append(rec)
    return records


def _deep_merge(base: dict, update: dict) -> dict:
    out = dict(base)
    for k, v in update.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _deep_merge(out[k], v)
        else:
            out[k] = v
    return out


# ---------------------------------------------------------------------------
# App factory
# ---------------------------------------------------------------------------

def create_app(config: Config, config_path: str | None = None,
               trainer_factory: Callable[..., Any] | None = None) -> Flask:
    """Create the web app.

    ``config`` is updated in place when settings are saved via the UI;
    ``config_path`` (optional) is where those settings are persisted as YAML.
    ``trainer_factory`` (optional, mainly for tests) replaces
    ``chessbot.ml.trainer.Trainer``.
    """
    app = Flask(__name__, template_folder="templates", static_folder="static")
    app.json.sort_keys = False
    lock = threading.Lock()
    state: dict[str, Any] = {"config": config, "game": None}
    training = TrainingManager(trainer_factory)
    app.extensions["chessbot"] = {"state": state, "training": training, "lock": lock,
                                  "config_path": config_path}

    def cfg() -> Config:
        return state["config"]

    def err(msg: str, code: int = 400, **extra):
        return jsonify({"error": msg, **extra}), code

    def body() -> dict:
        data = request.get_json(silent=True)
        return data if isinstance(data, dict) else {}

    def bot_play(game: GameSession) -> None:
        if game.board.is_game_over(claim_draw=True):
            return
        move = game.player.select_move(game.board.copy())
        if move not in game.board.legal_moves:
            raise RuntimeError(f"bot returned illegal move {move}")
        game.last_bot_move = move.uci()
        game.push(move)

    def persist_config() -> bool:
        if not config_path:
            return False
        save_config(cfg(), config_path)
        return True

    def apply_config(new: Config) -> None:
        cur = cfg()
        for f in ("run_dir", "model", "search", "mcts", "selfplay", "training", "evaluation", "ui"):
            setattr(cur, f, getattr(new, f))

    # ----------------------------------------------------------- pages
    @app.get("/")
    def page_play():
        return render_template("play.html", page="play", player_kinds=PLAYER_KINDS)

    @app.get("/dashboard")
    def page_dashboard():
        return render_template("dashboard.html", page="dashboard")

    @app.get("/settings")
    def page_settings():
        return render_template("settings.html", page="settings", config_path=config_path)

    @app.get("/models")
    def page_models():
        return render_template("models.html", page="models")

    # ----------------------------------------------------------- game API
    @app.post("/api/game/new")
    def game_new():
        data = body()
        color = data.get("color", "white")
        if color == "random":
            color = random.choice(["white", "black"])
        if color not in ("white", "black"):
            return err("color must be 'white', 'black' or 'random'")
        kind = data.get("bot") or cfg().ui.bot_player
        if kind not in PLAYER_KINDS:
            return err(f"bot must be one of {', '.join(PLAYER_KINDS)}")
        checkpoint = data["checkpoint"] if "checkpoint" in data else cfg().ui.bot_checkpoint
        if not _checkpoint_spec_ok(checkpoint):
            return err("invalid checkpoint name")
        checkpoint = checkpoint or None
        with lock:
            try:
                player, used, used_ckpt, warning = _make_bot(cfg(), kind, checkpoint)
            except Exception as exc:
                return err(f"could not create bot: {exc}", 500)
            game = GameSession(chess.WHITE if color == "white" else chess.BLACK, used, used_ckpt,
                               player, getattr(player, "name", used), warning)
            state["game"] = game
            if game.human_color == chess.BLACK:
                try:
                    bot_play(game)
                except Exception as exc:
                    return err(f"bot failed to move: {exc}", 500, state=game.state())
            return jsonify(game.state())

    @app.get("/api/game/state")
    def game_state():
        with lock:
            game = state["game"]
            if game is None:
                b = chess.Board()
                return jsonify({
                    "fen": b.fen(), "legal_moves": [], "turn": "white", "game_over": False,
                    "result": None, "termination": None, "history": [], "history_san": [],
                    "last_move": None, "last_bot_move": None, "check": False,
                    "human_color": None, "bot": None, "bot_name": None, "checkpoint": None,
                    "warning": None, "no_game": True,
                })
            return jsonify(game.state())

    @app.post("/api/game/move")
    def game_move():
        data = body()
        uci = data.get("uci")
        if not isinstance(uci, str):
            return err("missing 'uci' move")
        with lock:
            game = state["game"]
            if game is None:
                return err("no game in progress; start a new game first")
            board = game.board
            if board.is_game_over(claim_draw=True):
                return err("game is over", state=game.state())
            if board.turn != game.human_color:
                return err("it is not your turn", state=game.state())
            try:
                move = chess.Move.from_uci(uci.strip())
            except (ValueError, chess.InvalidMoveError):
                return err(f"malformed UCI move: {uci!r}")
            if move not in board.legal_moves:
                promo = chess.Move(move.from_square, move.to_square, promotion=chess.QUEEN)
                if move.promotion is None and promo in board.legal_moves:
                    return err("promotion piece required (append q, r, b or n)")
                return err(f"illegal move: {uci}")
            game.push(move)
            game.last_bot_move = None
            try:
                bot_play(game)
            except Exception as exc:
                return err(f"bot failed to move: {exc}", 500, state=game.state())
            return jsonify(game.state())

    @app.post("/api/game/bot_move")
    def game_bot_move():
        with lock:
            game = state["game"]
            if game is None:
                return err("no game in progress; start a new game first")
            if game.board.is_game_over(claim_draw=True):
                return err("game is over", state=game.state())
            if game.board.turn == game.human_color:
                return err("it is not the bot's turn", state=game.state())
            try:
                bot_play(game)
            except Exception as exc:
                return err(f"bot failed to move: {exc}", 500, state=game.state())
            return jsonify(game.state())

    # ----------------------------------------------------------- metrics
    @app.get("/api/metrics")
    def metrics():
        records = read_metrics(cfg().metrics_path)
        rtype = request.args.get("type")
        if rtype:
            records = [r for r in records if r.get("type") == rtype]
        return jsonify(records)

    # ----------------------------------------------------------- config
    @app.get("/api/config")
    def config_get():
        return jsonify(config_to_dict(cfg()))

    @app.get("/api/config/schema")
    def config_schema_get():
        return jsonify({"sections": config_schema(cfg()), "config_path": config_path})

    @app.post("/api/config")
    def config_post():
        data = request.get_json(silent=True)
        if not isinstance(data, dict):
            return jsonify({"errors": ["request body must be a JSON object"]}), 400
        merged = _deep_merge(config_to_dict(cfg()), data)
        try:
            new = config_from_dict(merged)
        except ConfigError as exc:
            return jsonify({"errors": exc.errors}), 400
        with lock:
            apply_config(new)
            try:
                saved = persist_config()
            except OSError as exc:
                return jsonify({"errors": [f"could not save {config_path}: {exc}"],
                                "config": config_to_dict(cfg())}), 500
        return jsonify({"ok": True, "saved": saved, "path": config_path,
                        "config": config_to_dict(cfg())})

    # ----------------------------------------------------------- models
    def models_payload() -> dict:
        c = cfg()
        items = _list_checkpoints(c.checkpoint_dir)
        sel = c.ui.bot_checkpoint
        for it in items:
            it["selected"] = sel is not None and it["name"] in (sel, f"{sel}.pt")
        return {"checkpoint_dir": str(c.checkpoint_dir), "selected": sel, "models": items}

    @app.get("/api/models")
    def models_list():
        return jsonify(models_payload())

    @app.post("/api/models/select")
    def models_select():
        data = body()
        if "name" not in data:
            return err("missing 'name'")
        name = data.get("name")
        if name in (None, ""):
            name = None
        elif name != "latest":
            path = safe_checkpoint_path(cfg().checkpoint_dir, name)
            if path is None:
                return err("invalid checkpoint name")
            if not path.is_file():
                return err(f"checkpoint not found: {name}", 404)
        with lock:
            cfg().ui.bot_checkpoint = name
            try:
                saved = persist_config()
            except OSError as exc:
                return err(f"could not save config: {exc}", 500)
        payload = models_payload()
        payload["saved"] = saved
        return jsonify(payload)

    @app.delete("/api/models/<name>")
    def models_delete(name: str):
        path = safe_checkpoint_path(cfg().checkpoint_dir, name)
        if path is None or path.suffix != ".pt":
            return err("invalid checkpoint name")
        if not path.is_file():
            return err(f"checkpoint not found: {name}", 404)
        if training.running:
            return err("cannot delete checkpoints while training is running", 409)
        path.unlink()
        with lock:
            if cfg().ui.bot_checkpoint == name:
                cfg().ui.bot_checkpoint = "latest"
                try:
                    persist_config()
                except OSError:
                    pass
        payload = models_payload()
        payload["deleted"] = name
        return jsonify(payload)

    # ----------------------------------------------------------- training
    @app.post("/api/training/start")
    def training_start():
        data = body()
        iterations = data.get("iterations", cfg().training.iterations)
        if isinstance(iterations, str) and iterations.strip().isdigit():
            iterations = int(iterations)
        if not isinstance(iterations, int) or isinstance(iterations, bool) or iterations < 1:
            return err("iterations must be a positive integer")
        if not training.start(cfg(), iterations):
            return err("training is already running", 409, status=training.status())
        return jsonify(training.status())

    @app.post("/api/training/stop")
    def training_stop():
        stopped = training.stop()
        st = training.status()
        st["stop_sent"] = stopped
        return jsonify(st)

    @app.get("/api/training/status")
    def training_status():
        try:
            n = int(request.args.get("log", 100))
        except ValueError:
            n = 100
        return jsonify(training.status(max(0, min(n, 300))))

    return app
