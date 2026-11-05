# Access Token 검증

- Access Token은 유효기간 15분의 Stateless JWT이며 HS256으로 서명한다.
- 서명 키는 환경변수로 관리하고, 서명·만료·`uid`·`sid`·`issuer`·`audience`·`token_type`을 검증한다.
- 보호 API는 인증 과정에서 DB·캐시의 `auth_sessions` 상태를 조회하지 않는다.
- 로그아웃·탈퇴·비밀번호 재설정은 Refresh Token 세션을 폐기하지만, 이미 발급된 Access Token은 만료까지 최대 15분간 유효할 수 있다.
- 서명 키와 토큰·비밀번호 원문은 로그에 남기지 않는다.
