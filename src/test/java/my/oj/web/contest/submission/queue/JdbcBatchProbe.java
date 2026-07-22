package my.oj.web.contest.submission.queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class JdbcBatchProbe {

    private final AtomicInteger addBatchCount = new AtomicInteger();
    private final AtomicInteger executeBatchCount = new AtomicInteger();
    private final AtomicInteger executeUpdateCount = new AtomicInteger();
    private final List<String> targetSqls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    void recordTargetSql(String sql) {
        targetSqls.add(sql);
    }

    void recordAddBatch(String sql) {
        addBatchCount.incrementAndGet();
        events.add("addBatch :: " + sql);
    }

    void recordExecuteBatch(String sql) {
        executeBatchCount.incrementAndGet();
        events.add("executeBatch :: " + sql);
    }

    void recordExecuteUpdate(String sql) {
        executeUpdateCount.incrementAndGet();
        events.add("executeUpdate :: " + sql);
    }

    int addBatchCount() {
        return addBatchCount.get();
    }

    int executeBatchCount() {
        return executeBatchCount.get();
    }

    int executeUpdateCount() {
        return executeUpdateCount.get();
    }

    List<String> targetSqls() {
        return List.copyOf(targetSqls);
    }

    List<String> events() {
        return List.copyOf(events);
    }

    void reset() {
        addBatchCount.set(0);
        executeBatchCount.set(0);
        executeUpdateCount.set(0);
        targetSqls.clear();
        events.clear();
    }
}
