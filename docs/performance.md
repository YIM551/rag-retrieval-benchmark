# 측정 정의와 재현 결과

## 최종 실험

과거 최종 bootrun 로그 3개에서 각각30건을 복구했습니다. 서버가 기록한 mode 기준이며 threadPoolSize=1, FINE, nestedDenseSparse=true가 모든 행에서 확인됩니다. 스레드풀 값은 클라이언트 동시 사용자 수가 아닙니다.

| 비교: SEQUENTIAL → PARALLEL_SYNC | 순차 ms | 개선병렬 ms | 감소율 |
|---|---:|---:|---:|
| 전체 평균 | 10722.833333 | 10402.766667 | 2.98% |
| 전체 p95 | 16725.1 | 13230.6 | 20.89% |
| Retrieval 평균 | 5832.3 | 4999.8 | 14.27% |
| Dense 작업시간 평균 | 2257.733333 | 1527.133333 | 32.36% |

수치는 [90건 원자료](../data/recovered/timing-events.jsonl)를 [재집계한 JSON](../data/recovered/reproduced-summary.json)에서 계산합니다. 원본 pandas 스크립트도 같은 평균/p95를 출력했습니다. 새 서버 실행 결과가 아닙니다.

## 무엇을 잰 값인가

- `totalMs`: 서버 요청 처리 전체. 클라이언트 네트워크 왕복을 포함한 end-to-end와 다릅니다.
- `queryExpansionMs`: 검색 전 질의 확장.
- `retrievalMs`: retrieveWithFilters 호출의 실제 경과시간. Dense/Sparse, merge, filter/MMR, 선택적 rerank, citation 구성까지 포함하며 LLM 생성은 제외합니다.
- `contextMs`: 최종 prompt build.
- `llmMs`: 외부 모델 API 호출 구간. 모델 추론만 따로 분리한 값은 아닙니다.
- Dense/Sparse/Merge 세부 시간: 여러 작업의 누적값일 수 있습니다. 병렬 작업의 합을 retrieval wall-clock으로 사용하지 않습니다.

과거 pandas p95는 `(n-1)*0.95` 위치의 **선형 보간**입니다. 신규 측정 계약 도구 `analyze_timings.py`의 nearest-rank와 정의가 달라 같은 입력이라도 p95가 다를 수 있습니다. 과거 재현은 `reproduce_historical.py`를 사용합니다.

## 초기 실험을 섞지 않은 이유

`legacy/sleepwell-backend/benchmarks/20251202-seq-par`에는 초기 client CSV60건과 당시 분석이 있습니다. 평균 15,772.4/12,073.3ms의 비교이며 최종 서버 로그90건과 다른 실행입니다. 서버 실제 모드·캐시·warm-up이 검증되지 않았으므로 이를 최종 개선율로 주장하지 않습니다.

## 실행 가능 범위

오프라인 로그 재집계, schema 검사, 회귀 테스트20개는 수행했습니다. 외부 API를 호출하는 live benchmark, 실제 사용자 부하, GPU/CPU 프로파일링, 분산 Spark 성능 검증은 실행하지 않았습니다. 실제 endpoint 테스트는 API 비용과 실행환경을 준비한 뒤 [RAG 저장소](https://github.com/YIM551/rag-sleep-assistant)의 로컬 부하 시나리오를 참고할 수 있습니다.

## 한계

mode별30건, 반복 횟수/신뢰구간 미확인, outcome/requestID/warmup/cache/corpus hash 부재로 일반화에는 제약이 있습니다. 오류율·TPS·동시 사용자수는 미확인입니다. P95 감소를 '최악의 응답시간 감소' 또는 통계적 유의성이 입증된 개선으로 바꾸어 말하지 않습니다.
