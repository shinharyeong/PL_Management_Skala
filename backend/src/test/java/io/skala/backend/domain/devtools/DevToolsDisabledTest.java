package io.skala.backend.domain.devtools;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.skala.backend.global.exception.BusinessException;
class DevToolsDisabledTest {
 @Test void disabled_before_any_account_or_session_access(){
  DevToolsService service=new DevToolsService(false,"dev@sk.com",null,null,null);
  assertThatThrownBy(service::accounts).isInstanceOf(BusinessException.class).hasMessage("요청한 자료를 찾을 수 없습니다.");
  assertThatThrownBy(()->service.login("dev@sk.com")).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.requireEditor(1L)).isInstanceOf(BusinessException.class);
 }
}
