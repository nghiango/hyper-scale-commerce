package com.hyperscale.commerce.modules.catalog.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(schema = "catalog", name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProductEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  @Column(name = "sku", nullable = false, unique = true, length = 255)
  private String sku;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "price", nullable = false)
  private int price;

  @Column(name = "availability", nullable = false, length = 50)
  private String availability;

  @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false)
  private Instant updatedAt;

}
