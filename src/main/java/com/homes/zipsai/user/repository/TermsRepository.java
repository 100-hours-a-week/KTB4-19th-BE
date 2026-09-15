package com.homes.zipsai.user.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.user.domain.Terms;
import com.homes.zipsai.user.domain.TermsType;

public interface TermsRepository extends JpaRepository<Terms, Long> {

    @Query("""
        select t from Terms t
        where t.termsType = :termsType and t.effectiveAt <= :date and t.deletedAt is null
        order by t.version desc
        limit 1
        """)
    Optional<Terms> findLatestEffective(@Param("termsType") TermsType termsType, @Param("date") LocalDate date);

    default Terms getLatest(TermsType termsType) {
        return findLatestEffective(termsType, LocalDate.now())
            .orElseThrow(() -> new IllegalStateException("시행 중인 약관이 없습니다: " + termsType));
    }
}
