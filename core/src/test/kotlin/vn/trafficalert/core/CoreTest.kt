package vn.trafficalert.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun confirmed(sign: SignClass, t: Long, score: Float = 0.9f) =
        ConfirmedSign(sign, score, BBox(0.4f, 0.3f, 0.5f, 0.4f), t)

    private fun det(sign: SignClass, score: Float = 0.9f, box: BBox = BBox(0.4f, 0.3f, 0.5f, 0.4f)) =
        Detection(sign, score, box)

    // ---- YoloDecoder ----

    @Test fun decoderPicksBestClassAndSuppressesOverlaps() {
        val nc = SignClass.entries.size
        val n = 3
        val out = FloatArray((4 + nc) * n)
        fun put(i: Int, cx: Float, cy: Float, w: Float, h: Float, cls: SignClass, s: Float) {
            out[i] = cx; out[n + i] = cy; out[2 * n + i] = w; out[3 * n + i] = h
            out[(4 + cls.ordinal) * n + i] = s
        }
        put(0, 0.5f, 0.5f, 0.1f, 0.1f, SignClass.SPEED_50, 0.9f)
        put(1, 0.505f, 0.5f, 0.1f, 0.1f, SignClass.SPEED_60, 0.7f) // trùng vị trí, điểm thấp hơn
        put(2, 0.2f, 0.2f, 0.1f, 0.1f, SignClass.NO_OVERTAKING, 0.3f) // dưới ngưỡng
        val result = YoloDecoder().decode(out, n)
        assertEquals(1, result.size)
        assertEquals(SignClass.SPEED_50, result[0].sign)
        assertEquals(0.45f, result[0].box.left, 1e-4f)
        assertEquals(0.55f, result[0].box.bottom, 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decoderRejectsWrongSize() { YoloDecoder().decode(FloatArray(10), 3) }

    // ---- DetectionFilter ----

    @Test fun filterDropsTinySkewedAndLowBoxes() {
        val f = DetectionFilter()
        assertTrue(f.accept(det(SignClass.SPEED_50)))
        assertFalse(f.accept(det(SignClass.SPEED_50, box = BBox(0.4f, 0.3f, 0.41f, 0.31f))))
        assertFalse(f.accept(det(SignClass.SPEED_50, box = BBox(0.4f, 0.3f, 0.43f, 0.4f))))
        assertFalse(f.accept(det(SignClass.SPEED_50, box = BBox(0.4f, 0.85f, 0.5f, 0.95f))))
        assertTrue(f.accept(det(SignClass.SUPPLEMENTARY_PLATE, box = BBox(0.4f, 0.3f, 0.55f, 0.35f))))
    }

    // ---- SignTracker ----

    @Test fun trackerNeedsSeveralHitsAndEmitsOncePerSign() {
        val t = SignTracker()
        val d = listOf(det(SignClass.SPEED_50))
        assertTrue(t.update(d, 0).isEmpty())
        assertTrue(t.update(d, 100).isEmpty())
        assertEquals(1, t.update(d, 200).size)
        // Vẫn thấy cùng cột biển: không phát lại.
        for (ms in 300L..3000L step 100) assertTrue(t.update(d, ms).isEmpty())
        // Biển cùng loại gặp lại sau đó khá lâu: phát lại.
        t.update(emptyList(), 20_000)
        t.update(d, 20_000); t.update(d, 20_100)
        assertEquals(1, t.update(d, 20_200).size)
    }

    @Test fun trackerIgnoresSingleFrameFlicker() {
        val t = SignTracker()
        assertTrue(t.update(listOf(det(SignClass.SPEED_80)), 0).isEmpty())
        assertTrue(t.update(listOf(det(SignClass.SPEED_80)), 5000).isEmpty())
        assertTrue(t.update(listOf(det(SignClass.SPEED_80)), 10_000).isEmpty())
    }

    // ---- AlertEngine ----

    @Test fun announcesNewSpeedLimitOnce() {
        val e = AlertEngine()
        e.onSign(confirmed(SignClass.SPEED_50, 0))
        val said = e.tick(0)
        assertEquals(1, said.size)
        assertTrue(said[0].text.contains("50"))
        assertTrue(e.tick(500).isEmpty())
        assertEquals(50, e.state(500).activeSpeedLimitKmh)
    }

    @Test fun repeatsOnlyWhileSpeedingByDefault() {
        val e = AlertEngine()
        e.onSign(confirmed(SignClass.SPEED_50, 0)); e.tick(0)
        e.onSpeed(45f, 10_000)
        assertTrue(e.tick(10_000).isEmpty())
        e.onSpeed(52f, 20_000) // trong sai số cho phép
        assertTrue(e.tick(20_000).isEmpty())
        e.onSpeed(62f, 30_000)
        val said = e.tick(30_000)
        assertEquals(1, said.size)
        assertTrue(said[0].urgent)
        assertTrue(e.state(30_000).isSpeeding)
        // Chưa đủ khoảng lặp thì chưa nhắc tiếp.
        e.onSpeed(62f, 33_000)
        assertTrue(e.tick(33_000).isEmpty())
        e.onSpeed(62f, 40_000)
        assertEquals(1, e.tick(40_000).size)
    }

    @Test fun acknowledgeStopsRepeatsUntilNextSign() {
        val e = AlertEngine()
        e.onSign(confirmed(SignClass.SPEED_50, 0)); e.tick(0)
        e.acknowledge()
        e.onSpeed(80f, 30_000)
        assertTrue(e.tick(30_000).isEmpty())
        assertTrue(e.state(30_000).speedLimitAcknowledged)
        // Gặp biển mới thì cảnh báo lại.
        e.onSign(confirmed(SignClass.SPEED_50, 60_000))
        assertEquals(1, e.tick(60_000).size)
        e.onSpeed(80f, 70_000)
        assertEquals(1, e.tick(70_000).size)
    }

    @Test fun alwaysModeRepeatsWithoutSpeedUntilAck() {
        val e = AlertEngine(AlertConfig(repeatMode = RepeatMode.ALWAYS_UNTIL_ACK))
        e.onSign(confirmed(SignClass.SPEED_60, 0)); e.tick(0)
        assertEquals(1, e.tick(10_000).size)
        assertEquals(1, e.tick(20_000).size)
        e.acknowledge()
        assertTrue(e.tick(30_000).isEmpty())
    }

    @Test fun staleGpsIsNotTreatedAsSpeeding() {
        val e = AlertEngine()
        e.onSign(confirmed(SignClass.SPEED_50, 0)); e.tick(0)
        e.onSpeed(90f, 1_000)
        assertTrue(e.tick(30_000).isEmpty())
        assertEquals(null, e.state(30_000).currentSpeedKmh)
    }

    @Test fun endSignClearsLimit() {
        val e = AlertEngine()
        e.onSign(confirmed(SignClass.SPEED_50, 0)); e.tick(0)
        e.onSign(confirmed(SignClass.END_SPEED_LIMIT, 5_000))
        assertEquals(1, e.tick(5_000).size)
        assertEquals(null, e.state(5_000).activeSpeedLimitKmh)
        e.onSpeed(90f, 20_000)
        assertTrue(e.tick(20_000).isEmpty())
    }

    @Test fun prohibitionRepeatsLimitedTimes() {
        val e = AlertEngine()
        e.onSign(confirmed(SignClass.NO_OVERTAKING, 0))
        assertEquals(1, e.tick(0).size)
        assertEquals(1, e.tick(10_000).size)
        assertEquals(1, e.tick(20_000).size)
        assertTrue(e.tick(30_000).isEmpty())
        assertTrue(e.tick(40_000).isEmpty())
    }

    @Test fun cloudMessageCanSetLimit() {
        val e = AlertEngine()
        e.onSign(confirmed(SignClass.OTHER_SIGN, 0))
        assertTrue(e.tick(0).isEmpty())
        e.onCloudMessage("Giới hạn 40 từ 6 giờ đến 22 giờ", 40, 2_000)
        assertEquals(1, e.tick(2_000).size)
        assertEquals(40, e.state(2_000).activeSpeedLimitKmh)
    }

    // ---- AckPhraseMatcher ----

    @Test fun recognisesAcknowledgements() {
        for (s in listOf("đã biết", "Đã biết rồi", "ừ biết rồi", "OK", "tôi biết", "da biet", "Được rồi!"))
            assertTrue(s, AckPhraseMatcher.isAcknowledgement(s))
        for (s in listOf("không biết", "chưa biết", "rẽ trái ở ngã tư", "", "book"))
            assertFalse(s, AckPhraseMatcher.isAcknowledgement(s))
    }

    // ---- CloudEscalationPolicy ----

    @Test fun escalationIsRateLimitedAndOnlyForCloudClasses() {
        val p = CloudEscalationPolicy()
        val other = ConfirmedSign(SignClass.OTHER_SIGN, 0.9f, BBox(0.4f, 0.3f, 0.5f, 0.4f), 0)
        assertFalse(p.shouldEscalate(confirmed(SignClass.SPEED_50, 0), 0, online = true))
        assertFalse(p.shouldEscalate(other, 0, online = false))
        assertFalse(p.shouldEscalate(other.copy(score = 0.5f), 0, online = true))
        assertFalse(p.shouldEscalate(other.copy(box = BBox(0.4f, 0.3f, 0.42f, 0.32f)), 0, online = true))
        assertTrue(p.shouldEscalate(other, 0, online = true))
        assertFalse(p.shouldEscalate(other, 5_000, online = true))
        assertTrue(p.shouldEscalate(other, 25_000, online = true))
    }

    @Test fun escalationRespectsHourlyCap() {
        val p = CloudEscalationPolicy(minIntervalMs = 0, maxCallsPerHour = 3)
        val other = confirmed(SignClass.OTHER_SIGN, 0)
        var t = 0L
        repeat(3) { assertTrue(p.shouldEscalate(other, t, true)); t += 1000 }
        assertFalse(p.shouldEscalate(other, t, true))
        assertTrue(p.shouldEscalate(other, t + 3_600_000, true))
    }

    @Test fun lateOrUnsureCloudResultsAreNotSpoken() {
        val p = CloudEscalationPolicy()
        val good = CloudSignResult(true, "Cấm đỗ xe ngày lẻ", null, 0.9f)
        assertTrue(p.shouldSpeak(good, 0, 3_000))
        assertFalse(p.shouldSpeak(good, 0, 9_000))
        assertFalse(p.shouldSpeak(good.copy(confidence = 0.4f), 0, 3_000))
        assertFalse(p.shouldSpeak(good.copy(isTrafficSign = false), 0, 3_000))
    }

    @Test fun changingConfigKeepsActiveLimit() {
        val e = AlertEngine()
        e.onSign(confirmed(SignClass.SPEED_60, 0)); e.tick(0)
        e.config = AlertConfig(repeatMode = RepeatMode.ALWAYS_UNTIL_ACK, repeatIntervalMs = 5_000)
        assertEquals(60, e.state(1_000).activeSpeedLimitKmh)
        assertEquals(1, e.tick(5_000).size)
    }
}
