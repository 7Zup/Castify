#!/usr/bin/env fish
# Publishes a Castify release on GitHub: both APKs, one version (docs/releasing.md).
#
#   scripts/release.fish 0.5.1
#
# 1. checks: on an up-to-date, clean main; version newer than gradle.properties (or equal
#    when that version was never released);
# 2. bumps `castify.version` through a small PR, merged at once (main is never committed to
#    directly);
# 3. runs the unit tests, builds both APKs (signed with this Mac's debug key);
# 4. creates the tag + GitHub release with castify-phone.apk and castify-tv.apk.

function fail
    echo "release: $argv" >&2
    exit 1
end

set ver $argv[1]
string match -qr '^\d{1,3}\.\d{1,2}\.\d{1,2}$' -- "$ver"; or fail "usage: scripts/release.fish <major.minor.patch>, e.g. 0.5.1"
set tag v$ver

cd (dirname (status filename))/..; or fail "can't find the repo root"

test (git branch --show-current) = main; or fail "switch to main first"
test (count (git status --porcelain)) -eq 0; or fail "the working tree is not clean"
git fetch -q origin; or fail "git fetch failed"
test (git rev-parse HEAD) = (git rev-parse origin/main); or fail "main is not up to date with origin/main (git pull)"
gh release view $tag >/dev/null 2>&1; and fail "$tag is already published"

set current (string replace -r '^castify\.version=' '' (string match -r '^castify\.version=.*' < gradle.properties))
test -n "$current"; or fail "castify.version missing from gradle.properties"

# Numeric comparison: 0.10.0 > 0.9.9.
function code
    set p (string split . $argv[1])
    math "$p[1] * 10000 + $p[2] * 100 + $p[3]"
end

if test (code $ver) -lt (code $current)
    fail "$ver is older than the current $current"
else if test (code $ver) -gt (code $current)
    echo "release: bumping $current → $ver"
    set branch release/$tag
    git checkout -q -b $branch; or fail "can't create $branch"
    string replace -r '^castify\.version=.*' "castify.version=$ver" < gradle.properties > gradle.properties.tmp
    and mv gradle.properties.tmp gradle.properties
    or fail "can't edit gradle.properties"
    git add gradle.properties
    and git commit -q -m "Release $tag"
    and git push -q -u origin $branch
    or fail "can't push $branch"
    gh pr create --base main --head $branch --title "Release $tag" --body "Bumps \`castify.version\` to $ver for the $tag release (scripts/release.fish)."
    and gh pr merge $branch --merge --delete-branch
    or fail "can't merge the version bump PR"
    git checkout -q main; and git pull -q --ff-only; or fail "can't update main"
end

echo "release: tests + build"
./gradlew -q :core:domain:test :core:protocol:test :phone:app:assembleDebug :tv:app:assembleDebug; or fail "tests or build failed"

set out build/release
mkdir -p $out
cp phone/app/build/outputs/apk/debug/app-debug.apk $out/castify-phone.apk; or fail "phone APK missing"
cp tv/app/build/outputs/apk/debug/app-debug.apk $out/castify-tv.apk; or fail "TV APK missing"

echo "release: publishing $tag"
gh release create $tag $out/castify-phone.apk $out/castify-tv.apk \
    --target (git rev-parse HEAD) --title "Castify $tag" --generate-notes --latest
or fail "gh release create failed"
echo "release: done, both apps will offer $tag at their next launch"
