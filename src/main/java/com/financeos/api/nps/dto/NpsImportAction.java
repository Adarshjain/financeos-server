package com.financeos.api.nps.dto;

/** What importing one statement row would do. */
public enum NpsImportAction {
    ADD,
    UPGRADE,
    SKIP,
    UNMAPPED
}
