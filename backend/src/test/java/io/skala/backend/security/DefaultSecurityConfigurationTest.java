package io.skala.backend.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** local이 아닌 환경에서 임의 인증과 역할로 API를 열 수 없는지 확인한다. */
@WebMvcTest(controllers = DefaultSecurityConfigurationTest.ProbeController.class)
@Import({SecurityConfiguration.class, SecurityProblemHandler.class,
        DefaultSecurityConfigurationTest.ProbeController.class})
@ActiveProfiles("test")
class DefaultSecurityConfigurationTest {

    @Autowired
    private MockMvc mvc;

    /**
     * 기본 보안 경계가 상태 확인만 익명으로 허용하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 메모리 HTTP 처리 실패는 Exception, 결과 불일치는 AssertionError.
     * Side Effects: 테스트 서버 소켓 없이 MVC 요청을 처리한다.
     */
    @Test
    void permitsOnlyHealthWithoutLocalProfile() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/security-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/security-probe").header("Authorization", "Bearer " + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 인증 객체에 기획 역할이 있어도 기본 거부 규칙을 우회하지 못하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 메모리 HTTP 처리 실패는 Exception, 우회가 허용되면 AssertionError.
     * Side Effects: 해당 요청에만 테스트 인증 문맥을 설정한다.
     */
    @Test
    void deniesEvenAnAuthenticatedPlannerOutsideLocalProfile() throws Exception {
        mvc.perform(get("/security-probe").with(user("planner@example.test").roles("PLANNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 실제 업무 데이터를 사용하지 않는 보안 경계 확인용 컨트롤러다. */
    @RestController
    static class ProbeController {

        /**
         * 허용 경로와 보호 경로에 동일한 메모리 응답을 제공한다.
         *
         * <p>Args: 없음.
         * Returns: 고정된 테스트 상태 맵.
         * Raises: 없음.
         * Side Effects: 없음.
         */
        @GetMapping({"/actuator/health", "/security-probe"})
        Map<String, String> probe() {
            return Map.of("status", "UP");
        }
    }
}
