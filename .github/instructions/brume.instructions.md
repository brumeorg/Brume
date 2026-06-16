---
applyTo: "**"
---

# Besoin fonctionnel & Vision produit — Brume

## Contexte

Il n'existe pas, à notre connaissance, de solution open source permettant d'anonymiser des données d'une base de production vers d'autres environnements de manière simple, configurable et fiable.

Ce problème est **irritant au quotidien** : lorsqu'un incident se produit en production, le reproduire et le déboguer dans un environnement hors-prod est difficile car les données réelles (potentiellement personnelles) ne peuvent pas être copiées telles quelles.

---

## Besoin fonctionnel

### Objectif principal

Permettre à une équipe technique de **copier un échantillon d'une base de données de production** vers une autre instance (dev, staging, QA…), en **anonymisant les données sensibles à la volée**, selon une **configuration définie par l'utilisateur**.

### Ce que le système doit faire

1. **Extraire un échantillon ciblé** depuis la base source.
   - L'utilisateur désigne les tables qu'il veut extraire, avec d'éventuels filtres (ex : les 1 000 dernières commandes, les événements du mois dernier).

2. **Construire un dataset complet et cohérent** (remontée récursive des parents FK).
   - Si une table extraite a des clés étrangères vers d'autres tables, les lignes parentes correspondantes sont automatiquement récupérées.
   - Ce processus est **récursif** : si les tables parentes ont elles-mêmes des FK, leurs parents sont aussi remontés, et ainsi de suite, jusqu'à obtenir un dataset auto-suffisant sans violation de contraintes d'intégrité référentielle.

3. **Anonymiser les données sensibles** selon une configuration fournie par l'utilisateur.
   - La configuration précise, table par table et colonne par colonne, quelle stratégie d'anonymisation appliquer.
   - Les données non déclarées comme sensibles sont copiées telles quelles.

4. **Écrire le dataset anonymisé** dans la base de destination.
   - Le schéma cible est créé ou synchronisé automatiquement via `pg_dump --schema-only`.
   - L'écriture respecte l'ordre d'insertion (parents avant enfants).

---

## Propriétés attendues de la solution

| Propriété | Description |
|---|---|
| **Configurable sans recompilation** | Les règles d'anonymisation sont dans un fichier `brume.yml` externe, modifiable sans toucher au code. |
| **Dataset intègre** | Le jeu de données copié satisfait toutes les contraintes FK — pas de lignes orphelines. |
| **Anonymisation cohérente** | Une même valeur réelle produit toujours la même valeur fictive, y compris si elle apparaît dans plusieurs tables. |
| **Anonymisation déterministe** | Deux exécutions avec le même secret et la même valeur source produisent le même résultat fictif. |
| **Non-réversibilité** | Impossible de retrouver la valeur réelle à partir de la valeur anonymisée sans la clé secrète. |
| **Copie partielle possible** | Un sous-ensemble filtré suffit, du moment que le dataset est cohérent (FK remontées automatiquement). |
| **One-shot** | L'outil s'exécute, fait son travail, et s'arrête. Pas de daemon, pas d'agent persistant. |
| **Packagé en Docker** | S'exécute dans n'importe quel environnement CI/CD via une image Docker. |

---

## Utilisateurs cibles

- **Développeurs** qui veulent reproduire un bug de production en local.
- **Équipes QA** qui ont besoin de données réalistes pour leurs tests.
- **Équipes data** qui veulent travailler sur des données représentatives sans exposer les données personnelles.

---

## Ce que le système ne fait PAS

- Pas de synchronisation continue (pas de CDC, pas de streaming).
- Pas de chiffrement de la base de destination au repos.
- Pas de gestion des migrations de schéma (pas Flyway/Liquibase).
- Pas d'auto-détection des colonnes sensibles — l'utilisateur déclare explicitement ce qui doit être anonymisé.

---

## Exemple de cas d'usage

> **Incident prod** : un bug affecte les commandes d'un client depuis 3 jours.
>
> L'équipe configure Brume pour extraire la table `orders` filtrée sur `customer_id = 42`.
>
> Brume remonte automatiquement :
> - Le `customer` (FK `orders.customer_id → customers.id`)
> - Les `address` du client (FK `customers.address_id → addresses.id`)
> - Les `order_items` liés (FK `order_items.order_id → orders.id`)
> - Les `products` (FK `order_items.product_id → products.id`)
>
> Toutes les données personnelles sont anonymisées selon la config. Dataset local complet, réaliste, conforme RGPD.

---

## État d'implémentation

> **✅ Architecture Hybride v2 — entièrement implémentée (2026-04-19)**
>
> Les phases 0 à 6 du plan de migration sont toutes terminées. Le code de référence dans
> `docs/anonymizer-code-complet.md` et `docs/anonymizer-documentation.md` décrit l'architecture
> désormais en production.

### Points d'attention techniques (comportements à connaître)

- **`FpeIdStrategy` retourne un `Long`**, pas un `String` — nécessaire pour que JDBC puisse lier la valeur à une colonne `bigint` sans erreur de type.
- **`FakeStrategy` (type `IBAN`) supprime les espaces** (`replaceAll("\\s+", "")`) — Datafaker peut générer des IBAN avec espaces de formatage dépassant `VARCHAR(34)`.
- **`BatchWriter` utilise `Types.OTHER`** pour toutes les valeurs `String` — requis pour les colonnes `jsonb` (PostgreSQL refuse `character varying` pour ce type).
- **`session_replication_role = 'replica'`** est positionné à l'intérieur de chaque transaction batch, pas au niveau de la connexion parente — les pools de connexions ne garantissent pas la réutilisation de la même connexion.
- **`replication.pgdump-path`** supporte les chemins multi-tokens (splitté sur les espaces) — permet `docker exec brume-source pg_dump` sur les machines sans `pg_dump` en local.
- **Remontée FK sans filtre** : si une table est listée dans `extraction.tables` sans filtre, `FkParentResolver` remonte *tous* ses parents FK, y compris ceux qui ne satisfont pas le filtre d'une autre table. Déclarer un filtre explicite sur chaque table évite ce comportement.

---

## Références techniques

- Architecture implémentée : `AGENTS.md` — diagramme, patterns, liste des fichiers
- Architecture détaillée : `docs/anonymizer-documentation.md`
- Code de référence v2 : `docs/anonymizer-code-complet.md`
- Plan de migration (phases 0-6) : `plan.md`
- Suivi d'exécution : `SUIVI.md`
- **Protocole de test : `TEST_PROTOCOL.md`** — schéma `test_brume`, infrastructure Docker, vérifications post-run, test de déterminisme
