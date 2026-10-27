package com.hyperscale.commerce;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class ExtractionRedisContainer extends GenericContainer<ExtractionRedisContainer> {

  public ExtractionRedisContainer(DockerImageName image) {
    super(image);
  }
}
