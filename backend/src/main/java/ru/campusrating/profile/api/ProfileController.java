package ru.campusrating.profile.api;

import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.profile.api.ProfileViews.UserProfile;
import ru.campusrating.profile.internal.ProfileService;

@RestController
public class ProfileController {
    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/users/{userId}/profile")
    public UserProfile getUserProfile(@PathVariable @Min(1) long userId) {
        return profiles.profile(userId);
    }
}
