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

import com.vmlens.api.AllInterleavings;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.quickperf.TestExecutionContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vmlens (happens-before data-race) scenario for {@link SqlRecorderRegistry} in the
 * dedicated-JVM mode, adapted from the standalone pilot driver of 2026-11-02
 * ({@code pilot.RegistryDriverMinimal}).
 *
 * <p>The scenario is deliberately minimal: <strong>one</strong> {@code register} racing
 * <strong>one</strong> full iteration of {@code getSqlRecorders()} per interleaving, with no
 * main-thread pre-touch of the registry inside the scenario. The pilot's first attempt kept
 * the stress shape (50 operations per thread) and produced a false "Success": the trace
 * contained zero {@code HashMap} events because vmlens drops racing operations performed in
 * large loops from the trace (loop threshold). Do not "strengthen" this scenario back into a
 * loop.
 *
 * <p>The shared state is the plain {@code java.util.HashMap} at
 * {@code SqlRecorderRegistry.java:25}: the pilot flagged a data race between
 * {@code HashMap$ValueIterator.next} (listener thread iterating) and {@code HashMap.putVal}
 * (lifecycle thread registering) — the pair behind the {@code ConcurrentModificationException}
 * seen under stress — and stayed silent on a {@code ConcurrentHashMap} variant.
 *
 * <p>The race verdict lives in the generated report ({@code target/reports/vmlens}); this test
 * asserts that interleavings were explored and swallows per-interleaving throwables the same
 * way the pilot driver counted them, because a thrown {@code ConcurrentModificationException}
 * is a symptom of the race under study, not a harness failure. A flag on the current production
 * code is the <em>expected</em> result: no production code is changed by this harness.
 *
 * <p>This source root is not part of the default build. See {@code src/vmlens-test/README.md}.
 */
class SqlRecorderRegistryVmlensTest {

    @BeforeAll
    static void enableDedicatedJvmMode() {
        System.setProperty("quickPerfToExecInASpecificJvm", "true");
    }

    @AfterAll
    static void restoreMode() {
        SqlRecorderRegistry.INSTANCE.clear();
        System.clearProperty("quickPerfToExecInASpecificJvm");
    }

    @Test
    void oneRegisterRacesOneFullIteration() throws Exception {
        int iterations = 0;
        SqlRecorder<SqlExecutions> recorder = new NoOpSqlRecorder();
        try (AllInterleavings all = new AllInterleavings("qp-registry-minimal")) {
            while (all.hasNext()) {
                iterations++;
                SqlRecorderRegistry registry = SqlRecorderRegistry.INSTANCE;
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread lifecycle = new Thread(() -> {
                    try {
                        registry.register(recorder);
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                });
                Thread listener = new Thread(() -> {
                    try {
                        for (SqlRecorder<?> sqlRecorder : registry.getSqlRecorders()) {
                            sqlRecorder.hashCode();
                        }
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                });
                lifecycle.start();
                listener.start();
                lifecycle.join();
                listener.join();
            }
        }
        assertTrue(iterations > 1, "vmlens must explore more than one interleaving");
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
