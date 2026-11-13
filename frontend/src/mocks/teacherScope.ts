import type { components } from '../api/schema';

type Schemas = components['schemas'];
function academicYear(date: Date) {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Moscow', year: 'numeric', month: 'numeric' }).formatToParts(date);
  const year = Number(parts.find((part) => part.type === 'year')?.value), month = Number(parts.find((part) => part.type === 'month')?.value);
  return year - (month < 9 ? 1 : 0);
}
function matches(grant: Schemas['AdminTeacherGrant'], group: Schemas['AdminStudyGroup'], year: number) {
  const course = year - group.admissionYear + 1;
  switch (grant.kind) {
    case 'GROUP': return grant.group.id === group.id;
    case 'FACULTY': return grant.faculty.id === group.faculty.id;
    case 'FACULTY_COURSE': return group.faculty.id === grant.faculty.id && course >= 1 && course <= 6 && course === grant.course;
    case 'COURSE': return course >= 1 && course <= 6 && course === grant.course;
  }
}
export function mockTeacherScope(approved: boolean, departmentFacultyId: number, groups: readonly Schemas['AdminStudyGroup'][], grants: readonly Schemas['AdminTeacherGrant'][], date: Date): Schemas['StudyGroupRef'][] {
  if (!approved) return [];
  const year = academicYear(date);
  const selected = groups.filter((group) => group.faculty.id === departmentFacultyId || grants.some((grant) => matches(grant, group, year)));
  return [...new Map(selected.map((group) => [group.id, { id: group.id, name: group.name, faculty: group.faculty }])).values()].sort((a, b) => a.name.localeCompare(b.name, 'ru') || a.id - b.id);
}
