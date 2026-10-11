package com.financeos.statement.parser;

public enum FileType {
    PDF, XLSX, XLS;

    // Readers accept a %PDF- header anywhere in the first 1024 bytes; HSBC
    // prepends a %%PDF:/%%PUSHDATA: preamble before the real header.
    private static final int PDF_HEADER_WINDOW = 1024;
    private static final byte[] PDF_HEADER = {'%', 'P', 'D', 'F', '-'};

    public static FileType detect(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw new StatementParseException("ERROR: file too small or empty");
        }
        if (bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) {
            return XLSX;
        }
        if ((bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0) {
            return XLS;
        }
        if (pdfHeaderOffset(bytes) >= 0) {
            return PDF;
        }
        throw new StatementParseException("ERROR: unsupported file type (expected PDF or Excel)");
    }

    /** Offset of the {@code %PDF-} header within the first 1024 bytes, or -1. */
    public static int pdfHeaderOffset(byte[] bytes) {
        int last = Math.min(bytes.length, PDF_HEADER_WINDOW) - PDF_HEADER.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < PDF_HEADER.length; j++) {
                if (bytes[i + j] != PDF_HEADER[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
