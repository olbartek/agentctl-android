#!/bin/sh
# Regenerates the Swift reference's transcripts that IosParityTest compares the Kotlin port against, byte for byte:
#
#   ctl/src/test/resources/ios/text/<scenario>.txt   shopctl run "$(cat scenarios/<scenario>.appctl)", then exit=<code>
#   ctl/src/test/resources/ios/screens.txt           shopctl screens
#
# It builds the reference's shopctl from an agentctl-ios checkout (by default the sibling of this repository,
# ../agentctl-ios; or pass its path) through that example's own wrapper, then runs every scenario file of this
# example — copies of the reference's, byte for byte — through it, with APPCTL_ROOT at the reference's example.
#
#   examples/agentshop/bench/ios_transcripts.sh [path/to/agentctl-ios]
#
# macOS with Swift 6.1 or newer; nothing here needs a simulator.

set -eu

HERE="$(cd "$(dirname "$0")/.." && pwd)"                # examples/agentshop
REPO="$(cd "$HERE/../.." && pwd)"
IOS="${1:-$REPO/../agentctl-ios}"
IOS="$(cd "$IOS" && pwd)"
IOS_SHOP="$IOS/Examples/AgentShop"
OUT="$HERE/ctl/src/test/resources/ios"

[ -x "$IOS_SHOP/appctl" ] || { echo "no Examples/AgentShop/appctl in $IOS" >&2; exit 2; }

# The wrapper builds shopctl incrementally before running it; `--help` is the cheapest thing to run.
"$IOS_SHOP/appctl" --help > /dev/null
SHOPCTL="$IOS_SHOP/.build/debug/shopctl"

# The reference's scenarios are what this example's are copied from: say so if they drifted apart.
if ! diff -rq "$IOS_SHOP/scenarios" "$HERE/scenarios" > /dev/null; then
  echo "warning: examples/agentshop/scenarios differs from $IOS_SHOP/scenarios; copy them over first" >&2
fi

rm -rf "$OUT/text"
mkdir -p "$OUT/text"
count=0
for file in "$HERE"/scenarios/*.appctl; do
  name="$(basename "$file" .appctl)"
  set +e
  APPCTL_ROOT="$IOS_SHOP" "$SHOPCTL" run "$(cat "$file")" > "$OUT/text/$name.txt" 2>&1
  status=$?
  set -e
  echo "exit=$status" >> "$OUT/text/$name.txt"
  count=$((count + 1))
done
APPCTL_ROOT="$IOS_SHOP" "$SHOPCTL" screens > "$OUT/screens.txt"

echo "Wrote $count transcripts and screens.txt to ${OUT#"$REPO"/}, from $(git -C "$IOS" rev-parse --short HEAD)."
