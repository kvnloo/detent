package app.detent.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.detent.launcher.LauncherState
import app.detent.launcher.PALETTES
import app.detent.launcher.Widgets
import kotlin.math.max

@Composable
fun WidgetPanel(widgets: Widgets, s: LauncherState) {
    val pal = PALETTES[s.cfg.theme]
    val idle = s.ob == null && !s.search && s.hear == null && s.open == null && s.menu == null
    if (!widgets.shown) {
        if (idle) Box(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            Txt("widgets +", 13f, pal.muted2,
                Modifier.align(if (s.left) Alignment.TopStart else Alignment.TopEnd)
                    .clickable { widgets.shown = true }.padding(12.dp))
        }
        return
    }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(pal.bg).tap { }.statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt("widgets", 26f, pal.fg)
            Txt("done", 14f, pal.fg, Modifier.clickable { widgets.shown = false }.padding(12.dp))
        }
        Txt("add widget +", 15f, pal.accent, Modifier.clickable { widgets.add() }.padding(vertical = 20.dp))
        if (widgets.ids.isEmpty()) Txt("add widgets from your installed apps", 14f, pal.muted2)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(widgets.ids, key = { it }) { id ->
                val info = widgets.info(id)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Txt(info?.loadLabel(ctx.packageManager) ?: "widget unavailable", 13f, pal.muted2)
                        Txt("remove", 13f, pal.muted2, Modifier.clickable { widgets.remove(id) }.padding(12.dp))
                    }
                    if (info != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val density = LocalDensity.current.density
                        val height = max(100, (info.minHeight / density).toInt()).coerceAtMost(400)
                        val width = maxWidth.value.toInt()
                        key(id) {
                            AndroidView(
                                factory = {
                                    widgets.host.createView(it, id, info).apply {
                                        setPadding(0, 0, 0, 0)
                                        widgets.options(id, width, height)
                                    }
                                },
                                update = { widgets.options(id, width, height) },
                                modifier = Modifier.fillMaxWidth().height(height.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
