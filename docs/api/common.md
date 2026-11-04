# 공통 API 규칙

[전체 API 목차](../api-spec.md) · [응답 필드 사전](schemas.md) · [정책 결정](../decisions.md)

v0.4 설계 계약이다. 사용자 확정 사항은 개인 경기, 쿼터별 골·어시스트·포지션·운동 기록, GPS·심박 원본 전체 보존, Edwards TRIMP다. 그 외 제한값과 운영 정책은 별도 검토한다.

## 전송·식별자·시간

- 기준 경로 `/api/v1`, HTTPS, JSON. 외부 ID는 UUID 문자열, 내부 UUID는 BINARY(16).
- 공개 인증 API를 제외하고 Bearer 인증이 필수다. userId/ownerId는 인증 사용자에서 결정하고 요청으로 소유권을 바꾸지 않는다.
- 서비스 기준 시간대는 **Asia/Seoul**이다. 일반 API의 요청·응답 날짜는 `2026-11-02T10:00:00+09:00`처럼 오프셋이 포함된 RFC 3339 문자열이다. 앱 화면과 날짜별 통계도 서울 기준이다. 시간대 없는 문자열은 사용하지 않는다.
- DB 내부는 UTC DATETIME(6) 저장을 유지한다. API 입력을 UTC로 정규화하고 출력 시 서울 시각으로 변환한다. 서비스 시간대와 DB 내부 저장 기준은 구별한다.
- query의 날짜를 URL에 넣을 때 `+`는 `%2B`로 인코딩한다. iOS는 URLComponents/URLQueryItem으로 쿼리를 구성하되, 최종 URL에서 `+09:00`의 `+`가 `%2B`로 인코딩됐는지 확인한다.
- **운동 업로드 원본만 예외**: iOS WorkoutPayload의 startDate/endDate 및 샘플 timestamp는 2001-01-01 UTC 기준 초 단위 Double이다. 서버가 978307200초를 더해 Unix 시각으로 변환한 뒤 UTC DATETIME(6)에 저장한다. 소수 초를 보존하며 Double 유한수와 DB 날짜 범위를 검사한다.
- 본문 없는 명령은 본문을 생략한다. PATCH는 누락 유지, nullable 필드의 명시적 null만 삭제 의미다. 알 수 없는 필드와 서버 관리 필드는 422다.

## 권한

| 표시 | 대상 | 범위 |
| --- | --- | --- |
| SELF | 인증된 본인 | 프로필·동의·기기·친구·알림 |
| OWNER | matches.owner_id가 본인 | 해당 경기와 소속 쿼터의 기록·위치·심박·운동 요약 |
| FRIEND_VISIBLE | 현재 친구이며 양쪽 비교 동의 충족 | 허용된 분석 요약만. 원본 경로·심박 및 타인의 경기 상세 접근은 허용하지 않음 |

모든 쿼터 API는 quarters → matches.owner_id를 확인한다. 경로에 matchId와 quarterId가 함께 있으면 소속 일치도 검증한다. 타인 개인 경기·쿼터는 404다. 차단·친구 해제·동의 철회는 매번 재평가한다.

### 경기·쿼터 인증 연결 제안 (#12)

신규 경기·쿼터 API의 서비스 경계는 내부 사용자 UUID를 명시적 인자(`UUID actorUserId`)로 받는 방식으로 제안한다. 인증 계층이 Bearer 토큰을 검증하고 내부 사용자 UUID를 제공하면 컨트롤러가 이를 서비스에 넘긴다. 서비스는 그 UUID로 경기 생성의 소유자를 정하고, 조회·변경에는 위 OWNER 규칙을 적용한다. 요청의 경로·본문·헤더에서 클라이언트가 보낸 사용자 ID를 인증 사용자 대신 사용하지 않는다.

Spring Security를 사용하는 경우 컨트롤러에서 `@AuthenticationPrincipal`로 인증 주체를 받고 그 주체의 내부 사용자 UUID를 전달하는 방안을 우선 검토한다. principal 타입과 UUID 접근 방식은 auth 담당자 확인 후 확정한다. #14에서는 서비스 인자로 UUID를 주입해 소유권을 검증할 수 있으며, 컨트롤러 테스트는 테스트 인증 주체를 사용한다. 신규 API의 실제 연동 검증에는 인증 경계의 401과 서비스의 소유권·쿼터 소속 404를 모두 포함한다.

역할 분담과 적용 시점은 [iOS·서버 연동 협의표](ios-integration.md#인증과-공통-응답-분담-제안-12)에 기록한다.

## 응답·목록

성공은 `{data, meta:{requestId,serverTime}}`, 오류는 `{error,meta}`다. 204에는 본문이 없다. 목록의 data는 배열이며 meta.pagination에 nextCursor와 hasNext가 있다. 빈 목록은 []다.

기본 limit=20, 허용 1~100은 기술 초안이다. cursor는 불투명하며 사용자·필터·정렬에 귀속된다. 동일 정렬값은 id 또는 sampleSeq로 구분한다. 원본 변경으로 페이지의 읽기 버전이 바뀌면 409 CURSOR_SNAPSHOT_CHANGED를 반환하는 안이다. 원본 샘플은 sampleSeq 오름차순이다. 임의의 시각 정렬로 원본 순서를 바꾸지 않는다.

## 변경 헤더와 동시성

| 요청 | Authorization | Idempotency-Key | If-Match |
| --- | --- | --- | --- |
| 로그인/갱신 | 인증 문서 기준 | 없음 | 없음 |
| GET | 필수 | 없음 | 없음 |
| 프로필·친구·알림·기기·경기장 변경 / 경기 생성 | 필수 | 필수 | 없음 |
| 기존 경기·쿼터·개인 기록·포지션·운동 업로드·요약 설정/재계산 | 필수 | 필수 | 부모 경기 최신 ETag 필수 |
| 작업 배치 | 필수 | 필수 | 외부 헤더 대신 각 작업 expectedVersion |

경기 version은 개인 경기와 하위 기록의 동시 수정 방지 버전이다. 쿼터가 기록의 중심이어도 기존 SQL의 matches.version을 재사용한다. 각 하위 GET은 ETag와 응답 meta.matchVersion을 제공한다. Quarter 객체에도 matchVersion이 있다. 목록과 null 단건도 meta.matchVersion을 제공한다. 경기 변경은 행 잠금과 If-Match 비교 후 version을 1 증가시키고 새 ETag를 반환한다. 누락은 428, 불일치는 412다. 백그라운드 요약의 완료·실패 상태 게시도 경기 version을 증가시킨다. 이는 경기 종료를 지연시키지 않는다.

Idempotency-Key는 UUID이며 범위는 (userId, method, canonical path, key)다. 본문과 If-Match를 요청 해시에 포함한다. 동일 키·동일 요청은 최초 상태·본문·헤더를 재생하고 현재 상태는 GET으로 확인한다. 동일 키·다른 요청은 409 IDEMPOTENCY_KEY_REUSED, 처리 중은 409 REQUEST_IN_PROGRESS다. 재생 전 현재 권한을 다시 확인하고, 성공 재생은 version 비교보다 먼저 수행한다. 도메인 변경과 멱등 결과를 같은 트랜잭션에 기록한다.

원본 업로드 성공 재생은 샘플이나 요약 계산 작업을 중복 생성하지 않는다. 키 보존 기간 이후에도 UNIQUE(quarter_id)와 기존 원본의 정규화 해시 비교로 같은 원본 재업로드는 200, 다른 원본은 409 WORKOUT_ALREADY_EXISTS로 처리한다. 원본 교체/삭제 API는 이번 계약에 없다. 키·작업 보존 기간과 재전송 최대 지연은 P10/P11에서 정한다.

<a id="errors"></a>
## 오류와 앱 대응

| HTTP | code 예시 | 앱 대응 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_CURSOR | 형식·헤더·커서 수정 |
| 401 | UNAUTHENTICATED / TOKEN_EXPIRED | 토큰 갱신 또는 로그인 |
| 403 | FORBIDDEN / CONSENT_REQUIRED | 권한·동의 안내 |
| 404 | RESOURCE_NOT_FOUND | 대상 없음 또는 타인 개인 리소스 |
| 409 | INVALID_STATE / ACTIVE_QUARTER_EXISTS / QUARTER_NOT_ENDED / WORKOUT_ALREADY_EXISTS | 최신 상태 확인, 자동 덮어쓰기 금지 |
| 409 | IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS | 같은 의도는 같은 키 유지, Retry-After 준수 |
| 409 | CURSOR_SNAPSHOT_CHANGED | 첫 페이지 재조회 |
| 412 | VERSION_CONFLICT | 최신 경기 ETag 확인 후 새 의도 요청 |
| 422 | VALIDATION_FAILED / INSUFFICIENT_DATA | details.fields 또는 부족 데이터 확인 |
| 428 | PRECONDITION_REQUIRED | 경기 최신 ETag 조회 |
| 429 | RATE_LIMITED | Retry-After 준수 |
| 500/503 | INTERNAL_ERROR / TEMPORARILY_UNAVAILABLE | requestId로 추적, 같은 키로 복구 |

모든 API에 위 오류가 적용된다. 도메인별 오류는 각 상세 문서에서 추가한다. 미계산 요약 지표는 HTTP 오류 대신 200 응답 내 null과 metricStatus로 표현한다. 202는 요약 재계산 또는 탈퇴처럼 후속 처리가 남은 변경 요청에 사용한다. 경기 종료는 200이다.

```json
{
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "최신 경기 정보를 확인해주세요.",
    "retryable": false,
    "persistence": "NOT_SAVED",
    "details": {"currentVersion": 9}
  },
  "meta": {"requestId": "req-example", "serverTime": "2026-11-02T10:20:00+09:00"}
}
```

persistence는 NOT_SAVED/SAVED/UNKNOWN이며 조회 오류에는 null이다. 네트워크 응답 유실 시 저장 여부는 알 수 없으므로 새 키로 무조건 제출하지 않는다. 위치·심박·토큰 원문은 로그에 넣지 않는다. 새 지표의 임계값은 미확정이며 계산 코드가 임의의 제품 기준을 만들면 안 된다.
