import { Button, Center, Paper, PasswordInput, Stack, TextInput, Title } from '@mantine/core';

export function LoginPlaceholder() {
  return (
    <Center mih="100dvh" p="md">
      <Paper withBorder p="lg" w="100%" maw={400}>
        <form onSubmit={(event) => event.preventDefault()}>
          <Stack>
            <Title order={2}>Вход</Title>
            <TextInput label="Email" type="email" autoComplete="email" />
            <PasswordInput label="Пароль" autoComplete="current-password" />
            <Button type="submit">Войти</Button>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}
