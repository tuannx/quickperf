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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positive control for the vmlens pass (see the {@code vmlens} profile in this module's
 * {@code pom.xml}): the canonical unsynchronized counter increment from the vmlens tutorial,
 * which any working vmlens setup must flag as a data race in the generated report
 * ({@code target/reports/vmlens}). A "Success" verdict for any other test in this source root
 * is only trustworthy if this control is flagged in the same run: vmlens silently reports
 * success when a scenario is too large for its loop threshold and the racing operations never
 * reach the trace, so every scenario here is kept minimal on purpose.
 *
 * <p>The race verdict lives in the report, not in this test's assertions: this test asserts
 * that interleavings were explored. See {@code src/vmlens-test/README.md}.
 *
 * <p>This source root is not part of the default build.
 */
class VmlensPositiveControlTest {

    @Test
    void plainCounterIncrementAcrossThreadsExploresMultipleInterleavings() throws Exception {
        int iterations = 0;
        try (AllInterleavings all = new AllInterleavings("vmlens-positive-control")) {
            while (all.hasNext()) {
                iterations++;
                Counter counter = new Counter();
                Thread first = new Thread(counter::inc);
                Thread second = new Thread(counter::inc);
                first.start();
                second.start();
                first.join();
                second.join();
            }
        }
        assertTrue(iterations > 1,
                "vmlens must explore more than one interleaving for the control to be meaningful");
    }

    private static final class Counter {
        private int value;

        void inc() {
            value++;
        }
    }

}
