package net.jonesie.traveldiary.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMetricsTest {

    private class MapStore : MetricsStore {
        val data = mutableMapOf<String, Any?>()
        override fun getInt(key: String) = data[key] as? Int ?: 0
        override fun getString(key: String) = data[key] as? String
        override fun getBoolean(key: String, default: Boolean) = data[key] as? Boolean ?: default
        override fun put(values: Map<String, Any?>) { data.putAll(values) }
        override fun removeAll(keys: Collection<String>) { keys.forEach { data.remove(it) } }
    }

    private fun metrics(store: MetricsStore = MapStore(), now: Long = 1_700_000_000_000L) = UsageMetrics(store) { now }

    @Test fun `counts each use`() {
        val m = metrics()
        m.record(Metric.SHARED_POST)
        m.record(Metric.SHARED_POST)
        m.record(Metric.PLAYED_TRIP)
        assertEquals(2, m.counts()[Metric.SHARED_POST])
        assertEquals(1, m.counts()[Metric.PLAYED_TRIP])
        assertEquals(0, m.counts()[Metric.SHARED_PDF])
    }

    @Test fun `remembers when counting began`() {
        val m = metrics(now = 1234L)
        assertNull(m.since())
        m.record(Metric.BACKED_UP)
        assertEquals(1234L, m.since())
    }

    @Test fun `counts nothing while switched off, and switching off clears what was counted`() {
        val m = metrics()
        m.record(Metric.LOGGED_INCIDENT)
        m.enabled = false
        assertTrue(m.counts().values.all { it == 0 })
        assertNull(m.since())
        m.record(Metric.LOGGED_INCIDENT)
        assertEquals(0, m.counts()[Metric.LOGGED_INCIDENT])
        m.enabled = true
        m.record(Metric.LOGGED_INCIDENT)
        assertEquals(1, m.counts()[Metric.LOGGED_INCIDENT])
    }

    @Test fun `is on by default and keeps counts out of feedback until asked`() {
        val m = metrics()
        assertTrue(m.enabled)
        assertFalse(m.includeInFeedback)
        m.includeInFeedback = true
        assertTrue(m.includeInFeedback)
    }

    @Test fun `reset clears the counts but keeps the settings`() {
        val m = metrics()
        m.includeInFeedback = true
        m.record(Metric.RESTORED)
        m.reset()
        assertEquals(0, m.counts()[Metric.RESTORED])
        assertNull(m.since())
        assertTrue(m.enabled)
        assertTrue(m.includeInFeedback)
    }

    @Test fun `report lists only what happened`() {
        val text = UsageMetrics.format(mapOf(Metric.SHARED_GPX to 3, Metric.PLAYED_TRIP to 1), since = 1_700_000_000_000L)
        assertTrue(text.startsWith("Feature use (counts only, since "))
        assertTrue(text.contains("- Shared a GPX track: 3"))
        assertTrue(text.contains("- Played a trip: 1"))
        assertFalse(text.contains("PDF"))
    }

    @Test fun `report says so when nothing is counted`() {
        assertEquals("Feature use: nothing counted yet.", UsageMetrics.format(emptyMap(), null))
    }

    @Test fun `keys are unique so counts cannot collide`() {
        assertEquals(Metric.values().size, Metric.values().map { it.key }.toSet().size)
    }
}
