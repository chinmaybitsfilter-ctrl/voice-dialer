package com.chinmay.voicedialer

import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlin.math.max
import kotlin.math.min

/** What the user asked for, after we've matched it against the contact list. */
sealed class Resolution {
    data class Dial(val number: String) : Resolution()
    data class Single(val contact: Contact, val numberType: Int?) : Resolution()
    data class Ambiguous(val candidates: List<Contact>, val numberType: Int?) : Resolution()
    data class NoMatch(val heard: String) : Resolution()
}

/**
 * Turns speech like "call mom", "please dial Rahul on mobile", "papa ko call karo"
 * or "call 98765 43210" into a contact / number to call.
 */
object VoiceCommandParser {

    private sealed class Command {
        data class Number(val number: String) : Command()
        data class Name(val query: String, val numberType: Int?) : Command()
    }

    private val digitWords = mapOf(
        "zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
        "nine" to "9", "double" to "", "plus" to "+"
    )

    private val leadingWake = Regex("^(hey|hi|ok|okay)\\s+(assistant|dialer|phone)?\\s*")
    private val leadingCall = Regex(
        "^(please\\s+)?((can|could|will|would) you\\s+)?(please\\s+)?" +
            "(make a call to|give a call to|place a call to|make a call|give a call|call up|" +
            "video call|call|dial|phone|ring|contact)\\s+(to\\s+)?"
    )
    // Hinglish: "mummy ko call karo", "bhai ko phone lagao"
    private val trailingHindi = Regex(
        "\\s+(ko\\s+)?(call|phone|fone|dial)\\s+(karo|kar do|kardo|karna|lagao|laga do|milao|kar)$"
    )
    private val trailingKo = Regex("\\s+ko$")
    private val trailingFiller = Regex("\\s+(please|now|right now|for me|immediately|quickly)$")
    private val numberTypeSuffix = Regex(
        "\\s+((on|at)\\s+)?((his|her|their|the)\\s+)?(mobile|cell|cellphone|home|landline|work|office)(\\s+(number|phone))?$"
    )

    fun normalize(s: String): String =
        s.lowercase()
            .map { if (it.isLetterOrDigit() || it == '+') it else ' ' }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun parse(raw: String): Command? {
        var t = normalize(raw)
        if (t.isEmpty()) return null

        t = t.replace(leadingWake, "")
        t = t.replace(leadingCall, "")
        repeat(2) {
            t = t.replace(trailingFiller, "")
            t = t.replace(trailingHindi, "")
            t = t.replace(trailingKo, "")
        }

        var numberType: Int? = null
        numberTypeSuffix.find(t)?.let { m ->
            val stripped = t.substring(0, m.range.first).trim()
            if (stripped.isNotEmpty()) {
                numberType = when (m.groupValues[5]) {
                    "mobile", "cell", "cellphone" -> Phone.TYPE_MOBILE
                    "home", "landline" -> Phone.TYPE_HOME
                    else -> Phone.TYPE_WORK
                }
                t = stripped
            }
        }
        t = t.trim()
        if (t.isEmpty() || t in setOf("call", "dial", "phone", "ring")) return null

        // Spoken phone number? ("98765 43210", "nine eight seven ...", "+91 ...")
        val tokens = t.split(" ")
        if (tokens.all { tok -> tok.all { it.isDigit() || it == '+' } || tok in digitWords }) {
            val number = tokens.joinToString("") { digitWords[it] ?: it }
            if (number.count { it.isDigit() } >= 3) return Command.Number(number)
        }
        return Command.Name(t, numberType)
    }

    // ---------- fuzzy name matching ----------

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    private fun similarity(a: String, b: String): Double {
        val len = max(a.length, b.length)
        if (len == 0) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / len
    }

    /** 0.0 (no resemblance) .. 1.0 (exact). */
    fun score(candidate: String, query: String): Double {
        val n = normalize(candidate)
        val q = normalize(query)
        if (n.isEmpty() || q.isEmpty()) return 0.0
        if (n == q) return 1.0

        val nNoSpace = n.replace(" ", "")
        val qNoSpace = q.replace(" ", "")
        if (nNoSpace == qNoSpace) return 0.97          // "chin may" vs "Chinmay"
        if (n.startsWith("$q ")) return 0.95           // "rahul" vs "Rahul Sharma"

        val nt = n.split(" ")
        val qt = q.split(" ")
        if (nt.any { it == q }) return 0.92            // "sharma" vs "Rahul Sharma"
        if (qt.all { qtok -> qtok.length >= 2 && nt.any { it.startsWith(qtok) } }) return 0.88
        if (n.contains(q)) return 0.8

        var best = max(similarity(n, q), similarity(nNoSpace, qNoSpace))
        for (tok in nt) best = max(best, similarity(tok, q) * 0.95)
        val perToken = qt.map { qtok -> nt.maxOf { similarity(it, qtok) } }.average() * 0.9
        return max(best, perToken)
    }

    /**
     * @param alternatives speech recognizer hypotheses, best first.
     */
    fun resolve(alternatives: List<String>, contacts: List<Contact>): Resolution {
        val parsed = alternatives.mapNotNull { parse(it) }
        val heard = alternatives.firstOrNull().orEmpty()
        if (parsed.isEmpty()) return Resolution.NoMatch(heard)

        (parsed.first() as? Command.Number)?.let { return Resolution.Dial(it.number) }

        val names = parsed.filterIsInstance<Command.Name>()
        val numberType = names.firstNotNullOfOrNull { it.numberType }

        val ranked = contacts.map { contact ->
            var best = 0.0
            names.forEachIndexed { i, cmd ->
                val weight = if (i == 0) 1.0 else 0.97   // trust the top hypothesis a little more
                val s = max(
                    score(contact.name, cmd.query),
                    contact.nickname?.let { score(it, cmd.query) } ?: 0.0
                ) * weight
                best = max(best, s)
            }
            contact to best
        }.filter { it.second >= 0.6 }.sortedByDescending { it.second }

        if (ranked.isEmpty()) return Resolution.NoMatch(names.first().query)

        val top = ranked.first().second
        val close = ranked.filter { it.second >= top - 0.04 }.map { it.first }
        return if (close.size == 1 || (top >= 0.999 && ranked.count { it.second >= 0.999 } == 1)) {
            Resolution.Single(ranked.first().first, numberType)
        } else {
            Resolution.Ambiguous(close.take(6), numberType)
        }
    }
}
