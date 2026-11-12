import { expect } from '@playwright/test';

const mailApi = process.env.E2E_MAIL_API ?? 'http://127.0.0.1:8091';

type Message = { subject: string; mimeMessage: string };

function body(mime: string) {
  const split = mime.indexOf('\r\n\r\n');
  const headers = mime.slice(0, split).toLowerCase();
  const raw = mime.slice(split + 4);
  if (headers.includes('content-transfer-encoding: base64')) return Buffer.from(raw.replace(/\s+/g, ''), 'base64').toString('utf8');
  if (headers.includes('content-transfer-encoding: quoted-printable')) {
    const bytes = raw.replace(/=\r?\n/g, '').replace(/=([0-9A-F]{2})/gi, (_, hex: string) => String.fromCharCode(parseInt(hex, 16)));
    return Buffer.from(bytes, 'latin1').toString('utf8');
  }
  return Buffer.from(raw, 'latin1').toString('utf8');
}

export async function messages(email: string): Promise<Message[]> {
  const response = await fetch(`${mailApi}/api/user/${encodeURIComponent(email)}/messages/`);
  const data: unknown = await response.json();
  return Array.isArray(data) ? data as Message[] : [];
}

export async function mailToken(email: string, route: 'email/confirm' | 'password/set', previous = 0) {
  let token: string | undefined;
  await expect.poll(async () => {
    const found = (await messages(email)).slice(previous).map((message) => body(message.mimeMessage).match(new RegExp(`/${route}/([A-Za-z0-9_-]+)`))?.[1]).find(Boolean);
    token = found;
    return Boolean(found);
  }, { message: `письмо ${route} для ${email}`, timeout: 20_000 }).toBe(true);
  return token!;
}
