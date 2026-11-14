package com.safepath.backend.guardian;

import com.safepath.backend.guardianrisk.ApiException;
import com.safepath.backend.guardian.GuardianModels.*;
import com.safepath.backend.guardianrisk.CurrentUser.Actor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class GuardianRegistrationService {
    private static final Logger log = LoggerFactory.getLogger(GuardianRegistrationService.class);
    private final GuardianRepository repository;
    private final GuardianRequestNotifier notifier;

    public GuardianRegistrationService(GuardianRepository repository, GuardianRequestNotifier notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }

    @Transactional
    public Registration requestRegistration(Actor actor, long guardianId) {
        if (actor.id() == guardianId) throw ApiException.badRequest("자기 자신을 보호자로 등록할 수 없습니다.");
        // The account module's findById is not present in this checkout. Reuse the existing
        // database lookup until its real implementation can be injected for both IDs.
        if (!repository.userExists(actor.id())) throw ApiException.notFound("이용자 회원");
        if (!repository.userExists(guardianId)) throw ApiException.notFound("보호자 회원");
        if (repository.findRegistration(actor.id(), guardianId).isPresent()) {
            // Every existing state remains a conflict, including rejected/revoked relations.
            throw ApiException.conflict("같은 사용자와 보호자의 등록이 이미 있습니다.");
        }
        // ON CONFLICT also covers another request inserting the same pair after the lookup.
        Registration registration = repository.request(actor.id(), guardianId)
                .orElseThrow(() -> ApiException.conflict("같은 사용자와 보호자의 등록이 이미 있습니다."));
        notifyGuardianAfterCommit(actor.id(), guardianId);
        return registration;
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

    @Transactional
    public Registration acceptRegistration(Actor actor, long id) { return respond(actor, id, Status.ACCEPTED); }

    @Transactional
    public Registration rejectRegistration(Actor actor, long id) { return respond(actor, id, Status.REJECTED); }

    private Registration respond(Actor actor, long id, Status target) {
        Registration registration = get(id, true);
        actor.requireOwner(registration.guardianId());
        requireState(registration, Status.PENDING);
        repository.setStatus(id, target);
        return get(id, false);
    }

    @Transactional
    public Registration revokeRegistration(Actor actor, long id) {
        Registration registration = get(id, true);
        requirePartyOrAdmin(actor, registration);
        if (registration.status() == Status.REJECTED || registration.status() == Status.REVOKED) {
            throw ApiException.conflict("이미 종료된 등록입니다.");
        }
        repository.setStatus(id, Status.REVOKED);
        return get(id, false);
    }

    @Transactional
    public Registration suspendRegistration(Actor actor, long id) {
        actor.requireAdmin();
        Registration registration = get(id, true);
        if (registration.status() != Status.PENDING && registration.status() != Status.ACCEPTED) {
            throw ApiException.conflict("요청 중이거나 수락된 등록만 중지할 수 있습니다.");
        }
        repository.setStatus(id, Status.SUSPENDED);
        return get(id, false);
    }

    @Transactional
    public Registration updatePermission(Actor actor, long id, PermissionRequest permissions) {
        Registration registration = get(id, true);
        // Ownership first: the owner's request needs no Firebase admin lookup.
        if (actor.id() != registration.userId()) actor.requireAdmin();
        requireState(registration, Status.ACCEPTED);
        repository.setPermissions(id, permissions);
        return get(id, false);
    }

    @Transactional(readOnly = true)
    public PermissionResponse checkPermission(Actor actor, long id, PermissionType type) {
        Registration registration = get(id, false);
        requirePartyOrAdmin(actor, registration);
        return new PermissionResponse(registration.allows(type));
    }

    private Registration get(long id, boolean lock) {
        return repository.find(id, lock).orElseThrow(() -> ApiException.notFound("보호자 등록"));
    }

    private void requirePartyOrAdmin(Actor actor, Registration registration) {
        // Parties are checked first so they never need the Firebase admin lookup.
        if (actor.id() != registration.userId() && actor.id() != registration.guardianId()) {
            actor.requireAdmin();
        }
    }

    private void requireState(Registration registration, Status expected) {
        if (registration.status() != expected) throw ApiException.conflict("현재 등록 상태에서는 처리할 수 없습니다.");
    }
}
