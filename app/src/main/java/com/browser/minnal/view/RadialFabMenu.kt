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
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible

data class RadialFabMenuItem(
    @DrawableRes val iconRes: Int,
    val label: String,
    val contentDescription: String,
    val onClick: () -> Unit,
    @ColorRes val iconBackgroundColorRes: Int = R.color.accent_color,
    @ColorRes val labelTextColorRes: Int = R.color.white,
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
    private val fabBottomLift = resources.getDimensionPixelSize(R.dimen.radial_fab_bottom_lift)
    private val fabSize = resources.getDimensionPixelSize(R.dimen.radial_fab_size)
    private val itemIconSize = resources.getDimensionPixelSize(R.dimen.radial_fab_item_size)
    private val itemStackSpacing = resources.getDimensionPixelSize(R.dimen.radial_fab_stack_spacing)
    private val itemGapAboveFab = resources.getDimensionPixelSize(R.dimen.radial_fab_item_gap)

    val isMenuExpanded: Boolean
        get() = isExpanded

    private var menuItems: List<RadialFabMenuItem> = emptyList()
    private var isExpanded = false
    private var fabBottomMargin = fabMargin
    private var fabCenterX = 0f
    private var fabCenterY = 0f
    private var isMainFabHidden = true
    private var mainFabShowAnimator: ObjectAnimator? = null
    private var mainFabHideAnimator: ObjectAnimator? = null

    private val scrim = View(context).apply {
        setBackgroundColor(0x99000000.toInt())
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

    private val mainFab = ImageView(context).apply {
        setImageResource(R.drawable.fab_icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.fab_menu_toggle)
        setOnClickListener { onMainFabClick() }
        ViewCompat.setElevation(this, resources.getDimension(R.dimen.material_grid_unit) * 2)
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
            LayoutParams(fabSize, fabSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin)
            },
        )
        mainFab.alpha = 1f
        mainFab.translationY = 0f
        isMainFabHidden = false
    }

    /** Slide the main FAB in from below (e.g. after scroll or on first layout). */
    fun showMainFab(animate: Boolean = true) {
        if (!isMainFabHidden && mainFab.translationY == 0f && mainFab.alpha >= 1f) {
            return
        }
        if (isExpanded) {
            return
        }
        isMainFabHidden = false
        mainFabHideAnimator?.cancel()
        mainFab.isVisible = true
        mainFab.bringToFront()
        if (animate && mainFab.translationY < 1f) {
            mainFab.translationY = mainFabHiddenTranslationY()
        }
        if (animate) {
            mainFabShowAnimator?.cancel()
            mainFabShowAnimator = ObjectAnimator.ofFloat(mainFab, TRANSLATION_Y, mainFab.translationY, 0f).apply {
                duration = FAB_VISIBILITY_ANIMATION_DURATION
                interpolator = DecelerateInterpolator()
                start()
            }
            mainFab.animate()
                .alpha(1f)
                .setDuration(FAB_VISIBILITY_ANIMATION_DURATION)
                .start()
        } else {
            mainFab.translationY = 0f
            mainFab.alpha = 1f
        }
    }

    /** Slide the main FAB off-screen (e.g. while scrolling down in fullscreen). */
    fun hideMainFab(animate: Boolean = true) {
        if (isMainFabHidden || isExpanded) {
            return
        }
        collapse()
        isMainFabHidden = true
        mainFabShowAnimator?.cancel()
        val hiddenTranslationY = mainFabHiddenTranslationY()
        if (animate) {
            mainFabHideAnimator?.cancel()
            mainFabHideAnimator = ObjectAnimator.ofFloat(
                mainFab,
                TRANSLATION_Y,
                mainFab.translationY,
                hiddenTranslationY,
            ).apply {
                duration = FAB_VISIBILITY_ANIMATION_DURATION
                interpolator = DecelerateInterpolator()
                start()
            }
            mainFab.animate()
                .alpha(0f)
                .setDuration(FAB_VISIBILITY_ANIMATION_DURATION)
                .start()
        } else {
            applyMainFabHiddenState(animate = false)
        }
    }

    private fun applyMainFabHiddenState(animate: Boolean) {
        if (animate) {
            hideMainFab(animate = true)
            return
        }
        mainFab.translationY = mainFabHiddenTranslationY()
        mainFab.alpha = 0f
        isMainFabHidden = true
    }

    private fun mainFabHiddenTranslationY(): Float =
        (fabSize + fabBottomMargin + fabMargin).toFloat()

    fun setFabBottomInset(insetPx: Int) {
        fabBottomMargin = fabMargin + insetPx + fabBottomLift
        (mainFab.layoutParams as LayoutParams).bottomMargin = fabBottomMargin
        mainFab.requestLayout()
        updateFabCenter()
        if (isMainFabHidden) {
            mainFab.translationY = mainFabHiddenTranslationY()
        }
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
            binding.radialFabItemIconContainer.backgroundTintList =
                context.getColorStateList(item.iconBackgroundColorRes)
            binding.radialFabItemLabel.text = item.label
            binding.radialFabItemLabel.setTextColor(context.getColor(item.labelTextColorRes))
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
        animateMainFabToggle(expanded = false)

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
        animateMainFabToggle(expanded = true)

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

        val firstItemOffset =
            fabSize / 2f + itemGapAboveFab + itemIconSize / 2f

        return (0 until count).map { index ->
            val dy = -(firstItemOffset + index * itemStackSpacing.toFloat())
            fabCenterX to fabCenterY + dy
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

    /** Icon circle center (right edge of the label + icon row). */
    private fun iconAnchorOffset(view: View): Pair<Float, Float> {
        val width = itemSize(view).first.toFloat()
        val height = itemSize(view).second.toFloat()
        return (width - itemIconSize / 2f) to (height / 2f)
    }

    private fun itemSize(view: View): Pair<Int, Int> {
        measureMenuItem(view)
        val width = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val height = view.height.takeIf { it > 0 } ?: view.measuredHeight
        return width to height
    }

    private fun onMainFabClick() {
        val shouldExpand = !isExpanded
        mainFab.animate().cancel()
        mainFab.animate()
            .scaleX(MAIN_FAB_PULSE_SCALE)
            .scaleY(MAIN_FAB_PULSE_SCALE)
            .setDuration(MAIN_FAB_PULSE_DURATION)
            .withEndAction {
                mainFab.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(MAIN_FAB_PULSE_DURATION)
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .withEndAction {
                        if (shouldExpand) {
                            expand()
                        } else {
                            collapse()
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun animateMainFabToggle(expanded: Boolean) {
        val targetRotation = if (expanded) MAIN_FAB_EXPANDED_ROTATION else 0f
        ObjectAnimator.ofFloat(mainFab, ROTATION, mainFab.rotation, targetRotation).apply {
            duration = ANIMATION_DURATION
            interpolator = OvershootInterpolator(1.2f)
            start()
        }
        val targetScale = if (expanded) MAIN_FAB_EXPANDED_SCALE else 1f
        mainFab.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    companion object {
        private const val ANIMATION_DURATION = 280L
        private const val STAGGER_DELAY = 45L
        private const val FAB_VISIBILITY_ANIMATION_DURATION = 200L
        private const val MAIN_FAB_PULSE_DURATION = 90L
        private const val MAIN_FAB_PULSE_SCALE = 0.9f
        private const val MAIN_FAB_EXPANDED_ROTATION = 45f
        private const val MAIN_FAB_EXPANDED_SCALE = 1.06f
    }
}
