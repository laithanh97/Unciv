package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Tooltip
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * A **Replacement** for Gdx [Tooltip], placement does not follow the mouse.
 *
 * Usage: [group][Group].addTooltip([text][String], size) builds a [Label] as tip actor and attaches it to your [Group].
 *
 * @param target        The widget the tooltip will be added to - take care this is the same for which addListener is called
 * @param content       The actor to display as Tooltip
 * @param targetAlign   Point on the [target] widget to align the Tooltip to
 * @param tipAlign      Point on the Tooltip to align with the given point on the [target]
 * @param offset        Additional offset for Tooltip position after alignment
 * @param animate       Use show/hide animations
 * @param forceContentSize  Force virtual [content] width/height for alignment calculation
 *                      - because Gdx auto layout reports wrong dimensions on scaled actors.
 */
// region fields
class UncivTooltip <T: Actor>(
    val target: Actor,
    val content: T,
    val targetAlign: Int = Align.topRight,
    val tipAlign: Int = Align.topRight,
    val offset: Vector2 = Vector2.Zero,
    val animate: Boolean = true,
    forceContentSize: Vector2? = null,
) : InputListener() {

    private val container: Container<T> = Container(content)
    enum class TipState { Hidden, Showing, Shown, Hiding }
    /** current visibility state of the Tooltip */
    var state: TipState = TipState.Hidden
        private set

    // Needed for Android with physical keyboard detected, to avoid tips staying on-screen after
    // touching buttons (exit fires, sometimes very late, with "to" actor being the label of the button)
    private var touchDownSeen = false

    private val contentWidth: Float
    private val contentHeight: Float

    init {
        content.touchable = Touchable.disabled
        container.pack()
        contentWidth = forceContentSize?.x ?: content.width
        contentHeight = forceContentSize?.y ?: content.height
    }

    //endregion
    //region show, hide and positioning

    /** Show the Tooltip ([immediate]ly or begin the animation). _Can_ be called programmatically. */
    fun show(immediate: Boolean = false) {
        if (target.stage == null) return

        val useAnimation = animate && !immediate
        if (state == TipState.Shown || state == TipState.Showing && useAnimation || !target.hasParent()) return
        if (state == TipState.Showing || state == TipState.Hiding) {
            container.clearActions()
            state = TipState.Hidden
            container.remove()
        }

        val pos = target.localToStageCoordinates(target.getEdgePoint(targetAlign)).add(offset)
        container.run {
            val originX = getOriginX(contentWidth, tipAlign)
            val originY = getOriginY(contentHeight, tipAlign)
            setOrigin(originX, originY)
            setPosition(pos.x - originX, pos.y - originY)
            if (useAnimation) {
                isTransform = true
                color.a = 0.1f
                setScale(0.1f)
            } else {
                isTransform = false
                color.a = 1f
                setScale(1f)
            }
        }
        target.stage.addActor(container)

        if (useAnimation) {
            startShowAction(TipState.Shown)
        } else
            state = TipState.Shown
    }

    /** Hide the Tooltip ([immediate]ly or begin the animation). _Can_ be called programmatically. */
    fun hide(immediate: Boolean = false) {
        val useAnimation = animate && !immediate
        if (state == TipState.Hidden || state == TipState.Hiding && useAnimation) return
        if (state == TipState.Showing || state == TipState.Hiding) {
            container.clearActions()
            state = TipState.Shown  // edge case. may actually only be partially 'shown' - animate hide anyway
        }
        if (useAnimation) {
            startShowAction(TipState.Hidden)
        } else {
            container.remove()
            state = TipState.Hidden
        }
    }

    private fun getOriginX(width: Float, align: Int) = when {
        (align and Align.left) != 0 -> 0f
        (align and Align.right) != 0 -> width
        else -> width / 2
    }
    private fun getOriginY(height: Float, align: Int) = when {
        (align and Align.bottom) != 0 -> 0f
        (align and Align.top) != 0 -> height
        else -> height / 2
    }
    private fun Actor.getEdgePoint(align: Int) =
        Vector2(getOriginX(width,align),getOriginY(height,align))

    private fun startShowAction(endState: TipState) {
        container.clearActions()
        container.addAction(ShowAction(endState))
    }

    private inner class ShowAction(
        private val endState: TipState
    ) : TemporalAction(tipAnimationDuration, Interpolation.fade) {
        private val transitionState: TipState
        private val valueAdd: Float
        private val valueMul: Float

        init {
            if (endState == TipState.Shown) {
                transitionState = TipState.Showing
                valueAdd = 0.1f
                valueMul = 0.9f
            } else {
                transitionState = TipState.Hiding
                valueAdd = 1f
                valueMul = -0.9f
            }
            state = transitionState
        }

        override fun update(percent: Float) {
            if (!target.hasParent()) {
                hide(true)
                finish()
            }
            val value = percent * valueMul + valueAdd
            target.color.a = value
            target.setScale(value)
        }
        override fun end() {
            if (state == transitionState) state = endState
            if (endState == TipState.Hidden) target.remove()
        }
    }

    //endregion
    //region events

    override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
        // assert(event?.listenerActor == target) - tested - holds true
        if (fromActor != null && fromActor.isDescendantOf(target)) return
        show()
    }

    override fun exit(event: InputEvent?, x: Float, y: Float, pointer: Int, toActor: Actor?) {
        if (!touchDownSeen && toActor != null && toActor.isDescendantOf(target)) return
        touchDownSeen = false
        hide()
    }

    override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int ): Boolean {
        touchDownSeen = true
        container.toFront()     // this is a no-op if it has no parent
        return super.touchDown(event, x, y, pointer, button)
    }

    //endregion

    companion object {
        /** Duration of the fade/zoom-in/out animations */
        const val tipAnimationDuration = 0.2f

        /**
         * Add a [Label]-based Tooltip with a rounded-corner background to a [Table] or other [Group].
         *
         * Tip is positioned over top right corner, slightly overshooting the receiver widget, longer tip [text]s will extend to the left.
         *
         * @param text Automatically translated tooltip text
         * @param size _Vertical_ size of the entire Tooltip including background
         * @param always override requirement: presence of physical keyboard
         * @param targetAlign   Point on the [target] widget to align the Tooltip to
         * @param tipAlign      Point on the Tooltip to align with the given point on the [target]
         */
        fun Actor.addTooltip(
            text: String,
            size: Float = 26f,
            always: Boolean = false,
            targetAlign: Int = Align.topRight,
            tipAlign: Int = Align.top,
            hideIcons: Boolean = false
        ) {
            if (!(always || GUI.keyboardAvailable) || text.isEmpty()) return

            val label = text.toLabel(BaseScreen.skinStrings.skinConfig.baseColor, 38, hideIcons = hideIcons)
            label.setAlignment(Align.center)

            val background = BaseScreen.skinStrings.getUiBackground("General/Tooltip", BaseScreen.skinStrings.roundedEdgeRectangleShape, Color.LIGHT_GRAY)
            // This controls text positioning relative to the background.
            // The minute fiddling makes both single caps and longer text look centered.
            @Suppress("SpellCheckingInspection")
            val skewPadDescenders = if (",;gjpqy".any { it in text }) 0f else 2.5f
            val horizontalPad = if (text.length > 1) 10f else 6f
            background.setPadding(4f+skewPadDescenders, horizontalPad, 8f-skewPadDescenders, horizontalPad)

            val widthHeightRatio: Float
            val multiRowSize = size * (1 + text.count { it == '\n' })
            val labelWithBackground = Container(label).apply {
                setBackground(background)
                pack()
                widthHeightRatio = width / height
                isTransform = true  // otherwise setScale is ignored
                setScale(multiRowSize / height)
            }

            addListener(UncivTooltip(this,
                labelWithBackground,
                forceContentSize = Vector2(multiRowSize * widthHeightRatio, multiRowSize),
                offset = Vector2(-multiRowSize/4, size/4),
                targetAlign = targetAlign,
                tipAlign = tipAlign
            ))
        }

        /**
         * Add a single Char [Label]-based Tooltip with a rounded-corner background to a [Table] or other [Group].
         *
         * Tip is positioned over top right corner, slightly overshooting the receiver widget.
         *
         * @param size _Vertical_ size of the entire Tooltip including background
         * @param always override requirement: presence of physical keyboard
         */
        fun Actor.addTooltip(char: Char, size: Float = 26f, always: Boolean = false) {
            addTooltip((if (char in "Ii") 'i' else char.uppercaseChar()).toString(), size, always)
        }

        /**
         * Add a [Label]-based Tooltip for a keyboard binding with a rounded-corner background to a [Table] or other [Group].
         *
         * Tip is positioned over top right corner, slightly overshooting the receiver widget.
         *
         * @param size _Vertical_ size of the entire Tooltip including background
         * @param always override requirement: presence of physical keyboard
         */
        fun Actor.addTooltip(key: KeyCharAndCode, size: Float = 26f, always: Boolean = false) {
            if (key != KeyCharAndCode.UNKNOWN)
                addTooltip(key.toString().tr(), size, always)
        }
    }
}
