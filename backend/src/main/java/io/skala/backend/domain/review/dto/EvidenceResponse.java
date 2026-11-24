package io.skala.backend.domain.review.dto;

import java.util.List;

/** GAP-09 답안지 항목을 이루는 원천 셀(시트·셀 오름차순) */
public record EvidenceResponse(List<Item> items) {

    /** account는 계정 코드를 확인하지 못한 행이면 null. description은 거래내역 및 산출근거. */
    public record Item(String sourceSheet, String sourceCell, Account account, String description, String amount) {
    }

    public record Account(String code, String name) {
    }
}
