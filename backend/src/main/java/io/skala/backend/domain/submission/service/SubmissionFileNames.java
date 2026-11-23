package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.submission.exception.SubmissionErrorCode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 업로드 파일 이름 규칙(FR-UPLOAD-004). 월간 {조직코드}_{YYYY-MM}.xlsx, 연간 {조직코드}_{YYYY}-PLAN.xlsx
 * (예: D100_2026-11.xlsx, D100_2027-PLAN.xlsx). 형식·기간이 다르면 INVALID_FILENAME, 조직코드가 다르면 ORGANIZATION_MISMATCH.
 */
public final class SubmissionFileNames {

    private static final Pattern MONTHLY = Pattern.compile("^([A-Za-z0-9]{1,80})_(\\d{4})-(\\d{2})\\.xlsx$");
    private static final Pattern ANNUAL = Pattern.compile("^([A-Za-z0-9]{1,80})_(\\d{4})-PLAN\\.xlsx$");

    private SubmissionFileNames() {
    }

    public static void check(String filename, SubmissionRequest request, String organizationCode) {
        if (filename == null || filename.length() > 255) {
            throw SubmissionErrorCode.INVALID_FILENAME.exception();
        }
        int year = request.getAnnualPlan().getYear();
        String fileOrganization;
        if (request.getType() == RequestType.ANNUAL_PLAN) {
            Matcher m = ANNUAL.matcher(filename);
            if (!m.matches() || Integer.parseInt(m.group(2)) != year) {
                throw SubmissionErrorCode.INVALID_FILENAME.exception();
            }
            fileOrganization = m.group(1);
        } else {
            Matcher m = MONTHLY.matcher(filename);
            if (!m.matches() || Integer.parseInt(m.group(2)) != year
                    || Integer.parseInt(m.group(3)) != request.getMonth()) {
                throw SubmissionErrorCode.INVALID_FILENAME.exception();
            }
            fileOrganization = m.group(1);
        }
        if (!fileOrganization.equals(organizationCode)) {
            throw SubmissionErrorCode.ORGANIZATION_MISMATCH.exception();
        }
    }

    /** xlsx는 ZIP 패키지다. 확장자만 바꾼 파일을 막는다(NFR-SEC-005). */
    public static boolean looksLikeXlsx(byte[] content) {
        return content.length >= 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4;
    }
}
