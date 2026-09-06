package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Column names are snake_case per spec section 4 ("Columns: id, name,
// icon_key, sort_order, is_protected"). They carry @ColumnInfo here rather
// than in Task 8 because this task freezes schema version 1: renaming a
// column after 1.json is committed is a schema change with no migration.
@Entity(
    tableName = "category",
    // `name` is the one seeded string that crosses into SQL
    // (CategoryDao.uncategorizedIdOrNull, spec 4 -- the confidence gate and the
    // categorizer resolve Uncategorized by id, but *something* has to find
    // that id once). Until now the column was unconstrained, so two rows could
    // hold the name the lookup reads and `LIMIT 1` would silently pick
    // whichever the plan reached first; the user's rename dialog and the
    // "add a category" sheet are both paths to a second `Uncategorized`.
    // Unique on top of that makes the lookup a `SEARCH ... USING INDEX` rather
    // than the `SCAN category` it measured as before.
    indices = [Index(value = ["name"], unique = true)],
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "icon_key") val iconKey: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "is_protected") val isProtected: Boolean = false,
)
