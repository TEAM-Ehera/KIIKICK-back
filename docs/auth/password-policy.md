# 비밀번호 검증 정책

## 적용 규칙

- 비밀번호는 Unicode 코드 포인트 기준 8~32자이고 UTF-8 기준 72바이트 이하여야 한다.
- 문자 조합, 연속 문자·숫자 정규식은 강제하지 않는다.
- 입력값을 trim, 대소문자 변환, Unicode 정규화하지 않는다.
- 회원가입·비밀번호 변경·비밀번호 재설정은 동일한 `PasswordPolicyValidator`를 사용한다.
- HIBP 등 외부 유출 비밀번호 API로 비밀번호를 전송하지 않는다.
- 원문은 DB·로그·외부 시스템에 남기지 않고 BCrypt 해시만 DB에 저장한다.
- BCrypt cost는 12로 시작하며 최종 운영값은 운영 환경 벤치마크 후 결정한다.

## 로컬 차단 목록

- 파일: `src/main/resources/blacklist.txt`
- 출처: Daniel Miessler의 SecLists
- 원본: `Passwords/Common-Credentials/10-million-password-list-top-1000.txt`
- 고정 커밋: `2d5dc7504a40962c53f932a6d9d5ece4b213dfc6`
- 원본 파일 Git blob: `ff86b6faf30b24b638a2dd64abbc57b0c3769ae1`
- 항목 수: 중복 없는 1,000개
- 라이선스: MIT, `docs/licenses/SecLists-MIT.txt`에 원문 포함
- 원본 URL: https://github.com/danielmiessler/SecLists/blob/2d5dc7504a40962c53f932a6d9d5ece4b213dfc6/Passwords/Common-Credentials/10-million-password-list-top-1000.txt

서버 시작 시 목록을 한 번 읽어 `HashSet<String>`으로 보관한다. 파일이 없거나 읽을 수
없거나 중복 없는 항목 수가 1,000개가 아니면 서버 시작을 실패시킨다. 입력 비밀번호와
목록 항목은 별도 변환 없이 정확히 일치 비교한다.

매년 1회 원본 목록, 라이선스 및 더 적절한 공개 데이터의 존재 여부를 검토한다. 목록을
바꾸는 경우 출처·고정 버전·라이선스를 갱신하고 비밀번호 정책 테스트와 코드 리뷰를 거쳐
배포한다.
