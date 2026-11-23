package io.skala.backend.domain.file.service;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.value.FilePurpose;
import java.io.InputStream;

/**
 * 파일 저장소 포트. 다른 도메인은 이 인터페이스만 호출한다.
 * 저장 위치(로컬 디스크·S3)는 구현체가 결정한다.
 */
public interface FileStorage {

    /**
     * 용도별 규칙(확장자·크기)을 검사한 뒤 저장하고 stored_object 행을 만든다.
     * 오류: UNSUPPORTED_FILE_TYPE(415), FILE_TOO_LARGE(413), INVALID_REQUEST(빈 파일)
     */
    StoredObject save(FilePurpose purpose, String originalFilename, String contentType, InputStream content);

    /**
     * drafts/ 파일을 uploads/로 복사해 영구 보존용 새 stored_object를 만든다. 호출한 쪽이 참조를 새 객체로 바꾼다.
     * 이미 uploads/에 있는 파일이면 그대로 돌려준다.
     */
    StoredObject promote(StoredObject draft);

    /** 저장된 파일 내용을 연다. 호출한 쪽이 스트림을 닫는다. */
    InputStream open(StoredObject object);
}
