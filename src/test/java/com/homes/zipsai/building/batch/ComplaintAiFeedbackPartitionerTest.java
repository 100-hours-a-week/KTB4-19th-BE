package com.homes.zipsai.building.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.data.domain.Pageable;

import com.homes.zipsai.building.repository.ComplaintDetailRepository;

@ExtendWith(MockitoExtension.class)
class ComplaintAiFeedbackPartitionerTest {

    @Mock
    ComplaintDetailRepository complaintDetailRepository;

    @Test
    @DisplayName("전송 대상 ID를 오래된 순으로 나눠 파티션마다 연속된 ID 구간을 맡긴다")
    void splitsPendingIdsIntoContiguousRanges() {
        given(complaintDetailRepository.findPendingAiFeedbackIds(any(Pageable.class)))
            .willReturn(List.of(3L, 5L, 8L, 13L, 21L, 34L, 55L));

        Map<String, ExecutionContext> partitions =
            new ComplaintAiFeedbackPartitioner(complaintDetailRepository, 5000).partition(3);

        assertThat(partitions).hasSize(3);
        assertRange(partitions.get("partition0"), 3L, 8L, 3);
        assertRange(partitions.get("partition1"), 13L, 34L, 3);
        assertRange(partitions.get("partition2"), 55L, 55L, 1);
    }

    @Test
    @DisplayName("전송 대상이 파티션 수보다 적으면 대상 수만큼만 나눈다")
    void createsFewerPartitionsThanGridSizeWhenFewTargets() {
        given(complaintDetailRepository.findPendingAiFeedbackIds(any(Pageable.class)))
            .willReturn(LongStream.rangeClosed(1, 2).boxed().toList());

        Map<String, ExecutionContext> partitions =
            new ComplaintAiFeedbackPartitioner(complaintDetailRepository, 5000).partition(4);

        assertThat(partitions).hasSize(2);
    }

    @Test
    @DisplayName("전송 대상이 없으면 아무것도 읽지 않는 파티션 하나만 만든다")
    void createsEmptyPartitionWhenNoTargets() {
        given(complaintDetailRepository.findPendingAiFeedbackIds(any(Pageable.class))).willReturn(List.of());

        Map<String, ExecutionContext> partitions =
            new ComplaintAiFeedbackPartitioner(complaintDetailRepository, 5000).partition(4);

        assertThat(partitions).hasSize(1);
        assertThat(partitions.get("partition0").getInt(ComplaintAiFeedbackPartitioner.LIMIT)).isZero();
    }

    private static void assertRange(ExecutionContext context, long minId, long maxId, int limit) {
        assertThat(context.getLong(ComplaintAiFeedbackPartitioner.MIN_ID)).isEqualTo(minId);
        assertThat(context.getLong(ComplaintAiFeedbackPartitioner.MAX_ID)).isEqualTo(maxId);
        assertThat(context.getInt(ComplaintAiFeedbackPartitioner.LIMIT)).isEqualTo(limit);
    }
}
