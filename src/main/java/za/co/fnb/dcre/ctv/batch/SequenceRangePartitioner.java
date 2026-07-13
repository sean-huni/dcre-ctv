package za.co.fnb.dcre.ctv.batch;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R-41: splits the arrival's sequence space [1, txCount] into contiguous
 * ranges, one per partition, keys fromSeq/toSeq (both inclusive). The header's
 * declared count is trusted here because the headerCheck step has already
 * proven spine count == declared count.
 */
public class SequenceRangePartitioner implements Partitioner {

    private final long txCount;

    public SequenceRangePartitioner(long txCount) {
        this.txCount = txCount;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        return split(txCount, gridSize);
    }

    static Map<String, ExecutionContext> split(long txCount, int gridSize) {
        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        if (txCount <= 0 || gridSize <= 0) {
            return partitions;
        }
        long chunk = (txCount + gridSize - 1) / gridSize; // ceil(txCount / gridSize)
        int index = 0;
        for (long from = 1; from <= txCount; from += chunk) {
            ExecutionContext context = new ExecutionContext();
            context.putLong("fromSeq", from);
            context.putLong("toSeq", Math.min(from + chunk - 1, txCount));
            partitions.put("partition" + index++, context);
        }
        return partitions;
    }
}
