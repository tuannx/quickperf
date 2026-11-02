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

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;
import org.quickperf.sql.SqlExecution;
import org.quickperf.sql.SqlExecutions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Fray controlled-scheduling test for the SQL recording path.
 *
 * <p>{@code DataSourceQuickPerfListener.afterQuery} is invoked by datasource-proxy on the thread
 * that executed the query, so when the code under test runs SQL from several threads (web request
 * threads, parallel streams, {@code @Async} executors, ...), {@link SqlMemoryRepository} and the
 * {@link SqlExecutions} instance it holds are written concurrently. {@code SqlExecutions} stores
 * executions in a plain {@link java.util.ArrayDeque}, which is not thread-safe: concurrent
 * {@code addLast} calls can lose statements or corrupt the deque.
 *
 * <p>This test records statements from several threads at once and then checks that every
 * statement was recorded exactly once. Fray explores thread interleavings systematically, so a
 * losing interleaving does not depend on lucky timing.
 *
 * <p>This source root is not part of the default build. See {@code src/fray-test/README.md}.
 */
@ExtendWith(FrayTestExtension.class)
class SqlRecordingConcurrencyFrayTest {

    private static final int WRITER_THREADS = 3;

    private static final int EXECUTIONS_PER_THREAD = 10;

    @FrayTest(iterations = 1000)
    void concurrentRecordingLosesNoStatement() throws Exception {
        SqlMemoryRepository repository = new SqlMemoryRepository();
        CountDownLatch startGate = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < WRITER_THREADS; t++) {
            final int threadIndex = t;
            Thread writer = new Thread(() -> {
                await(startGate);
                for (int i = 0; i < EXECUTIONS_PER_THREAD; i++) {
                    repository.addQueryExecution(newExecutionInfo(),
                            singletonList(new QueryInfo(insertStatement(threadIndex, i))));
                }
            });
            writer.start();
            writers.add(writer);
        }
        startGate.countDown();
        for (Thread writer : writers) {
            writer.join();
        }

        int expectedExecutions = WRITER_THREADS * EXECUTIONS_PER_THREAD;
        SqlExecutions recorded = repository.findExecutedQueries(null);
        assertEquals(expectedExecutions, recorded.getNumberOfExecutions(),
                "every concurrently recorded execution must be kept");
        Set<String> recordedStatements = new HashSet<>();
        for (SqlExecution execution : recorded) {
            assertNotNull(execution, "the recording must not contain holes");
            for (QueryInfo query : execution.getQueries()) {
                recordedStatements.add(query.getQuery());
            }
        }
        assertEquals(expectedExecutions, recordedStatements.size(),
                "every statement must be recorded exactly once");
    }

    private static String insertStatement(int threadIndex, int sequence) {
        return "INSERT INTO book (id) VALUES (" + (threadIndex * 1000 + sequence) + ")";
    }

    private static ExecutionInfo newExecutionInfo() {
        ExecutionInfo executionInfo = new ExecutionInfo();
        executionInfo.setSuccess(true);
        executionInfo.setStatementType(StatementType.STATEMENT);
        executionInfo.setDataSourceName("fray");
        executionInfo.setConnectionId("fray");
        return executionInfo;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

}
