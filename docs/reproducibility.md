# 재현성

## 외부 서비스 없이 가능한 실행

Python3.10+ 표준 라이브러리로 다음 명령을 실행합니다.

```bash
python scripts/reproduce_historical.py data/recovered/timing-events.jsonl --summary output/summary.json --csv output/timings.csv
python -m unittest discover -s tests -v
```

2026-09-10 현재20개 tests가 통과했습니다. 과거90행 재집계, 보고서 수치 일치, 모드 혼입, malformed JSON/duplicate key, 비정상시간·개인정보필드, overlapping task duration 처리와 CLI 종료코드를 검증합니다.

기존 신규 측정 계약의 합성 fixture 검사도 보존했습니다. 합성값은 과거 서비스 성능으로 사용하지 않습니다.

## 당시 도구

`legacy/`에 보존했습니다. 원 Python/pandas lock이 없어 당시와 byte-level로 같은 환경을 복구했다고 주장하지 않습니다. 현재 제공된 pandas 분석은 설치된 환경에서 과거 CSV의 평균/p95를 재출력했습니다.

PySpark 경로가 있으나 현재 Python 환경에 PySpark가 없어 실행하지 않았습니다. source에서 확인한 `coalesce(1)` 저장은 단일 출력 파일 편의를 위한 것이며 대용량 분산 처리 성과의 증거가 아닙니다.

## Live 실험 전 복구할 조건

서비스의 실험 commit, Java17/의존성, 실행 모드/스레드 설정, 논문 색인과 embedding, API credential, 질의집합, cache/warmup을 맞춰야 합니다. 과거 PowerShell sweep은 helper scope와 parameter 불일치 등 제약이 있으므로 그대로 자동 실행하지 않습니다. 신규 검증된 시나리오에서 server actual mode와 결과를 연결해야 합니다. 외부 API 비용이 발생하는 실행은 이번 공개 작업에서 제외했습니다.
