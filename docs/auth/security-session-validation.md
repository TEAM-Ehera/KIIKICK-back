# Access Token과 세션 검증

- Access Token은 HS256으로 서명하며 `uid`, `sid`, `issuer`, `audience`, `token_type`, 만료 시각을 검증한다.
- 보호 API는 토큰 검증 후 `auth_sessions`의 기본 키로 `AuthSessionRepository.findActiveById()`를 한 번 조회한다.
- 토큰의 사용자 ID와 세션의 사용자 ID가 같고, 세션이 존재하며 폐기되지 않았고 만료 전인 경우만 인증한다.
- 따라서 로그아웃·탈퇴·비밀번호 재설정이 세션을 폐기하면 기존 Access Token도 즉시 거부된다.
- 현재는 일관성과 즉시 무효화를 위해 DB를 직접 조회한다. 요청마다 인덱스 단건 조회 비용이 추가된다.
- 캐시는 DB 부하를 줄일 수 있지만 폐기 직후의 오래된 값이 인증을 허용할 수 있어 이번 범위에는 적용하지 않는다.
- 서명 키와 토큰·비밀번호 원문은 로그에 남기지 않는다.
