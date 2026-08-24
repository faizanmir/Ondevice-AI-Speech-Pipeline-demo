#!/usr/bin/env python3
"""
Word error rate for a transcript against the script that was read aloud.

Reports two numbers on purpose. The raw figure treats "neunzehn Komma vier" and "19,4" as a
mismatch, which counts the model's *correct* spoken-numeral normalisation as an error and so
overstates the damage. The adjusted figure maps the number phrases actually used in the script to
the digit forms a transcriber is expected to produce, leaving only genuine mistakes.
"""
import re
import sys
import difflib
from collections import Counter

# Spoken German numerals -> the digit form a correct transcript would use. Longest first, so
# "zweitausendsechsundzwanzig" is consumed before "zwei".
GERMAN_NUMBERS = [
    ("zweitausendsechsundzwanzig", "2026"),
    ("zweiundzwanzigtausend", "22000"),
    ("zweitausendzweihundertvier", "2204"),
    ("zweihundertsiebzehn", "217"),
    ("zweihundertneunundzwanzig", "229"),
    ("vierhundertsechs", "406"),
    ("vierhundertzwölf", "412"),
    ("zweihundertvier", "204"),
    ("dreihunderteinunddreißig", "331"),
    ("neunundneunzig", "99"),
    ("sechsundsiebzig", "76"),
    ("siebenundzwanzigsten", "27."),
    ("einundzwanzigsten", "21."),
    ("fünfunddreißig", "35"),
    ("vierundsiebzig", "74"),
    ("zweiundsiebzig", "72"),
    ("vierundfünfzig", "54"),
    ("fünfundfünfzig", "55"),
    ("achtundachtzig", "88"),
    ("siebenundzwanzig", "27"),
    ("vierundzwanzig", "24"),
    ("vierunddreißig", "34"),
    ("einunddreißig", "31"),
    ("neunzehnten", "19."),
    ("vierzehnten", "14."),
    ("achtzehnten", "18."),
    ("zweiundzwanzig", "22"),
    ("fünfundvierzig", "45"),
    ("zweiundzwanzig", "22"),
    ("dreiundzwanzig", "23"),
    ("neunundfünfzig", "59"),
    ("fünfzehn", "15"),
    ("vierzehn", "14"),
    ("neunzehn", "19"),
    ("achtzehn", "18"),
    ("siebzehn", "17"),
    ("sechzehn", "16"),
    ("dreizehn", "13"),
    ("zwanzig", "20"),
    ("dreißig", "30"),
    ("vierzig", "40"),
    ("fünfzig", "50"),
    ("sechzig", "60"),
    ("siebzig", "70"),
    ("achtzig", "80"),
    ("neunzig", "90"),
    ("zwölf", "12"),
    ("dritten", "3."),
    ("neunten", "9."),
    ("elf", "11"),
    ("zehn", "10"),
    ("null", "0"),
    ("eins", "1"),
    ("zwei", "2"),
    ("drei", "3"),
    ("vier", "4"),
    ("fünf", "5"),
    ("sechs", "6"),
    ("sieben", "7"),
    ("acht", "8"),
    ("neun", "9"),
]


# Spoken English numerals. Unlike the German table above -- a flat list of the exact phrases one
# script happens to use -- English composes regularly enough to be worth doing properly, so this is
# a small grammar rather than a lookup. It is applied identically to reference and hypothesis, which
# is what makes it safe: a systematic rewrite cannot favour one side, it only removes the
# spelled-versus-digit difference that is not a recognition error in the first place.
ENGLISH_UNITS = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
    "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14,
    "fifteen": 15, "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
}
ENGLISH_TENS = {
    "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50,
    "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
}
# Ordinals, written the way a recogniser writes them ("the 16th of August").
ENGLISH_ORDINALS = {
    "first": "1st", "second": "2nd", "third": "3rd", "fourth": "4th", "fifth": "5th",
    "sixth": "6th", "seventh": "7th", "eighth": "8th", "ninth": "9th", "tenth": "10th",
    "eleventh": "11th", "twelfth": "12th", "thirteenth": "13th", "fourteenth": "14th",
    "fifteenth": "15th", "sixteenth": "16th", "seventeenth": "17th", "eighteenth": "18th",
    "nineteenth": "19th", "twentieth": "20th", "thirtieth": "30th",
}


def expand_english(t):
    """Rewrites spoken English numerals to the digit forms a transcript is expected to produce."""
    # Years first. "twenty twenty six" composes to 46 under any left-to-right accumulator, so it has
    # to be consumed before the general rule ever sees it.
    t = re.sub(r"\btwenty twenty(?: (one|two|three|four|five|six|seven|eight|nine))?\b",
               lambda m: "2020" if m.group(1) is None else f"20{20 + ENGLISH_UNITS[m.group(1)]}", t)

    # Compound ordinals ("twenty ninth") before the bare ones.
    for tens, tv in ENGLISH_TENS.items():
        for ord_word, ord_digit in ENGLISH_ORDINALS.items():
            if ord_word in ("twentieth", "thirtieth"):
                continue
            unit = int(re.match(r"\d+", ord_digit).group())
            if unit > 9:
                continue
            t = re.sub(rf"\b{tens} {ord_word}\b", f"{tv + unit}{ord_digit[-2:]}", t)
    for word, digit in ENGLISH_ORDINALS.items():
        t = re.sub(rf"\b{word}\b", digit, t)

    # Scales. "twenty two thousand" -> 22000, "one hundred and eighty" -> 180.
    t = re.sub(r"\b(\w+(?: \w+)?) thousand\b",
               lambda m: f"{_en_value(m.group(1)) * 1000}" if _en_value(m.group(1)) else m.group(0), t)
    t = re.sub(r"\b(\w+) hundred(?: and (\w+(?: \w+)?))?\b", _en_hundred, t)

    # Bare compounds and singles, longest first so "forty seven" beats "forty".
    for tens, tv in ENGLISH_TENS.items():
        for unit, uv in ENGLISH_UNITS.items():
            if 1 <= uv <= 9:
                t = re.sub(rf"\b{tens} {unit}\b", str(tv + uv), t)
    for word, value in {**ENGLISH_TENS, **ENGLISH_UNITS}.items():
        t = re.sub(rf"\b{word}\b", str(value), t)

    # "seven point nine" -> 7.9, applied repeatedly so clause numbers ("8 point 5 point 1") fold
    # down to 8.5.1 rather than stopping after the first join.
    for _ in range(4):
        t = re.sub(r"(\d)\s+point\s+(\d)", r"\1.\2", t)
    t = re.sub(r"\bdash\b", "-", t)
    t = re.sub(r"\bminus\s+(\d)", r"-\1", t)
    # Join across digits only. A blanket "\s*-\s*" -> "-" also glues an ordinary word onto a
    # following negative number, turning "at -21.2" into "at-21.2" on whichever side happened to
    # write the sign -- an error invented by the scorer rather than made by the recogniser.
    t = re.sub(r"(\d)\s*-\s*(\d)", r"\1-\2", t)
    # Spoken batch codes ("T four two dash seven one six") arrive as separated digits but are written
    # closed up ("T42-716"). Collapsing runs of single digits is the same act as rewriting "nineteen
    # point four" to 19.4: it removes a formatting difference, not a recognition error. Runs only --
    # a lone digit is left alone, and the decimal rule above has already consumed "8 point 5".
    t = re.sub(r"\b\d(?:\s+\d)+\b", lambda m: m.group(0).replace(" ", ""), t)
    return t


def _en_value(phrase):
    """Numeric value of a short spoken-number phrase, or None if it is not one."""
    parts = phrase.split()
    if len(parts) == 1:
        w = parts[0]
        if w.isdigit():
            return int(w)
        return ENGLISH_UNITS.get(w) or ENGLISH_TENS.get(w)
    if len(parts) == 2 and parts[0] in ENGLISH_TENS and parts[1] in ENGLISH_UNITS:
        v = ENGLISH_UNITS[parts[1]]
        if 1 <= v <= 9:
            return ENGLISH_TENS[parts[0]] + v
    return None


def _en_hundred(m):
    head = _en_value(m.group(1))
    if head is None:
        return m.group(0)
    total = head * 100 + (_en_value(m.group(2)) or 0 if m.group(2) else 0)
    return str(total)


def normalise(text, expand_numbers=False, lang="de"):
    t = text.lower()
    # [NON-CONFORMITY], [/ACTION], and speaker tags like [S1]. Digits belong in the class: a
    # reference carrying [S1]/[S2] scored them as the words "s1"/"s2" and charged one deletion
    # each -- about 2.5 points on a 600-word dialogue, for text nobody ever said.
    t = re.sub(r"\[/?[a-z0-9\- ]+\]", " ", t)
    if expand_numbers:
        if lang == "en":
            t = expand_english(t)
        else:
            # "neunzehn komma vier" -> "19,4" before the generic word split.
            for word, digit in GERMAN_NUMBERS:
                t = re.sub(rf"\b{word}\b", digit, t)
            t = re.sub(r"(\d)\s+komma\s+(\d)", r"\1,\2", t)
            t = re.sub(r"\bstrich\b", "-", t)
            t = re.sub(r"(\d)\s+-\s+(\d)", r"\1-\2", t)
    t = t.replace("ß", "ss")
    t = re.sub(r"[^a-zäöü0-9,.\- ]", " ", t)
    t = re.sub(r"\s+", " ", t)
    words = t.split()
    # Strip punctuation from the edges of each token. Standard WER is scored on words, not on
    # commas: counting "versand." vs "versand" as a substitution measures the model's punctuation
    # taste, not whether it heard the word, and it inflates the rate by several points.
    out = []
    for w in words:
        w = w.strip(".,-")
        if w:
            out.append(w)
    return out


def edit_opcodes(a, b, band=64):
    """Minimum-edit-distance alignment, returned in difflib's opcode shape.

    Replaces difflib.SequenceMatcher, which finds longest *contiguous* matching blocks and is not
    an optimal edit script. The difference is invisible on ordinary transcripts and catastrophic on
    one particular input: a reference containing repeated content. Scoring a recording that plays a
    dialogue twice against a reference holding it twice, SequenceMatcher anchored the reference's
    first copy against the hypothesis's *second* pass, charged the rest as 1,899 deletions plus
    1,858 insertions, and reported 103.9% where the true answer was 8.1%.

    Exact, via a band that widens until the answer stops changing. A full n*m table is the textbook
    form but is quadratic in memory, and these references run to thousands of words; the optimal
    path stays near the diagonal for any transcript worth scoring, so a narrow band is almost always
    enough. Correctness does not depend on that guess: the band doubles until two successive widths
    agree, which they cannot do while the path is still being clipped.
    """
    # Common prefix and suffix are matched by any optimal alignment, so drop them before the DP.
    # On a good transcript this is most of the input.
    start = 0
    while start < len(a) and start < len(b) and a[start] == b[start]:
        start += 1
    end = 0
    while end < len(a) - start and end < len(b) - start and a[len(a) - 1 - end] == b[len(b) - 1 - end]:
        end += 1
    core_a, core_b = a[start:len(a) - end], b[start:len(b) - end]

    # Widen until the answer proves itself. Every step the optimal path takes away from the
    # diagonal is one insertion or deletion, so a path costing c can never stray more than c from
    # it: once the banded cost comes back <= the band, no wider band can find anything cheaper.
    # "Two successive widths agreed" was the first rule here and it is not sound -- two too-narrow
    # bands can agree on the same wrong answer, which one pair in 1500 duly did.
    k = max(band, abs(len(core_a) - len(core_b)) + 1)
    while True:
        ops, cost = _banded(core_a, core_b, k)
        if cost <= k or k > len(core_a) + len(core_b):
            break
        k *= 2

    full = ["equal"] * start + ops + ["equal"] * end
    return _group(full, start, end, len(a), len(b))


def _banded(a, b, k):
    """One banded pass. Returns (per-position ops, cost); the cost is exact iff the path fits."""
    n, m = len(a), len(b)
    grow_hi, grow_lo = max(0, m - n), max(0, n - m)
    INF = float("inf")

    def bounds(i):
        return max(0, i - grow_lo - k), min(m, i + grow_hi + k)

    prev_lo, prev_hi = bounds(0)
    prev = [j for j in range(prev_lo, prev_hi + 1)]          # row 0: all insertions
    back = []                                                # one bytearray per row
    for i in range(1, n + 1):
        lo, hi = bounds(i)
        row, marks = [], bytearray(hi - lo + 1)
        for j in range(lo, hi + 1):
            best, mark = INF, 0
            if j - 1 >= lo:                                   # insertion
                v = row[j - 1 - lo] + 1
                if v < best:
                    best, mark = v, 1
            if prev_lo <= j <= prev_hi:                       # deletion
                v = prev[j - prev_lo] + 1
                if v < best:
                    best, mark = v, 2
            if j > 0 and prev_lo <= j - 1 <= prev_hi:         # match or substitution
                v = prev[j - 1 - prev_lo] + (0 if a[i - 1] == b[j - 1] else 1)
                if v <= best:
                    best, mark = v, (3 if a[i - 1] == b[j - 1] else 4)
            if j == 0:
                # First column: i deletions, always. Not derivable from the row above once the band
                # has moved off column 0, and leaving it unreachable loses alignments that begin
                # with a deletion -- one random pair in 600 got a wrong cost without this.
                best, mark = i, 2
            row.append(best)
            marks[j - lo] = mark
        back.append((lo, marks))
        prev, prev_lo, prev_hi = row, lo, hi

    if n == 0:
        return ["insert"] * m, m

    ops, i, j = [], n, m
    while i > 0 or j > 0:
        if i == 0:
            ops.append("insert"); j -= 1; continue
        lo, marks = back[i - 1]
        mark = marks[j - lo] if lo <= j <= lo + len(marks) - 1 else 2
        if mark == 1:
            ops.append("insert"); j -= 1
        elif mark == 2:
            ops.append("delete"); i -= 1
        else:
            ops.append("equal" if mark == 3 else "replace"); i -= 1; j -= 1
    ops.reverse()
    return ops, prev[m - prev_lo]


def _group(ops, *_):
    """Consecutive non-equal operations become one opcode, as difflib reports them."""
    out, i, j, run = [], 0, 0, []
    def flush(run, i, j):
        if not run:
            return i, j
        r = sum(1 for o in run if o in ("replace", "delete"))
        h = sum(1 for o in run if o in ("replace", "insert"))
        tag = "replace" if r and h else ("delete" if r else "insert")
        out.append((tag, i, i + r, j, j + h))
        return i + r, j + h
    for op in ops:
        if op == "equal":
            i, j = flush(run, i, j); run = []
            if out and out[-1][0] == "equal" and out[-1][2] == i:
                tag, i1, i2, j1, j2 = out[-1]
                out[-1] = (tag, i1, i2 + 1, j1, j2 + 1)
            else:
                out.append(("equal", i, i + 1, j, j + 1))
            i, j = i + 1, j + 1
        else:
            run.append(op)
    flush(run, i, j)
    return out


def cer(ref_words, hyp_words):
    """Character error rate over the normalised text -- the same thing `jiwer.cer` computes."""
    r = list(" ".join(ref_words))
    h = list(" ".join(hyp_words))
    if not r:
        return 0.0
    sub, dele, ins, _ = score(r, h)
    return 100 * (sub + dele + ins) / len(r)


def score(ref_words, hyp_words):
    sub = dele = ins = 0
    pairs = []
    for tag, i1, i2, j1, j2 in edit_opcodes(ref_words, hyp_words):
        if tag == "replace":
            # An optimal script pairs off what it can and calls the remainder what it is, rather
            # than charging the whole block as substitutions the way a longest-match diff must.
            r, h = i2 - i1, j2 - j1
            sub += min(r, h)
            dele += max(0, r - h)
            ins += max(0, h - r)
            pairs.append((" ".join(ref_words[i1:i2]), " ".join(hyp_words[j1:j2])))
        elif tag == "delete":
            dele += i2 - i1
            pairs.append((" ".join(ref_words[i1:i2]), "<dropped>"))
        elif tag == "insert":
            ins += j2 - j1
            pairs.append(("<inserted>", " ".join(hyp_words[j1:j2])))
    return sub, dele, ins, pairs


def main(ref_path, hyp_path, lang="de"):
    ref_raw = open(ref_path).read()
    ref_raw = re.sub(r"\[\[[^\]]*\]\]", "", ref_raw)   # say() silence directives
    hyp_raw = open(hyp_path).read()

    for label, expand in (("RAW", False), ("NUMBER-NORMALISED", True)):
        r = normalise(ref_raw, expand, lang)
        h = normalise(hyp_raw, expand, lang)
        sub, dele, ins, pairs = score(r, h)
        err = sub + dele + ins
        coverage = 100 * len(h) / len(r) if r else 0
        print(f"--- {label} ---")
        print(f"reference {len(r)} words | hypothesis {len(h)} words")
        # Coverage before the error rate, per the shared protocol: under ~90% the transcript is
        # truncated and what follows measures how much is missing, not how well it heard. A 30-60%
        # "WER" with low coverage is that failure, and it has been misread here as an engine result.
        print(f"COVERAGE {coverage:.1f}%" + ("   <-- TRUNCATED RUN: fix the run before reading the WER" if coverage < 90 else ""))
        print(f"substitutions {sub}  deletions {dele}  insertions {ins}")
        print(f"WER {100*err/len(r):.1f}%   word accuracy {100-100*err/len(r):.1f}%")
        if expand:
            print(f"CER {cer(r, h):.1f}%   (character level; high WER + low CER means tokenisation, not misrecognition)")
        print()
        if expand:
            print("Most frequent error pairs (reference -> transcript):")
            for (a, b), n in Counter(pairs).most_common(25):
                if a and b:
                    print(f"  {n}x  {a[:48]!r} -> {b[:48]!r}")


if __name__ == "__main__":
    # --lang=en picks the English numeral grammar; German stays the default so the existing
    # documented invocations keep scoring exactly as they did.
    argv = sys.argv[1:]
    lang = "de"
    for a in list(argv):
        if a.startswith("--lang="):
            lang = a.split("=", 1)[1]
            argv.remove(a)
    main(*argv, lang=lang)
