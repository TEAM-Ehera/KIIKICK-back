# KIIKICK Docker 배포

Jenkins의 `kiikick-develop` 작업이 Java 21로 검증한 JAR를 서버의
`/usr/local/sbin/kiikick-install-dev-container`에 전달한다. 배포 스크립트를 변경하면
서버에 설치된 사본도 갱신한다. 새 컨테이너는 헬스체크 통과 후 배포를 완료한다.
기동 실패 시 직전 컨테이너를 복원한다.

## 이메일 인증 활성화 전 준비

서버의 `/etc/kiikick/kiikick.env`는 root 소유와 권한 600을 유지한다.
기존 DB 설정에 아래 인증 설정을 추가하고 인증 버전을 배포한다.

| 설정 | 값 |
| --- | --- |
| `AUTH_ACCESS_TOKEN_SECRET_BASE64` | 무작위 32바이트 이상을 Base64로 인코딩한 값 |
| `AUTH_EMAIL_CODE_HMAC_SECRET`, `AUTH_IP_HMAC_SECRET` | 서로 다른 무작위 32바이트 이상의 값 |
| `AUTH_MAIL_HOST`, `AUTH_MAIL_USERNAME`, `AUTH_MAIL_PASSWORD`, `AUTH_MAIL_FROM` | SMTP 호스트, 계정, 비밀번호, 발신 주소 |
| `AUTH_MAIL_PORT` | 기본 587. SMTP 설정에 맞춰 변경 |

배포 스크립트가 이 파일을 Bash로 읽으므로 공백이나 셸 특수문자가 포함된 값은
셸 리터럴로 인용한다. 환경변수 변경은 배포로 컨테이너를 다시 만들 때 반영된다.

이메일 인증을 공개하기 전에 운영 DB의 V001 적용을 확인하고, 배포 버전에 포함된
인증 마이그레이션을 순서대로 적용한다. Nginx는 외부 Forwarded 헤더를 제거하고
X-Forwarded-For를 연결의 원격 IP로 덮어써야 이메일 요청의 IP 한도를 보호할 수 있다.

## 포지션 코드

기본 허용 코드는 대문자 `GK,LB,CB,RB,LM,CM,RM,LW,ST,RW`다.
`KIIKICK_MATCH_POSITION_CODES`를 `/etc/kiikick/kiikick.env`에 설정하면 목록을
덮어쓰며, 배포 스크립트가 컨테이너로 전달한다. 빈 값은 모든 코드의 저장을 거절하므로 사용하지 않는다.
