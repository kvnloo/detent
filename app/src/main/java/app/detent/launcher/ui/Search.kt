package app.detent.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import app.detent.launcher.AppEntry
import app.detent.launcher.Ease
import app.detent.launcher.Gather
import app.detent.launcher.LauncherState
import app.detent.launcher.Palette
import app.detent.launcher.QuickIcons
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun SearchSheet(s: LauncherState, pal: Palette) {
    val u = LocalUnits.current
    val move = (40 * u.k).toInt()
    AnimatedVisibility(
        s.search,
        enter = fadeIn(tween(300)) + slideInVertically(tween(400, easing = Ease)) { move },
        exit = fadeOut(tween(300)) + slideOutVertically(tween(400, easing = Ease)) { move },
    ) {
        val focus = remember { FocusRequester() }
        val kb = LocalSoftwareKeyboardController.current
        val iconFor = s.pickFor
        // The field takes the keyboard. The icons of the quick button need no keyboard.
        LaunchedEffect(iconFor == null) {
            if (iconFor != null) {
                kb?.hide()
                return@LaunchedEffect
            }
            delay(80)
            runCatching { focus.requestFocus() }
            kb?.show()
        }
        val q = s.query
        val results = remember(q, s.repo.apps) { s.matches(q) }
        val recent = s.recentApps
        Column(Modifier.fillMaxSize().background(pal.sheet).tap { }.imePadding()) {
            Spacer(Modifier.height(u.dp(max(60f, u.top + 20f))))
            if (iconFor != null) {
                IconStep(s, pal, iconFor)
                return@Column
            }
            Row(
                Modifier.padding(start = u.dp(28f), end = u.dp(18f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u.dp(10f)),
            ) {
                BasicTextField(
                    value = q,
                    onValueChange = { s.query = it },
                    modifier = Modifier.weight(1f).height(u.dp(52f)).focusRequester(focus)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                                s.closeSearch()
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    textStyle = textStyle(u, 26f, pal.fg, FontWeight.Light, spacing = -.03f),
                    cursorBrush = SolidColor(pal.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { s.searchEnter() }),
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                val y = size.height - u.k / 2
                                drawLine(pal.rim2, Offset(0f, y), Offset(size.width, y), strokeWidth = u.k)
                            },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            val empty = when {
                                s.gather == Gather.Folder -> "find apps"
                                s.gather == Gather.Swipe -> "pick the swipe app"
                                s.pick -> "pick a quick app"
                                s.web -> "search the web"
                                else -> "search apps"
                            }
                            if (q.isEmpty()) Txt(empty, 26f, pal.muted, weight = FontWeight.Light, spacing = -.03f, maxLines = 1)
                            inner()
                        }
                    },
                )
                Box(Modifier.height(u.dp(44f)).tap { s.closeSearch() }.padding(horizontal = u.dp(10f)), contentAlignment = Alignment.Center) {
                    Txt("cancel", 13f, pal.muted2)
                }
            }
            if (s.gather == Gather.Folder) FolderHead(s, pal)
            val n = s.dialApps.size
            val count = when {
                s.gather == Gather.Folder -> "tap apps to put them in the folder · the first one opens on a tap"
                s.gather == Gather.Swipe -> "a sideways swipe on ${s.gatherLabel} opens the app you tap"
                s.web -> "your browser or search app shows the results"
                q.isNotBlank() -> "${results.size} " + if (results.size == 1) "match" else "matches"
                s.pick -> "tap an app for the quick button"
                else -> "${results.size} apps · $n on dial"
            }
            Row(
                Modifier.fillMaxWidth().padding(start = u.dp(28f), top = u.dp(16f), end = u.dp(18f), bottom = u.dp(8f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(count, 11f, pal.muted, Modifier.weight(1f), spacing = .06f, maxLines = 2)
                if (s.gather == Gather.Swipe && s.gatherSwipe != null) {
                    Box(Modifier.height(u.dp(32f)).tap { s.setSwipe(null) }.padding(horizontal = u.dp(10f)), contentAlignment = Alignment.Center) {
                        Txt("no swipe", 11f, pal.accent, spacing = .04f)
                    }
                }
            }
            if (s.web) {
                if (q.isNotBlank()) WebRow(s, pal, q.trim())
                return@Column
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = u.dp(12f), end = u.dp(12f), bottom = u.dp(48f)),
            ) {
                val picked = if (s.gather == Gather.Folder) s.gatherKeys.mapNotNull { k -> s.repo.apps.firstOrNull { it.key == k } } else emptyList()
                if (picked.isNotEmpty() && q.isBlank()) {
                    item(key = "picked") { Section("in the folder", pal) }
                    itemsIndexed(picked, key = { _, a -> "picked " + a.key }) { _, app ->
                        ResultRow(s, pal, app, highlight = false)
                    }
                    item(key = "all") { Section("all apps", pal) }
                } else if (q.isBlank() && recent.isNotEmpty() && s.gather == null) {
                    item(key = "recent") { Section("recent", pal, if (s.pick) null else "clear") { s.clearRecent() } }
                    itemsIndexed(recent, key = { _, a -> "recent " + a.key }) { _, app ->
                        ResultRow(s, pal, app, highlight = false, recent = true)
                    }
                    item(key = "all") { Section("all apps", pal) }
                }
                itemsIndexed(results, key = { _, a -> a.key }) { i, app ->
                    ResultRow(s, pal, app, highlight = q.isNotBlank() && i == 0)
                }
            }
        }
    }
}

/** The one row of the web search: the words, and a tap that hands them to the browser. */
@Composable
private fun WebRow(s: LauncherState, pal: Palette, words: String) {
    val u = LocalUnits.current
    Row(
        Modifier.padding(horizontal = u.dp(12f)).fillMaxWidth().height(u.dp(48f)).clip(RoundedCornerShape(u.dp(10f)))
            .background(pal.hover).tap { s.searchWeb() }.padding(horizontal = u.dp(16f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u.dp(12f)),
    ) {
        Canvas(Modifier.size(u.dp(16f))) { drawQuickIcon("globe", pal.muted2, Offset.Zero, size.width, 1.5f * u.k) }
        Txt(words, 16f, pal.fg, Modifier.weight(1f), maxLines = 1)
        Txt("search", 11f, pal.accent, spacing = .04f)
    }
}

/** A section label in the list, with an optional text button at the end. */
@Composable
private fun Section(text: String, pal: Palette, action: String? = null, onAction: () -> Unit = {}) {
    val u = LocalUnits.current
    Row(Modifier.fillMaxWidth().height(u.dp(40f)).padding(top = u.dp(8f)), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, 11f, pal.muted2, Modifier.weight(1f).padding(start = u.dp(16f)), spacing = .06f, maxLines = 1)
        if (action != null) {
            Box(Modifier.fillMaxHeight().tap(onClick = onAction).padding(horizontal = u.dp(16f)), contentAlignment = Alignment.Center) {
                Txt(action, 11f, pal.muted, spacing = .06f)
            }
        }
    }
}

@Composable
private fun ResultRow(s: LauncherState, pal: Palette, app: AppEntry, highlight: Boolean, recent: Boolean = false) {
    val u = LocalUnits.current
    var centre by remember { mutableFloatStateOf(0f) }
    val place = s.placeOf(app.key)
    Row(
        Modifier.fillMaxWidth().height(u.dp(48f)).clip(RoundedCornerShape(u.dp(10f)))
            .background(if (highlight) pal.hover else Color.Transparent)
            .onGloballyPositioned { centre = it.positionInRoot().y + it.size.height / 2f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .tap { s.resultTap(app, centre / u.k) }
                .padding(horizontal = u.dp(16f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u.dp(10f)),
        ) {
            Txt(app.label, 16f, pal.fg, Modifier.weight(1f, fill = false), maxLines = 1)
            if (s.hasDot(app)) Box(Modifier.size(u.dp(5f)).clip(CircleShape).background(pal.accent))
        }
        if (s.gather == Gather.Folder) {
            val i = s.gatherKeys.indexOf(app.key)
            Box(
                Modifier.size(u.dp(44f)).tap { s.resultTap(app, 0f) },
                contentAlignment = Alignment.Center,
            ) {
                val shape = CircleShape
                Box(
                    Modifier.size(u.dp(22f)).clip(shape)
                        .background(if (i >= 0) pal.accent else Color.Transparent)
                        .border(u.dp(1f), if (i >= 0) pal.accent else pal.rim2, shape),
                    contentAlignment = Alignment.Center,
                ) { if (i >= 0) Txt("${i + 1}", 11f, pal.onAccent, weight = FontWeight.Medium) }
            }
        } else if (s.gather == Gather.Swipe) {
            if (s.gatherSwipe?.key == app.key) Txt("swipe", 11f, pal.accent, Modifier.padding(horizontal = u.dp(16f)), spacing = .04f)
        } else if (s.pick) {
            if (s.quick.any { it.key == app.key }) Txt("quick app", 11f, pal.muted, Modifier.padding(horizontal = u.dp(16f)), spacing = .04f)
        } else if (recent) {
            Box(Modifier.size(u.dp(44f)).tap { s.removeRecent(app) }, contentAlignment = Alignment.Center) {
                Cross(pal.muted, u.dp(10f))
            }
        } else if (place != null) {
            Txt(place, 11f, pal.muted, Modifier.padding(horizontal = u.dp(16f)), spacing = .04f, maxLines = 1)
        } else {
            Box(
                Modifier.height(u.dp(44f)).tap { s.addToDial(app) }.padding(start = u.dp(4f), end = u.dp(8f)),
                contentAlignment = Alignment.Center,
            ) {
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier.height(u.dp(28f)).clip(shape).border(u.dp(1f), pal.rim2, shape).padding(horizontal = u.dp(12f)),
                    contentAlignment = Alignment.Center,
                ) { Txt("+ dial", 11f, pal.fg, maxLines = 1) }
            }
        }
    }
}

/** The folder sheet: the folder name and done. */
@Composable
private fun FolderHead(s: LauncherState, pal: Palette) {
    val u = LocalUnits.current
    Row(
        Modifier.padding(start = u.dp(28f), top = u.dp(10f), end = u.dp(18f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u.dp(10f)),
    ) {
        Txt("name", 11f, pal.muted, spacing = .06f)
        BasicTextField(
            value = s.folderName,
            onValueChange = { s.folderName = it.take(17) },
            modifier = Modifier.weight(1f).height(u.dp(40f)),
            singleLine = true,
            textStyle = textStyle(u, 18f, pal.fg, FontWeight.Normal),
            cursorBrush = SolidColor(pal.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { s.saveFolder() }),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val y = size.height - u.k / 2
                        drawLine(pal.rim2, Offset(0f, y), Offset(size.width, y), strokeWidth = u.k)
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (s.folderName.isEmpty()) Txt("messaging, social…", 18f, pal.muted, maxLines = 1)
                    inner()
                }
            },
        )
        val shape = RoundedCornerShape(50)
        Box(
            Modifier.height(u.dp(36f)).clip(shape).background(pal.accent).tap { s.saveFolder() }.padding(horizontal = u.dp(16f)),
            contentAlignment = Alignment.Center,
        ) { Txt("done", 12f, pal.onAccent, weight = FontWeight.Medium) }
    }
}

/** A small cross that removes a row. */
@Composable
private fun Cross(color: Color, size: Dp) {
    val u = LocalUnits.current
    Canvas(Modifier.size(size)) {
        val w = u.k * 1.2f
        drawLine(color, Offset(0f, 0f), Offset(this.size.width, this.size.height), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(this.size.width, 0f), Offset(0f, this.size.height), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** The second step of the quick app pick: an icon in place of the name, or the name. */
@Composable
private fun IconStep(s: LauncherState, pal: Palette, app: AppEntry) {
    val u = LocalUnits.current
    val mark = s.iconFor(app)
    val solo = s.soloPick(app)
    Row(
        Modifier.padding(start = u.dp(28f), end = u.dp(18f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u.dp(10f)),
    ) {
        Box(
            Modifier.weight(1f).height(u.dp(52f)).drawBehind {
                val y = size.height - u.k / 2
                drawLine(pal.rim2, Offset(0f, y), Offset(size.width, y), strokeWidth = u.k)
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            val nameMark = s.nameMark(app)
            if (nameMark != null) {
                Canvas(Modifier.size(u.dp(26f))) { drawQuickIcon(nameMark, pal.fg, Offset.Zero, size.width, 1.5f * u.k) }
            } else {
                Txt(app.display, 26f, pal.fg, weight = FontWeight.Light, spacing = -.03f, maxLines = 1)
            }
        }
        Box(Modifier.height(u.dp(44f)).tap { s.unpick() }.padding(horizontal = u.dp(10f)), contentAlignment = Alignment.Center) {
            Txt("back", 13f, pal.muted2)
        }
    }
    Txt(
        if (app.kids != null) "pick an icon for the folder" else "pick an icon for the quick button", 11f, pal.muted,
        Modifier.padding(start = u.dp(28f), top = u.dp(16f), end = u.dp(18f), bottom = u.dp(16f)),
        spacing = .06f, maxLines = 1,
    )
    // The name comes first. With more than one quick app, the button shows the first letter of the name.
    val tiles = listOf<String?>(null) + QuickIcons.names
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = u.dp(24f), end = u.dp(24f), bottom = u.dp(48f)),
        verticalArrangement = Arrangement.spacedBy(u.dp(10f)),
    ) {
        tiles.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(u.dp(10f))) {
                row.forEach { name ->
                    IconTile(s, pal, name, if (solo) "name" else app.initial, name == mark, Modifier.weight(1f))
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun IconTile(s: LauncherState, pal: Palette, name: String?, text: String, marked: Boolean, modifier: Modifier) {
    val u = LocalUnits.current
    val shape = RoundedCornerShape(u.dp(14f))
    val color = if (marked) pal.accent else pal.fg
    Box(
        modifier.aspectRatio(1f).clip(shape).background(if (marked) pal.hover else Color.Transparent)
            .border(u.dp(1f), if (marked) pal.accent else pal.rim2, shape)
            .semantics { contentDescription = name ?: "name" }
            .tap { s.setQuick(name) },
        contentAlignment = Alignment.Center,
    ) {
        if (name == null) {
            // The name, or the first letter of it, as the button shows it.
            if (text.length > 1) Txt(text, 11f, color, spacing = .02f) else Txt(text, 17f, color, line = 1f)
        } else {
            Canvas(Modifier.size(u.dp(22f))) {
                drawQuickIcon(name, color, Offset.Zero, size.width, 1.5f * u.k)
            }
        }
    }
}
