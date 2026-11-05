package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.ProductQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.utility.constant.PrefixConstant
import com.rita.ecommerceplatform.utility.containsPattern
import com.rita.ecommerceplatform.model.entity.Product
import com.rita.ecommerceplatform.model.entity.ProductStatus
import org.jdbi.v3.core.mapper.RowMapper
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
    // Both on the selling price (the discounted price when there is one), inclusive.
    val minPrice: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    val inStockOnly: Boolean = false,
    // Admin list only: products that need restocking.
    val stockLevel: StockLevel? = null,
    val sort: ProductSort = ProductSort.NEWEST,
)

/**
 * The admin list's stock filter (?stock=LOW|OUT), with the same rule as Product.availability(), so "low" means the
 * same to admins and the public: LOW only for products that have a threshold.
 */
enum class StockLevel(val condition: String) {
    LOW(
        "p.productstockquantity > 0 AND p.productlowstockthreshold IS NOT NULL " +
            "AND p.productstockquantity <= p.productlowstockthreshold",
    ),
    OUT("p.productstockquantity <= 0"),
}

/**
 * How a product listing is ordered. Each value maps to fixed SQL, so the client's sort text never reaches the query.
 * productid is the last key, so products with the same price or time keep one order across pages.
 */
enum class ProductSort(val param: String, val orderBy: String) {
    NEWEST("newest", "p.productcreatedat DESC, p.productid DESC"),
    PRICE_ASC("price_asc", "p.productsellingprice ASC, p.productid ASC"),
    PRICE_DESC("price_desc", "p.productsellingprice DESC, p.productid DESC"),
}

interface ProductRepository {
    /** True if any product, including a soft-deleted one, already uses this SKU (case-insensitive: CITEXT). */
    fun existsBySku(sku: String, excludingProductId: Long? = null): Boolean

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
    ): Long

    fun findById(productId: Long): Product?

    /** The product only if the public may see it: product ACTIVE and its category ACTIVE (same rule as public listings). */
    fun findPublicById(productId: Long): Product?

    /**
     * Same as findById, but locks the product row until the transaction ends, so a stock edit
     * cannot overwrite a concurrent stock change (e.g. a paid order). Must be called inside @Transactional.
     */
    fun findByIdForUpdate(productId: Long): Product?

    /**
     * Takes `quantity` from stock only if there is enough, and returns the stock left; null if there was not enough
     * (the conditional update from the 1.3 decision; ck__ec_product__stockquantity is the last guard).
     */
    fun decreaseStock(productId: Long, quantity: Int): Int?

    /** Puts `quantity` back into stock (e.g. a cancelled order) and returns the stock after; null if the product does not exist. */
    fun increaseStock(productId: Long, quantity: Int): Int?

    /**
     * Adds `change` (negative to remove) to the stock of a product that is not deleted, only if the stock stays 0 or
     * more, and returns the stock after. Null if it would go below 0 or the product is missing or deleted.
     * Relative, so a sale between the admin loading the page and saving is never overwritten.
     */
    fun adjustStock(productId: Long, change: Int): Int?

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
    ): Int

    /** Changes the status of a product that is not deleted (deleting is final, D3). Returns rows updated (0 or 1). */
    fun updateStatus(productId: Long, status: ProductStatus): Int

    /** One page of products matching the criteria, in the criteria's sort order. */
    fun search(criteria: ProductSearchCriteria, limit: Int, offset: Long): List<Product>

    /** Total number of products matching the criteria (for totalElements / totalPages). */
    fun count(criteria: ProductSearchCriteria): Long
}

@Repository
class ProductRepositoryImpl(private val jdbi: Jdbi) : ProductRepository {

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

    override fun existsBySku(sku: String, excludingProductId: Long?): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(ProductQuery.EXISTS_BY_SKU)
                .bind("sku", sku)
                .bind("excludingId", excludingProductId)
                .mapTo<Boolean>()
                .one()
        }

    override fun insert(
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
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(ProductQuery.INSERT)
                .bind("categoryId", categoryId)
                .bind("adminId", createdByAdminId)
                .bind("name", name)
                .bind("description", description)
                .bind("sku", sku)
                .bind("price", price)
                .bind("discountPrice", discountPrice)
                .bind("stockQuantity", stockQuantity)
                .bind("lowStockThreshold", lowStockThreshold)
                .mapTo<Long>()
                .one()
        }

    override fun findById(productId: Long): Product? =
        jdbi.withHandle<Product?, Exception> { handle ->
            handle.createQuery(ProductQuery.FIND_BY_ID)
                .bind("productId", productId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findPublicById(productId: Long): Product? =
        jdbi.withHandle<Product?, Exception> { handle ->
            handle.createQuery(ProductQuery.FIND_PUBLIC_BY_ID)
                .bind("productId", productId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun findByIdForUpdate(productId: Long): Product? =
        jdbi.withHandle<Product?, Exception> { handle ->
            handle.createQuery(ProductQuery.FIND_BY_ID_FOR_UPDATE)
                .bind("productId", productId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun decreaseStock(productId: Long, quantity: Int): Int? =
        jdbi.withHandle<Int?, Exception> { handle ->
            handle.createQuery(ProductQuery.DECREASE_STOCK)
                .bind("productId", productId)
                .bind("quantity", quantity)
                .mapTo<Int>()
                .findOne()
                .orElse(null)
        }

    override fun increaseStock(productId: Long, quantity: Int): Int? =
        jdbi.withHandle<Int?, Exception> { handle ->
            handle.createQuery(ProductQuery.INCREASE_STOCK)
                .bind("productId", productId)
                .bind("quantity", quantity)
                .mapTo<Int>()
                .findOne()
                .orElse(null)
        }

    override fun adjustStock(productId: Long, change: Int): Int? =
        jdbi.withHandle<Int?, Exception> { handle ->
            handle.createQuery(ProductQuery.ADJUST_STOCK)
                .bind("productId", productId)
                .bind("change", change)
                .mapTo<Int>()
                .findOne()
                .orElse(null)
        }

    override fun update(
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
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(ProductQuery.UPDATE)
                .bind("categoryId", categoryId)
                .bind("name", name)
                .bind("description", description)
                .bind("sku", sku)
                .bind("price", price)
                .bind("discountPrice", discountPrice)
                .bind("stockQuantity", stockQuantity)
                .bind("lowStockThreshold", lowStockThreshold)
                .bind("productId", productId)
                .execute()
        }

    override fun updateStatus(productId: Long, status: ProductStatus): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(ProductQuery.UPDATE_STATUS)
                .bind("status", status.name)
                .bind("productId", productId)
                .execute()
        }

    override fun search(criteria: ProductSearchCriteria, limit: Int, offset: Long): List<Product> {
        val (where, params) = whereClause(criteria)
        return jdbi.withHandle<List<Product>, Exception> { handle ->
            handle.createQuery("${ProductQuery.SELECT_WITH_CATEGORY} $where ORDER BY ${criteria.sort.orderBy} LIMIT :limit OFFSET :offset")
                .bindList("statuses", criteria.statuses.map { it.name })
                .bindMap(params + mapOf("limit" to limit, "offset" to offset))
                .map(rowMapper)
                .list()
        }
    }

    override fun count(criteria: ProductSearchCriteria): Long {
        val (where, params) = whereClause(criteria)
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("""
            SELECT COUNT(*)
            FROM ${PrefixConstant.ENTITY}PRODUCT p
            JOIN ${PrefixConstant.ENTITY}CATEGORY c ON c.categoryid = p.productcategoryid
            $where
            """)
                .bindList("statuses", criteria.statuses.map { it.name })
                .bindMap(params)
                .mapTo<Long>()
                .one()
        }
    }

    /**
     * Builds the WHERE clause from fixed SQL fragments only; every user value is a named parameter,
     * so search terms can never change the SQL.
     */
    private fun whereClause(criteria: ProductSearchCriteria): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf("p.productstatus IN (<statuses>)")
        val params = mutableMapOf<String, Any>()

        if (criteria.activeCategoryOnly) {
            conditions += "c.categorystatus = 'ACTIVE'"
        }
        criteria.categoryId?.let {
            conditions += "p.productcategoryid = :categoryId"
            params["categoryId"] = it
        }
        criteria.nameContains?.let {
            // Partial, case-insensitive name match (D5); uses the ix__ec_product__name_trgm index.
            conditions += "(p.productname::TEXT) ILIKE :namePattern ESCAPE '!'"
            params["namePattern"] = containsPattern(it)
        }
        criteria.minPrice?.let {
            conditions += "p.productsellingprice >= :minPrice"
            params["minPrice"] = it
        }
        criteria.maxPrice?.let {
            conditions += "p.productsellingprice <= :maxPrice"
            params["maxPrice"] = it
        }
        if (criteria.inStockOnly) {
            conditions += "p.productstockquantity > 0"
        }
        criteria.stockLevel?.let { conditions += "(${it.condition})" }
        return "WHERE " + conditions.joinToString(" AND ") to params
    }
}
