package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinkScopeTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 10);

    @Test
    void spanOfTakesTheMinAndMaxDate() {
        LinkScope s = LinkScope.spanOf(List.of(A.plusDays(3), A, A.plusDays(1)), d -> d);

        assertThat(s.from()).isEqualTo(A);
        assertThat(s.to()).isEqualTo(A.plusDays(3));
        assertThat(s.isAll()).isFalse();
    }

    @Test
    void spanOfNothingOrAnUnknownDateIsFull() {
        assertThat(LinkScope.spanOf(List.<LocalDate>of(), d -> d).isAll()).isTrue();
        assertThat(LinkScope.spanOf(java.util.Arrays.asList(A, null), d -> d).isAll()).isTrue();
        assertThat(LinkScope.of(A, null).isAll()).isTrue();
    }

    @Test
    void unionCoversBothAndAllWins() {
        LinkScope a = new LinkScope(A, A.plusDays(2));
        LinkScope b = new LinkScope(A.minusDays(5), A.plusDays(1));

        assertThat(a.union(b)).isEqualTo(new LinkScope(A.minusDays(5), A.plusDays(2)));
        assertThat(a.union(LinkScope.ALL)).isEqualTo(LinkScope.ALL);
        assertThat(LinkScope.ALL.union(a)).isEqualTo(LinkScope.ALL);
    }

    @Test
    void anchorsAreOneWindowOutAndCandidatesThree() {
        LinkScope s = new LinkScope(A, A.plusDays(4));

        assertThat(s.anchors(2)).isEqualTo(new LinkScope(A.minusDays(2), A.plusDays(6)));
        assertThat(s.candidates(2)).isEqualTo(new LinkScope(A.minusDays(6), A.plusDays(10)));
        assertThat(LinkScope.ALL.candidates(2)).isEqualTo(LinkScope.ALL);
    }

    @Test
    void containsIsInclusiveAndAllContainsEverything() {
        LinkScope s = new LinkScope(A, A.plusDays(2));

        assertThat(s.contains(A)).isTrue();
        assertThat(s.contains(A.plusDays(2))).isTrue();
        assertThat(s.contains(A.plusDays(3))).isFalse();
        assertThat(s.contains(A.minusDays(1))).isFalse();
        assertThat(LinkScope.ALL.contains(LocalDate.of(1990, 1, 1))).isTrue();
    }
}
