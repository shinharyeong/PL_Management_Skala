package io.skala.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

	/** 기본 보안·DB 마이그레이션 구성이 시작되는지 확인한다.
	 * Args: 없음. Returns: 없음. Raises: 시작 오류. Side Effects: 메모리 H2만 초기화.
	 */
	@Test
	void contextLoads() {
	}
}
