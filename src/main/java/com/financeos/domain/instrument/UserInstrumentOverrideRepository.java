package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserInstrumentOverrideRepository extends JpaRepository<UserInstrumentOverride, UUID> {

    List<UserInstrumentOverride> findByUserId(UUID userId);

    Optional<UserInstrumentOverride> findByUserIdAndInstrumentId(UUID userId, UUID instrumentId);
}
