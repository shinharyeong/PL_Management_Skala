package io.skala.backend.domain.review.dto;

import io.skala.backend.domain.review.entity.ReviewAttachment;
import java.time.OffsetDateTime;

/** openapi Attachment */
public record AttachmentResponse(String id, String name, long size, String contentType, OffsetDateTime uploadedAt,
                                 String url) {

    public static AttachmentResponse of(Long reviewItemId, ReviewAttachment a) {
        return new AttachmentResponse(String.valueOf(a.getId()), a.getOriginalFilename(), a.getByteSize(),
                a.getContentType(), a.getUploadedAt(),
                "/api/v1/review-items/" + reviewItemId + "/attachments/" + a.getId() + "/file");
    }
}
