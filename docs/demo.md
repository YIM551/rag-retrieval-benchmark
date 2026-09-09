# 실행 없이 확인하는 시연

대표 자료는 원 보고서 p11의 [평균 지연 그림](../assets/historical-mean-latency.jpg)과 [실제 콘솔 집계 화면](../assets/historical-console.png)입니다. 개인정보가 없는 기존 내장 이미지를 그대로 추출했습니다. 새 화면을 과거 실행처럼 만든 것이 아닙니다.

1. README 결과 표와 이미지를 확인합니다.
2. `python scripts/reproduce_historical.py data/recovered/timing-events.jsonl`을 실행합니다.
3. mode별 n=30, 평균, 선형 보간p95를 당시 콘솔 화면과 대조합니다.
4. [performance](performance.md)에서 검색 wall-clock과 전체시간의 차이를 확인합니다.

빅데이터 실험 자체의 전용 동영상은 확인하지 못했습니다. [RAG 앱의 모바일 시연](https://github.com/YIM551/rag-sleep-assistant)과 로그 분석 프로젝트의 증거 범위는 다릅니다.
