package com.tenant.service.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "subscription_entitlements", schema = "tenant_service")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionEntitlement {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "entitlement_key", nullable = false, length = 128, updatable = false)
    private String entitlementKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 16, updatable = false)
    private EntitlementType valueType;

    @Column(name = "boolean_value")
    private Boolean booleanValue;

    @Column(name = "integer_value")
    private Long integerValue;

    @Column(name = "string_value", length = 1000)
    private String stringValue;

    @Column(length = 32)
    private String unit;

    public Object value() {
        return switch (valueType) {
            case BOOLEAN -> booleanValue;
            case INTEGER -> integerValue;
            case STRING -> stringValue;
        };
    }
}
