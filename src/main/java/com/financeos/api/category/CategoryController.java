package com.financeos.api.category;

import com.financeos.api.category.dto.CategoryRequest;
import com.financeos.api.category.dto.CategoryResponse;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryService;
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
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        Category category = categoryService.createCategory(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(category));
    }

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getAllCategories() {
        List<CategoryResponse> responses = categoryService.getAllCategories().stream()
                .map(CategoryResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /** Paged, name-sorted, searchable variant of the list above (search = name contains, case-insensitive). */
    @GetMapping("/page")
    public ResponseEntity<Page<CategoryResponse>> getCategoriesPage(
            @RequestParam(required = false) String search,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(categoryService.searchCategories(search, pageable).map(CategoryResponse::from));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> getCategory(@PathVariable UUID id) {
        Category category = categoryService.getCategory(id);
        return ResponseEntity.ok(CategoryResponse.from(category));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable UUID id,
            @Valid @RequestBody CategoryRequest request) {
        Category category = categoryService.updateCategory(id, request.name());
        return ResponseEntity.ok(CategoryResponse.from(category));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
