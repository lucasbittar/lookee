package com.lookee.tv.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Decodes every golden file written by the Go core (core/testdata/golden) with unknown
 * keys forbidden, so Go and Kotlin cannot drift apart silently (docs/BRIDGE.md).
 */
class BridgeContractTest {
    private val strict = Json { ignoreUnknownKeys = false }
    private val goldenDir = File("../core/testdata/golden")

    private val models: Map<String, KSerializer<*>> = mapOf(
        "settings" to Settings.serializer(),
        "remote_setup" to RemoteSetup.serializer(),
        "roots" to ListSerializer(LibraryRoot.serializer()),
        "volumes" to ListSerializer(StorageVolume.serializer()),
        "scan_progress" to ScanUpdate.serializer(),
        "scan_finished" to ScanUpdate.serializer(),
        "scan_failed" to ScanUpdate.serializer(),
        "titles" to ListSerializer(MediaTitle.serializer()),
        "details" to MediaDetails.serializer(),
        "title_ref" to TitleRef.serializer(),
        "seed_result" to SeedResult.serializer(),
        "browse" to ListSerializer(FolderEntry.serializer()),
        "progress" to ListSerializer(PlaybackProgress.serializer()),
        "sidecars" to ListSerializer(SidecarSubtitle.serializer()),
        "subtitles" to ListSerializer(SubtitleResult.serializer()),
        "path_ref" to PathRef.serializer(),
        "tmdb_results" to ListSerializer(TmdbResult.serializer()),
        "torrent_search" to TorrentSearch.serializer(),
        "stream_stats" to StreamStats.serializer(),
        "logs" to ListSerializer(String.serializer()),
    )

    @Test
    fun everyGoldenFileHasAModel() {
        val files = goldenDir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.toSet()
            ?: fail("golden files not found at ${goldenDir.absolutePath}").let { emptySet() }
        assertEquals("golden files without a Kotlin model (or models without a file)", files, models.keys)
    }

    @Test
    fun everyGoldenFileDecodesStrictlyAndLosesNothing() {
        for ((name, serializer) in models) {
            val text = File(goldenDir, "$name.json").readText()
            val decoded = try {
                strict.decodeFromString(serializer, text)
            } catch (e: Exception) {
                fail("$name.json no longer matches its Kotlin model: ${e.message}")
            }
            // Re-encoding must reproduce every value Go sent, so no field was decoded into the wrong type.
            @Suppress("UNCHECKED_CAST")
            val back = Json { encodeDefaults = true }.encodeToJsonElement(serializer as KSerializer<Any?>, decoded)
            assertSubset(name, strict.parseToJsonElement(text), back)
        }
    }

    @Test
    fun errorCodesAreParsedFromGoMessages() {
        val e = GoCoreBridge.toLookeeException(Exception("lookee:no_tmdb_key: TMDB API key not set"))
        assertEquals(Codes.NO_TMDB_KEY, e.code)
        assertEquals("TMDB API key not set", e.message)
        val plain = GoCoreBridge.toLookeeException(Exception("unknown setting \"x\""))
        assertEquals(null, plain.code)
        assertTrue(plain.message!!.contains("unknown setting"))
    }

    private fun assertSubset(path: String, expected: JsonElement, actual: JsonElement) {
        when (expected) {
            is JsonObject -> {
                val obj = actual as? JsonObject ?: fail("$path: expected an object, got $actual").let { return }
                for ((k, v) in expected) {
                    val a = obj[k] ?: fail("$path.$k was dropped by the Kotlin model").let { return }
                    assertSubset("$path.$k", v, a)
                }
            }
            is JsonArray -> {
                val arr = actual as? JsonArray ?: fail("$path: expected an array, got $actual").let { return }
                assertEquals("$path: array length", expected.size, arr.size)
                expected.forEachIndexed { i, v -> assertSubset("$path[$i]", v, arr[i]) }
            }
            is JsonPrimitive -> {
                val a = actual as? JsonPrimitive ?: fail("$path: expected a value, got $actual").let { return }
                // Numbers compare by value (Go writes 100 where Kotlin's Double writes 100.0).
                val same = expected.content == a.content ||
                    (expected.content.toDoubleOrNull() != null && expected.content.toDoubleOrNull() == a.content.toDoubleOrNull())
                assertTrue("$path: Go sent ${expected.content}, Kotlin kept ${a.content}", same)
            }
        }
    }
}
