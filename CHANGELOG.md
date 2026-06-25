# Change Log



## v4.9.0

Date: 2026-06-25

* bump version to cql-language-server to 4.9.0 
* change clinical-reasoning to version 4.9.0
* fix issue with library names with hyphens


## v4.8.0

Date: 2026-06-13

* add version info support
* update cql engine to v4.9.0
* validate breakpoints
* add timestamp to server logs
* add define call stack and multi-frame stack trace improve FHIR variable display formatting and profile children
* unified runtime value registry, test case scope, and CQL type annotations
* add debug session shutdown enhancements
* fix DAP debugger correctness, thread safety, and protocol gaps
* adds support for debug hover
* adds cql debugging support
* code cleanup - rpc related
* add convert CQL to ELM as an AST
* bump version to 4.8.0


## v4.7.0

Date: 2026-05-28

* resolve alias hover in nested sub-queries and property hover across union + FunctionRef wrapper
* replace ELM-position-based hover with ANTLR CursorClassifier
* prevent ANTLR keyword suppression from swallowing expression hovers
* generic ANTLR keyword suppression for all query clause types
* change hover to use hybird approach POC
* format code with ktfmt (trailing commas, chain assignment, import ordering)
* add CQL test resources for hover and navigation coverage
* enrich hover provider with CQL syntax output, coercion unwrapping, and new ELM type support
* add go-to-definition support for function operands and parameters
* add ExpressionTrackBackVisitor support for Literal, OperandRef, OperandDef, ParameterRef, CodeRef, ConceptRef
* add Elements.unwrapCoercions for compiler-generated wrapper resolution
* adds code navigation support
* code cleanup and more unit tests
* adds more LibraryResolutionManager tests
* adds more LibraryResolutionManager tests
* adds FederatedLibrarySourceProvider and FederatedTerminologyRepo
* adds support for multi-projects library resolution
* bump version to 4.7.0


## v4.6.0

Date: 2026-05-14

* adds logging details to view elm command
* fix logging issues. cql-ls.log was not being updated
* bump CR to 4.6.0
* bump version to 4.6.0


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
* remove lingering springboot artifacts from pom files
* code clean up
* spotless apply
* fixes renamed main issue
* spotless applied, adds changelog check to ci pipeline
* removes lingering springboot artifacts from pom files

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
