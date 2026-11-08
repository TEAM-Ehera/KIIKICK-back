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


## V004 조회 인덱스

기존 V001~V003이 적용된 개발 DB에 `migrations/V004__query_access_indexes.sql`을 한 번 적용한다. 적용·검증 결과와 인덱스별 용도, 운영 이행 및 되돌리기는 [조회 성능 개선](../../performance/query-performance.md)을 참조한다. 새 SQL의 H2/MySQL 공통 회귀 테스트는 `QueryAccessContract`다.
