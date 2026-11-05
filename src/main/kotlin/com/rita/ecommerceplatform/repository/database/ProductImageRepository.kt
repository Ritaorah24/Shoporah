package com.rita.ecommerceplatform.repository.database

import com.rita.ecommerceplatform.repository.database.query.ProductImageQuery
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import com.rita.ecommerceplatform.model.entity.ProductImage
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** Product images (ec_product_image). Changes run with the product row locked, so the image limit and primary flag hold. */
interface ProductImageRepository {
    /** Inserts an image and returns the generated productimageid. */
    fun insert(productId: Long, url: String, storageKey: String, altText: String?, sortOrder: Int, isPrimary: Boolean): Long

    /** The product's images: the primary first, then by sort order (uses ix__ec_productimage__productid_sortorder). */
    fun findByProduct(productId: Long): List<ProductImage>

    /** The images of several products (a page of a list) in one query, each product's in the same order as findByProduct. */
    fun findByProductIds(productIds: Collection<Long>): Map<Long, List<ProductImage>>

    /** Changes an image's alt text and sort order (the service passes both, merged with the current row). Returns rows updated. */
    fun update(imageId: Long, altText: String?, sortOrder: Int): Int

    /**
     * Makes this image the product's only primary one: the old flag is cleared first, because
     * uq__ec_productimage__primary allows one primary at any moment. Call with the product row locked.
     */
    fun makePrimary(productId: Long, imageId: Long)

    /** One of the product's images; null if it does not exist or belongs to another product. */
    fun findById(productId: Long, imageId: Long): ProductImage?

    fun countByProduct(productId: Long): Int

    /** The sort order for a new image: after every existing one. */
    fun nextSortOrder(productId: Long): Int

    fun delete(imageId: Long): Int

    /** Makes the product's first image by sort order primary, if it has images and none is primary. Returns rows updated. */
    fun promoteFirstIfNoPrimary(productId: Long): Int
}

@Repository
class ProductImageRepositoryImpl(private val jdbi: Jdbi) : ProductImageRepository {

    private val rowMapper = RowMapper { rs, _ ->
        ProductImage(
            imageId = rs.getLong("productimageid"),
            productId = rs.getLong("productimageproductid"),
            url = rs.getString("productimageurl"),
            storageKey = rs.getString("productimagestoragekey"),
            altText = rs.getString("productimagealttext"),
            sortOrder = rs.getInt("productimagesortorder"),
            isPrimary = rs.getBoolean("productimageisprimary"),
            createdAt = rs.getObject("productimagecreatedat", LocalDateTime::class.java),
            updatedAt = rs.getObject("productimageupdatedat", LocalDateTime::class.java),
        )
    }

    override fun insert(productId: Long, url: String, storageKey: String, altText: String?, sortOrder: Int, isPrimary: Boolean): Long =
        jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(ProductImageQuery.INSERT)
                .bind("productId", productId)
                .bind("url", url)
                .bind("storageKey", storageKey)
                .bind("altText", altText)
                .bind("sortOrder", sortOrder)
                .bind("isPrimary", isPrimary)
                .mapTo<Long>()
                .one()
        }

    override fun findByProduct(productId: Long): List<ProductImage> =
        jdbi.withHandle<List<ProductImage>, Exception> { handle ->
            handle.createQuery(ProductImageQuery.FIND_BY_PRODUCT)
                .bind("productId", productId)
                .map(rowMapper)
                .list()
        }

    override fun findByProductIds(productIds: Collection<Long>): Map<Long, List<ProductImage>> {
        if (productIds.isEmpty()) return emptyMap()
        return jdbi.withHandle<List<ProductImage>, Exception> { handle ->
            handle.createQuery(ProductImageQuery.FIND_BY_PRODUCT_IDS)
                .bindList("productIds", productIds)
                .map(rowMapper)
                .list()
        }
            .groupBy { it.productId }
    }

    override fun update(imageId: Long, altText: String?, sortOrder: Int): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(ProductImageQuery.UPDATE)
                .bind("imageId", imageId)
                .bind("altText", altText)
                .bind("sortOrder", sortOrder)
                .execute()
        }

    override fun makePrimary(productId: Long, imageId: Long) {
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(ProductImageQuery.MAKE_PRIMARY)
                .bind("productId", productId)
                .bind("imageId", imageId)
                .execute()
        }
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(ProductImageQuery.MAKE_PRIMARY_2)
                .bind("imageId", imageId)
                .execute()
        }
    }

    override fun findById(productId: Long, imageId: Long): ProductImage? =
        jdbi.withHandle<ProductImage?, Exception> { handle ->
            handle.createQuery(ProductImageQuery.FIND_BY_ID)
                .bind("imageId", imageId)
                .bind("productId", productId)
                .map(rowMapper)
                .findOne()
                .orElse(null)
        }

    override fun countByProduct(productId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(ProductImageQuery.COUNT_BY_PRODUCT)
                .bind("productId", productId)
                .mapTo<Int>()
                .one()
        }

    override fun nextSortOrder(productId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(ProductImageQuery.NEXT_SORT_ORDER)
                .bind("productId", productId)
                .mapTo<Int>()
                .one()
        }

    override fun delete(imageId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(ProductImageQuery.DELETE)
                .bind("imageId", imageId)
                .execute()
        }

    override fun promoteFirstIfNoPrimary(productId: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(ProductImageQuery.PROMOTE_FIRST_IF_NO_PRIMARY)
                .bind("productId", productId)
                .execute()
        }
}
