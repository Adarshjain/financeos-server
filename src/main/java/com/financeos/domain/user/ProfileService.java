package com.financeos.domain.user;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The user's name and birth date: the one-time Hello submit and later edits. Null input = leave as is;
 * a blank birth date clears it. Every read and write names the user id.
 */
@Service
public class ProfileService {

    public static final int MAX_NAME_LENGTH = 120;

    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    public ProfileService(UserRepository userRepository, ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.events = events;
    }

    @Transactional
    public User hello(UUID userId, @Nullable String displayName, @Nullable String birthDate, boolean termsAccepted) {
        return hello(userId, displayName, birthDate, termsAccepted, true);
    }

    /** {@code finish=false} saves the profile and terms but leaves onboarding open (see {@link #finishHello}). */
    @Transactional
    public User hello(UUID userId, @Nullable String displayName, @Nullable String birthDate, boolean termsAccepted, boolean finish) {
        if (!termsAccepted) {
            throw new ValidationException("You need to accept the Terms and Privacy policy");
        }
        User user = load(userId);
        boolean[] changed = apply(user, displayName == null || displayName.isBlank() ? null : displayName, birthDate);
        Instant now = AppTime.nowInstant();
        boolean firstHello = finish && user.getHelloDoneAt() == null;
        if (firstHello) {
            user.setHelloDoneAt(now);
        }
        user.setTermsAcceptedAt(now);
        userRepository.save(user);
        publish(userId, changed);
        if (firstHello) {
            events.publishEvent(new HelloCompletedEvent(userId));
        }
        return user;
    }

    /** Marks onboarding done and publishes {@link HelloCompletedEvent} once. Returns whether it was newly marked. */
    @Transactional
    public boolean completeHello(UUID userId) {
        User user = load(userId);
        if (user.getHelloDoneAt() != null) {
            return false;
        }
        user.setHelloDoneAt(AppTime.nowInstant());
        userRepository.save(user);
        events.publishEvent(new HelloCompletedEvent(userId));
        return true;
    }

    @Transactional
    public User finishHello(UUID userId) {
        completeHello(userId);
        return load(userId);
    }

    @Transactional
    public User updateProfile(UUID userId, @Nullable String displayName, @Nullable String birthDate) {
        User user = load(userId);
        boolean[] changed = apply(user, displayName, birthDate);
        userRepository.save(user);
        publish(userId, changed);
        return user;
    }

    /** Saves a Google-provided birth date only when the user has not typed one. Returns whether it saved. */
    @Transactional
    public boolean saveGoogleBirthDate(UUID userId, String birthDate) {
        User user = load(userId);
        if (user.getBirthDate() != null && user.getBirthDateSource() == BirthDateSource.TYPED) {
            return false;
        }
        String normalized = BirthDates.normalize(birthDate);
        boolean changed = !normalized.equals(user.getBirthDate());
        user.setBirthDate(normalized);
        user.setBirthDateSource(BirthDateSource.GOOGLE);
        userRepository.save(user);
        if (changed) {
            events.publishEvent(new ProfileChangedEvent(userId, false, true));
        }
        return true;
    }

    /** Returns {nameChanged, birthDateChanged}. */
    private boolean[] apply(User user, @Nullable String displayName, @Nullable String birthDate) {
        boolean nameChanged = false;
        boolean birthChanged = false;
        if (displayName != null && !displayName.isBlank()) {
            String name = displayName.trim();
            if (name.length() > MAX_NAME_LENGTH) {
                throw new ValidationException("Name must be 1 to " + MAX_NAME_LENGTH + " characters");
            }
            if (!name.equals(user.getDisplayName())) {
                user.setDisplayName(name);
                user.setNameEditedAt(AppTime.nowInstant());
                nameChanged = true;
            }
        } else if (displayName != null) {
            throw new ValidationException("Name must be 1 to " + MAX_NAME_LENGTH + " characters");
        }
        if (birthDate != null) {
            String normalized = birthDate.isBlank() ? null : BirthDates.normalize(birthDate);
            if (normalized == null) {
                birthChanged = user.getBirthDate() != null;
                user.setBirthDate(null);
                user.setBirthDateSource(null);
            } else {
                birthChanged = !normalized.equals(user.getBirthDate());
                if (birthChanged) {
                    user.setBirthDate(normalized);
                    user.setBirthDateSource(BirthDateSource.TYPED);
                }
            }
        }
        return new boolean[] {nameChanged, birthChanged};
    }

    private void publish(UUID userId, boolean[] changed) {
        if (changed[0] || changed[1]) {
            events.publishEvent(new ProfileChangedEvent(userId, changed[0], changed[1]));
        }
    }

    private User load(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));
    }
}
