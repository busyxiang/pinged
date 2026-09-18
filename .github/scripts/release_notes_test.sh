#!/usr/bin/env bash
#
# Tests for release_notes.sh, the only step of the release workflow that
# cannot be rehearsed -- the workflow around it decrypts a keystore and
# publishes, so every run is real. Fixtures are built here with `git init` and
# nothing is read from the repository this ships in, so it passes on a shallow
# CI checkout.
#
# Half of this file is mutation tests: take a copy of the script, revert one
# guard with a `sed`, assert the result is wrong in that guard's own way.
# Each needs a fixture shaped to its failure, which is why there are three
# histories here -- three of the four baseline guards do nothing measurable on
# a history without the shape they are for, and a mutation test on the wrong
# fixture passes against a script with the guard deleted.

set -euo pipefail

script=$(cd "$(dirname "$0")" && pwd)/release_notes.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
sha=0f3a1c7d5e2b48a9c6d0f1e2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6

fail() {
    printf 'FAIL %s\n' "$1"
    failures=$((failures + 1))
}

pass() {
    printf 'ok   %s\n' "$1"
}

assert_contains() {
    local file=$1 needle=$2 what=$3
    if [ -f "$file" ] && grep -qF -- "$needle" "$file"; then
        pass "$what"
    else
        fail "$what: expected to find <$needle> in $file"
    fi
}

assert_absent() {
    local file=$1 needle=$2 what=$3
    if [ -f "$file" ] && grep -qF -- "$needle" "$file"; then
        fail "$what: did not expect <$needle> in $file"
    else
        pass "$what"
    fi
}

# A repository, its identity set locally so this passes on a runner with no
# global git config, and signing off so a developer with commit.gpgsign on
# does not get a passphrase prompt out of a test run.
new_repo() {
    local dir=$work/$1
    mkdir -p "$dir"
    git -C "$dir" init --quiet --initial-branch=main
    git -C "$dir" config user.name 'Fixture'
    git -C "$dir" config user.email 'fixture@example.invalid'
    git -C "$dir" config commit.gpgsign false
    git -C "$dir" config tag.gpgsign false
    printf '%s\n' "$dir"
}

commit_in() {
    local dir=$1 subject=$2
    printf '%s\n' "$subject" >> "$dir/log.txt"
    git -C "$dir" add -A
    git -C "$dir" commit --quiet -m "$subject"
}

# Run the script under test, or a mutant of it, capturing the notes and the
# workflow log separately. A non-zero exit is recorded rather than fatal, so
# the failure cases can be asserted on.
notes_for() {
    local runner=$1 dir=$2 tag=$3 out=$4
    rm -f "$out"
    ( cd "$dir" && GITHUB_REPOSITORY=busyxiang/pinged bash "$runner" "$tag" "$sha" "$out" ) \
        > "$out.log" 2>&1 || printf 'exit %s\n' "$?" >> "$out.log"
}

# A copy of the script with one guard reverted, left in $MUTANT. The sed is
# asserted to have matched: a mutation that silently failed to apply is a test
# passing for the wrong reason, and these patterns are lines of shell that get
# reworded. Sets a global rather than printing, because a `$(...)` would put
# `fail` in a subshell and lose the count.
MUTANT=
mutant() {
    local name=$1 expression=$2
    MUTANT=$work/mutant-$name.sh
    sed "$expression" "$script" > "$MUTANT"
    if cmp -s "$script" "$MUTANT"; then
        fail "mutant $name: the sed matched nothing, so this mutation tested the unmodified script"
        return 1
    fi
    return 0
}

# The main fixture: two releases, an SDD checkpoint tag on either side of the
# first one, a merge, and a commit message written to break a generator.

main=$(new_repo main)
commit_in "$main" 'Add approved design spec'
git -C "$main" tag sdd-plan-checkpoint
commit_in "$main" 'Capture notifications into an encrypted ledger, and prove it'
git -C "$main" tag -a v0.1.0 -m 'Pinged 0.1.0 -- the capture milestone'
commit_in "$main" 'Dispatch the database open off the main thread'
git -C "$main" tag sdd-checkpoint

# A merge, so the notes can be checked for listing what a branch carried
# rather than the word "Merge".
git -C "$main" checkout --quiet -b feature
commit_in "$main" 'Give :app a test that starts it, which is what nothing did'
git -C "$main" checkout --quiet main
git -C "$main" merge --quiet --no-ff -m 'Merge pull request #1 from busyxiang/feature' feature

# The hostile commit, used as both a commit and a tag message: a full fence,
# heredoc-terminator lines, command substitution in both spellings, printf
# conversions, raw HTML, and a mention that would notify a real account.
cat > "$work/hostile.txt" <<'HOSTILE_FIXTURE_ENDS_HERE'
Subject and body both hostile: `backticks`, "quotes", 'single', $HOME, ${PATH}

A fenced block inside a commit message:

```
echo this line would close a three-backtick fence
```

Lines that read like a heredoc terminator:
FIXTURE
NOTES_EOF
EOF

Command substitution that must never run: $(touch pwned-by-substitution)
and the older spelling: `touch pwned-by-backticks`

printf conversions that must survive verbatim: %s %d %5.2f %% and 100%

Markdown and HTML structure that must not take effect: # Heading
- list item
<img src=x onerror=alert(1)>
[link](https://example.invalid) | table | @busyxiang #1
HOSTILE_FIXTURE_ENDS_HERE

printf 'hostile\n' >> "$main/log.txt"
git -C "$main" add -A
git -C "$main" commit --quiet -F "$work/hostile.txt"
git -C "$main" tag -a v0.1.1 -F "$work/hostile.txt"

# The first release, which has no previous tag.

first=$work/first.md
notes_for "$script" "$main" v0.1.0 "$first"
assert_absent "$first.log" 'exit ' 'first release: the script succeeds'
assert_contains "$first" 'commits/v0.1.0' \
    'first release: links the commit list, since there is nothing to compare'
assert_absent "$first" 'compare/' 'first release: no compare link with an empty base'
assert_contains "$first" 'Pinged 0.1.0 -- the capture milestone' \
    'first release: quotes the tag message'
assert_contains "$first.log" 'No previous v* tag reachable' \
    'first release: says so in the workflow log'

# A release with a predecessor.

normal=$work/normal.md
notes_for "$script" "$main" v0.1.1 "$normal"
assert_absent "$normal.log" 'exit ' 'normal release: the script succeeds'
assert_contains "$normal" 'compare/v0.1.0...v0.1.1' \
    'normal release: the compare link names the previous tag'
assert_absent "$normal" 'Merge pull request' 'normal release: no commit list to carry a merge subject'
assert_absent "$normal" 'sdd-plan-checkpoint' 'normal release: ignores non-version tags'
assert_absent "$normal" 'sdd-checkpoint' 'normal release: ignores a non-version tag inside the range'

# What release-please already writes is not restated here: no commit count, no
# diffstat, no subject list. The compare link is the one derived thing kept.
assert_absent "$normal" 'commits since' 'slim: no commit count'
assert_absent "$normal" 'files changed' 'slim: no diffstat'
assert_absent "$normal" '### Commits' 'slim: no commit list section'
assert_absent "$normal" 'Give :app a test that starts it' 'slim: no commit subjects'

# The static half: the notes' only user-facing content, asserted rather than
# trusted to survive an edit to the generated half.
assert_contains "$normal" 'Sideload the APK below.' 'static: sideloading'
assert_contains "$normal" 'Allow restricted' 'static: the Android 13 restricted-settings toggle'
assert_contains "$normal" 'does not hand' 'static: no earlier notification can be recovered'
assert_contains "$normal" "sha256  $sha" 'static: the APK digest'

# The hostile text, rendered.

[ -e "$main/pwned-by-substitution" ] &&
    fail 'injection: a commit message ran a command through substitution'
[ -e "$main/pwned-by-backticks" ] &&
    fail 'injection: a commit message ran a command through backticks'
pass 'injection: no command in a commit message ran'

assert_contains "$normal" '$(touch pwned-by-substitution)' \
    'injection: substitution syntax survives as text'
assert_contains "$normal" '%s %d %5.2f %% and 100%' \
    'injection: printf conversions survive as text'
assert_contains "$normal" 'NOTES_EOF' 'injection: a heredoc-shaped line survives'
assert_contains "$normal" '<img src=x onerror=alert(1)>' 'injection: raw HTML survives as text'
assert_contains "$normal" '@busyxiang #1' 'injection: a mention survives as text'
# Four, because the tag message contains a three-backtick fence. What matters
# is that the fence outgrows any run inside it; mutant `fixed-fence` proves it.
assert_contains "$normal" '````' 'injection: the fence outgrows the backticks in the text'

# A second tag on the tip commit: a lightweight one, which has no message to
# quote, and which must not become the baseline of the release below it.

git -C "$main" tag v0.1.2

light=$work/light.md
notes_for "$script" "$main" v0.1.2 "$light"
assert_absent "$light.log" 'exit ' 'lightweight tag: the script still succeeds'
assert_contains "$light.log" '::warning::' 'lightweight tag: warns in the workflow log'
assert_absent "$light" 'What the tag says' 'lightweight tag: no quoted message section'
assert_absent "$light" 'Subject and body both hostile' \
    'lightweight tag: does not quote the commit message the tag dereferences to'
assert_contains "$light.log" 'no CHANGELOG.md entry and no tag message' \
    'lightweight tag: the warning names both sources, not just the tag'

newer=$work/newer.md
notes_for "$script" "$main" v0.1.1 "$newer"
assert_contains "$newer" 'compare/v0.1.0...v0.1.1' \
    'a newer tag exists: the baseline is still the tag below this one'
assert_absent "$newer" 'v0.1.2' 'a newer tag exists: it is not used as the baseline'

# An unknown tag fails loudly, because the alternative is a release whose
# notes are empty.

missing=$work/missing.md
notes_for "$script" "$main" v9.9.9 "$missing"
assert_contains "$missing.log" 'exit 1' 'unknown tag: fails'
assert_contains "$missing.log" '::error::' 'unknown tag: annotates the failure'
assert_contains "$missing.log" 'fetch-depth' 'unknown tag: names the likely cause'

# Mutation: the `v[0-9]*` filter. It matters on the first release, where
# there is no earlier version tag to outrank a checkpoint tag.

if mutant glob "s/'v\[0-9\]\*'/'*'/"; then
    out=$work/mutant-glob.md
    notes_for "$MUTANT" "$main" v0.1.0 "$out"
    assert_contains "$out" 'compare/sdd-plan-checkpoint' \
        'mutant glob: without the filter an SDD checkpoint becomes the first release baseline'
fi

# Mutation: the walk that requires the baseline to be below this tag.

if mutant newer-tag 's/^    \[ "\$seen_self" -eq 1 \] || continue$/    :/'; then
    out=$work/mutant-newer.md
    notes_for "$MUTANT" "$main" v0.1.1 "$out"
    assert_contains "$out" 'compare/v0.1.2...v0.1.1' \
        'mutant newer-tag: taking the first candidate picks the tag above this one'
fi

# Mutation: version sort. Needs a history where lexical and version order
# disagree, which the main fixture does not have.

sorted=$(new_repo sorted)
commit_in "$sorted" 'Nine'
git -C "$sorted" tag -a v0.9.0 -m 'Pinged 0.9.0'
commit_in "$sorted" 'Ten'
git -C "$sorted" tag -a v0.10.0 -m 'Pinged 0.10.0'
commit_in "$sorted" 'One point oh'
git -C "$sorted" tag -a v1.0.0 -m 'Pinged 1.0.0'

sortcase=$work/sorted.md
notes_for "$script" "$sorted" v1.0.0 "$sortcase"
assert_contains "$sortcase" 'compare/v0.10.0...v1.0.0' 'version sort: v0.10.0 is newer than v0.9.0'

# v1.0.0 rather than v0.11.0 as the tag being released, because the walk that
# requires a lower baseline masks a lexical sort otherwise: descending by
# refname, v0.10.0 happens to follow v0.11.0 anyway. It does not follow
# v1.0.0, which sorts above both.
if mutant sort 's/--sort=-v:refname/--sort=-refname/'; then
    out=$work/mutant-sort.md
    notes_for "$MUTANT" "$sorted" v1.0.0 "$out"
    assert_contains "$out" 'compare/v0.9.0...v1.0.0' \
        'mutant sort: a lexical sort puts v0.9.0 immediately below v1.0.0'
fi

# Mutation: --merged. Needs the backport shape -- a patch tag released off an
# older line and never merged back, sitting in version order between the tag
# being released and its real predecessor.

branched=$(new_repo branched)
commit_in "$branched" 'One'
git -C "$branched" tag -a v0.1.0 -m 'Pinged 0.1.0'
commit_in "$branched" 'Two, on the main line'
git -C "$branched" tag -a v0.2.0 -m 'Pinged 0.2.0'
git -C "$branched" checkout --quiet -b patch v0.1.0
commit_in "$branched" 'A patch released off the v0.1.0 line and never merged back'
git -C "$branched" tag -a v0.1.1 -m 'Pinged 0.1.1'
git -C "$branched" checkout --quiet main

branchcase=$work/branched.md
notes_for "$script" "$branched" v0.2.0 "$branchcase"
assert_contains "$branchcase" 'compare/v0.1.0...v0.2.0' \
    'unmerged patch tag: the baseline is the last ancestor, not the last version'

if mutant merged 's/--merged "\$commit" //'; then
    out=$work/mutant-merged.md
    notes_for "$MUTANT" "$branched" v0.2.0 "$out"
    assert_contains "$out" 'compare/v0.1.1...v0.2.0' \
        'mutant merged: without --merged a tag off another branch becomes the baseline'
fi

# Mutation: the two rules that keep commit text from escaping its container.

if mutant fixed-fence 's/^    length=\$((length + 1))$/    length=3/'; then
    out=$work/mutant-fence.md
    notes_for "$MUTANT" "$main" v0.1.1 "$out"
    assert_absent "$out" '````' \
        'mutant fixed-fence: a three-backtick fence is closed by the fence in the tag message'
fi

if mutant printf-format 's|^    printf .%s\\n. "\$text"$|    printf "$text\\n"|'; then
    out=$work/mutant-printf.md
    notes_for "$MUTANT" "$main" v0.1.1 "$out"
    assert_absent "$out" '%5.2f' \
        'mutant printf-format: printf "$text" eats the conversions in the message'
fi

# CHANGELOG.md, which is where the curated notes live once release-please is
# writing them. The tag annotation becomes the fallback for a tag cut without
# an entry, which is what every release before release-please was.

changelog=$(new_repo changelog)
commit_in "$changelog" 'First'
git -C "$changelog" tag -a v0.1.0 -m 'Pinged 0.1.0'

cat > "$changelog/CHANGELOG.md" <<'CHANGELOG_FIXTURE_ENDS_HERE'
# Changelog

## [0.3.0](https://github.com/busyxiang/pinged/compare/v0.2.0...v0.3.0) (2026-09-18)

### Features

* the release-please heading form is matched too

## 0.2.0 (2026-09-14)

The curated paragraph for this version, which is what the release publishes.

* a bullet that must survive as markdown

## 0.1.0 (2026-09-06)

An older entry that must not appear in a newer version's notes.
CHANGELOG_FIXTURE_ENDS_HERE

git -C "$changelog" add -A
git -C "$changelog" commit --quiet -m 'Write the changelog'
git -C "$changelog" tag -a v0.2.0 -m 'A tag annotation that must be ignored'
commit_in "$changelog" 'After'
# Lightweight, which is what release-please cuts: the summary comes from the
# changelog, so there is no tag message and none is needed.
git -C "$changelog" tag v0.3.0
commit_in "$changelog" 'A version the changelog never got an entry for'
git -C "$changelog" tag -a v0.4.0 -m 'The annotation is all this release has'

curated=$work/curated.md
notes_for "$script" "$changelog" v0.2.0 "$curated"
assert_absent "$curated.log" 'exit ' 'changelog: the script succeeds'
assert_contains "$curated" 'The curated paragraph for this version' \
    'changelog: publishes the entry for this version'
assert_contains "$curated" '* a bullet that must survive as markdown' \
    'changelog: emitted as markdown rather than fenced'
assert_absent "$curated" 'A tag annotation that must be ignored' \
    'changelog: the entry wins over the tag annotation'
assert_absent "$curated" 'An older entry' 'changelog: stops at the next heading'
assert_absent "$curated" 'the release-please heading form' \
    'changelog: does not reach the entry above this one'

fallback=$work/fallback.md
notes_for "$script" "$changelog" v0.4.0 "$fallback"
assert_contains "$fallback" 'The annotation is all this release has' \
    'changelog: a version with no entry falls back to the tag annotation'

rp=$work/rp.md
notes_for "$script" "$changelog" v0.3.0 "$rp"
assert_contains "$rp" 'the release-please heading form is matched too' \
    'changelog: matches the linked heading release-please writes'
assert_absent "$rp" 'The curated paragraph' \
    'changelog: and stops before the entry below it'

# The whole point of the warning change. release-please cuts lightweight tags
# by design, so warning on the tag object alone fired on every good release and
# advised `git tag -a`, which would not have helped.
assert_absent "$rp.log" '::warning::' \
    'warning: silent for a lightweight tag that has a changelog entry'
assert_absent "$curated.log" '::warning::' \
    'warning: silent for an annotated tag that has a changelog entry'
assert_absent "$fallback.log" '::warning::' \
    'warning: silent for a tag whose message is the summary'

# Mutation: the bracket strip, which is the only thing that reads
# release-please's own heading form rather than the hand-written one.

if mutant changelog-bracket 's|sub(/^\\\[/, "", heading)|heading = heading|'; then
    out=$work/mutant-changelog-bracket.md
    notes_for "$MUTANT" "$changelog" v0.3.0 "$out"
    assert_absent "$out" 'the release-please heading form is matched too' \
        'mutant changelog-bracket: release-please headings stop matching, so every release it wrote loses its summary'
    assert_contains "$out.log" 'no CHANGELOG.md entry and no tag message' \
        'mutant changelog-bracket: ...and the warning is what says so'
fi

# Mutation: the heading comparison, which is what bounds an entry.

if mutant changelog-bounds 's/inside = (heading == want)/inside = 1/'; then
    out=$work/mutant-changelog-bounds.md
    notes_for "$MUTANT" "$changelog" v0.2.0 "$out"
    assert_contains "$out" 'An older entry' \
        'mutant changelog-bounds: without the comparison the notes carry the whole changelog'
fi

# Mutation: the warning condition. Reverted to the tag object alone, it fires
# on every release-please release.

if mutant warn-on-tag-object 's/^if \[ -z "\$entry" \] && \[ -z "\$annotation" \]; then$/if [ "$tag_object" != "tag" ]; then/'; then
    out=$work/mutant-warn.md
    notes_for "$MUTANT" "$changelog" v0.3.0 "$out"
    assert_contains "$out.log" '::warning::' \
        'mutant warn-on-tag-object: a lightweight tag with a changelog entry warns again'
fi

# The one guard that is not in the script: a depth-1 clone has no earlier tag
# to find, so it reports every release as the first, successfully. Hence
# release.yml's fetch-depth: 0.

shallow=$work/shallow
git clone --quiet --depth 1 --branch v1.0.0 "file://$sorted" "$shallow" 2>/dev/null
shallow_notes=$work/shallow.md
notes_for "$script" "$shallow" v1.0.0 "$shallow_notes"
assert_contains "$shallow_notes" 'commits/v1.0.0' \
    'shallow checkout: a depth-1 clone silently produces first-release notes'
assert_absent "$shallow_notes" 'v0.10.0' \
    'shallow checkout: and loses the range the release actually covers'

printf '\n'
if [ "$failures" -ne 0 ]; then
    printf '%s assertions failed\n' "$failures"
    exit 1
fi
printf 'All assertions passed\n'
