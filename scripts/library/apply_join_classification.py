"""Applies a join classification to the seed CSVs (docs/library-maintenance.md 4-8, 4-10).

Usage (from the repository root):
  python scripts/library/apply_join_classification.py <classification.tsv> [<more.tsv> ...]
  Later files win for the same id: pass scripts/library/join_overrides.tsv (results the user decided) last.

classification.tsv columns: id, name, verdict, rules, eligibility_summary, extra_condition_text, join_caution,
visit_required, join_method, join_url, evidence, source_url, notes (NULL for empty).
  verdict OPEN | OPEN_CAUTION -> one rule RESIDENCE × NATIONWIDE
  verdict REGION              -> the listed rules, e.g. RESIDENCE:41;WORK:41
  verdict UNVERIFIED          -> no rules (eligibility not verified, ADR-0013)
Rewrites the library rows and the rules of the listed ids in every library/*-library*.csv and
*-eligibility-rule*.csv. Rows for ids that are not in a CSV (the pilot seed in 002-library-seed.yaml) are printed
so they can be changed by hand. A NULL join_method keeps the current text.
"""
import csv
import glob
import io
import sys

sys.stdout.reconfigure(encoding="utf-8")
LIBRARY_DIR = "backend/src/main/resources/db/changelog/library/"
REGIONS = "backend/src/main/resources/db/changelog/region/002-region-seed.csv"
BASIS_WORDS = {"RESIDENCE": "거주", "WORK": "재직", "SCHOOL": "재학"}
LIBRARY_FIELDS = ["eligibility_summary", "extra_condition_text", "join_caution", "visit_required", "join_url"]


def rules_of(row):
    if row["verdict"] in ("OPEN", "OPEN_CAUTION"):
        return [("RESIDENCE", "NATIONWIDE", "NULL")]
    if row["verdict"] != "REGION" or row["rules"] == "NULL":
        return []
    rules = []
    for item in row["rules"].split(";"):
        basis, scope = item.strip().split(":")
        rules.append((basis, "NATIONWIDE", "NULL") if scope == "NATIONWIDE" else (basis, "REGION", scope))
    return rules


def summary_of(rules, names):
    """Same wording as build_seed.py: "경기 거주 / 과천시 재직·재학"."""
    by_region = {}
    for basis, scope_type, region in rules:
        if scope_type == "REGION" and basis in BASIS_WORDS:
            by_region.setdefault(region, set()).add(basis)
    groups = {}
    for region, bases in by_region.items():
        key = tuple(b for b in BASIS_WORDS if b in bases)
        groups.setdefault(key, []).append(names[int(region)])
    return " / ".join("·".join(n) + " " + "·".join(BASIS_WORDS[b] for b in key) for key, n in groups.items()) or "NULL"


def write_csv(path, rows):
    out = io.StringIO()
    csv.writer(out, lineterminator="\n").writerows(rows)
    open(path, "w", encoding="utf-8", newline="").write(out.getvalue())


def main(classification_paths):
    names = {int(r["id"]): (r["short_name"] if r["short_name"] != "NULL" else r["name"])
             for r in csv.DictReader(open(REGIONS, encoding="utf-8"))}
    decided = {}
    for path in classification_paths:
        decided.update({r["id"]: r for r in csv.DictReader(open(path, encoding="utf-8"), delimiter="\t")})
    seen = set()
    for path in sorted(glob.glob(LIBRARY_DIR + "*-library*.csv")):
        rows = list(csv.reader(open(path, encoding="utf-8", newline="")))
        header = rows[0]
        for row in rows[1:]:
            d = decided.get(row[0])
            if not d:
                continue
            seen.add(row[0])
            values = dict(d)
            if values["verdict"] == "REGION" and values["eligibility_summary"] == "NULL":
                values["eligibility_summary"] = summary_of(rules_of(d), names)
            if values["verdict"] in ("OPEN", "OPEN_CAUTION"):
                values["eligibility_summary"] = "NULL"  # the screen shows "누구나 가입"
            for field in LIBRARY_FIELDS:
                row[header.index(field)] = values[field] or "NULL"
            if values.get("join_notice") and "join_notice" in header:  # optional column (안내)
                row[header.index("join_notice")] = values["join_notice"]
            if values["join_method"] not in ("", "NULL"):
                row[header.index("join_method")] = values["join_method"]
        write_csv(path, rows)
    for path in sorted(glob.glob(LIBRARY_DIR + "*-eligibility-rule*.csv")):
        rows = list(csv.reader(open(path, encoding="utf-8", newline="")))
        ids_here = {r[0] for r in rows[1:]} | {i for i in seen if i in library_ids_of(path)}
        kept = [r for r in rows[1:] if r[0] not in decided]
        added = [[i, *rule] for i in sorted(ids_here & decided.keys(), key=int) for rule in rules_of(decided[i])]
        write_csv(path, [rows[0]] + sorted(kept + added, key=lambda r: int(r[0])))
    for i in sorted(decided.keys() - seen, key=int):
        d = decided[i]
        print("not in a CSV, change by hand:", i, d["name"], d["verdict"], d["rules"], d["join_caution"])


def library_ids_of(rule_path):
    """Ids of the library CSV that pairs with this rule CSV (same number prefix)."""
    prefix = rule_path.replace("\\", "/").rsplit("/", 1)[1].split("-", 1)[0]
    ids = set()
    for path in glob.glob(LIBRARY_DIR + prefix + "-library*.csv"):
        ids |= {r["id"] for r in csv.DictReader(open(path, encoding="utf-8"))}
    return ids


if __name__ == "__main__":
    main(sys.argv[1:])
