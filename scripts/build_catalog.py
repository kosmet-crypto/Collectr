#!/usr/bin/env python3
"""Builds catalog/lego-foil.json from Rebrickable's public CSV dumps.

Usage: build_catalog.py <dir with sets.csv.gz and themes.csv.gz> <series.json> <output.json>

Also writes a report of small 6-digit sets that no series picked up (to the GitHub job
summary when available) so new series or prefixes are easy to spot.
"""
import csv
import datetime
import gzip
import io
import json
import os
import re
import sys
from collections import defaultdict


def read_csv(path):
    with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def main():
    src, config_path, out_path = sys.argv[1:4]
    config = json.load(open(config_path, encoding="utf-8"))
    max_parts = int(config.get("maxParts", 40))

    themes = {t["id"]: t for t in read_csv(os.path.join(src, "themes.csv.gz"))}

    def theme_path(theme_id):
        names = []
        seen = set()
        while theme_id and theme_id in themes and theme_id not in seen:
            seen.add(theme_id)
            names.append(themes[theme_id]["name"])
            theme_id = themes[theme_id].get("parent_id") or ""
        return names

    six = re.compile(r"^(\d{6})-1$")
    out_series = {s["id"]: [] for s in config["series"]}
    leftovers = defaultdict(list)

    for row in read_csv(os.path.join(src, "sets.csv.gz")):
        m = six.match(row["set_num"])
        if not m:
            continue
        code = m.group(1)
        try:
            parts = int(row.get("num_parts") or 0)
        except ValueError:
            parts = 0
        if parts > max_parts:
            continue
        path = theme_path(row["theme_id"])
        theme_text = " / ".join(path).lower()
        name = row["name"].strip()
        picked = False
        for s in config["series"]:
            words = [w.lower() for w in s.get("themes", [])]
            theme_ok = not words or any(re.search(r"\b" + re.escape(w) + r"\b", theme_text) for w in words)
            prefixes = s.get("prefixes") or []
            if prefixes:
                ok = any(code.startswith(p) for p in prefixes) and theme_ok
            else:
                ok = theme_ok and "foil" in name.lower()
            if ok:
                out_series[s["id"]].append({
                    "code": code,
                    "name": name,
                    "year": int(row["year"]) if (row.get("year") or "").isdigit() else None,
                    "img": row.get("img_url") or "",
                })
                picked = True
                break
        if not picked:
            leftovers[(code[:3], " / ".join(reversed(path)))].append(f"{code} {name}")

    series = []
    for s in config["series"]:
        items = sorted(out_series[s["id"]], key=lambda x: x["code"])
        # Same number twice (variants) keeps the first.
        seen = set()
        items = [i for i in items if not (i["code"] in seen or seen.add(i["code"]))]
        if items:
            series.append({"id": s["id"], "name": s["name"], "items": items})

    total = sum(len(s["items"]) for s in series)
    result = {
        "source": "Rebrickable",
        "generated": datetime.date.today().isoformat(),
        "series": series,
    }
    if total == 0:
        sys.exit("No foil packs found; keeping the current catalog")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
        f.write("\n")

    report = io.StringIO()
    report.write(f"## Foil pack catalog\n\n{total} packs in {len(series)} series\n\n")
    for s in series:
        report.write(f"- {s['name']}: {len(s['items'])}\n")
    report.write("\n## Small 6-digit sets not in any series (by prefix and theme)\n\n")
    for (prefix, theme), names in sorted(leftovers.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        if len(names) < 3:
            continue
        report.write(f"- **{prefix}xxx** {theme}: {len(names)} (e.g. {'; '.join(names[:3])})\n")
    text = report.getvalue()
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)


if __name__ == "__main__":
    main()
