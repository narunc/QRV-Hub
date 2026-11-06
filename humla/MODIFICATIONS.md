# Changes to Humla in this repository

This is [Humla](https://gitlab.com/quite/humla) (GPL-3.0) at upstream commit
`7966f382` ("Support echo cancellation using Android hardware AEC"), with these
changes made in 2026 by Narun Chanyavilas <opensource@narun.me> for QRV-Hub:

| File | Change |
|---|---|
| `audio/AudioInput.java`, `protocol/AudioHandler.java` | The microphone is stopped while push-to-talk is idle and restarted on the next press (about 30 ms), instead of recording continuously. Saves battery. |
| `net/HumlaConnection.java` | Keep-alive ping every 15 s instead of every 5 s (the server allows 30 s). Fewer radio wake-ups. |
| `src/main/jni/Application.mk` | Source paths in the native libraries are recorded relative to the project (`-ffile-prefix-map`), so built APKs do not contain the build machine's folder names. |
| `audio/AudioOutput.java` | An output gain that QRV-Hub sets for its own volume scale; at gain 0 the audio track stays paused so the speaker amplifier stays off; a listener told when playback starts and stops; each new speaker is logged with their channel and the server's voice context. |

The native codecs (`src/main/jni/opus`, `speex`, `celt-*`) and
`libs/humla-spongycastle` are unmodified upstream projects, included as git
submodules at the commits Humla pins.
