package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.UnlockSelector.Row;
import com.financeos.gmail.accountdetect.UnlockSelector.StatementMail;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Status;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UnlockSelectorTest {

    private static Entity entity(Detection d, String bank, String l4, Conf c, Status st, LocalDate last) {
        Entity e = new Entity(bank, l4);
        e.existsConf = c;
        e.status = st;
        e.last = last;
        d.entities.add(e);
        return e;
    }

    private static StatementMail mail(String ref, String bank, String cluster, LocalDate date, Set<String> l4, boolean prot, boolean opened) {
        return new StatementMail(ref, bank, cluster, date, l4, prot, opened);
    }

    @Test
    void picksNewestPerEntity_stopWindowForDormant_unlinkedPerCluster_skipsOpened_andDedupes() {
        Detection d = new Detection();
        entity(d, "hdfc", "7777", Conf.HIGH, Status.ACTIVE, LocalDate.of(2026, 10, 1));   // 7777 active
        entity(d, "hdfc", "5591", Conf.MEDIUM, Status.DORMANT, LocalDate.of(2025, 5, 20));
        entity(d, "axis", "8450", Conf.LOW, Status.ACTIVE, LocalDate.of(2026, 10, 1));
        List<StatementMail> ms = new ArrayList<>();
        for (int m = 1; m <= 10; m++) ms.add(mail("a" + m, "hdfc", "c1", LocalDate.of(2026, m, 12), Set.of("7777"), true, false));
        ms.add(mail("a11", "hdfc", "c1", LocalDate.of(2026, 10, 30), Set.of("7777"), true, true)); // newer but already opened
        for (int m = 1; m <= 28; m++) {
            LocalDate dt = LocalDate.of(2023, 1, 12).plusMonths(m);
            if (!dt.isAfter(LocalDate.of(2025, 6, 15))) ms.add(mail("b" + m, "hdfc", "c2", dt, Set.of("5591"), true, false));
        }
        ms.add(mail("u1", "hdfc", "c3", LocalDate.of(2026, 1, 5), Set.of(), true, false));
        ms.add(mail("u2", "hdfc", "c3", LocalDate.of(2026, 3, 5), Set.of(), true, false));
        ms.add(mail("u3", "hdfc", "c4", LocalDate.of(2026, 3, 5), Set.of("7777", "5591"), true, false)); // two entities: unlinked
        ms.add(mail("x1", "axis", "c5", LocalDate.of(2026, 3, 5), Set.of("8450"), true, false));        // LOW entity: no newest row
        ms.add(mail("n1", "hdfc", "c1", LocalDate.of(2026, 9, 1), Set.of("7777"), false, false));       // nothing protected

        List<Row> rows = UnlockSelector.select(ms, d);
        assertThat(rows).extracting(Row::ref).doesNotHaveDuplicates().doesNotContain("a11", "n1", "x1", "u1");
        assertThat(rows).filteredOn(r -> r.reason().equals(UnlockSelector.NEWEST)).extracting(Row::ref).containsExactlyInAnyOrder("a10", "b28");
        Row lastBefore = rows.stream().filter(r -> r.reason().equals(UnlockSelector.YEAR_BEFORE_STOP)).findFirst().orElseThrow();
        assertThat(lastBefore.ref()).isEqualTo("b16");  // 2024-05: twelve months before the last (2025-05, already the newest row)
        assertThat(rows).filteredOn(r -> r.reason().equals(UnlockSelector.UNLINKED)).extracting(Row::ref).containsExactlyInAnyOrder("u2", "u3");
        assertThat(rows).filteredOn(r -> r.ref().equals("u2")).extracting(Row::last4).containsOnlyNulls();
    }
}
