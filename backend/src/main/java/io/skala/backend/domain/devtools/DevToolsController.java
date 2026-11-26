package io.skala.backend.domain.devtools;

import io.skala.backend.domain.user.dto.LoginResponse;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class DevToolsController {
    private final DevToolsService tools;
    private final SharedCopyService copy;
    private final CurrentUser currentUser;
    public record Login(@NotBlank @Email String email) {}
    public record Change(@NotBlank @Size(max=200) String key,@NotBlank @Size(max=10000) String value,@PositiveOrZero long expectedRevision) {}
    @GetMapping("/api/v1/dev/accounts")
    public ResponseEntity<List<DevToolsService.Account>> accounts() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tools.accounts()); }
    @PostMapping("/api/v1/dev/sessions")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody Login input) { return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(tools.login(input.email())); }
    @GetMapping("/api/v1/ui-copy")
    public ResponseEntity<List<SharedCopyService.Item>> read() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(copy.read()); }
    @PutMapping("/api/v1/dev/ui-copy")
    public SharedCopyService.Item change(@Valid @RequestBody Change input) {
        Long id=currentUser.get().id(); tools.requireEditor(id);
        return copy.change(input.key(),input.value(),input.expectedRevision(),id);
    }
}
