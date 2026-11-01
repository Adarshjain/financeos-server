package db.migration;

import com.financeos.domain.report.SeededSpendReportRepoint;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * V124: re-points the Home dashboard's seeded "Spend this month" report from {@code tag} (V121 rewrote
 * every report) back to the fixed {@code category}, for unedited seeded copies only; see
 * {@link SeededSpendReportRepoint}. Idempotent: a copy already on {@code category} no longer matches.
 * Only changed rows are written and {@code updated_at} is left alone; no date/time is read or written.
 */
public class V124__repoint_seeded_spend_report extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V124__repoint_seeded_spend_report.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        // Read everything first, then write: no update while the result set is open.
        Map<String, String> rewritten = new HashMap<>();
        int scanned = 0;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, description, type, datasource, definition FROM reports "
                     + "WHERE name = 'Spend this month' AND datasource = 'transactions'")) {
            while (rs.next()) {
                scanned++;
                String updated = SeededSpendReportRepoint.repoint(rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), clob(rs, 6));
                if (updated != null) {
                    rewritten.put(rs.getString(1), updated);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE reports SET definition = ? WHERE id = ?")) {
            for (Map.Entry<String, String> e : rewritten.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setString(2, e.getKey());
                ps.executeUpdate();
            }
        }
        log.info("V124 seeded spend report: {} report(s) scanned, {} re-pointed", scanned, rewritten.size());
    }

    private static String clob(ResultSet rs, int column) throws Exception {
        try (Reader r = rs.getCharacterStream(column)) {
            if (r == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            for (int n; (n = r.read(buf)) > 0; ) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }
}
