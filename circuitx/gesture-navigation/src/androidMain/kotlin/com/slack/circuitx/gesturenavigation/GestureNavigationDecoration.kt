// Copyright (C) 2023 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.slack.circuitx.gesturenavigation

import android.os.Build
import android.window.BackEvent
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.slack.circuit.backstack.NavDecoration
import kotlin.math.absoluteValue
import kotlinx.collections.immutable.ImmutableList

public actual fun GestureNavigationDecoration(
  fallback: NavDecoration,
  onBackInvoked: () -> Unit
): NavDecoration =
  when {
    Build.VERSION.SDK_INT >= 34 -> AndroidPredictiveNavigationDecoration(onBackInvoked)
    else -> fallback
  }

@RequiresApi(34)
private class AndroidPredictiveNavigationDecoration(
  private val onBackInvoked: () -> Unit,
) : NavDecoration {
  @Composable
  override fun <T> DecoratedContent(
    args: ImmutableList<T>,
    backStackDepth: Int,
    modifier: Modifier,
    content: @Composable (T) -> Unit,
  ) {
    val current = args.first()
    val previous = args.getOrNull(1)

    Box(modifier = modifier) {
      var showPrevious by remember { mutableStateOf(false) }
      var recordPoppedFromGesture by remember { mutableStateOf<T?>(null) }

      val transition = updateTransition(targetState = current, label = "GestureNavDecoration")

      if (previous != null && !transition.isStateBeingAnimated(previous)) {
        // We display the 'previous' item in the back stack for when the user performs a gesture
        // to go back.
        // We only display it here if the transition is not running. When the transition is
        // running, the record's movable content will still be attached to the
        // AnimatedContent below. If we call it here too, we will invoke a new copy of
        // the content (and thus dropping all state). The if statement above keeps the states
        // exclusive, so that the movable content is only used once at a time.
        OptionalLayout(shouldLayout = { showPrevious }) { content(previous) }
      }

      // Remember the previous stack depth so we know if the navigation is going "back".
      var prevStackDepth by remember { mutableIntStateOf(backStackDepth) }
      SideEffect { prevStackDepth = backStackDepth }

      LaunchedEffect(transition.currentState) {
        // When the current state has changed (i.e. any transition has completed),
        // clear out any transient state
        showPrevious = false
        recordPoppedFromGesture = null
      }

      transition.AnimatedContent(
        transitionSpec = {
          // Mirror the forward and backward transitions of activities in Android 33
          when {
            // adding to back stack
            backStackDepth > prevStackDepth -> {
              (slideInHorizontally(tween(), SlightlyRight) + fadeIn()) togetherWith
                (slideOutHorizontally(tween(), SlightlyLeft) + fadeOut())
            }

            // come back from back stack
            backStackDepth < prevStackDepth -> {
              if (recordPoppedFromGesture == initialState) {
                  EnterTransition.None togetherWith scaleOut(targetScale = 0.8f) + fadeOut()
                } else {
                  slideInHorizontally(tween(), SlightlyLeft) + fadeIn() togetherWith
                    slideOutHorizontally(tween(), SlightlyRight) + fadeOut()
                }
                .apply { targetContentZIndex = -1f }
            }

            // Root reset. Crossfade
            else -> fadeIn() togetherWith fadeOut()
          }
        },
      ) { record ->
        var swipeProgress by remember { mutableFloatStateOf(0f) }

        if (backStackDepth > 1) {
          BackHandler(
            onBackProgress = { progress ->
              showPrevious = progress != 0f
              swipeProgress = progress
            },
            onBackInvoked = {
              if (swipeProgress != 0f) {
                // If back has been invoked, and the swipe progress isn't zero,
                // mark this record as 'popped via gesture' so we can
                // use a different transition
                recordPoppedFromGesture = record
              }
              onBackInvoked()
            },
          )
        }

        Box(
          Modifier.predictiveBackMotion(
            shape = MaterialTheme.shapes.extraLarge,
            progress = { swipeProgress },
          )
        ) {
          content(record)
        }
      }
    }
  }
}

private const val FIVE_PERCENT = 0.05f
private val SlightlyRight = { width: Int -> (width * FIVE_PERCENT).toInt() }
private val SlightlyLeft = { width: Int -> 0 - (width * FIVE_PERCENT).toInt() }

private fun <T> Transition<T>.isStateBeingAnimated(state: T): Boolean {
  return isRunning && (currentState == state || targetState == state)
}

/**
 * Implements most of the treatment specified at
 * https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back#designing-gesture
 *
 * The only piece missing is the vertical shift.
 */
private fun Modifier.predictiveBackMotion(
  shape: Shape,
  progress: () -> Float,
): Modifier = graphicsLayer {
  val p = progress()
  // If we're at progress 0f, skip setting any parameters
  if (p == 0f) return@graphicsLayer

  translationX = -(8.dp * p).toPx()
  shadowElevation = 6.dp.toPx()

  val scale = lerp(1f, 0.9f, p.absoluteValue)
  scaleX = scale
  scaleY = scale
  transformOrigin =
    TransformOrigin(
      pivotFractionX = if (p > 0) 1f else 0f,
      pivotFractionY = 0.5f,
    )

  this.shape = shape
  clip = true
}

@RequiresApi(34)
@Composable
private fun BackHandler(
  onBackProgress: (Float) -> Unit,
  animatedEnabled: Boolean = true,
  onBackInvoked: () -> Unit,
) {
  val onBackDispatcher =
    LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
      ?: error("OnBackPressedDispatcher is not available")
  val lastAnimatedEnabled by rememberUpdatedState(animatedEnabled)
  val lastOnBackProgress by rememberUpdatedState(onBackProgress)
  val lastOnBackInvoked by rememberUpdatedState(onBackInvoked)

  DisposableEffect(onBackDispatcher) {
    val callback =
      object : OnBackPressedCallback(true) {

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
          if (lastAnimatedEnabled) {
            lastOnBackProgress(0f)
          }
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
          if (lastAnimatedEnabled) {
            lastOnBackProgress(
              when (backEvent.swipeEdge) {
                BackEvent.EDGE_LEFT -> backEvent.progress
                else -> -backEvent.progress
              }
            )
          }
        }

        override fun handleOnBackPressed() = lastOnBackInvoked()
      }

    onBackDispatcher.addCallback(callback)

    onDispose { callback.remove() }
  }
}
