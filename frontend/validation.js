function validateUsername(username) {
  return username.trim().length >= 3;
}

function validatePassword(password) {
  return password.length >= 8;
}

function validatePasswordsMatch(password, confirm) {
  return password === confirm;
}

function validateEmail(email) {
  return email.trim().length > 0 && email.includes('@');
}

function escapeHTML(str) {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// Export for Jest (Node), expose globally for browser
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    validateUsername,
    validatePassword,
    validatePasswordsMatch,
    validateEmail,
    escapeHTML
  };
}