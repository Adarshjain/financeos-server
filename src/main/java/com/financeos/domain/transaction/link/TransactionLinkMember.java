package com.financeos.domain.transaction.link;

import com.financeos.domain.transaction.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transaction_link_members")
@Getter
@Setter
@NoArgsConstructor
public class TransactionLinkMember {

    @EmbeddedId
    private TransactionLinkMemberId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("linkId")
    @JoinColumn(name = "link_id")
    private TransactionLink link;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("transactionId")
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @Column(name = "is_anchor", nullable = false)
    private boolean anchor;

    public TransactionLinkMember(TransactionLink link, Transaction transaction, boolean anchor) {
        this.link = link;
        this.transaction = transaction;
        this.anchor = anchor;
        this.id = new TransactionLinkMemberId(
            link != null ? link.getId() : null,
            transaction != null ? transaction.getId() : null
        );
    }
}
