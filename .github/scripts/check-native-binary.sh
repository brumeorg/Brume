#!/bin/sh
# Garde-fous du binaire natif (ADR-0064), lancés par la CI GitLab (`native-build`, sur MR)
# et par la CI GitHub (`build-and-package.yml`, sur tag). Le script vit sous .github/ :
# c'est le seul dossier de scripts que `mirror-to-github` recopie sur le dépôt public.
#
#   1. Linux : aucun symbole glibc au-delà de GLIBC_2.28 (RHEL 8). La 2.2, compilée sur
#      ubuntu-latest, exigeait GLIBC_2.34 : elle s'installait sur RHEL 8 / Debian 11 /
#      Ubuntu 20.04 mais ne démarrait pas (#48a).
#   2. `brume --version` annonce la version attendue (la 2.2 annonçait « brume 2.1 »).
#
# Usage : check-native-binary.sh <binaire> <version attendue>
set -eu

bin=$1
expected=$2
max_glibc=GLIBC_2.28

if [ "$(uname -s)" = Linux ]; then
    symbols=$(objdump -T "$bin")
    versions=$(echo "$symbols" | grep -o 'GLIBC_[0-9.]*' | sort -uV)
    echo "glibc requise : $(echo "$versions" | tail -1) (plafond $max_glibc)"
    above=""
    for v in $versions; do
        [ "$(printf '%s\n' "$v" "$max_glibc" | sort -V | tail -1)" = "$max_glibc" ] || above="$above $v"
    done
    if [ -n "$above" ]; then
        echo "ERREUR : $bin exige$above, au-delà de $max_glibc — il ne démarrera pas sur RHEL 8. Symboles en cause :" >&2
        # -w : GLIBC_2.3 ne doit pas attraper GLIBC_2.34 (binutils 2.30 n'entoure pas la version de parenthèses)
        for v in $above; do echo "$symbols" | grep -wF "$v"; done >&2
        exit 1
    fi
fi

version=$("$bin" --version)
echo "$version"
if [ "$version" != "brume $expected" ]; then
    echo "ERREUR : $bin annonce « $version », attendu « brume $expected »" >&2
    exit 1
fi
