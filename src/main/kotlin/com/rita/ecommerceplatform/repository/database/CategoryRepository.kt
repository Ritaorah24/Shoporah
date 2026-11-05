package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.CategoryQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.Category
import com.rita.ecommerceplatform.model.entity.CategoryStatus
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

interface CategoryRepository {
    /**
     * True if any category, including a soft-deleted one, already has this name (case-insensitive:
     * the column is CITEXT). uq__ec_category__name covers deleted rows too, so their names stay taken.
     */
    fun existsByName(name: String, excludingCategoryId: Int? = null): Boolean

    /** Inserts a category and returns the generated categoryid. Status and timestamps come from column defaults. */
    fun insert(name: String, description: String?, createdByAdminId: Int): Int

    fun findById(categoryId: Int): Category?

    /** Categories with any of the given statuses, ordered by name. */
    fun findByStatuses(statuses: Collection<CategoryStatus>): List<Category>

    /** Updates name and description of a category that is not deleted. Returns rows updated (0 or 1). */
    fun update(categoryId: Int, name: String, description: String?): Int

    /** Changes the status of a category that is not deleted (deleting is final). Returns rows updated (0 or 1). */
    fun updateStatus(categoryId: Int, status: CategoryStatus): Int

    /** True if the category still has products that are not soft-deleted (D2: such a category cannot be deleted). */
    fun hasProducts(categoryId: Int): Boolean
}

@Repository
class CategoryRepositoryImpl(private val jdbi: Jdbi) : CategoryRepository {

    private val rowMapper = RowMapper { rs, _ ->
        Category(
            categoryId = rs.getInt("categoryid"),
            name = rs.getString("categoryname"),
            description = rs.getString("categorydescription"),
            createdByAdminId = rs.getInt("categorycreatedbyadminid"),
            status = CategoryStatus.valueOf(rs.getString("categorystatus").uppercase()),
            createdAt = rs.getObject("categorycreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("categoryupdatedat", LocalDateTime::class.java),
        )
    }

    override fun existsByName(name: String, excludingCategoryId: Int?): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(CategoryQuery.EXISTS_BY_NAME)
                .bind("name", name)
                .bind("excludingId", excludingCategoryId)
                .mapTo<Boolean>()
                .one()
        }

    override fun insert(name: String, description: String?, createdByAdminId: Int): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(CategoryQuery.INSERT)
                .bind("name", name)
                .bind("description", description)
                .bind("adminId", createdByAdminId)
                .mapTo<Int>()
                .one()
        }

    override fun findById(categoryId: Int): Category? =
        jdbi.withHandle<Category?, Exception> { handle ->
            handle.createQuery(CategoryQuery.FIND_BY_ID)
                .bind("categoryId", categoryId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findByStatuses(statuses: Collection<CategoryStatus>): List<Category> =
        jdbi.withHandle<List<Category>, Exception> { handle ->
            handle.createQuery(CategoryQuery.FIND_BY_STATUSES)
                .bindList("statuses", statuses.map { it.name })
                .map(rowMapper)
                .list()
        }

    override fun update(categoryId: Int, name: String, description: String?): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CategoryQuery.UPDATE)
                .bind("name", name)
                .bind("description", description)
                .bind("categoryId", categoryId)
                .execute()
        }

    override fun updateStatus(categoryId: Int, status: CategoryStatus): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(CategoryQuery.UPDATE_STATUS)
                .bind("status", status.name)
                .bind("categoryId", categoryId)
                .execute()
        }

    override fun hasProducts(categoryId: Int): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(CategoryQuery.HAS_PRODUCTS)
                .bind("categoryId", categoryId)
                .mapTo<Boolean>()
                .one()
        }
}
