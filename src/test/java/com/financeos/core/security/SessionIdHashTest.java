package com.financeos.core.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionIdHashTest {

    @Test
    void testHashSessionIdReturns12CharHex() {
        String rawSessionId = "E17D2AB5A8465593877B4D727CE35ADD";
        String hashed = SessionHashUtils.hashSessionId(rawSessionId);

        assertNotNull(hashed);
        assertEquals(12, hashed.length(), "Session hash must be 12 hex characters");
        assertFalse(hashed.contains(rawSessionId), "Raw session ID must not be present in output");
        assertFalse(rawSessionId.contains(hashed), "Hashed session ID must not be raw substring");
    }

    @Test
    void testHashSessionIdHandlesNullAndEmpty() {
        assertEquals("none", SessionHashUtils.hashSessionId(null));
        assertEquals("none", SessionHashUtils.hashSessionId(""));
    }
}
