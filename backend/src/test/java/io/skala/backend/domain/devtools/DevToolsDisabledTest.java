package io.skala.backend.domain.devtools;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.skala.backend.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

class DevToolsDisabledTest {

    @Test
    void disabled_before_any_account_or_session_access() {
        DevToolsService service = new DevToolsService(false, true, "key", null, null, null);
        assertThatThrownBy(() -> service.accounts("key")).isInstanceOf(BusinessException.class).hasMessage("요청한 자료를 찾을 수 없습니다.");
        assertThatThrownBy(() -> service.login("dev@sk.com", "key")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireEditor(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void enabled_without_configured_key_rejects_everyone() {
        DevToolsService service = new DevToolsService(true, true, "", null, null, null);
        assertThatThrownBy(() -> service.accounts(null)).isInstanceOf(BusinessException.class).hasMessage("현재 권한으로 접근할 수 없습니다.");
        assertThatThrownBy(() -> service.accounts("")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.login("dev@sk.com", "anything")).isInstanceOf(BusinessException.class);
    }

    @Test
    void enabled_with_key_rejects_missing_or_wrong_key_before_touching_users() {
        DevToolsService service = new DevToolsService(true, true, "team-key", null, null, null);
        assertThatThrownBy(() -> service.accounts(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.accounts("wrong")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.login("dev@sk.com", "wrong")).isInstanceOf(BusinessException.class);
    }
}
