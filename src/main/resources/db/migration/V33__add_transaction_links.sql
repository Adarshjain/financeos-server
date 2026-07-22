CREATE TABLE transaction_links (
    id           VARCHAR2(36) PRIMARY KEY,
    user_id      VARCHAR2(36) NOT NULL,
    type         VARCHAR2(20) NOT NULL,
    note         VARCHAR2(500),
    created_by   VARCHAR2(20) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE,
    updated_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_txn_links_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE transaction_link_members (
    link_id         VARCHAR2(36) NOT NULL,
    transaction_id  VARCHAR2(36) NOT NULL,
    is_anchor       NUMBER(1) DEFAULT 0 NOT NULL,
    PRIMARY KEY (link_id, transaction_id),
    CONSTRAINT fk_tlm_link FOREIGN KEY (link_id) REFERENCES transaction_links(id) ON DELETE CASCADE,
    CONSTRAINT fk_tlm_txn  FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE
);

CREATE INDEX idx_tlm_transaction ON transaction_link_members(transaction_id);
CREATE INDEX idx_txn_links_user  ON transaction_links(user_id);
