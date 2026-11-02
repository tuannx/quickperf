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

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fray controlled-scheduling test for {@link DataSourceProxyVerifier}.
 *
 * <p>Every recorder calls {@code addListenerIdentifier} from
 * {@code addQueryExecution}, that is on the thread that executed a query. When two datasource
 * proxies record the same test from two threads, the verifier must conclude that several proxies
 * were built. Its state is a plain {@code int} and a plain {@code boolean} updated with a
 * check-then-act sequence and no synchronization: if both threads read the initial {@code 0}
 * identifier before either writes, the second write overwrites the first one and each thread then
 * compares the stored identifier with its own, so the "several proxies" situation can be missed.
 *
 * <p>This source root is not part of the default build. See {@code src/fray-test/README.md}.
 */
@ExtendWith(FrayTestExtension.class)
class DataSourceProxyVerifierConcurrencyFrayTest {

    @FrayTest(iterations = 1000)
    void twoDataSourceProxiesAreDetected() throws Exception {
        DataSourceProxyVerifier verifier = new DataSourceProxyVerifier();
        CountDownLatch startGate = new CountDownLatch(1);
        Thread firstProxy = new Thread(() -> {
            await(startGate);
            verifier.addListenerIdentifier(111);
        });
        Thread secondProxy = new Thread(() -> {
            await(startGate);
            verifier.addListenerIdentifier(222);
        });
        firstProxy.start();
        secondProxy.start();
        startGate.countDown();
        firstProxy.join();
        secondProxy.join();

        assertTrue(verifier.hasQuickPerfBuiltSeveralDataSourceProxies(),
                "two distinct datasource-proxy listeners must be detected as several proxies");
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
