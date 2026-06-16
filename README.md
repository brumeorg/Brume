<p align="center">
  <img src="assets/logo.png" alt="Brume" width="200"/>
</p>

**Brume** est un outil de **pseudonymisation de bases de données PostgreSQL** conforme au RGPD. Il copie un sous-ensemble ciblé d'une base source vers une base cible ou vers un fichier.sql en transformant les données personnelles à la volée, tout en préservant l'intégrité référentielle (clés étrangères).

> **Contexte réglementaire** — Le RGPD (Règlement (UE) 2016/679) encourage explicitement la pseudonymisation comme mesure technique de réduction du risque (Art. 25, Art. 89). L'Article 4.5 définit la pseudonymisation comme le traitement de données personnelles de façon à ce qu'elles ne puissent plus être attribuées à une personne précise sans recours à des informations supplémentaires conservées séparément. Brume implémente cette définition : le `hmac-secret` et la `fpe-key` constituent ces « informations supplémentaires ».

> ⚠️ **Position juridique** — Brume produit une **pseudonymisation au sens de l'Art. 4.5**, **pas une anonymisation** au sens du considérant 26. Le dataset cible reste **donnée personnelle** au sens RGPD. Consulter votre DPO avant tout traitement en production.

---

## Fonctionnalités clés

- **Pseudonymisation forte déterministe** — même entrée + même secret → même sortie. Deux runs produisent des résultats identiques, garantissant la cohérence des environnements de test.
- **Préservation des clés étrangères** — les IDs sont chiffrés de manière cohérente (`FPE_ID`, `FPE_UUID`) : les contraintes FK restent valides sur la base cible, sans avoir à désactiver les vérifications d'intégrité.
- **Remontée automatique des parents et enfants FK** — si vous extrayez `orders`, Brume remonte automatiquement les `users` référencés, jusqu'à `fk_depth` niveaux.
- **Configuration déclarative** — un fichier `brume.yml` définit par table et par colonne la stratégie à appliquer (`FAKE`, `HASH`, `MASK`, `NULLIFY`, `FPE_ID`, `FPE_UUID`, `KEEP`).
- **Cohérence cross-tables** — `linked_columns` garantit que la même valeur réelle produit la même valeur fictive dans plusieurs tables sans lien FK entre elles.
- **Colonnes JSONB** — les chemins `$.field.subfield` sont pseudonymisés individuellement.
- **Audit k-anonymity** — sous-commande `brume audit --anonymity` pour mesurer le risque résiduel de ré-identification et produire un rapport DPO.
- **PostgreSQL 14–18** supporté (source et cible).

---

## Prérequis

- **PostgreSQL 14, 15, 16, 17 ou 18** sur la base source et la base cible
- ⚠️ **Sécurité** de préférence utiliser un compte en lecture seule.

---

## Installation sur Debian/Ubuntu


```bash
# 1. Configurer le repo (une seule fois)
curl -1sLf 'https://dl.cloudsmith.io/public/brume/brume/setup.deb.sh' | sudo -E bash

# 2. Installer
sudo apt-get install brume

# Mises à jour ensuite
sudo apt-get update && sudo apt-get upgrade brume

# 3. Configurer Brume (selon votre schéma)
cp brume.example.yml brume.yml
```

## Installation sur MacOS


```bash
# 1. Configurer le repo (une seule fois)
brew tap brumecorp/brume

# 2. Installer
brew install brume

# Mises à jour ensuite
brew upgrade brume

# 3. Configurer Brume (selon votre schéma)
cp brume.example.yml brume.yml
```

## Installation sur Fedora/CentOS


```bash
# 1. Configurer le repo (une seule fois)
curl -1sLf 'https://dl.cloudsmith.io/public/brume/brume/setup.rpm.sh' | sudo -E bash

# 2. Installer
sudo dnf install brume

# 3. Configurer Brume (selon votre schéma)
cp brume.example.yml brume.yml
```

---

## Stratégies de pseudonymisation

| Stratégie | Description |
|---|---|
| `FAKE` | Valeur fictive réaliste et déterministe (Datafaker, HMAC-seeded). Requiert `type`. |
| `FPE_ID` | Chiffrement préservant le format (FF1/FPE). Integer in → integer out. Idéal pour les PKs/FKs numériques. |
| `FPE_UUID` | Pseudonymisation déterministe UUID→UUID. Idéal pour les PKs/FKs UUID. |
| `MASK` | Masquage partiel selon le type : conserve le préfixe, remplace le reste par `*`. |
| `HASH` | HMAC one-way (non réversible). 64 chars avec HmacSHA256. |
| `NULLIFY` | Remplace par `NULL`. La colonne doit être nullable. |
| `KEEP` | Copie sans modification (comportement par défaut pour les colonnes non déclarées). |

Types sémantiques disponibles pour `FAKE` et `MASK` : `EMAIL`, `FIRST_NAME`, `LAST_NAME`, `PHONE`, `IBAN`, `ADDRESS`, `IP_ADDRESS`, `JSONB`.

---

## Configuration

### `.env` — connexions et secrets

```bash
cp .env.example .env

#Puis éditer le .env comme dans l'exemple
BRUME_HMAC_SECRET=mon-secret
BRUME_FPE_KEY=ma-cle-fpe-16ch
REPLICATION_SOURCE_PASSWORD=postgres
...
```

### `brume.yml` — règles de pseudonymisation

```yaml
extraction:
  fk_depth: 3          # Niveaux de profondeur FK automatique
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
          column: user_email   # Même vraie valeur → même fausse valeur

  tables:
    - table: users
      columns:
        - name: id
          strategy: FPE_ID    # Propagé automatiquement aux FKs orders.user_id
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

La `strategy` définit **comment une valeur réelle est transformée** avant d'être écrite sur la base cible. Elle se déclare au niveau de chaque colonne dans `brume.yml`.

| Valeur | Description |
|---|---|
| `FAKE` | Remplace par une valeur fictive réaliste et déterministe générée via Datafaker, seedée par HMAC. Requiert un `type`. |
| `MASK` | Masquage partiel qui préserve la structure du champ (ex : conserve les 3 premiers chiffres d'un numéro de téléphone). Requiert un `type`. |
| `HASH` | HMAC one-way SHA-256 keyé avec le secret. Non réversible. Déterministe : une même valeur source produit toujours le même hash — les jointures restent valides. |
| `NULLIFY` | Force `NULL`. La colonne doit être nullable. Réduit la surface PII à zéro. |
| `KEEP` | Copie la valeur sans modification. À utiliser pour les colonnes non sensibles ou déjà anonymisées. Comportement par défaut pour les colonnes non déclarées. |
| `FPE_ID` | Chiffrement préservant le format (FF1/BouncyCastle) pour les identifiants numériques. Entier en entrée → entier de même longueur en sortie. Idéal pour les PKs/FKs numériques — propagé automatiquement aux colonnes FK pointant vers cette PK. |
| `FPE_UUID` | Pseudonymisation UUID→UUID via HMAC-SHA256. Préserve le format `8-4-4-4-12` et l'unicité. Propagé automatiquement aux FK UUID. |

### Type

Le `type` précise la **sémantique du champ** pour les stratégies `FAKE` et `MASK`, afin que Brume génère des données fictives contextuellement cohérentes.

| Valeur | Description |
|---|---|
| `EMAIL` | Adresse email — ex : `alice@example.com` |
| `FIRST_NAME` | Prénom — ex : `Alice` |
| `LAST_NAME` | Nom de famille — ex : `Dupont` |
| `PHONE` | Numéro de téléphone — ex : `+33 6 12 34 56 78` |
| `ADDRESS` | Adresse postale — ex : `12 rue de la Paix, Paris` |
| `IBAN` | Numéro de compte bancaire IBAN — ex : `FR76 3000 6000 0112 3456 7890 189` |
| `IP_ADDRESS` | Adresse IPv4 — ex : `192.168.1.42` |
| `JSONB` | Colonne JSON : délègue à `JsonPathProcessor` pour anonymiser les champs imbriqués individuellement via `json_paths`. |

---

## CLI

```bash
brume plan        # Estime les volumes et détecte les colonnes PII non couvertes (read-only)
brume execute     # Exécute la pseudonymisation complète
brume dry-run     # Exécute sans écrire (NullSink — pour valider la config)
brume --help
```

Flags disponibles sur toutes les sous-commandes :

| Flag | Effet |
|---|---|
| `-v` / `--verbose` | Logs DEBUG |
| `-q` / `--quiet` | Logs ERROR uniquement (rapport final toujours visible) |
| `--json` | Sortie machine-readable sur stdout, logs JSON sur stderr |

---

## Audit k-anonymity

Brume mesure le risque résiduel de ré-identification via la k-anonymity (Sweeney 2002) :

```bash
brume audit --anonymity \
  --quasi-id "users:birth_date,zip_code,gender" \
  --report-format markdown
```

Le rapport liste les **classes singletons** (lignes uniques = ré-identifiables) et émet des recommandations actionnables. Conçu pour être remis au DPO.

**Limites V1 :** seule la k-anonymity est mesurée (pas la l-diversity ni la t-closeness) ; audit intra-table uniquement.

---

## Bonnes pratiques RGPD

- **Ne pas qualifier le dataset cible de « données anonymes »** — il reste donnée personnelle au sens RGPD.
- **Protéger `hmac-secret` et `fpe-key`** au même niveau que les données source. Leur fuite invalide la pseudonymisation.
- **Limiter les destinataires** du dataset pseudonymisé au strict nécessaire (tests, qualification, démo).
- **Consulter votre DPO** avant tout traitement en production ; le rapport `brume audit --anonymity` est un outil d'aide à la décision, pas une certification.

---

## Licence

Voir [LICENSE](LICENSE).
