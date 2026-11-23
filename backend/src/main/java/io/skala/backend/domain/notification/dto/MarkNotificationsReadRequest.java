package io.skala.backend.domain.notification.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/** NOTI-02. ids(최대 200개)와 all=true 중 하나만 보낸다. */
public record MarkNotificationsReadRequest(@Size(max = 200) List<Long> ids, Boolean all) {
}
