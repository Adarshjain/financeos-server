package db.migration;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.corporateaction.SharedCorporateActionSplit;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * V100: corporate actions become per user.
 * <ol>
 *   <li>{@code corporate_actions.user_id VARCHAR2(36)} is added.</li>
 *   <li>Every shared row (no owner) is copied, with a new id, to each user it affects, and the shared
 *       row is deleted; a row that affects nobody is just deleted
 *       ({@link SharedCorporateActionSplit} has the rule and the counts). No other table stores a
 *       corporate-action id, so nothing else is remapped.</li>
 *   <li>{@code user_id} becomes NOT NULL with an FK to users ON DELETE CASCADE (account deletion relies
 *       on it), plus the indexes the per-owner reads use: (user_id, instrument_id, ex_date) and
 *       (user_id, target_instrument_id).</li>
 * </ol>
 * A Java migration so the data step is the same code the tests run against H2.
 *
 * <p>Idempotent: ADD swallows ORA-01430 (column exists), MODIFY ... NOT NULL swallows ORA-01442
 * (already NOT NULL), ADD CONSTRAINT swallows ORA-02264 / ORA-02275 (name / constraint exists),
 * CREATE INDEX swallows ORA-00955 / ORA-01408 (name / column list already indexed); the data step only
 * reads shared rows and derives copy ids from (shared id, user id), so it is simply re-run for
 * owner-less rows an old server inserts while this runs (the NOT NULL step is retried until none is
 * left; ORA-02296 / ORA-00054 trigger the retry). Holdings without an owner (none on the dev database
 * on 2026-10-10) hold nothing for any user, so their instruments' rows are not copied for them.
 * TZ-safe: no date/time column is
 * added or converted, dates are read as DATE → LocalDate, {@code created_at} is copied in SQL, and
 * "today" is {@link AppTime#today()} (IST).
 */
public class V100__per_user_corporate_actions extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V100__per_user_corporate_actions.class);

    /** How often the split + NOT NULL step is tried while an old server keeps adding owner-less rows. */
    private static final int MAX_NOT_NULL_ATTEMPTS = 10;

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        ddl(c, "ALTER TABLE corporate_actions ADD user_id VARCHAR2(36)", 1430);

        // A server still on the old code may insert an owner-less row after the split ran: the NOT NULL
        // step then fails (ORA-02296 null values found, or ORA-00054 while that insert is uncommitted),
        // so the split runs again for the stragglers and the step is retried.
        for (int attempt = 1; ; attempt++) {
            SharedCorporateActionSplit.Result r = SharedCorporateActionSplit.run(c, AppTime.today());
            log.info("V100 corporate actions per user (pass {}): {} shared row(s), {} copy(ies) inserted, {} row(s) "
                            + "affecting nobody dropped, {} shared row(s) with fractional cash-in-lieu ({} copy(ies) "
                            + "carry it)", attempt, r.sharedRows(), r.copiesInserted(), r.rowsDropped(),
                    r.sharedRowsWithCashInLieu(), r.copiesWithCashInLieu());
            try {
                ddl(c, "ALTER TABLE corporate_actions MODIFY user_id NOT NULL", 1442);
                break;
            } catch (SQLException e) {
                if (attempt >= MAX_NOT_NULL_ATTEMPTS || (e.getErrorCode() != 2296 && e.getErrorCode() != 54)) {
                    throw e;
                }
                log.warn("V100: owner-less corporate actions appeared after the split (ORA-{}); splitting again",
                        e.getErrorCode());
                Thread.sleep(1000L);
            }
        }
        ddl(c, "ALTER TABLE corporate_actions ADD CONSTRAINT fk_corp_actions_user "
                + "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE", 2264, 2275);
        ddl(c, "CREATE INDEX idx_corp_actions_user_inst ON corporate_actions (user_id, instrument_id, ex_date)", 955, 1408);
        ddl(c, "CREATE INDEX idx_corp_actions_user_target ON corporate_actions (user_id, target_instrument_id)", 955, 1408);
    }

    /** Runs {@code sql}, ignoring the Oracle errors that mean it already happened. */
    private static void ddl(Connection c, String sql, Integer... alreadyDone) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            if (!Set.of(alreadyDone).contains(e.getErrorCode())) {
                throw e;
            }
        }
    }
}
