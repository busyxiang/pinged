#!/usr/bin/env bash
#
# Build the body of a GitHub release from the tag being released.
#
#     release_notes.sh <tag> <apk-sha256> <output-file>
#
# Notes go to the output file, nothing else does. Stdout is therefore free for
# workflow commands, which GitHub reads off stdout only -- a script that wrote
# its notes there could not raise a `::warning::`.
#
# Text out of git is prose nobody wrote with a generator in mind: it contains
# backticks, quotes, `$` and sometimes a fenced block. Two rules, both
# load-bearing, keep that from producing broken or forged notes:
#
#   * Never interpolated into anything a shell or printf parses. Every write
#     is `printf '%s\n' "$var"`, and there is no heredoc here, so a message
#     line that reads like a terminator has nothing to terminate.
#   * Only ever emitted inside a fence one backtick longer than its longest
#     backtick run (see `fence`), so it cannot close its own container. That
#     also kills `#123`, `@mention` and raw HTML. Escaping was the
#     alternative and is worse: these messages are hand-wrapped at 78
#     columns, and markdown would reflow them.

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

# Longest run of backticks on any line, so `fence` can outgrow it. Per
# character because shell patterns cannot count a run.
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
    # tr, because the shell has no repeat operator.
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

# The curated entry for this version out of CHANGELOG.md, or empty.
#
# Matches `## 0.3.0 (date)` and release-please's own `## [0.3.0](url) (date)`,
# and stops at the next `## `. The version is the tag without its `v`, so a
# heading that does not exist yet -- a tag pushed before the release PR was
# merged -- yields empty and the tag annotation below is used instead.
#
# Emitted as markdown rather than fenced, unlike everything taken out of git.
# The distinction is trust, not formatting: a commit message arrives from
# whoever wrote it, while this file is reviewed in the release PR by whoever
# could edit this script anyway. Fencing it would throw away the headings and
# bullets that make notes readable for the one audience they have.
changelog_entry() {
    local version=$1 root file
    root=$(git rev-parse --show-toplevel 2>/dev/null) || return 0
    file=$root/CHANGELOG.md
    [ -f "$file" ] || return 0
    awk -v want="$version" '
        /^## / {
            heading = $0
            sub(/^## +/, "", heading)
            sub(/^\[/, "", heading)
            sub(/[]( ].*$/, "", heading)
            inside = (heading == want)
            next
        }
        inside { print }
    ' "$file" | sed -e '/./,$!d' | awk '
        { lines[NR] = $0 }
        END {
            last = NR
            while (last > 0 && lines[last] ~ /^[[:space:]]*$/) last--
            for (i = 1; i <= last; i++) print lines[i]
        }
    '
}

# The previous release, the base of everything below. Four guards, each with
# a mutation test in release_notes_test.sh that restores what it replaced and
# watches the baseline come out wrong:
#
#   * `v[0-9]*`, not `*`: the two `capture-milestone-history` tags and any SDD
#     checkpoint would otherwise be candidates.
#   * `--merged`: only an ancestor can be the predecessor. A patch tagged off
#     an older line sorts between v0.2.0 and v0.1.0 without being on v0.2.0's
#     history, and diffing against it reports that line's work as deletions.
#   * `-v:refname`, not creation date and not a lexical sort, which would put
#     v0.10.0 below v0.9.0.
#   * The candidate must be *below* this tag in that order -- the `seen_self`
#     walk. Taking the first candidate that is not this tag picks a higher
#     reachable one when re-running for an older tag, and the notes then
#     describe the range backwards.
#
# Empty is a legitimate answer, and the rest of this script branches on it.
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

# %(contents) would paste a GPG signature block into the notes; subject and
# body separately leave it out. Untested here, because no tag in this
# repository is signed.
tag_object=$(git for-each-ref --format='%(objecttype)' "refs/tags/$tag")
annotation=
if [ "$tag_object" = "tag" ]; then
    annotation=$(git for-each-ref \
        --format='%(contents:subject)%0a%0a%(contents:body)' "refs/tags/$tag")
fi

entry=$(changelog_entry "${tag#v}")

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

    # The changelog entry is the whole of "what changed" now. It is written
    # for the person installing the APK and reviewed in the release pull
    # request, and everything this script used to derive from git -- a commit
    # count, a diffstat, the subject lines -- restated that worse and in more
    # words. The compare link is kept because it is the one thing the entry
    # cannot carry: a way through to the full messages.
    #
    # The tag annotation is the fallback for a tag cut without an entry, which
    # is what every release before release-please was.
    if [ -n "$entry" ]; then
        printf '%s\n' "$entry"
    elif [ -n "$annotation" ]; then
        printf '### What the tag says\n\n'
        fenced "$annotation"
    fi

    if [ -n "$compare" ]; then
        printf '\nEvery commit message in full: %s\n' "$compare"
    fi

    # Static, because none of it can be derived from git.
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
# Only when the notes have no summary at all. release-please cuts lightweight
# tags by design and the summary comes from CHANGELOG.md, so warning on the tag
# object alone fired on every good release and told the reader to do something
# that would not help -- which is how an annotation gets ignored.
if [ -z "$entry" ] && [ -z "$annotation" ]; then
    printf '::warning::%s has no CHANGELOG.md entry and no tag message, so these notes say nothing about what changed. Add the entry in the release pull request, or cut the tag with git tag -a; see docs/release.md.\n' "$tag"
fi
printf 'Wrote %s bytes of notes for %s (previous %s, summary from %s).\n' \
    "$(wc -c < "$out" | tr -d '[:space:]')" "$tag" "${previous:-none}" \
    "$([ -n "$entry" ] && echo CHANGELOG.md || { [ -n "$annotation" ] && echo "the tag" || echo nothing; })"
