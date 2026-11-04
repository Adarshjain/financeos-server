package com.financeos.gmail.classify.eval;

import com.financeos.statement.parser.StatementIdentity;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.StatementParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Local, dev-only hit-rate check of statement auto-detection over a folder of the owner's own PDFs
 * (env {@code STATEMENT_DETECT_DIR}, no default). Only .pdf files that open without a password are read;
 * per file it runs the issuer detector and the metadata extractor, nothing else. Prints one summary of
 * counts per detected bank id: no file names, descriptions, amounts, numbers or text. Writes nothing.
 * Run with {@code --spring.profiles.active=statement-detect-eval}.
 */
@Component
@Profile("statement-detect-eval")
public class StatementDetectEvalRunner implements CommandLineRunner {

    @Value("${STATEMENT_DETECT_DIR:}")
    private String dir;

    @Override
    public void run(String... args) throws IOException {
        if (dir == null || dir.isBlank()) {
            System.out.println("STATEMENT_DETECT_DIR is not set.");
            return;
        }
        List<Path> pdfs;
        try (Stream<Path> files = Files.list(Path.of(dir))) {
            pdfs = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().toList();
        }
        // bank id -> {files, bank text found, account number found}
        Map<String, int[]> byBank = new TreeMap<>();
        int total = 0, bankFound = 0, numberFound = 0, locked = 0, unreadable = 0;
        for (Path pdf : pdfs) {
            StatementIdentity id;
            try {
                id = StatementParseEngine.identify(Files.readAllBytes(pdf), null);
            } catch (StatementParseException e) {
                if (e.isPasswordProblem()) {
                    locked++;
                } else {
                    unreadable++;
                }
                continue;
            } catch (RuntimeException e) {
                unreadable++;
                continue;
            }
            total++;
            boolean bank = id.bankText() != null && !id.bankText().isBlank();
            boolean number = id.accountNumber() != null && !id.accountNumber().isBlank();
            if (bank) bankFound++;
            if (number) numberFound++;
            int[] row = byBank.computeIfAbsent(id.bankId() == null ? "(undetected)" : id.bankId(), k -> new int[3]);
            row[0]++;
            if (bank) row[1]++;
            if (number) row[2]++;
        }
        StringBuilder out = new StringBuilder("Statement detection summary\n");
        byBank.forEach((bankId, r) -> out.append(String.format("  %s: files=%d bankDetected=%d accountNumberFound=%d%n", bankId, r[0], r[1], r[2])));
        out.append(String.format("Totals: files=%d bankDetected=%d accountNumberFound=%d lockedSkipped=%d unreadableSkipped=%d%n",
                total, bankFound, numberFound, locked, unreadable));
        System.out.print(out);
    }
}
