-- The code deduplicates statements per account (existsByAccountIdAndFileSha256), but the
-- index was unique per user, so re-uploading a file whose statement row survives under a
-- different account of the same user blew up with ORA-00001. Align the index to the code.
DROP INDEX uk_stmt_user_sha256;
CREATE UNIQUE INDEX uk_stmt_account_sha256
    ON statements (CASE WHEN file_sha256 IS NOT NULL THEN account_id END, file_sha256);
