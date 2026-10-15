package com.homes.zipsai.building.batch;

import java.util.Iterator;

import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;

public class ComplaintAiFeedbackItemReader implements ItemReader<ComplaintDetail> {

    private final ComplaintDetailRepository complaintDetailRepository;
    private final String unknownValue;
    private final int maxBatchSize;
    private Iterator<ComplaintDetail> items;

    public ComplaintAiFeedbackItemReader(ComplaintDetailRepository complaintDetailRepository, String unknownValue,
                                         int maxBatchSize) {
        this.complaintDetailRepository = complaintDetailRepository;
        this.unknownValue = unknownValue;
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public ComplaintDetail read() {
        if (items == null) {
            items = complaintDetailRepository.findPendingAiFeedback(unknownValue, PageRequest.of(0, maxBatchSize))
                    .getContent().iterator();
        }
        return items.hasNext() ? items.next() : null;
    }
}
