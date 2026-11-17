plugins {
	java
	id("org.springframework.boot") version "3.4.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.skala"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.flywaydb:flyway-core")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testRuntimeOnly("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// 현재 808행 조직 원본의 Java·Python 경계 검사에만 실제 런타임 범위의 힙을 허용한다.
	System.getenv("EXCEL_PARSER_LARGE_TEST_FILE")?.takeIf { it.isNotBlank() }?.let {
		maxHeapSize = "896m"
	}
	// 실제 17개 파싱 결과를 전용 H2 메모리에 보존하는 선택 검사에만 힙을 늘린다.
	System.getProperty("business.workbook.path")?.takeIf { it.isNotBlank() }?.let {
		systemProperty("business.workbook.path", it)
		maxHeapSize = "2g"
	}
}
