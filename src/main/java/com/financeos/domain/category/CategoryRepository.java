package com.financeos.domain.category;

import com.financeos.domain.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    Optional<Category> findByNameAndUser(String name, User user);

    List<Category> findByUserId(UUID userId);

    /** One page of the user's categories whose lower-cased name matches {@code pattern} (a LIKE pattern), by name. */
    @Query("SELECT c FROM Category c WHERE c.user.id = :userId AND LOWER(c.name) LIKE :pattern ESCAPE '\\' " +
           "ORDER BY LOWER(c.name), c.id")
    Page<Category> findPage(@Param("userId") UUID userId, @Param("pattern") String pattern, Pageable pageable);
}
