package ru.campusrating.ledger;

/** fullName — снимок на момент проводки; автор null у проводки означает систему. */
public record Author(long id, String fullName) {}
