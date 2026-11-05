package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.utility.SQL_NOW
import com.rita.ecommerceplatform.model.entity.Category
import com.rita.ecommerceplatform.model.entity.CategoryStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class CategoryRepository(private val jdbcClient: JdbcClient) {

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

    /**
     * True if any category, including a soft-deleted one, already has this name (case-insensitive:
     * the column is CITEXT). uq__ec_category__name covers deleted rows too, so their names stay taken.
     */
    fun existsByName(name: String, excludingCategoryId: Int? = null): Boolean =
        jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM public.ec_category
                WHERE categoryname = :name
                  AND (CAST(:excludingId AS INTEGER) IS NULL OR categoryid <> :excludingId)
            )
            """
        )
            .param("name", name)
            .param("excludingId", excludingCategoryId)
            .query(Boolean::class.javaObjectType)
            .single()

    /** Inserts a category and returns the generated categoryid. Status and timestamps come from column defaults. */
    fun insert(name: String, description: String?, createdByAdminId: Int): Int =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_category (categoryname, categorydescription, categorycreatedbyadminid)
            VALUES (:name, :description, :adminId)
            RETURNING categoryid
            """
        )
            .param("name", name)
            .param("description", description)
            .param("adminId", createdByAdminId)
            .query(Int::class.javaObjectType)
            .single()

    fun findById(categoryId: Int): Category? =
        jdbcClient.sql("SELECT * FROM public.ec_category WHERE categoryid = :categoryId")
            .param("categoryId", categoryId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Categories with any of the given statuses, ordered by name. */
    fun findByStatuses(statuses: Collection<CategoryStatus>): List<Category> =
        jdbcClient.sql("SELECT * FROM public.ec_category WHERE categorystatus IN (:statuses) ORDER BY categoryname")
            .param("statuses", statuses.map { it.name })
            .query(rowMapper)
            .list()

    /** Updates name and description of a category that is not deleted. Returns rows updated (0 or 1). */
    fun update(categoryId: Int, name: String, description: String?): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_category
            SET categoryname = :name,
                categorydescription = :description,
                categoryupdatedat = $SQL_NOW
            WHERE categoryid = :categoryId AND categorystatus <> 'DELETED'
            """
        )
            .param("name", name)
            .param("description", description)
            .param("categoryId", categoryId)
            .update()

    /** Changes the status of a category that is not deleted (deleting is final). Returns rows updated (0 or 1). */
    fun updateStatus(categoryId: Int, status: CategoryStatus): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_category
            SET categorystatus = :status,
                categoryupdatedat = $SQL_NOW
            WHERE categoryid = :categoryId AND categorystatus <> 'DELETED'
            """
        )
            .param("status", status.name)
            .param("categoryId", categoryId)
            .update()

    /** True if the category still has products that are not soft-deleted (D2: such a category cannot be deleted). */
    fun hasProducts(categoryId: Int): Boolean =
        jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM public.ec_product
                WHERE productcategoryid = :categoryId AND productstatus <> 'DELETED'
            )
            """
        )
            .param("categoryId", categoryId)
            .query(Boolean::class.javaObjectType)
            .single()
}
