package com.tenant.service.subscription.service;

import com.tenant.service.common.exception.PlanUnavailableException;
import com.tenant.service.subscription.dto.EntitlementResponse;
import com.tenant.service.subscription.dto.SubscriptionPlanResponse;
import com.tenant.service.subscription.entity.PlanEntitlement;
import com.tenant.service.subscription.entity.PlanPublicationStatus;
import com.tenant.service.subscription.entity.PlanVersion;
import com.tenant.service.subscription.entity.SubscriptionPlan;
import com.tenant.service.subscription.repository.PlanEntitlementRepository;
import com.tenant.service.subscription.repository.PlanVersionRepository;
import com.tenant.service.subscription.repository.SubscriptionPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SubscriptionPlanService {

    private final SubscriptionPlanRepository planRepository;
    private final PlanVersionRepository versionRepository;
    private final PlanEntitlementRepository entitlementRepository;

    public SubscriptionPlanService(
            SubscriptionPlanRepository planRepository,
            PlanVersionRepository versionRepository,
            PlanEntitlementRepository entitlementRepository) {
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.entitlementRepository = entitlementRepository;
    }

    public List<SubscriptionPlanResponse> availablePlans() {
        return planRepository.findAllByActiveTrueOrderByCodeAsc().stream()
                .map(plan -> currentPublishedVersion(plan).map(version -> response(plan, version)))
                .flatMap(Optional::stream)
                .toList();
    }

    public SelectedPlan selectActivePlan(String code) {
        SubscriptionPlan plan = planRepository.findActiveByCodeForUpdate(code)
                .orElseThrow(() -> new PlanUnavailableException(code));
        PlanVersion version = versionRepository.findCurrentPublishedVersionsForUpdate(
                        plan.getId(), PlanPublicationStatus.PUBLISHED, Instant.now(),
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new PlanUnavailableException(code));
        return new SelectedPlan(plan, version,
                entitlementRepository.findAllByPlanVersionIdOrderByEntitlementKeyAsc(version.getId()));
    }

    public PlanVersion versionById(UUID id) {
        return versionRepository.findById(id).orElseThrow(() -> new PlanUnavailableException(id.toString()));
    }

    public SubscriptionPlan planById(UUID id) {
        return planRepository.findById(id).orElseThrow(() -> new PlanUnavailableException(id.toString()));
    }

    private Optional<PlanVersion> currentPublishedVersion(SubscriptionPlan plan) {
        return versionRepository
                .findFirstByPlanIdAndPublicationStatusAndEffectiveAtLessThanEqualOrderByVersionDesc(
                        plan.getId(), PlanPublicationStatus.PUBLISHED, Instant.now());
    }

    private SubscriptionPlanResponse response(SubscriptionPlan plan, PlanVersion version) {
        List<EntitlementResponse> entitlements =
                entitlementRepository.findAllByPlanVersionIdOrderByEntitlementKeyAsc(version.getId())
                        .stream()
                        .map(SubscriptionPlanService::entitlementResponse)
                        .toList();
        return new SubscriptionPlanResponse(
                plan.getCode(),
                plan.getName(),
                plan.getDescription(),
                version.getVersion(),
                version.getPriceMinor(),
                version.getCurrency(),
                version.getBillingInterval(),
                entitlements);
    }

    public static EntitlementResponse entitlementResponse(PlanEntitlement entitlement) {
        return new EntitlementResponse(
                entitlement.getEntitlementKey(),
                entitlement.getValueType(),
                entitlement.value(),
                entitlement.getUnit());
    }

    public record SelectedPlan(
            SubscriptionPlan plan,
            PlanVersion version,
            List<PlanEntitlement> entitlements) {
    }
}
