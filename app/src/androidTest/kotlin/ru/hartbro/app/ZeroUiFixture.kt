package ru.hartbro.app

import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/** Dialog has its own window: wait for its actual IME insets before injecting touch coordinates. */
internal fun awaitZeroKeyboardHidden(ui:ComposeTestRule) {
    ui.onNodeWithTag("zeroAccuracySource").performImeAction()
    ui.waitUntil(15_000) {
        var hidden=false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val focused=WindowInspector.getGlobalWindowViews().filter{it.hasWindowFocus()}
            hidden=focused.isNotEmpty()&&focused.all{view->
                val insets=ViewCompat.getRootWindowInsets(view)
                insets!=null&&!insets.isVisible(WindowInsetsCompat.Type.ime())&&insets.getInsets(WindowInsetsCompat.Type.ime()).bottom==0
            }
        }
        hidden
    }
    ui.waitForIdle()
}
