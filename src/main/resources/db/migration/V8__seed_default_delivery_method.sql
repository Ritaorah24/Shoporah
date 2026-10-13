-- =====================================================================
-- V8: Seed the default delivery method
-- =====================================================================
-- ec_order.orderdeliverymethodid is NOT NULL, but delivery-method
-- selection only arrives in milestone V2. Until then every order uses
-- this STANDARD method (fee 0). In V2, admins can change its fee and add
-- other methods.
--
-- ON CONFLICT ... DO NOTHING keeps the script safe to re-run.
-- =====================================================================

INSERT INTO public.ec_delivery_method (
    deliverymethodcode,
    deliverymethodname,
    deliverymethoddescription,
    deliverymethodfee
)
VALUES (
    'STANDARD',
    'Standard Delivery',
    'Default delivery method',
    0
)
ON CONFLICT (deliverymethodcode) DO NOTHING;
