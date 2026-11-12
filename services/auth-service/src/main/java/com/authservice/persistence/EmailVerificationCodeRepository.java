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
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select code from EmailVerificationCode code
        where code.normalizedEmail = :normalizedEmail
          and code.consumedAt is null
          and code.status = com.authservice.persistence.EmailVerificationCodeStatus.PENDING
        order by code.createdAt desc
        """)
    List<EmailVerificationCode> findAllPendingByNormalizedEmailForUpdate(
            @Param("normalizedEmail") String normalizedEmail);

    @Modifying
    @Query("""
        delete from EmailVerificationCode code
        where code.status <> com.authservice.persistence.EmailVerificationCodeStatus.PENDING
           or code.consumedAt is not null
           or code.expiresAt <= :cutoff
        """)
    int deleteExpiredOrConsumedBefore(@Param("cutoff") Instant cutoff);
}
