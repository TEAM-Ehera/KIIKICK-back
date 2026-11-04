# dbdiagram 편집용 SQL·DBML

작성일: 2026-11-02

| 목적 | MySQL SQL | DBML | 범위 |
| --- | --- | --- | --- |
| 현재 PR 구조 참고 | [current-pr.sql](current-pr.sql) | [current-pr.dbml](current-pr.dbml) | 4개 테이블, 3개 FK |
| 쿼터 중심 개인 기록 설계 | [full-design.sql](full-design.sql) | [full-design.dbml](full-design.dbml) | 24개 테이블, 30개 FK |

## 확정된 기록 방향

- 각 사용자는 자기 경기만 기록한다. `matches.owner_id`가 기록 대상이다.
- `quarters`가 기록의 중심이다. `matches`는 여러 쿼터를 묶는 날짜·장소 등의 공통 정보를 보관한다. `quarters.match_id`로 경기 묶음을 연결한다.
- 골·어시스트 원본 합계는 `quarter_player_stats`에 쿼터마다 저장한다. 경기 합계는 쿼터 합계로 계산하며 별도 집계 테이블/처리 상태는 두지 않는다.
- `quarter_positions`는 쿼터당 대표 포지션 하나를 저장한다. 각 테이블의 quarter_id PK로 쿼터당 최대 한 행을 보장한다. 포지션 미입력은 행 없음으로 표현한다.
- `match_participants`, `match_events`, `event_revisions`, `outbox_events`를 제거했다. 기존 `match_player_stats`는 `quarter_player_stats`로 대체했다.
- 팀 구분·홈/어웨이 점수·completion_status·aggregation_status·source_version·aggregated_version·aggregated_at을 제거했다. 일반 status 및 동시 수정 검사용 version은 유지한다.
- 경기장 재사용을 위해 `venues`를 유지한다.
- `workout_payloads`는 quarter_id로 연결한다. 쿼터당 측정 한 건을 설계 가정으로 UNIQUE 처리했다. 중단 후 재측정 파일을 여러 건 보관해야 한다면 이 제약은 재검토해야 한다.
- `workout_payloads.user_id`는 조회용으로 유지한다. 연결된 쿼터의 경기 소유자와 같은지 서비스 트랜잭션에서 검증한다.
- GPS·심박 원본은 모두 `track_points`, `heart_rate_samples`에 저장한다. sample_seq는 수신 배열 순서를 저장하며 요약 계산을 위해 원본을 삭제하거나 덮어쓰지 않는다.

## 조회용 쿼터 요약

`quarter_summaries`에 최고/평균 속력, 거리, 칼로리, 고강도 거리, 가속/감속/방향전환 횟수, 평균/최고 심박, Edwards TRIMP를 저장한다. 업로드 또는 원본 변경 시 계산하고 일반 조회는 저장된 결과를 읽는다. 지표별 기준·누락 처리·원본 버전은 [계산 계약 초안](quarter-summary.md)을 따른다. TRIMP는 Edwards로 통일했다. 결과와 계산에 사용한 개인 기준 최대심박수만 저장하고 Zone별 체류 시간은 저장하지 않는다. 다른 지표의 임계값은 미확정이다.

## iOS 운동 데이터 대응

근거: [WorkoutPayload DTO](https://github.com/DeveloperAcademy-POSTECH/2026-C6-A03-KIIKICK-iOS/blob/main/docs/dto/workout-payload.md).

- Watch는 측정 1회당 JSON 파일 하나를 iPhone으로 전송한다. 문서 기준 서버 업로드 경로는 아직 없다.
- startDate/endDate/totalCalories/totalDistance와 points/heartRateSamples의 모든 필드는 기존 운동 테이블 컬럼에 대응한다. 칼로리는 kcal, 거리는 m, 속도는 m/s다.
- 날짜 숫자는 Unix epoch가 아닌 2001-01-01 UTC 기준 초다. 기존 포맷 수신 시 978307200초를 더해 Unix 시각으로 변환한 뒤 DATETIME(6)에 UTC로 저장한다. 소수 초를 보존한다.
- speed/course의 -1은 무효 측정 표식이다. 원본 그대로 저장하고 통계 계산에서 따로 취급한다.
- DTO 자체에는 quarterId 및 고유 측정 ID가 없다. 서버 업로드 요청에서 쿼터 연결과 안정적인 재전송 식별자를 보완해야 한다. UNIQUE(quarter_id)만으로 다른 내용의 재업로드 정책까지 해결되지는 않는다.
- 측정 파일을 받지 못한 상태와 정상 측정된 0값을 구분한다. 쿼터가 종료됐더라도 후속 업로드를 받을 수 있도록 설계한다.

일반 API 날짜와 날짜별 통계는 Asia/Seoul(+09:00) 기준이다. DB 내부 UTC와 원본 Apple epoch는 유지한다.

## 사용 범위와 남은 검토

SQL은 ERD용 CREATE TABLE/ALTER TABLE ADD CONSTRAINT 정의이며 운영 DB 변경용 마이그레이션이 아니다. 실제 DB에 실행하지 않았다. DBML은 공식 @dbml/cli로 변환하고 설명과 그룹을 보완했다. 문자열 길이는 nickname(16) 이외 임시값이다. UUID는 BINARY(16), 날짜는 UTC DATETIME(6)을 사용한다.

친구 관계 low/high 정규화, 허용 상태, 권한, 골·어시스트 음수 금지, 단일 활성 쿼터 등은 서비스 검증 또는 후속 제약 설계가 필요하다. 이메일 인증코드와 IP 식별값은 용도별 HMAC-SHA-256, registrationToken은 SHA-256 해시만 저장한다. sync_operations의 필요 범위는 폰·워치의 실제 동기화 요구사항을 추가 검토해야 한다.

HMAC 비밀키는 코드나 DB에 저장하지 않는다. API 계층은 `AUTH_EMAIL_CODE_HMAC_SECRET`, `AUTH_IP_HMAC_SECRET` 환경변수에서 각각 32바이트 이상의 별도 값을 읽어 `AuthHashEncoder`를 구성한다.

[ERD](../erd.md), [기능 명세](../functional-spec.md), [API 명세](../api-spec.md)를 v0.4 개인·쿼터 모델로 함께 갱신했다. 현재 PR 스키마와 앱/서버 구현은 변경하지 않았다.

## 검증

SQL → DBML → MySQL SQL 왕복 변환으로 구문과 관계를 확인했다. 실제 MySQL 실행 및 앱 통합 검증은 수행하지 않았다.
