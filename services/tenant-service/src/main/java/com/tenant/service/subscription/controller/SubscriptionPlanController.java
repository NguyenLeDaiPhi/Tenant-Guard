package com.tenant.service.subscription.controller;

import com.tenant.service.subscription.dto.SubscriptionPlanResponse;
import com.tenant.service.subscription.service.SubscriptionPlanService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/subscription-plans")
public class SubscriptionPlanController {

    private final SubscriptionPlanService planService;

    public SubscriptionPlanController(SubscriptionPlanService planService) {
        this.planService = planService;
    }

    @GetMapping("/plans")
    public List<SubscriptionPlanResponse> list() {
        return planService.availablePlans();
    }
}
