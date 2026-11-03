package app.detent.launcher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Portable setup. App keys include their Android user serial, so they may not exist on another phone. */
data class SettingsData(
    val layout: String,
    val theme: String,
    val side: String,
    val apps: Set<String>,
    val quick: List<QuickSlot>,
    val recent: List<String>,
    val sound: Boolean,
    val haptics: Boolean,
    val dots: Boolean,
    val lockWall: Boolean,
    val ownBar: Boolean,
    val voice: Boolean,
    val webSearch: Boolean,
)

/** JSON format v1. No Android API or saved device state is needed to encode or decode it. */
object SettingsBackup {
    private val json = Json { prettyPrint = true }

    fun toJson(settings: SettingsData): String {
        val doc = buildJsonObject {
            put("version", 1)
            put("layout", settings.layout)
            put("theme", settings.theme)
            put("side", settings.side)
            put("apps", buildJsonArray { settings.apps.sorted().forEach { add(it) } })
            put("quick", buildJsonArray {
                settings.quick.forEach { slot ->
                    add(buildJsonObject {
                        put("key", slot.key)
                        slot.icon?.let { put("icon", it) }
                    })
                }
            })
            put("recent", buildJsonArray { settings.recent.forEach { add(it) } })
            put("sound", settings.sound)
            put("haptics", settings.haptics)
            put("dots", settings.dots)
            put("lockWall", settings.lockWall)
            put("ownBar", settings.ownBar)
            put("voice", settings.voice)
            put("webSearch", settings.webSearch)
        }
        return json.encodeToString(JsonElement.serializer(), doc)
    }

    /**
     * Reads only known fields. Absent fields keep the current value; unknown layouts and themes
     * keep the current choice. App keys that are not installed are dropped from every list.
     * Malformed JSON and unsupported schema versions are rejected before any settings are applied.
     */
    fun fromJson(
        text: String,
        current: SettingsData,
        layouts: Set<String>,
        themes: Set<String>,
        installedApps: Set<String>,
    ): SettingsData {
        val doc = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("settings must be a JSON object")
        val version = (doc["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        require(version == 1) { "unsupported settings version" }

        val apps = doc.strings("apps")?.filter { it in installedApps }?.toSet()
            ?: current.apps.filterTo(linkedSetOf<String>()) { it in installedApps }
        val quick = (doc["quick"] as? JsonArray)?.mapNotNull { entry ->
            val slot = entry as? JsonObject ?: return@mapNotNull null
            val key = slot.text("key") ?: return@mapNotNull null
            if (key !in installedApps) return@mapNotNull null
            QuickSlot(key, slot.text("icon")?.takeIf { it.isNotBlank() })
        }?.distinctBy { it.key }?.take(QUICK_MAX)
            ?: current.quick.filter { it.key in installedApps }.distinctBy { it.key }.take(QUICK_MAX)
        val recent = doc.strings("recent")?.filter { it in installedApps }?.distinct()?.take(10)
            ?: current.recent.filter { it in installedApps }.distinct().take(10)

        return SettingsData(
            layout = doc.text("layout")?.takeIf { it in layouts } ?: current.layout,
            theme = doc.text("theme")?.takeIf { it in themes } ?: current.theme,
            side = doc.text("side")?.takeIf { it == "left" || it == "right" } ?: current.side,
            apps = apps,
            quick = quick,
            recent = recent,
            sound = doc.flag("sound", current.sound),
            haptics = doc.flag("haptics", current.haptics),
            dots = doc.flag("dots", current.dots),
            lockWall = doc.flag("lockWall", current.lockWall),
            ownBar = doc.flag("ownBar", current.ownBar),
            voice = doc.flag("voice", current.voice),
            webSearch = doc.flag("webSearch", current.webSearch),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String, fallback: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fallback

    private fun JsonObject.strings(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.takeIf { value -> value.isNotEmpty() }
        }
}
