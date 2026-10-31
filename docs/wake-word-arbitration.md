# Wake word arbitration

How several VACA devices decide which one answers when more than one hears the same wake word, how
they find each other, and how interruptions and music ducking fit in.

Code: `app/src/main/java/com/msp1974/vacompanion/broadcasts/` (`VacaUdpBroadcast`, `WakeWordArbiter`,
`PeerPresence`), `satellite/Satellite.kt` (`handleWakeWordDetection`, `interruptResponse`), `audio/`
(`DetectionBuffer`, `MicLevelMonitor`, `WakeLevelCalibration`), `wakeword/DetectionRearmGate.kt`.

## In short

- Devices on the same network talk over a small UDP broadcast protocol and elect one **master**.
- Arbitration is **by wake word**: every device that hears a given wake word, in any room, reports it
  to the master, and the master picks one. **Only devices that reported it take part in the decision.**
  A device that didn't hear it is never consulted, told to stop, or counted as busy.
- The winner is normally the device with the **loudest calibrated mic level**, i.e. the one the
  speaker is nearest to. Ties fall back to who answered last, then detection score, then device id.
- A reporting device that is **already responding** always wins: the wake word interrupts its
  response, and the other devices that heard it do nothing.
- Every device that hears the wake word starts **buffering audio** straight away, so nothing said
  while the decision is made is lost. The winner streams that buffer and then live audio to Home
  Assistant; the others drop theirs.
- **Ducking** (lowering music on nearby devices) is separate. It follows each device's **area or custom
  zone**, not the wake word.

## Switching it off

Under Settings → Experimental → Voice assistant, each part can be turned off on a device (arbitration
is on by default, ducking follows the area). Every setting in that group is stored on the device and
survives a restart, and the app logs their values (`Experimental settings: ...`) when wake word detection
starts:

- **Wake word arbitration** off: the device doesn't report to the master or abide by its decisions. It
  answers every wake word it hears, and a response it is giving is still interrupted by its own wake
  word, but it doesn't tell the others.
- **Ducking scope** set to *Disabled* (the other choices are *Area* and *Custom zone*): the device
  neither sends `duck` when it answers nor lowers its music when it receives one. Its duck group is
  blank, so it matches no other device. Its own music is still lowered while it speaks.
  (`pipeline_completed` is always sent and handled, since unducking something that never ducked does
  nothing.)

The custom ducking zone setting lives there too and only applies in *Custom zone* scope.

## Two kinds of group

| | Arbitration | Ducking |
|---|---|---|
| Grouped by | the **wake word** (case-insensitive) | the device's **duck group**: `area:<ha area>` (default) or `zone:<zoneN>`, set by the duck scope setting |
| Who is involved | every device on the network listening for that wake word **that heard it** | devices with the same, non-blank duck group |
| Blank | a device with no wake word (`none`) doesn't take part | no area (area scope) means no ducking |
| Used for | who answers or interrupts | who lowers music when another device starts answering |

A consequence of grouping by wake word: if two devices in different rooms both hear the **same wake
word at the same moment**, only one of them answers. Devices with different wake words never affect
each other, and a device that did not hear it has no part in the decision.

## Messages

JSON broadcasts on UDP port 10950: `{"magic":"VACA","v":1,"type":"...","sender":"<device uuid>","data":{...}}`.
Every message also carries the sender's duck group (`duck_group`) in `data`. Anything that doesn't
parse, lacks the magic, or comes from the device itself is dropped.

| Type | Sent by | Meaning / extra fields |
|---|---|---|
| `device_hello` | a device whose listener has just come up | "I'm here"; the master answers with a heartbeat and every device answers with `presence` |
| `master_claim`, `master_heartbeat` | the master | election and keep-alive |
| `presence` | every device, every 10 s and in reply to a hello | `wake_word`: the wake word it listens for |
| `wake_detection` | a device that heard the wake word and needs a decision | `id`, `score`, `level` (calibrated dB, optional), `wake_word`, `responding` (true if the device was responding when it heard it) |
| `wake_decision` | the master | `winner` (the winning report's id), `ids` (all reports it covers) |
| `duck` | the device that is going to answer | others in its duck group lower their music |
| `pipeline_completed` | the device whose response has ended | others in its duck group unduck |

## The master

There is **one master for the whole network**; it makes a separate decision per wake word.

**Election**

1. A starting device sends `device_hello` and listens for about 2.5 s. An existing master replies with
   an immediate heartbeat.
2. If it hears no master it waits a random 0–0.5 s, checks again, and if there is still none it sends
   `master_claim` and becomes master.
3. The master heartbeats every 2 s. A device that hears no heartbeat for 6 s forgets the master and
   elects again.
4. If two devices both claim, the lowest device id wins and the other stands down.

**What the master alone holds**

- The open decision window for each wake word (the reports gathered so far).
- The last decision for each wake word (kept 2 s), so a late report can be told it lost.
- Which device last answered each wake word (kept 5 minutes). A device that only interrupted its
  response isn't counted as having answered.

**What every device holds** (built from what it hears, so any device can become master)

- *Presence* (`PeerPresence`): which other devices are around and which wake word each listens for. A
  device not heard from for 35 s is dropped. A device that has not said its wake word is assumed to
  compete. This is only used to skip arbitration when nobody else could have heard, and to decide
  early; it never decides who answers.

## What happens when the wake word is heard

On each device that hears it (`Satellite.onWakeWordDetected` / `handleWakeWordDetection`):

**This device is already responding** (it has a pipeline running that isn't stuck): it's an
*interruption*.

1. The device interrupts at once: it stops its pipeline and does nothing else. It never starts a new
   pipeline and doesn't wait for a decision.
2. If any other device listens for this wake word, it also reports to the master with
   `responding = true` and doesn't wait for an answer. This is so the decision for this wake word goes
   to this device and the others that heard it stand down.

**Otherwise** this device is a candidate:

- measure its calibrated level (see below);
- play the wake sound and start the audio buffer (`DetectionBuffer`) at once;
- **arbitrate** (`VacaUdpBroadcast.arbitrateWakeWord`).

**Arbitration is skipped, and the device simply answers, when:**

- it is a manual wake (button, or sent from HA);
- the device has no wake word;
- no master is known yet;
- **no other device listens for this wake word** (from presence), so nobody else could have heard it.

**Otherwise:**

1. The device sends `wake_detection` to the master (or adds it itself if it is the master).
2. The master opens a **150 ms window** for that wake word on the first report; later reports join it.
3. It decides **early** if a responding device has reported (it wins whatever the others say), or if
   every device that could have heard has reported: all the devices listening for that wake word, plus
   itself if it listens for it. Otherwise it decides when the window ends.
4. It picks one (`WakeWordArbiter`), in this order:
   0. a device that is **responding** (it interrupts); if several are, the rules below choose among them;
   1. the **loudest calibrated level**, if it beats the next device by more than 3 dB; devices within
      3 dB of the loudest are too close to call and go on to the next rule;
   2. the device that **last answered** this wake word, if that was within 5 minutes;
   3. the **highest wake word score**;
   4. the **lowest device id**.

   If any contender reported no level (an older version), levels are skipped and it starts at rule 2.
5. It broadcasts `wake_decision`. A report arriving within 2 s after a decision is told at once that it
   lost, so a slow device can't also answer.

**Then:**

- **Winner (a candidate)**: sends `duck`, wakes the screen, and starts the audio pipeline. The pipeline
  streams the buffered audio first, then live audio, in order, with nothing missed or repeated at the
  join.
- **Winner (a responding device)**: has already interrupted its response; nothing more happens.
- **Loser**: cancels its buffer and does nothing more (its wake sound has already played).
- **No decision within 1.5 s**: the device answers anyway and forces a new election, so a dead master
  can never silence a device.
- When a pipeline ends its device sends `pipeline_completed`, and the others in its duck group unduck.

## Timeline

```
 t=0     device A hears the wake word, device B (another room) hears it fainter
         A, B: measure level, play wake sound, start buffering, send wake_detection
 ~t+5ms  master receives both; window opens on the first report
 t+150   (or sooner if every device listening for this wake word has reported)
         master: picks A (louder by > 3 dB), broadcasts wake_decision
 ~t+160  A: sends duck, starts the pipeline, streams buffered audio then live audio to HA
         B: cancels its buffer, does nothing
         A's duck group lowers its music
```

## Interruptions

A wake word said while a device is responding is, for that device, an interruption.

- **The responding device heard it:** it interrupts at once and reports `responding = true`. The
  master awards it the decision immediately (it doesn't wait for the window), so the other devices
  that heard the same wake word lose and do nothing. A report from another device that arrives within
  2 s afterwards is told it lost, because that decision is still the latest.
- **The responding device did not hear it:** nothing tells it to stop. Only devices that reported take
  part, so any other device that heard the wake word is decided among the reporters and answers as
  usual. This is deliberate: it lets someone use a device in one room while another room's device is
  still talking.
- If the interrupting device is the only one listening for that wake word, it simply interrupts; there
  is nobody to tell.
- A device that interrupts isn't remembered as the last to have answered.

## Level and calibration

The level compared is the **raw microphone level before any processing** (WebRTC's gain control would
level everything up to speech loudness): the loudest 80 ms frame in the last 2 s, from
`MicLevelMonitor`, plus this device's **calibration offset**.

### Microphone calibration

Microphones differ in sensitivity by more than a change in distance does, so each device has an
offset (`wake_level_offset_db`, SharedPreferences, never sent to HA) set by the **Microphone
calibration** wizard (Settings → Experimental → Voice assistant). The wizard names the wake word in use
("Hey Jarvis") and takes 5 detections in a normal voice at about an arm's length. The **median** is
offset to -30 dB. While it runs, detections only record a reading; the device doesn't wake. If the
readings span more than 6 dB the wizard says so and suggests trying again.

Calibrate every device in one sitting, at the same distance and volume; the result is good to about
±3 dB, which is why the margin is 3 dB.

## One detection per utterance

The engines report a score every frame, so the frames after the one that crossed the threshold can cross
it too. `DetectionRearmGate` ignores them: after a detection fires, further ones for that wake word are
ignored until the score drops under half the threshold or the engine pauses for 300 ms (a 10 s
failsafe re-opens it). It is a self-contained piece (tagged `REARM-FILTER`) that can be removed on its
own. Ignored frames are logged.

## Timings and limits

| What | Value |
|---|---|
| Decision window | 150 ms (shorter if everyone has reported, or a responding device has) |
| Wait for a decision before answering anyway | 1.5 s |
| Level margin that lets level decide | 3 dB |
| "Last answered" remembered | 5 minutes |
| Late report treated as having lost | 2 s after a decision |
| Heartbeat / presumed dead | every 2 s / 6 s |
| Election wait at startup / random delay before claiming | 2.5 s / 0–0.5 s |
| Presence announced / peer forgotten | every 10 s / after 35 s |
| Wake word re-arms after | score < 50% of threshold, or a 300 ms gap (failsafe 10 s) |
| Level window / calibration readings / reference level | 2 s / 5 / -30 dB |

## Things to be aware of

- **Only reporters decide.** A device that doesn't hear the wake word is never asked to stop and
  never blocks another. Two devices can therefore both be responding, to different people.
- **Same wake word heard in two rooms at the same moment: one answer.** That is what arbitrating by
  wake word means. Use a different wake word per room if that is a problem.
- **A responding device that can't hear the wake word over its own reply is not interrupted by the
  others.** It stops by voice only if it hears the wake word itself (or the stop word).
- **Keep all devices on the same build.** A device that doesn't send `presence` is forgotten after
  35 s and may answer when it shouldn't.
- **The broadcast must reach every device**: same subnet, no client isolation between them.
- **Every candidate plays the wake sound**, including the ones that lose. The winner's microphone can
  hear the others' sounds; for a *spoken* wake sound, check the transcripts.
- **Calibration depends on how you spoke.** Redo it if devices move or if one consistently wins when it
  shouldn't.

## Debugging

Useful log lines (`adb logcat -s VacaUdpBroadcast Satellite SatelliteWakeWorkHandler`):

- `Wake word detected: ... (mic level -31.4 dB)`: the calibrated level that device measured.
- `VacaUdpBroadcast: decided winner 1a2b3c4d from 2 detection(s): <id> -31.0 dB, score 0.97, <id> -38.2 dB, score 0.98`:
  the master's decision, with every report. `(interrupting its response)` and `, responding` mark an
  interruption.
- `no other device listens for <wake word>, answering without arbitration`: the skip path.
- `reported after the decision, telling it it lost`: a report that missed the window.
- `no decision from master ..., starting anyway`: the master didn't answer in 1.5 s.
- `Wake word heard while responding, interrupting`: a device interrupting its own response.
- `Ignoring the rest of the ... detection already acted on`: residual frames dropped by the re-arm gate.

Pure logic with unit tests: `WakeWordArbiterTest`, `PeerPresenceTest`, `DetectionRearmGateTest`,
`DetectionBufferTest`, `WakeLevelCalibrationTest`, `MicLevelMonitorTest`. The message flow between
devices and the `Satellite` wiring need real devices to test.
