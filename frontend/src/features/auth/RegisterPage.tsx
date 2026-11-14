import { useMutation } from '@tanstack/react-query';
import { Button, NativeSelect, SegmentedControl, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { api } from '../../api/client';
import { FormLayout } from '../../app/layouts/PageLayouts';
import { ActionError, AuthLayout, ConfigNotices, LoginLink, PasswordField, RetryNotice } from './AuthLayout';
import { useAuthConfig, useFaculties, useFacultyOptions } from './authQueries';
import { registrationResult } from './contracts';
import { emailError, passwordError } from './formErrors';
import { useFormErrors } from './useAuthAction';

export function RegisterPage() {
  const [role, setRole] = useState<'STUDENT' | 'TEACHER'>('STUDENT');
  const [lastName, setLastName] = useState('');
  const [firstName, setFirstName] = useState('');
  const [middleName, setMiddleName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordRepeat, setPasswordRepeat] = useState('');
  const [facultyId, setFacultyId] = useState('');
  const [targetId, setTargetId] = useState('');
  const form = useFormErrors();
  const config = useAuthConfig();
  const faculties = useFaculties();
  const targets = useFacultyOptions(facultyId, role);
  const student = role === 'STUDENT';
  const targetField = student ? 'groupId' : 'departmentId';
  const mutation = useMutation({ retry: false, mutationFn: async () => {
    const common = { lastName: lastName.trim(), firstName: firstName.trim(), ...(middleName.trim() ? { middleName: middleName.trim() } : {}), email: email.trim(), password };
    const result = student
      ? await api.POST('/api/v1/auth/register/student', { body: { ...common, facultyId: Number(facultyId), groupId: Number(targetId) } })
      : await api.POST('/api/v1/auth/register/teacher', { body: { ...common, departmentId: Number(targetId) } });
    if (!result.response.ok) throw new Error('Не удалось зарегистрироваться');
    const outcome = registrationResult(result.data);
    setPassword('');
    setPasswordRepeat('');
    return outcome;
  }, onError: form.fromServer });
  const ready = config.isSuccess && faculties.isSuccess && targets.isSuccess && targetId !== '';
  const changeFaculty = (value: string) => { setFacultyId(value); setTargetId(''); form.clear('facultyId'); form.clear(targetField); };
  if (faculties.isPending) return <AuthLayout title="Регистрация" description="Создайте аккаунт студента или преподавателя."><Text role="status">Загрузка факультетов…</Text></AuthLayout>;
  return <AuthLayout title="Регистрация" description="Создайте аккаунт студента или преподавателя.">
    {mutation.data ? <Stack gap="md"><Text role="status" className="cr-success">{mutation.data.nextStep === 'LOGIN'
      ? 'Регистрация завершена. Теперь можно войти' : 'Регистрация завершена. Подтвердите email по ссылке из письма.'}</Text><LoginLink /></Stack>
      : <><SegmentedControl aria-label="Роль" value={role} styles={{ label: { color: 'var(--cr-text)' } }} onChange={(value) => {
        if (value !== 'STUDENT' && value !== 'TEACHER') return;
        setRole(value); setTargetId(''); form.setErrors({}); mutation.reset();
      }} data={[{ value: 'STUDENT', label: 'Студент' }, { value: 'TEACHER', label: 'Преподаватель' }]} disabled={mutation.isPending} />
        <FormLayout title="Ваши данные" onSubmit={(event) => {
          event.preventDefault();
          const errors = { lastName: lastName.trim().length < 1 || lastName.trim().length > 100 ? 'Укажите фамилию, не больше 100 символов' : undefined,
            firstName: firstName.trim().length < 1 || firstName.trim().length > 100 ? 'Укажите имя, не больше 100 символов' : undefined,
            middleName: middleName.trim().length > 100 ? 'Не больше 100 символов' : undefined,
            email: emailError(email.trim()), password: passwordError(password),
            passwordRepeat: passwordRepeat !== password ? 'Пароли не совпадают' : undefined };
          form.setErrors(Object.fromEntries(Object.entries(errors).filter((entry): entry is [string, string] => typeof entry[1] === 'string')));
          if (!ready || Object.values(errors).some(Boolean) || mutation.isPending) return;
          mutation.mutate();
        }}><TextInput label="Фамилия" autoComplete="family-name" value={lastName} onChange={(event) => { setLastName(event.currentTarget.value); form.clear('lastName'); }} error={form.errors.lastName} />
          <TextInput label="Имя" autoComplete="given-name" value={firstName} onChange={(event) => { setFirstName(event.currentTarget.value); form.clear('firstName'); }} error={form.errors.firstName} />
          <TextInput label="Отчество" description="Если есть" autoComplete="additional-name" value={middleName} onChange={(event) => { setMiddleName(event.currentTarget.value); form.clear('middleName'); }} error={form.errors.middleName} />
          <TextInput label="Email" type="email" autoComplete="email" value={email} onChange={(event) => { setEmail(event.currentTarget.value); form.clear('email'); }} error={form.errors.email} />
          <PasswordField label="Пароль" autoComplete="new-password" description="От 8 символов, не больше 72 байт UTF-8" value={password} onChange={(event) => { setPassword(event.currentTarget.value); form.clear('password'); }} error={form.errors.password} />
          <PasswordField label="Повторите пароль" autoComplete="new-password" value={passwordRepeat} onChange={(event) => { setPasswordRepeat(event.currentTarget.value); form.clear('passwordRepeat'); }} error={form.errors.passwordRepeat} />
          <NativeSelect label="Факультет" value={facultyId} styles={{ error: { color: 'var(--cr-status-overdue)' } }} onChange={(event) => changeFaculty(event.currentTarget.value)} disabled={!faculties.isSuccess || mutation.isPending} error={form.errors.facultyId}
            data={[{ value: '', label: 'Выберите факультет' }, ...(faculties.data ?? []).map((item) => ({ value: String(item.id), label: item.name }))]} />
          {faculties.isPending && <Text role="status">Загрузка факультетов…</Text>}
          {faculties.isError && <RetryNotice message="Не удалось загрузить факультеты" retry={() => { void faculties.refetch(); }} />}
          {faculties.isSuccess && faculties.data.length === 0 && <Text>Факультетов пока нет. Обратитесь к администратору.</Text>}
          <NativeSelect label={student ? 'Группа' : 'Кафедра'} value={targetId} styles={{ error: { color: 'var(--cr-status-overdue)' } }} onChange={(event) => { setTargetId(event.currentTarget.value); form.clear(targetField); }}
            disabled={!facultyId || !targets.isSuccess || mutation.isPending} error={form.errors[targetField]}
            data={[{ value: '', label: student ? 'Выберите группу' : 'Выберите кафедру' }, ...(targets.data ?? []).map((item) => ({ value: String(item.id), label: item.name }))]} />
          {facultyId && targets.isPending && <Text role="status">Загрузка справочника…</Text>}
          {targets.isError && <RetryNotice message="Не удалось загрузить справочник" retry={() => { void targets.refetch(); }} />}
          {targets.isSuccess && targets.data.length === 0 && <Text>{student ? 'Групп на этом факультете пока нет.' : 'Кафедр на этом факультете пока нет.'} Обратитесь к администратору.</Text>}
          {!student && <Text className="cr-caption">После регистрации заявку подтвердит администратор. Выбор кафедры не открывает доступ к группам.</Text>}
          {config.isPending && <Text role="status">Загрузка настроек стенда…</Text>}
          {config.isError && <RetryNotice message="Не удалось загрузить настройки стенда" retry={() => { void config.refetch(); }} />}
          {mutation.error != null && <ActionError error={mutation.error} onReset={mutation.reset} />}
          <Button type="submit" loading={mutation.isPending} disabled={!ready || mutation.isPending}>Зарегистрироваться</Button>
        </FormLayout><LoginLink /></>}
    <ConfigNotices config={config.data} />
  </AuthLayout>;
}
