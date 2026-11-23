package io.skala.backend.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.util.StateToken;
import org.junit.jupiter.api.Test;

class StateTokenTest {

    @Test
    void sameValuesGiveSameToken() {
        assertThat(StateToken.of(1L, "PENDING", 2)).isEqualTo(StateToken.of(1L, "PENDING", 2)).startsWith("st_");
    }

    @Test
    void changedValueFailsVerification() {
        String token = StateToken.of(1L, "PENDING");
        assertThatThrownBy(() -> StateToken.verify(token, StateToken.of(1L, "APPROVED")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("RESOURCE_CHANGED");
    }

    @Test
    void nullOrMissingTokenFails() {
        assertThatThrownBy(() -> StateToken.verify(null, StateToken.of(1L))).isInstanceOf(BusinessException.class);
    }
}
