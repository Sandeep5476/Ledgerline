const http = require('http');

const adminPassword = process.env.SEED_ADMIN_PASSWORD || 'AdminPassword_2026!';
const postData = JSON.stringify({ username: 'admin', password: adminPassword });

const loginReq = http.request({
  hostname: '127.0.0.1',
  port: 8080,
  path: '/api/auth/login',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(postData)
  }
}, res => {
  let body = '';
  res.on('data', chunk => body += chunk);
  res.on('end', () => {
    const token = JSON.parse(body).token;
    http.get({
      hostname: '127.0.0.1',
      port: 8080,
      path: '/api/admin/verify',
      headers: { 'Authorization': `Bearer ${token}` }
    }, verifyRes => {
      let verifyBody = '';
      verifyRes.on('data', chunk => verifyBody += chunk);
      verifyRes.on('end', () => {
        console.log(JSON.stringify(JSON.parse(verifyBody), null, 2));
      });
    });
  });
});

loginReq.write(postData);
loginReq.end();
