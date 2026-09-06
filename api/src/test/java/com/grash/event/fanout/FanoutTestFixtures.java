package com.grash.event.fanout;

import com.grash.model.Company;
import com.grash.model.CompanySettings;
import com.grash.model.GeneralPreferences;
import com.grash.model.Role;
import com.grash.model.Subscription;
import com.grash.model.SubscriptionPlan;
import com.grash.model.User;
import com.grash.model.UserSettings;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;

import java.util.HashSet;

/**
 * The smallest company, role and user the fan-out handlers need.
 *
 * <p>Shared because all three handlers reload the same shapes — a company for the language, a
 * user for the audience — and three copies of this drift apart. It builds nothing a test has to
 * reason about: no permissions, no plan features, no preferences beyond the defaults, so anything
 * a test depends on is set in that test and visible there.
 */
final class FanoutTestFixtures {

    private FanoutTestFixtures() {
    }

    static Company company(Long id) {
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .id(1L).name("Pro").features(new HashSet<>()).build();
        Subscription subscription = Subscription.builder().id(1L).subscriptionPlan(plan).build();
        CompanySettings settings = new CompanySettings();
        settings.setId(id);
        settings.setGeneralPreferences(new GeneralPreferences(settings));
        Company company = new Company("TestCo", 10, subscription);
        company.setId(id);
        company.setCompanySettings(settings);
        return company;
    }

    static Role role(RoleCode code) {
        return Role.builder()
                .id(1L)
                .name(code.name())
                .roleType(RoleType.ROLE_CLIENT)
                .code(code)
                .createPermissions(new HashSet<>())
                .viewPermissions(new HashSet<>())
                .viewOtherPermissions(new HashSet<>())
                .editOtherPermissions(new HashSet<>())
                .deleteOtherPermissions(new HashSet<>())
                .build();
    }

    static User user(Long id, Company company, RoleCode code) {
        User user = new User();
        user.setId(id);
        user.setFirstName("U" + id);
        user.setLastName("L" + id);
        user.setEmail("u" + id + "@test.com");
        user.setRole(role(code));
        user.setCompany(company);
        user.setEnabled(true);
        user.setUserSettings(new UserSettings());
        return user;
    }
}
