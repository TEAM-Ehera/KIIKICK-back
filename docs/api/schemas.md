# 응답 필드 사전

[전체 API 목차](../api-spec.md) · [공통 규칙](common.md)

v0.5. 표의 모든 키는 응답에 포함한다. nullable 값은 null이며 부모 객체가 null이면 하위 필드는 없다. 목록 메타와 오류 봉투는 공통 규칙을 따른다. DB snake_case와 JSON camelCase를 대응한다. PIVOT과 정책 enum 예시는 확정 목록을 뜻하지 않는다.

<a id="userself"></a>
## UserSelf

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 본인 사용자 ID |
| `nickname` | string | O | 허용 | — | 온보딩 전 null 가능 |
| `avatarUrl` | string | O | 허용 | — | 프로필 이미지 URL |
| `onboardingStatus` | enum | O | 불가 | — | REQUIRED 또는 COMPLETED |
| `comparisonConsent` | boolean | O | 불가 | — | 비교 목적의 현재 동의값에서 파생 |
| `createdAt` | timestamp | O | 불가 | — | 계정 생성 시각 |


<a id="publicprofile"></a>
## PublicProfile

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 공개 범위의 사용자 ID |
| `nickname` | string | O | 불가 | — | 온보딩 완료한 사용자의 표시명 |
| `avatarUrl` | string | O | 허용 | — | 프로필 이미지 URL |
| `relationship` | enum | O | 불가 | — | 조회자 기준 NONE/PENDING_SENT/PENDING_RECEIVED/FRIEND/BLOCKED |


<a id="session"></a>
## Session

Auth 응답의 날짜는 ISO 8601 Asia/Seoul `+09:00` 문자열이다. `UserSelf.createdAt`과 공통 Auth 메타의
`serverTime`도 동일하며 기존 서울 기준 응답 계약을 따른다. 비Auth 날짜·통계 계약은 유지한다.

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `sessionId` | UUID | O | 불가 | — | 로그아웃 대상 세션 ID |
| `accessToken` | string | O | 불가 | — | 서버 인증에 사용하는 비밀값. 로그 금지 |
| `refreshToken` | string | O | 불가 | — | 갱신 시 회전하는 비밀값. 로그 금지 |
| `expiresAt` | timestamp | O | 불가 | — | 15분 유효한 access token의 만료 시각 |
| `user` | UserSelf | O | 불가 | — | 본인 계정·온보딩 정보 |

중첩 타입: [응답 필드 사전 · UserSelf](schemas.md#userself)


<a id="emailverification"></a>
## EmailVerification

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `verificationId` | UUID | O | 불가 | — | 발송·재발송·검증 흐름 식별자 |
| `expiresAt` | timestamp | O | 불가 | — | 3분 유효한 인증번호의 만료 시각 |
| `resendAvailableAt` | timestamp | O | 불가 | — | 동일 이메일 재발송 가능 시각 |


<a id="registrationauthorization"></a>
## RegistrationAuthorization

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `registrationToken` | string | O | 불가 | — | SIGN_UP 목적의 10분 유효 일회용 비밀값. 로그 금지 |
| `expiresAt` | timestamp | O | 불가 | — | registrationToken 만료 시각 |


<a id="consent"></a>
## Consent

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `purpose` | string | O | 불가 | — | 동의 목적 코드. 목록 P11 미정 |
| `policyVersion` | string | O | 불가 | — | 동의한 약관/정책 버전 |
| `granted` | boolean | O | 불가 | — | true 부여, false 철회 |
| `recordedAt` | timestamp | O | 불가 | — | 서버가 기록한 시각 |


<a id="venue"></a>
## Venue

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 저장 경기장 ID |
| `name` | string | O | 불가 | — | 경기장 이름 |
| `address` | string | O | 허용 | — | 주소 |
| `createdAt` | timestamp | O | 불가 | — | 등록 시각 |
| `updatedAt` | timestamp | O | 불가 | — | 마지막 수정 시각 |


<a id="friendrequest"></a>
## FriendRequest

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 요청 ID. 취소 후 재요청이면 새로운 ID |
| `requesterId` | UUID | O | 불가 | — | 요청한 사용자 |
| `recipientId` | UUID | O | 불가 | — | 받는 사용자 |
| `status` | enum | O | 불가 | — | PENDING 또는 ACCEPTED |
| `createdAt` | timestamp | O | 불가 | — | 요청 생성 시각 |


<a id="friend"></a>
## Friend

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `profile` | PublicProfile | O | 불가 | — | 친구의 공개 프로필. relationship=FRIEND |
| `friendsSince` | timestamp | O | 불가 | — | 친구 요청 수락 시각 |

중첩 타입: [응답 필드 사전 · PublicProfile](schemas.md#publicprofile)


<a id="notification"></a>
## Notification

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 본인이 수신한 알림 ID |
| `type` | string | O | 불가 | — | 알림 종류. 예시 코드는 P09 확정 전 후보 |
| `title` | string | O | 불가 | — | 표시 제목 |
| `body` | string | O | 허용 | — | 부가 문구 |
| `target` | object | O | 불가 | — | 이동 대상 힌트. 실제 조회 시 재인가 |
| `target.type` | enum | O | 불가 | — | MATCH 또는 FRIEND_REQUEST |
| `target.id` | UUID | O | 불가 | — | 이동할 리소스 식별자 |
| `readAt` | timestamp | O | 허용 | — | 최초 읽음 시각. 미열람 null |
| `createdAt` | timestamp | O | 불가 | — | 알림 생성 시각 |


<a id="device"></a>
## Device

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 기기 ID |
| `platform` | enum | O | 불가 | — | IOS 또는 WATCHOS |
| `pushEnabled` | boolean | O | 불가 | — | 서버 발송 허용 여부. OS 권한도 별도 확인 |
| `lastSeenAt` | timestamp | O | 불가 | — | 마지막 서버 접속 시각 |


<a id="deletionrequest"></a>
## DeletionRequest

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `id` | UUID | O | 불가 | — | 탈퇴 처리 요청 ID |
| `status` | enum | O | 불가 | — | PENDING/PROCESSING/COMPLETED/FAILED |
| `requestedAt` | timestamp | O | 불가 | — | 접수 시각 |
| `completedAt` | timestamp | O | 허용 | — | 완료 시각 |


<a id="unreadcount"></a>
## UnreadCount

| 필드 | 타입 | 필수 | null | 기본값 / 예시 | 설명 |
| --- | --- | :---: | :---: | --- | --- |
| `count` | integer | O | 불가 | — | 현재 사용자의 readAt=null인 알림 수. 0 이상 |


<a id="match"></a>
## Match

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| id | UUID | 필수 | 개인 경기 묶음 ID |
| ownerId | UUID | 필수 | 유일한 기록 대상 사용자 |
| title | string | nullable | 표시 제목 |
| venueId | UUID | nullable | 저장 경기장 참조 |
| venueSnapshot | object | nullable | name:string, address:nullable string의 장소 사본 |
| scheduledAt | timestamp | nullable | 예정 시각 |
| status | enum | 필수 | DRAFT/READY/IN_PROGRESS/COMPLETED/CANCELLED |
| startedAt | timestamp | nullable | 경기 시작 |
| endedAt | timestamp | nullable | 경기 종료 |
| version | integer | 필수 | 경기 ETag와 일치 |
| createdAt | timestamp | 필수 | 생성 |
| updatedAt | timestamp | 필수 | 수정 |

<a id="quarter"></a>
## Quarter

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| id | UUID | 필수 | 기록 중심 단위 |
| matchId | UUID | 필수 | 날짜·장소를 공유하는 경기 묶음 |
| number | integer | 필수 | 경기 내 1부터 시작하는 번호 |
| durationSeconds | integer | 필수 | 설정 시간. 실제 측정 시간과 다름 |
| status | enum | 필수 | READY/IN_PROGRESS/COMPLETED |
| startedAt | timestamp | nullable | 시작 |
| endedAt | timestamp | nullable | 측정 마감; IN_PROGRESS인 입력 단계에도 존재 가능 |
| matchVersion | integer | 필수 | 부모 경기 version |
| createdAt | timestamp | 필수 | 생성 |
| updatedAt | timestamp | 필수 | 수정 |

<a id="quarterplayerstats"></a>
## QuarterPlayerStats

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| quarterId | UUID | 필수 | 본인 쿼터 |
| goals | integer | 필수 | 직접 입력하는 0 이상 골 합계 |
| assists | integer | 필수 | 직접 입력하는 0 이상 어시스트 합계 |
| updatedAt | timestamp | 필수 | 수정 |

<a id="positionentry"></a>
## PositionEntry

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| quarterId | UUID | 필수 | 본인 쿼터 |
| positionCode | string | 필수 | 대표 포지션 하나. 코드 목록 P05 |
| updatedAt | timestamp | 필수 | 수정 |

<a id="quartersummary"></a>
## QuarterSummary

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| quarterId | UUID | 필수 | 쿼터당 최대 한 행 |
| workoutPayloadId | UUID | 필수 | 같은 쿼터 운동 원본 |
| calculationStatus | enum | 필수 | PROCESSING/PARTIAL/READY/FAILED |
| calculationVersion | string | 필수 | 계산 알고리즘 버전 |
| metricStatus | object | 필수 | 각 지표 키 → {status,reason}. READY/MISSING_INPUT/INSUFFICIENT_DATA/FAILED |
| durationSeconds | number | nullable | 계산에 사용한 실제 운동 시간; 초 |
| maxSpeedMps | number | nullable | 유효 최고속도; m/s |
| totalDistanceM | number | nullable | 누적 거리; m |
| totalCaloriesKcal | number | nullable | 활동 칼로리; kcal |
| averageSpeedMps | number | nullable | 총거리 / 계산 운동 시간; m/s |
| highIntensityDistanceM | number | nullable | 고심박 조건을 만족하는 구간의 주행거리; m |
| accelerationCount | integer | nullable | 가속 이벤트 수; 회 |
| decelerationCount | integer | nullable | 감속 이벤트 수; 회 |
| directionChangeCount | integer | nullable | GPS 방향전환 이벤트 수; 회 |
| averageHeartRateBpm | number | nullable | 시간 가중 평균 심박; bpm |
| maxHeartRateBpm | number | nullable | 유효 최고 심박; bpm |
| trimpEdwards | number | nullable | Edwards TRIMP; AU |
| hrMaxReferenceBpm | number | nullable | 계산에 사용한 개인 기준 최대심박수; bpm |
| validHrDurationSeconds | number | nullable | 유효 심박 구간 시간; 초 |
| validGpsDurationSeconds | number | nullable | 유효 GPS 구간 시간; 초 |
| calculatedAt | timestamp | nullable | 마지막 결과 계산 시각 |
| updatedAt | timestamp | 필수 | 행 갱신 시각 |

calculation_job_id, calculation_parameters와 source_payload_hash는 내부 작업 식별·재현·무효화 정보이며 일반 응답에 노출하지 않는다. metricStatus.reason은 nullable 코드다. PROCESSING인 새 행의 결과는 null이며 이전 결과를 최신처럼 보여주지 않는다. 입력 부족은 0이 아니다. 기준 최대심박수는 이번 쿼터 관측 최고심박수와 구별한다.

<a id="workout"></a>
## Workout

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| id | UUID | 필수 | 저장된 원본 측정 ID |
| quarterId | UUID | 필수 | 소유 쿼터 |
| startDate | timestamp | 필수 | 응답에서는 +09:00 포함 서울 시각 문자열 |
| endDate | timestamp | 필수 | 응답에서는 +09:00 포함 서울 시각 문자열 |
| totalCalories | number | 필수 | 활동 칼로리 kcal |
| totalDistance | number | 필수 | 이동 거리 m |

<a id="trackpoint"></a>
## TrackPoint

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| sampleSeq | integer | 필수 | 수신 배열의 0부터 시작하는 인덱스 |
| latitude | number | 필수 | 위도 degrees |
| longitude | number | 필수 | 경도 degrees |
| altitude | number | 필수 | 고도 m |
| horizontalAccuracy | number | 필수 | 위치 정확도 m; 무효 표식은 보존 |
| speed | number | 필수 | m/s; -1 무효 표식 보존 |
| course | number | 필수 | degrees; -1 무효 표식 보존 |
| timestamp | timestamp | 필수 | 조회 응답은 +09:00 포함 서울 시각 문자열 |

<a id="heartratesample"></a>
## HeartRateSample

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| sampleSeq | integer | 필수 | 수신 배열 인덱스 |
| timestamp | timestamp | 필수 | +09:00 포함 서울 시각 문자열 |
| bpm | number | 필수 | 측정 심박 원본 |

<a id="quarterresult"></a>
## QuarterResult

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| quarter | Quarter | 필수 | 쿼터 정보 |
| stats | QuarterPlayerStats | nullable | 미입력은 null; 0 입력과 구별 |
| position | PositionEntry | nullable | 미입력은 null |
| workout | Workout | nullable | 미수신은 null |
| summary | QuarterSummary | nullable | 운동 미수신은 null; 수신 후 계산 상태 제공 |

<a id="matchresult"></a>
## MatchResult

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| match | Match | 필수 | 본인 완료 경기 |
| quarterCount | integer | 필수 | 완료 쿼터 개수 |
| goals | integer | nullable | 모든 쿼터 stats가 있으면 합계, 누락 있으면 null |
| assists | integer | nullable | 동일 규칙 |
| missingStatsQuarterIds | UUID[] | 필수 | 직접 입력 기록 누락 쿼터 |
| summaryAvailability | object | 필수 | {ready,partial,processing,failed,missing} 쿼터 수. 경기 상태와 독립 |

<a id="analyticsmeta"></a>
## AnalyticsMeta

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| quarterCount | integer | 필수 | 대상 완료 쿼터 수 |
| missingStatsQuarterCount | integer | 필수 | 직접 입력 기록 누락 수 |
| metricCoverage | object | 필수 | 지표별 {includedQuarterCount,totalQuarterCount} |
| calculationVersions | string[] | 필수 | 대상 요약에서 사용한 알고리즘 버전 목록 |
| emptyReason | string | nullable | NO_COMPLETED_QUARTERS 또는 null |

<a id="summary"></a>
## Summary

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| matchCount | integer | 필수 | 기간 내 대상 쿼터의 서로 다른 경기 수 |
| quarterCount | integer | 필수 | 기간 내 완료 쿼터 수 |
| goals | integer | nullable | 누락이 있으면 null. 대상 없음은 0 |
| assists | integer | nullable | 같은 규칙 |
| metrics | object | 필수 | MetricAggregate를 각 지원 지표 키에 저장 |
| meta | AnalyticsMeta | 필수 | 누락·계산 버전·빈 상태 |

<a id="metricaggregate"></a>
## MetricAggregate

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| value | number | nullable | 모든 대상 쿼터가 유효할 때만 전체값. 하나라도 부족하면 null |
| includedQuarterCount | integer | 필수 | 해당 지표에 유효한 쿼터 수 |
| totalQuarterCount | integer | 필수 | 대상 쿼터 수 |
| status | enum | 필수 | READY/INCOMPLETE/NO_DATA/POLICY_REQUIRED |

<a id="trendpoint"></a>
## TrendPoint

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| bucketStart | timestamp | 필수 | 서울 기준 버킷 시작(+09:00) |
| bucketEnd | timestamp | 필수 | 서울 기준 버킷 끝(+09:00) |
| metric | string | 필수 | 조회 지표 |
| result | MetricAggregate | 필수 | 원본 재계산 없이 저장 요약 사용 |

<a id="matchcomparison"></a>
## MatchComparison

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| current | Summary | 필수 | 본인 선택 경기 |
| previous | Summary | nullable | 이전 완료 경기; 없으면 null |
| metric | string | 필수 | 비교 지표 |
| delta | number | nullable | 양쪽 유효할 때 current-previous |

<a id="positionanalysis"></a>
## PositionAnalysis

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| positionCode | string | nullable | 미입력은 null |
| quarterCount | integer | 필수 | 해당 포지션 쿼터 수 |
| ratio | number | 필수 | 쿼터 수 / 전체 완료 쿼터 수; 0~1 |

<a id="friendcomparison"></a>
## FriendComparison

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| me | Summary | 필수 | 본인 허용 분석 |
| friend | Summary | 필수 | 친구의 허용 분석. 원본 데이터 제외 |
| metric | string | 필수 | 비교 지표 |
| delta | number | nullable | 두 값 유효할 때 차이 |

<a id="home"></a>
## Home

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| activeMatches | Match[] | 필수 | 본인 진행 경기 최대 5개 |
| readyMatches | Match[] | 필수 | 본인 준비 경기 최대 5개 |
| recentMatches | Match[] | 필수 | 본인 최근 완료 경기 |
| recentQuarterResults | QuarterResult[] | 필수 | 최근 완료 쿼터와 저장 요약. 원본 배열 제외 |
| unreadNotificationCount | integer | 필수 | 본인 알림 수 |
| emptyReason | string | nullable | NO_MATCHES/NO_COMPLETED_QUARTERS 또는 null |
| updatedAt | timestamp | 필수 | 응답 구성 시각 |

<a id="operationresult"></a>
## OperationResult

| 필드 | 타입 | 필수 / null | 의미 |
| --- | --- | --- | --- |
| operationId | UUID | 필수 | 재전송에도 유지하는 논리 작업 ID |
| status | enum | 필수 | APPLIED/REJECTED |
| httpStatus | integer | 필수 | 개별 명령 결과 |
| resourceId | UUID | nullable | 변경 대상 |
| matchVersion | integer | nullable | 작업 후 경기 버전 |
| error | object | nullable | 공통 error 형식 |
| processedAt | timestamp | 필수 | 처리 시각 |
