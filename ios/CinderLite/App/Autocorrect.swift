import UIKit

/// Autocorrect for Claude's prompt, as on Android. A terminal takes keys raw, so the keyboard can't
/// correct them itself; instead this follows the word being typed and, when a space or Enter ends
/// it, asks iOS's spell checker about it. A misspelled word with a close guess is erased and retyped.
/// Backspace right after a fix puts back what was typed, as keyboards do.
///
/// Only plain words are checked: letters and apostrophes, maybe a capital first, maybe punctuation
/// after ("teh," is "teh"). Anything else (README.md, getProp, ../src) is left alone, as is the rest
/// of a word after a key that moves or edits the line. UITextChecker answers at once, so unlike
/// Android's, nothing ever waits. It has no "recommended fix" flag either: a guess counts only when
/// it's a typo away from the word (one or two edits), so a word it doesn't know isn't swapped for
/// a different one.
///
/// Main thread only. `filter` takes what the keyboard typed and returns what to send instead.
final class Autocorrect {
    private let checker = UITextChecker()
    private let language: String
    private var word = ""
    private var tail = ""   // punctuation right after the word
    private var plain = true   // whether the word so far is one worth checking
    private var undo: String?   // what an immediate backspace types, after a fix

    init() {
        let available = UITextChecker.availableLanguages
        language = Locale.preferredLanguages.lazy.compactMap { pref in
            available.first { $0 == pref.replacingOccurrences(of: "-", with: "_") }
                ?? available.first { $0.hasPrefix(String(pref.prefix(2))) }
        }.first ?? "en_US"
    }

    /// One keyboard input: a character, a backspace, or something longer (a paste, an escape sequence).
    func filter(_ text: String) -> String {
        if text == DEL { return backspace() }
        guard text.count == 1, let c = text.unicodeScalars.first, text.unicodeScalars.count == 1 else {
            forget()
            return text
        }
        return typed(c)
    }

    private func typed(_ c: Unicode.Scalar) -> String {
        undo = nil
        switch c {
        case "\r", "\n", " ":
            defer { reset() }
            guard plain, word.unicodeScalars.count >= 2, let fix = correction(word) else { return String(c) }
            if c == " " { undo = String(repeating: DEL, count: count(fix + tail) + 1) + word + tail }
            return String(repeating: DEL, count: count(word + tail)) + fix + tail + String(c)
        case ".", ",", "!", "?", ";", ":":
            if word.isEmpty { plain = false } else { tail.unicodeScalars.append(c) }
        case _ where c.properties.isAlphabetic || c == "'":
            if !tail.isEmpty || (!word.isEmpty && c.properties.isUppercase) { plain = false }
            word.unicodeScalars.append(c)
        default:
            plain = false
        }
        return String(c)
    }

    private func backspace() -> String {
        if let undo {
            forget()
            return undo
        }
        if !tail.isEmpty { tail.removeLast() } else if !word.isEmpty { word.removeLast() } else { forget() }
        return DEL
    }

    /// Anything else that moves or edits the line (arrows, tab, esc, ctrl, paste, voice): the word
    /// is unknown now.
    func forget() {
        undo = nil
        reset()
        plain = false
    }

    private func reset() {
        word = ""
        tail = ""
        plain = true
    }

    private func correction(_ word: String) -> String? {
        let range = NSRange(location: 0, length: (word as NSString).length)
        guard checker.rangeOfMisspelledWord(in: word, range: range, startingAt: 0, wrap: false, language: language).location != NSNotFound,
              let guess = checker.guesses(forWordRange: range, in: word, language: language)?.first,
              guess != word, guess.allSatisfy({ $0.isLetter || $0 == "'" })
        else { return nil }
        let limit = word.count >= 5 ? 2 : 1
        return editDistance(word.lowercased(), guess.lowercased()) <= limit ? guess : nil
    }

    private func count(_ s: String) -> Int { s.unicodeScalars.count }   // the terminal erases characters
}

private let DEL = "\u{7f}"   // what the terminal's backspace sends

/// Edits (insert, delete, replace, swap two neighbors) between two words.
private func editDistance(_ a: String, _ b: String) -> Int {
    let a = Array(a), b = Array(b)
    if a.isEmpty || b.isEmpty { return max(a.count, b.count) }
    var d = Array(repeating: Array(repeating: 0, count: b.count + 1), count: a.count + 1)
    for i in 0...a.count { d[i][0] = i }
    for j in 0...b.count { d[0][j] = j }
    for i in 1...a.count {
        for j in 1...b.count {
            let cost = a[i - 1] == b[j - 1] ? 0 : 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if i > 1, j > 1, a[i - 1] == b[j - 2], a[i - 2] == b[j - 1] { d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1) }
        }
    }
    return d[a.count][b.count]
}
