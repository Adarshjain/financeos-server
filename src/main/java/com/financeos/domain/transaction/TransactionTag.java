package com.financeos.domain.transaction;

import com.financeos.domain.category.Tag;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transaction_tags")
@Getter
@Setter
@NoArgsConstructor
public class TransactionTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    public TransactionTag(Transaction transaction, Tag tag) {
        this.transaction = transaction;
        this.tag = tag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionTag that = (TransactionTag) o;
        if (id != null && that.id != null) {
            return id.equals(that.id);
        }
        return java.util.Objects.equals(
                transaction != null ? transaction.getId() : null,
                that.transaction != null ? that.transaction.getId() : null
        ) && java.util.Objects.equals(
                tag != null ? tag.getId() : null,
                that.tag != null ? that.tag.getId() : null
        );
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                transaction != null ? transaction.getId() : null,
                tag != null ? tag.getId() : null
        );
    }
}
