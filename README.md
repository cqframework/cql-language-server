# CQL Language Server

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.opencds.cqf.cql.ls/cql-ls/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.opencds.cqf.cql.ls/cql-ls) [![Build Status](https://app.travis-ci.com/DBCG/cql-language-server.svg?branch=master)](https://app.travis-ci.com/DBCG/cql-language-server) [![project chat](https://img.shields.io/badge/zulip-join_chat-brightgreen.svg)](https://chat.fhir.org/#narrow/stream/179220-cql)

A CQL Language server compatible with the [Language Server Protocol](https://microsoft.github.io/language-server-protocol/).

## Usage

Building this project requires Java 9+ and Maven 3.5+. The resulting jar is compatible with Java 8+.

Build the project with:

```bash
mvn package
```

The language server itself is designed to be used by IDE plugins, such as the [CQL Support for VS Code](<[https://atom.io/packages/language-cql](https://marketplace.visualstudio.com/items?itemName=cqframework.cql)>) package.

## Getting Help

Bugs and feature requests can be filed with [Github Issues](https://github.com/DBCG/cql-language-server/issues).

The implementers are active on the official FHIR [Zulip chat for CQL](https://chat.fhir.org/#narrow/stream/179220-cql).

Inquires for commercial support can be directed to [info@alphora.com](info@alphora.com).

## Related Projects

[Clinical Quality Language](https://github.com/cqframework/clinical_quality_language) - Tooling in support of the CQL specification, including the CQL verifier/translator and engine used in this project.

[Clinical Reasoning](https://github.com/cqframework/clinical-reasoning) - Provides for complete evaluation of CQL logic, as well as implementations of operations defined by the FHIR Clinical Reasoning module and related FHIR IGs.

[VSCode Plugin](https://github.com/cqframework/vscode-cql) - Makes use of this CQL Language Server to provide CQL language capabilities for the VSCode environment.

## Commit Policy

All new development takes place on `<feature>` branches off `master`. Once feature development on the branch is complete, the feature branch is submitted to `master` as a PR. The PR is reviewed by maintainers and regression testing by the CI build occurs.

Changes to the `master` branch must be done through an approved PR. Delete branches after merging to keep the repository clean.

Merges to `master` trigger a deployment to the Maven Snapshots repositories. 

Once ready for a release, the `master` branch is updated with the correct version number and is tagged. Releases SHALL NOT have a SNAPSHOT version, nor any SNAPSHOT dependencies. Releases must be deployed manually.

## Release Process

To release a new version of the language server:

1. Update master to be a release version (and all the reviews, bug fixes, etc. that that requires)
   1. Example version: `3.8.0`
2. Passed CI Build = ready for release
3. Use the following command to release from your local build (releases are not automatic)
   1. `mvn deploy -DskipTests=true -Dmaven.test.skip=true -T 4 -B -P release`
4. Create a Github Release (specify a tag of `vX.X.X` (e.g. `v3.8.0`) pointing at master to be created on release)
   1. Choose the "Auto-generate release notes" option
   2. Provide any additional detail/cleanup on the release notes
6. Update master version to the next snapshot version `X.X.X-SNAPSHOT` (e.g. `3.9.0-SNAPSHOT`)
7. Close all issues included in the release

## License

Copyright 2019+ Smile Digital Health

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

[http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
