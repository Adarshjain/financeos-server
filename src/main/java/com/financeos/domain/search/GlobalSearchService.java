package com.financeos.domain.search;

import com.financeos.api.search.dto.GlobalSearchResponse;
import com.financeos.api.search.dto.SearchAccountHit;
import com.financeos.api.search.dto.SearchDashboardHit;
import com.financeos.api.search.dto.SearchHoldingHit;
import com.financeos.api.search.dto.SearchLoanHit;
import com.financeos.api.search.dto.SearchPersonHit;
import com.financeos.api.search.dto.SearchReportHit;
import com.financeos.api.search.dto.SearchTransactionHit;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.transaction.TransactionListQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Global search: seven small capped queries (5 rows each, no counts), run in order inside one read-only
 * transaction. Every query is scoped by {@code user_id}; small groups list prefix matches first, then
 * alphabetically; transactions list newest first (served by idx_txn_user_date).
 */
@Service
@Transactional(readOnly = true)
public class GlobalSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearchService.class);

    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 100;

    private static final String LIKE = " ESCAPE '\\'";
    private static final String PREFIX_ORDER =
            " ORDER BY CASE WHEN LOWER(%1$s) LIKE :prefix" + LIKE + " THEN 0 ELSE 1 END, LOWER(%1$s), %2$s"
                    + " FETCH FIRST 5 ROWS ONLY";

    private static final String ACCOUNTS_SQL = """
            SELECT a.id, a.name, a.bank, a.type,
                   COALESCE(
                       (SELECT MIN(c.last4) FROM cards c
                         WHERE c.account_id = a.id AND c.user_id = :userId AND LOWER(c.last4) LIKE :q""" + LIKE + """
            ),
                       (SELECT c.last4 FROM cards c WHERE c.account_id = a.id AND c.user_id = :userId
                         ORDER BY c.created_at, c.id FETCH FIRST 1 ROW ONLY)) AS last4
              FROM accounts a
             WHERE a.user_id = :userId
               AND (LOWER(a.name) LIKE :q""" + LIKE + " OR LOWER(a.bank) LIKE :q" + LIKE + """

                    OR EXISTS (SELECT 1 FROM cards c
                                WHERE c.account_id = a.id AND c.user_id = :userId AND LOWER(c.last4) LIKE :q""" + LIKE + "))"
            + PREFIX_ORDER.formatted("a.name", "a.id");

    private static final String HOLDINGS_SQL = """
            SELECT i.id, COALESCE(o.name, i.name) AS name, COALESCE(o.symbol, i.symbol) AS symbol
              FROM instruments i
              LEFT JOIN user_instrument_overrides o ON o.instrument_id = i.id AND o.user_id = :userId
             WHERE EXISTS (SELECT 1 FROM holdings h WHERE h.user_id = :userId AND h.instrument_id = i.id)
               AND (LOWER(COALESCE(o.name, i.name)) LIKE :q""" + LIKE
            + " OR LOWER(COALESCE(o.symbol, i.symbol)) LIKE :q" + LIKE + ")"
            + PREFIX_ORDER.formatted("COALESCE(o.name, i.name)", "i.id");

    private static final String PEOPLE_SQL = "SELECT id, name FROM counterparties WHERE user_id = :userId"
            + " AND LOWER(name) LIKE :q" + LIKE + PREFIX_ORDER.formatted("name", "id");

    private static final String LOANS_SQL = "SELECT id, name, lender FROM loans WHERE user_id = :userId"
            + " AND (LOWER(name) LIKE :q" + LIKE + " OR LOWER(lender) LIKE :q" + LIKE
            + " OR LOWER(loan_account_number) LIKE :q" + LIKE + ")" + PREFIX_ORDER.formatted("name", "id");

    private static final String REPORTS_SQL = "SELECT id, name, type FROM reports WHERE user_id = :userId"
            + " AND LOWER(name) LIKE :q" + LIKE + PREFIX_ORDER.formatted("name", "id");

    private static final String DASHBOARDS_SQL = "SELECT id, name, is_default FROM dashboards WHERE user_id = :userId"
            + " AND LOWER(name) LIKE :q" + LIKE + PREFIX_ORDER.formatted("name", "id");

    private final NamedParameterJdbcTemplate jdbc;

    public GlobalSearchService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GlobalSearchResponse search(String rawQuery) {
        String text = rawQuery == null ? "" : rawQuery.trim();
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
            throw new ValidationException(
                    "Search text must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters.");
        }
        long started = System.nanoTime();
        String lowered = text.toLowerCase(Locale.ROOT);
        String escaped = SqlPredicates.escapeLike(lowered);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", UserContext.getCurrentUserId().toString())
                .addValue("q", "%" + escaped + "%")
                .addValue("prefix", escaped + "%");

        List<SearchAccountHit> accounts = jdbc.query(ACCOUNTS_SQL, params, (rs, n) -> new SearchAccountHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("bank"),
                AccountType.valueOf(rs.getString("type")), rs.getString("last4")));

        List<SearchTransactionHit> transactions = jdbc.query(transactionsSql(text, params), params,
                (rs, n) -> new SearchTransactionHit(UUID.fromString(rs.getString("id")),
                        rs.getDate("transaction_date").toLocalDate(), rs.getString("description"),
                        UUID.fromString(rs.getString("account_id")), rs.getString("account_name"),
                        rs.getBigDecimal("signed_amount")));

        List<SearchHoldingHit> holdings = jdbc.query(HOLDINGS_SQL, params, (rs, n) -> new SearchHoldingHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("symbol")));

        List<SearchPersonHit> people = jdbc.query(PEOPLE_SQL, params,
                (rs, n) -> new SearchPersonHit(UUID.fromString(rs.getString("id")), rs.getString("name")));

        List<SearchLoanHit> loans = jdbc.query(LOANS_SQL, params, (rs, n) -> new SearchLoanHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("lender")));

        List<SearchReportHit> reports = jdbc.query(REPORTS_SQL, params, (rs, n) -> new SearchReportHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), ReportType.valueOf(rs.getString("type"))));

        List<SearchDashboardHit> dashboards = jdbc.query(DASHBOARDS_SQL, params, (rs, n) -> new SearchDashboardHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getInt("is_default") != 0));

        log.debug("Global search: accounts={} transactions={} holdings={} people={} loans={} reports={} dashboards={} in {} ms",
                accounts.size(), transactions.size(), holdings.size(), people.size(), loans.size(), reports.size(),
                dashboards.size(), (System.nanoTime() - started) / 1_000_000);
        return new GlobalSearchResponse(accounts, transactions, holdings, people, loans, reports, dashboards);
    }

    /** Newest first; a numeric query also matches the amount's digits, as the transactions list search does. */
    private static String transactionsSql(String text, MapSqlParameterSource params) {
        String amountClause = "";
        String amountDigits = TransactionListQueryBuilder.numericSearchText(text);
        if (amountDigits != null) {
            params.addValue("qAmt", "%" + amountDigits + "%");
            amountClause = " OR TO_CHAR(ABS(t.amount), '" + TransactionListQueryBuilder.AMOUNT_TEXT_FORMAT
                    + "') LIKE :qAmt";
        }
        return "SELECT t.id, t.transaction_date, COALESCE(t.description, t.sourced_description) AS description,"
                + " t.account_id, a.name AS account_name,"
                + " (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS signed_amount"
                + " FROM transactions t JOIN accounts a ON a.id = t.account_id AND a.user_id = :userId"
                + " WHERE t.user_id = :userId AND (LOWER(t.description) LIKE :q" + LIKE
                + " OR LOWER(t.sourced_description) LIKE :q" + LIKE + amountClause + ")"
                + " ORDER BY t.transaction_date DESC, t.id DESC FETCH FIRST 5 ROWS ONLY";
    }
}
