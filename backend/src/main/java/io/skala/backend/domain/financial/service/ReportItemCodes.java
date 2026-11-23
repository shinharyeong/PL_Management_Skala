package io.skala.backend.domain.financial.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 파서 보고 항목 키(["REVENUE",["매출","제품매출"]])를 report_item.code로 바꾼다.
 * 파서와 같은 압축 JSON으로 만들고, 160자를 넘으면 "h:" + SHA-256으로 줄인다.
 */
final class ReportItemCodes {

    static final int MAX_LENGTH = 160;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportItemCodes() {
    }

    static String of(String kind, List<String> path) {
        try {
            return shorten(JSON.writeValueAsString(List.of(kind, path)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("보고 항목 키를 만들 수 없습니다.", e);
        }
    }

    static String shorten(String raw) {
        if (raw.length() <= MAX_LENGTH) {
            return raw;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "h:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
