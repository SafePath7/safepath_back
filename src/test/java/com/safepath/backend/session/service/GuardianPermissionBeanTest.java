package com.safepath.backend.session.service;

import com.safepath.backend.monitoring.service.GuardianMonitoringService;
import com.safepath.backend.monitoring.service.GuardianMonitoringQueryService;

import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.service.GuardianRequestNotifier;
import com.safepath.backend.risk.service.RiskSessionBridge;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardianPermissionBeanTest {
	@Test
	void componentScanConnectsRealAdapterRegistrationAndMonitoringServices() {
		GuardianRegistrationRepository repository = mock(GuardianRegistrationRepository.class);
		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(com.safepath.backend.user.UserService.class,
					() -> mock(com.safepath.backend.user.UserService.class));
			context.registerBean(GuardianRegistrationRepository.class, () -> repository);
			context.registerBean(GuardianRequestNotifier.class, () -> mock(GuardianRequestNotifier.class));
			context.registerBean(com.safepath.backend.notification.NotificationService.class,
					() -> mock(com.safepath.backend.notification.NotificationService.class));
			context.registerBean(RiskSessionBridge.class, () -> mock(RiskSessionBridge.class));
			context.register(GuardianRegistrationService.class, InMemoryChannelRegistry.class);
			context.scan("com.safepath.backend.session.service", "com.safepath.backend.session.store",
					"com.safepath.backend.monitoring.service");
			context.refresh();


			assertEquals(1, context.getBeansOfType(GuardianMonitoringQueryService.class).size());
			var lifecycle = context.getBean(SafeReturnSessionService.class);

			var monitoring = context.getBean(GuardianMonitoringService.class);

			when(repository.findByUserIdAndGuardianId(42L, 43L)).thenReturn(Optional.empty());
			assertThrows(AccessDeniedException.class, () -> lifecycle.validateForStart(42L, java.util.List.of(43L)));

			GuardianRegistration registration = BeanUtils.instantiateClass(GuardianRegistration.class);
			registration.changeStatus(RegistrationStatus.PENDING);
			registration.changePermissions(true, true, false);
			when(repository.findByUserIdAndGuardianId(42L, 43L)).thenReturn(Optional.of(registration));
			assertThrows(AccessDeniedException.class, () -> lifecycle.validateForStart(42L, java.util.List.of(43L)));
			registration.changeStatus(RegistrationStatus.ACCEPTED);
			var session = lifecycle.startSession(42L, java.util.List.of(43L));
			assertEquals(session, monitoring.getMonitoringStatus(session.id(), 43L));
			monitoring.getGuardianChannelId(session.id(), 43L);
			registration.changePermissions(false, true, false);
			monitoring.getGuardianChannelId(session.id(), 43L);
			registration.changeStatus(RegistrationStatus.REVOKED);
			assertThrows(AccessDeniedException.class, () -> monitoring.getMonitoringStatus(session.id(), 43L));
		}
	}
}
