# Changelog

All notable changes to Brume are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Pseudonymization outputs are stable across all releases of a major version (see
[Determinism and versions](README.md#determinism-and-versions)): a change of output only
ships in a new major version.

## [2.4] - 2026-11-24

Thanks to [@rjoly](https://github.com/rjoly) for reporting
[#1](https://github.com/brumeorg/Brume/issues/1), [#2](https://github.com/brumeorg/Brume/issues/2),
[#3](https://github.com/brumeorg/Brume/issues/3) and [#4](https://github.com/brumeorg/Brume/issues/4).

### Fixed

- Native binary: `brume diag` and the HTML and JSON reports print the build version. They
  printed `0.0.1-SNAPSHOT` (diag) and `dev` (reports), while `brume --version` was right.
  ([#1](https://github.com/brumeorg/Brume/issues/1))
- A column or a `json_paths` entry without `strategy` is rejected when the configuration is
  loaded, with the new `CONFIG_STRATEGY_MISSING`. It used to pass validation and then fail on
  the first row with `[UNEXPECTED] NullPointerException`, leaving a half-written target after
  `execute`. A `FAKE` `json_paths` entry without `type` is rejected with
  `CONFIG_STRATEGY_REQUIRES_TYPE`, as for columns. The strategy of a `json_paths` entry is never
  inherited from its column. A `type: JSONB` column needs no `strategy` of its own, since it is
  anonymized path by path: a declared one is ignored, as it always was (a `NULLIFY` there never
  emptied the column), and now reported with the warning `[CONFIG_JSONB_STRATEGY_IGNORED]`.
  ([#2](https://github.com/brumeorg/Brume/issues/2))
- Native binary: `FAKE` + `PHONE` failed on every value, in a column as in a `json_paths`
  entry, with `[UNEXPECTED]` (exit code 127) and
  `NoSuchMethodException: PhoneNumberUtil.getMetadataForRegion`.
  ([#3](https://github.com/brumeorg/Brume/issues/3))
- `--help` / `-h` and `--version` / `-V` work on every subcommand: `brume audit --help` was
  rejected as an unknown option, although the usage error itself suggested running it.
  `brume init --help` documents the `jdbc` / `dump` argument.
  ([#4](https://github.com/brumeorg/Brume/issues/4))
- `FAKE`: one value under two semantic types sharing a semantic key got the fake of whichever
  type came first. It happened with two leaves of one JSONB column (`$.first_name` and
  `$.last_name` both `Martin` got the same first name) and with `linked_columns` grouping
  columns of different types. Each type now gets its own fake; only these values change output.
- README: the `audit` example used `--report-format`, which does not exist; it now uses
  `--output-markdown`.

### Changed

- A JSONB column is recorded as `JSONB` in the execution state read by `brume audit`, instead
  of the strategy declared on it: the audit no longer treats the whole column as anonymized when
  only some of its paths are, and the quasi-identifier detection skips it. The schema checks of
  `plan` no longer apply that ignored strategy either: `HASH` or `MASK` on a JSONB column is no
  longer refused under `--strict-config`, nor `NULLIFY` on a `NOT NULL` one. A JSONB column
  must still be of PostgreSQL type `json` or `jsonb`.
- `brume.example.yml` declares its JSONB columns without `strategy`.

## [2.3] - 2026-11-20

### Fixed

- Linux native binary: 2.2 required glibc 2.34 and did not start on RHEL / Rocky / Alma 8,
  Debian 11 or Ubuntu 20.04 (``version `GLIBC_2.34' not found``), although its `.rpm` and
  `.deb` installed. It is now built against glibc 2.28 and runs on any Linux x86_64 with
  glibc ≥ 2.28: RHEL / Rocky / Alma / Oracle Linux 8+, Fedora, Debian 11+, Ubuntu 20.04+.
  Amazon Linux 2 and CentOS 7 are not supported (older glibc).
- `brume --version` prints the release version: 2.2 printed `2.1`. The release build now takes
  its version from the tag.
- Homebrew on Linux could download the package layout (`usr/bin/brume`) instead of the binary
  the formula expects: two release jobs produced an archive with the same name.

### Changed

- Before a release is published, its `.rpm` and `.deb` are installed and started on RHEL 8 / 9,
  Debian 11 / 13 and Ubuntu 20.04 / 24.04.

## [2.2] - 2026-11-14

### Added

- Settings are checked before startup, and every missing required setting is listed at once
  (`CONFIG_ENV_VAR_MISSING`).
- Each `extraction.tables[].filter` is checked against the source with `EXPLAIN` before
  anything is written. An invalid filter fails with `CONFIG_EXTRACTION_FILTER_INVALID` and
  PostgreSQL's reason. Before this, `execute` failed with `[UNEXPECTED]` (exit code 127) after
  the DDL had been replicated, and `plan` reported row counts of `-1`.
- New error codes for setting and command-line errors, in the usual
  `[CODE] message → suggestion` shape, as text or JSON: `CONFIG_ENV_FILE_INVALID` (malformed or
  duplicated line in `.env.brume`), `CONFIG_PROPERTY_INVALID` (wrong type or value: names the
  setting, its environment variable and the accepted values), `CONFIG_INVALID_FLAGS`,
  `CONFIG_CLI_USAGE`, `CONFIG_JDBC_SSLMODE_INVALID`, `CONFIG_POOL_SIZE_INVALID`,
  `AUDIT_KIND_REQUIRED`, `AUDIT_QUASI_ID_INVALID`, `AUDIT_SAMPLE_RATE_INVALID`.
- Warning `[CONFIG_UNKNOWN_KEY]` for an unknown key in `brume.yml`, with its line: a typo such
  as `fk_dpeth` used to be ignored silently.
- Warning `[CONFIG_LOCALE_UNKNOWN]` when `BRUME_LOCALE` is a language Datafaker does not know
  (it falls back to English).
- The replicated DDL no longer contains what an older server or `psql` rejects: the
  `\restrict` / `\unrestrict` meta-commands (emitted by `pg_dump` since 17.6, 16.10, 15.14…)
  and `SET transaction_timeout` (emitted by `pg_dump` ≥ 17, rejected by servers ≤ 16). Only
  replay a dump file that comes from a trusted source.

### Changed

- `pg_dump` 18 is the recommended client: it reads every supported source (12 → 18). The Docker
  image ships `postgresql18-client`. `brume init` accepts `pg_dump` ≥ 12 (it required ≥ 17) and
  recommends 18. The rule "`pg_dump` major ≥ source major" is checked before every run.
- `pg_dump` runs with `--quote-all-identifiers`, as its documentation recommends for
  cross-version restores.
- The cached schema DDL is keyed on the full `pg_dump --version` line as well.
- The target is changed only once the run is accepted: configuration, filter and schema
  checks, the plan and `max-target-rows` all run before the DDL replication. A refused run used
  to leave empty tables on the target.
- Configuration errors raised before startup, and `--verbose` used with `--quiet`, exit with
  code 1 (configuration) instead of 2, which is reserved for connection failures.
- A command line that does not parse exits with code 1 (`CONFIG_CLI_USAGE`) and points to
  `--help`, instead of printing the whole usage and exiting with code 2: a script retrying on
  code 2 would loop on a typo. With `--json`, the error is printed as JSON.
- An invalid `sslmode` is reported at startup, not as a connection failure.
- A startup failure is reported once, in Brume's format. Spring's
  "APPLICATION FAILED TO START" banner and stack trace are no longer printed.
- PostgreSQL 12 to 18 is tested on every change: the integration tests, and the end-to-end
  tests of the native binary, run against each version.

### Fixed

- Resuming an interrupted `execute`:
  - the resumed run started with `DROP SCHEMA … CASCADE`, which erased the tables the
    interrupted run had written; the checkpoint then skipped them and they ended empty. A
    resumed run now keeps the target schema;
  - the checkpoint is deleted after a successful run. Kept, it made the next `execute` skip
    every table it listed;
  - resuming only applies to the JDBC sink in `STREAMING` mode. DUMP, dry-run and `BATCH` runs
    now run in full instead of producing an incomplete result.
- Signals (SIGTERM, SIGINT, `docker stop`):
  - the native binary died at once, without cancelling JDBC queries and `pg_dump`, without
    `[RUN_CANCELLED]`, and could leave a truncated dump;
  - after a signal, Brume waits for the run to print `[RUN_CANCELLED]` and to close the sinks
    (gzip / zstd trailer of a dump). The exit code is 143 on SIGTERM and 130 on SIGINT;
  - a finished command no longer logs "SIGTERM received — cancelling 0 JDBC connection(s)";
  - a connection aborted on purpose no longer logs a HikariCP "marked as broken" stack trace.
- Native binary:
  - `audit --json` and `audit --output-json` failed with `[UNEXPECTED]` (exit code 127,
    "Failed to serialize anonymity report");
  - `diag --json` printed an object without any property;
  - `--version` printed `brume dev`.
- `brume diag`, documented as never contacting the databases, opened connections to the source
  in the background. Connections are now opened only when needed.
- `brume audit`: `--quasi-id users` failed with `[UNEXPECTED]` (exit code 127), and
  `--sample-rate 2` was silently brought down to `1.0`. Both are rejected as usage errors.
- `brume plan` accepted an invalid `brume.hmac-algorithm`, and `brume execute` failed on it
  only after the preflight. It is checked at startup.
- `-q` no longer prints "JdbcSink configured…".

### Security

- With `BRUME_HMAC_SECRET` or `BRUME_FPE_KEY` missing, the run used the literal placeholder
  text (`${BRUME_HMAC_SECRET}`, `${BRUME_FPE_KEY}`) as the secret: a public value anyone could
  use to recompute the pseudonyms. Both are now required before startup, and rejected again at
  validation.
- A malformed `.env.brume` is reported by key and line number, never with the line content or
  the values, so a secret does not end up in a CI log.

### Known issues

- This release reports its version as `2.1` (`brume --version`): `pom.xml` was not bumped.
  Fixed in 2.3.
- The Linux binary requires glibc ≥ 2.34: it does not start on RHEL / Rocky / Alma 8,
  Debian 11 or Ubuntu 20.04. Fixed in 2.3.

## [2.1] - 2026-10-24

### Changed

- `FAKE` propagates along foreign keys like `FPE_ID`, `FPE_UUID` and `HASH`: declared on any
  column of a foreign key, it applies to every column linked to it, with its `type` and its
  `min_year` / `max_year` bounds. A `linked_columns` entry is no longer needed along a declared
  foreign key; it remains for columns that share values without one. `FAKE` declared on linked
  columns with a different `type` or different bounds is rejected as a conflict.

### Removed

- Error code `CONFIG_FK_PROPAGATION_FAKE_REQUIRES_LINK`, no longer raised.

## [2.0] - 2026-10-24

### ⚠ Upgrade notes

- **Some outputs change compared with 1.x.** Pseudonymize again every database you need to join
  or compare with one produced by 1.x:
  - `FAKE` + `PHONE`: the format is kept, the digits are now drawn from the HMAC seed (1.6 % of
    values collided before);
  - `FAKE` names and addresses on a `UNIQUE` column carry a uniqueness suffix;
  - DUMP sink: timestamps are written with their UTC offset (see Fixed).
- **`fk_depth` is now a radius**, counted in foreign-key hops towards parents and children
  alike, and `plan` and `execute` apply the same rule. Start with `fk_depth: 1`; each hop can
  pull many more rows.

### Added

- `DATE_SHIFT` strategy: deterministic signed shift of ±N days on `date`, `timestamp`,
  `timestamptz`, `time` and `interval` columns. It preserves age cohorts and event ordering.
  N is at most `brume.date-shift.max-days` (default `30`, env `BRUME_DATE_SHIFT_MAX_DAYS`).
- `DATE` semantic type: `FAKE` + `DATE` draws a deterministic date of birth, adult by default
  (1935–2008), overridable per column with `min_year` / `max_year`. Invalid bounds fail with
  `CONFIG_DATE_RANGE_INVALID`.
- `extraction.orphan_policy`: `KEEP_PARENTS` (default) copies the parents of every copied row,
  whatever their distance, so the target never holds a dangling foreign key. `ALLOW_ORPHANS`
  keeps the radius strict, logs `[ORPHANS]` per foreign key and lists `orphanReferences` in the
  JSON report.
- UNIQUE columns:
  - `FAKE` with `FIRST_NAME`, `LAST_NAME` or `ADDRESS` on a column that is unique on its own
    gets a 64-bit suffix derived from the real value, cut to fit `varchar(n)`;
  - `brume plan` rejects `MASK` and `FAKE` + `DATE` on a `UNIQUE` column
    (`CONFIG_SCHEMA_UNIQUE_NOT_INJECTIVE`);
  - a remaining collision no longer stops the run. The row is not written and the loss is
    reported: `[UNIQUE_COLLISION]` warning, lost rows per table in the console report, error
    insight in the plan-vs-actual comparison, `uniqueCollisions` in the JSON report;
  - with the DUMP sink, rows that would break a unique index are dropped, so the file always
    restores with `psql`.
- `brume plan` rejects `NULLIFY` on a `NOT NULL` column (`CONFIG_SCHEMA_NULLIFY_NOT_NULL`).
- PostgreSQL 12 and 13 are supported (source and target): 12 to 18 in all.
- Schema support:
  - table and column names with any case, accents or spaces, written in `brume.yml` as stored
    in PostgreSQL;
  - partitioned tables: filters and rules are declared on the partitioned table, and foreign
    keys declared partition by partition count as foreign keys of the partitioned table;
  - views, materialized views and foreign tables are ignored by `brume scan` and cannot be
    targeted in `brume.yml`. Their definitions are still recreated on the target.
- `brume scan`:
  - birth-date columns (`birthday`, `birth`, `dob`) are mapped to `FAKE` + `DATE`. On temporal
    columns, a name matching a text pattern (e.g. `date_last_password_update`) is skipped
    instead of getting a rule whose output cannot be written back;
  - French column names are recognized: `courriel`, `portable`, `adresse_ip`, `naissance`,
    `mot_de_passe` / `mdp`, `securite_sociale` / `num_secu`.

### Changed

- `brume plan` counts exactly the rows `brume execute` writes.
- README: `FPE_ID` output is an integer but **not of the same length** as the input; an id up
  to 2 147 483 647 stays within that range, a larger one needs a `bigint`.

### Deprecated

- `replication.fk-depth` is ignored: `extraction.fk_depth` in `brume.yml` drives both `plan`
  and `execute`. The key is still accepted so that existing configurations load.

### Fixed

- DUMP sink: a `timestamptz` value was restored shifted by the time-zone difference between
  the machine running Brume and the server.
- A schema name with upper case or `*`, `?`, `.` did not match in `pg_dump`: it was passed as an
  unquoted pattern.
- A schema name with non-ASCII characters reached `pg_dump` mangled (`E2E ?dition`) under a
  non-UTF-8 locale (`LANG=C`, CI containers).

## [1.7] - 2026-10-18

### Added

- `brume scan` reads the source schema and writes a `brume.yml` with suggested rules:
  - PII columns (`email`, `phone`, `address`, `iban`, `password`, `token`, `ssn`, …) get the
    matching `FAKE` / `MASK` / `HASH` / `NULLIFY` strategy and `type`, with a comment naming
    the pattern that matched;
  - primary keys get `FPE_ID` (numeric) or `FPE_UUID` (uuid);
  - foreign keys are left to FK strategy propagation;
  - `extraction.tables` is left as a stub to fill in.

  It asks before overwriting an existing `brume.yml`; `--force` skips the question.

## [1.6] - 2026-10-10

### Added

- With no `.env.brume` in the current directory and no `BRUME_HMAC_SECRET` in the environment,
  Brume stops with `[CONFIG_ENV_MISSING]` and points to `brume init`, instead of a Spring
  binding error.

### Security

- Warning `[CONFIG_DEV_SECRET_DETECTED]` when `brume.hmac-secret` or `brume.fpe-key` still hold
  the public values of the `brume init` templates.

## [1.5] - 2026-10-09

### ⚠ Upgrade notes

- **The settings file is now `.env.brume`** instead of `.env`, to avoid clashing with Docker
  Compose and other tools that read `.env`. Rename your existing file.

### Added

- `brume init jdbc` and `brume init dump` create a `.env.brume` for the chosen sink.
  `brume init` alone asks which one (JDBC in a non-interactive shell).

### Changed

- `.env.template` is replaced by `.env.jdbc.template` and `.env.dump.template`.

### Security

- BouncyCastle 1.84 → 1.85, Jackson Databind 2.21.5 → 2.21.7.

### Known issues

- This release reports its version as `1.4`: `pom.xml` was not bumped.

## [1.4] - 2026-09-15

### Fixed

- Native binary: `BRUME_SINK_TYPE` was ignored and the JDBC sink always used, so DUMP and NULL
  modes were unavailable.
- `brume --version` printed `1.0.0` whatever the release. It now prints the build version (the
  native binary printed `dev` until 2.2).

## [1.3] - 2026-09-14

### Added

- `brume init` sets up a new project without a database or any configuration: it checks
  `pg_dump` (≥ 17), prints the install command for the OS when it is missing (a warning, not an
  error), and copies the `brume.yml` and `.env` templates into the current directory, asking
  before overwriting.

### Changed

- `brume` with no subcommand (e.g. `brume -v`) prints the usage without starting Spring or
  requiring a database configuration.

## [1.2] - 2026-09-12

### Security

- Dependency upgrades: Spring Boot 4.0.4 → 4.0.7, Spring Framework 7.0.9, PostgreSQL JDBC
  42.7.9 → 42.7.13, Jackson Databind 2.21.5, Thymeleaf 3.1.4, Log4j 2.25.5.
- `security` Maven profile running the OWASP dependency check, failing the build at CVSS ≥ 7.

## [1.1] - 2026-06-23

### ⚠ Upgrade notes

- **Environment variables are renamed with a `BRUME_` prefix**, and database URLs are split
  into host, port and database:

  | 1.0 | 1.1 |
  |---|---|
  | `HMAC_SECRET`, `FPE_KEY`, `HMAC_ALGORITHM` | `BRUME_HMAC_SECRET`, `BRUME_FPE_KEY`, `BRUME_HMAC_ALGORITHM` |
  | `LOCALE`, `PIPELINE_MODE`, `SCHEMA` | `BRUME_LOCALE`, `BRUME_PIPELINE_MODE`, `BRUME_SCHEMA` |
  | `SINK_TYPE`, `SINK_OUTPUT_PATH`, `SINK_COMPRESSION` | `BRUME_SINK_TYPE`, `BRUME_SINK_OUTPUT_PATH`, `BRUME_SINK_COMPRESSION` |
  | `SOURCE_DB_URL` | `BRUME_SOURCE_HOST`, `BRUME_SOURCE_PORT` (default 5432), `BRUME_SOURCE_DB`, `BRUME_SOURCE_SSLMODE` (default `prefer`) |
  | `SOURCE_DB_USERNAME`, `SOURCE_DB_PASSWORD` | `BRUME_SOURCE_USER`, `BRUME_SOURCE_PASSWORD` |
  | `TARGET_DB_URL` | `BRUME_TARGET_HOST`, `BRUME_TARGET_PORT` (default 5460), `BRUME_TARGET_DB`, `BRUME_TARGET_SSLMODE` (default `prefer`) |
  | `TARGET_DB_USERNAME`, `TARGET_DB_PASSWORD` | `BRUME_TARGET_USER`, `BRUME_TARGET_PASSWORD` |

### Added

- Composite primary keys and composite foreign keys: parents are resolved by the full key
  tuple, read in a deterministic order.
- Warning at schema analysis for tables with a composite primary key and for tables without
  one; the execution report counts both.
- FK strategy propagation works in both directions: a rule declared on any column of a foreign
  key (PK side or FK side) applies to every column it is linked to. Two different strategies
  on linked columns are rejected at startup.
- `BRUME_CONFIG_PATH`, `BRUME_PGDUMP_PATH` and `BRUME_PARALLELISM` (connection pool size,
  default 20).
- README in English, with installation on macOS (Homebrew) and Fedora / CentOS (dnf).

### Changed

- With the DUMP and NULL sinks, the `BRUME_TARGET_*` variables can be left unset.
- Spring startup information is no longer logged.

### Fixed

- Composite foreign keys pulled in the cartesian product of their columns' values instead of
  the referenced tuples.
- Native binary: HTML reports and `FAKE` values (Datafaker locale files and providers) failed.
- DUMP sink: when `pg_dump` failed while the dump was opened, the file stayed open (blocking a
  retry on Windows) and the error surfaced untyped.
- `brume plan` tagged a configured table as the FK parent of its own child.

## [1.0] - 2026-06-15

First release.

- Subcommands `plan` (volume estimate and uncovered PII columns, read-only), `execute`,
  `dry-run`, `diag` and `audit --anonymity` (k-anonymity report as text, JSON or Markdown).
  Global flags `-v` / `--verbose`, `-q` / `--quiet` and `--json`.
- Deterministic, HMAC-seeded strategies: `FAKE`, `HASH`, `MASK`, `NULLIFY`, `FPE_ID`, `FPE_UUID`,
  `KEEP`, with the semantic types `EMAIL`, `FIRST_NAME`, `LAST_NAME`, `PHONE`, `IBAN`, `ADDRESS`,
  `IP_ADDRESS` and `JSONB` (`json_paths`).
- Extraction of filtered tables with their FK parents and children up to `fk_depth`, strategy
  propagation from primary keys to foreign keys, and `linked_columns` for consistency across
  tables without a foreign key.
- Sinks: JDBC (target database), DUMP (SQL file, uncompressed, gzip or zstd) and NULL.
- Execution and plan reports in HTML and JSON, checkpoints to resume an interrupted run,
  timeouts and graceful cancellation.
- PostgreSQL 14 to 18. Native binary distributed as `.deb`, `.rpm`, Homebrew formula and
  Docker image.

[2.4]: https://github.com/brumeorg/Brume/compare/2.3...2.4
[2.3]: https://github.com/brumeorg/Brume/compare/2.2...2.3
[2.2]: https://github.com/brumeorg/Brume/compare/2.1...2.2
[2.1]: https://github.com/brumeorg/Brume/compare/2.0...2.1
[2.0]: https://github.com/brumeorg/Brume/compare/1.7...2.0
[1.7]: https://github.com/brumeorg/Brume/compare/1.6...1.7
[1.6]: https://github.com/brumeorg/Brume/compare/1.5...1.6
[1.5]: https://github.com/brumeorg/Brume/compare/1.4...1.5
[1.4]: https://github.com/brumeorg/Brume/compare/1.3...1.4
[1.3]: https://github.com/brumeorg/Brume/compare/1.2...1.3
[1.2]: https://github.com/brumeorg/Brume/compare/1.1...1.2
[1.1]: https://github.com/brumeorg/Brume/compare/1.0...1.1
[1.0]: https://github.com/brumeorg/Brume/releases/tag/1.0
