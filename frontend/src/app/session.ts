export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN';

export type Session = {
  userId: string;
  role: Role;
};
