# KIIKICK 운영 관측과 CI

개발 서버는 Spring Boot를 `kiikick-dev` Docker 컨테이너로 실행한다. Nginx가 로컬 8080으로 프록시하고 애플리케이션은 RDS MySQL에 연결한다. 첫 수집 대상은 Nginx의 구조화된 요청 지표다. URL·쿼리 문자열·클라이언트 IP·요청 본문 없이 요청 수, 5xx 비율, 응답 시간과 요청 ID를 확인할 수 있다. 원인 조사에는 서버의 애플리케이션과 MySQL 로그를 함께 본다.

## Jenkins

1. JDK 21과 Git이 설치된 빌드 에이전트에 `jdk21` 레이블을 붙인다.
2. GitHub Branch Source를 사용하는 Multibranch Pipeline에 저장소를 연결하고 PR과 `develop`을 검색하도록 설정한다. Jenkins에 접근 가능한 GitHub 웹훅을 연결하거나 주기적 브랜치 검색을 사용한다. 저장소 연결에는 읽기와 PR 상태 게시에 필요한 권한을 부여한다.
3. PR에서 Gradle 테스트와 JUnit 결과를 확인한다. `develop` 빌드에서는 JAR를 보관한다. 운영 배포 연결과 MySQL 이행 테스트는 각 환경과 기준 스키마가 준비된 뒤 별도 검증한다.

## AWS 설치 순서

1. 서울 리전에 `/kiikick/prod/nginx` 로그 그룹을 생성하고 보존 기간을 14일로 설정한다. EC2 인스턴스 프로파일에 해당 그룹의 스트림 생성·조회와 로그 쓰기 권한을 부여한다.
2. [Nginx 로그 포맷](nginx-log-format.conf)을 Nginx `http` 컨텍스트에 설치한다. KIIKICK 가상 호스트의 유효한 로그 설정에 `access_log /var/log/nginx/kiikick-metrics.log kiikick_metrics;`를 추가한다. 기존 접근 로그가 상속된다면 같은 수준에 그 설정도 명시한다. `nginx -t`가 성공하면 reload하고 로그 파일에 네 필드만 기록되는지 확인한다. 기존 logrotate 규칙이 새 파일을 포함하는지도 확인한다.
3. EC2에 CloudWatch Agent를 설치하고 [수집 설정](cloudwatch-agent.json)을 전달한다. 예를 들어 파일을 `/etc/kiikick/cloudwatch-agent.json`에 배치한 뒤 다음 명령을 실행한다.

   ```sh
   sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
     -a fetch-config -m ec2 -s -c file:/etc/kiikick/cloudwatch-agent.json
   ```

4. [Discord 함수](discord_notify.py)를 Python Lambda에 배포하고 핸들러를 `discord_notify.lambda_handler`, 타임아웃을 90초로 설정한다. `NGINX_LOG_GROUP=/kiikick/prod/nginx`와 팀 비공개 채널의 `DISCORD_WEBHOOK_URL`을 설정한다. 웹훅 URL은 저장소·터미널 출력에 남기지 않는다. 실행 역할에 Lambda 실행 로그 쓰기, 지정 로그 그룹의 `logs:StartQuery`, `logs:GetQueryResults`, `logs:StopQuery` 권한을 부여한다.
5. Nginx 로그 그룹의 5xx 지표 필터 패턴을 `{ $.status >= 500 && $.status < 600 }`로 설정한다. 지표값은 1, 기본값은 0으로 두고 차원을 추가하지 않는다. 최초 알람 기준은 5분 합계 5건이다. 누락 데이터는 `missing`으로 처리한다. 서비스나 수집이 완전히 끊긴 상황은 별도 외부 HTTPS 헬스체크로 감지한다. 두 알람의 `ALARM`, `OK` 동작에 Lambda를 연결하고 함수 리소스 정책을 해당 알람 ARN과 AWS 계정으로 제한한다.
6. EventBridge Scheduler에 매일 09:00 `Asia/Seoul` 일정과 Lambda 입력 `{"type":"daily-report"}`를 설정한다. 스케줄 실행 역할에는 해당 함수 호출 권한만 부여한다. 일일 리포트에는 요청 수, 5xx 비율, 상위 5xx 상태 코드가 포함된다.
7. 이메일이 필요하면 SNS 알림 주제의 예비 수신자로 `kiikick1022@gmail.com`을 구독한다. 팀이 확인 메일에서 구독을 승인한 뒤 사용할 수 있다.

## 검증과 장애 분석

테스트 환경에서 정상 요청과 5xx를 재현하고 CloudWatch에 기록되는지, Discord에서 장애·복구 알림을 받는지, 일일 리포트의 집계와 일치하는지 확인한다. 실제 장애에서는 알림 시각 주변의 5xx 건수와 최근 Jenkins 빌드를 대조한다. Nginx의 502가 늘었다면 같은 시각의 Spring Boot와 DB 로그를 확인한다.

```sh
sudo journalctl -u kiikick --since '15 minutes ago' --no-pager
sudo tail -n 100 /var/log/nginx/error.log /var/log/mysql/error.log
docker logs --since 15m --tail 100 kiikick-migration-mysql
# Compose를 사용하는 환경
docker compose config -q
docker compose ps -a
docker compose logs --since 15m --tail 100
```

장애 재현 기록에는 감지 시간, 증상, 확인한 로그, 복구 조치, 정상 응답 확인 시간을 남긴다. 로그 원문을 Discord에 공유할 때는 토큰, 요청 본문, 위치·심박 등 개인 데이터를 제거한다.

### 개발 서버 자동 배포

개발 서버의 내부 Jenkins는 `127.0.0.1:8090`에서 실행한다. SSH 로컬 포트 포워딩으로 접속한다. `kiikick-develop`은 공개 저장소의 `develop`만 체크아웃하는 Pipeline from SCM 잡이며, Jenkins가 약 2분 간격으로 변경을 확인한다. JDK 21 에이전트에서 테스트와 JAR 보관을 완료한 뒤 같은 서버의 배포 도우미를 실행한다.

배포 도우미 [install-dev-jar.sh](../deploy/install-dev-jar.sh)는 관리자가 `/usr/local/sbin/kiikick-install-dev-container`에 root 소유로 설치한다. Jenkins 계정에는 이 도우미의 sudo 실행만 허용한다. 관리자는 [Dockerfile](../deploy/Dockerfile)을 `/usr/local/share/kiikick/dev.Dockerfile`에 설치한다. 도우미는 JAR의 SHA-256을 검증하고 커밋 SHA로 태그된 이미지를 빌드한다. 새 컨테이너는 기존 환경 파일을 사용하고 로컬 8080에 바인딩한다. 헬스체크가 실패하면 직전 컨테이너를 복원하고 빌드를 실패로 표시한다. 첫 Docker 전환에서 실패하면 기존 systemd 앱 서비스를 재시작한다. DB 스키마 변경은 배포 전에 별도 이행 절차로 적용한다.
