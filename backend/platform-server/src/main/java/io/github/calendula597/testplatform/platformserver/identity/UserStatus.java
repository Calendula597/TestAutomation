package io.github.calendula597.testplatform.platformserver.identity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * Canonical user lifecycle states; disabled users lose live sessions.
 */
public enum UserStatus {
    @EnumValue
    ACTIVE,
    @EnumValue
    DISABLED
}
