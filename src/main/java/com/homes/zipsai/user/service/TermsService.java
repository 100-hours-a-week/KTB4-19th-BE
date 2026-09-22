package com.homes.zipsai.user.service;

import java.time.LocalDate;
import java.util.Arrays;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;
import com.homes.zipsai.user.domain.Terms;
import com.homes.zipsai.user.domain.TermsType;
import com.homes.zipsai.user.dto.TermsDetailResponse;
import com.homes.zipsai.user.dto.TermsListResponse;
import com.homes.zipsai.user.repository.TermsRepository;
import com.homes.zipsai.user.validator.UserInput;

@Service
public class TermsService {

    private final TermsRepository terms;

    public TermsService(TermsRepository terms) {
        this.terms = terms;
    }

    @Transactional(readOnly = true)
    public TermsListResponse getTermsList() {
        return new TermsListResponse(Arrays.stream(TermsType.values())
                .map(type -> {
                    Terms latest = terms.getLatest(type);
                    return new TermsListResponse.TermsItem(type, latest.getTitle());
                })
                .toList());
    }

    @Transactional(readOnly = true)
    public TermsDetailResponse getTermsDetail(String termsTypeValue) {
        TermsType termsType = parseTermsType(termsTypeValue);
        Terms latest = terms.findLatestEffective(termsType, LocalDate.now())
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.TERMS));
        return new TermsDetailResponse(latest.getContent());
    }

    private TermsType parseTermsType(String termsTypeValue) {
        try {
            return TermsType.valueOf(termsTypeValue);
        } catch (IllegalArgumentException exception) {
            throw UserInput.invalid("termsType", Reason.INVALID_TERMS_TYPE);
        }
    }
}
