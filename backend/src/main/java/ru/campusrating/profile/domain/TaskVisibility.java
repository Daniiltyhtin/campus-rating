package ru.campusrating.profile.domain;

import ru.campusrating.identity.Role;

/** Представление задачи в профиле студента по смотрящему (П8, мастер-R5). */
public enum TaskVisibility {
    PUBLIC, OWN, ADMIN;

    public static TaskVisibility of(long viewerId, Role viewerRole, long ownerId) {
        if (viewerId == ownerId) return OWN;
        return viewerRole == Role.ADMIN ? ADMIN : PUBLIC;
    }

    /** assignmentId отдаётся только себе и администратору: по нему открываются материалы сдачи. */
    public boolean revealsAssignment() {
        return this != PUBLIC;
    }
}
