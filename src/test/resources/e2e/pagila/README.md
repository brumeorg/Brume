# Pagila — sample database for the E2E suite (ADR-0051)

Copied unmodified from <https://github.com/devrimgunduz/pagila>, commit
`aeb87bcb0f9751bffb67bd7f4376e8534746d008` (2026-07-28), under the PostgreSQL License
(see `LICENSE.txt`). Fictional data — no real personal data.

Why this commit : the next upstream commits add a `vector` column (pgvector extension) and a
`VIRTUAL` generated column (PostgreSQL 18) that the PostgreSQL 16 servers of the CI cannot load.

| File | Upstream file | sha256 of the uncompressed upstream file |
|---|---|---|
| `pagila-schema.sql.gz` | `pagila-schema.sql` | `1450e2e74569680ab689b9244a47541820210802c1ad5817be31a03aa5e8a8a5` |
| `pagila-data.sql.gz` | `pagila-data.sql` | `c8bb1978d9bdc7d0e79821b7380908a6812b704340555e7ce859770dc5bcbe40` |
| `LICENSE.txt` | `LICENSE.txt` | `516e7dac679ac1eeb62d5614b01c4e7318154e9a147377d6264954215997ff38` |

Loaded by `PagilaE2E` with `psql`. One substitution happens at load time, not in these
files: `uuidv7()` (PostgreSQL 18+) becomes `gen_random_uuid()` so the schema loads on the
PostgreSQL 16 servers of the CI. The data sets every uuid explicitly, so the default is
never used.

To update: download the three files at a newer commit, gzip with `gzip -9 -n`, update this table.
