# 지연 측정 계약 (2026-09-09 신규 도구)

`scripts/analyze_timings.py`는 새 JSONL 계약을 검증하는 오프라인 도구다. 과거 `RAG_TIMING` 필드나 파서를 복원한 것이 아니다. 실제 서버 계측 연결, 병렬 실행, 외부 LLM 호출, 부하 생성은 구현하지 않았다.

## 무엇을 측정했는가

| 항목 | 확보한 과거 근거 | 새 도구의 처리 |
| --- | --- | --- |
| 16.7251 → 13.2306초 | 보고서의 서버 total p95, 모드별 30회 | 원시 요청이 없어 재계산하지 않음 |
| 평균 | 10.72283 → 10.40277초 | p95와 구분 |
| Retrieval 평균 | 5.83230 → 4.99980초 | rerank만의 감소라고 단정하지 않음 |
| TPS/동시 사용자 | 측정 근거 없음 | 처리량을 계산하지 않음 |
| LLM 네트워크 지연 | 서버 LLM 호출 구간은 통신·대기·생성 포함 가능 | 순수 네트워크 시간으로 부르지 않음 |

클라이언트 end-to-end는 요청 시작부터 최종 응답 수신까지, server_total은 서버 계측 시작부터 종료까지다. 스트리밍 TTFT와도 구분해야 한다. TPS/RPS는 명시한 관측 기간의 완료 요청 수/초이며, 지연의 역수나 참여자 수가 아니다. 정확한 historical timer 경계·백분위 구현은 원본 코드 확보 전 미확인이다.

## 입력 계약

UTF-8 JSONL, 한 줄 한 요청. 아래 예시는 전부 **합성 입력**이다. 성능 실측값이 아니다. 요청/질의 ID에 개인 정보나 질의 원문을 넣지 않는다.

- `schema_version`: 정수 1.
- `run_id`, `request_id`, `query_id`: 비어 있지 않은 식별자. run/request 쌍 중복은 거절한다.
- `actual_mode`: 서버에서 확인한 SEQUENTIAL / PARALLEL_OLD / PARALLEL_SYNC. 클라이언트가 요청한 모드만으로 채우면 안 된다. 도구가 실제 서버 동작을 인증하지는 못한다.
- `corpus_id`, `config_id`: 색인/질의 조건 및 설정을 추적할 식별자. config에는 코드 버전, 모델, 풀 크기, top-k, 타임아웃, 요청 순서/동시성 등 실험 조건을 연결한다.
- `cache_state`: cold / warm / disabled / unknown. unknown은 경고한다. 같은 run 안에서 값이 섞이면 거절한다.
- `timing_scope`: server_total / client_e2e. 한 run에는 한 측정 구간만 사용한다.
- `phase`: warmup / measurement. 워밍업은 집계에서 제외하고 개수를 출력한다.
- `outcome`: success / error / timeout. duration_ms는 해당 결과까지 걸린 유한한 음수 아닌 ms다.
- `stage_ms`: 선택 필드. server_total만 허용. dense, sparse, retrieval, rerank, llm 중 확인한 구간만 기록한다. 각 구간은 전체보다 길 수 없다. 검색 하위 구간 및 병렬 구간은 겹치므로 합산하지 않는다.

성공 요청만 평균/p95를 계산하되 실패율 분모에는 모든 measurement 시도를 포함한다. 실패가 많으면 성공 p95만으로 개선을 주장할 수 없다. stage별 n을 함께 출력하여 누락을 숨기지 않는다. 같은 run에서 모드·설정·색인·캐시·측정 구간이 섞이면 거절하고, 서로 다른 run은 통합하지 않는다. 새 p95는 nearest-rank `ceil(.95*n)`이며 과거 pandas 보간법 여부는 미확인이다. 30건의 p95는 소수 표본의 영향을 크게 받으므로 반복 측정이 필요하다.

## 실행

Python 3.10 이상, 외부 패키지 없음. 저장소 루트에서:

```bash
python scripts/analyze_timings.py tests/fixtures/synthetic-timings.jsonl --expected-mode SEQUENTIAL
python -m unittest discover -s tests -v
```

예제 출력은 파서 동작 확인용이다. 실서비스 속도, 부하 한계, 병렬화 효과를 측정한 결과로 인용하지 않는다. 잘못된 입력은 종료 코드 2로 실패하며 원문 로그를 오류 메시지에 출력하지 않는다.

## 실제 재실험을 위한 순서

원본 브랜치와 원시 로그를 확보하고 timer 경계 및 실제 모드를 확인한다. 질의/색인/모델/설정을 고정하고 워밍업과 캐시 조건을 기록한다. 모드 순서를 교차하여 반복하며 실패·타임아웃을 남긴다. 서버 단계 지연과 클라이언트 지연을 별도로 수집하고 검색 품질도 비교한다. 부하 실험은 동시 요청 수·관측 시간·완료/실패 수를 따로 기록한 뒤 RPS를 산출해야 한다. 이 도구의 합성 입력으로 해당 절차가 완료되었다고 주장하지 않는다.
