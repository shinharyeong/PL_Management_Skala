package io.skala.backend.domain.file.service;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.repository.StoredObjectRepository;
import io.skala.backend.domain.file.value.FilePurpose;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 로컬 디스크 저장소. 인프라 단계에서 같은 인터페이스의 S3 구현으로 바꾼다.
 * 저장 위치: app.storage.local-root(기본 ./local-storage) 아래 object_key 경로.
 */
@Service
public class LocalFileStorage implements FileStorage {

    private final StoredObjectRepository repository;
    private final Path root;

    public LocalFileStorage(StoredObjectRepository repository,
                            @Value("${app.storage.local-root:./local-storage}") String root) {
        this.repository = repository;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    @Transactional
    public StoredObject save(FilePurpose purpose, String originalFilename, String contentType, InputStream content) {
        String extension = extension(originalFilename);
        if (!purpose.getAllowedExtensions().isEmpty() && !purpose.getAllowedExtensions().contains(extension)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        String key = purpose.getPrefix() + YearMonth.now(ZoneOffset.UTC).toString().replace('-', '/') + "/"
                + UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
            MessageDigest digest = sha256();
            long size;
            try (InputStream in = new DigestInputStream(content, digest)) {
                size = Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (size == 0) {
                Files.deleteIfExists(temp);
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "빈 파일은 올릴 수 없습니다.");
            }
            if (size > purpose.getMaxBytes()) {
                Files.deleteIfExists(temp);
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
            return repository.save(StoredObject.create(key, type, size, HexFormat.of().formatHex(digest.digest())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    @Transactional
    public StoredObject promote(StoredObject draft) {
        if (!draft.getObjectKey().startsWith("drafts/")) {
            return draft;
        }
        String key = "uploads/" + draft.getObjectKey().substring("drafts/".length());
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.copy(resolve(draft.getObjectKey()), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return repository.findById(key).orElseGet(() -> repository.save(
                StoredObject.create(key, draft.getContentType(), draft.getSizeBytes(), draft.getSha256())));
    }

    @Override
    public InputStream open(StoredObject object) {
        try {
            return Files.newInputStream(resolve(object.getObjectKey()));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return path;
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
