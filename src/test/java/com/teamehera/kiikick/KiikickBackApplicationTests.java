package com.teamehera.kiikick;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class KiikickBackApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
    }

    @Test
    void usesIsolatedH2Database() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            String jdbcUrl = connection.getMetaData().getURL();

            assertTrue(
                    jdbcUrl.startsWith("jdbc:h2:mem:"),
                    () -> "Tests must use an isolated in-memory H2 database, but connected to: " + jdbcUrl
            );
        }
    }

}
