package com.financeos.api.tag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.financeos.domain.category.Tag;
import org.springframework.lang.Nullable;

import java.util.UUID;

/** A tag; transactionCount is set on the tag list/page/get (the Manage screen) and absent elsewhere. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TagResponse(
        UUID id,
        String name,
        @Nullable Long transactionCount) {

    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName(), null);
    }

    public static TagResponse from(Tag tag, long transactionCount) {
        return new TagResponse(tag.getId(), tag.getName(), transactionCount);
    }
}
