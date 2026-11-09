-- iOS의 보정 핀과 원본 핀을 별도로 보관한다. 기존 데이터의 좌표는 추정하지 않는다.
ALTER TABLE venues
    ADD COLUMN corner_a_latitude DOUBLE NULL,
    ADD COLUMN corner_a_longitude DOUBLE NULL,
    ADD COLUMN corner_b_latitude DOUBLE NULL,
    ADD COLUMN corner_b_longitude DOUBLE NULL,
    ADD COLUMN corner_c_latitude DOUBLE NULL,
    ADD COLUMN corner_c_longitude DOUBLE NULL,
    ADD COLUMN corner_d_latitude DOUBLE NULL,
    ADD COLUMN corner_d_longitude DOUBLE NULL,
    ADD COLUMN raw_corner_a_latitude DOUBLE NULL,
    ADD COLUMN raw_corner_a_longitude DOUBLE NULL,
    ADD COLUMN raw_corner_b_latitude DOUBLE NULL,
    ADD COLUMN raw_corner_b_longitude DOUBLE NULL,
    ADD COLUMN raw_corner_c_latitude DOUBLE NULL,
    ADD COLUMN raw_corner_c_longitude DOUBLE NULL,
    ADD COLUMN raw_corner_d_latitude DOUBLE NULL,
    ADD COLUMN raw_corner_d_longitude DOUBLE NULL;
