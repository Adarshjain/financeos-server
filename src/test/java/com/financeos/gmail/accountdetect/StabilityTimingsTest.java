package com.financeos.gmail.accountdetect;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StabilityTimingsTest {

    @Test
    void timingOverloadFillsBothKeysAndMatchesThreeArgResult() {
        SyntheticMailbox mb = SyntheticMailbox.generate(1);
        List<MailDerived> derived = mb.mails.stream().map(FeatureExtractor::derive).toList();
        Map<String, Long> timings = new LinkedHashMap<>();

        Stability.Result timed = Stability.detectStable(derived, mb.statements, 1, timings);
        Stability.Result plain = Stability.detectStable(derived, mb.statements, 1);

        assertThat(timings).containsKeys("10 detect full", "11 stability resamples");
        assertThat(timings.values()).allMatch(v -> v >= 0);
        assertThat(timed.detection().facts()).isEqualTo(plain.detection().facts());
        assertThat(timed.stability()).isEqualTo(plain.stability());
        assertThat(timed.demoted()).isEqualTo(plain.demoted());
        // null map = no recording
        assertThat(Stability.detectStable(derived, mb.statements, 1, null).detection().facts()).isEqualTo(plain.detection().facts());
    }
}
