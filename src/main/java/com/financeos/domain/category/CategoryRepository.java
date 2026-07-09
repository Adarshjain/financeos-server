package com.financeos.domain.category;

import com.financeos.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    Optional<Category> findByNameAndUser(String name, User user);

    List<Category> findByUserId(UUID userId);
}
