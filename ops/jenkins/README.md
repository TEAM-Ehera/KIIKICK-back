# 홈서버 Jenkins

EC2 인스턴스와 Nginx는 App Store 앱의 기존 `kiikick-api.duckdns.org` 주소를 계속 중계한다. Jenkins 이전 때문에 EC2를 종료하거나 Nginx·HTTPS·기존 도메인을 제거하지 않는다. EC2의 구 API 재배포 차단 파일도 유지한다.

Jenkins 2.580.2와 기존 사용자·암호화된 자격증명·빌드 메타데이터를 홈서버로 이전한다. 공개 도메인이나 Cloudflare Tunnel에 Jenkins를 연결하지 않는다. 내부 접속은 기존 계정으로 다음 SSH 터널을 사용한다.

```sh
ssh -N -L 8090:127.0.0.1:8090 asher@100.117.204.84
# 브라우저: http://localhost:8090
```

홈서버 경로는 `/home/asher/apps/kiikick-jenkins`다. `compose.yaml`의 UID/GID는 홈서버 asher=1000, Docker 소켓 그룹=983에 맞춘 값이다. 다른 서버에서는 `id`와 소켓 그룹을 먼저 확인한다. Jenkins는 배포에 Docker 소켓과 KIIKICK 배포 디렉터리를 사용하므로 Jenkins 접근은 기존 관리자에게만 허용한다. 플러그인·사용자 설정과 `secrets/`는 함께 백업하며 외부에 공개하지 않는다.

이미지 빌드 시 `Dockerfile`과 `../deploy/install-homeserver-jar.py`를 전용 빌드 디렉터리에 복사한다. 이 디렉터리에 Jenkins home이나 환경 파일을 넣지 않는다.

```sh
docker build -t kiikick-jenkins:2.580.2-homeserver image
docker compose up -d
```

`kiikick-develop`은 `develop`을 2분 간격으로 확인하고 Java 21 테스트 → assemble → SHA-256 검증 → 홈서버 배포를 수행한다. 관련 변경이 develop에 반영되기 전에는 잡을 비활성화해 구 JAR가 모니터링을 덮어쓰지 않게 한다. 변경 브랜치 검증은 별도 `kiikick-homeserver-validation` 잡에서 수행한다.

홈서버 배포 도우미는 현재 환경 파일을 유지하고 `SENTRY_RELEASE`만 커밋 SHA로 갱신한다. Sentry/PostHog 클래스·라이브러리가 없거나 검증용 엔드포인트가 들어간 JAR는 거부한다. candidate 상태 확인 → Nginx 전환 → Compose API 교체 → 상태 확인 → 원래 upstream 복귀 순서로 진행한다. 실패 시 이전 이미지·환경·Nginx 구성을 복원한다. DB 스키마를 변경하거나 과거 DB로 되돌리지는 않는다. rollback 자체가 실패하면 candidate를 남기고 운영자 점검을 요구한다.

Jenkins Package 단계는 `assemble`을 사용한다. 소스 컨텍스트 업로드를 자동화하려면 별도의 Sentry 빌드 전용 토큰을 CI 비밀 관리에 설정해야 한다. 토큰 없는 빌드에서도 오류 수집과 PostHog는 유지되지만 새 빌드의 Sentry 소스 줄은 자동 업로드되지 않는다. 개인 CLI 로그인 토큰을 서버에 복사하지 않는다.

배포 검사 테스트:

```sh
python3 -m unittest discover -s ops/deploy/tests -v
```

## 이전 검증 기록 (2026-11-26)

홈서버 Jenkins 로그인 화면 200, 기존 계정·암호화된 자격증명 복원, Java 21과 Docker 접근을 확인했다. 설치된 배포 도우미로 기존 승인된 운영 JAR를 다시 배포해 candidate/primary 전환과 임시 컨테이너 제거를 확인했다. 운영 DB·인증·Sentry·PostHog 환경을 유지했다. EC2 Nginx는 계속 실행 중이며 기존 앱 주소의 상태 점검은 200, 비인증 경기 조회는 401이다.

변경 검토: https://github.com/TEAM-Ehera/KIIKICK-back/pull/132

홈서버 Jenkins `kiikick-homeserver-validation` #1은 Java 테스트 365개와 배포 도우미 테스트 5개, JAR 패키징을 모두 통과해 SUCCESS로 완료했다. EC2의 Jenkins 자동 시작만 비활성화했으며 Nginx는 유지한다.
