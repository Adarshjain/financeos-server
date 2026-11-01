package com.financeos.api.category;

import com.financeos.api.category.dto.CategoryListResponse;
import com.financeos.domain.category.CategoryCatalog;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/category-list")
public class CategoryListController {

    private final CategoryCatalog catalog;

    public CategoryListController(CategoryCatalog catalog) {
        this.catalog = catalog;
    }

    /** The fixed global category list; static, so the client may cache it. */
    @GetMapping
    public ResponseEntity<CategoryListResponse> getCategoryList() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(CategoryListResponse.from(catalog));
    }
}
