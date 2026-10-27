package com.financeos.domain.epf;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EpfEmployerRepository extends JpaRepository<EpfEmployer, UUID> {

    List<EpfEmployer> findAllByUser_Id(UUID userId);

    Optional<EpfEmployer> findByIdAndUser_Id(UUID id, UUID userId);
}
