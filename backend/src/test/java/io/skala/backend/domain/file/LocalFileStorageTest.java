package io.skala.backend.domain.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.file.value.FilePurpose;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.support.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class LocalFileStorageTest extends PostgresIntegrationTest {

    @Autowired
    FileStorage storage;

    @Test
    void savesDraftAndPromotesToUploads() throws Exception {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        StoredObject draft = storage.save(FilePurpose.SUBMISSION_EXCEL, "D100_2026-11.xlsx", null, new ByteArrayInputStream(bytes));
        assertThat(draft.getObjectKey()).startsWith("drafts/excel/").endsWith(".xlsx");
        assertThat(draft.getSizeBytes()).isEqualTo(5L);
        assertThat(draft.getSha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");

        StoredObject kept = storage.promote(draft);
        assertThat(kept.getObjectKey()).isEqualTo(draft.getObjectKey().replaceFirst("^drafts/", "uploads/"));
        try (InputStream in = storage.open(kept)) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void rejectsWrongExtensionAndEmptyFile() {
        assertThatThrownBy(() -> storage.save(FilePurpose.SUBMISSION_EXCEL, "a.xls", null, new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThatThrownBy(() -> storage.save(FilePurpose.SUBMISSION_EXCEL, "a.xlsx", null, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void savesResultJsonUnderResultsAndKeepsItOnPromote() throws Exception {
        byte[] json = "{\"diagnostics\":[]}".getBytes(StandardCharsets.UTF_8);
        StoredObject parser = storage.save(FilePurpose.PARSER_RESULT, "parser-result.json", "application/json", new ByteArrayInputStream(json));
        StoredObject validation = storage.save(FilePurpose.VALIDATION_RESULT, "validation.json", "application/json", new ByteArrayInputStream(json));
        assertThat(parser.getObjectKey()).startsWith("results/parser/").endsWith(".json");
        assertThat(validation.getObjectKey()).startsWith("results/validation/").endsWith(".json");
        assertThat(parser.getContentType()).isEqualTo("application/json");
        assertThat(storage.promote(validation)).isSameAs(validation);
        try (InputStream in = storage.open(validation)) {
            assertThat(in.readAllBytes()).isEqualTo(json);
        }
        assertThatThrownBy(() -> storage.save(FilePurpose.VALIDATION_RESULT, "result.txt", "text/plain", new ByteArrayInputStream(json)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("UNSUPPORTED_FILE_TYPE");
    }
}
