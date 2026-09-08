# RAG Retrieval Benchmark

RAG 실행 모드별 구조화 로그를 수집·변환·집계하여 병렬 검색의 평균 지연과 p95를 비교한 성능 분석 프로젝트입니다.

**핵심 결과: 최종 보고서에서 개선 병렬 모드의 평균은 약 3.0%, p95는 약 20.9% 감소.** 원시 로그와 실험 브랜치가 미확보되어, 공개본은 보고서 기반 분석과 표 데이터로 구성합니다.

## Project Overview

초기 클라이언트 측 측정은 실행 모드 라벨과 실제 서버 모드가 섞여 성능을 과대평가했습니다. 보고서는 서버 모드 바인딩과 요청별 JSON 계측을 정리한 뒤 다시 측정한 결과를 최종 결론으로 사용합니다. 데이터 엔지니어 관점의 핵심은 측정 데이터의 생성·변환·검증입니다.

## Recruiter Snapshot

| 항목 | 내용 |
| --- | --- |
| 유형 | 캡스톤 RAG 백엔드를 확장한 빅데이터 과목 성능 연구 |
| 역할 | 임나경 단독 명의 결과보고서. 원본 브랜치의 파일별 작성 이력 확인 필요 |
| 기술 | 보고서: Java 17/Spring Boot, CompletableFuture, PowerShell 7, Python 3.11/pandas/matplotlib |
| 데이터 | 모드별 30회 요청의 RAG_TIMING 로그. 공개 CSV는 보고서의 집계표 전사본 |
| 핵심 구현 | 모드 바인딩, 단계별 타이밍, 로그 → CSV → 통계 |
| 결과 | Sequential 16.7251s → Parallel Sync 13.2306s p95, 보고서 기준 |

## Architecture

다음은 **보고서에서 설명하는 실험 흐름**입니다. 원본 스크립트 구현을 직접 검증한 그림이 아닙니다.

```mermaid
flowchart LR
  Q[Repeated queries per mode] --> B[Spring Boot RAG execution]
  B --> J[RAG_TIMING JSON logs]
  J --> X[PowerShell extraction]
  X --> C[CSV metrics]
  C --> A[Python aggregation]
  A --> R[Mean / p95 / stage breakdown]
```

## Tech Stack

보고서에서 Java/Spring Boot를 계측 대상, Qdrant/Lucene을 검색 구성, PowerShell을 로그 추출, Python/pandas/matplotlib을 분석 도구로 설명합니다. Spark/Hadoop/Kafka 또는 다중 노드 분산 처리는 확인되지 않아 기술 목록에 넣지 않습니다.

## Key Features

- 실제 모드와 라벨 불일치 → enum/config 바인딩 및 모드 로그 → 초기 수치의 신뢰성 문제 발견.
- 전체 시간만으로 병목을 알기 어려움 → Dense/Retrieval/LLM 단계 계측 → Retrieval 비중과 병렬화 범위 구분.
- 평균만으로 긴 대기를 설명하기 어려움 → p95 비교 → 평균 개선이 작아도 tail latency 개선을 관찰.

## How It Works

보고서 부록은 `RagExecutionMode`, `RagProperties`, `RagQueryService`, `RagTimingRecord`와 `run_rag_with_mode.ps1`, `benchmark_rag_client.ps1`, `extract_rag_timing.ps1`, `analyze_rag_timing.py`를 설명합니다. 실제 실험 코드는 `feat/rag-parallel-retrieval-v3` 브랜치에 있다고 적혀 있으나 원격 저장소 주소는 미확보입니다. 현재 제출 소스에는 해당 enum/record가 없으므로 동일 실험본이라고 간주하지 않았습니다.

## My Contribution

결과보고서는 임나경 단독 명의이며 계측 설계와 실험 분석을 기술합니다. 기존 팀 백엔드 전체의 소유·작성 권한과는 구분합니다. 커밋 단위 기여는 TODO입니다.

## Results

각 모드 30회. **보고서 표 5-2/5-3 전사이며 재실험 아님.**

| 모드 | 평균 ms | p95 ms | Retrieval 평균 ms | Dense 평균 ms | LLM 평균 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| SEQUENTIAL | 10722.83 | 16725.10 | 5832.30 | 2257.73 | 3028.77 |
| PARALLEL_OLD | 12425.83 | 22273.60 | 5851.93 | 2302.13 | 3995.97 |
| PARALLEL_SYNC | 10402.77 | 13230.60 | 4999.80 | 1527.13 | 3553.93 |

원시 요청값 없이 p95를 재계산할 수 없습니다. [표의 기계 판독본](data/reported-latency.csv)은 90개 요청 로그가 아니라 3행의 보고된 집계치입니다. 최종 수치와 모드가 검증되지 않은 초기 수치를 합치지 않았습니다.

## Getting Started

```bash
git clone https://github.com/YIM551/rag-retrieval-benchmark.git
cd rag-retrieval-benchmark
python scripts/summarize_reported_results.py
```

Python 3 표준 라이브러리만으로 표의 상대 차이를 계산합니다. 이 스크립트는 **2026년 포트폴리오 정리 때 추가한 전사 검산 도구**이며 당시 실험 코드의 복구본이 아닙니다. 실제 벤치마크 재실행에는 원본 브랜치, 환경 설정, 문서 색인, 질의 세트, 원시 로그가 필요합니다.

## Project Structure

`data/reported-latency.csv`: 보고서 표 / `scripts/summarize_reported_results.py`: 전사값 검산 / `docs/`: 공개 정책과 메타데이터.

## Technical Challenges

문제: 초기 병렬 실험이 과도하게 좋아 보임 → 원인: 모드 라벨/바인딩 및 캐시·워밍업 조건 혼재 → 보고서의 해결: 서버 로그에 실제 mode와 단계별 시간 기록 → 배운 점: 관측 데이터의 생성 경로부터 검증해야 성능 해석이 가능합니다.

기존 병렬 모드의 악화는 관측 결과입니다. 스케줄링 오버헤드라는 설명은 보고서의 해석이며 독립적인 CPU/스레드 프로파일링으로 원인을 확정한 것은 아닙니다.

## Limitations

단일 서버·제한된 스레드 설정·수천 건 수준 색인·모드별 30회·외부 LLM 변동성이 한계입니다. 동시 사용자 처리량 실험이나 대규모 분산 시스템 구축으로 표현하지 않습니다. 응답 품질과 통계적 유의성은 미검증입니다.

## Future Work

원시 로그 및 브랜치 확보, 모드/설정/질의 ID를 포함한 데이터 계약, 실패 요청 처리·반복 실험·신뢰구간, 검색 품질과 지연의 공동 평가를 우선합니다.

## References

「수면 상담 RAG 시스템의 병렬 Retrieval 성능 분석」, 2025-12-10, 인쇄 pp.7–9, 11–12, 17–18, 19–23. 개인정보와 원본 이미지의 권리 확인 전 PDF는 미공개.
