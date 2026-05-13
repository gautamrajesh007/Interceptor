package com.proxy.interceptor;

import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@RequiredArgsConstructor
@Slf4j
public class InterceptorApplication {

	private final AuditService auditService;

	public static void main(String[] args) {
		SpringApplication.run(InterceptorApplication.class, args);
	}

	@Bean
	CommandLineRunner initAdmin(AuthService authService,
								AuditService auditService,
								@Value("${admin.username}") String username,
								@Value("${admin.password}") String password
	) {
		return args -> {
			// Create default admin user if not exists
			boolean created = authService.createAdminIfNotExists(username, password);
			if (created) {
				auditService.log("SYSTEM", "admin_user_created",
						String.format("Default admin user created: %s", username),
						"localhost");
			}
			log.info("	=========================================");
			log.info("		Interceptor Proxy v2.0 Started");
			log.info("	=========================================");
			log.info("		Dashboard: https://localhost");
			log.info("		Proxy Port: 5432");
			log.info("		DB Engine: localhost:5433");
			log.info("	=========================================");
		};
	}

}
