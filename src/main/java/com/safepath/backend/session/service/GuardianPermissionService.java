package com.safepath.backend.session.service;

import com.safepath.backend.session.model.MonitoringPermissionType;

/** Monitoring-side contract; implementations must propagate lookup failures rather than grant access. */
public interface GuardianPermissionService {

	boolean hasAcceptedRelationship(Long userId, Long guardianId);

	boolean hasPermission(Long userId, Long guardianId, MonitoringPermissionType permissionType);
}
