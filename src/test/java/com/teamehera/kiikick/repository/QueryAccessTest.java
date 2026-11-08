package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.testsupport.QueryAccessContract;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:query_access;MODE=MySQL;DB_CLOSE_DELAY=-1")
class QueryAccessTest extends QueryAccessContract {}
