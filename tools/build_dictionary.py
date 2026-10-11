#!/usr/bin/env python3
"""
Builds the bundled n-gram prediction dictionary.

Corpus: Tatoeba English sentence export (CC BY 2.0 FR, attribution in README).
Conversational sentences rather than prose or web text, because that is what people
type on a phone keyboard.

    python tools/build_dictionary.py path/to/eng_sentences.tsv \
        -o app/src/main/res/raw/ngram_dictionary.bin

Output format, version 1, little-endian throughout. Word ids are assigned in ascending
UTF-8 byte order, which is what lets the runtime binary-search a prefix range instead of
scanning the vocabulary.

    header (64 bytes)
        0   char[4]  magic "CKBD"
        4   u16      version
        6   u16      flags (reserved, 0)
        8   u32      wordCount
        12  u32      unigramTopCount
        16  u32      bigramCount
        20  u32      trigramCount
        24  u32      wordOffsetsPos    u32[wordCount + 1], byte offsets into the blob
        28  u32      wordBlobPos       UTF-8 bytes, concatenated, no separators
        32  u32      wordBlobLen
        36  u32      unigramFreqPos    u32[wordCount], indexed by word id
        40  u32      unigramTopPos     u32[unigramTopCount], ids, most frequent first
        44  u32      bigramPos         records of (u32 prev, u32 word, u32 freq)
        48  u32      trigramPos        records of (u32 w1, u32 w2, u32 w3, u32 freq)
        52  u32[3]   reserved

    Bigram records are sorted by (prev, word); trigram records by (w1, w2, w3). Both are
    therefore range-searchable on their context prefix.

The sentence-start marker "<s>" is part of the vocabulary, so the first word of a
sentence gets a real bigram distribution rather than falling back to raw unigrams.
"""

from __future__ import annotations

import argparse
import bz2
import re
import struct
import sys
from collections import Counter, defaultdict

MAGIC = b"CKBD"
VERSION = 1
HEADER_SIZE = 64

# Vocabulary and pruning limits. These trade file size against coverage; the current
# values land around 6-8 MB, which is small enough for the iOS extension's memory
# ceiling once memory-mapped.
MAX_VOCAB = 60_000
MIN_UNIGRAM_COUNT = 3
MIN_BIGRAM_COUNT = 2
MIN_TRIGRAM_COUNT = 3
# Continuations kept per context. Bigrams get a wider fan-out because they are the
# workhorse: a trigram context matches far less often in real typing.
MAX_BIGRAM_CONTINUATIONS = 16
MAX_TRIGRAM_CONTINUATIONS = 6
UNIGRAM_TOP = 128  # cold-start candidates when there is no context

SENTENCE_START = "<s>"

# Words are lowercase letters plus the apostrophe, so "don't" and "we'll" survive
# tokenisation as single tokens. The keyboard re-applies capitalisation at commit time.
TOKEN_RE = re.compile(r"[a-z]+(?:'[a-z]+)*")


def sentences(path: str):
    """Yields the text column of the Tatoeba TSV export (id, lang, text)."""
    opener = bz2.open if path.endswith(".bz2") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                yield parts[2]


def tokenize(text: str) -> list[str]:
    return TOKEN_RE.findall(text.lower())


def count(path: str):
    unigrams: Counter[str] = Counter()
    bigrams: Counter[tuple[str, str]] = Counter()
    trigrams: Counter[tuple[str, str, str]] = Counter()

    total = 0
    for text in sentences(path):
        tokens = tokenize(text)
        if not tokens:
            continue
        total += 1
        if total % 200_000 == 0:
            print(f"  {total:,} sentences", file=sys.stderr)

        padded = [SENTENCE_START, SENTENCE_START] + tokens
        unigrams.update(tokens)
        for i in range(1, len(padded)):
            bigrams[(padded[i - 1], padded[i])] += 1
        for i in range(2, len(padded)):
            trigrams[(padded[i - 2], padded[i - 1], padded[i])] += 1

    print(f"  {total:,} sentences total", file=sys.stderr)
    return unigrams, bigrams, trigrams


def top_continuations(counts, context_len: int, min_count: int, limit: int):
    """Keeps only the most frequent continuations of each context."""
    grouped = defaultdict(list)
    for gram, freq in counts.items():
        if freq < min_count:
            continue
        grouped[gram[:context_len]].append((freq, gram))

    kept = []
    for entries in grouped.values():
        entries.sort(key=lambda pair: (-pair[0], pair[1]))
        kept.extend(entries[:limit])
    return kept


def build(path: str, out_path: str) -> None:
    print("Counting n-grams...", file=sys.stderr)
    unigrams, bigrams, trigrams = count(path)

    # Vocabulary: the most frequent words, plus the sentence-start marker.
    vocab_counts = {w: c for w, c in unigrams.items() if c >= MIN_UNIGRAM_COUNT}
    if len(vocab_counts) > MAX_VOCAB:
        vocab_counts = dict(
            sorted(vocab_counts.items(), key=lambda kv: (-kv[1], kv[0]))[:MAX_VOCAB]
        )
    vocab_counts[SENTENCE_START] = 0

    # Ids in UTF-8 byte order: prefix lookups become a binary search over the id space.
    words = sorted(vocab_counts, key=lambda w: w.encode("utf-8"))
    word_id = {w: i for i, w in enumerate(words)}
    print(f"  vocabulary: {len(words):,} words", file=sys.stderr)

    kept_bigrams = [
        (word_id[a], word_id[b], freq)
        for freq, (a, b) in top_continuations(
            bigrams, 1, MIN_BIGRAM_COUNT, MAX_BIGRAM_CONTINUATIONS
        )
        if a in word_id and b in word_id
    ]
    kept_bigrams.sort()

    kept_trigrams = [
        (word_id[a], word_id[b], word_id[c], freq)
        for freq, (a, b, c) in top_continuations(
            trigrams, 2, MIN_TRIGRAM_COUNT, MAX_TRIGRAM_CONTINUATIONS
        )
        if a in word_id and b in word_id and c in word_id
    ]
    kept_trigrams.sort()

    print(
        f"  bigrams: {len(kept_bigrams):,}  trigrams: {len(kept_trigrams):,}",
        file=sys.stderr,
    )

    top_ids = [
        word_id[w]
        for w, _ in sorted(
            ((w, c) for w, c in vocab_counts.items() if w != SENTENCE_START),
            key=lambda kv: (-kv[1], kv[0]),
        )[:UNIGRAM_TOP]
    ]

    # --- Serialise ---------------------------------------------------------------
    blob = bytearray()
    offsets = []
    for w in words:
        offsets.append(len(blob))
        blob.extend(w.encode("utf-8"))
    offsets.append(len(blob))

    word_offsets_pos = HEADER_SIZE
    word_blob_pos = word_offsets_pos + 4 * len(offsets)
    unigram_freq_pos = word_blob_pos + len(blob)
    # Keep every section 4-byte aligned so the runtime can read ints straight from the
    # mapped buffer without copying.
    unigram_freq_pos += (-unigram_freq_pos) % 4
    unigram_top_pos = unigram_freq_pos + 4 * len(words)
    bigram_pos = unigram_top_pos + 4 * len(top_ids)
    trigram_pos = bigram_pos + 12 * len(kept_bigrams)

    header = struct.pack(
        "<4sHHIIII IIIIIII III".replace(" ", ""),
        MAGIC,
        VERSION,
        0,
        len(words),
        len(top_ids),
        len(kept_bigrams),
        len(kept_trigrams),
        word_offsets_pos,
        word_blob_pos,
        len(blob),
        unigram_freq_pos,
        unigram_top_pos,
        bigram_pos,
        trigram_pos,
        0,
        0,
        0,
    )
    assert len(header) == HEADER_SIZE, len(header)

    with open(out_path, "wb") as out:
        out.write(header)
        out.write(struct.pack(f"<{len(offsets)}I", *offsets))
        out.write(blob)
        out.write(b"\x00" * ((-out.tell()) % 4))
        out.write(struct.pack(f"<{len(words)}I", *(vocab_counts[w] for w in words)))
        out.write(struct.pack(f"<{len(top_ids)}I", *top_ids))
        for prev, nxt, freq in kept_bigrams:
            out.write(struct.pack("<III", prev, nxt, freq))
        for a, b, c, freq in kept_trigrams:
            out.write(struct.pack("<IIII", a, b, c, freq))
        size = out.tell()

    print(f"Wrote {out_path} ({size / 1_048_576:.2f} MB)", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", help="Tatoeba eng_sentences.tsv (.bz2 accepted)")
    parser.add_argument("-o", "--output", required=True)
    args = parser.parse_args()
    build(args.corpus, args.output)


if __name__ == "__main__":
    main()
