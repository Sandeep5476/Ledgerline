const fs = require('fs');
const crypto = require('crypto');

const envFile = '.env.local';
let envContent = fs.readFileSync(envFile, 'utf8');

let appPassword = '';
const matchAppPwd = envContent.match(/APP_DB_PASSWORD=(.+)/);
if (matchAppPwd) {
  appPassword = matchAppPwd[1].trim().replace(/^["']|["']$/g, '');
} else {
  appPassword = crypto.randomBytes(18).toString('base64url');
  envContent += `\nAPP_DB_USER=ledger_app\nAPP_DB_PASSWORD=${appPassword}\n`;
  fs.writeFileSync(envFile, envContent, 'utf8');
  console.log('Generated new APP_DB_PASSWORD in .env.local');
}

// Ensure JWT_SECRET and SEED passwords exist in .env.local
let updated = false;
if (!envContent.includes('JWT_SECRET=')) {
  const jwtSecret = crypto.randomBytes(32).toString('hex');
  envContent += `\nJWT_SECRET=${jwtSecret}\n`;
  updated = true;
}
if (!envContent.includes('SEED_ADMIN_PASSWORD=')) {
  envContent += `\nSEED_ADMIN_PASSWORD=AdminPassword_2026!\n`;
  updated = true;
}
if (!envContent.includes('SEED_HOLDER_PASSWORD=')) {
  envContent += `\nSEED_HOLDER_PASSWORD=HolderPassword_2026!\n`;
  updated = true;
}
if (!envContent.includes('SEED_AUDITOR_PASSWORD=')) {
  envContent += `\nSEED_AUDITOR_PASSWORD=AuditorPassword_2026!\n`;
  updated = true;
}
if (updated) {
  fs.writeFileSync(envFile, envContent, 'utf8');
  console.log('Appended default secrets to .env.local');
}

console.log('Environment configuration verified.');

