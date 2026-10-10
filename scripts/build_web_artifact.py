"""Assemble the self-contained in-browser bot page (browser/dist/chessbot.html).

    python scripts/export_web_model.py --checkpoint runs/demo/checkpoints/latest.pt
    python scripts/build_web_artifact.py --metrics runs/demo/metrics.jsonl

The page embeds browser/bot.js, the exported network weights and the latest
evaluation records of that checkpoint from metrics.jsonl (real match results
only; nothing is shown if none exist).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _label(name: str) -> str:
    """Readable opponent name for the model card."""
    if name == "random":
        return "random mover"
    if name.startswith("alphabeta"):
        return "alpha-beta engine"
    m = re.search(r"iter_0*(\d+)", name)
    return f"its iteration {m.group(1)}" if m else name


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--model", default=str(ROOT / "browser/model.json"))
    ap.add_argument("--metrics", default=None, help="metrics.jsonl with type=eval records")
    ap.add_argument("--note", default=None, help="optional note shown on the model card")
    ap.add_argument("--out", default=str(ROOT / "browser/dist/chessbot.html"))
    args = ap.parse_args()

    spec = json.loads(Path(args.model).read_text())
    spec.pop("tests", None)
    evals = []
    if args.metrics and Path(args.metrics).exists():
        it = spec["source"]["iteration"]
        latest = {}
        for line in Path(args.metrics).read_text().splitlines():
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            if rec.get("type") == "eval" and rec.get("iteration") == it:
                latest[rec["opponent"]] = rec   # keep the most recent per opponent
        evals = [{k: r[k] for k in ("opponent", "games", "wins", "draws", "losses",
                                    "score", "elo", "elo_low", "elo_high")} for r in latest.values()]
    for e in evals:
        e["opponent"] = _label(e["opponent"])
    spec["evaluation"] = evals
    if args.note:
        spec["note"] = args.note

    html = (ROOT / "browser/template.html").read_text()
    bot = (ROOT / "browser/bot.js").read_text()
    data = json.dumps(spec, separators=(",", ":")).replace("</", "<\\/")
    html = html.replace("/*__BOT_JS__*/", bot).replace("/*__MODEL_JSON__*/", data)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html)
    print(f"wrote {out} ({out.stat().st_size / 1e6:.2f} MB), {len(evals)} eval record(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
