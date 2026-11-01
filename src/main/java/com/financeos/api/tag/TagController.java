package com.financeos.api.tag;

import com.financeos.api.tag.dto.BulkAddTagsRequest;
import com.financeos.api.tag.dto.BulkAddTagsResponse;
import com.financeos.api.tag.dto.TagRequest;
import com.financeos.api.tag.dto.TagResponse;
import com.financeos.domain.category.Tag;
import com.financeos.domain.category.TagService;
import com.financeos.domain.category.TagService.TagWithCount;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    private static TagResponse toResponse(TagWithCount t) {
        return TagResponse.from(t.tag(), t.transactionCount());
    }

    @PostMapping
    public ResponseEntity<TagResponse> createTag(@Valid @RequestBody TagRequest request) {
        Tag tag = tagService.createTag(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(TagResponse.from(tag));
    }

    @GetMapping
    public ResponseEntity<List<TagResponse>> getAllTags() {
        return ResponseEntity.ok(tagService.getAllTags().stream().map(TagController::toResponse).toList());
    }

    /** Paged, name-sorted, searchable variant of the list above (search = name contains, case-insensitive). */
    @GetMapping("/page")
    public ResponseEntity<Page<TagResponse>> getTagsPage(
            @RequestParam(required = false) String search,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(tagService.searchTags(search, pageable).map(TagController::toResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TagResponse> getTag(@PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(tagService.getTagWithCount(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TagResponse> updateTag(
            @PathVariable UUID id,
            @Valid @RequestBody TagRequest request) {
        tagService.updateTag(id, request.name());
        return ResponseEntity.ok(toResponse(tagService.getTagWithCount(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTag(@PathVariable UUID id) {
        tagService.deleteTag(id);
        return ResponseEntity.noContent().build();
    }

    /** Adds every tag to every transaction; idempotent and user-scoped. */
    @PostMapping("/bulk-add")
    public ResponseEntity<BulkAddTagsResponse> bulkAdd(@Valid @RequestBody BulkAddTagsRequest request) {
        return ResponseEntity.ok(tagService.bulkAdd(request.transactionIds(), request.tagIds()));
    }
}
