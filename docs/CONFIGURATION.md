# Configuration reference

All settings live in one YAML file, loaded by `chessbot.config.load_config`
and validated by `config_from_dict` / `validate_config`. Any key may be
omitted; missing keys take the built-in default (the values in
`configs/default.yaml`). Unknown sections or keys, wrong types and
out-of-range values are rejected, and **every** problem is reported at once
(`ConfigError.errors`).

```bash
python -m chessbot --config configs/quick.yaml train      # any command takes --config
python -m chessbot --config my.yaml serve                 # settings page saves back to my.yaml
```

Shipped files:

| File | Purpose |
|---|---|
| `configs/default.yaml` | Every setting at its default value, with comments. Start here when writing your own. |
| `configs/quick.yaml` | Tiny network (32 channels, 2 blocks), 32 simulations, 4 games/iteration, 50 steps. A CPU demo that shows the pipeline and the loss curves moving within minutes; it will not produce a strong player. Writes to `runs/quick`. |
| `configs/strong.yaml` | 128x10 network, 400 simulations, 64 games/iteration, 1000 steps of batch 256. For long runs (a CUDA GPU strongly recommended). Writes to `runs/strong`. |

Types: `int` accepts whole numbers (`32.0` is accepted as 32; `true`/`false`
are **not** integers). `float` accepts any number. `X or null` means YAML
`null` (in the web settings form: leave the field blank).

Changing `model.*` and then resuming in the same `run_dir` fails with a
clear error (the stored checkpoint has a different architecture): use a new
`run_dir` for a new architecture.

---

## Top level

### `run_dir`
* **Type / default:** string, `runs/default`. Must not be empty.
* **Effect:** root directory of one training run. The trainer writes
  `<run_dir>/checkpoints/iter_XXXXXX.pt` and `latest.pt`,
  `<run_dir>/replay_buffer.npz` and `<run_dir>/metrics.jsonl`
  (`Config.checkpoint_dir`, `buffer_path`, `metrics_path`). Training
  resumes from whatever is in this directory; the web dashboard, the
  models page and the bots' `latest` checkpoint all read from it. Use a
  separate directory per experiment / architecture.

---

## `model` — policy/value network (`chessbot.ml.model.PolicyValueNet`)

An AlphaZero-style residual CNN: input convolution, `res_blocks` residual
blocks of `channels` filters, a policy head (logits over every move
encoding) and a value head (`tanh` output in [-1, 1]). Changing any
`model.*` value changes the architecture, so existing checkpoints in the
same `run_dir` can no longer be resumed (they can still be loaded for play,
because checkpoints store their own model config).

### `model.channels`
* **Type / default / range:** int, `64`, [1, 512].
* **Effect:** filters per convolution in the residual tower. Compute and
  parameter count grow roughly with `channels²`. More channels = more
  capacity to learn patterns (stronger after enough training), but slower
  self-play (every MCTS simulation is a forward pass), slower training and
  more memory. 32 is a CPU demo size; 128–256 is typical for serious runs
  on a GPU.

### `model.res_blocks`
* **Type / default / range:** int, `4`, [0, 40].
* **Effect:** depth of the residual tower. Compute grows linearly with the
  number of blocks. Deeper networks see longer-range interactions across
  the board and learn more, at the cost of speed. `0` leaves only the input
  convolution and heads (very weak, useful for tests).

### `model.value_hidden`
* **Type / default / range:** int, `128`, [1, 2048].
* **Effect:** hidden units in the value head's fully connected layer.
  Small effect on speed; larger values give the value estimate more
  capacity. Rarely needs changing.

---

## `search` — classical alpha-beta engine (`chessbot.engine.search.AlphaBetaSearcher`)

Used by the `alphabeta` and `alphabeta_nn` players (play page, CLI `play`,
evaluation opponent `alphabeta`). Not used by MCTS self-play.

### `search.depth`
* **Type / default / range:** int, `3`, [1, 64] (the engine also caps it
  internally at its maximum ply).
* **Effect:** maximum iterative-deepening depth in plies. Each extra ply
  multiplies the work by the effective branching factor (roughly 4–8x with
  good move ordering), so depth is the main strength/speed knob: depth 2
  responds instantly but blunders tactics, depth 3–4 is a reasonable
  casual opponent in Python, 5+ gets slow without `time_limit`.

### `search.time_limit`
* **Type / default / range:** float or null, `null`; when set must be > 0
  (seconds).
* **Effect:** per-move time budget. Iterative deepening keeps going until
  `depth` is reached, the deadline expires (an unfinished iteration is
  abandoned and the best move of the last completed depth is played), or
  more than half the budget has been used after a completed depth (the
  next depth would not finish anyway). Depth 1 always completes, so a move
  is always returned. `null` = search to `depth` regardless of time. Set it
  with a high `depth` to get "as deep as possible in N seconds".

### `search.quiescence`
* **Type / default:** bool, `true`.
* **Effect:** at depth-0 leaves, keep searching captures and promotions
  (with a stand-pat score) instead of evaluating a position in the middle
  of an exchange. Turning it off makes search faster but much weaker
  tactically (horizon effect: it will happily "win" a piece that is
  immediately recaptured).

### `search.quiescence_depth`
* **Type / default / range:** int, `6`, [0, 32].
* **Effect:** maximum extra plies of captures/promotions in quiescence.
  Higher = more accurate in sharp positions but can explode node counts in
  positions with many captures; `0` behaves like quiescence off.

### `search.tt_size`
* **Type / default / range:** int, `200000`, >= 0.
* **Effect:** maximum transposition-table entries (oldest entries are
  evicted first). The TT stores results of already-searched positions and
  the best move, which speeds up iterative deepening and move ordering.
  Bigger = fewer re-searches but more RAM (Python objects: on the order of
  ~100–200 bytes per entry). `0` disables the table (slower, mainly for
  debugging).

---

## `mcts` — Monte-Carlo tree search (`chessbot.ml.mcts.MCTS`)

PUCT search guided by the network. Used for self-play (training data) and by
the `mcts` player (play page, evaluation). When playing (not self-play) the
MCTS player always picks the most-visited move and never adds noise; the
noise and temperature settings below only affect self-play.

### `mcts.simulations`
* **Type / default / range:** int, `64`, >= 1.
* **Effect:** simulations (network evaluations) per move. The most
  important strength/speed knob for the MCTS bot and for the quality of
  training targets: more simulations = a stronger player and better
  policy targets (visit distributions), but self-play and play time grow
  linearly. 32 is a fast demo, 100–800 is typical for real training.
  `evaluation.simulations` can override it for evaluation matches.

### `mcts.c_puct`
* **Type / default / range:** float, `1.5`, > 0.
* **Effect:** exploration constant in PUCT
  `Q + c_puct · P · sqrt(N_parent) / (1 + N_child)`. Higher = trust the
  network prior more and spread visits over more moves (broader, shallower
  search); lower = concentrate visits on the moves with the best value so
  far (narrower, deeper; can miss moves the prior underrates). 1–2.5 is the
  usual range.

### `mcts.dirichlet_alpha`
* **Type / default / range:** float, `0.3`, > 0.
* **Effect:** concentration of the Dirichlet noise mixed into the root
  priors **during self-play only**. Smaller values make the noise spiky
  (a few random moves get a large boost); larger values spread it evenly.
  AlphaZero used 0.3 for chess (≈ 10 / typical number of legal moves).

### `mcts.dirichlet_epsilon`
* **Type / default / range:** float, `0.25`, [0, 1].
* **Effect:** weight of the root noise: `P = (1-ε)·P + ε·noise`
  (self-play only). Higher = more exploration of new moves in self-play
  (more diverse data, but noisier games); `0` disables noise, which risks
  the network only ever reinforcing its current opening choices.

### `mcts.temperature`
* **Type / default / range:** float, `1.0`, >= 0.
* **Effect:** during the first `temperature_moves` plies of each self-play
  game, moves are sampled with probability proportional to
  `visits^(1/temperature)`. `1.0` = proportional to visit counts (diverse
  openings); values towards `0` approach always playing the most visited
  move; `> 1` flattens further (more random). Does not affect the play
  page (which is greedy).

### `mcts.temperature_moves`
* **Type / default / range:** int, `20`, >= 0.
* **Effect:** number of plies sampled with `temperature` in self-play;
  afterwards the most-visited move is played. More = more varied games and
  positions in the replay buffer; fewer = games closer to best play but
  more repetitive data. `0` = always greedy (no opening diversity except
  noise).

### `mcts.leaf_batch_size`
* **Type / default / range:** int or null, `null`, [1, 256].
* **Effect:** how many leaves MCTS collects (using virtual loss) before one
  batched network call. `null` = automatic: `simulations // 8` clamped to
  [1, 8] in self-play, and `1` (plain sequential AlphaZero search) for the
  MCTS bot on the play page / in evaluation. Larger values make self-play
  2-3x faster on CPU (measured 19.8 s -> 7.2 s per game for the default
  network at 64 simulations) because the network runs on batches, but the
  virtual loss slightly distorts the search, so very large values relative
  to `simulations` weaken play.

---

## `selfplay` (`chessbot.ml.selfplay.play_selfplay_game`, `Trainer.run_iteration`)

### `selfplay.games_per_iteration`
* **Type / default / range:** int, `8`, >= 1.
* **Effect:** self-play games generated per training iteration before the
  gradient steps. More games = more (and more diverse) fresh positions per
  iteration and less overfitting to a few games, but each iteration takes
  proportionally longer. Each game adds one sample per ply to the replay
  buffer. The dashboard stop button is honoured between games.

### `selfplay.max_moves`
* **Type / default / range:** int, `200`, >= 1.
* **Effect:** plies after which a self-play game is stopped and scored as
  a draw (termination `max_moves`). Untrained networks shuffle pieces for
  a long time, so this bounds the cost of a game. Lower = faster
  iterations but more artificial draws (value targets of 0) and fewer
  endgames learnt; higher = more decisive results, slower. Checkmate,
  stalemate, insufficient material, threefold repetition and the 50-move
  rule end games earlier.

---

## `training` (`chessbot.ml.trainer.Trainer`)

Each iteration: self-play → add samples to the replay buffer → (if the
buffer is large enough) `steps_per_iteration` gradient steps on uniform
random mini-batches → save checkpoint(s) and the buffer → append one record
to `metrics.jsonl`. Loss = `cross_entropy(policy, MCTS visits) +
value_loss_weight · MSE(value, game outcome)`, plus weight decay in the
optimizer.

### `training.iterations`
* **Type / default / range:** int, `10`, >= 1.
* **Effect:** number of iterations run by `chessbot train` (unless
  overridden on the command line) and the default value of the "Iterations"
  box on the dashboard. Training always resumes from `latest.pt`, so
  running 10 iterations twice equals running 20.

### `training.steps_per_iteration`
* **Type / default / range:** int, `100`, >= 0.
* **Effect:** gradient steps per iteration. More steps = the network fits
  the buffer more per iteration (faster learning per self-play game) but
  costs time and, if too high relative to `games_per_iteration`, overfits
  to stale data. A common rule of thumb is that each sample should be
  seen a few times: `steps · batch_size` ≈ 1–4 × new positions per
  iteration. `0` = only generate self-play data (loss values in the metrics
  are then null).

### `training.batch_size`
* **Type / default / range:** int, `128`, >= 1.
* **Effect:** samples per gradient step (sampled uniformly with replacement
  from the buffer). Larger batches give smoother, more stable gradients and
  better GPU utilisation but use more memory and time per step.

### `training.learning_rate`
* **Type / default / range:** float, `0.001`, (0, 10].
* **Effect:** optimizer step size. Too high = unstable / diverging loss,
  too low = very slow learning. Adam is usually fine at 1e-3 … 1e-4; SGD
  typically needs larger values (e.g. 0.01–0.2). On resume the current value
  replaces the one stored in the optimizer state, so it can be changed
  between runs.

### `training.weight_decay`
* **Type / default / range:** float, `0.0001`, >= 0.
* **Effect:** L2 regularisation passed to the optimizer. Higher reduces
  overfitting to the replay buffer but can underfit; `0` disables it.
  Also re-applied from the current config on resume.

### `training.optimizer`
* **Type / default / choices:** string, `adam`, one of `adam`, `sgd`.
* **Effect:** `adam` adapts per-parameter step sizes and is the robust
  default for small runs. `sgd` uses momentum (Nesterov when momentum >
  0), as in AlphaZero, and may generalise slightly better on long runs
  but needs learning-rate tuning. Switching optimizer on resume starts a
  fresh optimizer state (logged).

### `training.momentum`
* **Type / default / range:** float, `0.9`, [0, 1).
* **Effect:** SGD momentum; ignored when `optimizer` is `adam`. Higher
  smooths updates over more steps.

### `training.value_loss_weight`
* **Type / default / range:** float, `1.0`, >= 0.
* **Effect:** weight of the value MSE relative to the policy cross-entropy
  in the total loss. Raise it if the value head lags (value loss stays
  high); lower it if the value head overfits game outcomes (AlphaZero-style
  setups sometimes use 0.25–0.5 to reduce value overfitting on small data).
  `0` trains only the policy.

### `training.replay_buffer_size`
* **Type / default / range:** int, `50000`, >= 1.
* **Effect:** maximum positions stored; when full, the oldest are dropped
  (FIFO). Larger = more diverse training data and less overfitting, but
  older data was produced by weaker networks, and memory/disk use grows
  (each sample stores the encoded board and a full policy vector). The
  buffer is saved to `replay_buffer.npz` every iteration for resumable
  training.

### `training.min_buffer_size`
* **Type / default / range:** int, `256`, >= 1 and <= `replay_buffer_size`.
* **Effect:** gradient steps are skipped (logged, losses null in metrics)
  until the buffer holds at least this many positions. Prevents
  overfitting to the first one or two games. Higher = more initial
  self-play before learning starts.

### `training.checkpoint_every`
* **Type / default / range:** int, `1`, >= 1.
* **Effect:** an `iter_XXXXXX.pt` checkpoint is kept every N iterations;
  `latest.pt` (used for resuming and as the default bot) is updated after
  every iteration regardless. Higher = fewer historical checkpoints on disk
  (and on the models page).

### `training.keep_last`
* **Type / default / range:** int, `10`, >= 0.
* **Effect:** after each save, the oldest `iter_*.pt` files beyond this
  count are deleted (`latest.pt` is never deleted). `0` keeps every
  checkpoint (disk use grows without bound). Keep enough history if you
  want to evaluate against older checkpoints.

### `training.device`
* **Type / default / choices:** string, `auto`, one of `auto`, `cpu`, `cuda`.
* **Effect:** torch device for self-play inference and training. `auto`
  uses CUDA if available, else CPU. A GPU speeds up both self-play
  (network evaluations) and gradient steps by a large factor for anything
  but the smallest networks. (Bots on the play page always run on CPU.)

### `training.seed`
* **Type / default:** int or null, `null`.
* **Effect:** seeds Python, NumPy and torch RNGs (and per-iteration
  derived seeds for self-play and the replay buffer) for reproducible
  runs. `null` = nondeterministic. Full bit-for-bit reproducibility is
  only expected on CPU.

---

## `evaluation` (`chessbot evaluate`, `chessbot train --eval-every N`)

Plays the candidate checkpoint (an MCTS player) against an opponent and
appends a record with `type: "eval"` (wins/draws/losses, score, Elo
estimate with 95% interval) to `metrics.jsonl`; the dashboard plots the
score and Elo per checkpoint iteration.

### `evaluation.games`
* **Type / default / range:** int, `20`, >= 1.
* **Effect:** games per evaluation match (colours alternate). More games =
  a tighter Elo confidence interval (it shrinks roughly with
  `1/sqrt(games)`), but evaluation takes proportionally longer.

### `evaluation.opponent`
* **Type / default / choices:** string, `random`, one of `random`,
  `alphabeta`, `checkpoint`.
* **Effect:** who the candidate plays. `random` is a sanity check that
  learning happened at all (an early network should quickly beat it);
  `alphabeta` (using the `search.*` settings) is a fixed, meaningful
  baseline; `checkpoint` plays against `baseline_checkpoint`, to measure
  progress between iterations.

### `evaluation.baseline_checkpoint`
* **Type / default:** string or null, `null`. Required (non-empty) when
  `opponent` is `checkpoint`.
* **Effect:** checkpoint used as the opponent: `latest`, a file name in
  `<run_dir>/checkpoints/` (e.g. `iter_000010.pt`) or a path. Ignored for
  other opponents.

### `evaluation.max_moves`
* **Type / default / range:** int, `300`, >= 1.
* **Effect:** plies after which an evaluation game is stopped and scored a
  draw. Lower = faster evaluation but more draws (less informative
  results).

### `evaluation.simulations`
* **Type / default / range:** int or null, `null`; when set >= 1.
* **Effect:** MCTS simulations per move for the evaluated player(s);
  `null` uses `mcts.simulations`. Use a lower value for quick checks or a
  higher one to measure strength with more search.

---

## `ui` — web interface (`chessbot serve`, `chessbot.web.app`)

### `ui.host`
* **Type / default:** string, `127.0.0.1`. Must not be empty.
* **Effect:** interface the web server binds to. `127.0.0.1` = only this
  machine. `0.0.0.0` exposes it to the network — the app has no
  authentication and can start training or delete checkpoints, so only do
  this on a trusted network. Read at server start (changes need a restart).

### `ui.port`
* **Type / default / range:** int, `5000`, [1, 65535].
* **Effect:** TCP port of the web server. Read at server start.

### `ui.bot_player`
* **Type / default / choices:** string, `mcts`, one of `mcts`, `alphabeta`,
  `alphabeta_nn`, `random`.
* **Effect:** default bot type preselected on the play page (and used by
  CLI `play` without `--bot`). `mcts` = network + tree search (strength
  depends on training and `mcts.simulations`); `alphabeta` = classical
  engine (`search.*`); `alphabeta_nn` = alpha-beta using the network value
  as evaluation and its policy for move ordering; `random` = random legal
  moves.

### `ui.bot_checkpoint`
* **Type / default:** string or null, `latest`.
* **Effect:** checkpoint loaded by network-based bots: `latest`, a file
  name in `<run_dir>/checkpoints/`, or `null` for a freshly initialised
  (untrained) network. Set from the models page ("Use for bot"). If the
  checkpoint does not exist when a game starts, the web UI falls back
  gracefully and shows a warning: `mcts` uses an untrained network and
  `alphabeta_nn` falls back to classical `alphabeta`.

---

## Web settings page

`/settings` renders a form for every key above (grouped by section, with a
short description from `chessbot.config.FIELD_HELP`). On save the whole form
is sent to `POST /api/config`, validated with `config_from_dict`, and either
rejected with every error shown next to its field (nothing is applied), or
applied immediately to the running server and — if the server was started
with `--config FILE` — written to that file. Notes:

* Saving rewrites the YAML file without its comments (plain
  `yaml.safe_dump`). Keep a copy of a hand-commented file if needed.
* A training run that is already in progress keeps the configuration it
  started with; the new values apply to the next run.
* `ui.host` / `ui.port` take effect on the next server start.
