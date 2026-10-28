-- =====================================================================
-- V12: Stock is put back at most once per cancelled order (checkpoint 2.2)
-- =====================================================================
-- An admin cancel of a paid order writes one ORDER_CANCELLED movement per
-- product. The code already does this once (the order row is locked and
-- CANCELLED is final); this index makes it a database guarantee, so a
-- second restore for the same order and product can never be saved.
-- (An order has each product once: uq__ec_orderitem__orderid_productid.)
-- =====================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq__ec_inventorymovement__order_cancel
    ON public.ec_inventory_movement (inventorymovementorderid, inventorymovementproductid)
    WHERE inventorymovementtype = 'ORDER_CANCELLED';
