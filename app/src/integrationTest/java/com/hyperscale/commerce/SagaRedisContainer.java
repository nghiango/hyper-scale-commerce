package com.hyperscale.commerce;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class SagaRedisContainer extends GenericContainer<SagaRedisContainer> {

  public SagaRedisContainer(DockerImageName image) {
    super(image);
  }
}
