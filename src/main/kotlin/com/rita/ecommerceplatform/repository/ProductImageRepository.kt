package com.rita.ecommerceplatform.repository

import com.rita.ecommerceplatform.model.ProductImage
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** Product images (ec_product_image). Changes run with the product row locked, so the image limit and primary flag hold. */
@Repository
class ProductImageRepository(private val jdbcClient: JdbcClient) {

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

    /** Inserts an image and returns the generated productimageid. */
    fun insert(productId: Long, url: String, storageKey: String, altText: String?, sortOrder: Int, isPrimary: Boolean): Long =
        jdbcClient.sql(
            """
            INSERT INTO public.ec_product_image (
                productimageproductid, productimageurl, productimagestoragekey, productimagealttext,
                productimagesortorder, productimageisprimary
            )
            VALUES (:productId, :url, :storageKey, :altText, :sortOrder, :isPrimary)
            RETURNING productimageid
            """
        )
            .param("productId", productId)
            .param("url", url)
            .param("storageKey", storageKey)
            .param("altText", altText)
            .param("sortOrder", sortOrder)
            .param("isPrimary", isPrimary)
            .query(Long::class.javaObjectType)
            .single()

    /** The product's images: the primary first, then by sort order (uses ix__ec_productimage__productid_sortorder). */
    fun findByProduct(productId: Long): List<ProductImage> =
        jdbcClient.sql(
            """
            SELECT * FROM public.ec_product_image
            WHERE productimageproductid = :productId
            ORDER BY productimageisprimary DESC, productimagesortorder, productimageid
            """
        )
            .param("productId", productId)
            .query(rowMapper)
            .list()

    /** The images of several products (a page of a list) in one query, each product's in the same order as findByProduct. */
    fun findByProductIds(productIds: Collection<Long>): Map<Long, List<ProductImage>> {
        if (productIds.isEmpty()) return emptyMap()
        return jdbcClient.sql(
            """
            SELECT * FROM public.ec_product_image
            WHERE productimageproductid IN (:productIds)
            ORDER BY productimageproductid, productimageisprimary DESC, productimagesortorder, productimageid
            """
        )
            .param("productIds", productIds)
            .query(rowMapper)
            .list()
            .groupBy { it.productId }
    }

    /** Changes an image's alt text and sort order (the service passes both, merged with the current row). Returns rows updated. */
    fun update(imageId: Long, altText: String?, sortOrder: Int): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_product_image
            SET productimagealttext = :altText, productimagesortorder = :sortOrder, productimageupdatedat = $SQL_NOW
            WHERE productimageid = :imageId
            """
        )
            .param("imageId", imageId)
            .param("altText", altText)
            .param("sortOrder", sortOrder)
            .update()

    /**
     * Makes this image the product's only primary one: the old flag is cleared first, because
     * uq__ec_productimage__primary allows one primary at any moment. Call with the product row locked.
     */
    fun makePrimary(productId: Long, imageId: Long) {
        jdbcClient.sql(
            """
            UPDATE public.ec_product_image
            SET productimageisprimary = FALSE, productimageupdatedat = $SQL_NOW
            WHERE productimageproductid = :productId AND productimageisprimary AND productimageid <> :imageId
            """
        )
            .param("productId", productId)
            .param("imageId", imageId)
            .update()
        jdbcClient.sql(
            """
            UPDATE public.ec_product_image
            SET productimageisprimary = TRUE, productimageupdatedat = $SQL_NOW
            WHERE productimageid = :imageId AND NOT productimageisprimary
            """
        )
            .param("imageId", imageId)
            .update()
    }

    /** One of the product's images; null if it does not exist or belongs to another product. */
    fun findById(productId: Long, imageId: Long): ProductImage? =
        jdbcClient.sql("SELECT * FROM public.ec_product_image WHERE productimageid = :imageId AND productimageproductid = :productId")
            .param("imageId", imageId)
            .param("productId", productId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun countByProduct(productId: Long): Int =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_product_image WHERE productimageproductid = :productId")
            .param("productId", productId)
            .query(Int::class.javaObjectType)
            .single()

    /** The sort order for a new image: after every existing one. */
    fun nextSortOrder(productId: Long): Int =
        jdbcClient.sql("SELECT COALESCE(MAX(productimagesortorder) + 1, 0) FROM public.ec_product_image WHERE productimageproductid = :productId")
            .param("productId", productId)
            .query(Int::class.javaObjectType)
            .single()

    fun delete(imageId: Long): Int =
        jdbcClient.sql("DELETE FROM public.ec_product_image WHERE productimageid = :imageId")
            .param("imageId", imageId)
            .update()

    /** Makes the product's first image by sort order primary, if it has images and none is primary. Returns rows updated. */
    fun promoteFirstIfNoPrimary(productId: Long): Int =
        jdbcClient.sql(
            """
            UPDATE public.ec_product_image
            SET productimageisprimary = TRUE, productimageupdatedat = $SQL_NOW
            WHERE productimageid = (
                SELECT productimageid FROM public.ec_product_image
                WHERE productimageproductid = :productId
                ORDER BY productimagesortorder, productimageid
                LIMIT 1
            )
            AND NOT EXISTS (
                SELECT 1 FROM public.ec_product_image WHERE productimageproductid = :productId AND productimageisprimary
            )
            """
        )
            .param("productId", productId)
            .update()
}
