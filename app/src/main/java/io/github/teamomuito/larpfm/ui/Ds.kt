package io.github.teamomuito.larpfm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.teamomuito.larpfm.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The look of Pictoclip (https://github.com/teamomuito/pictoclip), which copies the Nintendo DS
// system menu. Fixed colours and one font, in light and dark system themes alike.

object DsColors {
    val Background = Color(0xFFF4F5F6)
    val Grid = Color(0xFFE1E4E7)
    val Border = Color(0xFF4A4F55)
    val Shadow = Color(0xFFC3C8CD)
    val Ink = Color(0xFF3A3F45)
    val Subtle = Color(0xFF8A9096)
    val Pressed = Color(0xFFD5E2EE)
    val Cursor = Color(0xFF3E6DB0)
    val TopLight = Color(0xFF6F8C99)
    val TopDark = Color(0xFF4A6573)
    val Bar = Color(0xFFE3E6E9)
    val Panel = Color.White
    val Error = Color(0xFFB5473A)
}

/** DotGothic16, the pixel font Pictoclip uses for all of its text. */
val DsFont = FontFamily(Font(R.font.dotgothic16))

/** Grid paper: a 1px line every 16dp on the blue-grey background. */
fun Modifier.dsGrid(): Modifier = drawWithCache {
    val cell = 16.dp.toPx()
    val lines = Path()
    var x = 0f
    while (x < size.width) {
        lines.addRect(Rect(x, 0f, x + 1f, size.height))
        x += cell
    }
    var y = 0f
    while (y < size.height) {
        lines.addRect(Rect(0f, y, size.width, y + 1f))
        y += cell
    }
    onDrawBehind {
        drawRect(DsColors.Background)
        drawPath(lines, DsColors.Grid)
    }
}

/**
 * A notched panel: a dark border that's cut off at the corners, a shadow along the bottom and
 * right, and a face that goes to the pressed colour while [pressed].
 */
fun Modifier.dsPanel(
    fill: Color = DsColors.Panel,
    border: Dp = 2.dp,
    pressed: Boolean = false,
): Modifier = drawWithCache {
    val px = border.toPx()
    val w = size.width
    val h = size.height
    val outline = notchedOutline(w, h, px)
    onDrawBehind {
        drawPath(outline, DsColors.Border)
        val left = px
        val top = px
        val right = w - px
        val bottom = h - px
        if (pressed) {
            drawRect(DsColors.Pressed, Offset(left, top), Size(right - left, bottom - top))
        } else {
            drawRect(fill, Offset(left, top), Size(right - left, bottom - top))
            drawRect(DsColors.Shadow, Offset(left, bottom - px), Size(right - left, px))
            drawRect(DsColors.Shadow, Offset(right - px, top), Size(px, bottom - top))
        }
    }
}

private fun notchedOutline(w: Float, h: Float, c: Float): Path = Path().apply {
    moveTo(c, 0f)
    lineTo(w - c, 0f)
    lineTo(w, c)
    lineTo(w, h - c)
    lineTo(w - c, h)
    lineTo(c, h)
    lineTo(0f, h - c)
    lineTo(0f, c)
    close()
}

/** Blue corner brackets around the selected item, the same as Pictoclip's selected clip. */
fun Modifier.dsCursor(show: Boolean): Modifier = if (!show) {
    this
} else {
    drawWithContent {
        drawContent()
        val stroke = 3.dp.toPx()
        val inset = 3.dp.toPx() + stroke / 2
        val arm = 14.dp.toPx()
        val l = inset
        val t = inset - 1.dp.toPx()
        val r = size.width - inset
        val b = size.height - inset + 1.dp.toPx()
        val brackets = Path().apply {
            moveTo(l, t + arm)
            lineTo(l, t)
            lineTo(l + arm, t)
            moveTo(r - arm, t)
            lineTo(r, t)
            lineTo(r, t + arm)
            moveTo(r, b - arm)
            lineTo(r, b)
            lineTo(r - arm, b)
            moveTo(l + arm, b)
            lineTo(l, b)
            lineTo(l, b - arm)
        }
        drawPath(brackets, DsColors.Cursor, style = Stroke(width = stroke))
    }
}

@Composable
fun DsText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    color: Color = DsColors.Ink,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = DsFont,
        fontSize = size,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}

/** The small grey heading above a group of panels. */
@Composable
fun SectionLabel(text: String) {
    DsText(text.uppercase(), size = 12.sp, color = DsColors.Subtle, modifier = Modifier.padding(start = 4.dp))
}

/** A panel that holds a column of content, e.g. one group of settings. */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    fill: Color = DsColors.Panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .dsPanel(fill = fill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** The status bar: the title on the left, and the clock and date on the right. */
@Composable
fun DsTopBar(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(10_000)
        }
    }
    val clock = SimpleDateFormat("HH:mm  MM/dd", Locale.getDefault()).format(Date(now))
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(DsColors.TopLight, DsColors.TopDark))),
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsText(title, size = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
            actions()
            DsText(clock, size = 14.sp, color = Color.White)
        }
    }
}

/** The prompt panel under the status bar, like "Choose a clip to copy.", with an optional count on the right. */
@Composable
fun DsPrompt(message: String, count: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 4.dp)
            .dsPanel()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsText(message, size = 15.sp, modifier = Modifier.weight(1f))
        if (count != null) DsText(count, size = 13.sp, color = DsColors.Subtle)
    }
}

/** The strip of buttons along the bottom, with the system navigation bar below it. */
@Composable
fun DsBottomBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(DsColors.Bar)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A bottom-screen button: an optional lettered ring (A, B, ...) and the label, centred in the rest.
 * Pressing it pushes the panel in.
 */
@Composable
fun DsButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    letter: String? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier
            .heightIn(min = 48.dp)
            .dsPanel(pressed = pressed)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(start = 11.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (letter != null) {
            Box(Modifier.size(24.dp).border(2.dp, DsColors.Border, CircleShape), contentAlignment = Alignment.Center) {
                DsText(letter, size = 13.sp, color = DsColors.Border)
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            DsText(label, size = 16.sp, color = if (enabled) DsColors.Ink else DsColors.Subtle)
        }
    }
}

/** A pill with a time on it, like the time on each clip. */
@Composable
fun DsTimePill(text: String) {
    DsText(
        text,
        size = 12.sp,
        modifier = Modifier
            .dsPanel(fill = DsColors.Background, border = 1.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** Pictoclip's page icon, drawn on a 9 by 11 grid of pixels. */
@Composable
fun PageIcon(modifier: Modifier = Modifier) {
    Canvas(modifier.size(27.dp, 33.dp)) {
        val unit = 3.dp.toPx()
        PAGE_ICON.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val color = when (ch) {
                    '#' -> DsColors.Border
                    '.' -> Color.White
                    '=' -> DsColors.Subtle
                    else -> return@forEachIndexed
                }
                drawRect(color, Offset(x * unit, y * unit), Size(unit, unit))
            }
        }
    }
}

/** On/off as a notched panel with a square knob: blue and to the right when on. */
@Composable
fun DsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(width = 44.dp, height = 22.dp)
            .dsPanel(fill = if (checked) DsColors.Cursor else DsColors.Background)
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(5.dp)
                .size(12.dp)
                .background(if (checked) Color.White else DsColors.Border),
        )
    }
}

@Composable
fun DsIconToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: @Composable (Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(28.dp)
            .dsPanel(fill = if (checked) DsColors.Cursor else DsColors.TopLight, border = 1.dp)
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (checked) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        icon(checked)
    }
}

@Composable
fun ScrobblingIcon(checked: Boolean, modifier: Modifier = Modifier) {
    val color = if (checked) Color.White else Color.White.copy(alpha = 0.55f)
    Canvas(modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(color, radius = 1.7.dp.toPx(), center = center)
        for (radius in listOf(4.5.dp.toPx(), 7.5.dp.toPx())) {
            for (start in listOf(135f, -45f)) {
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
fun AlbumIcon(checked: Boolean, modifier: Modifier = Modifier) {
    val color = if (checked) Color.White else Color.White.copy(alpha = 0.55f)
    Canvas(modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(color, radius = 7.5.dp.toPx(), center = center, style = Stroke(width = stroke))
        drawCircle(color, radius = 3.8.dp.toPx(), center = center, style = Stroke(width = stroke))
        drawCircle(color, radius = 1.4.dp.toPx(), center = center)
    }
}

/** A bar that fills up to the value. Tap or drag along it to change the value. */
@Composable
fun DsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var width by remember { mutableFloatStateOf(0f) }
    val span = range.endInclusive - range.start
    val fraction = if (span > 0f) ((value - range.start) / span).coerceIn(0f, 1f) else 0f
    fun valueAt(x: Float): Float = if (width > 0f) range.start + (x / width).coerceIn(0f, 1f) * span else value

    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onTap = { onValueChange(valueAt(it.x)) })
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectHorizontalDragGestures(onHorizontalDrag = { change, _ -> onValueChange(valueAt(change.position.x)) })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(14.dp).dsPanel(fill = DsColors.Background, border = 1.dp)) {
            Box(Modifier.padding(3.dp).fillMaxWidth(fraction).fillMaxHeight().background(DsColors.Cursor))
        }
    }
}

/** A row of choices, with the chosen one filled in blue. */
@Composable
fun <T> DsSegments(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().dsPanel(border = 1.dp).padding(4.dp)) {
        for (option in options) {
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (isSelected) DsColors.Cursor else Color.Transparent)
                    .clickable(enabled = enabled && !isSelected) { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                DsText(label(option), size = 15.sp, color = if (isSelected) Color.White else DsColors.Ink)
            }
        }
    }
}

/** A text box inside a thin notched panel, with its label above. */
@Composable
fun DsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    password: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DsText(label, size = 13.sp, color = DsColors.Subtle)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .dsPanel(fill = DsColors.Background, border = 1.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(fontFamily = DsFont, fontSize = 15.sp, color = DsColors.Ink),
            cursorBrush = SolidColor(DsColors.Cursor),
            singleLine = true,
            keyboardOptions = keyboardOptions,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

/** Pictoclip's time stamp: the clock time for today, otherwise the date. */
fun formatClipTime(millis: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "MM/dd", Locale.getDefault()).format(Date(millis))
}

/** The page icon from Pictoclip, one character per pixel: # border, . white, = grey. */
private val PAGE_ICON = listOf(
    "####### ",
    "#.....##",
    "#.....#.#",
    "#.....###",
    "#.......#",
    "#.=====.#",
    "#.......#",
    "#.=====.#",
    "#.......#",
    "#.===...#",
    "#########",
).map { it.padEnd(9) }
