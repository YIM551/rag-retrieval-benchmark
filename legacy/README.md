# 복구한 당시 구현

이 디렉터리는 2025년 Git source와 초기 측정 결과를 보존합니다. 새 코드의 정상 실행을 뜻하지 않습니다. `sleepwell-backend/analyze_rag_timing.py`의 오프라인 통계 경로는 복구한 CSV로 확인했습니다.

## 알려진 실행 제약

- root sweep3개는 주석 종료자 `!>`가 유효한 PowerShell 종료자 `#>`와 다릅니다.
- 일부 job에 batch 값이 전달되지 않으며, helper함수/using변수와 parallel runspace 범위가 불일치합니다.
- `benchmark_rag_parallel_strategies.ps1`에서 run script의 실제 parameter와 다른 ExecMode/Strategy를 사용합니다.
- client ModeLabel은 서버 실제 mode를 변경하지 않습니다.
- 원 extract_rag_timing의 Mode/Strategy 인수는 서버 기록을 덮어쓸 수 있습니다.
- 예전 plot script가 세부 시간 합으로 RetrievalMs를 다시 계산하는 것은 병렬 구간 해석에 부적합합니다.
- 오래된 RAG_BENCHMARKING.md의 설정명·로그 marker·명령과 최종 source가 일부 다릅니다.

현재 권장 오프라인 실행은 [README](../README.md)를 따릅니다. Live benchmark는 이들 제약과 외부 API 환경을 해결한 후 실행해야 하며 이번 작업에서 호출하지 않았습니다.
