import Foundation

/// The bundled n-gram model, in the identical binary format Android ships.
///
/// Memory-mapped rather than parsed. In a keyboard extension this is not an optimisation
/// but a requirement: the extension is killed around 30-60 MB, and parsing 43k words plus
/// ~480k n-gram records into Swift collections would exhaust that before UIKit loads.
/// Mapped pages are file-backed and evictable, so the resident cost is only what was
/// actually touched.
///
/// Format is documented in `tools/build_dictionary.py`. Word ids are assigned in UTF-8
/// byte order, which makes a prefix match a binary search over the id space; the
/// bigram/trigram tables are sorted by context, so continuations are a contiguous range.
final class NgramDictionary {

    struct Candidate {
        let word: String
        let score: Int
    }

    /// Marker for the start of a sentence. Present in the vocabulary, never offered as a word.
    static let sentenceStart = "<s>"

    private let data: Data
    private let wordCount: Int
    private let unigramTopCount: Int
    private let bigramCount: Int
    private let trigramCount: Int

    private let wordOffsetsPos: Int
    private let wordBlobPos: Int
    private let unigramFreqPos: Int
    private let unigramTopPos: Int
    private let bigramPos: Int
    private let trigramPos: Int

    /// Resolved at the end of `init`, once the tables it needs are in place. A `var`
    /// because `id(of:)` needs a fully-initialised instance to run.
    private var sentenceStartId: Int = -1

    private static let magic: UInt32 = 0x4442_4B43  // "CKBD" little-endian
    private static let supportedVersion: UInt16 = 1
    private static let bigramRecord = 12
    private static let trigramRecord = 16

    /// A trigram match is rarer and more specific, so it outranks a merely-common bigram.
    private static let trigramWeight = 4

    init?(url: URL) {
        guard let mapped = try? Data(contentsOf: url, options: .mappedIfSafe) else {
            return nil
        }
        self.data = mapped
        guard mapped.count > 64,
              NgramDictionary.readUInt32(mapped, 0) == NgramDictionary.magic,
              NgramDictionary.readUInt16(mapped, 4) == NgramDictionary.supportedVersion
        else {
            return nil
        }

        wordCount = Int(NgramDictionary.readUInt32(mapped, 8))
        unigramTopCount = Int(NgramDictionary.readUInt32(mapped, 12))
        bigramCount = Int(NgramDictionary.readUInt32(mapped, 16))
        trigramCount = Int(NgramDictionary.readUInt32(mapped, 20))
        wordOffsetsPos = Int(NgramDictionary.readUInt32(mapped, 24))
        wordBlobPos = Int(NgramDictionary.readUInt32(mapped, 28))
        unigramFreqPos = Int(NgramDictionary.readUInt32(mapped, 36))
        unigramTopPos = Int(NgramDictionary.readUInt32(mapped, 40))
        bigramPos = Int(NgramDictionary.readUInt32(mapped, 44))
        trigramPos = Int(NgramDictionary.readUInt32(mapped, 48))

        // The marker is a legitimate continuation of itself in the corpus (the padding
        // produces the pair), so without filtering it reaches the user as a word. This
        // bug shipped on Android and was caught only on a device.
        sentenceStartId = id(of: NgramDictionary.sentenceStart)
    }

    // MARK: - Primitive reads

    private static func readUInt32(_ data: Data, _ offset: Int) -> UInt32 {
        data.withUnsafeBytes { raw in
            raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self).littleEndian
        }
    }

    private static func readUInt16(_ data: Data, _ offset: Int) -> UInt16 {
        data.withUnsafeBytes { raw in
            raw.loadUnaligned(fromByteOffset: offset, as: UInt16.self).littleEndian
        }
    }

    private func int32(_ offset: Int) -> Int {
        Int(NgramDictionary.readUInt32(data, offset))
    }

    // MARK: - Vocabulary

    private func wordStart(_ id: Int) -> Int {
        int32(wordOffsetsPos + id * 4)
    }

    func word(at id: Int) -> String {
        let start = wordBlobPos + wordStart(id)
        let end = wordBlobPos + wordStart(id + 1)
        return String(decoding: data[start..<end], as: UTF8.self)
    }

    /// Byte comparison of the stored word against `needle`.
    /// Under `prefixOnly`, a word running past the needle matches; one running out early
    /// does not, and still sorts before it.
    private func compare(_ id: Int, _ needle: [UInt8], prefixOnly: Bool) -> Int {
        let start = wordBlobPos + wordStart(id)
        let length = wordStart(id + 1) - wordStart(id)
        let limit = max(length, needle.count)
        for i in 0..<limit {
            let a = i < length ? Int(data[start + i]) : -1
            let b = i < needle.count ? Int(needle[i]) : -1
            if a != b {
                if prefixOnly && b == -1 { return 0 }
                return a - b
            }
        }
        return 0
    }

    func id(of word: String) -> Int {
        let needle = Array(word.utf8)
        var low = 0
        var high = wordCount - 1
        while low <= high {
            let mid = (low + high) / 2
            let cmp = compare(mid, needle, prefixOnly: false)
            if cmp < 0 { low = mid + 1 } else if cmp > 0 { high = mid - 1 } else { return mid }
        }
        return -1
    }

    func isKnown(_ word: String) -> Bool { id(of: word) >= 0 }

    private func prefixRange(_ prefix: String) -> Range<Int>? {
        guard !prefix.isEmpty else { return nil }
        let needle = Array(prefix.utf8)

        var low = 0
        var high = wordCount
        while low < high {
            let mid = (low + high) / 2
            if compare(mid, needle, prefixOnly: true) < 0 { low = mid + 1 } else { high = mid }
        }
        let first = low
        guard first < wordCount, compare(first, needle, prefixOnly: true) == 0 else { return nil }

        high = wordCount
        low = first
        while low < high {
            let mid = (low + high) / 2
            if compare(mid, needle, prefixOnly: true) <= 0 { low = mid + 1 } else { high = mid }
        }
        return first..<low
    }

    func frequency(of word: String) -> Int {
        let wordId = id(of: word)
        return wordId >= 0 ? unigramFrequency(wordId) : 0
    }

    private func unigramFrequency(_ id: Int) -> Int {
        int32(unigramFreqPos + id * 4)
    }

    // MARK: - Lookup

    /// Continuations of `context`, optionally restricted to those starting with `prefix`.
    /// Backoff order: trigram, bigram, prefix range, global top words.
    func lookup(context: [String], prefix: String = "", limit: Int = 16) -> [Candidate] {
        let range = prefix.isEmpty ? nil : prefixRange(prefix)
        if !prefix.isEmpty && range == nil { return [] }

        var scores: [Int: Int] = [:]

        if context.count >= 2 {
            let w1 = id(of: context[context.count - 2])
            let w2 = id(of: context[context.count - 1])
            if w1 >= 0 && w2 >= 0 { collectTrigrams(w1, w2, range, &scores) }
        }
        if scores.count < limit, let last = context.last {
            let prev = id(of: last)
            if prev >= 0 { collectBigrams(prev, range, &scores) }
        }
        if scores.count < limit, let range {
            for id in range.sorted(by: { unigramFrequency($0) > unigramFrequency($1) })
                .prefix(limit - scores.count) where scores[id] == nil {
                scores[id] = unigramFrequency(id)
            }
        }
        if scores.isEmpty && prefix.isEmpty {
            for i in 0..<min(unigramTopCount, limit) {
                let id = int32(unigramTopPos + i * 4)
                if scores[id] == nil { scores[id] = unigramFrequency(id) }
            }
        }

        return scores.sorted { $0.value > $1.value }
            .prefix(limit)
            .map { Candidate(word: word(at: $0.key), score: $0.value) }
    }

    private func collectTrigrams(
        _ w1: Int, _ w2: Int, _ range: Range<Int>?, _ into: inout [Int: Int]
    ) {
        var index = lowerBound(count: trigramCount, size: NgramDictionary.trigramRecord, base: trigramPos) { pos in
            let a = self.int32(pos)
            return a != w1 ? a - w1 : self.int32(pos + 4) - w2
        }
        while index < trigramCount {
            let pos = trigramPos + index * NgramDictionary.trigramRecord
            if int32(pos) != w1 || int32(pos + 4) != w2 { break }
            let id = int32(pos + 8)
            if id != sentenceStartId, range == nil || range!.contains(id) {
                into[id] = int32(pos + 12) * NgramDictionary.trigramWeight
            }
            index += 1
        }
    }

    private func collectBigrams(_ prev: Int, _ range: Range<Int>?, _ into: inout [Int: Int]) {
        var index = lowerBound(count: bigramCount, size: NgramDictionary.bigramRecord, base: bigramPos) { pos in
            self.int32(pos) - prev
        }
        while index < bigramCount {
            let pos = bigramPos + index * NgramDictionary.bigramRecord
            if int32(pos) != prev { break }
            let id = int32(pos + 4)
            if id != sentenceStartId, range == nil || range!.contains(id), into[id] == nil {
                into[id] = int32(pos + 8)
            }
            index += 1
        }
    }

    private func lowerBound(
        count: Int, size: Int, base: Int, compare: (Int) -> Int
    ) -> Int {
        var low = 0
        var high = count
        while low < high {
            let mid = (low + high) / 2
            if compare(base + mid * size) < 0 { low = mid + 1 } else { high = mid }
        }
        return low
    }
}
