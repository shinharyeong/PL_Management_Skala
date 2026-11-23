package io.skala.backend.domain.financial.entity;

import io.skala.backend.domain.financial.value.AccountSourceType;
import io.skala.backend.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "account_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountItem extends BaseCreatedTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private AccountSourceType sourceType;

    @Column(name = "source_code", nullable = false, length = 160)
    private String sourceCode;

    @Column(name = "source_name", nullable = false, length = 500)
    private String sourceName;

    public static AccountItem create(AccountSourceType sourceType, String sourceCode, String sourceName) {
        AccountItem item = new AccountItem();
        item.sourceType = sourceType;
        item.sourceCode = sourceCode;
        item.sourceName = sourceName;
        return item;
    }
}
