# 조회 성능 개선

## 적용 범위

| 이슈 | 변경 | 남는 비용 |
|---|---|---|
| [#102](https://github.com/TEAM-Ehera/KIIKICK-back/issues/102) | owner/status와 created_at 또는 ended_at 커서 순서에 맞는 경기 인덱스 | DB B-tree 탐색 및 결과 출력 |
| [#103](https://github.com/TEAM-Ehera/KIIKICK-back/issues/103) | 닉네임 부분 검색·공개 범위·차단·커서를 먼저 적용하고 한 페이지에만 친구 관계 JOIN | 선행 와일드카드 부분 문자열 검사 |
| [#104](https://github.com/TEAM-Ehera/KIIKICK-back/issues/104) | 친구 low/high 방향별 인덱스 조회를 UNION ALL로 병합; 각 분기 최대 limit, 최종 limit | 필터에서 탈락하는 관계 후보 검사 |
| [#105](https://github.com/TEAM-Ehera/KIIKICK-back/issues/105) | 통계 행 callback 누적; 포지션은 DB GROUP BY; 추세는 요청 지표·필요 분모만 읽고 버킷 누산 | 해당 기간 쿼터 읽기 O(N); JDBC 드라이버의 결과 버퍼링은 별도 |
| [#106](https://github.com/TEAM-Ehera/KIIKICK-back/issues/106) | 지표·메타데이터·버전 정책을 같은 입력 순회에서 누적; 비교에는 원본 행 목록 대신 정책 상태 사용 | 고정 13개 지표의 행당 계산; 경기 ID·버전 집합 유지 |
| [#107](https://github.com/TEAM-Ehera/KIIKICK-back/issues/107) | 타입별 표시 조건으로 정확한 미확인 개수를 합산하고 unread 시간순·타입 인덱스 추가 | 현재 상태 확인과 정확한 COUNT는 후보 수에 비례 |
| [#108](https://github.com/TEAM-Ehera/KIIKICK-back/issues/108) | 전체 사용자 적재를 기본 20명·최대 100명 커서 페이지로 제한 | 결과 K개 DTO 변환 O(K) |
| [#109](https://github.com/TEAM-Ehera/KIIKICK-back/issues/109) | 검색어 없는 운동장 목록의 LIKE 생략; 오버로드 escaping 통일 | 검색어가 있으면 소유자의 운동장 부분 검색 |

친구·알림·경기 커서 검증 중 MySQL DATETIME → Timestamp → JVM LocalDateTime 변환으로 UTC 값이 바뀌는 문제도 확인했다. 공통 nullable DATETIME 변환 및 경기·친구·알림의 커서용 날짜는 `getObject(column, LocalDateTime.class)`로 읽는다. 같은 시각의 친구 관계가 양방향에 존재할 때 중복·누락 없이 다음 페이지로 이동하는 테스트를 H2와 JVM Asia/Seoul의 MySQL에서 공유한다.

## 사용자 목록 API 변경

`GET /api/v1/users?limit=20&cursor=...`는 기존 JSON 배열 형태를 유지한다. 단, 한 번에 전체 사용자를 반환하던 동작은 폐기한다. 다음 페이지가 있으면 `Link: </api/v1/users?limit=20&cursor=...>; rel="next"`를 제공하며 마지막 페이지에는 Link가 없다. 기본 20, 허용 범위 1~100이다. 잘못된 cursor 또는 limit은 400이다. 정렬은 기존과 같은 `created_at DESC, id ASC`다. 클라이언트는 전체 목록이 필요하면 Link를 따라가야 한다. `/users/search`의 공개 프로필·부분 검색 계약은 별개다.

## 검증

Java 21에서 `./gradlew test`로 API/Repository/집계 테스트를 실행한다. `QueryAccessContract`는 H2와 전용 로컬 MySQL에 공통 적용된다. `AnalyticsSummaryTest`는 분모 가중, 누락 우선순위, 버전·심박 기준, 빈 결과, 비유한 합계를 검증한다. `UserListControllerTest`는 JSON 배열·Link·400 응답을 검증한다.

MySQL은 이번 작업 전용 `kiikick-performance-mysql` 컨테이너의 `127.0.0.1:3306/kiikick_repository_test`에 기존 DDL → V001 → V002 → V003(refresh history) → V004 순서로 적용했다. 기존 개발 컨테이너·운영 DB는 수정하지 않았다. MySQL 테스트의 `LocalMySqlInitializer`가 호스트·포트·DB를 검증한다. SMTP 및 JWT/HMAC 값은 로컬 테스트용 값을 제공하며 실제 메일 전송을 검증하지 않는다.

`QueryAccessMySqlIntegrationTest.createdTimeIndexCanServeListWithoutSortingOwnerRows`는 같은 사용자 경기 1,000개에서 기존 scheduled_at 인덱스와 신규 created_at 인덱스를 각각 FORCE INDEX로 지정하여 EXPLAIN ANALYZE를 비교한다. 힌트는 테스트에만 있다. 이는 해당 인덱스가 정렬 없는 LIMIT 조회를 지원하는지 확인하는 실험이며, 운영 옵티마이저가 항상 같은 인덱스를 선택하거나 특정 응답 시간을 보장한다는 의미가 아니다. 운영 데이터 분포에서 실제 쿼리의 EXPLAIN ANALYZE, 읽은 행 수, filesort 및 쓰기 비용을 확인한다.

### 이번 실행 결과

- Java 21.0.12.1, Spring Boot 4.1.2: 일반 테스트 283개 통과.
- 전용 MySQL 8.4.11: 통합 테스트 107개 통과.
- 두 실행 모두 실패·오류·건너뜀 0개.
- 인덱스 지정 실험: 기존 인덱스는 1,000행 읽기 및 Sort, 신규 인덱스는 정렬 없이 21행 읽기를 확인했다. 단일 로컬 실행의 시간 차이를 운영 응답 속도 개선율로 일반화하지 않는다.

## 배포와 되돌리기

[V004](../db/mysql/migrations/V004__query_access_indexes.sql)는 기존 인덱스를 삭제하지 않는 추가 DDL이다. 이 저장소의 마이그레이션은 자동 실행되지 않는다. 운영에서는 MySQL 버전·스토리지·테이블 크기·DDL 락과 공간을 확인한 뒤 별도 적용한다. 인덱스 12개만큼 저장 공간과 INSERT/UPDATE 유지 비용이 증가한다. 운영 데이터에서 효과가 없는 인덱스는 후속 검토한다. 새 쿼리는 V004 없이도 정확하게 동작하나 성능 이점을 보장하지 않는다.

되돌릴 때는 V004에서 추가한 이름만 `DROP INDEX index_name ON table_name`으로 제거하고 해당 쿼리 변경을 되돌린다. 사용자 목록의 크기 제한을 되돌리면 다시 무제한 적재가 발생하므로 클라이언트 계약을 함께 확인한다.

## 복잡도와 후속 설계 경계

모든 요청을 O(1)로 바꾸지는 않는다. HashMap 개별 키 조회는 평균 O(1)이지만 SQL B-tree 조회와 K개 응답의 직렬화는 별도다. 지표가 13개로 고정되어 기존 통계 순회도 O(N)이었으며 이번 변경은 행 보관과 반복 읽기 비용을 줄인다. 영속 일별 요약·상수 시간 알림 카운터는 이번 구현에 포함하지 않는다. 도입하려면 통계 재계산·득점 수정·포지션 변경·동의/차단/탈퇴·친구 요청 처리의 갱신 및 무효화 계약이 필요하다. 임의 TTL로 최신성을 낮추지 않는다.

- [Java 21 HashMap](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/HashMap.html)
- [Spring JDBC 행별 처리](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/JdbcTemplate.html)
- [MySQL 복합 인덱스](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/multiple-column-indexes.html)
- [MySQL LIKE 범위 최적화](https://dev.mysql.com/doc/refman/8.4/en/range-optimization.html)
