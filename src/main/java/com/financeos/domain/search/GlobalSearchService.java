package com.financeos.domain.search;

import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
import com.financeos.api.search.dto.GlobalSearchResponse;
import com.financeos.api.search.dto.SearchAccountHit;
import com.financeos.api.search.dto.SearchCategoryHit;
import com.financeos.api.search.dto.SearchRuleHit;
import com.financeos.api.search.dto.SearchDashboardHit;
import com.financeos.api.search.dto.SearchHoldingHit;
import com.financeos.api.search.dto.SearchLoanHit;
import com.financeos.api.search.dto.SearchOpenBill;
import com.financeos.api.search.dto.SearchPersonHit;
import com.financeos.api.search.dto.SearchReportHit;
import com.financeos.api.search.dto.SearchTransactionHit;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.transaction.TransactionListQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * Global search: nine small capped queries (5 rows each, no counts), run in order inside one read-only
 * transaction. Every query is scoped by {@code user_id}; small groups list prefix matches first, then
 * alphabetically; transactions list newest first (served by idx_txn_user_date).
 */
@Service
@Transactional(readOnly = true)
public class GlobalSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearchService.class);

    static final int MIN_LENGTH = 2;
    /** Transactions and holdings are only searched from this length (too noisy at 2 characters). */
    static final int HEAVY_MIN_LENGTH = 3;
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

    /** Fixed taxonomy: categories and sub-categories in one query, prefix matches first. */
    private static final String CATEGORIES_SQL = """
            SELECT item_key, name, parent_name FROM (
                SELECT c.category_key AS item_key, c.name AS name, CAST(NULL AS VARCHAR2(100)) AS parent_name
                  FROM txn_categories c
                UNION ALL
                SELECT s.subcategory_key, s.name, p.name
                  FROM txn_subcategories s JOIN txn_categories p ON p.category_key = s.category_key
            ) WHERE LOWER(name) LIKE :q""" + LIKE
            + PREFIX_ORDER.formatted("name", "item_key");

    private static final String RULES_SQL = """
            SELECT r.id, r.merchant_key, c.name AS category_name
              FROM category_rules r JOIN txn_categories c ON c.category_key = r.category_key
             WHERE r.user_id = :userId
               AND (LOWER(r.merchant_key) LIKE :q""" + LIKE + " OR LOWER(r.display_name) LIKE :q" + LIKE + ")"
            + PREFIX_ORDER.formatted("r.merchant_key", "r.id");

    private final NamedParameterJdbcTemplate jdbc;

    private final CardBillService cardBillService;

    public GlobalSearchService(NamedParameterJdbcTemplate jdbc, CardBillService cardBillService) {
        this.jdbc = jdbc;
        this.cardBillService = cardBillService;
    }

    public GlobalSearchResponse search(String rawQuery) {
        String text = rawQuery == null ? "" : rawQuery.trim();
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
            throw new ValidationException(
                    "Search text must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters.");
        }
        return Timing.call(Ops.SEARCH_QUERY, () -> runSearch(text));
    }

    private GlobalSearchResponse runSearch(String text) {
        long started = System.nanoTime();
        String lowered = text.toLowerCase(Locale.ROOT);
        String escaped = SqlPredicates.escapeLike(lowered);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", UserContext.getCurrentUserId().toString())
                .addValue("q", "%" + escaped + "%")
                .addValue("prefix", escaped + "%");

        List<SearchAccountHit> accounts = withOpenBills(jdbc.query(ACCOUNTS_SQL, params, (rs, n) -> new SearchAccountHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("bank"),
                AccountType.valueOf(rs.getString("type")), rs.getString("last4"), null)));

        boolean heavy = text.length() >= HEAVY_MIN_LENGTH;
        List<SearchTransactionHit> transactions = !heavy ? List.<SearchTransactionHit>of()
                : jdbc.query(transactionsSql(text, params), params,
                (rs, n) -> new SearchTransactionHit(UUID.fromString(rs.getString("id")),
                        rs.getDate("transaction_date").toLocalDate(), rs.getString("description"),
                        UUID.fromString(rs.getString("account_id")), rs.getString("account_name"),
                        rs.getBigDecimal("signed_amount")));

        List<SearchHoldingHit> holdings = !heavy ? List.<SearchHoldingHit>of()
                : jdbc.query(HOLDINGS_SQL, params, (rs, n) -> new SearchHoldingHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("symbol")));

        List<SearchPersonHit> people = jdbc.query(PEOPLE_SQL, params,
                (rs, n) -> new SearchPersonHit(UUID.fromString(rs.getString("id")), rs.getString("name")));

        List<SearchLoanHit> loans = jdbc.query(LOANS_SQL, params, (rs, n) -> new SearchLoanHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("lender")));

        List<SearchReportHit> reports = jdbc.query(REPORTS_SQL, params, (rs, n) -> new SearchReportHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), ReportType.valueOf(rs.getString("type"))));

        List<SearchDashboardHit> dashboards = jdbc.query(DASHBOARDS_SQL, params, (rs, n) -> new SearchDashboardHit(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getInt("is_default") != 0));

        List<SearchCategoryHit> categories = jdbc.query(CATEGORIES_SQL, params, (rs, n) -> new SearchCategoryHit(
                rs.getString("item_key"), rs.getString("name"), rs.getString("parent_name")));

        List<SearchRuleHit> rules = jdbc.query(RULES_SQL, params, (rs, n) -> new SearchRuleHit(
                UUID.fromString(rs.getString("id")), rs.getString("merchant_key"), rs.getString("category_name")));

        log.debug("Global search: accounts={} transactions={} holdings={} people={} loans={} reports={} dashboards={}"
                        + " categories={} rules={} in {} ms",
                accounts.size(), transactions.size(), holdings.size(), people.size(), loans.size(), reports.size(),
                dashboards.size(), categories.size(), rules.size(), (System.nanoTime() - started) / 1_000_000);
        return new GlobalSearchResponse(accounts, transactions, holdings, people, loans, reports, dashboards,
                categories, rules);
    }

    /**
     * Attaches each credit card's unpaid bill. The bills come from the same batched read the Inbox and
     * Upcoming use ({@link CardBillService#listBills}: one live bill per open card), and "unpaid" is
     * the status the app offers Mark paid for (OPEN, PARTIAL, OVERDUE), so the figures cannot drift.
     * Skipped entirely when no hit is a credit card.
     */
    private List<SearchAccountHit> withOpenBills(List<SearchAccountHit> hits) {
        if (hits.stream().noneMatch(h -> h.type() == AccountType.credit_card)) {
            return hits;
        }
        Map<UUID, SearchOpenBill> byCard = new HashMap<>();
        for (CardBill bill : cardBillService.listBills(UserContext.getCurrentUserId())) {
            boolean unpaid = bill.status() == BillStatus.OPEN || bill.status() == BillStatus.PARTIAL
                    || bill.status() == BillStatus.OVERDUE;
            if (unpaid && bill.statementId() != null && bill.remainingAmount() != null) {
                byCard.put(bill.accountId(),
                        new SearchOpenBill(bill.statementId(), bill.remainingAmount(), bill.paymentDueDate()));
            }
        }
        return hits.stream().map(h -> h.type() == AccountType.credit_card
                ? new SearchAccountHit(h.id(), h.name(), h.bank(), h.type(), h.last4(), byCard.get(h.id()))
                : h).toList();
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
