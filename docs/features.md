# CQL Language Server features

A short tour of what the language server does, for IDE-plugin authors and contributors. It is
not an API reference; the Kotlin sources named below are authoritative.

## Standard LSP features

Registered in `CqlTextDocumentService` (text sync is `Full`):

- Diagnostics: compiler errors and warnings are published for open and changed CQL files
  (`DiagnosticsService`).
- Hover (`HoverProvider`)
- Go to definition (`DefinitionProvider`)
- Find references (`ReferencesProvider`)
- Document symbols (`DocumentSymbolProvider`)
- Document formatting (`FormattingProvider`)

No completion, rename, or code action providers are registered.

## Custom commands

Clients call these through `workspace/executeCommand`. `CqlWorkspaceService` advertises every
command from the `CommandContribution` list built in `ls/service/Main.kt`.

| Command ID | Arguments | Result |
| --- | --- | --- |
| `org.opencds.cqf.cql.ls.executeCql` | One `ExecuteCqlRequest` object: `fhirVersion`, `rootDir`, `optionsPath`, and `libraries[]` (`libraryName`, `libraryUri`, `libraryVersion`, `terminologyUri`, `model`, `context`, `parameters[]`) | `ExecuteCqlResponse`: `results[]`, `logs[]`, `versions` |
| `org.opencds.cqf.cql.ls.viewElm` | `[uri, elmType]`; `elmType` is `"xml"` (default) or `"json"` | ELM text for the library, or null |
| `org.opencds.cqf.cql.ls.virtualSource` | `[uri]` | Source text the server compiled for that URI, or null |
| `org.opencds.cqf.cql.ls.getVersionInfo` | none | `{ translator, engine, clinicalReasoning, languageServer }` |
| `org.opencds.cqf.cql.debug.startDebugSession` | none | Starts a debug session (see below) |
| `org.opencds.cqf.cql.ls.validateTestCase` | path string, or `{ path, options }` | `ValidateTestCaseResponse` |
| `org.opencds.cqf.cql.ls.prewarmProfileValidation` | `{ path, packages }` | `{ status, message }` |

Notes:

- `viewElm` returns text; the client must know how to display it. Its `json` form is written by
  `ElmJsonLibraryWriter`.
- `executeCql` and the debugger read the IG context (`ig.ini`) and library resolution state
  that the server tracks.

## Debugger

`startDebugSession` creates a `DebugSession` around `CqlDebugServer` (package
`org.opencds.cqf.cql.debug`). Only one session can be active; a second start fails until the
first ends. The server speaks the Debug Adapter Protocol and implements:

- `launch`, `configurationDone`, `disconnect`, `terminate`
- `setBreakpoints`, `setExceptionBreakpoints`
- `continue`, `next`, `stepIn`, `stepOut`
- `threads`, `stackTrace`, `scopes`, `variables`
- `evaluate` (including evaluate for hovers) and `completions` for the debug console
- `source` (for sources the server compiled)

## Test case validation

Code lives in `ls/server/command/ValidateTestCaseCommandContribution.kt`,
`ls/server/validation/testcase/` (custom checks) and `ls/server/validation/profile/` (HL7 core
engine). The vscode-cql extension surfaces this through the `validation` section of
`input/tests/config.jsonc`; that client config is not covered here.

### Commands

`org.opencds.cqf.cql.ls.validateTestCase` takes either a path string or `{ path, options }`.
`path` can be a single bundle or resource `.json` file, a test case directory, or a directory of
test cases. It runs on a two-thread executor and returns a `ValidateTestCaseResponse`. A missing
`path` or a blank one fails the request.

`org.opencds.cqf.cql.ls.prewarmProfileValidation` takes `{ path, packages }`, starts the engine
build in the background, and returns `{ status, message }` immediately. `status` is the engine
state name (for example `WARMING`, `READY`, `FAILED`).

### Options

All booleans default to `true`; `profile` defaults to off.

| Option | Meaning |
| --- | --- |
| `checkReferences` | Dangling-reference checks (custom), and reference checks in the core engine |
| `checkDuplicateIds` | Same `type/id` in more than one file of a case |
| `checkPatientLinkage` | Every resource points at the case's single Patient |
| `checkRequiredFields` | Mandatory-field checks on common resource types |
| `checkMadieStructure` | MeasureReport subject check and `.madie` manifest sync |
| `sharedDirs` | Extra directories of shared resources; a `shared/` folder next to the case is always read |
| `profile` | `OFF`, `QUICK`, or `FULL` |
| `suppressMessageIds` | Core issues to drop (see noise policy) |
| `downgradeMessageIds` | Core issues to downgrade from error to warning |
| `packages` | Extra `id#version` packages loaded on top of the IG's `dependsOn` |

```json
{
  "path": "/work/my-ig/input/tests/measure/MyMeasure",
  "options": {
    "profile": "QUICK",
    "checkMadieStructure": false,
    "sharedDirs": ["/work/my-ig/input/tests/shared"],
    "packages": ["hl7.fhir.us.core#6.1.0"],
    "suppressMessageIds": ["Unable to validate code without using server"],
    "downgradeMessageIds": ["us-core-6"]
  }
}
```

### Custom check codes

| Code | Severity | Raised when |
| --- | --- | --- |
| `ERR_PATH_NOT_FOUND` | error | The target path does not exist |
| `ERR_INVALID_JSON` | error | A file fails to parse |
| `ERR_NO_PATIENT` | error | The case has no Patient |
| `ERR_MULTIPLE_PATIENTS` | error | The case has more than one Patient |
| `ERR_DUPLICATE_ID` | error | Duplicate `type/id` across the case's files |
| `ERR_PATIENT_MISMATCH` | error | A resource references a different patient |
| `ERR_DANGLING_REFERENCE` | error | A reference cannot be resolved in the case or shared resources |
| `WARN_FULLURL_MISMATCH` | warning | A Bundle entry `fullUrl` does not match its `type/id` |
| `ERR_MEASURE_REPORT_SUBJECT` | error | `MeasureReport.subject` (or a contained subject parameter) differs from the patient |
| `WARN_MADIE_MANIFEST_SYNC` | warning | The case folder is not listed in the `.madie` manifest |
| `ERR_MISSING_REQUIRED_FIELD` / `WARN_MISSING_REQUIRED_FIELD` | error / warning | A mandatory field is absent |

### Profile validation

With `profile` set to `QUICK` or `FULL`, the files are also validated by the HL7 core
`ValidationEngine`.

- The engine loads `hl7.fhir.r4.core#4.0.1`, the IG's `dependsOn` packages (from its
  `ImplementationGuide`, found through `ig.ini`), any extra `packages`, and the IG's
  `input/vocabulary` and measure resources.
- It runs with no network (`NoNetworkFetcher` resolves references only from the case and shared
  resources) and no terminology server.
- `ProfileValidatorRegistry` caches one engine per (IG root, packages) and rebuilds it when
  `ig.ini` or the IG definition changes. QUICK and FULL share the engine. If no `ig.ini` exists at
  or above the path, profile validation fails with a message.
- The first build is slow (the source notes 30 seconds to 2 minutes). Until it finishes, results
  carry `profileStatus: WARMING` and only the custom checks; run again shortly or call
  `prewarmProfileValidation` ahead of time.
- QUICK checks that local references exist and have the right target type. FULL also validates
  reference targets against the element's `targetProfile` and keeps informational messages.
- When the engine ran on a file, these custom codes are dropped for it because core covers them:
  `ERR_DANGLING_REFERENCE`, `ERR_MISSING_REQUIRED_FIELD`, `WARN_MISSING_REQUIRED_FIELD`,
  `WARN_FULLURL_MISMATCH`. Files that failed to parse keep only `ERR_INVALID_JSON`.

Noise policy (`ProfileIssuePolicy`):

- Default downgrade from error to warning: `us-core-16`, `us-core-17`, `DUPLICATE_ID`.
- QUICK suppresses terminology "no server" messages and all informational issues.
- `suppressMessageIds` and `downgradeMessageIds` are added to those lists.
- An entry matches an issue if it equals the issue code, equals its `#invariant` suffix (for
  example `us-core-17` matches `...us-core-practitioner#us-core-17`), or, when the entry contains
  a space, appears in the message text.
- Dropped issues are counted in `suppressedCount` on each test case result.
- A core issue about a profile that is not loaded adds a hint to `profileMessage` to add the
  package to `packages`.

### Response shape

- `ValidateTestCaseResponse`: `targetPath`, `testCaseCount`, `resourceCount`, `errorCount`,
  `warningCount`, `testCases[]`, `profileStatus`, `profileMessage`.
- `TestCaseValidationResult`: `testCasePath`, `testCaseName`, `patientId`, `resourceCount`,
  `errorCount`, `warningCount`, `issues[]`, `profileStatus`, `profileMessage`, `suppressedCount`.
- `ValidationIssue`: `severity` (`ERROR`, `WARNING`, `INFORMATION`), `code`, `message`,
  `resourceType`, `resourceId`, `filePath`, `jsonPath`, `line`, and `source` (`"custom"` or
  `"core"`). Core issues use the engine's message id as `code`, or `CORE_NO_MESSAGE_ID`.
- `profileStatus` is `OFF`, `RAN`, `WARMING`, or `FAILED`.

## Configuration the server reads

- `ig.ini`: the server reads the package id and canonical URL from `ig.ini` files in workspace
  folders to resolve namespace-qualified library includes, and watches the file for changes.
  Profile validation uses the nearest `ig.ini` at or above the target path as the IG root.
- `cql/cql-options.json`: compiler options, read from the workspace folder's `cql` directory
  and reloaded when the file changes (`CompilerOptionsManager`).
