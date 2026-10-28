-- =====================================================================
-- V10: At most one email per lifecycle event (checkpoint 2.1)
-- =====================================================================
-- The code already queues each of these once (the events themselves
-- happen once, behind conditional updates); these indexes make it a
-- database guarantee. NotificationRepository.insertPending uses
-- ON CONFLICT DO NOTHING, so a duplicate is skipped instead of failing
-- (and rolling back) the order, payment or registration it belongs to.
-- Password emails are left out: a customer may change a password often.
-- =====================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq__ec_notification__order_type
    ON public.ec_notification (notificationorderid, notificationtype)
    WHERE notificationtype IN ('ORDER_CONFIRMATION', 'PAYMENT_CONFIRMATION', 'ORDER_SHIPPED', 'ORDER_DELIVERED');

CREATE UNIQUE INDEX IF NOT EXISTS uq__ec_notification__registration
    ON public.ec_notification (notificationcustomerid)
    WHERE notificationtype = 'REGISTRATION';
