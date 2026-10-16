package com.homes.zipsai.building.batch;

import java.util.Iterator;

import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

import com.homes.zipsai.building.repository.AiFeedbackDeletionTarget;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;

public class ComplaintAiFeedbackDeletionReader implements ItemReader<AiFeedbackDeletionTarget> {

    private final ComplaintDetailRepository complaintDetailRepository;
    private final int maxBatchSize;
    private Iterator<AiFeedbackDeletionTarget> items;

    public ComplaintAiFeedbackDeletionReader(ComplaintDetailRepository complaintDetailRepository, int maxBatchSize) {
        this.complaintDetailRepository = complaintDetailRepository;
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public AiFeedbackDeletionTarget read() {
        if (items == null) {
            items = complaintDetailRepository.findPendingAiFeedbackDeletions(PageRequest.of(0, maxBatchSize))
                    .iterator();
        }
        return items.hasNext() ? items.next() : null;
    }
}
