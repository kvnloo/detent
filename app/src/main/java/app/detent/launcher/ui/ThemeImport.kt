package app.detent.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.detent.launcher.ImportedThemes
import app.detent.launcher.LauncherState
import app.detent.launcher.Palette
import app.detent.launcher.ThemeCollection
import app.detent.launcher.ThemeDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit review before downloading a collection. No clipboard reads, automatic apply, or repo scripts. */
@Composable
fun ThemeImportSheet(s: LauncherState, pal: Palette) {
    val initial = s.themeImportLink ?: return
    val ctx = LocalContext.current.applicationContext
    val u = LocalUnits.current
    val scope = rememberCoroutineScope()
    var link by remember(initial) { mutableStateOf(initial) }
    var collection by remember(initial) { mutableStateOf<ThemeCollection?>(null) }
    var selected by remember(initial) { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(initial) { mutableStateOf<String?>(null) }
    var finished by remember(initial) { mutableStateOf(false) }
    var failures by remember(initial) { mutableStateOf(emptyList<String>()) }
    fun scan() {
        if (busy) return
        busy = true; message = "reading collection…"; collection = null; selected = emptySet(); finished = false; failures = emptyList()
        scope.launch {
            try {
                collection = withContext(Dispatchers.IO) { ThemeDownloads.discover(link.trim()) }
                message = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Couldn't read that collection." }
            finally { busy = false }
        }
    }
    Dialog(
        onDismissRequest = { if (!busy) s.closeThemeImport() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(pal.bg).safeDrawingPadding().imePadding().padding(u.dp(24f)),
            verticalArrangement = Arrangement.spacedBy(u.dp(14f)),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Txt("import themes", 26f, pal.fg, weight = FontWeight.Light)
                if (!busy) Chip("close", false, pal, { s.closeThemeImport() })
            }
            Txt("paste a public GitHub repo, folder, image gallery or collection JSON link", 12f, pal.muted2, line = 1.5f)
            BasicTextField(
                value = link,
                onValueChange = { if (!busy) { link = it; collection = null; finished = false; message = null } },
                modifier = Modifier.fillMaxWidth().border(u.dp(1f), pal.rim2, RoundedCornerShape(u.dp(8f))).padding(u.dp(14f)),
                textStyle = textStyle(u, 14f, pal.fg),
                cursorBrush = SolidColor(pal.accent), singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                decorationBox = { inner -> Box { if (link.isEmpty()) Txt("https://github.com/…", 14f, pal.muted); inner() } },
            )
            if (!busy) Row(horizontalArrangement = Arrangement.spacedBy(u.dp(8f))) {
                Chip("find themes", false, pal, { scan() })
                if (finished) Chip("view imported", true, pal, { s.showImportedThemes() })
            }
            val found = collection
            if (found != null) {
                Txt("${found.themes.size} found · ${selected.size} selected", 12f, pal.fg)
                if (!busy && !finished) Row(horizontalArrangement = Arrangement.spacedBy(u.dp(8f))) {
                    Chip("first ${minOf(ImportedThemes.BATCH, found.themes.size)}", false, pal, {
                        selected = found.themes.take(ImportedThemes.BATCH).map { it.id }.toSet()
                    })
                    Chip("clear", false, pal, { selected = emptySet() })
                }
                found.notes.forEach { Txt(it, 11f, pal.muted2, line = 1.4f) }
            }
            if (message != null) Txt(message!!, 12f, pal.accent, line = 1.5f, maxLines = 5)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u.dp(6f))) {
                items(failures) { failure -> Txt(failure, 12f, pal.accent, Modifier.padding(u.dp(12f)), line = 1.4f) }
                if (found != null) items(found.themes, key = { it.id }) { theme ->
                    val checked = theme.id in selected
                    Column(
                        Modifier.fillMaxWidth().background(if (checked) pal.rim else pal.bg, RoundedCornerShape(u.dp(8f)))
                            .toggleable(checked, enabled = !busy && !finished, role = Role.Checkbox) {
                                selected = if (checked) selected - theme.id else if (selected.size < ImportedThemes.BATCH) selected + theme.id else selected
                            }.padding(u.dp(12f)),
                        verticalArrangement = Arrangement.spacedBy(u.dp(5f)),
                    ) {
                        Txt((if (checked) "✓ " else "+ ") + theme.name, 14f, if (checked) pal.accent else pal.fg, maxLines = 2)
                        Txt(when {
                            theme.mobile != null -> "Wallpaper Engine · phone package"
                            theme.workshop != null -> "Workshop link · needs desktop transfer to phone"
                            else -> "wallpaper · colours from image"
                        }, 11f, pal.muted2, maxLines = 2)
                    }
                }
            }
            if (found != null && selected.isNotEmpty() && !busy && !finished) Chip("import ${selected.size}", true, pal, {
                busy = true; message = "importing…"
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            ImportedThemes.install(ctx, found.themes.filter { it.id in selected }) { name ->
                                scope.launch { message = "importing $name…" }
                            }
                        }
                        ImportedThemes.load(ctx)
                        message = "${result.added} imported · ${result.existing} already in library" +
                            if (result.failed.isEmpty()) "" else " · ${result.failed.size} failed (details below)"
                        failures = result.failed
                        finished = true
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "Couldn't save these themes." }
                    finally { busy = false }
                }
            })
            Txt("up to ${ImportedThemes.BATCH} per import · saved offline · Wallpaper Engine still handles applying live packages", 10f, pal.muted2, line = 1.4f)
        }
    }
}
