package com.teamehera.kiikick.global.jdbc;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

public final class JdbcValueConverter {

    private JdbcValueConverter() {
    }

    public static byte[] uuidToBytes(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    public static UUID bytesToUuid(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static LocalDateTime nullableDateTime(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        // DATETIME의 UTC 벽시계 값을 보존한다. Timestamp를 거치면 JVM 시간대가 커서에 섞일 수 있다.
        return resultSet.getObject(column, LocalDateTime.class);
    }
}
