-- =============================================================================
-- e2e_edge.sql — edge cases for the functional E2E suite (ADR-0051)
-- =============================================================================
-- Used with src/test/resources/e2e/edge-brume.yml by EdgeCasesE2E :
--   "Account"        mixed-case table name, UUID primary key (FPE_UUID), reserved-word
--                    columns "user" / "order", UNIQUE columns, text that is hard to
--                    escape in a COPY stream (bio, kept as-is)
--   session          UUID foreign key → "Account" (FPE_UUID propagated)
--   order_line       composite primary key (order_no, line_no)
--   shipment         composite foreign key → order_line + UUID foreign key
--   holiday          UNIQUE date column, outside the default extraction : a DATE_SHIFT
--                    there makes values collide (#29a — rows lost, run goes on, reported)
--   "fiche client"   names PostgreSQL only takes quoted (#26c) : a space in the table name,
--                    accented columns, a column holding a double quote, an accented FK
--                    column → "Account" (FPE_UUID propagated)
--   badge            FK → "Account".nickname, a UNIQUE column that is not the primary key,
--                    shorter (varchar 20 vs 100) : a suffixed fake must be cut the same way
--                    on both sides or the FK breaks (#29c)
--   login_event      partitioned table (2024 / 2025), FK → "Account" declared on the
--                    partitioned table : one logical table, never its partitions (#71c)
--   "E2E Édition"    second schema, mixed case + accent + space (#26c : pg_dump -n, COPY)
--
-- 400 accounts with a chunk_size of 7 force many chunk flushes. Values are fixed :
-- account ids are md5-derived UUIDs, no NOW(), no random().
--
-- Scope with shipment + "Account" declared and fk_depth 1 :
--   "Account" 400 · session 10, "fiche client" 5, login_event 10, badge 6 (children of the accounts) ·
--   shipment 4 · order_line 4 (referenced by a shipment ; lines (101,1) and (103,1) are not)
-- =============================================================================

DROP SCHEMA IF EXISTS e2e_edge CASCADE;
CREATE SCHEMA e2e_edge;

CREATE TABLE e2e_edge."Account" (
    id       UUID PRIMARY KEY,
    "user"   VARCHAR(100) NOT NULL,
    "order"  INTEGER NOT NULL,
    email    VARCHAR(255) NOT NULL UNIQUE,
    nickname VARCHAR(100) NOT NULL UNIQUE,
    bio      TEXT
);

CREATE TABLE e2e_edge.session (
    id         BIGINT PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES e2e_edge."Account"(id),
    token      TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE e2e_edge.order_line (
    order_no BIGINT NOT NULL,
    line_no  INTEGER NOT NULL,
    label    TEXT NOT NULL,
    PRIMARY KEY (order_no, line_no)
);

CREATE TABLE e2e_edge.shipment (
    id         BIGINT PRIMARY KEY,
    order_no   BIGINT NOT NULL,
    line_no    INTEGER NOT NULL,
    account_id UUID NOT NULL REFERENCES e2e_edge."Account"(id),
    FOREIGN KEY (order_no, line_no) REFERENCES e2e_edge.order_line (order_no, line_no)
);

CREATE TABLE e2e_edge.holiday (
    id    BIGINT PRIMARY KEY,
    day   DATE NOT NULL UNIQUE,
    label TEXT NOT NULL
);

INSERT INTO e2e_edge.holiday (id, day, label)
SELECT i, DATE '2025-06-01' + i, 'day ' || i FROM generate_series(1, 60) AS i;

INSERT INTO e2e_edge."Account" (id, "user", "order", email, nickname, bio)
SELECT md5('account-' || i)::uuid, 'login_' || i, i % 7, 'person' || i || '@example.org', 'nick_' || i, NULL
FROM generate_series(1, 400) AS i;

-- Text that must survive the COPY / INSERT path byte for byte (strategy KEEP).
UPDATE e2e_edge."Account" SET bio = E'tab\there'                          WHERE id = md5('account-1')::uuid;
UPDATE e2e_edge."Account" SET bio = E'line1\nline2\r\nline3'              WHERE id = md5('account-2')::uuid;
UPDATE e2e_edge."Account" SET bio = E'back\\slash and \\N inside'         WHERE id = md5('account-3')::uuid;
UPDATE e2e_edge."Account" SET bio = 'quote''s "double" ; semicolon'       WHERE id = md5('account-4')::uuid;
UPDATE e2e_edge."Account" SET bio = '😀 émoji ünïcödé 中文'               WHERE id = md5('account-5')::uuid;
UPDATE e2e_edge."Account" SET bio = ''                                    WHERE id = md5('account-6')::uuid;
UPDATE e2e_edge."Account" SET bio = E'\\N'                                WHERE id = md5('account-7')::uuid;

INSERT INTO e2e_edge.session (id, account_id, token, created_at)
SELECT i, md5('account-' || (i * 37 % 400 + 1))::uuid, 'tok-' || i,
       TIMESTAMPTZ '2025-01-01 08:00:00+00' + (i || ' hours')::interval
FROM generate_series(1, 10) AS i;

INSERT INTO e2e_edge.order_line (order_no, line_no, label) VALUES
    (100, 1, 'keyboard'),
    (100, 2, 'mouse'),
    (101, 1, 'never shipped'),
    (102, 1, 'screen'),
    (102, 2, 'cable'),
    (103, 1, 'never shipped either');

INSERT INTO e2e_edge.shipment (id, order_no, line_no, account_id) VALUES
    (1, 100, 1, md5('account-1')::uuid),
    (2, 100, 2, md5('account-2')::uuid),
    (3, 102, 1, md5('account-3')::uuid),
    (4, 102, 2, md5('account-1')::uuid);

CREATE TABLE e2e_edge."fiche client" (
    id                 BIGINT PRIMARY KEY,
    "compte référent"  UUID NOT NULL REFERENCES e2e_edge."Account"(id),
    "prénom"           TEXT NOT NULL,
    "nom de famille"   TEXT NOT NULL,
    "téléphone"        TEXT,
    "note ""interne""" TEXT
);

INSERT INTO e2e_edge."fiche client" (id, "compte référent", "prénom", "nom de famille", "téléphone", "note ""interne""") VALUES
    (1, md5('account-1')::uuid, 'Élodie', 'Lefèvre', '+33 6 12 34 56 01', 'rappeler lundi'),
    (2, md5('account-2')::uuid, 'Jérôme', 'Dupré',   '+33 6 12 34 56 02', NULL),
    (3, md5('account-3')::uuid, 'Anaïs',  'Bénard',  NULL,                'client "fidèle"'),
    (4, md5('account-4')::uuid, 'Gaëlle', 'Moreau',  '+33 6 12 34 56 04', ''),
    (5, md5('account-5')::uuid, 'Noël',   'Château', '+33 6 12 34 56 05', 'tél. pro');

CREATE TABLE e2e_edge.badge (
    id              BIGINT PRIMARY KEY,
    holder_nickname VARCHAR(20) NOT NULL REFERENCES e2e_edge."Account"(nickname)
);

INSERT INTO e2e_edge.badge (id, holder_nickname)
SELECT i, 'nick_' || (i * 13 % 400 + 1) FROM generate_series(1, 6) AS i;

CREATE TABLE e2e_edge.login_event (
    id         BIGINT NOT NULL,
    happened   DATE NOT NULL,
    account_id UUID NOT NULL REFERENCES e2e_edge."Account"(id),
    ip         TEXT,
    PRIMARY KEY (id, happened)
) PARTITION BY RANGE (happened);

CREATE TABLE e2e_edge.login_event_2024 PARTITION OF e2e_edge.login_event FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE e2e_edge.login_event_2025 PARTITION OF e2e_edge.login_event FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');

INSERT INTO e2e_edge.login_event (id, happened, account_id, ip)
SELECT i, CASE WHEN i <= 5 THEN DATE '2024-03-01' ELSE DATE '2025-03-01' END + i,
       md5('account-' || i)::uuid, '10.0.0.' || i
FROM generate_series(1, 10) AS i;

DROP SCHEMA IF EXISTS "E2E Édition" CASCADE;
CREATE SCHEMA "E2E Édition";

CREATE TABLE "E2E Édition"."Client" (
    id       BIGINT PRIMARY KEY,
    "e-mail" TEXT NOT NULL UNIQUE,
    ville    TEXT
);

INSERT INTO "E2E Édition"."Client" (id, "e-mail", ville) VALUES
    (1, 'claire@exemple.fr', 'Saint-Étienne'),
    (2, 'hervé@exemple.fr',  'Besançon'),
    (3, 'zoé@exemple.fr',    NULL);
