package com.example.filebridge

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FileBridge 设计系统。
 *
 * 参考 GitHub 上 anthropics/skills 的 frontend-design 规约 + Material 3 形状刻度：
 *  - 颜色字面量只出现在这个文件里，界面只引用 token
 *  - 先定 4~6 个命名色、字阶、形状，再写界面（plan → build → critique）
 *  - 避开默认审美：不用奶油底 + 衬线、不用全大写眉标、不用「所有元素同一个圆角」的 SaaS 卡片套装
 *  - 单一记忆点：路径面包屑是界面的主视觉，文件本身是主角，按钮收进动作面板
 *
 * 调性：工程感但温和，像一只整理好的抽屉。深墨蓝文字 + 冷纸白底 + 单一青绿强调。
 * 不打包自定义字体文件（会显著增大 APK），改用系统字族 + 明确字阶/字重建立层级。
 */
object FB {
    val Ink = Color(0xFF16212B)          // 主文字、深色图标
    val Slate = Color(0xFF55636E)        // 次级文字
    val Paper = Color(0xFFF6F8F7)        // 页面背景（冷调纸白，不是奶油）
    val Surface = Color(0xFFFFFFFF)      // 卡片
    val Line = Color(0xFFE1E7E5)         // 分隔线、描边
    val Accent = Color(0xFF0F7B6C)       // 唯一强调色：连接、当前目录、主操作
    val AccentSoft = Color(0xFFDCEFEA)   // 强调色浅底
    val Danger = Color(0xFFB4453A)       // 删除 / 错误
    val DangerSoft = Color(0xFFFBE9E7)
    val FileBlue = Color(0xFF2F6F8F)     // 代码 / 数据类文件
    val FileViolet = Color(0xFF6B5CA5)   // 图片类
    val FileAmber = Color(0xFF9A6B21)    // 压缩包 / 其它

    // 形状刻度（Material 3）：小元素 8 → 卡片 16 → 面板 28 → 按钮全圆角
    val ShapeSmall = RoundedCornerShape(8.dp)
    val ShapeCard = RoundedCornerShape(16.dp)
    val ShapeSheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp,
        bottomStart = 0.dp, bottomEnd = 0.dp)
    val ShapePill = RoundedCornerShape(50)
}

private val Scheme = lightColorScheme(
    primary = FB.Accent,
    onPrimary = Color.White,
    primaryContainer = FB.AccentSoft,
    onPrimaryContainer = FB.Ink,
    background = FB.Paper,
    onBackground = FB.Ink,
    surface = FB.Surface,
    onSurface = FB.Ink,
    surfaceVariant = Color(0xFFEDF2F0),
    onSurfaceVariant = FB.Slate,
    outline = FB.Line,
    error = FB.Danger,
    onError = Color.White,
)

private val Type = Typography(
    // 4 级字阶，靠字号/字重区分，不靠颜色或全大写标签
    displaySmall = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp, color = FB.Ink),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp, color = FB.Ink),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium,
        color = FB.Ink),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal,
        lineHeight = 20.sp, color = FB.Ink),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal,
        lineHeight = 17.sp, color = FB.Slate),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = FB.Ink),
)

private val Shapes = Shapes(
    extraSmall = FB.ShapeSmall,
    small = FB.ShapeSmall,
    medium = RoundedCornerShape(12.dp),
    large = FB.ShapeCard,
    extraLarge = FB.ShapeSheet,
)

@Composable
fun FileBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Type,
        shapes = Shapes,
        content = content,
    )
}
