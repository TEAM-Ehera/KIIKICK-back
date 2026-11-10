# 친구 프로필에서 경기·경기장·상세 스탯·히트맵 조회

기준 경로는 `/api/v1`이다. 모든 요청에 `Authorization: Bearer <accessToken>`을 보낸다.
친구 요청 수락으로 양쪽 모두 조회할 수 있다. 별도의 `FRIEND_COMPARISON` 동의는 필요하지 않으며, 기존 친구 비교 분석 API의 동의 정책은 그대로다.

## 호출 순서

1. `GET /users/{userId}/profile`에서 `relationship=FRIEND`를 확인한다.
2. `GET /friends/{userId}/matches?status=COMPLETED`로 친구의 완료 경기 목록을 조회한다.
3. 선택한 경기의 `/result`와 `/venue`를 조회한다.
4. `/result/quarters`로 쿼터별 상세 스탯을 표시하고, 선택한 쿼터의 `/heatmap`을 지도에 표시한다.

| 기능 | GET 경로 | 응답 data |
| --- | --- | --- |
| 경기 목록 (기존) | `/friends/{userId}/matches` | 기존 MatchResponse 배열 |
| 경기 상세 (기존) | `/friends/{userId}/matches/{matchId}` | 기존 MatchResponse |
| 경기 결과 (기존) | `/friends/{userId}/matches/{matchId}/result` | 경기·쿼터 수·득점·도움 합계·요약 계산 상태 |
| 쿼터 결과 목록 (기존) | `/friends/{userId}/matches/{matchId}/result/quarters` | 기존 QuarterResult 배열 |
| 경기장 (추가) | `/friends/{userId}/matches/{matchId}/venue` | 아래 경기장 객체 또는 null |
| 개별 쿼터 상세 (추가) | `/friends/{userId}/matches/{matchId}/quarters/{quarterId}/result` | 기존 QuarterResult |
| 쿼터 히트맵 (추가) | `/friends/{userId}/matches/{matchId}/quarters/{quarterId}/heatmap` | 아래 Heatmap 객체 |

목록은 `cursor`, `limit`(기본 20, 1~100)을 사용한다. 경기 목록의 선택적 `status`와 커서 계약은 기존 API를 유지한다. 완료 경기 탭에서는 반드시 `status=COMPLETED`를 보낸다. 정렬은 생성 시각 내림차순이다.

모든 친구 경기 성공 응답은 `Cache-Control: no-store`, `X-Request-Id`를 포함한다. 앱도 친구 해제·차단 후 이미 표시한 기록을 지워야 한다. 기존 버전 응답의 ETag를 유지한다. 신규 API는 `meta.requestId`, `meta.serverTime`, `meta.matchVersion`을 반환한다. 읽기 요청에는 본문·Idempotency-Key·If-Match가 필요하지 않다.

## 권한과 오류

- 매 요청마다 수락된 친구 관계·양방향 차단·탈퇴·공개 프로필 상태를 다시 확인한다. 해제·차단 후 기존 커서로도 접근할 수 없다.
- `matchId`는 경로의 `userId` 소유여야 하며, `quarterId`는 그 경기에 속해야 한다.
- 미인증은 `401`, 비친구·차단·비공개·잘못된 소유자/소속·없는 리소스는 `404 RESOURCE_NOT_FOUND`다.
- 경기 결과·개별 쿼터 상세·히트맵은 완료 경기용이다. 완료 전에는 `409 RESULT_NOT_READY`다. 개별 쿼터도 완료되어야 한다.
- 잘못된 limit은 `400 INVALID_REQUEST`, 커서 오류는 `400 INVALID_CURSOR`다. 쿼터 페이지 버전 변경은 기존 `409 CURSOR_SNAPSHOT_CHANGED`다.
- 친구에게 경기 수정·운동 업로드 권한은 생기지 않는다. GPS 시각별 경로와 심박 원본 API는 소유자 전용이다.

## 경기장 응답

```json
{
  "id": "00000001-0000-4000-8000-000000000001",
  "name": "풋살장",
  "address": "서울시 ...",
  "cornerA": { "latitude": 37.0, "longitude": 127.0 },
  "cornerB": { "latitude": 37.0, "longitude": 127.001 },
  "cornerC": { "latitude": 37.001, "longitude": 127.001 },
  "cornerD": { "latitude": 37.001, "longitude": 127.0 }
}
```

이름·주소는 경기 당시 스냅샷이다. `cornerA`~`cornerD`는 현재 연결된 저장 경기장의 보정된 좌표이며 역사적 스냅샷은 아니다. 좌표가 없으면 각 값은 null이다. 경기장 삭제 후에도 이름·주소를 유지하고 `id`와 좌표는 null로 반환한다. 경기장 정보 자체가 없으면 `data: null`이다. 친구의 다른 저장 경기장 목록을 공개하지 않는다.

## 상세 스탯 응답

기존 [결과 API](results.md)의 QuarterResult를 그대로 사용한다.

- `quarter`: 쿼터 번호·기간·상태
- `stats`: 골·어시스트
- `position`: 대표 포지션
- `workout`: 운동 기간·칼로리·거리
- `summary`: 최고/평균속도·고강도 이동거리·가감속·방향전환·평균/최대심박·TRIMP 등 계산된 지표

아직 입력/수신되지 않은 항목은 null일 수 있다. `summary.calculationStatus`와 `metricStatus`를 확인하고, 미계산·실패·원본 누락을 숫자 0으로 표시하지 않는다.

## 히트맵 응답

```json
{
  "quarterId": "00000002-0000-4000-8000-000000000002",
  "status": "READY",
  "cellSizeDegrees": 0.00005,
  "sampleCount": 3,
  "cells": [
    { "latitude": 37.000025, "longitude": 127.000025, "sampleCount": 2 },
    { "latitude": 37.000075, "longitude": 127.000025, "sampleCount": 1 }
  ]
}
```

위 예시는 `data` 내부다. `cells`의 좌표는 위경도 격자 중심이며 `sampleCount`를 히트맵 가중치로 사용한다. 격자 간격은 위도·경도 각각 0.00005도(위도 약 5.6m, 경도 간격은 위도에 따라 다름)다. 가중치는 샘플 개수이며 체류 시간이나 이동거리 비율이 아니다. 경기장 경계 밖의 점을 잘라내거나 경기장 좌표계로 변환하지 않는다.

DB에서 쿼터 운동 파일 하나의 GPS를 격자별로 집계한다. 위도 -90~90, 경도 -180~180, 수평 오차 0~20m, 속도 0~12m/s인 점만 사용한다. 원본 시각·속도·고도·진행 방향은 반환하지 않는다. 요약 계산 작업이 진행 중이어도 저장된 GPS로 조회할 수 있다.

| status | 의미 | cells / sampleCount |
| --- | --- | --- |
| READY | 유효한 GPS가 있음 | 격자 배열 / 유효 샘플 수 |
| MISSING_WORKOUT | 운동 파일 미수신 | `[]` / 0 |
| NO_VALID_POINTS | GPS 없음 또는 유효한 점 없음 | `[]` / 0 |
