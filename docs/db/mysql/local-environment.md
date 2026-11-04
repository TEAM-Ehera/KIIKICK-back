# Docker 기반 로컬 MySQL

저장소의 JDBC 설정은 MySQL과 TLS, UTC 세션을 사용한다. 서버의 정확한 엔진 버전과 DB 설정은 운영 담당자가 확인한 값을 기준으로 맞춘다. 운영 MySQL의 패치 버전은 8.4.11이며 로컬은 같은 버전의 공식 이미지를 사용한다. 운영 Ubuntu 패키지와 컨테이너 이미지는 배포 형태가 다르다.

## 서버와 비교할 값

서버에서 권한이 있는 담당자가 아래 읽기 전용 쿼리 결과를 확인한다. 서버 주소·계정·비밀번호는 공유할 필요가 없다.

```sql
SELECT VERSION() AS version, @@version_comment AS engine,
       @@GLOBAL.sql_mode AS sql_mode,
       @@GLOBAL.character_set_server AS server_charset,
       @@GLOBAL.collation_server AS server_collation,
       @@GLOBAL.time_zone AS server_time_zone,
       @@GLOBAL.lower_case_table_names AS lower_case_table_names,
       @@GLOBAL.require_secure_transport AS require_secure_transport;
SELECT @@character_set_database AS database_charset,
       @@collation_database AS database_collation;
```

확인한 엔진의 정확한 패치 버전 태그 또는 이미지 digest로 `MYSQL_IMAGE`를 설정한다. 차이가 있는 DB 설정은 로컬 설정에 반영한 뒤 비교 쿼리를 다시 실행한다. `lower_case_table_names`는 초기화 시 결정되므로 값이 달라지면 새 볼륨에서 확인한다. 관리형 MySQL이라면 이미지에서 재현 가능한 엔진·파라미터와 서버에서 별도로 확인해야 하는 설정을 구분한다.

## 실행과 검증

저장소 루트에서 실행한다. 운영과 같은 MySQL 패치 버전을 사용한다. 로컬의 TLS 강제 설정은 JDBC의 TLS 연결을 확인하기 위해 켠다. 운영 MySQL의 현재 강제 설정은 OFF다.

```sh
export MYSQL_IMAGE=mysql:8.4.11
docker run -d --name kiikick-migration-mysql \
  -p 127.0.0.1:13306:3306 \
  -v kiikick-migration-mysql-8411:/var/lib/mysql \
  -e MYSQL_DATABASE=kiikick_migration \
  -e MYSQL_USER=kiikick_local \
  -e MYSQL_PASSWORD=kiikick-local-dev \
  -e MYSQL_ROOT_PASSWORD=kiikick-local-root \
  "$MYSQL_IMAGE" --default-time-zone=+00:00 --require-secure-transport=ON \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci \
  --lower-case-table-names=0
```

기존 8.4.13 컨테이너에서 옮길 때는 먼저 SQL 덤프를 만들고 기존 컨테이너를 중지한다. 8.4.11은 새 볼륨에서 초기화하고 덤프를 복원한다.

아래 쿼리가 성공하면 MySQL에 TLS로 접속할 수 있다. 초기화 중이면 잠시 후 다시 실행한다.

```sh
docker exec kiikick-migration-mysql sh -c '
  MYSQL_PWD="$MYSQL_PASSWORD" mysql --host=127.0.0.1 --ssl-mode=REQUIRED \
    --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --execute="SELECT VERSION(), @@session.time_zone"
'
```

빈 DB 시나리오는 새 볼륨에 이행 SQL을 적용한다. 기존 데이터 시나리오를 처음 준비할 때는 별도 빈 볼륨에서 아래 두 파일부터 적용한다.

```sh
cat docs/db/mysql/create-tables.sql docs/db/mysql/legacy-migration-fixture.sql |
  docker exec -i kiikick-migration-mysql sh -c '
    MYSQL_PWD="$MYSQL_PASSWORD" mysql --host=127.0.0.1 --ssl-mode=REQUIRED \
      --user="$MYSQL_USER" --database="$MYSQL_DATABASE"
  '
```

로컬 백엔드 연결 값은 `DB_HOST=127.0.0.1`, `DB_PORT=13306`, `DB_NAME=kiikick_migration`, `DB_USERNAME=kiikick_local`, `DB_PASSWORD=kiikick-local-dev`다. 포트를 바꾸면 백엔드의 `DB_PORT`도 맞춘다.

중지와 재시작은 `docker stop kiikick-migration-mysql`과 `docker start kiikick-migration-mysql`을 사용한다. 별도의 검증 환경을 함께 실행하려면 컨테이너 이름, 호스트 포트, 볼륨 이름을 각각 다르게 지정한다. 적용 전후 비교 항목은 [DB 이행 검증](migration-verification.md)을 따른다.

## 운영 MySQL의 Docker 전환 순서

서버에는 Ubuntu의 MySQL 서비스가 실행 중이고 Docker CLI는 확인되지 않았다. Docker 설치 후 별도 포트에서 같은 MySQL 버전과 새 영구 볼륨으로 준비한다. DB 포트는 loopback에만 바인딩하고 운영 자격 증명을 사용한다.

1. 기존 DB의 스키마·데이터와 백엔드 계정의 권한을 백업한다. 테스트 복원에서 테이블, 행 수, 대표 레코드와 JDBC TLS 연결을 확인한다.
2. 전환 시간에 백엔드와 다른 DB 쓰기 작업을 멈춘다. 최종 백업을 컨테이너 DB에 복원한 뒤 백엔드 연결을 새 포트로 바꾼다.
3. 쓰기 재개 전에 읽기·쓰기 점검을 완료한다. 실패하면 기존 연결로 돌아간다. 쓰기를 재개한 뒤에는 새 DB에서 발생한 변경을 보존할 복구 계획이 필요하다.
4. 전환 검증 후 기존 서비스 중지와 보존 기간을 결정한다. 경기·쿼터 스키마 이행은 운영 DB의 Docker 전환과 별도로 검증한다.
