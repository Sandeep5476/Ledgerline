const fs = require('fs');
const { spawn } = require('child_process');

const envFile = '.env.local';
if (!fs.existsSync(envFile)) {
  console.error('.env.local not found');
  process.exit(1);
}

const envContent = fs.readFileSync(envFile, 'utf8');
const envVars = { ...process.env };

for (const line of envContent.split(/\r?\n/)) {
  const trimmed = line.trim();
  if (!trimmed || trimmed.startsWith('#')) continue;
  const idx = trimmed.indexOf('=');
  if (idx !== -1) {
    const key = trimmed.slice(0, idx).trim();
    const val = trimmed.slice(idx + 1).trim().replace(/^["']|["']$/g, '');
    envVars[key] = val;
  }
}

// Parse DATABASE_URL_UNPOOLED if present
if (envVars.DATABASE_URL_UNPOOLED) {
  const u = new URL(envVars.DATABASE_URL_UNPOOLED);
  envVars.DB_HOST = u.hostname;
  envVars.DB_PORT = u.port || '5432';
  envVars.DB_NAME = u.pathname.replace(/^\//, '');
  envVars.DB_SSLMODE = 'require';
  envVars.DB_MIGRATION_USER = decodeURIComponent(u.username);
  envVars.DB_MIGRATION_PASSWORD = decodeURIComponent(u.password);
  envVars.DB_USER = envVars.DB_MIGRATION_USER;
  envVars.DB_PASSWORD = envVars.DB_MIGRATION_PASSWORD;
}

envVars.APP_DB_USER = envVars.APP_DB_USER || 'ledger_app';
envVars.SPRING_PROFILES_ACTIVE = 'prod';
envVars.PORT = envVars.PORT || '8080';

console.log('Starting Spring Boot application with configuration:');
console.log(`  SPRING_PROFILES_ACTIVE: ${envVars.SPRING_PROFILES_ACTIVE}`);
console.log(`  DB_HOST: ${envVars.DB_HOST}`);
console.log(`  DB_PORT: ${envVars.DB_PORT}`);
console.log(`  DB_NAME: ${envVars.DB_NAME}`);
console.log(`  DB_SSLMODE: ${envVars.DB_SSLMODE}`);
console.log(`  DB_MIGRATION_USER: ${envVars.DB_MIGRATION_USER}`);
console.log(`  DB_MIGRATION_PASSWORD: [REDACTED]`);
console.log(`  APP_DB_USER: ${envVars.APP_DB_USER}`);
console.log(`  APP_DB_PASSWORD: [REDACTED]`);
console.log(`  PORT: ${envVars.PORT}`);

const child = spawn('java', ['-jar', 'backend/target/ledgerline-backend-0.0.1-SNAPSHOT.jar'], {
  env: envVars,
  stdio: 'inherit'
});

child.on('exit', (code, signal) => {
  console.log(`Application exited with code ${code} (signal: ${signal})`);
  process.exit(code || 0);
});

