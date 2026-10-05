package com.dpdpxray.core.screen

import com.dpdpxray.core.model.UiNode

object Fixtures {
    fun button(text: String) = UiNode(className = "android.widget.Button", text = text, isClickable = true)
    fun label(text: String) = UiNode(className = "android.widget.TextView", text = text)
    fun checkbox(text: String, checked: Boolean) =
        UiNode(className = "android.widget.CheckBox", text = text, isCheckable = true, isChecked = checked, isClickable = true)

    /** LeakyShop's deliberately bad consent dialog. */
    val leakyDialog = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            label("We value your privacy"),
            label("We use your data to give you the best offers."),
            checkbox("Share usage data to improve offers", checked = true),
            button("Accept & Continue"),
            UiNode(className = "android.widget.TextView", text = "Manage", isClickable = true),
        ),
    )

    /** LeakyShop's fixed consent dialog. */
    val fixedDialog = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            label("Your privacy choices"),
            label("We collect: email, device ID, purchase history. Purpose: order delivery and, only if you agree, personalised offers."),
            checkbox("Personalised offers (optional)", checked = false),
            label("You can withdraw consent anytime in Settings > Privacy."),
            label("View in: English · हिन्दी · ಕನ್ನಡ"),
            button("Reject"),
            button("Accept"),
        ),
    )

    val productGrid = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(label("Trending"), button("Add to cart"), label("₹499")),
    )
}
