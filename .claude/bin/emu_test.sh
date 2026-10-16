#!/usr/bin/env bash
#
# Tests for emu, against a fake adb that logs its arguments and keeps the
# `wm` override in a file, so this runs without a device and on CI.
#
# Each guard has a mutation test: take a copy of the script, revert the
# guard with a `sed`, assert the result fails in that guard's own way. A
# mutation that changes nothing is itself a failure, so a reworded line
# cannot leave its test passing against nothing.

set -euo pipefail

script=$(cd "$(dirname "$0")" && pwd)/emu
work=$(mktemp -d)
trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$work"' EXIT

failures=0

fail() {
    printf 'FAIL %s\n' "$1"
    failures=$((failures + 1))
}

pass() {
    printf 'ok   %s\n' "$1"
}

mkdir -p "$work/bin"
cat >"$work/bin/adb" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$ADB_LOG"
# The first call starts a server that outlives it, with the caller's open
# descriptors, as the real adb does.
if [ ! -e "$ADB_STATE/server" ]; then
    touch "$ADB_STATE/server"
    sleep 30 &
fi
up=$ADB_STATE/online-$2
case "$*" in
    *get-state) [ -e "$up" ] && echo device || exit 1 ;;
    # The device sweep: orphans lists pids to report; kill records them.
    *"ps -A -o PID=,PPID=,USER=,NAME="*) cat "$ADB_STATE/orphans" 2>/dev/null ;;
    *"shell kill -9 "*) all="$*"; printf '%s\n' "${all##*kill -9 }" >>"$ADB_STATE/killed"; rm -f "$ADB_STATE/orphans" ;;
    *"getprop sys.boot_completed") [ -e "$up" ] && echo 1 || exit 1 ;;
    *"emu kill") rm -f "$up" ;;
    *"wm size reset") rm -f "$ADB_STATE/size" ;;
    *"wm size "*) touch "$ADB_STATE/size" ;;
    *"wm size") echo "Physical size: 1080x2400"
        if [ -f "$ADB_STATE/size" ]; then echo "Override size: 320x640"; fi ;;
    *"wm density reset") rm -f "$ADB_STATE/density" ;;
    *"wm density "*) touch "$ADB_STATE/density" ;;
    *"wm density") echo "Physical density: 420"
        if [ -f "$ADB_STATE/density" ]; then echo "Override density: 160"; fi ;;
esac
EOF
# Comes up half a second after launch, as `-port N`'s emulator-N, unless
# EMULATOR_FAILS. Logs its arguments and whether it was handed the lock.
cat >"$work/bin/emulator" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$ADB_STATE/emulator.log"
if [ -e /proc/$$/fd/9 ]; then echo "inherited fd 9" >>"$ADB_STATE/emulator.log"; fi
[ -z "${EMULATOR_FAILS:-}" ] || exit 1
while [ $# -gt 0 ]; do [ "$1" = -port ] && port=$2; shift; done
sleep 0.5
touch "$ADB_STATE/online-emulator-$port"
EOF
chmod +x "$work/bin/emulator"
export EMU_EMULATOR=$work/bin/emulator
chmod +x "$work/bin/adb"
export PATH=$work/bin:$PATH

# A fresh lock directory, device state and log for each case.
case_n=0
fresh() {
    case_n=$((case_n + 1))
    export EMU_LOCK_DIR=$work/$case_n/locks
    export ADB_STATE=$work/$case_n/state
    export ADB_LOG=$work/$case_n/adb.log
    unset EMU_POOL EMU_IDLE EMU_BOOT_WAIT EMULATOR_FAILS EMU_MAX_HOLD EMU_KILL_GRACE
    mkdir -p "$ADB_STATE"
    touch "$ADB_STATE/online-emulator-5554" "$ADB_STATE/online-emulator-5556"
    : >"$ADB_LOG"
    log=$work/$case_n/order.log
    : >"$log"
}

# A copy of emu with one guard reverted; fails the run if the sed missed.
mutant() {
    local name=$1 expr=$2
    local copy=$work/emu-$name
    sed "$expr" "$script" >"$copy"
    chmod +x "$copy"
    if cmp -s "$script" "$copy"; then
        fail "mutation $name changed nothing; the guard's line was reworded"
    fi
    printf '%s' "$copy"
}

until_in() {
    local file=$1 needle=$2 i
    for i in $(seq 100); do
        grep -qF -- "$needle" "$file" 2>/dev/null && return 0
        sleep 0.05
    done
    return 1
}

# A holds the emulator for 2s; B asks during it. B must start after A ends.
serialises() {
    local emu=$1
    "$emu" -- sh -c "echo A-start >>$log; sleep 2; echo A-end >>$log" &
    until_in "$log" A-start
    "$emu" -- sh -c "echo B-start >>$log" 2>/dev/null
    wait
    [ "$(tr '\n' ' ' <"$log")" = "A-start A-end B-start " ]
}

# A leaves a process running that outlives it; B must not wait for that.
releases_on_return() {
    local emu=$1
    "$emu" -- sh -c "sleep 5 & echo A-done >>$log"
    "$emu" --wait 1 -- sh -c "echo B-start >>$log" 2>/dev/null
}

resets_after_failure() {
    local emu=$1
    "$emu" --ci-size -- false && return 1
    [ ! -e "$ADB_STATE/size" ] && [ ! -e "$ADB_STATE/density" ]
}

# The command must die with the lease: left running, it would use the
# emulator while the next holder does.
stops_on_term() {
    local emu=$1 pid child
    "$emu" --ci-size -- sh -c "echo \$\$ >>$log; exec sleep 30" &
    pid=$!
    until [ -s "$log" ]; do sleep 0.05; done
    child=$(cat "$log")
    [ -e "$ADB_STATE/size" ] || return 1
    kill -TERM "$pid"
    wait "$pid" || true
    sleep 0.2
    if kill -0 "$child" 2>/dev/null; then kill "$child"; return 1; fi
    [ ! -e "$ADB_STATE/size" ] && [ ! -e "$ADB_STATE/density" ]
}

fresh
if serialises "$script"; then pass "a second lease waits for the first"; else fail "a second lease waits for the first: $(cat "$log")"; fi

fresh
if serialises "$(mutant no-flock 's/if flock -n 9; then/if true; then/')"; then
    fail "mutation no-flock: leases still serialised"
else
    pass "mutation no-flock: leases overlap"
fi

fresh
if releases_on_return "$script"; then pass "a process the command leaves behind does not hold the lease"; else fail "a process the command leaves behind does not hold the lease"; fi

fresh
if releases_on_return "$(mutant fd-leak 's/"\$@" <&3 9>&- &/"$@" <\&3 \&/')"; then
    fail "mutation fd-leak: the lease was still released"
else
    pass "mutation fd-leak: the leftover process holds the lease"
fi

fresh
if resets_after_failure "$script"; then pass "--ci-size resets after a failing command"; else fail "--ci-size resets after a failing command"; fi

fresh
if resets_after_failure "$(mutant no-reset '/if \[ "\$ci_size" = 1 \]; then reset_size; fi/d')"; then
    fail "mutation no-reset: the size was still reset"
else
    pass "mutation no-reset: the size stays overridden"
fi

fresh
if stops_on_term "$script"; then pass "TERM stops the command and resets the size"; else fail "TERM stops the command and resets the size"; fi

fresh
if stops_on_term "$(mutant term-orphans '/^trap .*exit 143/d')"; then
    fail "mutation term-orphans: the command still stopped"
else
    pass "mutation term-orphans: the command outlives the lease"
fi

fresh
"$script" -- false && status=0 || status=$?
if [ "$status" = 1 ]; then pass "the command's exit status is emu's"; else fail "the command's exit status is emu's: got $status"; fi

fresh
out=$("$script" -- sh -c 'printf %s "$ANDROID_SERIAL"')
if [ "$out" = emulator-5554 ]; then pass "ANDROID_SERIAL names the leased emulator"; else fail "ANDROID_SERIAL names the leased emulator: got <$out>"; fi

fresh
out=$(printf 'from-stdin' | "$script" -- cat)
if [ "$out" = from-stdin ]; then pass "the command reads emu's stdin"; else fail "the command reads emu's stdin: got <$out>"; fi

# A dead holder's override, found by the next lease, which did not ask for one.
fresh
touch "$ADB_STATE/size" "$ADB_STATE/density"
"$script" -- true 2>/dev/null
if [ ! -e "$ADB_STATE/size" ] && [ ! -e "$ADB_STATE/density" ]; then pass "a dead holder's override is reset"; else fail "a dead holder's override is reset"; fi

fresh
touch "$ADB_STATE/size" "$ADB_STATE/density"
"$(mutant no-stale-reset '/^    reset_size$/d')" -- true 2>/dev/null
if [ -e "$ADB_STATE/size" ]; then pass "mutation no-stale-reset: the override survives"; else fail "mutation no-stale-reset: the override was still reset"; fi

fresh
rm "$ADB_STATE/online-emulator-5554"
"$script" -- sh -c "echo ran >>$log" 2>/dev/null
if grep -q -- '-avd Pixel_10a -port 5554 ' "$ADB_STATE/emulator.log" && [ -s "$log" ]; then
    pass "a lease boots an emulator that is not running"
else
    fail "a lease boots an emulator that is not running"
fi
if ! grep -q 'inherited fd 9' "$ADB_STATE/emulator.log"; then pass "the emulator it boots does not hold the lease"; else fail "the emulator it boots does not hold the lease"; fi

fresh
rm "$ADB_STATE/online-emulator-5554"
"$(mutant emulator-fd-leak 's/2>&1 9>&- &$/2>\&1 \&/')" -- true 2>/dev/null
if grep -q 'inherited fd 9' "$ADB_STATE/emulator.log"; then pass "mutation emulator-fd-leak: the emulator holds the lease"; else fail "mutation emulator-fd-leak: the emulator does not hold the lease"; fi

fresh
rm "$ADB_STATE/online-emulator-5554"
export EMULATOR_FAILS=1 EMU_BOOT_WAIT=2
"$script" -- sh -c "echo ran >>$log" 2>/dev/null && status=0 || status=$?
if [ "$status" = 69 ] && [ ! -s "$log" ]; then pass "an emulator that never boots refuses the command"; else fail "an emulator that never boots refuses the command: status $status"; fi

# Idle reaping: only what emu booted, only once idle, never under a lease.
fresh
rm "$ADB_STATE/online-emulator-5554"
export EMU_IDLE=1
"$script" -- true 2>/dev/null
sleep 2.5
if [ ! -e "$ADB_STATE/online-emulator-5554" ]; then pass "an emulator a lease booted stops when idle"; else fail "an emulator a lease booted stops when idle"; fi

fresh
export EMU_IDLE=1
"$script" -- true
sleep 2.5
if [ -e "$ADB_STATE/online-emulator-5554" ]; then pass "an emulator started by hand is never stopped"; else fail "an emulator started by hand is never stopped"; fi

no_reap_under_lease() {
    local emu=$1
    rm "$ADB_STATE/online-emulator-5554"
    EMU_IDLE=1 "$emu" -- true 2>/dev/null
    EMU_IDLE=0 "$emu" -- sh -c "sleep 2.5; [ -e $ADB_STATE/online-emulator-5554 ] && echo up >>$log"
    [ -s "$log" ]
}
fresh
if no_reap_under_lease "$script"; then pass "an idle stop waits for the lease another session took"; else fail "an idle stop waits for the lease another session took"; fi
fresh
if no_reap_under_lease "$(mutant reap-unleased 's/    flock -n 9 || exit 0/    true/')"; then
    fail "mutation reap-unleased: the emulator survived anyway"
else
    pass "mutation reap-unleased: the emulator is stopped mid-lease"
fi

# Two leases in a row: the first's reaper must not count from the first.
fresh
rm "$ADB_STATE/online-emulator-5554"
export EMU_IDLE=2
"$script" -- true 2>/dev/null
sleep 1.2
"$script" -- true
sleep 1.3
if [ -e "$ADB_STATE/online-emulator-5554" ]; then pass "the idle clock restarts at each lease"; else fail "the idle clock restarts at each lease"; fi
sleep 1.5
if [ ! -e "$ADB_STATE/online-emulator-5554" ]; then pass "and stops idle after the last"; else fail "and stops idle after the last"; fi

fresh
"$script" --stop
if [ ! -e "$ADB_STATE/online-emulator-5554" ]; then pass "--stop stops the emulator"; else fail "--stop stops the emulator"; fi

fresh
"$script" -- sh -c "echo A-start >>$log; sleep 3" &
until_in "$log" A-start
err=$("$script" --wait 1 -- true 2>&1) && status=0 || status=$?
wait
if [ "$status" = 75 ] && grep -qF 'sleep 3' <<<"$err" && grep -qF "$(basename "$(git -C "$(dirname "$script")" rev-parse --show-toplevel)")" <<<"$err"; then
    pass "a lease that times out names the holder and its command"
else
    fail "a lease that times out names the holder and its command: status $status, <$err>"
fi

# Long enough to queue behind a full suite (~10 min) instead of giving up.
fresh
"$script" -- sh -c "echo A-start >>$log; sleep 3" &
until_in "$log" A-start
"$script" -- true 2>"$work/$case_n/b.err" &
until_in "$work/$case_n/b.err" "waiting up to"
wait
if grep -qF 'waiting up to 1800s' "$work/$case_n/b.err"; then pass "a lease waits 30 minutes by default"; else fail "a lease waits 30 minutes by default: $(cat "$work/$case_n/b.err")"; fi

# The queue. A fake waiter is a ticket naming a live process: queued
# earlier, it must hold back a newcomer even with the emulator free.
started() { sed 's/.*) //' "/proc/$1/stat" | cut -d' ' -f20; }
older_ticket() {
    local pid=$1 start=$2
    mkdir -p "$EMU_LOCK_DIR/queue"
    printf '%s\tfake-worktree\tfake command\n' "$(date +%s)" \
        >"$EMU_LOCK_DIR/queue/$(($(date +%s%N) - 1000000000))-$pid-$start"
}
newcomer_waits() {
    local emu=$1 status
    "$emu" --wait 2 -- sh -c "echo ran >>$log" 2>/dev/null && status=0 || status=$?
    [ "$status" = 75 ] && [ ! -s "$log" ]
}

fresh
sleep 30 & waiter=$!
older_ticket "$waiter" "$(started "$waiter")"
if newcomer_waits "$script"; then pass "a newcomer waits behind an older live waiter"; else fail "a newcomer waits behind an older live waiter"; fi
if [ "$(ls "$EMU_LOCK_DIR/queue" | wc -l)" = 1 ]; then pass "a waiter that gives up leaves the queue"; else fail "a waiter that gives up leaves the queue: $(ls "$EMU_LOCK_DIR/queue")"; fi
out=$("$script" --status)
if grep -q 'queued 1: fake-worktree' <<<"$out"; then pass "--status lists the queue"; else fail "--status lists the queue: <$out>"; fi
kill "$waiter"; wait "$waiter" 2>/dev/null || true

fresh
sleep 30 & waiter=$!
older_ticket "$waiter" "$(started "$waiter")"
if newcomer_waits "$(mutant no-queue 's/    if \[ "$(ahead)" -lt "$slots" \]; then/    if true; then/')"; then
    fail "mutation no-queue: the newcomer still waited"
else
    pass "mutation no-queue: the newcomer jumps the queue"
fi
kill "$waiter"; wait "$waiter" 2>/dev/null || true

dead_ticket_passes() {
    local emu=$1
    sh -c 'exit 0' & local gone=$!
    wait "$gone"
    older_ticket "$gone" 12345
    sleep 30 & reused=$!
    older_ticket "$reused" 1
    "$emu" --wait 2 -- sh -c "echo ran >>$log" 2>/dev/null
    local status=$?
    kill "$reused"; wait "$reused" 2>/dev/null || true
    [ "$status" = 0 ] && [ -s "$log" ] && [ -z "$(ls "$EMU_LOCK_DIR/queue")" ]
}
fresh
if dead_ticket_passes "$script"; then pass "a dead waiter's ticket, or a reused pid's, holds up nobody"; else fail "a dead waiter's ticket, or a reused pid's, holds up nobody"; fi
fresh
if dead_ticket_passes "$(mutant no-liveness 's/        if \[ "$(started "$pid")" = "$start" \]; then/        if true; then/')"; then
    fail "mutation no-liveness: the dead ticket still let it through"
else
    pass "mutation no-liveness: a dead ticket blocks the queue"
fi

# Real waiters, queued a beat apart, are served in that order.
fresh
"$script" -- sh -c "echo A >>$log; sleep 2" &
until_in "$log" A
for w in B C D; do
    "$script" -- sh -c "echo $w >>$log" 2>/dev/null &
    sleep 0.3
done
wait
if [ "$(tr '\n' ' ' <"$log")" = "A B C D " ]; then pass "waiters are served first come, first served"; else fail "waiters are served first come, first served: $(tr '\n' ' ' <"$log")"; fi

# The adb server the first lease starts must not keep its lock.
server_releases() {
    local emu=$1
    "$emu" -- true
    "$emu" --wait 2 -- sh -c "echo B-ran >>$log" 2>/dev/null && [ -s "$log" ]
}
fresh
if server_releases "$script"; then pass "an adb server a lease starts does not hold the lease"; else fail "an adb server a lease starts does not hold the lease"; fi
fresh
if server_releases "$(mutant adb-fd-leak 's/    command adb "$@" 9>&-/    command adb "$@"/')"; then
    fail "mutation adb-fd-leak: the lease was still released"
else
    pass "mutation adb-fd-leak: the adb server holds the lease"
fi

# Stall guards. A hung command is stopped at --max-hold, its whole process
# group goes with it, and what it left on the device is swept.
times_out() {
    local emu=$1 status
    EMU_MAX_HOLD=1 timeout 8 "$emu" -- sh -c "echo \$\$ >$log; exec sleep 30" 2>"$work/$case_n/err" && status=0 || status=$?
    sleep 0.3
    local held=$(cat "$log")
    if kill -0 "$held" 2>/dev/null; then kill "$held"; return 1; fi
    [ "$status" = 124 ] && grep -q -- '--max-hold limit' "$work/$case_n/err"
}
fresh
if times_out "$script"; then pass "a command that outlasts --max-hold is stopped (124)"; else fail "a command that outlasts --max-hold is stopped (124): $(cat "$work/$case_n/err")"; fi
fresh
if times_out "$(mutant no-watchdog '/(sleep "$max_hold_s" \&\& kill -USR1 \$\$)/d')"; then
    fail "mutation no-watchdog: the command was still stopped"
else
    pass "mutation no-watchdog: the command holds on past the limit"
fi

stops_grandchildren() {
    local emu=$1 pid gc
    "$emu" -- sh -c "sleep 30 & echo \$! >$log; wait" 2>/dev/null &
    pid=$!
    until [ -s "$log" ]; do sleep 0.05; done
    gc=$(cat "$log")
    kill -TERM "$pid"; wait "$pid" 2>/dev/null || true
    sleep 0.3
    if kill -0 "$gc" 2>/dev/null; then kill "$gc"; return 1; fi
}
fresh
if stops_grandchildren "$script"; then pass "a stop reaches the command's children"; else fail "a stop reaches the command's children"; fi
fresh
if stops_grandchildren "$(mutant no-setsid 's/^setsid "\$@" <&3 9>&- &$/"$@" <\&3 9>\&- \&/')"; then
    fail "mutation no-setsid: the child was still stopped"
else
    pass "mutation no-setsid: the command's child outlives the stop"
fi

kills_term_ignorer() {
    local emu=$1 pid gone
    EMU_KILL_GRACE=1 "$emu" -- sh -c "trap '' TERM; echo \$\$ >$log; while :; do sleep 0.1; done" 2>/dev/null &
    pid=$!
    until [ -s "$log" ]; do sleep 0.05; done
    gone=$(cat "$log")
    kill -TERM "$pid"; wait "$pid" 2>/dev/null || true
    sleep 0.3
    if kill -0 "$gone" 2>/dev/null; then kill -KILL "$gone"; return 1; fi
}
fresh
if kills_term_ignorer "$script"; then pass "a command that ignores TERM is killed after the grace"; else fail "a command that ignores TERM is killed after the grace"; fi
fresh
if kills_term_ignorer "$(mutant no-kill '/^    kill -KILL -- -"\$child" 2>\/dev\/null || true$/d')"; then
    fail "mutation no-kill: the TERM-ignoring command still died"
else
    pass "mutation no-kill: the TERM-ignoring command survives"
fi

# Orphans on the device before the lease (a dead holder's) and after it.
fresh
printf '4101\n4102\n' >"$ADB_STATE/orphans"
"$script" -- sh -c "[ -e $ADB_STATE/orphans ] && echo still-there >>$log; true" 2>"$work/$case_n/err"
if [ ! -s "$log" ] && grep -qx '4101 4102' "$ADB_STATE/killed" && grep -q 'killed 2 process' "$work/$case_n/err"; then
    pass "a lease sweeps what an earlier one left on the device, before its command"
else
    fail "a lease sweeps what an earlier one left on the device, before its command: $(cat "$log" "$ADB_STATE/killed" 2>/dev/null)"
fi
fresh
"$script" -- sh -c "printf '5201\n' >$ADB_STATE/orphans" 2>/dev/null
if grep -qx '5201' "$ADB_STATE/killed" 2>/dev/null; then pass "a lease sweeps what its own command left on the device"; else fail "a lease sweeps what its own command left on the device"; fi
fresh
"$(mutant no-end-sweep 's/^    if \[ -n "\${child:-}" \]; then sweep_device; fi$/    true/')" -- sh -c "printf '5201\n' >$ADB_STATE/orphans" 2>/dev/null
if grep -qx '5201' "$ADB_STATE/killed" 2>/dev/null; then fail "mutation no-end-sweep: the orphan was still swept"; else pass "mutation no-end-sweep: the command's orphan stays on the device"; fi
fresh
printf '4101\n' >"$ADB_STATE/orphans"
"$(mutant no-start-sweep '/^sweep_device$/d')" -- sh -c "[ -e $ADB_STATE/orphans ] && echo still-there >>$log; true" 2>/dev/null
if [ -s "$log" ]; then pass "mutation no-start-sweep: the command runs beside the earlier orphan"; else fail "mutation no-start-sweep: the orphan was gone before the command"; fi

fresh
export EMU_POOL="emulator-5554 emulator-5556"
"$script" -- sh -c "echo \"A-start \$ANDROID_SERIAL\" >>$log; sleep 2; echo A-end >>$log" &
until_in "$log" A-start
"$script" --wait 1 -- sh -c "echo \"B-start \$ANDROID_SERIAL\" >>$log"
wait
if [ "$(tr '\n' ' ' <"$log")" = "A-start emulator-5554 B-start emulator-5556 A-end " ]; then
    pass "a pool of two serves two leases at once"
else
    fail "a pool of two serves two leases at once: $(tr '\n' ' ' <"$log")"
fi

fresh
"$script" -- sh -c "echo A-start >>$log; sleep 2" &
until_in "$log" A-start
out=$("$script" --status)
wait
if grep -q 'emulator-5554: held by' <<<"$out"; then pass "--status shows the holder"; else fail "--status shows the holder: <$out>"; fi
out=$("$script" --status)
if grep -q 'emulator-5554: free' <<<"$out"; then pass "--status shows a released emulator free"; else fail "--status shows a released emulator free: <$out>"; fi

# Every scale CI's `disable-animations` zeroes, set before the command starts.
animations_off_first() {
    local emu=$1 scale
    "$emu" -- sh -c "echo COMMAND >>$ADB_LOG"
    for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
        sed '/^COMMAND$/q' "$ADB_LOG" | grep -qF -- "shell settings put global $scale 0" || return 1
    done
}

fresh
if animations_off_first "$script"; then pass "animations are off before the command"; else fail "animations are off before the command: $(cat "$ADB_LOG")"; fi

fresh
if animations_off_first "$(mutant animations-on 's/settings put global "\$scale" 0/settings put global "\$scale" 1/')"; then
    fail "mutation animations-on: animations were still turned off"
else
    pass "mutation animations-on: the command runs with animations on"
fi

if [ "$failures" -gt 0 ]; then
    printf '%s failed\n' "$failures"
    exit 1
fi
