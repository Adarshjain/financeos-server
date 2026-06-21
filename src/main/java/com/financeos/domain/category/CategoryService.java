package com.financeos.domain.category;

import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public CategoryService(CategoryRepository categoryRepository, UserRepository userRepository) {
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    public Category createCategory(String name) {
        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Check for existing by name to prevent duplicates (also enforced by a
        // DB unique constraint on (user_id, name))
        String trimmedName = name.trim();
        return categoryRepository.findByNameAndUser(trimmedName, user)
                .orElseGet(() -> categoryRepository.save(new Category(trimmedName, user)));
    }

    @Transactional(readOnly = true)
    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Category getCategory(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));

        // SECURITY: Explicit ownership check
        UUID currentUserId = UserContext.getCurrentUserId();
        if (!category.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this category.");
        }

        return category;
    }

    public Category updateCategory(UUID id, String newName) {
        Category category = getCategory(id); // Reuses ownership check

        // Check for duplicates for this user
        String trimmedName = newName.trim();
        categoryRepository.findByNameAndUser(trimmedName, category.getUser())
                .ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        throw new ValidationException("A category with this name already exists.");
                    }
                });

        category.setName(trimmedName);
        return categoryRepository.save(category);
    }

    public void deleteCategory(UUID id) {
        Category category = getCategory(id); // Reuses ownership check
        categoryRepository.delete(category);
    }
}
