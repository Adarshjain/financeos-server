package com.financeos.domain.nps.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpsStatementPasswordTest {

    @Test
    void firstFourLettersOfFirstNamePlusDdMm() {
        assertThat(NpsStatementPassword.candidates("Ravi Kumar Sharma", LocalDate.of(1990, 6, 14)))
                .containsExactly("ravi1406");
    }

    @Test
    void shortFirstNameIsPaddedWithNines() {
        assertThat(NpsStatementPassword.candidates("Om Mukherjee", LocalDate.of(1975, 11, 1)))
                .containsExactly("99om0111");
    }

    @Test
    void trimsAndStripsNonLetters() {
        assertThat(NpsStatementPassword.candidates("  ravi", LocalDate.of(1990, 6, 14))).containsExactly("ravi1406");
        assertThat(NpsStatementPassword.candidates("A.B. Rao", LocalDate.of(1990, 6, 14))).containsExactly("99ab1406");
    }

    @Test
    void emptyWhenInputMissing() {
        assertThat(NpsStatementPassword.candidates(null, LocalDate.of(1990, 6, 14))).isEqualTo(List.of());
        assertThat(NpsStatementPassword.candidates("Ravi", null)).isEmpty();
        assertThat(NpsStatementPassword.candidates("   ", LocalDate.of(1990, 6, 14))).isEmpty();
    }
}
