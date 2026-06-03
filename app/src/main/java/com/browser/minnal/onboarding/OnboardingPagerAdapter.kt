package com.browser.minnal.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.browser.minnal.R

internal class OnboardingPagerAdapter(
    private val pages: List<OnboardingPage>,
) : RecyclerView.Adapter<OnboardingPagerAdapter.PageViewHolder>() {

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_onboarding_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val illustration = itemView.findViewById<ImageView>(R.id.onboarding_illustration)
        private val title = itemView.findViewById<TextView>(R.id.onboarding_page_title)
        private val description = itemView.findViewById<TextView>(R.id.onboarding_page_description)
        private val illustrationFrame =
            itemView.findViewById<View>(R.id.onboarding_illustration_frame)

        fun bind(page: OnboardingPage) {
            title.setText(page.titleRes)
            description.setText(page.descriptionRes)
            illustration.setImageResource(page.illustrationRes)
            illustrationFrame.setBackgroundResource(
                if (page.usePhotoStyle) {
                    R.drawable.onboarding_photo_frame
                } else {
                    R.drawable.onboarding_illustration_frame
                },
            )
            val padding = itemView.resources.getDimensionPixelSize(
                if (page.usePhotoStyle) {
                    R.dimen.onboarding_photo_padding
                } else {
                    R.dimen.onboarding_illustration_padding
                },
            )
            illustration.setPadding(padding, padding, padding, padding)
        }
    }
}
