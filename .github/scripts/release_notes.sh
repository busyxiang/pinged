#!/usr/bin/env bash
#
# Build the body of a GitHub release from the tag being released.
#
#     release_notes.sh <tag> <apk-sha256> <output-file>
#
# The notes go to the output file and nothing else does, so stdout stays free
# for the workflow log. That split is the reason the diagnostics below are
# `::warning::` lines rather than writes to stderr: GitHub reads workflow
# commands off a step's stdout only, so a script that wrote its notes there
# could not raise one.
#
# Runnable outside Actions against any tag in any clone, which is the only way
# this file gets tested -- the workflow that calls it runs a few times a year
# and signs an APK when it does. GITHUB_REPOSITORY and GITHUB_SERVER_URL are
# read if set, and only decide whether the compare link is emitted.
#
# Everything taken out of git -- the tag message, commit subjects -- is
# untrusted text in the sense that matters here: it is prose nobody wrote with
# a generator in mind, and it reliably contains backticks, quotes, `$`, `--`
# and occasionally a fenced block. Two rules keep that from turning into
# broken or forged notes, and both are load-bearing:
#
#   * It is never interpolated into anything a shell or printf parses. Every
#     write is `printf '%s\n' "$var"` -- never `printf "$var"`, which would
#     make a `%s` in a commit message a format specifier -- and there is no
#     heredoc anywhere here, so a commit body containing a line that reads
#     like a terminator has nothing to terminate.
#
#   * It is only ever emitted inside a fenced block whose fence is one
#     backtick longer than the longest backtick run in the text (see `fence`),
#     so no part of it can close its own container. Inside a fence there is no
#     markdown, no HTML, no `#123` autolink and no `@mention`, which is the
#     difference between rendering a commit message and letting one address
#     the release page. Escaping instead was the alternative and is worse:
#     these messages are hand-wrapped at 78 columns, and markdown would reflow
#     them into a wall while `\`` left visible backslashes behind.

set -euo pipefail

die() {
    printf '::error::%s\n' "$1"
    exit 1
}

tag=${1:-}
sha256=${2:-}
out=${3:-}

if [ -z "$tag" ] || [ -z "$sha256" ] || [ -z "$out" ]; then
    die "usage: release_notes.sh <tag> <apk-sha256> <output-file>"
fi

commit=$(git rev-parse --verify --quiet "refs/tags/$tag^{commit}") ||
    die "No tag $tag in this clone. If this ran in Actions, checkout fetched the tag without its history; release.yml sets fetch-depth: 0 for exactly that."

# The longest run of backticks on any line, so the caller can pick a fence
# that the text cannot close. Written per character rather than with a regex
# because the shell's own patterns cannot count a run.
longest_backtick_run() {
    awk '
        {
            run = 0
            for (i = 1; i <= length($0); i++) {
                if (substr($0, i, 1) == "`") {
                    run++
                    if (run > longest) longest = run
                } else {
                    run = 0
                }
            }
        }
        END { print longest + 0 }
    '
}

fence() {
    local length
    length=$(printf '%s\n' "$1" | longest_backtick_run)
    length=$((length + 1))
    [ "$length" -lt 3 ] && length=3
    # tr, because the shell has no repeat operator and a loop that appends a
    # backtick inside double quotes reads like a quoting bug.
    printf '%*s' "$length" '' | tr ' ' '`'
}

# Emit untrusted text as a fenced block. CR is stripped so a message written
# on Windows does not render with a visible ^M on every line.
fenced() {
    local text marker
    text=$(printf '%s\n' "$1" | tr -d '\r')
    marker=$(fence "$text")
    printf '%s\n' "$marker"
    printf '%s\n' "$text"
    printf '%s\n' "$marker"
}

# The previous release, which is the base of everything below. Four things
# this has to get right, and each one has a mutation test in
# release_notes_test.sh that puts the guard back to what it replaced and
# watches the baseline come out wrong:
#
#   * `v[0-9]*` and not `*`. There are two `capture-milestone-history` tags
#     here, kept as pointers to a squashed branch's real commits, and an SDD
#     checkpoint tag would otherwise be a candidate. It bites hardest on a
#     first release, where there is no v-tag below to outrank it.
#   * `--merged`, so only an ancestor of what is being released can be the
#     previous release. The shape that needs it is a patch released off an
#     older line: v0.1.1 tagged on a branch from v0.1.0 and never merged
#     back, which then sits between v0.2.0 and v0.1.0 in version order
#     without being on v0.2.0's history at all. Diffing against it reports
#     the older line's work as deletions.
#   * Version sort, not creation date and not a plain refname sort. A tag can
#     be created late for an old commit, and `-v:refname` orders v0.10.0
#     after v0.9.0 where a lexical sort does not.
#   * The candidate has to be *below* this tag in that order, which is what
#     the `seen_self` walk is for and not a flourish. Taking the first
#     candidate that is not this tag picks a higher one whenever a higher one
#     exists and is reachable -- re-running the workflow for an older tag, or
#     a release cut after a later tag was already pushed -- and then the notes
#     describe the range backwards.
#
# Empty is a legitimate answer -- the first release has no predecessor, and so
# does a `v*` tag whose name is not a version at all -- and the whole rest of
# this script has a branch for it rather than a guard.
previous=
seen_self=0
while IFS= read -r candidate; do
    if [ "$candidate" = "$tag" ]; then
        seen_self=1
        continue
    fi
    [ "$seen_self" -eq 1 ] || continue
    previous=$candidate
    break
done < <(git tag --list 'v[0-9]*' --merged "$commit" --sort=-v:refname)

if [ -n "$previous" ]; then
    log_range="$previous..$commit"
    diff_base=$previous
else
    log_range=$commit
    # The empty tree, so the first release's diffstat is the size of the
    # tree rather than blank. No -w: this hashes without writing an object.
    diff_base=$(git hash-object -t tree /dev/null)
fi

subjects=$(git log --no-merges --format='%h  %s' "$log_range" | tr -d '\r')
count=$(git log --no-merges --format='%h' "$log_range" | wc -l | tr -d '[:space:]')
# ` 12 files changed, 30 insertions(+), 4 deletions(-)` with a leading space,
# and empty when nothing changed at all.
shortstat=$(git diff --shortstat "$diff_base" "$commit" | sed -e 's/^ *//' -e 's/ *$//')
[ -n "$shortstat" ] || shortstat="no file changes"

# %(contents) would paste a GPG signature block into the notes; subject and
# body separately leave it out. Untested here, because no tag in this
# repository is signed.
tag_object=$(git for-each-ref --format='%(objecttype)' "refs/tags/$tag")
annotation=
if [ "$tag_object" = "tag" ]; then
    annotation=$(git for-each-ref \
        --format='%(contents:subject)%0a%0a%(contents:body)' "refs/tags/$tag")
fi

compare=
repo=${GITHUB_REPOSITORY:-}
server=${GITHUB_SERVER_URL:-https://github.com}
if [ -n "$repo" ]; then
    if [ -n "$previous" ]; then
        compare="$server/$repo/compare/$previous...$tag"
    else
        compare="$server/$repo/commits/$tag"
    fi
fi

{
    printf '## What changed\n\n'

    if [ -n "$previous" ]; then
        printf '%s commits since %s: %s.\n' "$count" "$previous" "$shortstat"
    else
        printf 'The first release, so there is no previous tag to compare against.\n'
        printf '%s commits, %s measured against an empty tree.\n' "$count" "$shortstat"
    fi

    if [ -n "$annotation" ]; then
        printf '\n### What the tag says\n\n'
        fenced "$annotation"
    fi

    printf '\n### Commits\n\n'
    # The honest part. A merged branch lands here as one squashed commit --
    # the whole capture milestone is `7918da9` -- so the length of this list
    # says nothing about the size of the release, and a reader who counts it
    # will get the wrong answer in the direction that flatters us. The
    # diffstat above is measured across the range and cannot be squashed, so
    # it is stated first and this list is framed against it.
    printf 'A merged branch lands here as a single squashed commit, so the length of this\n'
    printf 'list is not the size of the release -- the diffstat above is. Commit messages\n'
    printf 'in this repository are long and explain why; only their subject lines are\n'
    printf 'reproduced here.\n\n'

    if [ -n "$subjects" ]; then
        fenced "$subjects"
    else
        # Reachable when a tag is moved or a second tag is put on a commit
        # that already has one. Worded as containment rather than "points at
        # the same commit", which would also be false for a range holding
        # nothing but merge commits -- a shape this repository cannot produce
        # and which therefore has no test here.
        printf 'None. %s already contains every non-merge commit up to this tag.\n' "$previous"
    fi

    if [ -n "$compare" ]; then
        printf '\nEvery commit message in full: %s\n' "$compare"
    fi

    # Static, and it stays static because it is not about this release. All
    # three paragraphs are about the install and none of them can be derived
    # from git.
    printf '\n## Installing\n\n'
    printf 'Sideload the APK below.\n\n'
    printf 'Pinged needs notification access. On Android 13 and later that sits\n'
    printf 'behind restricted settings for apps installed outside an app store:\n'
    printf 'open App info, then the overflow menu, then **Allow restricted\n'
    printf 'settings**, and the toggle becomes available.\n\n'
    printf 'Capture starts the moment you grant access. Android does not hand\n'
    printf 'over notifications from before then, so nothing earlier can be\n'
    printf 'recovered.\n\n'
    printf '    sha256  %s\n' "$sha256"
} > "$out"

if [ -z "$previous" ]; then
    printf 'No previous v* tag reachable from %s. Writing first-release notes.\n' "$tag"
fi
if [ "$tag_object" != "tag" ]; then
    printf '::warning::%s is a lightweight tag, so it carries no message and the release notes have no summary in them. Cut releases with git tag -a; see docs/release.md.\n' "$tag"
fi
printf 'Wrote %s bytes of notes for %s (%s commits since %s).\n' \
    "$(wc -c < "$out" | tr -d '[:space:]')" "$tag" "$count" "${previous:-no previous tag}"
