"""One AI search timed the way the browser sends it (user 2026-11-26, docs/maintenance/cloudfront-trial.md): a fresh
connection, the status GET (it sets the CSRF cookie), then the stream POST on the same connection; prints ms from the
POST to the first event of each kind, and which edge answered (Cloudflare colo or CloudFront pop). Sends one real
question (one Haiku call).
  python first_search_timing.py [question] [region ids, comma] [host]
  python first_search_timing.py "우주를 배경으로 한 따뜻한 소설 추천해줘" 1114 cf-test.chaekcheck.com"""
import http.client, json, ssl, sys, time

question = sys.argv[1] if len(sys.argv) > 1 else "우주를 배경으로 한 따뜻한 소설 추천해줘"
regions = [int(x) for x in sys.argv[2].split(",")] if len(sys.argv) > 2 and sys.argv[2] else []
HOST = sys.argv[3] if len(sys.argv) > 3 else "chaekcheck.com"

t = time.perf_counter
ms = lambda a, b: round((b - a) * 1000)
c0 = t()
conn = http.client.HTTPSConnection(HOST, timeout=60, context=ssl.create_default_context())
conn.connect()
c1 = t()
conn.request("GET", "/api/v1/ai-searches/status", headers={"Accept": "application/json"})
r = conn.getresponse(); r.read()
c2 = t()
cookie = ""
for k, v in r.getheaders():
    if k.lower() == "set-cookie" and v.startswith("XSRF-TOKEN="):
        cookie = v.split(";")[0]
edge = r.getheader("x-amz-cf-pop") or (r.getheader("cf-ray") or "").rpartition("-")[2] or "?"
token = cookie.split("=", 1)[1] if cookie else ""
print(f"connect+tls {ms(c0, c1)} ms, status GET {ms(c1, c2)} ms ({r.status}), csrf {'yes' if token else 'no'}, edge {edge}")

body = json.dumps({"question": question, "turnNo": 1, "history": [], "residenceRegionIds": regions,
                   "workRegionIds": [], "libraryIds": [], "scope": "all", "visit": "any"}).encode()
p0 = t()
conn.request("POST", "/api/v1/ai-searches/stream", body=body, headers={
    "Content-Type": "application/json", "Accept": "text/event-stream, application/problem+json",
    "X-XSRF-TOKEN": token, "Cookie": cookie})
r = conn.getresponse()
p1 = t()
print(f"POST headers {ms(p0, p1)} ms, status {r.status}")
if r.status != 200:
    print(r.read()[:300].decode("utf-8", "replace")); sys.exit(1)
firsts, event, cards, text = {}, None, 0, ""
buf = b""
while True:
    chunk = r.read1(4096)
    if not chunk:
        break
    now = t()
    buf += chunk
    while b"\n\n" in buf:
        block, buf = buf.split(b"\n\n", 1)
        name, data = None, ""
        for line in block.decode("utf-8", "replace").split("\n"):
            if line.startswith("event:"): name = line[6:].strip()
            elif line.startswith("data:"): data += line[5:].strip()
        if not name: continue
        firsts.setdefault(name, ms(p0, now))
        if name == "card": cards += 1
        if name in ("head-delta", "card-delta") and len(text) < 60:
            text += json.loads(data).get("text", "")
        if name == "done":
            firsts["done"] = ms(p0, now)
            print("events (ms after POST sent):", json.dumps(firsts, ensure_ascii=False))
            print(f"cards {cards}, result {json.loads(data).get('result')}, first text: {text[:60]!r}")
            sys.exit(0)
print("stream ended without done", firsts)
