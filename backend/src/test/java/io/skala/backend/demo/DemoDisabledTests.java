package io.skala.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** local만 켠 환경에서 체험 공개 경로와 초기 자료가 생기지 않음을 확인한다. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:demo_disabled;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
    "app.local-auth.planner-email=",
    "app.local-auth.planner-token=",
    "app.local-auth.organization-email=",
    "app.local-auth.organization-token="
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
class DemoDisabledTests {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    /** demo가 없으면 빈 DB를 보존하고 익명 체험 세션을 열지 않는다.
     * Args: 없음. Returns: 없음. Raises: HTTP/DB/검증 오류.
     * Side Effects: 메모리 DB·HTTP 조회만 수행.
     */
    @Test
    void noDemoBeansDataOrAnonymousSessionWithoutDemoProfile() throws Exception {
        assertThat(context.getBeansOfType(DemoBootstrap.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoController.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoAccounts.class)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM organization").query(Long.class).single()).isZero();
        mvc.perform(post("/api/dev/session").header("Origin", "http://localhost:5173").header("Host", "localhost:5173")
                .contentType("application/json").content("{\"role\":\"PLANNER\"}"))
                .andExpect(status().isUnauthorized());
    }
}
