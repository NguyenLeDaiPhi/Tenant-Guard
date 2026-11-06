package com.authservice.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select code from EmailVerificationCode code
        where code.normalizedEmail = :normalizedEmail
          and code.consumedAt is null
        order by code.createdAt desc
        """)
    List<EmailVerificationCode> findAllActiveByNormalizedEmailForUpdate(
            @Param("normalizedEmail") String normalizedEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select code from EmailVerificationCode code
        where code.identityId = :identityId
          and code.consumedAt is null
        order by code.createdAt desc
        """)
    List<EmailVerificationCode> findAllActiveByIdentityIdForUpdate(
            @Param("identityId") UUID identityId);

    @Modifying
    @Query("""
        delete from EmailVerificationCode code
        where (code.consumedAt is not null and code.consumedAt < :cutoff)
           or code.expiresAt < :cutoff
        """)
    int deleteExpiredOrConsumedBefore(@Param("cutoff") Instant cutoff);
}
