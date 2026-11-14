package io.github.calendula597.testplatform.platformserver.identity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * Platform-level roles. The first initialized account is an ADMIN.
 */
public enum UserRole {
    @EnumValue
    ADMIN,
    @EnumValue
    MEMBER
}
