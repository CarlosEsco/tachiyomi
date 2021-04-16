package eu.kanade.tachiyomi.ui.library.display

import android.view.View
import android.view.View.inflate
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.setting.SettingsLibraryController
import eu.kanade.tachiyomi.util.view.compatToolTipText
import eu.kanade.tachiyomi.util.view.visible
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.TabbedBottomSheetDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class TabbedLibraryDisplaySheet(val controller: LibraryController) :
    TabbedBottomSheetDialog(controller.activity!!) {

    private val displayView: LibraryDisplayView = inflate(controller.activity!!, R.layout.library_display_layout, null) as LibraryDisplayView
    private val badgesView: LibraryBadgesView = inflate(controller.activity!!, R.layout.library_badges_layout, null) as LibraryBadgesView
    private val categoryView: LibraryCategoryView = inflate(controller.activity!!, R.layout.library_category_layout, null) as LibraryCategoryView

    init {
        displayView.controller = controller
        badgesView.controller = controller
        categoryView.controller = controller
        binding.menu.visible()
        binding.menu.compatToolTipText = context.getString(R.string.more_library_settings)
        binding.menu.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                R.drawable.ic_settings_24dp
            )
        )
        binding.menu.setOnClickListener {
            controller.router.pushController(SettingsLibraryController().withFadeTransaction())
            dismiss()
        }

        setExpandText(
            !controller.singleCategory && controller.presenter.showAllCategories,
            Injekt.get<PreferencesHelper>().collapsedCategories().getOrDefault().isNotEmpty(),
            false
        )
    }

    fun setExpandText(showExpanded: Boolean, allExpanded: Boolean, animated: Boolean = true) {
        categoryView.showExpandCategories(showExpanded)
        categoryView.setExpandText(allExpanded, animated)
    }

    override fun dismiss() {
        super.dismiss()
        controller.displaySheet = null
    }

    override fun getTabViews(): List<View> = listOf(
        displayView,
        badgesView,
        categoryView
    )

    override fun getTabTitles(): List<Int> = listOf(
        R.string.display,
        R.string.badges,
        R.string.more
    )
}