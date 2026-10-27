package com.hyperscale.commerce.orderquery.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class IsolatedRedisContainer extends GenericContainer<IsolatedRedisContainer> {
  public IsolatedRedisContainer(DockerImageName image) {
    super(image);
  }
}
