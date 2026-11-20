package com.authservice.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface EmailVerificationChallengeRepository extends JpaRepository<EmailVerificationChallenge, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select code from EmailVerificationChallenge code
        where code.identity.normalizedEmail = :normalizedEmail
          and code.consumedAt is null
        order by code.createdAt desc
        """)
    List<EmailVerificationChallenge> findAllPendingByNormalizedEmailForUpdate(
            @Param("normalizedEmail") String normalizedEmail);

    @Modifying
    @Query("""
        delete from EmailVerificationChallenge code
        where code.consumedAt is not null
           or code.expiresAt <= :cutoff
        """)
    int deleteExpiredOrConsumedBefore(@Param("cutoff") Instant cutoff);
}
