package com.conreo.couchytv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures

@Composable
fun Modifier.airMouseInput(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val focusRequester = remember { FocusRequester() }
    val currentOnClick = rememberUpdatedState(onClick)
    val currentOnLongClick = rememberUpdatedState(onLongClick)

    return focusRequester(focusRequester)
        .pointerInput(onLongClick != null) {
            if (onLongClick == null) {
                detectTapGestures(
                    onTap = {
                        focusRequester.requestFocus()
                        currentOnClick.value()
                    },
                )
            } else {
                detectTapGestures(
                    onTap = {
                        focusRequester.requestFocus()
                        currentOnClick.value()
                    },
                    onLongPress = {
                        focusRequester.requestFocus()
                        currentOnLongClick.value?.invoke()
                    },
                )
            }
        }
}

fun Modifier.airMouseDragScroll(
    state: ScrollableState,
    orientation: Orientation,
): Modifier = pointerInput(state, orientation) {
    awaitEachGesture {
        val downEvent = awaitPointerEvent(PointerEventPass.Initial)
        val down = downEvent.changes.firstOrNull {
            it.pressed && it.type == PointerType.Mouse
        } ?: return@awaitEachGesture

        var previousPosition = down.position
        var dragging = false
        val pointerId = down.id
        val slop = viewConfiguration.touchSlop

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId }
                ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture

            val delta = change.position - previousPosition
            previousPosition = change.position
            val axisDelta = if (orientation == Orientation.Horizontal) delta.x else delta.y

            if (!dragging) {
                val displacement = change.position - down.position
                val axisDisplacement =
                    if (orientation == Orientation.Horizontal) displacement.x else displacement.y
                val crossAxisDisplacement =
                    if (orientation == Orientation.Horizontal) displacement.y else displacement.x
                if (kotlin.math.abs(crossAxisDisplacement) > slop &&
                    kotlin.math.abs(crossAxisDisplacement) > kotlin.math.abs(axisDisplacement)
                ) {
                    return@awaitEachGesture
                }
                if (kotlin.math.abs(axisDisplacement) <= slop) continue
                dragging = true
                val overSlop = axisDisplacement -
                    kotlin.math.sign(axisDisplacement) * slop
                state.dispatchRawDelta(-overSlop)
            } else {
                state.dispatchRawDelta(-axisDelta)
            }

            change.consume()
        }
    }
}
