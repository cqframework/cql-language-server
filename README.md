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

[Clinical Quality Language](https://github.com/cqframework/clinical_quality_language) - Tooling in support of the CQL specification, including the CQL verifier/translator used in this project.

[CQL Engine](https://github.com/DBCG/cql_engine) - Open source Java-based ELM evaluation engine.

[CQL Evaluator](https://github.com/DBCG/cql-evaluator) - Integrates the CQL Translator and CQL Engine into an execution environment, and provides implementations of operations defined by FHIR IGs.

## Commit Policy

All new development takes place on `<feature>` branches off `master`. Once feature development on the branch is complete, the feature branch is submitted to `master` as a PR. The PR is reviewed by maintainers and regression testing by the CI build occurs.

Changes to the `master` branch must be done through an approved PR. Delete branches after merging to keep the repository clean.

Merges to `master` trigger a deployment to the Maven Snapshots repositories. Once ready for a release, the `master` branch is updated with the correct version number and is tagged. Tags trigger a full release to Maven Central and a corresponding release to Github. Releases SHALL NOT have a SNAPSHOT version, nor any SNAPSHOT dependencies.

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
