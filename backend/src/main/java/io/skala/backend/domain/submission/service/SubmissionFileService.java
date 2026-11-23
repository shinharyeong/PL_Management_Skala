package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.global.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** REQ-07 제출 파일 다운로드: 권한 확인 후 원본 파일 위치를 돌려준다. */
@Service
@RequiredArgsConstructor
public class SubmissionFileService {

    private final SubmissionAccess submissionAccess;

    public record FileInfo(String filename, StoredObject object) {
    }

    @Transactional(readOnly = true)
    public FileInfo find(Long submissionId, AuthenticatedUser user) {
        Submission submission = submissionAccess.loadForRead(submissionId, user);
        StoredObject object = submission.getSourceObject();
        // 지연 로딩 객체를 트랜잭션 안에서 초기화해 둔다.
        object.getContentType();
        return new FileInfo(submission.getOriginalFilename(), object);
    }
}
