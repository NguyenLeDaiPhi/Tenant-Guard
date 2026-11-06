package com.authservice.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select userIdentity from UserIdentity userIdentity
        where userIdentity.normalizedEmail = :normalizedEmail
        """)
    Optional<UserIdentity> findByNormalizedEmailForUpdate(
            @Param("normalizedEmail") String normalizedEmail);

    Optional<UserIdentity> findByNormalizedEmail(String normalizedEmail);
}
