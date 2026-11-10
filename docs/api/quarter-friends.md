# 쿼터별 함께 뛴 친구

경기 소유자가 각 쿼터에 함께 뛴 친구를 기록한다. 친구의 경기·쿼터를 변경하거나 기록 조회 권한을 부여하지 않는다.

| 동작 | 요청 | 성공 |
| --- | --- | --- |
| 목록 조회 | `GET /api/v1/matches/{matchId}/quarters/{quarterId}/friends` | 200 |
| 친구 추가 | `PUT /api/v1/matches/{matchId}/quarters/{quarterId}/friends/{friendUserId}` | 204 |
| 친구 삭제 | `DELETE /api/v1/matches/{matchId}/quarters/{quarterId}/friends/{friendUserId}` | 204 |

모든 경로 ID는 UUID이며 `Authorization: Bearer <accessToken>`이 필요하다. 수정은 `Idempotency-Key: <UUID>`와 `If-Match: "<matchVersion>"`가 필수이며 요청 본문은 보내지 않는다. 목록 조회 또는 경기 조회의 ETag를 사용하고, 수정 응답의 ETag로 갱신한다. 수정 응답에는 `X-Request-Id`가 포함된다.

```http
PUT /api/v1/matches/{matchId}/quarters/{quarterId}/friends/{friendUserId}
Authorization: Bearer <accessToken>
Idempotency-Key: <UUID>
If-Match: "3"
```

성공 응답: `204 No Content`, `ETag: "4"`. 삭제 요청도 같은 헤더를 사용한다.

목록 조회 응답 예시:

```json
{
  "data": [
    {
      "userId": "00000002-0000-4000-8000-000000000002",
      "nickname": "친구",
      "avatarUrl": null
    }
  ],
  "meta": {
    "requestId": "00000003-0000-4000-8000-000000000003",
    "serverTime": "2026-11-10T18:00:00+09:00",
    "matchVersion": 4
  }
}
```

- 경기 소유자만 조회·추가·삭제한다. 쿼터는 지정한 경기에 속해야 한다.
- 추가 시 수락된 친구 관계가 필요하다. 본인, 미수락·해제된 친구, 차단·탈퇴한 사용자는 추가할 수 없다.
- 경기·쿼터 상태와 무관하게 완료 후에도 기록을 편집할 수 있다.
- 같은 친구는 쿼터마다 한 번씩 저장된다. 서로 다른 쿼터에는 각각 등록할 수 있다.
- 친구 해제는 과거 함께 뛴 기록을 지우지 않는다. 차단·탈퇴한 사용자는 목록에서 숨긴다. 친구 관계가 해제되거나 차단되어도 기존 기록 삭제는 가능하다.
- 이미 등록된 친구의 추가, 없는 기록의 삭제도 204다. 새 멱등 키로 성공한 수정 요청은 경기 버전을 1 증가시킨다.
- 동일 키·경로·메서드·If-Match 재전송은 저장된 응답과 헤더를 재생한다. 최신 버전으로 다시 수정할 때는 새 키를 사용한다.
- 목록은 등록 시각·사용자 ID 순서이며 페이지네이션은 사용하지 않는다. 친구 프로필 정보는 현재 값을 표시한다.

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 잘못된 UUID·멱등 키 또는 허용하지 않는 요청 본문 |
| 401 | UNAUTHENTICATED / TOKEN_EXPIRED | 인증 정보 없음·만료 |
| 404 | RESOURCE_NOT_FOUND | 접근할 수 없는 경기·쿼터 또는 추가할 수 없는 친구 |
| 412 | VERSION_CONFLICT | If-Match와 현재 경기 버전 불일치 |
| 428 | PRECONDITION_REQUIRED | If-Match 누락 |
| 409 | IDEMPOTENCY_KEY_REUSED | 동일 키에 다른 If-Match 재사용 |

## DB 반영

기존 MySQL DB에는 [V007__quarter_friends.sql](../db/mysql/migrations/V007__quarter_friends.sql)을 V001–V006 이후 적용한다. `quarter_friends`의 기본 키는 `(quarter_id, friend_user_id)`다. 쿼터·사용자 물리 삭제 시 연결 기록도 삭제된다. 친구 관계와는 외래 키로 연결하지 않는다.

새 DB용 [full-design.sql](../dbdiagram/full-design.sql)에도 포함되어 있으므로 해당 파일로 생성한 DB에 V007을 중복 적용하지 않는다. 롤백은 `DROP TABLE quarter_friends`이며 함께 뛴 친구 기록이 삭제되므로 보존이 필요하면 먼저 백업한다.
