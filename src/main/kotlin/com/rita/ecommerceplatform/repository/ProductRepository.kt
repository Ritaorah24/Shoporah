package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.Product
import com.rita.ecommerceplatform.model.ProductStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Filters for a product listing. Public listings use statuses = [ACTIVE] and activeCategoryOnly = true;
 * admin listings can include INACTIVE products and inactive categories.
 */
data class ProductSearchCriteria(
    val statuses: Collection<ProductStatus>,
    val activeCategoryOnly: Boolean,
    val nameContains: String? = null,
    val categoryId: Int? = null,
)

@Repository
class ProductRepository(private val jdbcClient: JdbcClient) {

    // Every product query joins ec_category so the Product carries its category name.
    private val selectWithCategory = """
        SELECT p.*, c.categoryname
        FROM public.ec_product p
        JOIN public.ec_category c ON c.categoryid = p.productcategoryid
    """

    private val rowMapper = RowMapper { rs, _ ->
        Product(
            productId = rs.getLong("productid"),
            categoryId = rs.getInt("productcategoryid"),
            categoryName = rs.getString("categoryname"),
            name = rs.getString("productname"),
            description = rs.getString("productdescription"),
            sku = rs.getString("productsku"),
            price = rs.getBigDecimal("productprice"),
            discountPrice = rs.getBigDecimal("productdiscountprice"),
            sellingPrice = rs.getBigDecimal("productsellingprice"),
            stockQuantity = rs.getInt("productstockquantity"),
            lowStockThreshold = rs.getObject("productlowstockthreshold", Int::class.javaObjectType),
            createdByAdminId = rs.getInt("productcreatedbyadminid"),
            status = ProductStatus.valueOf(rs.getString("productstatus").uppercase()),
            createdAt = rs.getObject("productcreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("productupdatedat", LocalDateTime::class.java),
        )
    }

    /** True if any product, including a soft-deleted one, already uses this SKU (case-insensitive: CITEXT). */
    fun existsBySku(sku: String, excludingProductId: Long? = null): Boolean =
        jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM public.ec_product
                WHERE productsku = :sku
                  AND (CAST(:excludingId AS BIGINT) IS NULL OR productid <> :excludingId)
            )
            """
        )
            .param("sku", sku)
            .param("excludingId", excludingProductId)
            .query(Boolean::class.javaObjectType)
            .single()

    /** Inserts a product and returns the generated productid. Status, sellingprice and timestamps come from the database. */
    fun insert(
        categoryId: Int,
        createdByAdminId: Int,
        name: String,
        description: String?,
        sku: String,
        price: BigDecimal,
        discountPrice: BigDecimal?,
        stockQuantity: Int,
        lowStockThreshold: Int?,
    ): Long =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_product (
                productcategoryid, productcreatedbyadminid, productname, productdescription, productsku,
                productprice, productdiscountprice, productstockquantity, productlowstockthreshold
            )
            VALUES (
                :categoryId, :adminId, :name, :description, :sku,
                :price, :discountPrice, :stockQuantity, :lowStockThreshold
            )
            RETURNING productid
            """
        )
            .param("categoryId", categoryId)
            .param("adminId", createdByAdminId)
            .param("name", name)
            .param("description", description)
            .param("sku", sku)
            .param("price", price)
            .param("discountPrice", discountPrice)
            .param("stockQuantity", stockQuantity)
            .param("lowStockThreshold", lowStockThreshold)
            .query(Long::class.javaObjectType)
            .single()

    fun findById(productId: Long): Product? =
        jdbcClient.sql("$selectWithCategory WHERE p.productid = :productId")
            .param("productId", productId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** The product only if the public may see it: product ACTIVE and its category ACTIVE (same rule as public listings). */
    fun findPublicById(productId: Long): Product? =
        jdbcClient.sql(
            "$selectWithCategory WHERE p.productid = :productId AND p.productstatus = 'ACTIVE' AND c.categorystatus = 'ACTIVE'"
        )
            .param("productId", productId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * Same as findById, but locks the product row until the transaction ends, so a stock edit
     * cannot overwrite a concurrent stock change (e.g. a paid order). Must be called inside @Transactional.
     */
    fun findByIdForUpdate(productId: Long): Product? =
        jdbcClient.sql("$selectWithCategory WHERE p.productid = :productId FOR UPDATE OF p")
            .param("productId", productId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /**
     * Takes `quantity` from stock only if there is enough, and returns the stock left; null if there was not enough
     * (the conditional update from the 1.3 decision; ck__ec_product__stockquantity is the last guard).
     */
    fun decreaseStock(productId: Long, quantity: Int): Int? =
        jdbcClient.sql(
            """
            UPDATE public.ec_product
            SET productstockquantity = productstockquantity - :quantity, productupdatedat = $SQL_NOW
            WHERE productid = :productId AND productstockquantity >= :quantity
            RETURNING productstockquantity
            """
        )
            .param("productId", productId)
            .param("quantity", quantity)
            .query(Int::class.javaObjectType)
            .optional()
            .orElse(null)

    /**
     * Updates the editable fields of a product that is not deleted. The service merges a partial
     * PATCH with the current row first, so every field is passed. Returns rows updated (0 or 1).
     */
    fun update(
        productId: Long,
        categoryId: Int,
        name: String,
        description: String?,
        sku: String,
        price: BigDecimal,
        discountPrice: BigDecimal?,
        stockQuantity: Int,
        lowStockThreshold: Int?,
    ): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_product
            SET productcategoryid = :categoryId,
                productname = :name,
                productdescription = :description,
                productsku = :sku,
                productprice = :price,
                productdiscountprice = :discountPrice,
                productstockquantity = :stockQuantity,
                productlowstockthreshold = :lowStockThreshold,
                productupdatedat = $SQL_NOW
            WHERE productid = :productId AND productstatus <> 'DELETED'
            """
        )
            .param("categoryId", categoryId)
            .param("name", name)
            .param("description", description)
            .param("sku", sku)
            .param("price", price)
            .param("discountPrice", discountPrice)
            .param("stockQuantity", stockQuantity)
            .param("lowStockThreshold", lowStockThreshold)
            .param("productId", productId)
            .update()

    /** Changes the status of a product that is not deleted (deleting is final, D3). Returns rows updated (0 or 1). */
    fun updateStatus(productId: Long, status: ProductStatus): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_product
            SET productstatus = :status,
                productupdatedat = $SQL_NOW
            WHERE productid = :productId AND productstatus <> 'DELETED'
            """
        )
            .param("status", status.name)
            .param("productId", productId)
            .update()

    /** One page of products matching the criteria, newest first. */
    fun search(criteria: ProductSearchCriteria, limit: Int, offset: Long): List<Product> {
        val (where, params) = whereClause(criteria)
        return jdbcClient.sql(
            "$selectWithCategory $where ORDER BY p.productcreatedat DESC, p.productid DESC LIMIT :limit OFFSET :offset"
        )
            .params(params + mapOf("limit" to limit, "offset" to offset))
            .query(rowMapper)
            .list()
    }

    /** Total number of products matching the criteria (for totalElements / totalPages). */
    fun count(criteria: ProductSearchCriteria): Long {
        val (where, params) = whereClause(criteria)
        return jdbcClient.sql(
            """
            SELECT COUNT(*)
            FROM public.ec_product p
            JOIN public.ec_category c ON c.categoryid = p.productcategoryid
            $where
            """
        )
            .params(params)
            .query(Long::class.javaObjectType)
            .single()
    }

    /**
     * Builds the WHERE clause from fixed SQL fragments only; every user value is a named parameter,
     * so search terms can never change the SQL.
     */
    private fun whereClause(criteria: ProductSearchCriteria): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf("p.productstatus IN (:statuses)")
        val params = mutableMapOf<String, Any>("statuses" to criteria.statuses.map { it.name })

        if (criteria.activeCategoryOnly) {
            conditions += "c.categorystatus = 'ACTIVE'"
        }
        criteria.categoryId?.let {
            conditions += "p.productcategoryid = :categoryId"
            params["categoryId"] = it
        }
        criteria.nameContains?.let {
            // Partial, case-insensitive name match (D5); uses the ix__ec_product__name_trgm index.
            conditions += "(p.productname::TEXT) ILIKE :namePattern ESCAPE '\\'"
            params["namePattern"] = "%${escapeLike(it)}%"
        }
        return "WHERE " + conditions.joinToString(" AND ") to params
    }

    // So a search for "50%" or "a_b" matches those characters literally instead of as wildcards.
    private fun escapeLike(term: String): String =
        term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
