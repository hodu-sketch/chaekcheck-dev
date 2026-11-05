# 8. 책첵 추천의 원리·수학·코드 해설 (학습용)

- **이 문서가 답하는 것**: [5-improvements.md](5-improvements.md)의 개선안과 지금 동작하는 부분이 **어떤 원리로, 어떤 수학으로, 어떤 코드·라이브러리로** 되는지. 머신러닝 기본 개념을 하나씩, 남에게 설명할 수 있게. 실제 저장소 코드와, 아직 없는 것은 **「예시 코드(저장소에 없음)」**로 표시한 코드.
- **답하지 않는 것**: 측정 결과 숫자 전체 → [3-evaluation.md](3-evaluation.md). 개선안의 상태·결정 → [5-improvements.md](5-improvements.md). 자료 파일 위치 → [2-data.md](2-data.md).
- 절마다 같은 꼴: **원리 → 수식 → 우리 코드 → 라이브러리 쓰는 법 → 한 줄로 설명하기**.
- 예시 코드의 라이브러리 함수 이름은 2026-10 기준 공식 사용법을 따랐지만, 실제로 쓸 때는 설치한 버전의 공식 문서로 확인하세요 [확인 필요]. plan 조사의 버전: scikit-learn 1.9.1(BSD-3), umap-learn 0.5.12(BSD), cleanlab 2.9.0(Apache-2.0), faiss-cpu 1.15.1(MIT), bertopic 0.17.4(MIT).

## 목차

| 파트 | 절 | 개선안 |
|---|---|---|
| A 기초 | A1 임베딩 · A2 코사인과 정규화 · A3 최근접 이웃 검색 · A4 순위 지표 · A5 다양성 지표 · A6 LLM 판정 | — |
| B 지금 동작 | B1 본 책 점수 · B2 이웃 계산 · B3 같은 작품 규칙 · B4 서버 순서 · B5 화면 규칙 · B6 분야 짐작 · B7 int8 벡터(AI 검색) · B8 새벽 점수(꺼짐) | — |
| C 순서 고치기 | C1 순위 합치기(RRF) · C2 MMR · C3 가중합 vs 문턱 | I-01, I-02, I-17 |
| D 지도학습 | D1 지도학습이란 · D2 로지스틱 회귀 · D3 MLP · D4 불균형과 macro-F1 · D5 누수와 그룹 분할 · D6 확률 보정·ECE · D7 선택적 예측 · D8 kNN 다수결 기준선 | I-11 |
| E 라벨 잡음 | E1 confident learning | I-12 |
| F 비지도학습 | F1 차원 축소(UMAP) · F2 군집(k-means, HDBSCAN) · F3 c-TF-IDF · F4 군집 평가(순도·ARI·NPMI) | I-13 |
| G 같은 작품 | G1 문자열 유사도 · G2 쌍 분류기와 정밀도 우선 문턱 | I-14 |
| H 벡터 저장 | H1 해시로 바뀐 것만 · H2 양자화 · H3 근사 이웃(HNSW·IVF)과 recall | I-10 |
| I 생성·판단 모델 | I1 LLM으로 글 만들기와 규칙 검사 · I2 LLM 재정렬 · I3 판단 모델(Jev) | I-04, I-08, I-09 |
| J 온라인 실험 | J1 노출 기록 · J2 표본 크기 · J3 끼워 넣기 · J4 A/B와 SRM | I-05, I-06 |
| K 남에게 설명하기 | 질문별 짧은 답 | — |


### 적용 상태 표시 (2026-10-29, v1.20.6 기준)

절 제목 끝의 [ ]가 그 내용이 실제로 쓰이는지 알려 줍니다.

| 표시 | 뜻 |
|---|---|
| **[운영]** | 운영 서비스(chaekcheck.com)에서 동작하거나, 운영에 든 자료(이웃 표·요약·벡터 파일)를 만드는 데 실제로 씀 |
| **[평가용]** | 측정 스크립트(`scripts/foryou/eval/`)에서만 씀. 운영 코드에는 없음 |
| **[시험 후 버림]** | 오프라인으로 시험했고 결과가 나빠 쓰지 않기로 함 |
| **[미적용]** | 제안이거나 요청됨(5-improvements.md). 아직 어디에도 없음 |
| **[미적용 · 예제만]** | 운영에는 없고, 원리를 보이려고 `ml_basics_demo.py`로 한 번 돌려 본 것만 있음 |
| **[꺼짐]** | 코드는 있지만 운영에서 꺼 둠 |

한 절에 둘이 섞이면 「운영: … · 미적용: …」처럼 나눠 적었습니다.

| 파트 | 운영 | 미적용·평가용·버림 |
|---|---|---|
| A 기초 | A1 임베딩, A2 코사인, A3 전수 비교 | A3 HNSW(미적용), A4~A6 지표·판정(평가용) |
| B 지금 동작 | B1~B7 | B3 규칙 통일(미적용), B8 새벽 점수(꺼짐) |
| C 순서 | C3 문턱(v1.20.2), C1 RRF는 AI 검색에서만 | C1·C2·C3 가중합(책첵 추천에서 시험 후 버림) |
| D 지도학습 | D3 MLP는 AI 검색 1차 판단(v1.16.0~), D8 kNN 다수결은 B6 분야 짐작 | 책첵 추천 분류기(I-11) 전부 미적용 |
| E~G | — | 전부 미적용(I-12~I-14) |
| H 벡터 | H1 파일 전체 재사용, H2 int8(AI 검색) | H1 책 단위, H3 HNSW |
| I 생성·판단 | I1 요약 만들기(68,414권) | I1 빠진 책 채우기(I-04), I2, I3 |
| J 온라인 실험 | — | 전부 미적용(노출 기록 없음) |

---

# A. 기초 원리

## A1. 임베딩(embedding): 글을 숫자 벡터로 [운영]

**원리.** 글의 「뜻」을 고정 길이의 숫자 목록(벡터)으로 바꾸는 것. 뜻이 비슷한 글은 공간에서 가까운 점이 되도록 모델이 학습되어 있음. 우리가 쓰는 **KURE-v1**(`nlpai-lab/KURE-v1`)은 한국어 검색용으로 학습된 문장 임베딩 모델로, 글 하나를 **1,024개 실수**로 바꿈.

**어떻게 학습된 모델인가(개념).** 트랜스포머 인코더가 글의 토큰들을 읽어 토큰마다 벡터를 만들고, 이것을 하나로 모아(pooling) 문장 벡터를 냄. 학습은 대조 학습(contrastive learning): 「질문–정답 문서」처럼 짝인 글의 벡터는 가깝게, 짝이 아닌 글은 멀게 만드는 손실을 줄이며 가중치를 조정. 그래서 「같은 주제」의 책 소개가 가까워짐.

**우리 입력.** `제목 · 저자 · 출판사 · 연도` + 줄바꿈 + 소개 앞 300자(2-data.md 2절). 소개를 넣으면 제목만 쓸 때보다 이웃이 내용 쪽으로 바뀜(사전 시험, [experiments/2026-10-25-intro-test.md](experiments/2026-10-25-intro-test.md)).

**우리 코드** (`scripts/crawl/foryou_neighbours.py`):
```python
from sentence_transformers import SentenceTransformer
model = SentenceTransformer('nlpai-lab/KURE-v1', device='cpu')
vectors = model.encode(texts, batch_size=32, normalize_embeddings=True,
                       convert_to_numpy=True, show_progress_bar=False).astype('float32')
# vectors.shape == (책 수, 1024), 각 행의 길이 = 1
```

**한 줄로.** "책 설명을 한국어 임베딩 모델로 1,024차원 벡터로 바꿔, 뜻이 비슷한 책이 가까운 점이 되게 했다."

## A2. 코사인 유사도와 L2 정규화 [운영]

**수식.** 두 벡터 a, b의 코사인 유사도
```
cos(a, b) = (a · b) / (‖a‖ ‖b‖),   a · b = Σᵢ aᵢ bᵢ,   ‖a‖ = √(Σᵢ aᵢ²)
```
값은 −1~1. 방향이 같을수록 1. 벡터의 길이(크기)는 무시하고 방향만 봄.

**L2 정규화.** 각 벡터를 자기 길이로 나눠 길이를 1로 만듦: `â = a / ‖a‖`. 그러면 `‖â‖ = ‖b̂‖ = 1`이라 **코사인 = 내적** `cos(a,b) = â · b̂`. `normalize_embeddings=True`가 이것.

**왜 중요한가.** 내적은 행렬 곱 한 번으로 아주 많이 계산할 수 있음. 책 81,080권의 행렬 `V`(81,080 × 1,024)에서 `V @ V.T`의 (i, j) 칸이 곧 책 i와 j의 코사인.

**작은 예.** a = (3, 4) → ‖a‖ = 5 → â = (0.6, 0.8). b = (4, 3) → b̂ = (0.8, 0.6). 코사인 = 0.6×0.8 + 0.8×0.6 = 0.96.

**우리 값의 감각.** 10만 권 판에서 이웃 1위 코사인은 대개 0.6~0.8. 같은 작품 쌍도 0.68~0.78이라 **코사인만으로는 「같은 책」과 「비슷한 책」을 못 가름**(B3·G 절의 이유).

## A3. 최근접 이웃 검색(nearest neighbour search) [운영: 전수 비교 · 미적용: HNSW]

**문제.** 책마다 코사인이 가장 큰 k권(k = 20) 찾기.

**정확한 방법(전수 비교).** 모든 쌍을 계산: 81,080² ≈ 66억 번의 1,024차원 내적. 메모리에 한 번에 못 올리므로 **블록**으로 나눔.

**우리 코드** (`foryou_neighbours.py`, 핵심만):
```python
for start in range(0, len(books), 1000):                 # 1,000행씩
    block = vectors[start:start + 1000] @ vectors.T       # (1000, 81080) 코사인
    near = np.argpartition(-block, SHORTLIST, axis=1)[:, :SHORTLIST]   # 행마다 큰 200개
    for k in range(block.shape[0]):
        order = near[k][np.argsort(-block[k][near[k]])]   # 그 200개만 큰 순 정렬
        ...                                               # 같은 작품 건너뛰며 20개 고름(B2)
```
- `np.argpartition(x, k)`: 배열을 「k번째 원소 기준으로 왼쪽은 작게, 오른쪽은 크게」만 나눔. 전체 정렬 O(n log n) 대신 **평균 O(n)**. 상위 200개만 필요하니 충분. `-block`을 넣어 큰 값을 왼쪽으로
- 블록 하나의 메모리: 1,000 × 81,080 × 4바이트 ≈ 324MB

**근사 방법(ANN).** 책이 수백만 권이 되면 전수 비교가 느림 → H3절.

**전수 비교 대신 HNSW를 쓰면?** (사용자 질문 2026-10-29) 어디서 쓰는 검색이냐에 따라 답이 다름.

| 쓰는 곳 | 지금 방법 | 언제 계산 | HNSW가 맞나 |
|---|---|---|---|
| 책첵 추천 이웃 표 `foryou.book_neighbour` | PC 스크립트 `foryou_neighbours.py`가 8만 권 × 8만 권 전수(위 코드) | 자료를 바꿀 때 한 번(일괄). 서비스 중에는 표를 읽기만 함 | 지금은 아님. 모든 책의 이웃을 한꺼번에 구할 때는 행렬 곱이 빠르고 정확(재현율 100%). 계산 시간은 사용자 대기 시간과 상관없음 |
| AI 검색 질문 → 책 | 서버 `BookVectorFile.nearest`가 질문 하나마다 메모리의 81,078줄 int8 벡터를 전부 내적(B7) | 질문마다(실시간) | 책이 수십만~백만 권으로 늘면 후보. 질문 하나를 바로 찾는 일이 HNSW가 강한 곳 |

- HNSW는 **근사**: 일부 정답을 놓칠 수 있어 recall@k를 재고 씀(H3)
- DB에 넣는다면 PostgreSQL 확장 pgvector의 HNSW 인덱스. 지금 운영 DB(`docker/postgres-bigm`, pg_bigm만)에는 pgvector가 없음 → 이미지에 확장을 더해야 함. 벡터만 81,080 × 1,024 × 4바이트 ≈ 332MB(계산값) + 인덱스. 운영 디스크 남은 곳 9.2G(v1.20.2 배포 때)
- 새 책을 바로 이웃에 넣어야 할 때(일괄 계산을 기다릴 수 없을 때)도 DB 인덱스가 맞음. 지금은 그런 요구가 없음
- 전수 계산과 HNSW의 걸린 시간은 **재 본 적 없음**. 바꾸기 전에 같은 표본으로 시간·recall@20을 재야 함

**한 줄로.** "8만 권 × 8만 권 코사인을 1,000행씩 행렬 곱으로 계산하고, `argpartition`으로 행마다 상위 200개만 골라 정렬했다."

## A4. 순위 평가 지표: Hit@k, Precision@k, nDCG@k [평가용]

추천 결과(앞 k권)와 정답 집합을 비교. 우리 맞힘 시험에서 정답은 「같은 추천목록 묶음의 나머지 책」(3-evaluation.md 1-2).

- **Hit@k** = 앞 k권에 정답이 1권이라도 있으면 1, 없으면 0 → 시나리오 평균
- **Precision@k** = (앞 k권 중 정답 수) / k
- **nDCG@k**(정규화 할인 누적 이득): 위에 있을수록 더 쳐줌
  ```
  DCG@k  = Σ_{i=1..k} rel_i / log₂(i + 1)      (rel_i: i번째가 정답이면 1)
  IDCG@k = 정답을 맨 앞에 몰아 넣었을 때의 DCG
  nDCG@k = DCG@k / IDCG@k   (0~1)
  ```
  예: 정답 2권, 결과에서 1번째와 4번째가 정답 → DCG = 1/log₂2 + 1/log₂5 = 1 + 0.431 = 1.431, IDCG = 1/log₂2 + 1/log₂3 = 1 + 0.631 = 1.631 → nDCG = 0.877

**우리 코드** (`scripts/foryou/eval/offline_eval.py`, 자리 i를 0부터 셈):
```python
def ndcg(recs, rel):
    dcg = sum(1 / math.log2(i + 2) for i, r in enumerate(recs) if r in rel)
    ideal = sum(1 / math.log2(i + 2) for i in range(min(len(rel), K)))
    return dcg / ideal if ideal else 0.0
```

**함정(우리가 겪은 것).** 정답이 「추천목록 책」뿐이라, 목록 밖의 좋은 추천은 0으로 셈 → 대상을 3만에서 10만 권으로 넓혔더니 Hit@20이 31.8 → 15.5%로 「나빠진 것처럼」 보였지만 AI 판정은 오히려 좋아짐(E3). **지표의 정답 정의가 결론을 바꿀 수 있음.**

## A5. 다양성 지표: 엔트로피, 목록 안 유사도 [평가용]

- **섀넌 엔트로피**: 분포가 얼마나 고르게 퍼졌나
  ```
  H = − Σ_g p_g · ln p_g     (p_g: 앞 20권 중 분야 g의 비율)
  정규화 H_norm = H / ln G   (G = 6 분야, 0 = 한 분야뿐, 1 = 6분야 똑같이)
  ```
  예: 20권이 소설 10, 과학 10 → H = −2 × 0.5 ln 0.5 = 0.693 → H_norm = 0.693 / ln 6(1.792) = 0.387
- **목록 안 유사도(intra-list similarity)**: 20권끼리 코사인 평균 `(Σ_{i≠j} cos(i,j)) / (20×19)`. 우리 시험에서는 방식끼리 0.44~0.46로 차이가 작아 질림을 가르는 데 약했음

**우리 코드** (`offline_eval.py`):
```python
def entropy(recs):
    c = Counter(guessed(r) for r in recs)
    n = sum(c.values())
    h = -sum(v / n * math.log(v / n) for v in c.values())
    return h / math.log(len(GROUPS))
```

## A6. LLM 판정(LLM-as-a-judge), 방식 가리기 [평가용]

**원리.** 사람 평가 대신 언어 모델에게 (기준 책, 추천 책) 쌍을 보여 주고 1~5점을 매기게 함. 사람보다 싸고 빠르지만 모델 하나의 눈이고 실행마다 흔들림.

**편향을 줄이는 방법(우리가 한 것)**
1. **방식 가림(blind)**: 쌍에 어느 방식이 만든 것인지 쓰지 않음
2. **섞기(shuffle)**: 여러 방식의 쌍을 무작위로 섞어 한 파일에. 순서 효과·지침 피로를 방식 사이에 고르게 퍼뜨림
3. **같은 실행 안에서만 비교**: 같은 방식이 실행마다 3.90 / 3.58로 달랐음 → 방식 A와 B를 꼭 한 실행에 함께 넣음
4. **점별(pointwise) 점수**: 쌍마다 따로 점수. (다른 방법: 두 추천을 나란히 보여 주고 어느 쪽이 나은지 묻는 쌍별(pairwise) 비교 — 흔들림이 더 적지만 쌍 수가 늘어남)

**우리 판정 지시문**(3-evaluation.md 1-4): "A를 읽고 좋아한 독자에게 B를 추천하면 얼마나 좋은가 1~5점, 같은 작품이면 1". Haiku 하위 에이전트 250줄씩.

**한 줄로.** "사용자 기록이 없어서, 방식을 가리고 섞은 쌍을 LLM이 채점하게 했고, 실행마다 흔들리므로 비교할 방식은 반드시 같은 실행에 넣었다."

---

# B. 지금 동작하는 것의 코드와 원리

## B1. 본 책 점수와 기준 책 (`web/src/features/picks/viewedBooks.ts`) [운영]

**원리.** 암묵적 피드백(implicit feedback): 사용자가 별점을 주지 않아도 행동(열기·머물기·도서관 누름)에 무게를 줘 관심의 세기로 봄. 도서관을 누른 것은 빌리려는 뜻이라 가장 무겁게.

```ts
export function points(book: ViewedBook): number {
  return (book.opened ? 1 : 0) + (book.dwelled ? 2 : 0) + (book.libraryClicked ? 3 : 0)
}
export function baseBooks(books: ViewedBook[], now = Date.now()): ViewedBook[] {
  const candidates = recentBooks(books, now)            // 빼지 않은 책, 점수 순(같으면 최신), 상위 10
  const picked: ViewedBook[] = []
  const perGroup = new Map<string, number>()
  for (const book of candidates) {                      // 분야당 2권(BASE_PER_FIELD)까지, 6권(BASE_COUNT)
    if (picked.length === BASE_COUNT) break
    const group = book.fieldGroup ?? 'other'
    const count = perGroup.get(group) ?? 0
    if (count < BASE_PER_FIELD) { picked.push(book); perGroup.set(group, count + 1) }
  }
  for (const book of candidates) {                      // 모자라면 점수 순으로 채움
    if (picked.length === BASE_COUNT) break
    if (!picked.includes(book)) picked.push(book)
  }
  return picked.sort(byPoints)
}
```
**분야당 2권 상한**은 「기준 책이 한 분야에 몰리면 추천도 한 분야에 몰림」을 막는 다양성 제약(constraint). 점수가 아니라 개수 제한이라 계산이 단순하고 설명하기 쉬움.

## B2. 이웃 계산과 같은 작품 건너뛰기 (`scripts/crawl/foryou_neighbours.py`) [운영]

A3의 블록 계산 뒤, 행마다:
```python
picked = []
for j in itertools.chain(near[k][np.argsort(-block[k][near[k]])], np.argsort(-block[k])):
    n = books[j]
    if n[0] == b[0] or (b[5] and n[5] == b[5]) or same_work(n, b):
        continue                       # 자기 자신, main_title_norm이 같은 판, 같은 작품
    if any(same_work(n, p) for p, _ in picked):
        continue                       # 이미 고른 이웃과 같은 작품(작품마다 한 판)
    if any(p[0] == n[0] for p, _ in picked):
        continue                       # 200개 뒤 전체 정렬로 넘어가면 같은 책을 다시 봄
    picked.append((n, float(block[k][j])))
    if len(picked) == NEIGHBOURS:      # 20
        break
```
`itertools.chain`으로 「상위 200개를 먼저, 모자라면 전체 정렬」을 한 줄로 이음. 대부분 200개 안에서 끝나 빠름.

## B3. 같은 작품 규칙 (`work_key`, `first_author`, `same_work`) [운영: 규칙 · 미적용: 한 곳으로 통일]

**원리.** 엔티티 해석(entity resolution): 다른 레코드가 같은 실체(같은 작품)를 가리키는지 판단. 여기서는 **규칙 기반**: 제목을 정규화한 열쇠와 저자로.

```python
def work_key(title):
    bare = _BRACKETS.sub(' ', title)                                  # 괄호와 안 글자
    bare = re.split(r'\s*:\s*|\s+-\s*|\s*-\s+', bare)[0]              # 부제
    bare = _YEAR.sub('', bare.strip())                                 # 앞뒤 연도
    for _ in range(2):
        bare = _TAIL_NUMBER.sub('', bare.strip())                      # 권 번호, volume N
    key = re.sub(r'[\W_]+', '', bare).lower()
    return key or re.sub(r'[\W_]+', '', title).lower()

def same_work(a, b):
    ka, kb = work_key(a[1]), work_key(b[1])
    x, y = first_author(a[2]), first_author(b[2])
    authors_agree = not x or not y or x in y or y in x
    if ka == kb:
        return authors_agree
    if not (x and y and authors_agree):
        return False
    short, long_ = sorted((ka, kb), key=len)
    if len(short) >= 3 and short in long_:                             # 제목 포함
        return True
    return (len(os.path.commonprefix([ka[::-1], kb[::-1]])) >= 4      # 끝 4자+
            and len(os.path.commonprefix([ka, kb])) >= 2)              # 앞 2자+
```
- `os.path.commonprefix`는 문자열 목록의 공통 접두사. 문자열을 뒤집어(`[::-1]`) 넣으면 **공통 접미사**
- 「앞 2자」 조건이 없으면 「그리스신화이야기」와 「로마신화이야기」(끝 「신화이야기」 5자 공통)가 같은 작품이 됨 → 시험 `test_foryou_neighbours.py`로 막음
- 정밀도(잘못 묶지 않음)를 우선한 규칙. 재현율(다 잡음)은 낮음: 제목이 완전히 다른 번역판은 못 잡음 → G 절

**같은 작품 판정이 여러 곳에 따로 있음** (사용자 2026-10-29 「일괄적으로 할 필요 있는데.. 가져오기라던가」):

| 곳 | 규칙 | 쓰는 데 |
|---|---|---|
| PC `scripts/crawl/foryou_neighbours.py` `same_work` | 위 W2(괄호·부제·연도·권 번호를 뗀 `work_key` + 첫 저자, 제목 포함·앞뒤 일치) | 이웃 표를 만들 때 같은 작품 건너뛰기 |
| 서버 `catalog` `JdbcBookCards.workKeys` | `catalog.book.main_title_norm`이 같고 `catalog.book_contributor.name_exact`(AUTHOR·UNKNOWN) 하나를 공유 → 묶음(이름 사슬로 이어짐) | 책첵 추천 후보에서 같은 작품 하나만, 기준 책의 작품 빼기, 새벽 점수, 추천목록 줄의 다른 판 표지(`BookTexts.listCoverUrls`) |
| 서버 AI 검색 `NamedBook` | 사용자가 이름을 댄 책의 제목 열쇠로 판들을 모음 | 「○○ 같은 책」에서 그 책의 판들 빼기 |

같은 두 책이 어느 곳에서는 같은 작품, 다른 곳에서는 다른 작품이 될 수 있음. **통일안**: 책 목록을 가져올 때(`catalog` 가져오기) 한 규칙으로 작품 번호를 계산해 `catalog`에 칸으로 두고, 위 세 곳이 모두 그 칸만 읽음. 규칙을 보강(I-14 쌍 분류기, G 절)해도 그 칸 하나만 바뀜. `catalog` 표 구조와 가져오기는 사이클 26 영역이라 **plan 결정**(`docs/cycles/plan-feedback.md`에 기록)

## B4. 서버 순서: 기준 책을 번갈아 (`backend/.../foryou/internal/web/SimilarOrder.java`) [운영]

**원리.** 라운드 로빈 병합(round-robin merge). 기준 책마다 이웃 목록(가까운 순)이 있을 때, 한 기준 책의 이웃만 앞을 차지하지 않게 순위별로 돌아가며 뽑음. 각 기준 책을 공평하게 대표하면서, 순위가 높은(더 비슷한) 책이 먼저.

```java
static List<Long> byCloseness(List<Long> base, Map<Long, int[]> neighbours) {
    Set<Long> out = new LinkedHashSet<>();            // 넣은 차례를 지키며 중복 제거
    int longest = 0;
    for (long bookId : base) {
        longest = Math.max(longest, neighbours.getOrDefault(bookId, new int[0]).length);
    }
    for (int rank = 0; rank < longest; rank++) {
        for (long bookId : base) {
            int[] near = neighbours.getOrDefault(bookId, new int[0]);
            if (rank < near.length) {
                out.add((long) near[rank]);
            }
        }
    }
    return List.copyOf(out);
}
```
예: 기준 A의 이웃 [a1, a2, a3], B의 이웃 [b1, a1, b3] → [a1, b1, a2, b3, a3] (a1은 이미 있어 B의 2위 자리에서 건너뜀).

## B5. 화면 규칙 (`web/src/features/picks/forYouOrder.ts`) [운영]

- **분야 돌려 놓기 `mixFields`**: 분야별 큐(queue)로 나눈 뒤 0번째, 1번째 … 자리를 차례로 꺼냄(라운드 로빈). 본 책이 3권 미만일 때만
  ```ts
  for (let i = 0; mixed.length < cards.length; i++) {
    for (const queue of queues) { if (i < queue.length) mixed.push(queue[i]) }
  }
  ```
- **같은 저자 3연속 금지 `limitAuthorRuns`**: 탐욕(greedy) 배치 — 앞 2권과 저자가 같으면 다음 후보로
  ```ts
  while (waiting.length > 0) {
    const index = waiting.findIndex((card) => !wouldRun(placed, card))
    placed.push(waiting.splice(index === -1 ? 0 : index, 1)[0])
  }
  ```
- **안 본 분야 한 권 `withSurprises`**: 탐색(exploration). 10번째부터 15자리마다, 본 책 기록에 없는 분야의 첫 책을 그 자리로 옮김. 강화학습의 「ε-탐욕(epsilon-greedy)」과 같은 생각 — 대부분은 아는 것(이용, exploitation), 가끔은 모르는 것(탐색). ε-탐욕은 무작위 확률로 하고, 우리는 **정해진 자리**에서 함(재현 가능, 설명 쉬움)

## B6. 분야 짐작: 이웃 다수결 (`ForYouController.guessFieldGroups`) [운영]

**원리.** k-최근접 이웃 분류(kNN classification)의 가장 단순한 꼴: 라벨이 없는 점의 라벨을, 라벨 있는 이웃들의 다수결로.
```java
bookTexts.neighbours(off).forEach((id, neighbours) -> {
    Map<String, Integer> votes = new HashMap<>();
    for (int neighbour : neighbours) {
        ListedRef ref = listed.get((long) neighbour);
        if (ref != null && ref.fieldGroup() != null) votes.merge(ref.fieldGroup(), 1, Integer::sum);
    }
    int best = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    List<String> top = votes.entrySet().stream().filter(e -> e.getValue() == best).map(Map.Entry::getKey).toList();
    if (best >= MIN_GROUP_VOTES && top.size() == 1) guessed.put(id, top.getFirst());   // 2표 이상, 단독 1위
});
```
한계: 라벨 있는 이웃(추천목록 책)이 드물면 표가 안 모임 → 10만 권 판에서 6.7%만. D 절의 분류기가 이것을 대신하려는 것.

**짐작한 분야는 어디에 쓰나.** 카드 응답의 `fieldGroup` 칸(추천목록 책은 목록의 분야, 목록 밖 책은 이 짐작)으로 웹 세 곳에서:
1. 위쪽 분류 칩 `web/src/features/picks/forYouChips.ts`: 카드에 있는 분야만 칩으로 보이고, 칩을 누르면 `card.fieldGroup === 칩 분야`인 카드만 남김
2. 처음 순서 섞기 `forYouOrder.ts`(B5): 본 책이 3권 미만이면 분야를 번갈아(`mixFields`), 3권 이상이면 본 적 없는 분야 책을 정해진 자리에(`withSurprises`)
3. 본 책 기록 `useBookVisit.ts` → `viewedBooks.ts`: 책 창을 열면 그 책의 분야를 함께 적고, 2번의 「본 분야」로 씀

짐작을 못 한 책(`fieldGroup = null`)은 「전체」에만 나오고, 섞을 때는 「기타」 묶음, 「안 본 분야」 후보에서는 빠짐.

## B7. int8 벡터와 질문 검색 (AI 검색, 참고) [운영: AI 검색]

**원리.** 양자화(quantization): float32(4바이트)를 int8(1바이트)로 줄여 메모리를 1/4로. 행마다 자기 배율(scale)을 둠.
```python
scales = np.abs(matrix).max(axis=1) / 127.0                     # 행마다 가장 큰 절댓값이 127이 되게
q = np.round(matrix / scales[:, None]).clip(-127, 127).astype(np.int8)
# 복원: q × scale. 24 세션 실측 최악 코사인 0.99975 (거의 손실 없음)
```
서버(`BookVectorFile.nearest`)는 질문 벡터와 int8 행의 내적에 배율을 곱해 점수를 내고, **크기 limit의 최소 힙(min-heap)**으로 상위 limit개만 유지: 새 점수가 힙의 최솟값보다 크면 최솟값을 빼고 넣음 → O(n log limit).

## B8. 새벽 인기 점수 (운영에서 꺼짐, `foryou/internal/build/Scoring.java`) [꺼짐]
재료마다 0~100으로 맞춤(최댓값 = 100, 순위는 `101 − 순위`) → `총점 = 최근 수요 × 출간연도 가산 + 판매 순위 × 1.5 + 200위 목록 + KLWAVE × 0.3`, 최근 수요 = 대출 + 2 × 예약(도서관 인기순 화면). 여러 판의 점수는 대표 판에 더함. 지금은 `CHAEKCHECK_FORYOU_BUILD_ENABLED=false`(I-15).

---

# C. 순서 고치기의 수학 (I-01, I-02, I-17)

## C1. 순위 합치기: RRF(Reciprocal Rank Fusion) [책첵 추천: 시험 후 버림 · AI 검색: 운영]
여러 목록의 순위를 합치는 법. 점수 크기가 다른 목록도 순위만으로 합칠 수 있음.
```
RRF(d) = Σ_{목록 m} 1 / (k + rank_m(d))     (k: 상수, 보통 60; 우리 시험은 10)
```
예(k = 10): 책 d가 기준 A 목록 1위, B 목록 3위 → 1/11 + 1/13 = 0.168. 두 목록 모두 상위인 책이 위로. 우리 시험(E2)에서는 번갈아 방식보다 Hit가 낮아 안 씀. AI 검색은 낱말 검색과 질문 벡터 검색 후보를 RRF로 합침(사이클 24).

## C2. MMR(Maximal Marginal Relevance): 겹침 줄이기 [시험 후 버림]
관련성과 다양성의 균형을 탐욕적으로:
```
다음 책 = argmax_{d ∉ 고른 것} [ λ · sim(d, 질의) − (1 − λ) · max_{s ∈ 고른 것} sim(d, s) ]
```
λ = 1이면 관련성만, 0이면 다양성만. 우리 시험(E2): 이웃 그래프로 흉내 낸 「겹침 줄이기」(이미 고른 책의 이웃 앞 5위·같은 저자를 뒤로)는 판정 54.4%로 가장 낮았고, 벡터 MMR(λ = 0.7)은 오프라인 지표만 재고 판정은 하지 않음 → 둘 다 버림(I-17). **이유: 판정이 「쌍마다 얼마나 맞나」를 재는데, MMR은 덜 맞는 책을 앞으로 올리기 때문.** 다양성의 이득은 쌍 판정에 안 잡힘 — 다양성을 원하면 화면 규칙(B5)처럼 순서는 두고 일부 자리만 바꾸는 편이 손해가 적음.

## C3. 가중합 점수 vs 문턱(필터) (I-01) [운영: 문턱 · 시험 후 버림: 가중합]

**가중합**(E6에서 시험): 
```
score(d) = −(유사도 순 자리) + λ · instant(d) + μ · ln(1 + 내 도서관 수(d))
```
- **가중합(weighted sum)**: 단위가 다른 여러 값에 무게(λ, μ)를 곱해 더한 하나의 점수로 줄 세우는 방법. 여기서 쓰지 않음 — **E6 오프라인 시험(`scripts/foryou/eval/avail_eval.py`)에서만** 계산했고 운영 코드에는 없음(5-improvements I-17 「버림」)
- `instant(d)`: 책 d를 가진 **내 계획 도서관** 중 `library.library.lending_model`이 `SUBSCRIPTION`·`RENTAL`(대출 수 제한 없음)인 곳이 있으면 1, 없으면 0. 단 E6 시험 때는 `platform_type = KYOBO_SUBSCRIPTION_V1`로 셌음(3-evaluation.md E6·E7-2에서 바로잡음). 운영의 문턱은 `lending_model` 기준
- `λ · instant(d)`: 바로 빌릴 수 있는 책에 주는 덤. 점수의 단위가 「유사도 순 자리」이므로 λ = 8이면 그 책이 **8자리 앞으로** 당겨짐
- `μ · ln(1 + 내 도서관 수)`: 가진 도서관이 많을수록 주는 덤(μ = 3이면 1곳 → 2.1자리, 3곳 → 4.2자리, 30곳 → 10.3자리)
- `ln(1 + x)`: 도서관 수 1 → 2는 큰 차이, 30 → 31은 작은 차이로 보려는 **체감(diminishing return)** 함수
- 결과: λ = 8, μ = 3에서 바로 대출 48 → 59%, 판정 −5.6%p. 모든 책의 순서를 조금씩 흔들어 비슷함을 해침

**문턱**(E7, 결정):
```
통과(d) = instant(d) ∨ (내 도서관 수(d) ≥ 3)
결과 = [통과한 책을 유사도 순] (+ 모자라면 못 넘은 책을 뒤에)
```
- 「충분히 빌릴 만한 책」이라는 **이진 조건**으로 나눈 뒤 그 안에서는 동등(사용자의 말 「문턱을 넘은 것끼리는 동등하게」). 순서 정보(비슷함)를 그대로 보존
- 결과: 바로 대출 48 → 61%, 1곳뿐 11% → 0%, 판정 손해 없음
- 일반 원리: **제약 조건으로 거르고(필터) 목적 함수로 정렬(순위)** — 둘을 한 점수에 섞으면 단위가 다른 것을 저울질해야 하고(λ, μ를 정해야 함) 설명도 어려움

**지금 상태 (2026-10-29, v1.20.2).** 책첵 추천 개인 추천은 이 원리대로 동작함. 가중합은 없음.

| 차례 | 하는 일 | 종류 | 코드 |
|---|---|---|---|
| 1 | 기준 책별 이웃(가까운 순)을 순위별로 번갈아 모음 | 정렬(비슷함 하나만) | `SimilarOrder.byCloseness`(B4) |
| 2 | 기준 책과 같은 작품, 같은 작품 두 판 중 하나를 뺌 | 거르기 | `ForYouController.similarBooks` + `BookCards.workKeys` |
| 3 | 계획 도서관이 한 곳도 없는 책을 뺌 | 거르기 | `ForYouController.cards` |
| 4 | 문턱을 넘은 책 앞, 못 넘은 책 뒤(각각 1의 차례 그대로) | 거르기(안정 분할) | `SimilarOrder.availableFirst` |
| 5 | 60권에서 자름 | — | `MAX_SIMILAR` |

- 순수한 거르기와 다른 점 하나: 4에서 못 넘은 책을 **버리지 않고 뒤에 붙임**. 넘은 책이 적은 지역에서도 목록이 비지 않게. 운영 측정(16개 시·도, 가입 가능 범위)에서 앞 27~34권이 넘은 책, 뒤 4~8권이 1~2곳 책
- 웹의 B5 규칙(분야 번갈아, 같은 저자 3연속 금지, 안 본 분야 한 권)은 점수를 더하지 않고 자리만 옮기는 규칙
- 참고: AI 검색은 다른 기능으로, 낱말 후보와 벡터 후보를 RRF(C1, 순위 역수의 합)로 합치고 ML 1차 판단의 덤을 더함(`AiSearchFlow`의 `Fusion.rrf`) → 점수를 더하는 방식

**시험 코드** (`scripts/foryou/eval/threshold_eval2.py`, 핵심):
```python
inst = {i for i in plannable if libs[i][1] in ('SUBSCRIPTION', 'RENTAL')}   # 바로 대출 도서관
def rows_for(bases, plan, inst):
    out = []
    for n, pc, _ in ae.eligible(ae.rr(bases), bases, plan):    # pc = |library_ids ∩ plan|
        out.append((n, pc, bool(ae.libs.get(n, set()) & plan & inst)))
    return out
ok = [r for r in rows if r[2] or r[1] >= T]                   # 문턱 T
top = (ok + [r for r in rows if not (r[2] or r[1] >= T)])[:20]
```

**서버 코드(I-01, 2026-10-29 구현, v1.20.2 운영)** — `backend/.../foryou/internal/web/SimilarOrder.java`:
```java
static final int MIN_PLAN_LIBRARIES = 3;

static List<Long> availableFirst(List<Long> picks, Map<Long, long[]> libraryIds, Set<Long> planIds,
        Set<Long> instantLibraryIds) {
    List<Long> passed = new ArrayList<>(picks.size());
    List<Long> rest = new ArrayList<>();
    for (long pick : picks) {
        int inPlan = 0;
        boolean instant = false;
        for (long libraryId : libraryIds.getOrDefault(pick, new long[0])) {   // catalog.book.library_ids
            if (planIds.contains(libraryId)) {
                inPlan++;
                instant |= instantLibraryIds.contains(libraryId);
            }
        }
        (instant || inPlan >= MIN_PLAN_LIBRARIES ? passed : rest).add(pick);
    }
    passed.addAll(rest);            // 통과한 책이 모자랄 때만 뒤에서 보임
    return passed;
}
```
`ForYouController`는 계획 도서관(`SearchTarget`) 중 `lendingModel`이 `SUBSCRIPTION`·`RENTAL`인 것을 `instantLibraryIds`로 넘김. 시간 복잡도는 (후보 수 × 책당 소장 도서관 수), 후보 ≤ 120권이라 무시할 만함. 두 목록으로 나눠 이어 붙이는 것은 **안정 분할(stable partition)** — 각 부분 안의 원래 차례(유사도 순)를 그대로 지킴.

---

# D. 지도학습 (I-11: 책 프로필 분류기)

이 절의 숫자는 모두 **실제로 돌린 예제** `scripts/foryou/eval/ml_basics_demo.py`(2026-10-29)의 결과입니다. 자료와 조건:
- 입력 `x`: `chaekcheck-data/aisearch/100k/book-vectors.bin`의 책 벡터(KURE-v1 1,024차원, int8 × 줄마다 scale로 되살림, 길이 1)
- 정답 `y`: 같은 폴더 `profiles-detailed.jsonl`의 Haiku 분야(`field`, 22종)
- 둘 다 있는 책 **50,324권**. 같은 작품(B3의 `work_key` + `first_author`)은 한 묶음으로 보고 묶음째 나눔(D5) → 학습 26,838 / 보정 13,424 / 시험 10,062권
- 나눔 한 번, 설정 고르기 없음. **I-11의 결과가 아니라 원리를 보이는 예제**. 돌리는 데 numpy, scikit-learn 1.9.1(저장소에 고정한 환경 없음)

## D1. 지도학습이란 [미적용 · 예제만]
「문제(x)와 정답(y)이 짝지어진 예」를 많이 보여 주고, x만 보고 y를 맞히는 함수 f를 찾는 것. 사람이 정답을 「지도」해 준다고 해서 지도학습.

| 우리 경우 | 무엇 |
|---|---|
| x | 책 벡터 1,024개 숫자 |
| y | Haiku가 붙인 분야 22개 중 하나 |
| f를 찾는 데 쓰는 책 | 학습 자료(26,838권) |
| f가 잘하는지 재는 책 | 시험 자료(10,062권). 학습 때 **한 번도 안 본** 책이어야 함 |
| f를 쓰는 곳(I-11) | Haiku 프로필이 없는 책의 분야 맞히기 |

자료를 나누는 코드(D5에서 자세히):
```python
from sklearn.model_selection import StratifiedGroupKFold
train, test = next(StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=0).split(X, y, groups))
```

## D2. 로지스틱 회귀(다항, softmax) [미적용 · 예제만]

**모델.** 분야마다 가중치 벡터 w_c(1,024개)와 절편 b_c가 있음. 책 벡터 x와 내적해 분야마다 점수를 냄:
```
z_c = w_c · x + b_c                       (분야 c의 점수 = 로짓, 22개)
P(y = c | x) = exp(z_c) / Σ_k exp(z_k)    (softmax: 22개 점수를 합이 1인 확률로)
```
- 예: 점수가 [소설 3.0, 에세이 2.0, 역사 0.5]면 exp → [20.1, 7.4, 1.6], 합 29.1로 나눠 [0.69, 0.25, 0.06]
- 「직선(초평면)으로 공간을 나누는」 모델: 점수가 x에 대한 1차식

**학습 = 손실을 줄이는 w 찾기.** 교차 엔트로피(로그 손실):
```
L = − (1/N) Σ_i ln P(y = y_i | x_i)  +  (1/(2C)) Σ_c ‖w_c‖²
```
- 앞 항: 정답 분야에 준 확률이 1이면 ln 1 = 0(손실 없음), 0.1이면 −ln 0.1 = 2.3(큰 손실)
- 뒤 항: L2 규제. 가중치가 너무 커지지 않게 벌점(C가 작을수록 셈). 외우기(과적합) 방지
- 이 L은 볼록(그릇 모양) → 바닥이 하나라 lbfgs(기울기와 곡률 근사로 내려가는 방법)로 확실히 찾음

**코드와 결과** (`ml_basics_demo.py` 4번):
```python
from sklearn.linear_model import LogisticRegression
lr = LogisticRegression(max_iter=1000, C=1.0, class_weight='balanced')   # class_weight는 D4
lr.fit(X[fit], y[fit])                 # 학습: w, b를 찾음
p_lr = lr.predict_proba(X[test])       # (10062, 22) 책마다 분야별 확률
pred = lr.classes_[p_lr.argmax(1)]     # 가장 큰 확률의 분야를 답으로
```
결과: **정확도 0.688, macro-F1 0.613**, 학습 72초.

## D3. MLP(다층 퍼셉트론) [책첵 추천: 미적용 · AI 검색 1차 판단: 운영]

**모델.** 로지스틱 회귀 앞에 「중간 층(은닉층)」을 하나 더 둠:
```
h = ReLU(W₁ x + b₁)          1,024 → 256      ReLU(t) = max(0, t)
z = W₂ h + b₂                256 → 22
P(y | x) = softmax(z)
```
- W₁(256 × 1,024)은 책 벡터에서 「분야를 가르기 좋은 특징」 256개를 새로 만듦. ReLU가 음수를 0으로 잘라 **꺾인 선**을 만들 수 있게 함 → 직선 하나로 못 가르는 경계도 배움(비선형)
- ReLU가 없으면 W₂(W₁x) = (W₂W₁)x로 다시 1차식 → 로지스틱 회귀와 같아짐

**학습.**
- **역전파(backpropagation)**: 손실 L을 각 가중치로 미분한 값(기울기)을 출력 쪽에서 입력 쪽으로 연쇄 법칙(∂L/∂W₁ = ∂L/∂z · ∂z/∂h · ∂h/∂W₁)으로 차례로 구함
- **Adam**: 기울기 반대 방향으로 조금씩 옮김(w ← w − 학습률 × 보정된 기울기). 한 번에 자료 전부가 아니라 작은 묶음(미니배치)씩 → 「확률적」 경사 하강
- **볼록이 아님**: 바닥이 여럿이라 시작값(random_state)에 따라 결과가 조금 다름
- **과적합 막기**: 조기 종료(학습 자료 일부를 떼어 두고, 거기서 점수가 더 안 좋아지면 멈춤), 드롭아웃(학습 중 은닉 칸 일부를 무작위로 0으로)

**scikit-learn 코드와 결과** (`ml_basics_demo.py` 8번):
```python
from sklearn.neural_network import MLPClassifier
mlp = MLPClassifier(hidden_layer_sizes=(256,),   # 은닉층 1개, 256칸, ReLU(기본), Adam(기본)
                    early_stopping=True,         # 학습 자료의 10%를 떼어 두고 나빠지면 멈춤
                    max_iter=200, random_state=0)
mlp.fit(X[fit], y[fit])
p_mlp = mlp.predict_proba(X[test])
```
결과: **정확도 0.719, macro-F1 0.603**, 학습 24초(18바퀴에서 멈춤). 로지스틱 회귀보다 정확도 +3.1%p, macro-F1 −1.0%p(이 MLP는 D4의 클래스 가중치를 안 줌).

**PyTorch로 쓴 실제 코드** — AI 검색 1차 모델 시험(사이클 24, `scripts/aisearch/eval/mlp.py`의 `net`). 같은 구조를 손으로 쓴 것:
```python
m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, n_labels))
opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4)   # Adam + L2 비슷한 규제
lossf = nn.BCEWithLogitsLoss()            # 칸마다 「있다/없다」(여러 개 정답 가능)라 softmax 대신 sigmoid
for _ in range(epochs):                   # 바퀴(epoch)
    perm = torch.randperm(len(X))         # 매 바퀴 순서를 섞음
    for i in range(0, len(X), 64):        # 64권씩 미니배치
        b = perm[i:i + 64]
        opt.zero_grad()                   # 지난 기울기 지움
        lossf(m(X[b]), Yt[b]).backward()  # 손실 계산 → 역전파로 기울기
        opt.step()                        # 가중치 한 걸음 옮김
```

## D4. 불균형과 macro-F1 [미적용 · 예제만]

**문제.** 분야 분포가 심하게 치우침(소설 21% … 청소년 0.17%, 6-ml-method.md 0절). 큰 분야만 맞혀도 정확도가 높게 나옴.

**대응 1: 클래스 가중치.** 드문 분야를 틀리면 손실을 더 크게:
```
w_c = N / (C × N_c)        (scikit-learn class_weight='balanced', C = 분야 수)
```
청소년(87권)은 소설(10,546권)보다 약 120배 무거움.

**대응 2: 지표.** 분야마다 F1을 구해 단순 평균(macro):
```
정밀도 P_c = 맞게 c라 한 수 / c라 한 수        (c라고 한 것 중 맞은 비율)
재현율 R_c = 맞게 c라 한 수 / 실제 c인 수       (실제 c 중 찾아낸 비율)
F1_c = 2 P_c R_c / (P_c + R_c)
macro-F1 = (1/C) Σ_c F1_c                      (작은 분야도 똑같이 1표)
```
```python
from sklearn.metrics import accuracy_score, f1_score, classification_report
accuracy_score(y[test], pred)                 # 정확도
f1_score(y[test], pred, average='macro')      # macro-F1
print(classification_report(y[test], pred))   # 분야별 정밀도·재현율·F1
```
예제 결과로 보는 차이: kNN·MLP(가중치 없음)는 정확도가 높고(0.712·0.719) macro-F1이 낮음(0.589·0.603). 가중치를 준 로지스틱 회귀는 정확도가 낮고(0.688) macro-F1이 가장 높음(0.613) → 큰 분야 몇 개를 덜 맞히는 대신 작은 분야를 더 맞힘. 24 세션의 교훈: 학습 자료에 드문 유형이 1% 미만이면 그 유형 정확도가 무너짐(58.5% → 고르게 넣자 94.5%).

## D5. 데이터 누수와 그룹 분할 [미적용 · 예제만]

**문제.** 같은 작품의 다른 판은 벡터가 거의 같고 분야도 같음. 한 판은 학습에, 다른 판은 시험에 들어가면 모델이 「본 적 있는 답」으로 맞혀 점수가 부풀려짐 = **누수(leakage)**. 시험이 「처음 보는 책」을 흉내 내지 못함.

**대응.** 같은 작품 묶음을 통째로 한쪽에만 두는 **그룹 분할**. 분야 비율도 학습·시험에서 비슷하게(층화) 하려면 `StratifiedGroupKFold`.
```python
from foryou_neighbours import work_key, first_author
gid = {}
groups = np.array([gid.setdefault((work_key(title[b]), first_author(author[b])), len(gid)) for b in books])
cv = StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=0)   # 5등분, 1등분이 시험
train, test = next(cv.split(X, y, groups))
```
예제: 50,324권이 45,919묶음 → 2권 이상인 묶음의 책들이 한쪽에만 들어감.

## D6. 확률 보정과 ECE [미적용 · 예제만]

### 무엇이 문제인가: 과신과 과소신
모델은 답과 함께 「얼마나 확신하는지」를 확률로 냄. 예: 「이 책은 소설, 0.9」.
- 이 숫자가 **믿을 만하다(보정됐다)** = 모델이 0.9라고 한 책을 1,000권 모으면 그중 약 900권이 실제로 맞음
- **과신(overconfidence)**: 0.9라고 했는데 실제로는 70%만 맞음 → 숫자가 실력보다 큼
- **과소신(underconfidence)**: 0.6이라고 했는데 실제로는 80% 맞음 → 숫자가 실력보다 작음

왜 중요한가: D7에서 「확신 0.9 이상인 책만 기계로, 나머지는 Haiku로」처럼 **숫자로 문턱을 정함**. 숫자가 틀리면 문턱도 틀림 — 과신이면 틀린 답을 많이 받아들이고, 과소신이면 맞힐 책까지 Haiku로 보내 돈을 씀.

### 어떻게 재나: 신뢰도 표(reliability table)와 ECE
1. 시험 책마다 「가장 큰 확률」(확신)과 「맞았나」를 적음
2. 확신으로 책을 10칸(0~0.1, …, 0.9~1.0)에 나눔
3. 칸마다 **평균 확신**과 **실제 정답률**을 비교. 보정됐으면 둘이 같음
4. 그 차이를 칸의 책 비율로 가중 평균한 것이 **ECE(기대 보정 오차)**. 0이면 완벽
```
ECE = Σ_b (칸 b의 책 수 / 전체 책 수) · | 칸 b 정답률 − 칸 b 평균 확신 |
```
```python
conf = proba.max(1)                                   # 책마다 확신
right = classes[proba.argmax(1)] == y_test            # 맞았나
ece = 0
for lo, hi in zip(np.linspace(0, 1, 11)[:-1], np.linspace(0, 1, 11)[1:]):
    inside = (conf > lo) & (conf <= hi)
    if inside.any():
        ece += inside.mean() * abs(right[inside].mean() - conf[inside].mean())
```

**실제 표** — 로지스틱 회귀(D2) 시험 10,062권:

| 확신 칸 | 책 수 | 평균 확신 | 실제 정답률 | 판정 |
|---|---|---|---|---|
| 0.3~0.4 | 1,343 | 0.352 | 0.441 | 과소신(+9%p) |
| 0.5~0.6 | 1,322 | 0.548 | 0.654 | 과소신(+11%p) |
| 0.6~0.7 | 1,154 | 0.650 | 0.781 | 과소신(+13%p) |
| 0.8~0.9 | 1,312 | 0.852 | 0.916 | 과소신(+6%p) |
| 0.9~1.0 | 1,188 | 0.942 | 0.960 | 거의 맞음 |
| 전체 ECE | | | | **0.087** |

모든 칸에서 실제가 숫자보다 높음 = **과소신**. 이유: `class_weight='balanced'`(D4)가 드문 분야를 무겁게 쳐서, 모델이 드문 분야에도 확률을 나눠 주느라 1등 확률이 낮아짐. 답(1등)은 맞는데 숫자만 작음.

### 어떻게 고치나: 보정(calibration)
「모델이 낸 숫자 → 실제 정답률」로 바꿔 주는 함수를 **따로 떼어 둔 보정 자료**(학습에도 시험에도 안 쓴 13,424권)로 배움. 답(1등 분야)은 그대로 두고 숫자만 고침.

**① 온도 조정(temperature scaling)** — 숫자 하나 T로 확률의 뾰족함을 조절:
```
P = softmax(z / T)       T > 1: 덜 뾰족(확신을 낮춤, 과신 고침)   T < 1: 더 뾰족(확신을 높임, 과소신 고침)
```
- 예: 점수 [3.0, 2.0, 0.5] → T = 1에서 1등 0.69, T = 0.8이면 z/T = [3.75, 2.5, 0.63] → 1등 0.75
- 모든 점수를 같은 수로 나누므로 순서(1등)는 안 바뀜
- T 고르기: 보정 자료에서 로그 손실(D2의 L 앞 항)이 가장 작은 T
```python
z_cal = lr.decision_function(X[cal])          # 보정 자료의 점수(로짓) z
def log_loss_at(T):
    z = z_cal / T
    z = z - z.max(1, keepdims=True)           # exp가 넘치지 않게(값은 그대로)
    logp = z - np.log(np.exp(z).sum(1, keepdims=True))   # log softmax
    return -logp[np.arange(len(y_cal)), y_cal].mean()
T = min(np.arange(0.3, 3.01, 0.05), key=log_loss_at)
```
결과: **T = 0.80**(1보다 작음 = 과소신을 고치는 쪽) → **ECE 0.087 → 0.016**. 0.6~0.7 칸이 0.650 / 0.640, 0.9~1.0 칸이 0.956 / 0.939로 숫자와 실제가 붙음.

**② isotonic 회귀** — 「확신 → 정답률」을 계단 모양 함수로 직접 맞춤. 유일한 조건은 **단조 증가**(확신이 클수록 정답률이 작아지지 않음). 모양을 정해 두지 않아 자료가 많을 때 유연.
```python
from sklearn.isotonic import IsotonicRegression
iso = IsotonicRegression(out_of_bounds='clip').fit(conf_cal, right_cal)   # x = 확신, y = 맞음(1)/틀림(0)
new_conf = iso.predict(conf_test)
```
결과: **ECE 0.087 → 0.018**.

**③ Platt(sigmoid)** — 「확신 → 정답률」을 S자 로지스틱 곡선 하나(변수 2개)로 맞춤. 자료가 적을 때.

**scikit-learn이 한 번에 해 주는 것** — 모델 학습과 보정을 묶음. 학습 자료를 5등분해 4등분으로 학습, 1등분으로 보정을 5번 하고 평균:
```python
from sklearn.calibration import CalibratedClassifierCV
cal = CalibratedClassifierCV(estimator=LogisticRegression(max_iter=2000, class_weight='balanced'),
                             method='isotonic', cv=5)    # 또는 method='sigmoid'(Platt), 'temperature'
cal.fit(X[train], y[train])
proba = cal.predict_proba(X[test])
```

**참고: MLP(D3)는 처음부터 ECE 0.012**(0.9~1.0 칸 0.959 / 0.953). 클래스 가중치가 없고 조기 종료를 해서 이 예제에서는 거의 보정돼 있었음. 「신경망은 늘 과신」이 아니라 **재 봐야 앎**.

## D7. 선택적 예측(selective prediction): 확신 높은 것만 기계로 [미적용 · 예제만]
확신(보정한 1등 확률)이 문턱 τ 이상인 책만 분류기 답을 쓰고, 나머지는 Haiku로.
```
coverage(τ) = 확신 ≥ τ 인 책의 비율                (기계가 맡는 비율)
risk(τ)     = 그 책들 중 틀린 비율
목표: risk(τ) ≤ 5% 인 가장 작은 τ → Haiku로 보낼 책 수 = (1 − coverage) × N
```
```python
conf = p_t.max(1)                                  # 온도 보정 뒤 확신
right = lr.classes_[p_t.argmax(1)] == y[test]
for tau in (0.5, 0.6, 0.7, 0.8, 0.9, 0.95):
    acc = conf >= tau
    print(tau, acc.mean(), right[acc].mean())      # coverage, 받아들인 것의 정확도
```
예제 결과(로지스틱 회귀 + 온도 보정):

| τ | 기계가 맡는 비율 | 그중 정확도 |
|---|---|---|
| 0.50 | 74.6% | 78.7% |
| 0.70 | 51.7% | 86.9% |
| 0.80 | 40.2% | 90.1% |
| 0.90 | 24.9% | 93.9% |
| 0.95 | 15.0% | 95.6% |

τ를 올리면 맡는 책은 줄고 정확도는 오름. 보정했기 때문에 τ = 0.9 칸의 정확도(93.9%)가 숫자와 가까움 → 「몇 % 틀려도 되나」로 τ를 정할 수 있음. 이 예제에서 「95% 맞히기」는 15%만 기계가 맡음 — 분류기를 더 좋게 만들거나(I-11) 목표를 정해야 함.

## D8. kNN 다수결 기준선 [운영: B6 분야 짐작 · 기준선 측정: 평가용]

**kNN(k-최근접 이웃, k-nearest neighbours).** 학습이 없는 가장 단순한 분류: 새 책과 **가장 가까운 학습 책 k권**(여기서 k = 20, 코사인 = 벡터 내적)을 찾아, 그 책들의 분야 중 **가장 많은 것**을 답으로. 「비슷한 책은 같은 분야일 것」이라는 가정 하나뿐. B6의 분야 짐작과 같은 방법.
```python
votes = np.zeros((len(test), len(classes)))
for s in range(0, len(test), 2000):                       # 2,000권씩
    sim = X[test[s:s + 2000]] @ X[train].T                 # 시험 책 × 학습 책 코사인
    near = np.argpartition(-sim, 20, axis=1)[:, :20]       # 책마다 가까운 20권(A3)
    for k, row in enumerate(near):
        np.add.at(votes[s + k], ytr[row], 1)               # 그 20권의 분야에 1표씩
pred = classes[votes.argmax(1)]
```
결과: **정확도 0.712, macro-F1 0.589**, 9초(학습 없음).

**기준선(baseline)이란.** 새 모델이 **반드시 이겨야 하는 비교 대상**. 이 예제에서 로지스틱 회귀(0.688)는 정확도로는 kNN에 짐, MLP(0.719)는 겨우 이김. macro-F1로는 둘 다 이김(0.613·0.603 > 0.589). 그러니 「분류기를 쓰면 좋아진다」는 말은 **지표와 함께** 해야 함. 6-ml-method.md 0절의 전체 자료 기준선(학습 없이 Haiku 프로필로 잰 값): 분야 70.3%(다수 칸만 찍으면 21%), 대상 80.6%(63%), 수준 82.4%(63%), 소설 여부 93.2%(73%).

---

# E. 라벨 잡음 (I-12)

## E1. confident learning(확신 기반 라벨 오류 찾기) [미적용]
**문제.** 정답(Haiku 프로필)도 틀림: 같은 작품인데 분야가 다른 경우 20.8%.

**원리.** 모델이 「자기가 학습에 안 쓴 자료」로 낸 확률(out-of-fold)을 씀. 분야 j마다 문턱 t_j = 「라벨이 j인 책들에서 P(j)의 평균」(모델이 그 분야를 평소 얼마나 확신하나). 라벨이 ỹ인 책이 다른 분야 j에 대해 P(j) ≥ t_j이고 그 j가 가장 크면, 「라벨은 ỹ인데 실은 j일 것」으로 의심. 이런 책을 세어 라벨 잡음 행렬(실제 j가 ỹ로 붙은 수)을 추정하고, 자기 라벨 확률 P(ỹ)가 낮은 순으로 의심 목록을 줄 세움.

**예시 코드(저장소에 없음):**
```python
from sklearn.model_selection import cross_val_predict, StratifiedGroupKFold
from cleanlab.filter import find_label_issues

pred_probs = cross_val_predict(LogisticRegression(max_iter=2000, class_weight='balanced'),
                               X, y_idx, groups=groups,
                               cv=StratifiedGroupKFold(n_splits=5), method='predict_proba')
issues = find_label_issues(labels=y_idx, pred_probs=pred_probs,
                           return_indices_ranked_by='self_confidence')   # 의심 책 번호, 의심 큰 순
```
**우리 규칙(6-ml-method.md 3-1).** 신호 셋 중 둘 이상: ① 이웃 20권 다수 분야와 다름 ② confident learning 의심 ③ KDC(국가서지 분류) 대응과 다름. 신호를 겹쳐 정밀도를 올림. 의심의 정밀도는 「의심 200 + 정상 100」을 방식 가려 판정해 잼.

---

# F. 비지도학습 (I-13: 사전에 없는 주제 찾기)

## F1. 차원 축소: UMAP [미적용]
**왜.** 1,024차원에서는 모든 점 사이 거리가 비슷해지는 「차원의 저주」로 밀도 기반 군집이 잘 안 됨. 5~15차원으로 줄이면 가까운 것끼리의 구조가 또렷해짐.

**원리(개념).** ① 각 점의 k-최근접 이웃으로 「가까울 확률」을 매긴 그래프(퍼지 단순 복합체)를 만듦 ② 저차원 점들을 놓고, 저차원 그래프가 원래 그래프와 비슷해지도록(교차 엔트로피를 줄이도록) 점을 옮김. 국소 구조(이웃 관계)를 잘 보존. `n_neighbors`가 크면 큰 구조, 작으면 작은 구조.
```python
import umap
low = umap.UMAP(n_components=10, n_neighbors=15, min_dist=0.0, metric='cosine',
                random_state=0).fit_transform(X)    # (N, 10)
```
(시각화용이면 `n_components=2`로 2차원 「책 지도」)

## F2. 군집: k-means와 HDBSCAN [미적용]
**k-means.** 중심 k개를 두고 「점 → 가장 가까운 중심」, 「중심 → 소속 점의 평균」을 되풀이하며 `Σ ‖x − 중심‖²`를 줄임. k를 미리 정해야 하고, 모든 점을 어딘가에 억지로 넣음(잡음 없음), 둥근 군집만 잘 찾음.

**HDBSCAN(우리가 고른 것).** 밀도 기반:
1. 핵심 거리 `core_k(a)` = a에서 k번째 이웃까지 거리(밀도가 낮으면 큼)
2. 상호 도달 거리 `d_mr(a, b) = max(core_k(a), core_k(b), d(a, b))` — 성긴 곳의 점은 멀게 만들어 잡음이 다리를 놓지 못하게
3. 이 거리로 최소 신장 트리 → 거리를 키워 가며 끊기는 계층(덴드로그램)
4. `min_cluster_size`보다 작아지는 갈래는 잡음으로 떨어뜨리고, 오래 살아남는(안정성이 큰) 군집을 고름
- 장점: 군집 수를 미리 안 정함, 잡음(`label = -1`)을 버림, 모양이 자유로움
```python
from sklearn.cluster import HDBSCAN          # scikit-learn 1.3부터
labels = HDBSCAN(min_cluster_size=40, min_samples=10).fit_predict(low)   # -1 = 잡음
```

## F3. c-TF-IDF: 군집의 대표 낱말 [미적용]
군집 c 안 소개 글을 한 문서로 이어 붙이고, 낱말 t의 중요도:
```
W(t, c) = tf(t, c) · ln(1 + A / f(t))
tf(t, c) = 군집 c에서 t의 빈도(정규화), f(t) = 모든 군집에서 t의 빈도, A = 군집당 평균 낱말 수
```
그 군집에 많고 다른 군집에 드문 낱말이 위로. 한국어는 먼저 형태소 분석으로 명사만 뽑음(예: kiwipiepy의 `Kiwi().tokenize(text)`에서 품사 `NNG`·`NNP`) [확인 필요: 분석기 라이선스]. BERTopic은 「임베딩 → UMAP → HDBSCAN → c-TF-IDF」를 묶은 도구이고 미리 만든 벡터를 `fit_transform(docs, embeddings=X)`로 받음 [확인 필요].

## F4. 군집 평가: 순도, ARI, NPMI [미적용]
- **순도(purity)**: 군집마다 가장 많은 프로필 분야의 비율 → 높으면 사전과 겹침(새 정보 적음), 낮으면 사전에 없는 주제 후보
- **ARI(조정 랜드 지수)**: 두 군집 결과가 같은 쌍을 같은 군집에 넣는 정도, 우연 수준을 0으로 보정(−1~1). 씨앗을 바꿔 다시 돌려 ARI ≥ 0.7인 군집만 믿음(안정성)
  ```python
  from sklearn.metrics import adjusted_rand_score
  adjusted_rand_score(labels_seed0, labels_seed1)
  ```
- **NPMI(정규화 점별 상호 정보)**: 대표 낱말 둘이 같은 글에 함께 나오는 정도
  ```
  NPMI(a, b) = ln( P(a, b) / (P(a) P(b)) ) / ( −ln P(a, b) )      (−1~1, 1 = 늘 함께)
  ```
  대표 낱말들의 NPMI 평균이 높으면 한 주제를 가리킴

---

# G. 같은 작품 판정 보강 (I-14)

## G1. 문자열 유사도 [미적용]
- **n-gram 자카드**: 문자열을 길이 n 조각 집합으로 → `J(A, B) = |A ∩ B| / |A ∪ B|`. 예 n = 2: 「돈키호테」 = {돈키, 키호, 호테}, 「라만차돈키호테」 = {라만, 만차, 차돈, 돈키, 키호, 호테} → J = 3/6 = 0.5
- **포함·접두·접미**: B3 규칙이 쓰는 것
- **우리 요약 검사의 8자 연속 일치**도 같은 n-gram 생각(I1)

## G2. 쌍 분류기와 정밀도 우선 문턱 [미적용]
**원리.** 「같은 작품인가」를 이진 분류로. 쌍 (a, b)마다 특징 벡터:
```
[제목 2-gram 자카드, 제목 3-gram 자카드, work_key 포함 여부, 공통 접두 길이, 공통 접미 길이,
 첫 저자 일치(0/1), 저자 포함(0/1), 코사인, 출판사 같음(0/1), 출간 연도 차, ISBN 앞 9자리 같음(0/1)]
```
로지스틱 회귀로 P(같은 작품)을 내고, **정밀도 ≥ 0.95**가 되는 문턱을 고름(잘못 묶으면 다른 책이 추천에서 사라지므로 재현율보다 정밀도).
```python
from sklearn.metrics import precision_recall_curve
p = pair_clf.predict_proba(F_val)[:, 1]
prec, rec, thr = precision_recall_curve(y_val, p)
tau = thr[np.argmax(prec[:-1] >= 0.95)]          # 정밀도 0.95를 처음 넘는 문턱
```
정답 쌍: 지금 규칙이 잡는 쌍(양성) + Haiku 「같은 작품」 판정 쌍 + 사용자가 확인한 쌍. 왜 코사인만으로 안 되나: A2 끝(같은 작품 쌍 0.68~0.78).

---

# H. 벡터 저장과 검색 (I-10)

## H1. 해시로 바뀐 것만 다시 계산 [운영: 파일 전체 재사용 · 미적용: 책 단위]
책마다 입력 글의 SHA-1 해시를 저장 → 다음에 같은 해시면 벡터를 다시 쓰고, 다르면 그 책만 다시 인코딩. 지금 `foryou_neighbours.py`가 `vectors.ids.json`에 `{ids, texts: sha1(모든 글)}`를 두어 「전체가 같으면 재사용」만 함 → 책 단위로 넓히는 것이 I-10.
```python
import hashlib
h = hashlib.sha1(text.encode('utf-8')).hexdigest()
```

## H2. 양자화 [운영: AI 검색 int8]
B7 참고. float16(2바이트)은 대부분 그대로 쓸 만하고, int8은 행마다 배율로 오차를 줄임(최악 코사인 0.99975).

## H3. 근사 최근접 이웃(ANN): HNSW, IVF, recall@k [미적용]
134만 권이면 전수 비교가 오래 걸림 → 근사.
- **IVF**: k-means로 공간을 칸(nlist개)으로 나눠, 질의와 가까운 칸 nprobe개만 뒤짐
- **HNSW**: 여러 층의 「가까운 점끼리 이은 그래프」에서 위층(성김)부터 탐욕적으로 내려가며 찾음. 빠르고 정확도가 높지만 메모리를 더 씀
- **recall@k** = (근사로 찾은 k개 ∩ 정확한 k개) / k → 표본 1만 권으로 재서 0.95 이상이면 씀
```python
import faiss
faiss.normalize_L2(X)                                   # 코사인 = 내적
index = faiss.IndexHNSWFlat(X.shape[1], 32, faiss.METRIC_INNER_PRODUCT)
index.hnsw.efSearch = 128                               # 클수록 정확·느림
index.add(X)
scores, ids = index.search(X[:10000], 21)               # 자기 자신 포함 21개
```

---

# I. 생성·판단 모델 (I-04, I-08, I-09)

## I1. LLM으로 글 만들기와 규칙 검사 (I-04) [운영: 요약 68,414권 · 미적용: 빠진 12,666권 채우기]

### 무슨 글인가: 책 「AI 요약」
- **글**: 책 한 권을 해요체 한두 문장, 80~150자로 소개하는 글. 무엇에 관한 책이고 어떤 독자에게 맞는지
- **실제 예**(운영 `/api/v1/for-you/books`의 `blurb`, 2026-10-29): 「달러구트 꿈 백화점」 → "잠든 손님만 들어갈 수 있는 꿈 백화점을 배경으로, 꿈을 만들고 팔고 사는 사람들의 이야기를 담은 판타지 소설이에요. 꿈을 사고팔 수 있을지 묻는 엉뚱한 상상에서 출발해, 낯선 세계의 규칙을 따라가며 읽기를 좋아하는 독자에게 어울려요."
- **저장**: `catalog.book_summary`(v1.20.0부터 책마다 요약 한 종류). 운영에 68,414권(이웃 표 81,080권 중)
- **화면에서 보이는 곳**(v1.20.0부터 모두 같은 글): 책첵 추천 책 창, 추천목록 책 창, 책 상세의 「AI 요약 ⓘ」, AI 검색 답의 책 카드
- **요약이 없는 책**: 그 칸이 안 보임. 이웃 표 81,080권 중 12,666권 → 이것을 채우는 것이 **I-04**

### 어떻게 만드나
| 단계 | 무엇을 | 어디서 |
|---|---|---|
| 1 입력 모으기 | 책마다 서지 한 줄(제목 · 저자 · 출판사 · 연도) + 도서관 목록의 책 소개 원문(수집 DB `holding.intro_raw` 중 가장 긴 것) 앞 1,500자 | PC (`foryou_blurbs.py` `prepare`, 10만 권은 수집 PC `build_100k.py`) |
| 2 글 쓰기 | `claude-haiku-5-5`에 지시문 `SYSTEM` + 입력을 보내 글을 받음. 10만 권은 Message Batches(한꺼번에 보내고 나중에 받음, 값 절반) | Anthropic API |
| 3 기계 검사 | `check(text, intro)`로 규칙 위반을 찾음. 걸린 글은 버리고, 다음 번에 더 센 지시(`BLURB_RETRY_RULE`)를 붙여 다시 | PC |
| 4 넣기 | 통과한 글을 `book-texts` 파일(`kind = for_you_blurb`)로 → 가져오기 `book-texts`가 DB에 넣음 | 운영 서버 |

소개 원문은 PC에만 남고 운영에는 만든 글만 감(R8-2).

### 원리: 만든 뒤 기계로 검사(생성-검증 고리)
생성 모델은 지시를 대체로 따르지만 **가끔 어김**(길이 넘침, 칭찬 말, 원문 베끼기). 사람이 6만 권을 다 읽을 수 없으니 **기계가 확인할 수 있는 규칙**은 코드로 검사하고, 걸린 것만 다시 시킴.

지시문(`scripts/crawl/foryou_blurbs.py` `SYSTEM`, 요지): 해요체 한두 문장 80~150자 / 무엇에 관한 책·어떤 독자에게 / 칭찬 말 금지 / 결말·반전 금지 / 「AI·인공지능·요약·소개 원문」 같은 말 금지 / 원문과 8자 이상 연달아 같은 글자 금지 / 알 수 없으면 `NONE`.

검사 코드(같은 파일):
```python
MIN_CHARS, MAX_CHARS = 80, 150
BANNED = re.compile(r'AI|인공지능|최고의|감동적|꼭 읽어야|베스트셀러|필독')

def check(text, intro):
    if text.strip().upper() == 'NONE':            return 'none'         # 모델이 「모른다」고 함
    if not MIN_CHARS <= len(text) <= MAX_CHARS:   return 'length'
    if '
' in text or text.startswith(('"', '“', "'")): return 'shape'
    if BANNED.search(text):                       return 'banned_word'  # 칭찬 말, AI 언급
    if not re.search(r'(요|죠)[.!?]?$', text):     return 'not_haeyo'    # 해요체로 끝나나
    if copied_run(text, intro):                   return 'copied'       # 원문 베낌
    return None                                                          # 통과

def copied_run(text, source, run=8):
    a = re.sub(r'\s+', '', text); b = re.sub(r'\s+', '', source or '')   # 빈칸은 빼고 비교
    grams = {b[i:i + run] for i in range(len(b) - run + 1)}             # 원문의 8글자 조각 전부(집합)
    for i in range(len(a) - run + 1):
        if a[i:i + run] in grams:                                       # 요약의 8글자 조각이 원문에 있으면
            return a[i:i + run]                                         # 베낀 것
    return None
```
- 8글자 조각(8-gram)을 집합에 넣어 두면 「있나?」를 한 번에 확인(해시, O(1)) → 원문 길이 + 요약 길이에 비례하는 시간
- 왜 베낌을 막나: 소개 원문은 출판사·도서관의 글이라 그대로 옮기면 안 됨. 「자기 말로 다시 쓴 글」만 내보냄
- 기계로 못 잡는 규칙(결말을 말했나, 내용이 맞나)은 이 검사에 없음 → 지시문에만 의존

**10만 권 결과**(2026-10-26, 2-data.md 4절): 통과 43,227권, 탈락 8,571권. 탈락 이유는 원문 8자 연속 베낌 5,557 · `NONE` 2,153 · 길이 596 · 금지어 244 · 모양·해요체 아님 20(목록 `<data>/aisearch/100k/dropped-for_you_blurb.tsv`). 베낌이 가장 많아서, I-04에서는 「8자 넘게 같은 글자가 이어지지 않게」를 지시문에 더 세게 넣어 다시 보냄.

## I2. LLM 재정렬(오프라인) (I-08) [미적용]
책마다 이웃 20권을 LLM에게 보여 주고 「이 책 독자에게 맞는 차례」를 받음 = 목록 단위(listwise) 순위 매기기. 임베딩은 「글이 비슷한가」를, LLM은 「독자가 다음에 읽고 싶을까」를 판단할 수 있음. 운영 비용 0(미리 해서 `neighbour_ids` 차례만 바꿈). 잴 것: E2와 같은 방식 가린 판정으로 전후.

## I3. 판단 모델로 요청마다 점수 (I-09) [미적용]
점별(pointwise) 점수: (기준 책, 후보)마다 0~1 점수 → 정렬. 요청마다 60쌍이면 비용·지연이 핵심. 먼저 우리 Haiku 판정 1,000쌍과 같은 쌍을 매겨 **순위 상관(스피어만 ρ)**으로 일치도를 잼:
```
ρ = 1 − 6 Σ dᵢ² / (n (n² − 1))      (dᵢ: 두 점수의 순위 차)
```
```python
from scipy.stats import spearmanr
rho, p = spearmanr(haiku_scores, jev_scores)
```

---

# J. 온라인 실험 (I-05, I-06)

## J1. 노출 기록이 왜 필요한가 [미적용]
클릭률 = 클릭 / **노출**. 지금 서버는 클릭(`screen_event` `picks_book_view`)만 있고 「보였지만 안 누른 것」이 없음 → 클릭률도, 지난 노출로 새 방식을 오프라인 재생해 보는 평가(counterfactual/off-policy evaluation)도 못 함. 노출 한 줄 = (방문 키, 책 번호, 자리, 출처: 개인/기본/다시/다른 분야, 칩, 시각).

## J2. 표본 크기: 차이를 가리려면 몇 번 필요한가 [미적용]
두 비율 p₁, p₂를 유의수준 α(양측 5%), 검정력 1−β(80%)로 가리려면 한 쪽에
```
n ≈ (z_{1−α/2} + z_{1−β})² · [p₁(1−p₁) + p₂(1−p₂)] / (p₁ − p₂)²
  ≈ 2 (1.96 + 0.84)² · p(1−p) / δ²    (p₁ ≈ p₂ ≈ p일 때)
```
예: 책 열기 30%에서 5%p 차이 → 2 × 7.84 × 0.21 / 0.0025 ≈ **1,317회**. 도서관 누름 5%에서 2%p → 2 × 7.84 × 0.0475 / 0.0004 ≈ **1,862회**. 지금 책첵 추천 화면 보기 누적 88회 → A/B 불가.

## J3. 끼워 넣기(team-draft interleaving) [미적용]
한 사용자에게 A와 B의 결과를 **한 목록**에 섞어 보여 주고, 누른 책이 어느 쪽 것인지로 승부. 같은 사람이 두 방식을 동시에 보므로 사람 사이 차이가 없어져 A/B보다 훨씬 적은 표본으로 방향을 가림.
```
알고리즘(팀 드래프트):
  결과 목록 L = [], 팀 표시 T = {}
  while L이 k권이 아니면:
      동전을 던져 이번에 먼저 고를 쪽을 정함(또는 지금까지 적게 고른 쪽)
      그쪽 순위에서 아직 L에 없는 가장 높은 책을 L에 넣고 T[책] = 그쪽
  클릭마다 T[클릭한 책]에 1점 → 노출(세션)마다 더 많이 받은 쪽이 이김
```

## J4. A/B와 SRM [미적용]
- 배정: 브라우저 키의 해시를 2로 나눈 나머지 → 같은 브라우저는 늘 같은 쪽
- **SRM(표본 비율 불일치) 검사**: 50:50으로 나눴는데 실제 비율이 다르면 배정·기록에 버그가 있다는 신호. 카이제곱 검정
  ```python
  from scipy.stats import chisquare
  stat, p = chisquare([n_A, n_B], [ (n_A + n_B) / 2 ] * 2)    # p < 0.001이면 결과를 믿지 않음
  ```
- 주 지표 = 책첵 추천 방문당 도서관 누름(대출 의사), 안전 지표 = 빈 목록 비율·응답 시간, 기간 ≥ 2주(요일 효과)

---

# K. 남에게 설명하기 (짧은 답)

| 질문 | 답 |
|---|---|
| 추천은 어떻게 하나요? | 사용자가 본 책을 브라우저가 기억하고, 그 책과 **임베딩 코사인이 큰 책**을 미리 계산한 표에서 꺼내, **비슷한 순서대로 기준 책을 번갈아** 보여 줍니다(내용 기반 추천) |
| 왜 협업 필터링이 아닌가요? | 로그인 없이도 써야 하고 사용자 행동 자료가 적어서, 책 **내용**으로 비슷함을 계산했습니다 |
| 비슷함은 어떻게 재나요? | KURE-v1로 만든 1,024차원 벡터를 길이 1로 맞추면 **내적이 코사인**이 되고, 8만 권을 행렬 곱으로 한 번에 계산합니다 |
| 사용자 기록 없이 좋은지 어떻게 아나요? | 추천목록을 정답으로 한 **Hit@20·nDCG**, 가상 사용자로 **엔트로피 같은 다양성 지표**, 방식을 가린 **LLM 판정**을 함께 봤습니다. 판정은 흔들려서 같은 실행 안에서만 비교했습니다 |
| 지표가 틀린 적은요? | 정답이 추천목록 책뿐이라, 대상을 넓히니 Hit가 반으로 줄었는데 판정은 좋아졌습니다. 지표의 정답 정의 때문이었습니다 |
| 다양성은요? | MMR은 판정을 떨어뜨려 버렸고, 순서는 두고 **정해진 자리에서만** 안 본 분야를 넣는 탐색 규칙을 썼습니다 |
| 「다 대출 중」은요? | 가중합은 비슷함을 해쳐서, **문턱(필터)으로 거르고 그 안에서는 유사도 순**으로 했습니다. 바로 대출 48 → 61%, 판정 손해 없음 |
| 분야는 어떻게 붙이나요? | 지금은 이웃 다수결(kNN). 다음은 LLM이 만든 프로필 7.7만 권을 정답으로 **로지스틱 회귀**를 학습하고, **확률 보정 → 확신 높은 것만 기계로, 나머지는 LLM**(선택적 예측) |
| 학습에서 조심할 점은요? | **같은 작품이 학습·시험에 갈리지 않게 그룹 분할**(누수), **불균형이라 macro-F1**, LLM 정답에도 잡음이 있어 **confident learning**으로 의심 라벨을 찾음 |
| 비지도학습은 어디에 쓰나요? | **UMAP으로 줄이고 HDBSCAN으로 군집**, **c-TF-IDF**로 군집 이름 후보 → 분류 사전에 없는 주제를 찾고, 씨앗을 바꿔 **ARI**로 안정성을 확인 |
| 왜 A/B를 안 했나요? | 5%p 차이를 가리려면 한 쪽 약 1,300회가 필요한데 지금 88회. 먼저 **노출 기록**, 다음 **끼워 넣기**, 그다음 A/B |
