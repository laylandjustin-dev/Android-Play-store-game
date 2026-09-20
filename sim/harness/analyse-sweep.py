#!/usr/bin/env python3
"""Reads a balance-sweep CSV and reports the distribution, the targets, and the anomalies.

Written to answer three different questions, because they need different summaries:
  1. What happens?            distribution of outcomes by strategy
  2. Is it balanced?          the five design targets from section 12
  3. What is broken?          stocks and flows that no sane economy would produce
"""
import csv, sys, statistics as st
from collections import defaultdict

path = sys.argv[1]
rows = list(csv.DictReader(open(path)))
NUM = ('years','peak_population','final_population','tech_tier','buildings','wars','food','wood',
       'stone','knowledge','wealth','influence','produced_food','consumed_food','spoiled_food',
       'produced_wood','consumed_wood','births','deaths','combat_deaths','raids','trades',
       'elections','coups','rivals_alive','trait_points_spent','techs_chosen',
       'speed','health','hunting','elements','farming','gathering')
for r in rows:
    for k in NUM: r[k] = float(r[k])
    r['unrest'] = float(r['unrest'])

n = len(rows)
print(f"= {n} runs, {len(set(r['allocation'] for r in rows))} strategies, "
      f"{len(set(r['seed'] for r in rows))} seeds =\n")

# ---------------------------------------------------------------- 1. what happens
by = defaultdict(list)
for r in rows: by[r['allocation']].append(r)

def med(v): return st.median(v) if v else 0
print("-- outcome by strategy, worst to best mean years --")
print(f"{'strategy':24}{'mean y':>7}{'med':>5}{'max':>5}{'died<5y':>8}{'ascend':>7}"
      f"{'endure':>7}{'peak pop':>9}{'tier':>5}{'bld':>5}")
table = []
for name, rs in by.items():
    yrs = [r['years'] for r in rs]
    table.append((st.mean(yrs), name, rs, yrs))
for mean, name, rs, yrs in sorted(table):
    died = sum(1 for r in rs if r['years'] < 5) / len(rs)
    asc  = sum(1 for r in rs if r['end_state'] == 'ASCENSION') / len(rs)
    end  = sum(1 for r in rs if r['end_state'] == 'ENDURANCE') / len(rs)
    print(f"{name:24}{mean:7.1f}{med(yrs):5.0f}{max(yrs):5.0f}{died*100:7.0f}%{asc*100:6.0f}%"
          f"{end*100:6.0f}%{st.mean([r['peak_population'] for r in rs]):9.0f}"
          f"{st.mean([r['tech_tier'] for r in rs]):5.1f}{st.mean([r['buildings'] for r in rs]):5.0f}")

# The bimodality question, stated numerically.
print("\n-- is there a middle? distribution of run length --")
buckets = [(0,1),(1,5),(5,20),(20,50),(50,100),(100,200),(200,299),(299,10000)]
for lo, hi in buckets:
    c = sum(1 for r in rows if lo <= r['years'] < hi)
    bar = '#' * int(c / n * 120)
    print(f"  {lo:>3}-{hi if hi<10000 else '300+':<5} {c:5} ({c/n*100:4.1f}%) {bar}")

# Which trait actually decides survival?
print("\n-- survival against each trait (mean years by trait value) --")
for trait in ('speed','health','hunting','elements','farming','gathering'):
    vals = sorted(set(r[trait] for r in rows))
    parts = []
    for v in vals:
        sub = [r['years'] for r in rows if r[trait] == v]
        if len(sub) >= 10: parts.append(f"{int(v)}:{st.mean(sub):.0f}")
    print(f"  {trait:10} " + "  ".join(parts))

# ---------------------------------------------------------------- 2. the targets
print("\n-- the five section-12 targets --")
naive = by.get('naive-even', [])
if naive:
    m = st.mean([r['years'] for r in naive])
    print(f"  naive even spread survives 80-140 years: {m:.0f}y  "
          f"{'PASS' if 80 <= m <= 140 else 'FAIL'}")
reasoned = [r for r in rows if r['farming'] >= 5 or r['allocation'] in
            ('farmer','farm+elements','farm+gathering','duo-elements+farming')]
if reasoned:
    share = sum(1 for r in reasoned if r['years'] >= 299) / len(reasoned)
    print(f"  a reasoned allocation reaches 300y about 1 in 3: {share*100:.0f}%  "
          f"{'PASS' if 0.2 <= share <= 0.45 else 'FAIL'}")
solos = {n: st.mean([r['years'] for r in rs]) for n, rs in by.items() if n.startswith('solo-')}
if solos:
    best, worst = max(solos.values()), min(solos.values())
    spread = best / max(worst, 0.1)
    print(f"  no single trait at 8 dominates: spread {spread:.0f}x  "
          f"{'PASS' if spread <= 5 else 'FAIL'}   " +
          " ".join(f"{k.split('-')[1][:4]}={v:.0f}" for k, v in sorted(solos.items(), key=lambda x:-x[1])))
asc = sum(1 for r in rows if r['end_state'] == 'ASCENSION') / n
print(f"  ascension is rare without upgrades: {asc*100:.0f}%  {'PASS' if asc <= 0.15 else 'FAIL'}")

# ---------------------------------------------------------------- 3. what is broken
print("\n-- anomalies: stocks and flows no sane economy produces --")
alive = [r for r in rows if r['years'] >= 20]
def report(label, key, threshold, unit=''):
    bad = [r for r in alive if r[key] > threshold]
    if not bad: 
        print(f"  ok   {label}: max {max((r[key] for r in alive), default=0):,.0f}{unit}")
        return
    mx = max(r[key] for r in bad)
    print(f"  BAD  {label}: {len(bad)}/{len(alive)} runs over {threshold:,}{unit}, worst {mx:,.0f}{unit}"
          f"  (e.g. {sorted(bad, key=lambda r:-r[key])[0]['allocation']})")

if alive:
    report('wealth hoard', 'wealth', 100_000)
    report('wood hoard', 'wood', 100_000)
    report('stone hoard', 'stone', 100_000)
    report('influence hoard', 'influence', 5_000)
    report('knowledge hoard', 'knowledge', 100_000)

    # Food: how much of what was grown was actually eaten?
    waste = [(r['spoiled_food'] / max(r['produced_food'], 1)) for r in alive]
    print(f"  food spoiled as a share of food produced: mean {st.mean(waste)*100:.1f}%, "
          f"max {max(waste)*100:.1f}%")
    # A colony should not be able to grow more food than it can ever eat by a wide margin.
    ratio = [r['produced_food'] / max(r['consumed_food'], 1) for r in alive]
    print(f"  produced/consumed food: mean {st.mean(ratio):.2f}, max {max(ratio):.2f}")

    # Tech pacing: AD-30 says tier 6 should land around year 200.
    t6 = [r['years'] for r in rows if r['tech_tier'] >= 6]
    if t6:
        print(f"  runs reaching tier 6: {len(t6)}/{n} ({len(t6)/n*100:.0f}%), "
              f"earliest at year {min(t6):.0f}, median {med(t6):.0f}")
    # Influence: how does the stock compare to the most expensive lever (180)?
    infl = [r['influence'] for r in alive]
    print(f"  influence vs the 180-cost lever: median {med(infl):,.0f} "
          f"= {med(infl)/180:,.0f}x the priciest action")

# Sanity checks that should never trip at all.
print("\n-- invariant checks --")
def check(label, pred):
    bad = [r for r in rows if pred(r)]
    print(f"  {'FAIL' if bad else 'ok  '} {label}" + (f" — {len(bad)} runs" if bad else ""))
check("no negative stock", lambda r: min(r['food'], r['wood'], r['stone'], r['wealth'], r['knowledge']) < 0)
check("deaths never exceed births plus settlers", lambda r: r['deaths'] > r['births'] + 200)
check("peak population is at least the final", lambda r: r['peak_population'] < r['final_population'])
check("consumed food never exceeds produced plus founding stores",
      lambda r: r['consumed_food'] > r['produced_food'] + 5000)
check("a surviving run has a Premier history", lambda r: r['years'] >= 10 and r['elections'] == 0)
check("a surviving run built something", lambda r: r['years'] >= 10 and r['buildings'] == 0)
check("trait points are spent, not banked", lambda r: r['years'] >= 30 and r['trait_points_spent'] <= 10)
check("tech choices match the tier reached",
      lambda r: r['techs_chosen'] > 0 and r['techs_chosen'] > r['tech_tier'])
