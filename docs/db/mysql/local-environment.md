# Docker 기반 로컬 MySQL

저장소의 JDBC 설정은 MySQL과 TLS, UTC 세션을 사용한다. 서버의 정확한 엔진 버전과 DB 설정은 운영 담당자가 확인한 값을 기준으로 맞춘다. 확인 전에는 `mysql:8.4`로 로컬 동작만 점검할 수 있다.

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

저장소 루트에서 실행한다. 아래 태그는 서버 버전 확인 전 로컬 점검 예시다.

```sh
export MYSQL_IMAGE=mysql:8.4
docker-compose -f docs/db/mysql/compose.yaml up -d --wait
```

Compose 플러그인을 설치한 환경에서는 `docker-compose` 대신 `docker compose`를 사용한다. 빈 DB 시나리오는 초기 상태의 DB에 이행 SQL을 적용한다. 기존 데이터 시나리오는 별도 Compose 프로젝트 또는 초기화한 로컬 DB에서 아래 두 파일부터 적용한다.

```sh
cat docs/db/mysql/create-tables.sql docs/db/mysql/legacy-migration-fixture.sql |
  docker-compose -f docs/db/mysql/compose.yaml exec -T mysql sh -c '
    MYSQL_PWD="$MYSQL_PASSWORD" mysql --host=127.0.0.1 --ssl-mode=REQUIRED \
      --user="$MYSQL_USER" --database="$MYSQL_DATABASE"
  '
```

로컬 백엔드 연결 값은 `DB_HOST=127.0.0.1`, `DB_PORT=13306`, `DB_NAME=kiikick_migration`, `DB_USERNAME=kiikick_local`, `DB_PASSWORD=kiikick-local-dev`다. 포트를 바꾸려면 Compose의 `MYSQL_LOCAL_PORT`와 백엔드의 `DB_PORT`에 같은 값을 사용한다.

`docker-compose -f docs/db/mysql/compose.yaml down`은 볼륨을 보존한다. 빈 상태에서 재검증할 때는 별도 프로젝트와 포트를 사용하거나, 삭제해도 되는 로컬 테스트 데이터인지 확인한 뒤 `down -v`로 초기화한다. 적용 전후 비교 항목은 [DB 이행 검증](migration-verification.md)을 따른다.
