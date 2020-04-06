package eu.kanade.tachiyomi.ui.manga.info

import android.app.Dialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import com.elvishew.xlog.XLog
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import com.jakewharton.rxbinding.support.v4.widget.refreshes
import com.jakewharton.rxbinding.view.clicks
import com.jakewharton.rxbinding.view.longClicks
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.catalogue.CatalogueController
import eu.kanade.tachiyomi.ui.catalogue.browse.BrowseCatalogueController
import eu.kanade.tachiyomi.ui.catalogue.global_search.CatalogueSearchController
import eu.kanade.tachiyomi.ui.library.ChangeMangaCategoriesDialog
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.lang.truncateCenter
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.visible
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import exh.MERGED_SOURCE_ID
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import jp.wasabeef.glide.transformations.CropSquareTransformation
import kotlin.coroutines.CoroutineContext
import kotlinx.android.synthetic.main.manga_info_controller.backdrop
import kotlinx.android.synthetic.main.manga_info_controller.fab_favorite
import kotlinx.android.synthetic.main.manga_info_controller.manga_artist
import kotlinx.android.synthetic.main.manga_info_controller.manga_artist_label
import kotlinx.android.synthetic.main.manga_info_controller.manga_author
import kotlinx.android.synthetic.main.manga_info_controller.manga_chapters
import kotlinx.android.synthetic.main.manga_info_controller.manga_cover
import kotlinx.android.synthetic.main.manga_info_controller.manga_full_title
import kotlinx.android.synthetic.main.manga_info_controller.manga_genres_tags
import kotlinx.android.synthetic.main.manga_info_controller.manga_last_update
import kotlinx.android.synthetic.main.manga_info_controller.manga_source
import kotlinx.android.synthetic.main.manga_info_controller.manga_source_label
import kotlinx.android.synthetic.main.manga_info_controller.manga_status
import kotlinx.android.synthetic.main.manga_info_controller.manga_summary
import kotlinx.android.synthetic.main.manga_info_controller.smartsearch_buttons
import kotlinx.android.synthetic.main.manga_info_controller.smartsearch_merge_btn
import kotlinx.android.synthetic.main.manga_info_controller.swipe_refresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Fragment that shows manga information.
 * Uses R.layout.manga_info_controller.
 * UI related actions should be called from here.
 */
class MangaInfoController : NucleusController<MangaInfoPresenter>(),
        ChangeMangaCategoriesDialog.Listener, CoroutineScope {

    /**
     * Preferences helper.
     */
    private val preferences: PreferencesHelper by injectLazy()

    private val dateFormat: DateFormat by lazy {
        preferences.dateFormat().getOrDefault()
    }

    // EXH -->
    private var lastMangaThumbnail: String? = null

    private val smartSearchConfig get() = (parentController as MangaController).smartSearchConfig

    override val coroutineContext: CoroutineContext = Job() + Dispatchers.Main

    private val gson: Gson by injectLazy()

    private val sourceManager: SourceManager by injectLazy()
    // EXH <--

    init {
        setHasOptionsMenu(true)
        setOptionsMenuHidden(true)
    }

    override fun createPresenter(): MangaInfoPresenter {
        val ctrl = parentController as MangaController
        return MangaInfoPresenter(ctrl.manga!!, ctrl.source!!, ctrl.smartSearchConfig,
                ctrl.chapterCountRelay, ctrl.lastUpdateRelay, ctrl.mangaFavoriteRelay)
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.manga_info_controller, container, false)
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        // Set onclickListener to toggle favorite when FAB clicked.
        fab_favorite.clicks().subscribeUntilDestroy { onFabClick() }

        // Set onLongClickListener to manage categories when FAB is clicked.
        fab_favorite.longClicks().subscribeUntilDestroy { onFabLongClick() }

        // Set SwipeRefresh to refresh manga data.
        swipe_refresh.refreshes().subscribeUntilDestroy { fetchMangaFromSource() }

        manga_full_title.longClicks().subscribeUntilDestroy {
            copyToClipboard(view.context.getString(R.string.title), manga_full_title.text.toString())
        }

        manga_full_title.clicks().subscribeUntilDestroy {
            performGlobalSearch(manga_full_title.text.toString())
        }

        manga_artist.longClicks().subscribeUntilDestroy {
            copyToClipboard(manga_artist_label.text.toString(), manga_artist.text.toString())
        }

        manga_artist.clicks().subscribeUntilDestroy {
            // EXH Special case E-Hentai/ExHentai to use tag based search
            var text = manga_artist.text.toString()
            if (isEHentaiBasedSource())
                text = wrapTag("artist", text)
            performGlobalSearch(text)
        }

        manga_author.longClicks().subscribeUntilDestroy {
            // EXH Special case E-Hentai/ExHentai to ignore author field (unused)
            if (!isEHentaiBasedSource())
                copyToClipboard(manga_author.text.toString(), manga_author.text.toString())
        }

        manga_author.clicks().subscribeUntilDestroy {
            // EXH Special case E-Hentai/ExHentai to ignore author field (unused)
            if (!isEHentaiBasedSource())
                performGlobalSearch(manga_author.text.toString())
        }

        manga_summary.longClicks().subscribeUntilDestroy {
            copyToClipboard(view.context.getString(R.string.description), manga_summary.text.toString())
        }

        manga_cover.longClicks().subscribeUntilDestroy {
            copyToClipboard(view.context.getString(R.string.title), presenter.manga.title)
        }

        // EXH -->
        smartSearchConfig?.let { smartSearchConfig ->
            smartsearch_buttons.visible()

            smartsearch_merge_btn.clicks().subscribeUntilDestroy {
                // Init presenter here to avoid threading issues
                presenter

                launch {
                    try {
                        val mergedManga = withContext(Dispatchers.IO + NonCancellable) {
                            presenter.smartSearchMerge(presenter.manga, smartSearchConfig.origMangaId)
                        }

                        parentController?.router?.pushController(MangaController(mergedManga,
                                true,
                                update = true).withFadeTransaction())
                        applicationContext?.toast("Manga merged!")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        else {
                            applicationContext?.toast("Failed to merge manga: ${e.message}")
                        }
                    }
                }
            }
        }
        // EXH <--
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.manga_info, menu)

        if (presenter.source !is HttpSource) {
            menu.findItem(R.id.action_share).isVisible = false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // EXH -->
            R.id.action_smart_search -> openSmartSearch()
            // EXH <--
            R.id.action_open_in_web_view -> openInWebView()
            R.id.action_share -> shareManga()
            R.id.action_add_to_home_screen -> addToHomeScreen()
        }
        return super.onOptionsItemSelected(item)
    }

    // EXH -->
    private fun openSmartSearch() {
        val smartSearchConfig = CatalogueController.SmartSearchConfig(presenter.manga.title, presenter.manga.id!!)

        parentController?.router?.pushController(CatalogueController(Bundle().apply {
            putParcelable(CatalogueController.SMART_SEARCH_CONFIG, smartSearchConfig)
        }).withFadeTransaction())
    }
    // EXH <--

    /**
     * Check if manga is initialized.
     * If true update view with manga information,
     * if false fetch manga information
     *
     * @param manga manga object containing information about manga.
     * @param source the source of the manga.
     */
    fun onNextManga(manga: Manga, source: Source) {
        if (manga.initialized) {
            // Update view.
            setMangaInfo(manga, source)

            if ((parentController as MangaController).update) fetchMangaFromSource()
        } else {
            // Initialize manga.
            fetchMangaFromSource()
        }
    }

    /**
     * Update the view with manga information.
     *
     * @param manga manga object containing information about manga.
     * @param source the source of the manga.
     */
    private fun setMangaInfo(manga: Manga, source: Source?) {
        val view = view ?: return

        // TODO Duplicated in MigrationProcedureAdapter

        // update full title TextView.
        manga_full_title.text = if (manga.title.isBlank()) {
            view.context.getString(R.string.unknown)
        } else {
            manga.title
        }

        // Update artist TextView.
        manga_artist.text = if (manga.artist.isNullOrBlank()) {
            view.context.getString(R.string.unknown)
        } else {
            manga.artist
        }

        // Update author TextView.
        manga_author.text = if (manga.author.isNullOrBlank()) {
            view.context.getString(R.string.unknown)
        } else {
            manga.author
        }

        // If manga source is known update source TextView.
        if (source == null) {
            manga_source.text = view.context.getString(R.string.unknown)
            // EXH -->
        } else if (source.id == MERGED_SOURCE_ID) {
            manga_source.text = MergedSource.MangaConfig.readFromUrl(gson, manga.url).children.map {
                sourceManager.getOrStub(it.source).toString()
            }.distinct().joinToString()
            // EXH <--
        } else {
            val mangaSource = source?.toString()
            with(manga_source) {
                text = mangaSource
                setOnClickListener {
                    val sourceManager = Injekt.get<SourceManager>()
                    performSearch(sourceManager.getOrStub(source.id).name)
                }
            }
        }

        // EXH -->
        if (source?.id == MERGED_SOURCE_ID) {
            manga_source_label.text = "Sources"
        } else {
            manga_source_label.setText(R.string.manga_info_source_label)
        }
        // EXH <--

        // Update genres list
        if (!manga.genre.isNullOrBlank()) {
            manga_genres_tags.removeAllViews()

            manga.genre?.split(", ")?.forEach { genre ->
                val chip = Chip(view.context).apply {
                    text = genre
                    setOnClickListener { performSearch(genre) }
                }

                manga_genres_tags.addView(chip)
            }
        }

        // Update description TextView.
        manga_summary.text = if (manga.description.isNullOrBlank()) {
            view.context.getString(R.string.unknown)
        } else {
            manga.description
        }

        // Update status TextView.
        manga_status.setText(when (manga.status) {
            SManga.ONGOING -> R.string.ongoing
            SManga.COMPLETED -> R.string.completed
            SManga.LICENSED -> R.string.licensed
            else -> R.string.unknown
        })

        // Set the favorite drawable to the correct one.
        setFavoriteDrawable(manga.favorite)

        // Set cover if it matches
        val tagMatches = lastMangaThumbnail == manga.thumbnail_url
        val coverLoaded = manga_cover.drawable != null
        if ((!tagMatches || !coverLoaded) && !manga.thumbnail_url.isNullOrEmpty()) {
            lastMangaThumbnail = manga.thumbnail_url

            val coverSig = ObjectKey(manga.thumbnail_url ?: "")

            GlideApp.with(view.context)
                    .load(manga)
                    .signature(coverSig)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .centerCrop()
                    .into(manga_cover)

            if (backdrop != null) {
                GlideApp.with(view.context)
                        .load(manga)
                        .signature(coverSig)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .centerCrop()
                        .into(backdrop)
            }
        }
    }

    /**
     * Update chapter count TextView.
     *
     * @param count number of chapters.
     */
    fun setChapterCount(count: Float) {
        if (count > 0f) {
            manga_chapters?.text = DecimalFormat("#.#").format(count)
        } else {
            manga_chapters?.text = resources?.getString(R.string.unknown)
        }
    }

    fun setLastUpdateDate(date: Date) {
        if (date.time != 0L) {
            manga_last_update?.text = dateFormat.format(date)
        } else {
            manga_last_update?.text = resources?.getString(R.string.unknown)
        }
    }

    /**
     * Toggles the favorite status and asks for confirmation to delete downloaded chapters.
     */
    private fun toggleFavorite() {
        val view = view

        val isNowFavorite = presenter.toggleFavorite()
        if (view != null && !isNowFavorite && presenter.hasDownloads()) {
            view.snack(view.context.getString(R.string.delete_downloads_for_manga)) {
                setAction(R.string.action_delete) {
                    presenter.deleteDownloads()
                }
            }
        }
    }

    private fun openInWebView() {
        val source = presenter.source as? HttpSource ?: return

        val url = try {
            source.mangaDetailsRequest(presenter.manga).url.toString()
        } catch (e: Exception) {
            return
        }

        val activity = activity ?: return
        val intent = WebViewActivity.newIntent(activity, source.id, url, presenter.manga.title)
        startActivity(intent)
    }

    /**
     * Called to run Intent with [Intent.ACTION_SEND], which show share dialog.
     */
    private fun shareManga() {
        val context = view?.context ?: return

        val source = presenter.source as? HttpSource ?: return
        try {
            val url = source.mangaDetailsRequest(presenter.manga).url.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Update FAB with correct drawable.
     *
     * @param isFavorite determines if manga is favorite or not.
     */
    private fun setFavoriteDrawable(isFavorite: Boolean) {
        // Set the Favorite drawable to the correct one.
        // Border drawable if false, filled drawable if true.
        fab_favorite?.setImageResource(if (isFavorite)
            R.drawable.ic_bookmark_24dp
        else
            R.drawable.ic_add_to_library_24dp)
    }

    /**
     * Start fetching manga information from source.
     */
    private fun fetchMangaFromSource() {
        setRefreshing(true)
        // Call presenter and start fetching manga information
        presenter.fetchMangaFromSource()
    }

    /**
     * Update swipe refresh to stop showing refresh in progress spinner.
     */
    fun onFetchMangaDone() {
        setRefreshing(false)
    }

    /**
     * Update swipe refresh to start showing refresh in progress spinner.
     */
    fun onFetchMangaError(error: Throwable) {
        setRefreshing(false)
        activity?.toast(error.message)

        // [EXH]
        XLog.w("> Failed to fetch manga details!", error)
        XLog.w("> (source.id: %s, source.name: %s, manga.id: %s, manga.url: %s)",
                presenter.source.id,
                presenter.source.name,
                presenter.manga.id,
                presenter.manga.url)
    }

    /**
     * Set swipe refresh status.
     *
     * @param value whether it should be refreshing or not.
     */
    private fun setRefreshing(value: Boolean) {
        swipe_refresh?.isRefreshing = value
    }

    private fun onFabClick() {
        val manga = presenter.manga

        if (manga.favorite) {
            toggleFavorite()
            activity?.toast(activity?.getString(R.string.manga_removed_library))
        } else {
            val categories = presenter.getCategories()
            val defaultCategoryId = preferences.defaultCategory()
            val defaultCategory = categories.find { it.id == defaultCategoryId }

            when {
                // Default category set
                defaultCategory != null -> {
                    toggleFavorite()
                    presenter.moveMangaToCategory(manga, defaultCategory)
                    activity?.toast(activity?.getString(R.string.manga_added_library))
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    toggleFavorite()
                    presenter.moveMangaToCategory(manga, null)
                    activity?.toast(activity?.getString(R.string.manga_added_library))
                }

                // Choose a category
                else -> {
                    val ids = presenter.getMangaCategoryIds(manga)
                    val preselected = ids.mapNotNull { id ->
                        categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
                    }.toTypedArray()

                    ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
                            .showDialog(router)
                }
            }
        }
    }

    private fun onFabLongClick() {
        val manga = presenter.manga

        if (manga.favorite && presenter.getCategories().isNotEmpty()) {
            val categories = presenter.getCategories()

            val ids = presenter.getMangaCategoryIds(manga)
            val preselected = ids.mapNotNull { id ->
                categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
            }.toTypedArray()

            ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
                    .showDialog(router)
        } else {
            onFabClick()
        }
    }

    override fun updateCategoriesForMangas(mangas: List<Manga>, categories: List<Category>) {
        val manga = mangas.firstOrNull() ?: return

        if (!manga.favorite) {
            toggleFavorite()
        }

        presenter.moveMangaToCategories(manga, categories)
        activity?.toast(activity?.getString(R.string.manga_added_library))
    }

    /**
     * Add a shortcut of the manga to the home screen
     */
    private fun addToHomeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // TODO are transformations really unsupported or is it just the Pixel Launcher?
            createShortcutForShape()
        } else {
            ChooseShapeDialog(this).showDialog(router)
        }
    }

    /**
     * Dialog to choose a shape for the icon.
     */
    private class ChooseShapeDialog(bundle: Bundle? = null) : DialogController(bundle) {

        constructor(target: MangaInfoController) : this() {
            targetController = target
        }

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            val modes = intArrayOf(R.string.circular_icon,
                    R.string.rounded_icon,
                    R.string.square_icon)

            return MaterialDialog.Builder(activity!!)
                    .title(R.string.icon_shape)
                    .negativeText(android.R.string.cancel)
                    .items(modes.map { activity?.getString(it) })
                    .itemsCallback { _, _, i, _ ->
                        (targetController as? MangaInfoController)?.createShortcutForShape(i)
                    }
                    .build()
        }
    }

    /**
     * Retrieves the bitmap of the shortcut with the requested shape and calls [createShortcut] when
     * the resource is available.
     *
     * @param i The shape index to apply. Defaults to circle crop transformation.
     */
    private fun createShortcutForShape(i: Int = 0) {
        if (activity == null) return
        GlideApp.with(activity!!)
                .asBitmap()
                .load(presenter.manga)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .apply {
                    when (i) {
                        0 -> circleCrop()
                        1 -> transform(RoundedCorners(5))
                        2 -> transform(CropSquareTransformation())
                    }
                }
                .into(object : CustomTarget<Bitmap>(96, 96) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        createShortcut(resource)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        activity?.toast(R.string.icon_creation_fail)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
    }

    /**
     * Copies a string to clipboard
     *
     * @param label Label to show to the user describing the content
     * @param content the actual text to copy to the board
     */
    private fun copyToClipboard(label: String, content: String) {
        if (content.isBlank()) return

        val activity = activity ?: return
        val view = view ?: return

        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip = ClipData.newPlainText(label, content)

        activity.toast(view.context.getString(R.string.copied_to_clipboard, content.truncateCenter(20)),
                Toast.LENGTH_SHORT)
    }

    /**
     * Perform a global search using the provided query.
     *
     * @param query the search query to pass to the search controller
     */
    fun performGlobalSearch(query: String) {
        val router = parentController?.router ?: return
        router.pushController(CatalogueSearchController(query).withFadeTransaction())
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private fun performSearch(query: String) {
        val router = parentController?.router ?: return

        if (router.backstackSize < 2) {
            return
        }

        val previousController = router.backstack[router.backstackSize - 2].controller()
        when (previousController) {
            is LibraryController -> {
                router.handleBack()
                previousController.search(query)
            }
            is BrowseCatalogueController -> {
                router.handleBack()
                previousController.searchWithQuery(query)
            }
        }
    }

    // --> EH
    private fun wrapTag(namespace: String, tag: String) = if (tag.contains(' '))
        "$namespace:\"$tag$\""
    else
        "$namespace:$tag$"

    private fun parseTag(tag: String) = tag.substringBefore(':').trim() to tag.substringAfter(':').trim()

    private fun isEHentaiBasedSource(): Boolean {
        val sourceId = presenter.source.id
        return sourceId == EH_SOURCE_ID ||
                sourceId == EXH_SOURCE_ID
    }
    // <-- EH

    /**
     * Create shortcut using ShortcutManager.
     *
     * @param icon The image of the shortcut.
     */
    private fun createShortcut(icon: Bitmap) {
        val activity = activity ?: return
        val mangaControllerArgs = parentController?.args ?: return

        // Create the shortcut intent.
        val shortcutIntent = activity.intent
                .setAction(MainActivity.SHORTCUT_MANGA)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MangaController.MANGA_EXTRA,
                        mangaControllerArgs.getLong(MangaController.MANGA_EXTRA))

        // Check if shortcut placement is supported
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            val shortcutId = "manga-shortcut-${presenter.manga.title}-${presenter.source.name}"

            // Create shortcut info
            val shortcutInfo = ShortcutInfoCompat.Builder(activity, shortcutId)
                    .setShortLabel(presenter.manga.title)
                    .setIcon(IconCompat.createWithBitmap(icon))
                    .setIntent(shortcutIntent)
                    .build()

            val successCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Create the CallbackIntent.
                val intent = ShortcutManagerCompat.createShortcutResultIntent(activity, shortcutInfo)

                // Configure the intent so that the broadcast receiver gets the callback successfully.
                PendingIntent.getBroadcast(activity, 0, intent, 0)
            } else {
                NotificationReceiver.shortcutCreatedBroadcast(activity)
            }

            // Request shortcut.
            ShortcutManagerCompat.requestPinShortcut(activity, shortcutInfo,
                    successCallback.intentSender)
        }
    }
}
