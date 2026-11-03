# watchOS·iPhone 명령 동기화

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md) · [응답 필드 사전](schemas.md) · [정책 결정](../decisions.md)

계약 버전: **v0.4 · 2026-11-02 · 구현 전 설계**

요구사항 F10. 실제 측정 파일은 Watch → iPhone으로 전달되고 서버에는 WORK-01로 업로드한다. 이 문서의 명령 큐는 오프라인 경기/쿼터 조작까지 지원할 경우의 기술 초안이다. 지원 범위는 P10에서 정하며 센서 배열을 명령 배치에 넣지 않는다.

## 명령 종류

| type | payload | 일반 API 대응 |
| --- | --- | --- |
| START_MATCH | {} | GAME-01 |
| START_QUARTER | {quarterId} | GAME-02 |
| END_QUARTER | {quarterId} | GAME-08 |
| SET_QUARTER_STATS | {quarterId,goals,assists} | GAME-14: 절대 합계 교체 |
| SET_POSITION | {quarterId,positionCode} | GAME-10: 대표 하나 |
| COMPLETE_QUARTER | {quarterId} | GAME-11 |
| END_MATCH | {} | GAME-12: 즉시 종료 200 |

## API 바로가기

| ID | 기능 | Method | 경로 |
| --- | --- | --- | --- |
| [WATCH-01](#watch-01) | 오프라인 명령 배치 반영 | `POST` | `/sync/operations` |
| [WATCH-02](#watch-02) | 명령 처리 결과 조회 | `GET` | `/sync/operations/{operationId}` |

<a id="watch-01"></a>
## WATCH-01 · 오프라인 명령 배치 반영

| 항목 | 내용 |
| --- | --- |
| 요청 | `POST /api/v1/sync/operations` |
| 권한 | SELF |
| 성공 | 200 · OperationResult[] |

### 입력과 헤더

본문: deviceId(UUID, 필수), operations(필수 배열, 1~50 기술 초안). 각 원소는 operationId, matchId, expectedVersion(양의 정수), type, payload가 필수다.

인증 헤더는 필수다. 변경 요청에는 Idempotency-Key가 필수다. If-Match는 사용하지 않는다.

### 처리 규칙

1. 본인 등록 기기만 허용한다. 외부 If-Match는 쓰지 않고 개별 작업 expectedVersion을 검사한다.
2. 각 작업을 별도 트랜잭션으로 순서대로 처리한다. 한 경기 작업이 거절되면 같은 경기 후속 작업은 DEPENDENCY_FAILED, 다른 경기는 계속 처리한다.
3. 동일 사용자·operationId·동일 요청은 기존 결과를 재생한다. 다른 요청은 OPERATION_ID_REUSED다. device가 달라져도 작업 ID를 유지한다.
4. 200은 배치 응답 성공이다. 각 결과의 APPLIED/REJECTED와 httpStatus를 확인한다. expectedVersion 불일치는 개별 412다.
5. 작업은 일반 API와 같은 소유·상태 검증을 따른다. 늦게 도착한 센서 파일의 허용과 마감 후 조작 명령의 허용은 별개다. 원본은 WORK-01로 업로드한다.

### 오류와 앱 대응

등록 기기/소유권 오류는 403 또는 404. 개별 OPERATION_ID_REUSED는 409, VERSION_CONFLICT는 412다. [공통 오류](common.md#errors)도 적용한다.

<a id="watch-02"></a>
## WATCH-02 · 명령 처리 결과 조회

| 항목 | 내용 |
| --- | --- |
| 요청 | `GET /api/v1/sync/operations/{operationId}` |
| 권한 | SELF |
| 성공 | 200 · OperationResult |

### 입력과 헤더

path: operationId.

인증 헤더는 필수다. GET 요청에는 본문·멱등 키·If-Match를 보내지 않는다.

### 처리 규칙

1. 본인의 작업 결과만 조회한다. 미처리/존재하지 않는 작업은 404다.
2. 응답 유실 시 새 operationId로 다시 만들지 않고 기존 ID로 조회 또는 재전송한다.

### 오류와 앱 대응

등록 기기/소유권 오류는 403 또는 404. 개별 OPERATION_ID_REUSED는 409, VERSION_CONFLICT는 412다. [공통 오류](common.md#errors)도 적용한다.

## 저장 모델

sync_operations는 요청의 matchId를 requested_match_id에 보존하고, 권한 있는 실제 경기만 match_id로 연결한다. 존재하지 않거나 비공개인 경기 작업의 거절 결과도 저장할 수 있다. result에는 개별 처리 결과를 저장한다. 쿼터 중심이어도 명령 큐의 동시 수정 범위는 부모 경기 version이다.
