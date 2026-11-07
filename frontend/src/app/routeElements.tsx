import { Text } from '@mantine/core';
import { Navigate } from 'react-router';
import { AppShell } from './AppShell';
import { useSession } from './session';

export function SessionGate() {
  const session = useSession();
  return session ? <AppShell session={session} /> : <Navigate to="/login" replace />;
}

export function SectionPlaceholder() {
  return <Text>Раздел появится</Text>;
}
