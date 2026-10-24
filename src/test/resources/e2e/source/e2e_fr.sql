-- =============================================================================
-- e2e_fr.sql — source fixture for the functional E2E journey (ADR-0049)
-- =============================================================================
-- French column names, as found in real French-speaking schemas. Used only by
-- `brume scan` to assert French PII detection. Two traps are kept on purpose:
--   clients.nombre_commandes (INTEGER) — substring 'nom' → known gap #126b
--   clients.date_inscription (TIMESTAMP) — must NOT be flagged
-- =============================================================================

DROP SCHEMA IF EXISTS e2e_fr CASCADE;
CREATE SCHEMA e2e_fr;

CREATE TABLE e2e_fr.clients (
    id                      BIGINT PRIMARY KEY,
    prenom                  VARCHAR(100) NOT NULL,
    nom                     VARCHAR(100) NOT NULL,
    courriel                VARCHAR(255) NOT NULL,
    telephone               VARCHAR(20),
    portable                VARCHAR(20),
    adresse                 TEXT,
    code_postal             VARCHAR(10),
    ville                   VARCHAR(100),
    date_naissance          DATE,
    sexe                    CHAR(1),
    mot_de_passe            VARCHAR(255),
    numero_securite_sociale VARCHAR(15),
    iban                    VARCHAR(34),
    adresse_ip              VARCHAR(45),
    nombre_commandes        INTEGER NOT NULL DEFAULT 0,
    date_inscription        TIMESTAMP NOT NULL
);

CREATE TABLE e2e_fr.produits (
    id      BIGINT PRIMARY KEY,
    libelle VARCHAR(255) NOT NULL,
    prix    NUMERIC(10, 2) NOT NULL
);

CREATE TABLE e2e_fr.commandes (
    id                BIGINT PRIMARY KEY,
    client_id         BIGINT NOT NULL REFERENCES e2e_fr.clients(id),
    adresse_livraison TEXT,
    montant_total     NUMERIC(10, 2) NOT NULL,
    statut            VARCHAR(20) NOT NULL,
    date_commande     TIMESTAMP NOT NULL
);

CREATE TABLE e2e_fr.lignes_commande (
    id          BIGINT PRIMARY KEY,
    commande_id BIGINT NOT NULL REFERENCES e2e_fr.commandes(id),
    produit_id  BIGINT NOT NULL REFERENCES e2e_fr.produits(id),
    quantite    INTEGER NOT NULL
);

INSERT INTO e2e_fr.clients (id, prenom, nom, courriel, telephone, portable, adresse, code_postal, ville,
                            date_naissance, sexe, mot_de_passe, numero_securite_sociale, iban, adresse_ip,
                            nombre_commandes, date_inscription) VALUES
    (1, 'Camille', 'Durand', 'camille.durand@exemple.fr', '+33140000001', '+33600000001',
     '10 rue de Rivoli', '75004', 'Paris', '1988-04-12', 'F', 'motdepasse-hache-1',
     '288047510400112', 'FR7630001007941234567890185', '192.168.10.1', 2, '2024-02-01 10:00:00'),
    (2, 'Hugo', 'Lefebvre', 'hugo.lefebvre@exemple.fr', '+33478000002', '+33600000002',
     '4 quai Saint-Antoine', '69002', 'Lyon', '1975-10-30', 'M', 'motdepasse-hache-2',
     '175106938200245', 'FR7610278060610001020480101', '10.1.2.3', 1, '2024-02-02 10:00:00');

INSERT INTO e2e_fr.produits (id, libelle, prix) VALUES
    (10, 'Cafetière', 49.90),
    (11, 'Bouilloire', 29.90);

INSERT INTO e2e_fr.commandes (id, client_id, adresse_livraison, montant_total, statut, date_commande) VALUES
    (100, 1, '10 rue de Rivoli, 75004 Paris', 79.80, 'LIVREE', '2025-01-10 09:00:00'),
    (101, 2, '4 quai Saint-Antoine, 69002 Lyon', 49.90, 'EN_COURS', '2025-02-11 15:00:00');

INSERT INTO e2e_fr.lignes_commande (id, commande_id, produit_id, quantite) VALUES
    (1000, 100, 10, 1),
    (1001, 100, 11, 1),
    (1002, 101, 10, 1);
