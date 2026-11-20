package com.tenant.service.tenant.service;

import com.tenant.service.common.exception.DuplicateTenantSlugException;
import com.tenant.service.common.exception.ResourceNotFoundException;
import com.tenant.service.membership.entity.MembershipRole;
import com.tenant.service.membership.entity.MembershipStatus;
import com.tenant.service.membership.entity.TenantMembership;
import com.tenant.service.membership.repository.TenantMembershipRepository;
import com.tenant.service.security.TenantAuthorizationService;
import com.tenant.service.subscription.dto.EntitlementResponse;
import com.tenant.service.subscription.entity.EntitlementType;
import com.tenant.service.subscription.entity.PlanEntitlement;
import com.tenant.service.subscription.entity.TenantSubscription;
import com.tenant.service.subscription.entity.TenantSubscriptionStatus;
import com.tenant.service.subscription.entity.SubscriptionEntitlement;
import com.tenant.service.subscription.repository.SubscriptionEntitlementRepository;
import com.tenant.service.subscription.repository.TenantSubscriptionRepository;
import com.tenant.service.subscription.service.SubscriptionPlanService;
import com.tenant.service.tenant.dto.CreateTenantRequest;
import com.tenant.service.tenant.dto.TenantResponse;
import com.tenant.service.tenant.entity.Tenant;
import com.tenant.service.tenant.entity.TenantStatus;
import com.tenant.service.tenant.repository.TenantRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TenantApplicationService {

    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository membershipRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final SubscriptionEntitlementRepository subscriptionEntitlementRepository;
    private final SubscriptionPlanService planService;
    private final TenantAuthorizationService authorizationService;

    public TenantApplicationService(
            TenantRepository tenantRepository,
            TenantMembershipRepository membershipRepository,
            TenantSubscriptionRepository subscriptionRepository,
            SubscriptionEntitlementRepository subscriptionEntitlementRepository,
            SubscriptionPlanService planService,
            TenantAuthorizationService authorizationService) {
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEntitlementRepository = subscriptionEntitlementRepository;
        this.planService = planService;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public TenantResponse create(UUID ownerUserId, CreateTenantRequest request) {
        SubscriptionPlanService.SelectedPlan selectedPlan =
                planService.selectActivePlan(request.subscriptionPlanCode());
        Instant now = Instant.now();
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder()
                .id(tenantId)
                .name(request.name().trim())
                .slug(request.slug().trim())
                .ownerUserId(ownerUserId)
                .status(TenantStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            tenantRepository.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("tenants_slug_key")) {
                throw new DuplicateTenantSlugException();
            }
            throw exception;
        }

        TenantMembership ownerMembership = membershipRepository.save(TenantMembership.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .userId(ownerUserId)
                .role(MembershipRole.OWNER)
                .status(MembershipStatus.ACTIVE)
                .owner(true)
                .joinedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        boolean free = selectedPlan.version().getPriceMinor() != null
                && selectedPlan.version().getPriceMinor() == 0;
        TenantSubscription subscription = subscriptionRepository.save(TenantSubscription.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .planVersionId(selectedPlan.version().getId())
                .status(free ? TenantSubscriptionStatus.ACTIVE : TenantSubscriptionStatus.PENDING_PAYMENT)
                .selectedAt(now)
                .startedAt(free ? now : null)
                .createdAt(now)
                .updatedAt(now)
                .build());

        List<SubscriptionEntitlement> snapshots = selectedPlan.entitlements().stream()
                .map(entitlement -> snapshot(subscription.getId(), entitlement)).toList();
        subscriptionEntitlementRepository.saveAll(snapshots);

        return response(tenant, ownerMembership, subscription, selectedPlan.plan().getCode(),
                selectedPlan.version().getVersion(), snapshots);
    }

    @Transactional(readOnly = true)
    public List<TenantResponse> list(UUID userId) {
        return membershipRepository.findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, MembershipStatus.ACTIVE)
                .stream()
                .map(membership -> tenantRepository.findByIdAndStatus(
                                membership.getTenantId(), TenantStatus.ACTIVE)
                        .map(tenant -> responseFor(tenant, membership))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantResponse get(UUID tenantId, UUID userId) {
        Tenant tenant = authorizationService.requireActiveMember(tenantId, userId);
        TenantMembership membership = membershipRepository
                .findByTenantIdAndUserIdAndStatus(tenantId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(ResourceNotFoundException::new);
        return responseFor(tenant, membership);
    }

    @Transactional
    public TenantResponse updateName(UUID tenantId, UUID userId, String name) {
        Tenant tenant = authorizationService.requireOwner(tenantId, userId);
        tenant.setName(name.trim());
        tenant.setUpdatedAt(Instant.now());
        tenantRepository.save(tenant);
        TenantMembership membership = membershipRepository
                .findByTenantIdAndUserIdAndStatus(tenantId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(ResourceNotFoundException::new);
        return responseFor(tenant, membership);
    }

    private TenantResponse responseFor(Tenant tenant, TenantMembership membership) {
        TenantSubscription subscription = subscriptionRepository.findByTenantId(tenant.getId())
                .orElseThrow(ResourceNotFoundException::new);
        var planVersion = planService.versionById(subscription.getPlanVersionId());
        var plan = planService.planById(planVersion.getPlanId());
        List<SubscriptionEntitlement> snapshots =
                subscriptionEntitlementRepository.findAllBySubscriptionIdOrderByEntitlementKeyAsc(subscription.getId());
        return response(tenant, membership, subscription, plan.getCode(), planVersion.getVersion(), snapshots);
    }

    private static TenantResponse response(
            Tenant tenant,
            TenantMembership membership,
            TenantSubscription subscription,
            String planCode,
            int planVersion,
            List<SubscriptionEntitlement> snapshots) {
        List<EntitlementResponse> entitlements = snapshots.stream()
                .map(value -> new EntitlementResponse(
                        value.getEntitlementKey(), value.getValueType(), value.value(), value.getUnit()))
                .toList();
        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getOwnerUserId(),
                tenant.getStatus(),
                tenant.getCreatedAt(),
                membership.getRole(),
                new TenantResponse.SubscriptionResponse(
                        subscription.getStatus(),
                        planCode,
                        planVersion,
                        subscription.getSelectedAt(),
                        subscription.getStartedAt(),
                        entitlements));
    }

    private static SubscriptionEntitlement snapshot(UUID subscriptionId, PlanEntitlement source) {
        return SubscriptionEntitlement.builder()
                .id(UUID.randomUUID())
                .subscriptionId(subscriptionId)
                .entitlementKey(source.getEntitlementKey())
                .valueType(EntitlementType.valueOf(source.getValueType().name()))
                .booleanValue(source.getBooleanValue())
                .integerValue(source.getIntegerValue())
                .stringValue(source.getStringValue())
                .unit(source.getUnit())
                .build();
    }
}
