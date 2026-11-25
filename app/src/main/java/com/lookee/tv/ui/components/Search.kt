package com.lookee.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lookee.tv.ui.theme.LookeeColors
import java.text.Normalizer

private val PillShape = RoundedCornerShape(50)

/**
 * A search box for the remote. At rest it's a focusable pill, so moving the D-pad across it
 * doesn't pop the keyboard up; OK turns it into a text field and opens the keyboard. The
 * keyboard's Search key, Back or Down hand focus back to the pill. Results update as you type.
 */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    var fieldHadFocus by remember { mutableStateOf(false) }
    var pillFocused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val pill = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Both the pill and the field stay composed, and focus moves directly between them:
    // removing a focused node sends focus back to the tabs.
    val finish = {
        runCatching { pill.requestFocus() }
        keyboard?.hide()
        editing = false
    }
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { } // the field becomes focusable on this frame
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }

    Box(modifier) {
        Surface(
            onClick = { editing = true },
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (editing) 0f else 1f)
                .focusRequester(pill)
                .onFocusChanged { pillFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(PillShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = LookeeColors.Surface,
                focusedContainerColor = LookeeColors.Surface2,
                pressedContainerColor = LookeeColors.Surface2,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, LookeeColors.Line), shape = PillShape),
                focusedBorder = Border(BorderStroke(2.dp, LookeeColors.Accent), shape = PillShape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SearchGlyph(if (pillFocused || value.isNotEmpty()) LookeeColors.Accent else LookeeColors.Muted)
                Text(
                    value.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value.isEmpty()) LookeeColors.Muted else LookeeColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier
                .matchParentSize()
                .alpha(if (editing) 1f else 0f)
                .clip(PillShape)
                .background(LookeeColors.Surface)
                .border(2.dp, LookeeColors.Accent, PillShape)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchGlyph(LookeeColors.Accent)
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = LookeeColors.Muted, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = LookeeColors.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(LookeeColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { finish() }, onDone = { finish() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = editing }
                        .focusRequester(field)
                        .onFocusChanged {
                            if (it.isFocused) {
                                fieldHadFocus = true
                            } else if (fieldHadFocus) {
                                // Focus left some other way (Up to the tabs): stop editing.
                                fieldHadFocus = false
                                keyboard?.hide()
                                editing = false
                            }
                        }
                        .onPreviewKeyEvent { e ->
                            val leave = e.key == Key.Back || e.key == Key.DirectionDown
                            if (leave && e.type == KeyEventType.KeyUp) finish()
                            leave
                        },
                )
            }
        }
    }
}

/** A small magnifying glass. */
@Composable
fun SearchGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val r = size.minDimension * 0.34f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        val stroke = size.minDimension * 0.13f
        drawCircle(color, radius = r, center = c, style = Stroke(stroke))
        drawLine(color, Offset(c.x + r * 0.72f, c.y + r * 0.72f), Offset(size.width * 0.92f, size.height * 0.92f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** "Clear" next to an active search. */
@Composable
fun ClearSearch(onClear: () -> Unit) {
    LookeeButton("Clear", onClick = onClear, style = ButtonStyle.Quiet)
}

/**
 * Filters what a screen already shows by its titles: every word of [query] must appear,
 * ignoring case and accents ("beyonce" finds "Beyoncé"). Presentation only; nothing is fetched.
 */
fun matchesQuery(text: String, query: String): Boolean {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val haystack = fold(text)
    return words.all { haystack.contains(it) }
}

private val marks = Regex("\\p{Mn}+")

private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()
