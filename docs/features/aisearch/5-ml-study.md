# AI 책 찾기 ML 공부 노트: 원리 · 수식 · 실제 코드 (2026-10-29)

## 0. 이 문서가 답하는 것 / 읽는 법

- **답하는 것**: AI 책 찾기에 쓴 머신러닝을 **남에게 설명할 수 있을 만큼** 처음부터. 각 개념을 「한 줄 뜻 → 수식 → 직관 → 우리가 실제로 쓴 코드」 순서로 적었다. 우리가 무엇을 어떤 원리로, 어떤 라이브러리를 어떻게 써서 했는지.
- **바탕 문서**: [3-ml-models.md](3-ml-models.md)(모델·데이터·점수 요약). 숫자는 그 문서와 같고, 이 문서는 **원리와 코드**를 더한다.
- **답하지 않는 것**: 모델 출력이 질문 하나의 흐름 어디에 쓰이나 → [1-how-it-works.md](1-how-it-works.md) 4-3·4-4·4-9절. 버전마다 바뀐 것 → [2-versions.md](2-versions.md).
- **읽는 법**: 1절(큰 그림)을 먼저, 2절은 개념 사전(필요한 것만), 3절은 우리가 한 일을 순서대로(코드 포함), 4절은 라이브러리 표, 5절은 남에게 설명할 때 쓸 말.
- 코드는 저장소의 실제 코드에서 그대로 옮겼다(줄을 줄인 곳은 `…`). 학습 스크립트는 `scripts/aisearch/eval/`, 운영 계산은 `backend/src/main/java/com/chaekcheck/aisearch/internal/`.

## 1. 큰 그림 (한 화면)

```
                       ┌─────────── 학습 (PC, Python) ───────────┐
Sonnet이 만든 질문 + 정답 ─▶ KURE-v1로 벡터(1,024) ─▶ PyTorch MLP 학습 ─▶ 문턱 고르기 ─▶ 가중치 파일(.bin)
                       └──────────────────────────────────────────┘
                       ┌─────────── 운영 (서버, Java) ───────────┐
사용자 질문 ─▶ KURE-v1 int8 ONNX(임베딩 서버) ─▶ 1,024 벡터 ─┬▶ MLP 12머리(행렬곱·ReLU·softmax/sigmoid) ─▶ 길·칸
                                                         ├▶ 책 벡터 81,078개와 내적, 상위 k ─▶ 가까운 책
                                                         └▶ (이어 묻기) 관계 MLP ─▶ 관계 7가지
                       └──────────────────────────────────────────┘
```

1. **문장 → 숫자**: 「출근길에 가볍게 읽을 과학책」 같은 글을 KURE-v1이 **1,024개 실수**(길이 1인 벡터)로 바꾼다. 뜻이 비슷한 문장은 가까운 벡터가 된다. 이 모델은 남이 학습한 것을 **그대로** 쓴다.
2. **숫자 → 판단**: 그 벡터를 입력으로 받는 **작은 신경망(MLP)** 을 우리가 학습했다. 「이 질문이 책 찾기인가(길 5갈래)」, 「어느 분야·장르·독자인가(칸 11개)」, 「앞 질문과 어떤 관계인가(7가지)」.
3. **숫자 → 가까운 책**: 같은 KURE-v1로 미리 만든 책 81,078권의 벡터와 질문 벡터의 **내적(=코사인 유사도)** 을 계산해 가까운 책을 뽑는다.
4. 학습은 Python(PyTorch·scikit-learn), 운영 계산은 Java(행렬곱을 직접)와 ONNX Runtime.

## 2. 기초 원리 (개념 사전)

### 2-1. 벡터, 내적, 코사인 유사도, 정규화

- **벡터**: 숫자를 줄 세운 것. 우리 질문 벡터는 `x = (x₁, …, x₁₀₂₄)`.
- **내적**: 같은 자리끼리 곱해 더한 값.

  `x · y = Σᵢ xᵢ·yᵢ`

- **길이(L2 노름)**: `‖x‖ = √(Σᵢ xᵢ²)`.
- **코사인 유사도**: 두 벡터 사이 각도의 코사인. 방향이 같으면 1, 직각이면 0, 반대면 −1.

  `cos(x, y) = (x · y) / (‖x‖·‖y‖)`

- **L2 정규화**: 벡터를 자기 길이로 나눠 길이를 1로 만드는 것 `x̂ = x / ‖x‖`. **길이 1인 벡터끼리는 내적이 곧 코사인 유사도**다(분모가 1×1). 그래서 KURE 벡터를 미리 정규화해 두면 「코사인 계산」이 「내적 한 번」으로 끝난다.
- 직관: 1,024차원 공간의 한 점(방향)이 문장 하나의 「뜻」이다. 뜻이 비슷하면 방향이 비슷하다.
- 우리 코드: 학습 때 `km.encode(texts, normalize_embeddings=True)`(sentence-transformers), 운영 ONNX 모델은 그래프 끝에서 스스로 길이로 나눈다(3-1절), 책 벡터 파일을 만들 때 `matrix /= np.linalg.norm(matrix, axis=1, keepdims=True)`.

### 2-2. 문장 임베딩과 Transformer 인코더 (KURE-v1)

- **임베딩**: 글·단어·문장을 고정 길이 실수 벡터로 바꾼 것. 좋은 임베딩은 「뜻이 비슷하면 가깝게」 놓는다.
- **KURE-v1**(`nlpai-lab/KURE-v1`, 고려대 NLP&AI Lab, MIT): 모델 카드와 PC 사본의 `config.json` 기준으로
  - 뼈대 **XLM-RoBERTa**(model_type `xlm-roberta`): **24층**, 숨은 크기 **1,024**, 어텐션 머리 **16개**, 층 안 앞먹임 크기 4,096, 낱말 조각 사전 250,002개.
  - `BAAI/bge-m3`를 한국어 「질문–문서–어려운 오답 5개」 데이터로 **CachedGISTEmbedLoss**(sentence-transformers의 대조 학습 손실)로 미세조정한 모델.
  - 문장 벡터 = 마지막 층의 **첫 토큰(CLS) 벡터**(`1_Pooling/config.json`의 `pooling_mode_cls_token: true`) → **Normalize**(길이 1).
- **토큰화**: 글을 「조각(subword)」으로 자른다(SentencePiece 계열 사전). 「출근길에」 → 몇 개의 조각 id. 맨 앞에 특수 조각 `<s>`(=CLS 자리), 맨 뒤에 `</s>`가 붙는다. 사전은 SentencePiece의 Unigram 방식(원래 250,002개, 운영용으로 88,446개만 남김).
- **Transformer 인코더 한 층**(원리):
  1. 조각마다 1,024차원 벡터(임베딩 + 위치 정보).
  2. **자기 어텐션**: 각 조각이 다른 모든 조각을 얼마나 볼지 정한다. 조각 벡터들을 행렬 `X`라 하면

     `Q = XW_Q, K = XW_K, V = XW_V,   Attention(Q,K,V) = softmax(QKᵀ / √d_k) · V`

     `QKᵀ`는 「조각 i가 조각 j와 얼마나 맞나」 점수표, `√d_k`로 나눠 값이 너무 커지지 않게, softmax로 행마다 합 1인 비율로 바꾼 뒤 `V`를 그 비율로 섞는다. 이를 머리 16개로 나눠(각 64차원) 따로 한 뒤 이어 붙인다(멀티헤드).
  3. 더하기 + 정규화(잔차 연결, LayerNorm), 앞먹임 신경망(1,024 → 4,096 → 1,024, 사이 활성 함수 GELU, `config.json`의 `hidden_act`), 다시 더하기 + 정규화.
  4. 이것을 24번. 끝나면 CLS 자리 벡터가 「문장 전체를 본」 요약이 된다.
- **대조 학습(contrastive learning)** — KURE를 만든 쪽이 한 일(우리는 하지 않음): 「질문과 맞는 문서」는 가깝게, 「맞지 않는 문서(오답)」는 멀게 벡터를 학습시킨다. 전형적인 꼴(InfoNCE):

  `L = −log( exp(cos(q, d⁺)/τ) / Σⱼ exp(cos(q, dⱼ)/τ) )`

  맞는 쌍의 유사도가 오답들보다 크도록 미는 손실이다(τ: 온도. 일반 꼴이며, KURE가 쓴 CachedGISTEmbedLoss는 이 꼴에 「안내 모델로 가짜 오답 거르기」와 「큰 배치를 나눠 계산하는 캐시」를 더한 것. 정확한 식은 sentence-transformers 문서). 이렇게 배운 덕분에 우리는 벡터 사이 거리만으로 「뜻이 가깝다」를 쓸 수 있다.
- **바이인코더 vs 크로스인코더**: 우리는 질문과 책을 **따로** 벡터로 만든다(바이인코더) → 책 벡터를 미리 만들어 두고 질문 하나에 내적 81,078번이면 된다. 둘을 한 입력으로 넣어 점수를 내는 크로스인코더는 더 정확할 수 있지만 쌍마다 모델을 돌려야 해 느리다(재 보니 질문 하나 9.2초, [3-ml-models 2절](3-ml-models.md)).

### 2-3. 양자화 (int8)

- **뜻**: 32비트 실수(float32)를 8비트 정수(int8, −128\~127. 우리 책 벡터는 0을 가운데 두려고 −127\~127만 씀)로 줄여 저장·계산하는 것. 크기 약 1/4(줄마다 scale 4바이트가 더 붙음).
- **수식**(우리 책 벡터, 줄마다 scale 하나 = 「행별 대칭 양자화」):

  `s = max(|x|) / 127,   q = clip(round(x / s), −127, 127),   x ≈ q · s`

- 내적은 `x · y ≈ s · (q · y)` 로 계산한다 → 정수 줄과 질문 벡터를 곱해 더한 뒤 마지막에 scale을 한 번 곱한다.
- **오차**: 숫자 하나마다 반올림만큼(최대 s/2) 틀어진다. 길이 1 벡터에서 원래와의 코사인이 거의 1(책 벡터 파일을 만들 때 스크립트가 「가장 나쁜 코사인」을 출력해 확인).
- 질문 인코더(ONNX) 쪽은 ONNX Runtime의 `quantize_dynamic(per_channel=True)`로 행렬곱(MatMul)과 낱말표(Gather)의 가중치를 **채널별 int8**로 줄였다(가중치 행렬의 한 축의 칸마다 scale 하나. 「동적」 양자화라 중간 계산값은 실행 때 그때그때 줄임) → 2.2GB에서 낱말표 자르기와 함께 **385MB**, 원래 모델과 벡터 닮음 0.989(PC)([임베딩 보고서](reports/2026-10-25-embedding.md)).
- 우리 코드(`scripts/aisearch/make_book_vectors.py`):

  ```python
  matrix /= np.linalg.norm(matrix, axis=1, keepdims=True)          # 길이 1로
  scales = np.abs(matrix).max(axis=1) / 127.0                      # 줄마다 s
  q = np.round(matrix / scales[:, None]).clip(-127, 127).astype(np.int8)
  back = q.astype(np.float32) * scales[:, None]                    # 되살린 값으로 오차 확인
  ```

### 2-4. 선형 모델과 로지스틱 회귀 (기준선)

- **선형 모델**: 입력의 가중 합 `z = w · x + b`. `w`(가중치)와 `b`(편향)를 학습한다.
- **로지스틱 회귀(이진)**: 가중 합을 시그모이드로 0\~1 확률로.

  `p(y=1 | x) = σ(w · x + b),   σ(z) = 1 / (1 + e^(−z))`

  결정 경계 `w · x + b = 0` 은 공간을 **평평한 면(초평면)** 으로 가른다 → 선형 모델은 직선(면)으로만 나눌 수 있다.
- **여러 갈래**: softmax로 갈래마다 확률(다항 로지스틱 회귀), 또는 갈래마다 「이것 vs 나머지」 이진 모델을 따로(One-vs-Rest).
- **규제 C**: scikit-learn `LogisticRegression`의 기본 벌점은 L2이고, `C`는 그 규제 세기의 **역수**다. 푸는 식은 `½‖w‖² + C·Σ(데이터마다 손실)`의 최소화이고, C로 나누면 「손실 합 + `‖w‖² / (2C)`」와 같다. C가 작을수록 가중치를 작게(단순하게) 누른다. 기본값은 1, 우리는 `C=4`(규제를 기본보다 약하게).
- 우리 코드(기준선, `predict_eval.py`, `route5b.py`):

  ```python
  m = OneVsRestClassifier(LogisticRegression(max_iter=3000, C=4), n_jobs=8).fit(X, Y[:, keep])   # 칸: 칸마다 이진
  m = LogisticRegression(max_iter=3000, C=4).fit(X, y); …                                         # 길: 5갈래 다항
  ```

- KURE 벡터 위에 로지스틱 회귀만 얹은 것을 **linear probe**라고 부른다: 「임베딩이 이미 정보를 선형으로 꺼낼 수 있게 담고 있나」를 재는 표준 기준선.

### 2-5. MLP (다층 퍼셉트론)

- **뜻**: 선형 층 사이에 **비선형 함수**를 끼운 신경망. 우리 것은 은닉층 1개:

  `h = ReLU(W₁ x + b₁)`, `z = W₂ h + b₂`, `ReLU(t) = max(0, t)`

  - `x`: 1,024 (관계 분류기는 4,096), `h`: 512, `z`: 갈래 수 n.
  - `W₁`: 512 × 1,024, `W₂`: n × 512.
- **왜 비선형이 필요하나**: ReLU가 없으면 `W₂(W₁x)` = `(W₂W₁)x` 로 결국 선형 하나와 같다. ReLU가 「꺾인 면」을 여러 개 만들어 **굽은 결정 경계**를 그릴 수 있게 한다(은닉층 하나라도 칸이 충분히 많으면 닫힌 범위의 연속 함수를 원하는 만큼 가깝게 근사할 수 있다는 것이 「보편 근사 정리」. 일반 이론이고, 우리 512칸이 충분한지는 시험 점수로만 확인했다).
- **은닉층 = 특징 만들기**: `h`의 512개 값은 「학습이 스스로 만든 특징」이고, 마지막 층은 그 특징 위의 로지스틱 회귀다. 그래서 「MLP = 특징을 배우는 로지스틱 회귀」라고 설명할 수 있다.
- **가중치 수**: 1,024×512 + 512 + 512×n + n. 칸 22개 머리 536,086개(약 54만), 작은 칸 99개 머리 575,587개(약 58만), 관계 분류기(4,096×512 + 512 + 512×7 + 7) 2,101,255개(약 210만).

### 2-6. 출력을 확률로: softmax와 sigmoid

- **softmax**(하나만 고르는 문제, 다중 클래스): 점수 `z₁…zₙ`을 합 1인 확률로.

  `pₖ = e^(zₖ) / Σⱼ e^(zⱼ)`

  계산할 때는 `e^(zₖ − max z)` 로 최댓값을 빼고 지수를 한다. 결과는 같고(분자·분모에 같은 수가 곱해짐), 큰 수의 지수가 넘치는 것(오버플로)을 막는다.
- **sigmoid**(여러 개를 동시에 고를 수 있는 문제, 다중 라벨): 출력마다 따로 `pₖ = σ(zₖ)`. 합이 1일 필요가 없다. 「이 책 질문은 장르가 추리이면서 스릴러일 수 있다」.
- 우리 머리: 길(route)·소설 여부·나라·독자·수준 = softmax(하나 고름), 분야·작은 분야·장르·형식·빼 달라는 칸 = sigmoid(여럿 가능).
- 운영 Java 코드(`FirstCallModel.outputs`의 계산 부분, 그대로. 앞의 머리 찾기·크기 검사만 뺌):

  ```java
  double[] h = new double[hidden];
  for (int i = 0; i < hidden; i++) {            // h = ReLU(W1·x + b1)
      double sum = head.b1()[i];
      int row = i * dim;
      for (int j = 0; j < dim; j++) {
          sum += head.w1()[row + j] * vector[j];
      }
      h[i] = Math.max(0, sum);
  }
  int outputs = head.labels().size();
  double[] z = new double[outputs];
  for (int k = 0; k < outputs; k++) {           // z = W2·h + b2
      double sum = head.b2()[k];
      int row = k * hidden;
      for (int i = 0; i < hidden; i++) {
          sum += head.w2()[row + i] * h[i];
      }
      z[k] = sum;
  }
  if (head.kind() == Kind.MULTI) {              // 다중 라벨: 출력마다 sigmoid
      for (int k = 0; k < outputs; k++) {
          z[k] = 1 / (1 + Math.exp(-z[k]));
      }
      return z;
  }
  double top = Arrays.stream(z).max().orElse(0); // 다중 클래스: 최댓값을 빼고 softmax
  double total = 0;
  for (int k = 0; k < outputs; k++) {
      z[k] = Math.exp(z[k] - top);
      total += z[k];
  }
  for (int k = 0; k < outputs; k++) {
      z[k] /= total;
  }
  return z;
  ```

### 2-7. 손실 함수: 교차 엔트로피

- **손실**: 「모델이 얼마나 틀렸나」를 숫자 하나로. 학습은 이것을 줄이는 것.
- **교차 엔트로피(다중 클래스)**: 정답 갈래 y의 확률에 −log.

  `L = −log p_y`

  정답에 확률 1을 주면 0, 0.5면 0.69, 0.01이면 4.6(자연로그). 틀린 확신을 크게 벌한다. PyTorch `nn.CrossEntropyLoss`는 **점수 z를 받아** 안에서 log-softmax까지 한다(그래서 모델 끝에 softmax 층을 따로 두지 않는다). 칸의 하나 고르기 머리는 정답을 갈래 번호가 아니라 원-핫 행(`np.eye(len(labs))[y]`)으로 넘기는데, 이때 손실 `−Σₖ yₖ log pₖ`는 정답 칸 하나만 남아 위 식과 같다(길·관계 학습은 갈래 번호를 넘김).
- **이진 교차 엔트로피(다중 라벨)**: 출력마다

  `L = −[ y·log σ(z) + (1−y)·log(1−σ(z)) ]`

  를 평균. PyTorch `nn.BCEWithLogitsLoss`는 sigmoid와 이 식을 한 번에, 수치적으로 안정되게 계산한다(점수 z를 받음).
- 왜 이 손실인가: 「모델 확률이 데이터의 정답 분포와 얼마나 다른가」(최대 가능도 추정)와 같은 식이고, softmax/sigmoid와 묶으면 기울기가 `p − y` 꼴로 단순해 학습이 잘 된다.

### 2-8. 학습: 경사 하강, 역전파, 미니배치, AdamW

- **경사 하강**: 손실 L을 가중치 w에 대해 미분한 기울기 ∇L의 **반대 방향**으로 조금씩 움직인다.

  `w ← w − η · ∇L(w)`  (η: 학습률)

- **역전파**: 층이 쌓인 식의 기울기를 **연쇄 법칙**으로 뒤에서부터 계산하는 방법. PyTorch에서는 `loss.backward()` 한 줄이 모든 가중치의 기울기를 채운다. (예: `∂L/∂W₂ = (p − y) · hᵀ`, `∂L/∂h = W₂ᵀ(p − y)`, ReLU에서는 h > 0인 칸만 기울기가 통과.)
- **미니배치**: 데이터 전부가 아니라 64개씩 묶어 기울기를 계산·적용. 한 번 전체를 다 보는 것이 1 **epoch**. 우리는 **64개씩, 60 epoch**, epoch마다 순서를 섞는다(`torch.randperm`).
- **Adam**: 기울기의 이동 평균(m, 방향)과 제곱의 이동 평균(v, 크기)을 써서 가중치마다 걸음 크기를 자동으로 맞추는 최적화.

  `m ← β₁m + (1−β₁)g`, `v ← β₂v + (1−β₂)g²`, `m̂ = m/(1−β₁ᵗ)`, `v̂ = v/(1−β₂ᵗ)`, `w ← w − η · m̂ / (√v̂ + ε)`

  PyTorch 기본값 β₁=0.9, β₂=0.999, ε=1e-8. 우리는 η = **1e-3**.
- **AdamW**: 위에 **가중치 감쇠(weight decay)** 를 기울기와 분리해서 `w ← w − η·λ·w` 로 따로 적용(PyTorch는 Adam 걸음 전에 이것을 먼저 함). λ는 PyTorch 기본 1e-2, 우리는 **1e-4**. 가중치가 커지는 것을 막는 규제. 「분리」의 뜻: 보통 Adam에 L2 벌점을 손실로 넣으면 그 기울기 `λw`도 `√v̂`로 나뉘어 가중치마다 감쇠 세기가 달라지는데, AdamW는 그러지 않고 모든 가중치를 같은 비율로 줄인다(Loshchilov & Hutter).
- 우리 학습 코드(칸 머리, `export_first_call2.py`의 `mlp_model`, 그대로):

  ```python
  def mlp_model(X, Y, multi=True, epochs=60):
      torch.manual_seed(0)
      m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, Y.shape[1]))
      opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4)
      lossf = nn.BCEWithLogitsLoss() if multi else nn.CrossEntropyLoss()
      Xt, Yt = torch.tensor(X), torch.tensor(Y.astype(np.float32))
      for _ in range(epochs):
          perm = torch.randperm(len(Xt))
          for i in range(0, len(Xt), 64):
              b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
      m.eval(); return m
  ```

  한 줄씩: 시드 고정 → 모델 정의 → 최적화기 → 손실 → 60번 반복하며 섞고, 64개씩 「기울기 0으로 → 앞으로 계산 → 손실 → 역전파 → 한 걸음」 → 평가 모드.

### 2-9. 과적합과 규제: 드롭아웃, 가중치 감쇠, 데이터 나누기

- **과적합**: 학습 데이터는 잘 맞히는데 처음 보는 데이터에서 떨어지는 것. 학습 데이터의 우연한 무늬까지 외운 상태.
- **드롭아웃 0.3**: 학습할 때만 은닉 칸 하나하나를 매번 0.3의 확률로 끈다(평균 30%. 남은 칸은 1/(1−0.3)배로 키워 기댓값을 맞춤 = 「inverted dropout」, PyTorch 방식이라 평가 때는 아무것도 곱하지 않음). 특정 칸 몇 개에 기대지 못하게 해 일반화가 좋아진다. 평가(`m.eval()`)와 운영에서는 끄므로 **Java 코드에는 드롭아웃이 없다**(내보낼 때 `m[0]`(첫 Linear)과 `m[3]`(둘째 Linear)만 꺼냄, `m[2]`가 Dropout).
- **가중치 감쇠**(위 AdamW), **로지스틱 회귀의 C**: 둘 다 「가중치를 작게」 누르는 규제.
- **데이터 나누기**: 학습(train) / 문턱 고르기(dev) / 시험(test)을 겹치지 않게. 시험 점수는 학습·문턱 고르기에 **한 번도 쓰지 않은** 질문에서만 낸다.
  - 칸: 학습 = 질문 묶음 1\~24와 31\~70(시험과 글이 같은 질문 제외) 6,298개. dev = 그중 묶음 24(`2300 < seed <= 2400`, 98개). 시험 = 묶음 25\~30의 600개. dev는 문턱을 고를 때만 따로 두고, 문턱을 정한 뒤 운영 모델은 dev까지 포함한 6,298개로 다시 학습한다.
  - 길 나누기: 길 학습 질문(갈래별 질문, 칸 학습 질문은 빼고)에서 10개마다 1개를 dev로(`i % 10 == 0`). 이것도 tau를 고른 뒤 dev까지 모두 넣어 다시 학습한다. 시험은 따로 쓴 질문 700개(기존 600개 = 처음 400개 + 책첵 질문 100개 + 책첵 질문처럼 보이는 책 질문 100개, 그리고 짧은 질문 100개). 만들 때 `avoid.txt`로 겹침을 막고, 학습 스크립트도 시험과 글이 같은 질문을 학습에서 뺀다.
  - 관계: 시험 700쌍의 첫 질문은 학습에 없음.

### 2-10. 평가 지표

- **정확도(accuracy)**: 맞힌 수 / 전체. 하나만 고르는 머리(길, 소설 여부, 나라…)에 쓴다.
- **정밀도(precision)** = TP / (TP + FP): 「그렇다」고 한 것 중 맞은 비율. **재현율(recall)** = TP / (TP + FN): 실제로 그런 것 중 찾아낸 비율. **F1** = 둘의 조화 평균 = 2·TP / (2·TP + FP + FN).
- **micro F1**: 모든 질문·모든 칸의 TP·FP·FN을 먼저 합친 뒤 F1. 칸 머리(여럿 고르기) 점수가 이것이다. 우리 코드(`train.py`·`predict_eval.py`의 `f1`, 그대로):

  ```python
  def f1(pred, gold):
      tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
      fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
  ```

- **혼동 행렬**: 「정답 갈래 × 예측 갈래」 표. 어디서 헷갈리는지 보인다(관계 분류기 학습 때 출력, `rel_train.py`).
- **클래스 불균형의 함정**: 데이터의 97%가 한 갈래면 「늘 그 갈래」라고만 해도 정확도 97%. 그래서 갈래별 재현율·F1을 따로 본다. 우리 길 나누기가 처음 58.5%였던 원인이 이것이다(3-ml-models 6절).

### 2-11. 확률을 결정으로: 문턱과 비용

- 모델은 확률만 낸다. 「몇 이상이면 그렇다고 할까」(문턱)는 따로 정한다.
- **칸 문턱 고르기**(F1 최대): dev에서 문턱 t를 0.05\~0.65까지 0.05씩 바꿔 가며 F1이 가장 큰 t를 고른다(`predict_eval.py`, 그대로):

  ```python
  th = max(np.arange(0.05, 0.7, 0.05), key=lambda t: f1([[labs[i] for i in np.where(x >= t)[0]] for x in pd], gd))
  ```

  문턱을 낮추면 재현율↑ 정밀도↓, 높이면 반대. 결과: 분야 0.10, 작은 분야 0.30, 장르 0.25, 형식 0.65, 빼 달라는 분야 0.50, 빼 달라는 작은 분야·장르 0.05.
- **길 문턱 tau**(비용 비대칭): 「책 찾기 아님」·「책첵 사용법 질문」으로 잘못 보내면 검색을 하지 않아 책을 하나도 못 받고, 「보통 질문」으로 잘못 보내면 조금 덜 맞을 뿐이다. 그래서 「보통 질문이 아닌 갈래는 확률이 tau 이상일 때만」 따르고, dev에서 「책·제목·앞 책 질문을 책 찾기 아님·책첵 질문으로 보낸 수(막은 수) ≤ 1」 안에서 정확도가 가장 높은 tau를 0.30\~0.95(0.05씩)에서 고른다(`route5b.py`, 그대로):

  ```python
  def route5(P, tau):
      out = []
      for p in P:
          k = int(np.argmax(p)); out.append(R5[k] if k == 4 or p[k] >= tau else 'book')
      return out
  …
  for tau in np.arange(0.3, 0.96, 0.05):
      rd = route5(Pd, tau)
      blocked = sum(1 for g, r in zip(gd, rd) if g in ('book', 'title_like', 'refers_shown') and r in ('not_book', 'site'))
      acc = np.mean([g == r for g, r in zip(gd, rd)])
      if blocked <= 1 and (best is None or acc > best[1]): best = (tau, acc)
  ```

  라우터 B(v1.16.1\~v1.20.6 운영, 시드 0 모델)의 tau = **0.55**(파일 값 0.5499999999999999). 운영 Java(`FirstCallModel.route`): 가장 높은 갈래가 마지막 갈래(보통 질문)가 아니고 확률이 tau 미만이면 「보통 질문」.
- 이런 판정을 **비용 민감 결정**이라 하고, 「확신이 없으면 안전한 기본값으로」는 **거부 선택(reject option)** 의 변형이다.

### 2-12. TF-IDF (가장 단순한 기준선)

- 글을 「조각이 몇 번 나왔나」 벡터로 바꾸는 고전 방법. 조각 t, 문서 d에 대해

  `tfidf(t,d) = tf(t,d) · idf(t)`, scikit-learn 기본 `idf(t) = ln((1+n)/(1+df(t))) + 1`, 우리는 `sublinear_tf=True`로 `tf = 1 + ln(횟수)`, 마지막에 문서마다 길이 1로.

  흔한 조각(df 큼)은 낮게, 드문 조각은 높게.
- 우리 설정(`train.py`): `TfidfVectorizer(analyzer='char_wb', ngram_range=(2, 4), min_df=2, sublinear_tf=True)` — 낱말 경계 안의 **글자 2\~4개 조각**(한국어 활용형에 강함), 두 질문 이상에 나온 조각만.
- 결과: 같은 로지스틱 회귀(모든 칸 문턱 0.35, `train.py`)에서 작은 칸 F1 TF-IDF 38.2%, KURE 48.6% → 「뜻」을 담은 임베딩이 훨씬 낫다.

### 2-13. 문장 쌍 특징 (관계 분류기)

- 두 문장의 관계를 분류할 때 두 벡터 u(지금 질문), v(앞 질문)만 이어 붙이면 MLP가 「비교」를 스스로 배워야 한다. 비교에 쓸 값을 미리 만들어 넣는 고전 기법(InferSent, Conneau et al. 2017: `[u, v, |u−v|, u⊙v]`).
- 우리 입력 4,096 = `[u, v, w, u⊙v]`. w는 「앞 질문 + 공백 + 지금 질문」을 이어 붙인 글의 벡터(붙여 읽어야 뜻이 나오는 경우), ⊙는 원소별 곱(두 벡터가 같은 부호로 큰 차원에서 크게 양수, 부호가 다르면 음수. 이 곱을 모두 더하면 코사인).
- 학습 코드(`rel_train.py`) / 운영 코드(`MlFirstCall.features`):

  ```python
  def texts(x): return [x['q2'], x['q1'], x['q1'] + ' ' + x['q2']]
  def feat(x):
      a, b, c = (cache[t] for t in texts(x)); return np.concatenate([a, b, c, a * b])
  ```

  ```java
  static float[] features(float[] vector, float[] earlier, float[] joined) {
      int d = vector.length;
      float[] f = new float[4 * d];
      System.arraycopy(vector, 0, f, 0, d);
      System.arraycopy(earlier, 0, f, d, d);
      System.arraycopy(joined, 0, f, 2 * d, d);
      for (int i = 0; i < d; i++) {
          f[3 * d + i] = vector[i] * earlier[i];
      }
      return f;
  }
  ```

  학습과 운영의 입력 순서가 같아야 한다(학습–서빙 일치).

### 2-14. 가까운 것 찾기: 전수 내적 + 힙

- 질문 벡터 q와 책 81,078권의 내적을 **모두** 계산하고, 점수 상위 k개만 남긴다(정확한 최근접 이웃, 근사 색인 없음).
- 상위 k개는 크기 k의 **최소 힙**으로: 힙의 가장 작은 값보다 큰 점수가 오면 바꿔 넣는다. 계산량 = 내적 N×d(81,078 × 1,024 ≈ 8,300만 번 곱셈) + N·log k.
- 운영 Java(`BookVectorFile.nearest`, 그대로):

  ```java
  for (int row = 0; row < ids.length; row++) {
      int base = row * dim;
      float dot = 0;
      for (int i = 0; i < dim; i++) {
          dot += rows[base + i] * query[i];      // int8 줄 · 질문 벡터
      }
      float score = dot * scales[row];           // 줄의 scale을 마지막에 한 번
      if (best.size() < limit) {
          best.add(new int[] { Float.floatToIntBits(score), row });
      }
      else if (score > Float.intBitsToFloat(best.peek()[0])) {
          best.poll();
          best.add(new int[] { Float.floatToIntBits(score), row });
      }
  }
  ```

  점수는 「int8로 줄인 책 벡터와 질문 벡터의 내적」이라 정확한 코사인에 매우 가깝지만 같지는 않다(2-3절의 반올림 오차).

### 2-15. 순위 합치기: RRF

- 낱말 검색과 벡터 검색은 점수 척도가 다르다(낱말 검색 SQL의 자체 점수 vs 코사인). 점수를 더하는 대신 **순위만** 쓴다.
- **Reciprocal Rank Fusion**: 책 b가 목록 L에서 r등이면 `1/(k + r)` 점, 목록마다 더한다. 우리 k = **60**.

  `RRF(b) = Σ_L 1 / (60 + rank_L(b))`

  두 목록 모두 위에 있는 책이 가장 높다. k가 크면 1등과 10등의 차이가 완만해진다.
- 우리는 여기에 **칸 보너스**를 더한다: 질문의 칸(MLP 출력)과 책의 칸(`catalog.book_profile`, 사이클 26 전에는 `book-profiles.tsv`)이 맞은 칸 종류 수(분야·작은 분야·소설 여부·장르·나라·독자 등, 0\~8, `Shelves.matches`) × **0.004**(`shelf-bonus`). 그리고 질문이 빼 달라는 칸의 책과, 질문이 「소설 아님」인데 소설인 책은 맨 뒤로(`Shelves.excludes`). 운영 Java(`Fusion.rrf`, 줄임):

  ```java
  for (int i = 0; i < keyword.size(); i++) {
      if (keywordRank.putIfAbsent(keyword.get(i), i) == null) {
          score.merge(keyword.get(i), 1.0 / (k + i + 1), Double::sum);
      }
  }
  for (int i = 0; i < vector.size(); i++) {
      if (vectorRank.putIfAbsent(vector.get(i), i) == null) {
          score.merge(vector.get(i), 1.0 / (k + i + 1), Double::sum);
      }
  }
  score.replaceAll((id, sum) -> sum + bonus.applyAsDouble(id));   // 칸 보너스 = 0.004 × 맞은 칸 수
  …                                                                // 합이 큰 순, 같으면 낱말 순위 → 벡터 순위 → 책 번호로 정렬
  List<Long> ordered = new ArrayList<>(ids.stream().filter(id -> !last.test(id)).toList());
  ordered.addAll(ids.stream().filter(last).toList());              // 빼 달라는 칸의 책은 맨 뒤
  ```

  0.004의 크기 감: 한 목록에서 1등은 1/61 ≈ 0.0164, 2등은 1/62 ≈ 0.0161, 21등은 1/81 ≈ 0.0123이다. 1등과 21등의 차이(≈ 0.0040)가 칸 하나의 보너스와 같다. 곧 칸 하나(0.004)는 한 목록 안에서 순위 **20칸쯤**(아래쪽 순위일수록 점수 간격이 좁아 더 많이)에 해당해 작지 않고, 맞은 칸이 여럿이면 그 배수다.

### 2-16. 전이학습, 특징 추출, 합성 데이터

- **전이학습**: 큰 데이터로 배운 모델의 지식을 다른 작업에 옮겨 쓰는 것.
  - **특징 추출(우리 방식)**: 큰 모델을 얼려 두고 출력(벡터)만 특징으로 써서 작은 모델을 학습. 데이터가 적을 때(우리는 수천 개) 안전하고 빠르다.
  - **미세조정(fine-tuning)**: 큰 모델의 가중치까지 함께 학습. 데이터가 많아야 하고 비싸다. 우리는 하지 않았다.
- **합성 데이터**: 학습 질문과 정답을 Claude Sonnet이 만들었다. 큰 모델(LLM)의 판단을 작은 모델에 옮긴 셈이라 넓게는 **증류(distillation)** 에 가깝지만, 확률(soft label)이 아니라 정답 하나(hard label)를 배웠으므로 「LLM 라벨로 학습」이 정확한 말.
- **시드와 흔들림**: 신경망은 시작 가중치와 섞는 순서(그리고 드롭아웃)가 무작위라 같은 데이터로도 점수가 조금씩 다르다. 길 나누기·관계 실험(`route5b.py`, `rel_train.py`)에서 시드 3개(0, 1, 2)로 학습해 평균을 냈다(앙상블 아님). 운영 파일을 만드는 칸 머리 학습(`export_first_call2.py`)은 시드 0 한 번. 운영 파일은 모두 시드 0 모델 하나.

## 3. 우리가 실제로 한 일 (순서대로)

### 3-1. 질문을 벡터로 (학습 때와 운영 때)

- **학습 때(PC, Python)**: sentence-transformers로 원래 KURE-v1(float32)을 불러 한 번 만든 벡터를 파일에 캐시해 두고 재사용(`route_v2.py`가 `km`을 만들고 `route5b.py`가 그것을 이어 씀. `rel_train.py`도 같은 꼴).

  ```python
  km = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")
  …
      for q, v in zip(new, km.encode(new, normalize_embeddings=True, batch_size=64).astype(np.float32)): cache[q] = v
  ```

- **운영 때(서버, Java)**: int8로 줄인 ONNX 모델을 ONNX Runtime으로 돌린다. 토크나이저는 DJL의 HuggingFace 토크나이저(줄인 사전 `tokenizer-cut.json`, 최대 256조각). 모델 그래프 끝이 「CLS 벡터 → 길이로 나누기」라 출력이 바로 길이 1 벡터(`OnnxQuestionEncoder.encode`, 그대로):

  ```java
  Encoding encoding = tokenizer.encode(question);
  try (OnnxTensor idTensor = OnnxTensor.createTensor(env, new long[][] { encoding.getIds() });
          OnnxTensor maskTensor = OnnxTensor.createTensor(env, new long[][] { encoding.getAttentionMask() });
          OrtSession.Result result = session.run(Map.of("input_ids", idTensor, "attention_mask", maskTensor))) {
      return ((float[][]) result.get(0).getValue())[0];
  }
  …
  ```

  `attention_mask`는 「진짜 조각 = 1, 채운 칸(padding) = 0」으로 어텐션이 채운 칸을 보지 않게 하는 입력이다. 우리는 질문을 하나씩, 채우기 없이(`padding false`) 넣으므로 값이 모두 1이다(여러 질문을 길이 맞춰 한 번에 넣을 때 의미가 생김). 토크나이저는 맨 앞 `<s>`·맨 뒤 `</s>`를 붙이고(`addSpecialTokens`), 256조각을 넘으면 자른다. 운영에서는 같은 코드가 따로 둔 임베딩 서버(`embed-service`)에서 돌고, 백엔드는 HTTP로 벡터를 받는다([1-how-it-works 3-5절](1-how-it-works.md#3-5-임베딩-서버-embed-service)).
- 학습(float32)과 운영(int8)의 벡터가 조금 다르므로, 내보낼 때 같은 시험 질문을 두 벡터로 다시 재서 점수 차이를 확인했다(길 나누기 93.7 → 93.5%, [3-ml-models 8절](3-ml-models.md)).

### 3-2. 칸 머리 11개 학습

1. 데이터: 학습 질문 6,298개와 칸 정답(Sonnet). 칸마다 정답을 0/1 행렬로(다중 라벨) 또는 갈래 번호로(하나 고르기).
2. 모델: 2-8절 `mlp_model`(다중 라벨이면 BCEWithLogits, 하나 고르기면 CrossEntropy).
3. 문턱: dev(묶음 24)를 뺀 질문으로 학습한 모델을 dev에 돌려 2-11절처럼 칸마다 F1 최대 문턱을 고르고, 운영 모델은 dev까지 넣은 6,298개 전부로 다시 학습(하나 고르기 머리는 문턱이 없어 바로 전부로 학습).
4. 운영 판정(`FirstCallModel.labels`): 문턱 이상인 칸을 **칸 사전 순서대로** 앞에서 2개까지(확률 순이 아님 — 실험 스크립트가 그렇게 셌기 때문에 같게 맞춤).
5. 점수: 큰 칸 F1 89.8%, 작은 칸 99개 86.5%, 장르 71.0%(학습 6,298개, 보고서 5-3절). 로지스틱 회귀 기준선은 작은 칸 81.6%.

### 3-3. 길 나누기 머리 학습 (라우터 B)

1. 데이터: 두 덩어리를 합친다(`route5b.py`의 `np.vstack([Vg, X])`, `yg5 + y`).
   - 길 학습 질문 3,578개(`route5b.json`의 `n_train`): 5갈래(책 찾기 아님·제목만·앞 책 가리킴·책첵 사용법 질문·보통 질문) 갈래당 600개까지 + 책첵 질문처럼 보이는 책 질문 300개(「보통 질문」) + 짧은 장르·분위기 질문 300개(「보통 질문」).
   - 칸 학습 질문 6,298개(`Vg`): 「보통 질문」으로 일괄 넣는 것이 아니라, 질문마다 Sonnet이 단 표시(`notBook`·`titleLike`·`refersToShown`)대로 갈래를 정한다(`route_v2.py`의 `yg` → `yg5`). 그래서 대부분(6,152개)은 「보통 질문」이고 책 찾기 아님 49·제목만 40·앞 책 가리킴 57개가 섞인다. 「책첵 사용법 질문」은 없다.
2. 모델: 1,024 → 512 → 5, softmax, CrossEntropy(`route5b.py`의 `mlp5`, 2-8절과 같은 학습 설정).
3. tau: 2-11절 코드로, 시드 0 모델에서 0.55.
4. 점수(시드 3개 평균): 기존 시험 600개 93.9%(v1.16.0 판 94.6%), 짧은 질문 시험 97.3%(전 판 74%).
5. **배운 것(클래스 불균형)**: 처음엔 칸 학습 질문만으로 학습해 세 갈래가 각 1% 미만 → 58.5%. 갈래마다 고르게 400개를 더하자 94.5%(둘 다 4갈래 때, 시험 400개). 양보다 **고르게**가 결정적.

### 3-4. 관계 분류기 학습

1. 데이터: 관계 7가지 × 400쌍(new 399) 질문 쌍(Sonnet), 시험 700쌍.
2. 입력: 2-13절의 4,096 특징.
3. 모델: 4,096 → 512 → 7, softmax, CrossEntropy, 2-8절 설정(dev·문턱 없이 가장 높은 관계). 시드 3개 평균으로 학습량 곡선을 봄: 관계당 100/200/300/399쌍 → 93.8/96.0/95.9/**96.4%**(200쌍 뒤 평평 → 399에서 멈춤). v1.20.6까지의 운영 파일은 399쌍·시드 0 모델(`export_follow_up.py`). v1.21.0부터는 40쌍(ask_one 16, none 16, new 8)을 더해 다시 학습한 v2(시험 700쌍 96.1 → 96.0%).

### 3-5. 내보내기와 운영 계산

- PyTorch 모델에서 가중치 배열만 꺼내 우리만의 작은 파일 형식으로 쓴다(`export_first_call2.py`): `"CCFC1\n"` + JSON 머리 길이(int32) + JSON 머리(dim, hidden, 머리마다 이름·종류·라벨·출력 수, route면 tau, multi면 threshold·max 2) + 머리마다 `W1, b1, W2, b2`(float32, 리틀 엔디언).

  ```python
  W1, b1, W2, b2 = (m[0].weight.detach().numpy(), m[0].bias.detach().numpy(), m[3].weight.detach().numpy(), m[3].bias.detach().numpy())
  ```

  PyTorch `nn.Linear`의 `weight`는 [출력 × 입력] 모양이고 계산은 `y = x·Wᵀ + b`. Java는 같은 배열을 행 단위로 읽어 `W1[i*dim + j]`로 같은 계산을 한다(2-6절 코드).
- 왜 Java로 직접: 운영 서버(ARM 2코어, 메모리 2GB)에 PyTorch를 올리지 않고, 행렬곱 두 번이면 되는 작은 모델이라 순수 Java로 충분. 운영 로그의 `ml` 단계 중간값 12ms(벡터 만들기 `encode` 146ms가 훨씬 김).
- 출력이 쓰이는 곳: 길 → 검색을 할지·어떤 길로 갈지, 칸 → RRF 칸 보너스와 맨 뒤 보내기, 관계 → 앞 질문을 이을지([1-how-it-works 4-3·4-9절](1-how-it-works.md#4-3-ml-1차-ml)).

## 4. 라이브러리를 어떻게 썼나

| 라이브러리 | 어디서 | 무엇에 | 쓴 것 |
|---|---|---|---|
| **sentence-transformers** (Python) | PC 학습 | 원래 KURE-v1으로 질문·책 벡터 만들기 | `SentenceTransformer("nlpai-lab/KURE-v1")`, `encode(..., normalize_embeddings=True, batch_size=64)` |
| **PyTorch** (Python) | PC 학습 | MLP 정의·학습·저장 | `nn.Sequential`, `nn.Linear`, `nn.ReLU`, `nn.Dropout(0.3)`, `nn.CrossEntropyLoss`, `nn.BCEWithLogitsLoss`, `torch.optim.AdamW(lr=1e-3, weight_decay=1e-4)`, `torch.manual_seed`, `torch.randperm`, `loss.backward()`, `opt.step()`, `m.eval()`, `torch.save` |
| **scikit-learn** (Python) | PC 실험 | 기준선 | `TfidfVectorizer(char_wb, 2~4)`, `LogisticRegression(C=4, max_iter=3000)`, `OneVsRestClassifier` |
| **NumPy** (Python) | PC | 벡터 캐시, 정규화, int8 양자화, 문턱 탐색, 파일 쓰기 | `np.linalg.norm`, `np.round(...).clip(-127,127).astype(np.int8)`, `np.arange`, `tobytes()` |
| **ONNX / ONNX Runtime** | 모델 변환(PC), 운영(Java `ai.onnxruntime`) | KURE-v1을 int8 ONNX로, 운영에서 질문 벡터 계산 | PC: `torch.onnx.export`(opset 17), `onnxruntime.quantization.quantize_dynamic(per_channel=True)`. 운영: `OrtEnvironment`, `OrtSession`(스레드 수 지정), `OnnxTensor` |
| **DJL HuggingFace tokenizers** (Java) | 운영 | 질문을 조각 id로 | `HuggingFaceTokenizer.newInstance(tokenizer-cut.json, maxLength 256)` |
| 순수 Java | 운영 | MLP 계산, 내적·상위 k, RRF | `FirstCallModel`, `BookVectorFile`, `Fusion` (외부 라이브러리 없음) |

## 5. 남에게 설명하기

**30초판**
> 질문을 한국어 문장 임베딩 모델(KURE-v1)로 1,024차원 벡터로 바꾸고, 그 위에 은닉층 하나짜리 작은 신경망을 작업마다 얹었습니다. 「책 찾는 질문인가」, 「어느 분야인가」, 「앞 질문과 어떤 관계인가」를 각각 분류합니다. 학습 데이터는 LLM으로 만들었고, 그 덕분에 매 검색마다 하던 LLM 호출 하나를 없앴습니다. 책은 미리 벡터로 만들어 두고 질문 벡터와 코사인 유사도로 가까운 책을 찾습니다.

**2분판** (위에 더해)
- 인코더는 학습하지 않고 고정(특징 추출 방식의 전이학습). 수천 개 데이터로는 인코더까지 학습하기 어렵기 때문.
- 분류기는 Linear(1024→512) → ReLU → Dropout 0.3 → Linear(512→n). 하나 고르기는 softmax + 교차 엔트로피, 여럿 고르기는 출력마다 sigmoid + 이진 교차 엔트로피. AdamW, 미니배치 64, 60 epoch.
- 확률을 결정으로 바꾸는 문턱은 dev 셋에서 F1이 최대인 값으로, 「책 찾기 아님」 판정은 오분류 비용이 커서 확신 0.55 이상일 때만.
- 로지스틱 회귀(linear probe)를 기준선으로 비교해 MLP가 작은 칸 F1 81.6 → 86.5%로 확실히 나았다.
- 가장 큰 교훈은 클래스 불균형: 갈래를 고르게 맞추자 58.5 → 94.5%.
- 운영은 PyTorch 없이 Java로 행렬곱을 직접(운영 로그 `ml` 단계 = 머리 12개, 이어 묻기면 관계 분류기까지, 중간값 12ms), 질문 인코더는 int8 ONNX로 2.2GB → 385MB.

**자주 받을 질문**

| 질문 | 답 |
|---|---|
| 왜 LLM에게 다 맡기지 않았나? | 1차 분류는 정답이 정해진 작은 분류 문제라 작은 모델로 충분하고, LLM 호출 1\~2.4초와 비용을 없앨 수 있었다. 최종 카드 품질은 같았다(57문항 두 번 평균). |
| 왜 MLP? 더 큰 모델은? | 입력이 이미 좋은 임베딩이라 은닉층 하나로 충분했고, 데이터가 수천 개라 더 크면 과적합 위험. 로지스틱 회귀보다 나은지를 기준선으로 확인했다. |
| 앙상블인가? | 아니다. 머리 13개는 서로 다른 질문(길·칸·관계)에 답하는 독립 모델이고, 시드 여러 개는 흔들림을 재려고만 돌렸다. |
| 코사인 유사도를 왜 내적으로? | 벡터를 길이 1로 정규화해 두면 코사인 = 내적이라 곱셈·덧셈만으로 끝난다. |
| int8로 줄여도 괜찮나? | 원래와의 벡터 닮음 0.989(질문 인코더), 분류 정확도 93.7 → 93.5%로 거의 그대로. |
| 데이터를 LLM이 만들었는데 믿을 수 있나? | 사람이 단 정답이 없다는 한계가 있다. 그래서 시험 질문을 학습과 따로 만들고, 최종 결과(카드 품질)는 별도 채점으로 확인했다. |
| 문턱은 어떻게? | dev 셋에서 F1 최대(칸), 비용 비대칭 조건 아래 정확도 최대(길). |
