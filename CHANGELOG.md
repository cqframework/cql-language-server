# Change Log

## v4.5.0

Date: 2026-04-08

### Module Consolidation

Collapses the six-module Maven layout into a single module (`ls/server`) that produces
the fat JAR directly.

* Deleted modules: `core/`, `debug/server/`, `debug/service/`, `plugin/debug/`, `ls/service/`
* Removed `CqlLanguageServerPlugin`, `CqlLanguageServerPluginFactory`, `DebugPlugin`, and
  `DebugPluginFactory` — ServiceLoader plugin discovery replaced with direct wiring
* `DebugCommandContribution` wired directly in `main.kt`
* Source directories renamed: `src/main/java` → `src/main/kotlin`, `src/test/java` → `src/test/kotlin`
* Artifact ID changed from `cql-ls-service` to `cql-ls-server`
* adds unit tests
* fixes testing issues related to github java version
* adds changelog check to ci pipeline
* fixes duplicate debug command error
* code cleanup and consolidation
* fixes threading issue with context loading (#132)

## v4.4.1

Date: 2026-03-30

* Added Dokka support for generating KDoc API documentation

## v4.4.0

Date: 2026-03-30

* Added option to disable hover support
* Version bump to 4.4.0

## v4.3.0

Date: 2026-03-27

### Kotlin Migration

Complete migration of the language server from Java to Kotlin with Spring Boot removed.

* All source files converted from Java to Kotlin
* Spring Boot replaced with manual dependency wiring in `main.kt`
* `maven-shade-plugin` replaces `spring-boot-maven-plugin` for fat JAR assembly
* Added Dokka support for Kotlin documentation generation
* Added GitHub Actions CI with JUnit and Jacoco reporting

## v4.2.0

Date: 2026-02-24

* Fixed shutdown behavior when the VS Code extension process stops

## v4.1.2

Date: 2026-01-10

* Fixed exception thrown when loading `cql-options.json`

## v4.1.1

Date: 2026-01-10

* Fixed diagnostics messages not being reported for libraries resolved from packages (NPM/FHIR packages)

## v4.1.0

Date: 2026-01-09

* Fixed `IllegalCharacterException` when executing CQL on Windows
* Fixed null reference exception when loading an included library

## v4.0.0

Date: 2026-01-08

* Added support for CQL v4 language features

## v3.8.0

Date: 2025-09-09

* Fixed NPE when running in a workspace without an Implementation Guide

## v3.7.0

Date: 2025-08-15

* Added support for local modelinfo files
* Fixed "search not implemented" errors
* Fixed NPEs in certain error reporting paths

## v3.6.0

Date: 2025-08-14

* Updated Maven Central publishing configuration
* Fixed namespace resolution for CQL libraries referenced in FHIR IGs

## v3.5.0

Date: 2025-07-31

* Updated `clinical-reasoning` and `cql-translator` dependency versions
* Replaced `org.opencds.cqf.fhir.api.Repository` with `ca.uhn.fhir.repository.IRepository`

## v3.4.0

Date: 2025-02-13

* Version bump (dependency and compatibility updates)

## v3.3.1

Date: 2025-01-09

* Hotfix: formatting and stability corrections for v3.3.0

## v3.3.0

Date: 2025-01-09

* Updated to `clinical-reasoning` 3.16.0

## v3.2.1

Date: 2024-09-30

* Fixed "duplicate namespace" error when translating CQL in an IG-based project

## v3.2.0

Date: 2024-09-27

* Minor dependency and compatibility updates

## v3.1.0

Date: 2024-03-21

* Improved URI handling; switched to URI-based library resolution
* Updated to `clinical-reasoning` 3.2.0

## v3.0.0

Date: 2024-02-29

* Added CQL 3.x language support
* Added Jacoco coverage, ErrorProne, and Checkstyle quality tooling
* Restored CQL CLI command support
* General dependency updates and bit-rot fixes

## v2.0.0

Date: 2022-12-02

* Added support for loading CQL libraries via NPM / FHIR packages
* Various bug fixes for the CQL translator, engine, and evaluator

## v1.5.9

Date: 2022-09-28

* Fixed URI handling on Windows
* Updated `cql-translator` to 1.5.12, `cql-engine` to 1.5.10, `cql-evaluator` to 1.4.7
