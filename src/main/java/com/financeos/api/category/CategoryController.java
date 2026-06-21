package com.financeos.api.category;

import com.financeos.api.category.dto.CategoryRequest;
import com.financeos.api.category.dto.CategoryResponse;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryService;
import jakarta.validation.Valid;
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
