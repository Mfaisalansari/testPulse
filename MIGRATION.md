# Migration guide

Step-by-step walkthrough for adopting TestPulse in an existing
Cucumber 3 + TestNG framework. The same general flow works for
Cucumber + JUnit and pure JUnit/TestNG — variations called out as we go.

## Prerequisites

You need these reachable from where you'll run tests:

- Your TestPulse server (and its API key)
- Java 8+, Maven 3.6+
- Your existing test framework, working today

## Step 1 — Build TestPulse jars locally

```bash
git clone https://github.com/Mfaisalansari/testPulse.git
cd testPulse
mvn clean install
```

First run takes ~3 minutes; subsequent runs ~30 seconds.

**Verify:**

```bash
ls ~/.m2/repository/com/testpulse/
```

You should see all five artifact IDs.

## Step 2 — Add dependencies to your existing framework

In your existing framework's `pom.xml`:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.testpulse</groupId>
            <artifactId>testpulse-bom</artifactId>
            <version>0.1.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.testpulse</groupId>
        <artifactId>testpulse-cucumber3</artifactId>
    </dependency>
    <dependency>
        <groupId>com.testpulse</groupId>
        <artifactId>testpulse-testng</artifactId>
    </dependency>
</dependencies>
```

`testpulse-core` is pulled transitively. For Cucumber+JUnit instead of
TestNG, swap `testpulse-testng` for `testpulse-junit4`.

**Verify:**

```bash
mvn dependency:tree | grep testpulse
```

## Step 3 — Migrate one runner

Pick one (e.g. `CasualtyRunner`) as the pilot. Leave the others on the old
base class — they keep working unchanged.

**Before:**

```java
@CucumberOptions(
    features = "src/test/resources/features/casualty",
    glue = {"com.coforge.steps"}
)
public class CasualtyRunner extends AbstractTestNGCucumberTests {
}
```

**After:**

```java
import com.testpulse.testng.BaseRunner;
import cucumber.api.CucumberOptions;

@CucumberOptions(
    features = "src/test/resources/features/casualty",
    glue = {"com.coforge.steps", "com.testpulse.cucumber3"}
)
public class CasualtyRunner extends BaseRunner {
    @Override
    protected String getLob() {
        return "Casualty";
    }
}
```

**Verify:**

```bash
mvn -DskipTests clean compile test-compile
```

## Step 4 — First test run

```bash
mvn test \
    -Dtest=CasualtyRunner \
    -Dtestpulse.enabled=true \
    -Dtestpulse.url=http://testpulse.internal:8080 \
    -Dtestpulse.apiKey=YOUR_KEY \
    -Dtestpulse.division=Europe \
    -Dtestpulse.release=R2025.04 \
    -Dtestpulse.user=$(whoami)
```

Look for these log lines during the run:

```
INFO: TestPulse run created: r_xxxxxxxxxx
INFO: AsyncDispatcher started, queue capacity 10000
...
INFO: TestPulse run finalized: r_xxxxxxxxxx
```

## Step 5 — Verify on the dashboard

Open your TestPulse dashboard URL. The most recent run should show:

- LOB = Casualty
- Division, Release, User from the `-D` properties
- Status = Running, then Passed/Failed
- Every scenario from the Casualty feature pack
- Failure stack traces visible on failed scenarios

You will NOT see per-step detail yet — that's Step 7.

## Step 6 — Roll out to remaining runners

Repeat Step 3 for each remaining LOB runner, one per fortnight aligned with
your regression cycle. Each migration is mechanical:

1. Change `extends AbstractTestNGCucumberTests` → `extends BaseRunner`
2. Add `"com.testpulse.cucumber3"` to the `glue` array
3. Implement `getLob()` returning the LOB

Code-review, smoke-test, merge.

## Step 7 (optional) — Add step-level detail with AspectJ

```bash
find ~/.m2/repository/org/aspectj -name "aspectjweaver-*.jar"
```

In your framework's `pom.xml`:

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>-javaagent:${settings.localRepository}/org/aspectj/aspectjweaver/1.9.7/aspectjweaver-1.9.7.jar</argLine>
    </configuration>
</plugin>
```

If your step definitions don't live in a package matching `*..steps..*` or
`*..stepdefs..*`, add `src/test/resources/META-INF/aop.xml`:

```xml
<aspectj>
    <weaver>
        <include within="com.coforge.automation..*"/>
    </weaver>
</aspectj>
```

AspectJ merges this with what we ship. First run after enabling is
30-60% slower (weaver warm-up); subsequent runs return to normal speed.

## Step 8 (optional) — Wire screenshot capture

Failed scenarios appear without a thumbnail until you tell the library
how to capture one. Add this once during framework startup (e.g. in your
existing driver-init `@Before` hook):

```java
import com.testpulse.cucumber3.Cucumber3Config;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;

Cucumber3Config.setScreenshotProvider(() ->
    ((TakesScreenshot) DriverFactory.getDriver()).getScreenshotAs(OutputType.BYTES));
```

Replace `DriverFactory.getDriver()` with however your framework exposes
the active WebDriver for the current thread.

## Variation — Sequential (JUnit) runners

If you use `@RunWith(Cucumber.class)` for a smoke pack, swap
`testpulse-testng` for `testpulse-junit4` in Step 2, and adapt the runner
class in Step 3:

```java
@RunWith(Cucumber.class)
@CucumberOptions(
    features = "src/test/resources/features/smoke",
    glue = {"com.coforge.steps", "com.testpulse.cucumber3"}
)
public class SmokeRunner {
    @ClassRule
    public static final TestPulseRule REPORTING = TestPulseRule.forLob("Smoke");
}
```

Everything else (Step 4 onward) is the same.

## Variation — Explicit API (no auto-wire)

For pure JUnit or pure TestNG without Cucumber, skip the integration
modules and use the facade directly:

```java
TestPulse.init(TestPulseConfig.builder()
    .enabled(true)
    .url("http://testpulse.internal:8080")
    .apiKey("YOUR_KEY")
    .division("Europe")
    .release("R2025.04")
    .build());

Run run = TestPulse.startRun();
Scenario s = run.startScenario("Manual test").withLob("Casualty");
s.logStep("Given I do something", Status.PASSED, Duration.ofMillis(100));
s.finish();
run.finish();
TestPulse.shutdown();
```

## Troubleshooting

**"TestPulse run created" never appears.** Surefire might not be
forwarding system properties. Verify with `mvn help:system | grep testpulse`
in the same shell.

**"Failed to create run" with HTTP error.** Wrong URL or API key. Test
with `curl`:

```bash
curl -X POST -H "X-Api-Key: YOUR_KEY" \
    -H "Content-Type: application/json" \
    -d '{}' \
    http://testpulse.internal:8080/api/runs
```

**Scenarios appear but `lob: null`.** Your runner doesn't actually extend
`com.testpulse.testng.BaseRunner`, or another `@BeforeClass` is firing
first and overwriting context. Verify with a debugger or temp log.

**AspectJ activated but no steps appear.** Step packages don't match the
default `aop.xml` scope. Add your own `aop.xml` with explicit
`<include within="your.actual.package..*"/>`.

**Tests permanently slow after AspectJ.** Scope too broad. Check the
`<include within>` rules — overly broad scope weaves Selenium and
Cucumber internals.

**Pega iframe timeouts misreport.** Some Pega steps take 30+ seconds
(frame switches, harness reloads). Increase
`-Dtestpulse.readTimeoutMs=30000` and `-Dtestpulse.queueCapacity=20000`.

**Multiple parallel runners create duplicate Runs.** Each Surefire fork
is its own JVM; each tries to create a Run. Disable surefire forks
(`<forkCount>0</forkCount>`) — TestNG's `parallel="classes"` gives you
the parallelism without forks.

**`@Before` order conflicts.** Reporting hook fires at `order=0`. Your
own setup hooks should be at `order >= 1`. If your existing hook is at
`order=0`, change it to `1` to preserve our ordering invariant.

## Rolling back

To disable reporting without removing the dependencies:

```bash
mvn test                          # no -D properties → reporting silent
mvn test -Dtestpulse.enabled=false   # explicit disable
```

To remove entirely: revert the pom changes and the runner changes
(restore `extends AbstractTestNGCucumberTests`, remove
`com.testpulse.cucumber3` from glue, delete `getLob()`).
