package org.veltismc.veltis.command.permission;

import java.time.Instant;

/**
 * A temporary permission grant or denial attached to a source.
 *
 * <p>Attachments are used for transient permission changes that
 * expire after a given duration or when a condition is met.
 *
 * @param permission the permission being granted or denied
 * @param negated    true if this attachment denies the permission
 * @param createdAt  when this attachment was created
 * @param expiresAt  when this attachment expires (null = never)
 * @param reason     human-readable reason for this attachment
 */
public record PermissionAttachment(
    Permission permission,
    boolean negated,
    Instant createdAt,
    Instant expiresAt,
    String reason
) {

    /**
     * Creates a temporary grant.
     */
    public static PermissionAttachment grant(Permission permission, Instant expiresAt) {
        return new PermissionAttachment(permission, false, Instant.now(), expiresAt, "");
    }

    /**
     * Creates a temporary grant with a reason.
     */
    public static PermissionAttachment grant(Permission permission, Instant expiresAt, String reason) {
        return new PermissionAttachment(permission, false, Instant.now(), expiresAt, reason);
    }

    /**
     * Creates a temporary denial.
     */
    public static PermissionAttachment deny(Permission permission, Instant expiresAt) {
        return new PermissionAttachment(permission, true, Instant.now(), expiresAt, "");
    }

    /**
     * Creates a permanent grant (no expiry).
     */
    public static PermissionAttachment permanent(Permission permission) {
        return new PermissionAttachment(permission, false, Instant.now(), null, "");
    }

    /**
     * Returns true if this attachment has expired.
     */
    public boolean expired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}


