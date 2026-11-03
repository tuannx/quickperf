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
package org.quickperf.sql;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;
import org.quickperf.TestExecutionContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Fray controlled-scheduling test for {@link SqlRecorderRegistry} when test code executes in a
 * dedicated JVM (system property {@code quickPerfToExecInASpecificJvm=true}). In this mode the
 * registry stores recorders in a plain {@link java.util.HashMap} shared by every thread, and
 * {@code getSqlRecorders()} returns a live view of that map.
 *
 * <p>One thread plays the test lifecycle (register / clear, as recorders do in
 * {@code startRecording} and between tests) while another thread plays
 * {@code DataSourceQuickPerfListener.afterQuery}, which iterates the returned collection on the
 * thread that executed a query. Iterating a live {@code HashMap} view while another thread
 * mutates the map can throw {@link java.util.ConcurrentModificationException} or observe a
 * corrupted map.
 *
 * <p>This source root is not part of the default build. See {@code src/fray-test/README.md}.
 */
@ExtendWith(FrayTestExtension.class)
class SqlRecorderRegistryConcurrencyFrayTest {

    private static final int OPERATIONS = 300;

    @FrayTest(iterations = 200)
    void registrationWhileListenerIterates() throws Exception {
        System.setProperty("quickPerfToExecInASpecificJvm", "true");
        SqlRecorderRegistry registry = SqlRecorderRegistry.INSTANCE;
        registry.clear();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SqlRecorder<SqlExecutions> recorder = new NoOpSqlRecorder();

        Thread lifecycle = new Thread(() -> {
            try {
                for (int i = 0; i < OPERATIONS; i++) {
                    registry.register(recorder);
                    registry.clear();
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        Thread listener = new Thread(() -> {
            try {
                for (int i = 0; i < OPERATIONS; i++) {
                    for (SqlRecorder<?> sqlRecorder : registry.getSqlRecorders()) {
                        sqlRecorder.hashCode();
                    }
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        lifecycle.start();
        listener.start();
        lifecycle.join();
        listener.join();
        registry.clear();

        assertNull(failure.get(),
                () -> "concurrent registry access must not fail, but got: " + failure.get());
    }

    private static final class NoOpSqlRecorder implements SqlRecorder<SqlExecutions> {

        @Override
        public void startRecording(TestExecutionContext testExecutionContext) {
        }

        @Override
        public void addQueryExecution(ExecutionInfo execInfo, List<QueryInfo> queries,
                                      int listenerIdentifier) {
        }

        @Override
        public void stopRecording(TestExecutionContext testExecutionContext) {
        }

        @Override
        public SqlExecutions findRecord(TestExecutionContext testExecutionContext) {
            return SqlExecutions.NONE;
        }

        @Override
        public void cleanResources() {
        }

    }

}
