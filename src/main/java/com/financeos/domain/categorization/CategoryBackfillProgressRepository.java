package com.financeos.domain.categorization;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryBackfillProgressRepository
        extends JpaRepository<CategoryBackfillProgress, CategoryBackfillProgress.Key> {
}
