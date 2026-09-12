package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two ways the console can find out which routes exist.
 *
 * Neither needs a network to be worth testing, and both have bitten already in
 * their Python form: a `folder` sensor reports absolute paths on somebody
 * else's filesystem, and an index file is hand-written, which means it will be
 * hand-written wrongly.
 */
class RouteSyncTest {

    @Test
    fun `a folder sensor reports paths, and only the basenames are any use`() {
        // Real shape: file_list is absolute paths on the Home Assistant box.
        // Keeping them would make the route id carry /config/www, and the fetch
        // would ask /local/treadmill/routes//config/www/... for its file.
        val body = """
            {"entity_id":"sensor.routes","state":"3",
             "attributes":{"file_list":[
               "/config/www/treadmill/routes/the-hill.gpx",
               "/config/www/treadmill/routes/rolling-loop-5k.gpx",
               "/config/www/treadmill/routes/notes.txt",
               "/config/www/treadmill/routes/three-lap-hill.GPX"]}}
        """
        val names = RouteSync.namesFromSensor(body, "sensor.routes")
        // Sorted, and the .txt is gone. Case-insensitive on the extension,
        // because a phone that writes .GPX is not making a mistake.
        assertEquals(listOf("rolling-loop-5k.gpx", "the-hill.gpx",
                            "three-lap-hill.GPX"), names)
    }

    @Test
    fun `a sensor that is not a folder sensor says so`() {
        // The likeliest misconfiguration by a distance: pointing this at some
        // other sensor that happens to exist. "no file_list" is the whole
        // diagnosis, so it belongs in the message.
        try {
            RouteSync.namesFromSensor(
                """{"entity_id":"sensor.kitchen","state":"21.5","attributes":{}}""",
                "sensor.kitchen")
            throw AssertionError("a sensor with no file_list was accepted")
        } catch (e: Exception) {
            assertTrue("unhelpful message: ${e.message}",
                       e.message!!.contains("file_list"))
        }
    }

    @Test
    fun `an index file may be bare names or objects`() {
        // Both shapes, because stride_gpx.py accepts both and an index written
        // for one must work with the other.
        assertEquals(listOf("a.gpx", "b.gpx"),
                     RouteSync.namesFromIndex("""["b.gpx", "a.gpx"]"""))
        assertEquals(listOf("a.gpx", "b.gpx"),
                     RouteSync.namesFromIndex(
                         """[{"file":"b.gpx","note":"hilly"},{"file":"a.gpx"}]"""))
        // Mixed, which nobody would write on purpose and somebody will write.
        assertEquals(listOf("a.gpx", "b.gpx"),
                     RouteSync.namesFromIndex("""["a.gpx", {"file":"b.gpx"}]"""))
    }

    @Test
    fun `an index tolerates what a hand-written file actually contains`() {
        // Leading whitespace and a trailing newline from an editor.
        assertEquals(listOf("a.gpx"), RouteSync.namesFromIndex("\n  [\"a.gpx\"]\n"))
        // Entries that are not routes, silently ignored rather than fatal: an
        // index listing a README should still give you your routes.
        assertEquals(listOf("a.gpx"),
                     RouteSync.namesFromIndex("""["a.gpx", "README.md", ""]"""))
        // Paths, in case somebody copies them out of the folder sensor.
        assertEquals(listOf("a.gpx"),
                     RouteSync.namesFromIndex("""["/config/www/treadmill/routes/a.gpx"]"""))
        // An empty list parses. RouteSync treats the result as a failure and
        // keeps the cache — an empty index is far more often a wrong path than
        // an instruction to delete every route — but that decision is made
        // there, not by throwing here.
        assertEquals(emptyList<String>(), RouteSync.namesFromIndex("[]"))
    }

    @Test
    fun `a broken index is an error rather than an empty list`() {
        // The distinction matters: "no routes" and "I could not read the file"
        // both keep the cache, but only one of them is worth putting in front
        // of somebody as something to go and fix.
        for (bad in listOf("", "not json", "{}", """{"files":["a.gpx"]}""")) {
            try {
                RouteSync.namesFromIndex(bad)
                throw AssertionError("accepted a broken index: \"$bad\"")
            } catch (e: AssertionError) {
                throw e
            } catch (e: Exception) {
                // Expected — any JSON failure is a failure.
            }
        }
    }
}
