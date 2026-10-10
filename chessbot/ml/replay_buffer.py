"""Fixed-capacity ring buffer of self-play samples.

Public API works with ``(state float32 (NUM_PLANES,8,8), policy float32
(POLICY_SIZE,), value float32)``. Internally, to keep memory and files small:

* states are stored as float16 (planes are 0/1 except the halfmove-clock
  plane, a multiple of 0.01 in [0, 1], whose float16 error is < 3e-4);
* policies are stored **sparsely**: up to ``MAX_POLICY_ENTRIES`` (index
  int16, probability float16) pairs per sample, padded with index
  ``POLICY_SIZE``. A chess position has at most 218 legal moves, and MCTS
  visit distributions are non-zero only on legal moves, so nothing is lost
  apart from float16 rounding (policies are renormalised on sampling).
  If a policy ever has more non-zeros, the largest entries are kept.

The ``.npz`` file (``np.savez_compressed``) holds the filled entries in
chronological order (oldest first) under keys ``states``, ``policy_idx``,
``policy_val``, ``values`` and ``capacity``. Loading into a smaller capacity
keeps the newest samples.
"""

from __future__ import annotations

import os
from pathlib import Path

import numpy as np

from chessbot.ml.encoding import NUM_PLANES, POLICY_SIZE

MAX_POLICY_ENTRIES = 256
_STATE_SHAPE = (NUM_PLANES, 8, 8)


class ReplayBuffer:
    def __init__(self, capacity: int, seed: int | None = None):
        if capacity < 1:
            raise ValueError("capacity must be >= 1")
        self.capacity = int(capacity)
        self.rng = np.random.default_rng(seed)
        self._states = np.zeros((self.capacity, *_STATE_SHAPE), dtype=np.float16)
        self._pidx = np.full((self.capacity, MAX_POLICY_ENTRIES), POLICY_SIZE, dtype=np.int16)
        self._pval = np.zeros((self.capacity, MAX_POLICY_ENTRIES), dtype=np.float16)
        self._values = np.zeros(self.capacity, dtype=np.float32)
        self._next = 0      # slot the next sample is written to
        self._size = 0
        self.total_added = 0

    def __len__(self) -> int:
        return self._size

    def add(self, state: np.ndarray, policy: np.ndarray, value: float) -> None:
        state = np.asarray(state, dtype=np.float32)
        policy = np.asarray(policy, dtype=np.float32).reshape(-1)
        if state.shape != _STATE_SHAPE:
            raise ValueError(f"state shape {state.shape} != {_STATE_SHAPE}")
        if policy.shape != (POLICY_SIZE,):
            raise ValueError(f"policy shape {policy.shape} != ({POLICY_SIZE},)")
        nz = np.flatnonzero(policy)
        if len(nz) > MAX_POLICY_ENTRIES:
            nz = nz[np.argsort(policy[nz])[::-1][:MAX_POLICY_ENTRIES]]
        i = self._next
        self._states[i] = state
        self._pidx[i] = POLICY_SIZE
        self._pval[i] = 0
        self._pidx[i, :len(nz)] = nz
        self._pval[i, :len(nz)] = policy[nz]
        self._values[i] = value
        self._next = (i + 1) % self.capacity
        self._size = min(self._size + 1, self.capacity)
        self.total_added += 1

    def add_game(self, samples) -> None:
        for state, policy, value in samples:
            self.add(state, policy, value)

    def _dense(self, rows: np.ndarray) -> np.ndarray:
        out = np.zeros((len(rows), POLICY_SIZE + 1), dtype=np.float32)
        np.put_along_axis(out, self._pidx[rows].astype(np.int64),
                          self._pval[rows].astype(np.float32), axis=1)
        out = out[:, :POLICY_SIZE]
        sums = out.sum(axis=1, keepdims=True)
        np.divide(out, sums, out=out, where=sums > 0)
        return out

    def sample(self, batch_size: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """Uniform sample with replacement: (states, policies, values) as float32."""
        if self._size == 0:
            raise ValueError("cannot sample from an empty replay buffer")
        rows = self.rng.integers(0, self._size, size=batch_size)
        return (self._states[rows].astype(np.float32), self._dense(rows),
                self._values[rows].copy())

    def _order(self) -> np.ndarray:
        """Slot indices of the stored samples, oldest first."""
        if self._size < self.capacity:
            return np.arange(self._size)
        return (np.arange(self.capacity) + self._next) % self.capacity

    def get_all(self) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """All samples, oldest first, as float32 (states, dense policies, values)."""
        rows = self._order()
        return self._states[rows].astype(np.float32), self._dense(rows), self._values[rows].copy()

    def save(self, path) -> None:
        """Atomically write the buffer to a compressed ``.npz`` file."""
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        rows = self._order()
        tmp = path.with_name(path.name + ".tmp")
        with open(tmp, "wb") as fh:
            np.savez_compressed(fh, states=self._states[rows], policy_idx=self._pidx[rows],
                                policy_val=self._pval[rows], values=self._values[rows],
                                capacity=np.int64(self.capacity),
                                total_added=np.int64(self.total_added))
        os.replace(tmp, path)

    @classmethod
    def load(cls, path, capacity: int | None = None, seed: int | None = None) -> "ReplayBuffer":
        with np.load(Path(path)) as data:
            cap = int(capacity) if capacity is not None else int(data["capacity"])
            buf = cls(cap, seed=seed)
            states, pidx, pval, values = (data["states"], data["policy_idx"],
                                          data["policy_val"], data["values"])
            n = min(len(values), cap)
            start = len(values) - n
            buf._states[:n] = states[start:]
            buf._pidx[:n] = pidx[start:]
            buf._pval[:n] = pval[start:]
            buf._values[:n] = values[start:]
            buf._size = n
            buf._next = n % cap
            buf.total_added = int(data["total_added"]) if "total_added" in data else n
        return buf
