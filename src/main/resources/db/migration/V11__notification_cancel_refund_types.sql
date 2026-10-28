-- =====================================================================
-- V11: Cancel and refund emails (checkpoint 2.2)
-- =====================================================================
-- Adds ORDER_CANCELLED and ORDER_REFUNDED to the allowed notification
-- types (both now, so the refund step needs no migration of its own),
-- and adds them to V10's one-email-per-order-event index: an order is
-- cancelled or refunded at most once, so it gets at most one such email.
-- =====================================================================

ALTER TABLE public.ec_notification
    DROP CONSTRAINT IF EXISTS ck__ec_notification__type,
    ADD CONSTRAINT ck__ec_notification__type CHECK (notificationtype IN (
        'REGISTRATION', 'ORDER_CONFIRMATION', 'PAYMENT_CONFIRMATION', 'ORDER_SHIPPED', 'ORDER_DELIVERED',
        'ORDER_CANCELLED', 'ORDER_REFUNDED', 'PASSWORD_RESET', 'PASSWORD_CHANGED'
    ));

DROP INDEX IF EXISTS public.uq__ec_notification__order_type;

CREATE UNIQUE INDEX IF NOT EXISTS uq__ec_notification__order_type
    ON public.ec_notification (notificationorderid, notificationtype)
    WHERE notificationtype IN ('ORDER_CONFIRMATION', 'PAYMENT_CONFIRMATION', 'ORDER_SHIPPED', 'ORDER_DELIVERED',
                               'ORDER_CANCELLED', 'ORDER_REFUNDED');
