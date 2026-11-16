package io.skala.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	/** Spring 애플리케이션을 시작한다.
	 * Args: args 실행 설정. Returns: 없음. Raises: 시작 구성 오류.
	 * Side Effects: 서버 시작, 설정된 DB의 Flyway 마이그레이션 및 파서 워커 등록.
	 */
	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}
}
