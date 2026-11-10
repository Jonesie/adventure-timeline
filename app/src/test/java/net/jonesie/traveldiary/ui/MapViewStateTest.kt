package net.jonesie.traveldiary.ui

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in so these toggles can be tested without a real Android Context. */
internal class FakePrefs : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll() = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = REMOVE }
        override fun clear() = apply { pending.clear(); values.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            pending.forEach { (k, v) -> if (v === REMOVE) values.remove(k) else values[k] = v }
            pending.clear()
        }
    }

    private companion object { val REMOVE = Any() }
}

class MapViewStateTest {

    @Test
    fun allTripsMapTogglesDefaultOffAndPersistAcrossRestarts() {
        val prefs = FakePrefs()
        val first = MapViewState(prefs)
        assertFalse(first.showWaypoints)
        assertFalse(first.showPhotos)

        first.showWaypoints = true
        first.showPhotos = true

        // A fresh instance (as happens after the app/ViewModel restarts) must read the saved values back.
        val restarted = MapViewState(prefs)
        assertTrue(restarted.showWaypoints)
        assertTrue(restarted.showPhotos)
    }

    @Test
    fun perTripMapTogglesDefaultToCurrentBehaviourAndPersistAcrossRestarts() {
        val prefs = FakePrefs()
        val first = RouteMapViewState(prefs)
        // Matches the pre-existing rememberSaveable defaults: waypoints off, photos on.
        assertFalse(first.showWaypoints)
        assertTrue(first.showPhotos)

        first.showWaypoints = true
        first.showPhotos = false

        val restarted = RouteMapViewState(prefs)
        assertTrue(restarted.showWaypoints)
        assertFalse(restarted.showPhotos)
    }

    @Test
    fun perTripMapTogglesAreSharedAcrossTrips() {
        // One global preference, same as the all-trips map, so switching trips keeps the filters you chose.
        val prefs = FakePrefs()
        val tripOneView = RouteMapViewState(prefs)
        tripOneView.showWaypoints = true

        val tripTwoView = RouteMapViewState(prefs)
        assertTrue(tripTwoView.showWaypoints)
    }

    @Test
    fun settingTheSameValueDoesNotRewritePreferences() {
        val prefs = FakePrefs()
        val state = MapViewState(prefs)
        state.showWaypoints = false // already the default
        assertEquals(0, prefs.values.size)
    }
}
