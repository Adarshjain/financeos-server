package com.financeos.core.observability;

import io.micrometer.tracing.exporter.FinishedSpan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetectorSpanExportPolicyTest {

    private boolean exported(String name) {
        FinishedSpan span = mock(FinishedSpan.class);
        when(span.getName()).thenReturn(name);
        return new DetectorSpanExportPolicy().dropDetectorStageSpans().isExportable(span);
    }

    @Test
    void detectorStageSpans_areDropped_otherSpansExported() {
        assertThat(exported(Ops.DETECTOR_CLUSTER_ROLES)).isFalse();
        assertThat(exported(Ops.DETECTOR_FINISH)).isFalse();
        assertThat(exported(Ops.ACCOUNTDETECT_DETECT_FULL)).isTrue();
        assertThat(exported("job.account_detect")).isTrue();
        assertThat(exported("stage.chat.llm")).isTrue();
        assertThat(exported("query")).isTrue();
        assertThat(exported(null)).isTrue();
    }
}
