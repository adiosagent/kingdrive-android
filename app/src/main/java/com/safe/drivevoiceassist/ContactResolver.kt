package com.safe.drivevoiceassist

import android.content.Context
import android.provider.ContactsContract
import java.util.Locale

/** Loads the phone's contacts once and finds the best match for a spoken name. */
class ContactResolver(private val context: Context) {

    data class Contact(
        val id: Long,
        val name: String,
        val phones: MutableList<String> = ArrayList(),
        val emails: MutableList<String> = ArrayList()
    ) {
        fun firstName(): String = name.split(" ").firstOrNull { it.isNotBlank() } ?: name
    }

    data class Resolution(val candidates: List<Contact>, val remainder: String, val ambiguous: Boolean)

    private var cache: List<Contact> = emptyList()
    private var loadedAt = 0L

    fun all(): List<Contact> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - loadedAt < 120_000) return cache
        val map = LinkedHashMap<Long, Contact>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1
                ),
                "${ContactsContract.Data.MIMETYPE} IN (?,?)",
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                ),
                null
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: continue
                    val data = c.getString(3) ?: continue
                    val ct = map.getOrPut(id) { Contact(id, name) }
                    if (mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                        if (!ct.phones.contains(data)) ct.phones.add(data)
                    } else {
                        if (!ct.emails.contains(data)) ct.emails.add(data)
                    }
                }
            }
        } catch (e: SecurityException) {
            // READ_CONTACTS not granted yet
        }
        cache = map.values.toList()
        loadedAt = now
        return cache
    }

    fun resolve(query: String): List<Pair<Contact, Int>> =
        all().map { it to score(it.name, query) }
            .filter { it.second >= 45 }
            .sortedByDescending { it.second }

    /**
     * Finds the contact at the start of a phrase like "allan tan on my way" and returns
     * the leftover words ("on my way") as the remainder.
     */
    fun resolveWithRemainder(query: String): Resolution {
        val q = norm(query)
        if (q.isEmpty()) return Resolution(emptyList(), "", false)
        // Chinese (no spaces): try character prefixes, longest first
        if (!q.contains(' ') && q.any { it in '\u4e00'..'\u9fff' }) {
            var best: Resolution? = null
            var bestScore = 0
            val maxN = minOf(8, q.length)
            for (n in maxN downTo 1) {
                val prefix = q.substring(0, n)
                val r = resolve(prefix)
                if (r.isEmpty()) continue
                val top = r[0].second
                if (n <= 1 && top < 100) continue
                val adjusted = top + n * 4
                if (adjusted > bestScore) {
                    bestScore = adjusted
                    val ambiguous = r.size > 1 && r[1].second >= top - 10 && r[1].first.name != r[0].first.name
                    best = Resolution(r.map { it.first }.take(3), q.substring(n).trim(), ambiguous)
                }
            }
            return best ?: Resolution(emptyList(), "", false)
        }
        val words = q.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return Resolution(emptyList(), "", false)
        val maxN = minOf(3, words.size)
        var best: Resolution? = null
        var bestScore = 0
        for (n in maxN downTo 1) {
            val prefix = words.take(n).joinToString(" ")
            val r = resolve(prefix)
            if (r.isEmpty()) continue
            val top = r[0].second
            val adjusted = top + n * 3
            if (adjusted > bestScore) {
                bestScore = adjusted
                val remainder = words.drop(n).joinToString(" ")
                val ambiguous = r.size > 1 && r[1].second >= top - 10 && r[1].first.name != r[0].first.name
                best = Resolution(r.map { it.first }.take(3), remainder, ambiguous)
            }
        }
        return best ?: Resolution(emptyList(), "", false)
    }

    private fun score(name: String, query: String): Int {
        val n = norm(name)
        val q = norm(query)
        if (n.isEmpty() || q.isEmpty()) return 0
        if (n == q) return 100
        if (n.startsWith(q)) return 85
        val nTokens = n.split(" ").filter { it.isNotEmpty() }
        val qTokens = q.split(" ").filter { it.isNotEmpty() }
        if (qTokens.all { qt -> nTokens.any { it == qt } }) return 80
        if (n.contains(q)) return 70
        if (qTokens.all { qt -> nTokens.any { similarity(it, qt) >= 0.8 } }) return 65
        val first = nTokens.firstOrNull()
        if (first != null && similarity(first, q) >= 0.8) return 60
        if (similarity(n, q) >= 0.75) return 55
        if (qTokens.size == 1 && nTokens.any { similarity(it, qTokens[0]) >= 0.72 }) return 45
        return 0
    }

    companion object {
        fun norm(s: String): String =
            s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}'\\s]"), " ").replace(Regex("\\s+"), " ").trim()

        fun similarity(a: String, b: String): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val max = maxOf(a.length, b.length)
            if (max == 0) return 1.0
            return 1.0 - levenshtein(a, b).toDouble() / max
        }

        private fun levenshtein(a: String, b: String): Int {
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                System.arraycopy(cur, 0, prev, 0, cur.size)
            }
            return prev[b.length]
        }

        /** "012-345 6789" with country code 60 → "60123456789" (what wa.me expects). */
        fun toInternational(phone: String, countryCode: String): String {
            val trimmed = phone.trim()
            val digits = trimmed.filter { it.isDigit() }
            val cc = countryCode.filter { it.isDigit() }
            return when {
                trimmed.startsWith("+") -> digits
                digits.startsWith("00") -> digits.drop(2)
                digits.startsWith("0") -> cc + digits.drop(1)
                cc.isNotEmpty() && digits.startsWith(cc) && digits.length > 9 -> digits
                else -> cc + digits
            }
        }
    }
}
