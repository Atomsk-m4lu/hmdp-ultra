package com.hmdp.shopdetail.infrastructure.notification;

import com.hmdp.shopdetail.domain.model.GuaranteeClaim;
import com.hmdp.shopdetail.domain.repository.GuaranteeClaimRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClaimPayoutNotificationServiceTest {

    @Test
    void should_resolve_user_id_from_claim_when_not_provided() {
        GuaranteeClaim claim = new GuaranteeClaim();
        claim.setUserId(42L);
        ClaimPayoutNotificationService service =
                new ClaimPayoutNotificationService(repositoryReturning(Optional.of(claim)));

        assertEquals(Long.valueOf(42L), service.resolveUserId("claim-1", null));
    }

    @Test
    void should_keep_provided_user_id_without_querying_claim() {
        GuaranteeClaimRepository repository = new GuaranteeClaimRepository() {
            @Override
            public GuaranteeClaim save(GuaranteeClaim claim) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<GuaranteeClaim> findByClaimNo(String claimNo) {
                throw new AssertionError("should not query claim when userId is provided");
            }
        };
        ClaimPayoutNotificationService service = new ClaimPayoutNotificationService(repository);

        assertEquals(Long.valueOf(99L), service.resolveUserId("claim-1", 99L));
    }

    @Test
    void should_return_null_when_claim_missing() {
        ClaimPayoutNotificationService service =
                new ClaimPayoutNotificationService(repositoryReturning(Optional.empty()));

        assertNull(service.resolveUserId("claim-1", null));
    }

    @Test
    void should_return_null_when_claim_has_no_user_id() {
        GuaranteeClaim claim = new GuaranteeClaim();
        ClaimPayoutNotificationService service =
                new ClaimPayoutNotificationService(repositoryReturning(Optional.of(claim)));

        assertNull(service.resolveUserId("claim-1", null));
    }

    @Test
    void should_not_throw_when_recipient_missing() {
        ClaimPayoutNotificationService service =
                new ClaimPayoutNotificationService(repositoryReturning(Optional.empty()));

        assertDoesNotThrow(() -> service.notifyUser("claim-1", null, "自动赔付已完成"));
    }

    @Test
    void should_not_throw_when_claim_lookup_fails() {
        GuaranteeClaimRepository repository = new GuaranteeClaimRepository() {
            @Override
            public GuaranteeClaim save(GuaranteeClaim claim) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<GuaranteeClaim> findByClaimNo(String claimNo) {
                throw new IllegalStateException("db down");
            }
        };
        ClaimPayoutNotificationService service = new ClaimPayoutNotificationService(repository);

        assertDoesNotThrow(() -> service.notifyUser("claim-1", null, "自动赔付已完成"));
    }

    private GuaranteeClaimRepository repositoryReturning(Optional<GuaranteeClaim> result) {
        return new GuaranteeClaimRepository() {
            @Override
            public GuaranteeClaim save(GuaranteeClaim claim) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<GuaranteeClaim> findByClaimNo(String claimNo) {
                return result;
            }
        };
    }
}
