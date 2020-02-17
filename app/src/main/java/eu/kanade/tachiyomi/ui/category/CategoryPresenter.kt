package eu.kanade.tachiyomi.ui.category

import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Presenter of [CategoryController]. Used to manage the categories of the library.
 */
class CategoryPresenter(
        private val controller: CategoryController,
        private val db: DatabaseHelper = Injekt.get(),
        preferences: PreferencesHelper = Injekt.get()
) {

    private val context = preferences.context

    /**
     * List containing categories.
     */
    private var categories: MutableList<Category> = mutableListOf()

    /**
     * Called when the presenter is created.
     */
    fun getCategories() {
        if (categories.isNotEmpty()) {
            controller.setCategories(categories.map(::CategoryItem))
        }
        GlobalScope.launch(Dispatchers.IO) {
            categories.clear()
            categories.add(newCategory())
            categories.addAll(db.getCategories().executeAsBlocking())
            val catItems = categories.map(::CategoryItem)
            withContext(Dispatchers.Main) {
                controller.setCategories(catItems)
            }
        }
    }

    private fun newCategory(): Category {
        val default = Category.create(context.getString(R.string.create_new_category))
        default.order = CREATE_CATEGORY_ORDER
        default.id = Int.MIN_VALUE
        return default
    }

    /**
     * Creates and adds a new category to the database.
     *
     * @param name The name of the category to create.
     */
    fun createCategory(name: String): Boolean {
        // Do not allow duplicate categories.
        if (categoryExists(name)) {
            controller.onCategoryExistsError()
            return false
        }

        // Create category.
        val cat = Category.create(name)

        // Set the new item in the last position.
        cat.order = categories.map { it.order + 1 }.max() ?: 0

        // Insert into database.

        db.insertCategory(cat).executeAsBlocking()
        val cats = db.getCategories().executeAsBlocking()
        val newCat = cats.find { it.name == name } ?: return false
        categories.add(1, newCat)
        reorderCategories(categories)
        return true
    }

    /**
     * Deletes the given categories from the database.
     *
     * @param category The category to delete.
     */
    fun deleteCategory(category: Category?) {
        val safeCategory = category ?: return
        db.deleteCategory(safeCategory).executeAsBlocking()
        categories.remove(safeCategory)
        controller.setCategories(categories.map(::CategoryItem))
    }

    /**
     * Reorders the given categories in the database.
     *
     * @param categories The list of categories to reorder.
     */
    fun reorderCategories(categories: List<Category>) {
        categories.forEachIndexed { i, category ->
            if (category.order != CREATE_CATEGORY_ORDER)
                category.order = i - 1
        }
        db.insertCategories(categories.filter { it.order != CREATE_CATEGORY_ORDER }).executeAsBlocking()
        this.categories = categories.sortedBy { it.order }.toMutableList()
        controller.setCategories(categories.map(::CategoryItem))
    }

    /**
     * Renames a category.
     *
     * @param category The category to rename.
     * @param name The new name of the category.
     */
    fun renameCategory(category: Category, name: String): Boolean {
        // Do not allow duplicate categories.
        if (categoryExists(name)) {
            controller.onCategoryExistsError()
            return false
        }

        category.name = name
        db.insertCategory(category).executeAsBlocking()
        categories.find { it.id == category.id }?.name = name
        controller.setCategories(categories.map(::CategoryItem))
        return true
    }

    /**
     * Returns true if a category with the given name already exists.
     */
    private fun categoryExists(name: String): Boolean {
        return categories.any { it.name.equals(name, true) }
    }

    companion object {
        const val CREATE_CATEGORY_ORDER = -2
    }

}