package com.netflix.spinnaker.fiat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = {Main.class})
@TestPropertySource(properties = {"spring.config.location=classpath:fiat-test.yml"})
public class MainSpec {

  @Test
  public void startupTest() {}
}
