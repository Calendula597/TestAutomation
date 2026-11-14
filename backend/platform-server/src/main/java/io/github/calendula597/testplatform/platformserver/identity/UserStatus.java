package io.github.calendula597.testplatform.platformserver.identity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * Canonical user lifecycle states (PRD #1: removed users lose access).
 */
public enum UserStatus {
    @EnumValue
    ACTIVE,
    @EnumValue
    DISABLED
}
