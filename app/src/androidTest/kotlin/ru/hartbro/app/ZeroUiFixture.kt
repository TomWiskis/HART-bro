package ru.hartbro.app

import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/** Dialog has its own window: wait for its actual IME insets before injecting touch coordinates. */
internal fun awaitZeroKeyboardHidden(ui:ComposeTestRule) {
    ui.onNodeWithTag("zeroAccuracySource").performImeAction()
    ui.waitForIdle()
    var windows=""
    var hiddenSince=0L
    try { ui.waitUntil(15_000) {
        var hidden=false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val views=WindowInspector.getGlobalWindowViews()
            windows=views.joinToString {v->val i=ViewCompat.getRootWindowInsets(v);"${v.javaClass.simpleName}: focus=${v.hasWindowFocus()}, attached=${v.isAttachedToWindow}, ime=${i?.isVisible(WindowInsetsCompat.Type.ime())}, bottom=${i?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}"}
            val focused=views.filter{it.hasWindowFocus()}
            val input=InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(InputMethodManager::class.java)
            hidden=focused.isNotEmpty()&&focused.all{view->
                val insets=ViewCompat.getRootWindowInsets(view)
                val closed=insets!=null&&!insets.isVisible(WindowInsetsCompat.Type.ime())&&insets.getInsets(WindowInsetsCompat.Type.ime()).bottom==0
                if(!closed)input.hideSoftInputFromWindow(view.windowToken,0)
                closed
            }
        }
        val now=android.os.SystemClock.elapsedRealtime()
        if(!hidden)hiddenSince=0 else if(hiddenSince==0L)hiddenSince=now
        hidden&&now-hiddenSince>=300
    }
    } catch(e:Throwable){throw AssertionError("Dialog IME did not close: $windows",e)}
    ui.waitForIdle()
}
