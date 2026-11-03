package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;

import java.time.LocalDate;

/** Made-up detector entities for tests outside this package (the Entity constructor is package-private). */
public final class DetectionFixtures {

    private DetectionFixtures() {}

    public static Detection.Entity entity(String bank, String last4, Kind kind, Conf exists) {
        Detection.Entity e = new Detection.Entity(bank, last4);
        e.kind = kind;
        e.kindConf = kind == null ? null : exists;
        e.existsConf = exists;
        e.first = LocalDate.of(2024, 3, 10);
        e.last = LocalDate.of(2026, 10, 20);
        e.evidenceMails = 12;
        return e;
    }

    public static Detection detection(Detection.Entity... entities) {
        Detection d = new Detection();
        for (Detection.Entity e : entities) d.entities.add(e);
        return d;
    }
}
