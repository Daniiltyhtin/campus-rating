package ru.campusrating.identity.domain;

public sealed interface TeacherGrant {
    record Group(long groupId) implements TeacherGrant {
        public Group { requirePositive(groupId); }
    }
    record Faculty(long facultyId) implements TeacherGrant {
        public Faculty { requirePositive(facultyId); }
    }
    record FacultyCourse(long facultyId, int course) implements TeacherGrant {
        public FacultyCourse { requirePositive(facultyId); requireCourse(course); }
    }
    record Course(int course) implements TeacherGrant {
        public Course { requireCourse(course); }
    }
    private static void requirePositive(long id) {
        if (id <= 0) throw new IllegalArgumentException("Grant target must be positive");
    }
    private static void requireCourse(int course) {
        if (course < 1 || course > 6) throw new IllegalArgumentException("Course must be 1..6");
    }
}
