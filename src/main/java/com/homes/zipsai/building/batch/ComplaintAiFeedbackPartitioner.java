package com.homes.zipsai.building.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.data.domain.PageRequest;

import com.homes.zipsai.building.repository.ComplaintDetailRepository;

public class ComplaintAiFeedbackPartitioner implements Partitioner {

    static final String MIN_ID = "minId";
    static final String MAX_ID = "maxId";
    static final String LIMIT = "limit";

    private final ComplaintDetailRepository complaintDetailRepository;
    private final int maxBatchSize;

    public ComplaintAiFeedbackPartitioner(ComplaintDetailRepository complaintDetailRepository, int maxBatchSize) {
        this.complaintDetailRepository = complaintDetailRepository;
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        List<Long> ids = complaintDetailRepository.findPendingAiFeedbackIds(PageRequest.of(0, maxBatchSize));
        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            partitions.put("partition0", context(0, 0, 0));
            return partitions;
        }
        int size = (ids.size() + gridSize - 1) / gridSize;
        for (int from = 0, index = 0; from < ids.size(); from += size, index++) {
            List<Long> range = ids.subList(from, Math.min(from + size, ids.size()));
            partitions.put("partition" + index, context(range.getFirst(), range.getLast(), range.size()));
        }
        return partitions;
    }

    private static ExecutionContext context(long minId, long maxId, int limit) {
        ExecutionContext context = new ExecutionContext();
        context.putLong(MIN_ID, minId);
        context.putLong(MAX_ID, maxId);
        context.putInt(LIMIT, limit);
        return context;
    }
}
