# WorkoutPayload AWS 개발 서버 배포 계획

**상태:** PR 검토용 — 로컬 MySQL 8 검증 완료, RDS 적용 및 EC2 배포는 수행하지 않음

**대상 브랜치:** `feat/workout-payload-upload`

**DDL:** `docs/db/mysql/create-workout-payload-tables.sql`

## 1. Git 및 PR 의존 관계

- base: `feat/pitch-crud` (`c091c23`)
- 작업 브랜치: `feat/workout-payload-upload`
- 선행 PR: Pitch CRUD PR #3 (`feat/pitch-crud` → `feat/pitch-entity`)
- Pitch 프로덕션 코드는 수정하지 않고 `updatedAt` 회귀 assertion만 테스트에 추가했다.
- Match Entity가 포함된 `feat/match-entity`/`feat/match-crud`에는 의존하지 않는다.
- PR은 `feat/pitch-crud`를 base로 생성하고 선행 PR부터 순서대로 리뷰한다.

## 2. MySQL 8 DDL과 JPA 매핑 대응

| JPA Entity | MySQL 테이블 | PK | 관계/UNIQUE |
|---|---|---|---|
| `WorkoutPayload` | `workoutPayloads` | `id BINARY(16)` | 부모 |
| `TrackPoint` | `trackPoints` | `id BIGINT AUTO_INCREMENT` | FK `workoutPayloadId`, UNIQUE `(workoutPayloadId, sampleSeq)` |
| `HeartRateSample` | `heartRateSamples` | `id BIGINT AUTO_INCREMENT` | FK `workoutPayloadId`, UNIQUE `(workoutPayloadId, sampleSeq)` |

주요 타입 대응:

- Java `UUID` + `@JdbcTypeCode(SqlTypes.BINARY)` → MySQL `BINARY(16)`
- iOS Apple epoch `Double` → UTC Java `LocalDateTime` → MySQL `DATETIME(6)`
- 날짜는 가장 가까운 마이크로초로 HALF_UP 반올림
- Workout 날짜 필드는 `@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)`로 직접 JDBC 매핑하여 JVM/JDBC 시간대에 따른 시각 이동을 방지
- GPS, 심박수, 거리, 칼로리 Java `Double` → MySQL `DOUBLE`
- Java `Integer` → MySQL `INT`
- Java `Long` + identity → MySQL `BIGINT AUTO_INCREMENT`
- quoted JPA identifier → MySQL backtick identifier

DDL은 새 테이블 세 개만 생성하며 기존 `users`, `pitches`, `pitch_corners`, `matches`, `match_periods`를 변경하지 않는다. `IF NOT EXISTS`를 사용하지 않아 기존에 이름만 같은 잘못된 테이블이 있을 때 조용히 넘어가지 않도록 했다.

## 3. 로컬 MySQL 8 검증 결과와 재현 절차

MySQL 8.0.46 컨테이너에 DDL을 적용하고 Workout 전용 Spring 테스트 컨텍스트로 실제 검증을 완료했다. 전체 애플리케이션 컨텍스트는 기존 User/Pitch 테이블도 요구하므로, MySQL 통합 테스트는 Workout 엔티티와 Repository만 스캔하고 `ddl-auto=validate`를 사용한다.

아래 비밀번호는 재현용 예시이며 개발 머신 안에서만 사용한다.

```bash
docker run --name kiikick-mysql8-verify \
  -e MYSQL_ROOT_PASSWORD=local-only-password \
  -e MYSQL_DATABASE=kiikick_local \
  -p 3307:3306 \
  -d mysql:8.0
```

컨테이너가 준비된 후 DDL을 적용한다.

```bash
mysql --protocol=TCP \
  -h 127.0.0.1 -P 3307 \
  -u root -plocal-only-password \
  kiikick_local \
  < docs/db/mysql/create-workout-payload-tables.sql
```

대소문자와 테이블 정의를 확인한다.

```sql
SELECT VERSION(), @@lower_case_table_names;
SHOW CREATE TABLE `workoutPayloads`;
SHOW CREATE TABLE `trackPoints`;
SHOW CREATE TABLE `heartRateSamples`;
```

Workout 전용 MySQL 통합 테스트를 실행한다.

```bash
KIIKICK_MYSQL_VERIFY=true \
KIIKICK_MYSQL_URL='jdbc:mysql://127.0.0.1:3307/kiikick_local?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' \
KIIKICK_MYSQL_USERNAME=root \
KIIKICK_MYSQL_PASSWORD=local-only-password \
./gradlew test \
  --tests com.teamehera.kiikick.workout.mysql.WorkoutPayloadMysqlIntegrationTest
```

검증 완료 항목:

- MySQL 8.0.46 DDL 적용 및 JPA `ddl-auto=validate` 통과
- camelCase 테이블·컬럼, `BINARY(16)`, `BIGINT AUTO_INCREMENT`, `DATETIME(6)`, FK, 복합 UNIQUE 확인
- 실제 `POST /api/workout-payloads` 저장 및 Repository 재조회 확인
- 마이크로초 HALF_UP, 0-based `sampleSeq`, `speed/course=-1` 보존 확인
- JVM `Asia/Seoul`/`UTC`와 JDBC `Asia/Seoul`/`UTC` 네 조합 모두 동일한 UTC `DATETIME(6)` 저장 확인
- 전체 H2 테스트 38개 통과, MySQL 전용 테스트는 환경변수가 없으면 의도적으로 스킵

H2 테스트는 camelCase quoted mapping과 날짜 저장 정밀도를 확인하지만 MySQL의 `lower_case_table_names`, backtick, `DATETIME(6)` 동작까지 보증하지 않는다.

## 4. EC2 배포 구조 읽기 전용 점검 명령

다음 명령은 EC2에서 실행하되 설정 파일의 비밀번호나 환경변수 값을 출력해 공유하지 않는다.

### 호스트와 Java

```bash
whoami
hostname
uname -a
java -version
timedatectl
df -h
free -h
```

### Git checkout

```bash
cd /home/ubuntu/KIIKICK-back
git status --short --branch
git log -5 --oneline --decorate
git remote -v
```

`git remote -v` 결과에 토큰이 포함된 URL이 있다면 외부에 공유하지 않는다.

### 실행 프로세스와 포트

```bash
ps -ef | grep '[j]ava'
sudo ss -lntp
sudo systemctl list-units --type=service --all | grep -Ei 'kiikick|spring|java'
```

서비스 이름을 찾은 후 `<service>`를 실제 이름으로 바꾼다.

```bash
sudo systemctl status <service> --no-pager
sudo systemctl show <service> -p WorkingDirectory -p ExecStart -p EnvironmentFiles
sudo journalctl -u <service> -n 200 --no-pager
```

`Environment=` 또는 환경 파일 본문은 DB 비밀번호가 노출될 수 있으므로 출력하지 않는다.

### 배포 artifact

```bash
find /home/ubuntu /opt -maxdepth 4 -type f -name '*.jar' -ls 2>/dev/null
sha256sum /path/to/current.jar
readlink -f /path/to/current.jar
```

### Nginx와 HTTPS

```bash
sudo nginx -t
sudo systemctl status nginx --no-pager
sudo nginx -T 2>/dev/null | grep -nE 'server_name|proxy_pass|client_max_body_size|proxy_(read|send)_timeout'
sudo certbot certificates
```

### 애플리케이션 내부 확인

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8080/v3/api-docs | jq '.info, .paths | keys'
```

## 5. 배포 전 확인 명령

로컬/CI:

```bash
./gradlew clean test
git diff --check
git status --short --branch
```

현재 배포본의 공개 경로:

```bash
curl -i https://<development-domain>/actuator/health
curl -i https://<development-domain>/swagger-ui/index.html
curl -fsS https://<development-domain>/v3/api-docs \
  | jq '.paths["/api/workout-payloads"]'
```

배포 전 `/api/workout-payloads`가 아직 없다면 OpenAPI 결과는 `null`이어야 한다.

## 6. 배포 후 API/Swagger 확인

### 서버 상태

```bash
curl -i https://<development-domain>/actuator/health
curl -i https://<development-domain>/swagger-ui/index.html
curl -fsS https://<development-domain>/v3/api-docs \
  | jq '.paths["/api/workout-payloads"].post'
```

### 빈 배열 업로드

```bash
curl -i -X POST https://<development-domain>/api/workout-payloads \
  -H 'Content-Type: application/json' \
  --data '{
    "startDate": 750000000.0,
    "endDate": 750000600.0,
    "totalCalories": 0.0,
    "totalDistance": 0.0,
    "points": [],
    "heartRateSamples": []
  }'
```

성공 조건은 `201 Created`, `Location` 헤더, UUID 형식의 `workoutPayloadId`다.

### GPS·심박수 업로드

```bash
curl -i -X POST https://<development-domain>/api/workout-payloads \
  -H 'Content-Type: application/json' \
  --data '{
    "startDate": 750000000.0,
    "endDate": 750000600.0,
    "totalCalories": 420.5,
    "totalDistance": 3182.4,
    "points": [{
      "latitude": 37.5665,
      "longitude": 126.978,
      "altitude": 20.1,
      "horizontalAccuracy": 4.8,
      "speed": 2.3,
      "course": 87.5,
      "timestamp": 750000001.2
    }],
    "heartRateSamples": [{
      "timestamp": 750000001.0,
      "bpm": 142.0
    }]
  }'
```

### 잘못된 요청

`endDate < startDate` 요청이 `400` ProblemDetail을 반환하는지 확인한다.

```bash
curl -i -X POST https://<development-domain>/api/workout-payloads \
  -H 'Content-Type: application/json' \
  --data '{
    "startDate": 10.0,
    "endDate": 9.0,
    "totalCalories": 0.0,
    "totalDistance": 0.0,
    "points": [],
    "heartRateSamples": []
  }'
```

## 7. 예상 위험

1. **스키마 누락:** `ddl-auto=validate`이므로 새 테이블이 없거나 이름/타입이 다르면 애플리케이션 시작이 실패한다.
2. **대소문자 차이:** RDS MySQL의 `lower_case_table_names` 값에 따라 테이블 이름 동작이 로컬/H2와 다를 수 있다.
3. **수동 DDL drift:** 마이그레이션 도구가 없어 어느 환경에 어떤 DDL이 적용됐는지 추적하기 어렵다.
4. **UTC 날짜 환경 차이:** 필드별 직접 JDBC 매핑으로 로컬 네 가지 시간대 조합은 통과했지만, 배포 전 EC2 JVM 시간대와 RDS 세션 시간대를 기록하고 배포 후 원시 저장값을 다시 확인한다.
5. **큰 요청 본문:** GPS/심박수 배열이 커지면 Nginx `client_max_body_size`, proxy timeout, JVM 메모리, DB 트랜잭션 시간이 문제가 될 수 있다.
6. **중복 업로드:** 현재 payload에는 client idempotency key가 없어 네트워크 재시도 시 중복 행이 생성된다.
7. **무인증 endpoint:** 현재 API에는 사용자 인증이 없으므로 외부 노출 시 임의 업로드 위험이 있다.
8. **RDS 미검증:** 로컬 MySQL 8 검증은 완료했지만 실제 RDS에는 아직 DDL을 적용하거나 API 저장을 검증하지 않았다.

## 8. 롤백 계획

### 애플리케이션 우선 롤백

배포 전 현재 jar의 경로, SHA-256, Git commit을 기록하고 기존 artifact를 삭제하지 않는다. 새 버전에 문제가 있으면 서비스가 사용하는 symlink 또는 `ExecStart` 대상을 이전 jar로 돌린 후 재시작한다.

```bash
sudo systemctl restart <service>
sudo systemctl status <service> --no-pager
curl -fsS http://127.0.0.1:8080/actuator/health
```

실제 artifact 교체 명령은 현재 EC2의 배포 구조를 확인한 뒤 작성한다. 구조를 모르는 상태에서 jar를 덮어쓰지 않는다.

### DB 롤백

새 테이블은 기존 테이블과 연결되지 않으므로 애플리케이션만 이전 버전으로 돌리고 테이블을 남겨두는 것이 가장 안전하다. 테이블 삭제는 데이터 백업과 미사용 확인 후 별도 승인으로만 수행한다.

삭제가 승인된 경우 의존성 역순은 다음과 같다.

```sql
DROP TABLE `heartRateSamples`;
DROP TABLE `trackPoints`;
DROP TABLE `workoutPayloads`;
```

운영 또는 개발 데이터가 한 건이라도 있으면 먼저 백업하고, 자동 롤백 스크립트에서 위 DROP을 실행하지 않는다.

## 9. 실제 배포 전 승인 지점

- PR 리뷰 및 선행 PR부터의 순차 Merge 승인
- RDS 또는 개발 DB DDL 적용 승인
- 현재 EC2 service/jar 배포 구조 확인
- Nginx body size 및 timeout 변경 필요 여부 승인
- 실제 배포 및 재시작 승인
