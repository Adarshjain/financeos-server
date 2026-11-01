package com.financeos.domain.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One row of the fixed global sub-category list (txn_subcategories). Read-only; seeded by migration. */
@Entity
@Table(name = "txn_subcategories")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TxnSubcategory {

    @Id
    @Column(name = "subcategory_key", length = 80)
    private String key;

    @Column(name = "category_key", nullable = false, length = 40)
    private String categoryKey;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private CategoryDirection direction;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public TxnSubcategory(String key, String categoryKey, String name, CategoryDirection direction, int sortOrder) {
        this.key = key;
        this.categoryKey = categoryKey;
        this.name = name;
        this.direction = direction;
        this.sortOrder = sortOrder;
    }
}
