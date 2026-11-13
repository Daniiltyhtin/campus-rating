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
  for (const name of ['POSTGRES_PASSWORD', 'EMAIL_VERIFICATION', 'CAMPUS_PUBLIC_URL', 'DEMO_PASSWORD_PREFIX']) {
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

test('U22: env example asks for a demo password prefix and pins the project name', async () => {
  const example = await read('deploy/server.env.example');
  assert.match(example, /^DEMO_PASSWORD_PREFIX=$/m);
  assert.match(example, /^COMPOSE_PROJECT_NAME=campus-rating$/m);
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
  assert.match(host, /listen 443 ssl http2;/);
  assert.doesNotMatch(host, /http2 on;/);
  assert.match(host, /ssl_certificate \/etc\/letsencrypt\/live\//);
  assert.match(host, /proxy_pass http:\/\/127\.0\.0\.1:WEB_PORT;/);
  assert.doesNotMatch(host, /8080/);
  assert.match(host, /proxy_set_header X-Forwarded-Proto https;/);
  assert.match(host, /client_max_body_size 12m;/);
});

function locationBlock(config, pattern) {
  const start = config.indexOf(`location ${pattern} {`);
  assert.notEqual(start, -1, `location ${pattern} is missing`);
  return config.slice(start, config.indexOf('}', start) + 1);
}

function proxyLines(block) {
  return block.split('\n').map((line) => line.trim()).filter((line) => /^proxy_/.test(line));
}

test('U22: host nginx limits POST auth requests per client and answers 429', async () => {
  const host = await read('deploy/nginx/campus.conf.example');
  const zone = host.indexOf('limit_req_zone');
  assert.ok(zone !== -1 && zone < host.indexOf('server {'), 'zone must be declared outside server blocks');
  assert.match(host, /map \$request_method \$campus_auth_client \{\s+POST\s+\$binary_remote_addr;\s+default\s+"";\s+\}/);
  assert.match(host, /limit_req_zone \$campus_auth_client zone=campus_auth:10m rate=10r\/m;/);
  assert.match(host, /limit_req_status 429;/);
  const auth = locationBlock(host, '~ ^/api/v1/auth/(login|register/|email/|password)');
  assert.match(auth, /limit_req zone=campus_auth burst=10 nodelay;/);
  const pattern = new RegExp('^/api/v1/auth/(login|register/|email/|password)');
  for (const path of ['/api/v1/auth/login', '/api/v1/auth/register/student', '/api/v1/auth/register/teacher',
    '/api/v1/auth/email/verification', '/api/v1/auth/email/confirmation', '/api/v1/auth/password']) {
    assert.match(path, pattern, `${path} must be limited`);
  }
  for (const path of ['/api/v1/auth/csrf', '/api/v1/auth/config', '/api/v1/auth/me', '/api/v1/auth/logout', '/login']) {
    assert.doesNotMatch(path, pattern, `${path} must not be limited`);
  }
  assert.deepEqual(proxyLines(auth), proxyLines(locationBlock(host, '/')));
  assert.equal((host.match(/limit_req zone=/g) ?? []).length, 1);
});

test('U22: inner nginx has no rate limit for e2e registrations', async () => {
  assert.doesNotMatch(await read('frontend/nginx.conf'), /limit_req/);
});

test('U22: host nginx sends HSTS and hides its version', async () => {
  const host = await read('deploy/nginx/campus.conf.example');
  const tls = host.slice(host.indexOf('listen 443'));
  assert.match(tls, /server_tokens off;/);
  assert.match(tls, /add_header Strict-Transport-Security "max-age=31536000" always;/);
  assert.equal((tls.match(/proxy_pass /g) ?? []).length, (tls.match(/proxy_hide_header Strict-Transport-Security;/g) ?? []).length, 'Spring HSTS must not duplicate the host header');
});

test('U22: stand restarts by itself and keeps data unless reset is requested', async () => {
  const compose = await read('docker-compose.server.yml');
  for (const name of ['postgres', 'backend', 'web']) {
    assert.match(serviceBlock(compose, name), /^    restart: unless-stopped$/m, `${name} must restart`);
  }
  assert.match(serviceBlock(compose, 'backend'), /CAMPUS_DEMO_RESET: \$\{CAMPUS_DEMO_RESET:-false\}/);
  assert.doesNotMatch(serviceBlock(await read('docker-compose.yml'), 'backend'), /CAMPUS_DEMO_RESET/);
  assert.match(await read('deploy/server.env.example'), /^CAMPUS_DEMO_RESET=false$/m);
});

test('U22: inner nginx sends security headers once on every location', async () => {
  const nginx = await read('frontend/nginx.conf');
  const server = nginx.slice(nginx.indexOf('server {'), nginx.indexOf('location '));
  assert.match(server, /server_tokens off;/);
  assert.match(server, /add_header X-Content-Type-Options nosniff always;/);
  assert.match(server, /add_header Referrer-Policy no-referrer always;/);
  assert.match(server, /add_header Content-Security-Policy "frame-ancestors 'none'" always;/);
  const locations = nginx.slice(nginx.indexOf('location '));
  assert.doesNotMatch(locations, /add_header/, 'add_header in a location drops the server headers');
  assert.match(locationBlock(nginx, '/api/'), /proxy_hide_header X-Content-Type-Options;/);
});
