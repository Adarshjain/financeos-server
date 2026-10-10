package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserInstrumentRepointRepository extends JpaRepository<UserInstrumentRepoint, UUID> {

    Optional<UserInstrumentRepoint> findByUserIdAndFromInstrumentId(UUID userId, UUID fromInstrumentId);

    List<UserInstrumentRepoint> findByUserIdAndToInstrumentId(UUID userId, UUID toInstrumentId);

    List<UserInstrumentRepoint> findByUserId(UUID userId);
}
