import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';

const read = (file) => fs.readFile(new URL(`../../${file}`, import.meta.url), 'utf8');

function serviceBlock(compose, name) {
  const match = compose.match(new RegExp(`^  ${name}:\\n((?:    .*\\n|\\n)*)`, 'm'));
  assert.ok(match, `service ${name} is missing`);
  return match[1];
}

test('U22: override publishes only web on loopback', async () => {
  const compose = await read('docker-compose.server.yml');
  assert.match(serviceBlock(compose, 'postgres'), /ports: !reset \[\]/);
  assert.match(serviceBlock(compose, 'backend'), /ports: !reset \[\]/);
  const web = serviceBlock(compose, 'web');
  assert.match(web, /ports: !override\n\s+- "127\.0\.0\.1:\$\{WEB_PORT:-8080\}:8080"/);
  assert.equal((web.match(/^\s+- /gm) ?? []).length, 1);
});

test('U22: override requires stand settings and runs demo behind a proxy', async () => {
  const backend = serviceBlock(await read('docker-compose.server.yml'), 'backend');
  for (const name of ['POSTGRES_PASSWORD', 'EMAIL_VERIFICATION', 'CAMPUS_PUBLIC_URL']) {
    assert.match(backend, new RegExp(`\\$\\{${name}:\\?`), `${name} must be required`);
  }
  assert.match(backend, /SPRING_PROFILES_ACTIVE: demo/);
  assert.match(backend, /SERVER_FORWARD_HEADERS_STRATEGY: native/);
  assert.match(backend, /MANAGEMENT_HEALTH_MAIL_ENABLED: "false"/);
  assert.match(backend, /path: deploy\/server\.env\n\s+required: true/);
  assert.doesNotMatch(backend, /SPRING_MAIL_/);
});

test('U22: env example has no secret values and server.env is ignored', async () => {
  const example = await read('deploy/server.env.example');
  for (const name of ['POSTGRES_PASSWORD', 'SPRING_MAIL_USERNAME', 'SPRING_MAIL_PASSWORD', 'CAMPUS_MAIL_FROM']) {
    assert.match(example, new RegExp(`^#?\\s?${name}=$`, 'm'), `${name} must be empty`);
  }
  assert.match(await read('.gitignore'), /^deploy\/server\.env$/m);
});

test('U22: inner nginx keeps the scheme set by the host proxy', async () => {
  const nginx = await read('frontend/nginx.conf');
  assert.match(nginx, /map \$http_x_forwarded_proto \$forwarded_proto \{\s+default \$http_x_forwarded_proto;\s+''\s+\$scheme;\s+\}/);
  assert.match(nginx, /proxy_set_header X-Forwarded-Proto \$forwarded_proto;/);
  assert.doesNotMatch(nginx, /X-Forwarded-Proto \$scheme/);
});

test('U22: host nginx example terminates TLS and proxies to loopback', async () => {
  const host = await read('deploy/nginx/campus.conf.example');
  assert.match(host, /listen 80;[\s\S]*return 301 https:\/\/\$host\$request_uri;/);
  assert.match(host, /listen 443 ssl;/);
  assert.match(host, /ssl_certificate \/etc\/letsencrypt\/live\//);
  assert.match(host, /proxy_pass http:\/\/127\.0\.0\.1:8080;/);
  assert.match(host, /proxy_set_header X-Forwarded-Proto https;/);
  assert.match(host, /client_max_body_size 12m;/);
});
