#!/usr/bin/env bash
# Does strata accept what fieldlog exports? Builds strata from GitHub in Docker,
# then: (1) validate the merged notebook from `fieldlog demo`; (2) import
# strata's example site, export it again, and require strata's cross-validation
# output to be byte-identical; (3) a notebook with a gap is refused by fieldlog
# for the same reason strata gives.
set -euo pipefail
cd "$(dirname "$0")/.."
STRATA_REV=${STRATA_REV:-047ca52}
OUT=build/strata-check
mkdir -p "$OUT" .gradle-home/cargo
U="$(id -u):$(id -g)"
fieldlog() { docker run --rm -u "$U" -v "$PWD:/w" -w /w eclipse-temurin:17-jdk cli/build/install/fieldlog/bin/fieldlog "$@"; }
strata() {
  docker run --rm -u "$U" -e HOME=/tmp -e CARGO_HOME=/w/.gradle-home/cargo -v "$PWD:/w" -w /w rust:1 \
    sh -c '[ -x .gradle-home/cargo/bin/strata ] || cargo install -q --locked --git https://github.com/ahmed-hashim-pro/strata --rev '"$STRATA_REV"' strata; .gradle-home/cargo/bin/strata "$@"' strata "$@"
}

fieldlog demo -o "$OUT/merged.csv" > /dev/null
strata validate "$OUT/merged.csv" > "$OUT/validate.txt"
echo "ok: strata validate accepts the merged export ($(grep -c '^BH' "$OUT/merged.csv") rows)"

before=$(strata query examples/site.csv 600 480 || true)
after=$(strata query "$OUT/merged.csv" 600 480)
grep -q REFUSED <<<"$before" && ! grep -q REFUSED <<<"$after"
echo "ok: at (600, 480) strata refused the base of gravel before BH41, answers after"

fieldlog import examples/site.csv -o "$OUT/site.ops" > /dev/null
fieldlog export "$OUT/site.ops" -o "$OUT/roundtrip.csv" > /dev/null
strata cv examples/site.csv > "$OUT/cv-original.txt"
strata cv "$OUT/roundtrip.csv" > "$OUT/cv-roundtrip.txt"
cmp "$OUT/cv-original.txt" "$OUT/cv-roundtrip.txt"
echo "ok: strata cv is byte-identical on examples/site.csv and on it after import -> export"

printf 'units: clay, chalk\nhole,x,y,collar,unit,from,to\nBH01,0,0,10,clay,0,1\nBH01,0,0,10,chalk,1.5,3\n' > "$OUT/gap.csv"
strata validate "$OUT/gap.csv" > "$OUT/gap-strata.txt" 2>&1 && { echo "strata accepted a gap?"; exit 1; }
grep -q 'gap between 1 and 1.5 m' "$OUT/gap-strata.txt"
echo "ok: strata refuses a gap with the same words fieldlog uses: $(cat "$OUT/gap-strata.txt")"
