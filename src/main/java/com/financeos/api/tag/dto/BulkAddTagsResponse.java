package com.financeos.api.tag.dto;

/** transactionsFound: the caller's transactions among the ids sent; tagsAdded: new links created. */
public record BulkAddTagsResponse(int transactionsFound, int tagsAdded) {
}
