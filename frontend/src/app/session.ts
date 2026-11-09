import { createContext, useContext } from 'react';

export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN';

export type Session = {
  userId: string;
  role: Role;
};

const SessionContext = createContext<Session | null>(null);

export const SessionProvider = SessionContext.Provider;

export function useSession(): Session | null {
  return useContext(SessionContext);
}
