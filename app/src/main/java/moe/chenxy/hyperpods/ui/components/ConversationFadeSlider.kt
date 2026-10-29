package moe.chenxy.hyperpods.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.ConversationFade
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ConversationFadeSlider(
    title: Int,
    valueMs: Int,
    testTag: String,
    onChange: (Int) -> Unit,
) {
    val normalized = ConversationFade.normalize(valueMs)
    var draft by remember { mutableFloatStateOf(normalized.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(valueMs, dragging) {
        if (!dragging) draft = ConversationFade.normalize(valueMs).toFloat()
    }
    val range = ConversationFade.MIN_MS.toFloat()..ConversationFade.MAX_MS.toFloat()
    val displayed = draft.coerceIn(range)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(title), fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.conversation_fade_value, displayed.roundToInt()),
            fontSize = 13.sp)
        Text(stringResource(R.string.conversation_fade_range), fontSize = 12.sp)
        Slider(
            value = displayed,
            valueRange = range,
            onValueChange = {
                dragging = true
                draft = it
            },
            onValueChangeFinished = {
                dragging = false
                val submitted = ConversationFade.normalize(draft.roundToInt())
                draft = submitted.toFloat()
                onChange(submitted)
            },
            modifier = Modifier.fillMaxWidth().testTag(testTag)
                .progressSemantics(displayed, range),
        )
    }
}
