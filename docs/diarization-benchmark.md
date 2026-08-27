# Speaker attribution benchmark — ElevenLabs dialogues

On-device diarisation scored against sample-exact ground truth, on the Lenovo TB336FU.
Companion to `stt-benchmark.html`, which measures the words alone; this measures **who said them**.

Run with `docs/diarscore.py`, which recomputes the app's own figures through `wer.py` — the source
of truth for every WER published here — and adds the two measures the on-device number cannot see.

## The corpus

One 36-turn, 823-word auditor/auditee dialogue, rendered three ways by ElevenLabs. Each turn is
synthesised separately and concatenated, so `.truth.json` carries an exact sample range, speaker and
text per turn. The audio is not versioned (37 MB); it lives in `~/Downloads/Archive/audio/`, and
`render_dialog_11labs.py` regenerates it. The references are in `data/*.ref.txt`, generated from the
truth timeline in the `[S1] … [S2] …` form the in-app scorer reads.

| file | S1 | S2 | case |
|---|---|---|---|
| `identifiable_fm` | Sarah (f) | Daniel (m) | mixed gender — the easy case |
| `eleven_two_voice` | George (m) | Daniel (m) | same gender — the hard case |
| `eleven_ota` | George (m) | Daniel (m) | same audio, played aloud and re-recorded by the tablet |

`eleven_ota.truth.json` is **already offset-aligned** — its first turn starts at 203840 rather than
8000, and the 195840-sample difference is exactly what `align.py` recovers. Applying the offset a
second time is a mistake this benchmark made once: it shifts every range 12.24 s late and makes the
truth appear to overrun the recording by 1.63 s. It does not. Pass `--offset 0` for these files.

## Three denominators, one run

The same run scores very differently depending on what you divide by, and the spread is not noise —
it is the shape of the failure:

- **Word accuracy** — share of words both transcripts agree were said that landed on the right
  speaker. What the app shows on the row.
- **Frame accuracy** — share of 10 ms speech frames attributed correctly. Comparable to the earlier
  runs in `~/Downloads/Archive/audio/diarization_two_voice_results.md`.
- **Turn accuracy** — share of the 36 turns whose majority speaker is right.

Word and frame accuracy both weight a turn by how much of it there is. This dialogue is a real
transcribed conversation and is therefore full of one-word backchannels — "Mhm.", "Yeah.", "Okay." —
which are nearly free to get wrong on those two measures and are exactly what the diariser misses.
Turn accuracy weights every speaker change equally, which is closer to how someone reading the
transcript experiences it.

Speaker labels are arbitrary, so all three are computed under the one-to-one label assignment that
maximises agreement, the same convention diarisation error rate uses.

## Method

Enrolment is **three takes per voice, cut from the longest turns of the file being scored**, then
the whole file is diarised and scored. That means the enrolment audio is inside the test set: the
naming figures below are optimistic and are not what a first-time recording would give, where nobody
has enrolled from the recording itself. They are reported as a ceiling, not as field performance.

Speech model: **Parakeet TDT v3, int8, XNNPACK** (`stt_backend=onnx`, `onnx_provider=xnnpack`) —
the only backend besides Whisper Small that reports word timings, which diarisation alignment
requires. Device: Lenovo TB336FU.

## Results

2026-08-25. Each row is one run of the whole file; ~4:50 of audio in ~2:45, about 1.7× real time.

| file | case | run | coverage | WER | speaker (word) | frame | turn |
|---|---|---|---|---|---|---|---|
| `identifiable_fm` | mixed gender | 2:44 | 99.1% | **2.5%** | 98.3% | 96.2% | **29/36** |
| `eleven_two_voice` | same gender | 2:50 | 99.1% | **2.9%** | 98.6% | 96.0% | **27/36** |
| `eleven_ota` | same gender, over the air | 2:46 | 99.3% | **2.5%** | 98.5% | 95.9% | **26/36** |

Every run found 3 speakers where there are 2 — the third is `Unknown Speaker ?`, holding 1–2 s of
audio the aligner could not attribute. That is a large improvement on the 5–7 speakers earlier runs
produced, which is where most of the gain over the old numbers comes from.

## Transcription is solved here; attribution is not

WER sits at 2.5–2.9% across all three. Nothing in this benchmark is limited by the recogniser, and
the over-the-air row scoring the same as the clean import says the channel is not the limit either.

The attribution story depends entirely on which denominator you read:

- **By word, all three are the same** (98.3–98.6%) and the differences are noise.
- **By turn, they separate**: 29 / 27 / 26 of 36.

The word figure is not wrong, it is answering a different question. Backchannels are one or two
words, so getting fourteen of them wrong costs under two points of word accuracy — and backchannels
are exactly what fails.

## Everything above two seconds is correct

Split by turn length, across all three runs:

| turn length | identifiable_fm | eleven_two_voice | eleven_ota |
|---|---|---|---|
| under 2 s | 9/16 | 5/14 | 4/14 |
| 2–5 s | — | 2/2 | 2/2 |
| 5–15 s | 14/14 | 14/14 | 14/14 |
| over 15 s | 6/6 | 6/6 | 6/6 |

**Not one turn of two seconds or longer was attributed to the wrong person, in any of the three
runs.** Every error in this benchmark is a sub-two-second backchannel. That matches the 2-second
floor `SHORT_BLOCK_SECONDS` already encodes in `DiarizeWorker` — under two seconds there is not
enough voice for an attribution to be evidence — and it is why the mixed-gender file wins on turns
while tying on words: its advantage is entirely in the backchannels (9/16 against 5/14).

## The embedding model cannot separate two male voices

Enrolling the second voice raised the app's own collision warning — *"This sounds like Bob"* — on
**both** same-gender pairs, and not at all on the mixed pair. The voiceprints say why:

| pair | within-speaker | between-speaker | margin |
|---|---|---|---|
| Sarah (f) vs Daniel (m) | 0.90 | **0.222** | 0.68 |
| George (m) vs Daniel (m) | 0.91 | **0.734** | 0.18 |

A speaker's own three takes agree at ~0.90 either way. Two male ElevenLabs voices sit at 0.734 —
closer to each other than a genuine within-speaker margin is wide. The guard is right to fire, and
this is the ceiling every downstream stage inherits: once a cluster mixes both voices,
`labelClusters` can pick only one name and nothing later can recover the other.

That the same-gender runs still reach 98.5% by word says the *segmentation* is doing the work, not
the embedding — it is keeping the long turns apart on acoustic continuity, and only the short
fragments fall through to a voiceprint comparison that cannot separate them.

## Against the earlier numbers

`diarize_result_eleven.db`, the same clean file before ElevenLabs-matched enrolment existed:

| | speaker (word) | frame | speakers found |
|---|---|---|---|
| earlier, enrolled from macOS `say` voices | 55.9% | 53.6% | 7 |
| now, enrolled from the file's own voices | 98.6% | 96.0% | 3 |

The jump is not a model change — it is enrolment that matches the audio. The old run's voiceprints
came from macOS `say` and could not match ElevenLabs speech at all, so most clusters fell through to
`Unknown Speaker N`. It is a measurement of the old *setup*, not of the old pipeline.

The 96.0% frame accuracy also reproduces the ~96% recorded after the 3D-Speaker swap, from a
different setup, which is the reassuring part: two independent routes to the same figure.

## What these numbers are not

Enrolment was cut from the audio being scored, so the naming figures are a **ceiling**. A first
recording of someone, enrolled from a separate take, will do worse — how much worse is the obvious
next measurement, and it needs held-out enrolment. The transcription figures (coverage, WER) are
unaffected: they never touch the voiceprints.

## Does removing silence break segmentation?

The question compaction raises. `SpeakerDiarizer` sets `minDurationOff = 0.5f`, so pyannote uses
**silence duration as a cue** for where a turn ends — and splicing removes exactly that cue, while
creating artificial joins inside its 10-second analysis window. The plausible failure is turns
*merging* across a removed pause.

Probed with the truth timeline as a perfect VAD: `eleven_two_voice.wav` compacted to its 36 turns,
so **every inter-turn pause is gone** and each speaker change is a zero-silence splice. That is far
harsher than a real VAD would be — `SpeechRegions.MIN_GAP_SAMPLES` never cuts a gap under 1.5 s, and
the gaps here are ~250 ms, so a real run would not have removed any of them. Same enrolment clips,
same reference, same device.

| | baseline | compacted | change |
|---|---|---|---|
| audio | 288.6s | 279.1s | −3.3% |
| blocks | 22 | 23 | +1 |
| speakers found | 3 | 3 | — |
| coverage | 99.1% | 99.0% | −0.1 |
| WER | 2.9% | **2.5%** | −0.4 |
| speaker accuracy | 98.6% | 98.8% | +0.2 |
| frame accuracy | 96.0% | 95.5% | −0.5 |
| turn accuracy | **27/36** | **27/36** | — |
| run time | 170.0s | 161.8s | −4.8% |

**The predicted failure did not happen.** Turns did not merge — the block count went *up* by one,
not down — and turn accuracy is identical, including every per-length bucket (5/14, 2/2, 14/14,
6/6). The only movement is 0.5 points of frame accuracy, which is what you would expect from block
ranges no longer being able to stretch through silence that is no longer there: blocks are derived
from word times, and a word's recorded end is the next word's start.

Two things this does **not** establish. The corpus only had 3.3% silence, so this is a quality probe
and says nothing about the memory and time savings, which need a real meeting. And the enrolment was
recreated between the two runs from the same clips, so the ±0.2 on speaker accuracy is within
enrolment noise rather than an effect of compaction.

What it does establish is the thing that was blocking: **splicing at every turn boundary, with no
silence left anywhere, does not degrade segmentation on this material.**

## A real meeting, over the air

The measurement the corpus above cannot make. Everything so far is dense synthesised dialogue with
3.3% silence — no gap in it is long enough for `SpeechRegions` to cut, so compaction removes 0.0%
and the feature is untested where it is meant to be used.

`eleven_meeting.wav` is the same 36 turns, same two voices, same words, with **160 s of dead air**
inserted in nine stretches of 10–25 s: 7:26 long, 62.5% speech. Every clip came from the render
cache, so the only variable against the baseline is the silence.

It was then **played through the laptop speakers and recorded by the tablet's own microphone**,
using the app's Record button. That matters more than it sounds. In the file, dead air is digital
silence and detecting it proves nothing; captured over the air it is *room tone*, which is the thing
a real meeting actually presents. `align.py` recovered the 14.49 s playback offset (score 1975).

    compaction: 469.2s of audio -> 297.1s of speech in 10 regions (36.7% removed)

| | clean import | plain over-the-air | meeting over-the-air |
|---|---|---|---|
| audio | 288.6s | 311.2s | **469.2s** |
| dead air | none | none | **40.5%** |
| removed | — | — | **36.7%** |
| held in RAM | 17.6 MB | 19.0 MB | **18.8 MB** (29.7 uncompacted) |
| coverage | 99.1% | 99.3% | 98.4% |
| WER | 2.9% | 2.5% | 2.8% |
| speaker accuracy | 98.6% | 98.5% | 95.8% |
| frame accuracy | 96.0% | 95.9% | 93.1% |
| turn accuracy | 27/36 | **26/36** | **26/36** |

**Turn accuracy is identical to the comparable over-the-air run**, and the sub-2s bucket is a point
better (5/14 against 4/14). Attribution costs 2.7 points against that baseline while a third of the
audio is thrown away, and this run carries three penalties at once -- a microphone, 40.5% dead air,
and compaction. WER moved 0.3 points, which is the recogniser saying it never noticed: it runs on
the full recording by design, and only diarisation sees the compacted array.

The diariser found 9 clusters in 66 turns, folded to 5, named 3 -- Bob 170.7s, Tim 177.7s, 14.0s
unattributed across nine fragments. Splicing at nine points did not fragment it.

The one regression is the 5-15s bucket, 14/14 to 13/14. One long turn, against 36.7% of the audio.

Extrapolated at this ratio an hour-long meeting holds **144 MB rather than 228 MB**, which is what
moves two hours from an out-of-memory kill to affordable. Runtime is not quoted here: the run before
this one took twice as long as its own baseline for reasons not yet understood, and until that is
explained no speed number from this device is trustworthy.

## German

Whether any of this survives a change of language. Parakeet v3 is the recogniser either way -- its
own blurb claims "English, German and 23 more European languages" -- and diarisation should not care
at all, since a voice embedding encodes a voice rather than what it is saying. Both claims are
testable and neither had been tested.

`audit_de_dialog_say.txt` is the same audit conversation in German: an auditor pressing an
unprepared quality manager about an unplanned system change, written to the same shape as the
English one -- balanced turns, and thick with the one-word backchannels that are this pipeline's
known weak point. Only the first **24 turns** were rendered, because the ElevenLabs free tier had
3,202 characters left and the full script needs 4,631. The remaining 18 turns are in the file,
unrendered.

Two constraints worth stating, because they bound what the numbers mean:

- **The voices are George and Daniel** -- the same two as the English benchmark. Native German
  voices exist in the shared library but the free tier refuses them over the API
  (`paid_plan_required`), so this is English-accented German. It makes the WER an upper bound rather
  than a publishable German figure. It also makes the comparison *cleaner* in one specific way: the
  voices are held constant, so the English/German difference is language, not speaker.
- **Enrolment was not redone.** Bob and Tim were still enrolled from the *English* clips of these
  same voices, which turns a limitation into the more interesting question below.

| | English, same voices | German |
|---|---|---|
| audio | 288.6s, 36 turns | 185.0s, 24 turns |
| coverage | 99.1% | 98.2% |
| WER | 2.9% | **2.5%** |
| speaker accuracy | 98.6% | **98.2%** |
| frame accuracy | 96.0% | 95.3% |
| turn accuracy | 27/36 (75%) | 16/24 (67%) |
| clusters, before → after folding | 7 → 3 named | **4 → 2** → 3 named |
| runtime | 170s (0.59x realtime) | 127s (0.69x realtime) |

**German WER came out slightly better than English**, which was not the expected result -- the
earlier platform-recogniser work in `de-de-service-wedge` had German as the harder language, and the
prediction here was the same. On Parakeet it is not.

**A voiceprint built in one language matches the same speaker in another.** Bob and Tim were named
at 98.2% from embeddings computed entirely on English audio. Nothing was re-enrolled. That is worth
knowing for a multilingual meeting: enrolment does not have to be repeated per language, which is
the assumption the feature was quietly built on and had never checked.

Clustering was the cleanest of any run here -- 4 clusters folding to 2, against 7 folding to 3 on
the English file -- and the talk-time split came out at Bob 103.6s / Tim 79.6s against a truth of
103.2 / 75.2, with 1.4s unattributed.

The regression is the usual one, sharper: **sub-2s turns scored 2/10**, against 5/14 in English.
Every turn of two seconds or longer was correct again, 14 of 14 across the three longer buckets. Ten
of the twenty-four turns are backchannels -- "Mhm.", "Ja.", "Aha.", "Anscheinend." -- so a failure
that costs under two points of word accuracy costs eight points of turn accuracy. The mechanism is
unchanged from the English runs; the smaller, backchannel-heavier sample simply exposes it more.
