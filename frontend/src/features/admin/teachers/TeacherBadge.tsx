import { useTeachers } from './queries';

export function TeacherBadge({ viewerId }: { viewerId: number }) {
  const query = useTeachers(viewerId), count = query.data?.items.filter((teacher) => !teacher.teacherApproved).length ?? 0;
  return !query.isError && count > 0 ? <span aria-label={`Заявки преподавателей: ${count}`} aria-live="polite">{count}</span> : null;
}
