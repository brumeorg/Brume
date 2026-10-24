<p align="center">
  <img src="assets/logo.png" alt="Brume" width="200"/>
</p>

**Brume** is a **PostgreSQL database pseudonymization tool** compliant with the GDPR. It copies a targeted subset of a source database to a target database or a .sql file while transforming personal data on the fly, all while preserving referential integrity (foreign keys).

> **Regulatory context** — The GDPR (Regulation (EU) 2016/679) explicitly encourages pseudonymization as a technical measure for risk reduction (Art. 25, Art. 89). Article 4.5 defines pseudonymization as the processing of personal data in such a way that it can no longer be attributed to a specific person without the use of additional information kept separately. Brume implements this definition: the `hmac-secret` and the `fpe-key` constitute this "additional information".

> ⚠️ **Legal position** — Brume produces a **pseudonymization within the meaning of Art. 4.5**, **not anonymization** within the meaning of recital 26. The target dataset remains **personal data** under the GDPR. Consult your DPO before any production processing.

---

## Key features

- **Strong deterministic pseudonymization** — same input + same secret → same output, across runs and across all releases of a major version (see [Determinism and versions](#determinism-and-versions)). Test environments stay consistent from one refresh to the next.
- **Foreign key preservation** — IDs are encrypted consistently (`FPE_ID`, `FPE_UUID`): FK constraints remain valid on the target database, without having to disable integrity checks. A rule declared on any column of a foreign key (`FPE_ID`, `FPE_UUID`, `HASH`, `FAKE`) is applied to every column it is linked to by foreign keys — nothing else to declare.
- **Automatic FK traversal in both directions** — if you extract `orders`, Brume also copies the rows within `fk_depth` FK hops of them (their `users`, their `order_items`, …) and, by default, every parent those rows need: the target never holds a dangling FK.
- **Declarative configuration** — a `brume.yml` file defines, per table and per column, the strategy to apply (`FAKE`, `HASH`, `MASK`, `NULLIFY`, `FPE_ID`, `FPE_UUID`, `DATE_SHIFT`, `KEEP`).
- **Safe on UNIQUE columns** — fake values on a unique column get a uniqueness suffix; a remaining collision never stops the run and is reported table by table. A dump never contains a duplicate key (see [UNIQUE columns and collisions](#unique-columns-and-collisions)).
- **Real-world schemas** — accented and spaced table / column names, partitioned tables, views (see [Schema support](#schema-support)).
- **Schema auto-scan** — `brume scan` introspects the source database and generates a pre-filled `brume.yml` with suggested rules for PII patterns (email, phone, address, IBAN, credentials…) and primary keys (`FPE_ID` / `FPE_UUID`).
- **Cross-table consistency** — `linked_columns` guarantees that the same real value produces the same fake value across multiple tables **without an FK link between them**. Columns linked by a foreign key don't need it.
- **JSONB columns** — `$.field.subfield` paths are pseudonymized individually.
- **Date handling** — `DATE_SHIFT` for deterministic ±N-day perturbation on `date`/`timestamp` columns (preserves age cohorts and event ordering), or `FAKE` + `type: DATE` for a fully synthetic DOB with configurable `min_year` / `max_year`.
- **k-anonymity audit** — `brume audit --anonymity` subcommand to measure residual re-identification risk and produce a DPO report.
- **PostgreSQL 12–18** supported (source and target).

---

## Prerequisites

- **PostgreSQL 12, 13, 14, 15, 16, 17 or 18** on the source database and the target database (a local `pg_dump` ≥ source major version is required — Postgres compatibility policy)
- ⚠️ **Security**: preferably use a read-only account.

---

## Installation on Debian/Ubuntu


```bash
# 1. Configure the repo (one time only)
curl -1sLf 'https://dl.cloudsmith.io/public/brume/brume/setup.deb.sh' | sudo -E bash

# 2. Install
sudo apt-get install brume

# Updates afterwards
sudo apt-get update && sudo apt-get upgrade brume

# 3. Configure Brume (according to your schema)
cp brume.example.yml brume.yml
```

## Installation on MacOS


```bash
# 1. Configure the repo (one time only)
brew tap brumeorg/brume
brew trust --formula brumeorg/brume/brume

# 2. Install
brew install brume

# Updates afterwards
brew upgrade brume

# 3. Configure Brume (according to your schema)
cp brume.example.yml brume.yml
```

## Installation on Fedora/CentOS


```bash
# 1. Configure the repo (one time only)
curl -1sLf 'https://dl.cloudsmith.io/public/brume/brume/setup.rpm.sh' | sudo -E bash

# 2. Install
sudo dnf install brume

# 3. Configure Brume (according to your schema)
cp brume.example.yml brume.yml
```

---

## Pseudonymization strategies

| Strategy | Description |
|---|---|
| `FAKE` | Realistic and deterministic fake value (Datafaker, HMAC-seeded). Requires `type`. On a `UNIQUE` column, names and addresses get a uniqueness suffix. |
| `FPE_ID` | Format-preserving encryption (FF1/FPE). Integer in → integer out, without collision — but **not of the same length**. Ideal for numeric PKs/FKs. |
| `FPE_UUID` | Deterministic UUID→UUID pseudonymization. Ideal for UUID PKs/FKs. |
| `MASK` | Partial masking depending on the type: keeps the prefix, replaces the rest with `*`. |
| `HASH` | One-way HMAC (non-reversible). 64 chars with HmacSHA256. |
| `DATE_SHIFT` | Deterministic signed day shift (±N days, HMAC-seeded) on `date` / `timestamp` columns. Preserves age cohorts and event ordering. Shift magnitude configured by `brume.date-shift.max-days` (default `30`). |
| `NULLIFY` | Replaces with `NULL`. The column must be nullable — `brume plan` rejects `NULLIFY` on a `NOT NULL` column. |
| `KEEP` | Copy without modification (default behavior for undeclared columns). |

Semantic types available for `FAKE` and `MASK`: `EMAIL`, `FIRST_NAME`, `LAST_NAME`, `PHONE`, `IBAN`, `ADDRESS`, `IP_ADDRESS`, `DATE`, `JSONB`.

---

## Configuration

### `.env.brume` — connections and secrets

```bash
brume init jdbc   # (or `brume init dump`) creates .env.brume from the matching template

# Then edit .env.brume as in the example
BRUME_HMAC_SECRET=my-secret-16-chars-min
BRUME_FPE_KEY=my-fpe-key-16ch
BRUME_SOURCE_HOST=db.prod.internal
BRUME_SOURCE_DB=app_production
BRUME_SOURCE_USER=brume_reader
BRUME_SOURCE_PASSWORD=...
BRUME_SOURCE_SSLMODE=require
BRUME_SCHEMA=public
BRUME_TARGET_HOST=localhost
BRUME_TARGET_DB=app_dev
BRUME_TARGET_USER=app
BRUME_TARGET_PASSWORD=...
```

### `brume.yml` — pseudonymization rules

```yaml
extraction:
  fk_depth: 1          # FK radius around the extracted rows, in both directions
  orphan_policy: KEEP_PARENTS   # default — see "FK traversal" below
  tables:
    - table: orders
      filter: "created_at >= '2025-01-01'"

anonymization:
  linked_columns:
    - semantic_key: user_email
      columns:
        - table: users
          column: email
        - table: audit_logs
          column: user_email   # Same real value → same fake value

  tables:
    - table: users
      columns:
        - name: id
          strategy: FPE_ID    # Automatically propagated to FKs orders.user_id
        - name: email
          strategy: FAKE
          type: EMAIL
        - name: phone
          strategy: MASK
          type: PHONE
        - name: notes
          strategy: NULLIFY
```

### FK traversal: `fk_depth` and `orphan_policy`

`fk_depth` is a **radius**: a row is copied when it lies at most `fk_depth` foreign-key hops away from a row of an `extraction.tables` entry, counting hops towards parents and towards children alike. With `orders` filtered on 2025:

| `fk_depth` | Rows copied |
|---|---|
| 1 | the 2025 orders, their `users`, their `order_items` |
| 2 | + the other orders of those users (even outside the filter), the `products` of the items |
| 3 | + the items of those other orders, … |

Each hop can pull many more rows on a real database: start with `1` and raise it only when needed.

`orphan_policy` decides what happens at the edge of the radius, when a copied row references a row beyond it:

| Value | Behavior |
|---|---|
| `KEEP_PARENTS` (default) | The parents of every copied row are copied too, whatever their distance (upward only — Brume never descends again from them). The target never holds a dangling FK. |
| `ALLOW_ORPHANS` | The radius is strict. References to rows beyond it are kept as-is: the run logs `[ORPHANS] …` per FK and reports them in `orphanReferences` (JSON report, `--json`). Loading works, but joins lose those rows and re-creating the FK constraints on the target fails. |

`brume plan` applies the same rule and counts exactly the rows `brume execute` writes. Design notes: ADR-0050.

### UNIQUE columns and collisions

Deterministic does not mean collision-free: Datafaker knows about 5,000 first names, so two different real values can get the same fake one. On a `UNIQUE` column, the target would reject the second row. Brume handles this in three ways:

1. **Prevention.** On a column that is unique on its own (primary key, `UNIQUE` constraint or unique index), `FAKE` with `FIRST_NAME`, `LAST_NAME` or `ADDRESS` appends 64 bits derived from the exact real value: `Darrell.9f3ac21b04e7d5a6`, cut to fit `varchar(n)`. Columns that must hold the same value — both sides of a foreign key, `linked_columns` — receive the very same suffixed value.
2. **Refusal at `plan`.** `MASK` and `FAKE` + `DATE` on a `UNIQUE` column collide almost surely: `brume plan` rejects them (`CONFIG_SCHEMA_UNIQUE_NOT_INJECTIVE`).
3. **Explicit loss.** A remaining collision does not stop the run (exit code 0). The colliding row is not written, and the loss is reported: a `[UNIQUE_COLLISION]` warning, `[WARN] N ligne(s) perdue(s)` with the count per table in the console report, an error insight in the plan-vs-actual comparison, and `uniqueCollisions` per table in the JSON report. Rows that reference a lost row stay in the target with a dangling foreign key.

With the DUMP sink, rows that would break a unique index are dropped before being written, so the file always restores with `psql`. Unique indexes on an expression (`lower(email)`) or partial ones (`WHERE …`) are not checked.

Residual collision rates on a `UNIQUE` column, measured or computed:

| Rule | Collisions |
|---|---|
| `KEEP`, `HASH`, `FPE_ID`, `FPE_UUID` | None |
| `FAKE` `FIRST_NAME` / `LAST_NAME` / `ADDRESS` (suffixed) | ~1 in 37 million for 1 million rows (more on a `varchar` shorter than 12) |
| `FAKE` `EMAIL`, `IBAN` | None out of 200,000 values. Two emails that differ only by case get the same fake email. |
| `FAKE` `PHONE` | 81 (`en`) to 474 (`fr`) per million values |
| `FAKE` `IP_ADDRESS` | ~135 per million values |
| `DATE_SHIFT` | High on dense dates (14 out of 60 consecutive days) |
| Multi-column `UNIQUE` | No suffix: depends on the combination |

For zero collisions on a unique column, use `HASH`, `FPE_ID` or `FPE_UUID`.

### Schema support

- **Table and column names** are written in `brume.yml` exactly as stored in PostgreSQL — case, accents and spaces included, without SQL quotes (`table: fiche client`, `name: prénom`). Any legal PostgreSQL name is accepted (up to 63 bytes in UTF-8); Brume quotes it in every SQL statement it generates.
- **Partitioned tables** count as one table: declare filters and rules on the partitioned table (`payment`), never on its partitions. Brume reads and writes through it, and PostgreSQL routes each row to its partition. Foreign keys declared partition by partition are treated as foreign keys of the partitioned table.
- **Views, materialized views and foreign tables** are not tables: `brume scan` ignores them and `brume.yml` cannot target them. Their definitions are still recreated on the target by the schema copy.

### Strategy

The `strategy` defines **how a real value is transformed** before being written to the target database. It is declared at the column level in `brume.yml`.

| Value | Description |
|---|---|
| `FAKE` | Replaces with a realistic and deterministic fake value generated via Datafaker, seeded by HMAC. Requires a `type`. On a column that is unique on its own, `FIRST_NAME`, `LAST_NAME` and `ADDRESS` values get a uniqueness suffix (see [UNIQUE columns and collisions](#unique-columns-and-collisions)). |
| `MASK` | Partial masking that preserves the structure of the field (e.g. keeps the first 3 digits of a phone number). Requires a `type`. |
| `HASH` | One-way HMAC SHA-256 keyed with the secret. Non-reversible. Deterministic: the same source value always produces the same hash — joins remain valid. |
| `NULLIFY` | Forces `NULL`. The column must be nullable. Reduces the PII surface to zero. |
| `KEEP` | Copies the value without modification. To be used for non-sensitive or already-anonymized columns. Default behavior for undeclared columns. |
| `FPE_ID` | Format-preserving encryption (FF1/BouncyCastle) for non-negative integer identifiers. Bijective: two different ids never give the same output, so primary keys stay unique. The output is an integer but **not of the same length** — FF1 needs a minimum domain size, so small ids come out longer (with a given key, `1` → `12227206`, `12345` → `1977275239`). An id up to 2 147 483 647 is encrypted within that range and still fits an `integer` column; a larger id is encrypted on 18 digits (up to 999 999 999 999 999 999) and needs a `bigint`. Negative ids are rejected. Do not rely on the length or the order of the ids. Automatically propagated to FK columns pointing to this PK. |
| `FPE_UUID` | UUID→UUID pseudonymization via HMAC-SHA256. Preserves the `8-4-4-4-12` format and uniqueness. Automatically propagated to UUID FKs. |
| `DATE_SHIFT` | Deterministic signed day shift on temporal columns (`date`, `timestamp`, `timestamptz`, `time`, `interval`). Output = input ± N days where N is drawn from `[-max, +max]` via an HMAC seed of the input value. Preserves age cohorts and event ordering within the window. Not reversible without the HMAC secret. Magnitude configured by `brume.date-shift.max-days` (default `30`) — override via the env var `BRUME_DATE_SHIFT_MAX_DAYS`. |

### Type

The `type` specifies the **semantics of the field** for the `FAKE` and `MASK` strategies, so that Brume generates contextually coherent fake data.

| Value | Description |
|---|---|
| `EMAIL` | Email address — e.g. `alice@example.com` |
| `FIRST_NAME` | First name — e.g. `Alice` |
| `LAST_NAME` | Last name — e.g. `Dupont` |
| `PHONE` | Phone number in the format of the configured locale; the first digit comes from Datafaker, the others are drawn from the HMAC seed — e.g. `08 37 30 90 45` (`fr`), `(369) 107-2808` (`en`) |
| `ADDRESS` | Postal address — e.g. `12 rue de la Paix, Paris` |
| `IBAN` | IBAN bank account number — e.g. `FR76 3000 6000 0112 3456 7890 189` |
| `IP_ADDRESS` | IPv4 address — e.g. `192.168.1.42` |
| `DATE` | ISO date — e.g. `1987-11-02`. Combined with `FAKE`, draws a deterministic date from an adult-DOB anchor (1935-2008 by default). Range overridable per column via `min_year` / `max_year` (see below). Written back via PostgreSQL `Types.OTHER` inference — works on `date` and `timestamp` target columns. |
| `JSONB` | JSON column: delegates to `JsonPathProcessor` to anonymize nested fields individually via `json_paths`. |

#### Custom date range (`FAKE` + `DATE`)

For datasets whose DOBs don't fit the adult default — pediatric apps, student directories, pensioner-only — override the year range per column:

```yaml
- name: child_birthday
  strategy: FAKE
  type: DATE
  min_year: 2008    # inclusive, default 1935
  max_year: 2026    # exclusive, default 2008
```

Both bounds must be set together, both in `[1900, 2100]`, and `min_year < max_year`. Omit both to fall back to the adult-DOB default. Setting bounds on a non-FAKE strategy or on a non-DATE type is rejected at config validation.

---

## Determinism and versions

For the same `hmac-secret`, `fpe-key` and locale, every strategy returns the same output for the same input — across runs, machines, and **all releases of the same major version**. A refreshed test environment keeps the same pseudonyms, and two databases pseudonymized separately with the same secrets still join. Reference outputs are pinned in the test suite; changing one requires a new major version.

**2.0** changes some outputs compared with 1.x:

- `FAKE` + `PHONE`: the format is kept, the digits are now drawn from the HMAC seed (1.6 % of values collided before).
- `FAKE` names and addresses on a `UNIQUE` column carry the uniqueness suffix.
- DUMP sink: timestamps are written with their UTC offset. A `timestamptz` value restores to the right instant whatever the time zone of the restoring session — it used to be shifted by the time-zone difference between the machine running Brume and the server.

When upgrading from 1.x, pseudonymize again every database you need to join or compare: the values above change once.

---

## CLI

```bash
brume init jdbc   # Bootstrap for JDBC sink (writes to a target DB)
brume init dump   # Bootstrap for DUMP sink (writes a SQL file)
brume init        # No subcommand → interactive prompt (JDBC fallback in non-TTY shells)
brume scan        # Introspects the source schema and generates a pre-filled brume.yml
brume plan        # Estimates volumes and detects uncovered PII columns (read-only)
brume execute     # Runs the full pseudonymization
brume dry-run     # Runs without writing (NullSink — for validating the config)
brume --help
```

`brume init` is a one-shot bootstrap command for new projects. It probes `pg_dump`
(expected major version ≥ 17), prints the OS-specific install command if missing
(non-blocking warning — init continues), and copies the bundled `brume.yml` and a
sink-specific `.env.brume` template into the current directory. Existing files are
kept by default (prompt to overwrite). The dedicated `.env.brume` filename avoids
collisions with Docker Compose or other tooling that reads `.env`. See ADR-0045.

### `brume scan` — auto-generate `brume.yml` from the source schema

Once `.env.brume` points to a reachable source database, `brume scan` reads the
schema and emits a `brume.yml` pre-filled with suggested strategies:

- **PII columns** (`email`, `phone`, `address`, `iban`, `password`, `token`, `ssn`, …)
  are mapped to the appropriate `FAKE` / `MASK` / `HASH` / `NULLIFY` strategy with the
  matching semantic `type`.
- **Birth-date columns** (`birthday`, `birth`, `dob`) are mapped to `FAKE` + `type: DATE`
  — the output is a plausible adult DOB (1935-2008 anchor). For non-adult datasets,
  override per column with `min_year` / `max_year` after the scan.
- **Primary keys** are promoted to `FPE_ID` (numeric) or `FPE_UUID` (uuid).
- **Foreign keys** are intentionally left blank — they are resolved at run time
  by FK strategy propagation (same strategy as the referenced PK).
- **Temporal columns** (`date`, `timestamp`, `timestamptz`, `time`, `interval`) are
  type-aware: a column name matching a text-oriented pattern (e.g.
  `date_last_password_update` matching `password` → `HASH`) is skipped rather than
  producing a hash string that could not be written back into a timestamp slot. Add
  `DATE_SHIFT` by hand for these columns if you need to perturb the date itself.
- The `extraction.tables` block is emitted as a TODO stub: filter choices
  (temporal window, tenant scope) are business-driven and cannot be inferred
  from the schema alone.

If a `brume.yml` already exists, scan prompts before overwriting; pass `--force`
in CI shells. The output is a **proposal** — review it before running `plan` or
`execute`. The full pattern-to-strategy mapping is documented in ADR-0048.

```bash
brume scan           # Interactive — prompts before overwriting brume.yml
brume scan --force   # CI mode — overwrites without prompting
```

Flags available on all subcommands:

| Flag | Effect |
|---|---|
| `-v` / `--verbose` | DEBUG logs |
| `-q` / `--quiet` | ERROR logs only (final report always visible) |
| `--json` | Machine-readable output on stdout, JSON logs on stderr |

---

## k-anonymity audit

Brume measures the residual re-identification risk via k-anonymity (Sweeney 2002):

```bash
brume audit --anonymity \
  --quasi-id "users:birth_date,zip_code,gender" \
  --report-format markdown
```

The report lists the **singleton classes** (unique rows = re-identifiable) and emits actionable recommendations. Designed to be handed to the DPO.

**V1 limitations:** only k-anonymity is measured (not l-diversity or t-closeness); intra-table audit only.

---

## GDPR best practices

- **Do not refer to the target dataset as "anonymous data"** — it remains personal data under the GDPR.
- **Protect `hmac-secret` and `fpe-key`** at the same level as the source data. Their leak invalidates the pseudonymization.
- **Limit the recipients** of the pseudonymized dataset to the strict minimum (tests, QA, demo).
- **Consult your DPO** before any production processing; the `brume audit --anonymity` report is a decision-support tool, not a certification.

---

## License

See [LICENSE](LICENSE).
