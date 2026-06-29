# TestPulse

[![CI](https://github.com/OWNER/testpulse-libs/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/testpulse-libs/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-orange)](https://adoptium.net/)

API-driven test reporting library. Send your test runs, scenarios, and steps
to a TestPulse server via HTTP; view results on the dashboard in real time.
Modular by design — one small framework-agnostic core plus optional
integration modules for Cucumber, TestNG, and JUnit.

> **Status:** alpha (0.1.0-SNAPSHOT). The public API is stabilising; expect
> breaking changes until 1.0.0.

## Modules

| Module                  | Status   | What it does                                      |
| ----------------------- | -------- | ------------------------------------------------- |
| `testpulse-core`        | ✅ Built  | Public API, HTTP client, async dispatch          |
| `testpulse-cucumber3`   | Planned  | Cucumber 3 hooks + AspectJ step interception     |
| `testpulse-testng`      | Planned  | Suite + class lifecycle listeners                 |
| `testpulse-junit4`      | Planned  | `@ClassRule` for sequential runners               |
| `testpulse-bom`         | Planned  | Version coordination for consumers                |

## Installing

Once published to GitHub Packages, add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.testpulse</groupId>
    <artifactId>testpulse-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

You'll need a `~/.m2/settings.xml` with GitHub Packages credentials — see
[CONTRIBUTING.md](CONTRIBUTING.md#installing-from-github-packages).

## Quick start

The explicit API works with any test framework (or none at all). The
auto-wire modules (once available) wrap this same API behind framework hooks
so you don't write any of it.

```java
import com.testpulse.*;
import java.time.Duration;

// 1. Initialise once at suite start
TestPulse.init(TestPulseConfig.builder()
    .enabled(true)
    .url("http://testpulse.internal:8080")
    .apiKey(System.getenv("TESTPULSE_API_KEY"))
    .division("Europe")
    .release("R2025.04")
    .user(System.getProperty("user.name"))
    .build());

// 2. Start a run
Run run = TestPulse.startRun();

// 3. Start a scenario, log steps, finish
Scenario s = run.startScenario("Login flow")
    .withLob("Casualty")
    .withTags("@smoke");

s.logStep("Given I am on login page",  Status.PASSED, Duration.ofMillis(412));
s.logStep("When I submit credentials", Status.PASSED, Duration.ofMillis(640));
s.logStep("Then dashboard appears",    Status.FAILED, Duration.ofMillis(95), throwable);
s.finish();   // rolls up: any FAILED step → scenario FAILED

// 4. Finalise the run at suite end
run.finish();
TestPulse.shutdown();
```

## Configuration

Compose configuration from any combination of sources, each overlaying on top
of the previous:

```java
TestPulseConfig config = TestPulseConfig.builder()
    .url("http://default.example.com")            // programmatic defaults
    .environment("QA")
    .mergeClasspath("testpulse.properties")       // classpath overrides
    .mergeEnvironment()                            // TESTPULSE_URL etc.
    .mergeSystemProperties()                       // -Dtestpulse.url=…
    .build();
```

The property-to-env-var mapping is mechanical: `testpulse.url` →
`TESTPULSE_URL`. Every setting available on the builder is available from
every source.

## Building from source

Java 8+ and Maven 3.6+ required. The build targets Java 8 bytecode
regardless of the JDK version you build with.

```bash
git clone https://github.com/OWNER/testpulse-libs.git
cd testpulse-libs
mvn clean install
```

Artifacts land under `testpulse-core/target/`:

```
testpulse-core-0.1.0-SNAPSHOT.jar           # the library
testpulse-core-0.1.0-SNAPSHOT-sources.jar   # source code for IDE navigation
testpulse-core-0.1.0-SNAPSHOT-javadoc.jar   # API docs
```

`mvn install` also copies them to your local Maven repo so other projects on
the same machine can depend on them immediately.

## Running tests

Unit tests run with the default build:

```bash
mvn test
```

Integration tests need a real TestPulse server and are skipped automatically
when the environment isn't set:

```bash
mvn verify \
    -DTESTPULSE_URL=http://your-server:8080 \
    -DTESTPULSE_API_KEY=YOUR_KEY
```

## Server endpoints

The library calls these endpoints on the TestPulse server:

```
POST  /api/runs                                          → { runId }
POST  /api/runs/{runId}/scenarios                        → { scenarioId }
POST  /api/runs/{runId}/scenarios/{scenarioId}/steps     → 200/204
PATCH /api/runs/{runId}/scenarios/{scenarioId}/finish    → 200/204
PATCH /api/runs/{runId}/finalize                         → 200/204
```

All requests authenticate with the `X-Api-Key` header. Payload shapes are in
[`com.testpulse.internal.dto.Dtos`](testpulse-core/src/main/java/com/testpulse/internal/dto/Dtos.java) —
field names are the JSON keys on the wire.

## Documentation

- **[CONTRIBUTING.md](CONTRIBUTING.md)** — building, testing, code style, PRs
- **[CHANGELOG.md](CHANGELOG.md)** — version history
- **[LICENSE](LICENSE)** — Apache 2.0

## Roadmap

Next release will add `testpulse-cucumber3` and `testpulse-testng` so existing
Cucumber 3 + TestNG frameworks can adopt the library by adding a dependency
and setting two system properties — no Java code changes required.

After that, `testpulse-junit4` for sequential runners, then a BOM module for
version coordination. Cucumber 4/5/6 modules land as adoption demand
materialises.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
