package com.financeos.statement.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * The passwords tried for one document password: as given, then lowercase, then uppercase (some banks lock with a
 * different case than the one on file). Passwords are never logged or kept here.
 */
public final class PdfPasswords {

    private PdfPasswords() {
    }

    /** The password as given, its lowercase and its uppercase, distinct, in that order; empty for null / blank. */
    public static List<String> variants(String pwd) {
        List<String> out = new ArrayList<>(3);
        if (pwd == null || pwd.isBlank()) return out;
        for (String v : new String[]{pwd, pwd.toLowerCase(Locale.ROOT), pwd.toUpperCase(Locale.ROOT)}) {
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    /**
     * The variant of the password that opens this PDF; the password itself when none does (so the caller fails with
     * the same error as before) or when it is null / blank.
     */
    public static String workingPdf(byte[] pdf, String pwd) {
        List<String> vs = variants(pwd);
        if (vs.size() < 2) return pwd;
        for (String v : vs) {
            try (PDDocument ignored = Loader.loadPDF(pdf, v)) {
                return v;
            } catch (IOException | RuntimeException e) {
                // try the next variant
            }
        }
        return pwd;
    }

    /** As {@link #workingPdf} for an encrypted Excel file. */
    public static String workingExcel(byte[] bytes, String pwd) {
        List<String> vs = variants(pwd);
        if (vs.size() < 2) return pwd;
        for (String v : vs) {
            try (Workbook ignored = WorkbookFactory.create(new ByteArrayInputStream(bytes), v)) {
                return v;
            } catch (Exception e) {
                // try the next variant
            }
        }
        return pwd;
    }
}
