// ============================================================
// AEGIS — script.js
// Auth page (index.html): tab switching, validation, messages
// ============================================================

// --- Tab Switcher ---
function switchTab(tab) {
  const loginForm    = document.getElementById('login-form');
  const registerForm = document.getElementById('register-form');
  const tabs         = document.querySelectorAll('.tab-btn');

  if (tab === 'login') {
    loginForm.classList.remove('hidden');
    registerForm.classList.add('hidden');
    tabs[0].classList.add('active');
    tabs[1].classList.remove('active');
  } else {
    registerForm.classList.remove('hidden');
    loginForm.classList.add('hidden');
    tabs[1].classList.add('active');
    tabs[0].classList.remove('active');
  }
}

// --- Show inline message (error or success) ---
function showMessage(elementId, text, type) {
  const box = document.getElementById(elementId);
  if (!box) return;
  box.textContent = text;
  box.className = 'form-message ' + type; // 'error' or 'success'
  box.classList.remove('hidden');
}

function hideMessage(elementId) {
  const box = document.getElementById(elementId);
  if (box) box.classList.add('hidden');
}

// --- Login: client-side check before POST ---
function validateLogin() {
  const username = document.getElementById('login-user').value.trim();
  const password = document.getElementById('login-pass').value;

  if (!username || !password) {
    showMessage('login-message', 'Please fill in all fields.', 'error');
    return false; // stop form submit
  }

  hideMessage('login-message');
  return true; // allow form to POST to login.php
}

// --- Register: client-side check before POST ---
function validateRegister() {
  const username = document.getElementById('reg-username').value.trim();
  const email    = document.getElementById('reg-email').value.trim();
  const password = document.getElementById('reg-pass').value;
  const confirm  = document.getElementById('reg-confirm').value;

  if (!username || !email || !password || !confirm) {
    showMessage('register-message', 'Please fill in all fields.', 'error');
    return false;
  }

  if (username.length < 3) {
    showMessage('register-message', 'Username must be at least 3 characters.', 'error');
    return false;
  }

  if (password.length < 8) {
    showMessage('register-message', 'Password must be at least 8 characters.', 'error');
    return false;
  }

  if (password !== confirm) {
    showMessage('register-message', 'Passwords do not match.', 'error');
    return false;
  }

  hideMessage('register-message');
  return true; // allow form to POST to register.php
}

// --- Read URL params to display server-side messages ---
// PHP redirects back with ?error=... or ?success=... and ?tab=login/register
function readURLParams() {
  const params  = new URLSearchParams(window.location.search);
  const tab     = params.get('tab');
  const error   = params.get('error');
  const success = params.get('success');

  // Open the correct tab
  if (tab === 'register') {
    switchTab('register');
  } else {
    switchTab('login');
  }

  // Show error or success message in the right form
  if (error) {
    const targetId = tab === 'register' ? 'register-message' : 'login-message';
    showMessage(targetId, decodeURIComponent(error), 'error');
  }

  if (success) {
    showMessage('login-message', decodeURIComponent(success), 'success');
  }
}

// --- Run on page load ---
document.addEventListener('DOMContentLoaded', readURLParams);