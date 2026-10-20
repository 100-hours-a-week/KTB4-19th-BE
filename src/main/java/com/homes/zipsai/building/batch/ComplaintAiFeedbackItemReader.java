package com.homes.zipsai.building.batch;

import java.util.Collections;
import java.util.Iterator;

import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;

public class ComplaintAiFeedbackItemReader implements ItemReader<ComplaintDetail> {

    private final ComplaintDetailRepository complaintDetailRepository;
    private final long minId;
    private final long maxId;
    private final int limit;
    private Iterator<ComplaintDetail> items;

    public ComplaintAiFeedbackItemReader(ComplaintDetailRepository complaintDetailRepository,
                                         long minId, long maxId, int limit) {
        this.complaintDetailRepository = complaintDetailRepository;
        this.minId = minId;
        this.maxId = maxId;
        this.limit = limit;
    }

    @Override
    public ComplaintDetail read() {
        if (items == null) {
            items = limit <= 0
                    ? Collections.emptyIterator()
                    : complaintDetailRepository.findPendingAiFeedbackBetween(minId, maxId, PageRequest.of(0, limit))
                            .getContent().iterator();
        }
        return items.hasNext() ? items.next() : null;
    }
}
