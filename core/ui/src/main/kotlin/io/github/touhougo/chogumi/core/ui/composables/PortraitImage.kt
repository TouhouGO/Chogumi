package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.touhougo.chogumi.core.res.R

/**
 * 角色 / 人物 / 用户的图：铺满后**顶对齐**裁切，人物头部在画面上部。全仓唯一画法——
 * 圆形头像 [CircleAvatar]、收藏 / 好友网格 [PersonGridItem]、角色人物页拱形立绘都用它；
 * 外层的尺寸、形状、底色由调用方给。没有图或加载失败画人形图标 [MissingImageIcon]。
 *
 * **按请求关闭 crossfade**（`crossfade(0)` 等价于「无过渡」），否则顶对齐不生效：全局 ImageLoader 启用了
 * `.crossfade(true)`；Coil 3.6.2 在 crossfade 生效时绘制走 `CrossfadePainter` 包装层，节点侧
 * `AbstractContentPainterNode` 的 `calculateScaledSize` 走 `return size` 分支，`Alignment.align(scaledSize, size)`
 * 恒为 (0,0)——`alignment` 完全失效，退化为居中裁剪。实测：crossfade 生效时立绘裁切偏移 238/478 = 0.498
 * （居中、看不到头）；关掉后 0/478 = 0.000（顶对齐、露头）。漏关一处，
 * 竖构图的角色（如菜月昴 200×346）就只剩身子和腿。
 *
 * 代价：这些图失去淡入（全站其余图片仍保留全局 crossfade），换来露头。设计决策（not in M3）：M3 未规定
 * 图片加载过渡与裁剪对齐之间的关系。
 *
 * 依据：Bangumi 人物图实测为竖构图、头部在上部，`ContentScale.Crop` 居中裁会把头切掉。实测宽高比不固定：
 * PersonImageTest 记录的一批 medium 200×323（0.62），菜月昴 large 241×720（0.335）。共性是「竖构图、头在上」。
 */
@Composable
fun PortraitImage(url: String?, description: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(url, context) {
        url?.takeIf { it.isNotBlank() }?.let { ImageRequest.Builder(context).data(it).crossfade(0).build() }
    }
    var failed by remember(url) { mutableStateOf(false) }
    if (request == null || failed) {
        MissingImageIcon(R.drawable.person_24, modifier)
    } else {
        AsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            onError = { failed = true },
            modifier = modifier,
        )
    }
}
