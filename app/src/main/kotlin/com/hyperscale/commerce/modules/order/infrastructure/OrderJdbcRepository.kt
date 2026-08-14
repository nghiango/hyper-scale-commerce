package com.hyperscale.commerce.modules.order.infrastructure

import org.springframework.data.repository.CrudRepository

interface OrderJdbcRepository : CrudRepository<OrderEntity, Long>
