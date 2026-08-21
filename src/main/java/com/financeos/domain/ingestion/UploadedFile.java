package com.financeos.domain.ingestion;

public record UploadedFile(String filename, String contentType, byte[] bytes) {}
