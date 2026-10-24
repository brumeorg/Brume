-- =============================================================================
-- e2e_en.sql — source fixture for the functional E2E journey (ADR-0049)
-- =============================================================================
-- English column names. Used by `brume scan` (EN detection) AND by `brume plan` /
-- `brume execute` with src/test/resources/e2e/brume.yml.
--
-- Every value is fixed (no NOW(), no sequence) so the pseudonymized target can be
-- compared value-by-value against the golden dataset in e2e/expected/e2e_en/.
-- Each table carries a KEEP column with unique values (created_at / label) that
-- the test uses as a natural key to pair source and target rows.
--
-- Scope of the execute scenario (filter: orders.created_at >= 2025-01-01):
--   orders       3003-3010 extracted (8)  — 3001, 3002 are 2024 → excluded
--   order_items  4003-4015 extracted (13) — 4001, 4002 belong to 2024 orders
--   users        1001-1005 extracted (5)  — FK parents of orders + managers ;
--                1006 only owns 2024 order 3002 → excluded
--   products     2001-2005 extracted (5)  — FK parents of order_items ;
--                2006 only referenced by 2024 item 4002 → excluded
--   audit_logs   5001-5005 extracted (5)  — no filter, no FK (linked_columns on email)
--
-- FK self-reference cycle: 1001.manager → 1005, 1005.manager → 1001.
-- =============================================================================

DROP SCHEMA IF EXISTS e2e_en CASCADE;
CREATE SCHEMA e2e_en;

CREATE TABLE e2e_en.users (
    id            BIGINT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    phone         VARCHAR(20),
    iban          VARCHAR(34),
    ip_address    VARCHAR(45),
    address_json  JSONB,
    birth_date    DATE,
    password_hash VARCHAR(255),
    ssn           VARCHAR(11),
    gender        VARCHAR(10),
    company_name  VARCHAR(100),
    manager_id    BIGINT REFERENCES e2e_en.users(id),
    last_login_at TIMESTAMP,
    created_at    TIMESTAMP NOT NULL
);

CREATE TABLE e2e_en.products (
    id          BIGINT PRIMARY KEY,
    label       VARCHAR(255) NOT NULL,
    description TEXT,
    price       NUMERIC(10, 2) NOT NULL,
    created_at  TIMESTAMP NOT NULL
);

CREATE TABLE e2e_en.orders (
    id               BIGINT PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES e2e_en.users(id),
    shipping_address TEXT,
    total_amount     NUMERIC(10, 2) NOT NULL,
    notes            TEXT,
    created_at       TIMESTAMP NOT NULL
);

CREATE TABLE e2e_en.order_items (
    id         BIGINT PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES e2e_en.orders(id),
    product_id BIGINT NOT NULL REFERENCES e2e_en.products(id),
    quantity   INTEGER NOT NULL
);

CREATE TABLE e2e_en.audit_logs (
    id         BIGINT PRIMARY KEY,
    user_email VARCHAR(255),
    action     VARCHAR(100) NOT NULL,
    ip_address VARCHAR(45),
    payload    JSONB,
    created_at TIMESTAMP NOT NULL
);

-- Users: managers first, cycle closed by the UPDATE below.
INSERT INTO e2e_en.users (id, email, first_name, last_name, phone, iban, ip_address, address_json,
                          birth_date, password_hash, ssn, gender, company_name, manager_id,
                          last_login_at, created_at) VALUES
    (1001, 'alice.martin@example.com', 'Alice', 'Martin', '+33612345678', 'FR7614508359858489935765085',
     '192.168.1.10', '{"street": "12 rue de la Paix", "city": "Paris", "zip": "75001"}',
     '1985-03-14', '$2a$10$alicehashalicehashalice', '123-45-6789', 'F', 'Acme Corp', NULL,
     '2025-04-20 08:00:00', '2024-01-10 09:00:00'),
    (1002, 'bob.dupont@example.com', 'Bob', 'Dupont', '+33698765432', 'FR7630004000031234567890143',
     '10.0.0.47', '{"street": "5 avenue des Champs", "city": "Lyon", "zip": "69001"}',
     '1990-07-02', '$2a$10$bobhashbobhashbobhashb', '234-56-7890', 'M', 'Acme Corp', 1001,
     '2025-04-18 17:30:00', '2024-01-11 09:00:00'),
    (1003, 'claire.bernard@example.com', 'Claire', 'Bernard', '+33655443322', 'FR7610907004910010042556727',
     '172.16.0.5', '{"street": "8 boulevard Haussmann", "city": "Marseille", "zip": "13001"}',
     '1978-11-23', '$2a$10$clairehashclairehashcl', '345-67-8901', 'F', 'Globex', 1002,
     '2025-03-02 12:15:00', '2024-01-12 09:00:00'),
    (1004, 'david.leroy@example.com', 'David', 'Leroy', NULL, 'FR7617569000706467381965F85',
     '203.0.113.42', '{"street": "3 place Bellecour", "city": "Bordeaux", "zip": "33000"}',
     '2001-05-30', '$2a$10$davidhashdavidhashdavi', NULL, 'M', NULL, NULL,
     NULL, '2024-01-13 09:00:00'),
    (1005, 'emma.petit@example.com', 'Emma', 'Petit', '+33677889900', 'FR7630006000011234567890189',
     '198.51.100.7', '{"street": "22 rue du Commerce", "city": "Nantes", "zip": "44000"}',
     '1969-09-09', '$2a$10$emmahashemmahashemmaha', '456-78-9012', 'F', 'Initech', 1001,
     '2025-04-01 07:45:00', '2024-01-14 09:00:00'),
    (1006, 'frank.moreau@example.com', 'Frank', 'Moreau', '+33611223344', 'FR7612739000706154887563S11',
     '192.0.2.200', '{"street": "1 quai de la Fosse", "city": "Nantes", "zip": "44000"}',
     '1995-12-01', '$2a$10$frankhashfrankhashfran', '567-89-0123', 'M', 'Umbrella', NULL,
     '2024-12-24 20:00:00', '2024-01-15 09:00:00');

UPDATE e2e_en.users SET manager_id = 1005 WHERE id = 1001;

INSERT INTO e2e_en.products (id, label, description, price, created_at) VALUES
    (2001, 'Laptop Pro 15',      'High-end laptop',              1299.00, '2023-06-01 10:00:00'),
    (2002, 'Mechanical keyboard','RGB, brown switches',            129.90, '2023-06-02 10:00:00'),
    (2003, '4K monitor 27',      'UHD with HDR',                   449.00, '2023-06-03 10:00:00'),
    (2004, 'Ergonomic mouse',    NULL,                              59.90, '2023-06-04 10:00:00'),
    (2005, 'HD webcam',          '1080p with built-in microphone',  79.00, '2023-06-05 10:00:00'),
    (2006, 'USB-C dock',         'Only sold in 2024',              189.00, '2023-06-06 10:00:00');

INSERT INTO e2e_en.orders (id, user_id, shipping_address, total_amount, notes, created_at) VALUES
    (3001, 1001, '12 rue de la Paix, 75001 Paris',        129.90, 'Out of filter',        '2024-06-15 10:00:00'),
    (3002, 1006, '1 quai de la Fosse, 44000 Nantes',      189.00, NULL,                   '2024-11-30 14:30:00'),
    (3003, 1001, '12 rue de la Paix, 75001 Paris',       1428.90, 'Gift wrap',            '2025-01-05 09:15:00'),
    (3004, 1002, '15 rue Victor Hugo, 69002 Lyon',       1299.00, 'Business',             '2025-02-14 11:00:00'),
    (3005, 1003, '8 bd Haussmann, 13001 Marseille',        59.90, NULL,                   '2025-02-28 16:45:00'),
    (3006, 1001, '12 rue de la Paix, 75001 Paris',        449.00, 'Installation included','2025-03-10 08:30:00'),
    (3007, 1004, '3 place Bellecour, 33000 Bordeaux',     189.80, NULL,                   '2025-03-22 13:00:00'),
    (3008, 1005, '22 rue du Commerce, 44000 Nantes',     1827.00, 'Company order',        '2025-04-01 10:00:00'),
    (3009, 1002, '5 avenue des Champs, 69001 Lyon',       158.00, 'Return allowed',       '2025-04-10 15:30:00'),
    (3010, 1003, '8 bd Haussmann, 13001 Marseille',       578.90, 'Express 24h',          '2025-04-15 09:00:00');

INSERT INTO e2e_en.order_items (id, order_id, product_id, quantity) VALUES
    (4001, 3001, 2002, 1),
    (4002, 3002, 2006, 1),
    (4003, 3003, 2001, 1),
    (4004, 3003, 2002, 1),
    (4005, 3004, 2001, 1),
    (4006, 3005, 2004, 1),
    (4007, 3006, 2003, 1),
    (4008, 3007, 2002, 1),
    (4009, 3007, 2004, 1),
    (4010, 3008, 2001, 1),
    (4011, 3008, 2003, 1),
    (4012, 3008, 2005, 1),
    (4013, 3009, 2005, 2),
    (4014, 3010, 2003, 1),
    (4015, 3010, 2002, 1);

-- audit_logs.user_email shares values with users.email (linked_columns):
-- 5001 + 5004 → Alice (same fake value expected twice), 5002 → Bob, 5003 → Claire,
-- 5005 → Frank, whose users row is NOT extracted.
INSERT INTO e2e_en.audit_logs (id, user_email, action, ip_address, payload, created_at) VALUES
    (5001, 'alice.martin@example.com', 'LOGIN_SUCCESS', '192.168.1.10',
     '{"user": {"email": "alice.martin@example.com", "session": {"token": "abc123tok456"}}, "metadata": {"source": "web"}}',
     '2025-01-05 09:10:00'),
    (5002, 'bob.dupont@example.com', 'ORDER_CREATED', '10.0.0.47',
     '{"user": {"email": "bob.dupont@example.com", "session": {"token": "def789tok012"}}, "metadata": {"source": "mobile"}}',
     '2025-02-14 10:58:00'),
    (5003, 'claire.bernard@example.com', 'PAYMENT_VALIDATED', '172.16.0.5',
     '{"user": {"email": "claire.bernard@example.com", "session": {"token": "ghi345tok678"}}, "metadata": {"source": "web"}}',
     '2025-02-28 16:40:00'),
    (5004, 'alice.martin@example.com', 'LOGOUT', '192.168.1.10',
     '{"user": {"email": "alice.martin@example.com", "session": {"token": "jkl901tok234"}}, "metadata": {"source": "web"}}',
     '2025-01-05 18:30:00'),
    (5005, 'frank.moreau@example.com', 'FAILED_LOGIN', '192.0.2.200',
     '{"user": {"email": "frank.moreau@example.com", "session": {"token": null}}, "metadata": {"source": "api"}}',
     '2025-03-01 02:15:00');
