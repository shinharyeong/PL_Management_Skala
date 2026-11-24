package io.skala.backend.domain.request.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubmissionTemplateServiceTest {

    @Test
    void contentTypeFollowsFilenameExtension() {
        assertThat(SubmissionTemplateService.contentType("제출양식.xlsb"))
                .isEqualTo("application/vnd.ms-excel.sheet.binary.macroEnabled.12");
        assertThat(SubmissionTemplateService.contentType("제출양식.XLSB"))
                .isEqualTo("application/vnd.ms-excel.sheet.binary.macroEnabled.12");
        assertThat(SubmissionTemplateService.contentType("제출양식.xlsx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
}
