import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import { errorText, moscowDeadline } from './helpers';

describe('moscowDeadline', () => {
  it('переводит местное время Москвы в UTC', () => {
    expect(moscowDeadline('2026-11-02T12:00')).toBe('2026-11-02T09:00:00.000Z');
  });

  it('отбрасывает секунды из поля браузера', () => {
    expect(moscowDeadline('2026-11-02T12:00:30')).toBe('2026-11-02T09:00:00.000Z');
  });

  it('пустое значение отдаёт серверу как есть', () => {
    expect(moscowDeadline('')).toBe('');
  });
});

describe('errorText', () => {
  it('для INVALID_STATE без detail показывает текст про состояние работы', () => {
    const error = new ApiError({ type: 'about:blank', title: 'Conflict', status: 409, code: 'INVALID_STATE' });
    expect(errorText(error)).toBe('Состояние работы изменилось. Обновите данные.');
  });
});
