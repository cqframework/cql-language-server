---
title: "ADR-0001: Runtime Network Access (FHIR Packages, Proxies, TLS)"
status: "Accepted"
date: "2026-11-10"
tags: ["fhir-packages", "proxy", "tls", "jvm", "restricted-networks"]
issues: ["cqframework/cql-language-server#158", "cqframework/vscode-cql#106", "cqframework/vscode-cql#94"]
---

## 1. Context

The language server is a JVM process started by a client. Today those clients are vscode-cql and
cql-mcp. Once it's running, the LS makes **its own** network requests. They come from inside the
JVM, so the clients' Node-side proxy handling doesn't apply to them. That means VS Code's
`http.proxy` setting, and cql-mcp's `HTTPS_PROXY` support (cql-mcp ADR-0001), don't reach them.

**What the LS fetches.** When an IG declares FHIR package dependencies (QI-Core, US Core, CRMI
and so on), `IgContextManager.readContext` builds a HAPI `FilesystemPackageCacheManager` with the
default builder. CQL's `NpmPackageManager` and clinical-reasoning's `NpmRepository` do the same.
Missing packages are downloaded into `~/.fhir/packages`. If that fails, the LS logs a warning,
skips the dependencies that aren't cached, and carries on. The failure surfaces later as
unresolved libraries, profiles or value sets, not as a network error.

**How HAPI does it.** These facts were checked in `org.hl7.fhir.utilities` 6.9.12, the version
inside `cql-ls-server-5.0.0.jar`:

| Concern | Behaviour |
| --- | --- |
| Default registries | `https://packages2.fhir.org/packages` (primary), then `https://packages.fhir.org`. `https://build.fhir.org` is used for `current` CI builds. |
| Configuration file | `fhir-settings.json`. The path comes from the `-Dfhir.settings.path` system property, otherwise `~/.fhir/fhir-settings.json` (from `user.home`). |
| Mirrors | `servers[]` entries with `type: "fhir-package"` or `"npm-package"` are tried **before** the defaults. They can use `authenticationType` `basic`, `token` or `apikey`. Setting `ignoreDefaultPackageServers: true` removes the public registries from that list. |
| Fallback past the mirror | **Reproduced 2026-11-10.** If no configured server supplies the package, for **any** failure (404, 401, connection refused, or a failed tarball download), HAPI still contacts `build.fhir.org` (`CIBuildClient`, then `fetchTheOldWay`, `FilesystemPackageCacheManager.java:840`), even with `ignoreDefaultPackageServers: true`. That setting only filters the default *registry* list. The fallback is logged at DEBUG, plus a WARN from `PackageClient`. Nothing at INFO or above says it's going to a public site. |
| Mirror API (`npm-package`) | `GET <url>/<id>/<version>` (`Accept: application/json`) must return JSON with an absolute `dist.tarball` URL. HAPI then fetches `GET <dist.tarball>`. If that fails, it retries with `<major>.<minor>.x`, via `GET <url>/<id>` (a `versions` map). A `fhir-package` server serves the tarball directly at `<url>/<id>/<version>`. |
| Mirror auth | `token`: `Authorization: Bearer <token>`. `basic`: `Authorization: Basic …`. `apikey`: `Api-Key: <key>`. **Credentials are only sent to URLs with the same protocol, host and port whose path starts with the server `url`'s path** (`ManagedWebAccessUtils.urlMatchesOrigin`). A tarball served from elsewhere, for example `/tarballs/…` when `url` is `…/packages`, or a CDN, gets **no** auth header, fails with 401, and triggers the public fallback. |
| Offline | `prohibitNetworkAccess: true` blocks all HTTP, so only `~/.fhir/packages` is used. It also blocks the mirror, so it can't be combined with a mirror-only setup. |
| HTTP client | Package downloads go through `ManagedWebAccessor`, then `SimpleHTTPClient`, which uses `java.net.URL.openConnection()` (plain `HttpURLConnection`). |
| Proxy | `HttpURLConnection` follows the JVM's `https.proxyHost`/`https.proxyPort`, `http.proxyHost`/`http.proxyPort`, `http.nonProxyHosts` and `java.net.useSystemProxies` settings. It **ignores** `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY`. HAPI only reads `http.proxyUser`/`http.proxyPassword` in its OkHttp-based FHIR client, **not** on the package download path, so proxy credentials need a `java.net.Authenticator` that nothing installs today. The JDK also blocks Basic auth on HTTPS proxy tunnels by default (`jdk.http.auth.tunneling.disabledSchemes`). |
| TLS | The JVM's default truststore. `fhir-settings.json` has a `certificateSources` field, but its effect on this path hasn't been checked. |

**Reported in the field, and reproduced.**
[vscode-cql#106](https://github.com/cqframework/vscode-cql/issues/106): a `fhir-settings.json`
mirror with `ignoreDefaultPackageServers: true` and token auth is ignored, and packages still come
from the public registry. Reproduced on 2026-11-10 against LS 5.0.0, both by calling HAPI directly
and through the LS, driven by cql-mcp
([findings](https://github.com/cqframework/vscode-cql/issues/106#issuecomment-6768501546)). There
are two triggers:

1. The mirror doesn't host a package the LS needs. The LS always loads `hl7.fhir.r4.core` first,
   so a registry with only private packages hits this straight away.
2. The mirror's tarball URL is outside the configured `url` path, so the token isn't sent.

Through the LS, validation then reported **valid, with no diagnostics**. The failed
dependencies were skipped with a single WARN in the LS log.

## 2. Decision

### 2.1 Registry mirrors and offline use go through HAPI's own settings file

The LS adds **no** settings of its own for FHIR registries. Users configure mirrors, credentials
and offline mode in `fhir-settings.json`, the file HAPI's IG Publisher and validator already
use, so one file serves every HAPI-based tool. Clients may pass `-Dfhir.settings.path` for users
who want a separate file (§2.5).

**Caveat:** because of HAPI's fallback past the mirror (§1), this gives *mirror-first*, not
*mirror-only*, behaviour until §2.3 is implemented.

### 2.2 Package-load failures are reported to the client

When the LS can't load a declared package dependency, the failure must reach the client. A WARN
in the LS log isn't enough. At minimum, the LS reports:

- the package id and version
- the servers it tried
- whether a public fallback was attempted
- the underlying error, without credentials

It reports this in a way both clients can show: a diagnostic on the IG or library, and/or an LSP
notification or `workspace/executeCommand` status result. The exact mechanism is decided in the
implementation PR for #158. Validation must not report a clean result while declared
dependencies are missing.

### 2.3 Mirror-only mode when `ignoreDefaultPackageServers` is set

When `fhir-settings.json` sets `ignoreDefaultPackageServers: true`, the LS must not contact public
registries or canonical sites. If the configured servers can't supply a package, that's a
reported failure (§2.2), not a silent fallback. The preferred approach is an LS-side guard:
resolve through the configured servers first (with HAPI's `PackageClient`), and call
`loadPackage` only for packages found there, or only once the tarball is already in the cache.
The other option is an upstream HAPI change, which should also cover sending credentials to the
tarball host. The choice is made in #158.

### 2.4 The LS installs a proxy `Authenticator`

On startup (`Main.kt`), the LS installs a `java.net.Authenticator` that answers
`RequestorType.PROXY` challenges from `http.proxyUser`/`http.proxyPassword` (and the `https.`
equivalents), and only when those are set. Without it, proxy credentials reach HAPI's FHIR client
but not package downloads.

### 2.5 Client contract: pass the user's network settings to the JVM

Each client that starts the LS passes the user's network settings to it as JVM system
properties:

| Client source | JVM properties |
| --- | --- |
| `HTTPS_PROXY` (cql-mcp, upper or lower case); VS Code `http.proxy` (vscode-cql) | `-Dhttps.proxyHost`, `-Dhttps.proxyPort` (default 443 or 80 by scheme) |
| `HTTP_PROXY` | `-Dhttp.proxyHost`, `-Dhttp.proxyPort` |
| `NO_PROXY` | `-Dhttp.nonProxyHosts`. Commas become `\|`, `.example.com` becomes `*.example.com`, and `*` disables the proxy |
| userinfo in a proxy URL (`user:pass@`) | `-Dhttp.proxyUser`, `-Dhttp.proxyPassword` (plus `https.` equivalents) and `-Djdk.http.auth.tunneling.disabledSchemes=` (empty) |
| a user-chosen settings file (`CQL_LS_FHIR_SETTINGS` in cql-mcp; a setting in vscode-cql) | `-Dfhir.settings.path` |

Every client also offers a generic JVM options passthrough (`CQL_LS_JAVA_OPTS` in cql-mcp, and a
matching setting in vscode-cql). It covers anything the table doesn't, such as a corporate
truststore (`-Djavax.net.ssl.trustStore=…`), `-Djava.net.useSystemProxies=true`, or heap size.
A property the user set explicitly in the passthrough is never overridden. Credentials are never
logged, and are redacted from any command line a client logs.

## 3. Alternatives Considered

* **Do nothing and document `-D` flags.** **Rejected.** Neither client can pass them today. And
  a user who has configured a proxy reasonably expects it to apply to the whole tool.
* **`JAVA_TOOL_OPTIONS` only.** **Rejected as the main mechanism.** It applies to every JVM on
  the machine, prints a banner on stderr, and is easy to get wrong. It still works if users set
  it themselves.
* **LS-specific registry-mirror settings.** **Rejected.** They would duplicate
  `fhir-settings.json`, which HAPI already reads and which FHIR tooling users may already have.
* **Convert a client's extra CA certificates into a JVM truststore automatically.** **Deferred.**
  It means generating a PKCS12 truststore at startup, merged with the JDK's `cacerts`.
  Reconsider if TLS interception turns out to be common. Until then, it's a documented
  passthrough recipe.
* **Pre-bundle common FHIR packages in the jar.** **Rejected.** IG dependency versions differ
  between projects, and the jar is already about 210 MB.

## 4. Consequences

* **Positive:** In both clients, one proxy configuration covers the LS jar download and the LS's
  own package fetches.
* **Positive:** Registry mirrors and offline use work through a file HAPI users already know.
* **Positive:** Users see a clear error instead of a clean validation with silently missing
  dependencies.
* **Negative:** Proxy credentials appear as system properties in the JVM command line, which
  `ps` can show to other local users. If this matters, later we could pass them over stdin or in
  a temp file the LS reads.
* **Negative:** Until §2.3 lands, mirror configurations are mirror-first, not mirror-only.
* **Negative:** Corporate TLS needs a manual truststore recipe until the deferred alternative is
  built.

## 5. Open Questions (tracked in #158)

1. Does `fhir-settings.json` `certificateSources` affect `SimpleHTTPClient`? If it does, it could
   replace the truststore recipe.
2. Does the resource validation path (the `validateTestCase` command, 5.1.0-SNAPSHOT) fetch from
   `tx.fhir.org` or other terminology servers? If so, those requests need the same handling.
3. For §2.3: an LS-side guard, or an upstream HAPI change? Or both, with the guard as a stopgap?
4. For §2.2: which channel reports the failure (diagnostic, notification or command result), so
   it works for both clients?

## 6. Testing

- **LS unit tests:**
  - the `Authenticator` answers proxy challenges only, and only when credentials are set
  - the mirror-only guard never calls the fallback path when `ignoreDefaultPackageServers` is set
  - package-load failures produce the §2.2 report, with no credentials in it
- **LS integration test:** a fake package registry on localhost, with `-Duser.home` pointing at a
  temp folder, plus a logging proxy that rejects every other host. The 2026-11-10 reproduction
  scripts become this test. It asserts that the proxy log stays empty in mirror-only mode, and
  that an error is reported when the mirror lacks a package.
- **Clients:** unit tests for the §2.5 mapping, covering upper and lower case, ports by scheme,
  `NO_PROXY` translation, userinfo, passthrough precedence and log redaction. cql-mcp's
  restricted-network E2E test adds a fake registry to its `corp` network.

## 7. Agent Directives

* **MUST NOT** add LS-specific settings for FHIR registries. Use `fhir-settings.json`.
* **MUST NOT** let a package-load failure end in a clean validation result or a log-only
  warning.
* **MUST NOT** contact public registries or canonical sites when `ignoreDefaultPackageServers` is
  set, once §2.3 is implemented.
* **MUST NOT** override a JVM property the user set explicitly in a client's passthrough.
* **MUST NOT** log proxy or registry credentials, or an unredacted JVM command line.
* **MUST** run network tests against local fakes with an isolated `user.home`. Tests must never
  touch the real `~/.fhir`.
