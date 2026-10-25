"""Current dictionary (profiles.json, 22 shelves) vs detailed (profile3/out-*.jsonl) on the same 1,597 candidate books.
1. Agreement with the bibliography's KDC: big shelf (both), small shelf (detailed, where a KDC range is clear),
   author's country for literature (detailed, KDC 81x-89x).
2. Shelf matching inside the candidates: for every question the query-side facets (kw-facets.json / kw-facets2.json)
   give each of P's 30 candidates a match count; candidates are ordered by matches, then by P's order; the first 8
   are scored with the judge's grades (exact = 3, wrong = 0-1). No LLM pick: this isolates what the shelves add."""
import glob
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
J = json.load(open(os.path.join(HERE, "judged.json")))
QT = {q["id"]: q["type"] for q in json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))}
P1 = json.load(open(os.path.join(HERE, "profiles.json"), encoding="utf-8"))
P2 = {}
for p in glob.glob(os.path.join(HERE, "profile3", "out-*.jsonl")):
    for line in open(p, encoding="utf-8"):
        if line.strip():
            try:
                o = json.loads(line)
                P2[str(o["id"])] = o
            except ValueError:
                pass
kdc = {}
for line in open(os.path.join(HERE, "nlk-kdc.tsv"), encoding="utf-8"):
    r = line.rstrip("\n").split("\t")
    if r[2][:3].isdigit():
        kdc[r[0]] = int(r[2][:3])


def coarse(n):
    if 800 <= n < 900: return "lit"
    if 180 <= n < 190: return "psy"
    if n < 100: return "gen"
    if n < 200: return "phil"
    if n < 300: return "rel"
    if 320 <= n < 330: return "econ"
    if 370 <= n < 380: return "edu"
    if n < 400: return "soc"
    if n < 500: return "sci"
    if 510 <= n < 520: return "health"
    if 590 <= n < 600: return "life"
    if n < 600: return "tech"
    if n < 700: return "art"
    if n < 800: return "lang"
    if 980 <= n < 990: return "travel"
    return "hist"


BIG = {"소설": "lit", "시·희곡": "lit", "에세이": "lit", "인문·철학": "phil", "심리": "psy", "종교": "rel",
       "경제·경영": "econ", "자기계발": {"econ", "psy", "soc", "phil"}, "육아·교육": "edu", "사회·정치": "soc",
       "과학": "sci", "건강·의학": "health", "요리·생활": "life", "기술·IT": {"tech", "gen"}, "예술·취미": "art",
       "외국어": "lang", "여행": "travel", "역사": "hist"}
SUB = {"한국사": [(911, 912)], "동양사": [(910, 911), (912, 920)], "서양사": [(920, 930)], "동양철학": [(140, 160)],
       "서양철학": [(160, 170)], "수학": [(410, 420)], "물리·우주": [(420, 430), (440, 450)], "화학": [(430, 440)],
       "생물·생명": [(470, 500)], "인공지능": [(4, 6)], "프로그래밍": [(4, 6)], "요리": [(594, 595)],
       "육아": [(598, 599)], "기독교": [(230, 240)], "불교": [(220, 230)], "국내 여행": [(981, 982)],
       "해외 여행": [(982, 990)], "미술": [(600, 660)], "음악": [(670, 680)], "사진": [(660, 670)],
       "스포츠·게임": [(690, 700)], "재테크·투자": [(327, 328)], "시": [(811, 812), (821, 822), (831, 832), (841, 842), (851, 852), (861, 862), (891, 892)]}
COUNTRY = {81: "한국", 82: "중국", 83: "일본", 84: "영미", 85: "독일", 86: "프랑스"}


def ok(field, n):
    m = BIG.get(field)
    return None if m is None else (coarse(n) in m if isinstance(m, set) else coarse(n) == m)


def rate(pairs):
    pairs = [p for p in pairs if p is not None]
    return f"{100 * sum(pairs) / max(len(pairs), 1):.0f}% ({sum(pairs)}/{len(pairs)})"


print("profiles: current", len(P1), "detailed", len(P2))
print("1. KDC agreement")
print("   big shelf  current :", rate([ok(o.get("category"), kdc[b]) for b, o in P1.items() if b in kdc]))
print("   big shelf  detailed:", rate([ok(o.get("field"), kdc[b]) for b, o in P2.items() if b in kdc]))
print("   small shelf detailed:", rate([any(a <= kdc[b] < z for a, z in SUB[o["subfield"]])
                                       for b, o in P2.items() if b in kdc and o.get("subfield") in SUB]))
print("   country    detailed:", rate([o.get("country") == COUNTRY[kdc[b] // 10]
                                       for b, o in P2.items() if b in kdc and kdc[b] // 10 in COUNTRY
                                       and o.get("country")]))
print("   country given when KDC says literature of a known country:",
      rate([bool(o.get("country")) for b, o in P2.items() if b in kdc and kdc[b] // 10 in COUNTRY]))

F1 = {x["id"]: x for x in json.load(open(os.path.join(HERE, "kw-facets.json"), encoding="utf-8"))}
F2 = {x["id"]: x for x in json.load(open(os.path.join(HERE, "kw-facets2.json"), encoding="utf-8"))}
cands = {}
for p in glob.glob(os.path.join(HERE, "more", "pick-in-P-*.json")):
    for it in json.load(open(p, encoding="utf-8")):
        cands[it["id"]] = it["ids"]


def match1(f, o):
    if not o:
        return 0
    m = 0
    if f.get("category") and o.get("category") in f["category"]: m += 1
    if f.get("fiction") is not None and o.get("fiction") == f["fiction"]: m += 1
    if f.get("audience") and o.get("audience") == f["audience"]: m += 1
    if f.get("level") and o.get("level") == f["level"]: m += 1
    return m


AUD = {"아동": "아동", "청소년": "청소년", "전문": "전문"}


def match2(f, o):
    if not o:
        return 0
    m = 0
    if f.get("field") and o.get("field") in f["field"]: m += 1
    if f.get("subfield") and o.get("subfield") in f["subfield"]: m += 1
    if f.get("fiction") is not None and o.get("fiction") == f["fiction"]: m += 1
    if f.get("genre") and set(o.get("genre") or []) & set(f["genre"]): m += 1
    if f.get("country") and o.get("country") == f["country"]: m += 1
    if f.get("audience") and o.get("audience") == f["audience"]: m += 1
    if f.get("level") and o.get("level") == f["level"]: m += 1
    if f.get("form") and set(o.get("form") or []) & set(f["form"]): m += 1
    return m


def has(f, keys):
    return any(f.get(k) not in (None, "", []) for k in keys)


def top8(q, order):
    g = [J[str(q)].get(str(b), 0) for b in order[:8]]
    return sum(x >= 3 for x in g), sum(x <= 1 for x in g)


print("\n2. shelf matching inside P's 30 candidates, first 8 (exact per question / wrong per question)")
for label, qs in (("all 57", list(cands)),
                  ("with a facet (current)", [q for q in cands if has(F1.get(q, {}), ["category", "fiction", "audience", "level"])]),
                  ("with a narrow facet (detailed: subfield/genre/country/audience/level/form)",
                   [q for q in cands if has(F2.get(q, {}), ["subfield", "genre", "country", "audience", "level", "form"])])):
    rows = {"no shelves": [], "current": [], "detailed": []}
    for q in qs:
        base = cands[q]
        rows["no shelves"].append(top8(q, base))
        rows["current"].append(top8(q, sorted(base, key=lambda b: (-match1(F1.get(q, {}), P1.get(str(b))), base.index(b)))))
        rows["detailed"].append(top8(q, sorted(base, key=lambda b: (-match2(F2.get(q, {}), P2.get(str(b))), base.index(b)))))
    n = max(len(qs), 1)
    print(f"   {label} ({len(qs)} questions): " + ", ".join(
        f"{k} {sum(a for a, _ in v) / n:.2f} / {sum(b for _, b in v) / n:.2f}" for k, v in rows.items()))
