package cloud.dopp.homes

import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.ui.DeviceControls
import cloud.dopp.homes.ui.StartDevices
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A favorite's fine controls (owner, 2026-11-03: „Hoch, runter, heller,
 * dunkler, …“), the ones Homes' devices page offers per kind, read off the
 * card Homes sends (`/napi/portal/favorites`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceControlsTest {

    private fun card(json: String): Card = ApiClient.parseCardJson(JSONObject(json))

    private fun labels(c: DeviceControls.Controls) = c.buttons.map { it.label }

    @Test
    fun aDimmableLampHasItsBrightnessAndZeroTurnsItOff() {
        val c = DeviceControls.of(card("""{"entity_id":"light.k","domain":"light","state":"on","brightness":153}"""))
        val s = c.slider!!
        assertEquals(60, s.value)
        assertEquals("light.turn_on" to mapOf<String, Any>("brightness_pct" to 40), s.call(40))
        assertEquals("light.turn_off" to null, s.call(0))
        assertEquals("60 %", s.says())
        // an on/off lamp: its tap only, on or off
        assertTrue(DeviceControls.of(card("""{"entity_id":"light.k","domain":"light","state":"on","supported_color_modes":["onoff"]}""")).none)
        assertTrue(DeviceControls.of(card("""{"entity_id":"light.k","domain":"light","state":"off","supported_color_modes":["onoff"]}""")).none)
    }

    @Test
    fun aShutterHasHochStoppRunterAndItsPosition() {
        val c = DeviceControls.of(card("""{"entity_id":"cover.w","domain":"cover","device_class":"shutter","state":"open","current_position":40,"supported_features":15}"""))
        assertEquals(listOf("Hoch", "Stopp", "Runter"), labels(c))
        assertEquals(listOf("cover.open_cover", "cover.stop_cover", "cover.close_cover"), c.buttons.map { it.service })
        assertEquals("cover.set_cover_position" to mapOf<String, Any>("position" to 70), c.slider!!.call(70))
        // what HA says it cannot, it does not offer: no stop, no position
        val two = DeviceControls.of(card("""{"entity_id":"cover.w","domain":"cover","device_class":"blind","state":"open","current_position":40,"supported_features":3}"""))
        assertEquals(listOf("Hoch", "Runter"), labels(two))
        assertNull(two.slider)
    }

    /** A garage, a door, a gate, a cover HA does not name: no buttons; its tap and the one dialog stay. */
    @Test
    fun aGarageGetsNoControls() {
        for (dc in listOf("garage", "door", "gate", null)) {
            val cls = dc?.let { ""","device_class":"$it"""" } ?: ""
            assertTrue(dc.toString(), DeviceControls.of(card("""{"entity_id":"cover.g","domain":"cover"$cls,"state":"closed","current_position":0,"supported_features":15}""")).none)
        }
    }

    @Test
    fun aHeatingGoesKaelterAndWaermerByItsStepWithinItsBounds() {
        val c = DeviceControls.of(card("""{"entity_id":"climate.b","domain":"climate","state":"heat","current_temperature":21.5,"temperature":22,"target_temp_step":0.5,"min_temp":7,"max_temp":30}"""))
        assertEquals(listOf("Kälter", "Wärmer"), labels(c))
        assertEquals(listOf(21.5, 22.5), c.buttons.map { it.data!!["temperature"] })
        assertTrue(c.buttons.all { it.service == "climate.set_temperature" })
        val top = DeviceControls.of(card("""{"entity_id":"climate.b","domain":"climate","state":"heat","temperature":30,"max_temp":30}"""))
        assertEquals(listOf("Kälter"), labels(top))
        assertTrue(DeviceControls.of(card("""{"entity_id":"climate.b","domain":"climate","state":"off","temperature":22}""")).none)
    }

    @Test
    fun aPlayerPausesOrGoesOnAndStepsItsVolume() {
        val playing = DeviceControls.of(card("""{"entity_id":"media_player.r","domain":"media_player","state":"playing","supported_features":21517}"""))
        assertEquals(listOf("Pause", "Leiser", "Lauter"), labels(playing))
        assertEquals(listOf("media_player.media_pause", "media_player.volume_down", "media_player.volume_up"), playing.buttons.map { it.service })
        val paused = DeviceControls.of(card("""{"entity_id":"media_player.r","domain":"media_player","state":"paused","supported_features":16384}"""))
        assertEquals(listOf("Weiter"), labels(paused))
        assertTrue(DeviceControls.of(card("""{"entity_id":"media_player.r","domain":"media_player","state":"off","supported_features":21517}""")).none)
    }

    /** A lock and a switch have their tap only; a device HA does not reach has nothing. */
    @Test
    fun locksSwitchesAndTheUnreachableHaveNone() {
        assertTrue(DeviceControls.of(card("""{"entity_id":"lock.h","domain":"lock","state":"locked","supported_features":1}""")).none)
        assertTrue(DeviceControls.of(card("""{"entity_id":"switch.k","domain":"switch","state":"on"}""")).none)
        assertTrue(DeviceControls.of(card("""{"entity_id":"light.k","domain":"light","state":"unavailable","brightness":10}""")).none)
    }

    /**
     * Owner, 2026-11-04: a lamp that is off said „aus“ and nothing opened its
     * sheet. A lamp HA can dim has „aus“ as its level, and a step turns it on
     * at that level in one call; a lamp that only switches says a plain „aus“;
     * one out of reach has nothing to tap.
     */
    @Test
    fun anOffDimmerSaysAusAsItsLevel() {
        val off = card("""{"entity_id":"light.k","domain":"light","state":"off","brightness":null,"supported_color_modes":["brightness"]}""")
        val (words, level) = StartDevices.levelLine(off)!!
        assertEquals("", words)
        assertEquals("aus", level.says())
        assertEquals(0, level.value)
        assertEquals("Helligkeit", level.label)
        assertEquals(listOf("25 %", "50 %", "75 %", "100 %"), level.steps.map { it.label })
        assertEquals("light.turn_on" to mapOf<String, Any>("brightness_pct" to 25), level.call(25))
        assertEquals("25 %", level.says(25))
        // a lamp that only switches: a plain „aus“
        val plain = card("""{"entity_id":"light.k","domain":"light","state":"off","supported_color_modes":["onoff"]}""")
        assertNull(StartDevices.levelLine(plain))
        assertEquals("aus", StartDevices.stateText(plain))
        // out of reach, though HA can dim it: never a level
        val gone = card("""{"entity_id":"light.k","domain":"light","state":"unavailable","supported_color_modes":["brightness"]}""")
        assertNull(StartDevices.levelLine(gone))
        assertTrue(DeviceControls.of(gone).none)
        assertEquals("nicht erreichbar", StartDevices.stateText(gone))
        // on: its level after its words
        val on = StartDevices.levelLine(card("""{"entity_id":"light.k","domain":"light","state":"on","brightness":153}"""))!!
        assertEquals("an", on.first)
        assertEquals("60 %", on.second.says())
    }

    /** A shutter all the way down says „geschlossen · 0 %“, so its level is there to tap. */
    @Test
    fun aClosedCoverShowsZeroPercent() {
        val (words, level) = StartDevices.levelLine(
            card("""{"entity_id":"cover.w","domain":"cover","device_class":"shutter","state":"closed","current_position":0,"supported_features":15}"""),
        )!!
        assertEquals("geschlossen", words)
        assertEquals("0 %", StartDevices.percent(level.value))
        assertEquals("offen", StartDevices.levelLine(card("""{"entity_id":"cover.w","domain":"cover","device_class":"shutter","state":"open","current_position":60}"""))!!.first)
        // a garage has no level: its line stays the state
        assertNull(StartDevices.levelLine(card("""{"entity_id":"cover.g","domain":"cover","device_class":"garage","state":"closed","current_position":0,"supported_features":15}""")))
    }

    /** The sheet's quick steps: 25/50/75/100 % for a lamp, Auf/½/Zu for a cover, each exactly one call. */
    @Test
    fun eachQuickStepIsOneCall() {
        val lamp = DeviceControls.of(card("""{"entity_id":"light.k","domain":"light","state":"on","brightness":153}""")).slider!!
        assertEquals(listOf("25 %", "50 %", "75 %", "100 %"), lamp.steps.map { it.label })
        assertEquals(
            listOf(25, 50, 75, 100).map { "light.turn_on" to mapOf<String, Any>("brightness_pct" to it) },
            lamp.steps.map { lamp.call(it.value) },
        )
        val cover = DeviceControls.of(card("""{"entity_id":"cover.w","domain":"cover","device_class":"shutter","state":"open","current_position":40,"supported_features":15}""")).slider!!
        assertEquals(listOf("Auf", "½", "Zu"), cover.steps.map { it.label })
        assertEquals(
            listOf(100, 50, 0).map { "cover.set_cover_position" to mapOf<String, Any>("position" to it) },
            cover.steps.map { cover.call(it.value) },
        )
    }
}
