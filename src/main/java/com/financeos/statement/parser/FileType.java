package com.financeos.statement.parser;

public enum FileType {
    PDF, XLSX, XLS;

    public static FileType detect(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw new StatementParseException("ERROR: file too small or empty");
        }
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return PDF;
        }
        if (bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) {
            return XLSX;
        }
        if ((bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0) {
            return XLS;
        }
        throw new StatementParseException("ERROR: unsupported file type (expected PDF or Excel)");
    }
}
