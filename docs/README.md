# On-device transcription benchmark

Runs of a food-safety audit narration, recorded through the device microphone and
transcribed on device. 14–17 August 2026, on a Lenovo TB336FU tablet and a Xiaomi
25097RP43I (Snapdragon 8s Gen 4, `SM8735P`).

Open **[stt-benchmark.html](stt-benchmark.html)** in a browser — it is self-contained
(inline CSS and SVG, no network) and follows the system light/dark theme.

## Headline

| Run | Device | Engine | Raw WER | Number-normalised |
|---|---|---|---|---|
| English 11:19 | Lenovo | Gemma 4 E2B, CPU | 17.8% | 12.8% * |
| German 20:21 | Lenovo | Gemma 4 E2B, GPU | 19.3% | 15.2% |
| German 20:07 | Lenovo | Whisper Small, CPU | 15.0% | 9.4% |
| English 22:14 | Lenovo | Whisper Small, XNNPACK | 17.9% | 8.4% |
| English 22:21 | Xiaomi | Whisper Small, XNNPACK | 35.4% | 27.8% † |
| **English 22:42** | **Xiaomi** | **Whisper Small, XNNPACK** | **17.4%** | **7.8%** |

\* Only measurable once `wer.py` gained an English numeral grammar (`--lang=en`); the
German default is unchanged and every German figure above reproduces byte-identically.

† **Voided, not a recognition result.** The display timed out mid-recording and the
microphone went dead with it, losing ~4 minutes of audio and 634 contiguous words. The
VAD correctly reported the silence. See the HTML write-up for the two ways to detect
this after the fact — the WAV is deleted once transcription succeeds, so a degraded run
cannot be re-decoded.

The two German runs use byte-identical audio, so only the recogniser differs.
Whisper Small makes ~40% fewer errors on the normalised measure, and was the only
run of the three that returned a detected language (`de`) and tagged all three marker spans.

The two 22-minute English runs also use byte-identical audio, so only the device differs.
Excluding marker phrases from both sides gives 7.5% (Lenovo) and **6.9%** (Xiaomi), the
best the pipeline has produced. The Xiaomi is **4.4× faster end to end** — 473 s against
2063 s from stop to transcript, i.e. 0.35× realtime against 1.55×.

## Reproducing the scores

```sh
python3 wer.py data/audit_de.txt data/transcript_whisper.txt   # German, Whisper
python3 wer.py data/audit_de.txt data/transcript_de.txt        # German, Gemma
python3 wer.py data/audit_script.txt data/transcript_clean.txt # English, Gemma

# The 22-minute English runs, same audio file, one device each.
python3 wer.py data/audit_script_en2.txt data/transcript_en2_whisper.txt        --lang=en  # Lenovo
python3 wer.py data/audit_script_en2.txt data/transcript_en2_whisper_sd8sg4.txt --lang=en  # Xiaomi
```

`[[slnc N]]` directives in the source scripts are stripped by the scorer, so the
scripts are passed in as-is.

`wer.py` prints two figures. **Raw** compares words literally. **Number-normalised**
first rewrites spoken numerals to the digit forms a correct transcript should
produce, so that `neunzehn Komma vier` → `19,4` is not scored as an error. Both strip
punctuation, as standard WER does — an earlier version did not, which inflated every
score by several points.

German is the default. `--lang=en` selects an English numeral grammar that composes
numbers rather than looking them up, and collapses spoken batch codes
(`"T four two dash seven one six"` → `t42-716`). Quote the normalised figure: raw WER on
this content is dominated by formatting rather than misrecognition, and the residual
normalised errors are British/US spelling (`programme`→`program`), hyphenation, and
proper nouns (`Blomqvist`→`Blumkvist`, `swab`→`swap`, `site`→`side`).

## Files

| Path | What it is |
|---|---|
| `stt-benchmark.html` | The report |
| `diarization-pipeline.html` | How a transcript gets attributed to a speaker — every stage, with file:line anchors and the real timeline where a backchannel is swallowed |
| `diarization-benchmark.md` | Speaker attribution scored against sample-exact ground truth |
| `wer.py` | Scorer — Ratcliff–Obershelp alignment, two normalisation modes |
| `diarscore.py` | Diarisation scorer — recomputes the device's WER and speaker accuracy through `wer.py`, adds frame and turn accuracy |
| `data/audit_script.txt` | English source script, with `say` silence directives |
| `data/audit_de.txt` | German source script |
| `data/transcript.txt` | English, Gemma — as saved, including four leaked model refusals |
| `data/transcript_clean.txt` | English, Gemma — refusals removed; this is the scored one |
| `data/transcript_de.txt` | German, Gemma |
| `data/transcript_whisper.txt` | German, Whisper Small |
| `data/audit_script_en2.txt` | Longer English script (3086 words) used for the 22-minute runs |
| `data/transcript_en2_whisper.txt` | English 22:14, Whisper Small + XNNPACK, Lenovo |
| `data/transcript_en2_whisper_sd8sg4.txt` | English 22:42, Whisper Small + XNNPACK, Xiaomi — the best run |

## Method

Audio was generated with macOS `say` (Samantha for English, Anna for German) at
150 wpm and played through laptop speakers into the tablet microphone at 90% volume.
The scripts embed `[[slnc N]]` directives so pauses land where a real speaker would
put them, giving the segmenter quiet points to cut on.

Marker phrases (`start non conformity`, `Abweichung beginnen`, …) are excised into
tags rather than transcribed, so they register as deletions and understate all three
scores slightly.

Reuse the rendered `.aiff` across devices rather than re-synthesising it, so narration
variance drops out of a device comparison.

**A narration script must never contain `stop recording`, `discard recording`,
`open settings` or `open models`.** The keyword spotter listens for them live, and the
narration will otherwise halt or discard its own recording.

**Keep the screen on for the whole recording.** This voided a run: the display timed out,
the microphone went dead with it, and ~4 minutes of audio never existed. `svc power stayon`
was set and did not hold on MIUI. Set the timeout beyond the recording length and poll it
during the run, not just at the start:

```sh
adb -s $S shell settings put system screen_off_timeout 1800000
adb -s $S shell "dumpsys power | grep -E 'mWakefulness=|mStayOn'"   # expect Awake / true
```

Two ways to detect lost audio afterwards, neither needing the WAV (which is deleted once
transcription succeeds, so a degraded run cannot be re-decoded): `dumpsys batterystats
--history | grep -i screen`, and the app's own pre-decode watermarks — a run of
byte-identical slice lengths means the microphone delivered nothing, because the
quiet-point search only lands exactly on its 20 s target when no frame is quieter than
any other.

## Caveats

- Gemma ran on GPU for the German run and CPU for English; Whisper on CPU. Both
  German runs decoded at roughly 45 s/slice, so the accuracy comparison is not
  confounded by wall-clock, but the English↔German Gemma comparison crosses an
  accelerator boundary.
- Substitution counts are not comparable across languages — the German script is
  longer and tokenises differently. Compare rates, not counts.
- One speaker, one voice, clean room acoustics. Real site audio with overlapping
  speech and machine noise will be worse than any number here.
- The device comparison is one recording each, not a repeated measure. The 0.6-point
  normalised gap between the two 22-minute English runs is well inside what a single
  pair can establish; the 4.4× speed difference is not.
- The best run stored its language as `nn` rather than `en`, and its first marker tag
  opened at character 0. Neither affects the WER figures — the transcript text is
  correct — but both are open defects, written up in the HTML report.
