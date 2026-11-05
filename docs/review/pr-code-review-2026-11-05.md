# 전체 PR·통합 코드 리뷰 — 2026-11-05

## 범위와 재현 기준

검토 시점의 저장소 PR 36개(종료·병합·열림 포함)를 목록과 현재 코드에 대조했다. 종료된 대체 구현은 역사·대체 관계를 확인하고, 실행 검증은 현재 JDBC/Spring Boot 통합 코드에 집중했다. 이전 PR 브랜치들을 수정·강제 푸시한 것이 아니라 별도 통합 브랜치에 수정 사항을 모았다. 운영 승인이나 모든 가능한 입력에 대한 무결함 보증을 뜻하지 않는다.

- 기반: `feat/#43-analytics`의 `838d31d`; 최신 develop, #34, #57/#67/#75, #77/#78을 순차 병합한 `f96b037` 위에서 수정.
- 기존 PR #35는 계산 시도 식별자 방식이 충돌하므로 의도적으로 병합 제외.
- Java 21, 저장소 Spring Boot 4.1.2; 기본 H2와 별도 Docker MySQL 8.4.11 사용.
- MySQL은 이번 작업 전용 `kiikick-review-mysql`·tmpfs·loopback 13379. 기존 컨테이너와 운영 DB는 변경하지 않음.

## 수정한 발견 사항

| 우선순위 | 문제와 발생 조건 | 수정 | 검증 |
| --- | --- | --- | --- |
| P1 | #78 Principal이 record의 문자열로 노출되어 경기 API의 UUID 파싱이 실패 | AuthenticatedUser가 Principal을 구현하고 getName()으로 내부 UUID 제공 | 모든 기능 HTTP 테스트를 실제 서명 JWT+저장된 세션으로 전환 |
| P1 | 유효한 토큰으로 구형 /user/{타인ID} CRUD·업로드 접근 가능, 전체 사용자 조회 및 가입 우회 생성 가능 | 자기 ID 경로만 허용, 기존 전체 목록/생성 차단 | 타인 조회·수정·삭제·업로드 403, 본인 조회 200, 타인 데이터 불변 |
| P1 | 삭제된 사용자의 활성 세션이 인증에 계속 사용됨 | Access/Refresh 세션 조회에서 삭제되지 않은 users와 JOIN | 발급 토큰으로 조회 성공→softDelete→같은 토큰 401 |
| P2 | exp 없는 서명 토큰 및 aud 누락을 명시적으로 처리하지 않음 | exp 필수·aud null-safe 검증 | 각각 401, DB 세션 조회하지 않음 |
| P2 | 인증 DB 장애가 정상적인 JSON 오류로 처리되지 않음 | 인증 필터 실패 경로에 retryable 503 매핑 | 저장소 예외 주입 후 TEMPORARILY_UNAVAILABLE |
| P2 | 신규 경기/결과/분석/경기장 성공 응답과 인증 오류의 X-Request-Id 누락·형식 불일치 | Envelope/Metadata 계약으로 중복 직렬화 없이 GET 헤더 생성, 쓰기·204 헤더 저장/재생, 인증 오류 공통 타입 사용 | 본문 ID=헤더, 204 재전송 ID 동일, 401 WWW-Authenticate 및 NOT_SAVED |
| P2 | 예상하지 못한 서비스/DB 오류에서 공통 오류 계약과 저장 여부 표현이 깨짐 | scoped advice의 500/503 응답, 변경 실패 persistence=UNKNOWN, 식별자·예외 타입만 로그 | DB 예외 주입, retryable·UNKNOWN 확인, 원본 예외 메시지 비노출 |
| P2 | 워커의 PROCESSING 조회가 5초마다 전체 요약 스캔+정렬 | V003 상태/시각/쿼터 인덱스, 최신 SQL/DBML/H2 동기화 | EXPLAIN의 ALL/filesort→idx_summary_pending ref, filesort 제거 |
| P2 | 이메일/IP 제한이 retryAt 시각과 같은 요청도 다시 거절 | 윈도 하한 제외로 만료 시각 계약 일치 | 24시간/1분 경계와 경계 직전, 기존 동시 한도 테스트 |
| P2 | #43 확정 정책과 #44 보류 결정을 문서만 읽으면 미정/활성 초안으로 오인 | 정책 목록, API 목록, watch·iOS 원본/공유본 갱신 | 문서 링크·잔여 문구 대조 |

## PR별 검토 기록

| PR | 검토 시점 상태 | 기준 및 결과 |
| --- | --- | --- |
| [#1](https://github.com/TEAM-Ehera/KIIKICK-back/pull/1) | CLOSED | 종료된 JPA 초안. 현재 JDBC User/세션 경로를 기준으로 검증. |
| [#2](https://github.com/TEAM-Ehera/KIIKICK-back/pull/2) | CLOSED | 종료된 Pitch 초안. 현재 Venue 소유권·사본 계약으로 대체. |
| [#3](https://github.com/TEAM-Ehera/KIIKICK-back/pull/3) | CLOSED | 종료된 Pitch CRUD. 현재 SET-01~05와 회귀 검증. |
| [#4](https://github.com/TEAM-Ehera/KIIKICK-back/pull/4) | CLOSED | 종료된 MatchPeriod 초안. 현재 Quarter 상태 전이와 FK로 대체. |
| [#5](https://github.com/TEAM-Ehera/KIIKICK-back/pull/5) | CLOSED | 종료된 JPA 업로드. 현재 JDBC 기존 업로드와 WORK-01 분리 검증. |
| [#6](https://github.com/TEAM-Ehera/KIIKICK-back/pull/6) | MERGED | 구형 사용자 CRUD의 타인 접근·전체 목록·검증 없는 생성 경로 차단. 본인 경로는 유지. |
| [#7](https://github.com/TEAM-Ehera/KIIKICK-back/pull/7) | MERGED | 기존 업로드 저장 경로 보존, URL userId 소유권 제한. 새 쿼터 원본과 혼합하지 않음. |
| [#9](https://github.com/TEAM-Ehera/KIIKICK-back/pull/9) | MERGED | API 목차·정책·iOS 공유본에서 #43/#44 결정 반영. |
| [#10](https://github.com/TEAM-Ehera/KIIKICK-back/pull/10) | MERGED | 현재 27테이블 SQL·UUID/시간/FK와 신규 기능 연결을 MySQL 검증. |
| [#11](https://github.com/TEAM-Ehera/KIIKICK-back/pull/11) | MERGED | 템플릿 범위 확인. 이번 PR에도 계약·DB·검증·병합 제한 명시. |
| [#30](https://github.com/TEAM-Ehera/KIIKICK-back/pull/30) | CLOSED · draft | 종료된 초안. #31 계약 기준으로 인증 연결 검증. |
| [#31](https://github.com/TEAM-Ehera/KIIKICK-back/pull/31) | MERGED | Servlet Principal UUID 계약을 실제 JWT Principal에 연결. |
| [#33](https://github.com/TEAM-Ehera/KIIKICK-back/pull/33) | MERGED | develop의 Jenkins·관측 파일을 통합에 보존. Jenkins/AWS 실제 실행은 미검증. |
| [#34](https://github.com/TEAM-Ehera/KIIKICK-back/pull/34) | OPEN · draft | V001 legacy 보존과 신규 FK 적용 검증. V002/V003 순서 명시. 운영 미적용. |
| [#35](https://github.com/TEAM-Ehera/KIIKICK-back/pull/35) | OPEN | 별도 설계 충돌: updated_at 식별자 버전을 이번 통합에 병합하지 않음. 아래 병합 제한 참조. |
| [#36](https://github.com/TEAM-Ehera/KIIKICK-back/pull/36) | OPEN · draft | 공통 Envelope와 X-Request-Id 일치, 예외 응답, 실제 인증 경계 검증. |
| [#37](https://github.com/TEAM-Ehera/KIIKICK-back/pull/37) | OPEN · draft | 소유자→경기 잠금, 동일 키 재생, ETag 경합을 실제 MySQL HTTP 테스트로 검증. |
| [#38](https://github.com/TEAM-Ehera/KIIKICK-back/pull/38) | OPEN · draft | 생성·PATCH·커서·타인 404 검증. 실토큰 테스트 전환. |
| [#45](https://github.com/TEAM-Ehera/KIIKICK-back/pull/45) | OPEN | 쿼터 순번·마지막 미사용 삭제·버전 커서 회귀. P03 상한은 제품 결정 남음. |
| [#46](https://github.com/TEAM-Ehera/KIIKICK-back/pull/46) | OPEN | DRAFT/READY/IN_PROGRESS/COMPLETED/CANCELLED 전이·완료 조건 회귀. |
| [#47](https://github.com/TEAM-Ehera/KIIKICK-back/pull/47) | OPEN | 단일 활성 쿼터, 측정 마감과 완료 구분, 재생 회귀. |
| [#48](https://github.com/TEAM-Ehera/KIIKICK-back/pull/48) | OPEN | 미입력 null과 명시적 0, 절대 합계 교체·소유권 회귀. |
| [#49](https://github.com/TEAM-Ehera/KIIKICK-back/pull/49) | OPEN · draft | 잘못된 코드 422, 미입력 완료 허용 확인. 실제 허용 코드 목록은 남음. |
| [#50](https://github.com/TEAM-Ehera/KIIKICK-back/pull/50) | OPEN · draft | 크기 제한, 필드·시간 순서·중복·해시·쿼터 소유권 회귀. P10 잔여 정책 별도. |
| [#51](https://github.com/TEAM-Ehera/KIIKICK-back/pull/51) | OPEN | 원본 sampleSeq 커서, 쿼터/소유자 범위, 요약 조회·ETag 회귀. |
| [#52](https://github.com/TEAM-Ehera/KIIKICK-back/pull/52) | OPEN | 작업 ID 기반 stale write 차단·중복 게시 회귀. 워커 폴링 인덱스 추가. |
| [#53](https://github.com/TEAM-Ehera/KIIKICK-back/pull/53) | OPEN | 유효 BPM/간격/중복/선형 보간/Edwards 경계 테스트 확인. O(H). |
| [#54](https://github.com/TEAM-Ehera/KIIKICK-back/pull/54) | OPEN | GPS 필터·구간 적분·이벤트 지속/재진입 확인. 심박 결합 O(G+H). |
| [#55](https://github.com/TEAM-Ehera/KIIKICK-back/pull/55) | OPEN | HRmax 보존·재계산 중 거절·결과 무효화·작업 세대 회귀. |
| [#56](https://github.com/TEAM-Ehera/KIIKICK-back/pull/56) | OPEN | 결과 null/합계·상태별 개수·페이지 일괄 자식 조회 검증. MySQL 전체 흐름 추가. |
| [#57](https://github.com/TEAM-Ehera/KIIKICK-back/pull/57) | OPEN | 삭제 시 사본 보존·참조 해제·버전 증가·경기 수정 경합 회귀. 응답 헤더 보완. |
| [#67](https://github.com/TEAM-Ehera/KIIKICK-back/pull/67) | OPEN | 검색 공개 범위·양방향 차단·요청 승인·차단 후 멱등 재생 차단 회귀. |
| [#75](https://github.com/TEAM-Ehera/KIIKICK-back/pull/75) | OPEN | 보이는 대상만 목록/미읽음/읽음, 첫 읽음, 토큰 암호화/폐기 회귀. P09 발송 미구현. |
| [#76](https://github.com/TEAM-Ehera/KIIKICK-back/pull/76) | OPEN | 서울 [from,to), 빈 버킷, 가중 평균, 누락·비교 버전·양쪽 동의 검증. 문서 상태 보완. |
| [#77](https://github.com/TEAM-Ehera/KIIKICK-back/pull/77) | OPEN · draft | HMAC/코드 교체·실패 누적·일회용 토큰·동시 발송 한도 확인. retryAt 경계 오류 수정. |
| [#78](https://github.com/TEAM-Ehera/KIIKICK-back/pull/78) | OPEN · draft | Principal 연결, legacy 접근, 삭제 계정 세션, exp/aud 검증, 인증 장애 503와 공통 오류 보완. |

## 설계·동시성·복잡도 판단

- 변경은 MatchMutation이 소유자/경기 잠금, 버전 전제조건, 도메인 변경과 멱등 응답 저장을 같은 트랜잭션으로 묶는다. 같은 사용자의 서로 다른 경기 쓰기도 직렬화되는 비용이 있다. 현재 경기장 삭제와 경기 수정의 잠금 순서 보장이 우선이며, 실제 병목 자료 없이 잠금을 분리하지 않았다.
- 친구 변경은 사용자 두 행을 같은 순서로 잠근다. 검색은 바인딩 파라미터와 LIKE escape를 쓰며, 차단과 소유권을 응답 재생 시에도 다시 확인한다. Java UUID의 정렬 규칙을 사용하는 기존 pair 정규화는 데이터 재배치 없이 유지했다.
- 조회 요약은 원본을 매번 계산하지 않는다. 경기 결과 페이지는 자식 테이블별 IN 조회를 사용하여 쿼터 수만큼 쿼리를 반복하지 않는다.
- 심박 H개와 GPS G개는 정렬된 입력에서 O(H+G), Edwards Zone 수는 고정이다. 교차 구간은 진행 포인터로 순회한다. 원본 리스트·심박 구간 저장으로 공간은 O(H+G)이며 HTTP 원본 상한(현재 8 MiB)을 확정한 무제한 입력으로 오해하면 안 된다.
- 기간 분석은 선택한 완료 쿼터 Q개에 대해 고정 개수 지표를 계산하므로 O(Q)다. 기간을 생략하면 전체 기록을 메모리에 읽는다. 현재 부하 측정 없이 임의 기간 상한을 추가하지 않았으며, 대규모 계정에서는 SQL 집계/스트리밍 후보로 남긴다.
- 부분 닉네임 검색은 선행 % 특성상 B-tree만으로 문자열 검색량을 줄일 수 없다. 페이지 크기는 제한되지만 검색 대상 전체 스캔 비용은 남는다. 실제 규모와 검색 UX를 근거로 별도 검색 인덱스를 결정해야 한다.
- 워커는 여러 인스턴스에서 같은 작업을 중복 계산할 수 있으나 job ID와 PROCESSING 조건부 UPDATE로 결과 게시와 version 증가는 한 번만 성공한다. 중복 계산 비용 및 반복 장애의 재시도 제한은 운영 부하·P10/P11 보존 정책과 함께 후속 설계 대상이다.

## SQL 실행 계획 및 검증

실제 findProcessing 쿼리의 EXPLAIN을 전용 MySQL에서 비교했다. 소규모 검증 DB에서의 접근 계획이며 대규모 처리량/지연 벤치마크 결과는 아니다.

| 요약 테이블 접근 | type | key | Extra |
| --- | --- | --- | --- |
| V003 전 | ALL | 없음 | Using where; Using filesort |
| V003 후 | ref | idx_summary_pending | 없음 |

- Java 21 기본 테스트 **127개 통과**, MySQL 통합 **9개 통과**. 실패·오류·건너뜀 0. 인증 필터를 끄지 않고 기능 테스트의 JWT를 검증한다.
- MySQL: 기존 create-tables.sql → V001 → V002 → V003 적용. 기존 업로드, 27개 테이블, FK/UUID/DATETIME(6)/JSON, 이메일 동시 한도와 실제 HTTP 경합 검증.
- HTTP MySQL: 동일 키 동시 생성은 같은 자원/메타데이터를 재생, 같은 ETag 다른 키 수정은 200/412로 분리. 생성→쿼터→상태 전이→완료→지연 업로드→워커 중복 실행→결과 및 analytics 조회.
- REQUIRES_NEW 발송 제한 테스트의 고정 식별자가 반복 실행 시 누적되던 문제를 실행별 고유 이메일/IP로 수정했다.
- bootJar 패키징 및 git diff --check 통과. 실제 Jenkins, AWS 관측, APNs, iOS 기기는 이번 로컬 검증 범위가 아니다.

## 병합·출시 전에 남은 사항

1. **#35는 현 상태로 함께 병합하지 않는다.** calculation_job_id를 제거하지만 V001은 해당 컬럼을 NOT NULL로 생성하고 #52~#55는 UUID 기반 작업 세대를 사용한다. 이 통합안은 UUID 방식을 유지한다. updated_at 방식의 별도 변경을 채택하려면 도메인·워커·DDL·테스트 전체를 함께 전환해야 한다. 기존 PR을 자동 종료하거나 원격 브랜치를 덮어쓰지 않았다.
2. **P05 실제 positionCode 목록 미확정.** 기본 설정이 빈 목록이므로 저장 요청은 422다. 미입력 완료는 허용되지만 정상 포지션 저장을 출시하려면 코드 합의/설정이 필요하다.
3. **P10/P11 잔여 정책.** #44는 온라인 조작만 확정했다. 센서 시계 오차·파일 크기·재측정·키 만료 후 정리/재사용·원본 보존과 삭제 기준은 아직 모두 확정되지 않았다. expires_at 저장만 있고 자동 정리/재사용은 구현하지 않았다.
4. **인증/동의 API의 실제 제품 흐름.** #78의 토큰 검증 및 저장소 연결은 검증했지만 이메일 발송→가입→로그인→갱신·동의 UI까지 전체 API가 이 PR에 구현된 것은 아니다. 구형 가입 우회 경로를 계속 사용하는 클라이언트는 전용 인증 API로 이행해야 한다.
5. **P09 알림 생성·발송.** 목록/읽음/기기 관리와 암호화만 구현되어 있으며 실제 알림 발생·APNs 전달·보존은 별도다.
6. **통합 PR 특성.** 최신 develop을 포함하되 미병합 기능 PR을 한 브랜치에 모은 통합 검증안이다. 원격 develop에 직접 반영하거나 배포하지 않는다. 선행 PR을 개별 병합할 경우 이 PR의 diff가 줄어들도록 base/merge 순서를 관리한다.

## 공식 문서 확인

- [Spring Security JWT 검증과 사용자 정의 validator](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security Principal 이름 선택 구현](https://github.com/spring-projects/spring-security/blob/main/core/src/main/java/org/springframework/security/authentication/AbstractAuthenticationToken.java)
- [MySQL 8.4 ORDER BY와 복합 인덱스](https://dev.mysql.com/doc/refman/8.4/en/order-by-optimization.html)
