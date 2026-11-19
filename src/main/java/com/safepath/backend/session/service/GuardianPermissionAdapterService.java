package com.safepath.backend.session.service;

import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.session.model.MonitoringPermissionType;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class GuardianPermissionAdapterService implements GuardianPermissionService {
	private final GuardianRegistrationService guardianRegistrationService;

	public GuardianPermissionAdapterService(GuardianRegistrationService guardianRegistrationService) {
		this.guardianRegistrationService = Objects.requireNonNull(guardianRegistrationService);
	}

	@Override
	public boolean hasAcceptedRelationship(Long userId, Long guardianId) {
		validateIds(userId, guardianId);
		return guardianRegistrationService.getGuardianAccess(userId, guardianId).accepted();
	}

	@Override
	public boolean hasPermission(Long userId, Long guardianId, MonitoringPermissionType permissionType) {
		validateIds(userId, guardianId);
		if (permissionType == null) {
			throw new IllegalArgumentException("permissionType must not be null");
		}
		PermissionType type = switch (permissionType) {
			case STATUS -> PermissionType.LOCATION;
			case LOCATION -> PermissionType.LOCATION;
		};
		return guardianRegistrationService.hasGuardianPermission(userId, guardianId, type);
	}

	private void validateIds(Long userId, Long guardianId) {
		if (userId == null || userId <= 0 || guardianId == null || guardianId <= 0) {
			throw new IllegalArgumentException("User and guardian IDs must be positive numbers");
		}
	}
}
