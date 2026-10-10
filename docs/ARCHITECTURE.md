# Architecture & Shared Interfaces (authoritative contract)

Owner: **Lead Architect**. Agents must implement exactly these signatures. If a
contract change is genuinely required, report it to the lead instead of
silently changing it.

## Overview

```
                +-------------------+
 configs/*.yaml | chessbot.config   |  dataclass Config, YAML load/save/validate
                +---------+---------+
                          |
      +-------------------+--------------------+
      |                   |                    |
+-----v------+     +------v-------+     +------v------+
| engine/    |     | ml/          |     | web/        |
| evaluation |<----| neural_eval  |     | Flask app   |
| search     |     | encoding     |     | board, dash |
| (alpha-beta)|    | model, mcts  |     | settings,   |
+-----+------+     | selfplay     |     | models      |
      |            | replay_buffer|     +------+------+
      |            | trainer      |            |
      |            | checkpoints  |            |
      |            +------+-------+            |
      +-------+-----------+--------------------+
              |
       +------v------+      +-------------+
       | players.py  |----->| arena.py    |  match play, Elo estimate (QA)
       +------+------+      +-------------+
              |
       +------v------+
       | cli.py      |  python -m chessbot {train,play,serve,evaluate,bench}
       +-------------+
```

Learning algorithm: **AlphaZero-style self-play**. A PyTorch policy/value
network guides PUCT Monte-Carlo Tree Search. Self-play games produce
`(state, MCTS visit distribution, final outcome)` samples stored in a replay
buffer; the network is optimised with real gradient descent on
`cross_entropy(policy, visits) + value_loss_weight * MSE(value, outcome)
+ weight_decay (via optimizer)`. The classical alpha-beta engine is used as a
baseline opponent and as an alternative play mode (optionally using the
network value as evaluation).

## File ownership

| Owner | Files |
|---|---|
| Lead | `docs/ARCHITECTURE.md`, `chessbot/__init__.py`, `chessbot/__main__.py`, `chessbot/players.py`, `chessbot/cli.py`, `pyproject.toml`, `requirements.txt`, `README.md`, `tests/conftest.py`, `tests/test_players.py`, `tests/test_cli.py` |
| Chess Engine | `chessbot/engine/*`, `tests/test_engine_*.py` |
| Machine Learning | `chessbot/ml/*`, `tests/test_ml_*.py` |
| Frontend & Config | `chessbot/config.py` (lead wrote initial schema; agent may add validation/doc but must not rename/remove fields), `chessbot/web/*`, `configs/*.yaml`, `docs/CONFIGURATION.md`, `tests/test_config*.py`, `tests/test_web*.py` |
| QA & Performance | `chessbot/arena.py`, `scripts/benchmark.py`, `scripts/evaluate_checkpoints.py`, `tests/test_qa_*.py`, `docs/QA_REPORT.md` |

Agents never run `git commit`, `git checkout`, `git reset`, `git stash` or
other state-changing git commands; the lead commits.

## Conventions

* Scores from the engine are **centipawns from the side-to-move's
  perspective** (negamax convention). Mate scores: `MATE_SCORE - ply`
  (`MATE_SCORE = 100_000`).
* Network value is in `[-1, 1]` from the **side-to-move's perspective**.
* Game results: `1.0` white wins, `0.0` black wins, `0.5` draw.
* All randomness accepts an optional `seed` / `random.Random` / numpy
  `Generator` for reproducible tests.
* Device selection: `"auto"` -> cuda if available else cpu.

## chessbot.config (exists — see file)

```python
load_config(path: str | Path | None = None) -> Config     # None -> defaults
save_config(cfg: Config, path) -> None
config_from_dict(d: dict) -> Config     # raises ConfigError listing every problem
config_to_dict(cfg: Config) -> dict
validate_config(cfg: Config) -> list[str]   # empty list == valid
class ConfigError(ValueError): errors: list[str]
```
Sections: `model`, `search`, `mcts`, `selfplay`, `training`, `evaluation`,
`ui`, plus top-level `run_dir`.

## chessbot.engine (Chess Engine agent)

```python
# chessbot/engine/evaluation.py
MATE_SCORE = 100_000
class Evaluator(Protocol):
    def evaluate(self, board: chess.Board) -> float: ...   # cp, side to move
class ClassicalEvaluator:            # material + piece-square tables (+ simple extras)
    def evaluate(self, board) -> float

# chessbot/engine/search.py
@dataclass
class SearchResult:
    best_move: chess.Move | None
    score: float            # cp, side-to-move perspective
    depth: int              # deepest fully completed iteration
    nodes: int
    pv: list[chess.Move]
    time_seconds: float

class AlphaBetaSearcher:
    def __init__(self, evaluator: Evaluator | None = None,
                 config: SearchConfig | None = None,
                 move_prior: Callable[[chess.Board, list[chess.Move]], list[float]] | None = None): ...
    def search(self, board: chess.Board, depth: int | None = None,
               time_limit: float | None = None) -> SearchResult: ...
    def clear(self) -> None   # reset TT / killers / history
```
Requirements: iterative deepening, alpha-beta (negamax / PVS), transposition
table (bounded by `search.tt_size`), move ordering (TT move, MVV-LVA,
killers, history, optional `move_prior`), quiescence search (captures +
promotions, bounded by `search.quiescence_depth`), checkmate/stalemate/draw
detection (repetition, 50-move, insufficient material), time limit honoured.
`search()` must not mutate the caller's board (copy or push/pop restore).
Never returns an illegal move; returns `None` only if no legal moves.

## chessbot.ml (Machine Learning agent)

```python
# chessbot/ml/encoding.py
NUM_PLANES: int
POLICY_SIZE: int
def encode_board(board: chess.Board) -> np.ndarray      # float32 (NUM_PLANES, 8, 8), side-to-move perspective
def move_to_index(move: chess.Move, board: chess.Board) -> int
def index_to_move(index: int, board: chess.Board) -> chess.Move
def legal_move_mask(board: chess.Board) -> np.ndarray  # bool (POLICY_SIZE,)
# move_to_index/index_to_move must be bijective over legal moves of any position.

# chessbot/ml/model.py
class PolicyValueNet(nn.Module):
    def __init__(self, config: ModelConfig): ...
    def forward(self, x: Tensor) -> tuple[Tensor, Tensor]  # logits (B, POLICY_SIZE), value (B, 1) in [-1,1]
    @torch.no_grad()
    def predict(self, board: chess.Board) -> tuple[dict[chess.Move, float], float]  # legal-move priors, value
def select_device(name: str) -> torch.device

# chessbot/ml/mcts.py
class MCTS:
    def __init__(self, model: PolicyValueNet, config: MCTSConfig, device=None, rng=None): ...
    def run(self, board: chess.Board, add_noise: bool = False) -> dict[chess.Move, int]  # visit counts
    def select_move(self, board, temperature: float = 0.0, add_noise=False) -> tuple[chess.Move, dict[chess.Move, float]]  # move, visit distribution

# chessbot/ml/replay_buffer.py
class ReplayBuffer:
    def __init__(self, capacity: int, seed: int | None = None)
    def add(self, state: np.ndarray, policy: np.ndarray, value: float) -> None
    def add_game(self, samples: list[tuple[np.ndarray, np.ndarray, float]]) -> None
    def sample(self, batch_size: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]
    def __len__(self) -> int
    def save(self, path) -> None
    @classmethod
    def load(cls, path, capacity: int | None = None) -> "ReplayBuffer"

# chessbot/ml/selfplay.py
@dataclass
class GameRecord:
    samples: list[tuple[np.ndarray, np.ndarray, float]]  # value already from side-to-move perspective
    moves: list[str]           # UCI
    result: float              # 1.0 / 0.5 / 0.0 (white perspective)
    termination: str           # "checkmate", "stalemate", "max_moves", "repetition", ...
def play_selfplay_game(model, mcts_config: MCTSConfig, selfplay_config: SelfPlayConfig,
                       device=None, rng=None) -> GameRecord

# chessbot/ml/checkpoints.py
@dataclass
class CheckpointInfo:
    path: Path; iteration: int; global_step: int; timestamp: str; size_bytes: int
def save_checkpoint(path, model, optimizer, iteration: int, global_step: int,
                    config: Config, extra: dict | None = None) -> Path
def load_checkpoint(path, map_location="cpu") -> dict
def load_model(path, device="cpu") -> PolicyValueNet   # rebuilt from stored model config
def list_checkpoints(checkpoint_dir) -> list[CheckpointInfo]   # sorted by iteration
def resolve_checkpoint(spec: str | None, checkpoint_dir) -> Path | None  # "latest", filename, or path

# chessbot/ml/trainer.py
class Trainer:
    def __init__(self, config: Config, resume: bool = True,
                 log_fn: Callable[[str], None] | None = None): ...
    iteration: int; global_step: int; model; optimizer; buffer
    def run_iteration(self) -> dict      # self-play -> train -> checkpoint; returns metrics dict
    def run(self, iterations: int | None = None,
            stop_event: threading.Event | None = None,
            on_iteration_end: Callable[[dict], None] | None = None) -> list[dict]
    def train_steps(self, steps: int) -> dict    # policy_loss, value_loss, total_loss (means)
```
Checkpoint dir: `<run_dir>/checkpoints/iter_000005.pt` plus `latest.pt`.
Replay buffer persisted at `<run_dir>/replay_buffer.npz` for resumable
training. Metrics appended as JSON lines to `<run_dir>/metrics.jsonl`, one
object per iteration with keys:
`type="train", iteration, global_step, timestamp, games, positions,
avg_game_length, white_wins, black_wins, draws, policy_loss, value_loss,
total_loss, learning_rate, buffer_size, selfplay_seconds, train_seconds,
checkpoint`. **Only measured values** — never placeholders.

```python
# chessbot/ml/neural_eval.py
class NeuralEvaluator:   # satisfies engine Evaluator
    def __init__(self, model, device="cpu", scale_cp: float = 600.0)
    def evaluate(self, board) -> float   # value * scale_cp (side to move)
    def move_prior(self, board, moves) -> list[float]  # usable as AlphaBetaSearcher.move_prior
```

## chessbot.players (Lead)

```python
class Player(Protocol):
    name: str
    def select_move(self, board: chess.Board) -> chess.Move
    def reset(self) -> None
RandomPlayer(seed=None); AlphaBetaPlayer(config: Config, checkpoint=None);
MCTSPlayer(config: Config, checkpoint: str|Path|None, temperature=0.0)
make_player(kind: str, config: Config, checkpoint: str | None = None, seed=None) -> Player
# kind in {"random", "alphabeta", "alphabeta_nn", "mcts"}
```

## chessbot.arena (QA agent)

```python
@dataclass
class MatchResult:
    wins: int; losses: int; draws: int        # from player A's perspective
    games: list[dict]   # {"white": name, "black": name, "result": "1-0", "moves": [...], "termination": str}
    @property score -> float               # (wins + 0.5*draws)/n
    def elo_diff(self) -> tuple[float, float, float]  # estimate, low, high (95%)
def play_game(white: Player, black: Player, max_moves: int = 300,
              start_fen: str | None = None) -> dict
def play_match(a: Player, b: Player, games: int, max_moves: int = 300,
               openings: list[str] | None = None, alternate_colors=True) -> MatchResult
```

## chessbot.web (Frontend agent)

`create_app(config: Config, config_path: str | None = None) -> flask.Flask`.
No external CDN; vanilla HTML/CSS/JS served from `chessbot/web/static` and
`templates`. Pages: `/` play, `/dashboard`, `/settings`, `/models`.
JSON API (prefix `/api`):
* `POST /api/game/new {color, bot, checkpoint?}` -> state
* `GET /api/game/state` -> `{fen, legal_moves:[uci], turn, game_over, result, history}`
* `POST /api/game/move {uci}` -> state after human move **and** bot reply
* `POST /api/game/bot_move` -> state
* `GET /api/metrics` -> list of metrics.jsonl records
* `GET/POST /api/config` -> config dict / validate+save (400 with `errors` list)
* `GET /api/models`, `POST /api/models/select {name}`, `DELETE /api/models/<name>`
* `POST /api/training/start {iterations}`, `POST /api/training/stop`, `GET /api/training/status`
Training runs in a background thread using `Trainer.run(stop_event=...)`.
