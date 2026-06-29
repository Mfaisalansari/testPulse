# Contributing to TestPulse

Thank you for considering a contribution. This document covers the practical
steps; please open an issue first if you're planning a substantial change so
we can align on direction before you invest time in code.

## Building

Java 8+ and Maven 3.6+ required. The project compiles to Java 8 bytecode
regardless of the JDK version you build with.

```bash
git clone https://github.com/Mfaisalansari/testPulse.git
cd testPulse
mvn clean install
```

This produces three artifacts per module under `target/`:

- The main jar
- A sources jar (for IDE navigation)
- A javadoc jar

## Running tests

Unit tests run automatically with the build:

```bash
mvn test
```

Integration tests need a real TestPulse server. They're skipped automatically
when the required environment variables aren't set, so the default build is
self-contained:

```bash
mvn verify \
    -DTESTPULSE_URL=http://your-server:8080 \
    -DTESTPULSE_API_KEY=YOUR_KEY
```

## Project structure

```
testPulse/
├── pom.xml                          parent POM
├── testpulse-core/                  framework-agnostic API + HTTP + dispatch
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/testpulse/
│       │   ├── *.java               public API
│       │   └── internal/            implementation detail, do not depend on
│       └── test/java/com/testpulse/
│           └── SmokeIT.java         integration test against real server
├── testpulse-cucumber3/             (planned) Cucumber 3 integration
├── testpulse-testng/                (planned) TestNG integration
└── testpulse-junit4/                (planned) JUnit 4 integration
```

## Code style

The project ships an `.editorconfig` — any modern IDE will pick it up. The
essentials: 4-space indent for Java, LF line endings, UTF-8 encoding, trailing
whitespace trimmed.

A few conventions specific to this codebase:

- Public API classes live in `com.testpulse`; implementation details live in
  `com.testpulse.internal.*` and should not be referenced from outside the
  module. Reviewers will push back on PRs that leak internal types.
- Static state is fine in the `TestPulse` facade (it's the entry point) but
  rare elsewhere. Most classes take their dependencies via constructor.
- All public methods need javadoc; internal methods need it only when the
  intent isn't obvious from the name and signature.
- Java 8 baseline: no records, no `var`, no text blocks. The `module-info` and
  multi-release jar paths are deliberately not used.

## Testing changes

A change touching the public API needs:

1. Updated javadoc explaining the new behavior
2. A new or updated entry in `CHANGELOG.md` under `[Unreleased]`
3. Either a unit test or an integration test exercising the change
4. Confirmation that `mvn clean verify` passes against a real server

A change to internal classes typically needs only the test.

## Commit messages

Short subject (≤72 chars) describing what the commit does, in present tense
imperative: "Add fallback file flush on shutdown" not "Added fallback file
flush" or "Adding fallback...". Body is optional but appreciated for
non-obvious changes.

## Releasing

Release process lives in `.github/workflows/publish.yml`. Cutting a release
is: create a GitHub release with a tag like `v0.2.0`, the workflow publishes
to GitHub Packages automatically. See the workflow file for full details.

## Questions

Open an issue with the `question` label. For private matters, contact the
maintainers directly through GitHub.
