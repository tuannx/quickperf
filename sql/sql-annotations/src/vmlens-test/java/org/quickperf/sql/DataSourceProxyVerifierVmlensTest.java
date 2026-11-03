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
 * vmlens (happens-before data-race) scenario for {@link DataSourceProxyVerifier}, adapted from
 * the standalone pilot driver of 2026-11-02 ({@code pilot.VerifierDriver}).
 *
 * <p>Two threads race {@code addListenerIdentifier(111)} against {@code addListenerIdentifier(222)}
 * on a fresh verifier per interleaving. The race lives on the verifier's own plain field
 * {@code listenerIdentifier} (read at {@code DataSourceProxyVerifier.java:24}, read/written by the
 * check-then-act at line 31): a textbook application-level field race with no synchronization.
 * The pilot flagged it as a data race on the first interleaving and stayed silent on a
 * synchronized variant.
 *
 * <p>The race verdict lives in the generated report ({@code target/reports/vmlens}); this test
 * asserts that interleavings were explored. A flag on the current production code is the
 * <em>expected</em> result, not a test failure: no production code is changed by this harness.
 *
 * <p>This source root is not part of the default build. See {@code src/vmlens-test/README.md}.
 */
class DataSourceProxyVerifierVmlensTest {

    @Test
    void twoListenerIdentifiersRaceOnPlainField() throws Exception {
        int iterations = 0;
        try (AllInterleavings all = new AllInterleavings("qp-verifier")) {
            while (all.hasNext()) {
                iterations++;
                DataSourceProxyVerifier verifier = new DataSourceProxyVerifier();
                Thread first = new Thread(() -> verifier.addListenerIdentifier(111));
                Thread second = new Thread(() -> verifier.addListenerIdentifier(222));
                first.start();
                second.start();
                first.join();
                second.join();
            }
        }
        assertTrue(iterations > 1, "vmlens must explore more than one interleaving");
    }

}
