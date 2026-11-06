# Spender TC-7G: hardware controls used by QRV-Hub

The TC-7G firmware (Android 5.1.1) connects hardware and apps through broadcast
intents named `unipro.*` (the firmware's internal prefix; the names must be
used exactly as shown). They are undocumented; this is what QRV-Hub relies on,
as observed on the radio. Only the controls QRV-Hub uses are listed.

## Keys (firmware → apps)

The window manager (log tag `UniproKey`) sends `unipro.hotkey.<key>.down` and
`.up` for each press:

| Broadcast | Key | Screen off? | QRV-Hub uses it for |
|---|---|---|---|
| `unipro.hotkey.ptt.*` | PTT (and the headset PTT button) | yes | Talking |
| `unipro.hotkey.power.*` | Power button (key 140, scan code 116) | yes | Double press: mute / restore |
| `unipro.hotkey.volumeup.*`, `.volumedown.*` | Volume knob (F11 / F12) | yes | QRV-Hub's own volume |

The two side buttons are not broadcast while the screen is off, but their key
events (260 upper, 267 lower) always reach the focused window.

Notes:

* The power button is **key 140**, not `KEYCODE_POWER` (26). Presses injected
  with `adb shell input keyevent 26` take a different path and are not
  broadcast; use `input keyevent 140` to imitate the real button.
* The firmware handles the knob itself: each click also moves the system media
  volume one step and plays a beep ~0.3 s later at the system volume. QRV-Hub
  therefore keeps the system volume at 0 while nothing plays (silent beep) and
  covers the firmware's volume popup with its own slider.
* On this firmware media, ring, system and call volume are one linked volume.

## LED (apps → firmware)

The LED has a red and a green element (no blue). Send:

```java
Intent i = new Intent("unipro.led.set");
i.putExtra("color", 1);           // 0 = back to the firmware, 1 red, 2 green, 3 yellow (both)
i.putExtra("flicker", true);      // optional: blink
i.putExtra("delay_on", 1000);     // ms on
i.putExtra("delay_off", 3000);    // ms off
sendBroadcast(i);
```

With `color` 0 the firmware shows its battery pattern again: slow green flash
(1 s on, 3 s off) on battery above the low level, charging at 90 % or more, or
full; slow red flash while charging below 90 %; fast yellow flash when the
battery is low.

## Screen (apps → firmware)

| Broadcast | Effect |
|---|---|
| `unipro.screen.sleep` | Turn the display off now |
| `unipro.screen.wakeup` | Turn the display on |

The normal Android screen timeout cannot go below about 10 s on this firmware,
so QRV-Hub uses `unipro.screen.sleep` for its 5-second turn-off.

## Display size

The panel is 160×128. The apps are laid out for a 240×200 logical frame that
the system scales down (`adb shell wm size 240x200`, density 120). After a
reboot the system sometimes keeps the size but does not rescale the panel
(only the top-left corner shows); QRV-Hub Display checks SurfaceFlinger's viewport and
re-applies the size until it is right.
