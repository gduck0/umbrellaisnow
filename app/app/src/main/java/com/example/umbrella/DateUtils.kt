package com.example.umbrella

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 서버는 UTC ISO 8601 문자열로 시각을 내려준다. 화면 표시용으로 한국 시간 변환만 모아둔 유틸.
object DateUtils {
    private val seoulZone = ZoneId.of("Asia/Seoul")
    private val displayFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREA)
    private val shortFmt   = DateTimeFormatter.ofPattern("MM.dd HH:mm", Locale.KOREA)

    // "2025-01-15T03:22:11+00:00" → "2025.01.15 12:22"
    fun formatDisplay(isoStr: String?): String {
        if (isoStr.isNullOrBlank()) return "-"
        return try {
            val odt = OffsetDateTime.parse(isoStr)
            odt.atZoneSameInstant(seoulZone).format(displayFmt)
        } catch (e: Exception) {
            isoStr.take(16).replace("T", " ")
        }
    }

    fun formatShort(isoStr: String?): String {
        if (isoStr.isNullOrBlank()) return "-"
        return try {
            val odt = OffsetDateTime.parse(isoStr)
            odt.atZoneSameInstant(seoulZone).format(shortFmt)
        } catch (e: Exception) {
            isoStr.take(16).replace("T", " ")
        }
    }

    fun minutesSinceRental(rentedAtIso: String?): Long {
        if (rentedAtIso.isNullOrBlank()) return Long.MAX_VALUE
        return try {
            val rented = OffsetDateTime.parse(rentedAtIso).toInstant().toEpochMilli()
            (System.currentTimeMillis() - rented) / 60_000
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    // 불량 신고 가능 시간(기본 5분)이 얼마나 남았는지 초 단위로 반환
    fun defectReportSecondsLeft(rentedAtIso: String?, limitMinutes: Int = 5): Long {
        if (rentedAtIso.isNullOrBlank()) return 0
        return try {
            val rented = OffsetDateTime.parse(rentedAtIso).toInstant().toEpochMilli()
            val limitMs = limitMinutes * 60_000L
            val elapsed = System.currentTimeMillis() - rented
            maxOf(0L, (limitMs - elapsed) / 1000)
        } catch (e: Exception) {
            0L
        }
    }

    // 반납 기한까지 남은 시간 → "X시간 Y분 남음" 또는 "기한 초과"
    fun remainingTime(dueIsoStr: String?): String {
        if (dueIsoStr.isNullOrBlank()) return "기한 미정"
        return try {
            val due  = OffsetDateTime.parse(dueIsoStr).toInstant().toEpochMilli()
            val now  = System.currentTimeMillis()
            val diff = due - now
            if (diff <= 0) return "기한 초과"
            val hours   = diff / 3_600_000
            val minutes = (diff % 3_600_000) / 60_000
            "${hours}시간 ${minutes}분 남음"
        } catch (e: Exception) {
            dueIsoStr.take(16).replace("T", " ")
        }
    }
}
