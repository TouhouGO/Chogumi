package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpisodeCellKind
import io.github.touhougo.chogumi.core.model.WatchChange
import org.junit.Assert.assertEquals
import org.junit.Test

/** 静止格子与预览的视觉优先级，演示与真实组件共用。 */
class TileStyleTest {
    @Test
    fun `按住优先变方_下一集为圆_连段与强调按种类`() {
        val shapes = Shapes()
        for (kind in EpisodeCellKind.entries) {
            assertEquals(shapes.small, shapes.episodeTileShape(kind, NoJoin, pressed = true))
            assertEquals(kind == EpisodeCellKind.CURRENT || kind == EpisodeCellKind.NEXT, kind.numberEmphasized)
        }
        assertEquals(CircleShape, shapes.episodeTileShape(EpisodeCellKind.NEXT, NoJoin, pressed = false))
        val join = TileJoin(start = true, end = false)
        assertEquals(shapes.tileShape(join), shapes.episodeTileShape(EpisodeCellKind.WATCHED, join, pressed = false))
    }

    @Test
    fun `点中的实心格描边可见_下一集优先于预览_普通邻居保留原描边`() {
        val colors = lightColorScheme()
        assertEquals(TileBorder(3.dp, colors.onPrimary), colors.tileBorder(EpisodeCellKind.CURRENT, WatchChange.FILL, target = true))
        assertEquals(TileBorder(2.dp, colors.primary), colors.tileBorder(EpisodeCellKind.NEXT, WatchChange.FILL, target = false))
        assertEquals(TileBorder(1.dp, colors.primary.copy(alpha = 0.35f)), colors.tileBorder(EpisodeCellKind.AIRED, WatchChange.FILL, target = false))
        assertEquals(TileBorder(1.dp, colors.outlineVariant), colors.tileBorder(EpisodeCellKind.AIRED, null, target = false))
        assertEquals(TileBorder(1.dp, colors.outlineVariant.copy(alpha = 0f)), colors.tileBorder(EpisodeCellKind.WATCHED, null, target = false))
    }
}
