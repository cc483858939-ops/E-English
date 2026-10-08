package com.eenglish.listening

import android.graphics.PointF
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.espresso.*
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.hamcrest.Matchers.*
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher

fun textAt(tag: String) = Espresso.onView(withTagValue(equalTo(tag)))
fun selectionMenu(label: String) {
    val item = withText(label)
    // FloatingToolbar is a separate non-focusable platform window, not Activity's decor.
    Espresso.onView(item).inRoot(withDecorView(hasDescendant(item))).perform(click())
    if (label == "复制") dismissClipboardPreview()
}

private fun dismissClipboardPreview() {
    // Android 13+ shows a separate clipboard preview over the bottom question card.
    // Dismiss its actual system close button before the next text gesture.
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val info = automation.serviceInfo
    info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    automation.serviceInfo = info
    repeat(20) {
        val close = automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId("com.android.systemui:id/dismiss_button").asSequence() }
            .firstOrNull()
        if (close != null) {
            check(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(300)
            return
        }
        SystemClock.sleep(50)
    }
    // Some platform variants omit the close button; allow the standard preview to expire.
    SystemClock.sleep(6500)
}
fun pressWord(offset: Int) = object : ViewAction {
    override fun getDescription() = "Long press a UTF-16 text position"
    override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
    override fun perform(controller: UiController, view: View) {
        val text = view as TextView
        val point = textPoint(text, offset)
        GeneralClickAction(Tap.LONG, { floatArrayOf(point.x, point.y) }, Press.FINGER,
            InputDevice.SOURCE_TOUCHSCREEN, 0).perform(controller, view)
        // Android's text classifier/selection toolbar may finish asynchronously after ACTION_UP.
        controller.loopMainThreadForAtLeast(800)
        controller.loopMainThreadUntilIdle()
    }
}
fun selectionAction(start: Int, end: Int) = object : ViewAction {
    override fun getDescription() = "Adjust native selection using Android accessibility action"
    override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
    override fun perform(controller: UiController, view: View) {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
        controller.loopMainThreadForAtLeast(500)
        check((view as TextView).selectionStart == start && view.selectionEnd == end)
        controller.loopMainThreadUntilIdle()
    }
}
fun dragEndHandle(text: TextView, toOffset: Int) {
    val handle = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) { description.appendText("Android native selection end handle") }
        override fun matchesSafely(view: View) = view.id != View.NO_ID &&
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "selection_end_handle"
    }
    Espresso.onView(handle).inRoot(withDecorView(hasDescendant(handle)))
        .perform(object : ViewAction {
          override fun getDescription() = "Drag the native end handle with touchscreen finger events"
          override fun getConstraints(): Matcher<View> = isDisplayed()
          override fun perform(controller: UiController, view: View) {
            val from = textPoint(text, text.selectionEnd)
            val target = textPoint(text, toOffset)
            val center = GeneralLocation.CENTER.calculateCoordinates(view)
            val downTime = SystemClock.uptimeMillis()
            fun event(action: Int, fraction: Float) {
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coordinates = MotionEvent.PointerCoords().apply {
                    x = center[0] + (target.x - from.x) * fraction
                    // Native handles keep a finger-to-cursor gap and vertical hysteresis.
                    // Travel a line farther down; assertions inspect the resulting real range.
                    y = center[1] + (target.y - from.y + text.lineHeight) * fraction
                    pressure = 1f; size = 1f
                }
                val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                    arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { check(controller.injectMotionEvent(motion)) } finally { motion.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, 0f)
            try {
                repeat(32) { index -> controller.loopMainThreadForAtLeast(25); event(MotionEvent.ACTION_MOVE, (index + 1) / 32f) }
                controller.loopMainThreadForAtLeast(200)
                event(MotionEvent.ACTION_UP, 1f)
            } catch (error: Throwable) { event(MotionEvent.ACTION_CANCEL, 1f); throw error }
            controller.loopMainThreadForAtLeast(800)
          }
        })
}
fun textPoint(view: TextView, offset: Int): PointF {
    val line = view.layout.getLineForOffset(offset)
    val location = IntArray(2).also(view::getLocationOnScreen)
    return PointF(location[0] + view.totalPaddingLeft + view.layout.getPrimaryHorizontal(offset) + 2f,
        location[1] + view.totalPaddingTop + (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f - view.scrollY)
}
fun nativeText(activity: android.app.Activity, tag: String): TextView = activity.window.decorView.findViewWithTag(tag)
