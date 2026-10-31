package com.financeos.gmail.classify;

/** Coarse class of a Gmail message, as decided by {@link EmailClassifier}. */
public enum EmailKind {
    ALERT,
    STATEMENT,
    OTHER
}
