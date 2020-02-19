package eu.kanade.tachiyomi.ui.library

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.math.MathUtils.clamp
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.afollestad.materialdialogs.MaterialDialog
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.f2prateek.rx.preferences.Preference
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.jakewharton.rxrelay.BehaviorRelay
import com.jakewharton.rxrelay.PublishRelay
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.download.DownloadServiceListener
import eu.kanade.tachiyomi.data.library.LibraryServiceListener
import eu.kanade.tachiyomi.data.library.LibraryUpdateService
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.ui.base.controller.TabbedController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.category.CategoryController
import eu.kanade.tachiyomi.ui.download.DownloadController
import eu.kanade.tachiyomi.ui.library.filter.SortFilterBottomSheet
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.migration.MigrationController
import eu.kanade.tachiyomi.ui.migration.MigrationInterface
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.migration.manga.process.MigrationListController
import eu.kanade.tachiyomi.ui.migration.manga.process.MigrationProcedureConfig
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.inflate
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.updatePaddingRelative
import eu.kanade.tachiyomi.util.view.visible
import eu.kanade.tachiyomi.widget.AutofitRecyclerView
import eu.kanade.tachiyomi.widget.IgnoreFirstSpinnerListener
import kotlinx.android.synthetic.main.filter_bottom_sheet.*
import kotlinx.android.synthetic.main.library_controller.*
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.coroutines.delay
import rx.Subscription
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import kotlin.math.min

class LibraryController(
        bundle: Bundle? = null,
        private val preferences: PreferencesHelper = Injekt.get()
) : BaseController(bundle), TabbedController,
        ActionMode.Callback,
        ChangeMangaCategoriesDialog.Listener,
        MigrationInterface,
        DownloadServiceListener,
        LibraryServiceListener,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    FlexibleAdapter.OnItemMoveListener,
        LibraryCategoryAdapter.LibraryListener{

    /**
     * Position of the active category.
     */
    var activeCategory: Int = preferences.lastUsedCategory().getOrDefault()
        private set

    /**
     * Action mode for selections.
     */
    private var actionMode: ActionMode? = null

    /**
     * Library search query.
     */
    private var query = ""

    /**
     * Currently selected mangas.
     */
    val selectedMangas = mutableSetOf<Manga>()

    /**
     * Current mangas to move.
     */
    private var migratingMangas = mutableSetOf<Manga>()

    /**
     * Relay to notify the UI of selection updates.
     */
    val selectionRelay: PublishRelay<LibrarySelectionEvent> = PublishRelay.create()

    /**
     * Relay to notify search query changes.
     */
    val searchRelay: BehaviorRelay<String> = BehaviorRelay.create()

    /**
     * Relay to notify the library's viewpager for updates.
     */
    val libraryMangaRelay: BehaviorRelay<LibraryMangaEvent> = BehaviorRelay.create()

    /**
     * Relay to notify the library's viewpager to select all manga
     */
    val selectAllRelay: PublishRelay<Int> = PublishRelay.create()

    /**
     * Relay to notify the library's viewpager to reotagnize all
     */
    val reorganizeRelay: PublishRelay<Pair<Int, Int>> = PublishRelay.create()

    /**
     * Number of manga per row in grid mode.
     */
    var mangaPerRow = 0
        private set

    /**
     * Adapter of the view pager.
     */
    private var pagerAdapter: LibraryAdapter? = null
    private lateinit var adapter: LibraryCategoryAdapter

    private lateinit var spinner: Spinner

    private var lastClickPosition = -1

    /**
     * Drawer listener to allow swipe only for closing the drawer.
     */
    private var tabsVisibilityRelay: BehaviorRelay<Boolean> = BehaviorRelay.create(false)

    private var tabsVisibilitySubscription: Subscription? = null

    private var observeLater:Boolean = false

    var snack: Snackbar? = null

    var presenter = LibraryPresenter(this)
        private set

    private var justStarted = true

    private var updateScroll = true

    private var spinnerAdapter: SpinnerAdapter? = null

    private var scrollListener = object : RecyclerView.OnScrollListener () {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            val position =
                (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            val order = when (val item = adapter.getItem(position)) {
                is LibraryHeaderItem -> item.category.order
                is LibraryItem -> presenter.categories.find { it.id == item.manga.category }?.order
                else -> null
            }
            if (order != null && order != activeCategory) {
                preferences.lastUsedCategory().set(order)
                activeCategory = order
                val category = presenter.categories.find { it.order == order }

                bottom_sheet.lastCategory = category
                bottom_sheet.updateTitle()
                if (spinner.selectedItemPosition != order + 1) {
                    updateScroll = true
                    spinner.setSelection(order + 1, true)
                }
            }
        }
    }

    /**
     * Recycler view of the list of manga.
     */
    private lateinit var recycler: RecyclerView

    var libraryLayout = preferences.libraryLayout().getOrDefault()

    private var usePager: Boolean = !preferences.libraryAsSingleList().getOrDefault()

    init {
        setHasOptionsMenu(true)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun getTitle(): String? {
        return if (usePager) resources?.getString(R.string.label_library) else null
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.library_controller, container, false)
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        mangaPerRow = getColumnsPreferenceForCurrentOrientation().getOrDefault()

        if (usePager) {
            pager_layout.visible()
            fast_scroller.gone()
            pagerAdapter = LibraryAdapter(this)
            library_pager.adapter = pagerAdapter
            library_pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageSelected(position: Int) {
                    preferences.lastUsedCategory().set(position)
                    activeCategory = position
                    bottom_sheet.lastCategory = pagerAdapter?.categories?.getOrNull(position)
                    if (preferences.librarySortingMode().getOrDefault() == LibrarySort.DRAG_AND_DROP) bottom_sheet.updateTitle()
                }

                override fun onPageScrolled(
                    position: Int, positionOffset: Float, positionOffsetPixels: Int
                ) {
                }

                override fun onPageScrollStateChanged(state: Int) {}
            })
        }
        else {
            adapter = LibraryCategoryAdapter(this)
            recycler = (recycler_layout.inflate(R.layout.library_grid_recycler) as
                AutofitRecyclerView).apply {
                spanCount = if (libraryLayout == 0) 1 else mangaPerRow
                manager.spanSizeLookup = (object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        if (libraryLayout == 0) return 1
                        val item = this@LibraryController.adapter.getItem(position)
                        return if (item is LibraryHeaderItem) manager.spanCount else 1
                    }
                })
            }

            recycler.setHasFixedSize(true)
            recycler.adapter = adapter
            recycler_layout.addView(recycler)
            adapter.fastScroller = fast_scroller
            recycler.addOnScrollListener(scrollListener)

            spinner = ReSpinner(view.context)
            (activity as MainActivity).supportActionBar?.customView = spinner
            (activity as MainActivity).supportActionBar?.setDisplayShowCustomEnabled(true)
            spinnerAdapter = SpinnerAdapter(view.context, R.layout.library_spinner_textview,
                arrayOf(resources!!.getString(R.string.label_library)))
            spinnerAdapter?.setDropDownViewResource(R.layout.library_spinner_entry_text)
            spinner.adapter = spinnerAdapter
        }


        if (selectedMangas.isNotEmpty()) {
            createActionModeIfNeeded()
        }

        //bottom_sheet.onCreate(pager_layout)
        bottom_sheet.onCreate(if (usePager) pager_layout else recycler_layout)

        bottom_sheet.onGroupClicked = {
            when (it) {
                SortFilterBottomSheet.ACTION_REFRESH -> onRefresh()
                SortFilterBottomSheet.ACTION_FILTER -> onFilterChanged()
                SortFilterBottomSheet.ACTION_SORT -> onSortChanged()
                SortFilterBottomSheet.ACTION_DISPLAY -> reattachAdapter()
                SortFilterBottomSheet.ACTION_DOWNLOAD_BADGE -> presenter.requestDownloadBadgesUpdate()
                SortFilterBottomSheet.ACTION_UNREAD_BADGE -> presenter.requestUnreadBadgesUpdate()
                SortFilterBottomSheet.ACTION_CAT_SORT -> onCatSortChanged()
            }
        }

        fab.setOnClickListener {
            router.pushController(DownloadController().withFadeTransaction())
        }

        val config = resources?.configuration
        val phoneLandscape = (config?.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (config.screenLayout.and(Configuration.SCREENLAYOUT_SIZE_MASK)) <
            Configuration.SCREENLAYOUT_SIZE_LARGE)

        // pad the recycler if the filter bottom sheet is visible
        if (!usePager && !phoneLandscape) {
            val height = view.context.resources.getDimensionPixelSize(R.dimen.rounder_radius) + 4.dpToPx
            recycler.updatePaddingRelative(bottom = height)
        }

        if (presenter.isDownloading()) {
            fab.scaleY = 1f
            fab.scaleX = 1f
            fab.isClickable = true
            fab.isFocusable = true
        }
        presenter.onRestore()
        val library = presenter.getAllManga()
        if (library != null)  presenter.updateViewBlocking() //onNextLibraryUpdate(presenter.categories, library)
        else {
            library_pager.alpha = 0f
            recycler_layout.alpha = 0f
            presenter.getLibraryBlocking()
        }
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            if (!usePager)
                (activity as MainActivity).supportActionBar?.setDisplayShowCustomEnabled(true)
            else
                activity?.tabs?.setupWithViewPager(library_pager)
            presenter.getLibrary()
            DownloadService.addListener(this)
            DownloadService.callListeners()
            LibraryUpdateService.setListener(this)
        }
        else if (type == ControllerChangeType.PUSH_EXIT) {
            (activity as MainActivity).toolbar.menu.findItem(R.id
                .action_search)?.collapseActionView()
            (activity as MainActivity).supportActionBar?.setDisplayShowCustomEnabled(false)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        if (observeLater) {
            presenter.getLibrary()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        super.onActivityPaused(activity)
        observeLater = true
        presenter.onDestroy()
    }

    override fun onDestroy() {
        (activity as MainActivity).supportActionBar?.setDisplayShowCustomEnabled(false)
        presenter.onDestroy()
        super.onDestroy()
    }

    override fun onDestroyView(view: View) {
        pagerAdapter?.onDestroy()
        DownloadService.removeListener(this)
        LibraryUpdateService.removeListener()
        pagerAdapter = null
        actionMode = null
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = null
        super.onDestroyView(view)
    }

    override fun downloadStatusChanged(downloading: Boolean) {
        launchUI {
            val scale = if (downloading) 1f else 0f
            val fab = fab ?: return@launchUI
            fab.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
            fab.isClickable = downloading
            fab.isFocusable = downloading
            bottom_sheet?.adjustTitleMargin(downloading)
        }
    }

    override fun onUpdateManga(manga: LibraryManga) {
        presenter.updateManga(manga)
    }

    override fun onDetach(view: View) {
        destroyActionModeIfNeeded()
        snack?.dismiss()
        snack = null
        super.onDetach(view)
    }

    override fun configureTabs(tabs: TabLayout) {
        with(tabs) {
            tabGravity = TabLayout.GRAVITY_CENTER
            tabMode = TabLayout.MODE_SCROLLABLE
        }
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = tabsVisibilityRelay.subscribe { visible ->
            val tabAnimator = (activity as? MainActivity)?.tabAnimator ?: return@subscribe
            if (visible) {
                tabAnimator.expand()
            } else if (!visible) {
                tabAnimator.collapse()
            }
        }
    }

    override fun cleanupTabs(tabs: TabLayout) {
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = null
    }

    fun onNextLibraryUpdate(mangaMap: List<LibraryItem>, freshStart: Boolean = false) {
        if (mangaMap.isNotEmpty()) {
            empty_view.hide()
        } else {
            empty_view.show(R.drawable.ic_book_black_128dp, R.string.information_empty_library)
        }
        adapter.setItems(mangaMap)


        spinner.onItemSelectedListener = null
        spinnerAdapter = SpinnerAdapter(view!!.context, R.layout.library_spinner_textview,
            presenter.categories.map { it.name }.toTypedArray())
        spinnerAdapter?.setDropDownViewResource(R.layout.library_spinner_entry_text)
        spinner.adapter = spinnerAdapter


        spinner.setSelection(min(presenter.categories.size - 1, activeCategory + 1))
        if (!freshStart) {
            justStarted = false
            if (recycler_layout.alpha == 0f)
                recycler_layout.animate().alpha(1f).setDuration(500).start()


        }else {
            val position = if (freshStart) adapter.indexOf(activeCategory) else null
            if (position != null)
                (recycler.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(position, (-30).dpToPx)
        }
        adapter.isLongPressDragEnabled = canDrag()
        tabsVisibilityRelay.call(false)

        bottom_sheet.lastCategory = presenter.categories[clamp(activeCategory,
            0,
            presenter.categories.size - 1)]
        bottom_sheet.updateTitle()
        updateScroll = false
        spinner.onItemSelectedListener = IgnoreFirstSpinnerListener { pos ->
            if (updateScroll) {
                updateScroll = false
                return@IgnoreFirstSpinnerListener
            }
            val headerPosition = adapter.indexOf(pos - 1)
            if (headerPosition > -1) {
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                    headerPosition, (-30).dpToPx
                )
            }
        }
    }

    fun onNextLibraryUpdate(categories: List<Category>, mangaMap: Map<Int, List<LibraryItem>>,
        freshStart: Boolean = false) {
        val view = view ?: return
        val adapter = pagerAdapter ?: return

        // Show empty view if needed
        if (mangaMap.isNotEmpty()) {
            empty_view.hide()
        } else {
            empty_view.show(R.drawable.ic_book_black_128dp, R.string.information_empty_library)
        }

        // Get the current active category.
        val activeCat = if (adapter.categories.isNotEmpty())
            library_pager.currentItem
        else
            activeCategory

        categories.find { it.id == 0 }?.let {
            it.name = resources?.getString(
                if (categories.size == 1) R.string.pref_category_library
                else R.string.default_columns
            ) ?: "Default"
        }
        // Set the categories
        adapter.categories = categories

        // Restore active category.
        library_pager.setCurrentItem(activeCat, false)

        bottom_sheet.lastCategory = adapter.categories.getOrNull(activeCat)
        bottom_sheet.updateTitle()

        tabsVisibilityRelay.call(categories.size > 1)

        if (freshStart || !justStarted) {
            // Delay the scroll position to allow the view to be properly measured.
            view.post {
                if (isAttached) {
                    activity?.tabs?.setScrollPosition(library_pager.currentItem, 0f, true)
                }
            }

            // Send the manga map to child fragments after the adapter is updated.
            libraryMangaRelay.call(LibraryMangaEvent(mangaMap))
        }
        else if (!freshStart) {
            justStarted = false
            if (library_pager.alpha == 0f)
                library_pager.animate().alpha(1f).setDuration(500).start()
        }
    }

    /**
     * Returns a preference for the number of manga per row based on the current orientation.
     *
     * @return the preference.
     */
    private fun getColumnsPreferenceForCurrentOrientation(): Preference<Int> {
        return if (resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT)
            preferences.portraitColumns()
        else
            preferences.landscapeColumns()
    }

    /**
     * Called when a filter is changed.
     */
    private fun onFilterChanged() {
        activity?.invalidateOptionsMenu()
        presenter.requestFilterUpdate()
        destroyActionModeIfNeeded()
    }

    private fun onRefresh() {
        activity?.invalidateOptionsMenu()
        presenter.getLibrary()
        destroyActionModeIfNeeded()
    }

    /**
     * Called when the sorting mode is changed.
     */
    private fun onSortChanged() {
        presenter.requestSortUpdate()
        destroyActionModeIfNeeded()
    }

    fun onCatSortChanged(id: Int? = null) {
        val catId =
            (if (usePager)(id ?: pagerAdapter?.categories?.getOrNull(library_pager.currentItem)?.id)
            else (id ?: presenter.categories.find { it.order == activeCategory }?.id))
                ?: return
         presenter.requestCatSortUpdate(catId)
    }

    /**
     * Reattaches the adapter to the view pager to recreate fragments
     */
    private fun reattachAdapter() {
        if (usePager) {
            val adapter = pagerAdapter ?: return

            val position = library_pager.currentItem

            adapter.recycle = false
            library_pager.adapter = adapter
            library_pager.currentItem = position
            adapter.recycle = true
        }
        else {
            val position =
                (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            libraryLayout = preferences.libraryLayout().getOrDefault()
            recycler.adapter = adapter
            (recycler as? AutofitRecyclerView)?.spanCount = if (libraryLayout == 0) 1 else mangaPerRow

            (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
        }
    }

    /**
     * Creates the action mode if it's not created already.
     */
    fun createActionModeIfNeeded() {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(this)
            val view = activity?.window?.currentFocus ?: return
            val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as?
            InputMethodManager ?: return
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    /**
     * Destroys the action mode.
     */
    private fun destroyActionModeIfNeeded() {
        actionMode?.finish()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.library, menu)

        val config = resources?.configuration

        val phoneLandscape = (config?.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (config.screenLayout.and(Configuration.SCREENLAYOUT_SIZE_MASK)) <
            Configuration.SCREENLAYOUT_SIZE_LARGE)
        menu.findItem(R.id.action_library_filter).isVisible = phoneLandscape

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = resources?.getString(R.string.search_hint)

        searchItem.collapseActionView()
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, true)
            searchView.clearFocus()
        }

        // Mutate the filter icon because it needs to be tinted and the resource is shared.
        menu.findItem(R.id.action_library_filter).icon.mutate()

        setOnQueryTextChangeListener(searchView) {
            query = it ?: ""
            searchRelay.call(query)
            adapter.setFilter(it)
            adapter.performFilter()
            true
        }
        searchItem.fixExpand(onExpand = { invalidateMenuOnExpand() })
    }

    fun search(query:String) {
        this.query = query
    }

    override fun handleRootBack(): Boolean {
        val sheetBehavior = BottomSheetBehavior.from(bottom_sheet)
        if (sheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
            sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            return true
        }
        return false
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val navView = bottom_sheet ?: return

        val filterItem = menu.findItem(R.id.action_library_filter)

        // Tint icon if there's a filter active
        val filterColor = if (navView.hasActiveFilters()) Color.rgb(255, 238, 7)
        else activity?.getResourceColor(R.attr.actionBarTintColor) ?: Color.WHITE
        DrawableCompat.setTint(filterItem.icon, filterColor)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> expandActionViewFromInteraction = true
            R.id.action_library_filter -> {
                if (bottom_sheet.sheetBehavior?.state != BottomSheetBehavior.STATE_COLLAPSED)
                    bottom_sheet.sheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                else bottom_sheet.sheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
            }
            R.id.action_edit_categories -> {
                router.pushController(CategoryController().withFadeTransaction())
            }
            R.id.action_source_migration -> {
                router.pushController(MigrationController().withFadeTransaction())
            }
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    /**
     * Invalidates the action mode, forcing it to refresh its content.
     */
    fun invalidateActionMode() {
        actionMode?.invalidate()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.library_selection, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = selectedMangas.size
        if (count == 0) {
            // Destroy action mode if there are no items selected.
            destroyActionModeIfNeeded()
        } else {
            mode.title = resources?.getString(R.string.label_selected, count)
            if (preferences.librarySortingMode().getOrDefault() == LibrarySort.DRAG_AND_DROP) {
                val catId = (selectedMangas.first() as? LibraryManga)?.category
                val sameCat = /*(adapter?.categories?.getOrNull(library_pager.currentItem)?.id
                    == catId) &&*/ selectedMangas.all { (it as? LibraryManga)?.category == catId }
                menu.findItem(R.id.action_move_manga).isVisible = sameCat
            }
            else menu.findItem(R.id.action_move_manga).isVisible = false
        }
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_move_to_category -> showChangeMangaCategoriesDialog()
            R.id.action_delete -> {
                MaterialDialog(activity!!)
                    .message(R.string.confirm_manga_deletion)
                    .positiveButton(R.string.action_remove) {
                        deleteMangasFromLibrary()
                    }
                    .negativeButton(android.R.string.no)
                    .show()
            }
            /*R.id.action_select_all -> {
                adapter?.categories?.getOrNull(library_pager.currentItem)?.id?.let {
                    selectAllRelay.call(it)
                }
            }*/
            R.id.action_migrate -> {
                router.pushController(
                    if (preferences.skipPreMigration().getOrDefault()) {
                        MigrationListController.create(
                            MigrationProcedureConfig(
                                selectedMangas.mapNotNull { it.id },null)
                        )
                    }
                    else {
                        PreMigrationController.create( selectedMangas.mapNotNull { it.id } )
                    }
                   .withFadeTransaction())
                destroyActionModeIfNeeded()
            }
            /*R.id.action_to_top, R.id.action_to_bottom -> {
                adapter?.categories?.getOrNull(library_pager.currentItem)?.id?.let {
                    reorganizeRelay.call(it to if (item.itemId == R.id.action_to_top) -1 else -2)
                }
                destroyActionModeIfNeeded()
            }*/
            else -> return false
        }
        return true
    }

    override fun migrateManga(prevManga: Manga, manga: Manga, replace: Boolean): Manga? {
        if (manga.id != prevManga.id) {
            presenter.migrateManga(prevManga, manga, replace = replace)
        }
        val nextManga = migratingMangas.firstOrNull() ?: return null
        migratingMangas.remove(nextManga)
        return nextManga
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        // Clear all the manga selections and notify child views.
        selectedMangas.clear()
        adapter.mode = SelectableAdapter.Mode.SINGLE
        adapter.clearSelection()
        adapter.notifyDataSetChanged()
        lastClickPosition = -1
        adapter.isLongPressDragEnabled = canDrag()
        selectionRelay.call(LibrarySelectionEvent.Cleared())
        actionMode = null
    }

    fun openManga(manga: Manga, startY: Float?) {
        router.pushController(MangaController(manga, startY).withFadeTransaction())
    }

    /**
     * Sets the selection for a given manga.
     *
     * @param manga the manga whose selection has changed.
     * @param selected whether it's now selected or not.
     */
    fun setSelection(manga: Manga, selected: Boolean) {
        if (selected) {
            if (selectedMangas.add(manga)) {
                if (usePager) selectionRelay.call(LibrarySelectionEvent.Selected(manga))
                else {
                    val position = adapter.indexOf(manga)
                    if (adapter.mode != SelectableAdapter.Mode.MULTI) {
                        adapter.mode = SelectableAdapter.Mode.MULTI
                    }
                    launchUI {
                        delay(100)
                        adapter.isLongPressDragEnabled = false
                    }
                    adapter.toggleSelection(position)
                    (recycler.findViewHolderForItemId(manga.id!!) as? LibraryHolder)?.toggleActivation()
                }
            }
        } else {
            if (selectedMangas.remove(manga)) {
                if (usePager) selectionRelay.call(LibrarySelectionEvent.Unselected(manga))
                else {
                    val position = adapter.indexOf(manga)
                    lastClickPosition = -1
                    if (selectedMangas.isEmpty()) {
                        adapter.mode = SelectableAdapter.Mode.SINGLE
                        adapter.isLongPressDragEnabled = canDrag()
                    }
                    adapter.toggleSelection(position)
                    (recycler.findViewHolderForItemId(manga.id!!) as? LibraryHolder)?.toggleActivation()
                }
            }
        }
    }

    /**
     * Move the selected manga to a list of categories.
     */
    private fun showChangeMangaCategoriesDialog() {
        // Create a copy of selected manga
        val mangas = selectedMangas.toList()

        // Hide the default category because it has a different behavior than the ones from db.
        val categories = presenter.allCategories.filter { it.id != 0 }

        // Get indexes of the common categories to preselect.
        val commonCategoriesIndexes = presenter.getCommonCategories(mangas)
                .map { categories.indexOf(it) }
                .toTypedArray()

        ChangeMangaCategoriesDialog(this, mangas, categories, commonCategoriesIndexes)
                .showDialog(router)
    }

    private fun deleteMangasFromLibrary() {
        val mangas = selectedMangas.toList()
        presenter.removeMangaFromLibrary(mangas)
        destroyActionModeIfNeeded()
        snack?.dismiss()
        snack = snackbar_layout?.snack(activity?.getString(R.string.manga_removed_library) ?: "", Snackbar
            .LENGTH_INDEFINITE)  {
            var undoing = false
            setAction(R.string.action_undo) {
                presenter.addMangas(mangas)
                undoing = true
            }
            addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    super.onDismissed(transientBottomBar, event)
                    if (!undoing)
                        presenter.confirmDeletion(mangas)
                }
            })
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    override fun updateCategoriesForMangas(mangas: List<Manga>, categories: List<Category>) {
        presenter.moveMangasToCategories(categories, mangas)
        destroyActionModeIfNeeded()
    }

    /// Method for single list
    override fun startReading(position: Int) {
        if (adapter.mode == SelectableAdapter.Mode.MULTI) {
            toggleSelection(position)
            return
        }
        val manga = (adapter.getItem(position) as? LibraryItem)?.manga ?: return
        startReading(manga)
    }

    /// Method for the category view
    fun startReading(manga: Manga) {
        val activity = activity ?: return
        val chapter = presenter.getFirstUnread(manga) ?: return
        val intent = ReaderActivity.newIntent(activity, manga, chapter)
        destroyActionModeIfNeeded()
        startActivity(intent)
    }

    override fun canDrag(): Boolean {
        val filterOff = preferences.filterCompleted().getOrDefault() +
            preferences.filterTracked().getOrDefault() +
            preferences.filterUnread().getOrDefault() +
            preferences.filterMangaType().getOrDefault() +
            preferences.filterCompleted().getOrDefault() == 0 &&
            !preferences.hideCategories().getOrDefault()
        return filterOff && adapter.mode != SelectableAdapter.Mode.MULTI
    }

    /**
     * Called when a manga is clicked.
     *
     * @param position the position of the element clicked.
     * @return true if the item should be selected, false otherwise.
     */
    override fun onItemClick(view: View?, position: Int): Boolean {
        // If the action mode is created and the position is valid, toggle the selection.
        val item = adapter.getItem(position) as? LibraryItem ?: return false
        return if (adapter.mode == SelectableAdapter.Mode.MULTI) {
            lastClickPosition = position
            toggleSelection(position)
            true
        } else {
            openManga(item.manga, null)
            false
        }
    }

    /**
     * Called when a manga is long clicked.
     *
     * @param position the position of the element clicked.
     */
    override fun onItemLongClick(position: Int) {
        createActionModeIfNeeded()
        when {
            lastClickPosition == -1 -> setSelection(position)
            lastClickPosition > position -> for (i in position until lastClickPosition)
                setSelection(i)
            lastClickPosition < position -> for (i in lastClickPosition + 1..position)
                setSelection(i)
            else -> setSelection(position)
        }
        lastClickPosition = position
    }

    override fun onActionStateChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        val position = viewHolder?.adapterPosition ?: return
        if (actionState == 2) onItemLongClick(position)
    }

    /**
     * Tells the presenter to toggle the selection for the given position.
     *
     * @param position the position to toggle.
     */
    private fun toggleSelection(position: Int) {
        val item = adapter.getItem(position) as? LibraryItem ?: return

        setSelection(item.manga, !adapter.isSelected(position))
        invalidateActionMode()
    }


    /**
     * Tells the presenter to set the selection for the given position.
     *
     * @param position the position to toggle.
     */
    private fun setSelection(position: Int) {
        val item = adapter.getItem(position) as? LibraryItem ?: return

        setSelection(item.manga, true)
        invalidateActionMode()
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) { }

    override fun onItemReleased(position: Int) {
        if (adapter.selectedItemCount > 0) return
        val item = adapter.getItem(position) as? LibraryItem ?: return
        val newHeader = adapter.getSectionHeader(position) as? LibraryHeaderItem
        val libraryItems = adapter.getSectionItems(adapter.getSectionHeader(position))
            .filterIsInstance<LibraryItem>()
        val mangaIds = libraryItems.mapNotNull { (it as? LibraryItem)?.manga?.id }
        if (newHeader?.category?.id == item.manga.category) {
            presenter.rearrangeCategory(item.manga.category, mangaIds)
        } else {
            if (newHeader?.category?.mangaSort == null) {
                presenter.moveMangaToCategory(item, newHeader?.category?.id, mangaIds, true)
            } else {
                MaterialDialog(activity!!).message(R.string.switch_to_dnd)
                    .positiveButton(R.string.action_switch) {
                        presenter.moveMangaToCategory(item, newHeader.category.id, mangaIds, true)
                    }.negativeButton(
                        text = resources?.getString(
                            R.string.keep_current_sort,
                            resources!!.getString(newHeader.category.sortRes()).toLowerCase
                                (Locale.getDefault())
                        )
                    ) {
                        presenter.moveMangaToCategory(
                            item, newHeader.category.id, mangaIds, false
                        )
                    }
                    .cancelOnTouchOutside(false)
                    .show()
            }
        }
    }

    override fun shouldMoveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (adapter.selectedItemCount > 1)
            return false
        if (adapter.isSelected(fromPosition))
            toggleSelection(fromPosition)
        return true
    }

    override fun updateCategory(catId: Int): Boolean {
        val category = (adapter.getItem(catId) as? LibraryHeaderItem)?.category ?:
        return false
        val inQueue = LibraryUpdateService.categoryInQueue(category.id)
        snack?.dismiss()
        snack = snackbar_layout.snack(resources!!.getString(
                when {
                    inQueue -> R.string.category_already_in_queue
                    LibraryUpdateService.isRunning(view!!.context) ->
                        R.string.adding_category_to_queue
                    else -> R.string.updating_category_x
                }, category.name))
        if (!inQueue)
            LibraryUpdateService.start(view!!.context, category)
        return true

    }

    override fun sortCategory(catId: Int, sortBy: Int): String {
        presenter.sortCategory(catId, sortBy)
        return ""
    }
}