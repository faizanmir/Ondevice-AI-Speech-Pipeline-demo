#!/usr/bin/env python3
"""Render a [S1]/[S2] dialogue script as one 16 kHz mono WAV using macOS `say`,
with a distinct voice per speaker, and emit a sample-exact ground-truth timeline.

No microphone is involved: `say` writes each turn straight to a file and the turns
are concatenated here, so the truth timeline is exact by construction rather than
recovered by alignment. That is the difference between this and the over-the-air
runs, where `align.py` has to find the playback offset first.

Turn clips are cached by (voice, rate, text), so re-rendering a script after
editing a few lines only synthesises what actually changed.

    python3 docs/render_long_dialog.py SCRIPT OUT.wav \
        --voice1 "Daniel" --voice2 "Samantha" \
        --name1 Bob --name2 Tim --rate 150
"""
import argparse, hashlib, json, re, subprocess, sys, wave
from pathlib import Path

RATE = 16000
GAP_MS = 250      # turn-taking pause: natural, and short enough not to flatter the VAD
LEAD_MS = 500     # a little silence before the first word

ap = argparse.ArgumentParser()
ap.add_argument("script", type=Path)
ap.add_argument("out", type=Path)
ap.add_argument("--voice1", required=True, help="macOS voice for [S1]")
ap.add_argument("--voice2", required=True, help="macOS voice for [S2]")
ap.add_argument("--name1", default="S1", help="speaker name [S1] should be attributed to")
ap.add_argument("--name2", default="S2", help="speaker name [S2] should be attributed to")
ap.add_argument("--rate", type=int, default=150, help="words per minute passed to say -r")
ap.add_argument("--cache", type=Path, default=None)
args = ap.parse_args()

VOICE = {"S1": (args.voice1, args.name1), "S2": (args.voice2, args.name2)}
cache = args.cache or (args.out.parent / "turns-say")
cache.mkdir(parents=True, exist_ok=True)

turns = []
for line in args.script.read_text(encoding="utf-8").splitlines():
    m = re.match(r"^\[(S\d)\]\s*(.*)$", line.strip())
    if m and m.group(2).strip():
        turns.append((m.group(1), m.group(2).strip()))

if not turns:
    sys.exit(f"no [S1]/[S2] turns found in {args.script}")
print(f"{len(turns)} turns from {args.script.name}")

frames = bytearray(b"\x00\x00" * int(RATE * LEAD_MS / 1000))
truth, rendered, reused = [], 0, 0

for i, (tag, text) in enumerate(turns):
    voice, name = VOICE[tag]
    key = hashlib.sha256(f"{voice}\x00{args.rate}\x00{text}".encode()).hexdigest()[:20]
    clip = cache / f"{key}.wav"

    if not clip.exists():
        # -f keeps the text off the argument list, so umlauts and quotes survive intact
        txt = cache / f"{key}.txt"
        txt.write_text(text, encoding="utf-8")
        subprocess.run(
            ["say", "-v", voice, "-r", str(args.rate), "-f", str(txt),
             "-o", str(clip), "--data-format=LEI16@16000"],
            check=True,
        )
        txt.unlink()
        rendered += 1
    else:
        reused += 1

    with wave.open(str(clip)) as w:
        assert w.getnchannels() == 1 and w.getframerate() == RATE, (w.getparams(), clip)
        data = w.readframes(w.getnframes())

    start = len(frames) // 2
    frames += data
    end = len(frames) // 2
    truth.append({
        "index": i, "tag": tag, "speaker": name, "voice": voice,
        "startSample": start, "endSample": end, "text": text,
    })
    frames += b"\x00\x00" * int(RATE * GAP_MS / 1000)

args.out.parent.mkdir(parents=True, exist_ok=True)
with wave.open(str(args.out), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes(bytes(frames))

total = len(frames) // 2
Path(str(args.out) + ".truth.json").write_text(json.dumps(
    {"sampleRate": RATE, "totalSamples": total, "turns": truth}, indent=1, ensure_ascii=False
), encoding="utf-8")

speech = sum(t["endSample"] - t["startSample"] for t in truth)
print(f"  synthesised {rendered}, reused {reused} from cache")
print(f"  {args.out}  {total/RATE/60:.1f} min "
      f"({speech/RATE/60:.1f} min speech, {100*(1-speech/total):.1f}% silence)")
for tag in ("S1", "S2"):
    sec = sum(t["endSample"] - t["startSample"] for t in truth if t["tag"] == tag) / RATE
    n = sum(1 for t in truth if t["tag"] == tag)
    print(f"  {tag} -> {VOICE[tag][1]:<6} {VOICE[tag][0]:<28} {n:3} turns  {sec/60:.1f} min")
