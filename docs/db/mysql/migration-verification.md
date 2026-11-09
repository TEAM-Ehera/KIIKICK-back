# 경기·쿼터 DB 이행 검증 (#13)

이 문서는 개발용 MySQL 검증 기준이다. 이행 SQL의 최종 대상 스키마는 Repository PR #10 병합본에서 확인한다.

## 호환성 확인

- 기존 MySQL 스키마는 `users`의 날짜 필드에 camelCase를 쓴다. #10의 사용자 Repository는 snake_case와 프로필·온보딩 관련 컬럼을 읽는다. 이행 뒤에도 UUID, 닉네임, 생성·수정·삭제 시각이 보존되어야 하며 기존 사용자 생성·수정·조회도 동작해야 한다.
- #10의 기존 사용자별 업로드는 `workoutPayloads`, `trackPoints`, `heartRateSamples`에 기록한다. 신규 쿼터 업로드는 별도의 snake_case 테이블에 기록하며 `quarter_id`가 필수다. 기존 경로가 사용되는 동안 세 기존 테이블과 쓰기 경로를 유지하는 방안을 우선 검증한다.
- 쿼터 ID가 없는 기존 데이터는 기존 테이블에 보존하고 신규 쿼터 조회에서는 연결된 운동으로 반환하지 않는다. 경기 시각 등으로 연결을 추정하거나 임의의 경기·쿼터를 생성하지 않는다.

## 개발 DB 검증

1. 빈 DB에 이행 버전을 순서대로 적용한다. 사용자·경기·쿼터 Repository를 실행하고 쿼터에 연결된 운동과 샘플을 저장·조회한다.
2. 별도 DB에 [기존 스키마](create-tables.sql)를 적용하고 [검증 데이터](legacy-migration-fixture.sql)를 삽입한다. 기존 스키마와 데이터를 덤프한 뒤 이행 SQL을 적용한다.
3. 이행 전후의 사용자 UUID·날짜, 운동 ID·소유자·시각, GPS·심박 ID와 `sampleSeq`별 값을 비교한다. 기존 운동이 신규 쿼터 운동으로 나타나지 않는지 확인하고 기존 사용자별 업로드도 실행한다.
4. 이행 실패를 재현한다. MySQL DDL은 암시적으로 커밋될 수 있으므로 트랜잭션 롤백에 의존하지 않는다. 개발용 복구 DB에 이행 전 덤프를 복원하고 3단계의 데이터 비교를 반복한 뒤 이행을 재실행한다.

## 적용 순서

[로컬 MySQL 설정](local-environment.md)의 MySQL 8.4.11을 사용한다. 저장소 루트에서 다음 함수와 경로를 설정한다.

```sh
mysql_local() {
  docker exec -i kiikick-migration-mysql sh -c '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$@"
  ' sh "$@"
}

migration=docs/db/mysql/migrations/V001__match_quarter_schema.sql
```

빈 개발 DB에는 `create-tables.sql`을 먼저 적용하고 V001을 적용한다. 이미 기존 스키마가 있는 DB는 백업 후 V001부터 적용한다. 이행 중에는 애플리케이션과 배치의 쓰기를 중지한다. V001은 대상 DB에 한 번 적용하고, 첫 SQL 오류에서 멈추도록 `mysql --force` 없이 실행한다.

```sh
mysql_local kiikick_repository_test < docs/db/mysql/create-tables.sql &&
mysql_local kiikick_repository_test < "$migration"
```

Repository 검증에는 비어 있는 전용 `kiikick_repository_test`를 생성하고 로컬 계정에 해당 DB 권한을 부여한다. 위 SQL 적용 후 Java 21 환경에서 실행한다.

```sh
MYSQL_TEST_DB_HOST=127.0.0.1 MYSQL_TEST_DB_PORT=13306 \
MYSQL_TEST_DB_NAME=kiikick_repository_test MYSQL_TEST_DB_USERNAME=kiikick_local \
MYSQL_TEST_DB_PASSWORD=kiikick-local-dev ./gradlew test mysqlIntegrationTest
```

## 기존 데이터와 실패 복구

별도의 개발 DB에 기존 스키마와 fixture를 적용한다. 적용 전 아래 명령으로 스키마와 데이터를 함께 백업한다. 백업 파일은 저장소 밖에 둔다.

```sh
docker exec kiikick-migration-mysql sh -c '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot \
    --single-transaction --no-tablespaces --set-gtid-purged=OFF kiikick_13_legacy
' > /tmp/kiikick-13-legacy-backup.sql
```

이행 전후 다음 쿼리를 `mysql_local -BN kiikick_13_legacy`로 실행해 정렬된 결과를 비교한다. 사용자 날짜 컬럼의 이름만 이행 전에는 `createdAt, updatedAt, deletedAt`을 사용한다.

```sql
SELECT HEX(id), nickname, created_at, updated_at, deleted_at FROM users ORDER BY id;
SELECT HEX(id), HEX(userId), startDate, endDate, totalCalories, totalDistance
FROM workoutPayloads ORDER BY id;
SELECT id, HEX(workoutPayloadId), sampleSeq, latitude, longitude, altitude,
       horizontalAccuracy, speed, course, timestamp
FROM trackPoints ORDER BY workoutPayloadId, sampleSeq;
SELECT id, HEX(workoutPayloadId), sampleSeq, timestamp, bpm
FROM heartRateSamples ORDER BY workoutPayloadId, sampleSeq;
```

복구 검증은 백업을 별도 개발 DB에 복원하고 `CREATE TABLE auth_identities (id INT)`를 실행해 충돌을 만든다. V001 실행이 실패한 뒤 `users.created_at`이 이미 반영됐는지 확인한다. 빈 복구 DB에 백업을 다시 복원하고 원본과 비교한 뒤 V001을 재적용한다. 실패한 DB에서 V001을 그대로 다시 실행하면 이미 적용된 DDL과 충돌한다.

## 검증 결과

2026-11-04 기준 MySQL 8.4.11과 `develop`의 PR #10 병합 커밋 `9c5d015`를 사용했다.

| 검증 | 결과 |
| --- | --- |
| 빈 DB에 기존 스키마와 V001 적용 | 성공. 테이블 23개 생성 |
| Java 21 회귀 및 MySQL 통합 테스트 | H2 42개, MySQL 6개 통과. 신규 쿼터 운동과 기존 사용자별 업로드를 각각 검증 |
| 기존 fixture 전후 비교 | 사용자 2명, 운동 1건, GPS 2건, 심박 1건의 비교 결과 일치. 기존 운동과 연결된 신규 경기·쿼터 없음 |
| DDL 부분 적용 후 복구 | 첫 ALTER 적용 뒤 테이블 충돌로 실패. 백업 복원 및 V001 재적용 후 비교 결과 일치 |

계산 작업 식별 방식에 대한 후속 PR이 병합되면 확정 스키마에 V001을 맞추고 위 검증을 다시 실행한다. 현재 결과는 `calculation_job_id`가 있는 스키마에 해당한다.

## 경기장 좌표 추가 (V004)

기존 V001~V003(Refresh Token 이력) 적용 DB에는 애플리케이션 변경 배포 전에 [V004__venue_corners.sql](migrations/V004__venue_corners.sql)을 한 번 적용한다. 이 저장소의 이행 SQL은 수동 적용 방식이다. 기존 경기장의 16개 위도·경도 컬럼은 모두 NULL이며 좌표를 추정하거나 기존 주소를 변경하지 않는다. 신규 환경도 V001~V004를 순서대로 적용한다.

검증: 8개 핀의 서로 다른 소수 좌표를 저장한 뒤 상세·목록에서 값이 그대로 조회되는지, PATCH로 보정 좌표 전체 교체와 원본 개별 삭제가 되는지 확인한다. H2 통합 테스트는 갱신된 `src/test/resources/schema.sql`을 사용한다. 실제 MySQL 적용 전에는 대상 DB 백업과 별도 검증 DB에서 V004 적용 확인이 필요하다.

2026-11-09 검증: 격리 MySQL 8.4.11에 기존 스키마와 V001~V004를 순서대로 적용했다. 실제 JDBC Repository에서 8개 핀의 Double 정밀도, 목록·상세 매핑, 보정 핀 교체·원본 NULL 삭제와 기존 좌표 없는 경기장 테스트 3개가 통과했다. develop 기반 전체 H2 테스트 275개와 bootJar 빌드도 통과했다.

## 운동 이벤트·경기장 사본 추가 (V005)

[V005__workout_events_field.sql](migrations/V005__workout_events_field.sql)은 V004 이후 `workout_payloads`와 기존 `workoutPayloads`에 각각 nullable JSON `events_json`, `field_snapshot`을 추가한다. 각 ALTER는 별도 DDL이므로 부분 적용 실패 시 컬럼 상태를 확인하고 미적용 문장만 재실행한다. 두 테이블의 스키마 변경을 모두 완료한 뒤 애플리케이션을 배포한다. 기존 행·합계는 변경하지 않으며 NULL은 응답에서 빈 events 배열과 null field로 복원한다.

2026-11-09 검증: 전체 테스트 277개와 bootJar 빌드 통과. 격리 MySQL 8.4.11에서 V001~V005 적용과 JSON 사본 저장·조회, 기존 업로드, 경기장 코너 회귀 테스트 3개 통과.
