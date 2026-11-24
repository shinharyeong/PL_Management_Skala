package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.submission.exception.SubmissionErrorCode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 업로드 파일 이름 규칙(FR-UPLOAD-004). 월간 {조직코드}_{YYYY-MM}.xlsx, 연간 {조직코드}_{YYYY}-PLAN.xlsx
 * (예: D100_2026-11.xlsx, D100_2027-PLAN.xlsx). 실제 배포 양식이 Excel 바이너리 통합 문서라 .xlsb도 받는다(#57).
 * 형식·기간이 다르면 INVALID_FILENAME, 조직코드가 다르면 ORGANIZATION_MISMATCH.
 */
public final class SubmissionFileNames {

    private static final Pattern MONTHLY = Pattern.compile("^([A-Za-z0-9]{1,80})_(\\d{4})-(\\d{2})\\.(xlsx|xlsb)$");
    private static final Pattern ANNUAL = Pattern.compile("^([A-Za-z0-9]{1,80})_(\\d{4})-PLAN\\.(xlsx|xlsb)$");
    static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String XLSB_CONTENT_TYPE = "application/vnd.ms-excel.sheet.binary.macroEnabled.12";

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

    /** 규칙을 통과한 파일 이름의 확장자(xlsx 또는 xlsb, 소문자). */
    public static String extension(String filename) {
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    /** 브라우저가 형식을 보내지 않았을 때 쓸 저장 형식 */
    public static String defaultContentType(String filename) {
        return "xlsb".equals(extension(filename)) ? XLSB_CONTENT_TYPE : XLSX_CONTENT_TYPE;
    }

    /** xlsx·xlsb 모두 ZIP 패키지다. 확장자만 바꾼 파일을 막는다(NFR-SEC-005). 내부 형식은 파서가 확인한다. */
    public static boolean looksLikeWorkbook(byte[] content) {
        return content.length >= 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4;
    }
}
