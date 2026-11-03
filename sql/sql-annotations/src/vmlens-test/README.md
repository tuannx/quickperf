# vmlens data-race tests (opt-in, pass 2)

This directory contains [vmlens](https://github.com/vmlens/vmlens) tests for the SQL
recording path. vmlens records field accesses and synchronization actions while a test
runs under its agent and checks their happens-before ordering, which makes it the
memory-model complement of the Fray harness (fork PR #1, `src/fray-test`): Fray controls
the thread schedule at synchronization points — and therefore cannot reach these
plain-memory races, as measured in that PR — while vmlens judges the accesses themselves.
The two passes are independent and their agents must never run in the same JVM, so this
pass has its own source root, its own Maven profile, and its own agent installation.

The tests target the same three pieces of shared state as the Fray pass, written on the
threads executing the SQL queries of the code under test:

| Test | Component under test | Pilot result on current code (2026-11-02) |
| --- | --- | --- |
| `org.quickperf.sql.repository.SqlExecutionsVmlensTest` | `SqlMemoryRepository` / `SqlExecutions` (`java.util.ArrayDeque`, `SqlExecutions.java:32`) | **Data race** on `ArrayDeque.tail` |
| `org.quickperf.sql.SqlRecorderRegistryVmlensTest` | `SqlRecorderRegistry` in the dedicated-JVM mode (`java.util.HashMap`, `SqlRecorderRegistry.java:25`) | **Data race** (`HashMap$ValueIterator.next` vs `HashMap.putVal`) |
| `org.quickperf.sql.DataSourceProxyVerifierVmlensTest` | `DataSourceProxyVerifier` (plain `listenerIdentifier` field, lines 24/31) | **Data race** on `listenerIdentifier` |
| `org.quickperf.sql.VmlensPositiveControlTest` | canonical unsynchronized `value++` counter | **Data race** (required — see below) |

The scenarios are adapted from the standalone pilot drivers that produced the results
above (vmlens 1.2.28, standalone + `AllInterleavings` API). In the same pilot, each
scenario stayed silent ("Success") on a thread-safe variant of its target
(`ConcurrentLinkedDeque`, `ConcurrentHashMap`, synchronized verifier), so the harness
discriminates both ways.

## Build wiring

The tests are **not** part of the default build. The `vmlens` profile in
`sql/sql-annotations/pom.xml` (activate with `-Pvmlens`) adds this source root as a test
source root, adds `com.vmlens:api:1.2.28` (plus the `standalone` artifact used to install
the agent and generate the report), restricts surefire to the `*VmlensTest` classes, and
runs surefire with `-javaagent:target/vmlens-agent/agent.jar`. Without the profile the
build is unchanged and no vmlens artifact is resolved. Requires JDK 11+.

## Running

```sh
mvn -Pvmlens -pl sql/sql-annotations -am verify
```

Phases, mirroring the `installVmlensAgent` / `vmlensCheck` / `vmlensReport` tasks of the
ServiceTalk harness:

1. `generate-test-resources` — `maven-dependency-plugin` copies the vmlens standalone jar
   to `target/vmlens-standalone/`.
2. `process-test-classes` — `exec-maven-plugin` runs `standalone install`, creating
   `target/vmlens-agent/`.
3. `test` — surefire runs only the `*VmlensTest` classes, with the agent attached.
4. `verify` — `exec-maven-plugin` runs `standalone report`, writing the report to
   `target/reports/vmlens/` (`index.html` gives the per-run verdict).

## How to read the results — the verdict is in the report, not in the test task

A detector's finding is a trace to review, not an assertion, so the tests do **not** fail
when vmlens finds a race. They assert that interleavings were explored (and, for
`SqlExecutions`, the behavioral invariant the pilot observed in every interleaving). The
race verdict lives in `target/reports/vmlens/index.html`: **Data race** or **Success**
per run.

On the current production code the expected report outcome is **Data race for all three
targets and for the positive control**. That is the harness working as intended — it
reproduces, inside the build, the findings already proven by stress testing (statement
loss in 6/6 rounds, `ConcurrentModificationException` on registry iteration, 2/200,000
missed two-proxies detections) and by the standalone vmlens pilot. No production code is
changed by this harness; fixing the three components would flip their runs to "Success"
and turn these scenarios into regression tests.

Two operating rules from the pilot are mandatory:

* **Scenarios stay small.** vmlens drops racing operations performed in large loops from
  the trace (loop threshold) and then reports a false "Success" — the pilot's first
  registry attempt (50 operations per thread) produced a trace with zero `HashMap`
  events. That is why the registry scenario is exactly one `register` racing one full
  iteration; do not "strengthen" it back into a loop.
* **The positive control gates every "Success".** A "Success" report is only trustworthy
  if the control scenario (`VmlensPositiveControlTest`, a plain unsynchronized counter)
  is flagged in the same run. If the control is not flagged, treat every result of the
  run as unverified.

Do not activate `-Pfray` and `-Pvmlens` in the same build: the two agents must not share
a JVM.
