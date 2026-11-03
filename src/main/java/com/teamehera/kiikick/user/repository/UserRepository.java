package com.teamehera.kiikick.user.repository;

import com.teamehera.kiikick.user.domain.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/*
 * Repository는 데이터베이스 접근을 담당한다.
 *
 * Controller나 Service는 SQL을 직접 실행하지 않는다.
 * DB 관련 작업은 이 클래스에 모아 둔다.
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    /*
     * 생성자 주입.
     *
     * Spring이 JdbcTemplate 객체를 생성해서
     * UserRepository 생성자에 전달해 준다.
     */
    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /*
     * ResultSet의 현재 행 -> User 객체 변환.
     *
     * SQL SELECT 결과를 Java 객체로 만드는 역할이다.
     */
    private final RowMapper<User> userRowMapper = (rs, rowNum) ->
            new User(
                    bytesToUuid(rs.getBytes("id")),
                    rs.getString("nickname"),
                    rs.getTimestamp("createdAt").toLocalDateTime(),
                    rs.getTimestamp("updatedAt").toLocalDateTime(),
                    getNullableDateTime(rs, "deletedAt")
            );

    // CREATE: 사용자 생성
    public void save(UUID id, String nickname) {

        String sql = """
                INSERT INTO users
                    (id, nickname, createdAt, updatedAt)
                VALUES
                    (?, ?, NOW(6), NOW(6))
                """;

        // ? 자리에 UUID 바이트 배열과 nickname을 순서대로 넣는다.
        jdbcTemplate.update(sql, uuidToBytes(id), nickname);
    }

    // READ: UUID로 활성 사용자 한 명 조회
    public Optional<User> findById(UUID id) {

        String sql = """
                SELECT id, nickname, createdAt, updatedAt, deletedAt
                FROM users
                WHERE id = ?
                  AND deletedAt IS NULL
                """;

        List<User> users = jdbcTemplate.query(
                sql,
                userRowMapper,
                uuidToBytes(id)
        );

        // 조회 결과가 없으면 Optional.empty()를 반환한다.
        return users.stream().findFirst();
    }

    // READ: 삭제되지 않은 사용자 전체 조회
    public List<User> findAll() {

        String sql = """
                SELECT id, nickname, createdAt, updatedAt, deletedAt
                FROM users
                WHERE deletedAt IS NULL
                ORDER BY createdAt DESC, id ASC
                """;

        return jdbcTemplate.query(sql, userRowMapper);
    }

    // UPDATE: 닉네임 변경
    public int updateNickname(UUID id, String nickname) {

        String sql = """
                UPDATE users
                SET nickname = ?,
                    updatedAt = NOW(6)
                WHERE id = ?
                  AND deletedAt IS NULL
                """;

        // update()는 변경된 행의 개수를 반환한다.
        return jdbcTemplate.update(sql, nickname, uuidToBytes(id));
    }

    // DELETE: 실제 삭제 대신 deletedAt에 현재 시간 저장
    public int softDelete(UUID id) {

        String sql = """
                UPDATE users
                SET deletedAt = NOW(6),
                    updatedAt = NOW(6)
                WHERE id = ?
                  AND deletedAt IS NULL
                """;

        return jdbcTemplate.update(sql, uuidToBytes(id));
    }

    /*
     * Java UUID -> MySQL BINARY(16)
     *
     * UUID는 128비트 = 16바이트다.
     * MySQL의 BINARY(16) 컬럼에 저장하기 위해 변환한다.
     */
    private static byte[] uuidToBytes(UUID uuid) {

        ByteBuffer buffer = ByteBuffer.allocate(16);

        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());

        return buffer.array();
    }

    // MySQL BINARY(16) -> Java UUID
    private static UUID bytesToUuid(byte[] bytes) {

        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        long most = buffer.getLong();
        long least = buffer.getLong();

        return new UUID(most, least);
    }

    /*
     * deleted_at은 NULL일 수 있다.
     * NULL을 안전하게 LocalDateTime으로 변환한다.
     */
    private static java.time.LocalDateTime getNullableDateTime(
            ResultSet rs,
            String column
    ) throws SQLException {

        Timestamp timestamp = rs.getTimestamp(column);

        return timestamp == null
                ? null
                : timestamp.toLocalDateTime();
    }
}
