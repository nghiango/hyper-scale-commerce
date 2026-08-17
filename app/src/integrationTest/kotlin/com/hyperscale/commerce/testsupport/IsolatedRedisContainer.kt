package com.hyperscale.commerce.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class IsolatedRedisContainer(image: DockerImageName) :
    GenericContainer<IsolatedRedisContainer>(image)
