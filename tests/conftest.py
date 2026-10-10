import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from chessbot.config import Config  # noqa: E402


@pytest.fixture
def tiny_config(tmp_path) -> Config:
    """A config small enough for fast CPU tests, writing into tmp_path."""
    cfg = Config(run_dir=str(tmp_path / "run"))
    cfg.model.channels = 16
    cfg.model.res_blocks = 1
    cfg.model.value_hidden = 32
    cfg.search.depth = 2
    cfg.mcts.simulations = 8
    cfg.selfplay.games_per_iteration = 2
    cfg.selfplay.max_moves = 30
    cfg.training.steps_per_iteration = 5
    cfg.training.batch_size = 16
    cfg.training.min_buffer_size = 16
    cfg.training.replay_buffer_size = 2000
    cfg.training.device = "cpu"
    cfg.training.seed = 0
    cfg.evaluation.games = 2
    cfg.evaluation.max_moves = 40
    return cfg
