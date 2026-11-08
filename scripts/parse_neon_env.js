const fs = require('fs');
const env = fs.readFileSync('.env.local', 'utf8');
const match = env.match(/DATABASE_URL_UNPOOLED=(.+)/);
if (match) {
  const cleanUrl = match[1].trim().replace(/^["']|["']$/g, '');
  const u = new URL(cleanUrl);
  console.log('DB_HOST=' + u.hostname);
  console.log('DB_PORT=' + (u.port || '5432'));
  console.log('DB_NAME=' + u.pathname.replace(/^\//, ''));
  console.log('DB_MIGRATION_USER=' + u.username);
  console.log('DB_USER=' + u.username);
}
