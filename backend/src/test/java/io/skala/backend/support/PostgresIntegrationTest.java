package io.skala.backend.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 PostgreSQL이 필요한 통합 테스트의 부모. 자식 클래스에 @EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")을
 * 함께 붙여 TEST_DB_URL이 없으면 건너뛰게 한다(조건 어노테이션은 상속되지 않음).
 * 실행 예: TEST_DB_URL=jdbc:postgresql://localhost:5432/skala_test TEST_DB_USERNAME=... ./gradlew test
 * (빈 DB를 주면 Flyway가 V1부터 적용한다)
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        registry.add("app.notification.overdue-scan.cron", () -> "-");
        registry.add("app.storage.local-root", () -> System.getProperty("java.io.tmpdir") + "/skala-test-storage");
    }
}
