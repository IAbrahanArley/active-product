package io.github.iabrahanarley.active;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.processing.enabled=false")
class ProductActivationServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
