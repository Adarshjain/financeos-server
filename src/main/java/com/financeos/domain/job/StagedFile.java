package com.financeos.domain.job;

public record StagedFile(String filename, String contentType, byte[] bytes) {}
