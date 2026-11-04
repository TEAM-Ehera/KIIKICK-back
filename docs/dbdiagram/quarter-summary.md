# 쿼터 운동 요약 계산 계약 초안

`quarter_summaries`는 쿼터당 한 행인 조회용 파생 데이터다. GPS·심박 원본은 그대로 보존한다. 이 문서는 스키마 제안이며 계산 코드나 API 구현은 아니다. 미확정 기준은 임의의 숫자로 확정하지 않는다.

## 저장할 지표

| 필드 | 단위 | 원본과 계산 기준 |
| --- | --- | --- |
| max_speed_mps | m/s | 유효 GPS 속도의 최댓값. 무효값 -1 및 품질 기준 미달 샘플 제외 |
| total_distance_m | m | WorkoutPayload.totalDistance |
| total_calories_kcal | kcal | WorkoutPayload.totalCalories |
| average_speed_mps | m/s | 총거리 / 운동 시간. 휴식·일시정지 포함 여부를 먼저 정해야 함 |
| high_intensity_distance_m | m | 심박 기준을 만족하는 시간 구간과 겹치는 GPS 이동 거리의 합 |
| acceleration_count | 회 | 유효 속도 차이 / 시간 차이로 가속도 계산 후 임계값·최소 유지 시간·재진입 기준으로 이벤트 집계 |
| deceleration_count | 회 | 감속 임계값과 지속 조건을 만족하는 이벤트 수 |
| direction_change_count | 회 | 이동 방향의 최소 각도 차이(0~180도), 최소 이동 속도, 지속/재진입 조건으로 이벤트 집계 |
| average_heart_rate_bpm | bpm | 유효 심박 측정 구간의 시간 가중 평균 |
| max_heart_rate_bpm | bpm | 유효 심박 샘플의 최댓값 |
| trimp_edwards | AU | Σ(각 Zone 체류 시간(초) / 60 × Zone 가중치) |
| hr_max_reference_bpm | bpm | Edwards 계산에 사용한 개인 기준 최대심박수 스냅샷 |

운동 시간은 기존 quarters.duration_seconds의 설정 시간과 구별한다. 현재 DTO에는 startDate/endDate만 있고 pause 구간은 없으므로, 일시정지를 제외한 활동 시간은 추가 데이터 없이 정확하게 알 수 없다. duration_seconds는 요약 계산에 실제 사용한 시간이다.

## 계산에 필요한 정책

- 고강도 거리: 심박 임계값이 절대 bpm인지 개인 HRmax 비율인지 정해야 한다. GPS와 심박의 timestamp를 맞춰 구간을 결합한다. 단순히 고심박 샘플 수에 거리를 곱하지 않는다. 긴 심박/GPS 누락 구간은 무한 보간하지 않는다. 주행의 최소 속도 조건을 둘지도 정해야 한다.
- 가속/감속: 단위는 m/s². 임계값, 최소 지속 시간, 평활화, 중복 방지/재진입 조건, 최대 샘플 간격을 정해야 한다. 임계값을 넘는 샘플 수를 횟수로 세지 않는다.
- 방향전환: 359도 → 1도는 2도 변화다. course=-1 및 저속 구간 제외 기준과 회전각·최소 시간·중복 방지 기준이 필요하다. 이 값은 GPS 이동 방향 기반 추정치이며 신체의 회전 횟수를 직접 측정한 값은 아니다.
- 품질: GPS 위치 정확도, 이상 속도, 긴 누락 구간 및 유효 시간 비율 기준이 필요하다. DTO의 GPS는 균일한 시간 간격이 아니므로 시간 간격을 반드시 사용한다.

## TRIMP: Edwards로 통일

개인 HRmax 대비 50~60%, 60~70%, 70~80%, 80~90%, 90~100% 구간 체류 시간(분)에 각각 1,2,3,4,5를 곱해 합한다. 가중치는 선형 증가다. 각 Zone은 하한을 포함하고 상한을 제외한다. 마지막 Zone은 100%를 포함한다. 50% 미만은 가중치 0으로 집계하고 HRmax 초과 구간은 TRIMP에서 제외한다.

심박 계산은 30–250 bpm의 유한한 값을 사용한다. 동일 시각에는 마지막 유효 샘플을 선택하고, 유효값이 없는 시각이나 10초 초과 간격을 건너뛰어 연결하지 않는다. 유효 구간 안에서는 선형 보간으로 시간 가중 평균을 계산하고 Zone 경계를 지나는 구간의 체류 시간을 나눈다. HRmax 초과 값도 유효 범위 안이면 평균·최고심박에는 포함한다. 유효 구간이 없으면 평균과 유효 시간은 null이며, 유효 샘플 하나로도 최고심박은 기록할 수 있다. TRIMP에 사용할 구간이 없으면 TRIMP는 null이다.

저장하는 TRIMP 관련 컬럼은 결과인 trimp_edwards와 계산에 사용한 hr_max_reference_bpm 두 개다. Zone별 체류 시간은 계산 중에만 사용하고 별도 저장하지 않는다. 필요하면 보존된 심박 원본으로 다시 계산한다. 안정시 심박수나 성별 계수는 이 모델에 필요하지 않다. 개인 기준 HRmax가 없으면 TRIMP를 null로 두고 metric_status에 MISSING_INPUT을 기록한다.

hr_max_reference_bpm과 이번 쿼터에서 관측된 max_heart_rate_bpm은 다른 값이다. 매 쿼터의 관측 최고값을 HRmax로 자동 대입하지 않는다. 사용자 프로필 변경만으로 과거 점수를 조용히 덮어쓰지 않는다. 기준 HRmax의 출처 및 심박 구간 처리 규칙은 calculation_parameters에 보완할 수 있다. calculation_parameters는 가속·감속·방향전환 등 다른 지표의 임계값 기록에도 사용한다.

## 생성·조회·재계산

1. 업로드 식별자로 중복을 확인하고 원본 파일 내용을 해시한다.
2. workout_payloads 및 GPS·심박 원본을 저장한다. 요약을 교체할 경우 기존 요약을 같은 트랜잭션에서 무효화한다.
3. 설정된 정책으로 요약을 계산해 quarter_id 기준으로 저장한다. 계산 직전의 원본 해시와 현재 해시가 같고 calculation_job_id가 현재 PROCESSING 작업과 일치할 때만 게시한다.
4. 일반 쿼터 조회는 요약 행과 개인 기록을 조회한다. 원본 전체를 다시 읽지 않는다. 경로·그래프 요청은 원본 조회를 별도로 수행한다.
5. 원본/계산 정책 변경 시 해당 쿼터만 재계산한다. calculation_version과 calculation_parameters를 함께 저장해 재현 가능하게 한다.

calculation_status는 PROCESSING/PARTIAL/READY/FAILED 등을 제안한다. metric_status는 지표별 READY/MISSING_INPUT/INSUFFICIENT_DATA/FAILED와 이유를 저장하는 JSON 계약 초안이다. 미계산·누락은 null로 두고 실제 0과 구별한다. valid_hr_duration_seconds 및 valid_gps_duration_seconds로 계산에 사용한 유효 시간도 보관한다. calculated_at은 결과 계산 시각, updated_at은 행 갱신 시각이다.

workout_payload_id와 quarter_id의 복합 FK로 요약이 다른 쿼터의 운동 원본을 가리키는 것을 방지한다. source_payload_hash는 업로드/계산 코드가 실제 원본의 정규화 규칙에 맞게 산출해야 한다. calculation_job_id는 계산을 시작할 때마다 새 UUID를 저장하며, 완료·실패 갱신에서 같은 작업인지 확인한다. 계산 완료와 동시에 원본이 변경되는 경쟁 상황도 서비스 트랜잭션에서 처리해야 한다.

기존 데이터가 있는 MySQL에 적용할 때는 바로 NOT NULL 컬럼을 추가하지 않는다. 아래와 같이 nullable 추가, 기존 행 채우기, 검증, NOT NULL 전환 순서로 적용한다.

```sql
ALTER TABLE quarter_summaries
    ADD COLUMN calculation_job_id BINARY(16) NULL AFTER workout_payload_id;

UPDATE quarter_summaries
SET calculation_job_id = UUID_TO_BIN(UUID())
WHERE calculation_job_id IS NULL;

SELECT COUNT(*) AS missing_job_id_count
FROM quarter_summaries
WHERE calculation_job_id IS NULL;

ALTER TABLE quarter_summaries
    MODIFY COLUMN calculation_job_id BINARY(16) NOT NULL;
```

기존 PROCESSING 행은 실행 중인 작업이 새 ID를 알 수 없으므로 배포 전에 재시도 대상으로 전환하거나 새 계산을 시작해야 한다. 이 저장소에는 운영 마이그레이션 도구가 없으므로 위 SQL은 자동 실행하지 않으며, 실제 적용 시에는 쓰기 중지 또는 호환 배포 순서를 별도로 확정한다.

## 근거

- [iOS WorkoutPayload DTO](https://github.com/DeveloperAcademy-POSTECH/2026-C6-A03-KIIKICK-iOS/blob/main/docs/dto/workout-payload.md)
- [Edwards 계산을 사용한 연구](https://pmc.ncbi.nlm.nih.gov/articles/PMC7240108/)

## API 시간대와 연동 협의

서비스 화면·일별 통계와 일반 API는 Asia/Seoul(+09:00) 기준이다. 운동 원본의 날짜는 Apple epoch로 받고 내부는 UTC로 저장한다. 날짜 형식만 바꾸기 위해 원본 숫자에 9시간을 더하지 않는다. [API 명세](../api-spec.md)와 [iOS 협의표](../api/ios-integration.md)를 함께 따른다.
