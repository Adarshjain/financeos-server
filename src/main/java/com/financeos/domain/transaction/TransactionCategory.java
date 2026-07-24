package com.financeos.domain.transaction;

import com.financeos.domain.category.Category;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transaction_categories")
@Getter
@Setter
@NoArgsConstructor
public class TransactionCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    public TransactionCategory(Transaction transaction, Category category) {
        this.transaction = transaction;
        this.category = category;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionCategory that = (TransactionCategory) o;
        if (id != null && that.id != null) {
            return id.equals(that.id);
        }
        return java.util.Objects.equals(
                transaction != null ? transaction.getId() : null,
                that.transaction != null ? that.transaction.getId() : null
        ) && java.util.Objects.equals(
                category != null ? category.getId() : null,
                that.category != null ? that.category.getId() : null
        );
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                transaction != null ? transaction.getId() : null,
                category != null ? category.getId() : null
        );
    }
}
