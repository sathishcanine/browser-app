package com.browser.minnal.view

import com.browser.minnal.R
import com.browser.minnal.databinding.RadialFabMenuItemBinding
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.FloatingActionButton

data class RadialFabMenuItem(
    @DrawableRes val iconRes: Int,
    val label: String,
    val contentDescription: String,
    val onClick: () -> Unit,
)

/**
 * A floating action button that expands into a radial menu with labeled actions.
 */
class RadialFabMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val fabMargin = resources.getDimensionPixelSize(R.dimen.radial_fab_margin)
    private val fabSize = resources.getDimensionPixelSize(R.dimen.radial_fab_size)
    private val itemIconSize = resources.getDimensionPixelSize(R.dimen.radial_fab_item_size)

    val isMenuExpanded: Boolean
        get() = isExpanded

    private var menuItems: List<RadialFabMenuItem> = emptyList()
    private var isExpanded = false
    private var fabBottomMargin = fabMargin
    private var fabCenterX = 0f
    private var fabCenterY = 0f

    private val scrim = View(context).apply {
        setBackgroundColor(0x66000000)
        alpha = 0f
        isVisible = false
        setOnClickListener { collapse() }
    }

    private val menuItemsContainer = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        clipToOutline = false
        isVisible = false
    }

    private val mainFab = FloatingActionButton(context).apply {
        setImageResource(R.drawable.ic_action_plus)
        backgroundTintList = context.getColorStateList(R.color.accent_color)
        imageTintList = context.getColorStateList(R.color.white)
        customSize = fabSize
        elevation = resources.getDimension(R.dimen.material_grid_unit) * 2
        setOnClickListener {
            if (isExpanded) {
                collapse()
            } else {
                expand()
            }
        }
    }

    private val menuItemViews = mutableListOf<View>()

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = false
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(menuItemsContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            mainFab,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin)
            },
        )
    }

    fun setFabBottomInset(insetPx: Int) {
        fabBottomMargin = fabMargin + insetPx
        (mainFab.layoutParams as LayoutParams).bottomMargin = fabBottomMargin
        mainFab.requestLayout()
        updateFabCenter()
        if (isExpanded) {
            repositionExpandedItems()
        }
    }

    fun setMenuItems(items: List<RadialFabMenuItem>) {
        menuItemViews.forEach { menuItemsContainer.removeView(it) }
        menuItemViews.clear()
        menuItems = items

        val inflater = LayoutInflater.from(context)
        items.forEach { item ->
            val binding = RadialFabMenuItemBinding.inflate(inflater, menuItemsContainer, false)
            binding.radialFabItemIcon.setImageResource(item.iconRes)
            binding.radialFabItemIcon.imageTintList = context.getColorStateList(R.color.white)
            binding.radialFabItemLabel.text = item.label
            binding.root.contentDescription = item.contentDescription
            binding.root.elevation = resources.getDimension(R.dimen.material_grid_unit) * 2
            binding.root.alpha = 0f
            binding.root.isVisible = false
            binding.root.setOnClickListener {
                collapse()
                item.onClick()
            }
            menuItemsContainer.addView(
                binding.root,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            menuItemViews.add(binding.root)
        }
    }

    fun collapse() {
        if (!isExpanded) {
            return
        }
        isExpanded = false
        mainFab.setImageResource(R.drawable.ic_action_plus)

        val animators = mutableListOf<Animator>()
        animators += ObjectAnimator.ofFloat(scrim, ALPHA, scrim.alpha, 0f).apply {
            duration = ANIMATION_DURATION
        }

        menuItemViews.forEachIndexed { index, view ->
            val startX = view.translationX
            val startY = view.translationY
            val endX = fabCenterX - iconAnchorOffset(view).first
            val endY = fabCenterY - iconAnchorOffset(view).second
            animators += ObjectAnimator.ofFloat(view, TRANSLATION_X, startX, endX).apply {
                duration = ANIMATION_DURATION
                startDelay = index * STAGGER_DELAY
            }
            animators += ObjectAnimator.ofFloat(view, TRANSLATION_Y, startY, endY).apply {
                duration = ANIMATION_DURATION
                startDelay = index * STAGGER_DELAY
            }
            animators += ObjectAnimator.ofFloat(view, ALPHA, view.alpha, 0f).apply {
                duration = ANIMATION_DURATION / 2
                startDelay = index * STAGGER_DELAY
            }
        }

        AnimatorSet().apply {
            playTogether(animators)
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scrim.isVisible = false
                    menuItemsContainer.isVisible = false
                    menuItemViews.forEach { it.isVisible = false }
                }
            })
            start()
        }
    }

    fun expand() {
        if (isExpanded || menuItems.isEmpty() || width == 0 || height == 0) {
            if (!isExpanded && menuItems.isNotEmpty() && (width == 0 || height == 0)) {
                post { expand() }
            }
            return
        }
        isExpanded = true
        mainFab.setImageResource(R.drawable.ic_action_close)

        updateFabCenter()
        scrim.isVisible = true
        menuItemsContainer.isVisible = true

        menuItemViews.forEach { it.isVisible = true }
        layoutMenuItemsAtFabCenter()
        menuItemsContainer.bringToFront()
        mainFab.bringToFront()

        val positions = computeItemPositions()
        menuItemViews.forEachIndexed { index, view ->
            view.alpha = 0f

            val (targetX, targetY) = positions[index]
            val (anchorX, anchorY) = iconAnchorOffset(view)
            val targetTranslationX = targetX - anchorX
            val targetTranslationY = targetY - anchorY
            val startTranslationX = view.translationX
            val startTranslationY = view.translationY

            ObjectAnimator.ofFloat(view, TRANSLATION_X, startTranslationX, targetTranslationX).apply {
                duration = ANIMATION_DURATION
                startDelay = index * STAGGER_DELAY
                interpolator = DecelerateInterpolator()
                start()
            }
            ObjectAnimator.ofFloat(view, TRANSLATION_Y, startTranslationY, targetTranslationY).apply {
                duration = ANIMATION_DURATION
                startDelay = index * STAGGER_DELAY
                interpolator = DecelerateInterpolator()
                start()
            }
            ObjectAnimator.ofFloat(view, ALPHA, 0f, 1f).apply {
                duration = ANIMATION_DURATION
                startDelay = index * STAGGER_DELAY + ANIMATION_DURATION / 4
                start()
            }
        }

        ObjectAnimator.ofFloat(scrim, ALPHA, 0f, 1f).apply {
            duration = ANIMATION_DURATION
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFabCenter()
        if (isExpanded) {
            repositionExpandedItems()
        }
    }

    private fun updateFabCenter() {
        fabCenterX = width - fabMargin - fabSize / 2f
        fabCenterY = height - fabBottomMargin - fabSize / 2f
    }

    private fun layoutMenuItemsAtFabCenter() {
        menuItemViews.forEach { view ->
            measureMenuItem(view)
            if (view.measuredWidth == 0 || view.measuredHeight == 0) {
                return@forEach
            }
            view.layoutParams = FrameLayout.LayoutParams(view.measuredWidth, view.measuredHeight)
            val (anchorX, anchorY) = iconAnchorOffset(view)
            view.translationX = fabCenterX - anchorX
            view.translationY = fabCenterY - anchorY
        }
    }

    private fun measureMenuItem(view: View) {
        val spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(spec, spec)
    }

    private fun computeItemPositions(): List<Pair<Float, Float>> {
        val count = menuItems.size
        if (count == 0) {
            return emptyList()
        }

        val stepX = resources.getDimension(R.dimen.radial_fab_step_x)
        val stepY = resources.getDimension(R.dimen.radial_fab_step_y)
        val homeOffsetX = fabSize + itemIconSize + fabMargin.toFloat()
        val topLimit = paddingTop + fabMargin
        val availableHeight = (fabCenterY - fabSize / 2f - fabMargin - topLimit).coerceAtLeast(stepY)
        val rowStep = (availableHeight / count).coerceIn(stepY * 0.85f, stepY * 1.2f)

        val horizontalFactors = listOf(0f, 0.15f, 0.55f, 0.25f, 0.05f)

        return (0 until count).map { index ->
            val dx = -homeOffsetX - stepX * horizontalFactors.getOrElse(index) { 0.05f * index }
            val dy = if (index == 0) 0f else -rowStep * index
            fabCenterX + dx to fabCenterY + dy
        }
    }

    private fun repositionExpandedItems() {
        layoutMenuItemsAtFabCenter()
        val positions = computeItemPositions()
        menuItemViews.forEachIndexed { index, view ->
            if (index >= positions.size) {
                return@forEachIndexed
            }
            val (targetX, targetY) = positions[index]
            val (anchorX, anchorY) = iconAnchorOffset(view)
            view.translationX = targetX - anchorX
            view.translationY = targetY - anchorY
        }
    }

    /** Icon circle center (top of the vertical icon + label column). */
    private fun iconAnchorOffset(view: View): Pair<Float, Float> {
        val width = itemSize(view).first.toFloat()
        return (width / 2f) to (itemIconSize / 2f)
    }

    private fun itemSize(view: View): Pair<Int, Int> {
        measureMenuItem(view)
        val width = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val height = view.height.takeIf { it > 0 } ?: view.measuredHeight
        return width to height
    }

    companion object {
        private const val ANIMATION_DURATION = 280L
        private const val STAGGER_DELAY = 45L
    }
}
