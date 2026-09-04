package com.financeos.gmail.domain;

import com.financeos.domain.user.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GmailBackfillDemandTest {

    @Test
    void testIsNewAndMarkNotNew() {
        User user = new User();
        UUID userId = UUID.randomUUID();
        user.setId(userId);

        LocalDate floorDate = LocalDate.of(2026, 8, 1);
        GmailBackfillDemand demand = new GmailBackfillDemand(user, floorDate);

        assertThat(demand.getId()).isEqualTo(userId);
        assertThat(demand.getFloorDate()).isEqualTo(floorDate);
        assertThat(demand.isNew()).isTrue();

        demand.markNotNew();
        assertThat(demand.isNew()).isFalse();
    }

    @Test
    void testNoArgsConstructorDefaultsToNew() {
        GmailBackfillDemand demand = new GmailBackfillDemand();
        assertThat(demand.isNew()).isTrue();

        demand.markNotNew();
        assertThat(demand.isNew()).isFalse();
    }
}
