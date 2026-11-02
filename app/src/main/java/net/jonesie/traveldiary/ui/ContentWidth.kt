package net.jonesie.traveldiary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The widest a list or form is allowed to get. On a phone this changes nothing; on a tablet it stops rows spreading across the screen. */
private val MaxContentWidth = 720.dp

/** Caps the width at [MaxContentWidth] and centres it. Put it before any scroll modifier so the whole list is what gets centred. */
fun Modifier.contentWidth(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = MaxContentWidth).fillMaxWidth()

/** A full-size column held to [MaxContentWidth], for one tab or section of a wider screen. */
@Composable
fun CappedColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize().contentWidth(), content = content)
}
