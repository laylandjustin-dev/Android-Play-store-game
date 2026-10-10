"""AlphaZero-style residual policy/value network."""

from __future__ import annotations

import warnings

import chess
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from chessbot.config import ModelConfig
from chessbot.ml.encoding import NUM_PLANES, POLICY_SIZE, encode_board, legal_move_indices

POLICY_FILTERS = 2   # 1x1 conv filters in the policy head (as in AlphaZero)
VALUE_FILTERS = 1    # 1x1 conv filters in the value head


def select_device(name: str | torch.device | None = "auto") -> torch.device:
    """``"auto"`` -> cuda if available else cpu. ``"cuda"`` without a GPU falls back to cpu."""
    if isinstance(name, torch.device):
        return name
    name = (name or "auto").lower()
    if name == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if name.startswith("cuda"):
        if not torch.cuda.is_available():
            warnings.warn("CUDA requested but not available; falling back to CPU", RuntimeWarning)
            return torch.device("cpu")
        return torch.device(name)
    if name == "cpu":
        return torch.device("cpu")
    raise ValueError(f"unknown device {name!r}; expected 'auto', 'cpu' or 'cuda'")


class ResidualBlock(nn.Module):
    def __init__(self, channels: int):
        super().__init__()
        self.conv1 = nn.Conv2d(channels, channels, 3, padding=1, bias=False)
        self.bn1 = nn.BatchNorm2d(channels)
        self.conv2 = nn.Conv2d(channels, channels, 3, padding=1, bias=False)
        self.bn2 = nn.BatchNorm2d(channels)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        y = F.relu(self.bn1(self.conv1(x)))
        y = self.bn2(self.conv2(y))
        return F.relu(x + y)


class PolicyValueNet(nn.Module):
    """conv stem -> residual tower -> (policy logits, tanh value)."""

    def __init__(self, config: ModelConfig):
        super().__init__()
        self.config = config
        c = config.channels
        self.stem = nn.Sequential(
            nn.Conv2d(NUM_PLANES, c, 3, padding=1, bias=False),
            nn.BatchNorm2d(c),
            nn.ReLU(inplace=True),
        )
        self.tower = nn.Sequential(*[ResidualBlock(c) for _ in range(config.res_blocks)])
        self.policy_conv = nn.Sequential(
            nn.Conv2d(c, POLICY_FILTERS, 1, bias=False),
            nn.BatchNorm2d(POLICY_FILTERS),
            nn.ReLU(inplace=True),
        )
        self.policy_fc = nn.Linear(POLICY_FILTERS * 64, POLICY_SIZE)
        self.value_conv = nn.Sequential(
            nn.Conv2d(c, VALUE_FILTERS, 1, bias=False),
            nn.BatchNorm2d(VALUE_FILTERS),
            nn.ReLU(inplace=True),
        )
        self.value_fc1 = nn.Linear(VALUE_FILTERS * 64, config.value_hidden)
        self.value_fc2 = nn.Linear(config.value_hidden, 1)

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        h = self.tower(self.stem(x))
        p = self.policy_fc(self.policy_conv(h).flatten(1))
        v = F.relu(self.value_fc1(self.value_conv(h).flatten(1)))
        v = torch.tanh(self.value_fc2(v))
        return p, v

    @property
    def device(self) -> torch.device:
        return next(self.parameters()).device

    @torch.no_grad()
    def predict_batch(self, boards: list[chess.Board]) -> list[tuple[list[chess.Move], np.ndarray, float]]:
        """For each board: (legal moves, masked softmax priors aligned with moves, value)."""
        if not boards:
            return []
        x = torch.from_numpy(np.stack([encode_board(b) for b in boards])).to(self.device)
        was_training = self.training
        if was_training:
            self.eval()
        try:
            logits, values = self(x)
        finally:
            if was_training:
                self.train()
        logits = logits.float().cpu().numpy()
        values = values.float().cpu().numpy()[:, 0]
        out = []
        for i, b in enumerate(boards):
            moves, idx = legal_move_indices(b)
            if len(moves) == 0:
                out.append((moves, np.zeros(0, dtype=np.float32), float(values[i])))
                continue
            sel = logits[i, idx].astype(np.float64)
            sel -= sel.max()
            p = np.exp(sel)
            p /= p.sum()
            out.append((moves, p.astype(np.float32), float(values[i])))
        return out

    @torch.no_grad()
    def predict(self, board: chess.Board) -> tuple[dict[chess.Move, float], float]:
        """Priors over legal moves only (sum to 1) and value for the side to move.

        Always evaluates in eval mode (BatchNorm running statistics); the
        module's previous train/eval mode is restored afterwards.
        """
        moves, p, v = self.predict_batch([board])[0]
        return {m: float(pi) for m, pi in zip(moves, p)}, v
