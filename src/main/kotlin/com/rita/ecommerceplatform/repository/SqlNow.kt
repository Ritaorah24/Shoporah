package com.rita.ecommerceplatform.repository

/**
 * "Now" in the platform's time convention (UTC+1). Same expression as the
 * ...createdat / ...updatedat column defaults in the migrations. Column
 * defaults only apply on INSERT, so UPDATE statements set it explicitly.
 */
const val SQL_NOW = "((timezone('UTC', CURRENT_TIMESTAMP(6)))::TIMESTAMP + (1::NUMERIC || ' HOUR')::INTERVAL)"
