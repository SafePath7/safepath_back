package com.safepath.backend.guardian.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.common.util.UtcTime;
import com.safepath.backend.guardian.dto.GuardianAccess;
import com.safepath.backend.guardian.dto.PermissionRequest;
import com.safepath.backend.guardian.dto.PermissionResponse;
import com.safepath.backend.guardian.dto.RegistrationResponse;
import com.safepath.backend.guardian.dto.RegistrationRole;
import com.safepath.backend.guardian.dto.RegistrationSummaryResponse;
import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository.MemberName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class GuardianRegistrationService {
    private static final Logger log = LoggerFactory.getLogger(GuardianRegistrationService.class);
    private static final String DUPLICATE = "같은 사용자와 보호자의 등록이 이미 있습니다.";
    private static final String SUSPENDED_BY_ADMIN ="관리자에 의해 중지된 등록은 다시 요청할 수 없습니다.";
    private final GuardianRegistrationRepository repository;
    private final GuardianRequestNotifier notifier;

    public GuardianRegistrationService(GuardianRegistrationRepository repository, GuardianRequestNotifier notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }

    @Transactional
    public RegistrationResponse requestRegistration(Actor actor, long guardianId) {
        if (actor.id() == guardianId) throw ApiException.badRequest("자기 자신을 보호자로 등록할 수 없습니다.");
        if (!repository.memberExists(actor.id())) throw ApiException.notFound("이용자 회원");
        if (!repository.memberExists(guardianId)) throw ApiException.notFound("보호자 회원");
        Optional<GuardianRegistration> existing =
                repository.findForUpdateByUserIdAndGuardianId(actor.id(), guardianId);
        if (existing.isPresent()) return reRequest(existing.get());
        // Zero rows means another request inserted the same pair after the lookup.
        if (repository.insertIfAbsent(actor.id(), guardianId, UtcTime.now()) == 0) {
            throw ApiException.conflict(DUPLICATE);
        }
        GuardianRegistration registration = repository.findByUserIdAndGuardianId(actor.id(), guardianId)
                .orElseThrow(() -> ApiException.conflict(DUPLICATE));
        notifyGuardianAfterCommit(actor.id(), guardianId);
        return RegistrationResponse.from(registration);
    }

    /**
     * Re-request policy for an existing pair (the row is locked by the caller):
     * PENDING/ACCEPTED - duplicate, conflict.
     * SUSPENDED - an admin decision; the user cannot lift it by asking again, conflict.
     * REJECTED/REVOKED - ended relations; the row is reset to a fresh PENDING request with
     * every view permission off, and the guardian is notified again.
     */
    private RegistrationResponse reRequest(GuardianRegistration registration) {
        switch (registration.getStatus()) {
            case PENDING, ACCEPTED -> throw ApiException.conflict(DUPLICATE);
            case SUSPENDED -> throw ApiException.conflict(SUSPENDED_BY_ADMIN);
            case REJECTED, REVOKED -> {
                registration.reopenAsPending();
                GuardianRegistration saved = repository.saveAndFlush(registration);
                notifyGuardianAfterCommit(saved.getUserId(), saved.getGuardianId());
                return RegistrationResponse.from(saved);
            }
        }
        throw new IllegalStateException("Unknown registration status: " + registration.getStatus());
    }

    /** The notification module expects this push right after the request is saved. */
    private void notifyGuardianAfterCommit(long requesterId, long guardianId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifyGuardian(requesterId, guardianId);
            return;
        }
        // A rolled-back request never reaches afterCommit, so no push is sent for it.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyGuardian(requesterId, guardianId);
            }
        });
    }

    private void notifyGuardian(long requesterId, long guardianId) {
        try {
            notifier.notifyGuardianRequested(requesterId, guardianId);
        } catch (RuntimeException exception) {
            // The registration is already saved; a push failure must not turn it into an error.
            log.error("Guardian request notification failed: requesterId={}, guardianId={}",
                    requesterId, guardianId, exception);
        }
    }

    /** The caller's own registrations only, newest first; role and status are optional filters. */
    @Transactional(readOnly = true)
    public List<RegistrationSummaryResponse> listRegistrations(Actor actor, RegistrationRole role,
            RegistrationStatus status) {
        List<GuardianRegistration> registrations = (role == RegistrationRole.USER
                ? repository.findByUserIdOrderByIdDesc(actor.id())
                : role == RegistrationRole.GUARDIAN
                        ? repository.findByGuardianIdOrderByIdDesc(actor.id())
                        : repository.findByMemberIdOrderByIdDesc(actor.id()))
                .stream().filter(registration -> status == null || registration.getStatus() == status).toList();
        if (registrations.isEmpty()) return List.of();

        Set<Long> memberIds = new HashSet<>();
        for (GuardianRegistration registration : registrations) {
            memberIds.add(registration.getUserId());
            memberIds.add(registration.getGuardianId());
        }
        Map<Long, String> names = repository.findMemberNames(memberIds).stream()
                .collect(Collectors.toMap(MemberName::getId, MemberName::getName));
        return registrations.stream()
                .map(registration -> RegistrationSummaryResponse.of(registration,
                        names.get(registration.getUserId()), names.get(registration.getGuardianId())))
                .toList();
    }

    @Transactional
    public RegistrationResponse acceptRegistration(Actor actor, long id) {
        return respond(actor, id, RegistrationStatus.ACCEPTED);
    }

    @Transactional
    public RegistrationResponse rejectRegistration(Actor actor, long id) {
        return respond(actor, id, RegistrationStatus.REJECTED);
    }

    private RegistrationResponse respond(Actor actor, long id, RegistrationStatus target) {
        GuardianRegistration registration = getForUpdate(id);
        actor.requireOwner(registration.getGuardianId());
        requireState(registration, RegistrationStatus.PENDING);
        return changeStatus(registration, target);
    }

    @Transactional
    public RegistrationResponse revokeRegistration(Actor actor, long id) {
        GuardianRegistration registration = getForUpdate(id);
        requirePartyOrAdmin(actor, registration);
        if (registration.getStatus() == RegistrationStatus.REJECTED
                || registration.getStatus() == RegistrationStatus.REVOKED) {
            throw ApiException.conflict("이미 종료된 등록입니다.");
        }
        return changeStatus(registration, RegistrationStatus.REVOKED);
    }

    @Transactional
    public RegistrationResponse suspendRegistration(Actor actor, long id) {
        actor.requireAdmin();
        GuardianRegistration registration = getForUpdate(id);
        if (registration.getStatus() != RegistrationStatus.PENDING
                && registration.getStatus() != RegistrationStatus.ACCEPTED) {
            throw ApiException.conflict("요청 중이거나 수락된 등록만 중지할 수 있습니다.");
        }
        return changeStatus(registration, RegistrationStatus.SUSPENDED);
    }

    @Transactional
    public RegistrationResponse updatePermission(Actor actor, long id, PermissionRequest permissions) {
        GuardianRegistration registration = getForUpdate(id);
        // Ownership first: the owner's request needs no Firebase admin lookup.
        if (actor.id() != registration.getUserId()) actor.requireAdmin();
        requireState(registration, RegistrationStatus.ACCEPTED);
        registration.changePermissions(permissions.canViewLocation(), permissions.canViewStatus(),
                permissions.canViewPersonalInfo());
        return RegistrationResponse.from(repository.saveAndFlush(registration));
    }

    @Transactional(readOnly = true)
    public PermissionResponse checkPermission(Actor actor, long id, PermissionType type) {
        GuardianRegistration registration = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("보호자 등록"));
        requirePartyOrAdmin(actor, registration);
        return new PermissionResponse(registration.allows(type));
    }

    /**
     * For other modules: whether guardianId's registration for userId is accepted and
     * which of LOCATION/STATUS it may view. No registration, or one that is not
     * ACCEPTED (pending, rejected, revoked, suspended), yields all false.
     */
    @Transactional(readOnly = true)
    public GuardianAccess getGuardianAccess(long userId, long guardianId) {
        return repository.findByUserIdAndGuardianId(userId, guardianId)
                .filter(registration -> registration.getStatus() == RegistrationStatus.ACCEPTED)
                .map(registration -> new GuardianAccess(true,
                        registration.isCanViewLocation(), registration.isCanViewStatus()))
                .orElse(GuardianAccess.NONE);
    }

    /** For other modules: true only for an ACCEPTED registration that grants the permission. */
    @Transactional(readOnly = true)
    public boolean hasGuardianPermission(long userId, long guardianId, PermissionType type) {
        return repository.findByUserIdAndGuardianId(userId, guardianId)
                .map(registration -> registration.allows(type))
                .orElse(false);
    }

    private RegistrationResponse changeStatus(GuardianRegistration registration, RegistrationStatus status) {
        registration.changeStatus(status);
        return RegistrationResponse.from(repository.saveAndFlush(registration));
    }

    /** The row lock keeps two concurrent state changes from both passing the state check. */
    private GuardianRegistration getForUpdate(long id) {
        return repository.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("보호자 등록"));
    }

    private void requirePartyOrAdmin(Actor actor, GuardianRegistration registration) {
        // Parties are checked first so they never need the Firebase admin lookup.
        if (actor.id() != registration.getUserId() && actor.id() != registration.getGuardianId()) {
            actor.requireAdmin();
        }
    }

    private void requireState(GuardianRegistration registration, RegistrationStatus expected) {
        if (registration.getStatus() != expected) throw ApiException.conflict("현재 등록 상태에서는 처리할 수 없습니다.");
    }
}
