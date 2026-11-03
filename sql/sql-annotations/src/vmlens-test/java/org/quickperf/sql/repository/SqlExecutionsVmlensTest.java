/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2019-2022 the original author or authors.
 */
package org.quickperf.sql.repository;

import com.vmlens.api.AllInterleavings;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import org.junit.jupiter.api.Test;
import org.quickperf.sql.SqlExecution;
import org.quickperf.sql.SqlExecutions;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vmlens (happens-before data-race) scenario for {@link SqlExecutions} via
 * {@link SqlMemoryRepository} (this test is in the repository package because
 * {@code SqlMemoryRepository} is package-private — the same arrangement as the pilot driver),
 * adapted from the standalone pilot driver of 2026-11-02
 * ({@code org.quickperf.sql.repository.RecordingDriver}).
 *
 * <p>Two threads times three {@code addQueryExecution} calls with unique statement markers per
 * interleaving. The shared state under test is the plain {@code java.util.ArrayDeque} inside
 * {@code SqlExecutions} ({@code SqlExecutions.java:32}): the pilot flagged a data race on
 * {@code ArrayDeque.tail} (read/write at {@code ArrayDeque.addLast}) from both recording
 * threads, and stayed silent on a {@code ConcurrentLinkedDeque} variant. The scenario is kept
 * small on purpose: vmlens drops racing operations performed in large loops from the trace
 * and then reports a false "Success".
 *
 * <p>The race verdict lives in the generated report ({@code target/reports/vmlens}); this test
 * asserts the behavioral invariant the pilot observed in every explored interleaving — exactly
 * six distinct executions recorded — plus that interleavings were explored. A data-race flag
 * on the current production code is the <em>expected</em> result, not a test failure: no
 * production code is changed by this harness.
 *
 * <p>This source root is not part of the default build. See {@code src/vmlens-test/README.md}.
 */
class SqlExecutionsVmlensTest {

    private static final int THREADS = 2;
    private static final int PER_THREAD = 3;

    @Test
    void concurrentRecordingRacesOnArrayDeque() throws Exception {
        int iterations = 0;
        try (AllInterleavings all = new AllInterleavings("qp-recording")) {
            while (all.hasNext()) {
                iterations++;
                assertEquals("OK", runOnce(), "interleaving " + iterations);
            }
        }
        assertTrue(iterations > 1, "vmlens must explore more than one interleaving");
    }

    private static String runOnce() throws Exception {
        SqlMemoryRepository repository = new SqlMemoryRepository();
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int threadIndex = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < PER_THREAD; i++) {
                    ExecutionInfo info = new ExecutionInfo();
                    info.setSuccess(true);
                    info.setStatementType(StatementType.STATEMENT);
                    info.setDataSourceName("vmlens");
                    info.setConnectionId("vmlens");
                    repository.addQueryExecution(info, Collections.singletonList(
                            new QueryInfo("INSERT INTO book (id) VALUES ("
                                    + (threadIndex * PER_THREAD + i) + ")")));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        int expected = THREADS * PER_THREAD;
        SqlExecutions recorded = repository.findExecutedQueries(null);
        int counted = recorded.getNumberOfExecutions();
        if (counted != expected) {
            return "LOST-OR-CORRUPT count=" + counted + " expected=" + expected;
        }
        Set<String> seen = new HashSet<>();
        int iterated = 0;
        try {
            for (SqlExecution execution : recorded) {
                if (execution == null) {
                    return "NULL-HOLE at iteration " + iterated;
                }
                iterated++;
                for (QueryInfo query : execution.getQueries()) {
                    seen.add(query.getQuery());
                }
            }
        } catch (Throwable throwable) {
            return "ITERATION-THREW " + throwable;
        }
        if (iterated != expected || seen.size() != expected) {
            return "MISMATCH iterated=" + iterated + " distinct=" + seen.size()
                    + " expected=" + expected;
        }
        return "OK";
    }

}
