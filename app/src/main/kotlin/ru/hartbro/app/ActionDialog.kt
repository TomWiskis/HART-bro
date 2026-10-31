package ru.hartbro.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Actions have their own measured area; only the description consumes the remaining scrollable height. */
@Composable
internal fun ActionDialog(title:String,onDismiss:()->Unit,tag:String,content:@Composable ()->Unit,actions:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),contentAlignment=Alignment.Center) {
            Surface(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=maxHeight).testTag(tag),shape=MaterialTheme.shapes.extraLarge,tonalElevation=6.dp) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(title,style=MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) { content() }
                    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp),content=actions)
                }
            }
        }
    }
}
