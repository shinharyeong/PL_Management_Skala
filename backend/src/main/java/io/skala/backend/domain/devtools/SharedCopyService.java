package io.skala.backend.domain.devtools;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공통 문구는 PostgreSQL에 저장하고 revision 조건부 갱신으로 동시 편집 덮어쓰기를 막는다. */
@Service
@RequiredArgsConstructor
public class SharedCopyService {
    private final JdbcTemplate jdbc;
    public record Item(String key,String value,long revision,OffsetDateTime updatedAt) {}
    private static final org.springframework.jdbc.core.RowMapper<Item> ROW = (r,n)->new Item(r.getString("copy_key"),r.getString("copy_value"),r.getLong("revision"),r.getObject("updated_at",OffsetDateTime.class));
    @Transactional(readOnly=true)
    public List<Item> read() { return jdbc.query("select copy_key,copy_value,revision,updated_at from ui_copy_override order by copy_key",ROW); }
    @Transactional
    public Item change(String key,String value,long expectedRevision,Long userId) {
        String[] parts=key.split("\\.",-1);
        Set<String> forbidden=Set.of("__proto__","constructor","prototype");
        if (parts.length!=2 || !key.matches("[A-Za-z][A-Za-z0-9_-]*\\.[A-Za-z][A-Za-z0-9_-]*")
                || forbidden.contains(parts[0]) || forbidden.contains(parts[1]) || expectedRevision<0 || value.isBlank() || value.length()>10000)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        List<Item> result=expectedRevision==0 ? jdbc.query("""
            insert into ui_copy_override(copy_key,copy_value,revision,updated_by) values (?,?,1,?)
            on conflict(copy_key) do nothing returning copy_key,copy_value,revision,updated_at
            """,ROW,key,value,userId) : jdbc.query("""
            update ui_copy_override set copy_value=?,revision=revision+1,updated_by=?,updated_at=now()
            where copy_key=? and revision=? returning copy_key,copy_value,revision,updated_at
            """,ROW,value,userId,key,expectedRevision);
        if(result.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
        return result.getFirst();
    }
}
