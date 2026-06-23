<p align="center">
  <img src="assets/logo.png" alt="Brume" width="200"/>
</p>

**Brume** is a **PostgreSQL database pseudonymization tool** compliant with the GDPR. It copies a targeted subset of a source database to a target database or a .sql file while transforming personal data on the fly, all while preserving referential integrity (foreign keys).

> **Regulatory context** — The GDPR (Regulation (EU) 2016/679) explicitly encourages pseudonymization as a technical measure for risk reduction (Art. 25, Art. 89). Article 4.5 defines pseudonymization as the processing of personal data in such a way that it can no longer be attributed to a specific person without the use of additional information kept separately. Brume implements this definition: the `hmac-secret` and the `fpe-key` constitute this "additional information".

> ⚠️ **Legal position** — Brume produces a **pseudonymization within the meaning of Art. 4.5**, **not anonymization** within the meaning of recital 26. The target dataset remains **personal data** under the GDPR. Consult your DPO before any production processing.

---

## Key features

- **Strong deterministic pseudonymization** — same input + same secret → same output. Two runs produce identical results, ensuring consistency across test environments.
- **Foreign key preservation** — IDs are encrypted consistently (`FPE_ID`, `FPE_UUID`): FK constraints remain valid on the target database, without having to disable integrity checks.
- **Automatic FK parent and child traversal** — if you extract `orders`, Brume automatically traverses the referenced `users`, up to `fk_depth` levels.
- **Declarative configuration** — a `brume.yml` file defines, per table and per column, the strategy to apply (`FAKE`, `HASH`, `MASK`, `NULLIFY`, `FPE_ID`, `FPE_UUID`, `KEEP`).
- **Cross-table consistency** — `linked_columns` guarantees that the same real value produces the same fake value across multiple tables without an FK link between them.
- **JSONB columns** — `$.field.subfield` paths are pseudonymized individually.
- **k-anonymity audit** — `brume audit --anonymity` subcommand to measure residual re-identification risk and produce a DPO report.
- **PostgreSQL 14–18** supported (source and target).

---

## Prerequisites

- **PostgreSQL 14, 15, 16, 17 or 18** on the source database and the target database
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
| `FAKE` | Realistic and deterministic fake value (Datafaker, HMAC-seeded). Requires `type`. |
| `FPE_ID` | Format-preserving encryption (FF1/FPE). Integer in → integer out. Ideal for numeric PKs/FKs. |
| `FPE_UUID` | Deterministic UUID→UUID pseudonymization. Ideal for UUID PKs/FKs. |
| `MASK` | Partial masking depending on the type: keeps the prefix, replaces the rest with `*`. |
| `HASH` | One-way HMAC (non-reversible). 64 chars with HmacSHA256. |
| `NULLIFY` | Replaces with `NULL`. The column must be nullable. |
| `KEEP` | Copy without modification (default behavior for undeclared columns). |

Semantic types available for `FAKE` and `MASK`: `EMAIL`, `FIRST_NAME`, `LAST_NAME`, `PHONE`, `IBAN`, `ADDRESS`, `IP_ADDRESS`, `JSONB`.

---

## Configuration

### `.env` — connections and secrets

```bash
cp .env.template .env

# Then edit the .env as in the example
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
  fk_depth: 3          # Automatic FK depth levels
  tables:
    - table: orders
      filter: "created_at >= '2025-01-01'"
    - table: order_items

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

### Strategy

The `strategy` defines **how a real value is transformed** before being written to the target database. It is declared at the column level in `brume.yml`.

| Value | Description |
|---|---|
| `FAKE` | Replaces with a realistic and deterministic fake value generated via Datafaker, seeded by HMAC. Requires a `type`. |
| `MASK` | Partial masking that preserves the structure of the field (e.g. keeps the first 3 digits of a phone number). Requires a `type`. |
| `HASH` | One-way HMAC SHA-256 keyed with the secret. Non-reversible. Deterministic: the same source value always produces the same hash — joins remain valid. |
| `NULLIFY` | Forces `NULL`. The column must be nullable. Reduces the PII surface to zero. |
| `KEEP` | Copies the value without modification. To be used for non-sensitive or already-anonymized columns. Default behavior for undeclared columns. |
| `FPE_ID` | Format-preserving encryption (FF1/BouncyCastle) for numeric identifiers. Integer input → integer output of the same length. Ideal for numeric PKs/FKs — automatically propagated to FK columns pointing to this PK. |
| `FPE_UUID` | UUID→UUID pseudonymization via HMAC-SHA256. Preserves the `8-4-4-4-12` format and uniqueness. Automatically propagated to UUID FKs. |

### Type

The `type` specifies the **semantics of the field** for the `FAKE` and `MASK` strategies, so that Brume generates contextually coherent fake data.

| Value | Description |
|---|---|
| `EMAIL` | Email address — e.g. `alice@example.com` |
| `FIRST_NAME` | First name — e.g. `Alice` |
| `LAST_NAME` | Last name — e.g. `Dupont` |
| `PHONE` | Phone number — e.g. `+33 6 12 34 56 78` |
| `ADDRESS` | Postal address — e.g. `12 rue de la Paix, Paris` |
| `IBAN` | IBAN bank account number — e.g. `FR76 3000 6000 0112 3456 7890 189` |
| `IP_ADDRESS` | IPv4 address — e.g. `192.168.1.42` |
| `JSONB` | JSON column: delegates to `JsonPathProcessor` to anonymize nested fields individually via `json_paths`. |

---

## CLI

```bash
brume plan        # Estimates volumes and detects uncovered PII columns (read-only)
brume execute     # Runs the full pseudonymization
brume dry-run     # Runs without writing (NullSink — for validating the config)
brume --help
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
