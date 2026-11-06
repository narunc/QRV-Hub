#!/usr/bin/env bash
#
# install-display.sh -- restore the 240x200 display override on a freshly reset
# Spender TC-7G, and reinstall the QRV-Hub Display boot app that makes it persist.
#
#   ./install-display.sh                 # full: size + install + grant + launch + verify
#   ./install-display.sh --size-only     # just apply 240x200 now, no app
#   ./install-display.sh --verify        # report current state and exit
#   ./install-display.sh --reboot-test 8 # reboot N times, report the pass rate
#
# Needs the radio's adb serial (see `adb devices`):  SERIAL=xxxx display/install-display.sh
# Run it from the repository root.

set -uo pipefail

if [[ -z "${SERIAL:-}" ]]; then
  echo "Set SERIAL to the radio's adb serial (see: adb devices), e.g. SERIAL=abcd1234 $0" >&2
  exit 2
fi
PKG="me.narun.apps.qrvhub.display"
W=240
H=200
ADB=(adb -s "$SERIAL")

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m  ! %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  + %s\033[0m\n' "$*"; }

require_device() {
  if ! adb devices | grep -q "^${SERIAL}[[:space:]]*device$"; then
    warn "device ${SERIAL} not in 'device' state. adb devices says:"
    adb devices
    exit 1
  fi
  ok "device ${SERIAL} online"
}

show_state() {
  say "Current state"
  "${ADB[@]}" shell wm size
  "${ADB[@]}" shell wm density
  printf '  display_size_forced = %s\n' \
    "$("${ADB[@]}" shell settings get global display_size_forced | tr -d '\r')"
  printf '  sdk                 = %s\n' \
    "$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
  printf '  ro.config.size_override = %s\n' \
    "$("${ADB[@]}" shell getprop ro.config.size_override | tr -d '\r')"
  if "${ADB[@]}" shell pm list packages 2>/dev/null | grep -q "$PKG"; then
    "${ADB[@]}" shell dumpsys package "$PKG" \
      | grep -iE "versionCode|stopped=|WRITE_SECURE_SETTINGS" | sed 's/^/  /'
  else
    printf '  %s: not installed\n' "$PKG"
  fi
}

apply_size() {
  say "Applying ${W}x${H}"
  "${ADB[@]}" shell wm size "${W}x${H}"
  sleep 1
  "${ADB[@]}" shell sync            # settings provider flushes async
  "${ADB[@]}" shell wm size
}

find_apk() {
  local candidates=(
    "display/build/outputs/apk/debug/display-debug.apk"
  )
  for c in "${candidates[@]}"; do
    [[ -f "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

install_app() {
  local apk
  if ! apk="$(find_apk)"; then
    if [[ -x ./gradlew ]]; then
      say "No APK found -- building debug"
      ./gradlew :display:assembleDebug || { warn "build failed; see output above"; exit 1; }
      apk="$(find_apk)" || { warn "build reported success but no APK found"; exit 1; }
    else
      warn "No QRV-Hub Display APK and no ./gradlew here (run from the repository root)."
      warn "Build it first (Gradle JDK must be 17), or run with --size-only."
      exit 1
    fi
  fi
  ok "using $apk"

  say "Installing"
  # Order matters. Installing AFTER launching re-sets stopped=true, and a
  # stopped package never receives BOOT_COMPLETED.
  "${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || true
  "${ADB[@]}" install "$apk" || { warn "install failed"; exit 1; }

  say "Granting WRITE_SECURE_SETTINGS"
  "${ADB[@]}" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS \
    || warn "grant failed -- the app will not be able to change the display"

  say "Granting DUMP (to check the panel is really scaled)"
  "${ADB[@]}" shell pm grant "$PKG" android.permission.DUMP \
    || warn "DUMP grant failed -- QRV-Hub Display falls back to timed attempts"

  say "Launching once to clear the stopped flag"
  "${ADB[@]}" shell am start -n "${PKG}/.MainActivity" >/dev/null
  sleep 2
}

verify_install() {
  say "Verifying"
  local stopped granted size
  stopped="$("${ADB[@]}" shell dumpsys package "$PKG" | grep -o 'stopped=[a-z]*' | head -1 | tr -d '\r')"
  granted="$("${ADB[@]}" shell dumpsys package "$PKG" | grep -c 'WRITE_SECURE_SETTINGS' | tr -d '\r')"
  size="$("${ADB[@]}" shell wm size | tr -d '\r' | tr '\n' ' ')"

  printf '  %s\n' "$stopped"
  [[ "$stopped" == "stopped=false" ]] \
    && ok "not stopped -- BOOT_COMPLETED will be delivered" \
    || warn "stopped=true -- receiver will NOT fire. Re-run: adb -s $SERIAL shell am start -n $PKG/.MainActivity"

  [[ "$granted" -gt 0 ]] \
    && ok "WRITE_SECURE_SETTINGS present" \
    || warn "permission missing"

  printf '  wm size: %s\n' "$size"
  grep -q "${W}x${H}" <<<"$size" \
    && ok "override active" \
    || warn "override NOT active -- the reflection call may be failing; check: adb -s $SERIAL logcat -d -s QRVHubDisplay:V"

  say "Recent QRV-Hub Display log"
  "${ADB[@]}" shell "logcat -d -s QRVHubDisplay:V" | tail -20
}

reboot_test() {
  local n="${1:-8}" pass=0
  say "Reboot test x${n} (about 2 min each)"
  for i in $(seq 1 "$n"); do
    "${ADB[@]}" reboot
    "${ADB[@]}" wait-for-device
    sleep 90
    local s
    s="$("${ADB[@]}" shell wm size | tr -d '\r' | tr '\n' ' ')"
    if grep -q "${W}x${H}" <<<"$s"; then
      ok   "boot $i: $s"
      pass=$((pass+1))
    else
      warn "boot $i: $s"
      printf '       display_size_forced = %s\n' \
        "$("${ADB[@]}" shell settings get global display_size_forced | tr -d '\r')"
    fi
  done
  say "Result: ${pass}/${n} boots kept the override"
  [[ "$pass" -lt "$n" ]] && \
    warn "Raise FIRST_DELAY_MS to 30000 and ATTEMPTS to 5 in DisplayService.java, rebuild, re-run."
}

# ---------------------------------------------------------------------------

require_device

case "${1:-}" in
  --verify)      show_state ;;
  --size-only)   show_state; apply_size ;;
  --reboot-test) reboot_test "${2:-8}" ;;
  *)             show_state; apply_size; install_app; verify_install
                 say "Next"
                 echo "  ./install-display.sh --reboot-test 8"
                 ;;
esac
