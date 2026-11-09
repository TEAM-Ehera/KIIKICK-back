# KIIKICK 설계 문서

**2026-11-02 · v0.4 · 쿼터 중심 개인 기록 설계**

| 문서 | 내용 |
| --- | --- |
| [기능 명세](functional-spec.md) | 확정 요구사항·화면·흐름·추적표 |
| [API 명세](api-spec.md) | 개인 경기·쿼터·운동 업로드·요약 조회의 전체 경로와 상세 계약 |
| [ERD](erd.md) | 20개 테이블·28개 FK, 컬럼·인덱스·무결성 |
| [iOS 연동 협의표](api/ios-integration.md) | 현재 계약·iOS 변경점·결정 항목을 모은 회의용 표 |
| [확정·미확정 정책](decisions.md) | 사용자 결정과 남은 산식·공개·운영 정책 |
| [쿼터 요약 계산](dbdiagram/quarter-summary.md) | 운동 지표의 단위·산식·누락·재계산 |
| [SQL·DBML](dbdiagram/README.md) | 최신 설계와 현재 PR 스키마의 다이어그램용 파일 |
| [이전 PR 비교 기록](pr-comparison.md) | 특정 커밋과 v0.2의 역사적 비교. 현재 구현 상태 재확인 자료는 아님 |

## 목적에 따라 읽기

| 목적 | 시작 문서 | 읽는 방법 |
| --- | --- | --- |
| 팀 전체가 방향을 검토 | [API 공유본](review/api-spec.md), [iOS 협의표 공유본](review/ios-integration.md) | 주요 흐름·예시·결정 항목부터 읽기 |
| iOS·서버 개발 중 계약 확인 | [API 명세 원본](api-spec.md) | 전체 목록에서 API ID를 선택해 api/의 상세 요청·응답·오류로 이동 |
| 공통 DTO·통신 구현 | [공통 규칙](api/common.md), [응답 필드 사전](api/schemas.md) | 시간·인증·헤더·재시도·필드 타입 확인 |
| 합의 여부 확인 | [iOS 협의표 원본](api/ios-integration.md), [정책 목록](decisions.md) | 확정 사항과 미정인 기술 제안을 구분 |
| DB·계산 구현 참고 | [ERD](erd.md), [요약 계산 계약](dbdiagram/quarter-summary.md) | 원본·요약 관계와 미확정 산식 확인 |

문서가 main에 병합되어도 모든 API·정책이 일괄 승인되거나 구현됐다는 뜻은 아니다. 개발자는 합의된 범위부터 구현하고, 미정인 항목은 협의표에서 결정한 뒤 원본 명세를 갱신한다. 공유본은 설명용 요약이며 **개발 중 계약 확인은 원본 명세를 기준으로 한다.** 두 문서가 다르면 원본의 현재 계약을 확인하고 공유본을 맞춘다.

## 문서 수정 규칙

- 경로·필드·오류·시간·상태 정책을 바꿀 때는 api-spec.md와 해당 api/ 상세, schemas.md/common.md 중 영향을 받는 문서를 같은 PR에서 수정한다.
- 협의된 항목은 api/ios-integration.md와 decisions.md에 반영한다. API 경로에 바뀐 내용이 없다면 공유를 위해 서버 코드를 함께 변경할 필요는 없다.
- 팀 안내용 review/ 공유본도 의미가 달라진 부분을 함께 갱신한다. 두 벌을 독립된 계약으로 관리하지 않는다.
- GitHub에서는 이 README에서 상대 링크를 따라 읽는다. 로컬 전용 절대경로를 문서 본문 링크로 넣지 않는다.

## 현재 기준

각자 자기 경기만 기록한다. 쿼터별 개인 골·어시스트·대표 포지션과 운동 데이터를 저장하며 경기는 날짜·장소를 공유하는 묶음이다. GPS·심박 원본을 모두 보존하고 요청한 운동 지표는 계산 후 quarter_summaries에 저장한다. TRIMP는 Edwards로 통일한다. 서비스와 날짜별 통계는 Asia/Seoul 기준이며 일반 API 날짜는 +09:00을 포함한다. DB 내부 UTC 저장과 운동 원본 Apple epoch는 유지한다. 일반 조회에서 원본 전체를 재계산하지 않는다.

경기 참가자·팀 점수·득점자 연결·이벤트 수정 이력·경기 종료 집계/발행 대기 구조는 제거했다. #115의 운동 원본에는 goal/assist 발생 위치와 운동 당시 경기장 사본을 보존한다. 경기 종료 후 늦게 도착한 쿼터 운동 파일은 수신할 수 있다. 친구 관계는 개인 경기 상세·원본 접근 권한을 부여하지 않는다.

## 신뢰 범위와 사용 순서

사용자가 확정한 요구사항과 별도 기술 제안을 구분한다. Notion 상세 본문은 확인하지 못했고 미확인 내용을 확정 요구사항으로 추가하지 않았다. 운동 원본 필드는 [iOS DTO](https://github.com/DeveloperAcademy-POSTECH/2026-C6-A03-KIIKICK-iOS/blob/main/docs/dto/workout-payload.md)를 따른다. Watch → iPhone 전송과 신규 서버 API 계약을 구별한다.

기능 명세 → 정책 목록 → API → ERD 순으로 검토한다. 계산 지표는 계산 계약을 함께 읽는다. 이 문서들은 목표 설계이며 서버 구현·인증 적용·마이그레이션·배포 완료를 뜻하지 않는다. docs/dbdiagram/current-pr.*는 비교 당시 구현의 역사적 스키마로 유지한다. full-design.*가 현재 설계 기준이다. SQL은 ERD 생성용이며 운영 DB에 실행하지 않았다.

## 운영 관측과 CI

- [CloudWatch·Discord·Jenkins 설정 및 장애 대응](../ops/observability/README.md)
