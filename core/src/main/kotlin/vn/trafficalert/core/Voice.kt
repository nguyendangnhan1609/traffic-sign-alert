package vn.trafficalert.core

import java.text.Normalizer

/** Nhận ra câu xác nhận của tài xế trong kết quả nhận dạng giọng nói. */
object AckPhraseMatcher {
    private val phrases = listOf(
        "da biet", "biet roi", "toi biet", "da hieu", "hieu roi", "da ro", "ro roi",
        "ok", "oke", "okay", "duoc roi",
    )

    fun normalize(text: String): String {
        val lower = text.lowercase().replace('đ', 'd')
        val noMarks = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noMarks.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun isAcknowledgement(recognized: String): Boolean {
        val n = " " + normalize(recognized) + " "
        // "không biết", "chưa biết" không phải là xác nhận.
        if (n.contains(" khong ") || n.contains(" chua ")) return false
        return phrases.any { n.contains(" $it ") }
    }
}
