# 측정 데이터

| 데이터 | 출처 | 규모와 형식 | 용도 |
|---|---|---|---|
| timing-events.jsonl | 3개 과거 bootrun 로그에서 타이밍 JSON 필드만 추출 | 90행 × 15필드, 24,909바이트 | 실제 서버 모드별 단계 지연 재집계 |
| rag_timing_metrics.csv | 당시 로컬 백업 | 90행 × 16열, timestamp 포함 | 당시 pandas 분석 직접 실행 |
| 초기 client CSV | 2025-12-02 Git snapshot | 2개 파일 각30행 × 5열 | 초기 측정과 최종 측정 구분 |
| reported-latency.csv | 원 보고서 최종 표 전사 | 모드별 집계3행 | 복구 전 전사값과 교차 검증 |

이 데이터는 프로젝트에서 생성한 합성 질의의 성능 기록입니다. 원본 로그 전체에는 환경 정보와 질문 내용이 섞여 있어 공개하지 않습니다. JSON 공개본에는 실제 타이밍 및 실행 설정 필드만 보존했습니다. 원 로그 SHA-256과 추출 내역은 [source manifest](source-manifest.json)에 남겼습니다.

최종 기록 필드는 mode/strategy, totalMs, queryExpansionMs, retrievalMs, denseMs, sparseMs, mergeMs, rerankMs, contextMs, llmMs, guardrailMs, threadPoolSize, parallelGranularity, nestedDenseSparse입니다. 데이터 자체에 라이선스를 임의 부여하지 않았으며 타인의 논문/서적 원문과 사용자 상담 데이터는 포함하지 않았습니다.

## 품질 검사

신규 재집계 도구는 정확한 schema, 실제 mode-strategy 조합, finite/nonnegative 시간, wall-clock stage≤total, 모드 내 설정 일관성을 확인합니다. 모드 라벨을 외부 입력으로 덮어쓰지 않습니다.

기록에 요청 ID가 없어 같은 요청이 중복 저장되었는지 완전히 판별할 수 없습니다. 값이 같다는 이유로 임의 중복 삭제를 하지 않았습니다. 워밍업이나 실패 요청을 알아낼 수 없으므로 이들을 임의 제거하거나 성공률100%라고 표시하지 않았습니다.

## 처리와 저장

허용 필드 추출 → 값 검증 → mode별 그룹 집계 → JSON/CSV 저장입니다. RAG 문서의 수집·색인은 [RAG dataset 문서](https://github.com/YIM551/rag-sleep-assistant/blob/master/docs/dataset.md) 범위입니다. 분석 결과는 파일에 저장하며 별도 분석 DB나 분산 데이터 웨어하우스를 구축한 프로젝트로 표현하지 않습니다.
