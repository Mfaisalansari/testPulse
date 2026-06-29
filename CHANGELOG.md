# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial `testpulse-core` module: public API (`TestPulse`, `Run`, `Scenario`, `Status`, `TestPulseConfig`)
- Synchronous HTTP client for create/start/finish/finalize operations
- Async dispatcher with bounded queue, exponential backoff retry, and JSONL fallback for offline replay
- Multi-source configuration: programmatic builder, classpath properties, environment variables, system properties
- Integration smoke test (`SmokeIT`) that exercises the full public API against a real server
- Apache 2.0 license, Java 8 baseline, no test-framework dependencies in core

### Planned for next minor version
- `testpulse-cucumber3` module: Cucumber 3 hooks and AspectJ step interceptor
- `testpulse-testng` module: suite + class lifecycle listeners
- `testpulse-junit4` module: ClassRule for sequential runners
- `testpulse-bom` module: version coordination for consumers

[Unreleased]: https://github.com/OWNER/testpulse-libs/compare/v0.1.0...HEAD
