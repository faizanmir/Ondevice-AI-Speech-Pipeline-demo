# Benchmark data

References and transcripts for the on-device STT runs written up in `../stt-benchmark.html`.
Score any pair with:

```sh
python3 docs/wer.py <reference> <transcript> --lang=en
```

The recordings themselves are **not** in the repo — 120 MB of synthesised speech is not worth
versioning. They live in `~/Downloads/Archive/audio/`, each beside the `say` script that produced
it, so any of them can be regenerated from the script alone.

## References

| File | What it is |
|---|---|
| `audit_script_en2.txt` | The 21:30 audit narration. `audio/audit_en2.aiff`. |
| `audit_script_en2_first10min.txt` | The same, cut where a 10-minute run was stopped. |
| `audit_script_en2_first60s.txt` | The same, cut for the 60-second pacing probe. |
| `audit_script.txt`, `audit_de.txt` | Earlier English and German scripts. Their audio is gone. |
| `dialog_*.txt` | Five auditor/auditee dialogues, synthesised from a third party's PDF transcripts to compare against their published numbers. |

## The scorer changed on 2026-08-19

`wer.py` and the on-device `Wer.kt` both aligned with `difflib.SequenceMatcher` (longest contiguous
matching blocks). Both now compute a **minimum edit distance**, in a band that widens until the
answer proves itself.

Every number below was re-scored under the new aligner and nine of the ten are unchanged; the
unpaced run moved by 0.2 points. The old alignment was fine on ordinary transcripts. It failed on
exactly one shape, badly enough to be worth the swap: **a reference containing repeated content**.
Scoring a recording that plays a dialogue twice against a reference holding it twice, it anchored
the reference's first copy against the hypothesis's second pass and reported **103.9%** where the
answer was 8.7%.

Two practical consequences:

- A WER at or above ~100% means "these two texts are not the same material", not "the recogniser
  failed". Insertions are not bounded by the reference length, so the rate is not capped at 100.
- A transposition now costs what it should. Moving a three-word phrase is three substitutions, not
  three deletions plus three insertions.

## Runs

Normalised WER — numerals spelled out, so "14001" and "fourteen thousand and one" agree. Raw WER is
the first number `wer.py` prints. Everything below is the Xiaomi SM8735P unless the row says otherwise.

| Transcript | Backend | WER |
|---|---|---|
| `transcript_en2_whisper.txt` | Whisper Small, Lenovo TB336FU | 8.4% |
| `transcript_en2_whisper_sd8sg4.txt` | Whisper Small / XNNPACK | **7.8%** |
| `transcript_en2_whisper_screenoff_sd8sg4.txt` | Whisper Small — **void**: the screen blanked mid-recording and the mic died with it | 27.8% |
| `transcript_en2_platform_unpaced_sd8sg4.txt` | Android platform, audio written as fast as the pipe took it | 57.9% |
| `transcript_en2_platform_8x_sd8sg4.txt` | Android platform, fed at 8× real time | 25.7% |
| `transcript_en2_platform_16x_sd8sg4.txt` | Android platform, fed at 16× real time (adopted) | 26.1% |
| `transcript_en2_platform_60s_sd8sg4.txt` | Android platform, first 60 s only — pacing probe | 24.0% |
| `transcript_en2_platform_10min_sd8sg4.txt` | Android platform, first 10 min only | 18.0% |
| `transcript_dialog_major_whisper_sd8sg4.txt` | Whisper Small / XNNPACK, `dialog_major_non_conformity` | **2.6%** |
| `transcript_dialog_major_platform_sd8sg4.txt` | Android platform, same audio | 15.0% |

The unpaced row is the reason `PlatformTranscriber.FEED_PACE` exists: writing faster than the
recogniser consumes makes it drop whole utterances silently, announcing four beginnings of speech
and returning two results.

The last two rows are the only clean backend comparison in the set — identical audio, same device,
same session — and they are why the platform backend's 26% on the audit script is not a verdict on
the implementation. The same code scores 2.6% and 15.0% on easier content. Whisper is roughly
5.8× more accurate than the platform recogniser on the audio both were given.
