#!/usr/bin/env python3
"""Score a diarisation run off the device against its ground-truth timeline.

The cross-check for the in-app numbers. `Wer.kt` and `SpeakerAccuracy.kt` compute WER and speaker
accuracy on the device and show them on the row; this recomputes both here from the same stored
blocks, so a disagreement between the two is visible rather than assumed away. `wer.py` is the
source of truth for every published WER in this repo, and an on-device figure is only comparable to
the tables in `data/README.md` if it comes out of the same arithmetic.

It also reports the measure the in-app number deliberately cannot: **frame accuracy** against the
sample-exact `.truth.json` timeline. The in-app figure is word-level and compares only words both
transcripts agree were said, which makes it blind to time -- a block can hold the right words under
the right name and still cover the wrong seconds. Frame accuracy is what the earlier runs in
`~/Downloads/Archive/audio/diarization_two_voice_results.md` reported (73.6% on this corpus), so it
is also what makes the new numbers comparable to the old ones.

Usage:
    python3 docs/diarscore.py <speakers.db> <truth.json> <reference.ref.txt> [--offset N] [--lang en]
"""
import argparse, json, pathlib, sqlite3, sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import wer as werlib


def frame_accuracy(blocks, turns, offset, total_samples, step=160):
    """Share of 10 ms speech frames given the right speaker, under the best label mapping.

    Frames where the truth says nobody is speaking are skipped: silence has no correct answer, and
    counting it would let a run that labelled the gaps well hide a run that labelled the speech
    badly. `step` is 10 ms at 16 kHz, the resolution align.py already uses.
    """
    truth_at = {}
    for t in turns:
        for f in range(t["startSample"] // step, t["endSample"] // step):
            truth_at[f] = t["tag"]

    hyp_at = {}
    for b in blocks:
        for f in range((b["startSample"] + offset) // step, (b["endSample"] + offset) // step):
            hyp_at[f] = b["speakerName"]

    pairs = [(truth_at[f], hyp_at[f]) for f in truth_at if f in hyp_at]
    if not pairs:
        return 0.0, 0, len(truth_at)

    counts = {}
    for p in pairs:
        counts[p] = counts.get(p, 0) + 1
    mapping = best_mapping({t for t, _ in pairs}, {h for _, h in pairs}, counts)
    right = sum(n for (t, h), n in counts.items() if mapping.get(t) == h)
    # Denominator is every speech frame in the truth, not just the ones covered: a frame the run
    # never emitted a block for is a frame it got wrong, and dropping it would reward silence.
    return 100.0 * right / len(truth_at), right, len(truth_at)


def best_mapping(refs, hyps, counts):
    """Exhaustive one-to-one assignment maximising agreement -- the same rule SpeakerAccuracy uses."""
    refs, hyps = sorted(refs), sorted(hyps)
    best, best_score = {}, -1

    def search(i, taken, chosen, score):
        nonlocal best, best_score
        if i == len(refs):
            if score > best_score:
                best_score, best = score, dict(chosen)
            return
        search(i + 1, taken, chosen, score)          # leaving one unmapped is allowed
        for h in hyps:
            if h in taken:
                continue
            chosen[refs[i]] = h
            search(i + 1, taken | {h}, chosen, score + counts.get((refs[i], h), 0))
            del chosen[refs[i]]

    search(0, frozenset(), {}, 0)
    return best


def word_scores(blocks, turns, lang):
    """WER and word-level speaker accuracy -- the two figures the device shows on the row."""
    reference = " ".join(t["text"] for t in turns)
    hypothesis = " ".join(b["text"] for b in blocks)

    ref_w = werlib.normalise(reference, expand_numbers=True, lang=lang)
    hyp_w = werlib.normalise(hypothesis, expand_numbers=True, lang=lang)
    sub, dele, ins, _ = werlib.score(ref_w, hyp_w)
    wer_pct = 100.0 * (sub + dele + ins) / max(len(ref_w), 1)
    coverage = 100.0 * len(hyp_w) / max(len(ref_w), 1)

    # Per-word speaker labels, normalised a turn/block at a time so a speaker change is never read
    # across -- the same reason SpeakerAccuracy.parseReference works per turn.
    ref_tagged = [(w, t["tag"]) for t in turns
                  for w in werlib.normalise(t["text"], expand_numbers=True, lang=lang)]
    hyp_tagged = [(w, b["speakerName"]) for b in blocks
                  for w in werlib.normalise(b["text"], expand_numbers=True, lang=lang)]

    pairs = []
    for tag, i1, i2, j1, j2 in werlib.edit_opcodes([w for w, _ in ref_tagged],
                                                   [w for w, _ in hyp_tagged]):
        if tag != "equal":
            continue
        for k in range(i2 - i1):
            pairs.append((ref_tagged[i1 + k][1], hyp_tagged[j1 + k][1]))

    if not pairs:
        return wer_pct, coverage, None, 0
    counts = {}
    for p in pairs:
        counts[p] = counts.get(p, 0) + 1
    mapping = best_mapping({t for t, _ in pairs}, {h for _, h in pairs}, counts)
    right = sum(n for (t, h), n in counts.items() if mapping.get(t) == h)
    return wer_pct, coverage, 100.0 * right / len(pairs), len(pairs)


def turn_accuracy(blocks, turns, offset, mapping_hint=None):
    """Turns whose majority speaker is right, and the same split by turn length.

    The third denominator, and the one that moves most. Word and frame accuracy both weight a turn
    by how much of it there is, so a conversation's one-word backchannels -- "Mhm.", "Yeah." -- are
    nearly free to get wrong: fourteen of them can be misattributed while the word figure stays
    above 98%. Counting turns weights every speaker change equally, which is how a reader of the
    transcript experiences it.
    """
    buckets = {"<2s": [0, 0], "2-5s": [0, 0], "5-15s": [0, 0], ">15s": [0, 0]}
    pairs = []
    for t in turns:
        best, best_ov = None, 0
        for b in blocks:
            ov = min(t["endSample"], b["endSample"] + offset) - max(t["startSample"], b["startSample"] + offset)
            if ov > best_ov:
                best_ov, best = ov, b["speakerName"]
        pairs.append((t, best))

    counts = {}
    for t, h in pairs:
        if h is not None:
            counts[(t["tag"], h)] = counts.get((t["tag"], h), 0) + 1
    mapping = best_mapping({t["tag"] for t, _ in pairs}, {h for _, h in pairs if h}, counts)

    right = 0
    for t, h in pairs:
        dur = (t["endSample"] - t["startSample"]) / 16000
        key = "<2s" if dur < 2 else "2-5s" if dur < 5 else "5-15s" if dur < 15 else ">15s"
        buckets[key][1] += 1
        if h is not None and mapping.get(t["tag"]) == h:
            right += 1
            buckets[key][0] += 1
    return right, len(pairs), buckets


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db"); ap.add_argument("truth"); ap.add_argument("reference", nargs="?")
    ap.add_argument("--offset", type=int, default=0,
                    help="samples the played audio starts into the recording; see align.py")
    ap.add_argument("--lang", default="en")
    ap.add_argument("--recording", type=int, default=None, help="recordingId; default is the newest")
    ap.add_argument("--pieces", default=None,
                    help="pieces json from a compacted run; block ranges are expanded back to "
                         "recording coordinates before the time-based measures are taken")
    a = ap.parse_args()

    truth = json.loads(pathlib.Path(a.truth).read_text())
    turns = sorted(truth["turns"], key=lambda t: t["startSample"])

    con = sqlite3.connect(a.db); con.row_factory = sqlite3.Row
    rid = a.recording
    if rid is None:
        row = con.execute("SELECT id, name FROM diarized_recordings ORDER BY id DESC LIMIT 1").fetchone()
        if row is None:
            sys.exit("no recordings in that database")
        rid, name = row["id"], row["name"]
    else:
        name = con.execute("SELECT name FROM diarized_recordings WHERE id=?", (rid,)).fetchone()["name"]
    blocks = [dict(r) for r in con.execute(
        "SELECT startSample, endSample, speakerName, text FROM diarized_blocks "
        "WHERE recordingId=? ORDER BY startSample", (rid,))]

    if not blocks:
        sys.exit(f"recording {rid} ({name}) has no blocks -- did the run finish?")

    # A compacted run reports sample ranges in compacted space while the truth timeline is in
    # recording space, so the time-based measures need the blocks expanded back. The word-based
    # ones must NOT use the expanded list: a block spanning a splice becomes several pieces, and
    # every piece carries the whole block's text -- which counted the transcript three times over
    # and reported 350% coverage the first time this ran.
    timed_blocks = blocks
    if a.pieces:
        pieces = json.loads(pathlib.Path(a.pieces).read_text())["pieces"]
        expanded = []
        for b in blocks:
            for p in pieces:
                lo = max(b["startSample"], p["compactedStart"])
                hi = min(b["endSample"], p["compactedStart"] + p["length"])
                if lo >= hi:
                    continue
                start = p["originalStart"] + (lo - p["compactedStart"])
                expanded.append({**b, "startSample": start, "endSample": start + (hi - lo)})
        timed_blocks = sorted(expanded, key=lambda b: b["startSample"])
        print(f"  {len(blocks)} blocks -> {len(timed_blocks)} pieces after expansion")

    wer_pct, coverage, spk_pct, compared = word_scores(blocks, turns, a.lang)
    frame_pct, right, total = frame_accuracy(timed_blocks, turns, a.offset, truth["totalSamples"])
    names = sorted({b["speakerName"] for b in blocks})

    print(f"recording {rid}: {name}")
    print(f"  blocks            {len(blocks)}")
    print(f"  speakers found    {len(names)}  {names}")
    print(f"  coverage          {coverage:.1f}%")
    print(f"  WER (normalised)  {wer_pct:.1f}%")
    print(f"  speaker accuracy  {spk_pct:.1f}%  (over {compared} agreed words)" if spk_pct is not None
          else "  speaker accuracy  not measurable")
    print(f"  frame accuracy    {frame_pct:.1f}%  ({right}/{total} speech frames)")
    tr, tn, buckets = turn_accuracy(timed_blocks, turns, a.offset)
    print(f"  turn accuracy     {100.0*tr/tn:.1f}%  ({tr}/{tn} turns)")
    for k, (r, n) in buckets.items():
        if n:
            print(f"      {k:6s} {r}/{n}")


if __name__ == "__main__":
    main()
