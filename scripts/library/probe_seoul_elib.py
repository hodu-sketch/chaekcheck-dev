"""Re-check the Seoul Metropolitan Library's own e-book site (elib.seoul.go.kr) once, politely.

No adapter reads this site yet (docs/libraries/library-maintenance.md 4-14, docs/maintenance/platforms.md 11).
This script only repeats the 2026-10-20 research so a later maintenance run can see whether the
JSON API, its count fields and the holdings totals are still the same. It decides nothing.

Requests (GET only, our User-Agent, 1.1 s apart, about 7 in total):
  robots.txt, title search (/api/contents/search), one owned item's detail (/api/contents/{key}),
  main categories with counts for e-books and audiobooks (/api/category/main),
  one category page (/api/contents/catesearch) to see the largest page size that works.

Usage (repository root):
  python scripts/library/probe_seoul_elib.py [--keyword 사랑] [--page-count 100]
"""

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://elib.seoul.go.kr"
USER_AGENT = "chaekcheck/0.0.1"
GAP_SECONDS = 1.1
_last = [0.0]


def get(path, params=None):
    wait = _last[0] + GAP_SECONDS - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        body, status = e.read(), e.code
    finally:
        _last[0] = time.monotonic()
    return status, url, body


def as_json(body):
    try:
        return json.loads(body.decode("utf-8"))
    except ValueError:
        return None


def search_params(keyword, content_type, page_count):
    return {
        "libCode": "", "contentType": content_type, "searchKeyword": keyword, "searchOption": "1",
        "sortOption": "1", "innerSearchYN": "N", "innerKeyword": "", "currentCount": "1",
        "pageCount": str(page_count), "loanable": "", "isTotal": "false", "showType": "L",
        "searchCombine": "N",
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keyword", default="사랑")
    ap.add_argument("--page-count", type=int, default=100)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    status, url, body = get("/robots.txt")
    print(f"robots {status} {url}")
    print("  " + " | ".join(l.strip() for l in body.decode("utf-8", "replace").splitlines() if l.strip()))

    status, url, body = get("/api/contents/search", search_params(args.keyword, "EB", 10))
    data = as_json(body)
    print(f"search {status} {url}")
    owned_key = None
    if data:
        rows = data.get("ContentDataList") or []
        print(f"  totalCount={data.get('totalCount')} totalPage={data.get('totalPage')} rows={len(rows)}")
        if rows:
            print(f"  fields={sorted(rows[0].keys())}")
        for r in rows:
            print(f"  {r.get('contentsKey')}\t{r.get('contentsType')}\towner={r.get('ownerCode')}"
                  f"\tcopies={r.get('b2bCopys')}\tloan={r.get('currentLoanCount')}\tresv={r.get('currentResvCount')}"
                  f"\tisbn={r.get('isbn')}\t{r.get('title')}")
            if owned_key is None and r.get("contentsType") == "TY01" and r.get("ownerCode") != "OV":
                owned_key = r.get("contentsKey")
    else:
        print("  not JSON (API changed?)")

    if owned_key:
        status, url, body = get(f"/api/contents/{owned_key}")
        data = as_json(body)
        print(f"detail {status} {url}")
        if data and data.get("Contents"):
            c = data["Contents"][0]
            print(f"  contentsCopys={c.get('contentsCopys')} currentLoanCount={c.get('currentLoanCount')}"
                  f" currentResvCount={c.get('currentResvCount')} ownerCode={c.get('ownerCode')}")

    first_major = None
    for content_type in ("EB", "TY03"):
        status, url, body = get("/api/category/main", {"contentType": content_type})
        data = as_json(body)
        print(f"categories {content_type} {status} {url}")
        if data:
            cats = data.get("ContentDataList") or []
            total = sum(int(c.get("contentCount") or 0) for c in cats)
            print(f"  sum={total} " + ", ".join(f"{c.get('categoryNo')} {c.get('categoryName')} {c.get('contentCount')}" for c in cats))
            if content_type == "EB" and cats:
                first_major = cats[0].get("categoryNo")

    if first_major:
        status, url, body = get("/api/contents/catesearch", {
            "libCode": "", "majorCategory": first_major, "subCategory": "", "innerKeyword": "",
            "orderOption": "1", "loanable": "", "currentCount": "1", "pageCount": str(args.page_count)})
        data = as_json(body)
        print(f"category page {status} {url}")
        if data:
            print(f"  totalCount={data.get('totalCount')} totalPage={data.get('totalPage')}"
                  f" rows={len(data.get('ContentDataList') or [])}")
        else:
            print("  not JSON")


if __name__ == "__main__":
    main()
