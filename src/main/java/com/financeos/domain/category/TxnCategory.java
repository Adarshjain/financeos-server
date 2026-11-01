package com.financeos.domain.category;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One row of the fixed global category list (txn_categories). Read-only; seeded by migration. */
@Entity
@Table(name = "txn_categories")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TxnCategory {

    @Id
    @Column(name = "category_key", length = 40)
    private String key;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private CategoryDirection direction;

    @Column(name = "out_order")
    private Integer outOrder;

    @Column(name = "in_order")
    private Integer inOrder;

    @Column(nullable = false)
    private String icon;

    @Column(name = "excluded_by_default", nullable = false)
    private boolean excludedByDefault;

    public TxnCategory(String key, String name, CategoryDirection direction, Integer outOrder, Integer inOrder,
                       String icon, boolean excludedByDefault) {
        this.key = key;
        this.name = name;
        this.direction = direction;
        this.outOrder = outOrder;
        this.inOrder = inOrder;
        this.icon = icon;
        this.excludedByDefault = excludedByDefault;
    }
}
