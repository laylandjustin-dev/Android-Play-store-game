# QA Report

Owner: QA & Performance agent. Phase 2 (pre-integration). Everything below
was actually run on the date shown; anything not run is marked
**pending integration**.

Environment: Python 3.13.16, python-chess 1.11.2, torch 2.14.1 (CPU), numpy
2.5.3, pytest 9.1.1, Linux x86_64, 4 vCPUs (shared with other agents' jobs;
see "Performance measurements" for why that matters). Date: 2026-10-10.

## Test inventory

| File | What it checks | Status (phase 2 run) |
|---|---|---|
| `tests/test_qa_arena.py` (24) | referee legality checks (illegal / pseudo-legal / null / non-Move returns raise `IllegalMoveError` naming the player), player gets a copy (vandal player can't corrupt game), checkmate (fool's mate), stalemate, insufficient material, threefold & fifty-move claimed only once they have occurred, mate on the 100th halfmove beats the fifty-move claim, `max_moves` adjudication, colour alternation, `reset()` per game, openings cycled in colour pairs, `DEFAULT_OPENINGS` valid/balanced, Elo maths (50% -> 0, 75% -> +190.85, CI shrinks with n, finite at 0%/100%, empty match), `is_improvement` | 24 passed |
| `tests/test_qa_legality.py` (20) | python-chess perft vs published values (start d1-3, Kiwipete d1-2, CPW pos. 3 d3, pos. 4 d2); special positions (castling incl. through-check, en passant incl. pinned e.p., promotion, underpromotion, in-check, single-move); random/alphabeta/mcts players legal on 25-50 random positions + specials and never mutate the input board; alpha-beta: PV legal, `None` on mate/stalemate, finds mate-in-1, promotes when needed; MCTS visit/dist keys legal and sum to 1; encoding bijective over legal moves incl. underpromotion/e.p./castling; full arena games vs random | 20 passed |
| `tests/test_qa_pipeline.py` (7) | tiny Trainer: 2 iterations -> `iter_*.pt` + `latest.pt`, 2 well-formed `train` metrics records with finite losses and `total = policy + w*value`, weights changed, `make_player("mcts", cfg, "latest")` plays a legal game, resume continues at iteration 2 with identical weights and restored buffer and then writes iteration 3, changed `model.channels` on resume raises an error mentioning the model config | 7 passed |
| `tests/test_qa_scripts.py` (6) | `benchmark.py` movegen section (perft 8902), unknown section rejected, all sections ok-or-skipped on a tiny config; `evaluate_checkpoints.py` untrained vs random logs a well-formed `eval` record, missing checkpoint exits, checkpoint vs checkpoint reports iteration | 6 passed |

Command: `python -m pytest tests/test_qa_*.py -q -rs` -> **57 passed in 25.2 s**.
No test is currently slow enough (> 20 s) to need `@pytest.mark.slow`.

Skip behaviour verified: with `chessbot.engine.*` / `chessbot.ml.*` imports
blocked, the same run gives 36 passed, 15 skipped (each skip names the missing
module), 0 errors.

## Legality verification

* python-chess move generation matches published perft counts (see above), so
  it is a valid legality oracle.
* The arena rejects any move not in `board.legal_moves` (checked with
  `board.is_legal`) and never gives a player its own board.
* Ran in phase 2 against the engine/ML code as it was then: alpha-beta
  (depth 2) and untrained MCTS (8 sims) returned only legal moves on 30 + 25
  random positions and 13 special positions, and did not mutate the input
  board (FEN, move stack, castling rights, e.p. square compared).
* Match sanity: alpha-beta depth 2 vs random, 10 games with `DEFAULT_OPENINGS`:
  +10 =0 -0, all by checkmate (11-41 plies), Elo +529 [95% CI +317, +1200]
  (the upper bound is the score clamp at 0.999, i.e. "unbounded").

## Performance measurements

Run with `python scripts/benchmark.py` (default config: 64 ch x 4 res blocks,
0.92 M params; search depth 3 + quiescence; 64 MCTS sims). Taken on a shared
4-vCPU box while other agents were running jobs.

| Measurement | Result |
|---|---|
| python-chess perft, start d4 (197,281 nodes) | 0.43 s, ~459k nodes/s |
| python-chess perft, Kiwipete d3 (97,862 nodes) | 0.19 s, ~520k nodes/s |
| alpha-beta, start pos, depth 4 | 2,544 nodes, 0.13 s (~20k nodes/s) |
| alpha-beta, Kiwipete, depth 3 / 4 | 7,069 nodes 0.34 s / 23,249 nodes 0.81 s (~21-29k nodes/s) |
| alpha-beta, middlegame / endgame depth 4 | 8,645 nodes 0.28 s / 2,375 nodes 0.06 s |
| NN forward, batch 1, 1 thread | 1.3 ms (~750 pos/s) |
| NN forward, batch 64, 1 thread | 30 ms (~2,100 pos/s) |
| NN forward, batch 1 / 64, 4 threads (machine busy) | **229 ms / 576 ms** (see below) |
| `encode_board` | ~0.02 ms/position |
| MCTS, 64 sims/move | ~0.11 s/move, ~555-590 sims/s |
| self-play game, 64 sims, max 200 plies | 74 plies (checkmate) in 3.6 s, 0.049 s/ply |
| self-play game, 16 sims, max 40 plies (quick) | 0.94 s when the box was quiet; **170.7 s** in an earlier run while another torch process was running |

**Finding: torch thread oversubscription.** With torch's default 4 intra-op
threads and one other CPU-heavy torch process on the 4 cores, small forward
passes slow down 100-200x (batch 1: 1.1 ms with 1 thread -> ~240 ms with 4
threads, reproduced 4 times in isolation). A batch-128 Adam training step
(default model) took **~2,300 ms with 4 threads vs ~165 ms with 1 thread, ~90
ms with 2 threads, ~140 ms with 4 threads + `OMP_WAIT_POLICY=PASSIVE`**.
`chessbot/ml/mcts.py` already caps threads during search
(`limit_torch_threads`), and `neural_eval.py` uses it. The trainer's gradient
steps do not seem to be capped (grep found no thread limit outside mcts /
neural_eval). This is pending review in phase 3. Recommendation: make the
training thread count configurable, or default it to a conservative value.

## Checkpoint evaluation

Real run in a scratch directory (not the project's `runs/`): config 32 ch x 2
blocks, 16 sims, 4 games/iteration, 80-ply cap, 30 steps/iteration, 3
iterations (84 s). Policy loss went from 7.25 to 5.71 to 5.07.
`scripts/evaluate_checkpoints.py`, 10 games each, 16 sims, 80-ply cap,
`DEFAULT_OPENINGS`:

| Candidate | Opponent | W/D/L | Score | Elo (95% CI) |
|---|---|---|---|---|
| latest (iter 3) | random | 0/9/1 | 0.45 | -35 (-103 .. +30) |
| untrained | random | 0/10/0 | 0.50 | 0 (-66 .. +66) |
| latest (iter 3) | iter_000001 | 0/10/0 | 0.50 | 0 (-66 .. +66) |

As expected, this tiny run shows no measurable strength. Nearly every game hit
the 80-ply cap. A meaningful strength curve needs a longer training run and
more games: **pending integration**.

## Bugs found

1. **`claim_draw=True` ends games early (cli.py, web/app.py; not QA-owned).**
   In python-chess, `outcome(claim_draw=True)` / `is_game_over(claim_draw=True)`
   also returns a draw when the side to move merely *could* reach threefold
   repetition or the 100th halfmove with its next move. Verified: after
   `Nf3 Nf6 Ng1 Ng8 Nf3 Nf6 Ng1` (a position seen only twice) it reports
   THREEFOLD_REPETITION, and at halfmove clock 99 with Ra8# available it
   reports FIFTY_MOVES. `chessbot/cli.py:117,133` and `chessbot/web/app.py:129,
   403,496,523` use it, so a human could be robbed of a win in the web UI. The
   arena and `ml/selfplay.py` check only draws that have actually occurred.
2. (Fixed in own file during development) arena initially used
   `claim_draw=True`; caught by `test_threefold_repetition_claimed` /
   `test_fifty_move_rule_claimed`.

## Known limitations

* Elo intervals use a normal approximation of the per-game score, which is
  poor for small n and extreme scores. Smoothing at 0%/100% and a variance
  floor (documented in `chessbot/arena.py`) keep the numbers finite but the
  CI is approximate. Use >= 30-50 games for decisions. There is no SPRT.
* `DEFAULT_OPENINGS` are 8 four-ply lines. Deterministic players (MCTS at
  temperature 0, alpha-beta) therefore produce at most 16 distinct games per
  pairing.
* Benchmarks on this shared machine are noisy (see the thread finding).
  Re-run on an idle machine for representative numbers.
* Integration review of web UI legality (`/api/game/move`) is **pending
  integration** (phase 3).
